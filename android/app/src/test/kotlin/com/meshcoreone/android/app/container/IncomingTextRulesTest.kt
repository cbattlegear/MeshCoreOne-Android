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
            enable(TextRuleKind.ROOM)
            val before = f.harness.delivery.posted.size
            room(f, session, key(0xAB), "filter room text", stamp)
            val roomKey = EntityKey(f.radio, session.id)
            assertTrue(store.fetchRoomMessages(roomKey).isEmpty())
            val recovered = assertNotNull(store.fetchRemoteNodeSession(roomKey))
            assertTrue(recovered.isConnected)
            assertEquals(session.lastSyncTimestamp, recovered.lastSyncTimestamp)
            assertNull(recovered.lastMessageDate)
            assertEquals(0L, recovered.unreadCount)
            assertEquals(before, f.harness.delivery.posted.size)
            room(f, session, key(0xAB), "kept room text", stamp + 1u)
            assertEquals(listOf("kept room text"), store.fetchRoomMessages(roomKey).map { it.text })
            assertEquals(1L, store.fetchRemoteNodeSession(roomKey)?.unreadCount)
            room(f, session, key(7), "filter self text", stamp + 2u)
            assertEquals(2, store.fetchRoomMessages(roomKey).size)
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
