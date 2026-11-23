// AndroidOnly: WP-216 Durable outgoing reaction identity and captured wire inputs.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.Query
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID

@Entity(
    tableName = "outgoing_reaction_intents",
    primaryKeys = ["radioId", "messageID"],
    indices = [Index(value = ["radioId", "targetID", "targetIncarnation", "senderKey", "emojiKey"], unique = true)],
)
data class OutgoingReactionIntentEntity(
    val radioId: UUID,
    val messageID: UUID,
    val pendingID: UUID,
    val messageIncarnation: String,
    val pendingIncarnation: String,
    val targetID: UUID,
    val targetIncarnation: String,
    val senderName: String,
    val emoji: String,
    val senderKey: String,
    val emojiKey: String,
    val messageHash: String,
    val rawText: String,
    val channelIndex: Long?,
    val contactID: UUID?,
    val contactPublicKey: Bytes?,
    @ColumnInfo(defaultValue = "0") val completed: Boolean = false,
)

@Dao
interface OutgoingReactionIntentDao {
    @Insert
    suspend fun insert(intent: OutgoingReactionIntentEntity)

    @Query("SELECT * FROM outgoing_reaction_intents WHERE radioId = :radioId AND messageID = :messageID")
    suspend fun forMessage(radioId: UUID, messageID: UUID): OutgoingReactionIntentEntity?

    @Query("""
        SELECT * FROM outgoing_reaction_intents WHERE radioId = :radioId AND targetID = :targetID
        AND targetIncarnation = :incarnation AND senderKey = :sender AND emojiKey = :emoji
    """)
    suspend fun forTarget(
        radioId: UUID, targetID: UUID, incarnation: String, sender: String, emoji: String,
    ): OutgoingReactionIntentEntity?

    @Query("UPDATE outgoing_reaction_intents SET completed = 1 WHERE radioId = :radioId AND messageID = :messageID")
    suspend fun complete(radioId: UUID, messageID: UUID): Int

    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_claims AS claim
            JOIN direct_ack_wires AS wire ON wire.radioId = claim.radioId AND wire.claimId = claim.requestId
            WHERE claim.radioId = :radioId AND claim.messageId = :messageID AND claim.pendingId = :pendingID
              AND claim.incarnation = :messageIncarnation AND claim.pendingIncarnation = :pendingIncarnation
              AND (wire.acceptedCode IS NOT NULL OR claim.phase = 'DELIVERED')
        )
    """)
    suspend fun hasAcceptedDirect(
        radioId: UUID, messageID: UUID, pendingID: UUID, messageIncarnation: String, pendingIncarnation: String,
    ): Boolean

    @Query("""
        UPDATE outgoing_reaction_intents SET senderName = '', emoji = '',
            senderKey = '!redacted:' || messageID, emojiKey = '',
            messageHash = '', rawText = '', contactPublicKey = NULL
        WHERE radioId = :radioId AND targetID = :targetID
    """)
    suspend fun redactTarget(radioId: UUID, targetID: UUID)

    @Query("DELETE FROM outgoing_reaction_intents WHERE radioId = :radioId AND messageID = :messageID")
    suspend fun deleteMessage(radioId: UUID, messageID: UUID)

    @Query("DELETE FROM outgoing_reaction_intents WHERE radioId = :radioId AND contactID = :contactID")
    suspend fun deleteContact(radioId: UUID, contactID: UUID)

    @Query("DELETE FROM outgoing_reaction_intents WHERE radioId = :radioId")
    suspend fun clearRadio(radioId: UUID)
}
