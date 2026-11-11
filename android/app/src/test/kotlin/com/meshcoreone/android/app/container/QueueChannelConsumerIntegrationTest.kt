// AndroidOnly: WP-501 Actual App/runtime/factory queue channel-query composition, not another frozen queue original.
// AndroidOnly: WP-207 Supplemental deferred-recovery ordering, not ungated production acceptance.
// AndroidOnly: WP-307 Accepted outgoing channel reaction replay and persistence regressions for #612.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueChannelConsumerIntegrationTest : RoomProcessTest() {
    @Test fun actualFactorySuccessfulOutgoingChannelSendPersistsPendingReactionWithOriginalArrival() = runTest {
        withFactoryQueue(QueryReply.SUCCESS) { f ->
            f.awaitAcceptedAttempt()
            f.assertStored(MessageStatus.SENT, pending = false, reactionSummary = "👍:2")
            f.assertSuccessfulWire()
            f.awaitReactionNotification()
            assertEquals(
                listOf(
                    SyncDataEvent.ReactionReceived(f.message.id, "👍:1"),
                    SyncDataEvent.ReactionReceived(f.message.id, "👍:2"),
                ),
                f.reactionNotifications(),
            )

            val reactions = store.fetchReactions(EntityKey(f.radioId, f.message.id))
            assertEquals(2, reactions.size)
            assertEquals(setOf("Remote Node", "Second Remote Node"), reactions.map { it.senderName }.toSet())
            assertTrue(reactions.all { it.emoji == "👍" && it.rawText == f.pendingReactionRaw })
            assertTrue(reactions.all { it.receivedAt in requireNotNull(f.pendingReactionArrivalRange) })
            assertTrue(reactions.all { it.channelIndex == 0u.toUByte() && it.radioId == f.radioId })
            assertEquals("👍:2", store.fetchMessage(EntityKey(f.radioId, f.message.id))?.reactionSummary)
            assertTrue(f.pendingReactionSenders().isEmpty())
            f.assertCurrentGraph()
        }
    }

    @Test fun actualFactoryAcceptedOutgoingChannelSendRequeuesReactionWhenPersistenceFails() = runTest {
        val failingStore = FailOnceReactionLookupStore(store, failOnCall = 2)
        withFactoryQueue(QueryReply.SUCCESS, persistenceOverride = failingStore) { f ->
            f.awaitReactionPersistenceFailure(failingStore)
            f.awaitPendingReactionSenders(listOf("Second Remote Node"))
            assertEquals(
                listOf("Remote Node"),
                store.fetchReactions(EntityKey(f.radioId, f.message.id)).map { it.senderName },
            )
            assertEquals(listOf("Second Remote Node"), f.pendingReactionSenders())
            assertTrue(f.indexFailures().isNotEmpty())

            f.live.chatSendQueueService.transportDidOpen()
            f.awaitAcceptedAttempt()
            f.assertStored(MessageStatus.SENT, pending = false, reactionSummary = "👍:2")
            assertEquals(
                setOf("Remote Node", "Second Remote Node"),
                store.fetchReactions(EntityKey(f.radioId, f.message.id)).map { it.senderName }.toSet(),
            )
            assertTrue(f.pendingReactionSenders().isEmpty())
            assertEquals(
                listOf(
                    SyncDataEvent.ReactionReceived(f.message.id, "👍:1"),
                    SyncDataEvent.ReactionReceived(f.message.id, "👍:2"),
                ),
                f.reactionNotifications(),
            )
            f.assertSuccessfulWire()
        }
    }

    @Test fun actualFactoryOutgoingChannelSendRetriesReactionBatchAfterPersistenceCancellation() = runTest {
        val cancelledStore = FailOnceReactionLookupStore(store, CancellationException("Injected cancellation"))
        withFactoryQueue(QueryReply.SUCCESS, persistenceOverride = cancelledStore) { f ->
            f.awaitReactionPersistenceFailure(cancelledStore)
            f.awaitAcceptedAttempt()
            f.assertStored(MessageStatus.SENT, pending = false, reactionSummary = "👍:2")
            val reactions = store.fetchReactions(EntityKey(f.radioId, f.message.id))
            assertEquals(2, reactions.size)
            assertEquals(setOf("Remote Node", "Second Remote Node"), reactions.map { it.senderName }.toSet())
            assertTrue(reactions.all { it.emoji == "👍" && it.rawText == f.pendingReactionRaw })
            assertTrue(reactions.all { it.receivedAt in requireNotNull(f.pendingReactionArrivalRange) })
            assertTrue(reactions.all { it.channelIndex == 0u.toUByte() && it.radioId == f.radioId })
            assertEquals("👍:2", store.fetchMessage(EntityKey(f.radioId, f.message.id))?.reactionSummary)
            assertTrue(f.pendingReactionSenders().isEmpty())
            assertEquals(
                listOf(
                    SyncDataEvent.ReactionReceived(f.message.id, "👍:1"),
                    SyncDataEvent.ReactionReceived(f.message.id, "👍:2"),
                ),
                f.reactionNotifications(),
            )
            f.assertSuccessfulWire()
            f.assertCurrentGraph()
        }
    }

    @Test fun actualFactoryConfiguredChannelRetainsTheOriginalEnvelopeAsUncertain() = runTest {
        withFactoryQueue(QueryReply.CONFIGURED) { f ->
            f.awaitUncertainAttempt()
            f.assertRetainedDeviceError2()
            assertTrue(f.failures("channelQuery").isEmpty())
            assertTrue(f.failures("terminal").isEmpty())
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertWire()
            f.assertCurrentGraph()
            f.disconnect()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertStatusEvents(emptyList())
        }
    }

    @Test fun actualFactoryUnconfiguredQueryCannotSettleTheOriginalUncertainEnvelope() = runTest {
        withFactoryQueue(QueryReply.UNCONFIGURED) { f ->
            f.awaitUncertainAttempt()
            f.assertRetainedDeviceError2()
            assertTrue(f.failures("channelQuery").isEmpty())
            assertTrue(f.failures("park").isEmpty())
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertWire()
            f.assertCurrentGraph()
            f.disconnect()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertStatusEvents(emptyList())
        }
    }

    @Test fun actualFactoryRawNotFoundQueryResponseCannotAuthorizeAnUncertainSendRetry() = runTest {
        withFactoryQueue(QueryReply.RAW_NOT_FOUND) { f ->
            f.awaitUncertainAttempt()
            f.assertRetainedDeviceError2()
            f.assertNoPhysicalRecovery()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertWire()
            f.assertCurrentGraph()
            val report = f.live.chatSendQueueService.shutdown()
            f.assertRetainedTeardown(report)
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.disconnect()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertStatusEvents(emptyList())
        }
    }

    @Test fun actualFactoryHeldQueryResponseCannotWakeOrReplayAnUncertainSend() = runTest {
        withFactoryQueue(QueryReply.HELD) { f ->
            f.awaitUncertainAttempt()
            f.assertRetainedDeviceError2()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertWire()
            f.assertCurrentGraph()
            assertTrue(f.failures("channelQuery").isEmpty())
            assertTrue(f.failures("park").isEmpty())
            assertTrue(f.failures("terminal").isEmpty())

            val report = f.live.chatSendQueueService.shutdown()
            f.assertRetainedTeardown(report)
            assertSame(report, f.live.chatSendQueueService.shutdown())
            assertFalse(f.live.chatSendQueueService.isObservingConnection)
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertWire()
            assertTrue(f.failures("channelQuery").isEmpty())
            assertTrue(f.failures("park").isEmpty())
            assertTrue(f.failures("terminal").isEmpty())
            f.disconnect()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertStatusEvents(emptyList())
        }
    }

    @Test fun actualFactorySuccessorCannotReplayTheRetainedUncertainSubmission() = runTest {
        withFactoryQueue(QueryReply.CONFIGURED) { f ->
            f.awaitUncertainAttempt()
            f.assertRetainedDeviceError2()
            f.assertWire()
            f.disconnect()
            f.assertSuccessorDoesNotReplay()
            f.assertStored(MessageStatus.PENDING, pending = true)
            f.assertStatusEvents(emptyList())
        }
    }

    private enum class QueryReply { SUCCESS, CONFIGURED, UNCONFIGURED, RAW_NOT_FOUND, HELD }

    private suspend fun TestScope.withFactoryQueue(
        reply: QueryReply,
        persistenceOverride: PersistenceStoreProtocol = store,
        body: suspend (Fixture) -> Unit,
    ) {
        val f = Fixture(this, reply, persistenceOverride)
        var primary: Throwable? = null
        try {
            f.start()
            body(f)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                f.releaseRecovery()
                withContext(NonCancellable) { f.harness.container.close() }
            }
            catch (cleanup: Throwable) {
                val failure = primary
                if (failure == null) throw cleanup
                if (cleanup !== failure) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun assertDeviceError2(cause: Throwable) {
        val device = generateSequence(cause) { it.cause }.filterIsInstance<MeshCoreException.DeviceError>().single()
        assertEquals(2.toUByte(), device.code)
    }

    private fun Fixture.assertRetainedDeviceError2() {
        val failures = retainedFailures()
        assertTrue(failures.isNotEmpty(), "The raw send error must be retained")
        failures.forEach(::assertDeviceError2)
    }

    private fun Fixture.assertRetainedTeardown(report: TeardownReport) {
        assertFalse(report.isComplete)
        assertEquals(listOf(LifecycleStage.STOP_SERVICES), report.issues.map { it.stage })
        assertSame(retainedFailures().first(), report.issues.single().cause)
        assertDeviceError2(report.issues.single().cause)
    }

    private class RecoveryGate(private val delegate: CoroutineDispatcher) : CoroutineDispatcher() {
        private val lock = Any()
        private var held = true
        private val queued = mutableListOf<Pair<CoroutineContext, Runnable>>()
        val queuedCount: Int get() = synchronized(lock) { queued.size }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val dispatchNow = synchronized(lock) {
                if (held) { queued += context to block; false } else true
            }
            if (dispatchNow) delegate.dispatch(context, block)
        }
        fun release() {
            val work = synchronized(lock) {
                held = false
                queued.toList().also { queued.clear() }
            }
            work.forEach { (context, block) -> delegate.dispatch(context, block) }
        }
    }

    private inner class Fixture(
        private val test: TestScope,
        private val reply: QueryReply,
        private val persistenceOverride: PersistenceStoreProtocol,
    ) {
        private val recovery = RecoveryGate(StandardTestDispatcher(test.testScheduler))
        val harness = ContainerHarness(test, store,
            recoveryDispatcher = if (reply == QueryReply.RAW_NOT_FOUND) recovery else Dispatchers.Default,
            persistenceOverride = persistenceOverride)
        val radioId = RadioId(UUID.fromString("A0000000-0000-0000-0000-000000000001"))
        private val foreignRadioId = RadioId(UUID.fromString("B0000000-0000-0000-0000-000000000001"))
        private val deviceID = UUID.fromString("A0000000-0000-0000-0000-000000000002")
        private var channel = ChannelDTO(
            UUID.fromString("A0000000-0000-0000-0000-000000000003"), radioId, 0u, "Test",
            Bytes.fromHex("aa".repeat(16)),
        )
        lateinit var message: MessageDTO
            private set
        private lateinit var pending: PendingSendDTO
        private lateinit var foreignMessage: MessageDTO
        private lateinit var foreignPending: PendingSendDTO
        private val configuredReply = Bytes.fromHex("120054657374" + "00".repeat(28) + "aa".repeat(16))
        private val send = Bytes.fromHex("0300008000926548656c6c6f206368616e6e656c")
        private lateinit var firmware: FirmwareRadio
        lateinit var live: RadioSessionContainer
        private lateinit var statuses: SessionEventSubscription<MessageStatusEvent>
        lateinit var pendingReactionRaw: String
            private set
        var pendingReactionArrivalRange: ClosedRange<Instant>? = null
            private set
        private var holdContacts = true
        private var sending = false
        private val observedReactionNotifications = java.util.Collections.synchronizedList(
            mutableListOf<SyncDataEvent.ReactionReceived>(),
        )

        init {
            harness.links.configureRadio = { radio ->
                firmware = radio
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        4 -> if (holdContacts) emptyList() else null
                        3 -> { sending = true; listOf(if (reply == QueryReply.SUCCESS) Bytes.of(0) else Bytes.of(1, 2)) }
                        31 -> when {
                            !sending -> listOf(if (command[1] == 0.toUByte()) configuredReply
                                else Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                            reply == QueryReply.SUCCESS -> listOf(configuredReply)
                            reply == QueryReply.CONFIGURED -> listOf(configuredReply)
                            reply == QueryReply.UNCONFIGURED -> listOf(Bytes.fromHex("1200" + "00".repeat(48)))
                            reply == QueryReply.RAW_NOT_FOUND -> listOf(Bytes.of(1, 2))
                            else -> emptyList()
                        }
                        else -> null
                    }
                }
            }
        }

        suspend fun start() {
            assertNotEquals(deviceID, radioId.value)
            store.saveDevice(DeviceDTO(id = deviceID, radioId = radioId, publicKey = key(7),
                nodeName = "Stored Node", isActive = false))
            store.saveDevice(DeviceDTO(radioId = foreignRadioId, publicKey = key(8),
                nodeName = "Foreign Node", isActive = false))
            seedReconciledChannel(channel)
            channel = assertNotNull(store.fetchChannel(radioId, channel.index))
            val occupantID = assertNotNull(store.fetchChannelOccupantID(radioId, 0u))
            message = MessageDTO(
                id = UUID.fromString("A0000000-0000-0000-0000-000000000004"), radioId = radioId,
                channelIndex = 0u, text = "Hello channel", timestamp = 1_704_067_200u,
                createdAt = EPOCH, sortDate = EPOCH, direction = MessageDirection.OUTGOING,
                status = MessageStatus.PENDING, channelOccupantID = occupantID,
            )
            store.saveMessage(message)
            pending = store.admitQueueChannelFixture(
                message, UUID.fromString("A0000000-0000-0000-0000-000000000005"),
                isResend = false, localNodeName = "Stored Node", initialAttemptCount = 7,
            )
            foreignMessage = message.copy(
                id = UUID.fromString("B0000000-0000-0000-0000-000000000004"), radioId = foreignRadioId,
                text = "Foreign pending", channelOccupantID = null,
            )
            foreignPending = PendingSendDTO(
                UUID.fromString("B0000000-0000-0000-0000-000000000005"), foreignRadioId, foreignMessage.id,
                PendingSendKind.CHANNEL, null, 0u, false, "Foreign pending", 1_704_067_200u, "Foreign Node", 1, EPOCH, 2,
            )
            store.saveMessage(foreignMessage)
            store.upsertPendingSend(foreignPending)
            harness.container.foreground.activityStarted()
            val connect = harness.container.uiScope.async {
                harness.manager.connect(harness.target(deviceID))
            }
            harness.eventually("actual factory hydrates the row while SYNCING") {
                harness.manager.connectionState == DeviceConnectionState.SYNCING &&
                    harness.sessions.current?.chatSendQueueService?.queuedEnvelopeCount == 1 &&
                    firmware.sent.any { it[0] == 4.toUByte() }
            }
            live = assertNotNull(harness.sessions.current)
            assertSame(live, harness.lifecycle.created.single())
            assertSame(persistenceOverride, live.dataStore)
            assertEquals(radioId, live.token.radioId)
            assertEquals(live.token, live.messageService.token)
            assertEquals(live.token, live.chatSendQueueService.token)
            test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                live.syncCoordinator.dataEvents().collect { event ->
                    if (event is SyncDataEvent.ReactionReceived) observedReactionNotifications += event
                }
            }
            statuses = live.messageService.statusEvents()
            assertEquals(listOf(pending), store.fetchPendingSends(radioId))
            assertEquals(message, store.fetchMessage(EntityKey(radioId, message.id)))
            assertTrue(firmware.sent.none { it[0] == 3.toUByte() }, "No channel write before actual runtime READY")
            if (reply == QueryReply.SUCCESS) queuePendingChannelReaction()
            holdContacts = false
            firmware.push(Bytes.of(2) + le32(0))
            firmware.push(Bytes.of(4) + le32(0))
            connect.await()
            harness.assertReady()
            assertCurrentGraph()
        }

        fun failures(phase: String): List<Throwable> = synchronized(harness.messagingDiagnostics) {
            harness.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .filter { it.operation == "$phase.${message.id}" }
                .onEach { assertEquals(live.token, it.token) }.map { it.cause }
        }

        fun retainedFailures(): List<Throwable> = synchronized(harness.messagingDiagnostics) {
            harness.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .filter { it.operation.startsWith("channelRetained.") }
                .onEach { assertEquals(live.token, it.token) }
                .map { it.cause }
        }

        fun indexFailures(): List<Throwable> = synchronized(harness.messagingDiagnostics) {
            harness.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .filter { it.operation == "index.${message.id}" }
                .onEach { assertEquals(live.token, it.token) }
                .map { it.cause }
        }

        suspend fun awaitUncertainAttempt() {
            harness.eventually("actual factory persists the uncertain channel attempt",
                virtualBudgetMillis = 20_000, virtualStepMillis = 25) {
                runBlocking {
                    store.fetchPendingSendsForMessage(EntityKey(radioId, message.id))
                        .singleOrNull()?.channelDispatchState == QueuedChannelDispatchState.UNCERTAIN &&
                        retainedFailures().isNotEmpty()
                }
            }
        }

        private fun queuePendingChannelReaction() {
            val before = Instant.now()
            pendingReactionRaw = live.reactionService.buildReactionText("👍", "Stored Node", message.text, message.timestamp)
            val parsed = assertNotNull(ReactionParser.parse(pendingReactionRaw))
            listOf("Remote Node", "Second Remote Node").forEach { sender ->
                live.reactionService.queuePendingReaction(parsed, 0u, sender, pendingReactionRaw, radioId)
            }
            pendingReactionArrivalRange = before..Instant.now()
            assertEquals(listOf("Remote Node", "Second Remote Node"), pendingReactionSenders())
        }

        fun pendingReactionSenders(): List<String> =
            live.reactionService.indexMessage(message.id, 0u, "Stored Node", message.text, message.timestamp)
                .also { matches -> matches.forEach(live.reactionService::requeuePendingReaction) }
                .map { it.senderNodeName }

        suspend fun awaitAcceptedAttempt() {
            harness.eventually("actual factory accepts the channel send and persists its terminal state") {
                runBlocking {
                    store.fetchMessage(EntityKey(radioId, message.id))?.status == MessageStatus.SENT &&
                        store.fetchPendingSendsForMessage(EntityKey(radioId, message.id)).isEmpty()
                }
            }
        }

        suspend fun awaitReactionPersistenceFailure(failingStore: FailOnceReactionLookupStore) {
            try {
                harness.eventually("the injected reaction persistence failure is reached") {
                    failingStore.failed
                }
            } catch (timeout: AssertionError) {
                throw AssertionError(
                    "${timeout.message}; reactionExists calls=${failingStore.lookupCount}, " +
                        "wire=${queueWire()}, indexFailures=${indexFailures()}, " +
                        "pending=${pendingReactionSenders()}",
                    timeout,
                )
            }
        }

        suspend fun awaitPendingReactionSenders(expected: List<String>) {
            harness.eventually("the unprocessed reactions are restored to the pending queue") {
                pendingReactionSenders() == expected
            }
        }

        suspend fun awaitReactionNotification() {
            harness.eventually("the persisted reaction summary is broadcast to timeline consumers") {
                reactionNotifications().any { it.messageID == message.id }
            }
        }

        fun reactionNotifications(): List<SyncDataEvent.ReactionReceived> =
            synchronized(observedReactionNotifications) { observedReactionNotifications.toList() }

        fun queueWire(): List<Bytes> = firmware.sent.dropWhile { it[0] != 3.toUByte() }
            .filter { it[0] == 3.toUByte() || it[0] == 31.toUByte() }

        fun assertSuccessfulWire() {
            assertEquals(listOf(send), queueWire())
        }

        fun assertWire() {
            assertEquals(listOf(send), queueWire(),
                "The raw channel SEND error must not trigger GET_CHANNEL or another SEND")
        }

        suspend fun assertStored(status: MessageStatus, pending: Boolean, reactionSummary: String? = null) {
            assertEquals(
                message.copy(status = status, reactionSummary = reactionSummary),
                store.fetchMessage(EntityKey(radioId, message.id)),
            )
            val rows = store.fetchPendingSends(radioId)
            if (pending) {
                val retained = rows.single()
                assertEquals(this.pending.id, retained.id)
                assertEquals(8L, retained.attemptCount)
                assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.channelDispatchState)
                assertNotNull(retained.channelAttemptID)
            } else assertTrue(rows.isEmpty())
            val storedChannels = store.fetchChannels(radioId)
            val expectedChannel = if (status == MessageStatus.SENT) {
                channel.copy(lastMessageDate = assertNotNull(storedChannels.single().lastMessageDate))
            } else {
                channel
            }
            assertEquals(listOf(expectedChannel), storedChannels)
            assertEquals(foreignMessage, store.fetchMessage(EntityKey(foreignRadioId, foreignMessage.id)))
            assertEquals(listOf(foreignPending), store.fetchPendingSends(foreignRadioId))
        }

        fun assertCurrentGraph() {
            harness.assertReady()
            assertSame(live, harness.sessions.current)
            assertEquals(live.token, harness.manager.snapshot.value.token)
            assertEquals(live.token, harness.links.links.single().live)
            assertEquals(1, harness.sessions.created)
            assertEquals(1, firmware.connects)
            assertFalse(live.isTornDown)
        }

        fun releaseRecovery() = recovery.release()

        fun assertNoPhysicalRecovery() = assertEquals(
            0, recovery.queuedCount, "An uncertain send must not start a query-driven recovery job",
        )

        suspend fun assertSuccessorDoesNotReplay() {
            val retained = store.fetchPendingSendsForMessage(EntityKey(radioId, message.id)).single()
            val previousFirmware = firmware
            sending = false
            val blocked = assertFailsWith<ChannelDispatchFailure.UnsettledDispatch> {
                harness.manager.connect(harness.target(deviceID))
            }
            assertDeviceError2(blocked)
            harness.settle()
            assertNull(harness.sessions.current)
            assertEquals(1, harness.lifecycle.created.size, "Quarantine rejects the successor before service construction")
            assertEquals(listOf(retained), store.fetchPendingSendsForMessage(EntityKey(radioId, message.id)))
            assertEquals(listOf(send), previousFirmware.sent.filter { it[0] == 3.toUByte() })
            assertTrue(harness.links.radios.size > 1)
            harness.links.radios.drop(1).forEach { successor ->
                assertTrue(successor.sent.none { it[0] == 31.toUByte() }, "Quarantine rejects every successor before channel sync")
                assertTrue(successor.sent.none { it[0] == 3.toUByte() }, "Reconnection cannot replay an uncertain submission")
            }
        }

        suspend fun disconnect() {
            val report = harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertFalse(report.isComplete)
            assertEquals(listOf(LifecycleStage.STOP_SERVICES), report.issues.map { it.stage })
            report.issues.forEach {
                assertSame(retainedFailures().first(), it.cause)
                assertDeviceError2(it.cause)
            }
            assertTrue(live.isTornDown)
            assertFalse(live.chatSendQueueService.isObservingConnection)
            assertEquals(0, live.liveJobCount)
            assertNull(harness.sessions.current)
            assertEquals(0, harness.sessions.outstanding)
            assertEquals(0, firmware.readers)
            assertEquals(1, firmware.closes)
            assertEquals(foreignMessage, store.fetchMessage(EntityKey(foreignRadioId, foreignMessage.id)))
            assertEquals(listOf(foreignPending), store.fetchPendingSends(foreignRadioId))
        }

        suspend fun assertStatusEvents(expected: List<MessageStatusEvent>) {
            val events = statuses.events.toList()
            assertTrue(events.all { it.token == live.token })
            assertEquals(expected, events.map { it.event })
        }
    }

    private class FailOnceReactionLookupStore(
        private val delegate: RoomPersistenceStore,
        private val failure: Exception = IllegalStateException("Injected reaction lookup failure"),
        private val failOnCall: Int = 1,
    ) : PersistenceStoreProtocol by delegate, QueuedChannelReadinessPersisting by delegate {
        private val lookupCounter = java.util.concurrent.atomic.AtomicInteger()
        val lookupCount: Int get() = lookupCounter.get()
        @Volatile var failed = false
            private set

        override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean {
            if (lookupCounter.incrementAndGet() == failOnCall) {
                failed = true
                throw failure
            }
            return delegate.reactionExists(message, senderName, emoji)
        }
    }
}
