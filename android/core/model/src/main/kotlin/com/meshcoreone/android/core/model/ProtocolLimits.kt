// PortedFrom: MC1Services/Sources/MC1Services/Models/ProtocolLimits.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

object ProtocolLimits {
    const val PUBLIC_KEY_SIZE = 32
    const val ADVERT_TIMESTAMP_SIZE = 4
    const val PRIVATE_KEY_SIZE = 64
    const val MAX_PATH_SIZE = 64
    const val MAX_FRAME_SIZE = 176
    const val SIGNATURE_SIZE = 64
    const val MAX_PACKET_PAYLOAD = 184
    const val CIPHER_MAC_SIZE = 2
    const val MAX_HASH_SIZE = 8
    const val CIPHER_KEY_SIZE = 16
    const val CIPHER_BLOCK_SIZE = 16
    const val MAX_CONTACTS = 100
    const val OFFLINE_QUEUE_SIZE = 16
    const val MAX_NAME_LENGTH = 32
    const val CHANNEL_SECRET_SIZE = 16
    const val MAX_MESSAGE_LENGTH = 160
    const val MAX_USABLE_NAME_BYTES = 31
    const val MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES = 30
    const val MAX_DIRECT_MESSAGE_LENGTH = 150
    const val RX_LOG_HEARD_REPEAT_FRAME_BUDGET = 172
    const val RX_LOG_PUSH_OVERHEAD_BYTES = 3
    const val PACKET_HEADER_BYTES = 2
    const val TRANSPORT_CODE_BYTES = 4
    const val MIN_HEARD_REPEAT_PATH_BYTES = 1
    const val GROUP_CHANNEL_HASH_BYTES = 1
    const val GROUP_PLAINTEXT_HEADER_BYTES = 5
    const val CHANNEL_SENDER_PREFIX_SEPARATOR_UTF8_COUNT = 2
    const val MAX_CHANNEL_MESSAGE_TOTAL_LENGTH = 139

    fun maxChannelMessageLength(nodeNameByteCount: Long): Long =
        maxOf(0, Math.subtractExact(MAX_CHANNEL_MESSAGE_TOTAL_LENGTH.toLong() - 2, nodeNameByteCount))

    fun groupTextRxLogFrameByteCount(
        composedUTF8Count: Long,
        includesTransportCodes: Boolean,
        pathByteCount: Long,
    ): Long {
        val plaintext = Math.addExact(GROUP_PLAINTEXT_HEADER_BYTES.toLong(), composedUTF8Count)
        val padded = Math.multiplyExact(Math.addExact(plaintext, 15) / 16, 16)
        val overhead = RX_LOG_PUSH_OVERHEAD_BYTES + PACKET_HEADER_BYTES +
            (if (includesTransportCodes) TRANSPORT_CODE_BYTES else 0) + GROUP_CHANNEL_HASH_BYTES + CIPHER_MAC_SIZE
        return Math.addExact(Math.addExact(padded, pathByteCount), overhead.toLong())
    }
}
