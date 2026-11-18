// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider+Reactions.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshot
import java.time.Instant
import java.util.UUID

/** A message that carries seeded reactions, paired with its denormalized badge summary. */
data class ReactedMessage(val messageID: UUID, val summary: String)

/**
 * Messages that carry seeded reactions, paired with the denormalized badge summary. `saveMessage` drops
 * `reactionSummary`, so the summary is written via `updateMessageReactionSummary`; the per-reactor rows
 * render the detail list. Each summary's counts match the rows returned by `reactions(messageID, now)`.
 */
internal val MockDataProvider.reactedMessages: SnapshotList<ReactedMessage>
    get() = SnapshotList.of(
        ReactedMessage(aliceReactedMessageID, "👍:2,❤️:1"),
        ReactedMessage(bayAreaReactedMessageID, "🎉:2"),
        ReactedMessage(bayAreaMentionMessageID, "👍:1"),
    )

/**
 * Per-reactor reaction rows for the reactor-detail list. Swift's `ReactionDTO` defaults `receivedAt` to
 * `Date()`; here it is the seed's [now].
 */
internal fun MockDataProvider.reactions(messageID: UUID, now: Instant): SnapshotList<ReactionDTO> {
    val contactID = if (messageID == aliceReactedMessageID) aliceChenID else null
    val channelIndex = if (messageID == bayAreaReactedMessageID || messageID == bayAreaMentionMessageID) {
        bayAreaChannelIndex
    } else {
        null
    }
    return fixtureReactionIdentities(messageID).map { identity ->
        reaction(identity, now, contactID = contactID, channelIndex = channelIndex)
    }.snapshot()
}

/** `messageHash` is the first 8 characters of Swift's uppercase `uuidString`. */
@Suppress("LongParameterList")
private fun MockDataProvider.reaction(
    identity: SimulatorFixtureReactionIdentity,
    now: Instant,
    contactID: UUID? = null,
    channelIndex: UByte? = null,
): ReactionDTO = ReactionDTO(
    id = identity.id,
    messageID = identity.messageID,
    emoji = identity.emoji,
    senderName = identity.senderName,
    messageHash = identity.messageID.canonicalString().take(8),
    rawText = identity.emoji,
    receivedAt = now,
    channelIndex = channelIndex,
    contactID = contactID,
    radioId = simulatorRadioId,
)
