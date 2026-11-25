// PortedFrom: MC1Services/Sources/MC1Services/Models/Device.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/Contact.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/Channel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/DiscoveredNode.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/BlockedChannelSender.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native Room rows; UUID-only source references are not invented cascades.
package com.meshcoreone.android.core.database

import androidx.room.Embedded
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

@Entity(tableName = "devices", indices = [Index("radioId"), Index("publicKey")])
data class DeviceEntity(
    @PrimaryKey val id: UUID,
    val radioId: UUID,
    val publicKey: Bytes,
    val nodeName: String,
    val firmwareVersion: Long,
    val firmwareVersionString: String,
    val manufacturerName: String,
    val buildDate: String,
    val maxContacts: Long,
    val maxChannels: Long,
    val frequency: Long,
    val bandwidth: Long,
    val spreadingFactor: Long,
    val codingRate: Long,
    val txPower: Long,
    val maxTxPower: Long,
    val latitude: Double,
    val longitude: Double,
    val blePin: Long,
    val clientRepeat: Boolean,
    val pathHashMode: Long,
    val defaultFloodScopeName: String?,
    val preRepeatFrequency: Long?,
    val preRepeatBandwidth: Long?,
    val preRepeatSpreadingFactor: Long?,
    val preRepeatCodingRate: Long?,
    val manualAddContacts: Boolean,
    val autoAddConfig: Long,
    val autoAddMaxHops: Long,
    val multiAcks: Long,
    val telemetryModeBase: Long,
    val telemetryModeLoc: Long,
    val telemetryModeEnv: Long,
    val advertLocationPolicy: Long,
    @Embedded(prefix = "lastConnected_") val lastConnected: StoredInstant,
    val lastContactSync: Long,
    val isActive: Boolean,
    val ocvPreset: String?,
    val appliedRadioPresetID: String?,
    val customOCVArrayString: String?,
    val connectionMethods: SnapshotList<ConnectionMethod>,
    val knownRegions: SnapshotList<String>,
)

@Entity(tableName = "contacts", primaryKeys = ["radioId", "id"], indices = [Index("radioId"), Index(value = ["radioId", "publicKey"])])
data class ContactEntity(
    val radioId: UUID,
    val id: UUID,
    val publicKey: Bytes,
    val name: String,
    val typeRawValue: Long,
    val flags: Long,
    val outPathLength: Long,
    val outPath: Bytes,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val lastModified: Long,
    val lastHeardTimestamp: Long,
    val nickname: String?,
    val isBlocked: Boolean,
    val isMuted: Boolean,
    val isFavorite: Boolean,
    @Embedded(prefix = "lastMessageDate_") val lastMessageDate: StoredInstant?,
    val unreadCount: Long,
    val unreadMentionCount: Long,
    val ocvPreset: String?,
    val customOCVArrayString: String?,
    val avatarImageData: Bytes?,
    @ColumnInfo(defaultValue = "0") val isPinned: Boolean = false,
)

@Entity(tableName = "channels", primaryKeys = ["radioId", "id"], indices = [Index("radioId"), Index(value = ["radioId", "index"])])
data class ChannelEntity(
    val radioId: UUID,
    val id: UUID,
    val index: Long,
    val name: String,
    val secret: Bytes,
    val isEnabled: Boolean,
    @Embedded(prefix = "lastMessageDate_") val lastMessageDate: StoredInstant?,
    val unreadCount: Long,
    val unreadMentionCount: Long,
    val notificationLevelRawValue: Long,
    val legacyIsMuted: Boolean?,
    val isFavorite: Boolean,
    val floodScopeModeRawValue: String,
    val regionScope: String?,
)

@Entity(
    tableName = "discovered_nodes", primaryKeys = ["radioId", "id"],
    indices = [Index(value = ["radioId", "publicKey"]), Index(value = ["radioId", "lastHeard_seconds", "lastHeard_nanos"])],
)
data class DiscoveredNodeEntity(
    val radioId: UUID,
    val id: UUID,
    val publicKey: Bytes,
    val name: String,
    val typeRawValue: Long,
    @Embedded(prefix = "lastHeard_") val lastHeard: StoredInstant,
    val lastAdvertTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val outPathLength: Long,
    val outPath: Bytes,
    val inboundHopCount: Long?,
    val inboundHopAdvertTimestamp: Long?,
)

@Entity(tableName = "blocked_channel_senders", primaryKeys = ["radioId", "id"], indices = [Index(value = ["radioId", "name"])])
data class BlockedChannelSenderEntity(
    val radioId: UUID, val id: UUID, val name: String,
    @Embedded(prefix = "dateBlocked_") val dateBlocked: StoredInstant,
)
