// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Messages.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Contacts.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/ReactionParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/MeshCoreOpenReactionParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/InboundHopAdoption.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Extensions/Character+EmojiDetection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Utilities/DeduplicationKey.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import android.icu.lang.UCharacter
import android.icu.lang.UProperty
import android.icu.text.BreakIterator
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.sha256
import java.time.Instant
import java.util.Locale
import java.util.UUID

internal fun invalidData(reason: String): Nothing =
    throw PersistenceStoreException(PersistenceStoreError.InvalidData, IllegalArgumentException(reason))

internal fun checkedIncrement(value: Long): Long = try {
    Math.incrementExact(value)
} catch (cause: ArithmeticException) {
    throw PersistenceStoreException(PersistenceStoreError.InvalidData, cause)
}

internal fun checkedAdd(a: Long, b: Long): Long = try {
    Math.addExact(a, b)
} catch (cause: ArithmeticException) {
    throw PersistenceStoreException(PersistenceStoreError.InvalidData, cause)
}

internal fun decrementedUnread(value: Long): Long = try {
    maxOf(0, Math.subtractExact(value, 1))
} catch (cause: ArithmeticException) {
    throw PersistenceStoreException(PersistenceStoreError.InvalidData, cause)
}

internal fun queryBounds(limit: Long, offset: Long = 0) {
    if (limit < 0 || offset < 0) invalidData("Query limit and offset must be nonnegative")
}

internal fun Instant.truncatedUnixUIntOrNull(): UInt? {
    val seconds = if (epochSecond < 0 && nano > 0) epochSecond + 1 else epochSecond
    return seconds.takeIf { it in 0..0xFFFF_FFFFL }?.toUInt()
}

internal fun Instant.truncatedUnixUInt(): UInt =
    truncatedUnixUIntOrNull() ?: invalidData("Phone-clock seconds do not fit UInt32")

internal fun clampedPhoneClockTimestamp(stamp: UInt, now: Instant): UInt =
    minOf(stamp.toLong(), minOf(UInt.MAX_VALUE.toLong(), now.truncatedUnixUInt().toLong() + 300)).toUInt()

internal fun Bytes.startsWith(prefix: Bytes): Boolean =
    size >= prefix.size && slice(0, prefix.size) == prefix

internal object RepositoryDeduplicationKey {
    fun contentBased(
        contactID: UUID?, channelIndex: UByte?, senderNodeName: String?, timestamp: UInt, content: String,
    ): String = com.meshcoreone.android.core.model.DeduplicationKey.contentBased(
        contactID, channelIndex, senderNodeName, timestamp, content,
    )
}

internal data class InboundHop(val count: Long, val timestamp: UInt?)

internal fun adoptInboundHop(
    storedHops: Long?,
    storedTimestamp: UInt?,
    incomingHops: Long,
    incomingTimestamp: UInt?,
): InboundHop? = when {
    storedTimestamp == null -> InboundHop(incomingHops, incomingTimestamp)
    incomingTimestamp == null -> null
    incomingTimestamp > storedTimestamp -> InboundHop(incomingHops, incomingTimestamp)
    incomingTimestamp == storedTimestamp && incomingHops < (storedHops ?: Long.MAX_VALUE) ->
        InboundHop(incomingHops, incomingTimestamp)
    else -> null
}

internal object RepositoryReactionPolicy {
    private const val CROCKFORD = "0123456789abcdefghjkmnpqrstvwxyz"
    private const val MCO_EMOJI_COUNT = 184

    fun messageHash(text: String, timestamp: UInt): String {
        val input = ByteWriter().append(Bytes.utf8(text)).appendUInt32LE(timestamp).toBytes()
        var bits = 0L
        for (byte in sha256(input).prefix(5)) bits = (bits shl 8) or byte.toLong()
        return buildString(8) {
            for (shift in 35 downTo 0 step 5) append(CROCKFORD[((bits ushr shift) and 31).toInt()])
        }
    }

    fun isDirectReaction(text: String): Boolean =
        isMcoV3(text) || isMcoV1(text) || isPocketDirect(text)

    private fun isMcoV3(text: String): Boolean {
        if (text.length != 9 || !text.startsWith("r:") || text[6] != ':') return false
        val hash = text.substring(2, 6)
        val index = text.substring(7)
        if (!hash.all { it in '0'..'9' || it in 'a'..'f' || it in '\uFF10'..'\uFF19' || it in '\uFF41'..'\uFF46' }) {
            return false
        }
        if (!index.all { it in '0'..'9' || it in 'a'..'f' }) return false
        return requireNotNull(index.toIntOrNull(16)) < MCO_EMOJI_COUNT
    }

    private fun isMcoV1(text: String): Boolean {
        if (!text.startsWith("r:")) return false
        val colon = text.lastIndexOf(':')
        if (colon <= 2 || colon == text.lastIndex) return false
        val parts = text.substring(2, colon).split('_').filter { it.isNotEmpty() }
        if (parts.size != 3) return false
        val millis = unsignedDecimal(parts[0]) ?: return false
        val sender = unsignedDecimal(parts[1]) ?: return false
        val content = unsignedDecimal(parts[2]) ?: return false
        return millis / 1000u <= UInt.MAX_VALUE.toULong() &&
            sender <= UInt.MAX_VALUE.toULong() && content <= UInt.MAX_VALUE.toULong()
    }

    private fun unsignedDecimal(text: String): ULong? {
        val digits = text.removePrefix("+")
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        return digits.toULongOrNull()
    }

    private fun isPocketDirect(text: String): Boolean {
        if ("@[" in text) return false
        val newline = text.lastIndexOf('\n')
        if (newline < 0) return false
        val hash = text.substring(newline + 1)
        if (hash.length != 8 || !hash.all { it.code < 128 && (it.lowercaseChar() in CROCKFORD || it in "OoIiLl") }) {
            return false
        }
        val emoji = text.substring(0, newline)
        if (emoji.isEmpty()) return false
        val characters = BreakIterator.getCharacterInstance(Locale.ROOT)
        characters.setText(emoji)
        if (characters.first() != 0 || characters.next() != emoji.length || characters.next() != BreakIterator.DONE) {
            return false
        }
        val first = emoji.codePointAt(0)
        return UCharacter.hasBinaryProperty(first, UProperty.EMOJI) &&
            (first > 0x238C || emoji.codePointCount(0, emoji.length) > 1)
    }
}
