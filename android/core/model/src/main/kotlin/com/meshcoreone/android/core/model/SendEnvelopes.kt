// PortedFrom: MC1Services/Sources/MC1Services/Models/DirectMessageEnvelope.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/ChannelMessageEnvelope.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/PendingSend.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/PendingSendEnvelope.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/DeliveryContext.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import java.time.Instant
import java.util.UUID

data class DirectMessageEnvelope(val messageID: UUID, val contactID: UUID, val isResend: Boolean = false)

data class ChannelMessageEnvelope(
    val messageID: UUID, val channelIndex: UByte, val isResend: Boolean,
    val messageText: String, val messageTimestamp: UInt, val localNodeName: String?,
    val channelOccupantID: UUID? = null,
)

enum class PendingSendKind(val rawValue: Long) {
    DM(0), CHANNEL(1);
    companion object {
        fun fromRawValue(value: Long): PendingSendKind? = entries.firstOrNull { it.rawValue == value }
    }
}

data class PendingSendDTO(
    val id: UUID,
    val radioId: RadioId,
    val messageID: UUID,
    val kind: PendingSendKind,
    val contactID: UUID?,
    val channelIndex: UByte?,
    val isResend: Boolean,
    val messageText: String,
    val messageTimestamp: UInt,
    val localNodeName: String?,
    val sequence: Long,
    val enqueuedAt: Instant,
    val attemptCount: Long? = 0,
    val channelOccupantID: UUID? = null,
    val channelDispatchState: QueuedChannelDispatchState? = null,
    val channelAttemptID: UUID? = null,
) {
    fun directMessageEnvelope(): DirectMessageEnvelope? =
        if (kind == PendingSendKind.DM && contactID != null) DirectMessageEnvelope(messageID, contactID, isResend) else null
    fun channelMessageEnvelope(): ChannelMessageEnvelope? =
        if (kind == PendingSendKind.CHANNEL && channelIndex != null) {
            ChannelMessageEnvelope(messageID, channelIndex, isResend, messageText, messageTimestamp, localNodeName, channelOccupantID)
        } else null

    companion object {
        fun fromEnvelope(
            envelope: DirectMessageEnvelope, radioId: RadioId,
            id: UUID = UUID.randomUUID(), enqueuedAt: Instant = Instant.now(),
        ): PendingSendDTO = PendingSendDTO(
            id, radioId, envelope.messageID, PendingSendKind.DM, envelope.contactID, null,
            envelope.isResend, "", 0u, null, 0, enqueuedAt,
        )
        fun fromEnvelope(
            envelope: ChannelMessageEnvelope, radioId: RadioId,
            id: UUID = UUID.randomUUID(), enqueuedAt: Instant = Instant.now(),
        ): PendingSendDTO = PendingSendDTO(
            id, radioId, envelope.messageID, PendingSendKind.CHANNEL, null, envelope.channelIndex,
            envelope.isResend, envelope.messageText, envelope.messageTimestamp, envelope.localNodeName, 0, enqueuedAt,
            channelOccupantID = envelope.channelOccupantID,
        )
    }
}

sealed interface DeliveryContext {
    data class InitialSync(val anchor: Instant) : DeliveryContext
    data object Live : DeliveryContext
}
