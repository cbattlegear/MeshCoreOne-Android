// PortedFrom: MC1Tests/AppState/DisconnectedPillTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/AppState/ChatCoordinatorRegistrySurvivalTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/State/ChannelSlotOccupantChangedTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/State/ConnectionManagerDeleteDeviceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Tests/MC1ServicesTests/ServiceContainerWiringTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/AppState/AppStateEnvironmentDefaultTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.state.AppStatePlaceholder
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SessionStateTest : RoomProcessTest() {
    private suspend fun ContainerHarness.connect(id: UUID = UUID.randomUUID()): UUID {
        manager.connect(target(id))
        settle()
        assertReady()
        return id
    }

    // region DisconnectedPillTests (intent-backed)

    @OriginalCase("DisconnectedPillTests::shouldSuppressDisconnectedPill returns true when user explicitly disconnected()")
    @Test fun suppressedAfterExplicitDisconnect() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.preferences.values[PersistenceKeys.USER_EXPLICITLY_DISCONNECTED] = RuntimePreferenceValue.Flag(true)
            h.container.connectionPort.activate()
            assertTrue(h.container.connectionPort.shouldSuppressDisconnectedPill)
            assertEquals(ConnectionIntent.UserDisconnected, h.manager.connectionIntent)
        } finally { h.container.close() }
    }

    @OriginalCase("DisconnectedPillTests::shouldSuppressDisconnectedPill returns false when user did not explicitly disconnect()")
    @Test fun notSuppressedWithoutExplicitDisconnect() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.container.connectionPort.activate()
            assertFalse(h.container.connectionPort.shouldSuppressDisconnectedPill)
        } finally { h.container.close() }
    }

    // endregion

    // region ChatCoordinatorRegistrySurvivalTests

    private val dm = ChatConversationID.dm(RadioId(UUID.randomUUID()), UUID.randomUUID())

    @OriginalCase("ChatCoordinatorRegistrySurvivalTests::disconnect then reconnect wiring keeps the same coordinator instance()")
    @Test fun registrySurvivesReconnect() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val deviceId = h.connect()
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            val original = registry.coordinator(dm)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            assertSame(registry, h.appState.chatCoordinatorRegistry)
            assertSame(original, registry.existingCoordinator(dm))
            h.connect(deviceId)
            assertSame(registry, h.appState.chatCoordinatorRegistry)
            assertSame(original, h.appState.chatCoordinatorRegistry?.existingCoordinator(dm))
        } finally { h.container.close() }
    }

    @OriginalCase("ChatCoordinatorRegistrySurvivalTests::notifyDataRestored bumps servicesVersion and the next load binds a fresh coordinator()", "native-equivalent")
    @Test fun restoreBumpsVersionAndRebindsFresh() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            val original = registry.coordinator(dm)
            val before = h.appState.servicesVersion.value
            h.appState.notifyDataRestored()
            assertEquals(before + 1, h.appState.servicesVersion.value)
            assertNull(registry.existingCoordinator(original.conversationID))
            assertNotSame(original, registry.coordinator(dm), "the next load mints a fresh coordinator")
        } finally { h.container.close() }
    }

    @OriginalCase("ChatCoordinatorRegistrySurvivalTests::notifyDataRestored drops registry entries()")
    @Test fun restoreDropsEntries() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            val original = registry.coordinator(dm)
            h.appState.notifyDataRestored()
            assertSame(registry, h.appState.chatCoordinatorRegistry)
            assertNull(registry.existingCoordinator(dm))
            assertNotSame(original, registry.coordinator(dm))
        } finally { h.container.close() }
    }

    @OriginalCase("ChatCoordinatorRegistrySurvivalTests::forgetting the last-connected device empties the registry and reloads unavailable()", "native-equivalent")
    @Test fun forgettingLastDeviceEmptiesRegistry() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val deviceId = h.connect()
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            registry.coordinator(dm)
            val before = h.appState.servicesVersion.value
            h.manager.clearPersistedConnection(deviceId)
            h.eventually("the last-device cleared callback") { h.appState.offlineDataStore == null }
            assertSame(registry, h.appState.chatCoordinatorRegistry)
            assertNull(registry.existingCoordinator(dm))
            assertEquals(before + 1, h.appState.servicesVersion.value)
            assertNull(h.appState.offlineDataStore, "offline browsing is unavailable once the last device is forgotten")
        } finally { h.container.close() }
    }

    @OriginalCase("ChatCoordinatorRegistrySurvivalTests::forgetting a non-last-connected device does not fire onLastConnectedDeviceCleared()")
    @Test fun forgettingAnotherDeviceDoesNotClear() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val deviceId = h.connect()
            val registry = assertNotNull(h.appState.ensureChatCoordinatorRegistry())
            val coordinator = registry.coordinator(dm)
            val before = h.appState.servicesVersion.value
            h.manager.clearPersistedConnection(UUID.randomUUID())
            h.settle()
            assertEquals(before, h.appState.servicesVersion.value)
            assertSame(coordinator, registry.existingCoordinator(dm))
            h.manager.clearPersistedConnection(deviceId)
            h.eventually("the last-device cleared callback") { h.appState.servicesVersion.value == before + 1 }
        } finally { h.container.close() }
    }

    // endregion

    // region ChannelSlotOccupantChangedTests

    private fun channel(radio: RadioId, index: Int) = ChannelDTO(radioId = radio, index = index.toUByte(), name = "Channel $index")

    @OriginalCase("ChannelSlotOccupantChangedTests::closes the open chat for a changed slot and drops its draft()")
    @Test fun slotChangeClosesChatAndDropsDraft() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val radio = RadioId(UUID.randomUUID())
            val draftId = ChatConversationID.channel(radio, 5u)
            h.container.navigation.navigateToChannel(channel(radio, 5))
            assertTrue(h.container.navigation.state.value.chatsSelectedRoute is ChatSelection.Channel)
            h.draftStore.setDraft("unsent", draftId)
            h.appState.handleChannelSlotOccupantChanged(radio, setOf(5u))
            assertNull(h.container.navigation.state.value.chatsSelectedRoute)
            assertNull(h.draftStore.draft(draftId))
            assertEquals(1, h.appState.channelSlotGenerations.value[draftId])
        } finally { h.container.close() }
    }

    @OriginalCase("ChannelSlotOccupantChangedTests::bumps the generation once per change and only for the affected slots()")
    @Test fun generationBumpsPerChangeAndOnlyAffectedSlots() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val radio = RadioId(UUID.randomUUID())
            val slot5 = ChatConversationID.channel(radio, 5u)
            val slot3 = ChatConversationID.channel(radio, 3u)
            h.appState.handleChannelSlotOccupantChanged(radio, setOf(5u))
            h.appState.handleChannelSlotOccupantChanged(radio, setOf(5u, 3u))
            assertEquals(2, h.appState.channelSlotGenerations.value[slot5])
            assertEquals(1, h.appState.channelSlotGenerations.value[slot3])
            assertNull(h.appState.channelSlotGenerations.value[ChatConversationID.channel(RadioId(UUID.randomUUID()), 5u)])
        } finally { h.container.close() }
    }

    @OriginalCase("ChannelSlotOccupantChangedTests::leaves an open chat on another slot or radio alone()")
    @Test fun otherSlotOrRadioIsLeftAlone() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val radio = RadioId(UUID.randomUUID())
            val open = channel(radio, 3)
            h.container.navigation.navigateToChannel(open)
            h.appState.handleChannelSlotOccupantChanged(radio, setOf(5u))
            h.appState.handleChannelSlotOccupantChanged(RadioId(UUID.randomUUID()), setOf(3u))
            assertEquals(ChatSelection.Channel(open), h.container.navigation.state.value.chatsSelectedRoute)
        } finally { h.container.close() }
    }

    // endregion

    // region ConnectionManagerDeleteDeviceTests (PairingCoordinator.deleteDevice over the real runtime)

    private fun savedDevice(id: UUID, seed: Int = 0xA1, methods: List<ConnectionMethod> = listOf(ConnectionMethod.Bluetooth(id, "Radio"))) =
        DeviceDTO(id = id, radioId = RadioId(id), publicKey = key(seed), nodeName = "TestDevice", connectionMethods = methods.snapshot())

    @OriginalCase("ConnectionManagerDeleteDeviceTests::deleteDevice removes the ASK accessory then demotes the row()", "platform-adaptation")
    @Test fun deleteRemovesAssociationThenDemotes() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = UUID.randomUUID()
            val device = savedDevice(id)
            store.saveDevice(device)
            h.pairing.registered[id] = "Radio"
            h.container.pairing.deleteDevice(id)
            assertEquals(listOf(id), h.pairing.removed)
            assertEquals(1, h.pairing.removeCalls)
            assertNull(store.fetchDevice(id))
            val ghost = assertNotNull(store.fetchDevice(device.publicKey))
            assertNotEquals(id, ghost.id)
            assertEquals(device.radioId, ghost.radioId)
            assertTrue(ghost.connectionMethods.isEmpty())
        } finally { h.container.close() }
    }

    @OriginalCase("ConnectionManagerDeleteDeviceTests::deleteDevice decline keeps the live radio connected()", "platform-adaptation")
    @Test fun declineKeepsTheLiveRadio() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = h.connect()
            h.pairing.registered[id] = "Radio"
            h.pairing.removeFailure = DevicePairingError.Cancelled()
            assertFailsWith<DevicePairingError.Cancelled> { h.container.pairing.deleteDevice(id) }
            assertEquals(1, h.pairing.removeCalls)
            assertTrue(id in h.pairing.registered)
            assertEquals(id, store.fetchDevice(id)?.id)
            assertEquals(DeviceConnectionState.READY, h.manager.connectionState)
            assertEquals(id, h.manager.connectedDevice?.id)
            assertTrue(h.manager.connectionIntent.wantsConnection)
        } finally { h.container.close() }
    }

    @OriginalCase("ConnectionManagerDeleteDeviceTests::deleteDevice with no ASK accessory still demotes()", "platform-adaptation")
    @Test fun deleteWithoutAssociationStillDemotes() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = UUID.randomUUID()
            val device = savedDevice(id, seed = 0xA2, methods = listOf(ConnectionMethod.WiFi("10.0.0.2", 5000u, "Home")))
            store.saveDevice(device)
            h.container.pairing.deleteDevice(id)
            assertEquals(0, h.pairing.removeCalls, "an unknown association makes no platform call")
            assertNull(store.fetchDevice(id))
            assertTrue(assertNotNull(store.fetchDevice(device.publicKey)).connectionMethods.isEmpty())
        } finally { h.container.close() }
    }

    @OriginalCase("ConnectionManagerDeleteDeviceTests::deleteDevice of the live radio disconnects and clears user intent()", "platform-adaptation")
    @Test fun deleteLiveRadioDisconnects() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val id = h.connect()
            h.pairing.registered[id] = "Radio"
            h.container.pairing.deleteDevice(id)
            h.settle()
            assertNull(h.manager.connectedDevice)
            assertEquals(ConnectionIntent.UserDisconnected, h.manager.connectionIntent)
        } finally { h.container.close() }
    }

    @OriginalCase("ConnectionManagerDeleteDeviceTests::deleteDevice of a sibling radio does not disconnect the live radio()", "platform-adaptation")
    @Test fun deleteSiblingKeepsTheLiveRadio() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val live = h.connect()
            val sibling = UUID.randomUUID()
            store.saveDevice(savedDevice(sibling, seed = 0xB2))
            h.pairing.registered[live] = "Live"
            h.pairing.registered[sibling] = "Sibling"
            h.container.pairing.setPairingFlags(flowActive = true)
            h.container.pairing.deleteDevice(sibling)
            assertEquals(live, h.manager.connectedDevice?.id)
            assertTrue(h.manager.connectionIntent.wantsConnection)
            assertTrue(h.container.pairing.isPairingFlowActive)
            assertTrue(live in h.pairing.registered)
            assertFalse(sibling in h.pairing.registered)
        } finally { h.container.close() }
    }

    @OriginalCase("ConnectionManagerDeleteDeviceTests::deleteDevice of an in-flight connect tears down the attempt()", "platform-adaptation")
    @Test fun deleteInFlightConnectTearsDownTheAttempt() = runTest {
        val h = ContainerHarness(this, store)
        val gate = CompletableDeferred<Unit>()
        h.links.configureRadio = { it.connectGate = gate }
        try {
            val id = UUID.randomUUID()
            store.saveDevice(savedDevice(id))
            h.pairing.registered[id] = "Radio"
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { runCatching { h.manager.connect(h.target(id)) } }
            h.eventually("the attempt to be in flight") { h.manager.activeConnectionAttemptDeviceId == id }
            h.container.pairing.deleteDevice(id)
            h.settle()
            assertNull(h.manager.activeConnectionAttemptDeviceId)
            assertEquals(DeviceConnectionState.DISCONNECTED, h.manager.connectionState)
            assertEquals(ConnectionIntent.UserDisconnected, h.manager.connectionIntent)
            assertFalse(id in h.pairing.registered)
        } finally {
            gate.complete(Unit)
            h.container.close()
        }
    }

    // endregion

    // region ServiceContainerWiringTests (RadioSessionContainer)

    @OriginalCase("ServiceContainerWiringTests::init establishes all 6 cross-service connections()", "native-equivalent")
    @Test fun crossServiceConnectionsAreWired() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            // channelService -> rxLogService and rxLogService -> heardRepeatsService are readable flags.
            assertTrue(live.channelService.hasRxLogServiceWired)
            assertTrue(live.rxLogService.hasHeardRepeatsServiceWired)
            // The remaining four connections (contact->sync, contact->cleanup, nodeConfig->sync, message->contact) are
            // internal flags of core:services, not readable here; the graph is constructed through their only
            // public constructors, so each is supplied by construction (see WP-303.md).
            assertNotNull(live.contactService)
            assertNotNull(live.nodeConfigService)
        } finally { h.container.close() }
    }

    @OriginalCase("ServiceContainerWiringTests::tearDown clears the wired message and discovery handlers()")
    @Test fun teardownClearsHandlersAndDiscoverySubscription() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            assertTrue(live.messagePollingService.hasMessageHandlersWired, "sync wired the message handlers")
            var advertisementStreamEnded = false
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                live.advertisementService.events().collect { }
                advertisementStreamEnded = true
            }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            h.eventually("the advertisement stream to end") { advertisementStreamEnded }
            assertFalse(live.messagePollingService.hasMessageHandlersWired, "teardown breaks the container retain cycle")
        } finally { h.container.close() }
    }

    @OriginalCase("ServiceContainerWiringTests::tearDown clears the notification action forwarders that capture the handler()")
    @Test fun teardownClearsForwarders() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            val handler = live.notificationActionHandler
            live.notificationService.onQuickReply = { contact, text -> handler.handleQuickReply(contact, text) }
            live.notificationService.onChannelQuickReply = { radio, index, text -> handler.handleChannelQuickReply(radio, index, text) }
            live.notificationService.onMarkAsRead = { contact, id -> handler.handleMarkAsRead(contact, id) }
            live.notificationService.onChannelMarkAsRead = { radio, index, id -> handler.handleChannelMarkAsRead(radio, index, id) }
            live.notificationService.onRoomMarkAsRead = { session, id -> handler.handleRoomMarkAsRead(session, id) }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            assertNull(live.notificationService.onQuickReply)
            assertNull(live.notificationService.onChannelQuickReply)
            assertNull(live.notificationService.onMarkAsRead)
            assertNull(live.notificationService.onChannelMarkAsRead)
            assertNull(live.notificationService.onRoomMarkAsRead)
        } finally { h.container.close() }
    }

    @OriginalCase("ServiceContainerWiringTests::startEventMonitoring activates ACK expiry checker; stopEventMonitoring deactivates it()")
    @Test fun ackExpiryFollowsMonitoring() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.connect()
            val live = assertNotNull(h.sessions.current)
            assertTrue(live.messageService.isAckExpiryCheckingActive, "connect started monitoring")
            live.stopEventMonitoring()
            assertFalse(live.messageService.isAckExpiryCheckingActive)
            live.startEventMonitoring(live.token.radioId, enableAutoFetch = false, enableAdvertisementMonitoring = false)
            assertTrue(live.messageService.isAckExpiryCheckingActive)
            live.stopEventMonitoring()
            assertFalse(live.messageService.isAckExpiryCheckingActive)
        } finally { h.container.close() }
    }

    // endregion

    @OriginalCase("AppStateEnvironmentDefaultTests::placeholder init leaves the shared debug log buffer untouched()")
    @Test fun placeholderLeavesSharedDebugLogAlone() = runTest {
        DebugLogBuffer.shared = null
        assertNotNull(AppStatePlaceholder.instance)
        assertNull(DebugLogBuffer.shared, "the inert placeholder never publishes a buffer")
        val h = ContainerHarness(this, store)
        try {
            assertNotNull(DebugLogBuffer.shared, "a live container publishes its bootstrap buffer")
            assertSame(h.container.bootstrapDebugLog, DebugLogBuffer.shared)
        } finally { h.container.close() }
    }
}
