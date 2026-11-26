// PortedFrom: MeshCore/Sources/MeshCore/Protocol/DataExtensions.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.bytes

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.regex.Pattern

class Bytes(data: ByteArray) : Iterable<UByte> {
    private val value = data.copyOf()

    val size: Int get() = value.size
    val isEmpty: Boolean get() = value.isEmpty()
    val hexString: String
        get() = buildString(size * 2) {
            for (byte in value) {
                val unsigned = byte.toInt() and 0xff
                append(HEX[unsigned ushr 4])
                append(HEX[unsigned and 0x0f])
            }
        }

    operator fun get(index: Int): UByte = value[index].toUByte()

    fun toByteArray(): ByteArray = value.copyOf()

    fun slice(from: Int, until: Int): Bytes {
        require(from >= 0 && until >= from && until <= size) {
            "Byte slice is outside the value: start=$from, end=$until, size=$size"
        }
        return Bytes(value.copyOfRange(from, until))
    }

    fun prefix(length: Int): Bytes {
        require(length >= 0) { "Byte prefix length must not be negative" }
        return slice(0, minOf(length, size))
    }

    fun paddedOrTruncated(length: Int): Bytes =
        if (length <= 0) EMPTY else Bytes(value.copyOf(length))

    fun readUInt16LE(offset: Int): UShort = readLE(offset, 2).toUShort()
    fun readInt16LE(offset: Int): Short = readUInt16LE(offset).toShort()
    fun readUInt32LE(offset: Int): UInt = readLE(offset, 4).toUInt()
    fun readInt32LE(offset: Int): Int = readUInt32LE(offset).toInt()
    fun readUInt16(offset: Int): UShort = readUInt16LE(offset)
    fun readInt16(offset: Int): Short = readInt16LE(offset)
    fun readUInt32(offset: Int): UInt = readUInt32LE(offset)
    fun readInt32(offset: Int): Int = readInt32LE(offset)

    private fun readLE(offset: Int, width: Int): Long {
        require(offset >= 0) { "Byte offset must not be negative" }
        // Preserve the source helpers' explicit zero-on-short-input contract.
        if (offset > size - width) return 0
        var result = 0L
        repeat(width) { index ->
            result = result or ((value[offset + index].toLong() and 0xff) shl (index * 8))
        }
        return result
    }

    fun decodingLongestValidUtf8Prefix(): String {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        for (length in size downTo 1) {
            try {
                return decoder.reset().decode(ByteBuffer.wrap(value, 0, length)).toString()
            } catch (_: CharacterCodingException) {
                // Fixed-width firmware names can end within a UTF-8 sequence.
            }
        }
        return ""
    }

    operator fun plus(other: Bytes): Bytes = Bytes(value + other.value)

    override fun iterator(): Iterator<UByte> = object : Iterator<UByte> {
        private var position = 0
        override fun hasNext(): Boolean = position < size
        override fun next(): UByte {
            if (!hasNext()) throw NoSuchElementException("No byte remains")
            return value[position++].toUByte()
        }
    }

    override fun equals(other: Any?): Boolean = other is Bytes && value.contentEquals(other.value)
    override fun hashCode(): Int = value.contentHashCode()
    override fun toString(): String = "Bytes(size=$size)"

    companion object {
        private const val HEX = "0123456789abcdef"
        val EMPTY = Bytes(byteArrayOf())

        fun of(vararg values: Int): Bytes {
            require(values.all { it in 0..255 }) { "A byte value must be in 0..255" }
            return Bytes(ByteArray(values.size) { values[it].toByte() })
        }

        fun utf8(text: String): Bytes = Bytes(text.toByteArray(Charsets.UTF_8))

        fun parseHex(text: String): Bytes? {
            val characters = graphemePattern.matcher(text)
            val digits = mutableListOf<String>()
            while (characters.find()) digits += characters.group()
            val result = ByteArray(digits.size / 2)
            for (index in result.indices) {
                val first = digits[index * 2]
                val low = hexDigit(digits[index * 2 + 1]) ?: return null
                val high = hexDigit(first)
                result[index] = when {
                    high != null -> ((high shl 4) or low).toByte()
                    first == "+" -> low.toByte()
                    first == "-" && low == 0 -> 0
                    else -> return null
                }
            }
            return Bytes(result)
        }

        fun fromHex(text: String): Bytes =
            parseHex(text) ?: throw InvalidHexException(text)

        private fun hexDigit(character: String): Int? = when {
            character.length != 1 -> null
            character[0] in '0'..'9' -> character[0] - '0'
            character[0] in 'a'..'f' -> character[0] - 'a' + 10
            character[0] in 'A'..'F' -> character[0] - 'A' + 10
            else -> null
        }
    }
}

class InvalidHexException(val input: String) : IllegalArgumentException("Invalid hexadecimal byte value")

internal val graphemePattern: Pattern = Pattern.compile("\\X")

fun String.utf8Prefix(maxBytes: Int): String {
    if (maxBytes <= 0) return ""
    val matcher = graphemePattern.matcher(this)
    var remaining = maxBytes
    var end = 0
    while (matcher.find()) {
        val required = matcher.group().toByteArray(Charsets.UTF_8).size
        if (required > remaining) break
        remaining -= required
        end = matcher.end()
    }
    return substring(0, end)
}

fun String.utf8PaddedOrTruncated(length: Int): Bytes =
    Bytes.utf8(utf8Prefix(length)).paddedOrTruncated(length)

fun Byte.snrValue(): Double = toDouble() / 4.0
fun UByte.snrValue(): Double = toByte().snrValue()
