// AndroidOnly: WP-312 Mounted MapEntry action/filter/live-refresh and legal-link regressions.
// Native MapLibre is replaced only at the rendering seam; these are not device/projection observations.
package com.meshcoreone.android.app.container

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.BackupPreferenceSnapshot
import com.meshcoreone.android.core.datastore.PreferenceValue
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.maps.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.map.*
import java.io.IOException
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
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
class MapMountedConsumerTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val radio = RadioId(UUID(0, 1))
    private val contact = ContactDTO(
        id = UUID(0, 2), radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }),
        name = "Located contact", typeRawValue = 1u, latitude = 37.0, longitude = -122.0,
        lastHeardTimestamp = null,
    )
    private val node = DiscoveredNodeDTO(
        UUID(0, 3), radio, Bytes(ByteArray(32) { 2 }), "Located discovery", 1u,
        Instant.EPOCH, 0u, 38.0, -123.0, 255u, Bytes.EMPTY, null, null,
    )

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        host.get().getSharedPreferences("shared-map", 0).edit().clear().commit()
        runBlocking { MeshCoreStorage.get(host.get()).preferences.resetAppPreferences() }
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun mountedContactDetailAndMessageForwardTheActualDtoAndClearSelection() {
        val details = mutableListOf<ContactDTO>()
        val messages = mutableListOf<ContactDTO>()
        mount(MapFeatureDependencies(source { content() }, offline(), actions = object : MapFeatureActions {
            override fun openContact(contact: ContactDTO) { details += contact }
            override fun openChat(contact: ContactDTO) { messages += contact }
            override suspend fun addDiscovered(node: DiscoveredNodeDTO) = error("No add requested")
        }))
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_callout_details)).performClick()
        assertEquals(listOf(contact), details)
        compose.onNodeWithText("37.0, -122.0").assertDoesNotExist()
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_callout_message)).performClick()
        assertEquals(listOf(contact), messages)
    }

    @Test fun mountedFilterAndLiveStoreRefreshCannotRetainAHiddenOrDeletedSelection() {
        val updates = MutableSharedFlow<MapFeatureUpdate>(extraBufferCapacity = 10)
        var current = content()
        mount(MapFeatureDependencies(source { current }, offline(), updates))
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.onNodeWithText("37.0, -122.0").assertExists()
        compose.onNodeWithText(text(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("37.0, -122.0").assertDoesNotExist()
        compose.onNodeWithTag("pick:${contact.id}").assertDoesNotExist()
        compose.onNodeWithText(text(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.runOnIdle {
            current = MapFeatureContent()
            assertTrue(updates.tryEmit(MapFeatureUpdate("same-radio")))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText("37.0, -122.0").assertDoesNotExist()
    }

    @Test fun mountedDiscoveredDetailAddsTheActualNodeThroughTheAppPort() {
        val added = mutableListOf<DiscoveredNodeDTO>()
        mount(MapFeatureDependencies(source { content() }, offline(), actions = object : MapFeatureActions {
            override fun openContact(contact: ContactDTO) = error("No navigation")
            override fun openChat(contact: ContactDTO) = error("No navigation")
            override suspend fun addDiscovered(node: DiscoveredNodeDTO) { added += node }
        }))
        compose.onNodeWithText(text(R.string.l10n_app_map_map_callout_discovered)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pick:${node.id}").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("pick:${node.id}").performClick()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_discovereddetail_title)).assertExists()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_discovereddetail_add)).performClick()
        compose.waitForIdle()
        assertEquals(listOf(node), added)
    }

    @Test fun realSharedAttributionRendersAndDispatchesEveryApprovedProviderLink() {
        val opened = mutableListOf<String>()
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                    override fun openUri(uri: String) { opened += uri }
                }) {
                    MapAttribution(MapLibreOpenFreeMap.attribution)
                }
            }
        }
        MapLibreOpenFreeMap.attribution.forEach { provider ->
            compose.onNodeWithText(provider.label).assertIsDisplayed().performClick()
        }
        assertEquals(MapLibreOpenFreeMap.attribution.map { it.legalUri }, opened)
    }

    @Test fun pendingFocusNeverDispatchesSavedCameraAndDoneRemovesItsPin() {
        val saved = MapCamera(GeoPoint(51.0, 0.0), 0.5, 0.5)
        host.get().getSharedPreferences("shared-map", 0).edit()
            .putString("camera-v1", saved.encode()).commit()
        val focus = GeoPoint(10.0, 20.0)
        val pendingFocus = mutableStateOf<GeoPoint?>(focus)
        val dispatched = mutableListOf<MapPresentationState>()
        var consumed = 0
        mount(
            MapFeatureDependencies(source { content() }, offline()),
            focus = { pendingFocus.value },
            onFocusConsumed = { consumed++; pendingFocus.value = null },
            onPresentation = { dispatched += it },
        )
        assertTrue(dispatched.isNotEmpty())
        assertTrue(dispatched.none { it.camera == saved })
        assertEquals(MapCamera(focus, 0.01, 0.01), dispatched.last().camera)
        assertEquals(1, consumed)
        val pin = dispatched.last().points.single { it.style == MapPinStyle.DROPPED_PIN }
        assertFalse(pin.clusterable)
        compose.onNodeWithTag("pick:${pin.id}").performClick()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_common_done)).performClick()
        compose.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            dispatched.lastOrNull()?.points?.none { it.style == MapPinStyle.DROPPED_PIN } == true
        }
        assertEquals(MapCamera(focus, 0.01, 0.01), dispatched.last().camera)
    }

    @Test fun newFocusReplacesTheSingleStablePinAndReloadPreservesIt() {
        val focus = mutableStateOf<GeoPoint?>(GeoPoint(10.0, 20.0))
        val updates = MutableSharedFlow<MapFeatureUpdate>(extraBufferCapacity = 10)
        var loads = 0
        var latest: MapPresentationState? = null
        mount(
            MapFeatureDependencies(source { loads++; content() }, offline(), updates),
            focus = { focus.value },
            onPresentation = { latest = it },
        )
        val id = requireNotNull(latest).points.single { it.style == MapPinStyle.DROPPED_PIN }.id
        compose.runOnIdle { focus.value = GeoPoint(30.0, 40.0) }
        compose.waitForIdle()
        val replaced = requireNotNull(latest).points.single { it.style == MapPinStyle.DROPPED_PIN }
        assertEquals(id, replaced.id)
        assertEquals(GeoPoint(30.0, 40.0), replaced.position)
        assertEquals(MapCamera(replaced.position, 0.01, 0.01), requireNotNull(latest).camera)
        compose.runOnIdle { assertTrue(updates.tryEmit(MapFeatureUpdate("radio"))) }
        compose.waitUntil(5_000) { loads == 2 && requireNotNull(latest).points.any { it.id == contact.id } }
        assertEquals(replaced, requireNotNull(latest).points.single { it.style == MapPinStyle.DROPPED_PIN })
    }

    @Test fun initialAndExplicitFramingUseOnlyVisiblePinsAndEmptyMapDisablesCenterAll() {
        host.get().getSharedPreferences("shared-map", 0).edit().putString(
            "filter-v1", MapFilterState(favoritesOnly = true).encode(),
        ).commit()
        val favorite = content().markers.first().copy(isFavorite = true)
        var latest: MapPresentationState? = null
        mount(
            MapFeatureDependencies(source { MapFeatureContent(listOf(favorite, content().markers.last())) }, offline()),
            onPresentation = { latest = it },
        )
        assertEquals(listOf(contact.id), requireNotNull(latest).points.map { it.id })
        assertEquals(listOf(favorite.marker.position).boundingCamera(), requireNotNull(latest).camera)
        val initialCommandRevision = requireNotNull(latest).cameraCommandRevision
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_centerall)).assertIsEnabled().performClick()
        compose.waitUntil(5_000) {
            requireNotNull(latest).cameraCommandRevision > initialCommandRevision
        }
        assertEquals(listOf(favorite.marker.position).boundingCamera(), requireNotNull(latest).camera)
    }

    @Test fun emptyMapHasNoCameraAndCannotCenter() {
        var latest: MapPresentationState? = null
        mount(
            MapFeatureDependencies(source { MapFeatureContent() }, offline()),
            onPresentation = { latest = it },
        )
        assertNull(requireNotNull(latest).camera)
        assertTrue(requireNotNull(latest).points.isEmpty())
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_centerall)).assertIsNotEnabled()
    }

    @Test fun mountedHostObservesLegacyRestoreAndAppearanceWithoutARefetchOrPrivateStore() {
        var loads = 0
        var latest: MapPresentationState? = null
        mount(
            MapFeatureDependencies(source { loads++; content() }, offline()),
            onPresentation = { latest = it },
        )
        assertEquals(1, loads)
        compose.onNodeWithTag("pick:${node.id}").assertDoesNotExist()
        val storage = MeshCoreStorage.get(host.get())
        runBlocking {
            storage.backupPreferences.restoreMissing(BackupPreferenceSnapshot(mapOf(
                "showDiscoveredNodesOnMap" to PreferenceValue.BooleanValue(true),
                "mapColorSchemePreference" to PreferenceValue.StringValue("dark"),
            )))
        }

        compose.waitUntil(5_000) {
            requireNotNull(latest).isDark && requireNotNull(latest).points.any { it.id == node.id }
        }
        assertEquals(1, loads)
        compose.onNodeWithTag("pick:${node.id}").assertExists()
        runBlocking {
            assertTrue(requireNotNull(MapFilterState.decode(storage.preferences.get(AppStorageKey.mapFilterMainMap)))
                .showDiscovered)
            storage.preferences.set(AppStorageKey.mapColorSchemePreference, "light")
        }
        compose.waitUntil(5_000) { !requireNotNull(latest).isDark }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_clusternodes)).performClick()
        compose.waitUntil(5_000) {
            !runBlocking { storage.preferences.get(AppStorageKey.mapClusteringEnabled) }
        }
        assertFalse(host.get().getSharedPreferences("shared-map", 0).contains("cluster-v1"))
        assertEquals(PreferenceValue.BooleanValue(false),
            runBlocking { storage.backupPreferences.snapshotForBackup() }.presentValues["mapClusteringEnabled"])
    }

    @Test fun preferenceObservationRebindsWhenDependenciesChangeWithTheSameStore() {
        val store = AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences,
            host.get().getSharedPreferences("shared-map", 0),
        )
        var loads = 0
        val initial = MapFeatureDependencies(source { loads++; content() }, offline(), preferences = store)
        val dependencies = mutableStateOf(initial)
        mount(initial, dependenciesProvider = { dependencies.value })
        compose.onNodeWithTag("pick:${contact.id}").assertExists()

        compose.runOnIdle {
            dependencies.value = initial.copy(dataSource = source { loads++; content() })
        }
        compose.runOnUiThread { Shadows.shadowOf(Looper.getMainLooper()).idle() }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            loads == 2 && compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            store.setFilter(MapFilterHost.MAIN_MAP, MapFilterState(favoritesOnly = true))
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun successfulPreferenceWriteRestartsFailedObservationUntilNewValueArrives() {
        val real = AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences,
            host.get().getSharedPreferences("shared-map", 0),
        )
        val writeStarted = CompletableDeferred<Unit>()
        val releaseWrite = CompletableDeferred<Unit>()
        val writeFinished = CompletableDeferred<Unit>()
        val retryObservationStarted = CompletableDeferred<Unit>()
        val releaseRetryObservation = CompletableDeferred<Unit>()
        var observationCount = 0
        val store = object : MapPreferenceStore by real {
            override fun observe(host: MapFilterHost) = kotlinx.coroutines.flow.flow {
                if (host != MapFilterHost.MAIN_MAP) {
                    real.observe(host).collect { emit(it) }
                    return@flow
                }
                when (observationCount++) {
                    0 -> {
                        emit(real.observe(host).first())
                        writeStarted.await()
                        throw IOException("Fixture observation failure after initial value")
                    }
                    1 -> {
                        retryObservationStarted.complete(Unit)
                        releaseRetryObservation.await()
                        emit(real.observe(host).first())
                        real.observe(host).collect { emit(it) }
                    }
                    else -> real.observe(host).collect { emit(it) }
                }
            }

            override suspend fun setLabels(enabled: Boolean) {
                writeStarted.complete(Unit)
                releaseWrite.await()
                real.setLabels(enabled)
                writeFinished.complete(Unit)
            }
        }
        mount(
            MapFeatureDependencies(source { content() }, offline(), preferences = store),
        )
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_showlabels)).performClick()
        compose.waitUntil(5_000) {
            writeStarted.isCompleted &&
                compose.onAllNodesWithTag("map:preference-retry").fetchSemanticsNodes().isNotEmpty()
        }
        releaseWrite.complete(Unit)
        compose.waitUntil(5_000) { writeFinished.isCompleted }
        compose.waitUntil(5_000) { retryObservationStarted.isCompleted }
        compose.onNodeWithTag("map:preference-retry").assertExists()
        releaseRetryObservation.complete(Unit)
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("map:preference-retry").fetchSemanticsNodes().isEmpty()
        }
        assertFalse(runBlocking { real.observe(MapFilterHost.MAIN_MAP).first().labels })
    }

    @Test fun systemMapAppearanceFollowsEffectiveAppThemeAndKeepsExplicitMapOverrides() {
        var latest: MapPresentationState? = null
        mount(
            MapFeatureDependencies(source { content() }, offline()),
            onPresentation = { latest = it },
            appColorSchemePreference = AppColorSchemePreference.DARK,
        )
        compose.waitUntil(5_000) { latest?.isDark == true }

        val preferences = MeshCoreStorage.get(host.get()).preferences
        runBlocking { preferences.set(AppStorageKey.mapColorSchemePreference, "light") }
        compose.waitUntil(5_000) { latest?.isDark == false }
        runBlocking { preferences.set(AppStorageKey.mapColorSchemePreference, "dark") }
        compose.waitUntil(5_000) { latest?.isDark == true }
    }

    private fun mount(
        dependencies: MapFeatureDependencies,
        focus: () -> GeoPoint? = { null },
        onFocusConsumed: () -> Unit = {},
        onPresentation: (MapPresentationState) -> Unit = {},
        appColorSchemePreference: AppColorSchemePreference = AppColorSchemePreference.SYSTEM,
        dependenciesProvider: (() -> MapFeatureDependencies)? = null,
    ) {
        val bound = dependencies.copy(preferences = dependencies.preferences ?: AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences,
            host.get().getSharedPreferences("shared-map", 0),
        ))
        host.get().setContent {
            MeshCoreTheme(colorSchemePreference = appColorSchemePreference, systemDark = false, motionScale = 0f) {
                MapEntry(
                    FeatureRoute(FeatureId.MAP), { error("No generic navigation") },
                    dependenciesProvider?.invoke() ?: bound,
                    focus = focus(),
                    onFocusConsumed = onFocusConsumed,
                    mapSurface = { state, _, _, _, select, overlay ->
                        SideEffect { onPresentation(state) }
                        Box(Modifier.fillMaxSize()) {
                            Column(Modifier.align(androidx.compose.ui.Alignment.Center).testTag("test-renderer")) {
                                state.points.forEach { marker ->
                                    TextButton({ select(marker) }, Modifier.testTag("pick:${marker.id}")) { Text(marker.label.orEmpty()) }
                                }
                            }
                            overlay()
                        }
                    },
                )
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText(text(R.string.l10n_app_map_map_controls_centerall))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }

    private fun content() = MapFeatureContent(listOf(
        MapFeatureMarker(
            MapMarker(contact.id, GeoPoint(contact.latitude, contact.longitude), MapPinStyle.CONTACT_CHAT, contact.name),
            ContactMapType.CHAT, false, false, MapSelection.Contact(contact),
        ),
        MapFeatureMarker(
            MapMarker(node.id, GeoPoint(node.latitude, node.longitude), MapPinStyle.CONTACT_CHAT, node.name),
            ContactMapType.CHAT, false, true, MapSelection.Discovered(node),
        ),
    ))
    private fun source(load: suspend () -> MapFeatureContent) = MapFeatureDataSource { load() }
    private fun offline() = OfflineMapController(
        UnavailableOfflineMapBackend, emptyMap(), { error("No download") }, { error("No download") },
    )
    private fun text(id: Int) = host.get().getString(id)
}
