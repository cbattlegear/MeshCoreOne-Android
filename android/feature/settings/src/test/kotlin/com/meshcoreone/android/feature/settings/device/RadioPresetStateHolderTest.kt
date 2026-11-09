// AndroidOnly: WP-317 Behavior tests for the preset picker and Repeat Mode (no frozen Swift case covers them); expectations follow RadioPresetSection.swift.
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.feature.settings.device.support.FakeCatalog
import com.meshcoreone.android.feature.settings.device.support.FakeConnection
import com.meshcoreone.android.feature.settings.device.support.FakeRegions
import com.meshcoreone.android.feature.settings.device.support.FakeSettingsPort
import com.meshcoreone.android.feature.settings.device.support.FakeStore
import com.meshcoreone.android.feature.settings.device.support.TestScheduler
import com.meshcoreone.android.feature.settings.device.support.device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RadioPresetStateHolderTest {
    private val scheduler = TestScheduler()
    private val connection = FakeConnection(device(frequency = 915_000u))
    private val service = FakeSettingsPort()
    private val catalog = FakeCatalog()
    private val regions = FakeRegions()
    private val store = FakeStore()
    private val gate = RadioWriteGate()
    private val holder = RadioPresetStateHolder(scheduler.environment(), connection, regions, catalog, { service }, store, gate)
    private val state get() = holder.state.value

    private fun started(): RadioPresetStateHolder { holder.start(); scheduler.runCurrent(); return holder }

    @Test
    fun `starts on the preset the radio matches and refreshes self info once`() {
        started()
        assertEquals("us-915", state.selectedPresetId)
        assertEquals("us-915", state.currentPresetId)
        assertFalse(state.showsCustomRow)
        assertEquals(listOf("getSelfInfo"), service.calls)
    }

    @Test
    fun `an unmatched radio offers the custom row and selects nothing`() {
        connection.deviceFlow.value = device(frequency = 902_000u)
        started()
        assertNull(state.selectedPresetId)
        assertTrue(state.showsCustomRow)
    }

    @Test
    fun `picking a preset applies it verified and releases the gate`() {
        started()
        holder.onPresetSelected("eu-868")
        assertTrue(state.isApplying && gate.inFlight.value)
        scheduler.runCurrent()
        assertEquals("applyRadioPresetVerified(eu-868)", service.calls.last())
        assertFalse(state.isApplying || gate.inFlight.value)
    }

    @Test
    fun `picking custom never writes and syncing from the radio never writes`() {
        started()
        holder.onPresetSelected(null)
        connection.deviceFlow.value = connection.deviceFlow.value!!.copy(frequency = 868_000u)
        scheduler.runCurrent()
        assertEquals(listOf("getSelfInfo"), service.calls)
        assertEquals("eu-868", state.selectedPresetId)
    }

    @Test
    fun `a failed apply puts the picker back to what the radio matches`() {
        started()
        service.failNext("applyRadioPresetVerified", SettingsServiceException(SettingsServiceError.InvalidResponse))
        holder.onPresetSelected("eu-868")
        scheduler.runCurrent()
        assertEquals("us-915", state.selectedPresetId)
        assertTrue(state.errorMessage != null)
    }

    @Test
    fun `a retryable failure offers retry and retrying applies again`() {
        started()
        service.failNext("applyRadioPresetVerified", SettingsServiceException(SettingsServiceError.SendFailed))
        holder.onPresetSelected("eu-868")
        scheduler.runCurrent()
        assertTrue(holder.retryAlertState.value.isPresented)
        holder.retry.retry()
        scheduler.runCurrent()
        assertEquals(2, service.calls.count { it.startsWith("applyRadioPresetVerified") })
    }

    @Test
    fun `turning repeat mode on waits for the confirmation and then saves pre-repeat settings and writes the nearest repeat frequency`() {
        started()
        holder.onRepeatToggled(true)
        assertTrue(state.showRepeatConfirmation)
        assertFalse(state.isRepeatEnabled)
        assertTrue(store.calls.isEmpty())
        holder.confirmEnableRepeatMode()
        scheduler.runCurrent()
        assertEquals(listOf("savePreRepeat"), store.calls)
        assertTrue(state.isRepeatEnabled)
        assertEquals("rep-2", state.selectedPresetId)
        assertEquals("setRadioParamsVerified(911000,250000,10,5,true,null)", service.calls.last())
    }

    @Test
    fun `failed repeat snapshot prevents radio change and leaves repeat mode recoverable`() {
        started()
        store.preRepeatError = IllegalStateException("snapshot persistence failed")
        holder.onRepeatToggled(true)
        holder.confirmEnableRepeatMode()
        scheduler.runCurrent()

        assertEquals(listOf("savePreRepeat"), store.calls)
        assertEquals(listOf("getSelfInfo"), service.calls)
        assertFalse(state.isRepeatEnabled || state.isApplyingRepeat || gate.inFlight.value)
        assertEquals("us-915", state.selectedPresetId)
        assertTrue(state.errorMessage != null)
    }

    @Test
    fun `retired device repeat snapshot prevents radio change`() {
        started()
        store.preRepeatResult = false
        holder.onRepeatToggled(true)
        holder.confirmEnableRepeatMode()
        scheduler.runCurrent()

        assertEquals(listOf("savePreRepeat"), store.calls)
        assertEquals(listOf("getSelfInfo"), service.calls)
        assertFalse(state.isRepeatEnabled || state.isApplyingRepeat || gate.inFlight.value)
        assertEquals("us-915", state.selectedPresetId)
        assertTrue(state.errorMessage != null)
    }

    @Test
    fun `device switch after repeat snapshot does not leave repeat enabled on successor`() {
        val original = device(id = java.util.UUID(0, 100), frequency = 915_000u, clientRepeat = false)
        val successor = device(id = java.util.UUID(0, 101), frequency = 868_000u, clientRepeat = false)
        val switchedConnection = FakeConnection(original)
        val switchedService = FakeSettingsPort()
        val switchedCatalog = FakeCatalog()
        val switchedStore = FakeStore()
        val switchedGate = RadioWriteGate()
        var switchOnServiceLookup = false
        val switchedHolder = RadioPresetStateHolder(
            scheduler.environment(), switchedConnection, regions, switchedCatalog,
            {
                if (switchOnServiceLookup) {
                    switchOnServiceLookup = false
                    switchedConnection.deviceFlow.value = successor
                }
                switchedService
            },
            switchedStore, switchedGate,
        )
        switchedHolder.start()
        scheduler.runCurrent()

        switchOnServiceLookup = true
        switchedHolder.onRepeatToggled(true)
        switchedHolder.confirmEnableRepeatMode()
        scheduler.runCurrent()

        assertEquals(successor.id, switchedConnection.connectedDevice.value?.id)
        assertEquals(listOf("savePreRepeat"), switchedStore.calls)
        assertEquals(listOf("getSelfInfo", "getSelfInfo"), switchedService.calls)
        assertFalse(switchedHolder.state.value.isRepeatEnabled)
        assertFalse(switchedHolder.state.value.isApplyingRepeat || switchedGate.inFlight.value)
        assertEquals("eu-868", switchedHolder.state.value.selectedPresetId)
    }

    @Test
    fun `cancelling the repeat confirmation changes nothing`() {
        started()
        holder.onRepeatToggled(true)
        holder.dismissRepeatConfirmation()
        assertFalse(state.showRepeatConfirmation || state.isRepeatEnabled)
        assertEquals(listOf("getSelfInfo"), service.calls)
    }

    @Test
    fun `turning repeat mode off restores the saved radio settings and clears them`() {
        connection.deviceFlow.value = device(frequency = 910_525u, clientRepeat = true).copy(
            preRepeatFrequency = 868_000u, preRepeatBandwidth = 125_000u, preRepeatSpreadingFactor = 9u, preRepeatCodingRate = 6u,
        )
        started()
        assertTrue(state.isRepeatEnabled)
        assertEquals("rep-1", state.selectedPresetId)
        holder.onRepeatToggled(false)
        scheduler.runCurrent()
        assertEquals("setRadioParamsVerified(868000,125000,9,6,false,null)", service.calls.last())
        assertEquals(listOf("clearPreRepeat"), store.calls)
        assertFalse(state.isRepeatEnabled)
        assertFalse(state.isApplyingRepeat || gate.inFlight.value)
    }

    @Test
    fun `turning repeat mode off falls back to the live radio values and reverts the switch on failure`() {
        connection.deviceFlow.value = device(frequency = 910_525u, clientRepeat = true)
        started()
        service.failNext("setRadioParamsVerified", SettingsServiceException(SettingsServiceError.InvalidResponse))
        holder.onRepeatToggled(false)
        scheduler.runCurrent()
        assertEquals("setRadioParamsVerified(910525,250000,10,5,false,null)", service.calls.last())
        assertTrue(state.isRepeatEnabled)
        assertTrue(store.calls.isEmpty())
        assertTrue(state.errorMessage != null)
    }

    @Test
    fun `the radio flipping repeat mode on its own updates the switch and picker`() {
        started()
        connection.deviceFlow.value = device(frequency = 910_525u, clientRepeat = true)
        scheduler.runCurrent()
        assertTrue(state.isRepeatEnabled)
        assertEquals("rep-1", state.selectedPresetId)
        assertEquals(listOf("getSelfInfo"), service.calls.filter { it != "getSelfInfo" }.ifEmpty { listOf("getSelfInfo") })
    }

    @Test
    fun `the region footer data follows the selection and the picker disables while a write runs`() {
        started()
        regions.flow.value = RegionSelection("PT", RegionSelection.Source.MANUAL)
        scheduler.runCurrent()
        assertEquals(RegionSelection("PT", RegionSelection.Source.MANUAL), state.region)
        assertTrue(state.pickerEnabled(false))
        assertFalse(state.pickerEnabled(true))
    }
}
