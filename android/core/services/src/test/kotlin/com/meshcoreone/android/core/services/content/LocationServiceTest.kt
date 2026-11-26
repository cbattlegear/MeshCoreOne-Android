// PortedFrom: MC1/Services/LocationService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// No dedicated Swift test file exists for `LocationService` (independently authored, mirroring
// the pattern already used for `RedirectSafetyPolicyTest.kt`). Exercises the real orchestration
// policy against a fake `LocationProducing`, covering every typed `LocationServiceError` case
// the source class can actually produce.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocationServiceTest {

    private class FakeLocationProducing(
        initialStatus: LocationAuthorizationStatus = LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE,
    ) : LocationProducing {
        private val status = MutableStateFlow(initialStatus)
        override val authorizationStatus: StateFlow<LocationAuthorizationStatus> = status
        var permissionRequested = false
            private set
        var locationCallCount = 0
            private set

        /** Test hook: what the next [requestLocation] call does. Defaults to a fixed fix. */
        var onRequestLocation: suspend () -> GeoCoordinate = { GeoCoordinate(37.0, -122.0) }

        fun setStatus(value: LocationAuthorizationStatus) {
            status.value = value
        }

        override fun requestPermissionIfNeeded() {
            permissionRequested = true
        }

        override suspend fun requestLocation(): GeoCoordinate {
            locationCallCount++
            return onRequestLocation()
        }
    }

    @Test
    fun `requestCurrentLocation returns the fix when already authorized`() = runTest {
        val producing = FakeLocationProducing(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE)
        val service = LocationService(producing)

        val fix = service.requestCurrentLocation()

        assertEquals(GeoCoordinate(37.0, -122.0), fix)
        assertEquals(false, producing.permissionRequested)
    }

    @Test
    fun `requestCurrentLocation prompts for permission when not yet determined, then proceeds once granted`() = runTest {
        val producing = FakeLocationProducing(LocationAuthorizationStatus.NOT_DETERMINED)
        val service = LocationService(producing)

        producing.onRequestLocation = {
            // The permission decision must already have landed before the location request.
            GeoCoordinate(1.0, 2.0)
        }

        // Flip authorization to granted shortly after the permission prompt is requested,
        // simulating the real OS callback landing asynchronously.
        val fixDeferred = async { service.requestCurrentLocation() }
        while (!producing.permissionRequested) delay(1)
        producing.setStatus(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE)

        assertEquals(GeoCoordinate(1.0, 2.0), fixDeferred.await())
        assertTrue(producing.permissionRequested)
    }

    @Test
    fun `requestCurrentLocation throws NotAuthorized when permission is denied`() = runTest {
        val producing = FakeLocationProducing(LocationAuthorizationStatus.NOT_DETERMINED)
        val service = LocationService(producing)

        // This child is EXPECTED to fail (DENIED permission), and the test inspects its failure
        // explicitly via `fixDeferred.await()` below. A plain `async` under this test's own
        // scope would propagate that failure to the parent Job the instant it's thrown -
        // cancelling the whole test scope before `await()` ever runs - because child-failure
        // propagation to a non-supervisor parent isn't deferred until the caller awaits.
        // `supervisorScope` isolates that expected failure (its own Job doesn't propagate a
        // child's failure to its parent) and, unlike a manually constructed `SupervisorJob()`,
        // completes automatically once this block and its children finish - so it leaves no
        // dangling active job for `runTest`'s own cleanup to flag as leaked.
        val error = supervisorScope {
            val fixDeferred = async { service.requestCurrentLocation() }
            while (!producing.permissionRequested) delay(1)
            producing.setStatus(LocationAuthorizationStatus.DENIED)
            assertFailsWith<LocationServiceError.NotAuthorized> { fixDeferred.await() }
        }
        assertEquals(LocationAuthorizationStatus.DENIED, error.status)
        assertEquals(0, producing.locationCallCount)
    }

    @Test
    fun `requestCurrentLocation throws PermissionTimeout when authorization never decides`() = runTest {
        val producing = FakeLocationProducing(LocationAuthorizationStatus.NOT_DETERMINED)
        val service = LocationService(producing, permissionTimeoutMs = 10)

        assertFailsWith<LocationServiceError.PermissionTimeout> {
            service.requestCurrentLocation()
        }
        assertEquals(0, producing.locationCallCount)
    }

    @Test
    fun `requestCurrentLocation throws LocationTimeout when the fix never arrives in time`() = runTest {
        val producing = FakeLocationProducing(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE)
        producing.onRequestLocation = {
            delay(1_000)
            GeoCoordinate(0.0, 0.0)
        }
        val service = LocationService(producing)

        assertFailsWith<LocationServiceError.LocationTimeout> {
            service.requestCurrentLocation(timeoutMs = 10)
        }
    }

    @Test
    fun `requestCurrentLocation wraps an unexpected producer failure as RequestFailed`() = runTest {
        val producing = FakeLocationProducing(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE)
        producing.onRequestLocation = { throw IllegalStateException("GPS hardware unavailable") }
        val service = LocationService(producing)

        val error = assertFailsWith<LocationServiceError.RequestFailed> {
            service.requestCurrentLocation()
        }
        assertEquals("GPS hardware unavailable", error.message)
    }

    @Test
    fun `A second concurrent requestCurrentLocation is rejected with RequestInProgress, not coalesced`() = runTest {
        val producing = FakeLocationProducing(LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE)
        producing.onRequestLocation = {
            delay(1_000)
            GeoCoordinate(0.0, 0.0)
        }
        val service = LocationService(producing)

        val first = async { service.requestCurrentLocation(timeoutMs = 5_000) }
        while (producing.locationCallCount == 0) delay(1)

        assertFailsWith<LocationServiceError.RequestInProgress> {
            service.requestCurrentLocation()
        }

        first.cancel()
    }

    @Test
    fun `isAuthorized, hasRequestedPermission and isLocationDenied reflect the current status`() = runTest {
        val producing = FakeLocationProducing(LocationAuthorizationStatus.NOT_DETERMINED)
        val service = LocationService(producing)

        assertEquals(false, service.isAuthorized)
        assertEquals(false, service.hasRequestedPermission)
        assertEquals(false, service.isLocationDenied)

        producing.setStatus(LocationAuthorizationStatus.AUTHORIZED_ALWAYS)
        assertTrue(service.isAuthorized)
        assertTrue(service.hasRequestedPermission)
        assertEquals(false, service.isLocationDenied)

        producing.setStatus(LocationAuthorizationStatus.RESTRICTED)
        assertEquals(false, service.isAuthorized)
        assertTrue(service.hasRequestedPermission)
        assertTrue(service.isLocationDenied)
    }
}
