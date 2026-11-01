// PortedFrom: MC1Tests/AppState/NavigationCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Real graph, Room, platform notification receiver and mounted adaptive consumers.
package com.meshcoreone.android.app.container

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.navigation.*
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.*
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import com.meshcoreone.android.feature.nodes.deps.NodesDataStore
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.util.UUID
import kotlin.test.*

@Config(sdk = [31, 37], qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationFlowIntegrationTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val width = mutableStateOf(390.dp)

    @Before fun mountActivity() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyActivity() { host.pause().stop().destroy() }

    private fun mount(h: ContainerHarness) = compose.runOnUiThread {
        host.get().setContent {
            Box(Modifier.width(width.value).height(1000.dp)) {
                MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(
                        h.container.navigation, chats = h.container.chatsFeature, nodes = h.container.nodesFeature,
                    )
                }
            }
        }
    }

    private fun unmount() = compose.runOnUiThread { host.get().setContent {} }

    private suspend fun connect(h: ContainerHarness) {
        h.manager.connect(h.target())
        h.settle()
        h.assertReady()
        h.eventually("real graph finishes its initial contact/channel synchronization") {
            h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced &&
                h.container.navigation.state.value.navigationReady
        }
    }

    private fun await(h: ContainerHarness, condition: () -> Boolean) = compose.waitUntil(10_000) {
        h.settle()
        condition()
    }

    private fun confirmationEnabled(): Boolean {
        val nodes = compose.onAllNodesWithTag("contact-link-confirm").fetchSemanticsNodes()
        return nodes.size == 1 && !nodes.single().config.contains(SemanticsProperties.Disabled)
    }

    @Test fun productionNotificationCallbacksReachMountedDirectChannelDiscoveryAndContactDetails() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Notification peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = store.fetchContact(radio, key(20))!!
            val channel = ChannelDTO(radioId = radio, index = 3u, name = "Notification channel")
            store.saveChannel(channel)
            mount(h)
            val service = assertNotNull(h.sessions.current).notificationService
            val callback = assertNotNull(service.onNotificationTapped)
            val generation = h.container.navigation.state.value.generation
            h.appState.wireServicesIfConnected()
            assertSame(callback, service.onNotificationTapped, "Rewire must retain a single binding")
            assertEquals(generation, h.container.navigation.state.value.generation)

            val message = UUID.randomUUID()
            for (payload in listOf(
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), message),
                NotificationPayload.ChannelMessage(radio, channel.index, message),
            )) {
                assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, null, null)))
                compose.waitForIdle()
                val state = h.container.navigation.state.value
                assertEquals(AppTab.CHATS, state.selectedTab)
                compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
                when (payload) {
                    is NotificationPayload.DirectMessage -> {
                        assertEquals(contact.id, state.pendingChatContact?.id)
                        assertEquals(ChatSelection.Direct(contact), state.chatsSelectedRoute)
                    }
                    is NotificationPayload.ChannelMessage -> {
                        assertEquals(channel.id, state.pendingChannel?.id)
                        assertEquals(ChatSelection.Channel(channel), state.chatsSelectedRoute)
                    }
                    else -> error("Only chat payloads are in this matrix")
                }
            }
            for (manual in listOf(true, false)) {
                h.container.connectionPort.updateDevice(assertNotNull(h.appState.connectedDevice).copy(manualAddContacts = manual))
                h.eventually("manual contact-add preference reaches the connected device") {
                    h.appState.connectedDevice?.manualAddContacts == manual
                }
                assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                    NotificationPayload.NewContact(EntityKey(radio, contact.id)), null, null,
                )))
                compose.waitForIdle()
                val state = h.container.navigation.state.value
                assertEquals(AppTab.NODES, state.selectedTab)
                compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
                if (manual) {
                    assertEquals(NavigationDestination.Discovery, state.activeStack.last().destination)
                    assertTrue(state.pendingDiscoveryNavigation)
                } else {
                    assertEquals(contact.id, state.pendingContactDetail?.id)
                    assertEquals(NavigationDestination.ContactDetail(contact), state.activeStack.last().destination)
                }
            }
        } finally { unmount(); h.container.close() }
    }

    @Test fun reactionNotificationAnchorsReachActualMountedTimelinesAndSurviveWindowChanges() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Reaction peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = store.fetchContact(radio, key(20))!!
            val channel = ChannelDTO(radioId = radio, index = 1u, name = "Reaction channel")
            store.saveChannel(channel)
            mount(h)
            for (isChannel in listOf(false, true)) {
                val message = MessageDTO(
                    radioId = radio, contactID = if (isChannel) null else contact.id,
                    channelIndex = if (isChannel) channel.index else null,
                    text = "Anchored reaction ${if (isChannel) "channel" else "direct"}",
                    timestamp = 100u, createdAt = EPOCH, direction = MessageDirection.INCOMING,
                )
                store.saveMessage(message)
                assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                    NotificationPayload.Reaction(message.id, if (isChannel) null else EntityKey(radio, contact.id),
                        if (isChannel) channel.index else null, if (isChannel) radio else null),
                    null, null,
                )))
                assertEquals(message.id, h.container.navigation.state.value.pendingScrollToMessageID)
                await(h) {
                    compose.onAllNodesWithTag("timeline-bubble:${message.id}", useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("timeline-bubble:${message.id}", useUnmergedTree = true).assertIsDisplayed()
                val stack = h.container.navigation.state.value.activeStack
                val selection = h.container.navigation.state.value.chatsSelectedRoute
                for (dp in listOf(600, 744, 780, 834, 390)) {
                    compose.runOnIdle { width.value = dp.dp }
                    compose.onNodeWithTag(if (dp < 600) "navigation-hidden" else "navigation-rail").assertIsDisplayed()
                    assertEquals(stack, h.container.navigation.state.value.activeStack)
                    assertEquals(selection, h.container.navigation.state.value.chatsSelectedRoute)
                    compose.onNodeWithTag("timeline-bubble:${message.id}", useUnmergedTree = true).assertIsDisplayed()
                }
                compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
                compose.waitForIdle()
                assertEquals(stack.dropLast(1), h.container.navigation.state.value.activeStack)
            }
        } finally { unmount(); h.container.close() }
    }

    @Test fun actualPlatformTapWaitsForTheGraphAndReachesTheMountedDestinationExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        val h = ContainerHarness(this, store, notificationNavigationReady = { delivery.retryPendingActions() })
        delivery = AndroidMessagingNotificationDelivery(context, backgroundScope, h.container::handleNotificationResponse)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Cold notification peer") }
        try {
            val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
            val contact = ContactDTO(radioId = radio, publicKey = key(20), name = "Cold notification peer")
            store.saveContact(contact)
            delivery.registerCategories(SnapshotList.empty())
            delivery.post(NotificationRequest(
                NotificationId("wp302-cold"), NotificationCategory.DIRECT_MESSAGE, contact.name, "Cold tap",
                false, null, null, NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()),
            ))
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.activeNotifications.single().notification.contentIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            testScheduler.runCurrent()
            assertNull(h.sessions.current)
            assertEquals(1, h.container.navigation.state.value.activeStack.size, "No destination before real readiness")
            connect(h)
            mount(h)
            await(h) { h.container.navigation.state.value.pendingChatContact?.id == contact.id }
            val state = h.container.navigation.state.value
            compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
            assertEquals(ChatSelection.Direct(contact), state.chatsSelectedRoute)
            val stack = state.activeStack
            delivery.retryPendingActions()
            h.settle()
            assertEquals(stack, h.container.navigation.state.value.activeStack)
            manager.cancelAll()
        } finally { unmount(); h.container.close() }
    }

    @Test fun closedGenerationCallbacksCannotNavigateOrClearANewRadioRequest() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Generation peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = store.fetchContact(radio, key(20))!!
            val service = assertNotNull(h.sessions.current).notificationService
            val stale = assertNotNull(service.onNotificationTapped)
            h.manager.disconnect()
            h.settle()
            assertNull(service.onNotificationTapped)
            assertFalse(h.container.navigation.state.value.navigationReady)
            connect(h)
            val before = h.container.navigation.state.value
            stale(EntityKey(radio, contact.id))
            assertEquals(before, h.container.navigation.state.value)
            assertNotNull(h.sessions.current?.notificationService?.onNotificationTapped)
        } finally { h.container.close() }
    }

    @Test fun externalAndChatContactLinksRequireMountedConsentAndImportExactMetadataIntoRoom() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x09) listOf(Bytes.of(0)) else null }
        }
        try {
            connect(h)
            mount(h)
            val navigation = h.container.navigation
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            val chat = ChatLinkRouter(h.container.chatsFeature.list.links, backgroundScope)
            for ((index, type) in ContactType.entries.withIndex()) {
                val publicKey = key(30 + index)
                val name = "Claimed ${type.name}"
                val uri = h.container.nodesFeature.uriCodec.exportContactUri(name, publicKey, type)
                if (index % 2 == 0) external.routeExternal(uri) else assertTrue(chat.route(uri))
                testScheduler.runCurrent()
                val staged = assertNotNull(navigation.state.value.pendingContactLink)
                assertEquals(name, staged.name)
                assertEquals(publicKey, staged.publicKey)
                assertEquals(type, staged.type)
                assertEquals(h.appState.currentRadioId, staged.targetRadioId)
                assertEquals(navigation.state.value.generation, staged.generation)
                await(h) {
                    compose.onAllNodesWithTag("contact-link-confirmation").fetchSemanticsNodes().isNotEmpty() &&
                        confirmationEnabled()
                }
                compose.onNodeWithTag("contact-link-public-key").assertTextEquals(publicKey.uppercaseHexString(" "))
                val firmware = h.links.radios.last()
                val writes = firmware.sent.count { it[0].toInt() == 0x09 }
                assertNull(store.fetchContact(staged.targetRadioId!!, publicKey), "URI receipt must not import")
                compose.runOnIdle { width.value = 834.dp }
                assertSame(staged, navigation.state.value.pendingContactLink)
                compose.onNodeWithTag("contact-link-confirm").performClick()
                await(h) { navigation.state.value.pendingContactLink == null }
                val added = assertNotNull(store.fetchContact(staged.targetRadioId, publicKey))
                assertEquals(name, added.name)
                assertEquals(type, added.type)
                assertEquals(PacketBuilder.FLOOD_PATH_SENTINEL, added.outPathLength)
                assertEquals(writes + 1, firmware.sent.count { it[0].toInt() == 0x09 })
                assertEquals(added.id, navigation.state.value.pendingContactDetail?.id)
                assertEquals(AppTab.NODES, navigation.state.value.selectedTab)
                compose.onNodeWithTag("entry:${navigation.state.value.activeStack.last().id}").assertIsDisplayed()
            }
        } finally { unmount(); h.container.close() }
    }

    @Test fun mountedCancellationAndOfflineSavedContactViewNeverWriteOrAcknowledgeReplacementLinks() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved identity") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val existing = store.fetchContact(radio, key(20))!!
            mount(h)
            val n = h.container.navigation
            n.stageContactLink(ContactLinkRequest("Cancelled claim", key(30), ContactType.REPEATER))
            val cancelled = assertNotNull(n.state.value.pendingContactLink)
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink)
            assertNull(store.fetchContact(radio, key(30)))
            n.stageContactLink(ContactLinkRequest("Replacement claim", key(31), ContactType.ROOM))
            val replacement = n.state.value.pendingContactLink
            assertFalse(n.completeContactLink(cancelled))
            assertSame(replacement, n.state.value.pendingContactLink)
            n.clearPendingLinks()
            compose.waitForIdle()
            compose.onNodeWithTag("contact-link-confirmation").assertDoesNotExist()
            assertNull(n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingChannelLink)
            assertNull(n.state.value.pendingHashtag)
            h.manager.disconnect()
            h.settle()
            n.stageContactLink(ContactLinkRequest("Claimed alias", existing.publicKey, ContactType.ROOM), radio)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-name").assertTextEquals(existing.displayName)
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { n.state.value.pendingContactLink == null }
            assertEquals(existing.id, n.state.value.pendingContactDetail?.id)
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { unmount(); h.container.close() }
    }

    @Test fun cancelledAndStaleContactLookupsCannotMutateOrNavigateTheReplacementRequest() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Held lookup peer") }
        try {
            connect(h)
            val actual = h.container.nodesFeature
            val radio = assertNotNull(h.appState.currentRadioId)
            val existing = store.fetchContact(radio, key(20))!!
            val entered = CompletableDeferred<Unit>()
            val held = CompletableDeferred<ContactDTO?>()
            val backing = actual.session.servicesDataStore()!!
            val delayed = object : NodesDataStore by backing {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    entered.complete(Unit)
                    return held.await()
                }
            }
            val dependencies = NodesFeatureDependencies(
                object : NodesSession by actual.session {
                    override fun servicesDataStore() = delayed
                },
                actual.uriCodec, actual.timeouts, actual.messages, actual.announcer,
                actual.preferences, actual.clock, actual.locale,
            )
            val n = h.container.navigation
            n.stageContactLink(ContactLinkRequest("Held claim", key(20), ContactType.CHAT))
            val old = n.state.value.pendingContactLink!!
            val holder = ContactLinkConfirmation(dependencies, n, old)
            val job = async { holder.load() }
            entered.await()
            job.cancelAndJoin()
            assertNull(holder.state.value.failure, "Cancellation is not a lookup failure or fake success")
            assertFalse(holder.state.value.loading)
            assertSame(old, n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingContactDetail)

            val staleHolder = ContactLinkConfirmation(dependencies, n, old)
            val staleJob = async { staleHolder.load() }
            testScheduler.runCurrent()
            n.stageContactLink(ContactLinkRequest("Replacement", key(31), ContactType.REPEATER))
            val replacement = n.state.value.pendingContactLink
            held.complete(existing)
            staleJob.await()
            assertFalse(staleHolder.confirm())
            assertSame(replacement, n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingContactDetail)
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { h.container.close() }
    }
}
