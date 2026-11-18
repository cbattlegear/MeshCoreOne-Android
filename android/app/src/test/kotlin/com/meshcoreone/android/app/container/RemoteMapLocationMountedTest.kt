// PortedFrom: MC1/Services/LocationService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Repeaters/NeighborSNRMapView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.content.LocationManagerLocationProducing
import com.meshcoreone.android.app.content.LocationPermissionRequesting
import com.meshcoreone.android.app.remotenodes.RemoteNodesFixture
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.maps.GeoPoint
import com.meshcoreone.android.core.maps.MapCamera
import com.meshcoreone.android.core.maps.MapCameraPose
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.RemoteNodesEntry
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesUiDependencies
import com.meshcoreone.android.feature.remotenodes.ui.RemoteNodesMapSurface
import java.time.Duration
import java.util.UUID
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteMapLocationMountedTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun coldPhoneFixUpdatesTheMountedMapWithoutCatalogPublicationAndRevocationUsesRadio() =
        verifyLocationDelivery(granted = true)

    @Test fun deniedMountedMapUsesRadioWithoutPromptAndObservesGrantOnResume() =
        verifyLocationDelivery(granted = false)

    private fun verifyLocationDelivery(granted: Boolean) = runTest {
        val application = RuntimeEnvironment.getApplication()
        val manager = application.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        shadowOf(application).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (granted) shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
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
            h.container.deviceEditor.updateDevice(device.copy(latitude = 10.0, longitude = 10.0))
            h.settle()
            val fixture = RemoteNodesFixture()
            val prefix = byteArrayOf(1, 2, 3, 4, 5, 6)
            fixture.contacts += listOf(
                fixture.contact.copy(
                    id = UUID(0, 1), publicKey = Bytes(prefix + ByteArray(26)),
                    name = "Alpha radio neighbor", latitude = 10.0, longitude = 10.0, lastAdvertTimestamp = 200u,
                ),
                fixture.contact.copy(
                    id = UUID(0, 2), publicKey = Bytes(prefix + ByteArray(25) + byteArrayOf(1)),
                    name = "Zulu phone neighbor", latitude = 20.0, longitude = 20.0, lastAdvertTimestamp = 100u,
                ),
            )
            val storage = MeshCoreStorage.get(host.get())
            storage.preferences.resetAppPreferences()
            host.get().getSharedPreferences("shared-map", 0).edit().clear().commit()
            fixture.mapPreferences = AppMapPreferences(storage.preferences, host.get().getSharedPreferences("shared-map", 0))
            val real = h.container.createRemoteNodesFeatureDependencies()
            val observationStarted = CompletableDeferred<Unit>()
            val releaseObservation = CompletableDeferred<Unit>()
            val firstLocationDelivered = CompletableDeferred<Unit>()
            val dependencies = object : RemoteNodesUiDependencies by fixture {
                override val locations = real.locations.onStart {
                    observationStarted.complete(Unit)
                    releaseObservation.await()
                }.onEach { firstLocationDelivered.complete(Unit) }
                override val bestAvailableLocation get() = real.bestAvailableLocation
            }
            val pan = MapCamera(GeoPoint(40.0, -120.0), .3, .4, MapCameraPose(11.0, 25.0, 35.0))
            var presentation: MapPresentationState? = null
            val frames = mutableListOf<MapPresentationState>()
            var disposals = 0
            val surface: RemoteNodesMapSurface = { state, _, _, _, camera, _, modifier ->
                SideEffect { presentation = state; frames += state }
                DisposableEffect(Unit) { onDispose { disposals++ } }
                Column(modifier.testTag("location-map")) {
                    Button({ camera(pan) }) { Text("Pan location map") }
                }
            }
            host.get().setContent {
                MaterialTheme { RemoteNodesEntry(FeatureRoute(FeatureId.REMOTE_NODES), {}, dependencies, mapSurface = surface) }
            }
            fun await(condition: () -> Boolean) = compose.waitUntil(5_000) {
                h.settle(1)
                compose.onAllNodesWithTag("location-map").fetchSemanticsNodes()
                condition()
            }
            fun click(matcher: SemanticsMatcher, scroll: Boolean = false) {
                val node = compose.onNode(matcher)
                if (scroll) node.performScrollTo()
                node.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
            }
            await { compose.onAllNodesWithTag("open-node:${fixture.contact.id}").fetchSemanticsNodes().isNotEmpty() }
            click(hasTestTag("open-node:${fixture.contact.id}"))
            click(hasTestTag("remote-tab:TELEMETRY"))
            click(hasText(host.get().getString(L.remoteNodesStatusNeighbors)), scroll = true)
            await { compose.onAllNodesWithText(host.get().getString(L.remoteNodesStatusViewOnMap)).fetchSemanticsNodes().isNotEmpty() }
            click(hasText(host.get().getString(L.remoteNodesStatusViewOnMap)), scroll = true)
            await { presentation?.points?.any { it.label == "Alpha radio neighbor" } == true }
            click(hasText("Pan location map"), scroll = true)
            await { presentation?.camera == pan }
            val mountedDisposals = disposals
            frames.clear()
            assertTrue(observationStarted.isCompleted)
            assertTrue("The map stays usable while first location observation is pending",
                shadowOf(manager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).isEmpty())
            releaseObservation.complete(Unit)
            await { firstLocationDelivered.isCompleted }
            if (!granted) {
                assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).isEmpty())
                host.pause().stop()
                shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
                host.start().resume().visible()
                host.windowFocusChanged(true)
            }
            await { shadowOf(manager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).size == 1 }
            assertNull(producer.currentAuthorizedLocation)
            shadowOf(manager).simulateLocation(Location(LocationManager.FUSED_PROVIDER).apply {
                latitude = 20.0
                longitude = 20.0
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            })
            shadowOf(Looper.getMainLooper()).idle()
            await { presentation?.points?.any { it.label == "Zulu phone neighbor" } == true }
            assertEquals(Coordinate(20.0, 20.0), real.bestAvailableLocation)
            assertTrue("A mounted map observes a bounded one-shot producer, not continuous GPS tracking",
                shadowOf(manager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).isEmpty())
            host.pause().stop()
            shadowOf(application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
            assertNull(producer.currentAuthorizedLocation)
            assertNotNull(producer.currentLocation)
            assertEquals(Coordinate(10.0, 10.0), real.bestAvailableLocation)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
            host.start().resume().visible()
            host.windowFocusChanged(true)
            await { presentation?.points?.any { it.label == "Alpha radio neighbor" } == true }
            assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).isEmpty())
            h.container.deviceEditor.updateDevice(device.copy(latitude = 20.0, longitude = 20.0))
            await { presentation?.points?.any { it.label == "Zulu phone neighbor" } == true }
            assertEquals(Coordinate(20.0, 20.0), real.bestAvailableLocation)
            h.manager.disconnect()
            await { real.bestAvailableLocation == null && presentation?.points?.any { it.label == "Alpha radio neighbor" } == true }
            assertEquals(mountedDisposals, disposals)
            assertTrue(frames.isNotEmpty())
            assertTrue("Location readiness and permission changes must not refit the map", frames.all { it.camera == pan })
            assertEquals(0, prompts)
        } finally {
            host.pause().stop().destroy()
            producer.close()
            h.container.close()
        }
    }
}
