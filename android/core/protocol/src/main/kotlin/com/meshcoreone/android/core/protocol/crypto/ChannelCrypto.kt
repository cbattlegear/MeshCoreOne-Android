// PortedFrom: MeshCore/Sources/MeshCore/Protocol/ChannelCrypto.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.crypto

import com.meshcoreone.android.core.protocol.bytes.Bytes

object ChannelCrypto {
    const val macSize = WireCrypto.macSize
    const val keySize = WireCrypto.keySize
    const val timestampSize = 4
    const val txtTypeSize = 1
    const val plaintextHeaderSize = timestampSize + txtTypeSize

    sealed interface DecryptResult {
        data class Success(val timestamp: UInt, val txtType: UByte, val text: String) : DecryptResult {
            override fun toString(): String =
                "Success(timestamp=$timestamp, txtType=$txtType, textLength=${text.length})"
        }

        data object HmacFailed : DecryptResult
        data object DecryptFailed : DecryptResult
        data object PayloadTooShort : DecryptResult
    }

    fun decrypt(payload: Bytes, secret: Bytes): DecryptResult {
        if (payload.size < macSize + WireCrypto.blockSize) return DecryptResult.PayloadTooShort
        val tag = payload.prefix(macSize)
        val ciphertext = payload.slice(macSize, payload.size)
        if (!WireCrypto.authenticate(ciphertext, secret, tag)) return DecryptResult.HmacFailed
        if (secret.size < keySize || ciphertext.size % WireCrypto.blockSize != 0) {
            return DecryptResult.DecryptFailed
        }
        val plaintext = WireCrypto.decryptAes128Ecb(ciphertext, secret)
        if (plaintext.size < plaintextHeaderSize) return DecryptResult.DecryptFailed
        val text = nullTerminatedUtf8(plaintext, plaintextHeaderSize) ?: return DecryptResult.DecryptFailed
        return DecryptResult.Success(
            plaintext.readUInt32LE(0),
            plaintext[timestampSize],
            text,
        )
    }
}
