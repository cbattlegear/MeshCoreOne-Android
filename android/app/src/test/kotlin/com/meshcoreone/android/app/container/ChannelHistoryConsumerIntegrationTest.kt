// AndroidOnly: WP-202 Active channel timeline and summary reads preserve displayed Room origin.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.notifications.ChannelNotificationTarget
import com.meshcoreone.android.core.contracts.notifications.DeliveredNotification
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.contracts.notifications.NotificationReadReceipt
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.feature.chats.list.ChatChannelKey
import com.meshcoreone.android.feature.chats.composer.ComposerLocationSource
import com.meshcoreone.android.feature.chats.composer.ComposerTarget
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineEvent
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.platform.notifications.messaging.NotificationActionRecovery
import com.meshcoreone.android.platform.notifications.messaging.NotificationActionRecoveryStore
import com.meshcoreone.android.platform.notifications.messaging.NotificationRecoveryRead
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Test

class ChannelHistoryConsumerIntegrationTest : RoomProcessTest() {
    @Test fun notificationActionsRequireTheOriginalBoundChannel() = runTest {
        var afterSelection: (suspend () -> Unit)? = null
        val delivery = object : RecordingDelivery() {
            override suspend fun deliveredNotifications(): SnapshotList<DeliveredNotification> {
                val replace = afterSelection
                afterSelection = null
                replace?.invoke()
                return SnapshotList.empty()
            }
        }
        val h = ContainerHarness(this, store, deliveryOverride = delivery)
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recoveryStore = NotificationActionRecoveryStore(context)
        val text = "  keep the exact reply ${UUID.randomUUID()}  "
        h.links.configureRadio = { firmware ->
            firmware.respond = { frame ->
                when (frame[0].toInt()) {
                    3, 0x20 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val node = assertNotNull(h.sessions.current)
            val radio = node.token.radioId
            val secret = Bytes(ByteArray(16) { 1 })
            node.channelService.setChannelWithSecret(radio, 0u, "A", secret)
            val a = assertNotNull(store.fetchChannel(radio, 0u))
            val origin = assertNotNull(a.occupantID)
            val row = MessageDTO(radioId = radio, channelIndex = 0u, text = "Incoming", timestamp = 100u,
                direction = MessageDirection.INCOMING, isRead = false, channelOccupantID = origin)
            store.saveMessage(row)
            val key = EntityKey(radio, row.id)
            val payload = NotificationPayload.ChannelMessage(radio, 0u, row.id,
                ChannelNotificationTarget.Proven(radio, 0u, origin))
            val reply = NotificationResponse(payload, NotificationAction.REPLY, text)
            val read = NotificationResponse(payload, NotificationAction.MARK_READ, null)
            val recovery = mutableListOf<NotificationResponse>()
            suspend fun respond(response: NotificationResponse) {
                assertTrue(h.container.handleNotificationResponse(response) {
                    recoverChannelNotificationAction(context, it)
                    recovery.add(it)
                })
            }
            fun sends() = h.links.radios.single().sent.count { it[0].toInt() == 3 }
            val before = sends()
            val unprovedPayload = payload.copy(displayTarget = ChannelNotificationTarget.Unproved)
            respond(reply.copy(payload = unprovedPayload))
            respond(read.copy(payload = unprovedPayload))
            assertEquals(listOf(reply.copy(payload = unprovedPayload), read.copy(payload = unprovedPayload)), recovery)
            assertFalse(assertNotNull(store.fetchMessage(key)).isRead)
            assertEquals(before, sends())
            recovery.clear()

            val channelKey = EntityKey(radio, a.id)
            store.incrementChannelUnreadCount(channelKey)
            respond(read)
            assertTrue(assertNotNull(store.fetchMessage(key)).isRead)
            assertEquals(0L, assertNotNull(store.fetchChannel(channelKey)).unreadCount)
            store.incrementChannelUnreadCount(channelKey)
            respond(reply)
            assertEquals(before + 1, sends())
            assertEquals(0L, assertNotNull(store.fetchChannel(channelKey)).unreadCount)
            assertTrue(recovery.isEmpty())

            val b = ChannelInfo(0u, "B", Bytes(ByteArray(16) { 2 }))
            afterSelection = { store.saveChannel(radio, b) }
            val raced = NotificationReadReceipt()
            withContext(raced) { respond(read) }
            assertNull(afterSelection)
            assertEquals(NotificationReadReceipt.State.COMMITTED, raced.state)
            assertEquals(0, raced.completedWrites)
            assertEquals(listOf(read), recovery)
            recovery.clear()

            for (info in listOf(b, ChannelInfo(0u, "A", secret))) {
                store.saveChannel(radio, info)
                val replacement = assertNotNull(store.fetchChannel(radio, 0u))
                assertNotEquals(origin, replacement.occupantID)
                val replacementKey = EntityKey(radio, replacement.id)
                store.saveMessage(row.copy(channelOccupantID = replacement.occupantID))
                store.incrementChannelUnreadCount(replacementKey)
                val unread = assertNotNull(store.fetchChannel(replacementKey)).unreadCount
                respond(read)
                respond(reply)
                assertEquals(listOf(read, reply), recovery)
                assertEquals(text, recovery.last().userText)
                assertEquals(before + 1, sends())
                assertEquals(unread, assertNotNull(store.fetchChannel(replacementKey)).unreadCount)
                assertFalse(assertNotNull(store.fetchMessage(key)).isRead)
                assertTrue(store.fetchMessages(radio, 0u, limit = 50).none {
                    it.direction == MessageDirection.OUTGOING && it.channelOccupantID == replacement.occupantID
                })
                recovery.clear()
            }
            val retained = assertIs<NotificationRecoveryRead.Ready>(recoveryStore.read()).entries
                .filter { it.response.userText == text }
            assertEquals(3, retained.size)
            assertEquals(1, retained.count { it.response == reply.copy(payload = unprovedPayload) })
            assertEquals(2, retained.count { it.response == reply })
        } finally {
            (recoveryStore.read() as? NotificationRecoveryRead.Ready)?.entries
                ?.filter { it.response.userText == text }?.forEach { recoveryStore.dispose(it.id) }
            NotificationActionRecovery.acknowledgeFailure(NotificationActionRecovery.load(context).version)
            h.container.close()
        }
    }

    @Test fun retainedReactionCannotSendAfterEqualValueChannelReplacement() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { firmware ->
            firmware.respond = { frame ->
                when (frame[0].toInt()) {
                    3, 0x20 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val node = assertNotNull(h.sessions.current)
            val radio = node.token.radioId
            val info = ChannelInfo(0u, "A", Bytes(ByteArray(16) { 1 }))
            node.channelService.setChannelWithSecret(radio, 0u, "A", Bytes(ByteArray(16) { 1 }))
            val a = assertNotNull(store.fetchChannel(radio, 0u))
            val feature = h.container.chatsFeature
            val conversation = feature.captureMessageActionConversation(TimelineConversation.Channel(a))
            val message = MessageDTO(radioId = radio, channelIndex = 0u, text = "original message",
                direction = MessageDirection.INCOMING, senderNodeName = "Alice", timestamp = 100u,
                channelOccupantID = a.occupantID)
            store.saveMessage(message)
            val retained = assertNotNull(feature.timeline(conversation).data.fetchMessage(message.id))
            assertNotNull(retained.channelReactionIncarnation)
            fun channelWrites() = h.links.links.last().radio.sent.count { it[0].toInt() == 3 }
            val before = channelWrites()

            feature.react(retained, "\uD83D\uDC4D", conversation)
            assertEquals(before + 1, channelWrites(), "The unchanged channel must still send")
            store.saveMessage(message.copy(text = "Rewritten source"))
            assertFailsWith<PersistenceStoreException> { feature.react(retained, "\uD83D\uDC4D", conversation) }
            assertEquals(before + 1, channelWrites(), "A retained selection must not react to a rewritten row")

            store.saveChannel(radio, ChannelInfo(0u, "B", Bytes(ByteArray(16) { 2 })))
            store.saveChannel(radio, info)
            val replacement = assertNotNull(store.fetchChannel(radio, 0u))
            assertEquals(a.id, replacement.id)
            assertNotEquals(a.occupantID, replacement.occupantID)
            assertFailsWith<PersistenceStoreException> { feature.react(retained, "\uD83D\uDC4D", conversation) }
            assertEquals(before + 1, channelWrites())
            val newConversation = TimelineConversation.Channel(replacement)
            assertTrue(feature.timeline(newConversation).data.fetchMessages(newConversation, 50, 0).isEmpty())
            assertNotSame(h.appState.mountedChatCoordinator(conversation.id, a.occupantID),
                h.appState.mountedChatCoordinator(newConversation.id, replacement.occupantID))
        } finally { h.container.close() }
    }

    @Test fun retainedTimelineCannotSetReplacementFloodScope() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val token = assertNotNull(h.sessions.current).token
            val radio = token.radioId
            store.saveChannel(radio, ChannelInfo(0u, "A", Bytes(ByteArray(16) { 1 })))
            val original = assertNotNull(store.fetchChannel(radio, 0u))
            val feature = h.container.chatsFeature
            val deps = feature.mountedTimeline(TimelineConversation.Channel(original, token))
            val obsoleteToken = token.copy(generation =
                com.meshcoreone.android.core.contracts.domain.Generation(token.generation.value + 1))
            val obsolete = feature.mountedTimeline(TimelineConversation.Channel(original, obsoleteToken))
            val firmware = h.links.radios.last()
            val beforeObsolete = firmware.sent.size
            assertFailsWith<kotlinx.coroutines.CancellationException> { obsolete.syncChannelFloodScope(original) }
            assertEquals(beforeObsolete, firmware.sent.size)

            store.saveChannel(radio, ChannelInfo(0u, "B", Bytes(ByteArray(16) { 2 })))
            val before = firmware.sent.size
            val failure = assertFailsWith<PersistenceStoreException> { deps.syncChannelFloodScope(original) }
            assertEquals(ChannelHistoryConflict.STALE_OCCUPANT, assertIs<ChannelHistoryRejected>(failure.cause).reason)
            assertEquals(before, firmware.sent.size)
        } finally { h.container.close() }
    }

    @Test fun heldChannelDeliveryCannotIncrementReplacementUnread() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val node = assertNotNull(h.sessions.current)
            val radio = node.token.radioId
            val a = seedReconciledChannel(ChannelDTO(radioId = radio, index = 0u, name = "A"))
            val held = CompletableDeferred<Unit>()
            val entered = CompletableDeferred<Unit>()
            val oldDelivery = launch {
                entered.complete(Unit)
                held.await()
                store.incrementChannelDeliveryUnread(a, mention = true)
            }
            entered.await()
            store.saveChannel(radio, ChannelInfo(a.index, "B", Bytes(ByteArray(16) { 2 })))
            val replacement = assertNotNull(store.fetchChannel(radio, a.index))
            assertEquals(a.id, replacement.id)
            assertNotEquals(a.occupantID, replacement.occupantID)
            val dispose = h.container.chatsFeature.activate(ChatSelection.Channel(replacement), node.token)
            assertEquals(replacement.occupantID, node.notificationService.activeConversation.channelOccupantId)
            held.complete(Unit)
            oldDelivery.join()
            val unchanged = assertNotNull(store.fetchChannel(radio, a.index))
            assertEquals(replacement.unreadCount, unchanged.unreadCount)
            assertEquals(replacement.unreadMentionCount, unchanged.unreadMentionCount)
            dispose()
            store.incrementChannelDeliveryUnread(replacement, mention = true)
            val updated = assertNotNull(store.fetchChannel(radio, a.index))
            assertEquals(unchanged.unreadCount + 1, updated.unreadCount)
            assertEquals(unchanged.unreadMentionCount + 1, updated.unreadMentionCount)
        } finally { h.container.close() }
    }

    @Test fun retainedDeleteAndBlockCannotAffectReplacementHistory() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = assertNotNull(h.sessions.current).token.radioId
            val a = seedReconciledChannel(ChannelDTO(radioId = radio, index = 0u, name = "A"))
            val feature = h.container.chatsFeature
            val original = feature.captureMessageActionConversation(TimelineConversation.Channel(a))
            val row = MessageDTO(radioId = radio, channelIndex = a.index, text = "Original",
                direction = MessageDirection.INCOMING, senderNodeName = "Alice", timestamp = 100u)
            store.saveMessage(row)
            val selected = assertNotNull(feature.timeline(original).data.fetchMessage(row.id))
            val actions = feature.displayedActions(selected, original)

            store.saveChannel(radio, ChannelInfo(a.index, "B", Bytes(ByteArray(16) { 2 })))
            val replacement = assertNotNull(store.fetchChannel(radio, a.index))
            val current = feature.captureMessageActionConversation(TimelineConversation.Channel(replacement))
            val replacementRow = row.copy(text = "Replacement", timestamp = 101u)
            store.saveMessage(replacementRow)
            val key = EntityKey(radio, row.id)

            assertFailsWith<PersistenceStoreException> { feature.deleteDisplayed(selected, original) }
            assertFailsWith<PersistenceStoreException> { actions.blockChannelSender(radio, "Alice") }
            assertEquals("Replacement", store.fetchMessage(key)?.text)
            assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())

            val currentRow = assertNotNull(feature.timeline(current).data.fetchMessage(row.id))
            store.saveMessage(replacementRow.copy(text = "Rewritten in place"))
            assertFailsWith<PersistenceStoreException> { feature.deleteDisplayed(currentRow, current) }
            assertFailsWith<PersistenceStoreException> { feature.displayedActions(currentRow, current).blockChannelSender(radio, "Alice") }
            assertEquals("Rewritten in place", store.fetchMessage(key)?.text)
            assertTrue(store.fetchBlockedChannelSenders(radio).isEmpty())

            val rewritten = assertNotNull(feature.timeline(current).data.fetchMessage(row.id))
            feature.deleteDisplayed(rewritten, current)
            assertNull(store.fetchMessage(key))
            store.saveMessage(replacementRow)
            val restoredRow = assertNotNull(feature.timeline(current).data.fetchMessage(row.id))
            feature.displayedActions(restoredRow, current).blockChannelSender(radio, "Alice")
            assertEquals(listOf("Alice"), store.fetchBlockedChannelSenders(radio).map { it.name })
            assertNull(store.fetchMessage(key))
        } finally { h.container.close() }
    }

    @Test fun displayedSendAgainCannotRetargetEqualValueReplacement() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { firmware ->
            firmware.respond = { frame ->
                when (frame[0].toInt()) {
                    3, 0x20 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val node = assertNotNull(h.sessions.current)
            val radio = node.token.radioId
            val secret = Bytes(ByteArray(16) { 1 })
            node.channelService.setChannelWithSecret(radio, 0u, "A", secret)
            val original = assertNotNull(store.fetchChannel(radio, 0u))
            val feature = h.container.chatsFeature
            val conversation = feature.captureMessageActionConversation(TimelineConversation.Channel(original))
            val message = feature.sender(radio).createPendingChannel("retry me", original)
            val key = EntityKey(radio, message.id)
            store.updateMessageStatus(key, MessageStatus.FAILED)
            val retained = assertNotNull(feature.timeline(conversation).data.fetchMessage(message.id))
            val inlineRetry = feature.mountedTimeline(conversation).retryService
            val firmware = h.links.radios.last()
            fun channelWrites() = firmware.sent.count { it[0].toInt() == 3 }
            val before = channelWrites()

            feature.sendDisplayedAgain(retained, conversation)
            h.eventually("unchanged channel sends again") { channelWrites() == before + 1 }
            h.settle()
            store.updateMessageStatus(key, MessageStatus.FAILED)
            val failedAgain = assertNotNull(feature.timeline(conversation).data.fetchMessage(message.id))
            inlineRetry.retry(failedAgain, conversation)
            h.eventually("unchanged channel retries inline") { channelWrites() == before + 2 }
            h.settle()

            store.saveChannel(radio, ChannelInfo(0u, "B", Bytes(ByteArray(16) { 2 })))
            store.saveChannel(radio, ChannelInfo(0u, "A", secret))
            val replacement = assertNotNull(store.fetchChannel(radio, 0u))
            assertNotEquals(original.occupantID, replacement.occupantID)
            assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException> {
                feature.sendDisplayedAgain(retained, conversation)
            }
            assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException> {
                inlineRetry.retry(failedAgain, conversation)
            }
            assertEquals(before + 2, channelWrites())
            assertTrue(store.fetchPendingSendsForMessage(key).isEmpty())
        } finally { h.container.close() }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun replacementLiveEventsExcludeOldAcceptedCompletionAndKeepReceivingCurrentMessages() = runTest {
        val h = ContainerHarness(this, store)
        val radio = RadioId(UUID.randomUUID())
        try {
            store.saveChannel(radio, ChannelInfo(0u, "A", Bytes(ByteArray(16) { 1 })))
            val a = assertNotNull(store.fetchChannel(radio, 0u))
            val old = MessageDTO(radioId = radio, channelIndex = 0u, text = "accepted A", timestamp = 100u,
                status = MessageStatus.PENDING, channelOccupantID = a.occupantID)
            store.saveChannelMessage(old, ChannelHistoryScope(radio, 0u, assertNotNull(a.occupantID)))
            val pending = PendingSendDTO.fromEnvelope(
                ChannelMessageEnvelope(old.id, 0u, false, old.text, old.timestamp,
                    localNodeName = null, channelOccupantID = a.occupantID),
                radio,
            )
            val attempt = QueuedChannelAttemptKey(radio, pending.id, old.id, UUID.randomUUID())
            store.beginInlineChannelAttempt(pending, attempt, assertNotNull(a.occupantID), old.timestamp)
            store.settleQueuedChannelAttempt(attempt, QueuedChannelAttemptOutcome.ACCEPTED)
            store.saveChannel(radio, ChannelInfo(0u, "B", Bytes(ByteArray(16) { 2 })))
            val b = assertNotNull(store.fetchChannel(radio, 0u))
            val timeline = h.container.chatsFeature.timeline(TimelineConversation.Channel(b))
            val observed = mutableListOf<UUID>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                timeline.events.collect { event ->
                    when (event) {
                        is TimelineEvent.MessageReceived -> observed += event.message.id
                        is TimelineEvent.MessageChanged -> observed += assertNotNull(timeline.data.fetchMessage(event.messageId)).id
                        else -> Unit
                    }
                }
            }
            store.completeAcceptedQueuedChannelAttempt(attempt)
            h.appState.messageEventStream.send(MessageEvent.MessageStatusResolved(old.id, MessageStatus.SENT, null))
            h.appState.messageEventStream.send(MessageEvent.ChannelMessageReceived(old, 0u))
            val current = old.copy(id = UUID.randomUUID(), text = "current B", channelOccupantID = b.occupantID)
            store.saveChannelMessage(current, ChannelHistoryScope(radio, 0u, assertNotNull(b.occupantID)))
            h.appState.messageEventStream.send(MessageEvent.ChannelMessageReceived(current, 0u))
            h.appState.messageEventStream.send(MessageEvent.MessageStatusResolved(current.id, MessageStatus.SENT, null))
            val incoming = current.copy(id = UUID.randomUUID(), text = "incoming B", direction = MessageDirection.INCOMING,
                channelOccupantID = null)
            store.saveMessage(incoming)
            h.appState.messageEventStream.send(MessageEvent.ChannelMessageReceived(incoming, 0u))
            testScheduler.runCurrent()
            assertTrue(collector.isActive)
            assertEquals(listOf(current.id, current.id, incoming.id), observed)
            assertEquals(MessageStatus.SENT, store.fetchMessage(EntityKey(radio, old.id))?.status)
        } finally { h.container.close() }
    }

    @Test fun physicalTimelineWindowLookupAndSummaryCannotShowRetainedAAsReplacementB() = runTest {
        val h = ContainerHarness(this, store)
        val radio = RadioId(UUID.randomUUID())
        val at = Instant.ofEpochSecond(1_700_000_000)
        try {
            store.saveChannel(radio, ChannelInfo(0u, "A", Bytes(ByteArray(16) { 1 })))
            val a = assertNotNull(store.fetchChannel(radio, 0u))
            val old = MessageDTO(radioId = radio, channelIndex = 0u, text = "retained original A",
                timestamp = 100u, createdAt = at, channelOccupantID = a.occupantID, status = MessageStatus.FAILED)
            store.saveChannelMessage(old, ChannelHistoryScope(radio, 0u, assertNotNull(a.occupantID)))
            suspend fun withProof(row: MessageDTO): MessageDTO = row.copy(
                channelReactionIncarnation = assertNotNull(
                    store.fetchChannelReactionMatch(EntityKey(radio, row.id))?.channelReactionIncarnation,
                ),
            )
            val oldTimeline = h.container.chatsFeature.timeline(TimelineConversation.Channel(a)).data
            assertEquals(listOf(withProof(old)), oldTimeline.fetchMessages(TimelineConversation.Channel(a), 50, 0))

            store.saveChannel(radio, ChannelInfo(0u, "B", Bytes(ByteArray(16) { 2 })))
            val b = assertNotNull(store.fetchChannel(radio, 0u))
            val current = old.copy(id = UUID.randomUUID(), text = "current B", timestamp = 200u,
                createdAt = at.plusSeconds(10), sortDate = at.plusSeconds(10), channelOccupantID = b.occupantID)
            store.saveChannelMessage(current, ChannelHistoryScope(radio, 0u, assertNotNull(b.occupantID)))
            val currentWithProof = withProof(current)
            val conversation = TimelineConversation.Channel(b)
            val data = h.container.chatsFeature.timeline(conversation).data
            assertEquals(listOf(currentWithProof), data.fetchMessages(conversation, 50, 0))
            assertEquals(listOf(currentWithProof), data.fetchMessageWindow(conversation, at, 1).messages)
            assertEquals(currentWithProof, data.fetchMessage(current.id))
            assertFailsWith<PersistenceStoreException> { data.fetchMessage(old.id) }
            assertFailsWith<PersistenceStoreException> {
                oldTimeline.fetchMessages(TimelineConversation.Channel(a), 50, 0)
            }
            store.incrementChannelUnreadCount(EntityKey(radio, b.id))
            assertFailsWith<PersistenceStoreException> { oldTimeline.clearUnread(TimelineConversation.Channel(a)) }
            assertEquals(1L, store.fetchChannel(radio, b.index)?.unreadCount)
            data.clearUnread(conversation)
            assertEquals(0L, store.fetchChannel(radio, b.index)?.unreadCount)
            val summaries = h.container.chatsFeature.listFor(radio).data
            val key = ChatChannelKey(radio, b.index, b.id, b.occupantID)
            assertEquals(a.id, b.id)
            assertTrue(summaries.fetchFailedSendConversationKeys(radio,
                listOf(ChatChannelKey(radio, a.index, a.id, a.occupantID))).channelIDs.isEmpty(),
                "A still-displayed row cannot receive B's badge before the list refreshes")
            assertEquals(listOf(currentWithProof), summaries.fetchLastChannelMessages(listOf(key), 3).getValue(b.id))
            val unbound = current.copy(id = UUID.randomUUID(), text = "unbound original", channelOccupantID = null)
            store.saveMessage(unbound)
            assertEquals(listOf(currentWithProof), data.fetchMessages(conversation, 50, 0))
            assertEquals(listOf(currentWithProof), summaries.fetchLastChannelMessages(listOf(key), 3).getValue(b.id))
            assertFailsWith<PersistenceStoreException> { data.fetchMessage(unbound.id) }
            val incoming = current.copy(id = UUID.randomUUID(), text = "original incoming semantics",
                direction = MessageDirection.INCOMING, status = MessageStatus.SENT, channelOccupantID = null,
                timestamp = 300u, createdAt = at.plusSeconds(20), sortDate = at.plusSeconds(20), senderNodeName = "B sender")
            store.saveMessage(incoming)
            val incomingWithProof = withProof(incoming)
            assertEquals(listOf(currentWithProof, incomingWithProof), data.fetchMessages(conversation, 50, 0))
            assertEquals(listOf(incomingWithProof), data.fetchMessages(conversation, 1, 0))
            assertEquals(listOf(currentWithProof, incomingWithProof), data.fetchMessageWindow(conversation, at, 1).messages)
            assertEquals(incomingWithProof, data.fetchMessage(incoming.id))
            assertEquals(listOf(currentWithProof, incomingWithProof), summaries.fetchLastChannelMessages(listOf(key), 3).getValue(b.id))
            assertEquals(setOf(b.id), summaries.fetchFailedSendConversationKeys(radio, listOf(key)).channelIDs.toSet())
            data.markFailedSendsSeen(conversation)
            assertTrue(summaries.fetchFailedSendConversationKeys(radio, listOf(key)).channelIDs.isEmpty())
            assertFalse(assertNotNull(store.fetchMessage(EntityKey(radio, old.id))).failureSeen)
            assertFalse(assertNotNull(store.fetchMessage(EntityKey(radio, unbound.id))).failureSeen)
            assertEquals(old.text, store.fetchMessage(EntityKey(radio, old.id))?.text)
            assertEquals(unbound, store.fetchMessage(EntityKey(radio, unbound.id)))
            val hints = h.container.chatsFeature.environment(ComposerTarget.Channel(b), backgroundScope,
                object : ComposerLocationSource {
                    override val isAuthorized = false
                    override val currentCoordinate: Coordinate? = null
                    override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? = null
                })
            assertEquals(mapOf("B sender" to 300u), hints.recentSenderOrder.first { it.isNotEmpty() })
            store.saveChannel(radio, ChannelInfo(0u, "C", Bytes(ByteArray(16) { 3 })))
            assertFailsWith<PersistenceStoreException> { data.fetchMessage(incoming.id) }
            val stale = assertFailsWith<PersistenceStoreException> { data.fetchMessages(conversation, 50, 0) }
            assertEquals(ChannelHistoryConflict.STALE_OCCUPANT, assertIs<ChannelHistoryRejected>(stale.cause).reason)
            h.appState.refreshConversations()
            assertTrue(hints.recentSenderOrder.first { it.isEmpty() }.isEmpty())
        } finally {
            h.container.close()
        }
    }

    @Test fun simulatorTimelineAndSummaryKeepSeededUnboundHistoryWithoutRadioAuthority() = runTest {
        val h = ContainerHarness(this, store)
        val radio = MockDataProvider.simulatorRadioId
        try {
            store.saveSimulatorChannel(ChannelDTO(radioId = radio, index = 0u, name = "Simulator",
                secret = Bytes(ByteArray(16) { 1 })))
            val channel = assertNotNull(store.fetchChannel(radio, 0u))
            assertNull(channel.occupantID)
            val message = MessageDTO(radioId = radio, channelIndex = 0u, text = "seeded simulator history",
                timestamp = 100u, createdAt = Instant.ofEpochSecond(1_700_000_000))
            store.saveMessage(message)
            val conversation = TimelineConversation.Channel(channel)
            val data = h.container.chatsFeature.timeline(conversation).data
            assertEquals(listOf(message), data.fetchMessages(conversation, 50, 0))
            assertEquals(listOf(message), data.fetchMessageWindow(conversation, null, 50).messages)
            val summaries = h.container.chatsFeature.listFor(radio).data
            assertEquals(listOf(message), summaries.fetchLastChannelMessages(
                listOf(ChatChannelKey(radio, channel.index, channel.id)), 3,
            ).getValue(channel.id))
            assertNull(store.fetchChannelOccupantSnapshot(radio, 0u))
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            val feature = h.container.chatsFeature
            feature.deleteDisplayed(message, conversation)
            assertNull(store.fetchMessage(EntityKey(radio, message.id)))
            val incoming = message.copy(direction = MessageDirection.INCOMING, senderNodeName = "Simulator sender")
            store.saveMessage(incoming)
            val actions = feature.displayedActions(incoming, conversation)
            val otherRadio = RadioId(UUID.randomUUID())
            assertFailsWith<PersistenceStoreException> { actions.blockChannelSender(otherRadio, "Simulator sender") }
            assertTrue(store.fetchBlockedChannelSenders(otherRadio).isEmpty())
            actions.blockChannelSender(radio, "Simulator sender")
            assertEquals(listOf("Simulator sender"), store.fetchBlockedChannelSenders(radio).map { it.name })
            assertNull(store.fetchMessage(EntityKey(radio, incoming.id)))
        } finally {
            h.container.close()
        }
    }
}
