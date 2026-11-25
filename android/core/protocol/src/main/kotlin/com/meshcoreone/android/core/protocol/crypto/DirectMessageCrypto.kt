// PortedFrom: MeshCore/Sources/MeshCore/Protocol/DirectMessageCrypto.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.crypto

import com.meshcoreone.android.core.protocol.bytes.Bytes

object DirectMessageCrypto {
    const val macSize = WireCrypto.macSize
    const val headerSize = 2
    const val timestampSize = 4
    const val typeAttemptSize = 1
    const val minCiphertextSize = WireCrypto.blockSize
    const val minPacketSize = headerSize + macSize + minCiphertextSize

    sealed interface DecryptResult {
        data class Success(val timestamp: UInt, val typeAttempt: UByte, val text: String?) : DecryptResult {
            override fun toString(): String =
                "Success(timestamp=$timestamp, typeAttempt=$typeAttempt, textLength=${text?.length})"
        }

        data object MacMismatch : DecryptResult
        data object DecryptionFailed : DecryptResult
        data object InvalidPayload : DecryptResult
        data object KeyError : DecryptResult
    }

    fun decrypt(payload: Bytes, myPrivateKey: Bytes, senderPublicKey: Bytes): DecryptResult {
        if (payload.size < minPacketSize) return DecryptResult.InvalidPayload
        if (myPrivateKey.size != KeyFormat.X25519_PRIVATE.size || senderPublicKey.size != KeyFormat.X25519_PUBLIC.size) {
            return DecryptResult.KeyError
        }
        val sharedSecret = try {
            X25519Crypto.sharedSecret(myPrivateKey, senderPublicKey)
        } catch (_: CryptoException.NonContributoryPublicKey) {
            return DecryptResult.KeyError
        }
        val tag = payload.slice(headerSize, headerSize + macSize)
        val ciphertext = payload.slice(headerSize + macSize, payload.size)
        if (!WireCrypto.authenticate(ciphertext, sharedSecret, tag)) return DecryptResult.MacMismatch
        if (ciphertext.size % WireCrypto.blockSize != 0) return DecryptResult.DecryptionFailed
        val plaintext = WireCrypto.decryptAes128Ecb(ciphertext, sharedSecret)
        if (plaintext.size < timestampSize + typeAttemptSize) return DecryptResult.DecryptionFailed
        return DecryptResult.Success(
            plaintext.readUInt32LE(0),
            plaintext[timestampSize],
            nullTerminatedUtf8(plaintext, timestampSize + typeAttemptSize),
        )
    }

    fun extractTimestamp(payload: Bytes, myPrivateKey: Bytes, senderPublicKey: Bytes): UInt? =
        when (val result = decrypt(payload, myPrivateKey, senderPublicKey)) {
            is DecryptResult.Success -> result.timestamp
            else -> null
        }
}
