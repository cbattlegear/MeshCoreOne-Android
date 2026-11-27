// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.observer.json.MiniJson
import com.meshcoreone.android.core.observer.json.jsonObject
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.CancellationException

/**
 * The real Prizm client (contract "Sign-in" and "Claim"). Auth is `Authorization: Bearer pza_...` on every call except the token
 * exchange. Failures become [PrizmException]s with a short user-facing message; server bodies are parsed for known fields only and
 * never shown. [baseUrl] is read per call so the beta override applies without rebuilding the client.
 */
class HttpPrizmClient(
    private val baseUrl: () -> String,
    private val transport: HttpTransport,
    private val clock: Clock = Clock.systemUTC(),
) : PrizmClient {

    override suspend fun exchangeCode(code: String, codeVerifier: String): PrizmSession {
        val body = jsonObject { string("code", code); string("codeVerifier", codeVerifier) }
        val response = send("POST", PrizmEndpoints.TOKEN, null, body)
        if (response.status != 200) throw PrizmException(if (response.status in 400..499) SIGN_IN_REFUSED else serverProblem(response.status))
        val json = parse(response) as? Map<*, *> ?: throw badAnswer()
        val token = json["token"] as? String
        val expires = (json["expiresAt"] as? Double)?.let(::instantOf)
        val name = (json["user"] as? Map<*, *>)?.get("name") as? String
        if (token == null || !TOKEN.matches(token) || expires == null) throw badAnswer()
        return PrizmSession(token, expires, PrizmUser(name?.takeIf { it.isNotBlank() } ?: DEFAULT_NAME))
    }

    override suspend fun claimedNodes(session: PrizmSession): Set<String> {
        val response = send("GET", PrizmEndpoints.ME, session, null)
        if (response.status != 200) return emptySet()
        val user = (runCatching { MiniJson.parse(response.body) }.getOrNull() as? Map<*, *>)?.get("user") as? Map<*, *> ?: return emptySet()
        val nodes = user["nodes"] as? List<*> ?: return emptySet()
        return nodes.mapNotNull { node ->
            val key = when (node) {
                is String -> node
                is Map<*, *> -> (node["pk"] ?: node["publicKey"] ?: node["public_key"]) as? String
                else -> null
            }
            key?.lowercase()?.takeIf { KEY_HEX.matches(it) }
        }.toSet()
    }

    override suspend fun claimStart(session: PrizmSession, nodeKey: Bytes): ClaimChallenge {
        val response = send("POST", PrizmEndpoints.CLAIM_START, session, jsonObject { string("pk", nodeKey.hexString.lowercase()) })
        when (response.status) {
            200 -> Unit
            400 -> throw PrizmException("Prizm did not accept this node's key.")
            403 -> throw PrizmException("Prizm does not allow this node to be claimed.")
            429 -> throw PrizmException(TOO_MANY)
            else -> throw PrizmException(serverProblem(response.status))
        }
        val json = parse(response) as? Map<*, *> ?: throw badAnswer()
        val nonce = json["nonce"] as? String
        val message = json["message"] as? String
        if (nonce.isNullOrEmpty() || message.isNullOrEmpty()) throw badAnswer()
        val expires = (json["expiresAt"] as? Double)?.let(::instantOf) ?: clock.instant().plus(NONCE_LIFETIME)
        return ClaimChallenge(nonce, message, expires)
    }

    override suspend fun claimFinish(session: PrizmSession, request: ClaimFinishRequest): ClaimFinishResponse {
        val response = send("POST", PrizmEndpoints.CLAIM_FINISH, session, request.toJson())
        return when (response.status) {
            200 -> ClaimFinishResponse.Linked
            409 -> ClaimFinishResponse.ClaimedByOther(
                ((runCatching { MiniJson.parse(response.body) }.getOrNull() as? Map<*, *>)?.get("ownedBy") as? String)?.takeIf { it.isNotBlank() },
            )
            410 -> ClaimFinishResponse.NonceExpired
            400 -> ClaimFinishResponse.Rejected("Prizm did not accept the signature or the node details.")
            429 -> throw PrizmException(TOO_MANY)
            else -> throw PrizmException(serverProblem(response.status))
        }
    }

    override suspend fun updateNode(session: PrizmSession, nodeKey: Bytes, nodeType: NodeType, deviceInfo: NodeDeviceInfo) {
        val body = jsonObject {
            string("pk", nodeKey.hexString.lowercase())
            string("nodeType", nodeType.wireName)
            raw("device", deviceInfo.toJson())
        }
        expectOk(send("PUT", PrizmEndpoints.NODE_UPDATE, session, body), notFound = NOT_YOURS)
    }

    override suspend fun removeNode(session: PrizmSession, nodeKey: Bytes) {
        expectOk(send("DELETE", "${PrizmEndpoints.NODE_REMOVE}/${nodeKey.hexString.lowercase()}", session, null), notFound = NOT_YOURS)
    }

    override suspend fun deleteMyData(session: PrizmSession): DataDeleteResult {
        val response = send("POST", PrizmEndpoints.DATA_DELETE, session, "{}")
        expectOk(response, notFound = NOT_YOURS)
        val json = runCatching { MiniJson.parse(response.body) }.getOrNull() as? Map<*, *>
        return DataDeleteResult(((json?.get("queued") as? Double) ?: 0.0).toInt(), ((json?.get("nodes") as? Double) ?: 0.0).toInt())
    }

    override suspend fun revoke(session: PrizmSession) {
        val response = send("POST", PrizmEndpoints.LOGOUT, session, "{}")
        if (response.status !in 200..299 && response.status != 401) throw PrizmException(serverProblem(response.status))
    }

    // --- internals ---

    private fun expectOk(response: HttpResponse, notFound: String) {
        when (response.status) {
            in 200..299 -> Unit
            403, 404 -> throw PrizmException(notFound)
            429 -> throw PrizmException(TOO_MANY)
            else -> throw PrizmException(serverProblem(response.status))
        }
    }

    private suspend fun send(method: String, path: String, session: PrizmSession?, body: String?): HttpResponse {
        val headers = buildMap {
            put("Accept", "application/json")
            if (body != null) put("Content-Type", "application/json")
            if (session != null) put("Authorization", "Bearer ${session.accessToken}")
        }
        val response = try {
            transport.execute(HttpRequest(method, base() + path, headers, body))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: IOException) {
            throw PrizmException("Prizm could not be reached. Check your connection and try again.", error)
        }
        if (response.status == 401 && session != null) throw PrizmUnauthorizedException()
        return response
    }

    private fun base(): String = baseUrl().trimEnd('/')

    private fun parse(response: HttpResponse): Any? = try {
        MiniJson.parse(response.body)
    } catch (_: IllegalArgumentException) {
        throw badAnswer()
    }

    private fun badAnswer() = PrizmException("Prizm sent an answer this app does not understand.")
    private fun serverProblem(status: Int) = if (status >= 500) "Prizm had a problem. Try again later." else "Prizm refused the request (code $status)."

    /** Unix seconds; a value above 1e11 is taken as milliseconds. */
    private fun instantOf(value: Double): Instant =
        if (value > 1e11) Instant.ofEpochMilli(value.toLong()) else Instant.ofEpochSecond(value.toLong())

    companion object {
        /** Contract "Claim": nonces last 5 minutes (used when the answer has no usable `expiresAt`). */
        val NONCE_LIFETIME: Duration = Duration.ofMinutes(5)
        const val DEFAULT_NAME = "Prizm user"
        private const val SIGN_IN_REFUSED = "Prizm refused the sign-in. Start again."
        private const val TOO_MANY = "Too many attempts. Wait a minute and try again."
        private const val NOT_YOURS = "Prizm does not have this node claimed by your account."
        private val TOKEN = Regex("^pza_[A-Za-z0-9_-]{8,200}$")
        private val KEY_HEX = Regex("^[0-9a-f]{64}$")
    }
}
