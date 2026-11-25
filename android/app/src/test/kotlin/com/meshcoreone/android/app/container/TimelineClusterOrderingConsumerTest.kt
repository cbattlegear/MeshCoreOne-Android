// PortedFrom: MC1Tests/ViewModels/ChatViewModelPaginationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Timeline/ChatTimeline+Paging.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineDependencies
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.OpenTimelineBookkeeping
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineDataSource
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.TimelineScrollAnchor
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineRoute
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@Config(sdk = [31], qualifiers = "w360dp-h800dp-mdpi")
class TimelineClusterOrderingConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun exhaustedHistoryRefreshFailureRetriesTheWindowWithoutReopeningOrPaging() = runTest {
        withTimeline(this) { f ->
            val first = f.message(1, 0)
            store.saveMessage(first)
            f.mountRoute()
            f.awaitLoaded()
            assertFalse(f.vm.state.value.hasMoreMessages)
            val added = f.message(2, 10)
            store.saveMessage(added)
            f.failRefresh = true
            f.vm.refresh()
            compose.waitForIdle()
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)).assertIsDisplayed()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadConversationsFailed)).assertDoesNotExist()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadOlderMessagesFailed)).assertDoesNotExist()
            assertEquals(listOf(first.id), f.vm.state.value.messages.map { it.id })
            f.failRefresh = false
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await { f.vm.state.value.messages.any { it.id == added.id } && f.vm.state.value.passiveError == null }
            assertEquals(listOf(0), f.offsets)
            assertEquals(listOf(first.sortDate, first.sortDate), f.refreshAnchors)
            assertEquals(1, f.clearUnreadCalls)
            assertEquals(1, f.failedSeenCalls)
            assertFalse(f.vm.state.value.hasMoreMessages)
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)).assertDoesNotExist()
        }
    }

    @Test fun pagingFailureRetriesTheSameRawOffsetAndPreservesSuccessfulPaging() = runTest {
        withTimeline(this) { f ->
            val messages = List(52) { f.message(it + 1, it * 10L) }
            messages.forEach { store.saveMessage(it) }
            f.mountRoute()
            f.awaitLoaded()
            assertTrue(f.vm.state.value.hasMoreMessages)
            f.failAtOffset = 50
            f.vm.loadOlder()
            compose.waitForIdle()
            compose.onNodeWithTag("timeline-list").performScrollToIndex(0)
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadOlderMessagesFailed)).assertIsDisplayed()
            assertEquals(50, f.vm.state.value.messages.size)
            f.failAtOffset = null
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await { f.vm.state.value.messages.size == 52 && f.vm.state.value.passiveError == null }
            assertEquals(listOf(0, 50, 50), f.offsets)
            assertTrue(f.refreshAnchors.isEmpty())
            assertFalse(f.vm.state.value.hasMoreMessages)
            assertEquals(messages.map { it.id }, f.vm.state.value.messages.map { it.id })
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadOlderMessagesFailed)).assertDoesNotExist()
        }
    }

    @Test fun initialLoadFailureStillRetriesOpen() = runTest {
        withTimeline(this) { f ->
            val message = f.message(1, 0)
            store.saveMessage(message)
            f.failAtOffset = 0
            f.mountRoute()
            f.awaitLoaded()
            assertTrue(f.vm.state.value.loadError != null)
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadConversationsFailed)).assertIsDisplayed()
            f.failAtOffset = null
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await { f.vm.state.value.messages.any { it.id == message.id } && f.vm.state.value.loadError == null }
            assertEquals(listOf(0, 0), f.offsets)
            assertTrue(f.refreshAnchors.isEmpty())
            assertNull(f.vm.state.value.passiveError)
            assertEquals(1, f.clearUnreadCalls)
        }
    }

    @Test fun repeatedRefreshRetryCallbacksDoNotReopenLoadedHistory() = runTest {
        withTimeline(this) { f ->
            val messages = List(52) { f.message(it + 1, it * 10L) }
            messages.forEach { store.saveMessage(it) }
            f.mountRoute()
            f.awaitLoaded()
            f.vm.loadOlder()
            f.failRefresh = true
            f.vm.refresh()
            compose.onNodeWithTag("timeline-list").performScrollToIndex(0)
            f.failRefresh = false
            val release = CompletableDeferred<Unit>()
            f.refreshRelease = release
            try {
                compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
                f.await { f.refreshAnchors.size == 2 }
                f.vm.retryLoad()
            } finally {
                release.complete(Unit)
            }
            f.await {
                f.refreshCompletions == 1 && f.vm.state.value.passiveError == null &&
                    f.vm.state.value.messages.size == 52
            }
            f.vm.retryLoad()
            assertEquals(listOf(0, 50), f.offsets)
            assertEquals(2, f.refreshAnchors.size)
            assertEquals(messages.map { it.id }, f.vm.state.value.messages.map { it.id })
            assertTrue(f.vm.state.value.initialAnchorConsumed)
            assertEquals(1, f.clearUnreadCalls)
            assertEquals(1, f.failedSeenCalls)
        }
    }

    @Test fun renderFailureAfterExhaustedPagingRebuildsUnchangedInputsWithoutFetchingMessages() = runTest {
        withTimeline(this, channel = true) { f ->
            val messages = List(52) { f.message(it + 1, it * 10L) }
            messages.forEach { store.saveMessage(it) }
            f.mountRoute()
            f.awaitLoaded()
            assertNull(f.vm.state.value.loadError)
            assertEquals(50, f.vm.state.value.messages.size)
            f.failAtOffset = 50
            f.vm.loadOlder()
            compose.onNodeWithTag("timeline-list").performScrollToIndex(0)
            f.failAtOffset = null
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await { f.vm.state.value.messages.size == 52 && f.vm.state.value.renderItems.size == 52 }
            assertFalse(f.vm.state.value.hasMoreMessages)
            f.renderReads.fail = true
            f.requestRender()
            f.await { f.vm.state.value.renderError != null }
            assertNull(f.vm.state.value.passiveError)
            compose.onNodeWithTag("timeline-list").performScrollToIndex(0)
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)).assertIsDisplayed()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadOlderMessagesFailed)).assertDoesNotExist()
            val readsBeforeRetry = f.renderReads.count
            f.renderReads.fail = false
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await { f.vm.state.value.renderError == null }
            assertEquals(readsBeforeRetry + 1, f.renderReads.count)
            assertEquals(listOf(0, 50, 50), f.offsets)
            assertTrue(f.refreshAnchors.isEmpty())
            assertEquals(messages.map { it.id }, f.vm.state.value.messages.map { it.id })
            assertEquals(1, f.clearUnreadCalls)
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)).assertDoesNotExist()
        }
    }

    @Test fun successfulRenderingCannotDiscardAnOutstandingPagingRetry() = runTest {
        withTimeline(this, channel = true) { f ->
            val messages = List(52) { f.message(it + 1, it * 10L) }
            messages.forEach { store.saveMessage(it) }
            f.mountRoute()
            f.awaitLoaded()
            assertEquals(50, f.vm.state.value.messages.size)
            f.failAtOffset = 50
            f.vm.loadOlder()
            val windowFailure = checkNotNull(f.vm.state.value.passiveError)
            f.renderReads.fail = true
            f.requestRender()
            f.await { f.vm.state.value.renderError != null }
            assertTrue(f.vm.state.value.passiveError === windowFailure)
            compose.onNodeWithTag("timeline-list").performScrollToIndex(0)
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadOlderMessagesFailed)).assertIsDisplayed()
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)).assertDoesNotExist()
            f.renderReads.fail = false
            f.requestRender()
            f.await { f.vm.state.value.renderError == null }
            assertTrue(f.vm.state.value.passiveError === windowFailure)
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadOlderMessagesFailed)).assertIsDisplayed()
            f.failAtOffset = null
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await { f.vm.state.value.messages.size == 52 && f.vm.state.value.passiveError == null }
            assertEquals(listOf(0, 50, 50), f.offsets)
            assertTrue(f.refreshAnchors.isEmpty())
            assertNull(f.vm.state.value.renderError)
            assertEquals(1, f.clearUnreadCalls)
        }
    }

    @Test fun emptyInitialLoadFailureTakesPriorityOverRefreshAndRenderFailures() = runTest {
        withTimeline(this, channel = true) { f ->
            val message = f.message(1, 0)
            store.saveMessage(message)
            f.failAtOffset = 0
            f.renderReads.fail = true
            f.mountRoute()
            f.awaitLoaded()
            f.await { f.vm.state.value.renderError != null }
            f.failRefresh = true
            f.vm.refresh()
            assertTrue(f.vm.state.value.loadError != null)
            assertTrue(f.vm.state.value.passiveError != null)
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadConversationsFailed)).assertIsDisplayed()
            f.failAtOffset = null
            f.failRefresh = false
            f.renderReads.fail = false
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await {
                f.vm.state.value.messages.any { it.id == message.id } &&
                    f.vm.state.value.loadError == null && f.vm.state.value.renderError == null
            }
            assertNull(f.vm.state.value.passiveError)
            assertEquals(listOf(0, 0), f.offsets)
            assertEquals(listOf<Instant?>(null), f.refreshAnchors)
            assertEquals(1, f.clearUnreadCalls)
            assertEquals(1, f.failedSeenCalls)
        }
    }

    @Test fun emptyLoadedWindowRetriesOnlyTheFailedOpenBookkeepingStage() = runTest {
        withTimeline(this) { f ->
            f.failClearUnread = true
            f.failFailedSeen = true
            f.mountRoute()
            f.awaitLoaded()
            f.await { f.vm.state.value.bookkeepingError != null }
            assertNull(f.vm.state.value.loadError)
            assertEquals(OpenTimelineBookkeeping.CLEAR_UNREAD, f.vm.state.value.bookkeepingErrorOperation)
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)).assertIsDisplayed()
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsErrorLoadConversationsFailed)).assertDoesNotExist()
            f.failClearUnread = false
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await {
                f.vm.state.value.bookkeepingError != null &&
                    f.vm.state.value.bookkeepingErrorOperation == OpenTimelineBookkeeping.MARK_FAILED_SENDS_SEEN
            }
            assertEquals(listOf(0), f.offsets)
            assertEquals(2, f.clearUnreadCalls)
            assertEquals(1, f.failedSeenCalls)
            f.failFailedSeen = false
            f.failedSeenRelease = CompletableDeferred()
            val cancelledRetry = launch { f.vm.retryLoad() }
            f.await { f.failedSeenCalls == 2 }
            cancelledRetry.cancel()
            cancelledRetry.join()
            assertTrue(f.vm.state.value.bookkeepingError != null)
            assertEquals(OpenTimelineBookkeeping.MARK_FAILED_SENDS_SEEN, f.vm.state.value.bookkeepingErrorOperation)
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)).assertIsDisplayed()
            f.failedSeenRelease = null
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonTryAgain)).performClick()
            f.await { f.vm.state.value.bookkeepingError == null }
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsRowNoMessages)).assertIsDisplayed()
            f.vm.retryLoad()
            assertEquals(listOf(0), f.offsets)
            assertEquals(2, f.clearUnreadCalls)
            assertEquals(3, f.failedSeenCalls)
            assertTrue(f.refreshAnchors.isEmpty())
        }
    }

    @Test fun cancellationAfterBookkeepingReturnsCannotRepeatACompletedStage() = runTest {
        withTimeline(this) { f ->
            f.failClearUnread = true
            f.mountRoute()
            f.awaitLoaded()
            f.await { f.vm.state.value.bookkeepingError != null }
            f.failClearUnread = false
            f.cancelAfterClearUnread = true
            val afterClear = launch { f.vm.retryLoad() }
            f.await { afterClear.isCompleted }
            afterClear.join()
            assertTrue(afterClear.isCancelled)
            assertEquals(OpenTimelineBookkeeping.MARK_FAILED_SENDS_SEEN, f.vm.state.value.bookkeepingErrorOperation)
            assertTrue(f.vm.state.value.bookkeepingError != null)
            assertEquals(2, f.clearUnreadCalls)
            assertEquals(0, f.failedSeenCalls)
            compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonErrorFailedToLoad)).assertIsDisplayed()
            f.cancelAfterClearUnread = false
            f.cancelAfterFailedSeen = true
            val afterSeen = launch { f.vm.retryLoad() }
            f.await { afterSeen.isCompleted }
            afterSeen.join()
            assertTrue(afterSeen.isCancelled)
            assertNull(f.vm.state.value.bookkeepingError)
            compose.onNodeWithText(host.get().getString(AppChatsStrings.chatsRowNoMessages)).assertIsDisplayed()
            f.vm.retryLoad()
            assertEquals(listOf(0), f.offsets)
            assertEquals(2, f.clearUnreadCalls)
            assertEquals(1, f.failedSeenCalls)
        }
    }

    @Test fun initialPageDisplaysSenderOrderRatherThanRelayArrivalOrder() = runTest {
        withTimeline(this) { f ->
            val third = f.message(3, 0)
            val first = f.message(1, 2)
            val second = f.message(2, 3)
            listOf(third, first, second).forEach { store.saveMessage(it) }
            f.vm.open()
            f.mount()

            f.assertDisplayedOrder(first, second, third)
            assertEquals(listOf(first.id, second.id, third.id), f.vm.state.value.messages.map { it.id })
        }
    }

    @Test fun crossPageClusterPreservesRawWindowAnchorAndRefresh() = runTest {
        withTimeline(this) { f ->
            val chronologicalControl = f.message(90, -20)
            val third = f.message(3, 0)
            val first = f.message(1, 2)
            val second = f.message(2, 3)
            val newer = List(48) { f.message(100 + it, 20L + it * 10L) }
            val raw = listOf(chronologicalControl, third, first, second) + newer
            raw.forEach { store.saveMessage(it) }
            f.vm.open()
            assertEquals(50, f.vm.state.value.totalFetchedCount)
            assertTrue(f.vm.state.value.hasMoreMessages)
            f.vm.consumeInitialAnchor()
            val anchor = TimelineScrollAnchor(newer[20].id, 17)
            f.vm.updateScrollPosition(anchor, isAtLatest = false)
            f.vm.loadOlder()
            val expected = listOf(chronologicalControl, first, second, third) + newer

            assertEquals(expected.map { it.id }, f.vm.state.value.messages.map { it.id })
            assertEquals(anchor, f.vm.state.value.scrollAnchor)
            f.mount(third.id)
            f.assertDisplayedOrder(chronologicalControl, first, second, third)
            assertEquals(expected.map { it.id }, f.vm.state.value.messages.map { it.id })
            assertEquals(listOf(0, 50), f.offsets)
            assertEquals(52, f.vm.state.value.totalFetchedCount)
            assertFalse(f.vm.state.value.hasMoreMessages)
            // Mounted scrolling reports its own real anchor; refresh must preserve that exact anchor.
            val mountedAnchor = f.vm.state.value.scrollAnchor
            val added = f.message(200, 600)
            store.saveMessage(added)
            f.vm.refresh()
            compose.waitForIdle()
            f.assertDisplayedOrder(chronologicalControl, first, second, third)
            assertEquals((expected + added).map { it.id }, f.vm.state.value.messages.map { it.id })
            assertEquals(chronologicalControl.sortDate, f.refreshAnchors.single())
            assertEquals(53, f.vm.state.value.totalFetchedCount)
            assertFalse(f.vm.state.value.hasMoreMessages)
            assertEquals(mountedAnchor, f.vm.state.value.scrollAnchor)
            assertEquals(1, f.clearUnreadCalls)
            assertEquals(1, f.failedSeenCalls)
        }
    }

    @Test fun fullyTiedDatabaseRowsKeepProbeMembershipThroughPagingExhaustion() = runTest {
        withTimeline(this) { f ->
            val tied = List(52) { index ->
                f.message(77, 0).copy(id = UUID(0, index + 1L), text = "tied $index")
            }
            tied.forEach { store.saveMessage(it) }
            f.vm.open()
            val selected = f.fetchedPages.single().takeLast(50)
            assertEquals(selected.map { it.id }, f.vm.state.value.messages.map { it.id })
            assertEquals(50, f.vm.state.value.totalFetchedCount)
            f.vm.loadOlder()
            assertEquals(tied.map { it.id }.toSet(), f.vm.state.value.messages.map { it.id }.toSet())
            assertEquals(52, f.vm.state.value.messages.size)
            assertEquals(52, f.vm.state.value.totalFetchedCount)
            assertFalse(f.vm.state.value.hasMoreMessages)
            val before = f.vm.state.value.messages
            f.vm.consumeInitialAnchor()
            f.mount(before[2].id)
            f.assertDisplayedOrder(*before.take(3).toTypedArray())
            val firstBubbleId = compose.onNodeWithTag("timeline-bubble:${before.first().id}", useUnmergedTree = true)
                .fetchSemanticsNode().id
            f.publishHeardRepeat(before.first())
            compose.waitForIdle()
            assertEquals(before.map { it.id }, f.vm.state.value.messages.map { it.id })
            f.assertDisplayedOrder(*before.take(3).toTypedArray())
            assertEquals(
                firstBubbleId,
                compose.onNodeWithTag("timeline-bubble:${before.first().id}", useUnmergedTree = true)
                    .fetchSemanticsNode().id,
            )
        }
    }

    private suspend fun withTimeline(test: TestScope, channel: Boolean = false, body: suspend (Fixture) -> Unit) {
        val radio = RadioId(UUID(0, 307))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Radio"))
        val contact = ContactDTO(radioId = radio, publicKey = key(3), name = "Peer", lastHeardTimestamp = null)
        store.saveContact(contact)
        val renderReads = RenderReads()
        val persistence = object : PersistenceStoreProtocol by store, ChannelHistoryPersisting by store {
            override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> {
                renderReads.count++
                if (renderReads.fail) throw IllegalStateException("Scripted render contact read failure")
                return store.fetchContacts(radioId)
            }
        }
        val harness = ContainerHarness(test, store, persistenceOverride = persistence)
        val conversation = if (channel) {
            val slot = seedReconciledChannel(ChannelDTO(radioId = radio, index = 0u, name = "Timeline"))
            TimelineConversation.Channel(slot)
        } else TimelineConversation.Direct(contact)
        val fixture = Fixture(test, harness, conversation, renderReads)
        try { body(fixture) } finally {
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            fixture.vm.close()
            harness.container.close()
        }
    }

    private class RenderReads {
        var fail = false
        var count = 0
    }

    private inner class Fixture(
        test: TestScope,
        private val harness: ContainerHarness,
        private val conversation: TimelineConversation,
        val renderReads: RenderReads,
    ) {
        val offsets = mutableListOf<Int>()
        val fetchedPages = mutableListOf<List<MessageDTO>>()
        val refreshAnchors = mutableListOf<Instant?>()
        var clearUnreadCalls = 0
        var failedSeenCalls = 0
        var failRefresh = false
        var failClearUnread = false
        var failFailedSeen = false
        var failedSeenRelease: CompletableDeferred<Unit>? = null
        var cancelAfterClearUnread = false
        var cancelAfterFailedSeen = false
        var failAtOffset: Int? = null
        var refreshRelease: CompletableDeferred<Unit>? = null
        var refreshCompletions = 0
        private lateinit var boundState: CoordinatedTimelineState
        private val scope = CoroutineScope(test.backgroundScope.coroutineContext)
        private val base = harness.container.chatsFeature.timeline(conversation)
        private val dependencies = object : ChatTimelineDependencies by base {
            override fun bindTimeline(owner: Any, scope: CoroutineScope, zoneId: java.time.ZoneId, initial: ChatTimelineState) =
                base.bindTimeline(owner, scope, zoneId, initial).also {
                    check(it is CoordinatedTimelineState)
                    boundState = it
                }

            override val data = object : TimelineDataSource by base.data {
                override suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int): List<MessageDTO> {
                    offsets += offset
                    if (failAtOffset == offset) throw IllegalStateException("Scripted page read failure")
                    return base.data.fetchMessages(conversation, limit, offset).also { fetchedPages += it }
                }

                override suspend fun fetchMessageWindow(
                    conversation: TimelineConversation, anchorSortDate: Instant?, floorLimit: Int,
                ): MessageWindow {
                    refreshAnchors += anchorSortDate
                    if (failRefresh) throw IllegalStateException("Scripted refresh read failure")
                    refreshRelease?.await()
                    return base.data.fetchMessageWindow(conversation, anchorSortDate, floorLimit).also {
                        refreshCompletions++
                    }
                }

                override suspend fun clearUnread(conversation: TimelineConversation) {
                    clearUnreadCalls++
                    if (failClearUnread) throw IllegalStateException("Scripted unread read failure")
                    base.data.clearUnread(conversation)
                    if (cancelAfterClearUnread) currentCoroutineContext().cancel()
                }

                override suspend fun markFailedSendsSeen(conversation: TimelineConversation) {
                    failedSeenCalls++
                    if (failFailedSeen) throw IllegalStateException("Scripted failed-send bookkeeping failure")
                    failedSeenRelease?.await()
                    base.data.markFailedSendsSeen(conversation)
                    if (cancelAfterFailedSeen) currentCoroutineContext().cancel()
                }
            }
        }
        val vm = ChatViewModel(conversation, dependencies, scope, ZoneOffset.UTC)

        fun requestRender() = boundState.requestRender()

        fun mountRoute() {
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        ChatTimelineRoute(vm, Modifier.size(320.dp, 600.dp))
                    }
                }
            }
            compose.waitForIdle()
        }

        fun await(condition: () -> Boolean) = compose.waitUntil(30_000) {
            harness.settle()
            condition()
        }

        fun awaitLoaded() = await { vm.state.value.hasLoadedOnce && !vm.state.value.isLoading }

        suspend fun publishHeardRepeat(message: MessageDTO) {
            vm.start()
            harness.settle()
            store.saveMessage(message.copy(heardRepeats = 1))
            harness.appState.messageEventStream.send(MessageEvent.HeardRepeatRecorded(message.id, 1))
            harness.eventually("metadata event reaches the real timeline holder") {
                vm.state.value.messages.firstOrNull { it.id == message.id }?.heardRepeats == 1L
            }
        }

        fun message(timestamp: Int, receivedOffset: Long) = MessageDTO(
            id = UUID(0, timestamp.toLong()), radioId = conversation.id.radioId,
            contactID = (conversation as? TimelineConversation.Direct)?.contact?.id,
            channelIndex = (conversation as? TimelineConversation.Channel)?.channel?.index,
            senderNodeName = if (conversation is TimelineConversation.Channel) "Peer" else null,
            text = "message $timestamp", timestamp = timestamp.toUInt(),
            createdAt = Instant.ofEpochSecond(1_700_000_000L + receivedOffset),
            sortDate = Instant.ofEpochSecond(1_700_000_000L + receivedOffset),
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, isRead = true,
        )

        fun mount(target: UUID? = null) {
            val reversedRows = vm.state.value.rows.asReversed()
            val index = target?.let { id ->
                reversedRows.indexOfFirst { it is TimelineRow.Message && it.message.id == id }
            } ?: 0
            check(index >= 0)
            val list = LazyListState(index)
            val callbacks = ChatTimelineCallbacks(
                { scope.launch { vm.loadOlder() } }, {}, vm::consumeInitialAnchor,
                vm::updateScrollPosition, vm::jumpToLatest, { scope.launch { vm.refresh() } },
            )
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        ChatTimeline(
                            vm.state.collectAsState().value, callbacks, Modifier.size(320.dp, 600.dp),
                            listState = list, showsIncomingAvatars = false, showSenderName = false,
                        )
                    }
                }
            }
            compose.waitForIdle()
        }

        fun assertDisplayedOrder(vararg messages: MessageDTO) {
            val tops = messages.map {
                compose.onNodeWithTag("timeline-bubble:${it.id}", useUnmergedTree = true)
                    .getUnclippedBoundsInRoot().top.value
            }
            assertTrue("Expected ${messages.map { it.text }} top-to-bottom, got $tops", tops.zipWithNext().all { (a, b) -> a < b })
        }
    }
}
