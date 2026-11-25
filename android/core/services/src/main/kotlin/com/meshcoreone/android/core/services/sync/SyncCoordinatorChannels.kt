// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+Sync.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ChannelSyncError
import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.snapshot
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

internal class ContactChannelSyncResult(
    val contacts: SyncPhaseStatus,
    val channels: SyncPhaseStatus,
    val channelRetryIndices: SnapshotList<UByte>,
)

/** Syncs contacts and channels from the device (phases 1 and 2 of full sync). */
internal suspend fun SyncCoordinator.syncContactsAndChannels(
    radioId: RadioId,
    dataStore: PersistenceStoreProtocol,
    contactService: ContactServiceProtocol,
    channelService: ChannelServiceProtocol,
    appStateProvider: AppStateProvider?,
    rxLogService: SyncRxLogServicing?,
    forceFullSync: Boolean,
    config: ChannelSyncConfig,
): ContactChannelSyncResult {
    // Fetch the device once for both contacts (lastContactSync) and channels (maxChannels).
    val device = dataStore.fetchDevice(radioId)
    syncContactPhase(radioId, device, dataStore, contactService, forceFullSync)
    refreshRxLogContactKeys(radioId, dataStore, rxLogService)

    // Phase 2: channels (foreground only).
    val shouldSyncChannels = if (appStateProvider != null) {
        contain("isInForeground", true) { appStateProvider.isInForeground() }
    } else {
        log(DebugLogLevel.DEBUG, "No appStateProvider, defaulting to foreground mode")
        true
    }
    if (!shouldSyncChannels) {
        log(DebugLogLevel.INFO, "Skipping channel sync (app in background)")
        return ContactChannelSyncResult(SyncPhaseStatus.Clean, SyncPhaseStatus.Skipped, SnapshotList.empty())
    }

    val outcome = if (shouldSkipChannels(forceFullSync, config, clock.now())) {
        log(DebugLogLevel.INFO, "[Sync] Skipping channel sync (recent clean or attempted sync)")
        ChannelPhaseOutcome(SyncPhaseStatus.Skipped, emptyList())
    } else {
        syncChannelPhase(radioId, device, dataStore, channelService, rxLogService, config.usePipelinedChannelRead)
            ?: return ContactChannelSyncResult(SyncPhaseStatus.Clean, SyncPhaseStatus.Partial, SnapshotList.empty())
    }
    logPostSyncChannelDiagnostics(radioId, dataStore)
    if (rxLogService != null) refreshRxLogChannels(radioId, dataStore, rxLogService)
    return ContactChannelSyncResult(SyncPhaseStatus.Clean, outcome.status, outcome.retryIndices.snapshot())
}

/** Phase 1: contacts, incremental unless forced full, at capacity, or without a usable watermark. */
private suspend fun SyncCoordinator.syncContactPhase(
    radioId: RadioId,
    device: DeviceDTO?,
    dataStore: PersistenceStoreProtocol,
    contactService: ContactServiceProtocol,
    forceFullSync: Boolean,
) {
    // At capacity, force a pruning fetch: offline eviction is invisible to the watermark. Decided before
    // the watermark switch so the one-shot invalid-watermark recovery is not spent. The virtual V-contact
    // does not count; a count-read failure leaves atCapacity false.
    var atCapacity = false
    var realContactCount = 0
    val maxContacts = device?.maxContacts ?: 0u
    if (maxContacts > 0u) {
        try {
            val keys = dataStore.fetchContactPublicKeys(radioId)
            realContactCount = keys.size
            val vContactKey = device?.publicKey?.let(VContactIdentity::publicKey)
            if (vContactKey != null && vContactKey in keys) realContactCount -= 1
            atCapacity = realContactCount >= maxContacts.toInt()
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.ERROR, "Failed to read local contact count for capacity check: ${failure.syncDescription()}")
        }
    }

    var ranInvalidWatermarkRecovery = false
    val since: Instant? = if (forceFullSync || atCapacity) {
        val reason = if (forceFullSync) "forceFullSync" else "at capacity (local $realContactCount of max $maxContacts)"
        log(DebugLogLevel.NOTICE, "[Sync] Phase start: contacts (FULL sync, reason=$reason) — local contacts not on device will be pruned")
        null
    } else {
        when (val use = contactWatermarkUse(device?.lastContactSync, clock.now())) {
            ContactWatermarkUse.None -> {
                log(DebugLogLevel.NOTICE, "[Sync] Phase start: contacts (FULL sync, reason=no watermark) — local contacts not on device will be pruned")
                null
            }
            is ContactWatermarkUse.Incremental -> incrementalSince(use.watermark)
            is ContactWatermarkUse.Invalid -> {
                // Connect full-sync may prune; bound to one recovery per radio per coordinator lifetime.
                if (locked { invalidWatermarkRecoveryRadioID } == radioId) {
                    logInvalidWatermarkRecoveryExhaustedIfNeeded(radioId, use.stored)
                    incrementalSince(use.stored)
                } else {
                    ranInvalidWatermarkRecovery = true
                    log(
                        DebugLogLevel.NOTICE,
                        "[Sync] Phase start: contacts (FULL sync, reason=invalid watermark ${use.stored} exceeds phone " +
                            "reference + ${CONTACT_WATERMARK_PLAUSIBILITY_SKEW.inWholeSeconds}s) — one-shot recovery, store not rewritten",
                    )
                    null
                }
            }
        }
    }

    syncContactsPhase(radioId, dataStore, contactService, since, forceFullSync)
    if (ranInvalidWatermarkRecovery) locked { invalidWatermarkRecoveryRadioID = radioId }
}

