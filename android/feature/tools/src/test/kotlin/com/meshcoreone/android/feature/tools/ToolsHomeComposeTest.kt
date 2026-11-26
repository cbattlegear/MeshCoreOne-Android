// AndroidOnly: WP-314 Mounted Tools home route, accessibility and adaptive layout checks.
package com.meshcoreone.android.feature.tools

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings as T
import com.meshcoreone.android.core.ui.ScaffoldAvailabilityKey
import com.meshcoreone.android.feature.tools.navigation.ToolSelection
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolsHomeComposeTest {
    private lateinit var host: ActivityController<Activity>
    private lateinit var owner: ScreenOwner
    private lateinit var content: ComposeView
    private lateinit var recomposer: Recomposer
    private val frames = BroadcastFrameClock()
    private val selected = mutableListOf<ToolSelection>()

    @After fun destroy() {
        if (::content.isInitialized) content.disposeComposition()
        if (::owner.isInitialized) owner.registry.currentState = Lifecycle.State.DESTROYED
        if (::host.isInitialized) {
            host.windowFocusChanged(false)
            host.pause().stop().destroy()
        }
        frames.cancel()
    }

    @Test fun allToolsRemainAccessibleAndSelectTheirOwnRouteWithoutARadio() {
        mount()
        assertTrue(nodes().any { it.config.getOrElseNullable(ScaffoldAvailabilityKey) { null } == "AVAILABLE" })
        val labels = labels()
        val buttons = buttons()
        assertEquals(labels, buttons.map { text(it) })
        buttons.forEachIndexed { index, node ->
            assertEquals(Role.Button, node.config[SemanticsProperties.Role])
            assertTrue(node.boundsInRoot.width >= 48f && node.boundsInRoot.height >= 48f)
            assertFalse(node.config.contains(SemanticsProperties.Disabled))
            host.get().runOnUiThread { assertTrue(assertNotNull(node.config[SemanticsActions.OnClick].action)()) }
            assertEquals(ToolSelection.entries[index], selected.last())
        }
        assertEquals(ToolSelection.entries.toList(), selected.toList())
    }

    @Test fun compactRtlAtDoubleFontScaleKeepsLabelsAndActionsReadable() {
        mount(fontScale = 2f, direction = LayoutDirection.Rtl)
        assertReadableRows()
    }

    @Test
    @Config(qualifiers = "en-rUS-w800dp-h900dp-mdpi")
    fun expandedAtDoubleFontScaleKeepsLabelsAndActionsReadable() {
        mount(fontScale = 2f)
        assertReadableRows()
    }

    private fun assertReadableRows() {
        val labels = labels()
        assertEquals(labels, buttons().map { text(it) })
        for (label in labels) {
            val row = buttons().single { text(it) == label }
            assertTrue(row.boundsInRoot.width >= 48f && row.boundsInRoot.height >= 48f)
            assertTrue(row.boundsInRoot.left >= 0f && row.boundsInRoot.right <= content.width.toFloat())
            val textNode = nodes(unmerged = true).single {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { part -> part.text == label }
            }
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(assertNotNull(textNode.config[SemanticsActions.GetTextLayoutResult].action)(layouts))
            val layout = layouts.single()
            assertFalse(
                layout.hasVisualOverflow,
                "$label must wrap without clipping: size=${layout.size}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                    "bounds=${textNode.boundsInRoot}, text=${layout.layoutInput.text}",
            )
        }
    }

    private fun mount(fontScale: Float = 1f, direction: LayoutDirection = LayoutDirection.Ltr) {
        host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        owner = ScreenOwner()
        content = ComposeView(host.get()).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            recomposer = createLifecycleAwareWindowRecomposer(coroutineContext = frames, lifecycle = owner.lifecycle)
            setParentCompositionContext(recomposer)
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f, fontScale),
                    LocalLayoutDirection provides direction,
                ) {
                    MaterialTheme {
                        ToolsEntry(FeatureRoute(FeatureId.TOOLS), {}, onSelect = { selected.add(it) }, showRootTitle = false)
                    }
                }
            }
        }
        host.get().setContentView(content)
        repeat(12) { frame ->
            host.get().runOnUiThread {
                Snapshot.sendApplyNotifications()
                frames.sendFrame((frame + 1) * 16_000_000L)
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

    private fun labels() = listOf(
        T.toolsTracePath, T.toolsLineOfSight, T.toolsRxLog, T.toolsNoiseFloor, T.toolsNodeDiscovery, T.toolsCli,
    ).map(host.get()::getString)

    private fun text(node: SemanticsNode) =
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.joinToString("") { it.text }

    private fun buttons() = nodes().filter { it.config.contains(SemanticsActions.OnClick) }

    private fun nodes(unmerged: Boolean = false): List<SemanticsNode> {
        val root = composeRoot()
        val semantics = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return descendants(if (unmerged) semantics.unmergedRootSemanticsNode else semantics.rootSemanticsNode)
    }

    private fun composeRoot(): View {
        fun find(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) repeat(view.childCount) { index -> find(view.getChildAt(index))?.let { return it } }
            return null
        }
        return checkNotNull(find(content))
    }

    private class ScreenOwner : SavedStateRegistryOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
        private val savedState = SavedStateRegistryController.create(this).apply {
            performAttach()
            performRestore(null)
        }
        override val savedStateRegistry get() = savedState.savedStateRegistry
        init { registry.currentState = Lifecycle.State.RESUMED }
    }
}
