// AndroidOnly: WP-317 Behavior tests for the manual radio section (no frozen Swift case covers it); expectations follow AdvancedRadioSection.swift.
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.support.FakeConnection
import com.meshcoreone.android.feature.settings.device.support.FakeSettingsPort
import com.meshcoreone.android.feature.settings.device.support.TestScheduler
import com.meshcoreone.android.feature.settings.device.support.device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.yield

class AdvancedRadioStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device(frequency = 869_525u, bandwidth = 7_799u, spreadingFactor = 11u, codingRate = 6u, txPower = 14))
    private val service = FakeSettingsPort()
    private val gate = RadioWriteGate()
    private var serviceProvider: SettingsRadioPort? = service
    private val holder = AdvancedRadioStateHolder(scheduler.environment(), connection, { serviceProvider }, gate)
    private val state get() = holder.state.value
    private val invalidInput = UiText.Resource(AppSettingsStrings.advancedRadioInvalidInput)

    private fun started(): AdvancedRadioStateHolder { holder.start(); scheduler.runCurrent(); return holder }

    @Test
    fun `loads the device values with the nearest standard bandwidth and a three-digit frequency`() {
        started()
        assertTrue(state.hasLoaded)
        assertEquals("869.525", state.frequencyText)
        assertEquals(869.525, state.frequency)
        assertEquals(7_800u, state.bandwidth)
        assertEquals(11, state.spreadingFactor)
        assertEquals(6, state.codingRate)
        assertEquals("14", state.txPowerText)
        assertFalse(state.settingsModified)
        assertFalse(state.canApply(false))
    }

    @Test
    fun `nothing loads while disconnected and the section shows its spinner state`() {
        val empty = AdvancedRadioStateHolder(scheduler.environment(), FakeConnection(null), { service }, gate)
        empty.start(); scheduler.runCurrent()
        assertFalse(empty.state.value.hasLoaded)
    }

    @Test
    fun `an edit enables apply only while the radio is ready and no write is in flight`() {
        started()
        holder.onFrequencyTextChanged("868.1")
        assertTrue(state.settingsModified)
        assertTrue(state.canApply(false))
        assertFalse(state.canApply(true))
        connection.stateFlow.value = DeviceConnectionState.CONNECTED
        scheduler.runCurrent()
        assertFalse(state.canApply(false))
    }

    @Test
    fun `apply writes verified radio params then tx power and shows the checkmark for 1_5 s`() {
        started()
        holder.onFrequencyTextChanged("868.1")
        holder.onBandwidthSelected(125_000u)
        holder.onSpreadingFactorSelected(9)
        holder.onCodingRateSelected(5)
        holder.onTxPowerTextChanged("10")
        holder.apply()
        assertTrue(state.isApplying && gate.inFlight.value)
        scheduler.runCurrent()
        assertEquals(listOf("setRadioParamsVerified(868100,125000,9,5,false,null)", "setTxPowerVerified(10)"), service.calls)
        assertTrue(state.showSuccess)
        assertFalse(state.isApplying || gate.inFlight.value)
        scheduler.advanceBy(1500.milliseconds)
        assertFalse(state.showSuccess)
        assertNull(state.errorMessage)
    }

    @Test
    fun `repeat mode keeps the radio's frequency and passes the repeat flag through`() {
        connection.deviceFlow.value = device(frequency = 910_525u, clientRepeat = true)
        started()
        holder.onFrequencyTextChanged("868.1")
        holder.onSpreadingFactorSelected(9)
        holder.apply()
        scheduler.runCurrent()
        assertEquals("setRadioParamsVerified(910525,250000,9,5,true,null)", service.calls.first())
        assertFalse(state.frequencyEditable)
    }

    @Test
    fun `invalid input is rejected before any write`() {
        started()
        val invalid = listOf("", "abc", "nan", "149.9994", "2500.0006", "-1", "1e400")
        for (text in invalid) {
            holder.onFrequencyTextChanged(text)
            holder.dismissError()
            holder.apply()
            assertEquals(invalidInput, state.errorMessage, "frequency [$text]")
        }
        holder.onFrequencyTextChanged("915")
        for (power in listOf("", "x", "21", "-10", "128", "99999999999999999999")) {
            holder.onTxPowerTextChanged(power)
            holder.dismissError()
            holder.apply()
            assertEquals(invalidInput, state.errorMessage, "tx power [$power]")
        }
        assertTrue(service.calls.isEmpty())
        assertFalse(state.isApplying || gate.inFlight.value)
    }

    @Test
    fun `range edges are accepted and the frequency rounds half away from zero`() {
        started()
        holder.onTxPowerTextChanged("-9")
        for ((text, kHz) in listOf("150" to 150_000, "2500" to 2_500_000, "915.0005" to 915_001, "149.9996" to 150_000, "2500.0004" to 2_500_000)) {
            service.calls.clear()
            holder.onFrequencyTextChanged(text)
            holder.apply()
            scheduler.advanceBy(1500.milliseconds)
            assertTrue(service.calls.first().startsWith("setRadioParamsVerified($kHz,"), "[$text] ${service.calls}")
        }
    }

    @Test
    fun `tx power may not exceed the device maximum and a missing device falls back to the floor`() {
        connection.deviceFlow.value = device(maxTxPower = 10, txPower = 5)
        started()
        holder.onTxPowerTextChanged("11")
        holder.apply()
        assertEquals(invalidInput, state.errorMessage)
        holder.onTxPowerTextChanged("10")
        holder.dismissError()
        holder.apply()
        scheduler.runCurrent()
        assertTrue(service.calls.contains("setTxPowerVerified(10)"))
    }

    @Test
    fun `a second apply while a write is in flight is ignored`() {
        started()
        holder.onTxPowerTextChanged("10")
        gate.set(true)
        holder.apply()
        assertTrue(service.calls.isEmpty())
        assertFalse(state.isApplying)
    }

    @Test
    fun `missing service reports invalid input`() {
        started()
        serviceProvider = null
        holder.onTxPowerTextChanged("10")
        holder.apply()
        assertEquals(invalidInput, state.errorMessage)
    }

    @Test
    fun `a retryable failure opens the retry alert and retry repeats the write`() {
        started()
        holder.onTxPowerTextChanged("10")
        service.failNext("setRadioParamsVerified", SettingsServiceException(SettingsServiceError.SendFailed))
        holder.apply()
        scheduler.runCurrent()
        assertTrue(holder.retryAlertState.value.isPresented)
        assertNull(state.errorMessage)
        assertFalse(state.isApplying || gate.inFlight.value)
        holder.retry.retry()
        scheduler.runCurrent()
        assertEquals(2, service.calls.count { it.startsWith("setRadioParamsVerified") })
        assertEquals(0, holder.retryAlertState.value.retryCount)
    }

    @Test
    fun `three retryable failures end in the unable-to-save dialog whose OK dismisses the page`() {
        started()
        holder.onTxPowerTextChanged("10")
        repeat(3) { service.failNext("setRadioParamsVerified", SettingsServiceException(SettingsServiceError.SendFailed)) }
        holder.apply()
        scheduler.runCurrent()
        repeat(2) { holder.retry.retry(); scheduler.runCurrent() }
        assertTrue(holder.retryAlertState.value.isMaxRetriesExceeded)
        holder.retry.acknowledgeMaxRetries()
        assertEquals(1, scheduler.dismissCount)
        assertEquals(0, holder.retryAlertState.value.retryCount)
    }

    @Test
    fun `a non retryable failure shows the error and verification failures are not retried`() {
        started()
        holder.onTxPowerTextChanged("10")
        service.failNext("setTxPowerVerified", SettingsServiceException(SettingsServiceError.VerificationFailed("10", "9")))
        holder.apply()
        scheduler.runCurrent()
        assertTrue(state.errorMessage is UiText.Verbatim)
        assertFalse(holder.retryAlertState.value.isPresented)
        assertFalse(state.showSuccess)
    }

    @Test
    fun `device changes reload the fields except while applying`() {
        started()
        holder.onFrequencyTextChanged("1")
        connection.deviceFlow.value = device(frequency = 433_175u, bandwidth = 125_000u)
        scheduler.runCurrent()
        assertEquals("433.175", state.frequencyText)
        assertEquals(125_000u, state.bandwidth)
        // An unrelated device edit (name) must not reset the user's typing.
        holder.onFrequencyTextChanged("868")
        connection.deviceFlow.value = connection.deviceFlow.value!!.copy(nodeName = "Renamed")
        scheduler.runCurrent()
        assertEquals("868", state.frequencyText)
    }

    @Test
    fun `an applying write defers the reload of its own intermediate device state`() {
        started()
        holder.onTxPowerTextChanged("10")
        service.onCall = { method ->
            if (method == "setRadioParamsVerified") {
                connection.deviceFlow.value = device(frequency = 902_000u, txPower = 14)
                yield()
            }
        }
        holder.apply()
        scheduler.runCurrent()
        assertEquals("869.525", state.frequencyText)
    }

    @Test
    fun `the success checkmark clears when the device later diverges from the form`() {
        started()
        holder.onTxPowerTextChanged("10")
        holder.apply()
        scheduler.runCurrent()
        assertTrue(state.showSuccess)
        connection.deviceFlow.value = device(frequency = 902_000u)
        scheduler.runCurrent()
        assertFalse(state.showSuccess)
    }
}
