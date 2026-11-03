// AndroidOnly: WP-314 TS-09 Mounted code/paste actions use the production parser and width policy.
package com.meshcoreone.android.feature.tools.trace

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
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
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TraceCodeInputComposeTest {
    private lateinit var host: ActivityController<TraceCodeInputTestActivity>
    private val content get() = host.get().content
    private val frames get() = host.get().frames
    private var frameNanos = 0L

    @Before fun setup() {
        host = Robolectric.buildActivity(TraceCodeInputTestActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    @After fun dispose() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
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
                TracePathScreen(TracePathScreen.Dependencies(dependencies, recents, RecordingDiagnostics(), RADIO, 1))
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
        paste("AA,BB,FF,ZZ")
        click("Add nodes: BB")
        val feedback = listOf("Adding as hop 3", "Invalid format: ZZ", "FF not found", "AA already in path")
        assertEquals(feedback, announcementTexts())
        host.get().runOnUiThread { host.get().focusManager.clearFocus(force = true) }
        settle()
        scrollToIndex(35)
        assertFalse(nodes().any { it.config.contains(SemanticsActions.SetText) })
        scrollToIndex(0)
        assertEquals("", input().config[SemanticsProperties.EditableText].text)
        assertEquals(feedback, announcementTexts())
        assertEquals(1, host.get().recents.values.values.single().size)
        setInput("AA,BB")
        assertTrue(announcementTexts().isEmpty())
        click("Done")
        click("Add Hop")
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

    @Test fun actualActivityRecreationRestoresTwoByteDraftBeforePreviewAndSubmission() {
        assertRecreatedDraft("AA00,BB00", "2 B")
    }

    @Test fun actualActivityRecreationRestoresFourByteDraftBeforePreviewAndSubmission() {
        assertRecreatedDraft("AA000000,BB000000", "4 B")
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

    private fun paste(text: String) {
        val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("trace codes", text))
        click("Paste from clipboard")
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

    private fun input() = nodes().single { it.config.contains(SemanticsActions.SetText) }

    private fun button(label: String) = nodes().single {
        it.config.contains(SemanticsActions.OnClick) &&
            it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == label }
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
        return checkNotNull(find(content)) { "Production code-entry UI must be attached" }
    }
}

class TraceCodeInputTestActivity : ComponentActivity() {
    val frames = BroadcastFrameClock()
    lateinit var content: ComposeView
        private set
    internal lateinit var focusManager: FocusManager
    internal val recents = MapRecentsStorage()
    internal val sender = FakeTraceSender()
    private val dependencies = FakeTraceDependencies().apply {
        device = device()
        directory = FakeNodeDirectory(contacts = listOf(contact(0xAA, name = "Tower"), contact(0xBB, name = "Ridge")) +
            (1..40).map { contact(it, name = "Repeater $it") })
        this.sender = this@TraceCodeInputTestActivity.sender
    }
    private var traceScreenMounted = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    }

    internal fun mountTraceScreen() {
        traceScreenMounted = true
        content.setContent {
            focusManager = LocalFocusManager.current
            MaterialTheme {
                TracePathScreen(TracePathScreen.Dependencies(dependencies, recents, RecordingDiagnostics(), RADIO, 1))
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("traceScreenMounted", traceScreenMounted)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        content.disposeComposition()
        frames.cancel()
        super.onDestroy()
    }
}
