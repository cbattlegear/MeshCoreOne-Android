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

    @Test fun signsInAFakeUserAndHandsOutLocalBrokerCredentials() = runBlocking {
        val session = client.exchangeCode("any", "any", "app://cb")
        assertEquals("100000000000000001", session.user.discordUserId)
        assertFalse(session.needsRefresh(clock.instant()))
        assertTrue(client.refresh(session).expiresAt.isAfter(session.expiresAt.minusSeconds(1)))
        val credentials = client.mqttCredentials(session, ClaimFixtures.nodeKey)
        assertEquals("10.0.2.2", credentials.host)
        assertFalse(credentials.toString().contains(credentials.secret))
        assertFalse(session.toString().contains("standin-access"))
        client.revoke(session)
    }

    @Test fun acceptsAnyClaimEndToEnd() = runBlocking {
        val session = client.exchangeCode("c", "v", "r")
        val service = ClaimService(client, NodeSigner { Bytes(ByteArray(64)) }) { clock.instant() }
        val outcome = service.claim(session, ClaimFixtures.nodeKey, NodeType.HOME_BASE, ClaimFixtures.deviceInfo)
        assertEquals(ClaimOutcome.Linked, outcome)
    }

    @Test fun sessionRefreshWindow() {
        val session = PrizmSession("a", null, clock.instant().plusSeconds(30), PrizmUser("1", "u"))
        assertTrue(session.needsRefresh(clock.instant()))
    }
}
