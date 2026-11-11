// AndroidOnly: WP-311 Native API31/37 Compose semantics and narrow-window regressions.
package com.meshcoreone.android.feature.nodes.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.createLifecycleAwareWindowRecomposer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsOwner
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.share.ContactQrShareStateHolder
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Harness
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactPathActionsApi31ComposeTest : NodesScreensComposeTestBase.ContactPathActionsComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactPathActionsApi37ComposeTest : NodesScreensComposeTestBase.ContactPathActionsComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryUiApi31ComposeTest : NodesScreensComposeTestBase.DiscoveryScreenComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryUiApi37ComposeTest : NodesScreensComposeTestBase.DiscoveryScreenComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactShareApi31ComposeTest : NodesScreensComposeTestBase.ContactShareComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactShareApi37ComposeTest : NodesScreensComposeTestBase.ContactShareComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactHopSortApi31ComposeTest : NodesScreensComposeTestBase.ContactHopSortComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactHopSortApi37ComposeTest : NodesScreensComposeTestBase.ContactHopSortComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactsImmediateAdmissionApi31ComposeTest :
    NodesScreensComposeTestBase.ContactsImmediateAdmissionComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactsImmediateAdmissionApi37ComposeTest :
    NodesScreensComposeTestBase.ContactsImmediateAdmissionComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodesLocationApi31ComposeTest : NodesScreensComposeTestBase.NodesLocationComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NodesLocationApi37ComposeTest : NodesScreensComposeTestBase.NodesLocationComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryLiveApi31ComposeTest : NodesScreensComposeTestBase.DiscoveryLiveComposeTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryLiveApi37ComposeTest : NodesScreensComposeTestBase.DiscoveryLiveComposeTestBase()

abstract class NodesScreensComposeTestBase(deterministicFrames: Boolean = false) {
    protected lateinit var controller: ActivityController<ComponentActivity>
    private val frameClock = if (deterministicFrames) BroadcastFrameClock() else null
    private var frameNanos = 0L

    @Before
    fun attachHost() {
        idleMainLooper()
        controller = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        controller.get().actionBar?.hide()
        controller.windowFocusChanged(true)
    }

    @After
    fun disposeHost() {
        controller.windowFocusChanged(false)
        controller.pause().stop().destroy()
        idleMainLooper()
        frameClock?.cancel()
    }

    protected fun mount(content: @Composable () -> Unit) {
        controller.get().runOnUiThread {
            val parent = frameClock?.let {
                controller.get().window.decorView.createLifecycleAwareWindowRecomposer(
                    coroutineContext = it,
                    lifecycle = controller.get().lifecycle,
                )
            }
            controller.get().setContent(parent = parent) {
                CompositionLocalProvider(
                    LocalDensity provides Density(
                        density = controller.get().resources.displayMetrics.density,
                        fontScale = 2f,
                    ),
                    LocalLayoutDirection provides LayoutDirection.Rtl,
                ) {
                    MaterialTheme(content = content)
                }
            }
        }
        advanceFrames()
    }

    protected fun scrollToAction(label: String): SemanticsNode {
        var lastNode: SemanticsNode? = null
        var lastScrollResult: Boolean? = null
        repeat(20) {
            advanceFrames()
            actionNode(label)?.let { action ->
                lastNode = action
                if (isDisplayed(action)) return action
            }
            val scrollable = allSemanticsNodes().firstOrNull { it.config.contains(SemanticsActions.ScrollBy) }
            val scroll = scrollable?.config?.getOrElseNullable(SemanticsActions.ScrollBy) { null }?.action
            assertNotNull(scroll, "A scroll action must remain available to reveal $label")
            controller.get().runOnUiThread { lastScrollResult = scroll.invoke(0f, 600f) }
        }
        val root = findComposeView(rootView())
        val scrollState = allSemanticsNodes()
            .filter { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            .mapNotNull { it.config.getOrElseNullable(SemanticsProperties.VerticalScrollAxisRange) { null } }
            .joinToString { "${it.value.invoke()}/${it.maxValue.invoke()} reverse=${it.reverseScrolling}" }
        error(
            "$label never acquired visible Compose bounds; root=${root.width}x${root.height}, " +
                "target=${lastNode?.boundsInRoot}, scrollResult=$lastScrollResult, " +
                "scrollState=$scrollState, " +
                "nodes=${allSemanticsNodes().joinToString { "${nodeText(it)}@${it.boundsInRoot}" }}",
        )
    }

    protected fun awaitText(text: String): SemanticsNode {
        repeat(100) {
            Thread.sleep(10)
            advanceFrames()
            allSemanticsNodes().firstOrNull { nodeText(it).contains(text) && isDisplayed(it) }?.let { return it }
        }
        error(
            "Timed out waiting for '$text': " +
                allSemanticsNodes().joinToString { "${nodeText(it)}@${it.boundsInRoot}" },
        )
    }

    protected fun awaitAction(label: String): SemanticsNode {
        repeat(100) {
            Thread.sleep(10)
            advanceFrames()
            actionNode(label)?.let { return it }
        }
        error(
            "Timed out waiting for action '$label': " +
                allSemanticsNodes().joinToString { "${nodeText(it)}@${it.boundsInRoot}" },
        )
    }

    protected fun actionNode(label: String): SemanticsNode? =
        allSemanticsNodes().firstOrNull { node ->
            node.config.contains(SemanticsActions.OnClick) && nodeText(node).any { it == label }
        }

    private fun nodeText(node: SemanticsNode): List<String> =
        node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text }

