// AndroidOnly: WP-317 Rendered radio actions and retries must never acquire a successor session.
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.feature.settings.device.support.FakeCatalog
import com.meshcoreone.android.feature.settings.device.support.FakeConnection
import com.meshcoreone.android.feature.settings.device.support.FakeRegions
import com.meshcoreone.android.feature.settings.device.support.FakeSettingsPort
import com.meshcoreone.android.feature.settings.device.support.FakeStore
import com.meshcoreone.android.feature.settings.device.support.TestScheduler
import com.meshcoreone.android.feature.settings.device.support.device
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow

class RadioSettingsSessionTargetTest {
    private class Rig(repeat: Boolean = false) {
        val scheduler = TestScheduler()
        val firstDevice = device(firmwareVersion = 11u, clientRepeat = repeat, frequency = if (repeat) 910_525u else 915_000u)
        val first = SessionToken(ProcessEpoch(UUID(0, 10)), Generation(1), firstDevice.radioId)
        val secondDevice = firstDevice.copy(id = UUID(0, 20), radioId = RadioId(UUID(0, 21)))
        val second = first.copy(generation = Generation(2), radioId = secondDevice.radioId)
        val base = FakeConnection(firstDevice).also { it.startupFlow.value = false }
        val observations = MutableStateFlow(SettingsDeviceObservation(firstDevice, first))
        val connection = object : SettingsConnection by base {
            override val deviceObservations = observations
        }
        val a = FakeSettingsPort().also { port ->
            port.onCall = {
                if (observations.value.sessionToken != first) throw SettingsNotConnectedException()
            }
        }
        val b = FakeSettingsPort()
        private val aPort = object : SettingsRadioPort by a {
            override val sessionToken = first
            override fun currentDevice(expectedToken: SessionToken): DeviceDTO {
                if (expectedToken != first) throw SettingsNotConnectedException()
                return currentDeviceFor(expectedToken)
            }
        }
        private val bPort = object : SettingsRadioPort by b {
            override val sessionToken = second
            override fun currentDevice(expectedToken: SessionToken): DeviceDTO {
                if (expectedToken != second) throw SettingsNotConnectedException()
                return currentDeviceFor(expectedToken)
            }
        }
        var current: SettingsRadioPort = aPort
        val store = FakeStore()
        val preset = RadioPresetStateHolder(
            scheduler.environment(), connection, FakeRegions(), FakeCatalog(), { current }, store, RadioWriteGate(),
        )
        val hash = PathHashModeStateHolder(scheduler.environment(), connection, { current })
        val advanced = AdvancedRadioStateHolder(scheduler.environment(), connection, { current }, RadioWriteGate())

        init {
            preset.start()
            hash.start()
            advanced.start()
            scheduler.runCurrent()
            a.calls.clear()
            b.calls.clear()
        }

        fun switchToB() {
            base.deviceFlow.value = secondDevice
            observations.value = SettingsDeviceObservation(secondDevice, second)
            current = bPort
        }

        private fun currentDeviceFor(expected: SessionToken): DeviceDTO {
            val observed = observations.value
            if (observed.sessionToken != expected) throw SettingsNotConnectedException()
            return observed.device ?: throw SettingsNotConnectedException()
        }
    }

    @Test fun queuedNormalAndRepeatPresetSelectionsRetainAInsteadOfAcquiringB() {
        for (repeat in listOf(false, true)) {
            val r = Rig(repeat)
            r.preset.onPresetSelected(if (repeat) "rep-1" else "eu-868", r.first)
            assertTrue(r.preset.state.value.isApplying)
            r.switchToB()
            r.scheduler.runCurrent()
            assertTrue(r.b.calls.isEmpty(), "Queued selection must not write B")
            assertNotNull(r.preset.state.value.errorMessage)
            assertFalse(r.preset.state.value.isApplying)
        }
    }

    @Test fun normalAndRepeatPresetRetriesRetainTheOriginalBinding() {
        for (repeat in listOf(false, true)) {
            val r = Rig(repeat)
            r.a.failNext(if (repeat) "setRadioParamsVerified" else "applyRadioPresetVerified",
                SettingsServiceException(SettingsServiceError.SendFailed))
            r.preset.onPresetSelected(if (repeat) "rep-1" else "eu-868", r.first)
            r.scheduler.runCurrent()
            assertTrue(r.preset.retryAlertState.value.isPresented)
            val originalCalls = r.a.calls.toList()
            r.switchToB()
            r.scheduler.runCurrent()
            r.preset.retry.retry()
            r.scheduler.runCurrent()
            assertEquals(originalCalls, r.a.calls)
            assertTrue(r.b.calls.isEmpty(), "Retry must not acquire B")
            assertNotNull(r.preset.state.value.errorMessage)
        }
    }

