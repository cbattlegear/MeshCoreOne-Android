// PortedFrom: MC1/Views/Settings/Sections/AdvancedRadioSection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.ui.UiText
import kotlin.math.abs
import kotlin.math.truncate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Swift `@Binding var radioWriteInFlight`: serializes the preset and manual radio writes across sections. */
class RadioWriteGate {
    private val mutable = MutableStateFlow(false)
    val inFlight: StateFlow<Boolean> = mutable.asStateFlow()
    fun set(value: Boolean) { mutable.value = value }
}

data class AdvancedRadioState(
    val device: DeviceDTO? = null,
    val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
    val hasLoaded: Boolean = false,
    /** What the frequency field shows, in MHz; [frequency] is its lenient parse (null when unparseable). */
    val frequencyText: String = "",
    val frequency: Double? = null,
    val bandwidth: UInt? = null,
    val spreadingFactor: Int? = null,
    val codingRate: Int? = null,
    val txPowerText: String = "",
    val txPower: Int? = null,
    val isApplying: Boolean = false,
    val showSuccess: Boolean = false,
    val errorMessage: UiText? = null,
    val sessionToken: SessionToken? = null,
) {
    /** Repeat Mode pins the frequency, so its field is disabled. */
    val frequencyEditable: Boolean get() = device?.clientRepeat != true

    val settingsModified: Boolean
        get() {
            val current = device ?: return false
            val deviceFrequency = current.frequency.toDouble() / 1000.0
            return frequency.let { it == null || it != deviceFrequency } ||
                bandwidth != RadioOptions.nearestBandwidth(current.bandwidth) ||
                spreadingFactor != current.spreadingFactor.toInt() ||
                codingRate != current.codingRate.toInt() ||
                txPower != current.txPower.toInt()
        }

    fun canApply(radioWriteInFlight: Boolean): Boolean =
        connectionState == DeviceConnectionState.READY && settingsModified && !isApplying && !showSuccess && !radioWriteInFlight
}

