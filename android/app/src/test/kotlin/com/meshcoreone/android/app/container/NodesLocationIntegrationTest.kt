// AndroidOnly: WP-311 Actual process composition of authorized phone fixes and connected-radio fallback.
package com.meshcoreone.android.app.container

import android.Manifest
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.app.content.LocationManagerLocationProducing
import com.meshcoreone.android.app.content.LocationPermissionRequesting
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.feature.nodes.NodesEntry
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Rule
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodesLocationIntegrationTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun coldAuthorizedRealAdapterFixReordersMountedContactsWithoutAnotherFeatureRequest() =
        verifyMountedColdCache(granted = true)

    @Test
    fun deniedRealAdapterKeepsMountedLastHeardFallbackWithoutPromptOrFixRequest() =
        verifyMountedColdCache(granted = false)

    private fun verifyMountedColdCache(granted: Boolean) = runTest {
        val application = RuntimeEnvironment.getApplication()
        val manager = application.getSystemService(LocationManager::class.java)
        shadowOf(manager).setProviderEnabled(LocationManager.FUSED_PROVIDER, true)
        if (granted) shadowOf(application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        else shadowOf(application).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        var prompts = 0
        val adapter = LocationManagerLocationProducing(application, LocationPermissionRequesting { prompts++ })
        val h = ContainerHarness(this, store, nodesLocations = adapter.foregroundLocations().map {
            it?.let { coordinate -> Coordinate(coordinate.latitude, coordinate.longitude) }
        })
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val device = assertNotNull(h.appState.connectedDevice)
            h.container.deviceEditor.updateDevice(device.copy(latitude = 0.0, longitude = 0.0))
            val radio = device.radioId
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = key(41), name = "Alpha",
                latitude = 10.0, longitude = 10.0, lastModified = 1u, lastHeardTimestamp = null,
            ))
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = key(42), name = "Zulu",
                latitude = 20.0, longitude = 20.0, lastModified = 2u, lastHeardTimestamp = null,
            ))
            assertNull(adapter.currentLocation)
            host.get().setContent {
                MaterialTheme {
                    NodesEntry(FeatureRoute(FeatureId.NODES), {}, h.container.nodesFeature)
                }
            }
            compose.waitUntil(5_000) {
                h.settle(1)
                compose.onAllNodes(androidx.compose.ui.test.hasText("Alpha"), useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(host.get().getString(C.contactsListSort)).performClick()
            compose.onNodeWithText(host.get().getString(NodeSortOrder.DISTANCE.titleRes)).performClick()
            fun assertOrder(first: String, second: String) {
                compose.waitUntil(5_000) {
                    h.settle(1)
                    val a = compose.onNodeWithText(first, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    val b = compose.onNodeWithText(second, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
                    a.top < b.top
                }
            }
            assertOrder("Zulu", "Alpha")
            if (granted) {
                assertEquals(1, shadowOf(manager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).size,
                    "Mounted cold-cache collection must initiate exactly one authorized platform fix")
                shadowOf(manager).simulateLocation(Location(LocationManager.FUSED_PROVIDER).apply {
                    latitude = 10.0
                    longitude = 10.0
                    elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                })
                shadowOf(Looper.getMainLooper()).idle()
                assertOrder("Alpha", "Zulu")
                assertEquals(Coordinate(10.0, 10.0), adapter.currentLocation?.let { Coordinate(it.latitude, it.longitude) })
            } else {
                assertTrue(shadowOf(manager).getLocationUpdateListeners(LocationManager.FUSED_PROVIDER).isEmpty())
                assertOrder("Zulu", "Alpha")
            }
            assertEquals(0, prompts, "Neither path may invoke the permission requester")
        } finally {
            host.pause().stop().destroy()
            adapter.close()
            h.container.close()
        }
    }

    @Test
    fun actualNodesAdapterPrefersAuthorizedPhoneThenLiveRadioAndWithdrawsDisconnectedFallback() = runTest {
        val phone = MutableStateFlow<Coordinate?>(null)
        val h = ContainerHarness(this, store, nodesLocations = phone)
        val observed = mutableListOf<Coordinate?>()
        val collecting = backgroundScope.launch { h.container.nodesFeature.locations.collect { observed += it } }
        try {
            h.settle()
            assertNull(observed.last())
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val device = assertNotNull(h.appState.connectedDevice)
            h.container.deviceEditor.updateDevice(device.copy(latitude = 10.0, longitude = 10.0))
            h.settle()
            assertEquals(Coordinate(10.0, 10.0), observed.last())
            phone.value = Coordinate(20.0, 20.0)
            h.settle()
            assertEquals(Coordinate(20.0, 20.0), observed.last())
            h.container.deviceEditor.updateDevice(device.copy(latitude = 30.0, longitude = 30.0))
            h.settle()
            assertEquals(Coordinate(20.0, 20.0), observed.last(), "Phone fix still takes precedence")
            phone.value = null
            h.settle()
            assertEquals(Coordinate(30.0, 30.0), observed.last(), "Unavailable phone uses current radio, not an old sample")
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            assertNull(observed.last(), "Cached offline device coordinates are not a connected-radio fix")
        } finally {
            collecting.cancel()
            h.container.close()
        }
    }
}
