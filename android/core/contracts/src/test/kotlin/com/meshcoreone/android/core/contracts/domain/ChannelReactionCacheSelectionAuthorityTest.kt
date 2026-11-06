// AndroidOnly: WP-201 Cached selection value-shape controls; no fake authenticated occurrence or issuer.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class ChannelReactionCacheSelectionAuthorityTest {
    private val wire = ChannelMessage(3u, 1u, 0u, Instant.ofEpochSecond(42), "reaction", null)
    private val envelope = ChannelReceiveEnvelope(
        UUID(0, 1),
        SessionToken(ProcessEpoch(UUID(0, 2)), Generation(3), RadioId(UUID(0, 4))),
        wire,
        DeliveryContext.Live,
        ChannelReceiveOrigin.Unproved,
    )
    private val occurrence = ChannelReactionOccurrence.Unproven(
        envelope, ChannelReactionUnprovenReason.UNORDERED_INGRESS,
    )
    private val parsed = ParsedReaction("x", "sender", "hash")
    private fun candidate(order: Long, date: Long = 1) = ChannelReactionCacheCandidate.Unbound(
        UUID(0, order), "original", 42u, Instant.ofEpochSecond(date), order,
    )
    private fun request(vararg candidates: ChannelReactionCacheCandidate) =
        ChannelReactionCacheSelectionRequest(occurrence, parsed, candidates.toList().snapshot())

    @Test
    fun snapshotRetainsExactOriginalOccurrenceAndPerMemberOrder() {
        val first = candidate(10)
        val second = candidate(20)
        val request = request(first, second)
        assertSame(occurrence, request.occurrence)
        assertSame(envelope, request.occurrence.envelope)
        assertSame(wire, request.occurrence.envelope.message)
        assertSame(parsed, request.parsedReaction)
        assertSame(first, request.candidates[0])
        assertSame(second, request.candidates[1])
        assertEquals(20L, request.candidates[1].indexOrder)
    }

    @Test
    fun duplicateAndReorderedIndexOrderAreNotNormalizedOrSubstituted() {
        assertFailsWith<IllegalArgumentException> { request(candidate(2), candidate(1)) }
        assertFailsWith<IllegalArgumentException> { request(candidate(1), candidate(1)) }
    }

    @Test
    fun unboundMemberCannotBecomeASelectedCapture() {
        val request = request(candidate(10))
        assertFailsWith<IllegalArgumentException> { ChannelReactionCacheSelectionResult.Selected(request, 0) }
        assertFailsWith<IllegalArgumentException> { ChannelReactionCacheSelectionResult.Selected(request, 10) }
    }

    @Test
    fun blockedPreservesWholeSnapshotAndOriginalOrdinalRatherThanIndexOrder() {
        val request = request(candidate(10), candidate(20))
        val issue = ChannelReactionCacheSelectionIssue(1, ChannelReactionCacheSelectionIssueReason.Unbound)
        val result = ChannelReactionCacheSelectionResult.Blocked(request, listOf(issue).snapshot())
        assertSame(request, result.request)
        assertSame(issue, result.issues.single())
        assertSame(request.candidates[1], result.request.candidates[result.issues.single().ordinal!!])
        assertEquals(20L, result.request.candidates[1].indexOrder)
    }

    @Test
    fun globalUnprovedOutcomeRetainsEvenEmptyOriginalSnapshot() {
        val request = request()
        val result = ChannelReactionCacheSelectionResult.Blocked(
            request,
            listOf(ChannelReactionCacheSelectionIssue(
                null,
                ChannelReactionCacheSelectionIssueReason.Unproven(ChannelReactionUnprovenReason.INVALID_PROOF),
            )).snapshot(),
        )
        assertSame(request, result.request)
        assertEquals(
            ChannelReactionUnprovenReason.INVALID_PROOF,
            assertIs<ChannelReactionCacheSelectionIssueReason.Unproven>(result.issues.single().reason).reason,
        )
    }

    @Test
    fun malformedBlockedIssueSetsAreRejected() {
        val request = request(candidate(10), candidate(20))
        fun issue(ordinal: Int) = ChannelReactionCacheSelectionIssue(
            ordinal, ChannelReactionCacheSelectionIssueReason.Stale,
        )
        assertFailsWith<IllegalArgumentException> {
            ChannelReactionCacheSelectionResult.Blocked(request, emptyList<ChannelReactionCacheSelectionIssue>().snapshot())
        }
        for (issues in listOf(listOf(issue(2)), listOf(issue(1), issue(0)), listOf(issue(0), issue(0)))) {
            assertFailsWith<IllegalArgumentException> {
                ChannelReactionCacheSelectionResult.Blocked(request, issues.snapshot())
            }
        }
    }
}