/** Manual radio parameters: validate, then write verified radio params and TX power in that order. */
class AdvancedRadioStateHolder(
    private val env: SettingsEnvironment,
    private val connection: SettingsConnection,
    private val settingsService: () -> SettingsRadioPort?,
    private val writeGate: RadioWriteGate,
    private val retryAlert: RetryAlertController = RetryAlertController(),
) {
    private val mutable = MutableStateFlow(AdvancedRadioState())
    private val failures = SettingsFailureRouter(env, retryAlert)
    private var observing: Job? = null
    private var lastHash: List<Any?>? = null
    private var appliedTarget: SettingsRadioTarget? = null
    val state: StateFlow<AdvancedRadioState> = mutable.asStateFlow()
    val retryAlertState: StateFlow<RetryAlertState> = retryAlert.state
    val retry: RetryAlertController get() = retryAlert

    /** Starts following the connection (Swift `onAppear` plus `onChange(of: deviceRadioSettingsHash)`). Idempotent. */
    fun start() {
        if (observing != null) return
        observing = env.scope.launch {
            launch { connection.connectionState.collect { s -> mutable.update { it.copy(connectionState = s) } } }
            connection.deviceObservations.collect { observed ->
                val target = appliedTarget
                val current = if (target?.token != null && target.token == observed.sessionToken) {
                    try {
                        target.currentDevice(observed.device, observed.sessionToken)
                    } catch (failure: SettingsNotConnectedException) {
                        mutable.update { it.copy(errorMessage = env.describe(failure)) }
                        return@collect
                    }
                } else {
                    appliedTarget = null
                    observed.device
                }
                mutable.update { it.copy(sessionToken = observed.sessionToken) }
                onDevice(current)
            }
        }
    }

    fun stop() {
        observing?.cancel()
        observing = null
    }

    fun dismissError() = mutable.update { it.copy(errorMessage = null) }

    fun onFrequencyTextChanged(text: String) =
        mutable.update { it.copy(frequencyText = text, frequency = SwiftNumberFieldParser.parsePosixDouble(text)) }

    fun onTxPowerTextChanged(text: String) =
        mutable.update { it.copy(txPowerText = text, txPower = SwiftNumberFieldParser.parseInteger(text)) }

    fun onBandwidthSelected(hz: UInt) = mutable.update { it.copy(bandwidth = hz) }
    fun onSpreadingFactorSelected(value: Int) = mutable.update { it.copy(spreadingFactor = value) }
    fun onCodingRateSelected(value: Int) = mutable.update { it.copy(codingRate = value) }

    private fun onDevice(device: DeviceDTO?) {
        mutable.update { it.copy(device = device) }
        val hash = device?.let { listOf(it.frequency, it.bandwidth, it.spreadingFactor, it.codingRate, it.txPower, it.clientRepeat) }
        val first = lastHash == null
        if (!first && hash == lastHash) return
        lastHash = hash ?: listOf<Any?>(null)
        // Skip reloads only while this apply is in flight: frequency and clientRepeat arrive as separate events,
        // and reloading that intermediate state would flicker the field.
        if (!first && mutable.value.isApplying) return
        if (!first && mutable.value.showSuccess && mutable.value.settingsModified) mutable.update { it.copy(showSuccess = false) }
        loadCurrentSettings()
    }

    private fun loadCurrentSettings() {
        val device = mutable.value.device ?: return
        mutable.update {
            it.copy(
                frequency = device.frequency.toDouble() / 1000.0,
                frequencyText = formatFrequencyMHz(device.frequency),
                // Nearest handles non-standard bandwidths and firmware float error such as 7799 Hz for 7800 Hz.
                bandwidth = RadioOptions.nearestBandwidth(device.bandwidth),
                spreadingFactor = device.spreadingFactor.toInt(),
                codingRate = device.codingRate.toInt(),
                txPower = device.txPower.toInt(),
                txPowerText = device.txPower.toInt().toString(),
                hasLoaded = true,
            )
        }
    }

    fun apply() = apply(mutable.value.sessionToken)

    fun apply(renderedToken: SessionToken?) {
        if (writeGate.inFlight.value) return
        val current = mutable.value
        val service = settingsService()
        val target = try {
            if (service == null) null else
                SettingsRadioTarget.capture(renderedToken, current.device, current.sessionToken, service)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            mutable.update { it.copy(errorMessage = env.describe(failure)) }
            return
        }
        val request = target?.let { validated(current, it.device) }
        if (service == null || request == null) {
            mutable.update { it.copy(errorMessage = UiText.Resource(AppSettingsStrings.advancedRadioInvalidInput)) }
            return
        }
        startApply(requireNotNull(target), request)
    }

    private fun startApply(target: SettingsRadioTarget, request: Request) {
        if (writeGate.inFlight.value) return
        mutable.update { it.copy(isApplying = true) }
        writeGate.set(true)
        env.scope.launch { runApply(target, request) }
    }

    private class Request(val frequencyKHz: UInt, val bandwidthHz: UInt, val spreadingFactor: UByte, val codingRate: UByte, val power: Byte)

    /** Frequency and TX power are free text, so convert with non-trapping checks before scaling to wire fields. */
    private fun validated(state: AdvancedRadioState, device: DeviceDTO?): Request? {
        val freqMHz = state.frequency ?: return null
        val bandwidthHz = state.bandwidth ?: return null
        val spread = state.spreadingFactor ?: return null
        val code = state.codingRate ?: return null
        val power = state.txPower ?: return null
        val scaledKHz = roundHalfAwayFromZero(freqMHz * 1000)
        val range = PacketBuilder.FREQUENCY_RANGE_KHZ
        if (!freqMHz.isFinite() || scaledKHz < range.first.toDouble() || scaledKHz > range.last.toDouble()) return null
        val maxTxPower = device?.maxTxPower ?: PacketBuilder.TX_POWER_FLOOR
        if (spread !in 0..255 || code !in 0..255 || power !in Byte.MIN_VALUE..Byte.MAX_VALUE) return null
        if (power < PacketBuilder.TX_POWER_FLOOR || power > maxTxPower) return null
        return Request(scaledKHz.toLong().toUInt(), bandwidthHz, spread.toUByte(), code.toUByte(), power.toByte())
    }

    private suspend fun runApply(target: SettingsRadioTarget, request: Request) {
        try {
            val service = target.service
            service.reconcileRadioSettingsIfNeeded()
            val device = target.currentDevice(mutable.value.device, mutable.value.sessionToken)
            // Firmware treats an omitted repeat byte as off; read current values only from the captured radio.
            val captured = target.device
            val frequencyToSend =
                if (captured.clientRepeat || device.clientRepeat || request.frequencyKHz == captured.frequency) device.frequency
                else request.frequencyKHz
            val bandwidthToSend =
                if (request.bandwidthHz == RadioOptions.nearestBandwidth(captured.bandwidth)) device.bandwidth
                else request.bandwidthHz
            val sfToSend = if (request.spreadingFactor == captured.spreadingFactor) device.spreadingFactor else request.spreadingFactor
            val crToSend = if (request.codingRate == captured.codingRate) device.codingRate else request.codingRate
            val powerToSend = if (request.power == captured.txPower) device.txPower else request.power
            service.setRadioParamsVerified(
                frequencyToSend, bandwidthToSend, sfToSend, crToSend,
                clientRepeat = device.clientRepeat,
                expectedDevice = device,
            )
            target.requireCurrent(mutable.value.device, mutable.value.sessionToken)
            // RF is committed; retain reconciled power intent if its subsequent write fails.
            appliedTarget = target
            mutable.update {
                it.copy(
                    frequency = frequencyToSend.toDouble() / 1000.0,
                    frequencyText = formatFrequencyMHz(frequencyToSend),
                    bandwidth = RadioOptions.nearestBandwidth(bandwidthToSend),
                    spreadingFactor = sfToSend.toInt(),
                    codingRate = crToSend.toInt(),
                    txPower = powerToSend.toInt(),
                    txPowerText = powerToSend.toString(),
                )
            }
            service.setTxPowerVerified(powerToSend)
            target.requireCurrent(mutable.value.device, mutable.value.sessionToken)
            mutable.update { it.copy(txPower = powerToSend.toInt(), txPowerText = powerToSend.toString()) }
        } catch (cancelled: CancellationException) {
            release()
            throw cancelled
        } catch (error: Exception) {
            val message = failures.route(error) { startApply(target, request) }
            mutable.update { it.copy(errorMessage = message ?: it.errorMessage) }
            release()
            return
        }
        retryAlert.reset()
        release()
        mutable.update { it.copy(showSuccess = true) }
        try {
            env.clock.sleep(SUCCESS_DISPLAY)
        } finally {
            mutable.update { it.copy(showSuccess = false) }
        }
    }

    private fun release() {
        mutable.update { it.copy(isApplying = false) }
        writeGate.set(false)
    }

    private fun roundHalfAwayFromZero(value: Double): Double {
        val whole = truncate(value)
        return if (abs(value - whole) >= 0.5) whole + if (value < 0) -1.0 else 1.0 else whole
    }
}
