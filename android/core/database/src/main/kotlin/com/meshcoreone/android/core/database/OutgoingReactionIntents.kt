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
    indices = [Index(value = ["radioId", "targetID", "targetIncarnation", "targetFingerprint", "senderKey", "emojiKey"], unique = true)],
)
data class OutgoingReactionIntentEntity(
    val radioId: UUID,
    val messageID: UUID,
    val pendingID: UUID,
    val messageIncarnation: String,
    val pendingIncarnation: String,
    val targetID: UUID,
    val targetIncarnation: String,
    val targetFingerprint: String,
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
        AND targetIncarnation = :incarnation AND targetFingerprint = :fingerprint
        AND senderKey = :sender AND emojiKey = :emoji
    """)
    suspend fun forTarget(
        radioId: UUID, targetID: UUID, incarnation: String, fingerprint: String, sender: String, emoji: String,
    ): OutgoingReactionIntentEntity?

    @Query("UPDATE outgoing_reaction_intents SET completed = 1 WHERE radioId = :radioId AND messageID = :messageID")
    suspend fun complete(radioId: UUID, messageID: UUID): Int

    @Query("""
        UPDATE outgoing_reaction_intents SET pendingIncarnation = :next
        WHERE radioId = :radioId AND messageID = :messageID AND pendingID = :pendingID
          AND pendingIncarnation = :previous
    """)
    suspend fun renewPendingIncarnation(
        radioId: UUID, messageID: UUID, pendingID: UUID, previous: String, next: String,
    ): Int

    @Query("""
        UPDATE outgoing_reaction_intents SET pendingID = :nextID, pendingIncarnation = :nextIncarnation
        WHERE radioId = :radioId AND messageID = :messageID AND pendingID = :previousID
          AND pendingIncarnation = :previousIncarnation
    """)
    suspend fun rebindPending(
        radioId: UUID, messageID: UUID, previousID: UUID, previousIncarnation: String,
        nextID: UUID, nextIncarnation: String,
    ): Int

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
        SELECT EXISTS(
            SELECT 1 FROM direct_ack_claims AS claim
            JOIN direct_ack_wires AS wire ON wire.radioId = claim.radioId AND wire.claimId = claim.requestId
            WHERE claim.radioId = :radioId AND claim.messageId = :messageID
              AND (wire.acceptedCode IS NOT NULL OR claim.phase = 'DELIVERED' OR NOT EXISTS (
                  SELECT 1 FROM direct_ack_operations AS operation
                  WHERE operation.radioId = wire.radioId AND operation.claimId = wire.claimId
                    AND operation.kind = ('DEFINITELY_UNSENT:' || wire.requestId) COLLATE NOCASE
              ))
        )
    """)
    suspend fun hasPossiblyDispatchedDirect(radioId: UUID, messageID: UUID): Boolean

    @Query("SELECT * FROM outgoing_reaction_intents WHERE radioId = :radioId AND targetID = :targetID")
    suspend fun forTargetMessage(radioId: UUID, targetID: UUID): List<OutgoingReactionIntentEntity>

    @Query("""
        UPDATE outgoing_reaction_intents SET senderName = '', emoji = '',
            senderKey = '!redacted:' || messageID, emojiKey = '',
            targetFingerprint = '', messageHash = '', rawText = :wireFingerprint, contactPublicKey = NULL
        WHERE radioId = :radioId AND messageID = :messageID
    """)
    suspend fun redactIntent(radioId: UUID, messageID: UUID, wireFingerprint: String)

    @Query("DELETE FROM outgoing_reaction_intents WHERE radioId = :radioId AND messageID = :messageID")
    suspend fun deleteMessage(radioId: UUID, messageID: UUID)

    @Query("DELETE FROM outgoing_reaction_intents WHERE radioId = :radioId AND contactID = :contactID")
    suspend fun deleteContact(radioId: UUID, contactID: UUID)

    @Query("DELETE FROM outgoing_reaction_intents WHERE radioId = :radioId")
    suspend fun clearRadio(radioId: UUID)
}
