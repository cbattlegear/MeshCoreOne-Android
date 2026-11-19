// AndroidOnly: WP-314 Verify the mounted trace consumer of the real foreground location adapter.
package com.meshcoreone.android.app.container

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.app.content.LocationManagerLocationProducing
import com.meshcoreone.android.app.content.LocationPermissionRequesting
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.TraceInfo
import com.meshcoreone.android.core.protocol.event.TraceNode
import com.meshcoreone.android.feature.tools.trace.RecentHopsStorage
import com.meshcoreone.android.feature.tools.trace.TraceDiagnostics
import com.meshcoreone.android.feature.tools.trace.TracePathFeatureDependencies
import com.meshcoreone.android.feature.tools.trace.TracePathScreen
import com.meshcoreone.android.feature.tools.trace.TraceResponse
import com.meshcoreone.android.feature.tools.trace.TraceSender
import java.util.UUID
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TraceLocationMountedTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mountedTraceUsesGrantedFixAndFallsBackWithoutLosingItsPathOrResult() = runTest {
        val application = RuntimeEnvironment.getApplication()
        val provider = LocationManager.FUSED_PROVIDER
        val location = shadowOf(application.getSystemService(LocationManager::class.java))
        location.setProviderEnabled(provider, true)
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        var prompts = 0
        val producer = LocationManagerLocationProducing(application, LocationPermissionRequesting { prompts++ })
        val h = ContainerHarness(
            this, store,
            nodesLocations = producer.foregroundLocations().map { it?.let { fix -> Coordinate(fix.latitude, fix.longitude) } },
            phoneLocation = { producer.currentAuthorizedLocation?.let { Coordinate(it.latitude, it.longitude) } },
        )
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val device = requireNotNull(h.appState.connectedDevice)
            h.container.deviceEditor.updateDevice(device.copy(latitude = 0.0, longitude = 10.0))
            val repeater = ContactDTO(
                id = UUID(0, 1), radioId = device.radioId, publicKey = Bytes(ByteArray(32) { 0x3f }),
                name = "Trace tower", typeRawValue = 2u, latitude = 0.0, longitude = 10.002,
                lastHeardTimestamp = null,
            )
            store.saveContact(repeater)
            h.settle()
            val recent = object : RecentHopsStorage {
                override fun stringList(key: String): List<String>? = null
                override fun setStringList(key: String, value: List<String>) = Unit
            }
            val actual = h.container.createToolsFeatureDependencies(
                recent, h.container.createToolsDiagnosticsDependencies(),
            ).trace
            val responses = MutableSharedFlow<TraceResponse>(extraBufferCapacity = 1)
            var request: TraceInfo? = null
            var sends = 0
            val feature = object : TracePathFeatureDependencies by actual {
                override fun traceResponses() = responses
                override fun traceSender() = TraceSender { tag, auth, flags, path ->
                    sends++
                    assertEquals(1, path.size)
                    request = TraceInfo(tag, auth, flags, 1u, listOf(TraceNode(path, 8.0), TraceNode(null, 7.0)))
                    MessageSentInfo(0u, Bytes.EMPTY, 0u)
                }
            }
            val storage = MeshCoreStorage.get(host.get())
            storage.preferences.resetAppPreferences()
            val shared = host.get().getSharedPreferences("trace-location-map", 0)
            val deps = TracePathScreen.Dependencies(
                feature, recent, TraceDiagnostics { _, error -> throw AssertionError(error) },
                device.radioId, h.appState.servicesVersion.value, AppMapPreferences(storage.preferences, shared),
            )
            var frame: MapPresentationState? = null
            host.get().setContent {
                MaterialTheme {
                    TracePathScreen(deps, mapSurface = { state, _, _, _, pick, overlay ->
                        SideEffect { frame = state }
                        Box(Modifier.fillMaxSize()) {
                            state.points.firstOrNull { it.id == repeater.id }?.let { marker ->
                                TextButton(onClick = { pick(marker) }) { Text(repeater.name) }
                            }
                            overlay()
                        }
                    })
                }
            }
            fun await(condition: () -> Boolean) = compose.waitUntil(5_000) {
                h.settle(1)
                shadowOf(Looper.getMainLooper()).idle()
                condition()
            }
            fun visible(text: String) = compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
            fun click(resource: Int) = compose.onNodeWithText(host.get().getString(resource)).performClick()
            fun pathStart() = frame?.lines?.singleOrNull()?.points?.firstOrNull()
            fun resumeHost() {
                host.start().resume().visible()
                host.windowFocusChanged(true)
            }

            click(C.contactsTraceModeMap)
            await {
                visible(repeater.name) && responses.subscriptionCount.value == 1 &&
                    compose.onAllNodesWithTag("trace-map:preferences-loading").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithText(repeater.name).performClick()
            await { pathStart() == GeoPoint(0.0, 10.0) }
            assertTrue(location.getLocationUpdateListeners(provider).isEmpty())
            click(C.contactsTraceListRunTrace)
            await { request != null }
            assertTrue(responses.tryEmit(TraceResponse(checkNotNull(request), device.radioId)))
            await { visible(host.get().getString(C.contactsTraceMapViewResults)) }
            click(C.contactsTraceMapViewResults)
            // At the equator, 0.002 degrees of longitude is about 222.64 m each way.
            await { visible("445 m") }

            host.pause().stop()
            shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            resumeHost()
            await { location.getLocationUpdateListeners(provider).size == 1 }
            host.pause().stop()
            await { location.getLocationUpdateListeners(provider).isEmpty() }
            resumeHost()
            await { location.getLocationUpdateListeners(provider).size == 1 }
            location.simulateLocation(Location(provider).apply {
                latitude = 0.0
                longitude = 10.001
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            shadowOf(Looper.getMainLooper()).idle()
            await { pathStart() == GeoPoint(0.0, 10.001) && visible("223 m") }
            assertTrue(location.getLocationUpdateListeners(provider).isEmpty())

            host.pause().stop()
            shadowOf(application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            resumeHost()
            await { pathStart() == GeoPoint(0.0, 10.0) && visible("445 m") }
            assertFalse(visible("223 m"))
            h.container.deviceEditor.updateDevice(device.copy(latitude = 0.0, longitude = 0.0))
            await { frame?.lines?.isEmpty() == true && visible("2. ${repeater.name}") && !visible("445 m") }
            assertEquals(1, sends)
            assertEquals(0, prompts)
            assertTrue(location.getLocationUpdateListeners(provider).isEmpty())
        } finally {
            host.pause().stop().destroy()
            producer.close()
            h.container.close()
        }
    }
}
