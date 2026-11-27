// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.WireCrypto
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.ChannelSecret
import com.meshcoreone.android.core.protocol.model.sha256

/** A channel whose traffic may be uploaded in full. [secret] is key material: never log or serialize it. */
class AllowedChannel(val name: String, secret: Bytes) {
    private val key: Bytes = secret
    val hashByte: Int = sha256(key).prefix(ObserverConstants.CHANNEL_HASH_LENGTH)[0].toInt()

    /** Firmware MACThenDecrypt check only: HMAC-SHA256 (key zero-padded) truncated to 2 bytes over ciphertext. */
    internal fun verifies(mac: Bytes, ciphertext: Bytes): Boolean = WireCrypto.authenticate(ciphertext, key, mac)

    override fun toString(): String = "AllowedChannel(name=$name)"
}

/** Public channel plus every `#name` slot whose secret is the name-derived hashtag key. */
class ChannelAllowList private constructor(private val channels: List<AllowedChannel>) {
    val names: List<String> get() = channels.map { it.name }

    /**
     * Returns the allow-listed channel for a channel payload `[hash:1][mac:2][ciphertext]`, or null if no
     * allow-listed key both hash-matches and authenticates. Plaintext is never produced.
     */
    fun match(payload: Bytes): AllowedChannel? {
        val minimum = ObserverConstants.CHANNEL_HASH_LENGTH + WireCrypto.macSize + WireCrypto.blockSize
        if (payload.size < minimum) return null
        val macEnd = ObserverConstants.CHANNEL_HASH_LENGTH + WireCrypto.macSize
        val ciphertext = payload.slice(macEnd, payload.size)
        if (ciphertext.size % WireCrypto.blockSize != 0) return null
        val hash = payload[0].toInt()
        val mac = payload.slice(ObserverConstants.CHANNEL_HASH_LENGTH, macEnd)
        return channels.firstOrNull { it.hashByte == hash && it.verifies(mac, ciphertext) }
    }

    companion object {
        val publicOnly: ChannelAllowList = from(emptyList())

        fun from(slots: Collection<ChannelInfo>): ChannelAllowList {
            val publicChannel = AllowedChannel(ObserverConstants.PUBLIC_CHANNEL_NAME, ObserverConstants.PUBLIC_CHANNEL_KEY)
            val hashtag = slots.filter(::isHashtagSlot).map { AllowedChannel(it.name, it.secret) }
            val unique = (listOf(publicChannel) + hashtag).distinctBy { it.name }
            return ChannelAllowList(unique)
        }

        private fun isHashtagSlot(slot: ChannelInfo): Boolean =
            slot.name.length > 1 && slot.name.startsWith("#") &&
                slot.secret.size == WireCrypto.keySize &&
                slot.secret == ChannelSecret.DeriveFromName.secretData(slot.name)
    }
}
