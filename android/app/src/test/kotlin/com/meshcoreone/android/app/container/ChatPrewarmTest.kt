// PortedFrom: MC1Tests/State/ChatPrewarmRefresherTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/State/ChatTimelinePrimerTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/State/ChatTimelineFreshnessTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.ChatConversationType
import com.meshcoreone.android.app.state.ChatPrewarmRefresher
import com.meshcoreone.android.app.state.ChatPrimerFactory
import com.meshcoreone.android.app.state.ChatSenderTables
import com.meshcoreone.android.app.state.ChatTimelinePrimer
import com.meshcoreone.android.app.state.PopulateMode
import com.meshcoreone.android.app.state.PrimeTimeline
import com.meshcoreone.android.app.state.ReactionIndexing
import com.meshcoreone.android.app.state.TimelineOpenOutcome
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.rendering.ChatCoordinator
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import com.meshcoreone.android.core.services.rendering.ChatRenderState
import com.meshcoreone.android.core.services.rendering.ChatTimelineWriter
import com.meshcoreone.android.core.services.rendering.ChatWriterRole
import com.meshcoreone.android.core.services.rendering.EnvInputs
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * The prime-role timeline over the real store and coordinator writer. `ChatTimeline` itself (render-item baking,
 * dividers, sender resolution) is WP-307; this loads the newest window and commits it through the coordinator's
 * writer, so writer ownership, staleness and the refresh hook chain run for real.
 */
internal class StorePrimeTimeline(private val store: PersistenceStoreProtocol) : PrimeTimeline {
    override var envInputs: EnvInputs = EnvInputs.DEFAULT
    override var messages: SnapshotList<MessageDTO> = SnapshotList.empty()
    override var conversation: ChatConversationType? = null
    private var writer: ChatTimelineWriter? = null
    private val owner = Any()

    /** Awaited after the fetch and before the commit: the store hop a live open can overtake. */
    var afterFetch: suspend () -> Unit = {}

    override fun bind(coordinator: ChatCoordinator, senderTables: () -> ChatSenderTables): Boolean {
        writer = coordinator.bindWriter(owner, ChatWriterRole.PRIME)
        return writer != null
    }

    override suspend fun open(conversation: ChatConversationType, reactions: ReactionIndexing?, populateMode: PopulateMode): TimelineOpenOutcome {
        val target = writer ?: return TimelineOpenOutcome.Unavailable
        this.conversation = conversation
        return try {
            target.beginLoading()
            val fetched = window(store, conversation)
            afterFetch()
            if (!target.isCurrent) return TimelineOpenOutcome.Cancelled
            target.replaceAll(fetched)
            messages = fetched
            TimelineOpenOutcome.Loaded
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            TimelineOpenOutcome.Failed(failure)
        }
    }