/** Updates RxLogService with contact public keys for direct message decryption. */
private suspend fun SyncCoordinator.refreshRxLogContactKeys(
    radioId: RadioId,
    dataStore: PersistenceStoreProtocol,
    rxLogService: SyncRxLogServicing?,
) {
    if (rxLogService == null) return
    try {
        val publicKeys = dataStore.fetchContactPublicKeysByPrefix(radioId)
        contain("updateContactPublicKeys", Unit) { rxLogService.updateContactPublicKeys(publicKeys) }
        log(DebugLogLevel.DEBUG, "Updated ${publicKeys.size} contact public keys for direct message decryption")
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.ERROR, "Failed to fetch contact public keys: ${failure.syncDescription()}")
    }
}

/**
 * Whether a recent clean or attempted channel sync lets this sync skip channels. Swift compares the
 * elapsed seconds with the window's whole-seconds component.
 */
internal fun shouldSkipChannels(forceFullSync: Boolean, config: ChannelSyncConfig, now: Instant): Boolean {
    val window = config.channelSyncSkipWindow
    if (forceFullSync || window <= Duration.ZERO) return false
    val windowSeconds = window.inWholeSeconds.toDouble()
    fun isRecent(at: Instant?) = at != null && now.secondsSinceEpoch() - at.secondsSinceEpoch() < windowSeconds
    return isRecent(config.lastCleanChannelSync) || isRecent(config.lastAttemptedChannelSync)
}

private class ChannelPhaseOutcome(val status: SyncPhaseStatus, val retryIndices: List<UByte>)

/**
 * Runs the channel phase with one in-phase retry of retryable failures. Returns null when the channel
 * service threw: the phase is partial and diagnostics/RX-log refresh already ran.
 */
private suspend fun SyncCoordinator.syncChannelPhase(
    radioId: RadioId,
    device: DeviceDTO?,
    dataStore: PersistenceStoreProtocol,
    channelService: ChannelServiceProtocol,
    rxLogService: SyncRxLogServicing?,
    usePipelinedRead: Boolean,
): ChannelPhaseOutcome? {
    log(DebugLogLevel.INFO, "[Sync] State → .syncing(.channels)")
    setState(SyncState.Syncing(SyncProgress(SyncPhase.CHANNELS, 0, 0)))
    val maxChannels: UByte = device?.maxChannels ?: 0u
    callChannelSyncAttempted(radioId)

    val channelStart = clock.elapsed()
    val channelResult = try {
        channelService.syncChannels(radioId, maxChannels, usePipelinedRead)
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(
            DebugLogLevel.WARNING,
            "[Sync] Phase end: channels partial after thrown error in ${clock.elapsed() - channelStart}: ${failure.syncDescription()}",
        )
        logPostSyncChannelDiagnostics(radioId, dataStore)
        if (rxLogService != null) refreshRxLogChannels(radioId, dataStore, rxLogService)
        return null
    }
    log(
        DebugLogLevel.INFO,
        "[Sync] Phase end: channels - ${channelResult.channelsSynced} synced (device capacity: $maxChannels) in ${clock.elapsed() - channelStart}",
    )

    var phaseClean = channelResult.isComplete
    val hasNonRetryableErrors = channelResult.errors.size > channelResult.retryableIndices.size
    var remainingRetryable: List<UByte> = channelResult.retryableIndices

    // Retry failed channels once if there are retryable errors.
    if (!channelResult.isComplete && channelResult.retryableIndices.isNotEmpty()) {
        val retryable = channelResult.retryableIndices
        log(DebugLogLevel.INFO, "Retrying ${retryable.size} failed channels")
        val retryResult = try {
            channelService.retryFailedChannels(radioId, retryable)
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.WARNING, "Channel retry failed: ${failure.syncDescription()}")
            phaseClean = false
            remainingRetryable = retryable
            ChannelSyncResult(0, retryable.map { ChannelSyncError(it, ChannelSyncErrorType.Timeout, failure.syncDescription()) }.snapshot())
        }
        if (retryResult.isComplete && !hasNonRetryableErrors) {
            log(DebugLogLevel.INFO, "Retry recovered ${retryResult.channelsSynced} channels")
            phaseClean = true
        } else {
            remainingRetryable = retryResult.retryableIndices
            if (hasNonRetryableErrors) log(DebugLogLevel.WARNING, "Channels have non-retryable errors, phase not clean")
            log(DebugLogLevel.WARNING, "Channels still failing after retry: ${retryResult.errors.map { it.index }}")
            phaseClean = false
        }
    }

    if (phaseClean) {
        callCleanChannelSync(radioId)
        return ChannelPhaseOutcome(SyncPhaseStatus.Clean, emptyList())
    }
    return ChannelPhaseOutcome(SyncPhaseStatus.Partial, remainingRetryable)
}

