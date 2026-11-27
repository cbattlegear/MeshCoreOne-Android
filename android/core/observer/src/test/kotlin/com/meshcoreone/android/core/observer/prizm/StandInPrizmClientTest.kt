// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.prizm

import com.meshcoreone.android.core.observer.claim.ClaimFixtures
import com.meshcoreone.android.core.observer.claim.ClaimOutcome
import com.meshcoreone.android.core.observer.claim.ClaimService
import com.meshcoreone.android.core.observer.claim.NodeSigner
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StandInPrizmClientTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-27T12:00:00Z"), ZoneOffset.UTC)
    private val client = StandInPrizmClient(clock, random = { ByteArray(it) { 3 } })

    @Test fun signsInAFakeUserWithA180DayToken() = runBlocking {
        val session = client.exchangeCode("any", "any")
        assertEquals("Stand-in Observer", session.user.displayName)
        assertFalse(session.isExpired(clock.instant()))
        assertTrue(session.isExpired(clock.instant().plus(java.time.Duration.ofDays(181))))
        assertFalse(session.toString().contains(session.accessToken))
        client.revoke(session)
    }

    @Test fun claimsEndToEndThroughTheRealMessageValidationAndRemembersTheNode() = runBlocking {
        val session = client.exchangeCode("c", "v")
        val service = ClaimService(client, NodeSigner { Bytes(ByteArray(64)) }) { clock.instant() }
        assertEquals(ClaimOutcome.Linked, service.claim(session, ClaimFixtures.nodeKey, NodeType.HOME_BASE, ClaimFixtures.deviceInfo))
        assertEquals(setOf(ClaimFixtures.nodeKey.hexString), client.claimedNodes(session))
        assertEquals(ClaimOutcome.InfoUpdated, service.claim(session, ClaimFixtures.nodeKey, NodeType.HOME_BASE, ClaimFixtures.deviceInfo))
        client.removeNode(session, ClaimFixtures.nodeKey)
        assertTrue(client.claimedNodes(session).isEmpty())
    }
}
