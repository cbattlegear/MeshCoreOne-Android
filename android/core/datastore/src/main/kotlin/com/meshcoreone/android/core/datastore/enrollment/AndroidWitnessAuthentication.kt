// AndroidOnly: WP-204 AndroidKeystore authentication; existing witness keys are never regenerated.
package com.meshcoreone.android.core.datastore.enrollment

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.meshcoreone.android.core.datastore.AndroidStorageAccess
import com.meshcoreone.android.core.datastore.StorageAccess
import com.meshcoreone.android.core.datastore.StorageFailure
import com.meshcoreone.android.core.datastore.StorageOperation
import com.meshcoreone.android.core.datastore.StorageProblem
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.UUID
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

internal interface WitnessAuthentication {
    fun requireAccessible()
    fun createNew(witnessId: UUID)
    fun authenticate(witnessId: UUID, bytes: ByteArray): ByteArray
}

internal class AndroidWitnessAuthentication(
    private val access: StorageAccess,
    private val keys: WitnessKeys = FrameworkWitnessKeys(),
) : WitnessAuthentication {
    constructor(context: Context) : this(AndroidStorageAccess(context, secrets = true))

    override fun requireAccessible() = access.requireAccessible(StorageOperation.READ)

    override fun createNew(witnessId: UUID) = platform {
        access.requireAccessible(StorageOperation.WRITE)
        val alias = alias(witnessId)
        // Never wrap createKeyIfAbsent: an occupied identity is a failure, even after a lost return.
        if (keys.find(alias) != null) {
            throw StorageFailure(StorageProblem.SecretAlreadyExists, StorageOperation.WRITE)
        }
        keys.generate(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                .setKeySize(256)
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setUserAuthenticationRequired(false)
                .setUnlockedDeviceRequired(Build.VERSION.SDK_INT >= 35)
                .build(),
        )
    }

    override fun authenticate(witnessId: UUID, bytes: ByteArray): ByteArray = platform {
        requireAccessible()
        val key = keys.find(alias(witnessId))
            ?: throw StorageFailure(StorageProblem.FormerKeyMissing, StorageOperation.READ)
        Mac.getInstance("HmacSHA256").run {
            init(key)
            doFinal(bytes)
        }
    }

    private inline fun <T> platform(action: () -> T): T = try {
        action()
    } catch (failure: android.security.keystore.UserNotAuthenticatedException) {
        throw StorageFailure(StorageProblem.DeviceLocked, StorageOperation.READ, failure)
    } catch (failure: android.security.keystore.KeyPermanentlyInvalidatedException) {
        throw StorageFailure(StorageProblem.KeyPermanentlyInvalidated, StorageOperation.READ, failure)
    } catch (failure: GeneralSecurityException) {
        throw StorageFailure(StorageProblem.ProviderFailure, StorageOperation.READ, failure)
    } catch (failure: ProviderException) {
        throw StorageFailure(StorageProblem.ProviderFailure, StorageOperation.READ, failure)
    } catch (failure: IOException) {
        throw StorageFailure(StorageProblem.IoFailure, StorageOperation.READ, failure)
    }

    private fun alias(id: UUID): String = "meshcoreone.notification-enrollment-witness.v1.$id"
}

internal interface WitnessKeys {
    fun find(alias: String): SecretKey?
    fun generate(specification: KeyGenParameterSpec)
}

internal class FrameworkWitnessKeys : WitnessKeys {
    private fun store(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun find(alias: String): SecretKey? {
        val store = store()
        if (!store.containsAlias(alias)) return null
        return store.getKey(alias, null) as? SecretKey
            ?: throw StorageFailure(StorageProblem.CorruptKeystoreKey, StorageOperation.READ)
    }

    override fun generate(specification: KeyGenParameterSpec) {
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore").run {
            init(specification)
            generateKey()
        }
    }
}
