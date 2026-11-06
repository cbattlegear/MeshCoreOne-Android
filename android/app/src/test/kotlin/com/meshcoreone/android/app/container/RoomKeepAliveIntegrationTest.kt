// AndroidOnly: WP-310 CM-12 visible-room leases exercise the production graph, service and wire consumer.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.chats.rooms.RoomConversationStateHolder
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomKeepAliveIntegrationTest : RoomProcessTest() {
    private fun room(h: ContainerHarness, seed: Int = 42) = RemoteNodeSessionDTO(
        radioId = assertNotNull(h.appState.currentRadioId), publicKey = key(seed),
        name = "Visible room", role = RemoteNodeRole.ROOM_SERVER, isConnected = true,
        lastSyncTimestamp = 0x12345678u,
    )

    private suspend fun save(room: RemoteNodeSessionDTO) {
        store.saveRemoteNodeSessionDTO(room)
        store.saveContact(ContactDTO(
            radioId = room.radioId, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
            outPathLength = 1u, outPath = Bytes.of(0x0a), lastHeardTimestamp = 0u,
        ))
    }

    private fun configure(h: ContainerHarness) {
        h.links.configureRadio = { radio ->
            radio.respond = { command ->
                if (command[0].toInt() == 0x32) listOf(Bytes.of(0x06, 0, 1, 2, 3, 4) + le32(5000))
                else null
            }
        }
    }

    private fun packets(h: ContainerHarness) = h.links.radios.last().sent.filter { it[0].toInt() == 0x32 }
    private fun nextTick(h: ContainerHarness) {
        h.scheduler.advanceTimeBy(90_000)
        h.settle()
    }

    @Test fun visibleLeaseSendsImmediateRealKeepAliveWithStoredSyncBytesAndStopsTheLoop() = runTest {
        val h = ContainerHarness(this, store)
        configure(h)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val room = room(h)
            save(room)
            val source = assertNotNull(h.container.chatsFeature.room(room).keepAlive.first())
            val lease = assertNotNull(source.acquire(room))
            h.eventually("immediate keepalive") { packets(h).size == 1 }
            assertEquals(Bytes.of(0x32) + key(42) + Bytes.of(2, 0x78, 0x56, 0x34, 0x12), packets(h).single())
            nextTick(h)
            assertEquals(2, packets(h).size, "A visible lease must sustain the real service loop")
            lease.close()
            lease.close()
            nextTick(h)
            assertEquals(2, packets(h).size, "Disposal is idempotent and stops subsequent ticks")
        } finally { h.container.close() }
    }

    @Test fun staleSameRoomDisposalCannotStopReplacementOrADifferentRoom() = runTest {
        val h = ContainerHarness(this, store)
        configure(h)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val room = room(h)
            val other = room(h, 43)
            save(room); save(other)
            val source = assertNotNull(h.container.chatsFeature.room(room).keepAlive.first())
            val old = assertNotNull(source.acquire(room))
            h.eventually("first owner") { packets(h).size == 1 }
            val replacement = assertNotNull(source.acquire(room))
            h.eventually("replacement owner") { packets(h).size == 2 }
            val separate = assertNotNull(source.acquire(other))
            h.eventually("different room owner") { packets(h).size == 3 }
            old.close()
            nextTick(h)
            assertEquals(5, packets(h).size)
            replacement.close()
            nextTick(h)
            assertEquals(6, packets(h).size)
            assertEquals(key(43), Bytes(packets(h).last().toByteArray().copyOfRange(1, 33)))
            separate.close()
            nextTick(h)
            assertEquals(6, packets(h).size)
        } finally { h.container.close() }
    }

    @Test fun capturedSourceRejectsDisconnectedWrongRadioRepeaterAndWithdrawnGraphs() = runTest {
        val h = ContainerHarness(this, store)
        configure(h)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val room = room(h)
            save(room)
            val source = assertNotNull(h.container.chatsFeature.room(room).keepAlive.first())
            assertNull(source.acquire(room.copy(isConnected = false)))
            assertNull(source.acquire(room.copy(role = RemoteNodeRole.REPEATER)))
            assertNull(source.acquire(room.copy(radioId = RadioId(UUID.randomUUID()))))
            assertTrue(packets(h).isEmpty())
            val old = assertNotNull(source.acquire(room))
            h.eventually("old graph keepalive") { packets(h).size == 1 }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED); h.settle()
            assertNull(source.acquire(room))
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            save(room)
            assertNull(source.acquire(room), "A stale port cannot send into the replacement graph")
            val current = assertNotNull(h.container.chatsFeature.room(room).keepAlive.first())
            val replacement = assertNotNull(current.acquire(room))
            h.eventually("new graph keepalive") { packets(h).size == 1 }
            old.close()
            nextTick(h)
            assertEquals(2, packets(h).size, "Old-graph cleanup must not stop the replacement service")
            replacement.close()
            nextTick(h)
            assertEquals(2, packets(h).size)
        } finally { h.container.close() }
    }

    @Test fun visibleHolderRebindsTheActualServiceAfterReconnectWithoutRemounting() = runTest {
        val h = ContainerHarness(this, store)
        configure(h)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val room = room(h)
            save(room)
            val holder = RoomConversationStateHolder(room, h.container.chatsFeature.room(room), backgroundScope)
            holder.start()
            val visible = backgroundScope.launch { holder.keepAliveWhileVisible() }
            h.eventually("visible original graph") { packets(h).size == 1 }
            holder.updateDraft("recomposition")
            h.settle()
            assertEquals(1, packets(h).size)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED); h.settle()
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            save(room)
            holder.refreshSession()
            h.eventually("visible replacement graph") { packets(h).size == 1 }
            visible.cancelAndJoin()
            nextTick(h)
            assertEquals(1, packets(h).size)
        } finally { h.container.close() }
    }
}
