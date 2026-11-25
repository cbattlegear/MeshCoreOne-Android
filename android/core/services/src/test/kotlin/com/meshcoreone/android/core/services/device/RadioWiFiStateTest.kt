// AndroidOnly: WP-211 Companion Wi-Fi state regressions compared with current upstream.
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.contracts.domain.RadioWiFiResetObservation
import com.meshcoreone.android.core.contracts.domain.RadioWiFiState
import com.meshcoreone.android.core.contracts.domain.RadioWiFiStateRegistry
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class RadioWiFiStateTest {
    private fun state() = RadioWiFiState(RadioId(UUID.randomUUID()))
    private fun observation(uptime: UInt, started: Duration, completed: Duration = started) =
        RadioWiFiResetObservation(uptime, started, completed)

    @Test
    fun mutationsInvalidateReadsAndOnlyTheirOwnTokensMaySettle() {
        val state = state()
        val initial = assertNotNull(state.stableRevision())
        val first = assertNotNull(state.beginMutation(initial))
        assertNull(state.stableRevision())
        assertNull(state.beginMutation(initial))
        val second = assertNotNull(state.beginMutation(null))
        assertFalse(state.isUncontended(first))
        assertNull(state.settle(first, RadioWiFiState.MutationOutcome.ACCEPTED))
        assertTrue(state.isRebootPending)
        assertNull(state.settle(first, RadioWiFiState.MutationOutcome.REJECTED))
        assertNull(state.stableRevision())
        assertNull(state.settle(second, RadioWiFiState.MutationOutcome.REJECTED))
        assertTrue(state.isRebootPending)
        assertTrue(assertNotNull(state.stableRevision()) != initial)
    }

    @Test
    fun drainClosesAdmissionAndWaitsForEveryAdmittedMutation() = runTest {
        val state = state()
        val first = assertNotNull(state.beginMutation(null))
        val second = assertNotNull(state.beginMutation(null))
        state.closeAdmission()
        assertNull(state.beginMutation(null))
        assertNull(state.stableRevision())
        val wait = async { state.waitForMutationSettlement() }
        runCurrent()
        assertFalse(wait.isCompleted)
        state.settle(first, RadioWiFiState.MutationOutcome.REJECTED)
        runCurrent()
        assertFalse(wait.isCompleted)
        state.settle(second, RadioWiFiState.MutationOutcome.UNCERTAIN)
        wait.await()
        assertTrue(state.isRebootPending)
        state.openAdmission()
        assertNotNull(state.stableRevision())
    }

    @Test
    fun rebootClearsOnlyWithSubmissionLinkEndNewContextAndResetProof() {
        val state = state()
        val mutation = assertNotNull(state.beginMutation(null))
        state.settle(mutation, RadioWiFiState.MutationOutcome.ACCEPTED)
        val originalContext = UUID.randomUUID()
        val nextContext = UUID.randomUUID()
        val before = observation(1000u, Duration.ZERO)
        val after = observation(1u, 1.seconds)
        val reboot = assertNotNull(state.prepareExpectedReboot(originalContext, observation = before))
        val revision = assertNotNull(state.stableRevision())
        assertFalse(state.clearExpectedReboot(reboot, nextContext, revision, after))
        state.noteLinkEnded(originalContext)
        assertFalse(state.clearExpectedReboot(reboot, nextContext, revision, after))
        state.completeRebootSubmission(reboot, succeeded = true)
        assertFalse(state.clearExpectedReboot(reboot, originalContext, revision, after))
        assertFalse(state.clearExpectedReboot(reboot, nextContext, revision, null))
        assertTrue(state.clearExpectedReboot(reboot, nextContext, revision, after))
        assertFalse(state.isRebootPending)
    }

    @Test
    fun newerMutationInvalidatesEarlierRebootForEveryOutcome() {
        for (outcome in RadioWiFiState.MutationOutcome.entries) {
            val state = state()
            val first = assertNotNull(state.beginMutation(null))
            state.settle(first, RadioWiFiState.MutationOutcome.ACCEPTED)
            val oldContext = UUID.randomUUID()
            val reboot = assertNotNull(state.prepareExpectedReboot(oldContext, observation = observation(1000u, Duration.ZERO)))
            state.completeRebootSubmission(reboot, succeeded = true)
            state.noteLinkEnded(oldContext)
            val newer = assertNotNull(state.beginMutation(null))
            state.settle(newer, outcome)
            assertFalse(state.clearExpectedReboot(
                reboot, UUID.randomUUID(), assertNotNull(state.stableRevision()), observation(1u, 1.seconds),
            ))
            assertTrue(state.isRebootPending)
        }
    }

    @Test
    fun rejectedAndSupersededRebootsKeepPendingEvidence() {
        val state = state()
        val mutation = assertNotNull(state.beginMutation(null))
        state.settle(mutation, RadioWiFiState.MutationOutcome.UNCERTAIN)
        val context = UUID.randomUUID()
        val failed = assertNotNull(state.prepareExpectedReboot(context))
        state.completeRebootSubmission(failed, succeeded = false)
        assertNull(state.expectedRebootId)
        val superseded = assertNotNull(state.prepareExpectedReboot(context))
        state.completeRebootSubmission(superseded, succeeded = true)
        state.noteLinkEnded(context)
        state.cancelExpectedReboot()
        assertFalse(state.clearExpectedReboot(superseded, UUID.randomUUID(), assertNotNull(state.stableRevision()), null))
        assertTrue(state.isRebootPending)
    }

    @Test
    fun explicitConnectionFencesInFlightAndOverlappingRebootPreparations() {
        val state = state()
        state.settle(assertNotNull(state.beginMutation(null)), RadioWiFiState.MutationOutcome.ACCEPTED)
        val context = UUID.randomUUID()
        val staleEpoch = state.rebootEpoch
        val first = state.blockExpectedReboot(context)
        val second = state.blockExpectedReboot(context)
        assertNull(state.prepareExpectedReboot(context, expectedEpoch = staleEpoch))
        assertNull(state.prepareExpectedReboot(context, expectedEpoch = state.rebootEpoch))
        state.unblockExpectedReboot(context, first)
        assertNull(state.prepareExpectedReboot(context, expectedEpoch = state.rebootEpoch))
        state.unblockExpectedReboot(context, second)
        assertNull(state.prepareExpectedReboot(context, expectedEpoch = staleEpoch))
        assertNotNull(state.prepareExpectedReboot(context, expectedEpoch = state.rebootEpoch))
        assertTrue(state.isRebootPending)
    }

    @Test
    fun identityAdmissionTransitionInvalidatesAnInFlightRebootObservation() {
        val state = state()
        state.settle(assertNotNull(state.beginMutation(null)), RadioWiFiState.MutationOutcome.ACCEPTED)
        val staleEpoch = state.rebootEpoch
        state.closeAdmission()
        state.openAdmission()
        assertNull(state.prepareExpectedReboot(UUID.randomUUID(), expectedEpoch = staleEpoch))
        assertTrue(state.isRebootPending)
    }

    @Test
    fun canonicalEvidenceRetainsPendingAndInvalidatesEarlierRevision() {
        val state = state()
        val previous = assertNotNull(state.stableRevision())
        state.closeAdmission()
        state.adoptCanonicalEvidence(inheritedRebootPending = true)
        assertNull(state.stableRevision())
        state.openAdmission()
        assertTrue(state.isRebootPending)
        assertNull(state.beginMutation(previous))
        val revision = assertNotNull(state.stableRevision())
        state.closeAdmission()
        state.adoptCanonicalEvidence(inheritedRebootPending = false)
        state.openAdmission()
        assertTrue(state.isRebootPending)
        assertNull(state.beginMutation(revision))
    }

    @Test
    fun identityTransferDrainsBothStatesEvenWhenItsCallerIsCancelled() = runTest {
        val radio = RadioId(UUID.randomUUID())
        val otherRadio = RadioId(UUID.randomUUID())
        val registry = RadioWiFiStateRegistry()
        val previous = registry.prepare(radio)
        val canonical = registry.prepare(otherRadio)
        val oldWrite = assertNotNull(previous.beginMutation(null))
        val newWrite = assertNotNull(canonical.beginMutation(null))
        val transfer = async { registry.transfer(radio, otherRadio) }
        runCurrent()
        assertNull(previous.beginMutation(null))
        assertNull(canonical.beginMutation(null))
        transfer.cancel()
        val next = async { registry.prepare(otherRadio) }
        runCurrent()
        assertFalse(next.isCompleted)
        previous.settle(oldWrite, RadioWiFiState.MutationOutcome.UNCERTAIN)
        runCurrent()
        assertFalse(next.isCompleted)
        canonical.settle(newWrite, RadioWiFiState.MutationOutcome.REJECTED)
        runCurrent()
        val reused = next.await()
        assertSame(canonical, reused)
        assertTrue(reused.isRebootPending)
        assertNull(previous.beginMutation(null))
        val freshPrevious = registry.prepare(radio)
        assertNotSame(previous, freshPrevious)
        assertFalse(freshPrevious.isRebootPending)
    }

    @Test
    fun onlyAResetThatExcludesContinuousFirmwareProgressCounts() {
        val cases = listOf(
            Triple(1000u, 1u, 1L) to true,
            Triple(1000u, 1001u, 1L) to false,
            Triple(1u, 1u, 1L) to false,
            Triple(4_294_967u, 0u, 1L) to false,
            Triple(1000u, 1u, 4_294_968L) to false,
            Triple(UInt.MAX_VALUE, 1u, 1L) to false,
            Triple(1000u, 100u, 1L) to false,
            Triple(1000u, 1u, 4_294_000L) to false,
        )
        cases.forEach { (input, expected) ->
            val (before, after, elapsed) = input
            assertEquals(expected, observation(after, elapsed.seconds).provesReset(observation(before, Duration.ZERO)), "$input")
        }
        assertFalse(observation(0u, 1.seconds, 3.seconds).provesReset(observation(1000u, Duration.ZERO, 2.seconds)))
        assertFalse(observation(0u, 1.seconds, Duration.ZERO).provesReset(observation(1000u, Duration.ZERO)))
    }
}
