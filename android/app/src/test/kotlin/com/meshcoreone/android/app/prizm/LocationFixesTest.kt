// AndroidOnly: WP-101 Prizm Mobile Observer Location -> LocationFix conversion and permission list (Robolectric).
package com.meshcoreone.android.app.prizm

import android.location.Location
import android.location.LocationManager
import com.meshcoreone.android.core.observer.location.FixProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class LocationFixesTest {
    private fun location(provider: String = LocationManager.GPS_PROVIDER, configure: Location.() -> Unit = {}) =
        Location(provider).apply {
            latitude = 46.8772; longitude = -96.7898; accuracy = 4.5f; elapsedRealtimeNanos = 12_345_000_000L
            configure()
        }

    @Test fun aFullFixKeepsEveryFieldOnTheElapsedRealtimeClock() {
        val fix = LocationFixes.from(location { altitude = 280.0; verticalAccuracyMeters = 3f; speed = 13.4f; bearing = 90f })!!
        assertEquals(46.8772, fix.latitude); assertEquals(4.5, fix.hAccuracyMeters)
        assertEquals(280.0, fix.altitudeMeters); assertEquals(3.0, fix.vAccuracyMeters)
        assertEquals(13.4f.toDouble(), fix.speedMps); assertEquals(90.0, fix.bearingDegrees)
        assertEquals(12_345L, fix.fixElapsedMs); assertEquals(FixProvider.GPS, fix.provider)
    }

    @Test fun optionalFieldsStayAbsentWhenThePlatformHasNoValue() {
        val fix = LocationFixes.from(location(LocationManager.FUSED_PROVIDER))!!
        assertNull(fix.altitudeMeters); assertNull(fix.vAccuracyMeters); assertNull(fix.speedMps); assertNull(fix.bearingDegrees)
        assertEquals(FixProvider.FUSED, fix.provider)
    }

    @Test fun mockLocationsFixesWithoutAccuracyAndUnknownProvidersAreNotUsed() {
        assertNull(LocationFixes.from(location { isMock = true }))
        assertNull(LocationFixes.from(location { removeAccuracy() }))
        assertNull(LocationFixes.from(location("passive")))
    }

    @Test fun permissionsNeededDependOnTheActionAndTheSdk() {
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION", "android.permission.POST_NOTIFICATIONS"), PrizmPermissions.required(true, true, 34))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), PrizmPermissions.required(true, true, 31))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), PrizmPermissions.required(true, false, 34))
        assertEquals(emptyList(), PrizmPermissions.required(false, false, 34))
    }
}
