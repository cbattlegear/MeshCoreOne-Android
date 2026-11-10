// AndroidOnly: WP-202 Active channel timeline and summary reads preserve displayed Room origin.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.feature.chats.list.ChatChannelKey
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelHistoryConsumerIntegrationTest : RoomProcessTest() {
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
            val oldTimeline = h.container.chatsFeature.timeline(TimelineConversation.Channel(a)).data
            assertEquals(listOf(old), oldTimeline.fetchMessages(TimelineConversation.Channel(a), 50, 0))

            store.saveChannel(radio, ChannelInfo(0u, "B", Bytes(ByteArray(16) { 2 })))
            val b = assertNotNull(store.fetchChannel(radio, 0u))
            val current = old.copy(id = UUID.randomUUID(), text = "current B", timestamp = 200u,
                createdAt = at.plusSeconds(10), sortDate = at.plusSeconds(10), channelOccupantID = b.occupantID)
            store.saveChannelMessage(current, ChannelHistoryScope(radio, 0u, assertNotNull(b.occupantID)))
            val conversation = TimelineConversation.Channel(b)
            val data = h.container.chatsFeature.timeline(conversation).data
            assertEquals(listOf(current), data.fetchMessages(conversation, 50, 0))
            assertEquals(listOf(current), data.fetchMessageWindow(conversation, at, 1).messages)
            assertEquals(current, data.fetchMessage(current.id))
            assertFailsWith<PersistenceStoreException> { data.fetchMessage(old.id) }
            assertFailsWith<PersistenceStoreException> {
                oldTimeline.fetchMessages(TimelineConversation.Channel(a), 50, 0)
            }
            val summaries = h.container.chatsFeature.listFor(radio).data
            val key = ChatChannelKey(radio, b.index, b.id, b.occupantID)
            assertEquals(listOf(current), summaries.fetchLastChannelMessages(listOf(key), 3).getValue(b.id))
            val unbound = current.copy(id = UUID.randomUUID(), text = "unbound original", channelOccupantID = null)
            store.saveMessage(unbound)
            assertEquals(listOf(current), data.fetchMessages(conversation, 50, 0))
            assertEquals(listOf(current), summaries.fetchLastChannelMessages(listOf(key), 3).getValue(b.id))
            assertFailsWith<PersistenceStoreException> { data.fetchMessage(unbound.id) }
            val incoming = current.copy(id = UUID.randomUUID(), text = "original incoming semantics",
                direction = MessageDirection.INCOMING, status = MessageStatus.SENT, channelOccupantID = null,
                timestamp = 300u, createdAt = at.plusSeconds(20), sortDate = at.plusSeconds(20))
            store.saveMessage(incoming)
            assertEquals(listOf(current, incoming), data.fetchMessages(conversation, 50, 0))
            assertEquals(listOf(incoming), data.fetchMessages(conversation, 1, 0))
            assertEquals(listOf(current, incoming), data.fetchMessageWindow(conversation, at, 1).messages)
            assertEquals(incoming, data.fetchMessage(incoming.id))
            assertEquals(listOf(current, incoming), summaries.fetchLastChannelMessages(listOf(key), 3).getValue(b.id))
            assertEquals(setOf(b.id), summaries.fetchFailedSendConversationKeys(radio).channelIDs.toSet())
            data.markFailedSendsSeen(conversation)
            assertTrue(summaries.fetchFailedSendConversationKeys(radio).channelIDs.isEmpty())
            assertFalse(assertNotNull(store.fetchMessage(EntityKey(radio, old.id))).failureSeen)
            assertFalse(assertNotNull(store.fetchMessage(EntityKey(radio, unbound.id))).failureSeen)
            assertEquals(old.text, store.fetchMessage(EntityKey(radio, old.id))?.text)
            assertEquals(unbound, store.fetchMessage(EntityKey(radio, unbound.id)))
            store.saveChannel(radio, ChannelInfo(0u, "C", Bytes(ByteArray(16) { 3 })))
            assertFailsWith<PersistenceStoreException> { data.fetchMessage(incoming.id) }
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
        } finally {
            h.container.close()
        }
    }
}
