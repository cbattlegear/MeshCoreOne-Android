// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider+ChannelMessages.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.encodePathLen
import java.time.Instant

/**
 * Mesh HQ: a long, multi-sender backlog. Its unread count exceeds one page (pageSize is 50), so the
 * first-unread message — where the "New Messages" divider belongs — only loads when the initial page is
 * sized to cover all unread. Exercises the jump-to-divider scroll and all-unread-in-one-page load.
 */
internal const val MESH_HQ_TOTAL_MESSAGES = 80
internal const val MESH_HQ_UNREAD_COUNT = 60

private val meshHQSenders = listOf("Alice Chen", "Bob Martinez", "Carol Diaz", "Frank Wilson", "Hannah Lee")

private val meshHQLines = listOf(
    "Morning net check — who's on frequency?",
    "Copy, strong signal from the east ridge.",
    "Repeater 3 is back online after the firmware push.",
    "Battery bank held through the night, 82% remaining.",
    "Anyone have eyes on the weather coming over the pass?",
    "Rain expected this afternoon, plan accordingly.",
    "Trace route to the summit node looks clean, 3 hops.",
    "Lost the link to node 7 for a bit, back now.",
    "New antenna mount is up, gaining about 4 dB.",
    "Field team checking in from the trailhead.",
    "Packet loss down to under 2% since the reroute.",
    "Reminder: monthly maintenance window is Sunday.",
    "Great turnout on the group trace test today.",
)

/**
 * Mock channel rows for a slot index, relative to [now]. Incoming messages use `channelIndex` and
 * `senderNodeName`; `senderKeyPrefix` stays null, matching the MeshCore channel payload.
 */
fun MockDataProvider.channelMessages(index: UByte, now: Instant): SnapshotList<MessageDTO> = when (index) {
    publicChannelIndex -> publicChannelMessages(now)
    bayAreaChannelIndex -> bayAreaChannelMessages(now)
    trailCrewChannelIndex -> trailCrewChannelMessages(now)
    meshHQChannelIndex -> meshHQChannelMessages(now)
    else -> SnapshotList.empty()
}

private fun MockDataProvider.meshHQChannelMessages(now: Instant): SnapshotList<MessageDTO> {
    val path = encodePathLen(hashSize = 1, hopCount = 2)
    return fixtureChannelMessageIdentities(meshHQChannelIndex).mapIndexed { i, identity ->
        val createdAt = now.addingInterval((-(MESH_HQ_TOTAL_MESSAGES - i) * 120).toDouble())
        val isRead = i < (MESH_HQ_TOTAL_MESSAGES - MESH_HQ_UNREAD_COUNT)
        if (i % 8 == 7) {
            MockMessageFactory.message(
                identity = identity,
                createdAt = createdAt,
                text = "Copy that, thanks for the update.",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENT,
                channelIndex = meshHQChannelIndex,
                ackCode = (52000 + i).toUInt(),
                pathLength = path,
                isRead = isRead,
            )
        } else {
            MockMessageFactory.message(
                identity = identity,
                createdAt = createdAt,
                text = meshHQLines[i % meshHQLines.size],
                direction = MessageDirection.INCOMING,
                channelIndex = meshHQChannelIndex,
                pathLength = path,
                snr = 6.5 + (i % 5).toDouble() * 0.4,
                senderNodeName = meshHQSenders[i % meshHQSenders.size],
                isRead = isRead,
            )
        }
    }.snapshot()
}

