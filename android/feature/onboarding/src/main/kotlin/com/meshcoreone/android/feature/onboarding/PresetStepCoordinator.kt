// PortedFrom: MC1/Views/Onboarding/PresetStepView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PresetRetryAlert(val message: UiText, val presetId: String, val retryCount: Int) {
    val isMaxRetriesExceeded: Boolean get() = retryCount >= MAX_RETRIES

    companion object { const val MAX_RETRIES = 3 }
}

data class PresetStepState(
    val selectedId: String? = null,
    val isApplying: Boolean = false,
    val error: UiText? = null,
    val retryAlert: PresetRetryAlert? = null,
    val commitTick: Int = 0,
    val completionNeedsRetry: Boolean = false,
    val isCompleted: Boolean = false,
)

internal class OnboardingPresetTargetChangedException : IllegalStateException("Onboarding radio session changed")

data class OnboardingPresetCompletion(
    val receipt: OnboardingCompletionReceipt.Preset,
    val requireCurrent: () -> Unit,
    val seal: (OnboardingCompletionAttempt) -> Boolean,
    val generation: Long? = null,
)

class PresetStepCoordinator(
    private val scope: CoroutineScope,
    private val regionPort: OnboardingRegionPort,
    private val presets: OnboardingPresetPort,
    private val canStart: () -> Boolean = { true },
    private val lifecycle: OnboardingState? = null,
    private val onCompleted: suspend (OnboardingPresetCompletion) -> Unit,
) {
    private val mutable = MutableStateFlow(PresetStepState())
    val state: StateFlow<PresetStepState> = mutable.asStateFlow()
    private var retryCount = 0
    private data class AppliedPreset(val id: String, val target: OnboardingPresetTarget?, val generation: Long)
    private var appliedPreset: AppliedPreset? = null
    private var completionPersistenceJob: Job? = null
    private var applyJob: Job? = null
    private val lock = Any()
    private var completionJobGeneration = 0L
    private val currentGeneration: Long get() = lifecycle?.lifecycleGeneration?.value ?: 0L

    init {
        lifecycle?.onInvalidation {
            applyJob?.cancel()
            completionPersistenceJob?.cancel()
            retryCount = 0
            appliedPreset = null
            mutable.value = PresetStepState()
        }
    }

    private fun <T> withLifecycle(block: () -> T): T =
        if (lifecycle != null) lifecycle.withLifecycle(block) else synchronized(lock) { block() }

    private fun updateCurrent(generation: Long, transform: (PresetStepState) -> PresetStepState) = withLifecycle {
        if (currentGeneration == generation) mutable.update(transform)
    }

    fun visiblePresets(): List<OnboardingPresetOption> = regionPort.catalog.presets(regionPort.selection.value)

    fun select(id: String) = mutable.update {
        if (it.isApplying || it.isCompleted || !canStart()) it else it.copy(selectedId = id)
    }
    fun dismissError() = mutable.update { it.copy(error = null) }

    fun canApply(canApplyToRadio: Boolean, state: PresetStepState): Boolean =
        !state.isApplying && !state.isCompleted &&
            (lifecycle?.canReconcileCompletion() == true || (canStart() && state.selectedId != null && canApplyToRadio))

    /** Resolves the user's selection against what is actually visible (a stale id is ignored). */
    fun apply(id: String? = mutable.value.selectedId) = applyInGeneration(id, null)

    private fun applyInGeneration(id: String?, expectedGeneration: Long?) {
        val job = withLifecycle {
            val generation = currentGeneration
            if (expectedGeneration != null && generation != expectedGeneration) return@withLifecycle null
            if (mutable.value.isApplying || mutable.value.isCompleted) return@withLifecycle null
            if (lifecycle?.canReconcileCompletion() == true) return@withLifecycle beginCompletionPersistence()
            if (!canStart() || currentGeneration != generation) return@withLifecycle null
            val option = visiblePresets().firstOrNull { it.id == id } ?: return@withLifecycle null
            val target = presets.currentTarget()
            if (!canStart() || currentGeneration != generation) return@withLifecycle null
            if (appliedPreset == AppliedPreset(option.id, target, generation)) {
                return@withLifecycle beginCompletionPersistence()
            }
            appliedPreset = null
            mutable.update {
                it.copy(isApplying = true, error = null, retryAlert = null, completionNeedsRetry = false)
            }
            scope.launch(start = CoroutineStart.LAZY) {
                if (currentGeneration != generation) return@launch
                val outcome = try {
                    presets.apply(option.id, target)
                } catch (cancel: CancellationException) {
                    updateCurrent(generation) { it.copy(isApplying = false) }
                    throw cancel
                } catch (failure: Exception) {
                    OnboardingPresetOutcome.Failed(UiText.Verbatim(failure.message.orEmpty()))
                }
                finish(option.id, target, generation, outcome)
            }.also { applyJob = it }
        }
        job?.start()
    }

    private fun finish(
        id: String, target: OnboardingPresetTarget?, generation: Long, outcome: OnboardingPresetOutcome,
    ) {
        val job = withLifecycle {
            if (currentGeneration != generation) return@withLifecycle null
            val admitted = canStart()
            if (currentGeneration != generation) return@withLifecycle null
            if (!admitted) {
                mutable.update { it.copy(isApplying = false) }
                return@withLifecycle null
            }
            val currentTarget = presets.currentTarget()
            if (currentGeneration != generation) return@withLifecycle null
            if (currentTarget != target) {
                appliedPreset = null
                mutable.update {
                    it.copy(isApplying = false, completionNeedsRetry = false, error = UiText.Resource(O.presetErrorNotConnected))
                }
                return@withLifecycle null
            }
            when (outcome) {
                OnboardingPresetOutcome.Applied, OnboardingPresetOutcome.NoRadioToConfigure -> {
                    retryCount = 0
                    appliedPreset = AppliedPreset(id, target, generation)
                    return@withLifecycle beginCompletionPersistence()
                }
                OnboardingPresetOutcome.NotConnected -> mutable.update {
                    it.copy(isApplying = false, error = UiText.Resource(O.presetErrorNotConnected))
                }
                is OnboardingPresetOutcome.Retryable -> {
                    retryCount += 1
                    mutable.update { it.copy(isApplying = false, retryAlert = PresetRetryAlert(outcome.message, id, retryCount)) }
                }
                is OnboardingPresetOutcome.Failed -> mutable.update { it.copy(isApplying = false, error = outcome.message) }
            }
            null
        }
        job?.start()
    }

    private fun AppliedPreset.targetGuard(): () -> Unit {
        return {
            if (currentGeneration != generation || presets.currentTarget() != target) {
                throw OnboardingPresetTargetChangedException()
            }
        }
    }

    private fun AppliedPreset.sealer(): (OnboardingCompletionAttempt) -> Boolean {
        return { attempt ->
            if (currentGeneration != generation) throw OnboardingPresetTargetChangedException()
            val sealed = presets.sealCompletion(target, attempt)
            if (!sealed && attempt.rejection() == null) throw OnboardingPresetTargetChangedException()
            sealed
        }
    }
    internal fun completionTargetGuard(): () -> Unit = checkNotNull(appliedPreset).targetGuard()
    internal fun completionSealer(): (OnboardingCompletionAttempt) -> Boolean = checkNotNull(appliedPreset).sealer()
    internal fun completionReceipt(): OnboardingCompletionReceipt.Preset =
        checkNotNull(appliedPreset).let { OnboardingCompletionReceipt.Preset(it.id, it.target) }

    private fun beginCompletionPersistence(): Job? {
        val applied = appliedPreset ?: return null
        val generation = applied.generation
        if (currentGeneration != generation) return null
        val completion = OnboardingPresetCompletion(
            OnboardingCompletionReceipt.Preset(applied.id, applied.target), applied.targetGuard(), applied.sealer(),
            generation,
        )
        if (completionJobGeneration == generation && completionPersistenceJob?.isActive == true) return null
        mutable.update { it.copy(isApplying = true, error = null, retryAlert = null) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (currentGeneration != generation) return@launch
                if (lifecycle?.canReconcileCompletion() != true) completion.requireCurrent()
                onCompleted(completion)
                withLifecycle {
                    if (currentGeneration == generation) {
                        appliedPreset = null
                        mutable.update {
                            it.copy(isApplying = false, isCompleted = true, completionNeedsRetry = false, commitTick = it.commitTick + 1)
                        }
                    }
                }
            } catch (_: OnboardingPresetTargetChangedException) {
                withLifecycle {
                    if (currentGeneration == generation) {
                        appliedPreset = null
                        mutable.update {
                            it.copy(isApplying = false, completionNeedsRetry = false, error = UiText.Resource(O.presetErrorNotConnected))
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                updateCurrent(generation) { it.copy(isApplying = false) }
                throw cancelled
            } catch (_: Exception) {
                updateCurrent(generation) {
                    it.copy(isApplying = false, error = UiText.Resource(S.alertRetryUnableToSave), completionNeedsRetry = true)
                }
            }
        }
        completionJobGeneration = generation
        completionPersistenceJob = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                updateCurrent(generation) { it.copy(isApplying = false) }
            }
        }
        return job
    }

    fun retry() {
        val request = withLifecycle {
            val generation = currentGeneration
            val current = mutable.value
            if (current.isApplying || current.isCompleted ||
                (!canStart() && lifecycle?.canReconcileCompletion() != true) || currentGeneration != generation
            ) return@withLifecycle null
            val alert = current.retryAlert
            when {
                alert != null -> {
                    mutable.update { it.copy(retryAlert = null) }
                    alert.presetId to generation
                }
                appliedPreset != null && current.completionNeedsRetry -> checkNotNull(appliedPreset).id to generation
                else -> null
            }
        }
        request?.let { (id, generation) -> applyInGeneration(id, generation) }
    }

    /** Cancel or the max-retries acknowledgement; the latter surfaces the generic fallback. */
    fun dismissRetryAlert() {
        val alert = mutable.value.retryAlert ?: return
        retryCount = 0
        mutable.update {
            it.copy(
                retryAlert = null,
                error = if (alert.isMaxRetriesExceeded) UiText.Resource(S.alertRetryFallbackMessage) else it.error,
            )
        }
    }
}