    @Test fun staleRenderedPresetAndRepeatOffCallbacksCannotTargetBOrMissingProductionIdentity() {
        val r = Rig(repeat = true)
        val oldPresetCallback = { r.preset.onPresetSelected("rep-1", r.first) }
        val oldOffCallback = { r.preset.onRepeatToggled(false, r.first) }
        r.switchToB()
        r.scheduler.runCurrent()
        oldPresetCallback()
        assertNotNull(r.preset.state.value.errorMessage)
        r.preset.dismissError()
        oldOffCallback()
        assertNotNull(r.preset.state.value.errorMessage)
        r.preset.dismissError()
        r.preset.onPresetSelected("rep-1", null)
        assertNotNull(r.preset.state.value.errorMessage)
        assertTrue(r.b.calls.isEmpty())
        assertTrue(r.store.calls.isEmpty())
        r.preset.onPresetSelected("rep-1", r.second)
        r.scheduler.runCurrent()
        assertEquals(1, r.b.calls.count { it.startsWith("setRadioParamsVerified") })
    }

    @Test fun repeatConfirmationRetainsItsOpeningIdentityAcrossAtoB() {
        for (confirmWithNewRender in listOf(false, true)) {
            val r = Rig()
            r.preset.onRepeatToggled(true, r.first)
            assertTrue(r.preset.state.value.showRepeatConfirmation)
            r.switchToB()
            r.scheduler.runCurrent()
            r.preset.confirmEnableRepeatMode(if (confirmWithNewRender) r.second else r.first)
            r.scheduler.runCurrent()
            assertNotNull(r.preset.state.value.errorMessage)
            assertFalse(r.preset.state.value.showRepeatConfirmation || r.preset.state.value.isApplyingRepeat)
            assertTrue(r.store.calls.isEmpty(), "Stale confirmation must not snapshot B")
            assertTrue(r.b.calls.isEmpty(), "Stale confirmation must not enable B")
        }
    }

    @Test fun repeatOffRetryCannotClearOrWriteTheSuccessor() {
        val r = Rig(repeat = true)
        r.a.failNext("setRadioParamsVerified", SettingsServiceException(SettingsServiceError.SendFailed))
        r.preset.onRepeatToggled(false, r.first)
        r.scheduler.runCurrent()
        assertTrue(r.preset.retryAlertState.value.isPresented)
        r.switchToB()
        r.scheduler.runCurrent()
        r.preset.retry.retry()
        r.scheduler.runCurrent()
        assertNotNull(r.preset.state.value.errorMessage)
        assertTrue(r.b.calls.isEmpty())
        assertTrue(r.store.calls.isEmpty())
    }

    @Test fun consumedOrDismissedConfirmationCannotBeReusedToEnableB() {
        for (dismiss in listOf(false, true)) {
            val r = Rig()
            r.preset.onRepeatToggled(true, r.first)
            r.switchToB()
            r.scheduler.runCurrent()
            val retainedBConfirm = { r.preset.confirmEnableRepeatMode(r.second) }
            if (dismiss) r.preset.dismissRepeatConfirmation() else retainedBConfirm()
            retainedBConfirm()
            retainedBConfirm()
            r.scheduler.runCurrent()
            assertNotNull(r.preset.state.value.errorMessage)
            assertTrue(r.b.calls.isEmpty(), "Repeated/dismissed callback must not create B's confirmation target")
            assertTrue(r.store.calls.isEmpty())
            r.preset.onRepeatToggled(true, r.second)
            r.preset.confirmEnableRepeatMode(r.second)
            r.scheduler.runCurrent()
            assertEquals(listOf("savePreRepeat"), r.store.calls)
            assertEquals(1, r.b.calls.count { it.startsWith("setRadioParamsVerified") })
        }
    }

    @Test fun sameTokenQueuedAndRetriedRepeatPresetUsesCurrentUntouchedRadioFields() {
        for (retry in listOf(false, true)) {
            val r = Rig(repeat = true)
            if (retry) r.a.failNext("setRadioParamsVerified", SettingsServiceException(SettingsServiceError.SendFailed))
            r.preset.onPresetSelected("rep-1", r.first)
            if (retry) {
                r.scheduler.runCurrent()
                assertTrue(r.preset.retryAlertState.value.isPresented)
            }
            val current = r.firstDevice.copy(bandwidth = 125_000u, spreadingFactor = 9u, codingRate = 6u)
            r.base.deviceFlow.value = current
            r.observations.value = SettingsDeviceObservation(current, r.first)
            if (retry) r.preset.retry.retry()
            r.scheduler.runCurrent()
            assertEquals("setRadioParamsVerified(910525,125000,9,6,true,null)", r.a.calls.last())
            assertEquals(current, r.a.radioExpectations.last())
            assertTrue(r.b.calls.isEmpty())
            assertFalse(r.preset.state.value.isApplying)
        }
    }

