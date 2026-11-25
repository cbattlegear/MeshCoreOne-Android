// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageServiceConfig.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatSendQueueService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.messaging

data class PoolBackoffConfig(
    val attemptCap: Long = 3,
    val baseDelay: Double = 0.5,
    val exponentBase: Double = 2.0,
    val jitterRange: ClosedFloatingPointRange<Double> = 0.8..1.2,
) {
    init {
        require(attemptCap >= 0)
        require(baseDelay.isFinite() && baseDelay >= 0)
        require(exponentBase.isFinite() && exponentBase >= 0)
        require(jitterRange.start.isFinite() && jitterRange.endInclusive.isFinite() &&
            jitterRange.start >= 0 && jitterRange.start <= jitterRange.endInclusive)
    }
}

data class MessageServiceConfig(
    val floodFallbackOnRetry: Boolean = true,
    val maxAttempts: Long = 5,
    val maxFloodAttempts: Long = 1,
    val floodAfter: Long = 4,
    val minTimeout: Double = 0.0,
    val triggerPathDiscoveryAfterFlood: Boolean = true,
    val ackGiveUpWindow: Double = 30.0,
    val poolBackoff: PoolBackoffConfig = PoolBackoffConfig(),
) {
    init {
        require(maxAttempts in 0..5) { "maxAttempts must be <= 5 (4 direct + 1 flood)" }
        require(maxFloodAttempts >= 0 && floodAfter >= 0)
        require(minTimeout.isFinite() && minTimeout >= 0)
        require(ackGiveUpWindow.isFinite() && ackGiveUpWindow >= 0)
    }
}

data class ChatSendQueueConfig(
    val transportWaitTimeout: Double = 30.0,
    val disambiguateAfterAttempts: Long = 3,
    val maxConsecutiveFetchChannelFailures: Long = 16,
) {
    init {
        require(transportWaitTimeout.isFinite() && transportWaitTimeout > 0)
        require(disambiguateAfterAttempts >= 0 && maxConsecutiveFetchChannelFailures > 0)
    }
}
