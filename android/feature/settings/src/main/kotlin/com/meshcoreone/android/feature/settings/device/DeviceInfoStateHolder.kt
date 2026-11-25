// PortedFrom: MC1/Views/Settings/DeviceInfoView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.ui.UiText
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Storage-bar colour band: under 70 % green, under 90 % orange, otherwise red. */
enum class StorageUsageBand { NORMAL, WARNING, CRITICAL }

data class DeviceInfoState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val battery: DeviceBatterySnapshot? = null,
    val isEditingName: Boolean = false,
    val nodeName: String = "",
    val isSaving: Boolean = false,
    val errorMessage: UiText? = null,
) {
    val nameEditEnabled: Boolean get() = connectionState == DeviceConnectionState.READY && !isSaving

    /** An empty firmware string falls back to "Firmware v<number>"; the UI formats [firmwareVersionNumber]. */
    val firmwareVersionText: String? get() = device?.firmwareVersionString?.takeIf { it.isNotEmpty() }
    val firmwareVersionNumber: Int? get() = device?.firmwareVersion?.toInt()
    val buildDate: String? get() = device?.buildDate?.takeIf { it.isNotEmpty() }
    val manufacturer: String? get() = device?.manufacturerName?.takeIf { it.isNotEmpty() }

    /** Firmware reports storage in binary kilobytes. */
    val storageUsedBytes: Long get() = (battery?.usedStorageKB ?: 0).toLong() * BYTES_PER_KILOBYTE
    val storageTotalBytes: Long get() = (battery?.totalStorageKB ?: 0).toLong() * BYTES_PER_KILOBYTE
    val storageUsageRatio: Double get() = if (storageTotalBytes > 0) storageUsedBytes.toDouble() / storageTotalBytes else 0.0
    val storageBand: StorageUsageBand
        get() = when {
            storageUsageRatio < 0.7 -> StorageUsageBand.NORMAL
            storageUsageRatio < 0.9 -> StorageUsageBand.WARNING
            else -> StorageUsageBand.CRITICAL
        }

    /** "3.85 V": the abbreviated measurement with two fraction digits. */
    val voltageText: String? get() = battery?.let { String.format(Locale.ROOT, "%.2f V", it.voltageVolts) }

    private companion object {
        const val BYTES_PER_KILOBYTE = 1024L
    }
}

/** Device identity, node-name edit (UTF-8 byte-limited, verified), battery and storage readout. */
class DeviceInfoStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val identity: () -> NodeIdentityPort?,
    private val battery: DeviceBatteryPort,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(DeviceInfoState())
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    val state: StateFlow<DeviceInfoState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            launch { connection.connectedDevice.collect { d -> mutable.update { it.copy(device = d) } } }
            launch { battery.battery.collect { b -> mutable.update { it.copy(battery = b) } } }
            refreshBattery()
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    /** Pull to refresh and the initial `.task`. */
    suspend fun refreshBattery() {
        try {
            battery.fetch()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (ignored: Exception) {
            // The readout simply stays on its spinner.
        }
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }

    fun beginEditName() {
        val device = mutable.value.device ?: return
        mutable.update { it.copy(nodeName = device.nodeName, isEditingName = true) }
    }

    fun cancelEditName() = mutable.update { it.copy(isEditingName = false) }

    /** Caps the name at the usable UTF-8 byte limit without splitting a character. */
    fun onNodeNameChanged(text: String) {
        val capped = if (text.toByteArray(Charsets.UTF_8).size > ProtocolLimits.MAX_USABLE_NAME_BYTES) {
            text.utf8Prefix(ProtocolLimits.MAX_USABLE_NAME_BYTES)
        } else text
        mutable.update { it.copy(nodeName = capped) }
    }

    fun saveNodeName() {
        mutable.update { it.copy(isEditingName = false) }
        val name = SwiftCharacterSets.trimWhitespacesAndNewlines(mutable.value.nodeName)
        val port = identity()
        if (name.isEmpty() || port == null) return
        mutable.update { it.copy(isSaving = true) }
        env.scope.launch {
            try {
                port.setNodeNameVerified(name)
                retryAlert.reset()
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(isSaving = false) }
                throw cancelled
            } catch (error: Exception) {
                nodeNameFailure(error)
            }
            mutable.update { it.copy(isSaving = false) }
        }
    }

    private fun nodeNameFailure(error: Exception) {
        val message = failures.route(error) { saveNodeName() }
        mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
    }
}
