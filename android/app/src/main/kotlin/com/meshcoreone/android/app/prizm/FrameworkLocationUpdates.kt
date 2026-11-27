// AndroidOnly: WP-101 Prizm Mobile Observer location updates from the framework LocationManager (no Play services in this app).
package com.meshcoreone.android.app.prizm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Handler
import android.os.Looper
import com.meshcoreone.android.core.observer.location.LocationFix
import com.meshcoreone.android.core.observer.session.LocationUpdates
import java.util.concurrent.Executor

/**
 * Prefers the fused provider (API 31), falls back to GPS. Re-requests with the new interval whenever the sampling policy
 * changes. Without the precise-location permission it does nothing and logs, so the service never throws on a revoked grant;
 * with no fixes the upload gate simply pauses ("waiting for a location fix").
 */
class FrameworkLocationUpdates(
    private val context: Context,
    private val onFix: (LocationFix) -> Unit,
    private val log: (String) -> Unit = {},
) : LocationUpdates {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val executor = Executor { command -> Handler(Looper.getMainLooper()).post(command) }
    private val listener = LocationListener { location: Location -> LocationFixes.from(location)?.let(onFix) }
    private var active = false

    @SuppressLint("MissingPermission")
    @Synchronized
    override fun request(intervalMs: Long) {
        if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            log("precise location not granted; no fixes will arrive")
            return
        }
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER).firstOrNull(::enabled)
        if (provider == null) { log("no location provider is enabled"); return }
        try {
            manager.removeUpdates(listener)
            val request = LocationRequest.Builder(intervalMs).setQuality(LocationRequest.QUALITY_HIGH_ACCURACY).build()
            manager.requestLocationUpdates(provider, request, executor, listener)
            active = true
        } catch (error: SecurityException) {
            log("location request refused: ${error.message}")
        }
    }

    @Synchronized
    override fun stop() {
        if (!active) return
        manager.removeUpdates(listener)
        active = false
    }

    private fun enabled(provider: String) = try { manager.isProviderEnabled(provider) } catch (_: IllegalArgumentException) { false }
}
