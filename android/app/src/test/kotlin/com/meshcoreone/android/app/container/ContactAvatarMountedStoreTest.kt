// AndroidOnly: WP-311 Production nodes entry, media/crop and App contact adapter over real Room persistence.
package com.meshcoreone.android.app.container

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.BackEventCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.app.ActivityOptionsCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import com.meshcoreone.android.feature.nodes.detail.AvatarSaveObservation
import com.meshcoreone.android.feature.nodes.detail.AvatarSaveObserver
import com.meshcoreone.android.feature.nodes.detail.AvatarSaveOperationContext
import com.meshcoreone.android.feature.nodes.detail.AvatarSaveOutcome
import com.meshcoreone.android.feature.nodes.detail.AvatarSaveStage
import com.meshcoreone.android.feature.nodes.deps.NodesContactService
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import com.meshcoreone.android.feature.nodes.detail.ContactDetailStateHolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
    fun timeoutDiagnosticsPreserveTheStageCauseAndCollectorFailure() {
        val timeout = IllegalStateException("original timeout")
        for (collector in listOf(ReflectiveOperationException("reflection failed"), AssertionError("collector assertion"))) {
            val failure = assertFailsWith<AssertionError> {
                failWithDiagnostics("actual stage timed out", timeout) { throw collector }
            }
            assertEquals("actual stage timed out", failure.message)
            assertSame(timeout, failure.cause)
            assertSame(collector, failure.suppressed.single())
        }
        val cancellation = CancellationException("diagnostic cancelled")
        val propagated = assertFailsWith<CancellationException> {
            failWithDiagnostics("actual stage timed out", timeout) { throw cancellation }
        }
        assertSame(cancellation, propagated)
        assertSame(timeout, propagated.suppressed.single().cause)
    }

    class ThrowingDiagnosticTarget(val failure: Throwable) {
        fun collect(): String = throw failure
    }

    @Test
    fun reflectedDiagnosticCancellationAndFatalTargetsPropagateByIdentity() {
        val method = ThrowingDiagnosticTarget::class.java.getMethod("collect")
        val timeout = IllegalStateException("original timeout")
        for (target in listOf(CancellationException("reflected cancellation"), StackOverflowError("synthetic fatal"), ThreadDeath())) {
            val propagated = assertFailsWith<Throwable> {
                failWithDiagnostics("actual stage timed out", timeout) {
                    method.invoke(ThrowingDiagnosticTarget(target)) as String
                }
            }
            assertSame(target, propagated)
            assertEquals("actual stage timed out", propagated.suppressed.single().message)
            assertSame(timeout, propagated.suppressed.single().cause)
        }
        val ordinary = IllegalStateException("ordinary reflected target")
        val failure = assertFailsWith<AssertionError> {
            failWithDiagnostics("actual stage timed out", timeout) {
                method.invoke(ThrowingDiagnosticTarget(ordinary)) as String
            }
        }
        assertSame(timeout, failure.cause)
        val wrapper = failure.suppressed.single()
        assertTrue(wrapper is InvocationTargetException)
        assertSame(ordinary, wrapper.targetException)
    }

    @Test
    fun avatarWaitDrainsPostedMainWorkAndTheRealDefaultReturn() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val release = CompletableDeferred<Unit>()
        val background = CompletableDeferred<Unit>()
        val posted = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        try {
            val job = compose.runOnUiThread {
                scope.launch {
                    withContext(Dispatchers.Default) {
                        release.await()
                        assertTrue(Handler(Looper.getMainLooper()).post { posted.complete(Unit) })
                        background.complete(Unit)
                    }
                    resumed.complete(Unit)
                }
            }
            release.complete(Unit)
            compose.waitUntil(5_000) { background.isCompleted }
            assertFalse(posted.isCompleted, "Polling alone must not execute the paused Main queue")
            assertFalse(resumed.isCompleted, "The Default return still requires its Main continuation")
            awaitAvatarWrite("Default return and posted Main work", System.nanoTime() + 5_000_000_000L, { "host control" }) {
                posted.isCompleted && resumed.isCompleted && job.isCompleted
            }
            assertFalse(job.isCancelled)
        } finally {
            scope.cancel()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }

    @Test
    fun avatarWaitRejectsExpiredAndLateCompletionWithoutRenewingTheBudget() {
        var polled = false
        val expired = assertFailsWith<AssertionError> {
            awaitAvatarWrite("expired save", 1_000_000L, { "expired control" }, nanoTime = { 1_000_000L }) {
                polled = true
                true
            }
        }
        assertEquals("expired save: shared deadline expired", expired.message)
        assertFalse(polled)
        var samples = 0
        val late = assertFailsWith<AssertionError> {
            awaitAvatarWrite(
                "late holder completion", 2_000_000_000L, { "late control" },
                nanoTime = { if (samples++ == 0) 1_000_000_000L else 2_000_000_000L },
            ) { true }
        }
        assertEquals("late holder completion: condition completed after shared deadline", late.message)
    }

    @Test
    fun avatarWaitDoesNotStartAnotherMainTaskAfterTheSharedDeadline() {
        var now = 1_000_000_000L
        var firstRan = false
        var secondRan = false
        val handler = Handler(Looper.getMainLooper())
        val first = Runnable {
            firstRan = true
            now = 2_000_000_000L
        }
        val second = Runnable { secondRan = true }
        try {
            compose.runOnUiThread {
                handler.post(first)
                handler.post(second)
            }
            val failure = assertFailsWith<AssertionError> {
                pumpAvatarMainTasks(
                    "bounded task pump", 2_000_000_000L, { "bounded pump control" }, { now },
                )
            }
            assertEquals("bounded task pump: shared deadline expired during Main task pumping", failure.message)
            assertTrue(firstRan)
            assertFalse(secondRan, "A later ready Main task must remain unstarted after expiry")
        } finally {
            compose.runOnUiThread { handler.removeCallbacks(first); handler.removeCallbacks(second) }
        }
    }

    @Test
    @Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
    fun realNavigationPredictiveBackCancellationPreservesAnInFlightAvatarWrite() = runTest {
        val harness = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        val observations = java.util.concurrent.CopyOnWriteArrayList<AvatarSaveObservation>()
        installAvatarSaveObserver(host.get(), AvatarSaveObserver { observations.add(it) })
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
            val preview = ContactDTO(radioId = radio, publicKey = key(44), name = "Back preview contact", lastHeardTimestamp = null)
            val target = ContactDTO(radioId = radio, publicKey = key(45), name = "Held avatar target", lastHeardTimestamp = null)
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
                    val operation = currentCoroutineContext()[AvatarSaveOperationContext]
                    operation?.observe(AvatarSaveStage.SERVICE_ENTERED)
                    entered.complete(Unit)
                    try {
                        release.await()
                        forwarded.incrementAndGet()
                        service.updateContactAvatar(contact, imageData)
                        operation?.observe(AvatarSaveStage.SERVICE_RETURNED)
                        completed.complete(Unit)
                    } catch (error: CancellationException) {
                        operation?.observe(AvatarSaveStage.SERVICE_FAILED, AvatarSaveOutcome.CANCELLED, error)
                        cancelled.complete(Unit)
                        throw error
                    }
                }
            }
            val dependencies = production.withContactService(heldService)
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
            val previewEntry = navigation.state.value.activeStack.last()
            compose.onNode(
                hasText(preview.displayName) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and
                    hasAnyAncestor(hasTestTag("entry:${previewEntry.id}")),
            ).assertExists()
            compose.runOnIdle { navigation.navigateToContactDetail(target) }
            val targetEntry = navigation.state.value.activeStack.last()
            compose.onNode(
                hasText(target.displayName) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and
                    hasAnyAncestor(hasTestTag("entry:${targetEntry.id}")),
            ).assertExists()
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
            val beforeChoose = compose.runOnUiThread { avatarLifetimeDiagnostic(host.get()) }
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarCropChoose)).performClick()
            fun diagnostic() =
                "observations=$observations; before=$beforeChoose; " +
                    compose.runOnUiThread { avatarLifetimeDiagnostic(host.get()) }
            awaitAvatarWrite(
                "Held service not entered after Choose", System.nanoTime() + 5_000_000_000L,
                ::diagnostic, { harness.scheduler.runCurrent() },
            ) {
                entered.isCompleted
            }
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
            val completionDeadline = System.nanoTime() + 5_000_000_000L
            awaitAvatarWrite(
                "Held write persistence and job completion", completionDeadline,
                ::diagnostic, { harness.scheduler.runCurrent() },
            ) {
                completed.isCompleted &&
                    runBlocking { store.fetchContact(targetKey)?.avatarImageData != null } &&
                    observations.any { it.stage == AvatarSaveStage.JOB_COMPLETED }
            }
            awaitAvatarWrite(
                "Held write chooser recovery", completionDeadline,
                ::diagnostic, { harness.scheduler.runCurrent() },
            ) {
                !compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseSource))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) &&
                    observations.any { it.stage == AvatarSaveStage.JOB_COMPLETED }
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
            assertCompletedAvatarSave(observations)
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
        val observations = java.util.concurrent.CopyOnWriteArrayList<AvatarSaveObservation>()
        installAvatarSaveObserver(host.get(), AvatarSaveObserver { observations.add(it) })
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
            val production = harness.container.nodesFeature
            val service = assertNotNull(production.session.contactService())
            val writes = java.util.concurrent.CopyOnWriteArrayList<Pair<EntityKey, Bytes?>>()
            val returned = java.util.concurrent.atomic.AtomicInteger()
            val serviceFailure = java.util.concurrent.atomic.AtomicReference<Throwable?>()
            val dependencies = production.withContactService(object : NodesContactService by service {
                override suspend fun updateContactAvatar(contact: EntityKey, imageData: Bytes?) {
                    writes += contact to imageData
                    val operation = currentCoroutineContext()[AvatarSaveOperationContext]
                    operation?.observe(AvatarSaveStage.SERVICE_ENTERED)
                    try {
                        service.updateContactAvatar(contact, imageData)
                        operation?.observe(AvatarSaveStage.SERVICE_RETURNED)
                        returned.incrementAndGet()
                    } catch (error: Exception) {
                        operation?.observe(
                            AvatarSaveStage.SERVICE_FAILED,
                            if (error is CancellationException) AvatarSaveOutcome.CANCELLED else AvatarSaveOutcome.FAILED,
                            error,
                        )
                        serviceFailure.set(error)
                        throw error
                    }
                }
            })
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
                            NodesEntry(FeatureRoute(FeatureId.NODES), {}, dependencies, NodesDestination.Detail(target))
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
            val beforeChoose = compose.runOnUiThread { avatarLifetimeDiagnostic(host.get()) }
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarCropChoose)).performClick()
            val saveDeadline = System.nanoTime() + 5_000_000_000L
            fun awaitWrite(stage: String, deadline: Long, condition: () -> Boolean) {
                fun diagnostic() =
                    "$stage; serviceCalls=${writes.size}, returned=${returned.get()}, " +
                        "serviceFailure=${serviceFailure.get()?.javaClass?.simpleName}, lifecycle=${host.get().lifecycle.currentState}; " +
                        "observations=$observations; before=$beforeChoose; " +
                        compose.runOnUiThread { avatarLifetimeDiagnostic(host.get()) }
                awaitAvatarWrite(stage, deadline, ::diagnostic, { harness.scheduler.runCurrent() }, condition = condition)
            }
            awaitWrite("Production encoder must reach the actual avatar service", saveDeadline) { writes.isNotEmpty() }
            assertEquals(targetKey, writes.single().first)
            awaitWrite("Actual Room avatar persistence", saveDeadline) {
                runBlocking { store.fetchContact(targetKey)?.avatarImageData != null }
            }
            awaitWrite("Actual avatar service completion", saveDeadline) { returned.get() == 1 }
            assertNull(serviceFailure.get())
            awaitWrite("Actual holder completion and chooser recovery", saveDeadline) {
                !compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarChooseSource))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled) &&
                    observations.any { it.stage == AvatarSaveStage.JOB_COMPLETED }
            }
            val saved = assertNotNull(assertNotNull(store.fetchContact(targetKey)).avatarImageData).toByteArray()
            assertEquals(saved.toList(), assertNotNull(writes.single().second).toByteArray().toList())
            assertEquals(0xFF, saved[0].toInt() and 0xFF)
            assertEquals(0xD8, saved[1].toInt() and 0xFF)
            val decoded = assertNotNull(BitmapFactory.decodeByteArray(saved, 0, saved.size))
            assertEquals(512, decoded.width)
            assertEquals(512, decoded.height)
            assertTrue(android.graphics.Color.blue(decoded.getPixel(256, 256)) > 240)
            assertEquals(storedForeign, store.fetchContact(foreignKey))
            assertCompletedAvatarSave(observations)
            click(C.contactsDetailAvatarChooseSource)
            compose.onNodeWithText(host.get().getString(C.contactsDetailAvatarRemovePhoto)).assertExists().performClick()
            val removeDeadline = System.nanoTime() + 5_000_000_000L
            awaitWrite("Actual Room avatar removal", removeDeadline) {
                runBlocking { store.fetchContact(targetKey)?.avatarImageData == null }
            }
            awaitWrite("Actual avatar removal service completion", removeDeadline) { returned.get() == 2 }
            assertEquals(listOf(targetKey, targetKey), writes.map { it.first })
            assertNull(writes.last().second)
            assertEquals(storedForeign, store.fetchContact(foreignKey))
        } finally {
            host.windowFocusChanged(false)
            host.pause().stop().destroy()
            harness.container.close()
        }
    }

    private fun awaitAvatarWrite(
        stage: String,
        deadline: Long,
        diagnostic: () -> String,
        settle: () -> Unit = {},
        nanoTime: () -> Long = System::nanoTime,
        condition: () -> Boolean,
    ) {
        val remainingMillis = (deadline - nanoTime()) / 1_000_000L
        if (remainingMillis <= 0) failWithDiagnostics("$stage: shared deadline expired", null, diagnostic)
        try {
            compose.waitUntil(remainingMillis) {
                pumpAvatarMainTasks(stage, deadline, diagnostic, nanoTime)
                ensureAvatarBeforeDeadline(stage, "settling test scheduler", deadline, diagnostic, nanoTime)
                settle()
                ensureAvatarBeforeDeadline(stage, "evaluating completion", deadline, diagnostic, nanoTime)
                condition()
            }
        } catch (timeout: androidx.compose.ui.test.ComposeTimeoutException) {
            failWithDiagnostics("$stage: shared deadline wait timed out", timeout, diagnostic)
        }
        if (nanoTime() >= deadline) {
            failWithDiagnostics("$stage: condition completed after shared deadline", null, diagnostic)
        }
    }

    private fun pumpAvatarMainTasks(
        stage: String,
        deadline: Long,
        diagnostic: () -> String,
        nanoTime: () -> Long = System::nanoTime,
    ) {
        val scheduler = Shadows.shadowOf(Looper.getMainLooper()).scheduler
        while (scheduler.areAnyRunnable()) {
            if (nanoTime() >= deadline) {
                failWithDiagnostics("$stage: shared deadline expired during Main task pumping", null, diagnostic)
            }
            scheduler.runOneTask()
            if (nanoTime() >= deadline && scheduler.areAnyRunnable()) {
                failWithDiagnostics("$stage: shared deadline expired during Main task pumping", null, diagnostic)
            }
        }
    }

    private fun ensureAvatarBeforeDeadline(
        stage: String,
        checkpoint: String,
        deadline: Long,
        diagnostic: () -> String,
        nanoTime: () -> Long,
    ) {
        if (nanoTime() >= deadline) {
            failWithDiagnostics("$stage: shared deadline expired before $checkpoint", null, diagnostic)
        }
    }

    private fun failWithDiagnostics(stage: String, timeout: Throwable?, diagnostic: () -> String): Nothing {
        val failure = AssertionError(stage, timeout)
        try {
            failure.addSuppressed(AssertionError(diagnostic()))
        } catch (cancelled: CancellationException) {
            cancelled.addSuppressed(failure)
            throw cancelled
        } catch (diagnosticFailure: Throwable) {
            val target = if (diagnosticFailure is InvocationTargetException) diagnosticFailure.targetException else diagnosticFailure
            if (target is CancellationException || target is VirtualMachineError || target is ThreadDeath) {
                target.addSuppressed(failure)
                throw target
            }
            failure.addSuppressed(diagnosticFailure)
        }
        throw failure
    }

    private fun installAvatarSaveObserver(activity: ComponentActivity, observer: AvatarSaveObserver) {
        val type = Class.forName("com.meshcoreone.android.feature.nodes.ui.ContactDetailRetainedState")
            .asSubclass(ViewModel::class.java)
        val retained = ViewModelProvider(activity)[type]
        type.getMethod("setAvatarSaveObserver", AvatarSaveObserver::class.java).invoke(retained, observer)
    }

    private fun assertCompletedAvatarSave(observations: List<AvatarSaveObservation>) {
        val ids = observations.map { it.operationId }.distinct()
        assertEquals(1, ids.size)
        val events = observations.filter { it.operationId == ids.single() }
        val stages = events.map { it.stage }
        val expected = listOf(
            AvatarSaveStage.LAUNCHED,
            AvatarSaveStage.STARTED,
            AvatarSaveStage.ENCODE_STARTED,
            AvatarSaveStage.ENCODE_RETURNED,
            AvatarSaveStage.SERVICE_LOOKUP_STARTED,
            AvatarSaveStage.SERVICE_LOOKUP_RETURNED,
            AvatarSaveStage.STORE_LOOKUP_STARTED,
            AvatarSaveStage.STORE_LOOKUP_RETURNED,
            AvatarSaveStage.RADIO_LOOKUP_STARTED,
            AvatarSaveStage.RADIO_LOOKUP_RETURNED,
            AvatarSaveStage.CONTACT_LOOKUP_STARTED,
            AvatarSaveStage.CONTACT_LOOKUP_RETURNED,
            AvatarSaveStage.RADIO_LOOKUP_STARTED,
            AvatarSaveStage.RADIO_LOOKUP_RETURNED,
            AvatarSaveStage.SERVICE_CALL_STARTED,
            AvatarSaveStage.SERVICE_ENTERED,
            AvatarSaveStage.SERVICE_RETURNED,
            AvatarSaveStage.SERVICE_CALL_COMPLETED,
            AvatarSaveStage.CONTACT_LOOKUP_STARTED,
            AvatarSaveStage.CONTACT_LOOKUP_RETURNED,
            AvatarSaveStage.SAVE_SUCCEEDED,
            AvatarSaveStage.JOB_COMPLETED,
        )
        assertEquals(expected, stages, "Avatar save stages must match the complete ordered operation")
        assertEquals(AvatarSaveOutcome.COMPLETED, events.last().outcome)
        assertNull(events.last().failureType)
    }

    private fun avatarLifetimeDiagnostic(activity: ComponentActivity): String {
        val type = Class.forName("com.meshcoreone.android.feature.nodes.ui.ContactDetailRetainedState")
            .asSubclass(ViewModel::class.java)
        val retained = ViewModelProvider(activity)[type]
        val lifetimes = type.getMethod("getLifetimes").invoke(retained) as List<*>
        return lifetimes.joinToString(prefix = "retained=[", postfix = "]") { value ->
            val lifetime = requireNotNull(value)
            val scope = lifetime.javaClass.getMethod("getScope").invoke(lifetime) as CoroutineScope
            val holder = lifetime.javaClass.getMethod("getHolder").invoke(lifetime) as ContactDetailStateHolder
            val state = holder.state.value
            val job = scope.coroutineContext[Job]
            "active=${lifetime.javaClass.getMethod("getActive").invoke(lifetime)}, " +
                "scopeActive=${job?.isActive}, scopeCancelled=${job?.isCancelled}, " +
                "children=${job?.children?.map { "active=${it.isActive}/cancelled=${it.isCancelled}/completed=${it.isCompleted}" }?.toList()}, " +
                "dispatcher=${scope.coroutineContext[kotlin.coroutines.ContinuationInterceptor]}, " +
                "saving=${state.isSavingAvatar}, errorPresent=${state.errorMessage != null}, " +
                "imagePresent=${lifetime.javaClass.getMethod("getImage").invoke(lifetime) != null}"
        }
    }

    private fun NodesFeatureDependencies.withContactService(
        service: NodesContactService,
    ) = NodesFeatureDependencies(
        session = object : NodesSession by session {
            override fun contactService() = service
        },
        uriCodec = uriCodec,
        timeouts = timeouts,
        messages = messages,
        announcer = announcer,
        preferences = preferences,
        clock = clock,
        locale = locale,
    )
}
