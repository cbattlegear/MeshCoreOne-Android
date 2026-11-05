// AndroidOnly: WP-313 Mounted current and disappeared neighbor confidence disclosure.
package com.meshcoreone.android.feature.remotenodes.ui

import android.os.Looper
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.NeighborBaseline
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NodeLocationFix
import com.meshcoreone.android.core.model.NodeStatusMetrics
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotSet
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.Neighbour
import com.meshcoreone.android.core.protocol.event.NeighboursResponse
import com.meshcoreone.android.feature.remotenodes.dependencies.NodeSnapshotPort
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesCatalog
import com.meshcoreone.android.feature.remotenodes.dependencies.RemoteNodesFeatureDependencies
import com.meshcoreone.android.core.maps.MapPresentationState
import com.meshcoreone.android.feature.remotenodes.status.FakeFaults
import com.meshcoreone.android.feature.remotenodes.status.RepeaterStatusStateHolder
import com.meshcoreone.android.feature.remotenodes.status.contact
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.session
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowViewRootImpl

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RemoteNeighborConfidenceComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var previousLocale: Locale
    private var previousAnimationScale = 1f
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun openWindow() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        activity.get().actionBar?.hide()
        val resolver = activity.get().contentResolver
        previousAnimationScale = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        activity.windowFocusChanged(true)
        assertTrue(activity.get().window.decorView.hasWindowFocus())
    }

    @After fun closeWindow() {
        Settings.Global.putFloat(activity.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, previousAnimationScale)
        activity.windowFocusChanged(false)
        activity.pause().stop().destroy()
        scope.cancel()
        Locale.setDefault(previousLocale)
    }

    @Test fun currentAndDisappearedFallbacksAreIdentifiedAtDefaultFont() = exercise(1f)

    @Test fun currentAndDisappearedFallbacksAreIdentifiedAtDoubleFont() = exercise(2f)

    private fun text(id: Int) = activity.get().getString(id)

    private fun named(prefix: Int, suffix: Int, name: String) = contact(
        publicKey = Bytes(byteArrayOf(prefix.toByte(), suffix.toByte()) + ByteArray(30)),
        name = name,
    ).copy(id = UUID(0, (prefix.toLong() shl 8) or suffix.toLong()))

    private fun assertWarning(name: String) {
        compose.onNodeWithContentDescription("$name, ${text(L.remoteNodesStatusPossibleMatch)}")
            .performScrollTo().assertIsDisplayed().assertHasClickAction()
            .assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .assert(SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription, text(L.remoteNodesStatusPossibleMatchExplanation),
            ))
    }

    private fun openExplanation(name: String) {
        assertWarning(name)
        compose.onNodeWithContentDescription("$name, ${text(L.remoteNodesStatusPossibleMatch)}").performClick()
        compose.onNodeWithText(text(L.remoteNodesStatusPossibleMatchExplanation)).assertIsDisplayed()
    }

    private fun dismissExplanation() {
        compose.onNode(hasText(text(L.remoteNodesDone)) and hasAnyAncestor(isDialog())).performClick()
        compose.onNodeWithText(text(L.remoteNodesStatusPossibleMatchExplanation)).assertDoesNotExist()
    }

    private fun attachAuthenticationWindow() {
        compose.runOnUiThread {
            Snapshot.sendApplyNotifications()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnUiThread {
            val host = activity.get().window.decorView
            val view = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
            assertTrue(view.isAttachedToWindow)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(host.height, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            val root = View::class.java.getMethod("getViewRootImpl").invoke(view)
            Shadow.extract<ShadowViewRootImpl>(root).callWindowFocusChanged(true)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(view.hasWindowFocus())
        }
        repeat(3) { frame ->
            compose.mainClock.advanceTimeByFrame()
            compose.runOnUiThread {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                val host = activity.get().window.decorView
                val dialog = requireNotNull(ShadowDialog.getLatestDialog().window).decorView
                println("TS07 auth frame=$frame host=${host.width}x${host.height} " +
                    "dialog=${dialog.width}x${dialog.height} requested=${dialog.isLayoutRequested}")
            }
        }
    }

    @Test fun statusRouteKeepsExactAndUnknownHopsDistinctAndClosesStaleExplanation() {
        val node = session()
        val target = contact(publicKey = node.publicKey, outPathLength = 3u, outPath = Bytes.of(0x10, 0x20, 0x30))
        val alpha = named(0x10, 1, "Route Alpha")
        val beta = named(0x10, 2, "Route Beta")
        val exact = named(0x20, 1, "Exact route")
        val catalog = mutableStateOf(RemoteNodesCatalog(listOf(target, alpha, beta, exact), emptyList(), listOf(node)))
        val repeater = RepeaterStatusStateHolder(VirtualClock(), FakeFaults, scope)
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MeshCoreTheme(motionScale = 0f) {
                    RemoteNodeStatusContent(node, repeater.helper, repeater, null, catalog.value, false, Modifier.fillMaxSize(), { _, _, _, _, _, _, _ -> })
                }
            }
        }
        compose.onNodeWithText("10 → 20 → 30").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Exact route").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(L.remoteNodesAuthPathHopUnknown)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Exact route, ${text(L.remoteNodesStatusPossibleMatch)}").assertDoesNotExist()
        compose.onAllNodesWithText(text(L.remoteNodesStatusPossibleMatchTitle)).assertCountEquals(1)
        openExplanation("Route Alpha")
        compose.runOnIdle { catalog.value = catalog.value.copy(contacts = listOf(target, alpha, exact)) }
        compose.onNodeWithText(text(L.remoteNodesStatusPossibleMatchExplanation)).assertDoesNotExist()
        compose.onAllNodesWithText(text(L.remoteNodesStatusPossibleMatchTitle)).assertCountEquals(0)
        compose.onNodeWithText("Route Alpha").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { catalog.value = catalog.value.copy(contacts = listOf(target, exact)) }
        compose.onAllNodesWithText(text(L.remoteNodesAuthPathHopUnknown)).assertCountEquals(2)
        compose.onNodeWithText("Route Alpha").assertDoesNotExist()
        compose.onNodeWithText("10 → 20 → 30").performScrollTo().assertIsDisplayed()
    }

    @Test fun unplottableConfidenceKeepsMapIdentityHonestAndSurvivesBackToStatus() {
        val node = session(latitude = 37.0, longitude = -122.0)
        val catalog = RemoteNodesCatalog(
            listOf(named(0x10, 1, "Map Alpha"), named(0x10, 2, "Map Beta"), named(0x20, 1, "Exact unlocated")),
            emptyList(), listOf(node),
        )
        val repeater = RepeaterStatusStateHolder(VirtualClock(), FakeFaults, scope).apply {
            helper.setSession(node)
            setNeighborsExpanded(true)
        }
        runBlocking {
            repeater.handleNeighboursResponse(NeighboursResponse(
                node.publicKey.prefix(6), Bytes.of(0, 0, 0, 1), 3,
                listOf(Neighbour(Bytes.of(0x10), 30, 5.5), Neighbour(Bytes.of(0x20), 30, 6.5), Neighbour(Bytes.of(0x30), 30, 7.5)),
            ))
        }
        var plotted: MapPresentationState? = null
        val map: RemoteNodesMapSurface = { state, _, _, _, _, _, modifier ->
            plotted = state
            Box(modifier)
        }
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MeshCoreTheme(motionScale = 0f) {
                    RemoteNodeStatusContent(node, repeater.helper, repeater, null, catalog, false, Modifier.fillMaxSize(), map)
                }
            }
        }
        compose.onNodeWithText(text(L.remoteNodesStatusViewOnMap)).performScrollTo().performClick()
        compose.onNodeWithText("Exact unlocated").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("30").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Exact unlocated, ${text(L.remoteNodesStatusPossibleMatch)}").assertDoesNotExist()
        compose.onNodeWithContentDescription("30, ${text(L.remoteNodesStatusPossibleMatch)}").assertDoesNotExist()
        compose.onAllNodesWithText(text(L.remoteNodesStatusPossibleMatchTitle)).assertCountEquals(1)
        compose.runOnIdle {
            assertEquals("Only the known center may be plotted", 1, plotted!!.points.size)
            assertEquals(node.name, plotted!!.points.single().label)
            assertTrue(plotted!!.lines.isEmpty())
        }
        openExplanation("Map Alpha")
        dismissExplanation()
        compose.onNodeWithText(text(L.remoteNodesDone)).performScrollTo().performClick()
        compose.onNodeWithText(text(L.remoteNodesStatusNeighbors)).performScrollTo().assertIsDisplayed()
        assertWarning("Map Alpha")
        compose.onNodeWithText(text(L.remoteNodesStatusUnknown)).performScrollTo().assertIsDisplayed()
    }

    @Test fun authenticationUsesTheSameConfidenceAndFloodRoutingHidesInactiveHops() {
        val node = session()
        val target = contact(publicKey = node.publicKey, outPathLength = 3u, outPath = Bytes.of(0x10, 0x20, 0x30))
        val catalog = RemoteNodesCatalog(
            listOf(target, named(0x10, 1, "Login Alpha"), named(0x10, 2, "Login Beta"), named(0x20, 1, "Exact login")),
            emptyList(), emptyList(),
        )
        val dependencies = object : RemoteNodesFeatureDependencies {
            override val clock = VirtualClock()
            override val faults = FakeFaults
            override fun repeaterAdmin() = null
            override fun roomAdmin() = null
            override fun binaryTelemetry() = null
            override fun nodeSnapshots() = null
            override fun contactOcv() = null
            override fun historyStore() = null
            override fun login() = null
            override fun connectedRadioId() = node.radioId
            override fun deviceHashSize() = 1
        }
        var cancelled = 0
        activity.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                Box(Modifier.fillMaxSize()) {
                    RemoteNodeAuthenticationDialog(target, dependencies, catalog, { cancelled++ }, { error("Must not authenticate") })
                }
            }
        }
        attachAuthenticationWindow()
        compose.onNodeWithText("Exact login").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Exact login, ${text(L.remoteNodesStatusPossibleMatch)}").assertDoesNotExist()
        compose.onNodeWithText(text(L.remoteNodesAuthPathHopUnknown)).performScrollTo().assertIsDisplayed()
        openExplanation("Login Alpha")
        dismissExplanation()
        compose.onNodeWithText(text(L.remoteNodesAuthFloodRouting)).performScrollTo().performClick()
        compose.onNodeWithText("10 → 20 → 30").assertDoesNotExist()
        compose.onAllNodesWithText(text(L.remoteNodesStatusPossibleMatchTitle)).assertCountEquals(0)
        compose.onNodeWithText(text(L.remoteNodesAuthFloodRouting)).performClick()
        assertWarning("Login Alpha")
        compose.onNodeWithText(text(L.remoteNodesCancel)).performClick()
        assertEquals(1, cancelled)
    }

    private fun exercise(fontScale: Float) {
        val node = session().copy(id = UUID(0, 1))
        val clock = VirtualClock()
        val gone = listOf(
            NeighborSnapshotEntry(Bytes.of(0x40), 2.0, 60),
            NeighborSnapshotEntry(Bytes.of(0x50), 3.0, 60),
            NeighborSnapshotEntry(Bytes.of(0x60), 4.0, 60),
        )
        val previous = NodeStatusSnapshotDTO(
            id = UUID(0, 6),
            nodePublicKey = node.publicKey,
            timestamp = clock.now.minusSeconds(60),
            neighborSnapshots = gone.snapshot(),
        )
        val captured = mutableListOf<List<NeighborSnapshotEntry>>()
        val snapshots = object : NodeSnapshotPort {
            override suspend fun recordSnapshot(
                nodePublicKey: Bytes,
                status: NodeStatusMetrics?,
                telemetry: SnapshotList<TelemetrySnapshotEntry>?,
                neighbors: SnapshotList<NeighborSnapshotEntry>?,
                location: NodeLocationFix?,
            ): UUID {
                assertEquals(node.publicKey, nodePublicKey)
                neighbors?.let { captured += it.toList() }
                return UUID(0, 7)
            }

            override suspend fun neighborBaseline(nodePublicKey: Bytes): NeighborBaseline {
                assertEquals(node.publicKey, nodePublicKey)
                return NeighborBaseline(previous, gone.map { it.publicKeyPrefix }.snapshotSet())
            }

            override suspend fun previousStatusSnapshot(nodePublicKey: Bytes, before: Instant): NodeStatusSnapshotDTO? = null
            override suspend fun fetchSnapshots(nodePublicKey: Bytes, since: Instant?): SnapshotList<NodeStatusSnapshotDTO> =
                listOf(previous).snapshot()
        }
        fun named(prefix: Int, suffix: Int, name: String) = contact(
            publicKey = Bytes(byteArrayOf(prefix.toByte(), suffix.toByte()) + ByteArray(30)),
            name = name,
        ).copy(id = UUID(0, (prefix.toLong() shl 8) or suffix.toLong()))
        val catalog = RemoteNodesCatalog(
            contacts = listOf(
                named(0x10, 1, "Current Alpha"), named(0x10, 2, "Current Beta"),
                named(0x20, 1, "Exact current"),
                named(0x40, 1, "Gone Alpha"), named(0x40, 2, "Gone Beta"),
                named(0x50, 1, "Exact gone"),
            ),
            discoveredNodes = emptyList(),
            sessions = listOf(node),
        )
        val liveCatalog = mutableStateOf(catalog)
        val current = listOf(
            Neighbour(Bytes.of(0x10), 30, 5.5),
            Neighbour(Bytes.of(0x20), 30, 6.5),
            Neighbour(Bytes.of(0x30), 30, 7.5),
        )
        val repeater = RepeaterStatusStateHolder(clock, FakeFaults, scope).apply {
            configure({ null }, { null }, { snapshots }, { 2 })
            helper.setSession(node)
            setNeighborsExpanded(true)
        }
        runBlocking {
            repeater.handleNeighboursResponse(NeighboursResponse(node.publicKey.prefix(6), Bytes.of(0, 0, 0, 1), 3, current))
        }
        assertEquals(current.map { NeighborSnapshotEntry(it.publicKeyPrefix, it.snr, it.secondsAgo) }, captured.single())
        val fixtureMap: RemoteNodesMapSurface = { _, _, _, _, _, _, modifier -> Box(modifier) }
        activity.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                MeshCoreTheme(motionScale = 0f) {
                    RemoteNodeStatusContent(node, repeater.helper, repeater, null, liveCatalog.value, false, Modifier.fillMaxSize(), fixtureMap)
                }
            }
        }

        fun text(id: Int) = activity.get().getString(id)
        val label = text(L.remoteNodesStatusPossibleMatch)
        val explanation = text(L.remoteNodesStatusPossibleMatchExplanation)
        val title = text(L.remoteNodesStatusPossibleMatchTitle)
        listOf("Current Alpha", "Gone Alpha").forEach { name ->
            val description = "$name, $label"
            val indicator = compose.onNodeWithContentDescription(description)
            indicator
                .performScrollTo()
                .assertIsDisplayed()
                .assertHeightIsAtLeast(48.dp)
                .assertWidthIsAtLeast(48.dp)
                .assertHasClickAction()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, explanation))
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNode(
                hasText(title) and hasAnyAncestor(hasContentDescription(description)), useUnmergedTree = true,
            ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            assertEquals(fontScale, layouts.single().layoutInput.density.fontScale, 0f)
            if (layouts.single().hasVisualOverflow) {
                val layout = layouts.single()
                println("TS07 overflowing $name at $fontScale: width=${layout.didOverflowWidth}, height=${layout.didOverflowHeight}, " +
                    "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, size=${layout.size}, " +
                    "maxLines=${layout.multiParagraph.didExceedMaxLines}, " +
                    "lineBounds=${(0 until layout.lineCount).map { line -> listOf(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line)) }}; $layout")
            }
            assertFalse(layouts.single().hasVisualOverflow)
            indicator.performClick()
            compose.onNodeWithText(explanation).assertIsDisplayed()
            compose.onNodeWithText(text(L.remoteNodesDone)).performClick()
        }
        compose.onAllNodesWithText(title).assertCountEquals(2)
        listOf("Exact current", text(L.remoteNodesStatusUnknown), "Exact gone", "60").forEach { name ->
            compose.onNodeWithText(name).performScrollTo().assertIsDisplayed()
            compose.onNodeWithContentDescription("$name, $label").assertDoesNotExist()
        }
        compose.onAllNodesWithText(text(L.remoteNodesHistoryNotSeen)).assertCountEquals(3)
        compose.onAllNodesWithText(text(L.remoteNodesHistoryNew)).assertCountEquals(3)
        val snr = L.remoteNodesStatusSnrFormat(activity.get().resources, "5.5")
        val lastSeen = L.remoteNodesStatusSecondsAgo(activity.get().resources, 30)
        compose.onNodeWithText("10 / $snr / $lastSeen").performScrollTo().assertIsDisplayed()
        openExplanation("Current Alpha")
        compose.runOnIdle {
            liveCatalog.value = catalog.copy(contacts = catalog.contacts.filterNot { it.name.endsWith("Beta") })
        }
        compose.onNodeWithText(explanation).assertDoesNotExist()
        compose.onAllNodesWithText(title).assertCountEquals(0)
        compose.onNodeWithText("Current Alpha").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Gone Alpha").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(text(L.remoteNodesHistoryNotSeen)).assertCountEquals(3)
        compose.runOnIdle {
            liveCatalog.value = catalog.copy(contacts = catalog.contacts.filterNot { it.name.endsWith("Alpha") || it.name.endsWith("Beta") })
        }
        compose.onNodeWithText("Current Alpha").assertDoesNotExist()
        compose.onNodeWithText("Gone Alpha").assertDoesNotExist()
        compose.onAllNodesWithText(text(L.remoteNodesStatusUnknown)).assertCountEquals(2)
        compose.onNodeWithText("40").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithText(title).assertCountEquals(0)
        compose.runOnIdle { liveCatalog.value = catalog }
        assertWarning("Current Alpha")
        assertWarning("Gone Alpha")
    }
}
