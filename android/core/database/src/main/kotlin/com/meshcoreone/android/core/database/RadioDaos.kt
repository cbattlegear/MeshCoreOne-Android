// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
// Real row operations; WP-202 composes repository algorithms/transactions.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlinx.coroutines.flow.Flow

interface RowWriter<Row> {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insert(row: Row)
    @Upsert suspend fun upsert(row: Row)
    @Upsert suspend fun upsert(rows: List<Row>)
}

@Dao
interface DeviceDao : RowWriter<DeviceEntity> {
    @Query("SELECT * FROM devices ORDER BY lastConnected_seconds DESC, lastConnected_nanos DESC")
    suspend fun all(): List<DeviceEntity>
    @Query("SELECT * FROM devices WHERE id = :id") suspend fun byId(id: UUID): DeviceEntity?
    @Query("SELECT * FROM devices WHERE radioId = :radioId") suspend fun forRadio(radioId: UUID): List<DeviceEntity>
    @Query("SELECT * FROM devices WHERE publicKey = :publicKey") suspend fun forPublicKey(publicKey: Bytes): List<DeviceEntity>
    @Query("SELECT * FROM devices WHERE isActive = 1") suspend fun active(): List<DeviceEntity>
    @Query("DELETE FROM devices WHERE id = :id") suspend fun delete(id: UUID): Int
    @Query("UPDATE devices SET lastContactSync = :timestamp WHERE radioId = :radioId")
    suspend fun setLastContactSync(radioId: UUID, timestamp: Long): Int
    @Query("SELECT COUNT(*) FROM devices") suspend fun count(): Long
}

@Dao
interface ContactDao : RowWriter<ContactEntity> {
    @Query("SELECT * FROM contacts") suspend fun backupAll(): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE radioId = :radioId ORDER BY name")
    suspend fun forRadio(radioId: UUID): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE radioId = :radioId ORDER BY name")
    fun observe(radioId: UUID): Flow<List<ContactEntity>>
    @Query("SELECT * FROM contacts WHERE radioId = :radioId AND id = :id")
    suspend fun byId(radioId: UUID, id: UUID): ContactEntity?
    // AndroidOnly: WP-209 Existing-row avatar write cannot overwrite metadata or insert a deleted contact.
    @Query("UPDATE contacts SET avatarImageData = :imageData WHERE radioId = :radioId AND id = :id AND publicKey = :expectedPublicKey")
    suspend fun updateAvatar(radioId: UUID, id: UUID, expectedPublicKey: Bytes, imageData: Bytes?): Int
    @Query("SELECT * FROM contacts WHERE radioId = :radioId AND publicKey = :key")
    suspend fun forPublicKey(radioId: UUID, key: Bytes): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE radioId = :radioId AND substr(publicKey, 1, :length) = :prefix")
    suspend fun forPrefix(radioId: UUID, prefix: Bytes, length: Int): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE substr(publicKey, 1, :length) = :prefix")
    suspend fun globalPrefixHints(prefix: Bytes, length: Int): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE publicKey = :key") suspend fun globalPublicKeyHints(key: Bytes): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE radioId = :radioId AND lastMessageDate_seconds IS NOT NULL ORDER BY lastMessageDate_seconds DESC, lastMessageDate_nanos DESC")
    suspend fun conversations(radioId: UUID): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE radioId = :radioId AND isBlocked = 1 ORDER BY name")
    suspend fun blocked(radioId: UUID): List<ContactEntity>
    @Query("DELETE FROM contacts WHERE radioId = :radioId AND id = :id") suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM contacts WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
    @Query("SELECT COUNT(*) FROM contacts WHERE radioId = :radioId") suspend fun count(radioId: UUID): Long
}

@Dao
interface ChannelDao : RowWriter<ChannelEntity> {
    @Query("SELECT * FROM channels") suspend fun backupAll(): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE radioId = :radioId ORDER BY `index`") suspend fun forRadio(radioId: UUID): List<ChannelEntity>
    @Query("SELECT * FROM channels WHERE radioId = :radioId ORDER BY `index`") fun observe(radioId: UUID): Flow<List<ChannelEntity>>
    @Query("SELECT * FROM channels WHERE radioId = :radioId AND id = :id") suspend fun byId(radioId: UUID, id: UUID): ChannelEntity?
    @Query("SELECT * FROM channels WHERE radioId = :radioId AND `index` = :index") suspend fun forIndex(radioId: UUID, index: Long): List<ChannelEntity>
    @Query("UPDATE channels SET floodScopeModeRawValue = :mode, regionScope = :region WHERE radioId = :radioId AND id = :id")
    suspend fun setFloodScope(radioId: UUID, id: UUID, mode: String, region: String?): Int
    @Query("DELETE FROM channels WHERE radioId = :radioId AND id = :id") suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM channels WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
}

@Dao
interface DiscoveredNodeDao : RowWriter<DiscoveredNodeEntity> {
    @Query("SELECT * FROM discovered_nodes") suspend fun backupAll(): List<DiscoveredNodeEntity>
    @Query("SELECT * FROM discovered_nodes WHERE radioId = :radioId") suspend fun forRadio(radioId: UUID): List<DiscoveredNodeEntity>
    @Query("SELECT * FROM discovered_nodes WHERE radioId = :radioId AND id = :id") suspend fun byId(radioId: UUID, id: UUID): DiscoveredNodeEntity?
    @Query("SELECT * FROM discovered_nodes WHERE radioId = :radioId AND publicKey = :publicKey")
    suspend fun forPublicKey(radioId: UUID, publicKey: Bytes): List<DiscoveredNodeEntity>
    @Query("SELECT * FROM discovered_nodes WHERE radioId = :radioId ORDER BY lastHeard_seconds, lastHeard_nanos LIMIT :limit")
    suspend fun oldest(radioId: UUID, limit: Long): List<DiscoveredNodeEntity>
    @Query("SELECT COUNT(*) FROM discovered_nodes WHERE radioId = :radioId") suspend fun count(radioId: UUID): Long
    @Query("DELETE FROM discovered_nodes WHERE radioId = :radioId AND id = :id") suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM discovered_nodes WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
}

@Dao
interface BlockedChannelSenderDao : RowWriter<BlockedChannelSenderEntity> {
    @Query("SELECT * FROM blocked_channel_senders") suspend fun backupAll(): List<BlockedChannelSenderEntity>
    @Query("SELECT * FROM blocked_channel_senders WHERE radioId = :radioId ORDER BY dateBlocked_seconds DESC, dateBlocked_nanos DESC")
    suspend fun forRadio(radioId: UUID): List<BlockedChannelSenderEntity>
    @Query("SELECT * FROM blocked_channel_senders WHERE radioId = :radioId AND name = :name")
    suspend fun forName(radioId: UUID, name: String): List<BlockedChannelSenderEntity>
    @Query("DELETE FROM blocked_channel_senders WHERE radioId = :radioId AND id = :id")
    suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM blocked_channel_senders WHERE radioId = :radioId AND name = :name") suspend fun deleteName(radioId: UUID, name: String): Int
    @Query("DELETE FROM blocked_channel_senders WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
}
