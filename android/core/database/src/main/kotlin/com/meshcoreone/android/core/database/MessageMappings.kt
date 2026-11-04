// PortedFrom: MC1Services/Sources/MC1Services/Models/Message.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/MessageRepeat.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/Reaction.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/PendingSend.swift@db14559b39d32322b06477c6ae676112f583db50
// Checked snapshots and explicit, optional relationship linkage.
package com.meshcoreone.android.core.database

import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID

fun MessageDTO.toEntity(): MessageEntity = messageEntity(
    if (timestamp > 0u) timestamp.toLong() else createdAt.truncatedUnixSeconds().uint("message.timestamp").toLong(),
)

/** Caller-ID producers already supply the wire timestamp; zero is a value, not a missing timestamp. */
fun MessageDTO.toEntityPreservingTimestamp(): MessageEntity = messageEntity(timestamp.toLong())

private fun MessageDTO.messageEntity(storedTimestamp: Long): MessageEntity = MessageEntity(
    radioId.value, id, contactID, channelIndex?.toLong(), text, storedTimestamp,
    StoredInstant.from(createdAt), StoredInstant.from(sortDate), direction.rawValue, status.rawValue, textType.rawValue.toLong(),
    ackCode?.toLong(), pathLength.toLong(), snr, pathNodes, senderKeyPrefix, senderNodeName, isRead, replyToID, roundTripTime?.toLong(),
    heardRepeats, sendCount, retryAttempt, maxRetryAttempts, deduplicationKey, linkPreviewURL, linkPreviewTitle,
    null, null, false, containsSelfMention, mentionSeen, failureSeen, timestampCorrected, senderTimestamp?.toLong(),
    reactionSummary, routeType?.rawValue?.toLong() ?: -1, regionScope, regionScopeMatches,
)

private fun Instant.truncatedUnixSeconds(): Long =
    if (epochSecond < 0 && nano > 0) epochSecond + 1 else epochSecond

fun MessageEntity.toDTO(includeLinkPreviewBlobs: Boolean = true): MessageDTO = MessageDTO(
    id, RadioId(radioId), contactID, channelIndex?.ubyte("message.channelIndex"), text, timestamp.uint("message.timestamp"),
    createdAt.toInstant(), sortDate.toInstant(),
    MessageDirection.fromRawValue(directionRawValue) ?: throw DatabaseValueException("message.direction", "Unknown raw value $directionRawValue"),
    MessageStatus.fromRawValue(statusRawValue) ?: throw DatabaseValueException("message.status", "Unknown raw value $statusRawValue"),
    TextType.fromRawValue(textTypeRawValue.ubyte("message.textType")) ?: throw DatabaseValueException("message.textType", "Unknown raw value $textTypeRawValue"),
    ackCode?.uint("message.ackCode"), pathLength.ubyte("message.pathLength"), snr, pathNodes, senderKeyPrefix, senderNodeName,
    isRead, replyToID, roundTripTime?.uint("message.roundTripTime"), heardRepeats, sendCount, retryAttempt, maxRetryAttempts,
    deduplicationKey, linkPreviewURL, linkPreviewTitle, if (includeLinkPreviewBlobs) linkPreviewImageData else null,
    if (includeLinkPreviewBlobs) linkPreviewIconData else null, includeLinkPreviewBlobs && linkPreviewFetched,
    containsSelfMention, mentionSeen, failureSeen, timestampCorrected, senderTimestamp?.uint("message.senderTimestamp"), reactionSummary,
    routeTypeRawValue.takeIf { it in 0L..255L }?.let { RouteType.fromRawValue(it.toUByte()) }, regionScope, regionScopeMatches,
)

fun MessageRepeatDTO.toEntity(radioId: RadioId, parentMessageID: UUID?): MessageRepeatEntity {
    require(parentMessageID == null || parentMessageID == messageID) { "Repeat relationship must match its message ID" }
    return MessageRepeatEntity(
        radioId.value, id, messageID, parentMessageID, StoredInstant.from(receivedAt), pathNodes, pathLength.toLong(), snr, rssi, rxLogEntryID,
    )
}
fun MessageRepeatEntity.toDTO(): MessageRepeatDTO =
    MessageRepeatDTO(id, messageID, receivedAt.toInstant(), pathNodes, pathLength.ubyte("repeat.pathLength"), snr, rssi, rxLogEntryID)

fun ReactionDTO.toEntity(): ReactionEntity =
    ReactionEntity(radioId.value, id, messageID, emoji, senderName, messageHash, rawText, StoredInstant.from(receivedAt), channelIndex?.toLong(), contactID)
fun ReactionEntity.toDTO(): ReactionDTO =
    ReactionDTO(id, messageID, emoji, senderName, messageHash, rawText, receivedAt.toInstant(), channelIndex?.ubyte("reaction.channelIndex"), contactID, RadioId(radioId))

fun PendingSendDTO.toEntity(): PendingSendEntity = PendingSendEntity(
    radioId.value, id, messageID, kind.rawValue, contactID, channelIndex?.toLong(), isResend, messageText,
    messageTimestamp.toLong(), localNodeName, sequence, StoredInstant.from(enqueuedAt), attemptCount,
)
fun PendingSendEntity.toDTO(): PendingSendDTO = PendingSendDTO(
    id, RadioId(radioId), messageID,
    PendingSendKind.fromRawValue(kindRawValue) ?: throw DatabaseValueException("pendingSend.kind", "Unknown raw value $kindRawValue"),
    contactID, channelIndex?.ubyte("pendingSend.channelIndex"), isResend, messageText, messageTimestamp.uint("pendingSend.messageTimestamp"),
    localNodeName, sequence, enqueuedAt.toInstant(), attemptCount,
)
