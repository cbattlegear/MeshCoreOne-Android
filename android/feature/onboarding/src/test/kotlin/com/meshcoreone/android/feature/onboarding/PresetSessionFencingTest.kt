// AndroidOnly: WP-305 Fence asynchronous preset results and completion publication to their initiating target.
package com.meshcoreone.android.feature.onboarding

import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.ui.UiText
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PresetSessionFencingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val first = OnboardingPresetTarget(UUID.randomUUID(), 1, UUID.randomUUID())
    private val option = OnboardingPresetOption("preset", "Preset", 915.0)
    private val region = FakeRegion(presets = mapOf(null to listOf(option)))

    @After fun close() { scope.cancel() }

    private class HeldPreset(var target: OnboardingPresetTarget?) : OnboardingPresetPort {
        override val canApply = MutableStateFlow(true)
        var release = CompletableDeferred<Unit>()
        var calls = 0
        var outcome: OnboardingPresetOutcome = OnboardingPresetOutcome.Applied
        override fun currentTarget() = target
        override suspend fun apply(presetId: String): OnboardingPresetOutcome {
            calls++
            release.await()
            return outcome
        }
    }

    @Test fun delayedReceiptRetainsItsOriginalProducerAfterLaterReplacement() {
        val port = HeldPreset(first)
        val state = OnboardingState(InMemoryOnboardingFlagStore())
        lateinit var coordinator: PresetStepCoordinator
        coordinator = PresetStepCoordinator(scope, region, port) {
            state.completeOnboardingWithReceipt(
                coordinator.completionReceipt(), coordinator.completionTargetGuard(), coordinator.completionSealer(),
            )
        }
        coordinator.apply(option.id)
        port.release.complete(Unit)
        val receipt = state.completionReceipt
        assertEquals(OnboardingCompletionReceipt.Preset(option.id, first), receipt)
        port.target = first.copy(generation = 2, radioId = UUID.randomUUID())
        assertSame(receipt, state.completionReceipt)
        assertEquals(first, (state.completionReceipt as OnboardingCompletionReceipt.Preset).target)
    }

    @Test fun onlyAcknowledgedCompletionPublishesItsReceiptAndResetRemovesIt() = runBlocking {
        lateinit var state: OnboardingState
        val receipt = OnboardingCompletionReceipt.Preset(option.id, first)
        val flags = object : OnboardingFlagStore {
            var value = false
            override fun getBoolean(key: String) = value
            override fun setBoolean(key: String, value: Boolean) { this.value = value }
            override suspend fun persistBoolean(key: String, value: Boolean) {
                if (value) assertNull(state.completionReceipt)
                this.value = value
            }
        }
        state = OnboardingState(flags)
        state.completeOnboardingWithReceipt(receipt, {}) { it.trySeal() }
        assertSame(receipt, state.completionReceipt)
        state.resetOnboarding()
        assertNull(state.completionReceipt)
    }

    @Test fun resetRevokesReceiptBeforeNotifyingLifecycleObservers() = runBlocking {
        val state = OnboardingState(InMemoryOnboardingFlagStore())
        state.completeOnboarding()
        val original = checkNotNull(state.completionPublication.value)
        var ownedWhenNotified: Boolean? = null
        val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            state.lifecycleGeneration.drop(1).take(1).collect {
                ownedWhenNotified = state.ownsCompletion(original)
            }
        }
        state.resetOnboarding()
        observer.join()
        assertEquals(false, ownedWhenNotified)
        assertFalse(state.hasCompletedOnboarding)
        assertNull(state.completionReceipt)
        state.completeOnboarding()
        assertTrue(state.hasCompletedOnboarding)
        assertFalse(state.ownsCompletion(original))
    }

    @Test fun completedFlowRejectsAReplacementReceiptBeforeStartingAnotherWrite() = runBlocking {
        var writes = 0
        val flags = object : OnboardingFlagStore {
            var value = false
            override fun getBoolean(key: String) = value
            override fun setBoolean(key: String, value: Boolean) { writes++; this.value = value }
        }
        val state = OnboardingState(flags)
        val firstReceipt = OnboardingCompletionReceipt.Preset(option.id, first)
        state.completeOnboardingWithReceipt(firstReceipt, {}) { it.trySeal() }
        assertFailsWith<IllegalStateException> {
            state.completeOnboardingWithReceipt(
                OnboardingCompletionReceipt.Preset(option.id, first.copy(generation = 2)), {},
            ) { it.trySeal() }
        }
        assertEquals(1, writes)
        assertSame(firstReceipt, state.completionReceipt)
        assertTrue(state.hasCompletedOnboarding)
        state.retireCompletion()
        assertFailsWith<IllegalStateException> { state.completeOnboarding() }
        assertEquals(1, writes)
    }

    @Test fun explicitNoDeviceReceiptIsDistinctFromMissingIdentityAndRestoredCompletion() = runBlocking {
        val fresh = OnboardingState(InMemoryOnboardingFlagStore())
        assertNull(fresh.completionReceipt)
        fresh.completeOnboarding()
        assertEquals(OnboardingCompletionReceipt.WithoutDevice, fresh.completionReceipt)
        val noDevicePublication = checkNotNull(fresh.completionPublication.value)
        assertEquals(OnboardingCompletionProducer.NoDevice(null), noDevicePublication.producer)
        assertNotNull(noDevicePublication.attemptId)

        val restored = OnboardingState(InMemoryOnboardingFlagStore(mapOf(OnboardingState.KEY_HAS_COMPLETED to true)))
        assertEquals(OnboardingCompletionReceipt.Restored, restored.completionReceipt)
        assertNull(restored.completionPublication.value?.producer)
        assertNull(restored.completionPublication.value?.attemptId)

        val missingTarget = OnboardingCompletionReceipt.Preset(option.id, null)
        assertFalse(missingTarget == fresh.completionReceipt)
        val unattributed = OnboardingState(InMemoryOnboardingFlagStore())
        assertFailsWith<IllegalArgumentException> {
            unattributed.completeOnboardingWithReceipt(missingTarget, {}) { it.trySeal() }
        }
        assertFalse(unattributed.hasCompletedOnboarding)
        assertNull(unattributed.completionPublication.value)
        assertFailsWith<IllegalArgumentException> {
            unattributed.completeOnboardingWithReceipt(OnboardingCompletionReceipt.Restored, {}) { it.trySeal() }
        }
        Unit
    }

    @Test fun retainedPublicationKeepsOriginalSessionAndRejectsRetiredAttempt() = runBlocking {
        fun token(target: OnboardingPresetTarget) =
            SessionToken(ProcessEpoch(target.epoch), Generation(target.generation), RadioId(target.radioId))

        val state = OnboardingState(InMemoryOnboardingFlagStore())
        val original = OnboardingCompletionReceipt.Preset(option.id, first)
        state.completeOnboardingWithReceipt(original, {}) { it.trySeal() }
        val publication = checkNotNull(state.completionPublication.value)
        val originalSession = token(first)
        assertEquals(OnboardingCompletionProducer.Preset(originalSession), publication.producer)
        val originalAttemptId = checkNotNull(publication.attemptId)

        val recreatedDelivery = state.completionPublication.value
        assertSame(publication, recreatedDelivery)
        val successorSession = token(first.copy(generation = 2))
        assertNotEquals(OnboardingCompletionProducer.Preset(successorSession), publication.producer)

        state.resetOnboarding()
        var staleNavigationCommits = 0
        assertFalse(state.commitCompletion(publication) { staleNavigationCommits++; true })
        assertEquals(0, staleNavigationCommits)

        state.completeOnboardingWithReceipt(
            OnboardingCompletionReceipt.Preset(option.id, first.copy(generation = 2)), {},
        ) { it.trySeal() }
        val successorPublication = checkNotNull(state.completionPublication.value)
        assertEquals(OnboardingCompletionProducer.Preset(successorSession), successorPublication.producer)
        assertNotEquals(originalAttemptId, successorPublication.attemptId)
        assertFalse(state.commitCompletion(publication) { staleNavigationCommits++; true })
        assertEquals(0, staleNavigationCommits)
    }

    @Test fun cancelledCompletionDoesNotPublishProducerOrAttempt() = runBlocking {
        val flags = object : OnboardingFlagStore {
            override fun getBoolean(key: String) = false
            override fun setBoolean(key: String, value: Boolean) = Unit
            override suspend fun persistBoolean(
                key: String,
                value: Boolean,
                requireCurrent: () -> Unit,
                seal: (OnboardingCompletionAttempt) -> Boolean,
                attempt: OnboardingCompletionAttempt,
            ) {
                throw CancellationException("completion cancelled before persistence")
            }
        }
        val state = OnboardingState(flags)

        assertFailsWith<CancellationException> {
            state.completeOnboardingWithReceipt(
                OnboardingCompletionReceipt.Preset(option.id, first), {},
            ) { it.trySeal() }
        }

        assertFalse(state.hasCompletedOnboarding)
        assertNull(state.completionPublication.value)
        assertFalse(state.hasUnsettledCompletion)
    }

    @Test fun readbackReconcilesTheOriginalProducerAndAttempt() = runBlocking {
        class UnknownResult : IllegalStateException(), OnboardingCompletionOutcomeUnknown

        var storedValue = false
        var originalAttemptId: UUID? = null
        val flags = object : OnboardingFlagStore {
            override fun getBoolean(key: String) = storedValue
            override fun setBoolean(key: String, value: Boolean) { storedValue = value }
            override suspend fun persistBoolean(
                key: String,
                value: Boolean,
                requireCurrent: () -> Unit,
                seal: (OnboardingCompletionAttempt) -> Boolean,
                attempt: OnboardingCompletionAttempt,
            ) {
                requireCurrent()
                check(seal(attempt))
                originalAttemptId = attempt.id
                storedValue = value
                throw UnknownResult()
            }
            override suspend fun reconcileCompletion(
                attempt: OnboardingCompletionAttempt,
                requireCurrent: () -> Unit,
                onFinished: (Result<Boolean>) -> Unit,
            ) {
                requireCurrent()
                onFinished(Result.success(storedValue))
            }
        }
        val state = OnboardingState(flags)
        val receipt = OnboardingCompletionReceipt.Preset(option.id, first)

        assertFailsWith<UnknownResult> {
            state.completeOnboardingWithReceipt(receipt, {}) { it.trySeal() }
        }
        assertTrue(state.canReconcileCompletion())
        assertTrue(state.reconcileCompletion())

        val publication = checkNotNull(state.completionPublication.value)
        assertEquals(receipt, publication.receipt)
        assertEquals(
            OnboardingCompletionProducer.Preset(
                SessionToken(ProcessEpoch(first.epoch), Generation(first.generation), RadioId(first.radioId)),
            ),
            publication.producer,
        )
        assertEquals(originalAttemptId, publication.attemptId)
    }

    @Test fun disconnectedApplyCannotCompleteAndConnectedRetryAppliesAgain() {
        val port = HeldPreset(first)
        var completions = 0
        val coordinator = PresetStepCoordinator(scope, region, port) { completions++ }
        coordinator.select(option.id)
        coordinator.apply()
        port.target = null
        port.release.complete(Unit)
        assertEquals(0, completions)
        assertFalse(coordinator.state.value.isApplying)
        assertEquals(UiText.Resource(O.presetErrorNotConnected), coordinator.state.value.error)
        port.target = first.copy(generation = 2)
        coordinator.apply()
        assertEquals(2, port.calls)
        assertEquals(1, completions)
    }

    @Test fun replacementRadioAndSameRadioNewGenerationRejectLateSuccess() {
        for (replacement in listOf(first.copy(radioId = UUID.randomUUID()), first.copy(generation = 2))) {
            val port = HeldPreset(first)
            var completions = 0
            val coordinator = PresetStepCoordinator(scope, region, port) { completions++ }
            coordinator.apply(option.id)
            port.target = replacement
            port.release.complete(Unit)
            assertEquals(0, completions)
            assertEquals(0, coordinator.state.value.commitTick)
            assertFalse(coordinator.state.value.completionNeedsRetry)
            assertEquals(UiText.Resource(O.presetErrorNotConnected), coordinator.state.value.error)
        }
    }

    @Test fun staleFailureDoesNotOfferRetryForAnObsoleteRadio() {
        val port = HeldPreset(first)
        port.outcome = OnboardingPresetOutcome.Retryable(UiText.Verbatim("old failure"))
        val coordinator = PresetStepCoordinator(scope, region, port) { error("must not complete") }
        coordinator.apply(option.id)
        port.target = first.copy(generation = 2)
        port.release.complete(Unit)
        assertEquals(null, coordinator.state.value.retryAlert)
        assertEquals(UiText.Resource(O.presetErrorNotConnected), coordinator.state.value.error)
    }

    @Test fun changedTargetDuringDurableWriteDoesNotPublishCompletion() = runBlocking {
        val port = HeldPreset(first)
        val releaseWrite = CompletableDeferred<Unit>()
        var durable = false
        val flags = object : OnboardingFlagStore {
            override fun getBoolean(key: String) = durable
            override fun setBoolean(key: String, value: Boolean) { durable = value }
            override suspend fun persistBoolean(
                key: String, value: Boolean, requireCurrent: () -> Unit,
                seal: (com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt) -> Boolean,
            ) {
                releaseWrite.await()
                requireCurrent()
                check(seal(com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt()))
                durable = value
            }
        }
        val state = OnboardingState(flags)
        lateinit var coordinator: PresetStepCoordinator
        coordinator = PresetStepCoordinator(scope, region, port) {
            state.completeOnboarding(coordinator.completionTargetGuard())
        }
        coordinator.apply(option.id)
        port.release.complete(Unit)
        assertTrue(coordinator.state.value.isApplying)
        port.target = first.copy(generation = 2)
        releaseWrite.complete(Unit)
        assertFalse(durable, "A retired staged completion must never become a valid durable completion")
        assertFalse(state.hasCompletedOnboarding)
        assertEquals(0, coordinator.state.value.commitTick)
        assertFalse(coordinator.state.value.completionNeedsRetry)
        assertEquals(UiText.Resource(O.presetErrorNotConnected), coordinator.state.value.error)
    }

    @Test fun sameTargetWriteFailureRetriesOnlyPersistenceButReplacementReapplies() {
        val port = HeldPreset(first)
        port.release.complete(Unit)
        var writes = 0
        val coordinator = PresetStepCoordinator(scope, region, port) {
            writes++
            if (writes <= 2) error("storage unavailable")
        }
        coordinator.select(option.id)
        coordinator.apply()
        assertTrue(coordinator.state.value.completionNeedsRetry)
        coordinator.retry()
        assertEquals(1, port.calls)
        assertEquals(2, writes)
        port.target = first.copy(generation = 2)
        coordinator.retry()
        assertEquals(2, port.calls)
        assertEquals(3, writes)
        assertEquals(1, coordinator.state.value.commitTick)
    }

    @Test fun cancellationReleasesBusyWithoutCompletion() {
        val port = HeldPreset(first)
        var completions = 0
        val coordinator = PresetStepCoordinator(scope, region, port) { completions++ }
        coordinator.apply(option.id)
        scope.cancel()
        port.release.complete(Unit)
        assertFalse(coordinator.state.value.isApplying)
        assertEquals(0, completions)
        assertEquals(0, coordinator.state.value.commitTick)
    }

    @Test fun unchangedTargetSuccessAndNoRadioOutcomeStillComplete() {
        for (outcome in listOf(OnboardingPresetOutcome.Applied, OnboardingPresetOutcome.NoRadioToConfigure)) {
            val port = HeldPreset(first)
            port.outcome = outcome
            port.release.complete(Unit)
            var completions = 0
            val coordinator = PresetStepCoordinator(scope, region, port) { completions++ }
            coordinator.apply(option.id)
            assertEquals(1, completions)
            assertEquals(1, coordinator.state.value.commitTick)
            assertFalse(coordinator.state.value.isApplying)
        }
    }
}
