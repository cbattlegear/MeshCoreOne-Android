// PortedFrom: MC1Services/Sources/MC1Services/Transport/BLEStateMachine+CBDelegate.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BLEStateMachineRestorationAndTeardownTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.meshcoreone.android.core.ble

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BleStreamTest {
    @Test fun `notifications queue before subscription with exact equal empty and high bit values`() = runTest {
        val fixture = BleFixture()
        val flow = fixture.transport.receivedData()
        fixture.transport.connect()
        val expected = listOf(Bytes.of(0x80, 0xff), Bytes.of(0x80, 0xff), Bytes.EMPTY, Bytes.of(0))
        expected.forEach { fixture.connection.notify(fixture.rx, it) }
        fixture.transport.disconnect()
        assertEquals(expected, flow.toList())
    }

    @Test fun `bursts beyond the source 512 queue never conflate or silently drop packets`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val expected = List(2_048) { Bytes.of(it and 0xff, (it ushr 8) and 0xff) }
        expected.forEach { fixture.connection.notify(fixture.rx, it) }
        fixture.transport.disconnect()
        assertEquals(expected, fixture.transport.receivedData().toList())
        assertEquals(0L, fixture.transport.diagnostics.value.rejectedCallbacks)
    }

    @Test fun `notifications arriving during CCCD completion are retained before connect adoption`() = runTest {
        val fixture = BleFixture()
        val incoming = fixture.transport.receivedData()
        fixture.pause = GattOperationKind.Subscribe
        val connecting = async { fixture.transport.connect() }
        runCurrent()
        assertFalse(fixture.transport.isConnected())
        fixture.connection.notify(fixture.rx, Bytes.of(0x80, 0x01))
        fixture.complete()
        runCurrent()
        connecting.await()
        fixture.transport.disconnect()
        assertEquals(listOf(Bytes.of(0x80, 0x01)), incoming.toList())
    }

    @Test fun `notification payloads have immutable value semantics`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val mutable = byteArrayOf(0x80.toByte(), 0xff.toByte())
        val packet = Bytes(mutable)
        fixture.connection.notify(fixture.rx, packet)
        mutable.fill(0)
        packet.toByteArray().fill(0)
        fixture.transport.disconnect()
        assertEquals(listOf(Bytes.of(0x80, 0xff)), fixture.transport.receivedData().toList())
    }

    @Test fun `wrong characteristic identity cannot enter the ingestion drain`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val sameUuid = GattCharacteristic(NusUuid.RX, setOf(GattProperty.Notify), listOf(fixture.cccd))
        fixture.connection.notify(sameUuid, Bytes.of(1))
        fixture.connection.notify(fixture.tx, Bytes.of(2))
        fixture.connection.notify(fixture.rx, Bytes.of(3))
        fixture.transport.disconnect()
        assertEquals(listOf(Bytes.of(3)), fixture.transport.receivedData().toList())
        assertEquals(2L, fixture.transport.diagnostics.value.rejectedCallbacks)
    }

    @Test fun `old flow terminates and never consumes a new connection generation`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val oldFlow = fixture.transport.receivedData()
        val oldGatt = fixture.connection
        oldGatt.notify(fixture.rx, Bytes.of(1))
        fixture.transport.disconnect()
        fixture.transport.connect()
        val newFlow = fixture.transport.receivedData()
        assertNotSame(oldFlow, newFlow)
        oldGatt.notify(fixture.rx, Bytes.of(2))
        fixture.connection.notify(fixture.rx, Bytes.of(3))
        fixture.transport.disconnect()
        assertEquals(listOf(Bytes.of(1)), oldFlow.toList())
        assertEquals(listOf(Bytes.of(3)), newFlow.toList())
    }

    @Test fun `late notification after receiver close cannot resurrect a connection`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val flow = fixture.transport.receivedData()
        val gatt = fixture.connection
        fixture.transport.disconnect()
        gatt.notify(fixture.rx, Bytes.of(0x80))
        assertTrue(flow.toList().isEmpty())
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, gatt.closeCalls.values.size)
    }

    @Test fun `one ingestion drain is enforced rather than splitting packets among collectors`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val flow = fixture.transport.receivedData()
        val first = backgroundScope.launch { flow.collect() }
        runCurrent()
        assertEquals(BleError.MultipleReceivers, assertFailsWith<BleTransportException> { flow.take(1).toList() }.error)
        assertTrue(fixture.transport.isConnected())
        first.cancelAndJoin()
        fixture.connection.notify(fixture.rx, Bytes.of(4))
        assertEquals(listOf(Bytes.of(4)), flow.take(1).toList())
        fixture.transport.disconnect()
    }

    @Test fun `collector cancellation does not close a process owned physical link`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val receiver = backgroundScope.launch { fixture.transport.receivedData().collect() }
        runCurrent()
        receiver.cancelAndJoin()
        assertTrue(fixture.transport.isConnected())
        assertEquals(0, fixture.connection.closeCalls.values.size)
        fixture.connection.notify(fixture.rx, Bytes.of(5))
        fixture.transport.disconnect()
        assertEquals(listOf(Bytes.of(5)), fixture.transport.receivedData().toList())
    }

    @Test fun `unexpected failure drains complete packets then surfaces its typed cause`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        fixture.connection.notify(fixture.rx, Bytes.of(6))
        fixture.connection.notify(fixture.rx, Bytes.of(6))
        fixture.connection.events.onDisconnected(fixture.connection, 133)
        val packets = mutableListOf<Bytes>()
        val failure = assertFailsWith<BleTransportException> { fixture.transport.receivedData().collect { packets.add(it) } }
        assertEquals(listOf(Bytes.of(6), Bytes.of(6)), packets)
        assertEquals(BleError.ConnectionFailed("gatt.status.133"), failure.error)
        assertEquals(133, failure.status)
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `wrong GATT same address disconnect does not terminate the active receiver`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val unrelated = FakeGattConnection(fixture.connection.events)
        unrelated.events.onDisconnected(unrelated, 133)
        unrelated.notify(fixture.rx, Bytes.of(7))
        assertTrue(fixture.transport.isConnected())
        fixture.connection.notify(fixture.rx, Bytes.of(8))
        fixture.transport.disconnect()
        assertEquals(listOf(Bytes.of(8)), fixture.transport.receivedData().toList())
    }

    @Test fun `old disconnect after rapid reconnect is rejected despite matching device handle`() = runTest {
        val fixture = BleFixture()
        fixture.transport.connect()
        val old = fixture.connection
        fixture.transport.disconnect()
        fixture.transport.connect()
        val generation = fixture.transport.diagnostics.value.generation
        old.events.onDisconnected(old, 5)
        assertTrue(fixture.transport.isConnected())
        assertEquals(generation, fixture.transport.diagnostics.value.generation)
        assertEquals(1, old.closeCalls.values.size)
        fixture.transport.disconnect()
    }
}
