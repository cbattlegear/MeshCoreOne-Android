// PortedFrom: MC1/Services/Geocoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// The Swift original is a thin `Sendable` protocol wrapping `CLGeocoder` so `RegionResolver`
// can depend on a `GeocodeResult` value type instead of an un-constructible `CLPlacemark`.
// Android's reverse-geocoding equivalent (the platform `location.Geocoder` class) is likewise a narrow
// native-adapter role (deferred - see docs/android/deviations/WP-218.md); this file ports only
// the pure role interface and the value type, exactly as the source does. There is no
// orchestration logic to port here (unlike LocationService) - that stays entirely with the
// future native adapter and RegionResolver's own consumer, matching the source split.
package com.meshcoreone.android.core.services.content

import java.util.Locale

/**
 * Mirrors the Swift `GeocodeResult` struct field-for-field. `Equatable` there maps to Kotlin
 * `data class` equality here.
 */
data class GeocodeResult(
    val countryCode: String?,
    val administrativeArea: String?,
    val subAdministrativeArea: String?,
)

sealed class GeocoderError(message: String? = null, cause: Throwable? = null) : Exception(message, cause) {
    object Unavailable : GeocoderError()
    object RequestInProgress : GeocoderError()
    object Closed : GeocoderError()
    class RequestFailed(message: String, cause: Throwable? = null) : GeocoderError(message, cause)
}

/**
 * Narrow producer-role port for the real Android reverse-geocoding API (the platform
 * `location.Geocoder` class). A future native adapter (`app/content`, deferred - see docs/android/deviations/
 * WP-218.md) implements this against a real platform `location.Geocoder`; this module only
 * depends on the shape of the port, exactly as the Swift `Geocoder` protocol does for
 * `CLGeocoder`.
 */
interface Geocoder {
    /**
     * Resolves [coordinate] to a [GeocodeResult], or `null` if no placemark was found - mirrors
     * the source's `placemarks.first` optional-unwrap exactly rather than throwing on an empty
     * result. [preferredLocale] mirrors the source's optional `Locale?` parameter; `null` means
     * "use the platform default", same as the source passing `nil` to `CLGeocoder`.
     */
    suspend fun reverseGeocode(coordinate: GeoCoordinate, preferredLocale: Locale?): GeocodeResult?

    /**
     * Cancels any in-flight [reverseGeocode] request. The source's own doc comment notes a
     * fresh `CLGeocoder` per call would make this a no-op; the real adapter must likewise retain
     * a single underlying geocoder instance for this to do anything.
     */
    fun cancelGeocode()
}
