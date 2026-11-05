// AndroidOnly: WP-201 Message confirmation diagnostic value controls.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class DirectMessageConfirmationEvidenceTest {
    private val key = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val identity = DirectMessageConfirmationIdentity("a".repeat(64), 4)
    private val unproven = SnapshotSet(
        listOf(DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN),
    )

    @Test
    fun preauthorityUncertaintyCoexistsWithKnownCollisionAndUnprovenTarget() {
        val reasons = setOf(
            DirectMessageConfirmationUncertaintyReason.PREAUTHORITY_HISTORY_UNKNOWN,
            DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN,
            DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE,
        )
        val evidence = DirectMessageConfirmationEvidence.Present(
            key, identity, MessageStatus.DELIVERED, SnapshotSet(reasons),
        )

        assertEquals(reasons, evidence.reasons)
        assertEquals(MessageStatus.DELIVERED, evidence.status)
        assertEquals(identity, evidence.identity)
    }

    @Test
    fun missingAndIneligibleAreDistinctAndRetainExactRadioMessageKey() {
        val missing = DirectMessageConfirmationEvidence.Missing(key)
        val ineligible = DirectMessageConfirmationEvidence.NotEligible(key)

        assertEquals(key, missing.key)
        assertEquals(key, ineligible.key)
        assertNotEquals<DirectMessageConfirmationEvidence>(missing, ineligible)
        assertNotEquals(missing, missing.copy(key = key.copy(radioId = RadioId(UUID(0, 9)))))
        assertNotEquals(missing, missing.copy(key = key.copy(id = UUID(0, 9))))
    }

    @Test
    fun presentPreservesStatusWithoutTurningUncertaintyIntoFailed() {
        for (status in MessageStatus.entries) {
            val evidence = DirectMessageConfirmationEvidence.Present(key, identity, status, unproven)
            assertEquals(key, evidence.key)
            assertEquals(identity, evidence.identity)
            assertEquals(status, evidence.status)
            assertEquals(unproven, evidence.reasons)
        }
    }

    @Test
    fun identityDistinguishesReplacementAndRevisionWithoutAuthority() {
        val evidence = DirectMessageConfirmationEvidence.Present(
            key, identity, MessageStatus.FAILED, unproven,
        )
        val replaced = evidence.copy(identity = identity.copy(incarnationId = "b".repeat(64)))
        val mutated = evidence.copy(identity = identity.copy(revision = 5))

        assertNotEquals(evidence, replaced)
        assertNotEquals(evidence, mutated)
        assertEquals(evidence.key, replaced.key)
        assertEquals(evidence.key, mutated.key)
    }

    @Test
    fun knownCollisionCanCoexistWithUnprovenProvenanceAfterValueReconstruction() {
        val evidence = DirectMessageConfirmationEvidence.Present(
            key,
            identity,
            MessageStatus.FAILED,
            SnapshotSet(listOf(
                DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN,
                DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE,
            )),
        )
        val reconstructed = evidence.copy(
            identity = DirectMessageConfirmationIdentity(identity.incarnationId, identity.revision),
        )

        assertEquals(evidence, reconstructed)
        assertEquals(2, reconstructed.reasons.size)
        assertEquals(MessageStatus.FAILED, reconstructed.status)
    }

    @Test
    fun allReasonsRetainTheirValuesWithoutMutableAliases() {
        val reasons = DirectMessageConfirmationUncertaintyReason.entries.toMutableList()
        val evidence = DirectMessageConfirmationEvidence.Present(
            key, identity, MessageStatus.SENT, SnapshotSet(reasons),
        )
        reasons.clear()

        assertEquals(DirectMessageConfirmationUncertaintyReason.entries.toSet(), evidence.reasons)
        assertEquals(10, evidence.reasons.size)
    }

    @Test
    fun presentRequiresNonemptyReasonsIncludingCopy() {
        assertFailsWith<IllegalArgumentException> {
            DirectMessageConfirmationEvidence.Present(
                key, identity, MessageStatus.FAILED, SnapshotSet.empty(),
            )
        }
        val evidence = DirectMessageConfirmationEvidence.Present(
            key, identity, MessageStatus.FAILED, unproven,
        )
        assertFailsWith<IllegalArgumentException> {
            evidence.copy(reasons = SnapshotSet.empty())
        }
    }

    @Test
    fun identityPreservesFullLiteralWithoutUuidConversionOrNormalization() {
        val literal = "0123456789abcdef".repeat(4)
        val upper = literal.uppercase()

        assertEquals(literal, DirectMessageConfirmationIdentity(literal, 0).incarnationId)
        assertEquals(upper, DirectMessageConfirmationIdentity(upper, Long.MAX_VALUE).incarnationId)
        assertNotEquals(
            DirectMessageConfirmationIdentity(literal, 0),
            DirectMessageConfirmationIdentity(upper, 0),
        )
    }

    @Test
    fun identityRejectsTruncationNonhexAndNegativeRevisionIncludingCopy() {
        for (invalid in listOf("", "a".repeat(63), "a".repeat(65), "g".repeat(64), UUID(0, 3).toString())) {
            assertFailsWith<IllegalArgumentException> {
                DirectMessageConfirmationIdentity(invalid, 0)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            identity.copy(incarnationId = "a".repeat(32))
        }
        assertFailsWith<IllegalArgumentException> {
            identity.copy(revision = -1)
        }
    }
}
