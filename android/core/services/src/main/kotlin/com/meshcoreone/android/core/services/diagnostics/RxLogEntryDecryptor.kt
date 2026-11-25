// PortedFrom: MC1Services/Sources/MC1Services/Services/RxLogService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: pure decrypt logic over an immutable key snapshot taken from the service's lock.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.ChannelCrypto
import com.meshcoreone.android.core.protocol.crypto.CryptoException
import com.meshcoreone.android.core.protocol.crypto.DirectMessageCrypto
import com.meshcoreone.android.core.protocol.crypto.Ed25519ToX25519
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PayloadType

/**
 * Immutable decryption key snapshot. Channel secrets iterate in ascending channel index order
 * (the source iterates an unordered dictionary, so the first matching channel is unspecified there).
 */
internal data class RxLogCryptoState(
    val channelSecrets: Map<UByte, Bytes>,
    val channelNames: Map<UByte, String>,
    val myPrivateKey: Bytes?,
    val contactPublicKeysByPrefix: Map<UByte, List<Bytes>>,
) {
    fun withChannels(secrets: Map<UByte, Bytes>, names: Map<UByte, String>): RxLogCryptoState = copy(
        channelSecrets = secrets.entries.sortedBy { it.key }.associate { it.key to it.value },
        channelNames = LinkedHashMap(names),
    )

    fun channelName(index: UByte): String = channelNames[index] ?: "Channel $index"

    companion object {
        val EMPTY = RxLogCryptoState(emptyMap(), emptyMap(), null, emptyMap())
    }
}

/** Fields the live `process` path derives from decryption. */
internal data class RxLogLiveDecode(
    val channelIndex: UByte?,
    val channelName: String?,
    val decryptStatus: DecryptStatus,
    val senderTimestamp: UInt?,
    val decodedText: String?,
    val dmTimestamp: UInt?,
    val dmFailed: Boolean,
)

internal class RxLogEntryDecryptor(private val onProviderFailure: (CryptoException.ProviderFailure) -> Unit) {
    /** Live decode of a parsed packet: channel trial decryption, then direct-message decryption. */
    fun decodeLive(parsed: ParsedRxLogData, state: RxLogCryptoState): RxLogLiveDecode {
        var channelIndex: UByte? = null
        var channelName: String? = null
        var decryptStatus = DecryptStatus.NOT_APPLICABLE
        var decodedText: String? = null
        var senderTimestamp: UInt? = null
        var dmTimestamp: UInt? = null
        var dmFailed = false

        if (parsed.payloadType.isChannelPayload) {
            // Channel payload: [channelHash:1][MAC:2][ciphertext:N]. The first byte is a truncated
            // hash, not the index, so every known secret is tried until the MAC validates.
            val rawPayload = parsed.packetPayload
            if (rawPayload.size >= MIN_CHANNEL_PAYLOAD_SIZE) {
                val encrypted = rawPayload.slice(1, rawPayload.size)
                for ((index, secret) in state.channelSecrets) {
                    val success = tryChannel(encrypted, secret) ?: continue
                    channelIndex = index
                    channelName = state.channelName(index)
                    decryptStatus = DecryptStatus.SUCCESS
                    senderTimestamp = success.timestamp
                    decodedText = success.text
                    break
                }
                if (decryptStatus == DecryptStatus.NOT_APPLICABLE) decryptStatus = DecryptStatus.NO_MATCHING_KEY
            } else {
                decryptStatus = DecryptStatus.PENDING
            }
        }

        if (parsed.payloadType == PayloadType.TEXT_MESSAGE && parsed.routeType.isDirectRoute) {
            val dm = state.myPrivateKey?.let { tryDecryptDM(parsed.packetPayload, it, state.contactPublicKeysByPrefix) }
            if (dm != null) {
                senderTimestamp = dm.timestamp
                decodedText = dm.text
                decryptStatus = DecryptStatus.SUCCESS
                dmTimestamp = dm.timestamp
            }
            if (senderTimestamp == null) {
                decryptStatus = DecryptStatus.DM_NO_MATCHING_KEY
                dmFailed = true
            }
        }
        return RxLogLiveDecode(channelIndex, channelName, decryptStatus, senderTimestamp, decodedText, dmTimestamp, dmFailed)
    }

