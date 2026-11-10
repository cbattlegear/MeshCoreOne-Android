// PortedFrom: MC1Tests/ViewModels/ChatViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatViewModelDeleteSequencingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Views/Chats/ChatViewModelReloadSerializationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.list.ui.chatListFailureText
import com.meshcoreone.android.feature.chats.list.support.FakeDependencies
import com.meshcoreone.android.feature.chats.list.support.Fixtures.channel
import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.Fixtures.room
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import com.meshcoreone.android.feature.chats.list.support.scenario
import com.meshcoreone.android.feature.chats.list.support.settle
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Test

class ChatListRepairTest {
    @Test fun `replacement evicts old channel preview and ignores late old-origin fetch`() = scenario {
        val deps = FakeDependencies()
        val original = channel("A").copy(occupantID = UUID.randomUUID())
        val replacement = original.copy(occupantID = UUID.randomUUID())
        val oldMessage = com.meshcoreone.android.core.model.MessageDTO(
            radioId = original.radioId, channelIndex = original.index, text = "old A",
            timestamp = 100u, createdAt = Instant.ofEpochSecond(100), channelOccupantID = original.occupantID,
        )
        val newMessage = oldMessage.copy(id = UUID.randomUUID(), text = "new B", channelOccupantID = replacement.occupantID)
        val holder = ChatListStateHolder(deps, scope)
        holder.setBuffers(channels = listOf(original))
        holder.recomputeSnapshot()
        deps.data.channelMessages = mapOf(original.id to listOf(oldMessage))
        holder.loadLastMessagePreviews()
        assertEquals(oldMessage, holder.state.value.lastMessages[original.id])
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        deps.data.channelPreviewGate = { entered.complete(Unit); release.await() }
        val staleLoad = scope.launch { holder.loadLastMessagePreviews() }
        entered.await()
        holder.setBuffers(channels = listOf(replacement))
        holder.recomputeSnapshot()
        assertNull(holder.state.value.lastMessages[original.id])
        deps.data.channelPreviewGate = null
        deps.data.channelMessages = mapOf(replacement.id to listOf(newMessage))
        holder.loadLastMessagePreviews()
        release.complete(Unit)
        staleLoad.join()
        assertEquals(newMessage, holder.state.value.lastMessages[replacement.id])
    }

    @Test
    fun `typed disconnected delete maps to original localized error resource`() {
        assertEquals(
            UiText.Resource(AppChatsStrings.chatsErrorNotConnectedToDelete),
            chatListFailureText(ConversationActionError.NotConnected),
        )
        assertNull(chatListFailureText(IllegalStateException("disk")))
    }

    @Test
    @OriginalCase("BlockedContactFilteringTests::Blocked contacts are excluded from allConversations()")
    @OriginalCase("BlockedContactFilteringTests::Blocked repeaters are also excluded()")
    fun `joint blocked repeater matrix retains only normal chat`() = scenario {
        val deps = FakeDependencies()
        val normal = contact("Normal")
        deps.data.contacts = listOf(
            normal, contact("BlockedChat", isBlocked = true),
            contact("Repeater", typeRawValue = 2u),
            contact("BlockedRepeater", typeRawValue = 2u, isBlocked = true),
        )
        val holder = ChatListStateHolder(deps, scope)
        holder.requestConversationReload()?.join()
        assertEquals(listOf(Conversation.Direct(normal)), holder.state.value.allConversations)
        assertTrue(holder.state.value.favoriteConversations.isEmpty())
    }

    @Test
    @OriginalCase("ChatViewModelTests::allConversations excludes repeaters()")
    fun `two chats and two repeaters retain exact chat identities`() = scenario {
        val deps = FakeDependencies()
        val alice = contact("Alice")
        val bob = contact("Bob")
        deps.data.contacts = listOf(
            alice, bob, contact("Repeater 1", typeRawValue = 2u), contact("Repeater 2", typeRawValue = 2u),
        )
        val holder = ChatListStateHolder(deps, scope)
        holder.requestConversationReload()?.join()
        assertEquals(listOf(alice.id, bob.id), holder.state.value.allConversations.map { it.id })
        assertTrue(holder.state.value.allConversations.all { it is Conversation.Direct })
    }

