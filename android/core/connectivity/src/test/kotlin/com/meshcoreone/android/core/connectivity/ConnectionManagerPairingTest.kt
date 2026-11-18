// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerPairingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupError
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.support.InMemoryDeviceAccess
import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.PRESET_MATCHER
import com.meshcoreone.android.core.connectivity.support.PRESET_ROWS
import com.meshcoreone.android.core.connectivity.support.RuntimeDeviceAccess
import com.meshcoreone.android.core.connectivity.support.RuntimeHarness
import com.meshcoreone.android.core.connectivity.support.runtimeScenario
import com.meshcoreone.android.core.connectivity.support.selfInfo
import com.meshcoreone.android.core.connectivity.support.settle
import com.meshcoreone.android.core.connectivity.support.testDevice
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.junit.Test

class ConnectionManagerPairingTest {
    private fun RuntimeHarness.editor(access: com.meshcoreone.android.core.connectivity.device.ConnectedDeviceAccess) =
        ConnectedDeviceEditor(access, devices, contacts, { null }, PRESET_MATCHER, scenario.scope, { clock.instant })

    private fun RuntimeHarness.offlineEditor(device: DeviceDTO? = null): ConnectedDeviceEditor {
        device?.let { devices.rows[it.id] = it }
        return editor(InMemoryDeviceAccess(device))
    }

    private fun preset(id: String) = PRESET_ROWS.first { it.id == id }

