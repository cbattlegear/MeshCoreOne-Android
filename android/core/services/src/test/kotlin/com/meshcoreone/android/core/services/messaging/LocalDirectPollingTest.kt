// AndroidOnly: WP-208 Parsed-object direct receive pairing and retained classification over the real protocol session.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.*
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import java.lang.reflect.Proxy
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDirectPollingTest {
    @TestFactory fun localDirectPolling(): List<DynamicTest> = listOf(
        case("equal separately parsed packets retain two occurrences and pair only their own echoes") {
            val h = Harness(this)
            h.start()
            val store = LookupStore(h.store)
            val p = poller(h, store)
            try {
                val received = mutableListOf<LocalDirectMessageEnvelope>()
                p.setLocalDirectMessageHandler { received += it }
                p.startMessageEventMonitoring()
                repeat(2) { h.transport.incomingMessages += packet() }
                assertEquals(2L, p.pollAllMessages())
                runCurrent()
                assertEquals(2, received.size)
                assertEquals(received[0].message, received[1].message)
                assertTrue(received[0].message !== received[1].message)
                assertNotEquals(received[0].occurrenceID, received[1].occurrenceID)
                assertEquals(2, store.lookups)
                assertEquals(0, p.undeliveredCount)
            } finally { p.close() }
        },
        case("held poll matches shared parsed object rather than an equal reordered live object") {
            val h = Harness(this)
            h.start()
            val role = ControlledDirectSession(h.session)
            val gate = CompletableDeferred<Unit>()
            role.pollGate = gate
            val p = poller(h, LookupStore(h.store), role)
            try {
                val received = mutableListOf<LocalDirectMessageEnvelope>()
                p.setLocalDirectMessageHandler { received += it }
                p.startMessageEventMonitoring()
                h.transport.incomingMessages += packet()
                val polling = backgroundScope.async { p.pollAllMessages() }
                runCurrent()
                assertFalse(polling.isCompleted)
                h.transport.mock.simulateReceive(packet())
                runCurrent()
                assertEquals(2, role.observed.size)
                gate.complete(Unit)
                assertEquals(1L, polling.await())
                runCurrent()
                assertEquals(2, received.size)
                assertSame(role.observed[1], received[0].message)
                assertEquals(DeliveryContext.Live, received[0].context)
                assertSame(role.polled.single(), received[1].message)
                assertIs<DeliveryContext.InitialSync>(received[1].context)
                assertNotEquals(received[0].occurrenceID, received[1].occurrenceID)
            } finally { p.close() }
        },
        case("late expected echo cannot consume an equal distinct live packet") {
            val h = Harness(this)
            h.start()
            val role = ControlledDirectSession(h.session)
            val gate = CompletableDeferred<Unit>()
            role.eventGate = gate
            val p = poller(h, LookupStore(h.store), role)
            try {
                val received = mutableListOf<LocalDirectMessageEnvelope>()
                p.setLocalDirectMessageHandler { received += it }
                p.startMessageEventMonitoring()
                h.transport.incomingMessages += packet()
                assertEquals(1L, p.pollAllMessages())
                assertEquals(1, received.size)
                h.transport.mock.simulateReceive(packet())
                runCurrent()
                gate.complete(Unit)
                runCurrent()
                assertEquals(2, received.size)
                assertSame(role.polled.single(), received[0].message)
                assertSame(role.observed[1], received[1].message)
                assertEquals(DeliveryContext.Live, received[1].context)
                assertNotEquals(received[0].occurrenceID, received[1].occurrenceID)
            } finally { p.close() }
        },
        case("parked ambiguous occurrence cannot become unique after a roster shrink") {
            val h = Harness(this)
            h.start()
            val store = LookupStore(h.store)
            val p = poller(h, store)
            try {
                val received = mutableListOf<LocalDirectMessageEnvelope>()
                var fail = true
                p.setLocalDirectMessageHandler {
                    received += it
                    if (fail) error("held before persistence")
                }
                p.startMessageEventMonitoring()
                h.transport.incomingMessages += packet()
                assertFailsWith<MessagePollingException> { p.pollAllMessages() }
                store.resolution = LocalDirectContactResolution.Unique(h.contact, java.util.UUID.randomUUID())
                fail = false
                assertEquals(0L, p.pollAllMessages())
                assertEquals(2, received.size)
                assertEquals(received[0].occurrenceID, received[1].occurrenceID)
                assertSame(received[0].message, received[1].message)
                assertEquals(LocalDirectContactResolution.Ambiguous, received[1].resolution)
                assertEquals(1, store.lookups)
            } finally { p.close() }
        },
        case("database lookup failure parks without absence and retries the lookup") {
            val h = Harness(this)
            h.start()
            val store = LookupStore(h.store)
            store.failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("controlled storage failure"))
            val p = poller(h, store)
            try {
                var callbacks = 0
                p.setLocalDirectMessageHandler { callbacks++ }
                p.startMessageEventMonitoring()
                h.transport.incomingMessages += packet()
                assertFailsWith<PersistenceStoreException> { p.pollAllMessages() }
                assertEquals(0, callbacks)
                assertEquals(1, p.undeliveredCount)
                store.failure = null
                assertEquals(0L, p.pollAllMessages())
                assertEquals(1, callbacks)
                assertEquals(2, store.lookups)
            } finally { p.close() }
        },
        case("legacy registration replaces opt-in and CLI dispatch keeps its original lookup contract") {
            val h = Harness(this)
            h.start()
            val store = LookupStore(h.store)
            val p = poller(h, store)
            try {
                var typed = 0
                var legacy = 0
                var cli = 0
                p.setLocalDirectMessageHandler { typed++ }
                p.setContactMessageHandler { _, _, _ -> legacy++ }
                p.setCLIMessageHandler { _, _ -> cli++ }
                p.startMessageEventMonitoring()
                h.transport.incomingMessages += packet()
                h.transport.incomingMessages += packet(1u)
                assertEquals(2L, p.pollAllMessages())
                runCurrent()
                assertEquals(0, typed)
                assertEquals(1, legacy)
                assertEquals(1, cli)
                assertEquals(0, store.lookups)
            } finally { p.close() }
        },
    )

    private fun case(name: String, assertion: suspend TestScope.() -> Unit): DynamicTest =
        original("WP-208", "local direct $name", assertion = assertion)

    private fun poller(h: Harness, store: LookupStore, session: MeshCoreSessionProtocol = h.session) =
        MessagePollingService(h.token, session, store, h.signals, h.scope, h.clock,
            MessagingIssueReporter { h.diagnostics += it })

    private fun packet(type: UByte = 0u): Bytes =
        ByteWriter().appendUInt8(ResponseCode.CONTACT_MESSAGE_RECEIVED.rawValue).append(TARGET.prefix(6))
            .appendUInt8(0u).appendUInt8(type).appendUInt32LE(42u).append(Bytes.utf8("same body")).toBytes()

    private class LookupStore(base: PersistenceStoreProtocol) : PersistenceStoreProtocol by base,
        LocalDirectContactPersisting by unsupportedLocalRole() {
        var resolution: LocalDirectContactResolution = LocalDirectContactResolution.Ambiguous
        var failure: PersistenceStoreException? = null
        var lookups = 0
        override suspend fun classifyLocalDirectContact(radioId: RadioId, prefix: Bytes): LocalDirectContactResolution {
            lookups++
            failure?.let { throw it }
            return resolution
        }
    }

    private class ControlledDirectSession(private val actual: MeshCoreSessionProtocol) : MeshCoreSessionProtocol by actual {
        var pollGate: CompletableDeferred<Unit>? = null
        var eventGate: CompletableDeferred<Unit>? = null
        val polled = mutableListOf<ContactMessage>()
        val observed = mutableListOf<ContactMessage>()
        override suspend fun getMessage(timeout: Double?): MessageResult {
            val result = actual.getMessage(timeout)
            if (result is MessageResult.ContactMessage) {
                polled += result.message
                pollGate?.await()
            }
            return result
        }
        override fun events(filter: EventFilter): Flow<MeshEvent> {
            var first: MeshEvent.ContactMessageReceived? = null
            var reordered = false
            return actual.events(filter).transform { event ->
                if (event !is MeshEvent.ContactMessageReceived) emit(event)
                else {
                    eventGate?.await()
                    observed += event.message
                    val held = first
                    if (reordered) emit(event)
                    else if (held == null) first = event
                    else {
                        reordered = true
                        emit(event)
                        emit(held)
                    }
                }
            }
        }
    }

    companion object {
        private fun unsupportedLocalRole(): LocalDirectContactPersisting = LocalDirectContactPersisting::class.java.cast(
            Proxy.newProxyInstance(LocalDirectContactPersisting::class.java.classLoader,
                arrayOf(LocalDirectContactPersisting::class.java)) { _, method, _ ->
                throw UnsupportedOperationException("Undeclared polling-only test operation: ${method.name}")
            },
        )
    }
}
