// PortedFrom: MC1Services/Sources/MC1Services/Models/SavedTracePath.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/RxLogEntry.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/DebugLogEntry.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/LinkPreviewData.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Models/NodeStatusSnapshot.swift@db14559b39d32322b06477c6ae676112f583db50
// Process-global logs/cache and node-key-shared history preserve explicit source scope.
package com.meshcoreone.android.core.database

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

@Entity(tableName = "saved_trace_paths", primaryKeys = ["radioId", "id"], indices = [Index("radioId")])
data class SavedTracePathEntity(
    val radioId: UUID, val id: UUID, val name: String, val pathBytes: Bytes, val hashSize: Long,
    @Embedded(prefix = "createdDate_") val createdDate: StoredInstant,
)

@Entity(
    tableName = "trace_path_runs", primaryKeys = ["radioId", "id"],
    foreignKeys = [ForeignKey(
        entity = SavedTracePathEntity::class, parentColumns = ["radioId", "id"],
        childColumns = ["radioId", "savedPathID"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["radioId", "savedPathID"])],
)
data class TracePathRunEntity(
    val radioId: UUID, val id: UUID, val savedPathID: UUID?,
    @Embedded(prefix = "date_") val date: StoredInstant,
    val success: Boolean, val roundTripMs: Long, val hopsData: Bytes,
)

@Entity(
    tableName = "rx_log_entries", primaryKeys = ["radioId", "id"],
    indices = [Index(value = ["radioId", "channelIndex", "senderTimestamp"]), Index(value = ["radioId", "receivedAt_seconds", "receivedAt_nanos"])],
)
data class RxLogEntryEntity(
    val radioId: UUID, val id: UUID, @Embedded(prefix = "receivedAt_") val receivedAt: StoredInstant,
    val snr: Double?, val rssi: Long?, val routeType: Long, val payloadType: Long, val payloadVersion: Long,
    val transportCode: Bytes?, val pathLength: Long, val pathNodes: Bytes, val packetPayload: Bytes,
    val rawPayload: Bytes, val packetHash: String, val channelIndex: Long?, val channelName: String?,
    val decryptStatus: Long, val fromContactName: String?, val toContactName: String?, val senderTimestamp: Long?,
    val regionScope: String?, val regionScopeMatches: SnapshotList<String>, val payloadTypeBits: Long,
)

@Entity(tableName = "debug_log_entries", indices = [Index(value = ["timestamp_seconds", "timestamp_nanos"])])
data class DebugLogEntryEntity(
    @PrimaryKey val id: UUID, @Embedded(prefix = "timestamp_") val timestamp: StoredInstant,
    val level: Long, val subsystem: String, val category: String, val message: String,
)

@Entity(tableName = "link_preview_data")
data class LinkPreviewEntity(
    @PrimaryKey val url: String, val title: String?, val imageData: Bytes?, val iconData: Bytes?,
    val imageWidth: Long?, val imageHeight: Long?, @Embedded(prefix = "fetchedAt_") val fetchedAt: StoredInstant,
)

@Entity(tableName = "node_status_snapshots", indices = [Index(value = ["nodePublicKey", "timestamp_seconds", "timestamp_nanos"])])
data class NodeStatusSnapshotEntity(
    @PrimaryKey val id: UUID, @Embedded(prefix = "timestamp_") val timestamp: StoredInstant, val nodePublicKey: Bytes,
    val batteryMillivolts: Long?, val lastSNR: Double?, val lastRSSI: Long?, val noiseFloor: Long?, val uptimeSeconds: Long?,
    val rxAirtimeSeconds: Long?, val packetsSent: Long?, val packetsReceived: Long?, val receiveErrors: Long?,
    val sentDirect: Long?, val sentFlood: Long?, val receivedDirect: Long?, val receivedFlood: Long?,
    val directDuplicates: Long?, val floodDuplicates: Long?, val postedCount: Long?, val postPushCount: Long?,
    val neighborSnapshots: SnapshotList<NeighborSnapshotEntry>?, val telemetryEntries: SnapshotList<TelemetrySnapshotEntry>?,
    val latitude: Double?, val longitude: Double?, val altitude: Double?,
    val selfRadioID: UUID? = null,
)
