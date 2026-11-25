// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreMessageWindowTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class MessageWindowPersistenceTest : RepositoryTest() {
    private suspend fun seed(count: Int) {
        for (timestamp in 1..count) store.saveMessage(message(timestamp = timestamp.toUInt(),
            createdAt = Instant.ofEpochSecond(timestamp.toLong()), text = "m$timestamp"))
    }

    @Test @OriginalCase("PersistenceStoreMessageWindowTests::nil anchor applies the floor and reports hasMore()")
    fun nilAnchorUsesFloor() = runTest {
        seed(10)
        val window = store.fetchMessageWindow(entity(), null, 3)
        assertEquals(listOf(8u, 9u, 10u), window.messages.map { it.timestamp })
        assertTrue(window.hasMore)
    }

    @Test @OriginalCase("PersistenceStoreMessageWindowTests::anchor widening beats the floor()")
    fun anchorWidensTheWindow() = runTest {
        seed(10)
        val window = store.fetchMessageWindow(entity(), Instant.ofEpochSecond(4), 3)
        assertEquals(listOf(4u, 5u, 6u, 7u, 8u, 9u, 10u), window.messages.map { it.timestamp })
        assertTrue(window.hasMore)
    }

    @Test @OriginalCase("PersistenceStoreMessageWindowTests::tie rows at the anchor are included()")
    fun allAnchorTiesAreIncluded() = runTest {
        for (timestamp in listOf(3u, 5u, 5u, 7u)) store.saveMessage(message(timestamp = timestamp,
            createdAt = Instant.ofEpochSecond(timestamp.toLong()), text = "m$timestamp"))
        val window = store.fetchMessageWindow(entity(), Instant.ofEpochSecond(5), 1)
        assertEquals(2, window.messages.count { it.sortDate == Instant.ofEpochSecond(5) })
        assertFalse(window.messages.any { it.timestamp == 3u })
        assertTrue(window.hasMore)
    }

    @Test @OriginalCase("PersistenceStoreMessageWindowTests::probe row is dropped and hasMore is exact at the boundary()")
    fun probeIsDroppedAndExactBoundaryHasNoMore() = runTest {
        seed(4)
        val floor = store.fetchMessageWindow(entity(), null, 3)
        assertEquals(listOf(2u, 3u, 4u), floor.messages.map { it.timestamp })
        assertTrue(floor.hasMore)
        val exact = store.fetchMessageWindow(entity(), null, 4)
        assertEquals(listOf(1u, 2u, 3u, 4u), exact.messages.map { it.timestamp })
        assertFalse(exact.hasMore)
    }

    @Test @OriginalCase("PersistenceStoreMessageWindowTests::channel window matches the contact variant()")
    fun channelWindowHasTheSameAnchorRules() = runTest {
        for (timestamp in 1u..5u) store.saveMessage(message(contactID = null, channelIndex = 2u,
            timestamp = timestamp, createdAt = Instant.ofEpochSecond(timestamp.toLong()), text = "c$timestamp"))
        val window = store.fetchMessageWindow(RADIO_A, 2u, Instant.ofEpochSecond(3), 2)
        assertEquals(listOf(3u, 4u, 5u), window.messages.map { it.timestamp })
        assertTrue(window.hasMore)
    }
}
