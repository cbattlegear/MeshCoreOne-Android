// PortedFrom: MC1Services/Sources/MC1Services/Services/AdvertisementService+DeltaSync.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactIdentity
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// MARK: - Debounced Contact Delta Sync

/** Test entry point (Swift `scheduleDeltaSync()`): arms a round unless one is already registered. */
internal fun AdvertisementService.scheduleDeltaSync() = locked { scheduleDeltaSyncLocked(this) }

internal fun AdvertisementService.scheduleDeltaSyncLocked(state: AdvertisementServiceState) {
    if (state.deltaSyncJob != null) return
    var fireAt = clock.now() + advertSyncDebounce
    state.lastDeltaSyncEnd?.let { fireAt = maxOf(fireAt, it + advertSyncMinInterval) }
    armRoundLocked(state, fireAt)
}

/** Re-arms after `BUSY` with a short backoff so claim holds do not spin at zero interval. */
private fun AdvertisementService.scheduleBusyRetryLocked(state: AdvertisementServiceState) {
    if (state.deltaSyncJob != null || !state.hasPendingDeltaSyncWork) return
    armRoundLocked(state, clock.now() + advertSyncBusyBackoff)
}

private fun AdvertisementService.armRoundLocked(state: AdvertisementServiceState, fireAt: Duration) {
    state.deltaSyncGeneration += 1
    val generation = state.deltaSyncGeneration
    val round = scope.launch(start = CoroutineStart.LAZY) { runDeltaSync(fireAt, generation) }
    // Safety net for a round cancelled before it ever ran; a stale generation is a no-op.
    round.invokeOnCompletion { finishRound(generation) }
    state.deltaSyncJob = round
    state.jobsToStart += round
}

/**
 * Clears the registered round only while [generation] still owns it. A cancelled round that resumes
 * after a re-arm must not wipe the new task.
 */
internal fun AdvertisementService.finishRound(generation: Long) = locked { finishRoundLocked(this, generation) }

private fun finishRoundLocked(state: AdvertisementServiceState, generation: Long) {
    if (state.deltaSyncGeneration == generation) state.deltaSyncJob = null
}

private class DrainedRound(
    val handler: AdvertDeltaSyncHandler,
    val radioId: RadioId?,
    val drained: Set<Bytes>,
    val receiveTimes: Map<Bytes, Instant>,
    val pathKeys: Set<Bytes>,
    val fullRefetch: Boolean,
)

private suspend fun AdvertisementService.runDeltaSync(fireAt: Duration, generation: Long) {
    val roundJob = currentCoroutineContext().job
    try {
        clock.sleepUntil(fireAt)
    } catch (cancelled: CancellationException) {
        finishRound(generation)
        throw cancelled
    }
    // Swift checks cancellation only where `Task.isCancelled` is read; store hops after the fire never
    // abort the round, so rollback and recency stamping always run. The handler alone sees cancellation.
    withContext(NonCancellable) { runFiredRound(roundJob, generation) }
}

