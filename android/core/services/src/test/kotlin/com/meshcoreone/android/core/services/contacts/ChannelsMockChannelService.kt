// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockChannelService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ChannelSyncResult
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList

/**
 * Test double for [ChannelServiceProtocol]: configure the stubbed results before calling, then
 * inspect the recorded invocations. State is confined under a lock as the source actor's was.
 */
internal class ChannelsMockChannelService : ChannelServiceProtocol {
    data class SyncChannelsInvocation(val radioId: RadioId, val maxChannels: UByte, val usePipelinedRead: Boolean)
    data class RetryInvocation(val radioId: RadioId, val indices: List<UByte>)

    private val lock = Any()
    private var syncInvocations: List<SyncChannelsInvocation> = emptyList()
    private var retries: List<RetryInvocation> = emptyList()

    /** Result returned (or thrown) by `syncChannels`. */
    @Volatile var stubbedSyncChannelsResult: Result<ChannelSyncResult> = Result.success(ChannelSyncResult(0, SnapshotList.empty()))

    /** Result returned (or thrown) by `retryFailedChannels`. */
    @Volatile var stubbedRetryResult: Result<ChannelSyncResult> = Result.success(ChannelSyncResult(0, SnapshotList.empty()))

    val syncChannelsInvocations: List<SyncChannelsInvocation> get() = synchronized(lock) { syncInvocations }
    val retryInvocations: List<RetryInvocation> get() = synchronized(lock) { retries }

    override suspend fun syncChannels(radioId: RadioId, maxChannels: UByte, usePipelinedRead: Boolean): ChannelSyncResult {
        synchronized(lock) { syncInvocations = syncInvocations + SyncChannelsInvocation(radioId, maxChannels, usePipelinedRead) }
        return stubbedSyncChannelsResult.getOrThrow()
    }

    override suspend fun retryFailedChannels(radioId: RadioId, indices: SnapshotList<UByte>): ChannelSyncResult {
        synchronized(lock) { retries = retries + RetryInvocation(radioId, indices.toList()) }
        return stubbedRetryResult.getOrThrow()
    }

    /** Resets all recorded invocations. */
    fun reset() = synchronized(lock) {
        syncInvocations = emptyList()
        retries = emptyList()
    }
}
