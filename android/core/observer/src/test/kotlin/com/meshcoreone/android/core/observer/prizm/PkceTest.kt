// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PkceTest {
    @Test fun matchesRfc7636AppendixBVector() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test fun rfcAppendixBVerifierBytesRoundTripThroughGenerate() {
        // The RFC's verifier encodes this 32-byte octet sequence, so generate() must reproduce the RFC pair exactly.
        val octets = intArrayOf(116, 24, 223, 180, 151, 153, 224, 37, 79, 250, 96, 125, 216, 173, 187, 186, 22, 212, 37, 77, 105, 214, 191, 240, 91, 88, 5, 88, 83, 132, 141, 121)
        val pkce = Pkce.generate { ByteArray(32) { octets[it].toByte() } }
        assertEquals("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", pkce.codeVerifier)
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", pkce.codeChallenge)
    }

    @Test fun generatedVerifierIsInRfcAlphabetAndLength() {
        val pkce = Pkce.generate()
        assertTrue(pkce.codeVerifier.length in 43..128)
        assertTrue(pkce.codeVerifier.all { it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_' })
        assertEquals(Pkce.challengeFor(pkce.codeVerifier), pkce.codeChallenge)
    }

    @Test fun authorizationUrlCarriesS256ChallengeAndState() {
        val request = AuthorizationRequest.create(PrizmOAuthConfig("mc1", "meshcore1://prizm/callback")) { ByteArray(it) { 1 } }
        assertTrue(request.url.startsWith("https://mqtt.nodakmesh.org/api/prizm/auth/app/login?code_challenge="))
        assertTrue(request.url.contains("code_challenge_method=S256"))
        assertTrue(request.url.contains("code_challenge=${request.pkce.codeChallenge}"))
        assertTrue(request.url.endsWith("&state=${request.state}"))
        assertTrue(request.state.length in 8..128 && request.state.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        for (unsent in listOf("client_id", "redirect_uri", "response_type", "scope")) assertTrue(!request.url.contains(unsent), unsent)
    }

    @Test fun callbackStateMustMatch() {
        val request = AuthorizationRequest.create(PrizmOAuthConfig("mc1", "app://cb"))
        assertEquals("abc", request.codeFromCallback(mapOf("code" to "abc", "state" to request.state)))
        assertFailsWith<PrizmException> { request.codeFromCallback(mapOf("code" to "abc", "state" to "other")) }
        assertFailsWith<PrizmException> { request.codeFromCallback(mapOf("code" to "abc")) }
        assertFailsWith<PrizmException> { request.codeFromCallback(mapOf("error" to "access_denied", "state" to request.state)) }
        assertFailsWith<PrizmException> { request.codeFromCallback(mapOf("state" to request.state)) }
    }

    @Test fun aRestoredRequestStillChecksTheOriginalState() {
        val original = AuthorizationRequest.create(PrizmOAuthConfig("mc1", "app://cb"))
        val restored = AuthorizationRequest.restore(original.url, original.pkce.codeVerifier, original.state)
        assertEquals(original.pkce.codeChallenge, restored.pkce.codeChallenge)
        assertEquals("abc", restored.codeFromCallback(mapOf("code" to "abc", "state" to original.state)))
        assertFailsWith<PrizmException> { restored.codeFromCallback(mapOf("code" to "abc", "state" to "forged")) }
    }
}
