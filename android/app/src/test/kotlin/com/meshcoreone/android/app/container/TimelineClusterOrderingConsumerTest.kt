// PortedFrom: MC1Tests/ViewModels/ChatViewModelPaginationTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Timeline/ChatTimeline+Paging.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineDependencies
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineDataSource
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.TimelineScrollAnchor
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    private suspend fun withTimeline(test: TestScope, body: suspend (Fixture) -> Unit) {
        val radio = RadioId(UUID(0, 307))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Radio"))
        val contact = ContactDTO(radioId = radio, publicKey = key(3), name = "Peer", lastHeardTimestamp = null)
        store.saveContact(contact)
        val harness = ContainerHarness(test, store)
        val fixture = Fixture(test, harness, TimelineConversation.Direct(contact))
        try { body(fixture) } finally {
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            fixture.vm.close()
            harness.container.close()
        }
    }

    private inner class Fixture(
        test: TestScope,
        private val harness: ContainerHarness,
        private val conversation: TimelineConversation.Direct,
    ) {
        val offsets = mutableListOf<Int>()
        val fetchedPages = mutableListOf<List<MessageDTO>>()
        val refreshAnchors = mutableListOf<Instant?>()
        var clearUnreadCalls = 0
        var failedSeenCalls = 0
        private val scope = CoroutineScope(test.backgroundScope.coroutineContext)
        private val base = harness.container.chatsFeature.timeline(conversation)
        private val dependencies = object : ChatTimelineDependencies by base {
            override val data = object : TimelineDataSource by base.data {
                override suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int): List<MessageDTO> {
                    offsets += offset
                    return base.data.fetchMessages(conversation, limit, offset).also { fetchedPages += it }
                }

                override suspend fun fetchMessageWindow(
                    conversation: TimelineConversation, anchorSortDate: Instant?, floorLimit: Int,
                ): MessageWindow {
                    refreshAnchors += anchorSortDate
                    return base.data.fetchMessageWindow(conversation, anchorSortDate, floorLimit)
                }

                override suspend fun clearUnread(conversation: TimelineConversation) {
                    clearUnreadCalls++
                    base.data.clearUnread(conversation)
                }

                override suspend fun markFailedSendsSeen(conversation: TimelineConversation) {
                    failedSeenCalls++
                    base.data.markFailedSendsSeen(conversation)
                }
            }
        }
        val vm = ChatViewModel(conversation, dependencies, scope, ZoneOffset.UTC)

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
            id = UUID(0, timestamp.toLong()), radioId = conversation.contact.radioId,
            contactID = conversation.contact.id, text = "message $timestamp", timestamp = timestamp.toUInt(),
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
