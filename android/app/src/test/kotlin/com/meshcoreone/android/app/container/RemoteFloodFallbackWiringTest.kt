// AndroidOnly: WP-210 Verify the Advanced Settings preference drives the live radio service.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.services.remote.BinaryProtocolError
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class RemoteFloodFallbackWiringTest : RoomProcessTest() {
    @Test fun changingAdvancedSettingsControlsTheSamePublishedRadioService() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        val fallbackKey = AppStorageKey.remoteFloodFallback
        storage.preferences.remove(fallbackKey)
        val h = ContainerHarness(
            this, store, isRemoteFloodFallbackEnabled = storage::isRemoteFloodFallbackEnabled,
            sessionConfiguration = SessionConfiguration(binaryRequestOverallTimeout = 0.1, binaryRequestRetransmitInterval = null),
        )
        try {
            val settings = AppSettingsFeature.create(h.container, storage).remotePathFallbackSettings
            assertEquals(true, settings.enabled.first())
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            val radio = h.links.radios.single()
            var tag = 0L
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x32 -> listOf(Bytes.of(0x06, 0) + le32(++tag) + le32(100))
                    0x0d -> listOf(Bytes.of(0))
                    else -> null
                }
            }

            suspend fun verifyFallback(enabled: Boolean, expected: List<Int>) {
                settings.setEnabled(enabled)
                // Observe the committed value, not an optimistic UI state.
                settings.enabled.first { it == enabled }
                assertEquals(enabled, storage.preferences.get(fallbackKey))
                val offset = radio.sent.size
                val request = async { runCatching { graph.binaryProtocolService.requestACL(key(42)) } }
                h.eventually("binary request finishes") { request.isCompleted }
                assertIs<MeshCoreException.Timeout>(assertIs<BinaryProtocolError.SessionError>(request.await().exceptionOrNull()).error)
                assertEquals(expected, radio.sent.drop(offset).map { it[0].toInt() }.filter { it in setOf(0x32, 0x0d) })
                assertSame(graph, h.sessions.current)
            }

            verifyFallback(false, listOf(0x32))
            verifyFallback(true, listOf(0x32, 0x0d, 0x32))
        } finally {
            try { h.container.close() } finally { storage.close() }
        }
    }
}
