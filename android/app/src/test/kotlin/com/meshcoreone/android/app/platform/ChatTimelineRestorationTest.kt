// AndroidOnly: WP-307 Mounted timeline pixel-offset capture and saved-state restoration.
package com.meshcoreone.android.app.platform

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.InitialTimelineAnchor
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.TimelineScrollAnchor
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h800dp")
class ChatTimelineRestorationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun newestArrivalsFollowOnlyWhenPinnedOrOutgoingAndNeverOverrideAnExplicitTarget() {
        val radio = RadioId(UUID(0, 100))
        fun message(index: Int, outgoing: Boolean = false) = MessageDTO(
            id = UUID(0, index.toLong()), radioId = radio, text = "Message $index",
            timestamp = index.toUInt(), createdAt = Instant.ofEpochSecond(index.toLong()),
            sortDate = Instant.ofEpochSecond(index.toLong()),
            direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
        )
        fun ChatTimelineState.withMessages(messages: List<MessageDTO>) = copy(
            messages = messages,
            rows = messages.map { TimelineRow.Message(it, startsGroup = true, endsGroup = true) },
        )
        val state = mutableStateOf(
            ChatTimelineState(hasLoadedOnce = true, initialAnchor = InitialTimelineAnchor.Latest)
                .withMessages((1..25).map { message(it) }),
        )
        lateinit var list: LazyListState
        var reportedAnchor: TimelineScrollAnchor? = null
        var consumed = 0
        val callbacks = ChatTimelineCallbacks(
            onLoadOlder = {},
            onRetry = { error("Unexpected retry") },
            onInitialAnchorConsumed = {
                consumed++
                state.value = state.value.copy(initialAnchorConsumed = true)
            },
            onScrollPositionChanged = { anchor, _ -> reportedAnchor = anchor },
            onJumpToLatest = { error("Auto-follow is not a user jump") },
            onRetryLoad = { error("Unexpected load retry") },
        )
        compose.runOnUiThread {
            compose.activity.setContent {
                list = remember { LazyListState() }
                MeshCoreTheme(motionScale = 0f) {
                    ChatTimeline(state.value, callbacks, Modifier.size(320.dp, 300.dp), listState = list,
                        messageBody = { message, _ -> Box(Modifier.height(160.dp)) { Text(message.text) } })
                }
            }
        }
        fun append(index: Int, outgoing: Boolean = false) = compose.runOnIdle {
            state.value = state.value.withMessages(state.value.messages + message(index, outgoing))
        }
        fun assertAnchor(index: Int, offset: Int = 0) = compose.runOnIdle {
            assertEquals(TimelineScrollAnchor(message(index).id, offset), reportedAnchor)
        }

        assertAnchor(25)
        append(26)
        assertAnchor(26)
        compose.runOnIdle { runBlocking { list.scrollToItem(8, 24) } }
        assertAnchor(18, 24)
        append(27)
        assertAnchor(18, 24)
        append(28, outgoing = true)
        assertAnchor(28)

