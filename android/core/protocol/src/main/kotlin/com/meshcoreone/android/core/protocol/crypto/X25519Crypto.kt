// PortedFrom: MeshCore/Sources/MeshCore/Protocol/DirectMessageCrypto.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.crypto

import com.meshcoreone.android.core.protocol.bytes.Bytes
import org.bouncycastle.math.ec.rfc7748.X25519

object X25519Crypto {
    fun publicKey(privateKey: Bytes): Bytes {
        privateKey.requireKeyFormat(KeyFormat.X25519_PRIVATE)
        val scalar = privateKey.toByteArray()
        val publicKey = ByteArray(X25519.POINT_SIZE)
        try {
            X25519.generatePublicKey(scalar, 0, publicKey, 0)
            return Bytes(publicKey)
        } finally {
            scalar.fill(0)
        }
    }

    fun sharedSecret(privateKey: Bytes, publicKey: Bytes): Bytes {
        privateKey.requireKeyFormat(KeyFormat.X25519_PRIVATE)
        publicKey.requireKeyFormat(KeyFormat.X25519_PUBLIC)
        val scalar = privateKey.toByteArray()
        val shared = ByteArray(X25519.POINT_SIZE)
        try {
            if (!X25519.calculateAgreement(scalar, 0, publicKey.toByteArray(), 0, shared, 0)) {
                throw CryptoException.NonContributoryPublicKey()
            }
            return Bytes(shared)
        } finally {
            scalar.fill(0)
            shared.fill(0)
        }
    }
}
