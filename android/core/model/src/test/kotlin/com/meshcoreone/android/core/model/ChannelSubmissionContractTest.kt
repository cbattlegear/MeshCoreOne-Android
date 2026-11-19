// AndroidOnly: WP-201 Caller-stable input identity and origin consistency without persistence or dispatch claims.
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.ChannelMessageCreation
import com.meshcoreone.android.core.contracts.domain.ChannelSubmission
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import org.junit.jupiter.api.Test

class ChannelSubmissionContractTest {
    private val radio = RadioId(UUID(0, 1))
    private val origin = ChannelHistoryScope(radio, 7u, UUID(0, 2))
    private val messageID = UUID(0, 3)
    private val pendingID = UUID(0, 4)
    private val text = "original \uD83D\uDC4B e\u0301\n"

    @Test fun displayIncarnationNeverCreatesCommandAuthorityAndCannotSurviveAKeyChange() {
        val displayed = ChannelDTO(radioId = radio, index = 7u, name = "shown",
            secret = Bytes(ByteArray(16) { 1 }), displayIncarnationID = origin.expectedOccupantID)
        assertNull(displayed.occupantID)
        assertEquals(displayed.displayIncarnationID, displayed.withFavorite(true).displayIncarnationID)
        assertEquals(displayed.displayIncarnationID,
            displayed.updating(displayed.toChannelInfo().copy(name = "renamed")).displayIncarnationID)
        assertNull(displayed.updating(displayed.toChannelInfo().copy(secret = Bytes(ByteArray(16) { 2 }))).displayIncarnationID)
        assertNull(displayed.updating(displayed.toChannelInfo().copy(index = 8u)).displayIncarnationID)
        assertNotEquals(displayed, displayed.copy(displayIncarnationID = UUID(0, 9)))
        assertNotEquals(displayed, displayed.copy(displayIncarnationID = null))
    }

    @Test fun displayedWitnessSurvivesOnlyIdentityPreservingUpdatesAndParticipatesInEquality() {
        val channel = ChannelDTO(radioId = radio, index = 7u, name = "shown",
            secret = Bytes(ByteArray(16) { 1 }), occupantID = origin.expectedOccupantID)
        assertEquals(channel.occupantID, channel.withFloodScope(ChannelFloodScope.AllRegions).occupantID)
        assertEquals(channel.occupantID, channel.withFavorite(true).occupantID)
        assertEquals(channel.occupantID, channel.withNotificationLevel(NotificationLevel.MUTED).occupantID)
        assertEquals(channel.occupantID, channel.updating(channel.toChannelInfo().copy(name = "renamed")).occupantID)
        assertNull(channel.updating(channel.toChannelInfo().copy(secret = Bytes(ByteArray(16) { 2 }))).occupantID)
        assertNull(channel.updating(channel.toChannelInfo().copy(index = 8u)).occupantID)
        assertNotEquals(channel, channel.copy(occupantID = UUID(0, 9)))
        assertNotEquals(channel, channel.copy(occupantID = null))
    }

    @Test fun creationKeepsCallerIdentityOriginAndExactTextWithoutMintingMetadata() {
        for (type in TextType.entries) {
            val request = ChannelMessageCreation(messageID, origin, text, type)
            assertEquals(messageID, request.messageID)
            assertSame(origin, request.origin)
            assertEquals(text, request.text)
            assertEquals(type, request.textType)
            assertEquals(request, request.copy())
            assertEquals(request.hashCode(), request.copy().hashCode())
        }
        assertEquals(TextType.PLAIN, ChannelMessageCreation(messageID, origin, text).textType)
    }

    @Test fun submissionPreservesExactDtoEnvelopeAndCallerPendingIdentityForEitherResendValue() {
        val dto = MessageDTO(
            id = messageID, radioId = radio, channelIndex = origin.channelIndex, text = text,
            timestamp = 123u, createdAt = Instant.EPOCH, channelOccupantID = origin.expectedOccupantID,
        )
        for (resend in listOf(false, true)) {
            val envelope = ChannelMessageEnvelope(
                dto.id, assertNotNull(dto.channelIndex), resend, dto.text, dto.timestamp, "original node",
                dto.channelOccupantID,
            )
            val submission = ChannelSubmission(pendingID, origin, envelope)
            assertSame(envelope, submission.envelope)
            assertSame(origin, submission.origin)
            val pending = PendingSendDTO.fromEnvelope(
                submission.envelope, submission.origin.radioId, id = submission.pendingID, enqueuedAt = Instant.EPOCH,
            )
            assertEquals(pendingID, pending.id)
            assertEquals(messageID, pending.messageID)
            assertEquals(origin.radioId, pending.radioId)
            assertEquals(origin.expectedOccupantID, pending.channelOccupantID)
            assertEquals(envelope, pending.channelMessageEnvelope())
            assertNull(pending.channelDispatchState)
            assertNull(pending.channelAttemptID)
        }
    }

    @Test fun nullDifferentOccupantAndDifferentIndexRejectIncludingCopies() {
        val envelope = ChannelMessageEnvelope(messageID, origin.channelIndex, false, text, 1u, null, origin.expectedOccupantID)
        for (invalid in listOf(
            envelope.copy(channelOccupantID = null),
            envelope.copy(channelOccupantID = UUID(0, 9)),
            envelope.copy(channelIndex = 8u),
        )) {
            assertFailsWith<IllegalArgumentException> { ChannelSubmission(pendingID, origin, invalid) }
            val valid = ChannelSubmission(pendingID, origin, envelope)
            assertFailsWith<IllegalArgumentException> { valid.copy(envelope = invalid) }
        }
        assertFailsWith<IllegalArgumentException> {
            ChannelSubmission(pendingID, origin.copy(expectedOccupantID = UUID(0, 9)), envelope)
        }
    }

    @Test fun submissionEqualityDistinguishesIntentButDoesNotResolvePersistenceOrIdentityReuse() {
        val envelope = ChannelMessageEnvelope(messageID, origin.channelIndex, false, text, 1u, null, origin.expectedOccupantID)
        val submission = ChannelSubmission(pendingID, origin, envelope)
        assertEquals(submission, submission.copy())
        assertEquals(submission.hashCode(), submission.copy().hashCode())
        for (changed in listOf(
            submission.copy(pendingID = UUID(0, 9)),
            submission.copy(origin = origin.copy(radioId = RadioId(UUID(0, 9)))),
            submission.copy(envelope = envelope.copy(messageID = UUID(0, 9))),
            submission.copy(envelope = envelope.copy(messageText = "$text!")),
            submission.copy(envelope = envelope.copy(messageTimestamp = 2u)),
            submission.copy(envelope = envelope.copy(localNodeName = "another node")),
            submission.copy(envelope = envelope.copy(isResend = true)),
        )) assertNotEquals(submission, changed)
    }
}
