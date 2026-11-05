// Source-derived neutral contracts, metadata, cancellation and deterministic baseline assertions.
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/NodeSnapshotPersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Connection/DeviceConnectionState.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ConnectionState
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DomainContractTest {
    @Test fun repositorySurfaceIsAbstractTypedAndIncludesEveryPersistenceRoleRatherThanSuccessShapedDefaults() {
        val roles = listOf(DevicePersisting::class.java, ContactPersisting::class.java, ChannelPersisting::class.java,
            MessagePersisting::class.java, TracePathPersisting::class.java, HeardRepeatPersisting::class.java,
            DebugLogPersisting::class.java, LinkPreviewPersisting::class.java, RxLogPersisting::class.java,
            RoomPersisting::class.java, DiscoveredNodePersisting::class.java, ReactionPersisting::class.java,
            NodeSnapshotPersisting::class.java, PendingSendPersisting::class.java, FailedSendPersisting::class.java, MetadataPersisting::class.java)
        roles.forEach { role ->
            assertTrue(role.isInterface); assertTrue(role.methods.isNotEmpty())
            val instanceMethods = role.methods.filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            assertTrue(instanceMethods.isNotEmpty())
            assertTrue(instanceMethods.all { Modifier.isAbstract(it.modifiers) }, role.name)
            assertTrue(role.isAssignableFrom(PersistenceStoreProtocol::class.java), role.name)
        }
        assertTrue(roles.sumOf { it.declaredMethods.size } >= 160)
        assertEquals(Long::class.javaPrimitiveType, Generation::class.java.declaredFields.single { it.name == "value" }.type)
        assertNotEquals(EntityKey(RADIO, UUID(0, 1)), EntityKey(RadioId(UUID(0, 2)), UUID(0, 1)))
    }

    @Test fun connectionRungsIntentEpochsAndOptionalBleProjectionStayIndependent() {
        assertEquals(listOf(DeviceConnectionState.SYNCING, DeviceConnectionState.READY), DeviceConnectionState.entries.filter { it.isOperational })
        assertEquals(listOf(DeviceConnectionState.READY), DeviceConnectionState.entries.filter { it.canDrainSendQueue })
        assertEquals(3, DeviceConnectionState.entries.count { it.isConnected })
        assertEquals(ConnectionIntent.None, ConnectionIntent.restored(false))
        assertEquals(ConnectionIntent.UserDisconnected, ConnectionIntent.restored(true))
        assertFalse(ConnectionIntent.WantsConnection(true).persistedUserDisconnected)
        val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(Long.MAX_VALUE), RADIO)
        assertNotEquals(token, token.copy(epoch = ProcessEpoch(UUID.randomUUID())))
        assertFailsWith<IllegalArgumentException> { Generation(-1) }
        assertFailsWith<IllegalArgumentException> {
            ConnectionSnapshot(DeviceConnectionState.READY, ConnectionState.Connected, null, ConnectionIntent.None, null, null)
        }
        assertFailsWith<IllegalArgumentException> {
            ConnectionSnapshot(DeviceConnectionState.DISCONNECTED, ConnectionState.Disconnected, null, ConnectionIntent.None, token, null)
        }
        assertEquals(11, BleLinkPhase.entries.size); assertEquals("discoveryComplete", BleLinkPhase.DISCOVERY_COMPLETE.rawValue)
        assertFalse(BleLinkPhase.DISCOVERY_COMPLETE.isDiscoveryChain); assertTrue(BleLinkPhase.DISCOVERING_SERVICES.isDiscoveryChain)
        assertTrue(ConnectionSnapshot(DeviceConnectionState.SYNCING, ConnectionState.Connected, null, ConnectionIntent.None, token, null).state.isOperational)
        assertFalse(MonitoringOptions(false, false).enableAutoFetch)
    }

    @Test fun typedStorageAndSyncErrorsRetainMetadataAndUnderlyingCauses() {
        val cause = IllegalStateException("controlled read failure")
        val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("read"), cause)
        assertSame(cause, failure.cause); assertEquals(PersistenceStoreError.FetchFailed("read"), failure.error)
        val errors = SnapshotList.of(
            ChannelSyncError(1u, ChannelSyncErrorType.Timeout, "request"),
            ChannelSyncError(2u, ChannelSyncErrorType.SendTimeout, "send"),
            ChannelSyncError(3u, ChannelSyncErrorType.DeviceError(255u), "device"),
            ChannelSyncError(4u, ChannelSyncErrorType.CircuitBreaker, "parked"),
        )
        val result = ChannelSyncResult(1, errors)
        assertFalse(result.isComplete); assertEquals(1L, result.requestTimeoutCount); assertEquals(1L, result.sendTimeoutCount)
        assertTrue(result.circuitBreakerAborted); assertEquals(listOf(1.toUByte(), 2.toUByte()), result.retryableIndices)
        assertTrue(errors[0].countsTowardCircuitBreaker); assertFalse(errors[2].countsTowardCircuitBreaker)
        assertEquals(cause, AppBackupException(AppBackupError.ImportFailed(cause)).cause)
        assertFalse(TeardownReport(SnapshotList.of(TeardownIssue(LifecycleStage.FLUSH_RX, cause))).isComplete)
        assertEquals(50000L, DebugLogRetention.MAX_ENTRIES); assertEquals(1100L, RxLogRetention.KEEP_COUNT + RxLogRetention.PRUNE_THRESHOLD)
    }

    @Test fun foregroundSupportSuspendsResumesAndPropagatesCancellation() = runTest {
        val provider = MockAppStateProvider(false)
        assertFalse(provider.isInForeground()); provider.hangForegroundChecks()
        val pending = async { provider.isInForeground() }; runCurrent()
        assertTrue(provider.isWaitingOnForegroundCheck); provider.setIsInForeground(true); provider.releaseForegroundCheck()
        assertTrue(pending.await()); assertFalse(provider.isWaitingOnForegroundCheck)
        provider.hangForegroundChecks(); val cancelled = async { provider.isInForeground() }; runCurrent()
        cancelled.cancelAndJoin(); assertTrue(cancelled.isCancelled); assertFalse(provider.isWaitingOnForegroundCheck)
        assertFailsWith<CancellationException> { cancelled.await() }
        provider.releaseForegroundCheck()
    }

    @Test fun sparseNeighborAndStatusBaselinesUseOneInjectedClockAndDoNotDiffTheCurrentWindowAgainstItself() = runTest {
        val key = KEY
        val previous = testSnapshot().copy(timestamp = AT.minusSeconds(1000), neighborSnapshots = SnapshotList.of(NeighborSnapshotEntry(Bytes.of(128), 1.0, 1)))
        val sparse = previous.copy(id = UUID.randomUUID(), timestamp = AT.minusSeconds(500), neighborSnapshots = null, uptimeSeconds = null)
        val current = previous.copy(id = UUID.randomUUID(), timestamp = AT, neighborSnapshots = SnapshotList.of(NeighborSnapshotEntry(Bytes.of(255), 2.0, 0)))
        val store = SnapshotReadDouble(SnapshotList.of(previous, sparse, current))
        val baseline = store.fetchNeighborBaseline(key, Clock.fixed(AT.plusSeconds(30), ZoneOffset.UTC))
        assertEquals(previous, baseline.previous); assertEquals(setOf(Bytes.of(128)), baseline.seenPrefixes); assertEquals(1, store.fetches)
        assertEquals(previous, store.fetchPreviousStatusSnapshot(key, AT))
        store.failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("history"))
        assertFailsWith<PersistenceStoreException> { store.fetchNeighborBaseline(key, Clock.fixed(AT, ZoneOffset.UTC)) }
    }

    private class SnapshotReadDouble(private val rows: SnapshotList<NodeStatusSnapshotDTO>) : NodeSnapshotPersisting {
        var fetches = 0
        var failure: PersistenceStoreException? = null
        override suspend fun fetchNodeStatusSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO> {
            failure?.let { throw it }; fetches++; return rows
        }
        override suspend fun fetchLatestNodeStatusSnapshot(nodePublicKey: Bytes): NodeStatusSnapshotDTO? = error("Not used by these baseline assertions")
        override suspend fun saveNodeStatusSnapshot(nodePublicKey: Bytes, batteryMillivolts: UShort?, lastSNR: Double?, lastRSSI: Short?, noiseFloor: Short?, uptimeSeconds: UInt?, rxAirtimeSeconds: UInt?, packetsSent: UInt?, packetsReceived: UInt?, receiveErrors: UInt?, postedCount: UShort?, postPushCount: UShort?): UUID = error("Read-only test double")
        override suspend fun saveNodeStatusSnapshot(nodePublicKey: Bytes, status: NodeStatusMetrics): UUID = error("Read-only test double")
        override suspend fun updateSnapshotNeighbors(id: UUID, neighbors: SnapshotList<NeighborSnapshotEntry>) = error("Read-only test double")
        override suspend fun updateSnapshotTelemetry(id: UUID, telemetry: SnapshotList<TelemetrySnapshotEntry>) = error("Read-only test double")
        override suspend fun saveTelemetryOnlySnapshot(nodePublicKey: Bytes, telemetryEntries: SnapshotList<TelemetrySnapshotEntry>): UUID = error("Read-only test double")
        override suspend fun recordNodeStatusSnapshot(nodePublicKey: Bytes, status: NodeStatusMetrics?, telemetry: SnapshotList<TelemetrySnapshotEntry>?, neighbors: SnapshotList<NeighborSnapshotEntry>?, location: NodeLocationFix?): UUID = error("Read-only test double")
        override suspend fun deleteOldNodeStatusSnapshots(olderThan: Instant) = error("Read-only test double")
    }
}
