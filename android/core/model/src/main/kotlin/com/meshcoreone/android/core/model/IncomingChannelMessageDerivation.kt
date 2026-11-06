// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator+MessageHandlers.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-201 Shared existing WP-214 text/clock adaptations for guarded ingress and pre-write validation.
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.event.ChannelMessage
import java.time.Instant
import java.util.regex.Pattern

data class ParsedChannelMessageText(val senderNodeName: String?, val messageText: String)

class IncomingTimestampRangeError(val receiveTime: Instant) :
    Exception("Receive time $receiveTime is outside UInt32 Unix seconds; retaining the original sender timestamp.")

data class IncomingTimestampCorrection(
    val correctedTimestamp: UInt,
    val wasCorrected: Boolean,
    val rangeError: IncomingTimestampRangeError? = null,
)

/** Derived values, never an authenticated occurrence, clock, issuer, or storage receipt. */
data class IncomingChannelMessageFields(
    val senderNodeName: String?,
    val text: String,
    val originalTimestamp: UInt,
    val timestamp: UInt,
    val wasCorrected: Boolean,
    val senderTimestamp: UInt?,
    val textType: TextType,
    val unknownTextTypeRaw: UByte?,
    val deduplicationKey: String,
    val rangeError: IncomingTimestampRangeError?,
)

enum class IncomingChannelMessageField {
    TEXT, SENDER_NODE_NAME, TIMESTAMP, SENDER_TIMESTAMP, TIMESTAMP_CORRECTED, TEXT_TYPE, DEDUPLICATION_KEY,
}

/**
 * The existing Sync derivation, shared rather than approximated in storage. [derive] uses a declared
 * receive time, not evidence that the radio received anything at that time. No Unicode normalization
 * is applied to sender/body; the existing content-key helper hashes exactly the parsed body.
 */
object IncomingChannelMessageDerivation {
    const val TIMESTAMP_TOLERANCE_FUTURE_SECONDS = 5.0 * 60
    private const val TIMESTAMP_TOLERANCE_PAST_SECONDS = 6.0 * 30 * 24 * 60 * 60
    private val grapheme: Pattern = Pattern.compile("\\X")
    private val foundationWhitespaces: Set<Int> = buildSet {
        add(0x09); add(0x20); add(0xA0); add(0x1680)
        for (code in 0x2000..0x200B) add(code)
        add(0x202F); add(0x205F); add(0x3000)
    }

    fun parse(text: String): ParsedChannelMessageText {
        val parts = splitAtFirstColonCharacter(text)
        return if (parts.size > 1) {
            ParsedChannelMessageText(trimmingWhitespaces(parts[0]), trimmingWhitespaces(parts[1]))
        } else {
            ParsedChannelMessageText(null, text)
        }
    }

    /** Existing native clamp for wire dates; separate from recoverable receive-clock range errors. */
    fun uint32Seconds(date: Instant): UInt {
        val seconds = secondsSinceEpoch(date)
        return when {
            seconds <= 0.0 -> 0u
            seconds >= UInt.MAX_VALUE.toDouble() -> UInt.MAX_VALUE
            else -> seconds.toLong().toUInt()
        }
    }

    fun correctTimestamp(timestamp: UInt, receiveTime: Instant): IncomingTimestampCorrection {
        val receiveSeconds = secondsSinceEpoch(receiveTime)
        val timestampSeconds = timestamp.toDouble()
        val isTooFarInFuture = timestampSeconds > receiveSeconds + TIMESTAMP_TOLERANCE_FUTURE_SECONDS
        val isTooFarInPast = timestampSeconds < receiveSeconds - TIMESTAMP_TOLERANCE_PAST_SECONDS
        if (isTooFarInFuture || isTooFarInPast) {
            // Preserve the existing exact truncation check at the UInt32 receive-clock boundary.
            val truncatedSeconds = receiveTime.epochSecond +
                if (receiveTime.epochSecond < 0 && receiveTime.nano > 0) 1L else 0L
            if (truncatedSeconds !in 0L..UInt.MAX_VALUE.toLong()) {
                return IncomingTimestampCorrection(timestamp, false, IncomingTimestampRangeError(receiveTime))
            }
            return IncomingTimestampCorrection(truncatedSeconds.toUInt(), true)
        }
        return IncomingTimestampCorrection(timestamp, false)
    }

