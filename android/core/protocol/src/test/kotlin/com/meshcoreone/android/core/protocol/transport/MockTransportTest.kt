// PortedFrom: MeshCore/Sources/MeshCore/Transport/MockTransport.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/MeshCoreSessionGetChannelsTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.transport

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransportException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MockTransportTest {
    @Test
    fun `Mock begins disconnected with an empty history and opt-in capabilities`() = runTest {
        val transport = MockTransport()
        assertFalse(transport.isConnected())
        assertTrue(transport.sentData.isEmpty())
        assertFalse(transport.supportsWriteWithoutResponse())
        assertFalse(transport.supportsPipelinedReads())
    }

    @Test
    fun `Capability setter controls both write commands and pipelined reads`() = runTest {
        val transport = MockTransport()
        for (supported in listOf(true, false, true)) {
            transport.setSupportsWriteWithoutResponse(supported)
            assertEquals(supported, transport.supportsWriteWithoutResponse())
            assertEquals(supported, transport.supportsPipelinedReads())
        }
    }

    @Test
    fun `Both send paths require a connection and append exact immutable payloads`() = runTest {
        val transport = MockTransport()
        assertFailsWith<MockTransportException.NotConnected> { transport.send(Bytes.EMPTY) }
        assertFailsWith<MockTransportException.NotConnected> { transport.sendWithoutResponse(Bytes.of(1)) }
        transport.connect()
        transport.connect()
        val array = byteArrayOf(0x80.toByte(), 0xff.toByte())
        val payload = Bytes(array)
        transport.send(payload)
        array.fill(0)
        val snapshot = transport.sentData
        transport.sendWithoutResponse(Bytes.EMPTY)
        assertEquals(listOf(Bytes.fromHex("80ff")), snapshot)
        assertEquals(listOf(Bytes.fromHex("80ff"), Bytes.EMPTY), transport.sentData)
        transport.clearSentData()
        assertTrue(transport.sentData.isEmpty())
        assertEquals(listOf(Bytes.fromHex("80ff")), snapshot)
    }

    @Test
    fun `Failure threshold is based on successful sends and remains armed`() = runTest {
        val transport = MockTransport()
        transport.connect()
        transport.failSends(fromSendIndex = 2)
        transport.send(Bytes.of(1))
        repeat(3) {
            val failure = assertFailsWith<MockTransportException.SendFailed> { transport.send(Bytes.of(2)) }
            assertEquals("simulated send failure", failure.reason)
            assertEquals(listOf(Bytes.of(1)), transport.sentData)
        }
        transport.clearSentData()
        transport.send(Bytes.of(3))
        assertFailsWith<MockTransportException.SendFailed> { transport.sendWithoutResponse(Bytes.of(4)) }
        assertEquals(listOf(Bytes.of(3)), transport.sentData)
    }

    @Test
    fun `Failure threshold preserves source signed Int behavior and large indices`() = runTest {
        for (index in listOf(-1L, 0L, 1L)) {
            val transport = MockTransport()
            transport.connect()
            transport.failSends(index)
            assertFailsWith<MockTransportException.SendFailed> { transport.send(Bytes.EMPTY) }
        }
        val transport = MockTransport()
        transport.connect()
        transport.failSends(Long.MAX_VALUE)
        repeat(3) { transport.send(Bytes.of(it)) }
        assertEquals(3, transport.sentData.size)
    }

    @Test
    fun `Raw input can be buffered before connect and retains repeated empty and unknown packets`() = runTest {
        val transport = MockTransport()
        val stream = transport.receivedData()
        assertSame(stream, transport.receivedData())
        val packets = listOf(Bytes.EMPTY, Bytes.fromHex("ffff80"), Bytes.fromHex("ffff80"))
        for (packet in packets) transport.simulateReceive(packet)
        transport.connect()
        transport.disconnect()
        assertEquals(packets, stream.toList())
    }

    @Test
    fun `OK and error simulation use pinned codes optional fields and UInt32 little endian`() = runTest {
        val transport = MockTransport()
        transport.simulateOK()
        transport.simulateOK(0u)
        transport.simulateOK(0x89abcdefu)
        transport.simulateOK(UInt.MAX_VALUE)
        transport.simulateError(0xffu)
        transport.disconnect()
        assertEquals(
            listOf(
                Bytes.fromHex("00"), Bytes.fromHex("0000000000"),
                Bytes.fromHex("00efcdab89"), Bytes.fromHex("00ffffffff"), Bytes.fromHex("01ff"),
            ),
            transport.receivedData().toList(),
        )
    }

    @Test
    fun `Disconnect is idempotent finishes existing and late collectors and rejects closed injection`() = runTest {
        val transport = MockTransport()
        transport.connect()
        val received = async { transport.receivedData().toList() }
        transport.simulateReceive(Bytes.of(1))
        transport.disconnect()
        transport.disconnect()
        assertEquals(listOf(Bytes.of(1)), received.await())
        assertTrue(transport.receivedData().toList().isEmpty())
        assertFalse(transport.isConnected())
        assertFailsWith<MockTransportException.NotConnected> { transport.send(Bytes.of(1)) }
        assertFailsWith<MockTransportException.NotConnected> { transport.simulateReceive(Bytes.of(2)) }
    }

    @Test
    fun `Reconnect uses a fresh stream without leaking old queued packets or losing send history`() = runTest {
        val transport = MockTransport()
        transport.connect()
        val old = transport.receivedData()
        transport.send(Bytes.of(1))
        transport.simulateReceive(Bytes.of(2))
        transport.disconnect()
        transport.connect()
        val current = transport.receivedData()
        assertNotSame(old, current)
        transport.simulateReceive(Bytes.of(3))
        transport.disconnect()
        assertEquals(listOf(Bytes.of(2)), old.toList())
        assertEquals(listOf(Bytes.of(3)), current.toList())
        assertEquals(listOf(Bytes.of(1)), transport.sentData)
    }

    @Test
    fun `Collectors share a single ingestion queue rather than broadcasting or conflating ACKs`() = runTest {
        val transport = MockTransport()
        val stream = transport.receivedData()
        val first = async { stream.first() }
        val second = async { stream.first() }
        runCurrent()
        transport.simulateReceive(Bytes.of(0x82))
        transport.simulateReceive(Bytes.of(0x82))
        assertEquals(Bytes.of(0x82), first.await())
        assertEquals(Bytes.of(0x82), second.await())
        transport.disconnect()
        assertTrue(stream.toList().isEmpty())
    }

    @Test
    fun `Cancelled collection does not close the connection or discard future input`() = runTest {
        val transport = MockTransport()
        transport.connect()
        val consumer = async { transport.receivedData().toList() }
        runCurrent()
        consumer.cancel()
        assertFailsWith<CancellationException> { consumer.await() }
        assertTrue(transport.isConnected())
        transport.simulateReceive(Bytes.of(0xfe))
        transport.disconnect()
        assertEquals(listOf(Bytes.of(0xfe)), transport.receivedData().toList())
    }

    @Test
    fun `Precancelled commands do not mutate history state or input`() = runTest {
        val transport = MockTransport()
        transport.connect()
        val command = async(start = CoroutineStart.LAZY) { transport.send(Bytes.of(1)) }
        command.cancel()
        assertFailsWith<CancellationException> { command.await() }
        assertTrue(transport.sentData.isEmpty())
        assertTrue(transport.isConnected())
        transport.disconnect()
    }
}
