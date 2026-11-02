// AndroidOnly: WP-314 Mounted batch progress, aggregates and cancellation semantics.
package com.meshcoreone.android.feature.tools.trace

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BatchTraceCardComposeTest {
    private lateinit var host: ActivityController<ComponentActivity>
    private val state = mutableStateOf(TracePathState(batchEnabled = true, currentTraceIndex = 1))

    @Before fun mount() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        host.get().setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                MaterialTheme { TraceResultCard(state.value.displayedResult, state.value) }
            }
        }
        advanceFrames()
    }

    @After fun destroy() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun mountedProgressCompletionAndCancelledPartialResultsRemainAccessible() {
        assertTrue(descriptions().contains(C.contactsResultsBatchProgressLabel(host.get().resources, 1, 3)))
        val first = result(listOf(hop(0.0, start = true), hop(5.0)), 100)
        val second = result(listOf(hop(0.0, start = true), hop(7.0)), 200)
        state.value = state.value.copy(currentTraceIndex = 3, completedResults = listOf(first, second))
        advanceFrames()
        assertTrue(texts().contains("SNR: 7.0 dB"))
        assertTrue(descriptions().contains(C.contactsResultsHopAvgSNRLabel(host.get().resources, "6.0", "5.0", "7.0")))
        assertTrue(descriptions().contains(C.contactsResultsAvgRTTLabel(host.get().resources, 150, 100, 200)))

        val harness = TraceHarness()
        harness.holder.editStateForTesting { state.value }
        harness.holder.cancelBatchTrace()
        state.value = harness.state
        advanceFrames()
        assertTrue(texts().contains("SNR: 6.0 dB"))
        assertTrue(descriptions().contains(C.contactsResultsBatchSuccess(host.get().resources, 2, 3, 66)))
        assertFalse(descriptions().any { it.startsWith("Batch complete") || it.startsWith("Batch progress") })

        state.value = state.value.copy(
            currentTraceIndex = 3,
            completedResults = listOf(first, second, result(emptyList(), 0, success = false)),
        )
        advanceFrames()
        assertTrue(descriptions().contains(C.contactsResultsBatchCompleteLabel(host.get().resources, 2, 3, 66)))
        assertFalse(descriptions().any { it.startsWith("Batch progress") })
        assertTrue(descriptions().contains(C.contactsResultsAvgRTTLabel(host.get().resources, 150, 100, 200)))
        assertEquals(1, descriptions().count { it.startsWith("Average round trip") })
    }

    @Test fun mountedRttDescriptionDoesNotNarrowLongAndAllFailureHasNoAggregate() {
        val longRtt = Int.MAX_VALUE.toLong() + 1
        val success = result(listOf(hop(0.0, start = true), hop(5.0)), longRtt)
        state.value = state.value.copy(currentTraceIndex = 0, batchSize = 1, completedResults = listOf(success))
        advanceFrames()
        assertTrue(descriptions().any { it.contains("2147483648 milliseconds") })
        state.value = state.value.copy(completedResults = listOf(result(emptyList(), 0, success = false)))
        advanceFrames()
        assertTrue(descriptions().contains(C.contactsResultsBatchCompleteLabel(host.get().resources, 0, 1, 0)))
        assertFalse(descriptions().any { it.startsWith("Average round trip") })
    }

    private fun advanceFrames() {
        Snapshot.sendApplyNotifications()
        repeat(6) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    private fun nodes(): List<SemanticsNode> {
        fun composeView(view: View): View? {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
            if (view is ViewGroup) repeat(view.childCount) { index ->
                composeView(view.getChildAt(index))?.let { return it }
            }
            return null
        }
        val root = checkNotNull(composeView(host.get().window.decorView))
        val owner = root.javaClass.getMethod("getSemanticsOwner").invoke(root) as SemanticsOwner
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return descendants(owner.rootSemanticsNode)
    }

    private fun descriptions(): List<String> =
        nodes().flatMap { it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } }

    private fun texts(): List<String> =
        nodes().flatMap { it.config.getOrElse(SemanticsProperties.Text) { emptyList() } }.map { it.text }
}
