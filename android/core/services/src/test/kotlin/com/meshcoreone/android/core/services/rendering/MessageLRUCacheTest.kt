// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageLRUCacheTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class MessageLRUCacheTest {
    private val hello = ReactionWireFormat.generateMessageHash("Hello", 1_704_067_200u)

    @TestFactory
    fun messageLRUCacheTests(): List<DynamicTest> = listOf(
        case("Indexes and retrieves message") {
            val cache = MessageLRUCache()
            val messageID = UUID.randomUUID()
            cache.index(messageID, 0u, "Node", "Hello", 1_704_067_200u)
            val candidates = cache.lookup(0u, "Node", hello)
            assertEquals(1, candidates.size)
            assertEquals(messageID, candidates.first().messageID)
            assertEquals("Hello", candidates.first().text)
            assertEquals(1_704_067_200u, candidates.first().timestamp)
        },
        case("Returns empty array for non-existent message") {
            assertTrue(MessageLRUCache().lookup(0u, "Node", "abcd1234").isEmpty())
        },
        case("Evicts oldest key at capacity") {
            val cache = MessageLRUCache(capacity = 3)
            for (i in 0 until 3) cache.index(UUID.randomUUID(), 0u, "Node$i", "Message $i", i.toUInt())
            val hash0 = ReactionWireFormat.generateMessageHash("Message 0", 0u)
            assertFalse(cache.lookup(0u, "Node0", hash0).isEmpty())
            cache.index(UUID.randomUUID(), 0u, "Node3", "Message 3", 3u)
            assertTrue(cache.lookup(0u, "Node0", hash0).isEmpty())
        },
        case("Different channels are separate") {
            val cache = MessageLRUCache()
            val messageID = UUID.randomUUID()
            cache.index(messageID, 0u, "Node", "Hello", 1_704_067_200u)
            assertEquals(messageID, cache.lookup(0u, "Node", hello).firstOrNull()?.messageID)
            assertTrue(cache.lookup(1u, "Node", hello).isEmpty())
        },
        case("Stores multiple candidates per key") {
            val cache = MessageLRUCache()
            val id1 = UUID.randomUUID()
            val id2 = UUID.randomUUID()
            cache.index(id1, 0u, "Node", "Hello", 1_704_067_200u)
            cache.index(id2, 0u, "Node", "Hello", 1_704_067_200u)
            val candidates = cache.lookup(0u, "Node", hello)
            assertEquals(2, candidates.size)
            assertTrue(candidates.any { it.messageID == id1 })
            assertTrue(candidates.any { it.messageID == id2 })
        },
        case("Caps candidates per key") {
            val cache = MessageLRUCache(maxCandidatesPerKey = 3)
            val ids = List(5) { UUID.randomUUID() }
            ids.forEach { cache.index(it, 0u, "Node", "Hello", 1_704_067_200u) }
            val candidates = cache.lookup(0u, "Node", hello)
            assertEquals(3, candidates.size)
            assertFalse(candidates.any { it.messageID == ids[0] })
            assertFalse(candidates.any { it.messageID == ids[1] })
            assertTrue(candidates.any { it.messageID == ids[2] })
            assertTrue(candidates.any { it.messageID == ids[3] })
            assertTrue(candidates.any { it.messageID == ids[4] })
        },
        case("Re-indexing same messageID updates instead of duplicating") {
            val cache = MessageLRUCache()
            val messageID = UUID.randomUUID()
            cache.index(messageID, 0u, "Node", "Hello", 1_704_067_200u)
            cache.index(messageID, 0u, "Node", "Hello", 1_704_067_200u)
            val candidates = cache.lookup(0u, "Node", hello)
            assertEquals(1, candidates.size)
            assertEquals(messageID, candidates.first().messageID)
        },
    )

    @TestFactory
    fun nativeCacheCases(): List<DynamicTest> = listOf(
        native("re-indexing an existing key refreshes its recency so a different key is evicted") {
            val cache = MessageLRUCache(capacity = 2)
            cache.index(UUID.randomUUID(), 0u, "A", "a", 1u)
            cache.index(UUID.randomUUID(), 0u, "B", "b", 2u)
            cache.index(UUID.randomUUID(), 0u, "A", "a", 1u) // touch A
            cache.index(UUID.randomUUID(), 0u, "C", "c", 3u) // evicts B
            assertEquals(2, cache.lookup(0u, "A", ReactionWireFormat.generateMessageHash("a", 1u)).size)
            assertTrue(cache.lookup(0u, "B", ReactionWireFormat.generateMessageHash("b", 2u)).isEmpty())
            assertEquals(1, cache.lookup(0u, "C", ReactionWireFormat.generateMessageHash("c", 3u)).size)
        },
        native("pruned candidates keep insertion order, oldest first, and a re-indexed id moves to the end") {
            val cache = MessageLRUCache(maxCandidatesPerKey = 2)
            val (a, b, c) = List(3) { UUID.randomUUID() }
            listOf(a, b, a, c).forEach { cache.index(it, 0u, "Node", "Hello", 1_704_067_200u) }
            assertEquals(listOf(a, c), cache.lookup(0u, "Node", hello).map { it.messageID })
        },
        native("sender keys match by canonical equivalence like Swift String keys") {
            val cache = MessageLRUCache()
            val id = UUID.randomUUID()
            cache.index(id, 0u, "José", "Hello", 1_704_067_200u)
            assertEquals(id, cache.lookup(0u, "José", hello).single().messageID)
        },
        native("DM cache is separate from channel cache, evicts at capacity and clear() empties both") {
            val clock = Clock.fixed(Instant.ofEpochSecond(42), ZoneOffset.UTC)
            val cache = MessageLRUCache(capacity = 1, clock = clock)
            val contactA = UUID.randomUUID()
            val contactB = UUID.randomUUID()
            val dmID = UUID.randomUUID()
            cache.indexDM(dmID, contactA, "Hello", 1_704_067_200u)
            assertTrue(cache.lookup(0u, "Node", hello).isEmpty())
            val candidate = cache.lookupDM(contactA, hello).single()
            assertEquals(MessageCandidate(dmID, "Hello", 1_704_067_200u, Instant.ofEpochSecond(42)), candidate)
            cache.indexDM(UUID.randomUUID(), contactB, "Hello", 1_704_067_200u)
            assertTrue(cache.lookupDM(contactA, hello).isEmpty())
            cache.index(UUID.randomUUID(), 0u, "Node", "Hello", 1_704_067_200u)
            cache.clear()
            assertTrue(cache.lookupDM(contactB, hello).isEmpty())
            assertTrue(cache.lookup(0u, "Node", hello).isEmpty())
        },
        native("capacity zero keeps Swift's quirk: the key is dropped from order but its candidate stays") {
            val cache = MessageLRUCache(capacity = 0)
            val id = UUID.randomUUID()
            cache.index(id, 0u, "Node", "Hello", 1_704_067_200u)
            assertEquals(id, cache.lookup(0u, "Node", hello).single().messageID)
        },
    )

    private fun case(name: String, body: () -> Unit) = DynamicTest.dynamicTest("MessageLRUCacheTests::$name()", body)

    private fun native(name: String, body: () -> Unit) = DynamicTest.dynamicTest("WP-213::$name", body)
}
