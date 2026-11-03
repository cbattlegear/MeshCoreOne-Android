// AndroidOnly: WP-311 Mounted production contact-detail picker/crop/store flow, using local granted media.
package com.meshcoreone.android.feature.nodes.ui

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.Display
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.Lifecycle
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.contacts.key
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Harness
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowDisplayManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactAvatarApi31FlowTest : ContactAvatarFlowTestBase()

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactAvatarApi37FlowTest : ContactAvatarFlowTestBase()

abstract class ContactAvatarFlowTestBase : NodesScreensComposeTestBase() {
    private val harness = Harness().connect()
    private val contact = Fixtures.contact(radioId = checkNotNull(harness.session.radioId), name = "Avatar target")
    private val selected = mutableStateOf(contact)
    private val showSelected = mutableStateOf(true)
    private val incoming = mutableStateOf<ContactDTO?>(null)
    private val uri = Uri.parse("content://avatar-fixture/selected.png")
    private var requestCode: Int? = null
    private var launched: Intent? = null
    private fun newRegistry() = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            requestCode = code
            launched = contract.createIntent(controller.get(), input)
        }
    }
    private var registry = newRegistry()
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry get() = registry
    }

    private fun showingDialog() = ShadowDialog.getShownDialogs().lastOrNull { it.isShowing }
    private var failureDialog: Dialog? = null
    private var checkingFailure = false

    override fun rootView(): View = when {
        checkingFailure -> failureDialog?.window?.decorView ?: super.rootView()
        else -> showingDialog()?.window?.decorView ?: super.rootView()
    }

    private fun start(initial: ContactDTO = contact) {
        selected.value = initial
        harness.store.contacts += initial
        mountContact()
    }

    private fun mountContact() {
        mount {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalDensity provides Density(1f, 1f),
            ) {
                if (showSelected.value) {
                    key("outgoing-entry") { ContactDetailScreen(selected.value, harness.dependencies, NodesNavigation.NONE) }
                }
                incoming.value?.let { target ->
                    key("incoming-entry") { ContactDetailScreen(target, harness.dependencies, NodesNavigation.NONE) }
                }
            }
        }
    }

    private fun recreateHost(beforeMount: () -> Unit = {}) {
        val savedRegistry = Bundle()
        registry.onSaveInstanceState(savedRegistry)
        controller.windowFocusChanged(false)
        controller.recreate().visible()
        controller.get().actionBar?.hide()
        controller.windowFocusChanged(true)
        registry = newRegistry().apply { onRestoreInstanceState(savedRegistry) }
        beforeMount()
        mountContact()
    }

    private fun action(resource: Int) = click(awaitAction(controller.get().getString(resource)))

    private fun awaitAvatarState(
        description: String,
        timeout: (String) -> AssertionError = ::AvatarStateTimeout,
        deadline: Long = System.nanoTime() + 10_000_000_000L,
        condition: () -> Boolean,
    ) {
        while (System.nanoTime() < deadline) {
            advanceFrames()
            if (condition() && System.nanoTime() < deadline) return
            Thread.sleep(10)
        }
        val diagnostic = "Timed out waiting for $description: " + allSemanticsNodes().joinToString { node ->
            "${node.config.getOrElse(SemanticsProperties.Text) { emptyList() }}@${node.boundsInRoot}"
        }
        System.err.println(diagnostic)
        throw timeout(diagnostic)
    }

    private fun awaitAvatarText(
        text: String,
        timeout: (String) -> AssertionError = ::AvatarVisibleTextTimeout,
        deadline: Long = System.nanoTime() + 10_000_000_000L,
    ) {
        awaitAvatarState("visible text '$text'", timeout, deadline) {
            allSemanticsNodes().any { node ->
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == text } &&
                    isDisplayed(node)
            }
        }
    }

    private fun awaitHeldSave(entered: CompletableDeferred<Unit>) {
        awaitAvatarState("held avatar store entry", ::AvatarStoreEntryTimeout) { entered.isCompleted }
        val announcement = controller.get().getString(C.contactsDetailAvatarSavingAnnouncement)
        awaitAvatarText(announcement, ::AvatarProgressVisibilityTimeout)
        val liveRegion = allSemanticsNodes().single { node ->
            node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == announcement }
        }
        assertEquals(LiveRegionMode.Polite, liveRegion.config[SemanticsProperties.LiveRegion])
    }

    private fun Context.activity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.takeIf { it !== this }?.activity()
        else -> null
    }

    private fun currentFailureDialog(previous: Set<Dialog>): Dialog? =
        ShadowDialog.getShownDialogs().lastOrNull { dialog ->
            dialog !in previous && dialog.isShowing && dialog.context.activity() === controller.get()
        }

    private fun awaitAvatarFailure(
        message: String,
        previous: Set<Dialog>,
        deadline: Long,
        assertions: () -> Unit = {},
    ) {
        val retained = ViewModelProvider(controller.get())[ContactDetailRetainedState::class.java]
        val detail = retained.lifetimes.single()
        checkingFailure = true
        try {
            awaitAvatarState("the completed save's exact error state", ::AvatarFailureStateTimeout, deadline) {
                detail.active && !detail.holder.state.value.isSavingAvatar &&
                    detail.holder.state.value.errorMessage == NodesMessage.Text(message)
            }
            awaitAvatarState("the Activity lifecycle required by production UI collection", ::AvatarFailureLifecycleTimeout, deadline) {
                controller.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            }
            awaitAvatarState("a new showing error dialog owned by this Activity", ::AvatarFailureDialogTimeout, deadline) {
                currentFailureDialog(previous)?.also { failureDialog = it } != null
            }
            val dialog = assertNotNull(failureDialog)
            awaitAvatarState("the pinned error dialog's exact text semantics", ::AvatarFailureTextTimeout, deadline) {
                dialog.isShowing && allSemanticsNodes().any { node ->
                    node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text == message }
                }
            }
            awaitAvatarText(message, ::AvatarFailureVisibilityTimeout, deadline)
            assertSame(dialog.window?.decorView, rootView())
            assertTrue(dialog.isShowing)
            assertNotNull(actionNode(controller.get().getString(C.contactsCommonOk)))
            assertions()
        } finally {
            failureDialog = null
            checkingFailure = false
        }
    }

    private fun pick(file: Boolean = false) {
        action(C.contactsDetailAvatarChooseSource)
        action(if (file) C.contactsDetailAvatarChooseFile else C.contactsDetailAvatarChoosePhoto)
        assertNotNull(launched)
        if (file) {
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, launched?.action)
            assertEquals("*/*", launched?.type)
            assertEquals(listOf("image/*"), launched?.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
        } else {
            assertEquals("image/*", launched?.type)
        }
    }

    private fun result(data: ByteArray? = png(), cancelled: Boolean = false) {
        data?.let { Shadows.shadowOf(controller.get().contentResolver).registerInputStream(uri, ByteArrayInputStream(it)) }
        controller.get().runOnUiThread {
            assertTrue(registry.dispatchResult(
                assertNotNull(requestCode),
                if (cancelled) Activity.RESULT_CANCELED else Activity.RESULT_OK,
                if (cancelled) null else Intent().setData(uri),
            ))
        }
        advanceFrames()
    }

    private fun png(): ByteArray {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)
        return ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            bitmap.recycle()
            it.toByteArray()
        }
    }

    @Test
    fun fileSelectionActuallyDecodesCropsEncodesAndSavesTheSelectedContactThenRemoves() {
        start()
        pick(file = true)
        result()
        action(C.contactsDetailAvatarCropChoose)
        awaitAction(controller.get().getString(C.contactsDetailAvatarChooseSource))
        repeat(30) {
            if (harness.contactService.avatars.isEmpty()) { Thread.sleep(10); advanceFrames() }
        }
        val (key, bytes) = harness.contactService.avatars.single()
        assertEquals(contact.id, key.id)
        assertEquals(contact.radioId, key.radioId)
        val encoded = assertNotNull(bytes).toByteArray()
        assertEquals(0xFF, encoded[0].toInt() and 0xFF)
        assertEquals(0xD8, encoded[1].toInt() and 0xFF)
        val bitmap = assertNotNull(BitmapFactory.decodeByteArray(encoded, 0, encoded.size))
        assertEquals(512, bitmap.width)
        assertEquals(512, bitmap.height)
        assertTrue(android.graphics.Color.red(bitmap.getPixel(256, 256)) > 240)
        assertEquals(bytes, harness.store.contacts.single().avatarImageData)
        action(C.contactsDetailAvatarChooseSource)
        action(C.contactsDetailAvatarRemovePhoto)
        repeat(30) { Thread.sleep(10); advanceFrames() }
        assertEquals(listOf(bytes, null), harness.contactService.avatars.map { it.second })
        assertNull(harness.store.contacts.single().avatarImageData)
    }

    @Test
    fun photoPickerCancellationAndCropCancellationNeverMutate() {
        start()
        pick()
        result(cancelled = true)
        action(C.contactsDetailAvatarChooseSource)
        action(C.contactsCommonCancel)
        assertTrue(harness.contactService.avatars.isEmpty())
        pick()
        result()
        action(C.contactsCommonCancel)
        assertTrue(harness.contactService.avatars.isEmpty())
        assertNull(harness.store.contacts.single().avatarImageData)
    }

    @Test
    fun invalidMediaAndSaveFailureAreVisibleWithoutChangingTheContact() {
        start()
        pick(file = true)
        result(byteArrayOf(1, 2, 3))
        awaitText(controller.get().getString(C.contactsDetailAvatarInvalidImage))
        action(C.contactsCommonOk)
        harness.contactService.avatarBehavior = { _, _ -> throw java.io.IOException("storage unavailable") }
        pick(file = true)
        result()
        action(C.contactsDetailAvatarCropChoose)
        awaitText("failed: storage unavailable")
        assertNull(harness.store.contacts.single().avatarImageData)
    }

    @Test
    fun latePickerResultDoesNotTargetAReplacementContactEvenWithTheSameUuid() {
        start()
        pick(file = true)
        val replacement = contact.copy(radioId = Fixtures.radio(), publicKey = Fixtures.repeated(0xAB))
        harness.store.contacts += replacement
        controller.get().runOnUiThread { selected.value = replacement }
        advanceFrames()
        result()
        repeat(20) { Thread.sleep(10); advanceFrames() }
        assertTrue(harness.contactService.avatars.isEmpty())
        assertNull(actionNode(controller.get().getString(C.contactsDetailAvatarCropChoose)))
        assertTrue(harness.store.contacts.all { it.avatarImageData == null })
    }

    @Test
    fun changingContactWithACropOpenDiscardsItWithoutSaving() {
        start()
        pick(file = true)
        result()
        awaitAction(controller.get().getString(C.contactsDetailAvatarCropChoose))
        val replacement = Fixtures.contact(radioId = contact.radioId, name = "Replacement target")
        harness.store.contacts += replacement
        controller.get().runOnUiThread { selected.value = replacement }
        advanceFrames()
        awaitText(replacement.displayName)
        assertNull(actionNode(controller.get().getString(C.contactsDetailAvatarCropChoose)))
        assertTrue(harness.contactService.avatars.isEmpty())
    }

    @Test
    fun recreationBeforeDocumentPickerReturnKeepsTheOriginalRequestAndContact() {
        pickerReturnAfterRecreation(file = true)
    }

    @Test
    fun recreationBeforePhotoPickerReturnKeepsTheOriginalRequestAndContact() {
        pickerReturnAfterRecreation(file = false)
    }

    private fun pickerReturnAfterRecreation(file: Boolean) {
        start()
        pick(file)
        val originalCode = requestCode
        val originalRegistry = registry
        recreateHost {
            if (file) {
                Shadows.shadowOf(controller.get().contentResolver).registerInputStream(uri, ByteArrayInputStream(png()))
                assertTrue(registry.dispatchResult(assertNotNull(originalCode), Activity.RESULT_OK, Intent().setData(uri)))
            }
        }
        assertTrue(registry !== originalRegistry)
        assertEquals(originalCode, requestCode)
        if (!file) result()
        awaitAction(controller.get().getString(C.contactsDetailAvatarCropChoose))
        action(C.contactsDetailAvatarCropChoose)
        awaitAvatarState("one completed avatar save after recreation") {
            harness.contactService.avatars.size == 1 && harness.store.contacts.single().avatarImageData != null
        }
        assertEquals(contact.key, harness.contactService.avatars.single().first)
    }

    @Test
    fun recreationDuringCropRetainsTheImageZoomAndPanAndStillCancelsWithoutMutation() {
        start()
        pick(file = true)
        result()
        awaitAction(controller.get().getString(C.contactsDetailAvatarCropChoose))
        val zoom = allSemanticsNodes().single { it.config.contains(SemanticsActions.SetProgress) }
        controller.get().runOnUiThread { assertTrue(assertNotNull(zoom.config[SemanticsActions.SetProgress].action).invoke(2f)) }
        advanceFrames()
        action(C.contactsDetailAvatarCropMoveRight)
        val detail = ViewModelProvider(controller.get())[ContactDetailRetainedState::class.java].lifetimes.single()
        val originalImage = assertNotNull(detail.image)
        val originalGeometry = detail.geometry
        assertEquals(2.0, originalGeometry.scale)
        assertTrue(originalGeometry.offset.x > 0)
        recreateHost()
        awaitAction(controller.get().getString(C.contactsDetailAvatarCropChoose))
        val restored = ViewModelProvider(controller.get())[ContactDetailRetainedState::class.java].lifetimes.single()
        assertSame(detail, restored)
        assertSame(originalImage, restored.image)
        assertEquals(originalGeometry, restored.geometry)
        action(C.contactsCommonCancel)
        assertNull(restored.image)
        assertTrue(harness.contactService.avatars.isEmpty())
        assertNull(harness.store.contacts.single().avatarImageData)
    }

    @Test
    fun recreationBeforePickerCancellationStillLeavesTheStoreUnchangedAndChooserUsable() {
        start()
        pick(file = true)
        recreateHost()
        result(cancelled = true)
        val chooser = awaitAction(controller.get().getString(C.contactsDetailAvatarChooseSource))
        kotlin.test.assertFalse(chooser.config.contains(SemanticsProperties.Disabled))
        assertNull(actionNode(controller.get().getString(C.contactsDetailAvatarCropChoose)))
        assertTrue(harness.contactService.avatars.isEmpty())
        assertNull(harness.store.contacts.single().avatarImageData)
    }

    @Test
    fun recreationDoesNotRestoreAPendingPickerForAReplacementIdentity() {
        start()
        pick(file = true)
        val previous = ViewModelProvider(controller.get())[ContactDetailRetainedState::class.java].lifetimes.single()
        val replacement = contact.copy(publicKey = Fixtures.repeated(0xAB))
        harness.store.contacts[0] = replacement
        selected.value = replacement
        recreateHost()
        result()
        advanceFrames()
        assertTrue(!previous.active)
        assertNull(actionNode(controller.get().getString(C.contactsDetailAvatarCropChoose)))
        assertTrue(harness.contactService.avatars.isEmpty())
        assertNull(harness.store.contacts.single().avatarImageData)
    }

    @Test
    fun recreationWhileSavingRetainsProgressAndCompletesExactlyOneStoreOperation() {
        start()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val persist = harness.contactService.avatarBehavior
        harness.contactService.avatarBehavior = { key, bytes ->
            entered.complete(Unit)
            gate.await()
            persist(key, bytes)
        }
        try {
            pick(file = true)
            result()
            action(C.contactsDetailAvatarCropChoose)
            awaitHeldSave(entered)
            recreateHost()
            awaitHeldSave(entered)
            assertTrue(assertNotNull(actionNode(controller.get().getString(C.contactsDetailAvatarChooseSource)))
                .config.contains(SemanticsProperties.Disabled))
            assertNull(harness.store.contacts.single().avatarImageData)
            gate.complete(Unit)
            awaitAvatarState("the original avatar save after Activity recreation") {
                harness.store.contacts.single().avatarImageData != null
            }
            assertEquals(1, harness.contactService.avatars.size)
            assertEquals(contact.key, harness.contactService.avatars.single().first)
        } finally {
            gate.cancel()
        }
    }

    @Test
    fun cancelledOverlappingBackPreviewLeavesTheVisibleContactsPendingPickerUsable() {
        start()
        pick(file = true)
        val retained = ViewModelProvider(controller.get())[ContactDetailRetainedState::class.java]
        val original = retained.lifetimes.single()
        val preview = Fixtures.contact(radioId = contact.radioId, name = "Back preview")
        harness.store.contacts += preview
        controller.get().runOnUiThread { incoming.value = preview }
        advanceFrames()
        assertEquals(2, retained.lifetimes.size)
        assertTrue(original.active)
        assertTrue(original.picking)
        controller.get().runOnUiThread { incoming.value = null }
        advanceFrames()
        assertSame(original, retained.lifetimes.single())
        assertTrue(original.active)
        result()
        action(C.contactsDetailAvatarCropChoose)
        awaitAvatarState("the original visible entry's save after preview cancellation") {
            harness.store.contacts.first { it.id == contact.id }.avatarImageData != null
        }
        assertEquals(listOf(contact.key), harness.contactService.avatars.map { it.first })
        assertNull(harness.store.contacts.first { it.id == preview.id }.avatarImageData)
    }

    @Test
    fun outgoingSameIdentityEntryCannotCancelTheReopenedUpdatedContactsPickerAndSave() {
        start()
        val retained = ViewModelProvider(controller.get())[ContactDetailRetainedState::class.java]
        val outgoing = retained.lifetimes.single()
        val updated = contact.copy(nickname = "Updated contact")
        harness.store.contacts[0] = updated
        controller.get().runOnUiThread { incoming.value = updated }
        advanceFrames()
        assertEquals(2, retained.lifetimes.size)
        val reopened = retained.lifetimes.single { it !== outgoing }
        assertTrue(outgoing.active)
        assertTrue(reopened.active)
        controller.get().runOnUiThread { showSelected.value = false }
        advanceFrames()
        kotlin.test.assertFalse(outgoing.active)
        assertSame(reopened, retained.lifetimes.single())
        assertEquals("Updated contact", reopened.holder.state.value.contact.nickname)
        pick(file = true)
        result()
        action(C.contactsDetailAvatarCropChoose)
        awaitAvatarState("the reopened entry's independent save") {
            harness.store.contacts.single().avatarImageData != null
        }
        assertEquals(listOf(contact.key), harness.contactService.avatars.map { it.first })
        assertEquals("Updated contact", harness.store.contacts.single().nickname)
    }

    @Test
    fun savingAnnouncesProgressAndPreventsASecondSelectionUntilTheStoreCompletes() {
        start()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        harness.contactService.avatarBehavior = { _, _ ->
            entered.complete(Unit)
            gate.await()
            throw java.io.IOException("write rejected")
        }
        try {
            pick(file = true)
            result()
            action(C.contactsDetailAvatarCropChoose)
            awaitHeldSave(entered)
            val chooser = assertNotNull(actionNode(controller.get().getString(C.contactsDetailAvatarChooseSource)))
            assertTrue(isDisplayed(chooser))
            assertTrue(chooser.config.contains(SemanticsProperties.Disabled))
            assertEquals(1, harness.contactService.avatars.size)
            assertNull(harness.store.contacts.single().avatarImageData)
            val previous = ShadowDialog.getShownDialogs().toSet()
            val deadline = System.nanoTime() + 10_000_000_000L
            gate.complete(Unit)
            awaitAvatarFailure("failed: write rejected", previous, deadline) { action(C.contactsCommonOk) }
            val recovered = assertNotNull(actionNode(controller.get().getString(C.contactsDetailAvatarChooseSource)))
            kotlin.test.assertFalse(recovered.config.contains(SemanticsProperties.Disabled))
            assertEquals(1, harness.contactService.avatars.size)
            assertNull(harness.store.contacts.single().avatarImageData)
        } finally {
            gate.cancel()
        }
    }

    @Test
    fun sameMessageStaleAndForeignDialogsCannotSatisfyTheCurrentSaveFailure() {
        start()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val message = "failed: write rejected"
        harness.contactService.avatarBehavior = { _, _ ->
            entered.complete(Unit)
            gate.await()
            throw java.io.IOException("write rejected")
        }
        val foreignHost = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val stale = Dialog(controller.get())
        val foreign = Dialog(foreignHost.get())
        try {
            pick(file = true)
            result()
            action(C.contactsDetailAvatarCropChoose)
            awaitHeldSave(entered)
            val staleText = TextView(controller.get()).apply { text = message }
            stale.setContentView(staleText)
            stale.show()
            val previous = ShadowDialog.getShownDialogs().toSet()
            val foreignText = TextView(foreignHost.get()).apply { text = message }
            foreign.setContentView(foreignText)
            foreign.show()
            assertTrue(stale.isShowing)
            assertTrue(foreign.isShowing)
            assertEquals(message, staleText.text.toString())
            assertEquals(message, foreignText.text.toString())
            assertNull(currentFailureDialog(previous), "Neither same-message decoy belongs to the new current failure")
            val deadline = System.nanoTime() + 10_000_000_000L
            gate.complete(Unit)
            awaitAvatarFailure(message, previous, deadline) {
                val current = assertNotNull(failureDialog)
                assertTrue(current !== stale && current !== foreign)
                // Reordering a foreign same-message window cannot change the pinned semantics root.
                foreign.dismiss()
                foreign.show()
                assertSame(current.window?.decorView, rootView())
                action(C.contactsCommonOk)
                kotlin.test.assertFalse(current.isShowing)
            }
            assertTrue(stale.isShowing)
            assertTrue(foreign.isShowing)
            assertEquals(1, harness.contactService.avatars.size)
            assertNull(harness.store.contacts.single().avatarImageData)
        } finally {
            gate.cancel()
            stale.dismiss()
            foreign.dismiss()
            foreignHost.pause().stop().destroy()
        }
    }

    @Test
    fun disconnectingWhileCroppingReportsUnavailableWithoutSaving() {
        start()
        pick(file = true)
        result()
        awaitAction(controller.get().getString(C.contactsDetailAvatarCropChoose))
        harness.session.contacts = null
        action(C.contactsDetailAvatarCropChoose)
        awaitText(controller.get().getString(C.contactsDetailErrorServicesUnavailable))
        assertTrue(harness.contactService.avatars.isEmpty())
        assertNull(harness.store.contacts.single().avatarImageData)
    }

    @Test
    fun saveContinuesWhenTheProfileRowLeavesTheLazyViewport() {
        start()
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        harness.contactService.avatarBehavior = { _, _ ->
            entered.complete(Unit)
            gate.await()
            throw java.io.IOException("retained operation")
        }
        try {
            pick(file = true)
            result()
            action(C.contactsDetailAvatarCropChoose)
            awaitHeldSave(entered)
            kotlin.test.assertFalse(contact.hasLocation)
            val scroll = allSemanticsNodes().first { it.config.contains(SemanticsActions.ScrollToIndex) }
                .config[SemanticsActions.ScrollToIndex].action
            // This no-location detail has five items; the last is the danger-zone card.
            controller.get().runOnUiThread { assertTrue(assertNotNull(scroll).invoke(4)) }
            awaitAvatarState("the final danger-zone action in the viewport", ::AvatarViewportTimeout) {
                actionNode(controller.get().getString(C.contactsDetailClearMessages))?.let(::isDisplayed) == true
            }
            val chooser = actionNode(controller.get().getString(C.contactsDetailAvatarChooseSource))
            assertTrue(chooser == null || !isDisplayed(chooser), "The profile row must leave the lazy viewport")
            assertEquals(1, harness.contactService.avatars.size)
            val previous = ShadowDialog.getShownDialogs().toSet()
            val deadline = System.nanoTime() + 10_000_000_000L
            gate.complete(Unit)
            awaitAvatarFailure("failed: retained operation", previous, deadline)
            assertEquals(1, harness.contactService.avatars.size)
            assertNull(harness.store.contacts.single().avatarImageData)
        } finally {
            gate.cancel()
        }
    }

    private class AvatarStateTimeout(message: String) : AssertionError(message)
    private class AvatarVisibleTextTimeout(message: String) : AssertionError(message)
    private class AvatarStoreEntryTimeout(message: String) : AssertionError(message)
    private class AvatarProgressVisibilityTimeout(message: String) : AssertionError(message)
    private class AvatarViewportTimeout(message: String) : AssertionError(message)
    private class AvatarFailureStateTimeout(message: String) : AssertionError(message)
    private class AvatarFailureLifecycleTimeout(message: String) : AssertionError(message)
    private class AvatarFailureDialogTimeout(message: String) : AssertionError(message)
    private class AvatarFailureTextTimeout(message: String) : AssertionError(message)
    private class AvatarFailureVisibilityTimeout(message: String) : AssertionError(message)

    @Test
    fun selectedCropSurvivesActualWindowResizingAndStillCancelsWithoutMutation() {
        start()
        pick(file = true)
        result()
        awaitAction(controller.get().getString(C.contactsDetailAvatarCropChoose))
        for ((width, height) in listOf(600 to 900, 631 to 900, 840 to 900, 840 to 280, 360 to 900)) {
            ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, "+w${width}dp-h${height}dp")
            controller.get().runOnUiThread {
                val activity = controller.get()
                val config = Configuration(activity.resources.configuration).apply {
                    screenWidthDp = width
                    screenHeightDp = height
                    orientation = if (width > height) Configuration.ORIENTATION_LANDSCAPE else Configuration.ORIENTATION_PORTRAIT
                }
                activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
                activity.onConfigurationChanged(config)
                activity.window.decorView.dispatchConfigurationChanged(config)
                listOf(activity.window, ShadowDialog.getLatestDialog()?.window).forEach { window ->
                    window?.setLayout(width, height)
                    window?.decorView?.let { view ->
                        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                        view.layout(0, 0, width, height)
                    }
                }
            }
            advanceFrames()
            val canvas = allSemanticsNodes().single {
                it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } == "avatar-crop-canvas"
            }
            assertTrue(canvas.boundsInRoot.width > 0 && canvas.boundsInRoot.height > 0)
            assertTrue(isDisplayed(awaitAction(controller.get().getString(C.contactsDetailAvatarCropChoose))))
            assertTrue(isDisplayed(awaitAction(controller.get().getString(C.contactsCommonCancel))))
        }
        action(C.contactsCommonCancel)
        assertTrue(harness.contactService.avatars.isEmpty())
    }

    @Test
    fun boundedReaderRejectsOversizeAndDecodeRejectsNonGrantedUris() = runBlocking {
        val oversized = object : java.io.InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                buffer.fill(0, offset, offset + length)
                return length
            }
        }
        kotlin.test.assertFailsWith<java.io.IOException> { ContactAvatarMedia.readBounded(oversized) }
        kotlin.test.assertFailsWith<java.io.IOException> {
            ContactAvatarMedia.decode(controller.get().contentResolver, Uri.parse("https://invalid/image.png"))
        }
        assertTrue(harness.contactService.avatars.isEmpty())
    }
}
