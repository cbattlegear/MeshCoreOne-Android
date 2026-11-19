// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Diagnostics.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Metadata.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
// Real diagnostics/cache/history queries; repository retention/enrichment belongs to WP-202.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Query
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

@Dao
interface SavedTracePathDao : RowWriter<SavedTracePathEntity> {
    @Query("SELECT * FROM saved_trace_paths") suspend fun backupAll(): List<SavedTracePathEntity>
    @Query("SELECT * FROM saved_trace_paths WHERE radioId = :radioId ORDER BY createdDate_seconds DESC, createdDate_nanos DESC")
    suspend fun forRadio(radioId: UUID): List<SavedTracePathEntity>
    @Query("SELECT * FROM saved_trace_paths WHERE radioId = :radioId AND id = :id")
    suspend fun byId(radioId: UUID, id: UUID): SavedTracePathEntity?
    @Query("DELETE FROM saved_trace_paths WHERE radioId = :radioId AND id = :id")
    suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM saved_trace_paths WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
}

@Dao
interface TracePathRunDao : RowWriter<TracePathRunEntity> {
    @Query("SELECT * FROM trace_path_runs") suspend fun backupAll(): List<TracePathRunEntity>
    @Query("SELECT * FROM trace_path_runs WHERE radioId = :radioId AND savedPathID = :pathID ORDER BY date_seconds, date_nanos")
    suspend fun forPath(radioId: UUID, pathID: UUID): List<TracePathRunEntity>
    @Query("SELECT * FROM trace_path_runs WHERE radioId = :radioId AND id = :id")
    suspend fun byId(radioId: UUID, id: UUID): TracePathRunEntity?
    @Query("DELETE FROM trace_path_runs WHERE radioId = :radioId AND savedPathID = :pathID")
    suspend fun deleteForPath(radioId: UUID, pathID: UUID): Int
    @Query("DELETE FROM trace_path_runs WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
}

@Dao
interface RxLogDao : RowWriter<RxLogEntryEntity> {
    @Query("SELECT COUNT(*) FROM rx_log_entries WHERE radioId >= upper(:radioId) AND radioId <= lower(:radioId) AND radioId = :radioId COLLATE NOCASE AND id >= upper(:id) AND id <= lower(:id) AND id = :id COLLATE NOCASE")
    suspend fun countSimulatorIdentity(radioId: UUID, id: UUID): Long
    @Query("SELECT * FROM rx_log_entries WHERE radioId = :radioId ORDER BY receivedAt_seconds DESC, receivedAt_nanos DESC LIMIT :limit")
    suspend fun newest(radioId: UUID, limit: Long): List<RxLogEntryEntity>
    @Query("SELECT * FROM rx_log_entries WHERE radioId = :radioId AND id = :id")
    suspend fun byId(radioId: UUID, id: UUID): RxLogEntryEntity?
    @Query("SELECT * FROM rx_log_entries WHERE radioId = :radioId AND channelIndex = :index AND senderTimestamp = :timestamp ORDER BY receivedAt_seconds, receivedAt_nanos")
    suspend fun channelCorrelation(radioId: UUID, index: Long, timestamp: Long): List<RxLogEntryEntity>
    @Query("SELECT * FROM rx_log_entries WHERE radioId = :radioId AND payloadType = 2 AND senderTimestamp = :timestamp AND channelIndex IS NULL ORDER BY receivedAt_seconds DESC, receivedAt_nanos DESC")
    suspend fun directCorrelation(radioId: UUID, timestamp: Long): List<RxLogEntryEntity>
    @Query("SELECT * FROM rx_log_entries WHERE radioId = :radioId AND transportCode IS NOT NULL ORDER BY receivedAt_seconds DESC, receivedAt_nanos DESC LIMIT :limit")
    suspend fun withTransportCode(radioId: UUID, limit: Long): List<RxLogEntryEntity>
    @Query("SELECT * FROM rx_log_entries WHERE radioId = :radioId AND decryptStatus = :status AND (receivedAt_seconds > :seconds OR (receivedAt_seconds = :seconds AND receivedAt_nanos >= :nanos)) ORDER BY receivedAt_seconds ASC, receivedAt_nanos ASC")
    suspend fun forDecryptStatus(radioId: UUID, status: Long, seconds: Long, nanos: Int): List<RxLogEntryEntity>
    @Query("SELECT * FROM rx_log_entries WHERE radioId = :radioId ORDER BY receivedAt_seconds, receivedAt_nanos LIMIT :limit")
    suspend fun oldest(radioId: UUID, limit: Long): List<RxLogEntryEntity>
    @Query("SELECT COUNT(*) FROM rx_log_entries WHERE radioId = :radioId") suspend fun count(radioId: UUID): Long
    @Query("DELETE FROM rx_log_entries WHERE radioId = :radioId AND id = :id") suspend fun delete(radioId: UUID, id: UUID): Int
    @Query("DELETE FROM rx_log_entries WHERE radioId = :radioId") suspend fun clearRadio(radioId: UUID): Int
}

@Dao
interface DebugLogDao : RowWriter<DebugLogEntryEntity> {
    @Query("SELECT * FROM debug_log_entries WHERE timestamp_seconds > :seconds OR (timestamp_seconds = :seconds AND timestamp_nanos >= :nanos) ORDER BY timestamp_seconds DESC, timestamp_nanos DESC LIMIT :limit")
    suspend fun since(seconds: Long, nanos: Int, limit: Long): List<DebugLogEntryEntity>
    @Query("SELECT COUNT(*) FROM debug_log_entries") suspend fun count(): Long
    @Query("SELECT * FROM debug_log_entries ORDER BY timestamp_seconds, timestamp_nanos LIMIT :limit")
    suspend fun oldest(limit: Long): List<DebugLogEntryEntity>
    @Query("DELETE FROM debug_log_entries WHERE timestamp_seconds < :seconds OR (timestamp_seconds = :seconds AND timestamp_nanos < :nanos)")
    suspend fun deleteOlderThan(seconds: Long, nanos: Int): Int
    @Query("DELETE FROM debug_log_entries WHERE id = :id") suspend fun delete(id: UUID): Int
    @Query("DELETE FROM debug_log_entries") suspend fun clear(): Int
}

@Dao
interface LinkPreviewDao : RowWriter<LinkPreviewEntity> {
    @Query("SELECT * FROM link_preview_data WHERE url = :url") suspend fun byURL(url: String): LinkPreviewEntity?
}

@Dao
interface NodeSnapshotDao : RowWriter<NodeStatusSnapshotEntity> {
    @Query("SELECT * FROM node_status_snapshots") suspend fun backupAll(): List<NodeStatusSnapshotEntity>
    @Query("SELECT * FROM node_status_snapshots WHERE nodePublicKey = :key ORDER BY timestamp_seconds DESC, timestamp_nanos DESC LIMIT 1")
    suspend fun latest(key: Bytes): NodeStatusSnapshotEntity?
    @Query("SELECT * FROM node_status_snapshots WHERE nodePublicKey = :key ORDER BY timestamp_seconds, timestamp_nanos")
    suspend fun history(key: Bytes): List<NodeStatusSnapshotEntity>
    @Query("SELECT * FROM node_status_snapshots WHERE nodePublicKey = :key AND (timestamp_seconds > :seconds OR (timestamp_seconds = :seconds AND timestamp_nanos >= :nanos)) ORDER BY timestamp_seconds, timestamp_nanos")
    suspend fun historySince(key: Bytes, seconds: Long, nanos: Int): List<NodeStatusSnapshotEntity>
    @Query("SELECT * FROM node_status_snapshots WHERE id = :id") suspend fun byId(id: UUID): NodeStatusSnapshotEntity?
    @Query("DELETE FROM node_status_snapshots WHERE timestamp_seconds < :seconds OR (timestamp_seconds = :seconds AND timestamp_nanos < :nanos)")
    suspend fun deleteOlderThan(seconds: Long, nanos: Int): Int
    @Query("DELETE FROM node_status_snapshots WHERE nodePublicKey = :key AND NOT EXISTS(SELECT 1 FROM remote_node_sessions WHERE publicKey = :key)")
    suspend fun deleteIfUnreferenced(key: Bytes): Int
}