private suspend fun AdvertisementService.runFiredRound(roundJob: Job, generation: Long) {
    if (roundJob.isCancelled || isSyncingContacts || !isInForeground()) {
        finishRound(generation)
        return
    }
    val round = drainRound(roundJob, generation) ?: return
    val radioId = round.radioId

    // The pre-round key set gates notifications and escalation only; a failed read must not invent
    // notifications. Adoption uses every drained key that has a row after the exchange.
    val preRoundKnownKeys: Set<Bytes>? = radioId?.let {
        try {
            dataStore.fetchContactPublicKeys(it).toSet()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(
                DebugLogLevel.ERROR,
                "Contact key snapshot failed; suppressing new-contact notifications this round: ${failure.message}",
            )
            null
        }
    }
    val unknownAtStart = preRoundKnownKeys?.let { round.drained - it } ?: emptySet()
    // Snapshot lastModified for known path keys so an empty successful incremental is detectable.
    val preRoundPathLastModified = snapshotPathLastModified(round.pathKeys, radioId)

    val outcome = invokeHandler(roundJob, round)

    // Rollback before the teardown guard: a failed or cancelled round may already have committed early
    // batches, and an incremental sync never prunes them.
    rollBackContactsDeletedDuringSync(radioId)

    if (outcome == null || roundJob.isCancelled || deltaSyncHandler == null) {
        stampAdvertReceiveTimes(round.receiveTimes, radioId)
        finishRound(generation)
        return
    }
    when (outcome) {
        AdvertContactSyncOutcome.BUSY -> pendingLocked {
            // The radio was never asked: keep the failure budget and do not stamp lastDeltaSyncEnd.
            logger(DebugLogLevel.INFO, "Advert delta sync deferred: another sync holds the claim")
            finishRoundLocked(this, generation)
            requeueDrainedWorkLocked(this, round, shouldSchedule = false)
            scheduleBusyRetryLocked(this)
        }
        AdvertContactSyncOutcome.NOT_READY -> {
            logger(
                DebugLogLevel.NOTICE,
                "Advert delta sync not ready: dropping ${round.drained.size} pending key(s) until a full contact fetch completes",
            )
            stampAdvertReceiveTimes(round.receiveTimes, radioId)
            finishRound(generation)
        }
        AdvertContactSyncOutcome.FAILED -> if (recordFailedRound(round, generation)) {
            stampAdvertReceiveTimes(round.receiveTimes, radioId)
        }
        AdvertContactSyncOutcome.SYNCED -> completeSyncedRound(round, generation, preRoundKnownKeys, unknownAtStart, preRoundPathLastModified)
    }
}

/** Runs every pre-drain guard, then drains the pending work in the same actor segment. */
private suspend fun AdvertisementService.drainRound(roundJob: Job, generation: Long): DrainedRound? = pendingLocked {
    // Stop may have cleared the handler during the foreground hop; re-read it here.
    val handler = deltaSyncHandler
    // Empty-round guard before drain so a schedule with no work never clears the 0x8F set.
    if (roundJob.isCancelled || handler == null || !hasPendingDeltaSyncWork) {
        finishRoundLocked(this, generation)
        return@pendingLocked null
    }
    // Capture before teardown clears `currentRadioId` so rollback can still run after stop.
    val drained = pendingAdvertKeys.toSet()
    pendingAdvertKeys.clear()
    val receiveTimes = takeAdvertReceiveTimesLocked(drained)
    val pathKeys = pendingPathKeys.toSet()
    pendingPathKeys.clear()
    val fullRefetch = escalateToFullRefetch
    escalateToFullRefetch = false
    // Clear before the commit so only mid-round deletes race this round's write path.
    contactsDeletedDuringSync.clear()
    DrainedRound(handler, currentRadioId, drained, receiveTimes, pathKeys, fullRefetch)
}

/**
 * Invokes the handler in the round's own (cancellable) job so it observes teardown like a Swift child
 * await. Returns null when the round was cancelled under it. Any other throw, including a
 * CancellationException the handler raised itself (a timeout or a foreign Job) while the round is still
 * live, counts as FAILED: Swift handlers cannot throw, so the round must still roll back, stamp and requeue.
 */
private suspend fun AdvertisementService.invokeHandler(roundJob: Job, round: DrainedRound): AdvertContactSyncOutcome? =
    try {
        withContext(roundJob) { round.handler(round.fullRefetch) }
    } catch (cancelled: CancellationException) {
        if (roundJob.isCancelled) return null
        logger(DebugLogLevel.ERROR, "Advert delta sync handler was cancelled outside the round: ${cancelled.message}")
        AdvertContactSyncOutcome.FAILED
    } catch (failure: Exception) {
        logger(DebugLogLevel.ERROR, "Advert delta sync handler threw: ${failure.message}")
        AdvertContactSyncOutcome.FAILED
    }

