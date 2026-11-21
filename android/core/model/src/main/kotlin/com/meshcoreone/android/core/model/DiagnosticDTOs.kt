// PortedFrom: MC1Services/Sources/MC1Services/Models/DebugLogEntry.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/LinkPreviewData.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/DTOs/LinkPreviewDataDTO.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/BlockedChannelSender.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/Reaction.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Connection/RemoveUnfavoritedResult.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/MC1Services.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID

data class DebugLogEntryDTO private constructor(
    val id: UUID, val timestamp: Instant, val level: DebugLogLevel,
    val subsystem: String, val category: String, val message: String,
) {
    companion object {
        fun create(
            level: DebugLogLevel, subsystem: String, category: String, message: String,
            id: UUID = UUID.randomUUID(), timestamp: Instant = Instant.now(),
        ): DebugLogEntryDTO = DebugLogEntryDTO(id, timestamp, level, subsystem, category, message.graphemePrefix(4000))
        fun fromStored(
            id: UUID, timestamp: Instant, level: DebugLogLevel, subsystem: String, category: String, message: String,
        ): DebugLogEntryDTO = DebugLogEntryDTO(id, timestamp, level, subsystem, category, message)
    }
}

data class LinkPreviewDataDTO(
    val url: String,
    val title: String? = null,
    val imageData: Bytes? = null,
    val iconData: Bytes? = null,
    val imageWidth: Long? = null,
    val imageHeight: Long? = null,
    val fetchedAt: Instant = Instant.now(),
) { val id: String get() = url }

data class BlockedChannelSenderDTO(
    val id: UUID = UUID.randomUUID(), val name: String, val radioId: RadioId, val dateBlocked: Instant = Instant.now(),
)

data class ReactionDTO(
    val id: UUID = UUID.randomUUID(),
    val messageID: UUID,
    val emoji: String,
    val senderName: String,
    val messageHash: String,
    val rawText: String,
    val receivedAt: Instant = Instant.now(),
    val channelIndex: UByte? = null,
    val contactID: UUID? = null,
    val radioId: RadioId,
    /** Storage-issued comparison value from the target read, never backed up or persisted as input. */
    val expectedTargetIncarnation: String? = null,
    /** Incoming-consumer intent only; not backed up or returned as stored reaction data. */
    val retainNotification: Boolean = false,
)

data class RemoveUnfavoritedResult(val removed: Long, val total: Long)
object MC1ServicesVersion { const val VERSION = "0.1.0" }
