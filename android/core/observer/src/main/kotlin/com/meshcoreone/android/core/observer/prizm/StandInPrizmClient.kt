// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Clock
import java.time.Duration

/**
 * Used until Prizm's endpoints exist: signs in a fake user and accepts any claim. Does no I/O. Never ship it as the production client.
 */
class StandInPrizmClient(
    private val clock: Clock = Clock.systemUTC(),
    private val random: RandomSource = SystemRandomSource,
    private val user: PrizmUser = PrizmUser("100000000000000001", "Stand-in Observer"),
) : PrizmClient {
    private fun session(): PrizmSession =
        PrizmSession("standin-access", "standin-refresh", clock.instant().plus(TOKEN_LIFETIME), user)

    override suspend fun exchangeCode(code: String, codeVerifier: String, redirectUri: String) = session()
    override suspend fun refresh(session: PrizmSession) = session()
    override suspend fun claimStart(session: PrizmSession, nodeKey: Bytes, confirmTakeover: Boolean): ClaimStartResponse =
        ClaimStartResponse.Challenge(Bytes(random.nextBytes(NONCE_BYTES)), clock.instant().plus(NONCE_LIFETIME))
    override suspend fun claimFinish(session: PrizmSession, request: ClaimFinishRequest): ClaimFinishResponse =
        if (request.signature == null) ClaimFinishResponse.InfoUpdated else ClaimFinishResponse.Linked
    override suspend fun revoke(session: PrizmSession) = Unit

    companion object {
        const val NONCE_BYTES = 32
        val NONCE_LIFETIME: Duration = Duration.ofMinutes(2)
        val TOKEN_LIFETIME: Duration = Duration.ofHours(1)
    }
}
