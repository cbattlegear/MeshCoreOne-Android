// AndroidOnly: WP-309 Issue #729 local-only rule settings, conversation incarnations and bounded archive.
package com.meshcoreone.android.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import java.util.UUID
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "text_rule_preferences")
data class TextRulePreferenceEntity(@androidx.room.PrimaryKey val singleton: Int = 1, val payload: String)

@Entity(tableName = "text_rule_conversations", primaryKeys = ["radioId", "family", "parentID"])
data class TextRuleConversationEntity(
    val radioId: UUID, val family: String, val parentID: UUID, val fingerprint: String,
    val identity: UUID, val override: String,
)

@Entity(tableName = "recently_blocked_texts")
data class RecentlyBlockedTextEntity(
    @androidx.room.PrimaryKey val id: UUID,
    val radioId: UUID, val conversationID: UUID?, val contactID: UUID?,
    val senderPrefix: Bytes?,
    val arrivedSeconds: Long, val arrivedNanos: Int, val payload: String,
)

@Dao
interface TextRuleDao {
    @Query("SELECT * FROM text_rule_preferences WHERE singleton = 1")
    suspend fun preference(): TextRulePreferenceEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun preference(value: TextRulePreferenceEntity)
    @Query("SELECT * FROM text_rule_conversations")
    suspend fun conversations(): List<TextRuleConversationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun conversation(value: TextRuleConversationEntity)
    @Query("DELETE FROM text_rule_conversations WHERE radioId = :radio AND family = :family AND parentID = :parent")
    suspend fun deleteConversation(radio: UUID, family: String, parent: UUID)
    @Query("SELECT * FROM recently_blocked_texts ORDER BY arrivedSeconds DESC, arrivedNanos DESC, id DESC")
    suspend fun archive(): List<RecentlyBlockedTextEntity>
    @Query("SELECT id FROM recently_blocked_texts")
    fun observeArchive(): Flow<List<UUID>>
    @Insert
    suspend fun archive(value: RecentlyBlockedTextEntity)
    @Query("DELETE FROM recently_blocked_texts WHERE id = :id")
    suspend fun deleteArchive(id: UUID)
    @Query("DELETE FROM recently_blocked_texts WHERE radioId = :radio")
    suspend fun clearRadio(radio: UUID)
    @Query("SELECT EXISTS(SELECT 1 FROM recently_blocked_texts WHERE radioId = :radio AND contactID = :contact)")
    suspend fun referencesContact(radio: UUID, contact: UUID): Boolean
}
