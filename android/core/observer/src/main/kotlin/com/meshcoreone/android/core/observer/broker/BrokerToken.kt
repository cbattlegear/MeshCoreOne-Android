// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.broker

import com.meshcoreone.android.core.observer.claim.NodeSigner
import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Where the Prizm MQTT broker is (MQTT over WebSocket on TLS, subprotocol `mqtt`, path `/`; `GET /api/config/observer-join`). */
object BrokerEndpoint {
    const val HOST = "mqtt.nodakmesh.org"
    const val PORT = 443
    const val WEBSOCKET_PATH = "/"
    const val WEBSOCKET_SUBPROTOCOL = "mqtt"
}

/** MQTT username and password for one node. The password is a signed token and is never printed. */
class BrokerLogin(val username: String, val password: String, val issuedAt: Instant, val expiresAt: Instant) {
    override fun toString(): String = "BrokerLogin(username=$username, expiresAt=$expiresAt)"
}

/**
 * The broker password, as Prizm's fixed observers make it (contract "Broker login"): a JWT-shaped token
 * `<base64url(header)>.<base64url(payload)>.<hex(signature)>`, base64url without padding.
 *  - header `{"alg":"Ed25519","typ":"JWT"}`;
 *  - payload `{"publicKey":"<PK hex>","iat":<unix s>,"exp":<unix s>,"aud":"mqtt.nodakmesh.org"}`;
 *  - signature: the node signs (CMD_SIGN_*) the ASCII bytes `<base64url(header)>.<base64url(payload)>`, 64 bytes as 128 hex chars.
 * The server accepts exp - iat up to 168 hours and 5 minutes of clock skew. The username is `v1_<PK hex>`.
 * The key is written in upper case, as the server normalises it; case does not matter to the server.
 */
object BrokerToken {
    const val USERNAME_PREFIX = "v1_"
    const val AUDIENCE = BrokerEndpoint.HOST
    const val HEADER_JSON = """{"alg":"Ed25519","typ":"JWT"}"""
    const val NODE_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64
    val MAX_LIFETIME: Duration = Duration.ofHours(168)
    val DEFAULT_LIFETIME: Duration = Duration.ofHours(24)

    fun username(nodeKey: Bytes): String {
        require(nodeKey.size == NODE_KEY_BYTES) { "node key must be $NODE_KEY_BYTES bytes" }
        return USERNAME_PREFIX + nodeKey.hexString.uppercase()
    }

    /** The exact ASCII text the node signs. */
    fun signingInput(nodeKey: Bytes, issuedAt: Instant, expiresAt: Instant, audience: String = AUDIENCE): String {
        require(nodeKey.size == NODE_KEY_BYTES) { "node key must be $NODE_KEY_BYTES bytes" }
        require(expiresAt.isAfter(issuedAt)) { "exp must be after iat" }
        require(Duration.between(issuedAt, expiresAt) <= MAX_LIFETIME) { "token lifetime exceeds ${MAX_LIFETIME.toHours()} hours" }
        val payload = jsonObject {
            string("publicKey", nodeKey.hexString.uppercase())
            long("iat", issuedAt.epochSecond)
            long("exp", expiresAt.epochSecond)
            string("aud", audience)
        }
        return b64(HEADER_JSON.toByteArray(Charsets.US_ASCII)) + "." + b64(payload.toByteArray(Charsets.US_ASCII))
    }

    /** Signs through [signer] (the node, in bounded chunks) and assembles the login. Throws if the node refuses or returns a bad signature. */
    suspend fun build(
        nodeKey: Bytes,
        signer: NodeSigner,
        now: Instant,
        lifetime: Duration = DEFAULT_LIFETIME,
        audience: String = AUDIENCE,
    ): BrokerLogin {
        val issuedAt = Instant.ofEpochSecond(now.epochSecond)
        val expiresAt = issuedAt.plus(lifetime)
        val input = signingInput(nodeKey, issuedAt, expiresAt, audience)
        val signature = signer.sign(Bytes(input.toByteArray(Charsets.US_ASCII)))
        require(signature.size == SIGNATURE_BYTES) { "node returned a ${signature.size}-byte signature, expected $SIGNATURE_BYTES" }
        return BrokerLogin(username(nodeKey), input + "." + signature.hexString, issuedAt, expiresAt)
    }

    private fun b64(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

/**
 * Hands out a valid [BrokerLogin], signing a new token when none exists or the current one expires within [refreshMargin].
 * One signing at a time. A failed signing leaves the previous token in place (it may still be valid) and rethrows.
 */
class BrokerLoginProvider(
    private val nodeKey: Bytes,
    private val signer: NodeSigner,
    private val now: () -> Instant,
    private val lifetime: Duration = BrokerToken.DEFAULT_LIFETIME,
    private val refreshMargin: Duration = DEFAULT_REFRESH_MARGIN,
) {
    private val mutex = Mutex()
    private var cached: BrokerLogin? = null

    init { require(refreshMargin < lifetime) { "refresh margin must be shorter than the token lifetime" } }

    suspend fun current(): BrokerLogin = mutex.withLock {
        val existing = cached
        val at = now()
        if (existing != null && at.plus(refreshMargin).isBefore(existing.expiresAt)) return existing
        BrokerToken.build(nodeKey, signer, at, lifetime).also { cached = it }
    }

    /** When the next login should be fetched at the latest, or null before the first one. */
    fun refreshDueAt(): Instant? = cached?.expiresAt?.minus(refreshMargin)

    companion object { val DEFAULT_REFRESH_MARGIN: Duration = Duration.ofHours(2) }
}
