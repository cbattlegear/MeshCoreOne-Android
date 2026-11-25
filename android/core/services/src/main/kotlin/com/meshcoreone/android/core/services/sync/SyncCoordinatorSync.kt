// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+Sync.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.MessagePollingServiceProtocol
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.RadioId
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

// MARK: - Sync claim (Swift actor-local `isSyncInProgress` with `defer` release)

/** Claims [SyncCoordinatorState.isSyncInProgress]; returns the claim generation, or null when held. */
internal fun SyncCoordinator.tryClaimSync(): Long? = locked {
    if (isSyncInProgress) null else {
        isSyncInProgress = true
        syncClaimGeneration += 1
        syncClaimGeneration
    }
}

/** Releases a claim only while it still owns the flag (a disconnect may have cleared and re-issued it). */
internal fun SyncCoordinator.releaseSyncClaim(claim: Long) = locked {
    if (syncClaimGeneration == claim) isSyncInProgress = false
}

// MARK: - Full sync

/**
 * Performs full sync of contacts, channels, and messages from device, in that order.
 *
 * Waits out an advert-owned contact sync claim first; returns [FullSyncResult.SKIPPED] when another sync
 * already holds the claim. When [appStateProvider] reports background, channel sync is skipped.
 */
suspend fun SyncCoordinator.performFullSync(
    radioId: RadioId,
    dataStore: PersistenceStoreProtocol,
    contactService: ContactServiceProtocol,
    channelService: ChannelServiceProtocol,
    messagePollingService: MessagePollingServiceProtocol,
    appStateProvider: AppStateProvider? = null,
    rxLogService: SyncRxLogServicing? = null,
    notificationService: SyncNotificationServicing? = null,
    forceFullSync: Boolean = false,
    channelSyncConfig: ChannelSyncConfig = ChannelSyncConfig.NONE,
    platformName: String = "unknown",
): FullSyncResult {
    waitForAdvertContactSync()
    val claim = tryClaimSync() ?: run {
        log(DebugLogLevel.WARNING, "performFullSync called while already syncing, ignoring duplicate")
        return FullSyncResult.SKIPPED
    }
    try {
        return runFullSync(
            radioId, dataStore, contactService, channelService, messagePollingService, appStateProvider,
            rxLogService, notificationService, forceFullSync, channelSyncConfig, platformName,
        )
    } finally {
        releaseSyncClaim(claim)
    }
}

/** Full-sync body without the claim; callers hold it already. */
private suspend fun SyncCoordinator.runFullSync(
    radioId: RadioId,
    dataStore: PersistenceStoreProtocol,
    contactService: ContactServiceProtocol,
    channelService: ChannelServiceProtocol,
    messagePollingService: MessagePollingServiceProtocol,
    appStateProvider: AppStateProvider?,
    rxLogService: SyncRxLogServicing?,
    notificationService: SyncNotificationServicing?,
    forceFullSync: Boolean,
    channelSyncConfig: ChannelSyncConfig,
    platformName: String,
): FullSyncResult {
    log(DebugLogLevel.INFO, "Starting full sync for device ${radioId.canonicalString}")
    val syncStart = clock.elapsed()
    try {
        log(DebugLogLevel.INFO, "[Sync] State → .syncing(.contacts)")
        setState(SyncState.Syncing(SyncProgress(SyncPhase.CONTACTS, 0, 0)))
        locked { hasEndedSyncActivity = false }
        log(DebugLogLevel.INFO, "[Sync] Calling onSyncActivityStarted")
        callSyncActivityStarted()

        // Contacts remain connection-critical; channel errors are degraded state the caller retries.
        val contactChannelResult = syncContactsAndChannels(
            radioId, dataStore, contactService, channelService, appStateProvider, rxLogService, forceFullSync,
            channelSyncConfig,
        )

        // End sync activity before the messages phase (pill hides). During resync the outer bracket keeps
        // the count above zero, so this inner success cannot trigger the "Ready" toast prematurely.
        endSyncActivityOnce(succeeded = true)

        log(DebugLogLevel.INFO, "[Sync] State → .syncing(.messages)")
        setState(SyncState.Syncing(SyncProgress(SyncPhase.MESSAGES, 0, 0)))
        val messageStart = clock.elapsed()
        var messageCount = 0L
        val messageStatus: SyncPhaseStatus = try {
            messageCount = messagePollingService.pollAllMessages()
            SyncPhaseStatus.Clean
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.WARNING, "[Sync] Message polling failed after contacts/channels: ${failure.syncDescription()}")
            SyncPhaseStatus.Failed(failure.syncDescription())
        }
        log(DebugLogLevel.INFO, "[Sync] Phase end: messages - $messageCount polled in ${clock.elapsed() - messageStart}")

        // Catch-up messages were processed with suppression active; later messages are genuinely new.
        if (notificationService != null) {
            cancelSuppressionWatchdog()
            contain("setSuppressingNotifications", Unit) { notificationService.setSuppressingNotifications(false) }
        }

        notifyConversationsChanged()

        log(DebugLogLevel.INFO, "[Sync] State → .synced")
        setState(SyncState.Synced)
        setLastSyncDate(clock.now())

        log(
            DebugLogLevel.INFO,
            "[Sync] Complete: platform=$platformName, messages=$messageCount, duration=${clock.elapsed() - syncStart}",
        )
        return FullSyncResult(
            contacts = contactChannelResult.contacts,
            channels = contactChannelResult.channels,
            messages = messageStatus,
            channelRetryIndices = contactChannelResult.channelRetryIndices,
        )
    } catch (failure: Exception) {
        // Ensure the activity count is decremented once on every exit the inner paths did not cover.
        withContext(NonCancellable) { endSyncActivityOnce() }
        if (isCallerCancellation(failure)) {
            setState(SyncState.Idle)
            throw failure
        }
        log(DebugLogLevel.WARNING, "[Sync] State \u2192 .failed: ${failure.syncDescription()}")
        setState(SyncState.Failed(SyncCoordinatorError.SyncFailed(failure.syncDescription())))
        throw failure
    }
}