    companion object {
        suspend fun window(store: PersistenceStoreProtocol, conversation: ChatConversationType): SnapshotList<MessageDTO> =
            when (conversation) {
                is ChatConversationType.Dm ->
                    store.fetchMessageWindow(EntityKey(conversation.contact.radioId, conversation.contact.id), null, 50)
                is ChatConversationType.Channel ->
                    store.fetchMessageWindow(conversation.channel.radioId, conversation.channel.index, null, 50)
            }.messages.sortedWith(compareBy({ it.timestamp }, { it.createdAt })).snapshot()
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ChatPrewarmTest : RoomProcessTest() {
    private fun channel(radio: RadioId, index: Int = 0) = ChannelDTO(radioId = radio, index = index.toUByte(), name = "TestChannel")
    private fun contact(radio: RadioId, id: UUID = UUID.randomUUID(), unread: Long = 0) =
        ContactDTO(id = id, radioId = radio, publicKey = key(5), name = "TestContact", lastHeardTimestamp = null, unreadCount = unread)

    private fun channelMessage(radio: RadioId, index: Int, timestamp: UInt, text: String) = MessageDTO(
        radioId = radio, contactID = null, channelIndex = index.toUByte(), text = text, timestamp = timestamp,
        createdAt = EPOCH.plusSeconds(timestamp.toLong()), direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
        senderNodeName = "Sender",
    )

    private fun directMessage(radio: RadioId, contactId: UUID, timestamp: UInt, text: String) = MessageDTO(
        radioId = radio, contactID = contactId, text = text, timestamp = timestamp,
        createdAt = EPOCH.plusSeconds(timestamp.toLong()), direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
    )

    /** A previously opened conversation: an interactive owner populates the coordinator, then is discarded. */
    private suspend fun warm(registry: ChatCoordinatorRegistry, conversation: ChatConversationType): ChatCoordinator {
        val coordinator = registry.coordinator(conversation.coordinatorId)
        val owner = Any()
        val writer = assertNotNull(coordinator.bindWriter(owner, ChatWriterRole.INTERACTIVE))
        writer.beginLoading()
        writer.replaceAll(StorePrimeTimeline.window(store, conversation))
        coordinator.releaseWriter(owner)
        return coordinator
    }

    private fun primerDependencies(registry: ChatCoordinatorRegistry) = ChatTimelinePrimer.Dependencies(
        registry = { registry },
        dataStore = { store },
        reactionService = { null },
        onReactionPersisted = { _, _ -> error("Reaction persistence is unused in this test") },
        connectedDeviceNodeName = { null },
    )

    private fun hooks(registry: ChatCoordinatorRegistry, isActive: (ChatPrewarmRefresher.ConversationKind) -> Boolean = { false }) =
        ChatPrewarmRefresher.Hooks(
            registry = { registry },
            dependencies = { primerDependencies(registry) },
            envInputs = { EnvInputs.DEFAULT },
            isConversationActive = isActive,
            channel = { radio, index -> store.fetchChannel(radio, index) },
            contact = { radio, id -> store.fetchContact(EntityKey(radio, id)) },
            makePrimer = { dependencies -> ChatTimelinePrimer(dependencies, StorePrimeTimeline(store)) },
        )

    // region ChatPrewarmRefresherTests

    @OriginalCase("ChatPrewarmRefresherTests::channel arrival re-primes a warm coordinator with the new tail()", "native-equivalent")
    @Test fun channelArrivalReprimes() = runTest {
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val radio = RadioId(UUID.randomUUID())
        val channel = channel(radio)
        store.saveChannel(radio, com.meshcoreone.android.core.protocol.event.ChannelInfo(channel.index, channel.name, Bytes(ByteArray(16))))
        store.saveMessage(channelMessage(radio, 0, 1000u, "old"))
        val coordinator = warm(registry, ChatConversationType.Channel(store.fetchChannel(radio, 0u)!!))
        assertEquals(1, coordinator.messages.size)
        val id = ChatConversationID.channel(radio, 0u)

        val arrival = channelMessage(radio, 0, 2000u, "new")
        store.saveMessage(arrival)
        val refresher = ChatPrewarmRefresher(hooks(registry), backgroundScope, SchedulerClock { testScheduler.currentTime }, Duration.ZERO)
        refresher.noteChannelMessage(radio, 0u)
        refresher.noteChannelMessage(radio, 0u)
        assertEquals(1, refresher.inFlightCount, "a second arrival rides the scheduled refresh")
        assertNotNull(refresher.inFlightJob(id)).join()
        assertEquals(2, coordinator.messages.size)
        assertEquals(arrival.id, coordinator.messages.last().id)
        assertEquals(0, refresher.inFlightCount)
    }

    @OriginalCase("ChatPrewarmRefresherTests::direct-message arrival re-primes a warm coordinator with the new tail()", "native-equivalent")
    @Test fun directArrivalReprimes() = runTest {
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val radio = RadioId(UUID.randomUUID())
        val contact = contact(radio)
        store.saveContact(contact)
        store.saveMessage(directMessage(radio, contact.id, 1000u, "old"))
        val coordinator = warm(registry, ChatConversationType.Dm(contact))
        assertEquals(1, coordinator.messages.size)
        val arrival = directMessage(radio, contact.id, 2000u, "new")
        store.saveMessage(arrival)
        val refresher = ChatPrewarmRefresher(hooks(registry), backgroundScope, SchedulerClock { testScheduler.currentTime }, Duration.ZERO)
        refresher.noteDirectMessage(contact)
        assertNotNull(refresher.inFlightJob(ChatConversationID.dm(radio, contact.id))).join()
        assertEquals(2, coordinator.messages.size)
        assertEquals(arrival.id, coordinator.messages.last().id)
    }

    @OriginalCase("ChatPrewarmRefresherTests::an open conversation is not refreshed()")
    @Test fun openConversationIsNotRefreshed() = runTest {
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val radio = RadioId(UUID.randomUUID())
        store.saveChannel(radio, com.meshcoreone.android.core.protocol.event.ChannelInfo(0u, "TestChannel", Bytes(ByteArray(16))))
        store.saveMessage(channelMessage(radio, 0, 1000u, "old"))
        val coordinator = warm(registry, ChatConversationType.Channel(store.fetchChannel(radio, 0u)!!))
        store.saveMessage(channelMessage(radio, 0, 2000u, "new"))
        val refresher = ChatPrewarmRefresher(hooks(registry) { true }, backgroundScope, SchedulerClock { testScheduler.currentTime }, Duration.ZERO)
        refresher.noteChannelMessage(radio, 0u)
        assertEquals(0, refresher.inFlightCount)
        assertEquals(1, coordinator.messages.size)
    }

    @OriginalCase("ChatPrewarmRefresherTests::a cold conversation is ignored and no coordinator is created()")
    @Test fun coldConversationIsIgnored() = runTest {
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val radio = RadioId(UUID.randomUUID())
        val refresher = ChatPrewarmRefresher(hooks(registry), backgroundScope, SchedulerClock { testScheduler.currentTime }, Duration.ZERO)
        refresher.noteChannelMessage(radio, 0u)
        assertEquals(0, refresher.inFlightCount)
        assertNull(registry.existingCoordinator(ChatConversationID.channel(radio, 0u)))
    }

    // endregion

    // region ChatTimelinePrimerTests

    @OriginalCase("ChatTimelinePrimerTests::a primer resuming from a DB await after the live open has all its writes dropped()", "native-equivalent")
    @Test fun stalePrimerWritesAreDropped() = runTest {
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val radio = RadioId(UUID.randomUUID())
        val contact = contact(radio)
        store.saveMessage(directMessage(radio, contact.id, 1000u, "seed"))
        val conversation = ChatConversationType.Dm(contact)
        val coordinator = registry.coordinator(conversation.coordinatorId)
        val timeline = StorePrimeTimeline(store)
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        timeline.afterFetch = { reached.complete(Unit); gate.await() }
        val primer = ChatTimelinePrimer(primerDependencies(registry), timeline)
        val prime = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { primer.prime(conversation, EnvInputs.DEFAULT) }
        reached.await()

        // The live open supersedes the prime writer while the primer is suspended on the store hop.
        val liveOwner = Any()
        val live = assertNotNull(coordinator.bindWriter(liveOwner, ChatWriterRole.INTERACTIVE))
        assertTrue(live.isCurrent)
        live.replaceAll(listOf(directMessage(radio, contact.id, 2000u, "live")))
        val settledMessages = coordinator.messages
        val settledState = coordinator.renderState
        val settledId = coordinator.renderStateID

        gate.complete(Unit)
        prime.join()
        assertEquals(settledMessages, coordinator.messages)
        assertEquals(settledState, coordinator.renderState)
        assertEquals(settledId, coordinator.renderStateID)
    }

    @OriginalCase("ChatTimelinePrimerTests::a prime is denied while the conversation is open()", "native-equivalent")
    @Test fun primeIsDeniedWhileOpen() = runTest {
        val registry = ChatCoordinatorRegistry(store, backgroundScope)
        val radio = RadioId(UUID.randomUUID())
        val contact = contact(radio)
        val message = directMessage(radio, contact.id, 1000u, "already open")
        store.saveMessage(message)
        val conversation = ChatConversationType.Dm(contact)
        val coordinator = registry.coordinator(conversation.coordinatorId)
        val liveOwner = Any()
        val live = assertNotNull(coordinator.bindWriter(liveOwner, ChatWriterRole.INTERACTIVE))
        live.replaceAll(listOf(message))
        val before = coordinator.messages
        val idBefore = coordinator.renderStateID
        ChatTimelinePrimer(primerDependencies(registry), StorePrimeTimeline(store)).prime(conversation, EnvInputs.DEFAULT)
        assertEquals(before, coordinator.messages)
        assertEquals(idBefore, coordinator.renderStateID)
    }

    // endregion

    // region ChatTimelineFreshnessTests (production AppState hook chain)

    private fun harness(scope: TestScope) = ContainerHarness(
        scope, store, primerFactory = ChatPrimerFactory { dependencies -> ChatTimelinePrimer(dependencies, StorePrimeTimeline(store)) },
    )

    private suspend fun ContainerHarness.connected() {
        manager.connect(target())
        settle()
        assertReady()
    }

    @OriginalCase("ChatTimelineFreshnessTests::production AppState hook chain refreshes a warm closed channel on arrival()", "native-equivalent")
    @Test fun hookChainRefreshesWarmChannel() = runTest {
        val h = harness(this)
        try {
            h.connected()
            val radio = RadioId(UUID.randomUUID())
            store.saveChannel(radio, com.meshcoreone.android.core.protocol.event.ChannelInfo(0u, "TestChannel", Bytes(ByteArray(16))))
            val channel = store.fetchChannel(radio, 0u)!!
            store.saveMessage(channelMessage(radio, 0, 1000u, "old"))
            h.appState.chatEnvInputs(ChatConversationType.Channel(channel), com.meshcoreone.android.app.state.ChatEnvSnapshot("default", false, false, "L"))
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            ChatTimelinePrimer(h.appState.makeChatTimelinePrimerDependencies(), StorePrimeTimeline(store))
                .prime(ChatConversationType.Channel(channel), EnvInputs.DEFAULT)
            val id = ChatConversationID.channel(radio, 0u)
            val coordinator = assertNotNull(registry.existingCoordinator(id))
            assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
            assertEquals(1, coordinator.messages.size)

            val arrival = channelMessage(radio, 0, 2000u, "new")
            store.saveMessage(arrival)
            val refresher = h.appState.ensureChatPrewarmRefresher()
            refresher.noteChannelMessage(radio, 0u)
            val job = assertNotNull(refresher.inFlightJob(id), "the schedule gates passed")
            testScheduler.advanceTimeBy(300); testScheduler.runCurrent()
            job.join()
            assertEquals(2, coordinator.messages.size)
            assertEquals(arrival.id, coordinator.messages.last().id)
        } finally { h.container.close() }
    }

    @OriginalCase("ChatTimelineFreshnessTests::production AppState hook chain refreshes a warm closed DM on arrival()", "native-equivalent")
    @Test fun hookChainRefreshesWarmDirect() = runTest {
        val h = harness(this)
        try {
            h.connected()
            val radio = RadioId(UUID.randomUUID())
            val contact = contact(radio)
            store.saveContact(contact)
            store.saveMessage(directMessage(radio, contact.id, 1000u, "old"))
            h.appState.chatEnvInputs(ChatConversationType.Dm(contact), com.meshcoreone.android.app.state.ChatEnvSnapshot("default", false, false, "L"))
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            ChatTimelinePrimer(h.appState.makeChatTimelinePrimerDependencies(), StorePrimeTimeline(store))
                .prime(ChatConversationType.Dm(contact), EnvInputs.DEFAULT)
            val id = ChatConversationID.dm(radio, contact.id)
            val coordinator = assertNotNull(registry.existingCoordinator(id))
            assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
            val arrival = directMessage(radio, contact.id, 2000u, "new")
            store.saveMessage(arrival)
            val refresher = h.appState.ensureChatPrewarmRefresher()
            refresher.noteDirectMessage(contact)
            val job = assertNotNull(refresher.inFlightJob(id))
            testScheduler.advanceTimeBy(300); testScheduler.runCurrent()
            job.join()
            assertEquals(2, coordinator.messages.size)
            assertEquals(arrival.id, coordinator.messages.last().id)
        } finally { h.container.close() }
    }

    @OriginalCase("ChatTimelineFreshnessTests::a still-alive interactive owner cannot starve the arrival refresh after close()", "native-equivalent")
    @Test fun releasedInteractiveOwnerDoesNotStarveRefresh() = runTest {
        val h = harness(this)
        try {
            h.connected()
            val radio = RadioId(UUID.randomUUID())
            val contact = contact(radio)
            store.saveContact(contact)
            store.saveMessage(directMessage(radio, contact.id, 1000u, "old"))
            h.appState.chatEnvInputs(ChatConversationType.Dm(contact), com.meshcoreone.android.app.state.ChatEnvSnapshot("default", false, false, "L"))
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            val id = ChatConversationID.dm(radio, contact.id)
            val coordinator = registry.coordinator(id)

            // A live open: the interactive owner binds the writer and populates, and stays strongly referenced.
            val owner = Any()
            val writer = assertNotNull(coordinator.bindWriter(owner, ChatWriterRole.INTERACTIVE))
            writer.beginLoading()
            writer.replaceAll(StorePrimeTimeline.window(store, ChatConversationType.Dm(contact)))
            assertEquals(1, coordinator.messages.size)
            // The chat closes while the owner object is still alive: only the explicit release vacates the slot.
            coordinator.releaseWriter(owner)

            val arrival = directMessage(radio, contact.id, 2000u, "new")
            store.saveMessage(arrival)
            val refresher = h.appState.ensureChatPrewarmRefresher()
            refresher.noteDirectMessage(contact)
            val job = assertNotNull(refresher.inFlightJob(id))
            testScheduler.advanceTimeBy(300); testScheduler.runCurrent()
            job.join()
            assertEquals(2, coordinator.messages.size)
            assertEquals(arrival.id, coordinator.messages.last().id)
            assertNotNull(owner)
        } finally { h.container.close() }
    }

    // endregion
}
