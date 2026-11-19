// PortedFrom: MC1/State/AppState+DeviceActions.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.pairing.PairingError
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.runtime.DeadlineClock
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.ui.UiText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Guided pairing and system-registry setup flow (Swift `AppState+DeviceActions`). The system-registry
 * "Forget" prompts are the CompanionDeviceManager associations that have no saved device row; a rejected
 * picker retries once on the next foreground.
 */
class PairingFlow(
    private val connection: AppConnectionPort,
    private val ui: ConnectionUiState,
    private val scope: CoroutineScope,
    private val clock: DeadlineClock = SystemRuntimeClock(),
    /** Re-wires app state after a successful pairing connect (Swift `wireServicesIfConnected`). */
    private val wireServices: suspend () -> Unit,
    /** Ends the live session before pairing a different radio (Swift `disconnect(reason: .switchingDevice)`). */
    private val disconnect: suspend (RuntimeDisconnectReason) -> Unit,
    /** Runs after a fresh pairing connects (Swift appends the onboarding region step). */
    private val onFreshPairingCompleted: () -> Unit = {},
    private val freshPairingForegroundRetryDelay: Duration = 400.milliseconds,
    private val isAppForeground: () -> Boolean = { false },
    private val discoveryLifecycle: PairingDiscoveryLifecycle? = null,
) {
    @Volatile var isConfirmingSystemPairingSetup: Boolean = false
        private set
    @Volatile var isFreshPairingForegroundRetry: Boolean = false
        private set
    private var setupConfirmationJob: Job? = null
    private var foregroundRetryJob: Job? = null
    private var foregroundPairingJob: Job? = null
    private var freshPairingCompletion = 0L
    private val pairingStateLock = Any()
    private var nextPairingStateGeneration = 0L
    private var activePairingStateGeneration: Long? = null

    private data class DeviceScanStart(val job: Job, val stateGeneration: Long?)

    /** Returns true only after a fresh pairing completed; an existing live radio is not consent or success. */
    suspend fun startDeviceScanAndAwait(onSelectionCommitted: () -> Unit = {}): Boolean {
        val completionBefore = freshPairingCompletion
        val started = startDeviceScanInternal(onSelectionCommitted)
        try {
            started.job.join()
            ui.state.first {
                !it.isBusy && it.pendingSystemPairingSetup == null && it.queuedSystemPairingSetup == null &&
                    !it.shouldCompleteFreshPairingOnForeground
            }
            return freshPairingCompletion != completionBefore
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                started.job.cancelAndJoin()
                val generation = started.stateGeneration
                val cleanupJobs = synchronized(pairingStateLock) {
                    if (generation != null && nextPairingStateGeneration == generation) {
                        listOfNotNull(setupConfirmationJob, foregroundRetryJob, foregroundPairingJob)
                    } else null
                }
                if (cleanupJobs != null) {
                    cleanupJobs.forEach { it.cancelAndJoin() }
                    synchronized(pairingStateLock) {
                        if (nextPairingStateGeneration == generation) cancelSystemPairingSetup()
                    }
                }
            }
            throw cancelled
        }
    }

    /** Start device scan/pairing. Returns the flow's job so callers and tests can await completion. */
    fun startDeviceScan(onSelectionCommitted: () -> Unit = {}): Job =
        startDeviceScanInternal(onSelectionCommitted).job

    private fun startDeviceScanInternal(
        onSelectionCommitted: () -> Unit,
        allowExistingActivity: Boolean = false,
    ): DeviceScanStart {
        if (ui.pendingSystemPairingSetup != null || isConfirmingSystemPairingSetup) {
            return DeviceScanStart(scope.launch {}, null)
        }
        val stateGeneration = synchronized(pairingStateLock) {
            if (activePairingStateGeneration != null || ui.isBusy ||
                (!allowExistingActivity && connection.isPairingFlowActive)
            ) {
                return@synchronized null
            }
            val generation = ++nextPairingStateGeneration
            activePairingStateGeneration = generation
            ui.hideDisconnectedPill()
            ui.failedPairingDeviceId = null
            ui.isBusy = true
            connection.isPairingFlowActive = true
            generation
        } ?: return DeviceScanStart(scope.launch {}, null)
        val job = scope.launch {
            var discoveryGeneration: Long? = null
            try {
                discoveryGeneration = discoveryLifecycle?.begin(
                    checkNotNull(currentCoroutineContext()[Job]),
                    onSelectionCommitted,
                )
                withPairingFlowErrorHandling {
                    val pending = connection.systemAccessoriesMissingDeviceRecord()
                    if (pending.isNotEmpty()) {
                        if (ui.pendingSystemPairingSetup == null) {
                            ui.pendingSystemPairingSetup = SystemPairingSetupPrompt(
                                pending.map { SystemPairedAccessory(it.id, it.name) },
                            )
                        }
                        return@withPairingFlowErrorHandling
                    }
                    completeFreshPairing()
                }
            } finally {
                discoveryGeneration?.let { discoveryLifecycle?.end(it) }
            }
        }
        job.invokeOnCompletion {
            synchronized(pairingStateLock) {
                if (activePairingStateGeneration == stateGeneration) {
                    if (ui.pendingSystemPairingSetup == null && ui.queuedSystemPairingSetup == null &&
                        !ui.shouldCompleteFreshPairingOnForeground
                    ) {
                        connection.isPairingFlowActive = false
                    }
                    ui.isBusy = false
                    activePairingStateGeneration = null
                }
            }
        }
        return DeviceScanStart(job, stateGeneration)
    }

    /** Device-selection sheet left the hierarchy: start a queued scan or present the queued setup prompt. */
    fun handleDeviceSelectionSheetDismissed() {
        if (ui.queuedDeviceScanAfterSelectionDismiss) {
            ui.queuedDeviceScanAfterSelectionDismiss = false
            ui.queuedSystemPairingSetup = null
            connection.isPairingFlowActive = true
            scope.launch {
                connection.stopBleScanning()
                startDeviceScanInternal(onSelectionCommitted = {}, allowExistingActivity = true)
            }
            return
        }
        val queued = ui.queuedSystemPairingSetup ?: return
        ui.queuedSystemPairingSetup = null
        if (ui.pendingSystemPairingSetup != null) return
        connection.isPairingFlowActive = true
        ui.pendingSystemPairingSetup = queued
    }

    fun cancelSystemPairingSetup() {
        foregroundRetryJob?.cancel()
        foregroundRetryJob = null
        foregroundPairingJob?.cancel()
        ui.shouldCompleteFreshPairingOnForeground = false
        isFreshPairingForegroundRetry = false
        connection.isPairingFlowActive = false
        ui.queuedSystemPairingSetup = null
        ui.pendingSystemPairingSetup = null
    }

    fun handleSystemPairingSetupSheetDismissed() {
        if (isConfirmingSystemPairingSetup) return
        if (ui.pendingSystemPairingSetup != null) return
        cancelSystemPairingSetup()
    }

    /** Forgets the pending associations, then opens the picker. Pending clears first so the system prompt is the only dialog. */
    fun confirmSystemPairingSetup(): Job? {
        if (isConfirmingSystemPairingSetup) return null
        val prompt = ui.pendingSystemPairingSetup ?: return null
        isConfirmingSystemPairingSetup = true
        connection.isPairingFlowActive = true
        ui.shouldShowPickerOnForeground = false
        ui.isBusy = true
        ui.pendingSystemPairingSetup = null
        return scope.launch {
            try {
                withPairingFlowErrorHandling {
                    connection.removeSystemAccessoriesMissingDeviceRecord(prompt.accessories.map { it.id })
                    completeFreshPairing()
                }
            } finally {
                isConfirmingSystemPairingSetup = false
                if (!ui.shouldCompleteFreshPairingOnForeground) connection.isPairingFlowActive = false
                ui.isBusy = false
            }
        }.also { setupConfirmationJob = it }
    }

    private suspend fun completeFreshPairing() {
        if (connection.connectionState != DeviceConnectionState.DISCONNECTED) {
            disconnect(RuntimeDisconnectReason.SWITCHING_DEVICE)
        }
        connection.pairNewDevice()
        wireServices()
        onFreshPairingCompleted()
        freshPairingCompletion++
    }

    private suspend fun withPairingFlowErrorHandling(work: suspend () -> Unit) {
        try {
            work()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: DevicePairingError.Cancelled) {
            // Picker dismissed, or the system removal was declined. Do not re-prompt.
            ui.shouldCompleteFreshPairingOnForeground = false
            isFreshPairingForegroundRetry = false
        } catch (failure: DevicePairingError.AlreadyInProgress) {
            // A pairing flow is already running; the re-entrant request is ignored.
        } catch (failure: DevicePairingError.PickerUnavailable) {
            handlePickerUnavailableAfterForget()
        } catch (failure: PairingError) {
            ui.presentFreshPairingFailure(failure)
        } catch (failure: Exception) {
            ui.presentConnectionFailure(ConnectionUiState.userFacingMessage(failure))
        }
    }

    /** The system rejected the picker; retry once on the next activation. A second rejection is a real failure. */
    private fun handlePickerUnavailableAfterForget() {
        if (isFreshPairingForegroundRetry) {
            isFreshPairingForegroundRetry = false
            ui.shouldCompleteFreshPairingOnForeground = false
            connection.isPairingFlowActive = false
            ui.presentConnectionFailure(UiText.Resource(AppLocalizableStrings.errorAccessorySetupPickerRestricted))
            return
        }
        scheduleFreshPairingOnForeground()
    }

    private fun scheduleFreshPairingOnForeground() {
        ui.shouldCompleteFreshPairingOnForeground = true
        connection.isPairingFlowActive = true
        foregroundRetryJob?.cancel()
        foregroundRetryJob = scope.launch {
            clock.sleep(freshPairingForegroundRetryDelay)
            resumeFreshPairingIfNeeded()
        }
    }

    suspend fun resumeFreshPairingIfNeeded() = coroutineScope {
        if (!ui.shouldCompleteFreshPairingOnForeground) return@coroutineScope
        foregroundPairingJob = coroutineContext[Job]
        ui.isBusy = true
        ui.shouldCompleteFreshPairingOnForeground = false
        isFreshPairingForegroundRetry = true
        connection.isPairingFlowActive = true
        try {
            withPairingFlowErrorHandling { completeFreshPairing() }
        } finally {
            foregroundPairingJob = null
            isFreshPairingForegroundRetry = false
            if (!ui.shouldCompleteFreshPairingOnForeground) connection.isPairingFlowActive = false
            ui.isBusy = false
        }
    }

    /** Removes a device that failed pairing (wrong PIN) and retries automatically. */
    fun removeFailedPairingAndRetry(): Job? {
        val deviceId = ui.failedPairingDeviceId ?: return null
        ui.clearPairingFailure()
        return scope.launch {
            try {
                connection.removeFailedPairing(deviceId)
                ui.failedPairingDeviceId = null
                if (connection.hasSystemPairingRegistry && !isAppForeground()) {
                    // Removing the association can bounce the scene; wait for the next activation.
                    ui.shouldShowPickerOnForeground = true
                } else {
                    // Companion disassociation does not leave a foreground host or trigger activation.
                    startDeviceScan().join()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                ui.presentConnectionFailure(ConnectionUiState.userFacingMessage(failure))
            }
        }
    }

    /** Retries the device that just failed without removing the bond (transient failures only). */
    suspend fun retryFailedPairingConnect() {
        val deviceId = ui.failedPairingDeviceId ?: return
        ui.isBusy = true
        try {
            connection.connect(deviceId, forceReconnect = true)
            ui.failedPairingDeviceId = null
            wireServices()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            if (ConnectionFailures.isDeviceConnectedToOtherApp(failure)) {
                ui.failedPairingDeviceId = null
                ui.presentPairingFailure(PairingError.DeviceConnectedToOtherApp(deviceId))
            } else {
                ui.presentPairingFailure(
                    PairingError.ConnectionFailed(deviceId, failure, ConnectionFailures.isAuthenticationFailure(failure)),
                )
            }
        } finally {
            ui.isBusy = false
        }
    }

    /** Foreground handling of the pairing-related deferrals (Swift `handleBecameActive` prefix). Returns true when it consumed activation. */
    suspend fun handleBecameActive(): Boolean {
        if (ui.shouldCompleteFreshPairingOnForeground) {
            resumeFreshPairingIfNeeded()
            return true
        }
        if (ui.shouldShowPickerOnForeground && ui.pendingSystemPairingSetup == null && ui.queuedSystemPairingSetup == null) {
            ui.shouldShowPickerOnForeground = false
            startDeviceScan()
        }
        return false
    }
}
