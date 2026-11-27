// AndroidOnly: WP-101 Prizm Mobile Observer service logic (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.session

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.health.DeviceStatsReader
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import kotlinx.coroutines.flow.Flow

/** The connected node as the observer sees it. The Android adapter builds this from the live radio session. */
interface ObserverNodeLink {
    val nodeKey: Bytes
    val deviceInfo: NodeDeviceInfo

    /** The session event feed (MeshEvent.RxLogData for 0x88 frames, MeshEvent.ChannelInfo for slot replies). */
    fun events(): Flow<MeshEvent>

    /** Reads every channel slot from the node (CMD_GET_CHANNEL per index). May throw on a node or link failure. */
    suspend fun fetchChannels(): List<ChannelInfo>

    /** Null when the node cannot be asked for stats; health then carries only `ble_connected`. */
    fun statsReader(): DeviceStatsReader?

    fun isConnected(): Boolean
}

/** Requests location updates from the platform; the interval is whatever `SamplingRatePolicy` currently says. */
interface LocationUpdates {
    /** Starts, or re-requests with a new interval. Fixes are delivered to `ObserverSession.onFix`. */
    fun request(intervalMs: Long)

    fun stop()
}

/**
 * Packets are counted as they move: [heard] every parsed packet, [uploaded] full records handed to the upload pipeline,
 * [metadataOnly] private traffic reduced to routing metadata and handed on, [queued] rows waiting for the broker.
 * "Uploaded" means accepted for upload (the pipeline batches every 15 s); it is not a per-record broker ack.
 */
data class ObserverCounters(
    val heard: Int = 0,
    val uploaded: Int = 0,
    val metadataOnly: Int = 0,
    val queued: Int = 0,
)
