// PortedFrom: MC1/Views/Settings/Sections/LocationSettingsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/LocationSettingsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.AdvertLocationPolicy
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.UiText
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LocationSettingsState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val shareLocation: Boolean = false,
    val autoUpdateLocation: Boolean = false,
    val gpsSource: GpsSource = GpsSource.PHONE,
    val deviceHasGps: Boolean = false,
    val deviceGpsEnabled: Boolean = false,
    val isSaving: Boolean = false,
    val didLoad: Boolean = false,
    val showLocationDeniedAlert: Boolean = false,
    val errorMessage: UiText? = null,
) {
    /** "Location set" row detail: a fix other than 0,0 is stored on the radio. */
    val isLocationSet: Boolean get() = device?.let { it.latitude != 0.0 || it.longitude != 0.0 } ?: false
    val controlsEnabled: Boolean get() = connectionState == DeviceConnectionState.READY && !isSaving
    val setLocationEnabled: Boolean get() = controlsEnabled && !autoUpdateLocation
    val shouldPollDeviceGps: Boolean get() = autoUpdateLocation && gpsSource == GpsSource.DEVICE && deviceGpsEnabled
}

/**
 * Where the node location comes from: manual, the phone, or the radio's own GPS, plus whether it is shared in
 * adverts. Writes are verified; a failed write reverts the control that triggered it.
 */
class LocationSettingsStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
    private val preferences: DevicePreferencePort,
    private val location: LocationPermissionPort,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(LocationSettingsState())
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    private var polling: Job? = null
    val state: StateFlow<LocationSettingsState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    /** Swift `.task(id: startupTaskID)`: reload preferences and the radio's GPS state whenever the device, link or sync gate changes. */
    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            launch { connection.connectedDevice.collect { d -> mutable.update { it.copy(device = d) }; updatePolling() } }
            combine(connection.connectedDevice, connection.connectionState, connection.startupReadsAllowed) { d, c, a -> Triple(d?.id, c, a) }
                .distinctUntilChanged()
                .collectLatest { refresh() }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
        polling?.cancel()
        polling = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }
    fun dismissLocationDeniedAlert() = mutable.update { it.copy(showLocationDeniedAlert = false) }

    /** Reloads after the location picker closes (the Swift task id includes `showingLocationPicker`). */
    suspend fun refresh() {
        loadPreferences()
        if (!connection.startupReadsAllowed.value) return
        loadDeviceGpsState()
    }

    private fun loadPreferences() {
        val device = connection.connectedDevice.value
        mutable.update {
            if (device == null) it.copy(didLoad = true) else it.copy(
                shareLocation = device.sharesLocationPublicly,
                autoUpdateLocation = preferences.isAutoUpdateLocationEnabled(device.id),
                gpsSource = preferences.gpsSource(device.id),
                didLoad = true,
            )
        }
        updatePolling()
    }

    private suspend fun loadDeviceGpsState() {
        val service = settingsService() ?: return
        try {
            val gps = service.getDeviceGPSState()
            mutable.update { it.copy(deviceHasGps = gps.isSupported, deviceGpsEnabled = gps.isEnabled) }
            val deviceId = connection.connectedDevice.value?.id
            if (deviceId != null && gps.isEnabled && !preferences.hasSetGpsSource(deviceId)) {
                mutable.update { it.copy(gpsSource = GpsSource.DEVICE) }
                preferences.setGpsSource(GpsSource.DEVICE, deviceId)
            }
            if (gps.isEnabled) refreshDeviceInfoQuietly(service)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(deviceHasGps = false, deviceGpsEnabled = false) }
        }
        updatePolling()
    }

    private suspend fun refreshDeviceInfoQuietly(service: SettingsRadioPort) {
        try {
            service.refreshDeviceInfo()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            // `try?`
        }
    }

    /** While the radio's GPS drives the location and has no fix yet, ask for fresh device info every 3 s. */
    private fun updatePolling() {
        val shouldPoll = mutable.value.shouldPollDeviceGps
        if (shouldPoll && polling == null) {
            polling = env.scope.launch {
                val service = settingsService() ?: return@launch
                while (true) {
                    env.clock.sleep(GPS_POLL_INTERVAL)
                    val device = connection.connectedDevice.value ?: break
                    if (device.latitude != 0.0 || device.longitude != 0.0) break
                    refreshDeviceInfoQuietly(service)
                }
            }.also { job -> job.invokeOnCompletion { polling = null } }
        } else if (!shouldPoll) {
            polling?.cancel()
            polling = null
        }
    }

    // MARK: User actions

    fun onShareToggled(share: Boolean) {
        if (!mutable.value.didLoad) return
        mutable.update { it.copy(shareLocation = share) }
        updateShareLocation(share)
    }

    fun onAutoUpdateToggled(enabled: Boolean) {
        mutable.update { it.copy(autoUpdateLocation = enabled) }
        updatePolling()
        val deviceId = connection.connectedDevice.value?.id ?: return
        if (enabled && mutable.value.gpsSource == GpsSource.PHONE && location.isDenied()) {
            mutable.update { it.copy(autoUpdateLocation = false, showLocationDeniedAlert = true) }
            return
        }
        preferences.setAutoUpdateLocationEnabled(enabled, deviceId)
        if (enabled && mutable.value.gpsSource == GpsSource.PHONE) location.requestPermissionIfNeeded()
        if (mutable.value.gpsSource == GpsSource.DEVICE) {
            if (enabled) {
                saveDeviceGps(true, onFailure = {
                    mutable.update { it.copy(autoUpdateLocation = false) }
                    preferences.setAutoUpdateLocationEnabled(false, deviceId)
                })
            } else if (mutable.value.deviceGpsEnabled) {
                saveDeviceGps(false)
            }
        }
        if (mutable.value.shareLocation) updateShareLocation(true)
    }

    fun onGpsSourceSelected(source: GpsSource) {
        val previous = mutable.value.gpsSource
        mutable.update { it.copy(gpsSource = source) }
        val deviceId = connection.connectedDevice.value?.id ?: return
        if (source == GpsSource.PHONE && location.isDenied()) {
            mutable.update { it.copy(gpsSource = previous, showLocationDeniedAlert = true) }
            return
        }
        preferences.setGpsSource(source, deviceId)
        if (source == GpsSource.PHONE) {
            location.requestPermissionIfNeeded()
            if (mutable.value.deviceGpsEnabled) saveDeviceGps(false)
        } else if (mutable.value.autoUpdateLocation) {
            saveDeviceGps(true, onFailure = {
                mutable.update { it.copy(gpsSource = previous) }
                preferences.setGpsSource(previous, deviceId)
            })
        }
        if (mutable.value.shareLocation) updateShareLocation(true)
    }

    fun onDeviceGpsToggled(enabled: Boolean) {
        val deviceId = connection.connectedDevice.value?.id ?: return
        val current = mutable.value
        val disableAutoUpdate = !enabled && current.autoUpdateLocation && current.gpsSource == GpsSource.DEVICE
        saveDeviceGps(enabled, onSuccess = {
            if (disableAutoUpdate) {
                mutable.update { it.copy(autoUpdateLocation = false) }
                preferences.setAutoUpdateLocationEnabled(false, deviceId)
                applyDeviceGpsDisabledSharePolicyIfNeeded()
            }
        })
    }

    // MARK: Writes

    private fun selectedPolicy(share: Boolean): AdvertLocationPolicy {
        if (!share) return AdvertLocationPolicy.NONE
        val s = mutable.value
        return if (s.autoUpdateLocation && s.deviceHasGps && s.gpsSource == GpsSource.DEVICE) AdvertLocationPolicy.SHARE else AdvertLocationPolicy.PREFS
    }

    private fun updateShareLocation(share: Boolean) {
        val device = connection.connectedDevice.value ?: return
        val service = settingsService() ?: return
        val policy = selectedPolicy(share)
        if (device.advertLocationPolicyMode == policy) return
        mutable.update { it.copy(isSaving = true) }
        env.scope.launch {
            try {
                service.setOtherParamsVerified(device, advertLocationPolicy = policy)
                retryAlert.reset()
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(isSaving = false) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(shareLocation = !share) }
                val message = failures.route(error) { updateShareLocation(share) }
                mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
            }
            mutable.update { it.copy(isSaving = false) }
        }
    }

    private fun saveDeviceGps(enabled: Boolean, onSuccess: (suspend () -> Unit)? = null, onFailure: (() -> Unit)? = null) {
        val service = settingsService() ?: return
        val previous = mutable.value.deviceGpsEnabled
        mutable.update { it.copy(isSaving = true) }
        env.scope.launch {
            try {
                val gps = service.setDeviceGPSEnabledVerified(enabled)
                mutable.update { it.copy(deviceHasGps = gps.isSupported, deviceGpsEnabled = gps.isEnabled) }
                onSuccess?.invoke()
                retryAlert.reset()
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(isSaving = false) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(deviceGpsEnabled = previous) }
                onFailure?.invoke()
                val message = failures.route(error) { saveDeviceGps(enabled, onSuccess, onFailure) }
                mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
            }
            mutable.update { it.copy(isSaving = false) }
            updatePolling()
        }
    }

    /** Switching the radio's GPS off while its adverts shared the live fix downgrades them to the stored location. */
    private suspend fun applyDeviceGpsDisabledSharePolicyIfNeeded() {
        val device = connection.connectedDevice.value
        val service = settingsService()
        if (!mutable.value.shareLocation || device == null || device.advertLocationPolicyMode != AdvertLocationPolicy.SHARE || service == null) return
        service.setOtherParamsVerified(device, advertLocationPolicy = AdvertLocationPolicy.PREFS)
    }

    private fun LocationPermissionPort.isDenied(): Boolean =
        authorization.value == LocationAuthorization.DENIED || authorization.value == LocationAuthorization.RESTRICTED

    private companion object {
        val GPS_POLL_INTERVAL = 3.seconds
    }
}