/** Returns true when the failure cap dropped the drained advert keys (caller stamps their recency). */
private suspend fun AdvertisementService.recordFailedRound(round: DrainedRound, generation: Long): Boolean = pendingLocked {
    consecutiveDeltaSyncFailures += 1
    lastDeltaSyncEnd = clock.now()
    finishRoundLocked(this, generation)
    if (consecutiveDeltaSyncFailures < AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES) {
        requeueDrainedWorkLocked(this, round, shouldSchedule = true)
        return@pendingLocked false
    }
    // Drop drained advert keys (the cap's purpose) but restore path keys and an owed escalation.
    logger(
        DebugLogLevel.ERROR,
        "Advert delta sync failed ${AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES} times in a row; " +
            "dropping ${round.drained.size} pending key(s)",
    )
    consecutiveDeltaSyncFailures = 0
    // A 0x80/0x81 during this failing round spent its one schedule no-op against the still-set task;
    // detect that fresh work before restoring this round's own keys, and re-arm for it.
    val freshWorkArrivedMidRound = hasPendingDeltaSyncWork
    round.pathKeys.filterNot { isRadioDeleted(it) }.forEach { pendingPathKeys += it }
    if (round.fullRefetch) escalateToFullRefetch = true
    if (freshWorkArrivedMidRound) scheduleDeltaSyncLocked(this)
    true
}

private suspend fun AdvertisementService.completeSyncedRound(
    round: DrainedRound,
    generation: Long,
    preRoundKnownKeys: Set<Bytes>?,
    unknownAtStart: Set<Bytes>,
    preRoundPathLastModified: Map<Bytes, UInt>,
) {
    locked { consecutiveDeltaSyncFailures = 0 }
    // Unknown contacts get lastHeard = 0 on insert; stamp phone recency so a stale radio lastModified
    // cannot make them prune-eligible right after being heard.
    stampAdvertReceiveTimes(round.receiveTimes, round.radioId)
    // Only keys proven absent before the round are announced as new.
    val insertedKeysForNotify = preRoundKnownKeys?.let { round.drained - it } ?: emptySet()
    reconcile(round.drained, insertedKeysForNotify)
    // Adopt against every drained key that now has a row so a snapshot failure still links orphan DMs.
    val adoptedContactIDs = adoptOrphanedMessages(round.drained, round.radioId)
    eventBroadcaster.yield(AdvertisementEvent.ContactUpdated)
    if (adoptedContactIDs.isNotEmpty()) {
        eventBroadcaster.yield(AdvertisementEvent.ConversationsChanged)
        eventBroadcaster.yield(AdvertisementEvent.OrphanDirectMessagesAdopted(adoptedContactIDs.snapshot()))
    }
    if (!round.fullRefetch) {
        escalateMissingUnknownKeys(unknownAtStart)
        escalateUndeliveredPathUpdates(round.pathKeys, preRoundPathLastModified)
    } else {
        dropStillMissingUnknownKeys(unknownAtStart)
    }
    locked {
        lastDeltaSyncEnd = clock.now()
        finishRoundLocked(this, generation)
        if (hasPendingDeltaSyncWork) scheduleDeltaSyncLocked(this)
    }
}

/**
 * Returns a round's drained work to the pending set; a rolled-back key stays dropped because
 * refetching cannot bring it back. Requeued advert keys take a fresh phone receive time (Swift default).
 */
private fun AdvertisementService.requeueDrainedWorkLocked(
    state: AdvertisementServiceState,
    round: DrainedRound,
    shouldSchedule: Boolean,
) = with(state) {
    // Keep a consumed full-refetch flag so a flaky escalated round retries as a full refetch.
    if (round.fullRefetch) escalateToFullRefetch = true
    val now = clock.wallNow()
    round.drained.filterNot { isRadioDeleted(it) }.forEach { recordPendingAdvertKeyLocked(it, now) }
    round.pathKeys.filterNot { isRadioDeleted(it) }.forEach { pendingPathKeys += it }
    if (shouldSchedule && hasPendingDeltaSyncWork) scheduleDeltaSyncLocked(this)
}

