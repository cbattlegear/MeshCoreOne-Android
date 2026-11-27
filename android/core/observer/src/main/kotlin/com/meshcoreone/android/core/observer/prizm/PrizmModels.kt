// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant

/** The signed-in Prizm user. The server returns only a display name (no Discord id). */
data class PrizmUser(val displayName: String)

/**
 * The app token (`pza_...`) and its expiry. Tokens last 180 days and there is no refresh token: when the token is expired or the
 * server answers 401 the user signs in again. The token never appears in [toString].
 */
class PrizmSession(val accessToken: String, val expiresAt: Instant, val user: PrizmUser) {
    fun isExpired(now: Instant): Boolean = !now.isBefore(expiresAt)
    override fun toString(): String = "PrizmSession(user=${user.displayName}, expiresAt=$expiresAt)"
}

/** `claim/start` answer. [message] is signed exactly as returned; [nonce] is opaque and sent back on finish. */
class ClaimChallenge(val nonce: String, val message: String, val expiresAt: Instant)

/** Body of `claim/finish`: `{"pk","nonce","signature","nodeType","device","takeOver"}`. */
class ClaimFinishRequest(
    val nodeKey: Bytes,
    val nonce: String,
    val signature: Bytes,
    val nodeType: NodeType,
    val deviceInfo: NodeDeviceInfo,
    val takeOver: Boolean,
) {
    fun toJson(): String = jsonObject {
        string("pk", nodeKey.hexString.lowercase())
        string("nonce", nonce)
        string("signature", signature.hexString)
        string("nodeType", nodeType.wireName)
        raw("device", deviceInfo.toJson())
        bool("takeOver", takeOver)
    }
}

sealed interface ClaimFinishResponse {
    data object Linked : ClaimFinishResponse
    data object NonceExpired : ClaimFinishResponse
    /** 409 `owned`: the nonce stays valid on the server, but the app simply claims again after the user confirms. */
    class ClaimedByOther(val ownerLabel: String?) : ClaimFinishResponse
    class Rejected(val reason: String) : ClaimFinishResponse
}

/** What `POST /api/prizm/app/data/delete` queued. */
data class DataDeleteResult(val queued: Int, val nodes: Int)
