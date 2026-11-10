// AndroidOnly: WP-501 Actual App/runtime/factory queue channel-query composition, not another frozen queue original.
// AndroidOnly: WP-207 Supplemental deferred-recovery ordering, not ungated production acceptance.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QueueChannelConsumerIntegrationTest : RoomProcessTest() {
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
            assertTrue(report.isComplete, "An uncertain send must not create query-recovery teardown: ${report.issues}")
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
            assertTrue(report.isComplete, "No query is started for a raw uncertain send: ${report.issues}")
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

    private enum class QueryReply { CONFIGURED, UNCONFIGURED, RAW_NOT_FOUND, HELD }

    private suspend fun TestScope.withFactoryQueue(reply: QueryReply, body: suspend (Fixture) -> Unit) {
        val f = Fixture(this, reply)
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
        val service = assertIs<MessageServiceException>(
            generateSequence(cause) { it.cause }.filterIsInstance<MessageServiceException>().single(),
        )
        val session = assertIs<MessageServiceError.SessionError>(service.error)
        assertEquals(2.toUByte(), assertIs<MeshCoreException.DeviceError>(session.underlying).code)
    }

    private fun Fixture.assertRetainedDeviceError2() {
        val failures = retainedFailures()
        assertTrue(failures.isNotEmpty(), "The raw send error must be retained")
        failures.forEach(::assertDeviceError2)
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

    private inner class Fixture(test: TestScope, reply: QueryReply) {
        private val recovery = RecoveryGate(StandardTestDispatcher(test.testScheduler))
        val harness = ContainerHarness(test, store,
            recoveryDispatcher = if (reply == QueryReply.RAW_NOT_FOUND) recovery else Dispatchers.Default)
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
        private var holdContacts = true
        private var sending = false

        init {
            harness.links.configureRadio = { radio ->
                firmware = radio
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        4 -> if (holdContacts) emptyList() else null
                        3 -> { sending = true; listOf(Bytes.of(1, 2)) }
                        31 -> when {
                            !sending -> listOf(if (command[1] == 0.toUByte()) configuredReply
                                else Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
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
            assertSame(store, live.dataStore)
            assertEquals(radioId, live.token.radioId)
            assertEquals(live.token, live.messageService.token)
            assertEquals(live.token, live.chatSendQueueService.token)
            statuses = live.messageService.statusEvents()
            assertEquals(listOf(pending), store.fetchPendingSends(radioId))
            assertEquals(message, store.fetchMessage(EntityKey(radioId, message.id)))
            assertTrue(firmware.sent.none { it[0] == 3.toUByte() }, "No channel write before actual runtime READY")
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

        fun queueWire(): List<Bytes> = firmware.sent.dropWhile { it[0] != 3.toUByte() }
            .filter { it[0] == 3.toUByte() || it[0] == 31.toUByte() }

        fun assertWire() {
            assertEquals(listOf(send), queueWire(),
                "The raw channel SEND error must not trigger GET_CHANNEL or another SEND")
        }

        suspend fun assertStored(status: MessageStatus, pending: Boolean) {
            assertEquals(message.copy(status = status), store.fetchMessage(EntityKey(radioId, message.id)))
            val rows = store.fetchPendingSends(radioId)
            if (pending) {
                val retained = rows.single()
                assertEquals(this.pending.id, retained.id)
                assertEquals(8L, retained.attemptCount)
                assertEquals(QueuedChannelDispatchState.UNCERTAIN, retained.channelDispatchState)
                assertNotNull(retained.channelAttemptID)
            } else assertTrue(rows.isEmpty())
            assertEquals(listOf(channel), store.fetchChannels(radioId))
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
            harness.manager.connect(harness.target(deviceID))
            harness.assertReady()
            harness.settle()
            val successor = assertNotNull(harness.sessions.current)
            assertNotSame(live, successor)
            assertEquals(live.token.radioId, successor.token.radioId)
            assertTrue(successor.token.generation.value > live.token.generation.value)
            assertSame(store, successor.dataStore)
            assertEquals(2, harness.sessions.created)
            assertEquals(0, successor.chatSendQueueService.queuedEnvelopeCount)
            assertEquals(listOf(retained), store.fetchPendingSendsForMessage(EntityKey(radioId, message.id)))
            assertEquals(listOf(send), previousFirmware.sent.filter { it[0] == 3.toUByte() })
            assertTrue(firmware.sent.any { it[0] == 31.toUByte() }, "The successor must perform its own channel sync")
            assertTrue(firmware.sent.none { it[0] == 3.toUByte() }, "Reconnection cannot replay an uncertain submission")
        }

        suspend fun disconnect(expectedIssue: Throwable? = null) {
            val report = harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            if (expectedIssue == null) assertTrue(report.isComplete, "Unexpected teardown issues: ${report.issues}")
            else {
                assertEquals(listOf(LifecycleStage.STOP_SERVICES), report.issues.map { it.stage })
                assertSame(expectedIssue, report.issues.single().cause)
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
}
