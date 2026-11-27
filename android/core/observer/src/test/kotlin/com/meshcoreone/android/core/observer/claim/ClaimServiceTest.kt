// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.observer.prizm.ClaimChallenge
import com.meshcoreone.android.core.observer.prizm.ClaimFinishRequest
import com.meshcoreone.android.core.observer.prizm.ClaimFinishResponse
import com.meshcoreone.android.core.observer.prizm.DataDeleteResult
import com.meshcoreone.android.core.observer.prizm.PrizmClient
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.core.observer.prizm.PrizmUser
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.time.Duration
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class ScriptedClient(
    private val challenge: () -> ClaimChallenge,
    private val finish: ClaimFinishResponse = ClaimFinishResponse.Linked,
    private val claimed: Set<String> = emptySet(),
) : PrizmClient {
    var starts = 0
    val finishCalls = mutableListOf<ClaimFinishRequest>()
    val updates = mutableListOf<NodeType>()
    override suspend fun exchangeCode(code: String, codeVerifier: String) = error("unused")
    override suspend fun claimedNodes(session: PrizmSession) = claimed
    override suspend fun claimStart(session: PrizmSession, nodeKey: Bytes): ClaimChallenge { starts++; return challenge() }
    override suspend fun claimFinish(session: PrizmSession, request: ClaimFinishRequest): ClaimFinishResponse { finishCalls += request; return finish }
    override suspend fun updateNode(session: PrizmSession, nodeKey: Bytes, nodeType: NodeType, deviceInfo: NodeDeviceInfo) { updates += nodeType }
    override suspend fun removeNode(session: PrizmSession, nodeKey: Bytes) = Unit
    override suspend fun deleteMyData(session: PrizmSession) = DataDeleteResult(0, 0)
    override suspend fun revoke(session: PrizmSession) = Unit
}

class ClaimServiceTest {
    private val t0 = Instant.parse("2026-11-27T12:00:00Z")
    private val session = PrizmSession("pza_x", t0.plus(Duration.ofDays(180)), PrizmUser("u"))
    private val nonce = "nonce-abcdef0123456789"
    private val signature = Bytes(ByteArray(64) { 9 })
    private val key = ClaimFixtures.nodeKey
    private val serverMessage = "prizm-claim:v1:${key.hexString}:987654321098765432:$nonce"
    private var now = t0
    private val signed = mutableListOf<Bytes>()
    private var signingSeconds = 0L
    private val signer = NodeSigner { signed += it; now = now.plusSeconds(signingSeconds); signature }

    private fun challenge(expires: Instant = t0.plus(Duration.ofMinutes(5)), message: String = serverMessage) = { ClaimChallenge(nonce, message, expires) }
    private fun service(client: PrizmClient, nodeSigner: NodeSigner = signer) = ClaimService(client, nodeSigner) { now }
    private fun claim(service: ClaimService, confirm: Boolean = false) = runBlocking {
        service.claim(session, key, NodeType.PORTABLE, ClaimFixtures.deviceInfo, confirm)
    }

    @Test fun happyPathSignsTheServersMessageExactlyIncludingTheDiscordIdTheAppDoesNotKnow() {
        val client = ScriptedClient(challenge())
        assertEquals(ClaimOutcome.Linked, claim(service(client)))
        assertEquals(serverMessage, String(signed.single().toByteArray(), Charsets.UTF_8))
        val finish = client.finishCalls.single()
        assertEquals(signature, finish.signature); assertEquals(nonce, finish.nonce); assertEquals(NodeType.PORTABLE, finish.nodeType)
        assertEquals(false, finish.takeOver)
    }

    @Test fun aMessageThatIsNotTheClaimShapeIsNeverSigned() {
        val client = ScriptedClient(challenge(message = "eyJhbGciOiJFZDI1NTE5In0.eyJwdWJsaWNLZXkiOiJ4In0"))
        assertIs<ClaimOutcome.Rejected>(claim(service(client)))
        assertTrue(signed.isEmpty() && client.finishCalls.isEmpty())
        val otherNode = ScriptedClient(challenge(message = "prizm-claim:v1:${"ab".repeat(32)}:1:$nonce"))
        assertIs<ClaimOutcome.Rejected>(claim(service(otherNode)))
        assertTrue(signed.isEmpty())
    }

    @Test fun anAlreadyClaimedNodeIsUpdatedWithoutSigningOrStart() {
        val client = ScriptedClient(challenge(), claimed = setOf(key.hexString))
        assertEquals(ClaimOutcome.InfoUpdated, claim(service(client)))
        assertTrue(signed.isEmpty()); assertEquals(0, client.starts); assertEquals(listOf(NodeType.PORTABLE), client.updates)
    }

    @Test fun ownedByOtherIsReportedAtFinishThenTakeOverClaimsAgainWithAFreshSigning() {
        val owned = ScriptedClient(challenge(), ClaimFinishResponse.ClaimedByOther("someone"))
        assertEquals("someone", assertIs<ClaimOutcome.NeedsConfirmation>(claim(service(owned))).ownerLabel)
        val confirmed = ScriptedClient(challenge(), claimed = setOf(key.hexString))
        claim(service(confirmed), confirm = true)
        assertTrue(confirmed.finishCalls.single().takeOver)
        assertEquals(0, confirmed.updates.size, "takeover never takes the info-update shortcut")
    }

    @Test fun nonceAlreadyExpiredOnArrivalIsNotSigned() {
        val client = ScriptedClient(challenge(expires = t0))
        assertEquals(ClaimOutcome.NonceExpired, claim(service(client)))
        assertTrue(signed.isEmpty())
    }

    @Test fun aSlowSigningStillWorksWithinTheFiveMinuteNonceButNotBeyondIt() {
        signingSeconds = 200 // more than the old 2 minutes, less than 5
        assertEquals(ClaimOutcome.Linked, claim(service(ScriptedClient(challenge()))))
        now = t0; signed.clear(); signingSeconds = 301
        val late = ScriptedClient(challenge())
        assertEquals(ClaimOutcome.NonceExpired, claim(service(late)))
        assertTrue(late.finishCalls.isEmpty())
    }

    @Test fun serverReportedExpiryIsDistinctFromRejection() {
        assertEquals(ClaimOutcome.NonceExpired, claim(service(ScriptedClient(challenge(), ClaimFinishResponse.NonceExpired))))
        val rejected = assertIs<ClaimOutcome.Rejected>(claim(service(ScriptedClient(challenge(), ClaimFinishResponse.Rejected("bad signature")))))
        assertEquals("bad signature", rejected.reason)
    }

    @Test fun nodeSigningFailureIsReportedAndNothingIsFinished() {
        val client = ScriptedClient(challenge())
        val failing = NodeSigner { throw MeshCoreException.SigningFailed("no session") }
        assertIs<ClaimOutcome.NodeSigningFailed>(claim(service(client, failing)))
        assertTrue(client.finishCalls.isEmpty())
    }

    @Test fun wrongSizedSignatureFromTheNodeIsRejectedLocally() {
        val client = ScriptedClient(challenge())
        assertIs<ClaimOutcome.NodeSigningFailed>(claim(service(client, NodeSigner { Bytes(ByteArray(63)) })))
        assertTrue(client.finishCalls.isEmpty())
    }
}
