// PortedFrom: MC1/Views/Onboarding/RegionStepView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RegionStepMode { RESOLVING, DETECTED, MANUAL }

data class RegionStepState(
    val isResolving: Boolean = true,
    val resolved: RegionSelection? = null,
    val showManualPicker: Boolean = false,
    val manualSelection: RegionSelection? = null,
    val error: UiText? = null,
) {
    /** Falls silently to the manual picker on denial, timeout or no network. */
    fun mode(locationGranted: Boolean): RegionStepMode = when {
        showManualPicker || !locationGranted -> RegionStepMode.MANUAL
        resolved != null -> RegionStepMode.DETECTED
        isResolving -> RegionStepMode.RESOLVING
        else -> RegionStepMode.MANUAL
    }
}

class RegionStepCoordinator(
    private val scope: CoroutineScope,
    private val port: OnboardingRegionPort,
    private val isLocationGranted: () -> Boolean,
    private val onCommitted: () -> Unit,
) {
    private val mutable = MutableStateFlow(RegionStepState())
    val state: StateFlow<RegionStepState> = mutable.asStateFlow()
    private var job: Job? = null
    private var pendingUserRetry = false

    /** Call on entry and whenever location authorization flips (granted or revoked in Settings). */
    fun resolve() {
        job?.cancel()
        job = scope.launch {
            if (!isLocationGranted() || mutable.value.showManualPicker) {
                mutable.update { it.copy(isResolving = false) }
                return@launch
            }
            mutable.update { it.copy(isResolving = true) }
            val region = try {
                port.resolveRegion()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                null
            }
            val wasUserRetry = pendingUserRetry
            pendingUserRetry = false
            mutable.update {
                it.copy(
                    resolved = region, isResolving = false,
                    showManualPicker = it.showManualPicker || region == null,
                    error = if (region == null && wasUserRetry) UiText.Resource(O.regionUseMyLocationFailure) else it.error,
                )
            }
        }
    }

    /** Clears any prior result first so the spinner shows instead of flashing the old region. */
    fun useMyLocation() {
        pendingUserRetry = true
        mutable.update { it.copy(resolved = null, showManualPicker = false, isResolving = true) }
        resolve()
    }

    fun chooseAnother() = mutable.update { it.copy(showManualPicker = true) }
    fun setManualSelection(selection: RegionSelection?) = mutable.update { it.copy(manualSelection = selection) }
    fun dismissError() = mutable.update { it.copy(error = null) }

    fun commitDetected() {
        val region = mutable.value.resolved ?: return
        port.setSelection(region)
        onCommitted()
    }

    fun commitManual() {
        val selection = mutable.value.manualSelection ?: return
        port.setSelection(selection)
        onCommitted()
    }
}
