// AndroidOnly: WP-309 Issue #729 real firmware frames, receive services, Room, unread and notification admission.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IncomingTextRulesTest : RoomProcessTest() {
    private val stamp = 1_704_067_200u
    private suspend fun TestScope.withFixture(block: suspend (RxIngestionFixture) -> Unit) {
        val f = RxIngestionFixture(this, store, delivery = RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED))
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
