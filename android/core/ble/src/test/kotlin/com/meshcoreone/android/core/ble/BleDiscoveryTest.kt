// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineEmptyGATTHoldTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineBondSuspectRecoveryTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.meshcoreone.android.core.ble

import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BleDiscoveryTest {
    @Test fun `absent Bluetooth adapter is not scanning ready or a successful connection`() = runTest {
        val fixture = BleFixture()
        fixture.facade.available = BluetoothAvailability.Unavailable
        assertEquals(BleError.BluetoothUnavailable, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertTrue(fixture.facade.connections.isEmpty())
    }

    @Test fun `permission denial occurs before opening a physical GATT`() = runTest {
        val fixture = BleFixture()
        fixture.facade.available = BluetoothAvailability.Unauthorized
        assertEquals(BleError.BluetoothUnauthorized, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertTrue(fixture.facade.connections.isEmpty())
    }

    @Test fun `powered off Bluetooth never becomes an indefinite readiness waiter`() = runTest {
        val fixture = BleFixture()
        fixture.facade.available = BluetoothAvailability.PoweredOff
        assertEquals(BleError.BluetoothPoweredOff, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertEquals(0, fixture.clock.sleeperCount)
        fixture.facade.available = BluetoothAvailability.Ready
        fixture.transport.connect()
        assertTrue(fixture.transport.isConnected())
        fixture.transport.disconnect()
    }

    @Test fun `empty successful service discovery fails closed without an OS reconnect hold`() = runTest {
        val fixture = BleFixture()
        fixture.services = emptyList()
        assertEquals(BleError.ServiceNotFound, assertFailsWith<BleTransportException> { fixture.transport.connect(BleConnectMode.Reconnect) }.error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertFalse(fixture.transport.isConnected())
        fixture.clock.advanceBy(60.seconds)
        runCurrent()
        assertEquals(1, fixture.facade.connections.size)
    }

    @Test fun `a non NUS service and duplicate NUS services cannot be adopted`() = runTest {
        val fixture = BleFixture()
        val original = fixture.services.single()
        fixture.services = listOf(GattService(UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB"), original.characteristics))
        assertEquals(BleError.ServiceNotFound, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        fixture.services = listOf(original, original)
        assertEquals(BleError.ServiceNotFound, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertTrue(fixture.facade.connections.all { it.closeCalls.values.size == 1 })
    }

    @Test fun `missing TX or RX characteristics never create a partial connected transport`() = runTest {
        for (characteristics in listOf(emptyList(), listOf(BleFixture().tx), listOf(BleFixture().rx))) {
            val fixture = BleFixture()
            fixture.services = listOf(GattService(NusUuid.SERVICE, characteristics))
            assertEquals(BleError.CharacteristicNotFound, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
            assertFalse(fixture.transport.isConnected())
            assertEquals(1, fixture.connection.closeCalls.values.size)
        }
    }

    @Test fun `TX must actually advertise acknowledged writes`() = runTest {
        val fixture = BleFixture()
        fixture.services = listOf(GattService(NusUuid.SERVICE, listOf(
            GattCharacteristic(NusUuid.TX, setOf(GattProperty.WriteWithoutResponse), emptyList()), fixture.rx,
        )))
        assertEquals(BleError.CharacteristicPropertyMissing(GattProperty.Write),
            assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `RX indication only or read only is not NUS notification support`() = runTest {
        val fixture = BleFixture()
        fixture.services = listOf(GattService(NusUuid.SERVICE, listOf(
            fixture.tx, GattCharacteristic(NusUuid.RX, emptySet(), listOf(fixture.cccd)),
        )))
        assertEquals(BleError.CharacteristicPropertyMissing(GattProperty.Notify),
            assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertFalse(fixture.transport.isConnected())
    }

    @Test fun `missing or duplicated notification CCCD fails before negotiation or writes`() = runTest {
        for (descriptors in listOf(emptyList(), listOf(GattDescriptor(NusUuid.CCCD), GattDescriptor(NusUuid.CCCD)))) {
            val fixture = BleFixture()
            fixture.services = listOf(GattService(NusUuid.SERVICE, listOf(
                fixture.tx, GattCharacteristic(NusUuid.RX, setOf(GattProperty.Notify), descriptors),
            )))
            assertEquals(BleError.DescriptorNotFound, assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
            assertEquals(listOf(GattOperationKind.Connect, GattOperationKind.DiscoverServices), fixture.connection.operations.values.map { it.kind })
        }
    }

    @Test fun `MTU callback is actual capability not the requested number`() = runTest {
        val fixture = BleFixture()
        fixture.mtu = 23
        fixture.transport.connect()
        val request = fixture.connection.operations.values.filterIsInstance<GattOperation.Mtu>().single()
        assertEquals(517, request.requested)
        assertEquals(23, fixture.transport.diagnostics.value.actualMtu)
        assertEquals(20, fixture.transport.diagnostics.value.maximumCommandBytes)
        fixture.transport.disconnect()
    }

    @Test fun `too small negotiated MTU is a typed recovery failure`() = runTest {
        val fixture = BleFixture(BleConfiguration(minimumMtu = 247))
        fixture.mtu = 185
        assertEquals(BleError.MtuTooSmall(185, 247), assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
        assertTrue(fixture.connection.operations.values.none { it.kind == GattOperationKind.Subscribe })
    }

    @Test fun `invalid actual MTU values cannot advertise frame capacity`() = runTest {
        for (mtu in listOf(0, 22, 518)) {
            val fixture = BleFixture()
            fixture.mtu = mtu
            assertEquals(BleError.InvalidMtu(mtu), assertFailsWith<BleTransportException> { fixture.transport.connect() }.error)
            assertFalse(fixture.transport.isConnected())
            assertEquals(1, fixture.connection.closeCalls.values.size)
        }
    }

    @Test fun `MTU request without a callback is bounded by the discovery deadline`() = runTest {
        val fixture = BleFixture()
        fixture.pause = GattOperationKind.Mtu
        val result = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        fixture.clock.advanceBy(39_999.milliseconds)
        runCurrent()
        assertFalse(result.isCompleted)
        fixture.clock.advanceBy(1.milliseconds)
        runCurrent()
        assertEquals(BleError.ConnectionTimeout, result.await().error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `notification subscription without completion cannot claim connected`() = runTest {
        val fixture = BleFixture()
        fixture.pause = GattOperationKind.Subscribe
        val result = async { assertFailsWith<BleTransportException> { fixture.transport.connect(BleConnectMode.Reconnect) } }
        runCurrent()
        assertFalse(fixture.transport.isConnected())
        fixture.clock.advanceBy(15.seconds)
        runCurrent()
        assertEquals(BleError.ConnectionTimeout, result.await().error)
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `disconnect after subscription reply before adoption preserves auth classification`() = runTest {
        val fixture = BleFixture()
        fixture.pause = GattOperationKind.Subscribe
        val result = async { assertFailsWith<BleTransportException> { fixture.transport.connect() } }
        runCurrent()
        fixture.complete()
        fixture.connection.events.onDisconnected(fixture.connection, 5)
        runCurrent()
        assertEquals(BleError.AuthenticationFailed, result.await().error)
        assertEquals(BlePhase.Idle, fixture.transport.diagnostics.value.phase)
        assertEquals(1, fixture.facade.connections.size)
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }
}
