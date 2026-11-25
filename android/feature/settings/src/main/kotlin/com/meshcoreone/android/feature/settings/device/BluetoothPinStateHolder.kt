// PortedFrom: MC1/Views/Settings/Sections/BluetoothSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Firmware BLE pairing PIN protocol values: both the factory default and zero mean "no custom PIN configured". */
object BlePin {
    const val FIRMWARE_DEFAULT: UInt = 123_456u
    const val UNSET: UInt = 0u

    /** A custom PIN must be exactly six digits. */
    val CUSTOM_RANGE: UIntRange = 100_000u..999_999u
}

enum class BluetoothPinType { DEFAULT, CUSTOM }

data class BluetoothPinState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val pinType: BluetoothPinType = BluetoothPinType.DEFAULT,
    val isPinVisible: Boolean = false,
    val showingPinEntry: Boolean = false,
    val showingChangePinEntry: Boolean = false,
    val showingRemoveConfirmation: Boolean = false,
    val isChangingPin: Boolean = false,
    val errorMessage: UiText? = null,
) {
    /** The picker shows the device's actual type: unset and the firmware default are both "default". */
    val currentPinType: BluetoothPinType
        get() {
            val pin = device?.blePin ?: return BluetoothPinType.DEFAULT
            return if (pin == BlePin.UNSET || pin == BlePin.FIRMWARE_DEFAULT) BluetoothPinType.DEFAULT else BluetoothPinType.CUSTOM
        }
    val showsCurrentPin: Boolean get() = pinType == BluetoothPinType.CUSTOM && (device?.blePin ?: 0u) > 0u
    val enabled: Boolean get() = connectionState == DeviceConnectionState.READY && !isChangingPin
}

/**
 * BLE PIN: custom six-digit PIN or the firmware default. Each change is written to RAM and applied by a reboot, so
 * both confirmation paths end in the expected link-drop timeout.
 */
class BluetoothPinStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
) {
    private val mutable = MutableStateFlow(BluetoothPinState())
    private var observing: Job? = null
    private var synced = false
    val state: StateFlow<BluetoothPinState> = mutable.asStateFlow()

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            connection.connectedDevice.collect { d ->
                mutable.update { it.copy(device = d) }
                // Swift syncs the picker on appear only; later device updates must not undo the user's pending choice.
                if (!synced && d != null) {
                    synced = true
                    mutable.update { it.copy(isPinVisible = false) }
                    revertPinType()
                }
            }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }
    fun togglePinVisible() = mutable.update { it.copy(isPinVisible = !it.isPinVisible) }
    fun requestChangePin() = mutable.update { it.copy(showingChangePinEntry = true) }
    private fun revertPinType() = mutable.update { it.copy(pinType = it.currentPinType) }

    /** The user picked a type in the menu. Custom asks for a PIN; custom to default asks to confirm. */
    fun onPinTypeSelected(newType: BluetoothPinType) {
        val before = mutable.value
        if (newType == before.currentPinType) { mutable.update { it.copy(pinType = newType) }; return }
        mutable.update { it.copy(pinType = newType) }
        when {
            newType == BluetoothPinType.CUSTOM && before.pinType != BluetoothPinType.CUSTOM ->
                mutable.update { it.copy(showingPinEntry = true) }
            before.pinType == BluetoothPinType.CUSTOM && newType == BluetoothPinType.DEFAULT ->
                mutable.update { it.copy(showingRemoveConfirmation = true) }
        }
    }

    /** Cancel on either the set-PIN entry or the remove confirmation: back to the device's actual type. */
    fun cancelPinDialog() {
        mutable.update { it.copy(showingPinEntry = false, showingRemoveConfirmation = false, showingChangePinEntry = false) }
        revertPinType()
    }

    fun dismissChangePinEntry() = mutable.update { it.copy(showingChangePinEntry = false) }

    fun confirmRemoveCustomPin() {
        mutable.update { it.copy(showingRemoveConfirmation = false) }
        write(BlePin.FIRMWARE_DEFAULT)
    }

    /** Set or change the PIN from the text the user typed; anything but six digits reports the error and reverts. */
    fun submitCustomPin(text: String) {
        mutable.update { it.copy(showingPinEntry = false, showingChangePinEntry = false) }
        val pin = SwiftNumberFieldParser.parseUInt32Strict(text)
        if (pin == null || pin !in BlePin.CUSTOM_RANGE) {
            mutable.update { it.copy(errorMessage = UiText.Resource(AppSettingsStrings.bluetoothErrorInvalidPin)) }
            revertPinType()
            return
        }
        write(pin)
    }

    private fun write(pin: UInt) {
        mutable.update { it.copy(isChangingPin = true) }
        env.scope.launch {
            try {
                val service = settingsService() ?: throw SettingsNotConnectedException()
                // Written to RAM until the reboot; the radio disconnects before it can acknowledge the reboot.
                service.setBlePin(pin)
                try {
                    service.reboot()
                } catch (expected: SettingsOperationTimeoutException) {
                    // Expected: the device reboots before the write acknowledgement arrives.
                }
            } catch (cancelled: CancellationException) {
                mutable.update { it.copy(isChangingPin = false) }
                throw cancelled
            } catch (error: Exception) {
                mutable.update { it.copy(errorMessage = env.describe(error)) }
                revertPinType()
            }
            mutable.update { it.copy(isChangingPin = false) }
        }
    }
}