        compose.runOnIdle {
            state.value = state.value.withMessages(state.value.messages + message(29, outgoing = true)).copy(
                initialAnchor = InitialTimelineAnchor.Message(message(12).id),
                initialAnchorConsumed = false,
            )
        }
        assertAnchor(12)
        compose.runOnIdle { assertEquals(2, consumed) }
    }

    @Test
    fun pixelOnlyScrollSurvivesSaveAndRecreationWithAnInitiallyEmptyTimeline() {
        val radio = RadioId(UUID.fromString("10000000-0000-0000-0000-000000000001"))
        val messages = (1..30).map { index ->
            MessageDTO(
                id = UUID(0, index.toLong()),
                radioId = radio,
                text = "Cached message $index",
                timestamp = index.toUInt(),
                createdAt = Instant.ofEpochSecond(index.toLong()),
                sortDate = Instant.ofEpochSecond(index.toLong()),
            )
        }
        val rows = messages.map { TimelineRow.Message(it, startsGroup = true, endsGroup = true) }
        val loaded = ChatTimelineState(
            messages = messages,
            rows = rows,
            hasLoadedOnce = true,
            hasMoreMessages = true,
            initialAnchor = InitialTimelineAnchor.Latest,
        )
        val state = mutableStateOf(loaded)
        val mounted = mutableStateOf(true)
        val selection = "direct:${radio.canonicalString}:cached-peer"
        var registry = SaveableStateRegistry(restoredValues = null, canBeSaved = { true })
        lateinit var savedValues: Map<String, List<Any?>>
        lateinit var list: LazyListState
        var reportedAnchor: TimelineScrollAnchor? = null
        var reportedAtLatest = true
        var olderLoads = 0
        val callbacks = ChatTimelineCallbacks(
            onLoadOlder = { olderLoads++ },
            onRetry = { error("Restoration must not retry a send") },
            onInitialAnchorConsumed = {
                state.value = state.value.copy(initialAnchorConsumed = true)
            },
            onScrollPositionChanged = { anchor, atLatest ->
                reportedAnchor = anchor
                reportedAtLatest = atLatest
            },
            onJumpToLatest = { error("Restoration must not jump to latest") },
            onRetryLoad = { error("Restoration must not trigger a load retry") },
        )
        compose.runOnUiThread {
            compose.activity.setContent {
                if (mounted.value) {
                    CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                        // The App keys the conversation by selection, not by current window width.
                        key(selection) {
                            list = remember { LazyListState() }
                            MeshCoreTheme(motionScale = 0f) {
                                ChatTimeline(
                                    state.value,
                                    callbacks,
                                    Modifier.size(width = 320.dp, height = 300.dp),
                                    listState = list,
                                    messageBody = { message, _ ->
                                        Box(Modifier.height(160.dp)) { Text(message.text) }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnIdle { runBlocking { list.scrollToItem(10, 12) } }
        compose.waitForIdle()
        val beforeIndices = compose.runOnIdle { list.layoutInfo.visibleItemsInfo.map { it.index } }
        val messageId = messages.asReversed()[10].id
        compose.runOnIdle {
            assertEquals(TimelineScrollAnchor(messageId, 12), reportedAnchor)
            assertFalse(reportedAtLatest)
            runBlocking { list.scrollToItem(10, 24) }
        }
        compose.waitForIdle()
        val oldList = list
        compose.runOnIdle {
            assertEquals(beforeIndices, list.layoutInfo.visibleItemsInfo.map { it.index })
            assertEquals(10, list.firstVisibleItemIndex)
            assertEquals(24, list.firstVisibleItemScrollOffset)
            assertFalse(reportedAtLatest)
            assertEquals(0, olderLoads)
            savedValues = registry.performSave()
            mounted.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            registry = SaveableStateRegistry(savedValues, canBeSaved = { true })
            state.value = ChatTimelineState(isLoading = true, hasMoreMessages = false)
            mounted.value = true
        }
        compose.waitForIdle()
        compose.runOnIdle {
            // A fresh, deliberately non-saveable LazyListState rules out positional-state rescue.
            assertNotSame(oldList, list)
            assertEquals(0, list.firstVisibleItemIndex)
            assertEquals(0, list.firstVisibleItemScrollOffset)
            state.value = loaded
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(state.value.initialAnchorConsumed)
            assertEquals(10, list.firstVisibleItemIndex)
            assertEquals(24, list.firstVisibleItemScrollOffset)
            val restoredRow = state.value.rows.asReversed()[list.firstVisibleItemIndex] as TimelineRow.Message
            assertEquals(messageId, restoredRow.message.id)
            assertEquals(TimelineScrollAnchor(messageId, 24), reportedAnchor)
            assertFalse(reportedAtLatest)
            assertEquals(0, olderLoads)
        }
        compose.runOnIdle { runBlocking { list.scrollToItem(27, 12) } }
        compose.waitForIdle()
        val oldestIndices = compose.runOnIdle { list.layoutInfo.visibleItemsInfo.map { it.index } }
        compose.runOnIdle {
            assertEquals(1, olderLoads)
            runBlocking { list.scrollToItem(27, 24) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(oldestIndices, list.layoutInfo.visibleItemsInfo.map { it.index })
            assertEquals(1, olderLoads)
            assertEquals(TimelineScrollAnchor(messages.asReversed()[27].id, 24), reportedAnchor)
            runBlocking { list.scrollToItem(0) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertTrue(reportedAtLatest)
            assertEquals(TimelineScrollAnchor(messages.last().id, 0), reportedAnchor)
            assertEquals(1, olderLoads)
        }
    }
}
