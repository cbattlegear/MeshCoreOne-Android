// PortedFrom: MC1Tests/Views/Chats/Models/ChatRenderStateTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

private fun makeMessage(id: UUID = UUID.randomUUID(), index: Int): MessageDTO {
    val timestamp = (1_700_000_000 + index * 60).toUInt()
    return MessageDTO(
        id = id,
        radioId = RadioId(UUID.randomUUID()),
        contactID = UUID.randomUUID(),
        text = "message $index",
        timestamp = timestamp,
        createdAt = Instant.ofEpochSecond(timestamp.toLong()),
        direction = MessageDirection.OUTGOING,
        status = MessageStatus.SENT,
        isRead = true,
    )
}

private fun makeFakeMessageItem(id: UUID, senderName: String): MessageItem = MessageItem(
    id = id,
    envelope = MessageItemEnvelope(
        messageID = id,
        isOutgoing = true,
        senderName = senderName,
        senderResolution = NodeNameResolution(senderName, NodeNameMatchKind.EXACT),
        status = MessageStatus.SENT,
        date = Instant.ofEpochSecond(1_700_000_000),
        hasFailed = false,
        containsSelfMention = false,
        mentionSeen = false,
        incomingAvatar = null,
    ),
    content = SnapshotList.empty(),
    footer = MessageFooter(
        showHop = false, hopCount = 0, formattedPath = null, regionToShow = null, sendTimeToShow = null,
        sendTimeWasCorrected = false, showStatusRow = false, status = MessageStatus.SENT, isChannelMessage = false,
        heardRepeats = 0, retryAttempt = 0, maxRetryAttempts = 0, sendCount = 0,
    ),
    grouping = GroupingFlags(showTimestamp = false, showDirectionGap = false, showSenderName = false, showNewMessagesDivider = false),
    shouldRequestPreviewFetch = false,
)

/**
 * These are core snapshot/builder unit oracles, not proof about a mounted ViewModel. The original
 * accessor family's real five-field mounted assertions live in App's SharedTimelineIntegrationTest.
 * The historical coordinator/StateFlow smoke assertions below are retained without transferring
 * their coverage to the UI consumer.
 */
class ChatRenderStateTest {
    private suspend fun CoroutineScope.makeChatSetup(messages: List<MessageDTO>): ChatCoordinator {
        val coordinator = renderingCoordinator()
        val writer = requireNotNull(coordinator.bindWriter(this@ChatRenderStateTest, ChatWriterRole.INTERACTIVE))
        coordinator.replaceAllForTesting(messages)
        writer.rebuildItems(renderingBuildPairs(coordinator.messages), EnvInputs.DEFAULT)
        coordinator.buildItemsTask?.join()
        return coordinator
    }

    private suspend fun CoroutineScope.makeChatSetup(messageCount: Int): ChatCoordinator =
        makeChatSetup(List(messageCount) { makeMessage(index = it) })

