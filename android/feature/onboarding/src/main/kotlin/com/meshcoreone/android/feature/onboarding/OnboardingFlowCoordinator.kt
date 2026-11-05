// AndroidOnly: WP-305 composition of the onboarding step coordinators behind OnboardingFeatureDependencies.
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingFlowCoordinator(private val scope: CoroutineScope, private val deps: OnboardingFeatureDependencies) {
    val onboarding = OnboardingState(deps.flags)
    val permissions = PermissionsCoordinator(deps.permissions)
    val deviceScan = DeviceScanCoordinator(scope, deps.pairing, deps.demo, deps.permissions) { onboarding.append(OnboardingStep.REGION) }
    val wifi = WiFiConnectionCoordinator(scope, deps.wifi) {
        deviceScan.dismissSheet()
        onboarding.append(OnboardingStep.REGION)
    }
    val region = RegionStepCoordinator(scope, deps.region, { deps.permissions.snapshot.value.location == PermissionStatus.GRANTED }) {
        onboarding.append(OnboardingStep.PRESET)
    }
    val preset = PresetStepCoordinator(scope, deps.region, deps.presets, onCompleted = ::completePreset)

    private suspend fun completePreset() {
        onboarding.completeOnboardingWithReceipt(
            preset.completionReceipt(), preset.completionTargetGuard(), preset.completionSealer(),
        )
    }

    private val clearing = MutableStateFlow(false)
    val isClearingPairings: StateFlow<Boolean> = clearing.asStateFlow()
    private var pendingPairAfterPermission = false
    private var completingWithoutDevice = false

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
    fun back(): Boolean = onboarding.pop()

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
        if (completingWithoutDevice) return
        completingWithoutDevice = true
        deviceScan.dismissSheet()
        scope.launch {
            try {
                onboarding.completeOnboarding()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                deviceScan.reportCompletionFailure(UiText.Verbatim(failure.message.orEmpty()))
            } finally {
                completingWithoutDevice = false
            }
        }
    }

    fun retire() = onboarding.retireCompletion()

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
