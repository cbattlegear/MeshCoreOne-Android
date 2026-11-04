// AndroidOnly: WP-306 Binds ChatListFeatureDependencies to the stateless list: holder lifecycle, pending navigation, dialogs.
package com.meshcoreone.android.feature.chats.list.ui

import androidx.activity.compose.BackHandler

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.ui.AvatarImageCache
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.chats.list.ChatListActions
import com.meshcoreone.android.feature.chats.list.ChatListFeatureDependencies
import com.meshcoreone.android.feature.chats.list.ChatListMessage
import com.meshcoreone.android.feature.chats.list.ChatListStateHolder
import com.meshcoreone.android.feature.chats.list.ChatRoute
import com.meshcoreone.android.feature.chats.list.ConversationActionError
import com.meshcoreone.android.feature.chats.list.NewChatStateHolder
import com.meshcoreone.android.feature.chats.list.UnresolvedMessagesStateHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

/**
 * Hosts the Chats list for an app that bound [dependencies]. Navigation is delegated: [onOpenChat]
 * receives the route (timeline/composer are WP-307/308), [onRequestRoomAuth] is called for a
 * disconnected room, [onNewChannel] opens the channel-options flow owned elsewhere.
 */
@Composable
fun ChatsListRoot(
    dependencies: ChatListFeatureDependencies,
    selectedRoute: ChatRoute?,
    onOpenChat: (ChatRoute) -> Unit,
    onClearSelection: () -> Unit,
    onNewChannel: () -> Unit,
    onRoomAuthenticationRequested: (com.meshcoreone.android.core.model.RemoteNodeSessionDTO) -> Unit,
    failureText: @Composable (Throwable) -> UiText,
    modifier: Modifier = Modifier,
    avatarCache: AvatarImageCache? = null,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies) { ChatListStateHolder(dependencies, scope) }
    val newChat = remember(dependencies) { NewChatStateHolder(dependencies) }
    val currentSelection by rememberUpdatedState(selectedRoute)
    val currentOpen by rememberUpdatedState(onOpenChat)
    val currentClear by rememberUpdatedState(onClearSelection)
    val actions = remember(holder) {
        ChatListActions(holder, dependencies, scope, navigate = { currentOpen(it) },
            clearNavigationIfActive = { if (currentSelection == it) currentClear() })
    }
    val state by holder.state.collectAsState()
    val connection by dependencies.connectionState.collectAsState()
    val radioId by dependencies.currentRadioId.collectAsState()
    var showingNewChat by remember { mutableStateOf(false) }
    var showingUnresolved by rememberSaveable(radioId?.value?.toString()) { mutableStateOf(false) }
    val unresolved = remember(dependencies, radioId) {
        UnresolvedMessagesStateHolder(radioId, dependencies.data, dependencies.diagnostics)
    }
    val unresolvedState by unresolved.state.collectAsState()
    val view = LocalView.current
    val offlineAnnouncement = stringResource(AppChatsStrings.chatsAccessibilityOfflineAnnouncement)

    LaunchedEffect(holder) {
        holder.start()
        actions.consumePendingRoomAuthentication()
        holder.requestConversationReload()?.join()
        if (actions.shouldAnnounceOfflineState()) view.announceForAccessibility(offlineAnnouncement)
        actions.handlePendingNavigation()
        actions.handlePendingChannelNavigation()
        actions.handlePendingRoomNavigation()
    }
    val pendingContact by dependencies.navigation.pendingChatContact.collectAsState()
    val pendingChannel by dependencies.navigation.pendingChannel.collectAsState()
    val pendingRoom by dependencies.navigation.pendingRoomSession.collectAsState()
    val pendingAuth by dependencies.navigation.pendingRoomAuthentication.collectAsState()
    LaunchedEffect(pendingContact) { actions.handlePendingNavigation() }
    LaunchedEffect(pendingChannel) { actions.handlePendingChannelNavigation() }
    LaunchedEffect(pendingRoom) { actions.handlePendingRoomNavigation() }
    LaunchedEffect(pendingAuth) { actions.consumePendingRoomAuthentication() }
    LaunchedEffect(state.roomToAuthenticate) {
        state.roomToAuthenticate?.let { onRoomAuthenticationRequested(it); holder.showRoomAuthentication(null) }
    }

    val callbacks = remember(holder, actions) {
        ChatsListCallbacks(
            onSearchChange = holder::setSearchText,
            onFilterChange = holder::setFilter,
            onOpen = actions::open,
            onAction = { conversation, action ->
                when (action) {
                    ConversationAction.DELETE -> actions.handleDeleteConversation(conversation)
                    ConversationAction.MUTE -> scope.launchHolder { holder.toggleMute(conversation) }
                    ConversationAction.FAVORITE -> scope.launchHolder { holder.toggleFavorite(conversation) }
                }
            },
            onNewChat = { showingNewChat = true },
            onNewChannel = onNewChannel,
            onDismissBanner = { holder.errorBannerMessage = null },
            onOpenUnresolvedMessages = { showingUnresolved = true },
        )
    }
    val messageText: @Composable (ChatListMessage) -> UiText = { message -> chatMessageText(message, failureText) }
    if (showingUnresolved) {
        BackHandler { showingUnresolved = false }
        LaunchedEffect(unresolved) {
            merge(dependencies.reloadRequests, dependencies.messageEvents.map { Unit })
                .onStart { emit(Unit) }.collectLatest { unresolved.refresh() }
        }
        UnresolvedMessagesScreen(
            unresolvedState,
            onBack = { showingUnresolved = false },
            onRefresh = { scope.launch { unresolved.refresh() } },
            onLoadMore = { scope.launch { unresolved.loadMore() } },
            modifier = modifier,
        )
    } else {
        ChatsListScreen(state, connection == DeviceConnectionState.READY, selectedRoute, dependencies.clock, callbacks,
            messageText, modifier, avatarCache)
    }

    ChatsDialogs(state, holder, actions, messageText)
    if (showingNewChat) {
        NewChatSheet(newChat, radioId,
            onSelectContact = { showingNewChat = false; actions.open(ChatRoute.Direct(it)) },
            onDismiss = { showingNewChat = false }, avatarCache = avatarCache)
    }
}

