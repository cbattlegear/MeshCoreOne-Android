// PortedFrom: MC1/Views/Settings/Sections/DeviceActionsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class DeviceActionsState(
    val showingRebootAlert: Boolean = false,
    val isRebooting: Boolean = false,
    val errorMessage: UiText? = null,
)

/** Reboot with a confirmation dialog. The expected link-drop timeout is success, never an error. */
class DeviceActionsStateHolder(
    private val env: SettingsEnvironment,
    private val settingsService: () -> SettingsRadioPort?,
) {
    private val mutable = MutableStateFlow(DeviceActionsState())
    val state: StateFlow<DeviceActionsState> = mutable.asStateFlow()

    fun requestReboot() = mutable.update { it.copy(showingRebootAlert = true) }
    fun dismissRebootAlert() = mutable.update { it.copy(showingRebootAlert = false) }
    fun dismissError() = mutable.update { it.copy(errorMessage = null) }

    fun confirmReboot() {
        mutable.update { it.copy(showingRebootAlert = false) }
        val service = settingsService() ?: return
        mutable.update { it.copy(isRebooting = true) }
        env.scope.launch {
            try {
                service.reboot()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (expected: SettingsOperationTimeoutException) {
                // Expected: the device reboots before the write acknowledgement arrives.
            } catch (error: Exception) {
                mutable.update { it.copy(errorMessage = env.describe(error)) }
            } finally {
                mutable.update { it.copy(isRebooting = false) }
            }
        }
    }
}