    @TestFactory
    fun chatRenderStateTests(): List<DynamicTest> = listOf(
        case("empty has expected field defaults") {
            val empty = ChatRenderState.EMPTY
            assertTrue(empty.items.isEmpty())
            assertTrue(empty.itemIndexByID.isEmpty())
            assertEquals(true, empty.hasMoreMessages)
            assertEquals(false, empty.isLoadingOlder)
            assertEquals(0L, empty.totalFetchedCount)
            assertEquals(ChatRenderState.LoadPhase.UNINITIALIZED, empty.phase)
        },
        case("with(...) replaces only specified fields") {
            val updated = ChatRenderState.EMPTY.with(hasMoreMessages = false, totalFetchedCount = 42)
            assertEquals(false, updated.hasMoreMessages)
            assertEquals(42L, updated.totalFetchedCount)
            assertEquals(ChatRenderState.EMPTY.isLoadingOlder, updated.isLoadingOlder)
            assertEquals(ChatRenderState.EMPTY.items, updated.items)
            assertEquals(ChatRenderState.EMPTY.itemIndexByID, updated.itemIndexByID)
            assertEquals(ChatRenderState.EMPTY.phase, updated.phase)
        },
        case("with(phase:) replaces only the phase") {
            val loading = ChatRenderState.EMPTY.with(phase = ChatRenderState.LoadPhase.LOADING)
            assertEquals(ChatRenderState.LoadPhase.LOADING, loading.phase)
            assertEquals(ChatRenderState.EMPTY.items, loading.items)
            assertEquals(ChatRenderState.EMPTY.hasMoreMessages, loading.hasMoreMessages)
            assertEquals(ChatRenderState.LoadPhase.LOADED, loading.with(phase = ChatRenderState.LoadPhase.LOADED).phase)
        },
        case("appendingItem preserves phase") {
            val loaded = ChatRenderState.EMPTY.with(phase = ChatRenderState.LoadPhase.LOADED)
            assertEquals(ChatRenderState.LoadPhase.LOADED, loaded.appendingItem(makeFakeMessageItem(UUID.randomUUID(), "sender")).phase)
        },
        case("updatingItem preserves phase") {
            runRendering {
                val coordinator = makeChatSetup(3)
                coordinator.markLoadedForTesting()
                val writer = requireNotNull(coordinator.bindWriter(this@ChatRenderStateTest, ChatWriterRole.INTERACTIVE))
                writer.rebuildItems(renderingBuildPairs(coordinator.messages), EnvInputs.DEFAULT)
                coordinator.buildItemsTask?.join()
                val target = coordinator.renderState.items[0]
                val after = coordinator.renderState.updatingItem(target.id) { makeFakeMessageItem(target.id, "replaced") }
                assertEquals(coordinator.renderState.phase, after.phase)
            }
        },
        case("removingItem preserves phase") {
            runRendering {
                val messages = List(2) { makeMessage(index = it) }
                val coordinator = makeChatSetup(messages)
                coordinator.markLoadedForTesting()
                val writer = requireNotNull(coordinator.bindWriter(this@ChatRenderStateTest, ChatWriterRole.INTERACTIVE))
                writer.rebuildItems(renderingBuildPairs(coordinator.messages), EnvInputs.DEFAULT)
                coordinator.buildItemsTask?.join()
                assertEquals(coordinator.renderState.phase, coordinator.renderState.removingItem(messages[0].id).phase)
            }
        },
        case("itemIndexByID matches items after buildItems") {
            runRendering {
                val state = makeChatSetup(5).renderState
                assertEquals(5, state.items.size)
                state.items.forEachIndexed { index, item -> assertEquals(index.toLong(), state.itemIndexByID[item.id]) }
            }
        },
        case("Two builds with identical inputs produce equal render states") {
            runRendering {
                val inputs = List(3) { makeMessage(index = it) }
                assertEquals(makeChatSetup(inputs).renderState, makeChatSetup(inputs).renderState)
            }
        },
        case("ChatViewModel accessors mirror renderState field-for-field") {
            runRendering {
                val coordinator = makeChatSetup(3)
                val state = coordinator.renderState
                val observed = coordinator.renderStateFlow.value
                assertEquals(state.items.size, observed.items.size)
                assertEquals(state.itemIndexByID, observed.itemIndexByID)
                assertEquals(state.hasMoreMessages, observed.hasMoreMessages)
                assertEquals(state.isLoadingOlder, observed.isLoadingOlder)
                assertEquals(state.totalFetchedCount, observed.totalFetchedCount)
            }
        },
        case("updatingItem replaces a single row, leaves others untouched") {
            runRendering {
                val state = makeChatSetup(3).renderState
                val target = state.items[1]
                val updated = state.updatingItem(target.id) { makeFakeMessageItem(target.id, "replaced") }
                assertEquals("replaced", updated.items[1].envelope.senderName)
                assertEquals(state.items[0], updated.items[0])
                assertEquals(state.items[2], updated.items[2])
                assertEquals(state.itemIndexByID, updated.itemIndexByID)
            }
        },
        case("updatingItem no-ops on missing ID") {
            runRendering {
                val before = makeChatSetup(2).renderState
                assertEquals(before, before.updatingItem(UUID.randomUUID()) { makeFakeMessageItem(UUID.randomUUID(), "ignored") })
            }
        },
        case("removingItem removes the row and rebuilds the index") {
            runRendering {
                val messages = List(3) { makeMessage(index = it) }
                val after = makeChatSetup(messages).renderState.removingItem(messages[0].id)
                assertEquals(2, after.items.size)
                assertNull(after.itemIndexByID[messages[0].id])
                after.items.forEachIndexed { index, item -> assertEquals(index.toLong(), after.itemIndexByID[item.id]) }
            }
        },
        case("removingItem no-ops on missing ID") {
            runRendering {
                val before = makeChatSetup(2).renderState
                assertEquals(before, before.removingItem(UUID.randomUUID()))
            }
        },
        case("appendingItem appends and updates totalFetchedCount") {
            val initial = ChatRenderState.EMPTY
            val item = makeFakeMessageItem(UUID.randomUUID(), "sender")
            val after = initial.appendingItem(item)
            assertEquals(1, after.items.size)
            assertEquals(item.id, after.items[0].id)
            assertEquals(0L, after.itemIndexByID[item.id])
            assertEquals(initial.totalFetchedCount + 1, after.totalFetchedCount)
        },
    )

    @TestFactory
    fun nativeRenderStateCases(): List<DynamicTest> = listOf(
        DynamicTest.dynamicTest("WP-213::updatingItem returns the identical instance when the transform yields an equal item") {
            val item = makeFakeMessageItem(UUID.randomUUID(), "same")
            val state = ChatRenderState.EMPTY.appendingItem(item)
            assertTrue(state.updatingItem(item.id) { it.copy() } === state)
            assertTrue(state.removingItem(UUID.randomUUID()) === state)
        },
        DynamicTest.dynamicTest("WP-213::index maps keep the later offset for duplicate ids, like Swift indexByID") {
            val id = UUID.randomUUID()
            val state = ChatRenderState.EMPTY.appendingItem(makeFakeMessageItem(id, "a")).appendingItem(makeFakeMessageItem(id, "b"))
            assertEquals(1L, state.itemIndexByID[id])
            assertEquals(2L, state.totalFetchedCount)
        },
    )

    private fun case(name: String, body: () -> Unit) = DynamicTest.dynamicTest("ChatRenderStateTests::$name()", body)
}
