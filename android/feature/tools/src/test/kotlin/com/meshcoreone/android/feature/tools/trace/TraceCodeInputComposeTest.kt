// AndroidOnly: WP-314 TS-09 Mounted code/paste actions use the production parser and width policy.
// AndroidOnly: WP-314 TS-11 Mounted path editing controls drive the real state holder.
// AndroidOnly: WP-314 TS-13 Mounted Save/name actions persist through the real holder.
// AndroidOnly: WP-314 TS-14 Mounted comparisons consume actual saved history and trace responses.
// AndroidOnly: WP-314 TS-15 Mounted distance info exposes calculated and unavailable explanations.
// AndroidOnly: WP-314 TS-19 Mounted hash-width actions respect the live radio's firmware capability.
// AndroidOnly: WP-314 TS-12 Mounted failure recovery copies the captured attempted path.
// AndroidOnly: WP-314 TS-10 Mounted trace errors use localized alerts and rejection feedback.
// AndroidOnly: WP-314 TS-17 Mounted room and repeater selections use live trace contacts.
// AndroidOnly: WP-314 TS-18 Mounted saved-path selection exposes the full restored path.
// AndroidOnly: WP-314 Mounted saved-path storage failures remain visible and recoverable.
// AndroidOnly: WP-314 Saved-path deletion requires a connection-scoped confirmation.
// AndroidOnly: WP-314 Mounted trace-map controls expose real preferences and trace state.
package com.meshcoreone.android.feature.tools.trace

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meshcoreone.android.core.maps.MapColorPreference
import com.meshcoreone.android.core.maps.MapFilterHost
import com.meshcoreone.android.core.maps.MapFilterState
import com.meshcoreone.android.core.maps.MapMarker
import com.meshcoreone.android.core.maps.MapPinStyle
import com.meshcoreone.android.core.maps.MapPreferenceStore
import com.meshcoreone.android.core.maps.MapPreferences
import com.meshcoreone.android.core.maps.semanticText
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.TracePathRunDTO
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.TraceNode
import java.time.Duration
import java.text.DateFormat
import java.text.FieldPosition
import java.text.ParsePosition
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TraceCodeInputComposeTest {
    private lateinit var host: ActivityController<TraceCodeInputTestActivity>
    private val content get() = host.get().content
    private val frames get() = host.get().frames
    private var frameNanos = 0L

    @Before fun setup() = attachHost()

    @After fun dispose() = destroyHost()

    @Test fun liveTraceInputSelectsLoadedRoomsAndStillSelectsRepeaters() {
        val harness = TraceHarness(configureDependencies = true)
        harness.deps.device = device()
        harness.deps.directory = FakeNodeDirectory(
            contacts = listOf(
                contact(0xB2, name = "Room Server", type = com.meshcoreone.android.core.protocol.model.ContactType.ROOM),
                contact(0xA1, name = "Repeater"),
            ),
        )
        harness.holder.bindConnection(RADIO, 1)
        harness.complete { harness.holder.loadContacts(RADIO) }
        assertEquals(listOf("Repeater"), harness.state.availableRepeaters.map { it.name })
        assertEquals(listOf("Room Server"), harness.state.availableRooms.map { it.name })

        mountPathList(harness)
        scrollToIndex(7)
        click("Room Server")
        assertEquals(listOf("Room Server"), harness.state.outboundPath.map { it.resolvedName })

        scrollToIndex(6)
        click("Repeater")
        assertEquals(listOf("Room Server", "Repeater"), harness.state.outboundPath.map { it.resolvedName })
    }

    @Test fun unsupportedFirmwareDisablesWidthChoicesWithoutChangingThePathOrResult() {
        for ((version, text) in listOf(7u.toUByte() to "v1.9.0", 8u.toUByte() to "v1.10.0")) {
            val harness = configured()
            harness.deps.device = device(firmwareVersion = version, firmwareVersionString = text)
            harness.holder.addNode(contact(0xAA, name = "Tower"))
            harness.holder.editStateForTesting {
                it.copy(activeSavedPath = savedPath(Bytes.of(0xAA)), result = result(emptyList()))
            }
            val before = harness.state
            mountPathList(harness)
            scrollToIndex(8)
            for (label in listOf("1 B", "2 B", "4 B")) {
                val node = button(label)
                assertTrue(node.config.contains(SemanticsProperties.Disabled), "$text must disable $label")
                val action = assertNotNull(node.config[SemanticsActions.OnClick].action)
                host.get().runOnUiThread { action() }
                settle()
                assertEquals(before, harness.state, "$text must not change the trace")
            }
        }
    }

    @Test fun supportedFirmwareAllowsWidthChoicesAndRetainedActionsRecheckLiveCapability() {
        val harness = configured()
        harness.holder.addNode(contact(0xAA, name = "Tower"))
        mountPathList(harness)
        scrollToIndex(8)
        click("2 B")
        assertEquals(1u.toUByte(), harness.state.traceHashMode)
        assertEquals(Bytes.of(0xAA, 0x00), harness.state.outboundPath.single().hashBytes)

        val retained = assertNotNull(button("4 B").config[SemanticsActions.OnClick].action)
        harness.holder.editStateForTesting {
            it.copy(activeSavedPath = savedPath(Bytes.of(0xAA, 0x00)), result = result(emptyList()))
        }
        val before = harness.state
        host.get().runOnUiThread {
            harness.deps.device = device(firmwareVersionString = "v1.10.0")
            retained()
        }
        settle()
        assertEquals(before, harness.state, "An old enabled action cannot alter an unsupported radio")

        val newerFirmware = configured()
        newerFirmware.deps.device = device(firmwareVersionString = "v1.12.0")
        newerFirmware.holder.addNode(contact(0xAA, name = "Tower"))
        newerFirmware.holder.editStateForTesting {
            it.copy(activeSavedPath = savedPath(Bytes.of(0xAA)), result = result(emptyList()))
        }
        mountPathList(newerFirmware)
        scrollToIndex(8)
        click("4 B")
        assertEquals(2u.toUByte(), newerFirmware.state.traceHashMode)
        assertEquals(Bytes.of(0xAA, 0x00, 0x00, 0x00), newerFirmware.state.outboundPath.single().hashBytes)
        assertNull(newerFirmware.state.activeSavedPath)
        assertNull(newerFirmware.state.result)
    }

    @Test fun missingMapPreferencesShowAnErrorAndLeaveListNavigationUsable() {
        val h = configured()
        content.setContent {
            MaterialTheme {
                TracePathScreen(
                    TracePathScreen.Dependencies(h.deps, h.recents, h.diagnostics, RADIO, 1),
                    mapSurface = host.get().mapSurface,
                )
            }
        }
        settle()
        click(host.get().getString(C.contactsTraceModeMap))
        assertTrue(texts().contains(host.get().getString(L.commonErrorFailedToLoad)))
        click(host.get().getString(C.contactsTraceModeList))
        assertTrue(texts().contains(host.get().getString(C.contactsTraceListRunTrace)))
    }

    @Test fun mapClusteringControlPersistsAndUpdatesTheMountedSurfaceAtLargeFont() {
        for (fontScale in listOf(1f, 2f)) {
            val h = connectedMapHarness()
            val preferences = TraceCodeInputMapPreferences()
            var renderedClustering: Boolean? = null
            val surface: TraceMapSurface = { _, clustering, _, _, _, overlay ->
                SideEffect { renderedClustering = clustering }
                Box(Modifier.fillMaxSize(), content = overlay)
            }
            mountPathMap(h, preferences, fontScale, surface)
            val label = host.get().getString(M.mapControlsClusterNodes)
            assertTrue(preferences.observe(MapFilterHost.TRACE_PATH).value.clustering)
            assertEquals(true, renderedClustering)
            assertTrue(button(label).boundsInRoot.width > 0f)

            click(label)
            assertFalse(preferences.observe(MapFilterHost.TRACE_PATH).value.clustering)
            assertEquals(false, renderedClustering)
            click(label)
            assertTrue(preferences.observe(MapFilterHost.TRACE_PATH).value.clustering)
            assertEquals(true, renderedClustering)
        }
    }

    @Test fun mapLabelControlKeepsContactAndDiscoveredNamesAccessible() {
        val h = connectedMapHarness()
        val contact = contact(0xAA, name = "North", latitude = 38.0, longitude = -122.0)
            .copy(nickname = "Summit")
        val discovered = discovered(key(0xBB), "Hill", latitude = 37.5, longitude = -121.5)
        h.holder.setContactsForTesting(listOf(contact))
        h.holder.editStateForTesting { it.copy(discoveredRepeaters = listOf(discovered)) }
        h.holder.addNode(contact)
        val preferences = TraceCodeInputMapPreferences()
        val renderedMarkers = AtomicReference<List<MapMarker>>(emptyList())
        val surface: TraceMapSurface = { presentation, _, _, _, _, overlay ->
            SideEffect { renderedMarkers.set(presentation.points) }
            Box(Modifier.fillMaxSize(), content = overlay)
        }
        mountPathMap(h, preferences, surface = surface)

        fun markers() = renderedMarkers.get().associateBy { it.id }
        assertEquals("Summit", markers().getValue(contact.id).label)
        assertEquals("Hill", markers().getValue(discovered.id).label)
        assertEquals("Summit, 1", markers().getValue(contact.id).semanticText)
        assertEquals("Hill", markers().getValue(discovered.id).semanticText)

        val label = host.get().getString(M.mapControlsShowLabels)
        click(label)
        assertFalse(preferences.observe(MapFilterHost.TRACE_PATH).value.labels)
        assertNull(markers().getValue(contact.id).label)
        assertNull(markers().getValue(discovered.id).label)
        assertEquals("Summit, 1", markers().getValue(contact.id).semanticText)
        assertEquals("Hill", markers().getValue(discovered.id).semanticText)

        click(label)
        assertTrue(preferences.observe(MapFilterHost.TRACE_PATH).value.labels)
        assertEquals("Summit", markers().getValue(contact.id).label)
        assertEquals("Hill", markers().getValue(discovered.id).label)
    }

    @Test fun clusteringWriteFailureIsVisibleAndCanBeRetried() {
        val h = connectedMapHarness()
        val preferences = TraceCodeInputMapPreferences().apply {
            clusteringFailure = IllegalStateException("preference write failed")
        }
        mountPathMap(h, preferences)
        val label = host.get().getString(M.mapControlsClusterNodes)
        click(label)
        assertTrue(preferences.observe(MapFilterHost.TRACE_PATH).value.clustering)
        assertTrue(texts().contains(host.get().getString(L.commonErrorFailedToLoad)))
        assertEquals("trace-map-write-clustering", h.diagnostics.failures.single().first)

        preferences.clusteringFailure = null
        click(host.get().getString(M.mapPreviewRetryButtonAccessibilityLabel))
        click(label)
        assertFalse(preferences.observe(MapFilterHost.TRACE_PATH).value.clustering)
    }

    @Test fun rapidAndRetainedMapRunActionsDoNotReplaceAnActiveTrace() {
        val h = connectedMapHarness()
        val sender = FakeTraceSender()
        h.deps.sender = sender
        h.holder.addNode(contact(0xAA, name = "Tower"))
        mountPathMap(h)
        val runLabel = host.get().getString(C.contactsTraceListRunTrace)
        val action = assertNotNull(button(runLabel).config[SemanticsActions.OnClick].action)
        host.get().runOnUiThread {
            assertTrue(action())
            assertTrue(action())
        }
        settle()
        h.main.runCurrent()
        settle()
        val tag = sender.sent.single().tag
        assertEquals(tag, h.holder.pendingTag)
        assertTrue(h.state.isRunning)
        val running = actionButton(host.get().getString(C.contactsTraceListRunningLabel))
        assertTrue(running.config.contains(SemanticsProperties.Disabled))
        assertEquals(LiveRegionMode.Polite, running.config[SemanticsProperties.LiveRegion])
        assertTrue(texts().contains(host.get().getString(C.contactsTraceListRunningTrace)))

        host.get().runOnUiThread { assertTrue(action()) }
        settle()
        assertEquals(1, sender.sent.size)
        assertEquals(tag, h.holder.pendingTag)
        h.respond(tag, listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO)
        settle()
        click(runLabel)
        h.main.runCurrent()
        assertEquals(2, sender.sent.size)
    }

    @Test fun failedMapRunShowsTheFailureAndAllowsAnotherAttempt() {
        val h = connectedMapHarness()
        val sender = FakeTraceSender(failure = IllegalStateException("radio send failed"))
        h.deps.sender = sender
        h.holder.addNode(contact(0xAA, name = "Tower"))
        mountPathMap(h)
        val runLabel = host.get().getString(C.contactsTraceListRunTrace)

        click(runLabel)
        h.main.runCurrent()
        settle()
        assertFalse(h.state.isRunning)
        assertEquals(EnglishTraceStrings.sendFailed, h.state.errorMessage)
        assertEquals("sendTrace", h.diagnostics.failures.single().first)
        click(host.get().getString(C.contactsTraceMapViewResults))
        assertTrue(texts().contains(EnglishTraceStrings.sendFailed))
        assertTrue(texts().contains("AA"))
        click(host.get().getString(C.contactsTraceListCopyPath))
        assertEquals("AA", clipboardText())
        click(host.get().getString(L.commonDone))

        sender.failure = null
        click(runLabel)
        h.main.runCurrent()
        assertEquals(2, sender.sent.size)
        assertTrue(h.state.isRunning)
    }

    @Test fun mapBatchSelectionRunsTheChosenCount() {
        val h = connectedMapHarness()
        val sender = FakeTraceSender(failure = IllegalStateException("no response"))
        h.deps.sender = sender
        h.holder.addNode(contact(0xAA, name = "Tower"))
        h.holder.setBatchEnabled(true)
        mountPathMap(h)
        click("5")
        assertEquals(5, h.state.batchSize)
        click(host.get().getString(C.contactsTraceListRunTrace))
        repeat(4) { h.time.advanceBy(500.milliseconds); settle() }
        assertEquals(5, sender.sent.size)
        assertEquals(5, h.state.completedResults.size)
        assertFalse(h.state.isRunning)
    }

    @Test fun batchSwitchIsNamedAndOperableInListAndMap() {
        for (map in listOf(false, true)) {
            val h = connectedMapHarness()
            h.holder.addNode(contact(0xAA, name = "Tower"))
            if (map) mountPathMap(h) else {
                mountPathList(h)
                scrollToIndex(8)
            }
            val label = host.get().getString(C.contactsTraceListBatchTrace)
            assertTrue(actionButton(label).config.contains(SemanticsProperties.ToggleableState))
            clickDescription(label)
            assertTrue(h.state.batchEnabled)
            clickDescription(label)
            assertFalse(h.state.batchEnabled)
        }
    }

    @Test fun changingNextBatchCountPreservesCompletedAndCancelledResultCounts() {
        for (map in listOf(false, true)) for (original in listOf(3, 5)) for (cancelled in listOf(false, true)) {
            val h = connectedMapHarness()
            val sender = FakeTraceSender()
            h.deps.sender = sender
            h.holder.addNode(contact(0xAA, name = "Tower"))
            h.holder.setBatchEnabled(true)
            h.holder.setBatchSize(original)
            h.start { h.holder.runBatchTrace() }
            val completed = if (cancelled) 2 else original
            repeat(completed) { index ->
                h.respond(sender.sent.last().tag, listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO)
                if (index < completed - 1) h.time.advanceBy(500.milliseconds)
            }
            if (cancelled) h.holder.cancelBatchTrace()
            assertFalse(h.state.isRunning)
            if (map) mountPathMap(h) else {
                mountPathList(h)
                scrollToIndex(8)
            }
            val next = if (original == 3) 5 else 3
            click(next.toString())
            assertEquals(next, h.state.batchSize)
            assertEquals(original, h.state.presentedBatchSize)
            if (map) click(host.get().getString(C.contactsTraceMapViewResults)) else scrollToTraceResults(h)
            val expected = if (cancelled) C.contactsResultsBatchSuccess(
                host.get().resources, completed, original, completed * 100 / original,
            ) else C.contactsResultsBatchCompleteLabel(host.get().resources, original, original, 100)
            assertTrue(nodes().any {
                expected in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
            })
            assertTrue(h.state.canSavePath)
            if (map) click(host.get().getString(L.commonDone)) else scrollToIndex(10)
            click(host.get().getString(C.contactsTraceListRunTrace))
            h.main.runCurrent()
            assertEquals(next, h.state.presentedBatchSize)
            assertTrue(h.state.completedResults.isEmpty())
            assertEquals(completed + 1, sender.sent.size)
            h.holder.cancelBatchTrace()
        }
    }

    @Test fun listBatchSelectionAndCurrentPathCopyUseTheRenderedRoute() {
        val h = configured()
        h.holder.addNode(contact(0xAA, name = "Tower"))
        h.holder.setBatchEnabled(true)
        mountPathList(h)
        scrollToIndex(3)
        click(host.get().getString(C.contactsTraceListCopyPath))
        assertEquals("AA", clipboardText())
        scrollToIndex(8)
        click("5")
        assertEquals(5, h.state.batchSize)
        click("3")
        assertEquals(3, h.state.batchSize)
    }

    @Test fun mapSuccessfulResultCopiesAndSavesWithoutLeavingMap() {
        val h = connectedMapHarness()
        val sender = FakeTraceSender()
        val store = InMemoryTracePathStore { h.time.now() }
        h.deps.sender = sender
        h.deps.store = store
        h.holder.addNode(contact(0xAA, name = "Tower"))
        mountPathMap(h)
        click(host.get().getString(C.contactsTraceListCopyPath))
        assertEquals("AA", clipboardText())
        click(host.get().getString(C.contactsTraceListRunTrace))
        h.main.runCurrent()
        h.respond(sender.sent.single().tag, listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO)
        settle()
        click(host.get().getString(C.contactsTraceMapViewResults))
        click(host.get().getString(C.contactsTraceListCopyPath))
        assertEquals("AA", clipboardText())
        click(host.get().getString(C.contactsResultsSavePath))
        setInput("Map route")
        click(host.get().getString(L.commonSave))
        assertEquals("Map route", store.paths.values.single().name)
        assertEquals(Bytes.of(0xAA), store.paths.values.single().pathBytes)
    }

    @Test fun activeBatchSurvivesSwitchingBetweenListAndMap() {
        for (startOnMap in listOf(false, true)) {
            val h = connectedMapHarness()
            val sender = FakeTraceSender()
            h.deps.sender = sender
            h.holder.addNode(contact(0xAA, name = "Tower"))
            h.holder.setBatchEnabled(true)
            val preferences = TraceCodeInputMapPreferences()
            val connection = TracePathScreen.Dependencies(
                h.deps, h.recents, h.diagnostics, RADIO, 1, preferences,
            )
            val saved = SavedPathsStateHolder(object : SavedPathsStrings {
                override val loadFailed = "Load failed"
                override val renameFailed = "Rename failed"
                override val deleteFailed = "Delete failed"
            }, h.diagnostics)
            content.setContent {
                key(h) {
                    val state = h.holder.state.collectAsStateWithLifecycle().value
                    MaterialTheme {
                        TracePathContent(
                            state, h.holder, saved.state.collectAsStateWithLifecycle().value, saved,
                            preferences, null, h.diagnostics, host.get().mapSurface, connection,
                        )
                    }
                }
            }
            settle()
            if (startOnMap) click(host.get().getString(C.contactsTraceModeMap)) else scrollToIndex(10)
            click(host.get().getString(C.contactsTraceListRunTrace))
            h.main.runCurrent()
            settle()
            assertTrue(h.state.isRunning)
            assertEquals(1, sender.sent.size)
            click(host.get().getString(if (startOnMap) C.contactsTraceModeList else C.contactsTraceModeMap))
            settle()
            assertTrue(h.state.isRunning)
            repeat(3) { index ->
                h.respond(sender.sent.last().tag, listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO)
                if (index < 2) h.time.advanceBy(500.milliseconds)
            }
            settle()
            assertEquals(3, sender.sent.size)
            assertEquals(3, h.state.successCount)
            assertTrue(h.state.isBatchComplete)
            assertFalse(h.state.isRunning)
        }
    }

    @Test fun mountedTraceFailureShowsLocalizedDismissibleAlertAndRejectFeedback() {
        val h = configured()
        h.deps.device = device()
        h.deps.directory = FakeNodeDirectory(contacts = listOf(contact(0xAA, name = "Tower")))
        val sender = FakeTraceSender(failure = IllegalStateException("radio send failed"))
        h.deps.sender = sender
        val feedback = mutableListOf<HapticFeedbackType>()
        val connection = TracePathScreen.Dependencies(h.deps, h.recents, h.diagnostics, RADIO, 1)

        content.setContent {
            val recordingHaptics = remember {
                object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
                        feedback += hapticFeedbackType
                    }
                }
            }
            CompositionLocalProvider(LocalHapticFeedback provides recordingHaptics) {
                MaterialTheme {
                    TracePathScreen(connection, mapSurface = host.get().mapSurface)
                }
            }
        }
        settle()
        click("Tower")
        val runLabel = host.get().getString(C.contactsTraceListRunTrace)
        scrollToIndex(10)
        click(runLabel)

        assertEquals(1, sender.sent.size)
        val title = host.get().getString(C.contactsTraceFailed)
        val message = host.get().getString(C.contactsTraceErrorSendFailed)
        assertTrue(texts().contains(title))
        assertTrue(texts().contains(message))
        assertTrue(nodes().any { it.config.contains(SemanticsProperties.IsDialog) })
        assertEquals(listOf(HapticFeedbackType.Reject), feedback)

        click(host.get().getString(C.contactsCommonOk))
        assertFalse(nodes().any { it.config.contains(SemanticsProperties.IsDialog) })
        assertFalse(texts().contains(title))
        assertTrue(texts().contains(message), "The retained failure result remains available after alert dismissal")

        sender.failure = null
        scrollToIndex(10)
        click(runLabel)
        assertEquals(2, sender.sent.size)
    }

    @Test fun listTimeoutCopiesTheCapturedMultibytePathAndRejectsRetiredActions() {
        val h = configured()
        val sender = FakeTraceSender()
        h.deps.sender = sender
        h.holder.setTraceHashMode(1u)
        h.holder.addNode(contact(0xA1, 0x10, name = "First"))
        h.holder.addNode(contact(0xB2, 0x20, name = "Second"))
        h.complete { h.holder.runTrace() }
        assertEquals(Bytes.of(0xA1, 0x10, 0xB2, 0x20, 0xA1, 0x10), sender.sent.single().path)
        mountPathList(h, fontScale = 2f)
        h.time.advanceBy(30.seconds)
        settle()
        scrollToTraceResults(h)
        assertEquals("A110,B220,A110", assertNotNull(h.state.result).tracedPathString)
        assertTrue(texts().contains(EnglishTraceStrings.noResponse))
        assertTrue(texts().contains("A110,B220,A110"))
        val label = host.get().getString(C.contactsTraceListCopyPath)
        val staleAction = assertNotNull(button(label).config[SemanticsActions.OnClick].action)
        click(label)
        assertEquals("A110,B220,A110", clipboardText())

        setClipboardText("unchanged")
        host.get().runOnUiThread {
            h.holder.addNode(contact(0xC3, 0x30, name = "Later edit"))
            assertTrue(staleAction())
        }
        settle()
        assertNull(h.state.displayedResult)
        assertEquals("unchanged", clipboardText())
    }

    @Test fun allFailedBatchShowsTheActualAttemptOnTheMapAndRetiresCopyWithConnection() {
        val h = connectedMapHarness()
        val sender = FakeTraceSender(failure = IllegalStateException("radio send failed"))
        h.deps.sender = sender
        h.holder.setTraceHashMode(2u)
        h.holder.addNode(contact(0xA1, 0x10, 0xB2, 0x20, name = "Tower"))
        h.holder.setBatchEnabled(true)
        h.holder.setBatchSize(1)
        mountPathMap(h)
        h.start { h.holder.runBatchTrace() }
        settle()
        assertEquals(1, h.state.completedResults.size)
        assertEquals("A110B220", assertNotNull(h.state.displayedResult).tracedPathString)
        click(host.get().getString(C.contactsTraceMapViewResults))
        assertTrue(texts().contains(EnglishTraceStrings.sendFailed))
        assertTrue(texts().contains("A110B220"))
        val label = host.get().getString(C.contactsTraceListCopyPath)
        val staleAction = assertNotNull(button(label).config[SemanticsActions.OnClick].action)
        click(label)
        assertEquals("A110B220", clipboardText())

        setClipboardText("unchanged")
        host.get().runOnUiThread {
            h.holder.retireConnection()
            assertTrue(staleAction())
        }
        settle()
        assertEquals("unchanged", clipboardText())
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
    }

    @Test fun emptyAttemptCopiesAnEmptyPathWithoutInventingAReplacement() {
        val h = configured()
        h.holder.editStateForTesting {
            it.copy(result = TraceResult.timeout(Bytes.EMPTY, 2, EnglishTraceStrings.noResponse))
        }
        mountTraceResults(h)
        assertTrue(texts().contains(EnglishTraceStrings.noResponse))
        setClipboardText("previous")
        click(host.get().getString(C.contactsTraceListCopyPath))
        assertEquals("", clipboardText())
    }

    @Test fun mountedTraceDistanceShowsFullValueAndPartialFallbackDetails() {
        val route = listOf(
            hop(0.0, start = true, name = "My Device"),
            hop(5.0, lat = 37.8044, lon = -122.2712, name = "Tower A", hash = Bytes.of(0x3F)),
            hop(4.0, lat = 37.8716, lon = -122.2727, name = "Tower B", hash = Bytes.of(0x4F)),
            hop(0.0, end = true, name = "My Device"),
        )
        val location = Coordinate(37.7749, -122.4194)
        val full = configured()
        val successfulBatchResult = result(route)
        full.holder.editStateForTesting {
            it.copy(
                result = TraceResult.timeout(Bytes.EMPTY, 1, EnglishTraceStrings.noResponse),
                batchEnabled = true,
                batchPresentationActive = true,
                batchSize = 1,
                currentTraceIndex = 1,
                completedResults = listOf(successfulBatchResult),
            )
        }
        mountTraceResults(full, location = location)
        assertEquals(successfulBatchResult.id, full.state.displayedResult?.id)
        val fullDistance = assertNotNull(
            TracePathState(result = successfulBatchResult).withResultLocation(location).totalPathDistance,
        )
        assertTrue(texts().contains(host.get().getString(C.contactsResultsTotalDistance)))
        assertTrue(texts().contains("${"%.0f".format(fullDistance)} m"))
        assertFalse(texts().contains(host.get().getString(C.contactsResultsUnavailable)))
        val fullInfoDescription = "${host.get().getString(C.contactsResultsPartialDistanceLabel)}. " +
            host.get().getString(C.contactsResultsPartialDistanceHint)
        assertFalse(nodes().any {
            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
                .any { description -> description == fullInfoDescription }
        })

        val partial = configured()
        partial.holder.editStateForTesting {
            it.copy(result = result(route.map { hop ->
                if (hop.isStartNode || hop.isEndNode) hop.copy(latitude = null, longitude = null) else hop
            }))
        }
        mountTraceResults(partial)
        val partialDistance = assertNotNull(partial.state.totalPathDistance)
        assertTrue(texts().contains("${"%.0f".format(partialDistance)} m"))
        val infoDescription = "${host.get().getString(C.contactsResultsPartialDistanceLabel)}. " +
            host.get().getString(C.contactsResultsPartialDistanceHint)
        assertTrue(actionButton(infoDescription).boundsInRoot.height >= 48f)
        clickDescription(infoDescription)
        assertTrue(texts().contains(host.get().getString(C.contactsResultsPartialDistanceExplanation)))
        assertTrue(texts().contains(host.get().getString(C.contactsResultsFullPathTip)))
        click(host.get().getString(L.commonDone))
        assertFalse(texts().contains(host.get().getString(C.contactsResultsPartialDistanceExplanation)))
    }

    @Test fun mountedUnavailableTraceDistanceExplainsMissingRepeaterLocations() {
        val h = configured()
        val resultHops = listOf(
            hop(0.0, start = true, name = "My Device"),
            hop(5.0, name = "Tower A", hash = Bytes.of(0x3F)),
            hop(4.0, lat = 37.8716, lon = -122.2727, name = "Tower B", hash = Bytes.of(0x4F)),
            hop(0.0, end = true, name = "My Device"),
        )
        val location = Coordinate(37.7749, -122.4194)
        h.holder.editStateForTesting { it.copy(result = result(resultHops)) }
        mountTraceResults(h, location = location)
        assertNull(h.state.withResultLocation(location).totalPathDistance)
        assertTrue(texts().contains(host.get().getString(C.contactsResultsUnavailable)))
        val infoDescription = "${host.get().getString(C.contactsResultsDistanceUnavailableLabel)}. " +
            host.get().getString(C.contactsResultsDistanceInfoHint)
        clickDescription(infoDescription)
        assertTrue(texts().contains(host.get().getString(C.contactsResultsMissingLocations)))
        assertTrue(texts().contains(host.get().getString(C.contactsResultsRepeatersWithoutLocations)))
        assertTrue(texts().contains("Tower A"))
        click(host.get().getString(L.commonDone))
    }

    @Test fun mountedUnavailableTraceDistanceExplainsWhenMoreRepeatersAreNeeded() {
        val h = configured()
        h.holder.editStateForTesting {
            it.copy(result = result(listOf(
                hop(0.0, start = true, name = "My Device"),
                hop(5.0, lat = 37.8044, lon = -122.2712, name = "Tower A", hash = Bytes.of(0x3F)),
                hop(0.0, end = true, name = "My Device"),
            )))
        }
        mountTraceResults(h)
        assertNull(h.state.totalPathDistance)
        val infoDescription = "${host.get().getString(C.contactsResultsDistanceUnavailableLabel)}. " +
            host.get().getString(C.contactsResultsDistanceInfoHint)
        clickDescription(infoDescription)
        assertTrue(texts().contains(host.get().getString(C.contactsResultsNeedsRepeaters)))
        click(host.get().getString(L.commonDone))
    }

    @Test fun failureCardCopiesThreeByteChunksFromTheResultNotTheEditor() {
        val h = configured()
        val attempted = Bytes.of(0xA1, 0xB2, 0xC3, 0xD4, 0xE5, 0xF6)
        h.holder.addNode(contact(0x11, name = "Different editor path"))
        h.holder.editStateForTesting {
            it.copy(result = TraceResult.sendFailed(EnglishTraceStrings.sendFailed, attempted, 3))
        }
        mountTraceResults(h)
        assertTrue(texts().contains("A1B2C3,D4E5F6"))
        click(host.get().getString(C.contactsTraceListCopyPath))
        assertEquals("A1B2C3,D4E5F6", clipboardText())
    }

    @Test fun retainedCopyActionDoesNotCopyAReplacementFailureAfterRecomposition() {
        val h = configured()
        val sender = FakeTraceSender(failure = IllegalStateException("radio send failed"))
        h.deps.sender = sender
        h.holder.addNode(contact(0xAA, name = "Tower"))
        h.complete { h.holder.runTrace() }
        mountTraceResults(h)
        val label = host.get().getString(C.contactsTraceListCopyPath)
        val staleAction = assertNotNull(button(label).config[SemanticsActions.OnClick].action)
        val oldResult = assertNotNull(h.state.result)

        h.complete { h.holder.runTrace() }
        settle()
        scrollToTraceResults(h)
        assertTrue(assertNotNull(h.state.result).id != oldResult.id)
        setClipboardText("unchanged")
        host.get().runOnUiThread { assertTrue(staleAction()) }
        settle()
        assertEquals("unchanged", clipboardText())
        click(label)
        assertEquals("AA", clipboardText())
    }

    @Test fun mixedBatchStillShowsItsSuccessfulResultAfterALaterFailure() {
        val h = connectedMapHarness()
        val sender = FakeTraceSender()
        h.deps.sender = sender
        h.holder.addNode(contact(0xAA, name = "Tower"))
        h.holder.setBatchEnabled(true)
        h.holder.setBatchSize(2)
        mountPathMap(h)
        h.start { h.holder.runBatchTrace() }
        h.respond(sender.sent.single().tag, listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO)
        sender.failure = IllegalStateException("radio send failed")
        h.time.advanceBy(500.milliseconds)
        settle()
        assertEquals(listOf(true, false), h.state.completedResults.map { it.success })
        assertTrue(assertNotNull(h.state.displayedResult).success)
        click(host.get().getString(C.contactsTraceMapViewResults))
        assertTrue(texts().contains("2. Tower"))
        assertTrue(texts().contains("SNR: 6.0 dB"))
        click(host.get().getString(C.contactsTraceListCopyPath))
        assertEquals("AA", clipboardText())
    }

    @Test fun mapResultsRetainCancelledBatchProgressAndSuccessfulHopDetails() {
        val h = connectedMapHarness()
        val sender = FakeTraceSender()
        h.deps.sender = sender
        h.holder.addNode(contact(0xAA, name = "Tower"))
        h.holder.setBatchEnabled(true)
        h.start { h.holder.runBatchTrace() }
        h.respond(sender.sent.single().tag, listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO)
        h.holder.cancelBatchTrace()
        assertFalse(h.state.isRunning)
        assertEquals(1, h.state.successCount)
        mountPathMap(h)

        click(host.get().getString(C.contactsTraceMapViewResults))
        val progress = C.contactsResultsBatchSuccess(host.get().resources, 1, 3, 33)
        assertTrue(nodes().any {
            progress in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }
        })
        assertTrue(texts().contains("2. Tower"))
        assertTrue(texts().contains("SNR: 6.0 dB"))
    }

    @Test fun mapResultsShowUnlocatedHopDetailsAndRejectAStaleAction() {
        val h = connectedMapHarness()
        val sender = FakeTraceSender()
        h.deps.sender = sender
        val unlocated = contact(0xAA, name = "Unlocated Ridge")
        h.holder.setContactsForTesting(listOf(unlocated))
        h.holder.addNode(unlocated)
        var points: List<MapMarker> = emptyList()
        val surface: TraceMapSurface = { presentation, _, _, _, _, overlay ->
            SideEffect { points = presentation.points }
            Box(Modifier.fillMaxSize(), content = overlay)
        }
        mountPathMap(h, fontScale = 2f, surface = surface)
        click(host.get().getString(C.contactsTraceListRunTrace))
        h.main.runCurrent()
        h.time.advanceBy(437.milliseconds)
        h.respond(sender.sent.single().tag, listOf(node(0xAA, -6.0), node(null, 3.0)), RADIO)
        settle()
        assertTrue(points.none { it.style == MapPinStyle.BADGE })
        val resultsLabel = host.get().getString(C.contactsTraceMapViewResults)
        val action = assertNotNull(button(resultsLabel).config[SemanticsActions.OnClick].action)
        click(resultsLabel)
        assertTrue(texts().contains("437 ms"))
        assertTrue(texts().contains("2. Unlocated Ridge"))
        assertTrue(texts().contains("SNR: -6.0 dB"))

        host.get().runOnUiThread {
            h.holder.clearPath()
            assertTrue(action())
        }
        settle()
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertFalse(texts().contains(resultsLabel))

        val next = contact(0xBB, name = "Next Ridge")
        host.get().runOnUiThread {
            h.holder.setContactsForTesting(listOf(next))
            h.holder.addNode(next)
        }
        settle()
        click(host.get().getString(C.contactsTraceListRunTrace))
        h.main.runCurrent()
        h.time.advanceBy(123.milliseconds)
        h.respond(sender.sent.last().tag, listOf(node(0xBB, 2.0), node(null, 3.0)), RADIO)
        settle()
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        click(resultsLabel)
        assertTrue(texts().contains("123 ms"))
        assertTrue(texts().contains("2. Next Ridge"))
        assertFalse(texts().contains("2. Unlocated Ridge"))
    }

    @Test fun mountedTraceComparisonUsesAcceptanceOrderAtEqualTimestampsAndClockRollback() {
        for (rollback in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            var appends = 0
            val h = comparisonHarness(listOf(run(T0.plusMillis(150), roundTripMs = 100)), currentMs = 150) { store ->
                object : TracePathPersisting by store {
                    override suspend fun appendTracePathRun(key: EntityKey, run: TracePathRunDTO) {
                        if (++appends == 1) gate.await()
                        store.appendTracePathRun(key, run)
                    }
                }
            }
            if (rollback) h.time.advanceBy((-1).seconds)
            h.complete { h.holder.runTrace() }
            h.respond(
                (h.deps.sender as FakeTraceSender).sent.last().tag,
                listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO,
            )
            mountTraceResults(h)
            assertEquals(150L, h.state.previousRun?.roundTripMs)
            assertComparison(h, "0 ms", "100%", C.contactsResultsComparisonDecreased)
            gate.complete(Unit)
            h.main.runCurrent()
            settle()
            scrollToTraceResults(h)
            assertEquals(150L, h.state.previousRun?.roundTripMs)
            assertComparison(h, "0 ms", "100%", C.contactsResultsComparisonDecreased)
        }
    }

    @Test fun mountedTraceComparisonUsesAcceptedBatchSuccessAfterHeldOrFailedAppend() {
        for (fail in listOf(false, true)) {
            val gate = CompletableDeferred<Unit>()
            var appends = 0
            val h = comparisonHarness(listOf(run(T0.minusSeconds(60), roundTripMs = 100)), currentMs = 150) { store ->
                object : TracePathPersisting by store {
                    override suspend fun appendTracePathRun(key: EntityKey, run: TracePathRunDTO) {
                        if (++appends == 2) {
                            gate.await()
                            if (fail) throw java.io.IOException("held batch comparison append failure")
                        }
                        store.appendTracePathRun(key, run)
                    }
                }
            }
            h.holder.setBatchEnabled(true)
            h.holder.setBatchSize(1)
            h.start { h.holder.runBatchTrace() }
            h.time.advanceBy(50.milliseconds)
            h.respond(
                (h.deps.sender as FakeTraceSender).sent.last().tag,
                listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO,
            )
            assertTrue(h.state.isBatchComplete)
            assertEquals(2, assertNotNull(h.state.activeSavedPath).runs.size)
            if (fail) {
                gate.complete(Unit)
                h.main.runCurrent()
                assertTrue(h.diagnostics.failures.any { it.second is java.io.IOException })
            }
            h.holder.setBatchEnabled(false)
            h.complete { h.holder.runTrace() }
            h.time.advanceBy(25.milliseconds)
            h.respond(
                (h.deps.sender as FakeTraceSender).sent.last().tag,
                listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO,
            )
            mountTraceResults(h)
            assertEquals(50L, h.state.previousRun?.roundTripMs)
            assertComparison(h, "25 ms", "50%", C.contactsResultsComparisonDecreased)
            if (!fail) {
                gate.complete(Unit)
                h.main.runCurrent()
                settle()
                scrollToTraceResults(h)
                assertEquals(50L, h.state.previousRun?.roundTripMs)
                assertComparison(h, "25 ms", "50%", C.contactsResultsComparisonDecreased)
            }
        }
    }

    @Test fun mountedTraceComparisonKeepsItsAcceptedBaselineAcrossHeldHistoryAppend() =
        heldComparisonAppend(fail = false)

    @Test fun mountedTraceComparisonKeepsItsAcceptedBaselineWhenHistoryAppendFails() =
        heldComparisonAppend(fail = true)

    private fun heldComparisonAppend(fail: Boolean) {
        for (priorCount in listOf(1, 2)) {
            val previous = run(T0.minusSeconds(60), roundTripMs = 100)
            val history = if (priorCount == 1) listOf(previous)
                else listOf(run(T0.minusSeconds(120), roundTripMs = 250), previous)
            val gate = CompletableDeferred<Unit>()
            val h = comparisonHarness(history, currentMs = 150) { store ->
                object : TracePathPersisting by store {
                    override suspend fun appendTracePathRun(key: EntityKey, run: TracePathRunDTO) {
                        gate.await()
                        if (fail) throw java.io.IOException("held comparison append failure")
                        store.appendTracePathRun(key, run)
                    }
                }
            }
            mountTraceResults(h)
            assertEquals(priorCount, assertNotNull(h.state.activeSavedPath).runs.size)
            assertEquals(previous, h.state.previousRun)
            assertComparison(h, "150 ms", "50%", C.contactsResultsComparisonIncreased)
            gate.complete(Unit)
            h.main.runCurrent()
            settle()
            scrollToTraceResults(h)
            assertEquals(previous, h.state.previousRun)
            assertComparison(h, "150 ms", "50%", C.contactsResultsComparisonIncreased)
            assertEquals(priorCount + if (fail) 0 else 1, assertNotNull(h.state.activeSavedPath).runs.size)
            if (fail) assertTrue(h.diagnostics.failures.any { it.second is java.io.IOException })
            else assertTrue(h.diagnostics.failures.isEmpty())
        }
    }

    @Test fun mountedTraceComparisonRejectsOlderAppendCompletionAfterANewerAcceptedResult() {
        val gate = CompletableDeferred<Unit>()
        var appends = 0
        val h = comparisonHarness(listOf(run(T0.minusSeconds(60), roundTripMs = 100)), currentMs = 150) { store ->
            object : TracePathPersisting by store {
                override suspend fun appendTracePathRun(key: EntityKey, run: TracePathRunDTO) {
                    if (++appends == 1) gate.await()
                    store.appendTracePathRun(key, run)
                }
            }
        }
        mountTraceResults(h)
        assertComparison(h, "150 ms", "50%", C.contactsResultsComparisonIncreased)
        h.complete { h.holder.runTrace() }
        h.time.advanceBy(75.milliseconds)
        h.respond(
            (h.deps.sender as FakeTraceSender).sent.last().tag,
            listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO,
        )
        settle()
        scrollToTraceResults(h)
        val previous = assertNotNull(h.state.previousRun)
        assertEquals(150L, previous.roundTripMs)
        assertComparison(h, "75 ms", "50%", C.contactsResultsComparisonDecreased)
        gate.complete(Unit)
        h.main.runCurrent()
        settle()
        scrollToTraceResults(h)
        assertEquals(previous, h.state.previousRun)
        assertComparison(h, "75 ms", "50%", C.contactsResultsComparisonDecreased)
        assertEquals(3, assertNotNull(h.state.activeSavedPath).runs.size)
        h.holder.clearPath()
        settle()
        assertNull(h.state.acceptedComparison)
        assertNull(h.state.previousRun)
        assertFalse(texts().any { it.startsWith("vs.") })
    }

    @Test fun mountedTraceComparisonUsesThePreviousSuccessNotAnOlderOrFailedRun() {
        val previous = run(T0.minusSeconds(60), roundTripMs = 100)
        val h = comparisonHarness(
            listOf(previous, run(T0.minusSeconds(120), roundTripMs = 250), run(T0.minusSeconds(30), success = false)),
            currentMs = 150,
        )
        mountTraceResults(h)
        assertEquals(previous, h.state.previousRun)
        assertComparison(h, "150 ms", "50%", C.contactsResultsComparisonIncreased)
        assertEquals(4, assertNotNull(h.state.activeSavedPath).runs.size)
    }

    @Test fun mountedTraceComparisonShowsTheDecreasedRttAndAccessibleDirection() {
        val h = comparisonHarness(listOf(run(T0.minusSeconds(60), roundTripMs = 100)), currentMs = 50)
        mountTraceResults(h)
        assertComparison(h, "50 ms", "50%", C.contactsResultsComparisonDecreased)
    }

    @Test fun mountedTraceComparisonOmitsTheDeltaWhenRttIsUnchanged() {
        val h = comparisonHarness(listOf(run(T0.minusSeconds(60), roundTripMs = 100)), currentMs = 100)
        mountTraceResults(h)
        assertComparison(h, "100 ms")
        assertNoComparisonDirection()
        assertFalse(texts().any { it.contains('%') })
    }

    @Test fun mountedTraceComparisonUsesZeroPercentForAZeroBaseline() {
        val h = comparisonHarness(listOf(run(T0.minusSeconds(60), roundTripMs = 0)), currentMs = 25)
        mountTraceResults(h)
        assertComparison(h, "25 ms", "0%", C.contactsResultsComparisonIncreased)
        assertFalse(texts().any { it.contains("NaN") || it.contains("Infinity") })
    }

    @Test fun mountedTraceComparisonAppearsOnlyAfterTwoSuccessfulStoredRuns() {
        for (history in listOf(emptyList(), listOf(run(T0.minusSeconds(60), success = false)))) {
            val h = comparisonHarness(history, currentMs = 100)
            mountTraceResults(h)
            assertNull(h.state.previousRun)
            assertTrue(texts().contains("100 ms"))
            assertFalse(texts().any { it.startsWith("vs.") })
            assertNoComparisonDirection()
            h.complete { h.holder.runTrace() }
            h.time.advanceBy(50.milliseconds)
            h.respond(
                (h.deps.sender as FakeTraceSender).sent.last().tag,
                listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO,
            )
            settle()
            scrollToTraceResults(h)
            assertEquals(100L, h.state.previousRun?.roundTripMs)
            assertComparison(h, "50 ms", "50%", C.contactsResultsComparisonDecreased)
        }
    }

    @Test fun mountedTraceComparisonIsAbsentForUnsavedAndFailedResults() {
        val unsaved = successfulSaveHarness()
        mountTraceResults(unsaved)
        assertNull(unsaved.state.activeSavedPath)
        assertFalse(texts().any { it.startsWith("vs.") })
        assertNoComparisonDirection()
        val h = comparisonHarness(listOf(run(T0.minusSeconds(60), roundTripMs = 100)), currentMs = 150)
        mountTraceResults(h)
        assertComparison(h, "150 ms", "50%", C.contactsResultsComparisonIncreased)
        h.complete { h.holder.runTrace() }
        h.time.advanceBy(FloodTraceTimeout.sanitized((h.deps.sender as FakeTraceSender).suggestedTimeoutMs))
        settle()
        scrollToTraceResults(h)
        val failure = assertNotNull(h.state.result)
        assertFalse(failure.success)
        assertEquals(EnglishTraceStrings.noResponse, failure.errorMessage)
        assertEquals("AA", failure.tracedPathString)
        assertNotNull(h.state.errorMessage)
        assertNotNull(h.state.previousRun)
        assertFalse(texts().any { it.startsWith("vs.") })
        assertNoComparisonDirection()
    }

    @Test fun mountedTraceComparisonDoesNotReplaceBatchRttPresentation() {
        val h = comparisonHarness(listOf(run(T0.minusSeconds(60), roundTripMs = 100)), currentMs = 150)
        h.holder.setBatchEnabled(true)
        h.holder.setBatchSize(2)
        h.start { h.holder.runBatchTrace() }
        val sender = h.deps.sender as FakeTraceSender
        h.time.advanceBy(50.milliseconds)
        h.respond(sender.sent.last().tag, listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO)
        h.time.advanceBy(500.milliseconds)
        h.time.advanceBy(100.milliseconds)
        h.respond(sender.sent.last().tag, listOf(node(0xAA, 8.0), node(null, 4.0)), RADIO)
        mountTraceResults(h)
        assertNotNull(h.state.previousRun)
        assertTrue(h.state.isBatchComplete)
        val average = actionButton(C.contactsResultsAvgRTTLabel(host.get().resources, 75, 50, 100))
        assertTrue(average.boundsInRoot.width > 0f && average.boundsInRoot.height > 0f)
        assertEquals(75L, assertNotNull(h.state.batchRtt).averageMs)
        assertFalse(texts().any { it.startsWith("vs.") })
        assertNoComparisonDirection()
    }

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h900dp-mdpi")
    fun mountedTraceComparisonLocalizesDatePercentAndDirectionAtDoubleFont() {
        val h = comparisonHarness(listOf(run(T0.minusSeconds(60), roundTripMs = 100)), currentMs = 150)
        mountTraceResults(h, fontScale = 2f)
        val r = host.get().resources
        val percent = java.text.NumberFormat.getPercentInstance(r.configuration.locales[0]).apply {
            maximumFractionDigits = 0
        }.format(0.5)
        assertComparison(h, "150 ms", percent, C.contactsResultsComparisonIncreased)
        assertFalse(r.getString(C.contactsResultsComparisonIncreased) == "Increased")
        val comparison = nodes().single {
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text ->
                text.text == comparisonCaption(h)
            }
        }
        assertTrue(comparison.boundsInRoot.width > 0f)
        assertTrue(comparison.boundsInRoot.height > 0f)
        assertTrue(comparison.boundsInRoot.left >= 0f)
        assertTrue(comparison.boundsInRoot.right <= 320f)
        assertTrue(comparison.boundsInRoot.bottom <= 900f)
    }

    private fun comparisonHarness(
        history: List<TracePathRunDTO>,
        currentMs: Int,
        wrapStore: ((InMemoryTracePathStore) -> TracePathPersisting)? = null,
    ) = configured().also { h ->
        h.deps.sender = FakeTraceSender()
        h.holder.addNode(contact(0xAA, name = "Tower"))
        val store = InMemoryTracePathStore { h.time.now() }.also {
            it.insert(savedPath(h.state.fullPathData, runs = history, radioId = RADIO))
        }
        h.deps.store = wrapStore?.invoke(store) ?: store
        h.complete { h.holder.runTrace() }
        h.time.advanceBy(currentMs.milliseconds)
        h.respond(
            (h.deps.sender as FakeTraceSender).sent.last().tag,
            listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO,
        )
        assertEquals(currentMs.toLong(), assertNotNull(h.state.result).durationMs)
        assertNotNull(h.state.activeSavedPath)
        if (wrapStore == null) assertEquals(history.size + 1, assertNotNull(h.state.activeSavedPath).runs.size)
    }

    private fun mountTraceResults(h: TraceHarness, fontScale: Float = 1f, location: Coordinate? = null) {
        mountPathList(h, fontScale, location)
        scrollToTraceResults(h)
    }

    private fun scrollToTraceResults(h: TraceHarness) {
        val s = h.state
        val repeaters = s.availableRepeaters.let { if (it.isEmpty()) 0 else it.size + 1 }
        scrollToIndex(6 + s.outboundPath.size + repeaters)
    }

    private fun comparisonCaption(h: TraceHarness): String {
        val previous = assertNotNull(h.state.previousRun)
        val r = host.get().resources
        val date = DateFormat.getDateInstance(DateFormat.MEDIUM, r.configuration.locales[0]).format(Date.from(previous.date))
        return C.contactsResultsComparison(r, previous.roundTripMs.toInt(), date)
    }

    private fun assertComparison(h: TraceHarness, current: String, percent: String? = null, direction: Int? = null) {
        assertTrue(texts().contains(current), "Current RTT must remain visible: ${texts()}")
        assertTrue(texts().contains(comparisonCaption(h)), "Stored previous RTT/date must be visible: ${texts()}")
        assertTrue(texts().contains(host.get().getString(C.contactsPathDetailRoundTrip)))
        if (percent != null && direction != null) {
            val label = "${host.get().getString(direction)}, $percent"
            val delta = actionButton(label)
            assertTrue(delta.boundsInRoot.width > 0f && delta.boundsInRoot.height > 0f)
        }
    }

    private fun assertNoComparisonDirection() {
        val directions = listOf(C.contactsResultsComparisonIncreased, C.contactsResultsComparisonDecreased).map {
            host.get().getString(it)
        }
        assertFalse(nodes().any { node ->
            node.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { text ->
                directions.any { text.startsWith(it) }
            }
        })
    }

    @Test fun mountedSaveNamesAndPersistsTheSuccessfulTraceAndSuppressesAnotherSave() {
        val h = successfulSaveHarness()
        val store = h.deps.store as InMemoryTracePathStore
        val traced = assertNotNull(h.state.result)
        mountSaveAction(h)
        assertTrue(button(host.get().getString(C.contactsResultsSavePath)).boundsInRoot.height >= 48f)
        click(host.get().getString(C.contactsResultsSavePath))
        assertEquals("Tower", input().config[SemanticsProperties.EditableText].text)
        setInput(" \t ")
        assertTrue(button(host.get().getString(L.commonSave)).config.contains(SemanticsProperties.Disabled))
        val disabledSave = assertNotNull(button(host.get().getString(L.commonSave)).config[SemanticsActions.OnClick].action)
        host.get().runOnUiThread { disabledSave() }
        settle()
        assertTrue(store.paths.isEmpty())
        click(host.get().getString(L.commonCancel))
        assertTrue(store.paths.isEmpty())
        click(host.get().getString(C.contactsResultsSavePath))
        assertEquals("Tower", input().config[SemanticsProperties.EditableText].text)
        setInput("  Field route  ")
        click(host.get().getString(L.commonSave))
        val saved = store.paths.values.single()
        assertEquals("  Field route  ", saved.name, "Preserve the source's untrimmed nonblank name")
        assertEquals(RADIO, saved.radioId)
        assertEquals(traced.tracedPathBytes, saved.pathBytes)
        assertEquals(2L, saved.hashSize)
        assertEquals(traced.durationMs, saved.runs.single().roundTripMs)
        assertEquals(listOf(6.0), saved.runs.single().hopsSNR.toList())
        assertEquals(saved, h.state.activeSavedPath)
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        assertFalse(texts().contains(host.get().getString(C.contactsResultsSavePath)))
        assertEquals(1, (h.deps.sender as FakeTraceSender).sent.size, "Saving does not send radio commands")
    }

    @Test fun mountedSavePersistsEveryCompletedBatchRunAtTheDispatchedWidth() {
        val h = successfulSaveHarness(batch = true)
        val results = h.state.completedResults
        assertEquals(2, results.size)
        mountSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        setInput("Batch route")
        click(host.get().getString(L.commonSave))
        val saved = (h.deps.store as InMemoryTracePathStore).paths.values.single()
        assertEquals("Batch route", saved.name)
        assertEquals(results.first().tracedPathBytes, saved.pathBytes)
        assertEquals(2L, saved.hashSize)
        assertEquals(results.map { it.durationMs }, saved.runs.map { it.roundTripMs })
        assertEquals(2, saved.runs.size)
        assertEquals(saved, h.state.activeSavedPath)
    }

    @Test fun mountedSaveFailureKeepsTheNameAndAllowsARealStorageRetry() {
        val h = successfulSaveHarness()
        val store = h.deps.store as InMemoryTracePathStore
        mountSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        setInput("Retry this name")
        store.failure = java.io.IOException("fixture storage failure")
        click(host.get().getString(L.commonSave))
        assertTrue(texts().contains(host.get().getString(C.contactsTraceMapSaveFailedMessage)))
        assertEquals("Retry this name", input().config[SemanticsProperties.EditableText].text)
        assertTrue(store.paths.isEmpty())
        assertNull(h.state.activeSavedPath)
        assertEquals("savePath", h.diagnostics.failures.single().first)
        store.failure = null
        click(host.get().getString(L.commonSave))
        assertEquals("Retry this name", store.paths.values.single().name)
        assertNotNull(h.state.activeSavedPath)
    }

    @Test fun mountedSaveBlocksRepeatedConfirmationWhileTheAcceptedWriteIsHeld() {
        val h = successfulSaveHarness()
        val store = h.deps.store as InMemoryTracePathStore
        val release = CompletableDeferred<Unit>()
        var creates = 0
        h.deps.store = object : TracePathPersisting by store {
            override suspend fun createSavedTracePath(
                radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long,
                initialRun: com.meshcoreone.android.core.model.TracePathRunDTO?,
            ): SavedTracePathDTO {
                creates += 1
                release.await()
                return store.createSavedTracePath(radioId, name, pathBytes, hashSize, initialRun)
            }
        }
        mountSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        val confirm = assertNotNull(button(host.get().getString(L.commonSave)).config[SemanticsActions.OnClick].action)
        host.get().runOnUiThread { confirm() }
        settle()
        assertEquals(1, creates)
        assertTrue(button(host.get().getString(L.commonSave)).config.contains(SemanticsProperties.Disabled))
        assertTrue(button(host.get().getString(L.commonCancel)).config.contains(SemanticsProperties.Disabled))
        host.get().runOnUiThread { repeat(3) { confirm() } }
        settle()
        assertEquals(1, creates)
        assertTrue(store.paths.isEmpty())
        host.get().runOnUiThread { release.complete(Unit) }
        settle()
        assertEquals(1, store.paths.size)
        assertNotNull(h.state.activeSavedPath)
    }

    @Test fun mountedSaveRejectsEditedAndFailedRoutesWithoutWritingStorage() {
        val h = successfulSaveHarness()
        val store = h.deps.store as InMemoryTracePathStore
        mountSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        val confirm = assertNotNull(button(host.get().getString(L.commonSave)).config[SemanticsActions.OnClick].action)
        host.get().runOnUiThread { h.holder.clearPath(); confirm() }
        settle()
        assertTrue(store.paths.isEmpty())
        assertFalse(nodes().any { it.config.contains(SemanticsProperties.IsDialog) })
        assertFalse(texts().contains(host.get().getString(C.contactsResultsSavePath)))
        h.holder.addNode(contact(0xAA, name = "Tower"))
        h.deps.sender = FakeTraceSender(failure = java.io.IOException("fixture send failure"))
        h.complete { h.holder.runTrace() }
        settle()
        assertFalse(h.state.canSavePath)
        assertFalse(texts().contains(host.get().getString(C.contactsResultsSavePath)))
        assertTrue(store.paths.isEmpty())
    }

    @Test fun mountedSaveRemainsBusyWhenTheNextBatchResultArrivesDuringStorage() =
        heldBatchSaveAcrossResult(fail = false)

    @Test fun mountedSaveRetainsVisibleFailureAndNameWhenTheNextBatchResultArrivesDuringStorage() =
        heldBatchSaveAcrossResult(fail = true)

    @Test fun mountedBatchSaveRetryReusesTheCreatedPathAndCommittedRunIdentities() {
        for (failAfterCommit in listOf(false, true)) {
            val h = successfulSaveHarness(batch = true)
            val store = h.deps.store as InMemoryTracePathStore
            var creates = 0
            var failAppend = true
            h.deps.store = object : TracePathPersisting by store {
                override suspend fun createSavedTracePath(
                    radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long,
                    initialRun: com.meshcoreone.android.core.model.TracePathRunDTO?,
                ): SavedTracePathDTO {
                    creates += 1
                    return store.createSavedTracePath(radioId, name, pathBytes, hashSize, initialRun)
                }
                override suspend fun appendTracePathRun(
                    path: com.meshcoreone.android.core.contracts.domain.EntityKey,
                    run: com.meshcoreone.android.core.model.TracePathRunDTO,
                ) {
                    if (failAppend && !failAfterCommit) throw java.io.IOException("append before commit")
                    store.appendTracePathRun(path, run)
                    if (failAppend) throw java.io.IOException("append after commit")
                }
            }
            mountSaveAction(h)
            click(host.get().getString(C.contactsResultsSavePath))
            setInput("Retry partial batch")
            click(host.get().getString(L.commonSave))
            val partial = store.paths.values.single()
            assertEquals(if (failAfterCommit) 2 else 1, partial.runs.size)
            assertNull(h.state.activeSavedPath)
            assertTrue(texts().contains(host.get().getString(C.contactsTraceMapSaveFailedMessage)))
            assertEquals("Retry partial batch", input().config[SemanticsProperties.EditableText].text)
            assertEquals("savePath(batch)", h.diagnostics.failures.single().first)
            if (!failAfterCommit) {
                setInput("Retry partial batch B")
                click(host.get().getString(L.commonSave))
                assertEquals("Retry partial batch B", store.paths.values.single().name)
                assertEquals("Retry partial batch B", input().config[SemanticsProperties.EditableText].text)
                assertTrue(texts().contains(host.get().getString(C.contactsTraceMapSaveFailedMessage)))
                setInput("Retry partial batch")
            }
            failAppend = false
            click(host.get().getString(L.commonSave))
            val saved = store.paths.values.single()
            assertEquals(1, creates, "Retry must resume the already-created path")
            assertEquals(partial.id, saved.id)
            assertEquals("Retry partial batch", saved.name, "A to B to A retries must use the latest requested name")
            assertEquals(partial.runs.map { it.id }, saved.runs.take(partial.runs.size).map { it.id })
            assertEquals(2, saved.runs.size, "An append that committed before reporting failure must not be duplicated")
            assertEquals(listOf(listOf(6.0), listOf(8.0)), saved.runs.map { it.hopsSNR.toList() })
            assertEquals(saved, h.state.activeSavedPath)
        }
    }

    @Test fun mountedSaveCanStartAgainAfterItsPartialPathIsDeleted() {
        val h = successfulSaveHarness(batch = true)
        val store = h.deps.store as InMemoryTracePathStore
        var failAppend = true
        h.deps.store = object : TracePathPersisting by store {
            override suspend fun appendTracePathRun(
                path: com.meshcoreone.android.core.contracts.domain.EntityKey,
                run: com.meshcoreone.android.core.model.TracePathRunDTO,
            ) {
                if (failAppend) throw java.io.IOException("partial path fixture")
                store.appendTracePathRun(path, run)
            }
        }
        mountSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        click(host.get().getString(L.commonSave))
        val partial = store.paths.values.single()
        assertEquals(1, partial.runs.size)
        assertTrue(texts().contains(host.get().getString(C.contactsTraceMapSaveFailedMessage)))
        click(host.get().getString(L.commonCancel))
        h.complete { store.deleteSavedTracePath(com.meshcoreone.android.core.contracts.domain.EntityKey(RADIO, partial.id)) }
        host.get().runOnUiThread { h.holder.handleSavedPathDeleted(partial.id) }
        settle()
        assertTrue(store.paths.isEmpty())
        assertTrue(h.state.canSavePath)
        failAppend = false
        scrollToSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        click(host.get().getString(L.commonSave))
        val saved = store.paths.values.single()
        assertFalse(saved.id == partial.id, "An explicitly deleted partial path must not trap later Save attempts")
        assertEquals(2, saved.runs.size)
        assertEquals(saved, h.state.activeSavedPath)
    }

    private fun heldBatchSaveAcrossResult(fail: Boolean) {
        val h = configured()
        val store = InMemoryTracePathStore { h.time.now() }
        val release = CompletableDeferred<Unit>()
        var creates = 0
        var failCreate = fail
        h.deps.store = object : TracePathPersisting by store {
            override suspend fun createSavedTracePath(
                radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long,
                initialRun: com.meshcoreone.android.core.model.TracePathRunDTO?,
            ): SavedTracePathDTO {
                creates += 1
                release.await()
                if (failCreate) throw java.io.IOException("held fixture storage failure")
                return store.createSavedTracePath(radioId, name, pathBytes, hashSize, initialRun)
            }
        }
        val sender = FakeTraceSender()
        h.deps.sender = sender
        h.holder.setTraceHashMode(1u)
        h.holder.addNode(contact(0xAA, 0x11, name = "Tower"))
        h.holder.setBatchEnabled(true)
        h.holder.setBatchSize(2)
        h.start { h.holder.runBatchTrace() }
        h.respond(sender.sent.last().tag, listOf(TraceNode(Bytes.of(0xAA, 0x11), 6.0), node(null, 3.0)), RADIO)
        assertTrue(h.state.canSavePath)
        mountSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        setInput("Pending batch route")
        val confirm = assertNotNull(button(host.get().getString(L.commonSave)).config[SemanticsActions.OnClick].action)
        click(host.get().getString(L.commonSave))
        assertEquals(1, creates)
        h.time.advanceBy(500.milliseconds)
        h.respond(sender.sent.last().tag, listOf(TraceNode(Bytes.of(0xAA, 0x11), 8.0), node(null, 4.0)), RADIO)
        settle()
        val save = button(host.get().getString(L.commonSave))
        assertTrue(save.config.contains(SemanticsProperties.Disabled))
        val nameField = nodes().single { it.config.contains(SemanticsProperties.EditableText) }
        assertTrue(nameField.config.contains(SemanticsProperties.Disabled))
        assertEquals("Pending batch route", nameField.config[SemanticsProperties.EditableText].text)
        host.get().runOnUiThread { confirm() }
        settle()
        assertEquals(1, creates, "A new batch result must not release an accepted save's submission guard")
        host.get().runOnUiThread { release.complete(Unit) }
        settle()
        if (fail) {
            assertTrue(store.paths.isEmpty())
            assertNull(h.state.activeSavedPath)
            assertEquals("Pending batch route", input().config[SemanticsProperties.EditableText].text)
            assertTrue(texts().contains(host.get().getString(C.contactsTraceMapSaveFailedMessage)))
            failCreate = false
            click(host.get().getString(L.commonSave))
            assertEquals(2, creates)
        }
        val saved = store.paths.values.single()
        assertEquals("Pending batch route", saved.name)
        assertEquals(listOf(listOf(6.0), listOf(8.0)), saved.runs.map { it.hopsSNR.toList() })
        assertEquals(saved, h.state.activeSavedPath)
        assertEquals(2, h.state.completedResults.size)
    }

    @Test fun mountedSaveRejectsRetainedSameRadioConfirmationAfterReconnect() = staleSaveAcrossReconnect(false)

    @Test fun mountedSaveRejectsRetainedDifferentRadioConfirmationAfterReconnect() = staleSaveAcrossReconnect(true)

    @Test fun mountedAcceptedSaveKeepsItsSameRadioOriginWithoutReplacingFreshResults() = acceptedSaveAcrossReconnect(false)

    @Test fun mountedAcceptedSaveKeepsItsDifferentRadioOriginWithoutReplacingFreshResults() = acceptedSaveAcrossReconnect(true)

    @Test
    @Config(qualifiers = "fr-rFR-w320dp-h900dp-mdpi")
    fun mountedSaveUsesLocalizedNameAndActionsAtDoubleFont() {
        val h = successfulSaveHarness()
        mountSaveAction(h, fontScale = 2f)
        val savePath = host.get().getString(C.contactsResultsSavePath)
        assertFalse(savePath == "Save Path")
        click(savePath)
        assertTrue(texts().contains(host.get().getString(C.contactsTraceMapPathName)))
        setInput("Relais de montagne")
        assertTrue(button(host.get().getString(L.commonSave)).boundsInRoot.height >= 48f)
        click(host.get().getString(L.commonSave))
        assertEquals("Relais de montagne", (h.deps.store as InMemoryTracePathStore).paths.values.single().name)
    }

    private fun successfulSaveHarness(batch: Boolean = false): TraceHarness = configured().also { h ->
        h.deps.store = InMemoryTracePathStore { h.time.now() }
        h.deps.sender = FakeTraceSender()
        h.holder.setTraceHashMode(1u)
        h.holder.addNode(contact(0xAA, 0x11, name = "Tower"))
        h.holder.setBatchEnabled(batch)
        h.holder.setBatchSize(2)
        h.start { h.holder.runBatchTrace() }
        h.respond(
            (h.deps.sender as FakeTraceSender).sent.last().tag,
            listOf(TraceNode(Bytes.of(0xAA, 0x11), 6.0), node(null, 3.0)), RADIO,
        )
        if (batch) {
            h.time.advanceBy(500.milliseconds)
            h.respond(
                (h.deps.sender as FakeTraceSender).sent.last().tag,
                listOf(TraceNode(Bytes.of(0xAA, 0x11), 8.0), node(null, 4.0)), RADIO,
            )
        }
        assertTrue(h.state.canSavePath)
        assertNull(h.state.activeSavedPath)
    }

    private fun mountSaveAction(h: TraceHarness, fontScale: Float = 1f) {
        mountPathList(h, fontScale)
        scrollToSaveAction(h)
    }

    private fun scrollToSaveAction(h: TraceHarness) {
        val state = h.state
        val repeaterRows = state.availableRepeaters.let { if (it.isEmpty()) 0 else it.size + 1 }
        scrollToIndex(7 + state.outboundPath.size + repeaterRows)
    }

    private fun staleSaveAcrossReconnect(crossRadio: Boolean) {
        val h = successfulSaveHarness()
        val store = h.deps.store as InMemoryTracePathStore
        val generation = mutableIntStateOf(1)
        h.holder.bindConnection(RADIO, 1)
        // Binding retires the old result; obtain the eligible result in the actual A binding.
        h.complete { h.holder.runTrace() }
        val sender = h.deps.sender as FakeTraceSender
        h.respond(sender.sent.last().tag, listOf(TraceNode(Bytes.of(0xAA, 0x11), 6.0), node(null, 3.0)), RADIO)
        content.setContent {
            val state = h.holder.state.collectAsStateWithLifecycle().value
            val connection = TracePathScreen.Dependencies(h.deps, h.recents, h.diagnostics, h.deps.device?.radioId, generation.intValue)
            val entry = rememberTraceCodeInputState(h.holder, connection.generation, connection.radioId)
            MaterialTheme { TracePathListContent(state, h.holder, entry, connection) }
        }
        settle()
        scrollToSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        val oldConfirm = assertNotNull(button(host.get().getString(L.commonSave)).config[SemanticsActions.OnClick].action)
        val nextRadio = if (crossRadio) RadioId(UUID.randomUUID()) else RADIO
        host.get().runOnUiThread {
            h.deps.device = device().copy(radioId = nextRadio)
            h.holder.bindConnection(nextRadio, 2)
            generation.intValue = 2
        }
        settle()
        host.get().runOnUiThread { oldConfirm() }
        settle()
        assertTrue(store.paths.isEmpty(), "Retained A confirmation cannot save against B")
        assertNull(h.state.activeSavedPath)
        assertFalse(nodes().any { it.config.contains(SemanticsProperties.IsDialog) })
        h.complete { h.holder.runTrace() }
        h.respond(sender.sent.last().tag, listOf(TraceNode(Bytes.of(0xAA, 0x11), 9.0), node(null, 5.0)), nextRadio)
        settle()
        scrollToSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        setInput("Fresh B route")
        click(host.get().getString(L.commonSave))
        val saved = store.paths.values.single()
        assertEquals(nextRadio, saved.radioId)
        assertEquals("Fresh B route", saved.name)
    }

    private fun acceptedSaveAcrossReconnect(crossRadio: Boolean) {
        val h = successfulSaveHarness()
        val store = h.deps.store as InMemoryTracePathStore
        val release = CompletableDeferred<Unit>()
        var acceptedRadio: RadioId? = null
        h.deps.store = object : TracePathPersisting by store {
            override suspend fun createSavedTracePath(
                radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long,
                initialRun: com.meshcoreone.android.core.model.TracePathRunDTO?,
            ): SavedTracePathDTO {
                acceptedRadio = radioId
                release.await()
                return store.createSavedTracePath(radioId, name, pathBytes, hashSize, initialRun)
            }
        }
        mountSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        setInput("Accepted A")
        click(host.get().getString(L.commonSave))
        assertEquals(RADIO, acceptedRadio)
        val nextRadio = if (crossRadio) RadioId(UUID.randomUUID()) else RADIO
        host.get().runOnUiThread {
            h.deps.device = device().copy(radioId = nextRadio)
            h.holder.bindConnection(nextRadio, 2)
        }
        settle()
        h.complete { h.holder.runTrace() }
        val sender = h.deps.sender as FakeTraceSender
        h.respond(sender.sent.last().tag, listOf(TraceNode(Bytes.of(0xAA, 0x11), 9.0), node(null, 5.0)), nextRadio)
        settle()
        val fresh = h.state
        host.get().runOnUiThread { release.complete(Unit) }
        settle()
        val saved = store.paths.values.single()
        assertEquals(RADIO, saved.radioId, "Already accepted writes remain attributed to A")
        assertEquals("Accepted A", saved.name)
        assertEquals(fresh, h.state, "Old completion must not adopt A's saved path or overwrite B")
        assertNull(h.state.activeSavedPath)
        scrollToSaveAction(h)
        click(host.get().getString(C.contactsResultsSavePath))
        assertEquals("Tower", input().config[SemanticsProperties.EditableText].text)
        click(host.get().getString(L.commonCancel))
    }

    private fun attachHost() {
        host = Robolectric.buildActivity(TraceCodeInputTestActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    private fun destroyHost() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun realTraceScreenExposesPastePreviewAndAddsOnlyDistinctResolvableCodes() {
        val directory = FakeNodeDirectory(contacts = listOf(contact(0xAA, name = "Tower"), contact(0xBB, name = "Ridge")))
        val dependencies = FakeTraceDependencies().apply {
            device = device()
            this.directory = directory
        }
        val recents = MapRecentsStorage()
        content.setContent {
            MaterialTheme {
                TracePathScreen(
                    TracePathScreen.Dependencies(
                        dependencies, recents, RecordingDiagnostics(), RADIO, 1,
                        mapPreferences = host.get().mapPreferences,
                    ),
                    mapSurface = host.get().mapSurface,
                )
            }
        }
        settle()
        click("Add Hop")
        paste("\u200B\n  aa, bB,AA,FF,ZZ  \r\n\u200B")
        assertTrue(texts().containsAll(listOf("AA will be added", "BB will be added", "FF not found", "Invalid format: ZZ")))
        assertEquals(1, texts().count { it == "AA will be added" })
        click("Add nodes: AA, BB")
        assertEquals("", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(listOf("FF not found", "Invalid format: ZZ")))
        assertTrue(announcementTexts().containsAll(listOf("Adding as hop 3", "FF not found", "Invalid format: ZZ")))
        assertEquals(2, recents.values.values.single().size)
        setInput("AA,BB")
        assertTrue(texts().containsAll(listOf("AA already in path", "BB already in path")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        assertFalse(announcementTexts().any { it.startsWith("Adding as hop") })
        click("Done")
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        click("Add Hop")
        assertEquals("", input().config[SemanticsProperties.EditableText].text)
    }

    @Test fun pastedUniformWidthsRebuildTheRealPathAndDoNotSendRadioCommands() {
        val harness = configured()
        val sender = FakeTraceSender()
        harness.deps.sender = sender
        harness.holder.addNode(contact(0xCC, 0xDD, name = "Existing"))
        mount(harness)
        click("Add Hop")
        paste("AA00,BB00")
        assertEquals(2, harness.holder.hashSize)
        assertEquals(Bytes.of(0xCC, 0xDD), harness.state.outboundPath.single().hashBytes)
        assertTrue(texts().containsAll(listOf("AA00 will be added", "BB00 will be added")))
        paste("AA000000,BB000000")
        assertEquals(4, harness.holder.hashSize)
        assertTrue(texts().contains("AA000000 will be added"))
        paste("AA,BB")
        assertEquals(1, harness.holder.hashSize)
        assertEquals(Bytes.of(0xCC), harness.state.outboundPath.single().hashBytes)
        click("Add nodes: AA, BB")
        assertEquals(listOf(Bytes.of(0xCC), Bytes.of(0xAA), Bytes.of(0xBB)), harness.state.outboundPath.map { it.hashBytes })
        assertTrue(sender.sent.isEmpty())
    }

    @Test fun mixedMalformedAndUnknownCodesKeepTheirTypedOutcomesBeforeAndAfterAdding() {
        val harness = configured()
        harness.holder.setTraceHashMode(1u)
        harness.holder.addNode(contact(0xAA, name = "Existing"))
        val saved = savedPath(Bytes.of(0xAA, 0x00), hashSize = 2)
        val previous = result(emptyList())
        harness.holder.editStateForTesting { it.copy(activeSavedPath = saved, result = previous) }
        mount(harness)
        click("Add Hop")
        setInput("AA00,BB00,FF00,GG,AA")
        assertEquals(2, harness.holder.hashSize)
        assertEquals(saved, harness.state.activeSavedPath)
        assertEquals(previous, harness.state.result)
        assertTrue(texts().containsAll(listOf(
            "AA00 already in path", "BB00 will be added", "FF00 not found", "Invalid format: GG", "Invalid format: AA",
        )))
        click("Add nodes: BB00")
        assertEquals(listOf(Bytes.of(0xAA, 0x00), Bytes.of(0xBB, 0x00)), harness.state.outboundPath.map { it.hashBytes })
        assertNull(harness.state.activeSavedPath)
        assertNull(harness.state.result)
        assertTrue(texts().containsAll(listOf("AA00 already in path", "FF00 not found", "Invalid format: GG, AA")))
        assertEquals("", input().config[SemanticsProperties.EditableText].text)
    }

    @Test fun unsupportedFirmwareAndEmptyClipboardDoNotAdoptWidthOrInvalidateExistingState() {
        val harness = configured(supportsOverride = false)
        harness.holder.setTraceHashMode(1u)
        val saved = savedPath(Bytes.of(0xAA, 0x00), hashSize = 2)
        val previous = result(emptyList())
        harness.holder.editStateForTesting { it.copy(activeSavedPath = saved, result = previous) }
        mount(harness)
        click("Add Hop")
        paste("AA,BB")
        assertEquals(2, harness.holder.hashSize)
        assertTrue(texts().containsAll(listOf("Invalid format: AA", "Invalid format: BB")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        paste("   \n ")
        assertEquals("AA,BB", input().config[SemanticsProperties.EditableText].text)
        setInput(" , , ")
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        assertEquals(saved, harness.state.activeSavedPath)
        assertEquals(previous, harness.state.result)
        assertTrue(harness.state.outboundPath.isEmpty())
    }

    @Test @Config(qualifiers = "en-rUS-w840dp-h900dp-mdpi")
    fun expandedDoubleFontPreservesSameWidthStateAndExposesTextNotColorOnly() {
        val harness = configured()
        harness.holder.setTraceHashMode(1u)
        val saved = savedPath(Bytes.of(0xAA, 0x00), hashSize = 2)
        harness.holder.editStateForTesting { it.copy(activeSavedPath = saved) }
        mount(harness, fontScale = 2f)
        click("Add Hop")
        paste("aa00,BB00")
        assertEquals(saved, harness.state.activeSavedPath)
        assertEquals(2, harness.holder.hashSize)
        assertTrue(texts().containsAll(listOf("AA00 will be added", "BB00 will be added")))
        assertTrue(input().config[SemanticsProperties.Focused])
        assertTrue(button("Paste from clipboard").boundsInRoot.height >= 48f)
        assertTrue(button("Add nodes: AA00, BB00").boundsInRoot.height >= 48f)
        click("Add nodes: AA00, BB00")
        assertEquals(2, harness.state.outboundPath.size)
    }

    @Test fun successfulMountedAddAnnouncesTheNextHopButDuplicateAndEmptyEditsDoNot() {
        val harness = configured()
        mount(harness)
        click("Add Hop")
        paste("AA,BB")
        assertTrue(announcementTexts().isEmpty())
        click("Add nodes: AA, BB")
        assertEquals(2, harness.state.outboundPath.size)
        assertEquals(listOf("Adding as hop 3"), announcementTexts())
        setInput("AA,BB")
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        assertTrue(announcementTexts().isEmpty())
        setInput(" , ")
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        assertTrue(announcementTexts().isEmpty())
        assertEquals(2, harness.state.outboundPath.size)
    }

    @Test fun mountedPathControlsReorderWithinBoundariesAndPreserveRemovalInvalidation() {
        val harness = configured()
        listOf(
            contact(0xA1, name = "Alpha"),
            contact(0xB2, name = "Bravo"),
            contact(0xC3, name = "Charlie"),
        ).forEach(harness.holder::addNode)
        harness.holder.editStateForTesting {
            it.copy(activeSavedPath = savedPath(Bytes.of(0xA1, 0xB2, 0xC3)), result = result(emptyList()))
        }
        mountPathList(harness)

        assertTrue(actionButton(pathActionDescription(C.contactsDetailAvatarCropMoveUp, 1, harness.state.outboundPath[0]))
            .config.contains(SemanticsProperties.Disabled))
        assertTrue(actionButton(pathActionDescription(C.contactsDetailAvatarCropMoveDown, 3, harness.state.outboundPath[2]))
            .config.contains(SemanticsProperties.Disabled))
        assertTrue(actionButton(pathActionDescription(C.contactsDetailAvatarCropMoveDown, 1, harness.state.outboundPath[0]))
            .boundsInRoot.height >= 48f)

        clickDescription(pathActionDescription(C.contactsDetailAvatarCropMoveDown, 1, harness.state.outboundPath[0]))
        assertEquals(listOf("Bravo", "Alpha", "Charlie"), harness.state.outboundPath.map { it.resolvedName })
        assertNull(harness.state.activeSavedPath)
        assertNull(harness.state.result)

        clickDescription(pathActionDescription(C.contactsDetailAvatarCropMoveUp, 3, harness.state.outboundPath[2]))
        assertEquals(listOf("Bravo", "Charlie", "Alpha"), harness.state.outboundPath.map { it.resolvedName })
        clickDescription(pathActionDescription(L.commonDelete, 2, harness.state.outboundPath[1]))
        assertEquals(listOf("Bravo", "Alpha"), harness.state.outboundPath.map { it.resolvedName })
    }

    @Test
    @Config(qualifiers = "en-rUS-w320dp-h900dp-mdpi")
    fun compactLargeFontPathRowKeepsNameHashAndEveryActionReadable() {
        val harness = configured()
        harness.holder.addNode(contact(0xA1, name = "Mountain Ridge Emergency Repeater"))
        mountPathList(harness, fontScale = 2f)
        scrollToIndex(2)
        val hop = harness.state.outboundPath.single()
        val label = C.contactsTraceListHopLabel(host.get().resources, 1, hop.displayText)
        val textNodes = listOf(label, hop.hashHex).map { text ->
            val matches = nodes().filter {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text }
            }
            if (text == hop.hashHex) matches.minBy { it.boundsInRoot.top } else matches.single()
        }
        textNodes.forEach { node ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(assertNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts))
            val layout = layouts.single()
            if (layout.layoutInput.text.text == label) {
                assertFalse(layout.didOverflowWidth, "Hop name width: $layout")
                assertFalse(layout.didOverflowHeight, "Hop name height: $layout")
            } else {
                assertFalse(layout.didOverflowWidth, "Hop hash width: $layout")
                assertFalse(layout.didOverflowHeight, "Hop hash height: $layout")
            }
            assertFalse(layout.hasVisualOverflow,
                "Compact 200% text must wrap without clipping: text=${layout.layoutInput.text}, " +
                    "size=${layout.size}, bounds=${node.boundsInRoot}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                    "lines=${layout.lineCount}, paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}")
            assertTrue(node.boundsInRoot.width >= layout.size.width - 1f)
            assertTrue(node.boundsInRoot.height >= layout.size.height - 1f)
            assertTrue(node.boundsInRoot.width > 0f)
        }
        val actions = listOf(C.contactsDetailAvatarCropMoveUp, C.contactsDetailAvatarCropMoveDown, L.commonDelete)
            .map { actionButton(pathActionDescription(it, 1, hop)) }
        actions.forEach { node ->
            assertTrue(node.boundsInRoot.width >= 48f && node.boundsInRoot.height >= 48f)
            assertTrue(node.boundsInRoot.left >= 0f && node.boundsInRoot.right <= content.width.toFloat())
            textNodes.forEach { text ->
                assertTrue(text.boundsInRoot.right <= node.boundsInRoot.left ||
                    text.boundsInRoot.left >= node.boundsInRoot.right ||
                    text.boundsInRoot.bottom <= node.boundsInRoot.top ||
                    text.boundsInRoot.top >= node.boundsInRoot.bottom, "Details and controls must not overlap")
            }
        }
        assertTrue(actions[0].config.contains(SemanticsProperties.Disabled))
        assertTrue(actions[1].config.contains(SemanticsProperties.Disabled))
        clickDescription(pathActionDescription(L.commonDelete, 1, hop))
        assertTrue(harness.state.outboundPath.isEmpty(), "Compact layout keeps the real removal action")
    }

    @Test fun mountedClearPathRequiresConfirmationAndResetsTheTraceHashOverride() {
        val harness = configured()
        harness.holder.addNode(contact(0xA1, name = "Alpha"))
        harness.holder.setTraceHashMode(2u)
        harness.holder.setAutoReturnPath(false)
        harness.holder.editStateForTesting {
            it.copy(activeSavedPath = savedPath(Bytes.of(0xA1)), result = result(emptyList()))
        }
        mountPathList(harness)

        click(host.get().getString(C.contactsTraceClearPath))
        assertTrue(texts().contains(host.get().getString(C.contactsTraceClearPathMessage)))
        click("Cancel")
        assertEquals(1, harness.state.outboundPath.size)
        assertEquals(2u.toUByte(), harness.state.traceHashMode)

        click(host.get().getString(C.contactsTraceClearPath))
        click(host.get().getString(C.contactsTraceClearPath))
        assertTrue(harness.state.outboundPath.isEmpty())
        assertNull(harness.state.traceHashMode)
        assertEquals(0u.toUByte(), harness.holder.effectiveTraceMode)
        assertNull(harness.state.activeSavedPath)
        assertNull(harness.state.result)
        assertFalse(harness.state.autoReturnPath)
        assertTrue(texts().contains(host.get().getString(C.contactsTraceListEmptyPath)))
    }

    @Test fun mountedClearConfirmationRetiresATraceStartedAfterTheDialogOpened() {
        for (batch in listOf(false, true)) {
            val harness = configured()
            harness.holder.addNode(contact(0xA1, name = "Alpha"))
            harness.holder.setTraceHashMode(2u)
            harness.holder.setBatchEnabled(batch)
            val gate = CompletableDeferred<MessageSentInfo>()
            var tag = 0u
            harness.deps.sender = TraceSender { sentTag, _, _, _ -> tag = sentTag; gate.await() }
            mountPathList(harness)
            click(host.get().getString(C.contactsTraceClearPath))
            val run = harness.start { harness.holder.runBatchTrace() }
            assertTrue(harness.state.isRunning)
            settle()
            click(host.get().getString(C.contactsTraceClearPath))
            assertFalse(harness.state.isRunning)
            assertTrue(harness.state.outboundPath.isEmpty())
            assertNull(harness.state.traceHashMode)
            val cleared = harness.state
            harness.respond(tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            gate.complete(MessageSentInfo(0u, Bytes.EMPTY, 0u))
            harness.main.runCurrent()
            harness.time.advanceBy(60.seconds)
            harness.respond(tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            settle()
            assertTrue(run.isCompleted)
            assertEquals(cleared, harness.state)
            assertNull(harness.state.displayedResult)
            assertTrue(harness.state.completedResults.isEmpty())
            assertTrue(texts().contains(host.get().getString(C.contactsTraceListEmptyPath)))
        }
    }

    @Test fun staleMountedMoveCallbackRetiresPendingSingleAndBatchTraces() {
        for (batch in listOf(false, true)) {
            val harness = configured()
            harness.holder.addNode(contact(0xA1, name = "Alpha"))
            harness.holder.addNode(contact(0xB2, name = "Bravo"))
            harness.holder.setBatchEnabled(batch)
            val gate = CompletableDeferred<MessageSentInfo>()
            var tag = 0u
            harness.deps.sender = TraceSender { sentTag, _, _, _ -> tag = sentTag; gate.await() }
            mountPathList(harness)
            val oldCallback = assertNotNull(actionButton(
                pathActionDescription(C.contactsDetailAvatarCropMoveDown, 1, harness.state.outboundPath[0]),
            ).config[SemanticsActions.OnClick].action)
            val run = harness.start { harness.holder.runBatchTrace() }
            assertTrue(harness.state.isRunning)
            host.get().runOnUiThread { assertTrue(oldCallback()) }
            settle()
            assertFalse(harness.state.isRunning)
            assertEquals(listOf("Bravo", "Alpha"), harness.state.outboundPath.map { it.resolvedName })
            val edited = harness.state
            gate.complete(MessageSentInfo(0u, Bytes.EMPTY, 0u))
            harness.main.runCurrent()
            harness.respond(tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            harness.time.advanceBy(60.seconds)
            settle()
            assertTrue(run.isCompleted)
            assertEquals(edited, harness.state)
            assertNull(harness.state.displayedResult)
            assertTrue(harness.state.completedResults.isEmpty())
        }
    }

    @Test fun retainedHopCallbacksCannotEditOrCancelTheHopNowOccupyingTheOldIndex() {
        for (removeFirst in listOf(false, true)) {
            val harness = configured()
            harness.holder.addNode(contact(0xA1, name = "Alpha"))
            harness.holder.addNode(contact(0xB2, name = "Bravo"))
            harness.holder.addNode(contact(0xC3, name = "Charlie"))
            mountPathList(harness)
            val alpha = harness.state.outboundPath[0]
            val moveLabel = pathActionDescription(C.contactsDetailAvatarCropMoveDown, 1, alpha)
            val deleteLabel = pathActionDescription(L.commonDelete, 1, alpha)
            val oldMove = assertNotNull(actionButton(moveLabel).config[SemanticsActions.OnClick].action)
            val oldRemove = assertNotNull(actionButton(deleteLabel).config[SemanticsActions.OnClick].action)
            clickDescription(if (removeFirst) deleteLabel else moveLabel)
            assertEquals("Bravo", harness.state.outboundPath[0].resolvedName)
            val gate = CompletableDeferred<MessageSentInfo>()
            var tag = 0u
            harness.deps.sender = TraceSender { sentTag, _, _, _ -> tag = sentTag; gate.await() }
            val run = harness.start { harness.holder.runTrace() }
            val running = harness.state
            host.get().runOnUiThread {
                assertTrue(oldMove())
                assertTrue(oldRemove())
            }
            settle()
            assertEquals(running, harness.state, "Old Alpha callbacks must not edit or cancel Bravo's path")
            assertEquals(tag, harness.holder.pendingTag)
            gate.complete(MessageSentInfo(0u, Bytes.EMPTY, 0u))
            harness.main.runCurrent()
            harness.respond(tag, listOf(node(0xB2, 5.0), node(null, 3.0)), RADIO)
            settle()
            assertTrue(run.isCompleted)
            assertEquals(running.fullPathData, assertNotNull(harness.state.result).tracedPathBytes)
        }
    }

    @Test fun mountedAutoReturnSwitchRetiresTheOldTraceAndTheNextRunUsesTheNewWireRoute() {
        for (batch in listOf(false, true)) {
            val harness = configured()
            val recorded = FakeTraceSender()
            harness.deps.sender = recorded
            harness.holder.addNode(contact(0xA1, name = "Alpha"))
            harness.holder.addNode(contact(0xB2, name = "Bravo"))
            harness.holder.setBatchEnabled(batch)
            harness.holder.setBatchSize(1)
            mountPathList(harness)
            harness.start { harness.holder.runBatchTrace() }
            assertEquals(Bytes.of(0xA1, 0xB2, 0xA1), recorded.sent.last().path)
            val oldTag = recorded.sent.last().tag
            host.get().runOnUiThread {
                val autoReturn = nodes().first { it.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Switch }
                assertTrue(assertNotNull(autoReturn.config[SemanticsActions.OnClick].action)())
            }
            settle()
            assertFalse(harness.state.autoReturnPath)
            assertFalse(harness.state.isRunning)
            val edited = harness.state
            harness.respond(oldTag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            harness.time.advanceBy(60.seconds)
            assertEquals(edited, harness.state)
            val next = harness.start { harness.holder.runBatchTrace() }
            assertEquals(Bytes.of(0xA1, 0xB2), recorded.sent.last().path)
            harness.respond(recorded.sent.last().tag, listOf(node(0xB2, 5.0), node(null, 3.0)), RADIO)
            settle()
            assertTrue(next.isCompleted)
            assertEquals(edited.fullPathData, assertNotNull(harness.state.result).tracedPathBytes)
        }
    }

    @Test fun retainedMoveDownAtANewBoundaryCannotRetireAnotherTrace() {
        for (batch in listOf(false, true)) {
            val harness = configured()
            harness.holder.addNode(contact(0xA1, name = "Alpha"))
            harness.holder.addNode(contact(0xB2, name = "Bravo"))
            harness.holder.addNode(contact(0xC3, name = "Charlie"))
            harness.holder.setBatchEnabled(batch)
            harness.holder.setBatchSize(1)
            mountPathList(harness)
            val oldMove = assertNotNull(actionButton(
                pathActionDescription(C.contactsDetailAvatarCropMoveDown, 2, harness.state.outboundPath[1]),
            ).config[SemanticsActions.OnClick].action)
            harness.holder.removeRepeater(2)
            settle()
            val gate = CompletableDeferred<MessageSentInfo>()
            var tag = 0u
            harness.deps.sender = TraceSender { sentTag, _, _, _ -> tag = sentTag; gate.await() }
            val run = harness.start { harness.holder.runBatchTrace() }
            val running = harness.state
            host.get().runOnUiThread { assertTrue(oldMove()) }
            settle()
            assertEquals(running, harness.state)
            assertEquals(tag, harness.holder.pendingTag)
            gate.complete(MessageSentInfo(0u, Bytes.EMPTY, 0u))
            harness.main.runCurrent()
            harness.respond(tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            settle()
            assertTrue(run.isCompleted)
            assertFalse(harness.state.isRunning)
            assertEquals(running.fullPathData, assertNotNull(harness.state.result).tracedPathBytes)
        }
    }

    @Test fun stableFeatureReconnectRetiresHeldLookupAndSendBeforeAFreshMountedRun() {
        for (sameRadio in listOf(false, true)) for (heldLookup in listOf(false, true)) {
            val newRadio = if (sameRadio) RADIO else RadioId(UUID.randomUUID())
            val persistence = InMemoryTracePathStore { T0 }
            val oldPath = savedPath(Bytes.of(0xAA), radioId = RADIO)
            persistence.insert(oldPath)
            val selected = if (sameRadio) oldPath else savedPath(Bytes.of(0xAA), radioId = newRadio).also(persistence::insert)
            val lookupGate = CompletableDeferred<Unit>()
            val sendGate = CompletableDeferred<MessageSentInfo>()
            val oldSends = mutableListOf<UInt>()
            val freshSender = FakeTraceSender()
            val oldEvents = TraceResponseBroadcaster()
            val newEvents = TraceResponseBroadcaster()
            var events = oldEvents
            var firstLookup = true
            val feature = FakeTraceDependencies().apply {
                device = device()
                directory = FakeNodeDirectory(contacts = listOf(contact(0xAA, name = "Tower")))
                responses = { events.subscribe() }
                store = object : TracePathPersisting by persistence {
                    override suspend fun fetchSavedTracePaths(radioId: RadioId) =
                        persistence.fetchSavedTracePaths(radioId).also {
                            if (firstLookup) {
                                firstLookup = false
                                if (heldLookup) lookupGate.await()
                            }
                        }
                }
                sender = TraceSender { tag, _, _, _ -> oldSends += tag; sendGate.await() }
            }
            val connection = mutableStateOf(TracePathScreen.Dependencies(
                feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
            ))
            content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
            settle()
            click("Tower")
            scrollToIndex(10)
            click(host.get().getString(C.contactsTraceListRunTrace))
            assertEquals(if (heldLookup) 0 else 1, oldSends.size)
            host.get().runOnUiThread {
                feature.device = device().copy(radioId = newRadio)
                feature.sender = freshSender
                events = newEvents
                connection.value = connection.value.copy(radioId = newRadio, generation = 2)
            }
            settle()
            lookupGate.complete(Unit)
            sendGate.complete(MessageSentInfo(0u, Bytes.EMPTY, 0u))
            oldSends.forEach { tag ->
                oldEvents.yield(TraceResponse(traceInfo(tag, listOf(node(0xAA, 9.0), node(null, 3.0))), RADIO))
                newEvents.yield(TraceResponse(traceInfo(tag, listOf(node(0xAA, 9.0), node(null, 3.0))), newRadio))
            }
            settle()
            assertEquals(if (heldLookup) 0 else 1, oldSends.size, "A retired lookup must not reach its captured sender")
            assertTrue(freshSender.sent.isEmpty())
            assertTrue(persistence.paths.values.all { it.runs.isEmpty() })
            assertFalse(texts().any { it.contains("SNR") })
            scrollToIndex(10)
            click(host.get().getString(C.contactsTraceListRunTrace))
            assertEquals(Bytes.of(0xAA), freshSender.sent.single().path, "Reconnect preserves the editable route")
            newEvents.yield(TraceResponse(
                traceInfo(freshSender.sent.single().tag, listOf(node(0xAA, 6.0), node(null, 3.0))), newRadio,
            ))
            settle()
            assertEquals(1, persistence.paths.getValue(selected.id).runs.size)
            assertEquals(1, persistence.paths.values.sumOf { it.runs.size })
            assertTrue(texts().contains("SNR: 6.0 dB"))
        }
    }

    @Test fun mountedOneHopAutoReturnKeepsThePendingWireRoute() = oneHopAutoReturnPreservesRoute(false)
    @Test fun mountedOneHopAutoReturnKeepsTheCompletedResult() = oneHopAutoReturnPreservesRoute(true)

    private fun oneHopAutoReturnPreservesRoute(completed: Boolean) {
        val h = configured()
        val recorded = FakeTraceSender()
        h.deps.sender = recorded
        h.holder.addNode(contact(0xA1, name = "Alpha"))
        h.complete { h.holder.runTrace() }
        val tag = recorded.sent.single().tag
        if (completed) h.respond(tag, listOf(node(0xA1, 6.0), node(null, 3.0)), RADIO)
        mountPathList(h)
        val before = h.state
        val pending = h.holder.pendingTag
        host.get().runOnUiThread {
            val toggle = nodes().first { it.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Switch }
            assertTrue(assertNotNull(toggle.config[SemanticsActions.OnClick].action)())
        }
        settle()
        assertEquals(before.copy(autoReturnPath = false), h.state)
        assertEquals(pending, h.holder.pendingTag)
        if (!completed) h.respond(tag, listOf(node(0xA1, 6.0), node(null, 3.0)), RADIO)
        settle()
        assertTrue(assertNotNull(h.state.result).success)
        assertEquals(Bytes.of(0xA1), h.state.result?.tracedPathBytes)
        assertEquals(1, recorded.sent.size)
    }

    @Test fun productionOriginAllowsOfflineDraftEntryAndSameRadioReconnect() = offlineDraftAcrossReconnect(false, false)
    @Test fun productionOriginAllowsOfflineDraftEntryAndDifferentRadioReconnect() = offlineDraftAcrossReconnect(false, true)
    @Test fun productionOriginRetainsOpenDraftOnDisconnectAndSameRadioReconnect() = offlineDraftAcrossReconnect(true, false)
    @Test fun productionOriginRetainsOpenDraftOnDisconnectAndDifferentRadioReconnect() = offlineDraftAcrossReconnect(true, true)
    @Test fun offlineRunAttemptPreservesDraftAndSameRadioReconnect() = offlineDraftAcrossReconnect(true, false, true)
    @Test fun offlineRunAttemptPreservesDraftAndDifferentRadioReconnect() = offlineDraftAcrossReconnect(true, true, true)

    @Test fun offlineDraftSurvivesOldLookupCompletion() = offlineDraftAfterOldWork("lookup")
    @Test fun offlineDraftSurvivesOldSendCompletion() = offlineDraftAfterOldWork("send")
    @Test fun offlineDraftSurvivesOldBatchSleepCompletion() = offlineDraftAfterOldWork("sleep")
    @Test fun offlineDraftSurvivesAcceptedSingleSaveCompletion() = offlineDraftAfterOldWork("save")
    @Test fun offlineDraftSurvivesAcceptedBatchSaveCompletion() = offlineDraftAfterOldWork("batch-save")
    @Test fun offlineDraftSurvivesAcceptedHistoryRefreshCompletion() = offlineDraftAfterOldWork("history")
    @Test fun offlineDraftSurvivesUnmatchedLateResponse() = offlineDraftAfterOldWork("response")
    @Test fun offlineDraftSurvivesStrictRunRejection() = offlineDraftAfterOldWork("strict")

    private fun offlineDraftAfterOldWork(stage: String) {
        val h = configured()
        val epoch = com.meshcoreone.android.core.contracts.domain.ProcessEpoch(UUID.randomUUID())
        var token: com.meshcoreone.android.core.contracts.domain.SessionToken? =
            com.meshcoreone.android.core.contracts.domain.SessionToken(
                epoch, com.meshcoreone.android.core.contracts.domain.Generation(1), RADIO,
            )
        var version = 1
        val feature = object : TracePathFeatureDependencies by h.deps {
            override fun sessionToken() = token
            override fun isOriginatingSessionCurrent(
                generation: Int, token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
            ) = token != null && generation == version && token == this.sessionToken()
        }
        h.holder.configure(feature)
        h.holder.bindConnection(RADIO, version, token)
        h.holder.addNode(contact(0xAA, name = "Tower"))
        val persistence = InMemoryTracePathStore { h.time.now() }
        if (stage == "lookup" || stage == "history") {
            persistence.insert(savedPath(h.state.fullPathData, radioId = RADIO))
        }
        val release = CompletableDeferred<Unit>()
        var entered = false
        h.deps.store = object : TracePathPersisting by persistence {
            override suspend fun fetchSavedTracePaths(radioId: RadioId) =
                persistence.fetchSavedTracePaths(radioId).also {
                    if (stage == "lookup") { entered = true; release.await() }
                }
            override suspend fun fetchSavedTracePath(key: com.meshcoreone.android.core.contracts.domain.EntityKey) =
                persistence.fetchSavedTracePath(key).also {
                    if (stage == "history") { entered = true; release.await() }
                }
            override suspend fun createSavedTracePath(
                radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long,
                initialRun: com.meshcoreone.android.core.model.TracePathRunDTO?,
            ): SavedTracePathDTO {
                entered = true
                release.await()
                return persistence.createSavedTracePath(radioId, name, pathBytes, hashSize, initialRun)
            }
        }
        val tags = mutableListOf<UInt>()
        h.deps.sender = TraceSender { tag, _, _, _ ->
            tags += tag
            if (stage == "send") { entered = true; release.await() }
            MessageSentInfo(0u, Bytes.EMPTY, 0u)
        }
        h.holder.setBatchEnabled(stage == "sleep" || stage == "batch-save")
        h.holder.setBatchSize(if (stage == "sleep") 2 else 1)
        val run = h.start { h.holder.runBatchTrace() }
        if (stage in listOf("sleep", "save", "batch-save", "history")) {
            h.respond(tags.single(), listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO)
        }
        var saved: Boolean? = null
        val save = if (stage == "save" || stage == "batch-save") {
            assertTrue(run.isCompleted)
            h.start { saved = h.holder.savePath("Accepted A") }
        } else null
        if (stage in listOf("lookup", "send", "save", "batch-save", "history")) assertTrue(entered)
        if (stage == "sleep") assertFalse(run.isCompleted)
        token = null
        h.deps.device = null
        h.holder.bindConnection(null, version, null)
        mount(h)
        click("Add Hop")
        setInput("Offline before release")
        val offline = h.state
        val sends = tags.size
        release.complete(Unit)
        h.main.runCurrent()
        h.time.advanceBy(500.milliseconds)
        if (stage == "response") h.respond(tags.single(), listOf(node(0xAA, 9.0), node(null, 3.0)), RADIO)
        if (stage == "strict") {
            assertFalse(h.holder.isConnectionCurrent(), "Local draft authority never authorizes radio execution")
            h.complete { h.holder.runBatchTrace() }
        }
        h.time.advanceBy(60.seconds)
        assertTrue(run.isCompleted)
        assertTrue(save?.isCompleted != false)
        assertEquals(sends, tags.size, "Retired A work never sends again")
        assertEquals(offline, h.state, "Old work cannot mutate the rebound offline holder")
        assertTrue(h.holder.isBoundEditingCurrent(null, version, null))
        setInput("Offline after release")
        paste("CC")
        assertEquals("CC", input().config[SemanticsProperties.EditableText].text)
        click("Done")
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        if (save != null) {
            assertEquals(true, saved)
            assertEquals(RADIO, persistence.paths.values.single().radioId)
        }
        if (stage == "history") assertEquals(1, persistence.paths.values.single().runs.size)
        version += 1
        val nextRadio = RadioId(UUID.randomUUID())
        token = com.meshcoreone.android.core.contracts.domain.SessionToken(
            epoch, com.meshcoreone.android.core.contracts.domain.Generation(2), nextRadio,
        )
        h.deps.device = device().copy(radioId = nextRadio)
        h.holder.bindConnection(nextRadio, version, token)
        h.holder.setContactsForTesting(listOf(contact(0xBB, name = "Ridge")))
        click("Add Hop")
        setInput("BB")
        click("Add nodes: BB")
        assertEquals(2, h.state.outboundPath.size, "Fresh B can still extend the actual retained route")
        assertEquals(sends, tags.size)
    }

    private fun offlineDraftAcrossReconnect(disconnectOpenDraft: Boolean, crossRadio: Boolean, retainedRoute: Boolean = false) {
        val epoch = com.meshcoreone.android.core.contracts.domain.ProcessEpoch(UUID.randomUUID())
        var token: com.meshcoreone.android.core.contracts.domain.SessionToken? =
            if (disconnectOpenDraft) com.meshcoreone.android.core.contracts.domain.SessionToken(
                epoch, com.meshcoreone.android.core.contracts.domain.Generation(1), RADIO,
            ) else null
        var version = 1
        val recorded = FakeTraceSender()
        val deps = FakeTraceDependencies().apply {
            device = if (disconnectOpenDraft) device() else null
            sender = recorded
            directory = FakeNodeDirectory(contacts = listOf(contact(0xAA, name = "Tower"), contact(0xBB, name = "Ridge")))
        }
        val feature = object : TracePathFeatureDependencies by deps {
            override fun sessionToken() = token
            override fun originatingSessionToken(generation: Int) = token.takeIf { generation == version }
            override fun isOriginatingSessionCurrent(
                generation: Int,
                token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
            ) = token != null && generation == version && token == this.sessionToken()
        }
        val connection = mutableStateOf(TracePathScreen.Dependencies(
            feature, MapRecentsStorage(), RecordingDiagnostics(), token?.radioId, version,
        ))
        content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
        settle()
        scrollToIndex(3)
        click("Add Hop")
        setInput("AA")
        val inputIndex = if (retainedRoute) 4 else 3
        val freshCode = if (retainedRoute) "BB" else "AA"
        if (retainedRoute) {
            click("Add nodes: AA")
            scrollToIndex(inputIndex)
            setInput(freshCode)
        }
        val oldText = assertNotNull(input().config[SemanticsActions.SetText].action)
        val oldDone = assertNotNull(button("Done").config[SemanticsActions.OnClick].action)
        if (disconnectOpenDraft) {
            host.get().runOnUiThread {
                token = null
                deps.device = null
                version += 1
                connection.value = connection.value.copy(radioId = null, generation = version)
            }
            settle()
            if (retainedRoute) {
                scrollToIndex(8)
                val run = button(host.get().getString(C.contactsTraceListRunTrace))
                assertTrue(run.config.contains(SemanticsProperties.Disabled))
                host.get().runOnUiThread { assertTrue(assertNotNull(run.config[SemanticsActions.OnClick].action)()) }
                settle()
                assertTrue(recorded.sent.isEmpty(), "An offline run attempt never writes to the radio")
            }
            scrollToIndex(inputIndex)
            assertEquals(freshCode, input().config[SemanticsProperties.EditableText].text)
            host.get().runOnUiThread {
                assertTrue(oldText(AnnotatedString("Retired A")))
                assertTrue(oldDone())
            }
            settle()
            assertEquals(freshCode, input().config[SemanticsProperties.EditableText].text)
        }
        setInput("BB")
        paste("CC")
        assertEquals("CC", input().config[SemanticsProperties.EditableText].text)
        click("Done")
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        click("Add Hop")
        setInput(freshCode)
        val offlineText = assertNotNull(input().config[SemanticsActions.SetText].action)
        val offlineDone = assertNotNull(button("Done").config[SemanticsActions.OnClick].action)
        val nextRadio = if (crossRadio) RadioId(UUID.randomUUID()) else RADIO
        host.get().runOnUiThread {
            version += 1
            token = com.meshcoreone.android.core.contracts.domain.SessionToken(
                epoch, com.meshcoreone.android.core.contracts.domain.Generation(version.toLong()), nextRadio,
            )
            deps.device = device().copy(radioId = nextRadio)
            connection.value = connection.value.copy(radioId = nextRadio, generation = version)
        }
        settle()
        scrollToIndex(inputIndex)
        host.get().runOnUiThread {
            assertTrue(offlineText(AnnotatedString("Retired offline")))
            assertTrue(offlineDone())
        }
        settle()
        assertEquals(freshCode, input().config[SemanticsProperties.EditableText].text)
        assertTrue(recorded.sent.isEmpty(), "Local drafts never send radio commands")
        assertTrue(button("Add nodes: $freshCode").boundsInRoot.height >= 48f)
        click("Add nodes: $freshCode")
        assertEquals("", input().config[SemanticsProperties.EditableText].text)
        scrollToIndex(2)
        assertTrue(texts().any { it.contains(if (retainedRoute) "Ridge (BB)" else "Tower (AA)") },
            "Fresh connected Add changes the real route")
    }

    @Test fun sameRadioReconnectRejectsRetainedAutoReturn() = retainedListActionAcrossReconnect(false, "auto")
    @Test fun differentRadioReconnectRejectsRetainedAutoReturn() = retainedListActionAcrossReconnect(true, "auto")
    @Test fun sameRadioReconnectRejectsRetainedBatchToggle() = retainedListActionAcrossReconnect(false, "batch")
    @Test fun differentRadioReconnectRejectsRetainedBatchToggle() = retainedListActionAcrossReconnect(true, "batch")
    @Test fun sameRadioReconnectRejectsRetainedHashMode() = retainedListActionAcrossReconnect(false, "hash")
    @Test fun differentRadioReconnectRejectsRetainedHashMode() = retainedListActionAcrossReconnect(true, "hash")
    @Test fun sameRadioReconnectRejectsRetainedRepeaterAdd() = retainedListActionAcrossReconnect(false, "repeater")
    @Test fun differentRadioReconnectRejectsRetainedRepeaterAdd() = retainedListActionAcrossReconnect(true, "repeater")
    @Test fun sameRadioReconnectRejectsRetainedDiscoveredRepeaterAdd() = retainedListActionAcrossReconnect(false, "discovered")
    @Test fun differentRadioReconnectRejectsRetainedDiscoveredRepeaterAdd() = retainedListActionAcrossReconnect(true, "discovered")
    @Test fun mountedSavedPathDisplaysFullStoredWidthStringAndRemainsEditable() {
        val persistence = InMemoryTracePathStore { T0 }
        val saved = savedPath(
            Bytes.of(0xAA, 0xBB, 0xCC, 0xDD, 0xAA, 0xBB),
            hashSize = 2,
            radioId = RADIO,
        ).copy(name = "Saved two-byte route")
        persistence.insert(saved)
        val deps = FakeTraceDependencies().apply {
            device = device()
            directory = FakeNodeDirectory(contacts = listOf(contact(0xEE, 0xFF, name = "Tower")))
            store = persistence
        }
        val connection = TracePathScreen.Dependencies(
            deps, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
        )
        content.setContent { MaterialTheme { TracePathScreen(connection) } }
        settle()

        click(host.get().getString(C.contactsTraceSaved))
        click(saved.name)
        scrollToIndex(4)
        val restoredPath = "AABB,CCDD,AABB"
        assertTrue(texts().contains(restoredPath))
        assertTrue(nodes().any { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == restoredPath } &&
                node.boundsInRoot.width > 0f
        }, "The complete restored path must be visible and exposed as accessible text")

        scrollToIndex(7)
        click("Tower")
        scrollToIndex(5)
        assertTrue(texts().contains("AABB,CCDD,EEFF,CCDD,AABB"),
            "Editing the loaded path must update the visible outbound and mirrored return path")
    }

    @Test fun traceMapRunButtonDisablesWhenDisconnected() {
        val epoch = com.meshcoreone.android.core.contracts.domain.ProcessEpoch(UUID.randomUUID())
        var token = com.meshcoreone.android.core.contracts.domain.SessionToken(
            epoch, com.meshcoreone.android.core.contracts.domain.Generation(1), RADIO,
        )
        var version = 1
        val persistence = InMemoryTracePathStore { T0 }
        val path = savedPath(Bytes.of(0xA1), radioId = RADIO)
        persistence.insert(path)
        val recorded = FakeTraceSender()
        val deps = FakeTraceDependencies().apply {
            device = device()
            store = persistence
            sender = recorded
        }
        val feature = object : TracePathFeatureDependencies by deps {
            override fun sessionToken() = token
            override fun originatingSessionToken(generation: Int) = token.takeIf { generation == version }
            override fun isOriginatingSessionCurrent(
                generation: Int,
                token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
            ) = generation == version && token == this.sessionToken()
        }
        val connection = mutableStateOf(TracePathScreen.Dependencies(
            feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
            mapPreferences = host.get().mapPreferences,
        ))
        content.setContent { MaterialTheme { TracePathScreen(connection.value, host.get().mapSurface) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(path.name)
        click(host.get().getString(C.contactsTraceModeMap))
        val runLabel = host.get().getString(C.contactsTraceListRunTrace)
        val retainedRun = assertNotNull(button(runLabel).config[SemanticsActions.OnClick].action)
        assertFalse(button(runLabel).config.contains(SemanticsProperties.Disabled))

        host.get().runOnUiThread {
            deps.device = null
            version = 2
            token = token.copy(generation = com.meshcoreone.android.core.contracts.domain.Generation(2))
            connection.value = connection.value.copy(generation = version)
        }
        settle()
        assertTrue(button(runLabel).config.contains(SemanticsProperties.Disabled))
        host.get().runOnUiThread { assertTrue(retainedRun()) }
        settle()
        assertTrue(recorded.sent.isEmpty(), "A retained Run action cannot send after disconnection")
    }

    @Test fun sameRadioReconnectRejectsRetainedRun() = retainedListActionAcrossReconnect(false, "run")
    @Test fun differentRadioReconnectRejectsRetainedRun() = retainedListActionAcrossReconnect(true, "run")
    @Test fun sameRadioReconnectRejectsRetainedCancel() = retainedListActionAcrossReconnect(false, "cancel")
    @Test fun differentRadioReconnectRejectsRetainedCancel() = retainedListActionAcrossReconnect(true, "cancel")
    @Test fun sameRadioReconnectRejectsRetainedCodeText() = retainedListActionAcrossReconnect(false, "text")
    @Test fun differentRadioReconnectRejectsRetainedCodeText() = retainedListActionAcrossReconnect(true, "text")
    @Test fun sameRadioReconnectRejectsRetainedCodePaste() = retainedListActionAcrossReconnect(false, "paste")
    @Test fun differentRadioReconnectRejectsRetainedCodePaste() = retainedListActionAcrossReconnect(true, "paste")
    @Test fun sameRadioReconnectRejectsRetainedCodeAdd() = retainedListActionAcrossReconnect(false, "codes")
    @Test fun differentRadioReconnectRejectsRetainedCodeAdd() = retainedListActionAcrossReconnect(true, "codes")

    private fun retainedListActionAcrossReconnect(crossRadio: Boolean, action: String) {
        val epoch = com.meshcoreone.android.core.contracts.domain.ProcessEpoch(UUID.randomUUID())
        var token = com.meshcoreone.android.core.contracts.domain.SessionToken(
            epoch, com.meshcoreone.android.core.contracts.domain.Generation(1), RADIO,
        )
        var version = 1
        val persistence = InMemoryTracePathStore { T0 }
        val saved = savedPath(Bytes.of(0xA1), radioId = RADIO)
        persistence.insert(saved)
        val recorded = FakeTraceSender()
        val events = TraceResponseBroadcaster()
        val deps = FakeTraceDependencies().apply {
            device = device()
            store = persistence
            sender = recorded
            responses = { events.subscribe() }
            directory = FakeNodeDirectory(
                contacts = listOf(contact(0xA1, name = "Alpha"), contact(0xB2, name = "Bravo")),
                nodes = if (action == "discovered") listOf(discovered(key(0xE5), "Scout")) else emptyList(),
            )
        }
        val feature = object : TracePathFeatureDependencies by deps {
            override fun sessionToken() = token
            override fun originatingSessionToken(generation: Int) = token.takeIf { generation == version }
            override fun isOriginatingSessionCurrent(
                generation: Int,
                token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
            ) = generation == version && token == this.sessionToken()
        }
        val connection = mutableStateOf(TracePathScreen.Dependencies(
            feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
        ))
        content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(saved.name)
        host.get().runOnUiThread {
            val autoReturn = nodes().first { it.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Switch }
            assertEquals(androidx.compose.ui.state.ToggleableState.On, autoReturn.config[SemanticsProperties.ToggleableState])
            assertTrue(assertNotNull(autoReturn.config[SemanticsActions.OnClick].action)())
        }
        settle()
        val codeAction = action in listOf("text", "paste", "codes")
        val runLabel = host.get().getString(C.contactsTraceListRunTrace)
        if (codeAction) {
            scrollToIndex(4)
            click("Add Hop")
            setInput("B2")
        }
        if (action == "cancel") {
            scrollToIndex(11)
            click(runLabel)
        }
        scrollToIndex(when (action) {
            "auto" -> 1
            "batch" -> 9
            "hash" -> 8
            "repeater" -> 7
            "discovered" -> 8
            "run", "cancel" -> 11
            else -> 4
        })
        fun switchAction(last: Boolean): () -> Boolean {
            val switches = nodes().filter { it.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Switch }
            return assertNotNull((if (last) switches.last() else switches.first()).config[SemanticsActions.OnClick].action)
        }
        val oldText = if (action == "text") assertNotNull(input().config[SemanticsActions.SetText].action) else null
        val old = when (action) {
            "auto" -> switchAction(false)
            "batch" -> switchAction(true)
            "hash" -> assertNotNull(button("4 B").config[SemanticsActions.OnClick].action)
            "repeater" -> assertNotNull(button("Bravo").config[SemanticsActions.OnClick].action)
            "discovered" -> assertNotNull(button("Scout").config[SemanticsActions.OnClick].action)
            "run" -> assertNotNull(button(runLabel).config[SemanticsActions.OnClick].action)
            "cancel" -> assertNotNull(button(host.get().getString(C.contactsTraceListRunningTrace)).config[SemanticsActions.OnClick].action)
            "paste" -> assertNotNull(button("Paste from clipboard").config[SemanticsActions.OnClick].action)
            "codes" -> assertNotNull(button("Add nodes: B2").config[SemanticsActions.OnClick].action)
            else -> null
        }
        val nextRadio = if (crossRadio) RadioId(UUID.randomUUID()) else RADIO
        host.get().runOnUiThread {
            token = token.copy(generation = com.meshcoreone.android.core.contracts.domain.Generation(2), radioId = nextRadio)
            version = 2
            deps.device = device().copy(radioId = nextRadio)
            connection.value = connection.value.copy(radioId = nextRadio, generation = version)
        }
        settle()
        scrollToIndex(if (action == "discovered") 13 else 11)
        click(runLabel)
        val sent = recorded.sent.last()
        val sendCount = recorded.sent.size
        assertEquals(Bytes.of(0xA1), sent.path)
        if (action == "paste") {
            val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("trace codes", "B2B2,C3C3"))
        }
        host.get().runOnUiThread {
            if (oldText != null) assertTrue(oldText(AnnotatedString("B2B2,C3C3")))
            else assertTrue(assertNotNull(old)())
        }
        settle()
        assertEquals(sendCount, recorded.sent.size, "Retained $action cannot issue a B trace")
        assertTrue(texts().contains(host.get().getString(C.contactsTraceListRunningTrace)),
            "Retained $action cannot edit or cancel B's trace")
        if (codeAction) {
            scrollToIndex(4)
            assertEquals("B2", input().config[SemanticsProperties.EditableText].text, "B retains its draft without A overwriting it")
            scrollToIndex(11)
        }
        events.yield(TraceResponse(traceInfo(sent.tag, listOf(node(0xA1, 6.0), node(null, 3.0))), nextRadio))
        settle()
        assertTrue(texts().contains("SNR: 6.0 dB"), "Retained $action cannot corrupt B correlation")
        scrollToIndex(when (action) {
            "auto" -> 1
            "batch" -> 9
            "hash" -> 8
            "repeater" -> 7
            "discovered" -> 8
            "run", "cancel" -> 11
            else -> 4
        })
        when (action) {
            "auto" -> host.get().runOnUiThread { assertTrue(switchAction(false)()) }
            "batch" -> host.get().runOnUiThread { assertTrue(switchAction(true)()) }
            "hash" -> click("4 B")
            "repeater" -> click("Bravo")
            "discovered" -> click("Scout")
            "run", "cancel" -> {
                click(runLabel)
                assertEquals(sendCount + 1, recorded.sent.size)
                click(host.get().getString(C.contactsTraceListRunningTrace))
                assertFalse(texts().contains(host.get().getString(C.contactsTraceListRunningTrace)))
            }
            else -> {
                setInput("B2")
                if (action == "paste") paste("B2")
                click("Add nodes: B2")
                assertEquals("", input().config[SemanticsProperties.EditableText].text)
            }
        }
        settle()
        if (action == "auto" || action == "batch") {
            val switches = nodes().filter { it.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Switch }
            assertEquals(androidx.compose.ui.state.ToggleableState.On,
                (if (action == "auto") switches.first() else switches.last()).config[SemanticsProperties.ToggleableState],
                "Fresh B toggle changes the real holder")
        }
        if (codeAction || action == "repeater" || action == "discovered") {
            scrollToIndex(if (action == "discovered") 13 else 12)
            click(runLabel)
            val expected = if (action == "discovered") Bytes.of(0xA1, 0xE5) else Bytes.of(0xA1, 0xB2)
            assertEquals(expected, recorded.sent.last().path, "Fresh B $action changes the real route")
            click(host.get().getString(C.contactsTraceListRunningTrace))
        } else if (action == "hash") {
            scrollToIndex(11)
            click(runLabel)
            assertEquals(2u.toUByte(), recorded.sent.last().flags)
            click(host.get().getString(C.contactsTraceListRunningTrace))
        }
    }

    @Test fun sameRadioReconnectRejectsRetainedHopEditsAgainstThePreservedRoute() {
        for (action in listOf("up", "down", "remove")) retainedPathEditAcrossReconnect(false, action)
    }

    @Test fun differentRadioReconnectRejectsRetainedHopEditsAgainstThePreservedRoute() {
        for (action in listOf("up", "down", "remove")) retainedPathEditAcrossReconnect(true, action)
    }

    @Test fun sameRadioReconnectDismissesTheOldClearDialog() = retainedPathEditAcrossReconnect(false, "dialog")

    @Test fun differentRadioReconnectDismissesTheOldClearDialog() = retainedPathEditAcrossReconnect(true, "dialog")

    @Test fun sameRadioReconnectRejectsARetainedClearConfirmation() = retainedPathEditAcrossReconnect(false, "confirm")

    @Test fun differentRadioReconnectRejectsARetainedClearConfirmation() = retainedPathEditAcrossReconnect(true, "confirm")

    @Test fun sameRadioReconnectRetiresMapClearDialogAndConfirmation() = retainedPathEditAcrossReconnect(false, "map-dialog")

    @Test fun differentRadioReconnectRejectsRetainedMapClearConfirmation() =
        retainedPathEditAcrossReconnect(true, "map-confirm")

    private fun retainedPathEditAcrossReconnect(crossRadio: Boolean, action: String) {
        val epoch = com.meshcoreone.android.core.contracts.domain.ProcessEpoch(UUID.randomUUID())
        var token = com.meshcoreone.android.core.contracts.domain.SessionToken(
            epoch, com.meshcoreone.android.core.contracts.domain.Generation(1), RADIO,
        )
        var publishedVersion = 1
        val persistence = InMemoryTracePathStore { T0 }
        val path = savedPath(Bytes.of(0xA1, 0xB2, 0xC3), radioId = RADIO)
        persistence.insert(path)
        val recorded = FakeTraceSender()
        val events = TraceResponseBroadcaster()
        val deps = FakeTraceDependencies().apply {
            device = device()
            store = persistence
            sender = recorded
            responses = { events.subscribe() }
            directory = FakeNodeDirectory(contacts = listOf(
                contact(0xA1, name = "Alpha"), contact(0xB2, name = "Bravo"), contact(0xC3, name = "Charlie"),
            ))
        }
        val feature = object : TracePathFeatureDependencies by deps {
            override fun sessionToken() = token
            override fun originatingSessionToken(generation: Int) = token.takeIf { generation == publishedVersion }
            override fun isOriginatingSessionCurrent(
                generation: Int,
                token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
            ) = generation == publishedVersion && token == this.sessionToken()
        }
        val connection = mutableStateOf(TracePathScreen.Dependencies(
            feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
            mapPreferences = host.get().mapPreferences,
        ))
        content.setContent { MaterialTheme { TracePathScreen(connection.value, host.get().mapSurface) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(path.name)
        val isMap = action.startsWith("map-")
        val isClear = action == "dialog" || action == "confirm" || isMap
        if (isMap) {
            click(host.get().getString(C.contactsTraceModeMap))
            settle()
        }
        val resource = when (action) {
            "up" -> C.contactsDetailAvatarCropMoveUp
            "down" -> C.contactsDetailAvatarCropMoveDown
            else -> L.commonDelete
        }
        val oldHop = if (isClear) null else {
            scrollToIndex(if (action == "up") 3 else 2)
            val label = "${host.get().getString(resource)}: ${
                C.contactsTraceListHopLabel(host.get().resources, if (action == "up") 2 else 1,
                    if (action == "up") "Bravo (B2)" else "Alpha (A1)")
            }"
            assertNotNull(actionButton(label).config[SemanticsActions.OnClick].action)
        }
        if (!isMap) scrollToIndex(5)
        val clearLabel = host.get().getString(if (isMap) C.contactsTraceMapClear else C.contactsTraceClearPath)
        val confirmLabel = host.get().getString(C.contactsTraceClearPath)
        val oldOpen = assertNotNull(button(clearLabel).config[SemanticsActions.OnClick].action)
        val oldConfirm = if (isClear) {
            click(clearLabel)
            assertNotNull(button(confirmLabel).config[SemanticsActions.OnClick].action).also {
                if (action == "confirm" || action == "map-confirm") click(host.get().getString(L.commonCancel))
            }
        } else null
        val nextRadio = if (crossRadio) RadioId(UUID.randomUUID()) else RADIO
        host.get().runOnUiThread {
            token = token.copy(generation = com.meshcoreone.android.core.contracts.domain.Generation(2), radioId = nextRadio)
            publishedVersion = 2
            deps.device = device().copy(radioId = nextRadio)
            connection.value = connection.value.copy(radioId = nextRadio, generation = 2)
            assertEquals(token, connection.value.originatingToken)
        }
        settle()
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true, "Reconnect retires A's clear dialog")
        if (!isMap) scrollToIndex(14)
        click(host.get().getString(C.contactsTraceListRunTrace))
        assertEquals(Bytes.of(0xA1, 0xB2, 0xC3), recorded.sent.single().path, "B retains the editable route")
        host.get().runOnUiThread { assertTrue((oldConfirm ?: assertNotNull(oldHop))()) }
        settle()
        if (isMap) {
            assertFalse(button(clearLabel).config.contains(SemanticsProperties.Disabled),
                "A's retained confirmation must not clear B's map route")
        } else {
            assertTrue(texts().contains(host.get().getString(C.contactsTraceListRunningTrace)),
                "A's retained edit must not change B's route or cancel B's trace")
        }
        assertEquals(path, persistence.paths[path.id])
        if (isClear) {
            host.get().runOnUiThread { assertTrue(oldOpen()) }
            settle()
            assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true, "A's retained open cannot create a B dialog")
            if (!isMap) scrollToIndex(5)
            click(clearLabel)
            click(confirmLabel)
            if (!isMap) assertTrue(texts().contains(host.get().getString(C.contactsTraceListEmptyPath)))
            if (isMap) {
                assertTrue(button(clearLabel).config.contains(SemanticsProperties.Disabled),
                    "Fresh B confirmation clears the map route")
            } else {
                assertFalse(texts().contains(host.get().getString(C.contactsTraceListRunningTrace)))
            }
        } else {
            val label = "${host.get().getString(resource)}: ${
                C.contactsTraceListHopLabel(host.get().resources, if (action == "up") 2 else 1,
                    if (action == "up") "Bravo (B2)" else "Alpha (A1)")
            }"
            scrollToIndex(if (action == "up") 3 else 2)
            clickDescription(label)
            val expected = if (action == "remove") listOf(0xB2, 0xC3) else listOf(0xB2, 0xA1, 0xC3)
            scrollToIndex(11 + expected.size)
            click(host.get().getString(C.contactsTraceListRunTrace))
            val sent = recorded.sent.last()
            assertEquals(Bytes.of(*expected.toIntArray()), sent.path, "Fresh B edit reaches the real holder")
            events.yield(TraceResponse(
                traceInfo(sent.tag, expected.map { node(it, 6.0) } + node(null, 3.0)), nextRadio,
            ))
            settle()
            assertTrue(texts().contains("SNR: 6.0 dB"), "The fresh B trace still accepts its own response")
        }
    }

    @Test fun savedPathDeletionRequiresConfirmationAndCancelKeepsHistory() {
        val store = InMemoryTracePathStore { T0 }
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO, runs = listOf(run(T0))).copy(name = "Ridge route")
        store.insert(path)
        val feature = FakeTraceDependencies().apply {
            device = device()
            this.store = store
        }
        val connection = TracePathScreen.Dependencies(feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1)
        content.setContent { MaterialTheme { TracePathScreen(connection) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        val deleteLabel = "${host.get().getString(L.commonDelete)}: ${path.name}"
        clickDescription(deleteLabel)
        assertTrue(texts().contains(host.get().getString(C.contactsSavedPathsDeleteTitle)))
        assertTrue(texts().contains(C.contactsSavedPathsDeleteMessage(host.get().resources, path.name)))
        assertEquals(path, store.paths[path.id])
        val oldConfirm = assertNotNull(button(host.get().getString(C.contactsCommonDelete))
            .config[SemanticsActions.OnClick].action)
        click(host.get().getString(C.contactsCommonCancel))
        assertEquals(path, store.paths[path.id])
        assertTrue(texts().contains(path.name))
        clickDescription(deleteLabel)
        host.get().runOnUiThread { assertTrue(oldConfirm()) }
        settle()
        assertEquals(path, store.paths[path.id])
        assertTrue(texts().contains(C.contactsSavedPathsDeleteMessage(host.get().resources, path.name)))
        click(host.get().getString(C.contactsCommonDelete))
        assertFalse(store.paths.containsKey(path.id))
    }

    @Test fun mountedSavedTraceRunsPersistAcrossComparisonHistoryAndRunDetails() {
        val h = configured()
        val store = InMemoryTracePathStore { h.time.now() }
        val oldest = run(T0.minusSeconds(120), 300)
        val failure = run(T0.minusSeconds(30), success = false)
        val previous = run(T0.minusSeconds(60), 100)
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO, runs = listOf(oldest, failure, previous))
        store.insert(path)
        h.deps.store = store
        h.deps.sender = FakeTraceSender()
        h.holder.bindConnection(RADIO, 1)
        val connection = TracePathScreen.Dependencies(h.deps, h.recents, h.diagnostics, RADIO, 1)
        val saved = SavedPathsStateHolder(object : SavedPathsStrings {
            override val loadFailed = "Load failed"
            override val renameFailed = "Rename failed"
            override val deleteFailed = "Delete failed"
        }, h.diagnostics).also {
            it.configure(object : SavedPathsDependencies {
                override fun connectedDevice() = h.deps.device
                override fun savedPaths() = store
            })
        }
        content.setContent {
            val state by h.holder.state.collectAsStateWithLifecycle()
            val paths by saved.state.collectAsStateWithLifecycle()
            MaterialTheme {
                TracePathContent(state, h.holder, paths, saved, null, null, h.diagnostics, host.get().mapSurface, connection)
            }
        }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(path.name)
        assertEquals(path.id, h.state.activeSavedPath?.id, "Saved row must still load, not open history")
        scrollToTraceResults(h)
        click(host.get().getString(C.contactsTraceListRunTrace))
        h.main.runCurrent()
        h.time.advanceBy(200.milliseconds)
        h.respond(
            (h.deps.sender as FakeTraceSender).sent.single().tag,
            listOf(node(0xAA, 6.0), node(null, -2.0)), RADIO,
        )
        settle()
        scrollToTraceResults(h)
        assertComparison(h, "200 ms", "100%", C.contactsResultsComparisonIncreased)
        clickTag("trace-view-runs")
        assertTrue(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-chart" })
        val chart = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-chart" }
        val alternative = chart.config[SemanticsProperties.ContentDescription].single()
        assertTrue(alternative.contains("100 ms") && alternative.contains("200 ms") && alternative.contains("300 ms"))
        assertTrue(texts().contains("75%"))
        assertTrue(texts().contains("100 ms"))
        scrollToIndex(2)
        val current = store.paths.getValue(path.id).runs.maxBy { it.date }
        val visibleRuns = nodes().mapNotNull { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.takeIf { tag -> tag.startsWith("trace-run:") } }
        assertEquals(listOf(current, failure, previous, oldest).map { "trace-run:${it.id}" }, visibleRuns)
        clickTag("trace-run:${current.id}")
        assertTrue(texts().contains(host.get().getString(C.contactsPathDetailRunDetails)))
        assertTrue(texts().contains("6.00 dB"))
        assertFalse(texts().contains("-2.00 dB"), "The end device is not a stored intermediate hop")
        click(host.get().getString(L.commonDone))
        assertTrue(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history" })
        clickTag("trace-run:${failure.id}")
        assertTrue(texts().contains(host.get().getString(C.contactsPathDetailFailed)))
        assertFalse(texts().contains(host.get().getString(C.contactsPathDetailPerHopSNR)))
        click(host.get().getString(L.commonDone))
        scrollToIndex(0)
        click(host.get().getString(L.commonDone))
        assertComparison(h, "200 ms", "100%", C.contactsResultsComparisonIncreased)
        clickTag("trace-view-runs")
        scrollToIndex(2)
        assertTrue(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-run:${current.id}" })
        assertEquals(4, store.paths.getValue(path.id).runs.size)
        assertEquals(1, (h.deps.sender as FakeTraceSender).sent.size, "Navigation must never send another trace")
    }

    @Test fun mapHistoryDoneReturnsToItsComparison() = mapHistoryReturnsToComparison(useBack = false)

    @Test fun mapHistoryBackReturnsToItsComparison() = mapHistoryReturnsToComparison(useBack = true)

    private fun mapHistoryReturnsToComparison(useBack: Boolean) {
        val h = configured()
        val store = InMemoryTracePathStore { h.time.now() }
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO, runs = listOf(run(T0.minusSeconds(60), 100)))
        store.insert(path)
        h.deps.store = store
        h.deps.sender = FakeTraceSender()
        h.holder.bindConnection(RADIO, 1)
        h.holder.loadSavedPath(path)
        h.complete { h.holder.runTrace() }
        h.time.advanceBy(200.milliseconds)
        h.respond(
            (h.deps.sender as FakeTraceSender).sent.single().tag,
            listOf(node(0xAA, 6.0), node(null, 3.0)), RADIO,
        )
        val preferences = TraceCodeInputMapPreferences()
        val connection = TracePathScreen.Dependencies(h.deps, h.recents, h.diagnostics, RADIO, 1, preferences)
        val saved = SavedPathsStateHolder(object : SavedPathsStrings {
            override val loadFailed = "Load failed"
            override val renameFailed = "Rename failed"
            override val deleteFailed = "Delete failed"
        }, h.diagnostics).also {
            it.configure(object : SavedPathsDependencies {
                override fun connectedDevice() = h.deps.device
                override fun savedPaths() = store
            })
        }
        content.setContent {
            val state by h.holder.state.collectAsStateWithLifecycle()
            MaterialTheme {
                TracePathContent(
                    state, h.holder, SavedPathsState(), saved, preferences, null,
                    h.diagnostics, host.get().mapSurface, connection,
                )
            }
        }
        settle()
        click(host.get().getString(C.contactsTraceModeMap))
        click(host.get().getString(C.contactsTraceMapViewResults))
        assertComparison(h, "200 ms", "100%", C.contactsResultsComparisonIncreased)
        clickTag("trace-view-runs")
        val historyDialog = ShadowDialog.getShownDialogs().last { it.isShowing }
        scrollToIndex(2)
        val current = store.paths.getValue(path.id).runs.maxBy { it.date }
        clickTag("trace-run:${current.id}")
        assertTrue(texts().contains(host.get().getString(C.contactsPathDetailRunDetails)))
        click(host.get().getString(L.commonDone))
        scrollToIndex(0)
        if (useBack) {
            host.get().runOnUiThread {
                assertIs<ComponentDialog>(historyDialog).onBackPressedDispatcher.onBackPressed()
            }
            var remaining = 20
            do { settle() } while (historyDialog.isShowing && --remaining > 0)
        } else {
            click(host.get().getString(L.commonDone))
        }
        assertFalse(historyDialog.isShowing)
        assertComparison(h, "200 ms", "100%", C.contactsResultsComparisonIncreased)
        click(host.get().getString(L.commonDone))
        assertTrue(texts().contains(host.get().getString(C.contactsTraceMapViewResults)))
        assertFalse(ShadowDialog.getShownDialogs().any { it.isShowing })
        assertEquals(1, (h.deps.sender as FakeTraceSender).sent.size)
    }

    @Test fun savedHistoryIdentitySaverRejectsMalformedRestorationAndKeepsTheStoredFormat() {
        val diagnostics = RecordingDiagnostics()
        val saver = traceHistoryPathIdSaver(diagnostics)
        val scope = object : SaverScope {
            override fun canBeSaved(value: Any) = true
        }
        val id = UUID.randomUUID()
        val stored = with(saver) { scope.save(id) }
        assertEquals(id.toString(), stored)
        assertEquals(id, saver.restore(assertNotNull(stored)))
        for (invalid in listOf("", "not-a-uuid")) assertNull(saver.restore(invalid))
        assertEquals(List(2) { "restoreTraceHistory" }, diagnostics.failures.map { it.first })
        assertTrue(diagnostics.failures.all { it.second is IllegalArgumentException })
    }

    @Test fun chartDescriptionCachesFormattingAndInvalidatesForChangedRunsAndLocale() {
        val recompose = mutableIntStateOf(0)
        val formattedDates = mutableListOf<Long>()
        fun countingFormat(locale: Locale): DateFormat {
            val delegate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale)
            return object : DateFormat() {
                init {
                    calendar = delegate.calendar
                    numberFormat = delegate.numberFormat
                }
                override fun format(date: Date, buffer: StringBuffer, position: FieldPosition): StringBuffer {
                    check(recompose.intValue >= 0)
                    formattedDates += date.time
                    return delegate.format(date, buffer, position)
                }
                override fun parse(source: String, position: ParsePosition): Date? = delegate.parse(source, position)
            }
        }
        val runs = mutableStateOf(List(25) { run(T0.plusSeconds(it.toLong()), (it + 1).toLong()) })
        val format = mutableStateOf(countingFormat(Locale.US))
        content.setContent { MaterialTheme { TraceRunChart(runs.value, format.value) } }
        settle()
        val middle = runs.value[12].date.toEpochMilli()
        val middleFormats = formattedDates.count { it == middle }
        assertTrue(middleFormats > 0)
        val totalFormats = formattedDates.size
        host.get().runOnUiThread { recompose.intValue++ }
        settle()
        assertTrue(formattedDates.size > totalFormats, "The chart must actually recompose")
        assertEquals(middleFormats, formattedDates.count { it == middle }, "Unchanged history must not be joined/formatted again")
        host.get().runOnUiThread {
            runs.value = runs.value.mapIndexed { index, run -> if (index == 12) run.copy(roundTripMs = 999) else run }
        }
        settle()
        fun description() = nodes().single {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-chart"
        }.config[SemanticsProperties.ContentDescription].single()
        assertTrue(description().contains("999 ms"))
        host.get().runOnUiThread { format.value = countingFormat(Locale.FRANCE) }
        settle()
        val frenchDate = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, Locale.FRANCE)
            .format(Date(middle))
        assertTrue(description().contains("$frenchDate: 999 ms"))
    }

    @Test fun savedHistoryRefreshShowsEmptyFailureRecoveryDeletionAndConnectionRetirement() {
        val store = InMemoryTracePathStore { T0 }
        val path = savedPath(Bytes.of(0xAA, 0xBB), hashSize = 2, radioId = RADIO)
        store.insert(path)
        var refreshGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val feature = FakeTraceDependencies().apply {
            device = device()
            this.store = object : TracePathPersisting by store {
                override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                    refreshGate?.await()
                    return store.fetchSavedTracePath(key)
                }
            }
        }
        val connection = mutableStateOf(TracePathScreen.Dependencies(
            feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
        ))
        content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(C.contactsResultsViewRuns(host.get().resources, 0))
        assertTrue(texts().contains(host.get().getString(com.meshcoreone.android.core.l10n.R.string.trace_history_empty)))
        assertTrue(texts().contains("AABB"))
        assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-chart" })
        store.failure = java.io.IOException("held history read failed")
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        refreshGate = gate
        clickTag("trace-history-refresh")
        assertTrue(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-loading" })
        assertTrue(nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-refresh" }
            .config.contains(SemanticsProperties.Disabled))
        refreshGate = null
        gate.complete(Unit)
        settle()
        assertTrue(announcementTexts().contains(host.get().getString(T.toolsSavedPathsLoadFailed)))
        store.failure = null
        val first = run(T0)
        store.insert(path.copy(runs = com.meshcoreone.android.core.model.SnapshotList.of(first)))
        clickTag("trace-history-refresh")
        assertFalse(texts().contains(host.get().getString(com.meshcoreone.android.core.l10n.R.string.trace_history_empty)))
        assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-chart" })
        assertFalse(announcementTexts().contains(host.get().getString(T.toolsSavedPathsLoadFailed)))
        store.paths.clear()
        clickTag("trace-history-refresh")
        assertTrue(texts().contains(host.get().getString(com.meshcoreone.android.core.l10n.R.string.trace_history_missing)))
        assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("trace-run:") })
        host.get().runOnUiThread {
            feature.device = device().copy(radioId = RadioId(UUID.randomUUID()))
            connection.value = connection.value.copy(radioId = feature.device?.radioId, generation = 2)
        }
        settle()
        assertFalse(ShadowDialog.getShownDialogs().any { it.isShowing }, "Switching radio retires the history sheet")
    }

    @Test
    @Config(qualifiers = "en-rUS-w840dp-h1200dp-mdpi")
    fun savedHistoryContentSupportsExpandedDoubleFontAndDoesNotChartFailedRuns() {
        val history = SavedPathDetailState(savedPath(Bytes.of(0xAA), runs = listOf(
            run(T0, success = false), run(T0.minusSeconds(1), 250),
        )), isVerifiedFromStore = true)
        content.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme { SavedTraceHistoryContent(history, {}, {}, {}) }
            }
        }
        settle()
        assertFalse(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-chart" })
        assertTrue(texts().contains("250 ms"))
        scrollToIndex(2)
        assertTrue(texts().contains(host.get().getString(C.contactsPathDetailFailed)))
        val rows = nodes().filter { it.config.getOrElse(SemanticsProperties.TestTag) { "" }.startsWith("trace-run:") }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.boundsInRoot.width > 0f && it.boundsInRoot.height >= 48f })
    }

    @Test fun deletedHistoryAndOpenRunStayHiddenDuringRetryFailureAndRetainedRowActions() {
        val run = run(T0)
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO, runs = listOf(run))
        val store = InMemoryTracePathStore { T0 }.apply { insert(path) }
        var gate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val feature = FakeTraceDependencies().apply {
            device = device()
            this.store = object : TracePathPersisting by store {
                override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                    gate?.await()
                    return store.fetchSavedTracePath(key)
                }
            }
        }
        val connection = TracePathScreen.Dependencies(feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1)
        content.setContent { MaterialTheme { TracePathScreen(connection) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(C.contactsResultsViewRuns(host.get().resources, 1))
        val refresh = assertNotNull(nodes().single {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-refresh"
        }.config[SemanticsActions.OnClick].action)
        scrollToIndex(2)
        val retainedRun = assertNotNull(nodes().single {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-run:${run.id}"
        }.config[SemanticsActions.OnClick].action)
        clickTag("trace-run:${run.id}")
        assertTrue(texts().contains(host.get().getString(C.contactsPathDetailRunDetails)))
        store.paths.clear()
        host.get().runOnUiThread { assertTrue(refresh()) }
        settle()
        assertTrue(texts().contains(host.get().getString(com.meshcoreone.android.core.l10n.R.string.trace_history_missing)))
        assertNoSavedHistoryData()
        val retry = kotlinx.coroutines.CompletableDeferred<Unit>()
        gate = retry
        store.failure = java.io.IOException("retry after deletion")
        clickTag("trace-history-refresh")
        assertTrue(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history-loading" })
        assertNoSavedHistoryData()
        gate = null
        retry.complete(Unit)
        settle()
        assertTrue(announcementTexts().contains(host.get().getString(T.toolsSavedPathsLoadFailed)))
        assertNoSavedHistoryData()
        host.get().runOnUiThread { assertTrue(retainedRun()) }
        settle()
        assertNoSavedHistoryData()
        store.failure = null
        store.insert(path)
        clickTag("trace-history-refresh")
        assertTrue(texts().contains(host.get().getString(C.contactsPathDetailRunDetails)))
        assertTrue(texts().contains("-2.00 dB"))
    }

    @Test fun openHistoryAndRunDetailsRestoreByRadioAndPathIdentityAfterActivityRecreation() {
        val selected = run(T0, 123)
        val path = savedPath(Bytes.of(0xAA, 0xBB), hashSize = 2, radioId = RADIO, runs = listOf(
            run(T0.minusSeconds(1), 456), selected,
        )).copy(name = "Restored history")
        val store = InMemoryTracePathStore { T0 }.apply { insert(path) }
        val reads = mutableListOf<EntityKey>()
        host.get().installSavedPaths(object : TracePathPersisting by store {
            override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                reads += key
                return store.fetchSavedTracePath(key)
            }
        })
        host.get().useEmptyDirectory()
        host.get().mountTraceScreen()
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(C.contactsResultsViewRuns(host.get().resources, 2))
        assertTrue(texts().contains(path.name))
        assertEquals(listOf(EntityKey(RADIO, path.id)), reads)
        scrollToIndex(2)
        clickTag("trace-run:${selected.id}")
        assertTrue(texts().contains("123 ms"))
        assertFalse(texts().contains("456 ms"))
        val oldActivity = host.get()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertNotSame(oldActivity, host.get())
        assertEquals(List(2) { EntityKey(RADIO, path.id) }, reads, "The restored route must fetch by its saved identity")
        assertTrue(texts().contains(host.get().getString(C.contactsPathDetailRunDetails)))
        assertTrue(texts().contains("123 ms"))
        assertFalse(texts().contains("456 ms"))
        assertTrue(texts().contains("-2.00 dB"))
        click(host.get().getString(L.commonDone))
        assertTrue(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history" })
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertEquals(List(3) { EntityKey(RADIO, path.id) }, reads)
        assertTrue(nodes().any { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "trace-history" })
        assertFalse(texts().contains(host.get().getString(C.contactsPathDetailRunDetails)))
        scrollToIndex(0)
        assertTrue(texts().contains(path.name))
        assertTrue(texts().contains("AABB"))
        assertTrue(host.get().sender.sent.isEmpty(), "Restoring history must not execute a trace")
    }

    @Test fun restoredHistoryDoesNotResolveTheSamePathIdAgainstADifferentRadio() {
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO, runs = listOf(run(T0)))
        val otherRadio = RadioId(UUID.randomUUID())
        val store = InMemoryTracePathStore { T0 }.apply { insert(path) }
        val reads = mutableListOf<EntityKey>()
        host.get().installSavedPaths(object : TracePathPersisting by store {
            override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
                reads += key
                return store.fetchSavedTracePath(key)
            }
        })
        host.get().mountTraceScreen()
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(C.contactsResultsViewRuns(host.get().resources, 1))
        assertEquals(listOf(EntityKey(RADIO, path.id)), reads)
        store.insert(path.copy(radioId = otherRadio, name = "Other radio history"))
        host.get().useOtherRadioOnRecreation(otherRadio)
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertEquals(listOf(EntityKey(RADIO, path.id)), reads, "The restored route must retain its originating radio identity")
        assertFalse(ShadowDialog.getShownDialogs().any { it.isShowing })
        assertFalse(texts().contains("Other radio history"))
    }

    private fun assertNoSavedHistoryData() {
        val tags = nodes().map { it.config.getOrElse(SemanticsProperties.TestTag) { "" } }
        assertFalse(tags.any { it.startsWith("trace-run:") || it == "trace-run-detail" || it == "trace-history-chart" })
        assertFalse(texts().contains(host.get().getString(C.contactsPathDetailRunDetails)))
    }

    @Test fun savedDeleteConfirmationCannotActAfterReconnect() {
        for (sameRadio in listOf(false, true)) {
            val nextRadio = if (sameRadio) RADIO else RadioId(UUID.randomUUID())
            val store = InMemoryTracePathStore { T0 }
            val oldPath = savedPath(Bytes.of(0xAA), radioId = RADIO).copy(name = "Old path")
            val newPath = savedPath(Bytes.of(0xBB), radioId = nextRadio).copy(name = "New path")
            store.insert(oldPath)
            store.insert(newPath)
            val feature = FakeTraceDependencies().apply {
                device = device()
                this.store = store
            }
            val connection = mutableStateOf(TracePathScreen.Dependencies(
                feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
            ))
            content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
            settle()
            click(host.get().getString(C.contactsTraceSaved))
            clickDescription("${host.get().getString(L.commonDelete)}: ${oldPath.name}")
            val oldConfirm = assertNotNull(button(host.get().getString(C.contactsCommonDelete))
                .config[SemanticsActions.OnClick].action)
            host.get().runOnUiThread {
                feature.device = device().copy(radioId = nextRadio)
                connection.value = connection.value.copy(radioId = nextRadio, generation = 2)
            }
            settle()
            click(host.get().getString(C.contactsTraceSaved))
            clickDescription("${host.get().getString(L.commonDelete)}: ${newPath.name}")
            host.get().runOnUiThread { assertTrue(oldConfirm()) }
            settle()
            assertTrue(texts().contains(C.contactsSavedPathsDeleteMessage(host.get().resources, newPath.name)))
            assertEquals(oldPath, store.paths[oldPath.id])
            assertEquals(newPath, store.paths[newPath.id])
            click(host.get().getString(C.contactsCommonDelete))
            assertFalse(store.paths.containsKey(newPath.id))
            assertTrue(store.paths.containsKey(oldPath.id))
        }
    }

    @Test fun savedDialogReportsLoadAndDeleteFailuresAndAllowsRecovery() {
        val store = InMemoryTracePathStore { T0 }
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO).copy(name = "Ridge route")
        store.insert(path)
        store.failure = java.io.IOException("storage unavailable")
        val diagnostics = RecordingDiagnostics()
        val feature = FakeTraceDependencies().apply {
            device = device()
            this.store = store
        }
        val connection = TracePathScreen.Dependencies(feature, MapRecentsStorage(), diagnostics, RADIO, 1)
        content.setContent { MaterialTheme { TracePathScreen(connection) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        val loadError = host.get().getString(T.toolsSavedPathsLoadFailed)
        assertTrue(texts().contains(loadError))
        assertTrue(announcementTexts().contains(loadError))
        assertFalse(texts().contains(host.get().getString(C.contactsTraceListEmptyPath)))
        assertFalse(texts().contains(path.name))

        store.failure = null
        click(host.get().getString(T.toolsLineOfSightRetry))
        assertFalse(texts().contains(loadError))
        assertTrue(texts().contains(path.name))

        store.failure = java.io.IOException("delete unavailable")
        val deleteLabel = "${host.get().getString(L.commonDelete)}: ${path.name}"
        confirmSavedDelete(deleteLabel)
        val deleteError = host.get().getString(T.toolsSavedPathsDeleteFailed)
        assertTrue(texts().contains(deleteError))
        assertTrue(announcementTexts().contains(deleteError))
        assertTrue(texts().contains(path.name))
        assertTrue(store.paths.containsKey(path.id))
        assertFalse(texts().contains(host.get().getString(T.toolsLineOfSightRetry)))

        store.failure = null
        confirmSavedDelete(deleteLabel)
        assertFalse(texts().contains(deleteError))
        assertFalse(store.paths.containsKey(path.id))
        assertEquals(listOf("loadSavedPaths", "deletePath"), diagnostics.failures.map { it.first })
    }

    @Test fun savedDialogRenameKeepsDraftOnStorageFailureAndRetries() {
        val store = InMemoryTracePathStore { T0 }
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO).copy(name = "Ridge route")
        store.insert(path)
        val diagnostics = RecordingDiagnostics()
        val feature = FakeTraceDependencies().apply {
            device = device()
            this.store = store
        }
        val connection = TracePathScreen.Dependencies(feature, MapRecentsStorage(), diagnostics, RADIO, 1)
        content.setContent { MaterialTheme { TracePathScreen(connection) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        clickDescription("${host.get().getString(C.contactsSavedPathsRename)}: ${path.name}")
        setInput("New route")
        store.failure = java.io.IOException("storage unavailable")
        click(host.get().getString(L.commonSave))
        assertEquals(path.name, store.paths.getValue(path.id).name)
        assertEquals("New route", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().contains(host.get().getString(T.toolsSavedPathsRenameFailed)))
        store.failure = null
        click(host.get().getString(L.commonSave))
        assertEquals("New route", store.paths.getValue(path.id).name)
        assertTrue(texts().contains("New route"))
        assertEquals(listOf("renamePath"), diagnostics.failures.map { it.first })
        clickDescription("${host.get().getString(C.contactsSavedPathsRename)}: New route")
        setInput("")
        click(host.get().getString(L.commonSave))
        assertEquals("", store.paths.getValue(path.id).name, "Swift permits empty names when renaming")
        val fallback = C.contactsPathNamePrefix(host.get().resources, "AA")
        assertTrue(texts().contains(fallback))
        clickDescription("${host.get().getString(C.contactsSavedPathsRename)}: $fallback")
        setInput("  ")
        click(host.get().getString(L.commonSave))
        assertEquals("  ", store.paths.getValue(path.id).name)
        assertTrue(texts().contains(fallback))
        clickDescription("${host.get().getString(L.commonDelete)}: $fallback")
        assertTrue(texts().contains(C.contactsSavedPathsDeleteMessage(host.get().resources, fallback)))
        click(host.get().getString(C.contactsCommonCancel))
        assertEquals("  ", store.paths.getValue(path.id).name)
        click(fallback)
        assertTrue(texts().contains("AA"))
    }

    @Test fun retainedRenameActionsCannotAffectANewSelection() {
        for (reopenSamePath in listOf(false, true)) {
            val store = InMemoryTracePathStore { T0 }
            val first = savedPath(Bytes.of(0xAA), radioId = RADIO).copy(name = "First route")
            val second = savedPath(Bytes.of(0xBB), radioId = RADIO).copy(name = "Second route")
            store.insert(first)
            store.insert(second)
            val feature = FakeTraceDependencies().apply {
                device = device()
                this.store = store
            }
            val connection = TracePathScreen.Dependencies(feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1)
            content.setContent { key(feature) { MaterialTheme { TracePathScreen(connection) } } }
            settle()
            click(host.get().getString(C.contactsTraceSaved))
            val rename = host.get().getString(C.contactsSavedPathsRename)
            val save = host.get().getString(L.commonSave)
            val cancel = host.get().getString(L.commonCancel)
            clickDescription("$rename: ${first.name}")
            val oldSave = assertNotNull(button(save).config[SemanticsActions.OnClick].action)
            val oldCancel = assertNotNull(button(cancel).config[SemanticsActions.OnClick].action)
            click(cancel)
            val selected = if (reopenSamePath) first else second
            clickDescription("$rename: ${selected.name}")
            setInput("Current draft")
            host.get().runOnUiThread {
                assertTrue(oldSave())
                assertTrue(oldCancel())
            }
            settle()
            assertEquals(first.name, store.paths.getValue(first.id).name)
            assertEquals(second.name, store.paths.getValue(second.id).name)
            assertEquals("Current draft", input().config[SemanticsProperties.EditableText].text)
            click(save)
            assertEquals("Current draft", store.paths.getValue(selected.id).name)
        }
    }

    @Test fun savedDialogAnnouncesDeleteFailureAfterScrolling() {
        val store = InMemoryTracePathStore { T0 }
        val paths = (0 until 25).map { index ->
            savedPath(Bytes.of(index), radioId = RADIO).copy(name = "Route $index").also(store::insert)
        }
        val feature = FakeTraceDependencies().apply {
            device = device()
            this.store = store
        }
        val connection = TracePathScreen.Dependencies(feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1)
        content.setContent { MaterialTheme { TracePathScreen(connection) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        scrollToIndex(20)
        store.failure = java.io.IOException("delete unavailable")
        val label = "${host.get().getString(L.commonDelete)}: ${paths[20].name}"
        confirmSavedDelete(label)
        val error = host.get().getString(T.toolsSavedPathsDeleteFailed)
        assertTrue(texts().contains(error))
        assertTrue(announcementTexts().contains(error))
        assertTrue(store.paths.containsKey(paths[20].id))
        store.failure = null
        confirmSavedDelete(label)
        assertFalse(texts().contains(error))
        assertFalse(store.paths.containsKey(paths[20].id))
    }

    @Test fun savedDialogRetiresVisibleRowsHeldLoadsAndRetainedActionsAcrossConnections() {
        for (sameRadio in listOf(false, true)) for (heldLoad in listOf(false, true)) {
            val nextRadio = if (sameRadio) RADIO else RadioId(UUID.randomUUID())
            val persistence = InMemoryTracePathStore { T0 }
            val oldPath = savedPath(Bytes.of(0xAA), radioId = RADIO).copy(name = "Radio A route")
            val newPath = savedPath(Bytes.of(0xBB), radioId = nextRadio).copy(name = "Radio B route")
            persistence.insert(oldPath)
            val gate = CompletableDeferred<Unit>()
            var fetches = 0
            val recorded = FakeTraceSender()
            val events = TraceResponseBroadcaster()
            val feature = FakeTraceDependencies().apply {
                device = device()
                directory = FakeNodeDirectory(contacts = listOf(contact(0xAA, name = "Tower"), contact(0xBB, name = "Ridge")))
                sender = recorded
                responses = { events.subscribe() }
                store = object : TracePathPersisting by persistence {
                    override suspend fun fetchSavedTracePaths(radioId: RadioId) =
                        persistence.fetchSavedTracePaths(radioId).also {
                            if (++fetches == 2 && heldLoad) gate.await()
                        }
                }
            }
            val connection = mutableStateOf(TracePathScreen.Dependencies(
                feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
            ))
            content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
            settle()
            click(host.get().getString(C.contactsTraceSaved))
            val oldSelect = assertNotNull(button(oldPath.name).config[SemanticsActions.OnClick].action)
            val deleteLabel = host.get().getString(L.commonDelete)
            val oldDelete = assertNotNull(actionButton("$deleteLabel: ${oldPath.name}").config[SemanticsActions.OnClick].action)
            if (heldLoad) {
                click(host.get().getString(L.commonDone))
                click(host.get().getString(C.contactsTraceSaved))
                assertFalse(texts().contains(oldPath.name))
            }
            host.get().runOnUiThread {
                persistence.insert(newPath)
                feature.device = device().copy(radioId = nextRadio)
                connection.value = connection.value.copy(radioId = nextRadio, generation = 2)
            }
            settle()
            assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
            gate.complete(Unit)
            settle()
            assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true, "A late load must not reopen an old dialog")
            click(host.get().getString(C.contactsTraceSaved))
            assertTrue(texts().contains(newPath.name))
            host.get().runOnUiThread {
                assertTrue(oldSelect())
                assertTrue(oldDelete())
            }
            settle()
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true, "Old selection must not dismiss the new dialog")
            assertTrue(texts().contains(newPath.name))
            assertTrue(persistence.paths.containsKey(oldPath.id))
            assertTrue(persistence.paths.containsKey(newPath.id))
            click(newPath.name)
            scrollToIndex(11)
            click(host.get().getString(C.contactsTraceListRunTrace))
            assertEquals(Bytes.of(0xBB), recorded.sent.single().path)
            host.get().runOnUiThread {
                assertTrue(oldSelect())
                assertTrue(oldDelete())
            }
            settle()
            events.yield(TraceResponse(
                traceInfo(recorded.sent.single().tag, listOf(node(0xBB, 6.0), node(null, 3.0))), nextRadio,
            ))
            settle()
            assertEquals(1, persistence.paths.getValue(newPath.id).runs.size)
            assertTrue(persistence.paths.getValue(oldPath.id).runs.isEmpty())
            click(host.get().getString(C.contactsTraceSaved))
            confirmSavedDelete("$deleteLabel: ${newPath.name}")
            assertFalse(persistence.paths.containsKey(newPath.id))
            assertTrue(persistence.paths.containsKey(oldPath.id), "Stale callbacks must never delete radio A's accepted data")
        }
    }

    @Test fun mountedSameTokenReloadRejectsOldCallbacksBeforeEffectRebind() {
        val token = com.meshcoreone.android.core.contracts.domain.SessionToken(
            com.meshcoreone.android.core.contracts.domain.ProcessEpoch(UUID.randomUUID()),
            com.meshcoreone.android.core.contracts.domain.Generation(1), RADIO,
        )
        var publishedVersion = 1
        val persistence = InMemoryTracePathStore { T0 }
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO).copy(name = "Before restore")
        persistence.insert(path)
        val deps = FakeTraceDependencies().apply {
            device = device()
            store = persistence
            directory = FakeNodeDirectory()
        }
        val feature = object : TracePathFeatureDependencies by deps {
            override fun sessionToken() = token
            override fun originatingSessionToken(generation: Int) = token.takeIf { generation == publishedVersion }
            override fun isOriginatingSessionCurrent(
                generation: Int,
                token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
            ) = generation == publishedVersion && token == this.sessionToken()
        }
        val captured = TracePathScreen.Dependencies(feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1)
        val connection = mutableStateOf(captured)
        content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        val oldSelect = assertNotNull(button(path.name).config[SemanticsActions.OnClick].action)
        val oldDelete = assertNotNull(actionButton("${host.get().getString(L.commonDelete)}: ${path.name}")
            .config[SemanticsActions.OnClick].action)
        val replacement = path.copy(name = "Restored replacement", pathBytes = Bytes.of(0xBB))
        persistence.insert(replacement)
        publishedVersion = 2
        host.get().runOnUiThread {
            assertTrue(oldSelect())
            assertTrue(oldDelete())
        }
        settle()
        assertEquals(replacement, persistence.paths[path.id])
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        host.get().runOnUiThread { connection.value = captured.copy(generation = 2) }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(replacement.name)
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        click(host.get().getString(C.contactsTraceSaved))
        confirmSavedDelete("${host.get().getString(L.commonDelete)}: ${replacement.name}")
        assertFalse(persistence.paths.containsKey(path.id))
    }

    @Test fun delayedSameRadioScreenBindingKeepsItsOriginatingGraph() = delayedScreenBinding(crossRadio = false)

    @Test fun delayedDifferentRadioScreenBindingKeepsItsOriginatingGraph() = delayedScreenBinding(crossRadio = true)

    private fun delayedScreenBinding(crossRadio: Boolean) {
        val epoch = com.meshcoreone.android.core.contracts.domain.ProcessEpoch(UUID.randomUUID())
        var token = com.meshcoreone.android.core.contracts.domain.SessionToken(
            epoch, com.meshcoreone.android.core.contracts.domain.Generation(1), RADIO,
        )
        var publishedToken = token
        var publishedVersion = 1
        val persistence = InMemoryTracePathStore { T0 }
        val recorded = FakeTraceSender()
        val deps = FakeTraceDependencies().apply {
            device = device()
            store = persistence
            sender = recorded
            directory = FakeNodeDirectory()
        }
        val feature = object : TracePathFeatureDependencies by deps {
            override fun sessionToken() = token
            override fun originatingSessionToken(generation: Int) = publishedToken.takeIf { generation == publishedVersion }
            override fun isOriginatingSessionCurrent(
                generation: Int,
                token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
            ) = generation == publishedVersion && token == publishedToken
        }
        val captured = TracePathScreen.Dependencies(
            feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
        )
        assertEquals(token, captured.originatingToken)
        val nextRadio = if (crossRadio) RadioId(UUID.randomUUID()) else RADIO
        token = token.copy(generation = com.meshcoreone.android.core.contracts.domain.Generation(2), radioId = nextRadio)
        publishedToken = token
        publishedVersion = 2
        deps.device = device().copy(radioId = nextRadio)
        val path = savedPath(Bytes.of(0xBB), radioId = nextRadio).copy(name = "New graph only")
        persistence.insert(path)
        val connection = mutableStateOf(captured)
        content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        assertFalse(texts().contains(path.name), "Delayed A configure must not expose B rows")
        assertTrue(recorded.sent.isEmpty())
        click(host.get().getString(L.commonDone))
        val fresh = captured.copy(radioId = nextRadio, generation = 2)
        assertEquals(token, fresh.originatingToken)
        host.get().runOnUiThread { connection.value = fresh }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(path.name)
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertFalse(texts().contains(host.get().getString(C.contactsTraceListEmptyPath)))
        click(host.get().getString(C.contactsTraceSaved))
        confirmSavedDelete("${host.get().getString(L.commonDelete)}: ${path.name}")
        assertFalse(persistence.paths.containsKey(path.id), "Fresh B origin permits the original delete action")
    }

    @Test fun savedRowActionsRejectANewLiveGraphBeforeTheScreenGenerationChanges() {
        val epoch = com.meshcoreone.android.core.contracts.domain.ProcessEpoch(UUID.randomUUID())
        var token = com.meshcoreone.android.core.contracts.domain.SessionToken(
            epoch, com.meshcoreone.android.core.contracts.domain.Generation(1), RADIO,
        )
        var publishedToken = token
        var publishedVersion = 1
        val persistence = InMemoryTracePathStore { T0 }
        val path = savedPath(Bytes.of(0xAA), radioId = RADIO).copy(name = "Retained graph route")
        persistence.insert(path)
        val deps = FakeTraceDependencies().apply {
            device = device()
            store = persistence
            directory = FakeNodeDirectory()
        }
        val feature = object : TracePathFeatureDependencies by deps {
            override fun sessionToken() = token
            override fun originatingSessionToken(generation: Int) = publishedToken.takeIf { generation == publishedVersion }
            override fun isOriginatingSessionCurrent(
                generation: Int,
                token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
            ) = generation == publishedVersion && token == publishedToken
        }
        val connection = mutableStateOf(TracePathScreen.Dependencies(
            feature, MapRecentsStorage(), RecordingDiagnostics(), RADIO, 1,
        ))
        assertEquals(token, connection.value.originatingToken)
        content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        val oldSelect = assertNotNull(button(path.name).config[SemanticsActions.OnClick].action)
        val deleteLabel = "${host.get().getString(L.commonDelete)}: ${path.name}"
        val oldDelete = assertNotNull(actionButton(deleteLabel).config[SemanticsActions.OnClick].action)
        token = token.copy(generation = com.meshcoreone.android.core.contracts.domain.Generation(2))
        host.get().runOnUiThread {
            assertTrue(oldSelect())
            assertTrue(oldDelete())
        }
        settle()
        assertTrue(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertEquals(path, persistence.paths[path.id])
        click(host.get().getString(L.commonDone))
        assertTrue(texts().contains(host.get().getString(C.contactsTraceListEmptyPath)))
        host.get().runOnUiThread {
            publishedToken = token
            publishedVersion = 2
            connection.value = connection.value.copy(generation = 2)
            assertEquals(token, connection.value.originatingToken)
        }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(path.name)
        assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true)
        assertFalse(texts().contains(host.get().getString(C.contactsTraceListEmptyPath)))
        click(host.get().getString(C.contactsTraceSaved))
        confirmSavedDelete(deleteLabel)
        assertFalse(persistence.paths.containsKey(path.id))
    }

    @Test fun mountedBatchSwitchRetiresSingleAndBatchOperationsWithoutExtraSends() {
        for (initialBatch in listOf(false, true)) {
            val harness = configured()
            val recorded = FakeTraceSender()
            harness.deps.sender = recorded
            harness.holder.addNode(contact(0xA1, name = "Alpha"))
            harness.holder.addNode(contact(0xB2, name = "Bravo"))
            harness.holder.setBatchEnabled(initialBatch)
            harness.holder.setBatchSize(2)
            mountPathList(harness)
            scrollToIndex(9)
            val execution = harness.start { harness.holder.runBatchTrace() }
            host.get().runOnUiThread {
                val batchSwitch = nodes().filter {
                    it.config.getOrElseNullable(SemanticsProperties.Role) { null } == Role.Switch
                }.last()
                assertTrue(assertNotNull(batchSwitch.config[SemanticsActions.OnClick].action)())
            }
            settle()
            assertEquals(!initialBatch, harness.state.batchEnabled)
            assertFalse(harness.state.isRunning)
            val changed = harness.state
            harness.respond(recorded.sent.single().tag, listOf(node(0xA1, 5.0), node(null, 3.0)), RADIO)
            harness.time.advanceBy(60.seconds)
            settle()
            assertTrue(execution.isCompleted)
            assertEquals(changed, harness.state)
            assertEquals(1, recorded.sent.size)
            assertNull(harness.state.result)
            assertTrue(harness.state.completedResults.isEmpty())
            harness.holder.setBatchSize(1)
            val next = harness.start { harness.holder.runBatchTrace() }
            assertEquals(2, recorded.sent.size)
            harness.respond(recorded.sent.last().tag, listOf(node(0xA1, 6.0), node(null, 3.0)), RADIO)
            settle()
            assertTrue(next.isCompleted)
            assertFalse(harness.state.isRunning)
            assertTrue(assertNotNull(harness.state.result).success)
            assertEquals(if (initialBatch) 0 else 1, harness.state.completedResults.size)
        }
    }

    @Test fun emptyAndWhitespacePasteClearPriorSuccessAndErrorsWithoutChangingDraft() {
        listOf("", " \n\t\r ", "\u200B\n \r\u200B").forEach { blank ->
            val harness = configured()
            mount(harness)
            click("Add Hop")
            paste("AA,FF,ZZ")
            click("Add nodes: AA")
            assertTrue(announcementTexts().containsAll(listOf("Adding as hop 2", "FF not found", "Invalid format: ZZ")))
            paste(blank)
            assertEquals("", input().config[SemanticsProperties.EditableText].text)
            assertTrue(announcementTexts().isEmpty())
            assertFalse(texts().any { it in listOf("Adding as hop 2", "FF not found", "Invalid format: ZZ") })
            assertEquals(listOf(Bytes.of(0xAA)), harness.state.outboundPath.map { it.hashBytes })
            assertEquals(1, harness.holder.hashSize)

            setInput("BB")
            paste(blank)
            assertEquals("BB", input().config[SemanticsProperties.EditableText].text)
            assertTrue(texts().contains("BB will be added"))
            assertFalse(button("Add nodes: BB").config.contains(SemanticsProperties.Disabled))
            assertTrue(announcementTexts().isEmpty())
            assertEquals(listOf(Bytes.of(0xAA)), harness.state.outboundPath.map { it.hashBytes })
            assertEquals(1, harness.holder.hashSize)
        }
    }

    @Test fun mountedLazyDisposalPreservesEveryRejectedCategoryAndCapturedSuccessUntilTheNextEdit() {
        host.get().mountTraceScreen()
        settle()
        click("Tower")
        click("Add Hop")
        assertTrue(input().config[SemanticsProperties.Focused])
        paste("AA,BB,FF,ZZ")
        click("Add nodes: BB")
        val feedback = listOf("Adding as hop 3", "Invalid format: ZZ", "FF not found", "AA already in path")
        assertEquals(feedback, announcementTexts())
        host.get().runOnUiThread { host.get().focusManager.clearFocus(force = true) }
        settle()
        assertFalse(input().config[SemanticsProperties.Focused])
        scrollToIndex(35)
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        scrollToIndex(0)
        assertFalse(input().config[SemanticsProperties.Focused])
        assertEquals("", input().config[SemanticsProperties.EditableText].text)
        assertEquals(feedback, announcementTexts())
        assertEquals(1, host.get().recents.values.values.single().size)
        setInput("AA,BB")
        assertTrue(announcementTexts().isEmpty())
        click("Done")
        click("Add Hop")
        assertTrue(input().config[SemanticsProperties.Focused])
        assertTrue(announcementTexts().isEmpty())
    }

    @Test fun lazyRemountDoesNotReapplyDraftInferenceAfterAnExplicitWidthChoice() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().runOnUiThread { host.get().focusManager.clearFocus(force = true) }
        settle()
        scrollToIndex(47)
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        click("1 B")
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
        scrollToIndex(0)
        assertEquals("AA00,BB00", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
    }

    @Test fun switchingBetweenListAndMapPreservesDraftAndPartialAddFeedback() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        setInput("AA00,BB00,FF00,GG")
        val preview = listOf("AA00 will be added", "BB00 will be added", "FF00 not found", "Invalid format: GG")
        assertTrue(texts().containsAll(preview))
        host.get().runOnUiThread { host.get().focusManager.clearFocus(force = true) }
        settle()
        assertFalse(input().config[SemanticsProperties.Focused])
        click("Map")
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        click("List")
        assertFalse(input().config[SemanticsProperties.Focused])
        assertEquals("AA00,BB00,FF00,GG", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(preview))
        click("Add nodes: AA00, BB00")
        val feedback = listOf("Adding as hop 3", "Invalid format: GG", "FF00 not found")
        assertEquals(feedback, announcementTexts())
        click("Map")
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        click("List")
        assertFalse(input().config[SemanticsProperties.Focused])
        assertEquals("", input().config[SemanticsProperties.EditableText].text)
        assertEquals(feedback, announcementTexts())
        assertEquals(2, host.get().recents.values.values.single().size)
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun leavingBeforeInitialFocusDoesNotRequestFocusOnListReturn() {
        host.get().mountTraceScreen()
        settle()
        host.get().runOnUiThread {
            val openEntry = assertNotNull(button("Add Hop").config[SemanticsActions.OnClick].action)
            val showMap = assertNotNull(button("Map").config[SemanticsActions.OnClick].action)
            assertTrue(openEntry())
            assertTrue(showMap())
        }
        settle()
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        click("List")
        assertEquals("", input().config[SemanticsProperties.EditableText].text)
        assertFalse(input().config[SemanticsProperties.Focused])
    }

    @Test fun actualActivityRecreationRestoresTwoByteDraftBeforePreviewAndSubmission() {
        assertRecreatedDraft("AA00,BB00", "2 B")
    }

    @Test fun actualActivityRecreationRestoresFourByteDraftBeforePreviewAndSubmission() {
        assertRecreatedDraft("AA000000,BB000000", "4 B")
    }

    @Test fun actualActivityRecreationPreservesAnExplicitOneByteModeOverAnInferredTwoByteDraft() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().runOnUiThread { host.get().focusManager.clearFocus(force = true) }
        settle()
        scrollToIndex(47)
        click("1 B")
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
        scrollToIndex(0)
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        val before = host.get()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertNotSame(before, host.get())
        assertEquals("AA00,BB00", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        scrollToIndex(47)
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
        scrollToIndex(0)
        host.get().setRadioHashMode(1u)
        setInput("AA")
        assertTrue(texts().contains("AA will be added"))
        assertFalse(button("Add nodes: AA").config.contains(SemanticsProperties.Disabled))
    }

    @Test fun actualActivityRestorationDoesNotApplyASavedOverrideToUnsupportedFirmware() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        assertTrue(texts().containsAll(listOf("AA00 will be added", "BB00 will be added")))
        host.get().setFirmware("v1.10.0")
        val before = host.get()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertNotSame(before, host.get())
        assertEquals("AA00,BB00", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun disconnectedRecreationsRetainAnInferredWidthUntilSupportedReconnection() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        assertTrue(texts().containsAll(listOf("AA00 will be added", "BB00 will be added")))
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")

        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        host.get().runOnUiThread { host.get().reconnect("v1.11.0") }
        settle()
        assertTrue(texts().containsAll(listOf("AA00 will be added", "BB00 will be added")))
        assertFalse(button("Add nodes: AA00, BB00").config.contains(SemanticsProperties.Disabled))
        click("Add nodes: AA00, BB00")
        assertEquals(2, host.get().recents.values.values.single().size)
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun disconnectedRecreationRetainsAnExplicitDefaultOverrideWhenTheRadioDefaultChanges() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().runOnUiThread { host.get().focusManager.clearFocus(force = true) }
        settle()
        scrollToIndex(47)
        click("1 B")
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
        scrollToIndex(0)
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")

        host.get().runOnUiThread { host.get().reconnect("v1.11.0", pathHashMode = 1u) }
        settle()
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        scrollToIndex(47)
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
        assertFalse(button("2 B").config[SemanticsProperties.Selected])
    }

    @Test fun disconnectedRestorationDiscardsTheOverrideOnlyAfterUnsupportedFirmwareIsKnown() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")

        host.get().runOnUiThread { host.get().reconnect("v1.10.0") }
        settle()
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        host.get().runOnUiThread { host.get().reconnect("v1.11.0") }
        settle()
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun pendingRestoreWaitsUntilTheGenerationMatchesTheActiveRadio() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        val activeRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
        host.get().runOnUiThread { host.get().reconnectWithStaleRadioSignal(activeRadio, RADIO) }
        settle()
        assertWaitingForConnection("AA00,BB00")
        scrollToIndex(47)
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
        scrollToIndex(0)
        assertWaitingForConnection("AA00,BB00")

        host.get().runOnUiThread { host.get().publishActiveRadioSignal() }
        settle()
        assertTrue(texts().containsAll(listOf("AA00 will be added", "BB00 will be added")))
        scrollToIndex(47)
        assertTrue(button("2 B").config[SemanticsProperties.Selected])
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun sameWidthPasteSupersedesPendingRestorationBeforeTheRadioSignalCatchesUp() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        val activeRadio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
        host.get().runOnUiThread { host.get().reconnectWithStaleRadioSignal(activeRadio, RADIO) }
        settle()
        assertWaitingForConnection("AA00,BB00")
        paste("AA,BB")
        host.get().runOnUiThread { host.get().publishActiveRadioSignal() }
        settle()
        assertEquals("AA,BB", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(listOf("AA will be added", "BB will be added")))
        click("Add nodes: AA, BB")
        assertTrue(announcementTexts().contains("Adding as hop 3"))
        assertEquals(2, host.get().recents.values.values.single().size)
        scrollToIndex(47)
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun disconnectBetweenSignalCheckAndRestorationRetainsTheSavedWidth() {
        val fixture = changeDeviceAfterRestoreCheck(null)
        val pendingMode = fixture.entry.restoredHashMode.takeIf { fixture.entry.restoringDraft }
        assertEquals(1u.toUByte(), fixture.harness.state.traceHashMode ?: pendingMode)

        host.get().runOnUiThread {
            fixture.dependencies.device = device()
            fixture.generation.intValue += 1
        }
        settle()
        assertEquals(1u.toUByte(), fixture.harness.state.traceHashMode)
    }

    @Test fun switchingRadioBetweenSignalCheckAndRestorationWaitsForTheMatchingSignal() {
        val next = device(radioId = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000b2")))
        val fixture = changeDeviceAfterRestoreCheck(next)
        assertTrue(fixture.entry.restoringDraft)
        assertEquals(1u.toUByte(), fixture.entry.restoredHashMode)
        assertNull(fixture.harness.state.traceHashMode)

        host.get().runOnUiThread {
            fixture.radioSignal.value = next.radioId
            fixture.generation.intValue += 1
        }
        settle()
        assertEquals(1u.toUByte(), fixture.harness.state.traceHashMode)
        assertFalse(fixture.entry.restoringDraft)
    }

    @Test fun reconnectReclassifiesAnUnchangedDraftWhenTheRadioDefaultWidthChanges() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA,BB")
        assertTrue(texts().containsAll(listOf("AA will be added", "BB will be added")))
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertEquals("AA,BB", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(listOf("AA not found", "BB not found")))

        host.get().runOnUiThread {
            host.get().useEmptyDirectory()
            host.get().reconnect("v1.11.0", pathHashMode = 1u)
        }
        settle()
        assertTrue(texts().containsAll(listOf("Invalid format: AA", "Invalid format: BB")))
        assertFalse(texts().contains("AA not found"))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun manualWidthChoiceWhileDisconnectedSupersedesThePendingRestoration() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        scrollToIndex(4)
        click("1 B")
        scrollToIndex(0)
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        host.get().runOnUiThread { host.get().reconnect("v1.11.0", pathHashMode = 1u) }
        settle()
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
    }

    @Test fun manualWidthChoiceSurvivesImmediateReconnectWithoutAnotherRecreation() {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        scrollToIndex(4)
        click("1 B")
        host.get().runOnUiThread { host.get().reconnect("v1.11.0", pathHashMode = 1u) }
        settle()
        scrollToIndex(0)
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        scrollToIndex(47)
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
    }

    @Test fun activityRecreationRestoresTheExplicitDefaultOverrideInTheActualHolder() {
        host.get().mountObservedEntry()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        val before = assertNotNull(host.get().observedHolder)
        host.get().runOnUiThread { before.setTraceHashMode(0u) }
        settle()
        assertEquals(0u.toUByte(), before.state.value.traceHashMode)
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        val after = assertNotNull(host.get().observedHolder)
        assertNotSame(before, after)
        assertEquals(0u.toUByte(), after.state.value.traceHashMode)
        host.get().setRadioHashMode(1u)
        setInput("AA")
        assertEquals(1, after.hashSize)
        assertEquals(0u.toUByte(), after.state.value.traceHashMode)
        assertTrue(texts().contains("AA will be added"))
        assertFalse(button("Add nodes: AA").config.contains(SemanticsProperties.Disabled))
    }

    @Test fun disconnectedRecreationRestoresTheExplicitDefaultMarkerInTheActualHolder() {
        host.get().mountObservedEntry()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        val before = assertNotNull(host.get().observedHolder)
        host.get().runOnUiThread { before.setTraceHashMode(0u) }
        settle()
        assertEquals(0u.toUByte(), before.state.value.traceHashMode)
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        val after = assertNotNull(host.get().observedHolder)
        assertNotSame(before, after)
        assertNull(after.state.value.traceHashMode)
        assertWaitingForConnection("AA00,BB00")

        host.get().runOnUiThread { host.get().reconnect("v1.11.0") }
        settle()
        assertEquals(0u.toUByte(), after.state.value.traceHashMode)
        host.get().setRadioHashMode(1u)
        setInput("AA")
        assertEquals(1, after.hashSize)
        assertEquals(0u.toUByte(), after.state.value.traceHashMode)
        assertTrue(texts().contains("AA will be added"))
        assertFalse(button("Add nodes: AA").config.contains(SemanticsProperties.Disabled))
    }

    @Test fun clearingThePathWhileDisconnectedSupersedesThePendingOverride() {
        host.get().mountObservedEntry()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        val holder = assertNotNull(host.get().observedHolder)
        host.get().runOnUiThread { holder.clearPath() }
        settle()
        assertNull(holder.state.value.traceHashMode)
        assertFalse(texts().contains("Not connected to device"))

        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        val restored = assertNotNull(host.get().observedHolder)
        assertNotSame(holder, restored)
        assertNull(restored.state.value.traceHashMode)
        assertFalse(texts().contains("Not connected to device"))
        host.get().runOnUiThread { host.get().reconnect("v1.11.0") }
        settle()
        assertNull(restored.state.value.traceHashMode)
        assertTrue(restored.state.value.outboundPath.isEmpty())
    }

    @Test fun loadingASavedPathWhileDisconnectedSupersedesThePendingOverride() {
        host.get().mountObservedEntry()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        val holder = assertNotNull(host.get().observedHolder)
        val saved = savedPath(Bytes.of(0xAA, 0x00, 0x00, 0x00), hashSize = 4)
        host.get().runOnUiThread { holder.loadSavedPath(saved) }
        settle()
        assertNull(holder.state.value.traceHashMode)
        assertEquals(saved, holder.state.value.activeSavedPath)
        assertFalse(texts().contains("Not connected to device"))

        host.get().runOnUiThread { host.get().reconnect("v1.11.0") }
        settle()
        assertNull(holder.state.value.traceHashMode)
        assertEquals(saved, holder.state.value.activeSavedPath)
        assertEquals(Bytes.of(0xAA, 0x00, 0x00, 0x00), holder.state.value.outboundPath.single().hashBytes)
    }

    @Test fun loadingAnEmptySavedPathCancelsPendingRestorationWithoutAStateChange() {
        host.get().mountObservedEntry()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        val holder = assertNotNull(host.get().observedHolder)
        val before = holder.state.value
        host.get().runOnUiThread { holder.loadSavedPath(savedPath(Bytes.EMPTY)) }
        settle()
        assertEquals(before, holder.state.value)
        assertFalse(texts().contains("Not connected to device"))

        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        val restored = assertNotNull(host.get().observedHolder)
        assertNotSame(holder, restored)
        assertFalse(texts().contains("Not connected to device"))
        host.get().runOnUiThread { host.get().reconnect("v1.11.0") }
        settle()
        assertNull(restored.state.value.traceHashMode)
        assertTrue(restored.state.value.outboundPath.isEmpty())
    }

    @Test fun choosingASavedPathFromTheMountedDialogSupersedesAPendingRestore() {
        val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
        val saved = savedPath(Bytes.of(0xAA, 0x00, 0x00, 0x00), hashSize = 4, radioId = radio)
        selectSavedPathWhileSignalIsStale(saved)

        host.get().runOnUiThread { host.get().publishActiveRadioSignal() }
        settle()
        assertEquals("AA00,BB00", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().contains("AA000000"))
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        scrollToIndex(47)
        assertTrue(button("4 B").config[SemanticsProperties.Selected])
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun choosingAnEmptySavedPathFromTheMountedDialogSupersedesAPendingRestore() {
        val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000b2"))
        selectSavedPathWhileSignalIsStale(savedPath(Bytes.EMPTY, radioId = radio))

        host.get().runOnUiThread { host.get().publishActiveRadioSignal() }
        settle()
        assertEquals("AA00,BB00", input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(listOf("Invalid format: AA00", "Invalid format: BB00")))
        assertTrue(button("No new nodes to add").config.contains(SemanticsProperties.Disabled))
        scrollToIndex(47)
        assertTrue(button("1 B").config[SemanticsProperties.Selected])
        assertTrue(host.get().sender.sent.isEmpty())
    }

    @Test fun disposingTheDisconnectedEntryCancelsItsPendingRestoration() {
        host.get().mountObservedEntry()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        val holder = assertNotNull(host.get().observedHolder)
        host.get().runOnUiThread { content.setContent { MaterialTheme {} } }
        settle()
        host.get().runOnUiThread {
            host.get().reconnect("v1.11.0")
            host.get().reconfigureObservedHolder()
        }
        settle()
        assertNull(holder.state.value.traceHashMode)
        assertTrue(holder.state.value.outboundPath.isEmpty())
    }

    @Test fun disposingTheEntryUnregistersItsHashModeActionObserver() {
        val harness = configured()
        var mountedEntry: TraceCodeInputState? = null
        content.setContent {
            val entry = rememberTraceCodeInputState(harness.holder)
            SideEffect { mountedEntry = entry }
        }
        settle()
        val entry = assertNotNull(mountedEntry)
        host.get().runOnUiThread { content.setContent { MaterialTheme {} } }
        settle()
        host.get().runOnUiThread {
            entry.restoredHashMode = 1u
            entry.restoringDraft = true
            harness.holder.clearPath()
        }
        settle()
        assertTrue(entry.restoringDraft)
        assertEquals(1u.toUByte(), entry.restoredHashMode)
    }

    @Test fun recreatedAndDestroyedActivityDoesNotStarveTheNextMountedScreen() {
        host.get().mountObservedEntry()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        val before = host.get()
        host.recreate()
        settle()
        assertNotSame(before, host.get())
        assertEquals("AA00,BB00", input().config[SemanticsProperties.EditableText].text)

        destroyHost()
        attachHost()
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA,BB")
        assertTrue(texts().containsAll(listOf("AA will be added", "BB will be added")))
        click("Add nodes: AA, BB")
        assertEquals(2, host.get().recents.values.values.single().size)
    }

    @Test fun restoringAnExplicitDefaultOverrideDoesNotInvalidateAnExistingPathOrResult() {
        val harness = configured()
        val saved = savedPath(Bytes.of(0xAA), hashSize = 1)
        val previous = result(emptyList())
        harness.holder.editStateForTesting { it.copy(activeSavedPath = saved, result = previous) }
        harness.holder.restoreTraceHashMode(0u)
        assertEquals(0u.toUByte(), harness.state.traceHashMode)
        assertEquals(saved, harness.state.activeSavedPath)
        assertEquals(previous, harness.state.result)
        harness.deps.device = device(pathHashMode = 1u)
        assertEquals(1, harness.holder.hashSize)
    }

    @Test fun sameWidthPasteNotifiesOnceWithoutInvalidatingAnExistingPathOrResult() {
        val harness = configured()
        harness.holder.loadSavedPath(savedPath(Bytes.of(0xAA), hashSize = 1))
        val previous = result(emptyList())
        harness.holder.editStateForTesting { it.copy(result = previous) }
        val before = harness.state
        var actions = 0
        val stopObserving = harness.holder.observeHashModeActions { actions++ }
        try {
            harness.holder.adoptHashSize("AA,BB")
            assertEquals(1, actions)
            assertEquals(before, harness.state)
            harness.holder.adoptHashSize("AA00,BB00")
            assertEquals(2, actions)
            assertEquals(2, harness.holder.hashSize)
        } finally {
            stopObserving()
        }
    }

    private fun assertRecreatedDraft(draft: String, mode: String) {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste(draft)
        val codes = draft.split(',')
        assertTrue(texts().containsAll(codes.map { "$it will be added" }))
        val before = host.get()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertNotSame(before, host.get())
        assertEquals(draft, input().config[SemanticsProperties.EditableText].text)
        assertTrue(texts().containsAll(codes.map { "$it will be added" }))
        assertFalse(texts().any { it.startsWith("Invalid format:") })
        assertFalse(button("Add nodes: ${codes.joinToString(", ")}").config.contains(SemanticsProperties.Disabled))
        click("Add nodes: ${codes.joinToString(", ")}")
        assertEquals(2, host.get().recents.values.values.single().size)
        assertTrue(host.get().sender.sent.isEmpty())
        scrollToIndex(48)
        assertTrue(button(mode).config[SemanticsProperties.Selected])
    }

    private data class RestoreRaceFixture(
        val harness: TraceHarness,
        val dependencies: FakeTraceDependencies,
        val generation: MutableIntState,
        val radioSignal: MutableState<RadioId?>,
        val entry: TraceCodeInputState,
    )

    private fun changeDeviceAfterRestoreCheck(replacement: DeviceDTO?): RestoreRaceFixture {
        val harness = TraceHarness()
        val dependencies = FakeTraceDependencies().apply { device = device() }
        val changingDependencies = object : TracePathFeatureDependencies by dependencies {
            var changeAfterRead = false
            override fun connectedDevice(): DeviceDTO? {
                val observed = dependencies.device
                if (changeAfterRead) {
                    changeAfterRead = false
                    dependencies.device = replacement
                }
                return observed
            }
        }
        harness.holder.configure(changingDependencies)
        val generation = mutableIntStateOf(1)
        val radioSignal = mutableStateOf<RadioId?>(RADIO)
        var savedEntry: TraceCodeInputState? = null
        content.setContent {
            val entry = rememberTraceCodeInputState(harness.holder, generation.intValue, radioSignal.value)
            SideEffect { savedEntry = entry }
        }
        settle()
        val entry = assertNotNull(savedEntry)
        host.get().runOnUiThread {
            entry.restoredHashMode = 1u
            entry.restoringDraft = true
            changingDependencies.changeAfterRead = true
            generation.intValue += 1
        }
        settle()
        assertFalse(changingDependencies.changeAfterRead)
        assertEquals(replacement, dependencies.device)
        return RestoreRaceFixture(harness, dependencies, generation, radioSignal, entry)
    }

    private fun selectSavedPathWhileSignalIsStale(saved: SavedTracePathDTO) {
        host.get().mountTraceScreen()
        settle()
        click("Add Hop")
        paste("AA00,BB00")
        host.get().startDisconnectedOnRecreation()
        host.recreate().visible()
        host.windowFocusChanged(true)
        settle()
        assertWaitingForConnection("AA00,BB00")
        host.get().runOnUiThread {
            host.get().installSavedPath(saved)
            host.get().reconnectWithStaleRadioSignal(saved.radioId, RADIO)
        }
        settle()
        assertWaitingForConnection("AA00,BB00")
        click("Saved")
        click(saved.name)
    }

    private fun assertWaitingForConnection(draft: String) {
        assertEquals(draft, input().config[SemanticsProperties.EditableText].text)
        assertTrue(button("Not connected to device").config.contains(SemanticsProperties.Disabled))
        assertFalse(texts().any { it.endsWith(" will be added") || it.startsWith("Invalid format:") })
    }

    private fun scrollToIndex(index: Int) {
        host.get().runOnUiThread {
            val list = nodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
            assertTrue(assertNotNull(list.config[SemanticsActions.ScrollToIndex].action)(index))
        }
        settle()
    }

    private fun configured(supportsOverride: Boolean = true) = TraceHarness(configureDependencies = true).also {
        it.deps.device = device(firmwareVersionString = if (supportsOverride) "v1.11.0" else "v1.10.0")
        it.holder.setContactsForTesting(listOf(contact(0xAA, name = "Tower"), contact(0xBB, name = "Ridge")))
    }

    private fun mount(harness: TraceHarness, fontScale: Float = 1f) {
        content.setContent {
            key(harness) {
                val state = harness.holder.state.collectAsStateWithLifecycle().value
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme { TraceCodeInput(state, harness.holder) }
                }
            }
        }
        settle()
    }

    private fun mountPathList(harness: TraceHarness, fontScale: Float = 1f, location: Coordinate? = null) {
        content.setContent {
            key(harness) {
                val state = harness.holder.state.collectAsStateWithLifecycle().value
                val codeInput = rememberTraceCodeInputState(harness.holder)
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme { TracePathListContent(state, harness.holder, codeInput, location = location) }
                }
            }
        }
        settle()
    }

    private fun connectedMapHarness() = configured().also {
        it.holder.bindConnection(RADIO, 1)
        it.holder.setContactsForTesting(listOf(contact(0xAA, name = "Tower")))
    }

    private fun mountPathMap(
        harness: TraceHarness,
        preferences: MapPreferenceStore = TraceCodeInputMapPreferences(),
        fontScale: Float = 1f,
        surface: TraceMapSurface = host.get().mapSurface,
    ) {
        val connection = TracePathScreen.Dependencies(
            harness.deps, harness.recents, harness.diagnostics, RADIO, 1, preferences,
        )
        content.setContent {
            key(harness) {
                val state = harness.holder.state.collectAsStateWithLifecycle().value
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme {
                        TracePathMapContent(state, harness.holder, preferences, null, harness.diagnostics, surface, connection)
                    }
                }
            }
        }
        settle()
    }

    private fun paste(text: String) {
        val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("trace codes", text))
        click("Paste from clipboard")
    }

    private fun clipboardText(): String? {
        val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        return clipboard.primaryClip?.getItemAt(0)?.coerceToText(host.get())?.toString()
    }

    private fun setClipboardText(text: String) {
        val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("test", text))
    }

    private fun setInput(text: String) {
        host.get().runOnUiThread {
            assertTrue(assertNotNull(input().config[SemanticsActions.SetText].action)(AnnotatedString(text)))
        }
        settle()
    }

    private fun click(label: String) {
        host.get().runOnUiThread {
            val node = button(label)
            assertFalse(node.config.contains(SemanticsProperties.Disabled), "$label must be enabled")
            assertTrue(assertNotNull(node.config[SemanticsActions.OnClick].action)())
        }
        settle()
    }

    private fun clickTag(tag: String) {
        host.get().runOnUiThread {
            val node = nodes().single { it.config.getOrElse(SemanticsProperties.TestTag) { "" } == tag }
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            assertTrue(assertNotNull(node.config[SemanticsActions.OnClick].action)())
        }
        settle()
    }

    private fun input(): SemanticsNode {
        val matches = nodes().filter { it.config.contains(SemanticsActions.SetText) }
        assertEquals(1, matches.size, "Expected one editable trace-code field")
        return matches[0]
    }

    private fun button(label: String): SemanticsNode {
        val matches = nodes().filter {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == label }
        }
        assertEquals(1, matches.size, "Expected one action labeled '$label'; visible text: ${texts()}")
        return matches[0]
    }

    private fun actionButton(description: String): SemanticsNode {
        val matches = nodes().filter {
            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() }.any { text ->
                text == description
            }
        }
        assertEquals(1, matches.size, "Expected one action described '$description'")
        return matches[0]
    }

    private fun clickDescription(description: String) {
        host.get().runOnUiThread {
            val node = actionButton(description)
            assertFalse(node.config.contains(SemanticsProperties.Disabled), "$description must be enabled")
            assertTrue(assertNotNull(node.config[SemanticsActions.OnClick].action)())
        }
        settle()
    }

    private fun confirmSavedDelete(description: String) {
        clickDescription(description)
        click(host.get().getString(C.contactsCommonDelete))
    }

    private fun pathActionDescription(action: Int, index: Int, hop: TracePathHop) =
        "${host.get().getString(action)}: ${C.contactsTraceListHopLabel(host.get().resources, index, hop.displayText)}"

    private fun settle() {
        repeat(12) {
            host.get().runOnUiThread {
                Snapshot.sendApplyNotifications()
                frameNanos += 16_000_000L
                frames.sendFrame(frameNanos)
            }
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
        host.get().runOnUiThread {
            val root = composeRoot()
            root.javaClass.methods.filter { it.name == "measureAndLayout" }.forEach { method ->
                if (method.parameterCount == 1) method.invoke(root, true) else method.invoke(root)
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
    }

    private fun texts() = nodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }

    private fun announcementTexts(): List<String> {
        fun textBelow(node: SemanticsNode): List<String> =
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } +
                node.children.flatMap(::textBelow)
        return nodes().filter {
            it.config.getOrElseNullable(SemanticsProperties.LiveRegion) { null } == LiveRegionMode.Polite
        }.flatMap(::textBelow)
    }

    private fun nodes(): List<SemanticsNode> {
        val root = composeRoot()
        val semantics = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return descendants(semantics.rootSemanticsNode)
    }

    private fun composeRoot(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) repeat(view.childCount) { index -> find(view.getChildAt(index))?.let { return it } }
            return null
        }
        val root = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }?.window?.decorView ?: content
        return checkNotNull(find(root)) { "Production trace UI must be attached" }
    }
}

