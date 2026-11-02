// AndroidOnly: WP-302 Deterministic native stack, lifecycle, cold-route, cancellation, partition and restoration assertions.
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.app.navigation.cases.NotificationFixture
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TestName
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException

@OptIn(ExperimentalCoroutinesApi::class)
class NavigationStateTest {
    @get:Rule val executionName = TestName()
    @Before fun bindActualInputs() = emitNavigationExecutionBinding(javaClass.name, executionName.methodName, "jvm")
    private val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000011"))
    private val otherRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000012"))
    private val message = UUID.fromString("00000000-0000-0000-0000-000000000013")
    private val contact = ContactDTO(
        id = UUID.fromString("00000000-0000-0000-0000-000000000014"),
        radioId = radio, publicKey = Bytes(ByteArray(32) { 0xAA.toByte() }), name = "Fixture", lastHeardTimestamp = null,
    )
    private val channel = ChannelDTO(
        id = UUID.fromString("00000000-0000-0000-0000-000000000015"), radioId = radio, index = 3u, name = "Fixture",
    )
    private val room = RemoteNodeSessionDTO(
        id = UUID.fromString("00000000-0000-0000-0000-000000000016"),
        radioId = radio, publicKey = Bytes(ByteArray(32) { 0xBB.toByte() }), name = "Fixture", role = RemoteNodeRole.ROOM_SERVER,
    )
    private val payload = NotificationPayload.DirectMessage(EntityKey(radio, contact.id), message)
    private fun lookup(
        contact: ContactDTO? = this.contact, channel: ChannelDTO? = this.channel, room: RemoteNodeSessionDTO? = this.room,
    ) = object : NavigationLookup {
        override suspend fun contact(key: EntityKey) = contact
        override suspend fun channel(radioId: RadioId, index: UByte) = channel
        override suspend fun room(key: EntityKey) = room
    }

    @Test fun delayedPublicationNeverRegressesTheNewestCommittedState() {
        val initial = NavigationState()
        val store = NavigationStateStore(initial)
        val original = store.snapshot()
        val first = NavigationStateStore.Publication()
        val second = NavigationStateStore.Publication()
        assertTrue(store.compareAndSet(original, initial.copy(selectedTab = AppTab.NODES), first))
        val afterFirst = store.snapshot()
        assertTrue(store.compareAndSet(afterFirst, afterFirst.value.copy(selectedTab = AppTab.MAP), second))
        store.release(second)
        assertSame(initial, store.state.value)
        store.release(first)
        assertEquals(AppTab.MAP, store.state.value.selectedTab)
        assertFalse(store.compareAndSet(original, initial.copy(selectedTab = AppTab.TOOLS)))
    }

    @Test fun ordinaryUpdateCannotPublishAnUnreleasedGuardedCommit() {
        val initial = NavigationState()
        val store = NavigationStateStore(initial)
        val guarded = NavigationStateStore.Publication()
        assertTrue(store.compareAndSet(store.snapshot(), initial.copy(selectedTab = AppTab.NODES), guarded))
        store.update { it.copy(selectedTab = AppTab.MAP) }
        assertSame(initial, store.state.value)
        store.release(guarded)
        assertEquals(AppTab.MAP, store.state.value.selectedTab)
    }

    @Test fun staleSnapshotCannotCommitAfterAnEqualStateReturns() {
        val initial = NavigationState()
        val store = NavigationStateStore(initial)
        val stale = store.snapshot()
        store.update { it.copy(selectedTab = AppTab.NODES) }
        store.update { initial }
        assertSame(initial, store.value)
        assertFalse(store.compareAndSet(stale, initial.copy(selectedTab = AppTab.MAP)))
    }

