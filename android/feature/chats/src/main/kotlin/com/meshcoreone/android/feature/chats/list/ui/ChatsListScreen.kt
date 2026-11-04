// PortedFrom: MC1/Views/Chats/ChatsView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ConversationListContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ChatFilterPicker.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ChatsListModifiers.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.ui.AvatarImageCache
import com.meshcoreone.android.core.ui.ErrorBanner
import com.meshcoreone.android.core.ui.FilterChoice
import com.meshcoreone.android.core.ui.GlassFilterBar
import com.meshcoreone.android.core.ui.UiErrorState
import com.meshcoreone.android.core.ui.UiRecovery
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.list.ChatFilter
import com.meshcoreone.android.feature.chats.list.ChatListMessage
import com.meshcoreone.android.feature.chats.list.ChatListState
import com.meshcoreone.android.feature.chats.list.ChatRoute
import com.meshcoreone.android.feature.chats.list.Conversation
import com.meshcoreone.android.feature.chats.list.filtered
import java.time.Clock
import java.util.UUID

/** Every user intent the list emits; the root maps these onto the state holder and shell navigation. */
data class ChatsListCallbacks(
    val onSearchChange: (String) -> Unit,
    val onFilterChange: (ChatFilter) -> Unit,
    val onOpen: (ChatRoute) -> Unit,
    val onAction: (Conversation, ConversationAction) -> Unit,
    val onNewChat: () -> Unit,
    val onNewChannel: () -> Unit,
    val onDismissBanner: () -> Unit,
    val onOpenUnresolvedMessages: (() -> Unit)? = null,
)

private val bannerId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000306")

