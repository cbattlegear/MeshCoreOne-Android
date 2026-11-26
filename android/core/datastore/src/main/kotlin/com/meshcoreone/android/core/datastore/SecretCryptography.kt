// PortedFrom: MC1Services/Sources/MC1Services/Services/KeychainService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native Keystore AES-GCM is independent of firmware wire AES-ECB.
package com.meshcoreone.android.core.datastore

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.security.keystore.UserNotAuthenticatedException
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import javax.crypto.AEADBadTagException
import javax.crypto.BadPaddingException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AuthenticatedCiphertext(val nonce: Bytes, val ciphertext: Bytes) {
    override fun toString(): String = "AuthenticatedCiphertext([REDACTED])"
}

interface SecretCryptography {
    fun containsKey(alias: String): Boolean
    fun createKeyIfAbsent(alias: String)
    fun encrypt(alias: String, plaintext: ByteArray, authenticatedData: ByteArray): AuthenticatedCiphertext
    fun decrypt(alias: String, value: AuthenticatedCiphertext, authenticatedData: ByteArray): ByteArray
    fun deleteKey(alias: String)
}

enum class KeystoreUnlockedAccessPolicy(val requireUnlockedKeystoreKey: Boolean) {
    LEGACY_EXPLICIT_LOCK_CHECKS(false),
    PLATFORM_ENFORCED_WITH_EXPLICIT_LOCK_CHECKS(true);

    companion object {
        fun forPlatform(sdk: Int): KeystoreUnlockedAccessPolicy {
            require(sdk >= 31) { "Credential-protected storage requires Android API31 or later" }
            return if (sdk >= 35) PLATFORM_ENFORCED_WITH_EXPLICIT_LOCK_CHECKS else LEGACY_EXPLICIT_LOCK_CHECKS
        }
    }
}

internal interface KeystoreKeyAccess {
    fun find(alias: String): SecretKey?
    fun generate(specification: KeyGenParameterSpec): SecretKey
    fun delete(alias: String)
}

internal class FrameworkKeystoreKeyAccess : KeystoreKeyAccess {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun find(alias: String): SecretKey? {
        val store = store()
        if (!store.containsAlias(alias)) return null
        val key = store.getKey(alias, null)
            ?: throw StorageFailure(StorageProblem.FormerKeyMissing, StorageOperation.READ)
        return key as? SecretKey
            ?: throw StorageFailure(StorageProblem.CorruptKeystoreKey, StorageOperation.READ)
    }

    override fun generate(specification: KeyGenParameterSpec): SecretKey =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(specification)
            generateKey()
        }

    override fun delete(alias: String) = store().deleteEntry(alias)
}

class AndroidKeystoreCryptography internal constructor(
    private val keys: KeystoreKeyAccess,
    private val access: StorageAccess,
    val unlockedAccessPolicy: KeystoreUnlockedAccessPolicy = KeystoreUnlockedAccessPolicy.forPlatform(Build.VERSION.SDK_INT),
) : SecretCryptography {
    internal constructor(context: android.content.Context) : this(
        FrameworkKeystoreKeyAccess(), AndroidStorageAccess(context, secrets = true),
    )

    override fun containsKey(alias: String): Boolean =
        platformCall(StorageOperation.READ) { keys.find(alias) != null }

    override fun createKeyIfAbsent(alias: String) {
        platformCall(StorageOperation.WRITE) {
            synchronized(creationLock) {
                if (keys.find(alias) == null) {
                    keys.generate(
                        KeyGenParameterSpec.Builder(
                            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                        )
                            .setKeySize(256)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .setRandomizedEncryptionRequired(true)
                            .setUserAuthenticationRequired(false)
                            // API31-34 adds an unintended PIN requirement and can delete keys on lock removal.
                            .setUnlockedDeviceRequired(unlockedAccessPolicy.requireUnlockedKeystoreKey)
                            .build(),
                    )
                }
            }
        }
    }

    override fun encrypt(alias: String, plaintext: ByteArray, authenticatedData: ByteArray): AuthenticatedCiphertext =
        platformCall(StorageOperation.WRITE) {
            val key = requireKey(alias, StorageOperation.WRITE)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            cipher.updateAAD(authenticatedData)
            val nonce = cipher.iv
            if (nonce.size != NONCE_BYTES) {
                throw StorageFailure(StorageProblem.ProviderFailure, StorageOperation.WRITE)
            }
            AuthenticatedCiphertext(Bytes(nonce), Bytes(cipher.doFinal(plaintext)))
        }

    override fun decrypt(alias: String, value: AuthenticatedCiphertext, authenticatedData: ByteArray): ByteArray =
        platformCall(StorageOperation.READ) {
            if (value.nonce.size != NONCE_BYTES || value.ciphertext.size < TAG_BYTES) {
                throw StorageFailure(StorageProblem.CorruptEnvelope, StorageOperation.READ)
            }
            val key = requireKey(alias, StorageOperation.READ)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BYTES * 8, value.nonce.toByteArray()))
            cipher.updateAAD(authenticatedData)
            cipher.doFinal(value.ciphertext.toByteArray())
        }

    override fun deleteKey(alias: String) = platformCall(StorageOperation.DELETE) { keys.delete(alias) }

    private fun requireKey(alias: String, operation: StorageOperation): SecretKey =
        keys.find(alias) ?: throw StorageFailure(StorageProblem.FormerKeyMissing, operation)

    private inline fun <T> platformCall(operation: StorageOperation, block: () -> T): T = try {
        access.requireAccessible(operation)
        block()
    } catch (failure: KeyPermanentlyInvalidatedException) {
        throw StorageFailure(StorageProblem.KeyPermanentlyInvalidated, operation, failure)
    } catch (failure: UserNotAuthenticatedException) {
        throw StorageFailure(StorageProblem.DeviceLocked, operation, failure)
    } catch (failure: AEADBadTagException) {
        throw StorageFailure(StorageProblem.AuthenticationFailed, operation, failure)
    } catch (failure: BadPaddingException) {
        throw StorageFailure(StorageProblem.AuthenticationFailed, operation, failure)
    } catch (failure: GeneralSecurityException) {
        throw StorageFailure(providerProblem(failure), operation, failure)
    } catch (failure: ProviderException) {
        throw StorageFailure(providerProblem(failure), operation, failure)
    } catch (failure: IOException) {
        throw StorageFailure(StorageProblem.IoFailure, operation, failure)
    } catch (failure: SecurityException) {
        throw StorageFailure(StorageProblem.PermissionDenied, operation, failure)
    }

    companion object {
        internal const val NONCE_BYTES = 12
        internal const val TAG_BYTES = 16
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val creationLock = Any()

        private fun providerProblem(failure: Throwable): StorageProblem {
            var current: Throwable? = failure
            val visited = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (current != null && visited.size < 16 && visited.add(current)) {
                when (current) {
                    is KeyPermanentlyInvalidatedException -> return StorageProblem.KeyPermanentlyInvalidated
                    is UserNotAuthenticatedException -> return StorageProblem.DeviceLocked
                    is AEADBadTagException -> return StorageProblem.AuthenticationFailed
                }
                current = current.cause
            }
            return StorageProblem.ProviderFailure
        }
    }
}
