// AndroidOnly: WP-201 Executable ambiguity candidate controls, not Room enforcement evidence.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class DirectAckAmbiguityCandidateTest {
    private val radio = RadioId(UUID(0, 1))
    private val target = wire(2, 3, 123u)

    @Test
    fun preparedTargetCannotSettleBeforeFirstWire() {
        assertEquals(
            setOf(DirectAckNonsettlingReason.PRE_WIRE),
            nonsettling(target.copy(emission = DirectAckEmissionEvidence.PREPARED)).reasons,
        )
    }

    @Test
    fun predictedCodeStaysNonsettlingUntilActualAcceptanceIsRecorded() {
        assertEquals(
            setOf(DirectAckNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED),
            nonsettling(target).reasons,
        )
        assertSame(
            DirectAckAmbiguityDecision.NoKnownAmbiguity,
            assess(target.copy(acceptedCode = 123u)),
        )
    }

    @Test
    fun sameValidClaimRetriesPreserveEarlierCodeDuringThirdWire() {
        val third = target.copy(wireRequestId = UUID(0, 5), predictedCode = 789u)
        val earlier = target.copy(acceptedCode = 123u)
        assertSame(
            DirectAckAmbiguityDecision.NoKnownAmbiguity,
            assess(earlier, history = SnapshotList.of(earlier, third)),
        )
    }

    @Test
    fun latestEmittingRetryTargetRetainsEarlierAcceptedSameClaimCode() {
        val earlier = target.copy(acceptedCode = 123u)
        val retry = target.copy(wireRequestId = UUID(0, 4), predictedCode = 789u)

        assertSame(
            DirectAckAmbiguityDecision.NoKnownAmbiguity,
            assess(retry, 123u, SnapshotList.of(earlier, retry)),
        )
    }

    @Test
    fun latestPreparedRetryTargetDoesNotFenceEarlierEligibleSameClaimAck() {
        val earlier = target.copy(acceptedCode = 123u)
        val retry = target.copy(
            wireRequestId = UUID(0, 4),
            predictedCode = 789u,
            emission = DirectAckEmissionEvidence.PREPARED,
        )

        assertSame(
            DirectAckAmbiguityDecision.NoKnownAmbiguity,
            assess(retry, 123u, SnapshotList.of(earlier, retry)),
        )
        assertEquals(
            setOf(DirectAckNonsettlingReason.PRE_WIRE),
            assertIs<DirectAckAmbiguityDecision.Nonsettling>(
                assess(retry, 789u, SnapshotList.of(earlier, retry)),
            ).reasons,
        )
    }

    @Test
    fun acceptedActualSupersedesOnlyItsOwnPrediction() {
        val acceptedMismatch = target.copy(acceptedCode = 456u)
        assertEquals(
            setOf(DirectAckNonsettlingReason.UNREGISTERED_CODE),
            nonsettling(acceptedMismatch).reasons,
        )

        val earlierEligible = target.copy(acceptedCode = 123u)
        val currentAcceptedMismatch = target.copy(
            wireRequestId = UUID(0, 4),
            predictedCode = 123u,
            acceptedCode = 456u,
        )
        assertSame(
            DirectAckAmbiguityDecision.NoKnownAmbiguity,
            assess(currentAcceptedMismatch, 123u, SnapshotList.of(earlierEligible, currentAcceptedMismatch)),
        )
    }

    @Test
    fun earlierSameClaimMatchStillChecksCrossClaimCollisionAndWildcard() {
        val earlier = target.copy(acceptedCode = 123u)
        val retry = target.copy(wireRequestId = UUID(0, 4), predictedCode = 789u)
        val competitor = wire(6, 7, 123u)

        assertEquals(
            setOf(
                DirectAckNonsettlingReason.UNRESOLVED_PRIOR_EMISSION,
                DirectAckNonsettlingReason.KNOWN_CROSS_CLAIM_CODE,
            ),
            assertIs<DirectAckAmbiguityDecision.Nonsettling>(
                assess(retry, 123u, SnapshotList.of(earlier, retry, competitor)),
            ).reasons,
        )
    }

    @Test
    fun identicalActualCrossClaimCodeRemainsAmbiguousAfterBothAccept() {
        val old = wire(6, 7, 123u, 123u)
        assertEquals(
            setOf(DirectAckNonsettlingReason.KNOWN_CROSS_CLAIM_CODE),
            nonsettling(target.copy(acceptedCode = 123u), SnapshotList.of(old)).reasons,
        )
    }

    @Test
    fun distinctActualDoesNotMakeSharedPredictionSafeButUniqueActualHasNoKnownCollision() {
        val old = wire(6, 7, 123u, 123u)
        val accepted = target.copy(acceptedCode = 456u)
        assertEquals(
            setOf(
                DirectAckNonsettlingReason.UNREGISTERED_CODE,
                DirectAckNonsettlingReason.KNOWN_CROSS_CLAIM_CODE,
            ),
            nonsettling(accepted, SnapshotList.of(old)).reasons,
        )
        assertSame(
            DirectAckAmbiguityDecision.NoKnownAmbiguity,
            assess(accepted, 456u, SnapshotList.of(old)),
        )
    }

    @Test
    fun acceptedCrossClaimActualSupersedesItsPredictionForKnownCodeOverlap() {
        val old = wire(6, 7, predicted = 123u, accepted = 456u)
        assertSame(
            DirectAckAmbiguityDecision.NoKnownAmbiguity,
            assess(target.copy(acceptedCode = 123u), 123u, SnapshotList.of(old)),
        )
        assertEquals(
            setOf(DirectAckNonsettlingReason.KNOWN_CROSS_CLAIM_CODE),
            assertIs<DirectAckAmbiguityDecision.Nonsettling>(
                assess(target.copy(acceptedCode = 456u), 456u, SnapshotList.of(old)),
            ).reasons,
        )
    }

    @Test
    fun unknownPriorActualIsWildcardEvenWhenPredictionsDiffer() {
        val old = wire(6, 7, UInt.MAX_VALUE)
        assertEquals(
            setOf(
                DirectAckNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED,
                DirectAckNonsettlingReason.UNRESOLVED_PRIOR_EMISSION,
            ),
            nonsettling(target, SnapshotList.of(old)).reasons,
        )
    }

    @Test
    fun legacyUnknownIsNotDefiniteNonEmissionAndReasonsAreNotLost() {
        val old = wire(6, 7, 123u).copy(emission = DirectAckEmissionEvidence.LEGACY_UNKNOWN)
        assertEquals(
            setOf(
                DirectAckNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED,
                DirectAckNonsettlingReason.UNRESOLVED_PRIOR_EMISSION,
                DirectAckNonsettlingReason.KNOWN_CROSS_CLAIM_CODE,
            ),
            nonsettling(target, SnapshotList.of(old)).reasons,
        )
        assertEquals(
            setOf(DirectAckNonsettlingReason.UNKNOWN_TARGET_EMISSION),
            nonsettling(target.copy(emission = DirectAckEmissionEvidence.LEGACY_UNKNOWN)).reasons,
        )
    }

    @Test
    fun affirmativePreparedCompetitorAndOtherRadioDoNotIntroduceAmbiguity() {
        val prepared = wire(6, 7, 123u).copy(emission = DirectAckEmissionEvidence.PREPARED)
        val otherRadio = wire(8, 9, 123u).copy(radioId = RadioId(UUID(0, 10)))
        assertSame(
            DirectAckAmbiguityDecision.NoKnownAmbiguity,
            assess(target.copy(acceptedCode = 123u), history = SnapshotList.of(prepared, otherRadio)),
        )
    }

    @Test
    fun unrelatedCodeCannotSettleEvenWithoutCompetingHistory() {
        assertEquals(
            setOf(DirectAckNonsettlingReason.UNREGISTERED_CODE),
            assertIs<DirectAckAmbiguityDecision.Nonsettling>(assess(target, UInt.MAX_VALUE)).reasons,
        )
    }

    private fun wire(claim: Long, request: Long, predicted: UInt, accepted: UInt? = null) =
        DirectAckAmbiguityWire(
            radio, UUID(0, claim), UUID(0, request), predicted, accepted,
            DirectAckEmissionEvidence.POTENTIAL_EMISSION,
        )

    private fun assess(
        wire: DirectAckAmbiguityWire,
        code: UInt = 123u,
        history: SnapshotList<DirectAckAmbiguityWire> = SnapshotList.empty(),
    ) = DirectAckAmbiguityCandidate.assess(wire, code, history)

    private fun nonsettling(
        wire: DirectAckAmbiguityWire,
        history: SnapshotList<DirectAckAmbiguityWire> = SnapshotList.empty(),
    ) = assertIs<DirectAckAmbiguityDecision.Nonsettling>(assess(wire, history = history))
}
