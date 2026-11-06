// AndroidOnly: WP-201 Row/harvest shape controls, not an accepted row receipt, RX issuer or Room transaction.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame

class MessageHarvestAuthorityTest {
    // This marker is never submitted to a production authority or treated as an authentic receipt.
    private class Receipt : SavedMessageRowReceipt
    private val radio = RadioId(UUID(0, 1))
    private val wire = ChannelMessage(3u, 2u, 0u, Instant.ofEpochSecond(42), "sender: body", 4.5)
    private val occurrence = ChannelReactionOccurrence.Unproven(
        ChannelReceiveEnvelope(
            UUID(0, 2), SessionToken(ProcessEpoch(UUID(0, 3)), Generation(4), radio),
            wire, DeliveryContext.Live, ChannelReceiveOrigin.Unproved,
        ),
        ChannelReactionUnprovenReason.UNORDERED_INGRESS,
    )
    private val message = MessageDTO(
        id = UUID(0, 5), radioId = radio, channelIndex = 3u, text = "body", timestamp = 42u,
        createdAt = Instant.EPOCH, direction = MessageDirection.INCOMING, pathLength = 2u, snr = 4.5,
    )
    private val row = SavedMessageRow(message, Receipt())

    private fun candidate(id: Long, time: Long) = OriginalRxCandidate.Unproven(
        RxLogEntryDTO(
            id = UUID(0, id), radioId = radio, receivedAt = Instant.ofEpochSecond(time),
            snr = 4.5, rssi = -90, routeType = RouteType.FLOOD, payloadType = PayloadType.GROUP_TEXT,
            payloadVersion = 0u, transportCode = null, pathLength = 0u, pathNodes = Bytes(byteArrayOf()),
            packetPayload = Bytes(byteArrayOf()), rawPayload = Bytes(byteArrayOf()), packetHash = "untrusted",
            channelIndex = 3u, senderTimestamp = 42u, payloadTypeBits = 5u, decodedText = "sender: body",
        ),
        ChannelReactionUnprovenReason.MISSING_ORIGIN,
    )

    @Test
    fun saveRetainsExactOccurrenceAndIndependentWireFields() {
        val request = ChannelMessageRowSaveRequest(message, occurrence)
        assertSame(message, request.message)
        assertSame(occurrence, request.occurrence)
        assertSame(wire, request.occurrence.envelope.message)
        assertEquals(2.toUByte(), request.message.pathLength)
        assertEquals(4.5, request.message.snr)
    }

    @Test
    fun preSaveRxEnrichmentIsRejectedRatherThanCertifiedOrSilentlyStripped() {
        for (enriched in listOf(
            message.copy(pathNodes = Bytes(byteArrayOf())),
            message.copy(routeType = RouteType.FLOOD),
            message.copy(regionScope = "region"),
            message.copy(regionScopeMatches = listOf("region").snapshot()),
            message.copy(pathLength = 0u),
            message.copy(snr = null),
        )) {
            assertFailsWith<IllegalArgumentException> { ChannelMessageRowSaveRequest(enriched, occurrence) }
        }
    }

    @Test
    fun committedUnprovenAndExistingKeepStorageLineageWithoutReactionAuthority() {
        val inserted = ChannelReactionIngressResult.InsertedUnproven(
            message, ChannelReactionUnprovenReason.UNORDERED_INGRESS,
        )
        val result = RowBoundChannelIngress(inserted, row)
        assertSame(inserted, result.original)
        assertSame(row, result.savedRow)
        assertNull(result.savedRow!!.reactionCapture)
        val existing = ChannelReactionIngressResult.Existing(EntityKey(radio, message.id))
        assertSame(row, RowBoundChannelIngress(existing, row).savedRow)
        assertFailsWith<IllegalArgumentException> { RowBoundChannelIngress(existing, null) }
        assertFailsWith<IllegalArgumentException> {
            RowBoundChannelIngress(ChannelReactionIngressResult.Rejected(ChannelReactionRejection.NOT_ELIGIBLE), row)
        }
    }

    @Test
    fun stableEarlierRxCandidatesRetainTheirOwnExactEntriesWithoutProof() {
        val first = candidate(10, -5)
        val second = candidate(11, -5)
        val request = IncomingPathHarvestRequest(row, listOf(first, second).snapshot())
        assertSame(first, request.candidates[0])
        assertSame(second.entry, request.candidates[1].entry)
        assertEquals(Instant.ofEpochSecond(-5), request.candidates[0].entry.receivedAt)
        assertFailsWith<IllegalArgumentException> {
            IncomingPathHarvestRequest(row, listOf(candidate(12, 1), candidate(13, 0)).snapshot())
        }
    }

    @Test
    fun unprovedBlockedBatchRetainsRowReceiptAndAllOriginalCandidates() {
        val candidate = candidate(10, 0)
        val request = IncomingPathHarvestRequest(row, listOf(candidate).snapshot())
        val issue = IncomingPathHarvestIssue(
            0, IncomingPathHarvestIssueReason.Unproven(ChannelReactionUnprovenReason.MISSING_ORIGIN),
        )
        val blocked = IncomingPathHarvestResult.Blocked(request, listOf(issue).snapshot())
        assertSame(request, blocked.request)
        assertSame(row.receipt, blocked.request.savedRow.receipt)
        assertSame(candidate, blocked.request.candidates.single())
        assertSame(issue, blocked.issues.single())
    }

    @Test
    fun blockedIssueBoundsAndNonemptyRequirementAreChecked() {
        val request = IncomingPathHarvestRequest(row, emptyList<OriginalRxCandidate>().snapshot())
        assertFailsWith<IllegalArgumentException> {
            IncomingPathHarvestResult.Blocked(request, emptyList<IncomingPathHarvestIssue>().snapshot())
        }
        assertFailsWith<IllegalArgumentException> {
            IncomingPathHarvestResult.Blocked(
                request, listOf(IncomingPathHarvestIssue(0, IncomingPathHarvestIssueReason.Stale)).snapshot(),
            )
        }
        val blocked = IncomingPathHarvestResult.Blocked(
            request, listOf(IncomingPathHarvestIssue(null, IncomingPathHarvestIssueReason.Unresolved)).snapshot(),
        )
        assertSame(request, blocked.request)
    }
}
