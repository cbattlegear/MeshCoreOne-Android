// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceAttemptCountTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceSendDMBookkeepingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceACKTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceSendTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: WP-208 actual Room/process-store and real-session consumers on simulated SDK31, not HIL.
package com.meshcoreone.android.core.data.messaging

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.contracts.domain.errors.MessagePollingException
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.data.backup.backupEnvelope
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.data.repository.OriginalCase
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.MessageService
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.messaging.MessagePollingService
import com.meshcoreone.android.core.services.messaging.PoolBackoffConfig
import com.meshcoreone.android.core.services.contacts.ChannelService
import java.security.MessageDigest
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
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
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
class MessagingRoomIntegrationTest {
    private val scheduler = TestCoroutineScheduler()
    private val process = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
    private val radio = RadioId(UUID.fromString("AAAAAAAA-1111-2222-3333-444444444444"))
    private val other = RadioId(UUID.fromString("BBBBBBBB-1111-2222-3333-444444444444"))
    private val self = Bytes(ByteArray(32) { 1 })
    private val target = Bytes(ByteArray(32) { 0x22 })
    private val base = Instant.parse("2026-10-05T12:00:00Z")
    private var wallOffsetMillis = 0L
    private val clock = object : SessionClock {
        override val now: Duration get() = scheduler.currentTime.milliseconds
        override val wallClock: Clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneOffset.UTC
            override fun withZone(zone: ZoneId): Clock = this
            override fun instant(): Instant = base.plusMillis(scheduler.currentTime + wallOffsetMillis)
        }
        override suspend fun sleepFor(duration: Duration) { delay(duration) }
    }
    private lateinit var db: MeshCoreDatabase
    private lateinit var store: RoomPersistenceStore
    private lateinit var contact: ContactDTO
    private val generations = mutableListOf<RadioGeneration>()
    private val pollers = mutableListOf<MessagePollingService>()
    private fun database(name: String? = null): MeshCoreDatabase {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val builder = if (name == null) Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            else Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
        val immediate = Executor { it.run() }
        return builder.allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
    }
    @Before fun open() {
        db = database()
        store = RoomPersistenceStore(db, process, clock.wallClock)
        contact = ContactDTO(radioId = radio, publicKey = target, name = "Peer", lastHeardTimestamp = 0u)
    }
    @After fun close() {
        try {
            runTest(scheduler) {
                var failure: Exception? = null
                suspend fun release(action: suspend () -> Unit) {
                    try { action() }
                    catch (cause: Exception) {
                        val first = failure
                        if (first == null) failure = cause else first.addSuppressed(cause)
                    }
                }
                withContext(NonCancellable) {
                    pollers.asReversed().forEach { release { it.close() } }
                    generations.asReversed().forEach { release { it.stop() } }
                    release { store.close() }
                }
                failure?.let { throw it }
            }
        }
        finally { process.cancel(); db.close() }
    }
    private suspend fun seed() {
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = self, nodeName = "Local"))
        store.saveContact(contact)
    }
    private inner class RadioGeneration(
        number: Long,
        persistence: PersistenceStoreProtocol = store,
        config: com.meshcoreone.android.core.services.messaging.MessageServiceConfig =
            com.meshcoreone.android.core.services.messaging.MessageServiceConfig(),
        sessionTimeout: Double = 2.0,
        channelQuery: MessagingChannelQuery = MessagingChannelQuery { null },
        queueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    ) {
        val scope = CoroutineScope(SupervisorJob(process.coroutineContext[Job]) + StandardTestDispatcher(scheduler))
        val token = SessionToken(ProcessEpoch(UUID.fromString("CCCCCCCC-1111-2222-3333-444444444444")), Generation(number), radio)
        val signals = RoomSignals(token)
        val firmware = RoomFirmware()
        val diagnostics = mutableListOf<MessagingDiagnostic>()
        val session = MeshCoreSession(firmware, SessionConfiguration(defaultTimeout = sessionTimeout), clock, scope.coroutineContext)
        val service = MessageService(token, self, session, persistence, signals, scope, config, clock,
            reporter = MessagingIssueReporter { diagnostics += it })
        val queue = ChatSendQueueService(token, persistence, service, channelQuery,
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> }, signals, scope, queueConfig, clock,
            reporter = MessagingIssueReporter { diagnostics += it })
        init { generations += this }
        suspend fun start() { session.start() }
        suspend fun stop() { queue.shutdown(); service.close(); session.stop(); scope.cancel() }
        fun poller() = MessagePollingService(token, session, store, signals, scope, clock).also { pollers += it }
    }

    @OriginalCase("ChatSendQueueServiceAttemptCountTests::fresh send: row with attemptCount=0 first drain uses fresh timestamp()")
    @OriginalCase("ChatSendQueueServiceAttemptCountTests::process restart: row with attemptCount=1 rehydrates with preserveTimestamp=true()")
    @Test fun freshAndRecoveredAttemptsUseTheActualCommittedCounterAndWireTimestamp() = runTest(scheduler) {
        seed()
        val first = RadioGeneration(1); first.start()
        val fresh = first.service.createPendingMessage("fresh", contact)
        val originalTimestamp = base.minusSeconds(3_600).epochSecond.toUInt()
        store.updateMessageTimestamp(EntityKey(radio, fresh.id), originalTimestamp)
        val row = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(fresh.id, contact.id), radio)
        store.upsertPendingSend(row.copy(sequence = 1))
        first.signals.set(DeviceConnectionState.CONNECTED)
        first.queue.hydrate(); runCurrent()
        assertEquals(0L, store.fetchPendingSends(radio).single().attemptCount)
        first.firmware.error = 3u
        first.signals.set(DeviceConnectionState.READY); runCurrent(); advanceTimeBy(3501); runCurrent()
        assertEquals(1L, store.fetchPendingSends(radio).single().attemptCount)
        val stamp = assertNotNull(store.fetchMessage(EntityKey(radio, fresh.id))).timestamp
        assertNotEquals(originalTimestamp, stamp)
        first.stop()
        val second = RadioGeneration(2); second.start(); second.firmware.error = 3u
        second.signals.set(DeviceConnectionState.CONNECTED); second.queue.hydrate(); runCurrent()
        second.signals.set(DeviceConnectionState.READY); runCurrent(); advanceTimeBy(3501); runCurrent()
        assertEquals(2L, store.fetchPendingSends(radio).single().attemptCount)
        assertEquals(stamp, second.firmware.sent().first().readUInt32LE(3))
        assertEquals(stamp, assertNotNull(store.fetchMessage(EntityKey(radio, fresh.id))).timestamp)
        second.stop()
    }

    @Test fun warmUpPurgesOnlyLegacyNullAndForgottenRadiosAndPreservesCurrentZeroRows() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "pending", timestamp = 100u)
        store.saveMessage(message)
        val current = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(current.copy(sequence = 1))
        store.upsertPendingSend(current.copy(id = UUID.randomUUID(), sequence = 2, attemptCount = null))
        store.upsertPendingSend(current.copy(id = UUID.randomUUID(), radioId = other, sequence = 1))
        store.warmUp()
        assertEquals(listOf(current.copy(sequence = 1)), store.fetchPendingSends(radio))
        assertTrue(store.fetchPendingSends(other).isEmpty())
    }

    @Test fun coldStoreReopenRecoversTheSameRadioMessageAndPendingSendWithoutRewritingSortDate() = runTest(scheduler) {
        // Robolectric isolates each test directory; native Windows SQLite still limits path length.
        val name = "cold.db"
        store.close(); db.close(); db = database(name); store = RoomPersistenceStore(db, process, clock.wallClock)
        seed()
        val sort = base.minusSeconds(86400)
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "cold", timestamp = 100u,
            createdAt = base.minusSeconds(100), sortDate = sort)
        store.saveMessage(message)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1, attemptCount = 1)
        store.upsertPendingSend(pending)
        store.close(); db.close(); db = database(name); store = RoomPersistenceStore(db, process, clock.wallClock)
        store.warmUp()
        assertEquals(pending, store.fetchPendingSends(radio).single())
        assertEquals(sort, assertNotNull(store.fetchMessage(EntityKey(radio, message.id))).sortDate)
        val g = RadioGeneration(3); g.start(); g.firmware.error = 3u
        g.signals.set(DeviceConnectionState.CONNECTED); g.queue.hydrate(); runCurrent()
        g.signals.set(DeviceConnectionState.READY); runCurrent(); advanceTimeBy(3501); runCurrent()
        assertEquals(100u, g.firmware.sent().first().readUInt32LE(3))
        assertEquals(sort, assertNotNull(store.fetchMessage(EntityKey(radio, message.id))).sortDate); g.stop()
    }

    @OriginalCase("ChatSendQueueServiceTests::hydrate fetch returns rows in sequence order()")
    @Test fun actualHydrationDrainsOneRadioInFIFOOrderAndLeavesTheOtherPartitionUntouched() = runTest(scheduler) {
        seed(); store.saveDevice(DeviceDTO(radioId = other, publicKey = target, nodeName = "Other"))
        val g = RadioGeneration(4); g.start()
        val messageIds = mutableListOf<UUID>()
        for ((sequence, text) in listOf(3L to "three", 1L to "one", 2L to "two")) {
            val m = MessageDTO(radioId = radio, contactID = contact.id, text = text, timestamp = 100u)
            messageIds += m.id
            store.saveMessage(m); store.upsertPendingSend(PendingSendDTO.fromEnvelope(
                DirectMessageEnvelope(m.id, contact.id), radio).copy(sequence = sequence))
        }
        val foreignMessage = MessageDTO(id = messageIds.first(), radioId = other, contactID = contact.id,
            text = "same UUID on another radio", timestamp = 100u, status = MessageStatus.RETRYING)
        store.saveMessage(foreignMessage)
        val foreign = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(foreignMessage.id, contact.id, true), other)
            .copy(sequence = 1, attemptCount = 2)
        store.upsertPendingSend(foreign)
        assertEquals(listOf(1L, 2L, 3L), store.fetchPendingSends(radio).map { it.sequence })
        g.signals.set(DeviceConnectionState.READY); g.queue.hydrate(); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(listOf("one", "two", "three"), g.firmware.sent().map { it.slice(13, it.size).toByteArray().toString(Charsets.UTF_8) })
        assertTrue(store.fetchPendingSends(radio).isEmpty()); assertEquals(listOf(foreign), store.fetchPendingSends(other))
        assertEquals(foreignMessage, store.fetchMessage(EntityKey(other, foreignMessage.id)))
        g.stop()
    }

    @OriginalCase("ChatSendQueueServiceTests::hydrate replays persisted PendingSend rows and drains them()")
    @Test fun actualHydrationDropsTheMissingContactEnvelopeWithoutAnyWireSend() = runTest(scheduler) {
        exerciseMissingContactHydration(1, onceOnly = false)
    }

    @OriginalCase("ChatSendQueueServiceTests::hydrate processes only the service's own radio rows()")
    @Test fun actualMissingContactHydrationDrainsBothLocalRowsAndPreservesTheForeignEnvelope() = runTest(scheduler) {
        exerciseMissingContactHydration(2, onceOnly = false)
    }

    @OriginalCase("ChatSendQueueServiceTests::hydrate runs once per service instance()")
    @Test fun theActualSecondHydrateCannotEnqueueANewlyPersistedRowOnTheSameService() = runTest(scheduler) {
        exerciseMissingContactHydration(1, onceOnly = true)
    }

    private suspend fun TestScope.exerciseMissingContactHydration(count: Int, onceOnly: Boolean) {
        seed()
        val rows = (1..count).map {
            PendingSendDTO.fromEnvelope(DirectMessageEnvelope(UUID.randomUUID(), UUID.randomUUID()), radio)
                .copy(sequence = it.toLong())
        }
        rows.forEach { store.upsertPendingSend(it) }
        val foreign = rows.first().copy(radioId = other)
        store.upsertPendingSend(foreign)
        val g = RadioGeneration(70)
        g.start()
        g.signals.set(DeviceConnectionState.READY)
        g.queue.hydrate()
        runCurrent()
        g.queue.awaitDrainCompletion()
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertEquals(listOf(foreign), store.fetchPendingSends(other))
        assertTrue(g.firmware.sent().isEmpty())
        if (onceOnly) {
            val next = rows.first().copy(id = UUID.randomUUID(), messageID = UUID.randomUUID(), sequence = 2)
            store.upsertPendingSend(next)
            g.queue.hydrate()
            runCurrent()
            g.queue.awaitDrainCompletion()
            assertEquals(listOf(next), store.fetchPendingSends(radio))
        }
        g.stop()
    }

    @OriginalCase("ChatSendQueueServiceAttemptCountTests::bump failure parks envelope, preserves row, does not call sendPendingDirectMessage()")
    @Test fun anActualSqlCounterRollbackParksBeforeChangingTheWireTimestampOrSending() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(71)
        g.start()
        val created = g.service.createPendingMessage("Hello", contact)
        val original = created.copy(timestamp = base.minusSeconds(3_600).epochSecond.toUInt())
        store.saveMessage(original)
        val row = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(original.id, contact.id), radio).copy(sequence = 1)
        store.upsertPendingSend(row)
        val events = g.service.statusEvents()
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_queue_bump AFTER UPDATE ON pending_sends " +
            "BEGIN SELECT RAISE(FAIL, 'controlled queue bump rollback'); END")
        try {
            g.queue.hydrate()
            runCurrent()
            g.signals.set(DeviceConnectionState.READY)
            runCurrent()
            advanceUntilParked(g, original.id)
            assertEquals(listOf(row), store.fetchPendingSends(radio))
            assertEquals(original, store.fetchMessage(EntityKey(radio, original.id)))
            assertTrue(g.firmware.sent().isEmpty())
            assertTrue(completedStatuses(g, events).isEmpty())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_queue_bump")
            events.close()
            g.stop()
        }
    }

    @OriginalCase("ChatSendQueueServiceTests::transient send error preserves the persisted row while parked on the trigger()")
    @Test fun theActualTransientQueueFailureKeepsItsCommittedEnvelopeWhileParked() = runTest(scheduler) {
        withParkedDm { g, message, row, events ->
            assertEquals(listOf(row.copy(attemptCount = 1)), store.fetchPendingSends(radio))
            assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
            assertEquals(3, g.firmware.sent().size)
            assertTrue(completedStatuses(g, events).isEmpty())
        }
    }

    @OriginalCase("ChatSendQueueServiceTests::Transient DM send error must not broadcast .failed()")
    @Test fun theActualTransientQueueFailureNeverPublishesAFailedBubble() = runTest(scheduler) {
        withParkedDm { g, message, row, events ->
            advanceTimeBy(300)
            runCurrent()
            assertEquals(listOf(row.copy(attemptCount = 1)), store.fetchPendingSends(radio))
            assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
            assertTrue(completedStatuses(g, events).none { it is MessageStatusEvent.Failed })
        }
    }

    @OriginalCase("ChatSendQueueServiceTests::Terminal DM send error broadcasts .failed exactly once()")
    @Test fun theActualIllegalArgumentReplyFailsItsRoomRowAndPublishesExactlyOnce() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(72)
        g.start()
        g.firmware.error = 6u
        val message = g.service.createPendingMessage("Hello", contact)
        val row = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1)
        store.upsertPendingSend(row)
        val events = g.service.statusEvents()
        g.queue.hydrate()
        runCurrent()
        g.signals.set(DeviceConnectionState.READY)
        runCurrent()
        g.queue.awaitDrainCompletion()
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertEquals(1, g.firmware.sent().size)
        assertEquals(listOf(MessageStatusEvent.Failed(message.id)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("ChatSendQueueServiceTests::observeConnectionState fires the trigger for an already-ready initial state()")
    @Test fun rebindingTheActualAlreadyReadySubscriptionWakesItsParkedEnvelopeWithoutANewEdge() = runTest(scheduler) {
        withParkedDm { g, _, row, events ->
            g.queue.observeConnectionState()
            runCurrent()
            advanceTimeBy(300)
            runCurrent()
            assertEquals(listOf(row.copy(attemptCount = 2)), store.fetchPendingSends(radio))
            assertEquals(6, g.firmware.sent().size)
            assertTrue(completedStatuses(g, events).isEmpty())
        }
    }

    @OriginalCase("ChatSendQueueServiceTests::observeConnectionState keeps a drain parked through .connected and wakes it on .ready()")
    @Test fun actualConnectedStateCannotWakeTheParkButTheNextReadyEdgeCan() = runTest(scheduler) {
        withParkedDm { g, _, row, events ->
            g.signals.set(DeviceConnectionState.CONNECTED)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(row.copy(attemptCount = 1)), store.fetchPendingSends(radio))
            g.signals.set(DeviceConnectionState.READY)
            runCurrent()
            advanceTimeBy(300)
            runCurrent()
            assertEquals(listOf(row.copy(attemptCount = 2)), store.fetchPendingSends(radio))
            assertTrue(completedStatuses(g, events).isEmpty())
        }
    }

    @OriginalCase("ChatSendQueueServiceTests::connection-state ramp fires the trigger exactly once()")
    @Test fun theActualDisconnectedConnectedSyncingReadyRampWakesExactlyOneMoreAttempt() = runTest(scheduler) {
        withParkedDm { g, _, row, events ->
            for (state in listOf(DeviceConnectionState.DISCONNECTED, DeviceConnectionState.CONNECTED,
                DeviceConnectionState.SYNCING, DeviceConnectionState.READY)) g.signals.set(state)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(row.copy(attemptCount = 2)), store.fetchPendingSends(radio))
            assertEquals(6, g.firmware.sent().size)
            assertTrue(completedStatuses(g, events).isEmpty())
        }
    }

    private suspend fun TestScope.withParkedDm(
        block: suspend (RadioGeneration, MessageDTO, PendingSendDTO, SessionEventSubscription<MessageStatusEvent>) -> Unit,
    ) {
        seed()
        val g = RadioGeneration(73, config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01)))
        g.start()
        g.firmware.error = 3u
        val message = g.service.createPendingMessage("Hello", contact)
        val row = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1)
        store.upsertPendingSend(row)
        val foreignMessage = message.copy(radioId = other)
        val foreignRow = row.copy(radioId = other)
        store.saveMessage(foreignMessage)
        store.upsertPendingSend(foreignRow)
        val events = g.service.statusEvents()
        try {
            g.queue.hydrate()
            runCurrent()
            g.signals.set(DeviceConnectionState.READY)
            runCurrent()
            advanceUntilParked(g, message.id)
            assertEquals(listOf(row.copy(attemptCount = 1)), store.fetchPendingSends(radio))
            block(g, message, row, events)
            assertEquals(foreignMessage, store.fetchMessage(EntityKey(other, message.id)))
            assertEquals(listOf(foreignRow), store.fetchPendingSends(other))
        } finally {
            events.close()
            g.stop()
        }
    }

    private fun TestScope.advanceUntilParked(g: RadioGeneration, id: UUID) {
        val deadline = scheduler.currentTime + 10_000
        fun parked() = g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any { it.operation == "park.$id" }
        while (!parked() && scheduler.currentTime < deadline) {
            advanceTimeBy(1)
            runCurrent()
        }
        assertTrue(parked(), "The real drain must reach its reported park before assertions")
    }

    @OriginalCase("ChatSendQueueServiceTests::channel drain parks deviceError(2) below disambiguateAfterAttempts()")
    @Test fun theActualNotFoundChannelSendParksWithoutQueryingBeforeItsDisambiguationThreshold() = runTest(scheduler) {
        exerciseChannelNotFound(initialAttempts = 0, boundedWait = false)
    }

    @OriginalCase("ChatSendQueueServiceTests::channel drain treats deviceError(2) as terminal when fetchChannel confirms the channel is gone()")
    @Test fun theActualChannelServiceUnconfiguredSlotTerminallyRetiresOnlyItsMissingSlotEnvelope() = runTest(scheduler) {
        exerciseChannelNotFound(initialAttempts = 7, boundedWait = false)
    }

    @OriginalCase("ChatSendQueueServiceTests::drain bounded wait re-attempts after transportWaitTimeout expires()")
    @Test fun theRealThirtySecondChannelParkRetriesAtItsDeadlineWithoutAnyTransportWake() = runTest(scheduler) {
        exerciseChannelNotFound(initialAttempts = 0, boundedWait = true)
    }

    private suspend fun TestScope.exerciseChannelNotFound(initialAttempts: Long, boundedWait: Boolean) {
        seed()
        val channel = ChannelDTO(radioId = radio, index = 0u, name = "Test Channel", secret = Bytes(ByteArray(16) { 0xAA.toByte() }))
        store.saveChannel(channel)
        lateinit var channels: ChannelService
        val g = RadioGeneration(74, config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01)),
            channelQuery = MessagingChannelQuery { channels.fetchChannel(it) })
        channels = ChannelService(g.session, store, null)
        g.start()
        g.firmware.error = 2u
        g.firmware.emptyChannelSlot = true
        val message = g.service.createPendingChannelMessage("Hello channel", 0u, radio)
        val row = PendingSendDTO.fromEnvelope(ChannelMessageEnvelope(message.id, 0u, true,
            message.text, message.timestamp, "Test Radio"), radio).copy(sequence = 1, attemptCount = initialAttempts)
        store.upsertPendingSend(row)
        val foreign = row.copy(radioId = other)
        store.upsertPendingSend(foreign)
        val events = g.service.statusEvents()
        g.queue.hydrate()
        runCurrent()
        g.signals.set(DeviceConnectionState.READY)
        runCurrent()
        if (initialAttempts >= 3) {
            withTimeout(5_000) { g.queue.awaitDrainCompletion() }
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, message.id))?.status)
            assertEquals(1, g.firmware.channelQueries().size)
            assertEquals(listOf(MessageStatusEvent.Failed(message.id)), completedStatuses(g, events))
        } else {
            advanceUntilParked(g, message.id)
            assertEquals(listOf(row.copy(attemptCount = 1)), store.fetchPendingSends(radio))
            assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
            if (boundedWait) {
                advanceTimeBy(29_999)
                runCurrent()
                assertEquals(listOf(row.copy(attemptCount = 1)), store.fetchPendingSends(radio))
                advanceTimeBy(1)
                runCurrent()
                assertEquals(listOf(row.copy(attemptCount = 2)), store.fetchPendingSends(radio))
                advanceTimeBy(300)
                runCurrent()
                assertEquals(listOf(row.copy(attemptCount = 2)), store.fetchPendingSends(radio))
                assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
            }
            assertTrue(g.firmware.channelQueries().isEmpty())
            assertTrue(completedStatuses(g, events).isEmpty())
        }
        assertEquals(channel, store.fetchChannel(radio, 0u))
        assertEquals(listOf(foreign), store.fetchPendingSends(other))
        g.stop()
    }

    @OriginalCase("ChatSendQueueServiceTests::Channel fetchChannel failure counter is per-envelope and does not cascade()")
    @Test fun channelQueryFailureCapRetiresOnlyItsEnvelopeAndPreservesTheNextActualRoomRow() = runTest(scheduler) {
        seed()
        val channel = ChannelDTO(UUID.randomUUID(), radio, 0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }))
        store.saveChannel(channel)
        val queryFailure = MeshCoreException.Timeout()
        val g = RadioGeneration(29,
            config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01)),
            channelQuery = MessagingChannelQuery {
                delay(1_900)
                throw queryFailure
            },
            queueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2),
        )
        g.start()
        g.firmware.error = 2u
        val first = g.service.createPendingChannelMessage("Hello channel", 0u, radio)
        val second = g.service.createPendingChannelMessage("Second message", 0u, radio)
        val firstRow = PendingSendDTO.fromEnvelope(
            ChannelMessageEnvelope(first.id, 0u, true, first.text, first.timestamp, "Test Radio"), radio,
        ).copy(sequence = 1, attemptCount = 7)
        val secondRow = PendingSendDTO.fromEnvelope(
            ChannelMessageEnvelope(second.id, 0u, false, second.text, second.timestamp, "Test Radio"), radio,
        ).copy(sequence = 2, attemptCount = 2)
        store.upsertPendingSend(firstRow)
        store.upsertPendingSend(secondRow)
        val foreignMessage = first.copy(radioId = other)
        val foreignRow = firstRow.copy(radioId = other)
        store.saveMessage(foreignMessage)
        store.upsertPendingSend(foreignRow)
        val status = g.service.statusEvents()
        val observed = mutableListOf<MessageStatusEvent>()
        val listener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            status.events.collect { observed += it.event }
        }
        try {
            g.queue.hydrate()
            runCurrent()
            g.signals.set(DeviceConnectionState.READY)
            runCurrent()
            val deadline = scheduler.currentTime + 30_000
            while (g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                    .none { it.operation == "park.${first.id}" } && scheduler.currentTime < deadline) {
                advanceTimeBy(50)
                runCurrent()
            }
            assertEquals(listOf(firstRow.copy(attemptCount = 8), secondRow), store.fetchPendingSends(radio))
            assertEquals(first, store.fetchMessage(EntityKey(radio, first.id)))
            g.queue.transportDidOpen()
            runCurrent()
            while (store.hasPendingSend(EntityKey(radio, first.id)) && scheduler.currentTime < deadline) {
                advanceTimeBy(50)
                runCurrent()
            }
            assertFalse(store.hasPendingSend(EntityKey(radio, first.id)))
            assertEquals(first.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, first.id)))
            advanceTimeBy(3_000)
            runCurrent()
            val queries = g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .filter { it.operation.startsWith("channelQuery.") }
            assertEquals(2, queries.count { it.operation == "channelQuery.${first.id}" })
            assertEquals(1, queries.count { it.operation == "channelQuery.${second.id}" })
            assertTrue(queries.all { it.cause === queryFailure })
            assertEquals(listOf(secondRow.copy(attemptCount = 3)), store.fetchPendingSends(radio))
            assertEquals(second, store.fetchMessage(EntityKey(radio, second.id)))
            assertEquals(foreignMessage, store.fetchMessage(EntityKey(other, first.id)))
            assertEquals(listOf(foreignRow), store.fetchPendingSends(other))
            assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.Failed(first.id)), observed)
            assertEquals(channel, store.fetchChannel(radio, 0u))
            val report = g.queue.shutdown()
            assertTrue(report.isComplete, report.issues.toString())
            assertEquals(listOf(secondRow.copy(attemptCount = 3)), store.fetchPendingSends(radio))
        } finally {
            listener.cancelAndJoin()
            status.close()
            g.stop()
        }
    }

    @OriginalCase("MessageServiceACKTests::checkExpiredAcks marks expired ACK as failed()")
    @Test fun expiredAcceptedAckCommitsFailureAndPublishesItOnceThroughTheActualRoomService() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(30)
        g.start()
        val message = g.acceptedWithoutAck("Test message", Bytes.of(1, 2, 3, 4))
        val events = g.service.statusEvents()
        wallOffsetMillis = 60_000
        g.service.checkExpiredAcks()
        g.service.checkExpiredAcks()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.Failed(message.id)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::ACK within grace window reconciles .sent → .delivered via the in-memory pending entry()")
    @Test fun acceptedAckInsideTheOriginalGraceWindowReconcilesItsRealSentRow() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(31, config = MessageServiceConfig(ackGiveUpWindow = 45.0))
        g.start()
        g.firmware.suggestedTimeout = 25_000u
        val code = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF)
        val message = g.acceptedWithoutAck("Test message", code)
        val events = g.service.statusEvents()
        g.service.startEventMonitoring()
        wallOffsetMillis = 31_000
        g.service.checkExpiredAcks()
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(1, g.service.pendingAckCount)
        g.firmware.pushAck(code, 99u)
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.DELIVERED, roundTripTime = 99u),
            store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 99u)),
            completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::checkExpiredAcks fails a DM after max(ackGiveUpWindow, per-attempt timeout); the window is the floor()")
    @Test fun realAcceptedAckExpiryRetainsTheOriginalTwentySecondWindowFloor() = runTest(scheduler) {
        exerciseAckWindow(timeout = 5.0, liveAge = 15, expiredAge = 25)
    }

    @OriginalCase("MessageServiceACKTests::checkExpiredAcks honors a per-attempt timeout longer than the give-up window (slow preset)()")
    @Test fun realAcceptedAckExpiryRetainsTheOriginalSixtySecondSlowPresetTimeout() = runTest(scheduler) {
        exerciseAckWindow(timeout = 60.0, liveAge = 30, expiredAge = 70)
    }

    private suspend fun exerciseAckWindow(timeout: Double, liveAge: Long, expiredAge: Long) {
        seed()
        val g = RadioGeneration(32, config = MessageServiceConfig(minTimeout = timeout, ackGiveUpWindow = 20.0))
        g.start()
        val live = g.acceptedWithoutAck("Test message", Bytes.of(1, 2, 3, 4))
        wallOffsetMillis = -(expiredAge - liveAge) * 1_000
        val expired = g.acceptedWithoutAck("Test message", Bytes.of(5, 6, 7, 8))
        val foreign = expired.copy(radioId = other)
        store.saveMessage(foreign)
        val events = g.service.statusEvents()
        wallOffsetMillis = liveAge * 1_000
        g.service.checkExpiredAcks()
        assertEquals(live, store.fetchMessage(EntityKey(radio, live.id)))
        assertEquals(expired.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, expired.id)))
        assertEquals(foreign, store.fetchMessage(EntityKey(other, foreign.id)))
        assertEquals(1, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.Failed(expired.id)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::checkExpiredAcks does not broadcast failure when DB stays delivered()")
    @Test fun realDeliveredRowSurvivesExpiryWithoutAFalseFailureEvent() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(33)
        g.start()
        val message = g.acceptedWithoutAck("Test message", Bytes.of(0xCE, 0xEC, 0xAC, 0xCE))
        val delivered = message.copy(status = MessageStatus.DELIVERED)
        store.saveMessage(delivered)
        val events = g.service.statusEvents()
        wallOffsetMillis = 60_000
        g.service.checkExpiredAcks()
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(completedStatuses(g, events).isEmpty())
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::failAllPendingMessages fails all non-delivered and broadcasts .failed()")
    @Test fun failAllCommitsBothAcceptedRoomFailuresAndPublishesEachExactlyOnce() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(34)
        g.start()
        val first = g.acceptedWithoutAck("first", Bytes.of(1, 2, 3, 4))
        val second = g.acceptedWithoutAck("second", Bytes.of(5, 6, 7, 8))
        val events = g.service.statusEvents()
        g.service.failAllPendingMessages()
        g.service.failAllPendingMessages()
        assertEquals(first.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, first.id)))
        assertEquals(second.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, second.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.Failed(first.id), MessageStatusEvent.Failed(second.id)),
            completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::failAllPendingMessages does not downgrade or notify on a delivered DB row()")
    @Test fun failAllLeavesTheActualDeliveredRowIntactAndRetiresOnlyItsTracking() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(35)
        g.start()
        val message = g.acceptedWithoutAck("Test message", Bytes.of(0xFA, 0x11, 0x77, 0x33))
        val delivered = message.copy(status = MessageStatus.DELIVERED)
        store.saveMessage(delivered)
        val events = g.service.statusEvents()
        g.service.failAllPendingMessages()
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(completedStatuses(g, events).isEmpty())
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::stopAndFailAllPending stops checking and fails all pending()")
    @Test fun stopAndFailStopsTheRealTimerAndCommitsItsAcceptedRoomFailure() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(36)
        g.start()
        val message = g.acceptedWithoutAck("Test message", Bytes.of(1, 2, 3, 4))
        val events = g.service.statusEvents()
        g.service.startAckExpiryChecking()
        assertTrue(g.service.isAckExpiryCheckingActive)
        g.service.stopAndFailAllPending()
        assertFalse(g.service.isAckExpiryCheckingActive)
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.Failed(message.id)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::handleAcknowledgement uses firmware tripTime when provided()")
    @Test fun actualAckListenerKeepsTheOriginalFirmwareTripTimeInsteadOfWallElapsedTime() = runTest(scheduler) {
        exerciseAckTripTime(Bytes.of(0xEF, 0xBE, 0xAD, 0xDE), age = 10, tripTime = 250u)
    }

    @OriginalCase("MessageServiceACKTests::handleAcknowledgement leaves roundTripTime nil when firmware does not supply tripTime()")
    @Test fun actualAckListenerPreservesAnAbsentFirmwareTripTime() = runTest(scheduler) {
        exerciseAckTripTime(Bytes.of(0xBE, 0xBA, 0xFE, 0xCA), age = 2, tripTime = null)
    }

    private suspend fun exerciseAckTripTime(code: Bytes, age: Long, tripTime: UInt?) {
        seed()
        val g = RadioGeneration(37)
        g.start()
        val message = g.acceptedWithoutAck("Test message", code)
        val events = g.service.statusEvents()
        g.service.startEventMonitoring()
        wallOffsetMillis = age * 1_000
        g.firmware.pushAck(code, tripTime)
        scheduler.runCurrent()
        assertEquals(message.copy(status = MessageStatus.DELIVERED, roundTripTime = tripTime),
            store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(base.plusSeconds(age), store.fetchContact(EntityKey(radio, contact.id))?.lastMessageDate)
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, tripTime)),
            completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::late ACK landing in the checker's await-gap cannot flip a just-failed row to .delivered()")
    @Test fun actualLateAckCannotResurrectTheFailedRoomRowWhileItsTrackingStillExists() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(38)
        g.start()
        val code = Bytes.of(0x9A, 0xBC, 0xDE, 0xF0)
        val message = g.acceptedWithoutAck("Test message", code)
        val events = g.service.statusEvents()
        g.service.startEventMonitoring()
        assertTrue(store.updateMessageStatusUnlessDelivered(EntityKey(radio, message.id), MessageStatus.FAILED))
        assertEquals(1, g.service.pendingAckCount)
        g.firmware.pushAck(code, 200u)
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(completedStatuses(g, events).isEmpty())
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::late ACK after the row is already .failed with no live entry stays .failed()")
    @Test fun actualUnmatchedLateAckCannotResurrectTheOriginalFailedRoomRow() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(39)
        g.start()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Test message", timestamp = 42u,
            status = MessageStatus.FAILED, ackCode = 0xABCD_EF01u)
        store.saveMessage(message)
        val events = g.service.statusEvents()
        g.service.startEventMonitoring()
        g.firmware.pushAck(Bytes.of(0x01, 0xEF, 0xCD, 0xAB), 300u)
        runCurrent()
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(false, g.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>().single().orphanSentRow)
        assertTrue(completedStatuses(g, events).isEmpty())
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::createPendingMessage stamps lastMessageDate so a first DM appears in the chat list before any send succeeds()")
    @Test fun pendingFirstDmBecomesAnActualConversationBeforeAnyWireSend() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(50)
        assertTrue(store.fetchConversations(radio).isEmpty())
        val message = g.service.createPendingMessage("First DM", contact)
        assertEquals(MessageDTO(id = message.id, radioId = radio, contactID = contact.id,
            text = "First DM", timestamp = base.epochSecond.toUInt(), createdAt = base), message)
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(listOf(contact.copy(lastMessageDate = base)), store.fetchConversations(radio).toList())
        assertTrue(g.firmware.sent().isEmpty())
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::createPendingChannelMessage saves to dataStore with pending status()")
    @Test fun pendingChannelCreationCommitsItsOriginalFieldsWithoutSending() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(51)
        val message = g.service.createPendingChannelMessage("Hello channel", 0u, radio)
        assertEquals(MessageDTO(id = message.id, radioId = radio, channelIndex = 0u,
            text = "Hello channel", timestamp = base.epochSecond.toUInt(), createdAt = base), message)
        assertEquals(message, store.fetchMessages(radio, 0u).single())
        assertTrue(g.firmware.channelSent().isEmpty())
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::sendDirectMessage saves message to dataStore before send attempt()")
    @Test fun rawDirectSendObservesTheAlreadyCommittedPendingRoomMessage() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(52)
        g.start()
        var before: MessageDTO? = null
        g.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_MESSAGE.rawValue) {
                before = store.fetchMessages(EntityKey(radio, contact.id)).single()
                assertEquals(MessageStatus.PENDING, before?.status)
                assertTrue(g.firmware.sent().isEmpty())
            }
        }
        val sent = g.service.sendDirectMessage("Hello", contact)
        val pending = assertNotNull(before)
        assertEquals(MessageDTO(id = sent.id, radioId = radio, contactID = contact.id, text = "Hello",
            timestamp = base.epochSecond.toUInt(), createdAt = base), pending)
        assertEquals(pending.copy(status = MessageStatus.SENT, ackCode = sent.ackCode), sent)
        assertEquals(sent, store.fetchMessage(EntityKey(radio, sent.id)))
        assertEquals(1, g.firmware.sent().size)
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::sendChannelMessage saves message to dataStore before send attempt()")
    @Test fun rawChannelSendObservesTheAlreadyCommittedPendingRoomMessage() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(53)
        g.start()
        var before: MessageDTO? = null
        g.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue) {
                before = store.fetchMessages(radio, 0u).single()
                assertEquals(MessageStatus.PENDING, before?.status)
                assertTrue(g.firmware.channelSent().isEmpty())
            }
        }
        val events = g.service.statusEvents()
        val receipt = g.service.sendChannelMessage("Hello channel", 0u, radio)
        val pending = assertNotNull(before)
        assertEquals(MessageDTO(id = receipt.id, radioId = radio, channelIndex = 0u, text = "Hello channel",
            timestamp = base.epochSecond.toUInt(), createdAt = base), pending)
        assertEquals(pending.copy(status = MessageStatus.SENT), store.fetchMessage(EntityKey(radio, receipt.id)))
        assertEquals(listOf(MessageStatusEvent.StatusResolved(receipt.id, MessageStatus.SENT, null)),
            completedStatuses(g, events))
        assertEquals(1, g.firmware.channelSent().size)
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::sendPendingChannelMessage sets failed status on send error()")
    @Test fun disconnectedPendingChannelSendCommitsFailureInTheActualStore() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(54)
        val message = g.service.createPendingChannelMessage("Hello channel", 0u, radio)
        val events = g.service.statusEvents()
        assertFailsWith<MessageServiceException> { g.service.sendPendingChannelMessage(message.id) }
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertTrue(g.firmware.channelSent().isEmpty())
        assertTrue(completedStatuses(g, events).isEmpty(), "The queue owns pending-send failure publication")
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::resendChannelMessage writes .sent before broadcasting .resent and refreshes counts()")
    @Test fun channelResendCommitsItsSentStatusAndCountsBeforeTheConsumerEvent() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, channelIndex = 0u, text = "Hello channel",
            timestamp = 42u, createdAt = base.minusSeconds(1), status = MessageStatus.FAILED,
            heardRepeats = 3, sendCount = 1)
        store.saveMessage(message)
        val g = RadioGeneration(55)
        g.start()
        val events = g.service.statusEvents()
        val seen = mutableListOf<MessageDTO?>()
        val collecting = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.events.collect {
                assertEquals(g.token, it.token)
                assertEquals(MessageStatusEvent.Resent(message.id), it.event)
                seen += store.fetchMessage(EntityKey(radio, message.id))
            }
        }
        val stamp = g.service.resendChannelMessage(message.id, preserveTimestamp = false)
        runCurrent()
        val expected = message.copy(timestamp = base.epochSecond.toUInt(), status = MessageStatus.SENT,
            heardRepeats = 0, sendCount = 2)
        assertEquals(base.epochSecond.toUInt(), stamp)
        assertEquals(listOf<MessageDTO?>(expected), seen)
        assertEquals(expected, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(1, g.firmware.channelSent().size)
        events.close()
        collecting.cancelAndJoin()
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::resendDirectMessage increments sendCount and broadcasts .resent on a successful resend()")
    @Test fun acknowledgedDirectResendCommitsExactlyOneCountAndResentEvent() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Hello",
            timestamp = 42u, createdAt = base.minusSeconds(1), status = MessageStatus.DELIVERED, sendCount = 1)
        store.saveMessage(message)
        val g = RadioGeneration(56)
        g.start()
        val events = g.service.statusEvents()
        val result = g.service.resendDirectMessage(message.id, contact, preserveTimestamp = true)
        assertEquals(MessageStatus.DELIVERED, result.status)
        assertEquals(2L, result.sendCount)
        assertEquals(42u, result.timestamp)
        assertEquals(result, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(listOf<MessageStatusEvent>(
            MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u),
            MessageStatusEvent.Resent(message.id)), completedStatuses(g, events))
        assertEquals(1, g.firmware.sent().size)
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::sendPendingDirectMessage does not bump sendCount or broadcast .resent on first send()")
    @Test fun firstPendingDirectSendRetainsItsCountAndPublishesOnlyDelivery() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(57)
        g.start()
        val message = g.service.createPendingMessage("Hello", contact)
        val events = g.service.statusEvents()
        val result = g.service.sendPendingDirectMessage(message.id, contact, preserveTimestamp = true)
        assertEquals(1L, result.sendCount)
        assertEquals(MessageStatus.DELIVERED, result.status)
        assertEquals(message.timestamp, result.timestamp)
        assertEquals(result, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(listOf(MessageStatusEvent.StatusResolved(message.id, MessageStatus.DELIVERED, 123u)),
            completedStatuses(g, events))
        assertEquals(1, g.firmware.sent().size)
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::retry exhaustion leaves the DM .sent with its pending entry alive, never prematurely .failed()")
    @Test fun actualRetryExhaustionLeavesSentAndOneLiveEntryForTheCheckerToOwn() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(58, config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5, minTimeout = 0.0))
        g.start()
        g.firmware.acknowledge = false
        g.firmware.acceptedAck = Bytes.of(0xA1, 0xB2, 0xC3, 0xD4)
        val events = g.service.statusEvents()
        val result = g.service.sendMessageWithRetry("exhaust me", contact)
        assertEquals(MessageStatus.SENT, result.status)
        assertEquals(1L, result.sendCount)
        assertEquals(result, store.fetchMessage(EntityKey(radio, result.id)))
        assertEquals(1, g.service.pendingAckCount)
        assertEquals(2, g.firmware.sent().size)
        assertEquals(listOf<MessageStatusEvent>(
            MessageStatusEvent.Retrying(result.id, 0, 1),
            MessageStatusEvent.StatusResolved(result.id, MessageStatus.SENT, null)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::a genuine send exception still fails the DM through the outer catch()")
    @Test fun genuineRawTransportFailureCommitsFailureAndRetiresActualAckTracking() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(59)
        g.start()
        val cause = MeshTransportError.SendFailed("controlled raw transport drop")
        g.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_MESSAGE.rawValue) throw cause
        }
        val events = g.service.statusEvents()
        assertSame(cause, assertFailsWith<MeshTransportError.SendFailed> {
            g.service.sendMessageWithRetry("boom", contact)
        })
        val message = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertEquals(MessageStatus.FAILED, message.status)
        assertEquals("boom", message.text)
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.Failed(message.id)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::checkExpiredAcks cannot fail a DM while its retry loop is still inside waitForEvent()")
    @Test fun checkerCannotFailTheActualInFlightRetryAwaitingItsAck() = runTest(scheduler) {
        exerciseInFlightChecker(grace = 30.0, ageMillis = 0)
    }

    @OriginalCase("MessageServiceSendTests::checkExpiredAcks respects a slow-preset per-attempt timeout that exceeds a tiny give-up window()")
    @Test fun actualSlowAttemptSurvivesItsCheckerBeyondTheTinyGiveUpWindow() = runTest(scheduler) {
        exerciseInFlightChecker(grace = 1.0, ageMillis = 2_000)
    }

    @OriginalCase("MessageServiceSendTests::failMessageAndRethrow does not downgrade a delivered DB row()")
    @Test fun aRawSendFailureCannotDowngradeTheAlreadyDeliveredRoomRow() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(61)
        g.start()
        val cause = MeshTransportError.SendFailed("controlled drop after delivery commit")
        g.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_MESSAGE.rawValue) throw cause
        }
        val events = g.service.statusEvents()
        var delivered: MessageDTO? = null
        assertSame(cause, assertFailsWith<MeshTransportError.SendFailed> {
            g.service.sendMessageWithRetry("delivered before failure", contact, onMessageCreated = {
                delivered = it.copy(status = MessageStatus.DELIVERED, ackCode = 0x3322_11DDu)
                store.saveMessage(assertNotNull(delivered))
            })
        })
        val expected = assertNotNull(delivered)
        assertEquals(expected, store.fetchMessage(EntityKey(radio, expected.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(completedStatuses(g, events).isEmpty())
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::finalizeSend exhaustion does not downgrade a delivered DB row()")
    @Test fun publicRetryExhaustionCannotDowngradeTheAlreadyDeliveredRoomRow() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(62, config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5, minTimeout = 0.0))
        g.start()
        g.firmware.acknowledge = false
        g.firmware.acceptedAck = Bytes.of(0xFE, 0xED, 0xFA, 0xCE)
        val events = g.service.statusEvents()
        var delivered: MessageDTO? = null
        val result = g.service.sendMessageWithRetry("delivered before exhaustion", contact, onMessageCreated = {
            delivered = it.copy(status = MessageStatus.DELIVERED, ackCode = 0xCEFA_EDFEu)
            store.saveMessage(assertNotNull(delivered))
        })
        assertEquals(assertNotNull(delivered), result)
        assertEquals(result, store.fetchMessage(EntityKey(radio, result.id)))
        assertEquals(1, g.service.pendingAckCount)
        assertEquals(2, g.firmware.sent().size)
        assertEquals(listOf(MessageStatusEvent.Retrying(result.id, 0, 1)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::handleAcknowledgement matches any CRC accumulated across retry attempts()")
    @Test fun anActualAckForAttemptZeroStillDeliversDuringTheThirdWireAttempt() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(63, config = MessageServiceConfig(maxAttempts = 3, floodAfter = 5, minTimeout = 0.0))
        g.start()
        val codes = listOf(Bytes.of(0xAA, 0xAA, 0xAA, 0xAA), Bytes.of(0xBB, 0xBB, 0xBB, 0xBB),
            Bytes.of(0xCC, 0xCC, 0xCC, 0xCC))
        g.firmware.acknowledge = false
        g.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_MESSAGE.rawValue) g.firmware.acceptedAck = codes[g.firmware.sent().size]
        }
        val events = g.service.statusEvents()
        val sending = async { g.service.sendMessageWithRetry("Test message", contact) }
        runCurrent()
        assertEquals(codes[0], g.firmware.lastAck)
        advanceTimeBy(13)
        runCurrent()
        assertEquals(codes[1], g.firmware.lastAck)
        advanceTimeBy(13)
        runCurrent()
        assertEquals(codes[2], g.firmware.lastAck)
        assertEquals(3, g.firmware.sent().size)
        g.firmware.pushAck(codes[0], 500u)
        runCurrent()
        val delivered = sending.await()
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(codes[0].readUInt32LE(0), delivered.ackCode)
        assertEquals(500u, delivered.roundTripTime)
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, delivered.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf<MessageStatusEvent>(
            MessageStatusEvent.Retrying(delivered.id, 0, 2),
            MessageStatusEvent.Retrying(delivered.id, 1, 2),
            MessageStatusEvent.StatusResolved(delivered.id, MessageStatus.DELIVERED, 500u)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::handleAcknowledgement updates contact lastMessageDate on late ACK()")
    @Test fun anActualLateAckUpdatesThePersistedContactAndConversationDate() = runTest(scheduler) {
        contact = contact.copy(name = "LateAckContact", outPathLength = 2u, outPath = Bytes.of(1, 2),
            lastAdvertTimestamp = base.epochSecond.toUInt(), lastModified = base.epochSecond.toUInt())
        seed()
        val g = RadioGeneration(64, config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5, minTimeout = 0.0))
        g.start()
        val code = Bytes.of(0x11, 0x22, 0x33, 0x44)
        g.firmware.acceptedAck = code
        g.firmware.acknowledge = false
        val sent = g.service.sendMessageWithRetry("Test message", contact)
        assertEquals(MessageStatus.SENT, sent.status)
        assertNull(store.fetchContact(EntityKey(radio, contact.id))?.lastMessageDate)
        val events = g.service.statusEvents()
        g.service.startEventMonitoring()
        wallOffsetMillis = 1_000
        val receivedAt = clock.wallClock.instant()
        g.firmware.pushAck(code, 500u)
        runCurrent()
        val updatedContact = assertNotNull(store.fetchContact(EntityKey(radio, contact.id)))
        assertEquals(contact.copy(lastMessageDate = receivedAt), updatedContact)
        assertEquals(listOf(updatedContact), store.fetchConversations(radio).toList())
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, sent.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(500u, delivered.roundTripTime)
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.StatusResolved(sent.id, MessageStatus.DELIVERED, 500u)),
            completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::failMessageAndRethrow removes pendingAcks entry and rethrows()")
    @Test fun aNotConnectedExceptionRetiresActualSpeculativeTrackingAndFailsTheCommittedRow() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(65)
        g.start()
        g.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_MESSAGE.rawValue) {
                assertEquals(1, g.service.pendingAckCount)
                throw MeshCoreException.NotConnected()
            }
        }
        val events = g.service.statusEvents()
        val failure = assertFailsWith<MessageServiceException> { g.service.sendMessageWithRetry("Test message", contact) }
        assertIs<MeshCoreException.NotConnected>(assertIs<MessageServiceError.SessionError>(failure.error).underlying)
        val message = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertEquals(MessageStatus.FAILED, message.status)
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.Failed(message.id)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::finalizeSend preserves roundTripTime after listener-won delivery()")
    @Test fun theRealListenerWinsBeforeAcceptanceAndFinalizationPreservesItsFirmwareRtt() = runTest(scheduler) {
        exercisePublicFinalization(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD), 500u, listenerWins = true)
    }

    @OriginalCase("MessageServiceACKTests::finalizeSend broadcasts .statusResolved when in-loop waitForEvent wins the ACK race()")
    @Test fun theRealInLoopAckPublishesExactlyOneDeliveredResolution() = runTest(scheduler) {
        exercisePublicFinalization(Bytes.of(0x11, 0x22, 0x33, 0x44), 500u, listenerWins = false)
    }

    @OriginalCase("MessageServiceACKTests::finalizeSend delivered branch writes .delivered, updates contact, yields .statusResolved, removes entry()")
    @Test fun publicFinalizationCommitsDeliveryContactRecencyAndOneResolutionWithoutAnRtt() = runTest(scheduler) {
        exercisePublicFinalization(Bytes.of(0x10, 0x20, 0x30, 0x40), null, listenerWins = false)
    }

    @OriginalCase("MessageServiceACKTests::finalizeSend nil branch over an already-.failed row leaves it .failed()")
    @Test fun exhaustedPublicFinalizationCannotPublishSentOverAnAlreadyFailedRoomRow() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(66, config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5, minTimeout = 0.0))
        g.start()
        g.firmware.acknowledge = false
        g.firmware.acceptedAck = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)
        val events = g.service.statusEvents()
        var failed: MessageDTO? = null
        val result = g.service.sendMessageWithRetry("already failed", contact, onMessageCreated = {
            failed = it.copy(status = MessageStatus.FAILED)
            store.saveMessage(assertNotNull(failed))
        })
        assertEquals(assertNotNull(failed), result)
        assertEquals(result, store.fetchMessage(EntityKey(radio, result.id)))
        assertEquals(listOf(MessageStatusEvent.Retrying(result.id, 0, 1)), completedStatuses(g, events))
        g.stop()
    }

    @OriginalCase("MessageServiceACKTests::finalizeSend nil branch skips the DB write when the listener already delivered()")
    @Test fun publicFinalizationPerformsNoMessageUpdateAfterTheActualListenerCommitsDelivery() = runTest(scheduler) {
        exercisePublicFinalization(Bytes.of(0x55, 0x66, 0x77, 0x88), null,
            listenerWins = true, rejectWriteAfterDelivery = true)
    }

    @OriginalCase("MessageServiceACKTests::finalizeSend nil branch yields .statusResolved(.sent) from a non-terminal row and gates off on a terminal one()")
    @Test fun actualRetryExhaustionResolvesRetryingToSentButCannotResolveAnAlreadyFailedRow() = runTest(scheduler) {
        seed()
        for ((index, status) in listOf(MessageStatus.RETRYING, MessageStatus.FAILED).withIndex()) {
            val g = RadioGeneration(80L + index,
                config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5, minTimeout = 0.0))
            g.start()
            g.firmware.acknowledge = false
            g.firmware.acceptedAck = if (index == 0) Bytes.of(1, 2, 3, 4) else Bytes.of(5, 6, 7, 8)
            val events = g.service.statusEvents()
            val result = g.service.sendMessageWithRetry("nil finalizer", contact, onMessageCreated = {
                store.saveMessage(it.copy(status = status))
            })
            assertEquals(if (status == MessageStatus.RETRYING) MessageStatus.SENT else status, result.status)
            assertEquals(result, store.fetchMessage(EntityKey(radio, result.id)))
            val expected: List<MessageStatusEvent> = listOf(MessageStatusEvent.Retrying(result.id, 0, 1)) +
                if (status == MessageStatus.RETRYING)
                    listOf(MessageStatusEvent.StatusResolved(result.id, MessageStatus.SENT, null)) else emptyList()
            assertEquals(expected, completedStatuses(g, events))
            assertEquals(2, g.firmware.sent().size)
            assertEquals(1, g.service.pendingAckCount)
            g.stop()
        }
    }

    private suspend fun TestScope.exercisePublicFinalization(
        code: Bytes, tripTime: UInt?, listenerWins: Boolean, rejectWriteAfterDelivery: Boolean = false,
    ) {
        seed()
        val g = RadioGeneration(67, config = MessageServiceConfig(maxAttempts = 1, floodAfter = 5))
        g.start()
        val expectedCode = if (listenerWins) {
            val input = ByteWriter().appendUInt32LE(base.epochSecond.toUInt()).appendUInt8(0u)
                .append(Bytes.utf8("Test message")).append(self).toBytes()
            Bytes(MessageDigest.getInstance("SHA-256").digest(input.toByteArray())).prefix(4)
        } else code
        g.firmware.acceptedAck = expectedCode
        g.firmware.automaticTripTime = tripTime
        g.firmware.suggestedTimeout = 5_000u
        val events = g.service.statusEvents()
        val gate = CompletableDeferred<Unit>()
        if (listenerWins) {
            g.service.startEventMonitoring()
            g.firmware.ackBeforeAcceptance = true
            g.firmware.acceptanceGate = gate
        }
        val sending = async { g.service.sendMessageWithRetry("Test message", contact) }
        var writeGuardInstalled = false
        val delivered = try {
            runCurrent()
            if (listenerWins) {
                assertFalse(sending.isCompleted)
                val beforeAcceptance = store.fetchMessages(EntityKey(radio, contact.id)).single()
                assertEquals(MessageStatus.DELIVERED, beforeAcceptance.status)
                assertEquals(tripTime, beforeAcceptance.roundTripTime)
                assertEquals(0, g.service.pendingAckCount)
                if (rejectWriteAfterDelivery) {
                    db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_delivered_rewrite AFTER UPDATE ON messages " +
                        "BEGIN SELECT RAISE(FAIL, 'finalization rewrote listener delivery'); END")
                    writeGuardInstalled = true
                }
                gate.complete(Unit)
                runCurrent()
            }
            sending.await()
        } finally {
            if (writeGuardInstalled) db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_delivered_rewrite")
        }
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(tripTime, delivered.roundTripTime)
        assertEquals(expectedCode.readUInt32LE(0), delivered.ackCode)
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, delivered.id)))
        assertEquals(contact.copy(lastMessageDate = base), store.fetchContact(EntityKey(radio, contact.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.StatusResolved(delivered.id, MessageStatus.DELIVERED, tripTime)),
            completedStatuses(g, events))
        assertEquals(1, g.firmware.sent().size)
        g.stop()
    }

    private suspend fun TestScope.exerciseInFlightChecker(grace: Double, ageMillis: Long) {
        seed()
        val g = RadioGeneration(60, config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5, ackGiveUpWindow = grace))
        g.start()
        val code = Bytes.of(0x7A, 0x7B, 0x7C, 0x7D)
        g.firmware.acceptedAck = code
        g.firmware.acknowledge = false
        g.firmware.suggestedTimeout = 100_000u
        val events = g.service.statusEvents()
        val sending = async { g.service.sendMessageWithRetry("in flight", contact) }
        runCurrent()
        assertEquals(1, g.firmware.sent().size)
        advanceTimeBy(ageMillis)
        runCurrent()
        g.service.checkExpiredAcks()
        val pending = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertNotEquals(MessageStatus.FAILED, pending.status)
        assertEquals(1, g.service.pendingAckCount)
        assertFalse(sending.isCompleted)
        g.firmware.pushAck(code, 100u)
        runCurrent()
        val delivered = sending.await()
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(100u, delivered.roundTripTime)
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, delivered.id)))
        assertEquals(listOf(MessageStatusEvent.StatusResolved(delivered.id, MessageStatus.DELIVERED, 100u)),
            completedStatuses(g, events))
        g.stop()
    }

    private suspend fun RadioGeneration.acceptedWithoutAck(text: String, code: Bytes): MessageDTO {
        firmware.acknowledge = false
        firmware.acceptedAck = code
        val message = service.sendDirectMessage(text, contact)
        assertEquals(MessageStatus.SENT, message.status)
        assertEquals(code.readUInt32LE(0), message.ackCode)
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        return message
    }

    private suspend fun completedStatuses(
        g: RadioGeneration, subscription: SessionEventSubscription<MessageStatusEvent>,
    ): List<MessageStatusEvent> {
        g.service.finishStatusEvents()
        val events = subscription.events.toList()
        assertTrue(events.all { it.token == g.token })
        subscription.close()
        return events.map { it.event }
    }

    @OriginalCase("PersistenceStoreTests::hasOutgoingSentDM flags a stuck .sent DM by ackCode and ignores other rows()")
    @Test fun unmatchedAckListenerReportsOnlyOutgoingSentDirectRowsWithoutInventingDelivery() = runTest(scheduler) {
        seed()
        val ack = Bytes.fromHex("0DF0FECA")
        val sent = MessageDTO(radioId = radio, contactID = contact.id, text = "orphan sent",
            timestamp = 100u, direction = MessageDirection.OUTGOING, status = MessageStatus.SENT,
            ackCode = 0xCAFE_F00Du)
        val g = RadioGeneration(40)
        g.start()
        g.service.startEventMonitoring()
        val subscription = g.service.statusEvents()
        val events = mutableListOf<MessageStatusEvent>()
        val collecting = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            subscription.events.collect { events += it.event }
        }
        try {
            val excluded = listOf(
                sent.copy(radioId = other),
                sent.copy(id = UUID.randomUUID(), status = MessageStatus.DELIVERED),
                sent.copy(id = UUID.randomUUID(), status = MessageStatus.FAILED),
                sent.copy(id = UUID.randomUUID(), status = MessageStatus.RETRYING),
                sent.copy(id = UUID.randomUUID(), status = MessageStatus.PENDING),
                sent.copy(id = UUID.randomUUID(), direction = MessageDirection.INCOMING),
                sent.copy(id = UUID.randomUUID(), contactID = null, channelIndex = 1u),
                sent.copy(id = UUID.randomUUID(), ackCode = 1u),
            )
            for (row in excluded) store.saveMessage(row)
            g.firmware.pushAck(ack)
            runCurrent()
            assertEquals(false, g.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>()
                .single().orphanSentRow)
            store.saveMessage(sent)
            g.firmware.pushAck(ack)
            runCurrent()
            assertEquals(listOf(false, true),
                g.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>().map { it.orphanSentRow })
            assertEquals(sent, store.fetchMessage(EntityKey(radio, sent.id)))
            for (row in excluded) assertEquals(row, store.fetchMessage(EntityKey(row.radioId, row.id)))
            assertTrue(events.isEmpty())
            assertEquals(0, g.service.pendingAckCount)
            assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>().all { it.token == g.token })
        } finally {
            subscription.close()
            collecting.cancelAndJoin()
            g.stop()
        }
    }

    @Test fun recoveredUnmatchedAckReadClearsOnlyItsDiagnosticFailureAndAllowsCompleteTeardown() = runTest(scheduler) {
        seed()
        val cause = PersistenceStoreException(PersistenceStoreError.FetchFailed("controlled ACK diagnostic read"))
        var fail = true
        val actual = store
        val persistence = object : PersistenceStoreProtocol by actual {
            override suspend fun hasOutgoingSentDM(radioId: RadioId, ackCode: UInt): Boolean {
                if (fail) throw cause
                return actual.hasOutgoingSentDM(radioId, ackCode)
            }
        }
        val g = RadioGeneration(41, persistence)
        g.start()
        g.service.startEventMonitoring()
        val ack = Bytes.fromHex("0DF0FECA")
        g.firmware.pushAck(ack)
        runCurrent()
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
            .any { it.operation == "unmatchedAckDiagnostic" && it.cause === cause })
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>().isEmpty())
        fail = false
        g.firmware.pushAck(ack)
        runCurrent()
        assertEquals(false, g.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>().single().orphanSentRow)
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(g.service.close().isComplete)
        assertTrue(store.fetchMessages(EntityKey(radio, contact.id)).isEmpty())
        g.stop()
    }

    @Test fun unrecoveredUnmatchedAckReadRemainsAnExplicitTeardownFailure() = runTest(scheduler) {
        seed()
        val cause = PersistenceStoreException(PersistenceStoreError.FetchFailed("persistent ACK diagnostic read"))
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun hasOutgoingSentDM(radioId: RadioId, ackCode: UInt): Boolean = throw cause
        }
        val g = RadioGeneration(42, persistence)
        g.start()
        g.service.startEventMonitoring()
        g.firmware.pushAck(Bytes.fromHex("0DF0FECA"))
        runCurrent()
        assertEquals(0, g.service.pendingAckCount)
        val report = g.service.close()
        assertFalse(report.isComplete)
        assertSame(cause, report.issues.single().cause)
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>().isEmpty())
        g.stop()
    }

    @Test fun actualBackupConsumerBackfillsOnlyNilIncomingKeysAndUsesOutgoingUUIDIdentity() = runTest(scheduler) {
        seed()
        val incoming = MessageDTO(radioId = radio, contactID = contact.id, text = "e\u0301", timestamp = UInt.MAX_VALUE,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, sortDate = base.minusSeconds(123))
        val emptyKey = incoming.copy(id = UUID.randomUUID(), deduplicationKey = "")
        val outgoing = incoming.copy(id = UUID.randomUUID(), direction = MessageDirection.OUTGOING, deduplicationKey = null)
        for (message in listOf(incoming, emptyKey, outgoing)) store.saveMessage(message)
        val exported = db.backupEnvelope("test", "test", base).messages.associateBy { it.id }
        assertEquals(DeduplicationKey.contentBased(contact.id, null, null, UInt.MAX_VALUE, "e\u0301"), exported[incoming.id]?.deduplicationKey)
        assertEquals("", exported[emptyKey.id]?.deduplicationKey); assertNull(exported[outgoing.id]?.deduplicationKey)
        assertNull(assertNotNull(store.fetchMessage(EntityKey(radio, incoming.id))).deduplicationKey)
        assertEquals(incoming.sortDate, exported[incoming.id]?.sortDate)
        assertEquals("out-${outgoing.id.canonicalString()}", com.meshcoreone.android.core.data.backup.messageBackupKey(outgoing))
    }

    @Test fun deliveredManualRetryRowDoesNotEmitAnotherPacketOrLoseItsPersistedStatus() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "already delivered",
            timestamp = 100u, status = MessageStatus.DELIVERED)
        store.saveMessage(message)
        val dto = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.replacePendingSendForRetry(message.id, dto)
        val g = RadioGeneration(5); g.start(); g.signals.set(DeviceConnectionState.READY)
        g.queue.hydrate(); runCurrent(); g.queue.awaitDrainCompletion()
        assertTrue(g.firmware.sent().isEmpty()); assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty()); g.stop()
    }

    @Test fun twoRadioGenerationsDoNotCloseTheProcessStoreOrDuplicateMonitors() = runTest(scheduler) {
        seed()
        val first = RadioGeneration(6); first.start(); first.service.startEventMonitoring(); first.service.startEventMonitoring()
        val message = first.service.sendDirectMessage("one physical generation", contact)
        runCurrent(); assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        first.stop()
        val second = RadioGeneration(7); second.start(); second.service.startEventMonitoring()
        assertEquals(message.id, store.fetchMessage(EntityKey(radio, message.id))?.id)
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id)
        val another = second.service.sendDirectMessage("successor", contact); runCurrent()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, another.id))?.status); second.stop()
    }

    @Test fun actualClosedRepositoryFailureCannotAdvanceAttemptCountOrSendOnTheWire() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(8); g.start()
        val message = g.service.createPendingMessage("storage failure", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1))
        g.signals.set(DeviceConnectionState.CONNECTED); g.queue.hydrate(); runCurrent()
        store.close()
        g.signals.set(DeviceConnectionState.READY); runCurrent()
        assertTrue(g.firmware.sent().isEmpty())
        assertFalse(g.queue.shutdown().isComplete); g.service.close(); g.session.stop(); g.scope.cancel()
    }

    @Test fun acceptedBlockedInsertFinishesBeforeShutdownAndSuccessorHydration() = runTest(scheduler) {
        seed()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val actual = store
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long {
                entered.complete(Unit); release.await()
                return actual.insertPendingSendAssigningSequence(dto)
            }
        }
        val first = RadioGeneration(9, blocked); first.start()
        val message = first.service.createPendingMessage("accepted before shutdown", contact)
        val enqueue = backgroundScope.async(start = CoroutineStart.UNDISPATCHED) { first.queue.enqueueDM(DirectMessageEnvelope(message.id, contact.id)) }
        val closing = async(start = CoroutineStart.UNDISPATCHED) { first.queue.shutdown() }
        assertFalse(entered.isCompleted); assertFalse(closing.isCompleted)
        runCurrent(); assertTrue(entered.isCompleted)
        var hydrated = false
        val second = RadioGeneration(10)
        val successor = async { closing.await(); second.start(); second.queue.hydrate(); hydrated = true }
        runCurrent(); assertFalse(hydrated); assertTrue(store.fetchPendingSends(radio).isEmpty())
        enqueue.cancelAndJoin()
        release.complete(Unit); successor.await()
        assertEquals(1, store.fetchPendingSends(radio).size)
        second.signals.set(DeviceConnectionState.READY); runCurrent(); second.queue.awaitDrainCompletion()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(1, second.firmware.sent().size); assertTrue(first.firmware.sent().isEmpty())
        first.stop(); second.stop()
    }

    @Test fun acceptedInsertStorageFailureIsIncludedInShutdownInsteadOfBeingLostAfterItReturns() = runTest(scheduler) {
        seed()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val actual = store
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long {
                entered.complete(Unit); release.await()
                return actual.insertPendingSendAssigningSequence(dto)
            }
        }
        val g = RadioGeneration(11, blocked); g.start()
        val message = g.service.createPendingMessage("failing accepted insert", contact)
        val enqueue = backgroundScope.async {
            try {
                g.queue.enqueueDM(DirectMessageEnvelope(message.id, contact.id))
                null
            } catch (failure: ChatSendQueueServiceException) { failure }
        }
        runCurrent(); assertTrue(entered.isCompleted)
        val closing = async { g.queue.shutdown() }; runCurrent(); assertFalse(closing.isCompleted)
        store.close(); release.complete(Unit)
        val failure = assertNotNull(enqueue.await())
        assertIs<PersistenceStoreException>(failure.cause)
        val report = closing.await()
        assertFalse(report.isComplete)
        assertTrue(report.issues.any { it.cause is ChatSendQueueServiceException && it.cause.cause is PersistenceStoreException })
        assertTrue(g.firmware.sent().isEmpty()); g.service.close(); g.session.stop(); g.scope.cancel()
    }

    @Test fun realAckDuringBlockedRetryStatusPreventsAnotherWireAttempt() = runTest(scheduler) {
        seed()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val actual = store
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun updateMessageRetryStatus(key: EntityKey, status: MessageStatus, retryAttempt: Long, maxRetryAttempts: Long) {
                entered.complete(Unit); release.await()
                actual.updateMessageRetryStatus(key, status, retryAttempt, maxRetryAttempts)
            }
        }
        val g = RadioGeneration(12, blocked, com.meshcoreone.android.core.services.messaging.MessageServiceConfig(maxAttempts = 2, floodAfter = 5))
        g.start(); g.service.startEventMonitoring(); g.firmware.acknowledge = false
        val sending = async { g.service.sendMessageWithRetry("Room retry gap", contact) }
        runCurrent(); advanceTimeBy(13); runCurrent(); assertTrue(entered.isCompleted)
        g.firmware.pushAck(assertNotNull(g.firmware.lastAck)); runCurrent()
        release.complete(Unit)
        assertEquals(MessageStatus.DELIVERED, sending.await().status); assertEquals(1, g.firmware.sent().size)
        g.stop()
    }

    @Test fun trueAckAndFinalReadFailureRecoverOneResendClaimWithoutRecountingANewClaim() = runTest(scheduler) {
        seed()
        var countWritten = false; var failFinal = true
        val actual = store
        val fault = object : PersistenceStoreProtocol by actual {
            override suspend fun incrementMessageSendCount(key: EntityKey): Long =
                actual.incrementMessageSendCount(key).also { countWritten = true }
            override suspend fun fetchMessage(key: EntityKey): MessageDTO? {
                if (countWritten && failFinal) {
                    failFinal = false
                    throw PersistenceStoreException(PersistenceStoreError.FetchFailed("final-read-only"), IllegalStateException("deterministic"))
                }
                return actual.fetchMessage(key)
            }
        }
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Room resend recovery", timestamp = 100u, status = MessageStatus.SENT)
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), radio).copy(sequence = 1))
        val g = RadioGeneration(13, fault); g.start(); g.service.startEventMonitoring()
        g.queue.hydrate(); runCurrent(); g.signals.set(DeviceConnectionState.READY); runCurrent()
        assertEquals(2L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount); assertEquals(1, g.firmware.sent().size)
        assertTrue(store.hasPendingSend(EntityKey(radio, message.id)))
        g.queue.transportDidOpen(); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(2L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount); assertEquals(1, g.firmware.sent().size)
        advanceTimeBy(1000); g.queue.enqueueDM(DirectMessageEnvelope(message.id, contact.id, true)); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(3L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount); assertEquals(2, g.firmware.sent().size)
        g.stop()
    }

    @Test fun persistedCallbackUsesRealRoomAndCanCloseWithoutJoiningItsOwnSend() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(14); g.start(); var callbacks = 0
        withTimeout(2000) {
            assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException> {
                g.service.sendMessageWithRetry("Room callback close", contact, onMessageCreated = {
                    callbacks++
                    assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, it.id))?.status)
                    assertTrue(g.service.close().isComplete)
                })
            }
        }
        assertEquals(1, callbacks); assertTrue(g.firmware.sent().isEmpty())
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id)
        g.queue.shutdown(); g.session.stop(); g.scope.cancel()
    }

    @Test fun unacknowledgedResendRetiredByFailAllDoesNotIncrementTheActualRoomCount() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "unacknowledged manual resend",
            timestamp = 100u, status = MessageStatus.SENT)
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), radio).copy(sequence = 1))
        val g = RadioGeneration(15); g.start(); g.firmware.acknowledge = false
        g.queue.hydrate(); runCurrent(); g.signals.set(DeviceConnectionState.READY); runCurrent()
        g.service.failAllPendingMessages(); advanceTimeBy(13); runCurrent(); g.queue.awaitDrainCompletion()
        val actual = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.FAILED, actual.status); assertEquals(1L, actual.sendCount)
        assertEquals(1, g.firmware.sent().size); assertTrue(store.fetchPendingSends(radio).isEmpty()); g.stop()
    }

    @Test fun actualRoomExpiryCannotTurnMissingAckTrackingIntoAConfirmedResend() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "expired manual resend",
            timestamp = 100u, status = MessageStatus.SENT)
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), radio).copy(sequence = 1))
        val g = RadioGeneration(16, config = com.meshcoreone.android.core.services.messaging.MessageServiceConfig(ackGiveUpWindow = 0.0))
        g.start(); g.firmware.acknowledge = false
        g.queue.hydrate(); runCurrent(); g.signals.set(DeviceConnectionState.READY); runCurrent()
        wallOffsetMillis = 1000
        g.service.checkExpiredAcks(); advanceTimeBy(13); runCurrent(); g.queue.awaitDrainCompletion()
        val actual = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.FAILED, actual.status); assertEquals(1L, actual.sendCount)
        assertEquals(1, g.firmware.sent().size); g.stop()
    }

    @Test fun genuineRoomAckRetiresItsLookupBeforeAcceptanceAndCountsExactlyOneResend() = runTest(scheduler) {
        seed()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "early confirmed resend",
            timestamp = 100u, status = MessageStatus.SENT)
        store.saveMessage(message)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id, true), radio).copy(sequence = 1))
        val g = RadioGeneration(17); g.start(); g.service.startEventMonitoring()
        val gate = CompletableDeferred<Unit>(); g.firmware.acceptanceGate = gate; g.firmware.ackBeforeAcceptance = true
        g.queue.hydrate(); runCurrent(); g.signals.set(DeviceConnectionState.READY); runCurrent()
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(1L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount)
        gate.complete(Unit); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(2L, store.fetchMessage(EntityKey(radio, message.id))?.sendCount)
        assertEquals(1, g.firmware.sent().size); g.stop()
    }

    @Test fun manualPollingRoomConsumerCanCloseWithAnExplicitUnfinishedRecordReport() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(18); g.start(); g.signals.set(DeviceConnectionState.READY)
        val p = g.poller()
        var report: TeardownReport? = null
        var savedID: UUID? = null
        p.setContactMessageHandler { wire, resolved, _ ->
            assertEquals(contact.id, resolved?.id)
            val message = MessageDTO(radioId = radio, contactID = contact.id, text = wire.text,
                timestamp = wire.senderTimestamp.epochSecond.toUInt(), direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED)
            store.saveMessage(message); savedID = message.id
            report = p.close()
        }
        p.startMessageEventMonitoring()
        g.firmware.incomingMessages += incomingContactPacket("manual Room consumer")
        val polling = backgroundScope.async { p.pollAllMessages() }
        withTimeout(2000) { assertFailsWith<CancellationException> { polling.await() } }
        runCurrent(); assertFalse(assertNotNull(report).isComplete); assertEquals(1, p.undeliveredCount)
        assertEquals(0L, p.pendingHandlerCount); assertFalse(p.hasMessageHandlersWired)
        assertEquals("manual Room consumer", store.fetchMessage(EntityKey(radio, assertNotNull(savedID)))?.text)
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id); g.stop()
    }

    @Test fun livePollingRoomConsumerCanAwaitItsOwnGenerationCloseWithoutLeakingHandlers() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(19); g.start(); g.signals.set(DeviceConnectionState.READY)
        val p = g.poller()
        val report = CompletableDeferred<TeardownReport>()
        p.setContactMessageHandler { _, resolved, _ ->
            assertEquals(contact.id, resolved?.id)
            report.complete(p.close())
        }
        p.startMessageEventMonitoring()
        g.firmware.pushPacket(incomingContactPacket("live Room consumer"))
        assertFalse(withTimeout(2000) { report.await() }.isComplete)
        runCurrent(); assertEquals(1, p.undeliveredCount); assertEquals(0L, p.pendingHandlerCount)
        assertFalse(p.hasMessageHandlersWired); assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id)
        g.stop()
    }

    @Test fun deletedAcceptedAckDoesNotPoisonActualRoomExpiryOrTheLiveMonitor() = runTest(scheduler) {
        seed(); val g = RadioGeneration(20); g.start(); g.signals.set(DeviceConnectionState.READY)
        g.firmware.acknowledge = false; g.service.startEventMonitoring(); g.service.startAckExpiryChecking()
        val deleted = g.service.sendDirectMessage("deleted accepted DM", contact)
        val deletedAck = assertNotNull(g.firmware.lastAck)
        val later = g.service.sendDirectMessage("later accepted DM", contact)
        store.deleteMessage(EntityKey(radio, deleted.id)); g.firmware.pushAck(deletedAck); runCurrent()
        assertNull(store.fetchMessage(EntityKey(radio, deleted.id))); assertEquals(1, g.service.pendingAckCount)
        advanceTimeBy(35_001); runCurrent()
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, later.id))?.status)
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(g.service.isAckExpiryCheckingActive); assertTrue(g.service.isEventMonitoringActive)
        g.firmware.acknowledge = true
        val next = g.service.sendDirectMessage("monitor still receives", contact); runCurrent()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, next.id))?.status)
        assertEquals(3, g.firmware.sent().size); g.stop()
    }

    @Test fun actualRoomExpiryIsolatesOneStorageFailureAndCanRecoverItsRetainedEntry() = runTest(scheduler) {
        seed(); val actual = store
        var blockedID: UUID? = null
        val cause = PersistenceStoreException(PersistenceStoreError.SaveFailed("one expiry"), IllegalStateException("deterministic"))
        val faulty = object : PersistenceStoreProtocol by actual {
            override suspend fun updateMessageStatusUnlessDelivered(key: EntityKey, status: MessageStatus): Boolean {
                if (key.id == blockedID && status == MessageStatus.FAILED) throw cause
                return actual.updateMessageStatusUnlessDelivered(key, status)
            }
        }
        val g = RadioGeneration(21, faulty); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val first = g.service.sendDirectMessage("first expiry", contact)
        val later = g.service.sendDirectMessage("later expiry", contact); blockedID = first.id
        wallOffsetMillis = 60_000; g.service.checkExpiredAcks()
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, first.id))?.status)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, later.id))?.status)
        assertEquals(1, g.service.pendingAckCount)
        blockedID = null; g.service.checkExpiredAcks()
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, first.id))?.status)
        assertEquals(0, g.service.pendingAckCount); assertTrue(g.service.close().isComplete); g.stop()
    }

    @Test fun realSessionPushAfterManualNoMoreIsDeliveredOnceIntoRoomWithLiveContext() = runTest(scheduler) {
        seed(); val g = RadioGeneration(22); g.start(); g.signals.set(DeviceConnectionState.READY)
        val p = g.poller(); val saved = mutableListOf<UUID>()
        p.setContactMessageHandler { wire, resolved, context ->
            assertEquals(DeliveryContext.Live, context); saved += saveIncomingContact(wire, resolved).id
        }
        p.startAutoFetch(radio); runCurrent()
        g.firmware.afterNoMore = {
            g.firmware.incomingMessages += incomingContactPacket("late Room message")
            g.firmware.pushPacket(Bytes.of(ResponseCode.MESSAGES_WAITING.rawValue.toInt()))
        }
        assertEquals(0L, p.pollAllMessages()); advanceUntilIdle()
        assertEquals(1, saved.size)
        assertEquals("late Room message", store.fetchMessage(EntityKey(radio, saved.single()))?.text)
        assertEquals(1, store.fetchMessages(EntityKey(radio, contact.id)).size)
        assertTrue(g.firmware.incomingMessages.isEmpty()); assertTrue(p.close().isComplete); g.stop()
    }

    @Test fun disconnectedInlineSendCommitsFailedInRoomButSettledAcceptedSendStaysSent() = runTest(scheduler) {
        seed(); val g = RadioGeneration(23); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val settled = g.service.sendDirectMessage("settled before disconnect", contact)
        g.firmware.acceptanceGate = CompletableDeferred()
        val sending = backgroundScope.async { g.service.sendDirectMessage("inline at disconnect", contact) }
        runCurrent()
        val inline = store.fetchMessages(EntityKey(radio, contact.id)).single { it.text == "inline at disconnect" }
        g.signals.set(DeviceConnectionState.DISCONNECTED); withTimeout(2000) { g.service.close() }
        assertFailsWith<CancellationException> { sending.await() }
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, inline.id))?.status)
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, settled.id))?.status)
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id)
        assertEquals(2, g.firmware.sent().size); g.stop()
    }

    @Test fun disconnectedQueuedRetryKeepsItsActualRoomEnvelopeAndClearsTheRetryingStatus() = runTest(scheduler) {
        seed(); val g = RadioGeneration(24); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val message = g.service.createPendingMessage("queued disconnect", contact)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1)
        store.upsertPendingSend(pending); g.queue.hydrate(); runCurrent(); advanceTimeBy(13); runCurrent()
        assertEquals(MessageStatus.RETRYING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        val stamp = store.fetchMessage(EntityKey(radio, message.id))?.timestamp
        g.signals.set(DeviceConnectionState.DISCONNECTED); g.queue.shutdown()
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(stamp, store.fetchMessage(EntityKey(radio, message.id))?.timestamp)
        assertEquals(pending.id, store.fetchPendingSends(radio).single().id)
        assertEquals(1L, store.fetchPendingSends(radio).single().attemptCount)
        advanceTimeBy(1000); runCurrent(); assertEquals(2, g.firmware.sent().size); g.stop()
    }

    @Test fun malformedRoomEnvelopeDoesNotStopValidLaterHydrationOrLoseItsRepairableRow() = runTest(scheduler) {
        seed(); val g = RadioGeneration(25); g.start(); g.signals.set(DeviceConnectionState.READY)
        val malformed = g.service.createPendingMessage("malformed pending", contact)
        val bad = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(malformed.id, contact.id), radio).copy(sequence = 1)
        store.upsertPendingSend(bad.copy(contactID = null))
        val valid = g.service.createPendingMessage("valid later pending", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(valid.id, contact.id), radio).copy(sequence = 2))
        assertFailsWith<ChatSendQueueServiceException> { g.queue.hydrate() }; runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, valid.id))?.status)
        assertEquals(listOf(bad.id), store.fetchPendingSends(radio).map { it.id })
        assertEquals(1, g.firmware.sent().size)
        store.upsertPendingSend(bad); g.queue.hydrate(); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, malformed.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty()); assertEquals(2, g.firmware.sent().size)
        assertTrue(g.queue.shutdown().isComplete); g.stop()
    }

    @Test fun preflightFaultRestoresActualFailedRoomRowToPendingWithoutAdvancingItsCounter() = runTest(scheduler) {
        seed(); val actual = store
        val cause = PersistenceStoreException(PersistenceStoreError.SaveFailed("preflight"), IllegalStateException("deterministic"))
        val faulty = object : PersistenceStoreProtocol by actual {
            override suspend fun incrementPendingSendAttemptCount(key: EntityKey): Long? = throw cause
        }
        val g = RadioGeneration(26, faulty); g.start(); g.signals.set(DeviceConnectionState.READY)
        val message = g.service.createPendingMessage("preflight status", contact)
        store.updateMessageStatus(EntityKey(radio, message.id), MessageStatus.FAILED)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio).copy(sequence = 1))
        g.queue.hydrate(); runCurrent()
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(0L, store.fetchPendingSends(radio).single().attemptCount)
        assertTrue(g.firmware.sent().isEmpty())
        assertTrue(g.queue.shutdown().issues.any { it.cause === cause }); g.stop()
    }

    @Test fun missingCLIConsumerDoesNotBlockLaterRoomPersistenceAndItsRecordCanBeRetried() = runTest(scheduler) {
        seed(); val g = RadioGeneration(27); g.start(); g.signals.set(DeviceConnectionState.READY)
        val p = g.poller(); val saved = mutableListOf<UUID>()
        p.setContactMessageHandler { wire, resolved, _ -> saved += saveIncomingContact(wire, resolved).id }
        g.firmware.incomingMessages += incomingContactPacket("unwired CLI", 1u)
        g.firmware.incomingMessages += incomingContactPacket("plain persisted after CLI")
        assertFailsWith<MessagePollingException> { p.pollAllMessages() }
        assertEquals(1, saved.size); assertEquals(1, p.undeliveredCount)
        assertEquals("plain persisted after CLI", store.fetchMessage(EntityKey(radio, saved.single()))?.text)
        assertTrue(g.firmware.incomingMessages.isEmpty())
        p.setCLIMessageHandler { wire, resolved -> saved += saveIncomingContact(wire, resolved).id }
        assertEquals(0L, p.pollAllMessages()); assertEquals(2, saved.size)
        assertEquals(2, store.fetchMessages(EntityKey(radio, contact.id)).size)
        assertEquals(0, p.undeliveredCount); assertTrue(p.close().isComplete); g.stop()
    }

    @Test fun committedInlineRoomSaveAtDisconnectFailsBeforeTheWireAndPreservesTheProcessStore() = runTest(scheduler) {
        seed(); val actual = store
        val committed = CompletableDeferred<UUID>(); val release = CompletableDeferred<Unit>()
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun saveMessage(dto: MessageDTO) {
                actual.saveMessage(dto); committed.complete(dto.id); release.await()
            }
        }
        val g = RadioGeneration(28, blocked); g.start(); g.signals.set(DeviceConnectionState.READY)
        val send = backgroundScope.async {
            try { g.service.sendDirectMessage("committed at disconnect", contact); null }
            catch (failure: MessageServiceException) { failure }
        }
        runCurrent(); val id = committed.await()
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, id))?.status)
        g.signals.set(DeviceConnectionState.DISCONNECTED); release.complete(Unit)
        assertEquals(MessageServiceError.NotConnected, assertNotNull(send.await()).error)
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, id))?.status)
        assertTrue(g.firmware.sent().isEmpty())
        assertEquals(contact.id, store.fetchContact(EntityKey(radio, contact.id))?.id); g.stop()
    }

    @Test fun acceptedRoomBookkeepingFinishesBeforeDisconnectTeardownWithoutInventingAnAck() = runTest(scheduler) {
        seed(); val actual = store
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val blocked = object : PersistenceStoreProtocol by actual {
            override suspend fun updateMessageAck(key: EntityKey, ackCode: UInt, status: MessageStatus, roundTripTime: UInt?) {
                if (status == MessageStatus.SENT) { entered.complete(Unit); release.await() }
                actual.updateMessageAck(key, ackCode, status, roundTripTime)
            }
        }
        val g = RadioGeneration(29, blocked); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val send = backgroundScope.async { g.service.sendDirectMessage("accepted Room bookkeeping", contact) }
        runCurrent(); assertTrue(entered.isCompleted)
        val message = store.fetchMessages(EntityKey(radio, contact.id)).single()
        g.signals.set(DeviceConnectionState.DISCONNECTED)
        val closing = backgroundScope.async { g.service.close() }; runCurrent(); assertFalse(closing.isCompleted)
        release.complete(Unit); closing.await()
        assertFailsWith<CancellationException> { send.await() }
        val saved = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.SENT, saved.status); assertNull(saved.roundTripTime)
        assertEquals(1, g.firmware.sent().size); g.stop()
    }

    @Test
    @OriginalCase("MessageServiceSendDMBookkeepingTests::sendDirectMessage keeps ACK tracking and does not report failure when post-send bookkeeping fails()")
    fun sourceAcceptedDMKeepsAckTrackingWhenItsSelectedRealRoomRowWasDeleted() = runTest(scheduler) {
        contact = contact.copy(publicKey = Bytes(ByteArray(32) { 0xAB.toByte() }), name = "TestContact",
            typeRawValue = 1u, lastHeardTimestamp = null)
        seed()
        val g = RadioGeneration(28, sessionTimeout = 10.0)
        g.firmware.acknowledge = false
        g.firmware.acceptedAck = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
        g.firmware.suggestedTimeout = 5000u
        g.firmware.acceptanceGate = CompletableDeferred()
        g.start()
        val events = g.service.statusEvents()
        val sending = g.scope.async { g.service.sendDirectMessage("hi", contact) }
        runCurrent()
        assertEquals(1, g.firmware.sent().size)
        val saved = store.fetchMessages(EntityKey(radio, contact.id), 10, 0).first()
        store.deleteMessage(EntityKey(radio, saved.id))
        assertNull(store.fetchMessage(EntityKey(radio, saved.id)))
        g.firmware.acceptanceGate?.complete(Unit)
        assertFailsWith<Exception> { sending.await() }
        assertEquals(1, g.service.pendingAckCount)
        g.service.finishStatusEvents()
        assertFalse(events.events.toList().any { it.event is MessageStatusEvent.Failed })
        g.stop()
    }

    private suspend fun saveIncomingContact(wire: ContactMessage, resolved: ContactDTO?): MessageDTO {
        assertEquals(contact.id, resolved?.id)
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = wire.text,
            timestamp = wire.senderTimestamp.epochSecond.toUInt(), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED)
        store.saveMessage(message)
        return message
    }

    private fun incomingContactPacket(text: String, type: UByte = 0u): Bytes =
        ByteWriter().appendUInt8(ResponseCode.CONTACT_MESSAGE_RECEIVED.rawValue).append(target.prefix(6))
            .appendUInt8(0u).appendUInt8(type).appendUInt32LE(42u).append(Bytes.utf8(text)).toBytes()

    private class RoomSignals(private val token: SessionToken) : ConnectionSignals {
        private val value = MutableStateFlow(snapshot(token, DeviceConnectionState.CONNECTED))
        override val snapshot: kotlinx.coroutines.flow.StateFlow<ConnectionSnapshot> get() = value
        private val channels = mutableListOf<kotlinx.coroutines.channels.Channel<ConnectionSnapshot>>()
        fun set(rung: DeviceConnectionState) {
            val next = snapshot(token, rung); value.value = next
            channels.forEach { it.trySend(next).getOrThrow() }
        }
        override suspend fun subscribeTransitions(): ConnectionSubscription {
            val channel = kotlinx.coroutines.channels.Channel<ConnectionSnapshot>(kotlinx.coroutines.channels.Channel.UNLIMITED)
            channels += channel
            return object : ConnectionSubscription {
                override val initial = value.value
                override val transitions: Flow<ConnectionSnapshot> = flow { try { for (next in channel) emit(next) } finally { close() } }
                override fun close() { channels.remove(channel); channel.cancel() }
            }
        }
        companion object {
            fun snapshot(token: SessionToken, state: DeviceConnectionState) =
                ConnectionSnapshot(state,
                    if (state == DeviceConnectionState.DISCONNECTED) ConnectionState.Disconnected else ConnectionState.Connected,
                    null, ConnectionIntent.WantsConnection(),
                    if (state == DeviceConnectionState.DISCONNECTED) null else token, null)
        }
    }

    private inner class RoomFirmware : MeshTransport {
        private val mock = MockTransport()
        var error: UByte? = null
        var emptyChannelSlot = false
        var acknowledge = true
        var ackBeforeAcceptance = false
        var automaticTripTime: UInt? = 123u
        var acceptanceGate: CompletableDeferred<Unit>? = null
        var acceptedAck: Bytes? = null
        var suggestedTimeout: UInt = 10u
        var lastAck: Bytes? = null
        var beforeSend: (suspend (Bytes) -> Unit)? = null
        val incomingMessages = ArrayDeque<Bytes>()
        var afterNoMore: (suspend () -> Unit)? = null
        fun sent(): List<Bytes> = mock.sentData.filter { it[0] == CommandCode.SEND_MESSAGE.rawValue }
        fun channelSent(): List<Bytes> = mock.sentData.filter { it[0] == CommandCode.SEND_CHANNEL_MESSAGE.rawValue }
        fun channelQueries(): List<Bytes> = mock.sentData.filter { it[0] == CommandCode.GET_CHANNEL.rawValue }
        override suspend fun isConnected() = mock.isConnected()
        override suspend fun connect() = mock.connect()
        override suspend fun disconnect() = mock.disconnect()
        override suspend fun receivedData() = mock.receivedData()
        override suspend fun supportsWriteWithoutResponse() = false
        override suspend fun supportsPipelinedReads() = false
        override suspend fun send(data: Bytes) {
            beforeSend?.invoke(data)
            mock.send(data)
            when (CommandCode.fromRawValue(data[0])) {
                CommandCode.APP_START -> mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
                    .appendUInt8(1u).appendUInt8(22u).appendUInt8(22u).append(self)
                    .appendInt32LE(0).appendInt32LE(0).appendUInt8(0u).appendUInt8(0u).appendUInt8(0u)
                    .appendUInt32LE(915_000u).appendUInt32LE(125_000u).appendUInt8(7u).appendUInt8(5u).append(Bytes.utf8("Test")).toBytes())
                CommandCode.SEND_MESSAGE -> {
                    error?.let { mock.simulateError(it); return }
                    val input = ByteWriter().appendUInt32LE(data.readUInt32LE(3))
                        .appendUInt8((data[2].toInt() and 3).toUByte()).append(data.slice(13, data.size)).append(self).toBytes()
                    val ack = acceptedAck ?: Bytes(MessageDigest.getInstance("SHA-256").digest(input.toByteArray())).prefix(4)
                    lastAck = ack
                    if (acknowledge && ackBeforeAcceptance) pushAck(ack, automaticTripTime)
                    acceptanceGate?.await()
                    mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.MESSAGE_SENT.rawValue).appendUInt8(0u)
                        .append(ack).appendUInt32LE(suggestedTimeout).toBytes())
                    if (acknowledge && !ackBeforeAcceptance) pushAck(ack, automaticTripTime)
                }
                CommandCode.SEND_CHANNEL_MESSAGE -> {
                    error?.let { mock.simulateError(it); return }
                    mock.simulateOK()
                }
                CommandCode.GET_CONTACT_BY_KEY -> mock.simulateError(2u)
                CommandCode.GET_CHANNEL -> if (emptyChannelSlot) {
                    mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.CHANNEL_INFO.rawValue)
                        .appendUInt8(data[1]).append(Bytes(ByteArray(32))).append(Bytes(ByteArray(16))).toBytes())
                } else mock.simulateError(2u)
                CommandCode.RESET_PATH -> mock.simulateOK()
                CommandCode.GET_MESSAGE -> {
                    val empty = incomingMessages.isEmpty()
                    mock.simulateReceive(if (empty) Bytes.of(ResponseCode.NO_MORE_MESSAGES.rawValue.toInt()) else incomingMessages.removeFirst())
                    if (empty) afterNoMore?.also { afterNoMore = null }?.invoke()
                }
                else -> throw AssertionError("Unexpected raw Room-test command")
            }
        }
        suspend fun pushAck(ack: Bytes, tripTime: UInt? = 123u) {
            val packet = ByteWriter().appendUInt8(ResponseCode.ACK.rawValue).append(ack)
            if (tripTime != null) packet.appendUInt32LE(tripTime)
            mock.simulateReceive(packet.toBytes())
        }
        suspend fun pushPacket(packet: Bytes) { mock.simulateReceive(packet) }
    }
}
