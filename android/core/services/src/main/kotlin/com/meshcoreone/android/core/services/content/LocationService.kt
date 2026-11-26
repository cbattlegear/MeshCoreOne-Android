// PortedFrom: MC1/Services/LocationService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// The Swift original is a `CLLocationManagerDelegate` whose delegate callbacks
// (`didUpdateLocations`/`didFailWithError`/`locationManagerDidChangeAuthorization`) resume
// `CheckedContinuation`s set up by `requestCurrentLocation`. Android's `location` package
// APIs are callback-based in the exact same shape (a `LocationListener`/permission-callback
// pair rather than a Kotlin suspend function), so that real platform wiring is the
// `LocationProducing` native-adapter boundary (deferred - see docs/android/deviations/
// WP-218.md): this file ports the orchestration policy itself - one-request-at-a-time,
// permission-wait-then-request, independent location/permission timeouts, typed failure
// states - as pure JVM code against that injected role, exactly mirroring the original's
// control flow with coroutines standing in for continuations.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/** Mirrors the Swift `CLAuthorizationStatus` cases this port's policy actually branches on. */
enum class LocationAuthorizationStatus {
    NOT_DETERMINED,
    AUTHORIZED_WHEN_IN_USE,
    AUTHORIZED_ALWAYS,
    DENIED,
    RESTRICTED,
}

/** Source errors plus the explicit native permission-request capability boundary. */
sealed class LocationServiceError : Exception() {
    data class NotAuthorized(val status: LocationAuthorizationStatus) : LocationServiceError()
    object RequestInProgress : LocationServiceError()
    object PermissionTimeout : LocationServiceError()
    object PermissionRequestUnavailable : LocationServiceError()
    object LocationTimeout : LocationServiceError()
    data class RequestFailed(override val message: String) : LocationServiceError()
}

/**
 * Narrow producer-role port for the real Android location APIs (the platform
 * `location.LocationManager` class, never Google Play Services' `FusedLocationProviderClient` - GMS is
 * explicitly not a prerequisite for this optional feature). A future native adapter
 * (`app/content`, deferred - see docs/android/deviations/WP-218.md) implements this against a
 * real `LocationManager`/permission-callback pair; this module only depends on the shape of
 * the port.
 */
interface LocationProducing {
    /** Current permission status; the native adapter owns updating this as the OS reports it. */
    val authorizationStatus: StateFlow<LocationAuthorizationStatus>

    /** Triggers the platform permission prompt if [authorizationStatus] is [LocationAuthorizationStatus.NOT_DETERMINED]. */
    fun requestPermissionIfNeeded()

    /**
     * Requests one fresh location fix. Suspends until the platform delivers a fix or fails;
     * does not itself enforce authorization or impose a timeout - both are this port's
     * [LocationService] orchestration responsibility, not the native role's.
     */
    suspend fun requestLocation(): GeoCoordinate
}

/**
 * Orchestrates [LocationProducing] exactly as the Swift `LocationService` orchestrates
 * `CLLocationManager`: only one [requestCurrentLocation] in flight at a time, permission is
 * requested and awaited (bounded by [permissionTimeoutMs]) before falling through to the
 * platform location request (bounded by its own `timeoutMs`), and every failure mode maps to a
 * typed [LocationServiceError] - never a silent null/default coordinate.
 */
class LocationService(
    private val producing: LocationProducing,
    private val permissionTimeoutMs: Long = 30_000,
) {
    private val requestLock = Mutex()

    val authorizationStatus: LocationAuthorizationStatus get() = producing.authorizationStatus.value

    val isAuthorized: Boolean
        get() = authorizationStatus == LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE ||
            authorizationStatus == LocationAuthorizationStatus.AUTHORIZED_ALWAYS

    val hasRequestedPermission: Boolean
        get() = authorizationStatus != LocationAuthorizationStatus.NOT_DETERMINED

    val isLocationDenied: Boolean
        get() = authorizationStatus == LocationAuthorizationStatus.DENIED ||
            authorizationStatus == LocationAuthorizationStatus.RESTRICTED

    /**
     * Requests the current location, prompting for permission first if not yet determined.
     * Mirrors the Swift original's `requestCurrentLocation(timeout:)` exactly: rejects a second
     * concurrent call with [LocationServiceError.RequestInProgress] rather than coalescing it,
     * since (unlike [LinkPreviewCache]'s network fetch) a stale concurrent location caller
     * genuinely wants its own fresh fix attempt policy, matching the source's one-continuation-
     * at-a-time field.
     */
    suspend fun requestCurrentLocation(timeoutMs: Long = 10_000): GeoCoordinate {
        if (!requestLock.tryLock()) throw LocationServiceError.RequestInProgress
        try {
            if (!isAuthorized) {
                if (authorizationStatus == LocationAuthorizationStatus.NOT_DETERMINED) {
                    producing.requestPermissionIfNeeded()
                    waitForAuthorizationDecision()
                }
                if (!isAuthorized) throw LocationServiceError.NotAuthorized(authorizationStatus)
            }

            return try {
                withTimeout(timeoutMs) { producing.requestLocation() }
            } catch (timeout: TimeoutCancellationException) {
                throw LocationServiceError.LocationTimeout
            } catch (error: LocationServiceError) {
                throw error
            } catch (cancellation: CancellationException) {
                // A genuine outer cancellation (e.g. the caller's own coroutine was cancelled),
                // distinct from `TimeoutCancellationException` above: must propagate untouched,
                // not get coerced into a RequestFailed outcome - cancellation is cooperative
                // coroutine machinery, not a producer failure, mirroring this module's
                // established precedent (see this file's header and `RegionResolver`).
                throw cancellation
            } catch (error: Exception) {
                throw LocationServiceError.RequestFailed(error.message ?: error.toString())
            }
        } finally {
            requestLock.unlock()
        }
    }

    private suspend fun waitForAuthorizationDecision() {
        val decided = withTimeoutOrNull(permissionTimeoutMs) {
            producing.authorizationStatus.filter { it != LocationAuthorizationStatus.NOT_DETERMINED }.first()
        }
        if (decided == null) throw LocationServiceError.PermissionTimeout
    }
}
