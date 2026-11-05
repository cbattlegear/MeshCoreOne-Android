// AndroidOnly: WP-303 Mounted foreground warning and held/stale/lifecycle actions, not device consent proof.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertDoesNotExist
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.R
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator as Enrollment
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.model.NotificationPreferences
import com.meshcoreone.android.core.datastore.enrollment.ForegroundAcknowledgmentResult
import com.meshcoreone.android.core.datastore.enrollment.WitnessObservation
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyLimits
import com.meshcoreone.android.platform.notifications.messaging.NotificationReplyStagingStore
import com.meshcoreone.android.feature.settings.device.DiscoveryChoiceSource
import com.meshcoreone.android.feature.settings.device.NotificationAuthorization
import com.meshcoreone.android.feature.settings.device.NotificationPermissionPort
import com.meshcoreone.android.feature.settings.device.NotificationSettingsStateHolder
import com.meshcoreone.android.feature.settings.device.SettingsEnvironment
import com.meshcoreone.android.feature.settings.device.SettingsNavigator
import com.meshcoreone.android.feature.settings.device.StoredDiscoveryChoices
import com.meshcoreone.android.feature.settings.device.ui.NotificationSettingsScreen
import java.util.UUID
import java.io.File
import android.database.sqlite.SQLiteDatabase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.SQLiteMode
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class NotificationEnrollmentEntryTest {
    @get:Rule val compose = createEmptyComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var host: ActivityController<ComponentActivity>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var backend: RecordingBackend
    private lateinit var producer: NotificationEnrollmentProducer
    private var ids = 0
    private var ledger: EnrollmentLedgerFixture? = null
    private var generationFile: File? = null

    @Before fun create() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        backend = RecordingBackend()
        producer = NotificationEnrollmentProducer(scope, backend) { UUID(0, (++ids).toLong()) }
    }

    @After fun destroy() {
        ledger?.close()
        host.pause().stop().destroy()
        scope.cancel()
        generationFile?.let { SQLiteDatabase.deleteDatabase(it) }
    }

    private fun text(id: Int) = host.get().getString(id)
    private fun mount(value: NotificationEnrollmentProducer? = producer) {
        compose.runOnUiThread { host.get().setContent { MeshCoreTheme { NotificationEnrollmentEntry(value) } } }
        compose.waitForIdle()
    }
    private fun start() = compose.onNodeWithText(text(R.string.notification_enrollment_action)).performClick()

    @Test fun productionHoldShowsExplanationAndCannotCreateWarningOrIdentity() {
        mount(null)
        compose.onNodeWithText(text(R.string.notification_enrollment_held)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.notification_enrollment_action)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.notification_enrollment_title)).assertDoesNotExist()
        assertEquals(0, ids)
        assertEquals(0, backend.stages)
    }

    @Test fun actualNotificationsSettingsMountsEntryWithoutRequiringSystemPermission() {
        val preferences = object : NotificationPreferencesPort {
            override val preferences = MutableStateFlow(NotificationPreferences(
                true, true, true, false, false, false, false, true, true, true, true,
            ))
            override suspend fun update(preferences: NotificationPreferences) { this.preferences.value = preferences }
        }
        val holder = NotificationSettingsStateHolder(
            SettingsEnvironment(scope), preferences,
            object : NotificationPermissionPort {
                override suspend fun status() = NotificationAuthorization.DENIED
                override suspend fun request() = false
            },
            DiscoveryChoiceSource { StoredDiscoveryChoices(false, false, false) },
        )
        compose.runOnUiThread {
            host.get().setContent {
                MeshCoreTheme {
                    NotificationSettingsScreen(holder, SettingsNavigator {}, null) {
                        NotificationEnrollmentEntry(producer)
                    }
                }
            }
        }
        compose.waitForIdle()
        start()
        compose.onNodeWithText(text(R.string.notification_enrollment_warning)).assertIsDisplayed()
        assertEquals(4, ids)
        assertEquals(0, backend.stages)
    }

    @Test fun actualClickDisplaysExactlyTheCopyPassedToPresentationWithoutConsent() {
        mount()
        start()
        compose.onNodeWithText(text(R.string.notification_enrollment_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.notification_enrollment_warning)).assertIsDisplayed()
        assertEquals(EnrollmentWarningCopy(
            text(R.string.notification_enrollment_title), text(R.string.notification_enrollment_warning),
            text(R.string.notification_enrollment_confirm), text(R.string.notification_enrollment_cancel),
        ), backend.shown?.copy)
        assertEquals(4, ids)
        assertEquals(0, backend.stages)
        assertEquals(0, backend.enrollments)
    }

    @Test fun actualConfirmStagesBeforeEnrollmentWithoutClaimingDelivery() {
        mount()
        start()
        compose.onNodeWithText(text(R.string.notification_enrollment_confirm)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("stage", "enroll", "retire", "invalidate"), backend.events)
        compose.onNodeWithText(text(R.string.notification_enrollment_bound)).assertDoesNotExist()
        assertEquals(1, backend.enrollments)
    }

    @Test fun realDismissalRetiresTheWarningAndDoesNotStageAuthorization() {
        mount()
        start()
        compose.onNodeWithText(text(R.string.notification_enrollment_cancel)).performClick()
        compose.waitForIdle()
        assertTrue(requireNotNull(backend.shown).retired)
        assertEquals(0, backend.stages)
        assertEquals(0, backend.enrollments)
    }

    @Test fun realPauseWhileStagingFencesLateReturnAndOnlyOffersResume() {
        backend.gate = CompletableDeferred()
        mount()
        start()
        compose.onNodeWithText(text(R.string.notification_enrollment_confirm)).performClick()
        compose.runOnUiThread { host.pause() }
        assertTrue(requireNotNull(backend.shown).retired)
        compose.runOnUiThread {
            backend.gate?.complete(Unit)
            host.resume().visible()
            host.windowFocusChanged(true)
        }
        compose.waitForIdle()
        assertEquals(0, backend.enrollments)
        compose.onNodeWithText(text(R.string.notification_enrollment_action)).assertIsNotEnabled()
        compose.onNodeWithText(text(R.string.notification_enrollment_resume)).performClick()
        compose.waitForIdle()
        assertEquals(1, backend.resumes)
        assertEquals(4, ids)
    }

    @Test fun actualDialogWindowFocusLossRetiresBeforeAnotherAction() {
        mount()
        start()
        compose.runOnUiThread {
            requireNotNull(ShadowDialog.getLatestDialog().window).decorView.dispatchWindowFocusChanged(false)
        }
        compose.waitForIdle()
        assertTrue(requireNotNull(backend.shown).retired)
        assertEquals(0, backend.stages)
        assertEquals(0, backend.enrollments)
    }

    @Test fun unmountedWarningIsNotRestoredAsConsentAndRetryKeepsOperation() {
        mount()
        start()
        val original = requireNotNull(backend.shown)
        compose.runOnUiThread { host.get().setContent {} }
        compose.waitForIdle()
        assertTrue(original.retired)
        mount()
        start()
        assertEquals(original.request, backend.shown?.request)
        assertFalse(requireNotNull(backend.shown) === original)
        assertEquals(4, ids)
        assertEquals(0, backend.stages)
    }

    @Test fun actualActivityRecreationRetiresPresentationButRetainsProcessOperation() {
        mount()
        start()
        val original = requireNotNull(backend.shown)
        compose.runOnUiThread { host.recreate() }
        compose.waitForIdle()
        assertTrue(original.retired)
        mount()
        start()
        assertEquals(original.request, backend.shown?.request)
        assertFalse(requireNotNull(backend.shown) === original)
        assertEquals(4, ids)
        assertEquals(0, backend.stages)
    }

    @Test fun actualMountedActionConsumesRealAcknowledgmentAndBindsRealWitnessAndJournal() {
        val real = mountReal()
        val request = shownRequest()
        compose.onNodeWithText(text(R.string.notification_enrollment_confirm)).performClick()
        compose.waitUntil(30_000) {
            (producer.state.value as? NotificationEnrollmentProducer.State.Outcome)?.result is Enrollment.Result.BoundSnapshot
        }
        assertEquals(listOf("STAGED", "CONSUMED"), real.statuses())
        val reopened = real.backend(realJournal())
        runBlocking {
            assertEquals(ForegroundAcknowledgmentResult.AlreadyConsumed,
                requireNotNull(real.verifier).verify(request.toWitnessRequest()))
            val result = reopened.resume(copy())
            assertEquals(request.operation, assertIs<Enrollment.Result.BoundSnapshot>(result).witness.operation)
        }
        assertTrue(generationFile?.isFile == true)
        assertTrue(real.failures.isEmpty())
    }

    @Test fun actualPauseBeforeRealStageAdmissionLeavesNoAcknowledgmentWitnessOrJournal() {
        assertRetiredBeforeRealStage(dispose = false)
    }

    @Test fun actualDisposalBeforeRealStageAdmissionLeavesNoAcknowledgmentWitnessOrJournal() {
        assertRetiredBeforeRealStage(dispose = true)
    }

    private fun assertRetiredBeforeRealStage(dispose: Boolean) {
        val real = mountReal("EXPLICIT_ACTION")
        shownRequest()
        compose.onNodeWithText(text(R.string.notification_enrollment_confirm)).performClick()
        assertTrue(requireNotNull(real.gate).entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        retireHost(dispose)
        real.gate?.release?.countDown()
        awaitRealOutcome()
        assertEquals(emptyList(), real.statuses())
        assertFalse(real.witnessFile.exists())
        assertFalse(requireNotNull(generationFile).exists())
        assertEquals(4, ids)
    }

    @Test fun actualDisposalBeforeRealConsumptionInvalidatesStagedLedgerWithoutWitnessOrJournal() {
        assertRetiredBeforeRealConsumption(dispose = true)
    }

    @Test fun actualPauseBeforeRealConsumptionInvalidatesStagedLedgerWithoutWitnessOrJournal() {
        assertRetiredBeforeRealConsumption(dispose = false)
    }

    private fun assertRetiredBeforeRealConsumption(dispose: Boolean) {
        val real = mountReal("WITNESS_CONSUMPTION")
        val request = shownRequest()
        compose.onNodeWithText(text(R.string.notification_enrollment_confirm)).performClick()
        assertTrue(requireNotNull(real.gate).entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(listOf("STAGED"), real.statuses())
        retireHost(dispose)
        real.gate?.release?.countDown()
        awaitRealOutcome()
        compose.waitUntil(30_000) { real.statuses().lastOrNull() == "INVALIDATED" }
        assertEquals(listOf("STAGED", "INVALIDATED"), real.statuses())
        assertFalse(real.witnessFile.exists())
        assertFalse(requireNotNull(generationFile).exists())
        runBlocking {
            assertEquals(ForegroundAcknowledgmentResult.Rejected,
                requireNotNull(real.verifier).verify(request.toWitnessRequest()))
            assertEquals(WitnessObservation.UnknownOrLost, requireNotNull(real.witness).observe())
        }

        private fun retireHost(dispose: Boolean) {
            compose.runOnUiThread {
                if (dispose) host.get().setContent {} else host.pause()
            }
            compose.waitForIdle()
        }
    }

    private fun copy() = EnrollmentWarningCopy(
        text(R.string.notification_enrollment_title), text(R.string.notification_enrollment_warning),
        text(R.string.notification_enrollment_confirm), text(R.string.notification_enrollment_cancel),
    )

    private fun realJournal(): NotificationEnrollmentJournalAdapter {
        val limits = NotificationReplyLimits(3, 10_000, 30_000)
        return NotificationEnrollmentJournalAdapter(host.get(), limits, 30_000,
            NotificationReplyStagingStore.IssuanceLimits(3, 10_000, 30_000), limits)
    }

    private fun mountReal(point: String? = null): EnrollmentLedgerFixture {
        val real = EnrollmentLedgerFixture(temporary.newFolder())
        ledger = real
        if (point != null) real.gate = EnrollmentLedgerFixture.Gate(point)
        producer = NotificationEnrollmentProducer(scope, real.backend(realJournal())) { UUID.randomUUID().also { ids++ } }
        mount()
        start()
        compose.waitForIdle()
        return real
    }

    private fun shownRequest(): Enrollment.EnrollmentRequest {
        val request = assertIs<NotificationEnrollmentProducer.State.Warning>(producer.state.value).presentation.request
        generationFile = File(host.get().noBackupFilesDir, "notification-reply-generation-${request.operation.storeId}.db")
        return request
    }

    private fun awaitRealOutcome() {
        compose.waitUntil(30_000) { producer.state.value is NotificationEnrollmentProducer.State.Outcome }
    }

    private class RecordingBackend : NotificationEnrollmentProducer.Backend {
        val events = mutableListOf<String>()
        var stages = 0
        var enrollments = 0
        var resumes = 0
        var shown: Shown? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun observe(copy: EnrollmentWarningCopy) = Enrollment.Observation.UnknownOrLost
        override suspend fun resume(copy: EnrollmentWarningCopy): Enrollment.Result {
            resumes++
            return Enrollment.Result.NeedsAcknowledgment
        }
        override fun present(request: Enrollment.EnrollmentRequest, copy: EnrollmentWarningCopy) =
            Shown(request, copy).also { shown = it }

        inner class Shown(
            override val request: Enrollment.EnrollmentRequest,
            override val copy: EnrollmentWarningCopy,
        ) : NotificationEnrollmentProducer.Presentation {
            var retired = false
            override fun retire() { retired = true; events += "retire" }
            override suspend fun invalidate() { check(retired); events += "invalidate" }
            override suspend fun stageExplicitAction(): NotificationEnrollmentProducer.Staging {
                stages++
                events += "stage"
                gate?.await()
                return NotificationEnrollmentProducer.Staging.STAGED
            }
            override suspend fun enroll(): Enrollment.Result {
                check(stages == 1 && !retired)
                events += "enroll"
                enrollments++
                return Enrollment.Result.Indeterminate(request.operation, Enrollment.Step.AUTHORIZE)
            }
        }
    }
}
