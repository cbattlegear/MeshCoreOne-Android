// PortedFrom: MC1Services/Tests/MC1ServicesTests/ChatTimelineWriterTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import java.lang.ref.Reference
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Owner stand-in for `bindWriter`; the coordinator holds it weakly. */
private class WriterOwner

/**
 * Android owners explicitly release at screen/prime completion. Correctness never waits for a JVM
 * collection cycle; the source's ARC teardown family exercises that deterministic native equivalent.
 */
class ChatTimelineWriterTest {
    private fun makeMessage(text: String = "hello"): MessageDTO = MessageDTO(
        id = UUID.randomUUID(),
        radioId = RadioId(UUID.randomUUID()),
        contactID = UUID.randomUUID(),
        text = text,
        timestamp = 1u,
        createdAt = Instant.now(),
        direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED,
    )

    @TestFactory
    fun chatTimelineWriterTests(): List<DynamicTest> = listOf(
        case("interactive bind always succeeds and revokes the prior writer") {
            runRendering {
                val coordinator = renderingCoordinator()
                val ownerA = WriterOwner()
                val ownerB = WriterOwner()
                val writerA = assertNotNull(coordinator.bindWriter(ownerA, ChatWriterRole.INTERACTIVE))
                assertTrue(writerA.isCurrent)
                val writerB = assertNotNull(coordinator.bindWriter(ownerB, ChatWriterRole.INTERACTIVE))
                assertTrue(writerB.isCurrent)
                assertFalse(writerA.isCurrent)
            }
        },
        case("releaseWriter vacates the slot for a later prime bind") {
            runRendering {
                val coordinator = renderingCoordinator()
                val owner = WriterOwner()
                val primeOwner = WriterOwner()
                val writer = assertNotNull(coordinator.bindWriter(owner, ChatWriterRole.INTERACTIVE))
                assertNull(coordinator.bindWriter(primeOwner, ChatWriterRole.PRIME))
                coordinator.releaseWriter(owner)
                val primeWriter = assertNotNull(coordinator.bindWriter(primeOwner, ChatWriterRole.PRIME))
                assertTrue(primeWriter.isCurrent)
                assertFalse(writer.isCurrent)
            }
        },
        case("releaseWriter from a superseded owner does not evict the successor") {
            runRendering {
                val coordinator = renderingCoordinator()
                val ownerA = WriterOwner()
                val ownerB = WriterOwner()
                val primeOwner = WriterOwner()
                assertNotNull(coordinator.bindWriter(ownerA, ChatWriterRole.INTERACTIVE))
                val writerB = assertNotNull(coordinator.bindWriter(ownerB, ChatWriterRole.INTERACTIVE))
                coordinator.releaseWriter(ownerA)
                assertNull(coordinator.bindWriter(primeOwner, ChatWriterRole.PRIME))
                assertTrue(writerB.isCurrent)
                Reference.reachabilityFence(ownerB)
            }
        },
        case("stale writer mutations all no-op") {
            runRendering {
                val coordinator = renderingCoordinator()
                val ownerA = WriterOwner()
                val ownerB = WriterOwner()
                val seeded = makeMessage()
                val writerA = assertNotNull(coordinator.bindWriter(ownerA, ChatWriterRole.INTERACTIVE))
                writerA.replaceAll(listOf(seeded))
                assertEquals(1, coordinator.messages.size)
                assertNotNull(coordinator.bindWriter(ownerB, ChatWriterRole.INTERACTIVE))

                val renderStateIDBefore = coordinator.renderStateID
                val messagesBefore = coordinator.messages
                val renderStateBefore = coordinator.renderState
                val extra = makeMessage("stale append")
                writerA.replaceAll(emptyList())
                assertFalse(writerA.append(extra))
                writerA.prepend(listOf(extra))
                writerA.update(seeded.id) { it.copy(text = "stale edit") }
                writerA.remove(seeded.id)
                writerA.replaceMessagesPreservingByID(emptyList())
                writerA.beginLoading()
                writerA.markLoaded()
                writerA.updateRenderState { it.with(isLoadingOlder = true) }
                writerA.updateRenderItem(seeded.id) { it }
                writerA.removeRenderItem(seeded.id)
                writerA.applyStatusUpdate(seeded.id, MessageStatus.FAILED)
                writerA.rebuildItems(emptyList(), EnvInputs.DEFAULT)
                writerA.enqueueReload(seeded.id)

                assertEquals(renderStateIDBefore, coordinator.renderStateID)
                assertEquals(messagesBefore, coordinator.messages)
                assertEquals(renderStateBefore, coordinator.renderState)
                assertEquals("hello", coordinator.messagesByID[seeded.id]?.text)
            }
        },
        case("current writer mutations apply") {
            runRendering {
                val coordinator = renderingCoordinator()
                val writer = assertNotNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.INTERACTIVE))
                val message = makeMessage()
                assertTrue(writer.append(message))
                assertEquals(1, coordinator.messages.size)
                writer.update(message.id) { it.copy(text = "edited") }
                assertEquals("edited", coordinator.messagesByID[message.id]?.text)
                writer.remove(message.id)
                assertTrue(coordinator.messages.isEmpty())
            }
        },
        case("prime bind is denied while an interactive owner is alive") {
            runRendering {
                val coordinator = renderingCoordinator()
                val interactiveOwner = WriterOwner()
                val interactive = assertNotNull(coordinator.bindWriter(interactiveOwner, ChatWriterRole.INTERACTIVE))
                assertNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.PRIME))
                assertTrue(interactive.isCurrent)
                Reference.reachabilityFence(interactiveOwner) // Swift keeps the owner alive to scope end
            }
        },
        case("prime bind succeeds over a vacant slot, a prior prime, and a deallocated owner") {
            runRendering {
                val coordinator = renderingCoordinator()
                val primeOwnerA = WriterOwner()
                val primeA = assertNotNull(coordinator.bindWriter(primeOwnerA, ChatWriterRole.PRIME)) // vacant slot
                val primeOwnerB = WriterOwner()
                val primeB = assertNotNull(coordinator.bindWriter(primeOwnerB, ChatWriterRole.PRIME)) // prior prime
                assertFalse(primeA.isCurrent)
                assertTrue(primeB.isCurrent)

                val owner = WriterOwner()
                val interactive = assertNotNull(coordinator.bindWriter(owner, ChatWriterRole.INTERACTIVE))
                coordinator.releaseWriter(owner)
                assertFalse(interactive.isCurrent)
                interactive.append(makeMessage("released"))
                assertTrue(coordinator.messages.isEmpty())
                assertNotNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.PRIME))
            }
        },
        case("interactive bind swaps hooks atomically with write ownership") {
            runRendering {
                val coordinator = renderingCoordinator()
                val rebuilderHits = mutableListOf<String>()
                val primeWriter = coordinator.bindWriter(WriterOwner(), ChatWriterRole.PRIME, renderItemRebuilder = { rebuilderHits.add("prime") })
                val interactiveOwner = WriterOwner()
                val interactiveWriter = coordinator.bindWriter(
                    interactiveOwner, ChatWriterRole.INTERACTIVE, renderItemRebuilder = { rebuilderHits.add("interactive") },
                )
                assertNotNull(primeWriter)
                assertNotNull(interactiveWriter)
                coordinator.renderItemRebuilder?.invoke(UUID.randomUUID())
                assertEquals(listOf("interactive"), rebuilderHits)
            }
        },
        case("stale writer cannot schedule a full rebuild") {
            runRendering {
                val coordinator = renderingCoordinator()
                val message = makeMessage()
                val writerA = assertNotNull(coordinator.bindWriter(WriterOwner(), ChatWriterRole.PRIME))
                writerA.replaceAll(listOf(message))
                val interactiveOwner = WriterOwner()
                assertNotNull(coordinator.bindWriter(interactiveOwner, ChatWriterRole.INTERACTIVE))
                val renderStateIDBefore = coordinator.renderStateID
                writerA.rebuildItems(emptyList(), EnvInputs.DEFAULT)
                assertEquals(renderStateIDBefore, coordinator.renderStateID)
                assertNull(coordinator.buildItemsTask)
            }
        },
    )

    private fun case(name: String, body: () -> Unit): DynamicTest = DynamicTest.dynamicTest("ChatTimelineWriterTests::$name()", body)

}
