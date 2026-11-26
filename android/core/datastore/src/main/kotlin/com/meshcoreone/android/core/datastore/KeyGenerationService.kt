// PortedFrom: MC1Services/Sources/MC1Services/Services/KeyGenerationService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.datastore

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.CryptoException
import com.meshcoreone.android.core.protocol.crypto.Ed25519Crypto
import java.security.ProviderException
import java.security.SecureRandom
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext

sealed class KeyGenerationFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MaxAttemptsExceeded : KeyGenerationFailure("Could not generate a key with that prefix. Try a different one.")
    class ReservedPrefix : KeyGenerationFailure("That prefix is reserved and cannot be used.")
    class RandomGenerationFailed(cause: Throwable? = null) :
        KeyGenerationFailure("Secure random number generation failed. Please try again.", cause)
    class InvalidKey(cause: Throwable? = null) : KeyGenerationFailure("The key is not a valid Ed25519 private key.", cause)
    class InvalidPrefix : KeyGenerationFailure("The prefix must contain at most four uppercase hexadecimal digits.")
}

fun interface SeedSource {
    fun nextSeed(): Bytes
}

class SecureSeedSource(private val random: SecureRandom = SecureRandom()) : SeedSource {
    override fun nextSeed(): Bytes {
        val seed = ByteArray(Ed25519Crypto.seedSize)
        try {
            random.nextBytes(seed)
            return Bytes(seed)
        } catch (failure: ProviderException) {
            throw KeyGenerationFailure.RandomGenerationFailed(failure)
        } finally {
            seed.fill(0)
        }
    }
}

class GeneratedIdentity internal constructor(val expandedPrivateKey: Bytes, val publicKey: Bytes) {
    override fun toString(): String = "GeneratedIdentity([REDACTED])"
}

class KeyGenerationService(
    private val seeds: SeedSource = SecureSeedSource(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun generateIdentity(hexPrefix: String?): GeneratedIdentity =
        withContext(dispatcher) { generateOnWorker(hexPrefix) }

    private suspend fun generateOnWorker(hexPrefix: String?): GeneratedIdentity {
        if (hexPrefix != null && (hexPrefix.length > 4 || hexPrefix.any { it !in '0'..'9' && it !in 'A'..'F' })) {
            throw KeyGenerationFailure.InvalidPrefix()
        }
        if (hexPrefix != null && hexPrefix.length >= 2 && (hexPrefix.startsWith("00") || hexPrefix.startsWith("FF"))) {
            throw KeyGenerationFailure.ReservedPrefix()
        }
        val attempts = maxAttempts(hexPrefix?.length ?: 0)
        repeat(attempts) { attempt ->
            currentCoroutineContext().ensureActive()
            if (attempt % 128 == 0) yield()
            val seed = seeds.nextSeed()
            if (seed.size != Ed25519Crypto.seedSize) throw KeyGenerationFailure.RandomGenerationFailed()
            val publicKey = Ed25519Crypto.publicKeyFromSeed(seed)
            if (publicKey[0] == 0.toUByte() || publicKey[0] == 255.toUByte()) return@repeat
            if (hexPrefix != null && !publicKey.hexUppercase().startsWith(hexPrefix)) return@repeat
            return GeneratedIdentity(Ed25519Crypto.expandSeed(seed), publicKey)
        }
        throw KeyGenerationFailure.MaxAttemptsExceeded()
    }

    companion object {
        fun validateExpandedKey(data: Bytes) {
            try {
                Ed25519Crypto.validateExpandedPrivateKey(data)
            } catch (failure: CryptoException.InvalidKeyLength) {
                throw KeyGenerationFailure.InvalidKey(failure)
            } catch (failure: CryptoException.InvalidExpandedPrivateKey) {
                throw KeyGenerationFailure.InvalidKey(failure)
            }
        }

        fun importExpandedKey(data: Bytes): GeneratedIdentity {
            validateExpandedKey(data)
            return GeneratedIdentity(data, Ed25519Crypto.publicKeyFromExpanded(data))
        }

        internal fun maxAttempts(prefixLength: Int): Int {
            require(prefixLength in 0..4)
            return if (prefixLength == 0) 10_000 else maxOf(10_000, (1 shl (prefixLength * 4)) * 20)
        }
    }
}

internal fun Bytes.hexUppercase(): String = joinToString("") { it.toString(16).uppercase().padStart(2, '0') }
