// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+Sync.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ContactSyncResult
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/** Contact watermark value meaning no contact sync has ever succeeded. */
const val NO_CONTACT_WATERMARK: UInt = 0u

/** `since` for a prune-free full contact fetch: the device reports every contact, local-only rows stay. */
val PRUNE_FREE_FULL_FETCH_SINCE: Instant = Instant.EPOCH

/** Max lead over the plausibility reference before a stored watermark is unusable (2 days). */
val CONTACT_WATERMARK_PLAUSIBILITY_SKEW: Duration = 2.days

/** Bound for waiting out an advert-owned contact-sync claim (above the 180 s contact stream timeout). */
val ADVERT_CONTACT_SYNC_WAIT_TIMEOUT: Duration = 200.seconds

/** Developer-facing reason when the advert claim wait hits its bound. */
const val ADVERT_CONTACT_SYNC_WAIT_TIMED_OUT_MESSAGE = "Timed out waiting for background contact sync"

/** Seconds the incremental `since` filter is rewound from the stored watermark (device filter is `>`). */
private const val INCREMENTAL_SINCE_OVERLAP_SECONDS = 1L

/** The `since` filter for an incremental contact fetch from a stored watermark. */
internal fun incrementalSince(watermark: UInt): Instant =
    Instant.ofEpochSecond(watermark.toLong() - INCREMENTAL_SINCE_OVERLAP_SECONDS)

/**
 * Decides whether a stored contact-sync watermark can drive incremental sync. The reference clock is the
 * phone (the radio is held within 5 s of it on connect). A stamp more than
 * [CONTACT_WATERMARK_PLAUSIBILITY_SKEW] ahead is [ContactWatermarkUse.Invalid]; the stored value is never
 * rewritten downward here.
 */
fun contactWatermarkUse(fromLastContactSync: UInt?, referenceNow: Instant): ContactWatermarkUse {
    val raw = fromLastContactSync
    if (raw == null || raw == NO_CONTACT_WATERMARK) return ContactWatermarkUse.None
    val referenceSeconds = referenceNow.uint32Seconds()
    val maxSkew = CONTACT_WATERMARK_PLAUSIBILITY_SKEW.inWholeSeconds.toUInt()
    val upperBound = if (referenceSeconds > UInt.MAX_VALUE - maxSkew) UInt.MAX_VALUE else referenceSeconds + maxSkew
    return if (raw > upperBound) ContactWatermarkUse.Invalid(raw) else ContactWatermarkUse.Incremental(raw)
}

/** Removes and returns every advert-claim waiter (claim cleared or disconnect). */
internal fun SyncCoordinatorState.takeAdvertSyncWaiters(): List<CompletableDeferred<Unit>> {
    val waiters = advertSyncWaiters.values.toList()
    advertSyncWaiters.clear()
    return waiters
}

/** Logs once when invalid-watermark recovery is spent and rounds fall back to the stored stamp. */
internal fun SyncCoordinator.logInvalidWatermarkRecoveryExhaustedIfNeeded(radioId: RadioId, stored: UInt) {
    val shouldLog = locked {
        if (invalidWatermarkRecoveryExhaustedLoggedRadioID == radioId) false else {
            invalidWatermarkRecoveryExhaustedLoggedRadioID = radioId
            true
        }
    }
    if (!shouldLog) return
    log(
        DebugLogLevel.NOTICE,
        "[Sync] Invalid watermark recovery exhausted for radio ${radioId.canonicalString}: stored $stored still " +
            "implausible — using stored stamp; manual contact refresh required",
    )
}

/** Runs contact sync and writes back the watermark when the result carries one. */
internal suspend fun SyncCoordinator.syncContactsPhase(
    radioId: RadioId,
    dataStore: PersistenceStoreProtocol,
    contactService: ContactServiceProtocol,
    since: Instant?,
    forceFullSync: Boolean = false,
): ContactSyncResult {
    if (since != null) log(DebugLogLevel.INFO, "[Sync] Phase start: contacts (incremental, watermark=$since)")
    val contactStart = clock.elapsed()
    val result = contactService.syncContacts(radioId, since)
    val syncType = if (result.isIncremental) "incremental" else "full"
    val forced = if (forceFullSync) ", forced" else ""
    log(
        DebugLogLevel.INFO,
        "[Sync] Phase end: contacts - ${result.contactsReceived} ($syncType$forced) in ${clock.elapsed() - contactStart}",
    )
    notifyContactsChanged()

    if (result.lastSyncTimestamp > 0u) dataStore.updateDeviceLastContactSync(radioId, result.lastSyncTimestamp)
    if (since == null) locked { fullContactSyncCompletedRadioID = radioId }
    return result
}

