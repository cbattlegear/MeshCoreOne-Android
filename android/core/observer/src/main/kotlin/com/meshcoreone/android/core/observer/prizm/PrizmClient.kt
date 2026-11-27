// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * Transport failure or refusal talking to Prizm. [message] is written for the user (never a raw server body or stack text).
 */
open class PrizmException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The token is missing, revoked or expired (HTTP 401): there is no refresh, the user must sign in again. */
class PrizmUnauthorizedException(message: String = "Your Prizm sign-in has expired. Sign in again.") : PrizmException(message)

/**
 * Everything the app needs from Prizm, behind one interface so tests and the stand-in never touch the network.
 * See [PrizmEndpoints] for the paths ([HttpPrizmClient] is the real implementation). Every call that takes a [PrizmSession]
 * throws [PrizmUnauthorizedException] on 401.
 */
interface PrizmClient {
    /** Completes the browser sign-in: `POST /api/prizm/app/token {"code","codeVerifier"}`. */
    suspend fun exchangeCode(code: String, codeVerifier: String): PrizmSession

    /** Lowercase hex keys of the nodes this user has claimed (`GET /api/prizm/me`, `user.nodes`); empty when unknown. */
    suspend fun claimedNodes(session: PrizmSession): Set<String>

    suspend fun claimStart(session: PrizmSession, nodeKey: Bytes): ClaimChallenge
    suspend fun claimFinish(session: PrizmSession, request: ClaimFinishRequest): ClaimFinishResponse

    /** Update a node this user already claimed, no signature (`PUT /api/prizm/app/node`). */
    suspend fun updateNode(session: PrizmSession, nodeKey: Bytes, nodeType: NodeType, deviceInfo: NodeDeviceInfo)

    /** Stop claiming a node; ends its broker access, its uploaded data stays (`DELETE /api/prizm/app/node/<pk>`). */
    suspend fun removeNode(session: PrizmSession, nodeKey: Bytes)

    /** Queue deletion of everything the user's claimed nodes uploaded (`POST /api/prizm/app/data/delete`). */
    suspend fun deleteMyData(session: PrizmSession): DataDeleteResult

    /** Sign out: revoke the token server side (`POST /api/prizm/app/logout`). */
    suspend fun revoke(session: PrizmSession)
}
