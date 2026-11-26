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
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
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
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h1200dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalTestApi::class)
class MapMountedConsumerTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
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
        openFilters()
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

    @Test fun mountedAdvancedFiltersApplyLiveAndClearIndependently() {
        val recent = Instant.parse("2026-01-01T00:00:00Z")
        val clock = java.time.Clock.fixed(recent, java.time.ZoneOffset.UTC)
        val markers = content().markers.map {
            it.copy(heardAt = recent, displayedHops = 2)
        }
        mount(MapFeatureDependencies(source { MapFeatureContent(markers) }, offline(), clock = clock))
        openFilters()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("map:advanced-open").performClick()
            advanceFrames()
            compose.onNodeWithTag("map:advanced-scroll")
                .performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 2_000f) }
            settleAnimations()
            compose.onNodeWithTag("map:max-hops").assertIsDisplayed().performTextInput("1")
            waitUntilAdvancingFrames {
                compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithTag("map:advanced-reset").performClick()
            waitUntilAdvancingFrames {
                compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("map:max-hops").assertIsDisplayed().performTextInput("1")
            advanceFrames()
            compose.onNodeWithText(text(R.string.l10n_app_map_map_common_done)).performClick()
            advanceFrames()
            compose.onNodeWithText(host.get().getString(R.string.map_advanced_clear, text(R.string.map_advanced_hops)))
                .performClick()
            waitUntilAdvancingFrames {
                compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isNotEmpty()
            }
        } finally {
            compose.mainClock.autoAdvance = true
        }
        compose.waitForIdle()
    }

    @Test fun advancedBoundsAndUnitsHaveDistinctAccessibleLabels() {
        mount(MapFeatureDependencies(source { content() }, offline()))
        openFilters()
        compose.onNodeWithTag("map:advanced-open").performClick()
        advanceFrames()
        fun scoped(dimension: Int, bound: Int) = host.get().getString(
            R.string.text_rules_scoped_choice, text(dimension), text(bound),
        )
        val heardMin = scoped(R.string.map_advanced_last_heard, R.string.map_advanced_minimum)
        val heardMax = scoped(R.string.map_advanced_last_heard, R.string.map_advanced_maximum)
        fun assertBound(tag: String, label: String) {
            compose.onNodeWithTag(tag).performScrollTo().performTextInput("1")
            compose.onNodeWithTag(tag).assertTextContains(label)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("1")))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ContentDescription))
        }
        assertBound("map:min-age", heardMin)
        assertBound("map:max-age", heardMax)
        compose.onNodeWithTag("map:min-age-unit").performScrollTo().assertContentDescriptionEquals(
            host.get().getString(R.string.text_rules_scoped_choice, heardMin, text(R.string.map_advanced_hours)),
        )
        compose.onNodeWithTag("map:max-age-unit").performScrollTo().assertContentDescriptionEquals(
            host.get().getString(R.string.text_rules_scoped_choice, heardMax, text(R.string.map_advanced_hours)),
        )
        assertBound("map:min-hops", scoped(R.string.map_advanced_hops, R.string.map_advanced_minimum))
        assertBound("map:max-hops", scoped(R.string.map_advanced_hops, R.string.map_advanced_maximum))
    }

    @Test fun delayedFilterWritesDoNotOverwriteNewerNumericAndUnitEdits() {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseSecond = CompletableDeferred<Unit>()
        val observed = kotlinx.coroutines.flow.MutableStateFlow(
            MapPreferences(MapFilterState(), clustering = true, labels = true, appearance = MapColorPreference.SYSTEM),
        )
        val store = object : MapPreferenceStore {
            var writes = 0
            override fun observe(host: MapFilterHost) = observed
            override suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState) {
                when (++writes) {
                    1 -> { firstStarted.complete(Unit); releaseFirst.await() }
                    2 -> { secondStarted.complete(Unit); releaseSecond.await() }
                }
                observed.value = observed.value.copy(filter = transform(observed.value.filter))
            }
            override suspend fun setFilter(host: MapFilterHost, filter: MapFilterState) = error("No set requested")
            override suspend fun setClustering(enabled: Boolean) = error("No clustering change")
            override suspend fun setLabels(enabled: Boolean) = error("No label change")
            override suspend fun setNorthLocked(enabled: Boolean) = error("No north-lock change")
            override suspend fun setStyle(style: MapStyle) = error("No style change")
        }
        mount(MapFeatureDependencies(source { content() }, offline(), preferences = store))
        openFilters()
        compose.mainClock.autoAdvance = false
        try {
            compose.onNodeWithTag("map:advanced-open").performClick()
            advanceFrames()
            compose.onNodeWithTag("map:max-age").performTextReplacement("1")
            waitUntilAdvancingFrames { firstStarted.isCompleted }
            compose.onNodeWithTag("map:max-age").performTextReplacement("12")
            compose.onNodeWithTag("map:max-age-unit").performClick()
            advanceFrames()
            compose.onNodeWithTag("map:max-age-unit:days").performSemanticsAction(SemanticsActions.OnClick)
            advanceFrames()
            releaseFirst.complete(Unit)
            waitUntilAdvancingFrames { secondStarted.isCompleted }
            advanceFrames()
            assertEquals(MapDuration.of(1, MapDurationUnit.hours), observed.value.filter.lastHeard.maxAge)
            compose.onNodeWithTag("map:max-age").assertTextContains("12")
            compose.onNodeWithTag("map:max-age-unit").assertTextContains(text(R.string.map_advanced_days))
            releaseSecond.complete(Unit)
            waitUntilAdvancingFrames {
                observed.value.filter.lastHeard.maxAge == MapDuration.of(12, MapDurationUnit.days)
            }
            advanceFrames()
            compose.onNodeWithTag("map:max-age").assertTextContains("12")
            compose.onNodeWithTag("map:max-age-unit").assertTextContains(text(R.string.map_advanced_days))
        } finally {
            releaseFirst.complete(Unit)
            releaseSecond.complete(Unit)
            compose.mainClock.autoAdvance = true
        }
    }

    @Test fun foregroundRefreshAgesVisibleNodesWithoutStoreTraffic() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        val clock = object : java.time.Clock() {
            override fun instant() = now
            override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
        }
        val marker = content().markers.first().copy(heardAt = now)
        runBlocking {
            MeshCoreStorage.get(host.get()).preferences.set(
                AppStorageKey.mapFilterMainMap,
                MapFilterState(lastHeard = MapLastHeardRange.of(maxAge = MapDuration.of(1, MapDurationUnit.minutes))).encode(),
            )
        }
        mount(MapFeatureDependencies(source { MapFeatureContent(listOf(marker)) }, offline(), clock = clock))
        compose.onNodeWithTag("pick:${contact.id}").assertExists()
        compose.runOnIdle {
            host.pause().stop()
            now = now.plusSeconds(61)
            host.start().resume().visible().windowFocusChanged(true)
        }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("pick:${contact.id}").fetchSemanticsNodes().isEmpty()
        }
    }

    @Test fun recurringRefreshExpiresSelectionAndStopsWhileActivityIsStopped() {
        var now = Instant.parse("2026-01-01T00:00:00Z")
        var clockReads = 0
        var loads = 0
        val clock = object : java.time.Clock() {
            override fun instant(): Instant { clockReads++; return now }
            override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
        }
        val marker = content().markers.first().copy(heardAt = now)
        runBlocking {
            MeshCoreStorage.get(host.get()).preferences.set(
                AppStorageKey.mapFilterMainMap,
                MapFilterState(lastHeard = MapLastHeardRange.of(maxAge = MapDuration.of(2, MapDurationUnit.minutes))).encode(),
            )
        }
        mount(MapFeatureDependencies(source { loads++; MapFeatureContent(listOf(marker)) }, offline(), clock = clock))
        compose.onNodeWithTag("pick:${contact.id}").performClick()
        compose.onNodeWithText("37.0, -122.0").assertExists()
        val initialReads = clockReads
        val initialLoads = loads
        now = now.plusSeconds(61)
        compose.mainClock.advanceTimeBy(60_000)
        advanceFrames()
        assertEquals(initialReads + 1, clockReads)
        compose.onNodeWithTag("pick:${contact.id}").assertExists()
        compose.onNodeWithText("37.0, -122.0").assertExists()
        now = now.plusSeconds(60)
        compose.mainClock.advanceTimeBy(60_000)
        advanceFrames()
        assertEquals(initialReads + 2, clockReads)
        compose.onNodeWithTag("pick:${contact.id}").assertDoesNotExist()
        compose.onNodeWithText("37.0, -122.0").assertDoesNotExist()
        assertEquals(initialLoads, loads)

        compose.runOnIdle { host.pause().stop() }
        advanceFrames()
        val pausedReads = clockReads
        now = now.plusSeconds(180)
        compose.mainClock.advanceTimeBy(180_000)
        advanceFrames()
        assertEquals(pausedReads, clockReads)
        compose.runOnIdle { host.start().resume().visible().windowFocusChanged(true) }
        advanceFrames()
        assertEquals(pausedReads + 1, clockReads)
        compose.mainClock.advanceTimeBy(60_000)
        advanceFrames()
        assertEquals(pausedReads + 2, clockReads)
        assertEquals(initialLoads, loads)
    }

    @Test fun recurringRefreshStopsWhenScreenLeavesComposition() {
        var clockReads = 0
        var disposed = false
        val showMap = mutableStateOf(true)
        val clock = object : java.time.Clock() {
            override fun instant(): Instant { clockReads++; return Instant.parse("2026-01-01T00:00:00Z") }
            override fun getZone(): java.time.ZoneId = java.time.ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId): java.time.Clock = this
        }
        runBlocking {
            MeshCoreStorage.get(host.get()).preferences.set(
                AppStorageKey.mapFilterMainMap,
                MapFilterState(lastHeard = MapLastHeardRange.of(maxAge = MapDuration.of(2, MapDurationUnit.minutes))).encode(),
            )
        }
        mount(
            MapFeatureDependencies(source { content() }, offline(), clock = clock),
            onMapDispose = { disposed = true },
            showMap = { showMap.value },
        )
        val initialReads = clockReads
        compose.mainClock.advanceTimeBy(60_000)
        advanceFrames()
        assertEquals(initialReads + 1, clockReads)
        compose.runOnIdle { showMap.value = false }
        compose.waitForIdle()
        assertTrue("The map renderer must leave composition", disposed)
        val disposedReads = clockReads
        compose.mainClock.advanceTimeBy(120_000)
        advanceFrames()
        assertEquals(disposedReads, clockReads)
    }

    @Test fun mountedDiscoveredDetailAddsTheActualNodeThroughTheAppPort() {
        val added = mutableListOf<DiscoveredNodeDTO>()
        mount(MapFeatureDependencies(source { content() }, offline(), actions = object : MapFeatureActions {
            override fun openContact(contact: ContactDTO) = error("No navigation")
            override fun openChat(contact: ContactDTO) = error("No navigation")
            override suspend fun addDiscovered(node: DiscoveredNodeDTO) { added += node }
        }))
        openFilters()
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
        chooseMapOption(R.string.l10n_app_map_map_controls_centerall)
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
        openMapOptions()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_centerall)).assertIsEnabled()
        clickMenuItem(text(R.string.l10n_app_map_map_controls_centerall))
        waitUntilAdvancingFrames {
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
        openMapOptions()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_centerall)).assertIsNotEnabled()
        // Pick an enabled entry so the popup closes instead of leaking into the next test's window list.
        clickMenuItem(text(R.string.l10n_app_map_map_controls_refresh))
    }

    @Test fun northLockChangesMountedPresentationAndRestoresFromProcessPreferences() {
        var latest: MapPresentationState? = null
        val restoration = restorationTester()
        mount(
            MapFeatureDependencies(source { content() }, offline()),
            onPresentation = { latest = it },
            restoration = restoration,
        )
        assertFalse(requireNotNull(latest).northLocked)
        openMapOptions()
        compose.onNodeWithTag("map:north-lock").assertIsOff()
        clickMenuItem(text(R.string.l10n_app_map_map_controls_locknorth))
        waitUntilAdvancingFrames { requireNotNull(latest).northLocked }
        restoration.emulateSavedInstanceStateRestore()
        advanceFrames()
        waitUntilAdvancingFrames { requireNotNull(latest).northLocked }
        openMapOptions()
        compose.onNodeWithTag("map:north-lock").assertIsOn()
        clickMenuItem(text(R.string.l10n_app_map_map_controls_locknorth))
        waitUntilAdvancingFrames { !requireNotNull(latest).northLocked }
    }

    @Test fun locationFixCentersWithoutReplacingZoomAndPanClearsIndicator() {
        val location = FakeMapLocation(MapLocationAuthorization.NOT_DETERMINED, permissionGranted = true)
        val saved = MapCamera(GeoPoint(51.0, 0.0), 0.5, 0.4, MapCameraPose(8.0, 20.0, 0.0))
        host.get().getSharedPreferences("shared-map", 0).edit().putString("camera-v1", saved.encode()).commit()
        var latest: MapPresentationState? = null
        lateinit var reportCamera: (MapCamera) -> Unit
        lateinit var reportMovement: (Boolean) -> Unit
        mount(MapFeatureDependencies(source { content() }, offline(), location = location),
            onPresentation = { latest = it }, onCameraCallback = { reportCamera = it },
            onMovementCallback = { reportMovement = it })
        compose.onNodeWithTag("map:center-user").performClick()
        waitUntilAdvancingFrames { location.requests == 1 }
        compose.onNodeWithTag("map:center-user").assertIsNotEnabled()
        val fix = GeoPoint(40.0, -70.0)
        location.fix.complete(fix)
        waitUntilAdvancingFrames { latest?.camera?.center == fix }
        assertEquals(saved.copy(center = fix), requireNotNull(latest).camera)
        compose.onNodeWithTag("map:center-user").assertIsSelected()
        compose.runOnIdle { reportMovement(true) }
        compose.onNodeWithTag("map:center-user").assertIsNotSelected()
        compose.runOnIdle { reportCamera(saved.copy(center = fix)) }
        compose.onNodeWithTag("map:center-user").assertIsSelected()
        compose.runOnIdle { reportCamera(saved) }
        compose.onNodeWithTag("map:center-user").assertIsNotSelected()
        compose.runOnIdle { reportCamera(saved.copy(center = fix)) }
        compose.onNodeWithTag("map:center-user").assertIsSelected()
        compose.runOnIdle { location.status.value = MapLocationAuthorization.DENIED }
        waitUntilAdvancingFrames { compose.onAllNodesWithTag("map:center-user").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("map:center-user").assertIsNotSelected()
        chooseMapOption(R.string.l10n_app_map_map_controls_centerall)
        waitUntilAdvancingFrames { latest?.camera == content().markers.map { it.marker.position }.take(1).boundingCamera() }
    }

    @Test fun layerMenuUsesProviderNetworkAndOfflineCoverageWithoutChangingRenderedStyle() {
        val network = MutableStateFlow(MapNetworkStatus.ONLINE)
        val savedPacks = MutableStateFlow<List<OfflinePack>>(emptyList())
        val backend = object : OfflineMapBackend {
            override val packs = savedPacks
            override suspend fun createAndStart(request: OfflinePackRequest) = error("No download")
            override suspend fun delete(packId: UUID) = error("No delete")
            override suspend fun pause(packId: UUID) = error("No pause")
            override suspend fun resume(packId: UUID) = error("No resume")
        }
        var latest: MapPresentationState? = null
        lateinit var reportViewport: (MapViewport?) -> Unit
        val controller = OfflineMapController(backend, emptyMap(), { 0 }, { false })
        mount(
            MapFeatureDependencies(source { content() }, controller, network = network),
            onPresentation = { latest = it },
            onViewportCallback = { reportViewport = it },
        )
        compose.onNodeWithTag("map:layer-menu").performClick()
        settleAnimations()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsEnabled()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_satellite)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_topo)).assertIsNotEnabled()
        compose.onAllNodesWithText(text(R.string.map_style_provider_unavailable)).assertCountEquals(2)
        compose.runOnIdle { network.value = MapNetworkStatus.OFFLINE }
        waitUntilAdvancingFrames {
            compose.onAllNodesWithText(text(R.string.map_offline_coverage_unconfirmed))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsNotEnabled()
        val complete = OfflinePack(
            UUID(0, 100), "Saved", OfflineLayer.BASE,
            GeoBounds(GeoPoint(36.0, -123.0), GeoPoint(38.0, -121.0)),
            10, 14, Instant.EPOCH, OfflinePackState.COMPLETE, 1f,
        )
        compose.runOnIdle { savedPacks.value = listOf(complete) }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsNotEnabled()
        val partial = MapViewport(GeoBounds(GeoPoint(37.0, -122.0), GeoPoint(39.0, -120.0)), 12.0)
        compose.runOnIdle { reportViewport(partial) }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsNotEnabled()
        val covered = MapViewport(GeoBounds(GeoPoint(36.5, -122.5), GeoPoint(37.5, -121.5)), 12.0)
        compose.runOnIdle { reportViewport(covered.copy(zoom = 15.0)) }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsNotEnabled()
        compose.runOnIdle { reportViewport(covered.copy(zoom = 9.0)) }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsNotEnabled()
        compose.runOnIdle {
            savedPacks.value = listOf(complete.copy(state = OfflinePackState.PAUSED))
            reportViewport(covered)
        }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsNotEnabled()
        compose.runOnIdle { savedPacks.value = listOf(complete) }
        waitUntilAdvancingFrames {
            compose.onAllNodesWithText(text(R.string.map_offline_coverage_unconfirmed))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsEnabled()
        compose.runOnIdle { reportViewport(null) }
        compose.onNodeWithText(text(R.string.map_offline_coverage_unconfirmed)).assertExists()
        compose.onNodeWithText(text(R.string.l10n_app_map_map_style_standard)).assertIsNotEnabled()
        assertEquals(MapStyle.STANDARD, requireNotNull(latest).style)
    }

    @Test fun locationPermissionDenialRevocationAndNoFixDoNotMoveCamera() {
        val location = FakeMapLocation(MapLocationAuthorization.NOT_DETERMINED)
        var latest: MapPresentationState? = null
        mount(MapFeatureDependencies(source { content() }, offline(), location = location), onPresentation = { latest = it })
        val initial = requireNotNull(latest).camera
        compose.onNodeWithTag("map:center-user").performClick()
        compose.onNodeWithText(text(R.string.map_location_permission_denied)).assertExists()
        assertEquals(0, location.requests)
        assertEquals(initial, requireNotNull(latest).camera)
        compose.onNodeWithText(text(R.string.l10n_app_map_map_common_done)).performClick()
        compose.runOnIdle { location.status.value = MapLocationAuthorization.AUTHORIZED }
        location.fix.completeExceptionally(IllegalStateException("No fix"))
        compose.onNodeWithTag("map:center-user").performClick()
        waitUntilAdvancingFrames { location.requests == 1 }
        compose.onNodeWithText(text(R.string.l10n_app_contacts_contacts_list_locationunavailable)).assertExists()
        assertEquals(initial, requireNotNull(latest).camera)
        compose.onNodeWithText(text(R.string.l10n_app_map_map_common_done)).performClick()
        compose.runOnIdle { location.status.value = MapLocationAuthorization.DENIED }
        compose.onNodeWithTag("map:center-user").performClick()
        compose.onNodeWithText(text(R.string.map_location_permission_denied)).assertExists()
        assertEquals(1, location.requests)
        assertEquals(initial, requireNotNull(latest).camera)
    }

    @Test fun delayedLocationFixDoesNotOverrideNewerCenterAll() {
        val location = FakeMapLocation(MapLocationAuthorization.AUTHORIZED)
        var latest: MapPresentationState? = null
        mount(MapFeatureDependencies(source { content() }, offline(), location = location), onPresentation = { latest = it })
        compose.onNodeWithTag("map:center-user").performClick()
        waitUntilAdvancingFrames { location.requests == 1 }
        val revision = requireNotNull(latest).cameraCommandRevision
        chooseMapOption(R.string.l10n_app_map_map_controls_centerall)
        waitUntilAdvancingFrames { requireNotNull(latest).cameraCommandRevision > revision }
        val centered = requireNotNull(latest).camera
        compose.runOnIdle { location.fix.complete(GeoPoint(40.0, -70.0)) }
        waitUntilAdvancingFrames { compose.onAllNodesWithTag("map:center-user").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText(text(R.string.l10n_app_settings_radio_presetlocation_usemylocation_locating)).fetchSemanticsNodes().isEmpty() }
        assertEquals(centered, requireNotNull(latest).camera)
        compose.onNodeWithTag("map:center-user").assertIsNotSelected()
    }

    @Test fun delayedLocationFixDoesNotOverrideNewerFocus() {
        val location = FakeMapLocation(MapLocationAuthorization.AUTHORIZED)
        val focus = mutableStateOf<GeoPoint?>(null)
        var latest: MapPresentationState? = null
        mount(MapFeatureDependencies(source { content() }, offline(), location = location),
            focus = { focus.value }, onPresentation = { latest = it })
        compose.onNodeWithTag("map:center-user").performClick()
        waitUntilAdvancingFrames { location.requests == 1 }
        val revision = requireNotNull(latest).cameraCommandRevision
        val selected = GeoPoint(12.0, 24.0)
        compose.runOnIdle { focus.value = selected }
        compose.waitForIdle()
        waitUntilAdvancingFrames(description = { "focus=${focus.value}, revision=$revision, latest=$latest" }) {
            latest?.camera?.center == selected && requireNotNull(latest).cameraCommandRevision > revision
        }
        compose.runOnIdle { focus.value = null }
        compose.runOnIdle { location.fix.complete(GeoPoint(40.0, -70.0)) }
        waitUntilAdvancingFrames { compose.onAllNodesWithText(text(R.string.l10n_app_settings_radio_presetlocation_usemylocation_locating)).fetchSemanticsNodes().isEmpty() }
        assertEquals(selected, requireNotNull(latest).camera?.center)
        compose.onNodeWithTag("map:center-user").assertIsNotSelected()
    }

    @Test fun delayedLocationFixDoesNotOverrideUserPan() {
        val location = FakeMapLocation(MapLocationAuthorization.AUTHORIZED)
        var latest: MapPresentationState? = null
        lateinit var reportCamera: (MapCamera) -> Unit
        lateinit var reportMovement: (Boolean) -> Unit
        mount(MapFeatureDependencies(source { content() }, offline(), location = location),
            onPresentation = { latest = it }, onCameraCallback = { reportCamera = it },
            onMovementCallback = { reportMovement = it })
        compose.onNodeWithTag("map:center-user").performClick()
        waitUntilAdvancingFrames { location.requests == 1 }
        val pan = MapCamera(GeoPoint(20.0, 30.0), 0.4, 0.5)
        compose.runOnIdle {
            reportMovement(true)
            reportCamera(pan)
            location.fix.complete(GeoPoint(40.0, -70.0))
        }
        waitUntilAdvancingFrames { compose.onAllNodesWithText(text(R.string.l10n_app_settings_radio_presetlocation_usemylocation_locating)).fetchSemanticsNodes().isEmpty() }
        assertEquals(pan, requireNotNull(latest).camera)
        compose.onNodeWithTag("map:center-user").assertIsNotSelected()
    }

    @Test fun delayedPermissionGrantDoesNotOverrideNewerFocus() {
        val location = FakeMapLocation(MapLocationAuthorization.NOT_DETERMINED, delayPermission = true)
        val focus = mutableStateOf<GeoPoint?>(null)
        var latest: MapPresentationState? = null
        mount(MapFeatureDependencies(source { content() }, offline(), location = location),
            focus = { focus.value }, onPresentation = { latest = it })
        compose.onNodeWithTag("map:center-user").performClick()
        val revision = requireNotNull(latest).cameraCommandRevision
        val selected = GeoPoint(12.0, 24.0)
        compose.runOnIdle { focus.value = selected }
        compose.waitForIdle()
        waitUntilAdvancingFrames(description = { "focus=${focus.value}, revision=$revision, latest=$latest" }) {
            latest?.camera?.center == selected && requireNotNull(latest).cameraCommandRevision > revision
        }
        compose.runOnIdle { focus.value = null }
        compose.runOnIdle { location.grantPermission() }
        compose.waitForIdle()
        assertEquals(0, location.requests)
        assertEquals(selected, requireNotNull(latest).camera?.center)
        compose.onNodeWithTag("map:center-user").assertIsNotSelected()
    }

    @Test fun delayedLocationFixDoesNotOverrideClusterZoom() {
        val location = FakeMapLocation(MapLocationAuthorization.AUTHORIZED)
        var latest: MapPresentationState? = null
        val clusterCamera = MapCamera(GeoPoint(37.0, -122.0), 0.1, 0.1, MapCameraPose(14.0, 0.0, 0.0))
        mount(MapFeatureDependencies(source { content() }, offline(), location = location),
            onPresentation = { latest = it }, testClusterCamera = clusterCamera)
        compose.onNodeWithTag("map:center-user").performClick()
        waitUntilAdvancingFrames { location.requests == 1 }
        compose.onNodeWithTag("test-cluster").performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle { location.fix.complete(GeoPoint(40.0, -70.0)) }
        waitUntilAdvancingFrames { compose.onAllNodesWithText(text(R.string.l10n_app_settings_radio_presetlocation_usemylocation_locating)).fetchSemanticsNodes().isEmpty() }
        assertEquals(clusterCamera, requireNotNull(latest).camera)
        compose.onNodeWithTag("map:center-user").assertIsNotSelected()
    }

    @Test fun programmaticMapMovementDoesNotCancelPendingLocationFix() {
        val location = FakeMapLocation(MapLocationAuthorization.AUTHORIZED)
        var latest: MapPresentationState? = null
        lateinit var reportMovement: (Boolean) -> Unit
        mount(MapFeatureDependencies(source { content() }, offline(), location = location),
            onPresentation = { latest = it }, onMovementCallback = { reportMovement = it })
        compose.onNodeWithTag("map:center-user").performClick()
        waitUntilAdvancingFrames { location.requests == 1 }
        val fix = GeoPoint(40.0, -70.0)
        compose.runOnIdle {
            reportMovement(false)
            location.fix.complete(fix)
        }
        waitUntilAdvancingFrames { latest?.camera?.center == fix }
    }

    @Test fun revocationWhileLocationIsPendingDoesNotMoveTheCamera() {
        val location = FakeMapLocation(MapLocationAuthorization.AUTHORIZED)
        var latest: MapPresentationState? = null
        mount(MapFeatureDependencies(source { content() }, offline(), location = location), onPresentation = { latest = it })
        val initial = requireNotNull(latest).camera
        compose.onNodeWithTag("map:center-user").performClick()
        waitUntilAdvancingFrames { location.requests == 1 }
        compose.runOnIdle {
            location.status.value = MapLocationAuthorization.DENIED
            location.fix.complete(GeoPoint(40.0, -70.0))
        }
        waitUntilAdvancingFrames {
            compose.onAllNodesWithText(text(R.string.map_location_permission_denied)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(initial, requireNotNull(latest).camera)
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
        chooseMapOption(R.string.l10n_app_settings_offlinemaps_title)
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
        openFilters()
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
        compose.onNodeWithTag("map:options-menu").assertDoesNotExist()
        release.complete(Unit)
        waitUntilAdvancingFrames {
            compose.onAllNodesWithTag("map:options-menu").fetchSemanticsNodes().isNotEmpty() &&
                latest?.isDark == true && latest?.points?.none { it.id == contact.id } == true
        }
        assertEquals(1, loads)
        assertEquals(1, consumed)
        assertEquals(target, requireNotNull(latest).points.single { it.style == MapPinStyle.DROPPED_PIN }.position)
        assertEquals(target, requireNotNull(latest).camera?.center)
        assertTrue(runBlocking { real.observe(MapFilterHost.MAIN_MAP).first() }.filter.favoritesOnly)
    }

    @Test fun failedPreferenceWriteRetriesBeforeLaterChangesAndClearsTheError() {
        val real = AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences,
            host.get().getSharedPreferences("shared-map", 0),
        )
        var attempts = 0
        val store = object : MapPreferenceStore by real {
            override suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState) {
                if (++attempts == 1) throw IOException("Fixture preference write failure")
                real.updateFilter(host, transform)
            }
        }
        mount(MapFeatureDependencies(source { content() }, offline(), preferences = store))
        openFilters()
        compose.onNodeWithText(text(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("map:preference-retry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(text(R.string.l10n_app_map_map_controls_showlabels))
            .performSemanticsAction(SemanticsActions.OnClick)
        assertTrue(runBlocking { real.observe(MapFilterHost.MAIN_MAP).first().labels })
        compose.onNodeWithTag("map:preference-retry").performClick()
        compose.waitUntil(5_000) {
            val saved = runBlocking { real.observe(MapFilterHost.MAIN_MAP).first() }
            saved.filter.favoritesOnly && !saved.labels
        }
        compose.onNodeWithTag("map:preference-retry").assertDoesNotExist()
        assertEquals(2, attempts)
    }

    @Test fun switchingPreferenceStoresClearsTheOldFailureAndAllowsNewWrites() {
        val real = AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences,
            host.get().getSharedPreferences("shared-map", 0),
        )
        val failing = object : MapPreferenceStore by real {
            override suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState) {
                throw IOException("Fixture preference write failure")
            }
        }
        val initial = MapFeatureDependencies(source { content() }, offline(), preferences = failing)
        val dependencies = mutableStateOf(initial)
        mount(initial, dependenciesProvider = { dependencies.value })
        openFilters()
        compose.onNodeWithText(text(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)).performClick()
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("map:preference-retry").fetchSemanticsNodes().isNotEmpty()
        }
        compose.runOnIdle { dependencies.value = initial.copy(preferences = real) }
        compose.waitUntil(5_000) {
            compose.onAllNodesWithTag("map:preference-retry").fetchSemanticsNodes().isEmpty() &&
                compose.onAllNodesWithTag("map:options-menu").fetchSemanticsNodes().isNotEmpty()
        }
        if (compose.onAllNodesWithText(text(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites))
                .fetchSemanticsNodes().isEmpty()) openFilters()
        compose.onNodeWithText(text(R.string.l10n_app_contacts_contacts_pathedit_filter_favorites)).performClick()
        compose.waitUntil(5_000) { runBlocking { real.observe(MapFilterHost.MAIN_MAP).first().filter.favoritesOnly } }
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
        openFilters()
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
        onViewportCallback: ((MapViewport?) -> Unit) -> Unit = {},
        onMovementCallback: ((Boolean) -> Unit) -> Unit = {},
        testClusterCamera: MapCamera? = null,
        onMapDispose: () -> Unit = {},
        showMap: () -> Boolean = { true },
        awaitReady: Boolean = true,
    ) {
        val bound = dependencies.copy(preferences = dependencies.preferences ?: AppMapPreferences(
            MeshCoreStorage.get(host.get()).preferences,
            host.get().getSharedPreferences("shared-map", 0),
        ))
        host.get().runOnUiThread {
            val content: @Composable () -> Unit = {
                MeshCoreTheme(colorSchemePreference = appColorSchemePreference, systemDark = false, motionScale = 0f) {
                    if (showMap()) MapEntry(
                        FeatureRoute(FeatureId.MAP), { error("No generic navigation") },
                        dependenciesProvider?.invoke() ?: bound,
                        focus = focus(),
                        onFocusConsumed = onFocusConsumed,
                        mapSurface = { state, _, _, camera, select, viewport, moving, overlay ->
                            SideEffect { onPresentation(state); onCameraCallback(camera); onViewportCallback(viewport); onMovementCallback(moving) }
                            DisposableEffect(Unit) { onDispose { onMapDispose() } }
                            Box(Modifier.fillMaxSize()) {
                                Column(Modifier.align(androidx.compose.ui.Alignment.Center).testTag("test-renderer")) {
                                    if (testClusterCamera != null) {
                                        TextButton({
                                            moving(true)
                                            camera(testClusterCamera)
                                        }, Modifier.testTag("test-cluster")) { Text("Cluster") }
                                    }
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
                compose.onAllNodesWithTag("map:options-menu")
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

    private fun waitUntilAdvancingFrames(timeoutMillis: Long = 5_000, description: () -> String = { "" }, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        while (System.nanoTime() < deadline) {
            advanceFrames()
            if (condition()) return
            Thread.sleep(10)
        }
        error("Condition was not satisfied while advancing the mounted map frame clock: ${description()}")
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
    private class FakeMapLocation(
        initial: MapLocationAuthorization,
        private val permissionGranted: Boolean = false,
        private val delayPermission: Boolean = false,
    ) : MapLocationPort {
        val status = MutableStateFlow(initial)
        override val authorization = status
        val fix = CompletableDeferred<GeoPoint>()
        var requests = 0
        private var pendingPermission: ((Boolean) -> Unit)? = null
        override fun refreshAuthorization() = status.value
        @Composable override fun rememberPermissionRequester(onResult: (Boolean) -> Unit): () -> Unit =
            {
                if (delayPermission) pendingPermission = onResult
                else {
                    status.value = if (permissionGranted) MapLocationAuthorization.AUTHORIZED else MapLocationAuthorization.DENIED
                    onResult(permissionGranted)
                }
            }
        fun grantPermission() {
            status.value = MapLocationAuthorization.AUTHORIZED
            requireNotNull(pendingPermission).invoke(true)
            pendingPermission = null
        }
        override suspend fun requestLocation(): GeoPoint {
            requests++
            return fix.await()
        }
    }
    private fun source(load: suspend () -> MapFeatureContent) = MapFeatureDataSource { load() }
    private fun offline() = OfflineMapController(
        UnavailableOfflineMapBackend, emptyMap(), { error("No download") }, { error("No download") },
    )
    private fun text(id: Int) = host.get().getString(id)

    /** Node filters live in the collapsed filter panel; open it before touching them. */
    private fun openFilters() {
        compose.onNodeWithTag("map:filters-toggle").performClick()
        settleAnimations()
    }

    /** Center/refresh/offline/list live in the "Map options" menu. */
    private fun chooseMapOption(id: Int) {
        openMapOptions()
        clickMenuItem(text(id))
    }

    /** Menu items live in a popup window; invoke their click action instead of relying on popup hit-testing. */
    private fun clickMenuItem(label: String) {
        compose.onNodeWithText(label).performSemanticsAction(SemanticsActions.OnClick)
        compose.waitForIdle()
    }

    private fun openMapOptions() {
        compose.onNodeWithTag("map:options-menu").performClick()
        settleAnimations()
    }

    /** Menu and panel enter animations run on the frame clock; let them finish before touching their content. */
    private fun settleAnimations() {
        repeat(4) { advanceFrames() }
        compose.waitForIdle()
    }
}
