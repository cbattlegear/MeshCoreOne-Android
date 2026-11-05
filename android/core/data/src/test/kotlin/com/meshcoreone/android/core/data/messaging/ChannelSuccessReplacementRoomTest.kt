// AndroidOnly: WP-202 Real Room controls for WP-208 accepted-channel replacement and cleanup races.
package com.meshcoreone.android.core.data.messaging

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.MeshCoreSessionProtocol
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.protocol.session.MessageWriteDispatch
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.MessageService
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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
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
class ChannelSuccessReplacementRoomTest {
    private val scheduler = TestCoroutineScheduler()
    private val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val radio = RadioId(UUID.fromString("AAAAAAAA-1111-2222-3333-444444444444"))
    private val base = Instant.parse("2026-10-05T12:00:00Z")
    private val self = Bytes(ByteArray(32) { 1 })
    private val contact = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 2 }),
        name = "Peer", lastHeardTimestamp = 0u)
    private val clock = object : SessionClock {
        override val now: Duration get() = scheduler.currentTime.milliseconds
        override val wallClock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = base.plusMillis(scheduler.currentTime)
        }
        override suspend fun sleepFor(duration: Duration) { delay(duration) }
    }
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore
    private val generations = mutableListOf<GenerationFixture>()
    private val gates = mutableListOf<Gate>()

    @Before fun open() {
        val immediate = Executor { it.run() }
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MeshCoreDatabase::class.java)
            .addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        store = RoomPersistenceStore(db, process, clock.wallClock)
        runTest(scheduler) {
            store.saveDevice(DeviceDTO(radioId = radio, publicKey = self, nodeName = "Local"))
            store.saveContact(contact)
        }
    }

    @After fun close() {
        try {
            runTest(scheduler) {
                for (gate in gates) gate.release.complete(Unit)
                try {
                    for (g in generations.asReversed()) g.stop()
                } finally { store.close() }
            }
        } finally { process.cancel(); db.close() }
    }

    private fun key(id: UUID) = EntityKey(radio, id)
    private fun envelope(row: MessageDTO, resend: Boolean = false) =
        ChannelMessageEnvelope(row.id, requireNotNull(row.channelIndex), resend, row.text, row.timestamp, "Local")

    private suspend fun original(): MessageDTO {
        val row = MessageDTO(radioId = radio, channelIndex = 0u, text = "original channel",
            timestamp = base.epochSecond.toUInt(), createdAt = base, heardRepeats = 3, sendCount = 4)
        store.saveMessage(row)
        repeatFor(row)
        return row
    }

    private suspend fun repeatFor(row: MessageDTO): MessageRepeatDTO =
        MessageRepeatDTO(messageID = row.id, receivedAt = base, pathNodes = Bytes.of(42), pathLength = 1u)
            .also { store.saveMessageRepeat(radio, it) }

    private suspend fun replace(row: MessageDTO, direct: Boolean): Pair<MessageDTO, MessageRepeatDTO> {
        store.deleteMessage(key(row.id))
        val replacement = row.copy(text = "replacement", contactID = if (direct) contact.id else null,
            channelIndex = if (direct) null else 0u, status = MessageStatus.PENDING,
            timestamp = row.timestamp + 77u, heardRepeats = 9, sendCount = 12)
        store.saveMessage(replacement)
        return replacement to repeatFor(replacement)
    }

    private suspend fun assertPreserved(row: MessageDTO, repeat: MessageRepeatDTO) {
        assertEquals(row, store.fetchMessage(key(row.id)))
        assertEquals(listOf(repeat), store.fetchMessageRepeats(key(row.id)))
    }

    private inner class Gate {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        init { gates += this }
        suspend fun hold() { entered.complete(Unit); release.await() }
    }

    private suspend fun tolerateRejection(block: suspend () -> Unit) {
        try { block() }
        catch (_: MessageServiceException) { }
        catch (_: PersistenceStoreException) { }
    }

    @Test fun inlineAcceptedSendCannotMarkSameKeyDirectReplacementSent() = runTest(scheduler) {
        inlineReplacement(direct = true)
    }

    @Test fun inlineAcceptedSendCannotMarkSameKeyChannelReplacementSent() = runTest(scheduler) {
        inlineReplacement(direct = false)
    }

    private suspend fun TestScope.inlineReplacement(direct: Boolean) {
        val gate = Gate()
        val g = GenerationFixture()
        g.afterAccepted = { gate.hold() }
        g.start()
        val send = async { tolerateRejection { g.service.sendChannelMessage("inline", 0u, radio) } }
        try {
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertEquals(1, g.firmware.channelSent().size)
            val original = store.fetchMessages(radio, 0u).single()
            val (replacement, repeat) = replace(original, direct)
            gate.release.complete(Unit); runCurrent(); send.await()
            assertPreserved(replacement, repeat)
            assertEquals(1, g.firmware.channelSent().size)
        } finally { gate.release.complete(Unit); send.cancelAndJoin() }
    }

    @Test fun acceptedPendingSendCannotMarkSameKeyDirectReplacementSent() = runTest(scheduler) {
        pendingReplacement(direct = true, resend = false)
    }

    @Test fun acceptedResendCannotResetOrIncrementSameKeyChannelReplacement() = runTest(scheduler) {
        pendingReplacement(direct = false, resend = true)
    }

    private suspend fun TestScope.pendingReplacement(direct: Boolean, resend: Boolean) {
        val row = original()
        val gate = Gate()
        val g = GenerationFixture()
        g.afterAccepted = { gate.hold() }
        g.start()
        val send = async {
            tolerateRejection {
                if (resend) g.service.resendChannelMessage(row.id, preserveTimestamp = true)
                else g.service.sendPendingChannelMessage(row.id)
            }
        }
        try {
            runCurrent()
            assertTrue(gate.entered.isCompleted)
            assertEquals(1, g.firmware.channelSent().size)
            val (replacement, repeat) = replace(row, direct)
            gate.release.complete(Unit); runCurrent(); send.await()
            assertPreserved(replacement, repeat)
            assertEquals(1, g.firmware.channelSent().size)
        } finally { gate.release.complete(Unit); send.cancelAndJoin() }
    }

    @Test fun indexHeldAfterSentCannotDeleteSameIdReplacementPending() = runTest(scheduler) {
        cleanupReplacement(samePendingId = true)
    }

    @Test fun indexHeldAfterSentCannotDeleteNewSiblingPending() = runTest(scheduler) {
        cleanupReplacement(samePendingId = false)
    }

    private suspend fun TestScope.cleanupReplacement(samePendingId: Boolean) {
        val row = original()
        val gate = Gate()
        val g = GenerationFixture(indexer = OutgoingChannelReactionIndexer { _, _, _, _, _ -> gate.hold() })
        g.start()
        g.queue.enqueueChannel(envelope(row))
        g.drain()
        assertTrue(gate.entered.isCompleted)
        assertEquals(1, g.firmware.channelSent().size)
        val sent = assertNotNull(store.fetchMessage(key(row.id)))
        assertEquals(MessageStatus.SENT, sent.status)
        val captured = store.fetchPendingSendsForMessage(key(row.id)).single()
        val replacement = captured.copy(id = if (samePendingId) captured.id else UUID.randomUUID(),
            messageText = "replacement pending", attemptCount = 41, sequence = 42)
        store.upsertPendingSend(replacement)
        val pendingBefore = store.fetchPendingSendsForMessage(key(row.id))
        gate.release.complete(Unit); runCurrent()
        assertEquals(pendingBefore, store.fetchPendingSendsForMessage(key(row.id)))
        assertEquals(sent, store.fetchMessage(key(row.id)))
        assertEquals(1, g.firmware.channelSent().size)
    }

    @Test fun failedPostAcceptanceCommitCannotTransferStampToRecreatedMessageOnRetry() = runTest(scheduler) {
        val row = original()
        val fault = CommitFault()
        val g = GenerationFixture(persistence = fault)
        g.start()
        g.queue.enqueueChannel(envelope(row, resend = true)); g.drain()
        assertEquals(1, fault.failures)
        assertEquals(1, g.firmware.channelSent().size)
        val captured = store.fetchPendingSendsForMessage(key(row.id)).single()
        val (replacement, repeat) = replace(row, direct = false)
        val pending = captured.copy(messageText = replacement.text, messageTimestamp = replacement.timestamp,
            attemptCount = 19, sequence = 20)
        store.upsertPendingSend(pending)
        g.queue.transportDidOpen(); runCurrent()
        assertPreserved(replacement, repeat)
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(key(row.id)))
        assertEquals(1, g.firmware.channelSent().size)
    }

    @Test fun failedIndexCannotTransferCompletedCacheToRecreatedMessageOnRetry() = runTest(scheduler) {
        val row = original()
        var indexCalls = 0
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("index fault after accepted send"))
        val g = GenerationFixture(indexer = OutgoingChannelReactionIndexer { _, _, _, _, _ ->
            indexCalls++
            if (indexCalls == 1) throw fault
        })
        g.start()
        g.queue.enqueueChannel(envelope(row)); g.drain()
        assertEquals(1, indexCalls)
        assertEquals(MessageStatus.SENT, store.fetchMessage(key(row.id))?.status)
        val captured = store.fetchPendingSendsForMessage(key(row.id)).single()
        val (replacement, repeat) = replace(row, direct = false)
        val pending = captured.copy(messageText = replacement.text, messageTimestamp = replacement.timestamp,
            attemptCount = 29, sequence = 30)
        store.upsertPendingSend(pending)
        g.queue.transportDidOpen(); runCurrent()
        assertPreserved(replacement, repeat)
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(key(row.id)))
        assertEquals(1, indexCalls)
        assertEquals(1, g.firmware.channelSent().size)
    }

    @Test fun originalIncarnationSendAndResendCommitOwnStatusCountersAndRepeats() = runTest(scheduler) {
        val row = original()
        val g = GenerationFixture()
        g.start()
        g.service.sendPendingChannelMessage(row.id)
        assertEquals(row.copy(status = MessageStatus.SENT), store.fetchMessage(key(row.id)))
        assertEquals(1, store.fetchMessageRepeats(key(row.id)).size)
        assertEquals(row.timestamp, g.service.resendChannelMessage(row.id, preserveTimestamp = true))
        assertEquals(row.copy(status = MessageStatus.SENT, sendCount = 5, heardRepeats = 0),
            store.fetchMessage(key(row.id)))
        assertTrue(store.fetchMessageRepeats(key(row.id)).isEmpty())
        assertEquals(2, g.firmware.channelSent().size)
    }

    @Test fun originalQueuedSendIndexesBeforeDeletingOnlyItsCapturedPending() = runTest(scheduler) {
        val row = original()
        val sentinel = MessageDTO(radioId = radio, channelIndex = 1u, text = "unrelated",
            timestamp = row.timestamp, createdAt = base)
        store.saveMessage(sentinel)
        val foreignPending = PendingSendDTO.fromEnvelope(envelope(sentinel), radio).copy(sequence = 90)
        var indexed = 0
        val g = GenerationFixture(indexer = OutgoingChannelReactionIndexer { id, channel, name, text, timestamp ->
            assertEquals(row.id, id); assertEquals(0u.toUByte(), channel); assertEquals("Local", name)
            assertEquals(row.text, text); assertEquals(row.timestamp, timestamp)
            assertEquals(MessageStatus.SENT, store.fetchMessage(key(row.id))?.status)
            assertEquals(1, store.fetchPendingSendsForMessage(key(row.id)).size)
            indexed++
        })
        g.start()
        g.queue.enqueueChannel(envelope(row))
        // Add an unrelated envelope after hydration, so this queue never claims it.
        g.queue.hydrate(); runCurrent()
        store.upsertPendingSend(foreignPending)
        g.signals.set(DeviceConnectionState.READY); runCurrent()
        assertEquals(1, indexed)
        assertTrue(store.fetchPendingSendsForMessage(key(row.id)).isEmpty())
        assertEquals(listOf(foreignPending), store.fetchPendingSendsForMessage(key(sentinel.id)))
        assertEquals(sentinel, store.fetchMessage(key(sentinel.id)))
        assertEquals(1, g.firmware.channelSent().size)
    }

    @Test fun exactAcceptedCompletionRetryDoesNotResendBumpOrDoubleCount() = runTest(scheduler) {
        val row = original()
        val fault = CommitFault(afterCommit = true)
        val g = GenerationFixture(persistence = fault)
        g.start()
        g.queue.enqueueChannel(envelope(row, resend = true)); g.drain()
        assertEquals(1, fault.failures)
        assertEquals(1, g.firmware.channelSent().size)
        assertEquals(MessageStatus.SENT, store.fetchMessage(key(row.id))?.status)
        assertEquals(1L, store.fetchPendingSendsForMessage(key(row.id)).single().attemptCount)
        g.queue.transportDidOpen(); runCurrent()
        assertEquals(row.copy(status = MessageStatus.SENT, sendCount = 5, heardRepeats = 0),
            store.fetchMessage(key(row.id)))
        assertTrue(store.fetchMessageRepeats(key(row.id)).isEmpty())
        assertTrue(store.fetchPendingSendsForMessage(key(row.id)).isEmpty())
        assertEquals(1, g.firmware.channelSent().size)
        assertEquals(2, fault.requests.size)
        assertEquals(1, fault.requests.distinct().size)
    }

    @Test fun cleanupHeldAfterIndexReturnCannotDeleteReplacementPending() = runTest(scheduler) {
        val row = original()
        val fault = CleanupGate()
        var indexed = 0
        val g = GenerationFixture(persistence = fault,
            indexer = OutgoingChannelReactionIndexer { _, _, _, _, _ -> indexed++ })
        g.start()
        g.queue.enqueueChannel(envelope(row)); g.drain()
        assertTrue(fault.gate.entered.isCompleted)
        assertEquals(1, indexed)
        val captured = store.fetchPendingSendsForMessage(key(row.id)).single()
        val replacement = captured.copy(messageText = "replacement after index", attemptCount = 97)
        store.upsertPendingSend(replacement)
        fault.gate.release.complete(Unit); runCurrent()
        assertEquals(listOf(replacement), store.fetchPendingSendsForMessage(key(row.id)))
        assertEquals(row.copy(status = MessageStatus.SENT), store.fetchMessage(key(row.id)))
        assertEquals(1, g.firmware.channelSent().size)
    }

    @Test fun committedAcceptedPendingSurvivesFailedIndexAndRetirementWithoutReplacementGenerationWireSend() =
        runTest(scheduler) {
            val row = original()
            val first = GenerationFixture(indexer = OutgoingChannelReactionIndexer { _, _, _, _, _ ->
                throw PersistenceStoreException(PersistenceStoreError.SaveFailed("index unavailable"))
            })
            first.start()
            first.queue.enqueueChannel(envelope(row)); first.drain()
            assertEquals(1, first.firmware.channelSent().size)
            assertEquals(MessageStatus.SENT, store.fetchMessage(key(row.id))?.status)
            val before = store.fetchPendingSendsForMessage(key(row.id))
            assertEquals(1L, before.single().attemptCount)
            first.stop()
            val second = GenerationFixture()
            second.start(); second.drain()
            assertTrue(second.firmware.channelSent().isEmpty())
            assertEquals(before, store.fetchPendingSendsForMessage(key(row.id)))
            assertEquals(row.copy(status = MessageStatus.SENT), store.fetchMessage(key(row.id)))
        }

    @Test fun genuineAcceptedButUncommittedReturnRetainsSuppressionAcrossOrdinaryGenerationReplacement() =
        runTest(scheduler) {
            val row = original()
            val unavailable = object : PersistenceStoreProtocol by store, ChannelSendCompletionAuthority by store {
                override suspend fun recordChannelSendCompleted(
                    attempt: ChannelFailureAttempt, completionRequestId: UUID,
                    mode: ChannelSendCompletionMode, acceptedTimestamp: UInt,
                ): ChannelSendCompletionResult =
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("completion unavailable"))
            }
            val first = GenerationFixture(persistence = unavailable)
            first.start()
            first.queue.enqueueChannel(envelope(row)); first.drain()
            assertEquals(1, first.firmware.channelSent().size)
            val before = store.fetchPendingSendsForMessage(key(row.id))
            val beforeMessage = store.fetchMessage(key(row.id))
            assertEquals(1L, before.single().attemptCount)
            assertEquals(MessageStatus.PENDING, beforeMessage?.status)
            first.stop()
            val second = GenerationFixture()
            second.start(); second.drain()
            assertTrue(second.firmware.channelSent().isEmpty())
            assertEquals(before, store.fetchPendingSendsForMessage(key(row.id)))
            assertEquals(beforeMessage, store.fetchMessage(key(row.id)))
        }

    @Test fun genuineNewPendingLifecycleAfterRetirementCanSendSameMessageWithoutInheritingOldCompletion() =
        runTest(scheduler) {
            val row = original()
            val first = GenerationFixture(indexer = OutgoingChannelReactionIndexer { _, _, _, _, _ ->
                throw PersistenceStoreException(PersistenceStoreError.SaveFailed("index unavailable"))
            })
            first.start()
            first.queue.enqueueChannel(envelope(row)); first.drain()
            val oldPending = store.fetchPendingSendsForMessage(key(row.id)).single()
            assertEquals(1, first.firmware.channelSent().size)
            first.stop()
            store.deletePendingSend(key(oldPending.id))
            store.upsertPendingSend(oldPending.copy(isResend = true, attemptCount = 0))
            val second = GenerationFixture()
            second.start(); second.drain()
            assertEquals(1, second.firmware.channelSent().size)
            assertEquals(row.copy(status = MessageStatus.SENT, sendCount = 5, heardRepeats = 0),
                store.fetchMessage(key(row.id)))
            assertTrue(store.fetchPendingSendsForMessage(key(row.id)).isEmpty())
        }

    private inner class CommitFault(private val afterCommit: Boolean = false) :
        PersistenceStoreProtocol by store, ChannelSendCompletionAuthority by store {
        var failures = 0
        val requests = mutableListOf<Pair<ChannelFailureAttempt, UUID>>()
        override suspend fun recordChannelSendCompleted(
            attempt: ChannelFailureAttempt, completionRequestId: UUID,
            mode: ChannelSendCompletionMode, acceptedTimestamp: UInt,
        ): ChannelSendCompletionResult {
            requests += attempt to completionRequestId
            if (failures == 0) {
                failures++
                if (afterCommit) store.recordChannelSendCompleted(attempt, completionRequestId, mode, acceptedTimestamp)
                throw PersistenceStoreException(PersistenceStoreError.SaveFailed("post-acceptance completion fault"))
            }
            return store.recordChannelSendCompleted(attempt, completionRequestId, mode, acceptedTimestamp)
        }
    }

    private inner class CleanupGate : PersistenceStoreProtocol by store, ChannelSendCompletionAuthority by store {
        val gate = Gate()
        override suspend fun completeChannelSendPending(
            completion: ChannelSendCompletion, pendingCompletionRequestId: UUID,
        ): ChannelSendPendingCompletionResult {
            gate.hold()
            return store.completeChannelSendPending(completion, pendingCompletionRequestId)
        }
    }

    private inner class GenerationFixture(
        persistence: PersistenceStoreProtocol = store,
        indexer: OutgoingChannelReactionIndexer = OutgoingChannelReactionIndexer { _, _, _, _, _ -> },
    ) {
        private val scope = CoroutineScope(SupervisorJob(process.coroutineContext[Job]) + StandardTestDispatcher(scheduler))
        val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(generations.size.toLong() + 1), radio)
        val signals = Signals(token)
        val firmware = Firmware()
        var afterAccepted: suspend () -> Unit = {}
        private val raw = MeshCoreSession(firmware, SessionConfiguration(defaultTimeout = 2.0), clock, scope.coroutineContext)
        private val session = object : MeshCoreSessionProtocol by raw {
            override suspend fun sendChannelMessage(channel: UByte, text: String, timestamp: Instant) {
                raw.sendChannelMessage(channel, text, timestamp)
                afterAccepted()
            }
            override suspend fun sendChannelMessage(
                channel: UByte, text: String, timestamp: Instant, dispatch: MessageWriteDispatch,
            ) {
                raw.sendChannelMessage(channel, text, timestamp, dispatch)
                afterAccepted()
            }
        }
        val diagnostics = mutableListOf<MessagingDiagnostic>()
        val service = MessageService(token, self, session, persistence, signals, scope, clock = clock,
            reporter = MessagingIssueReporter { diagnostics += it }, directAckAuthority = store,
            directPendingCompletionAuthority = store, directAckRadioExclusionAuthority = store,
            directAckRadioFailureAuthority = store, channelFailureAuthority = store,
            channelSendCompletionAuthority = persistence as? ChannelSendCompletionAuthority ?: store,
            channelQueueAdmissionAuthority = persistence as? ChannelQueueAdmissionAuthority ?: store)
        val queue = ChatSendQueueService(token, persistence, service, MessagingChannelQuery { null }, indexer,
            signals, scope, clock = clock, reporter = MessagingIssueReporter { diagnostics += it })
        init { generations += this }
        suspend fun start() { raw.start() }
        suspend fun drain() {
            queue.hydrate()
            scheduler.runCurrent()
            signals.set(DeviceConnectionState.READY)
            scheduler.runCurrent()
        }
        suspend fun stop() { queue.shutdown(); service.close(); raw.stop(); scope.cancel() }
    }

    private class Signals(private val token: SessionToken) : ConnectionSignals {
        private val value = MutableStateFlow(snapshot(token, DeviceConnectionState.CONNECTED))
        override val snapshot: StateFlow<ConnectionSnapshot> get() = value
        private val subscriptions = mutableListOf<Channel<ConnectionSnapshot>>()
        fun set(state: DeviceConnectionState) {
            val next = snapshot(token, state)
            value.value = next
            for (subscription in subscriptions) subscription.trySend(next).getOrThrow()
        }
        override suspend fun subscribeTransitions(): ConnectionSubscription {
            val channel = Channel<ConnectionSnapshot>(Channel.UNLIMITED)
            subscriptions += channel
            return object : ConnectionSubscription {
                override val initial = value.value
                override val transitions = flow { try { for (next in channel) emit(next) } finally { close() } }
                override fun close() { subscriptions.remove(channel); channel.cancel() }
            }
        }
        companion object {
            private fun snapshot(token: SessionToken, state: DeviceConnectionState) =
                ConnectionSnapshot(state, ConnectionState.Connected, null, ConnectionIntent.WantsConnection(), token, null)
        }
    }

    private inner class Firmware : MeshTransport {
        private val mock = MockTransport()
        fun channelSent(): List<Bytes> = mock.sentData.filter { it[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue }
        override suspend fun isConnected() = mock.isConnected()
        override suspend fun connect() = mock.connect()
        override suspend fun disconnect() = mock.disconnect()
        override suspend fun receivedData() = mock.receivedData()
        override suspend fun supportsWriteWithoutResponse() = false
        override suspend fun supportsPipelinedReads() = false
        override suspend fun send(data: Bytes) {
            mock.send(data)
            when (CommandCode.fromRawValue(data[0])) {
                CommandCode.APP_START -> mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
                    .appendUInt8(1u).appendUInt8(22u).appendUInt8(22u).append(self)
                    .appendInt32LE(0).appendInt32LE(0).appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
                    .appendUInt32LE(915_000u).appendUInt32LE(125_000u).appendUInt8(7u).appendUInt8(5u)
                    .append(Bytes.utf8("Test")).toBytes())
                CommandCode.SEND_CHANNEL_MESSAGE -> mock.simulateOK()
                else -> throw AssertionError("Unexpected channel-control command: ${data[0]}")
            }
        }
    }
}
