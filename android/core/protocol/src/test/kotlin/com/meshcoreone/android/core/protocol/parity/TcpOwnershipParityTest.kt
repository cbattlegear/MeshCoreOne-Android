// AndroidOnly: WP-109 Real TCP consumer proof for intentional wildcard quarantine and retained physical ownership.
package com.meshcoreone.android.core.protocol.parity

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SessionCorrelationException
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.tcp.WiFiTransport
import java.io.FilterOutputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import kotlin.test.*

private class OwnershipClock : SessionClock {
    private val lock = Any()
    private var instant = Duration.ZERO
    private val sleepers = mutableListOf<Pair<Duration, CancellableContinuation<Unit>>>()
    val registered = Channel<Duration>(Channel.UNLIMITED)
    override val now: Duration get() = synchronized(lock) { instant }
    override val wallClock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    override suspend fun sleepFor(duration: Duration) {
        currentCoroutineContext().ensureActive()
        suspendCancellableCoroutine<Unit> { continuation ->
            val entry = synchronized(lock) { (instant + duration) to continuation }
            synchronized(lock) { if (continuation.isActive) sleepers += entry }
            continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(entry) } }
            registered.trySend(entry.first).getOrThrow()
        }
    }
    fun advance(duration: Duration) {
        val due = synchronized(lock) {
            instant += duration
            sleepers.filter { it.first <= instant }.also { sleepers.removeAll(it.toSet()) }
        }
        due.forEach { it.second.resume(Unit) }
    }
}

private class SendTrackingTransport(private val delegate: MeshTransport) : MeshTransport by delegate {
    val completedSends = AtomicInteger()
    override suspend fun send(data: Bytes) {
        delegate.send(data)
        completedSends.incrementAndGet()
    }
    override suspend fun sendWithoutResponse(data: Bytes) {
        delegate.sendWithoutResponse(data)
        completedSends.incrementAndGet()
    }
}

private class OwnershipSocket : Socket() {
    val closes = AtomicInteger()
    override fun close() { closes.incrementAndGet(); super.close() }
    override fun getOutputStream(): OutputStream = object : FilterOutputStream(super.getOutputStream()) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun flush() = out.flush()
    }
}

private class OwnershipPeer : AutoCloseable {
    private val server = ServerSocket(0, 2, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))).apply { soTimeout = 5000 }
    val port: Int get() = server.localPort
    private var socket: Socket? = null
    suspend fun accept() = runInterruptible(Dispatchers.IO) {
        socket = server.accept().apply { soTimeout = 5000; tcpNoDelay = true }
    }
    suspend fun read(): Bytes = runInterruptible(Dispatchers.IO) {
        val input = checkNotNull(socket).getInputStream()
        assertEquals(0x3c, input.read())
        val low = input.read(); val high = input.read()
        assertTrue(low >= 0 && high >= 0)
        val length = low or (high shl 8)
        Bytes(input.readNBytes(length).also { assertEquals(length, it.size) })
    }
    suspend fun send(packet: Bytes) = runInterruptible(Dispatchers.IO) {
        checkNotNull(socket).getOutputStream().apply {
            write(byteArrayOf(0x3e, packet.size.toByte(), (packet.size ushr 8).toByte()) + packet.toByteArray())
            flush()
        }
    }
    suspend fun closed() = runInterruptible(Dispatchers.IO) {
        assertEquals(-1, checkNotNull(socket).getInputStream().read())
    }
    override fun close() { socket?.close(); server.close() }
}

private fun selfInfo(): Bytes = Bytes.fromHex("0501141e") + Bytes(ByteArray(32) { 0x11 }) +
    Bytes(ByteArray(12)) + Bytes.fromHex("38f60d0090d003000b08") + Bytes.utf8("Peer")
private fun battery(value: Int): Bytes = Bytes.of(12, value and 255, value ushr 8)
private suspend fun CoroutineScope.handshake(session: MeshCoreSession, peer: OwnershipPeer) {
    val start = async { session.start() }
    peer.accept()
    assertEquals(Bytes.fromHex("01032020202020204d436f7265"), peer.read())
    peer.send(selfInfo())
    start.await()
}
private fun session(transport: MeshTransport, scope: CoroutineScope, clock: SessionClock) = MeshCoreSession(
    transport, SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"), clock,
    scope.coroutineContext, onDiagnostic = {},
)
private inline fun <reified T : Throwable> correlationCause(failure: Throwable): T = assertNotNull(
    generateSequence(failure) { it.cause }.take(16).filterIsInstance<T>().firstOrNull(),
    "The exact canonical correlation type must survive coroutine exception stack recovery",
)

