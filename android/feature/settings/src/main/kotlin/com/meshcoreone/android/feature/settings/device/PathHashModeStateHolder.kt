// PortedFrom: MC1/Views/Settings/Sections/PathHashModeSection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PathHashModeState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val selectedMode: UByte? = null,
    val isApplying: Boolean = false,
    val errorMessage: UiText? = null,
    val sessionToken: SessionToken? = null,
) {
    /** 0 is one-byte, 1 two-byte and 2 three-byte path hashes. */
    val displayedMode: UByte get() = selectedMode ?: device?.pathHashMode ?: 0u
    val enabled: Boolean get() = connectionState == DeviceConnectionState.READY && !isApplying
}

/** Path hash size picker for firmware v10 and newer; the section is hidden on older firmware. */
class PathHashModeStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(PathHashModeState())
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    private var failedMode: UByte? = null
    val state: StateFlow<PathHashModeState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            connection.deviceObservations.collect { observed ->
                val d = observed.device
                mutable.update { it.copy(device = d, sessionToken = observed.sessionToken, selectedMode = d?.pathHashMode ?: it.selectedMode ?: 0u) }
            }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }

    fun onModeSelected(mode: UByte) = onModeSelected(mode, mutable.value.sessionToken)

    fun onModeSelected(mode: UByte, renderedToken: SessionToken?) {
        if (mutable.value.isApplying) return
        val target = try {
            val observed = mutable.value
            SettingsRadioTarget.capture(renderedToken, observed.device, observed.sessionToken, settingsService())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            apply(mode, null, failure)
            return
        }
        if (mode == mutable.value.selectedMode && failedMode == null && !target.service.pathHashModeNeedsRecovery) return
        apply(mode, target)
    }

    private fun apply(mode: UByte, target: SettingsRadioTarget?, acquisitionFailure: Exception? = null) {
        if (mutable.value.isApplying) return
        mutable.update { it.copy(selectedMode = mode, isApplying = true, errorMessage = null) }
        env.scope.launch {
            try {
                acquisitionFailure?.let { throw it }
                val captured = target ?: throw SettingsNotConnectedException()
                captured.requireCurrent(mutable.value.device, mutable.value.sessionToken)
                captured.service.setPathHashModeVerified(mode)
                failedMode = null
                retryAlert.reset()
            } catch (cancelled: CancellationException) {
                failedMode = mode
                mutable.update { it.copy(isApplying = false, selectedMode = it.device?.pathHashMode ?: 0u) }
                throw cancelled
            } catch (error: Exception) {
                failedMode = mode
                val message = failures.route(error) { apply(mode, target) }
                mutable.update { it.copy(selectedMode = it.device?.pathHashMode ?: 0u, errorMessage = message ?: it.errorMessage) }
            }
            mutable.update { it.copy(isApplying = false) }
        }
    }
}
