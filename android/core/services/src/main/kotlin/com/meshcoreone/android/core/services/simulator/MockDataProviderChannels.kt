// PortedFrom: MC1Services/Sources/MC1Services/Simulator/MockDataProvider+Channels.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.simulator

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.time.Instant

/**
 * Seeded channels with varied notification levels and a favorite, so the channel list exercises
 * all/favorite/muted states. Saved via the DTO-based `saveChannel(dto)` (the wire `ChannelInfo` carries no
 * notification/favorite state).
 */
fun MockDataProvider.channels(now: Instant): SnapshotList<ChannelDTO> = SnapshotList.of(
    ChannelDTO(
        id = publicChannelID,
        radioId = simulatorRadioId,
        index = publicChannelIndex,
        name = "Public",
        secret = channelSecret(0xA0u),
        isEnabled = true,
        lastMessageDate = now.addingInterval(-20L),
        unreadCount = 2,
        notificationLevel = NotificationLevel.ALL,
        isFavorite = false,
    ),
    ChannelDTO(
        id = bayAreaChannelID,
        radioId = simulatorRadioId,
        index = bayAreaChannelIndex,
        name = "Bay Area",
        secret = channelSecret(0xB0u),
        isEnabled = true,
        lastMessageDate = now.addingInterval(-3600L),
        unreadCount = 1,
        unreadMentionCount = 1,
        notificationLevel = NotificationLevel.ALL,
        isFavorite = true,
    ),
    ChannelDTO(
        id = trailCrewChannelID,
        radioId = simulatorRadioId,
        index = trailCrewChannelIndex,
        name = "Trail Crew",
        secret = channelSecret(0xC0u),
        isEnabled = true,
        lastMessageDate = now.addingInterval(-7200L),
        unreadCount = 0,
        notificationLevel = NotificationLevel.MUTED,
        isFavorite = false,
    ),
    // Long backlog whose unread count exceeds one page (pageSize is 50), so the first-unread message — where
    // the "New Messages" divider belongs — only exists once the initial load is sized to cover all unread.
    // Exercises both the jump-to-divider scroll and the all-unread-in-one-page load sizing.
    ChannelDTO(
        id = meshHQChannelID,
        radioId = simulatorRadioId,
        index = meshHQChannelIndex,
        name = "Mesh HQ",
        secret = channelSecret(0xD0u),
        isEnabled = true,
        lastMessageDate = now.addingInterval(-90L),
        unreadCount = MESH_HQ_UNREAD_COUNT.toLong(),
        notificationLevel = NotificationLevel.ALL,
        isFavorite = false,
    ),
)

/** Deterministic 16-byte channel PSK from a seed (`UInt8(i) &+ seed`). */
private fun channelSecret(seed: UByte): Bytes = Bytes(ByteArray(16) { index -> (index + seed.toInt()).toByte() })
