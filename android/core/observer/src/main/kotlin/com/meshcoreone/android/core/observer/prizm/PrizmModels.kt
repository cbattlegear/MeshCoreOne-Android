// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import java.time.Instant

data class PrizmUser(val discordUserId: String, val displayName: String)

/** OAuth tokens plus the signed-in user. Tokens never appear in [toString]. */
class PrizmSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAt: Instant,
    val user: PrizmUser,
) {
    fun needsRefresh(now: Instant, skew: Duration = Duration.ofSeconds(60)): Boolean = !now.plus(skew).isBefore(expiresAt)
    override fun toString(): String = "PrizmSession(user=${user.discordUserId}, expiresAt=$expiresAt)"
}

/** Broker login for one node. Either a password or a JWT goes in [secret]; it is never printed. */
class MqttCredentials(
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val username: String,
    val secret: String,
    val topicPrefix: String,
) {
    override fun toString(): String = "MqttCredentials(host=$host:$port, tls=$useTls, username=$username)"
}

sealed interface ClaimStartResponse {
    /** Sign the claim message with [nonce] before [expiresAt] (about two minutes after issue). */
    class Challenge(val nonce: Bytes, val expiresAt: Instant) : ClaimStartResponse
    /** This key is already linked to the signed-in user: skip signing and just update the info. */
    data object AlreadyLinkedToYou : ClaimStartResponse
    /** Linked to someone else; the user must confirm before it is taken over. */
    class ClaimedByOther(val ownerLabel: String?) : ClaimStartResponse
}

/** [signature] is null only when the key is already linked to the user and only the info is being updated. */
class ClaimFinishRequest(
    val nodeKey: Bytes,
    val nonce: Bytes?,
    val signature: Bytes?,
    val nodeType: NodeType,
    val deviceInfo: NodeDeviceInfo,
    val confirmTakeover: Boolean,
) {
    fun toJson(): String = jsonObject {
        string("node_key", nodeKey.hexString)
        if (nonce != null) string("nonce", nonce.hexString)
        if (signature != null) string("signature", signature.hexString)
        string("node_type", nodeType.wireName)
        raw("device", deviceInfo.toJson())
        bool("confirm_takeover", confirmTakeover)
    }
}

sealed interface ClaimFinishResponse {
    data object Linked : ClaimFinishResponse
    data object InfoUpdated : ClaimFinishResponse
    data object NonceExpired : ClaimFinishResponse
    class ClaimedByOther(val ownerLabel: String?) : ClaimFinishResponse
    class Rejected(val reason: String) : ClaimFinishResponse
}
