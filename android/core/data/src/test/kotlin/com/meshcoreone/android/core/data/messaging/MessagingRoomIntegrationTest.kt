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
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.data.backup.BackupModelKind
import com.meshcoreone.android.core.data.backup.backupEnvelope
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.data.repository.OriginalCase
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
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
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFence
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
        return builder.addCallback(DirectAckStorage.callback).addMigrations(
            MeshCoreDatabase.MIGRATION_1_2, MeshCoreDatabase.MIGRATION_2_3,
            MeshCoreDatabase.MIGRATION_3_4, DirectAckStorage.migration4To5, DirectAckStorage.migration5To6,
        )
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
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
    private suspend fun seedChannel(index: UByte, name: String, secret: Bytes): ChannelOccupantSnapshot {
        store.saveChannel(radio, ChannelInfo(index, name, secret))
        return assertNotNull(store.fetchChannelOccupantSnapshot(radio, index))
    }
    private suspend fun channelMessage(text: String, origin: ChannelHistoryScope): MessageDTO =
        store.createOrReconcileChannelMessage(
            ChannelMessageCreation(UUID.randomUUID(), origin, text),
            timestamp = 1u,
            createdAt = base,
        )
    private suspend fun admitChannelQueueRow(
        message: MessageDTO,
        origin: ChannelHistoryScope,
        isResend: Boolean,
        initialAttemptCount: Long,
    ): PendingSendDTO {
        require(initialAttemptCount in 0..Int.MAX_VALUE.toLong())
        val pendingID = UUID.randomUUID()
        val envelope = ChannelMessageEnvelope(
            message.id, origin.channelIndex, isResend, message.text, message.timestamp, "Test Radio",
            origin.expectedOccupantID,
        )
        store.insertPendingSendAssigningSequence(
            PendingSendDTO.fromEnvelope(
                envelope,
                message.radioId,
                id = pendingID,
                enqueuedAt = message.createdAt,
            ),
        )
        val pendingKey = EntityKey(message.radioId, pendingID)
        repeat(initialAttemptCount.toInt()) {
            store.incrementReadyQueuedChannelAttemptCount(pendingKey, message.id)
        }
        return checkNotNull(
            store.fetchPendingSendsForMessage(EntityKey(message.radioId, message.id))
                .singleOrNull { it.id == pendingID },
        )
    }
    private suspend fun RadioGeneration.reconcileSeededChannel(
        channels: ChannelService, snapshot: ChannelOccupantSnapshot,
    ) = store.withChannelOperation { scoped, identity ->
        val index = snapshot.channel.index
        val observed = channels.fetchChannel(index)
            ?: error("Seeded channel slot ${index.toInt()} was not present on the test radio")
        check(observed.index == index && observed.name == snapshot.channel.name &&
            observed.secret == snapshot.channel.secret
        ) { "Test radio channel did not match the seeded persistence slot" }
        val persisted = scoped.batchSaveChannels(
            radio, listOf(observed).snapshot(), SnapshotList.empty(), null,
        )
        check(persisted.any { it.index == index })
        val current = scoped.fetchChannelOccupantSnapshot(radio, index)
            ?: error("Reconciled test channel lost its persisted occupant")
        check(current.occupantID == snapshot.occupantID) {
            "Reconciliation changed the fixture's seeded channel occupant"
        }
        channelDispatchFence.recordReconciled(channelDispatchLease, identity, listOf(index))
        firmware.markChannelQueries()
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
        val channelDispatchFence = ChannelDispatchFence(ChannelIdentityMutex.PROCESS)
        val channelDispatchLease = channelDispatchFence.openSession(
            token, session, ChannelDispatchAdmission(token, session),
        )
        val service = MessageService(token, self, session, persistence, signals, scope, config, clock,
            reporter = MessagingIssueReporter { diagnostics += it },
            directAckAuthority = persistence as? DirectAckAuthority ?: store,
            directPendingCompletionAuthority = persistence as? DirectPendingCompletionAuthority ?: store,
            channelDispatchLease = channelDispatchLease,
            directQueueAuthority = persistence as? DirectQueueAuthority ?: store)
        val queue = ChatSendQueueService(token, persistence, service, channelQuery,
            OutgoingChannelReactionIndexer { _, _, _, _, _ -> }, signals, scope, queueConfig, clock,
            reporter = MessagingIssueReporter { diagnostics += it })
        init { generations += this }
        suspend fun start() { session.start() }
        suspend fun stop() {
            try {
                queue.shutdown(); service.close(); session.stop()
            } finally {
                channelDispatchFence.retire(channelDispatchLease)
                scope.cancel()
            }
        }
        fun poller() = MessagePollingService(token, session, store, signals, scope, clock).also { pollers += it }
    }

    private open inner class DirectRoles(private val actual: RoomPersistenceStore) : PersistenceStoreProtocol by actual,
        DirectAckAuthority by actual, DirectPendingCompletionAuthority by actual, DirectQueueAuthority by actual,
        DirectMessageCreationAuthority by actual {
        private val keys = mutableMapOf<DirectAckAttempt, EntityKey>()
        private val intents = mutableMapOf<DirectAckIntent, EntityKey>()
        protected fun attemptKey(attempt: DirectAckAttempt) = requireNotNull(keys[attempt])
        override suspend fun captureDirectAckIntent(
            lease: DirectAckLease, key: EntityKey, requestId: UUID, mode: DirectAckMode, pendingSendId: UUID?,
        ): DirectAckIntentResult = actual.captureDirectAckIntent(lease, key, requestId, mode, pendingSendId).also {
            when (it) {
                is DirectAckIntentResult.Captured -> intents[it.intent] = key
                is DirectAckIntentResult.AlreadyCaptured -> intents[it.intent] = key
                is DirectAckIntentResult.Rejected -> Unit
            }
        }
        override suspend fun captureDirectQueueIntent(
            selection: DirectQueueSelection, requestId: UUID, mode: DirectAckMode,
        ): DirectAckIntentResult = actual.captureDirectQueueIntent(selection, requestId, mode).also {
            when (it) {
                is DirectAckIntentResult.Captured -> intents[it.intent] = it.receipt.key
                is DirectAckIntentResult.AlreadyCaptured -> intents[it.intent] = it.receipt.key
                is DirectAckIntentResult.Rejected -> Unit
            }
        }
        override suspend fun captureCreatedDirectIntent(creation: DirectMessageCreation): DirectAckIntentResult =
            actual.captureCreatedDirectIntent(creation).also {
                when (it) {
                    is DirectAckIntentResult.Captured -> intents[it.intent] = it.receipt.key
                    is DirectAckIntentResult.AlreadyCaptured -> intents[it.intent] = it.receipt.key
                    is DirectAckIntentResult.Rejected -> Unit
                }
            }
        override suspend fun captureCreatedDirectRetryIntent(
            creation: DirectMessageCreation, mode: DirectAckMode,
        ): DirectAckIntentResult = actual.captureCreatedDirectRetryIntent(creation, mode).also {
            when (it) {
                is DirectAckIntentResult.Captured -> intents[it.intent] = it.receipt.key
                is DirectAckIntentResult.AlreadyCaptured -> intents[it.intent] = it.receipt.key
                is DirectAckIntentResult.Rejected -> Unit
            }
        }
        override suspend fun beginDirectAckAttempt(
            intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
        ): DirectAckBeginResult {
            val key = requireNotNull(intents[intent])
            val result = actual.beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode)
            when (result) {
                is DirectAckBeginResult.Begun -> keys[result.attempt] = key
                is DirectAckBeginResult.AlreadyBegun -> keys[result.attempt] = key
                is DirectAckBeginResult.Rejected -> Unit
            }
            return result
        }
    }

    private inner class DirectCompletionBarrier : PersistenceStoreProtocol by store, DirectAckAuthority by store,
        DirectPendingCompletionAuthority by store, DirectQueueAuthority by store, DirectMessageCreationAuthority by store {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<UUID>()
        var bumps = 0
        var contactReads = 0
        var contactReadsAtCommit = 0
        var throwAfterCommit = false
        var rejectContactAfterCommit = false
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("lost conditional pending completion return"))

        override suspend fun bumpDirectQueueSelection(
            selection: DirectQueueSelection, requestId: UUID,
        ): DirectQueueBumpResult {
            bumps++
            return store.bumpDirectQueueSelection(selection, requestId)
        }
        override suspend fun fetchContact(key: EntityKey): ContactDTO? {
            check(!rejectContactAfterCommit) { "A completed direct send must not revalidate contact or dispatch again" }
            contactReads++
            return store.fetchContact(key)
        }
        override suspend fun completeDirectPending(
            attempt: DirectAckAttempt, pendingSendId: UUID, requestId: UUID,
        ): DirectAckMutationResult {
            requests += requestId
            entered.complete(Unit); release.await()
            val result = store.completeDirectPending(attempt, pendingSendId, requestId)
            lostReturn()
            return result
        }
        override suspend fun completeDirectPendingWithoutDispatch(
            completion: DirectPendingCompletion, completionRequestId: UUID,
        ): DirectPendingCompletionResult {
            requests += completionRequestId
            entered.complete(Unit); release.await()
            val result = store.completeDirectPendingWithoutDispatch(completion, completionRequestId)
            lostReturn()
            return result
        }
        private fun lostReturn() {
            if (throwAfterCommit) {
                throwAfterCommit = false
                contactReadsAtCommit = contactReads
                rejectContactAfterCommit = true
                throw fault
            }
        }
    }

    @Test fun ownQueuedPendingCompletionPreservesExhaustedSentLateAckAuthority() = runTest(scheduler) {
        seed()
        val barrier = DirectCompletionBarrier()
        val g = RadioGeneration(130, barrier, MessageServiceConfig(maxAttempts = 1, minTimeout = 0.0))
        g.start(); g.service.startEventMonitoring(); g.firmware.acknowledge = false
        val message = g.service.createPendingMessage("late ACK after dequeue", contact)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(pending)
        g.signals.set(DeviceConnectionState.READY); g.queue.hydrate(); runCurrent()
        advanceTimeBy(20); runCurrent()
        assertTrue(barrier.entered.isCompleted)
        assertEquals(1L, store.fetchPendingSends(radio).single().attemptCount)
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(1, g.service.pendingAckCount)
        barrier.release.complete(Unit); runCurrent(); g.queue.awaitDrainCompletion()
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        val code = assertNotNull(g.firmware.lastAck)
        g.firmware.pushAck(code, 123u); runCurrent()
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(code.readUInt32LE(0), delivered.ackCode)
        assertEquals(123u, delivered.roundTripTime)
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(1, g.firmware.sent().size)
        assertEquals(1, barrier.bumps)
        g.stop()
    }

    @Test fun queuedOwnCompletionLostReturnReplaysOnlyBookkeeping() = runTest(scheduler) {
        completionLostReturn(noDispatch = false)
    }

    @Test fun queuedZeroBudgetCompletionLostReturnReplaysWithoutAnyWireOrStatusPromotion() = runTest(scheduler) {
        completionLostReturn(noDispatch = true)
    }

    private suspend fun TestScope.completionLostReturn(noDispatch: Boolean) {
        seed()
        val barrier = DirectCompletionBarrier().apply { throwAfterCommit = true }
        val g = RadioGeneration(if (noDispatch) 132 else 131, barrier,
            MessageServiceConfig(maxAttempts = if (noDispatch) 0 else 1, minTimeout = 0.0))
        g.start(); g.firmware.acknowledge = false
        val message = g.service.createPendingMessage("lost dequeue return", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio))
        g.signals.set(DeviceConnectionState.READY); g.queue.hydrate(); runCurrent()
        if (!noDispatch) { advanceTimeBy(20); runCurrent() }
        assertTrue(barrier.entered.isCompleted)
        barrier.release.complete(Unit); runCurrent()
        g.queue.transportDidOpen(); runCurrent(); g.queue.awaitDrainCompletion()
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertEquals(2, barrier.requests.size)
        assertEquals(1, barrier.requests.distinct().size)
        assertEquals(1, barrier.bumps)
        assertEquals(barrier.contactReadsAtCommit, barrier.contactReads)
        assertEquals(if (noDispatch) 0 else 1, g.firmware.sent().size)
        val row = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(if (noDispatch) MessageStatus.PENDING else MessageStatus.SENT, row.status)
        assertEquals(message.sendCount, row.sendCount)
        g.stop()
    }

    @Test fun deliveredQueuedSendRecoversItsPendingRowAfterReconnectWithoutAnotherWire() = runTest(scheduler) {
        seed()
        val barrier = DirectCompletionBarrier()
        val first = RadioGeneration(270, barrier, MessageServiceConfig(maxAttempts = 1, minTimeout = 0.0))
        first.start(); first.service.startEventMonitoring()
        val message = first.service.createPendingMessage("delivered before dequeue", contact)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(pending)
        first.signals.set(DeviceConnectionState.READY); first.queue.hydrate(); runCurrent()
        assertTrue(barrier.entered.isCompleted)
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(1, first.firmware.sent().size)
        assertEquals(1L, store.fetchPendingSends(radio).single().attemptCount)
        first.stop()

        val next = RadioGeneration(271)
        next.start(); next.signals.set(DeviceConnectionState.READY); next.queue.hydrate()
        runCurrent(); next.queue.awaitDrainCompletion()
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, message.id)))
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertTrue(next.firmware.sent().isEmpty())
        assertEquals(0, next.queue.queuedEnvelopeCount)
        next.stop()
    }

    @Test fun deliveredQueuedSendCannotRecoverAnIdenticalPendingReplacementAfterReconnect() = runTest(scheduler) {
        seed()
        val barrier = DirectCompletionBarrier()
        val first = RadioGeneration(272, barrier, MessageServiceConfig(maxAttempts = 1, minTimeout = 0.0))
        first.start(); first.service.startEventMonitoring()
        val message = first.service.createPendingMessage("replacement after delivery", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio))
        first.signals.set(DeviceConnectionState.READY); first.queue.hydrate(); runCurrent()
        assertTrue(barrier.entered.isCompleted)
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        val replacement = store.fetchPendingSends(radio).single()
        first.stop()
        store.upsertPendingSend(replacement)

        val next = RadioGeneration(273)
        next.start(); next.signals.set(DeviceConnectionState.READY); next.queue.hydrate()
        runCurrent(); next.queue.awaitDrainCompletion()
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(listOf(replacement), store.fetchPendingSends(radio))
        assertTrue(next.firmware.sent().isEmpty())
        assertEquals(1, next.queue.queuedEnvelopeCount)
        next.stop()
    }

    @Test fun deliveredQueueRecoveryReplaysLostCaptureWithoutDispatch() = runTest(scheduler) {
        deliveredQueueRecoveryLostReturn(loseCapture = true, replaceAfterCompletion = false)
    }

    @Test fun deliveredQueueRecoveryReplaysLostCompletionWithoutDispatch() = runTest(scheduler) {
        deliveredQueueRecoveryLostReturn(loseCapture = false, replaceAfterCompletion = false)
    }

    @Test fun deliveredQueueRecoveryLostCompletionCannotDeleteItsReplacement() = runTest(scheduler) {
        deliveredQueueRecoveryLostReturn(loseCapture = false, replaceAfterCompletion = true)
    }

    private suspend fun TestScope.deliveredQueueRecoveryLostReturn(loseCapture: Boolean, replaceAfterCompletion: Boolean) {
        seed()
        val barrier = DirectCompletionBarrier()
        val first = RadioGeneration(276, barrier, MessageServiceConfig(maxAttempts = 1, minTimeout = 0.0))
        first.start(); first.service.startEventMonitoring()
        val message = first.service.createPendingMessage("recover delivered result", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio))
        first.signals.set(DeviceConnectionState.READY); first.queue.hydrate(); runCurrent()
        assertTrue(barrier.entered.isCompleted)
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        val original = store.fetchPendingSends(radio).single()
        first.stop()

        val captures = mutableListOf<UUID>()
        val completions = mutableListOf<UUID>()
        var lost = false
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("lost recovery return"))
        val racing = object : DirectRoles(store) {
            override suspend fun captureDirectPendingCompletion(
                lease: DirectAckLease, key: EntityKey, pendingSendId: UUID, captureRequestId: UUID,
                reason: DirectPendingCompletionReason,
            ): DirectPendingCaptureResult {
                captures += captureRequestId
                val result = super.captureDirectPendingCompletion(lease, key, pendingSendId, captureRequestId, reason)
                if (loseCapture && !lost && result is DirectPendingCaptureResult.Captured) {
                    lost = true
                    throw fault
                }
                return result
            }

            override suspend fun completeDirectPendingWithoutDispatch(
                completion: DirectPendingCompletion, completionRequestId: UUID,
            ): DirectPendingCompletionResult {
                completions += completionRequestId
                val result = super.completeDirectPendingWithoutDispatch(completion, completionRequestId)
                if (!loseCapture && !lost && result is DirectPendingCompletionResult.Applied) {
                    lost = true
                    throw fault
                }
                return result
            }
        }
        val next = RadioGeneration(277, racing)
        next.start(); next.signals.set(DeviceConnectionState.READY); next.queue.hydrate(); runCurrent()
        assertTrue(lost)
        assertEquals(if (loseCapture) listOf(original) else emptyList(), store.fetchPendingSends(radio))
        assertTrue(next.firmware.sent().isEmpty())
        if (replaceAfterCompletion) store.upsertPendingSend(original)
        next.queue.transportDidOpen(); runCurrent(); next.queue.awaitDrainCompletion()
        assertEquals(if (loseCapture) 2 else 1, captures.size)
        assertEquals(1, captures.distinct().size)
        assertEquals(if (loseCapture) 1 else 2, completions.size)
        assertEquals(1, completions.distinct().size)
        assertEquals(if (replaceAfterCompletion) listOf(original) else emptyList(), store.fetchPendingSends(radio))
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, message.id)))
        assertTrue(next.firmware.sent().isEmpty())
        next.stop()
    }

    @Test fun noDispatchCaptureCannotDeleteSameIdPendingReplacementAfterSuspension() = runTest(scheduler) {
        seed()
        val barrier = DirectCompletionBarrier()
        val g = RadioGeneration(133, barrier)
        g.start()
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "already delivered",
            timestamp = 100u, status = MessageStatus.DELIVERED, roundTripTime = 321u, ackCode = 0x1234u)
        store.saveMessage(message)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(pending)
        g.signals.set(DeviceConnectionState.READY); g.queue.hydrate(); runCurrent()
        assertTrue(barrier.entered.isCompleted)
        val replacement = store.fetchPendingSends(radio).single().copy(messageText = "replacement envelope")
        store.upsertPendingSend(replacement)
        barrier.release.complete(Unit); runCurrent()
        assertEquals(listOf(replacement), store.fetchPendingSends(radio))
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertTrue(g.firmware.sent().isEmpty())
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directCompletionStale.${pending.id}"
        })
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(replacement), store.fetchPendingSends(radio))
        assertTrue(g.firmware.sent().isEmpty())
        g.stop()
        assertEquals(listOf(replacement), store.fetchPendingSends(radio))
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
    }

    @Test fun missingContactWithACompetingIntentParksWithoutAnImmediateRetryLoop() = runTest(scheduler) {
        seed()
        val ghost = contact.copy(id = UUID.randomUUID())
        val message = MessageDTO(
            radioId = radio, contactID = ghost.id, text = "missing contact", timestamp = 100u,
            status = MessageStatus.PENDING,
        )
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, ghost.id), radio)
        store.saveMessage(message)
        store.upsertPendingSend(pending)
        var cleanups = 0
        val actual = store
        val competing = object : DirectRoles(actual) {
            override suspend fun captureDirectQueueSelection(
                lease: DirectAckLease, key: EntityKey, pendingSendId: UUID,
            ): DirectQueueCaptureResult {
                val selection = super.captureDirectQueueSelection(lease, key, pendingSendId)
                assertIs<DirectAckIntentResult.Captured>(
                    actual.captureDirectAckIntent(lease, key, UUID.randomUUID(), DirectAckMode.SEND, pendingSendId),
                )
                return selection
            }

            override suspend fun mutateDirectQueueSelection(
                selection: DirectQueueSelection, requestId: UUID, mutation: DirectQueueMutation,
            ): DirectQueueMutationResult {
                if (mutation == DirectQueueMutation.MissingContact) cleanups++
                return super.mutateDirectQueueSelection(selection, requestId, mutation)
            }
        }
        val g = RadioGeneration(232, competing)
        g.start(); g.signals.set(DeviceConnectionState.READY)
        g.queue.hydrate(); runCurrent()
        assertEquals(1, cleanups)
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(EntityKey(radio, message.id)))
        assertTrue(g.firmware.sent().isEmpty())
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "park.${message.id}"
        })
        advanceTimeBy(1_000); runCurrent()
        assertEquals(1, cleanups)
        g.queue.transportDidOpen(); runCurrent()
        assertEquals(2, cleanups)
        assertEquals(listOf(pending), store.fetchPendingSendsForMessage(EntityKey(radio, message.id)))
        g.stop()
    }

    @Test fun cancelledUnbegunIntentReleasesTheBlockedEarlierAck() = runTest(scheduler) {
        exerciseAbandonedIntent(cancel = true)
    }

    @Test fun lostIntentCaptureReturnReleasesTheBlockedEarlierAck() = runTest(scheduler) {
        exerciseAbandonedIntent(cancel = false)
    }

    @Test fun lostIntentRetirementReturnStillReleasesTheBlockedEarlierAck() = runTest(scheduler) {
        exerciseAbandonedIntent(cancel = false, loseRetirementReturn = true)
    }

    @Test fun retryCreationCannotSendAnIdenticalReplacementFromItsCallback() = runTest(scheduler) {
        retryCreationReplacement(maxAttempts = 1)
    }

    @Test fun zeroAttemptRetryCreationCannotReturnAnIdenticalReplacementAsItsOwn() = runTest(scheduler) {
        retryCreationReplacement(maxAttempts = 0)
    }

    @Test fun retryCallbackPayloadEditsRequireASeparateExplicitSend() = runTest(scheduler) {
        seed()
        val edits: List<(MessageDTO) -> MessageDTO> = listOf(
            { it.copy(text = "edited by callback") },
            { it.copy(timestamp = it.timestamp - 1u) },
            { it.copy(text = "edited together", timestamp = it.timestamp - 1u) },
        )
        for ((index, edit) in edits.withIndex()) {
            val g = RadioGeneration(235L + index, config = MessageServiceConfig(maxAttempts = 1))
            g.start(); g.service.startEventMonitoring()
            val events = g.service.statusEvents()
            var edited: MessageDTO? = null
            val failure = assertFailsWith<PersistenceStoreException> {
                g.service.sendMessageWithRetry("before callback", contact, onMessageCreated = {
                    edited = edit(it)
                    store.saveMessage(assertNotNull(edited))
                })
            }
            assertEquals(
                PersistenceStoreError.SaveFailed("Direct ACK authority: retry creation payload changed"),
                failure.error,
            )
            val expected = assertNotNull(edited)
            assertEquals(expected, store.fetchMessage(EntityKey(radio, expected.id)))
            assertEquals(0, db.directAcks().currentIntents(radio.value, expected.id))
            assertEquals(0, db.directAcks().currentUnresolvedClaims(radio.value, expected.id))
            assertTrue(g.firmware.sent().isEmpty())

            val explicit = g.service.sendPendingDirectMessage(expected.id, contact, preserveTimestamp = true)
            val wire = g.firmware.sent().single()
            assertEquals(Bytes.utf8(expected.text), wire.slice(13, wire.size))
            assertEquals(expected.timestamp, wire.readUInt32LE(3))
            assertEquals(expected.text, explicit.text)
            assertEquals(explicit, store.fetchMessage(EntityKey(radio, expected.id)))
            assertTrue(completedStatuses(g, events).none { it is MessageStatusEvent.Failed })
            g.stop()
        }
    }

    @Test fun retryCallbackStatusEditPreservesTheOriginalAuthorizedPayload() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(238, config = MessageServiceConfig(maxAttempts = 1))
        g.start(); g.service.startEventMonitoring()
        val result = g.service.sendMessageWithRetry("original payload", contact, onMessageCreated = {
            store.saveMessage(it.copy(status = MessageStatus.RETRYING))
        })
        val wire = g.firmware.sent().single()
        assertEquals(Bytes.utf8("original payload"), wire.slice(13, wire.size))
        assertEquals(base.epochSecond.toUInt(), wire.readUInt32LE(3))
        assertEquals("original payload", result.text)
        assertEquals(result, store.fetchMessage(EntityKey(radio, result.id)))
        g.stop()
    }

    @Test fun zeroAttemptRetryReturnsSameIncarnationCallbackEditsWithoutSending() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(236, config = MessageServiceConfig(maxAttempts = 0))
        g.start()
        var edited: MessageDTO? = null
        val result = g.service.sendMessageWithRetry("before callback", contact, onMessageCreated = {
            edited = it.copy(text = "edited by callback", status = MessageStatus.RETRYING)
            store.saveMessage(assertNotNull(edited))
        })
        assertEquals(edited, result)
        assertEquals(result, store.fetchMessage(EntityKey(radio, result.id)))
        assertTrue(g.firmware.sent().isEmpty())
        g.stop()
    }

    @Test fun zeroAttemptRetryCannotReturnAReplacementInsertedAfterItsFirstResolution() = runTest(scheduler) {
        seed()
        val entered = CompletableDeferred<MessageDTO>()
        val release = CompletableDeferred<Unit>()
        val barrier = object : DirectRoles(store) {
            private var first = true
            override suspend fun resolveCreatedDirectWithoutDispatch(
                creation: DirectMessageCreation, requestId: UUID, resolveToSent: Boolean, attempt: DirectAckAttempt?,
            ): DirectMessageCreationResolutionResult {
                val result = super.resolveCreatedDirectWithoutDispatch(creation, requestId, resolveToSent, attempt)
                if (first) {
                    first = false
                    entered.complete(assertIs<DirectMessageCreationResolutionResult.Applied>(result).receipt.message)
                    release.await()
                }
                return result
            }
        }
        val g = RadioGeneration(237, barrier, MessageServiceConfig(maxAttempts = 0))
        g.start()
        val sending = backgroundScope.async {
            assertFailsWith<PersistenceStoreException> {
                g.service.sendMessageWithRetry("zero-attempt replacement", contact)
            }
        }
        val original = entered.await()
        val key = EntityKey(radio, original.id)
        store.deleteMessage(key)
        store.saveMessage(original)
        release.complete(Unit)
        sending.await()
        assertEquals(original, store.fetchMessage(key))
        assertTrue(g.firmware.sent().isEmpty())
        g.stop()
    }

    @Test fun zeroAttemptRetryRecoversALostResolutionReturnWithoutRepeatingWork() = runTest(scheduler) {
        seed()
        val requests = mutableListOf<UUID>()
        val barrier = object : DirectRoles(store) {
            private var first = true
            override suspend fun resolveCreatedDirectWithoutDispatch(
                creation: DirectMessageCreation, requestId: UUID, resolveToSent: Boolean, attempt: DirectAckAttempt?,
            ): DirectMessageCreationResolutionResult {
                requests += requestId
                val result = super.resolveCreatedDirectWithoutDispatch(creation, requestId, resolveToSent, attempt)
                if (first) {
                    first = false
                    assertIs<DirectMessageCreationResolutionResult.Applied>(result)
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("lost zero-attempt resolution return"))
                }
                return result
            }
        }
        val g = RadioGeneration(238, barrier, MessageServiceConfig(maxAttempts = 0))
        g.start()
        val result = g.service.sendMessageWithRetry("zero-attempt readback", contact)
        assertEquals(MessageStatus.PENDING, result.status)
        assertEquals(result, store.fetchMessage(EntityKey(radio, result.id)))
        assertEquals(3, requests.size)
        assertEquals(1, requests.distinct().size)
        assertTrue(g.firmware.sent().isEmpty())
        g.stop()
    }

    private suspend fun retryCreationReplacement(maxAttempts: Long) {
        seed()
        val g = RadioGeneration(234, config = MessageServiceConfig(maxAttempts = maxAttempts))
        g.start()
        var replacement: MessageDTO? = null
        assertFailsWith<PersistenceStoreException> {
            g.service.sendMessageWithRetry("recreated while callback owns the original", contact, onMessageCreated = {
                store.deleteMessage(EntityKey(radio, it.id))
                store.saveMessage(it)
                replacement = it
            })
        }
        val saved = assertNotNull(replacement)
        assertEquals(saved, store.fetchMessage(EntityKey(radio, saved.id)))
        assertTrue(g.firmware.sent().isEmpty())
        g.stop()
    }

    private suspend fun TestScope.exerciseAbandonedIntent(cancel: Boolean, loseRetirementReturn: Boolean = false) {
        seed()
        val captured = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("lost capture return"))
        var loseRetirement = loseRetirementReturn
        val actual = store
        val barrier = object : DirectRoles(actual) {
            override suspend fun captureDirectAckIntent(
                lease: DirectAckLease, key: EntityKey, requestId: UUID, mode: DirectAckMode, pendingSendId: UUID?,
            ): DirectAckIntentResult {
                val result = super.captureDirectAckIntent(lease, key, requestId, mode, pendingSendId)
                assertIs<DirectAckIntentResult.Captured>(result)
                captured.complete(Unit)
                release.await()
                if (!cancel) throw fault
                return result
            }

            override suspend fun retireDirectAckIntent(lease: DirectAckLease, requestId: UUID): DirectAckIntentRetirement {
                val result = super.retireDirectAckIntent(lease, requestId)
                if (loseRetirement && result == DirectAckIntentRetirement.Retired) {
                    loseRetirement = false
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("lost retirement return"))
                }
                return result
            }
        }
        val g = RadioGeneration(233, barrier)
        g.start()
        val ack = Bytes.of(0x11, 0x22, 0x33, 0x44)
        val message = g.acceptedWithoutAck("earlier accepted attempt", ack)
        g.service.startEventMonitoring()
        val successor = backgroundScope.async {
            try {
                g.service.resendDirectMessage(message.id, contact, preserveTimestamp = true)
                null
            } catch (failure: PersistenceStoreException) { failure }
        }
        captured.await()
        g.firmware.pushAck(ack, 76u)
        runCurrent()
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(1, g.service.pendingAckCount)
        if (cancel) successor.cancelAndJoin()
        else {
            release.complete(Unit)
            assertSame(fault, successor.await())
        }
        runCurrent()
        assertEquals(message.copy(status = MessageStatus.DELIVERED, roundTripTime = 76u),
            store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(1, g.firmware.sent().size)
        assertFalse(loseRetirement)
        g.stop()
    }

    @Test fun actualDispatchCannotDeleteReplacementPendingOrDeliverWithItsInvalidatedClaim() = runTest(scheduler) {
        seed()
        val barrier = DirectCompletionBarrier()
        val g = RadioGeneration(134, barrier, MessageServiceConfig(maxAttempts = 1, minTimeout = 0.0))
        g.start(); g.service.startEventMonitoring(); g.firmware.acknowledge = false
        val status = g.service.statusEvents()
        val observed = mutableListOf<MessageStatusEvent>()
        val listener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            status.events.collect { observed += it.event }
        }
        val message = g.service.createPendingMessage("actual-send replacement", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio))
        g.signals.set(DeviceConnectionState.READY); g.queue.hydrate(); runCurrent()
        advanceTimeBy(20); runCurrent()
        assertTrue(barrier.entered.isCompleted)
        val sent = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.SENT, sent.status)
        val replacement = store.fetchPendingSends(radio).single().copy(messageText = "replacement envelope")
        store.upsertPendingSend(replacement)
        barrier.release.complete(Unit); runCurrent()
        assertEquals(listOf(replacement), store.fetchPendingSends(radio))
        assertEquals(sent, store.fetchMessage(EntityKey(radio, message.id)))
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directCompletionStale.${replacement.id}"
        })
        g.firmware.pushAck(assertNotNull(g.firmware.lastAck), 123u); runCurrent()
        assertEquals(sent, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(observed.none { it is MessageStatusEvent.StatusResolved && it.status == MessageStatus.DELIVERED })
        assertEquals(1, g.firmware.sent().size)
        assertEquals(1, barrier.bumps)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(listOf(replacement), store.fetchPendingSends(radio))
        assertEquals(1, g.firmware.sent().size)
        g.stop(); listener.cancelAndJoin(); status.close()
        assertEquals(listOf(replacement), store.fetchPendingSends(radio))
        assertEquals(sent, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(1, g.firmware.sent().size)
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
            runCurrent()
            assertTrue(g.diagnostics.any {
                (it as? MessagingDiagnostic.Failure)?.operation == "park.${original.id}"
            })
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
            advanceTimeBy(200)
            runCurrent()
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
        withParkedDm { g, message, row, events ->
            g.signals.set(DeviceConnectionState.CONNECTED)
            runCurrent()
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(listOf(row.copy(attemptCount = 1)), store.fetchPendingSends(radio))
            assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
            assertEquals(3, g.firmware.sent().size)
            g.signals.set(DeviceConnectionState.READY)
            runCurrent()
            advanceTimeBy(300)
            runCurrent()
            assertEquals(listOf(row.copy(attemptCount = 2)), store.fetchPendingSends(radio))
            assertEquals(6, g.firmware.sent().size)
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
            advanceTimeBy(100)
            runCurrent()
            assertTrue(g.diagnostics.any {
                (it as? MessagingDiagnostic.Failure)?.operation == "park.${message.id}"
            })
            assertEquals(listOf(row.copy(attemptCount = 1)), store.fetchPendingSends(radio))
            block(g, message, row, events)
            assertEquals(foreignMessage, store.fetchMessage(EntityKey(other, message.id)))
            assertEquals(listOf(foreignRow), store.fetchPendingSends(other))
        } finally {
            events.close()
            g.stop()
        }
    }

    @OriginalCase("ChatSendQueueServiceTests::channel drain parks deviceError(2) below disambiguateAfterAttempts()")
    @Test fun theActualNotFoundChannelSendRemainsUncertainBelowItsDisambiguationThreshold() = runTest(scheduler) {
        exerciseChannelNotFound(initialAttempts = 0, boundedWait = false)
    }

    @OriginalCase("ChatSendQueueServiceTests::channel drain treats deviceError(2) as terminal when fetchChannel confirms the channel is gone()")
    @Test fun theActualChannelServiceUnconfiguredSlotCannotAuthorizeRetirement() = runTest(scheduler) {
        exerciseChannelNotFound(initialAttempts = 7, boundedWait = false)
    }

    @OriginalCase("ChatSendQueueServiceTests::drain bounded wait re-attempts after transportWaitTimeout expires()")
    @Test fun theRealThirtySecondChannelWaitDoesNotRetryAnUncertainAttempt() = runTest(scheduler) {
        exerciseChannelNotFound(initialAttempts = 0, boundedWait = true)
    }

    private suspend fun TestScope.exerciseChannelNotFound(initialAttempts: Long, boundedWait: Boolean) {
        seed()
        val snapshot = seedChannel(0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }))
        val origin = ChannelHistoryScope.from(snapshot)
        val channel = snapshot.channel
        lateinit var channels: ChannelService
        val g = RadioGeneration(74, config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01)),
            channelQuery = MessagingChannelQuery { channels.fetchChannel(it) })
        channels = ChannelService(g.session, store, null)
        g.start()
        g.firmware.configureChannel(channel)
        g.reconcileSeededChannel(channels, snapshot)
        g.firmware.error = 2u
        g.firmware.emptyChannelSlot = true
        val message = channelMessage("Hello channel", origin)
        val row = admitChannelQueueRow(message, origin, isResend = true, initialAttemptCount = initialAttempts)
        val foreign = row.copy(
            id = UUID.randomUUID(), radioId = other, channelDispatchState = null, channelAttemptID = null,
        )
        store.upsertPendingSend(foreign)
        val events = g.service.statusEvents()
        g.queue.hydrate()
        runCurrent()
        g.signals.set(DeviceConnectionState.READY)
        runCurrent()
        withTimeout(5_000) { g.queue.awaitDrainCompletion() }
        val retained = store.fetchPendingSends(radio).single()
        assertEquals(row.id, retained.id)
        assertEquals(initialAttempts + 1, retained.attemptCount)
        assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.channelDispatchState)
        assertNotNull(retained.channelAttemptID)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(g.firmware.channelQueries().isEmpty())
        assertTrue(completedStatuses(g, events).isEmpty())
        if (boundedWait) {
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(retained, store.fetchPendingSends(radio).single())
            assertTrue(g.firmware.channelQueries().isEmpty())
        }
        assertEquals(channel, store.fetchChannel(radio, 0u))
        assertEquals(listOf(foreign), store.fetchPendingSends(other))
        g.stop()
    }

    @OriginalCase("ChatSendQueueServiceTests::Channel fetchChannel failure counter is per-envelope and does not cascade()")
    @Test fun rawChannelSendErrorsRemainUncertainWithoutQueryingOrCascading() = runTest(scheduler) {
        seed()
        val snapshot = seedChannel(0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }))
        val origin = ChannelHistoryScope.from(snapshot)
        val channel = snapshot.channel
        val queryFailure = MeshCoreException.Timeout()
        val g = RadioGeneration(29,
            config = MessageServiceConfig(poolBackoff = PoolBackoffConfig(attemptCap = 2, baseDelay = 0.01)),
            channelQuery = MessagingChannelQuery {
                delay(1_900)
                throw queryFailure
            },
            queueConfig = ChatSendQueueConfig(maxConsecutiveFetchChannelFailures = 2),
        )
        val channels = ChannelService(g.session, store, null)
        g.start()
        g.firmware.configureChannel(channel)
        g.reconcileSeededChannel(channels, snapshot)
        g.firmware.error = 2u
        val first = channelMessage("Hello channel", origin)
        val second = channelMessage("Second message", origin)
        val firstRow = admitChannelQueueRow(first, origin, isResend = true, initialAttemptCount = 7)
        val secondRow = admitChannelQueueRow(second, origin, isResend = false, initialAttemptCount = 2)
        val foreignMessage = first.copy(radioId = other)
        val foreignRow = firstRow.copy(
            id = UUID.randomUUID(), radioId = other, channelDispatchState = null, channelAttemptID = null,
        )
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
            withTimeout(5_000) { g.queue.awaitDrainCompletion() }
            val retained = store.fetchPendingSends(radio).associateBy { it.id }
            assertEquals(setOf(firstRow.id, secondRow.id), retained.keys)
            assertEquals(8L, retained.getValue(firstRow.id).attemptCount)
            assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.getValue(firstRow.id).channelDispatchState)
            assertNotNull(retained.getValue(firstRow.id).channelAttemptID)
            assertEquals(3L, retained.getValue(secondRow.id).attemptCount)
            assertEquals(QueuedChannelDispatchState.READY, retained.getValue(secondRow.id).channelDispatchState)
            assertNull(retained.getValue(secondRow.id).channelAttemptID)
            assertTrue(store.fetchQueuedChannelAttempts(EntityKey(radio, secondRow.id)).isEmpty())
            assertEquals(1, g.firmware.channelSent().size)
            assertEquals(first, store.fetchMessage(EntityKey(radio, first.id)))
            assertTrue(store.hasPendingSend(EntityKey(radio, first.id)))
            assertTrue(store.hasPendingSend(EntityKey(radio, second.id)))
            assertTrue(g.firmware.channelQueries().isEmpty())
            assertEquals(second, store.fetchMessage(EntityKey(radio, second.id)))
            assertEquals(foreignMessage, store.fetchMessage(EntityKey(other, first.id)))
            assertEquals(listOf(foreignRow), store.fetchPendingSends(other))
            assertTrue(observed.isEmpty())
            assertEquals(channel, store.fetchChannel(radio, 0u))
            val report = g.queue.shutdown()
            assertFalse(report.isComplete)
            assertEquals(2, report.issues.size)
            val retainedCauses = report.issues.map { generateSequence(it.cause) { cause -> cause.cause }.toList() }
            assertEquals(1, retainedCauses.count { causes -> causes.any { it is ChannelDispatchFailure.UnsettledDispatch } })
            assertTrue(retainedCauses.all { causes -> causes.any { it is MeshCoreException.DeviceError && it.code == 2.toUByte() } })
            assertEquals(setOf(firstRow.id, secondRow.id), store.fetchPendingSends(radio).map { it.id }.toSet())
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
        val snapshot = seedChannel(0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }))
        val g = RadioGeneration(51)
        val message = g.service.createPendingChannelMessage("Hello channel", 0u, radio)
        assertEquals(MessageDTO(id = message.id, radioId = radio, channelIndex = 0u,
            text = "Hello channel", timestamp = base.epochSecond.toUInt(), createdAt = base,
            channelOccupantID = snapshot.occupantID), message)
        assertEquals(message, store.fetchMessages(radio, 0u).single())
        assertTrue(g.firmware.channelSent().isEmpty())
        g.stop()
    }

    @Test fun pendingChannelCreationRetainsCapturedOriginAcrossReplacementWithoutAdoptingTheNewSlot() = runTest(scheduler) {
        seed()
        val original = seedChannel(0u, "Original", Bytes(ByteArray(16) { 0x11 }))
        var captured: ChannelHistoryScope? = null
        val replacing = object : PersistenceStoreProtocol by store, ChannelHistoryPersisting by store {
            override suspend fun createOrReconcileChannelMessage(
                creation: ChannelMessageCreation, timestamp: UInt, createdAt: Instant,
            ): MessageDTO {
                captured = creation.origin
                store.saveChannel(radio, ChannelInfo(0u, "Replacement", Bytes(ByteArray(16) { 0x22 })))
                return store.createOrReconcileChannelMessage(creation, timestamp, createdAt)
            }
        }
        val g = RadioGeneration(510, persistence = replacing)
        val message = g.service.createPendingChannelMessage("Original intent", 0u, radio)
        assertEquals(ChannelHistoryScope.from(original), captured)
        assertEquals(original.occupantID, message.channelOccupantID)
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        val failure = assertFailsWith<PersistenceStoreException> {
            store.validateChannelMessageOrigin(EntityKey(radio, message.id))
        }
        assertEquals(ChannelHistoryConflict.STALE_OCCUPANT, assertIs<ChannelHistoryRejected>(failure.cause).reason)
        assertNotEquals(original.occupantID, assertNotNull(store.fetchChannelOccupantSnapshot(radio, 0u)).occupantID)
        assertEquals(listOf(message), store.fetchMessages(radio, 0u).toList())
        assertTrue(store.fetchPendingSends(radio).isEmpty())
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
        val snapshot = seedChannel(0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }))
        val g = RadioGeneration(53)
        g.start()
        g.firmware.configureChannel(snapshot.channel)
        g.reconcileSeededChannel(ChannelService(g.session, store, null), snapshot)
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
            timestamp = base.epochSecond.toUInt(), createdAt = base, channelOccupantID = snapshot.occupantID), pending)
        assertEquals(pending.copy(status = MessageStatus.SENT), store.fetchMessage(EntityKey(radio, receipt.id)))
        assertEquals(listOf(MessageStatusEvent.StatusResolved(receipt.id, MessageStatus.SENT, null)),
            completedStatuses(g, events))
        assertEquals(1, g.firmware.channelSent().size)
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::sendPendingChannelMessage sets failed status on send error()")
    @Test fun disconnectedPendingChannelSendCommitsFailureInTheActualStore() = runTest(scheduler) {
        seed()
        val snapshot = seedChannel(0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }))
        val g = RadioGeneration(54)
        val message = g.service.createPendingChannelMessage("Hello channel", 0u, radio)
        assertEquals(snapshot.occupantID, message.channelOccupantID)
        val events = g.service.statusEvents()
        val failure = assertFails { g.service.sendPendingChannelMessage(message.id) }
        assertIs<ChannelDispatchFailure.UnreconciledSession>(failure.cause)
        assertEquals(message.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, message.id)))
        assertTrue(g.firmware.channelSent().isEmpty())
        assertTrue(completedStatuses(g, events).isEmpty(), "The queue owns pending-send failure publication")
        g.stop()
    }

    @OriginalCase("MessageServiceSendTests::resendChannelMessage writes .sent before broadcasting .resent and refreshes counts()")
    @Test fun channelResendCommitsItsSentStatusAndCountsBeforeTheConsumerEvent() = runTest(scheduler) {
        seed()
        val snapshot = seedChannel(0u, "Test Channel", Bytes(ByteArray(16) { 0xAA.toByte() }))
        val message = MessageDTO(radioId = radio, channelIndex = 0u, text = "Hello channel",
            timestamp = 42u, createdAt = base.minusSeconds(1), status = MessageStatus.FAILED,
            heardRepeats = 3, sendCount = 1, channelOccupantID = snapshot.occupantID)
        store.saveChannelMessage(message, ChannelHistoryScope.from(snapshot))
        val g = RadioGeneration(55)
        g.start()
        g.firmware.configureChannel(snapshot.channel)
        g.reconcileSeededChannel(ChannelService(g.session, store, null), snapshot)
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
    @Test fun genuineRawTransportFailureRetainsTheUncertainActualWire() = runTest(scheduler) {
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
        assertEquals(MessageStatus.PENDING, message.status)
        assertEquals("boom", message.text)
        assertEquals(1, g.service.pendingAckCount)
        assertEquals(1, db.directAcks().currentUnresolvedClaims(radio.value, message.id))
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${message.id}" && it.cause === cause
        })
        assertTrue(completedStatuses(g, events).none { it is MessageStatusEvent.Failed })
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
        assertEquals(1, g.service.pendingAckCount)
        advanceTimeBy(120_000); runCurrent(); g.service.checkExpiredAcks()
        assertEquals(expected, store.fetchMessage(EntityKey(radio, expected.id)))
        assertTrue(completedStatuses(g, events).none { it is MessageStatusEvent.Failed })
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
    @Test fun theRealListenerBuffersBeforeAcceptanceAndFinalizationPreservesItsFirmwareRtt() = runTest(scheduler) {
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

    @Test fun retrySenderWaitsForConfirmedAckWhenEarlyPredictionDiffers() = runTest(scheduler) {
        earlyPredictedAckThenDifferentAcceptance(queued = false)
    }

    @Test fun inlineCompletionPromotesOnlyItsExactAcceptedCurrentWire() = runTest(scheduler) {
        inlineCompletionBoundary(replaceBeforeCompletion = false)
    }

    @Test fun inlineCompletionCannotPromoteAnIdenticalReplacementAfterAcceptance() = runTest(scheduler) {
        inlineCompletionBoundary(replaceBeforeCompletion = true)
    }

    @Test fun inlineOwnCaptureRejectionFailsOnlyItsDefinitelyUnsentCreation() = runTest(scheduler) {
        unresolvedInlineGrant("capture")
    }

    @Test fun inlineOwnBeginFailureFailsOnlyItsDefinitelyUnsentCreation() = runTest(scheduler) {
        unresolvedInlineGrant("beforeBegin")
    }

    @Test fun inlineLostCommittedBeginReturnRetainsUncertaintyWithoutInventingAGrant() = runTest(scheduler) {
        unresolvedInlineGrant("afterBegin")
    }

    private suspend fun TestScope.unresolvedInlineGrant(stage: String) {
        seed()
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("Controlled $stage failure"))
        var beginsCommitted = 0
        val actual = store
        val barrier = object : DirectRoles(actual) {
            override suspend fun captureCreatedDirectIntent(creation: DirectMessageCreation): DirectAckIntentResult =
                if (stage == "capture") {
                DirectAckIntentResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
            } else super.captureCreatedDirectIntent(creation)

            override suspend fun beginDirectAckAttempt(
                intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
            ): DirectAckBeginResult {
                if (stage == "beforeBegin") throw fault
                val result = super.beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode)
                assertIs<DirectAckBeginResult.Begun>(result)
                beginsCommitted++
                throw fault
            }
        }
        val g = RadioGeneration(143, barrier)
        g.start()
        val events = g.service.statusEvents()
        val thrown = assertFailsWith<PersistenceStoreException> {
            g.service.sendDirectMessage("unresolved own grant", contact)
        }
        if (stage != "capture") assertSame(fault, thrown)
        assertEquals(if (stage == "afterBegin") 1 else 0, beginsCommitted)
        val saved = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertEquals(if (stage == "afterBegin") MessageStatus.PENDING else MessageStatus.FAILED, saved.status)
        assertNull(saved.ackCode)
        assertEquals(0, g.service.pendingAckCount)
        assertTrue(g.firmware.sent().isEmpty())
        val failures = g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
            .filter { it.operation == "failMessage.${saved.id}" }
        if (stage == "afterBegin") {
            val diagnostic = failures.single()
            assertIs<PersistenceStoreException>(diagnostic.cause)
            assertTrue(thrown.suppressed.any { it === diagnostic.cause })
        } else {
            assertTrue(failures.isEmpty())
            assertTrue(thrown.suppressed.isEmpty())
        }
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.StaleResult>().none {
            it.operation == "inlineFailureAuthority"
        })
        assertEquals(
            if (stage == "afterBegin") emptyList() else listOf(MessageStatusEvent.Failed(saved.id)),
            completedStatuses(g, events),
        )
        g.stop()
        assertEquals(saved, store.fetchMessage(EntityKey(radio, saved.id)))
    }

    @Test fun inlineAcceptanceCannotBindToAResendRegisteredWhileItsResponseIsHeld() = runTest(scheduler) {
        competingInlineAttempt(beginFailure = false)
    }

    @Test fun inlineLostBeginReturnCannotFailAnActuallyRegisteredSuccessor() = runTest(scheduler) {
        competingInlineAttempt(beginFailure = true)
    }

    @Test fun delayedSuccessfulInlineBeginCannotReplaceANewerRegisteredResend() = runTest(scheduler) {
        seed()
        val firstBegin = CompletableDeferred<Unit>()
        val releaseFirstBegin = CompletableDeferred<Unit>()
        val resendAtWire = CompletableDeferred<Unit>()
        val releaseResend = CompletableDeferred<Unit>()
        var begins = 0
        val actual = store
        val barrier = object : DirectRoles(actual) {
            override suspend fun beginDirectAckAttempt(
                intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
            ): DirectAckBeginResult {
                val result = super.beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode)
                if (result is DirectAckBeginResult.Begun && ++begins == 1) {
                    firstBegin.complete(Unit)
                    releaseFirstBegin.await()
                }
                return result
            }
        }
        val g = RadioGeneration(231, barrier, MessageServiceConfig(maxAttempts = 1))
        g.start(); g.service.startEventMonitoring()
        g.firmware.acknowledge = false
        val resendCode = Bytes.of(0x51, 0x62, 0x73, 0x84)
        g.firmware.acceptedAck = resendCode
        g.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_MESSAGE.rawValue) {
                resendAtWire.complete(Unit)
                releaseResend.await()
            }
        }
        val inline = async { runCatching { g.service.sendDirectMessage("delayed begin return", contact) } }
        runCurrent(); assertTrue(firstBegin.isCompleted)
        val original = store.fetchMessages(EntityKey(radio, contact.id)).single()
        val resend = async { g.service.resendDirectMessage(original.id, contact, preserveTimestamp = true) }
        try {
            runCurrent(); assertTrue(resendAtWire.isCompleted); assertEquals(2, begins)
            releaseFirstBegin.complete(Unit); runCurrent()
            assertIs<PersistenceStoreException>(inline.await().exceptionOrNull())
            assertEquals(original, store.fetchMessage(EntityKey(radio, original.id)))
            assertEquals(1, g.service.pendingAckCount)
            assertTrue(g.firmware.sent().isEmpty())
            releaseResend.complete(Unit); runCurrent()
            g.firmware.pushAck(resendCode, 123u); runCurrent()
            val delivered = resend.await()
            assertEquals(MessageStatus.DELIVERED, delivered.status)
            assertEquals(resendCode.readUInt32LE(0), delivered.ackCode)
            assertEquals(2L, delivered.sendCount)
            assertEquals(1, g.firmware.sent().size)
        } finally {
            releaseFirstBegin.complete(Unit); releaseResend.complete(Unit)
            inline.cancelAndJoin(); resend.cancelAndJoin(); g.stop()
        }
    }

    @Test fun identicalInlineAckCannotSettleARegisteredResendBeforeItsFirstWire() = runTest(scheduler) {
        competingInlineAttempt(beginFailure = false, identicalCode = true)
    }

    @Test fun delayedIdenticalInlineAckCannotSettleResendAfterItsOwnAcceptance() = runTest(scheduler) {
        competingInlineAttempt(beginFailure = false, identicalCode = true, ackAfterResendAccepted = true)
    }

    @Test fun delayedAckFromAnAcceptedClaimCannotDeliverALaterClaimWithTheSameActualCode() = runTest(scheduler) {
        sameActualAcrossClaims(loseAcceptanceRecord = false)
    }

    @Test fun unknownPersistedActualAfterEmissionCannotAuthorizeALaterClaimsAck() = runTest(scheduler) {
        sameActualAcrossClaims(loseAcceptanceRecord = true)
    }

    private suspend fun TestScope.sameActualAcrossClaims(loseAcceptanceRecord: Boolean) {
        seed()
        val actual = store
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("Controlled missing actual-code commit"))
        var lostAcceptance = false
        val registrations = mutableListOf<DirectAckAttempt>()
        val requests = mutableListOf<UUID>()
        val acceptedAttempts = mutableListOf<DirectAckAttempt>()
        val deliveredAttempts = mutableListOf<DirectAckAttempt>()
        val recording = object : DirectRoles(actual) {
            override suspend fun beginDirectAckAttempt(
                intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
            ): DirectAckBeginResult = super.beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode).also {
                if (it is DirectAckBeginResult.Begun) {
                    registrations += it.attempt
                    requests += it.receipt.requestId
                }
            }
            override suspend fun recordDirectSendAccepted(
                attempt: DirectAckAttempt, wireRequestId: UUID, ackCode: UInt,
            ): DirectAckMutationResult {
                if (loseAcceptanceRecord && !lostAcceptance) {
                    lostAcceptance = true
                    throw fault
                }
                return actual.recordDirectSendAccepted(attempt, wireRequestId, ackCode).also {
                    if (it is DirectAckMutationResult.Applied || it is DirectAckMutationResult.AlreadyApplied) {
                        acceptedAttempts += attempt
                    }
                }
            }
            override suspend fun settleDirectAck(
                attempt: DirectAckAttempt, ackCode: UInt, roundTripTime: UInt?,
            ): DirectAckMutationResult = actual.settleDirectAck(attempt, ackCode, roundTripTime).also {
                if (it is DirectAckMutationResult.Applied || it is DirectAckMutationResult.AlreadyApplied) {
                    deliveredAttempts += attempt
                }
            }
        }
        val g = RadioGeneration(143, recording, MessageServiceConfig(maxAttempts = 1))
        g.start(); g.service.startEventMonitoring()
        val code = Bytes.of(0x10, 0x20, 0x30, 0x40)
        val first = if (loseAcceptanceRecord) {
            g.firmware.acknowledge = false
            g.firmware.acceptedAck = code
            val saved = g.service.sendDirectMessage("same actual across claims", contact)
            assertEquals(1, g.firmware.sent().size)
            assertEquals(code, g.firmware.lastAck)
            assertTrue(acceptedAttempts.isEmpty())
            assertNull(db.directAcks().wires(radio.value, requests.single()).single().acceptedCode)
            assertEquals(MessageStatus.PENDING, saved.status)
            assertNull(saved.ackCode)
            assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "dmSent.${saved.id}" && it.cause === fault
            })
            assertEquals(saved, store.fetchMessage(EntityKey(radio, saved.id)))
            saved
        } else {
            g.acceptedWithoutAck("same actual across claims", code).also {
                assertEquals(1, acceptedAttempts.size)
            }
        }
        val events = g.service.statusEvents()
        val observed = mutableListOf<MessageStatusEvent>()
        val listener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.events.collect { observed += it.event }
        }
        val resend = async { g.service.resendDirectMessage(first.id, contact, preserveTimestamp = true) }
        try {
            runCurrent()
            assertEquals(if (loseAcceptanceRecord) 1 else 2, acceptedAttempts.size)
            assertEquals(2, registrations.size)
            assertNotSame(registrations[0], registrations[1])
            assertSame(registrations[1], acceptedAttempts.last())
            assertEquals(2, g.firmware.sent().size)
            assertEquals(code, g.firmware.lastAck)
            val beforeOldAck = assertNotNull(store.fetchMessage(EntityKey(radio, first.id)))
            assertNotEquals(MessageStatus.DELIVERED, beforeOldAck.status)
            g.firmware.pushAck(code, 999u)
            runCurrent()
            assertEquals(beforeOldAck, store.fetchMessage(EntityKey(radio, first.id)))
            assertTrue(deliveredAttempts.isEmpty())
            assertTrue(observed.isEmpty())
            assertFalse(resend.isCompleted)
            assertEquals(2, g.firmware.sent().size)
        } finally {
            withContext(NonCancellable) {
                resend.cancelAndJoin()
                listener.cancelAndJoin()
                events.close()
                g.stop()
            }
        }
        assertNotEquals(MessageStatus.DELIVERED, assertNotNull(store.fetchMessage(EntityKey(radio, first.id))).status)
    }

    @Test fun repeatedActualCodeWithinTheSameValidClaimStillDeliversAfterRetry() = runTest(scheduler) {
        seed()
        val actual = store
        val acceptedAttempts = mutableListOf<DirectAckAttempt>()
        val recording = object : DirectRoles(actual) {
            override suspend fun recordDirectSendAccepted(
                attempt: DirectAckAttempt, wireRequestId: UUID, ackCode: UInt,
            ): DirectAckMutationResult = actual.recordDirectSendAccepted(attempt, wireRequestId, ackCode).also {
                if (it is DirectAckMutationResult.Applied || it is DirectAckMutationResult.AlreadyApplied) {
                    acceptedAttempts += attempt
                }
            }
        }
        val g = RadioGeneration(144, recording,
            MessageServiceConfig(maxAttempts = 2, floodAfter = 5, minTimeout = 0.0))
        g.start(); g.service.startEventMonitoring()
        g.firmware.acknowledge = false
        val code = Bytes.of(0x10, 0x20, 0x30, 0x40)
        g.firmware.acceptedAck = code
        val events = g.service.statusEvents()
        val sending = async { g.service.sendMessageWithRetry("same claim repeated actual", contact) }
        runCurrent()
        assertEquals(1, g.firmware.sent().size)
        advanceTimeBy(13); runCurrent()
        assertEquals(2, g.firmware.sent().size)
        assertEquals(2, acceptedAttempts.size)
        assertSame(acceptedAttempts[0], acceptedAttempts[1])
        g.firmware.pushAck(code, 123u)
        runCurrent()
        val delivered = sending.await()
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(code.readUInt32LE(0), delivered.ackCode)
        assertEquals(123u, delivered.roundTripTime)
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, delivered.id)))
        assertEquals(listOf(MessageStatusEvent.StatusResolved(delivered.id, MessageStatus.DELIVERED, 123u)),
            completedStatuses(g, events).filterIsInstance<MessageStatusEvent.StatusResolved>())
        assertEquals(0, g.service.pendingAckCount)
        g.stop()
    }

    private suspend fun TestScope.competingInlineAttempt(
        beginFailure: Boolean, identicalCode: Boolean = false, ackAfterResendAccepted: Boolean = false,
    ) {
        seed()
        var inlineCode = Bytes.of(0x10, 0x20, 0x30, 0x40)
        val releaseInline = CompletableDeferred<Unit>()
        val beginHeld = CompletableDeferred<Unit>()
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("Controlled lost inline begin return"))
        val registrations = mutableListOf<Pair<DirectAckAttempt, UUID>>()
        val predictions = mutableListOf<UInt>()
        val acceptances = mutableListOf<Triple<DirectAckAttempt, UUID, UInt>>()
        val completions = mutableListOf<DirectAckAttempt>()
        val settlements = mutableListOf<DirectAckAttempt>()
        val actual = store
        val barrier = object : DirectRoles(actual) {
            override suspend fun beginDirectAckAttempt(
                intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
            ): DirectAckBeginResult {
                val result = super.beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode)
                if (result is DirectAckBeginResult.Begun) {
                    registrations += result.attempt to firstWireRequestId
                    predictions += predictedAckCode
                    if (identicalCode && registrations.size == 1) {
                        inlineCode = ByteWriter().appendUInt32LE(predictedAckCode).toBytes()
                    }
                    if (beginFailure && registrations.size == 1) {
                        beginHeld.complete(Unit)
                        releaseInline.await()
                        throw fault
                    }
                }
                return result
            }
            override suspend fun recordDirectSendAccepted(
                attempt: DirectAckAttempt, wireRequestId: UUID, ackCode: UInt,
            ): DirectAckMutationResult {
                acceptances += Triple(attempt, wireRequestId, ackCode)
                return actual.recordDirectSendAccepted(attempt, wireRequestId, ackCode)
            }
            override suspend fun applyDirectAckBookkeeping(
                attempt: DirectAckAttempt, requestId: UUID, change: DirectAckBookkeeping,
            ): DirectAckMutationResult {
                if (change is DirectAckBookkeeping.SendCompleted) completions += attempt
                return actual.applyDirectAckBookkeeping(attempt, requestId, change)
            }
            override suspend fun settleDirectAck(
                attempt: DirectAckAttempt, ackCode: UInt, roundTripTime: UInt?,
            ): DirectAckMutationResult {
                settlements += attempt
                return actual.settleDirectAck(attempt, ackCode, roundTripTime)
            }
        }
        val g = RadioGeneration(142, barrier, MessageServiceConfig(maxAttempts = 1))
        g.start(); g.service.startEventMonitoring()
        g.firmware.acknowledge = false
        val resendCode = Bytes.of(0x50, 0x60, 0x70, 0x80)
        g.firmware.acceptedAck = if (identicalCode) null else inlineCode
        val resendAtWire = CompletableDeferred<Unit>()
        val releaseResend = CompletableDeferred<Unit>()
        val inlineWireCount = if (beginFailure) 0 else 1
        g.firmware.acceptanceGate = releaseInline
        g.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_MESSAGE.rawValue && g.firmware.sent().size == inlineWireCount) {
                g.firmware.acceptedAck = resendCode
                resendAtWire.complete(Unit)
                releaseResend.await()
            }
        }
        val events = g.service.statusEvents()
        val observed = mutableListOf<MessageStatusEvent>()
        val listener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            events.events.collect { observed += it.event }
        }
        val inline = async { runCatching { g.service.sendDirectMessage("held inline response", contact) } }
        runCurrent()
        assertEquals(inlineWireCount, g.firmware.sent().size)
        assertEquals(beginFailure, beginHeld.isCompleted)
        val pending = store.fetchMessages(EntityKey(radio, contact.id)).single()
        val resend = async { g.service.resendDirectMessage(pending.id, contact, preserveTimestamp = true) }
        runCurrent()
        assertEquals(2, registrations.size)
        assertNotSame(registrations[0].first, registrations[1].first)
        assertNotEquals(registrations[0].second, registrations[1].second)
        if (identicalCode) {
            assertEquals(predictions[0], predictions[1])
            assertEquals(inlineCode.readUInt32LE(0), predictions[1])
        } else {
            assertNotEquals(inlineCode.readUInt32LE(0), predictions[1])
        }
        assertTrue(acceptances.isEmpty())
        assertTrue(completions.isEmpty())
        assertEquals(inlineWireCount, g.firmware.sent().size)
        releaseInline.complete(Unit)
        runCurrent()
        assertTrue(resendAtWire.isCompleted)
        if (identicalCode) assertEquals(inlineCode, g.firmware.lastAck)
        val inlineFailure = assertIs<PersistenceStoreException>(inline.await().exceptionOrNull())
        if (beginFailure) {
            assertSame(fault, inlineFailure)
            assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.StaleResult>().any {
                it.operation == "inlineFailureAuthority"
            })
        }
        assertTrue(acceptances.isEmpty())
        assertTrue(completions.isEmpty())
        assertTrue(settlements.isEmpty())
        assertEquals(pending, store.fetchMessage(EntityKey(radio, pending.id)))
        assertTrue(observed.isEmpty())
        val delivered = try {
            if (!ackAfterResendAccepted) {
                g.firmware.pushAck(inlineCode, 999u)
                runCurrent()
            }
            assertEquals(inlineWireCount, g.firmware.sent().size)
            assertEquals(pending, store.fetchMessage(EntityKey(radio, pending.id)))
            assertTrue(settlements.isEmpty())
            assertTrue(observed.isEmpty())
            releaseResend.complete(Unit)
            runCurrent()
            assertEquals(inlineWireCount + 1, g.firmware.sent().size)
            assertEquals(registrations[1].first, acceptances.last().first)
            assertEquals(registrations[1].second, acceptances.last().second)
            assertEquals(resendCode.readUInt32LE(0), acceptances.last().third)
            if (ackAfterResendAccepted) {
                val beforeDelayedAck = store.fetchMessage(EntityKey(radio, pending.id))
                assertNotEquals(MessageStatus.DELIVERED, assertNotNull(beforeDelayedAck).status)
                g.firmware.pushAck(inlineCode, 999u)
                runCurrent()
                assertEquals(beforeDelayedAck, store.fetchMessage(EntityKey(radio, pending.id)))
                assertEquals(inlineWireCount + 1, g.firmware.sent().size)
                assertTrue(settlements.isEmpty())
                assertTrue(observed.isEmpty())
                assertFalse(resend.isCompleted)
            }
            g.firmware.pushAck(resendCode, 123u)
            runCurrent()
            val delivered = resend.await()
            assertEquals(MessageStatus.DELIVERED, delivered.status)
            assertEquals(resendCode.readUInt32LE(0), delivered.ackCode)
            assertEquals(123u, delivered.roundTripTime)
            assertEquals(2L, delivered.sendCount)
            assertTrue(settlements.isNotEmpty())
            assertTrue(settlements.all { it === registrations[1].first })
            assertEquals(listOf(MessageStatusEvent.StatusResolved(pending.id, MessageStatus.DELIVERED, 123u)),
                observed.filterIsInstance<MessageStatusEvent.StatusResolved>())
            assertEquals(listOf(MessageStatusEvent.Resent(pending.id)), observed.filterIsInstance<MessageStatusEvent.Resent>())
            assertEquals(0, g.service.pendingAckCount)
            delivered
        } finally {
            withContext(NonCancellable) {
                releaseInline.complete(Unit)
                releaseResend.complete(Unit)
                inline.cancelAndJoin()
                resend.cancelAndJoin()
                listener.cancelAndJoin()
                events.close()
                g.stop()
            }
        }
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, pending.id)))
    }

    private suspend fun TestScope.inlineCompletionBoundary(replaceBeforeCompletion: Boolean) {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val operations = mutableListOf<Pair<UUID, UUID>>()
        val actual = store
        val barrier = object : DirectRoles(actual) {
            override suspend fun applyDirectAckBookkeeping(
                attempt: DirectAckAttempt, requestId: UUID, change: DirectAckBookkeeping,
            ): DirectAckMutationResult {
                if (change is DirectAckBookkeeping.SendCompleted) {
                    operations += requestId to change.wireRequestId
                    entered.complete(Unit)
                    release.await()
                }
                return actual.applyDirectAckBookkeeping(attempt, requestId, change)
            }
        }
        val g = RadioGeneration(141, barrier)
        g.start()
        g.firmware.acknowledge = false
        val acceptedCode = Bytes.of(0x10, 0x20, 0x30, 0x40)
        g.firmware.acceptedAck = acceptedCode
        val events = g.service.statusEvents()
        val sending = async { g.service.sendDirectMessage("inline completion boundary", contact) }
        runCurrent()
        assertTrue(entered.isCompleted)
        val id = store.fetchMessages(EntityKey(radio, contact.id)).single().id
        val accepted = assertNotNull(store.fetchMessage(EntityKey(radio, id)))
        assertEquals(MessageStatus.PENDING, accepted.status)
        assertEquals(1, g.firmware.sent().size)
        assertEquals(1, operations.size)
        assertNotEquals(operations.single().first, operations.single().second)
        if (replaceBeforeCompletion) {
            store.deleteMessage(EntityKey(radio, id))
            store.saveMessage(accepted)
            assertEquals(accepted, store.fetchMessage(EntityKey(radio, id)))
        }
        release.complete(Unit)
        runCurrent()
        val result = sending.await()
        if (replaceBeforeCompletion) {
            assertEquals(accepted, result)
            assertEquals(accepted, store.fetchMessage(EntityKey(radio, id)))
            assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "dmSent.$id" && it.cause is PersistenceStoreException
            })
            assertTrue(completedStatuses(g, events).isEmpty())
        } else {
            assertEquals(MessageStatus.SENT, result.status)
            assertEquals(acceptedCode.readUInt32LE(0), result.ackCode)
            assertNull(result.roundTripTime)
            assertEquals(result, store.fetchMessage(EntityKey(radio, id)))
            assertEquals(listOf(MessageStatusEvent.StatusResolved(id, MessageStatus.SENT, null)),
                completedStatuses(g, events))
        }
        assertEquals(1, g.firmware.sent().size)
        g.stop()
        if (replaceBeforeCompletion) assertEquals(accepted, store.fetchMessage(EntityKey(radio, id)))
    }

    @Test fun queuedSendWaitsForConfirmedAckWhenEarlyPredictionDiffers() = runTest(scheduler) {
        earlyPredictedAckThenDifferentAcceptance(queued = true)
    }

    @Test fun soleWireEarlyPredictionCannotDeliverAfterDifferentActualResponse() = runTest(scheduler) {
        earlyPredictedAckThenDifferentAcceptance(queued = false)
    }

    @Test fun soleWireLatePredictionCannotDeliverAfterDifferentActualResponse() = runTest(scheduler) {
        earlyPredictedAckThenDifferentAcceptance(queued = false, earlyPrediction = false)
    }

    @Test fun actualAckBeforeMismatchedResponseIsBufferedThenDeliversItsExactWire() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(145)
        g.start(); g.service.startEventMonitoring()
        val accepted = Bytes.of(0x10, 0x20, 0x30, 0x40)
        val gate = CompletableDeferred<Unit>()
        g.firmware.acceptedAck = accepted
        g.firmware.earlyAck = accepted
        g.firmware.ackBeforeAcceptance = true
        g.firmware.acceptanceGate = gate
        val events = g.service.statusEvents()
        val sending = async { g.service.sendDirectMessage("early actual not predicted", contact) }
        runCurrent()
        val pending = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertEquals(MessageStatus.PENDING, pending.status)
        assertNull(pending.ackCode)
        assertNull(pending.roundTripTime)
        assertFalse(sending.isCompleted)
        gate.complete(Unit); runCurrent()
        val delivered = sending.await()
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(accepted.readUInt32LE(0), delivered.ackCode)
        assertEquals(123u, delivered.roundTripTime)
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, delivered.id)))
        assertEquals(1, g.firmware.sent().size)
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf(MessageStatusEvent.StatusResolved(delivered.id, MessageStatus.DELIVERED, 123u)),
            completedStatuses(g, events))
        g.stop()
    }

    private suspend fun TestScope.earlyPredictedAckThenDifferentAcceptance(
        queued: Boolean, earlyPrediction: Boolean = true,
    ) {
        seed()
        val g = RadioGeneration(if (queued) 136 else 135, config = MessageServiceConfig(maxAttempts = 1))
        g.start(); g.service.startEventMonitoring()
        val text = "predicted before different acceptance"
        val input = ByteWriter().appendUInt32LE(base.epochSecond.toUInt()).appendUInt8(0u)
            .append(Bytes.utf8(text)).append(self).toBytes()
        val predicted = Bytes(MessageDigest.getInstance("SHA-256").digest(input.toByteArray())).prefix(4)
        val accepted = Bytes.of(0x10, 0x20, 0x30, 0x40)
        assertNotEquals(predicted, accepted)
        val gate = CompletableDeferred<Unit>()
        g.firmware.acceptedAck = accepted
        g.firmware.acknowledge = earlyPrediction
        g.firmware.earlyAck = predicted
        g.firmware.ackBeforeAcceptance = earlyPrediction
        g.firmware.acceptanceGate = gate
        val events = g.service.statusEvents()
        val sending = if (queued) {
            val message = g.service.createPendingMessage(text, contact)
            store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio))
            g.signals.set(DeviceConnectionState.READY)
            g.queue.hydrate()
            null
        } else async { g.service.sendMessageWithRetry(text, contact) }
        runCurrent()
        assertFalse(sending?.isCompleted ?: false)
        val pending = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertEquals(MessageStatus.PENDING, pending.status)
        assertNull(pending.ackCode)
        assertNull(pending.roundTripTime)
        assertEquals(1, g.service.pendingAckCount)
        assertEquals(1, g.firmware.sent().size)
        gate.complete(Unit); runCurrent()
        g.firmware.pushAck(predicted, 999u); runCurrent()
        val beforeActualAck = assertNotNull(store.fetchMessage(EntityKey(radio, pending.id)))
        assertNotEquals(MessageStatus.DELIVERED, beforeActualAck.status)
        assertNull(beforeActualAck.roundTripTime)
        g.firmware.pushAck(accepted, 123u); runCurrent()
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, pending.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(accepted.readUInt32LE(0), delivered.ackCode)
        assertEquals(123u, delivered.roundTripTime)
        if (sending != null) assertEquals(delivered, sending.await()) else g.queue.awaitDrainCompletion()
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, delivered.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(1, g.firmware.sent().size)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().none {
            it.operation == "park.${delivered.id}"
        })
        assertEquals(listOf(MessageStatusEvent.StatusResolved(delivered.id, MessageStatus.DELIVERED, 123u)),
            completedStatuses(g, events))
        g.stop()
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, delivered.id)))
    }

    @Test fun inlineIntentRejectsChangedTextBeforeBegin() = runTest(scheduler) {
        invalidateIssuedIntent(queued = false, change = "text")
    }

    @Test fun inlineIntentRejectsIdenticalMessageRecreationBeforeBegin() = runTest(scheduler) {
        invalidateIssuedIntent(queued = false, change = "recreate")
    }

    @Test fun queuedIntentRejectsChangedTextBeforeBegin() = runTest(scheduler) {
        invalidateIssuedIntent(queued = true, change = "text")
    }

    @Test fun queuedIntentRejectsIdenticalMessageRecreationBeforeBegin() = runTest(scheduler) {
        invalidateIssuedIntent(queued = true, change = "recreate")
    }

    @Test fun queuedIntentRejectsSameIdPendingReplacementBeforeBegin() = runTest(scheduler) {
        invalidateIssuedIntent(queued = true, change = "pending")
    }

    @Test fun rejectedOldAcknowledgementCannotCompleteAnInvalidatedDirectSend() = runTest(scheduler) {
        seed()
        val g = RadioGeneration(142, config = MessageServiceConfig(maxAttempts = 2, floodAfter = 5))
        g.start(); g.service.startEventMonitoring(); g.firmware.acknowledge = false
        val sending = async { runCatching { g.service.sendMessageWithRetry("original A", contact) } }
        runCurrent()
        assertEquals(1, g.firmware.sent().size)
        assertFalse(sending.isCompleted)
        val original = store.fetchMessages(EntityKey(radio, contact.id)).single()
        val replacement = original.copy(text = "replacement B")
        store.saveMessage(replacement)

        g.firmware.pushAck(assertNotNull(g.firmware.lastAck), 123u)
        runCurrent()
        advanceTimeBy(15_000); runCurrent()

        assertIs<PersistenceStoreException>(sending.await().exceptionOrNull())
        assertEquals(replacement, store.fetchMessage(EntityKey(radio, original.id)))
        assertEquals(1, g.firmware.sent().size)
        assertEquals(0, g.service.pendingAckCount)
        g.stop()
        assertEquals(replacement, store.fetchMessage(EntityKey(radio, original.id)))
    }

    @Test fun replacementQueuedDuringOldPreflightKeepsItsOwnAttemptAndIsDrained() = runTest(scheduler) {
        seed()
        lateinit var generation: RadioGeneration
        var replaced = false
        var captured: PendingSendDTO? = null
        val racing = object : DirectRoles(store) {
            override suspend fun captureDirectQueueSelection(
                lease: DirectAckLease, key: EntityKey, pendingSendId: UUID,
            ): DirectQueueCaptureResult = super.captureDirectQueueSelection(lease, key, pendingSendId).also {
                if (it is DirectQueueCaptureResult.Captured) captured = it.pending
            }
            override suspend fun bumpDirectQueueSelection(
                selection: DirectQueueSelection, requestId: UUID,
            ): DirectQueueBumpResult {
                if (!replaced) {
                    replaced = true
                    val expected = assertNotNull(captured)
                    val replacement = expected.copy(id = UUID.randomUUID(), sequence = expected.sequence + 1)
                    store.deletePendingSend(EntityKey(radio, expected.id))
                    store.upsertPendingSend(replacement)
                    generation.queue.signalDMEnqueued(assertNotNull(replacement.directMessageEnvelope()))
                    val result = store.bumpDirectQueueSelection(selection, requestId)
                    assertIs<DirectQueueBumpResult.Rejected>(result)
                    assertEquals(replacement, store.fetchPendingSendsForMessage(EntityKey(radio, expected.messageID)).single())
                    assertTrue(generation.firmware.sent().isEmpty())
                    return result
                }
                return store.bumpDirectQueueSelection(selection, requestId)
            }
        }
        generation = RadioGeneration(143, racing, MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.service.startEventMonitoring()
        val message = generation.service.createPendingMessage("replacement retry", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio))
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent(); generation.queue.awaitDrainCompletion()

        assertTrue(replaced)
        assertEquals(1, generation.firmware.sent().size)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        generation.stop()
    }

    @Test fun sameIdSuccessorSignaledDuringOldPreflightIsDrainedAsItsOwnSelection() = runTest(scheduler) {
        seed()
        lateinit var generation: RadioGeneration
        var captured: PendingSendDTO? = null
        var replaced = false
        var successorCount: Long? = null
        val racing = object : DirectRoles(store) {
            override suspend fun captureDirectQueueSelection(
                lease: DirectAckLease, key: EntityKey, pendingSendId: UUID,
            ): DirectQueueCaptureResult = super.captureDirectQueueSelection(lease, key, pendingSendId).also {
                if (it is DirectQueueCaptureResult.Captured) captured = it.pending
            }
            override suspend fun bumpDirectQueueSelection(
                selection: DirectQueueSelection, requestId: UUID,
            ): DirectQueueBumpResult {
                if (!replaced) {
                    replaced = true
                    val original = assertNotNull(captured)
                    store.deletePendingSend(EntityKey(radio, original.id))
                    val replacement = original.copy(attemptCount = 7)
                    store.upsertPendingSend(replacement)
                    generation.queue.signalDMEnqueued(assertNotNull(replacement.directMessageEnvelope()))
                    val old = super.bumpDirectQueueSelection(selection, requestId)
                    assertEquals(DirectAckRejection.INVALIDATED, assertIs<DirectQueueBumpResult.Rejected>(old).reason)
                    assertEquals(replacement, store.fetchPendingSendsForMessage(EntityKey(radio, original.messageID)).single())
                    return old
                }
                return super.bumpDirectQueueSelection(selection, requestId).also {
                    if (it is DirectQueueBumpResult.Applied) successorCount = it.receipt.attemptCount
                }
            }
        }
        generation = RadioGeneration(250, racing, MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.service.startEventMonitoring()
        val message = generation.service.createPendingMessage("same-id successor", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio))
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent(); generation.queue.awaitDrainCompletion()
        assertTrue(replaced)
        assertEquals(8L, successorCount)
        assertEquals(1, generation.firmware.sent().size)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        generation.stop()
    }

    @Test fun queuedNoDispatchLostCompletionReturnHandsOffSameIdSuccessor() = runTest(scheduler) {
        seed()
        for ((index, signal) in listOf(false, true).withIndex()) {
            lateinit var generation: RadioGeneration
            lateinit var replacement: PendingSendDTO
            val requests = mutableListOf<UUID>()
            val bumpCounts = mutableListOf<Long>()
            var inserted = false
            val racing = object : DirectRoles(store) {
                override suspend fun bumpDirectQueueSelection(
                    selection: DirectQueueSelection, requestId: UUID,
                ): DirectQueueBumpResult = super.bumpDirectQueueSelection(selection, requestId).also {
                    if (it is DirectQueueBumpResult.Applied) bumpCounts += it.receipt.attemptCount
                }
                override suspend fun completeDirectPendingWithoutDispatch(
                    completion: DirectPendingCompletion, completionRequestId: UUID,
                ): DirectPendingCompletionResult {
                    requests += completionRequestId
                    val result = super.completeDirectPendingWithoutDispatch(completion, completionRequestId)
                    if (!inserted) {
                        assertIs<DirectPendingCompletionResult.Applied>(result)
                        inserted = true
                        store.upsertPendingSend(replacement)
                        if (signal) generation.queue.signalDMEnqueued(assertNotNull(replacement.directMessageEnvelope()))
                        throw PersistenceStoreException(PersistenceStoreError.SaveFailed("lost no-dispatch completion return"))
                    }
                    return result
                }
            }
            generation = RadioGeneration(251L + index, racing, MessageServiceConfig(maxAttempts = 0))
            generation.start()
            val message = generation.service.createPendingMessage("no-dispatch successor $signal", contact)
            val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
            replacement = original.copy(attemptCount = 7)
            store.upsertPendingSend(original)
            generation.signals.set(DeviceConnectionState.READY)
            generation.queue.hydrate(); runCurrent()
            assertTrue(inserted)
            assertEquals(replacement, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single())
            generation.queue.transportDidOpen(); runCurrent(); generation.queue.awaitDrainCompletion()
            assertEquals(listOf(1L, 8L), bumpCounts)
            assertEquals(3, requests.size)
            assertEquals(requests[0], requests[1])
            assertNotEquals(requests[1], requests[2])
            assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).isEmpty())
            assertTrue(generation.firmware.sent().isEmpty())
            generation.stop()
        }
    }

    @Test fun attemptedSameIdSuccessorStaysRetainedThroughTheNextGeneration() = runTest(scheduler) {
        seed()
        val barrier = DirectCompletionBarrier()
        val generation = RadioGeneration(253, barrier, MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.service.startEventMonitoring()
        val message = generation.service.createPendingMessage("attempted predecessor", contact)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        assertTrue(barrier.entered.isCompleted)
        assertEquals(1, generation.firmware.sent().size)
        val before = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        store.deletePendingSend(EntityKey(radio, original.id))
        val replacement = original.copy(attemptCount = 7, isResend = true)
        store.upsertPendingSend(replacement)
        generation.queue.signalDMEnqueued(assertNotNull(replacement.directMessageEnvelope()))
        barrier.release.complete(Unit); runCurrent(); generation.queue.awaitDrainCompletion()
        assertEquals(replacement, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single())
        assertEquals(before, store.fetchMessage(EntityKey(radio, message.id)))
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directSuccessorRetained.${original.id}"
        })
        generation.queue.signalDMEnqueued(assertNotNull(replacement.directMessageEnvelope()))
        runCurrent(); generation.queue.awaitDrainCompletion()
        assertEquals(1, generation.firmware.sent().size)
        generation.stop()

        val next = RadioGeneration(254, config = MessageServiceConfig(maxAttempts = 1))
        next.start(); next.service.startEventMonitoring()
        next.signals.set(DeviceConnectionState.READY)
        next.queue.hydrate(); runCurrent(); next.queue.awaitDrainCompletion()
        assertTrue(next.firmware.sent().isEmpty())
        assertEquals(replacement, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single())
        assertTrue(next.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${original.id}"
        })
        next.stop()
    }

    @Test fun queuedUnknownWireTimeoutIsNotAutomaticallyReplayedAfterReconnect() = runTest(scheduler) {
        seed()
        val generation = RadioGeneration(255, config = MessageServiceConfig(maxAttempts = 1), sessionTimeout = 0.05)
        generation.start()
        generation.firmware.acceptanceGate = CompletableDeferred()
        val message = generation.service.createPendingMessage("unknown direct outcome", contact)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        assertEquals(1, generation.firmware.sent().size)
        advanceTimeBy(51); runCurrent(); generation.queue.awaitDrainCompletion()
        val retained = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
        assertEquals(1L, retained.attemptCount)
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${original.id}"
        })
        generation.queue.transportDidOpen()
        generation.queue.signalDMEnqueued(assertNotNull(retained.directMessageEnvelope()))
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, generation.firmware.sent().size)
        generation.stop()

        val next = RadioGeneration(256, config = MessageServiceConfig(maxAttempts = 1))
        next.start(); next.signals.set(DeviceConnectionState.READY)
        next.queue.hydrate(); runCurrent(); next.queue.awaitDrainCompletion()
        assertTrue(next.firmware.sent().isEmpty())
        assertEquals(retained, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single())
        assertTrue(next.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${original.id}"
        })
        next.stop()
    }

    @Test fun backupImportAndReconnectRetainAnUnknownWireUntilAnExplicitRetry() = runTest(scheduler) {
        seed()
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<android.content.Context>())
        try {
            val backup = AppBackupService(storage.backupPreferences, "test", "1")
            val generation = RadioGeneration(272, config = MessageServiceConfig(maxAttempts = 1), sessionTimeout = 0.05)
            generation.start()
            generation.firmware.acceptanceGate = CompletableDeferred()
            val message = generation.service.createPendingMessage("restore unknown wire", contact)
            val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
            store.upsertPendingSend(original)
            generation.signals.set(DeviceConnectionState.READY)
            generation.queue.hydrate(); runCurrent()
            assertEquals(1, generation.firmware.sent().size)
            advanceTimeBy(51); runCurrent(); generation.queue.awaitDrainCompletion()
            val key = EntityKey(radio, message.id)
            val retained = store.fetchPendingSendsForMessage(key).single()
            assertEquals(1L, retained.attemptCount)
            generation.stop()

            val exported = backup.exportEnvelope(store)
            val imported = backup.importBackup(exported, store)
            assertEquals(1L, imported.counts.getValue(BackupModelKind.MESSAGES).skipped)
            assertEquals(retained, store.fetchPendingSendsForMessage(key).single())

            val next = RadioGeneration(273, config = MessageServiceConfig(maxAttempts = 1))
            next.start(); next.service.startEventMonitoring()
            next.signals.set(DeviceConnectionState.READY)
            next.queue.hydrate(); runCurrent(); next.queue.awaitDrainCompletion()
            assertTrue(next.firmware.sent().isEmpty())
            assertEquals(retained, store.fetchPendingSendsForMessage(key).single())
            assertTrue(next.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                it.operation == "directUncertain.${original.id}"
            })

            val retry = retained.copy(id = UUID.randomUUID(), isResend = true, attemptCount = 0)
            store.replacePendingSendForRetry(message.id, retry)
            advanceTimeBy(1_100)
            next.queue.signalDMEnqueued(assertNotNull(retry.directMessageEnvelope()))
            runCurrent(); next.queue.awaitDrainCompletion()
            assertEquals(1, next.firmware.sent().size)
            assertEquals(MessageStatus.DELIVERED, store.fetchMessage(key)?.status)
            assertTrue(store.fetchPendingSendsForMessage(key).isEmpty())
            next.stop()
        } finally {
            storage.close()
        }
    }

    @Test fun explicitQueuedRetryAfterUnknownOutcomeDrainsSameOrDifferentPendingIdentity() = runTest(scheduler) {
        seed()
        for ((index, sameID) in listOf(false, true).withIndex()) {
            val generation = RadioGeneration(260L + index, config = MessageServiceConfig(
                maxAttempts = 1, poolBackoff = PoolBackoffConfig(attemptCap = 0),
            ))
            generation.start(); generation.service.startEventMonitoring()
            generation.firmware.error = 3u
            val message = generation.service.createPendingMessage("explicit retry $sameID", contact)
            val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
            store.upsertPendingSend(original)
            generation.signals.set(DeviceConnectionState.READY)
            generation.queue.hydrate(); runCurrent(); generation.queue.awaitDrainCompletion()
            val uncertain = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
            assertEquals(1, generation.firmware.sent().size)
            assertEquals(1L, uncertain.attemptCount)
            val retry = uncertain.copy(id = if (sameID) uncertain.id else UUID.randomUUID(), isResend = true, attemptCount = 0)
            store.replacePendingSendForRetry(message.id, retry)
            val storedRetry = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
            assertEquals(retry.id, storedRetry.id)
            advanceTimeBy(1_100)
            generation.firmware.error = null
            generation.queue.signalDMEnqueued(assertNotNull(storedRetry.directMessageEnvelope()))
            runCurrent(); generation.queue.awaitDrainCompletion()
            assertEquals(2, generation.firmware.sent().size)
            assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
            assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).isEmpty())
            assertEquals(0, generation.queue.queuedEnvelopeCount)
            generation.stop()
        }
    }

    @Test fun genericDifferentIdResendCannotAcquireAnExplicitRetryGrant() = runTest(scheduler) {
        seed()
        val generation = RadioGeneration(262, config = MessageServiceConfig(
            maxAttempts = 1, poolBackoff = PoolBackoffConfig(attemptCap = 0),
        ))
        generation.start()
        generation.firmware.error = 3u
        val message = generation.service.createPendingMessage("generic replacement", contact)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent(); generation.queue.awaitDrainCompletion()
        val uncertain = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
        store.deletePendingSend(EntityKey(radio, uncertain.id))
        val replacement = uncertain.copy(id = UUID.randomUUID(), isResend = true, attemptCount = 0)
        store.upsertPendingSend(replacement)
        generation.firmware.error = null
        generation.queue.signalDMEnqueued(assertNotNull(replacement.directMessageEnvelope()))
        runCurrent(); generation.queue.awaitDrainCompletion()
        assertEquals(1, generation.firmware.sent().size)
        assertEquals(replacement, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single())
        assertNotEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${replacement.id}"
        })
        generation.stop()
    }

    @Test fun explicitRetryDoesNotTreatAKnownPriorCodeAsItsOwnAcknowledgement() = runTest(scheduler) {
        seed()
        var oldCode: UInt? = null
        val recording = object : DirectRoles(store) {
            override suspend fun beginDirectAckAttempt(
                intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
            ): DirectAckBeginResult {
                if (oldCode == null) oldCode = predictedAckCode
                return super.beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode)
            }
        }
        val generation = RadioGeneration(263, recording, MessageServiceConfig(
            maxAttempts = 1, minTimeout = 0.01, poolBackoff = PoolBackoffConfig(attemptCap = 0),
        ))
        generation.start(); generation.service.startEventMonitoring()
        generation.firmware.error = 3u
        val message = generation.service.createPendingMessage("known collision", contact)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent(); generation.queue.awaitDrainCompletion()
        val uncertain = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
        val replacement = uncertain.copy(id = UUID.randomUUID(), isResend = true, attemptCount = 0)
        store.replacePendingSendForRetry(message.id, replacement)
        advanceTimeBy(1_100)
        generation.firmware.error = null
        generation.firmware.acceptedAck = ByteWriter().appendUInt32LE(assertNotNull(oldCode)).toBytes()
        val stored = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
        generation.queue.signalDMEnqueued(assertNotNull(stored.directMessageEnvelope()))
        runCurrent(); advanceTimeBy(100); runCurrent(); generation.queue.awaitDrainCompletion()
        assertEquals(2, generation.firmware.sent().size)
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).isEmpty())
        generation.firmware.pushAck(assertNotNull(generation.firmware.acceptedAck)); runCurrent()
        assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, message.id))?.status)
        generation.stop()
    }

    @Test fun queuedCancellationBeforeProtocolSerializerDispatchCanRecoverAcrossGenerations() = runTest(scheduler) {
        seed()
        var claimID: UUID? = null
        var lease: DirectAckLease? = null
        val recording = object : DirectRoles(store) {
            override suspend fun captureDirectQueueSelection(
                owner: DirectAckLease, key: EntityKey, pendingSendId: UUID,
            ): DirectQueueCaptureResult {
                lease = owner
                return super.captureDirectQueueSelection(owner, key, pendingSendId)
            }
            override suspend fun captureDirectQueueIntent(
                selection: DirectQueueSelection, requestId: UUID, mode: DirectAckMode,
            ): DirectAckIntentResult {
                claimID = requestId
                return super.captureDirectQueueIntent(selection, requestId, mode)
            }
        }
        val generation = RadioGeneration(264, recording, MessageServiceConfig(maxAttempts = 1))
        generation.start()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        generation.firmware.beforeSend = {
            if (it[0] == CommandCode.RESET_PATH.rawValue) { entered.complete(Unit); gate.await() }
        }
        val blocker = backgroundScope.async { generation.session.resetPath(contact.publicKey) }
        runCurrent(); assertTrue(entered.isCompleted)
        val message = generation.service.createPendingMessage("cancel before serializer", contact)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        assertTrue(generation.firmware.sent().isEmpty())
        val stopping = backgroundScope.async { generation.queue.shutdown() }
        runCurrent()
        assertTrue(stopping.isCompleted)
        stopping.await()
        val observed = assertIs<DirectAckObservation.Found>(
            store.inspectDirectAckAttempt(assertNotNull(lease), assertNotNull(claimID)),
        ).receipt
        assertNotNull(observed.definitelyUnsentWireRequestId)
        assertEquals(DirectAckPermission.INVALIDATED, observed.permission)
        assertEquals(1L, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single().attemptCount)
        gate.complete(Unit); runCurrent(); blocker.await()
        assertTrue(generation.firmware.sent().isEmpty())
        generation.stop()

        val successor = RadioGeneration(265, config = MessageServiceConfig(maxAttempts = 1))
        successor.start(); successor.service.startEventMonitoring()
        successor.signals.set(DeviceConnectionState.READY)
        successor.queue.hydrate(); runCurrent(); successor.queue.awaitDrainCompletion()
        assertEquals(1, successor.firmware.sent().size)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).isEmpty())
        successor.stop()
    }

    @Test fun definitelyUnsentInlineNotificationCanRetryTheSameCodeWithoutAFalseCollision() = runTest(scheduler) {
        seed()
        val generation = RadioGeneration(268, config = MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.service.startEventMonitoring()
        generation.signals.set(DeviceConnectionState.READY)
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        generation.firmware.beforeSend = {
            if (it[0] == CommandCode.RESET_PATH.rawValue) { entered.complete(Unit); gate.await() }
        }
        val blocker = backgroundScope.async { generation.session.resetPath(contact.publicKey) }
        runCurrent(); assertTrue(entered.isCompleted)
        val message = generation.service.createPendingMessage("cancel inline before serializer", contact)
        val receipt = NotificationActionReceipt()
        val send = backgroundScope.async(receipt) {
            generation.service.sendPendingDirectMessage(message.id, contact, preserveTimestamp = true)
        }
        runCurrent()
        assertEquals(NotificationActionReceipt.State.IN_FLIGHT, receipt.state)
        assertTrue(generation.firmware.sent().isEmpty())
        send.cancelAndJoin()
        assertFailsWith<CancellationException> { send.await() }
        assertEquals(NotificationActionReceipt.State.READY, receipt.state)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        val input = ByteWriter().appendUInt32LE(message.timestamp).appendUInt8(0u)
            .append(Bytes.utf8(message.text)).append(self).toBytes()
        val oldCode = Bytes(MessageDigest.getInstance("SHA-256").digest(input.toByteArray())).prefix(4)
        generation.firmware.pushAck(oldCode); runCurrent()
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.UnmatchedAcknowledgement>().isNotEmpty())
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)

        gate.complete(Unit); runCurrent(); blocker.await()
        generation.firmware.beforeSend = null
        val delivered = withContext(receipt) {
            generation.service.sendPendingDirectMessage(message.id, contact, preserveTimestamp = true)
        }
        assertTrue(receipt.isAccepted)
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(1, generation.firmware.sent().size)
        generation.stop()
    }

    @Test fun uncorrelatedInlineNotificationErrorsRemainUncertainAndDoNotReplay() = runTest(scheduler) {
        seed()
        for ((index, code) in listOf(1u, 3u).withIndex()) {
            val generation = RadioGeneration(269L + index, config = MessageServiceConfig(maxAttempts = 1))
            generation.start(); generation.signals.set(DeviceConnectionState.READY)
            generation.firmware.error = code.toUByte()
            val message = generation.service.createPendingMessage("uncorrelated error $code", contact)
            val receipt = NotificationActionReceipt()
            val error = assertFailsWith<MessageServiceException> {
                withContext(receipt) {
                    generation.service.sendPendingDirectMessage(message.id, contact, preserveTimestamp = true)
                }
            }
            assertEquals(code.toUByte(), assertIs<MeshCoreException.DeviceError>(
                assertIs<MessageServiceError.SessionError>(error.error).underlying,
            ).code)
            assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
            assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
            assertEquals(1, generation.firmware.sent().size)
            generation.firmware.error = null
            assertFailsWith<MessageServiceException> {
                withContext(receipt) {
                    generation.service.sendPendingDirectMessage(message.id, contact, preserveTimestamp = true)
                }
            }
            assertEquals(1, generation.firmware.sent().size)
            generation.stop()
        }
    }

    @Test fun uncorrelatedInlineErrorsKeepTheNonNotificationRowAndWireUncertain() = runTest(scheduler) {
        exerciseUncorrelatedNonNotificationErrors(withRetry = false)
    }

    @Test fun uncorrelatedRetryErrorsKeepTheNonNotificationRowAndWireUncertain() = runTest(scheduler) {
        exerciseUncorrelatedNonNotificationErrors(withRetry = true)
    }

    private suspend fun TestScope.exerciseUncorrelatedNonNotificationErrors(withRetry: Boolean) {
        seed()
        val generation = RadioGeneration(272, config = MessageServiceConfig(maxAttempts = 2))
        generation.start(); generation.signals.set(DeviceConnectionState.READY)
        val events = generation.service.statusEvents()
        for ((index, code) in listOf(1u, 3u, 6u).withIndex()) {
            generation.firmware.error = code.toUByte()
            val text = "non-notification error $code"
            val error = assertFailsWith<MessageServiceException> {
                if (withRetry) generation.service.sendMessageWithRetry(text, contact)
                else generation.service.sendDirectMessage(text, contact)
            }
            assertEquals(code.toUByte(), assertIs<MeshCoreException.DeviceError>(
                assertIs<MessageServiceError.SessionError>(error.error).underlying,
            ).code)
            val row = store.fetchMessages(EntityKey(radio, contact.id)).single { it.text == text }
            assertEquals(MessageStatus.PENDING, row.status)
            assertEquals(1, db.directAcks().currentUnresolvedClaims(radio.value, row.id))
            assertEquals(index + 1, generation.firmware.sent().size)
        }
        assertTrue(completedStatuses(generation, events).none { it is MessageStatusEvent.Failed })
        generation.stop()
    }

    @Test fun priorAcceptedWireAndLaterUncorrelatedErrorClearRetryingWithoutFalseFailure() = runTest(scheduler) {
        seed()
        val generation = RadioGeneration(273, config = MessageServiceConfig(maxAttempts = 2))
        generation.start(); generation.signals.set(DeviceConnectionState.READY)
        generation.firmware.acknowledge = false
        generation.firmware.beforeSend = {
            if (it[0] == CommandCode.SEND_MESSAGE.rawValue && generation.firmware.sent().isNotEmpty()) {
                generation.firmware.error = 1u
            }
        }
        val events = generation.service.statusEvents()
        val sending = backgroundScope.async {
            runCatching { generation.service.sendMessageWithRetry("accepted then uncertain", contact) }
        }
        runCurrent()
        assertEquals(1, generation.firmware.sent().size)
        advanceTimeBy(20); runCurrent()
        val failure = assertIs<MessageServiceException>(sending.await().exceptionOrNull())
        assertEquals(1u.toUByte(), assertIs<MeshCoreException.DeviceError>(
            assertIs<MessageServiceError.SessionError>(failure.error).underlying,
        ).code)
        val row = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertEquals(MessageStatus.PENDING, row.status)
        assertEquals(1, db.directAcks().currentUnresolvedClaims(radio.value, row.id))
        assertEquals(2, generation.firmware.sent().size)
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${row.id}"
        })
        assertTrue(completedStatuses(generation, events).none { it is MessageStatusEvent.Failed })
        generation.stop()
    }

    @Test fun failedPreWireProofKeepsNotificationUncertainAndDoesNotFailTheRow() = runTest(scheduler) {
        seed()
        val proofFailure = PersistenceStoreException(PersistenceStoreError.SaveFailed("unsent proof unavailable"))
        val failingAuthority = object : DirectRoles(store) {
            override suspend fun recordDefinitelyUnsentInlineWire(
                attempt: DirectAckAttempt, wireRequestId: UUID, requestId: UUID,
            ): DirectAckMutationResult = throw proofFailure
        }
        val generation = RadioGeneration(274, failingAuthority, MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.signals.set(DeviceConnectionState.READY)
        val gate = CompletableDeferred<Unit>()
        generation.firmware.acceptanceGate = gate
        val blocker = backgroundScope.async { generation.service.sendDirectMessage("holds direct serializer", contact) }
        runCurrent()
        assertEquals(1, generation.firmware.sent().size)
        val receipt = NotificationActionReceipt()
        val held = backgroundScope.async(receipt) {
            generation.service.sendDirectMessage("proof failure before wire", contact)
        }
        runCurrent()
        val row = store.fetchMessages(EntityKey(radio, contact.id)).single { it.text == "proof failure before wire" }
        assertEquals(NotificationActionReceipt.State.READY, receipt.state)
        assertEquals(1, generation.firmware.sent().size)
        held.cancelAndJoin()
        assertFailsWith<CancellationException> { held.await() }
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation.startsWith("directUnsentRetirement.") && it.cause === proofFailure
        })
        assertEquals(NotificationActionReceipt.State.UNCERTAIN, receipt.state)
        assertTrue(receipt.preventsReplay)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, row.id))?.status)
        assertEquals(1, db.directAcks().currentUnresolvedClaims(radio.value, row.id))
        assertEquals(1, generation.firmware.sent().size)
        assertFailsWith<MessageServiceException> {
            withContext(receipt) { generation.service.sendDirectMessage("proof failure before wire", contact) }
        }
        gate.complete(Unit); runCurrent(); blocker.await()
        generation.stop()
    }

    @Test fun provenUnsentNonNotificationCancellationFailsOnlyItsOwnInlineRow() = runTest(scheduler) {
        seed()
        val generation = RadioGeneration(275, config = MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.signals.set(DeviceConnectionState.READY)
        val events = generation.service.statusEvents()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        generation.firmware.beforeSend = {
            if (it[0] == CommandCode.RESET_PATH.rawValue) { entered.complete(Unit); gate.await() }
        }
        val blocker = backgroundScope.async { generation.session.resetPath(contact.publicKey) }
        runCurrent(); assertTrue(entered.isCompleted)
        val send = backgroundScope.async { generation.service.sendDirectMessage("definitely not sent", contact) }
        runCurrent()
        val row = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertTrue(generation.firmware.sent().isEmpty())
        send.cancelAndJoin()
        assertFailsWith<CancellationException> { send.await() }
        assertEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, row.id))?.status)
        assertEquals(listOf(row.id), completedStatuses(generation, events)
            .filterIsInstance<MessageStatusEvent.Failed>().map { it.messageID })
        gate.complete(Unit); runCurrent(); blocker.await()
        assertTrue(generation.firmware.sent().isEmpty())
        generation.stop()
    }

    @Test fun queuedCancellationAfterPhysicalWriteRemainsUncertainAcrossGenerations() = runTest(scheduler) {
        seed()
        var claimID: UUID? = null
        var lease: DirectAckLease? = null
        val recording = object : DirectRoles(store) {
            override suspend fun captureDirectQueueSelection(
                owner: DirectAckLease, key: EntityKey, pendingSendId: UUID,
            ): DirectQueueCaptureResult {
                lease = owner
                return super.captureDirectQueueSelection(owner, key, pendingSendId)
            }
            override suspend fun captureDirectQueueIntent(
                selection: DirectQueueSelection, requestId: UUID, mode: DirectAckMode,
            ): DirectAckIntentResult {
                claimID = requestId
                return super.captureDirectQueueIntent(selection, requestId, mode)
            }
        }
        val generation = RadioGeneration(266, recording, MessageServiceConfig(maxAttempts = 1))
        generation.start()
        val gate = CompletableDeferred<Unit>()
        generation.firmware.acceptanceGate = gate
        val message = generation.service.createPendingMessage("cancel after physical write", contact)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        assertEquals(1, generation.firmware.sent().size)
        val stopping = backgroundScope.async { generation.queue.shutdown() }
        runCurrent()
        assertTrue(stopping.isCompleted)
        stopping.await()
        val observed = assertIs<DirectAckObservation.Found>(
            store.inspectDirectAckAttempt(assertNotNull(lease), assertNotNull(claimID)),
        ).receipt
        assertNull(observed.definitelyUnsentWireRequestId)
        assertTrue(observed.wires.single().acceptedAckCode == null)
        val retained = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
        assertEquals(1L, retained.attemptCount)
        gate.complete(Unit); runCurrent()
        generation.stop()

        val successor = RadioGeneration(267, config = MessageServiceConfig(maxAttempts = 1))
        successor.start(); successor.signals.set(DeviceConnectionState.READY)
        successor.queue.hydrate(); runCurrent(); successor.queue.awaitDrainCompletion()
        assertTrue(successor.firmware.sent().isEmpty())
        assertEquals(retained, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single())
        assertNotEquals(MessageStatus.FAILED, store.fetchMessage(EntityKey(radio, message.id))?.status)
        successor.stop()
    }

    @Test fun queuedPoolFullResponseRemainsUncertainWithoutAnExactRejectionReceipt() = runTest(scheduler) {
        seed()
        val generation = RadioGeneration(257, config = MessageServiceConfig(
            maxAttempts = 1, poolBackoff = PoolBackoffConfig(attemptCap = 0),
        ))
        generation.start(); generation.service.startEventMonitoring()
        generation.firmware.error = 3u
        val message = generation.service.createPendingMessage("ambiguous pool response", contact)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        assertEquals(1, generation.firmware.sent().size)
        assertEquals(1L, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single().attemptCount)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        generation.firmware.error = null
        val retained = store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single()
        generation.queue.transportDidOpen()
        generation.queue.signalDMEnqueued(assertNotNull(retained.directMessageEnvelope()))
        advanceTimeBy(60_000); runCurrent(); generation.queue.awaitDrainCompletion()
        assertEquals(1, generation.firmware.sent().size)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertEquals(retained, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single())
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${original.id}"
        })
        generation.stop()
    }

    @Test fun poolRejectionAfterAnEarlierAcceptedWireCannotAuthorizeAutomaticReplay() = runTest(scheduler) {
        seed()
        val generation = RadioGeneration(258, config = MessageServiceConfig(
            maxAttempts = 2, floodAfter = 5, minTimeout = 0.0, poolBackoff = PoolBackoffConfig(attemptCap = 0),
        ))
        generation.start()
        generation.firmware.acknowledge = false
        generation.firmware.beforeSend = { data ->
            if (data[0] == CommandCode.SEND_MESSAGE.rawValue && generation.firmware.sent().size == 1) {
                generation.firmware.error = 3u
            }
        }
        val message = generation.service.createPendingMessage("accepted before pool rejection", contact)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        advanceTimeBy(20); runCurrent(); generation.queue.awaitDrainCompletion()
        assertEquals(2, generation.firmware.sent().size)
        assertEquals(1L, store.fetchPendingSendsForMessage(EntityKey(radio, message.id)).single().attemptCount)
        assertTrue(generation.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${original.id}"
        })
        generation.queue.transportDidOpen()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(2, generation.firmware.sent().size)
        generation.stop()
    }

    @Test fun staleMissingContactCleanupCannotDeleteOrFailAQueuedReplacement() = runTest(scheduler) {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val racing = object : DirectRoles(store) {
            override suspend fun mutateDirectQueueSelection(
                selection: DirectQueueSelection, requestId: UUID, mutation: DirectQueueMutation,
            ): DirectQueueMutationResult {
                if (mutation == DirectQueueMutation.MissingContact) {
                    entered.complete(Unit)
                    release.await()
                }
                return super.mutateDirectQueueSelection(selection, requestId, mutation)
            }
        }
        val generation = RadioGeneration(144, racing, MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.service.startEventMonitoring()
        val missing = UUID.randomUUID()
        val row = MessageDTO(
            radioId = radio, contactID = missing, text = "old A", timestamp = base.epochSecond.toUInt(),
            createdAt = base, status = MessageStatus.PENDING,
        )
        store.saveMessage(row)
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(row.id, missing), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        assertTrue(entered.isCompleted)
        assertTrue(generation.firmware.sent().isEmpty())

        store.deletePendingSend(EntityKey(radio, original.id))
        val replacement = row.copy(contactID = contact.id, text = "replacement B")
        store.saveMessage(replacement)
        val next = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(row.id, contact.id), radio)
        store.upsertPendingSend(next)
        generation.queue.signalDMEnqueued(assertNotNull(next.directMessageEnvelope()))
        release.complete(Unit)
        runCurrent(); generation.queue.awaitDrainCompletion()

        assertEquals(1, generation.firmware.sent().size)
        assertEquals(Bytes.utf8("replacement B"), generation.firmware.sent().single().slice(13, generation.firmware.sent().single().size))
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, row.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        generation.stop()
    }

    @Test fun lostQueueBumpReturnReplaysTheSameAttemptWithoutSendingTwice() = runTest(scheduler) {
        seed()
        val requests = mutableListOf<UUID>()
        var lostReturn = true
        val racing = object : DirectRoles(store) {
            override suspend fun bumpDirectQueueSelection(
                selection: DirectQueueSelection, requestId: UUID,
            ): DirectQueueBumpResult {
                requests += requestId
                val result = super.bumpDirectQueueSelection(selection, requestId)
                if (lostReturn && result is DirectQueueBumpResult.Applied) {
                    lostReturn = false
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("lost queue bump return"))
                }
                return result
            }
        }
        val generation = RadioGeneration(145, racing, MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.service.startEventMonitoring()
        val row = generation.service.createPendingMessage("one queued attempt", contact)
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(row.id, contact.id), radio))
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        assertEquals(1L, store.fetchPendingSends(radio).single().attemptCount)
        assertTrue(generation.firmware.sent().isEmpty())

        generation.queue.transportDidOpen()
        runCurrent(); generation.queue.awaitDrainCompletion()
        assertEquals(2, requests.size)
        assertEquals(1, requests.distinct().size)
        assertEquals(1, generation.firmware.sent().size)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, row.id))?.status)
        generation.stop()
    }

    @Test fun queuedTransientRemapFailureParksAndReplaysBeforeSending() = runTest(scheduler) {
        queuedTransientRemapRecovery(lostReturn = false)
    }

    @Test fun queuedTransientRemapLostReturnParksAndReplaysBeforeSending() = runTest(scheduler) {
        queuedTransientRemapRecovery(lostReturn = true)
    }

    private suspend fun TestScope.queuedTransientRemapRecovery(lostReturn: Boolean) {
        seed()
        val requests = mutableListOf<UUID>()
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("controlled remap failure"))
        var rejectPreWire = true
        val racing = object : DirectRoles(store) {
            override suspend fun captureDirectQueueIntent(
                selection: DirectQueueSelection, requestId: UUID, mode: DirectAckMode,
            ): DirectAckIntentResult {
                if (rejectPreWire) {
                    rejectPreWire = false
                    throw MessageServiceException(MessageServiceError.NotConnected)
                }
                return super.captureDirectQueueIntent(selection, requestId, mode)
            }

            override suspend fun mutateDirectQueueSelection(
                selection: DirectQueueSelection, requestId: UUID, mutation: DirectQueueMutation,
            ): DirectQueueMutationResult {
                if (mutation == DirectQueueMutation.ReturnToPending) {
                    requests += requestId
                    if (requests.size == 1) {
                        if (lostReturn) {
                            assertIs<DirectQueueMutationResult.Applied>(
                                super.mutateDirectQueueSelection(selection, requestId, mutation),
                            )
                        }
                        throw fault
                    }
                }
                return super.mutateDirectQueueSelection(selection, requestId, mutation)
            }
        }
        val generation = RadioGeneration(if (lostReturn) 275 else 274, racing, MessageServiceConfig(maxAttempts = 1))
        generation.start(); generation.service.startEventMonitoring()
        val row = MessageDTO(
            radioId = radio, contactID = contact.id, text = "recover pre-wire remap",
            timestamp = 100u, createdAt = base, status = MessageStatus.RETRYING,
        )
        store.saveMessage(row)
        val pending = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(row.id, contact.id), radio)
        store.upsertPendingSend(pending)
        generation.signals.set(DeviceConnectionState.READY); generation.queue.hydrate(); runCurrent()
        assertEquals(1, requests.size)
        assertEquals(if (lostReturn) MessageStatus.PENDING else MessageStatus.RETRYING,
            store.fetchMessage(EntityKey(radio, row.id))?.status)
        assertEquals(1L, store.fetchPendingSends(radio).single().attemptCount)
        assertTrue(generation.firmware.sent().isEmpty())

        generation.queue.transportDidOpen(); runCurrent(); generation.queue.awaitDrainCompletion()
        assertEquals(2, requests.size)
        assertEquals(1, requests.distinct().size)
        assertEquals(1, generation.firmware.sent().size)
        assertEquals(MessageStatus.DELIVERED, store.fetchMessage(EntityKey(radio, row.id))?.status)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        generation.stop()
    }

    @Test fun replacedQueuedRowBeforePreparationCannotReceiveTheOldTimestampOrWire() = runTest(scheduler) {
        staleQueuedBoundary(zeroAttempts = false, afterPreparation = false)
    }

    @Test fun replacedQueuedRowBeforeIntentCannotReceiveTheOldWire() = runTest(scheduler) {
        staleQueuedBoundary(zeroAttempts = false, afterPreparation = true)
    }

    @Test fun replacedQueuedRowBeforeZeroAttemptPreparationIsNotFinalizedByTheOldEntry() = runTest(scheduler) {
        staleQueuedBoundary(zeroAttempts = true, afterPreparation = false)
    }

    @Test fun replacedQueuedRowBeforeZeroAttemptCaptureIsNotDeletedByTheOldEntry() = runTest(scheduler) {
        staleQueuedBoundary(zeroAttempts = true, afterPreparation = true)
    }

    private suspend fun TestScope.staleQueuedBoundary(zeroAttempts: Boolean, afterPreparation: Boolean) {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val observed = CompletableDeferred<Any>()
        val continueDrain = CompletableDeferred<Unit>()
        var first = true
        val barrier = object : DirectRoles(store) {
            private suspend fun <T : Any> hold(block: suspend () -> T): T {
                if (!first) return block()
                first = false
                entered.complete(Unit)
                release.await()
                val result = block()
                observed.complete(result)
                continueDrain.await()
                return result
            }
            override suspend fun prepareDirectQueueSelection(
                selection: DirectQueueSelection, requestId: UUID, timestamp: UInt?, resolveToSent: Boolean,
            ): DirectQueuePreparationResult = if (afterPreparation) {
                super.prepareDirectQueueSelection(selection, requestId, timestamp, resolveToSent)
            } else hold { super.prepareDirectQueueSelection(selection, requestId, timestamp, resolveToSent) }
            override suspend fun captureDirectQueueIntent(
                selection: DirectQueueSelection, requestId: UUID, mode: DirectAckMode,
            ): DirectAckIntentResult = if (afterPreparation && !zeroAttempts) {
                hold { super.captureDirectQueueIntent(selection, requestId, mode) }
            } else super.captureDirectQueueIntent(selection, requestId, mode)
            override suspend fun captureDirectQueueCompletion(
                selection: DirectQueueSelection, captureRequestId: UUID, reason: DirectPendingCompletionReason,
            ): DirectPendingCaptureResult = if (afterPreparation && zeroAttempts) {
                hold { super.captureDirectQueueCompletion(selection, captureRequestId, reason) }
            } else super.captureDirectQueueCompletion(selection, captureRequestId, reason)
        }
        val generation = RadioGeneration(146, barrier, MessageServiceConfig(maxAttempts = if (zeroAttempts) 0 else 1))
        generation.start(); generation.service.startEventMonitoring()
        val initial = generation.service.createPendingMessage("same pending identity", contact)
        store.saveMessage(initial.copy(timestamp = initial.timestamp - 1u))
        val original = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(initial.id, contact.id), radio)
        store.upsertPendingSend(original)
        generation.signals.set(DeviceConnectionState.READY)
        generation.queue.hydrate(); runCurrent()
        entered.await()
        val before = assertNotNull(store.fetchMessage(EntityKey(radio, initial.id)))
        val replacement = store.fetchPendingSends(radio).single()
        store.deletePendingSend(EntityKey(radio, replacement.id))
        store.upsertPendingSend(replacement)
        release.complete(Unit)
        when (val result = observed.await()) {
            is DirectQueuePreparationResult.Rejected -> assertEquals(DirectAckRejection.INVALIDATED, result.reason)
            is DirectAckIntentResult.Rejected -> assertEquals(DirectAckRejection.INVALIDATED, result.reason)
            is DirectPendingCaptureResult.Rejected -> assertEquals(DirectAckRejection.INVALIDATED, result.reason)
            else -> fail("Old selection unexpectedly authorized $result")
        }
        assertEquals(before, store.fetchMessage(EntityKey(radio, initial.id)))
        assertEquals(replacement, store.fetchPendingSends(radio).single())
        assertTrue(generation.firmware.sent().isEmpty())
        continueDrain.complete(Unit)
        runCurrent(); generation.queue.awaitDrainCompletion()
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertEquals(if (zeroAttempts) 0 else 1, generation.firmware.sent().size)
        generation.stop()
    }

    @Test fun inlineDispatchRejectsAnIndependentEditBeforeItsCreationBoundCapture() = runTest(scheduler) {
        seed()
        var saved: MessageDTO? = null
        val editing = object : DirectRoles(store) {
            override suspend fun createDirectMessage(
                lease: DirectAckLease, message: MessageDTO, creationRequestId: UUID, claimRequestId: UUID,
            ): DirectMessageCreationResult {
                saved = message
                return super.createDirectMessage(lease, message, creationRequestId, claimRequestId)
            }
            override suspend fun captureCreatedDirectIntent(creation: DirectMessageCreation): DirectAckIntentResult {
                val original = assertNotNull(saved)
                store.saveMessage(original.copy(text = "independent edit", timestamp = original.timestamp - 1u))
                return super.captureCreatedDirectIntent(creation)
            }
        }
        val g = RadioGeneration(141, editing, MessageServiceConfig(maxAttempts = 1))
        g.start()
        assertFailsWith<PersistenceStoreException> { g.service.sendDirectMessage("cached A", contact) }
        val original = assertNotNull(saved)
        assertEquals(original.copy(text = "independent edit", timestamp = original.timestamp - 1u),
            store.fetchMessage(EntityKey(radio, original.id)))
        assertTrue(g.firmware.sent().isEmpty())
        g.stop()
    }

    @Test fun inlinePreCaptureSendAgainHandsOffToTheUserRequestedQueueEntry() = runTest(scheduler) {
        seed()
        for ((index, successorFirst) in listOf(false, true).withIndex()) {
            val entered = CompletableDeferred<MessageDTO>()
            val release = CompletableDeferred<Unit>()
            var created: MessageDTO? = null
            val barrier = object : DirectRoles(store) {
                override suspend fun createDirectMessage(
                    lease: DirectAckLease, message: MessageDTO, creationRequestId: UUID, claimRequestId: UUID,
                ): DirectMessageCreationResult {
                    val result = super.createDirectMessage(lease, message, creationRequestId, claimRequestId)
                    created = message
                    return result
                }

                override suspend fun captureCreatedDirectIntent(creation: DirectMessageCreation): DirectAckIntentResult {
                    entered.complete(assertNotNull(created))
                    release.await()
                    return super.captureCreatedDirectIntent(creation)
                }
            }
            val g = RadioGeneration(270L + index, barrier, MessageServiceConfig(maxAttempts = 1))
            g.start(); g.service.startEventMonitoring()
            g.signals.set(DeviceConnectionState.READY)
            g.queue.hydrate()
            val events = g.service.statusEvents()
            val inline = backgroundScope.async {
                runCatching { g.service.sendDirectMessage("user requested retry $successorFirst", contact) }
            }
            try {
                runCurrent()
                assertTrue(entered.isCompleted)
                val message = entered.await()
                val key = EntityKey(radio, message.id)
                val envelope = DirectMessageEnvelope(message.id, contact.id, isResend = true)
                val pending = PendingSendDTO.fromEnvelope(envelope, radio)
                store.replacePendingSendForRetry(message.id, pending)

                if (successorFirst) {
                    g.queue.signalDMEnqueued(envelope)
                    runCurrent(); g.queue.awaitDrainCompletion()
                }

                release.complete(Unit)
                runCurrent()
                assertIs<PersistenceStoreException>(inline.await().exceptionOrNull())

                if (!successorFirst) {
                    assertEquals(MessageStatus.PENDING, store.fetchMessage(key)?.status)
                    assertEquals(pending.id, store.fetchPendingSendsForMessage(key).single().id)
                    assertTrue(g.firmware.sent().isEmpty())
                    g.queue.signalDMEnqueued(envelope)
                    runCurrent(); g.queue.awaitDrainCompletion()
                }

                val wire = g.firmware.sent().single()
                assertEquals(Bytes.utf8(message.text), wire.slice(13, wire.size))
                assertEquals(MessageStatus.DELIVERED, store.fetchMessage(key)?.status)
                assertTrue(store.fetchPendingSendsForMessage(key).isEmpty())
                assertTrue(completedStatuses(g, events).none { it is MessageStatusEvent.Failed && it.messageID == message.id })
            } finally {
                release.complete(Unit)
                inline.cancelAndJoin()
                g.stop()
            }
        }
    }

    @Test fun queuedDispatchDerivesPayloadFromIssuedSnapshotNotPreCaptureDto() = runTest(scheduler) {
        dispatchFromIssuedSnapshot()
    }

    private suspend fun TestScope.dispatchFromIssuedSnapshot() {
        seed()
        val actual = store
        val changed = "replacement B before capture"
        var replaced = false
        var queuedMessageID: UUID? = null
        val replacing = object : DirectRoles(actual) {
            private suspend fun replaceOnce(key: EntityKey) {
                if (!replaced) {
                    replaced = true
                    val original = assertNotNull(actual.fetchMessage(key))
                    actual.saveMessage(original.copy(text = changed, timestamp = original.timestamp - 1u))
                }
            }
            override suspend fun captureDirectQueueIntent(
                selection: DirectQueueSelection, requestId: UUID, mode: DirectAckMode,
            ): DirectAckIntentResult {
                replaceOnce(EntityKey(radio, requireNotNull(queuedMessageID)))
                return super.captureDirectQueueIntent(selection, requestId, mode)
            }
        }
        val g = RadioGeneration(141, replacing, MessageServiceConfig(maxAttempts = 1))
        g.start(); g.service.startEventMonitoring()
        val row = g.service.createPendingMessage("cached A", contact)
        queuedMessageID = row.id
        store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(row.id, contact.id), radio))
        g.signals.set(DeviceConnectionState.READY); g.queue.hydrate(); runCurrent()
        g.queue.awaitDrainCompletion()
        val result = assertNotNull(store.fetchMessage(EntityKey(radio, row.id)))
        assertTrue(replaced)
        val wire = g.firmware.sent().single()
        assertEquals(Bytes.utf8(changed), wire.slice(13, wire.size))
        assertEquals(base.epochSecond.toUInt() - 1u, wire.readUInt32LE(3))
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, result.id)))
        assertEquals(changed, delivered.text)
        assertEquals(wire.readUInt32LE(3), delivered.timestamp)
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(123u, delivered.roundTripTime)
        assertEquals(0, g.service.pendingAckCount)
        g.stop()
        assertEquals(delivered, store.fetchMessage(EntityKey(radio, result.id)))
    }

    private suspend fun TestScope.invalidateIssuedIntent(queued: Boolean, change: String) {
        seed()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val actual = store
        val barrier = object : DirectRoles(actual) {
            override suspend fun beginDirectAckAttempt(
                intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
            ): DirectAckBeginResult {
                entered.complete(Unit); release.await()
                return super.beginDirectAckAttempt(intent, firstWireRequestId, predictedAckCode)
            }
        }
        val g = RadioGeneration(140, barrier, MessageServiceConfig(maxAttempts = 1))
        g.start(); g.service.startEventMonitoring()
        val sending = if (queued) {
            val message = g.service.createPendingMessage("original A", contact)
            store.upsertPendingSend(PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio))
            g.signals.set(DeviceConnectionState.READY); g.queue.hydrate()
            null
        } else async { runCatching { g.service.sendDirectMessage("original A", contact) } }
        runCurrent()
        assertTrue(entered.isCompleted)
        assertTrue(g.firmware.sent().isEmpty())
        val original = store.fetchMessages(EntityKey(radio, contact.id)).single()
        val replacement = if (change == "text") original.copy(text = "replacement B") else original
        when (change) {
            "recreate" -> { store.deleteMessage(EntityKey(radio, original.id)); store.saveMessage(replacement) }
            "text" -> store.saveMessage(replacement)
            "pending" -> store.upsertPendingSend(store.fetchPendingSends(radio).single().copy(messageText = "replacement pending"))
            else -> error("Unknown intent mutation")
        }
        val pendingAfterMutation = store.fetchPendingSends(radio)
        release.complete(Unit); runCurrent()
        if (sending != null) assertIs<PersistenceStoreException>(sending.await().exceptionOrNull())
        else assertTrue(g.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "park.${original.id}"
        })
        assertEquals(replacement, store.fetchMessage(EntityKey(radio, original.id)))
        assertEquals(pendingAfterMutation, store.fetchPendingSends(radio))
        assertTrue(g.firmware.sent().isEmpty())
        assertEquals(0, g.service.pendingAckCount)
        g.stop()
        assertEquals(replacement, store.fetchMessage(EntityKey(radio, original.id)))
        assertEquals(pendingAfterMutation, store.fetchPendingSends(radio))
    }

    private suspend fun TestScope.exercisePublicFinalization(
        code: Bytes, tripTime: UInt?, listenerWins: Boolean, rejectWriteAfterDelivery: Boolean = false,
    ) {
        seed()
        var writeGuardInstalled = false
        val persistence = if (rejectWriteAfterDelivery) object : DirectRoles(store) {
            override suspend fun settleDirectAck(
                attempt: DirectAckAttempt, ackCode: UInt, roundTripTime: UInt?,
            ): DirectAckMutationResult = super.settleDirectAck(attempt, ackCode, roundTripTime).also {
                if (!writeGuardInstalled &&
                    (it is DirectAckMutationResult.Applied || it is DirectAckMutationResult.AlreadyApplied)
                ) {
                    db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_delivered_rewrite AFTER UPDATE ON messages " +
                        "BEGIN SELECT RAISE(FAIL, 'finalization rewrote listener delivery'); END")
                    writeGuardInstalled = true
                }
            }
        } else store
        val g = RadioGeneration(67, persistence, MessageServiceConfig(maxAttempts = 1, floodAfter = 5))
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
        val delivered = try {
            runCurrent()
            if (listenerWins) {
                assertFalse(sending.isCompleted)
                val beforeAcceptance = store.fetchMessages(EntityKey(radio, contact.id)).single()
                assertEquals(MessageStatus.PENDING, beforeAcceptance.status)
                assertNull(beforeAcceptance.roundTripTime)
                assertEquals(1, g.service.pendingAckCount)
                gate.complete(Unit)
                runCurrent()
                if (rejectWriteAfterDelivery) assertTrue(writeGuardInstalled)
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
        val blocked = object : DirectRoles(actual) {
            override suspend fun applyDirectAckBookkeeping(
                attempt: DirectAckAttempt, requestId: UUID, change: DirectAckBookkeeping,
            ): DirectAckMutationResult {
                if (change is DirectAckBookkeeping.RetryStarted) { entered.complete(Unit); release.await() }
                return actual.applyDirectAckBookkeeping(attempt, requestId, change)
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
        val fault = object : DirectRoles(actual) {
            override suspend fun applyDirectAckBookkeeping(
                attempt: DirectAckAttempt, requestId: UUID, change: DirectAckBookkeeping,
            ): DirectAckMutationResult = actual.applyDirectAckBookkeeping(attempt, requestId, change).also {
                if (change == DirectAckBookkeeping.ResendCountIncrement &&
                    it !is DirectAckMutationResult.Rejected) countWritten = true
            }
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
        val faulty = object : DirectRoles(actual) {
            override suspend fun recordDirectFailure(attempt: DirectAckAttempt): DirectAckMutationResult {
                if (attemptKey(attempt).id == blockedID) throw cause
                return actual.recordDirectFailure(attempt)
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

    @Test fun actualRoomFailAllRetainsEachFailedEntryAndRetriesWithoutDuplicateEvents() = runTest(scheduler) {
        seed()
        val actual = store
        val faults = listOf(
            PersistenceStoreException(PersistenceStoreError.SaveFailed("first fail-all"), IllegalStateException("first")),
            PersistenceStoreException(PersistenceStoreError.SaveFailed("second fail-all"), IllegalStateException("second")),
        )
        val blocked = linkedMapOf<UUID, PersistenceStoreException>()
        val writes = mutableListOf<EntityKey>()
        val faulty = object : DirectRoles(actual) {
            override suspend fun recordDirectFailure(attempt: DirectAckAttempt): DirectAckMutationResult {
                val key = attemptKey(attempt)
                writes += key
                blocked[key.id]?.let { throw it }
                return actual.recordDirectFailure(attempt)
            }
        }
        val g = RadioGeneration(80, faulty); g.start()
        val messages = listOf(
            g.acceptedWithoutAck("first retained", Bytes.of(1, 2, 3, 4)),
            g.acceptedWithoutAck("second retained", Bytes.of(5, 6, 7, 8)),
            g.acceptedWithoutAck("healthy later", Bytes.of(9, 10, 11, 12)),
        )
        messages.take(2).forEachIndexed { index, message -> blocked[message.id] = faults[index] }
        val foreign = messages[0].copy(radioId = other)
        store.saveMessage(foreign)
        val observed = mutableListOf<MessageStatusEvent>()
        val events = g.service.statusEvents()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            events.events.collect { event ->
                assertEquals(g.token, event.token)
                observed += event.event
            }
        }
        val thrown = assertFailsWith<PersistenceStoreException> { g.service.failAllPendingMessages() }
        assertSame(faults[0], thrown)
        assertEquals(listOf(faults[1]), thrown.suppressed.toList())
        assertEquals(messages.map { EntityKey(radio, it.id) }, writes)
        for (index in 0..1) {
            val message = messages[index]
            assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
            assertTrue(g.diagnostics.any { it is MessagingDiagnostic.Failure &&
                it.operation == "failPending.${message.id}" && it.cause === faults[index] })
        }
        assertEquals(messages[2].copy(status = MessageStatus.FAILED),
            store.fetchMessage(EntityKey(radio, messages[2].id)))
        assertEquals(2, g.service.pendingAckCount)
        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.Failed(messages[2].id)), observed)
        blocked.clear()
        g.service.failAllPendingMessages()
        g.service.failAllPendingMessages()
        runCurrent()
        assertEquals(listOf(0, 1, 2, 0, 1).map { EntityKey(radio, messages[it].id) }, writes)
        for (message in messages) {
            assertEquals(message.copy(status = MessageStatus.FAILED),
                store.fetchMessage(EntityKey(radio, message.id)))
        }
        assertEquals(foreign, store.fetchMessage(EntityKey(other, foreign.id)))
        assertEquals(0, g.service.pendingAckCount)
        val expected: List<MessageStatusEvent> = listOf(2, 0, 1).map { MessageStatusEvent.Failed(messages[it].id) }
        assertEquals(expected, observed)
        assertEquals(3, g.firmware.sent().size)
        assertTrue(g.service.close().isComplete)
        collecting.cancelAndJoin(); events.close(); g.stop()
    }

    @Test fun cancellingActualRoomFailAllPreservesUncommittedRowsAndAllowsExplicitRetry() = runTest(scheduler) {
        seed()
        val actual = store
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<EntityKey>()
        var suspendWrite = true
        val suspended = object : DirectRoles(actual) {
            override suspend fun recordDirectFailure(attempt: DirectAckAttempt): DirectAckMutationResult {
                val key = attemptKey(attempt)
                writes += key
                if (suspendWrite) { entered.complete(Unit); release.await() }
                return actual.recordDirectFailure(attempt)
            }
        }
        val g = RadioGeneration(81, suspended); g.start()
        val first = g.acceptedWithoutAck("cancel first", Bytes.of(1, 2, 3, 4))
        val later = g.acceptedWithoutAck("cancel later", Bytes.of(5, 6, 7, 8))
        val observed = mutableListOf<MessageStatusEvent>()
        val events = g.service.statusEvents()
        val collecting = backgroundScope.launch(UnconfinedTestDispatcher(scheduler)) {
            events.events.collect { event -> assertEquals(g.token, event.token); observed += event.event }
        }
        val sweep = backgroundScope.async { g.service.failAllPendingMessages() }
        entered.await(); sweep.cancelAndJoin()
        assertFailsWith<CancellationException> { sweep.await() }
        assertEquals(listOf(EntityKey(radio, first.id)), writes)
        assertEquals(first, store.fetchMessage(EntityKey(radio, first.id)))
        assertEquals(later, store.fetchMessage(EntityKey(radio, later.id)))
        assertEquals(2, g.service.pendingAckCount)
        assertTrue(observed.isEmpty())
        assertTrue(g.diagnostics.none { it is MessagingDiagnostic.Failure })
        suspendWrite = false
        g.service.failAllPendingMessages(); g.service.failAllPendingMessages(); runCurrent()
        assertEquals(listOf(first.id, first.id, later.id).map { EntityKey(radio, it) }, writes)
        assertEquals(first.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, first.id)))
        assertEquals(later.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, later.id)))
        assertEquals(0, g.service.pendingAckCount)
        assertEquals(listOf<MessageStatusEvent>(MessageStatusEvent.Failed(first.id), MessageStatusEvent.Failed(later.id)), observed)
        assertEquals(2, g.firmware.sent().size)
        assertTrue(g.service.close().isComplete)
        collecting.cancelAndJoin(); events.close(); g.stop()
    }

    @Test fun actualRoomFailAllStopsAfterOwnershipWithdrawalWithoutPublishingStaleFailure() = runTest(scheduler) {
        seed()
        val actual = store
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val writes = mutableListOf<EntityKey>()
        val suspended = object : DirectRoles(actual) {
            override suspend fun recordDirectFailure(attempt: DirectAckAttempt): DirectAckMutationResult {
                val key = attemptKey(attempt)
                writes += key; entered.complete(Unit); release.await()
                return actual.recordDirectFailure(attempt)
            }
        }
        val g = RadioGeneration(82, suspended); g.start()
        val first = g.acceptedWithoutAck("withdraw first", Bytes.of(1, 2, 3, 4))
        val later = g.acceptedWithoutAck("withdraw later", Bytes.of(5, 6, 7, 8))
        val successor = RadioGeneration(83)
        val events = g.service.statusEvents()
        val sweep = backgroundScope.async {
            assertFailsWith<MessageServiceException> { g.service.failAllPendingMessages() }
        }
        entered.await()
        g.signals.set(DeviceConnectionState.READY, successor.token)
        release.complete(Unit)
        assertEquals(MessageServiceError.NotConnected, sweep.await().error)
        runCurrent()
        assertEquals(listOf(EntityKey(radio, first.id)), writes)
        assertEquals(first.copy(status = MessageStatus.FAILED), store.fetchMessage(EntityKey(radio, first.id)))
        assertEquals(later, store.fetchMessage(EntityKey(radio, later.id)))
        assertEquals(2, g.service.pendingAckCount)
        assertTrue(completedStatuses(g, events).isEmpty())
        assertTrue(g.diagnostics.none { it is MessagingDiagnostic.Failure })
        assertEquals(2, g.firmware.sent().size)
        g.stop()
    }

    @Test fun actualRoomHydrationReadFailureRetriesItsOriginalEnvelopeAndThenLatches() = runTest(scheduler) {
        seed()
        val actual = store
        val cause = PersistenceStoreException(PersistenceStoreError.SaveFailed("hydration read"), IllegalStateException("read"))
        var failRead = true
        var reads = 0
        val faulty = object : PersistenceStoreProtocol by actual {
            override suspend fun fetchPendingSends(radioId: RadioId): SnapshotList<PendingSendDTO> {
                reads++
                if (failRead) throw cause
                return actual.fetchPendingSends(radioId)
            }
        }
        val g = RadioGeneration(84, faulty); g.start(); g.signals.set(DeviceConnectionState.READY)
        val message = g.service.createPendingMessage("retry hydration read", contact)
            .copy(timestamp = 42u, createdAt = base.minusSeconds(3_600))
        store.saveMessage(message)
        val row = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
            .copy(sequence = 1, attemptCount = 1)
        store.upsertPendingSend(row)
        val failure = assertFailsWith<ChatSendQueueServiceException> { g.queue.hydrate() }
        assertTrue(generateSequence(failure.cause) { it.cause }.any { it === cause })
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(listOf(row), store.fetchPendingSends(radio))
        assertTrue(g.firmware.sent().isEmpty())
        failRead = false
        g.queue.hydrate(); runCurrent(); g.queue.awaitDrainCompletion()
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(message.timestamp, delivered.timestamp)
        assertEquals(message.createdAt, delivered.createdAt)
        assertEquals(message.id, delivered.id)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        g.queue.hydrate(); runCurrent(); g.queue.awaitDrainCompletion()
        assertEquals(2, reads)
        assertEquals(1, g.firmware.sent().size)
        assertTrue(g.queue.shutdown().isComplete); g.stop()
    }

    @Test fun cancellingActualRoomHydrationObserverKeepsOneSharedReadAndOneWireSend() = runTest(scheduler) {
        seed()
        val actual = store
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var reads = 0
        val suspended = object : PersistenceStoreProtocol by actual {
            override suspend fun fetchPendingSends(radioId: RadioId): SnapshotList<PendingSendDTO> {
                reads++; entered.complete(Unit); release.await()
                return actual.fetchPendingSends(radioId)
            }
        }
        val g = RadioGeneration(85, suspended); g.start(); g.signals.set(DeviceConnectionState.READY)
        val message = g.service.createPendingMessage("shared hydration read", contact)
            .copy(timestamp = 84u, createdAt = base.minusSeconds(7_200))
        store.saveMessage(message)
        val row = PendingSendDTO.fromEnvelope(DirectMessageEnvelope(message.id, contact.id), radio)
            .copy(sequence = 1, attemptCount = 1)
        store.upsertPendingSend(row)
        val first = backgroundScope.async { g.queue.hydrate() }
        entered.await(); first.cancelAndJoin()
        assertFailsWith<CancellationException> { first.await() }
        val second = backgroundScope.async { g.queue.hydrate() }
        runCurrent()
        assertFalse(second.isCompleted)
        assertEquals(1, reads)
        assertEquals(message, store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(listOf(row), store.fetchPendingSends(radio))
        assertTrue(g.firmware.sent().isEmpty())
        release.complete(Unit); second.await(); runCurrent(); g.queue.awaitDrainCompletion()
        val delivered = assertNotNull(store.fetchMessage(EntityKey(radio, message.id)))
        assertEquals(MessageStatus.DELIVERED, delivered.status)
        assertEquals(message.timestamp, delivered.timestamp)
        assertEquals(message.createdAt, delivered.createdAt)
        assertEquals(message.id, delivered.id)
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        g.queue.hydrate()
        assertEquals(1, reads)
        assertEquals(1, g.firmware.sent().size)
        assertTrue(g.queue.shutdown().isComplete); g.stop()
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

    @Test fun disconnectedInlineSendRetainsUncertaintyButSettledAcceptedSendStaysSent() = runTest(scheduler) {
        seed(); val g = RadioGeneration(23); g.start(); g.signals.set(DeviceConnectionState.READY); g.firmware.acknowledge = false
        val settled = g.service.sendDirectMessage("settled before disconnect", contact)
        g.firmware.acceptanceGate = CompletableDeferred()
        val sending = backgroundScope.async { g.service.sendDirectMessage("inline at disconnect", contact) }
        runCurrent()
        val inline = store.fetchMessages(EntityKey(radio, contact.id)).single { it.text == "inline at disconnect" }
        assertEquals(1, db.directAcks().currentUnresolvedClaims(radio.value, inline.id))
        g.signals.set(DeviceConnectionState.DISCONNECTED); val report = withTimeout(2000) { g.service.close() }
        assertFailsWith<CancellationException> { sending.await() }
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, inline.id))?.status)
        db.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM direct_ack_claims AS claim JOIN direct_ack_wires AS wire " +
                "ON wire.radioId = claim.radioId AND wire.claimId = claim.requestId " +
                "WHERE claim.radioId = ? AND claim.messageId = ? AND wire.acceptedCode IS NULL",
            arrayOf(radio.value.canonicalString(), inline.id.canonicalString()),
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        assertTrue(store.fetchPendingSendsForMessage(EntityKey(radio, inline.id)).isEmpty())
        assertFalse(report.isComplete)
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
        val next = RadioGeneration(240)
        next.start(); next.signals.set(DeviceConnectionState.READY)
        next.queue.hydrate(); runCurrent(); next.queue.awaitDrainCompletion()
        assertTrue(next.firmware.sent().isEmpty())
        assertEquals(pending.id, store.fetchPendingSends(radio).single().id)
        assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radio, message.id))?.status)
        assertTrue(next.diagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
            it.operation == "directUncertain.${pending.id}"
        })
        next.stop()
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
        val faulty = object : DirectRoles(actual) {
            override suspend fun bumpDirectQueueSelection(
                selection: DirectQueueSelection, requestId: UUID,
            ): DirectQueueBumpResult = throw cause
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

    @Test fun lostInlineCreationReturnRecoversItsOwnCommittedRowWithoutSending() = runTest(scheduler) {
        seed()
        val fault = PersistenceStoreException(PersistenceStoreError.SaveFailed("lost inline creation return"))
        val actual = store
        val blocked = object : DirectRoles(actual) {
            override suspend fun createDirectMessage(
                lease: DirectAckLease, message: MessageDTO, creationRequestId: UUID, claimRequestId: UUID,
            ): DirectMessageCreationResult {
                assertIs<DirectMessageCreationResult.Created>(
                    super.createDirectMessage(lease, message, creationRequestId, claimRequestId),
                )
                throw fault
            }
        }
        val g = RadioGeneration(228, blocked); g.start(); g.signals.set(DeviceConnectionState.READY)
        val events = g.service.statusEvents()
        assertSame(fault, assertFailsWith<PersistenceStoreException> {
            g.service.sendDirectMessage("lost creation return", contact)
        })
        val saved = store.fetchMessages(EntityKey(radio, contact.id)).single()
        assertEquals(MessageStatus.FAILED, saved.status)
        assertTrue(g.firmware.sent().isEmpty())
        assertEquals(listOf(MessageStatusEvent.Failed(saved.id)), completedStatuses(g, events))
        g.stop()
    }

    @Test fun inlineCreationCannotCaptureOrFailAnIdenticalReplacementAfterCommit() = runTest(scheduler) {
        seed()
        val committed = CompletableDeferred<MessageDTO>(); val release = CompletableDeferred<Unit>()
        val actual = store
        val blocked = object : DirectRoles(actual) {
            override suspend fun createDirectMessage(
                lease: DirectAckLease, message: MessageDTO, creationRequestId: UUID, claimRequestId: UUID,
            ): DirectMessageCreationResult {
                val result = super.createDirectMessage(lease, message, creationRequestId, claimRequestId)
                committed.complete(message); release.await()
                return result
            }
        }
        val g = RadioGeneration(229, blocked); g.start(); g.signals.set(DeviceConnectionState.READY)
        val events = g.service.statusEvents()
        val send = backgroundScope.async {
            try { g.service.sendDirectMessage("identical replacement", contact); null }
            catch (failure: PersistenceStoreException) { failure }
        }
        runCurrent()
        val original = committed.await()
        val key = EntityKey(radio, original.id)
        store.deleteMessage(key); store.saveMessage(original)
        release.complete(Unit)
        val failure = assertNotNull(send.await())
        assertTrue(failure.suppressed.isNotEmpty())
        assertEquals(original, store.fetchMessage(key))
        assertTrue(g.firmware.sent().isEmpty())
        assertTrue(completedStatuses(g, events).isEmpty())
        g.stop()
    }

    @Test fun inlinePrewireDisconnectCannotFailACompetingPersistedIntent() = runTest(scheduler) {
        seed()
        val committed = CompletableDeferred<Pair<MessageDTO, DirectAckLease>>()
        val release = CompletableDeferred<Unit>()
        val actual = store
        val blocked = object : DirectRoles(actual) {
            override suspend fun createDirectMessage(
                lease: DirectAckLease, message: MessageDTO, creationRequestId: UUID, claimRequestId: UUID,
            ): DirectMessageCreationResult {
                val result = super.createDirectMessage(lease, message, creationRequestId, claimRequestId)
                committed.complete(message to lease); release.await()
                return result
            }
        }
        val g = RadioGeneration(230, blocked); g.start(); g.signals.set(DeviceConnectionState.READY)
        val events = g.service.statusEvents()
        val send = backgroundScope.async {
            try { g.service.sendDirectMessage("competing intent", contact); null }
            catch (failure: MessageServiceException) { failure }
        }
        runCurrent()
        val (original, lease) = committed.await()
        val key = EntityKey(radio, original.id)
        assertIs<DirectAckIntentResult.Captured>(
            store.captureDirectAckIntent(lease, key, UUID.randomUUID(), DirectAckMode.SEND, null),
        )
        g.signals.set(DeviceConnectionState.DISCONNECTED); release.complete(Unit)
        val failure = assertNotNull(send.await())
        assertEquals(MessageServiceError.NotConnected, failure.error)
        assertTrue(failure.suppressed.isNotEmpty())
        assertEquals(original, store.fetchMessage(key))
        assertTrue(g.firmware.sent().isEmpty())
        assertTrue(completedStatuses(g, events).isEmpty())
        g.stop()
    }

    @Test fun committedInlineRoomSaveAtDisconnectFailsBeforeTheWireAndPreservesTheProcessStore() = runTest(scheduler) {
        seed(); val actual = store
        val committed = CompletableDeferred<UUID>(); val release = CompletableDeferred<Unit>()
        val blocked = object : DirectRoles(actual) {
            override suspend fun createDirectMessage(
                lease: DirectAckLease, message: MessageDTO, creationRequestId: UUID, claimRequestId: UUID,
            ): DirectMessageCreationResult {
                val result = super.createDirectMessage(lease, message, creationRequestId, claimRequestId)
                committed.complete(message.id); release.await()
                return result
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
        val blocked = object : DirectRoles(actual) {
            override suspend fun recordDirectSendAccepted(
                attempt: DirectAckAttempt, wireRequestId: UUID, ackCode: UInt,
            ): DirectAckMutationResult {
                entered.complete(Unit); release.await()
                return actual.recordDirectSendAccepted(attempt, wireRequestId, ackCode)
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
        fun set(rung: DeviceConnectionState, currentToken: SessionToken = token) {
            val next = snapshot(currentToken, rung); value.value = next
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
        private val channels = mutableMapOf<UByte, ChannelInfo>()
        private var channelQueryBaseline = 0
        var error: UByte? = null
        var emptyChannelSlot = false
        var acknowledge = true
        var ackBeforeAcceptance = false
        var earlyAck: Bytes? = null
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
        fun channelQueries(): List<Bytes> =
            mock.sentData.filter { it[0] == CommandCode.GET_CHANNEL.rawValue }.drop(channelQueryBaseline)
        fun markChannelQueries() {
            channelQueryBaseline = mock.sentData.count { it[0] == CommandCode.GET_CHANNEL.rawValue }
        }
        fun configureChannel(channel: ChannelDTO) {
            channels[channel.index] = ChannelInfo(channel.index, channel.name, channel.secret)
        }
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
                    if (acknowledge && ackBeforeAcceptance) pushAck(earlyAck ?: ack, automaticTripTime)
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
                CommandCode.GET_CHANNEL -> {
                    val index = data[1]
                    val channel = channels[index]
                    when {
                        emptyChannelSlot -> mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.CHANNEL_INFO.rawValue)
                            .appendUInt8(index).append(Bytes(ByteArray(32))).append(Bytes(ByteArray(16))).toBytes())
                        channel != null -> {
                            val name = channel.name.toByteArray(Charsets.UTF_8).copyOf(32)
                            mock.simulateReceive(ByteWriter().appendUInt8(ResponseCode.CHANNEL_INFO.rawValue)
                                .appendUInt8(index).append(Bytes(name)).append(channel.secret).toBytes())
                        }
                        else -> mock.simulateError(2u)
                    }
                }
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
