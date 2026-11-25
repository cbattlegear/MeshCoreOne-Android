// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider+Contacts.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID

/** All mock contacts for simulator testing, relative to [now]. */
@Suppress("LongMethod")
fun MockDataProvider.contacts(now: Instant): SnapshotList<ContactDTO> {
    val nowSeconds = swiftUInt32Seconds(now)
    return SnapshotList.of(
        // Alice Chen - chat, normal, 3 unread, 2 hops
        mockContact(
            id = aliceChenID, seed = 10u, name = "Alice Chen", type = ContactType.CHAT,
            outPathLength = 2u, outPath = Bytes.of(0x10, 0x20), // 2-hop path
            lastAdvertTimestamp = nowSeconds.swiftMinus(300u), // 5 min ago
            latitude = 37.7849, longitude = -122.4094, lastModified = nowSeconds,
            lastMessageDate = now.addingInterval(-40L), unreadCount = 3,
        ),
        // Bob Martinez - chat, 1 hop (direct)
        mockContact(
            id = bobMartinezID, seed = 20u, name = "Bob Martinez", type = ContactType.CHAT,
            outPathLength = 1u, outPath = Bytes.of(0x20), // Direct
            lastAdvertTimestamp = nowSeconds.swiftMinus(60u), // 1 min ago
            latitude = 37.7649, longitude = -122.4294, lastModified = nowSeconds,
            lastMessageDate = now.addingInterval(-900L), // 15 min ago
        ),
        // Charlie Node - repeater, 0 hops (self)
        mockContact(
            id = charlieNodeID, seed = 30u, name = "Charlie Node", type = ContactType.REPEATER,
            outPathLength = 0u, outPath = Bytes.EMPTY,
            lastAdvertTimestamp = nowSeconds.swiftMinus(120u), // 2 min ago
            latitude = 37.7549, longitude = -122.4394, lastModified = nowSeconds,
        ),
        // Diana's Room - room, 3 hops
        mockContact(
            id = dianasRoomID, seed = 40u, name = "Diana's Room", type = ContactType.ROOM,
            outPathLength = 3u, outPath = Bytes.of(0x10, 0x20, 0x40), // 3-hop path
            lastAdvertTimestamp = nowSeconds.swiftMinus(600u), // 10 min ago
            latitude = 37.7449, longitude = -122.4494, lastModified = nowSeconds,
        ),
        // Eve Thompson - chat, blocked, 4 hops
        mockContact(
            id = eveThompsonID, seed = 50u, name = "Eve Thompson", type = ContactType.CHAT,
            outPathLength = 4u, outPath = Bytes.of(0x10, 0x20, 0x30, 0x50), // 4-hop path
            lastAdvertTimestamp = nowSeconds.swiftMinus(1800u), // 30 min ago
            latitude = 37.7349, longitude = -122.4594, lastModified = nowSeconds, isBlocked = true,
        ),
        // Frank Wilson - chat, nickname "Dad", 2 hops
        mockContact(
            id = frankWilsonID, seed = 60u, name = "Frank Wilson", type = ContactType.CHAT,
            outPathLength = 2u, outPath = Bytes.of(0x10, 0x60), // 2-hop path
            lastAdvertTimestamp = nowSeconds.swiftMinus(3600u), // 1 hour ago
            latitude = 37.7249, longitude = -122.4694, lastModified = nowSeconds, nickname = "Dad",
            lastMessageDate = now.addingInterval(-1800L),
        ),
        // Ghost Node - repeater, no recent contact, 5 hops
        mockContact(
            id = ghostNodeID, seed = 70u, name = "Ghost Node", type = ContactType.REPEATER,
            outPathLength = 5u, outPath = Bytes.of(0x10, 0x20, 0x30, 0x40, 0x70), // 5-hop path (stale)
            lastAdvertTimestamp = nowSeconds.swiftMinus(86400u), // 24 hours ago
            latitude = 0.0, longitude = 0.0, lastModified = nowSeconds.swiftMinus(86400u),
        ),
        // Hannah Lee - chat, direct, short greeting conversation
        mockContact(
            id = hannahLeeID, seed = 80u, name = "Hannah Lee", type = ContactType.CHAT,
            outPathLength = 1u, outPath = Bytes.of(0x80), // Direct
            lastAdvertTimestamp = nowSeconds.swiftMinus(30u), // 30 seconds ago
            latitude = 37.7149, longitude = -122.4794, lastModified = nowSeconds, isFavorite = true,
            lastMessageDate = now.addingInterval(-600L), // 10 min ago
        ),
        // Located so a matched hop can be pinned.
        locatedRepeater(northRidgeRepeaterID, northRidgeRepeaterSeed, "North Ridge", 37.8320, -122.4820, 180, now),
        locatedRepeater(twinPeaksRepeaterID, twinPeaksRepeaterSeed, "Twin Peaks", 37.7544, -122.4477, 240, now),
        locatedRepeater(oaklandRepeaterID, oaklandRepeaterSeed, "Oakland", 37.8044, -122.2712, 300, now),
    )
}

@Suppress("LongParameterList")
private fun MockDataProvider.mockContact(
    id: UUID,
    seed: UByte,
    name: String,
    type: ContactType,
    outPathLength: UByte,
    outPath: Bytes,
    lastAdvertTimestamp: UInt,
    latitude: Double,
    longitude: Double,
    lastModified: UInt,
    nickname: String? = null,
    isBlocked: Boolean = false,
    isFavorite: Boolean = false,
    lastMessageDate: Instant? = null,
    unreadCount: Long = 0,
): ContactDTO = ContactDTO(
    id = id,
    radioId = simulatorRadioId,
    publicKey = mockPublicKey(seed),
    name = name,
    typeRawValue = type.rawValue,
    flags = 0u,
    outPathLength = outPathLength,
    outPath = outPath,
    lastAdvertTimestamp = lastAdvertTimestamp,
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified,
    lastHeardTimestamp = null,
    nickname = nickname,
    isBlocked = isBlocked,
    isMuted = false,
    isFavorite = isFavorite,
    lastMessageDate = lastMessageDate,
    unreadCount = unreadCount,
)

@Suppress("LongParameterList")
private fun MockDataProvider.locatedRepeater(
    id: UUID,
    seed: UByte,
    name: String,
    latitude: Double,
    longitude: Double,
    advertAgeSeconds: Long,
    now: Instant,
): ContactDTO {
    val advert = swiftUInt32Seconds(now.addingInterval(-advertAgeSeconds))
    return mockContact(
        id = id, seed = seed, name = name, type = ContactType.REPEATER, outPathLength = 0u, outPath = Bytes.EMPTY,
        lastAdvertTimestamp = advert, latitude = latitude, longitude = longitude, lastModified = advert,
    )
}
