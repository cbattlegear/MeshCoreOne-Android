// PortedFrom: MeshCore/Sources/MeshCore/Protocol/DataExtensions.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: checked cursor/writer distinguishes truncated input from the source zero-return helpers.
package com.meshcoreone.android.core.protocol.bytes

import java.io.ByteArrayOutputStream

class PacketBoundsException(val offset: Int, val requested: Int, val available: Int) :
    IllegalArgumentException("Truncated packet: offset=$offset, requested=$requested, size=$available")

class ByteReader(private val bytes: Bytes, initialOffset: Int = 0) {
    var position: Int = initialOffset
        private set
    val remaining: Int get() = bytes.size - position

    init {
        require(initialOffset in 0..bytes.size) { "Initial byte cursor is outside the packet" }
    }

    private fun take(width: Int): Int {
        if (width < 0 || width > remaining) throw PacketBoundsException(position, width, bytes.size)
        val start = position
        position += width
        return start
    }

    fun readUInt8(): UByte = bytes[take(1)]
    fun readInt8(): Byte = readUInt8().toByte()
    fun readUInt16LE(): UShort = bytes.readUInt16LE(take(2))
    fun readInt16LE(): Short = readUInt16LE().toShort()
    fun readUInt32LE(): UInt = bytes.readUInt32LE(take(4))
    fun readInt32LE(): Int = readUInt32LE().toInt()
    fun readBytes(length: Int): Bytes {
        val start = take(length)
        return bytes.slice(start, position)
    }
}

class ByteWriter {
    private val output = ByteArrayOutputStream()
    val size: Int get() = output.size()

    fun append(bytes: Bytes): ByteWriter = apply { output.write(bytes.toByteArray()) }
    fun appendUInt8(value: UByte): ByteWriter = apply { output.write(value.toInt()) }
    fun appendInt8(value: Byte): ByteWriter = appendUInt8(value.toUByte())
    fun appendUInt16LE(value: UShort): ByteWriter = appendLE(value.toUInt(), 2)
    fun appendInt16LE(value: Short): ByteWriter = appendUInt16LE(value.toUShort())
    fun appendUInt32LE(value: UInt): ByteWriter = appendLE(value, 4)
    fun appendInt32LE(value: Int): ByteWriter = appendUInt32LE(value.toUInt())

    private fun appendLE(value: UInt, width: Int): ByteWriter = apply {
        repeat(width) { index -> output.write(((value shr (index * 8)) and 0xffu).toInt()) }
    }

    fun toBytes(): Bytes = Bytes(output.toByteArray())
}
