// AndroidOnly: WP-313 Mounted current and disappeared neighbor confidence disclosure.
package com.meshcoreone.android.feature.remotenodes.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
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
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp")
class RemoteNeighborConfidenceComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private lateinit var previousLocale: Locale
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before fun openWindow() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
    }

    @After fun closeWindow() {
        activity.pause().stop().destroy()
        scope.cancel()
        Locale.setDefault(previousLocale)
    }

    @Test fun currentAndDisappearedFallbacksAreIdentifiedAtDefaultFont() = exercise(1f)

    @Test fun currentAndDisappearedFallbacksAreIdentifiedAtDoubleFont() = exercise(2f)

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
                MaterialTheme {
                    RemoteNodeStatusContent(node, repeater.helper, repeater, null, catalog, false, Modifier.fillMaxSize(), fixtureMap)
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
    }
}
