// AndroidOnly: WP-312 Real trace-screen candidate/tap/filter/camera journey with process preferences.
// Only the native rendering seam is replaced; this is not installed MapLibre or device evidence.
package com.meshcoreone.android.app.container

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.maps.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.tools.trace.*
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
class TraceMapMountedConsumerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val radio = RadioId(UUID(0, 1))
    private val north = ContactDTO(
        id = UUID(0, 2), radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }),
        name = "North", typeRawValue = 2u, latitude = 37.0, longitude = -122.0,
        lastHeardTimestamp = null,
    )
    private val south = north.copy(
        id = UUID(0, 3), publicKey = Bytes(ByteArray(32) { 2 }),
        name = "South", latitude = 38.0, isFavorite = true,
    )
    private val hill = DiscoveredNodeDTO(
        UUID(0, 4), radio, Bytes(ByteArray(32) { 3 }), "Hill", 2u,
        Instant.EPOCH, 0u, 39.0, -121.0, 255u, Bytes.EMPTY, null, null,
    )
    private val panned = MapCamera(GeoPoint(10.0, 20.0), 0.3, 0.4)
    private var latest: MapPresentationState? = null
    private var cameraChange: ((MapCamera) -> Unit)? = null
    private var tableContacts = listOf(north, south)
    private val tableVersion = mutableIntStateOf(0)
    private val failures = mutableListOf<Exception>()
    private var frameClock: BroadcastFrameClock? = null
    private var frameNanos = 0L

    @Before fun createHost() {
        frameClock = null
        frameNanos = 0L
        cameraChange = null
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        host.get().getSharedPreferences("shared-map", 0).edit().clear().commit()
        runBlocking { MeshCoreStorage.get(host.get()).preferences.resetAppPreferences() }
    }

    @After fun destroyHost() {
        host.pause().stop().destroy()
        frameClock?.cancel()
    }

    @Test fun pretraceCandidatesEditLastHopAndRetainPanAcrossLivePreferences() {
        mount()
        compose.onNodeWithTag("pin:${north.id}").performClick()
        compose.waitUntil(5_000) { latest?.points?.singleOrNull { it.id == north.id }?.hopIndex == 1 }
        compose.onNodeWithTag("pin:${hill.id}").performClick()
        compose.waitUntil(5_000) { latest?.points?.singleOrNull { it.id == hill.id }?.hopIndex == 2 }
        assertEquals(listOf("trace-1"), latest!!.lines.map { it.id })
        assertEquals(
            listOf(GeoPoint(37.0, -122.0), GeoPoint(39.0, -121.0)), latest!!.lines.single().points,
        )
        assertEquals(MapLineStyle.TRACE_UNTRACED, latest!!.lines.single().style)
        compose.onNodeWithTag("pin:${north.id}").performClick()
        compose.waitForIdle()
        assertEquals(listOf(1, 2), latest!!.points.filter { it.hopIndex != null }.map { it.hopIndex })
        compose.onNodeWithTag("pan").performClick()
        compose.runOnIdle {
            tableContacts = listOf(north.copy(latitude = 36.0), south)
            tableVersion.intValue++
        }
        compose.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
        compose.waitUntil(5_000) { latest?.lines?.singleOrNull()?.points?.firstOrNull() == GeoPoint(36.0, -122.0) }
        assertEquals(panned, latest!!.camera)
        compose.onNodeWithText(text(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)).performClick()
        compose.waitUntil(5_000) {
            latest?.points?.filter { it.hopIndex != null }?.size == 2 &&
                compose.onAllNodesWithText(text(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_pathedit_filter_discovered))
                    .fetchSemanticsNodes().any { node ->
                        node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled)
                    }
        }
        assertEquals(setOf(north.id, south.id, hill.id), latest!!.points.map { it.id }.toSet())
        val store = MeshCoreStorage.get(host.get()).preferences
        runBlocking { store.update {
            this[AppStorageKey.mapShowLabels] = false
            this[AppStorageKey.mapColorSchemePreference] = "dark"
        } }
        compose.waitUntil(5_000) { latest?.isDark == true && latest?.points?.all { it.label == null } == true }
        assertEquals(panned, latest!!.camera)
        compose.onNodeWithTag("pin:${hill.id}").performClick()
        compose.waitUntil(5_000) { latest?.points?.none { it.id == hill.id } == true }
        assertTrue(latest!!.lines.isEmpty())
        assertEquals(1, latest!!.points.single { it.id == north.id }.hopIndex)
        compose.onNodeWithText(text(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_trace_map_clear)).performClick()
        compose.onNodeWithTag("trace-map:confirm-clear").performClick()
        compose.waitUntil(5_000) { latest?.points?.map { it.id } == listOf(south.id) }
        assertTrue(latest!!.lines.isEmpty())
        assertEquals(panned, latest!!.camera)
        assertTrue(failures.isEmpty())
    }

    @Test fun discoveredRepeaterCanBeAddedFromTheAccessibleTraceEditorList() {
        mount()
        compose.onNodeWithText(text(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_trace_mode_list))
            .performClick()
        compose.onNodeWithText("Hill").assertIsDisplayed().performClick()

        compose.onNodeWithText(text(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_trace_mode_map))
            .performClick()
        compose.waitUntil(5_000) { latest?.points?.singleOrNull { it.id == hill.id }?.hopIndex == 1 }
        assertTrue(failures.isEmpty())
    }

    @Test fun systemMapAppearanceFollowsEffectiveAppThemeAndKeepsExplicitMapOverrides() {
        mount(AppColorSchemePreference.DARK)
        compose.waitUntil(5_000) { latest?.isDark == true }

        val preferences = MeshCoreStorage.get(host.get()).preferences
        runBlocking { preferences.set(AppStorageKey.mapColorSchemePreference, "light") }
        compose.waitUntil(5_000) { latest?.isDark == false }
        runBlocking { preferences.set(AppStorageKey.mapColorSchemePreference, "dark") }
        compose.waitUntil(5_000) { latest?.isDark == true }
    }

    @Test fun explicitTraceFramingReissuesAfterPanAndOnRepeatedFit() {
        mount(deterministicFrames = true)
        val initialRevision = latest!!.cameraCommandRevision
        val reportCamera = requireNotNull(cameraChange)
        host.get().runOnUiThread { reportCamera(panned) }
        advanceFrames()
        compose.waitForIdle()
        waitUntilAdvancingFrames {
            latest?.camera == panned
        }
        assertEquals(initialRevision, latest!!.cameraCommandRevision)

        compose.onNodeWithTag("trace-map:center-all").performClick()
        advanceFrames()
        compose.waitForIdle()
        waitUntilAdvancingFrames {
            latest?.camera != panned && latest!!.cameraCommandRevision > initialRevision
        }
        val firstFitRevision = latest!!.cameraCommandRevision
        compose.onNodeWithTag("trace-map:center-all").performClick()
        advanceFrames()
        compose.waitForIdle()
        waitUntilAdvancingFrames {
            latest!!.cameraCommandRevision > firstFitRevision
        }
    }

    private fun mount(
        appColorSchemePreference: AppColorSchemePreference = AppColorSchemePreference.SYSTEM,
        deterministicFrames: Boolean = false,
    ) {
        val directory = object : TraceNodeDirectory {
            override suspend fun fetchContacts(radioId: RadioId) = tableContacts
            override suspend fun fetchDiscoveredNodes(radioId: RadioId) = listOf(hill)
        }
        val feature = object : TracePathFeatureDependencies {
            override fun connectedDevice(): DeviceDTO? = null
            override fun bestAvailableLocation(): Coordinate? = null
            override fun nodeDirectory() = directory
            override fun savedPaths(): com.meshcoreone.android.core.contracts.domain.TracePathPersisting? = null
            override fun traceSender(): TraceSender? = null
            override fun traceResponses(): kotlinx.coroutines.flow.Flow<TraceResponse>? = null
        }
        val recent = object : RecentHopsStorage {
            private val values = mutableMapOf<String, List<String>>()
            override fun stringList(key: String) = values[key]
            override fun setStringList(key: String, value: List<String>) { values[key] = value }
        }
        val preferences = AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences, host.get().getSharedPreferences("shared-map", 0),
        )
        val dependencies = TracePathScreen.Dependencies(
            feature, recent, TraceDiagnostics { _, error -> failures += error }, radio, 0, preferences,
        )
        host.get().runOnUiThread {
            val parent = if (deterministicFrames) {
                BroadcastFrameClock().also { frameClock = it }.let { clock ->
                    host.get().window.decorView.createLifecycleAwareWindowRecomposer(
                        coroutineContext = clock,
                        lifecycle = host.get().lifecycle,
                    )
                }
            } else null
            host.get().setContent(parent = parent) {
                MeshCoreTheme(colorSchemePreference = appColorSchemePreference, systemDark = false) {
                    TracePathScreen(
                        dependencies.copy(nodeTableVersion = tableVersion.intValue),
                        mapSurface = { presentation, _, _, camera, marker, overlay ->
                            SideEffect {
                                latest = presentation
                                cameraChange = camera
                            }
                            Box(Modifier.fillMaxSize()) {
                                Column {
                                    presentation.points.forEach { point ->
                                        TextButton(
                                            onClick = { marker(point) }, modifier = Modifier.testTag("pin:${point.id}"),
                                        ) { Text(point.label ?: point.id.toString()) }
                                    }
                                    TextButton(onClick = { camera(panned) }, modifier = Modifier.testTag("pan")) { Text("Pan") }
                                }
                                overlay()
                            }
                        },
                    )
                }
            }
        }
        advanceFrames()
        compose.onNodeWithText(text(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_trace_mode_map)).performClick()
        advanceFrames()
        waitUntilAdvancingFrames {
            latest?.points?.size == 3 &&
                compose.onAllNodesWithText(text(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_pathedit_filter_favorites))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("trace-map:center-all").assertIsEnabled()
        assertNotNull(latest!!.camera)
    }

    private fun advanceFrames() {
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
            val clock = frameClock
            if (clock == null) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
            } else {
                host.get().runOnUiThread {
                    Snapshot.sendApplyNotifications()
                    frameNanos += 16_000_000L
                    clock.sendFrame(frameNanos)
                }
            }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    private fun waitUntilAdvancingFrames(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            advanceFrames()
            if (condition()) return
            Thread.sleep(10)
        }
        error("Condition was not satisfied while advancing the trace frame clock")
    }

    private fun text(id: Int): String = host.get().getString(id)
}