/**
 * Attempts to resync after a previous sync failure. Unlike [onConnectionEstablished], does not rewire
 * handlers or restart event monitoring. Returns true when the sync produced usable contacts.
 */
suspend fun SyncCoordinator.performResync(
    radioId: RadioId,
    dependencies: SyncDependencies,
    forceFullSync: Boolean = false,
    channelSyncConfig: ChannelSyncConfig = ChannelSyncConfig.NONE,
    platformName: String = "unknown",
): Boolean {
    locked { performResyncOverride }?.let { return it(radioId, dependencies) }
    log(DebugLogLevel.INFO, "Attempting resync for device ${radioId.canonicalString}")

    val notifications = dependencies.notificationService
    val polling = dependencies.messagePollingService
    val result = try {
        log(DebugLogLevel.INFO, "Suppressing message notifications during resync")
        contain("setSuppressingNotifications", Unit) { notifications.setSuppressingNotifications(true) }
        startSuppressionWatchdog(notifications)
        log(DebugLogLevel.INFO, "[Sync] Pausing auto-fetch for resync")
        contain("pauseAutoFetch", Unit) { polling.pauseAutoFetch() }
        performFullSync(
            radioId, dependencies.dataStore, dependencies.contactService, dependencies.channelService, polling,
            dependencies.appStateProvider, dependencies.rxLogService, notifications, forceFullSync,
            channelSyncConfig, platformName,
        )
    } catch (failure: Exception) {
        // Cleanup runs to completion even when cancelled; Swift's catch-all also absorbs cancellation
        // and returns false, Kotlin rethrows it after the cleanup.
        withContext(NonCancellable) {
            drainHandlersAndResumeNotifications(notifications, polling, "resync failed")
            contain("resumeAutoFetch", Unit) { polling.resumeAutoFetch() }
        }
        if (isCallerCancellation(failure)) throw failure
        log(DebugLogLevel.WARNING, "Resync failed: ${failure.syncDescription()}")
        setState(SyncState.Failed(SyncCoordinatorError.SyncFailed(failure.syncDescription())))
        return false
    }

    withContext(NonCancellable) {
        startDiscoveryEventMonitoring(dependencies, radioId)
        drainHandlersAndResumeNotifications(notifications, polling, "resync complete")
        log(DebugLogLevel.INFO, "[Sync] Resuming auto-fetch after resync")
        contain("resumeAutoFetch", Unit) { polling.resumeAutoFetch() }
    }

    if (result.isConnectionUsable) {
        log(DebugLogLevel.INFO, "Resync succeeded")
        return true
    }
    log(DebugLogLevel.WARNING, "Resync completed without usable contacts")
    return false
}

