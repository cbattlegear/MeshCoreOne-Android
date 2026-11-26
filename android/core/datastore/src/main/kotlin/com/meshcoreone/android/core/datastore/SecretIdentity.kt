// PortedFrom: MC1Services/Sources/MC1Services/Services/KeychainService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/KeyGenerationService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.datastore

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.Base64

sealed class SecretIdentity(val radioId: RadioId) {
    abstract val storageKey: String
    abstract val type: Int
    internal abstract fun validatePlaintext(plaintext: ByteArray)
    final override fun toString(): String = "SecretIdentity([REDACTED])"

    class NodePassword(radioId: RadioId, val nodePublicKey: Bytes) : SecretIdentity(radioId) {
        init {
            if (nodePublicKey.size != 32) {
                throw StorageFailure(StorageProblem.InvalidSecretIdentity, StorageOperation.OPEN)
            }
        }
        override val type: Int = 1
        override val storageKey: String =
            "com.pocketmesh.nodepasswords/${radioId.canonicalString}/${Base64.getEncoder().encodeToString(nodePublicKey.toByteArray())}"
        override fun validatePlaintext(plaintext: ByteArray) {
            if (plaintext.size > MAXIMUM_SECRET_BYTES) {
                throw StorageFailure(StorageProblem.InvalidSecretValue, StorageOperation.WRITE)
            }
            decodeUtf8(plaintext, StorageOperation.READ)
        }
    }

    class RadioIdentity(radioId: RadioId) : SecretIdentity(radioId) {
        override val type: Int = 2
        override val storageKey: String = "com.meshcoreone.android.savedIdentity/${radioId.canonicalString}"
        override fun validatePlaintext(plaintext: ByteArray) {
            if (plaintext.size != 96) {
                throw StorageFailure(StorageProblem.InvalidSecretValue, StorageOperation.WRITE)
            }
            val imported = KeyGenerationService.importExpandedKey(Bytes(plaintext.copyOfRange(0, 64)))
            if (imported.publicKey != Bytes(plaintext.copyOfRange(64, 96))) {
                throw StorageFailure(StorageProblem.InvalidSecretValue, StorageOperation.WRITE)
            }
        }
    }

    companion object { internal const val MAXIMUM_SECRET_BYTES = 65_536 }
}

class SensitiveIdentityExport internal constructor(private val material: ByteArray) : AutoCloseable {
    private var closed = false

    @Synchronized
    fun copyExpandedPrivateKey(): ByteArray {
        check(!closed) { "Sensitive export is closed" }
        return material.copyOfRange(0, 64)
    }

    @Synchronized
    fun publicKey(): Bytes {
        check(!closed) { "Sensitive export is closed" }
        return Bytes(material.copyOfRange(64, 96))
    }

    @Synchronized
    override fun close() {
        material.fill(0)
        closed = true
    }

    override fun toString(): String = "SensitiveIdentityExport([REDACTED]; manual export only)"
}

internal fun decodeUtf8(bytes: ByteArray, operation: StorageOperation): String = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
} catch (failure: java.nio.charset.CharacterCodingException) {
    throw StorageFailure(StorageProblem.InvalidSecretValue, operation, failure)
}
