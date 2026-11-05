// AndroidOnly: WP-309 Real Room and dispatcher/list/timeline consumers of radio-scoped neutral invalidation.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.*
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.feature.chats.list.ChatListStateHolder
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineDependencies
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PersistenceInvalidationConsumerTest : RoomProcessTest() {
    @Test fun neutralInvalidationRetainsOlderWindowWithoutReactionOrReadWrites() = runTest {
        val radio = RadioId(UUID(309, 170))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Saved"))
        val peer = ContactDTO(radioId = radio, publicKey = key(4), name = "Peer", lastHeardTimestamp = null)
        store.saveContact(peer)
        val rows = (0 until 60).map { offset ->
            MessageDTO(
                radioId = radio, contactID = peer.id, text = "History $offset",
                timestamp = (offset + 1).toUInt(), createdAt = EPOCH.plusSeconds(offset.toLong()),
                status = MessageStatus.DELIVERED,
            ).also { store.saveMessage(it) }
        }
        val h = ContainerHarness(this, store)
        val screenScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val conversation = TimelineConversation.Direct(peer)
        val actual = h.container.chatsFeature.timeline(conversation)
        var reactionCalls = 0
        val dependencies = object : ChatTimelineDependencies by actual {
            override suspend fun prepareMessages(messages: List<MessageDTO>): List<MessageDTO> {
                reactionCalls++
                return actual.prepareMessages(messages)
            }
        }
        val vm = ChatViewModel(conversation, dependencies, screenScope)
        try {
            vm.start()
            vm.open()
            assertTrue(vm.state.value.hasMoreMessages)
            vm.loadOlder()
            assertEquals(rows.map { it.id }, vm.state.value.messages.map { it.id })
            assertFalse(vm.state.value.hasMoreMessages)
            val beforeCalls = reactionCalls
            store.saveContact(peer.copy(unreadCount = 4))
            val changed = rows.last().copy(text = "Persisted new state", status = MessageStatus.FAILED,
                failureSeen = false, reactionSummary = "X:1")
            store.saveMessage(changed)
            h.appState.messageEventStream.send(MessageEvent.MessagePersistenceChanged(EntityKey(radio, changed.id)))
            h.eventually("neutral update preserves paged-in history and actual persisted summary") {
                vm.state.value.messages.lastOrNull()?.reactionSummary == "X:1"
            }
            assertEquals(rows.map { it.id }, vm.state.value.messages.map { it.id })
            assertEquals(beforeCalls, reactionCalls)
            assertEquals(4L, store.fetchContact(EntityKey(radio, peer.id))?.unreadCount)
            assertFalse(requireNotNull(store.fetchMessage(EntityKey(radio, changed.id))).failureSeen)
            store.deleteMessage(EntityKey(radio, changed.id))
            h.appState.messageEventStream.send(MessageEvent.MessagePersistenceChanged(EntityKey(radio, changed.id)))
            h.eventually("neutral deletion retains every surviving older row") { vm.state.value.messages.size == 59 }
            assertEquals(rows.dropLast(1).map { it.id }, vm.state.value.messages.map { it.id })
            assertFalse(vm.state.value.hasMoreMessages)
            assertEquals(beforeCalls, reactionCalls)
            assertEquals(4L, store.fetchContact(EntityKey(radio, peer.id))?.unreadCount)
        } finally {
            screenScope.cancel()
            h.container.close()
        }
    }

    @Test fun delayedDirectCommitPublicationRefetchesReplacementChannelWithoutSeeingItsFailure() =
        consumerFlow(originalChannel = false)

    @Test fun delayedChannelCommitPublicationRefetchesReplacementDirectWithoutSeeingItsFailure() =
        consumerFlow(originalChannel = true)

    private fun consumerFlow(originalChannel: Boolean) = runTest {
        val radio = RadioId(UUID(309, 160))
        val foreign = RadioId(UUID(309, 161))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Saved"))
        val peer = ContactDTO(radioId = radio, publicKey = key(4), name = "Peer",
            lastHeardTimestamp = null, lastMessageDate = EPOCH)
        val channel = ChannelDTO(radioId = radio, index = 3u, name = "Channel", lastMessageDate = EPOCH)
        store.saveContact(peer)
        store.saveChannel(channel)
        val original = MessageDTO(
            radioId = radio, contactID = if (originalChannel) null else peer.id,
            channelIndex = if (originalChannel) channel.index else null,
            text = "Original pending", timestamp = 1u, createdAt = EPOCH,
        )
        store.saveMessage(original)
        val h = ContainerHarness(this, store)
        val screenScope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job]))
        val directConversation = TimelineConversation.Direct(peer)
        val channelConversation = TimelineConversation.Channel(channel)
        var reactionHookCalls = 0
        fun monitored(conversation: TimelineConversation): ChatTimelineDependencies {
            val actual = h.container.chatsFeature.timeline(conversation)
            return object : ChatTimelineDependencies by actual {
                override suspend fun prepareMessages(messages: List<MessageDTO>): List<MessageDTO> {
                    reactionHookCalls++
                    return actual.prepareMessages(messages)
                }
            }
        }
        val direct = ChatViewModel(directConversation, monitored(directConversation), screenScope)
        val channels = ChatViewModel(channelConversation, monitored(channelConversation), screenScope)
        val list = ChatListStateHolder(h.container.chatsFeature.listFor(radio), screenScope)
        val statuses = Channel<SessionEvent<MessageStatusEvent>>(Channel.UNLIMITED)
        val token = SessionToken(ProcessEpoch(UUID(309, 162)), Generation(1), radio)
        val dispatcher = MessageEventDispatcher(h.appState, h.appState.messageEventStream, screenScope)
        val observed = mutableListOf<MessageEvent>()
        screenScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { observed += it }
        }
        dispatcher.wire(MessageEventSources(
            emptyFlow(), emptyFlow(), emptyFlow(), emptyFlow(), emptyFlow(),
            object : SessionEventSubscription<MessageStatusEvent> {
                override val events = statuses.receiveAsFlow()
                override fun close() { statuses.close() }
            },
        ))
        suspend fun publish(key: EntityKey) {
            statuses.send(SessionEvent(token, MessageStatusEvent.PersistenceChanged(key)))
        }
        fun visible(channelRow: Boolean) {
            h.container.navigation.selectTab(AppTab.CHATS)
            if (channelRow) h.container.navigation.navigateToChannel(channel)
            else h.container.navigation.navigateToChat(peer)
        }
        val current = if (originalChannel) channels else direct
        val replacementView = if (originalChannel) direct else channels
        val originalConversationId = if (originalChannel) channel.id else peer.id
        val replacementConversationId = if (originalChannel) peer.id else channel.id
        val key = EntityKey(radio, original.id)
        var delayedPublication: Deferred<Unit>? = null
        val releasePublication = CompletableDeferred<Unit>()
        try {
            direct.start()
            channels.start()
            direct.open()
            channels.open()
            list.start()
            list.requestConversationReload()?.join()
            h.settle()
            val initialReactionCalls = reactionHookCalls
            assertTrue(initialReactionCalls > 0)
            visible(originalChannel)
            store.saveMessage(original.copy(status = MessageStatus.FAILED, reactionSummary = "X:1"))
            publish(key)
            h.eventually("normal persisted failure reloads timeline, preview and visible badge") {
                current.state.value.messages.singleOrNull()?.status == MessageStatus.FAILED &&
                    current.state.value.messages.singleOrNull()?.reactionSummary == "X:1" &&
                    list.state.value.lastMessages[originalConversationId]?.status == MessageStatus.FAILED &&
                    originalConversationId in list.state.value.failedSendIds
            }
            assertFalse(requireNotNull(store.fetchMessage(key)).failureSeen)
            assertEquals(initialReactionCalls, reactionHookCalls)

            val committed = CompletableDeferred<Unit>()
            delayedPublication = async {
                store.saveMessage(original.copy(status = MessageStatus.FAILED))
                committed.complete(Unit)
                releasePublication.await()
                publish(key)
            }
            committed.await()
            store.deleteMessage(key)
            val replacement = original.copy(
                contactID = if (originalChannel) peer.id else null,
                channelIndex = if (originalChannel) null else channel.index,
                text = "Actual replacement", status = MessageStatus.FAILED, failureSeen = false,
                reactionSummary = "X:1",
            )
            store.saveMessage(replacement)
            val unrelated = replacement.copy(
                id = UUID.randomUUID(), text = "Unrelated retained failure", timestamp = 0u,
                createdAt = EPOCH.minusSeconds(1), sortDate = EPOCH.minusSeconds(1),
            )
            store.saveMessage(unrelated)
            visible(!originalChannel)
            releasePublication.complete(Unit)
            delayedPublication.await()
            h.eventually("delayed old commit publication reloads actual replacement and removes old conversation row") {
                current.state.value.messages.none { it.id == original.id } &&
                    replacementView.state.value.messages.any { it.id == replacement.id && it.text == replacement.text } &&
                    list.state.value.lastMessages[replacementConversationId]?.text == replacement.text &&
                    replacementConversationId in list.state.value.failedSendIds &&
                    originalConversationId !in list.state.value.failedSendIds
            }
            assertEquals(replacement, store.fetchMessage(key))
            assertFalse(requireNotNull(store.fetchMessage(key)).failureSeen)
            assertFalse(requireNotNull(store.fetchMessage(EntityKey(radio, unrelated.id))).failureSeen)

            store.saveMessage(replacement.copy(text = "Unannounced current radio change"))
            publish(EntityKey(foreign, original.id))
            h.settle()
            assertEquals(replacement.text, replacementView.state.value.messages.single { it.id == original.id }.text)
            assertEquals(replacement.text, list.state.value.lastMessages[replacementConversationId]?.text)
            assertTrue(observed.contains(MessageEvent.MessagePersistenceChanged(EntityKey(foreign, original.id))))
            assertFalse(requireNotNull(store.fetchMessage(key)).failureSeen)

            store.updateMessageStatus(key, MessageStatus.DELIVERED)
            publish(key)
            h.eventually("actual delivered state survives stale failure-origin invalidation") {
                replacementView.state.value.messages.any { it.id == original.id && it.status == MessageStatus.DELIVERED } &&
                    list.state.value.lastMessages[replacementConversationId]?.status == MessageStatus.DELIVERED
            }
            assertEquals(MessageStatus.DELIVERED, store.fetchMessage(key)?.status)
            assertTrue(replacementConversationId in list.state.value.failedSendIds)
            assertFalse(requireNotNull(store.fetchMessage(EntityKey(radio, unrelated.id))).failureSeen)
            store.deleteMessage(key)
            store.deleteMessage(EntityKey(radio, unrelated.id))
            publish(key)
            h.eventually("missing actual row clears timeline and list preview without failure publication") {
                replacementView.state.value.messages.none { it.id == original.id } &&
                    list.state.value.lastMessages[replacementConversationId] == null &&
                    replacementConversationId !in list.state.value.failedSendIds
            }
            assertNull(store.fetchMessage(key))
            assertTrue(observed.none { it is MessageEvent.MessageFailed })
            assertEquals(initialReactionCalls, reactionHookCalls)
            h.appState.messageEventStream.send(MessageEvent.ChannelHistoryDeleted(radio))
            h.eventually("ordinary history deletion retains its original reaction-reconciliation hook") {
                reactionHookCalls > initialReactionCalls
            }
        } finally {
            releasePublication.complete(Unit)
            delayedPublication?.cancelAndJoin()
            dispatcher.cancelAll()
            screenScope.cancel()
            h.container.close()
        }
    }
}