    @Test fun sameTokenQueuedAndRetriedManualApplyReadsCurrentRepeatConstraint() {
        for (initialRepeat in listOf(false, true)) for (retry in listOf(false, true)) {
            val r = Rig(repeat = initialRepeat)
            r.advanced.onFrequencyTextChanged("868.1")
            r.advanced.onTxPowerTextChanged("10")
            if (retry) r.a.failNext("setRadioParamsVerified", SettingsServiceException(SettingsServiceError.SendFailed))
            r.advanced.apply(r.first)
            if (retry) {
                r.scheduler.runCurrent()
                assertTrue(r.advanced.retryAlertState.value.isPresented)
            }
            val current = r.firstDevice.copy(clientRepeat = !initialRepeat, frequency = 910_525u)
            r.base.deviceFlow.value = current
            r.observations.value = SettingsDeviceObservation(current, r.first)
            val verificationEntered = CompletableDeferred<Unit>()
            val verificationRelease = CompletableDeferred<Unit>()
            r.a.onCall = { method ->
                if (method == "setRadioParamsVerified") {
                    verificationEntered.complete(Unit)
                    verificationRelease.await()
                }
            }
            if (retry) r.advanced.retry.retry()
            r.scheduler.runCurrent()
            assertTrue(verificationEntered.isCompleted)
            assertTrue(r.advanced.state.value.isApplying)
            verificationRelease.complete(Unit)
            r.scheduler.runCurrent()
            val expectedFrequency = if (initialRepeat || current.clientRepeat) current.frequency else 868_100u
            assertEquals(
                "setRadioParamsVerified($expectedFrequency,250000,10,5,${current.clientRepeat},null)",
                r.a.calls.last { it.startsWith("setRadioParamsVerified") },
            )
            assertEquals("setTxPowerVerified(10)", r.a.calls.last())
            assertEquals(current, r.a.radioExpectations.last())
            assertTrue(r.b.calls.isEmpty())
            assertTrue(r.advanced.state.value.showSuccess)
            assertEquals(expectedFrequency.toDouble() / 1000.0, r.advanced.state.value.frequency)
            assertEquals(formatFrequencyMHz(expectedFrequency), r.advanced.state.value.frequencyText)
        }
    }

    @Test fun heldHashSelectionAndItsRetryNeverAcquireB() {
        for (retry in listOf(false, true)) {
            val r = Rig()
            if (retry) r.a.failNext("setPathHashModeVerified", SettingsServiceException(SettingsServiceError.SendFailed))
            r.hash.onModeSelected(2u, r.first)
            if (retry) {
                r.scheduler.runCurrent()
                assertTrue(r.hash.retryAlertState.value.isPresented)
            }
            r.switchToB()
            if (retry) {
                r.scheduler.runCurrent()
                r.hash.retry.retry()
            }
            r.scheduler.runCurrent()
            assertTrue(r.b.calls.isEmpty())
            assertNotNull(r.hash.state.value.errorMessage)
            assertFalse(r.hash.state.value.isApplying)
        }
    }

    @Test fun staleRenderedHashCallbackFailsButFreshBSelectionSucceeds() {
        val r = Rig()
        val oldCallback = { r.hash.onModeSelected(2u, r.first) }
        r.switchToB()
        r.scheduler.runCurrent()
        oldCallback()
        r.scheduler.runCurrent()
        assertTrue(r.b.calls.isEmpty())
        assertNotNull(r.hash.state.value.errorMessage)
        r.hash.onModeSelected(2u, r.second)
        r.scheduler.runCurrent()
        assertEquals(listOf("setPathHashModeVerified(2)"), r.b.calls)
    }

    @Test fun manualRadioHeldDispatchAndRetryKeepTheCapturedServiceAndParameters() {
        for (repeat in listOf(false, true)) for (retry in listOf(false, true)) {
            val r = Rig(repeat)
            r.advanced.onTxPowerTextChanged("10")
            if (retry) r.a.failNext("setRadioParamsVerified", SettingsServiceException(SettingsServiceError.SendFailed))
            r.advanced.apply(r.first)
            if (retry) {
                r.scheduler.runCurrent()
                assertTrue(r.advanced.retryAlertState.value.isPresented)
            }
            r.switchToB()
            if (retry) {
                r.scheduler.runCurrent()
                r.advanced.retry.retry()
            }
            r.scheduler.runCurrent()
            assertTrue(r.b.calls.isEmpty(), "Manual radio must not acquire B")
            assertNotNull(r.advanced.state.value.errorMessage)
            assertFalse(r.advanced.state.value.isApplying || r.advanced.state.value.showSuccess)
        }
    }

    @Test fun staleRenderedManualApplyFailsAndFreshBApplySucceeds() {
        val r = Rig()
        val oldCallback = { r.advanced.apply(r.first) }
        r.switchToB()
        r.scheduler.runCurrent()
        r.advanced.onTxPowerTextChanged("10")
        oldCallback()
        assertNotNull(r.advanced.state.value.errorMessage)
        assertTrue(r.b.calls.isEmpty())
        r.advanced.apply(r.second)
        r.scheduler.runCurrent()
        assertEquals(1, r.b.calls.count { it.startsWith("setRadioParamsVerified") })
        assertEquals("setTxPowerVerified(10)", r.b.calls.last())
        assertTrue(r.advanced.state.value.showSuccess)
    }
}
