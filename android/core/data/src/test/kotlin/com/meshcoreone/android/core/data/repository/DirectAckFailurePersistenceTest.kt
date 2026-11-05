// AndroidOnly: WP-202 Real Room controls for direct-ACK failure-write commit uncertainty.
package com.meshcoreone.android.core.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class DirectAckFailurePersistenceTest {
    private val scheduler = TestCoroutineScheduler()
    private val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val radio = RadioId(UUID.fromString("AAAAAAAA-1111-2222-3333-444444444444"))
    private val self = Bytes(ByteArray(32) { 1 })
    private val peer = Bytes(ByteArray(32) { 0x22 })
    private val code = Bytes.fromHex("01234567")
    private val base = Instant.parse("2026-10-05T12:00:00Z")
    private val clock = object : SessionClock {
        override val now: Duration get() = scheduler.currentTime.milliseconds
        override val wallClock: Clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = base.plusMillis(scheduler.currentTime)
        }
        override suspend fun sleepFor(duration: Duration) { delay(duration) }
    }
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore
    private lateinit var contact: ContactDTO
    private val generations = mutableListOf<RadioGeneration>()

    @Before fun open() {
        val immediate = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), MeshCoreDatabase::class.java,
        ).allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = RoomPersistenceStore(db, process, clock.wallClock)
        contact = ContactDTO(radioId = radio, publicKey = peer, name = "Peer", lastHeardTimestamp = 0u)
    }

    @After fun close() {
        try {
            runTest(scheduler) {
                try {
                    for (generation in generations.asReversed()) generation.stop()
                } finally { store.close() }
            }
        } finally { process.cancel(); db.close() }
    }

    private inner class RadioGeneration(number: Long, persistence: PersistenceStoreProtocol = store) {
        val scope = CoroutineScope(SupervisorJob(process.coroutineContext[Job]) + StandardTestDispatcher(scheduler))
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("CCCCCCCC-1111-2222-3333-444444444444")), Generation(number), radio,
        )
        val signals = Signals(token)
        val firmware = Firmware()
        val session = MeshCoreSession(firmware, SessionConfiguration(), clock, scope.coroutineContext)
        val diagnostics = mutableListOf<MessagingDiagnostic>()
        val service = MessageService(
            token, self, session, persistence, signals, scope,
            MessageServiceConfig(ackGiveUpWindow = 1.0), clock,
            reporter = MessagingIssueReporter { diagnostics += it },
        )
        val statuses = mutableListOf<MessageStatusEvent>()
        private val subscription = service.statusEvents()
        private val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect {
                assertEquals(token, it.token)
                statuses += it.event
            }
        }
        init { generations += this }

        suspend fun start() { session.start(); service.startEventMonitoring() }
        suspend fun stop(): TeardownReport {
            try { return service.close() }
            finally {
                try { session.stop() }
                finally { subscription.close(); collector.cancelAndJoin(); scope.cancel() }
            }
        }
    }

    private inner class FailedWriter : PersistenceStoreProtocol by store {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = PersistenceStoreException(PersistenceStoreError.SaveFailed("controlled FAILED writer"))
        var commit = true
        var throws = true
        var failedWrites = 0
        var ackWrites = 0
        var committed: Boolean? = null

        override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean {
            if (status != MessageStatus.FAILED) return store.updateMessageStatusUnlessDelivered(key, status)
            failedWrites++
            check(failedWrites == 1) { "Unexpected second FAILED writer" }
            entered.complete(Unit)
            release.await()
            val changed = if (commit) store.updateMessageStatusUnlessDelivered(key, status) else false
            if (commit) committed = changed
            if (throws) throw failure
            return changed
        }

        override suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt?) {
            ackWrites++
            store.updateMessageAck(key, ackCode, status, roundTripTime)
        }
    }

    private suspend fun TestScope.sent(generation: RadioGeneration): MessageDTO {
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = self, nodeName = "Local"))
        store.saveContact(contact)
        generation.start()
        val message = generation.service.sendDirectMessage("direct ACK", contact)
        assertEquals(MessageStatus.SENT, message.status)
        assertEquals(code.readUInt32LE(0), message.ackCode)
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(1, generation.service.pendingAckCount)
        runCurrent()
        generation.statuses.clear()
        return message
    }

    private suspend fun TestScope.registerAckWhileWriterIsHeld(generation: RadioGeneration, writer: FailedWriter) {
        assertTrue(writer.entered.isCompleted)
        assertFalse(writer.release.isCompleted)
        generation.firmware.pushAck()
        runCurrent()
        assertEquals(1, generation.service.pendingAckCount)
        assertEquals(0, writer.ackWrites, "ACK persistence must wait for the real service mutation lock")
        // A registered ACK is excluded from a second failure sweep before that sweep can take the held lock.
        val sweep = async { generation.service.failAllPendingMessages() }
        runCurrent()
        assertTrue(sweep.isCompleted, "The raw ACK must already have marked the live pending entry delivered")
        sweep.await()
        assertEquals(1, writer.failedWrites)
    }

    private suspend fun TestScope.assertUncertain(generation: RadioGeneration, writer: FailedWriter, message: MessageDTO) {
        val key = EntityKey(radio, message.id)
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
        assertEquals(1, generation.service.pendingAckCount, "Uncertain FAILED is not a delivered readback")
        assertTrue(generation.statuses.isEmpty(), "Uncertain persistence must not publish delivery or terminal failure")
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any { it.cause === writer.failure })
        generation.service.checkExpiredAcks()
        runCurrent()
        assertEquals(1, generation.service.pendingAckCount, "A later reconciliation must retain the same uncertainty")
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
        val report = generation.stop()
        assertFalse(report.isComplete)
        assertTrue(report.issues.any { it.cause === writer.failure }, "Teardown must report the original writer cause")
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
    }

    @Test fun failedWriterCommitsThenThrowsAfterMatchingRawAckRetainsUncertainty() = runTest(scheduler) {
        val writer = FailedWriter()
        val generation = RadioGeneration(1, writer)
        val message = sent(generation)
        val failure = async { runCatching { generation.service.failAllPendingMessages() } }
        runCurrent()
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, message.id))?.status)
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        assertEquals(true, writer.committed, "The actual Room writer must return after committing FAILED")
        assertUncertain(generation, writer, message)
    }

    @Test fun expiredAckWriterCommitsThenThrowsAfterMatchingRawAckRetainsUncertainty() = runTest(scheduler) {
        val writer = FailedWriter()
        val generation = RadioGeneration(2, writer)
        val message = sent(generation)
        advanceTimeBy(1_001)
        val expiry = async { generation.service.checkExpiredAcks() }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        expiry.await()
        assertEquals(true, writer.committed)
        assertUncertain(generation, writer, message)
    }

    @Test fun throwBeforeRoomCommitStillAllowsTheRegisteredAckToDeliver() = runTest(scheduler) {
        val writer = FailedWriter().apply { commit = false }
        val generation = RadioGeneration(3, writer)
        val message = sent(generation)
        val failure = async { runCatching { generation.service.failAllPendingMessages() } }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        runCurrent()
        assertNull(writer.committed)
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(code.readUInt32LE(0), delivered.ackCode)
        assertEquals(123u, delivered.roundTripTime)
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u)), generation.statuses)
        assertTrue(generation.stop().isComplete)
    }

    @Test fun successfulRoomFailureReturnAllowsItsInFlightAckToDeliver() = runTest(scheduler) {
        val writer = FailedWriter().apply { throws = false }
        val generation = RadioGeneration(4, writer)
        val message = sent(generation)
        val failure = async { generation.service.failAllPendingMessages() }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        failure.await()
        runCurrent()
        assertEquals(true, writer.committed)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u)), generation.statuses)
        assertTrue(generation.stop().isComplete)
    }

    @Test fun independentlyTerminalFailedRowCannotBeResurrectedByAnInFlightAck() = runTest(scheduler) {
        val writer = FailedWriter().apply { throws = false }
        val generation = RadioGeneration(5, writer)
        val message = sent(generation)
        val key = EntityKey(radio, message.id)
        store.updateMessageStatus(key, MessageStatus.FAILED)
        val failure = async { generation.service.failAllPendingMessages() }
        runCurrent()
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        failure.await()
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(key))
        assertEquals(0, generation.service.pendingAckCount)
        assertTrue(generation.statuses.isEmpty())
        assertTrue(generation.stop().isComplete)
    }

    @Test fun genuinelyLateAckAfterCompletedFailureRetirementCannotResurrectRoomRow() = runTest(scheduler) {
        val generation = RadioGeneration(6)
        val message = sent(generation)
        generation.service.failAllPendingMessages()
        runCurrent()
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.Failed(message.id)), generation.statuses)
        generation.firmware.pushAck()
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, generation.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.Failed(message.id)), generation.statuses)
        assertTrue(generation.diagnostics.any { it is MessagingDiagnostic.UnmatchedAcknowledgement })
        assertTrue(generation.stop().isComplete)
    }

    @Test fun competingFailedWriterCannotBeMistakenForTheThrowingWritersCommit() = runTest(scheduler) {
        val writer = FailedWriter().apply { commit = false }
        val generation = RadioGeneration(7, writer)
        val message = sent(generation)
        val failure = async { runCatching { generation.service.failAllPendingMessages() } }
        runCurrent()
        assertTrue(writer.entered.isCompleted)
        store.updateMessageStatus(EntityKey(radio, message.id), MessageStatus.FAILED)
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        assertNull(writer.committed, "This writer did not commit the competing FAILED transition")
        assertUncertain(generation, writer, message)
    }

    @Test fun competingDeliveredWriterRemainsDeliveredWhenFailedWriterThrows() = runTest(scheduler) {
        val writer = FailedWriter()
        val generation = RadioGeneration(8, writer)
        val message = sent(generation)
        val failure = async { runCatching { generation.service.failAllPendingMessages() } }
        runCurrent()
        assertTrue(writer.entered.isCompleted)
        store.updateMessageAck(EntityKey(radio, message.id), code.readUInt32LE(0), MessageStatus.DELIVERED, 123u)
        registerAckWhileWriterIsHeld(generation, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        runCurrent()
        assertEquals(false, writer.committed, "Room must reject FAILED over the competing DELIVERED row")
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(0, generation.service.pendingAckCount)
        assertTrue(generation.stop().isComplete)
    }

    @Test fun replacementSessionCannotBorrowTheOldInstancesUncertaintyOrPendingAck() = runTest(scheduler) {
        val writer = FailedWriter()
        val old = RadioGeneration(9, writer)
        val message = sent(old)
        val failure = async { runCatching { old.service.failAllPendingMessages() } }
        runCurrent()
        registerAckWhileWriterIsHeld(old, writer)
        writer.release.complete(Unit)
        assertSame(writer.failure, failure.await().exceptionOrNull())
        assertUncertain(old, writer, message)
        val replacement = RadioGeneration(10)
        old.signals.replace(replacement.token)
        replacement.start()
        replacement.firmware.pushAck()
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, replacement.service.pendingAckCount)
        assertTrue(replacement.statuses.isEmpty())
        assertTrue(replacement.diagnostics.any { it is MessagingDiagnostic.UnmatchedAcknowledgement })
        assertTrue(replacement.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().none { it.cause === writer.failure })
        assertTrue(replacement.stop().isComplete)
    }

    private class Signals(token: SessionToken) : ConnectionSignals {
        private val value = MutableStateFlow(snapshot(token))
        override val snapshot get() = value
        fun replace(token: SessionToken) { value.value = snapshot(token) }
        override suspend fun subscribeTransitions(): ConnectionSubscription = object : ConnectionSubscription {
            override val initial = value.value
            override val transitions: Flow<ConnectionSnapshot> = flow { awaitCancellation() }
            override fun close() = Unit
        }
        companion object {
            fun snapshot(token: SessionToken) = ConnectionSnapshot(
                DeviceConnectionState.CONNECTED, ConnectionState.Connected, null,
                ConnectionIntent.WantsConnection(), token, null,
            )
        }
    }

    private inner class Firmware : MeshTransport {
        private val mock = MockTransport()
        override suspend fun isConnected() = mock.isConnected()
        override suspend fun connect() = mock.connect()
        override suspend fun disconnect() = mock.disconnect()
        override suspend fun receivedData() = mock.receivedData()
        override suspend fun supportsWriteWithoutResponse() = false
        override suspend fun supportsPipelinedReads() = false
        override suspend fun send(data: Bytes) {
            mock.send(data)
            when (CommandCode.fromRawValue(data[0])) {
                CommandCode.APP_START -> mock.simulateReceive(
                    ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
                        .appendUInt8(1u).appendUInt8(22u).appendUInt8(22u).append(self)
                        .appendInt32LE(0).appendInt32LE(0).appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
                        .appendUInt32LE(915_000u).appendUInt32LE(125_000u).appendUInt8(7u).appendUInt8(5u)
                        .append(Bytes.utf8("Test")).toBytes(),
                )
                CommandCode.SEND_MESSAGE -> mock.simulateReceive(
                    ByteWriter().appendUInt8(ResponseCode.MESSAGE_SENT.rawValue).appendUInt8(0u)
                        .append(code).appendUInt32LE(1u).toBytes(),
                )
                CommandCode.GET_CONTACT_BY_KEY -> mock.simulateError(2u)
                else -> throw AssertionError("Unexpected direct-ACK test command: ${data[0]}")
            }
        }
        suspend fun pushAck() {
            mock.simulateReceive(
                ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).append(code).appendUInt32LE(123u).toBytes(),
            )
        }
    }
}
