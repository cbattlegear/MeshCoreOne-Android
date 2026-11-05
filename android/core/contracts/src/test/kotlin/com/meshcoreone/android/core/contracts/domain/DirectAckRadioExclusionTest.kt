// AndroidOnly: WP-201 Radio-wide ACK exclusion evidence conformance controls.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DirectAckRadioExclusionTest {
    private val radio = RadioId(UUID(0, 1))
    private val targetClaim = UUID(0, 2)

    @Test
    fun preauthorityUncertaintyIsIndependentOfCompleteTruncatedAndSaturatedSamples() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        for (coverage in DirectAckRadioEvidenceCoverage.entries) {
            val baseline = reasons(123u, target, coverage = coverage)
            assertEquals(
                baseline + DirectAckRadioNonsettlingReason.PREAUTHORITY_HISTORY_UNKNOWN,
                reasons(123u, target, coverage = coverage, preauthorityHistoryUnknown = true),
            )
        }
    }

    @Test
    fun preauthorityUncertaintyDoesNotRequireAnySurvivingWireOrSyntheticSaturation() {
        val snapshot = DirectAckRadioEvidenceSnapshot(
            radio, SnapshotList.empty(), DirectAckRadioEvidenceCoverage.COMPLETE, true,
        )
        assertEquals(0, snapshot.wires.size)
        assertEquals(DirectAckRadioEvidenceCoverage.COMPLETE, snapshot.coverage)
        assertEquals(
            setOf(
                DirectAckRadioNonsettlingReason.PREAUTHORITY_HISTORY_UNKNOWN,
                DirectAckRadioNonsettlingReason.TARGET_CODE_UNREGISTERED,
            ),
            DirectAckRadioEvidenceAssessment.nonsettlingReasons(targetClaim, 123u, snapshot),
        )
    }

    @Test
    fun preauthorityWildcardAndNonfailureReasonsAreNotSaturation() {
        val warning = DirectAckRadioConfirmationEvidence.PersistedWildcard(
            radio,
            SnapshotSet(listOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN)),
        )
        val failure = DirectAckRadioFailureResult.Nonfailing(
            SnapshotSet(listOf(DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN)),
        )

        assertEquals(
            setOf(DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN),
            warning.reasons,
        )
        assertEquals(
            setOf(DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN),
            failure.reasons,
        )
        assertNotEquals(
            warning,
            warning.copy(reasons = SnapshotSet(listOf(
                DirectAckRadioConfirmationWildcardReason.STICKY_SATURATION,
            ))),
        )
    }

    @Test
    fun nonfailingRequiresNonemptyReasonsIncludingOnCopy() {
        assertFailsWith<IllegalArgumentException> {
            DirectAckRadioFailureResult.Nonfailing(SnapshotSet.empty())
        }
        val result = DirectAckRadioFailureResult.Nonfailing(
            SnapshotSet(listOf(DirectAckRadioNonfailingReason.TARGET_UNRESOLVED_PHYSICAL_EMISSION)),
        )
        assertFailsWith<IllegalArgumentException> {
            result.copy(reasons = SnapshotSet.empty())
        }
    }

    @Test
    fun nonfailingRetainsEveryTypedReasonWithoutMutableAliases() {
        val reasons = DirectAckRadioNonfailingReason.entries.toMutableList()
        val result = DirectAckRadioFailureResult.Nonfailing(SnapshotSet(reasons))
        reasons.clear()

        assertEquals(DirectAckRadioNonfailingReason.entries.toSet(), result.reasons)
        assertEquals(9, result.reasons.size)
    }

    @Test
    fun targetAndForeignUncertaintyAreDistinctFromRejectedAuthority() {
        val target = DirectAckRadioFailureResult.Nonfailing(
            SnapshotSet(listOf(DirectAckRadioNonfailingReason.TARGET_UNRESOLVED_PHYSICAL_EMISSION)),
        )
        val foreign = DirectAckRadioFailureResult.Nonfailing(
            SnapshotSet(listOf(DirectAckRadioNonfailingReason.UNRESOLVED_OTHER_CLAIM_EMISSION)),
        )

        assertNotEquals(target, foreign)
        for (reason in DirectAckRejection.entries) {
            val rejected = DirectAckRadioFailureResult.Rejected(reason)
            assertEquals(reason, rejected.reason)
            assertNotEquals<DirectAckRadioFailureResult>(target, rejected)
            assertNotEquals<DirectAckRadioFailureResult>(foreign, rejected)
        }
    }

    @Test
    fun confirmationObservationRetainsRadioAndDistinguishesReadFromSnapshot() {
        val inspecting = DirectAckRadioConfirmationObservation.Inspecting(radio)
        val evidence = DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radio)
        val snapshot = DirectAckRadioConfirmationObservation.Snapshot(evidence)

        assertEquals(radio, inspecting.radioId)
        assertEquals(evidence.radioId, snapshot.radioId)
        assertEquals(evidence, snapshot.evidence)
        assertNotEquals<DirectAckRadioConfirmationObservation>(inspecting, snapshot)
    }

    @Test
    fun confirmationObservationDerivesRadioFromEvidenceIncludingCopiedSnapshot() {
        val snapshot = DirectAckRadioConfirmationObservation.Snapshot(
            DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radio),
        )
        val otherRadio = RadioId(UUID(0, 20))
        val evidence = DirectAckRadioConfirmationEvidence.PersistedWildcard(
            otherRadio,
            SnapshotSet(listOf(DirectAckRadioConfirmationWildcardReason.UNTRACKED_LEGACY_ANCESTRY)),
        )
        val copied = snapshot.copy(evidence = evidence)

        assertEquals(radio, snapshot.radioId)
        assertEquals(otherRadio, copied.radioId)
        assertEquals(evidence, copied.evidence)
        assertNotEquals(snapshot, copied)
    }

    @Test
    fun confirmationEvidenceAlwaysIdentifiesItsExactRadio() {
        val otherRadio = RadioId(UUID(0, 20))
        val evidence = DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radio)

        assertEquals(radio, evidence.radioId)
        assertNotEquals(
            evidence,
            DirectAckRadioConfirmationEvidence.NoPersistedWildcard(otherRadio),
        )
    }

    @Test
    fun confirmationWildcardReasonsRemainTypedDistinctAndRadioScoped() {
        for (reason in DirectAckRadioConfirmationWildcardReason.entries) {
            val evidence = DirectAckRadioConfirmationEvidence.PersistedWildcard(
                radio,
                SnapshotSet(listOf(reason)),
            )
            assertEquals(radio, evidence.radioId)
            assertEquals(setOf(reason), evidence.reasons)
            assertNotEquals<DirectAckRadioConfirmationEvidence>(
                DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radio),
                evidence,
            )
        }
    }

    @Test
    fun confirmationWildcardSnapshotRetainsAllReasonsWithoutMutableAliases() {
        val reasons = DirectAckRadioConfirmationWildcardReason.entries.toMutableList()
        val evidence = DirectAckRadioConfirmationEvidence.PersistedWildcard(radio, SnapshotSet(reasons))
        reasons.clear()

        assertEquals(DirectAckRadioConfirmationWildcardReason.entries.toSet(), evidence.reasons)
        assertEquals(5, evidence.reasons.size)
    }

    @Test
    fun confirmationWildcardRequiresNonemptyReasonsIncludingOnCopy() {
        assertFailsWith<IllegalArgumentException> {
            DirectAckRadioConfirmationEvidence.PersistedWildcard(radio, SnapshotSet.empty())
        }
        val evidence = DirectAckRadioConfirmationEvidence.PersistedWildcard(
            radio,
            SnapshotSet(listOf(DirectAckRadioConfirmationWildcardReason.STICKY_SATURATION)),
        )
        assertFailsWith<IllegalArgumentException> {
            evidence.copy(reasons = SnapshotSet.empty())
        }
    }

    @Test
    fun olderResolutionReplayReceiptCanCarryNewerResolutionMetadata() {
        val emission = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u), emission = 8)
        val firstResolutionId = UUID(0, 9)
        val latestResolutionId = UUID(0, 10)
        val latest = DirectAckEmissionReceipt(UUID(0, 8), latestResolutionId, emission)
        val olderReplay = DirectAckEmissionMutationResult.AlreadyApplied(latest)

        assertEquals(UUID(0, 8), olderReplay.receipt.emissionRequestId)
        assertEquals(latestResolutionId, olderReplay.receipt.resolutionRequestId)
        assertNotEquals(firstResolutionId, olderReplay.receipt.resolutionRequestId)
        assertEquals(DirectAckRadioWireOutcome.Accepted(123u), olderReplay.receipt.evidence.outcome)
    }

    @Test
    fun resolutionIdentityConflictReceiptRetainsFirstAcceptedCodeAndUnsentFact() {
        val conflict = wire(
            2,
            3,
            123u,
            DirectAckRadioWireOutcome.Conflict(123u, definitelyUnsentObserved = true),
            emission = 8,
        )
        val receipt = DirectAckEmissionReceipt(UUID(0, 8), UUID(0, 10), conflict)
        val result = DirectAckEmissionMutationResult.Conflict(receipt)

        assertEquals(receipt, result.receipt)
        assertEquals(
            DirectAckRadioWireOutcome.Conflict(123u, definitelyUnsentObserved = true),
            result.receipt.evidence.outcome,
        )
        assertNotEquals<DirectAckEmissionMutationResult>(
            DirectAckEmissionMutationResult.AlreadyApplied(receipt),
            result,
        )
    }

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
            reasons(
                456u,
                target.copy(outcome = DirectAckRadioWireOutcome.Accepted(456u)),
                firstForeignEmission,
                secondForeignEmission,
            ),
        )
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE),
            reasons(
                987u,
                target.copy(outcome = DirectAckRadioWireOutcome.Accepted(987u)),
                firstForeignEmission,
                secondForeignEmission,
            ),
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
        assertEquals(
            setOf(
                DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION,
            ),
            reasons(789u, target.copy(outcome = DirectAckRadioWireOutcome.Accepted(789u)), foreign),
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
        assertEquals(
            setOf(
                DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION,
            ),
            reasons(789u, target.copy(outcome = DirectAckRadioWireOutcome.Accepted(789u)), legacy),
        )
        assertEquals(null, legacy.emissionRequestId)
    }

    @Test
    fun legacyAcceptedCodeRemainsWildcardAndKnownCompetitor() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val legacy = wire(5, 6, 789u, DirectAckRadioWireOutcome.LegacyAcceptedUnknown(456u))

        assertEquals(
            setOf(
                DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION,
            ),
            reasons(456u, target.copy(outcome = DirectAckRadioWireOutcome.Accepted(456u)), legacy),
        )
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION),
            reasons(123u, target, legacy),
        )
        assertEquals(null, legacy.emissionRequestId)
        assertEquals(
            setOf(DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED),
            reasons(
                456u,
                wire(2, 3, 789u, DirectAckRadioWireOutcome.LegacyAcceptedUnknown(456u)),
            ),
        )
    }

    @Test
    fun laterPhysicalMarkerDoesNotEraseLegacyWireAncestry() {
        val target = wire(2, 3, 123u, DirectAckRadioWireOutcome.Accepted(123u))
        val legacy = wire(5, 6, 789u, DirectAckRadioWireOutcome.LegacyAcceptedUnknown(456u))
        val laterEmission = wire(5, 6, 789u, DirectAckRadioWireOutcome.Accepted(987u), emission = 8)

        assertEquals(
            setOf(
                DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION,
            ),
            reasons(
                456u,
                target.copy(outcome = DirectAckRadioWireOutcome.Accepted(456u)),
                legacy,
                laterEmission,
            ),
        )
        assertEquals(
            setOf(
                DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE,
                DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION,
            ),
            reasons(
                987u,
                target.copy(outcome = DirectAckRadioWireOutcome.Accepted(987u)),
                legacy,
                laterEmission,
            ),
        )
        assertEquals(legacy.wireRequestId, laterEmission.wireRequestId)
        assertEquals(null, legacy.emissionRequestId)
        assertNotEquals(null, laterEmission.emissionRequestId)
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
        preauthorityHistoryUnknown: Boolean = false,
    ) = DirectAckRadioEvidenceAssessment.nonsettlingReasons(
        targetClaim,
        ackCode,
        DirectAckRadioEvidenceSnapshot(
            radio, SnapshotList.of(*wires), coverage, preauthorityHistoryUnknown,
        ),
    )

    private fun wire(
        claim: Long,
        request: Long,
        predictedCode: UInt,
        outcome: DirectAckRadioWireOutcome,
        emission: Long? = if (
            outcome == DirectAckRadioWireOutcome.LegacyUnknown ||
            outcome is DirectAckRadioWireOutcome.LegacyAcceptedUnknown
        ) null else request + 1_000,
    ) = DirectAckRadioWireEvidence(
        radio,
        UUID(0, claim),
        UUID(0, request),
        emission?.let { UUID(0, it) },
        predictedCode,
        outcome,
    )
}
