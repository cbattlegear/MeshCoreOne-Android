// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

import com.meshcoreone.android.core.observer.location.FixProvider
import com.meshcoreone.android.core.observer.location.LocationFix
import com.meshcoreone.android.core.observer.location.LocationSharing
import com.meshcoreone.android.core.observer.location.PositionResult
import com.meshcoreone.android.core.observer.upload.RecordLocation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal fun square(south: Double, west: Double, north: Double, east: Double) =
    GeoPolygon(listOf(LatLon(south, west), LatLon(south, east), LatLon(north, east), LatLon(north, west)))

/** nd = lon -98..-97, mn = lon -97..-96, lat 46..47. */
internal fun testFence() = PolygonGeofence(
    mapOf("nd" to listOf(square(46.0, -98.0, 47.0, -97.0)), "mn" to listOf(square(46.0, -97.0, 47.0, -96.0))), 2_000.0,
)

internal fun resolvedAt(lat: Double, lon: Double) =
    PositionResult.Resolved(RecordLocation(lat, lon, 5.0, provider = "gps", fixAgeMs = 0), 0L)

internal fun noFix(lat: Double?, lon: Double?, ageMs: Long?) = PositionResult.NoFix(
    if (lat == null || lon == null) null else LocationFix(lat, lon, 5.0, fixElapsedMs = 0, provider = FixProvider.GPS), ageMs,
)

class UploadGateTest {
    private var sharing = true
    private val gate = UploadGate(testFence(), LocationSharing { sharing })

    private fun accepted(lat: Double, lon: Double) = GateDecision.Accept(resolvedAt(lat, lon).location)

    @Test fun startsWaitingForAFixWithNoNotification() {
        assertEquals(GateState.WAITING_FOR_FIX, gate.state)
        assertNull(gate.notificationText)
    }

    @Test fun insideUploadsWithThePositionAndNoNotification() {
        assertEquals(accepted(46.5, -97.5), gate.admit(resolvedAt(46.5, -97.5)))
        assertEquals(GateState.INSIDE, gate.state)
        assertEquals(accepted(46.5, -96.5), gate.admit(resolvedAt(46.5, -96.5)))
        assertNull(gate.notificationText)
    }

    @Test fun outsidePausesWithTheNotificationAndResumesWhenBackInside() {
        assertEquals(GateDecision.Reject(RejectReason.OUTSIDE), gate.admit(resolvedAt(40.0, -97.5)))
        assertEquals(GateState.OUTSIDE, gate.state)
        assertEquals("Paused: outside ND and MN", gate.notificationText)
        assertEquals(accepted(46.5, -97.5), gate.admit(resolvedAt(46.5, -97.5)))
        assertEquals(GateState.INSIDE, gate.state); assertNull(gate.notificationText)
    }

    @Test fun theTwoKilometreBufferKeepsBorderPositionsUploading() {
        assertEquals(accepted(46.5, -98.02), gate.admit(resolvedAt(46.5, -98.02))) // about 1.5 km west of the edge
        assertEquals(GateDecision.Reject(RejectReason.OUTSIDE), gate.admit(resolvedAt(46.5, -98.04))) // about 3 km
    }

    @Test fun sharingOffPausesEverythingWithItsOwnNotificationAndResumesWhenSwitchedBackOn() {
        sharing = false
        assertEquals(GateDecision.Reject(RejectReason.LOCATION_OFF), gate.admit(resolvedAt(46.5, -97.5)))
        assertEquals(GateState.LOCATION_OFF, gate.state)
        assertEquals("Upload paused: location is off", gate.notificationText)
        // Also with no fix at all, and outside: the cause shown is the switch.
        assertEquals(GateDecision.Reject(RejectReason.LOCATION_OFF), gate.admit(noFix(null, null, null)))
        assertEquals(GateDecision.Reject(RejectReason.LOCATION_OFF), gate.admit(resolvedAt(40.0, -97.5)))
        sharing = true
        assertEquals(accepted(46.5, -97.5), gate.admit(resolvedAt(46.5, -97.5)))
        assertNull(gate.notificationText)
    }

    @Test fun refreshSharingShowsAndClearsThePausedStateWithoutAPacket() {
        val here = resolvedAt(46.5, -97.5)
        gate.refreshSharing(here)
        assertNull(gate.notificationText); assertEquals(GateState.WAITING_FOR_FIX, gate.state)
        sharing = false; gate.refreshSharing(here)
        assertEquals(GateState.LOCATION_OFF, gate.state); assertEquals("Upload paused: location is off", gate.notificationText)
        sharing = true; gate.refreshSharing(here)
        assertEquals(GateState.INSIDE, gate.state); assertNull(gate.notificationText) // re-decided from the position now
        sharing = false; gate.refreshSharing(here); sharing = true; gate.refreshSharing(noFix(null, null, null))
        assertEquals("Paused: waiting for a location fix", gate.notificationText)
        sharing = false; gate.refreshSharing(here); sharing = true; gate.refreshSharing(resolvedAt(40.0, -97.5))
        assertEquals("Paused: outside ND and MN", gate.notificationText)
    }

    @Test fun aFailingSharingSettingFailsClosed() {
        val broken = UploadGate(testFence(), LocationSharing { error("prefs unavailable") })
        assertEquals(GateDecision.Reject(RejectReason.LOCATION_OFF), broken.admit(resolvedAt(46.5, -97.5)))
    }

    @Test fun noFixIsRejectedEvenWhenAGoodInsideFixExistedMinutesAgo() {
        // The server records only packets with a position: the old "last good fix" rule is gone.
        assertEquals(GateDecision.Reject(RejectReason.NO_FIX), gate.admit(noFix(46.5, -96.5, 60_000)))
        assertEquals(GateState.WAITING_FOR_FIX, gate.state)
        assertEquals("Paused: waiting for a location fix", gate.notificationText)
        assertEquals(GateDecision.Reject(RejectReason.NO_FIX), gate.admit(noFix(null, null, null)))
    }

    @Test fun positionsTheServerWouldDropAreWaitingForABetterFix() {
        fun at(acc: Double, age: Long?, lat: Double = 46.5, lon: Double = -97.5) =
            PositionResult.Resolved(RecordLocation(lat, lon, acc, provider = "gps", fixAgeMs = age), 0L)
        assertEquals(GateDecision.Reject(RejectReason.POOR_FIX), gate.admit(at(50.5, 0)))
        assertEquals(GateState.WAITING_FOR_FIX, gate.state)
        assertEquals(GateDecision.Reject(RejectReason.POOR_FIX), gate.admit(at(5.0, 10_001)))
        assertEquals(GateDecision.Reject(RejectReason.POOR_FIX), gate.admit(at(5.0, 0, 0.0, 0.0)))
        assertEquals(GateDecision.Accept(at(50.0, 10_000).location), gate.admit(at(50.0, 10_000)))
        assertEquals(GateDecision.Accept(at(5.0, null).location), gate.admit(at(5.0, null))) // interpolated: no fix age
    }
}
