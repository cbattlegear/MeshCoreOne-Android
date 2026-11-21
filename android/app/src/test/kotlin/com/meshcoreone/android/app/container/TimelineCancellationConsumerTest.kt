// PortedFrom: MC1Tests/Views/Chats/ChatReconnectPopulateTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Timeline/ChatTimeline+Paging.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineDependencies
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.feature.chats.timeline.TimelineDataSource
import com.meshcoreone.android.feature.chats.timeline.TimelineStateStore
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@Config(sdk = [31], qualifiers = "w360dp-h800dp-mdpi")
class TimelineCancellationConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun cancelledRefreshAfterFetchKeepsTheMountedWindow() = runTest { verifyCancellation(this, true, false) }
    @Test fun cancelledRefreshAfterPreparationKeepsTheMountedWindow() = runTest { verifyCancellation(this, true, true) }
    @Test fun cancelledOlderFetchKeepsTheMountedWindowAndRetiresSpinner() = runTest { verifyCancellation(this, false, false) }
    @Test fun cancelledOlderPreparationKeepsTheMountedWindowAndRetiresSpinner() = runTest { verifyCancellation(this, false, true) }

    private suspend fun verifyCancellation(test: TestScope, refresh: Boolean, duringPreparation: Boolean) {
        val radio = RadioId(UUID(0, 402))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Radio"))
        val contact = ContactDTO(radioId = radio, publicKey = key(3), name = "Peer", lastHeardTimestamp = null)
        store.saveContact(contact)
        fun message(index: Int) = MessageDTO(
            id = UUID(0, index.toLong()), radioId = radio, contactID = contact.id,
            text = "message $index", timestamp = (1_700_000_000 + index).toUInt(),
            createdAt = Instant.ofEpochSecond(1_700_000_000L + index * 10L),
            sortDate = Instant.ofEpochSecond(1_700_000_000L + index * 10L),
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, isRead = true,
        )
        val raw = (1..52).map(::message)
        raw.forEach { store.saveMessage(it) }
        val harness = ContainerHarness(test, store)
        val conversation = TimelineConversation.Direct(contact)
        val base = harness.container.chatsFeature.timeline(conversation)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var gateEnabled = false
        var preparationCalls = 0
        lateinit var bound: TimelineStateStore
        suspend fun park() {
            if (gateEnabled) withContext(NonCancellable) {
                entered.complete(Unit)
                release.await()
            }
        }
        val dependencies = object : ChatTimelineDependencies by base {
            override fun bindTimeline(
                owner: Any, scope: CoroutineScope, zoneId: ZoneId, initial: ChatTimelineState,
            ): TimelineStateStore = base.bindTimeline(owner, scope, zoneId, initial).also { bound = it }

            override val data = object : TimelineDataSource by base.data {
                override suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int): List<MessageDTO> {
                    val result = base.data.fetchMessages(conversation, limit, offset)
                    if (!duringPreparation) park()
                    return result
                }

                override suspend fun fetchMessageWindow(
                    conversation: TimelineConversation, anchorSortDate: Instant?, floorLimit: Int,
                ): MessageWindow {
                    val result = base.data.fetchMessageWindow(conversation, anchorSortDate, floorLimit)
                    if (!duringPreparation) park()
                    return result
                }
            }

            override suspend fun prepareMessages(messages: List<MessageDTO>): List<MessageDTO> {
                preparationCalls++
                val result = base.prepareMessages(messages)
                if (duringPreparation) park()
                return result
            }
        }
        val vm = ChatViewModel(conversation, dependencies, CoroutineScope(test.backgroundScope.coroutineContext), ZoneOffset.UTC)
        try {
            vm.open()
            vm.consumeInitialAnchor()
            val candidate = if (refresh) message(53).also { store.saveMessage(it) } else raw.first()
            val list = LazyListState(if (refresh) 0 else vm.state.value.rows.lastIndex - 1)
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        ChatTimeline(
                            vm.state.collectAsState().value,
                            ChatTimelineCallbacks({}, {}, vm::consumeInitialAnchor, vm::updateScrollPosition, {}, {}),
                            Modifier.size(320.dp, 600.dp), listState = list,
                            showsIncomingAvatars = false, showSenderName = false,
                        )
                    }
                }
            }
            compose.waitForIdle()
            val before = vm.state.value
            val preparedBefore = preparationCalls
            assertEquals(50, before.totalFetchedCount)
            assertTrue(before.hasMoreMessages)
            gateEnabled = true
            var returnedNormally = false
            var cancellationPropagated = false
            val operation = test.launch {
                try {
                    if (refresh) vm.refresh() else vm.loadOlder()
                    returnedNormally = true
                } catch (cancelled: CancellationException) {
                    cancellationPropagated = true
                    throw cancelled
                }
            }
            entered.await()
            assertTrue("The actual writer must remain current throughout caller cancellation", bound.isCurrent)
            if (!refresh) assertTrue(vm.state.value.isLoadingOlder)
            operation.cancel()
            release.complete(Unit)
            operation.join()
            compose.waitForIdle()
            val after = vm.state.value
            assertEquals("Cancelled work must not publish its fetched rows", before.messages.map { it.id }, after.messages.map { it.id })
            assertEquals(before.messages, after.messages)
            assertEquals(before.rows, after.rows)
            assertEquals(before.totalFetchedCount, after.totalFetchedCount)
            assertEquals(before.hasMoreMessages, after.hasMoreMessages)
            assertEquals(before.scrollAnchor, after.scrollAnchor)
            assertEquals(before.initialAnchor, after.initialAnchor)
            assertFalse(after.isLoadingOlder)
            assertNull(after.passiveError)
            assertTrue(bound.isCurrent)
            assertTrue("Cancellation must reach the caller", cancellationPropagated)
            assertFalse(returnedNormally)
            if (!duringPreparation) assertEquals("Cancelled fetch must not enter preparation", preparedBefore, preparationCalls)
            compose.onNodeWithTag("timeline-bubble:${candidate.id}", useUnmergedTree = true).assertDoesNotExist()

            gateEnabled = false
            if (refresh) vm.refresh() else vm.loadOlder()
            compose.waitForIdle()
            assertTrue(vm.state.value.messages.any { it.id == candidate.id })
            assertEquals(if (refresh) 51 else 52, vm.state.value.totalFetchedCount)
            assertEquals(refresh, vm.state.value.hasMoreMessages)
            assertFalse(vm.state.value.isLoadingOlder)
            assertNull(vm.state.value.passiveError)
        } finally {
            release.complete(Unit)
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            vm.close()
            harness.container.close()
        }
    }
}
