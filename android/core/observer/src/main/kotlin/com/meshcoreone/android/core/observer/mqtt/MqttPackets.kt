// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.mqtt

import java.io.ByteArrayOutputStream

class MqttProtocolException(message: String) : Exception(message)

/** Packets the broker sends back. Everything else a publish-only client never expects is [Other] and ignored. */
sealed interface MqttIncoming {
    data class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : MqttIncoming {
        val accepted: Boolean get() = returnCode == 0
    }
    data class PubAck(val packetId: Int) : MqttIncoming
    data object PingResp : MqttIncoming
    data class Other(val type: Int) : MqttIncoming
}

/**
 * The MQTT 3.1.1 subset a publish-only client needs: CONNECT (clean session, username and password), PUBLISH at QoS 1,
 * PINGREQ and DISCONNECT out; CONNACK, PUBACK and PINGRESP in. No subscribe, no QoS 2, no will.
 */
object MqttPackets {
    private const val TYPE_CONNECT = 1
    private const val TYPE_CONNACK = 2
    private const val TYPE_PUBLISH = 3
    private const val TYPE_PUBACK = 4
    private const val TYPE_PINGRESP = 13
    private const val MAX_REMAINING_LENGTH = 268_435_455
    private const val MAX_FIELD = 65_535

    fun connect(clientId: String, username: String, password: ByteArray, keepAliveSeconds: Int): ByteArray {
        require(keepAliveSeconds in 0..MAX_FIELD) { "keep alive out of range" }
        val body = ByteArrayOutputStream()
        body.writeField("MQTT".toByteArray(Charsets.US_ASCII))
        body.write(4) // protocol level 3.1.1
        body.write(0x80 or 0x40 or 0x02) // username, password, clean session
        body.write(keepAliveSeconds ushr 8); body.write(keepAliveSeconds and 0xff)
        body.writeField(clientId.toByteArray(Charsets.UTF_8))
        body.writeField(username.toByteArray(Charsets.UTF_8))
        body.writeField(password)
        return frame(TYPE_CONNECT shl 4, body.toByteArray())
    }

    /** QoS 1, not retained. [packetId] is 1..65535. */
    fun publish(topic: String, payload: ByteArray, packetId: Int, duplicate: Boolean = false): ByteArray {
        require(packetId in 1..MAX_FIELD) { "packet id out of range" }
        require(topic.isNotEmpty() && '#' !in topic && '+' !in topic && '\u0000' !in topic) { "invalid publish topic" }
        val body = ByteArrayOutputStream()
        body.writeField(topic.toByteArray(Charsets.UTF_8))
        body.write(packetId ushr 8); body.write(packetId and 0xff)
        body.write(payload)
        return frame((TYPE_PUBLISH shl 4) or 0x02 or (if (duplicate) 0x08 else 0), body.toByteArray())
    }

    fun pingReq(): ByteArray = byteArrayOf(0xC0.toByte(), 0)

    fun disconnect(): ByteArray = byteArrayOf(0xE0.toByte(), 0)

    private fun frame(firstByte: Int, body: ByteArray): ByteArray {
        require(body.size <= MAX_REMAINING_LENGTH) { "packet too large" }
        val out = ByteArrayOutputStream(body.size + 5)
        out.write(firstByte)
        var length = body.size
        do {
            var digit = length % 128
            length /= 128
            if (length > 0) digit = digit or 0x80
            out.write(digit)
        } while (length > 0)
        out.write(body)
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeField(bytes: ByteArray) {
        require(bytes.size <= MAX_FIELD) { "field too long" }
        write(bytes.size ushr 8); write(bytes.size and 0xff); write(bytes)
    }

    /**
     * Incremental decoder: feed it whatever a WebSocket frame delivered (a packet may be split or several may be joined) and get
     * the complete packets. Throws [MqttProtocolException] on a malformed packet; the connection must then be dropped.
     */
    class Decoder {
        private var buffer = ByteArray(0)

        @Synchronized
        fun feed(bytes: ByteArray): List<MqttIncoming> {
            buffer += bytes
            val out = ArrayList<MqttIncoming>()
            while (true) {
                val parsed = parseOne() ?: break
                out += parsed
            }
            return out
        }

        private fun parseOne(): MqttIncoming? {
            if (buffer.size < 2) return null
            var length = 0
            var multiplier = 1
            var index = 1
            while (true) {
                if (index >= buffer.size) return null
                val digit = buffer[index].toInt() and 0xff
                length += (digit and 0x7f) * multiplier
                index++
                if (digit and 0x80 == 0) break
                multiplier *= 128
                if (multiplier > 128 * 128 * 128) throw MqttProtocolException("remaining length too long")
            }
            if (buffer.size < index + length) return null
            val first = buffer[0].toInt() and 0xff
            val body = buffer.copyOfRange(index, index + length)
            buffer = buffer.copyOfRange(index + length, buffer.size)
            return when (val type = first ushr 4) {
                TYPE_CONNACK -> {
                    if (body.size != 2) throw MqttProtocolException("bad CONNACK length ${body.size}")
                    MqttIncoming.ConnAck(body[0].toInt() and 1 == 1, body[1].toInt() and 0xff)
                }
                TYPE_PUBACK -> {
                    if (body.size != 2) throw MqttProtocolException("bad PUBACK length ${body.size}")
                    MqttIncoming.PubAck(((body[0].toInt() and 0xff) shl 8) or (body[1].toInt() and 0xff))
                }
                TYPE_PINGRESP -> MqttIncoming.PingResp
                else -> MqttIncoming.Other(type)
            }
        }
    }
}
