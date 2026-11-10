// AndroidOnly: WP-315 mounted entry-owned seed consumption across actual Activity recreation.
package com.meshcoreone.android.feature.tools.los

import android.app.Activity
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.Coordinate
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w900dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineOfSightEntryLifecycleTest {
    private lateinit var host: ActivityController<LineOfSightLifecycleActivity>
    private lateinit var content: ComposeView
    private lateinit var holder: LineOfSightStateHolder
    private val frames = BroadcastFrameClock()
    private var frameNanos = 0L
    private val selected = createTestContact(name = "Preselected", latitude = 37.8, longitude = -122.4)
    private val elevation = FakeElevationSource(ManualClock())
    private val dependencies = LineOfSightFeatureDependencies(
        elevationSource = elevation,
        pathAnalyzer = FakePathAnalyzer(),
        contactSource = { null },
        radioId = { null },
        deviceFrequencyKHz = { null },
    )

    @Before fun attach() {
        host = Robolectric.buildActivity(LineOfSightLifecycleActivity::class.java).setup().visible()
        mount()
        assertEquals(selected, holder.state.value.pointA?.contact)
        assertEquals(1, elevation.fetchCount)
    }

    @After fun dispose() {
        content.disposeComposition()
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
        frames.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun mountedRelocationConsumesTheSeedBeforeActivityRecreation() {
        val relocate = host.get().getString(AppToolsStrings.toolsLineOfSightRelocate)
        click(nodes().single {
            it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } == listOf(relocate)
        })
        assertEquals(PointID.POINT_A, holder.state.value.relocatingPoint)
        // The JVM cannot project a native map tap; deliver its coordinate to the real map handler.
        host.get().runOnUiThread { holder.handleMapTap(Coordinate(38.0, -122.0)) }
        settle()
        assertEquals(Coordinate(38.0, -122.0), holder.state.value.pointA?.coordinate)
        assertNull(holder.state.value.pointA?.contact)
        assertEquals(2, elevation.fetchCount)
        recreate()
        assertNull(holder.state.value.pointA, "The original contact must not replace the operator's relocated point")
        assertEquals(2, elevation.fetchCount, "Recreation must not fetch the superseded contact")
    }

    @Test fun clearAndReselectInOneMainTurnCannotLoseConsumptionToFlowConflation() {
        val clear = nodes().single {
            it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "los-clear-all"
        }
        host.get().runOnUiThread {
            assertTrue(assertNotNull(clear.config[SemanticsActions.OnClick].action).invoke())
            holder.selectPoint(Coordinate(selected.latitude, selected.longitude), selected)
            assertEquals(selected, holder.state.value.pointA?.contact)
        }
        settle()
        assertEquals(2, elevation.fetchCount)
        recreate()
        assertNull(holder.state.value.pointA, "Even a same-contact reselection must not replay the original seed")
        assertEquals(2, elevation.fetchCount)
    }

    @Test fun untouchedPreselectionIsStillAvailableAfterActivityRecreation() {
        recreate()
        assertEquals(selected, holder.state.value.pointA?.contact)
        assertEquals(Coordinate(37.8, -122.4), holder.state.value.pointA?.coordinate)
        assertEquals(2, elevation.fetchCount)
    }

    private fun recreate() {
        val previousActivity = host.get()
        val previousHolder = holder
        host.windowFocusChanged(false)
        host.recreate().visible()
        mount()
        assertFalse(previousActivity === host.get(), "The Activity must actually change")
        assertFalse(previousHolder === holder, "A fresh holder must honor the restored consumption flag")
    }

    private fun mount() {
        host.windowFocusChanged(true)
        content = ComposeView(host.get()).apply {
            id = 315
            setViewTreeLifecycleOwner(host.get())
            setViewTreeSavedStateRegistryOwner(host.get())
            setParentCompositionContext(createLifecycleAwareWindowRecomposer(frames, host.get().lifecycle))
        }
        host.get().setContentView(content)
        content.setContent {
            val entryHolder = rememberLineOfSightStateHolder(dependencies, selected)
            SideEffect { holder = entryHolder }
            val state by entryHolder.state.collectAsState()
            MaterialTheme { LineOfSightPanel(state, entryHolder, Modifier) }
        }
        settle()
    }

    private fun click(node: SemanticsNode) {
        host.get().runOnUiThread {
            assertTrue(assertNotNull(node.config[SemanticsActions.OnClick].action).invoke())
        }
        settle()
    }

    private fun nodes(): List<SemanticsNode> {
        val owner = composeRoot().javaClass.getMethod("getSemanticsOwner").invoke(composeRoot()) as SemanticsOwner
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return descendants(owner.rootSemanticsNode)
    }

    private fun composeRoot(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) repeat(view.childCount) { index -> find(view.getChildAt(index))?.let { return it } }
            return null
        }
        return checkNotNull(find(content))
    }

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
            composeRoot().javaClass.methods.filter { it.name == "measureAndLayout" }.forEach { method ->
                if (method.parameterCount == 1) method.invoke(composeRoot(), true) else method.invoke(composeRoot())
            }
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}

class LineOfSightLifecycleActivity : Activity(), SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this).apply { performAttach() }
    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedState.performRestore(savedInstanceState)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.STARTED
    }

    override fun onResume() {
        super.onResume()
        registry.currentState = Lifecycle.State.RESUMED
    }

    override fun onPause() {
        registry.currentState = Lifecycle.State.STARTED
        super.onPause()
    }

    override fun onStop() {
        registry.currentState = Lifecycle.State.CREATED
        super.onStop()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        savedState.performSave(outState)
    }
}
