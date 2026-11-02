// PortedFrom: MC1Tests/Views/Chats/ChatLinkRouterTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ExistingChannelLinkPersistenceTest : RoomProcessTest() {
    @Test
    fun existingChatLinkNavigatesStoredChannelWithoutApplyingLinkRegion() = runTest {
        val harness = ContainerHarness(this, store)
        harness.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x20) listOf(Bytes.of(0)) else null }
        }
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            harness.eventually("initial channel sync completes before seeding the persisted channel") {
                harness.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }

            val radioId = assertNotNull(harness.appState.currentRadioId)
            val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
            val existing = ChannelDTO(
                radioId = radioId,
                index = 1u,
                name = "Ops",
                secret = secret,
            ).withFloodScope(ChannelFloodScope.Region("Germany"))
            store.saveChannel(existing)
            assertEquals(ChannelFloodScope.Region("Germany"), store.fetchChannels(radioId).single().floodScope)

            val router = ChatLinkRouter(harness.container.chatsFeature.list.links, backgroundScope)
            val channelWritesBefore = harness.links.radios.single().sent.count { it[0].toInt() == 0x20 }
            assertTrue(
                router.route(
                    "meshcore://channel/add?name=Ops&secret=${secret.hexString}&region_scope=attacker",
                ),
            )
            testScheduler.runCurrent()

            assertEquals(
                ChatSelection.Channel(existing),
                harness.container.navigation.state.value.chatsSelectedRoute,
            )
            assertNull(harness.container.navigation.state.value.pendingChannelLink)
            assertEquals(channelWritesBefore, harness.links.radios.single().sent.count { it[0].toInt() == 0x20 })
            assertEquals(
                ChannelFloodScope.Region("Germany"),
                store.fetchChannels(radioId).single { it.id == existing.id }.floodScope,
            )
        } finally {
            harness.container.close()
        }
    }
}
