// AndroidOnly: WP-201 Immutable channel submission intent retained independently of pending-row cleanup.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Query
import java.util.UUID
import java.time.Instant

@Entity(tableName = "channel_submissions", primaryKeys = ["pendingID"],
    indices = [Index(value = ["radioId", "messageID"])])
data class ChannelSubmissionEntity(
    val pendingID: UUID,
    val radioId: UUID,
    val messageID: UUID,
    val channelIndex: Long,
    val channelOccupantID: UUID,
    val messageText: String?,
    val messageTimestamp: Long,
    val localNodeName: String?,
    val isResend: Boolean,
    val enqueuedSeconds: Long,
    val enqueuedNanos: Int,
    val sequence: Long,
    val phaseRawValue: Long,
) {
    fun validatedIndex(): UByte = channelIndex.ubyte("channelSubmission.index")
    fun validatedTimestamp(): UInt = messageTimestamp.uint("channelSubmission.timestamp")
    fun enqueuedAt(): Instant = StoredInstant(enqueuedSeconds, enqueuedNanos).toInstant()
}

@Dao
interface ChannelSubmissionDao : RowWriter<ChannelSubmissionEntity> {
    @Query("SELECT * FROM channel_submissions WHERE pendingID = :pendingID")
    suspend fun byId(pendingID: UUID): ChannelSubmissionEntity?
    @Query("SELECT EXISTS(SELECT 1 FROM pending_sends WHERE id = :pendingID) OR EXISTS(SELECT 1 FROM queued_channel_attempts WHERE pendingID = :pendingID)")
    suspend fun hasUnreceiptedIdentity(pendingID: UUID): Boolean
    @Query("UPDATE channel_submissions SET phaseRawValue = :phase WHERE pendingID = :pendingID AND radioId = :radioId AND messageID = :messageID AND phaseRawValue = 0")
    suspend fun finish(pendingID: UUID, radioId: UUID, messageID: UUID, phase: Long): Int
    @Query("UPDATE channel_submissions SET messageText = NULL, localNodeName = NULL, phaseRawValue = 3 WHERE radioId = :radioId AND messageID = :messageID")
    suspend fun redactMessage(radioId: UUID, messageID: UUID): Int
    @Query("UPDATE channel_submissions SET messageText = NULL, localNodeName = NULL, phaseRawValue = 3 WHERE radioId = :radioId")
    suspend fun redactRadio(radioId: UUID): Int
}
