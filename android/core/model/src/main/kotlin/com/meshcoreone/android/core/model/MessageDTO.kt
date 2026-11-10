// PortedFrom: MC1Services/Sources/MC1Services/Models/Message.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.decodePathLen
import java.time.Duration
import java.time.Instant
import java.util.UUID

enum class MessageStatus(val rawValue: Long) {
    PENDING(0), SENDING(1), SENT(2), DELIVERED(3), FAILED(4), RETRYING(5);
    companion object {
        fun fromRawValue(value: Long): MessageStatus? = entries.firstOrNull { it.rawValue == value }
    }
}

enum class MessageDirection(val rawValue: Long) {
    INCOMING(0), OUTGOING(1);
    companion object {
        fun fromRawValue(value: Long): MessageDirection? = entries.firstOrNull { it.rawValue == value }
    }
}

data class MessageDTO(
    val id: UUID = UUID.randomUUID(),
    val radioId: RadioId,
    val contactID: UUID? = null,
    val channelIndex: UByte? = null,
    val text: String,
    val timestamp: UInt,
    val createdAt: Instant = Instant.now(),
    val sortDate: Instant = createdAt,
    val direction: MessageDirection = MessageDirection.OUTGOING,
    val status: MessageStatus = MessageStatus.PENDING,
    val textType: TextType = TextType.PLAIN,
    val ackCode: UInt? = null,
    val pathLength: UByte = 0u,
    val snr: Double? = null,
    val pathNodes: Bytes? = null,
    val senderKeyPrefix: Bytes? = null,
    val senderNodeName: String? = null,
    val isRead: Boolean = false,
    val replyToID: UUID? = null,
    val roundTripTime: UInt? = null,
    val heardRepeats: Long = 0,
    val sendCount: Long = 1,
    val retryAttempt: Long = 0,
    val maxRetryAttempts: Long = 0,
    val deduplicationKey: String? = null,
    val linkPreviewURL: String? = null,
    val linkPreviewTitle: String? = null,
    val linkPreviewImageData: Bytes? = null,
    val linkPreviewIconData: Bytes? = null,
    val linkPreviewFetched: Boolean = false,
    val containsSelfMention: Boolean = false,
    val mentionSeen: Boolean = false,
    val failureSeen: Boolean = false,
    val timestampCorrected: Boolean = false,
    val senderTimestamp: UInt? = null,
    val reactionSummary: String? = null,
    val routeType: RouteType? = null,
    val regionScope: String? = null,
    val regionScopeMatches: SnapshotList<String> = SnapshotList.empty(),
    val channelOccupantID: UUID? = null,
) {
    private val fields get() = arrayOf(
        id, radioId, contactID, channelIndex, text, timestamp, createdAt, sortDate, direction, status, textType,
        ackCode, pathLength, snr, pathNodes, senderKeyPrefix, senderNodeName, isRead, replyToID, roundTripTime,
        heardRepeats, sendCount, retryAttempt, maxRetryAttempts, deduplicationKey, linkPreviewURL, linkPreviewTitle,
        linkPreviewImageData, linkPreviewIconData, linkPreviewFetched, containsSelfMention, mentionSeen,
        failureSeen, timestampCorrected, senderTimestamp, reactionSummary, routeType, regionScope, regionScopeMatches,
        channelOccupantID,
    )
    override fun equals(other: Any?): Boolean = other is MessageDTO && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)

    val isOutgoing: Boolean get() = direction == MessageDirection.OUTGOING
    val isChannelMessage: Boolean get() = channelIndex != null
    val reactionTimestamp: UInt get() = senderTimestamp ?: timestamp
    val isPending: Boolean get() = status == MessageStatus.PENDING || status == MessageStatus.SENDING
    val hasFailed: Boolean get() = status == MessageStatus.FAILED
    val date: Instant get() = createdAt
    val senderDate: Instant get() = Instant.ofEpochSecond(timestamp.toLong())
    val wireSentDate: Instant get() = Instant.ofEpochSecond(reactionTimestamp.toLong())
    val hopCount: Long get() = decodePathLen(pathLength)?.hopCount?.toLong() ?: (pathLength.toLong() and 63)
    val isFloodRouted: Boolean
        get() = if (channelIndex != null) true else routeType?.isFlood ?: (pathLength != 255.toUByte())
    val isDirectRouted: Boolean get() = !isFloodRouted
    val pathHashSize: Long get() = decodePathLen(pathLength)?.hashSize?.toLong() ?: 1
    val pathHashSizeIfKnown: Long? get() = decodePathLen(pathLength)?.hashSize?.toLong()
    val pathHops: SnapshotList<PathHop> get() = pathNodes?.pathHops(pathHashSize) ?: SnapshotList.empty()
    val pathNodesHex: SnapshotList<String> get() = pathHops.map { it.hex }.snapshot()
    val pathString: String get() = pathNodesHex.joinToString(" \u2192 ")
    val pathStringForClipboard: String get() = pathNodesHex.joinToString(",")

    fun withoutPreviewBlobs(): MessageDTO =
        copy(linkPreviewImageData = null, linkPreviewIconData = null, linkPreviewFetched = false)

    companion object {
        fun reorderSameSenderClusters(messages: List<MessageDTO>): SnapshotList<MessageDTO> {
            val result = messages.toMutableList()
            var start = 0
            while (start < result.size) {
                var end = start + 1
                while (end < result.size &&
                    sameSender(result[end], result[end - 1]) &&
                    Duration.between(result[end - 1].sortDate, result[end].sortDate) <= Duration.ofSeconds(5)
                ) end++
                if (end - start > 1) {
                    val sorted = result.subList(start, end).sortedWith(compareBy<MessageDTO> { it.timestamp }.thenBy { it.createdAt })
                    sorted.forEachIndexed { offset, message -> result[start + offset] = message }
                }
                start = end
            }
            return result.snapshot()
        }

        private fun sameSender(a: MessageDTO, b: MessageDTO): Boolean {
            if (a.direction != b.direction || a.isChannelMessage != b.isChannelMessage) return false
            if (!a.isChannelMessage) return true
            return a.senderNodeName != null && b.senderNodeName != null && a.senderNodeName == b.senderNodeName
        }
    }
}
