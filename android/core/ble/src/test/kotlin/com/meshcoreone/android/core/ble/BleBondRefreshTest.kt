// PortedFrom: MC1Services/Tests/MC1ServicesTests/Transport/BondShieldRefreshTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.meshcoreone.android.core.ble

import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BleBondRefreshTest {
    private val radioId = UUID.fromString("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")

    private suspend fun connected(live: Boolean = true): BleFixture {
        val fixture = BleFixture()
        fixture.transport.connect()
        fixture.transport.recordBondVerification(radioId, fixture.wallNow.minusSeconds(3_600))
        if (live) fixture.transport.setAppSessionLive(fixture.transport.diagnostics.value.generation, radioId)
        return fixture
    }

    @Test fun `successful RSSI with live session refreshes an existing verification`() = runTest {
        val fixture = connected()
        val before = assertNotNull(fixture.transport.bondVerification(radioId))
        assertEquals(-50, fixture.transport.readRssi())
        assertEquals(fixture.wallNow, fixture.transport.bondVerification(radioId))
        assertTrue(assertNotNull(fixture.transport.bondVerification(radioId)) > before)
        fixture.transport.disconnect()
    }

    @Test fun `failed RSSI does not refresh verification`() = runTest {
        val fixture = connected()
        val before = fixture.transport.bondVerification(radioId)
        fixture.rssiStatus = 133
        assertEquals(BleError.RssiReadFailed(133), assertFailsWith<BleTransportException> { fixture.transport.readRssi() }.error)
        assertEquals(before, fixture.transport.bondVerification(radioId))
        assertTrue(fixture.transport.isConnected())
        fixture.transport.disconnect()
    }

    @Test fun `pre handshake connected without session live does not refresh`() = runTest {
        val fixture = connected(live = false)
        val before = fixture.transport.bondVerification(radioId)
        fixture.transport.readRssi()
        assertEquals(before, fixture.transport.bondVerification(radioId))
        fixture.transport.disconnect()
    }

    @Test fun `stale session live after phase teardown does not refresh on reconnect`() = runTest {
        val fixture = connected()
        val before = fixture.transport.bondVerification(radioId)
        fixture.transport.disconnect()
        fixture.transport.connect()
        fixture.transport.readRssi()
        assertEquals(before, fixture.transport.bondVerification(radioId))
        fixture.transport.disconnect()
    }

    @Test fun `dead stack with preserved connected phase does not refresh`() = runTest {
        val fixture = connected()
        val before = fixture.transport.bondVerification(radioId)
        fixture.transport.setAppSessionLive(fixture.transport.diagnostics.value.generation, null)
        fixture.transport.readRssi()
        assertEquals(before, fixture.transport.bondVerification(radioId))
        fixture.transport.disconnect()
    }

    @Test fun `refresh never creates a verification that was cleared`() = runTest {
        val fixture = connected()
        fixture.transport.clearBondVerification(radioId)
        fixture.transport.readRssi()
        assertNull(fixture.transport.bondVerification(radioId))
        fixture.transport.disconnect()
    }

    @Test fun `clear then tick and tick then clear both leave nil`() = runTest {
        val fixture = connected()
        fixture.transport.clearBondVerification(radioId)
        fixture.transport.readRssi()
        assertNull(fixture.transport.bondVerification(radioId))
        fixture.transport.recordBondVerification(radioId, fixture.wallNow.minusSeconds(1))
        fixture.transport.readRssi()
        assertNotNull(fixture.transport.bondVerification(radioId))
        fixture.transport.clearBondVerification(radioId)
        assertNull(fixture.transport.bondVerification(radioId))
        fixture.transport.disconnect()
    }

    @Test fun `stale bond and live RSSI never shield a definitive authentication failure`() = runTest {
        val fixture = connected()
        fixture.transport.readRssi()
        fixture.transport.recordBondVerification(radioId, fixture.wallNow.minusSeconds(6 * 60 * 60L + 60))
        fixture.connection.events.onDisconnected(fixture.connection, 5)
        assertEquals(BleError.AuthenticationFailed, fixture.transport.diagnostics.value.issue)
        assertFalse(fixture.transport.isConnected())
        assertEquals(1, fixture.connection.closeCalls.values.size)
    }

    @Test fun `cross launch seed uses the refreshed date without manufacturing a new verification`() = runTest {
        val fixture = connected()
        fixture.transport.readRssi()
        val refreshed = assertNotNull(fixture.transport.bondVerification(radioId))
        val reseeded = BleFixture()
        reseeded.transport.recordBondVerification(radioId, refreshed)
        assertEquals(fixture.wallNow, reseeded.transport.bondVerification(radioId))
        assertNull(reseeded.transport.bondVerification(UUID.fromString("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")))
        fixture.transport.disconnect()
    }

    @Test fun `joint zombie shield cannot gain new freshness after session live is cleared`() = runTest {
        val fixture = connected()
        val stale = fixture.wallNow.minusSeconds(6 * 60 * 60L + 60)
        fixture.transport.recordBondVerification(radioId, stale)
        fixture.transport.setAppSessionLive(fixture.transport.diagnostics.value.generation, null)
        repeat(3) { fixture.transport.readRssi() }
        assertEquals(stale, fixture.transport.bondVerification(radioId))
        fixture.connection.events.onDisconnected(fixture.connection, 5)
        assertEquals(BleError.AuthenticationFailed, fixture.transport.diagnostics.value.issue)
    }

    @Test fun `onBondRefreshed fires only when refresh mutates`() = runTest {
        val fixture = connected()
        val events = mutableListOf<BondRefresh>()
        fixture.transport.setBondRefreshedHandler { events.add(it) }
        fixture.transport.readRssi()
        assertEquals(listOf(radioId), events.map { it.radioId })
        assertTrue(fixture.transport.isBondRefreshCurrent(events.single()))
        fixture.transport.clearBondVerification(radioId)
        fixture.transport.readRssi()
        assertEquals(1, events.size)
        fixture.transport.disconnect()
    }

    @Test fun `forget after RSSI hop invalidates the refresh rather than resurrecting keys`() = runTest {
        val fixture = connected()
        var refresh: BondRefresh? = null
        fixture.transport.setBondRefreshedHandler { refresh = it }
        fixture.transport.readRssi()
        val beforeForget = assertNotNull(refresh)
        fixture.transport.clearBondVerification(radioId)
        assertFalse(fixture.transport.isBondRefreshCurrent(beforeForget))
        assertNull(fixture.transport.bondVerification(radioId))
        fixture.transport.disconnect()
    }

    @Test fun `epoch gate blocks an old refresh after clear even when a new verification was seeded`() = runTest {
        val fixture = connected()
        var refresh: BondRefresh? = null
        fixture.transport.setBondRefreshedHandler { refresh = it }
        fixture.transport.readRssi()
        val old = assertNotNull(refresh)
        assertTrue(fixture.transport.isBondRefreshCurrent(old))
        fixture.transport.clearBondVerification(radioId)
        fixture.transport.recordBondVerification(radioId, old.verifiedAt)
        assertFalse(fixture.transport.isBondRefreshCurrent(old))
        fixture.transport.disconnect()
    }

    @Test fun `wrong radio and generation cannot claim a live app session`() = runTest {
        val fixture = connected(live = false)
        val generation = fixture.transport.diagnostics.value.generation
        fixture.transport.setAppSessionLive(generation, UUID.fromString("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA"))
        val before = fixture.transport.bondVerification(radioId)
        fixture.transport.readRssi()
        assertEquals(before, fixture.transport.bondVerification(radioId))
        assertEquals(BleError.StaleGeneration(generation - 1, generation),
            assertFailsWith<BleTransportException> { fixture.transport.setAppSessionLive(generation - 1, radioId) }.error)
        fixture.transport.disconnect()
    }

    @Test fun `RSSI callback from a stopped generation cannot refresh a live successor`() = runTest {
        val fixture = connected()
        fixture.pause = GattOperationKind.Rssi
        val reading = async { assertFailsWith<BleTransportException> { fixture.transport.readRssi() } }
        runCurrent()
        val previous = fixture.connection
        val request = requireNotNull(fixture.pending)
        val before = fixture.transport.bondVerification(radioId)
        fixture.transport.disconnect()
        fixture.pause = null
        fixture.transport.connect()
        fixture.complete(previous, request)
        runCurrent()
        assertEquals(BleError.NotConnected, reading.await().error)
        assertEquals(before, fixture.transport.bondVerification(radioId))
        fixture.transport.disconnect()
    }
}
