// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.broker

import com.meshcoreone.android.core.observer.claim.NodeSigner
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import java.time.Instant
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

private val NODE_KEY = Bytes(ByteArray(32) { it.toByte() })
private val IAT = Instant.ofEpochSecond(1_795_802_645)

/** Signature i*3 mod 256, and a record of what was signed. */
private class FakeSigner(private val signatureSize: Int = 64) : NodeSigner {
    val signed = ArrayList<String>()
    override suspend fun sign(message: Bytes): Bytes {
        signed += String(message.toByteArray(), Charsets.US_ASCII)
        return Bytes(ByteArray(signatureSize) { ((it * 3) % 256).toByte() })
    }
}

class BrokerTokenTest {
    private val header = "eyJhbGciOiJFZDI1NTE5IiwidHlwIjoiSldUIn0"
    private val payload = "eyJwdWJsaWNLZXkiOiIwMDAxMDIwMzA0MDUwNjA3MDgwOTBBMEIwQzBEMEUwRjEwMTExMjEzMTQxNTE2MTcxODE5MUExQjFDMUQxRTFGIiwi" +
        "aWF0IjoxNzk1ODAyNjQ1LCJleHAiOjE3OTU4ODkwNDUsImF1ZCI6Im1xdHQubm9kYWttZXNoLm9yZyJ9"
    private val signatureHex = "000306090c0f1215181b1e2124272a2d303336393c3f4245484b4e5154575a5d606366696c6f7275787b7e8184878a8d9093" +
        "96999c9fa2a5a8abaeb1b4b7babd"

    @Test fun knownAnswerForTheContractFormat() = runBlocking<Unit> {
        val signer = FakeSigner()
        val login = BrokerToken.build(NODE_KEY, signer, IAT, Duration.ofHours(24))
        assertEquals("v1_" + NODE_KEY.hexString.uppercase(), login.username)
        assertEquals("$header.$payload.$signatureHex", login.password)
        assertEquals(listOf("$header.$payload"), signer.signed) // the node signs the ASCII "<header>.<payload>"
        assertEquals(IAT.plusSeconds(86_400), login.expiresAt)
    }

    @Test fun headerAndPayloadDecodeToTheContractJson() {
        val input = BrokerToken.signingInput(NODE_KEY, IAT, IAT.plusSeconds(3_600))
        val (h, p) = input.split(".")
        val decoder = Base64.getUrlDecoder()
        assertEquals("""{"alg":"Ed25519","typ":"JWT"}""", String(decoder.decode(h)))
        assertEquals(
            """{"publicKey":"${NODE_KEY.hexString.uppercase()}","iat":1795802645,"exp":1795806245,"aud":"mqtt.nodakmesh.org"}""",
            String(decoder.decode(p)),
        )
        assertFalse(input.contains('=') || input.contains('+') || input.contains('/'))
    }

    @Test fun subSecondTimesAreTruncatedToWholeSeconds() = runBlocking<Unit> {
        val login = BrokerToken.build(NODE_KEY, FakeSigner(), IAT.plusMillis(987), Duration.ofHours(1))
        assertEquals(IAT, login.issuedAt)
    }

    @Test fun rejectsLifetimesTheServerWouldRefuse() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { BrokerToken.signingInput(NODE_KEY, IAT, IAT.plus(Duration.ofHours(169))) }
        assertFailsWith<IllegalArgumentException> { BrokerToken.signingInput(NODE_KEY, IAT, IAT) }
        BrokerToken.signingInput(NODE_KEY, IAT, IAT.plus(Duration.ofHours(168)))
    }

    @Test fun rejectsABadKeyOrSignatureLength() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { BrokerToken.username(Bytes(ByteArray(31))) }
        assertFailsWith<IllegalArgumentException> { BrokerToken.build(NODE_KEY, FakeSigner(63), IAT) }
    }

    @Test fun theSignedInputIsLongerThanOneChunkSoSigningMustBeBounded() {
        // The real signer is SessionNodeSigner -> signBounded (160-byte chunks); the input here is 228 bytes.
        assertTrue(BrokerToken.signingInput(NODE_KEY, IAT, IAT.plusSeconds(86_400)).length > 160)
    }

    @Test fun theLoginNeverPrintsThePassword() = runBlocking<Unit> {
        val login = BrokerToken.build(NODE_KEY, FakeSigner(), IAT)
        assertFalse(login.toString().contains(login.password))
    }
}

class BrokerLoginProviderTest {
    private var now = IAT
    private val signer = FakeSigner()
    private val provider = BrokerLoginProvider(NODE_KEY, signer, { now }, Duration.ofHours(24), Duration.ofHours(2))

    @Test fun signsOnceAndReusesUntilTheRefreshMargin() = runBlocking<Unit> {
        val first = provider.current()
        now = IAT.plus(Duration.ofHours(21))
        assertEquals(first.password, provider.current().password)
        assertEquals(1, signer.signed.size)
    }

    @Test fun refreshesBeforeExpiry() = runBlocking<Unit> {
        val first = provider.current()
        assertEquals(IAT.plus(Duration.ofHours(22)), provider.refreshDueAt())
        now = IAT.plus(Duration.ofHours(22)).plusSeconds(1)
        val second = provider.current()
        assertEquals(2, signer.signed.size)
        assertTrue(second.expiresAt.isAfter(first.expiresAt))
    }

    @Test fun aFailedSigningKeepsThePreviousTokenAndPropagates() = runBlocking<Unit> {
        var fail = false
        val flaky = NodeSigner { m -> if (fail) error("node unavailable") else FakeSigner().sign(m) }
        val p = BrokerLoginProvider(NODE_KEY, flaky, { now })
        val first = p.current()
        fail = true; now = IAT.plus(Duration.ofHours(23))
        assertFailsWith<IllegalStateException> { p.current() }
        fail = false; now = IAT.plus(Duration.ofHours(10))
        assertEquals(first.password, p.current().password)
    }
}
