// PortedFrom: MC1/Views/Onboarding/PresetStepView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

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

class PresetStepCoordinator(
    private val scope: CoroutineScope,
    private val regionPort: OnboardingRegionPort,
    private val presets: OnboardingPresetPort,
    private val onCompleted: suspend () -> Unit,
) {
    private val mutable = MutableStateFlow(PresetStepState())
    val state: StateFlow<PresetStepState> = mutable.asStateFlow()
    private var retryCount = 0

    fun visiblePresets(): List<OnboardingPresetOption> = regionPort.catalog.presets(regionPort.selection.value)

    fun select(id: String) = mutable.update { it.copy(selectedId = id) }
    fun dismissError() = mutable.update { it.copy(error = null) }

    fun canApply(canApplyToRadio: Boolean, state: PresetStepState): Boolean =
        !state.isApplying && state.selectedId != null && canApplyToRadio

    /** Resolves the user's selection against what is actually visible (a stale id is ignored). */
    fun apply(id: String? = mutable.value.selectedId) {
        val option = visiblePresets().firstOrNull { it.id == id } ?: return
        if (mutable.value.isApplying) return
        mutable.update { it.copy(isApplying = true) }
        scope.launch {
            try {
                finish(option.id, presets.apply(option.id))
            } catch (cancel: CancellationException) {
                mutable.update { it.copy(isApplying = false) }
                throw cancel
            } catch (failure: Exception) {
                mutable.update { it.copy(isApplying = false, error = UiText.Verbatim(failure.message.orEmpty())) }
            }
        }
    }

    private suspend fun finish(id: String, outcome: OnboardingPresetOutcome) {
        when (outcome) {
            OnboardingPresetOutcome.Applied, OnboardingPresetOutcome.NoRadioToConfigure -> {
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
