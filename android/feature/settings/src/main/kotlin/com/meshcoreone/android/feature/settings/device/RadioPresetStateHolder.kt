// PortedFrom: MC1/Views/Settings/Sections/RadioPresetSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/RadioSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.PreRepeatSettingsAttempt
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val sessionToken: SessionToken? = null,
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
    private var repeatConfirmationTarget: SettingsRadioTarget? = null
    val state: StateFlow<RadioPresetState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            launch { runStartupReads() }
            combine(connection.deviceObservations, regions.selection) { observed, region -> observed to region }
                .collect { (observed, region) ->
                    mutable.update { it.copy(sessionToken = observed.sessionToken) }
                    onInputs(observed.device, region)
                }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }
    fun dismissRepeatConfirmation() {
        repeatConfirmationTarget = null
        mutable.update { it.copy(showRepeatConfirmation = false) }
    }

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
    fun onPresetSelected(id: String?) = onPresetSelected(id, mutable.value.sessionToken)

    fun onPresetSelected(id: String?, renderedToken: SessionToken?) {
        if (mutable.value.isApplying || mutable.value.isApplyingRepeat || writeGate.inFlight.value) return
        withRepeatTarget(renderedToken) { target ->
            val snapshot = mutable.value
            mutable.update { it.copy(selectedPresetId = id) }
            val list = if (snapshot.isRepeatEnabled) snapshot.repeatPresets else snapshot.presets
            if (id != null && list.any { it.id == id }) applyPreset(id, snapshot.isRepeatEnabled, target)
        }
    }

    /** The user tapped the Repeat Mode switch. Turning it on waits for the confirmation dialog. */
    fun onRepeatToggled(enabled: Boolean) = onRepeatToggled(enabled, mutable.value.sessionToken)

    fun onRepeatToggled(enabled: Boolean, renderedToken: SessionToken?) {
        if (mutable.value.isApplying || mutable.value.isApplyingRepeat || writeGate.inFlight.value) return
        withRepeatTarget(renderedToken) { target ->
            if (enabled) {
                repeatConfirmationTarget = target
                mutable.update { it.copy(showRepeatConfirmation = true) }
            } else disableRepeatMode(target)
        }
    }

    fun confirmEnableRepeatMode() = confirmEnableRepeatMode(mutable.value.sessionToken)

    fun confirmEnableRepeatMode(renderedToken: SessionToken?) {
        if (mutable.value.isApplying || mutable.value.isApplyingRepeat || writeGate.inFlight.value) return
        val openingTarget = repeatConfirmationTarget
        repeatConfirmationTarget = null
        mutable.update { it.copy(showRepeatConfirmation = false) }
        try {
            if (openingTarget == null || openingTarget.token != renderedToken) throw SettingsNotConnectedException()
            openingTarget.requireCurrent(mutable.value.device, mutable.value.sessionToken)
            enableRepeatMode(openingTarget)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(errorMessage = env.describe(failure)) }
        }
    }

    private fun applyPreset(id: String, repeat: Boolean, target: SettingsRadioTarget) {
        if (mutable.value.isApplying || mutable.value.isApplyingRepeat || writeGate.inFlight.value) return
        mutable.update { it.copy(isApplying = true, errorMessage = null) }
        writeGate.set(true)
        env.scope.launch { runApply(id, repeat, target) }
    }

    private suspend fun runApply(id: String, repeat: Boolean, target: SettingsRadioTarget) {
        try {
            target.requireCurrent(mutable.value.device, mutable.value.sessionToken)
            val service = target.service
            service.reconcileRadioSettingsIfNeeded()
            if (repeat) {
                val device = target.currentDevice(mutable.value.device, mutable.value.sessionToken)
                val frequency = catalog.repeatFrequencyKHz(id) ?: throw SettingsNotConnectedException()
                // Repeat Mode changes only the frequency; bandwidth, SF and CR stay as the radio has them.
                service.setRadioParamsVerified(
                    frequency, device.bandwidth, device.spreadingFactor, device.codingRate,
                    clientRepeat = true, expectedDevice = device,
                )
            } else {
                service.applyRadioPresetVerified(id)
            }
            retryAlert.reset()
        } catch (cancelled: CancellationException) {
            releaseApply()
            throw cancelled
        } catch (error: Exception) {
            mutable.update { it.copy(selectedPresetId = currentMatchingPresetId()) }
            val message = failures.route(error) { applyPreset(id, repeat, target) }
            mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
        }
        releaseApply()
    }

    private fun releaseApply() {
        mutable.update { it.copy(isApplying = false) }
        writeGate.set(false)
    }

    private fun enableRepeatMode(target: SettingsRadioTarget) {
        val device = target.device
        val presetId = catalog.nearestRepeatPreset(device.frequency)?.id ?: return
        startEnableRepeat(target, presetId)
    }

    private fun withRepeatTarget(renderedToken: SessionToken?, action: (SettingsRadioTarget) -> Unit) {
        try {
            val observed = mutable.value
            action(SettingsRadioTarget.capture(renderedToken, observed.device, observed.sessionToken, settingsService()))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(errorMessage = env.describe(failure)) }
        }
    }

    private fun startEnableRepeat(target: SettingsRadioTarget, presetId: String) {
        if (mutable.value.isApplying || mutable.value.isApplyingRepeat || writeGate.inFlight.value) return
        mutable.update { it.copy(isApplyingRepeat = true, errorMessage = null) }
        writeGate.set(true)
        env.scope.launch {
            val attempt = PreRepeatSettingsAttempt(target.token)
            try {
                target.requireCurrent(mutable.value.device, mutable.value.sessionToken)
                target.service.reconcileRadioSettingsIfNeeded()
                deviceStore.savePreRepeatSettings(target.token, attempt)
                currentCoroutineContext().ensureActive()
                val current = target.currentDevice(mutable.value.device, mutable.value.sessionToken)
                setRepeatToggle(true)
                mutable.update { it.copy(selectedPresetId = presetId) }
                val frequency = catalog.repeatFrequencyKHz(presetId) ?: throw SettingsNotConnectedException()
                target.service.setRadioParamsVerified(
                    frequency, current.bandwidth, current.spreadingFactor, current.codingRate,
                    clientRepeat = true, expectedDevice = current, repeatAttempt = attempt,
                )
                retryAlert.reset()
            } catch (cancelled: CancellationException) {
                abortUnissuedRepeat(target, attempt, cancelled)
                setRepeatToggle(connection.connectedDevice.value?.clientRepeat ?: false)
                throw cancelled
            } catch (failure: Exception) {
                abortUnissuedRepeat(target, attempt, failure)
                setRepeatToggle(connection.connectedDevice.value?.clientRepeat ?: false)
                val message = failures.route(failure) { startEnableRepeat(target, presetId) }
                mutable.update { it.copy(selectedPresetId = currentMatchingPresetId(), errorMessage = message ?: it.errorMessage) }
            } finally {
                releaseRepeat()
            }
        }
    }

    private suspend fun abortUnissuedRepeat(
        target: SettingsRadioTarget, attempt: PreRepeatSettingsAttempt, failure: Exception,
    ) {
        try {
            // Only the committed creation receipt and definitely-unsent dispatch qualify for this tail.
            withContext(NonCancellable) { deviceStore.abortPreRepeatSettings(target.token, attempt) }
        } catch (cleanupFailure: Exception) {
            if (cleanupFailure !== failure) failure.addSuppressed(cleanupFailure)
            mutable.update { it.copy(errorMessage = env.describe(cleanupFailure)) }
        }
    }

    private fun disableRepeatMode(target: SettingsRadioTarget) {
        if (mutable.value.isApplying || mutable.value.isApplyingRepeat || writeGate.inFlight.value) return
        // A failure before verified off restores the switch; snapshot cleanup cannot undo verified off.
        mutable.update { it.copy(isApplyingRepeat = true, isRepeatEnabled = false, errorMessage = null) }
        writeGate.set(true)
        env.scope.launch { runDisableRepeat(target) }
    }

    private suspend fun runDisableRepeat(target: SettingsRadioTarget) {
        var repeatOffAcknowledged = false
        try {
            target.service.reconcileRadioSettingsIfNeeded()
            deviceStore.validatePreRepeatSettings(target.token)
            val device = target.currentDevice(mutable.value.device, mutable.value.sessionToken)
            val restoreAuthority = deviceStore.preRepeatSettingsRestoreAuthority(target.token, device)
            target.service.setRadioParamsVerified(
                device.preRepeatFrequency ?: device.frequency, device.preRepeatBandwidth ?: device.bandwidth,
                device.preRepeatSpreadingFactor ?: device.spreadingFactor, device.preRepeatCodingRate ?: device.codingRate,
                clientRepeat = false,
                expectedDevice = device,
                restoreAuthority = restoreAuthority,
            )
            repeatOffAcknowledged = true
            target.requireCurrent(mutable.value.device, mutable.value.sessionToken)
            deviceStore.clearPreRepeatSettings(target.token)
            mutable.update { it.copy(selectedPresetId = it.currentPresetId) }
            retryAlert.reset()
        } catch (cancelled: CancellationException) {
            releaseRepeat()
            throw cancelled
        } catch (error: Exception) {
            mutable.update {
                it.copy(isRepeatEnabled = if (it.sessionToken == target.token) !repeatOffAcknowledged else it.device?.clientRepeat ?: false)
            }
            val message = failures.route(error) { disableRepeatMode(target) }
            mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
        }
        releaseRepeat()
    }

    private fun releaseRepeat() {
        mutable.update { it.copy(isApplyingRepeat = false) }
        writeGate.set(false)
    }
}
