// PortedFrom: MC1/Views/Settings/AdvancedSettingsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * The advanced page's cross-section duties: refresh the radio's cached settings once startup reads are allowed, and
 * leave the page when the radio goes away. Each section holder owns its own state.
 */
class AdvancedSettingsStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
) {
    private var observing: Job? = null

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { dismissWhenDeviceDisappears() }
            combine(connection.connectedDevice, connection.connectionState, connection.startupReadsAllowed) { d, c, a -> Triple(d?.id, c, a) }
                .distinctUntilChanged()
                .collectLatest { refreshDeviceSettings() }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    /** `onChange(of: connectedDevice)`: a nil device pops the page (the first emission is the page's initial value). */
    private suspend fun dismissWhenDeviceDisappears() {
        var seenDevice = connection.connectedDevice.value != null
        connection.connectedDevice.collect { device ->
            if (device == null && seenDevice) env.dismiss()
            if (device != null) seenDevice = true
        }
    }

    /** Fetch fresh settings so the cache is current. Waits out contact/channel sync contention, and ignores read failures. */
    suspend fun refreshDeviceSettings() {
        if (!connection.startupReadsAllowed.value) return
        val service = settingsService() ?: return
        quietly { service.getSelfInfo() }
        val device = connection.connectedDevice.value
        // autoAddConfig only exists on v1.12+ firmware, the default flood scope on v11+.
        if (device?.supportsAutoAddConfig == true) quietly { service.refreshAutoAddConfig() }
        if (connection.connectedDevice.value?.supportsDefaultFloodScope == true) quietly { service.getDefaultFloodScope() }
    }

    private suspend fun quietly(block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            // `try?`
        }
    }
}
