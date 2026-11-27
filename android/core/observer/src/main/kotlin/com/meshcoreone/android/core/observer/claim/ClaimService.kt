// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.observer.prizm.ClaimFinishRequest
import com.meshcoreone.android.core.observer.prizm.ClaimFinishResponse
import com.meshcoreone.android.core.observer.prizm.ClaimStartResponse
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
    /** Already linked to this user; only the node type and device info were updated (no signing). */
    data object InfoUpdated : ClaimOutcome
    /** Linked to someone else. Ask the user, then call again with confirmTakeover = true. */
    class NeedsConfirmation(val ownerLabel: String?) : ClaimOutcome
    /** The two minute nonce ran out (before or after signing). Start over. */
    data object NonceExpired : ClaimOutcome
    /** Prizm refused the claim (for example a bad signature). */
    class Rejected(val reason: String) : ClaimOutcome
    /** The node could not sign (BLE drop, node error, buffer). */
    class NodeSigningFailed(val cause: MeshCoreException) : ClaimOutcome
}

/** Pure claim flow: claimStart -> node signs -> claimFinish. Network and node are injected. */
class ClaimService(
    private val client: PrizmClient,
    private val signer: NodeSigner,
    private val clock: ClaimClock,
) {
    /** @throws com.meshcoreone.android.core.observer.prizm.PrizmException on network failure. */
    suspend fun claim(
        session: PrizmSession,
        nodeKey: Bytes,
        nodeType: NodeType,
        deviceInfo: NodeDeviceInfo,
        confirmTakeover: Boolean = false,
    ): ClaimOutcome {
        require(nodeKey == deviceInfo.publicKey) { "device info belongs to a different node" }
        return when (val start = client.claimStart(session, nodeKey, confirmTakeover)) {
            ClaimStartResponse.AlreadyLinkedToYou -> {
                val request = ClaimFinishRequest(nodeKey, null, null, nodeType, deviceInfo, confirmTakeover)
                finish(session, request, signed = false)
            }
            is ClaimStartResponse.ClaimedByOther -> ClaimOutcome.NeedsConfirmation(start.ownerLabel)
            is ClaimStartResponse.Challenge -> signAndFinish(session, nodeKey, nodeType, deviceInfo, confirmTakeover, start)
        }
    }

    private suspend fun signAndFinish(
        session: PrizmSession,
        nodeKey: Bytes,
        nodeType: NodeType,
        deviceInfo: NodeDeviceInfo,
        confirmTakeover: Boolean,
        challenge: ClaimStartResponse.Challenge,
    ): ClaimOutcome {
        if (expired(challenge)) return ClaimOutcome.NonceExpired
        val message = ClaimMessage.build(nodeKey, session.user.discordUserId, challenge.nonce)
        val signature = try {
            signer.sign(message)
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
        return finish(session, request, signed = true)
    }

    private suspend fun finish(session: PrizmSession, request: ClaimFinishRequest, signed: Boolean): ClaimOutcome =
        when (val response = client.claimFinish(session, request)) {
            ClaimFinishResponse.Linked -> if (signed) ClaimOutcome.Linked else ClaimOutcome.InfoUpdated
            ClaimFinishResponse.InfoUpdated -> ClaimOutcome.InfoUpdated
            ClaimFinishResponse.NonceExpired -> ClaimOutcome.NonceExpired
            is ClaimFinishResponse.ClaimedByOther -> ClaimOutcome.NeedsConfirmation(response.ownerLabel)
            is ClaimFinishResponse.Rejected -> ClaimOutcome.Rejected(response.reason)
        }

    private fun expired(challenge: ClaimStartResponse.Challenge): Boolean = !clock.now().isBefore(challenge.expiresAt)

    private companion object {
        const val SIGNATURE_BYTES = 64
    }
}
