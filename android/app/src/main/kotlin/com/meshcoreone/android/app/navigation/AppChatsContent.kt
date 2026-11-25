// AndroidOnly: WP-407 Native conversation registration; screen scopes never create or close a radio connection.
// AndroidOnly: WP-310 Bind room body actions to the shared link dispatcher/router and radio-scoped contacts.
// AndroidOnly: WP-307 Restore pending replies on composer initialization or notification-source replacement.
// AndroidOnly: WP-308 Bind the frozen conversation-specific prompt to the mounted composer.
// AndroidOnly: WP-306 Explicit mailto tap action and visible native-handler failures.
package com.meshcoreone.android.app.navigation

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.meshcoreone.android.app.container.AppChatsFeature
import com.meshcoreone.android.app.content.LocationManagerLocationProducing
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.feature.chats.ChatsEntry
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.composer.ui.ChatConversationScreen
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import com.meshcoreone.android.feature.chats.list.ContactLinkLookupIssue
import com.meshcoreone.android.feature.chats.list.ChatRoute
import com.meshcoreone.android.feature.chats.rooms.RoomConversationRoute
import com.meshcoreone.android.feature.chats.rooms.RoomConversationStateHolder
import com.meshcoreone.android.feature.chats.timeline.ChatViewModel
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch

internal val ChannelMapPolicyKey = SemanticsPropertyKey<String>("ChannelMapPolicy")
private var SemanticsPropertyReceiver.channelMapPolicy by ChannelMapPolicyKey
internal class ChannelMapPolicy(initial: Boolean) {
    private val mutableState = MutableStateFlow(0L to initial)
    val state = mutableState.asStateFlow()
    private val mutableObservationGeneration = MutableStateFlow(0L)
    val observationGeneration = mutableObservationGeneration.asStateFlow()
    private val mutableAdmissionFailureVersion = MutableStateFlow(0L)
    val admissionFailureVersion = mutableAdmissionFailureVersion.asStateFlow()
    private var version = 0L

    @Synchronized fun invalidate(): Long {
        version++
        mutableState.value = version to false
        return version
    }

    @Synchronized fun resolve(version: Long, allowed: Boolean): Boolean {
        if (version != this.version) return false
        mutableState.value = version to allowed
        return true
    }

    fun isAllowed() = mutableState.value.second
    @Synchronized fun admissionReadSucceeded(version: Long) {
        if (version == this.version && mutableAdmissionFailureVersion.value != 0L) {
            mutableAdmissionFailureVersion.value = 0L
            mutableObservationGeneration.value += 1
        }
    }
    @Synchronized fun admissionReadFailure() {
        mutableAdmissionFailureVersion.value = invalidate()
    }

    @Synchronized fun admissionRejected() {
        invalidate()
        mutableObservationGeneration.value += 1
    }
}

internal fun ChatSelection.route(): ChatRoute = when (this) {
    is ChatSelection.Direct -> ChatRoute.Direct(contact)
    is ChatSelection.Channel -> ChatRoute.Channel(channel)
    is ChatSelection.Room -> ChatRoute.Room(session)
}

internal fun NavigationCoordinator.openChat(route: ChatRoute) {
    if (state.value.chatsSelectedRoute?.route() == route &&
        state.value.stacks.getValue(com.meshcoreone.android.core.contracts.AppTab.CHATS)
            .last().destination is NavigationDestination.Chat
    ) return
    when (route) {
        is ChatRoute.Direct -> navigateToChat(route.contact)
        is ChatRoute.Channel -> navigateToChannel(route.channel)
        is ChatRoute.Room -> navigateToRoom(route.session)
    }
}

