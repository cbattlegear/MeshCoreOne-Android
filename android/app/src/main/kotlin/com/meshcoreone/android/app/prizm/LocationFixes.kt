// AndroidOnly: WP-101 Prizm Mobile Observer conversion of a platform Location into the observer's plain LocationFix.
package com.meshcoreone.android.app.prizm

import android.location.Location
import android.location.LocationManager
import com.meshcoreone.android.core.observer.location.FixProvider
import com.meshcoreone.android.core.observer.location.LocationFix

internal object LocationFixes {
    /**
     * Null (the fix is not used) for a mock location, a provider the spec has no name for, or a fix with no accuracy: the
     * accuracy decides whether a fix may touch a privacy zone, so a fix without one is not trusted. `fixElapsedMs` is on the
     * `elapsedRealtime` clock, the same one packets are stamped with.
     */
    fun from(location: Location): LocationFix? {
        if (location.isMock || !location.hasAccuracy()) return null
        val provider = when (location.provider) {
            LocationManager.GPS_PROVIDER -> FixProvider.GPS
            LocationManager.FUSED_PROVIDER -> FixProvider.FUSED
            LocationManager.NETWORK_PROVIDER -> FixProvider.NETWORK
            else -> return null
        }
        return LocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            hAccuracyMeters = location.accuracy.toDouble(),
            altitudeMeters = location.takeIf { it.hasAltitude() }?.altitude,
            vAccuracyMeters = location.takeIf { it.hasVerticalAccuracy() }?.verticalAccuracyMeters?.toDouble(),
            speedMps = location.takeIf { it.hasSpeed() }?.speed?.toDouble(),
            bearingDegrees = location.takeIf { it.hasBearing() }?.bearing?.toDouble(),
            fixElapsedMs = location.elapsedRealtimeNanos / NANOS_PER_MILLI,
            provider = provider,
        ).takeIf { it.isValid }
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