    @Test fun guardedNavigationPublishesOnlyAfterReleasingTheLifecycleBoundary() = runTest {
        val boundary = Any()
        val n = NavigationCoordinator()
        n.installCommitBoundary { action -> synchronized(boundary, action) }
        n.replaceRadio(radio)
        val observed = CompletableDeferred<Boolean>()
        val acquired = CountDownLatch(1)
        val probe = Thread { synchronized(boundary) { acquired.countDown() } }
        val collector = launch(Dispatchers.Unconfined) {
            n.state.drop(1).first()
            probe.start()
            observed.complete(acquired.await(2, TimeUnit.SECONDS))
        }

        assertTrue(n.completeContactLookup(
            ContactLinkRequest("Fixture", contact.publicKey, ContactType.CHAT), contact,
            radio, n.state.value.generation, radio,
        ))
        assertTrue("A synchronous StateFlow collector must not run under the lifecycle boundary", observed.await())
        probe.join()
        collector.join()
        assertEquals(contact, n.state.value.pendingContactDetail)
    }

    @Test fun reentrantCollectorImmediatelySeesItsNewerPublishedState() = runTest {
        val n = NavigationCoordinator()
        val observed = CompletableDeferred<AppTab>()
        val collector = launch(Dispatchers.Unconfined) {
            n.state.drop(1).first()
            n.selectTab(AppTab.MAP)
            observed.complete(n.state.value.selectedTab)
        }
        n.selectTab(AppTab.NODES)
        assertEquals(AppTab.MAP, observed.await())
        collector.join()
    }