@Composable
internal fun AppChatsContent(
    feature: AppChatsFeature,
    destination: NavigationDestination,
    coordinator: NavigationCoordinator,
) {
    val state by coordinator.state.collectAsState()
    val presented = presentedNavigation(coordinator)
    val entryId = LocalNavigationEntryId.current
    var newChannel by remember { mutableStateOf(false) }
    if (newChannel) AppChannelsContent(feature, coordinator) { newChannel = false }
    val selected = (destination as? NavigationDestination.Chat)?.selection
    if (selected == null) {
        val radio by feature.currentRadioId.collectAsState()
        if (radio == null) {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
            ) {
                androidx.compose.material3.Text(stringResource(AppChatsStrings.chatsEmptyStateNoConversationsTitle))
                androidx.compose.material3.Text(stringResource(AppChatsStrings.chatsEmptyStateNoConversationsDescription))
                androidx.compose.material3.TextButton(onClick = {
                    coordinator.navigate(FeatureRoute(FeatureId.ONBOARDING))
                }) { androidx.compose.material3.Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppSettingsStrings.deviceConnect)) }
            }
            return
        }
        val list = remember(feature, radio) { feature.listFor(radio) }
        ChatsEntry(
            FeatureRoute(FeatureId.CHATS), coordinator::navigate, list,
            selectedRoute = state.chatsSelectedRoute?.route(),
            onOpenChat = coordinator::openChat,
            onClearSelection = { coordinator.back(presented) },
            onNewChannel = { newChannel = true },
            onRoomAuthenticationRequested = coordinator::requestRoomAuthentication,
            failureText = { channelFailureText(it) },
            consumePendingRoomAuthentication = false,
        )
        return
    }
    if (selected is ChatSelection.Channel) {
        val availability = remember(feature, selected, entryId) { feature.channelMountAvailability(selected.channel) }
        val available by availability.collectAsStateWithLifecycle(initialValue = null)
        if (available != true) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                if (available == null) CircularProgressIndicator()
                else Text(
                    stringResource(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.errorPersistenceChannelNotFound),
                    modifier = Modifier.testTag("channel-unavailable"),
                )
            }
            return
        }
    }
    key(selected) { AppChatConversation(feature, selected, coordinator, state, entryId) }
}

