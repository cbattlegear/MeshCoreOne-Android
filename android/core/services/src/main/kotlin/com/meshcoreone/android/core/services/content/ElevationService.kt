// PortedFrom: MC1/Services/ElevationService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// The source actor owns three concerns in one type: pure point-sampling/sample-count math,
// request-URL construction, and HTTP-with-retry-and-JSON-decode against Open-Meteo's bulk
// elevation endpoint. This port keeps the first two pure JVM (and ports them against the
// source's exact thresholds/formulas) but defers HTTP execution AND response JSON decoding to an
// injected [ElevationFetching] role: the Android-compatible HTTP backend (OkHttp proposal) is
// still pending coordinator sign-off (see docs/android/deviations/WP-218.md), and using
// kotlinx-serialization here would extend an admission scoped specifically to
// InlineImageDimensionsStore, not requested for this file. This port owns only the retry/backoff
// orchestration and request-parameter formatting, matching the source's own
// performRequest/parseElevationResponse split at the boundary where each attempt crosses into
// the native adapter.
//
// `fetchElevations`' per-sample `distanceFromAMeters` further depends on `RFCalculator.distance`,
// whose *primary file* the port-manifest assigns to WP-212 (RF/*, not yet merged). The one
// reusable calculation it needs (`RFCalculator.distance(from:to:)`'s Haversine formula) is now
// ported as `rf.GeodesicDistance.metersBetween` - a narrow, explicitly admitted WP-218
// prerequisite (see that file's own header), not a hand-duplicated/conflicting re-derivation.
// [distanceMeters] now defaults to it, while remaining overridable (e.g. by a test fake) exactly
// like [LocationProducing]/[Geocoder] remain injectable roles.
//
// The source's own per-sample result struct, `ElevationSample`, is likewise NOT this file's to
// port: it is a shared RF-module type at `MC1Services/Sources/MC1Services/RF/ElevationSample.swift`,
// also assigned to WP-212 (cross-referenced by `RFCalculator`/`PathAnalysisResult`/etc). This port
// instead returns a WP-218-local [ElevationPathSample] shape, so as not to pre-empt WP-212's file
// under a borrowed name; unifying the two is WP-212's/an integration WP's concern.
package com.meshcoreone.android.core.services.content

import com.meshcoreone.android.core.services.rf.GeodesicDistance
import kotlinx.coroutines.delay

/** Mirrors the Swift `ElevationServiceError` enum's cases and payloads exactly; message
 * rendering (the source's `errorDescription`/L10n strings) is an app-layer presentation concern,
 * out of core:services' pure-JVM scope - this port preserves the error *identity and payload*
 * parity the source's tests actually exercise, not the localized string content. */
sealed class ElevationServiceError : Exception() {
    data class NetworkError(val description: String) : ElevationServiceError()
    object InvalidResponse : ElevationServiceError()
    data class ApiError(val apiMessage: String) : ElevationServiceError()
    object NoData : ElevationServiceError()
    object RateLimited : ElevationServiceError()
}

/**
 * A WP-218-local result shape for one sampled point along an elevation path.
 *
 * This is deliberately NOT named/ported as the source's `ElevationSample` struct: that type
 * actually lives at `MC1Services/Sources/MC1Services/RF/ElevationSample.swift`, which the
 * port-manifest assigns to **WP-212** (cross-referenced by `RFCalculator.swift`,
 * `PathAnalysisResult.swift`, etc. - it is a shared RF-module type, not local to
 * `ElevationService.swift`). Porting it here under WP-218 would claim a file this work package
 * does not own. Once WP-212 lands its own `ElevationSample` Kotlin type, unifying this service's
 * return type with it is that integration's concern, not a silent WP-218 pre-emption.
 */
data class ElevationPathSample(
    val coordinate: GeoCoordinate,
    val elevation: Double,
    val distanceFromAMeters: Double,
)

/** Result of one HTTP attempt against the bulk elevation endpoint, in request-coordinate order.
 * Distinguishes exactly the outcomes [ElevationService]'s retry policy branches on, mirroring the
 * source's `performRequest`'s own status-code/exception handling. */
sealed class ElevationFetchAttempt {
    /** [elevations] is already response-JSON-decoded, in the same order as the request
     * coordinates - mirrors the source's `parseElevationResponse` contract exactly. */
    data class Success(val elevations: List<Double>) : ElevationFetchAttempt()
    object RateLimited : ElevationFetchAttempt()
    data class HttpError(val statusCode: Int) : ElevationFetchAttempt()
    data class NetworkError(val message: String) : ElevationFetchAttempt()
    object InvalidResponse : ElevationFetchAttempt()
}

/**
 * Narrow producer-role port for the real Open-Meteo bulk elevation HTTP call (pending the
 * Android-compatible HTTP backend - see docs/android/deviations/WP-218.md). Owns both request
 * execution and response JSON decoding for a single attempt; [ElevationService] owns only the
 * retry/backoff policy and request-parameter formatting around it, matching the source's own
 * actor-method split.
 */
interface ElevationFetching {
    /**
     * Performs one request for the given comma-joined latitude/longitude lists (already
     * formatted to 6 decimal places with a dot separator, matching the source's
     * `en_US_POSIX`-locale `FloatingPointFormatStyle`), in the same order as the coordinates
     * [ElevationService] is resolving.
     */
    suspend fun fetchElevations(latitudes: String, longitudes: String): ElevationFetchAttempt
}

/**
 * Pure-JVM port of `MC1/Services/ElevationService.swift`'s sampling math and retry/backoff
 * orchestration, against the injected [fetching] role and [distanceMeters] (standing in for
 * `RFCalculator.distance`, not yet ported - see file header).
 */
