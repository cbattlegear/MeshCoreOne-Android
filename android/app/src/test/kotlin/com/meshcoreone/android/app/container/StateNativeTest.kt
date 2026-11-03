// AndroidOnly: WP-303 Native tests for the app state holders: battery monitor, message event dispatcher, stale cleanup, codecs and foreground state.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.BatteryMonitor
import com.meshcoreone.android.app.state.BatteryServices
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.app.state.MessageEventDispatcher
import com.meshcoreone.android.app.state.MessageEventHost
import com.meshcoreone.android.app.state.MessageEventSources
import com.meshcoreone.android.app.state.MessageEventStream
import com.meshcoreone.android.app.state.ProcessForegroundState
import com.meshcoreone.android.app.state.RegionSelectionJson
import com.meshcoreone.android.core.contracts.domain.MessageStatusEvent
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.SessionEvent
import com.meshcoreone.android.core.contracts.domain.SessionEventSubscription
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.runtime.DeadlineClock
import com.meshcoreone.android.core.services.reactions.HeardRepeatEvent
import com.meshcoreone.android.core.services.remote.RemoteNodeEvent
import com.meshcoreone.android.core.services.remote.RoomServerEvent
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import com.meshcoreone.android.core.services.sync.SyncDataEventBroadcaster
import com.meshcoreone.android.core.ui.isBatteryPresent
import com.meshcoreone.android.core.ui.percentage
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StateNativeTest {
    private fun TestScope.clock() = SchedulerClock { testScheduler.currentTime }
    private fun TestScope.advance(seconds: Long) { testScheduler.advanceTimeBy(seconds * 1000); testScheduler.runCurrent() }

    // region BatteryMonitor

    private class FakeBattery : BatteryServices {
        var level = 4000L
        var failure: Exception? = null
        var reads = 0
        val posts = mutableListOf<Long>()
        override suspend fun getBattery(): BatteryInfo { reads++; failure?.let { throw it }; return BatteryInfo(level) }
        override suspend fun postLowBatteryNotification(deviceName: String, batteryPercentage: Long) { posts += batteryPercentage }
    }

    private val ocv = OCVPreset.LI_ION.ocvArray
    /** The highest voltage whose charge is still at most [percent]. */
    private fun levelAtMost(percent: Int): Long = (2900L..4300L).last { BatteryInfo(it).percentage(ocv) <= percent }

    /** The lowest voltage whose charge is above [percent]. */
    private fun levelAbove(percent: Int): Long = (2900L..4300L).first { BatteryInfo(it).percentage(ocv) > percent }

    @Test fun startingTwiceKeepsOneBootstrapAndOneRefreshLoop() = runTest {
        val services = FakeBattery()
        val monitor = BatteryMonitor(backgroundScope, clock(), { Instant.EPOCH.plusMillis(testScheduler.currentTime) })
        val device = deviceOf()
        monitor.start(services, device)
        monitor.start(services, device)
        testScheduler.runCurrent()
        assertEquals(1, services.reads, "the replaced bootstrap never ran: one bootstrap read")
        assertTrue(monitor.isRefreshLoopActive)
        advance(125)
        assertEquals(2, services.reads, "one refresh loop polls every 120 s")
        monitor.stop()
        advance(500)
        assertEquals(2, services.reads, "stop ends polling")
        assertFalse(monitor.isRefreshLoopActive)
    }

    @Test fun restartingTheRefreshLoopReplacesTheRunningLoop() = runTest {
        val services = FakeBattery()
        val monitor = BatteryMonitor(backgroundScope, clock())
        val device = deviceOf()
        monitor.startRefreshLoop(services, device)
        monitor.startRefreshLoop(services, device)
        advance(121)
        assertEquals(1, services.reads)
    }

    @Test fun lowBatteryNotifiesOncePerThresholdCrossingAndRearmsAfterRecovery() = runTest {
        val services = FakeBattery()
        val monitor = BatteryMonitor(backgroundScope, clock())
        val device = deviceOf()
        services.level = levelAbove(20)
        monitor.fetchDeviceBattery(services, device)
        assertTrue(services.posts.isEmpty())
        services.level = levelAtMost(19)
        monitor.fetchDeviceBattery(services, device)
        assertEquals(1, services.posts.size, "crossing 20% notifies")
        monitor.fetchDeviceBattery(services, device)
        assertEquals(1, services.posts.size, "the same level does not repeat")
        services.level = levelAbove(20)
        monitor.fetchDeviceBattery(services, device)
        services.level = levelAtMost(19)
        monitor.fetchDeviceBattery(services, device)
        assertEquals(2, services.posts.size, "recovering above the threshold re-arms it")
    }

    @Test fun bootstrapNotifiesWhenAlreadyBelowAThreshold() = runTest {
        val services = FakeBattery().apply { level = levelAtMost(4) }
        val monitor = BatteryMonitor(backgroundScope, clock())
        monitor.start(services, deviceOf())
        testScheduler.runCurrent()
        assertEquals(1, services.posts.size)
        monitor.stop()
    }

    @Test fun missedThresholdsPostOnceForTheWholeGap() = runTest {
        val services = FakeBattery().apply { level = levelAtMost(4) }
        val monitor = BatteryMonitor(backgroundScope, clock())
        val device = deviceOf()
        monitor.checkMissedBatteryThreshold(device, services)
        assertEquals(1, services.posts.size, "20, 10 and 5 are reported with a single notification")
        monitor.checkMissedBatteryThreshold(device, services)
        assertEquals(1, services.posts.size)
    }

    @Test fun overdueFetchHonorsTheFifteenMinuteWindow() = runTest {
        val services = FakeBattery()
        val monitor = BatteryMonitor(backgroundScope, clock(), { Instant.EPOCH.plusMillis(testScheduler.currentTime) })
        val device = deviceOf()
        monitor.fetchBatteryIfOverdue(services, device)
        assertEquals(1, services.reads)
        testScheduler.advanceTimeBy(899_000)
        monitor.fetchBatteryIfOverdue(services, device)
        assertEquals(1, services.reads)
        testScheduler.advanceTimeBy(1_000)
        monitor.fetchBatteryIfOverdue(services, device)
        assertEquals(2, services.reads)
    }

    @Test fun aFailedReadClearsTheBatteryAndCancellationPropagates() = runTest {
        val services = FakeBattery()
        val monitor = BatteryMonitor(backgroundScope, clock())
        monitor.fetchDeviceBattery(services, deviceOf())
        assertTrue(monitor.deviceBattery?.isBatteryPresent == true)
        services.failure = IllegalStateException("radio busy")
        monitor.fetchDeviceBattery(services, deviceOf())
        assertNull(monitor.deviceBattery)
        services.failure = CancellationException("cancelled")
        assertFailsWith<CancellationException> { monitor.fetchDeviceBattery(services, deviceOf()) }
    }

    // endregion

    // region MessageEventDispatcher

    private class Host : MessageEventHost {
        val prewarmDirect = mutableListOf<UUID>()
        val prewarmChannel = mutableListOf<Pair<RadioId, UByte>>()
        val reactions = mutableListOf<UUID>()
        var sessionChanges = 0
        override fun noteDirectMessageForPrewarm(contact: ContactDTO) { prewarmDirect += contact.id }
        override fun noteChannelMessageForPrewarm(radioId: RadioId, channelIndex: UByte) { prewarmChannel += radioId to channelIndex }
        override suspend fun handleReactionNotification(messageId: UUID) { reactions += messageId }
        override fun handleSessionStateChange() { sessionChanges += 1 }
    }

    private class Sources(
        val token: SessionToken = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RadioId(UUID.randomUUID())),
    ) {
        val data = MutableSharedFlow<SyncDataEvent>(extraBufferCapacity = 16)
        val heard = MutableSharedFlow<HeardRepeatEvent>(extraBufferCapacity = 16)
        val region = MutableSharedFlow<com.meshcoreone.android.core.model.SnapshotList<UUID>>(extraBufferCapacity = 16)
        val remote = MutableSharedFlow<RemoteNodeEvent>(extraBufferCapacity = 16)
        val room = MutableSharedFlow<RoomServerEvent>(extraBufferCapacity = 16)
        val status = Channel<SessionEvent<MessageStatusEvent>>(Channel.UNLIMITED)
        var statusClosed = 0
        var onStatusClose: () -> Unit = {}
        fun send(event: MessageStatusEvent) { status.trySend(SessionEvent(token, event)) }
        fun build(dataEvents: Flow<SyncDataEvent> = data, heardEvents: Flow<HeardRepeatEvent> = heard) = MessageEventSources(
            dataEvents, heardEvents, region, remote, room,
            object : SessionEventSubscription<MessageStatusEvent> {
                override val events: Flow<SessionEvent<MessageStatusEvent>> = status.receiveAsFlow()
                override fun close() {
                    statusClosed += 1
                    try {
                        onStatusClose()
                    } finally {
                        status.close()
                    }
                }
            },
        )
    }

    @Test fun dispatcherRoutesEveryServiceEventToTheStreamAndHost() = runTest {
        val host = Host(); val stream = MessageEventStream(); val sources = Sources()
        val dispatcher = MessageEventDispatcher(host, stream, backgroundScope)
        val received = mutableListOf<MessageEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.events().collect { received += it } }
        dispatcher.wire(sources.token, sources.build())
        val radio = RadioId(UUID.randomUUID())
        val contact = ContactDTO(radioId = radio, publicKey = key(2), name = "Peer", lastHeardTimestamp = null)
        val dm = MessageDTO(radioId = radio, contactID = contact.id, text = "hi", timestamp = 1u, createdAt = EPOCH)
        val channelMessage = dm.copy(id = UUID.randomUUID(), contactID = null, channelIndex = 4u)
        val room = RoomMessageDTO(sessionID = UUID.randomUUID(), authorKeyPrefix = key(1), text = "r", timestamp = 1u)
        val messageId = UUID.randomUUID()
        sources.data.tryEmit(SyncDataEvent.DirectMessageReceived(dm, contact))
        sources.data.tryEmit(SyncDataEvent.ChannelMessageReceived(channelMessage, 4u))
        sources.data.tryEmit(SyncDataEvent.RoomMessageReceived(room))
        sources.data.tryEmit(SyncDataEvent.ReactionReceived(messageId, "👍1"))
        sources.data.tryEmit(SyncDataEvent.ContactsChanged)
        sources.data.tryEmit(SyncDataEvent.ConversationsChanged)
        sources.heard.tryEmit(HeardRepeatEvent(messageId, 3))
        sources.region.tryEmit(emptyList<UUID>().snapshot())
        sources.region.tryEmit(listOf(messageId).snapshot())
        sources.remote.tryEmit(RemoteNodeEvent.SessionStateChanged(EntityKey(radio, room.sessionID), true))
        sources.room.tryEmit(RoomServerEvent.StatusUpdated(EntityKey(radio, messageId), MessageStatus.FAILED))
        sources.room.tryEmit(RoomServerEvent.StatusUpdated(EntityKey(radio, messageId), MessageStatus.DELIVERED))
        sources.room.tryEmit(RoomServerEvent.ConnectionRecovered(EntityKey(radio, room.sessionID)))
        sources.send(MessageStatusEvent.StatusResolved(messageId, MessageStatus.SENT, 7u))
        sources.send(MessageStatusEvent.Resent(messageId))
        sources.send(MessageStatusEvent.Retrying(messageId, 2, 4))
        sources.send(MessageStatusEvent.RoutingChanged(contact.id, true))
        sources.send(MessageStatusEvent.Failed(messageId))
        testScheduler.runCurrent()
        assertEquals(
            listOf<MessageEvent>(
                MessageEvent.DirectMessageReceived(dm, contact), MessageEvent.ChannelMessageReceived(channelMessage, 4u),
                MessageEvent.RoomMessageReceived(room, room.sessionID), MessageEvent.ReactionReceived(messageId, "👍1"),
                MessageEvent.HeardRepeatRecorded(messageId, 3), MessageEvent.MessagesRegionUpdated(listOf(messageId)),
                MessageEvent.RoomMessageFailed(messageId), MessageEvent.RoomMessageStatusUpdated(messageId),
                MessageEvent.MessageStatusResolved(messageId, MessageStatus.SENT, 7u), MessageEvent.MessageResent(messageId),
                MessageEvent.MessageRetrying(messageId, 2, 4), MessageEvent.RoutingChanged(contact.id, true),
                MessageEvent.MessageFailed(messageId),
            ).sortedBy { it.toString() },
            received.sortedBy { it.toString() },
        )
        assertEquals(listOf(contact.id), host.prewarmDirect)
        assertEquals(listOf(radio to 4.toUByte()), host.prewarmChannel)
        assertEquals(listOf(messageId), host.reactions)
        assertEquals(2, host.sessionChanges, "session-state change and room connection recovery both bump the counter")
        dispatcher.cancelAll()
    }

    @Test fun rewiringCancelsThePreviousStreamsAndNeverAccumulatesCollectors() = runTest {
        val host = Host(); val stream = MessageEventStream()
        val dispatcher = MessageEventDispatcher(host, stream, backgroundScope)
        val first = Sources(); val second = Sources()
        dispatcher.wire(first.token, first.build())
        val firstJobs = dispatcher.activeJobCount
        dispatcher.wire(second.token, second.build())
        testScheduler.runCurrent()
        assertEquals(firstJobs, dispatcher.activeJobCount, "a re-wire replaces, not adds")
        assertEquals(1, first.statusClosed, "the previous status subscription was closed")
        dispatcher.cancelAll()
        testScheduler.runCurrent()
        assertEquals(0, dispatcher.activeJobCount)
        assertEquals(1, second.statusClosed)
    }

    @Test fun retiringAnOldSameRadioSessionLeavesTheReplacementDispatcherActive() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val epoch = ProcessEpoch(UUID.randomUUID())
        val first = Sources(SessionToken(epoch, Generation(1), radio))
        val second = Sources(SessionToken(epoch, Generation(2), radio))
        val stream = MessageEventStream()
        val dispatcher = MessageEventDispatcher(Host(), stream, backgroundScope)
        val received = mutableListOf<MessageEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.events().collect { received += it } }

        dispatcher.wire(first.token, first.build())
        dispatcher.wire(second.token, second.build())
        val replacementJobs = dispatcher.activeJobCount
        assertTrue(replacementJobs > 0)
        dispatcher.cancelForSession(first.token)
        testScheduler.runCurrent()

        assertEquals(replacementJobs, dispatcher.activeJobCount)
        assertEquals(1, first.statusClosed)
        assertEquals(0, second.statusClosed)
        val messageId = UUID.randomUUID()
        second.send(MessageStatusEvent.Failed(messageId))
        testScheduler.runCurrent()
        assertTrue(received.contains(MessageEvent.MessageFailed(messageId)))

        dispatcher.cancelForSession(second.token)
        dispatcher.cancelForSession(second.token)
        testScheduler.runCurrent()
        assertEquals(0, dispatcher.activeJobCount)
        assertEquals(1, second.statusClosed)
    }

    @Test fun closingRetiredStatusSubscriptionCannotCancelAConcurrentlyWiredReplacement() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val epoch = ProcessEpoch(UUID.randomUUID())
        val first = Sources(SessionToken(epoch, Generation(1), radio))
        val second = Sources(SessionToken(epoch, Generation(2), radio))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        first.onStatusClose = {
            entered.countDown()
            check(release.await(10, TimeUnit.SECONDS)) { "Retired subscription was not released" }
        }
        val stream = MessageEventStream()
        val dispatcher = MessageEventDispatcher(Host(), stream, backgroundScope)
        val received = mutableListOf<MessageEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.events().collect { received += it } }
        dispatcher.wire(first.token, first.build())
        val retiring = async(Dispatchers.IO) { dispatcher.cancelForSession(first.token) }
        try {
            assertTrue(withContext(Dispatchers.IO) { entered.await(10, TimeUnit.SECONDS) })
            dispatcher.wire(second.token, second.build())
            val replacementJobs = dispatcher.activeJobCount
            assertTrue(replacementJobs > 0)
            val messageId = UUID.randomUUID()
            second.send(MessageStatusEvent.Failed(messageId))
            testScheduler.runCurrent()
            assertTrue(received.contains(MessageEvent.MessageFailed(messageId)))
            assertEquals(0, second.statusClosed)
            assertEquals(replacementJobs, dispatcher.activeJobCount)
        } finally {
            release.countDown()
            retiring.await()
            dispatcher.cancelAll()
        }
        assertEquals(1, first.statusClosed)
        assertEquals(1, second.statusClosed)
        assertEquals(0, dispatcher.activeJobCount)
    }

    @Test fun reentrantRewireClosesRetiredSubscriptionsAndKeepsReplacementCollectors() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val epoch = ProcessEpoch(UUID.randomUUID())
        val first = Sources(SessionToken(epoch, Generation(1), radio))
        val second = Sources(SessionToken(epoch, Generation(2), radio))
        val registered = SyncDataEventBroadcaster()
        val firstData = registered.subscribe()
        val contact = ContactDTO(radioId = radio, publicKey = key(2), name = "Peer", lastHeardTimestamp = null)
        val message = MessageDTO(radioId = radio, contactID = contact.id, text = "hi", timestamp = 1u, createdAt = EPOCH)
        registered.yield(SyncDataEvent.DirectMessageReceived(message, contact))
        var lateStarted = false
        var lateClosed = false
        val lateHeard = flow<HeardRepeatEvent> {
            try {
                lateStarted = true
                awaitCancellation()
            } finally {
                lateClosed = true
            }
        }
        val stream = MessageEventStream()
        lateinit var dispatcher: MessageEventDispatcher
        val host = object : MessageEventHost {
            override fun noteDirectMessageForPrewarm(contact: ContactDTO) = dispatcher.wire(second.token, second.build())
            override fun noteChannelMessageForPrewarm(radioId: RadioId, channelIndex: UByte) = Unit
            override suspend fun handleReactionNotification(messageId: UUID) = Unit
            override fun handleSessionStateChange() = Unit
        }
        dispatcher = MessageEventDispatcher(host, stream, backgroundScope)
        val received = mutableListOf<MessageEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.events().collect { received += it } }

        dispatcher.wire(first.token, first.build(firstData, lateHeard))
        testScheduler.runCurrent()

        assertEquals(0, registered.subscriberCount)
        assertTrue(lateStarted)
        assertTrue(lateClosed)
        assertEquals(1, first.statusClosed)
        assertEquals(0, second.statusClosed)
        assertEquals(6, dispatcher.activeJobCount)
        val messageId = UUID.randomUUID()
        second.send(MessageStatusEvent.Failed(messageId))
        testScheduler.runCurrent()
        assertTrue(received.contains(MessageEvent.MessageFailed(messageId)))
        dispatcher.cancelAll()
        assertEquals(1, second.statusClosed)
    }

    @Test fun aThrowingHostDoesNotEndTheStream() = runTest {
        val stream = MessageEventStream(); val sources = Sources()
        val host = object : MessageEventHost {
            override fun noteDirectMessageForPrewarm(contact: ContactDTO) = error("prewarm failed")
            override fun noteChannelMessageForPrewarm(radioId: RadioId, channelIndex: UByte) = Unit
            override suspend fun handleReactionNotification(messageId: UUID) = Unit
            override fun handleSessionStateChange() = Unit
        }
        val dispatcher = MessageEventDispatcher(host, stream, backgroundScope)
        val received = mutableListOf<MessageEvent>()
        backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.events().collect { received += it } }
        dispatcher.wire(sources.token, sources.build())
        val radio = RadioId(UUID.randomUUID())
        val contact = ContactDTO(radioId = radio, publicKey = key(2), name = "Peer", lastHeardTimestamp = null)
        val dm = MessageDTO(radioId = radio, contactID = contact.id, text = "hi", timestamp = 1u, createdAt = EPOCH)
        sources.data.tryEmit(SyncDataEvent.DirectMessageReceived(dm, contact))
        sources.data.tryEmit(SyncDataEvent.ReactionReceived(UUID.randomUUID(), "x"))
        testScheduler.runCurrent()
        assertEquals(2, received.size, "the event after a failing handler is still delivered")
        dispatcher.cancelAll()
    }

    // endregion

    // region Stale node cleanup

    @Test fun staleCleanupHonorsThresholdCooldownAndForce() = runTest {
        val port = FakeConnectionPort()
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val prefs = MemoryStaleCleanup(days = 0)
        val state = appStateOf(backgroundScope, port, stale = prefs, now = { now })
        state.performStaleNodeCleanup()!!.join()
        assertTrue(port.calls.none { it.startsWith("removeStale") }, "no threshold, no cleanup")
        prefs.days = 30
        state.performStaleNodeCleanup()!!.join()
        assertEquals(1, port.calls.count { it == "removeStale:30" })
        assertEquals(now, prefs.last)
        now = now.plusSeconds(3 * 3600 - 1)
        state.performStaleNodeCleanup()!!.join()
        assertEquals(1, port.calls.count { it.startsWith("removeStale") }, "inside the 3 h cooldown")
        state.performStaleNodeCleanup(force = true)!!.join()
        assertEquals(2, port.calls.count { it.startsWith("removeStale") }, "force skips the cooldown")
        now = now.plusSeconds(3 * 3600)
        state.performStaleNodeCleanup()!!.join()
        assertEquals(3, port.calls.count { it.startsWith("removeStale") }, "cooldown elapsed")
    }

    // endregion

    // region Region codec and reference-date conversion (expected values from the swiftc oracle in evidence)

    @Test fun regionJsonMatchesFoundationEncoding() {
        // wp303_foundation_oracle.out: json={"countryCode":"a\/b\"c\\d","source":"manual"}
        val slash = RegionSelection("a/b\"c\\d", RegionSelection.Source.MANUAL)
        assertEquals("{\"countryCode\":\"a\\/b\\\"c\\\\d\",\"source\":\"manual\"}", RegionSelectionJson.encode(slash))
        assertEquals(slash, RegionSelectionJson.decode(RegionSelectionJson.encode(slash)))
        // Foundation decodes escaped code points and any member order.
        assertEquals(
            RegionSelection("PT", RegionSelection.Source.MANUAL),
            RegionSelectionJson.decode("{\"source\":\"manual\",\"countryCode\":\"\\u0050T\"}"),
        )
        val full = RegionSelection("US", RegionSelection.Source.LOCATION, "US-CA", "los angeles")
        assertEquals(full, RegionSelectionJson.decode(RegionSelectionJson.encode(full)))
    }

    @Test fun regionJsonRejectsMalformedAndUnknownContent() {
        for (bad in listOf("", "{", "[]", "{\"countryCode\":\"US\"}", "{\"countryCode\":\"US\",\"source\":\"gps\"}",
            "{\"countryCode\":\"US\",\"source\":\"manual\",\"extra\":\"x\"}", "{\"countryCode\":1,\"source\":\"manual\"}",
            "{\"countryCode\":\"US\",\"countryCode\":\"PT\",\"source\":\"manual\"}", "{\"countryCode\":\"US\",\"source\":\"manual\"} x",
            "{\"countryCode\":\"U\\q\",\"source\":\"manual\"}")) {
            assertNull(RegionSelectionJson.decode(bad), "must reject: $bad")
        }
    }

    @Test fun referenceDateConversionMatchesFoundation() {
        // wp303_foundation_oracle.out: referenceDateEpochSeconds=978307200, 1704067200.5 -> 725760000.5, 726019200.25 -> 1704326400.25
        assertEquals(978_307_200L, DataStoreStaleCleanupPreferences.REFERENCE_DATE_EPOCH_SECONDS)
        assertEquals(725_760_000.5, DataStoreStaleCleanupPreferences.toReferenceSeconds(Instant.ofEpochSecond(1_704_067_200, 500_000_000)))
        assertEquals(
            Instant.ofEpochSecond(1_704_326_400, 250_000_000),
            DataStoreStaleCleanupPreferences.fromReferenceSeconds(726_019_200.25),
        )
    }

    // endregion

    // region ProcessForegroundState

    @Test fun foregroundMeansAtLeastOneStartedActivity() = runTest {
        val state = ProcessForegroundState()
        assertFalse(state.isInForeground())
        assertFalse(state.foregroundFlow.value)
        assertTrue(state.activityStarted(), "the first start enters the foreground")
        assertFalse(state.activityStarted(), "a second activity does not")
        assertTrue(state.isInForeground())
        assertTrue(state.foregroundFlow.value, "the observable flag follows the count")
        assertFalse(state.activityStopped(), "one activity remains")
        assertTrue(state.isInForeground())
        assertTrue(state.activityStopped(), "the last stop backgrounds the process")
        assertFalse(state.isInForeground())
        assertFalse(state.foregroundFlow.value)
        assertFalse(state.activityStopped(), "an unmatched stop never goes negative")
        assertFalse(state.isInForeground())
    }

    // endregion
}
