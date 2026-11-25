// PortedFrom: MC1Services/Sources/MC1Services/Extensions/Data+Extensions.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Extensions/Locale+POSIX.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.regex.Pattern
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

val POSIX_LOCALE: Locale = Locale.forLanguageTag("en-US-POSIX")

fun Bytes.uppercaseHexString(separator: String = ""): String =
    joinToString(separator) { it.toString(16).padStart(2, '0').uppercase(Locale.ROOT) }

data class PathHop(val data: Bytes) {
    val hex: String get() = data.uppercaseHexString()
}

fun Bytes.pathHops(hashSize: Long): SnapshotList<PathHop> {
    if (hashSize <= 0) return SnapshotList.empty()
    val hops = mutableListOf<PathHop>()
    var offset = 0
    while (offset < size) {
        val end = offset + minOf(hashSize, (size - offset).toLong()).toInt()
        hops += PathHop(slice(offset, end))
        offset = end
    }
    return hops.snapshot()
}

fun applicationBytesFromHex(text: String): Bytes? {
    val characters = GRAPHEMES.matcher(text)
    val filtered = buildString {
        while (characters.find()) {
            val value = characters.group()
            if (value.length == 1 && value[0].isSourceHexDigit()) append(value)
        }
    }
    if (filtered.length % 2 != 0) return null
    val bytes = ByteArray(filtered.length / 2)
    for (index in bytes.indices) {
        val high = filtered[index * 2].asciiHexDigit() ?: return null
        val low = filtered[index * 2 + 1].asciiHexDigit() ?: return null
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return Bytes(bytes)
}

private fun Char.isSourceHexDigit(): Boolean =
    asciiHexDigit() != null || this in '\uFF10'..'\uFF19' ||
        this in '\uFF21'..'\uFF26' || this in '\uFF41'..'\uFF46'

private fun Char.asciiHexDigit(): Int? = when (this) {
    in '0'..'9' -> this - '0'
    in 'a'..'f' -> this - 'a' + 10
    in 'A'..'F' -> this - 'A' + 10
    else -> null
}

val Bytes.ackCodeUInt32: UInt get() = readUInt32LE(0)

// Foundation's measured .zlib container is raw DEFLATE, not RFC1950.
fun Bytes.zlibCompressed(): Bytes {
    val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, true)
    try {
        deflater.setInput(toByteArray())
        deflater.finish()
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!deflater.finished()) {
            val count = deflater.deflate(buffer)
            check(count > 0) { "DEFLATE compressor made no progress" }
            output.write(buffer, 0, count)
        }
        return Bytes(output.toByteArray())
    } finally {
        deflater.end()
    }
}

fun Bytes.zlibDecompressed(maxUncompressedBytes: Long): Bytes {
    require(maxUncompressedBytes >= 0) { "Expanded byte cap must not be negative" }
    val inflater = Inflater(true)
    try {
        inflater.setInput(toByteArray())
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (!inflater.finished()) {
            val count = try {
                inflater.inflate(buffer)
            } catch (cause: DataFormatException) {
                throw AppBackupException(AppBackupError.InvalidFile, cause)
            }
            if (output.size().toLong() + count > maxUncompressedBytes) {
                throw AppBackupException(AppBackupError.DecompressedTooLarge(maxUncompressedBytes))
            }
            if (count == 0 && !inflater.finished()) {
                throw AppBackupException(
                    AppBackupError.InvalidFile,
                    DataFormatException(
                        if (inflater.needsDictionary()) "Unsupported DEFLATE dictionary"
                        else if (inflater.needsInput()) "Truncated DEFLATE stream"
                        else "DEFLATE decoder made no progress",
                    ),
                )
            }
            output.write(buffer, 0, count)
        }
        return Bytes(output.toByteArray())
    } finally {
        inflater.end()
    }
}

internal val GRAPHEMES: Pattern = Pattern.compile("\\X")

internal fun String.graphemePrefix(limit: Int): String {
    require(limit >= 0)
    val matcher = GRAPHEMES.matcher(this)
    var end = 0
    repeat(limit) {
        if (!matcher.find()) return this
        end = matcher.end()
    }
    return substring(0, end)
}
