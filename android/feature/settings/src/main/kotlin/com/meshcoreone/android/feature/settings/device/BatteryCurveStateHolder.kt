// PortedFrom: MC1/Views/Settings/Sections/BatteryCurveSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/Components/BatteryCurveChart.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.OCVPreset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Why the voltage list is invalid; the UI maps it to the localized message. */
sealed interface BatteryCurveValidationError {
    /** A value outside 1000..99999 mV; [percent] is the table row (100 down to 0). */
    data class OutOfRange(val percent: Int) : BatteryCurveValidationError
    data object NotDescending : BatteryCurveValidationError
}

data class BatteryCurveState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val selectedPreset: OCVPreset = OCVPreset.LI_ION,
    val voltages: List<Long> = OCVPreset.LI_ION.ocvArray.toList(),
    val isEditingValues: Boolean = false,
    val validationError: BatteryCurveValidationError? = null,
) {
    val isDisabled: Boolean get() = connectionState != DeviceConnectionState.READY
    val presets: List<OCVPreset> get() = OCVPreset.selectablePresets.toList()

    /** Custom is shown in the menu only while selected and not already among the presets. */
    val showsCustomRow: Boolean get() = selectedPreset == OCVPreset.CUSTOM && OCVPreset.CUSTOM !in presets

    /** A field is flagged when it is out of range or not strictly below its neighbour above or above its neighbour below. */
    fun fieldHasError(index: Int): Boolean {
        val value = voltages[index]
        if (value !in OCVPreset.validMillivoltRange) return true
        if (index > 0 && voltages[index - 1] <= value) return true
        if (index < voltages.lastIndex && value <= voltages[index + 1]) return true
        return false
    }
}

/**
 * Battery curve editor. Preset picks save immediately; typed values commit when the field loses focus or on submit,
 * never per keystroke, so half-typed numbers never reach the device. Save failures are silent, as in Swift (`try?`).
 */
class BatteryCurveStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val store: DeviceSettingsStorePort,
) {
    private val mutable = MutableStateFlow(BatteryCurveState())
    private var observing: Job? = null
    private var loadedDeviceId: java.util.UUID? = null
    private val valueWhenFocused = HashMap<Int, Long>()
    val state: StateFlow<BatteryCurveState> = mutable.asStateFlow()

    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            connection.connectedDevice.collect { d ->
                mutable.update { it.copy(device = d) }
                // `.task(id: connectedDevice?.id)`: reload only when the radio changes.
                if (d?.id != loadedDeviceId) { loadedDeviceId = d?.id; loadFromDevice() }
            }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun setEditingValues(expanded: Boolean) = mutable.update { it.copy(isEditingValues = expanded) }

    /** `OCVPreset(rawValue:)` or a custom 11-value list parsed with Swift's `Int(String)` after trimming spaces. */
    fun loadFromDevice() {
        val device = mutable.value.device ?: return
        val name = device.ocvPreset
        if (name != null) {
            if (name == OCVPreset.CUSTOM.rawValue && device.customOCVArrayString != null) {
                val parsed = device.customOCVArrayString!!.split(',')
                    .mapNotNull { SwiftIntText.parseTrimmed(it) }
                if (parsed.size == OCV_POINTS) {
                    mutable.update { it.copy(voltages = parsed, selectedPreset = OCVPreset.CUSTOM) }
                    return
                }
            }
            OCVPreset.fromRawValue(name)?.let { preset ->
                mutable.update { it.copy(selectedPreset = preset, voltages = preset.ocvArray.toList()) }
                return
            }
        }
        mutable.update { it.copy(selectedPreset = OCVPreset.LI_ION, voltages = OCVPreset.LI_ION.ocvArray.toList()) }
    }

    fun onPresetSelected(preset: OCVPreset) {
        mutable.update { it.copy(selectedPreset = preset) }
        if (preset != OCVPreset.CUSTOM) {
            val values = preset.ocvArray.toList()
            mutable.update { it.copy(voltages = values) }
            save(preset, values)
        }
    }

    /** Typed text for row [index]; unparseable text leaves the value as it was (a `TextField(value:)` binding). */
    fun onVoltageTextChanged(index: Int, text: String) {
        val parsed = SwiftNumberFieldParser.parseLong(text) ?: return
        mutable.update { state -> state.copy(voltages = state.voltages.mapIndexed { i, v -> if (i == index) parsed else v }) }
    }

    fun onFieldFocusChanged(index: Int, focused: Boolean) {
        if (focused) {
            valueWhenFocused[index] = mutable.value.voltages[index]
        } else {
            commitEdit(index)
            valueWhenFocused.remove(index)
        }
    }

    fun onFieldSubmitted(index: Int) = commitEdit(index)

    private fun commitEdit(index: Int) {
        if (valueWhenFocused[index] == mutable.value.voltages[index]) return
        valueWhenFocused[index] = mutable.value.voltages[index]
        handleCommit()
    }

    private fun handleCommit() {
        val error = validate(mutable.value.voltages)
        mutable.update { it.copy(validationError = error) }
        if (error != null) return
        val state = mutable.value
        // Values that still match the selected preset are a no-op: reclassifying them as custom would flip the picker.
        if (state.selectedPreset != OCVPreset.CUSTOM && state.voltages == state.selectedPreset.ocvArray.toList()) return
        mutable.update { it.copy(selectedPreset = OCVPreset.CUSTOM) }
        save(OCVPreset.CUSTOM, state.voltages)
    }

    private fun save(preset: OCVPreset, values: List<Long>) {
        val deviceId = mutable.value.device?.id ?: return
        env.scope.launch {
            try {
                store.updateOcvSettings(deviceId, preset.rawValue, if (preset == OCVPreset.CUSTOM) values.joinToString(",") else null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (ignored: Exception) {
                // `try?`: a failed save is not surfaced.
            }
        }
    }

    companion object {
        const val OCV_POINTS = 11

        /** Every value in range, then strictly descending from 100 % down to 0 %. */
        fun validate(values: List<Long>): BatteryCurveValidationError? {
            values.forEachIndexed { index, value ->
                if (value !in OCVPreset.validMillivoltRange) return BatteryCurveValidationError.OutOfRange((10 - index) * 10)
            }
            if (values.zipWithNext().any { (current, next) -> current <= next }) return BatteryCurveValidationError.NotDescending
            return null
        }
    }
}

/** Swift `Int(String)` after `trimmingCharacters(in: .whitespaces)`: ASCII digits with an optional sign (oracle3). */
internal object SwiftIntText {
    fun parseTrimmed(text: String): Long? {
        val trimmed = text.trim { it == ' ' || it == '\t' || it == ' ' || it == ' ' || it == '　' }
        val body = if (trimmed.startsWith("+") || trimmed.startsWith("-")) trimmed.substring(1) else trimmed
        if (body.isEmpty() || !body.all { it in '0'..'9' }) return null
        return trimmed.toLongOrNull()
    }
}
