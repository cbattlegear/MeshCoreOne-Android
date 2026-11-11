// AndroidOnly: WP-312 Offline request admission and cancellation firewall regressions.
package com.meshcoreone.android.core.maps

import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test
import org.maplibre.android.MapLibre
import org.maplibre.android.ModuleProvider
import org.maplibre.android.ModuleProviderImpl
import org.maplibre.android.http.HttpRequest
import org.maplibre.android.http.HttpResponder

class OpenFreeMapOfflineSuspensionTest {
    @Test fun installationIsIdempotentAndWrapsTheActualModuleFactory() {
        val original = MapLibre.getModuleProvider()
        var executions = 0
        val delegate = object : ModuleProvider by ModuleProviderImpl() {
            override fun createHttpRequest() = object : HttpRequest {
                override fun executeRequest(
                    responder: HttpResponder, nativePtr: Long, resourceUrl: String,
                    dataRange: String, etag: String, modified: String, offlineUsage: Boolean,
                ) { executions++ }
                override fun cancelRequest() = Unit
            }
        }
        try {
            MapLibre.setModuleProvider(delegate)
            OpenFreeMapOfflineSuspension.install()
            val installed = MapLibre.getModuleProvider()
            assertTrue(installed is SuspendedOfflineModuleProvider)
            OpenFreeMapOfflineSuspension.install()
            assertSame(installed, MapLibre.getModuleProvider())
            var failures = 0
            installed.createHttpRequest().executeRequest(
                responder { _, _ -> failures++ }, 1,
                MapLibreOpenFreeMap.STYLE_URI, "", "", "", true,
            )
            assertEquals(1, failures)
            assertEquals(0, executions)
        } finally {
            MapLibre.setModuleProvider(original)
        }
    }

    @Test fun recognizesProviderAuthorityWithoutBlockingLookalikesOrOtherProviders() {
        listOf(
            "https://tiles.openfreemap.org/styles/liberty",
            "http://OPENFREEMAP.ORG/planet",
            "https://fonts.openfreemap.org./glyphs",
        ).forEach { assertTrue(it, OpenFreeMapOfflineSuspension.appliesTo(it)) }
        listOf(
            "https://openfreemap.org.example.invalid/tiles",
            "https://example.invalid/openfreemap.org/tiles",
            "https://openfreemap.org@example.invalid/tiles",
            "file:///openfreemap.org/style.json",
            "not a url",
        ).forEach { assertFalse(it, OpenFreeMapOfflineSuspension.appliesTo(it)) }
        assertFalse(OpenFreeMapOfflineSuspension.appliesTo(null))
    }

    @Test fun blocksEveryConcurrentOfflineAttemptBeforeTheSdkAndPreservesCancellation() {
        val delegated = AtomicInteger()
        val cancelled = AtomicInteger()
        val failed = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            val futures = executor.invokeAll((0 until 80).map { index ->
                Callable {
                    val request = SuspendedOfflineHttpRequest(object : HttpRequest {
                        override fun executeRequest(
                            responder: HttpResponder, nativePtr: Long, resourceUrl: String,
                            dataRange: String, etag: String, modified: String, offlineUsage: Boolean,
                        ) { delegated.incrementAndGet() }
                        override fun cancelRequest() { cancelled.incrementAndGet() }
                    })
                    request.executeRequest(responder { type, message ->
                        assertEquals(HttpRequest.PERMANENT_ERROR, type)
                        assertEquals(OfflineMapError.OpenFreeMapDownloadsSuspended.message, message)
                        failed.incrementAndGet()
                    }, index.toLong(), "https://tiles.openfreemap.org/$index.pbf", "", "", "", true)
                    request.cancelRequest()
                }
            })
            futures.forEach { it.get() }
        } finally {
            executor.shutdownNow()
        }
        assertEquals(0, delegated.get())
        assertEquals(80, failed.get())
        assertEquals(80, cancelled.get())
    }

    @Test fun forwardsOnlineAndOtherProviderArgumentsAndResponseUnchanged() {
        for ((url, offline) in listOf(
            "https://tiles.openfreemap.org/planet/1.pbf" to false,
            "https://example.invalid/tiles/1.pbf" to true,
        )) {
            val body = byteArrayOf(1, 2, 3)
            var received: ByteArray? = null
            val callback = object : HttpResponder {
                override fun handleFailure(type: Int, message: String?) = error("Unexpected failure $message")
                override fun onResponse(
                    code: Int, etag: String?, modified: String?, cacheControl: String?,
                    expires: String?, retryAfter: String?, rateLimit: String?, data: ByteArray?,
                ) { received = data }
            }
            var calls = 0
            val request = SuspendedOfflineHttpRequest(object : HttpRequest {
                override fun executeRequest(
                    responder: HttpResponder, nativePtr: Long, resourceUrl: String,
                    dataRange: String, etag: String, modified: String, offlineUsage: Boolean,
                ) {
                    calls++
                    assertSame(callback, responder)
                    assertEquals(123L, nativePtr)
                    assertEquals(url, resourceUrl)
                    assertEquals("bytes=1-2", dataRange)
                    assertEquals("etag", etag)
                    assertEquals("date", modified)
                    assertEquals(offline, offlineUsage)
                    responder.onResponse(200, null, null, null, null, null, null, body)
                }
                override fun cancelRequest() = Unit
            })
            request.executeRequest(callback, 123, url, "bytes=1-2", "etag", "date", offline)
            assertEquals(1, calls)
            assertSame(body, received)
        }
    }

    private fun responder(failure: (Int, String?) -> Unit) = object : HttpResponder {
        override fun handleFailure(type: Int, message: String?) = failure(type, message)
        override fun onResponse(
            code: Int, etag: String?, modified: String?, cacheControl: String?,
            expires: String?, retryAfter: String?, rateLimit: String?, data: ByteArray?,
        ) = error("Blocked request returned success")
    }
}
