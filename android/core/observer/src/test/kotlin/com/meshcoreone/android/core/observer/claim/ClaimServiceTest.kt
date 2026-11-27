// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.claim

import com.meshcoreone.android.core.observer.prizm.ClaimFinishRequest
import com.meshcoreone.android.core.observer.prizm.ClaimFinishResponse
import com.meshcoreone.android.core.observer.prizm.ClaimStartResponse
import com.meshcoreone.android.core.observer.prizm.PrizmClient
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.core.observer.prizm.PrizmUser
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class ScriptedClient(
    private val start: ClaimStartResponse,
    private val finish: ClaimFinishResponse = ClaimFinishResponse.Linked,
) : PrizmClient {
    val startCalls = mutableListOf<Boolean>()
    val finishCalls = mutableListOf<ClaimFinishRequest>()
    override suspend fun exchangeCode(code: String, codeVerifier: String, redirectUri: String) = error("unused")
    override suspend fun refresh(session: PrizmSession) = error("unused")
    override suspend fun claimStart(session: PrizmSession, nodeKey: Bytes, confirmTakeover: Boolean): ClaimStartResponse {
        startCalls += confirmTakeover; return start
    }
    override suspend fun claimFinish(session: PrizmSession, request: ClaimFinishRequest): ClaimFinishResponse {
        finishCalls += request; return finish
    }
    override suspend fun revoke(session: PrizmSession) = Unit
}

class ClaimServiceTest {
    private val t0 = Instant.parse("2026-11-27T12:00:00Z")
    private val session = PrizmSession("a", "r", t0.plusSeconds(3600), PrizmUser("123456789012345678", "u"))
    private val nonce = Bytes(ByteArray(32) { 7 })
    private val signature = Bytes(ByteArray(64) { 9 })
    private val key = ClaimFixtures.nodeKey
    private var now = t0
    private val signed = mutableListOf<Bytes>()
    private val signer = NodeSigner { signed += it; now = now.plusSeconds(signingSeconds); signature }
    private var signingSeconds = 0L

    private fun service(client: PrizmClient, nodeSigner: NodeSigner = signer) = ClaimService(client, nodeSigner) { now }
    private fun claim(service: ClaimService, confirm: Boolean = false) = runBlocking {
        service.claim(session, key, NodeType.PORTABLE, ClaimFixtures.deviceInfo, confirm)
    }
    private fun challenge(seconds: Long = 120) = ClaimStartResponse.Challenge(nonce, t0.plusSeconds(seconds))

    @Test fun happyPathSignsTheExactMessageAndFinishesWithTheSignature() {
        val client = ScriptedClient(challenge())
        assertEquals(ClaimOutcome.Linked, claim(service(client)))
        assertEquals(ClaimMessage.build(key, "123456789012345678", nonce), signed.single())
        val finish = client.finishCalls.single()
        assertEquals(signature, finish.signature); assertEquals(nonce, finish.nonce); assertEquals(NodeType.PORTABLE, finish.nodeType)
    }

    @Test fun alreadyLinkedSkipsSigningAndOnlyUpdatesInfo() {
        val client = ScriptedClient(ClaimStartResponse.AlreadyLinkedToYou, ClaimFinishResponse.InfoUpdated)
        assertEquals(ClaimOutcome.InfoUpdated, claim(service(client)))
        assertTrue(signed.isEmpty())
        val finish = client.finishCalls.single()
        assertNull(finish.signature); assertNull(finish.nonce)
    }

    @Test fun claimedByOtherIsADistinctResultAndNothingIsSigned() {
        val client = ScriptedClient(ClaimStartResponse.ClaimedByOther("someone#1"))
        val outcome = assertIs<ClaimOutcome.NeedsConfirmation>(claim(service(client)))
        assertEquals("someone#1", outcome.ownerLabel)
        assertTrue(signed.isEmpty() && client.finishCalls.isEmpty())
    }

    @Test fun confirmedTakeoverIsForwardedToStartAndFinish() {
        val client = ScriptedClient(challenge())
        claim(service(client), confirm = true)
        assertEquals(listOf(true), client.startCalls)
        assertTrue(client.finishCalls.single().confirmTakeover)
    }

    @Test fun serverSideConfirmRaceIsAlsoNeedsConfirmation() {
        val client = ScriptedClient(challenge(), ClaimFinishResponse.ClaimedByOther(null))
        assertIs<ClaimOutcome.NeedsConfirmation>(claim(service(client)))
    }

    @Test fun nonceAlreadyExpiredOnArrivalIsNotSigned() {
        val client = ScriptedClient(challenge(seconds = 0))
        assertEquals(ClaimOutcome.NonceExpired, claim(service(client)))
        assertTrue(signed.isEmpty())
    }

    @Test fun nonceExpiringWhileTheNodeSignsIsNotSubmitted() {
        signingSeconds = 130
        val client = ScriptedClient(challenge())
        assertEquals(ClaimOutcome.NonceExpired, claim(service(client)))
        assertTrue(client.finishCalls.isEmpty())
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
