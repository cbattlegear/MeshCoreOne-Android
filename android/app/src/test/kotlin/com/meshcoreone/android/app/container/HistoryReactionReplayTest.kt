// PortedFrom: MC1Tests/Views/Chats/ChatViewModelReactionIndexingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-309 Fetched-history replay through the mounted App timeline and real Room store.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.ReactionIndexScope
import com.meshcoreone.android.app.state.ReactionIndexing
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.rendering.MessageFragment
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HistoryReactionReplayTest : RoomProcessTest() {
    private val emoji = "\uD83D\uDC4D"
    private val stamp = 1_704_067_200u
    private enum class Fetch { OPEN, REFRESH, OLDER }

    @Test fun directOpenReplaysStoredHistory() = runTest { replay(this, true, Fetch.OPEN) }
    @Test fun directRefreshReplaysStoredHistory() = runTest { replay(this, true, Fetch.REFRESH) }
    @Test fun directOlderPageReplaysStoredHistory() = runTest { replay(this, true, Fetch.OLDER) }
    @Test fun channelOpenReplaysStoredHistory() = runTest { replay(this, false, Fetch.OPEN) }
    @Test fun channelRefreshReplaysStoredHistory() = runTest { replay(this, false, Fetch.REFRESH) }
    @Test fun channelOlderPageReplaysStoredHistory() = runTest { replay(this, false, Fetch.OLDER) }

    private data class Fixture(
        val harness: ContainerHarness,
        val live: RadioSessionContainer,
        val contact: ContactDTO,
        val channel: ChannelDTO,
    )

    private suspend fun withSession(test: TestScope, body: suspend (Fixture) -> Unit) {
        val harness = ContainerHarness(test, store)
        try {
            harness.manager.connect(harness.target())
            harness.eventually("history reaction session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            val contact = ContactDTO(
                radioId = live.token.radioId, publicKey = key(3), name = "Alice", lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            store.saveChannel(live.token.radioId, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            body(Fixture(harness, live, contact, assertNotNull(store.fetchChannel(live.token.radioId, 3u))))
        } finally {
            harness.container.close()
        }
    }

    private fun target(fixture: Fixture, direct: Boolean) = MessageDTO(
        radioId = fixture.live.token.radioId,
        contactID = if (direct) fixture.contact.id else null,
        channelIndex = if (direct) null else fixture.channel.index,
        text = "Hello",
        timestamp = if (direct) stamp + 300u else stamp,
        senderTimestamp = if (direct) stamp else null,
        timestampCorrected = direct,
        createdAt = EPOCH,
        sortDate = EPOCH,
        senderNodeName = if (direct) null else "Alice",
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
    )

    private fun queue(fixture: Fixture, direct: Boolean): String {
        // Independent existing wire vector: "Hello" at Unix second 1704067200 hashes to b45pc4ek.
        val wire = if (direct) "$emoji\nb45pc4ek" else "@[Alice]$emoji\nb45pc4ek"
        if (direct) {
            fixture.live.reactionService.queuePendingDMReaction(
                assertNotNull(ReactionParser.parseDM(wire)), fixture.contact.id, "Bob", wire, fixture.contact.radioId,
            )
        } else {
            fixture.live.reactionService.queuePendingReaction(
                assertNotNull(ReactionParser.parse(wire)), fixture.channel.index, "Bob", wire, fixture.channel.radioId,
            )
        }
        return wire
    }

    private suspend fun replay(test: TestScope, direct: Boolean, fetch: Fetch) = withSession(test) { fixture ->
        val target = target(fixture, direct)
        store.saveMessage(target)
        if (fetch == Fetch.OLDER) {
            repeat(50) { index ->
                store.saveMessage(target.copy(
                    id = UUID.randomUUID(), text = "newer $index", timestamp = stamp + index.toUInt() + 1u,
                    senderTimestamp = null, createdAt = EPOCH.plusSeconds(index + 1L),
                    sortDate = EPOCH.plusSeconds(index + 1L),
                ))
            }
        }
        val conversation = if (direct) TimelineConversation.Direct(fixture.contact)
            else TimelineConversation.Channel(fixture.channel)
        val dependencies = fixture.harness.container.chatsFeature.timeline(conversation)
        val mounted = ChatViewModel(conversation, dependencies, CoroutineScope(currentCoroutineContext()))
        val foreignContact = UUID.randomUUID()
        val foreignChannel: UByte = 4u
        val service = fixture.live.reactionService
        val foreignWire = if (direct) "$emoji\nb45pc4ek" else "@[Alice]$emoji\nb45pc4ek"
        if (direct) {
            service.queuePendingDMReaction(assertNotNull(ReactionParser.parseDM(foreignWire)), foreignContact,
                "Other", foreignWire, target.radioId)
        } else {
            service.queuePendingReaction(assertNotNull(ReactionParser.parse(foreignWire)), foreignChannel,
                "Other", foreignWire, target.radioId)
        }
        try {
            if (fetch != Fetch.OPEN) mounted.open()
            val messageKey = EntityKey(target.radioId, target.id)
            assertTrue(store.fetchReactions(messageKey).isEmpty())
            if (fetch == Fetch.OLDER) {
                assertEquals(50, mounted.state.value.messages.size)
                assertFalse(mounted.state.value.messages.any { it.id == target.id })
                assertTrue(mounted.state.value.hasMoreMessages)
            }
            val wire = queue(fixture, direct)
            when (fetch) {
                Fetch.OPEN -> mounted.open()
                Fetch.REFRESH -> mounted.refresh()
                Fetch.OLDER -> mounted.loadOlder()
            }
            assertNull(mounted.state.value.loadError)
            assertNull(mounted.state.value.passiveError)
            assertEquals("$emoji:1", mounted.state.value.messages.single { it.id == target.id }.reactionSummary)
            val coordinator = assertNotNull(fixture.harness.appState.chatCoordinatorRegistry).coordinator(conversation.id)
            val rendered = coordinator.renderStateFlow.first { state ->
                state.items.singleOrNull { it.id == target.id }?.content
                    ?.contains(MessageFragment.ReactionSummary("$emoji:1")) == true
            }
            assertTrue(rendered.items.single { it.id == target.id }.content.contains(MessageFragment.ReactionSummary("$emoji:1")))
            assertEquals("$emoji:1", coordinator.messages.single { it.id == target.id }.reactionSummary)
            val first = store.fetchReactions(messageKey).single()
            assertEquals(target.id, first.messageID)
            assertEquals(target.radioId, first.radioId)
            assertEquals(if (direct) fixture.contact.id else null, first.contactID)
            assertEquals(if (direct) null else fixture.channel.index, first.channelIndex)
            assertEquals("Bob", first.senderName)
            assertEquals(emoji, first.emoji)
            assertEquals("b45pc4ek", first.messageHash)
            assertEquals(wire, first.rawText)
            assertEquals("$emoji:1", assertNotNull(store.fetchMessage(messageKey)).reactionSummary)

            queue(fixture, direct)
            mounted.refresh()
            assertEquals(listOf(first), store.fetchReactions(messageKey), "Re-queue must not replace or duplicate the row")
            assertEquals("$emoji:1", mounted.state.value.messages.single { it.id == target.id }.reactionSummary)
            assertEquals(if (fetch == Fetch.OLDER) 51 else 1, mounted.state.value.messages.size)
            val retained = if (direct) {
                service.indexDMMessage(UUID.randomUUID(), foreignContact, "Hello", stamp).map { it.rawText }
            } else {
                service.indexMessage(UUID.randomUUID(), foreignChannel, "Alice", "Hello", stamp).map { it.rawText }
            }
            assertEquals(listOf(foreignWire), retained, "Another contact/channel's pending reaction must stay queued")
        } finally {
            mounted.close()
        }
    }

    @Test fun directOlderPageReconcilesAReplayedReactionOnAnAlreadyLoadedRow() = runTest {
        overlappingPage(this, direct = true)
    }

    @Test fun channelOlderPageReconcilesAReplayedReactionOnAnAlreadyLoadedRow() = runTest {
        overlappingPage(this, direct = false)
    }

    private suspend fun overlappingPage(test: TestScope, direct: Boolean) = withSession(test) { fixture ->
        val target = target(fixture, direct)
        val oldest = target.copy(
            id = UUID.randomUUID(), text = "older", timestamp = stamp - 1u, senderTimestamp = null,
            createdAt = EPOCH.minusSeconds(1), sortDate = EPOCH.minusSeconds(1),
        )
        store.saveMessage(oldest)
        store.saveMessage(target)
        repeat(49) { index ->
            store.saveMessage(target.copy(
                id = UUID.randomUUID(), text = "newer $index", timestamp = stamp + index.toUInt() + 1u,
                senderTimestamp = null, createdAt = EPOCH.plusSeconds(index + 1L), sortDate = EPOCH.plusSeconds(index + 1L),
            ))
        }
        val conversation = if (direct) TimelineConversation.Direct(fixture.contact)
            else TimelineConversation.Channel(fixture.channel)
        val mounted = ChatViewModel(
            conversation, fixture.harness.container.chatsFeature.timeline(conversation),
            CoroutineScope(currentCoroutineContext()),
        )
        try {
            mounted.open()
            assertEquals(50, mounted.state.value.messages.size)
            assertNull(mounted.state.value.messages.single { it.id == target.id }.reactionSummary)
            assertFalse(mounted.state.value.messages.any { it.id == oldest.id })
            queue(fixture, direct)
            // A new stored row shifts the offset without a live timeline event or a refresh.
            val newest = target.copy(
                id = UUID.randomUUID(), text = "not yet loaded", timestamp = stamp + 51u, senderTimestamp = null,
                createdAt = EPOCH.plusSeconds(51), sortDate = EPOCH.plusSeconds(51),
            )
            store.saveMessage(newest)
            mounted.loadOlder()
            val messageKey = EntityKey(target.radioId, target.id)
            assertEquals(1, store.fetchReactions(messageKey).size)
            assertEquals("$emoji:1", assertNotNull(store.fetchMessage(messageKey)).reactionSummary)
            assertEquals("$emoji:1", mounted.state.value.messages.single { it.id == target.id }.reactionSummary)
            assertEquals(51, mounted.state.value.messages.size)
            assertEquals(51, mounted.state.value.messages.map { it.id }.toSet().size)
            assertTrue(mounted.state.value.messages.any { it.id == oldest.id })
            assertFalse(mounted.state.value.messages.any { it.id == newest.id }, "Older paging must not become a refresh")
            assertNull(mounted.state.value.passiveError)
            val coordinator = assertNotNull(fixture.harness.appState.chatCoordinatorRegistry).coordinator(conversation.id)
            val rendered = coordinator.renderStateFlow.first { state ->
                state.items.singleOrNull { it.id == target.id }?.content
                    ?.contains(MessageFragment.ReactionSummary("$emoji:1")) == true
            }
            assertEquals(51, rendered.items.size)
        } finally {
            mounted.close()
        }
    }

    @Test fun unknownChannelSenderSkipsOnlyThatMessageAndRetainsItsPendingReaction() = runTest {
        withSession(this) { fixture ->
            val incoming = target(fixture, direct = false)
            val outgoing = incoming.copy(id = UUID.randomUUID(), direction = MessageDirection.OUTGOING, senderNodeName = null)
            val unknownIncoming = incoming.copy(id = UUID.randomUUID(), senderNodeName = null, text = "unknown sender")
            listOf(outgoing, unknownIncoming, incoming).forEach { store.saveMessage(it) }
            queue(fixture, direct = false)
            val outgoingWire = "@[Me]$emoji\nb45pc4ek"
            fixture.live.reactionService.queuePendingReaction(
                assertNotNull(ReactionParser.parse(outgoingWire)), fixture.channel.index, "Carol",
                outgoingWire, fixture.channel.radioId,
            )
            val feature = fixture.harness.container.chatsFeature
            val prepared = feature.prepareTimelineMessages(
                listOf(outgoing, unknownIncoming, incoming),
                ReactionIndexing(fixture.live.reactionService, ReactionIndexScope.Channel(fixture.channel, null)),
            )
            assertEquals(listOf(outgoing.id, unknownIncoming.id, incoming.id), prepared.map { it.id })
            assertNull(prepared[0].reactionSummary)
            assertNull(prepared[1].reactionSummary)
            assertEquals("$emoji:1", prepared[2].reactionSummary, "Unknown names must not abort the rest of the page")
            assertTrue(store.fetchReactions(EntityKey(outgoing.radioId, outgoing.id)).isEmpty())
            assertTrue(store.fetchReactions(EntityKey(unknownIncoming.radioId, unknownIncoming.id)).isEmpty())
            val retried = feature.prepareTimelineMessages(
                listOf(outgoing),
                ReactionIndexing(fixture.live.reactionService, ReactionIndexScope.Channel(fixture.channel, "Me")),
            )
            assertEquals("$emoji:1", retried.single().reactionSummary)
            val saved = store.fetchReactions(EntityKey(outgoing.radioId, outgoing.id)).single()
            assertEquals("Carol", saved.senderName)
            assertEquals(fixture.channel.radioId, saved.radioId)
            assertEquals(fixture.channel.index, saved.channelIndex)
            assertNull(saved.contactID)
            assertEquals(outgoingWire, saved.rawText)
            assertEquals("$emoji:1", assertNotNull(store.fetchMessage(EntityKey(outgoing.radioId, outgoing.id))).reactionSummary)
        }
    }
}
