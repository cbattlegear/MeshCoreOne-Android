// PortedFrom: MC1/Views/Settings/Sections/StaleNodeCleanupSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import java.time.Instant
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Which footer line the stale-node section shows. */
enum class StaleCleanupFooter { DISABLED, SELECT_THRESHOLD, ENABLED, DISCONNECTED }

data class StaleNodeCleanupState(
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val isEnabled: Boolean = false,
    val thresholdDays: Int = 0,
    val lastCleanup: Instant? = null,
) {
    /** The last-run line appears only while a threshold is set and a cleanup has run. */
    val showsLastRun: Boolean get() = isEnabled && lastCleanup != null && thresholdDays > 0
    val footer: StaleCleanupFooter
        get() = when {
            !isEnabled -> StaleCleanupFooter.DISABLED
            thresholdDays == 0 -> StaleCleanupFooter.SELECT_THRESHOLD
            connectionState == DeviceConnectionState.READY -> StaleCleanupFooter.ENABLED
            else -> StaleCleanupFooter.DISCONNECTED
        }
    val controlsEnabled: Boolean get() = connectionState == DeviceConnectionState.READY
}

/** Auto-removal of stale non-favorite nodes. Picking a threshold while connected runs a forced cleanup at once. */
class StaleNodeCleanupStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val port: StaleNodeCleanupPort,
) {
    private val mutable = MutableStateFlow(StaleNodeCleanupState(thresholdDays = port.thresholdDays.value, lastCleanup = port.lastCleanup.value))
    private var observing: Job? = null
    val state: StateFlow<StaleNodeCleanupState> = mutable.asStateFlow()

    fun start() {
        if (observing != null) return
        // `onAppear { isEnabled = threshold > 0 }`
        mutable.update {
            it.copy(thresholdDays = port.thresholdDays.value, lastCleanup = port.lastCleanup.value, isEnabled = port.thresholdDays.value > 0)
        }
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            launch { port.thresholdDays.collect { d -> mutable.update { it.copy(thresholdDays = d) } } }
            launch { port.lastCleanup.collect { l -> mutable.update { it.copy(lastCleanup = l) } } }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun onEnabledToggled(enabled: Boolean) {
        mutable.update { it.copy(isEnabled = enabled) }
        if (!enabled) port.setThresholdDays(0)
    }

    fun onThresholdSelected(days: Int) {
        require(days in THRESHOLD_CHOICES) { "Unsupported stale-node threshold: $days" }
        port.setThresholdDays(days)
        mutable.update { it.copy(thresholdDays = days) }
        if (days > 0 && mutable.value.connectionState == DeviceConnectionState.READY) port.performCleanup(force = true)
    }

    companion object {
        /** "Select" (0) followed by 7, 14, 30 and 90 days. */
        val THRESHOLD_CHOICES: List<Int> = listOf(0, 7, 14, 30, 90)
    }
}
