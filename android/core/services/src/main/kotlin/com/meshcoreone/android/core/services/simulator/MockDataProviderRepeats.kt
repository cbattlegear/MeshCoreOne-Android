// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider+Repeats.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.encodePathLen
import java.time.Instant
import java.util.UUID

/**
 * Message IDs that carry seeded `MessageRepeat` rows. Outgoing rows are send echoes. Incoming rows are later
 * flood routes.
 */
internal val MockDataProvider.messagesWithRepeats: SnapshotList<UUID>
    get() = SnapshotList.of(frankRepeatMessageID, aliceMultiPathMessageID, publicMultiPathMessageID)

/** Distinct repeater hashes, hop counts, and signal stats. The row count matches the parent's `heardRepeats`. */
internal fun MockDataProvider.messageRepeats(messageID: UUID, now: Instant): SnapshotList<MessageRepeatDTO> {
    if (messageID == aliceMultiPathMessageID) {
        return incomingPathRepeats(messageID, receivedAt = now.addingInterval(ALICE_MULTI_PATH_AGE_SECONDS))
    }
    if (messageID == publicMultiPathMessageID) {
        return incomingPathRepeats(messageID, receivedAt = now.addingInterval(PUBLIC_MULTI_PATH_AGE_SECONDS))
    }
    if (messageID != frankRepeatMessageID) return SnapshotList.empty()
    return fixtureRepeatIdentities(messageID).mapIndexed { index, identity ->
        when (index) {
            0 -> MessageRepeatDTO(
                id = identity.id,
                messageID = identity.messageID,
                receivedAt = now.addingInterval(-255_590L),
                pathNodes = Bytes.of(0x31), // 1 hop, 1-byte hash
                pathLength = encodePathLen(hashSize = 1, hopCount = 1),
                snr = 6.5,
                rssi = -92,
                rxLogEntryID = identity.rxLogEntryID,
            )
            1 -> MessageRepeatDTO(
                id = identity.id,
                messageID = identity.messageID,
                receivedAt = now.addingInterval(-255_585L),
                pathNodes = Bytes.of(0x8F, 0x2C), // 1 hop, 2-byte hash
                pathLength = encodePathLen(hashSize = 2, hopCount = 1),
                snr = 4.2,
                rssi = -101,
                rxLogEntryID = identity.rxLogEntryID,
            )
            2 -> MessageRepeatDTO(
                id = identity.id,
                messageID = identity.messageID,
                receivedAt = now.addingInterval(-255_580L),
                pathNodes = Bytes.of(0x44, 0x71), // 2 hops, 1-byte hash
                pathLength = encodePathLen(hashSize = 1, hopCount = 2),
                snr = 1.9,
                rssi = -108,
                rxLogEntryID = identity.rxLogEntryID,
            )
            else -> invalidFixture("Unknown Frank fixture repeat")
        }
    }.snapshot()
}
