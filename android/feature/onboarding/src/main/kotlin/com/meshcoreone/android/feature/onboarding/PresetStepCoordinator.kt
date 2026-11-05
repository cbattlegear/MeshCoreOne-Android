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
    private var completionJobGeneration = 0L
    private val currentGeneration: Long get() = lifecycle?.lifecycleGeneration?.value ?: 0L

    init {
        lifecycle?.onInvalidation {
            retryCount = 0
            appliedPreset = null
            mutable.value = PresetStepState()
        }
    }

    fun visiblePresets(): List<OnboardingPresetOption> = regionPort.catalog.presets(regionPort.selection.value)

    fun select(id: String) = mutable.update {
        if (it.isApplying || it.isCompleted || !canStart()) it else it.copy(selectedId = id)
    }
    fun dismissError() = mutable.update { it.copy(error = null) }

    fun canApply(canApplyToRadio: Boolean, state: PresetStepState): Boolean =
        !state.isApplying && !state.isCompleted && canStart() && state.selectedId != null && canApplyToRadio

    /** Resolves the user's selection against what is actually visible (a stale id is ignored). */
    fun apply(id: String? = mutable.value.selectedId) {
        if (mutable.value.isApplying || mutable.value.isCompleted || !canStart()) return
        val option = visiblePresets().firstOrNull { it.id == id } ?: return
        val target = presets.currentTarget()
        val generation = currentGeneration
        if (appliedPreset == AppliedPreset(option.id, target, generation)) {
            beginCompletionPersistence()
            return
        }
        appliedPreset = null
        mutable.update {
            it.copy(isApplying = true, error = null, retryAlert = null, completionNeedsRetry = false)
        }
        scope.launch {
            if (currentGeneration != generation) return@launch
            val outcome = try {
                presets.apply(option.id, target)
            } catch (cancel: CancellationException) {
                if (currentGeneration == generation) mutable.update { it.copy(isApplying = false) }
                throw cancel
            } catch (failure: Exception) {
                OnboardingPresetOutcome.Failed(UiText.Verbatim(failure.message.orEmpty()))
            }
            finish(option.id, target, generation, outcome)
        }
    }

    private suspend fun finish(
        id: String, target: OnboardingPresetTarget?, generation: Long, outcome: OnboardingPresetOutcome,
    ) {
        if (currentGeneration != generation) return
        if (!canStart()) {
            mutable.update { it.copy(isApplying = false) }
            return
        }
        if (presets.currentTarget() != target) {
            appliedPreset = null
            mutable.update {
                it.copy(isApplying = false, completionNeedsRetry = false, error = UiText.Resource(O.presetErrorNotConnected))
            }
            return
        }
        when (outcome) {
            OnboardingPresetOutcome.Applied, OnboardingPresetOutcome.NoRadioToConfigure -> {
                retryCount = 0
                appliedPreset = AppliedPreset(id, target, generation)
                beginCompletionPersistence()
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

    private fun beginCompletionPersistence() {
        val applied = appliedPreset ?: return
        val generation = applied.generation
        if (currentGeneration != generation) return
        val completion = OnboardingPresetCompletion(
            OnboardingCompletionReceipt.Preset(applied.id, applied.target), applied.targetGuard(), applied.sealer(),
        )
        if (completionJobGeneration == generation && completionPersistenceJob?.isActive == true) return
        mutable.update { it.copy(isApplying = true, error = null, retryAlert = null) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                if (currentGeneration != generation) return@launch
                completion.requireCurrent()
                onCompleted(completion)
                if (currentGeneration != generation) return@launch
                appliedPreset = null
                mutable.update {
                    it.copy(isApplying = false, isCompleted = true, completionNeedsRetry = false, commitTick = it.commitTick + 1)
                }
            } catch (_: OnboardingPresetTargetChangedException) {
                if (currentGeneration != generation) return@launch
                appliedPreset = null
                mutable.update {
                    it.copy(isApplying = false, completionNeedsRetry = false, error = UiText.Resource(O.presetErrorNotConnected))
                }
            } catch (cancelled: CancellationException) {
                if (currentGeneration == generation) mutable.update { it.copy(isApplying = false) }
                throw cancelled
            } catch (_: Exception) {
                if (currentGeneration != generation) return@launch
                mutable.update {
                    it.copy(isApplying = false, error = UiText.Resource(S.alertRetryUnableToSave), completionNeedsRetry = true)
                }
            }
        }
        completionJobGeneration = generation
        completionPersistenceJob = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException && currentGeneration == generation) {
                mutable.update { it.copy(isApplying = false) }
            }
        }
        job.start()
    }

    fun retry() {
        val current = mutable.value
        if (current.isApplying || current.isCompleted || !canStart()) return
        val alert = current.retryAlert
        if (alert != null) {
            mutable.update { it.copy(retryAlert = null) }
            apply(alert.presetId)
            return
        }
        if (appliedPreset != null && current.completionNeedsRetry) apply(current.selectedId)
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