    @Test
    @OriginalCase("BlockedContactFilteringTests::allConversations returns empty when all contacts are blocked()")
    @OriginalCase("ChatViewModelTests::allConversations returns empty when only repeaters exist()")
    fun `all blocked and all repeater fixtures publish empty partitions`() = scenario {
        for (contacts in listOf(
            listOf(contact("Blocked1", isBlocked = true), contact("Blocked2", isBlocked = true)),
            listOf(contact("Repeater 1", typeRawValue = 2u), contact("Repeater 2", typeRawValue = 2u)),
        )) {
            val deps = FakeDependencies()
            val holder = ChatListStateHolder(deps, scope)
            holder.setBuffers(contacts = listOf(contact("Previously visible", isFavorite = true)))
            holder.recomputeSnapshot()
            deps.data.contacts = contacts
            holder.requestConversationReload()?.join()
            assertEquals(ConversationSnapshot.EMPTY, holder.state.value.snapshot)
            assertTrue(holder.state.value.allConversations.isEmpty())
            assertTrue(holder.state.value.hasLoadedOnce)
        }
    }

    @Test
    @OriginalCase("ChatViewModelReloadSerializationTests::filters preserved and partitioned()")
    fun `reload preserves full exclusions partition and descending dates`() = scenario {
        val deps = FakeDependencies()
        val favOlder = contact("FavOlder", isFavorite = true, lastMessageDate = Instant.ofEpochSecond(1000))
        val favNewer = contact("FavNewer", isFavorite = true, lastMessageDate = Instant.ofEpochSecond(2000))
        val plain = contact("Plain", lastMessageDate = Instant.ofEpochSecond(2000))
        deps.data.contacts = listOf(
            favOlder, plain, contact("Repeater", typeRawValue = 2u), contact("Blocked", isBlocked = true), favNewer,
        )
        deps.data.channels = listOf(channel(""))
        val holder = ChatListStateHolder(deps, scope)
        holder.requestConversationReload()?.join()
        assertEquals(listOf(favNewer.id, favOlder.id), holder.state.value.favoriteConversations.map { it.id })
        assertEquals(listOf(plain.id), holder.state.value.nonFavoriteConversations.map { it.id })
        assertEquals(listOf(favNewer.id, favOlder.id, plain.id), holder.state.value.allConversations.map { it.id })
    }

    @Test
    @OriginalCase("ChatViewModelDeleteSequencingTests::masked stale reload produces no generation bump()")
    @OriginalCase("ChatViewModelDeleteSequencingTests::confirming reload self heals without an extra bump()")
    @OriginalCase("ChatViewModelReloadSerializationTests::deleted row does not reappear on stale reload()")
    fun `room stale and confirming fetches preserve hidden generation`() = scenario {
        val deps = FakeDependencies()
        val held = room()
        deps.data.rooms = listOf(held)
        val holder = ChatListStateHolder(deps, scope)
        holder.requestConversationReload()?.join()
        val before = holder.state.value.snapshotGeneration
        holder.removeConversation(Conversation.Room(held))
        val removed = holder.state.value.snapshotGeneration
        assertEquals(before + 1, removed)
        holder.requestConversationReload()?.join()
        assertTrue(holder.state.value.allConversations.isEmpty())
        assertEquals(setOf(held.id), holder.state.value.pendingRemovalIds)
        assertEquals(removed, holder.state.value.snapshotGeneration)
        deps.data.rooms = emptyList()
        holder.requestConversationReload()?.join()
        assertTrue(holder.state.value.pendingRemovalIds.isEmpty())
        assertTrue(holder.state.value.allConversations.isEmpty())
        assertEquals(removed, holder.state.value.snapshotGeneration)
    }

    @Test
    @OriginalCase("ChatViewModelDeleteSequencingTests::restore readmits held row exactly once()")
    fun `room repeated restore preserves identity and single generation bump`() = scenario {
        val holder = ChatListStateHolder(FakeDependencies(), scope)
        val held = room()
        holder.setBuffers(roomSessions = listOf(held))
        holder.recomputeSnapshot()
        holder.removeConversation(Conversation.Room(held))
        val removed = holder.state.value.snapshotGeneration
        holder.restoreConversation(Conversation.Room(held))
        assertEquals(listOf(Conversation.Room(held)), holder.state.value.allConversations)
        assertTrue(holder.state.value.pendingRemovalIds.isEmpty())
        assertEquals(removed + 1, holder.state.value.snapshotGeneration)
        holder.restoreConversation(Conversation.Room(held))
        assertEquals(listOf(held), holder.state.value.roomSessions)
        assertEquals(removed + 1, holder.state.value.snapshotGeneration)
    }

