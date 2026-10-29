// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChannelServicePipelineTests.swift@db14559b39d32322b06477c6ae676112f583db50
// The session-role seam retains the original read/missing/error outcomes; all persistence is actual Room.
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
        val committed = store.fetchChannels(RADIO_A)
        assertEquals(expected, committed.map { ChannelInfo(it.index, it.name, it.secret) })
        assertEquals(listOf(committed), cache.snapshots)
    }

    @OriginalCase("ChannelServicePipelineTests::a dropped write is reconciled with a serial read and persisted()")
    @Test fun missingPipelineSlotIsCommittedOnlyAfterItsAcknowledgedReconcileRead() = runTest {
        val session = Session((0..3).map { info(it) }, missing = setOf(2u))
        val cache = Cache()
        val result = ChannelService(session, store, cache, noDelay).syncChannels(RADIO_A, 4u, true)
        assertEquals(4L, result.channelsSynced)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf<UByte>(0u, 1u, 2u, 3u, 2u), session.reads)
        assertEquals((0..3).map { info(it) },
            store.fetchChannels(RADIO_A).map { ChannelInfo(it.index, it.name, it.secret) })
        assertEquals(1, cache.snapshots.size)
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
    @Test fun failedReconcileCommitsOnlyAnsweredSlotsAndPreservesTheCompleteUnreadRowAndHistory() = runTest {
        val kept = ChannelDTO(radioId = RADIO_A, index = 2u, name = "keep", secret = secret(0xCD))
        store.saveChannel(kept)
        val history = message(contactID = null, channelIndex = 2u)
        store.saveMessage(history)
        val session = Session(listOf(info(0), info(1), info(3)), setOf(2u), setOf(2u))
        val cache = Cache()
        val result = ChannelService(session, store, cache, noDelay).syncChannels(RADIO_A, 4u, true)
        assertEquals(3L, result.channelsSynced)
        assertEquals(listOf<UByte>(0u, 1u, 2u, 3u, 2u), session.reads)
        assertEquals(2.toUByte(), result.errors.single().index)
        assertEquals(ChannelSyncErrorType.TransportError, result.errors.single().errorType)
        assertFalse(result.circuitBreakerAborted)
        assertEquals((0..3).map { it.toUByte() }, store.fetchChannels(RADIO_A).map { it.index })
        assertEquals(kept, store.fetchChannel(entity(id = kept.id)))
        assertEquals(history, store.fetchMessage(entity(id = history.id)))
        assertEquals(1, cache.snapshots.size)
    }

    @OriginalCase("ChannelServicePipelineTests::reconcile circuit breaker opens after consecutive failures and never deletes unread slots()")
    @Test fun threeFailedReconcilesOpenTheBreakerWithoutDeletingAnyUnreadSlot() = runTest {
        val kept = (2..7).map { ChannelDTO(radioId = RADIO_A, index = it.toUByte(), name = "keep$it", secret = secret(0xCD)) }
        kept.forEach { store.saveChannel(it) }
        val missing = (2..7).map { it.toUByte() }.toSet()
        val session = Session(listOf(info(0), info(1)), missing, missing)
        val cache = Cache()
        val result = ChannelService(session, store, cache, noDelay).syncChannels(RADIO_A, 8u, true)
        assertEquals(2L, result.channelsSynced)
        assertTrue(result.circuitBreakerAborted)
        assertEquals(listOf<UByte>(2u, 3u, 4u), result.errors.filter { it.errorType == ChannelSyncErrorType.TransportError }.map { it.index })
        assertEquals(listOf<UByte>(5u, 6u, 7u), result.errors.filter { it.errorType == ChannelSyncErrorType.CircuitBreaker }.map { it.index })
        assertEquals((0..7).map { it.toUByte() } + listOf<UByte>(2u, 3u, 4u), session.reads)
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
