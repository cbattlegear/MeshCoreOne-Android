// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.geo

import com.meshcoreone.android.core.observer.location.FixProvider
import com.meshcoreone.android.core.observer.location.LocationFix
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
    private val gate = UploadGate(testFence())
    private val tenMinutes = 10 * 60_000L

    @Test fun startsWaitingForAFixWithNoNotification() {
        assertEquals(GateState.WAITING_FOR_FIX, gate.state)
        assertNull(gate.notificationText)
    }

    @Test fun insideUploadsAndPicksTheRegionFromThePosition() {
        assertEquals(GateDecision.Accept("nd"), gate.admit(resolvedAt(46.5, -97.5)))
        assertEquals(GateState.INSIDE, gate.state)
        assertEquals(GateDecision.Accept("mn"), gate.admit(resolvedAt(46.5, -96.5)))
        assertNull(gate.notificationText)
    }

    @Test fun outsidePausesWithTheNotificationAndResumesWhenBackInside() {
        assertEquals(GateDecision.Reject(RejectReason.OUTSIDE), gate.admit(resolvedAt(40.0, -97.5)))
        assertEquals(GateState.OUTSIDE, gate.state)
        assertEquals("Paused: outside ND and MN", gate.notificationText)
        assertEquals(GateDecision.Accept("nd"), gate.admit(resolvedAt(46.5, -97.5)))
        assertEquals(GateState.INSIDE, gate.state); assertNull(gate.notificationText)
    }

    @Test fun theTwoKilometreBufferKeepsBorderPositionsUploading() {
        assertEquals(GateDecision.Accept("nd"), gate.admit(resolvedAt(46.5, -98.02))) // about 1.5 km west of the edge
        assertEquals(GateDecision.Reject(RejectReason.OUTSIDE), gate.admit(resolvedAt(46.5, -98.04))) // about 3 km
    }

    @Test fun noFixIsAcceptedWhenTheLastGoodFixWasInsideAndAtMostTenMinutesOld() {
        assertEquals(GateDecision.Accept("mn"), gate.admit(noFix(46.5, -96.5, 5 * 60_000L)))
        assertEquals(GateDecision.Accept("mn"), gate.admit(noFix(46.5, -96.5, tenMinutes)))
        assertEquals(GateState.INSIDE, gate.state)
    }

    @Test fun noFixIsRejectedWhenTheLastGoodFixIsOlderThanTenMinutes() {
        assertEquals(GateDecision.Reject(RejectReason.NO_FIX), gate.admit(noFix(46.5, -96.5, tenMinutes + 1)))
        assertEquals(GateState.WAITING_FOR_FIX, gate.state)
        assertEquals("Paused: waiting for a location fix", gate.notificationText)
    }

    @Test fun noFixIsRejectedWhenTheLastGoodFixWasOutside() {
        assertEquals(GateDecision.Reject(RejectReason.OUTSIDE), gate.admit(noFix(40.0, -97.5, 60_000)))
        assertEquals(GateState.OUTSIDE, gate.state); assertEquals("Paused: outside ND and MN", gate.notificationText)
    }

    @Test fun noFixAtAllIsRejected() {
        assertEquals(GateDecision.Reject(RejectReason.NO_FIX), gate.admit(noFix(null, null, null)))
    }

    @Test fun aGateWithoutAnyRegionGeofenceStillWorksThroughTheContainsInterface() {
        val everywhere = object : RegionGeofence {
            override fun contains(latitude: Double, longitude: Double) = true
            override fun regionFor(latitude: Double, longitude: Double): String? = null
        }
        // A point that is inside but has no region cannot be routed to a topic, so it is not uploaded.
        assertEquals(GateDecision.Reject(RejectReason.NO_REGION), UploadGate(everywhere).admit(resolvedAt(1.0, 1.0)))
    }
}
