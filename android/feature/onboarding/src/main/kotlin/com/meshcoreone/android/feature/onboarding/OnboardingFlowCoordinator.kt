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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class OnboardingFlowCoordinator(private val scope: CoroutineScope, private val deps: OnboardingFeatureDependencies) {
    private var completing = false
    @Volatile
    private var refreshingCompletionPresentation = false
    private var pendingPairAfterPermission = false
    val onboarding = OnboardingState(deps.flags)
    val permissions = PermissionsCoordinator(deps.permissions)
    val deviceScan = DeviceScanCoordinator(
        scope, deps.pairing, deps.demo, deps.permissions,
        onConnected = { onboarding.append(OnboardingStep.REGION) },
        canStartConnection = { !refreshingCompletionPresentation && !completing && onboarding.canStartCompletion() },
        lifecycle = onboarding,
    )
    val wifi = WiFiConnectionCoordinator(
        scope, deps.wifi,
        onConnected = {
            val generation = onboarding.lifecycleGeneration.value
            deviceScan.dismissSheet()
            if (
                onboarding.lifecycleGeneration.value == generation &&
                !refreshingCompletionPresentation &&
                !completing &&
                onboarding.canStartCompletion()
            ) {
                onboarding.append(OnboardingStep.REGION)
            }
        },
        canStartConnection = { !refreshingCompletionPresentation && !completing && onboarding.canStartCompletion() },
        lifecycle = onboarding,
    )
    val region = RegionStepCoordinator(scope, deps.region, { deps.permissions.snapshot.value.location == PermissionStatus.GRANTED }) {
        onboarding.append(OnboardingStep.PRESET)
    }
    val preset = PresetStepCoordinator(
        scope, deps.region, deps.presets,
        canStart = { !refreshingCompletionPresentation && !completing && onboarding.canStartCompletion() },
        lifecycle = onboarding, onCompleted = ::completePreset,
    )

    private suspend fun completePreset(completion: OnboardingPresetCompletion) {
        if (onboarding.canReconcileCompletion()) {
            check(onboarding.reconcileCompletion(completion.generation)) {
                "Onboarding completion was not committed; retry is now available"
            }
        } else {
            onboarding.completeOnboardingWithReceipt(
                completion.receipt, completion.requireCurrent, completion.seal,
            )
        }
    }

    private val clearing = MutableStateFlow(false)
    val isClearingPairings: StateFlow<Boolean> = clearing.asStateFlow()
    private val completionError = MutableStateFlow(false)
    val hasCompletionError: StateFlow<Boolean> = completionError.asStateFlow()
    private val completingWithoutDevice = MutableStateFlow(false)
    val isCompletingWithoutDevice: StateFlow<Boolean> = completingWithoutDevice.asStateFlow()
    private var clearingJob: Job? = null
    private var completionJob: Job? = null
    val currentStep: OnboardingStep get() = onboarding.onboardingPath.lastOrNull() ?: OnboardingStep.WELCOME

    init {
        onboarding.onInvalidation {
            clearingJob?.cancel()
            completionJob?.cancel()
            clearingJob = null
            clearing.value = false
            completing = false
            completingWithoutDevice.value = false
            completionError.value = false
            pendingPairAfterPermission = false
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
        !completing && !preset.state.value.isApplying && !onboarding.hasCompletedOnboarding &&
            !onboarding.hasUnsettledCompletion
    fun back(): Boolean = canLeaveCurrentFlow() && onboarding.pop()

    /**
     * Runs a non-blocking atomic host-witness swap only while this flow is idle.
     * [update] must not suspend, perform I/O or navigation, or call back into product behavior.
     */
    fun refreshCompletionPresentationIfIdle(update: () -> Boolean): Boolean = onboarding.withLifecycle {
        if (
            refreshingCompletionPresentation ||
            completing ||
            clearing.value ||
            preset.state.value.isApplying ||
            onboarding.hasCompletedOnboarding ||
            onboarding.hasUnsettledCompletion ||
            !onboarding.canStartCompletion() ||
            onboarding.canReconcileCompletion()
        ) return@withLifecycle false

        refreshingCompletionPresentation = true
        try {
            update()
        } finally {
            refreshingCompletionPresentation = false
        }
    }

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

    fun canCompleteWithoutDevice(): Boolean = onboarding.withLifecycle {
        !refreshingCompletionPresentation &&
            (onboarding.canStartCompletion() || onboarding.canReconcileCompletion()) &&
            !preset.state.value.isApplying &&
            !completing &&
            !clearing.value &&
            !deviceScan.state.value.localBusy &&
            !deps.pairing.connection.value.isBusy &&
            !wifi.state.value.isConnecting
    }

    fun dismissPairingSheet() {
        if (canDismissPairingSheet()) deviceScan.dismissSheet()
    }

    fun canDismissPairingSheet(): Boolean = !completing

    fun openPairingSheet(sheet: PairSheet) {
        if (sheet == PairSheet.WIFI && deviceScan.state.value.sheet != PairSheet.WIFI) wifi.reset()
        deviceScan.showSheet(sheet)
    }

    private fun completeWithoutDevice() {
        val job = onboarding.withLifecycle {
            if (!canCompleteWithoutDevice()) return@withLifecycle null
            val generation = onboarding.lifecycleGeneration.value
            val reconciling = onboarding.canReconcileCompletion()
            val targetAtAttempt = if (reconciling) null else deps.presets.currentTarget()
            if (
                onboarding.lifecycleGeneration.value != generation ||
                !canCompleteWithoutDevice() ||
                onboarding.canReconcileCompletion() != reconciling
            ) return@withLifecycle null
            val sessionAtAttempt = targetAtAttempt?.toSessionToken()
            completing = true
            completingWithoutDevice.value = true
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (reconciling) {
                        check(onboarding.reconcileCompletion(generation)) {
                            "Onboarding completion was not committed; retry is now available"
                        }
                    } else {
                        onboarding.completeOnboardingWithSeal(
                            sessionAtAttempt = sessionAtAttempt,
                            requireCurrent = {
                                check(onboarding.lifecycleGeneration.value == generation) {
                                    "Onboarding attempt was retired"
                                }
                            },
                            seal = { attempt ->
                                targetAtAttempt?.let { deps.presets.sealCompletion(it, attempt) }
                                    ?: attempt.trySeal()
                            },
                        )
                    }
                    onboarding.withLifecycle {
                        if (onboarding.lifecycleGeneration.value == generation) {
                            completionError.value = false
                            deviceScan.dismissSheet()
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    onboarding.withLifecycle {
                        if (onboarding.lifecycleGeneration.value == generation) completionError.value = true
                    }
                } finally {
                    onboarding.withLifecycle {
                        if (onboarding.lifecycleGeneration.value == generation) {
                            completing = false
                            completingWithoutDevice.value = false
                        }
                    }
                }
            }.also { completionJob = it }
        }
        job?.start()
    }

    fun clearStalePairings() {
        val job = onboarding.withLifecycle {
            if (clearing.value || refreshingCompletionPresentation || completing || !onboarding.canStartCompletion()) {
                return@withLifecycle null
            }
            val generation = onboarding.lifecycleGeneration.value
            clearing.update { true }
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (onboarding.lifecycleGeneration.value != generation || !onboarding.canStartCompletion()) return@launch
                    deps.pairing.clearStalePairings()
                    currentCoroutineContext().ensureActive()
                    onboarding.withLifecycle {
                        if (onboarding.lifecycleGeneration.value == generation) deviceScan.dismissSheet()
                    }
                } finally {
                    onboarding.withLifecycle {
                        if (onboarding.lifecycleGeneration.value == generation) clearing.update { false }
                    }
                }
            }.also { clearingJob = it }
        }
        job?.start()
    }
}