class ElevationService(
    private val fetching: ElevationFetching,
    private val distanceMeters: (GeoCoordinate, GeoCoordinate) -> Double = GeodesicDistance::metersBetween,
) {
    /** Fetch elevation for a single coordinate. */
    suspend fun fetchElevation(coordinate: GeoCoordinate): Double {
        val samples = fetchElevations(listOf(coordinate))
        return samples.firstOrNull()?.elevation ?: throw ElevationServiceError.NoData
    }

    /** Fetch elevations for multiple coordinates along a path, with distance-from-first-point. */
    suspend fun fetchElevations(path: List<GeoCoordinate>): List<ElevationPathSample> {
        if (path.isEmpty()) throw ElevationServiceError.NoData

        val coordinatesToFetch =
            if (path.size > MAX_POINTS_PER_REQUEST) subsample(path, MAX_POINTS_PER_REQUEST) else path

        val latitudes = coordinatesToFetch.joinToString(",") { formatCoordinate(it.latitude) }
        val longitudes = coordinatesToFetch.joinToString(",") { formatCoordinate(it.longitude) }

        val elevations = performRequest(latitudes, longitudes)
        if (elevations.size != coordinatesToFetch.size) throw ElevationServiceError.InvalidResponse

        val startCoordinate = coordinatesToFetch[0]
        return coordinatesToFetch.mapIndexed { index, coordinate ->
            ElevationPathSample(
                coordinate = coordinate,
                elevation = elevations[index],
                distanceFromAMeters = distanceMeters(startCoordinate, coordinate),
            )
        }
    }

    /** Performs [fetching] with exponential backoff retry on rate limiting, mirroring the
     * source's `performRequest` exactly. */
    private suspend fun performRequest(latitudes: String, longitudes: String): List<Double> {
        repeat(MAX_RETRIES) { attempt ->
            when (val result = fetching.fetchElevations(latitudes, longitudes)) {
                is ElevationFetchAttempt.Success -> return result.elevations
                is ElevationFetchAttempt.RateLimited -> {
                    delay(BASE_RETRY_DELAY_MS * (1L shl attempt))
                }
                is ElevationFetchAttempt.NetworkError -> throw ElevationServiceError.NetworkError(result.message)
                is ElevationFetchAttempt.HttpError -> throw ElevationServiceError.ApiError("HTTP ${result.statusCode}")
                ElevationFetchAttempt.InvalidResponse -> throw ElevationServiceError.InvalidResponse
            }
        }
        throw ElevationServiceError.RateLimited
    }

    companion object {
        private const val MAX_POINTS_PER_REQUEST = 100
        private const val MAX_RETRIES = 3
        private const val BASE_RETRY_DELAY_MS = 500L

        private const val THRESHOLD_UNDER_1KM = 1000.0
        private const val THRESHOLD_1_TO_5KM = 5000.0
        private const val THRESHOLD_5_TO_20KM = 20000.0

        private const val SAMPLE_COUNT_UNDER_1KM = 20
        private const val SAMPLE_COUNT_1_TO_5KM = 50
        private const val SAMPLE_COUNT_5_TO_20KM = 80
        private const val SAMPLE_COUNT_OVER_20KM = 100

        /** Generate evenly spaced coordinates between two points, including both endpoints. */
        fun sampleCoordinates(from: GeoCoordinate, to: GeoCoordinate, sampleCount: Int): List<GeoCoordinate> {
            val count = sampleCount.coerceIn(2, MAX_POINTS_PER_REQUEST)
            val coordinates = ArrayList<GeoCoordinate>(count)
            for (i in 0 until count) {
                val fraction = i.toDouble() / (count - 1).toDouble()
                coordinates.add(
                    GeoCoordinate(
                        latitude = from.latitude + fraction * (to.latitude - from.latitude),
                        longitude = from.longitude + fraction * (to.longitude - from.longitude),
                    ),
                )
            }
            return coordinates
        }

        /** Calculate optimal sample count based on distance, mirroring the source's thresholds
         * exactly. */
        fun optimalSampleCount(distanceMeters: Double): Int = when {
            distanceMeters < THRESHOLD_UNDER_1KM -> SAMPLE_COUNT_UNDER_1KM
            distanceMeters < THRESHOLD_1_TO_5KM -> SAMPLE_COUNT_1_TO_5KM
            distanceMeters < THRESHOLD_5_TO_20KM -> SAMPLE_COUNT_5_TO_20KM
            else -> SAMPLE_COUNT_OVER_20KM
        }

        /** Subsample a list to a target count, preserving first and last elements, mirroring the
         * source's own index-stepping formula exactly. */
        private fun <T> subsample(list: List<T>, targetCount: Int): List<T> {
            if (list.size <= targetCount || targetCount < 2) return list

            val result = ArrayList<T>(targetCount)
            result.add(list[0])

            val middleCount = targetCount - 2
            if (middleCount > 0) {
                val step = (list.size - 2).toDouble() / (middleCount + 1).toDouble()
                for (i in 1..middleCount) {
                    val index = (i * step).toInt()
                    result.add(list[index])
                }
            }

            result.add(list[list.size - 1])
            return result
        }

        /** Mirrors the source's `en_US_POSIX`-locale, 6-decimal-place formatting: `Locale.ROOT`
         * is likewise always dot-decimal regardless of the JVM default locale. */
        private fun formatCoordinate(value: Double): String = String.format(java.util.Locale.ROOT, "%.6f", value)
    }
}
