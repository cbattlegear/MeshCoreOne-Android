// PortedFrom: MC1/Views/Settings/Sections/TelemetrySettingsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/Sections/DirectMessagesSettingsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.TelemetryModes
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Firmware telemetry permission levels, shared by the base, location and environment modes. */
object TelemetryMode {
    val OFF: UByte = 0u
    val TRUSTED_ONLY: UByte = 1u
    val EVERYONE: UByte = 2u
}

data class TelemetrySettingsState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val isSaving: Boolean = false,
    val errorMessage: UiText? = null,
) {
    private val base: UByte get() = device?.telemetryModeBase ?: TelemetryMode.OFF
    val telemetryEnabled: Boolean get() = base > TelemetryMode.OFF
    val locationEnabled: Boolean get() = (device?.telemetryModeLoc ?: TelemetryMode.OFF) > TelemetryMode.OFF
    val environmentEnabled: Boolean get() = (device?.telemetryModeEnv ?: TelemetryMode.OFF) > TelemetryMode.OFF
    val filterByTrusted: Boolean get() = device?.telemetryModeBase == TelemetryMode.TRUSTED_ONLY

    /** The three detail switches and "manage trusted" only appear once telemetry is on. */
    val showsDetails: Boolean get() = telemetryEnabled
    val showsManageTrusted: Boolean get() = filterByTrusted
    val controlsEnabled: Boolean get() = connectionState == DeviceConnectionState.READY && !isSaving
}

/** Telemetry sharing: every switch writes the three mode bytes together through one verified call. */
class TelemetrySettingsStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(TelemetrySettingsState())
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    val state: StateFlow<TelemetrySettingsState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            connection.connectedDevice.collect { d -> mutable.update { it.copy(device = d) } }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }

    /** Trusted-only filtering stays on when enabling a switch, otherwise the level is everyone. */
    private val enabledMode: UByte get() = if (mutable.value.filterByTrusted) TelemetryMode.TRUSTED_ONLY else TelemetryMode.EVERYONE

    fun onTelemetryToggled(on: Boolean) = save(base = if (on) enabledMode else TelemetryMode.OFF)
    fun onLocationToggled(on: Boolean) = save(location = if (on) enabledMode else TelemetryMode.OFF)
    fun onEnvironmentToggled(on: Boolean) = save(environment = if (on) enabledMode else TelemetryMode.OFF)

    fun onFilterByTrustedToggled(on: Boolean) {
        val device = mutable.value.device
        val mode = if (on) TelemetryMode.TRUSTED_ONLY else TelemetryMode.EVERYONE
        fun keep(current: UByte?): UByte = if ((current ?: TelemetryMode.OFF) > TelemetryMode.OFF) mode else TelemetryMode.OFF
        save(keep(device?.telemetryModeBase), keep(device?.telemetryModeLoc), keep(device?.telemetryModeEnv))
    }

    private fun save(base: UByte? = null, location: UByte? = null, environment: UByte? = null) {
        val device = mutable.value.device ?: return
        val service = settingsService() ?: return
        mutable.update { it.copy(isSaving = true) }
        env.scope.launch {
            try {
                val modes = TelemetryModes.of(
                    base ?: device.telemetryModeBase, location ?: device.telemetryModeLoc, environment ?: device.telemetryModeEnv,
                )
                service.setOtherParamsVerified(device, telemetryModes = modes)
                retryAlert.reset()
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(isSaving = false) }
                throw cancelled
            } catch (error: Exception) {
                val message = failures.route(error) { save(base, location, environment) }
                mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
            }
            mutable.update { it.copy(isSaving = false) }
        }
    }
}

data class DirectMessagesState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val isSaving: Boolean = false,
    val errorMessage: UiText? = null,
) {
    /** Picker value 1 or 2: the firmware stores acknowledgments minus one (a missing device reads as 1). */
    val acknowledgments: Int get() = (device?.multiAcks?.toInt() ?: 0) + 1
    val enabled: Boolean get() = connectionState == DeviceConnectionState.READY && !isSaving
}

/** Direct-message acknowledgment count (1 or 2). */
class DirectMessagesStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(DirectMessagesState())
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    val state: StateFlow<DirectMessagesState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            connection.connectedDevice.collect { d -> mutable.update { it.copy(device = d) } }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }

    fun onAcknowledgmentsSelected(count: Int) {
        require(count in 1..2) { "Acknowledgment count must be 1 or 2" }
        save((count - 1).toUByte())
    }

    private fun save(value: UByte) {
        val device = mutable.value.device ?: return
        val service = settingsService() ?: return
        mutable.update { it.copy(isSaving = true) }
        env.scope.launch {
            try {
                service.setOtherParamsVerified(device, multiAcks = value)
                retryAlert.reset()
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(isSaving = false) }
                throw cancelled
            } catch (error: Exception) {
                val message = failures.route(error) { save(value) }
                mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
            }
            mutable.update { it.copy(isSaving = false) }
        }
    }
}
