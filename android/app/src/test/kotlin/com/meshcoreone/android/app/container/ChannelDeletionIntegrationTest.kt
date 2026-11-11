// AndroidOnly: WP-209 Exact displayed-occupant deletion through the real app graph, protocol and Room.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import com.meshcoreone.android.feature.chats.list.ChatListActions
import com.meshcoreone.android.feature.chats.list.ChatListMessage
import com.meshcoreone.android.feature.chats.list.ChatListStateHolder
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelDeletionIntegrationTest : RoomProcessTest() {
    @Test fun delayedOriginalDeleteRejectsReusedRowAndPreservesReplacementHistory() = runTest {
        val h = ContainerHarness(this, store)
        configure(h)
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            val radio = graph.token.radioId
            for (sameValues in listOf(false, true)) {
                graph.channelService.setChannelWithSecret(radio, 1u, "A", secret(1))
                val original = assertNotNull(store.fetchChannel(radio, 1u))
                val dependencies = h.container.chatsFeature.listFor(radio)
                val capturedServices = assertNotNull(dependencies.conversationServices())
                graph.channelService.setChannelWithSecret(radio, 1u, "B", secret(2))
                if (sameValues) graph.channelService.setChannelWithSecret(radio, 1u, "A", secret(1))
                val replacement = assertNotNull(store.fetchChannel(radio, 1u))
                assertEquals(original.id, replacement.id)
                assertNotEquals(original.occupantID, replacement.occupantID)
                if (sameValues) {
                    assertEquals(original.name, replacement.name)
                    assertEquals(original.secret, replacement.secret)
                }
                val history = MessageDTO(radioId = radio, channelIndex = 1u, text = "B data", timestamp = 1u,
                    direction = MessageDirection.INCOMING)
                store.saveMessage(history)
                h.container.navigation.navigateToChannel(replacement)
                val before = h.container.navigation.currentState
                val firmware = h.links.radios.single()
                val writes = firmware.sent.filter { it[0].toInt() == 0x20 }.toList()
                assertFailsWith<ChannelDispatchFailure.ReplacedOccupant> { capturedServices.clearChannel(original) }
                assertEquals(writes, firmware.sent.filter { it[0].toInt() == 0x20 })
                assertEquals(replacement, store.fetchChannel(radio, 1u))
                assertEquals(history, store.fetchMessage(EntityKey(radio, history.id)))
                assertEquals(before, h.container.navigation.currentState)
                val holder = ChatListStateHolder(dependencies, backgroundScope)
                holder.requestConversationReload()?.join()
                val actions = ChatListActions(holder, dependencies, backgroundScope, {},
                    { error("A stale action must not run rendered-selection cleanup") })
                actions.deleteChannelConversation(original)
                h.eventually("visible stale-delete failure") { !holder.isDeletePending(original.id) }
                val failure = assertIs<ChatListMessage.Failure>(holder.state.value.channelDeleteFailure?.message)
                assertIs<ChannelDispatchFailure.ReplacedOccupant>(failure.cause)
                assertEquals(listOf(replacement), holder.state.value.channels.filter { it.index == 1.toUByte() })
                assertEquals(writes, firmware.sent.filter { it[0].toInt() == 0x20 })
                assertEquals(replacement, store.fetchChannel(radio, 1u))
                assertEquals(history, store.fetchMessage(EntityKey(radio, history.id)))
                assertEquals(before, h.container.navigation.currentState)
            }
        } finally { h.container.close() }
    }

    @Test fun validListActionDeletesOnlyItsProvedChannelAndClearsTheAuthoritativeSelection() = runTest {
        val h = ContainerHarness(this, store)
        configure(h)
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            val radio = graph.token.radioId
            graph.channelService.setChannelWithSecret(radio, 1u, "A", secret(1))
            graph.channelService.setChannelWithSecret(radio, 2u, "Other", secret(2))
            val original = assertNotNull(store.fetchChannel(radio, 1u))
            val other = assertNotNull(store.fetchChannel(radio, 2u))
            val history = MessageDTO(radioId = radio, channelIndex = 1u, text = "A data", timestamp = 1u,
                direction = MessageDirection.INCOMING)
            val retained = history.copy(id = java.util.UUID.randomUUID(), channelIndex = 2u, text = "Other data")
            store.saveMessage(history)
            store.saveMessage(retained)
            val navigation = h.container.navigation
            navigation.navigateToChannel(original)
            val dependencies = h.container.chatsFeature.listFor(radio)
            val holder = ChatListStateHolder(dependencies, backgroundScope)
            holder.requestConversationReload()?.join()
            val actions = ChatListActions(holder, dependencies, backgroundScope, {},
                { error("Channel cleanup must use the navigation owner, not a rendered selection") })
            actions.deleteChannelConversation(original)
            h.eventually("guarded delete") { !holder.isDeletePending(original.id) }
            assertNull(holder.state.value.channelDeleteFailure)
            assertNull(store.fetchChannel(radio, 1u))
            assertNull(store.fetchMessage(EntityKey(radio, history.id)))
            assertEquals(other, store.fetchChannel(radio, 2u))
            assertEquals(retained, store.fetchMessage(EntityKey(radio, retained.id)))
            assertNull(navigation.currentState.chatsSelectedRoute)
            assertFalse(navigation.currentState.canGoBack)
            val clear = h.links.radios.single().sent.last { it[0].toInt() == 0x20 }
            assertEquals(com.meshcoreone.android.core.protocol.command.PacketBuilder.setChannel(1u, "", secret(0)), clear)
        } finally { h.container.close() }
    }

    @Test fun capturedServicesCannotRetargetASuccessorConnectionEvenWithTheSameRadio() = runTest {
        val h = ContainerHarness(this, store)
        configure(h)
        try {
            h.container.foreground.activityStarted()
            val target = h.target()
            h.manager.connect(target)
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            val radio = graph.token.radioId
            graph.channelService.setChannelWithSecret(radio, 1u, "A", secret(1))
            val original = assertNotNull(store.fetchChannel(radio, 1u))
            val captured = assertNotNull(h.container.chatsFeature.listFor(radio).conversationServices())
            h.manager.disconnect(com.meshcoreone.android.core.runtime.RuntimeDisconnectReason.USER_INITIATED)
            h.manager.connect(target)
            h.settle()
            h.assertReady()
            val successor = assertNotNull(h.sessions.current)
            assertNotSame(graph, successor)
            val writes = h.links.radios.sumOf { device -> device.sent.count { it[0].toInt() == 0x20 } }
            assertFailsWith<ChannelDispatchFailure.RetiredSession> { captured.clearChannel(original) }
            assertEquals(writes, h.links.radios.sumOf { device -> device.sent.count { it[0].toInt() == 0x20 } })
            assertSame(successor, h.sessions.current)
        } finally { h.container.close() }
    }

    private fun configure(h: ContainerHarness) {
        h.links.configureRadio = { radio -> radio.respond = { frame ->
            when (frame[0].toInt()) {
                0x1f -> listOf(Bytes.of(0x12, frame[1].toInt()) + Bytes(ByteArray(48)))
                0x20 -> listOf(Bytes.of(0))
                else -> null
            }
        } }
    }

    private fun secret(value: Int) = Bytes(ByteArray(16) { value.toByte() })
}
