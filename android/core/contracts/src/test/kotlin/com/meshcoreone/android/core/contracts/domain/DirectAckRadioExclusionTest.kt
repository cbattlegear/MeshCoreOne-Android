// AndroidOnly: WP-201 Radio-wide ACK exclusion evidence conformance controls.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DirectAckRadioExclusionTest {
    private val radio = RadioId(UUID(0, 1))
    private val targetClaim = UUID(0, 2)

    @Test
    fun targetPredictionDoesNotSettleUntilItsActualCodeIsAccepted() {
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED),
            reasons(
                ackCode = 123u,
                wire(2, 3, 123u, DirectAckRadioWireOutcome.MayHaveWritten),
            ),
        )
    }

    @Test
    fun acceptedActualSupersedesOnlyItsOwnTargetPrediction() {
        val accepted = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(456u))
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.TARGET_CODE_UNREGISTERED),
            reasons(123u, accepted),
        )
        assertEquals(emptySet(), reasons(456u, accepted))
    }

    @Test
    fun earlierAcceptedSameClaimWireRemainsEligibleWithUnsentRetry() {
        val earlier = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val retry = wire(2, 4, 789u, DirectAckRadioWireOutcome.DefinitelyUnsent)
        assertEquals(emptySet(), reasons(123u, earlier, retry))
    }

    @Test
    fun repeatedTransportInvocationsHaveDistinctEvidenceEvenWhenWireIdIsReused() {
        val firstEmission = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u), emission = 8)
        val secondEmission = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(456u), emission = 9)

        assertEquals(emptySet(), reasons(123u, firstEmission, secondEmission))
        assertEquals(emptySet(), reasons(456u, firstEmission, secondEmission))
        assertEquals(firstEmission.wireRequestId, secondEmission.wireRequestId)
        assertNotEquals(firstEmission.emissionRequestId, secondEmission.emissionRequestId)
    }

    @Test
    fun foreignActualCodesRemainCompetitorsAcrossEmissionsReusingWireId() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val firstForeignEmission =
            wire(5, 6, 789u, DirectAckRadioWireOutcome.Accepted(456u), emission = 8)
        val secondForeignEmission =
            wire(5, 6, 789u, DirectAckRadioWireOutcome.Accepted(987u), emission = 9)

        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE),
            reasons(456u, target, firstForeignEmission, secondForeignEmission),
        )
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE),
            reasons(987u, target, firstForeignEmission, secondForeignEmission),
        )
        assertEquals(firstForeignEmission.wireRequestId, secondForeignEmission.wireRequestId)
        assertNotEquals(firstForeignEmission.emissionRequestId, secondForeignEmission.emissionRequestId)
    }

    @Test
    fun definitelyUnsentRetryDoesNotClearEarlierPossibleEmissionWithSameWireId() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val possible = wire(5, 6, 789u, DirectAckRadioWireOutcome.MayHaveWritten, emission = 8)
        val unsentRetry = wire(5, 6, 789u, DirectAckRadioWireOutcome.DefinitelyUnsent, emission = 9)

        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION),
            reasons(123u, target, possible, unsentRetry),
        )
    }

    @Test
    fun foreignAcceptedWireRetainsBothPredictedAndActualCodesAsCompetitors() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val foreign = wire(5, 6, 123u, DirectAckRadioWireOutcome.Accepted(456u))
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE),
            reasons(123u, target, foreign),
        )
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE),
            reasons(456u, wire(2, 7, 456u, DirectAckRadioWireOutcome.Accepted(456u)), foreign),
        )
    }

    @Test
    fun unresolvedForeignEmissionIsWildcardDespiteDifferentPrediction() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val foreign = wire(5, 6, 789u, DirectAckRadioWireOutcome.MayHaveWritten)
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION),
            reasons(123u, target, foreign),
        )
    }

    @Test
    fun markerlessHistoricalWireIsUnknownNotDefinitelyUnsent() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val legacy = wire(5, 6, 789u, DirectAckRadioWireOutcome.LegacyUnknown)
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION),
            reasons(123u, target, legacy),
        )
    }

    @Test
    fun contradictoryEvidenceRemainsWildcardAndRetainsKnownCodeCollision() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val conflict = wire(
            5,
            6,
            789u,
            DirectAckRadioWireOutcome.Conflict(123u, definitelyUnsentObserved = true),
        )
        assertEquals(
            setOf(
                DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION,
            ),
            reasons(123u, target, conflict),
        )
    }

    @Test
    fun saturationAndTruncatedSamplesNeverLookLikeCompleteHistory() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.RADIO_HISTORY_SATURATED),
            reasons(123u, target, coverage = DirectAckRadioEvidenceCoverage.SATURATED),
        )
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.RADIO_HISTORY_INCOMPLETE),
            reasons(123u, target, coverage = DirectAckRadioEvidenceCoverage.TRUNCATED),
        )
    }

    @Test
    fun definitelyUnsentForeignWireDoesNotCompete() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val unsent = wire(5, 6, 123u, DirectAckRadioWireOutcome.DefinitelyUnsent)
        assertEquals(emptySet(), reasons(123u, target, unsent))
    }

    @Test
    fun evidenceFromAnotherRadioDoesNotCompete() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val otherRadio = wire(5, 6, 123u, DirectAckRadioWireOutcome.MayHaveWritten)
            .copy(radioId = RadioId(UUID(0, 7)))
        assertEquals(emptySet(), reasons(123u, target, otherRadio))
    }

    private fun reasons(
        ackCode: UInt,
        vararg wires: DirectAckRadioWireEvidence,
        coverage: DirectAckRadioEvidenceCoverage = DirectAckRadioEvidenceCoverage.COMPLETE,
    ) = DirectAckRadioEvidenceAssessment.nonsettlingReasons(
        targetClaim,
        ackCode,
        DirectAckRadioEvidenceSnapshot(radio, SnapshotList.of(*wires), coverage),
    )

    private fun wire(
        claim: Long,
        request: Long,
        predictedCode: UInt,
        outcome: DirectAckRadioWireOutcome,
        emission: Long = request + 1_000,
    ) = DirectAckRadioWireEvidence(
        radio,
        UUID(0, claim),
        UUID(0, request),
        UUID(0, emission),
        predictedCode,
        outcome,
    )
}
