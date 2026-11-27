// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.observer.claim.ClaimFixtures
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.observer.json.MiniJson
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A scripted in-process "server": routes by "METHOD path"; records every request. */
private class FakeServer(private val routes: MutableMap<String, (HttpRequest) -> HttpResponse> = mutableMapOf()) : HttpTransport {
    val requests = mutableListOf<HttpRequest>()
    var unreachable = false
    fun on(route: String, handler: (HttpRequest) -> HttpResponse) { routes[route] = handler }
    fun reply(route: String, status: Int, body: String = "{}") = on(route) { HttpResponse(status, body) }
    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        if (unreachable) throw IOException("connect failed: secret-host:1234")
        val path = request.url.removePrefix("https://beta.example")
        return (routes["${request.method} $path"] ?: { HttpResponse(404, "{}") })(request)
    }
}

class HttpPrizmClientTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC)
    private val server = FakeServer()
    private var base = "https://beta.example/"
    private val client = HttpPrizmClient({ base }, server, clock)
    private val session = PrizmSession("pza_abcdefgh12345678", Instant.parse("2027-05-01T00:00:00Z"), PrizmUser("u"))
    private val key = ClaimFixtures.nodeKey

    private fun <T> run(block: suspend () -> T): T = runBlocking { block() }
    private fun body(r: HttpRequest) = MiniJson.parse(r.body!!) as Map<*, *>

    @Test fun tokenExchangePostsCodeAndVerifierWithoutAuthAndParsesTheSession() {
        server.on("POST /api/prizm/app/token") { HttpResponse(200, """{"token":"pza_abcdefgh12345678","expiresAt":1795000000,"user":{"name":"Josh"}}""") }
        val s = run { client.exchangeCode("the-code", "the-verifier") }
        assertEquals("pza_abcdefgh12345678", s.accessToken); assertEquals(Instant.ofEpochSecond(1795000000), s.expiresAt)
        assertEquals("Josh", s.user.displayName)
        val request = server.requests.single()
        assertEquals("https://beta.example/api/prizm/app/token", request.url, "base URL override, no double slash")
        assertEquals(mapOf("code" to "the-code", "codeVerifier" to "the-verifier"), body(request))
        assertFalse(request.headers.containsKey("Authorization"))
        assertEquals("application/json", request.headers["Content-Type"])
    }

    @Test fun badTokenAnswersAreRefusedWithUserMessages() {
        server.reply("POST /api/prizm/app/token", 400)
        assertEquals("Prizm refused the sign-in. Start again.", assertFailsWith<PrizmException> { run { client.exchangeCode("c", "v") } }.message)
        server.reply("POST /api/prizm/app/token", 200, """{"token":"not-a-token","expiresAt":1,"user":{}}""")
        assertFailsWith<PrizmException> { run { client.exchangeCode("c", "v") } }
        server.reply("POST /api/prizm/app/token", 200, "<html>")
        assertFailsWith<PrizmException> { run { client.exchangeCode("c", "v") } }
        server.reply("POST /api/prizm/app/token", 503, "stack trace secret")
        assertEquals("Prizm had a problem. Try again later.", assertFailsWith<PrizmException> { run { client.exchangeCode("c", "v") } }.message)
    }

    @Test fun networkFailureBecomesAFriendlyMessageWithoutTheRawError() {
        server.unreachable = true
        val e = assertFailsWith<PrizmException> { run { client.claimStart(session, key) } }
        assertEquals("Prizm could not be reached. Check your connection and try again.", e.message)
        assertFalse(e.message!!.contains("secret-host"))
    }

    @Test fun authedCallsSendTheBearerTokenAndA401MeansSignInAgain() {
        server.reply("POST /api/prizm/app/claim/start", 401, "token revoked")
        val e = assertFailsWith<PrizmUnauthorizedException> { run { client.claimStart(session, key) } }
        assertEquals("Your Prizm sign-in has expired. Sign in again.", e.message)
        assertEquals("Bearer pza_abcdefgh12345678", server.requests.single().headers["Authorization"])
    }

    @Test fun claimStartSendsLowercasePkAndReadsNonceMessageAndExpiry() {
        server.on("POST /api/prizm/app/claim/start") { HttpResponse(200, """{"nonce":"n0nce-abcdef","message":"prizm-claim:v1:x:1:n0nce-abcdef","expiresAt":1795000300}""") }
        val c = run { client.claimStart(session, key) }
        assertEquals("n0nce-abcdef", c.nonce); assertEquals("prizm-claim:v1:x:1:n0nce-abcdef", c.message)
        assertEquals(Instant.ofEpochSecond(1795000300), c.expiresAt)
        assertEquals(mapOf("pk" to key.hexString), body(server.requests.single()))
    }

    @Test fun claimStartWithoutExpiryUsesTheFiveMinuteNonceLifetime() {
        server.reply("POST /api/prizm/app/claim/start", 200, """{"nonce":"n0nce-abcdef","message":"m"}""")
        assertEquals(clock.instant().plusSeconds(300), run { client.claimStart(session, key) }.expiresAt)
        server.reply("POST /api/prizm/app/claim/start", 200, """{"nonce":"","message":"m"}""")
        assertFailsWith<PrizmException> { run { client.claimStart(session, key) } }
    }

    @Test fun claimStartErrorsMapToMessages() {
        for ((status, text) in listOf(400 to "key", 403 to "allow", 429 to "Too many", 500 to "problem")) {
            server.reply("POST /api/prizm/app/claim/start", status, "raw")
            val message = assertFailsWith<PrizmException> { run { client.claimStart(session, key) } }.message!!
            assertTrue(message.contains(text), message); assertFalse(message.contains("raw"))
        }
    }

    private fun finishRequest(takeOver: Boolean = false) =
        ClaimFinishRequest(key, "n0nce-abcdef", Bytes(ByteArray(64) { 1 }), NodeType.VEHICLE, ClaimFixtures.deviceInfo, takeOver)

    @Test fun claimFinishMapsEveryContractStatus() {
        server.reply("POST /api/prizm/app/claim/finish", 200, """{"claimed":true}""")
        assertEquals(ClaimFinishResponse.Linked, run { client.claimFinish(session, finishRequest()) })
        server.reply("POST /api/prizm/app/claim/finish", 409, """{"error":"owned","ownedBy":"Alex"}""")
        assertEquals("Alex", assertIs<ClaimFinishResponse.ClaimedByOther>(run { client.claimFinish(session, finishRequest()) }).ownerLabel)
        server.reply("POST /api/prizm/app/claim/finish", 409, "garbage")
        assertEquals(null, assertIs<ClaimFinishResponse.ClaimedByOther>(run { client.claimFinish(session, finishRequest()) }).ownerLabel)
        server.reply("POST /api/prizm/app/claim/finish", 410)
        assertEquals(ClaimFinishResponse.NonceExpired, run { client.claimFinish(session, finishRequest()) })
        server.reply("POST /api/prizm/app/claim/finish", 400, "raw-body")
        val rejected = assertIs<ClaimFinishResponse.Rejected>(run { client.claimFinish(session, finishRequest()) })
        assertFalse(rejected.reason.contains("raw-body"))
        server.reply("POST /api/prizm/app/claim/finish", 500)
        assertFailsWith<PrizmException> { run { client.claimFinish(session, finishRequest()) } }
    }

    @Test fun claimFinishBodyUsesTheContractKeysAndTakeOver() {
        server.reply("POST /api/prizm/app/claim/finish", 200)
        run { client.claimFinish(session, finishRequest(takeOver = true)) }
        val b = body(server.requests.single())
        assertEquals(setOf("pk", "nonce", "signature", "nodeType", "device", "takeOver"), b.keys)
        assertEquals("vehicle", b["nodeType"]); assertEquals(true, b["takeOver"]); assertEquals("01".repeat(64), b["signature"])
    }

    @Test fun updateRemoveAndDeleteUseTheOwnerEndpoints() {
        server.reply("PUT /api/prizm/app/node", 200)
        run { client.updateNode(session, key, NodeType.HOME_BASE, ClaimFixtures.deviceInfo) }
        val put = server.requests.last()
        assertEquals("PUT", put.method); assertEquals(setOf("pk", "nodeType", "device"), body(put).keys); assertEquals("home", body(put)["nodeType"])
        server.reply("DELETE /api/prizm/app/node/${key.hexString}", 200)
        run { client.removeNode(session, key) }
        assertEquals("DELETE", server.requests.last().method)
        server.reply("POST /api/prizm/app/data/delete", 200, """{"queued":42,"nodes":2}""")
        assertEquals(DataDeleteResult(42, 2), run { client.deleteMyData(session) })
        server.reply("DELETE /api/prizm/app/node/${key.hexString}", 404, "no")
        assertEquals("Prizm does not have this node claimed by your account.", assertFailsWith<PrizmException> { run { client.removeNode(session, key) } }.message)
    }

    @Test fun deleteMyDataToleratesAnEmptyBody() {
        server.reply("POST /api/prizm/app/data/delete", 200, "")
        assertEquals(DataDeleteResult(0, 0), run { client.deleteMyData(session) })
    }

    @Test fun claimedNodesReadsKeysFromStringsOrObjectsAndIgnoresJunk() {
        val other = "ab".repeat(32)
        server.reply("GET /api/prizm/me", 200, """{"user":{"name":"x","nodes":["${key.hexString.uppercase()}",{"pk":"$other"},{"x":1},"short",5]}}""")
        assertEquals(setOf(key.hexString, other), run { client.claimedNodes(session) })
        server.reply("GET /api/prizm/me", 500); assertTrue(run { client.claimedNodes(session) }.isEmpty())
        server.reply("GET /api/prizm/me", 200, "nope"); assertTrue(run { client.claimedNodes(session) }.isEmpty())
        server.reply("GET /api/prizm/me", 401)
        assertFailsWith<PrizmUnauthorizedException> { run { client.claimedNodes(session) } }
    }

    @Test fun logoutRevokesAndTreatsAnAlreadyRevokedTokenAsDone() {
        server.reply("POST /api/prizm/app/logout", 200); run { client.revoke(session) }
        server.reply("POST /api/prizm/app/logout", 401)
        assertFailsWith<PrizmUnauthorizedException> { run { client.revoke(session) } }
        server.reply("POST /api/prizm/app/logout", 500)
        assertFailsWith<PrizmException> { run { client.revoke(session) } }
    }

    @Test fun requestsNeverPrintTheirSecrets() {
        val r = HttpRequest("POST", "https://x/y", mapOf("Authorization" to "Bearer pza_secret"), """{"codeVerifier":"secret"}""")
        assertFalse(r.toString().contains("secret"))
    }
}
