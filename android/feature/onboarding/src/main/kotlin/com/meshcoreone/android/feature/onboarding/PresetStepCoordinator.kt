// PortedFrom: MC1/Views/Onboarding/PresetStepView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
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
)

internal class OnboardingPresetTargetChangedException : IllegalStateException("Onboarding radio session changed")

class PresetStepCoordinator(
    private val scope: CoroutineScope,
    private val regionPort: OnboardingRegionPort,
    private val presets: OnboardingPresetPort,
    private val onCompleted: suspend () -> Unit,
) {
    private val mutable = MutableStateFlow(PresetStepState())
    val state: StateFlow<PresetStepState> = mutable.asStateFlow()
    private var retryCount = 0
    private var appliedPresetId: String? = null
    private var appliedPresetTarget: OnboardingPresetTarget? = null

    fun visiblePresets(): List<OnboardingPresetOption> = regionPort.catalog.presets(regionPort.selection.value)

    fun select(id: String) = mutable.update { it.copy(selectedId = id) }
    fun dismissError() = mutable.update { it.copy(error = null) }

    fun canApply(canApplyToRadio: Boolean, state: PresetStepState): Boolean =
        !state.isApplying && state.selectedId != null && canApplyToRadio

    /** Resolves the user's selection against what is actually visible (a stale id is ignored). */
    fun apply(id: String? = mutable.value.selectedId) {
        val option = visiblePresets().firstOrNull { it.id == id } ?: return
        if (mutable.value.isApplying) return
        val target = presets.currentTarget()
        mutable.update { it.copy(isApplying = true) }
        scope.launch {
            try {
                finish(option.id, target, presets.apply(option.id, target))
            } catch (cancel: CancellationException) {
                mutable.update { it.copy(isApplying = false) }
                throw cancel
            } catch (_: OnboardingPresetTargetChangedException) {
                mutable.update { it.copy(isApplying = false, error = UiText.Resource(O.presetErrorNotConnected)) }
            } catch (failure: Exception) {
                mutable.update { it.copy(isApplying = false, error = UiText.Verbatim(failure.message.orEmpty())) }
            }
        }
    }

    private suspend fun finish(id: String, target: OnboardingPresetTarget?, outcome: OnboardingPresetOutcome) {
        if (presets.currentTarget() != target) {
            mutable.update { it.copy(isApplying = false, error = UiText.Resource(O.presetErrorNotConnected)) }
            return
        }
        when (outcome) {
            OnboardingPresetOutcome.Applied, OnboardingPresetOutcome.NoRadioToConfigure -> {
                appliedPresetId = id
                appliedPresetTarget = target
                onCompleted()
                retryCount = 0
                mutable.update { it.copy(isApplying = false, retryAlert = null, commitTick = it.commitTick + 1) }
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

    internal fun completionTargetGuard(): () -> Unit {
        val target = appliedPresetTarget
        return {
            if (presets.currentTarget() != target) throw OnboardingPresetTargetChangedException()
        }
    }

    internal fun completionSealer(): (OnboardingCompletionAttempt) -> Boolean {
        val target = appliedPresetTarget
        return { attempt ->
            val sealed = presets.sealCompletion(target, attempt)
            if (!sealed && attempt.rejection() == null) throw OnboardingPresetTargetChangedException()
            sealed
        }
    }

    internal fun completionReceipt(): OnboardingCompletionReceipt.Preset =
        OnboardingCompletionReceipt.Preset(checkNotNull(appliedPresetId), appliedPresetTarget)

    fun retry() {
        val alert = mutable.value.retryAlert ?: return
        mutable.update { it.copy(retryAlert = null) }
        apply(alert.presetId)
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