class TraceCodeInputTestActivity : Activity(), SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this).apply { performAttach() }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry
    val frames = BroadcastFrameClock()
    lateinit var content: ComposeView
        private set
    internal lateinit var focusManager: FocusManager
    internal val recents = MapRecentsStorage()
    internal val sender = FakeTraceSender()
    internal val mapPreferences: MapPreferenceStore = TraceCodeInputMapPreferences()
    internal val mapSurface: TraceMapSurface = { _, _, _, _, _, overlay ->
        Box(Modifier.fillMaxSize(), content = overlay)
    }
    internal var observedHolder: TracePathStateHolder? = null
        private set
    private val contacts = listOf(contact(0xAA, name = "Tower"), contact(0xBB, name = "Ridge")) +
        (1..40).map { contact(it, name = "Repeater $it") }
    private val dependencies = FakeTraceDependencies().apply {
        device = device()
        directory = FakeNodeDirectory(contacts = contacts)
        this.sender = this@TraceCodeInputTestActivity.sender
    }
    private var traceScreenMounted = false
    private var observedEntryMounted = false
    private var firmware = "v1.11.0"
    private var connectionGeneration by mutableIntStateOf(1)
    private var disconnectOnRecreation = false
    private var retainSavedPaths = false
    private var otherRadioOnRecreation: RadioId? = null
    private var signaledRadioOverride: RadioId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedState.performRestore(savedInstanceState)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        (lastNonConfigurationInstance as? TracePathPersisting)?.let(::installSavedPaths)
        savedInstanceState?.getString("firmware")?.let(::setFirmware)
        savedInstanceState?.getString("otherRadioOnRecreation")?.let {
            dependencies.device = device().copy(radioId = RadioId(UUID.fromString(it)))
        }
        if (savedInstanceState?.getBoolean("disconnectedOnRecreation") == true) dependencies.device = null
        content = ComposeView(this).apply {
            id = android.R.id.custom
            setViewTreeLifecycleOwner(this@TraceCodeInputTestActivity)
            setViewTreeSavedStateRegistryOwner(this@TraceCodeInputTestActivity)
            setParentCompositionContext(createLifecycleAwareWindowRecomposer(
                coroutineContext = frames,
                lifecycle = this@TraceCodeInputTestActivity.lifecycle,
            ))
        }
        setContentView(content)
        if (savedInstanceState?.getBoolean("traceScreenMounted") == true) mountTraceScreen()
        else if (savedInstanceState?.getBoolean("observedEntryMounted") == true) mountObservedEntry()
    }

    internal fun mountTraceScreen() {
        traceScreenMounted = true
        observedEntryMounted = false
        content.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                TracePathScreen(
                    TracePathScreen.Dependencies(
                        dependencies, recents, RecordingDiagnostics(), signaledRadioId(), connectionGeneration,
                        mapPreferences = mapPreferences,
                    ),
                    mapSurface = mapSurface,
                )
            }
        }
    }

    internal fun mountObservedEntry() {
        traceScreenMounted = false
        observedEntryMounted = true
        content.setContent {
            val scope = rememberCoroutineScope()
            val holder = remember(dependencies) {
                TracePathStateHolder(scope, SystemTraceTimeSource, EnglishTraceStrings, recents, RecordingDiagnostics()).also {
                    it.configure(dependencies)
                    it.setContactsForTesting(contacts)
                }
            }
            LaunchedEffect(holder, connectionGeneration) { holder.configure(dependencies) }
            SideEffect { observedHolder = holder }
            focusManager = LocalFocusManager.current
            val state = holder.state.collectAsStateWithLifecycle().value
            val entry = rememberTraceCodeInputState(holder, connectionGeneration, signaledRadioId())
            MaterialTheme { TraceCodeInput(state, holder, entry) }
        }
    }

    internal fun setFirmware(version: String) {
        firmware = version
        dependencies.device = device(firmwareVersionString = version)
    }

    internal fun setRadioHashMode(mode: UByte) {
        dependencies.device = device(firmwareVersionString = firmware, pathHashMode = mode)
    }

    internal fun startDisconnectedOnRecreation() {
        disconnectOnRecreation = true
    }

    internal fun useEmptyDirectory() {
        dependencies.directory = FakeNodeDirectory()
    }

    internal fun installSavedPath(path: SavedTracePathDTO) {
        dependencies.store = InMemoryTracePathStore { T0 }.apply { insert(path) }
    }

    internal fun installSavedPaths(store: TracePathPersisting) {
        dependencies.store = store
        retainSavedPaths = true
    }

    internal fun useOtherRadioOnRecreation(radioId: RadioId) {
        otherRadioOnRecreation = radioId
    }

    override fun onRetainNonConfigurationInstance(): Any? = if (retainSavedPaths) dependencies.store else null

    private fun signaledRadioId(): RadioId? = signaledRadioOverride ?: dependencies.device?.radioId

    internal fun reconnectWithStaleRadioSignal(activeRadio: RadioId, signaledRadio: RadioId) {
        firmware = "v1.11.0"
        dependencies.device = device(firmwareVersionString = firmware, radioId = activeRadio)
        signaledRadioOverride = signaledRadio
        connectionGeneration += 1
    }

    internal fun publishActiveRadioSignal() {
        signaledRadioOverride = null
        connectionGeneration += 1
    }

    internal fun reconnect(version: String, pathHashMode: UByte = 0u) {
        firmware = version
        dependencies.device = device(firmwareVersionString = version, pathHashMode = pathHashMode)
        connectionGeneration += 1
    }

    internal fun reconfigureObservedHolder() {
        checkNotNull(observedHolder).configure(dependencies)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("traceScreenMounted", traceScreenMounted)
        outState.putBoolean("observedEntryMounted", observedEntryMounted)
        outState.putString("firmware", firmware)
        outState.putString("otherRadioOnRecreation", otherRadioOnRecreation?.value?.toString())
        outState.putBoolean("disconnectedOnRecreation", disconnectOnRecreation || dependencies.device == null)
        savedState.performSave(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
    }

    override fun onResume() {
        super.onResume()
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onPause() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        super.onPause()
    }

    override fun onStop() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        super.onStop()
    }

    override fun onDestroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        content.disposeComposition()
        frames.cancel()
        super.onDestroy()
    }
}

private class TraceCodeInputMapPreferences : MapPreferenceStore {
    var clusteringFailure: Exception? = null
    private val state = MutableStateFlow(
        MapPreferences(
            MapFilterState.seed(MapFilterHost.TRACE_PATH),
            clustering = true,
            labels = true,
            appearance = MapColorPreference.SYSTEM,
        ),
    )

    override fun observe(host: MapFilterHost) = state

    override suspend fun setFilter(host: MapFilterHost, filter: MapFilterState) {
        state.update { it.copy(filter = filter.sanitized(host)) }
    }

    override suspend fun updateFilter(host: MapFilterHost, transform: (MapFilterState) -> MapFilterState) {
        state.update { it.copy(filter = transform(it.filter).sanitized(host)) }
    }

    override suspend fun setClustering(enabled: Boolean) {
        clusteringFailure?.let { throw it }
        state.update { it.copy(clustering = enabled) }
    }

    override suspend fun setLabels(enabled: Boolean) {
        state.update { it.copy(labels = enabled) }
    }
}
