// PortedFrom: MC1/Views/Onboarding/PresetStepView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding

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
    private var appliedPresetId: String? = null
    private var completionPersistenceJob: Job? = null

    fun visiblePresets(): List<OnboardingPresetOption> = regionPort.catalog.presets(regionPort.selection.value)

    fun select(id: String) = mutable.update { it.copy(selectedId = id) }
    fun dismissError() = mutable.update { it.copy(error = null) }

    fun canApply(canApplyToRadio: Boolean, state: PresetStepState): Boolean =
        !state.isApplying && state.selectedId != null && canApplyToRadio

    /** Resolves the user's selection against what is actually visible (a stale id is ignored). */
    fun apply(id: String? = mutable.value.selectedId) {
        if (mutable.value.isApplying) return
        val option = visiblePresets().firstOrNull { it.id == id } ?: return
        if (appliedPresetId == option.id) {
            beginCompletionPersistence()
            return
        }
        mutable.update { it.copy(isApplying = true, error = null, retryAlert = null) }
        scope.launch {
            val outcome = try {
                presets.apply(option.id)
            } catch (cancel: CancellationException) {
                mutable.update { it.copy(isApplying = false) }
                throw cancel
            } catch (failure: Exception) {
                OnboardingPresetOutcome.Failed(UiText.Verbatim(failure.message.orEmpty()))
            }
            finish(option.id, outcome)
        }
    }

    private suspend fun finish(id: String, outcome: OnboardingPresetOutcome) {
        when (outcome) {
            OnboardingPresetOutcome.Applied, OnboardingPresetOutcome.NoRadioToConfigure -> {
                retryCount = 0
                appliedPresetId = id
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

    private fun beginCompletionPersistence() {
        if (completionPersistenceJob?.isActive == true) return
        mutable.update { it.copy(isApplying = true, error = null, retryAlert = null) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                onCompleted()
                appliedPresetId = null
                mutable.update {
                    it.copy(isApplying = false, completionNeedsRetry = false, commitTick = it.commitTick + 1)
                }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(isApplying = false) }
                throw cancelled
            } catch (_: Exception) {
                mutable.update {
                    it.copy(isApplying = false, error = UiText.Resource(S.alertRetryUnableToSave), completionNeedsRetry = true)
                }
            }
        }
        completionPersistenceJob = job
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) mutable.update { it.copy(isApplying = false) }
        }
        job.start()
    }

    fun retry() {
        val current = mutable.value
        if (current.isApplying) return
        val alert = current.retryAlert
        if (alert != null) {
            mutable.update { it.copy(retryAlert = null) }
            apply(alert.presetId)
            return
        }
        if (appliedPresetId != null && current.completionNeedsRetry) {
            if (current.selectedId == appliedPresetId) beginCompletionPersistence()
            else apply(current.selectedId)
        }
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
