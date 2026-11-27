// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Clock
import java.time.Duration

/**
 * Offline stand-in for [HttpPrizmClient] (debug setting): signs in a fake user and accepts any claim. Does no I/O. Its claim message
 * has the server's shape so the real validation and signing run against it. Never ship it as the production client.
 */
class StandInPrizmClient(
    private val clock: Clock = Clock.systemUTC(),
    private val random: RandomSource = SystemRandomSource,
    private val user: PrizmUser = PrizmUser("Stand-in Observer"),
) : PrizmClient {
    private val claimed = HashSet<String>()

    private fun session(): PrizmSession = PrizmSession("pza_standin_token", clock.instant().plus(TOKEN_LIFETIME), user)

    override suspend fun exchangeCode(code: String, codeVerifier: String) = session()
    override suspend fun claimedNodes(session: PrizmSession): Set<String> = synchronized(claimed) { claimed.toSet() }
    override suspend fun claimStart(session: PrizmSession, nodeKey: Bytes): ClaimChallenge {
        val nonce = Bytes(random.nextBytes(NONCE_BYTES)).hexString
        return ClaimChallenge(nonce, "prizm-claim:v1:${nodeKey.hexString.lowercase()}:100000000000000001:$nonce", clock.instant().plus(HttpPrizmClient.NONCE_LIFETIME))
    }
    override suspend fun claimFinish(session: PrizmSession, request: ClaimFinishRequest): ClaimFinishResponse {
        synchronized(claimed) { claimed += request.nodeKey.hexString.lowercase() }
        return ClaimFinishResponse.Linked
    }
    override suspend fun updateNode(session: PrizmSession, nodeKey: Bytes, nodeType: NodeType, deviceInfo: NodeDeviceInfo) = Unit
    override suspend fun removeNode(session: PrizmSession, nodeKey: Bytes) { synchronized(claimed) { claimed -= nodeKey.hexString.lowercase() } }
    override suspend fun deleteMyData(session: PrizmSession) = DataDeleteResult(0, 0)
    override suspend fun revoke(session: PrizmSession) = Unit

    companion object {
        const val NONCE_BYTES = 16
        val TOKEN_LIFETIME: Duration = Duration.ofDays(180)
    }
}
