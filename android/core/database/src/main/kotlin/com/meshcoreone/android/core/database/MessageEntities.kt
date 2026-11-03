// PortedFrom: MC1Services/Sources/MC1Services/Models/Message.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/MessageRepeat.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/Reaction.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/PendingSend.swift@db14559b39d32322b06477c6ae676112f583db50
// Composite child identities prevent equal IDs from crossing radio partitions.
package com.meshcoreone.android.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

@Entity(
    tableName = "messages", primaryKeys = ["radioId", "id"],
    indices = [
        Index(value = ["radioId", "channelIndex", "createdAt_seconds", "createdAt_nanos"]),
        Index(value = ["radioId", "channelIndex", "sortDate_seconds", "sortDate_nanos"]),
        Index(value = ["radioId", "channelIndex", "timestamp"]),
        Index(value = ["radioId", "contactID", "createdAt_seconds", "createdAt_nanos"]),
        Index(value = ["radioId", "contactID", "sortDate_seconds", "sortDate_nanos"]),
        Index(value = ["radioId", "contactID", "containsSelfMention", "mentionSeen"]),
        Index(value = ["radioId", "channelIndex", "containsSelfMention", "mentionSeen"]),
        Index(value = ["radioId", "deduplicationKey"]),
    ],
)
data class MessageEntity(
    val radioId: UUID,
    val id: UUID,
    val contactID: UUID?,
    val channelIndex: Long?,
    val text: String,
    val timestamp: Long,
    @Embedded(prefix = "createdAt_") val createdAt: StoredInstant,
    @Embedded(prefix = "sortDate_") val sortDate: StoredInstant,
    val directionRawValue: Long,
    val statusRawValue: Long,
    val textTypeRawValue: Long,
    val ackCode: Long?,
    val pathLength: Long,
    val snr: Double?,
    val pathNodes: Bytes?,
    val senderKeyPrefix: Bytes?,
    val senderNodeName: String?,
    val isRead: Boolean,
    val replyToID: UUID?,
    val roundTripTime: Long?,
    val heardRepeats: Long,
    val sendCount: Long,
    val retryAttempt: Long,
    val maxRetryAttempts: Long,
    val deduplicationKey: String?,
    val linkPreviewURL: String?,
    val linkPreviewTitle: String?,
    val linkPreviewImageData: Bytes?,
    val linkPreviewIconData: Bytes?,
    val linkPreviewFetched: Boolean,
    val containsSelfMention: Boolean,
    val mentionSeen: Boolean,
    val failureSeen: Boolean,
    val timestampCorrected: Boolean,
    val senderTimestamp: Long?,
    val reactionSummary: String?,
    val routeTypeRawValue: Long,
    val regionScope: String?,
    val regionScopeMatches: SnapshotList<String>,
    val channelOccupantID: UUID? = null,
)

@Entity(
    tableName = "message_repeats", primaryKeys = ["radioId", "id"],
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class, parentColumns = ["radioId", "id"],
        childColumns = ["radioId", "parentMessageID"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [
        Index(value = ["radioId", "messageID", "receivedAt_seconds", "receivedAt_nanos"]),
        Index(value = ["radioId", "rxLogEntryID"]), Index(value = ["radioId", "parentMessageID"]),
    ],
)
data class MessageRepeatEntity(
    val radioId: UUID,
    val id: UUID,
    val messageID: UUID,
    val parentMessageID: UUID?,
    @Embedded(prefix = "receivedAt_") val receivedAt: StoredInstant,
    val pathNodes: Bytes,
    val pathLength: Long,
    val snr: Double?,
    val rssi: Long?,
    val rxLogEntryID: UUID?,
)

@Entity(
    tableName = "reactions", primaryKeys = ["radioId", "id"],
    indices = [
        Index(value = ["radioId", "messageID"]),
        Index(value = ["radioId", "contactID", "messageID"]),
        Index(value = ["radioId", "messageID", "senderName", "emoji"]),
    ],
)
data class ReactionEntity(
    val radioId: UUID, val id: UUID, val messageID: UUID, val emoji: String, val senderName: String,
    val messageHash: String, val rawText: String, @Embedded(prefix = "receivedAt_") val receivedAt: StoredInstant,
    val channelIndex: Long?, val contactID: UUID?,
)

@Entity(
    tableName = "pending_sends", primaryKeys = ["radioId", "id"],
    indices = [Index(value = ["radioId", "sequence"]), Index(value = ["radioId", "messageID"])],
)
data class PendingSendEntity(
    val radioId: UUID, val id: UUID, val messageID: UUID, val kindRawValue: Long,
    val contactID: UUID?, val channelIndex: Long?, val isResend: Boolean, val messageText: String,
    val messageTimestamp: Long, val localNodeName: String?, val sequence: Long,
    @Embedded(prefix = "enqueuedAt_") val enqueuedAt: StoredInstant, val attemptCount: Long?,
    val channelOccupantID: UUID? = null,
)
