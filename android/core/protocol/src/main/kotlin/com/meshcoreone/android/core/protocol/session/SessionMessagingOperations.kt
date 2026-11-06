// PortedFrom: MeshCore/Sources/MeshCore/Session/MeshCoreSession+Messaging.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import java.time.Instant

internal suspend fun SessionCore.sendMessageWithRetry(
    destination: Bytes, text: String, timestamp: Instant, maxAttempts: Long,
    floodAfter: Long, maxFloodAttempts: Long, timeout: Double?,
): MessageSentInfo? {
    if (destination.size < PacketBuilder.PUBLIC_KEY_SIZE) throw MeshCoreException.InvalidInput("Full public key required for retry with path reset")
    if (maxAttempts !in 0..256 || floodAfter < 0 || maxFloodAttempts < 0) throw MeshCoreException.InvalidInput("Retry counters exceed the firmware attempt-byte range")
    timeout?.let { timeoutDuration(it) }
    return exchange {
        var attempts = 0L
        var floodAttempts = 0L
        var flood = false
        while (attempts < maxAttempts && (!flood || floodAttempts < maxFloodAttempts)) {
            if (attempts == floodAfter && !flood) {
                try {
                    requireFullPublicKey(destination, "resetPath")
                    simple(PacketBuilder.resetPath(destination))
                    flood = true
                } catch (failure: MeshCoreException.DeviceError) {
                    core.diagnostic(SessionDiagnostic.BackgroundFailure(generation.number, "retry-path-reset", failure))
                } catch (failure: MeshCoreException.InvalidInput) {
                    core.diagnostic(SessionDiagnostic.BackgroundFailure(generation.number, "retry-path-reset", failure))
                }
            }
            // ACK registration precedes even the send that discovers its code.
            val acknowledgements = core.register(generation) { it is MeshEvent.Acknowledgement }
            var expected: Bytes? = null
            try {
                val info = query(
                    PacketBuilder.sendMessage(destination.prefix(6), text, timestamp, attempts.toUByte()),
                    "messageSent", errorMatcher = ::deviceError,
                ) { (it as? MeshEvent.MessageSent)?.info }
                expected = info.expectedAck
                if (expected in generation.retiredAckCodes) {
                    throw MeshCoreException.ConnectionLost(SessionCorrelationException.ReusedTag(generation.number, expected))
                }
                val seconds = timeout ?: (info.suggestedTimeoutMs.toDouble() / SessionConfiguration.MILLISECONDS_PER_SECOND *
                    SessionConfiguration.RETRY_ACK_TIMEOUT_MULTIPLIER)
                val acknowledged = if (seconds == 0.0) {
                    var found = false
                    while (true) {
                        val event = acknowledgements.channel.tryReceive().getOrNull() ?: break
                        if ((event as MeshEvent.Acknowledgement).code == expected) found = true
                    }
                    found
                } else try {
                    core.clock.withDeadline(seconds) {
                        for (event in acknowledgements.channel) {
                            if ((event as MeshEvent.Acknowledgement).code == expected) return@withDeadline true
                        }
                        throw generation.failure ?: MeshCoreException.ConnectionLost()
                    }
                } catch (_: MeshCoreException.Timeout) {
                    false
                }
                if (acknowledged) return@exchange info
            } finally {
                expected?.let { core.withLock { generation.retiredAckCodes += it } }
                core.unregister(generation, acknowledgements)
            }
            attempts += 1
            if (flood) floodAttempts += 1
        }
        null
    }
}
