// AndroidOnly: WP-309 Issue #729 real firmware frames, receive services, Room, unread and notification admission.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.remote.RoomServerEvent
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

private class InterleavedTextRuleStore(private val backing: RoomPersistenceStore) :
    PersistenceStoreProtocol by backing,
    TextRulePersisting by backing,
    DirectAckAuthority by backing,
    DirectPendingCompletionAuthority by backing,
    DirectQueueAuthority by backing,
    DirectMessageCreationAuthority by backing,
    OutgoingReactionPersisting by backing,
    ChannelHistoryPersisting by backing,
    ChannelDraftRecoveryPersisting by backing,
    QueuedChannelTerminalPersisting by backing,
    QueuedChannelReadinessPersisting by backing,
    InlineChannelAttemptPersisting by backing,
    ChannelSubmissionPersisting by backing {
    var beforeAdmission: suspend (MessageDTO, ContactDTO?) -> Unit = { _, _ -> }
    var beforeChannelAdmission: suspend (MessageDTO, ChannelDTO?) -> Unit = { _, _ -> }
    var afterAdmission: suspend (MessageDTO, ContactDTO?, TextRuleAdmission) -> Unit = { _, _, _ -> }
    var beforeDirectValidation: suspend (MessageDTO, ContactDTO, Boolean) -> Unit = { _, _, _ -> }
    var beforeChannelMatch: suspend (EntityKey) -> Unit = {}
    var beforeChannelRead: suspend (RadioId, UByte) -> Unit = { _, _ -> }
    var beforeRoomNameLookup: suspend (Bytes) -> Unit = {}
    var beforeRoomAdmission: suspend (RoomMessageDTO, RemoteNodeSessionDTO) -> Unit = { _, _ -> }
    var afterRoomAdmission: suspend (RoomMessageDTO, RemoteNodeSessionDTO, TextRuleRoomAdmission) -> Unit = { _, _, _ -> }
    var beforeRoomValidation: suspend (RemoteNodeSessionDTO, RoomMessageDTO?) -> Unit = { _, _ -> }

    override suspend fun admitTextMessage(
        message: MessageDTO, contact: ContactDTO?, channel: ChannelDTO?,
        wireText: String, wireTextType: UByte, isReaction: Boolean,
        receivedAt: Instant, isViewingConversation: Boolean,
        onCommitted: (TextRuleAdmission, String?) -> Unit,
    ): TextRuleAdmission {
        beforeAdmission(message, contact)
        beforeChannelAdmission(message, channel)
        val result = backing.admitTextMessage(
            message, contact, channel, wireText, wireTextType, isReaction, receivedAt, isViewingConversation, onCommitted,
        )
        afterAdmission(message, contact, result)
        return result
    }

    override suspend fun currentDirectTextContact(
        message: MessageDTO, contact: ContactDTO, messageSaved: Boolean, messageIncarnation: String?,
    ): ContactDTO? {
        beforeDirectValidation(message, contact, messageSaved)
        return backing.currentDirectTextContact(message, contact, messageSaved, messageIncarnation)
    }

    override suspend fun fetchChannelReactionMatch(key: EntityKey): MessageDTO? {
        beforeChannelMatch(key)
        return backing.fetchChannelReactionMatch(key)
    }

    override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? {
        beforeChannelRead(radioId, index)
        return backing.fetchChannel(radioId, index)
    }

    override suspend fun findContactNameByKeyPrefix(prefix: Bytes): String? {
        beforeRoomNameLookup(prefix)
        return backing.findContactNameByKeyPrefix(prefix)
    }

    override suspend fun admitRoomText(
        message: RoomMessageDTO, room: RemoteNodeSessionDTO, onCommitted: (TextRuleRoomAdmission) -> Unit,
    ): TextRuleRoomAdmission {
        beforeRoomAdmission(message, room)
        val result = backing.admitRoomText(message, room, onCommitted)
        afterRoomAdmission(message, room, result)
        return result
    }

    override suspend fun currentRoomTextSession(
        room: RemoteNodeSessionDTO, message: RoomMessageDTO?,
    ): RemoteNodeSessionDTO? {
        beforeRoomValidation(room, message)
        return backing.currentRoomTextSession(room, message)
    }
}