// MARK: - Advert claim wait

/**
 * Suspends while an advert-driven delta sync holds the claim, so background advert work cannot turn a
 * full sync, connection setup, or channel-only retry into a silent skip.
 *
 * Throws `CancellationException` when the caller is cancelled and [SyncCoordinatorError.SyncFailed] when
 * [timeout] elapses with the claim still held.
 */
suspend fun SyncCoordinator.waitForAdvertContactSync(timeout: Duration? = null) {
    val bound = timeout ?: locked { advertContactSyncWaitTimeoutOverride } ?: ADVERT_CONTACT_SYNC_WAIT_TIMEOUT
    val deadline = clock.elapsed() + bound
    while (true) {
        currentCoroutineContext().ensureActive()
        val remaining = deadline - clock.elapsed()
        // Register in the same critical section as the claim check: no gap for a missed release.
        val step = locked {
            when {
                !(isSyncInProgress && advertContactSyncActive) -> AdvertWaitStep.Released
                remaining <= Duration.ZERO -> AdvertWaitStep.TimedOut
                else -> {
                    val id = nextAdvertSyncWaiterID++
                    AdvertWaitStep.Park(id, CompletableDeferred<Unit>().also { advertSyncWaiters[id] = it })
                }
            }
        }
        when (step) {
            AdvertWaitStep.Released -> return
            AdvertWaitStep.TimedOut -> {
                log(DebugLogLevel.WARNING, "Timed out waiting for advert contact sync to release claim")
                throw SyncCoordinatorError.SyncFailed(ADVERT_CONTACT_SYNC_WAIT_TIMED_OUT_MESSAGE)
            }
            is AdvertWaitStep.Park -> awaitAdvertClaimRelease(step.id, step.deferred, remaining)
        }
    }
}

private sealed interface AdvertWaitStep {
    data object Released : AdvertWaitStep
    data object TimedOut : AdvertWaitStep
    class Park(val id: Long, val deferred: CompletableDeferred<Unit>) : AdvertWaitStep
}

/** Waits for release, cancellation, or [timeout]; the first finisher wins and the waiter is unregistered. */
private suspend fun SyncCoordinator.awaitAdvertClaimRelease(id: Long, deferred: CompletableDeferred<Unit>, timeout: Duration) {
    try {
        coroutineScope {
            val timer = launch {
                clock.sleep(timeout)
                deferred.completeExceptionally(SyncCoordinatorError.SyncFailed(ADVERT_CONTACT_SYNC_WAIT_TIMED_OUT_MESSAGE))
            }
            try {
                deferred.await()
            } finally {
                timer.cancel()
            }
        }
    } finally {
        locked { advertSyncWaiters.remove(id) }
    }
}

/**
 * Waits out an advert claim, then marks a user-initiated contact refresh active with no suspension in
 * between, so no delta can interleave between observe and claim.
 */
suspend fun SyncCoordinator.claimManualContactSync(timeout: Duration? = null) {
    // One deadline across re-waits, so repeated advert re-claims cannot extend the bound.
    val bound = timeout ?: locked { advertContactSyncWaitTimeoutOverride } ?: ADVERT_CONTACT_SYNC_WAIT_TIMEOUT
    val deadline = clock.elapsed() + bound
    while (true) {
        waitForAdvertContactSync(deadline - clock.elapsed())
        val claimed = locked {
            if (isSyncInProgress && advertContactSyncActive) false else {
                manualContactSyncActive = true
                true
            }
        }
        if (claimed) return
    }
}

/** Marks a user-initiated contact refresh so advert delta sync returns busy. */
fun SyncCoordinator.setManualContactSyncActive(active: Boolean) = locked { manualContactSyncActive = active }

/** Test hook: shortens the advert claim wait bound used by default parameters. */
fun SyncCoordinator.setAdvertContactSyncWaitTimeoutOverride(timeout: Duration?) =
    locked { advertContactSyncWaitTimeoutOverride = timeout }

// MARK: - Advert delta sync

