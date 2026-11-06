// AndroidOnly: WP-201 Creation value-shape controls, not issuer, schema or transaction proof.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class OriginalChannelCreationAuthorityTest {
    // Test markers confer no production authority.
    private class Proof : OriginalChannelSelectionProof
    private class Receipt : OriginalChannelCreationReceipt
    private val radio = RadioId(UUID(0, 1))
    private val session = SessionToken(ProcessEpoch(UUID(0, 2)), Generation(3), radio)
    private val proof = Proof()
    private val intent = OriginalChannelCreationIntent(
        UUID(0, 4), UUID(0, 5), proof, session, "original text", TextType.PLAIN,
        UInt.MAX_VALUE, Instant.ofEpochSecond(42, 123456789),
    )

    @Test
    fun originalIntentRetainsStableIdentityTimestampAndNanoseconds() {
        assertEquals(UUID(0, 4), intent.requestId)
        assertEquals(UUID(0, 5), intent.messageId)
        assertEquals(UInt.MAX_VALUE, intent.timestamp)
        assertEquals(123456789, intent.createdAt.nano)
        assertEquals("original text", intent.text)
        assertSame(proof, intent.selection)
        assertSame(session, intent.sessionToken)
    }

    @Test
    fun selectionRetainsActualSnapshotAndExactProofSessionPair() {
        val channel = ChannelDTO(radioId = radio, index = 1u, name = "selected")
        val selected = SelectedOriginalChannel(proof, channel, session)
        assertSame(channel, selected.channel)
        assertSame(proof, selected.proof)
        assertSame(session, selected.sessionToken)
    }

    @Test
    fun createdRetainsActualReceiptCanonicalMessageAndSession() {
        val receipt = Receipt()
        val message = MessageDTO(
            id = intent.messageId, radioId = radio, channelIndex = 1u,
            text = intent.text, timestamp = intent.timestamp, createdAt = intent.createdAt,
        )
        val created = CreatedOriginalChannel(receipt, message, session)
        assertSame(receipt, created.receipt)
        assertSame(message, created.message)
        assertSame(session, created.sessionToken)
    }

    @Test
    fun equalPublicSelectionSnapshotsDoNotConferEqualOpaqueAuthority() {
        assertNotSame(proof, Proof())
        assertNotSame(Receipt(), Receipt())
    }

    @Test
    fun duplicateResultCarriesOnlyObservationOfRecordedIdentity() {
        val key = EntityKey(radio, intent.messageId)
        val evidence = OriginalChannelCreationObservation.RecordedCurrent(intent.requestId, key)
        val duplicate = OriginalChannelCreationResult.AlreadyRecorded(evidence)
        assertSame(evidence, duplicate.observation)
        assertEquals(key, evidence.key)
        assertEquals(intent.requestId, evidence.requestId)
    }

    @Test
    fun unavailableAndConflictRemainDistinctFromNotRecordedAndFreshCreation() {
        val key = EntityKey(radio, intent.messageId)
        val unavailable = OriginalChannelCreationObservation.RecordedUnavailable(intent.requestId, key)
        assertEquals(key, unavailable.key)
        assertNotSame(OriginalChannelCreationObservation.NotRecorded, unavailable)
        assertNotSame(OriginalChannelCreationObservation.Conflict, unavailable)
        assertEquals(
            OriginalChannelCreationRejection.REQUEST_CONFLICT,
            OriginalChannelCreationResult.Rejected(
                OriginalChannelCreationRejection.REQUEST_CONFLICT,
            ).reason,
        )
    }
}
