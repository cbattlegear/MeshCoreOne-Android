// AndroidOnly: WP-101 Prizm Mobile Observer codecs, callback recognition, notification copy and claim mapping.
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.ClaimOutcome
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.core.observer.prizm.PrizmUser
import com.meshcoreone.android.core.observer.session.ObserverCounters
import com.meshcoreone.android.core.observer.session.ObserverMode
import com.meshcoreone.android.core.observer.session.ObserverStatus
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimResult
import com.meshcoreone.android.feature.settings.prizm.PrizmNodeType
import com.meshcoreone.android.feature.settings.prizm.PrizmZone
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PrizmStoreCodecsTest {
    @Test fun zonesRoundTripAndDropDamagedOrOutOfRangeLines() {
        val zones = listOf(PrizmZone("a.b;c", 46.877213, -96.789811, 300.0), PrizmZone("z2", -33.5, 151.25, 1234.5))
        assertEquals(zones, PrizmStoreCodecs.decodeZones(PrizmStoreCodecs.encodeZones(zones)))
        val text = PrizmStoreCodecs.encodeZones(zones) + "\nnot a zone\nWjM=;95.0;0.0;10.0\nWjM=;1.0;2.0;-5.0\n"
        assertEquals(zones, PrizmStoreCodecs.decodeZones(text))
        assertTrue(PrizmStoreCodecs.decodeZones("").isEmpty())
    }

    @Test fun sessionRoundTripsAndOldFormatsAreSignedOut() {
        val user = PrizmUser("Name with . and ; chars")
        val session = PrizmSession("pza_acc.ess", Instant.ofEpochMilli(1_700_000_000_123), user)
        val back = PrizmStoreCodecs.decodeSession(PrizmStoreCodecs.encodeSession(session))!!
        assertEquals("pza_acc.ess", back.accessToken); assertEquals(session.expiresAt, back.expiresAt); assertEquals(user, back.user)
        assertNull(PrizmStoreCodecs.decodeSession("garbage"))
        val oldV1 = listOf("v1", "a", "r", "1", "123", "n").joinToString(".") { java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray()) }
        assertNull(PrizmStoreCodecs.decodeSession(oldV1), "stand-in sessions with a refresh token are not carried over")
    }

    @Test fun pendingAuthorizationRoundTripsAndRejectsGarbage() {
        val pending = PendingAuthorization("https://x/y?a=b&c=d", "verifier", "state")
        assertEquals(pending, PrizmStoreCodecs.decodePending(PrizmStoreCodecs.encodePending(pending)))
        assertNull(PrizmStoreCodecs.decodePending("v1"))
    }

    @Test fun theSessionTextNeverPrintsTokens() {
        val session = PrizmSession("SECRET-ACCESS", Instant.EPOCH, PrizmUser("n"))
        assertFalse(session.toString().contains("SECRET"))
    }
}

class PrizmCallbackTest {
    @Test fun onlyTheExactHttpsAppLinkIsACallback() {
        assertTrue(PrizmCallback.isCallback("https://mqtt.nodakmesh.org/app/callback?code=a&state=b"))
        assertFalse(PrizmCallback.isCallback("http://mqtt.nodakmesh.org/app/callback"))
        assertFalse(PrizmCallback.isCallback("https://mqtt.nodakmesh.org/app/callback/x"))
        assertFalse(PrizmCallback.isCallback("https://mqtt.nodakmesh.org.evil.example/app/callback"))
        assertFalse(PrizmCallback.isCallback("https://mqtt.nodakmesh.org:8443/app/callback"))
        assertFalse(PrizmCallback.isCallback("meshcore://contact/add"))
        assertFalse(PrizmCallback.isCallback(null)); assertFalse(PrizmCallback.isCallback("::not a uri::"))
        // A beta base URL changes only the accepted host.
        assertTrue(PrizmCallback.isCallback("https://beta.example.org/app/callback?code=a", "beta.example.org"))
        assertFalse(PrizmCallback.isCallback("https://mqtt.nodakmesh.org/app/callback?code=a", "beta.example.org"))
    }

    @Test fun parametersAreDecodedAndTheFirstValueWins() {
        val p = PrizmCallback.parameters("https://mqtt.nodakmesh.org/app/callback?code=a%2Bb&state=s%20t&state=other&flag")
        assertEquals("a+b", p["code"]); assertEquals("s t", p["state"]); assertEquals("", p["flag"])
        assertTrue(PrizmCallback.parameters("https://mqtt.nodakmesh.org/app/callback").isEmpty())
    }
}

class PrizmNotificationTextTest {
    private val strings = object : PrizmNotificationStrings {
        override fun waitingForNode() = "waiting"
        override fun counts(heard: Int, uploaded: Int, metadataOnly: Int, queued: Int, withheld: Int) = "h$heard u$uploaded m$metadataOnly q$queued w$withheld"
    }

    @Test fun uploadingShowsCountsAndPausedShowsTheGateTextFirst() {
        val counters = ObserverCounters(5, 3, 2, 1, 4)
        assertEquals(PrizmNotificationContent("h5 u3 m2 q1 w4", "h5 u3 m2 q1 w4"), PrizmNotificationText.content(ObserverStatus(ObserverMode.UPLOADING, counters), strings))
        assertEquals(
            PrizmNotificationContent("Paused: outside ND and MN", "Paused: outside ND and MN\nh5 u3 m2 q1 w4"),
            PrizmNotificationText.content(ObserverStatus(ObserverMode.UPLOADING, counters, "Paused: outside ND and MN"), strings),
        )
    }

    @Test fun waitingForANodeShowsOnlyThatLine() {
        assertEquals("waiting", PrizmNotificationText.content(ObserverStatus(ObserverMode.WAITING_FOR_NODE), strings).summary)
        assertEquals("waiting", PrizmNotificationText.content(ObserverStatus(), strings).details)
    }
}

class PrizmClaimMappingTest {
    @Test fun everyOutcomeMapsAndOnlyCompletedClaimsAreRemembered() {
        assertEquals(PrizmClaimResult.Linked, PrizmClaimMapping.result(ClaimOutcome.Linked))
        assertEquals(PrizmClaimResult.InfoUpdated, PrizmClaimMapping.result(ClaimOutcome.InfoUpdated))
        assertEquals(PrizmClaimResult.NeedsConfirmation("Alex"), PrizmClaimMapping.result(ClaimOutcome.NeedsConfirmation("Alex")))
        assertEquals(PrizmClaimResult.Expired, PrizmClaimMapping.result(ClaimOutcome.NonceExpired))
        assertEquals(PrizmClaimResult.Rejected("bad"), PrizmClaimMapping.result(ClaimOutcome.Rejected("bad")))
        assertEquals(PrizmClaimResult.SigningFailed, PrizmClaimMapping.result(ClaimOutcome.NodeSigningFailed(MeshCoreException.Timeout())))
        assertTrue(PrizmClaimMapping.isClaimed(PrizmClaimResult.Linked) && PrizmClaimMapping.isClaimed(PrizmClaimResult.InfoUpdated))
        assertFalse(PrizmClaimMapping.isClaimed(PrizmClaimResult.Expired))
    }

    @Test fun everyNodeTypeMapsToItsWireName() {
        assertEquals(
            listOf("handheld", "vehicle", "home", "portable"),
            PrizmNodeType.entries.map { PrizmClaimMapping.nodeType(it).wireName },
        )
    }
}