    /** Copy of [entry] with `decodedText` (and attribution) populated when decryption succeeds. */
    fun decryptEntry(entry: RxLogEntryDTO, state: RxLogCryptoState): RxLogEntryDTO {
        if (entry.payloadType == PayloadType.TEXT_MESSAGE && entry.routeType.isDirectRoute) {
            val myPrivateKey = state.myPrivateKey ?: return entry
            val dm = tryDecryptDM(entry.packetPayload, myPrivateKey, state.contactPublicKeysByPrefix) ?: return entry
            return entry.copy(senderTimestamp = dm.timestamp, decodedText = dm.text)
        }
        if (!entry.payloadType.isChannelPayload || entry.packetPayload.size < MIN_CHANNEL_PAYLOAD_SIZE) return entry

        val encrypted = entry.packetPayload.slice(1, entry.packetPayload.size)

        // Fast path: the stored channel index of a previously successful decryption.
        val storedIndex = entry.channelIndex
        val storedSecret = storedIndex?.let { state.channelSecrets[it] }
        if (entry.decryptStatus == DecryptStatus.SUCCESS && storedSecret != null) {
            tryChannel(encrypted, storedSecret)?.let { success ->
                return entry.copy(senderTimestamp = success.timestamp, decodedText = success.text)
            }
        }

        // Slow path: try every secret and record which channel matched so attribution is correct.
        for ((index, secret) in state.channelSecrets) {
            val success = tryChannel(encrypted, secret) ?: continue
            return entry.copy(
                channelIndex = index, channelName = state.channelName(index),
                senderTimestamp = success.timestamp, decodedText = success.text,
            )
        }
        return entry
    }

    /** DM reprocess: sender prefix is `packetPayload[1]` of a direct-routed `[destHash:1][srcHash:1]...`. */
    fun extractDMTimestamp(entry: RxLogEntryDTO, myPrivateKey: Bytes, state: RxLogCryptoState): UInt? {
        if (entry.packetPayload.size < PAYLOAD_HASH_SIZE * 2 || !entry.routeType.isDirectRoute) return null
        val candidateKeys = state.contactPublicKeysByPrefix[entry.packetPayload[PAYLOAD_HASH_SIZE]] ?: return null
        for (senderPublicKey in candidateKeys) {
            val timestamp = guarded { DirectMessageCrypto.extractTimestamp(entry.packetPayload, myPrivateKey, senderPublicKey) }
            if (timestamp != null) return timestamp
        }
        return null
    }

    private data class DMResult(val timestamp: UInt, val text: String?)

    private fun tryDecryptDM(payload: Bytes, myPrivateKey: Bytes, keysByPrefix: Map<UByte, List<Bytes>>): DMResult? {
        if (payload.size < DirectMessageCrypto.minPacketSize) return null
        // DM payload: [destHash:1][srcHash:1][MAC:2][ciphertext:N]
        val candidateKeys = keysByPrefix[payload[PAYLOAD_HASH_SIZE]] ?: return null
        for (senderPublicKey in candidateKeys) {
            val result = guarded { DirectMessageCrypto.decrypt(payload, myPrivateKey, senderPublicKey) }
            if (result is DirectMessageCrypto.DecryptResult.Success) return DMResult(result.timestamp, result.text)
        }
        return null
    }

    private fun tryChannel(encrypted: Bytes, secret: Bytes): ChannelCrypto.DecryptResult.Success? =
        guarded { ChannelCrypto.decrypt(encrypted, secret) } as? ChannelCrypto.DecryptResult.Success

    /** A JCA provider failure is reported and treated as "this key did not match", never as success. */
    private inline fun <T : Any> guarded(operation: () -> T?): T? = try {
        operation()
    } catch (failure: CryptoException.ProviderFailure) {
        onProviderFailure(failure)
        null
    }

    companion object {
        /** 1 (channel hash) + MAC + 16 (minimum ciphertext block). */
        const val MIN_CHANNEL_PAYLOAD_SIZE = 1 + ChannelCrypto.macSize + 16
        private const val PAYLOAD_HASH_SIZE = 1

        /**
         * Converts Ed25519 contact keys to X25519 for DM ECDH. Keys the converter rejects (wrong size,
         * or — stricter than the source — an invalid curve point) are dropped; an empty prefix is omitted.
         */
        fun convertPublicKeysToX25519(keys: Map<UByte, List<Bytes>>): Map<UByte, List<Bytes>> =
            keys.entries.mapNotNull { (prefix, publicKeys) ->
                val converted = publicKeys.mapNotNull { key ->
                    try {
                        Ed25519ToX25519.convertPublicKey(key)
                    } catch (_: CryptoException) {
                        null
                    }
                }
                if (converted.isEmpty()) null else prefix to converted
            }.toMap()
    }
}

private val PayloadType.isChannelPayload: Boolean
    get() = this == PayloadType.GROUP_TEXT || this == PayloadType.GROUP_DATA