/**
 * Removes contacts the radio deleted (0x8F) since this round drained, before `reconcile`, using
 * `deleteContactIfUnreferenced` so a DM attached after the batch re-save keeps its contact.
 */
private suspend fun AdvertisementService.rollBackContactsDeletedDuringSync(radioId: RadioId?) {
    val keys = locked { contactsDeletedDuringSync.toSet() }
    if (radioId == null || keys.isEmpty()) return
    for (publicKey in keys) {
        val pubKeyHex = publicKey.uppercaseHexString()
        try {
            val contact = dataStore.fetchContact(radioId, publicKey) ?: continue
            dataStore.deleteContactIfUnreferenced(EntityKey(radioId, contact.id))
            logger(DebugLogLevel.INFO, "Delta sync rollback: removed contact $pubKeyHex deleted by the radio mid-sync")
            eventBroadcaster.yield(AdvertisementEvent.ContactUpdated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Delta sync rollback failed for $pubKeyHex: ${failure.message}")
        }
    }
}

/** Refreshes Discover rows for drained 0x80 keys and announces only keys this round inserted. */
internal suspend fun AdvertisementService.reconcile(drained: Set<Bytes>, insertedKeys: Set<Bytes>) {
    val radioId = currentRadioId ?: return
    for (publicKey in drained) {
        if (isRadioDeleted(publicKey)) continue
        val pubKeyHex = publicKey.uppercaseHexString()
        try {
            val contact = dataStore.fetchContact(radioId, publicKey)
            if (contact == null) {
                discoverTrace(DebugLogLevel.DEBUG, "B2 reconcile: no contact yet key=$pubKeyHex")
                continue
            }
            // Re-check after each store hop; the radio can delete mid-round.
            if (isRadioDeleted(publicKey)) continue
            // Stored radio fields verbatim; never stamp the phone clock into radio-sourced timestamps.
            val frame = ContactFrame(
                contact.publicKey, contact.type, contact.flags, contact.outPathLength, contact.outPath, contact.name,
                contact.lastAdvertTimestamp, contact.latitude, contact.longitude, contact.lastModified,
            )
            try {
                val result = dataStore.upsertDiscoveredNode(radioId, frame)
                discoverTrace(DebugLogLevel.DEBUG, "B2 reconcile upsert key=$pubKeyHex isNew=${result.isNew}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                discoverTrace(DebugLogLevel.ERROR, "B2 reconcile upsert FAILED key=$pubKeyHex: ${failure.message}")
            }
            if (isRadioDeleted(publicKey)) continue
            // Prefer losing a notification over inventing one.
            if (publicKey in insertedKeys) {
                eventBroadcaster.yield(AdvertisementEvent.NewContactDiscovered(contact.name, contact.id, contact.type))
                locked { logOverwriteReplacementIfRecentLocked(contact.name, contact.type) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Reconcile failed for $pubKeyHex: ${failure.message}")
        }
    }
}

/** Links orphaned DMs whose sender prefix matches contacts among [keys]; returns adopting contact ids. */
private suspend fun AdvertisementService.adoptOrphanedMessages(keys: Set<Bytes>, radioId: RadioId?): List<UUID> {
    if (radioId == null || keys.isEmpty()) return emptyList()
    val contacts = ArrayList<ContactIdentity>(keys.size)
    for (publicKey in keys) {
        if (isRadioDeleted(publicKey)) continue
        try {
            dataStore.fetchContact(radioId, publicKey)?.let { contacts += ContactIdentity(it.id, it.publicKey) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Adoption contact lookup failed for ${publicKey.uppercaseHexString()}: ${failure.message}")
        }
    }
    if (contacts.isEmpty()) return emptyList()
    return try {
        val adopted = dataStore.adoptOrphanedDirectMessages(radioId, contacts.snapshot())
        if (adopted.isNotEmpty()) logger(DebugLogLevel.INFO, "Adopted orphaned DMs for ${adopted.size} contact(s)")
        adopted.keys.toList()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger(DebugLogLevel.ERROR, "Orphaned DM adoption failed: ${failure.message}")
        emptyList()
    }
}

/** After a successful incremental, escalate once when keys unknown at round start still lack a row. */
private suspend fun AdvertisementService.escalateMissingUnknownKeys(unknownAtStart: Set<Bytes>) {
    val radioId = currentRadioId ?: return
    val missing = LinkedHashSet<Bytes>()
    for (publicKey in unknownAtStart) {
        // A key the radio deleted is missing on purpose; refetching cannot bring it back.
        if (isRadioDeleted(publicKey)) continue
        val exists = contactExists(radioId, publicKey) ?: continue
        if (!exists) missing += publicKey
    }
    if (missing.isEmpty()) return
    logger(DebugLogLevel.INFO, "Advert delta sync escalation: ${missing.size} unknown key(s) still missing")
    val now = clock.wallNow()
    pendingLocked {
        escalateToFullRefetch = true
        missing.forEach { recordPendingAdvertKeyLocked(it, now) }
    }
}

/** Pre-round `lastModified` for known path keys; keys with no local row are omitted. */
private suspend fun AdvertisementService.snapshotPathLastModified(keys: Set<Bytes>, radioId: RadioId?): Map<Bytes, UInt> {
    if (radioId == null || keys.isEmpty()) return emptyMap()
    val snapshot = LinkedHashMap<Bytes, UInt>()
    for (publicKey in keys) {
        try {
            dataStore.fetchContact(radioId, publicKey)?.let { snapshot[publicKey] = it.lastModified }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Path lastModified snapshot failed for ${publicKey.uppercaseHexString()}: ${failure.message}")
        }
    }
    return snapshot
}

/**
 * After a successful incremental driven by 0x81, escalate once when a path key was not refreshed
 * (radio lastmod at or below the stored watermark yields an empty incremental stream).
 */
private suspend fun AdvertisementService.escalateUndeliveredPathUpdates(
    drainedPathKeys: Set<Bytes>,
    preRoundLastModified: Map<Bytes, UInt>,
) {
    val radioId = currentRadioId ?: return
    if (drainedPathKeys.isEmpty()) return
    var undelivered = 0
    for (publicKey in drainedPathKeys) {
        if (isRadioDeleted(publicKey)) continue
        try {
            val contact = dataStore.fetchContact(radioId, publicKey)
            if (contact == null) {
                undelivered += 1
                continue
            }
            val before = preRoundLastModified[publicKey]
            if (before != null && contact.lastModified <= before) undelivered += 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Path delivery check failed for ${publicKey.uppercaseHexString()}: ${failure.message}")
        }
    }
    if (undelivered == 0) return
    logger(DebugLogLevel.INFO, "Advert path update escalation: $undelivered path key(s) not refreshed by incremental fetch")
    locked { escalateToFullRefetch = true }
}

/** After an escalated full refetch, log keys that are still missing (no loop). */
private suspend fun AdvertisementService.dropStillMissingUnknownKeys(unknownAtStart: Set<Bytes>) {
    val radioId = currentRadioId ?: return
    for (publicKey in unknownAtStart) {
        if (isRadioDeleted(publicKey)) continue
        val exists = contactExists(radioId, publicKey) ?: continue
        if (!exists) logger(DebugLogLevel.INFO, "Advert full refetch still missing key=${publicKey.uppercaseHexString()}; dropping")
    }
}

/** Whether a contact row exists locally, or null when the read failed (never evidence to escalate). */
private suspend fun AdvertisementService.contactExists(radioId: RadioId, publicKey: Bytes): Boolean? =
    try {
        dataStore.fetchContact(radioId, publicKey) != null
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        logger(DebugLogLevel.ERROR, "Contact lookup failed for ${publicKey.uppercaseHexString()}: ${failure.message}")
        null
    }
