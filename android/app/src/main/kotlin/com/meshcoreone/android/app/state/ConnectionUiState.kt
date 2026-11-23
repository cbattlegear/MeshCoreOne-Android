// PortedFrom: MC1/State/ConnectionUIState.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.connectivity.pairing.PairingError
import com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings
import com.meshcoreone.android.core.l10n.R as L10nR
import com.meshcoreone.android.core.runtime.DeadlineClock
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.services.contacts.AdvertisementEvent
import com.meshcoreone.android.core.services.contacts.AdvertisementService
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.contacts.ContactServiceEvent
import com.meshcoreone.android.core.services.sync.SyncCoordinator
import com.meshcoreone.android.core.services.sync.SyncPhase
import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Receives VoiceOver-style connection announcements (TalkBack on Android). */
fun interface AccessibilityAnnouncer {
    fun announce(message: UiText)
    companion object { val NONE = AccessibilityAnnouncer {} }
}

/** Where the resync loop's exhaustion hook is installed (Swift `connectionManager.onResyncFailed`). */
fun interface ResyncFailureRegistry {
    fun setOnResyncFailed(handler: (() -> Unit)?)
}

/** Immutable view of everything [ConnectionUiState] publishes; observers collect [ConnectionUiState.state]. */
data class ConnectionUiSnapshot(
    val showReadyToast: Boolean = false,
    val syncFailedPillVisible: Boolean = false,
    val disconnectedPillVisible: Boolean = false,
    val syncActivityCount: Int = 0,
    val currentSyncPhase: SyncPhase? = null,
    val showingConnectionFailedAlert: Boolean = false,
    val connectionFailedMessage: UiText? = null,
    val connectionFailedTitle: UiText? = null,
    val pairingFailureKind: PairingFailureKind? = null,
    val failedPairingDeviceId: UUID? = null,
    val otherAppWarningDeviceId: UUID? = null,
    val isBusy: Boolean = false,
    val isNodeStorageFull: Boolean = false,
    val shouldShowPickerOnForeground: Boolean = false,
    val shouldCompleteFreshPairingOnForeground: Boolean = false,
    val pendingSystemPairingSetup: SystemPairingSetupPrompt? = null,
    val queuedSystemPairingSetup: SystemPairingSetupPrompt? = null,
    val queuedDeviceScanAfterSelectionDismiss: Boolean = false,
    val hasSystemPairingRegistry: Boolean = true,
)

/**
 * Connection-related UI state: status pills, sync activity, alerts and pairing state. All mutation goes
 * through one immutable-snapshot flow; the four timers (ready toast, sync-failed pill, disconnected pill and
 * the two event collectors) are lock-confined jobs on [scope], each cancelled before it is replaced.
 */