    @Test @OriginalCase("ConnectionManagerPairingTests::unfavoritedNodeCount throws when not connected()")
    fun `unfavoritedNodeCount throws when not connected`() = runtimeScenario {
        assertFailsWith<ConnectivityError.NotConnected> { editor(RuntimeDeviceAccess(manager)).unfavoritedNodeCount() }
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::removeUnfavoritedNodes throws when not connected()")
    fun `removeUnfavoritedNodes throws when not connected`() = runtimeScenario {
        assertFailsWith<ConnectivityError.NotConnected> { editor(RuntimeDeviceAccess(manager)).removeUnfavoritedNodes() }
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::removeStaleNodes throws when not connected()")
    fun `removeStaleNodes throws when not connected`() = runtimeScenario {
        assertFailsWith<ConnectivityError.NotConnected> { editor(RuntimeDeviceAccess(manager)).removeStaleNodes(30) }
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::pairNewDevice rejects re-entry without clearing the outer call's flag()")
    fun `pairNewDevice rejects re-entry without clearing the outer call's flag`() = runtimeScenario {
        val pairing = coordinator()
        pairing.setPairingFlags(inProgress = true)
        assertFailsWith<DevicePairingError.AlreadyInProgress> { pairing.pairNewDevice() }
        assertTrue(pairing.isPairingInProgress)
        assertTrue(manager.shouldDeferOpportunisticReconnect)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::pairNewDevice stops BLE scanning before showing ASK picker()")
    fun `pairNewDevice stops BLE scanning before showing the companion picker`() = runtimeScenario {
        val pairing = coordinator()
        val consumer = scenario.scope.launch { scans.startBleScanning().collect {} }
        assertTrue(scenario.awaitCondition { scanGateway.scanning })
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        companion.pickerEntered = entered
        companion.pickerGate = gate
        companion.setPickerResult(Result.failure(CompanionSetupError.PickerDismissed()))
        val task = scenario.scope.launch { runCatching { pairing.pairNewDevice() } }
        entered.await()
        assertEquals(1, scanGateway.stopCount)
        assertEquals(false, scanGateway.scanning)
        gate.complete(Unit)
        task.join()
        consumer.cancel()
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updateDevice(with:) updates connectedDevice()", "native-equivalent")
    fun `updateDevice(with) updates connectedDevice`() = runtimeScenario {
        val access = InMemoryDeviceAccess()
        val device = testDevice(nodeName = "NewDevice")
        editor(access).updateDevice(device)
        assertEquals("NewDevice", access.connectedDevice?.nodeName)
        assertEquals(device.id, access.connectedDevice?.id)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updateAutoAddConfig updates config when connected()", "native-equivalent")
    fun `updateAutoAddConfig updates config when connected`() = runtimeScenario {
        val editor = offlineEditor(testDevice())
        editor.updateAutoAddConfig(AutoAddConfig(5u, 3u))?.join()
        assertEquals(5.toUByte(), editor.connectedDevice?.autoAddConfig)
        assertEquals(3.toUByte(), editor.connectedDevice?.autoAddMaxHops)
        assertEquals(editor.connectedDevice, devices.rows[editor.connectedDevice!!.id], "Edit is persisted")
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updateAutoAddConfig does nothing when not connected()", "native-equivalent")
    fun `updateAutoAddConfig does nothing when not connected`() = runtimeScenario {
        val editor = offlineEditor()
        assertNull(editor.updateAutoAddConfig(AutoAddConfig(5u, 3u)))
        assertNull(editor.connectedDevice)
        assertTrue(devices.rows.isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updateClientRepeat updates repeat flag when connected()", "native-equivalent")
    fun `updateClientRepeat updates repeat flag when connected`() = runtimeScenario {
        val editor = offlineEditor(testDevice())
        editor.updateClientRepeat(true)
        assertEquals(true, editor.connectedDevice?.clientRepeat)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updatePathHashMode updates hash mode when connected()", "native-equivalent")
    fun `updatePathHashMode updates hash mode when connected`() = runtimeScenario {
        val editor = offlineEditor(testDevice())
        editor.updatePathHashMode(2u)
        assertEquals(2.toUByte(), editor.connectedDevice?.pathHashMode)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updateDevice stamps appliedRadioPresetID from the event()", "native-equivalent")
    fun `updateDevice stamps appliedRadioPresetID from the event`() = runtimeScenario {
        val editor = offlineEditor(testDevice().copy(appliedRadioPresetID = null, clientRepeat = false))
        editor.updateDevice(selfInfo(preset("br")), "br")
        assertEquals("br", editor.connectedDevice?.appliedRadioPresetID)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updateDevice clears appliedRadioPresetID when RF no longer matches()", "native-equivalent")
    fun `updateDevice clears appliedRadioPresetID when RF no longer matches`() = runtimeScenario {
        val editor = offlineEditor(testDevice().copy(appliedRadioPresetID = "br", clientRepeat = false))
        editor.updateDevice(selfInfo(preset("us-ca")), null)
        assertNull(editor.connectedDevice?.appliedRadioPresetID)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updateDevice keeps appliedRadioPresetID when RF still matches()", "native-equivalent")
    fun `updateDevice keeps appliedRadioPresetID when RF still matches`() = runtimeScenario {
        val editor = offlineEditor(testDevice().copy(appliedRadioPresetID = "br", clientRepeat = false))
        editor.updateDevice(selfInfo(preset("br")), null)
        assertEquals("br", editor.connectedDevice?.appliedRadioPresetID)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::updateDevice keeps appliedRadioPresetID during Repeat Mode()", "native-equivalent")
    fun `updateDevice keeps appliedRadioPresetID during Repeat Mode`() = runtimeScenario {
        val editor = offlineEditor(testDevice().copy(appliedRadioPresetID = "br", clientRepeat = false))
        editor.updateClientRepeat(true)
        editor.updateDevice(selfInfo(869.495, 62.5, 8u, 8u), null)
        assertEquals("br", editor.connectedDevice?.appliedRadioPresetID)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::savePreRepeatSettings changes connectedDevice()", "native-equivalent")
    fun `savePreRepeatSettings changes connectedDevice`() = runtimeScenario {
        val editor = offlineEditor(testDevice(frequency = 915_000u, bandwidth = 250_000u, spreadingFactor = 10u, codingRate = 5u))
        val original = editor.connectedDevice
        editor.savePreRepeatSettings()
        assertNotEquals(original, editor.connectedDevice)
        assertNotNull(editor.connectedDevice)
        assertEquals(915_000u, editor.connectedDevice?.preRepeatFrequency)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::clearPreRepeatSettings clears saved settings()", "native-equivalent")
    fun `clearPreRepeatSettings clears saved settings`() = runtimeScenario {
        val editor = offlineEditor(testDevice())
        editor.savePreRepeatSettings()
        val afterSave = editor.connectedDevice
        editor.clearPreRepeatSettings()
        assertNotEquals(afterSave, editor.connectedDevice)
        assertNull(editor.connectedDevice?.preRepeatFrequency)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::waitForOtherAppReconnection returns true on immediate detection()")
    fun `waitForOtherAppReconnection returns true on immediate detection`() = runtimeScenario {
        stub.systemConnected = { true }
        assertTrue(coordinator().waitForOtherAppReconnection(UUID.randomUUID()))
        assertEquals(1, stub.systemConnectedCalls.size)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::waitForOtherAppReconnection returns false after all checks()")
    fun `waitForOtherAppReconnection returns false after all checks`() = runtimeScenario {
        val result = CompletableDeferred<Boolean>()
        scenario.scope.launch { result.complete(coordinator().waitForOtherAppReconnection(UUID.randomUUID())) }
        assertTrue(scenario.awaitCondition { result.isCompleted })
        assertEquals(false, result.await())
        assertEquals(6, stub.systemConnectedCalls.size)
        assertEquals(List(5) { com.meshcoreone.android.core.connectivity.pairing.PairingCoordinator.OTHER_APP_CHECK_INTERVAL }, clock.sleeps)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::waitForOtherAppReconnection detects delayed reconnection()")
    fun `waitForOtherAppReconnection detects delayed reconnection`() = runtimeScenario {
        var calls = 0
        stub.systemConnected = { ++calls >= 3 }
        val result = CompletableDeferred<Boolean>()
        scenario.scope.launch { result.complete(coordinator().waitForOtherAppReconnection(UUID.randomUUID())) }
        assertTrue(scenario.awaitCondition { result.isCompleted })
        assertEquals(true, result.await())
        assertEquals(3, stub.systemConnectedCalls.size)
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::fetchSavedDevices returns empty array when no devices saved()")
    fun `fetchSavedDevices returns empty array when no devices saved`() = runtimeScenario {
        assertTrue(coordinator().fetchSavedDevices().isEmpty())
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::deleteDevice completes without error for non-existent device()")
    fun `deleteDevice completes without error for non-existent device`() = runtimeScenario {
        coordinator().deleteDevice(UUID.randomUUID())
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::deleteDevice clears the persisted connection when removing the last-connected radio()")
    fun `deleteDevice clears the persisted connection when removing the last-connected radio`() = runtimeScenario {
        val id = UUID.randomUUID()
        last.persist(id, RadioId(UUID.randomUUID()), "Radio")
        assertEquals(id, lastConnectedDeviceId())
        coordinator().deleteDevice(id)
        assertNull(lastConnectedDeviceId())
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::deleteDevice preserves the persisted connection when removing a different radio()")
    fun `deleteDevice preserves the persisted connection when removing a different radio`() = runtimeScenario {
        val lastConnected = UUID.randomUUID()
        last.persist(lastConnected, RadioId(UUID.randomUUID()), "Radio")
        coordinator().deleteDevice(UUID.randomUUID())
        assertEquals(lastConnected, lastConnectedDeviceId())
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::forgetDevice clears the persisted connection when removing the last-connected radio()")
    fun `forgetDevice clears the persisted connection when removing the last-connected radio`() = runtimeScenario {
        val id = UUID.randomUUID()
        last.persist(id, RadioId(UUID.randomUUID()), "Radio")
        coordinator().forgetDevice(id)
        assertNull(lastConnectedDeviceId())
    }

    @Test @OriginalCase("ConnectionManagerPairingTests::forgetDevice preserves the persisted connection when removing a different radio()")
    fun `forgetDevice preserves the persisted connection when removing a different radio`() = runtimeScenario {
        val lastConnected = UUID.randomUUID()
        last.persist(lastConnected, RadioId(UUID.randomUUID()), "Radio")
        coordinator().forgetDevice(UUID.randomUUID())
        assertEquals(lastConnected, lastConnectedDeviceId())
    }
}
