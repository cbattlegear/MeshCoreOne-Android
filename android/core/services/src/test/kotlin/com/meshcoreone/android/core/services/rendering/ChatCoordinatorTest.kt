// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChatCoordinatorTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Adaptations: Swift polls `reloadInFlight` with 10 ms sleeps; these join the drain job instead. The
 * Swift in-memory `PersistenceStore` is [RenderingInMemoryMessageStore]; store keys are `EntityKey`.
 */
class ChatCoordinatorTest {
    @TestFactory
    fun chatCoordinatorTests(): List<DynamicTest> = listOf(
        case("initialPageSize keeps the standard page when unread fits within it") {
            assertEquals(ChatCoordinator.PAGE_SIZE, ChatCoordinator.initialPageSize(0))
            assertEquals(ChatCoordinator.PAGE_SIZE, ChatCoordinator.initialPageSize(10))
            val underFit = ChatCoordinator.PAGE_SIZE - ChatCoordinator.DIVIDER_READ_CONTEXT - 1
            assertEquals(ChatCoordinator.PAGE_SIZE, ChatCoordinator.initialPageSize(underFit))
        },
        case("initialPageSize grows to cover all unread plus read context") {
            val unread = ChatCoordinator.PAGE_SIZE + 70
            val limit = ChatCoordinator.initialPageSize(unread)
            assertEquals(unread + ChatCoordinator.DIVIDER_READ_CONTEXT, limit)
            assertTrue(limit > unread, "Under the cap, unread plus context must fit in the first page")
        },
        case("initialPageSize caps a huge unread backlog") {
            assertEquals(ChatCoordinator.MAX_INITIAL_PAGE_SIZE, ChatCoordinator.initialPageSize(ChatCoordinator.MAX_INITIAL_PAGE_SIZE * 10))
        },
        case("append adds a new message and bumps renderStateID") {
            runRendering {
                val coordinator = renderingCoordinator()
                val message = RenderingFixtures.testDirectMessage()
                val before = coordinator.renderStateID
                assertTrue(coordinator.append(message))
                assertEquals(1, coordinator.messages.size)
                assertEquals(message, coordinator.messagesByID[message.id])
                assertEquals(before + 1UL, coordinator.renderStateID)
            }
        },
        case("append is idempotent on duplicate id") {
            runRendering {
                val coordinator = renderingCoordinator()
                val message = RenderingFixtures.testDirectMessage()
                coordinator.append(message)
                val countBefore = coordinator.messages.size
                val renderIDBefore = coordinator.renderStateID
                assertFalse(coordinator.append(message))
                assertEquals(countBefore, coordinator.messages.size)
                assertEquals(renderIDBefore, coordinator.renderStateID)
            }
        },
        case("update no-ops on missing id") {
            runRendering {
                val coordinator = renderingCoordinator()
                val renderIDBefore = coordinator.renderStateID
                coordinator.update(UUID.randomUUID()) { RenderingFixtures.testDirectMessage() }
                assertTrue(coordinator.messages.isEmpty())
                assertEquals(renderIDBefore, coordinator.renderStateID)
            }
        },
        case("update mutates an existing message in place and bumps renderStateID") {
            runRendering {
                val coordinator = renderingCoordinator()
                val message = RenderingFixtures.testDirectMessage(text = "before")
                coordinator.append(message)
                val renderIDBefore = coordinator.renderStateID
                coordinator.update(message.id) { RenderingFixtures.testDirectMessage(id = message.id, text = "after") }
                assertEquals("after", coordinator.messages.first().text)
                assertEquals(renderIDBefore + 1UL, coordinator.renderStateID)
            }
        },
        case("renderStateID increments on every mutation") {
            runRendering {
                val coordinator = renderingCoordinator()
                val initial = coordinator.renderStateID
                coordinator.replaceAll(listOf(RenderingFixtures.testDirectMessage()))
                coordinator.append(RenderingFixtures.testDirectMessage())
                coordinator.update(coordinator.messages[0].id) { it }
                coordinator.remove(coordinator.messages[0].id)
                assertEquals(initial + 4UL, coordinator.renderStateID)
            }
        },
        case("rebuildItems fires renderStateInvalidated when setRenderState rejects stale build") {
            runRendering {
                val coordinator = renderingCoordinator()
                val message = RenderingFixtures.testDirectMessage()
                coordinator.append(message)
                val invalidated = AtomicInteger()
                coordinator.renderStateInvalidated = { invalidated.incrementAndGet() }
                val inputs = listOf(
                    message to MessageBuildInputs(
                        messageID = message.id,
                        previewState = PreviewLoadState.IDLE,
                        loadedPreview = null,
                        cachedURL = null,
                        isInlineImageURL = false,
                        hasInlineImageRef = false,
                        hasPreviewImageRef = false,
                        hasPreviewIconRef = false,
                        imageIsGIF = false,
                        formattedText = null,
                        baseColor = BaseColorSlot.INCOMING,
                        formattedPath = null,
                        senderResolution = NodeNameResolution("", NodeNameMatchKind.UNRESOLVED),
                        showTimestamp = false,
                        showDirectionGap = false,
                        showSenderName = false,
                        showNewMessagesDivider = false,
                    ),
                )
                coordinator.rebuildItems(inputs, EnvInputs.DEFAULT)
                // Advance the id before the off-thread build lands, so the apply is rejected.
                coordinator.append(RenderingFixtures.testDirectMessage())
                coordinator.buildItemsTask?.join()
                assertTrue(coordinator.renderState.items.isEmpty())
                assertEquals(1, invalidated.get())
            }
        },
        case("setRenderState returns false on stale capturedID") {
            runRendering {
                val coordinator = renderingCoordinator()
                val staleID = coordinator.renderStateID
                coordinator.append(RenderingFixtures.testDirectMessage())
                assertFalse(coordinator.setRenderState(ChatRenderState.EMPTY.with(hasMoreMessages = false), staleID))
                assertTrue(coordinator.renderState.hasMoreMessages)
            }
        },
        case("setRenderState applies when capturedID matches") {
            runRendering {
                val coordinator = renderingCoordinator()
                val currentID = coordinator.renderStateID
                assertTrue(coordinator.setRenderState(ChatRenderState.EMPTY.with(hasMoreMessages = false), currentID))
                assertFalse(coordinator.renderState.hasMoreMessages)
            }
        },
        case("enqueueReload unions IDs into pendingReloadIDs") {
            runRendering {
                val coordinator = renderingCoordinator()
                val id1 = UUID.randomUUID()
                val id2 = UUID.randomUUID()
                coordinator.enqueueReload(setOf(id1))
                coordinator.enqueueReload(setOf(id2))
                // The drain is scheduled but cannot run before this non-suspending block yields.
                assertTrue(id1 in coordinator.pendingReloadIDs)
                assertTrue(id2 in coordinator.pendingReloadIDs)
                assertTrue(coordinator.reloadInFlight)
            }
        },
        case("applyReloadedIDs invokes renderItemRebuilder after refreshing a DTO") {
            runRendering {
                val store = RenderingInMemoryMessageStore()
                val registry = renderingRegistry(store)
                val radioId = RadioId(UUID.randomUUID())
                val contactID = UUID.randomUUID()
                val coordinator = registry.coordinator(ChatConversationID.dm(radioId, contactID))
                val initial = RenderingFixtures.testDirectMessage(
                    radioId = radioId, contactID = contactID, text = "before", status = MessageStatus.SENDING,
                )
                store.saveMessage(initial)
                coordinator.append(initial)
                val rebuiltIDs = CopyOnWriteArrayList<UUID>()
                coordinator.renderItemRebuilder = { rebuiltIDs.add(it) }
                // An ack lands in the store after the coordinator loaded the message.
                store.updateMessageStatus(com.meshcoreone.android.core.contracts.domain.EntityKey(radioId, initial.id), MessageStatus.SENT)
                coordinator.enqueueReload(initial.id)
                coordinator.coalescedReloadTask?.join()
                assertFalse(coordinator.reloadInFlight)
                assertEquals(listOf(initial.id), rebuiltIDs.toList())
                assertEquals(MessageStatus.SENT, coordinator.messagesByID[initial.id]?.status)
            }
        },
        case("applyStatusUpdate blocks .failed -> .pending unless userInitiated") {
            runRendering {
                val coordinator = renderingCoordinator()
                val message = RenderingFixtures.testDirectMessage(status = MessageStatus.PENDING)
                coordinator.append(message)
                coordinator.applyStatusUpdate(message.id, MessageStatus.FAILED)
                assertEquals(MessageStatus.FAILED, coordinator.messagesByID[message.id]?.status)
                coordinator.applyStatusUpdate(message.id, MessageStatus.PENDING)
                assertEquals(MessageStatus.FAILED, coordinator.messagesByID[message.id]?.status)
                coordinator.applyStatusUpdate(message.id, MessageStatus.PENDING, userInitiated = true)
                assertEquals(MessageStatus.PENDING, coordinator.messagesByID[message.id]?.status)
            }
        },
        case("fresh coordinator starts in .uninitialized phase") {
            runRendering { assertEquals(ChatRenderState.LoadPhase.UNINITIALIZED, renderingCoordinator().renderState.phase) }
        },
        case("beginLoading transitions .uninitialized to .loading") {
            runRendering {
                val coordinator = renderingCoordinator()
                val before = coordinator.renderStateID
                coordinator.beginLoading()
                assertEquals(ChatRenderState.LoadPhase.LOADING, coordinator.renderState.phase)
                assertEquals(before + 1UL, coordinator.renderStateID)
            }
        },
        case("beginLoading is a no-op once .loaded") {
            runRendering {
                val coordinator = renderingCoordinator()
                coordinator.replaceAll(emptyList())
                val before = coordinator.renderStateID
                coordinator.beginLoading()
                assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
                assertEquals(before, coordinator.renderStateID)
            }
        },
        case("beginLoading is a no-op while already .loading") {
            runRendering {
                val coordinator = renderingCoordinator()
                coordinator.beginLoading()
                val before = coordinator.renderStateID
                coordinator.beginLoading()
                assertEquals(ChatRenderState.LoadPhase.LOADING, coordinator.renderState.phase)
                assertEquals(before, coordinator.renderStateID)
            }
        },
        case("markLoaded transitions .uninitialized to .loaded") {
            runRendering {
                val coordinator = renderingCoordinator()
                val before = coordinator.renderStateID
                coordinator.markLoaded()
                assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
                assertEquals(before + 1UL, coordinator.renderStateID)
            }
        },
        case("markLoaded transitions .loading to .loaded") {
            runRendering {
                val coordinator = renderingCoordinator()
                coordinator.beginLoading()
                val before = coordinator.renderStateID
                coordinator.markLoaded()
                assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
                assertEquals(before + 1UL, coordinator.renderStateID)
            }
        },
        case("markLoaded is idempotent when already .loaded") {
            runRendering {
                val coordinator = renderingCoordinator()
                coordinator.markLoaded()
                val before = coordinator.renderStateID
                coordinator.markLoaded()
                assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
                assertEquals(before, coordinator.renderStateID)
            }
        },
        case("replaceAll transitions phase to .loaded") {
            runRendering {
                val coordinator = renderingCoordinator()
                assertEquals(ChatRenderState.LoadPhase.UNINITIALIZED, coordinator.renderState.phase)
                coordinator.replaceAll(listOf(RenderingFixtures.testDirectMessage()))
                assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
            }
        },
        case("replaceAll with empty list still transitions to .loaded") {
            runRendering {
                val coordinator = renderingCoordinator()
                coordinator.beginLoading()
                coordinator.replaceAll(emptyList())
                assertEquals(ChatRenderState.LoadPhase.LOADED, coordinator.renderState.phase)
                assertTrue(coordinator.messages.isEmpty())
            }
        },
        case("phase is observed identically by sibling view models sharing one coordinator") {
            runRendering {
                val registry = renderingRegistry()
                val conversationID = ChatConversationID.dm(RadioId(UUID.randomUUID()), UUID.randomUUID())
                val coordinatorA = registry.coordinator(conversationID)
                val coordinatorB = registry.coordinator(conversationID)
                assertSame(coordinatorA, coordinatorB)
                assertEquals(ChatRenderState.LoadPhase.UNINITIALIZED, coordinatorA.renderState.phase)
                coordinatorA.replaceAll(emptyList())
                assertEquals(ChatRenderState.LoadPhase.LOADED, coordinatorA.renderState.phase)
                assertEquals(ChatRenderState.LoadPhase.LOADED, coordinatorB.renderStateFlow.value.phase)
            }
        },
        case("applyReloadedIDs skips renderItemRebuilder for unknown IDs") {
            runRendering {
                val store = RenderingInMemoryMessageStore()
                val registry = renderingRegistry(store)
                val radioId = RadioId(UUID.randomUUID())
                val contactID = UUID.randomUUID()
                val coordinator = registry.coordinator(ChatConversationID.dm(radioId, contactID))
                val pagedOut = RenderingFixtures.testDirectMessage(
                    radioId = radioId, contactID = contactID, text = "paged out", status = MessageStatus.SENT,
                )
                store.saveMessage(pagedOut)
                val rebuiltIDs = CopyOnWriteArrayList<UUID>()
                coordinator.renderItemRebuilder = { rebuiltIDs.add(it) }
                coordinator.enqueueReload(pagedOut.id)
                coordinator.coalescedReloadTask?.join()
                assertTrue(rebuiltIDs.isEmpty())
                assertEquals(1, store.fetchedKeys.size, "the paged-out id is still fetched, just not applied")
            }
        },
    )

    private fun case(name: String, body: () -> Unit): DynamicTest = DynamicTest.dynamicTest("ChatCoordinatorTests::$name()", body)
}
