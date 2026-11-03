// AndroidOnly: WP-107 Direct/channel messaging provenance on the real per-generation serializer.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class MessageWriteDispatchTest {
    private enum class Kind(val command: Int) { DIRECT(0x02), CHANNEL(0x03) }

    private suspend fun send(role: MessagingSessionOps, kind: Kind, dispatch: MessageWriteDispatch) {
        when (kind) {
            Kind.DIRECT -> {
                role.sendMessage(filled(0x44, 32), "hello", testEpoch, 0x80u, dispatch)
                Unit
            }
            Kind.CHANNEL -> role.sendChannelMessage(7u, "hello", testEpoch, dispatch)
        }
    }

    private suspend fun reply(f: SessionFixture, kind: Kind) {
        when (kind) {
            Kind.DIRECT -> f.transport.receive(sentPacket(hex("11223344"), 3456))
            Kind.CHANNEL -> f.transport.ok()
        }
    }

    @TestFactory
    fun messagingCases() = Kind.entries.flatMap { kind -> listOf(
        nativeCase("$kind pre-cancelled call proves unsent before creating its owned serializer task") {
            val f = fixture(); start(f)
            val dispatch = MessageWriteDispatch()
            val observed = CompletableDeferred<ChannelWriteCancellationDisposition>()
            val caller = backgroundScope.launch(start = CoroutineStart.ATOMIC) {
                try { send(f.session, kind, dispatch) }
                catch (cancelled: CancellationException) {
                    observed.complete(dispatch.cancellationDisposition)
                    throw cancelled
                }
            }
            caller.cancel(CancellationException("before task")); runCurrent()
            assertTrue(caller.isCancelled)
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, observed.await())
            assertEquals(1, f.transport.sent.size)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        nativeCase("$kind real mutex-queued cancellation never dispatches when the prior command releases") {
            val f = fixture(); start(f)
            val prior = checkedRequest { f.session.resetPath(filled(0x11, 32)) }; runCurrent()
            val dispatch = MessageWriteDispatch()
            val observed = CompletableDeferred<ChannelWriteCancellationDisposition>()
            val caller = backgroundScope.launch {
                try { send(f.session, kind, dispatch) }
                catch (cancelled: CancellationException) {
                    observed.complete(dispatch.cancellationDisposition)
                    throw cancelled
                }
            }
            runCurrent()
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
            assertEquals(2, f.transport.sent.size)
            caller.cancel(); runCurrent()
            assertTrue(caller.isCancelled)
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, observed.await())
            assertEquals(2, f.transport.sent.size)
            f.transport.ok(); runCurrent(); prior.await()
            assertEquals(2, f.transport.sent.size, "No cancelled messaging task can dispatch later")
            val retryDispatch = MessageWriteDispatch()
            val retry = checkedRequest { send(f.session, kind, retryDispatch) }; runCurrent()
            assertEquals(3, f.transport.sent.size)
            assertEquals(kind.command, f.transport.sent.last()[0].toInt())
            reply(f, kind); runCurrent(); retry.await()
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, retryDispatch.cancellationDisposition)
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("$kind cancellation after dispatch preserves original exception and drains its own response") {
            val f = fixture(); start(f)
            val dispatch = MessageWriteDispatch()
            val observed = CompletableDeferred<CancellationException>()
            val caller = backgroundScope.launch {
                try { send(f.session, kind, dispatch) }
                catch (cancelled: CancellationException) {
                    observed.complete(cancelled)
                    throw cancelled
                }
            }
            runCurrent()
            assertEquals(kind.command, f.transport.sent.last()[0].toInt())
            caller.cancel(CancellationException("actual sender cancellation")); runCurrent()
            assertEquals("actual sender cancellation", observed.await().message)
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            val successor = checkedRequest { send(f.session, kind, MessageWriteDispatch()) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            reply(f, kind); runCurrent()
            assertEquals(3, f.transport.sent.size)
            assertFalse(successor.isCompleted, "An old message response is not a new command's response")
            reply(f, kind); runCurrent(); successor.await()
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("$kind cancellation at cooperative transport entry remains possibly written") {
            val f = fixture(); start(f)
            val gate = CompletableDeferred<Unit>()
            f.transport.beforeSend = { if (it[0].toInt() == kind.command) gate.await() }
            val dispatch = MessageWriteDispatch()
            val caller = checkedRequest { send(f.session, kind, dispatch) }; runCurrent()
            assertEquals(1, f.transport.sent.size)
            caller.cancel(); runCurrent()
            assertFailsWith<CancellationException> { caller.await() }
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            gate.complete(Unit); runCurrent()
            assertEquals(1, f.transport.sent.size)
            assertFailsWith<MeshCoreException.ConnectionLost> { send(f.session, kind, MessageWriteDispatch()) }
            f.session.stop()
        },
        nativeCase("$kind noncooperative late transport effect cannot be described as definitely unsent") {
            val radio = SessionRadioTransport()
            var continuation: Continuation<Unit>? = null
            val transport = object : MeshTransport by radio {
                val sent = mutableListOf<Bytes>()
                override suspend fun send(data: Bytes) {
                    if (data[0].toInt() == kind.command) {
                        suspendCoroutine<Unit> { continuation = it }
                    }
                    check(radio.isConnected())
                    sent += data
                }
            }
            val session = MeshCoreSession(
                transport, SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
                SchedulerClock(testScheduler), backgroundScope.coroutineContext,
            )
            val starting = checkedRequest { session.start() }; runCurrent()
            radio.receive(selfPacket()); runCurrent(); starting.await()
            assertEquals(1, transport.sent.size)
            val dispatch = MessageWriteDispatch()
            val caller = checkedRequest { send(session, kind, dispatch) }; runCurrent()
            assertNotNull(continuation)
            caller.cancel(); runCurrent()
            assertFailsWith<CancellationException> { caller.await() }
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            assertEquals(1, transport.sent.size)
            continuation!!.resume(Unit); runCurrent()
            assertEquals(2, transport.sent.size, "The actual transport records a write after cancellation returns")
            val expected = when (kind) {
                Kind.DIRECT -> hex("02008080009265") + filled(0x44, 6) + hex("68656c6c6f")
                Kind.CHANNEL -> hex("0300078000926568656c6c6f")
            }
            assertEquals(expected, transport.sent.last())
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            session.stop()
        },
        nativeCase("$kind sent cancellation without response retains unresolved-family quarantine") {
            val f = fixture(); start(f)
            val dispatch = MessageWriteDispatch()
            val caller = checkedRequest { send(f.session, kind, dispatch) }; runCurrent()
            caller.cancel(); runCurrent()
            assertFailsWith<CancellationException> { caller.await() }
            advanceTimeBy(1000); runCurrent()
            reply(f, kind); runCurrent()
            assertFailsWith<MeshCoreException.ConnectionLost> { send(f.session, kind, MessageWriteDispatch()) }
            assertEquals(2, f.transport.sent.size)
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("$kind retirement cannot dispatch an old queued message or upgrade sent cancellation") {
            val f = fixture(); start(f)
            val prior = checkedRequest { f.session.resetPath(filled(0x11, 32)) }; runCurrent()
            val unsent = MessageWriteDispatch()
            val queued = checkedRequest { send(f.session, kind, unsent) }; runCurrent()
            queued.cancel(); runCurrent()
            assertFailsWith<CancellationException> { queued.await() }
            f.session.stop(); runCurrent()
            assertFailsWith<MeshCoreException.ConnectionLost> { prior.await() }
            start(f)
            assertEquals(listOf(1, 0x0d, 1), f.transport.sent.map { it[0].toInt() })
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, unsent.cancellationDisposition)
            val possible = MessageWriteDispatch()
            val sent = checkedRequest { send(f.session, kind, possible) }; runCurrent()
            sent.cancel(); runCurrent()
            assertFailsWith<CancellationException> { sent.await() }
            f.session.stop()
            assertEquals(ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN, possible.cancellationDisposition)
        },
        nativeCase("$kind fallback role preserves cancellation and cannot invent dispatch accounting") {
            val cancellation = CancellationException("unsupported role")
            var calls = 0
            val role = object : MessagingSessionOps {
                override val currentSelfInfo: SelfInfo? = null
                override suspend fun sendMessage(destination: Bytes, text: String, timestamp: Instant, attempt: UByte): MessageSentInfo {
                    calls += 1
                    throw cancellation
                }
                override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) {
                    calls += 1
                    throw cancellation
                }
            }
            val dispatch = MessageWriteDispatch()
            assertSame(cancellation, assertFailsWith<CancellationException> { send(role, kind, dispatch) })
            assertEquals(1, calls)
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
            assertFailsWith<IllegalArgumentException> { send(role, kind, dispatch) }
            assertEquals(1, calls)
        },
    ) }

    @TestFactory
    fun wireAndHandleCases() = listOf(
        nativeCase("additive direct send cannot share a messageSent with an active status exchange") {
            val f = fixture(); start(f)
            val key = filled(0x31, 32)
            val statusCommand = hex("1b") + key
            val messagePacket = hex("02008080009265") + filled(0x44, 6) + hex("68656c6c6f")
            val status = checkedRequest { f.session.requestStatus(key, ContactType.ROOM) }; runCurrent()
            assertEquals(statusCommand, f.transport.sent.last())
            val dispatch = MessageWriteDispatch()
            val role: MessagingSessionOps = f.session
            val message = checkedRequest {
                role.sendMessage(filled(0x44, 32), "hello", testEpoch, 0x80u, dispatch = dispatch)
            }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            assertFalse(status.isCompleted)
            assertFalse(message.isCompleted)
            f.transport.receive(sentPacket(hex("deadbeef"), 1111)); runCurrent()
            assertFalse(status.isCompleted, "MessageSent metadata is not the requested status response")
            assertFalse(message.isCompleted, "The queued additive call cannot consume another exchange's metadata")
            assertEquals(2, f.transport.sent.size, "The status exchange must retain the real serializer")
            f.transport.receive(statusPacket(key, 1234, 5, 2)); runCurrent()
            val result = status.await()
            assertEquals(StatusResponse.Layout.ROOM_SERVER, result.layout)
            assertEquals(1234L, result.battery)
            assertEquals(5u.toUShort(), result.roomServerPostedCount)
            assertEquals(2u.toUShort(), result.roomServerPostPushCount)
            assertEquals(
                listOf(hex("01032020202020204d436f7265"), statusCommand, messagePacket),
                f.transport.sent,
            )
            assertFalse(message.isCompleted, "The earlier MessageSent must not complete the newly dispatched call")
            f.transport.receive(sentPacket(hex("11223344"), 3456)); runCurrent()
            assertEquals(MessageSentInfo(0u, hex("11223344"), 3456u), message.await())
            assertEquals(3, f.transport.sent.size)
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        nativeCase("direct additive call preserves prefix timestamp attempt UTF8 bytes and MessageSentInfo") {
            val f = fixture(); start(f)
            val dispatch = MessageWriteDispatch()
            val expected = hex("02008080009265") + filled(0x44, 6) + hex("6869f09f9880")
            val role: MessagingSessionOps = f.session
            val result = command(f, expected, sentPacket(hex("11223344"), 3456)) {
                role.sendMessage(filled(0x44, 32), "hi\uD83D\uDE00", testEpoch, 0x80u, dispatch)
            }
            assertEquals(MessageSentInfo(0u, hex("11223344"), 3456u), result)
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
            command(f, expected, sentPacket(hex("55667788"), 1234)) {
                f.session.sendMessage(filled(0x44, 32), "hi\uD83D\uDE00", testEpoch, 0x80u)
            }
            f.session.stop()
        },
        nativeCase("channel additive call preserves channel timestamp UTF8 bytes and old Unit call") {
            val f = fixture(); start(f)
            val dispatch = MessageWriteDispatch()
            val expected = hex("030007800092656869f09f9880")
            val role: MessagingSessionOps = f.session
            command(f, expected, raw(0)) {
                role.sendChannelMessage(7u, "hi\uD83D\uDE00", testEpoch, dispatch)
            }
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
            command(f, expected, raw(0)) {
                f.session.sendChannelMessage(7u, "hi\uD83D\uDE00", testEpoch)
            }
            f.session.stop()
        },
        nativeCase("a message handle cannot be reused across direct and channel attempts") {
            val f = fixture(); start(f)
            val prior = checkedRequest { f.session.resetPath(filled(0x11, 32)) }; runCurrent()
            val dispatch = MessageWriteDispatch()
            val first = checkedRequest { send(f.session, Kind.DIRECT, dispatch) }; runCurrent()
            first.cancel(); runCurrent()
            assertFailsWith<CancellationException> { first.await() }
            assertFailsWith<IllegalArgumentException> { send(f.session, Kind.CHANNEL, dispatch) }
            f.transport.ok(); runCurrent(); prior.await()
            assertEquals(2, f.transport.sent.size)
            assertEquals(ChannelWriteCancellationDisposition.DEFINITELY_UNSENT, dispatch.cancellationDisposition)
            f.session.stop()
        },
        nativeCase("direct invalid prefix and both device errors preserve typed failures without unsent inference") {
            val f = fixture(); start(f)
            val invalid = MessageWriteDispatch()
            assertFailsWith<MeshCoreException.InvalidInput> {
                f.session.sendMessage(filled(0x44, 5), "invalid", dispatch = invalid)
            }
            assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, invalid.cancellationDisposition)
            assertEquals(1, f.transport.sent.size)
            for (kind in Kind.entries) {
                val dispatch = MessageWriteDispatch()
                val caller = checkedRequest { send(f.session, kind, dispatch) }; runCurrent()
                f.transport.error(42); runCurrent()
                assertEquals(42u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { caller.await() }.code)
                assertEquals(ChannelWriteCancellationDisposition.UNKNOWN, dispatch.cancellationDisposition)
            }
            f.session.stop()
        },
    )
}
