// PortedFrom: MC1Tests/AppState/NavigationCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Real graph, Room, platform notification receiver and mounted adaptive consumers.
package com.meshcoreone.android.app.container

import android.Manifest
import android.app.NotificationManager
import android.os.Build
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import com.meshcoreone.android.app.state.MessageEvent
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
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import com.meshcoreone.android.feature.chats.list.ChatLinkEnvironment
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import com.meshcoreone.android.feature.nodes.deps.NodesDataStore
import com.meshcoreone.android.feature.nodes.deps.NodesContactService
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import com.meshcoreone.android.platform.notifications.messaging.AndroidMessagingNotificationDelivery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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

    @Test fun disconnectedRoomNotificationFromChatsRootShowsAuthenticationWithoutOpeningTheRoom() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = key(40), name = "Root room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false, unreadCount = 3,
            )
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
                lastHeardTimestamp = null,
            ))
            store.saveRemoteNodeSessionDTO(room)
            val navigation = h.container.navigation
            mount(h)
            h.settle()
            compose.waitForIdle()
            val previous = navigation.state.value.stacks.getValue(AppTab.CHATS)
            assertEquals(AppTab.CHATS, navigation.state.value.selectedTab)
            assertEquals(1, previous.size)
            assertNull(navigation.state.value.chatsSelectedRoute)
            val payload = NotificationPayload.RoomMessage(room.name, EntityKey(radio, room.id), UUID.randomUUID())

            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, null, null)))
            await(h) { compose.onAllNodesWithTag("room-authentication-cancel").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("room-authentication-sheet").assertIsDisplayed()
            assertEquals(room.id, navigation.state.value.pendingRoomAuthentication?.id)
            assertEquals(previous, navigation.state.value.stacks.getValue(AppTab.CHATS))
            assertNull(navigation.state.value.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)

            compose.onNodeWithTag("room-authentication-cancel").performClick()
            await(h) { navigation.state.value.pendingRoomAuthentication == null }
            assertEquals(previous, navigation.state.value.stacks.getValue(AppTab.CHATS))
            assertNull(navigation.state.value.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)
        } finally {
            unmount()
            h.container.close()
        }
    }

    @Test fun disconnectedRoomNotificationKeepsThePreviousChatAndUnreadStateAcrossResizeAndCancel() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Prior peer") }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val peer = assertNotNull(store.fetchContact(radio, key(20)))
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = key(40), name = "Resize room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false, unreadCount = 3,
            )
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
                lastHeardTimestamp = null,
            ))
            store.saveRemoteNodeSessionDTO(room)
            val n = h.container.navigation
            n.navigateToChat(peer)
            mount(h)
            h.settle()
            compose.waitForIdle()
            val previous = n.state.value.stacks.getValue(AppTab.CHATS)
            val payload = NotificationPayload.RoomMessage(room.name, EntityKey(radio, room.id), UUID.randomUUID())

            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, null, null)))
            await(h) { compose.onAllNodesWithTag("room-authentication-cancel").fetchSemanticsNodes().size == 1 }
            val first = n.state.value
            val firstId = assertNotNull(first.roomAuthenticationDeliveryId)
            assertEquals(previous, first.stacks.getValue(AppTab.CHATS))
            assertEquals(ChatSelection.Direct(peer), first.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)

            compose.runOnUiThread { width.value = 950.dp }
            await(h) { n.state.value.widthDp >= 950f }
            compose.onNodeWithTag("room-authentication-sheet").assertIsDisplayed()
            assertEquals(firstId, n.state.value.roomAuthenticationDeliveryId)
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            await(h) { compose.onAllNodesWithTag("room-authentication-password").fetchSemanticsNodes().size == 1 }
            compose.onNodeWithTag("room-authentication-password").performTextInput("first attempt")
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, null, null)))
            val replacementId = assertNotNull(n.state.value.roomAuthenticationDeliveryId)
            assertNotEquals(firstId, replacementId)
            assertFalse(n.dismissRoomAuthentication(firstId))
            await(h) {
                compose.onAllNodesWithTag("room-authentication-password").fetchSemanticsNodes().singleOrNull()
                    ?.config?.getOrNull(SemanticsProperties.EditableText)?.text == ""
            }
            val sheet = compose.runOnIdle {
                assertNotNull(ShadowDialog.getLatestDialog()).also { assertTrue(it.isShowing) }
            }
            repeat(2) {
                compose.runOnIdle {
                    if (sheet.isShowing) {
                        sheet.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
                        sheet.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
                    }
                }
                compose.waitForIdle()
                assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            }
            await(h) { n.state.value.pendingRoomAuthentication == null }
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(ChatSelection.Direct(peer), n.state.value.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)
        } finally {
            compose.runOnUiThread { width.value = 390.dp }
            unmount()
            h.container.close()
        }
    }

    @Test fun mountedRoomListAuthenticatesBeforeOpeningAndSurfacesLoginFailure() = runTest {
        val h = ContainerHarness(this, store)
        val roomKey = key(40)
        val allowLogin = java.util.concurrent.atomic.AtomicBoolean(false)
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Prior peer")
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x1a -> listOf(
                        Bytes.of(0x06, 0, 0xaa, 0xbb, 0xcc, 0xdd) + le32(1_000),
                        Bytes.of(if (allowLogin.get()) 0x85 else 0x86, 1) + roomKey.prefix(6),
                    )
                    0x1b -> listOf(Bytes.of(1, 2))
                    else -> null
                }
            }
        }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val peer = assertNotNull(store.fetchContact(radio, key(20)))
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = roomKey, name = "List room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false, unreadCount = 4,
            )
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
                outPathLength = 0u, lastHeardTimestamp = null,
            ))
            store.saveRemoteNodeSessionDTO(room)
            val n = h.container.navigation
            n.navigateToChat(peer)
            compose.runOnUiThread { width.value = 950.dp }
            mount(h)
            await(h) { compose.onAllNodesWithContentDescription("List room", substring = true).fetchSemanticsNodes().isNotEmpty() }
            val previous = n.state.value.stacks.getValue(AppTab.CHATS)
            compose.onNodeWithContentDescription("List room", substring = true).performClick()
            await(h) { compose.onAllNodesWithTag("room-authentication-password").fetchSemanticsNodes().size == 1 }
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(4L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)
            compose.onNodeWithTag("room-authentication-password").performTextInput("test password")
            compose.onNodeWithTag("room-authentication-submit").performClick()
            await(h) { compose.onAllNodesWithTag("room-authentication-error").fetchSemanticsNodes().size == 1 }
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(4L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)

            allowLogin.set(true)
            compose.onNodeWithTag("room-authentication-submit").performClick()
            await(h) { (n.state.value.chatsSelectedRoute as? ChatSelection.Room)?.session?.isConnected == true }
            await(h) { n.state.value.pendingRoomAuthentication == null }
            val roomEntry = n.state.value.activeStack.last()
            await(h) { compose.onAllNodesWithTag("entry:${roomEntry.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("entry:${roomEntry.id}").assertIsDisplayed()
            h.eventually("mounted room detail marks messages read after authentication") {
                kotlinx.coroutines.runBlocking { store.fetchRemoteNodeSession(EntityKey(radio, room.id))?.unreadCount == 0L }
            }
            assertTrue(h.links.radios.last().sent.any { it[0].toInt() == 0x1a })
            assertTrue(n.back())
            assertEquals(previous, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(ChatSelection.Direct(peer), n.state.value.chatsSelectedRoute)
        } finally {
            compose.runOnUiThread { width.value = 390.dp }
            unmount()
            h.container.close()
        }
    }

    @Test fun cachedRoomAuthenticationWaitsForReadyAndCanRetry() = runTest {
        val roomKey = key(40)
        val roomName = "Syncing room"
        val h = ContainerHarness(
            this, store, sessionConfiguration = SessionConfiguration(contactStreamInactivityTimeout = 60.0),
        )
        h.links.configureRadio = { radio ->
            radio.knownContacts += Bytes.of(3) + roomKey + Bytes.of(3, 0, 0) + Bytes(ByteArray(64)) +
                Bytes.utf8(roomName).paddedOrTruncated(32) + le32(1) + le32(0) + le32(0) + le32(1)
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x04 -> listOf(Bytes.of(2) + le32(1))
                    0x1a -> listOf(
                        Bytes.of(0x06, 0, 0xaa, 0xbb, 0xcc, 0xdd) + le32(1_000),
                        Bytes.of(0x85, 1) + roomKey.prefix(6),
                    )
                    0x1b -> listOf(Bytes.of(1, 2))
                    else -> null
                }
            }
        }
        val connecting = backgroundScope.async { h.manager.connect(h.target()) }
        try {
            h.eventually("session is bound while contact sync is held") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.container.navigation.state.value.boundSessionToken == h.sessions.current?.token &&
                    h.sessions.current != null && h.links.radios.last().sent.any { it[0].toInt() == 0x04 }
            }
            val initialSession = assertNotNull(h.sessions.current)
            val radio = initialSession.token.radioId
            val room = RemoteNodeSessionDTO(
                radioId = radio, publicKey = roomKey, name = roomName,
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false, unreadCount = 3,
            )
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = roomKey, name = roomName, typeRawValue = 3u,
                outPathLength = 0u, lastHeardTimestamp = null,
            ))
            store.saveRemoteNodeSessionDTO(room)
            mount(h)
            compose.waitUntil(10_000) {
                testScheduler.runCurrent()
                compose.onAllNodesWithContentDescription(roomName, substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription(roomName, substring = true).performClick()
            compose.waitUntil(10_000) {
                testScheduler.runCurrent()
                compose.onAllNodesWithTag("room-authentication-password").fetchSemanticsNodes().size == 1
            }
            compose.onNodeWithTag("room-authentication-password").performTextInput("test password")
            compose.onNodeWithTag("room-authentication-submit").performClick()
            compose.waitUntil(10_000) {
                testScheduler.runCurrent()
                compose.onAllNodesWithTag("room-authentication-error").fetchSemanticsNodes().size == 1
            }
            val firmware = h.links.radios.last()
            assertEquals(DeviceConnectionState.SYNCING, h.manager.connectionState)
            assertTrue(firmware.sent.none { it[0].toInt() == 0x1a || it[0].toInt() == 0x1b })
            assertEquals(room.id, h.container.navigation.state.value.pendingRoomAuthentication?.id)
            assertNull(h.container.navigation.state.value.chatsSelectedRoute)
            assertEquals(3L, assertNotNull(store.fetchRemoteNodeSession(EntityKey(radio, room.id))).unreadCount)

            firmware.push(firmware.knownContacts.single())
            firmware.push(Bytes.of(4) + le32(0))
            h.eventually("held contact sync reaches READY") { h.manager.connectionState == DeviceConnectionState.READY }
            connecting.await()
            assertSame(initialSession, h.sessions.current)
            compose.onNodeWithTag("room-authentication-submit").performClick()
            await(h) { (h.container.navigation.state.value.chatsSelectedRoute as? ChatSelection.Room)?.session?.isConnected == true }
            assertTrue(firmware.sent.any { it[0].toInt() == 0x1a })
            assertNull(h.container.navigation.state.value.pendingRoomAuthentication)
        } finally {
            connecting.cancelAndJoin()
            unmount()
            h.container.close()
        }
    }

    @Test fun staleRoomAuthenticationCannotUseASameRadioSuccessor() = runTest {
        val h = ContainerHarness(this, store)
        val target = h.target()
        try {
            connect(h, target)
            val first = assertNotNull(h.sessions.current)
            val room = RemoteNodeSessionDTO(
                radioId = first.token.radioId, publicKey = key(40), name = "Stale room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false,
            )
            val contact = ContactDTO(
                radioId = room.radioId, publicKey = room.publicKey, name = room.name, typeRawValue = 3u,
                lastHeardTimestamp = null,
            )
            h.container.navigation.requestRoomAuthentication(room)
            val staged = h.container.navigation.state.value
            assertEquals(first.token, staged.roomAuthenticationSessionToken)

            h.manager.disconnect()
            h.settle()
            connect(h, target)
            val successor = assertNotNull(h.sessions.current)
            assertEquals(first.token.radioId, successor.token.radioId)
            assertNotEquals(first.token, successor.token)
            val feature = h.container.chatsFeature
            val isCurrent = feature.captureNavigationSession(staged.roomAuthenticationSessionToken)
            val dependencies = feature.roomForAuthentication(room, staged.roomAuthenticationSessionToken, isCurrent)
            assertFalse(isCurrent())
            val failure = assertFailsWith<com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException> {
                dependencies.service.join(contact, "secret", false)
            }
            assertEquals(com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError.NotConnected, failure.error)
            assertTrue(h.links.radios.last().sent.none { it[0].toInt() == 0x1a })
        } finally { h.container.close() }
    }

    @Test fun rewiringSameSessionRestoresTheRoomAuthenticationOwner() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val navigation = h.container.navigation
            navigation.clearPerRadioSelection()
            assertNull(navigation.state.value.boundSessionToken)
            h.appState.wireServicesIfConnected()
            h.settle()
            assertSame(session, h.sessions.current)
            assertEquals(session.token, navigation.state.value.boundSessionToken)

            val room = RemoteNodeSessionDTO(
                radioId = session.token.radioId, publicKey = key(40), name = "Rewired room",
                role = RemoteNodeRole.ROOM_SERVER, isConnected = false,
            )
            navigation.requestRoomAuthentication(room)
            val token = navigation.state.value.roomAuthenticationSessionToken
            assertEquals(session.token, token)
            assertTrue(h.container.chatsFeature.captureNavigationSession(token)())
        } finally { h.container.close() }
    }

    @Test fun aTapWhoseLookupOutlivesItsSessionIsNotAcknowledgedAndCanReachTheReplacement() = runTest {
        var hold = false
        var heldKey: EntityKey? = null
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<ContactDTO?>()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                if (hold && key == heldKey) {
                    entered.complete(Unit)
                    return release.await()
                }
                return store.fetchContact(key)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Retired tap target") }
        try {
            val target = h.target()
            connect(h, target)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            heldKey = EntityKey(radio, contact.id)
            val response = NotificationResponse(
                NotificationPayload.DirectMessage(assertNotNull(heldKey), UUID.randomUUID()), null, null,
            )
            hold = true
            val pending = async { h.container.handleNotificationResponse(response) }
            entered.await()
            hold = false
            h.manager.disconnect()
            h.eventually("retired notification graph is withdrawn") {
                h.sessions.current == null && !h.container.navigation.state.value.navigationReady
            }
            val beforeCompletion = h.container.navigation.state.value
            release.complete(contact)
            assertFalse(pending.await(), "A stale navigation outcome must leave the tap unacknowledged")
            assertEquals(beforeCompletion, h.container.navigation.state.value)

            connect(h, target)
            assertTrue(h.container.handleNotificationResponse(response))
            assertEquals(ChatSelection.Direct(contact), h.container.navigation.state.value.chatsSelectedRoute)
        } finally {
            release.complete(null)
            h.container.close()
        }
    }

    @Test fun cancellationAfterARealNotificationCommitDoesNotReplayTheQueuedTap() = runTest {
        verifyNotificationCommitWins(observerCancels = true)
    }

    @Test fun failedObserverAfterARealNotificationCommitDoesNotReplayTheQueuedTap() = runTest {
        verifyNotificationCommitWins(observerCancels = false)
    }

    private suspend fun TestScope.verifyNotificationCommitWins(observerCancels: Boolean) {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Committed tap target") }
        var attempts = 0
        var binding: NotificationNavigationBinding? = null
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val session = assertNotNull(h.sessions.current)
            val observed = CompletableDeferred<NavigationOutcome>()
            val cancellation = CompletableDeferred<kotlinx.coroutines.CancellationException>()
            val acknowledgement = CompletableDeferred<Boolean>()
            binding = NotificationNavigationBinding(
                session.notificationService, h.container.navigation, PersistenceNavigationLookup(store),
                connectedDevice = { h.appState.connectedDevice },
                onOutcome = {
                    observed.complete(it)
                    if (observerCancels) throw kotlinx.coroutines.CancellationException("Observer cancelled after navigation")
                    throw IllegalStateException("Observer failed after navigation")
                },
                isCurrentSession = { h.container.isCurrentSession(session) },
            )
            val delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
                attempts++
                try {
                    h.container.handleNotificationResponse(response).also { acknowledgement.complete(it) }
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    cancellation.complete(failure)
                    throw failure
                }
            }
            manager.cancelAll()
            delivery.registerCategories(SnapshotList.empty())
            delivery.post(NotificationRequest(
                NotificationId("wp302-committed-tap"), NotificationCategory.DIRECT_MESSAGE, contact.name, "Committed tap",
                false, null, null, NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()),
            ))
            manager.activeNotifications.single().notification.contentIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            h.eventually("the real tap commits before the observer exits") {
                if (observerCancels) cancellation.isCompleted else acknowledgement.isCompleted
            }
            assertEquals(NavigationOutcome.Navigated, observed.await())
            if (observerCancels) {
                assertEquals("Observer cancelled after navigation", cancellation.await().message)
            } else {
                assertTrue(acknowledgement.await())
            }
            val state = h.container.navigation.state.value
            assertEquals(ChatSelection.Direct(contact), state.chatsSelectedRoute)
            assertEquals(1, attempts)

            testScheduler.runCurrent()
            delivery.retryPendingActions()
            h.settle()
            assertEquals(1, attempts, "A committed tap must not be delivered again")
            assertEquals(state.activeStack, h.container.navigation.state.value.activeStack)
        } finally {
            binding?.close()
            manager.cancelAll()
            h.container.close()
        }
    }

    @Test fun currentSessionOptionalCustomAndActionCallbacksKeepTheirExistingSemantics() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val session = assertNotNull(h.sessions.current)
            val contact = EntityKey(radio, UUID.randomUUID())
            val message = UUID.randomUUID()
            val payload = NotificationPayload.DirectMessage(contact, message)
            val tap = NotificationResponse(payload, null, null)
            val original = session.notificationService.onNotificationTapped

            session.notificationService.onNotificationTapped = null
            assertTrue(h.container.handleNotificationResponse(tap))
            var taps = 0
            session.notificationService.onNotificationTapped = { assertEquals(contact, it); taps++ }
            assertTrue(h.container.handleNotificationResponse(tap))
            assertEquals(1, taps)
            session.notificationService.onNotificationTapped = { throw IllegalStateException("custom callback failed") }
            assertTrue(h.container.handleNotificationResponse(tap))
            session.notificationService.onNotificationTapped = {
                throw kotlinx.coroutines.CancellationException("custom callback cancelled")
            }
            assertFailsWith<kotlinx.coroutines.CancellationException> { h.container.handleNotificationResponse(tap) }
            session.notificationService.onNotificationTapped = original

            val calls = mutableListOf<String>()
            session.notificationService.onQuickReply = { key, text ->
                assertEquals(contact, key)
                calls += "reply:$text"
            }
            session.notificationService.onMarkAsRead = { key, id ->
                assertEquals(contact, key)
                assertEquals(message, id)
                calls += "read"
            }
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, NotificationAction.REPLY, "hello")))
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(payload, NotificationAction.MARK_READ, null)))
            assertTrue(h.container.handleNotificationResponse(
                NotificationResponse(NotificationPayload.LowBattery(5), null, null),
            ))
            assertEquals(listOf("reply:hello", "read"), calls)
        } finally { h.container.close() }
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

    @Test fun actualReadyDuringTheFinalRetryDelayDeliversTheQueuedTapOnce() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        var readinessSignals = 0
        val routeAttempts = mutableListOf<Long>()
        val h = ContainerHarness(
            this, store, sessionConfiguration = SessionConfiguration(contactStreamInactivityTimeout = 60.0),
            notificationNavigationReady = { readinessSignals++; delivery.retryPendingActions() },
        )
        delivery = AndroidMessagingNotificationDelivery(context, backgroundScope) { response ->
            routeAttempts += testScheduler.currentTime
            h.container.handleNotificationResponse(response)
        }
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Final delay peer")
            radio.respond = { frame -> if (frame[0].toInt() == 0x04) listOf(Bytes.of(2) + le32(1)) else null }
        }
        try {
            manager.cancelAll()
            val radio = RadioId(DeviceIdentity.deriveUUID(key(7)))
            val contact = ContactDTO(
                radioId = radio, publicKey = key(20), name = "Final delay peer", lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            delivery.registerCategories(SnapshotList.empty())
            delivery.post(NotificationRequest(
                NotificationId("wp302-final-delay"), NotificationCategory.DIRECT_MESSAGE, contact.name, "Queued tap",
                false, null, null, NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()),
            ))
            manager.activeNotifications.single().notification.contentIntent.send()
            shadowOf(android.os.Looper.getMainLooper()).idle()
            testScheduler.runCurrent()
            val firstAttempt = routeAttempts.single()
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("callbacks installed while initial contact sync is held") {
                h.manager.connectionState == DeviceConnectionState.SYNCING &&
                    h.container.navigation.state.value.navigationReady &&
                    h.links.radios.last().sent.any { it[0].toInt() == 0x04 }
            }
            val lastAttempt = firstAttempt + 14_750
            assertTrue(testScheduler.currentTime <= lastAttempt)
            testScheduler.advanceTimeBy(lastAttempt - testScheduler.currentTime)
            testScheduler.runCurrent()
            assertEquals(60, routeAttempts.size)
            assertEquals(DeviceConnectionState.SYNCING, h.manager.connectionState)
            assertNull(h.container.navigation.state.value.pendingChatContact)

            val beforeReady = readinessSignals
            val firmware = h.links.radios.last()
            firmware.push(firmware.knownContacts.single())
            firmware.push(Bytes.of(4) + le32(0))
            h.eventually("held sync completes during the final retry delay", virtualBudgetMillis = 100) {
                h.manager.connectionState == DeviceConnectionState.READY && readinessSignals > beforeReady
            }
            val retryDeadline = firstAttempt + 15_000
            assertTrue(testScheduler.currentTime < retryDeadline, "READY must occur during the final delay")
            assertEquals(60, routeAttempts.size, "No new drain starts before the old owner finishes")
            testScheduler.advanceTimeBy(retryDeadline - testScheduler.currentTime)
            testScheduler.runCurrent()
            connecting.await()

            mount(h)
            await(h) {
                (h.container.navigation.state.value.chatsSelectedRoute as? ChatSelection.Direct)?.contact?.id == contact.id
            }
            val state = h.container.navigation.state.value
            val synchronizedContact = assertNotNull(store.fetchContact(radio, contact.publicKey))
            assertEquals(contact.id, synchronizedContact.id)
            assertEquals(synchronizedContact, assertIs<ChatSelection.Direct>(state.chatsSelectedRoute).contact)
            compose.onNodeWithTag("entry:${state.activeStack.last().id}").assertIsDisplayed()
            assertEquals(61, routeAttempts.size)
            val stack = state.activeStack
            delivery.retryPendingActions()
            h.settle()
            assertEquals(61, routeAttempts.size)
            assertEquals(stack, h.container.navigation.state.value.activeStack)
        } finally {
            manager.cancelAll()
            unmount()
            h.container.close()
        }
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

    @Test fun contactProducersCaptureTheCommittedGenerationBeforeItsPublication() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Published contact") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            val previousGeneration = navigation.state.value.generation
            val holdingBinding = ThreadLocal<Boolean>()
            navigation.installCommitBoundary { action ->
                h.manager.withNavigationCommitBoundary {
                    h.sessions.withNavigationCommitBoundary { current ->
                        if (current == null) action() else current.withNavigationCommitBoundary(action)
                    }
                }
                if (holdingBinding.get() == true && navigation.currentState.generation == previousGeneration + 1) {
                    entered.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Publication was not released" }
                }
            }
            val binding = async(Dispatchers.IO) {
                holdingBinding.set(true)
                try {
                    navigation.bindNotificationSession(radio, h.appState.connectedDevice?.publicKey, session.token) {
                        h.container.isCurrentSession(session)
                    }
                } finally {
                    holdingBinding.remove()
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "The session commit must precede its publication")
            val currentGeneration = navigation.currentState.generation
            assertEquals(previousGeneration + 1, currentGeneration)
            assertEquals(previousGeneration, navigation.state.value.generation)

            val externalEnvironment = ContainerDeepLinkEnvironment(h.container, navigation)
            val chatEnvironment = h.container.chatsFeature.list.links
            assertEquals(currentGeneration, externalEnvironment.contactLinkGeneration)
            assertEquals(currentGeneration, chatEnvironment.contactLinkGeneration)
            val savedUri = h.container.nodesFeature.uriCodec.exportContactUri("Saved claim", key(20), ContactType.ROOM)
            val external = async(Dispatchers.IO) { AppDeepLinkRouter(externalEnvironment).routeExternal(savedUri) }
            assertEquals(DeepLinkRouteOutcome.Navigated, external.await())
            assertEquals(contact.id, navigation.currentState.selectedContact?.id)

            val unknownKey = key(30)
            val pendingUri = h.container.nodesFeature.uriCodec.exportContactUri("New claim", unknownKey, ContactType.REPEATER)
            val chat = ChatLinkRouter(chatEnvironment, backgroundScope)
            assertTrue(chat.route(pendingUri))
            h.eventually("chat link stages against the committed, unpublished generation") {
                navigation.currentState.pendingContactLink?.publicKey == unknownKey
            }
            val pending = assertNotNull(navigation.currentState.pendingContactLink)
            assertEquals("New claim", pending.name)
            assertEquals(ContactType.REPEATER, pending.type)
            assertEquals(radio, pending.targetRadioId)
            assertEquals(currentGeneration, pending.generation)
            assertEquals(previousGeneration, navigation.state.value.generation)

            release.countDown()
            assertTrue(binding.await())
            assertEquals(navigation.currentState, navigation.state.value)
            assertEquals(pending, navigation.state.value.pendingContactLink)
        } finally {
            release.countDown()
            h.container.close()
        }
    }

    @Test fun newerContactLookupsAndDirectStagingSupersedeOlderInFlightLookups() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = {
            it.knownContacts += contactFrame(key(20), "Older contact")
            it.knownContacts += contactFrame(key(21), "Newer contact")
        }
        try {
            connect(h)
            val radio = assertNotNull(h.appState.currentRadioId)
            val oldContact = assertNotNull(store.fetchContact(radio, key(20)))
            val newContact = assertNotNull(store.fetchContact(radio, key(21)))
            val navigation = h.container.navigation
            for ((olderFromChat, newerRoute) in listOf(
                false to "chat", true to "external", false to "direct", true to "direct",
            )) for (olderFound in listOf(false, true)) {
                navigation.clearPendingLinks()
                navigation.clearFailure()
                val current = assertNotNull(h.sessions.current)
                assertTrue(navigation.bindNotificationSession(radio, current.navigationPublicKey, current.token) {
                    h.container.isCurrentSession(current)
                })
                val entered = CompletableDeferred<RadioId>()
                val release = CompletableDeferred<ContactDTO?>()
                val rejected = CompletableDeferred<Unit>()
                suspend fun fetch(radioId: RadioId, publicKey: Bytes): ContactDTO? =
                    if (publicKey == key(20)) {
                        entered.complete(radioId)
                        release.await()
                    } else store.fetchContact(radioId, publicKey)
                val external = AppDeepLinkRouter(object :
                    AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, navigation) {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = fetch(radioId, publicKey)
                })
                val chat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = fetch(radioId, publicKey)
                }, backgroundScope, com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ ->
                    if (message == "Contact-link lookup context is no longer current") rejected.complete(Unit)
                })
                val oldUri = h.container.nodesFeature.uriCodec.exportContactUri("Older claim", key(20), ContactType.ROOM)
                val older = if (olderFromChat) {
                    assertTrue(chat.route(oldUri))
                    null
                } else async { external.routeExternal(oldUri) }
                assertEquals(radio, entered.await())

                val newKey = if (newerRoute == "external") key(21) else key(30)
                val newUri = h.container.nodesFeature.uriCodec.exportContactUri("Newer claim", newKey, ContactType.REPEATER)
                when (newerRoute) {
                    "chat" -> {
                        assertTrue(chat.route(newUri))
                        h.eventually("newer chat contact confirmation is staged") {
                            navigation.state.value.pendingContactLink?.publicKey == newKey
                        }
                    }
                    "external" -> assertEquals(DeepLinkRouteOutcome.Navigated, external.routeExternal(newUri))
                    else -> navigation.stageContactLink(
                        ContactLinkRequest("Newer claim", newKey, ContactType.REPEATER), radio,
                    )
                }
                if (newerRoute == "external") {
                    assertEquals(newContact, navigation.state.value.pendingContactDetail)
                } else {
                    val staged = assertNotNull(navigation.state.value.pendingContactLink)
                    assertEquals("Newer claim", staged.name)
                    assertEquals(ContactType.REPEATER, staged.type)
                    assertEquals(newKey, staged.publicKey)
                    assertEquals(radio, staged.targetRadioId)
                }
                val beforeCompletion = navigation.state.value
                release.complete(if (olderFound) oldContact else null)
                if (older != null) assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, older.await())
                else h.eventually("older chat contact lookup is rejected") { rejected.isCompleted }
                assertEquals(beforeCompletion, navigation.state.value, "The older lookup must not change navigation")
            }
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

    @Test fun preConnectionContactLookupSurvivesRetryWithinTheFirstConnectionSubmission() = runTest {
        val h = ContainerHarness(this, store)
        val chatScheduler = TestCoroutineScheduler()
        val deferredChat = CoroutineScope(SupervisorJob() + StandardTestDispatcher(chatScheduler))
        var attempts = 0
        h.links.configureRadio = { radio ->
            attempts++
            if (attempts == 1) radio.connectFailure = IllegalStateException("First attempt failed")
        }
        try {
            val n = h.container.navigation
            val uri = h.container.nodesFeature.uriCodec.exportContactUri("Retry claim", key(31), ContactType.REPEATER)
            assertTrue(ChatLinkRouter(h.container.chatsFeature.list.links, deferredChat).route(uri))
            assertNull(n.state.value.pendingContactLink)
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("the first submission succeeds after retry", virtualBudgetMillis = 30_000) {
                connecting.isCompleted
            }
            connecting.await()
            h.settle()
            h.assertReady()
            assertTrue(attempts > 1)

            chatScheduler.runCurrent()
            val staged = assertNotNull(n.state.value.pendingContactLink)
            assertEquals("Retry claim", staged.name)
            assertEquals(key(31), staged.publicKey)
            assertEquals(ContactType.REPEATER, staged.type)
            assertEquals(h.appState.currentRadioId, staged.targetRadioId)
            assertEquals(n.state.value.generation, staged.generation)
        } finally {
            deferredChat.cancel()
            h.container.close()
        }
    }

    @Test fun interleavedNotificationDestinationsRetireEarlierChatHandoffsBeforeMountedEffectsRun() = runTest {
        val h = ContainerHarness(this, store)
        var navigationSubscription: kotlinx.coroutines.Job? = null
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
            navigationSubscription = listNavigation.pendingRoomSession.launchIn(backgroundScope)
            h.settle()
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
            assertNull(n.state.value.pendingScrollToMessageID, "Only reaction taps request a message anchor")
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
        } finally { navigationSubscription?.cancelAndJoin(); unmount(); h.container.close() }
    }

    @Test fun retiredDisconnectCannotCancelCollectorsWiredAfterItsNavigationCommit() = runTest {
        val secret = Bytes(ByteArray(16) { (it + 1).toByte() })
        val h = ContainerHarness(this, store)
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        val paused = AtomicBoolean(false)
        val events = CopyOnWriteArrayList<MessageEvent>()
        val eventCollector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            h.appState.messageEventStream.events().collect { events += it }
        }
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(20), "Replacement peer")
            radio.respond = { command ->
                if (command.size > 1 && command[0] == 0x1f.toUByte() && command[1] == 0.toUByte()) {
                    listOf(Bytes.of(0x12, 0) + Bytes.utf8("Test").paddedOrTruncated(32) + secret)
                } else null
            }
        }
        try {
            connect(h)
            val retired = assertNotNull(h.sessions.current)
            val deviceId = assertNotNull(h.appState.connectedDevice).id
            val n = h.container.navigation
            n.installCommitBoundary { action ->
                val before = n.currentState
                h.manager.withNavigationCommitBoundary {
                    h.sessions.withNavigationCommitBoundary { current ->
                        if (current == null) action() else current.withNavigationCommitBoundary(action)
                    }
                }
                val after = n.currentState
                if (before.boundSessionToken == retired.token && after.boundSessionToken == null &&
                    after.generation == before.generation + 1 && paused.compareAndSet(false, true)
                ) {
                    committed.countDown()
                    check(release.await(30, TimeUnit.SECONDS)) { "Retiring cleanup was not released" }
                }
            }

            val disconnect = async(Dispatchers.IO) { h.appState.disconnect() }
            h.eventually("retiring navigation cleanup commits before job cancellation") { committed.count == 0L }
            assertFalse(disconnect.isCompleted)
            val reconnect = async { h.manager.connect(h.target(deviceId)) }
            h.eventually("replacement graph wires before retiring cleanup resumes") {
                val current = h.sessions.current
                reconnect.isCompleted && current != null && current !== retired &&
                    h.manager.connectionState == DeviceConnectionState.READY &&
                    n.currentState.boundSessionToken == current.token && n.currentState.navigationReady
            }
            reconnect.await()
            val replacement = assertNotNull(h.sessions.current)
            assertEquals(retired.token.radioId, replacement.token.radioId)
            assertNotEquals(retired.token, replacement.token)
            replacement.channelService.syncChannels(replacement.token.radioId, 1u, usePipelinedRead = false)
            h.eventually("replacement sync and message collectors are subscribed") {
                replacement.syncCoordinator.dataEventBroadcaster.subscriberCount >= 2 &&
                    h.appState.activeSessionJobCount > 0 && h.appState.messageEventDispatcher.activeJobCount > 0
            }
            val sessionJobs = h.appState.activeSessionJobCount
            val messageJobs = h.appState.messageEventDispatcher.activeJobCount

            release.countDown()
            h.eventually("retiring disconnect completes") { disconnect.isCompleted }
            disconnect.await()
            h.settle()
            assertEquals(replacement.token, n.currentState.boundSessionToken)
            assertEquals(sessionJobs, h.appState.activeSessionJobCount)
            assertEquals(messageJobs, h.appState.messageEventDispatcher.activeJobCount)
            val contactsVersion = h.appState.contactsVersion.value
            replacement.syncCoordinator.dataEventBroadcaster.yield(SyncDataEvent.ContactsChanged)
            h.eventually("replacement contact collector still receives events") {
                h.appState.contactsVersion.value > contactsVersion
            }

            val radio = h.links.radios.last()
            val previousResponse = radio.respond
            val frames = ConcurrentLinkedQueue(listOf(
                Bytes.of(0x11, 20, 0, 0, 0, 1, 0) +
                    le32(EPOCH.epochSecond) + Bytes.utf8("Alice: collector-after-replacement"),
            ))
            radio.respond = { command ->
                if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a))
                else previousResponse(command)
            }
            try {
                assertEquals(1L, replacement.messagePollingService.pollAllMessages())
            } finally {
                radio.respond = previousResponse
            }
            h.eventually("replacement radio message reaches the app event stream") {
                events.filterIsInstance<MessageEvent.ChannelMessageReceived>().any {
                    it.message.radioId == replacement.token.radioId &&
                        it.message.text == "collector-after-replacement"
                }
            }

            val contact = assertNotNull(store.fetchContact(replacement.token.radioId, key(20)))
            assertTrue(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(replacement.token.radioId, contact.id), UUID.randomUUID()),
                null, null,
            )))
            mount(h)
            await(h) { (n.state.value.chatsSelectedRoute as? ChatSelection.Direct)?.contact?.id == contact.id }
            compose.onNodeWithTag("entry:${n.state.value.activeStack.last().id}").assertIsDisplayed()
        } finally {
            release.countDown()
            eventCollector.cancelAndJoin()
            unmount()
            h.container.close()
        }
    }

    @Test fun unboundNavigationDisconnectStillRetiresCurrentSessionCollectors() = runTest {
        val h = ContainerHarness(this, store)
        try {
            connect(h)
            val current = assertNotNull(h.sessions.current)
            assertTrue(h.appState.activeSessionJobCount > 0)
            assertTrue(h.appState.messageEventDispatcher.activeJobCount > 0)
            h.container.navigation.manuallyDisconnect()
            assertNull(h.container.navigation.currentState.boundSessionToken)
            assertEquals(current.token, h.sessions.current?.token)

            h.appState.disconnect()
            h.eventually("unbound navigation disconnect retires the captured session collectors") {
                h.appState.activeSessionJobCount == 0 && h.appState.messageEventDispatcher.activeJobCount == 0
            }
        } finally {
            h.container.close()
        }
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
            h.eventually("replacement runtime publishes before its queued navigation binding") {
                val replacement = h.sessions.current
                replacement != null && replacement !== oldSession &&
                    h.manager.snapshot.value.token == replacement.token
            }
            val replacement = assertNotNull(h.sessions.current)
            assertNotEquals(replacement.token, n.currentState.boundSessionToken)
            assertEquals(oldGeneration, n.currentState.generation)
            val beforeMixedCapture = n.currentState
            val captured = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(30), it)
            }
            assertEquals(ContactLinkCaptureDisposition.StaleContext, captured.disposition)
            val mixedReads = AtomicInteger()
            val rejectedCaptures = CopyOnWriteArrayList<String>()
            val mixedChat = ChatLinkRouter(object : ChatLinkEnvironment by h.container.chatsFeature.list.links {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    mixedReads.incrementAndGet()
                    return store.fetchContact(radioId, publicKey)
                }
            }, backgroundScope, com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ ->
                rejectedCaptures += message
            })
            assertTrue(mixedChat.route(urls.last()))
            testScheduler.runCurrent()
            assertEquals(0, mixedReads.get(), "A mixed initiating context must not access the store")
            assertEquals(listOf("Contact-link initiating context is not current"), rejectedCaptures)
            assertEquals(beforeMixedCapture, n.currentState)
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

    @Test fun capturedContactRetryValidationKeepsGraphAndRevisionIdentity() = runTest {
        val h = ContainerHarness(this, store)
        val target = h.target()
        try {
            connect(h, target)
            val first = assertNotNull(h.sessions.current)
            val navigation = h.container.navigation
            val original = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(20), it)
            }
            val afterCapture = navigation.currentState
            repeat(2) { assertTrue(h.container.isContactLinkContextCurrent(original, key(20))) }
            assertFalse(h.container.isContactLinkContextCurrent(original, key(21)))
            assertSame(afterCapture, navigation.currentState)
            assertSame(afterCapture, navigation.state.value)

            val newer = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(20), it)
            }
            val afterNewerCapture = navigation.currentState
            assertFalse(h.container.isContactLinkContextCurrent(original, key(20)))
            assertTrue(h.container.isContactLinkContextCurrent(newer, key(20)))
            assertSame(afterNewerCapture, navigation.currentState)

            h.manager.disconnect()
            h.settle()
            connect(h, target)
            val replacement = assertNotNull(h.sessions.current)
            assertNotSame(first, replacement)
            assertNotEquals(first.token, replacement.token)
            assertEquals(first.token.radioId, replacement.token.radioId)
            val beforeStaleCheck = navigation.currentState
            assertFalse(h.container.isContactLinkContextCurrent(newer, key(20)))
            assertSame(beforeStaleCheck, navigation.currentState)
            val fresh = ContactLinkPublications().use {
                h.container.captureContactLinkContext(key(20), it)
            }
            assertTrue(h.container.isContactLinkContextCurrent(fresh, key(20)))
        } finally { h.container.close() }
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

    @Test fun retiredImportReportsDisconnectionAfterAckBeforeRowLookup() = runTest {
        val acknowledged = CompletableDeferred<Unit>()
        val releaseAcknowledgement = CompletableDeferred<Unit>()
        val replacementHandshake = CompletableDeferred<Unit>()
        var replacing = false
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { firmware ->
            firmware.respond = { frame -> if (frame[0].toInt() == 0x09) listOf(Bytes.of(0)) else null }
            if (replacing) firmware.connectGate = replacementHandshake
        }
        try {
            connect(h)
            val actual = h.container.nodesFeature
            val realService = assertNotNull(actual.session.contactService())
            val delayedService = object : NodesContactService by realService {
                override suspend fun addOrUpdateContact(radioId: RadioId, contact: ContactFrame) {
                    realService.addOrUpdateContact(radioId, contact)
                    acknowledged.complete(Unit)
                    releaseAcknowledgement.await()
                }
            }
            val dependencies = NodesFeatureDependencies(
                object : NodesSession by actual.session {
                    override fun contactService() = delayedService
                },
                actual.uriCodec, actual.timeouts, actual.messages, actual.announcer,
                actual.preferences, actual.clock, actual.locale,
            )
            val navigation = h.container.navigation
            mount(h, dependencies)
            navigation.stageContactLink(ContactLinkRequest("Acknowledged import", key(30), ContactType.CHAT))
            val pending = assertNotNull(navigation.state.value.pendingContactLink)
            await(h) { confirmationEnabled() }
            compose.onNodeWithTag("contact-link-confirm").performClick()
            h.eventually("import has a real ACK before replacement") { acknowledged.isCompleted }
            replacing = true
            val deviceId = assertNotNull(h.appState.connectedDevice).id
            val rebuilding = async { h.manager.rebuildSession(deviceId) }
            h.eventually("old graph withdraws before replacement handshake") {
                h.links.radios.size == 2 && h.sessions.current == null
            }
            releaseAcknowledgement.complete(Unit)
            compose.waitForIdle()
            h.eventually("retired ACK-backed import reports disconnection") {
                compose.onAllNodesWithTag("contact-link-error").fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(pending.deliveryId, navigation.state.value.pendingContactLink?.deliveryId)
            assertNull(navigation.state.value.pendingContactDetail)
            replacementHandshake.complete(Unit)
            rebuilding.await()
            h.settle()
            h.assertReady()
        } finally {
            releaseAcknowledgement.complete(Unit)
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

    @Test fun teardownBetweenValidityAndCommitRejectsContactLookupAndNotificationAcknowledgement() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Commit boundary peer") }
        val entered = CountDownLatch(2)
        val release = CountDownLatch(1)
        var binding: NotificationNavigationBinding? = null
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            val generation = navigation.state.value.generation
            val captured = h.container.captureContactLinkSession(generation)
            val contactChecks = AtomicInteger()
            val notificationChecks = AtomicInteger()
            fun pauseAfterCheck(checks: AtomicInteger, pauseAt: Int, valid: () -> Boolean): Boolean {
                val result = valid()
                if (checks.incrementAndGet() == pauseAt) {
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS)) { "Retirement did not release the commit check" }
                }
                return result
            }
            binding = NotificationNavigationBinding(
                session.notificationService, navigation, PersistenceNavigationLookup(store),
                connectedDevice = { h.appState.connectedDevice }, onOutcome = {},
                isCurrentSession = {
                    pauseAfterCheck(notificationChecks, 2) { h.container.isCurrentSession(session) }
                },
            )
            val lookup = async(Dispatchers.IO) {
                navigation.completeContactLookup(
                    ContactLinkRequest("Stale contact", contact.publicKey, ContactType.CHAT),
                    contact, radio, generation, radio,
                ) { pauseAfterCheck(contactChecks, 1, captured) }
            }
            val tap = async(Dispatchers.IO) {
                h.container.handleNotificationResponse(NotificationResponse(
                    NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()), null, null,
                ))
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Both routes must pause after their validity checks")

            val disconnect = async { h.manager.disconnect() }
            h.eventually("teardown completes for the captured session") { disconnect.isCompleted }
            disconnect.await()
            assertNull(h.manager.snapshot.value.token)
            val afterRetirement = navigation.state.value
            release.countDown()
            assertFalse(lookup.await())
            assertFalse(tap.await(), "A stale notification must remain unacknowledged")
            assertEquals(afterRetirement, navigation.state.value)
        } finally {
            release.countDown()
            binding?.close()
            h.container.close()
        }
    }

    @Test fun queuedTapSurvivesRetirementBetweenItsValidityCheckAndCommit() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        val h = ContainerHarness(this, store, notificationNavigationReady = { delivery.retryPendingActions() })
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Queued retirement target") }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val rejected = CompletableDeferred<Unit>()
        val accepted = CopyOnWriteArrayList<UUID>()
        var binding: NotificationNavigationBinding? = null
        try {
            delivery = AndroidMessagingNotificationDelivery(context, dispatcherScope) { response ->
                val handled = h.container.handleNotificationResponse(response)
                val message = (response.payload as NotificationPayload.DirectMessage).messageID
                if (handled) accepted += message else rejected.complete(Unit)
                handled
            }
            val target = h.target()
            connect(h, target)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val checks = AtomicInteger()
            binding = NotificationNavigationBinding(
                session.notificationService, h.container.navigation, PersistenceNavigationLookup(store),
                connectedDevice = { h.appState.connectedDevice }, onOutcome = {},
                isCurrentSession = {
                    val valid = h.container.isCurrentSession(session)
                    if (checks.incrementAndGet() == 2) {
                        entered.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Retirement did not release the queued tap" }
                    }
                    valid
                },
            )
            manager.cancelAll()
            delivery.registerCategories(SnapshotList.empty())
            val first = UUID.randomUUID()
            suspend fun enqueue(id: String, message: UUID) {
                manager.cancelAll()
                delivery.post(NotificationRequest(
                    NotificationId(id), NotificationCategory.DIRECT_MESSAGE, contact.name, "Queued tap",
                    false, null, null, NotificationPayload.DirectMessage(EntityKey(radio, contact.id), message),
                ))
                manager.activeNotifications.single().notification.contentIntent.send()
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }
            enqueue("wp302-retired-tap", first)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "Tap must pause after its pre-commit validity check")
            val disconnect = async { h.manager.disconnect() }
            h.eventually("the tap's session retires before its commit") { disconnect.isCompleted }
            disconnect.await()
            h.settle()
            val afterRetirement = h.container.navigation.state.value
            release.countDown()
            h.eventually("the dispatcher receives the stale rejection") { rejected.isCompleted }
            assertEquals(afterRetirement, h.container.navigation.state.value)
            assertTrue(accepted.isEmpty())

            connect(h, target)
            h.eventually("the retained tap reaches the replacement session") { accepted.contains(first) }
            assertEquals(ChatSelection.Direct(contact), h.container.navigation.state.value.chatsSelectedRoute)
            delivery.retryPendingActions()
            val second = UUID.randomUUID()
            enqueue("wp302-retirement-sentinel", second)
            h.eventually("a later queued tap completes after the retained tap") { accepted.contains(second) }
            assertEquals(listOf(first, second), accepted.toList())
        } finally {
            release.countDown()
            binding?.close()
            dispatcherScope.cancel()
            manager.cancelAll()
            h.container.close()
        }
    }

    @Test fun queuedMarkReadWaitsForItsActionForwarder() = runTest {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        if (Build.VERSION.SDK_INT >= 33) shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        val manager = context.getSystemService(NotificationManager::class.java)
        val dispatcherScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        lateinit var delivery: AndroidMessagingNotificationDelivery
        val h = ContainerHarness(this, store, notificationNavigationReady = { delivery.retryPendingActions() })
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Queued action target") }
        val rejected = CompletableDeferred<Unit>()
        val accepted = CopyOnWriteArrayList<UUID>()
        val forwarded = CopyOnWriteArrayList<Pair<EntityKey, UUID>>()
        try {
            delivery = AndroidMessagingNotificationDelivery(context, dispatcherScope) { response ->
                val handled = h.container.handleNotificationResponse(response)
                val message = (response.payload as NotificationPayload.DirectMessage).messageID
                if (handled) accepted += message else rejected.complete(Unit)
                handled
            }
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val key = EntityKey(radio, contact.id)
            session.notificationService.onMarkAsRead = null
            delivery.registerCategories(SnapshotList.of(NotificationCategoryDefinition(
                NotificationCategory.DIRECT_MESSAGE,
                SnapshotList.of(NotificationActionDefinition(NotificationAction.MARK_READ, "Mark read", null, null)),
            )))
            suspend fun enqueue(id: String, message: UUID) {
                manager.cancelAll()
                delivery.post(NotificationRequest(
                    NotificationId(id), NotificationCategory.DIRECT_MESSAGE, contact.name, "Queued action",
                    false, null, null, NotificationPayload.DirectMessage(key, message),
                ))
                manager.activeNotifications.single().notification.actions.single().actionIntent.send()
                shadowOf(android.os.Looper.getMainLooper()).idle()
            }

            val first = UUID.randomUUID()
            enqueue("wp302-missing-action", first)
            h.eventually("the dispatcher retains the action while its forwarder is missing") { rejected.isCompleted }
            assertTrue(accepted.isEmpty())
            session.notificationService.onMarkAsRead = { target, message -> forwarded += target to message }
            delivery.retryPendingActions()
            h.eventually("the retained action reaches the installed forwarder") { accepted.contains(first) }
            val second = UUID.randomUUID()
            enqueue("wp302-action-sentinel", second)
            h.eventually("a later action drains after the retained action") { accepted.contains(second) }
            assertEquals(listOf(first, second), accepted.toList())
            assertEquals(listOf(key to first, key to second), forwarded.toList())
        } finally {
            dispatcherScope.cancel()
            manager.cancelAll()
            h.container.close()
        }
    }

    @Test fun contactCommitWinsWhenTeardownStartsInsideItsSerializedCommit() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Commit winner") }
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            val generation = navigation.state.value.generation
            val captured = h.container.captureContactLinkSession(generation)
            val checks = AtomicInteger()
            val route = async(Dispatchers.IO) {
                navigation.completeContactLookup(
                    ContactLinkRequest("Winner", contact.publicKey, ContactType.CHAT),
                    contact, radio, generation, radio,
                ) {
                    val result = captured()
                    if (checks.incrementAndGet() == 2) {
                        inside.countDown()
                        check(release.await(10, TimeUnit.SECONDS)) { "Commit boundary was not released" }
                    }
                    result
                }
            }
            assertTrue(inside.await(10, TimeUnit.SECONDS))
            val disconnectStarted = CountDownLatch(1)
            val disconnect = async(Dispatchers.IO) {
                disconnectStarted.countDown()
                h.manager.disconnect()
            }
            assertTrue(disconnectStarted.await(10, TimeUnit.SECONDS))
            assertEquals(session.token, h.manager.snapshot.value.token)
            release.countDown()
            assertTrue(route.await())
            h.eventually("disconnect completes after the committed route") { disconnect.isCompleted }
            disconnect.await()
            assertEquals(contact.id, navigation.state.value.selectedContact?.id)
        } finally {
            release.countDown()
            h.container.close()
        }
    }

    @Test fun retiredConfirmationCannotOpenItsLoadedContactButCanStillBeDismissed() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val heldStore = object : PersistenceStoreProtocol by store {
            override suspend fun fetchConnectedRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
                return store.fetchConnectedRemoteNodeSessions(radioId)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = heldStore)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Retired confirmation") }
        try {
            connect(h)
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            navigation.stageContactLink(ContactLinkRequest("Claim", contact.publicKey, ContactType.ROOM), radio)
            val link = assertNotNull(navigation.state.value.pendingContactLink)
            val confirmation = ContactLinkConfirmation(h.container.nodesFeature, navigation, link)
            confirmation.load()
            assertEquals(contact, confirmation.state.value.existing)

            val disconnect = async { h.manager.disconnect() }
            h.eventually("retired generation enters held cleanup") { cleanupEntered.isCompleted }
            assertFalse(session.isTornDown)
            assertFalse(h.container.isCurrentSession(session))
            val before = navigation.state.value
            assertFalse(confirmation.confirm())
            assertEquals(before, navigation.state.value)
            assertTrue(confirmation.dismiss())
            assertNull(navigation.state.value.pendingContactLink)
            assertNull(navigation.state.value.pendingContactDetail)
            releaseCleanup.complete(Unit)
            h.eventually("held cleanup finishes") { disconnect.isCompleted }
            disconnect.await()
        } finally {
            releaseCleanup.complete(Unit)
            h.container.close()
        }
    }

    @Test fun postReadyFailureWithHeldCleanupRejectsTheRetiredGraphDespiteItsReadySnapshot() = runTest {
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val heldStore = object : PersistenceStoreProtocol by store {
            override suspend fun fetchConnectedRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> {
                cleanupEntered.complete(Unit)
                releaseCleanup.await()
                return store.fetchConnectedRemoteNodeSessions(radioId)
            }
        }
        lateinit var h: ContainerHarness
        var captured: (() -> Boolean)? = null
        var callback: (suspend (EntityKey) -> Unit)? = null
        var generation = -1L
        var failed = false
        h = ContainerHarness(this, store, persistenceOverride = heldStore, notificationNavigationReady = {
            if (!failed && h.manager.snapshot.value.state == DeviceConnectionState.READY) {
                failed = true
                val session = checkNotNull(h.sessions.current)
                generation = h.container.navigation.state.value.generation
                captured = h.container.captureContactLinkSession(generation)
                callback = checkNotNull(session.notificationService.onNotificationTapped)
                throw IllegalStateException("Failure after READY publication")
            }
        })
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Held cleanup peer") }
        val connecting = async {
            try {
                h.manager.connect(h.target())
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: IllegalStateException) {
                Unit
            }
        }
        try {
            h.eventually("failed READY generation enters held disconnect cleanup") { cleanupEntered.isCompleted }
            val session = assertNotNull(h.sessions.current)
            val radio = session.token.radioId
            val contact = assertNotNull(store.fetchContact(radio, key(20)))
            assertEquals(DeviceConnectionState.READY, h.manager.snapshot.value.state)
            assertEquals(session.token, h.manager.snapshot.value.token)
            assertFalse(session.isTornDown)
            assertFalse(h.container.isCurrentSession(session))
            val navigation = h.container.navigation
            val before = navigation.state.value
            assertFalse(navigation.completeContactLookup(
                ContactLinkRequest("Retired", contact.publicKey, ContactType.CHAT), contact,
                radio, generation, radio, assertNotNull(captured),
            ))
            val receipt = NotificationNavigationReceipt()
            withContext(receipt) { assertNotNull(callback)(EntityKey(radio, contact.id)) }
            assertEquals(NavigationOutcome.Failed(NavigationFailure.StaleGeneration), receipt.outcome)
            assertFalse(h.container.handleNotificationResponse(NotificationResponse(
                NotificationPayload.DirectMessage(EntityKey(radio, contact.id), UUID.randomUUID()), null, null,
            )))
            assertEquals(before, navigation.state.value)
        } finally {
            releaseCleanup.complete(Unit)
            connecting.cancelAndJoin()
            h.container.close()
        }
    }
}
