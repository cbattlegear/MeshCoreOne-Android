// PortedFrom: MC1Services/Sources/MC1Services/Sync/DevicePlatform+SyncThrottling.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.model.DevicePlatform
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * If channels were synced more recently than this, skip channel re-sync on resync.
 * Only enabled for ESP32 where channel re-sync wastes scarce connection time. Channel skipping is a
 * correctness tradeoff (not just performance), so it stays disabled for unknown platforms.
 */
val DevicePlatform.channelSyncSkipWindow: Duration
    get() = when (this) {
        DevicePlatform.ESP32 -> 30.seconds
        DevicePlatform.NRF52, DevicePlatform.UNKNOWN -> Duration.ZERO
    }

/** Builds a channel sync config for a sync operation. */
fun DevicePlatform.channelSyncConfig(
    lastCleanChannelSync: Instant?,
    lastAttemptedChannelSync: Instant? = null,
    usePipelinedChannelRead: Boolean = false,
): ChannelSyncConfig = ChannelSyncConfig(
    channelSyncSkipWindow = channelSyncSkipWindow,
    lastCleanChannelSync = lastCleanChannelSync,
    lastAttemptedChannelSync = lastAttemptedChannelSync,
    usePipelinedChannelRead = usePipelinedChannelRead,
)
