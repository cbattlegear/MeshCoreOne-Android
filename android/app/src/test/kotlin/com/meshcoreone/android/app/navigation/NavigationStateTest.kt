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
import com.meshcoreone.android.feature.chats.list.ChatRoute
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.collect
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
    @Test fun channelDeletionCleanupCannotPopAnEqualValueReopenedEntry() {
        val n = NavigationCoordinator()
        val original = channel.copy(occupantID = UUID.randomUUID())
        n.navigateToChannel(original)
        val cleanup = n.captureChannelSelectionCleanup(original)
        n.back()
        n.navigateToChannel(original.copy())
        val replacement = n.currentState
        assertFalse(cleanup())
        assertEquals(replacement, n.currentState)
        assertTrue(n.captureChannelSelectionCleanup(original)())
        assertNull(n.currentState.chatsSelectedRoute)
        assertFalse(n.currentState.canGoBack)
    }

    @Test fun channelDeletionCleanupUsesCommittedOwnerEvenWhilePublishedSelectionLags() = runTest {
        val n = NavigationCoordinator()
        n.replaceRadio(radio)
        n.setNavigationReady(true)
        val original = channel.copy(occupantID = UUID.randomUUID())
        val replacement = original.copy(occupantID = UUID.randomUUID())
        n.navigateToChannel(original)
        val presented = n.publishedNavigation.value
        val cleanup = n.captureChannelSelectionCleanup(original)
        val gate = n.registerChatContactMount(ChatContactMount(presented.value.activeStack.last().id))
        try {
            assertEquals(NavigationOutcome.Navigated, n.handleNotification(
                NotificationPayload.ChannelMessage(radio, channel.index, message), lookup(channel = replacement),
            ))
            assertEquals(presented, n.publishedNavigation.value)
            val committed = n.currentState
            assertEquals(ChatSelection.Channel(replacement), committed.chatsSelectedRoute)
            assertFalse(cleanup())
            assertFalse(n.captureChannelSelectionCleanup(original)())
            assertEquals(committed, n.currentState)
        } finally { gate.release() }
        assertEquals(ChatSelection.Channel(replacement), n.state.value.chatsSelectedRoute)
    }

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
    private fun NavigationCoordinator.completeOnboardingForTest(
        presented: NavigationStateStore.PublishedSnapshot, entryId: Long,
    ): Boolean {
        installOnboardingSessionCapture { { true } }
        return completeMountedOnboarding(
            captureMountedOnboarding(presented, entryId), MountedOnboardingProducer.NoDevice,
        )
    }

    @Test fun metadataOnlyHandoffCommitsRetainEveryUnreleasedPredecessorGate() {
        val initial = NavigationState()
        val store = NavigationStateStore(initial)
        val first = NavigationStateStore.Publication()
        val second = NavigationStateStore.Publication()
        val mount = ChatContactMount(null)
        assertTrue(store.compareAndSet(store.snapshot(), initial.copy(selectedTab = AppTab.MAP), first))
        val captured = store.snapshot()
        assertTrue(store.compareAndSet(
            captured, captured.value, second, handoffs = ChatContactHandoffs(mapOf(mount.id to true)),
        ))
        store.release(second)
        assertSame(initial, store.state.value)
        val registered = store.snapshot()
        assertTrue(store.compareAndSet(
            registered, registered.value, handoffs = registered.handoffs.copy(mounts = mapOf(mount.id to false)),
        ))
        assertSame(initial, store.state.value)
        store.release(first)
        assertEquals(AppTab.MAP, store.state.value.selectedTab)
        assertEquals(false, store.snapshot().handoffs.mounts[mount.id])
        assertEquals(store.snapshot().revision, store.publishedSnapshots.value.revision)
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

    @Test fun backLeavesThePresentedChatInsteadOfAnUnpublishedNotificationDescendant() = runTest {
        val n = NavigationCoordinator()
        n.replaceRadio(radio)
        n.setNavigationReady(true)
        n.navigateToSetting(SettingsDetail.LANGUAGE)
        val settings = n.currentState.stacks.getValue(AppTab.SETTINGS)
        n.navigateToChat(contact)
        val presented = n.publishedNavigation.value
        val gate = n.registerChatContactMount(ChatContactMount(presented.value.activeStack.last().id))
        try {
            assertEquals(NavigationOutcome.Navigated, n.handleNotification(
                NotificationPayload.ChannelMessage(radio, channel.index, message), lookup(),
            ))
            assertEquals(presented, n.publishedNavigation.value)
            assertEquals(ChatSelection.Channel(channel), n.currentState.chatsSelectedRoute)
            assertTrue(n.back())
            assertEquals(1, n.currentState.activeStack.size)
            assertNull(n.currentState.chatsSelectedRoute)
            assertNull(n.currentState.pendingChannel)
            assertNull(n.currentState.pendingMessageScroll)
            assertEquals(settings, n.currentState.stacks.getValue(AppTab.SETTINGS))
            assertFalse("The same presented entry cannot be popped twice", n.back())
            assertEquals(presented, n.publishedNavigation.value)
        } finally {
            gate.release()
        }
        assertEquals(1, n.state.value.activeStack.size)
        assertNull(n.state.value.chatsSelectedRoute)
        assertEquals(settings, n.state.value.stacks.getValue(AppTab.SETTINGS))
    }

    @Test fun backKeepsItsPresentedEntryWhenANewerPublicationReachesTheFlowBeforeTheUi() = runTest {
        val n = NavigationCoordinator()
        n.replaceRadio(radio)
        n.setNavigationReady(true)
        n.navigateToChat(contact)
        val presented = n.publishedNavigation.value
        assertEquals(NavigationOutcome.Navigated, n.handleNotification(
            NotificationPayload.ChannelMessage(radio, channel.index, message), lookup(),
        ))
        assertTrue(n.publishedNavigation.value.revision > presented.revision)
        assertEquals(ChatSelection.Channel(channel), n.state.value.chatsSelectedRoute)
        assertTrue(n.back(presented))
        assertEquals(AppTab.CHATS, n.state.value.selectedTab)
        assertEquals(1, n.state.value.activeStack.size)
        assertNull(n.state.value.chatsSelectedRoute)
    }

    @Test fun backFromThePresentedChatsRootCannotPopAnUnpublishedNotification() = runTest {
        val n = NavigationCoordinator()
        n.replaceRadio(radio)
        n.setNavigationReady(true)
        val presented = n.publishedNavigation.value
        val gate = n.registerChatContactMount(ChatContactMount(null))
        try {
            assertEquals(NavigationOutcome.Navigated, n.handleNotification(payload, lookup()))
            val committed = n.currentState
            assertFalse(n.back())
            assertEquals(committed, n.currentState)
            assertEquals(presented, n.publishedNavigation.value)
        } finally {
            gate.release()
        }
        assertEquals(ChatSelection.Direct(contact), n.state.value.chatsSelectedRoute)
    }

    @Test fun backFromAPresentedTabRootRetainsThatTabsUnseenDetail() {
        val n = NavigationCoordinator()
        n.selectTab(AppTab.NODES)
        val presented = n.publishedNavigation.value
        val gate = n.registerChatContactMount(ChatContactMount(null))
        try {
            n.navigateToContactDetail(contact)
            val nodes = n.currentState.stacks.getValue(AppTab.NODES)
            assertTrue(n.back(presented))
            assertEquals(AppTab.CHATS, n.currentState.selectedTab)
            assertEquals(nodes, n.currentState.stacks.getValue(AppTab.NODES))
            assertEquals(contact, n.currentState.selectedContact)
            assertEquals(presented, n.publishedNavigation.value)
        } finally {
            gate.release()
        }
        assertEquals(AppTab.CHATS, n.state.value.selectedTab)
        assertEquals(NavigationDestination.ContactDetail(contact), n.state.value.stacks.getValue(AppTab.NODES).last().destination)
    }

    @Test fun backDoesNotReinterpretAnAlreadyRemovedPresentedEntry() {
        val n = NavigationCoordinator()
        n.navigateToChat(contact)
        val presented = n.publishedNavigation.value
        assertTrue(n.back(presented))
        n.navigateToChannel(channel)
        val replacement = n.state.value
        assertFalse(n.back(presented))
        assertEquals(replacement, n.state.value)
    }

    @Test fun backCannotRebaseAPresentedEntryOntoARetiredGeneration() {
        val n = NavigationCoordinator()
        n.navigateToChat(contact)
        val presented = n.publishedNavigation.value
        n.manuallyDisconnect()
        n.navigateToChannel(channel)
        val replacement = n.state.value
        assertFalse(n.back(presented))
        assertEquals(replacement, n.state.value)
    }

    @Test fun onboardingCompletionPopsItsEntryAndSelectsChatsInOneRevision() {
        val n = NavigationCoordinator()
        n.navigateToChat(contact, message)
        val chats = n.currentState.stacks.getValue(AppTab.CHATS)
        val scroll = n.currentState.pendingMessageScroll
        n.navigateToSetting(SettingsDetail.LANGUAGE)
        val settings = n.currentState.stacks.getValue(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        val entry = presented.value.activeStack.last()

        assertTrue(n.completeOnboardingForTest(presented, entry.id))
        assertEquals(presented.revision + 1, n.publishedNavigation.value.revision)
        assertEquals(AppTab.CHATS, n.currentState.selectedTab)
        assertEquals(chats, n.currentState.stacks.getValue(AppTab.CHATS))
        assertEquals(settings, n.currentState.stacks.getValue(AppTab.SETTINGS))
        assertEquals(scroll, n.currentState.pendingMessageScroll)
        assertEquals(SettingsDetail.LANGUAGE, n.currentState.selectedSetting)
        assertFalse(n.completeOnboardingForTest(presented, entry.id))
    }

    @Test fun firstRunCompletionSelectsChatsWithoutPoppingTheHiddenStackAndRetiresEvenWhenUnchanged() {
        for (alreadyChats in listOf(false, true)) {
            val n = NavigationCoordinator()
            n.installOnboardingSessionCapture { { true } }
            if (alreadyChats) n.navigateToChat(contact) else n.navigateToSetting(SettingsDetail.LANGUAGE)
            val presented = n.publishedNavigation.value
            val hiddenStack = presented.value.activeStack
            val completion = n.captureFirstRunOnboarding(presented)
            assertNotNull(completion)

            assertTrue(n.completeFirstRunOnboarding(completion, MountedOnboardingProducer.NoDevice))
            assertEquals(AppTab.CHATS, n.currentState.selectedTab)
            assertEquals(hiddenStack, n.currentState.stacks.getValue(presented.value.selectedTab))
            assertEquals(presented.revision + (if (alreadyChats) 0L else 1L), n.publishedNavigation.value.revision)
            val committed = n.currentState
            assertFalse(n.completeFirstRunOnboarding(completion, MountedOnboardingProducer.NoDevice))
            assertEquals(committed, n.currentState)
        }
    }

    @Test fun firstRunCompletionRejectsForeignAndReactivatedNavigationOwners() {
        for (reactivate in listOf(false, true)) {
            val n = NavigationCoordinator()
            val foreign = NavigationCoordinator()
            n.installOnboardingSessionCapture { { true } }
            foreign.installOnboardingSessionCapture { { true } }
            n.navigateToSetting(SettingsDetail.LANGUAGE)
            foreign.navigateToSetting(SettingsDetail.LANGUAGE)
            val presented = n.publishedNavigation.value
            val completion = n.captureFirstRunOnboarding(presented)
            assertNotNull(completion)
            assertFalse(n.completeFirstRunOnboarding(null, MountedOnboardingProducer.NoDevice))
            assertNull(foreign.captureFirstRunOnboarding(presented))
            assertFalse(foreign.completeFirstRunOnboarding(completion, MountedOnboardingProducer.NoDevice))
            n.selectTab(AppTab.MAP)
            if (reactivate) n.selectTab(AppTab.SETTINGS)
            val current = n.currentState
            assertFalse(n.completeFirstRunOnboarding(completion, MountedOnboardingProducer.NoDevice))
            assertFalse(n.reportFirstRunOnboardingFailure(completion))
            assertEquals(current, n.currentState)
        }
    }

    @Test fun firstRunCompletionChecksTheOriginalProducerInsideTheCommitBoundary() {
        val first = SessionToken(ProcessEpoch(UUID(0, 86)), Generation(1), radio)
        val second = first.copy(generation = Generation(2))
        val n = NavigationCoordinator()
        assertTrue(n.bindNotificationSession(radio, null, first))
        n.selectTab(AppTab.SETTINGS)
        var authority = first
        n.installOnboardingSessionCapture { { it == MountedOnboardingProducer.Preset(authority) } }
        val completion = n.captureFirstRunOnboarding(n.publishedNavigation.value)
        assertNotNull(completion)
        val before = n.currentState
        n.installCommitBoundary { action -> authority = second; action() }

        assertFalse(n.completeFirstRunOnboarding(completion, MountedOnboardingProducer.Preset(first)))
        assertEquals(before, n.currentState)
        n.installCommitBoundary { action -> authority = first; action() }
        assertTrue(n.completeFirstRunOnboarding(completion, MountedOnboardingProducer.Preset(first)))
        assertEquals(AppTab.CHATS, n.currentState.selectedTab)
    }

    @Test fun firstRunRejectionRetainsAnOccupiedFailureAndRequiresANewAttemptAfterReporting() {
        val n = NavigationCoordinator()
        var valid = false
        n.installOnboardingSessionCapture { { valid } }
        n.selectTab(AppTab.SETTINGS)
        val completion = n.captureFirstRunOnboarding(n.publishedNavigation.value)
        assertNotNull(completion)
        n.requestRoomAuthentication(room.copy(role = RemoteNodeRole.REPEATER))
        val occupied = n.currentState
        assertEquals(NavigationFailure.WrongTarget(NavigationTarget.ROOM), occupied.failure)
        assertFalse(n.completeFirstRunOnboarding(completion, MountedOnboardingProducer.NoDevice))
        assertFalse(n.reportFirstRunOnboardingFailure(completion))
        assertEquals(occupied, n.currentState)

        n.clearFailure()
        assertTrue(n.reportFirstRunOnboardingFailure(completion))
        assertTrue(n.currentState.failure is NavigationFailure.StaleOnboarding)
        valid = true
        assertFalse(n.completeFirstRunOnboarding(completion, MountedOnboardingProducer.NoDevice))
        n.clearFailure()
        val retry = n.captureFirstRunOnboarding(n.publishedNavigation.value)
        assertNotNull(retry)
        assertTrue(n.completeFirstRunOnboarding(retry, MountedOnboardingProducer.NoDevice))
        assertEquals(AppTab.CHATS, n.currentState.selectedTab)
    }

    @Test fun firstRunRetirementAndPublicationCannotReplayAnOldAttempt() {
        val n = NavigationCoordinator()
        n.installOnboardingSessionCapture { { true } }
        n.selectTab(AppTab.SETTINGS)
        val presented = n.publishedNavigation.value
        val retired = n.captureFirstRunOnboarding(presented)
        assertNotNull(retired)
        val gate = n.registerChatContactMount(ChatContactMount(null))
        var lateCompletion: Boolean? = null
        n.installCommitBoundary { action ->
            action()
            if (lateCompletion == null) {
                lateCompletion = false
                lateCompletion = n.completeFirstRunOnboarding(retired, MountedOnboardingProducer.NoDevice)
            }
        }
        try {
            assertTrue(n.reportFirstRunOnboardingFailure(retired))
            assertEquals(false, lateCompletion)
            assertTrue(n.currentState.failure is NavigationFailure.StaleOnboarding)
            assertEquals(presented, n.publishedNavigation.value)
        } finally {
            gate.release()
        }
        assertTrue(n.state.value.failure is NavigationFailure.StaleOnboarding)
        n.clearFailure()
        val cancelled = n.captureFirstRunOnboarding(n.publishedNavigation.value)
        assertNotNull(cancelled)
        n.retireFirstRunOnboarding(cancelled)
        assertFalse(n.completeFirstRunOnboarding(cancelled, MountedOnboardingProducer.NoDevice))
        assertFalse(n.reportFirstRunOnboardingFailure(cancelled))
    }

    @Test fun onboardingCompletionKeepsBackCleanupAndExpandedToolsSelectionSemantics() {
        val chats = NavigationCoordinator()
        chats.navigateToChat(contact, message)
        chats.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val chatSource = chats.publishedNavigation.value
        assertTrue(chats.completeOnboardingForTest(chatSource, chatSource.value.activeStack.last().id))
        assertEquals(ChatSelection.Direct(contact), chats.currentState.chatsSelectedRoute)
        assertNull(chats.currentState.pendingChatContact)
        assertNull(chats.currentState.pendingMessageScroll)

        val tools = NavigationCoordinator()
        tools.measureWindow(834f)
        tools.navigateToTool(ToolSelection.CLI)
        tools.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val toolSource = tools.publishedNavigation.value
        assertTrue(tools.completeOnboardingForTest(toolSource, toolSource.value.activeStack.last().id))
        assertEquals(AppTab.CHATS, tools.currentState.selectedTab)
        assertEquals(1, tools.currentState.stacks.getValue(AppTab.TOOLS).size)
        assertNull(tools.currentState.selectedTool)
    }

    @Test fun onboardingCompletionRejectsAnInactiveOrReactivatedPresentedEntry() {
        for (reactivate in listOf(false, true)) {
            val n = NavigationCoordinator()
            n.selectTab(AppTab.SETTINGS)
            n.navigate(FeatureRoute(FeatureId.ONBOARDING))
            val presented = n.publishedNavigation.value
            val entryId = presented.value.activeStack.last().id
            val gate = n.registerChatContactMount(ChatContactMount(null))
            try {
                n.selectTab(AppTab.MAP)
                if (reactivate) n.selectTab(AppTab.SETTINGS)
                val current = n.currentState
                assertEquals(presented, n.publishedNavigation.value)
                assertFalse(n.completeOnboardingForTest(presented, entryId))
                assertEquals(current, n.currentState)
                assertEquals(presented, n.publishedNavigation.value)
            } finally {
                gate.release()
            }
        }
    }

    @Test fun onboardingCompletionRejectsAnIdenticalReplacementAndWrongMountedEntry() {
        val n = NavigationCoordinator()
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val original = n.publishedNavigation.value
        val originalId = original.value.activeStack.last().id
        assertTrue(n.back(original))
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val replacement = n.publishedNavigation.value
        val replacementId = replacement.value.activeStack.last().id
        assertNotEquals(originalId, replacementId)
        assertEquals(original.value.activeStack.last().destination, replacement.value.activeStack.last().destination)
        val current = n.currentState

        assertFalse(n.completeOnboardingForTest(original, originalId))
        assertFalse(n.completeOnboardingForTest(replacement, originalId))
        assertEquals(current, n.currentState)
        assertTrue(n.completeOnboardingForTest(replacement, replacementId))
    }

    @Test fun onboardingCompletionRejectsASnapshotFromAnIdenticalReplacementCoordinator() {
        fun coordinator() = NavigationCoordinator().also {
            it.selectTab(AppTab.SETTINGS)
            it.navigate(FeatureRoute(FeatureId.ONBOARDING))
        }
        val retired = coordinator()
        val replacement = coordinator()
        val source = retired.publishedNavigation.value
        val current = replacement.publishedNavigation.value
        assertEquals(source.revision, current.revision)
        assertEquals(source.value, current.value)
        val entryId = source.value.activeStack.last().id

        assertFalse(replacement.completeOnboardingForTest(source, entryId))
        retired.installOnboardingSessionCapture { { true } }
        val retiredCompletion = retired.captureMountedOnboarding(source, entryId)
        assertNotNull(retiredCompletion)
        assertFalse(replacement.completeMountedOnboarding(retiredCompletion, MountedOnboardingProducer.NoDevice))
        assertEquals(current.value, replacement.currentState)
        assertTrue(replacement.completeOnboardingForTest(current, entryId))
    }

    @Test fun onboardingCompletionAcceptsFirstBindingButRejectsSameRadioSessionReplacement() {
        val first = SessionToken(ProcessEpoch(UUID(0, 81)), Generation(1), radio)
        val second = SessionToken(ProcessEpoch(UUID(0, 81)), Generation(2), radio)
        for (alreadyBound in listOf(false, true)) {
            val n = NavigationCoordinator()
            if (alreadyBound) assertTrue(n.bindNotificationSession(radio, null, first))
            n.selectTab(AppTab.SETTINGS)
            n.navigate(FeatureRoute(FeatureId.ONBOARDING))
            val presented = n.publishedNavigation.value
            assertTrue(n.bindNotificationSession(radio, null, if (alreadyBound) second else first))
            val replacement = n.currentState

            if (alreadyBound) {
                assertFalse(n.completeOnboardingForTest(presented, presented.value.activeStack.last().id))
                assertEquals(replacement, n.currentState)
            } else {
                assertTrue(n.completeOnboardingForTest(presented, presented.value.activeStack.last().id))
                assertEquals(AppTab.CHATS, n.currentState.selectedTab)
                assertEquals(first, n.currentState.boundSessionToken)
            }
        }
    }

    @Test fun onboardingCompletionAcceptsTheFirstBindingAfterAnOfflineRetainedRadio() {
        val epoch = ProcessEpoch(UUID(0, 85))
        val first = SessionToken(epoch, Generation(1), radio)
        for (nextRadio in listOf(radio, otherRadio)) {
            val n = NavigationCoordinator()
            assertTrue(n.bindNotificationSession(radio, null, first))
            n.clearPendingLinks()
            assertEquals(radio, n.currentState.radioId)
            assertNull(n.currentState.boundSessionToken)
            n.selectTab(AppTab.SETTINGS)
            n.navigate(FeatureRoute(FeatureId.ONBOARDING))
            val presented = n.publishedNavigation.value
            val successor = SessionToken(epoch, Generation(2), nextRadio)
            n.installOnboardingSessionCapture { { it == MountedOnboardingProducer.Preset(successor) } }
            val completion = n.captureMountedOnboarding(presented, presented.value.activeStack.last().id)
            if (nextRadio != radio) {
                assertTrue(n.clearPerRadioSelectionWhen { true })
                val cleared = n.currentState
                assertFalse(n.completeMountedOnboarding(completion, MountedOnboardingProducer.Preset(successor)))
                assertEquals(cleared, n.currentState)
            }
            assertTrue(n.bindNotificationSession(nextRadio, null, successor))
            val bound = n.currentState
            val wrong = successor.copy(generation = Generation(3))
            assertFalse(n.completeMountedOnboarding(completion, MountedOnboardingProducer.Preset(wrong)))
            assertEquals(bound, n.currentState)
            assertTrue(n.completeMountedOnboarding(completion, MountedOnboardingProducer.Preset(successor)))
            assertEquals(AppTab.CHATS, n.currentState.selectedTab)
            assertEquals(successor, n.currentState.boundSessionToken)
        }
        for (guardedCleanup in listOf(false, true)) {
            val n = NavigationCoordinator()
            assertTrue(n.bindNotificationSession(radio, null, first))
            n.clearPendingLinks()
            n.selectTab(AppTab.SETTINGS)
            n.navigate(FeatureRoute(FeatureId.ONBOARDING))
            val presented = n.publishedNavigation.value
            val successor = SessionToken(epoch, Generation(2), otherRadio)
            n.installOnboardingSessionCapture { { it == MountedOnboardingProducer.Preset(successor) } }
            val completion = n.captureMountedOnboarding(presented, presented.value.activeStack.last().id)
            if (guardedCleanup) assertTrue(n.clearPerRadioSelectionWhen { true })
            n.clearPendingLinks()
            assertTrue(n.bindNotificationSession(otherRadio, null, successor))
            val current = n.currentState
            assertFalse(n.completeMountedOnboarding(completion, MountedOnboardingProducer.Preset(successor)))
            assertEquals(current, n.currentState)
        }
    }

    @Test fun onboardingCompletionRequiresItsSessionWitnessAtTheCommitBoundary() {
        val n = NavigationCoordinator()
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        val entryId = presented.value.activeStack.last().id
        assertFalse(n.completeMountedOnboarding(
            n.captureMountedOnboarding(presented, entryId), MountedOnboardingProducer.NoDevice,
        ))

        var current = true
        n.installOnboardingSessionCapture { { current } }
        val witness = n.captureMountedOnboarding(presented, entryId)
        val before = n.currentState
        n.installCommitBoundary { action -> current = false; action() }
        assertFalse(n.completeMountedOnboarding(witness, MountedOnboardingProducer.NoDevice))
        assertEquals(before, n.currentState)
    }

    @Test fun firstBindingCannotOverrideARejectedOnboardingSessionWitness() {
        val token = SessionToken(ProcessEpoch(UUID(0, 83)), Generation(1), radio)
        val n = NavigationCoordinator()
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        n.installOnboardingSessionCapture { { false } }
        val completion = n.captureMountedOnboarding(presented, presented.value.activeStack.last().id)
        assertTrue(n.bindNotificationSession(radio, null, token))
        val current = n.currentState
        assertFalse(n.completeMountedOnboarding(completion, MountedOnboardingProducer.NoDevice))
        assertEquals(current, n.currentState)
    }

    @Test fun onboardingProducerIsValidatedInsideTheCommitBoundary() {
        val first = SessionToken(ProcessEpoch(UUID(0, 84)), Generation(1), radio)
        val second = first.copy(generation = Generation(2))
        val n = NavigationCoordinator()
        assertTrue(n.bindNotificationSession(radio, null, first))
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        var authority = first
        var checks = 0
        n.installOnboardingSessionCapture {
            { producer ->
                checks++
                producer == MountedOnboardingProducer.Preset(authority)
            }
        }
        val completion = n.captureMountedOnboarding(presented, presented.value.activeStack.last().id)
        val before = n.currentState
        n.installCommitBoundary { action -> authority = second; action() }

        assertFalse(n.completeMountedOnboarding(completion, MountedOnboardingProducer.Preset(first)))
        assertEquals(before, n.currentState)
        assertEquals(1, checks)
        n.installCommitBoundary { action -> authority = first; action() }
        assertTrue(n.completeMountedOnboarding(completion, MountedOnboardingProducer.Preset(first)))
        assertEquals(2, checks)
        assertEquals(AppTab.CHATS, n.currentState.selectedTab)
    }

    @Test fun retiredOnboardingAttemptCannotAuthorizeOrReportForItsReplacement() {
        val n = NavigationCoordinator()
        n.installOnboardingSessionCapture { { true } }
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        val entry = presented.value.activeStack.last()
        val retired = n.captureMountedOnboarding(presented, entry.id)
        val before = n.currentState
        var injected = false
        n.installCommitBoundary { action ->
            if (!injected) {
                injected = true
                n.retireMountedOnboarding(retired)
            }
            action()
        }

        assertFalse(n.completeMountedOnboarding(retired, MountedOnboardingProducer.NoDevice))
        assertTrue(injected)
        assertFalse(n.reportMountedOnboardingFailure(retired))
        assertEquals(before, n.currentState)
        val replacement = n.captureMountedOnboarding(presented, entry.id)
        assertTrue(n.completeMountedOnboarding(replacement, MountedOnboardingProducer.NoDevice))
        assertEquals(AppTab.CHATS, n.currentState.selectedTab)
    }

    @Test fun onboardingFailureIsReportedOnlyForTheStillActiveMountedEntry() {
        for (replacement in listOf("none", "tab", "reactivated", "entry")) {
            val n = NavigationCoordinator()
            n.installOnboardingSessionCapture { { false } }
            n.selectTab(AppTab.SETTINGS)
            n.navigate(FeatureRoute(FeatureId.ONBOARDING))
            val presented = n.publishedNavigation.value
            val completion = n.captureMountedOnboarding(presented, presented.value.activeStack.last().id)
            when (replacement) {
                "tab" -> n.selectTab(AppTab.MAP)
                "reactivated" -> {
                    n.selectTab(AppTab.MAP)
                    n.selectTab(AppTab.SETTINGS)
                }
                "entry" -> {
                    assertTrue(n.back(presented))
                    n.navigate(FeatureRoute(FeatureId.ONBOARDING))
                }
            }
            val before = n.currentState
            assertFalse(n.completeMountedOnboarding(completion, MountedOnboardingProducer.NoDevice))
            if (replacement == "none") {
                assertTrue(n.reportMountedOnboardingFailure(completion))
                assertTrue(n.currentState.failure is NavigationFailure.StaleOnboarding)
                assertEquals(before.copy(failure = n.currentState.failure), n.currentState)
            } else {
                assertFalse(n.reportMountedOnboardingFailure(completion))
                assertEquals(before, n.currentState)
            }
        }
    }

    @Test fun onboardingFailureDoesNotOverwriteAnExistingUnrelatedFailure() {
        val n = NavigationCoordinator()
        var sessionCurrent = false
        n.installOnboardingSessionCapture { { sessionCurrent } }
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        val completion = n.captureMountedOnboarding(presented, presented.value.activeStack.last().id)
        n.requestRoomAuthentication(room.copy(role = RemoteNodeRole.REPEATER))
        val before = n.currentState
        assertEquals(NavigationFailure.WrongTarget(NavigationTarget.ROOM), before.failure)

        assertFalse(n.completeMountedOnboarding(completion, MountedOnboardingProducer.NoDevice))
        assertFalse(n.reportMountedOnboardingFailure(completion))
        assertEquals(before, n.currentState)
        n.clearFailure()
        assertTrue(n.reportMountedOnboardingFailure(completion))
        sessionCurrent = true
        assertFalse(n.completeMountedOnboarding(completion, MountedOnboardingProducer.NoDevice))
        val first = n.currentState.failure
        assertTrue(first is NavigationFailure.StaleOnboarding)
        n.clearFailure(first)
        val replacement = n.captureMountedOnboarding(n.publishedNavigation.value, presented.value.activeStack.last().id)
        assertTrue(n.reportMountedOnboardingFailure(replacement))
        val second = n.currentState.failure
        assertTrue(second is NavigationFailure.StaleOnboarding)
        assertNotEquals(first, second)
        n.clearFailure(first)
        assertEquals(second, n.currentState.failure)
    }

    @Test fun onboardingRejectionPreservesAnUnreleasedPublicationPredecessor() {
        val n = NavigationCoordinator()
        n.installOnboardingSessionCapture { { true } }
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        val completion = n.captureMountedOnboarding(presented, presented.value.activeStack.last().id)
        val gate = n.registerChatContactMount(ChatContactMount(null))
        var interleave = true
        var lateCompletion: Boolean? = null
        n.installCommitBoundary { action ->
            action()
            if (interleave) {
                interleave = false
                lateCompletion = n.completeMountedOnboarding(completion, MountedOnboardingProducer.NoDevice)
            }
        }
        try {
            assertTrue(n.reportMountedOnboardingFailure(completion))
            assertEquals(false, lateCompletion)
            assertTrue(n.currentState.failure is NavigationFailure.StaleOnboarding)
            assertEquals(AppTab.SETTINGS, n.currentState.selectedTab)
            assertEquals(presented, n.publishedNavigation.value)
        } finally {
            gate.release()
        }
        assertTrue(n.state.value.failure is NavigationFailure.StaleOnboarding)
    }

    @Test fun onboardingCompletionRechecksSourceAfterCompetingLifecycleCommit() {
        val first = SessionToken(ProcessEpoch(UUID(0, 82)), Generation(1), radio)
        val second = SessionToken(ProcessEpoch(UUID(0, 82)), Generation(2), radio)
        for (change in listOf("tab", "entry", "session")) {
            val n = NavigationCoordinator()
            assertTrue(n.bindNotificationSession(radio, null, first))
            n.selectTab(AppTab.SETTINGS)
            n.navigate(FeatureRoute(FeatureId.ONBOARDING))
            val presented = n.publishedNavigation.value
            var injected = false
            var competing: NavigationState? = null
            n.installCommitBoundary { action ->
                if (!injected) {
                    injected = true
                    when (change) {
                        "tab" -> n.selectTab(AppTab.MAP)
                        "entry" -> {
                            assertTrue(n.back(presented))
                            n.navigate(FeatureRoute(FeatureId.ONBOARDING))
                        }
                        "session" -> assertTrue(n.bindNotificationSession(radio, null, second))
                    }
                    competing = n.currentState
                }
                action()
            }

            assertFalse(n.completeOnboardingForTest(presented, presented.value.activeStack.last().id))
            assertTrue(injected)
            assertEquals(competing, n.currentState)
        }
    }

    @Test fun onboardingCompletionRetriesAnUnrelatedConcurrentWidthChange() {
        val n = NavigationCoordinator()
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        var attempts = 0
        n.installCommitBoundary { action ->
            attempts++
            if (attempts == 1) n.measureWindow(834f)
            action()
        }

        assertTrue(n.completeOnboardingForTest(presented, presented.value.activeStack.last().id))
        assertEquals(2, attempts)
        assertEquals(834f, n.currentState.widthDp, 0f)
        assertEquals(AppTab.CHATS, n.currentState.selectedTab)
    }

    @Test fun onboardingCompletionPreservesAnUnreleasedPublicationPredecessor() {
        val n = NavigationCoordinator()
        n.selectTab(AppTab.SETTINGS)
        n.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val presented = n.publishedNavigation.value
        val gate = n.registerChatContactMount(ChatContactMount(null))
        try {
            assertTrue(n.completeOnboardingForTest(presented, presented.value.activeStack.last().id))
            assertEquals(AppTab.CHATS, n.currentState.selectedTab)
            assertEquals(presented, n.publishedNavigation.value)
        } finally {
            gate.release()
        }
        assertEquals(AppTab.CHATS, n.state.value.selectedTab)
        assertEquals(n.currentState, n.state.value)
    }

    @Test fun finiteSnapshotWriterContentionCannotReopenASealedChatMountOrReleaseItsPredecessor() = runTest {
        val coordinator = NavigationCoordinator()
        coordinator.navigateToChat(contact)
        val mount = ChatContactMount(coordinator.currentState.activeStack.last().id)
        coordinator.registerChatContactMount(mount).release()
        val held = ContactLinkPublications()
        coordinator.captureContactLinkLookup(contact.publicKey, radio, null, null, null, true, held, mount)
        val before = coordinator.state.value
        val start = CompletableDeferred<Unit>()
        val writers = List(4) { writer ->
            async(Dispatchers.Default) {
                start.await()
                repeat(500) { turn ->
                    coordinator.selectTab(AppTab.entries[(turn + writer) % AppTab.entries.size])
                }
            }
        }
        val sealing = async(Dispatchers.Default) {
            start.await()
            coordinator.sealChatContactMount(mount)
        }
        try {
            start.complete(Unit)
            // Finite writers permit eventual completion; this is not a wait-free latency assertion.
            writers.forEach { it.await() }
            sealing.await().release()
            repeat(50) {
                coordinator.registerChatContactMount(mount).release()
                val revision = coordinator.currentState.contactLookupRevision
                val capture = ContactLinkPublications().use { publications ->
                    coordinator.captureContactLinkLookup(contact.publicKey, radio, null, null, null, true, publications, mount)
                }
                assertEquals(ContactLinkCaptureDisposition.StaleContext, capture.disposition)
                assertEquals(revision, coordinator.currentState.contactLookupRevision)
            }
            assertEquals(before, coordinator.state.value)
            held.release()
            assertEquals(coordinator.currentState, coordinator.state.value)
        } finally {
            start.complete(Unit)
            writers.forEach { it.cancelAndJoin() }
            sealing.cancelAndJoin()
            held.release()
        }
    }

    @Test fun inactiveDirectChannelAndRoomMountsCannotCaptureOrResolveContactLookups() {
        for (source in listOf(ChatSelection.Direct(contact), ChatSelection.Channel(channel), ChatSelection.Room(room))) {
            for (switchTab in listOf(false, true)) {
                val coordinator = NavigationCoordinator()
                when (source) {
                    is ChatSelection.Direct -> coordinator.navigateToChat(source.contact)
                    is ChatSelection.Channel -> coordinator.navigateToChannel(source.channel)
                    is ChatSelection.Room -> coordinator.navigateToRoom(source.session)
                }
                val mount = ChatContactMount(coordinator.currentState.activeStack.last().id)
                coordinator.registerChatContactMount(mount).release()
                val captured = ContactLinkPublications().use { publications ->
                    coordinator.captureContactLinkLookup(contact.publicKey, radio, null, null, null, true, publications, mount)
                }
                assertEquals(ContactLinkCaptureDisposition.Lookup, captured.disposition)
                val next = contact.copy(id = UUID.randomUUID(), publicKey = Bytes(ByteArray(32) { 0xCC.toByte() }))
                if (switchTab) coordinator.selectTab(AppTab.MAP) else coordinator.navigateToChat(next)
                assertFalse(mount.registrationSealed.get())
                assertEquals(
                    ContactLinkLookupResolution.Stale,
                    coordinator.resolveCapturedContactLookup(captured, null, true, null),
                )
                val revision = coordinator.currentState.contactLookupRevision
                val rejected = ContactLinkPublications().use { publications ->
                    coordinator.captureContactLinkLookup(contact.publicKey, radio, null, null, null, true, publications, mount)
                }
                assertEquals(ContactLinkCaptureDisposition.StaleContext, rejected.disposition)
                assertEquals(revision, coordinator.currentState.contactLookupRevision)
                if (switchTab) {
                    assertEquals(AppTab.MAP, coordinator.currentState.selectedTab)
                    assertEquals(source, coordinator.currentState.chatsSelectedRoute)
                } else {
                    assertEquals(ChatSelection.Direct(next), coordinator.currentState.chatsSelectedRoute)
                }
            }
        }
    }

    @Test fun publishedRevisionDoesNotExposeADeferredNavigationCommit() {
        val initial = NavigationState()
        val store = NavigationStateStore(initial)
        val observed = store.publishedSnapshots.value
        val guarded = NavigationStateStore.Publication()
        assertTrue(store.compareAndSet(store.snapshot(), initial.copy(selectedTab = AppTab.NODES), guarded))
        store.update { it.copy(selectedTab = AppTab.MAP) }

        assertEquals(2L, store.snapshot().revision)
        assertEquals(0L, store.publishedSnapshots.value.revision)
        assertSame(initial, store.publishedSnapshots.value.value)
        assertSame(initial, store.state.value)

        store.release(guarded)
        val published = store.publishedSnapshots.value
        assertEquals(2L, published.revision)
        assertEquals(AppTab.MAP, published.value.selectedTab)
        assertEquals(published.value, store.state.value)
        assertEquals(0L, observed.revision)
        assertSame(initial, observed.value)
    }

    @Test fun publishedRevisionsRemainObservableWhenNavigationValuesAreEqual() = runTest {
        val initial = NavigationState()
        val store = NavigationStateStore(initial)
        val revisions = mutableListOf<Long>()
        val states = mutableListOf<NavigationState>()
        backgroundScope.launch { store.publishedSnapshots.collect { revisions.add(it.revision) } }
        backgroundScope.launch { store.state.collect { states.add(it) } }
        runCurrent()

        assertTrue(store.compareAndSet(store.snapshot(), initial, forceRevision = true))
        runCurrent()

        assertEquals(listOf(0L, 1L), revisions)
        assertEquals(listOf(initial), states)
        assertEquals(1L, store.publishedSnapshots.value.revision)
        assertSame(initial, store.publishedSnapshots.value.value)
    }

    @Test fun publishedRevisionStreamWaitsForEveryOutstandingGuard() = runTest {
        val initial = NavigationState()
        val store = NavigationStateStore(initial)
        val snapshots = mutableListOf<NavigationStateStore.PublishedSnapshot>()
        backgroundScope.launch { store.publishedSnapshots.collect { snapshots.add(it) } }
        runCurrent()

        val first = NavigationStateStore.Publication()
        val second = NavigationStateStore.Publication()
        assertTrue(store.compareAndSet(store.snapshot(), initial.copy(selectedTab = AppTab.NODES), first))
        assertTrue(store.compareAndSet(store.snapshot(), initial.copy(selectedTab = AppTab.MAP), second))
        store.release(second)
        runCurrent()
        assertEquals(listOf(0L), snapshots.map { it.revision })
        assertSame(initial, snapshots.single().value)

        store.release(first)
        runCurrent()
        assertEquals(listOf(0L, 2L), snapshots.map { it.revision })
        assertEquals(AppTab.MAP, snapshots.last().value.selectedTab)
    }

    @Test fun coherentContactCaptureRejectsReplacementGraphBeforeItsNavigationBinding() {
        val n = NavigationCoordinator()
        val epoch = ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000041"))
        val tokenA = SessionToken(epoch, Generation(1), radio)
        val tokenB = SessionToken(epoch, Generation(2), radio)
        assertTrue(n.bindNotificationSession(radio, null, tokenA))
        val captured = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, radio, null, tokenA, tokenA, true, it)
        }
        val before = n.currentState
        for (runtimeToken in listOf(null, tokenB)) {
            val mixed = ContactLinkPublications().use {
                n.captureContactLinkLookup(contact.publicKey, radio, null, tokenB, runtimeToken, true, it)
            }
            assertEquals(ContactLinkCaptureDisposition.StaleContext, mixed.disposition)
            assertEquals(before, n.currentState)
        }
        assertTrue(n.bindNotificationSession(radio, null, tokenB))
        val replacement = n.currentState
        for (selectChats in listOf(false, true)) {
            val outcome = ContactLinkPublications().use {
                n.completeCapturedContactLookup(
                    captured, ContactLinkRequest("Old", contact.publicKey, ContactType.CHAT), contact,
                    tokenB, false, null, selectChats = selectChats, publications = it,
                )
            }
            assertEquals(ContactLinkCompletion.StaleContext, outcome)
            assertEquals(replacement, n.currentState)
        }
    }

    @Test fun capturedContactLookupValidationSharesCompletionRevisionAndInitialBindingRules() {
        val n = NavigationCoordinator()
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000049")), Generation(1), radio,
        )
        val original = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, null, null, null, null, true, it)
        }
        val offline = n.currentState
        assertTrue(n.isCapturedContactLookupCurrent(original, null, false))
        assertSame(offline, n.currentState)
        assertTrue(n.bindNotificationSession(radio, null, token))
        val bound = n.currentState
        assertTrue(n.isCapturedContactLookupCurrent(original, token, true))
        assertFalse(n.isCapturedContactLookupCurrent(original, token, false))
        assertSame(bound, n.currentState)

        val newer = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, radio, null, token, token, true, it)
        }
        val afterCapture = n.currentState
        assertFalse(n.isCapturedContactLookupCurrent(original, token, true))
        assertTrue(n.isCapturedContactLookupCurrent(newer, token, false))
        assertSame(afterCapture, n.currentState)
        assertEquals(ContactLinkCompletion.ConfirmationStaged, ContactLinkPublications().use {
            n.completeCapturedContactLookup(
                newer, ContactLinkRequest("Current", contact.publicKey, ContactType.REPEATER),
                null, token, false, null, false, it,
            )
        })
        val afterCompletion = n.currentState
        assertFalse(n.isCapturedContactLookupCurrent(newer, token, false))
        assertSame(afterCompletion, n.currentState)
    }

    @Test fun coherentContactCaptureAllowsOnlyItsFirstBindingAndKeepsTargetMetadata() {
        val n = NavigationCoordinator()
        val captured = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, null, null, null, null, true, it)
        }
        val unbound = n.resolveCapturedContactLookup(captured, null, true, null) as ContactLinkLookupResolution.Current
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000042")), Generation(1), radio,
        )
        assertTrue(n.bindNotificationSession(radio, null, token))
        val link = ContactLinkRequest("Untrusted alias", contact.publicKey, ContactType.REPEATER)
        val bound = n.currentState
        assertEquals(ContactLinkResolvedCompletion.TargetChanged, ContactLinkPublications().use {
            n.completeResolvedCapturedContactLookup(
                captured, unbound, link, null, token, true, null, selectChats = false, publications = it,
            )
        })
        assertSame(bound, n.currentState)
        assertEquals(ContactLinkCompletion.StaleContext, ContactLinkPublications().use {
            n.completeCapturedContactLookup(captured, link, null, token, true, null, selectChats = false, publications = it)
        })
        assertSame(bound, n.currentState)
        val resolved = n.resolveCapturedContactLookup(captured, token, true, null) as ContactLinkLookupResolution.Current
        assertEquals(radio, resolved.radioId)
        assertEquals(ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.ConfirmationStaged), ContactLinkPublications().use {
            n.completeResolvedCapturedContactLookup(
                captured, resolved, link, null, token, true, null, selectChats = false, publications = it,
            )
        })
        val pending = requireNotNull(n.state.value.pendingContactLink)
        assertEquals(radio, pending.targetRadioId)
        assertEquals(n.state.value.generation, pending.generation)
        assertEquals(link.name, pending.name)
        assertEquals(link.type, pending.type)
        assertEquals(link.publicKey, pending.publicKey)
        assertNotNull(pending.deliveryId)
        val beforeReplay = n.currentState
        assertEquals(ContactLinkCompletion.StaleContext, ContactLinkPublications().use {
            n.completeCapturedContactLookup(captured, link, null, token, true, null, selectChats = false, publications = it)
        })
        assertEquals(beforeReplay, n.currentState)

        val self = NavigationCoordinator()
        val selfLookup = ContactLinkPublications().use {
            self.captureContactLinkLookup(contact.publicKey, null, null, null, null, true, it)
        }
        assertTrue(self.bindNotificationSession(radio, contact.publicKey, token))
        val selfTarget = self.resolveCapturedContactLookup(
            selfLookup, token, true, contact.publicKey,
        ) as ContactLinkLookupResolution.Current
        assertEquals(ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.IgnoredSelfContact), ContactLinkPublications().use {
            self.completeResolvedCapturedContactLookup(
                selfLookup, selfTarget, link, null, token, true, contact.publicKey, selectChats = false, publications = it,
            )
        })
        assertNull(self.state.value.pendingContactLink)
    }

    @Test fun capturedContactResolutionIdentifiesTheFirstGraphBeforeNavigationBinding() {
        val n = NavigationCoordinator()
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-00000000004a")), Generation(1), radio,
        )
        val captured = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, null, null, null, null, true, it)
        }
        val offline = n.resolveCapturedContactLookup(captured, null, true, null) as ContactLinkLookupResolution.Current
        assertNull(offline.radioId)
        assertNull(offline.selfPublicKey)
        val before = n.currentState
        val target = n.resolveCapturedContactLookup(captured, token, true, room.publicKey) as ContactLinkLookupResolution.Current
        assertEquals(radio, target.radioId)
        assertEquals(room.publicKey, target.selfPublicKey)
        assertSame(before, n.currentState)
        val link = ContactLinkRequest("First graph", contact.publicKey, ContactType.REPEATER)
        assertEquals(ContactLinkResolvedCompletion.TargetChanged, ContactLinkPublications().use {
            n.completeResolvedCapturedContactLookup(captured, offline, link, null, token, true, room.publicKey, false, it)
        })
        assertSame(before, n.currentState)
        assertEquals(ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.Navigated), ContactLinkPublications().use {
            n.completeResolvedCapturedContactLookup(captured, target, link, contact, token, true, room.publicKey, false, it)
        })
        assertEquals(contact, n.currentState.selectedContact)
        assertNull(n.currentState.pendingContactLink)
        assertTrue(n.bindNotificationSession(radio, room.publicKey, token))
        assertEquals(contact, n.currentState.selectedContact)
    }

    @Test fun capturedContactResolutionBindsSelfIdentityAndRejectsForeignSnapshots() {
        val n = NavigationCoordinator()
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-00000000004b")), Generation(1), radio,
        )
        val captured = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, radio, null, null, null, true, it)
        }
        val beforeBinding = n.resolveCapturedContactLookup(captured, null, true, null) as ContactLinkLookupResolution.Current
        assertEquals(radio, beforeBinding.radioId)
        assertNull(beforeBinding.selfPublicKey)
        val foreignNavigation = NavigationCoordinator()
        val foreignCapture = ContactLinkPublications().use {
            foreignNavigation.captureContactLinkLookup(contact.publicKey, radio, null, null, null, true, it)
        }
        val foreignTarget = foreignNavigation.resolveCapturedContactLookup(
            foreignCapture, null, true, null,
        ) as ContactLinkLookupResolution.Current
        val link = ContactLinkRequest("Self", contact.publicKey, ContactType.CHAT)
        val beforeForeign = n.currentState
        assertEquals(ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.StaleContext), ContactLinkPublications().use {
            n.completeResolvedCapturedContactLookup(captured, foreignTarget, link, null, null, true, null, false, it)
        })
        assertSame(beforeForeign, n.currentState)

        assertTrue(n.bindNotificationSession(radio, contact.publicKey, token))
        val bound = n.currentState
        assertEquals(ContactLinkResolvedCompletion.TargetChanged, ContactLinkPublications().use {
            n.completeResolvedCapturedContactLookup(
                captured, beforeBinding, link, null, token, true, contact.publicKey, false, it,
            )
        })
        assertSame(bound, n.currentState)
        val selfTarget = n.resolveCapturedContactLookup(
            captured, token, true, contact.publicKey,
        ) as ContactLinkLookupResolution.Current
        assertEquals(radio, selfTarget.radioId)
        assertEquals(contact.publicKey, selfTarget.selfPublicKey)
        assertEquals(ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.IgnoredSelfContact), ContactLinkPublications().use {
            n.completeResolvedCapturedContactLookup(
                captured, selfTarget, link, null, token, true, contact.publicKey, false, it,
            )
        })
        assertNull(n.currentState.pendingContactLink)
        assertNull(n.currentState.selectedContact)
    }

    @Test fun capturedContactCompletionChecksTheExactTargetAndPublishesAfterTheOuterGuard() = runTest {
        val n = NavigationCoordinator()
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000043")), Generation(1), radio,
        )
        assertTrue(n.bindNotificationSession(radio, null, token))
        val guard = Any()
        var observed = false
        val observer = backgroundScope.launch(Dispatchers.Unconfined) {
            n.state.collect {
                if (it.selectedContact != null) {
                    assertFalse(Thread.holdsLock(guard))
                    observed = true
                    if (it.selectedSetting == null) n.navigateToSetting(SettingsDetail.LANGUAGE)
                }
            }
        }
        val capturePublication = ContactLinkPublications()
        val captured = synchronized(guard) {
            n.captureContactLinkLookup(contact.publicKey, radio, null, token, token, true, capturePublication)
        }
        capturePublication.release()
        val publication = ContactLinkPublications()
        val cancellation = CancellationException("cancelled after commit")
        val thrown = assertThrows(CancellationException::class.java) {
            publication.use {
                synchronized(guard) {
                    assertEquals(ContactLinkCompletion.Navigated, n.completeCapturedContactLookup(
                        captured, ContactLinkRequest("Alias", contact.publicKey, ContactType.CHAT), contact,
                        token, false, null, selectChats = false, publications = publication,
                    ))
                    assertEquals(contact, n.currentState.selectedContact)
                    assertNull(n.state.value.selectedContact)
                    assertFalse(observed)
                    throw cancellation
                }
            }
        }
        assertSame(cancellation, thrown)
        assertTrue(observed)
        assertEquals(SettingsDetail.LANGUAGE, n.state.value.selectedSetting)
        observer.cancelAndJoin()

        for (selectChats in listOf(false, true)) for (wrongContact in listOf(
            contact.copy(radioId = otherRadio), contact.copy(publicKey = room.publicKey),
        )) {
            val wrongLookup = ContactLinkPublications().use {
                n.captureContactLinkLookup(contact.publicKey, radio, null, token, token, true, it)
            }
            val beforeTab = n.currentState.selectedTab
            assertEquals(ContactLinkCompletion.WrongTarget, ContactLinkPublications().use {
                n.completeCapturedContactLookup(
                    wrongLookup, ContactLinkRequest("Alias", contact.publicKey, ContactType.CHAT),
                    wrongContact, token, false, null, selectChats = selectChats, publications = it,
                )
            })
            assertEquals(NavigationFailure.WrongTarget(NavigationTarget.CONTACT), n.state.value.failure)
            assertEquals(contact, n.state.value.selectedContact)
            assertEquals(beforeTab, n.state.value.selectedTab)
        }
    }

    @Test fun capturedContactTabIntentCommitsAtomicallyForChatAndExternalOutcomes() = runTest {
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000044")), Generation(1), radio,
        )
        for (selectChats in listOf(false, true)) for (flow in listOf(
            "confirmation", "captured-self", "first-binding-self", "existing",
        )) {
            val n = NavigationCoordinator()
            val firstBinding = flow == "first-binding-self"
            val capturedSelf = flow == "captured-self"
            val selfKey = contact.publicKey.takeIf { capturedSelf }
            if (!firstBinding) assertTrue(n.bindNotificationSession(radio, selfKey, token))
            n.selectTab(AppTab.MAP)
            val captured = ContactLinkPublications().use {
                n.captureContactLinkLookup(
                    contact.publicKey, radio.takeUnless { firstBinding }, selfKey,
                    token.takeUnless { firstBinding }, token.takeUnless { firstBinding }, true, it,
                )
            }
            if (firstBinding) assertTrue(n.bindNotificationSession(radio, contact.publicKey, token))
            val before = n.state.value
            val guard = Any()
            val observed = mutableListOf<NavigationState>()
            val observer = backgroundScope.launch(Dispatchers.Unconfined) {
                n.state.collect {
                    assertFalse(Thread.holdsLock(guard))
                    observed += it
                }
            }
            val expectedTab = when {
                flow == "existing" -> AppTab.NODES
                selectChats -> AppTab.CHATS
                else -> AppTab.MAP
            }
            val expectedOutcome = when (flow) {
                "captured-self", "first-binding-self" -> ContactLinkCompletion.IgnoredSelfContact
                "existing" -> ContactLinkCompletion.Navigated
                else -> ContactLinkCompletion.ConfirmationStaged
            }
            val target = n.resolveCapturedContactLookup(
                captured, token, firstBinding, contact.publicKey.takeIf { firstBinding },
            ) as ContactLinkLookupResolution.Current
            ContactLinkPublications().use { publications ->
                synchronized(guard) {
                    assertEquals(ContactLinkResolvedCompletion.Completed(expectedOutcome), n.completeResolvedCapturedContactLookup(
                        captured, target, ContactLinkRequest("Alias", contact.publicKey, ContactType.REPEATER),
                        contact.takeIf { flow == "existing" }, token, firstBinding,
                        contact.publicKey.takeIf { firstBinding }, selectChats, publications,
                    ))
                    assertEquals(expectedTab, n.currentState.selectedTab)
                    assertSame(before, n.state.value)
                    assertEquals(listOf(before), observed)
                    when (expectedOutcome) {
                        ContactLinkCompletion.ConfirmationStaged -> {
                            val staged = requireNotNull(n.currentState.pendingContactLink)
                            assertEquals(radio, staged.targetRadioId)
                            assertEquals("Alias", staged.name)
                            assertEquals(ContactType.REPEATER, staged.type)
                            assertNotNull(staged.deliveryId)
                        }
                        ContactLinkCompletion.Navigated -> assertEquals(contact, n.currentState.selectedContact)
                        else -> assertNull(n.currentState.pendingContactLink)
                    }
                }
            }
            val committed = n.currentState
            assertEquals(committed, n.state.value)
            assertEquals(expectedTab, committed.selectedTab)
            assertEquals(before.stacks.getValue(AppTab.MAP), committed.stacks.getValue(AppTab.MAP))
            assertEquals(listOf(before, committed).distinct(), observed)
            observer.cancelAndJoin()
        }
    }

    @Test fun staleCapturedSelfContactCannotApplyExternalTabIntentAfterReplacement() {
        val n = NavigationCoordinator()
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000045")), Generation(1), radio,
        )
        assertTrue(n.bindNotificationSession(radio, contact.publicKey, token))
        val captured = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, radio, contact.publicKey, token, token, true, it)
        }
        assertEquals(ContactLinkCaptureDisposition.IgnoredSelfContact, captured.disposition)
        val replacement = token.copy(generation = Generation(2))
        assertTrue(n.bindNotificationSession(radio, contact.publicKey, replacement))
        n.selectTab(AppTab.MAP)
        val before = n.currentState
        for (selectChats in listOf(false, true)) {
            assertEquals(ContactLinkCompletion.StaleContext, ContactLinkPublications().use {
                n.completeCapturedContactLookup(
                    captured, ContactLinkRequest("Self", contact.publicKey, ContactType.CHAT), null,
                    replacement, false, null, selectChats, it,
                )
            })
            assertEquals(before, n.currentState)
            assertEquals(before, n.state.value)
        }
    }

    @Test fun selfContactCaptureSupersedesEarlierContactLookup() {
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000046")), Generation(1), radio,
        )
        for (found in listOf(false, true)) for (selectChats in listOf(false, true)) {
            val n = NavigationCoordinator()
            assertTrue(n.bindNotificationSession(radio, room.publicKey, token))
            n.selectTab(AppTab.MAP)
            val older = ContactLinkPublications().use {
                n.captureContactLinkLookup(contact.publicKey, radio, room.publicKey, token, token, true, it)
            }
            val self = ContactLinkPublications().use {
                n.captureContactLinkLookup(room.publicKey, radio, room.publicKey, token, token, true, it)
            }
            assertEquals(ContactLinkCaptureDisposition.IgnoredSelfContact, self.disposition)
            val beforeOlderCompletion = n.currentState
            assertEquals(ContactLinkCompletion.StaleContext, ContactLinkPublications().use {
                n.completeCapturedContactLookup(
                    older, ContactLinkRequest("Older", contact.publicKey, ContactType.CHAT),
                    contact.takeIf { found }, token, false, null, false, it,
                )
            })
            assertEquals(beforeOlderCompletion, n.currentState)
            assertEquals(ContactLinkCompletion.IgnoredSelfContact, ContactLinkPublications().use {
                n.completeCapturedContactLookup(
                    self, ContactLinkRequest("Self", room.publicKey, ContactType.REPEATER),
                    null, token, false, null, selectChats, it,
                )
            })
            assertEquals(if (selectChats) AppTab.CHATS else AppTab.MAP, n.currentState.selectedTab)
            assertNull(n.currentState.pendingContactLink)
            assertNull(n.currentState.pendingContactDetail)
        }
    }

    @Test fun newerContactCaptureAndCompletedSelfCannotReplaySelfTabIntent() {
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000047")), Generation(1), radio,
        )
        val n = NavigationCoordinator()
        assertTrue(n.bindNotificationSession(radio, room.publicKey, token))
        n.selectTab(AppTab.MAP)
        val olderSelf = ContactLinkPublications().use {
            n.captureContactLinkLookup(room.publicKey, radio, room.publicKey, token, token, true, it)
        }
        val newer = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, radio, room.publicKey, token, token, true, it)
        }
        val beforeStaleCompletion = n.currentState
        assertEquals(ContactLinkCompletion.StaleContext, ContactLinkPublications().use {
            n.completeCapturedContactLookup(
                olderSelf, ContactLinkRequest("Self", room.publicKey, ContactType.CHAT),
                null, token, false, null, true, it,
            )
        })
        assertEquals(beforeStaleCompletion, n.currentState)
        assertEquals(ContactLinkCompletion.ConfirmationStaged, ContactLinkPublications().use {
            n.completeCapturedContactLookup(
                newer, ContactLinkRequest("Newer", contact.publicKey, ContactType.REPEATER),
                null, token, false, null, false, it,
            )
        })
        val pending = requireNotNull(n.currentState.pendingContactLink)
        assertEquals("Newer", pending.name)
        assertEquals(ContactType.REPEATER, pending.type)
        assertEquals(radio, pending.targetRadioId)
        assertEquals(AppTab.MAP, n.currentState.selectedTab)

        val currentSelf = ContactLinkPublications().use {
            n.captureContactLinkLookup(room.publicKey, radio, room.publicKey, token, token, true, it)
        }
        assertEquals(ContactLinkCompletion.IgnoredSelfContact, ContactLinkPublications().use {
            n.completeCapturedContactLookup(
                currentSelf, ContactLinkRequest("Self", room.publicKey, ContactType.CHAT),
                null, token, false, null, true, it,
            )
        })
        n.selectTab(AppTab.MAP)
        val beforeReplay = n.currentState
        assertEquals(ContactLinkCompletion.StaleContext, ContactLinkPublications().use {
            n.completeCapturedContactLookup(
                currentSelf, ContactLinkRequest("Self", room.publicKey, ContactType.CHAT),
                null, token, false, null, true, it,
            )
        })
        assertEquals(beforeReplay, n.currentState)
        assertSame(pending, n.currentState.pendingContactLink)
    }

    @Test fun rejectedSelfCaptureDoesNotSupersedeCurrentContactLookup() {
        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000048")), Generation(1), radio,
        )
        val n = NavigationCoordinator()
        assertTrue(n.bindNotificationSession(radio, room.publicKey, token))
        val current = ContactLinkPublications().use {
            n.captureContactLinkLookup(contact.publicKey, radio, room.publicKey, token, token, true, it)
        }
        val beforeRejectedCapture = n.currentState
        val rejected = ContactLinkPublications().use {
            n.captureContactLinkLookup(room.publicKey, radio, room.publicKey, token, token, false, it)
        }
        assertEquals(ContactLinkCaptureDisposition.StaleContext, rejected.disposition)
        assertEquals(beforeRejectedCapture, n.currentState)
        assertEquals(ContactLinkCompletion.ConfirmationStaged, ContactLinkPublications().use {
            n.completeCapturedContactLookup(
                current, ContactLinkRequest("Current", contact.publicKey, ContactType.ROOM),
                null, token, false, null, false, it,
            )
        })
        assertEquals(contact.publicKey, n.currentState.pendingContactLink?.publicKey)
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

    @Test fun pendingLinkCleanupRejectsSameRadioReplacementAdoptedAtCommitBoundary() {
        val n = NavigationCoordinator()
        val epoch = ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000021"))
        val tokenA = SessionToken(epoch, Generation(1), radio)
        val tokenB = SessionToken(epoch, Generation(2), radio)
        assertTrue(n.bindNotificationSession(radio, null, tokenA))
        n.setNavigationReady(true)
        n.navigateToDiscovery()
        n.navigateToChat(contact)
        val retiringOwner = n.captureCleanupOwner()

        var replaceAtBoundary = true
        var replacement: NavigationState? = null
        n.installCommitBoundary { action ->
            if (replaceAtBoundary) {
                replaceAtBoundary = false
                assertTrue(n.bindNotificationSession(radio, null, tokenB))
                n.stageContactLink(
                    ContactLinkRequest("Replacement", Bytes(byteArrayOf(1)), ContactType.CHAT),
                    radio,
                )
                n.stageChannelLink(ChannelLinkRequest("Replacement", Bytes(byteArrayOf(2))))
                n.stageHashtag(HashtagJoinRequest("replacement"))
                n.setNavigationReady(true)
                replacement = n.state.value
            }
            action()
        }

        assertFalse(n.clearPendingLinksIfCurrent(retiringOwner))
        assertEquals(replacement, n.state.value)
        assertEquals(tokenB, n.state.value.boundSessionToken)
        assertNotNull(n.state.value.pendingContactLink?.deliveryId)
        assertNotNull(n.state.value.pendingChannelLink)
        assertNotNull(n.state.value.pendingHashtag)
        assertEquals(ChatSelection.Direct(contact), n.state.value.chatsSelectedRoute)
        assertTrue(n.state.value.nodesShowingDiscovery)
    }

    @Test fun pendingLinkCleanupHandlesUnboundStateAndRejectsItsFirstBinding() {
        val unbound = NavigationCoordinator()
        unbound.stageChannelLink(ChannelLinkRequest("Initial", Bytes(byteArrayOf(3))))
        unbound.stageHashtag(HashtagJoinRequest("initial"))
        val retiringOwner = unbound.captureCleanupOwner()
        assertNull(retiringOwner.sessionToken)
        assertNull(retiringOwner.radioId)
        assertEquals(0, retiringOwner.generation)

        val token = SessionToken(
            ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000022")),
            Generation(1),
            radio,
        )
        assertTrue(unbound.bindNotificationSession(radio, null, token))
        unbound.setNavigationReady(true)
        val replacement = unbound.state.value
        assertFalse(unbound.clearPendingLinksIfCurrent(retiringOwner))
        assertEquals(replacement, unbound.state.value)

        val stillUnbound = NavigationCoordinator()
        stillUnbound.stageChannelLink(ChannelLinkRequest("Initial", Bytes(byteArrayOf(4))))
        val initialOwner = stillUnbound.captureCleanupOwner()
        assertTrue(stillUnbound.clearPendingLinksIfCurrent(initialOwner))
        assertNull(stillUnbound.state.value.pendingChannelLink)
        assertEquals(1L, stillUnbound.state.value.generation)
    }

    @Test fun staleDirectChannelAndRoomHandoffsCannotOverrideANewerChatSelection() {
        val newerContact = contact.copy(
            id = UUID.fromString("00000000-0000-0000-0000-000000000023"),
            name = "Newer",
        )
        val staleRoutes = listOf(
            ChatRoute.Direct(contact),
            ChatRoute.Channel(channel),
            ChatRoute.Room(room),
        )
        staleRoutes.forEach { staleRoute ->
            val n = NavigationCoordinator()
            when (staleRoute) {
                is ChatRoute.Direct -> n.navigateToChat(staleRoute.contact)
                is ChatRoute.Channel -> n.navigateToChannel(staleRoute.channel)
                is ChatRoute.Room -> n.navigateToRoom(staleRoute.session)
            }
            n.navigateToChat(newerContact)
            val newerState = n.state.value

            assertFalse(n.acknowledgePendingChatRoute(staleRoute))
            assertEquals(newerState, n.state.value)
            assertEquals(ChatSelection.Direct(newerContact), n.state.value.chatsSelectedRoute)
        }
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

    @Test fun channelSelectionUsesIncarnationWithoutMakingPreferenceChangesAnotherDestination() {
        val shown = channel.copy(occupantID = UUID(0, 100))
        val renamed = shown.copy(name = "Renamed", isFavorite = true)
        assertEquals(ChatSelection.Channel(shown), ChatSelection.Channel(renamed))
        assertEquals(ChatSelection.Channel(shown).hashCode(), ChatSelection.Channel(renamed).hashCode())
        val replacement = shown.copy(occupantID = UUID(0, 101))
        assertNotEquals(ChatSelection.Channel(shown), ChatSelection.Channel(replacement))
        assertNotEquals(ChatSelection.Channel(shown), ChatSelection.Channel(shown.copy(occupantID = null)))
        val navigation = NavigationCoordinator()
        navigation.navigateToChannel(shown)
        val originalEntry = navigation.state.value.activeStack.last().id
        navigation.navigateToChannel(renamed)
        assertEquals(originalEntry, navigation.state.value.activeStack.last().id)
        navigation.navigateToChannel(replacement)
        assertNotEquals(originalEntry, navigation.state.value.activeStack.last().id)
        assertEquals(replacement, (navigation.state.value.chatsSelectedRoute as ChatSelection.Channel).channel)
    }

    @Test fun reactionScrollBelongsToTheCurrentEntryAcrossResizeAndBackPreview() = runTest {
        val n = NavigationCoordinator()
        n.navigateToChat(contact, message)
        val outgoing = requireNotNull(n.state.value.pendingMessageScroll)
        val reaction = NotificationPayload.Reaction(message, null, channel.index, radio)
        assertEquals(NavigationOutcome.Navigated, n.handleNotification(reaction, lookup()))
        val incoming = requireNotNull(n.state.value.pendingMessageScroll)
        val stack = n.state.value.activeStack

        assertNotEquals(outgoing.entryId, incoming.entryId)
        assertNotEquals(outgoing.deliveryId, incoming.deliveryId)
        for (width in listOf(390f, 600f, 780f, 834f)) {
            n.measureWindow(width)
            val observed = n.state.value
            assertEquals(stack, observed.activeStack)
            assertNull(observed.pendingMessageScrollFor(outgoing.entryId))
            assertEquals(incoming, observed.pendingMessageScrollFor(incoming.entryId))
            assertFalse(n.consumePendingMessageScroll(outgoing.entryId, incoming))
            assertFalse(n.consumePendingMessageScroll(outgoing.entryId, outgoing))
            assertEquals(observed, n.state.value)
        }

        n.selectTab(AppTab.NODES)
        assertNull(n.state.value.pendingMessageScrollFor(incoming.entryId))
        assertFalse(n.consumePendingMessageScroll(incoming.entryId, incoming))
        n.selectTab(AppTab.CHATS)
        assertEquals(incoming, n.state.value.pendingMessageScrollFor(incoming.entryId))
        assertTrue(n.back())
        assertEquals(stack.dropLast(1), n.state.value.activeStack)
        assertEquals(ChatSelection.Direct(contact), n.state.value.chatsSelectedRoute)
        assertNull(n.state.value.pendingMessageScroll)
        assertFalse(n.consumePendingMessageScroll(incoming.entryId, incoming))
    }

    @Test fun identicalReactionReplacementsHaveDistinctDeliveriesAndCannotClearEachOther() = runTest {
        val n = NavigationCoordinator()
        val reaction = NotificationPayload.Reaction(message, EntityKey(radio, contact.id), null, null)
        assertEquals(NavigationOutcome.Navigated, n.handleNotification(reaction, lookup()))
        val first = requireNotNull(n.state.value.pendingMessageScroll)
        val stack = n.state.value.activeStack
        assertEquals(NavigationOutcome.Navigated, n.handleNotification(reaction, lookup()))
        val replacement = requireNotNull(n.state.value.pendingMessageScroll)

        assertEquals(stack, n.state.value.activeStack)
        assertEquals(first.entryId, replacement.entryId)
        assertEquals(first.messageId, replacement.messageId)
        assertNotEquals(first.deliveryId, replacement.deliveryId)
        assertFalse(n.isPendingMessageScroll(first.entryId, first))
        assertFalse(n.consumePendingMessageScroll(first.entryId, first))
        assertEquals(replacement, n.state.value.pendingMessageScroll)
        assertTrue(n.isPendingMessageScroll(replacement.entryId, replacement))
        assertTrue(n.consumePendingMessageScroll(replacement.entryId, replacement))
        assertNull(n.state.value.pendingMessageScroll)
        assertFalse(n.consumePendingMessageScroll(replacement.entryId, replacement))
    }

    @Test fun unpublishedReplacementRejectsAnOlderPublishedScrollAcknowledgement() = runTest {
        val n = NavigationCoordinator()
        n.navigateToChat(contact, message)
        val observed = n.state.value
        val outgoing = requireNotNull(observed.pendingMessageScroll)
        val committed = CountDownLatch(1)
        val release = CountDownLatch(1)
        n.installCommitBoundary { action ->
            action()
            committed.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "The test must release the navigation publication" }
        }
        val reaction = NotificationPayload.Reaction(message, null, channel.index, radio)
        val replacement = async(Dispatchers.Default) { n.handleNotification(reaction, lookup()) }
        try {
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            assertEquals(observed, n.state.value)
            val incoming = requireNotNull(n.currentState.pendingMessageScroll)
            assertNotEquals(outgoing.entryId, incoming.entryId)
            assertFalse(n.isPendingMessageScroll(outgoing.entryId, outgoing))
            assertFalse(n.consumePendingMessageScroll(outgoing.entryId, outgoing))
            assertEquals(incoming, n.currentState.pendingMessageScroll)
            assertEquals(observed, n.state.value)
        } finally {
            release.countDown()
        }
        assertEquals(NavigationOutcome.Navigated, replacement.await())
        assertEquals(ChatSelection.Channel(channel), n.state.value.chatsSelectedRoute)
    }

    @Test fun provisionalMessageScrollSurvivesItsRadioBindingButNotRadioReplacement() {
        val n = NavigationCoordinator()
        n.navigateToChat(contact, message)
        val pending = requireNotNull(n.state.value.pendingMessageScroll)
        assertTrue(n.bindNotificationSession(radio, null))
        assertEquals(pending, n.state.value.pendingMessageScroll)
        assertTrue(n.isPendingMessageScroll(pending.entryId, pending))
        n.replaceRadio(otherRadio)
        assertNull(n.state.value.pendingMessageScroll)
        assertFalse(n.consumePendingMessageScroll(pending.entryId, pending))
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
    @Test fun restorationCannotOverwriteLiveHostAndInvalidWindowWidthIsNotSilentlyAccepted() = runTest {
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

        val process = NavigationCoordinator()
        process.attachHostState(NavigationState())
        process.installOnboardingSessionCapture { { true } }
        process.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val original = process.publishedNavigation.value
        val originalEntry = original.value.activeStack.last()
        val witness = process.captureMountedOnboarding(original, originalEntry.id)
        val saved = NavigationSavedState.restore(NavigationSavedState.encode(original.value))
        assertTrue(process.back())
        val afterBack = process.state.value
        process.attachHostState(saved)
        assertEquals(afterBack, process.state.value)
        assertFalse(process.completeMountedOnboarding(witness, MountedOnboardingProducer.NoDevice))
        process.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val replacement = process.publishedNavigation.value
        assertNotEquals(originalEntry.id, replacement.value.activeStack.last().id)
        assertTrue(process.completeOnboardingForTest(replacement, replacement.value.activeStack.last().id))

        val issuedBeforeHost = NavigationCoordinator()
        issuedBeforeHost.installOnboardingSessionCapture { { true } }
        issuedBeforeHost.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val issuedSnapshot = issuedBeforeHost.publishedNavigation.value
        val issued = issuedSnapshot.value
        val issuedEntry = issued.activeStack.last()
        val oldIdentity = checkNotNull(issuedSnapshot.entryIdentity(issuedEntry.id))
        val oldWitness = checkNotNull(issuedBeforeHost.captureMountedOnboarding(issuedSnapshot, issuedEntry.id))
        val separateCoordinator = NavigationCoordinator()
        separateCoordinator.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val separateSnapshot = separateCoordinator.publishedNavigation.value
        assertEquals(issuedEntry.id, separateSnapshot.value.activeStack.last().id)
        assertNotEquals(oldIdentity, separateSnapshot.entryIdentity(issuedEntry.id))
        assertTrue(issuedBeforeHost.back())
        issuedBeforeHost.attachHostState(NavigationSavedState.restore(NavigationSavedState.encode(issued)))
        val attached = issuedBeforeHost.publishedNavigation.value
        assertEquals(issued.activeStack, attached.value.activeStack)
        val newIdentity = checkNotNull(attached.entryIdentity(issuedEntry.id))
        assertNotEquals(oldIdentity, newIdentity)
        assertFalse(issuedBeforeHost.completeMountedOnboarding(oldWitness, MountedOnboardingProducer.NoDevice))
        issuedBeforeHost.selectTab(AppTab.SETTINGS)
        issuedBeforeHost.measureWindow(900f)
        assertEquals(newIdentity, issuedBeforeHost.publishedNavigation.value.entryIdentity(issuedEntry.id))
        issuedBeforeHost.selectTab(AppTab.CHATS)
        val returned = issuedBeforeHost.publishedNavigation.value
        assertEquals(newIdentity, returned.entryIdentity(issuedEntry.id))
        assertTrue(issuedBeforeHost.completeOnboardingForTest(returned, issuedEntry.id))

        val notificationBeforeHost = NavigationCoordinator()
        notificationBeforeHost.replaceRadio(radio)
        notificationBeforeHost.setNavigationReady(true)
        val roomPayload = NotificationPayload.RoomMessage(room.name, EntityKey(radio, room.id), message)
        assertEquals(
            NavigationOutcome.Navigated,
            notificationBeforeHost.handleNotification(roomPayload, lookup()),
        )
        assertEquals(issuedEntry.id, notificationBeforeHost.state.value.roomAuthenticationDeliveryId)
        notificationBeforeHost.replaceRadio(otherRadio)
        assertNull(notificationBeforeHost.state.value.pendingRoomAuthentication)
        notificationBeforeHost.setNavigationReady(true)
        val beforeNotificationRestore = notificationBeforeHost.state.value
        notificationBeforeHost.attachHostState(NavigationSavedState.restore(NavigationSavedState.encode(issued)))
        val afterNotificationRestore = notificationBeforeHost.state.value
        assertEquals(issued.stacks, afterNotificationRestore.stacks)
        assertEquals(beforeNotificationRestore.radioId, afterNotificationRestore.radioId)
        assertEquals(beforeNotificationRestore.generation, afterNotificationRestore.generation)
        assertEquals(beforeNotificationRestore.navigationReady, afterNotificationRestore.navigationReady)

        val freshProcess = NavigationCoordinator()
        freshProcess.navigate(FeatureRoute(FeatureId.ONBOARDING))
        assertTrue(freshProcess.back())
        val freshHost = NavigationCoordinator()
        freshHost.navigate(FeatureRoute(FeatureId.ONBOARDING))
        assertTrue(freshHost.back())
        freshHost.navigate(FeatureRoute(FeatureId.ONBOARDING))
        val freshSaved = NavigationSavedState.restore(NavigationSavedState.encode(freshHost.state.value))
        freshProcess.attachHostState(freshSaved)
        assertEquals(freshSaved.activeStack, freshProcess.state.value.activeStack)
        assertEquals(freshSaved.nextEntryId, freshProcess.state.value.nextEntryId)

        val noOpAttachment = NavigationStateStore(NavigationState())
        noOpAttachment.attachHostOnce { it }
        assertEquals(0L, noOpAttachment.snapshot().revision)
        noOpAttachment.attachHostOnce { restored }
        assertEquals(NavigationState(), noOpAttachment.value)

        val competingHosts = NavigationStateStore(NavigationState())
        val firstPrepared = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val first = executor.submit {
                competingHosts.attachHostOnce {
                    firstPrepared.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    restored
                }
            }
            assertTrue(firstPrepared.await(5, TimeUnit.SECONDS))
            val winner = NavigationState(selectedTab = AppTab.MAP)
            competingHosts.attachHostOnce { winner }
            assertEquals(winner, competingHosts.value)
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            assertEquals(winner, competingHosts.value)
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
        }
    }
    @Test fun unavailableMapConsumerAndOldErrorDismissalDoNotPretendSuccessOrLoseNewFailure() {
        assertEquals(MapNavigationOutcome.UNAVAILABLE, MapNavigationConsumer().navigateToMap(MapFocusRequest(1.0, 2.0)))
        val current = NavigationFailure.TargetNotFound(NavigationTarget.CHANNEL)
        val n = NavigationCoordinator(NavigationState(failure = current))
        n.clearFailure(NavigationFailure.TargetNotFound(NavigationTarget.CONTACT)); assertEquals(current, n.state.value.failure)
        n.clearFailure(current); assertNull(n.state.value.failure)
    }
}