    @Test fun fiveTabsRetainIndependentStacksAndBackReturnsToChatsBeforeExit() {
        val n = NavigationCoordinator()
        n.navigateToChat(contact); val chats = n.state.value.activeStack
        n.navigateToContactDetail(contact); val nodes = n.state.value.activeStack
        n.navigateToTool(ToolSelection.LINE_OF_SIGHT); val tools = n.state.value.activeStack
        n.navigateToSetting(SettingsDetail.LANGUAGE); val settings = n.state.value.activeStack
        n.navigateToMap(1.0, 2.0)
        assertEquals(chats, n.state.value.stacks[AppTab.CHATS]); assertEquals(nodes, n.state.value.stacks[AppTab.NODES])
        assertEquals(tools, n.state.value.stacks[AppTab.TOOLS]); assertEquals(settings, n.state.value.stacks[AppTab.SETTINGS])
        assertTrue(n.back()); assertEquals(chats, n.state.value.activeStack)
        assertTrue(n.back()); assertTrue(n.state.value.tabBarVisible); assertNull(n.state.value.pendingChatContact)
        assertNull(n.state.value.chatsSelectedRoute); assertFalse(n.back())
    }
    @Test fun detailBackRestoresPreviousSelectionAndClearsPendingDelivery() {
        val n = NavigationCoordinator()
        n.navigateToChat(contact); n.navigateToChannel(channel, message)
        assertTrue(n.back()); assertEquals(ChatSelection.Direct(contact), n.state.value.chatsSelectedRoute)
        assertNull(n.state.value.pendingChannel); assertNull(n.state.value.pendingScrollToMessageID)
        n.navigateToContactDetail(contact); n.navigateToDiscovery()
        assertTrue(n.back()); assertEquals(contact, n.state.value.selectedContact)
        assertFalse(n.state.value.pendingDiscoveryNavigation); assertFalse(n.state.value.nodesShowingDiscovery)
    }
    @Test fun duplicateDestinationDoesNotAllocateAnotherEntryButUpdatesScrollRequest() {
        val n = NavigationCoordinator(); n.navigateToChat(contact)
        val before = n.state.value.activeStack
        n.navigateToChat(contact, message)
        assertEquals(before, n.state.value.activeStack); assertEquals(message, n.state.value.pendingScrollToMessageID)
        val failed = NavigationCoordinator(n.state.value.copy(failure = NavigationFailure.UnsupportedNotification))
        failed.navigateToChat(contact, message)
        assertEquals(before, failed.state.value.activeStack)
        assertNull("A successful duplicate route must dismiss the superseded failure", failed.state.value.failure)
        val refreshed = contact.copy(name = "Updated fixture", lastHeardTimestamp = 0u)
        assertEquals(ChatSelection.Direct(contact), ChatSelection.Direct(refreshed))
        assertEquals(ChatSelection.Direct(contact).hashCode(), ChatSelection.Direct(refreshed).hashCode())
        assertNotEquals(ChatSelection.Direct(contact), ChatSelection.Channel(channel.copy(id = contact.id)))
        n.navigateToChat(refreshed, message)
        assertEquals(before.last().id, n.state.value.activeStack.last().id)
        val selection = n.state.value.activeStack.last().destination as NavigationDestination.Chat
        assertEquals("Updated fixture", (selection.selection as ChatSelection.Direct).contact.name)
        assertEquals(refreshed, n.state.value.pendingChatContact)
    }
    @Test fun auxiliaryRoutesAreOwnedByTheSelectedTabAndDoNotCreateAnotherRoot() {
        val n = NavigationCoordinator(); n.selectTab(AppTab.NODES)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        assertEquals(NavigationDestination.Auxiliary(FeatureId.ONBOARDING), n.state.value.activeStack.last().destination)
        assertTrue(n.back()); assertEquals(AppTab.NODES, n.state.value.selectedTab)
        assertEquals(1, n.state.value.activeStack.size)
    }
    @Test fun everyToolAndSettingsLifetimePreservesOnlyOfflineOrAppWideSelections() {
        for (tool in ToolSelection.entries) {
            val n = NavigationCoordinator(); n.navigateToTool(tool); n.clearPerDeviceSelection()
            assertEquals(tool.takeUnless { it.requiresRadio }, n.state.value.selectedTool)
            assertEquals(if (tool.requiresRadio) 1 else 2, n.state.value.stacks.getValue(AppTab.TOOLS).size)
        }
        for (detail in SettingsDetail.entries) {
            val n = NavigationCoordinator(); n.navigateToSetting(detail); n.clearPerDeviceSelection()
            assertEquals(detail.takeUnless { it.requiresDevice }, n.state.value.selectedSetting)
            assertEquals(if (detail.requiresDevice) 1 else 2, n.state.value.stacks.getValue(AppTab.SETTINGS).size)
        }
        val layered = NavigationCoordinator()
        layered.navigateToTool(ToolSelection.LINE_OF_SIGHT)
        layered.navigateToTool(ToolSelection.CLI)
        layered.navigateToSetting(SettingsDetail.LANGUAGE)
        layered.navigateToSetting(SettingsDetail.RADIO)
        layered.manuallyDisconnect()
        assertEquals(ToolSelection.LINE_OF_SIGHT, layered.state.value.selectedTool)
        assertEquals(NavigationDestination.Tool(ToolSelection.LINE_OF_SIGHT),
            layered.state.value.stacks.getValue(AppTab.TOOLS).last().destination)
        assertEquals(SettingsDetail.LANGUAGE, layered.state.value.selectedSetting)
        assertEquals(NavigationDestination.Setting(SettingsDetail.LANGUAGE), layered.state.value.activeStack.last().destination)
        assertEquals(2, layered.state.value.stacks.getValue(AppTab.TOOLS).size)
        assertEquals(2, layered.state.value.activeStack.size)
    }
    @Test fun manualDisconnectRetainsCachedDetailsButReplacementRedactsEveryRadioSelection() {
        val n = NavigationCoordinator(); n.replaceRadio(radio)
        n.navigateToChat(contact); n.navigateToContactDetail(contact)
        n.navigateToSetting(SettingsDetail.RADIO); n.navigateToTool(ToolSelection.CLI)
        n.manuallyDisconnect()
        assertEquals(ChatSelection.Direct(contact), n.state.value.chatsSelectedRoute)
        assertEquals(contact, n.state.value.selectedContact); assertNull(n.state.value.selectedSetting)
        assertNull(n.state.value.selectedTool)
        n.replaceRadio(otherRadio)
        assertNull(n.state.value.chatsSelectedRoute); assertNull(n.state.value.selectedContact)
        assertNull(n.state.value.pendingChatContact); assertNull(n.state.value.pendingContactDetail)
        assertEquals(1, n.state.value.stacks.getValue(AppTab.CHATS).size)
        assertEquals(otherRadio, n.state.value.radioId)
    }
    @Test fun regularWidthToolExitClearsToolButCompactSwitchAndWidthChangeRetainIt() {
        val n = NavigationCoordinator(); n.measureWindow(360f); n.navigateToTool(ToolSelection.RX_LOG)
        n.selectTab(AppTab.CHATS); assertEquals(ToolSelection.RX_LOG, n.state.value.selectedTool)
        n.measureWindow(834f); assertEquals(ToolSelection.RX_LOG, n.state.value.selectedTool)
        n.selectTab(AppTab.TOOLS); n.selectTab(AppTab.NODES)
        assertNull(n.state.value.selectedTool); assertEquals(1, n.state.value.stacks.getValue(AppTab.TOOLS).size)
    }
    @Test fun coldNotificationWaitsForReadinessAndBindsInitialRadioOnlyOnce() = runTest {
        val n = NavigationCoordinator(); val id = n.enqueueNotification(payload)
        assertEquals(NavigationOutcome.NotReady, n.consumePendingNotification(lookup()))
        assertEquals(id, n.state.value.pendingNotifications.single().id)
        n.replaceRadio(radio); n.setNavigationReady(true)
        assertEquals(NavigationOutcome.Navigated, n.consumePendingNotification(lookup()))
        assertTrue(n.state.value.pendingNotifications.isEmpty())
        assertEquals(NavigationOutcome.NoPendingRoute, n.consumePendingNotification(lookup()))
        assertEquals(2, n.state.value.activeStack.size)
    }
    @Test fun coldQueueIsFifoAndConcurrentConsumersCannotDeliverTheSameRequestTwice() = runTest {
        val n = NavigationCoordinator(); n.replaceRadio(radio); n.setNavigationReady(true)
        n.enqueueNotification(payload); n.enqueueNotification(NotificationPayload.ChannelMessage(radio, channel.index, message))
        val first = async { n.consumePendingNotification(lookup()) }
        val second = async { n.consumePendingNotification(lookup()) }
        assertEquals(NavigationOutcome.Navigated, first.await()); assertEquals(NavigationOutcome.Navigated, second.await())
        assertEquals(ChatSelection.Channel(channel), n.state.value.chatsSelectedRoute)
        assertEquals(3, n.state.value.activeStack.size); assertTrue(n.state.value.pendingNotifications.isEmpty())
    }
    @Test fun cancelledColdLookupRetainsRequestAndRetryCommitsOnlyOneEntry() = runTest {
        val n = NavigationCoordinator(); n.setNavigationReady(true); n.enqueueNotification(payload)
        val started = CompletableDeferred<Unit>(); val gate = CompletableDeferred<ContactDTO?>()
        val slow = object : NavigationLookup by lookup() {
            override suspend fun contact(key: EntityKey): ContactDTO? { started.complete(Unit); return gate.await() }
        }
        val job = launch { n.consumePendingNotification(slow) }
        started.await(); job.cancelAndJoin()
        assertEquals(1, n.state.value.pendingNotifications.size); assertEquals(1, n.state.value.activeStack.size)
        assertNull(n.state.value.failure)
        assertEquals(NavigationOutcome.Navigated, n.consumePendingNotification(lookup()))
        assertEquals(2, n.state.value.activeStack.size); assertTrue(n.state.value.pendingNotifications.isEmpty())
    }
    @Test fun replacementDuringLookupRejectsLateSuccessAndLateRepositoryFailure() = runTest {
        for (throws in listOf(false, true)) {
            val n = NavigationCoordinator(); n.replaceRadio(radio)
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val slow = object : NavigationLookup by lookup() {
                override suspend fun contact(key: EntityKey): ContactDTO? {
                    started.complete(Unit); release.await()
                    if (throws) throw PersistenceStoreException(PersistenceStoreError.FetchFailed("fixture"))
                    return contact
                }
            }
            val result = async { n.handleNotification(payload, slow) }
            started.await(); n.replaceRadio(otherRadio); release.complete(Unit)
            assertEquals(NavigationOutcome.Failed(NavigationFailure.StaleGeneration), result.await())
            assertNull(n.state.value.pendingChatContact); assertNull(n.state.value.failure)
            assertEquals(1, n.state.value.activeStack.size)
        }
    }
    @Test fun anOldQueuedRadioRequestHasAnExplicitStaleOutcomeAfterReplacement() = runTest {
        val n = NavigationCoordinator(); n.replaceRadio(radio); n.enqueueNotification(payload)
        n.replaceRadio(otherRadio); n.setNavigationReady(true)
        assertEquals(NavigationOutcome.Failed(NavigationFailure.StaleGeneration), n.consumePendingNotification(lookup()))
        assertTrue(n.state.value.pendingNotifications.isEmpty()); assertNull(n.state.value.pendingChatContact)
    }
    @Test fun wrongActiveRadioAndWrongReturnedEntityOrIndexNeverNavigate() = runTest {
        val n = NavigationCoordinator(); n.replaceRadio(otherRadio)
        assertEquals(NavigationOutcome.Failed(NavigationFailure.WrongRadio(otherRadio, radio)), n.handleNotification(payload, lookup()))
        n.replaceRadio(radio)
        assertEquals(NavigationOutcome.Failed(NavigationFailure.WrongTarget(NavigationTarget.CONTACT)),
            n.handleNotification(payload, lookup(contact = contact.copy(id = message))))
        assertEquals(NavigationOutcome.Failed(NavigationFailure.WrongTarget(NavigationTarget.CHANNEL)),
            n.handleNotification(NotificationPayload.ChannelMessage(radio, 2u, message), lookup()))
        assertEquals(1, n.state.value.activeStack.size)
    }
    @Test fun missingNewContactFallsBackButOtherMissingTargetsAndRepositoryErrorsAreTyped() = runTest {
        val n = NavigationCoordinator()
        val missing = NavigationFailure.TargetNotFound(NavigationTarget.CONTACT)
        assertEquals(NavigationOutcome.Fallback(missing),
            n.handleNotification(NotificationPayload.NewContact(EntityKey(radio, contact.id)), lookup(contact = null)))
        assertEquals(AppTab.NODES, n.state.value.selectedTab); assertEquals(missing, n.state.value.failure)
        val error = PersistenceStoreError.FetchFailed("fixture")
        val broken = object : NavigationLookup by lookup() {
            override suspend fun contact(key: EntityKey): ContactDTO? = throw PersistenceStoreException(error)
        }
        assertEquals(NavigationOutcome.Failed(NavigationFailure.Repository(error)), n.handleNotification(payload, broken))
        assertEquals(NavigationFailure.Repository(error), n.state.value.failure)
        n.navigateToMap(1.0, 2.0)
        NotificationFixture(this, radio, n, broken).use {
            requireNotNull(it.service.onNewContactNotificationTapped)(EntityKey(radio, contact.id))
            assertEquals(listOf(NavigationOutcome.Fallback(NavigationFailure.Repository(error))), it.outcomes)
        }
        assertEquals(AppTab.NODES, n.state.value.selectedTab)
        assertEquals(NavigationFailure.Repository(error), n.state.value.failure)
        assertEquals(NavigationOutcome.Failed(NavigationFailure.UnsupportedNotification),
            n.handleNotification(NotificationPayload.LowBattery(20), lookup()))
    }
    @Test fun unconnectedRoomRequestsAuthenticationAndConnectedRoomNavigatesThroughRealCallback() = runTest {
        for (connected in listOf(false, true)) {
            val n = NavigationCoordinator(NavigationState(failure = NavigationFailure.UnsupportedNotification))
            val value = room.copy(isConnected = connected)
            NotificationFixture(this, radio, n, lookup(room = value)).use {
                requireNotNull(it.service.onRoomNotificationTapped)(EntityKey(radio, value.id))
                assertEquals(listOf(NavigationOutcome.Navigated), it.outcomes)
            }
            assertEquals(AppTab.CHATS, n.state.value.selectedTab)
            assertEquals(if (connected) ChatSelection.Room(value) else null, n.state.value.chatsSelectedRoute)
            assertEquals(if (connected) null else value, n.state.value.pendingRoomAuthentication)
            assertNull(n.state.value.failure)
        }
    }
    @Test fun roomAuthenticationPreservesThePriorStackAndDistinguishesIdenticalDeliveries() = runTest {
        for (width in listOf(390f, 950f)) {
            val n = NavigationCoordinator()
            n.replaceRadio(radio)
            n.measureWindow(width)
            n.navigateToChat(contact)
            val priorStack = n.state.value.stacks.getValue(AppTab.CHATS)
            val disconnected = room.copy(isConnected = false)
            val payload = NotificationPayload.RoomMessage(disconnected.name, EntityKey(radio, disconnected.id), message)

            assertEquals(NavigationOutcome.Navigated, n.handleNotification(payload, lookup(room = disconnected)))
            val first = n.state.value
            val firstId = requireNotNull(first.roomAuthenticationDeliveryId)
            assertEquals(priorStack, first.stacks.getValue(AppTab.CHATS))
            assertEquals(ChatSelection.Direct(contact), first.chatsSelectedRoute)
            assertTrue(n.dismissRoomAuthentication(firstId))
            assertEquals(priorStack, n.state.value.stacks.getValue(AppTab.CHATS))

            n.handleNotification(payload, lookup(room = disconnected))
            val replacedId = requireNotNull(n.state.value.roomAuthenticationDeliveryId)
            n.handleNotification(payload, lookup(room = disconnected))
            val latest = n.state.value
            val latestId = requireNotNull(latest.roomAuthenticationDeliveryId)
            assertNotEquals(replacedId, latestId)
            assertFalse(n.dismissRoomAuthentication(replacedId))
            assertFalse(n.completeRoomAuthentication(
                replacedId, requireNotNull(latest.roomAuthenticationGeneration), disconnected.copy(isConnected = true),
            ) { true })
            assertFalse(n.completeRoomAuthentication(
                latestId, requireNotNull(latest.roomAuthenticationGeneration),
                disconnected.copy(isConnected = true, publicKey = Bytes(ByteArray(32) { 0x7f })),
            ) { true })
            assertEquals(latestId, n.state.value.roomAuthenticationDeliveryId)
            val authenticated = disconnected.copy(isConnected = true)
            assertTrue(n.completeRoomAuthentication(
                latestId, requireNotNull(latest.roomAuthenticationGeneration), authenticated,
            ) { true })
            assertNull(n.state.value.pendingRoomAuthentication)
            assertEquals(ChatSelection.Room(authenticated), n.state.value.chatsSelectedRoute)
            assertTrue(n.back())
            assertEquals(priorStack, n.state.value.stacks.getValue(AppTab.CHATS))
            assertEquals(ChatSelection.Direct(contact), n.state.value.chatsSelectedRoute)
        }
    }

