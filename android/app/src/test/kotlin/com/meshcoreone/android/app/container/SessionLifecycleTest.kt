// AndroidOnly: WP-303 Native proof: reconnect and radio switch build one graph per generation and release every monitor and stream.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagingDiagnostic
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.messaging.ChannelQueryAccountingEvent
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionLifecycleTest : RoomProcessTest() {
    private suspend fun ContainerHarness.connect(target: ConnectionTarget = target()) {
        manager.connect(target)
        settle()
        assertReady()
    }

    private suspend fun ContainerHarness.disconnect() {
        manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
        settle()
    }

    @Test
    fun reconnectCyclesKeepOneLiveGraphAndNeverGrowSubscribersOrJobs() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val seen = mutableListOf<RadioSessionContainer>()
            val dispatcherJobs = mutableListOf<Int>()
            val sessionJobs = mutableListOf<Int>()
            val dataSubscribers = mutableListOf<Int>()
            repeat(3) {
                h.connect()
                val live = assertNotNull(h.sessions.current)
                assertSame(h.chatSendQueueProgress, live.chatSendQueueProgress,
                    "every replacement graph receives the process-owned send progress")
                seen += live
                dispatcherJobs += h.appState.messageEventDispatcher.activeJobCount
                sessionJobs += h.appState.activeSessionJobCount
                dataSubscribers += live.syncCoordinator.dataEventBroadcaster.subscriberCount
                assertEquals(1, h.sessions.outstanding, "exactly one live graph while connected")
                h.disconnect()
                assertNull(h.sessions.current)
                assertEquals(0, h.sessions.outstanding, "no graph survives a disconnect")
            }
            assertEquals(3, h.sessions.created)
            assertEquals(3, h.sessions.tornDown)
            assertEquals(3, seen.map { System.identityHashCode(it) }.toSet().size)
            assertEquals(1, dispatcherJobs.toSet().size, "dispatcher collectors do not accumulate: $dispatcherJobs")
            assertEquals(1, sessionJobs.toSet().size, "app-state collectors do not accumulate: $sessionJobs")
            assertEquals(1, dataSubscribers.toSet().size, "data-event subscribers do not accumulate: $dataSubscribers")
            for (old in seen) {
                assertTrue(old.isTornDown)
                assertFalse(old.isEventMonitoringActive)
                assertEquals(0, old.liveJobCount, "no coroutine outlives its graph")
                assertEquals(0, old.syncCoordinator.dataEventBroadcaster.subscriberCount)
            }
            assertTrue(h.links.radios.all { it.closes == 1 && it.readers == 0 }, "each physical link closed exactly once")
        } finally { h.container.close() }
    }

    @Test
    fun radioSwitchTearsDownTheOldGraphBeforeTheNewOneServes() = runTest {
        val h = ContainerHarness(this, store, identity = { target ->
            if (target is ConnectionTarget.Bluetooth && target.deviceId == SECOND) key(9) else key(7)
        })
        try {
            h.connect(h.target(FIRST))
            val first = assertNotNull(h.sessions.current)
            val firstRadio = first.token.radioId
            val before = h.appState.servicesVersion.value
            h.manager.connect(h.target(SECOND))
            h.settle()
            h.assertReady()
            val second = assertNotNull(h.sessions.current)
            assertNotSame(first, second)
            assertNotEquals(firstRadio, second.token.radioId, "the second radio is a different identity")
            assertSame(h.chatSendQueueProgress, first.chatSendQueueProgress)
            assertSame(first.chatSendQueueProgress, second.chatSendQueueProgress,
                "radio isolation is keyed within one process-owned progress tracker")
            assertTrue(first.isTornDown)
            assertEquals(0, first.liveJobCount)
            assertEquals(1, h.sessions.outstanding)
            assertTrue(h.appState.servicesVersion.value > before, "views reload on a services change")
            assertSame(second.syncCoordinator, h.appState.syncCoordinator)
            assertEquals(second.token.radioId, h.appState.currentRadioId)
        } finally { h.container.close() }
    }

    @Test
    fun foregroundAppRetainsRawChannelErrorsAcrossEnvelopesAndReconnectWithoutReplay() = runTest {
        val radioId = RadioId(UUID.fromString("A0000000-0000-0000-0000-000000000001"))
        val deviceId = UUID.fromString("A0000000-0000-0000-0000-000000000002")
        val messageAId = UUID.fromString("A0000000-0000-0000-0000-000000000004")
        val pendingAId = UUID.fromString("A0000000-0000-0000-0000-000000000005")
        val messageBId = UUID.fromString("A0000000-0000-0000-0000-000000000006")
        val pendingBId = UUID.fromString("A0000000-0000-0000-0000-000000000007")
        val accounting = ConcurrentLinkedQueue<ChannelQueryAccountingEvent>()
        val bootstrapRequests = ConcurrentLinkedQueue<Pair<SessionToken, UByte>>()
        val queueQueries = ConcurrentLinkedQueue<Pair<SessionToken, UByte>>()
        lateinit var h: ContainerHarness
        h = ContainerHarness(
            this,
            store,
            sessionConfiguration = SessionConfiguration(defaultTimeout = 0.2),
            onChannelQueryAccounting = { accounting += it },
        )
        val failedMessages = ConcurrentLinkedQueue<UUID>()
        val failuresObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { event ->
                if (event is MessageEvent.MessageFailed) failedMessages += event.messageID
            }
        }
        h.links.configureRadio = { radio ->
            val sending = AtomicBoolean(false)
            radio.respond = { command ->
                when (command[0].toInt()) {
                    3 -> {
                        check(h.manager.connectionState == DeviceConnectionState.READY)
                        sending.set(true)
                        listOf(Bytes.of(1, 2))
                    }
                    31 -> {
                        val index = command[1]
                        val token = checkNotNull(h.manager.snapshot.value.token)
                        if (sending.get()) {
                            queueQueries += token to index
                            emptyList()
                        } else {
                            bootstrapRequests += token to index
                            listOf(
                                Bytes.of(0x12, index.toInt()) +
                                    Bytes.utf8(if (index == 0.toUByte()) "Test" else "").paddedOrTruncated(32) +
                                    Bytes(ByteArray(16) { if (index == 0.toUByte()) 0xaa.toByte() else 0 }),
                            )
                        }
                    }
                    else -> null
                }
            }
        }

        val channel = ChannelDTO(
            id = UUID.fromString("A0000000-0000-0000-0000-000000000003"),
            radioId = radioId,
            index = 0u,
            name = "Test",
            secret = Bytes.fromHex("aa".repeat(16)),
        )
        store.saveDevice(DeviceDTO(
            id = deviceId,
            radioId = radioId,
            publicKey = key(7),
            nodeName = "Stored Node",
            isActive = false,
        ))
        seedReconciledChannel(channel)
        val occupantID = assertNotNull(store.fetchChannelOccupantID(radioId, 0u))
        val messageA = MessageDTO(
            id = messageAId,
            radioId = radioId,
            channelIndex = 0u,
            text = "Hello channel",
            timestamp = 1_704_067_200u,
            createdAt = EPOCH,
            sortDate = EPOCH,
            direction = MessageDirection.OUTGOING,
            status = MessageStatus.PENDING,
            channelOccupantID = occupantID,
        )
        val messageB = messageA.copy(id = messageBId, text = "Second message")
        store.saveMessage(messageA)
        store.saveMessage(messageB)
        store.upsertPendingSend(PendingSendDTO(
            id = pendingAId,
            radioId = radioId,
            messageID = messageAId,
            kind = PendingSendKind.CHANNEL,
            contactID = null,
            channelIndex = 0u,
            isResend = true,
            messageText = messageA.text,
            messageTimestamp = messageA.timestamp,
            localNodeName = "Stored Node",
            sequence = 1,
            enqueuedAt = EPOCH,
            attemptCount = 7,
            channelOccupantID = occupantID,
            channelDispatchState = QueuedChannelDispatchState.READY,
        ))
        store.upsertPendingSend(PendingSendDTO(
            id = pendingBId,
            radioId = radioId,
            messageID = messageBId,
            kind = PendingSendKind.CHANNEL,
            contactID = null,
            channelIndex = 0u,
            isResend = false,
            messageText = messageB.text,
            messageTimestamp = messageB.timestamp,
            localNodeName = "Stored Node",
            sequence = 2,
            enqueuedAt = EPOCH,
            attemptCount = 2,
            channelOccupantID = occupantID,
            channelDispatchState = QueuedChannelDispatchState.READY,
        ))

        suspend fun assertUncertainRows() {
            val rows = store.fetchPendingSends(radioId).associateBy { it.id }
            assertEquals(setOf(pendingAId, pendingBId), rows.keys)
            for ((pendingID, messageID, attemptCount) in listOf(
                Triple(pendingAId, messageAId, 8L),
                Triple(pendingBId, messageBId, 3L),
            )) {
                val row = rows.getValue(pendingID)
                assertEquals(messageID, row.messageID)
                assertEquals(attemptCount, row.attemptCount)
                assertEquals(QueuedChannelDispatchState.UNCERTAIN, row.channelDispatchState)
                assertNotNull(row.channelAttemptID)
                assertEquals(MessageStatus.PENDING, store.fetchMessage(EntityKey(radioId, messageID))?.status)
            }
        }

        try {
            assertTrue(h.container.foreground.activityStarted())
            h.appState.handleBecameActive()
            h.appState.handleReturnToForeground()
            h.container.uiScope.async { h.manager.connect(h.target(deviceId)) }.await()
            h.assertReady()
            val first = assertNotNull(h.sessions.current)
            assertEquals(radioId, first.token.radioId)
            assertEquals((0 until 8).map { it.toUByte() }.toSet(),
                bootstrapRequests.filter { it.first == first.token }.map { it.second }.toSet(),
                "Initial foreground sync still reads every advertised slot before queue dispatch")
            h.eventually("both original rows retain their correlated raw ERROR2 attempts") {
                h.links.radios.single().sent.count { it[0] == 3.toUByte() } == 2 && runBlocking {
                    store.fetchPendingSends(radioId).size == 2 &&
                        store.fetchPendingSends(radioId).all {
                            it.channelDispatchState == QueuedChannelDispatchState.UNCERTAIN
                        }
                }
            }
            assertUncertainRows()
            val retainedIDs = h.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>()
                .filter { it.operation.startsWith("channelRetained.") }
                .map { UUID.fromString(it.operation.substringAfterLast('.')) }
                .toSet()
            assertEquals(setOf(pendingAId, pendingBId), retainedIDs)
            assertTrue(queueQueries.isEmpty(), "Raw ERROR2 must not authorize GET_CHANNEL")
            assertTrue(accounting.isEmpty(), "No query failure is counted or persisted")
            assertTrue(failedMessages.isEmpty(), "Uncertainty is not a terminal failure notification")
            assertEquals(DeviceConnectionState.READY, h.manager.connectionState)
            assertSame(first, h.sessions.current)
            assertEquals(1, h.sessions.created)

            first.chatSendQueueService.transportDidOpen()
            h.settle()
            assertEquals(2, h.links.radios.single().sent.count { it[0] == 3.toUByte() },
                "A transport wake cannot automatically replay uncertain attempts")
            assertUncertainRows()

            val report = h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertTrue(report.isComplete, "Explicit teardown after uncertainty is clean: ${report.issues}")
            h.settle()
            assertUncertainRows()
            h.manager.connect(h.target(deviceId))
            h.settle()
            h.assertReady()
            assertEquals(2, h.lifecycle.created.size)
            assertEquals(listOf(2, 0), h.links.radios.map { radio ->
                radio.sent.count { it[0] == 3.toUByte() }
            }, "A replacement graph must not replay retained uncertain rows")
            assertTrue(queueQueries.isEmpty())
            assertTrue(accounting.isEmpty())
            assertTrue(failedMessages.isEmpty())
            assertUncertainRows()
        } finally {
            failuresObserver.cancelAndJoin()
            h.container.close()
        }
    }

    @Test
    fun teardownFinishesEveryEventStreamAndClearsNotificationForwarders() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            var completed = false
            // Subscribed straight on the coordinator and collected outside app state: only the container's own
            // finishDataEvents() can end this stream.
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                live.syncCoordinator.dataEvents().collect { }
                completed = true
            }
            assertNotNull(live.notificationService.onQuickReply, "app state installed the forwarders")
            h.disconnect()
            h.eventually("the stream to finish") { completed }
            collector.cancel()
            assertNull(live.notificationService.onQuickReply)
            assertNull(live.notificationService.onChannelQuickReply)
            assertNull(live.notificationService.onMarkAsRead)
            assertNull(live.notificationService.onChannelMarkAsRead)
            assertNull(live.notificationService.onRoomMarkAsRead)
        } finally { h.container.close() }
    }

    @Test
    fun startingMonitorsTwiceDoesNotDuplicateAnyMonitor() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            assertTrue(live.isEventMonitoringActive)
            val before = live.liveJobCount
            live.startEventMonitoring(live.token.radioId)
            live.startEventMonitoring(live.token.radioId, enableAutoFetch = true, enableAdvertisementMonitoring = true)
            h.settle()
            assertEquals(before, live.liveJobCount, "a second start must be a no-op")
        } finally { h.container.close() }
    }

    @Test
    fun tearDownIsIdempotentAndRefusesToRestartMonitors() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            h.disconnect()
            val again = live.tearDown()
            assertTrue(again.isComplete, "a repeated teardown replays the first receipt: ${again.issues}")
            live.startEventMonitoring(live.token.radioId)
            assertFalse(live.isEventMonitoringActive, "a torn-down graph never restarts a monitor")
            assertEquals(0, live.liveJobCount)
        } finally { h.container.close() }
    }

    @Test
    fun processGlobalDebugLogBufferNeverPointsAtATornDownGraph() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            assertSame(live.debugLogBuffer, DebugLogBuffer.shared)
            h.disconnect()
            assertNotSame(live.debugLogBuffer, DebugLogBuffer.shared)
            assertSame(h.container.bootstrapDebugLog, DebugLogBuffer.shared)
        } finally { h.container.close() }
    }

    @Test
    fun explicitDisconnectRunsTheSameSessionTeardownAsConnectionLoss() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            assertTrue(h.appState.activeSessionJobCount > 0)
            h.appState.disconnect()
            h.settle()
            assertEquals(0, h.appState.activeSessionJobCount)
            assertEquals(0, h.appState.messageEventDispatcher.activeJobCount)
            assertEquals(DeviceConnectionState.DISCONNECTED, h.manager.connectionState)
        } finally { h.container.close() }
    }

    private companion object {
        val FIRST: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
        val SECOND: UUID = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    }
}
