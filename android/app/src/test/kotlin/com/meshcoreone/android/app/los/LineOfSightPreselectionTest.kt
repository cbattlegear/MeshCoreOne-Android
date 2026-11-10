// AndroidOnly: WP-315 TS-21 real shell-to-entry handoff for the three pinned preselection families.
package com.meshcoreone.android.app.los

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.container.AppLineOfSightPathAnalyzer
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.app.navigation.NavigationSavedState
import com.meshcoreone.android.app.navigation.ToolSelection
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.tools.los.ElevationSample
import com.meshcoreone.android.feature.tools.los.LineOfSightElevationSource
import com.meshcoreone.android.feature.tools.los.LineOfSightFeatureDependencies
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w900dp-h1200dp-mdpi", shadows = [UnsupportedMapProcess::class])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineOfSightPreselectionTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val navigation = NavigationCoordinator()
    private val render = mutableIntStateOf(0)
    private var widthDp = 900
    private val elevationReads = mutableListOf<Coordinate>()
    private val radio = RadioId(UUID(0, 315))
    private val selected = ContactDTO(
        id = UUID(0, 21), radioId = radio, publicKey = Bytes(ByteArray(32) { 21 }),
        name = "Preselected", typeRawValue = ContactType.CHAT.rawValue,
        latitude = 37.8, longitude = -122.4, lastHeardTimestamp = null,
    )
    private val dependencies = LineOfSightFeatureDependencies(
        elevationSource = object : LineOfSightElevationSource {
            override suspend fun fetchElevation(coordinate: Coordinate): Double {
                elevationReads += coordinate
                return 100.0
            }
            override suspend fun fetchElevations(path: List<Coordinate>): List<ElevationSample> =
                error("Preselection must not request terrain analysis")
            override fun optimalSampleCount(distanceMeters: Double): Int =
                error("Preselection must not sample terrain")
            override fun sampleCoordinates(from: Coordinate, to: Coordinate, sampleCount: Int): List<Coordinate> =
                error("Preselection must not sample terrain")
        },
        pathAnalyzer = AppLineOfSightPathAnalyzer,
        contactSource = { null },
        radioId = { null },
        deviceFrequencyKHz = { null },
    )

    @Before fun attach() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun dispose() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    private fun mount(contact: ContactDTO?) {
        if (contact == null) navigation.navigateToTool(ToolSelection.LINE_OF_SIGHT)
        else navigation.navigateToLineOfSight(contact)
        mountShell()
    }

    private fun mountShell() {
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                Box(Modifier.width(widthDp.dp).height((1200 + render.intValue).dp)) {
                    NativeNavigationShell(navigation, lineOfSight = dependencies)
                }
            }
        }
    }

    @Test fun locatedContactReachesMountedPointAAndIsNotReappliedAfterClear() {
        mount(selected)
        compose.onNodeWithText(selected.name).assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(listOf(Coordinate(37.8, -122.4)), elevationReads)
            assertEquals(selected, (navigation.state.value.activeStack.last().destination as NavigationDestination.Tool).preselectedContact)
        }
        compose.onAllNodesWithText(host.get().getString(AppToolsStrings.toolsLineOfSightNotSelected)).assertCountEquals(1)
        compose.onNodeWithTag("los-clear-all").performClick()
        compose.runOnIdle { render.intValue++ }
        compose.onNodeWithText(selected.name).assertDoesNotExist()
        assertEmptyEndpoints()
        compose.runOnIdle { assertEquals(1, elevationReads.size) }
    }

    @Test fun absentContactLeavesMountedPointAEmpty() {
        mount(null)
        assertEmptyEndpoints()
        compose.runOnIdle { assertEquals(emptyList<Coordinate>(), elevationReads) }
    }

    @Test
    @Config(qualifiers = "en-rUS-w400dp-h1200dp-mdpi")
    fun explicitSameContactHandoffGetsAFreshCompactEntryAfterClearAndTabSwitch() {
        widthDp = 400
        mount(selected)
        compose.onNodeWithText(selected.name).assertIsDisplayed()
        val originalId = navigation.state.value.activeStack.last().id
        compose.onNodeWithTag("los-clear-all").performClick()
        assertEmptyEndpoints()
        compose.runOnIdle {
            assertFalse(navigation.state.value.isSidebarWide)
            assertEquals(400f, navigation.state.value.widthDp, 1f)
            navigation.selectTab(AppTab.NODES)
        }
        compose.waitForIdle()
        compose.runOnIdle { navigation.selectTab(AppTab.TOOLS) }
        assertEmptyEndpoints()
        compose.runOnIdle {
            assertEquals(originalId, navigation.state.value.activeStack.last().id)
            assertEquals(1, elevationReads.size)
            navigation.navigateToContactDetail(selected)
        }
        compose.waitForIdle()
        compose.runOnIdle { navigation.navigateToLineOfSight(selected) }
        compose.onNodeWithText(selected.name).assertIsDisplayed()
        compose.runOnIdle {
            val entries = navigation.state.value.activeStack.filter {
                (it.destination as? NavigationDestination.Tool)?.selection == ToolSelection.LINE_OF_SIGHT
            }
            assertEquals(1, entries.size)
            assertFalse("A new explicit handoff must not reuse cleared entry state", originalId == entries.single().id)
            assertEquals(listOf(Coordinate(37.8, -122.4), Coordinate(37.8, -122.4)), elevationReads)
        }
    }

    @Test fun clearedContactIsNotReappliedAfterActualActivityRecreation() {
        mount(selected)
        compose.onNodeWithText(selected.name).assertIsDisplayed()
        compose.onNodeWithTag("los-clear-all").performClick()
        assertEmptyEndpoints()
        compose.waitForIdle()
        val before = host.get()
        host.windowFocusChanged(false)
        host.recreate().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        mountShell()
        compose.waitForIdle()
        assertFalse("The Activity must actually be replaced", before === host.get())
        compose.runOnIdle {
            assertEquals(selected, (navigation.state.value.activeStack.last().destination as NavigationDestination.Tool).preselectedContact)
            assertEquals(listOf(Coordinate(37.8, -122.4)), elevationReads)
        }
        assertEmptyEndpoints()
        compose.onNodeWithText(selected.name).assertDoesNotExist()
    }

    @Test fun unlocatedContactLeavesMountedPointAEmpty() {
        mount(selected.copy(name = "No Location", latitude = 0.0, longitude = 0.0))
        assertEmptyEndpoints()
        compose.onNodeWithText("No Location").assertDoesNotExist()
        compose.runOnIdle { assertEquals(emptyList<Coordinate>(), elevationReads) }
    }

    @Test fun openingPlainToolAfterContactDoesNotReuseItsSelectedEndpoint() {
        mount(selected)
        compose.onNodeWithText(selected.name).assertIsDisplayed()
        compose.runOnIdle { navigation.navigateToTool(ToolSelection.LINE_OF_SIGHT) }
        assertEmptyEndpoints()
        compose.onNodeWithText(selected.name).assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, elevationReads.size) }
    }

    @Test fun savedNavigationRetainsToolButNeverSerializesPreselectedContact() {
        navigation.navigateToLineOfSight(selected)
        val tokens = NavigationSavedState.encode(navigation.state.value)
        val encoded = tokens.joinToString()
        listOf(selected.name, selected.id.toString(), radio.toString(), "37.8", "-122.4").forEach {
            assertFalse("Saved state must not contain contact data: $it", encoded.contains(it))
        }
        val destination = NavigationSavedState.restore(tokens).activeStack.last().destination as NavigationDestination.Tool
        assertEquals(ToolSelection.LINE_OF_SIGHT, destination.selection)
        assertNull(destination.preselectedContact)
    }

    private fun assertEmptyEndpoints() {
        compose.onAllNodesWithText(host.get().getString(AppToolsStrings.toolsLineOfSightNotSelected)).assertCountEquals(2)
        compose.onNodeWithTag("los-clear-all").assertDoesNotExist()
    }
}
