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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
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
import java.time.Duration
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

    @After fun destroyHost() {
        host.pause().stop().destroy()
    }

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

    @Test fun consumedFocusAndItsSelectionSurviveRestorationWithoutReplayingTheRequest() {
        val saved = MapCamera(GeoPoint(51.0, 0.0), 0.5, 0.5)
        host.get().getSharedPreferences("shared-map", 0).edit()
            .putString("camera-v1", saved.encode()).commit()
        runBlocking {
            MeshCoreStorage.get(host.get()).preferences.set(
                AppStorageKey.mapFilterMainMap, MapFilterState(favoritesOnly = true).encode(),
            )
        }
        val focus = GeoPoint(10.0, 20.0)
        val pendingFocus = mutableStateOf<GeoPoint?>(focus)
        val dispatched = mutableListOf<MapPresentationState>()
        var consumed = 0
        val restoration = restorationTester()
        mount(
            MapFeatureDependencies(source { content() }, offline()),
            focus = { pendingFocus.value },
            onFocusConsumed = { consumed++; pendingFocus.value = null },
            onPresentation = { dispatched += it },
            restoration = restoration,
        )
        val pin = dispatched.last().points.single { it.style == MapPinStyle.DROPPED_PIN }
        compose.onNodeWithTag("pick:${pin.id}").performClick()
        compose.onNodeWithText("10.0, 20.0").assertIsDisplayed()

        dispatched.clear()
        restoration.emulateSavedInstanceStateRestore()
        advanceFrames()
        compose.waitForIdle()

        assertEquals(1, consumed)
        assertTrue(dispatched.isNotEmpty())
        assertTrue("Restoration must not dispatch the older cross-launch camera", dispatched.none { it.camera == saved })
        assertEquals(pin, dispatched.last().points.single { it.style == MapPinStyle.DROPPED_PIN })
        assertEquals(MapCamera(focus, 0.01, 0.01), dispatched.last().camera)
        compose.onNodeWithText("10.0, 20.0").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_common_done)).performClick()
        compose.waitForIdle()
        restoration.emulateSavedInstanceStateRestore()
        advanceFrames()
        compose.waitForIdle()
        assertEquals(1, consumed)
        assertTrue(dispatched.last().points.none { it.style == MapPinStyle.DROPPED_PIN })
        compose.onNodeWithText("10.0, 20.0").assertDoesNotExist()
        assertEquals(MapCamera(focus, 0.01, 0.01), dispatched.last().camera)
        val nextFocus = GeoPoint(30.0, 40.0)
        compose.runOnIdle { pendingFocus.value = nextFocus }
        compose.waitForIdle()
        assertEquals(2, consumed)
        val replacement = dispatched.last().points.single { it.style == MapPinStyle.DROPPED_PIN }
        assertEquals(pin.id, replacement.id)
        assertEquals(nextFocus, replacement.position)
        assertEquals(MapCamera(nextFocus, 0.01, 0.01), dispatched.last().camera)
    }

    @Test fun explicitFramingSurvivesRestorationBeforeNativeCameraPersistence() {
        val dispatched = mutableListOf<MapPresentationState>()
        lateinit var reportCamera: (MapCamera) -> Unit
        val restoration = restorationTester()
        mount(
            MapFeatureDependencies(source { content() }, offline()),
            onPresentation = { dispatched += it },
            onCameraCallback = { reportCamera = it },
            restoration = restoration,
        )
        val pan = MapCamera(GeoPoint(5.0, 6.0), 0.2, 0.3, MapCameraPose(12.0, 45.0, 30.0))
        compose.runOnIdle { reportCamera(pan) }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_centerall)).performClick()
        compose.waitForIdle()
        val framed = requireNotNull(dispatched.last().camera)
        assertNotEquals(pan, framed)
        assertEquals(pan.encode(), host.get().getSharedPreferences("shared-map", 0).getString("camera-v1", null))

        dispatched.clear()
        restoration.emulateSavedInstanceStateRestore()
        advanceFrames()
        compose.waitForIdle()

        assertTrue(dispatched.isNotEmpty())
        assertTrue("Restoration must retain the explicit frame before native persistence", dispatched.all { it.camera == framed })
        compose.runOnIdle { reportCamera(pan) }
        restoration.emulateSavedInstanceStateRestore()
        advanceFrames()
        compose.waitForIdle()
        assertEquals(pan, dispatched.last().camera)
    }

    @Test fun newPendingFocusOverridesRestoredPinAndCameraBeforeAnyPresentation() {
        val original = GeoPoint(10.0, 20.0)
        val replacement = GeoPoint(30.0, 40.0)
        val pendingFocus = mutableStateOf<GeoPoint?>(original)
        val dispatched = mutableListOf<MapPresentationState>()
        var replaceOnDispose = false
        var consumed = 0
        val restoration = restorationTester()
        mount(
            MapFeatureDependencies(source { content() }, offline()),
            focus = { pendingFocus.value },
            onFocusConsumed = { consumed++; pendingFocus.value = null },
            onPresentation = { dispatched += it },
            restoration = restoration,
            onMapDispose = {
                if (replaceOnDispose) {
                    replaceOnDispose = false
                    pendingFocus.value = replacement
                }
            },
        )
        val pinId = dispatched.last().points.single { it.style == MapPinStyle.DROPPED_PIN }.id
        compose.runOnIdle { replaceOnDispose = true }
        dispatched.clear()
        restoration.emulateSavedInstanceStateRestore()
        advanceFrames()
        compose.waitForIdle()

        assertEquals(2, consumed)
        assertTrue(dispatched.isNotEmpty())
        assertTrue(
            "A new pending focus must own every restored camera presentation",
            dispatched.all { it.camera == MapCamera(replacement, 0.01, 0.01) },
        )
        dispatched.forEach { state ->
            val pin = state.points.single { it.style == MapPinStyle.DROPPED_PIN }
            assertEquals(pinId, pin.id)
            assertEquals(replacement, pin.position)
        }

        dispatched.clear()
        restoration.emulateSavedInstanceStateRestore()
        advanceFrames()
        compose.waitForIdle()
        assertEquals(2, consumed)
        assertTrue(dispatched.isNotEmpty())
        assertTrue(dispatched.all { it.camera == MapCamera(replacement, 0.01, 0.01) })
        assertEquals(replacement, dispatched.last().points.single { it.id == pinId }.position)
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

    @Test
    @Config(qualifiers = "de-rDE-w360dp-h1600dp-mdpi")
    fun suspendedOfflinePanelExplainsLocalizedUnavailableActionsAndRetainsCompletedAndDelete() {
        val complete = OfflinePack(
            UUID(0, 100), "Completed region", OfflineLayer.BASE,
            GeoBounds(GeoPoint(37.0, -123.0), GeoPoint(38.0, -122.0)),
            10, 14, Instant.EPOCH, OfflinePackState.COMPLETE, 1f,
        )
        val partial = complete.copy(id = UUID(0, 101), name = "Partial region", state = OfflinePackState.PAUSED, completedFraction = 0.4f)
        val saved = kotlinx.coroutines.flow.MutableStateFlow(listOf(complete, partial))
        val deleted = mutableListOf<UUID>()
        val backend = object : OfflineMapBackend {
            override val packs = saved
            override val downloadError = OfflineMapError.OpenFreeMapDownloadsSuspended
            override suspend fun createAndStart(request: OfflinePackRequest) = error("Suspended creation")
            override suspend fun resume(packId: UUID) = error("Suspended resume")
            override suspend fun pause(packId: UUID) = error("Already paused")
            override suspend fun delete(packId: UUID) {
                deleted += packId
                saved.value = saved.value.filterNot { it.id == packId }
            }
        }
        val controller = OfflineMapController(backend, emptyMap(), { error("No disk probe") }, { error("No network probe") })
        mount(MapFeatureDependencies(source { content() }, controller))
        compose.onNodeWithText(text(R.string.l10n_app_settings_offlinemaps_title)).performClick()
        val explanation = text(R.string.map_offline_downloads_suspended)
        assertTrue("Must resolve the German Android resource", explanation.startsWith("Neue und fortgesetzte"))
        compose.onNodeWithTag("map:offline-unavailable").assertTextEquals(explanation).assertIsDisplayed()
        compose.onNodeWithTag("map:offline-download").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("map:offline-pack:${complete.id}").performScrollTo().assertExists()
        compose.onNodeWithTag("map:offline-resume:${complete.id}").assertDoesNotExist()
        compose.onNodeWithTag("map:offline-resume:${partial.id}").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("map:offline-delete:${partial.id}").performScrollTo().assertIsEnabled().performClick()
        compose.onNode(hasText(partial.name) and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.onNode(hasText(text(R.string.l10n_app_localizable_common_delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(5_000) { deleted == listOf(partial.id) }
        assertEquals(listOf(complete), saved.value)
        compose.onNodeWithTag("map:offline-pack:${complete.id}").performScrollTo().assertExists()
        compose.onNodeWithTag("map:offline-delete:${complete.id}").assertIsEnabled()
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

        host.get().runOnUiThread {
            dependencies.value = initial.copy(dataSource = source { loads++; content() })
        }
        advanceFrames()
        compose.waitForIdle()
        waitUntilAdvancingFrames {
            loads == 2 && compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isNotEmpty()
        }

        runBlocking {
            store.setFilter(MapFilterHost.MAIN_MAP, MapFilterState(favoritesOnly = true))
        }
        waitUntilAdvancingFrames {
            compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun missingPreferenceDependencyReportsUnavailableAndRecoversWithThePendingFocusAndRealStore() {
        var loads = 0
        var consumed = 0
        val target = GeoPoint(45.0, -110.0)
        val initial = MapFeatureDependencies(source { loads++; content() }, offline())
        val dependencies = mutableStateOf(initial)
        var latest: MapPresentationState? = null
        val presentations = mutableListOf<MapPresentationState>()
        mount(
            initial, focus = { target }, onFocusConsumed = { consumed++ },
            onPresentation = { latest = it; presentations += it },
            dependenciesProvider = { dependencies.value }, awaitReady = false,
        )
        compose.onNodeWithTag("map:preferences-unavailable").assertIsDisplayed()
        compose.onNodeWithText(text(R.string.l10n_app_localizable_common_error_failedtoload)).assertIsDisplayed()
        compose.onNodeWithTag("test-renderer").assertDoesNotExist()
        compose.onNodeWithTag("map:preference-retry").assertDoesNotExist()
        assertTrue(presentations.isEmpty())
        assertEquals(0, loads)
        assertEquals(0, consumed)
        assertNull(MapFilterState.decode(runBlocking {
            MeshCoreStorage.get(host.get()).preferences.get(AppStorageKey.mapFilterMainMap)
        }))

        val real = AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences,
            host.get().getSharedPreferences("shared-map", 0),
        )
        runBlocking {
            real.setFilter(MapFilterHost.MAIN_MAP, MapFilterState(favoritesOnly = true))
            MeshCoreStorage.get(host.get()).preferences.set(AppStorageKey.mapColorSchemePreference, "dark")
        }
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val store = object : MapPreferenceStore by real {
            override fun observe(host: MapFilterHost) = kotlinx.coroutines.flow.flow {
                started.complete(Unit)
                release.await()
                real.observe(host).collect { emit(it) }
            }
        }
        compose.runOnIdle { dependencies.value = initial.copy(preferences = store) }
        compose.onNodeWithTag("map:preferences-unavailable").assertDoesNotExist()
        waitUntilAdvancingFrames { started.isCompleted }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_showlabels)).assertDoesNotExist()
        release.complete(Unit)
        waitUntilAdvancingFrames {
            compose.onAllNodesWithText(text(R.string.l10n_app_map_map_controls_showlabels)).fetchSemanticsNodes().isNotEmpty() &&
                latest?.isDark == true && latest?.points?.none { it.id == contact.id } == true
        }
        assertEquals(1, loads)
        assertEquals(1, consumed)
        assertEquals(target, requireNotNull(latest).points.single { it.style == MapPinStyle.DROPPED_PIN }.position)
        assertEquals(target, requireNotNull(latest).camera?.center)
        assertTrue(runBlocking { real.observe(MapFilterHost.MAIN_MAP).first() }.filter.favoritesOnly)
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
        restoration: StateRestorationTester? = null,
        onCameraCallback: ((MapCamera) -> Unit) -> Unit = {},
        onMapDispose: () -> Unit = {},
        awaitReady: Boolean = true,
    ) {
        val bound = dependencies.copy(preferences = dependencies.preferences ?: AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences,
            host.get().getSharedPreferences("shared-map", 0),
        ))
        host.get().runOnUiThread {
            val content: @Composable () -> Unit = {
                MeshCoreTheme(colorSchemePreference = appColorSchemePreference, systemDark = false, motionScale = 0f) {
                    MapEntry(
                        FeatureRoute(FeatureId.MAP), { error("No generic navigation") },
                        dependenciesProvider?.invoke() ?: bound,
                        focus = focus(),
                        onFocusConsumed = onFocusConsumed,
                        mapSurface = { state, _, _, camera, select, overlay ->
                            SideEffect { onPresentation(state); onCameraCallback(camera) }
                            DisposableEffect(Unit) { onDispose { onMapDispose() } }
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
            if (restoration == null) host.get().setContent(content = content) else restoration.setContent(content)
        }
        advanceFrames()
        compose.waitForIdle()
        if (awaitReady) {
            waitUntilAdvancingFrames {
                compose.onAllNodesWithText(text(R.string.l10n_app_map_map_controls_centerall))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        compose.waitForIdle()
    }

    private fun restorationTester() = StateRestorationTester(
        object : ComposeContentTestRule, ComposeTestRule by compose {
            override fun setContent(composable: @Composable () -> Unit) {
                host.get().setContent(content = composable)
            }
        },
    )

    private fun advanceFrames() {
        repeat(6) {
            compose.mainClock.advanceTimeByFrame()
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
        error("Condition was not satisfied while advancing the mounted map frame clock")
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
