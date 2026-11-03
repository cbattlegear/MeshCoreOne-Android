// AndroidOnly: WP-311 Production nodes entry, media/crop and App contact adapter over real Room persistence.
package com.meshcoreone.android.app.container

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.nodes.NodesDestination
import com.meshcoreone.android.feature.nodes.NodesEntry
import com.meshcoreone.android.feature.nodes.deps.NodesContactService
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
    @Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
    fun realNavigationPredictiveBackCancellationPreservesAnInFlightAvatarWrite() = runTest {
        val harness = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val release = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val completed = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val attempts = java.util.concurrent.CopyOnWriteArrayList<Pair<EntityKey, Bytes?>>()
        val forwarded = java.util.concurrent.atomic.AtomicInteger()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            harness.manager.connect(harness.target())
            harness.settle()
            harness.assertReady()
            val radio = assertNotNull(harness.appState.currentRadioId)
            val preview = ContactDTO(radioId = radio, publicKey = key(44), name = "Back preview contact")
            val target = ContactDTO(radioId = radio, publicKey = key(45), name = "Held avatar target")
            val foreign = target.copy(radioId = RadioId(UUID(0, 992)), publicKey = key(46), name = "Foreign radio")
            listOf(preview, target, foreign).forEach { store.saveContact(it) }
            val targetKey = EntityKey(radio, target.id)
            val previewKey = EntityKey(radio, preview.id)
            val foreignKey = EntityKey(foreign.radioId, foreign.id)
            val storedTarget = assertNotNull(store.fetchContact(targetKey))
            val storedPreview = assertNotNull(store.fetchContact(previewKey))
            val storedForeign = assertNotNull(store.fetchContact(foreignKey))
            val production = harness.container.nodesFeature
            val service = assertNotNull(production.session.contactService())
            val heldService = object : NodesContactService by service {
                override suspend fun updateContactAvatar(contact: EntityKey, imageData: Bytes?) {
                    attempts += contact to imageData
                    entered.complete(Unit)
                    try {
                        release.await()
                        forwarded.incrementAndGet()
                        service.updateContactAvatar(contact, imageData)
                        completed.complete(Unit)
                    } catch (error: CancellationException) {
                        cancelled.complete(Unit)
                        throw error
                    }
                }
            }
            val dependencies = NodesFeatureDependencies(
                session = object : NodesSession by production.session {
                    override fun contactService() = heldService
                },
                uriCodec = production.uriCodec,
                timeouts = production.timeouts,
                messages = production.messages,
                announcer = production.announcer,
                preferences = production.preferences,
                clock = production.clock,
                locale = production.locale,
            )
            val navigation = NavigationCoordinator()
            var requestCode: Int? = null
            val registry = object : ActivityResultRegistry() {
                override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
                    val intent = contract.createIntent(host.get(), input)
                    assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.action)
                    assertEquals(listOf("image/*"), intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList())
                    requestCode = code
                }
            }
            val owner = object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }
            host.get().setContent {
                CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                    MeshCoreTheme(motionScale = 0f) {
                        NativeNavigationShell(navigation, nodes = dependencies)
                    }
                }
            }
            compose.runOnIdle { navigation.navigateToContactDetail(preview) }
            compose.onNodeWithText(preview.displayName).assertExists()
            val previewEntry = navigation.state.value.activeStack.last()
            compose.runOnIdle { navigation.navigateToContactDetail(target) }
            compose.onNodeWithText(target.displayName).assertExists()
            val targetEntry = navigation.state.value.activeStack.last()
            val uri = Uri.parse("content://avatar-back-fixture/photo.png")
            val bitmap = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val png = ByteArrayOutputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                bitmap.recycle()
                it.toByteArray()
            }
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseSource)).performScrollTo().performClick()
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseFile)).performClick()
            Shadows.shadowOf(host.get().contentResolver).registerInputStream(uri, ByteArrayInputStream(png))
            compose.runOnUiThread {
                assertTrue(registry.dispatchResult(assertNotNull(requestCode), Activity.RESULT_OK, Intent().setData(uri)))
            }
            compose.waitUntil(5_000) {
                compose.onAllNodes(androidx.compose.ui.test.hasText(host.get().getString(C.contactsDetailAvatarCropChoose)))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarCropChoose)).performClick()
            compose.waitUntil(5_000) { entered.isCompleted }
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarSavingAnnouncement)).assertExists()
            assertEquals(storedTarget, store.fetchContact(targetKey))
            val before = navigation.state.value
            compose.runOnUiThread {
                host.get().onBackPressedDispatcher.dispatchOnBackStarted(BackEventCompat(0f, 100f, 0f, BackEventCompat.EDGE_LEFT))
                host.get().onBackPressedDispatcher.dispatchOnBackProgressed(BackEventCompat(80f, 100f, 0.5f, BackEventCompat.EDGE_LEFT))
            }
            compose.waitForIdle()
            // Both real NavDisplay entries must be mounted, not merely present in the coordinator's stack.
            compose.onNodeWithTag("entry:${previewEntry.id}").assertExists()
            compose.onNodeWithTag("entry:${targetEntry.id}").assertExists()
            assertEquals(before, navigation.state.value)
            assertFalse(cancelled.isCompleted, "Preview composition must not cancel the selected entry's save")
            assertFalse(completed.isCompleted)
            assertEquals(0, forwarded.get())
            compose.runOnUiThread { host.get().onBackPressedDispatcher.dispatchOnBackCancelled() }
            compose.waitForIdle()
            assertEquals(before, navigation.state.value)
            assertEquals(target, navigation.state.value.selectedContact)
            compose.onNodeWithTag("entry:${targetEntry.id}").assertExists()
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarSavingAnnouncement)).assertExists()
            assertTrue(compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseSource))
                .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled))
            assertFalse(cancelled.isCompleted)
            assertEquals(1, attempts.size)
            assertEquals(storedTarget, store.fetchContact(targetKey))
            release.complete(Unit)
            compose.waitUntil(5_000) {
                harness.scheduler.runCurrent()
                completed.isCompleted && runBlocking { store.fetchContact(targetKey)?.avatarImageData != null }
            }
            val saved = assertNotNull(assertNotNull(store.fetchContact(targetKey)).avatarImageData)
            assertEquals(targetKey to saved, attempts.single())
            assertEquals(1, forwarded.get())
            assertFalse(cancelled.isCompleted)
            assertEquals(storedPreview, store.fetchContact(previewKey))
            assertEquals(storedForeign, store.fetchContact(foreignKey))
            val bytes = saved.toByteArray()
            val decoded = assertNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
            assertEquals(512, decoded.width)
            assertEquals(512, decoded.height)
            assertTrue(android.graphics.Color.blue(decoded.getPixel(256, 256)) > 240)
            decoded.recycle()
            compose.waitUntil(5_000) {
                !compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseSource))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseSource)).performClick()
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarRemovePhoto)).assertExists()
            compose.onNodeWithText(host.get().getString(C.contactsCommonCancel)).performClick()
            assertEquals(1, attempts.size)
            assertEquals(1, forwarded.get())
            assertEquals(saved, assertNotNull(store.fetchContact(targetKey)).avatarImageData)
        } finally {
            release.cancel()
            host.windowFocusChanged(false)
            host.pause().stop().destroy()
            harness.container.close()
        }
    }

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
