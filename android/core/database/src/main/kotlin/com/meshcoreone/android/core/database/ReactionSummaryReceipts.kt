// AndroidOnly: WP-214 Atomic reaction-summary operation receipts, scoped to a target incarnation.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import java.util.UUID

@Entity(
    tableName = "reaction_summary_receipts",
    primaryKeys = ["radioId", "operationID"],
    foreignKeys = [ForeignKey(
        entity = MessageEntity::class, parentColumns = ["radioId", "id"],
        childColumns = ["radioId", "messageID"], onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index(value = ["radioId", "messageID"])],
)
data class ReactionSummaryReceiptEntity(
    val radioId: UUID,
    val operationID: UUID,
    val messageID: UUID,
    val targetIncarnation: String,
    val summary: String?,
)

@Dao
interface ReactionSummaryReceiptDao {
    @Insert suspend fun insert(receipt: ReactionSummaryReceiptEntity)
    @Query("SELECT * FROM reaction_summary_receipts WHERE radioId = :radioId AND operationID = :operationID")
    suspend fun byOperation(radioId: UUID, operationID: UUID): ReactionSummaryReceiptEntity?
}
