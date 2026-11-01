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
import com.meshcoreone.android.app.deeplinks.AppDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.DeepLinkRouteOutcome
import com.meshcoreone.android.app.deeplinks.PendingExternalRoute
import com.meshcoreone.android.app.navigation.*
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.notifications.*
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.runtime.LastConnectionStore
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import com.meshcoreone.android.feature.chats.list.ChatLinkEnvironment
import com.meshcoreone.android.feature.nodes.deps.NodesDataStore
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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

    private fun mount(h: ContainerHarness, nodes: NodesFeatureDependencies = h.container.nodesFeature) = compose.runOnUiThread {
        host.get().setContent {
            Box(Modifier.width(width.value).height(1000.dp)) {
                MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(
                        h.container.navigation, chats = h.container.chatsFeature, nodes = nodes,
                    )
                }
            }
        }
    }

    private fun unmount() = compose.runOnUiThread { host.get().setContent {} }

    private suspend fun connect(h: ContainerHarness, target: ConnectionTarget = h.target()) {
        h.manager.connect(target)
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
                        assertNull(state.pendingChatContact, "The mounted consumer acknowledges the delivery")
                        assertEquals(contact, assertIs<ChatSelection.Direct>(state.chatsSelectedRoute).contact)
                        assertEquals(ChatSelection.Direct(contact), state.chatsSelectedRoute)
                    }
                    is NotificationPayload.ChannelMessage -> {
                        assertEquals(channel, assertIs<ChatSelection.Channel>(state.chatsSelectedRoute).channel)
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

    @Test fun actualPlatformTapSurvivesSyncBeyondTheRetryWindowAndReachesTheMountedDestinationExactlyOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        var readinessSignals = 0
        val h = ContainerHarness(
            this, store, sessionConfiguration = SessionConfiguration(contactStreamInactivityTimeout = 60.0),
            notificationNavigationReady = { readinessSignals++; delivery.retryPendingActions() },
        )
        delivery = AndroidMessagingNotificationDelivery(context, backgroundScope, h.container::handleNotificationResponse)
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Cold notification peer")
            radio.respond = { frame -> if (frame[0].toInt() == 0x04) listOf(Bytes.of(2) + le32(1)) else null }
        }
        try {
            val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
            val contact = ContactDTO(
                radioId = radio, publicKey = key(20), name = "Cold notification peer", lastHeardTimestamp = null,
            )
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
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("callbacks installed while initial contact sync is held") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.container.navigation.state.value.navigationReady &&
                    h.links.radios.last().sent.any { it[0].toInt() == 0x04 }
            }
            testScheduler.advanceTimeBy(16_000)
            testScheduler.runCurrent()
            assertEquals(DeviceConnectionState.SYNCING, h.manager.connectionState)
            assertNull(h.container.navigation.state.value.pendingChatContact)
            val beforeReady = readinessSignals
            val firmware = h.links.radios.last()
            firmware.push(firmware.knownContacts.single())
            firmware.push(Bytes.of(4) + le32(0))
            h.eventually("held sync completes through the real READY edge") {
                h.manager.connectionState == DeviceConnectionState.READY && readinessSignals > beforeReady
            }
            connecting.await()
            mount(h)
            await(h) {
                (h.container.navigation.state.value.chatsSelectedRoute as? ChatSelection.Direct)?.contact?.id == contact.id
            }
            val state = h.container.navigation.state.value
            val synchronizedContact = assertNotNull(store.fetchContact(radio, contact.publicKey))
            assertEquals(contact.id, synchronizedContact.id, "Sync must preserve the notification's original target")
            compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
            assertEquals(synchronizedContact, assertIs<ChatSelection.Direct>(state.chatsSelectedRoute).contact)
            val stack = state.activeStack
            delivery.retryPendingActions()
            h.settle()
            assertEquals(stack, h.container.navigation.state.value.activeStack)
            manager.cancelAll()
        } finally { unmount(); h.container.close() }
    }

    @Test fun externalAndChatLookupResultsCannotCrossARealRadioSwitchOrNavigateAnOldSavedContact() = runTest {
        val deviceA = UUID(1, 1)
        val deviceB = UUID(2, 2)
        val h = ContainerHarness(this, store, identity = { target ->
            if ((target as ConnectionTarget.Bluetooth).deviceId == deviceA) key(7) else key(8)
        })
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Original identity") }
        try {
            for (fromChat in listOf(false, true)) for (found in listOf(false, true)) {
                connect(h, h.target(deviceA))
                val radioA = assertNotNull(h.appState.currentRadioId)
                val original = assertNotNull(store.fetchContact(radioA, key(20)))
                val n = h.container.navigation
                n.clearFailure()
                val entered = CompletableDeferred<RadioId>()
                val lookup = CompletableDeferred<ContactDTO?>()
                val chatRejected = CompletableDeferred<Unit>()
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Original claim", key(20), ContactType.ROOM)
                val external = AppDeepLinkRouter(object : AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, n) {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        entered.complete(radioId)
                        return lookup.await()
                    }
                })
                val chat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                        entered.complete(radioId)
                        return lookup.await()
                    }
                }, backgroundScope, com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ ->
                    if (message == "Contact-link lookup context is no longer current") chatRejected.complete(Unit)
                })
                val routing = if (fromChat) {
                    assertTrue(chat.route(uri))
                    null
                } else async { external.routeExternal(uri) }
                assertEquals(radioA, entered.await())
                h.manager.disconnect()
                h.settle()
                connect(h, h.target(deviceB))
                assertNotEquals(radioA, h.appState.currentRadioId)
                n.stageContactLink(ContactLinkRequest("New radio claim", key(31), ContactType.REPEATER))
                n.selectTab(AppTab.MAP)
                val replacement = assertNotNull(n.state.value.pendingContactLink)
                val stack = n.state.value.activeStack
                val beforeCompletion = n.state.value
                lookup.complete(if (found) original else null)
                if (routing != null) assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, routing.await())
                else h.eventually("stale chat producer explicitly rejects completion") { chatRejected.isCompleted }
                assertEquals(beforeCompletion, n.state.value)
                assertSame(replacement, n.state.value.pendingContactLink)
                assertEquals(stack, n.state.value.activeStack)
                assertEquals(AppTab.MAP, n.state.value.selectedTab)
                assertNull(n.state.value.pendingContactDetail)
                assertNull(store.fetchContact(assertNotNull(h.appState.currentRadioId), key(31)))
                h.manager.disconnect()
                h.settle()
            }
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { h.container.close() }
    }

    @Test fun coldContactDeliverySurvivesTheInitialHandshakeAndRequiresMountedConsent() = runTest {
        val h = ContainerHarness(this, store)
        val handshake = CompletableDeferred<Unit>()
        val chatScheduler = TestCoroutineScheduler()
        val deferredChat = CoroutineScope(SupervisorJob() + StandardTestDispatcher(chatScheduler))
        h.links.configureRadio = { radio -> radio.connectGate = handshake }
        try {
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("physical handshake is held before radio assignment") { h.links.radios.isNotEmpty() }
            val n = h.container.navigation
            val router = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n))
            val pending = PendingExternalRoute(router::routeExternal)
            val uri = h.container.nodesFeature.uriCodec.exportContactUri("Handshake claim", key(30), ContactType.ROOM)
            val delivery = PendingExternalRoute.Delivery("wp302-handshake", uri)
            pending.markReady()
            assertEquals(
                PendingExternalRoute.SubmitOutcome.Routed(DeepLinkRouteOutcome.ConfirmationStaged),
                pending.submit(delivery),
            )
            val unbound = assertNotNull(n.state.value.pendingContactLink)
            assertNull(unbound.targetRadioId)
            val chatUri = h.container.nodesFeature.uriCodec.exportContactUri("Queued handshake claim", key(31), ContactType.REPEATER)
            assertTrue(ChatLinkRouter(h.container.chatsFeature.list.links, deferredChat).route(chatUri))
            mount(h)
            await(h) { confirmationEnabled() }
            handshake.complete(Unit)
            connecting.await()
            h.settle()
            h.assertReady()
            val rebound = assertNotNull(n.state.value.pendingContactLink)
            assertEquals(h.appState.currentRadioId, rebound.targetRadioId)
            assertEquals(n.state.value.generation, rebound.generation)
            assertEquals(unbound.name, rebound.name)
            assertEquals(unbound.publicKey, rebound.publicKey)
            assertEquals(unbound.type, rebound.type)
            assertNotEquals(unbound.deliveryId, rebound.deliveryId)
            assertFalse(n.completeContactLink(unbound))
            assertNull(pending.snapshot())
            assertEquals(PendingExternalRoute.SubmitOutcome.Duplicate, pending.submit(delivery))
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-name").assertTextEquals("Handshake claim")
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
            assertNull(store.fetchContact(assertNotNull(rebound.targetRadioId), key(30)))
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink)
            chatScheduler.runCurrent()
            val queued = assertNotNull(n.state.value.pendingContactLink)
            assertEquals(key(31), queued.publicKey)
            assertEquals(rebound.targetRadioId, queued.targetRadioId)
            assertEquals(n.state.value.generation, queued.generation)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-name").assertTextEquals("Queued handshake claim")
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink)
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { deferredChat.cancel(); unmount(); h.container.close() }
    }

    @Test fun interleavedNotificationDestinationsRetireEarlierChatHandoffsBeforeMountedEffectsRun() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Interleaved peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val channel = ChannelDTO(radioId = radio, index = 3u, name = "Interleaved channel")
            store.saveChannel(channel)
            val n = h.container.navigation
            val retainedRoom = RemoteNodeSessionDTO(
                radioId = radio, publicKey = key(40), name = "Retained room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = true,
            )
            n.navigateToRoom(retainedRoom)
            h.settle()
            val listNavigation = h.container.chatsFeature.list.navigation
            assertEquals(retainedRoom, listNavigation.pendingRoomSession.value)
            n.navigateToContactDetail(contact)
            h.settle()
            assertEquals(retainedRoom, n.state.value.pendingRoomSession, "Plain contact navigation preserves Room context")
            assertNull(listNavigation.pendingRoomSession.value, "The inactive Chats list cannot consume Room context")
            assertTrue(n.back())
            n.selectTab(AppTab.CHATS)
            val firstMessage = UUID.randomUUID()
            val latestMessage = UUID.randomUUID()
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), firstMessage), null, null,
            )))
            assertEquals(contact, n.state.value.pendingChatContact)
            assertNull(n.state.value.pendingRoomSession, "A newer notification replaces the earlier delivery handoff")
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.ChannelMessage(radio, channel.index, UUID.randomUUID()), null, null,
            )))
            assertNull(n.state.value.pendingChatContact)
            assertEquals(channel, n.state.value.pendingChannel)
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), latestMessage), null, null,
            )))
            assertNull(n.state.value.pendingChannel)
            assertEquals(contact, n.state.value.pendingChatContact)
            assertEquals(latestMessage, n.state.value.pendingScrollToMessageID)
            val chatsStack = n.state.value.activeStack
            h.container.connectionPort.updateDevice(assertNotNull(h.appState.connectedDevice).copy(manualAddContacts = true))
            h.eventually("interleaved discovery uses the actual manual-add device preference") {
                h.appState.connectedDevice?.manualAddContacts == true
            }
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.NewContact(EntityKey(radio, contact.id)), null, null,
            )))
            assertEquals(AppTab.NODES, n.state.value.selectedTab)
            assertNull(n.state.value.pendingChatContact)
            assertNull(n.state.value.pendingChannel)
            assertNull(n.state.value.pendingRoomSession)
            mount(h)
            h.settle()
            compose.waitForIdle()
            assertEquals(AppTab.NODES, n.state.value.selectedTab)
            assertEquals(NavigationDestination.Discovery, n.state.value.activeStack.last().destination)
            compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
            assertEquals(chatsStack, n.state.value.stacks.getValue(AppTab.CHATS))
            compose.runOnUiThread { n.selectTab(AppTab.CHATS) }
            h.settle()
            compose.waitForIdle()
            assertEquals(chatsStack, n.state.value.activeStack)
            assertEquals(contact, assertIs<ChatSelection.Direct>(n.state.value.chatsSelectedRoute).contact)
            compose.onNodeWithTag("entry:${chatsStack.last().id}").assertIsDisplayed()
            assertTrue(n.back())
            assertEquals(channel, assertIs<ChatSelection.Channel>(n.state.value.chatsSelectedRoute).channel)
        } finally { unmount(); h.container.close() }
    }

    @Test fun sameRadioSessionReplacementRetiresCapturedAndInFlightCallbacksWithoutLosingSelection() = runTest {
        val uiScheduler = TestCoroutineScheduler()
        val mainScope = CoroutineScope(backgroundScope.coroutineContext + uiScheduler + StandardTestDispatcher(uiScheduler))
        var hold = false
        var holdReplacement = false
        var heldKey: EntityKey? = null
        val entered = CompletableDeferred<Unit>()
        val lookup = CompletableDeferred<ContactDTO?>()
        val replacementHandshake = CompletableDeferred<Unit>()
        val retiredTransport = CompletableDeferred<Unit>()
        val contactLookupRelease = CompletableDeferred<Unit>()
        val contactReads = java.util.concurrent.atomic.AtomicInteger()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                if (hold && key == heldKey) {
                    entered.complete(Unit)
                    return lookup.await()
                }
                return store.fetchContact(key)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence, mainScope = mainScope)
        h.links.configureRadio = { radio ->
            if (holdReplacement) radio.connectGate = replacementHandshake
            radio.knownContacts += contactFrame(key(20), "Retired callback target")
            radio.knownContacts += contactFrame(key(21), "Retained selection")
        }
        try {
            val started = h.container.start()
            h.eventually("production activation completes") {
                uiScheduler.runCurrent()
                started.isCompleted
            }
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("activated graph finishes real initial synchronization") {
                uiScheduler.runCurrent()
                connecting.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.navigation.state.value.navigationReady
            }
            connecting.await()
            val radio = assertNotNull(h.appState.currentRadioId)
            val oldSession = assertNotNull(h.sessions.current)
            val oldCallback = assertNotNull(oldSession.notificationService.onNotificationTapped)
            val oldGeneration = h.container.navigation.state.value.generation
            val target = assertNotNull(store.fetchContact(radio, key(20)))
            heldKey = EntityKey(radio, target.id)
            val selected = assertNotNull(store.fetchContact(radio, key(21)))
            val n = h.container.navigation
            val conversation = ChatConversationID.dm(radio, selected.id)
            h.draftStore.setDraft("saved before rebuild", conversation)
            n.navigateToChat(selected)
            mount(h)
            val composerMatcher = hasSetTextAction() and hasContentDescription(
                host.get().getString(com.meshcoreone.android.core.l10n.generated.AppChatsStrings.chatsInputAccessibilityLabel),
                substring = true,
            )
            h.eventually("retained composer mounts") {
                uiScheduler.runCurrent()
                compose.waitForIdle()
                compose.onAllNodes(composerMatcher and hasText("saved before rebuild")).fetchSemanticsNodes().size == 1
            }
            val composer = compose.onNode(composerMatcher).assertTextEquals("saved before rebuild")
            composer.performClick().performTextReplacement("typing during rebuild")
            val composerId = composer.fetchSemanticsNode().id
            h.draftStore.setDraft("independent persisted draft", conversation)
            val stack = n.state.value.activeStack
            hold = true
            val inFlight = async { oldCallback(EntityKey(radio, target.id)) }
            entered.await()
            suspend fun heldContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                val found = store.fetchContact(radioId, publicKey)
                contactReads.incrementAndGet()
                contactLookupRelease.await()
                return found
            }
            val external = AppDeepLinkRouter(object : AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, n) {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = heldContact(radioId, publicKey)
            })
            val chat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = heldContact(radioId, publicKey)
            }, backgroundScope)
            val urls = listOf(20, 30).map {
                h.container.nodesFeature.uriCodec.exportContactUri("Retired contact lookup", key(it), ContactType.REPEATER)
            }
            val externalRoutes = urls.map { url -> async { external.routeExternal(url) } }
            urls.forEach { assertTrue(chat.route(it)) }
            h.eventually("both producers hold found and absent contact lookups") { contactReads.get() == 4 }
            hold = false
            holdReplacement = true
            val deviceId = assertNotNull(h.appState.connectedDevice).id
            h.links.radios.first().disconnectGate = retiredTransport
            val rebuilding = async { h.manager.rebuildSession(deviceId) }
            h.eventually("graph withdrawn while physical teardown keeps the actual DISCONNECTED edge current") {
                h.sessions.current == null && h.links.radios.first().disconnecting &&
                    h.manager.connectionState == DeviceConnectionState.DISCONNECTED
            }
            uiScheduler.runCurrent()
            h.eventually("activated withdrawal retirement runs after the disconnected edge") {
                uiScheduler.runCurrent()
                oldSession.notificationService.onNotificationTapped == null && !n.state.value.navigationReady
            }
            assertEquals(stack, n.state.value.activeStack)
            assertEquals(ChatSelection.Direct(selected), n.state.value.chatsSelectedRoute)
            assertEquals(composerId, composer.fetchSemanticsNode().id)
            composer.assertIsFocused().assertTextEquals("typing during rebuild")
            assertEquals("independent persisted draft", h.draftStore.draft(conversation))
            retiredTransport.complete(Unit)
            h.eventually("retired graph withdrawn while replacement handshake is held") {
                h.links.radios.size == 2 && h.sessions.current == null
            }
            h.eventually("retired navigation binding is detached during the held handshake") {
                oldSession.notificationService.onNotificationTapped == null && !n.state.value.navigationReady
            }
            assertNull(oldSession.notificationService.onNotificationTapped)
            assertFalse(n.state.value.navigationReady)
            assertEquals(oldGeneration, n.state.value.generation)
            val replacementGap = n.state.value
            assertFalse(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, target.id), UUID.randomUUID()), null, null,
            )), "The production dispatcher must retain the response until the new graph is ready")
            lookup.complete(target)
            contactLookupRelease.complete(Unit)
            testScheduler.runCurrent()
            inFlight.await()
            externalRoutes.forEach { assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, it.await()) }
            testScheduler.runCurrent()
            oldCallback(EntityKey(radio, target.id))
            assertEquals(replacementGap, n.state.value, "Retired callbacks cannot commit before the next binding")
            assertEquals(stack, n.state.value.activeStack)
            replacementHandshake.complete(Unit)
            h.eventually("activated replacement becomes ready") {
                uiScheduler.runCurrent()
                rebuilding.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                    n.state.value.navigationReady
            }
            rebuilding.await()
            h.settle()
            h.assertReady()
            assertNotSame(oldSession, h.sessions.current)
            assertNull(oldSession.notificationService.onNotificationTapped)
            assertTrue(n.state.value.generation > oldGeneration)
            assertEquals(stack, n.state.value.activeStack)
            assertEquals(ChatSelection.Direct(selected), n.state.value.chatsSelectedRoute)
            uiScheduler.runCurrent()
            compose.waitForIdle()
            assertEquals(composerId, composer.fetchSemanticsNode().id)
            composer.assertIsFocused().assertTextEquals("typing during rebuild")
            assertEquals("independent persisted draft", h.draftStore.draft(conversation))
            oldCallback(EntityKey(radio, target.id))
            assertEquals(stack, n.state.value.activeStack)
            assertEquals(ChatSelection.Direct(selected), n.state.value.chatsSelectedRoute)
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, target.id), UUID.randomUUID()), null, null,
            )))
            compose.waitForIdle()
            assertEquals(ChatSelection.Direct(target), n.state.value.chatsSelectedRoute)
            compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
        } finally {
            retiredTransport.complete(Unit)
            replacementHandshake.complete(Unit)
            contactLookupRelease.complete(Unit)
            lookup.complete(null)
            unmount()
            val closing = async { h.container.close() }
            h.eventually("activated process cleanup drains the controlled UI queue") {
                uiScheduler.runCurrent()
                closing.isCompleted
            }
            closing.await()
        }
    }

    @Test fun contactLinksFromBothProducersSurviveProvisionalFirstGraphBeforeTokenPublication() = runTest {
        val publication = CompletableDeferred<Unit>()
        val saving = CompletableDeferred<Unit>()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveDevice(device: DeviceDTO) {
                saving.complete(Unit)
                publication.await()
                store.saveDevice(device)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
        val saved = ContactDTO(
            radioId = radio, publicKey = key(20), name = "Persisted provisional peer", lastHeardTimestamp = null,
        )
        h.links.configureRadio = { firmware -> firmware.knownContacts += contactFrame(saved.publicKey, saved.name) }
        try {
            store.saveDevice(DeviceDTO(id = radio.value, radioId = radio, publicKey = key(7), nodeName = "Cached radio"))
            store.saveContact(saved)
            val persisted = assertNotNull(store.fetchContact(radio, saved.publicKey))
            LastConnectionStore(h.preferences, SystemRuntimeClock()).persist(radio.value, radio, "Cached radio")
            h.container.connectionPort.refreshLastConnection()
            h.settle()
            val connecting = async { h.manager.connect(h.target(radio.value)) }
            h.eventually("real graph published before suspended device persistence completes") {
                saving.isCompleted && h.sessions.current != null && h.manager.snapshot.value.token == null
            }
            val provisional = assertNotNull(h.sessions.current)
            val n = h.container.navigation
            assertNull(n.state.value.radioId)
            assertEquals(radio, h.appState.currentRadioId)
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n))
            val chat = ChatLinkRouter(h.container.chatsFeature.list.links, backgroundScope)
            mount(h)
            for (fromChat in listOf(false, true)) {
                val foundUri = h.container.nodesFeature.uriCodec.exportContactUri("Untrusted alias", saved.publicKey, ContactType.ROOM)
                if (fromChat) {
                    assertTrue(chat.route(foundUri))
                    h.eventually("chat producer navigates persisted identity during provisional publication") {
                        n.state.value.activeStack.last().destination == NavigationDestination.ContactDetail(persisted)
                    }
                } else {
                    val found = async { external.routeExternal(foundUri) }
                    h.eventually("external saved lookup completes during provisional publication") { found.isCompleted }
                    assertEquals(DeepLinkRouteOutcome.Navigated, found.await())
                }
                compose.waitForIdle()
                assertEquals(NavigationDestination.ContactDetail(persisted), n.state.value.activeStack.last().destination)
                compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
                n.back()
                val request = ContactLinkRequest("Provisional ${if (fromChat) "chat" else "external"}", key(30), ContactType.REPEATER)
                val absentUri = h.container.nodesFeature.uriCodec.exportContactUri(request.name, request.publicKey, request.type)
                if (fromChat) {
                    assertTrue(chat.route(absentUri))
                    h.eventually("chat producer stages provisional confirmation") { n.state.value.pendingContactLink != null }
                } else {
                    val absent = async { external.routeExternal(absentUri) }
                    h.eventually("external absent lookup completes during provisional publication") { absent.isCompleted }
                    assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, absent.await())
                }
                val pending = assertNotNull(n.state.value.pendingContactLink)
                assertEquals(
                    request.copy(targetRadioId = radio, generation = n.state.value.generation, deliveryId = pending.deliveryId),
                    pending,
                )
                compose.waitForIdle()
                compose.onNodeWithTag("contact-link-confirmation").assertIsDisplayed()
                compose.onNodeWithTag("contact-link-cancel").performClick()
                assertNull(n.state.value.pendingContactLink)
                assertSame(provisional, h.sessions.current)
                assertNull(h.manager.snapshot.value.token)
            }
            publication.complete(Unit)
            h.eventually("provisional first graph becomes the same published ready graph") { connecting.isCompleted }
            connecting.await()
            h.settle()
            h.assertReady()
            assertSame(provisional, h.sessions.current)
            assertNull(n.state.value.pendingContactLink)
            assertTrue(h.links.radios.all { firmware -> firmware.sent.none { it[0].toInt() == 0x09 } })
        } finally {
            publication.complete(Unit)
            unmount()
            h.container.close()
        }
    }

    @Test fun retiredImportCannotNavigateAfterCommittedRowLookupDuringReplacementHandshake() = runTest {
        val importedKey = key(30)
        val importedName = "Committed retired import"
        val importedLookup = CompletableDeferred<Unit>()
        val releaseLookup = CompletableDeferred<Unit>()
        val replacementHandshake = CompletableDeferred<Unit>()
        var holdLookup = false
        var replacing = false
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                val found = store.fetchContact(radioId, publicKey)
                if (holdLookup && publicKey == importedKey && found != null) {
                    importedLookup.complete(Unit)
                    releaseLookup.await()
                }
                return found
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { firmware ->
            firmware.knownContacts += contactFrame(key(21), "Retained contact")
            firmware.respond = { frame -> if (frame[0].toInt() == 0x09) listOf(Bytes.of(0)) else null }
            if (replacing) {
                firmware.connectGate = replacementHandshake
                firmware.knownContacts += contactFrame(importedKey, importedName)
            }
        }
        try {
            connect(h)
            val n = h.container.navigation
            val radio = assertNotNull(h.appState.currentRadioId)
            val selected = assertNotNull(store.fetchContact(radio, key(21)))
            n.navigateToContactDetail(selected)
            mount(h)
            n.stageContactLink(ContactLinkRequest(importedName, importedKey, ContactType.CHAT))
            val pending = assertNotNull(n.state.value.pendingContactLink)
            await(h) { confirmationEnabled() }
            holdLookup = true
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { importedLookup.isCompleted }
            assertNotNull(store.fetchContact(radio, importedKey), "The ACK-backed write really committed")
            replacing = true
            val deviceId = assertNotNull(h.appState.connectedDevice).id
            val rebuilding = async { h.manager.rebuildSession(deviceId) }
            h.eventually("import's old graph withdraws before replacement handshake") {
                h.links.radios.size == 2 && h.sessions.current == null
            }
            releaseLookup.complete(Unit)
            compose.waitForIdle()
            h.eventually("retired import reports disconnection instead of navigating") {
                compose.onAllNodesWithTag("contact-link-error").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(pending.deliveryId, n.state.value.pendingContactLink?.deliveryId)
            assertEquals(selected.id, n.state.value.selectedContact?.id)
            replacementHandshake.complete(Unit)
            rebuilding.await()
            h.settle()
            h.assertReady()
            assertNotEquals(pending.deliveryId, n.state.value.pendingContactLink?.deliveryId)
            assertEquals(selected.id, n.state.value.selectedContact?.id)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink)
            assertEquals(selected.id, n.state.value.selectedContact?.id)
        } finally {
            releaseLookup.complete(Unit)
            replacementHandshake.complete(Unit)
            unmount()
            h.container.close()
        }
    }

    @Test fun savedOfflineContactDestinationSurvivesCompatibleInitialAssignmentButNotAnotherRadio() = runTest {
        for (compatible in listOf(true, false)) {
            val h = ContainerHarness(this, store, identity = { if (compatible) key(7) else key(8) })
            val deviceId = DeviceIdentity.deriveUUID(key(7))
            val radio = RadioId(deviceId)
            val saved = ContactDTO(
                radioId = radio, publicKey = key(20), name = "Saved offline identity", lastHeardTimestamp = null,
            )
            val handshake = CompletableDeferred<Unit>()
            h.links.configureRadio = { firmware ->
                firmware.connectGate = handshake
                firmware.knownContacts += contactFrame(key(20), saved.name)
            }
            try {
                store.saveDevice(DeviceDTO(id = deviceId, radioId = radio, publicKey = key(7), nodeName = "Cached radio"))
                store.saveContact(saved)
                val persisted = assertNotNull(store.fetchContact(radio, saved.publicKey))
                LastConnectionStore(h.preferences, SystemRuntimeClock()).persist(deviceId, radio, "Cached radio")
                h.container.connectionPort.refreshLastConnection()
                val targetId = if (compatible) deviceId else DeviceIdentity.deriveUUID(key(8))
                val connecting = async { h.manager.connect(h.target(targetId)) }
                h.eventually("handshake held with actual persisted offline radio") { h.links.radios.isNotEmpty() }
                val n = h.container.navigation
                assertNull(n.state.value.radioId)
                assertEquals(radio, h.appState.currentRadioId)
                val router = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n))
                val pending = PendingExternalRoute(router::routeExternal)
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Claimed alias", saved.publicKey, ContactType.ROOM)
                pending.markReady()
                assertEquals(
                    PendingExternalRoute.SubmitOutcome.Routed(DeepLinkRouteOutcome.Navigated),
                    pending.submit(PendingExternalRoute.Delivery("wp302-saved-offline", uri)),
                )
                val stack = n.state.value.activeStack
                assertEquals(persisted.id, n.state.value.selectedContact?.id)
                mount(h)
                compose.onNodeWithTag("entry:${stack.last().id}").assertIsDisplayed()
                handshake.complete(Unit)
                connecting.await()
                h.settle()
                h.assertReady()
                compose.waitForIdle()
                assertNull(pending.snapshot())
                assertNull(n.state.value.pendingContactLink)
                if (compatible) {
                    assertEquals(stack, n.state.value.activeStack)
                    assertEquals(persisted.id, n.state.value.selectedContact?.id)
                    compose.onNodeWithTag("entry:${stack.last().id}").assertIsDisplayed()
                } else {
                    assertNotEquals(radio, n.state.value.radioId)
                    assertNull(n.state.value.selectedContact)
                    assertEquals(1, n.state.value.activeStack.size)
                }
                assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
            } finally { unmount(); h.container.close() }
        }
    }

    @Test fun identicalDeliveriesReplaceMountedHoldersAndCancelHeldImportsWithoutLateAcknowledgement() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x09) emptyList() else null }
        }
        try {
            connect(h)
            mount(h)
            val n = h.container.navigation
            val raw = ContactLinkRequest("Identical claim", key(30), ContactType.REPEATER)
            n.stageContactLink(raw)
            val first = assertNotNull(n.state.value.pendingContactLink)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            val firmware = h.links.radios.last()
            h.eventually("real import waits for the held radio ACK") {
                firmware.sent.count { it[0].toInt() == 0x09 } == 1
            }
            compose.runOnUiThread { n.stageContactLink(raw) }
            val second = assertNotNull(n.state.value.pendingContactLink)
            assertNotEquals(first.deliveryId, second.deliveryId)
            assertFalse(n.completeContactLink(first), "Old dismissal must not clear an identical replacement")
            compose.waitForIdle()
            firmware.push(Bytes.of(0))
            await(h) { confirmationEnabled() }
            assertSame(second, n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingContactDetail, "The disposed import cannot navigate after a late ACK")
            compose.runOnUiThread {
                n.clearPendingContactLink()
                n.stageContactLink(raw)
            }
            val third = assertNotNull(n.state.value.pendingContactLink)
            assertNotEquals(second.deliveryId, third.deliveryId)
            assertFalse(n.completeContactLink(second), "An unobserved clear/restage must retire the old holder")
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-cancel").performClick()
            assertNull(n.state.value.pendingContactLink, "The current identical replacement remains dismissible")
            assertNull(n.state.value.pendingContactDetail)
            assertEquals(1, firmware.sent.count { it[0].toInt() == 0x09 })

            firmware.respond = { frame -> if (frame[0].toInt() == 0x09) listOf(Bytes.of(0)) else null }
            compose.runOnUiThread {
                n.stageContactLink(raw)
                n.clearPendingContactLink()
                n.stageContactLink(raw)
            }
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { n.state.value.pendingContactLink == null }
            assertEquals(key(30), n.state.value.pendingContactDetail?.publicKey)
        } finally { unmount(); h.container.close() }
    }

    @Test fun mountedRetryMarksEveryLoadBusyAndCannotOverwriteANewerResult() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Retry saved identity") }
        try {
            connect(h)
            val actual = h.container.nodesFeature
            val saved = assertNotNull(store.fetchContact(assertNotNull(h.appState.currentRadioId), key(20)))
            var attempts = 0
            val entered = CompletableDeferred<Unit>()
            val retry = CompletableDeferred<ContactDTO?>()
            val delayed = object : NodesDataStore by assertNotNull(actual.session.servicesDataStore()) {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    attempts++
                    if (attempts == 1) throw IllegalStateException("Temporary lookup failure")
                    if (retry.isCompleted) return store.fetchContact(radioId, publicKey)
                    check(attempts == 2) { "An overlapping retry reached persistence" }
                    entered.complete(Unit)
                    return retry.await()
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
            n.stageContactLink(ContactLinkRequest("Retry claim", key(20), ContactType.ROOM))
            mount(h, dependencies)
            await(h) { compose.onAllNodesWithTag("contact-link-error").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { entered.isCompleted }
            compose.onNodeWithTag("contact-link-confirm").assertIsNotEnabled().performClick()
            compose.waitForIdle()
            assertEquals(2, attempts)
            retry.complete(saved)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-error").assertDoesNotExist()
            compose.onNodeWithTag("contact-link-name").assertTextEquals(saved.displayName)
            compose.onNodeWithTag("contact-link-confirm").performClick()
            await(h) { n.state.value.pendingContactLink == null }
            assertEquals(saved.id, n.state.value.selectedContact?.id)
            assertTrue(h.links.radios.all { it.sent.none { frame -> frame[0].toInt() == 0x09 } })
        } finally { unmount(); h.container.close() }
    }

    @Test fun aSavedContactLinkRetiresTheMountedPendingImportItReplaces() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Saved destination")
            radio.respond = { frame -> if (frame[0].toInt() == 0x09) emptyList() else null }
        }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val saved = assertNotNull(store.fetchContact(radio, key(20)))
            val n = h.container.navigation
            mount(h)
            n.stageContactLink(ContactLinkRequest("Retired pending import", key(30), ContactType.ROOM))
            val retired = assertNotNull(n.state.value.pendingContactLink)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            val firmware = h.links.radios.last()
            h.eventually("old mounted import waits for radio ACK") { firmware.sent.any { it[0].toInt() == 0x09 } }
            val uri = h.container.nodesFeature.uriCodec.exportContactUri("Claimed saved alias", key(20), ContactType.ROOM)
            assertEquals(
                DeepLinkRouteOutcome.Navigated,
                AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n)).routeExternal(uri),
            )
            assertNull(n.state.value.pendingContactLink)
            compose.waitForIdle()
            compose.onNodeWithTag("contact-link-confirmation").assertDoesNotExist()
            firmware.push(Bytes.of(0))
            h.settle()
            assertFalse(n.completeContactLink(retired))
            assertEquals(saved.id, n.state.value.selectedContact?.id)
            assertEquals(saved.id, assertIs<NavigationDestination.ContactDetail>(
                n.state.value.activeStack.last().destination,
            ).contact.id)
            compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
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
