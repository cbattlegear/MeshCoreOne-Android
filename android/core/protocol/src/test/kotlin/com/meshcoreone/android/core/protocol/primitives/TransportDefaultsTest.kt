// PortedFrom: MeshCore/Tests/MeshCoreTests/Transport/MeshTransportDefaultsTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.primitives

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TransportDefaultsTest {
    private class TestTransport : MeshTransport {
        var connected = false
        var writeCommands = false
        var sendFailure: Throwable? = null
        val sent = mutableListOf<Bytes>()
        val incoming = Channel<Bytes>(Channel.UNLIMITED)

        override suspend fun connect() {
            connected = true
        }

        override suspend fun disconnect() {
            connected = false
            incoming.close()
        }

        override suspend fun send(data: Bytes) {
            if (!connected) throw MeshCoreException.NotConnected()
            sendFailure?.let { throw it }
            sent += data
        }

        override suspend fun supportsWriteWithoutResponse(): Boolean = writeCommands
        override suspend fun receivedData(): Flow<Bytes> = incoming.receiveAsFlow()
        override suspend fun isConnected(): Boolean = connected
    }

    @Test
    fun `Default sendWithoutResponse forwards to send`() = runTest {
        val transport = TestTransport()
        transport.connect()
        val payload = Bytes.of(0xab, 0xcd)
        transport.sendWithoutResponse(payload)
        assertEquals(listOf(payload), transport.sent)
    }

    @Test
    fun `Default supportsWriteWithoutResponse is false`() = runTest {
        val transport = object : MeshTransport by TestTransport() {
            override suspend fun supportsWriteWithoutResponse(): Boolean =
                super<MeshTransport>.supportsWriteWithoutResponse()
        }
        assertFalse(transport.supportsWriteWithoutResponse())
    }

    @Test
    fun `Default supportsPipelinedReads mirrors supportsWriteWithoutResponse`() = runTest {
        val transport = TestTransport()
        assertFalse(transport.supportsPipelinedReads())
        transport.writeCommands = true
        assertTrue(transport.supportsPipelinedReads())
    }

    @Test
    fun `Disconnect ends raw ingestion without losing queued chunks`() = runTest {
        val transport = TestTransport()
        transport.connect()
        val values = async { transport.receivedData().toList() }
        transport.incoming.send(Bytes.of(1))
        transport.incoming.send(Bytes.of(2, 3))
        transport.disconnect()
        transport.disconnect()
        assertEquals(listOf(Bytes.of(1), Bytes.of(2, 3)), values.await())
        assertFalse(transport.isConnected())
        assertFailsWith<MeshCoreException.NotConnected> { transport.sendWithoutResponse(Bytes.of(4)) }
    }

    @Test
    fun `Default forwarding propagates cancellation and transport failures`() = runTest {
        val transport = TestTransport()
        transport.connect()
        val failure = CancellationException("fixture cancellation")
        transport.sendFailure = failure
        assertSame(failure, assertFailsWith<CancellationException> {
            transport.sendWithoutResponse(Bytes.of(1))
        })
        assertTrue(transport.sent.isEmpty())
    }
}
