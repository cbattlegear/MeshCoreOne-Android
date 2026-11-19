// AndroidOnly: WP-201 Retained exact queued channel wire-attempt journal; no raw channel secrets.
package com.meshcoreone.android.core.database

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import com.meshcoreone.android.core.model.*
import java.util.UUID

@Entity(
    tableName = "queued_channel_attempts", primaryKeys = ["radioId", "attemptID"],
    indices = [Index(value = ["radioId", "pendingID"])],
)
data class QueuedChannelAttemptEntity(
    val radioId: UUID,
    val attemptID: UUID,
    val pendingID: UUID,
    val messageID: UUID,
    val channelIndex: Long,
    val channelOccupantID: UUID,
    val wireTimestamp: Long,
    val isResend: Boolean,
    val phaseRawValue: Long,
    @ColumnInfo(defaultValue = "0") val isCompleted: Boolean = false,
)

fun QueuedChannelAttemptEntity.toDTO(): QueuedChannelAttempt = QueuedChannelAttempt(
    QueuedChannelAttemptKey(RadioId(radioId), pendingID, messageID, attemptID),
    channelIndex.ubyte("queuedChannelAttempt.channelIndex"), channelOccupantID,
    wireTimestamp.uint("queuedChannelAttempt.wireTimestamp"),
    isResend,
    checkedPhase(),
    isCompleted,
)

private fun QueuedChannelAttemptEntity.checkedPhase(): QueuedChannelAttemptPhase {
    val phase = QueuedChannelAttemptPhase.fromRawValue(phaseRawValue)
        ?: throw DatabaseValueException("queuedChannelAttempt.phase", "Unknown raw value $phaseRawValue")
    if (isCompleted && phase != QueuedChannelAttemptPhase.ACCEPTED) {
        throw DatabaseValueException("queuedChannelAttempt.phase", "Only an accepted attempt can be completed")
    }
    return phase
}

@Dao
interface QueuedChannelAttemptDao : RowWriter<QueuedChannelAttemptEntity> {
    @Query("SELECT * FROM queued_channel_attempts WHERE radioId = :radioId AND attemptID = :attemptID")
    suspend fun byId(radioId: UUID, attemptID: UUID): QueuedChannelAttemptEntity?
    @Query("SELECT * FROM queued_channel_attempts WHERE radioId = :radioId AND pendingID = :pendingID ORDER BY rowid")
    suspend fun forPending(radioId: UUID, pendingID: UUID): List<QueuedChannelAttemptEntity>
    @Query("UPDATE queued_channel_attempts SET phaseRawValue = :phase WHERE radioId = :radioId AND attemptID = :attemptID AND pendingID = :pendingID AND messageID = :messageID AND phaseRawValue = 0 AND isCompleted = 0")
    suspend fun settle(radioId: UUID, pendingID: UUID, messageID: UUID, attemptID: UUID, phase: Long): Int
    @Query("UPDATE queued_channel_attempts SET isCompleted = 1 WHERE radioId = :radioId AND attemptID = :attemptID AND pendingID = :pendingID AND messageID = :messageID AND phaseRawValue = 1 AND isCompleted = 0")
    suspend fun complete(radioId: UUID, pendingID: UUID, messageID: UUID, attemptID: UUID): Int
    @Query("DELETE FROM queued_channel_attempts WHERE radioId = :radioId")
    suspend fun clearRadio(radioId: UUID): Int
}
