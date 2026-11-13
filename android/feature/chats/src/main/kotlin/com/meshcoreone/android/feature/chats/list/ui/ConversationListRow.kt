// PortedFrom: MC1/Views/Chats/ConversationRowActions.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ConversationListContent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.ui.AvatarImageCache
import com.meshcoreone.android.core.ui.DeletingRowOverlay
import com.meshcoreone.android.core.ui.RowAction
import com.meshcoreone.android.core.ui.SwipeActionsContainer
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.selectedRowHighlight
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.list.Conversation
import java.time.Clock
import java.time.Instant

/** Stable ids for the shared row actions (swipe/overflow, long-press menu and TalkBack custom actions). */
enum class ConversationAction(val id: String) {
    DELETE("delete"), MUTE("mute"), FAVORITE("favorite");

    companion object {
        fun fromId(id: String): ConversationAction? = entries.firstOrNull { it.id == id }
    }
}

/** Delete and mute need the radio; favorite also waits while a toggle is in flight. */
fun conversationRowActions(
    conversation: Conversation,
    isConnected: Boolean,
    isDeletePending: Boolean,
    isTogglingFavorite: Boolean,
): List<RowAction> = listOf(
    RowAction(ConversationAction.DELETE.id, UiText.Resource(AppChatsStrings.chatsActionDelete), destructive = true,
        enabled = isConnected && !isDeletePending),
    RowAction(
        ConversationAction.MUTE.id,
        UiText.Resource(if (conversation.isMuted) AppChatsStrings.chatsActionUnmute else AppChatsStrings.chatsActionMute),
        destructive = false, enabled = isConnected,
    ),
    RowAction(
        ConversationAction.FAVORITE.id,
        UiText.Resource(if (conversation.isFavorite) AppChatsStrings.chatsActionUnfavorite else AppChatsStrings.chatsActionFavorite),
        destructive = false, enabled = isConnected && !isTogglingFavorite,
    ),
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ConversationListRow(
    model: ConversationRowModel,
    clock: Clock,
    referenceDate: Instant?,
    avatarCache: AvatarImageCache?,
    isSelected: Boolean,
    isDeleting: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit,
    onAction: (ConversationAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val conversation = model.conversation
    val actions = conversationRowActions(conversation, isConnected, isDeleting, model.isTogglingFavorite)
    var menuOpen by remember(conversation.id) { mutableStateOf(false) }
    val description = rowDescription(model)
    DeletingRowOverlay(isDeleting, UiText.Resource(AppChatsStrings.chatsActionDelete), modifier) { enabled ->
        SwipeActionsContainer(
            rowIdentity = conversation.id.toString(),
            actions = actions.snapshot(),
            actionsLabel = UiText.Resource(AppContactsStrings.contactsDiscoveryMenu),
            onAction = { id -> ConversationAction.fromId(id)?.let(onAction) },
        ) {
            Box {
                ConversationRowContent(
                    model, clock, referenceDate, avatarCache,
                    Modifier
                        .selectedRowHighlight(isSelected)
                        .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = { menuOpen = true })
                        .sharedTouchTarget()
                        .padding(horizontal = RowHorizontalPadding, vertical = 6.dp)
                        .semantics(mergeDescendants = true) {
                            contentDescription = description
                            role = Role.Button
                            selected = isSelected
                            onClick { onClick(); true }
                        },
                )
                DropdownMenu(menuOpen, { menuOpen = false }) {
                    for (action in actions) {
                        val kind = ConversationAction.fromId(action.id) ?: continue
                        DropdownMenuItem(
                            text = { Text(com.meshcoreone.android.core.ui.uiString(action.title)) },
                            enabled = action.enabled,
                            onClick = { menuOpen = false; onAction(kind) },
                            modifier = Modifier.sharedTouchTarget(),
                        )
                    }
                }
            }
        }
    }
}

/** One TalkBack utterance per row: name, level, favorite, unread, then the preview. */
@Composable
private fun rowDescription(model: ConversationRowModel): String {
    LocalConfiguration.current
    val resources = LocalContext.current.resources
    val conversation = model.conversation
    val parts = buildList {
        add(model.title)
        when (conversation.notificationLevel) {
            NotificationLevel.MUTED -> add(stringResource(AppChatsStrings.chatsRowMuted))
            NotificationLevel.MENTIONS_ONLY -> add(stringResource(AppChatsStrings.chatsRowMentionsOnly))
            NotificationLevel.ALL -> Unit
        }
        if (conversation.isFavorite) add(stringResource(AppChatsStrings.chatsRowFavorite))
        if (conversation.unreadCount > 0) add(AppLocalizableStrings.tabsChatsUnreadAccessibilityValue(resources, conversation.unreadCount.toInt()))
        if (model.hasFailedSend) add(stringResource(AppChatsStrings.chatsRowFailedSend))
        add(model.preview ?: stringResource(AppChatsStrings.chatsRowNoMessages))
    }
    return parts.joinToString(", ")
}