private fun CoroutineScope.launchHolder(block: suspend () -> Unit) {
    launch { block() }
}

@Composable
private fun chatMessageText(message: ChatListMessage, failureText: @Composable (Throwable) -> UiText): UiText = when (message) {
    ChatListMessage.LoadConversationsFailed -> UiText.Resource(AppChatsStrings.chatsErrorLoadConversationsFailed)
    is ChatListMessage.Verbatim -> UiText.Verbatim(message.text)
    is ChatListMessage.Failure -> chatListFailureText(message.cause) ?: failureText(message.cause)
}

internal fun chatListFailureText(failure: Throwable): UiText? = when (failure) {
    ConversationActionError.NotConnected -> UiText.Resource(AppChatsStrings.chatsErrorNotConnectedToDelete)
    else -> null
}

@Composable
private fun ChatsDialogs(
    state: com.meshcoreone.android.feature.chats.list.ChatListState,
    holder: ChatListStateHolder,
    actions: ChatListActions,
    messageText: @Composable (ChatListMessage) -> UiText,
) {
    val scope = rememberCoroutineScope()
    state.errorAlert?.let { message ->
        AlertDialog(
            onDismissRequest = { holder.showError(null) },
            confirmButton = { TextButton({ holder.showError(null) }, Modifier.sharedTouchTarget()) { Text(stringResource(AppChatsStrings.chatsCommonOk)) } },
            text = { Text(uiString(messageText(message))) },
        )
    }
    state.channelDeleteFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = { holder.showChannelDeleteFailure(null) },
            title = { Text(stringResource(AppChatsStrings.chatsChannelInfoDeleteFailedTitle)) },
            text = { Text(uiString(messageText(failure.message))) },
            confirmButton = {
                TextButton({ holder.showChannelDeleteFailure(null); actions.deleteChannelConversation(failure.channel) }, Modifier.sharedTouchTarget()) {
                    Text(stringResource(AppLocalizableStrings.commonTryAgain))
                }
            },
            dismissButton = { TextButton({ holder.showChannelDeleteFailure(null) }, Modifier.sharedTouchTarget()) { Text(stringResource(AppChatsStrings.chatsCommonOk)) } },
        )
    }
    state.roomToDelete?.let { session ->
        AlertDialog(
            onDismissRequest = { holder.showRoomToDelete(null) },
            title = { Text(stringResource(AppChatsStrings.chatsAlertLeaveRoomTitle)) },
            text = { Text(stringResource(AppChatsStrings.chatsAlertLeaveRoomMessage)) },
            confirmButton = {
                TextButton({ holder.showRoomToDelete(null); scope.launchHolder { actions.deleteRoom(session) } }, Modifier.sharedTouchTarget()) {
                    Text(stringResource(AppChatsStrings.chatsAlertLeaveRoomConfirm))
                }
            },
            dismissButton = { TextButton({ holder.showRoomToDelete(null) }, Modifier.sharedTouchTarget()) { Text(stringResource(AppChatsStrings.chatsCommonCancel)) } },
        )
    }
}
