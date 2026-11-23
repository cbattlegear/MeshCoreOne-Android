// AndroidOnly: WP-210 Verify the Advanced Settings preference drives the live radio service.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.services.remote.BinaryProtocolError
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RemoteFloodFallbackWiringTest : RoomProcessTest() {
    @Test fun replacementRetiresRecoveryBeforeAnAdmittedWriterLetsTheGraphClose() = runTest {
        val firstDeviceId = UUID(0, 100)
        val secondDeviceId = UUID(0, 101)
        val readingPolicy = CompletableDeferred<Unit>()
        val resumePolicy = CompletableDeferred<Unit>()
        val writeAdmitted = CompletableDeferred<Unit>()
        val finishWrite = CompletableDeferred<Unit>()
        val h = ContainerHarness(
            this, store,
            identity = { target -> key(if ((target as ConnectionTarget.Bluetooth).deviceId == firstDeviceId) 7 else 8) },
            isRemoteFloodFallbackEnabled = {
                readingPolicy.complete(Unit)
                resumePolicy.await()
                true
            },
            sessionConfiguration = SessionConfiguration(binaryRequestOverallTimeout = 0.1, binaryRequestRetransmitInterval = null),
        )
        try {
            h.manager.connect(h.target(firstDeviceId))
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            val device = assertNotNull(h.manager.connectedDevice)
            val radio = h.links.radios.single()
            var tag = 0L
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x32 -> listOf(Bytes.of(0x06, 0) + le32(++tag) + le32(100))
                    0x0d -> listOf(Bytes.of(0))
                    else -> null
                }
            }
            val request = async { runCatching { graph.binaryProtocolService.requestACL(key(42)) } }
            h.eventually("binary request reached its recovery decision") { readingPolicy.isCompleted }
            val writer = async {
                h.manager.withDeviceWrite(graph.token, device.id) {
                    writeAdmitted.complete(Unit)
                    finishWrite.await()
                }
            }
            writeAdmitted.await()
            val replacement = async { h.manager.switchDevice(h.target(secondDeviceId)) }
            h.eventually("replacement logically retires the old generation") { h.manager.snapshot.value.token != graph.token }
            assertFalse(replacement.isCompleted)
            assertFalse(graph.publicationFence.isClosed)
            assertTrue(radio.connected)

            resumePolicy.complete(Unit)
            h.eventually("timeout recovery is denied during the write drain") { request.isCompleted }
            assertIs<CancellationException>(request.await().exceptionOrNull())
            assertEquals(listOf(0x32), radio.sent.map { it[0].toInt() }.filter { it in setOf(0x32, 0x0d) })
            assertEquals(1, h.links.radios.size)

            finishWrite.complete(Unit)
            assertNotNull(writer.await())
            replacement.await()
            h.settle()
            h.assertReady()
            assertEquals(key(8), h.manager.connectedDevice?.publicKey)
            assertTrue(assertNotNull(h.sessions.current).token != graph.token)
        } finally {
            resumePolicy.complete(Unit)
            finishWrite.complete(Unit)
            h.container.close()
        }
    }

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
