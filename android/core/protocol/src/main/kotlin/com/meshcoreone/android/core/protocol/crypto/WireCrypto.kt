// PortedFrom: MeshCore/Sources/MeshCore/Protocol/ChannelCrypto.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MeshCore/Sources/MeshCore/Protocol/DirectMessageCrypto.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.crypto

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.util.Arrays

object WireCrypto {
    const val macSize = 2
    const val keySize = 16
    const val blockSize = 16

    fun hmacSha256(data: Bytes, key: Bytes): Bytes {
        // An empty HMAC key and a zero-filled hash block have the same RFC 2104 normalization.
        val keyBytes = if (key.isEmpty) ByteArray(64) else key.toByteArray()
        try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(keyBytes, "HmacSHA256"))
            return Bytes(mac.doFinal(data.toByteArray()))
        } catch (failure: GeneralSecurityException) {
            throw CryptoException.ProviderFailure("HMAC-SHA256", failure)
        } finally {
            keyBytes.fill(0)
        }
    }

    fun truncatedHmacSha256(data: Bytes, key: Bytes): Bytes = hmacSha256(data, key).prefix(macSize)

    fun authenticate(ciphertext: Bytes, secret: Bytes, tag: Bytes): Boolean {
        if (tag.size != macSize) throw CryptoException.InvalidTagLength(tag.size)
        return Arrays.constantTimeAreEqual(
            truncatedHmacSha256(ciphertext, secret).toByteArray(),
            tag.toByteArray(),
        )
    }

    fun encryptAes128EcbZeroPadded(plaintext: Bytes, secret: Bytes): Bytes {
        requireSecret(secret)
        val length = ((plaintext.size.toLong() + blockSize - 1) / blockSize) * blockSize
        if (length > Int.MAX_VALUE) throw CryptoException.DataTooLarge(plaintext.size)
        val padded = plaintext.toByteArray().copyOf(length.toInt())
        try {
            return transform(padded, secret, Cipher.ENCRYPT_MODE)
        } finally {
            padded.fill(0)
        }
    }

    fun decryptAes128Ecb(ciphertext: Bytes, secret: Bytes): Bytes {
        requireSecret(secret)
        if (ciphertext.size % blockSize != 0) throw CryptoException.InvalidCiphertextLength(ciphertext.size)
        return transform(ciphertext.toByteArray(), secret, Cipher.DECRYPT_MODE)
    }

    private fun requireSecret(secret: Bytes) {
        if (secret.size < keySize) throw CryptoException.SecretTooShort(secret.size)
    }

    private fun transform(input: ByteArray, secret: Bytes, mode: Int): Bytes {
        val key = secret.prefix(keySize).toByteArray()
        try {
            val cipher = Cipher.getInstance("AES/ECB/NoPadding")
            cipher.init(mode, SecretKeySpec(key, "AES"))
            val output = cipher.doFinal(input)
            try {
                return Bytes(output)
            } finally {
                output.fill(0)
            }
        } catch (failure: GeneralSecurityException) {
            throw CryptoException.ProviderFailure("AES-128/ECB/NoPadding", failure)
        } finally {
            key.fill(0)
        }
    }
}

internal fun nullTerminatedUtf8(plaintext: Bytes, offset: Int): String? {
    val bytes = plaintext.toByteArray()
    var end = offset
    while (end < bytes.size && bytes[end] != 0.toByte()) end++
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(bytes, offset, end - offset)).toString()
    } catch (_: CharacterCodingException) {
        null
    } finally {
        bytes.fill(0)
    }
}
