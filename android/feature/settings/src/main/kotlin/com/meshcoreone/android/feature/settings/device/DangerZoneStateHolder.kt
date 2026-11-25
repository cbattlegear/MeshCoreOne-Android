// PortedFrom: MC1/Views/Settings/Sections/DangerZoneViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/Sections/DangerZoneSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

data class DangerZoneState(
    val showingForgetConfirmation: Boolean = false,
    val showingResetAlert: Boolean = false,
    val isResetting: Boolean = false,
    val errorMessage: UiText? = null,
    val showingRemoveUnfavoritedAlert: Boolean = false,
    val isRemovingUnfavorited: Boolean = false,
    val showRemoveSuccess: Boolean = false,
    val unfavoritedCount: Int = 0,
    val showRemoveResult: Boolean = false,
    val removeResult: RemoveOutcome? = null,
)

/** What the removal-result dialog says; the UI renders it with the plural-aware `AppSettingsStrings` accessors. */
sealed interface RemoveOutcome {
    data object NoneFound : RemoveOutcome
    data class Partial(val removed: Long, val total: Long) : RemoveOutcome
}

/**
 * Forget, factory-reset and remove-unfavorited flows. Providers are read at the call site, so a null read means
 * disconnected (Swift `DangerZoneViewModel.configure`). Destructive actions are only reachable through the
 * confirmation flags, which the UI presents as dialogs.
 */
class DangerZoneStateHolder(
    private val env: SettingsEnvironment,
    private val settingsService: () -> SettingsRadioPort?,
    private val connectedDevice: () -> DeviceDTO?,
    private val maintenance: DeviceMaintenancePort?,
) {
    private val mutable = MutableStateFlow(DangerZoneState())
    private var removeJob: Job? = null
    val state: StateFlow<DangerZoneState> = mutable.asStateFlow()

    fun requestForget() = mutable.update { it.copy(showingForgetConfirmation = true) }
    fun requestReset() = mutable.update { it.copy(showingResetAlert = true) }
    fun dismissForget() = mutable.update { it.copy(showingForgetConfirmation = false) }
    fun dismissReset() = mutable.update { it.copy(showingResetAlert = false) }
    fun dismissRemoveAlert() = mutable.update { it.copy(showingRemoveUnfavoritedAlert = false) }
    fun dismissRemoveResult() = mutable.update { it.copy(showRemoveResult = false) }
    fun dismissError() = mutable.update { it.copy(errorMessage = null) }

    fun cancelPendingRemoval() {
        removeJob?.cancel()
    }

    /** Returns true when the device was forgotten and the hosting page should dismiss. */
    suspend fun forgetDevice(deleteData: Boolean): Boolean {
        val port = maintenance ?: return false
        return try {
            port.forgetDevice(deleteData)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (declined: PairingCancelledException) {
            false
        } catch (error: Exception) {
            mutable.update { it.copy(errorMessage = env.describe(error)) }
            false
        }
    }

    /**
     * Returns true when the reset flow finished and the hosting page should dismiss. A null service, device or
     * maintenance port mirrors a disconnected state.
     */
    suspend fun factoryReset(): Boolean {
        val service = settingsService()
        val deviceId = connectedDevice()?.id
        val port = maintenance
        if (service == null || deviceId == null || port == null) {
            mutable.update { it.copy(errorMessage = UiText.Resource(AppSettingsStrings.dangerZoneErrorServicesUnavailable)) }
            return false
        }
        mutable.update { it.copy(isResetting = true) }
        try {
            try {
                // The radio normally reboots before it answers, so a timeout here is expected, not a failure.
                service.factoryReset()
                env.clock.sleep(RESET_REBOOT_GRACE)
            } catch (cancelled: CancellationException) {
                // Swift swallows the sleep's CancellationError and still cleans up; do the cleanup, then propagate.
                withContext(NonCancellable) { port.forgetDevice(deviceId) }
                throw cancelled
            } catch (expected: Exception) {
                // Expected: the device reboots before sending its OK response.
            }
            port.forgetDevice(deviceId)
            return true
        } finally {
            mutable.update { it.copy(isResetting = false) }
        }
    }

    suspend fun fetchUnfavoritedCount() {
        val port = maintenance ?: return
        try {
            val count = port.unfavoritedNodeCount()
            mutable.update {
                if (count == 0) {
                    it.copy(
                        unfavoritedCount = 0,
                        removeResult = RemoveOutcome.NoneFound,
                        showRemoveResult = true,
                    )
                } else it.copy(unfavoritedCount = count, showingRemoveUnfavoritedAlert = true)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutable.update { it.copy(errorMessage = env.describe(error)) }
        }
    }

    fun removeUnfavoritedNodes() {
        val port = maintenance ?: return
        mutable.update { it.copy(isRemovingUnfavorited = true, showingRemoveUnfavoritedAlert = false) }
        removeJob = env.scope.launch(start = CoroutineStart.ATOMIC) { runRemoval(port) }
    }

    private suspend fun runRemoval(port: DeviceMaintenancePort) {
        try {
            val result = port.removeUnfavoritedNodes()
            mutable.update { it.copy(isRemovingUnfavorited = false) }
            if (result.removed == result.total) {
                mutable.update { it.copy(showRemoveSuccess = true) }
                env.clock.sleep(REMOVE_SUCCESS_DISPLAY)
                mutable.update { it.copy(showRemoveSuccess = false) }
            } else {
                mutable.update {
                    it.copy(
                        removeResult = RemoveOutcome.Partial(result.removed, result.total),
                        showRemoveResult = true,
                    )
                }
            }
        } catch (cancelled: CancellationException) {
            // Swift leaves the transient label as is; clear it here so a longer-lived holder never shows it stale.
            mutable.update { it.copy(showRemoveSuccess = false) }
            throw cancelled
        } catch (error: Exception) {
            mutable.update { it.copy(errorMessage = env.describe(error)) }
        } finally {
            mutable.update { it.copy(isRemovingUnfavorited = false) }
        }
    }

    private companion object {
        /** Grace period for the radio to reboot after a factory reset before local cleanup. */
        val RESET_REBOOT_GRACE = 1.seconds

        /** How long the transient "Removed" confirmation stays on the button label. */
        val REMOVE_SUCCESS_DISPLAY = 1500.milliseconds
    }
}
