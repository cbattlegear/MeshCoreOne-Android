// AndroidOnly: WP-305 composition of the onboarding step coordinators behind OnboardingFeatureDependencies.
package com.meshcoreone.android.feature.onboarding

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
        canStartConnection = { !completing },
    )
    val wifi = WiFiConnectionCoordinator(
        scope, deps.wifi,
        onConnected = {
            deviceScan.dismissSheet()
            onboarding.append(OnboardingStep.REGION)
        },
        canStartConnection = { !completing },
    )
    val region = RegionStepCoordinator(scope, deps.region, { deps.permissions.snapshot.value.location == PermissionStatus.GRANTED }) {
        onboarding.append(OnboardingStep.PRESET)
    }
    val preset = PresetStepCoordinator(scope, deps.region, deps.presets) { onboarding.completeOnboarding() }

    private val clearing = MutableStateFlow(false)
    val isClearingPairings: StateFlow<Boolean> = clearing.asStateFlow()
    private val completionError = MutableStateFlow(false)
    val hasCompletionError: StateFlow<Boolean> = completionError.asStateFlow()
    private val completingWithoutDevice = MutableStateFlow(false)
    val isCompletingWithoutDevice: StateFlow<Boolean> = completingWithoutDevice.asStateFlow()
    val canLeaveCurrentFlowState: StateFlow<Boolean> = combine(
        preset.state,
        isCompletingWithoutDevice,
    ) { presetState, completingWithoutDevice ->
        !completingWithoutDevice && !presetState.isApplying
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        initialValue = !completingWithoutDevice.value && canLeaveCurrentFlow(),
    )
    val currentStep: OnboardingStep get() = onboarding.onboardingPath.lastOrNull() ?: OnboardingStep.WELCOME

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
    fun canLeaveCurrentFlow(): Boolean = !completing && !preset.state.value.isApplying
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
        !completing && !deviceScan.state.value.localBusy &&
            !deps.pairing.connection.value.isBusy && !wifi.state.value.isConnecting

    fun dismissPairingSheet() {
        if (canDismissPairingSheet()) deviceScan.dismissSheet()
    }

    fun canDismissPairingSheet(): Boolean = !completing

    private fun completeWithoutDevice() {
        if (!canCompleteWithoutDevice()) return
        completing = true
        completingWithoutDevice.value = true
        scope.launch {
            try {
                onboarding.completeOnboarding()
                completionError.value = false
                deviceScan.dismissSheet()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                completionError.value = true
            } finally {
                completing = false
                completingWithoutDevice.value = false
            }
        }
    }

    fun clearStalePairings() {
        if (clearing.value) return
        clearing.update { true }
        scope.launch {
            try {
                deps.pairing.clearStalePairings()
                deviceScan.dismissSheet()
            } finally {
                clearing.update { false }
            }
        }
    }
}
