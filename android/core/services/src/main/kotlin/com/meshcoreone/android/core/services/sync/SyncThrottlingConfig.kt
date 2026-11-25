// PortedFrom: MC1Services/Sources/MC1Services/Sync/SyncThrottlingConfig.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import java.time.Instant
import kotlin.time.Duration

/**
 * Controls whether channel re-sync is skipped on resync.
 * Computed from the device platform and last clean/attempted channel sync state at sync start.
 */
data class ChannelSyncConfig(
    /** If channels were synced more recently than this window, skip channel re-sync. */
    val channelSyncSkipWindow: Duration = Duration.ZERO,
    /** Timestamp of the last fully-clean channel sync for the current device. */
    val lastCleanChannelSync: Instant? = null,
    /** Timestamp of the last attempted channel sync, even if it was partial. */
    val lastAttemptedChannelSync: Instant? = null,
    /**
     * Whether channel reads should use the windowed read pipeline: nRF52 over BLE (write commands) or
     * ESP32 over WiFi (back-to-back TCP sends). `false` for ESP32 over BLE.
     */
    val usePipelinedChannelRead: Boolean = false,
) {
    companion object {
        /** No skip; Swift `ChannelSyncConfig.none`. */
        val NONE = ChannelSyncConfig()
    }
}
