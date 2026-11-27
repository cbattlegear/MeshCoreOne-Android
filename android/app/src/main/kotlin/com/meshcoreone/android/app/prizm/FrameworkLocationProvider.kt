// AndroidOnly: WP-101 Prizm Mobile Observer one-shot location for "use my current location" in the privacy zone form.
package com.meshcoreone.android.app.prizm

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import com.meshcoreone.android.feature.settings.prizm.PrizmCoordinate
import java.util.concurrent.Executor
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

class FrameworkLocationProvider(private val context: Context, private val timeoutMs: Long = 15_000L) : PrizmLocationProvider {
    @SuppressLint("MissingPermission")
    override suspend fun currentLocation(): PrizmCoordinate? {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!granted) return null
        val manager = context.getSystemService(LocationManager::class.java)
        val provider = listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) } ?: return null
        val fix: Location? = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { continuation ->
                val signal = CancellationSignal()
                continuation.invokeOnCancellation { signal.cancel() }
                try {
                    manager.getCurrentLocation(provider, signal, Executor { it.run() }) { continuation.resumeWith(Result.success(it)) }
                } catch (error: SecurityException) {
                    continuation.resumeWith(Result.success(null))
                }
            }
        }
        return fix?.let { PrizmCoordinate(it.latitude, it.longitude) }
    }
}
