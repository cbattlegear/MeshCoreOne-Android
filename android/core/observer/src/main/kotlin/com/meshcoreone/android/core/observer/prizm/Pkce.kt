// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

fun interface RandomSource {
    fun nextBytes(count: Int): ByteArray
}

object SystemRandomSource : RandomSource {
    private val secure = SecureRandom()
    override fun nextBytes(count: Int): ByteArray = ByteArray(count).also(secure::nextBytes)
}

private fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

/** RFC 7636 code verifier / S256 challenge pair. */
class Pkce(val codeVerifier: String, val codeChallenge: String) {
    override fun toString(): String = "Pkce(challenge=$codeChallenge)"

    companion object {
        const val CHALLENGE_METHOD = "S256"
        private const val VERIFIER_RANDOM_BYTES = 32 // 43 base64url characters, the RFC minimum

        fun generate(random: RandomSource = SystemRandomSource): Pkce {
            val verifier = base64Url(random.nextBytes(VERIFIER_RANDOM_BYTES))
            return Pkce(verifier, challengeFor(verifier))
        }

        /** BASE64URL-ENCODE(SHA256(ASCII(code_verifier))) without padding. */
        fun challengeFor(codeVerifier: String): String =
            base64Url(MessageDigest.getInstance("SHA-256").digest(codeVerifier.toByteArray(Charsets.US_ASCII)))

        fun newState(random: RandomSource = SystemRandomSource): String = base64Url(random.nextBytes(16))
    }
}

/**
 * [clientId], [redirectUri] and [scope] are NOT sent: the Prizm server has no client registry and fixes the redirect to
 * `https://mqtt.nodakmesh.org/app/callback` (contract "Sign-in"). They remain only so callers and the stand-in compile.
 */
data class PrizmOAuthConfig(
    val clientId: String,
    val redirectUri: String,
    val scope: String = "mqtt claim",
    val baseUrl: String = PrizmEndpoints.BASE_URL,
)

/** A pending authorization: open [url] in a browser, keep [pkce] and [state] until the redirect comes back. */
class AuthorizationRequest private constructor(val url: String, val pkce: Pkce, val state: String) {
    /** Returns the code, or throws [PrizmException] on an OAuth error or a state mismatch (CSRF). */
    fun codeFromCallback(parameters: Map<String, String>): String {
        parameters["error"]?.let { throw PrizmException("Authorization refused: $it") }
        if (parameters["state"] != state) throw PrizmException("Authorization state mismatch")
        return parameters["code"]?.takeIf { it.isNotEmpty() } ?: throw PrizmException("Authorization response had no code")
    }

    companion object {
        /** Rebuilds a pending request after the process was killed while the browser was open. */
        fun restore(url: String, codeVerifier: String, state: String): AuthorizationRequest =
            AuthorizationRequest(url, Pkce(codeVerifier, Pkce.challengeFor(codeVerifier)), state)

        fun create(config: PrizmOAuthConfig, random: RandomSource = SystemRandomSource): AuthorizationRequest {
            val pkce = Pkce.generate(random)
            val state = Pkce.newState(random)
            // Contract "Sign-in": only these three parameters; the redirect is fixed on the server (the App Link callback).
            val query = listOf(
                "code_challenge" to pkce.codeChallenge,
                "code_challenge_method" to Pkce.CHALLENGE_METHOD,
                "state" to state,
            ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
            return AuthorizationRequest("${config.baseUrl}${PrizmEndpoints.AUTHORIZE}?$query", pkce, state)
        }
    }
}