// MARK: - Connection lifecycle

/**
 * Called when a connection is established. Wires handlers, starts event monitoring, and performs the
 * initial sync, in the order the Sync guide requires:
 * 1. wire message handlers (before events can arrive) and the advert delta-sync handler;
 * 2. start event monitoring with auto-fetch disabled; 3. export the private key;
 * 4. full sync; 5. discovery monitoring; 6. flush deferred advert fetches;
 * 7. drain pending handlers and resume notifications; 8. start auto-fetch.
 */
suspend fun SyncCoordinator.onConnectionEstablished(
    radioId: RadioId,
    dependencies: SyncDependencies,
    forceFullSync: Boolean = false,
    channelSyncConfig: ChannelSyncConfig = ChannelSyncConfig.NONE,
    platformName: String = "unknown",
): FullSyncResult {
    log(DebugLogLevel.INFO, "Connection established for device ${radioId.canonicalString}")
    waitForAdvertContactSync()

    // Claim before the wiring awaits so racing connection setups cannot double-wire handlers.
    val claim = tryClaimSync() ?: run {
        log(DebugLogLevel.WARNING, "onConnectionEstablished called while already syncing, ignoring duplicate")
        return FullSyncResult.SKIPPED
    }
    try {
        return establishConnection(radioId, dependencies, forceFullSync, channelSyncConfig, platformName)
    } finally {
        releaseSyncClaim(claim)
    }
}

private suspend fun SyncCoordinator.establishConnection(
    radioId: RadioId,
    dependencies: SyncDependencies,
    forceFullSync: Boolean,
    channelSyncConfig: ChannelSyncConfig,
    platformName: String,
): FullSyncResult {
    val notifications = dependencies.notificationService
    val polling = dependencies.messagePollingService
    val adverts = dependencies.advertisementService
    try {
        // Suppress message notifications during sync; unread counts and badges still update.
        log(DebugLogLevel.INFO, "Suppressing message notifications during sync")
        contain("setSuppressingNotifications", Unit) { notifications.setSuppressingNotifications(true) }
        startSuppressionWatchdog(notifications)

        // Defer advert-driven contact sync during full sync to avoid radio contention.
        contain("setSyncingContacts", Unit) { adverts.setSyncingContacts(true) }

        // 1. Handlers and advert delta sync first, so a failed initial sync cannot leave them dead.
        wireMessageHandlers(dependencies, radioId)
        val dataStore = dependencies.dataStore
        val contactService = dependencies.contactService
        contain("setDeltaSyncHandler", Unit) {
            adverts.setDeltaSyncHandler { fullRefetch ->
                performAdvertContactSync(fullRefetch, radioId, dataStore, contactService)
            }
        }

        deleteBlockedSenderMessages(radioId, dependencies.dataStore)

        // 2. Event monitoring (handlers ready); auto-fetch and advert monitoring wait until after sync.
        log(DebugLogLevel.INFO, "[Sync] Starting event monitoring for device ${radioId.canonicalString.take(8)}")
        contain("startEventMonitoring", Unit) { dependencies.startEventMonitoring(radioId, false) }

        // 3. Device private key for direct message decryption.
        try {
            val privateKey = dependencies.exportPrivateKey()
            contain("updatePrivateKey", Unit) { dependencies.rxLogService.updatePrivateKey(privateKey) }
            log(DebugLogLevel.DEBUG, "Device private key exported for direct message decryption")
        } catch (failure: Exception) {
            failure.rethrowIfCallerCancelled()
            log(DebugLogLevel.WARNING, "Failed to export private key: ${failure.syncDescription()}")
        }

        // 4. Full sync (the claim is already held).
        val syncResult = runFullSync(
            radioId, dependencies.dataStore, dependencies.contactService, dependencies.channelService, polling,
            dependencies.appStateProvider, dependencies.rxLogService, notifications, forceFullSync,
            channelSyncConfig, platformName,
        )

        // 5. Discovery monitoring after the sync, so adverts arriving during sync do not notify.
        startDiscoveryEventMonitoring(dependencies, radioId)

        // 6. Flush deferred advert-driven contact fetches.
        contain("setSyncingContacts", Unit) { adverts.setSyncingContacts(false) }

        // 7. Drain pending message handlers and resume notifications.
        drainHandlersAndResumeNotifications(notifications, polling, "sync complete")

        // 8. Auto-fetch after suppression is cleared to avoid notification spam.
        log(DebugLogLevel.INFO, "[Sync] Starting auto-fetch for device ${radioId.canonicalString.take(8)}")
        contain("startAutoFetch", Unit) { polling.startAutoFetch(radioId) }

        log(DebugLogLevel.INFO, "Connection setup complete for device ${radioId.canonicalString}")
        return syncResult
    } catch (failure: Exception) {
        withContext(NonCancellable) {
            drainHandlersAndResumeNotifications(notifications, polling, "sync failed")
            contain("setSyncingContacts", Unit) { adverts.setSyncingContacts(false) }
        }
        throw failure
    }
}

