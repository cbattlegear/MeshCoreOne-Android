// AndroidOnly: WP-309 Issue #729 installation-wide rules and atomic receive admission.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class TextRuleAdmission { DISCARDED, REPLAY, SAVED, SAVED_BLOCKED, SAVED_CHANNEL_STALE, REACTION }
data class TextRuleRoomAdmission(
    val saved: Boolean,
    val recovered: Boolean,
    val messageIncarnation: String? = null,
)

interface TextRulePersisting {
    val textRuleChanges: Flow<Unit>
    val textRuleLoadFailed: StateFlow<Boolean>
    suspend fun textRulesPreference(): TextRulesPreference
    suspend fun updateTextRules(update: (TextRulesPreference) -> TextRulesPreference)
    suspend fun textRuleConversations(): List<TextRuleConversation>
    suspend fun setTextRuleOverride(key: TextRuleKey, value: TextRuleOverride)
    suspend fun recentlyBlockedTexts(): List<RecentlyBlockedText>
    /**
     * Contact inputs retain their captured receive incarnation. [onCommitted] is bounded,
     * non-throwing bookkeeping invoked after commit, even if the caller is then cancelled. Its
     * second argument is the committed message incarnation for a saved message.
     */
    suspend fun admitTextMessage(
        message: MessageDTO, contact: ContactDTO?, channel: ChannelDTO?,
        wireText: String, wireTextType: UByte, isReaction: Boolean,
        receivedAt: Instant, isViewingConversation: Boolean,
        onCommitted: (TextRuleAdmission, String?) -> Unit = { _, _ -> },
    ): TextRuleAdmission
    /** Recheck the contact and the saved message's incarnation and immutable receive fields. */
    suspend fun currentDirectTextContact(
        message: MessageDTO, contact: ContactDTO, messageSaved: Boolean, messageIncarnation: String?,
    ): ContactDTO?
    /** Recheck a saved direct message without assuming it already has a contact. */
    suspend fun isCurrentDirectTextMessage(message: MessageDTO, messageIncarnation: String?): Boolean
    suspend fun admitRoomText(
        message: RoomMessageDTO, room: RemoteNodeSessionDTO,
        onCommitted: (TextRuleRoomAdmission) -> Unit = {},
    ): TextRuleRoomAdmission
    /** Recheck a room receive's incarnation and, when saved, the captured message incarnation. */
    suspend fun currentRoomTextSession(
        room: RemoteNodeSessionDTO, message: RoomMessageDTO?, messageIncarnation: String?,
    ): RemoteNodeSessionDTO?
}
