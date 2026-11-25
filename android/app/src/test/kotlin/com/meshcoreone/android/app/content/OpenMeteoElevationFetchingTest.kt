// PortedFrom: MC1Tests/Services/ElevationServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import android.net.Uri
import com.meshcoreone.android.core.services.content.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class OpenMeteoElevationFetchingTest {
    private class Fetcher(
        private val body: String,
        private val status: Int = 200,
    ) : BoundedHttpFetching {
        var url: String? = null
        var closed = 0
        override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
            this.url = url
            val bytes = body.toByteArray()
            return HttpFetchAttempt.Started(status, "application/json", bytes.size.toLong(),
                closeResponse = { closed++ }, chunks = { receive -> receive(bytes) })
        }
    }

    @Test
    fun `actual native URL and JSON consumer preserve coordinate order and real Haversine distances`() = runTest {
        val fetcher = Fetcher("""{"elevation":[10.5,20.25]}""")
        val start = GeoCoordinate(37.7749, -122.4194)
        val end = GeoCoordinate(37.8049, -122.3894)
        val service = ElevationService(OpenMeteoElevationFetching(fetcher))

        val samples = service.fetchElevations(listOf(start, end))

        assertEquals(listOf(10.5, 20.25), samples.map { it.elevation })
        assertEquals(0.0, samples.first().distanceFromAMeters)
        assertTrue(samples.last().distanceFromAMeters > 0)
        val url = Uri.parse(fetcher.url)
        assertEquals("https", url.scheme)
        assertEquals("api.open-meteo.com", url.host)
        assertEquals("37.774900,37.804900", url.getQueryParameter("latitude"))
        assertEquals("-122.419400,-122.389400", url.getQueryParameter("longitude"))
        assertEquals(1, fetcher.closed)
    }

    @Test
    fun `malformed and wrong-type elevation values never become a successful numeric default`() = runTest {
        for (body in listOf("not-json", """{}""", """{"elevation":["1"]}""", """{"elevation":[null]}""")) {
            val fetcher = Fetcher(body)
            val result = OpenMeteoElevationFetching(fetcher).fetchElevations("1.000000", "2.000000")
            assertEquals(ElevationFetchAttempt.InvalidResponse, result)
            assertEquals(1, fetcher.closed)
        }
    }

    @Test
    fun `real HTTP statuses preserve rate-limit and non-200 errors and close the rejected response`() = runTest {
        val rate = Fetcher("""{"elevation":[1]}""", 429)
        assertEquals(ElevationFetchAttempt.RateLimited, OpenMeteoElevationFetching(rate).fetchElevations("1", "2"))
        assertEquals(1, rate.closed)
        val error = Fetcher("""{"elevation":[1]}""", 503)
        val failure = OpenMeteoElevationFetching(error).fetchElevations("1", "2")
        assertIs<ElevationFetchAttempt.HttpError>(failure)
        assertEquals(503, failure.statusCode)
        assertEquals(1, error.closed)
    }
}
