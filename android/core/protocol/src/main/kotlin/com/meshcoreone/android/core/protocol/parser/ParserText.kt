// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Parsers+Messaging.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: Foundation character sets and Unicode maximal-subpart UTF-8 replacement on the JVM.
package com.meshcoreone.android.core.protocol.parser

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.logging.Logger

internal val parserLogger: Logger = Logger.getLogger("MeshCore.Parsers")

internal fun Bytes.exactUtf8(): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(toByteArray())).toString()
} catch (_: CharacterCodingException) {
    null
}

internal fun Bytes.exactUtf8OrEmpty(field: String): String = exactUtf8() ?: run {
    parserLogger.warning("$field: Invalid UTF-8; preserving the source empty-string fallback")
    ""
}

internal fun Bytes.lossyUtf8(field: String): String {
    exactUtf8()?.let { return it }
    parserLogger.warning("$field: Invalid UTF-8; using lossy conversion")
    // JDK decoding collapses a UTF-8 surrogate into one replacement; Swift replaces
    // each invalid byte. Consume only the longest potentially valid sequence prefix.
    return buildString {
        var offset = 0
        while (offset < size) {
            val first = this@lossyUtf8[offset].toInt()
            if (first < 0x80) {
                append(first.toChar())
                offset++
                continue
            }
            val width = when (first) {
                in 0xc2..0xdf -> 2
                in 0xe0..0xef -> 3
                in 0xf0..0xf4 -> 4
                else -> 0
            }
            if (width == 0) {
                append('\ufffd')
                offset++
                continue
            }
            var value = first and (0x7f shr width)
            var consumed = 1
            while (consumed < width && offset + consumed < size) {
                val byte = this@lossyUtf8[offset + consumed].toInt()
                val allowed = if (consumed != 1) 0x80..0xbf else when (first) {
                    0xe0 -> 0xa0..0xbf
                    0xed -> 0x80..0x9f
                    0xf0 -> 0x90..0xbf
                    0xf4 -> 0x80..0x8f
                    else -> 0x80..0xbf
                }
                if (byte !in allowed) break
                value = (value shl 6) or (byte and 0x3f)
                consumed++
            }
            if (consumed == width) appendCodePoint(value) else append('\ufffd')
            offset += consumed
        }
    }
}

internal fun Bytes.beforeNull(): Bytes = prefix(indexOf(0.toUByte()).let { if (it < 0) size else it })

internal fun String.trimControls(): String = trimCodePoints {
    Character.getType(it) == Character.CONTROL.toInt() || Character.getType(it) == Character.FORMAT.toInt()
}

internal fun String.trimWhitespaces(): String = trimCodePoints {
    it == 0x09 || Character.getType(it) == Character.SPACE_SEPARATOR.toInt()
}

internal fun String.trimWhitespacesAndNewlines(): String = trimCodePoints {
    it in 0x09..0x0d || it == 0x85 || Character.getType(it) in setOf(
        Character.SPACE_SEPARATOR.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
    )
}

private inline fun String.trimCodePoints(predicate: (Int) -> Boolean): String {
    var start = 0
    var end = length
    while (start < end && predicate(codePointAt(start))) start += Character.charCount(codePointAt(start))
    while (end > start && predicate(codePointBefore(end))) end -= Character.charCount(codePointBefore(end))
    return substring(start, end)
}

internal fun String.splitOmittingEmpty(separator: Char, maxSplits: Int = Int.MAX_VALUE): List<String> {
    val parts = mutableListOf<String>()
    var start = 0
    for (index in indices) {
        if (this[index] != separator) continue
        if (index > start) {
            parts += substring(start, index)
            if (parts.size == maxSplits) {
                if (index + 1 < length) parts += substring(index + 1)
                return parts
            }
        }
        start = index + 1
    }
    if (start < length) parts += substring(start)
    return parts
}