    @Test
    @OriginalCase("ChatViewModelDeleteSequencingTests::is delete pending reflects both sets()")
    fun `pending guard follows each set and their union`() = scenario {
        val holder = ChatListStateHolder(FakeDependencies(), scope)
        val held = Conversation.Room(room())
        val other = UUID.randomUUID()
        assertFalse(holder.isDeletePending(held.id))
        holder.removeConversation(held)
        assertTrue(holder.isDeletePending(held.id))
        holder.markDeleting(held.id)
        holder.markDeleting(other)
        assertTrue(holder.isDeletePending(other))
        holder.restoreConversation(held)
        assertTrue(holder.isDeletePending(held.id))
        holder.clearDeleting(held.id)
        assertFalse(holder.isDeletePending(held.id))
        assertTrue(holder.isDeletePending(other))
        holder.clearDeleting(other)
        assertFalse(holder.isDeletePending(other))
        assertTrue(holder.state.value.pendingRemovalIds.isEmpty())
        assertTrue(holder.state.value.deletingIds.isEmpty())
    }

    @Test
    @OriginalCase("ChatViewModelDeleteSequencingTests::direct delete throws when not connected()", "platform-adaptation")
    fun `callable delete rejects every nonready state without writes or navigation`() = scenario {
        for (connection in DeviceConnectionState.entries.filter { it != DeviceConnectionState.READY }) {
            val deps = FakeDependencies()
            deps.connectionState.value = connection
            val held = contact("Alice")
            val holder = ChatListStateHolder(deps, scope)
            holder.setBuffers(contacts = listOf(held))
            holder.recomputeSnapshot()
            val before = holder.state.value.snapshotGeneration
            val cleared = mutableListOf<ChatRoute>()
            val actions = ChatListActions(holder, deps, scope, {}, { cleared += it })
            actions.deleteDirectConversation(held)
            settle()
            val failure = assertIs<ChatListMessage.Failure>(holder.state.value.errorAlert)
            assertEquals(ConversationActionError.NotConnected, failure.cause)
            assertEquals(listOf(Conversation.Direct(held)), holder.state.value.allConversations)
            assertEquals(before, holder.state.value.snapshotGeneration)
            assertTrue(holder.state.value.pendingRemovalIds.isEmpty())
            assertTrue(deps.data.calls.isEmpty())
            assertTrue(cleared.isEmpty())
        }
    }

    @Test
    fun `disconnect between optimistic hide and write restores held direct row`() = scenario {
        val deps = FakeDependencies()
        val held = contact("Alice")
        val holder = ChatListStateHolder(deps, scope)
        holder.setBuffers(contacts = listOf(held))
        holder.recomputeSnapshot()
        val actions = ChatListActions(holder, deps, scope, {}, {
            deps.connectionState.value = DeviceConnectionState.DISCONNECTED
        })
        actions.deleteDirectConversation(held)
        settle()
        val failure = assertIs<ChatListMessage.Failure>(holder.state.value.errorAlert)
        assertEquals(ConversationActionError.NotConnected, failure.cause)
        assertEquals(listOf(held), holder.state.value.contacts)
        assertEquals(listOf(Conversation.Direct(held)), holder.state.value.allConversations)
        assertTrue(holder.state.value.pendingRemovalIds.isEmpty())
        assertTrue(deps.data.calls.isEmpty())
    }

    @Test
    @OriginalCase("ChatViewModelReloadSerializationTests::stale reload does not commit()", "platform-adaptation")
    fun `parked stale fetch cannot replace fresh snapshot at either commit boundary`() = scenario {
        for (parkRooms in listOf(false, true)) {
            val deps = FakeDependencies()
            val contacts = (0 until 12).map { contact("C$it") }
            val heldRoom = room()
            deps.data.contacts = contacts
            deps.data.rooms = listOf(heldRoom)
            val arrived = CompletableDeferred<Unit>()
            var release: Continuation<Unit>? = null
            val gate: suspend () -> Unit = {
                suspendCoroutine { continuation ->
                    release = continuation
                    arrived.complete(Unit)
                }
            }
            if (parkRooms) deps.data.roomFetchGate = gate else deps.data.conversationFetchGate = gate
            val holder = ChatListStateHolder(deps, scope)
            val stale = assertNotNull(holder.requestConversationReload())
            arrived.await()
            try {
                deps.data.conversationFetchGate = null
                deps.data.roomFetchGate = null
                deps.data.contacts = contacts.dropLast(1)
                deps.data.rooms = emptyList()
                assertNotNull(holder.requestConversationReload()).join()
                val fresh = holder.state.value.snapshot
                val generation = holder.state.value.snapshotGeneration
                assertEquals(contacts.dropLast(1).map { it.id }, fresh.others.map { it.id })
                assertFalse(stale.isActive)
                assertNotNull(release).resume(Unit)
                release = null
                stale.join()
                assertEquals(fresh, holder.state.value.snapshot)
                assertEquals(generation, holder.state.value.snapshotGeneration)
                assertTrue(holder.state.value.allConversations.none { it.id == contacts.last().id || it.id == heldRoom.id })
            } finally {
                release?.resume(Unit)
            }
        }
    }
}
