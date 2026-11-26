// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEPhaseTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.ble

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BleValuesTest {
    @Test fun `idle phase has correct name`() { assertEquals("idle", BlePhase.Idle.sourceName) }
    @Test fun `idle is not part of discovery chain`() { assertFalse(BlePhase.Idle.isDiscoveryChain) }
    @Test fun `idle phase is not active`() { assertFalse(BlePhase.Idle.isActive) }
    @Test fun `idle phase has no peripheral`() { assertNull(BleFixture().transport.diagnostics.value.handle) }
    @Test fun `idle phase has no deviceID`() { assertNull(BleFixture().transport.diagnostics.value.handle) }

    @Test fun `initializes in idle phase`() { assertEquals(BlePhase.Idle, BleFixture().transport.diagnostics.value.phase) }
    @Test fun `isConnected returns false when idle`() = runTest { assertFalse(BleFixture().transport.isConnected()) }
    @Test fun `connectedDeviceID returns nil when idle`() { assertNull(BleFixture().transport.diagnostics.value.handle) }
    @Test fun `isAutoReconnecting returns false when idle`() {
        assertFalse(BleFixture().transport.diagnostics.value.phase in setOf(BlePhase.AutoReconnecting, BlePhase.RestoringState))
    }
    @Test fun `linkDiagnostics reports the idle phase when idle`() {
        val diagnostics = BleFixture().transport.diagnostics.value
        assertEquals(BlePhase.Idle, diagnostics.phase)
        assertNull(diagnostics.actualMtu)
        assertFalse(diagnostics.firmwareVerified)
    }
    @Test fun `disconnect returns immediately when idle`() = runTest {
        val fixture = BleFixture()
        fixture.transport.disconnect()
        assertEquals(BlePhase.Idle, fixture.transport.diagnostics.value.phase)
        assertTrue(fixture.facade.connections.isEmpty())
    }
    @Test fun `send throws notConnected when idle`() = runTest {
        val failure = assertFailsWith<BleTransportException> { BleFixture().transport.send(Bytes.of(1, 2, 3)) }
        assertEquals(BleError.NotConnected, failure.error)
    }
    @Test fun `disconnect is idempotent`() = runTest {
        val fixture = BleFixture()
        repeat(3) { fixture.transport.disconnect() }
        assertEquals(BlePhase.Idle, fixture.transport.diagnostics.value.phase)
        assertTrue(fixture.facade.connections.isEmpty())
    }
    @Test fun `connection generation starts at zero`() { assertEquals(0L, BleFixture().transport.diagnostics.value.generation) }

    @Test fun `NUS UUIDs preserve the independent pinned literals`() {
        assertEquals(UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"), NusUuid.SERVICE)
        assertEquals(UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E"), NusUuid.TX)
        assertEquals(UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"), NusUuid.RX)
        assertEquals(UUID.fromString("00002902-0000-1000-8000-00805F9B34FB"), NusUuid.CCCD)
    }
    @Test fun `source phase names and discovery projection are explicit`() {
        assertEquals(
            listOf("idle", "waitingForBluetooth", "connecting", "discoveringServices", "discoveringCharacteristics",
                "subscribingToNotifications", "discoveryComplete", "connected", "autoReconnecting", "restoringState", "disconnecting"),
            BlePhase.entries.take(11).map { it.sourceName },
        )
        for (phase in listOf(BlePhase.DiscoveringServices, BlePhase.DiscoveringCharacteristics, BlePhase.SubscribingToNotifications)) {
            assertTrue(phase.isDiscoveryChain)
            assertTrue(phase.isActive)
        }
    }
    @Test fun `source timeout defaults remain ten forty fifteen five seconds`() {
        assertEquals(BleTimeouts(10.seconds, 40.seconds, 15.seconds, 5.seconds), BleTimeouts())
    }
    @Test fun `invalid deadlines and MTU configuration are rejected`() {
        assertFailsWith<IllegalArgumentException> { BleTimeouts(write = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BleTimeouts(connection = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { BleConfiguration(requestedMtu = 518) }
        assertFailsWith<IllegalArgumentException> { BleConfiguration(minimumMtu = 22) }
        assertFailsWith<IllegalArgumentException> { BleConfiguration(writePacing = (-1).seconds) }
    }
    @Test fun `Bluetooth addresses are redacted connection handles not UUID identities`() {
        val handle = BleDeviceHandle("aa:bb:cc:dd:ee:ff")
        assertEquals("AA:BB:CC:DD:EE:FF", handle.address)
        assertEquals(handle, BleDeviceHandle("AA:BB:CC:DD:EE:FF"))
        assertEquals(handle.hashCode(), BleDeviceHandle("AA:BB:CC:DD:EE:FF").hashCode())
        assertEquals("BleDeviceHandle(redacted)", handle.toString())
        assertEquals(BleError.DeviceNotFound, assertFailsWith<BleTransportException> { BleDeviceHandle("not-a-device") }.error)
    }
    @Test fun `firmware evidence does not follow from MTU or characteristic properties`() {
        assertFailsWith<IllegalArgumentException> { FirmwareFrameCapabilities(513, evidence = "test") }
        assertFailsWith<IllegalArgumentException> { FirmwareFrameCapabilities(20, evidence = "") }
        assertFailsWith<IllegalArgumentException> { FirmwareFrameCapabilities(20, pipelinedReads = true, evidence = "test") }
        assertFalse(FirmwareFrameCapabilities(20, evidence = "test").writeWithoutResponse)
    }
    @Test fun `typed recovery distinguishes power permission pairing and firmware`() {
        assertEquals(BleRecovery.EnableBluetooth, BleTransportException(BleError.BluetoothPoweredOff).recovery)
        assertEquals(BleRecovery.GrantBluetoothConnect, BleTransportException(BleError.BluetoothUnauthorized).recovery)
        assertEquals(BleRecovery.PairInSystem, BleTransportException(BleError.AuthenticationFailed).recovery)
        assertEquals(BleRecovery.VerifyFirmwareCapabilities,
            BleTransportException(BleError.FirmwareCapabilityUnverified(21, 20)).recovery)
    }
}