class ConnectionUiState(
    private val scope: CoroutineScope,
    private val clock: DeadlineClock = SystemRuntimeClock(),
    private val announcer: AccessibilityAnnouncer = AccessibilityAnnouncer.NONE,
) {
    private val snapshot = MutableStateFlow(ConnectionUiSnapshot())
    val state: StateFlow<ConnectionUiSnapshot> = snapshot.asStateFlow()

    private val lock = Any()
    private var readyToastJob: Job? = null
    private var syncFailedPillJob: Job? = null
    private var disconnectedPillJob: Job? = null
    private var nodeStorageEventsJob: Job? = null
    private var nodeDeletedEventsJob: Job? = null

    val showReadyToast: Boolean get() = snapshot.value.showReadyToast
    val syncFailedPillVisible: Boolean get() = snapshot.value.syncFailedPillVisible
    val disconnectedPillVisible: Boolean get() = snapshot.value.disconnectedPillVisible
    var syncActivityCount: Int
        get() = snapshot.value.syncActivityCount
        set(value) = snapshot.update { it.copy(syncActivityCount = value) }
    var currentSyncPhase: SyncPhase?
        get() = snapshot.value.currentSyncPhase
        set(value) = snapshot.update { it.copy(currentSyncPhase = value) }
    var showingConnectionFailedAlert: Boolean
        get() = snapshot.value.showingConnectionFailedAlert
        set(value) = snapshot.update { it.copy(showingConnectionFailedAlert = value) }
    var connectionFailedMessage: UiText?
        get() = snapshot.value.connectionFailedMessage
        set(value) = snapshot.update { it.copy(connectionFailedMessage = value) }
    var connectionFailedTitle: UiText?
        get() = snapshot.value.connectionFailedTitle
        set(value) = snapshot.update { it.copy(connectionFailedTitle = value) }
    var pairingFailureKind: PairingFailureKind?
        get() = snapshot.value.pairingFailureKind
        set(value) = snapshot.update { it.copy(pairingFailureKind = value) }
    var failedPairingDeviceId: UUID?
        get() = snapshot.value.failedPairingDeviceId
        set(value) = snapshot.update { it.copy(failedPairingDeviceId = value) }
    var otherAppWarningDeviceId: UUID?
        get() = snapshot.value.otherAppWarningDeviceId
        set(value) = snapshot.update { it.copy(otherAppWarningDeviceId = value) }
    var isBusy: Boolean
        get() = snapshot.value.isBusy
        set(value) = snapshot.update { it.copy(isBusy = value) }
    var isNodeStorageFull: Boolean
        get() = snapshot.value.isNodeStorageFull
        set(value) = snapshot.update { it.copy(isNodeStorageFull = value) }
    var shouldShowPickerOnForeground: Boolean
        get() = snapshot.value.shouldShowPickerOnForeground
        set(value) = snapshot.update { it.copy(shouldShowPickerOnForeground = value) }
    var shouldCompleteFreshPairingOnForeground: Boolean
        get() = snapshot.value.shouldCompleteFreshPairingOnForeground
        set(value) = snapshot.update { it.copy(shouldCompleteFreshPairingOnForeground = value) }
    var pendingSystemPairingSetup: SystemPairingSetupPrompt?
        get() = snapshot.value.pendingSystemPairingSetup
        set(value) = snapshot.update { it.copy(pendingSystemPairingSetup = value) }
    var queuedSystemPairingSetup: SystemPairingSetupPrompt?
        get() = snapshot.value.queuedSystemPairingSetup
        set(value) = snapshot.update { it.copy(queuedSystemPairingSetup = value) }
    var queuedDeviceScanAfterSelectionDismiss: Boolean
        get() = snapshot.value.queuedDeviceScanAfterSelectionDismiss
        set(value) = snapshot.update { it.copy(queuedDeviceScanAfterSelectionDismiss = value) }

    /** When false the app cannot drop the OS Bluetooth bond, so auth-failure copy names system settings. */
    var hasSystemPairingRegistry: Boolean
        get() = snapshot.value.hasSystemPairingRegistry
        set(value) = snapshot.update { it.copy(hasSystemPairingRegistry = value) }

    // region Timers

    /** Runs [action] after [delay] unless the returned job is cancelled first (Swift `guard !Task.isCancelled`). */
    private fun delayed(delay: Duration, action: () -> Unit): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        clock.sleep(delay)
        if (isActive) action()
    }

    fun showReadyToastBriefly() {
        synchronized(lock) {
            readyToastJob?.cancel()
            snapshot.update { it.copy(showReadyToast = true) }
            readyToastJob = delayed(READY_TOAST_DURATION) { snapshot.update { it.copy(showReadyToast = false) } }
        }
    }

    fun hideReadyToast() {
        synchronized(lock) { readyToastJob?.cancel(); readyToastJob = null }
        snapshot.update { it.copy(showReadyToast = false) }
    }

    fun showSyncFailedPill() {
        synchronized(lock) {
            syncFailedPillJob?.cancel()
            snapshot.update { it.copy(syncFailedPillVisible = true) }
            announcer.announce(UiText.Resource(AppLocalizableStrings.accessibilityConnectionSyncFailedDisconnecting))
            syncFailedPillJob = delayed(SYNC_FAILED_PILL_DURATION) { snapshot.update { it.copy(syncFailedPillVisible = false) } }
        }
    }

    fun hideSyncFailedPill() {
        synchronized(lock) { syncFailedPillJob?.cancel(); syncFailedPillJob = null }
        snapshot.update { it.copy(syncFailedPillVisible = false) }
    }

    /** Disconnected pill shows only after a 1 s delay so brief reconnects never flash it. */
    fun updateDisconnectedPillState(
        connectionState: DeviceConnectionState,
        lastConnectedDeviceId: UUID?,
        shouldSuppressDisconnectedPill: Boolean,
    ) {
        synchronized(lock) {
            disconnectedPillJob?.cancel()
            disconnectedPillJob = null
            if (connectionState != DeviceConnectionState.DISCONNECTED || lastConnectedDeviceId == null ||
                shouldSuppressDisconnectedPill
            ) {
                snapshot.update { it.copy(disconnectedPillVisible = false) }
                return
            }
            disconnectedPillJob = delayed(DISCONNECTED_PILL_DELAY) { snapshot.update { it.copy(disconnectedPillVisible = true) } }
        }
    }

    fun hideDisconnectedPill() {
        synchronized(lock) { disconnectedPillJob?.cancel(); disconnectedPillJob = null }
        snapshot.update { it.copy(disconnectedPillVisible = false) }
    }

    // endregion

    // region Activity tracking

    /** Sync-activity bracket start (Swift `onStarted` and the DEBUG `simulateSyncStarted`). */
    fun syncActivityStarted() = snapshot.update { it.copy(syncActivityCount = it.syncActivityCount + 1) }

    /**
     * Sync-activity bracket end. Guards against double decrement (disconnect and the sync error path can both
     * end an activity) and shows the Ready toast only when all activity ends successfully.
     */
    fun syncActivityEnded(succeeded: Boolean) {
        var reachedZero = false
        val changed = synchronized(lock) {
            val current = snapshot.value.syncActivityCount
            if (current <= 0) false else {
                snapshot.update { it.copy(syncActivityCount = current - 1) }
                reachedZero = current - 1 == 0
                true
            }
        }
        if (changed && reachedZero && succeeded) showReadyToastBriefly()
    }

    // endregion

    // region Service wiring

    /** Resets connection UI state when services become unavailable (disconnect). */
    fun handleDisconnect(
        connectionState: DeviceConnectionState,
        lastConnectedDeviceId: UUID?,
        shouldSuppressDisconnectedPill: Boolean,
    ) {
        announcer.announce(UiText.Resource(AppLocalizableStrings.accessibilityConnectionDeviceConnectionLost))
        synchronized(lock) {
            nodeStorageEventsJob?.cancel(); nodeStorageEventsJob = null
            nodeDeletedEventsJob?.cancel(); nodeDeletedEventsJob = null
        }
        snapshot.update { it.copy(syncActivityCount = 0, currentSyncPhase = null, isNodeStorageFull = false) }
        hideReadyToast()
        updateDisconnectedPillState(connectionState, lastConnectedDeviceId, shouldSuppressDisconnectedPill)
    }

    /**
     * Wires sync-activity callbacks, the resync-failed pill and the two node-storage event collectors for one
     * connection. Both event streams subscribe synchronously (registration happens at `events()`), so no storage
     * event emitted by `onConnectionEstablished` can be missed; a re-wire cancels the previous collectors.
     */
    fun wireCallbacks(
        syncCoordinator: SyncCoordinator,
        advertisementService: AdvertisementService,
        contactService: ContactService,
        resyncFailure: ResyncFailureRegistry,
    ) {
        hideDisconnectedPill()
        announcer.announce(UiText.Resource(AppLocalizableStrings.accessibilityConnectionDeviceReconnected))

        // Contacts and channels phases only, not messages.
        syncCoordinator.setSyncActivityCallbacks(
            onStarted = { syncActivityStarted() },
            onEnded = { succeeded -> syncActivityEnded(succeeded) },
            onPhaseChanged = { phase -> currentSyncPhase = phase },
        )
        resyncFailure.setOnResyncFailed { showSyncFailedPill() }

        val advertisementEvents = advertisementService.events()
        val contactEvents = contactService.events()
        synchronized(lock) {
            nodeStorageEventsJob?.cancel()
            nodeStorageEventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                advertisementEvents.collect { event ->
                    if (event is AdvertisementEvent.NodeStorageFullChanged) isNodeStorageFull = event.isFull
                }
            }
            nodeDeletedEventsJob?.cancel()
            nodeDeletedEventsJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    contactEvents.events.collect { event ->
                        if (event is ContactServiceEvent.NodeDeleted) isNodeStorageFull = false
                    }
                } catch (cancelled: CancellationException) {
                    contactEvents.close()
                    throw cancelled
                }
            }
        }
    }

    /** Cancels every timer and collector (process shutdown). */
    fun close() {
        synchronized(lock) {
            listOf(readyToastJob, syncFailedPillJob, disconnectedPillJob, nodeStorageEventsJob, nodeDeletedEventsJob)
                .forEach { it?.cancel() }
            readyToastJob = null; syncFailedPillJob = null; disconnectedPillJob = null
            nodeStorageEventsJob = null; nodeDeletedEventsJob = null
        }
    }

    // endregion

    // region Connection failure routing

    /** Generic (non-pairing) failure; clears every pairing field so stale recovery state cannot leak onto it. */
    fun presentConnectionFailure(message: UiText?) = snapshot.update {
        it.copy(
            connectionFailedTitle = null, pairingFailureKind = null, failedPairingDeviceId = null,
            connectionFailedMessage = message, showingConnectionFailedAlert = true,
        )
    }

    /** Failure of a user-initiated connect to an already-paired radio; a dead bond routes to guided re-pair. */
    fun presentSavedDeviceConnectFailure(deviceId: UUID, error: Throwable) {
        when {
            ConnectionFailures.isDeviceConnectedToOtherApp(error) -> otherAppWarningDeviceId = deviceId
            ConnectionFailures.isAuthenticationFailure(error) ->
                presentPairingFailure(PairingError.ConnectionFailed(deviceId, error, authenticationFailure = true))
            else -> presentConnectionFailure(userFacingMessage(error))
        }
    }

    /** Fresh-pair failure; a rejected PIN uses distinct copy from a dead saved bond. */
    fun presentFreshPairingFailure(error: PairingError) {
        if (error !is PairingError.ConnectionFailed || !error.isAuthenticationFailure) {
            presentPairingFailure(error)
            return
        }
        snapshot.update {
            it.copy(
                failedPairingDeviceId = error.deviceId,
                connectionFailedTitle = UiText.Resource(AppLocalizableStrings.alertPairingFailedTitle),
                connectionFailedMessage = UiText.Resource(L10nR.string.wp305_pairing_pin_rejected),
                pairingFailureKind = PairingFailureKind.PIN_REJECTED,
                showingConnectionFailedAlert = true,
            )
        }
    }

    /** Clears every field of the pairing-failure alert so a stale "Couldn't Pair" cannot present later. */
    fun clearPairingFailure() = snapshot.update {
        it.copy(
            showingConnectionFailedAlert = false, connectionFailedTitle = null, connectionFailedMessage = null,
            pairingFailureKind = null, failedPairingDeviceId = null,
        )
    }

    /** Routes a [PairingError] to the correct alert so every catch site produces identical UX. */
    fun presentPairingFailure(error: PairingError) {
        when (error) {
            is PairingError.DeviceConnectedToOtherApp -> otherAppWarningDeviceId = error.deviceId
            is PairingError.ConnectionFailed -> snapshot.update {
                if (error.isAuthenticationFailure) it.copy(
                    failedPairingDeviceId = error.deviceId,
                    connectionFailedTitle = UiText.Resource(AppLocalizableStrings.alertPairingFailedTitle),
                    connectionFailedMessage = UiText.Resource(L10nR.string.wp305_pairing_authentication_failed),
                    pairingFailureKind = PairingFailureKind.AUTHENTICATION, showingConnectionFailedAlert = true,
                ) else it.copy(
                    failedPairingDeviceId = error.deviceId, connectionFailedTitle = null,
                    connectionFailedMessage = UiText.Resource(AppOnboardingStrings.deviceScanErrorConnectionFailed),
                    pairingFailureKind = PairingFailureKind.TRANSIENT, showingConnectionFailedAlert = true,
                )
            }
        }
    }

    // endregion

    companion object {
        val READY_TOAST_DURATION: Duration = 2.seconds
        val SYNC_FAILED_PILL_DURATION: Duration = 7.seconds
        val DISCONNECTED_PILL_DELAY: Duration = 1.seconds

        /** Swift `Error.userFacingMessage` for failures with no localized mapping. */
        fun userFacingMessage(error: Throwable): UiText =
            UiText.Verbatim(error.message ?: error.javaClass.simpleName)
    }
}
