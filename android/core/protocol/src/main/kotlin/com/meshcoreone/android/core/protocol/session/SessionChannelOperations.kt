// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Channels.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.supervisorScope

internal suspend fun ExchangeOwner.channel(index: UByte): ChannelInfo =
    query(PacketBuilder.getChannel(index), "channel:${index.toInt()}") {
        (it as? MeshEvent.ChannelInfo)?.info?.takeIf { info -> info.index == index }
    }

internal suspend fun SessionCore.getChannels(indices: List<UByte>): ChannelFetchResult {
    val requested = indices.toList()
    if (requested.isEmpty()) return ChannelFetchResult(emptyList(), emptyList())
    return exchange {
        if (!transport.supportsPipelinedReads()) {
            val received = mutableListOf<ChannelInfo>()
            for (index in requested) received += channel(index)
            return@exchange ChannelFetchResult(received, emptyList())
        }
        val unique = requested.distinct()
        unique.forEach { core.checkCorrelation(generation, "channel:${it.toInt()}", acceptsErrors = false) }
        val subscription = core.register(generation) { it is MeshEvent.ChannelInfo }
        val collected = linkedMapOf<UByte, ChannelInfo>()
        val outstanding = linkedSetOf<UByte>()
        val window = configuration.channelPipelineWindow.coerceAtLeast(1).coerceAtMost(unique.size.toLong()).toInt()
        val withoutResponse = transport.supportsWriteWithoutResponse()
        var nextToSend = 0
        val progress = StreamProgressTracker(clock)
        suspend fun sendNext() {
            val index = unique[nextToSend++]
            outstanding += index
            send(PacketBuilder.getChannel(index), withoutResponse = withoutResponse)
        }
        try {
            repeat(window) { sendNext() }
            supervisorScope {
                val consumer = async {
                    for (event in subscription.channel) {
                        val info = (event as MeshEvent.ChannelInfo).info
                        if (info.index !in outstanding || collected.containsKey(info.index)) continue
                        outstanding.remove(info.index)
                        collected[info.index] = info
                        progress.markProgress()
                        if (nextToSend < unique.size) sendNext()
                        if (collected.size == unique.size) break
                    }
                }
                val watchdog = async {
                    val hard = timeoutDuration(configuration.channelPipelineHardTimeout)
                    val idle = timeoutDuration(configuration.channelPipelineIdleTimeout)
                    while (true) {
                        val before = progress.snapshot()
                        if (before.elapsed >= hard) break
                        clock.sleepFor(minOf(idle, hard - before.elapsed))
                        val after = progress.snapshot()
                        if (after.elapsed >= hard || after.generation == before.generation) break
                    }
                    core.unregister(generation, subscription)
                }
                try {
                    select {
                        consumer.onAwait { Unit }
                        watchdog.onAwait { consumer.await() }
                    }
                } finally {
                    consumer.cancel()
                    watchdog.cancel()
                    consumer.cancelAndJoin()
                    watchdog.cancelAndJoin()
                }
            }
            core.requireCurrent(generation)
            val missing = requested.filter { it !in collected }
            if (missing.isEmpty()) clock.sleepFor(timeoutDuration(configuration.channelPipelinePostDrainGrace, allowZero = true))
            ChannelFetchResult(collected.values.sortedBy { it.index }, missing)
        } finally {
            if (core.isCurrent(generation)) outstanding.forEach {
                core.unresolved(generation, "channel:${it.toInt()}", acceptsErrors = false)
            }
            core.unregister(generation, subscription)
        }
    }
}