    fun derive(wire: ChannelMessage, receiveTime: Instant): IncomingChannelMessageFields {
        val parsed = parse(wire.text)
        val originalTimestamp = uint32Seconds(wire.senderTimestamp)
        val correction = correctTimestamp(originalTimestamp, receiveTime)
        val recognizedType = TextType.fromRawValue(wire.textType)
        return IncomingChannelMessageFields(
            senderNodeName = parsed.senderNodeName,
            text = parsed.messageText,
            originalTimestamp = originalTimestamp,
            timestamp = correction.correctedTimestamp,
            wasCorrected = correction.wasCorrected,
            senderTimestamp = if (correction.wasCorrected) originalTimestamp else null,
            textType = recognizedType ?: TextType.PLAIN,
            unknownTextTypeRaw = if (recognizedType == null) wire.textType else null,
            deduplicationKey = DeduplicationKey.contentBased(
                null, wire.channelIndex, parsed.senderNodeName, originalTimestamp, parsed.messageText,
            ),
            rangeError = correction.rangeError,
        )
    }

    /**
     * Compare actual wire-derived fields using [MessageDTO.createdAt] as the declared receive time.
     * This is not a storage policy or proof: callers separately validate radio/slot, incoming status,
     * context sort date, unset RX metadata, and wire path/SNR before any DML.
     */
    fun mismatches(wire: ChannelMessage, message: MessageDTO): SnapshotList<IncomingChannelMessageField> {
        val expected = derive(wire, message.createdAt)
        return buildList {
            if (message.text != expected.text) add(IncomingChannelMessageField.TEXT)
            if (message.senderNodeName != expected.senderNodeName) add(IncomingChannelMessageField.SENDER_NODE_NAME)
            if (message.timestamp != expected.timestamp) add(IncomingChannelMessageField.TIMESTAMP)
            if (message.senderTimestamp != expected.senderTimestamp) add(IncomingChannelMessageField.SENDER_TIMESTAMP)
            if (message.timestampCorrected != expected.wasCorrected) add(IncomingChannelMessageField.TIMESTAMP_CORRECTED)
            if (message.textType != expected.textType) add(IncomingChannelMessageField.TEXT_TYPE)
            if (message.deduplicationKey != expected.deduplicationKey) add(IncomingChannelMessageField.DEDUPLICATION_KEY)
        }.snapshot()
    }

    private fun secondsSinceEpoch(date: Instant): Double =
        date.epochSecond.toDouble() + date.nano / 1_000_000_000.0

    /** Swift split(maxSplits: 1) counts only nonempty pieces and walks Character graphemes. */
    private fun splitAtFirstColonCharacter(text: String): List<String> {
        val matcher = grapheme.matcher(text)
        val characters = ArrayList<String>()
        while (matcher.find()) characters += matcher.group()
        val result = ArrayList<String>()
        val current = StringBuilder()
        var index = 0
        while (index < characters.size) {
            val character = characters[index]
            index += 1
            if (character != ":") {
                current.append(character)
                continue
            }
            if (current.isNotEmpty()) {
                result += current.toString()
                current.setLength(0)
                break
            }
        }
        while (index < characters.size) current.append(characters[index++])
        if (current.isNotEmpty()) result += current.toString()
        return result
    }

    private fun trimmingWhitespaces(text: String): String {
        var start = 0
        var end = text.length
        while (start < end) {
            val code = text.codePointAt(start)
            if (code !in foundationWhitespaces) break
            start += Character.charCount(code)
        }
        while (end > start) {
            val code = text.codePointBefore(end)
            if (code !in foundationWhitespaces) break
            end -= Character.charCount(code)
        }
        return text.substring(start, end)
    }
}
