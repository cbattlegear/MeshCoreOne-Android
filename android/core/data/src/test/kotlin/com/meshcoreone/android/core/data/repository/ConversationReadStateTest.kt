// AndroidOnly: WP-306 Real Room coverage of upstream 122d6af conversation-counter read intent.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ConversationReadStateTest : RepositoryTest() {
    @Test fun readClearsCountersAcrossKindsWithoutChangingMessageOrFailureFlags() = runTest {
        val contact = contact().copy(unreadCount = 4, unreadMentionCount = 2)
        val channel = ChannelDTO(radioId = RADIO_A, index = 1u, name = "Channel", unreadCount = 4, unreadMentionCount = 2)
        val room = session().copy(unreadCount = 4)
        store.saveContact(contact)
        store.saveChannel(channel)
        store.saveRemoteNodeSessionDTO(room)
        val incoming = message(direction = MessageDirection.INCOMING).copy(isRead = false, mentionSeen = false)
        val failed = message(status = MessageStatus.FAILED)
        val channelFailure = message(contactID = null, channelIndex = 1u, status = MessageStatus.FAILED)
        listOf(incoming, failed, channelFailure).forEach { store.saveMessage(it) }
        val roomFailure = RoomMessageDTO(sessionID = room.id, authorKeyPrefix = key().prefix(6), text = "failed", timestamp = 1u,
            isFromSelf = true, statusRawValue = MessageStatus.FAILED.rawValue)
        store.saveRoomMessage(RADIO_A, roomFailure)
        val failures = store.fetchFailedSendConversationKeys(RADIO_A)
        store.setContactReadState(entity(id = contact.id), true)
        store.setChannelReadState(requireNotNull(store.fetchChannel(entity(id = channel.id))), true)
        store.setRoomReadState(entity(id = room.id), true)
        assertEquals(0L, requireNotNull(store.fetchContact(entity())).unreadCount)
        assertEquals(0L, requireNotNull(store.fetchContact(entity())).unreadMentionCount)
        assertEquals(0L, requireNotNull(store.fetchChannel(entity(id = channel.id))).unreadCount)
        assertEquals(0L, requireNotNull(store.fetchChannel(entity(id = channel.id))).unreadMentionCount)
        assertEquals(0L, requireNotNull(store.fetchRemoteNodeSession(entity(id = room.id))).unreadCount)
        listOf(incoming, failed, channelFailure).forEach {
            assertEquals(it, store.fetchMessage(entity(id = it.id)))
        }
        assertEquals(roomFailure, store.fetchRoomMessage(entity(id = roomFailure.id)))
        assertEquals(failures, store.fetchFailedSendConversationKeys(RADIO_A))
        assertEquals(UnreadCounts(0, 0, 0), store.getTotalUnreadCounts(RADIO_A))
    }

    @Test fun unreadIsIdempotentPreservesMentionsAndLaterArrivalsAndNeverCrossesRadios() = runTest {
        val contact = contact()
        val channel = ChannelDTO(radioId = RADIO_A, index = 1u, name = "Channel")
        val room = session()
        for (radio in listOf(RADIO_A, RADIO_B)) {
            store.saveContact(contact.copy(radioId = radio))
            store.saveChannel(channel.copy(radioId = radio))
            store.saveRemoteNodeSessionDTO(room.copy(radioId = radio))
        }
        suspend fun unread() {
            store.setContactReadState(entity(), false)
            store.setChannelReadState(requireNotNull(store.fetchChannel(entity(id = channel.id))), false)
            store.setRoomReadState(entity(id = room.id), false)
        }
        unread()
        unread()
        assertEquals(UnreadCounts(1, 1, 1), store.getTotalUnreadCounts(RADIO_A))
        store.incrementUnreadCount(entity())
        store.incrementUnreadMentionCount(entity())
        store.incrementChannelUnreadCount(entity(id = channel.id))
        store.incrementChannelUnreadMentionCount(entity(id = channel.id))
        store.incrementRoomUnreadCount(entity(id = room.id))
        unread()
        assertEquals(UnreadCounts(2, 2, 2), store.getTotalUnreadCounts(RADIO_A))
        assertEquals(1L, requireNotNull(store.fetchContact(entity())).unreadMentionCount)
        assertEquals(1L, requireNotNull(store.fetchChannel(entity(id = channel.id))).unreadMentionCount)
        assertEquals(UnreadCounts(0, 0, 0), store.getTotalUnreadCounts(RADIO_B))
        store = newStore()
        assertEquals(UnreadCounts(2, 2, 2), store.getTotalUnreadCounts(RADIO_A))
    }

    @Test fun missingRowsAndReplacedChannelsFailWithoutEditingReplacementCounters() = runTest {
        assertEquals(PersistenceStoreError.ContactNotFound,
            assertFailsWith<PersistenceStoreException> { store.setContactReadState(entity(), true) }.error)
        assertEquals(PersistenceStoreError.RemoteNodeSessionNotFound,
            assertFailsWith<PersistenceStoreException> { store.setRoomReadState(entity(), false) }.error)
        val channel = ChannelDTO(radioId = RADIO_A, index = 1u, name = "Channel", secret = key(1).prefix(16))
        assertEquals(PersistenceStoreError.ChannelNotFound,
            assertFailsWith<PersistenceStoreException> { store.setChannelReadState(channel, true) }.error)
        store.saveChannel(channel)
        val captured = requireNotNull(store.fetchChannel(entity(id = channel.id)))
        store.saveChannel(RADIO_A, ChannelInfo(1u, "Replacement", key(2).prefix(16)))
        store.incrementChannelUnreadCount(entity(id = channel.id))
        assertEquals(PersistenceStoreError.ChannelNotFound,
            assertFailsWith<PersistenceStoreException> { store.setChannelReadState(captured, true) }.error)
        assertEquals(1L, requireNotNull(store.fetchChannel(entity(id = channel.id))).unreadCount)
    }
}
