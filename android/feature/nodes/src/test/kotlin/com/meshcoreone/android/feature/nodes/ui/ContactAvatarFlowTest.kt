// AndroidOnly: WP-311 Mounted production contact-detail picker/crop/store flow, using local granted media.
package com.meshcoreone.android.feature.nodes.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.Display
import android.view.View
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.Density
import androidx.core.app.ActivityOptionsCompat
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.feature.nodes.NodesNavigation
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Harness
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
    private val uri = Uri.parse("content://avatar-fixture/selected.png")
    private var requestCode: Int? = null
    private var launched: Intent? = null
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            requestCode = code
            launched = contract.createIntent(controller.get(), input)
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry = registry
    }

    override fun rootView(): View =
        ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: super.rootView()

    private fun start(initial: ContactDTO = contact) {
        selected.value = initial
        harness.store.contacts += initial
        mount {
            CompositionLocalProvider(
                LocalActivityResultRegistryOwner provides registryOwner,
                LocalDensity provides Density(1f, 1f),
            ) {
                ContactDetailScreen(selected.value, harness.dependencies, NodesNavigation.NONE)
            }
        }
    }

    private fun action(resource: Int) = click(awaitAction(controller.get().getString(resource)))

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
    fun savingAnnouncesProgressAndPreventsASecondSelectionUntilTheStoreCompletes() {
        start()
        val gate = CompletableDeferred<Unit>()
        harness.contactService.avatarBehavior = { _, _ -> gate.await(); throw java.io.IOException("write rejected") }
        pick(file = true)
        result()
        action(C.contactsDetailAvatarCropChoose)
        awaitText(controller.get().getString(C.contactsDetailAvatarSavingAnnouncement))
        val chooser = assertNotNull(actionNode(controller.get().getString(C.contactsDetailAvatarChooseSource)))
        assertTrue(chooser.config.contains(SemanticsProperties.Disabled))
        gate.complete(Unit)
        awaitText("failed: write rejected")
        action(C.contactsCommonOk)
        val recovered = assertNotNull(actionNode(controller.get().getString(C.contactsDetailAvatarChooseSource)))
        kotlin.test.assertFalse(recovered.config.contains(SemanticsProperties.Disabled))
        assertNull(harness.store.contacts.single().avatarImageData)
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
        harness.contactService.avatarBehavior = { _, _ -> gate.await(); throw java.io.IOException("retained operation") }
        pick(file = true)
        result()
        action(C.contactsDetailAvatarCropChoose)
        awaitText(controller.get().getString(C.contactsDetailAvatarSavingAnnouncement))
        scrollToAction(controller.get().getString(C.contactsDetailEditPath))
        gate.complete(Unit)
        awaitText("failed: retained operation")
        assertNull(harness.store.contacts.single().avatarImageData)
    }

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
