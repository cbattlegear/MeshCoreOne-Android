// PortedFrom: MC1Services/Tests/MC1ServicesTests/PairingStrandedAssociationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.ble.BleError
import com.meshcoreone.android.core.ble.BleTransportException
import com.meshcoreone.android.core.connectivity.pairing.CompanionPairingService
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupError
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.pairing.PairingCoordinator
import com.meshcoreone.android.core.connectivity.pairing.PairingError
import com.meshcoreone.android.core.connectivity.support.FakeRadio
import com.meshcoreone.android.core.connectivity.support.MockCompanionSetupService
import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.association
import com.meshcoreone.android.core.connectivity.support.runtimeScenario
import com.meshcoreone.android.core.connectivity.support.scenario
import com.meshcoreone.android.core.connectivity.support.settle
import com.meshcoreone.android.core.connectivity.support.testDevice
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.runtime.LinkFailure
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Test

/** Pairing never removes associations; unsaved ones are listed, then removed only on confirmation. */
class PairingStrandedAssociationTest {
    @Test @OriginalCase("PairingStrandedAssociationTests::fresh-pair authentication failure removes no association and reports connectionFailed()")
    fun `fresh-pair authentication failure removes no association and reports connectionFailed`() = runtimeScenario {
        val id = UUID.randomUUID()
        devices.saveDevice(testDevice(id))
        companion.setPairedAccessories(listOf(association(id, "test")))
        companion.setPickerResult(Result.success(id))
        companion.isSessionActive = false
        createRadio = { FakeRadio().also { it.connectFailure = BleTransportException(BleError.AuthenticationFailed, status = 5) } }
        val pairing = coordinator().apply { otherAppWaitStrategy = { false } }
        val failure = assertFailsWith<PairingError.ConnectionFailed> { pairing.pairNewDevice() }
        assertTrue(failure.isAuthenticationFailure)
        assertEquals(id, failure.deviceId)
        assertEquals(0, companion.removeAccessoryCallCount)
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::transient connect failure during pairing removes no association()")
    fun `transient connect failure during pairing removes no association`() = runtimeScenario {
        val id = UUID.randomUUID()
        companion.setPickerResult(Result.success(id))
        companion.isSessionActive = false
        createRadio = { FakeRadio().also { it.connectFailure = BleTransportException(BleError.ConnectionFailed("out of range")) } }
        val pairing = coordinator().apply { otherAppWaitStrategy = { false } }
        val result = CompletableDeferred<Result<Unit>>()
        scenario.scope.launch { result.complete(runCatching { pairing.pairNewDevice() }) }
        assertTrue(scenario.awaitCondition { result.isCompleted }, "Retries advance on the virtual clock")
        val failure = assertIs<PairingError.ConnectionFailed>(result.await().exceptionOrNull())
        assertFalse(failure.isAuthenticationFailure)
        assertEquals(id, failure.deviceId)
        assertEquals(0, companion.removeAccessoryCallCount)
        assertEquals(4, radios.size, "Full four-attempt budget with a system pairing registry")
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::pairNewDevice does not remove an unsaved ASK accessory()")
    fun `pairNewDevice does not remove an unsaved companion association`() = runtimeScenario {
        companion.setPairedAccessories(listOf(association(UUID.randomUUID(), "stranded")))
        companion.setPickerResult(Result.failure(CompanionSetupError.PickerDismissed()))
        assertFailsWith<DevicePairingError.Cancelled> { coordinator().pairNewDevice() }
        assertEquals(0, companion.removeAccessoryCallCount)
        assertEquals(1, companion.showPickerCallCount)
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::systemAccessoriesMissingDeviceRecord omits saved radios and reports unsaved ASK accessories()")
    fun `systemAccessoriesMissingDeviceRecord omits saved radios and reports unsaved associations`() = runtimeScenario {
        val saved = UUID.randomUUID(); val unsaved = UUID.randomUUID()
        devices.saveDevice(testDevice(saved))
        companion.setPairedAccessories(listOf(association(saved, "saved"), association(unsaved, "unsaved")))
        val pending = coordinator().systemAccessoriesMissingDeviceRecord()
        assertEquals(listOf(unsaved), pending.map { it.id })
        assertEquals("unsaved", pending.first().name)
        assertEquals(0, companion.removeAccessoryCallCount)
        assertEquals(0, companion.showPickerCallCount)
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::systemAccessoriesMissingDeviceRecord skips an id when fetchDevice throws()")
    fun `systemAccessoriesMissingDeviceRecord skips an id when fetchDevice throws`() = runtimeScenario {
        val saved = UUID.randomUUID()
        devices.saveDevice(testDevice(saved))
        companion.setPairedAccessories(listOf(association(saved, "saved")))
        devices.fetchByIdFault = IllegalStateException("fetchFailed")
        val pairing = coordinator()
        assertTrue(pairing.systemAccessoriesMissingDeviceRecord().isEmpty())
        pairing.removeSystemAccessoriesMissingDeviceRecord(listOf(saved))
        assertEquals(0, companion.removeAccessoryCallCount)
        assertTrue(reports.any { it.first == "pairing.missingRecord.fetch" }, "The skipped lookup failure is reported")
        devices.fetchByIdFault = null
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::systemAccessoriesMissingDeviceRecord activates before enumerating()")
    fun `systemAccessoriesMissingDeviceRecord activates before enumerating`() = runtimeScenario {
        val unsaved = UUID.randomUUID()
        companion.isSessionActive = false
        companion.setPairedAccessories(listOf(association(unsaved, "B")))
        val pending = coordinator().systemAccessoriesMissingDeviceRecord()
        assertTrue(companion.activateSessionCallCount >= 1)
        assertEquals(listOf(unsaved), pending.map { it.id })
        assertEquals(0, companion.showPickerCallCount)
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::removeSystemAccessoriesMissingDeviceRecord removes only the requested unsaved ids()")
    fun `removeSystemAccessoriesMissingDeviceRecord removes only the requested unsaved ids`() = runtimeScenario {
        val saved = UUID.randomUUID(); val unsaved = UUID.randomUUID()
        devices.saveDevice(testDevice(saved))
        companion.setPairedAccessories(listOf(association(saved, "saved"), association(unsaved, "unsaved")))
        coordinator().removeSystemAccessoriesMissingDeviceRecord(listOf(saved, unsaved))
        assertEquals(1, companion.removeAccessoryCallCount)
        assertEquals(unsaved, companion.lastRemovedDeviceId)
        assertEquals(listOf(saved), companion.storedAccessories.map { it.deviceId })
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::removeDevice maps ASError.userCancelled to DevicePairingError.cancelled()", "platform-adaptation")
    fun `removeDevice maps a declined removal to DevicePairingError_cancelled`() = scenario {
        val mock = MockCompanionSetupService()
        val unsaved = UUID.randomUUID()
        mock.setPairedAccessories(listOf(association(unsaved, "unsaved")))
        mock.removeAccessoryError = CompanionSetupError.UserCancelled()
        val pairing = CompanionPairingService(mock)
        assertFailsWith<DevicePairingError.Cancelled> { pairing.removeDevice(unsaved) }
        assertNotNull(mock.accessory(unsaved))
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::discoverDevice maps pickerRestricted to DevicePairingError.pickerUnavailable()")
    fun `discoverDevice maps pickerRestricted to DevicePairingError_pickerUnavailable`() = scenario {
        val mock = MockCompanionSetupService()
        mock.setPickerResult(Result.failure(CompanionSetupError.PickerRestricted()))
        val pairing = CompanionPairingService(mock)
        pairing.activate()
        assertFailsWith<DevicePairingError.PickerUnavailable> { pairing.discoverDevice() }
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::systemAccessoriesMissingDeviceRecord omits a connected id with no Device row()")
    fun `systemAccessoriesMissingDeviceRecord omits a connected id with no Device row`() = runtimeScenario {
        val live = connectReady()
        devices.rows.remove(live)
        companion.setPairedAccessories(listOf(association(live, "live")))
        val pairing = coordinator()
        assertTrue(pairing.systemAccessoriesMissingDeviceRecord().isEmpty())
        pairing.removeSystemAccessoriesMissingDeviceRecord(listOf(live))
        assertEquals(0, companion.removeAccessoryCallCount)
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::systemAccessoriesMissingDeviceRecord omits an in-flight attempt id with no Device row()")
    fun `systemAccessoriesMissingDeviceRecord omits an in-flight attempt id with no Device row`() = runtimeScenario {
        val attempt = UUID.randomUUID()
        register(attempt, "attempt")
        val gate = CompletableDeferred<Unit>()
        createRadio = { FakeRadio().also { it.beforeConnect = { gate.await() } } }
        val connect = scenario.scope.launch { runCatching { manager.connect(target(attempt)) } }
        assertTrue(scenario.awaitCondition { manager.activeConnectionAttemptDeviceId == attempt })
        val pairing = coordinator()
        assertTrue(pairing.systemAccessoriesMissingDeviceRecord().isEmpty())
        pairing.removeSystemAccessoriesMissingDeviceRecord(listOf(attempt))
        assertEquals(0, companion.removeAccessoryCallCount)
        gate.complete(Unit)
        connect.join()
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::settings-removal keeps the Device row when ASK still lists the accessory()")
    fun `settings-removal keeps the Device row when the registry still lists the association`() = runtimeScenario {
        val id = UUID.randomUUID()
        val device = testDevice(id)
        devices.saveDevice(device)
        last.persist(id, device.radioId, device.nodeName)
        companion.setPairedAccessories(listOf(association(id, "Radio")))
        val pairing = coordinator()
        pairing.devicePairingDidRemoveDevice(pairing.pairing, id)
        settle()
        assertNull(lastConnectedDeviceId())
        assertNotNull(devices.fetchDevice(id))
        assertTrue(pairing.systemAccessoriesMissingDeviceRecord().isEmpty())
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::settings-removal ghosts the Device row when ASK no longer lists the accessory()")
    fun `settings-removal ghosts the Device row when the registry no longer lists the association`() = runtimeScenario {
        val id = UUID.randomUUID()
        val device = testDevice(id)
        devices.saveDevice(device)
        last.persist(id, device.radioId, device.nodeName)
        val pairing = coordinator()
        pairing.devicePairingDidRemoveDevice(pairing.pairing, id)
        settle()
        assertNull(devices.fetchDevice(id))
        assertNotNull(devices.fetchDevice(device.publicKey), "Ghost keeps the publicKey bridge")
        assertNull(lastConnectedDeviceId())
        assertTrue(pairing.systemAccessoriesMissingDeviceRecord().isEmpty())
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::pairNewDevice does not treat a just-paired system-connected radio as another app()")
    fun `pairNewDevice does not treat a just-paired system-connected radio as another app`() = runtimeScenario {
        val id = UUID.randomUUID()
        companion.setPickerResult(Result.success(id))
        companion.isSessionActive = false
        stub.systemConnected = { true }
        stub.adoptionSucceeds = true
        var settleCalls = 0
        val pairing = coordinator().apply { adoptionSettleStrategy = { settleCalls++ } }
        pairing.pairNewDevice()
        assertEquals(1, settleCalls)
        assertEquals(listOf(id), adoptions)
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::connect after last-connected cleared does not throw other-app for an ASK-owned radio()")
    fun `connect after last-connected cleared does not throw other-app for a companion-owned radio`() = runtimeScenario {
        val id = UUID.randomUUID()
        companion.setPairedAccessories(listOf(association(id, "Radio")))
        companion.isSessionActive = false
        stub.systemConnected = { true }
        stub.adoptionSucceeds = true
        manager.connect(target(id), forceFullSync = true, forceReconnect = true)
        assertEquals(listOf(id), adoptions)
        assertEquals(DeviceConnectionState.CONNECTING, manager.connectionState)
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::waitForPairingAdoptionToSettle times out while still connected()")
    fun `waitForPairingAdoptionToSettle times out while still connected`() = runtimeScenario {
        manager.setConnectionState(DeviceConnectionState.CONNECTED)
        val pairing = coordinator().apply { adoptionSettleTimeout = kotlin.time.Duration.parse("80ms") }
        val result = CompletableDeferred<Result<Unit>>()
        scenario.scope.launch { result.complete(runCatching { pairing.waitForPairingAdoptionToSettle() }) }
        assertTrue(scenario.awaitCondition { result.isCompleted })
        val failure = assertIs<ConnectivityError.ConnectionFailed>(result.await().exceptionOrNull())
        assertEquals(PairingCoordinator.PAIRING_ADOPTION_TIMED_OUT_DETAIL, failure.detail)
    }

    @Test @OriginalCase("PairingStrandedAssociationTests::connect still throws other-app for a system-connected radio this app does not own()")
    fun `connect still throws other-app for a system-connected radio this app does not own`() = runtimeScenario {
        val id = UUID.randomUUID()
        companion.isSessionActive = false
        stub.systemConnected = { true }
        assertFailsWith<LinkFailure.DeviceConnectedToOtherApp> {
            manager.connect(target(id), forceFullSync = true, forceReconnect = true)
        }
        assertTrue(adoptions.isEmpty())
    }
}
