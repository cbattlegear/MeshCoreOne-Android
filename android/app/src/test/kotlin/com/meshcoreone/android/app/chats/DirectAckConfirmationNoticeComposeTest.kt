// AndroidOnly: WP-309 Mounted notice/state controls with canonical complete-evidence fixtures.
// These are not real Room, physical-radio, integrated App-flow or full-WP acceptance evidence.
package com.meshcoreone.android.app.chats

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotSet
import com.meshcoreone.android.feature.chats.actions.DirectAckConfirmationNoticeRoute
import com.meshcoreone.android.feature.chats.actions.DirectAckConfirmationObservation
import com.meshcoreone.android.feature.chats.actions.DirectAckConfirmationStateHolder
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DirectAckConfirmationNoticeComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var previousLocale: Locale
    private lateinit var holder: DirectAckConfirmationStateHolder
    private lateinit var graphScope: CoroutineScope
    private val unexpectedReports = mutableListOf<Throwable>()
    private val radio = RadioId(UUID(309, 51))
    private val otherRadio = RadioId(UUID(309, 52))
    private val token = SessionToken(ProcessEpoch(UUID(309, 53)), Generation(1), radio)
    private val binding = mutableStateOf(DirectAckConfirmationObservation(radio, token, 1))

    @Before fun create() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.US)
        createHost(Locale.US)
    }

    private fun createHost(locale: Locale) {
        host = Robolectric.buildActivity(ComponentActivity::class.java)
        host.get().applyOverrideConfiguration(Configuration().apply { setLocale(locale); fontScale = 2f })
        host.setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    private fun destroyHost() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    @After fun close() {
        try { destroyHost() }
        finally {
            if (::holder.isInitialized) holder.invalidate()
            if (::graphScope.isInitialized) graphScope.cancel()
            Locale.setDefault(previousLocale)
        }
    }

    private fun source(read: suspend (RadioId) -> DirectAckRadioConfirmationEvidence) =
        object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Inspecting(radioId))
                emit(DirectAckRadioConfirmationObservation.Snapshot(read(radioId)))
                awaitCancellation()
            }
        }

    private fun wildcard(id: RadioId = radio) = DirectAckRadioConfirmationEvidence.PersistedWildcard(
        id, SnapshotSet(DirectAckRadioConfirmationWildcardReason.entries.filterNot {
            it == DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN
        }),
    )

    @Test fun unknownPrehistoryStatesAllNewConfirmationsUnavailableAndOffersNoClearingAction() {
        mount(source { id -> DirectAckRadioConfirmationEvidence.PersistedWildcard(
            id, SnapshotSet(listOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN)),
        ) })
        assertBody(R.string.direct_ack_confirmation_preauthority_unknown)
        val copy = text(R.string.direct_ack_confirmation_preauthority_unknown)
        assertTrue(copy.contains("New direct-message delivery confirmations are unavailable"))
        assertTrue(copy.contains("indefinitely"))
        assertTrue(copy.contains("still send"))
        assertTrue(copy.contains("Previously confirmed deliveries remain delivered"))
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_uncertain)).assertDoesNotExist()
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_check_again)).assertDoesNotExist()
    }

    @Test fun unknownPrehistoryDominatesOtherReasonsAndSurvivesAReadFailureWithoutAFakeClear() {
        var fail = false
        val releaseFailure = CompletableDeferred<Unit>()
        mount(source { id ->
            if (fail) {
                releaseFailure.await()
                throw PersistenceStoreException(PersistenceStoreError.FetchFailed("synthetic read failure"))
            }
            DirectAckRadioConfirmationEvidence.PersistedWildcard(
                id, SnapshotSet(DirectAckRadioConfirmationWildcardReason.entries),
            )
        })
        assertBody(R.string.direct_ack_confirmation_preauthority_unknown)
        fail = true
        holder.recheck()
        compose.waitUntil(30_000) {
            holder.state.value.status ==
                com.meshcoreone.android.feature.chats.actions.DirectAckConfirmationStatus.Checking
        }
        assertBody(R.string.direct_ack_confirmation_preauthority_unknown)
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_check_again)).assertDoesNotExist()
        releaseFailure.complete(Unit)
        compose.waitUntil(30_000) {
            holder.state.value.status is
                com.meshcoreone.android.feature.chats.actions.DirectAckConfirmationStatus.ReadUnavailable
        }
        assertBody(R.string.direct_ack_confirmation_preauthority_unknown)
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_check_again)).assertDoesNotExist()
    }

    @Test fun otherPersistedReasonsKeepScopedWarningAndReadOnlyRecheck() {
        for (reason in DirectAckRadioConfirmationWildcardReason.entries.filterNot {
            it == DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN
        }) {
            mount(source { id -> DirectAckRadioConfirmationEvidence.PersistedWildcard(id, SnapshotSet(listOf(reason))) })
            assertBody(R.string.direct_ack_confirmation_uncertain)
            compose.onNodeWithText(text(R.string.direct_ack_confirmation_preauthority_unknown)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.direct_ack_confirmation_check_again)).performScrollTo().assertIsEnabled()
        }
    }

    @Test fun strictUnknownCopyHasDistinctResourcesInEverySupportedLocale() {
        val english = text(R.string.direct_ack_confirmation_preauthority_unknown)
        for (tag in listOf("de", "es", "fr", "it", "ko", "nl", "pl", "pt", "ru", "uk", "zh-Hans")) {
            val localized = host.get().createConfigurationContext(Configuration().apply {
                setLocale(Locale.forLanguageTag(tag))
            }).getString(R.string.direct_ack_confirmation_preauthority_unknown)
            assertTrue(tag, localized.isNotBlank())
            assertNotEquals(tag, english, localized)
        }
    }

    private fun mount(
        observer: DirectAckRadioConfirmationEvidenceObserving?,
        direction: LayoutDirection = LayoutDirection.Ltr,
    ) {
        if (::holder.isInitialized) holder.invalidate()
        if (::graphScope.isInitialized) graphScope.cancel()
        graphScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, cause -> unexpectedReports += cause })
        holder = DirectAckConfirmationStateHolder(observer, graphScope)
        holder.observe(binding.value)
        render(direction)
    }

    private fun rebind(observation: DirectAckConfirmationObservation) {
        binding.value = observation
        holder.observe(observation)
    }

    private fun render(direction: LayoutDirection = LayoutDirection.Ltr) {
        host.get().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                MeshCoreTheme(
                    colorSchemePreference = AppColorSchemePreference.LIGHT,
                    systemDark = false, highContrast = false, motionScale = 0f,
                ) {
                    Column(Modifier.width(360.dp).fillMaxSize().verticalScroll(rememberScrollState())) {
                        DirectAckConfirmationNoticeRoute(binding.value, holder)
                    }
                }
            }
        }
    }

    private fun text(id: Int) = host.get().getString(id)

    private fun assertBody(id: Int) {
        val node = compose.onNodeWithText(text(id)).performScrollTo().assertIsDisplayed()
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.isNotEmpty())
        assertTrue(layouts.none { it.hasVisualOverflow })
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_title))
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        compose.onNodeWithText("Delivered").assertDoesNotExist()
        compose.onNodeWithText("Failed").assertDoesNotExist()
        assertEquals(2f, host.get().resources.configuration.fontScale, 0f)
    }

    @Test fun missingCapabilityIsLocalizedAndAccessibleWithoutAnAllClearOrFakeRetry() {
        destroyHost()
        Locale.setDefault(Locale.FRANCE)
        createHost(Locale.FRANCE)
        mount(null, LayoutDirection.Rtl)
        assertBody(R.string.direct_ack_confirmation_missing)
        assertEquals("Confirmation de livraison", text(R.string.direct_ack_confirmation_title))
        assertNotEquals(
            "This radio's stored delivery evidence cannot be checked here. Delivery is not confirmed.",
            text(R.string.direct_ack_confirmation_missing),
        )
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_check_again)).assertDoesNotExist()
    }

    @Test fun actualWaitingQueryThenPersistedUncertaintyNeverRendersFailedOrDelivered() {
        val gate = CompletableDeferred<DirectAckRadioConfirmationEvidence>()
        val reads = mutableListOf<RadioId>()
        mount(source { reads += it; gate.await() })
        assertBody(R.string.direct_ack_confirmation_checking)
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_checked)).assertDoesNotExist()
        compose.runOnIdle { gate.complete(wildcard()) }
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { assertEquals(listOf(radio), reads) }
    }

    @Test fun noWildcardSnapshotStillExplicitlyDoesNotConfirmDelivery() {
        mount(source { DirectAckRadioConfirmationEvidence.NoPersistedWildcard(it) })
        assertBody(R.string.direct_ack_confirmation_checked)
        assertEquals(
            "The stored-evidence check finished. It does not confirm whether your messages were delivered.",
            text(R.string.direct_ack_confirmation_checked),
        )
    }

    @Test fun typedReadErrorOffersAReadOnlyAccessibleRecheck() {
        var reads = 0
        val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("fixture"))
        mount(source {
            if (reads++ == 0) throw failure
            wildcard()
        })
        assertBody(R.string.direct_ack_confirmation_unavailable)
        val button = compose.onNodeWithText(text(R.string.direct_ack_confirmation_check_again))
            .performScrollTo().assertIsDisplayed()
        button.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertTrue(button.getUnclippedBoundsInRoot().height >= 48.dp)
        button.performClick()
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { assertEquals(2, reads) }
    }

    @Test fun recreatedActivityRetainsTheSingleGraphMonitorAndItsWarning() {
        val evidence = wildcard()
        var reads = 0
        val observer = source { assertEquals(radio, it); reads++; evidence }
        mount(observer)
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { assertEquals(1, reads) }
        destroyHost()
        createHost(Locale.US)
        render()
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { assertEquals(1, reads) }
        // A replaced graph must query retained evidence again, not reconstruct a healthy default.
        mount(observer)
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { assertEquals(2, reads) }
    }

    @Test fun replacingRadioRejectsTheOldNoncancellableResultInTheMountedRoute() {
        val old = CompletableDeferred<DirectAckRadioConfirmationEvidence>()
        val reads = mutableListOf<RadioId>()
        mount(source {
            reads += it
            if (it == radio) withContext(NonCancellable) { old.await() }
            else DirectAckRadioConfirmationEvidence.NoPersistedWildcard(it)
        })
        assertBody(R.string.direct_ack_confirmation_checking)
        compose.runOnIdle { rebind(DirectAckConfirmationObservation(otherRadio, null, 2)) }
        assertBody(R.string.direct_ack_confirmation_checked)
        compose.runOnIdle { old.complete(wildcard()) }
        assertBody(R.string.direct_ack_confirmation_checked)
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_uncertain)).assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(radio, otherRadio), reads) }
    }

    @Test fun sameRadioSessionAndProducerEpochReplacementBothRequeryWithoutReusingOldAbsence() {
        var reads = 0
        mount(source {
            reads++
            if (reads == 1) DirectAckRadioConfirmationEvidence.NoPersistedWildcard(it) else wildcard(it)
        })
        assertBody(R.string.direct_ack_confirmation_checked)
        compose.runOnIdle {
            rebind(binding.value.copy(sessionToken = token.copy(generation = Generation(2))))
        }
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { rebind(binding.value.copy(observationEpoch = 2)) }
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { assertEquals(3, reads) }
    }

    @Test fun stoppedScreenKeepsOneGraphMonitorAndResumesWithItsNewEvidence() {
        val events = Channel<DirectAckRadioConfirmationObservation>(Channel.UNLIMITED)
        var subscriptions = 0
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId): Flow<DirectAckRadioConfirmationObservation> =
                flow {
                    subscriptions++
                    emit(DirectAckRadioConfirmationObservation.Snapshot(
                        DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId),
                    ))
                    for (event in events) emit(event)
                }
        }
        mount(observer)
        assertBody(R.string.direct_ack_confirmation_checked)
        compose.runOnIdle {
            host.pause().stop()
            events.trySend(DirectAckRadioConfirmationObservation.Inspecting(radio)).getOrThrow()
            events.trySend(DirectAckRadioConfirmationObservation.Snapshot(wildcard())).getOrThrow()
        }
        compose.runOnIdle { host.start().resume().visible(); host.windowFocusChanged(true) }
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { assertEquals(1, subscriptions) }
    }

    @Test fun refreshAndReadFailureDoNotEraseAKnownWildcardWarning() {
        val refresh = CompletableDeferred<Unit>()
        val fail = CompletableDeferred<Unit>()
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(wildcard(radioId)))
                refresh.await()
                emit(DirectAckRadioConfirmationObservation.Inspecting(radioId))
                fail.await()
                throw PersistenceStoreException(PersistenceStoreError.FetchFailed("closed fixture"))
            }
        }
        mount(observer)
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { refresh.complete(Unit) }
        assertBody(R.string.direct_ack_confirmation_uncertain)
        compose.runOnIdle { fail.complete(Unit) }
        assertBody(R.string.direct_ack_confirmation_uncertain)
        assertBody(R.string.direct_ack_confirmation_unavailable)
    }

    @Test fun unexpectedCompletionIsUnavailableNotAnApparentClear() {
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(
                    DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId),
                ))
            }
        }
        mount(observer)
        assertBody(R.string.direct_ack_confirmation_unavailable)
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_checked)).assertDoesNotExist()
    }

    @Test fun unexpectedExceptionAfterNoWildcardReplacesTheHistoricalNoticeWithUnavailable() {
        val fail = CompletableDeferred<Unit>()
        val unexpected = IllegalStateException("unexpected mounted fixture")
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(
                    DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId),
                ))
                fail.await()
                throw unexpected
            }
        }
        mount(observer)
        assertBody(R.string.direct_ack_confirmation_checked)
        compose.runOnIdle { fail.complete(Unit) }
        assertBody(R.string.direct_ack_confirmation_unavailable)
        compose.onNodeWithText(text(R.string.direct_ack_confirmation_checked)).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, unexpectedReports.size)
            assertSame(unexpected, unexpectedReports.single())
        }
    }
}
