// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerPairingTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.device.RadioPresets
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectedDeviceConsumerIntegrationTest : RoomProcessTest() {
    @Test fun actualConnectedEditsReachAppStateAndRoomWithoutReplacingTheGraph() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            val port = h.container.connectionPort
            val original = assertNotNull(port.connectedDevice)
            port.updateDevice(original.copy(nodeName = "Edited radio"))
            h.settle()
            assertEquals("Edited radio", h.appState.connectedDevice?.nodeName)
            assertEquals(original.id, h.appState.connectedDevice?.id)

            port.updateAutoAddConfig(AutoAddConfig(5u, 3u))
            h.settle()
            val autoAdd = assertNotNull(store.fetchDevice(original.id))
            assertEquals(5u.toUByte(), autoAdd.autoAddConfig)
            assertEquals(3u.toUByte(), autoAdd.autoAddMaxHops)
            assertEquals(autoAdd, h.appState.connectedDevice)

            port.updateClientRepeat(true)
            h.settle()
            assertTrue(assertNotNull(store.fetchDevice(original.id)).clientRepeat)
            assertTrue(assertNotNull(h.appState.connectedDevice).clientRepeat)
            port.updatePathHashMode(2u)
            h.settle()
            assertEquals(2u.toUByte(), assertNotNull(store.fetchDevice(original.id)).pathHashMode)
            assertEquals(2u.toUByte(), h.appState.connectedDevice?.pathHashMode)

            val beforeSave = assertNotNull(port.connectedDevice)
            assertNotNull(h.container.deviceEditor.savePreRepeatSettings()).join()
            h.settle()
            val saved = assertNotNull(store.fetchDevice(original.id))
            assertTrue(saved != beforeSave)
            assertTrue(saved.hasPreRepeatSettings)
            assertEquals(beforeSave.frequency, saved.preRepeatFrequency)
            assertEquals(beforeSave.bandwidth, saved.preRepeatBandwidth)
            assertEquals(beforeSave.spreadingFactor, saved.preRepeatSpreadingFactor)
            assertEquals(beforeSave.codingRate, saved.preRepeatCodingRate)
            assertEquals(saved, h.appState.connectedDevice)

            assertNotNull(h.container.deviceEditor.clearPreRepeatSettings()).join()
            h.settle()
            val cleared = assertNotNull(store.fetchDevice(original.id))
            assertTrue(saved != cleared)
            assertFalse(cleared.hasPreRepeatSettings)
            assertNull(cleared.preRepeatFrequency)
            assertNull(cleared.preRepeatBandwidth)
            assertNull(cleared.preRepeatSpreadingFactor)
            assertNull(cleared.preRepeatCodingRate)
            assertEquals(cleared, h.appState.connectedDevice)
            assertEquals(graph, h.sessions.current)
            assertEquals(1, h.sessions.created)
        } finally {
            h.container.close()
        }
    }

    @Test fun actualPresetConsumerStampsClearsAndPreservesTheOriginalRfPredicates() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val port = h.container.connectionPort
            val device = assertNotNull(port.connectedDevice)
            val br = RadioPresets.all.single { it.id == "br" }
            val us = RadioPresets.all.single { it.id == "us-ca" }
            val brInfo = device.info(br.frequencyMHz, br.bandwidthKHz, br.spreadingFactor, br.codingRate)
            val usInfo = device.info(us.frequencyMHz, us.bandwidthKHz, us.spreadingFactor, us.codingRate)

            suspend fun verify(info: SelfInfo, explicitPreset: String?, expected: String?) {
                port.updateDevice(info, explicitPreset)
                h.settle()
                assertEquals(expected, h.appState.connectedDevice?.appliedRadioPresetID)
                assertEquals(expected, assertNotNull(store.fetchDevice(device.id)).appliedRadioPresetID)
            }

            verify(brInfo, "br", "br")
            verify(brInfo, null, "br")
            verify(usInfo, null, null)
            verify(brInfo, "br", "br")
            port.updateClientRepeat(true)
            h.settle()
            verify(device.info(869.495, 62.5, 8u, 8u), null, "br")
        } finally {
            h.container.close()
        }
    }

    @Test fun disconnectedAndForeignDtoEditsCannotReplaceTheActualConnectedDevice() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val port = h.container.connectionPort
            val original = assertNotNull(port.connectedDevice)
            port.updateDevice(DeviceDTO(radioId = original.radioId, publicKey = key(3), nodeName = "Foreign"))
            assertEquals(original, port.connectedDevice)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            val persisted = assertNotNull(store.fetchDevice(original.id))
            port.updateDevice(original.copy(nodeName = "Disconnected edit"))
            port.updateAutoAddConfig(AutoAddConfig(5u, 3u))
            port.updateClientRepeat(true)
            port.updatePathHashMode(2u)
            h.settle()
            assertNull(port.connectedDevice)
            assertEquals(persisted, store.fetchDevice(original.id))
        } finally {
            h.container.close()
        }
    }

    private fun DeviceDTO.info(frequency: Double, bandwidth: Double, sf: UByte, cr: UByte) = SelfInfo(
        advertisementType = 0u, txPower = 20, maxTxPower = 20, publicKey = publicKey,
        latitude = 0.0, longitude = 0.0, multiAcks = 2u, advertisementLocationPolicy = 0u,
        telemetryModeEnvironment = 0u, telemetryModeLocation = 0u, telemetryModeBase = 2u,
        manualAddContacts = false, radioFrequency = frequency, radioBandwidth = bandwidth,
        radioSpreadingFactor = sf, radioCodingRate = cr, name = "TestNode",
    )
}