class IncomingTextRulesTest : RoomProcessTest() {
    private val stamp = 1_704_067_200u
    private suspend fun TestScope.withFixture(
        persistence: PersistenceStoreProtocol = store,
        delivery: RecordingDelivery = RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED),
        block: suspend (RxIngestionFixture) -> Unit,
    ) {
        val f = RxIngestionFixture(this, store, persistence = persistence,
            delivery = delivery)
        try {
            f.connect()
            f.harness.eventually("notifications no longer suppressed") {
                f.harness.sessions.current?.notificationService?.isSuppressingNotifications == false
            }
            block(f)
        } finally { f.harness.container.close() }
    }
    private suspend fun enable(kind: TextRuleKind, phrase: String = "filter", field: TextRuleField = TextRuleField.MESSAGE) {
        store.updateTextRules {
            it.copy(rules = listOf(TextRule(phrase = phrase, field = field)),
                selections = it.selections + (kind to TextRuleSelection(TextRuleSelectionMode.ALL)))
        }
    }
    private suspend fun direct(f: RxIngestionFixture, peer: ContactDTO, text: String, timestamp: UInt) {
        f.pollFrame(Bytes.of(0x10, 20, 0, 0) + peer.publicKey.prefix(6) +
            Bytes.of(0, 0) + le32(timestamp.toLong()) + Bytes.utf8(text))
    }
    private suspend fun room(f: RxIngestionFixture, room: RemoteNodeSessionDTO, author: Bytes, text: String, timestamp: UInt) {
        f.pollFrame(Bytes.of(0x10, 20, 0, 0) + room.publicKey.prefix(6) +
            Bytes.of(0, 2) + le32(timestamp.toLong()) + author.prefix(4) + Bytes.utf8(text))
    }

    @Test fun unresolvedChannelReceivesRemainStoredWithoutOriginProofOrScopedFiltering() = runTest {
        withFixture { f ->
            store.withChannelOperation { scoped, _ -> scoped.beginChannelMutation(f.radio, 0u) }
            assertNull(assertNotNull(store.fetchChannel(f.radio, 0u)).occupantID)
            f.poll(stamp, "Peer: retained without rules")
            enable(TextRuleKind.PUBLIC_CHANNEL)
            f.poll(stamp + 1u, "Peer: filter while unresolved")
            val messages = store.fetchMessages(f.radio, 0u)
            assertEquals(setOf("retained without rules", "filter while unresolved"), messages.map { it.text }.toSet())
            assertTrue(messages.all { it.channelOccupantID == null })
            assertTrue(store.recentlyBlockedTexts().isEmpty())
        }
    }

    @Test fun archiveFailuresRetainChannelDirectAndRoomFramesUntilStorageRecovers() = runTest {
        withFixture { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Peer", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            val server = ContactDTO(radioId = f.radio, publicKey = key(0xCC), name = "Room",
                typeRawValue = 3u, lastHeardTimestamp = 0u)
            store.saveContact(server)
            val session = RemoteNodeSessionDTO(radioId = f.radio, publicKey = server.publicKey,
                name = "Room", role = RemoteNodeRole.ROOM_SERVER)
            store.saveRemoteNodeSessionDTO(session)
            val roomKey = EntityKey(f.radio, session.id)
            val deliveries: List<Pair<TextRuleKind, suspend () -> Unit>> = listOf(
                TextRuleKind.PUBLIC_CHANNEL to suspend { f.poll(stamp, "Peer: filter channel") },
                TextRuleKind.DIRECT_MESSAGE to suspend { direct(f, peer, "filter direct", stamp + 1u) },
                TextRuleKind.ROOM to suspend { room(f, session, key(0xDD), "filter room", stamp + 2u) },
            )
            val notifications = f.harness.delivery.posted.size
            for ((index, delivery) in deliveries.withIndex()) {
                enable(delivery.first)
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_receive_archive BEFORE INSERT ON recently_blocked_texts " +
                        "BEGIN SELECT RAISE(FAIL, 'test receive archive failure'); END")
                try {
                    assertFailsWith<PersistenceStoreException> { delivery.second() }
                    assertEquals(1, f.live.messagePollingService.undeliveredCount)
                    assertEquals(index, store.recentlyBlockedTexts().size)
                    assertTrue(store.fetchMessages(f.radio, 0u).isEmpty())
                    assertTrue(store.fetchMessages(EntityKey(f.radio, peer.id)).isEmpty())
                    assertTrue(store.fetchRoomMessages(roomKey).isEmpty())
                    assertFalse(assertNotNull(store.fetchRemoteNodeSession(roomKey)).isConnected)
                    assertEquals(notifications, f.harness.delivery.posted.size)
                } finally {
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_receive_archive")
                }
                f.live.messagePollingService.pollAllMessages()
                assertEquals(0, f.live.messagePollingService.undeliveredCount)
                assertEquals(index + 1, store.recentlyBlockedTexts().size)
            }
            assertEquals(setOf("filter channel", "filter direct", "filter room"),
                store.recentlyBlockedTexts().map { it.body }.toSet())
            assertEquals(0L, store.getUnreadCount(EntityKey(f.radio, peer.id)))
            assertEquals(0L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)
            assertTrue(assertNotNull(store.fetchRemoteNodeSession(roomKey)).isConnected)
            assertEquals(notifications, f.harness.delivery.posted.size)
        }
    }

    @Test fun channelRulesUseOriginalFieldsBeforeHistoryUnreadAndNotificationsAndReplayStaysBlocked() = runTest {
        withFixture { f ->
            enable(TextRuleKind.PUBLIC_CHANNEL, " FILTER ", TextRuleField.MESSAGE)
            val channel = assertNotNull(store.fetchChannel(f.radio, 0u))
            val before = f.harness.delivery.posted.size
            f.poll(stamp, "Peer:  filter ")
            assertTrue(store.fetchMessages(f.radio, 0u).isEmpty())
            assertEquals(0L, store.getChannelUnreadCount(EntityKey(f.radio, channel.id)))
            assertEquals(before, f.harness.delivery.posted.size)
            val entry = store.recentlyBlockedTexts().single()
            assertEquals(" filter ", entry.body)
            assertEquals("Peer", entry.sender)
            assertEquals("Peer:  filter ", entry.identity.wireText)
            assertNull(store.fetchChannel(f.radio, 0u)?.lastMessageDate)
            store.updateTextRules { it.copy(rules = emptyList()) }
            f.poll(stamp, "Peer:  filter ")
            assertTrue(store.fetchMessages(f.radio, 0u).isEmpty())
            assertEquals(1, store.recentlyBlockedTexts().size)
            f.poll(stamp + 1u, "Peer: kept original")
            assertEquals(listOf("kept original"), store.fetchMessages(f.radio, 0u).map { it.text })
            assertNull(store.fetchMessages(f.radio, 0u).single().channelOccupantID,
                "Rule selection scope must not manufacture RF-origin proof for an incoming message")
            assertEquals(1L, store.getChannelUnreadCount(EntityKey(f.radio, channel.id)))
            assertEquals(before + 1, f.harness.delivery.posted.size)
        }
    }

    @Test fun dmDefaultIsOffThenSenderAndBodyRulesSuppressButAmbiguousSenderDoesNot() = runTest {
        withFixture { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Filter Peer",
                typeRawValue = 1u, lastHeardTimestamp = 0u)
            store.saveContact(peer)
            store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter"))) }
            direct(f, peer, "filter retained by default", stamp)
            assertEquals(1, store.fetchMessages(EntityKey(f.radio, peer.id)).size)
            enable(TextRuleKind.DIRECT_MESSAGE, "filter", TextRuleField.SENDER)
            val before = f.harness.delivery.posted.size
            direct(f, peer, "blocked by sender", stamp + 1u)
            assertEquals(1, store.fetchMessages(EntityKey(f.radio, peer.id)).size)
            assertEquals(1L, store.getUnreadCount(EntityKey(f.radio, peer.id)))
            assertEquals(before, f.harness.delivery.posted.size)
            val collision = peer.copy(id = UUID.randomUUID(),
                publicKey = peer.publicKey.prefix(6) + Bytes(ByteArray(26) { 0x55 }), name = "Other")
            store.saveContact(collision)
            direct(f, peer, "ambiguous sender is retained", stamp + 2u)
            assertEquals(1, store.recentlyBlockedTexts().size)
            enable(TextRuleKind.DIRECT_MESSAGE)
            direct(f, peer, "filter by body despite ambiguity", stamp + 3u)
            assertEquals(2, store.recentlyBlockedTexts().size)
        }
    }

    @Test fun directReplayRequiresTheSameSenderAndExactBodyEvenWhenContentKeysCollide() = runTest {
        withFixture { f ->
            val first = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Unknown A", lastHeardTimestamp = 0u)
            val second = ContactDTO(radioId = f.radio, publicKey = key(0xCD), name = "Unknown B", lastHeardTimestamp = 0u)
            direct(f, first, "same text", stamp)
            direct(f, second, "same text", stamp)
            direct(f, first, "same text", stamp)
            val orphans = db.messages().orphanedDirectMessages(f.radio.value)
            assertEquals(2, orphans.size)
            assertEquals(setOf(first.publicKey.prefix(6), second.publicKey.prefix(6)), orphans.map { it.senderKeyPrefix }.toSet())

            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xEF), name = "Peer", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            val firstBody = "room-message-23870"
            val secondBody = "room-message-35318"
            assertEquals(DeduplicationKey.contentBased(peer.id, null, null, stamp + 1u, firstBody),
                DeduplicationKey.contentBased(peer.id, null, null, stamp + 1u, secondBody))
            direct(f, peer, firstBody, stamp + 1u)
            direct(f, peer, secondBody, stamp + 1u)
            direct(f, peer, secondBody, stamp + 1u)
            assertEquals(setOf(firstBody, secondBody), store.fetchMessages(EntityKey(f.radio, peer.id)).map { it.text }.toSet())
            assertEquals(2L, store.getUnreadCount(EntityKey(f.radio, peer.id)))
        }
    }

    @Test fun directAdmissionUsesCurrentBlockedStateBeforeAndAfterTheTransaction() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val first = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "First", lastHeardTimestamp = 0u)
            val second = ContactDTO(radioId = f.radio, publicKey = key(0xCD), name = "Second", lastHeardTimestamp = 0u)
            store.saveContact(first)
            store.saveContact(second)
            enable(TextRuleKind.DIRECT_MESSAGE)
            val events = mutableListOf<SyncDataEvent.DirectMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.DirectMessageReceived) events += it
                }
            }
            val before = f.harness.delivery.posted.size
            suspend fun block(contact: ContactDTO) {
                assertEquals(ContactLocalUpdateResult.Updated(ContactBlockTransition.BLOCKED),
                    store.updateContactLocalFields(EntityKey(f.radio, contact.id), contact.publicKey,
                        ContactLocalUpdate.Preferences(null, true, null)))
            }
            interleaved.beforeAdmission = { message, captured ->
                if (message.text == "filter while blocked") block(assertNotNull(captured))
            }
            direct(f, first, "filter while blocked", stamp)
            val firstCurrent = assertNotNull(store.fetchContact(EntityKey(f.radio, first.id)))
            assertTrue(firstCurrent.isBlocked)
            assertEquals(0L, firstCurrent.unreadCount)
            assertNotNull(firstCurrent.lastMessageDate)
            assertEquals(listOf("filter while blocked"), store.fetchMessages(EntityKey(f.radio, first.id)).map { it.text })
            assertTrue(store.recentlyBlockedTexts().isEmpty())
            assertEquals(before, f.harness.delivery.posted.size)

            interleaved.beforeAdmission = { _, _ -> }
            var unreadAtCommit: Long? = null
            interleaved.afterAdmission = { message, captured, _ ->
                if (message.text == "blocked after admission") {
                    val current = assertNotNull(captured)
                    unreadAtCommit = store.getUnreadCount(EntityKey(f.radio, current.id))
                    block(current)
                }
            }
            direct(f, second, "blocked after admission", stamp + 1u)
            val secondCurrent = assertNotNull(store.fetchContact(EntityKey(f.radio, second.id)))
            assertTrue(secondCurrent.isBlocked)
            assertEquals(1L, unreadAtCommit, "the message committed before the block")
            assertEquals(0L, secondCurrent.unreadCount, "blocking clears existing unread counts")
            assertEquals(before, f.harness.delivery.posted.size)
            testScheduler.runCurrent()
            assertEquals(2, events.size)
            assertTrue(events.all { it.contact.isBlocked })
            observer.cancel()
        }
    }

    @Test fun replacingContactAfterAdmissionCannotUpdateOrPublishTheReplacement() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Original", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            val oldDate = Instant.ofEpochSecond(100)
            val events = mutableListOf<SyncDataEvent.DirectMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.DirectMessageReceived) events += it
                }
            }
            interleaved.afterAdmission = { _, captured, result ->
                if (result == TextRuleAdmission.SAVED) {
                    val old = assertNotNull(captured)
                    store.deleteContact(EntityKey(f.radio, old.id))
                    store.saveContact(old.copy(name = "Replacement", lastMessageDate = oldDate,
                        lastHeardTimestamp = 5u, unreadCount = 17, unreadMentionCount = 8))
                }
            }
            val before = f.harness.delivery.posted.size
            direct(f, peer, "before replacement", stamp)
            val replacement = assertNotNull(store.fetchContact(EntityKey(f.radio, peer.id)))
            assertEquals("Replacement", replacement.name)
            assertEquals(oldDate, replacement.lastMessageDate)
            assertEquals(5u, replacement.lastHeardTimestamp)
            assertEquals(17L, replacement.unreadCount)
            assertEquals(8L, replacement.unreadMentionCount)
            assertTrue(store.fetchMessages(EntityKey(f.radio, peer.id)).isEmpty())
            assertEquals(before, f.harness.delivery.posted.size)
            testScheduler.runCurrent()
            assertTrue(events.isEmpty())
            observer.cancel()
        }
    }

    @Test fun directBookkeepingFailureRollsBackAdmissionAndTheRetainedFrameRetries() = runTest {
        withFixture { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Peer", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            val before = f.harness.delivery.posted.size
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_direct_bookkeeping BEFORE UPDATE ON contacts " +
                    "BEGIN SELECT RAISE(FAIL, 'test contact bookkeeping failure'); END")
            try {
                assertFailsWith<PersistenceStoreException> { direct(f, peer, "retry after rollback", stamp) }
                assertEquals(1, f.live.messagePollingService.undeliveredCount)
                assertTrue(store.fetchMessages(EntityKey(f.radio, peer.id)).isEmpty())
                assertEquals(0L, store.getUnreadCount(EntityKey(f.radio, peer.id)))
                assertEquals(before, f.harness.delivery.posted.size)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_direct_bookkeeping")
            }
            f.live.messagePollingService.pollAllMessages()
            assertEquals(0, f.live.messagePollingService.undeliveredCount)
            assertEquals(listOf("retry after rollback"), store.fetchMessages(EntityKey(f.radio, peer.id)).map { it.text })
            assertEquals(1L, store.getUnreadCount(EntityKey(f.radio, peer.id)))
            assertEquals(before + 1, f.harness.delivery.posted.size)
        }
    }

    @Test fun directPostCommitValidationFailuresResumeTheSameMessageExactlyOnce() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Peer", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            val key = EntityKey(f.radio, peer.id)
            val events = mutableListOf<SyncDataEvent.DirectMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.DirectMessageReceived) events += it
                }
            }
            for (failedRead in 1..2) {
                var reads = 0
                interleaved.beforeDirectValidation = { _, _, _ ->
                    if (++reads == failedRead) throw PersistenceStoreException(PersistenceStoreError.FetchFailed("test validation"))
                }
                val before = f.harness.delivery.posted.size
                val contactsVersion = f.live.syncCoordinator.contactsVersion
                val text = "retry validation $failedRead"
                val timestamp = stamp + failedRead.toUInt()
                assertFailsWith<PersistenceStoreException> { direct(f, peer, text, timestamp) }
                assertEquals(1, f.live.messagePollingService.undeliveredCount)
                val committed = store.fetchMessages(key).single { it.text == text }
                assertEquals(failedRead.toLong(), store.getUnreadCount(key))
                assertEquals(before, f.harness.delivery.posted.size)
                f.live.messagePollingService.pollAllMessages()
                assertEquals(0, f.live.messagePollingService.undeliveredCount)
                assertEquals(1, store.fetchMessages(key).count { it.text == text })
                assertEquals(failedRead.toLong(), store.getUnreadCount(key))
                assertEquals(before + 1, f.harness.delivery.posted.size)
                assertEquals(contactsVersion + 1, f.live.syncCoordinator.contactsVersion)
                testScheduler.runCurrent()
                assertEquals(1, events.count { it.message.id == committed.id })
                direct(f, peer, text, timestamp)
                assertEquals(before + 1, f.harness.delivery.posted.size)
                assertEquals(contactsVersion + 1, f.live.syncCoordinator.contactsVersion)
                assertEquals(1, store.fetchMessages(key).count { it.text == text })
            }
            observer.cancel()
        }
    }

    @Test fun replacedOrChangedDirectMessageIsNotIndexedOrPublishedAfterReadFailure() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Peer", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            val contactKey = EntityKey(f.radio, peer.id)
            val events = mutableListOf<SyncDataEvent.DirectMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.DirectMessageReceived) events += it
                }
            }
            for ((index, variant) in listOf("identical", "different", "in-place").withIndex()) {
                var failed = false
                interleaved.beforeDirectValidation = { _, _, _ ->
                    if (!failed) {
                        failed = true
                        throw PersistenceStoreException(PersistenceStoreError.FetchFailed("test direct message read"))
                    }
                }
                val text = "original $variant"
                val timestamp = stamp + index.toUInt()
                val before = f.harness.delivery.posted.size
                assertFailsWith<PersistenceStoreException> { direct(f, peer, text, timestamp) }
                val original = store.fetchMessages(contactKey).single { it.text == text }
                val messageKey = EntityKey(f.radio, original.id)
                val originalIncarnation = assertNotNull(db.directAcks().incarnation(f.radio.value, original.id, "message"))
                if (variant != "in-place") store.deleteMessage(messageKey)
                val replacement = if (variant == "identical") original else original.copy(
                    text = "replacement $variant", senderKeyPrefix = key(0xCD).prefix(6),
                    timestamp = original.timestamp + 1u,
                )
                store.saveMessage(replacement)
                val replacementIncarnation = assertNotNull(db.directAcks().incarnation(f.radio.value, original.id, "message"))
                if (variant == "in-place") {
                    assertEquals(originalIncarnation.incarnation, replacementIncarnation.incarnation)
                } else {
                    assertNotEquals(originalIncarnation.incarnation, replacementIncarnation.incarnation)
                }
                assertEquals(replacement, store.fetchMessage(messageKey))
                val contactsVersion = f.live.syncCoordinator.contactsVersion
                f.live.messagePollingService.pollAllMessages()
                assertEquals(0, f.live.messagePollingService.undeliveredCount)
                assertEquals(before, f.harness.delivery.posted.size)
                assertEquals(contactsVersion, f.live.syncCoordinator.contactsVersion)
                assertNull(f.live.reactionService.findDMTargetMessage(ReactionParser.generateMessageHash(text, timestamp), peer.id))
                testScheduler.runCurrent()
                assertTrue(events.isEmpty())
            }
            observer.cancel()
        }
    }

    @Test fun cancelledAfterDirectNotificationDoesNotPostItTwice() = runTest {
        val delivery = object : RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED) {
            var cancelAfterPost = false
            override suspend fun post(request: NotificationRequest): NotificationPostResult {
                val result = super.post(request)
                if (cancelAfterPost) {
                    cancelAfterPost = false
                    currentCoroutineContext()[Job]!!.cancel(CancellationException("test cancellation after notification"))
                }
                return result
            }
        }
        withFixture(delivery = delivery) { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Peer", lastHeardTimestamp = 0u)
            store.saveContact(peer)
            val contactKey = EntityKey(f.radio, peer.id)
            val events = mutableListOf<SyncDataEvent.DirectMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.DirectMessageReceived) events += it
                }
            }
            val before = delivery.posted.size
            delivery.cancelAfterPost = true
            val interrupted = async { direct(f, peer, "already notified", stamp) }
            assertFailsWith<CancellationException> { interrupted.await() }
            assertEquals(before + 1, delivery.posted.size)
            val committed = store.fetchMessages(contactKey).single()
            f.live.messagePollingService.pollAllMessages()
            assertEquals(0, f.live.messagePollingService.undeliveredCount)
            assertEquals(before + 1, delivery.posted.size)
            assertEquals(1L, store.getUnreadCount(contactKey))
            testScheduler.runCurrent()
            assertEquals(listOf(committed.id), events.map { it.message.id })
            observer.cancel()
        }
    }

    @Test fun replacedOrphanDirectMessageDoesNotPublishAfterACommittedFailure() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val unknown = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Unknown", lastHeardTimestamp = 0u)
            var committed: MessageDTO? = null
            interleaved.afterAdmission = { message, contact, result ->
                if (contact == null && result == TextRuleAdmission.SAVED && committed == null) {
                    committed = message
                    throw PersistenceStoreException(PersistenceStoreError.FetchFailed("test post-commit failure"))
                }
            }
            assertFailsWith<PersistenceStoreException> { direct(f, unknown, "orphan", stamp) }
            val original = assertNotNull(committed)
            assertNull(original.contactID)
            val messageKey = EntityKey(f.radio, original.id)
            store.deleteMessage(messageKey)
            store.saveMessage(original)
            val conversationsVersion = f.live.syncCoordinator.conversationsVersion
            f.live.messagePollingService.pollAllMessages()
            assertEquals(0, f.live.messagePollingService.undeliveredCount)
            assertEquals(conversationsVersion, f.live.syncCoordinator.conversationsVersion)
            assertEquals(original, store.fetchMessage(messageKey))
        }
    }

    @Test fun cancelledAfterBlockedDirectCommitKeepsItsOriginalNotificationDecision() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val peer = ContactDTO(radioId = f.radio, publicKey = key(0xAB), name = "Peer",
                lastHeardTimestamp = 0u, isBlocked = true)
            store.saveContact(peer)
            val key = EntityKey(f.radio, peer.id)
            var interrupted = false
            interleaved.afterAdmission = { _, captured, result ->
                if (!interrupted && result == TextRuleAdmission.SAVED_BLOCKED) {
                    interrupted = true
                    assertEquals(ContactLocalUpdateResult.Updated(ContactBlockTransition.UNBLOCKED),
                        store.updateContactLocalFields(key, assertNotNull(captured).publicKey,
                            ContactLocalUpdate.Preferences(null, false, null)))
                    throw CancellationException("test cancellation after commit")
                }
            }
            val events = mutableListOf<SyncDataEvent.DirectMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.DirectMessageReceived) events += it
                }
            }
            val before = f.harness.delivery.posted.size
            assertFailsWith<CancellationException> { direct(f, peer, "blocked at commit", stamp) }
            val committed = store.fetchMessages(key).single()
            f.live.messagePollingService.pollAllMessages()
            assertEquals(1, store.fetchMessages(key).size)
            assertEquals(0L, store.getUnreadCount(key))
            assertEquals(before, f.harness.delivery.posted.size)
            testScheduler.runCurrent()
            assertEquals(listOf(committed.id), events.map { it.message.id })
            observer.cancel()
        }
    }

    @Test fun channelBookkeepingFailureRollsBackAdmissionAndTheRetainedFrameRetries() = runTest {
        withFixture { f ->
            val channel = assertNotNull(store.fetchChannel(f.radio, 0u))
            val before = f.harness.delivery.posted.size
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_channel_bookkeeping BEFORE UPDATE ON channels " +
                    "BEGIN SELECT RAISE(FAIL, 'test channel bookkeeping failure'); END")
            try {
                assertFailsWith<PersistenceStoreException> { f.poll(stamp, "Peer: retry channel") }
                assertEquals(1, f.live.messagePollingService.undeliveredCount)
                assertTrue(store.fetchMessages(f.radio, 0u).isEmpty())
                assertEquals(channel.lastMessageDate, store.fetchChannel(f.radio, 0u)?.lastMessageDate)
                assertEquals(channel.unreadCount, store.getChannelUnreadCount(EntityKey(f.radio, channel.id)))
                assertEquals(before, f.harness.delivery.posted.size)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_channel_bookkeeping")
            }
            f.live.messagePollingService.pollAllMessages()
            assertEquals(0, f.live.messagePollingService.undeliveredCount)
            assertEquals(listOf("retry channel"), store.fetchMessages(f.radio, 0u).map { it.text })
            assertNotNull(store.fetchChannel(f.radio, 0u)?.lastMessageDate)
            assertEquals(channel.unreadCount + 1, store.getChannelUnreadCount(EntityKey(f.radio, channel.id)))
            assertEquals(before + 1, f.harness.delivery.posted.size)
        }
    }

    @Test fun channelPostCommitReadsRetainTheOriginalDeliveryAndPublishOnce() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val channel = assertNotNull(store.fetchChannel(f.radio, 0u))
            val key = EntityKey(f.radio, channel.id)
            val events = mutableListOf<SyncDataEvent.ChannelMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.ChannelMessageReceived) events += it
                }
            }
            for (stage in 1..2) {
                var armed = false
                var failed = false
                var admissions = 0
                val text = "retry channel read $stage"
                interleaved.afterAdmission = { message, _, result ->
                    if (message.text == text && result == TextRuleAdmission.SAVED) {
                        admissions++
                        armed = true
                    }
                }
                interleaved.beforeChannelMatch = {
                    if (stage == 1 && armed && !failed) {
                        failed = true
                        throw PersistenceStoreException(PersistenceStoreError.FetchFailed("test channel message read"))
                    }
                }
                interleaved.beforeChannelRead = { _, _ ->
                    if (stage == 2 && armed && !failed) {
                        failed = true
                        throw PersistenceStoreException(PersistenceStoreError.FetchFailed("test channel validation"))
                    }
                }
                val before = f.harness.delivery.posted.size
                val conversationsVersion = f.live.syncCoordinator.conversationsVersion
                val timestamp = stamp + stage.toUInt()
                assertFailsWith<PersistenceStoreException> { f.poll(timestamp, "Peer: $text") }
                assertEquals(1, f.live.messagePollingService.undeliveredCount)
                val committed = store.fetchMessages(f.radio, 0u).single { it.text == text }
                assertEquals(channel.unreadCount + stage, store.getChannelUnreadCount(key))
                assertEquals(before, f.harness.delivery.posted.size)
                f.live.messagePollingService.pollAllMessages()
                assertEquals(0, f.live.messagePollingService.undeliveredCount)
                assertEquals(1, admissions)
                assertEquals(channel.unreadCount + stage, store.getChannelUnreadCount(key))
                assertEquals(before + 1, f.harness.delivery.posted.size)
                assertEquals(conversationsVersion + 1, f.live.syncCoordinator.conversationsVersion)
                testScheduler.runCurrent()
                assertEquals(1, events.count { it.message.id == committed.id })
                f.poll(timestamp, "Peer: $text")
                assertEquals(before + 1, f.harness.delivery.posted.size)
                assertEquals(1, store.fetchMessages(f.radio, 0u).count { it.text == text })
            }
            observer.cancel()
        }
    }

    @Test fun cancelledChannelCommitResumesWithoutReadmittingOrNotifyingAReplacement() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val channel = assertNotNull(store.fetchChannel(f.radio, 0u))
            val key = EntityKey(f.radio, channel.id)
            val events = mutableListOf<SyncDataEvent.ChannelMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.ChannelMessageReceived) events += it
                }
            }
            var admissions = 0
            interleaved.afterAdmission = { _, _, result ->
                if (result == TextRuleAdmission.SAVED) {
                    admissions++
                    throw CancellationException("test cancellation after channel commit")
                }
            }
            val before = f.harness.delivery.posted.size
            assertFailsWith<CancellationException> { f.poll(stamp, "Peer: retained channel") }
            val committed = store.fetchMessages(f.radio, 0u).single()
            f.live.messagePollingService.pollAllMessages()
            assertEquals(1, admissions)
            assertEquals(channel.unreadCount + 1, store.getChannelUnreadCount(key))
            assertEquals(before + 1, f.harness.delivery.posted.size)
            testScheduler.runCurrent()
            assertEquals(listOf(committed.id), events.map { it.message.id })

            assertFailsWith<CancellationException> { f.poll(stamp + 1u, "Peer: stale channel") }
            store.withChannelOperation { scoped, _ -> scoped.beginChannelMutation(f.radio, 0u) }
            f.live.messagePollingService.pollAllMessages()
            assertEquals(2, admissions)
            assertEquals(before + 1, f.harness.delivery.posted.size)
            testScheduler.runCurrent()
            assertEquals(listOf(committed.id), events.map { it.message.id })
            observer.cancel()
        }
    }

    @Test fun cancelledAfterChannelNotificationDoesNotPostItTwice() = runTest {
        val delivery = object : RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED) {
            var cancelAfterPost = false
            override suspend fun post(request: NotificationRequest): NotificationPostResult {
                val result = super.post(request)
                if (cancelAfterPost) {
                    cancelAfterPost = false
                    currentCoroutineContext()[Job]!!.cancel(CancellationException("test cancellation after notification"))
                }
                return result
            }
        }
        withFixture(delivery = delivery) { f ->
            val channel = assertNotNull(store.fetchChannel(f.radio, 0u))
            val key = EntityKey(f.radio, channel.id)
            val events = mutableListOf<SyncDataEvent.ChannelMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.ChannelMessageReceived) events += it
                }
            }
            val before = delivery.posted.size
            delivery.cancelAfterPost = true
            val interrupted = async { f.poll(stamp, "Peer: already notified") }
            assertFailsWith<CancellationException> { interrupted.await() }
            assertEquals(before + 1, delivery.posted.size)
            val committed = store.fetchMessages(f.radio, 0u).single()
            f.live.messagePollingService.pollAllMessages()
            assertEquals(before + 1, delivery.posted.size)
            assertEquals(channel.unreadCount + 1, store.getChannelUnreadCount(key))
            testScheduler.runCurrent()
            assertEquals(listOf(committed.id), events.map { it.message.id })
            observer.cancel()
        }
    }

    @Test fun identicalChannelReplacementIsNotPublishedWhenACommittedReadRetries() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            var failed = false
            interleaved.beforeChannelMatch = {
                if (!failed) {
                    failed = true
                    throw PersistenceStoreException(PersistenceStoreError.FetchFailed("test channel message read"))
                }
            }
            val events = mutableListOf<SyncDataEvent.ChannelMessageReceived>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.ChannelMessageReceived) events += it
                }
            }
            val before = f.harness.delivery.posted.size
            assertFailsWith<PersistenceStoreException> { f.poll(stamp, "Peer: original channel message") }
            val original = store.fetchMessages(f.radio, 0u).single()
            val key = EntityKey(f.radio, original.id)
            val originalIncarnation = assertNotNull(store.fetchChannelReactionMatch(key)?.channelReactionIncarnation)
            store.deleteMessage(key)
            store.saveMessage(original)
            assertEquals(original, store.fetchMessage(key))
            assertNotEquals(originalIncarnation, store.fetchChannelReactionMatch(key)?.channelReactionIncarnation)
            f.live.messagePollingService.pollAllMessages()
            assertEquals(listOf("original channel message"), store.fetchMessages(f.radio, 0u).map { it.text })
            assertEquals(before, f.harness.delivery.posted.size)
            testScheduler.runCurrent()
            assertTrue(events.isEmpty())
            observer.cancel()
        }
    }

    @Test fun activeChannelDoesNotAccumulateUnreadDuringAtomicAdmission() = runTest {
        withFixture { f ->
            val channel = assertNotNull(store.fetchChannel(f.radio, 0u))
            f.live.notificationService.setActiveConversation(
                channelIndex = channel.index, channelRadioId = f.radio,
                channelOccupantId = assertNotNull(channel.occupantID),
            )
            f.poll(stamp, "Peer: viewing")
            assertNotNull(store.fetchChannel(f.radio, 0u)?.lastMessageDate)
            assertEquals(channel.unreadCount, store.getChannelUnreadCount(EntityKey(f.radio, channel.id)))
            f.live.notificationService.setActiveConversation()
            f.poll(stamp + 1u, "Peer: no longer viewing")
            assertEquals(channel.unreadCount + 1, store.getChannelUnreadCount(EntityKey(f.radio, channel.id)))
        }
    }

    @Test fun unresolvedChannelAfterCaptureRetainsHistoryWithoutUpdatingOrNotifyingTheSlot() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val channel = assertNotNull(store.fetchChannel(f.radio, 0u))
            assertNotNull(channel.occupantID)
            val before = f.harness.delivery.posted.size
            interleaved.beforeChannelAdmission = { _, captured ->
                if (captured != null) store.withChannelOperation { scoped, _ ->
                    scoped.beginChannelMutation(f.radio, 0u)
                }
            }
            f.poll(stamp, "Peer: unresolved after capture")
            val current = assertNotNull(store.fetchChannel(f.radio, 0u))
            assertNull(current.occupantID)
            assertEquals(channel.lastMessageDate, current.lastMessageDate)
            assertEquals(channel.unreadCount, current.unreadCount)
            assertEquals(listOf("unresolved after capture"), store.fetchMessages(f.radio, 0u).map { it.text })
            assertEquals(before, f.harness.delivery.posted.size)
        }
    }

    @Test fun changedChannelSecretDoesNotApplyTheCapturedChannelRule() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            enable(TextRuleKind.PUBLIC_CHANNEL)
            val channel = assertNotNull(store.fetchChannel(f.radio, 0u))
            val before = f.harness.delivery.posted.size
            interleaved.beforeChannelAdmission = { _, captured ->
                if (captured != null) db.openHelper.writableDatabase.execSQL(
                    "UPDATE channels SET secret = zeroblob(16) WHERE `index` = 0")
            }
            f.poll(stamp, "Peer: filter after secret change")
            assertEquals(channel.occupantID, store.fetchChannel(f.radio, 0u)?.occupantID)
            assertEquals(listOf("filter after secret change"), store.fetchMessages(f.radio, 0u).map { it.text })
            assertTrue(store.recentlyBlockedTexts().isEmpty())
            assertEquals(channel.unreadCount, store.getChannelUnreadCount(EntityKey(f.radio, channel.id)))
            assertEquals(before, f.harness.delivery.posted.size)
        }
    }

    @Test fun channelChangingAfterAdmissionDoesNotNotifyItsNewOccupant() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val before = f.harness.delivery.posted.size
            interleaved.afterAdmission = { _, _, result ->
                if (result == TextRuleAdmission.SAVED) store.withChannelOperation { scoped, _ ->
                    scoped.beginChannelMutation(f.radio, 0u)
                }
            }
            f.poll(stamp, "Peer: changed after commit")
            assertEquals(listOf("changed after commit"), store.fetchMessages(f.radio, 0u).map { it.text })
            assertNull(store.fetchChannel(f.radio, 0u)?.occupantID)
            assertEquals(before, f.harness.delivery.posted.size)
        }
    }

    @Test fun roomReplayRequiresExactBodyEvenWhenTheShortHashCollides() = runTest {
        withFixture { f ->
            val server = ContactDTO(radioId = f.radio, publicKey = key(0xCC), name = "Room",
                typeRawValue = 3u, lastHeardTimestamp = 0u)
            store.saveContact(server)
            val session = RemoteNodeSessionDTO(radioId = f.radio, publicKey = server.publicKey,
                name = "Room", role = RemoteNodeRole.ROOM_SERVER)
            store.saveRemoteNodeSessionDTO(session)
            val roomKey = EntityKey(f.radio, session.id)
            val author = key(0xAB)
            val firstBody = "room-message-23870"
            val secondBody = "room-message-35318"
            assertEquals(RoomMessageDTO.generateDeduplicationKey(stamp, author.prefix(4), firstBody),
                RoomMessageDTO.generateDeduplicationKey(stamp, author.prefix(4), secondBody))
            room(f, session, author, firstBody, stamp)
            room(f, session, author, secondBody, stamp)
            room(f, session, author, firstBody, stamp)
            assertEquals(setOf(firstBody, secondBody), store.fetchRoomMessages(roomKey).map { it.text }.toSet())
            assertEquals(2L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)

            enable(TextRuleKind.ROOM, "35318")
            room(f, session, author, firstBody, stamp + 1u)
            room(f, session, author, secondBody, stamp + 1u)
            room(f, session, author, secondBody, stamp + 1u)
            assertEquals(3, store.fetchRoomMessages(roomKey).size)
            assertEquals(3L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)
            assertEquals(listOf(secondBody), store.recentlyBlockedTexts().map { it.body })
        }
    }

    @Test fun replacingRoomAfterAdmissionCannotPublishIntoTheReplacement() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val server = ContactDTO(radioId = f.radio, publicKey = key(0xCC), name = "Room",
                typeRawValue = 3u, lastHeardTimestamp = 0u)
            store.saveContact(server)
            val original = RemoteNodeSessionDTO(radioId = f.radio, publicKey = server.publicKey,
                name = "Original", role = RemoteNodeRole.ROOM_SERVER)
            store.saveRemoteNodeSessionDTO(original)
            val roomKey = EntityKey(f.radio, original.id)
            val roomEvents = mutableListOf<RoomServerEvent>()
            val syncEvents = mutableListOf<SyncDataEvent.RoomMessageReceived>()
            val roomObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.roomServerService.events().collect { roomEvents += it }
            }
            val syncObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.RoomMessageReceived) syncEvents += it
                }
            }
            val replacement = original.copy(name = "Replacement", unreadCount = 17, lastSyncTimestamp = 42u)
            interleaved.afterRoomAdmission = { _, _, result ->
                if (result.saved) {
                    store.deleteRemoteNodeSession(roomKey)
                    store.saveRemoteNodeSessionDTO(replacement)
                }
            }
            val before = f.harness.delivery.posted.size
            room(f, original, key(0xAB), "before replacement", stamp)
            val current = assertNotNull(store.fetchRemoteNodeSession(roomKey))
            assertEquals("Replacement", current.name)
            assertEquals(17L, current.unreadCount)
            assertEquals(42u, current.lastSyncTimestamp)
            assertFalse(current.isConnected)
            assertTrue(store.fetchRoomMessages(roomKey).isEmpty())
            assertEquals(before, f.harness.delivery.posted.size)
            testScheduler.runCurrent()
            assertTrue(roomEvents.isEmpty())
            assertTrue(syncEvents.isEmpty())
            roomObserver.cancel()
            syncObserver.cancel()
        }
    }

    @Test fun failedRoomPreparationOrAdmissionCannotRetargetAnIdenticallyRestoredRoom() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val server = ContactDTO(radioId = f.radio, publicKey = key(0xCC), name = "Room",
                typeRawValue = 3u, lastHeardTimestamp = 0u)
            store.saveContact(server)
            val roomEvents = mutableListOf<RoomServerEvent>()
            val syncEvents = mutableListOf<SyncDataEvent.RoomMessageReceived>()
            val roomObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.roomServerService.events().collect { roomEvents += it }
            }
            val syncObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.RoomMessageReceived) syncEvents += it
                }
            }
            val before = f.harness.delivery.posted.size
            var replacement: RemoteNodeSessionDTO? = null
            for ((index, failure) in listOf("message", "archive", "name").withIndex()) {
                val original = RemoteNodeSessionDTO(radioId = f.radio, publicKey = server.publicKey,
                    name = "Original", role = RemoteNodeRole.ROOM_SERVER)
                val roomKey = EntityKey(f.radio, original.id)
                store.saveRemoteNodeSessionDTO(original)
                val originalWitness = assertNotNull(store.fetchRemoteNodeSession(roomKey)).receiveConversationID
                store.updateTextRules {
                    it.copy(rules = if (failure == "archive") listOf(TextRule(phrase = "filter")) else emptyList(),
                        selections = it.selections + (TextRuleKind.ROOM to TextRuleSelection(TextRuleSelectionMode.ALL)))
                }
                if (failure == "name") {
                    var failOnce = true
                    interleaved.beforeRoomNameLookup = {
                        if (failOnce) {
                            failOnce = false
                            throw PersistenceStoreException(PersistenceStoreError.FetchFailed("test room author lookup"))
                        }
                    }
                } else {
                    val table = if (failure == "archive") "recently_blocked_texts" else "room_messages"
                    db.openHelper.writableDatabase.execSQL(
                        "CREATE TRIGGER reject_room_admission BEFORE INSERT ON $table " +
                            "BEGIN SELECT RAISE(FAIL, 'test room admission failure'); END")
                }
                try {
                    assertFailsWith<PersistenceStoreException> {
                        room(f, original, key(0xAB), "filter old $failure", stamp + index.toUInt())
                    }
                    assertEquals(1, f.live.messagePollingService.undeliveredCount)
                    assertTrue(store.fetchRoomMessages(roomKey).isEmpty())
                    assertTrue(store.recentlyBlockedTexts().isEmpty())
                } finally {
                    if (failure != "name") db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_room_admission")
                }
                store.deleteRemoteNodeSession(roomKey)
                val restored = original.copy(name = "Replacement", unreadCount = 17, lastSyncTimestamp = 42u,
                    lastMessageDate = Instant.ofEpochSecond(100))
                replacement = restored
                store.saveRemoteNodeSessionDTO(restored)
                assertNotEquals(originalWitness, assertNotNull(store.fetchRemoteNodeSession(roomKey)).receiveConversationID)
                f.live.messagePollingService.pollAllMessages()
                assertEquals(0, f.live.messagePollingService.undeliveredCount)
                val current = assertNotNull(store.fetchRemoteNodeSession(roomKey))
                assertEquals("Replacement", current.name)
                assertEquals(17L, current.unreadCount)
                assertEquals(42u, current.lastSyncTimestamp)
                assertEquals(Instant.ofEpochSecond(100), current.lastMessageDate)
                assertFalse(current.isConnected)
                assertTrue(store.fetchRoomMessages(roomKey).isEmpty())
                assertTrue(store.recentlyBlockedTexts().isEmpty())
                assertEquals(before, f.harness.delivery.posted.size)
                testScheduler.runCurrent()
                assertTrue(roomEvents.isEmpty())
                assertTrue(syncEvents.isEmpty())
                if (index != 2) store.deleteRemoteNodeSession(roomKey)
            }
            store.updateTextRules { it.copy(rules = emptyList()) }
            val current = assertNotNull(replacement)
            room(f, current, key(0xAB), "fresh room message", stamp + 20u)
            assertEquals(listOf("fresh room message"),
                store.fetchRoomMessages(EntityKey(f.radio, current.id)).map { it.text })
            assertEquals(before + 1, f.harness.delivery.posted.size)
            roomObserver.cancel()
            syncObserver.cancel()
        }
    }

    @Test fun roomAdmissionRollbackRetriesTheOriginalPreparedMessage() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val server = ContactDTO(radioId = f.radio, publicKey = key(0xCC), name = "Room",
                typeRawValue = 3u, lastHeardTimestamp = 0u)
            store.saveContact(server)
            val session = RemoteNodeSessionDTO(radioId = f.radio, publicKey = server.publicKey,
                name = "Room", role = RemoteNodeRole.ROOM_SERVER)
            store.saveRemoteNodeSessionDTO(session)
            val attempted = mutableListOf<UUID>()
            interleaved.beforeRoomAdmission = { message, _ -> attempted += message.id }
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_room_admission BEFORE INSERT ON room_messages " +
                    "BEGIN SELECT RAISE(FAIL, 'test room admission failure'); END")
            try {
                assertFailsWith<PersistenceStoreException> { room(f, session, key(0xAB), "retry room", stamp) }
                assertEquals(1, f.live.messagePollingService.undeliveredCount)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_room_admission")
            }
            val before = f.harness.delivery.posted.size
            f.live.messagePollingService.pollAllMessages()
            assertEquals(0, f.live.messagePollingService.undeliveredCount)
            assertEquals(2, attempted.size)
            assertEquals(attempted.first(), attempted.last())
            assertEquals(listOf(attempted.first()),
                store.fetchRoomMessages(EntityKey(f.radio, session.id)).map { it.id })
            assertEquals(1L, store.fetchRemoteNodeSession(EntityKey(f.radio, session.id))?.unreadCount)
            assertEquals(before + 1, f.harness.delivery.posted.size)
        }
    }

    @Test fun roomValidationFailureRetainsTheCommittedReceiveForRetry() = runTest {
        val interleaved = InterleavedTextRuleStore(store)
        withFixture(interleaved) { f ->
            val server = ContactDTO(radioId = f.radio, publicKey = key(0xCC), name = "Room",
                typeRawValue = 3u, lastHeardTimestamp = 0u)
            store.saveContact(server)
            val session = RemoteNodeSessionDTO(radioId = f.radio, publicKey = server.publicKey,
                name = "Room", role = RemoteNodeRole.ROOM_SERVER)
            store.saveRemoteNodeSessionDTO(session)
            val roomKey = EntityKey(f.radio, session.id)
            val roomEvents = mutableListOf<RoomServerEvent>()
            val syncEvents = mutableListOf<SyncDataEvent.RoomMessageReceived>()
            val roomObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.roomServerService.events().collect { roomEvents += it }
            }
            val syncObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.RoomMessageReceived) syncEvents += it
                }
            }
            var failed = false
            interleaved.beforeRoomValidation = { _, _ ->
                if (!failed) {
                    failed = true
                    throw PersistenceStoreException(PersistenceStoreError.FetchFailed("test room validation"))
                }
            }
            val before = f.harness.delivery.posted.size
            assertFailsWith<PersistenceStoreException> { room(f, session, key(0xAB), "retry room", stamp) }
            assertEquals(1, f.live.messagePollingService.undeliveredCount)
            val committed = store.fetchRoomMessages(roomKey).single()
            assertEquals(1L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)
            assertEquals(before, f.harness.delivery.posted.size)
            f.live.messagePollingService.pollAllMessages()
            assertEquals(0, f.live.messagePollingService.undeliveredCount)
            assertEquals(listOf(committed.id), store.fetchRoomMessages(roomKey).map { it.id })
            assertEquals(1L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)
            assertEquals(before + 1, f.harness.delivery.posted.size)
            testScheduler.runCurrent()
            assertEquals(listOf<RoomServerEvent>(RoomServerEvent.ConnectionRecovered(roomKey)), roomEvents)
            assertEquals(listOf(committed.id), syncEvents.map { it.message.id })
            room(f, session, key(0xAB), "retry room", stamp)
            assertEquals(before + 1, f.harness.delivery.posted.size)
            roomObserver.cancel()
            syncObserver.cancel()
        }
    }

    @Test fun cancelledAfterRoomNotificationDoesNotPostItTwice() = runTest {
        val delivery = object : RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED) {
            var cancelAfterPost = false
            override suspend fun post(request: NotificationRequest): NotificationPostResult {
                val result = super.post(request)
                if (cancelAfterPost) {
                    cancelAfterPost = false
                    currentCoroutineContext()[Job]!!.cancel(CancellationException("test cancellation after notification"))
                }
                return result
            }
        }
        withFixture(delivery = delivery) { f ->
            val server = ContactDTO(radioId = f.radio, publicKey = key(0xCC), name = "Room",
                typeRawValue = 3u, lastHeardTimestamp = 0u)
            store.saveContact(server)
            val session = RemoteNodeSessionDTO(radioId = f.radio, publicKey = server.publicKey,
                name = "Room", role = RemoteNodeRole.ROOM_SERVER)
            store.saveRemoteNodeSessionDTO(session)
            val roomKey = EntityKey(f.radio, session.id)
            val roomEvents = mutableListOf<RoomServerEvent>()
            val syncEvents = mutableListOf<SyncDataEvent.RoomMessageReceived>()
            val roomObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.roomServerService.events().collect { roomEvents += it }
            }
            val syncObserver = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.syncCoordinator.dataEventBroadcaster.subscribe().collect {
                    if (it is SyncDataEvent.RoomMessageReceived) syncEvents += it
                }
            }
            val before = delivery.posted.size
            delivery.cancelAfterPost = true
            val interrupted = async { room(f, session, key(0xAB), "already notified", stamp) }
            assertFailsWith<CancellationException> { interrupted.await() }
            assertEquals(before + 1, delivery.posted.size)
            val committed = store.fetchRoomMessages(roomKey).single()
            f.live.messagePollingService.pollAllMessages()
            assertEquals(0, f.live.messagePollingService.undeliveredCount)
            assertEquals(before + 1, delivery.posted.size)
            assertEquals(1L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)
            testScheduler.runCurrent()
            assertEquals(listOf<RoomServerEvent>(RoomServerEvent.ConnectionRecovered(roomKey)), roomEvents)
            assertEquals(listOf(committed.id), syncEvents.map { it.message.id })
            roomObserver.cancel()
            syncObserver.cancel()
        }
    }

    @Test fun roomRulesPreserveBookmarkAndUnreadWhileRecoveringConnectionAndSelfMessagesAreExempt() = runTest {
        withFixture { f ->
            val server = ContactDTO(radioId = f.radio, publicKey = key(0xCC), name = "Room",
                typeRawValue = 3u, lastHeardTimestamp = 0u)
            store.saveContact(server)
            val session = RemoteNodeSessionDTO(radioId = f.radio, publicKey = server.publicKey,
                name = "Room", role = RemoteNodeRole.ROOM_SERVER, lastSyncTimestamp = stamp - 1u)
            store.saveRemoteNodeSessionDTO(session)
            val roomKey = EntityKey(f.radio, session.id)
            val initial = assertNotNull(store.fetchRemoteNodeSession(roomKey))
            enable(TextRuleKind.ROOM)
            val before = f.harness.delivery.posted.size
            room(f, session, key(0xAB), "filter room text", stamp)
            assertTrue(store.fetchRoomMessages(roomKey).isEmpty())
            val recovered = assertNotNull(store.fetchRemoteNodeSession(roomKey))
            assertTrue(recovered.isConnected)
            assertEquals(session.lastSyncTimestamp, recovered.lastSyncTimestamp)
            assertEquals(initial.lastMessageDate, recovered.lastMessageDate)
            assertNull(db.sessions().byId(f.radio.value, session.id)?.lastMessageDate)
            assertEquals(0L, recovered.unreadCount)
            assertEquals(before, f.harness.delivery.posted.size)
            room(f, session, key(0xAB), "kept room text", stamp + 1u)
            assertEquals(listOf("kept room text"), store.fetchRoomMessages(roomKey).map { it.text })
            assertEquals(1L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)
            room(f, session, key(7), "filter self text", stamp + 2u)
            val messages = store.fetchRoomMessages(roomKey)
            assertEquals(2, messages.size)
            assertTrue(messages.single { it.text == "filter self text" }.isFromSelf)
            assertEquals(1L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)
            assertEquals(1, store.recentlyBlockedTexts().size)
        }
    }

    @Test fun senderRuleSuppressesChannelReactionWithoutArchivingOrMutatingItsTarget() = runTest {
        withFixture { f ->
            f.poll(stamp, "Alice: Hello")
            val target = store.fetchMessages(f.radio, 0u).single()
            enable(TextRuleKind.PUBLIC_CHANNEL, "Bob", TextRuleField.SENDER)
            f.poll(stamp + 1u, "Bob: @[Alice]👍\nb45pc4ek")
            assertTrue(store.fetchReactions(EntityKey(f.radio, target.id)).isEmpty())
            assertTrue(store.recentlyBlockedTexts().isEmpty())
            assertEquals(1, store.fetchMessages(f.radio, 0u).size)
            enable(TextRuleKind.PUBLIC_CHANNEL, "Alice", TextRuleField.MESSAGE)
            f.poll(stamp + 2u, "Carol: @[Alice]👍\nb45pc4ek")
            assertEquals(1, store.fetchReactions(EntityKey(f.radio, target.id)).size)
            assertTrue(store.recentlyBlockedTexts().isEmpty())
        }
    }
}