/** Public: multi-sender chatter with a same-sender cluster, plus our own reply. */
private fun MockDataProvider.publicChannelMessages(now: Instant): SnapshotList<MessageDTO> {
    val path = encodePathLen(hashSize = 1, hopCount = 2)
    return fixtureChannelMessageIdentities(publicChannelIndex).mapIndexed { index, identity ->
        when (index) {
            0 -> channelIncoming(
                identity, publicChannelIndex, now.addingInterval(-9000L),
                "Anyone monitoring the north repeater today?", sender = "Alice Chen", snr = 7.4, path = path,
            )
            // Same-sender cluster (consecutive messages from Alice).
            1 -> channelIncoming(
                identity, publicChannelIndex, now.addingInterval(-8940L),
                "Signal's been solid on my end all morning.", sender = "Alice Chen", snr = 7.6, path = path,
            )
            2 -> channelIncoming(
                identity, publicChannelIndex, now.addingInterval(-6000L),
                "Same here, clear copy from the south ridge.", sender = "Bob Martinez", snr = 6.9, path = path,
            )
            3 -> MockMessageFactory.message(
                identity = identity,
                createdAt = now.addingInterval(-1200L),
                text = "Good to hear. I'll run a trace this afternoon.",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENT,
                channelIndex = publicChannelIndex,
                ackCode = 50001u,
                pathLength = path,
            )
            // RX-log correlated flood: hop IDs plus a multi-match region.
            4 -> MockMessageFactory.message(
                identity = identity,
                createdAt = now.addingInterval(-600L),
                text = "Flood from a region that matches two names on this radio",
                direction = MessageDirection.INCOMING,
                channelIndex = publicChannelIndex,
                pathLength = path,
                snr = 6.2,
                pathNodes = Bytes.of(0x10, 0x20),
                senderNodeName = "Alice Chen",
                routeType = RouteType.TC_FLOOD,
                regionScope = null,
                regionScopeMatches = ambiguousRegionNames,
            )
            // Same routes as `aliceMultiPathMessage`. `senderNodeName` matches Alice Chen so `locatedSender(for:)`
            // finds her.
            5 -> publicMultiPathMessage(identity, now)
            else -> invalidFixture("Unknown public fixture message")
        }
    }.snapshot()
}

/** Bay Area (favorite): a reacted message and a self-mention (unread mention badge). */
private fun MockDataProvider.bayAreaChannelMessages(now: Instant): SnapshotList<MessageDTO> {
    val path = encodePathLen(hashSize = 1, hopCount = 1)
    return fixtureChannelMessageIdentities(bayAreaChannelIndex).mapIndexed { index, identity ->
        when (index) {
            0 -> channelIncoming(
                identity, bayAreaChannelIndex, now.addingInterval(-7200L),
                "Welcome to all the new members this week!", sender = "Carol Diaz", snr = 9.1, path = path,
            )
            // Reacted message (badge applied via updateMessageReactionSummary).
            1 -> channelIncoming(
                identity, bayAreaChannelIndex, now.addingInterval(-5400L),
                "We just passed 50 active nodes in the area 🎉", sender = "Carol Diaz", snr = 9.0, path = path,
            )
            // Self-mention drives the mention highlight and unread-mention badge; also reacted.
            2 -> channelIncoming(
                identity, bayAreaChannelIndex, now.addingInterval(-3600L),
                "@[Sim] can you cover the cleanup this Saturday?", sender = "Carol Diaz", snr = 8.8, path = path,
                isRead = false, containsSelfMention = true, mentionSeen = false,
            )
            else -> invalidFixture("Unknown bayArea fixture message")
        }
    }.snapshot()
}

/** Trail Crew (muted): a short exchange to show a muted channel. */
private fun MockDataProvider.trailCrewChannelMessages(now: Instant): SnapshotList<MessageDTO> {
    val path = encodePathLen(hashSize = 1, hopCount = 2)
    return fixtureChannelMessageIdentities(trailCrewChannelIndex).mapIndexed { index, identity ->
        when (index) {
            0 -> channelIncoming(
                identity, trailCrewChannelIndex, now.addingInterval(-9600L),
                "Bridge repair is done. Trail's open again.", sender = "Frank Wilson", snr = 5.2, path = path,
            )
            1 -> MockMessageFactory.message(
                identity = identity,
                createdAt = now.addingInterval(-7200L),
                text = "Nice work everyone.",
                direction = MessageDirection.OUTGOING,
                status = MessageStatus.SENT,
                channelIndex = trailCrewChannelIndex,
                ackCode = 50002u,
                pathLength = path,
            )
            else -> invalidFixture("Unknown trailCrew fixture message")
        }
    }.snapshot()
}

/** Builds an incoming channel message for its fixture slot. */
@Suppress("LongParameterList")
private fun MockDataProvider.channelIncoming(
    identity: SimulatorFixtureMessageIdentity,
    channelIndex: UByte,
    createdAt: Instant,
    text: String,
    sender: String,
    snr: Double,
    path: UByte,
    isRead: Boolean = true,
    containsSelfMention: Boolean = false,
    mentionSeen: Boolean = false,
): MessageDTO {
    return MockMessageFactory.message(
        identity = identity,
        createdAt = createdAt,
        text = text,
        direction = MessageDirection.INCOMING,
        channelIndex = channelIndex,
        pathLength = path,
        snr = snr,
        senderNodeName = sender,
        isRead = isRead,
        containsSelfMention = containsSelfMention,
        mentionSeen = mentionSeen,
    )
}
