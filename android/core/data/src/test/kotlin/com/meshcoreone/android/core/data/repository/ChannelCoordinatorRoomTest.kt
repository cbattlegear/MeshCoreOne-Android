// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChannelServicePipelineTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChannelServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.ChannelSyncErrorType
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.session.ChannelFetchResult
import com.meshcoreone.android.core.protocol.session.ChannelSessionOps
import com.meshcoreone.android.core.services.contacts.ChannelDecryptionCache
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.contacts.ChannelServiceClock
import kotlin.test.*
import org.junit.Test
import kotlinx.coroutines.test.runTest

class ChannelCoordinatorRoomTest : RepositoryTest() {
    private fun secret(byte: Int) = Bytes(ByteArray(16) { byte.toByte() })
    private fun info(index: Int, name: String = "ch$index", byte: Int = 0xAB) =
        ChannelInfo(index.toUByte(), name, secret(byte))

    private class Session(
        private val rows: List<ChannelInfo>,
        private val missing: Set<UByte> = emptySet(),
        private val failedReads: Set<UByte> = emptySet(),
        val sendFailure: MeshTransportError.SendFailed? = null,
    ) : ChannelSessionOps {
        val reads = mutableListOf<UByte>()
        override suspend fun getChannels(indices: List<UByte>): ChannelFetchResult {
            reads += indices
            sendFailure?.let { throw it }
            return ChannelFetchResult(indices.filter { it !in missing }.map(::row), indices.filter { it in missing })
        }
        override suspend fun getChannel(index: UByte): ChannelInfo {
            reads += index
            if (index in failedReads) throw MeshTransportError.SendFailed("controlled reconcile failure")
            return row(index)
        }
        private fun row(index: UByte) = rows.singleOrNull { it.index == index }
            ?: ChannelInfo(index, "", Bytes(ByteArray(16)))
        override suspend fun setChannel(index: UByte, name: String, secret: Bytes) =
            error("This read-only sync fixture must not write a radio channel")
    }

    private inner class Cache : ChannelDecryptionCache {
        val snapshots = mutableListOf<List<ChannelDTO>>()
        override suspend fun updateChannels(channels: List<ChannelDTO>) {
            assertEquals(store.fetchChannels(RADIO_A), channels, "Cache consumers must observe already committed rows")
            snapshots += channels.toList()
        }
    }