@Timeout(15)
class TcpOwnershipParityTest {
    @Test
    fun `unanswered arbitrary TCP matcher quarantines typed successor and fresh physical connection clears it`() = runBlocking {
        supervisorScope {
            OwnershipPeer().use { peer ->
                val sockets = mutableListOf<OwnershipSocket>()
                val physicalTransport = WiFiTransport(socketFactory = { OwnershipSocket().also { sockets += it } })
                physicalTransport.setConnectionInfo("127.0.0.1", peer.port)
                val transport = SendTrackingTransport(physicalTransport)
                val clock = OwnershipClock()
                val first = session(transport, this, clock)
                try {
                    handshake(first, peer)
                    val sendsBeforeRequest = transport.completedSends.get()
                    val request = async {
                        first.sendAndWait(PacketBuilder.getBattery(), timeout = 0.05) {
                            (it as? MeshEvent.Battery)?.info
                        }
                    }
                    assertEquals(Bytes.of(20), peer.read())
                    // The peer can read before send returns; wait for its completion to isolate reply timeout.
                    while (transport.completedSends.get() == sendsBeforeRequest) yield()
                    while (clock.registered.receive() != 50.milliseconds) Unit
                    clock.advance(50.milliseconds)
                    assertFailsWith<MeshCoreException.Timeout> { request.await() }
                    val failure = assertFailsWith<MeshCoreException.ConnectionLost> { first.getBattery() }
                    assertEquals("*", correlationCause<SessionCorrelationException.UnresolvedReply>(failure).responseFamily)
                    val events = first.events()
                    peer.send(battery(1111))
                    assertEquals(1111L, assertIs<MeshEvent.Battery>(events.first { it is MeshEvent.Battery }).info.level)
                } finally {
                    withContext(NonCancellable) { first.stop() }
                }
                peer.closed()
                assertEquals(1, sockets.single().closes.get())
                val fresh = session(transport, this, OwnershipClock())
                try {
                    handshake(fresh, peer)
                    val read = async { fresh.getBattery() }
                    assertEquals(Bytes.of(20), peer.read())
                    peer.send(battery(2222))
                    assertEquals(2222L, read.await().level)
                } finally {
                    withContext(NonCancellable) { fresh.stop() }
                }
                assertEquals(listOf(1, 1), sockets.map { it.closes.get() })
                assertFalse(physicalTransport.isConnected())
            }
        }
    }

    @Test
    fun `retained TCP link blocks both logical reuse and another session until exact old owner closes`() = runBlocking {
        supervisorScope {
            OwnershipPeer().use { peer ->
                val sockets = mutableListOf<OwnershipSocket>()
                val transport = WiFiTransport(socketFactory = { OwnershipSocket().also { sockets += it } })
                transport.setConnectionInfo("127.0.0.1", peer.port)
                val first = session(transport, this, OwnershipClock())
                val other = session(transport, this, OwnershipClock())
                try {
                    handshake(first, peer)
                    correlationCause<SessionCorrelationException.ConcurrentTransportOwner>(
                        assertFailsWith<MeshCoreException.ConnectionLost> { other.start() },
                    )
                    assertTrue(transport.isConnected(), "A competing live session cannot close the actual owner")
                    assertEquals(0, sockets.single().closes.get())
                    first.stop(disconnectTransport = false)
                    assertTrue(transport.isConnected())
                    assertEquals(0, sockets.single().closes.get())
                    correlationCause<SessionCorrelationException.RetainedTransport>(
                        assertFailsWith<MeshCoreException.ConnectionLost> { first.start() },
                    )
                    correlationCause<SessionCorrelationException.RetainedTransport>(
                        assertFailsWith<MeshCoreException.ConnectionLost> { other.start() },
                    )
                    assertTrue(transport.isConnected(), "An unsuccessful competing session cannot close the retained owner")
                    assertEquals(1, sockets.size)
                } finally {
                    withContext(NonCancellable) { first.stop(); other.stop() }
                }
                assertFalse(transport.isConnected())
                assertEquals(1, sockets.single().closes.get())
                peer.closed()
            }
        }
    }

    @Test
    fun `cancelled owning job still awaits actual TCP close and releases a fresh session claim`() = runBlocking {
        supervisorScope {
            OwnershipPeer().use { peer ->
                val socket = OwnershipSocket()
                val transport = WiFiTransport(socketFactory = { socket })
                transport.setConnectionInfo("127.0.0.1", peer.port)
                val owner = Job()
                val ownedScope = CoroutineScope(coroutineContext + owner)
                val running = session(transport, ownedScope, OwnershipClock())
                try {
                    handshake(running, peer)
                    owner.cancel()
                    running.stop()
                    owner.join()
                    assertFalse(transport.isConnected())
                    assertEquals(1, socket.closes.get())
                    peer.closed()
                    running.stop()
                    assertEquals(1, socket.closes.get())
                } finally {
                    withContext(NonCancellable) { running.stop(); owner.cancelAndJoin() }
                }
            }
        }
    }
}
