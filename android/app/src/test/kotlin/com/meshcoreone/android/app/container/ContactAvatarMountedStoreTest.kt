// AndroidOnly: WP-311 Production nodes entry, media/crop and App contact adapter over real Room persistence.
package com.meshcoreone.android.app.container

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.nodes.NodesDestination
import com.meshcoreone.android.feature.nodes.NodesEntry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactAvatarMountedStoreTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun productionPickerCropAndRemovePersistOnlyTheSelectedRadioContact() = runTest {
        val harness = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val radio = assertNotNull(harness.appState.currentRadioId)
            val target = ContactDTO(radioId = radio, publicKey = key(42), name = "Avatar Room target", lastHeardTimestamp = null)
            val foreign = target.copy(radioId = RadioId(UUID(0, 991)), publicKey = key(43), name = "Foreign radio")
            store.saveContact(target)
            store.saveContact(foreign)
            val targetKey = EntityKey(radio, target.id)
            val foreignKey = EntityKey(foreign.radioId, foreign.id)
            val storedForeign = assertNotNull(store.fetchContact(foreignKey))
            assertEquals(foreign.id, storedForeign.id)
            assertEquals(foreign.radioId, storedForeign.radioId)
            assertEquals(foreign.publicKey, storedForeign.publicKey)
            assertNull(storedForeign.avatarImageData)
            var requestCode: Int? = null
            fun newRegistry() = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    val intent = contract.createIntent(host.get(), input)
                    assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                    assertEquals("*/*", intent.type)
                    assertEquals(listOf("image/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
                    requestCode = code
                }
            }
            var registry = newRegistry()
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry get() = registry
            }
            fun mount() {
                host.get().setContent {
                    CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                        MeshCoreTheme(motionScale = 0f) {
                            NodesEntry(FeatureRoute(FeatureId.NODES), {}, harness.container.nodesFeature, NodesDestination.Detail(target))
                        }
                    }
                }
            }
            fun recreate() {
                val savedRegistry = Bundle()
                registry.onSaveInstanceState(savedRegistry)
                host.windowFocusChanged(false)
                host.recreate().visible()
                host.get().actionBar?.hide()
                host.windowFocusChanged(true)
                registry = newRegistry().apply { onRestoreInstanceState(savedRegistry) }
                mount()
                compose.waitForIdle()
            }
            mount()
            val uri = Uri.parse("content://avatar-room-fixture/photo.png")
            val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val png = ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                bitmap.recycle()
                it.toByteArray()
            }
            fun click(resource: Int) {
                compose.onNodeWithText(host.get().getString(resource)).performScrollTo().performClick()
            }
            fun pick(recreateBeforeReturn: Boolean = false) {
                click(C.contactsDetailAvatarChooseSource)
                // Dialog controls are not in the parent lazy list.
                compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseFile)).performClick()
                if (recreateBeforeReturn) recreate()
                Shadows.shadowOf(host.get().contentResolver).registerInputStream(uri, ByteArrayInputStream(png))
                compose.runOnUiThread {
                    assertTrue(registry.dispatchResult(assertNotNull(requestCode), Activity.RESULT_OK, Intent().setData(uri)))
                }
                compose.waitUntil(5_000) {
                    compose.onAllNodes(androidx.compose.ui.test.hasText(host.get().getString(C.contactsDetailAvatarCropChoose)))
                        .fetchSemanticsNodes().isNotEmpty()
                }
            }
            pick(recreateBeforeReturn = true)
            compose.onNodeWithText(host.get().getString(C.contactsCommonCancel)).performClick()
            compose.waitForIdle()
            assertNull(assertNotNull(store.fetchContact(targetKey)).avatarImageData)
            assertEquals(storedForeign, store.fetchContact(foreignKey))
            pick()
            recreate()
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarCropChoose)).assertExists()
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarCropChoose)).performClick()
            try {
                compose.waitUntil(5_000) {
                    harness.scheduler.runCurrent()
                    runBlocking { store.fetchContact(targetKey)?.avatarImageData != null }
                }
            } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
                System.err.println("Avatar Room save did not complete: " +
                    compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher("all mounted nodes") { true },
                        useUnmergedTree = true).fetchSemanticsNodes().joinToString { it.config.toString() })
                throw timeout
            }
            val saved = assertNotNull(assertNotNull(store.fetchContact(targetKey)).avatarImageData).toByteArray()
            assertEquals(0xFF, saved[0].toInt() and 0xFF)
            assertEquals(0xD8, saved[1].toInt() and 0xFF)
            val decoded = assertNotNull(BitmapFactory.decodeByteArray(saved, 0, saved.size))
            assertEquals(512, decoded.width)
            assertEquals(512, decoded.height)
            assertTrue(android.graphics.Color.blue(decoded.getPixel(256, 256)) > 240)
            assertEquals(storedForeign, store.fetchContact(foreignKey))
            compose.waitUntil(5_000) {
                !compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseSource))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            click(C.contactsDetailAvatarChooseSource)
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarRemovePhoto)).assertExists().performClick()
            compose.waitUntil(5_000) { runBlocking { store.fetchContact(targetKey)?.avatarImageData == null } }
            assertEquals(storedForeign, store.fetchContact(foreignKey))
        } finally {
            host.windowFocusChanged(false)
            host.pause().stop().destroy()
            harness.container.close()
        }
    }
}