private suspend fun SyncCoordinator.callCleanChannelSync(radioId: RadioId) {
    val callback = locked { onCleanChannelSync } ?: return
    contain("onCleanChannelSync", Unit) { callback(radioId) }
}

private suspend fun SyncCoordinator.callChannelSyncAttempted(radioId: RadioId) {
    val callback = locked { onChannelSyncAttempted } ?: return
    contain("onChannelSyncAttempted", Unit) { callback(radioId) }
}

/** Retries only unresolved channel indices without replaying contacts/messages. */
suspend fun SyncCoordinator.retryChannels(
    radioId: RadioId,
    channelService: ChannelServiceProtocol,
    indices: List<UByte>,
): ChannelSyncResult {
    if (indices.isEmpty()) return ChannelSyncResult(0)
    fun failed(type: ChannelSyncErrorType, description: String) =
        ChannelSyncResult(0, indices.map { ChannelSyncError(it, type, description) }.snapshot())

    try {
        waitForAdvertContactSync()
    } catch (failure: Exception) {
        // Swift returns a "Retry cancelled" result; Kotlin never swallows the caller's cancellation.
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.WARNING, "Channel-only retry timed out waiting for advert contact sync")
        return failed(ChannelSyncErrorType.CircuitBreaker, ADVERT_CONTACT_SYNC_WAIT_TIMED_OUT_MESSAGE)
    }

    val claim = tryClaimSync() ?: run {
        log(DebugLogLevel.INFO, "Channel-only retry skipped because sync is already active")
        return failed(ChannelSyncErrorType.CircuitBreaker, "Skipped because sync is already active")
    }
    var bracketOpen = false
    try {
        log(DebugLogLevel.INFO, "[Sync] State \u2192 .syncing(.channels) (channel-only retry)")
        setState(SyncState.Syncing(SyncProgress(SyncPhase.CHANNELS, 0, indices.size.toLong())))
        bracketOpen = true
        callSyncActivityStarted()
        callChannelSyncAttempted(radioId)

        val result = try {
            channelService.retryFailedChannels(radioId, indices.snapshot())
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.WARNING, "Channel-only retry failed: ${failure.syncDescription()}")
            bracketOpen = false
            callSyncActivityEnded(false)
            setState(SyncState.Synced)
            return failed(ChannelSyncErrorType.TransportError, failure.syncDescription())
        }

        if (result.isComplete) callCleanChannelSync(radioId)
        bracketOpen = false
        callSyncActivityEnded(result.isComplete)
        setState(SyncState.Synced)
        return result
    } catch (cancelled: CancellationException) {
        // Every cancelled exit closes the bracket once and leaves the channels phase.
        if (bracketOpen) withContext(NonCancellable) { callSyncActivityEnded(false) }
        setState(SyncState.Idle)
        throw cancelled
    } finally {
        releaseSyncClaim(claim)
    }
}

private suspend fun SyncCoordinator.logPostSyncChannelDiagnostics(radioId: RadioId, dataStore: PersistenceStoreProtocol) {
    try {
        val channels = dataStore.fetchChannels(radioId)
        val emptyNameWithSecret = channels.filter { it.name.isEmpty() && it.hasSecret }.map { it.index }.sorted()
        log(DebugLogLevel.INFO, "Post-sync channel diagnostics: total=${channels.size}, emptyNameWithSecret=${emptyNameWithSecret.size}")
        if (emptyNameWithSecret.isNotEmpty()) {
            log(DebugLogLevel.WARNING, "Post-sync channels with empty names and non-zero secrets: $emptyNameWithSecret")
        }
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.ERROR, "Failed to compute post-sync channel diagnostics: ${failure.syncDescription()}")
    }
}

/**
 * Refreshes the RX log channel cache. Swift's `Dictionary(uniqueKeysWithValues:)` traps on a duplicate
 * slot; Kotlin keeps the last row for that slot.
 */
private suspend fun SyncCoordinator.refreshRxLogChannels(
    radioId: RadioId,
    dataStore: PersistenceStoreProtocol,
    rxLogService: SyncRxLogServicing,
) {
    try {
        val channels = dataStore.fetchChannels(radioId)
        val secrets = channels.associate { it.index to it.secret }
        val names = channels.associate { it.index to it.name }
        contain("updateChannels", Unit) { rxLogService.updateChannels(secrets, names) }
        log(DebugLogLevel.DEBUG, "Refreshed RxLogService channel cache with ${channels.size} channels")
    } catch (failure: Exception) {
        failure.rethrowIfCallerCancelled()
        log(DebugLogLevel.ERROR, "Failed to refresh RxLogService channel cache: ${failure.syncDescription()}")
    }
}