    private class Writes : ChannelSessionOps by Session(emptyList()) {
        val sent = mutableListOf<ChannelInfo>()
        override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
            sent += ChannelInfo(index, name, secret)
        }
    }

    @OriginalCase("ChannelServiceTests::clearChannel deletes the row and its messages, then fires the slot handler()")
    @Test fun clearCommitsTheActualSlotAndHistoryDeletionBeforeTheOccupantCallback() = runTest {
        val channel = ChannelDTO(radioId = RADIO_A, index = 5u, name = "Old", secret = secret(0x11))
        val history = message(contactID = null, channelIndex = 5u, text = "old")
        val foreign = channel.copy(radioId = RADIO_B)
        val foreignHistory = history.copy(radioId = RADIO_B)
        store.saveChannel(channel)
        store.saveChannel(foreign)
        store.saveMessage(history)
        store.saveMessage(foreignHistory)
        val session = Writes()
        val cache = Cache()
        val callbacks = mutableListOf<Set<UByte>>()
        val service = ChannelService(session, store, cache, noDelay)
        service.setSlotOccupantChangedHandler { radio, indices ->
            assertEquals(RADIO_A, radio)
            assertNull(store.fetchChannel(RADIO_A, 5u))
            assertTrue(store.fetchMessages(RADIO_A, 5u).isEmpty())
            callbacks += indices.toSet()
        }
        service.clearChannel(RADIO_A, 5u)
        assertEquals(listOf(setOf<UByte>(5u)), callbacks)
        assertEquals(listOf(ChannelInfo(5u, "", secret(0))), session.sent)
        assertEquals(foreign, store.fetchChannel(RADIO_B, 5u))
        assertEquals(foreignHistory, store.fetchMessage(entity(RADIO_B, history.id)))
        assertEquals(listOf(emptyList<ChannelDTO>()), cache.snapshots)
    }

    @OriginalCase("ChannelServiceTests::setChannel fires the slot handler on a new secret and not on a same-secret rename or first insert()")
    @Test fun actualFirstInsertAndRenameStaySilentButAChangedSecretPublishesTheCommittedReplacement() = runTest {
        val session = Writes()
        val cache = Cache()
        val callbacks = mutableListOf<Set<UByte>>()
        val service = ChannelService(session, store, cache, noDelay)
        service.setSlotOccupantChangedHandler { radio, indices ->
            assertEquals(RADIO_A, radio)
            assertEquals("Replaced", store.fetchChannel(RADIO_A, 5u)?.name)
            assertEquals(session.sent.last().secret, store.fetchChannel(RADIO_A, 5u)?.secret)
            callbacks += indices.toSet()
        }
        service.setChannel(RADIO_A, 5u, "First", "one")
        val first = assertNotNull(store.fetchChannel(RADIO_A, 5u))
        service.setChannel(RADIO_A, 5u, "Renamed", "one")
        assertEquals(first.copy(name = "Renamed"), store.fetchChannel(RADIO_A, 5u))
        assertTrue(callbacks.isEmpty())
        service.setChannel(RADIO_A, 5u, "Replaced", "two")
        assertNotEquals(first.secret, store.fetchChannel(RADIO_A, 5u)?.secret)
        assertEquals(listOf(setOf<UByte>(5u)), callbacks)
        assertEquals(listOf("First", "Renamed", "Replaced"), session.sent.map { it.name })
        assertEquals(3, cache.snapshots.size)
    }

    @OriginalCase("ChannelServiceTests::syncChannels fires the slot handler for vacated and secret-changed slots, not first sightings()")
    @Test fun actualSyncPublishesOnlyTheVacatedAndChangedOccupantsAfterTheirBatchCommits() = runTest {
        val old = listOf(info(1, "One", 0x11), info(2, "Two", 0x22))
        old.forEach { store.saveChannel(RADIO_A, it) }
        val foreign = ChannelDTO(radioId = RADIO_B, index = 2u, name = "Two", secret = secret(0x22))
        store.saveChannel(foreign)
        val session = Session(listOf(info(1, "OneB", 0x99), info(3, "First", 0x33)))
        val cache = Cache()
        val service = ChannelService(session, store, cache, noDelay)
        val callbacks = mutableListOf<Set<UByte>>()
        service.setSlotOccupantChangedHandler { radio, indices ->
            assertEquals(RADIO_A, radio)
            assertEquals(listOf(info(1, "OneB", 0x99), info(3, "First", 0x33)),
                store.fetchChannels(RADIO_A).map { ChannelInfo(it.index, it.name, it.secret) })
            callbacks += indices.toSet()
        }
        val result = service.syncChannels(RADIO_A, 4u, false)
        assertEquals(2L, result.channelsSynced)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf(setOf<UByte>(1u, 2u)), callbacks)
        assertEquals(foreign, store.fetchChannel(RADIO_B, 2u))
        assertEquals(1, cache.snapshots.size)
    }

    private val noDelay = object : ChannelServiceClock {
        override suspend fun sleep(duration: kotlin.time.Duration) = Unit
    }

    @OriginalCase("ChannelServicePipelineTests::pipelined sync persists non-contiguous configured channels to their own slots()")
    @Test fun nonContiguousPipelineCommitsExactSlotsBeforeUpdatingTheDecryptionConsumer() = runTest {
        val expected = listOf(info(0), info(2), info(7))
        val session = Session(expected)
        val cache = Cache()
        val result = ChannelService(session, store, cache, noDelay).syncChannels(RADIO_A, 8u, true)
        assertEquals(3L, result.channelsSynced)
        assertTrue(result.errors.isEmpty())
        assertEquals((0..7).map { it.toUByte() }, session.reads)
        val committed = store.fetchChannels(RADIO_A).toList()
        assertEquals(expected, committed.map { ChannelInfo(it.index, it.name, it.secret) })
        assertEquals(listOf(committed), cache.snapshots)
    }

    @OriginalCase("ChannelServicePipelineTests::a dropped write is reconciled with a serial read and persisted()")
    @Test fun missingPipelineSlotRequiresFreshSessionAndCommitsOnlyAnsweredSlots() = runTest {
        val session = Session((0..3).map { info(it) }, missing = setOf(2u))
        val cache = Cache()
        val result = ChannelService(session, store, cache, noDelay).syncChannels(RADIO_A, 4u, true)
        assertEquals(3L, result.channelsSynced)
        assertEquals(listOf<UByte>(2u), result.errors.map { it.index })
        assertEquals(ChannelSyncErrorType.TransportError, result.errors.single().errorType)
        assertTrue(result.retryableIndices.isEmpty(), "Retry belongs to a fresh physical connection, not this service")
        assertEquals(listOf<UByte>(0u, 1u, 2u, 3u), session.reads)
        assertEquals(listOf(0, 1, 3).map { info(it) },
            store.fetchChannels(RADIO_A).map { ChannelInfo(it.index, it.name, it.secret) })
        assertEquals(1, cache.snapshots.size)
        assertEquals(store.fetchChannels(RADIO_A), cache.snapshots.single())
    }

    @OriginalCase("ChannelServicePipelineTests::a transport send failure mid-pipeline throws and persists nothing()")
    @Test fun pipelineSendFailurePreservesRowsAndDoesNotPublishADecryptionSnapshot() = runTest {
        val foreign = ChannelDTO(radioId = RADIO_B, index = 2u, name = "foreign", secret = secret(0xCD))
        store.saveChannel(foreign)
        val failure = MeshTransportError.SendFailed("controlled pipeline send failure")
        val session = Session(emptyList(), sendFailure = failure)
        val cache = Cache()
        val service = ChannelService(session, store, cache, noDelay)
        assertSame(failure, assertFailsWith<MeshTransportError.SendFailed> {
            service.syncChannels(RADIO_A, 4u, true)
        })
        assertTrue(store.fetchChannels(RADIO_A).isEmpty())
        assertEquals(listOf(foreign), store.fetchChannels(RADIO_B))
        assertTrue(cache.snapshots.isEmpty())
    }

    @OriginalCase("ChannelServicePipelineTests::a mid-drain stall persists only read slots and never deletes an unread configured slot()")
    @Test fun reconnectRequiredCommitsOnlyAnsweredSlotsAndPreservesTheCompleteUnreadRowAndHistory() = runTest {
        val kept = ChannelDTO(radioId = RADIO_A, index = 2u, name = "keep", secret = secret(0xCD))
        store.saveChannel(kept)
        val history = message(contactID = null, channelIndex = 2u)
        store.saveMessage(history)
        val session = Session(listOf(info(0), info(1), info(3)), setOf(2u), setOf(2u))
        val cache = Cache()
        val result = ChannelService(session, store, cache, noDelay).syncChannels(RADIO_A, 4u, true)
        assertEquals(3L, result.channelsSynced)
        assertEquals(listOf<UByte>(0u, 1u, 2u, 3u), session.reads)
        assertEquals(2.toUByte(), result.errors.single().index)
        assertEquals(ChannelSyncErrorType.TransportError, result.errors.single().errorType)
        assertFalse(result.circuitBreakerAborted)
        assertEquals((0..3).map { it.toUByte() }, store.fetchChannels(RADIO_A).map { it.index })
        assertEquals(kept, store.fetchChannel(entity(id = kept.id)))
        assertEquals(history, store.fetchMessage(entity(id = history.id)))
        assertEquals(1, cache.snapshots.size)
    }

    @OriginalCase("ChannelServicePipelineTests::reconcile circuit breaker opens after consecutive failures and never deletes unread slots()")
    @Test fun allMissingSlotsRequireFreshSessionWithoutDeletingAnyUnreadSlot() = runTest {
        val kept = (2..7).map { ChannelDTO(radioId = RADIO_A, index = it.toUByte(), name = "keep$it", secret = secret(0xCD)) }
        kept.forEach { store.saveChannel(it) }
        val missing = (2..7).map { it.toUByte() }.toSet()
        val session = Session(listOf(info(0), info(1)), missing, missing)
        val cache = Cache()
        val result = ChannelService(session, store, cache, noDelay).syncChannels(RADIO_A, 8u, true)
        assertEquals(2L, result.channelsSynced)
        assertFalse(result.circuitBreakerAborted, "No same-link reconcile loop is entered")
        assertEquals((2..7).map { it.toUByte() }, result.errors.filter { it.errorType == ChannelSyncErrorType.TransportError }.map { it.index })
        assertTrue(result.errors.none { it.errorType == ChannelSyncErrorType.CircuitBreaker })
        assertTrue(result.retryableIndices.isEmpty())
        assertEquals((0..7).map { it.toUByte() }, session.reads)
        assertEquals((0..7).map { it.toUByte() }, store.fetchChannels(RADIO_A).map { it.index })
        for (row in kept) assertEquals(row, store.fetchChannel(entity(id = row.id)))
        assertEquals(1, cache.snapshots.size)
    }

    @OriginalCase("ChannelServicePipelineTests::serial path (usePipelinedRead: false) still syncs via acknowledged reads()")
    @Test fun serialReadsCommitOnlyTheConfiguredSlotBeforeConsumerNotification() = runTest {
        val session = Session(listOf(info(0)))
        val cache = Cache()
        val result = ChannelService(session, store, cache, noDelay).syncChannels(RADIO_A, 2u, false)
        assertEquals(1L, result.channelsSynced)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf<UByte>(0u, 1u), session.reads)
        assertEquals(listOf(info(0)), store.fetchChannels(RADIO_A).map { ChannelInfo(it.index, it.name, it.secret) })
        assertEquals(1, cache.snapshots.size)
    }
}