    protected fun allSemanticsNodes(): List<SemanticsNode> {
        val view = findComposeView(rootView())
        val owner = view.javaClass.getMethod("getSemanticsOwner").invoke(view) as SemanticsOwner
        val result = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            result += node
            node.children.forEach(::visit)
        }
        visit(owner.rootSemanticsNode)
        return result
    }

    private fun findComposeView(view: View): View {
        return findComposeViewOrNull(view)
            ?: throw AssertionError("Compose root view is not attached")
    }

    private fun findComposeViewOrNull(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) {
            repeat(view.childCount) { index ->
                findComposeViewOrNull(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    protected fun isDisplayed(node: SemanticsNode): Boolean {
        val root = findComposeView(rootView())
        val bounds: Rect = node.boundsInRoot
        return bounds.width > 0f && bounds.height > 0f &&
            bounds.right > 0f && bounds.bottom > 0f &&
            bounds.left < root.width && bounds.top < root.height
    }

    protected fun currentDensity() = controller.get().resources.displayMetrics.density

    protected open fun rootView(): View = controller.get().window.decorView

    protected fun advanceFrames() {
        repeat(if (frameClock == null) 1 else 6) {
            controller.get().runOnUiThread {
                Snapshot.sendApplyNotifications()
                frameClock?.let {
                    frameNanos += 16_000_000L
                    it.sendFrame(frameNanos)
                }
            }
            idleMainLooper()
        }
        controller.get().runOnUiThread {
            val view = findComposeView(rootView())
            if (view.width > 0 && view.height > 0) {
                view.forceLayout()
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY),
                )
                view.layout(view.left, view.top, view.right, view.bottom)
            }
        }
    }

    protected fun click(node: SemanticsNode) {
        val action = node.config[SemanticsActions.OnClick].action
        assertNotNull(action)
        controller.get().runOnUiThread { assertTrue(action.invoke()) }
        advanceFrames()
    }

    private fun idleMainLooper() {
        repeat(6) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16))
        }
    }

    protected fun awaitCondition(predicate: () -> Boolean) {
        repeat(100) {
            advanceFrames()
            if (predicate()) return
            Thread.sleep(10)
        }
        assertTrue(predicate(), "The mounted operation did not complete")
    }

    abstract class DiscoveryLiveComposeTestBase : NodesScreensComposeTestBase(deterministicFrames = true) {
        private var menuOpen = false
        override fun rootView(): View =
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: if (menuOpen) {
                checkNotNull(WindowInspector.getGlobalWindowViews().lastOrNull { it.isShown })
            } else super.rootView()

        @Test
        fun heldRefreshCannotResurrectAMountedRemovedRow() = verifyHeldMutation(clear = false)

        @Test
        fun heldRefreshCannotResurrectMountedClearedRows() = verifyHeldMutation(clear = true)

        @Test
        fun heldRemoveCompletionRestartsAPendingReplacementRadioRead() = verifyHeldWriteReplacement(clear = false)

        @Test
        fun heldClearCompletionRestartsAPendingReplacementRadioRead() = verifyHeldWriteReplacement(clear = true)

        private fun clickMutation(clear: Boolean) {
            val context = controller.get()
            if (clear) {
                click(awaitAction(context.getString(C.contactsListSort)))
                menuOpen = true
                click(awaitAction(context.getString(C.contactsDiscoveryClear)))
                menuOpen = false
                click(awaitAction(context.getString(C.contactsDiscoveryClearConfirm)))
            } else {
                click(awaitAction(context.getString(C.contactsDiscoveryRemove)))
            }
        }

        private fun verifyHeldWriteReplacement(clear: Boolean) {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += Fixtures.node(radioId = radio, name = "Old radio row")
            val writeEntered = CompletableDeferred<Unit>()
            val releaseWrite = CompletableDeferred<Unit>()
            val heldWrite: suspend () -> Unit = { writeEntered.complete(Unit); releaseWrite.await() }
            if (clear) harness.store.clearDiscoveredGate = heldWrite else harness.store.deleteDiscoveredGate = heldWrite
            mount { DiscoveryScreen(harness.dependencies) }
            awaitText("Old radio row")
            clickMutation(clear)
            awaitCondition { writeEntered.isCompleted }
            val replacement = Harness().connect()
            val replacementRadio = checkNotNull(replacement.session.radioId)
            val latest = Fixtures.node(radioId = replacementRadio, name = "New radio row")
            replacement.store.discovered += latest
            val readEntered = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            var readExited = false
            replacement.store.fetchContactKeysGate = {
                if (!readEntered.isCompleted) {
                    readEntered.complete(Unit)
                    try {
                        releaseRead.await()
                    } finally {
                        readExited = true
                    }
                }
            }
            controller.get().runOnUiThread {
                harness.session.offlineStore = replacement.store
                harness.session.servicesStore = replacement.store
                harness.session.radioId = replacementRadio
                harness.session.contacts = replacement.contactService
                harness.session.device = replacement.session.device
            }
            awaitCondition { readEntered.isCompleted }
            releaseWrite.complete(Unit)
            awaitText(latest.name)
            assertTrue(readExited, "Mutation completion must replace the invalidated new-radio read")
            releaseRead.complete(Unit)
            advanceFrames()
            assertEquals(listOf(latest), replacement.store.discovered)
            assertTrue(harness.store.discovered.isEmpty())
            assertTrue(replacement.store.discoveredFetches >= 2, "Discarding a read must not discard its invalidation")
            assertTrue(isDisplayed(awaitText(latest.name)))
        }

        private fun verifyHeldMutation(clear: Boolean) {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += Fixtures.node(radioId = radio, name = "Remove while refreshing")
            mount { DiscoveryScreen(harness.dependencies) }
            awaitText("Remove while refreshing")
            val survivor = Fixtures.node(radioId = radio, name = "Surviving node", publicKey = Fixtures.repeated(0x72))
            if (!clear) {
                harness.store.discovered += survivor
                harness.store.contacts += Fixtures.contact(radioId = radio, publicKey = survivor.publicKey)
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var returned = false
            harness.store.fetchContactKeysGate = {
                if (!entered.isCompleted) {
                    entered.complete(Unit)
                    try {
                        release.await()
                    } finally {
                        returned = true
                    }
                }
            }
            controller.get().runOnUiThread { harness.session.versions.value++ }
            awaitCondition { entered.isCompleted }
            val context = controller.get()
            clickMutation(clear)
            if (clear) {
                awaitText(context.getString(C.contactsDiscoveryEmptyTitle))
                assertTrue(harness.store.discovered.isEmpty())
            } else {
                awaitText(survivor.name)
                awaitText(context.getString(C.contactsDiscoveryAdded))
                assertEquals(listOf(survivor), harness.store.discovered)
            }
            release.complete(Unit)
            awaitCondition { returned && harness.store.discoveredFetches >= 3 }
            assertTrue(isDisplayed(awaitText(if (clear) context.getString(C.contactsDiscoveryEmptyTitle) else survivor.name)))
            assertFalse(allSemanticsNodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == "Remove while refreshing" }
            })
            assertTrue(harness.contactService.calls.isEmpty())
        }

        @Test
        fun heldBackgroundRefreshKeepsTheScrolledListMountedAndRetainsItsAnchor() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += (0..19).map {
                Fixtures.node(radioId = radio, name = "Node ${it.toString().padStart(2, '0')}")
            }
            mount { DiscoveryScreen(harness.dependencies) }
            awaitText("Node 00")
            fun list() = allSemanticsNodes().single { it.config.contains(SemanticsActions.ScrollToIndex) }
            val listId = list().id
            controller.get().runOnUiThread {
                assertTrue(checkNotNull(list().config[SemanticsActions.ScrollToIndex].action).invoke(10))
            }
            awaitText("Node 10")
            val offset = list().config[SemanticsProperties.VerticalScrollAxisRange].value()
            assertTrue(offset > 0f, "The regression must start from a genuinely scrolled list")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            harness.store.fetchDiscoveredGate = { entered.complete(Unit); release.await() }
            controller.get().runOnUiThread {
                val index = harness.store.discovered.indexOfFirst { it.name == "Node 10" }
                harness.store.discovered[index] = harness.store.discovered[index].copy(name = "Node 10!")
                harness.session.versions.value++
            }
            awaitCondition { entered.isCompleted }
            assertEquals(listId, list().id, "A pending background refresh must not dispose the lazy list")
            assertEquals(offset, list().config[SemanticsProperties.VerticalScrollAxisRange].value())
            assertTrue(isDisplayed(awaitText("Node 10")))
            release.complete(Unit)
            awaitText("Node 10!")
            assertEquals(listId, list().id)
            assertEquals(offset, list().config[SemanticsProperties.VerticalScrollAxisRange].value())
        }

        @Test
        fun mountedDiscoveryReloadsNewRowsAndExternalContactAdmissionOnVersionChanges() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val first = Fixtures.node(radioId = radio, name = "First discovery")
            val latest = Fixtures.node(radioId = radio, name = "Late discovery", publicKey = Fixtures.repeated(0x71))
            harness.store.discovered += first
            mount { DiscoveryScreen(harness.dependencies) }
            awaitText(first.name)
            controller.get().runOnUiThread {
                harness.store.discovered.clear()
                harness.store.discovered += latest
                harness.session.versions.value++
            }
            awaitText(latest.name)
            assertFalse(allSemanticsNodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == first.name }
            })
            assertNotNull(actionNode(controller.get().getString(C.contactsDiscoveryAdd)))
            controller.get().runOnUiThread {
                harness.store.contacts += Fixtures.contact(radioId = radio, publicKey = latest.publicKey)
                harness.session.versions.value++
            }
            awaitText(controller.get().getString(C.contactsDiscoveryAdded))
            assertTrue(actionNode(controller.get().getString(C.contactsDiscoveryAdd)) == null)
            assertTrue(harness.contactService.calls.isEmpty(), "External persistence refresh must not send an add command")
        }

        @Test
        fun sameRadioReplacementCancelsHeldDiscoveryReadAndShowsTheReplacementStore() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += Fixtures.node(radioId = radio, name = "Retired discovery")
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var cancelled = false
            harness.store.fetchDiscoveredGate = {
                entered.complete(Unit)
                try {
                    release.await()
                } finally {
                    cancelled = true
                }
            }
            mount { DiscoveryScreen(harness.dependencies) }
            awaitCondition { entered.isCompleted }
            val replacement = Harness().connect(radio)
            replacement.store.discovered += Fixtures.node(radioId = radio, name = "Replacement discovery")
            controller.get().runOnUiThread {
                harness.session.offlineStore = replacement.store
                harness.session.servicesStore = replacement.store
                harness.session.contacts = replacement.contactService
                harness.session.device = replacement.session.device
            }
            awaitText("Replacement discovery")
            assertTrue(cancelled, "The old fetch must be cancelled, not publish after the replacement read")
            release.complete(Unit)
            advanceFrames()
            assertFalse(allSemanticsNodes().any {
                it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { text -> text.text == "Retired discovery" }
            })
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(replacement.contactService.calls.isEmpty())
        }

        @Test
        fun stoppedDiscoveryDoesNotReadAndResumingLoadsLatestPersistedRows() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += Fixtures.node(radioId = radio, name = "Before stop")
            mount { DiscoveryScreen(harness.dependencies) }
            awaitText("Before stop")
            controller.pause().stop()
            advanceFrames()
            val reads = harness.store.discoveredFetches
            harness.store.discovered.clear()
            harness.store.discovered += Fixtures.node(radioId = radio, name = "While stopped")
            harness.session.versions.value++
            advanceFrames()
            assertEquals(reads, harness.store.discoveredFetches, "A stopped screen must not read the store")
            controller.start().resume().visible()
            controller.windowFocusChanged(true)
            awaitText("While stopped")
            assertTrue(harness.store.discoveredFetches > reads)
            assertTrue(harness.contactService.calls.isEmpty())
        }
    }

    abstract class NodesLocationComposeTestBase : NodesScreensComposeTestBase(deterministicFrames = true) {
        private var menuOpen = false
        override fun rootView(): View = if (menuOpen) {
            checkNotNull(WindowInspector.getGlobalWindowViews().lastOrNull { it.isShown })
        } else super.rootView()

        @Test
        fun contactsDistanceReordersOnLocationChangesAndFallsBackWhenUnavailable() = verifyLocation(discovery = false)

        @Test
        fun discoveryDistanceReordersOnLocationChangesAndFallsBackWhenUnavailable() = verifyLocation(discovery = true)

        private fun verifyLocation(discovery: Boolean) {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.contacts += listOf(
                Fixtures.contact(radioId = radio, name = "Alpha", latitude = 10.0, longitude = 10.0, lastModified = 1u),
                Fixtures.contact(radioId = radio, name = "Zulu", latitude = 20.0, longitude = 20.0, lastModified = 2u),
            )
            harness.store.discovered += listOf(
                Fixtures.node(radioId = radio, publicKey = Fixtures.repeated(0x11), name = "Alpha", latitude = 10.0, longitude = 10.0, lastHeard = java.time.Instant.ofEpochSecond(1)),
                Fixtures.node(radioId = radio, publicKey = Fixtures.repeated(0x22), name = "Zulu", latitude = 20.0, longitude = 20.0, lastHeard = java.time.Instant.ofEpochSecond(2)),
            )
            mount {
                if (discovery) DiscoveryScreen(harness.dependencies)
                else NodesListScreen(harness.dependencies, NodesNavigation.NONE)
            }
            awaitText("Alpha")
            awaitText("Zulu")
            val context = controller.get()
            click(awaitAction(context.getString(C.contactsListSort)))
            menuOpen = true
            val distance = awaitAction(context.getString(NodeSortOrder.DISTANCE.titleRes))
            menuOpen = false
            click(distance)
            assertOrder("Zulu", "Alpha")
            controller.get().runOnUiThread { harness.locations.value = Coordinate(10.0, 10.0) }
            assertOrder("Alpha", "Zulu")
            controller.get().runOnUiThread { harness.locations.value = Coordinate(20.0, 20.0) }
            assertOrder("Zulu", "Alpha")
            controller.get().runOnUiThread { harness.locations.value = Coordinate(10.0, 10.0) }
            assertOrder("Alpha", "Zulu")
            controller.get().runOnUiThread { harness.locations.value = null }
            assertOrder("Zulu", "Alpha")
            assertTrue(harness.contactService.calls.isEmpty(), "Location changes must not issue radio commands")
        }

        private fun assertOrder(first: String, second: String) {
            repeat(100) {
                advanceFrames()
                fun bounds(text: String) = allSemanticsNodes().firstOrNull {
                    isDisplayed(it) && it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == text }
                }?.boundsInRoot
                val a = bounds(first)
                val b = bounds(second)
                if (a != null && b != null && a.top < b.top) return
                Thread.sleep(10)
            }
            error("Expected mounted order $first before $second")
        }
    }

    abstract class ContactHopSortComposeTestBase : NodesScreensComposeTestBase(deterministicFrames = true) {
        private var sortMenuOpen = false

        override fun rootView(): View =
            if (sortMenuOpen) {
                checkNotNull(WindowInspector.getGlobalWindowViews().lastOrNull { it.isShown })
            } else {
                super.rootView()
            }

        @Test
        fun hopOnlyRefreshReordersVisibleContactsWithoutReselectingSort() {
            exerciseHopRefresh(NodeSortOrder.HOPS)
        }

        @Test
        fun hopOnlyRefreshPreservesNameSort() {
            exerciseHopRefresh(NodeSortOrder.NAME)
        }

        private fun exerciseHopRefresh(sortOrder: NodeSortOrder) {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val fixed = Fixtures.contact(
                radioId = radio, name = "Fixed", publicKey = Fixtures.repeated(0x11), outPathLength = 2u,
            )
            val flood = Fixtures.contact(
                radioId = radio, name = "Flood", publicKey = Fixtures.repeated(0x22), outPathLength = 0xFFu,
            )
            val contacts = listOf(fixed, flood)
            harness.store.contacts += contacts
            val discovered = Fixtures.node(radioId = radio, publicKey = flood.publicKey, name = flood.name)
            harness.store.discovered += discovered
            val loadedHops = mutableListOf<Long?>()
            harness.store.fetchContactsGate = { loadedHops += harness.store.discovered.single().inboundHopCount }
            mount { NodesListScreen(harness.dependencies, NodesNavigation.NONE) }

            val context = controller.get()
            awaitText(fixed.name)
            awaitText(flood.name)
            click(awaitAction(context.getString(C.contactsListSort)))
            sortMenuOpen = true
            val sortAction = awaitAction(context.getString(sortOrder.titleRes))
            sortMenuOpen = false
            click(sortAction)
            assertVisibleOrder(fixed.name, flood.name)

            val hopUpdates = listOf(1L, 3L, null)
            hopUpdates.forEachIndexed { index, hops ->
                harness.store.discovered[0] = discovered.copy(inboundHopCount = hops)
                click(awaitAction(context.getString(C.contactsListSyncNodes)))
                awaitSync(harness, index + 1)
                assertEquals(listOf(null) + hopUpdates.take(index + 1), loadedHops, "Every hop update must be loaded")
                assertEquals(contacts, harness.store.contacts, "Only discovered inbound hops may change")
                if (sortOrder == NodeSortOrder.HOPS && hops == 1L) {
                    assertVisibleOrder(flood.name, fixed.name)
                } else {
                    assertVisibleOrder(fixed.name, flood.name)
                }
            }
            assertEquals(listOf("sync", "sync", "sync"), harness.contactService.calls)
            assertEquals(listOf(true, false, true, false, true, false), harness.adverts.syncingFlags)
        }

        private fun awaitSync(harness: Harness, count: Int) {
            val expectedCalls = List(count) { "sync" }
            val expectedFlags = List(count) { listOf(true, false) }.flatten()
            repeat(100) {
                Thread.sleep(10)
                advanceFrames()
                if (harness.contactService.calls == expectedCalls && harness.adverts.syncingFlags == expectedFlags) return
            }
            assertEquals(expectedCalls, harness.contactService.calls, "Each refresh must invoke the live Sync action")
            assertEquals(expectedFlags, harness.adverts.syncingFlags, "Each refresh must complete before checking order")
        }

        private fun assertVisibleOrder(first: String, second: String) {
            var lastBounds: Pair<Rect?, Rect?> = null to null
            repeat(100) {
                Thread.sleep(10)
                advanceFrames()
                val nodes = allSemanticsNodes()
                fun visibleBounds(text: String): Rect? =
                    nodes.firstOrNull {
                        it.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { value -> value.text == text } &&
                            isDisplayed(it)
                    }?.boundsInRoot
                val firstBounds = visibleBounds(first)
                val secondBounds = visibleBounds(second)
                lastBounds = firstBounds to secondBounds
                if (firstBounds != null && secondBounds != null && firstBounds.top < secondBounds.top) return
            }
            error(
                "$first must appear above $second after refresh; $first=${lastBounds.first}, $second=${lastBounds.second}; " +
                    "nodes=${allSemanticsNodes().joinToString {
                        "${it.config.getOrElse(SemanticsProperties.Text) { emptyList() }}@${it.boundsInRoot}"
                    }}",
            )
        }
    }

    abstract class ContactPathActionsComposeTestBase : NodesScreensComposeTestBase() {
        @Test
        @Config(shadows = [UnsupportedNodesMapProcess::class])
        fun locatedContactOpensOfflineLineOfSightWithTheExactContact() {
            val harness = Harness().connect()
            val contact = Fixtures.contact(
                radioId = checkNotNull(harness.session.radioId),
                name = "LOS target",
                latitude = 37.8,
                longitude = -122.4,
            )
            harness.store.contacts += contact
            harness.session.state = DeviceConnectionState.DISCONNECTED
            harness.session.contacts = null
            val opened = mutableListOf<ContactDTO>()
            val navigation = object : NodesNavigation by NodesNavigation.NONE {
                override val openLineOfSight: (ContactDTO) -> Unit = { opened += it }
            }
            mount { ContactDetailScreen(contact, harness.dependencies, navigation) }
            val action = scrollToAction(controller.get().getString(AppToolsStrings.toolsLineOfSight))
            assertTrue(action.boundsInRoot.height / currentDensity() >= 48f)
            assertFalse(action.config.contains(SemanticsProperties.Disabled))
            click(action)
            assertEquals(listOf(contact), opened)
            assertTrue(harness.contactService.calls.isEmpty(), "Offline LOS must not issue radio commands")
        }

        @Test
        fun unlocatedContactDoesNotOfferLineOfSight() {
            val harness = Harness().connect()
            val contact = Fixtures.contact(radioId = checkNotNull(harness.session.radioId))
            harness.store.contacts += contact
            val navigation = object : NodesNavigation by NodesNavigation.NONE {
                override val openLineOfSight: (ContactDTO) -> Unit = { error("Unlocated contact must not navigate") }
            }
            mount { ContactDetailScreen(contact, harness.dependencies, navigation) }
            assertTrue(actionNode(controller.get().getString(AppToolsStrings.toolsLineOfSight)) == null)
        }

        @Test
        fun pathActionsRemainVisibleAndAtLeast48DpAtLargeTextInRtl() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val contact = Fixtures.contact(
                radioId = radio,
                type = ContactType.REPEATER,
                name = "Path target",
                outPathLength = 1u,
                outPath = Bytes(byteArrayOf(0x01)),
            )
            harness.store.contacts += contact

            mount { ContactDetailScreen(contact, harness.dependencies, NodesNavigation.NONE) }

            val context = controller.get()
            listOf(C.contactsDetailDiscoverPath, C.contactsDetailEditPath, C.contactsDetailResetPath)
                .map(context::getString)
                .forEach { label ->
                    val action = scrollToAction(label)
                    assertTrue(isDisplayed(action), "$label must have visible bounds after scrolling")
                    assertTrue(action.boundsInRoot.height / currentDensity() >= 48f, "$label must retain a 48dp target")
                    assertFalse(action.config.contains(SemanticsProperties.Disabled), "$label must remain enabled")
                }
        }

        @Test
        fun disconnectedPathActionsRemainVisibleAndDisabled() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val contact = Fixtures.contact(
                radioId = radio,
                type = ContactType.REPEATER,
                name = "Offline path target",
                outPathLength = 1u,
                outPath = Bytes(byteArrayOf(0x01)),
            )
            harness.store.contacts += contact
            harness.session.state = DeviceConnectionState.DISCONNECTED
            harness.session.device = null
            harness.session.contacts = null
            harness.session.adverts = null
            harness.session.trace = null

            mount { ContactDetailScreen(contact, harness.dependencies, NodesNavigation.NONE) }

            val context = controller.get()
            listOf(C.contactsDetailDiscoverPath, C.contactsDetailEditPath, C.contactsDetailResetPath)
                .map(context::getString)
                .forEach { label ->
                    val action = scrollToAction(label)
                    assertTrue(isDisplayed(action), "$label must remain visible while disconnected")
                    assertTrue(action.config.contains(SemanticsProperties.Disabled), "$label must be disabled offline")
                    assertTrue(action.boundsInRoot.height / currentDensity() >= 48f, "$label must retain a 48dp target")
                }
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.adverts.syncingFlags.isEmpty())
        }
    }

    abstract class DiscoveryScreenComposeTestBase : NodesScreensComposeTestBase() {
        override fun rootView(): View =
            ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: super.rootView()

        @Test
        fun disconnectedDiscoveryShowsCachedOfflineResultInsteadOfProgress() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += Fixtures.node(radioId = radio, name = "Cached offline node")
            harness.session.state = DeviceConnectionState.DISCONNECTED
            harness.session.device = null

            mount { DiscoveryScreen(harness.dependencies) }

            assertTrue(isDisplayed(awaitText("Cached offline node")))
            assertFalse(
                allSemanticsNodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) },
            )
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.adverts.syncingFlags.isEmpty())
            assertTrue(harness.trace.sent.isEmpty())

            val context = controller.get()
            listOf(C.contactsDiscoveryAdd, C.contactsDiscoveryRemove).map(context::getString).forEach { label ->
                val action = awaitAction(label)
                assertTrue(isDisplayed(action), "$label must remain visible at 200% font scale")
                assertTrue(action.boundsInRoot.height / currentDensity() >= 48f, "$label must retain a 48dp target")
            }
            val add = awaitAction(context.getString(C.contactsDiscoveryAdd))
            assertFalse(add.config.contains(SemanticsProperties.Disabled))
            click(add)
            assertTrue(
                isDisplayed(awaitText(context.getString(C.contactsAddErrorNotConnected))),
                "Offline Add must explain recovery instead of silently succeeding",
            )
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.store.contacts.isEmpty())
            assertEquals(listOf("Cached offline node"), harness.store.discovered.map { it.name })
            click(awaitAction(context.getString(C.contactsCommonOk)))
            val remove = awaitAction(context.getString(C.contactsDiscoveryRemove))
            assertFalse(remove.config.contains(SemanticsProperties.Disabled))
            click(remove)
            assertTrue(isDisplayed(awaitText(context.getString(C.contactsDiscoveryEmptyTitle))))
            assertTrue(harness.store.discovered.isEmpty())
            assertTrue(harness.contactService.calls.isEmpty())
        }

        @Test
        fun connectedDiscoveryAddUsesTheLiveServiceAndReloadsAddedState() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val node = Fixtures.node(radioId = radio, name = "Ready relay")
            harness.store.discovered += node
            mount { DiscoveryScreen(harness.dependencies) }
            val context = controller.get()
            val add = awaitAction(context.getString(C.contactsDiscoveryAdd))
            assertFalse(add.config.contains(SemanticsProperties.Disabled))
            assertTrue(isDisplayed(add))
            assertTrue(add.boundsInRoot.height / currentDensity() >= 48f)
            click(add)
            assertTrue(isDisplayed(awaitText(context.getString(C.contactsDiscoveryAdded))))
            assertEquals(listOf("add:Ready relay"), harness.contactService.calls)
            assertEquals(node.publicKey, harness.store.contacts.single().publicKey)
            assertEquals(radio, harness.store.contacts.single().radioId)
            assertTrue(harness.adverts.syncingFlags.isEmpty())
            assertTrue(harness.trace.sent.isEmpty())
        }

        @Test
        fun disconnectAfterMountRechecksAddCapabilityBeforeAnyRadioCall() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.store.discovered += Fixtures.node(radioId = radio, name = "Was ready")
            mount { DiscoveryScreen(harness.dependencies) }
            val context = controller.get()
            val add = awaitAction(context.getString(C.contactsDiscoveryAdd))
            harness.session.contacts = null
            harness.session.device = null
            harness.session.state = DeviceConnectionState.DISCONNECTED
            click(add)
            assertTrue(isDisplayed(awaitText(context.getString(C.contactsAddErrorNotConnected))))
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.store.contacts.isEmpty())
        }
    }

    abstract class ContactsImmediateAdmissionComposeTestBase : NodesScreensComposeTestBase(deterministicFrames = true) {
        private var menuOpen = false

        override fun rootView(): View =
            if (menuOpen) {
                checkNotNull(WindowInspector.getGlobalWindowViews().lastOrNull { it.isShown })
            } else {
                ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: super.rootView()
            }

        @Test
        fun mountedManualAddAppendsBeforeListFetchIncludesContact() = verifyAdmission("append", scan = false)

        @Test
        fun mountedManualAddReplacesSameUuidBeforeListFetchIncludesContact() = verifyAdmission("same-id", scan = false)

        @Test
        fun mountedManualAddReplacesSameKeyAndRadioBeforeListFetchIncludesContact() = verifyAdmission("same-key", scan = false)

        @Test
        fun mountedScanAddAppendsBeforeListFetchIncludesContact() = verifyAdmission("append", scan = true)

        @Test
        fun mountedScanAddReplacesSameUuidBeforeListFetchIncludesContact() = verifyAdmission("same-id", scan = true)

        @Test
        fun mountedScanAddReplacesSameKeyAndRadioBeforeListFetchIncludesContact() = verifyAdmission("same-key", scan = true)

        @Test
        fun mountedNewScanConfirmationBindsAddAndImportingState() = verifyScanConfirmationImport(fails = false)

        @Test
        fun mountedFailedScanImportRestoresTheEnabledAddLabelWithoutProgress() = verifyScanConfirmationImport(fails = true)

        @Test
        fun mountedExistingScanConfirmationNamesTheStoredTargetAndOpensWithoutImport() {
            val harness = Harness().connect()
            val stored = Fixtures.contact(
                radioId = checkNotNull(harness.session.radioId),
                name = "Stored node",
                nickname = "Saved alias",
            )
            harness.store.contacts += stored
            val opened = mutableListOf<ContactDTO>()
            openScanConfirmation(harness, "Claimed name", stored.publicKey, opened)
            val context = controller.get()
            val view = awaitAction(context.getString(C.contactsAddView))
            assertEquals(
                listOf(C.contactsAddViewAccessibility(context.resources, stored.displayName)),
                view.config[SemanticsProperties.ContentDescription],
                "View must name the saved target, not the QR's claimed name",
            )
            assertFalse(view.config.contains(SemanticsProperties.Disabled))
            assertFalse(view.config.contains(SemanticsProperties.ProgressBarRangeInfo))
            assertTrue(harness.contactService.calls.isEmpty())
            click(view)
            awaitCondition { opened.isNotEmpty() }
            assertEquals(listOf(stored), opened)
            assertEquals(listOf(stored), harness.store.contacts)
            assertTrue(harness.contactService.calls.isEmpty(), "Viewing a saved contact must not import it again")
        }

        private fun verifyScanConfirmationImport(fails: Boolean) {
            val harness = Harness().connect()
            val key = Fixtures.repeated(0xAB)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val persist = harness.contactService.addBehavior
            harness.contactService.addBehavior = { radio, frame ->
                entered.complete(Unit)
                release.await()
                if (fails) error("held import failed")
                persist(radio, frame)
            }
            val opened = mutableListOf<ContactDTO>()
            openScanConfirmation(harness, "Scanned node", key, opened)
            val context = controller.get()
            val addLabel = context.getString(C.contactsAddAdd)
            val add = awaitAction(addLabel)
            assertEquals(listOf(addLabel), add.config[SemanticsProperties.ContentDescription])
            assertFalse(add.config.contains(SemanticsProperties.Disabled))
            assertFalse(add.config.contains(SemanticsProperties.ProgressBarRangeInfo))
            assertTrue(harness.contactService.calls.isEmpty())
            click(add)
            awaitCondition { entered.isCompleted }
            val importingLabel = context.getString(C.contactsScanImporting)
            val importing = allSemanticsNodes().single {
                it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } == listOf(importingLabel)
            }
            assertTrue(isDisplayed(importing))
            assertTrue(importing.config.contains(SemanticsProperties.Disabled))
            val progress = allSemanticsNodes().single { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) }
            assertEquals(ProgressBarRangeInfo.Indeterminate, progress.config[SemanticsProperties.ProgressBarRangeInfo])
            assertTrue(
                generateSequence(progress) { it.parent }.any { it.id == importing.id },
                "Import progress must belong to the disabled primary action, not an unrelated spinner",
            )
            assertTrue(isDisplayed(progress))
            assertEquals(listOf("add:Scanned node"), harness.contactService.calls)
            assertTrue(opened.isEmpty(), "A held import must not navigate before persistence completes")
            assertTrue(harness.store.contacts.isEmpty())
            release.complete(Unit)
            if (fails) {
                val retry = awaitAction(addLabel)
                assertEquals(listOf(addLabel), retry.config[SemanticsProperties.ContentDescription])
                assertFalse(retry.config.contains(SemanticsProperties.Disabled))
                assertFalse(allSemanticsNodes().any { it.config.contains(SemanticsProperties.ProgressBarRangeInfo) })
                assertTrue(opened.isEmpty())
                assertTrue(harness.store.contacts.isEmpty())
            } else {
                awaitCondition { opened.isNotEmpty() }
                assertEquals(listOf(harness.store.contacts.single()), opened)
                assertEquals(key, opened.single().publicKey)
                assertEquals("Scanned node", opened.single().name)
            }
            assertEquals(listOf("add:Scanned node"), harness.contactService.calls)
        }

        private fun openScanConfirmation(
            harness: Harness,
            claimedName: String,
            key: Bytes,
            opened: MutableList<ContactDTO>,
        ) {
            Shadows.shadowOf(controller.get().application).denyPermissions(Manifest.permission.CAMERA)
            val navigation = object : NodesNavigation by NodesNavigation.NONE {
                override fun openContact(contact: ContactDTO) { opened += contact }
            }
            mount { NodesListScreen(harness.dependencies, navigation) }
            val context = controller.get()
            click(awaitAction(context.getString(C.contactsListAddContact)))
            click(awaitAction(context.getString(C.contactsAddScanQR)))
            val field = allSemanticsNodes().single { it.config.contains(SemanticsActions.SetText) }
            setText(field, harness.codec.exportContactUri(claimedName, key, ContactType.CHAT))
            click(awaitAction(context.getString(C.contactsAddView)))
            awaitText(if (harness.store.contacts.isEmpty()) claimedName else harness.store.contacts.single().displayName)
        }

        @Test
        fun mountedManualAddRejectsReplacementBeforeTheDialogRecomposes() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            mount { NodesListScreen(harness.dependencies, NodesNavigation.NONE) }
            val context = controller.get()
            click(awaitAction(context.getString(C.contactsListAddContact)))
            val fields = allSemanticsNodes().filter { it.config.contains(SemanticsActions.SetText) }
            setText(fields[0], "Old form")
            setText(fields[1], Fixtures.repeated(0xAB).hexString)
            val oldAdd = checkNotNull(awaitAction(context.getString(C.contactsAddAdd)).config[SemanticsActions.OnClick].action)
            val replacement = Harness().connect(radio)
            controller.get().runOnUiThread {
                harness.session.contacts = replacement.contactService
                harness.session.adverts = replacement.adverts
                harness.session.device = replacement.session.device
                harness.session.servicesStore = replacement.store
                harness.session.offlineStore = replacement.store
                assertTrue(oldAdd.invoke())
            }
            advanceFrames()
            assertTrue(harness.contactService.calls.isEmpty(), "Retired form must not dispatch A")
            assertTrue(replacement.contactService.calls.isEmpty(), "A form cannot capture B before recomposition")
            assertTrue(harness.store.contacts.isEmpty())
            assertTrue(replacement.store.contacts.isEmpty())
            val freshFields = allSemanticsNodes().filter { it.config.contains(SemanticsActions.SetText) }
            setText(freshFields[0], "Fresh")
            setText(freshFields[1], Fixtures.repeated(0xCD).hexString)
            click(awaitAction(context.getString(C.contactsAddAdd)))
            awaitText("Fresh")
            assertEquals(listOf("add:Fresh", "getContact"), replacement.contactService.calls)
            assertEquals("Fresh", replacement.store.contacts.single().name)
            assertTrue(harness.contactService.calls.isEmpty())
        }

        @Test
        fun mountedDeleteConfirmationRetiresOnDifferentRadio() = verifyActionOwner(sameRadio = false, delete = true)

        @Test
        fun mountedDeleteConfirmationRetiresOnSameRadioSessionReplacement() = verifyActionOwner(sameRadio = true, delete = true)

        @Test
        fun mountedChatConfirmationRetiresOnDifferentRadio() = verifyActionOwner(sameRadio = false, delete = false)

        @Test
        fun mountedChatConfirmationRetiresOnSameRadioSessionReplacement() = verifyActionOwner(sameRadio = true, delete = false)

        @Test
        fun mountedOpenDeleteMenuRetiresOnDifferentRadio() = verifyActionOwner(sameRadio = false, delete = true, menuHeld = true)

        @Test
        fun mountedOpenDeleteMenuRetiresOnSameRadioSessionReplacement() = verifyActionOwner(sameRadio = true, delete = true, menuHeld = true)

        @Test
        fun mountedOpenChatMenuRetiresOnDifferentRadio() = verifyActionOwner(sameRadio = false, delete = false, menuHeld = true)

        @Test
        fun mountedOpenChatMenuRetiresOnSameRadioSessionReplacement() = verifyActionOwner(sameRadio = true, delete = false, menuHeld = true)

        @Test
        fun mountedRetainedSyncCannotStartReplacementSessionSync() = verifySyncControlOwner(sameRadio = true)

        @Test
        fun mountedRetainedSyncCannotStartReplacementRadioSync() = verifySyncControlOwner(sameRadio = false)

        @Test
        fun mountedSyncCarriesItsOwnerAcrossTheReadinessHandoff() = verifySyncHandoffOwner(ready = true)

        @Test
        fun mountedSyncCannotPublishRetiredNotReadyFailureAcrossTheHandoff() = verifySyncHandoffOwner(ready = false)

        @Test
        fun mountedSyncCannotDisplayRetiredErrorAfterTheFinalOwnershipCheck() =
            verifySyncHandoffOwner(ready = false, finalBoundary = true)

        @Test
        fun mountedCurrentNotReadySyncStillReportsTheConnectError() {
            val harness = Harness().connect()
            harness.session.state = DeviceConnectionState.DISCONNECTED
            mount { NodesListScreen(harness.dependencies, NodesNavigation.NONE) }
            val context = controller.get()
            click(awaitAction(context.getString(C.contactsListSyncNodes)))
            awaitText(context.getString(C.contactsListConnectToSync))
            assertTrue(checkNotNull(ShadowDialog.getLatestDialog()).isShowing)
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.adverts.syncingFlags.isEmpty())
        }

        private fun verifySyncHandoffOwner(ready: Boolean, finalBoundary: Boolean = false) {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            harness.session.state = if (ready) DeviceConnectionState.READY else DeviceConnectionState.DISCONNECTED
            mount { NodesListScreen(harness.dependencies, NodesNavigation.NONE) }
            val label = controller.get().getString(C.contactsListSyncNodes)
            val oldNode = awaitAction(label)
            val replacement = Harness().connect(radio)
            val fresh = Fixtures.contact(radioId = radio, name = "Fresh")
            replacement.store.contacts += fresh
            var swaps = 0
            val replace: () -> Unit = {
                swaps++
                harness.session.contacts = replacement.contactService
                harness.session.adverts = replacement.adverts
                harness.session.device = replacement.session.device
                harness.session.servicesStore = replacement.store
                harness.session.offlineStore = replacement.store
                harness.session.state = DeviceConnectionState.READY
            }
            if (finalBoundary) {
                var checks = 0
                harness.session.onRadioIdRead = {
                    if (++checks == 2) {
                        harness.session.onRadioIdRead = {}
                        replace()
                    }
                }
            } else {
                harness.session.onConnectionStateRead = {
                    harness.session.onConnectionStateRead = {}
                    replace()
                }
            }
            click(oldNode)
            assertEquals(1, swaps, "The graph must actually change during the ready/not-ready handoff")
            advanceFrames()
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(replacement.contactService.calls.isEmpty(), "An A click cannot recapture B at holder entry")
            assertTrue(harness.adverts.syncingFlags.isEmpty())
            assertTrue(replacement.adverts.syncingFlags.isEmpty())
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true, "A's not-ready error cannot be published into B")
            awaitCondition { actionNode(label)?.id?.let { it != oldNode.id } == true }
            click(awaitAction(label))
            awaitCondition { replacement.adverts.syncingFlags == listOf(true, false) }
            awaitText(fresh.name)
            assertEquals(listOf("sync"), replacement.contactService.calls)
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true)
        }

        @Test
        fun mountedShareEnablesWhenReplacementDeviceBecomesReadyWithoutAnotherUiChange() {
            val harness = Harness().connect()
            mount { NodesListScreen(harness.dependencies, NodesNavigation.NONE) }
            val label = controller.get().getString(C.contactsListShareMyContact)
            awaitCondition { actionNode(label)?.config?.contains(SemanticsProperties.Disabled) == false }
            val replacement = Harness().connect(checkNotNull(harness.session.radioId))
            harness.session.contacts = replacement.contactService
            harness.session.device = null
            harness.session.state = DeviceConnectionState.DISCONNECTED
            awaitCondition { actionNode(label)?.config?.contains(SemanticsProperties.Disabled) == true }
            harness.session.device = replacement.session.device
            harness.session.state = DeviceConnectionState.READY
            awaitCondition { actionNode(label)?.config?.contains(SemanticsProperties.Disabled) == false }
        }

        @Test
        fun mountedInitialReadRestartsAfterSyncAndQuietSameRadioReplacement() = verifyInitialLoadReplacement(admit = false)

        @Test
        fun mountedInitialReadReplacementRetainsAnActualManualAdmission() = verifyInitialLoadReplacement(admit = true)

        private fun verifyInitialLoadReplacement(admit: Boolean) {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val initialEntered = CompletableDeferred<Unit>()
            val initialRelease = CompletableDeferred<Unit>()
            harness.store.fetchContactsGate = {
                initialEntered.complete(Unit)
                initialRelease.await()
            }
            val syncEntered = CompletableDeferred<Unit>()
            val syncRelease = CompletableDeferred<Unit>()
            harness.contactService.sync = {
                syncEntered.complete(Unit)
                syncRelease.await()
            }
            mount { NodesListScreen(harness.dependencies, NodesNavigation.NONE) }
            awaitCondition { initialEntered.isCompleted }
            val admitted = Fixtures.contact(radioId = radio, name = "Admitted")
            if (admit) {
                harness.contactService.addBehavior = { _, _ -> }
                harness.contactService.getContactBehavior = { _, _ -> admitted }
                val context = controller.get()
                click(awaitAction(context.getString(C.contactsListAddContact)))
                val fields = allSemanticsNodes().filter { it.config.contains(SemanticsActions.SetText) }
                setText(fields[0], admitted.name)
                setText(fields[1], admitted.publicKey.hexString)
                click(awaitAction(context.getString(C.contactsAddAdd)))
                awaitText(admitted.name)
            }
            val syncLabel = controller.get().getString(C.contactsListSyncNodes)
            click(awaitAction(syncLabel))
            awaitCondition { syncEntered.isCompleted }
            val replacement = Harness().connect(radio)
            val fresh = Fixtures.contact(radioId = radio, name = "Fresh")
            replacement.store.contacts += fresh
            var reads = 0
            replacement.store.fetchContactsGate = { reads++ }
            harness.session.contacts = replacement.contactService
            harness.session.adverts = replacement.adverts
            harness.session.device = replacement.session.device
            harness.session.servicesStore = replacement.store
            harness.session.offlineStore = replacement.store
            awaitText(fresh.name)
            if (admit) awaitText(admitted.name)
            assertFalse(initialRelease.isCompleted, "B must load before A's independent read is released")
            assertFalse(syncRelease.isCompleted)
            assertEquals(1, reads)
            assertTrue(replacement.contactService.calls.isEmpty(), "Rebinding starts a local read, not a radio sync")
            initialRelease.complete(Unit)
            syncRelease.complete(Unit)
            advanceFrames()
            awaitText(fresh.name)
            if (admit) awaitText(admitted.name)
            click(awaitAction(syncLabel))
            awaitCondition { replacement.adverts.syncingFlags == listOf(true, false) }
            assertEquals(2, reads)
            assertEquals(listOf("sync"), replacement.contactService.calls)
            if (admit) awaitText(admitted.name)
        }

        private fun verifySyncControlOwner(sameRadio: Boolean) {
            val harness = Harness().connect()
            val oldRadio = checkNotNull(harness.session.radioId)
            val old = Fixtures.contact(radioId = oldRadio, name = "Old")
            harness.store.contacts += old
            mount { NodesListScreen(harness.dependencies, NodesNavigation.NONE) }
            awaitText(old.name)
            val label = controller.get().getString(C.contactsListSyncNodes)
            val oldNode = awaitAction(label)
            val retainedClick = checkNotNull(oldNode.config[SemanticsActions.OnClick].action)
            val replacement = Harness().connect(if (sameRadio) oldRadio else Fixtures.radio())
            val fresh = Fixtures.contact(radioId = checkNotNull(replacement.session.radioId), name = "Fresh")
            replacement.store.contacts += fresh
            harness.session.radioId = replacement.session.radioId
            harness.session.device = replacement.session.device
            harness.session.contacts = replacement.contactService
            harness.session.adverts = replacement.adverts
            harness.session.servicesStore = replacement.store
            harness.session.offlineStore = replacement.store
            awaitCondition { actionNode(label)?.id?.let { it != oldNode.id } == true }
            controller.get().runOnUiThread { assertTrue(retainedClick.invoke()) }
            advanceFrames()
            assertTrue(harness.contactService.calls.isEmpty(), "Retained A Sync must not send through A")
            assertTrue(replacement.contactService.calls.isEmpty(), "Retained A Sync must not start B")
            assertTrue(harness.adverts.syncingFlags.isEmpty())
            assertTrue(replacement.adverts.syncingFlags.isEmpty())
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true)
            click(awaitAction(label))
            awaitCondition { replacement.adverts.syncingFlags == listOf(true, false) }
            awaitText(fresh.name)
            assertEquals(listOf("sync"), replacement.contactService.calls)
            assertTrue(harness.contactService.calls.isEmpty())
            assertTrue(harness.adverts.syncingFlags.isEmpty())
        }

        @Test
        fun mountedSameRadioReplacementAllowsFreshSyncWhileRetainingAdmission() {
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val admitted = Fixtures.contact(radioId = radio, name = "Admitted")
            harness.contactService.addBehavior = { _, _ -> }
            harness.contactService.getContactBehavior = { _, _ -> admitted }
            mount { NodesListScreen(harness.dependencies, NodesNavigation.NONE) }
            val context = controller.get()
            click(awaitAction(context.getString(C.contactsListAddContact)))
            val fields = allSemanticsNodes().filter { it.config.contains(SemanticsActions.SetText) }
            setText(fields[0], admitted.name)
            setText(fields[1], admitted.publicKey.hexString)
            click(awaitAction(context.getString(C.contactsAddAdd)))
            awaitText(admitted.name)

            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            harness.contactService.sync = {
                entered.complete(Unit)
                release.await()
                error("retired sync failed")
            }
            val syncLabel = context.getString(C.contactsListSyncNodes)
            click(awaitAction(syncLabel))
            awaitCondition { entered.isCompleted }
            val replacement = Harness().connect(radio)
            var replacementReads = 0
            replacement.store.fetchContactsGate = { replacementReads++ }
            harness.session.contacts = replacement.contactService
            harness.session.adverts = replacement.adverts
            harness.session.device = replacement.session.device
            harness.session.servicesStore = replacement.store
            harness.session.offlineStore = replacement.store
            awaitCondition { actionNode(syncLabel)?.config?.contains(SemanticsProperties.Disabled) == false }
            awaitText(admitted.name)
            click(awaitAction(syncLabel))
            awaitCondition { replacement.adverts.syncingFlags == listOf(true, false) }
            assertFalse(release.isCompleted, "B must sync while A's request remains held")
            release.complete(Unit)
            awaitCondition { harness.adverts.syncingFlags == listOf(true, false) }
            awaitText(admitted.name)
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true, "A's retired failure must not create B's error dialog")
            assertEquals(1, replacementReads)
            assertEquals(listOf("sync"), replacement.contactService.calls)
            assertEquals(listOf("add:Admitted", "getContact", "sync"), harness.contactService.calls)
        }

        private fun verifyActionOwner(sameRadio: Boolean, delete: Boolean, menuHeld: Boolean = false) {
            val harness = Harness().connect()
            val oldRadio = checkNotNull(harness.session.radioId)
            val old = Fixtures.contact(radioId = oldRadio, name = "Old target")
            harness.store.contacts += old
            val replacement = Harness().connect(if (sameRadio) oldRadio else Fixtures.radio())
            val radio = checkNotNull(replacement.session.radioId)
            val fresh = old.copy(radioId = radio, name = "Fresh target")
            replacement.store.contacts += fresh
            val opened = mutableListOf<ContactDTO>()
            val chats = mutableListOf<ContactDTO>()
            val navigation = object : NodesNavigation by NodesNavigation.NONE {
                override fun openContact(contact: ContactDTO) { opened += contact }
                override fun openChat(contact: ContactDTO) { chats += contact }
            }
            mount { NodesListScreen(harness.dependencies, navigation) }
            val oldOpen = checkNotNull(awaitAction(old.name).config[SemanticsActions.OnClick].action)
            val context = controller.get()
            val action = context.getString(if (delete) C.contactsCommonDelete else C.contactsDetailSendMessage)
            val confirm = context.getString(if (delete) C.contactsCommonOk else C.contactsCommonDone)
            val oldMenuButton = checkNotNull(awaitAction("\u22EE").config[SemanticsActions.OnClick].action)
            val oldControl = if (menuHeld) {
                click(awaitAction("\u22EE"))
                menuOpen = true
                checkNotNull(awaitAction(action).config[SemanticsActions.OnClick].action)
            } else {
                openRowConfirmation(action)
                assertTrue(checkNotNull(ShadowDialog.getLatestDialog()).isShowing)
                checkNotNull(awaitAction(confirm).config[SemanticsActions.OnClick].action)
            }

            harness.session.radioId = radio
            harness.session.device = replacement.session.device
            harness.session.contacts = replacement.contactService
            harness.session.adverts = replacement.adverts
            harness.session.offlineStore = replacement.store
            harness.session.servicesStore = replacement.store
            awaitCondition {
                ShadowDialog.getLatestDialog()?.isShowing != true &&
                    actionNode(context.getString(C.contactsListSyncNodes))?.config?.contains(SemanticsProperties.Disabled) == false
            }
            menuOpen = false
            assertTrue(actionNode(action) == null, "The old menu must retire without selecting an item")
            controller.get().runOnUiThread {
                assertTrue(oldMenuButton.invoke())
                assertTrue(oldControl.invoke())
                assertTrue(oldOpen.invoke())
            }
            advanceFrames()
            assertTrue(ShadowDialog.getLatestDialog()?.isShowing != true, "Retained controls must not recreate a confirmation")
            assertTrue(actionNode(action) == null, "Retained menu buttons must not open B's menu")
            assertTrue(harness.contactService.calls.isEmpty(), "Retired controls must not use A's service")
            assertTrue(replacement.contactService.calls.isEmpty(), "Retired controls must not use B's service")
            assertTrue(opened.isEmpty(), "Retired row navigation must not open A on B")
            assertTrue(chats.isEmpty(), "Retired confirmation must not navigate A on B")

            click(awaitAction(context.getString(C.contactsListSyncNodes)))
            awaitText(fresh.name)
            awaitCondition { replacement.adverts.syncingFlags == listOf(true, false) }
            click(awaitAction(fresh.name))
            assertEquals(listOf(fresh), opened)
            replacement.contactService.removeContact = { requestedRadio, key ->
                assertEquals(radio, requestedRadio)
                assertEquals(fresh.publicKey, key)
            }
            openRowConfirmation(action)
            click(awaitAction(confirm))
            if (delete) {
                awaitCondition { replacement.contactService.calls == listOf("sync", "remove") }
                assertTrue(chats.isEmpty())
            } else {
                assertEquals(listOf(fresh), chats)
                assertEquals(listOf("sync"), replacement.contactService.calls)
            }
            assertTrue(harness.contactService.calls.isEmpty())
        }

        private fun openRowConfirmation(action: String) {
            click(awaitAction("\u22EE"))
            menuOpen = true
            val item = awaitAction(action)
            menuOpen = false
            click(item)
        }

        private fun verifyAdmission(identity: String, scan: Boolean) {
            if (scan) Shadows.shadowOf(controller.get().application).denyPermissions(Manifest.permission.CAMERA)
            val harness = Harness().connect()
            val radio = checkNotNull(harness.session.radioId)
            val existing = Fixtures.contact(radioId = radio, name = "Alex")
            val added = when (identity) {
                "same-id" -> existing.copy(name = "Sam")
                "same-key" -> Fixtures.contact(radioId = radio, publicKey = existing.publicKey, name = "Sam")
                else -> Fixtures.contact(radioId = radio, name = "Sam")
            }
            harness.store.contacts += existing
            var fetches = 0
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            harness.store.fetchContactsGate = {
                fetches++
                if (fetches == 2) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            var imported = false
            harness.contactService.addBehavior = { _, frame ->
                assertEquals(added.publicKey, frame.publicKey)
                assertEquals(added.name, frame.name)
                imported = true
            }
            harness.contactService.getContactBehavior = { requestedRadio, key ->
                assertEquals(radio, requestedRadio)
                assertEquals(added.publicKey, key)
                added
            }
            harness.store.fetchContactBehavior = { requestedRadio, key ->
                assertEquals(radio, requestedRadio)
                assertEquals(added.publicKey, key)
                if (imported) added else null
            }
            val opened = mutableListOf<ContactDTO>()
            val navigation = object : NodesNavigation by NodesNavigation.NONE {
                override fun openContact(contact: ContactDTO) { opened += contact }
            }
            val inset = mutableStateOf(0)
            mount { NodesListScreen(harness.dependencies, navigation, Modifier.padding(inset.value.dp)) }
            awaitText("Alex")
            val context = controller.get()
            click(awaitAction(context.getString(C.contactsListSyncNodes)))
            awaitCondition { entered.isCompleted }
            click(awaitAction(context.getString(C.contactsListAddContact)))
            if (scan) {
                click(awaitAction(context.getString(C.contactsAddScanQR)))
                val field = allSemanticsNodes().single { it.config.contains(SemanticsActions.SetText) }
                setText(field, harness.codec.exportContactUri(added.name, added.publicKey, added.type))
                click(awaitAction(context.getString(C.contactsAddView)))
                awaitAction(context.getString(C.contactsAddAdd))
            } else {
                val fields = allSemanticsNodes().filter { it.config.contains(SemanticsActions.SetText) }
                assertEquals(2, fields.size)
                setText(fields[0], added.name)
                setText(fields[1], added.publicKey.hexString)
            }
            click(awaitAction(context.getString(C.contactsAddAdd)))
            awaitText("Sam")
            assertFalse(release.isCompleted, "Admission must render while the older list fetch is held")
            release.complete(Unit)
            awaitCondition { harness.adverts.syncingFlags == listOf(true, false) }
            advanceFrames()
            val rows = allSemanticsNodes().filter { node ->
                isDisplayed(node) && node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Sam" }
            }
            assertEquals(1, rows.size, "The completed add must be presented exactly once")
            if (identity != "append") {
                assertFalse(allSemanticsNodes().any { node ->
                    isDisplayed(node) && node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Alex" }
                })
            }
            click(awaitAction("Sam"))
            assertEquals(List(if (scan) 2 else 1) { added }, opened)
            assertEquals(2, fetches, "Add completion must not start another list fetch")
            assertEquals(listOf(existing), harness.store.contacts, "The fake list deliberately has not caught up")
            assertEquals(if (scan) listOf("sync", "add:Sam") else listOf("sync", "add:Sam", "getContact"), harness.contactService.calls)

            val otherRadio = Fixtures.radio()
            val other = Fixtures.contact(radioId = otherRadio, publicKey = added.publicKey, name = "Other radio")
            harness.store.contacts += other
            harness.connect(otherRadio)
            controller.get().runOnUiThread { inset.value = 1 }
            awaitText(other.name)
            assertFalse(allSemanticsNodes().any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Sam" }
            }, "A new radio must not inherit the previous list's pending admissions")
            click(awaitAction(other.name))
            assertEquals(other, opened.last())

            harness.store.contacts.clear()
            harness.store.contacts += added
            harness.connect(radio)
            controller.get().runOnUiThread { inset.value = 2 }
            awaitText("Sam")
            click(awaitAction(context.getString(C.contactsListSyncNodes)))
            awaitCondition { harness.adverts.syncingFlags == listOf(true, false, true, false) }
            assertEquals(1, allSemanticsNodes().count { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == "Sam" }
            })
        }

        private fun setText(node: SemanticsNode, text: String) {
            controller.get().runOnUiThread {
                assertTrue(checkNotNull(node.config[SemanticsActions.SetText].action).invoke(AnnotatedString(text)))
            }
            advanceFrames()
        }

    }

    abstract class ContactShareComposeTestBase : NodesScreensComposeTestBase() {
        override fun rootView(): View = checkNotNull(ShadowDialog.getLatestDialog().window).decorView

        @Test
        fun qrCopyKeepsFullTextTargetFeedbackClipboardChooserAndDismissal() {
            val context = controller.get()
            val configuration = Configuration(context.resources.configuration).apply {
                fontScale = 2f
                setLayoutDirection(java.util.Locale.forLanguageTag("ar"))
            }
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
            val harness = Harness()
            val key = Fixtures.repeated(0xAB)
            val showing = mutableStateOf(true)
            lateinit var holder: ContactQrShareStateHolder
            mount {
                val scope = rememberCoroutineScope()
                if (showing.value) {
                    holder = remember {
                        ContactQrShareStateHolder("Offline relay", key, ContactType.REPEATER, harness.dependencies, scope)
                    }
                    ContactShareDialog(holder) { showing.value = false }
                }
            }
            val copy = awaitAction(context.getString(C.contactsQrCopy))
            assertEquals(2f, context.resources.configuration.fontScale)
            assertEquals(View.LAYOUT_DIRECTION_RTL, context.resources.configuration.layoutDirection)
            assertTrue(
                allSemanticsNodes().any { it.config.contains(SemanticsActions.ScrollBy) },
                "QR content must expose non-gesture scroll recovery when the window is constrained",
            )
            assertTrue(
                generateSequence(copy.parent) { it.parent }.none { it.config.contains(SemanticsActions.ScrollBy) },
                "Copy must not be clipped by the QR content scroll viewport",
            )
            val publicKey = awaitText(holder.publicKeyText)
            assertEquals(publicKey.size.height.toFloat(), publicKey.boundsInRoot.height, "Public key must be fully visible")
            assertEquals(publicKey.size.width.toFloat(), publicKey.boundsInRoot.width, "Public key must not clip horizontally")
            assertTrue(copy.boundsInRoot.height / currentDensity() >= 48f, "Copy must retain a 48dp height: ${copy.boundsInRoot}")
            assertTrue(copy.boundsInRoot.width / currentDensity() >= 48f, "Copy must retain a 48dp width: ${copy.boundsInRoot}")
            assertTrue(copy.boundsInRoot.top >= 0f && copy.boundsInRoot.bottom <= rootView().height)
            assertTrue(copy.boundsInRoot.left >= 0f && copy.boundsInRoot.right <= rootView().width)
            val layouts = mutableListOf<TextLayoutResult>()
            val textLayout = copy.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }?.action
            assertNotNull(textLayout, "Copy must retain measurable text")
            context.runOnUiThread { textLayout.invoke(layouts) }
            assertTrue(layouts.isNotEmpty())
            layouts.forEach {
                repeat(it.lineCount) { line ->
                    assertTrue(it.getLineTop(line) >= 0f)
                    assertTrue(it.getLineBottom(line) <= it.size.height, "Copy line $line must be fully allocated")
                    assertTrue(
                        it.getLineRight(line) - it.getLineLeft(line) <= copy.boundsInRoot.width,
                        "Copy line $line must fit within the action",
                    )
                }
            }
            click(copy)
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(key.hexString.uppercase(), clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            val copied = awaitAction(context.getString(C.contactsQrCopied))
            assertTrue(copied.config.contains(SemanticsProperties.Disabled))
            runBlocking { harness.clock.advanceBy(1.seconds) }
            advanceFrames()
            assertNotNull(actionNode(context.getString(C.contactsQrCopied)))
            runBlocking { harness.clock.advanceBy(1.seconds) }
            val reset = awaitAction(context.getString(C.contactsQrCopy))
            assertFalse(reset.config.contains(SemanticsProperties.Disabled))
            click(scrollToAction(context.getString(C.contactsDetailShareContact)))
            val chooser = Shadows.shadowOf(context).nextStartedActivity
            assertEquals(Intent.ACTION_CHOOSER, chooser.action)
            assertEquals(holder.contactName, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE)?.toString())
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            assertNotNull(send)
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals(holder.contactUri, send.getStringExtra(Intent.EXTRA_TEXT))
            val shareSubject = context.getString(C.contactsQrShareSubject)
            assertFalse(shareSubject == holder.contactName, "Fixture must distinguish the localized subject from the contact name")
            assertEquals(shareSubject, send.getStringExtra(Intent.EXTRA_SUBJECT))
            click(scrollToAction(context.getString(C.contactsCommonDone)))
            assertFalse(showing.value)
        }

    }
}