/**
 * Advert-driven contact delta sync. Claims the sync guard as advert-owned so full sync waits rather than
 * skips. Both modes need proof that a full contact fetch already succeeded for this radio; otherwise a
 * watermark written here would make later full syncs incremental and leave ghost contacts.
 *
 * @param fullRefetch true uses the epoch-0 prune-free full fetch; false uses the stored watermark.
 */
suspend fun SyncCoordinator.performAdvertContactSync(
    fullRefetch: Boolean,
    radioId: RadioId,
    dataStore: PersistenceStoreProtocol,
    contactService: ContactServiceProtocol,
): SyncAdvertContactSyncOutcome {
    val claim = locked {
        when {
            manualContactSyncActive -> -1L
            isSyncInProgress -> -2L
            else -> {
                isSyncInProgress = true
                advertContactSyncActive = true
                syncClaimGeneration += 1
                syncClaimGeneration
            }
        }
    }
    if (claim == -1L) {
        log(DebugLogLevel.INFO, "Advert contact sync deferred because a manual contact refresh is active")
        return SyncAdvertContactSyncOutcome.BUSY
    }
    if (claim == -2L) {
        log(DebugLogLevel.INFO, "Advert contact sync deferred because sync is already active")
        return SyncAdvertContactSyncOutcome.BUSY
    }
    try {
        return runAdvertContactSync(fullRefetch, radioId, dataStore, contactService)
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.WARNING, "Advert contact sync failed: ${failure.syncDescription()}")
        return SyncAdvertContactSyncOutcome.FAILED
    } finally {
        val waiters = locked {
            if (syncClaimGeneration == claim) {
                isSyncInProgress = false
                advertContactSyncActive = false
            }
            takeAdvertSyncWaiters()
        }
        waiters.forEach { it.complete(Unit) }
    }
}

private suspend fun SyncCoordinator.runAdvertContactSync(
    fullRefetch: Boolean,
    radioId: RadioId,
    dataStore: PersistenceStoreProtocol,
    contactService: ContactServiceProtocol,
): SyncAdvertContactSyncOutcome {
    val device = dataStore.fetchDevice(radioId)
    val watermarkUse = contactWatermarkUse(device?.lastContactSync, clock.now())
    val hasCompletedFullSync = locked { fullContactSyncCompletedRadioID == radioId }
    // `.invalid` still proves a prior stamp exists, so recovery may run.
    if (watermarkUse == ContactWatermarkUse.None && !hasCompletedFullSync) {
        log(
            DebugLogLevel.NOTICE,
            "[Sync] Advert contact sync skipped: no contact watermark yet (connect sync contacts phase has not succeeded)",
        )
        return SyncAdvertContactSyncOutcome.NOT_READY
    }

    var ranInvalidWatermarkRecovery = false
    val since: Instant = if (fullRefetch) {
        log(DebugLogLevel.INFO, "[Sync] Advert contact sync: prune-free full refetch")
        PRUNE_FREE_FULL_FETCH_SINCE
    } else {
        when (watermarkUse) {
            ContactWatermarkUse.None -> {
                log(DebugLogLevel.INFO, "[Sync] Advert contact sync: prune-free full fetch (full sync found no contacts to stamp)")
                PRUNE_FREE_FULL_FETCH_SINCE
            }
            is ContactWatermarkUse.Incremental -> incrementalSince(watermarkUse.watermark)
            is ContactWatermarkUse.Invalid -> {
                // Prune-free epoch-0 only: background advert work must never prune. One recovery per radio.
                if (locked { invalidWatermarkRecoveryRadioID } == radioId) {
                    logInvalidWatermarkRecoveryExhaustedIfNeeded(radioId, watermarkUse.stored)
                    incrementalSince(watermarkUse.stored)
                } else {
                    ranInvalidWatermarkRecovery = true
                    log(
                        DebugLogLevel.NOTICE,
                        "[Sync] Advert contact sync: invalid watermark ${watermarkUse.stored} exceeds phone reference + " +
                            "${CONTACT_WATERMARK_PLAUSIBILITY_SKEW.inWholeSeconds}s — one-shot prune-free full recovery, store not rewritten",
                    )
                    PRUNE_FREE_FULL_FETCH_SINCE
                }
            }
        }
    }

    syncContactsPhase(radioId, dataStore, contactService, since)
    if (ranInvalidWatermarkRecovery) locked { invalidWatermarkRecoveryRadioID = radioId }
    return SyncAdvertContactSyncOutcome.SYNCED
}