@Composable
private fun AppChatConversation(
    feature: AppChatsFeature,
    selected: ChatSelection,
    coordinator: NavigationCoordinator,
    state: NavigationState,
    entryId: Long?,
) {
        val linkContext = LocalContext.current
        val webHandler = LocalUriHandler.current
        var linkFailure by remember(selected) { mutableStateOf<Exception?>(null) }
        val externalLinks = remember(linkContext, webHandler, feature, selected) {
            ChatExternalLinkOpener(linkContext, webHandler::openUri) { failure ->
                feature.report("Chat link could not be opened", failure)
                linkFailure = failure
            }
        }
        linkFailure?.let {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { linkFailure = null },
                text = { androidx.compose.material3.Text(stringResource(
                    com.meshcoreone.android.R.string.platform_chat_link_unavailable,
                )) },
                confirmButton = { androidx.compose.material3.TextButton(onClick = { linkFailure = null }) {
                    androidx.compose.material3.Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonOk))
                } },
            )
        }
        val scope = rememberCoroutineScope()
        val contactMount = remember(feature, selected, entryId) { ChatContactMount(entryId) }
        DisposableEffect(feature, contactMount) {
            feature.registerContactMount(contactMount)
            onDispose { feature.sealContactMount(contactMount) }
        }
        val linkEnvironment = remember(feature, contactMount) { feature.linksForMount(contactMount) }
        val router = remember(feature, linkEnvironment, scope) {
            ChatLinkRouter(linkEnvironment, scope, feature.list.diagnostics)
        }
        ContactLookupFailureDialog(
            router,
            entryId != null && state.selectedTab == com.meshcoreone.android.core.contracts.AppTab.CHATS &&
                state.activeStack.last().id == entryId,
        )
        val connection by feature.connectionState.collectAsState()
        val servicesVersion by feature.timelineServicesVersion.collectAsStateWithLifecycle()
        val publishedSession = remember(feature, servicesVersion) { feature.timelineSessionForVersion(servicesVersion) }
        val channelSession = publishedSession.takeIf { selected is ChatSelection.Channel }
        LifecycleResumeEffect(feature, selected, connection to channelSession) {
            val deactivate = feature.activate(selected, channelSession)
            onPauseOrDispose { deactivate() }
        }
        if (selected is ChatSelection.Room) {
            val radio = selected.session.radioId
            val holder = remember(feature, selected) {
                RoomConversationStateHolder(selected.session, feature.room(selected.session), scope)
            }
            val list = remember(feature, radio) { feature.listFor(radio) }
            val contacts = remember(feature, radio) { mutableStateOf(emptyList<com.meshcoreone.android.core.model.ContactDTO>()) }
            LaunchedEffect(list) {
                list.reloadRequests.onStart { emit(Unit) }.collect {
                    try {
                        contacts.value = list.data.fetchContacts(radio)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        holder.reportLinkFailure(failure)
                    }
                }
            }
            val device = remember(feature, radio, scope) { feature.pathDevice(radio, scope) }
            val links = remember(contacts, device, radio, router, coordinator, externalLinks) {
                ConversationLinkActions(
                    contacts = { contacts.value },
                    connectedDeviceName = { device.value?.nodeName },
                    radioId = { radio },
                    route = router::route,
                    openExternal = externalLinks::open,
                    navigateToContact = coordinator::navigateToContactDetail,
                )
            }
            val preferences = feature.content?.rendering?.collectAsState()?.value
            RoomConversationRoute(
                holder, links,
                failureText = { com.meshcoreone.android.core.ui.uiString(UiErrorMapper().message(it)) },
                replyWithQuote = preferences?.get(com.meshcoreone.android.core.datastore.AppStorageKey.replyWithQuote) == true,
                onSendDirectMessage = coordinator::navigateToChat,
            )
            return
        }
        val conversation = when (selected) {
            is ChatSelection.Direct -> TimelineConversation.Direct(selected.contact)
            is ChatSelection.Channel -> TimelineConversation.Channel(
                selected.channel, channelSession?.takeIf { it.radioId == selected.channel.radioId },
            )
            is ChatSelection.Room -> error("Room uses its own message store")
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        val mapPolicy = remember(feature, selected) { ChannelMapPolicy(selected !is ChatSelection.Channel) }
        val mapPolicyState by mapPolicy.state.collectAsState()
        val channelPolicy by produceState<Pair<Long, Boolean?>?>(null, feature, selected, lifecycleOwner) {
            if (selected is ChatSelection.Channel) {
                try {
                    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                        try {
                            feature.channelPreviewMetadata(
                                selected, mapPolicy::invalidate, mapPolicy.admissionFailureVersion,
                                mapPolicy::admissionReadSucceeded, mapPolicy.observationGeneration,
                            ).collect { (version, metadata) ->
                                val allowed = metadata?.let { !it.suppressesMapPreviews }
                                if (mapPolicy.resolve(version, allowed == true)) value = version to allowed
                            }
                        } finally {
                            mapPolicy.invalidate()
                            value = null
                        }
                    }
                } finally {
                    mapPolicy.invalidate()
                }
            }
        }
        val suppressMapPreviews = selected is ChatSelection.Channel &&
            (channelPolicy?.second != true || !mapPolicyState.second)
        val target = when (selected) {
            is ChatSelection.Direct -> ComposerTarget.Direct(selected.contact)
            is ChatSelection.Channel -> ComposerTarget.Channel(selected.channel)
            is ChatSelection.Room -> error("Room uses its own send service")
        }
        val context = LocalContext.current
        val producer = remember(context) { LocationManagerLocationProducing(context) }
        DisposableEffect(producer) { onDispose { producer.close() } }
        var phoneLocation by remember { mutableStateOf<Coordinate?>(null) }
        val location = remember(producer) {
            object : ComposerLocationSource {
                override val isAuthorized get() =
                    context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                override val currentCoordinate: Coordinate? get() = phoneLocation.takeIf { isAuthorized }
                override suspend fun requestCurrentLocation(timeoutMillis: Long): Coordinate? {
                    if (!isAuthorized) return null
                    return try {
                        withTimeout(timeoutMillis) {
                            producer.requestLocation().let { Coordinate(it.latitude, it.longitude) }.also { phoneLocation = it }
                        }
                    } catch (_: TimeoutCancellationException) {
                        feature.report("Location share timed out", null)
                        null
                    }
                }
            }
        }
        val environment = remember(feature, target, scope) { feature.environment(target, scope, location) }
        val mentionContacts by environment.mentionContacts.collectAsStateWithLifecycle()
        val composerDevice by environment.device.collectAsStateWithLifecycle()
        val avatarTable = rememberChannelAvatars(
            if (conversation is TimelineConversation.Channel) mentionContacts else emptyList(),
        )
        val avatarCache = remember(feature) { com.meshcoreone.android.core.ui.AvatarImageCache() }
        DisposableEffect(avatarCache) { onDispose { avatarCache.clear() } }
        val timeline = remember(feature, conversation) {
            ChatViewModel(conversation, feature.mountedTimeline(conversation), scope)
        }
        val timelineState by timeline.state.collectAsStateWithLifecycle()
        val rendered = remember(timelineState.renderItems) {
            timelineState.renderItems.filterIsInstance<com.meshcoreone.android.app.container.CoordinatedRenderItem>()
                .associate { it.id to it.item }
        }
        val bubbleLabels = rememberMessageBubbleLabels(
            feature.content, mentionContacts, composerDevice?.nodeName, rendered, feature::updateRenderEnvironment,
        )
        var replyTo by remember(conversation.id) { mutableStateOf<java.util.UUID?>(null) }
        val decorateSender: (ComposerSendPort) -> ComposerSendPort = remember(feature, target) { { underlying ->
            object : ComposerSendPort by underlying {
                override suspend fun createPendingDirect(
                    text: String, contact: com.meshcoreone.android.core.model.ContactDTO,
                    textType: com.meshcoreone.android.core.model.TextType, replyToId: java.util.UUID?,
                ): com.meshcoreone.android.core.model.MessageDTO {
                    val reply = replyTo
                    return underlying.createPendingDirect(text, contact, textType, reply ?: replyToId).also {
                        if (replyTo == reply) replyTo = null
                    }
                }
            }
        } }
        val composer = remember(feature, target, decorateSender) {
            feature.composer(target, environment, scope, decorateSender)
        }
        LaunchedEffect(composer, timelineState.messages) {
            composer.updateLoadedChannelSenders(timelineState.messages)
        }
        val notificationSource by feature.notificationSource.collectAsState()
        LaunchedEffect(composer, notificationSource) {
            feature.restoreNotificationDraft(target, composer, notificationSource)
        }
        var pickContact by remember { mutableStateOf(false) }
        var actionSelection by remember {
            mutableStateOf<Pair<com.meshcoreone.android.core.model.MessageDTO, TimelineConversation>?>(null)
        }
        var reactionSelection by remember(conversation) {
            mutableStateOf<Pair<com.meshcoreone.android.core.model.MessageDTO, String?>?>(null)
        }
        var deletionFailure by remember { mutableStateOf<Throwable?>(null) }
        val emojiPicker = remember(feature, scope) { feature.content?.emojiPicker(scope) }
        if (pickContact) androidx.compose.material3.AlertDialog(
            onDismissRequest = { pickContact = false },
            text = {
                androidx.compose.foundation.lazy.LazyColumn {
                    items(mentionContacts.size) { index ->
                        val contact = mentionContacts[index]
                        androidx.compose.material3.TextButton(onClick = {
                            composer.insertShared(ContactShare.formatShare(contact.publicKey, contact.type, contact.name))
                            pickContact = false
                        }) { androidx.compose.material3.Text(contact.displayName) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { pickContact = false }) {
                    androidx.compose.material3.Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonCancel))
                }
            },
        )
        actionSelection?.let { (message, originalConversation) ->
            AppMessageActions(feature, message, originalConversation, composer, location, coordinator,
                onReply = { if (conversation is TimelineConversation.Direct) replyTo = it.id },
                onDelete = {
                    scope.launch {
                        try {
                            feature.deleteDisplayed(message, originalConversation)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            feature.report("Message deletion failed", failure)
                            deletionFailure = failure
                        }
                    }
                },
                onDismiss = { actionSelection = null })
        }
        reactionSelection?.let { (message, emoji) ->
            val holder = remember(feature, message, conversation) {
                com.meshcoreone.android.feature.chats.actions.MessageActionsStateHolder(
                    feature.displayedActions(message, conversation), scope,
                )
            }
            com.meshcoreone.android.feature.chats.actions.ReactionDetailsRoute(
                message.id, emoji, holder, onDismiss = { reactionSelection = null },
            )
        }
        deletionFailure?.let { failure ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { deletionFailure = null },
                text = { androidx.compose.material3.Text(com.meshcoreone.android.core.ui.uiString(UiErrorMapper().message(failure))) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { deletionFailure = null }) {
                        androidx.compose.material3.Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonOk))
                    }
                },
            )
        }
        val links = remember(feature, router, coordinator, externalLinks) {
            ConversationLinkActions(
                contacts = { environment.mentionContacts.value },
                connectedDeviceName = { environment.device.value?.nodeName },
                radioId = { conversation.id.radioId },
                route = router::route,
                openExternal = externalLinks::open,
                navigateToContact = coordinator::navigateToContactDetail,
            )
        }

        val placeholder = stringResource(when (conversation) {
            is TimelineConversation.Direct -> AppChatsStrings.chatsInputPlaceholderDirectMessage
            is TimelineConversation.Channel -> if (conversation.channel.isEncryptedChannel) {
                AppChatsStrings.chatsChannelTypePrivate
            } else {
                AppChatsStrings.chatsChannelTypePublic
            }
        })
        AppChatConversationScreen(
            timeline, composer, placeholder, links,
            modifier = if (selected is ChatSelection.Channel) Modifier.semantics {
                val status = when {
                    channelPolicy?.second == false -> "suppressed"
                    channelPolicy?.second == true && mapPolicyState.second -> "allowed"
                    else -> "pending"
                }
                channelMapPolicy = "${selected.channel.id}:$status"
            } else Modifier,
            currentUserName = composerDevice?.nodeName,
            onMessageActions = { message -> actionSelection = message to conversation },
            onReactionDetails = { message, emoji -> reactionSelection = message to emoji },
            messageAccessibilityLabel = bubbleLabels,
            incomingAvatar = { message -> AppChannelAvatar(message, avatarTable, mentionContacts, avatarCache) },
            onPickContact = { pickContact = true },
            scrollTo = state.pendingMessageScrollFor(entryId),
            isScrollCurrent = { request -> entryId != null && coordinator.isPendingMessageScroll(entryId, request) },
            onScrollConsumed = { request -> entryId != null && coordinator.consumePendingMessageScroll(entryId, request) },
            report = feature::report,
            emojiPicker = emojiPicker,
            emojiStatus = {
                feature.content?.let { content ->
                    val save by content.emojiPreferences.save.collectAsStateWithLifecycle()
                    when (val current = save) {
                        is com.meshcoreone.android.app.container.EmojiPreferenceSave.Failed ->
                            androidx.compose.foundation.layout.Row {
                                androidx.compose.material3.Text(com.meshcoreone.android.core.ui.uiString(
                                    UiErrorMapper().message(current.cause),
                                ), modifier = androidx.compose.ui.Modifier.weight(1f))
                                androidx.compose.material3.TextButton(onClick = content.emojiPreferences::retry) {
                                    androidx.compose.material3.Text(stringResource(AppChatsStrings.chatsMessageActionRetry))
                                }
                            }
                        else -> Unit
                    }
                }
            },
            attachments = { message, outgoing ->
                feature.content?.let { AppMessageContent(it, message, feature::report,
                    onMap = { url -> links.route(url) }, rendered = rendered[message.id],
                    suppressMapPreviews = suppressMapPreviews, mapPreviewWanted = mapPolicy::isAllowed,
                    mapPreviewAdmission = { validate, start ->
                        if (selected is ChatSelection.Channel) {
                            feature.admitChannelPreview(
                                selected, mapPolicy::isAllowed, mapPolicy::admissionRejected,
                                mapPolicy::admissionReadFailure, validate, start,
                            )
                        } else {
                            if (validate()) { start(); true } else false
                        }
                    },
                    mapPreviewPolicyVersion = channelPolicy?.first ?: 0L) }
            },
        )
}

@Composable
private fun ContactLookupFailureDialog(router: ChatLinkRouter, active: Boolean) {
    val failure by router.contactLookupFailure.collectAsState(initial = null)
    val current = failure ?: return
    if (!active) return
    val dismiss = { router.dismissContactLookupFailure(current) }
    androidx.compose.material3.AlertDialog(
        modifier = Modifier.testTag("chat-contact-link-failure"),
        onDismissRequest = dismiss,
        title = {
            androidx.compose.material3.Text(
                stringResource(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_nodekind_contact),
            )
        },
        text = {
            androidx.compose.material3.Text(stringResource(
                com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonErrorFailedToLoad,
            ))
        },
        confirmButton = {
            if (current.issue is ContactLinkLookupIssue.Failed) {
                androidx.compose.material3.TextButton(
                    onClick = { router.retryContactLookup(current) },
                    modifier = Modifier.testTag("chat-contact-link-retry"),
                ) {
                    androidx.compose.material3.Text(stringResource(
                        com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain,
                    ))
                }
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(
                onClick = dismiss,
                modifier = Modifier.testTag("chat-contact-link-cancel"),
            ) {
                androidx.compose.material3.Text(stringResource(
                    com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonCancel,
                ))
            }
        },
    )
}
