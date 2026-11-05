// AndroidOnly: WP-305 composition of the onboarding step coordinators behind OnboardingFeatureDependencies.
package com.meshcoreone.android.feature.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingFlowCoordinator(private val scope: CoroutineScope, private val deps: OnboardingFeatureDependencies) {
    private var completing = false
    private var pendingPairAfterPermission = false
    val onboarding = OnboardingState(deps.flags)
    val permissions = PermissionsCoordinator(deps.permissions)
    val deviceScan = DeviceScanCoordinator(
        scope, deps.pairing, deps.demo, deps.permissions,
        onConnected = { onboarding.append(OnboardingStep.REGION) },
        canStartConnection = { !completing && onboarding.canStartCompletion() },
    )
    val wifi = WiFiConnectionCoordinator(
        scope, deps.wifi,
        onConnected = {
            deviceScan.dismissSheet()
            onboarding.append(OnboardingStep.REGION)
        },
        canStartConnection = { !completing && onboarding.canStartCompletion() },
    )
    val region = RegionStepCoordinator(scope, deps.region, { deps.permissions.snapshot.value.location == PermissionStatus.GRANTED }) {
        onboarding.append(OnboardingStep.PRESET)
    }
    val preset = PresetStepCoordinator(
        scope, deps.region, deps.presets,
        canStart = { !completing && onboarding.canStartCompletion() },
        lifecycle = onboarding, onCompleted = ::completePreset,
    )

    private suspend fun completePreset(completion: OnboardingPresetCompletion) {
        onboarding.completeOnboardingWithReceipt(
            completion.receipt, completion.requireCurrent, completion.seal,
        )
    }

    private val clearing = MutableStateFlow(false)
    val isClearingPairings: StateFlow<Boolean> = clearing.asStateFlow()
    private val completionError = MutableStateFlow(false)
    val hasCompletionError: StateFlow<Boolean> = completionError.asStateFlow()
    private val completingWithoutDevice = MutableStateFlow(false)
    val isCompletingWithoutDevice: StateFlow<Boolean> = completingWithoutDevice.asStateFlow()
    private var clearingJob: Job? = null
    val currentStep: OnboardingStep get() = onboarding.onboardingPath.lastOrNull() ?: OnboardingStep.WELCOME

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            onboarding.lifecycleGeneration.onEach {
                clearingJob?.cancel()
                clearingJob = null
                clearing.value = false
                completing = false
                completingWithoutDevice.value = false
                completionError.value = false
                pendingPairAfterPermission = false
            }.takeWhile { !onboarding.isRetired() }.collect()
        }
    }

    /** Resumes a partially onboarded install above the welcome root (no-op once complete). */
    fun restoreStartingPath() {
        val snapshot = deps.permissions.snapshot.value
        val facts = OnboardingResumeFacts(deps.pairing.pairedAccessoryCount.value, deps.pairing.hasLastConnectedDevice)
        onboarding.replacePath(
            onboarding.suggestedStartingPath(facts, snapshot.location, snapshot.notifications, deps.region.selection.value != null),
        )
    }

    fun getStarted() = onboarding.append(OnboardingStep.PERMISSIONS)
    fun continueFromPermissions() = onboarding.append(OnboardingStep.PAIR)
    // Completion remains owned by the mounted host until it delivers navigation and retires this flow.
    fun canLeaveCurrentFlow(): Boolean =
        !completing && !preset.state.value.isApplying && !onboarding.hasCompletedOnboarding
    fun back(): Boolean = canLeaveCurrentFlow() && onboarding.pop()

    /** Just-in-time Bluetooth permission: ask on first "Add device", then resume pairing from the result. */
    fun addDevice(requestPermission: (OnboardingPermissionKind) -> Unit) {
        if (deviceScan.startPairing() == PairingBlocker.BLUETOOTH_PERMISSION) {
            pendingPairAfterPermission = true
            requestPermission(OnboardingPermissionKind.BLUETOOTH)
        }
    }

    fun onPermissionResult() {
        deps.permissions.refresh()
        if (pendingPairAfterPermission) {
            pendingPairAfterPermission = false
            deviceScan.startPairing()
        }
    }

    fun confirmNoDevice() {
        completeWithoutDevice()
    }

    fun retryCompletion() {
        if (completionError.value) completeWithoutDevice()
    }

    fun dismissCompletionError() {
        completionError.value = false
    }

    fun canCompleteWithoutDevice(): Boolean =
        onboarding.canStartCompletion() && !preset.state.value.isApplying &&
            !completing && !clearing.value && !deviceScan.state.value.localBusy &&
            !deps.pairing.connection.value.isBusy && !wifi.state.value.isConnecting

    fun dismissPairingSheet() {
        if (canDismissPairingSheet()) deviceScan.dismissSheet()
    }

    fun canDismissPairingSheet(): Boolean = !completing

    fun openPairingSheet(sheet: PairSheet) {
        if (sheet == PairSheet.WIFI && deviceScan.state.value.sheet != PairSheet.WIFI) wifi.reset()
        deviceScan.showSheet(sheet)
    }

    private fun completeWithoutDevice() {
        if (!canCompleteWithoutDevice()) return
        completing = true
        completingWithoutDevice.value = true
        val generation = onboarding.lifecycleGeneration.value
        scope.launch {
            try {
                if (onboarding.lifecycleGeneration.value != generation) return@launch
                onboarding.completeOnboarding()
                if (onboarding.lifecycleGeneration.value != generation) return@launch
                completionError.value = false
                deviceScan.dismissSheet()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (onboarding.lifecycleGeneration.value == generation) completionError.value = true
            } finally {
                if (onboarding.lifecycleGeneration.value == generation) {
                    completing = false
                    completingWithoutDevice.value = false
                }
            }
        }
    }

    fun clearStalePairings() {
        if (clearing.value || completing || !onboarding.canStartCompletion()) return
        val generation = onboarding.lifecycleGeneration.value
        clearing.update { true }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (onboarding.lifecycleGeneration.value != generation || !onboarding.canStartCompletion()) return@launch
                deps.pairing.clearStalePairings()
                currentCoroutineContext().ensureActive()
                if (onboarding.lifecycleGeneration.value != generation) return@launch
                deviceScan.dismissSheet()
            } finally {
                if (onboarding.lifecycleGeneration.value == generation) clearing.update { false }
            }
        }
        clearingJob = job
        job.start()
    }
}
