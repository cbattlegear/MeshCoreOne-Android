// AndroidOnly: WP-302 External channel lookups cannot commit against a replacement graph or navigation entry.
package com.meshcoreone.android.app.container

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.meshcoreone.android.app.NavigationHostViewModel
import com.meshcoreone.android.app.deeplinks.AppDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.DeepLinkRouteOutcome
import com.meshcoreone.android.app.deeplinks.PendingExternalRoute
import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.navigation.GuardedExternalLinkRouter
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncState
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalChannelOwnershipTest : RoomProcessTest() {
    private val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
    private val channelUri = "meshcore://channel/add?name=Ops&secret=${secret.hexString}&region_scope=testregion"
    private val hashtagUri = "meshcoreone://hashtag/incident"
    private val firstDevice = UUID(1, 1)
    private val secondDevice = UUID(2, 2)

    @Test
    fun delayedExternalResultsCannotCommitAfterSameOrDifferentRadioReplacement() = runTest {
        val h = ContainerHarness(this, store, identity = { target ->
            if ((target as ConnectionTarget.Bluetooth).deviceId == firstDevice) key(7) else key(8)
        })
        try {
            for (hashtag in listOf(false, true)) for (found in listOf(false, true)) {
                for (sameRadio in listOf(false, true)) {
                    connect(h, h.target(firstDevice))
                    val original = assertNotNull(h.sessions.current)
                    val radio = original.token.radioId
                    val navigation = h.container.navigation
                    navigation.selectTab(AppTab.MAP)
                    val entered = CompletableDeferred<RadioId>()
                    val release = CompletableDeferred<List<ChannelDTO>>()
                    val environment = object :
                        AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, navigation) {
                        override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> {
                            entered.complete(radioId)
                            return release.await()
                        }
                    }
                    val router = GuardedExternalLinkRouter(h.container, navigation, environment)
                    val queue = PendingExternalRoute(router::routeExternal)
                    assertEquals(PendingExternalRoute.SubmitOutcome.Staged, queue.markReady())
                    val delivery = async {
                        queue.submit(PendingExternalRoute.Delivery("held", if (hashtag) hashtagUri else channelUri))
                    }
                    assertEquals(radio, entered.await())

                    h.manager.disconnect()
                    h.settle()
                    connect(h, h.target(if (sameRadio) firstDevice else secondDevice))
                    val replacement = assertNotNull(h.sessions.current)
                    assertNotEquals(original.token, replacement.token)
                    if (sameRadio) assertEquals(radio, replacement.token.radioId)
                    else assertNotEquals(radio, replacement.token.radioId)
                    navigation.selectTab(AppTab.MAP)
                    val before = navigation.currentState
                    val existing = ChannelDTO(
                        radioId = radio, index = 1u, name = if (hashtag) "#incident" else "Ops", secret = secret,
                    )
                    release.complete(if (found) listOf(existing) else emptyList())
                    assertEquals(
                        PendingExternalRoute.SubmitOutcome.Routed(DeepLinkRouteOutcome.Rejected),
                        delivery.await(),
                    )
                    assertEquals(before, navigation.currentState)
                    assertEquals(AppTab.MAP, navigation.currentState.selectedTab)
                    assertNull(navigation.currentState.pendingChannelLink)
                    assertNull(navigation.currentState.pendingHashtag)
                    assertNull(queue.snapshot())
                    h.manager.disconnect()
                    h.settle()
                }
            }
        } finally {
            h.container.close()
        }
    }

    @Test
    fun delayedConfirmationCannotReplaceANewerTabSelection() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h, h.target())
            val radio = assertNotNull(h.appState.currentRadioId)
            val navigation = h.container.navigation
            for (uri in listOf(channelUri, hashtagUri)) {
                navigation.selectTab(AppTab.SETTINGS)
                val entered = CompletableDeferred<RadioId>()
                val release = CompletableDeferred<List<ChannelDTO>>()
                val environment = object :
                    AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, navigation) {
                    override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> {
                        entered.complete(radioId)
                        return release.await()
                    }
                }
                val router = GuardedExternalLinkRouter(h.container, navigation, environment)
                val delivery = async { router.routeExternal(uri) }
                assertEquals(radio, entered.await())
                navigation.selectTab(AppTab.MAP)
                val before = navigation.currentState
                release.complete(emptyList())
                assertEquals(DeepLinkRouteOutcome.Rejected, delivery.await())
                assertEquals(before, navigation.currentState)
                assertNull(navigation.currentState.pendingChannelLink)
                assertNull(navigation.currentState.pendingHashtag)
            }
        } finally {
            h.container.close()
        }
    }

    @Test
    fun offlineLookupCannotStageAfterANewConnectionIsSubmittedBeforeGraphExposure() = runTest {
        val h = ContainerHarness(this, store, identity = { target ->
            if ((target as ConnectionTarget.Bluetooth).deviceId == firstDevice) key(7) else key(8)
        })
        val connectGate = CompletableDeferred<Unit>()
        try {
            connect(h, h.target(firstDevice))
            val radio = assertNotNull(h.appState.currentRadioId)
            h.container.connectionPort.refreshLastConnection()
            h.manager.disconnect()
            h.settle()
            assertEquals(radio, h.appState.currentRadioId)
            assertNull(h.sessions.current)

            val navigation = h.container.navigation
            navigation.selectTab(AppTab.MAP)
            val entered = CompletableDeferred<RadioId>()
            val release = CompletableDeferred<List<ChannelDTO>>()
            val environment = object :
                AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, navigation) {
                override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> {
                    entered.complete(radioId)
                    return release.await()
                }
            }
            val router = GuardedExternalLinkRouter(h.container, navigation, environment)
            val delivery = async { router.routeExternal(channelUri) }
            assertEquals(radio, entered.await())

            val allocated = CompletableDeferred<Unit>()
            h.links.configureRadio = { radioLink ->
                radioLink.connectGate = connectGate
                allocated.complete(Unit)
            }
            val connecting = async { h.manager.connect(h.target(secondDevice)) }
            allocated.await()
            assertNull(h.sessions.current, "The new submission must still be before graph publication")
            val before = navigation.currentState
            release.complete(emptyList())
            assertEquals(DeepLinkRouteOutcome.Rejected, delivery.await())
            assertEquals(before, navigation.currentState)
            assertNull(navigation.currentState.pendingChannelLink)
            connectGate.complete(Unit)
            connecting.await()
            h.settle()
            assertNull(navigation.currentState.pendingChannelLink)
        } finally {
            connectGate.complete(Unit)
            h.container.close()
        }
    }

    @Test
    fun currentAndOfflineExternalLinksStillNavigateOrStageTheirMetadata() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val navigation = h.container.navigation
            val router = GuardedExternalLinkRouter(h.container, navigation)
            navigation.selectTab(AppTab.MAP)
            assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, router.routeExternal(channelUri))
            assertEquals(AppTab.CHATS, navigation.currentState.selectedTab)
            assertEquals(ChannelLinkRequest("Ops", secret, "testregion"), navigation.currentState.pendingChannelLink)
            navigation.stageChannelLink(null)

            connect(h, h.target())
            val radio = assertNotNull(h.appState.currentRadioId)
            val channel = ChannelDTO(radioId = radio, index = 1u, name = "Ops", secret = secret)
            store.saveChannel(channel)
            navigation.selectTab(AppTab.SETTINGS)
            assertEquals(DeepLinkRouteOutcome.Navigated, router.routeExternal(channelUri))
            assertEquals(ChatSelection.Channel(channel), navigation.currentState.chatsSelectedRoute)

            navigation.selectTab(AppTab.SETTINGS)
            assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, router.routeExternal(hashtagUri))
            assertEquals(AppTab.CHATS, navigation.currentState.selectedTab)
            assertEquals(HashtagJoinRequest("#incident"), navigation.currentState.pendingHashtag)
        } finally {
            h.container.close()
        }
    }

    @Test
    fun replacingAnIdenticalQueuedLinkCancelsTheHeldDeliveryAndStagesTheNewOne() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h, h.target())
            val radio = assertNotNull(h.appState.currentRadioId)
            val navigation = h.container.navigation
            navigation.selectTab(AppTab.MAP)
            val entered = CompletableDeferred<RadioId>()
            val release = CompletableDeferred<List<ChannelDTO>>()
            var reads = 0
            val environment = object :
                AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, navigation) {
                override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> {
                    if (++reads == 1) {
                        entered.complete(radioId)
                        return release.await()
                    }
                    return emptyList()
                }
            }
            val router = GuardedExternalLinkRouter(h.container, navigation, environment)
            val queue = PendingExternalRoute(router::routeExternal)
            queue.markReady()
            val old = async { queue.submit(PendingExternalRoute.Delivery("old", channelUri)) }
            assertEquals(radio, entered.await())
            val replacement = async { queue.submit(PendingExternalRoute.Delivery("new", channelUri)) }
            assertEquals(
                PendingExternalRoute.SubmitOutcome.Routed(DeepLinkRouteOutcome.ConfirmationStaged),
                replacement.await(),
            )
            assertEquals(PendingExternalRoute.SubmitOutcome.Superseded, old.await())
            assertEquals(2, reads)
            assertEquals(ChannelLinkRequest("Ops", secret, "testregion"), navigation.currentState.pendingChannelLink)
            assertEquals(AppTab.CHATS, navigation.currentState.selectedTab)
            assertNull(queue.snapshot())
        } finally {
            h.container.close()
        }
    }

    @Test
    fun actualActivityHostRoutesAnOfflineChannelIntentWithItsMetadata() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = ContainerHarness(this, store)
        val host = NavigationHostViewModel()
        val retained = ViewModelStore().apply { put("navigation", host) }
        try {
            host.bindDeepLinks(h.container)
            testScheduler.runCurrent()
            h.container.navigation.selectTab(AppTab.MAP)
            host.submitNewViewIntent(Intent(Intent.ACTION_VIEW, Uri.parse(channelUri)))
            testScheduler.runCurrent()
            assertEquals(ChannelLinkRequest("Ops", secret, "testregion"), host.navigation.state.value.pendingChannelLink)
            assertEquals(AppTab.CHATS, host.navigation.state.value.selectedTab)
            assertNull(host.pendingExternalRoute.snapshot())
        } finally {
            retained.clear()
            h.container.close()
            Dispatchers.resetMain()
        }
    }

    private suspend fun connect(h: ContainerHarness, target: ConnectionTarget) {
        h.manager.connect(target)
        h.settle()
        h.assertReady()
        h.eventually("external-link graph is synchronized and navigation is bound") {
            h.appState.syncCoordinator?.state == SyncState.Synced &&
                h.container.navigation.state.value.navigationReady
        }
    }
}
