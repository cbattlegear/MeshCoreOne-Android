// PortedFrom: MC1Tests/Services/ElevationServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Ports every case from OptimalSampleCountTests (5) and SampleCoordinatesTests (7) verbatim -
// these are pure math with no platform dependency. ErrorTests (4 cases) asserted *localized
// message content* (`errorDescription` strings sourced from L10n.Localizable) - an app-layer
// presentation concern outside core:services' pure-JVM scope; this file instead asserts the
// error identity/payload, but these are NOT replacements for the four original localized
// presentation assertions: the source154 collector keeps those four cases BLOCKED.
// DistanceIntegrationTests now uses the admitted real GeodesicDistance prerequisite, not a
// fake distance lambda; the separate fetch wiring test deliberately still uses an injected fake.
//
// The retry/backoff/attempt-dispatch orchestration in `performRequest` has no original Swift
// *unit* test (it was only exercised by the source's own network-dependent integration paths,
// absent from the frozen unit-test tree); the cases below are independently authored, following
// the precedent already set for `LocationServiceTest.kt`.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.test.runTest
import com.meshcoreone.android.core.services.rf.GeodesicDistance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ElevationServiceTest {

    // MARK: - optimalSampleCount

    @Test
    fun `optimalSampleCount returns 20 samples for distances under 1km`() {
        assertEquals(20, ElevationService.optimalSampleCount(0.0))
        assertEquals(20, ElevationService.optimalSampleCount(500.0))
        assertEquals(20, ElevationService.optimalSampleCount(999.0))
    }

    @Test
    fun `optimalSampleCount returns 50 samples for distances 1-5km`() {
        assertEquals(50, ElevationService.optimalSampleCount(1000.0))
        assertEquals(50, ElevationService.optimalSampleCount(2500.0))
        assertEquals(50, ElevationService.optimalSampleCount(4999.0))
    }

    @Test
    fun `optimalSampleCount returns 80 samples for distances 5-20km`() {
        assertEquals(80, ElevationService.optimalSampleCount(5000.0))
        assertEquals(80, ElevationService.optimalSampleCount(10000.0))
        assertEquals(80, ElevationService.optimalSampleCount(19999.0))
    }

    @Test
    fun `optimalSampleCount returns 100 samples for distances over 20km`() {
        assertEquals(100, ElevationService.optimalSampleCount(20000.0))
        assertEquals(100, ElevationService.optimalSampleCount(50000.0))
        assertEquals(100, ElevationService.optimalSampleCount(100_000.0))
    }

    @Test
    fun `optimalSampleCount never exceeds 100`() {
        val distances = listOf(0, 100, 500, 1000, 2000, 5000, 10000, 20000, 50000, 100_000, 1_000_000)
        for (distance in distances) {
            val count = ElevationService.optimalSampleCount(distance.toDouble())
            assertTrue(count <= 100, "Sample count $count exceeds 100 for distance ${distance}m")
        }
    }

    // MARK: - sampleCoordinates

    private val pointA = GeoCoordinate(latitude = 37.7749, longitude = -122.4194)
    private val pointB = GeoCoordinate(latitude = 37.8049, longitude = -122.3894)

    @Test
    fun `sampleCoordinates first coordinate equals pointA`() {
        val samples = ElevationService.sampleCoordinates(pointA, pointB, 10)
        assertEquals(pointA.latitude, samples.first().latitude)
        assertEquals(pointA.longitude, samples.first().longitude)
    }

    @Test
    fun `sampleCoordinates last coordinate equals pointB`() {
        val samples = ElevationService.sampleCoordinates(pointA, pointB, 10)
        assertEquals(pointB.latitude, samples.last().latitude)
        assertEquals(pointB.longitude, samples.last().longitude)
    }

    @Test
    fun `sampleCoordinates returns correct number of samples`() {
        for (count in listOf(2, 5, 10, 20, 50, 100)) {
            val samples = ElevationService.sampleCoordinates(pointA, pointB, count)
            assertEquals(count, samples.size, "Expected $count samples, got ${samples.size}")
        }
    }

    @Test
    fun `sampleCoordinates count clamped to minimum of 2`() {
        assertEquals(2, ElevationService.sampleCoordinates(pointA, pointB, 1).size)
        assertEquals(2, ElevationService.sampleCoordinates(pointA, pointB, 0).size)
    }

    @Test
    fun `sampleCoordinates count clamped to maximum of 100`() {
        assertEquals(100, ElevationService.sampleCoordinates(pointA, pointB, 150).size)
    }

    @Test
    fun `sampleCoordinates are evenly distributed`() {
        val samples = ElevationService.sampleCoordinates(pointA, pointB, 5)
        val latStep = (pointB.latitude - pointA.latitude) / 4
        val lonStep = (pointB.longitude - pointA.longitude) / 4

        for (i in 0 until 5) {
            val expectedLat = pointA.latitude + i * latStep
            val expectedLon = pointA.longitude + i * lonStep
            assertTrue(
                kotlin.math.abs(samples[i].latitude - expectedLat) < 0.0001,
                "Latitude at index $i differs: expected $expectedLat, got ${samples[i].latitude}",
            )
            assertTrue(
                kotlin.math.abs(samples[i].longitude - expectedLon) < 0.0001,
                "Longitude at index $i differs: expected $expectedLon, got ${samples[i].longitude}",
            )
        }
    }

    @Test
    fun `sampleCoordinates identical points return same coordinate repeated`() {
        val samples = ElevationService.sampleCoordinates(pointA, pointA, 5)
        assertEquals(5, samples.size)
        for (sample in samples) {
            assertEquals(pointA.latitude, sample.latitude)
            assertEquals(pointA.longitude, sample.longitude)
        }
    }

    @Test
    fun `Sample coordinates work with RFCalculator distance`() {
        val samples = ElevationService.sampleCoordinates(pointA, pointB, 5)
        val totalDistance = GeodesicDistance.metersBetween(pointA, pointB)
        val cumulativeDistance = samples.zipWithNext().sumOf { (from, to) ->
            GeodesicDistance.metersBetween(from, to)
        }

        assertTrue(kotlin.math.abs(cumulativeDistance - totalDistance) < 1.0)
    }

    // MARK: - ElevationServiceError identity/payload parity (see file header)

    @Test
    fun `NetworkError carries the underlying description`() {
        val error = ElevationServiceError.NetworkError("Connection failed")
        assertEquals("Connection failed", error.description)
    }

    @Test
    fun `InvalidResponse is a distinct singleton case`() {
        assertEquals(ElevationServiceError.InvalidResponse, ElevationServiceError.InvalidResponse)
    }

    @Test
    fun `ApiError carries the api message`() {
        val error = ElevationServiceError.ApiError("Rate limit exceeded")
        assertEquals("Rate limit exceeded", error.apiMessage)
    }

    @Test
    fun `NoData is a distinct singleton case`() {
        assertEquals(ElevationServiceError.NoData, ElevationServiceError.NoData)
    }

    // MARK: - fetchElevations orchestration (independently authored; see file header)

    private class FakeElevationFetching(
        private val attempts: MutableList<ElevationFetchAttempt>,
    ) : ElevationFetching {
        var callCount = 0
            private set
        var lastLatitudes: String? = null
        var lastLongitudes: String? = null

        override suspend fun fetchElevations(latitudes: String, longitudes: String): ElevationFetchAttempt {
            callCount++
            lastLatitudes = latitudes
            lastLongitudes = longitudes
            return attempts.removeAt(0)
        }
    }

    @Test
    fun `fetchElevation returns the single sample's elevation`() = runTest {
        val fetching = FakeElevationFetching(mutableListOf(ElevationFetchAttempt.Success(listOf(123.4))))
        val service = ElevationService(fetching) { _, _ -> 0.0 }
        assertEquals(123.4, service.fetchElevation(pointA))
    }

    @Test
    fun `fetchElevations attaches distanceMeters per sample from the first point`() = runTest {
        val fetching = FakeElevationFetching(
            mutableListOf(ElevationFetchAttempt.Success(listOf(10.0, 20.0, 30.0))),
        )
        val path = listOf(pointA, pointB, GeoCoordinate(0.0, 0.0))
        val calls = mutableListOf<Pair<GeoCoordinate, GeoCoordinate>>()
        val service = ElevationService(fetching) { from, dest -> calls.add(from to dest); calls.size * 100.0 }

        val samples = service.fetchElevations(path)

        assertEquals(listOf(10.0, 20.0, 30.0), samples.map { it.elevation })
        assertEquals(listOf(pointA, pointA, pointA), calls.map { it.first })
        assertEquals(path, calls.map { it.second })
        assertEquals(listOf(100.0, 200.0, 300.0), samples.map { it.distanceFromAMeters })
    }

    @Test
    fun `fetchElevations throws NoData for an empty path`() = runTest {
        val service = ElevationService(FakeElevationFetching(mutableListOf())) { _, _ -> 0.0 }
        assertFailsWith<ElevationServiceError.NoData> { service.fetchElevations(emptyList()) }
    }

    @Test
    fun `fetchElevations throws InvalidResponse when elevation count mismatches`() = runTest {
        val fetching = FakeElevationFetching(mutableListOf(ElevationFetchAttempt.Success(listOf(1.0))))
        val service = ElevationService(fetching) { _, _ -> 0.0 }
        assertFailsWith<ElevationServiceError.InvalidResponse> { service.fetchElevations(listOf(pointA, pointB)) }
    }

    @Test
    fun `fetchElevations retries with backoff on RateLimited then succeeds`() = runTest {
        val fetching = FakeElevationFetching(
            mutableListOf(
                ElevationFetchAttempt.RateLimited,
                ElevationFetchAttempt.RateLimited,
                ElevationFetchAttempt.Success(listOf(42.0)),
            ),
        )
        val service = ElevationService(fetching) { _, _ -> 0.0 }
        val samples = service.fetchElevations(listOf(pointA))
        assertEquals(42.0, samples.single().elevation)
        assertEquals(3, fetching.callCount)
    }

    @Test
    fun `fetchElevations throws RateLimited after exhausting retries`() = runTest {
        val fetching = FakeElevationFetching(
            mutableListOf(
                ElevationFetchAttempt.RateLimited,
                ElevationFetchAttempt.RateLimited,
                ElevationFetchAttempt.RateLimited,
            ),
        )
        val service = ElevationService(fetching) { _, _ -> 0.0 }
        assertFailsWith<ElevationServiceError.RateLimited> { service.fetchElevations(listOf(pointA)) }
        assertEquals(3, fetching.callCount)
    }

    @Test
    fun `fetchElevations maps HttpError to ApiError with the status code`() = runTest {
        val fetching = FakeElevationFetching(mutableListOf(ElevationFetchAttempt.HttpError(503)))
        val service = ElevationService(fetching) { _, _ -> 0.0 }
        val error = assertFailsWith<ElevationServiceError.ApiError> { service.fetchElevations(listOf(pointA)) }
        assertTrue(error.apiMessage.contains("503"))
    }

    @Test
    fun `fetchElevations maps NetworkError attempt to NetworkError error`() = runTest {
        val fetching = FakeElevationFetching(mutableListOf(ElevationFetchAttempt.NetworkError("offline")))
        val service = ElevationService(fetching) { _, _ -> 0.0 }
        val error = assertFailsWith<ElevationServiceError.NetworkError> { service.fetchElevations(listOf(pointA)) }
        assertEquals("offline", error.description)
    }

    @Test
    fun `fetchElevations maps InvalidResponse attempt directly to InvalidResponse error`() = runTest {
        val fetching = FakeElevationFetching(mutableListOf(ElevationFetchAttempt.InvalidResponse))
        val service = ElevationService(fetching) { _, _ -> 0.0 }
        assertFailsWith<ElevationServiceError.InvalidResponse> { service.fetchElevations(listOf(pointA)) }
    }

    @Test
    fun `fetchElevations subsamples paths over 100 points, preserving endpoints`() = runTest {
        val longPath = (0 until 150).map { GeoCoordinate(it.toDouble(), it.toDouble()) }
        val fetching = FakeElevationFetching(
            mutableListOf(ElevationFetchAttempt.Success(List(100) { it.toDouble() })),
        )
        val service = ElevationService(fetching) { _, _ -> 0.0 }

        val samples = service.fetchElevations(longPath)

        assertEquals(100, samples.size)
        assertEquals(longPath.first(), samples.first().coordinate)
        assertEquals(longPath.last(), samples.last().coordinate)
    }

    @Test
    fun `fetchElevations formats latitude-longitude lists dot-decimal regardless of default locale`() = runTest {
        val fetching = FakeElevationFetching(mutableListOf(ElevationFetchAttempt.Success(listOf(1.0))))
        val service = ElevationService(fetching) { _, _ -> 0.0 }
        service.fetchElevations(listOf(GeoCoordinate(37.774900, -122.419400)))
        assertEquals("37.774900", fetching.lastLatitudes)
        assertEquals("-122.419400", fetching.lastLongitudes)
    }
}