/** Stateless Chats list: top bar, search, filter chips, rows with swipe/long-press actions and empty/loading/error states. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatsListScreen(
    state: ChatListState,
    isConnected: Boolean,
    selectedRoute: ChatRoute?,
    clock: Clock,
    callbacks: ChatsListCallbacks,
    messageText: @Composable (ChatListMessage) -> UiText,
    modifier: Modifier = Modifier,
    avatarCache: AvatarImageCache? = null,
) {
    val strings = rememberChatListStrings()
    val favorites = remember(state.snapshot, state.selectedFilter, state.searchText, strings) {
        state.favoriteConversations.filtered(state.selectedFilter, state.searchText, strings)
    }
    val others = remember(state.snapshot, state.selectedFilter, state.searchText, strings) {
        state.nonFavoriteConversations.filtered(state.selectedFilter, state.searchText, strings)
    }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(AppChatsStrings.chatsTitle), Modifier.semantics { heading() }) },
                actions = { NewMessageMenu(callbacks.onNewChat, callbacks.onNewChannel) },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            callbacks.onOpenUnresolvedMessages?.let { open ->
                ListItem(
                    headlineContent = { Text(stringResource(com.meshcoreone.android.core.l10n.R.string.unresolved_messages_title)) },
                    supportingContent = { Text(stringResource(com.meshcoreone.android.core.l10n.R.string.unresolved_messages_entry)) },
                    modifier = Modifier.fillMaxWidth().sharedTouchTarget().clickable(role = Role.Button, onClick = open),
                )
            }
            SearchField(state.searchText, callbacks.onSearchChange)
            FilterBar(state.selectedFilter, state.searchText.isNotEmpty(), callbacks.onFilterChange)
            state.errorBanner?.let { message ->
                ErrorBanner(
                    UiErrorState(bannerId, "chats.list.banner", messageText(message), UiRecovery.RETRY),
                    callbacks.onDismissBanner, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            when {
                !state.hasLoadedOnce -> LoadingBody()
                favorites.isEmpty() && others.isEmpty() ->
                    EmptyBody(state.selectedFilter, state.searchText.isNotEmpty()) { callbacks.onFilterChange(ChatFilter.ALL) }
                else -> ConversationList(favorites + others, state, isConnected, selectedRoute, clock, strings, avatarCache, callbacks)
            }
        }
    }
}

@Composable
private fun ConversationList(
    rows: List<Conversation>,
    state: ChatListState,
    isConnected: Boolean,
    selectedRoute: ChatRoute?,
    clock: Clock,
    strings: com.meshcoreone.android.feature.chats.list.ChatListStrings,
    avatarCache: AvatarImageCache?,
    callbacks: ChatsListCallbacks,
) {
    val referenceDate = remember(clock, rows) { clock.instant() }
    LazyColumn(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))) {
        items(rows, key = { it.id }) { conversation ->
            val route = ChatRoute.from(conversation)
            Column {
                ConversationListRow(
                    model = ConversationRowModel(
                        conversation, conversation.displayName(strings), state.lastMessagePreview(conversation.id),
                        state.conversationHasFailedSend(conversation.id), state.togglingFavoriteId == conversation.id,
                    ),
                    clock = clock, referenceDate = referenceDate, avatarCache = avatarCache,
                    isSelected = selectedRoute == route,
                    isDeleting = conversation.id in state.deletingIds,
                    isConnected = isConnected,
                    onClick = { callbacks.onOpen(route) },
                    onAction = { callbacks.onAction(conversation, it) },
                )
                HorizontalDivider(Modifier.padding(start = 72.dp))
            }
        }
    }
}

@Composable
private fun NewMessageMenu(onNewChat: () -> Unit, onNewChannel: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton({ open = true }, Modifier.sharedTouchTarget()) {
            Icon(MeshSymbol.MESSAGES.vector, stringResource(AppChatsStrings.chatsComposeNewMessage))
        }
        DropdownMenu(open, { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(AppChatsStrings.chatsComposeNewChat)) },
                onClick = { open = false; onNewChat() }, modifier = Modifier.sharedTouchTarget(),
            )
            DropdownMenuItem(
                text = { Text(stringResource(AppChatsStrings.chatsComposeNewChannel)) },
                onClick = { open = false; onNewChannel() }, modifier = Modifier.sharedTouchTarget(),
            )
        }
    }
}

@Composable
private fun SearchField(text: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = text, onValueChange = onChange, singleLine = true,
        placeholder = { Text(stringResource(AppChatsStrings.chatsSearchPlaceholder)) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).sharedTouchTarget(),
    )
}

@Composable
private fun FilterBar(selected: ChatFilter, isSearching: Boolean, onSelect: (ChatFilter) -> Unit) {
    val choices = remember {
        ChatFilter.entries.map { FilterChoice(it, UiText.Resource(it.labelResource())) }.snapshot()
    }
    GlassFilterBar(choices, selected, isSearching, UiText.Resource(AppChatsStrings.chatsFilterTitle), onSelect,
        Modifier.padding(vertical = 4.dp))
}

internal fun ChatFilter.labelResource(): Int = when (this) {
    ChatFilter.ALL -> AppChatsStrings.chatsFilterAll
    ChatFilter.UNREAD -> AppChatsStrings.chatsFilterUnread
    ChatFilter.DIRECT_MESSAGES -> AppChatsStrings.chatsFilterDirectMessages
    ChatFilter.CHANNELS -> AppChatsStrings.chatsFilterChannels
    ChatFilter.ROOMS -> AppChatsStrings.chatsFilterRooms
}

@Composable
private fun LoadingBody() {
    Box(Modifier.fillMaxSize().semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate }, Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun EmptyBody(filter: ChatFilter, isSearching: Boolean, onClearFilter: () -> Unit) {
    val (title, description) = when (filter) {
        ChatFilter.ALL -> AppChatsStrings.chatsEmptyStateNoConversationsTitle to AppChatsStrings.chatsEmptyStateNoConversationsDescription
        ChatFilter.UNREAD -> AppChatsStrings.chatsEmptyStateNoUnreadTitle to AppChatsStrings.chatsEmptyStateNoUnreadDescription
        ChatFilter.DIRECT_MESSAGES -> AppChatsStrings.chatsEmptyStateNoDirectMessagesTitle to AppChatsStrings.chatsEmptyStateNoDirectMessagesDescription
        ChatFilter.CHANNELS -> AppChatsStrings.chatsEmptyStateNoChannelsTitle to AppChatsStrings.chatsEmptyStateNoChannelsDescription
        ChatFilter.ROOMS -> AppChatsStrings.chatsEmptyStateNoRoomsTitle to AppChatsStrings.chatsEmptyStateNoRoomsDescription
    }
    Column(Modifier.fillMaxSize().padding(24.dp), Arrangement.Center, Alignment.CenterHorizontally) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(description), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (filter != ChatFilter.ALL && !isSearching) {
            TextButton(onClearFilter, Modifier.sharedTouchTarget()) { Text(stringResource(AppChatsStrings.chatsFilterClear)) }
        }
    }
}
