// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.observer.prizm.ClaimChallenge
import com.meshcoreone.android.core.observer.prizm.ClaimFinishRequest
import com.meshcoreone.android.core.observer.prizm.ClaimFinishResponse
import com.meshcoreone.android.core.observer.prizm.PrizmClient
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.time.Instant

fun interface ClaimClock {
    fun now(): Instant
}

sealed interface ClaimOutcome {
    /** Newly linked after the node signed the challenge. */
    data object Linked : ClaimOutcome
    /** Already claimed by this user; only the node type and device info were updated (no signing). */
    data object InfoUpdated : ClaimOutcome
    /** Linked to someone else. Ask the user, then call again with confirmTakeover = true. */
    class NeedsConfirmation(val ownerLabel: String?) : ClaimOutcome
    /** The nonce (5 minutes) ran out, before or after signing. Start over. */
    data object NonceExpired : ClaimOutcome
    /** Prizm refused the claim (for example a bad signature), or sent a message this app will not sign. */
    class Rejected(val reason: String) : ClaimOutcome
    /** The node could not sign (BLE drop, node error, buffer). */
    class NodeSigningFailed(val cause: MeshCoreException) : ClaimOutcome
}

/**
 * Claim flow: (already claimed by me -> PUT node) | claim/start -> node signs the returned `message` exactly -> claim/finish.
 * A claim owned by someone else is reported at finish (409); the caller asks the user and calls again with
 * `confirmTakeover = true`, which starts over with a fresh nonce (one more signing). Network and node are injected.
 */
class ClaimService(
    private val client: PrizmClient,
    private val signer: NodeSigner,
    private val clock: ClaimClock,
) {
    /** @throws com.meshcoreone.android.core.observer.prizm.PrizmException on network failure or 401. */
    suspend fun claim(
        session: PrizmSession,
        nodeKey: Bytes,
        nodeType: NodeType,
        deviceInfo: NodeDeviceInfo,
        confirmTakeover: Boolean = false,
    ): ClaimOutcome {
        require(nodeKey == deviceInfo.publicKey) { "device info belongs to a different node" }
        if (!confirmTakeover && nodeKey.hexString.lowercase() in client.claimedNodes(session)) {
            client.updateNode(session, nodeKey, nodeType, deviceInfo)
            return ClaimOutcome.InfoUpdated
        }
        val challenge = client.claimStart(session, nodeKey)
        if (!ClaimMessage.validate(challenge.message, nodeKey, challenge.nonce)) {
            return ClaimOutcome.Rejected("Prizm sent a claim message this app does not recognise, so the node was not asked to sign it.")
        }
        return signAndFinish(session, nodeKey, nodeType, deviceInfo, confirmTakeover, challenge)
    }

    private suspend fun signAndFinish(
        session: PrizmSession,
        nodeKey: Bytes,
        nodeType: NodeType,
        deviceInfo: NodeDeviceInfo,
        confirmTakeover: Boolean,
        challenge: ClaimChallenge,
    ): ClaimOutcome {
        if (expired(challenge)) return ClaimOutcome.NonceExpired
        val signature = try {
            signer.sign(ClaimMessage.bytes(challenge.message))
        } catch (error: MeshCoreException) {
            return ClaimOutcome.NodeSigningFailed(error)
        }
        if (signature.size != SIGNATURE_BYTES) {
            return ClaimOutcome.NodeSigningFailed(
                MeshCoreException.InvalidResponse("$SIGNATURE_BYTES-byte Ed25519 signature", "${signature.size} bytes"),
            )
        }
        // Signing over BLE can be slow; do not spend a request on a nonce that has already lapsed.
        if (expired(challenge)) return ClaimOutcome.NonceExpired
        val request = ClaimFinishRequest(nodeKey, challenge.nonce, signature, nodeType, deviceInfo, confirmTakeover)
        return when (val response = client.claimFinish(session, request)) {
            ClaimFinishResponse.Linked -> ClaimOutcome.Linked
            ClaimFinishResponse.NonceExpired -> ClaimOutcome.NonceExpired
            is ClaimFinishResponse.ClaimedByOther -> ClaimOutcome.NeedsConfirmation(response.ownerLabel)
            is ClaimFinishResponse.Rejected -> ClaimOutcome.Rejected(response.reason)
        }
    }

    private fun expired(challenge: ClaimChallenge): Boolean = !clock.now().isBefore(challenge.expiresAt)

    private companion object {
        const val SIGNATURE_BYTES = 64
    }
}
