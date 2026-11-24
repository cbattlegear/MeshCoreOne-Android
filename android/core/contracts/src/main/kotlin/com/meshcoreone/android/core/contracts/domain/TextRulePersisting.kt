// AndroidOnly: WP-309 Issue #729 installation-wide rules and atomic receive admission.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import java.time.Instant
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class TextRuleAdmission { DISCARDED, REPLAY, SAVED, SAVED_BLOCKED, SAVED_CHANNEL_STALE, REACTION }
data class TextRuleRoomAdmission(val saved: Boolean, val recovered: Boolean)

interface TextRulePersisting {
    val textRuleChanges: Flow<Unit>
    val textRuleLoadFailed: StateFlow<Boolean>
    suspend fun textRulesPreference(): TextRulesPreference
    suspend fun updateTextRules(update: (TextRulesPreference) -> TextRulesPreference)
    suspend fun textRuleConversations(): List<TextRuleConversation>
    suspend fun setTextRuleOverride(key: TextRuleKey, value: TextRuleOverride)
    suspend fun recentlyBlockedTexts(): List<RecentlyBlockedText>
    /**
     * Contact/room inputs retain their captured receive incarnation. [onCommitted] is bounded,
     * non-throwing bookkeeping invoked after commit, even if the caller is then cancelled.
     */
    suspend fun admitTextMessage(
        message: MessageDTO, contact: ContactDTO?, channel: ChannelDTO?,
        wireText: String, wireTextType: UByte, isReaction: Boolean,
        receivedAt: Instant, isViewingConversation: Boolean,
        onCommitted: (TextRuleAdmission) -> Unit = {},
    ): TextRuleAdmission
    /** Recheck a direct receive's contact incarnation and, for saved text, its message row. */
    suspend fun currentDirectTextContact(message: MessageDTO, contact: ContactDTO, messageSaved: Boolean): ContactDTO?
    suspend fun admitRoomText(
        message: RoomMessageDTO, room: RemoteNodeSessionDTO,
        onCommitted: (TextRuleRoomAdmission) -> Unit = {},
    ): TextRuleRoomAdmission
    /** Recheck a room receive's incarnation and, when saved, the exact message row. */
    suspend fun currentRoomTextSession(room: RemoteNodeSessionDTO, message: RoomMessageDTO?): RemoteNodeSessionDTO?
}
