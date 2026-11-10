// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockMeshCoreSession.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.session.ChannelFetchResult
import com.meshcoreone.android.core.protocol.session.ChannelSessionOps

/**
 * Channel-role subset of the source `MockMeshCoreSession`: stubbed channels (unstubbed slots
 * answer empty), a stubbed get/set error, and recorded invocations. Adds per-index scripted
 * outcomes and a scripted `getChannels` split so service tests can model dropped pipeline
 * writes independently of the protocol session's correlation policy.
 */
internal class ChannelsMockSession : ChannelSessionOps {
    data class SetChannelInvocation(val index: UByte, val name: String, val secret: Bytes)

    private val lock = Any()
    private var stubbedChannels: Map<UByte, ChannelInfo> = emptyMap()
    private var scripted: Map<UByte, List<suspend () -> ChannelInfo>> = emptyMap()
    private var getChannelIndices: List<UByte> = emptyList()
    private var setChannelInvocations: List<SetChannelInvocation> = emptyList()

    @Volatile var stubbedGetChannelError: Exception? = null
    @Volatile var stubbedSetChannelError: Exception? = null
    var beforeSetChannel: suspend () -> Unit = {}

    /** When set, `getChannels` returns these missing indices instead of answering them. */
    @Volatile var pipelineMissing: Set<UByte> = emptySet()

    val recordedGetChannelIndices: List<UByte> get() = synchronized(lock) { getChannelIndices }
    val recordedSetChannels: List<SetChannelInvocation> get() = synchronized(lock) { setChannelInvocations }

    fun setStubbedChannels(channels: Map<UByte, ChannelInfo>) = synchronized(lock) { stubbedChannels = channels }

    /** Queues outcomes for successive `getChannel(index)` calls before falling back to the stubs. */
    fun script(index: UByte, vararg outcomes: suspend () -> ChannelInfo) = synchronized(lock) {
        scripted = scripted + (index to (scripted[index].orEmpty() + outcomes))
    }

    override suspend fun getChannel(index: UByte): ChannelInfo {
        val next = synchronized(lock) {
            getChannelIndices = getChannelIndices + index
            val queue = scripted[index].orEmpty()
            if (queue.isEmpty()) null else queue.first().also { scripted = scripted + (index to queue.drop(1)) }
        }
        if (next != null) return next()
        stubbedGetChannelError?.let { throw it }
        return stubbedOrEmpty(index)
    }

    override suspend fun getChannels(indices: List<UByte>): ChannelFetchResult {
        synchronized(lock) { getChannelIndices = getChannelIndices + indices }
        stubbedGetChannelError?.let { throw it }
        val missing = pipelineMissing
        return ChannelFetchResult(indices.filter { it !in missing }.map(::stubbedOrEmpty), indices.filter { it in missing })
    }

    override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
        synchronized(lock) { setChannelInvocations = setChannelInvocations + SetChannelInvocation(index, name, secret) }
        beforeSetChannel()
        stubbedSetChannelError?.let { throw it }
    }

    private fun stubbedOrEmpty(index: UByte): ChannelInfo =
        synchronized(lock) { stubbedChannels[index] } ?: ChannelInfo(index, "", Bytes(ByteArray(16)))
}