    @Test fun roomAuthenticationIsRetiredOrRestagedWhenItsRadioSessionChanges() {
        val n = NavigationCoordinator()
        val firstToken = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), radio)
        val secondToken = firstToken.copy(generation = Generation(2))
        assertTrue(n.bindNotificationSession(radio, null, firstToken))
        val disconnected = room.copy(isConnected = false)
        n.requestRoomAuthentication(disconnected)
        val original = n.state.value
        val oldId = requireNotNull(original.roomAuthenticationDeliveryId)
        val oldGeneration = requireNotNull(original.roomAuthenticationGeneration)
        assertEquals(firstToken, original.roomAuthenticationSessionToken)

        assertFalse(n.bindNotificationSession(radio, null, secondToken) { false })
        assertEquals(original, n.state.value)
        assertTrue(n.bindNotificationSession(radio, null, secondToken))
        val rebound = n.state.value
        val newId = requireNotNull(rebound.roomAuthenticationDeliveryId)
        assertNotEquals(oldId, newId)
        assertEquals(rebound.generation, rebound.roomAuthenticationGeneration)
        assertEquals(secondToken, rebound.roomAuthenticationSessionToken)
        assertFalse(n.completeRoomAuthentication(oldId, oldGeneration, disconnected.copy(isConnected = true)) { true })
        assertFalse(n.completeRoomAuthentication(newId, rebound.generation, disconnected.copy(isConnected = true)) { false })
        assertEquals(newId, n.state.value.roomAuthenticationDeliveryId)
        n.manuallyDisconnect()
        assertNull(n.state.value.pendingRoomAuthentication)
        assertNull(n.state.value.boundSessionToken)
        assertFalse(n.dismissRoomAuthentication(newId))

        n.requestRoomAuthentication(disconnected)
        n.replaceRadio(otherRadio)
        assertNull(n.state.value.pendingRoomAuthentication)
        assertNull(n.state.value.roomAuthenticationDeliveryId)
    }

    @Test fun reactionPrioritizesContactThenFallsBackToChannelAndPreservesMessageIdentity() = runTest {
        val request = NotificationPayload.Reaction(message, EntityKey(radio, contact.id), channel.index, radio)
        val n = NavigationCoordinator(); n.handleNotification(request, lookup())
        assertEquals(ChatSelection.Direct(contact), n.state.value.chatsSelectedRoute)
        n.handleNotification(request, lookup(contact = null))
        assertEquals(ChatSelection.Channel(channel), n.state.value.chatsSelectedRoute)
        assertEquals(message, n.state.value.pendingScrollToMessageID)
        n.handleNotification(NotificationPayload.QuickReplyFailed(EntityKey(radio, contact.id)), lookup())
        assertEquals(ChatSelection.Direct(contact), n.state.value.chatsSelectedRoute)
        n.handleNotification(NotificationPayload.ChannelQuickReplyFailed(radio, channel.index), lookup())
        assertEquals(ChatSelection.Channel(channel), n.state.value.chatsSelectedRoute)
        val error = PersistenceStoreError.FetchFailed("fixture contact lookup")
        val contactFailed = object : NavigationLookup by lookup() {
            override suspend fun contact(key: EntityKey): ContactDTO? = throw PersistenceStoreException(error)
        }
        NotificationFixture(this, radio, n, contactFailed).use {
            requireNotNull(it.service.onReactionNotificationTapped)(EntityKey(radio, contact.id), channel.index, radio, message)
            assertEquals(listOf(NavigationOutcome.Fallback(NavigationFailure.Repository(error))), it.outcomes)
        }
        assertEquals(ChatSelection.Channel(channel), n.state.value.chatsSelectedRoute)
        assertEquals(message, n.state.value.pendingScrollToMessageID)
        assertEquals(NavigationFailure.Repository(error), n.state.value.failure)
        assertEquals(NavigationOutcome.Failed(NavigationFailure.Repository(error)),
            n.handleNotification(request, object : NavigationLookup by contactFailed {
                override suspend fun channel(radioId: RadioId, index: UByte): ChannelDTO? = null
            }))
    }
    @Test fun callbackTeardownDoesNotClearReplacementOrQuickReplyHandlersAndOldGenerationIsRejected() = runTest {
        val n = NavigationCoordinator(); n.replaceRadio(radio)
        val fixture = NotificationFixture(this, radio, n, lookup())
        val stale = requireNotNull(fixture.service.onNotificationTapped)
        val replacement: suspend (EntityKey) -> Unit = {}
        val reply: suspend (EntityKey, String) -> Unit = { _, _ -> }
        fixture.service.onNotificationTapped = replacement; fixture.service.onQuickReply = reply
        fixture.close()
        assertSame(replacement, fixture.service.onNotificationTapped); assertSame(reply, fixture.service.onQuickReply)
        assertNull(fixture.service.onChannelNotificationTapped); assertNull(fixture.service.onRoomNotificationTapped)
        assertNull(fixture.service.onReactionNotificationTapped); assertNull(fixture.service.onNewContactNotificationTapped)
        n.replaceRadio(otherRadio); stale(EntityKey(radio, contact.id))
        assertEquals(listOf(NavigationOutcome.Failed(NavigationFailure.StaleGeneration)), fixture.outcomes)
        assertNull(n.state.value.pendingChatContact)
    }
    @Test fun wrappedCancellationAndUnexpectedFailuresAreNotConvertedToNavigationSuccessOrRepositoryFallback() = runTest {
        val cancelled = object : NavigationLookup by lookup() {
            override suspend fun contact(key: EntityKey): ContactDTO? =
                throw PersistenceStoreException(PersistenceStoreError.FetchFailed("fixture"), CancellationException("fixture"))
        }
        val n = NavigationCoordinator()
        for (request in listOf(payload, NotificationPayload.Reaction(message, EntityKey(radio, contact.id), channel.index, radio))) {
            try { n.handleNotification(request, cancelled); fail("Cancellation must propagate instead of starting recovery") }
            catch (_: CancellationException) { assertNull(n.state.value.failure) }
        }
        val unexpected = object : NavigationLookup by lookup() {
            override suspend fun contact(key: EntityKey): ContactDTO? = error("fixture programming failure")
        }
        try { n.handleNotification(payload, unexpected); fail("Unexpected failure must propagate") }
        catch (error: IllegalStateException) { assertEquals("fixture programming failure", error.message) }
        assertEquals(1, n.state.value.activeStack.size)
    }
    @Test fun publicRestorationRetainsEveryTabAndOpaqueEntryIdentityWithoutPrivateDtoOrLinkBytes() {
        val n = NavigationCoordinator(); n.navigateToChat(contact); n.navigateToContactDetail(contact)
        n.navigateToTool(ToolSelection.LINE_OF_SIGHT); n.navigateToSetting(SettingsDetail.LANGUAGE)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val encoded = NavigationSavedState.encode(n.state.value)
        assertFalse(encoded.any { contact.name in it || contact.id.toString() in it || radio.value.toString() in it })
        val restored = NavigationSavedState.restore(encoded)
        assertEquals(AppTab.SETTINGS, restored.selectedTab)
        assertEquals(n.state.value.stacks[AppTab.SETTINGS], restored.stacks[AppTab.SETTINGS])
        assertEquals(n.state.value.stacks[AppTab.TOOLS], restored.stacks[AppTab.TOOLS])
        assertEquals(n.state.value.nextEntryId, restored.nextEntryId)
        assertEquals(NavigationFailure.PrivateSelectionNotRestored(2), restored.failure)
        assertNull(restored.selectedContact); assertNull(restored.chatsSelectedRoute); assertNull(restored.radioId)
        assertFalse(restored.navigationReady)
    }
    @Test fun malformedOrDuplicateOrUnknownSavedEntriesHaveExplicitInvalidDataRecovery() {
        val valid = NavigationSavedState.encode(NavigationState())
        val malformed = listOf(
            emptyList(), listOf("wp302.v0|CHATS|5"), listOf("wp302.v1|UNKNOWN|5"),
            listOf("wp302.v1|CHATS|9223372036854775807"),
            valid + "CHATS|0|root|CHATS", valid.dropLast(1),
            valid + "TOOLS|4|tool|missing", valid + "NODES|4|setting|language",
            valid + "CHATS|4|auxiliary|chats.root",
        )
        for (tokens in malformed) assertEquals(NavigationFailure.InvalidSavedState, NavigationSavedState.restore(tokens).failure)
    }
    @Test fun restorationCannotOverwriteLiveHostAndInvalidWindowWidthIsNotSilentlyAccepted() {
        val cold = NavigationCoordinator()
        val restored = NavigationState(selectedTab = AppTab.NODES)
        cold.restoreHostState(restored)
        assertSame(restored, cold.state.value)
        assertThrows(IllegalStateException::class.java) { cold.restoreHostState(NavigationState()) }
        assertSame("Rejected restoration must not change the live state", restored, cold.state.value)
        val emptyHost = NavigationCoordinator()
        val emptyRestoration = NavigationState()
        emptyHost.restoreHostState(emptyRestoration)
        assertSame(emptyRestoration, emptyHost.state.value)
        assertThrows(IllegalStateException::class.java) { emptyHost.restoreHostState(NavigationState()) }
        val n = NavigationCoordinator(); n.navigateToChat(contact)
        assertThrows(IllegalStateException::class.java) { n.restoreHostState(NavigationState()) }
        for (width in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            assertThrows(IllegalArgumentException::class.java) { n.measureWindow(width) }
        }
        assertEquals(contact, n.state.value.pendingChatContact)
    }
    @Test fun unavailableMapConsumerAndOldErrorDismissalDoNotPretendSuccessOrLoseNewFailure() {
        assertEquals(MapNavigationOutcome.UNAVAILABLE, MapNavigationConsumer().navigateToMap(MapFocusRequest(1.0, 2.0)))
        val current = NavigationFailure.TargetNotFound(NavigationTarget.CHANNEL)
        val n = NavigationCoordinator(NavigationState(failure = current))
        n.clearFailure(NavigationFailure.TargetNotFound(NavigationTarget.CONTACT)); assertEquals(current, n.state.value.failure)
        n.clearFailure(current); assertNull(n.state.value.failure)
    }
}
