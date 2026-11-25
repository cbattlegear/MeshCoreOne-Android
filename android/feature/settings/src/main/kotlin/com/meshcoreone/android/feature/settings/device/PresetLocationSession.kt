// PortedFrom: MC1/Views/Settings/PresetLocationSession.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.ui.UiText
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PresetLocationState(
    val isResolving: Boolean = false,
    val errorMessage: UiText? = null,
    val showOpenSettingsAlert: Boolean = false,
)

/**
 * Resolves the phone location to a country/state filter for the preset list. Overlapping requests queue: a
 * user-initiated request waits for the one in flight; an automatic one is dropped.
 */
class PresetLocationSession(
    private val env: SettingsEnvironment,
    private val location: LocationPermissionPort,
    private val regions: RegionSelectionPort,
    private val lookup: RegionLookupPort,
) {
    private val mutable = MutableStateFlow(PresetLocationState())
    private var task: Job? = null
    private var generation = 0
    val state: StateFlow<PresetLocationState> = mutable.asStateFlow()

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }
    fun dismissOpenSettingsAlert() = mutable.update { it.copy(showOpenSettingsAlert = false) }

    fun resolveOnAppear() {
        val authorized = location.authorization.value == LocationAuthorization.AUTHORIZED
        if (!PresetLocationPolicy.shouldResolveOnAppear(authorized, regions.selection.value?.source)) return
        enqueue(PresetLocationPolicy.ResolveKind.APPEAR)
    }

    fun useMyLocation() = enqueue(PresetLocationPolicy.ResolveKind.USER_INITIATED)

    fun cancel() {
        task?.cancel()
        task = null
    }

    private fun enqueue(kind: PresetLocationPolicy.ResolveKind) {
        val existing = task
        if (existing != null) {
            if (kind != PresetLocationPolicy.ResolveKind.USER_INITIATED) return
            start(kind, waitingOn = existing)
            return
        }
        start(kind, waitingOn = null)
    }

    private fun start(kind: PresetLocationPolicy.ResolveKind, waitingOn: Job?) {
        generation += 1
        val current = generation
        task = env.scope.launch {
            waitingOn?.join()
            perform(kind, current)
            if (generation == current) task = null
        }
    }

    private suspend fun perform(kind: PresetLocationPolicy.ResolveKind, generation: Int) {
        mutable.update { it.copy(isResolving = true) }
        try {
            when (kind) {
                PresetLocationPolicy.ResolveKind.APPEAR -> runResolve(kind)
                PresetLocationPolicy.ResolveKind.USER_INITIATED -> performUserInitiated()
            }
        } finally {
            // A replaced enqueue must not clear isResolving or the button re-enables between GPS slots.
            if (this.generation == generation) mutable.update { it.copy(isResolving = false) }
        }
    }

    private suspend fun performUserInitiated() {
        when (PresetLocationPolicy.useMyLocationAction(location.authorization.value)) {
            PresetLocationPolicy.UseMyLocationAction.OPEN_SETTINGS -> mutable.update { it.copy(showOpenSettingsAlert = true) }
            PresetLocationPolicy.UseMyLocationAction.RESOLVE -> runResolve(PresetLocationPolicy.ResolveKind.USER_INITIATED)
            PresetLocationPolicy.UseMyLocationAction.WAIT_FOR_AUTHORIZATION -> {
                try {
                    location.requestCurrentLocation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: Exception) {
                    // Status is the source of truth: timeout, deny, or a location miss after grant.
                }
                when (PresetLocationPolicy.actionAfterAuthorizationWait(location.authorization.value)) {
                    PresetLocationPolicy.AfterAuthorizationWait.RESOLVE -> runResolve(PresetLocationPolicy.ResolveKind.USER_INITIATED)
                    PresetLocationPolicy.AfterAuthorizationWait.OPEN_SETTINGS -> mutable.update { it.copy(showOpenSettingsAlert = true) }
                    PresetLocationPolicy.AfterAuthorizationWait.NONE -> Unit
                }
            }
        }
    }

    private suspend fun runResolve(kind: PresetLocationPolicy.ResolveKind) {
        if (location.isRequestingLocation.value) waitForLocationSlot()
        if (location.isRequestingLocation.value) return
        val result = lookup.resolve()
        val current = regions.selection.value
        val next = PresetLocationPolicy.committedSelection(current, result, kind)
        if (next != current) regions.set(next)
        if (result == null && PresetLocationPolicy.shouldPresentLookupMiss(kind, requestInProgress = false)) {
            mutable.update { it.copy(errorMessage = UiText.Resource(AppSettingsStrings.radioPresetLocationUseMyLocationFailure)) }
        }
    }

    private suspend fun waitForLocationSlot() {
        var waited = 0L
        while (location.isRequestingLocation.value && waited < LOCATION_SLOT_WAIT_MS) {
            env.clock.sleep(LOCATION_SLOT_POLL)
            waited += LOCATION_SLOT_POLL.inWholeMilliseconds
        }
    }

    private companion object {
        val LOCATION_SLOT_POLL = 50.milliseconds
        val LOCATION_SLOT_WAIT_MS = 10.seconds.inWholeMilliseconds
    }
}
