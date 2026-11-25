// PortedFrom: MC1/Services/Geocoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// One retained operation owns its continuation and legacy IO job. Android exposes no physical
// geocoder cancellation API: cancellation completes the caller immediately and rejects late
// platform results; a legacy Binder call may finish later inside this adapter's owned scope.
package com.meshcoreone.android.app.content

import android.content.Context
import android.location.Address
import android.location.Geocoder as AndroidGeocoder
import android.os.Build
import androidx.annotation.RequiresApi
import com.meshcoreone.android.core.services.content.GeoCoordinate
import com.meshcoreone.android.core.services.content.Geocoder
import com.meshcoreone.android.core.services.content.GeocodeResult
import com.meshcoreone.android.core.services.content.GeocoderError
import java.util.Locale
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Real [Geocoder] producer using [android.location.Geocoder]. Field mapping to [GeocodeResult]
 * mirrors the source's `CLPlacemark` field selection exactly: `countryCode` <- `Address
 * .countryCode`, `administrativeArea` <- `Address.adminArea`, `subAdministrativeArea` <-
 * `Address.subAdminArea`.
 */
class AndroidGeocoderAdapter(
    private val context: Context,
    /**
     * Constructs the underlying [AndroidGeocoder] for one [reverseGeocode] call. Defaults to a
     * real `android.location.Geocoder(context, locale)` in production; overridable only so tests
     * can hand back a Robolectric-shadowed instance they've pre-seeded via
     * `ShadowGeocoder.setFromLocation` (an instance-scoped shadow method, not a static one) --
     * this does not change production behavior, which always takes this default.
     */
    private val geocoderFactory: (Locale) -> AndroidGeocoder = { locale -> AndroidGeocoder(context, locale) },
        dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Geocoder, AutoCloseable {
        private val lock = Any()
        private val workers = CoroutineScope(SupervisorJob() + dispatcher)
        private var active: Request? = null
        private var closed = false

        private class Request(val continuation: CancellableContinuation<GeocodeResult?>) {
            var worker: Job? = null
        }

        override suspend fun reverseGeocode(coordinate: GeoCoordinate, preferredLocale: Locale?): GeocodeResult? =
            suspendCancellableCoroutine { continuation ->
                val request = Request(continuation)
                val failure = synchronized(lock) {
                    when {
                        closed -> GeocoderError.Closed
                        active != null -> GeocoderError.RequestInProgress
                        !AndroidGeocoder.isPresent() -> GeocoderError.Unavailable
                        else -> {
                            active = request
                            null
                        }
                    }
                }
                if (failure != null) {
                    continuation.resumeWith(Result.failure(failure))
                    return@suspendCancellableCoroutine
                }
                continuation.invokeOnCancellation { cancel(request) }
                if (!owns(request)) return@suspendCancellableCoroutine
                try {
                    val geocoder = geocoderFactory(preferredLocale ?: Locale.getDefault())
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        geocodeAsync(request, geocoder, coordinate)
                    } else {
                        val worker = workers.launch {
                            try {
                                ensureActive()
                                @Suppress("DEPRECATION")
                                val addresses = geocoder.getFromLocation(coordinate.latitude, coordinate.longitude, 1)
                                complete(request, Result.success(addresses.toResult()))
                            } catch (cancellation: CancellationException) {
                                cancel(request)
                                throw cancellation
                            } catch (cancellation: CancellationException) {
                                cancel(request)
                                throw cancellation
                            } catch (error: Exception) {
                                complete(request, Result.failure(GeocoderError.RequestFailed("Reverse geocoding failed", error)))
                            }
                        }
                        synchronized(lock) {
                            if (active === request) request.worker = worker else worker.cancel()
                        }
                    }
                } catch (error: Exception) {
                    complete(request, Result.failure(GeocoderError.RequestFailed("Reverse geocoding could not start", error)))
                }
            }

        private fun owns(request: Request): Boolean = synchronized(lock) { active === request }

        private fun complete(request: Request, result: Result<GeocodeResult?>) {
            val claimed = synchronized(lock) {
                if (active !== request) false else {
                    active = null
                    true
                }
            }
            if (claimed) request.continuation.resumeWith(result)
        }

        private fun cancel(request: Request) {
            val claimed = synchronized(lock) {
                if (active !== request) false else {
                    active = null
                    true
                }
            }
            if (claimed) {
                request.worker?.cancel()
                request.continuation.cancel(CancellationException("Reverse geocoding cancelled"))
            }
        }

        override fun cancelGeocode() {
            synchronized(lock) { active }?.let(::cancel)
        }

        override fun close() {
            synchronized(lock) { closed = true }
            cancelGeocode()
            workers.cancel()
        }

        private fun List<Address>?.toResult(): GeocodeResult? = this?.firstOrNull()?.let {
            GeocodeResult(it.countryCode, it.adminArea, it.subAdminArea)
        }

        @RequiresApi(Build.VERSION_CODES.TIRAMISU)
        private fun geocodeAsync(request: Request, geocoder: AndroidGeocoder, coordinate: GeoCoordinate) {
            if (!owns(request)) return
            geocoder.getFromLocation(
                coordinate.latitude,
                coordinate.longitude,
                1,
                object : AndroidGeocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) {
                        complete(request, Result.success(addresses.toResult()))
                    }

                    override fun onError(errorMessage: String?) {
                        complete(request, Result.failure(
                            GeocoderError.RequestFailed(errorMessage ?: "Reverse geocoding provider failed"),
                        ))
                    }
                },
            )
        }
}
