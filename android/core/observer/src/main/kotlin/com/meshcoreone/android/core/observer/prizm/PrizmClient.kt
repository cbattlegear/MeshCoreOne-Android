// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.protocol.bytes.Bytes

/** Transport failure talking to Prizm (offline, 5xx, malformed body). Auth or claim refusals are results, not this. */
class PrizmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Everything the app needs from Prizm, behind one interface so tests and the stand-in never touch the network.
 * The real HTTP implementation is not written yet; see [PrizmEndpoints] for the paths. There is no broker-credentials call: the
 * MQTT password is a token the node signs itself ([com.meshcoreone.android.core.observer.broker.BrokerToken]).
 */
interface PrizmClient {
    /** Completes the authorization-code flow started with [AuthorizationRequest]. */
    suspend fun exchangeCode(code: String, codeVerifier: String, redirectUri: String): PrizmSession
    suspend fun refresh(session: PrizmSession): PrizmSession
    suspend fun claimStart(session: PrizmSession, nodeKey: Bytes, confirmTakeover: Boolean = false): ClaimStartResponse
    suspend fun claimFinish(session: PrizmSession, request: ClaimFinishRequest): ClaimFinishResponse

    /** Sign out: revoke the refresh and access tokens server side. */
    suspend fun revoke(session: PrizmSession)
}
