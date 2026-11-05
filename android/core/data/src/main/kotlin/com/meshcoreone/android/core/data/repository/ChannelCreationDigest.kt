// AndroidOnly: WP-202 Exact immutable channel creation arguments without retaining payload history.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.time.Instant

internal fun channelCreationDigest(row: MessageDTO): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { output ->
        fun text(value: String?) {
            output.writeInt(value?.length ?: -1)
            if (value != null) output.writeChars(value)
        }
        fun number(value: Long?) {
            output.writeBoolean(value != null)
            if (value != null) output.writeLong(value)
        }
        fun binary(value: Bytes?) {
            output.writeInt(value?.size ?: -1)
            if (value != null) output.write(value.toByteArray())
        }
        fun moment(value: Instant) {
            output.writeLong(value.epochSecond)
            output.writeInt(value.nano)
        }
        output.writeInt(1)
        text(row.radioId.value.toString())
        text(row.id.toString())
        text(row.contactID?.toString())
        number(row.channelIndex?.toLong())
        text(row.text)
        number(row.timestamp.toLong())
        moment(row.createdAt)
        moment(row.sortDate)
        number(row.direction.rawValue)
        number(row.status.rawValue)
        number(row.textType.rawValue.toLong())
        number(row.ackCode?.toLong())
        number(row.pathLength.toLong())
        number(row.snr?.let { java.lang.Double.doubleToLongBits(it) })
        binary(row.pathNodes)
        binary(row.senderKeyPrefix)
        text(row.senderNodeName)
        output.writeBoolean(row.isRead)
        text(row.replyToID?.toString())
        number(row.roundTripTime?.toLong())
        number(row.heardRepeats)
        number(row.sendCount)
        number(row.retryAttempt)
        number(row.maxRetryAttempts)
        text(row.deduplicationKey)
        text(row.linkPreviewURL)
        text(row.linkPreviewTitle)
        binary(row.linkPreviewImageData)
        binary(row.linkPreviewIconData)
        output.writeBoolean(row.linkPreviewFetched)
        output.writeBoolean(row.containsSelfMention)
        output.writeBoolean(row.mentionSeen)
        output.writeBoolean(row.failureSeen)
        output.writeBoolean(row.timestampCorrected)
        number(row.senderTimestamp?.toLong())
        text(row.reactionSummary)
        number(row.routeType?.rawValue?.toLong())
        text(row.regionScope)
        output.writeInt(row.regionScopeMatches.size)
        row.regionScopeMatches.forEach(::text)
    }
    return Bytes(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray())).hexString
}
