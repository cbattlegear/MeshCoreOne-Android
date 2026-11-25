// PortedFrom: MeshCore/Sources/MeshCore/Transport/WiFiFrameCodec.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.transport.tcp

import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException

object WiFiFrameCodec {
    val outboundDelimiter: UByte = 0x3cu
    val inboundDelimiter: UByte = 0x3eu
    const val headerSize = 3
    const val maxPayloadSize = 0xffff

    fun encode(payload: Bytes): Bytes {
        if (payload.size > maxPayloadSize) {
            throw WiFiFrameException.PayloadTooLarge(payload.size, maxPayloadSize)
        }
        return ByteWriter()
            .appendUInt8(outboundDelimiter)
            .appendUInt16LE(payload.size.toUShort())
            .append(payload)
            .toBytes()
    }
}

sealed class WiFiFrameException(message: String) : IOException(message) {
    class PayloadTooLarge(val actualSize: Int, val maximumSize: Int) :
        WiFiFrameException("WiFi payload exceeds its length field: actual=$actualSize, maximum=$maximumSize")

    class TruncatedFrame(val bufferedBytes: Int, val expectedBytes: Int?) :
        WiFiFrameException("TCP ended within a WiFi frame: buffered=$bufferedBytes, expected=$expectedBytes")
}

class WiFiFrameDecoder {
    private val header = ByteArray(WiFiFrameCodec.headerSize)
    private var headerCount = 0
    private var payload: ByteArray? = null
    private var payloadCount = 0

    var discardedByteCount: Long = 0
        private set

    val bufferedByteCount: Int get() = headerCount + payloadCount

    fun decode(data: Bytes): List<Bytes> {
        val input = data.toByteArray()
        val frames = mutableListOf<Bytes>()
        var offset = 0
        while (offset < input.size) {
            if (headerCount == 0 && input[offset].toUByte() != WiFiFrameCodec.inboundDelimiter) {
                discardedByteCount = Math.addExact(discardedByteCount, 1)
                offset++
                continue
            }
            while (headerCount < header.size && offset < input.size) {
                header[headerCount++] = input[offset++]
            }
            if (headerCount < header.size) break

            val currentPayload = payload ?: ByteArray(
                (header[1].toInt() and 0xff) or ((header[2].toInt() and 0xff) shl 8),
            ).also { payload = it }
            val count = minOf(currentPayload.size - payloadCount, input.size - offset)
            input.copyInto(currentPayload, payloadCount, offset, offset + count)
            payloadCount += count
            offset += count
            if (payloadCount == currentPayload.size) {
                frames += Bytes(currentPayload)
                clearPartialFrame()
            }
        }
        return frames
    }

    fun finish() {
        if (bufferedByteCount != 0) {
            throw WiFiFrameException.TruncatedFrame(
                bufferedByteCount,
                payload?.let { WiFiFrameCodec.headerSize + it.size },
            )
        }
    }

    fun reset() {
        clearPartialFrame()
        discardedByteCount = 0
    }

    private fun clearPartialFrame() {
        headerCount = 0
        payload = null
        payloadCount = 0
    }
}
