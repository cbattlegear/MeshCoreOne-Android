// AndroidOnly: WP-314 TS-09 Mounted code/paste actions use the production parser and width policy.
// AndroidOnly: WP-314 TS-11 Mounted path editing controls drive the real state holder.
package com.meshcoreone.android.feature.tools.trace

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
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
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
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
            nodes().single { it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text } }
        }
        textNodes.forEach { node ->
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            assertTrue(assertNotNull(node.config[SemanticsActions.GetTextLayoutResult].action)(layouts))
            val layout = layouts.single()
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

    @Test fun sameRadioReconnectRejectsRetainedAutoReturn() = retainedListActionAcrossReconnect(false, "auto")
    @Test fun differentRadioReconnectRejectsRetainedAutoReturn() = retainedListActionAcrossReconnect(true, "auto")
    @Test fun sameRadioReconnectRejectsRetainedBatchToggle() = retainedListActionAcrossReconnect(false, "batch")
    @Test fun differentRadioReconnectRejectsRetainedBatchToggle() = retainedListActionAcrossReconnect(true, "batch")
    @Test fun sameRadioReconnectRejectsRetainedHashMode() = retainedListActionAcrossReconnect(false, "hash")
    @Test fun differentRadioReconnectRejectsRetainedHashMode() = retainedListActionAcrossReconnect(true, "hash")
    @Test fun sameRadioReconnectRejectsRetainedRepeaterAdd() = retainedListActionAcrossReconnect(false, "repeater")
    @Test fun differentRadioReconnectRejectsRetainedRepeaterAdd() = retainedListActionAcrossReconnect(true, "repeater")
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
            directory = FakeNodeDirectory(contacts = listOf(contact(0xA1, name = "Alpha"), contact(0xB2, name = "Bravo")))
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
        scrollToIndex(11)
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
            "run", "cancel" -> 11
            else -> 4
        })
        when (action) {
            "auto" -> host.get().runOnUiThread { assertTrue(switchAction(false)()) }
            "batch" -> host.get().runOnUiThread { assertTrue(switchAction(true)()) }
            "hash" -> click("4 B")
            "repeater" -> click("Bravo")
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
        if (codeAction || action == "repeater") {
            scrollToIndex(12)
            click(runLabel)
            assertEquals(Bytes.of(0xA1, 0xB2), recorded.sent.last().path, "Fresh B $action changes the real route")
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
        ))
        content.setContent { MaterialTheme { TracePathScreen(connection.value) } }
        settle()
        click(host.get().getString(C.contactsTraceSaved))
        click(path.name)
        val isClear = action == "dialog" || action == "confirm"
        val resource = when (action) {
            "up" -> C.contactsDetailAvatarCropMoveUp
            "down" -> C.contactsDetailAvatarCropMoveDown
            else -> L.commonDelete
        }
        scrollToIndex(if (action == "up") 3 else 2)
        val label = "${host.get().getString(resource)}: ${
            C.contactsTraceListHopLabel(host.get().resources, if (action == "up") 2 else 1,
                if (action == "up") "Bravo (B2)" else "Alpha (A1)")
        }"
        val oldHop = if (isClear) null else assertNotNull(actionButton(label).config[SemanticsActions.OnClick].action)
        scrollToIndex(5)
        val clearLabel = host.get().getString(C.contactsTraceClearPath)
        val oldOpen = assertNotNull(button(clearLabel).config[SemanticsActions.OnClick].action)
        val oldConfirm = if (isClear) {
            click(clearLabel)
            assertNotNull(button(clearLabel).config[SemanticsActions.OnClick].action).also {
                if (action == "confirm") click(host.get().getString(L.commonCancel))
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
        scrollToIndex(14)
        click(host.get().getString(C.contactsTraceListRunTrace))
        assertEquals(Bytes.of(0xA1, 0xB2, 0xC3), recorded.sent.single().path, "B retains the editable route")
        host.get().runOnUiThread { assertTrue((oldConfirm ?: assertNotNull(oldHop))()) }
        settle()
        assertTrue(texts().contains(host.get().getString(C.contactsTraceListRunningTrace)),
            "A's retained edit must not change B's route or cancel B's trace")
        assertEquals(path, persistence.paths[path.id])
        if (isClear) {
            host.get().runOnUiThread { assertTrue(oldOpen()) }
            settle()
            assertFalse(ShadowDialog.getLatestDialog()?.isShowing == true, "A's retained open cannot create a B dialog")
            scrollToIndex(5)
            click(clearLabel)
            click(clearLabel)
            assertTrue(texts().contains(host.get().getString(C.contactsTraceListEmptyPath)))
            assertFalse(texts().contains(host.get().getString(C.contactsTraceListRunningTrace)))
        } else {
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
            clickDescription("$deleteLabel: ${newPath.name}")
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
        clickDescription("${host.get().getString(L.commonDelete)}: ${replacement.name}")
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
        clickDescription("${host.get().getString(L.commonDelete)}: ${path.name}")
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
        clickDescription(deleteLabel)
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

    private fun mountPathList(harness: TraceHarness, fontScale: Float = 1f) {
        content.setContent {
            key(harness) {
                val state = harness.holder.state.collectAsStateWithLifecycle().value
                val codeInput = rememberTraceCodeInputState(harness.holder)
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    MaterialTheme { TracePathListContent(state, harness.holder, codeInput) }
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
        val root = ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: content
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
    private var signaledRadioOverride: RadioId? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        savedState.performRestore(savedInstanceState)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        savedInstanceState?.getString("firmware")?.let(::setFirmware)
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
                    ),
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
