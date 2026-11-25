// PortedFrom: MC1/Services/RegionResolver.swift@5e980a53e077c45a1270170612fdf5dae97792ff
//
// The source lives in MC1 (not MC1Services) specifically because MC1Services is intentionally
// CoreLocation-free -- but every dependency it actually orchestrates (`LocationService`,
// `Geocoder`, `RegionSelection`) already has a pure-JVM home in this module (`content`/
// `core:model`), so the orchestration policy itself ports here exactly like
// `ElevationService`/`LinkPreviewCache` do: no CoreLocation/OSLog/@MainActor import exists to
// port, only the actual control flow.
//
// The default matcher delegates to the coordinator-admitted, frozen WP-211 RegionalAreas
// producer. The injectable role remains for deterministic location/geocoder consumer tests;
// the producer's six borrowed files are not modified or claimed as WP-218 source acceptance.
//
// Everything else is implemented for real: coordinate rounding to the nearest whole degree for
// the cache key (round-half-away-from-zero, matching Swift's default `Double.rounded()`), the
// 24-hour cache TTL, the independent 5-second location-fetch and 5-second geocode timeouts, the
// lowercase/trim/diacritic-fold normalization step (Java's canonical-decomposition `Normalizer`
// is the direct pure-JVM equivalent of `.folding(options: .diacriticInsensitive, locale:)`), the
// " county" suffix strip, and the catch-all-return-null failure policy the source's own doc
// comment specifies as intentional ("Failure modes are silent -- callers fall through to manual
// picker"), not a hidden-bug broad catch.
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.model.RegionSelection
import java.text.Normalizer
import java.util.Locale
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * Narrow producer-role port over the `RegionalAreas` geographic
 * catalog (WP-211): translates a geocoder's free-text administrative-area/sub-administrative-area
 * strings into the stable subdivision/county codes [RegionSelection] actually stores. Mirrors the
 * source's `RegionalAreas.matchSubdivision`/`matchCounty` static functions' exact signatures and
 * null-on-no-match contract.
 */
interface RegionAreaMatching {
    /** Matches [normalized] (already lowercased/trimmed/diacritic-folded) against [country]'s
     * known subdivisions, returning the ISO 3166-2 code or `null` for no match / `null` input. */
    fun matchSubdivision(country: String, normalized: String?): String?

    /** Matches [normalized] against [country]/[state]'s known counties, returning a stable county
     * key or `null` for no match / `null` input. */
    fun matchCounty(country: String, state: String?, normalized: String?): String?
}

/**
 * Resolves a [RegionSelection] from the device's current location, exactly mirroring the Swift
 * `RegionResolver`'s one-shot `resolve()` orchestration: location fetch (bounded by
 * [locationTimeoutMs]), a 24-hour per-rounded-coordinate cache, reverse geocoding (bounded by
 * [geocodeTimeoutMs], cancelling the geocoder on timeout), administrative-area/county
 * normalization, and [areaMatching] code lookup. Every failure mode (unauthorized, no fix, geocode
 * timeout/error, nil country code) resolves to `null` -- the source's documented contract is that
 * callers fall through to a manual region picker, never that a missing/denied location is an
 * application error.
 */
class RegionResolver(
    private val location: LocationService,
    private val geocoder: Geocoder,
    private val areaMatching: RegionAreaMatching = RegionalAreaMatcher,
    private val locationTimeoutMs: Long = LOCATION_TIMEOUT_MS,
    private val geocodeTimeoutMs: Long = GEOCODE_TIMEOUT_MS,
    private val cacheTtlMs: Long = CACHE_TTL_MS,
    private val clockMs: () -> Long = System::currentTimeMillis,
) {
    private val cacheMutex = Mutex()
    private val cache = mutableMapOf<CacheKey, CachedResult>()

    /**
     * Returns a [RegionSelection] derived from the device's current location, or `null` for any
     * failure (denied, timeout, no network, nil country code). Mirrors the source's `resolve()`
     * exactly, including its intentional catch-all-returns-nil policy.
     */
    suspend fun resolve(): RegionSelection? {
        if (!location.isAuthorized) return null
        return try {
            val coordinate = location.requestCurrentLocation(locationTimeoutMs)
            val key = CacheKey.rounding(coordinate)

            val cached = cacheMutex.withLock { cache[key] }
            if (cached != null && cached.isFresh(clockMs())) return cached.value

            val result = reverseGeocode(coordinate)
            val countryCode = result?.countryCode ?: return null

            val normalizedAdmin = result.administrativeArea?.let(::normalize)
            val normalizedCounty = result.subAdministrativeArea
                ?.let(::normalize)
                ?.replace(COUNTY_SUFFIX, "")

            val adminCode = areaMatching.matchSubdivision(countryCode, normalizedAdmin)
            val countyKey = areaMatching.matchCounty(countryCode, adminCode, normalizedCounty)

            val selection = RegionSelection(
                countryCode = countryCode,
                source = RegionSelection.Source.LOCATION,
                administrativeAreaCode = adminCode,
                countyKey = countyKey,
            )
            cacheMutex.withLock {
                cache[key] = CachedResult(selection, expiresAtMs = clockMs() + cacheTtlMs)
            }
            selection
        } catch (error: Exception) {
            Logger.getLogger("MeshCore.RegionResolver").fine(
                "Region resolution failed: ${error.javaClass.simpleName}",
            )
            null
        }
    }

    /**
     * Bounds the geocoder call with [geocodeTimeoutMs] and cancels it on timeout (or any other
     * cancellation), mirroring the source's `withTimeout`/`withTaskCancellationHandler` pair.
     */
    private suspend fun reverseGeocode(coordinate: GeoCoordinate): GeocodeResult? =
        withTimeout(geocodeTimeoutMs) {
            try {
                geocoder.reverseGeocode(coordinate, GEOCODING_LOCALE)
            } catch (cancellation: CancellationException) {
                geocoder.cancelGeocode()
                throw cancellation
            }
        }

    private fun normalize(raw: String): String {
        val folded = Normalizer.normalize(raw.trim(), Normalizer.Form.NFD)
            .replace(DIACRITIC_MARKS, "")
        return folded.lowercase(GEOCODING_LOCALE)
    }

    private data class CacheKey(val roundedLatitude: Int, val roundedLongitude: Int) {
        companion object {
            fun rounding(coordinate: GeoCoordinate): CacheKey = CacheKey(
                roundAwayFromZero(coordinate.latitude),
                roundAwayFromZero(coordinate.longitude),
            )

            /** Round-half-away-from-zero, matching Swift's default `Double.rounded()`
             * (`.toNearestOrAwayFromZero`) -- Kotlin/Java's `Math.round` rounds half-up instead,
             * which diverges for negative half-integer coordinates (e.g. -2.5). */
            private fun roundAwayFromZero(value: Double): Int =
                if (value >= 0.0) Math.floor(value + 0.5).toInt() else Math.ceil(value - 0.5).toInt()
        }
    }

    private data class CachedResult(val value: RegionSelection, val expiresAtMs: Long) {
        fun isFresh(nowMs: Long): Boolean = nowMs < expiresAtMs
    }

    companion object {
        const val LOCATION_TIMEOUT_MS: Long = 5_000
        const val GEOCODE_TIMEOUT_MS: Long = 5_000
        const val CACHE_TTL_MS: Long = 24 * 60 * 60 * 1000

        private val GEOCODING_LOCALE: Locale = Locale.forLanguageTag("en-US")
        private const val COUNTY_SUFFIX = " county"
        private val DIACRITIC_MARKS = Regex("\\p{Mn}+")
    }
}