/**
 * Called when disconnecting from the device. Clears the sync guards (waking advert-claim waiters), ends
 * the activity bracket when mid-contacts/channels, resets state to idle, and clears suppression.
 */
suspend fun SyncCoordinator.onDisconnected(notificationService: SyncNotificationServicing) =
    withContext(NonCancellable) { resetForDisconnect(notificationService) }

/** The disconnect safety net; it must run to completion even when its caller is cancelled. */
private suspend fun SyncCoordinator.resetForDisconnect(notificationService: SyncNotificationServicing) {
    val currentState = state
    val (hadClaim, ended) = locked { isSyncInProgress to hasEndedSyncActivity }
    log(DebugLogLevel.WARNING, "[Sync] onDisconnected called - syncState: $currentState, hasEndedSyncActivity: $ended")
    if (hadClaim) log(DebugLogLevel.WARNING, "isSyncInProgress still true at disconnect — clearing as safety net")

    val waiters = locked {
        isSyncInProgress = false
        syncClaimGeneration += 1
        advertContactSyncActive = false
        manualContactSyncActive = false
        // The next session must prove its own full contact fetch before delta sync runs.
        fullContactSyncCompletedRadioID = null
        invalidWatermarkRecoveryRadioID = null
        invalidWatermarkRecoveryExhaustedLoggedRadioID = null
        // Pending reactions persist for the app session; unresolved-channel tracking is per connection.
        unresolvedChannelIndices = emptySet()
        lastUnresolvedChannelSummaryAt = null
        takeAdvertSyncWaiters()
    }
    waiters.forEach { it.complete(Unit) }

    if (currentState is SyncState.Syncing &&
        (currentState.progress.phase == SyncPhase.CONTACTS || currentState.progress.phase == SyncPhase.CHANNELS)
    ) {
        endSyncActivityOnce()
    }

    log(DebugLogLevel.INFO, "[Sync] State → .idle (disconnected)")
    setState(SyncState.Idle)

    // Safety net: suppression must never outlive the connection.
    cancelSuppressionWatchdog()
    contain("setSuppressingNotifications", Unit) { notificationService.setSuppressingNotifications(false) }
    log(DebugLogLevel.INFO, "Disconnected, sync state reset to idle")
}

/**
 * Cancels the suppression watchdog, resumes notifications, then waits up to 30 s for pending message
 * handlers. Suppression is cleared first as defense-in-depth for error paths; both steps are idempotent.
 */
internal suspend fun SyncCoordinator.drainHandlersAndResumeNotifications(
    notificationService: SyncNotificationServicing,
    messagePollingService: MessagePollingServiceProtocol,
    context: String,
) {
    cancelSuppressionWatchdog()
    log(DebugLogLevel.INFO, "Resuming message notifications ($context)")
    contain("setSuppressingNotifications", Unit) { notificationService.setSuppressingNotifications(false) }
    val drained = contain("waitForPendingHandlers", false) {
        messagePollingService.waitForPendingHandlers(PENDING_HANDLER_DRAIN_TIMEOUT)
    }
    if (!drained) log(DebugLogLevel.WARNING, "Timed out waiting for pending message handlers")
}

/** Bound for draining pending message handlers after a sync. */
internal val PENDING_HANDLER_DRAIN_TIMEOUT: java.time.Duration = java.time.Duration.ofSeconds(30)
