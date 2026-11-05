// AndroidOnly: WP-315 mounted TS-42 operator action and cancellation coverage.
package com.meshcoreone.android.feature.tools.los

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Duration
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LineOfSightMountedWorkflowTest {
    private lateinit var host: ActivityController<Activity>
    private lateinit var screenOwner: ScreenOwner
    private lateinit var content: ComposeView
    private val frames = BroadcastFrameClock()
    private lateinit var scope: CoroutineScope
    private lateinit var clock: ManualClock
    private lateinit var elevation: FakeElevationSource
    private lateinit var holder: LineOfSightStateHolder
    private var frameNanos = 0L

    @Before
    fun attachHost() {
        host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        host.windowFocusChanged(true)
        screenOwner = ScreenOwner()
        content = ComposeView(host.get()).apply {
            setViewTreeLifecycleOwner(screenOwner)
            setViewTreeSavedStateRegistryOwner(screenOwner)
            setParentCompositionContext(
                createLifecycleAwareWindowRecomposer(
                    coroutineContext = frames,
                    lifecycle = screenOwner.lifecycle,
                ),
            )
        }
        host.get().setContentView(content)
        clock = ManualClock()
        elevation = FakeElevationSource(clock)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        holder = LineOfSightStateHolder(scope, elevation, FakePathAnalyzer(), EmptyCoroutineContext)
    }

    @After
    fun disposeHost() {
        host.get().runOnUiThread(holder::dispose)
        scope.cancel()
        content.disposeComposition()
        screenOwner.registry.currentState = Lifecycle.State.DESTROYED
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
        frames.cancel()
        idleMainLooper()
    }

    @Test
    fun `mounted clear all removes selected path and result but retains saved contacts`() {
        val radioId = RadioId(UUID.randomUUID())
        val savedRepeater = createTestContact(
            name = "Saved relay",
            latitude = 37.8,
            longitude = -122.35,
            type = ContactType.REPEATER,
            radioId = radioId,
        )
        val contacts = FakeContactSource().apply { add(savedRepeater) }
        onMain {
            holder.configure({ contacts }, { radioId })
            scope.launch { holder.loadRepeaters() }
            holder.setPointA(SAN_FRANCISCO)
            holder.setPointB(OAKLAND)
        }
        awaitState("saved repeater and endpoint elevations load") {
            holder.state.value.repeatersWithLocation == listOf(savedRepeater) && holder.state.value.canAnalyze
        }

        onMain(holder::analyze)
        awaitState("direct analysis completes") { holder.state.value.analysisStatus is AnalysisStatus.Result }
        onMain {
            holder.setRepeaterPoint(RepeaterPoint(TEST_REPEATER_COORDINATE, pathFraction = 0.5))
            holder.requestAnalysis()
        }
        awaitState("relay result is available") { holder.state.value.analysisStatus is AnalysisStatus.RelayResult }

        content.setContent {
            val state by holder.state.collectAsState()
            MaterialTheme {
                LineOfSightPanel(state, holder, Modifier.fillMaxSize())
            }
        }
        settle()
        clickClearAll()

        val state = holder.state.value
        assertNull(state.pointA)
        assertNull(state.pointB)
        assertNull(state.repeaterPoint)
        assertFalse(state.isAnalyzing)
        assertEquals(AnalysisStatus.Idle, state.analysisStatus)
        assertTrue(state.elevationProfile.isEmpty())
        assertEquals(listOf(savedRepeater), state.repeatersWithLocation, "clearing the path must not delete saved contacts")
    }

    @Test
    fun `mounted clear all cancels an in flight analysis`() {
        onMain {
            holder.setPointA(SAN_FRANCISCO)
            holder.setPointB(OAKLAND)
        }
        awaitState("endpoint elevations load") { holder.state.value.canAnalyze }
        content.setContent {
            val state by holder.state.collectAsState()
            MaterialTheme {
                LineOfSightPanel(state, holder, Modifier.fillMaxSize())
            }
        }
        settle()

        elevation.fetchDelayMillis = 1_000
        onMain(holder::requestAnalysis)
        awaitState("analysis waits on profile fetch") { holder.state.value.isAnalyzing }
        assertTrue(clock.pendingSleepers > 0)

        clickClearAll()
        assertNull(holder.state.value.pointA)
        assertNull(holder.state.value.pointB)
        assertFalse(holder.state.value.isAnalyzing)
        assertEquals(AnalysisStatus.Idle, holder.state.value.analysisStatus)
        assertEquals(0, clock.pendingSleepers, "clear all must cancel the in-flight elevation fetch")

        settle()
        assertEquals(AnalysisStatus.Idle, holder.state.value.analysisStatus, "cancelled work must not publish a stale result")
    }

    private fun awaitState(message: String, condition: () -> Boolean) {
        repeat(100) {
            settle()
            if (condition()) return
        }
        error("$message; state=${holder.state.value}")
    }

    private fun findClearAllAction(): SemanticsNode {
        val matches = semanticsNodes().filter {
            it.config.contains(SemanticsActions.OnClick) &&
                it.config.getOrElse(SemanticsProperties.TestTag) { "" } == "los-clear-all"
        }
        assertEquals(1, matches.size, "Expected one mounted clear-all action")
        return matches.single()
    }

    private fun clickClearAll() {
        var handled = false
        host.get().runOnUiThread {
            val action = findClearAllAction()
            assertTrue(isDisplayed(action), "clear-all must be visible to the operator")
            assertTrue(
                action.boundsInRoot.height / host.get().resources.displayMetrics.density >= 48f,
                "clear-all must retain a 48dp touch target",
            )
            assertEquals(
                listOf(host.get().getString(AppContactsStrings.contactsDiscoveryClear)),
                action.config[SemanticsProperties.ContentDescription],
                "clear-all must have a distinct localized accessibility label",
            )
            assertEquals(
                listOf(host.get().getString(AppToolsStrings.toolsLineOfSightClear)),
                action.config[SemanticsProperties.Text].map { it.text },
                "clear-all must retain its visible localized button copy",
            )
            handled = assertNotNull(action.config[SemanticsActions.OnClick].action).invoke()
        }
        settle()
        assertTrue(handled, "the mounted clear-all action must handle the click")
    }

    private fun semanticsNodes(): List<SemanticsNode> {
        val root = composeRoot()
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return descendants(owner.rootSemanticsNode)
    }

    private fun composeRoot(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) repeat(view.childCount) { index -> find(view.getChildAt(index))?.let { return it } }
            return null
        }
        return checkNotNull(find(content)) { "LOS panel must be attached to a Compose view" }
    }

    private fun isDisplayed(node: SemanticsNode): Boolean {
        val root = composeRoot()
        val bounds: Rect = node.boundsInRoot
        return bounds.width > 0f && bounds.height > 0f &&
            bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < root.width && bounds.top < root.height
    }

    private fun onMain(block: () -> Unit) {
        host.get().runOnUiThread(block)
        settle()
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
        idleMainLooper()
    }

    private fun idleMainLooper() {
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }
}

private class ScreenOwner : SavedStateRegistryOwner {
    val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private val savedState = SavedStateRegistryController.create(this).apply {
        performAttach()
        performRestore(null)
    }
    override val savedStateRegistry get() = savedState.savedStateRegistry

    init {
        registry.currentState = Lifecycle.State.RESUMED
    }
}
