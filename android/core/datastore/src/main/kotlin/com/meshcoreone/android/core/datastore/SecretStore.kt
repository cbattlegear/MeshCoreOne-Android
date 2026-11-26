// PortedFrom: MC1Services/Sources/MC1Services/Services/KeychainService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/KeyGenerationService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.datastore

import androidx.datastore.core.DataStore
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class KeyRotationResult(val oldKeyCleanupIssue: StorageFailure?) {
    val oldKeyRemoved: Boolean get() = oldKeyCleanupIssue == null
}

class SecretStore internal constructor(
    private val store: DataStore<SecretArchive>,
    private val cryptography: SecretCryptography,
    private val access: StorageAccess,
    private val reporter: StorageIssueReporter,
    private val aliasPrefix: String,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val operations = Mutex()

    suspend fun storePassword(password: String, radioId: RadioId, nodePublicKey: Bytes) {
        val identity = SecretIdentity.NodePassword(radioId, nodePublicKey)
        val plaintext = encodePassword(password)
        try {
            write(identity, plaintext, replaceExisting = true)
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun retrievePassword(radioId: RadioId, nodePublicKey: Bytes): String? =
        read(SecretIdentity.NodePassword(radioId, nodePublicKey))?.let { plaintext ->
            try {
                decodeUtf8(plaintext, StorageOperation.READ)
            } finally {
                plaintext.fill(0)
            }
        }

    suspend fun hasPassword(radioId: RadioId, nodePublicKey: Bytes): Boolean =
        read(SecretIdentity.NodePassword(radioId, nodePublicKey))?.let {
            it.fill(0)
            true
        } ?: false

    suspend fun deletePassword(radioId: RadioId, nodePublicKey: Bytes) =
        delete(SecretIdentity.NodePassword(radioId, nodePublicKey))

    suspend fun saveIdentity(
        radioId: RadioId,
        expandedPrivateKey: Bytes,
        expectedPublicKey: Bytes,
        replaceExisting: Boolean = false,
    ) {
        val imported = KeyGenerationService.importExpandedKey(expandedPrivateKey)
        if (imported.publicKey != expectedPublicKey) {
            throw reported(StorageFailure(StorageProblem.InvalidSecretValue, StorageOperation.WRITE))
        }
        val plaintext = expandedPrivateKey.toByteArray() + expectedPublicKey.toByteArray()
        try {
            write(SecretIdentity.RadioIdentity(radioId), plaintext, replaceExisting)
        } finally {
            plaintext.fill(0)
        }
    }

    suspend fun exportIdentityForUser(radioId: RadioId): SensitiveIdentityExport =
        read(SecretIdentity.RadioIdentity(radioId))?.let(::SensitiveIdentityExport)
            ?: throw reported(StorageFailure(StorageProblem.SecretNotFound, StorageOperation.EXPORT))

    suspend fun deleteIdentity(radioId: RadioId) = delete(SecretIdentity.RadioIdentity(radioId))

    suspend fun rotateEncryptionKey(): KeyRotationResult {
        var oldAlias: String? = null
        operation(StorageOperation.ROTATE) {
            store.updateData { previous ->
                access.requireAccessible(StorageOperation.ROTATE)
                val alias = requireUsedKey(previous, StorageOperation.ROTATE)
                    ?: throw StorageFailure(StorageProblem.SecretNotFound, StorageOperation.ROTATE)
                if (previous.generation == Long.MAX_VALUE) {
                    throw StorageFailure(StorageProblem.CorruptSecretState, StorageOperation.ROTATE)
                }
                val generation = previous.generation + 1
                val nextAlias = "$aliasPrefix.$generation"
                // A failed pre-commit rotation may leave this unused key. Reuse it, never replace it.
                cryptography.createKeyIfAbsent(nextAlias)
                val reencrypted = linkedMapOf<String, Bytes>()
                for ((key, envelope) in previous.entries) {
                    currentCoroutineContext().ensureActive()
                    val identity = identityFromStorageKey(key)
                    val plaintext = SecretEnvelope.decrypt(identity, alias, envelope, cryptography)
                    try {
                        reencrypted[key] = SecretEnvelope.encrypt(identity, nextAlias, plaintext, cryptography)
                    } finally {
                        plaintext.fill(0)
                    }
                }
                currentCoroutineContext().ensureActive()
                oldAlias = alias
                SecretArchive(nextAlias, generation, reencrypted).authenticated(cryptography)
            }
        }
        // The file has committed. Finish only obsolete-key cleanup; do not claim a rollback now.
        return withContext(NonCancellable + dispatcher) {
            try {
                cryptography.deleteKey(checkNotNull(oldAlias))
                KeyRotationResult(null)
            } catch (failure: StorageFailure) {
                reporter.report(failure)
                KeyRotationResult(failure)
            }
        }
    }

    private suspend fun read(identity: SecretIdentity): ByteArray? {
        var material: ByteArray? = null
        try {
            return operation(StorageOperation.READ) {
                val archive = store.data.first()
                access.requireAccessible(StorageOperation.READ)
                currentCoroutineContext().ensureActive()
                val alias = requireUsedKey(archive, StorageOperation.READ)
                val envelope = archive.entries[identity.storageKey] ?: return@operation null
                if (alias == null) throw StorageFailure(StorageProblem.CorruptSecretState, StorageOperation.READ)
                SecretEnvelope.decrypt(identity, alias, envelope, cryptography).also {
                    material = it
                    currentCoroutineContext().ensureActive()
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            material?.fill(0)
            throw cancelled
        }
    }

    private suspend fun write(identity: SecretIdentity, plaintext: ByteArray, replaceExisting: Boolean) =
        operation(StorageOperation.WRITE) {
            store.updateData { previous ->
                access.requireAccessible(StorageOperation.WRITE)
                currentCoroutineContext().ensureActive()
                var alias = requireUsedKey(previous, StorageOperation.WRITE)
                val existing = previous.entries[identity.storageKey]
                if (existing != null) {
                    if (!replaceExisting) throw StorageFailure(StorageProblem.SecretAlreadyExists, StorageOperation.WRITE)
                    val decrypted = SecretEnvelope.decrypt(identity, checkNotNull(alias), existing, cryptography)
                    decrypted.fill(0)
                }
                if (alias == null) {
                    if (previous.entries.isNotEmpty()) {
                        throw StorageFailure(StorageProblem.CorruptSecretState, StorageOperation.WRITE)
                    }
                    alias = "$aliasPrefix.0"
                    cryptography.createKeyIfAbsent(alias)
                }
                val encrypted = SecretEnvelope.encrypt(identity, alias, plaintext, cryptography)
                currentCoroutineContext().ensureActive()
                SecretArchive(alias, previous.generation, previous.entries + (identity.storageKey to encrypted))
                    .authenticated(cryptography)
            }
            Unit
        }

    private suspend fun delete(identity: SecretIdentity) = operation(StorageOperation.DELETE) {
        store.updateData { previous ->
            access.requireAccessible(StorageOperation.DELETE)
            requireUsedKey(previous, StorageOperation.DELETE)
            currentCoroutineContext().ensureActive()
            if (!previous.entries.containsKey(identity.storageKey)) previous
            else SecretArchive(previous.alias, previous.generation, previous.entries - identity.storageKey)
                .authenticated(cryptography)
        }
        Unit
    }

    private fun requireUsedKey(archive: SecretArchive, operation: StorageOperation): String? {
        val alias = archive.alias ?: return null
        if (alias != "$aliasPrefix.${archive.generation}") {
            throw StorageFailure(StorageProblem.CorruptSecretState, operation)
        }
        if (!cryptography.containsKey(alias)) {
            throw StorageFailure(StorageProblem.FormerKeyMissing, operation)
        }
        archive.verifyAuthentication(cryptography)
        return alias
    }

    private suspend fun <T> operation(operation: StorageOperation, block: suspend () -> T): T = try {
        operations.withLock {
            access.requireAccessible(operation)
            withContext(dispatcher) { block() }
        }
    } catch (failure: StorageFailure) {
        throw reported(
            if (failure.operation == operation) failure else StorageFailure(failure.problem, operation, failure),
        )
    } catch (failure: IOException) {
        throw reported(StorageFailure(StorageProblem.IoFailure, operation, failure))
    } catch (failure: SecurityException) {
        throw reported(StorageFailure(StorageProblem.PermissionDenied, operation, failure))
    }

    private fun reported(failure: StorageFailure): StorageFailure = failure.also(reporter::report)

    private fun encodePassword(password: String): ByteArray {
        if (password.length > SecretIdentity.MAXIMUM_SECRET_BYTES) {
            throw reported(StorageFailure(StorageProblem.InvalidSecretValue, StorageOperation.WRITE))
        }
        try {
            val buffer = Charsets.UTF_8.newEncoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .encode(java.nio.CharBuffer.wrap(password))
            if (buffer.remaining() > SecretIdentity.MAXIMUM_SECRET_BYTES) {
                throw reported(StorageFailure(StorageProblem.InvalidSecretValue, StorageOperation.WRITE))
            }
            return ByteArray(buffer.remaining()).also(buffer::get)
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw reported(StorageFailure(StorageProblem.InvalidSecretValue, StorageOperation.WRITE, failure))
        }
    }
}

internal fun identityFromStorageKey(key: String): SecretIdentity {
    val fields = key.split('/', limit = 3)
    try {
        if (fields.size !in 2..3) throw IllegalArgumentException("Malformed secret identity")
        val radio = RadioId(UUID.fromString(fields[1]))
        if (radio.canonicalString != fields[1]) throw IllegalArgumentException("Noncanonical radio identity")
        return when {
            fields[0] == "com.pocketmesh.nodepasswords" && fields.size == 3 -> {
                val bytes = Base64.getDecoder().decode(fields[2])
                if (Base64.getEncoder().encodeToString(bytes) != fields[2]) {
                    throw IllegalArgumentException("Noncanonical public key")
                }
                SecretIdentity.NodePassword(radio, Bytes(bytes))
            }
            fields[0] == "com.meshcoreone.android.savedIdentity" && fields.size == 2 -> SecretIdentity.RadioIdentity(radio)
            else -> throw IllegalArgumentException("Unsupported secret identity")
        }
    } catch (failure: IllegalArgumentException) {
        throw StorageFailure(StorageProblem.InvalidSecretIdentity, StorageOperation.READ, failure)
    }
}

object SensitiveStoragePolicy {
    const val AUTOMATIC_BACKUP_ALLOWED = false
    const val NODE_PASSWORDS_INCLUDED_IN_MANUAL_APP_BACKUP = false
    const val IDENTITY_EXPORT_REQUIRES_EXPLICIT_USER_ACTION = true
    const val MANUAL_BACKUPS_MAY_CONTAIN_CHANNEL_SECRETS = true
}
