// PortedFrom: MC1/Views/Settings/Sections/RadioPresetSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/RadioSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RadioPresetState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val region: RegionSelection? = null,
    val presets: List<RadioPresetOption> = emptyList(),
    val repeatPresets: List<RadioPresetOption> = emptyList(),
    val currentPresetId: String? = null,
    val selectedPresetId: String? = null,
    val isRepeatEnabled: Boolean = false,
    val isApplying: Boolean = false,
    val isApplyingRepeat: Boolean = false,
    val showRepeatConfirmation: Boolean = false,
    val errorMessage: UiText? = null,
) {
    /** "Custom" is offered only in repeat mode or while the radio matches no preset. */
    val showsCustomRow: Boolean get() = isRepeatEnabled || currentPresetId == null
    val supportsRepeatToggle: Boolean get() = device?.supportsClientRepeat == true
    fun pickerEnabled(radioWriteInFlight: Boolean): Boolean =
        connectionState == DeviceConnectionState.READY && !(isApplying || isApplyingRepeat || radioWriteInFlight)
    fun repeatToggleEnabled(radioWriteInFlight: Boolean): Boolean = !(isApplying || isApplyingRepeat || radioWriteInFlight)
}

/**
 * Preset picker plus Repeat Mode. Selections the user makes apply to the radio; selections this holder makes while
 * syncing to the device never do (Swift needed `hasInitialized`/`isProgrammaticRepeatToggle` for that, here the
 * user actions are separate entry points).
 */
class RadioPresetStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val regions: RegionSelectionPort,
    private val catalog: RadioCatalogPort,
    private val settingsService: () -> SettingsRadioPort?,
    private val deviceStore: DeviceSettingsStorePort,
    private val writeGate: RadioWriteGate,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(RadioPresetState(repeatPresets = catalog.repeatPresets))
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    private var started = false
    private var lastCurrentPresetId: String? = null
    private var lastCurrentRepeatPresetId: String? = null
    private var lastClientRepeat: Boolean? = null
    val state: StateFlow<RadioPresetState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            launch { runStartupReads() }
            combine(connection.connectedDevice, regions.selection) { device, region -> device to region }
                .collect { (device, region) -> onInputs(device, region) }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }
    fun dismissRepeatConfirmation() = mutable.update { it.copy(showRepeatConfirmation = false) }

    /** `.task(id: startupTaskID)`: one fresh self-info read once startup reads are allowed. */
    private suspend fun runStartupReads() {
        combine(connection.connectedDevice, connection.connectionState, connection.startupReadsAllowed) { d, c, a -> Triple(d?.id, c, a) }
            .distinctUntilChanged()
            .collect { (id, _, allowed) ->
                if (id == null || !allowed) return@collect
                val service = settingsService() ?: return@collect
                try {
                    service.getSelfInfo()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (ignored: Exception) {
                    // `try?`: a failed refresh leaves the cached device values.
                }
            }
    }

    private fun onInputs(device: DeviceDTO?, region: RegionSelection?) {
        val current = device?.let { catalog.resolvedPreset(it, region) }
        val currentRepeat = device?.let { catalog.matchingRepeatPreset(it.frequency) }
        val presets = catalog.visiblePresets(region, current?.id)
        val first = !started
        started = true
        mutable.update { it.copy(device = device, region = region, presets = presets, currentPresetId = current?.id) }
        if (first) {
            setRepeatToggle(device?.clientRepeat ?: false)
            mutable.update { it.copy(selectedPresetId = currentMatchingPresetId()) }
        } else {
            syncFromDevice(device, current?.id, currentRepeat?.id)
        }
        lastCurrentPresetId = current?.id
        lastCurrentRepeatPresetId = currentRepeat?.id
        lastClientRepeat = device?.clientRepeat
    }

    private fun syncFromDevice(device: DeviceDTO?, currentId: String?, currentRepeatId: String?) {
        val repeatEnabled = mutable.value.isRepeatEnabled
        if (!repeatEnabled && currentId != lastCurrentPresetId) {
            val selected = mutable.value.selectedPresetId
            val stillMatches = selected != null && device != null && selected in catalog.matchingPresetIds(device)
            if (!stillMatches) mutable.update { it.copy(selectedPresetId = currentId) }
        }
        if (repeatEnabled && currentRepeatId != lastCurrentRepeatPresetId) mutable.update { it.copy(selectedPresetId = currentRepeatId) }
        val newRepeat = device?.clientRepeat
        if (newRepeat != lastClientRepeat && (newRepeat ?: false) != repeatEnabled) {
            setRepeatToggle(newRepeat ?: false)
            mutable.update { it.copy(selectedPresetId = currentMatchingPresetId()) }
        }
    }

    private fun currentMatchingPresetId(): String? {
        val device = mutable.value.device ?: return null
        return if (mutable.value.isRepeatEnabled) catalog.matchingRepeatPreset(device.frequency)?.id else mutable.value.currentPresetId
    }

    private fun setRepeatToggle(value: Boolean) = mutable.update { it.copy(isRepeatEnabled = value) }

    /** The user picked a preset; "Custom" (null) never writes. */
    fun onPresetSelected(id: String?) {
        mutable.update { it.copy(selectedPresetId = id) }
        if (id != null) applyPreset(id)
    }

    /** The user tapped the Repeat Mode switch. Turning it on waits for the confirmation dialog. */
    fun onRepeatToggled(enabled: Boolean) {
        if (enabled) mutable.update { it.copy(showRepeatConfirmation = true) } else disableRepeatMode()
    }

    fun confirmEnableRepeatMode() {
        mutable.update { it.copy(showRepeatConfirmation = false) }
        enableRepeatMode()
    }

    private fun applyPreset(
        id: String,
        repeat: Boolean = mutable.value.isRepeatEnabled,
        expectedDeviceId: java.util.UUID? = connection.connectedDevice.value?.id,
    ) {
        val snapshot = mutable.value
        val list = if (repeat) snapshot.repeatPresets else snapshot.presets
        if (list.none { it.id == id }) {
            writeGate.set(false)
            return
        }
        mutable.update { it.copy(isApplying = true) }
        writeGate.set(true)
        env.scope.launch {
            val applied = runApply(id, repeat, expectedDeviceId)
            if (repeat) {
                if (applied) mutable.update { it.copy(isRepeatEnabled = true, selectedPresetId = id) }
                else syncRepeatModeToConnectedDevice()
            }
        }
    }

    private suspend fun runApply(id: String, repeat: Boolean, expectedDeviceId: java.util.UUID? = null): Boolean {
        var applied = false
        try {
            val service = settingsService() ?: throw SettingsNotConnectedException()
            val device = connection.connectedDevice.value ?: throw SettingsNotConnectedException()
            if (expectedDeviceId != null && device.id != expectedDeviceId) return false
            if (repeat) {
                val frequency = catalog.repeatFrequencyKHz(id) ?: throw SettingsNotConnectedException()
                // Repeat Mode changes only the frequency; bandwidth, SF and CR stay as the radio has them.
                service.setRadioParamsVerified(frequency, device.bandwidth, device.spreadingFactor, device.codingRate, clientRepeat = true)
            } else {
                service.applyRadioPresetVerified(id)
            }
            retryAlert.reset()
            applied = connection.connectedDevice.value?.id == device.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            mutable.update { it.copy(selectedPresetId = currentMatchingPresetId()) }
            val message = failures.route(error) { applyPreset(id, repeat, expectedDeviceId) }
            mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
        } finally {
            releaseApply()
        }
        return applied
    }

    private fun releaseApply() {
        mutable.update { it.copy(isApplying = false) }
        writeGate.set(false)
    }

    private fun enableRepeatMode() {
        val device = mutable.value.device ?: return
        val presetId = catalog.nearestRepeatPreset(device.frequency)?.id ?: return
        writeGate.set(true)
        mutable.update { it.copy(isApplyingRepeat = true, errorMessage = null) }
        env.scope.launch { runEnableRepeatMode(device.id, presetId) }
    }

    private suspend fun runEnableRepeatMode(deviceId: java.util.UUID, presetId: String) {
        try {
            if (!deviceStore.savePreRepeatSettings(deviceId) || connection.connectedDevice.value?.id != deviceId) {
                throw SettingsNotConnectedException()
            }
            setRepeatToggle(true)
            mutable.update { it.copy(selectedPresetId = presetId, isApplyingRepeat = false) }
            if (!runApply(presetId, repeat = true, expectedDeviceId = deviceId)) {
                syncRepeatModeToConnectedDevice()
            }
        } catch (cancelled: CancellationException) {
            releaseRepeat()
            throw cancelled
        } catch (error: Exception) {
            syncRepeatModeToConnectedDevice()
            val message = failures.route(error) { enableRepeatMode() }
            mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
            releaseRepeat()
        }
    }

    private fun syncRepeatModeToConnectedDevice() {
        val device = connection.connectedDevice.value
        mutable.update { current ->
            val enabled = device?.clientRepeat == true
            current.copy(
                isRepeatEnabled = enabled,
                selectedPresetId = when {
                    device == null -> null
                    enabled -> catalog.matchingRepeatPreset(device.frequency)?.id
                    else -> catalog.resolvedPreset(device, current.region)?.id
                },
            )
        }
    }

    private fun disableRepeatMode() {
        val device = mutable.value.device ?: return
        // The switch already shows off while the write runs; a failure puts it back.
        mutable.update { it.copy(isApplyingRepeat = true, isRepeatEnabled = false) }
        writeGate.set(true)
        env.scope.launch { runDisableRepeat(device) }
    }

    private suspend fun runDisableRepeat(device: DeviceDTO) {
        try {
            val service = settingsService() ?: throw SettingsNotConnectedException()
            service.setRadioParamsVerified(
                device.preRepeatFrequency ?: device.frequency, device.preRepeatBandwidth ?: device.bandwidth,
                device.preRepeatSpreadingFactor ?: device.spreadingFactor, device.preRepeatCodingRate ?: device.codingRate,
                clientRepeat = false,
            )
            deviceStore.clearPreRepeatSettings()
            mutable.update { it.copy(selectedPresetId = it.currentPresetId) }
            retryAlert.reset()
        } catch (cancelled: CancellationException) {
            releaseRepeat()
            throw cancelled
        } catch (error: Exception) {
            setRepeatToggle(true)
            val message = failures.route(error) { disableRepeatMode() }
            mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
        }
        releaseRepeat()
    }

    private fun releaseRepeat() {
        mutable.update { it.copy(isApplyingRepeat = false) }
        writeGate.set(false)
    }
}
