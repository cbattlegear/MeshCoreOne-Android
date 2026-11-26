// PortedFrom: MeshCore/Sources/MeshCore/Models/Destination.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.security.MessageDigest

sealed class DestinationError(message: String) : IllegalArgumentException(message) {
    class InvalidHexString(val input: String) : DestinationError("Invalid destination hexadecimal value")
    class InsufficientLength(val expected: Int, val actual: Int) :
        DestinationError("Destination public key is too short: expected=$expected, actual=$actual")
}

sealed interface Destination {
    data class Data(val value: Bytes) : Destination
    data class HexString(val value: String) : Destination
    data class Contact(val value: MeshContact) : Destination

    fun publicKey(prefixLength: Int = 6): Bytes {
        require(prefixLength >= 0) { "Destination prefix length must not be negative" }
        val key = when (this) {
            is Data -> value
            is HexString -> Bytes.parseHex(value) ?: throw DestinationError.InvalidHexString(value)
            is Contact -> value.publicKey
        }
        if (key.size < prefixLength) throw DestinationError.InsufficientLength(prefixLength, key.size)
        return key.prefix(prefixLength)
    }

    fun fullPublicKey(): Bytes = publicKey(32)
}

fun sha256(data: Bytes): Bytes = Bytes(MessageDigest.getInstance("SHA-256").digest(data.toByteArray()))

sealed interface FloodScope {
    data object Disabled : FloodScope
    data class ChannelName(val name: String) : FloodScope
    data class RawKey(val key: Bytes) : FloodScope
    data class Region(val name: String) : FloodScope

    fun scopeKey(): Bytes = when (this) {
        Disabled -> Bytes.EMPTY.paddedOrTruncated(16)
        is ChannelName -> sha256(Bytes.utf8(name)).prefix(16)
        is RawKey -> key.paddedOrTruncated(16)
        is Region -> sha256(Bytes.utf8(if (name.startsWith("#")) name else "#$name")).prefix(16)
    }
}

sealed interface ChannelSecret {
    data class Explicit(val data: Bytes) : ChannelSecret
    data object DeriveFromName : ChannelSecret

    fun secretData(channelName: String): Bytes = when (this) {
        is Explicit -> data.paddedOrTruncated(16)
        DeriveFromName -> sha256(Bytes.utf8(channelName)).prefix(16)
    }
}
