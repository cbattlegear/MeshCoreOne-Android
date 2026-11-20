// AndroidOnly: WP-214 Local classified-reaction journal, independent of target/message deletion.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.UUID

@Entity(tableName = "reaction_queue_state", primaryKeys = ["singleton"])
data class ReactionQueueStateEntity(val singleton: Int = 0, val version: UUID)

@Entity(tableName = "classified_channel_reactions", primaryKeys = ["radioId", "occurrenceID"])
data class ClassifiedReactionEntity(
    val radioId: UUID,
    val occurrenceID: UUID,
    val emoji: String,
    val targetSender: String,
    val messageHash: String,
    val channelIndex: Int,
    val senderName: String,
    val rawText: String,
    val receivedSeconds: Long,
    val receivedNanos: Int,
    val localNodeName: String?,
    val deliveryKind: Int,
    val anchorSeconds: Long?,
    val anchorNanos: Int?,
    val keyOrder: Long,
    val entryOrder: Long,
    val queued: Boolean,
    val targetID: UUID?,
    val targetIncarnation: String?,
    val notificationPending: Boolean,
    val mutation: Int,
)

@Dao
interface ClassifiedReactionQueueDao {
    @Query("SELECT * FROM reaction_queue_state WHERE singleton = 0")
    suspend fun state(): ReactionQueueStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun state(value: ReactionQueueStateEntity)
    @Query("SELECT * FROM classified_channel_reactions ORDER BY entryOrder, radioId, occurrenceID LIMIT 201")
    suspend fun entries(): List<ClassifiedReactionEntity>
    @Query("DELETE FROM classified_channel_reactions")
    suspend fun clear()
    @Query("DELETE FROM classified_channel_reactions WHERE radioId = :radioId")
    suspend fun clearRadio(radioId: UUID)
    @Insert suspend fun insert(entries: List<ClassifiedReactionEntity>)
}
