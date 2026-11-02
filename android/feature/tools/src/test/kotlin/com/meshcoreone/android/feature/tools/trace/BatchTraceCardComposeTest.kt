// AndroidOnly: WP-314 Mounted batch progress, aggregates and cancellation semantics.
package com.meshcoreone.android.feature.tools.trace

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onEach
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
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchTraceCardComposeTest {
    private lateinit var host: ActivityController<Activity>
    private lateinit var owner: ScreenOwner
    private lateinit var content: ComposeView
    private val frameClock = BroadcastFrameClock()
    private var frameNanos = 0L
    private val state = MutableStateFlow(
        TracePathState(batchEnabled = true, currentTraceIndex = 1, batchPresentationActive = true),
    )
    @Volatile private var collectedState: TracePathState? = null
    @Volatile private var renderedState: TracePathState? = null
    private val observedState = state.onEach { collectedState = it }

    @Before fun mount() {
        host = Robolectric.buildActivity(Activity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        owner = ScreenOwner()
        content = ComposeView(host.get()).apply {
            setViewTreeLifecycleOwner(owner)
            setViewTreeSavedStateRegistryOwner(owner)
            // Keep the real lifecycle-aware recomposer while advancing its frames deterministically.
            setParentCompositionContext(
                createLifecycleAwareWindowRecomposer(coroutineContext = frameClock, lifecycle = owner.lifecycle),
            )
            setContent {
                val snapshot by observedState.collectAsStateWithLifecycle(initialValue = state.value)
                SideEffect { renderedState = snapshot }
                CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                    MaterialTheme { TraceResults(snapshot) }
                }
            }
        }
        host.get().setContentView(content)
        advanceFrames()
        assertEquals(state.value, renderedState, "The mounted results must observe the initial state")
    }

    @After fun destroy() {
        content.disposeComposition()
        owner.registry.currentState = Lifecycle.State.DESTROYED
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
        frameClock.cancel()
    }

    @Test fun mountedProgressCompletionAndCancelledPartialResultsRemainAccessible() {
        assertTrue(descriptions().contains(C.contactsResultsBatchProgressLabel(host.get().resources, 1, 3)))
        val first = result(listOf(hop(0.0, start = true), hop(5.0)), 100)
        val second = result(listOf(hop(0.0, start = true), hop(7.0)), 200)
        render(state.value.copy(currentTraceIndex = 3, completedResults = listOf(first, second)))
        assertTrue(texts().contains("SNR: 7.0 dB"))
        assertTrue(descriptions().contains(C.contactsResultsHopAvgSNRLabel(host.get().resources, "6.0", "5.0", "7.0")))
        assertTrue(descriptions().contains(C.contactsResultsAvgRTTLabel(host.get().resources, 150, 100, 200)))

        val harness = TraceHarness()
        harness.holder.editStateForTesting { state.value }
        harness.holder.cancelBatchTrace()
        render(harness.state)
        assertTrue(texts().contains("SNR: 6.0 dB"))
        assertTrue(descriptions().contains(C.contactsResultsBatchSuccess(host.get().resources, 2, 3, 66)))
        assertFalse(descriptions().any { it.startsWith("Batch complete") || it.startsWith("Batch progress") })

        render(state.value.copy(
            currentTraceIndex = 3,
            completedResults = listOf(first, second, result(emptyList(), 0, success = false)),
        ))
        assertTrue(descriptions().contains(C.contactsResultsBatchCompleteLabel(host.get().resources, 2, 3, 66)))
        assertFalse(descriptions().any { it.startsWith("Batch progress") })
        assertTrue(descriptions().contains(C.contactsResultsAvgRTTLabel(host.get().resources, 150, 100, 200)))
        assertEquals(1, descriptions().count { it.startsWith("Average round trip") })
    }

    @Test fun mountedRttDescriptionDoesNotNarrowLongAndAllFailureHasNoAggregate() {
        val longRtt = Int.MAX_VALUE.toLong() + 1
        val success = result(listOf(hop(0.0, start = true), hop(5.0)), longRtt)
        render(state.value.copy(currentTraceIndex = 0, batchSize = 1, completedResults = listOf(success)))
        assertTrue(descriptions().any { it.contains("2147483648 milliseconds") })
        render(state.value.copy(completedResults = listOf(result(emptyList(), 0, success = false))))
        assertTrue(descriptions().contains(C.contactsResultsBatchCompleteLabel(host.get().resources, 0, 1, 0)))
        assertFalse(descriptions().any { it.startsWith("Average round trip") })
    }

    @Test fun actualEditsAndClearHideCompletedAndCancelledCardsWithoutDeletingHistory() {
        for (cancelled in listOf(false, true)) for (clear in listOf(false, true)) {
            val harness = TraceHarness()
            harness.holder.addNode(contact(0xAA, name = "Old route"))
            val success = result(
                listOf(hop(0.0, start = true), hop(5.0)), 100, path = harness.state.fullPathData,
            )
            harness.holder.editStateForTesting {
                it.copy(
                    batchEnabled = true, batchSize = if (cancelled) 3 else 1,
                    batchPresentationActive = true, currentTraceIndex = if (cancelled) 2 else 0,
                    completedResults = listOf(success), result = success,
                )
            }
            if (cancelled) harness.holder.cancelBatchTrace()
            render(harness.state)
            assertTrue(descriptions().contains(C.contactsResultsAvgRTTLabel(host.get().resources, 100, 100, 100)))
            if (clear) harness.holder.clearPath() else harness.holder.addNode(contact(0xBB, name = "New route"))
            render(harness.state)
            assertFalse(descriptions().any { it.startsWith("Average round trip") || it.contains("successful") })
            assertFalse(texts().any { it.contains("SNR") })
            assertEquals(listOf(success), harness.state.completedResults)
            assertEquals(100L, harness.state.averageRTT)
        }
    }

    @Test fun responseAfterActiveBatchEditDoesNotMixRouteStatistics() {
        val harness = TraceHarness(configureDependencies = true)
        val sender = FakeTraceSender()
        harness.deps.device = device()
        harness.deps.sender = sender
        harness.holder.addNode(contact(0xAA, name = "Old route"))
        harness.holder.setBatchEnabled(true)
        harness.holder.setBatchSize(3)

        val job = harness.start { harness.holder.runBatchTrace() }
        assertTrue(harness.state.batchPresentationActive)
        render(harness.state)
        assertTrue(descriptions().contains(C.contactsResultsBatchProgressLabel(host.get().resources, 1, 3)))

        harness.respond(sender.sent.single().tag, listOf(node(0xAA, 5.0), node(null, 0.0)), RADIO)
        harness.holder.removeRepeater(0)
        harness.holder.addNode(contact(0xBB, name = "New route"))
        harness.time.advanceBy(500.milliseconds)
        assertEquals(Bytes.of(0xBB), sender.sent.last().path)
        harness.respond(sender.sent.last().tag, listOf(node(0xBB, 9.0), node(null, 0.0)), RADIO)

        assertTrue(job.isActive)
        assertFalse(harness.state.batchPresentationActive)
        assertEquals(HopStats(7.0, 5.0, 9.0), harness.state.hopStats(1))
        assertEquals(2, harness.state.completedResults.size)
        render(harness.state)
        assertTrue(texts().contains("SNR: 9.0 dB"))
        assertFalse(descriptions().any { it.startsWith("Average signal to noise ratio") })
        assertFalse(descriptions().any { it.startsWith("Average round trip") || it.startsWith("Batch progress") })

        harness.holder.cancelBatchTrace()
        harness.time.advanceBy(500.milliseconds)
        assertTrue(job.isCompleted)
        assertEquals(2, harness.state.completedResults.size)
        render(harness.state)
        assertTrue(texts().contains("SNR: 9.0 dB"))
        assertFalse(descriptions().any { it.startsWith("Average signal to noise ratio") })

        val next = harness.start { harness.holder.runBatchTrace() }
        assertTrue(harness.state.batchPresentationActive)
        assertTrue(harness.state.completedResults.isEmpty())
        render(harness.state)
        assertTrue(descriptions().contains(C.contactsResultsBatchProgressLabel(host.get().resources, 1, 3)))
        harness.holder.cancelBatchTrace()
        harness.main.runCurrent()
        assertTrue(next.isCompleted)
    }

    private fun render(snapshot: TracePathState) {
        val updateRan = AtomicBoolean()
        host.get().runOnUiThread {
            state.value = snapshot
            updateRan.set(true)
        }
        advanceFrames()
        assertTrue(updateRan.get(), "The UI-thread state update must execute")
        assertEquals(snapshot, state.value, "The source flow must contain the updated state")
        assertTrue(state.subscriptionCount.value > 0, "The lifecycle-aware collector must be active")
        assertEquals(snapshot, collectedState, "The lifecycle-aware collector must receive the updated state")
        assertEquals(snapshot, renderedState, "The mounted results must observe every state transition")
    }

    private fun advanceFrames() {
        repeat(6) {
            host.get().runOnUiThread {
                Snapshot.sendApplyNotifications()
                frameNanos += 16_000_000L
                frameClock.sendFrame(frameNanos)
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

    private fun nodes(): List<SemanticsNode> {
        val root = composeRoot()
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return descendants(owner.rootSemanticsNode)
    }

    private fun composeRoot(): View {
        fun composeView(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) repeat(view.childCount) { index ->
                composeView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        return checkNotNull(composeView(content)) { "Production Compose results must be attached" }
    }

    private fun descriptions(): List<String> =
        nodes().flatMap { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }

    private fun texts(): List<String> =
        nodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }

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
