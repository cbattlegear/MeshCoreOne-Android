// AndroidOnly: WP-201 Reaction target value controls, not issuance, transaction or cache proof.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReactionTargetAuthorityTest {
    // Test markers carry no production issuer or storage authority.
    private class Target : ChannelReactionTarget

    private val radio = RadioId(UUID(0, 1))
    private val message = MessageDTO(
        id = UUID(0, 2), radioId = radio, channelIndex = 3u,
        text = "original", timestamp = 42u, createdAt = Instant.ofEpochSecond(50),
        senderTimestamp = 7u,
    )
    private val initial = CapturedChannelReactionTarget(Target(), message)

    @Test
    fun captureCarriesTheActualSnapshotAlongsideAnOpaqueTarget() {
        assertSame(message, initial.message)
        assertEquals(42u, initial.message.timestamp)
        assertEquals(7u, initial.message.reactionTimestamp)
    }

    @Test
    fun equalPublicSnapshotsDoNotMakeDistinctTargetsTheSameAuthority() {
        val other = CapturedChannelReactionTarget(Target(), message.copy())

        assertEquals(initial.message, other.message)
        assertNotSame(initial.target, other.target)
    }

    @Test
    fun appliedExposesTheExplicitPostWriteSuccessorAndSummary() {
        val successor = CapturedChannelReactionTarget(Target(), message.copy(reactionSummary = "x:1"))
        val result = ChannelReactionApplyResult.Applied(successor, "x:1", true)

        assertSame(successor, result.successor)
        assertNotSame(initial.target, result.successor.target)
        assertEquals(result.summary, result.successor.message.reactionSummary)
        assertTrue(result.inserted)
    }

    @Test
    fun duplicateIsNotANewInsertionButStillHasAnExplicitSuccessor() {
        val successor = CapturedChannelReactionTarget(Target(), message.copy(reactionSummary = "x:1"))
        val result = ChannelReactionApplyResult.Applied(successor, "x:1", false)

        assertFalse(result.inserted)
        assertSame(successor.target, result.successor.target)
        assertEquals("x:1", result.summary)
    }

    @Test
    fun actualSnapshotPreservesItsOwnRadioAndChannel() {
        val otherRadio = RadioId(UUID(0, 9))
        val other = CapturedChannelReactionTarget(Target(), message.copy(radioId = otherRadio, channelIndex = 4u))

        assertEquals(otherRadio, other.message.radioId)
        assertEquals(4.toUByte(), other.message.channelIndex)
        assertNotSame(initial.target, other.target)
    }

    @Test
    fun staleAndRejectedAreDistinctExplicitOutcomes() {
        val mutation: ChannelReactionApplyResult = ChannelReactionApplyResult.Stale
        val rejected = ChannelReactionApplyResult.Rejected(ChannelReactionRejection.INVALID_HANDLE)

        assertSame(ChannelReactionApplyResult.Stale, mutation)
        assertEquals(ChannelReactionRejection.INVALID_HANDLE, rejected.reason)
    }

    @Test
    fun reactionRetryCanRetainExactOriginalArrivalAndIdentity() {
        val reaction = ReactionDTO(
            id = UUID(0, 4), messageID = message.id, emoji = "x", senderName = "sender",
            messageHash = "hash", rawText = "original-wire", receivedAt = Instant.ofEpochSecond(2, 3),
            channelIndex = message.channelIndex, radioId = radio,
        )
        val retained = reaction.copy()

        assertEquals(reaction, retained)
        assertEquals(Instant.ofEpochSecond(2, 3), retained.receivedAt)
        assertEquals(UUID(0, 4), retained.id)
        assertEquals("original-wire", retained.rawText)
    }

    @Test
    fun emptySummaryIsNotConfusedWithAnUnavailableResult() {
        val successor = CapturedChannelReactionTarget(Target(), message.copy(reactionSummary = ""))
        val result = ChannelReactionApplyResult.Applied(successor, "", false)

        assertEquals("", result.summary)
        assertEquals("", result.successor.message.reactionSummary)
        assertFalse(result.inserted)
    }

    @Test
    fun ingressDistinguishesOriginalInsertionFromExistingKeyWithoutAuthority() {
        val inserted = ChannelReactionIngressResult.Inserted(initial)
        val existing = ChannelReactionIngressResult.Existing(EntityKey(radio, message.id))

        assertSame(initial, inserted.captured)
        assertEquals(EntityKey(radio, message.id), existing.key)
    }

    @Test
    fun anchoredWindowCanCarryTheFullLoadedRangeAndHasMore() {
        val rows = List(201) { index ->
            CapturedChannelReactionTarget(Target(), message.copy(id = UUID(1, index.toLong())))
        }.snapshot()
        val window = ChannelReactionWindow(rows, true)

        assertSame(rows, window.messages)
        assertEquals(201, window.messages.size)
        assertEquals(UUID(1, 0), window.messages.first().message.id)
        assertEquals(UUID(1, 200), window.messages.last().message.id)
        assertTrue(window.hasMore)
    }
}
