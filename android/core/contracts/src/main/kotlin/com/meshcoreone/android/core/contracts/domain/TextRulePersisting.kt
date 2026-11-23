// AndroidOnly: WP-309 Issue #729 installation-wide rules and atomic receive admission.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class TextRuleAdmission { DISCARDED, REPLAY, SAVED, REACTION }
data class TextRuleRoomAdmission(val saved: Boolean, val recovered: Boolean)

interface TextRulePersisting {
    val textRuleChanges: Flow<Unit>
    val textRuleLoadFailed: StateFlow<Boolean>
    suspend fun textRulesPreference(): TextRulesPreference
    suspend fun updateTextRules(update: (TextRulesPreference) -> TextRulesPreference)
    suspend fun textRuleConversations(): List<TextRuleConversation>
    suspend fun setTextRuleOverride(key: TextRuleKey, value: TextRuleOverride)
    suspend fun recentlyBlockedTexts(): List<RecentlyBlockedText>
    /** Contact/room inputs must retain the receive incarnation captured by the store's lookup transaction. */
    suspend fun admitTextMessage(
        message: MessageDTO, contact: ContactDTO?, channel: ChannelDTO?,
        wireText: String, wireTextType: UByte, isReaction: Boolean,
    ): TextRuleAdmission
    suspend fun admitRoomText(message: RoomMessageDTO, room: RemoteNodeSessionDTO): TextRuleRoomAdmission
}
