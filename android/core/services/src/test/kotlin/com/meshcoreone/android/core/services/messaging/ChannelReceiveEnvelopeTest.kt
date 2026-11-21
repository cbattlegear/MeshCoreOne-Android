// AndroidOnly: WP-214 Nonactivated receive-envelope propagation over real parsed session events, not firmware-origin proof.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.ChannelReceiveEnvelope
import com.meshcoreone.android.core.contracts.domain.ChannelReceiveEnvelopePolling
import com.meshcoreone.android.core.contracts.domain.ChannelReceiveOrigin
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagingDiagnostic
import com.meshcoreone.android.core.contracts.domain.TeardownReport
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingError
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingException
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MessageResult
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import java.lang.reflect.Modifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelReceiveEnvelopeTest {
    @TestFactory
    fun receiveEnvelopes(): List<DynamicTest> = listOf(false, true).map { v3 ->
        case("equal separately parsed V${if (v3) 3 else 1} packets have distinct unproved occurrences") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val received = mutableListOf<ChannelReceiveEnvelope>()
            val optIn: ChannelReceiveEnvelopePolling = p
            optIn.setChannelReceiveEnvelopeHandler { envelope, metadata ->
                assertNull(metadata)
                received += envelope
            }
            p.startMessageEventMonitoring()
            repeat(2) { h.transport.incomingMessages += channelPacket(v3) }

            assertEquals(2L, p.pollAllMessages())
            runCurrent()

            assertEquals(2, received.size)
            assertEquals(received[0].message, received[1].message)
            assertTrue(received[0].message !== received[1].message)
            assertNotEquals(received[0].occurrenceID, received[1].occurrenceID)
            received.forEach {
                assertEquals(h.token, it.session)
                assertSame(ChannelReceiveOrigin.Unproved, it.origin)
                assertIs<DeliveryContext.InitialSync>(it.context)
            }
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        }
    } + listOf(
        case("reordered equal events cannot exchange the held poll occurrence or delivery context") {
            val h = Harness(this)
            h.start()
            val role = ControlledReceiveSession(h.session, reorderFirstPair = true)
            val pollGate = CompletableDeferred<Unit>()
            role.pollGate = pollGate
            val p = h.poller(role)
            val received = mutableListOf<ChannelReceiveEnvelope>()
            p.setChannelReceiveEnvelopeHandler { envelope, _ -> received += envelope }
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()
            val polling = backgroundScope.async { p.pollAllMessages() }
            runCurrent()
            assertFalse(polling.isCompleted)
            assertEquals(1, role.polled.size)
            assertTrue(received.isEmpty())

            h.transport.mock.simulateReceive(channelPacket())
            runCurrent()
            assertEquals(2, role.observed.size)
            assertEquals(role.observed[0], role.observed[1])
            assertTrue(role.observed[0] !== role.observed[1])
            pollGate.complete(Unit)
            assertEquals(1L, polling.await())
            runCurrent()

            assertEquals(2, received.size)
            assertSame(role.observed[1], received[0].message)
            assertEquals(DeliveryContext.Live, received[0].context)
            assertSame(role.polled.single(), received[1].message)
            assertIs<DeliveryContext.InitialSync>(received[1].context)
            assertNotEquals(received[0].occurrenceID, received[1].occurrenceID)
            received.forEach { assertSame(ChannelReceiveOrigin.Unproved, it.origin) }
            assertTrue(p.close().isComplete)
        },
        case("a delayed poll echo cannot swallow an earlier reordered structurally equal live occurrence") {
            val h = Harness(this)
            h.start()
            val role = ControlledReceiveSession(h.session, reorderFirstPair = true)
            val eventGate = CompletableDeferred<Unit>()
            role.eventGate = eventGate
            val p = h.poller(role)
            val received = mutableListOf<ChannelReceiveEnvelope>()
            p.setChannelReceiveEnvelopeHandler { envelope, _ -> received += envelope }
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()

            assertEquals(1L, p.pollAllMessages())
            assertEquals(1, received.size)
            val first = received.single()
            assertSame(role.polled.single(), first.message)
            h.transport.mock.simulateReceive(channelPacket())
            runCurrent()
            assertEquals(1, received.size)
            eventGate.complete(Unit)
            runCurrent()

            assertEquals(2, received.size)
            assertSame(first, received[0])
            assertSame(role.observed[1], received[1].message)
            assertEquals(DeliveryContext.Live, received[1].context)
            assertNotEquals(first.occurrenceID, received[1].occurrenceID)
            assertSame(ChannelReceiveOrigin.Unproved, received[1].origin)
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        },
        case("parked delivery retains the exact envelope across replacement and retry") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val a = ChannelDTO(radioId = RADIO, index = 0u, name = "A", secret = Bytes(ByteArray(16) { 1 }))
            h.store.channels[EntityKey(RADIO, a.id)] = a
            val envelopes = mutableListOf<ChannelReceiveEnvelope>()
            val metadata = mutableListOf<ChannelDTO?>()
            var fail = true
            p.setChannelReceiveEnvelopeHandler { envelope, channel ->
                envelopes += envelope
                metadata += channel
                if (fail) throw IllegalStateException("retry this consumed message")
            }
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()
            assertFailsWith<MessagePollingException> { p.pollAllMessages() }
            assertEquals(1, p.undeliveredCount)
            val original = envelopes.single()
            val b = a.copy(name = "B", secret = Bytes(ByteArray(16) { 2 }))
            h.store.channels[EntityKey(RADIO, a.id)] = b
            fail = false

            assertEquals(0L, p.pollAllMessages())

            assertEquals(2, envelopes.size)
            assertSame(original, envelopes[1])
            assertEquals(listOf<ChannelDTO?>(a, b), metadata)
            assertSame(ChannelReceiveOrigin.Unproved, original.origin)
            assertEquals(0, p.undeliveredCount)
            assertTrue(h.store.messages.isEmpty())
            assertTrue(p.close().isComplete)
        },
        case("same-key ABA metadata cannot promote a parked unproved delivery") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val a = ChannelDTO(radioId = RADIO, index = 0u, name = "A", secret = Bytes(ByteArray(16) { 1 }))
            h.store.channels[EntityKey(RADIO, a.id)] = a
            val received = mutableListOf<ChannelReceiveEnvelope>()
            var fail = true
            p.setChannelReceiveEnvelopeHandler { envelope, _ ->
                received += envelope
                if (fail) throw IllegalStateException("held across ABA")
            }
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()
            assertFailsWith<MessagePollingException> { p.pollAllMessages() }
            h.store.channels.clear()
            val b = a.copy(name = "B", secret = Bytes(ByteArray(16) { 2 }))
            h.store.channels[EntityKey(RADIO, b.id)] = b
            h.store.channels.clear()
            val recreated = ChannelDTO(radioId = RADIO, index = 0u, name = "A", secret = a.secret)
            h.store.channels[EntityKey(RADIO, recreated.id)] = recreated
            fail = false

            assertEquals(0L, p.pollAllMessages())

            assertSame(received[0], received[1])
            assertSame(ChannelReceiveOrigin.Unproved, received[1].origin)
            assertNotEquals(a.id, recreated.id)
            assertTrue(p.close().isComplete)
        },
        case("an empty drain cannot prove a later occurrence after same-row replacement") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val a = ChannelDTO(radioId = RADIO, index = 0u, name = "A", secret = Bytes(ByteArray(16) { 1 }))
            val b = a.copy(name = "B", secret = Bytes(ByteArray(16) { 2 }))
            val key = EntityKey(RADIO, a.id)
            h.store.channels[key] = a
            val delayedFrame = channelPacket()
            val received = mutableListOf<Pair<ChannelReceiveEnvelope, ChannelDTO?>>()
            p.setChannelReceiveEnvelopeHandler { envelope, metadata -> received += envelope to metadata }
            p.startAutoFetch(RADIO)
            runCurrent()
            h.transport.afterNoMore = {
                h.store.channels[key] = b
                h.transport.incomingMessages += delayedFrame
                h.transport.mock.simulateReceive(Bytes.of(ResponseCode.MESSAGES_WAITING.rawValue.toInt()))
            }

            assertEquals(0L, p.pollAllMessages())
            advanceUntilIdle()

            assertEquals(1, received.size)
            val (envelope, metadata) = received.single()
            assertEquals(b, metadata)
            assertEquals(a.id, assertNotNull(metadata).id)
            assertEquals(DeliveryContext.Live, envelope.context)
            assertSame(ChannelReceiveOrigin.Unproved, envelope.origin)
            assertTrue(h.transport.incomingMessages.isEmpty())
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        },
        case("duplicate-key slots cannot turn indexed metadata into receive proof") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val sharedSecret = Bytes(ByteArray(16) { 1 })
            val a = ChannelDTO(radioId = RADIO, index = 0u, name = "A", secret = sharedSecret)
            val other = ChannelDTO(radioId = RADIO, index = 1u, name = "Other", secret = sharedSecret)
            h.store.channels[EntityKey(RADIO, other.id)] = other
            h.store.channels[EntityKey(RADIO, a.id)] = a
            val received = mutableListOf<Pair<ChannelReceiveEnvelope, ChannelDTO?>>()
            p.setChannelReceiveEnvelopeHandler { envelope, metadata -> received += envelope to metadata }
            h.transport.incomingMessages += channelPacket()

            assertEquals(1L, p.pollAllMessages())

            val (envelope, metadata) = received.single()
            assertEquals(a, metadata)
            assertEquals(a.secret, other.secret)
            assertEquals(h.token, envelope.session)
            assertSame(ChannelReceiveOrigin.Unproved, envelope.origin)
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        },
        case("simulated firmware backlog across reconnect remains unproved after replacement") {
            val previous = Harness(this)
            previous.start()
            val a = ChannelDTO(radioId = RADIO, index = 0u, name = "A", secret = Bytes(ByteArray(16) { 1 }))
            val b = a.copy(name = "B", secret = Bytes(ByteArray(16) { 2 }))
            val key = EntityKey(RADIO, a.id)
            previous.store.channels[key] = a
            previous.transport.incomingMessages += channelPacket()
            previous.close()
            previous.store.channels[key] = b
            val successor = Harness(this, store = previous.store, generation = 2)
            successor.start()
            val p = successor.poller()
            val received = mutableListOf<Pair<ChannelReceiveEnvelope, ChannelDTO?>>()
            p.setChannelReceiveEnvelopeHandler { envelope, metadata -> received += envelope to metadata }
            successor.transport.incomingMessages += previous.transport.incomingMessages.removeFirst()

            assertEquals(1L, p.pollAllMessages())

            val (envelope, metadata) = received.single()
            assertNotEquals(previous.token, successor.token)
            assertEquals(successor.token, envelope.session)
            assertEquals(b, metadata)
            assertEquals(a.id, assertNotNull(metadata).id)
            assertSame(ChannelReceiveOrigin.Unproved, envelope.origin)
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        },
        case("legacy registration replaces opt-in registration without calling both consumers") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var typed = 0
            var legacy = 0
            p.setChannelReceiveEnvelopeHandler { _, _ -> typed++ }
            assertTrue(p.hasMessageHandlersWired)
            p.setChannelMessageHandler { message, _, context ->
                legacy++
                assertEquals("Alice: body", message.text)
                assertIs<DeliveryContext.InitialSync>(context)
            }
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()

            assertEquals(1L, p.pollAllMessages())
            runCurrent()

            assertEquals(0, typed)
            assertEquals(1, legacy)
            assertTrue(p.close().isComplete)
        },
        case("opt-in registration replaces the legacy consumer and clearing parks instead of losing data") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var legacy = 0
            val received = mutableListOf<ChannelReceiveEnvelope>()
            p.setChannelMessageHandler { _, _, _ -> legacy++ }
            p.setChannelReceiveEnvelopeHandler { envelope, _ -> received += envelope }
            h.transport.incomingMessages += channelPacket()
            assertEquals(1L, p.pollAllMessages())
            assertEquals(0, legacy)
            assertEquals(1, received.size)
            p.clearMessageHandlers()
            assertFalse(p.hasMessageHandlersWired)
            h.transport.incomingMessages += channelPacket()
            assertFailsWith<MessagePollingException> { p.pollAllMessages() }
            assertEquals(1, p.undeliveredCount)
            p.setChannelReceiveEnvelopeHandler { envelope, _ -> received += envelope }

            assertEquals(0L, p.pollAllMessages())

            assertEquals(2, received.size)
            assertNotEquals(received[0].occurrenceID, received[1].occurrenceID)
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        },
        case("generation retirement during lookup cannot call the typed consumer") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            p.setChannelReceiveEnvelopeHandler { _, _ -> calls++ }
            h.store.before = { if (it == "fetchChannel") gate.await() }
            h.transport.incomingMessages += channelPacket()
            val polling = backgroundScope.async {
                assertFailsWith<MessagePollingException> { p.pollAllMessages() }
            }
            runCurrent()
            h.signals.set(token(generation = 2), DeviceConnectionState.READY)
            gate.complete(Unit)
            polling.await()

            assertEquals(0, calls)
            assertEquals(1, p.undeliveredCount)
            assertFalse(p.close().isComplete)
            h.store.before = null
            val successor = Harness(this, store = h.store, generation = 2)
            successor.start()
            val next = successor.poller()
            val received = mutableListOf<ChannelReceiveEnvelope>()
            next.setChannelReceiveEnvelopeHandler { envelope, _ -> received += envelope }
            successor.transport.incomingMessages += channelPacket()
            assertEquals(1L, next.pollAllMessages())
            assertEquals(successor.token, received.single().session)
            assertSame(ChannelReceiveOrigin.Unproved, received.single().origin)
            assertTrue(next.close().isComplete)
        },
        case("typed consumer retains existing reentrant close and unfinished-delivery reporting") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var report: TeardownReport? = null
            p.setChannelReceiveEnvelopeHandler { envelope, _ ->
                assertSame(ChannelReceiveOrigin.Unproved, envelope.origin)
                report = p.close()
            }
            h.transport.incomingMessages += channelPacket()
            val polling = backgroundScope.async { p.pollAllMessages() }

            assertFailsWith<CancellationException> { polling.await() }

            assertFalse(assertNotNull(report).isComplete)
            assertEquals(1, p.undeliveredCount)
            assertEquals(0L, p.pendingHandlerCount)
            assertFalse(p.hasMessageHandlersWired)
        },
        case("legacy to opt-in to legacy cannot exchange two outstanding reordered echoes") {
            val h = Harness(this)
            h.start()
            val role = ControlledReceiveSession(h.session, reorderFirstPair = true)
            val eventGate = CompletableDeferred<Unit>()
            role.eventGate = eventGate
            val p = h.poller(role)
            val legacy = mutableListOf<Pair<ChannelMessage, DeliveryContext>>()
            val typed = mutableListOf<ChannelReceiveEnvelope>()
            suspend fun legacyConsumer() =
                p.setChannelMessageHandler { message, _, context -> legacy += message to context }
            legacyConsumer()
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()
            assertEquals(1L, p.pollAllMessages())
            p.setChannelReceiveEnvelopeHandler { envelope, _ -> typed += envelope }
            h.transport.incomingMessages += channelPacket()
            assertEquals(1L, p.pollAllMessages())
            legacyConsumer()
            eventGate.complete(Unit)
            runCurrent()

            assertEquals(2, role.polled.size)
            assertEquals(role.polled[0], role.polled[1])
            assertTrue(role.polled[0] !== role.polled[1])
            assertEquals(1, legacy.size)
            assertEquals(1, typed.size)
            assertSame(role.polled[0], legacy.single().first)
            assertIs<DeliveryContext.InitialSync>(legacy.single().second)
            assertSame(role.polled[1], typed.single().message)
            assertIs<DeliveryContext.InitialSync>(typed.single().context)
            assertSame(ChannelReceiveOrigin.Unproved, typed.single().origin)
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        },
        case("legacy to opt-in to legacy selects the exact held poll occurrence before an equal buffered event") {
            val h = Harness(this)
            h.start()
            val role = ControlledReceiveSession(h.session, reorderFirstPair = true)
            val pollGate = CompletableDeferred<Unit>()
            role.pollGate = pollGate
            val p = h.poller(role)
            val legacy = mutableListOf<Pair<ChannelMessage, DeliveryContext>>()
            var typedCalls = 0
            suspend fun legacyConsumer() =
                p.setChannelMessageHandler { message, _, context -> legacy += message to context }
            legacyConsumer()
            role.betweenReorderedEvents = {
                p.setChannelReceiveEnvelopeHandler { _, _ -> typedCalls++ }
            }
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()
            val polling = backgroundScope.async { p.pollAllMessages() }
            runCurrent()
            assertFalse(polling.isCompleted)
            h.transport.mock.simulateReceive(channelPacket())
            runCurrent()
            assertEquals(2, role.observed.size)
            assertTrue(legacy.isEmpty())
            legacyConsumer()
            pollGate.complete(Unit)
            assertEquals(1L, polling.await())
            runCurrent()

            assertEquals(0, typedCalls)
            assertEquals(2, legacy.size)
            assertSame(role.observed[1], legacy[0].first)
            assertEquals(DeliveryContext.Live, legacy[0].second)
            assertSame(role.polled.single(), legacy[1].first)
            assertIs<DeliveryContext.InitialSync>(legacy[1].second)
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        },
        case("pure legacy retains structural-first polled-object callbacks and InitialSync then Live order") {
            val h = Harness(this)
            h.start()
            val role = ControlledReceiveSession(h.session, reorderFirstPair = true)
            val pollGate = CompletableDeferred<Unit>()
            role.pollGate = pollGate
            val p = h.poller(role)
            val received = mutableListOf<Pair<ChannelMessage, DeliveryContext>>()
            p.setChannelMessageHandler { message, _, context -> received += message to context }
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()
            val polling = backgroundScope.async { p.pollAllMessages() }
            runCurrent()
            assertFalse(polling.isCompleted)
            h.transport.mock.simulateReceive(channelPacket())
            runCurrent()
            assertEquals(2, role.observed.size)
            assertEquals(role.observed[0], role.observed[1])
            assertTrue(role.observed[0] !== role.observed[1])
            pollGate.complete(Unit)
            assertEquals(1L, polling.await())
            runCurrent()

            assertEquals(2, received.size)
            assertSame(role.polled.single(), received[0].first)
            assertIs<DeliveryContext.InitialSync>(received[0].second)
            assertSame(role.polled.single(), received[1].first)
            assertEquals(DeliveryContext.Live, received[1].second)
            assertEquals(0, p.undeliveredCount)
            assertTrue(p.close().isComplete)
        },
        case("pre-lookup admission preserves equal live and polled occurrences and their contexts") {
            val h = Harness(this)
            h.start()
            val role = ControlledReceiveSession(h.session, reorderFirstPair = false)
            val pollGate = CompletableDeferred<Unit>()
            role.beforePollGate = pollGate
            val p = h.poller(role)
            val admitted = mutableListOf<Pair<ChannelMessage, DeliveryContext>>()
            p.setChannelMessageHandler(
                beforeLookup = { message, context ->
                    admitted += message to context
                    suspend { }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            p.startMessageEventMonitoring()
            val polling = backgroundScope.async { p.pollAllMessages() }
            runCurrent()
            val reaction = "Bob: @[Alice]\uD83D\uDC4D\nb45pc4ek"
            h.transport.mock.simulateReceive(channelPacket(text = reaction))
            runCurrent()
            assertEquals(1, role.observed.size)
            h.transport.incomingMessages += channelPacket(text = reaction)
            pollGate.complete(Unit)
            assertEquals(1L, polling.await())
            runCurrent()

            assertEquals(2, role.observed.size)
            assertEquals(role.observed[0], role.observed[1])
            assertTrue(role.observed[0] !== role.observed[1])
            assertEquals(2, admitted.size)
            assertSame(role.observed[0], admitted[0].first)
            assertEquals(DeliveryContext.Live, admitted[0].second)
            assertSame(role.polled.single(), admitted[1].first)
            assertIs<DeliveryContext.InitialSync>(admitted[1].second)
            assertTrue(p.close().isComplete)
        },
        case("live intake admits the next reaction while the previous consumer is suspended") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val admitted = mutableListOf<Pair<ChannelMessage, DeliveryContext>>()
            val gate = CompletableDeferred<Unit>()
            p.setChannelMessageHandler(
                beforeLookup = { message, context ->
                    admitted += message to context
                    suspend { gate.await() }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            try {
                p.startMessageEventMonitoring()
                h.transport.mock.simulateReceive(channelPacket(text = "Bob: @[Alice]\uD83D\uDC4D\nb45pc4ek"))
                runCurrent()
                assertEquals(1, admitted.size)
                h.transport.mock.simulateReceive(channelPacket(text = "Carol: @[Alice]\uD83D\uDC4D\nb45pc4ek"))
                runCurrent()
                assertEquals(2, admitted.size)
                assertEquals(listOf(DeliveryContext.Live, DeliveryContext.Live), admitted.map { it.second })
                assertTrue(admitted[0].first !== admitted[1].first)
                p.stopMessageEventMonitoring()
                assertEquals(2, admitted.size)
            } finally {
                gate.complete(Unit)
                p.close()
            }
        },
        case("monitor restart resumes accepted live work without a new event") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var admissions = 0
            var attempts = 0
            val recovered = CompletableDeferred<Unit>()
            p.setChannelMessageHandler(
                beforeLookup = { _, _ ->
                    admissions++
                    suspend {
                        attempts++
                        if (attempts == 1) awaitCancellation()
                        recovered.complete(Unit)
                    }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            try {
                p.startMessageEventMonitoring()
                h.transport.mock.simulateReceive(channelPacket(text = "Bob: @[Alice]\uD83D\uDC4D\nb45pc4ek"))
                runCurrent()
                assertEquals(1, admissions)
                assertEquals(1, attempts)
                p.stopMessageEventMonitoring()
                assertEquals(1, p.undeliveredCount)
                p.startMessageEventMonitoring()
                runCurrent()
                assertTrue(recovered.isCompleted)
                assertEquals(1, admissions)
                assertEquals(2, attempts)
                assertEquals(0, p.undeliveredCount)
            } finally { p.close() }
        },
        case("monitor restart does not retry an interrupted non-reaction handler") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var attempts = 0
            p.setChannelMessageHandler(
                beforeLookup = { _, _ -> null },
                handler = { _, _, _ ->
                    attempts++
                    awaitCancellation()
                },
            )
            try {
                p.startMessageEventMonitoring()
                h.transport.mock.simulateReceive(channelPacket(text = "ordinary body"))
                runCurrent()
                assertEquals(1, attempts)
                p.stopMessageEventMonitoring()
                p.startMessageEventMonitoring()
                runCurrent()
                assertEquals(1, attempts)
                assertEquals(1, p.undeliveredCount)
            } finally { p.close() }
        },
        case("monitor restart does not invoke a replaced pre-lookup handler") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var oldAttempts = 0
            var replacementCalls = 0
            p.setChannelMessageHandler(
                beforeLookup = { _, _ ->
                    suspend {
                        oldAttempts++
                        awaitCancellation()
                    }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            try {
                p.startMessageEventMonitoring()
                h.transport.mock.simulateReceive(channelPacket())
                runCurrent()
                assertEquals(1, oldAttempts)
                p.stopMessageEventMonitoring()
                p.setChannelMessageHandler { _, _, _ -> replacementCalls++ }
                p.startMessageEventMonitoring()
                runCurrent()
                assertEquals(1, oldAttempts)
                assertEquals(0, replacementCalls)
                assertEquals(1, p.undeliveredCount)
            } finally { p.close() }
        },
        case("replaced handler can admit a live event the original handler declined") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val gate = CompletableDeferred<Unit>()
            val oldSeen = mutableListOf<String>()
            val newSeen = mutableListOf<String>()
            var fallback = 0
            var newAttempts = 0
            p.setChannelMessageHandler(
                beforeLookup = { message, _ ->
                    oldSeen += message.text
                    if (message.text == "first") suspend { gate.await() } else null
                },
                handler = { _, _, _ -> fallback++ },
            )
            try {
                p.startMessageEventMonitoring()
                h.transport.mock.simulateReceive(channelPacket(text = "first"))
                runCurrent()
                h.transport.mock.simulateReceive(channelPacket(text = "second"))
                runCurrent()
                assertEquals(listOf("first", "second"), oldSeen)
                p.setChannelMessageHandler(
                    beforeLookup = { message, _ ->
                        newSeen += message.text
                        suspend {
                            newAttempts++
                            if (newAttempts == 1) awaitCancellation()
                        }
                    },
                    handler = { _, _, _ -> fallback++ },
                )
                gate.complete(Unit)
                runCurrent()
                assertEquals(listOf("second"), newSeen)
                assertEquals(0, fallback)
                assertEquals(1, newAttempts)
                p.stopMessageEventMonitoring()
                p.startMessageEventMonitoring()
                runCurrent()
                assertEquals(listOf("second"), newSeen)
                assertEquals(2, newAttempts)
                assertEquals(0, p.undeliveredCount)
            } finally {
                gate.complete(Unit)
                p.close()
            }
        },
        case("live admission failure is reported while the earlier consumer is suspended") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val gate = CompletableDeferred<Unit>()
            val failure = IllegalStateException("receive capacity")
            var attempts = 0
            p.setChannelMessageHandler(
                beforeLookup = { _, _ ->
                    attempts++
                    if (attempts == 2) throw failure
                    suspend { gate.await() }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            try {
                p.startMessageEventMonitoring()
                h.transport.mock.simulateReceive(channelPacket(text = "first"))
                runCurrent()
                h.transport.mock.simulateReceive(channelPacket(text = "second"))
                runCurrent()
                assertEquals(2, attempts)
                assertEquals(2, p.undeliveredCount)
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                    it.operation == "messageDelivery" && it.cause.cause === failure
                })
                gate.complete(Unit)
                runCurrent()
                assertEquals(0L, p.pollAllMessages())
                assertEquals(3, attempts)
                assertEquals(0, p.undeliveredCount)
            } finally {
                gate.complete(Unit)
                p.close()
            }
        },
        case("live intake reports its bounded backlog without discarding accepted reactions") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val gate = CompletableDeferred<Unit>()
            var admitted = 0
            p.setChannelMessageHandler(
                beforeLookup = { _, _ ->
                    admitted++
                    suspend { gate.await() }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            try {
                p.startMessageEventMonitoring()
                repeat(101) {
                    h.transport.mock.simulateReceive(channelPacket(text = "reaction $it"))
                    runCurrent()
                }
                assertEquals(100, admitted)
                assertEquals(100, p.undeliveredCount)
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                    it.operation == "liveIntakeCapacity"
                })
                gate.complete(Unit)
                runCurrent()
                assertEquals(101, admitted)
                assertEquals(0, p.undeliveredCount)
            } finally {
                gate.complete(Unit)
                p.close()
            }
        },
        case("live intake stops explicitly if a full backlog cannot drain") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var admitted = 0
            p.setChannelMessageHandler(
                beforeLookup = { _, _ ->
                    admitted++
                    suspend { throw MessagePollingException(MessagePollingError.NotConnected) }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            try {
                p.startMessageEventMonitoring()
                repeat(101) {
                    h.transport.mock.simulateReceive(channelPacket(text = "reaction $it"))
                    runCurrent()
                }
                assertEquals(100, admitted)
                assertEquals(100, p.undeliveredCount)
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                    it.operation == "liveIntakeCapacity"
                })
                assertTrue(h.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                    it.operation == "messageMonitor"
                })
            } finally { p.close() }
        },
        case("live accepted consumer can close its own poller without a self join") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val result = CompletableDeferred<TeardownReport>()
            p.setChannelMessageHandler(
                beforeLookup = { _, _ -> suspend { result.complete(p.close()) } },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            p.startMessageEventMonitoring()
            h.transport.mock.simulateReceive(channelPacket())
            runCurrent()
            assertTrue(result.isCompleted)
            assertFalse(result.await().isComplete)
            assertFalse(p.hasMessageHandlersWired)
        },
        case("live source accepted just before close does not start a retired consumer") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            var admitted = 0
            var consumed = 0
            var closing: kotlinx.coroutines.Job? = null
            p.setChannelMessageHandler(
                beforeLookup = { _, _ ->
                    admitted++
                    closing = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { p.close() }
                    suspend { consumed++ }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            p.startMessageEventMonitoring()
            h.transport.mock.simulateReceive(channelPacket())
            runCurrent()
            assertNotNull(closing).join()
            assertEquals(1, admitted)
            assertEquals(0, consumed)
        },
        case("live monitor stop and a reentrant consumer close cannot wait on each other") {
            val h = Harness(this)
            h.start()
            val p = h.poller()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val closed = CompletableDeferred<TeardownReport>()
            p.setChannelMessageHandler(
                beforeLookup = { _, _ ->
                    suspend {
                        entered.complete(Unit)
                        withContext(NonCancellable) {
                            release.await()
                            closed.complete(p.close())
                        }
                    }
                },
                handler = { _, _, _ -> error("pre-lookup admission must handle the message") },
            )
            p.startMessageEventMonitoring()
            h.transport.mock.simulateReceive(channelPacket())
            runCurrent()
            assertTrue(entered.isCompleted)
            val stop = backgroundScope.async { p.stopMessageEventMonitoring() }
            runCurrent()
            assertFalse(stop.isCompleted)
            release.complete(Unit)
            runCurrent()
            assertTrue(stop.isCompleted)
            assertTrue(closed.isCompleted)
            stop.await()
            assertFalse(closed.await().isComplete)
        },
        case("reserved proved state has no public constructor or copy factory") {
            val proof = ChannelReceiveOrigin.Proved::class.java
            assertTrue(proof.declaredConstructors.all { Modifier.isPrivate(it.modifiers) })
            assertTrue(proof.methods.none { it.name == "copy" || it.returnType == proof })
        },
    ) + listOf(false, true).map { initiallyTyped ->
        case("changing consumers with an outstanding echo keeps occurrence-aware pairing ($initiallyTyped)") {
            val h = Harness(this)
            h.start()
            val role = ControlledReceiveSession(h.session, reorderFirstPair = true)
            val eventGate = CompletableDeferred<Unit>()
            role.eventGate = eventGate
            val p = h.poller(role)
            val typed = mutableListOf<ChannelReceiveEnvelope>()
            val legacy = mutableListOf<ChannelMessage>()
            suspend fun typedConsumer() =
                p.setChannelReceiveEnvelopeHandler { envelope, _ -> typed += envelope }
            suspend fun legacyConsumer() =
                p.setChannelMessageHandler { message, _, _ -> legacy += message }
            if (initiallyTyped) typedConsumer() else legacyConsumer()
            p.startMessageEventMonitoring()
            h.transport.incomingMessages += channelPacket()
            assertEquals(1L, p.pollAllMessages())
            if (initiallyTyped) legacyConsumer() else typedConsumer()
            h.transport.mock.simulateReceive(channelPacket())
            runCurrent()
            eventGate.complete(Unit)
            runCurrent()

            assertEquals(1, typed.size)
            assertEquals(1, legacy.size)
            val polled = role.polled.single()
            val live = role.observed[1]
            assertSame(if (initiallyTyped) polled else live, typed.single().message)
            assertSame(if (initiallyTyped) live else polled, legacy.single())
            assertSame(ChannelReceiveOrigin.Unproved, typed.single().origin)
            assertTrue(p.close().isComplete)
        }
    }

    private fun case(name: String, assertion: suspend TestScope.() -> Unit): DynamicTest =
        original("WP-214", "receive envelope $name", assertion = assertion)

    private fun channelPacket(v3: Boolean = false, text: String = "Alice: body"): Bytes {
        val packet = ByteWriter().appendUInt8(
            if (v3) ResponseCode.CHANNEL_MESSAGE_RECEIVED_V3.rawValue else ResponseCode.CHANNEL_MESSAGE_RECEIVED.rawValue,
        )
        if (v3) packet.appendInt8(-10).appendUInt16LE(0u)
        return packet.appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
            .appendUInt32LE(42u).append(Bytes.utf8(text)).toBytes()
    }

    private class ControlledReceiveSession(
        private val actual: MeshCoreSessionProtocol,
        private val reorderFirstPair: Boolean,
    ) : MeshCoreSessionProtocol by actual {
        var beforePollGate: CompletableDeferred<Unit>? = null
        var pollGate: CompletableDeferred<Unit>? = null
        var eventGate: CompletableDeferred<Unit>? = null
        var betweenReorderedEvents: (suspend () -> Unit)? = null
        val polled = mutableListOf<ChannelMessage>()
        val observed = mutableListOf<ChannelMessage>()

        override suspend fun getMessage(timeout: Double?): MessageResult {
            beforePollGate?.await()
            val result = actual.getMessage(timeout)
            if (result is MessageResult.ChannelMessage) {
                polled += result.message
                pollGate?.await()
            }
            return result
        }

        override fun events(filter: EventFilter): Flow<MeshEvent> {
            val events = actual.events(filter)
            var first: MeshEvent.ChannelMessageReceived? = null
            var reordered = false
            return events.transform { event ->
                if (event !is MeshEvent.ChannelMessageReceived) {
                    emit(event)
                } else {
                    eventGate?.await()
                    observed += event.message
                    val held = first
                    if (!reorderFirstPair || reordered) emit(event)
                    else if (held == null) first = event
                    else {
                        reordered = true
                        emit(event)
                        betweenReorderedEvents?.invoke()
                        emit(held)
                    }
                }
            }
        }
    }
}
