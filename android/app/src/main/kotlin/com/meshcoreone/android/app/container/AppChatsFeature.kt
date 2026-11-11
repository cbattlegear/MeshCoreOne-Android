// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+SendQueues.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+EventStream.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-407 native composition binds the existing chat list/timeline/composer to the single process store and live radio graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.navigation.ContactLinkLookupResolution
import com.meshcoreone.android.app.navigation.ContactLinkRequest
import com.meshcoreone.android.app.navigation.ContactLinkResolvedCompletion
import com.meshcoreone.android.app.navigation.ChatContactMount
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.app.navigation.PendingChannelConfirmation
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.ChannelQuery
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryConflict
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryRejected
import com.meshcoreone.android.core.contracts.domain.MessageWindow
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceError
import com.meshcoreone.android.core.contracts.domain.errors.ChatSendQueueServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.reactions.isHiddenOutgoingReaction
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.actions.BlockedSenderHistoryDeletionException
import com.meshcoreone.android.feature.chats.list.*
import com.meshcoreone.android.feature.chats.timeline.*
import com.meshcoreone.android.feature.chats.rooms.*
import com.meshcoreone.android.feature.chats.channels.*
import com.meshcoreone.android.core.services.contacts.ChannelService
import com.meshcoreone.android.core.services.messaging.ChatSendQueueService
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.core.services.remote.startSessionKeepAlive
import com.meshcoreone.android.core.services.remote.stopSessionKeepAlive
import java.time.Clock
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.services.contacts.ChannelFloodScopeResolver
import com.meshcoreone.android.core.services.rendering.ChatCoordinator
import com.meshcoreone.android.core.services.rendering.ChatRenderState
import com.meshcoreone.android.core.services.rendering.ChatWriterRole
import com.meshcoreone.android.core.services.rendering.ChatTimelineWriter
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.rendering.MessageBuildInputs
import com.meshcoreone.android.core.services.rendering.MessageItem
import com.meshcoreone.android.core.services.rendering.NodeNameResolution
import com.meshcoreone.android.core.services.rendering.NodeNameMatchKind
import com.meshcoreone.android.app.navigation.bubbleSenderResolution
import com.meshcoreone.android.app.navigation.messageBubbleBuildInputs
import com.meshcoreone.android.app.navigation.MessageBubbleConfiguration
import com.meshcoreone.android.app.state.ChatConversationType
import com.meshcoreone.android.app.state.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId

class AppChatsFeature internal constructor(private val container: AppContainer) {
    internal data class NavigationDeviceContext(
        val radioId: RadioId,
        val defaultFloodScopeName: String?,
    )

    internal fun registerContactMount(mount: ChatContactMount) = container.registerChatContactMount(mount)
    internal fun sealContactMount(mount: ChatContactMount) = container.sealChatContactMount(mount)
    internal fun acceptContactOffers() = container.acceptChatContactOffers()

    internal fun linksForMount(mount: ChatContactMount): ChatLinkEnvironment {
        val underlying = list.links
        return object : ChatLinkEnvironment by underlying {
            override fun captureContactLinkContext(publicKey: Bytes, publications: ContactLinkPublications) =
                container.captureContactLinkContext(publicKey, publications, mount)

            override fun completeResolvedContactLookup(
                context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
            ): ContactLinkTargetCompletion {
                cancellation.ensureActive()
                if (target !is ContactLinkLookupResolution.Current) {
                    return ContactLinkTargetCompletion.Completed(ContactLinkCompletion.StaleContext)
                }
                return container.offerChatContact(
                    mount, context, target, ContactLinkRequest(link.name, link.publicKey, link.contactType),
                    existing, publications, cancellation,
                )
            }
        }
    }

    private data class PushedFloodScope(
        val session: SessionToken,
        val preference: ChannelFloodScope,
        val deviceDefault: String?,
        val supportsUnscoped: Boolean,
    )
    private val floodScopeMutex = Mutex()
    private var lastPushedScope: PushedFloodScope? = null
    private val visibleRoomKeepAlives = mutableMapOf<Pair<RadioSessionContainer, EntityKey>, Any>()
    private val channelConfirmations = mutableMapOf<Pair<NavigationCoordinator, UUID>, ChannelConfirmationSubmission>()
    private val store get() = container.processStore
    private val scope get() = container.uiScope
    private val app get() = container.appState
    private val navigation get() = container.navigation
    private val logger = Logger.getLogger("com.mc1.AppChatsFeature")
    private val channelHistory: ChannelHistoryPersisting
        get() = store as? ChannelHistoryPersisting
            ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                UnsupportedOperationException("Occupant-scoped channel history is not wired"))

    private fun channelOrigin(radio: RadioId, index: UByte, occupant: UUID?): ChannelHistoryScope =
        ChannelHistoryScope(radio, index, occupant ?: throw PersistenceStoreException(
            PersistenceStoreError.InvalidData, ChannelHistoryRejected(ChannelHistoryConflict.UNKNOWN_AUTHORITY),
        ))

    private suspend fun channelMessages(channel: ChannelDTO, limit: Long = 50, offset: Long = 0): SnapshotList<MessageDTO> =
        if (channel.radioId == MockDataProvider.simulatorRadioId) {
            store.fetchMessages(channel.radioId, channel.index, limit, offset)
        } else {
            channelHistory.fetchChannelTimeline(channelOrigin(channel.radioId, channel.index, channel.occupantID), limit, offset)
        }

    private suspend fun channelWindow(channel: ChannelDTO, anchor: Instant?, limit: Long): MessageWindow =
        if (channel.radioId == MockDataProvider.simulatorRadioId) {
            store.fetchMessageWindow(channel.radioId, channel.index, anchor, limit)
        } else {
            channelHistory.fetchChannelTimelineWindow(channelOrigin(channel.radioId, channel.index, channel.occupantID), anchor, limit)
        }

    private suspend fun markChannelFailuresSeen(channel: ChannelDTO) {
        if (channel.radioId == MockDataProvider.simulatorRadioId) {
            store.markFailedSendsSeen(channel.radioId, channel.index)
        } else {
            channelHistory.markChannelTimelineFailuresSeen(channelOrigin(channel.radioId, channel.index, channel.occupantID))
        }
    }
    private val renderEnvironment = MutableStateFlow(EnvInputs.DEFAULT.copy(translationOffersEnabled = false))
    private val renderResources = MutableStateFlow<android.content.res.Resources?>(null)
    internal val content get() = container.chatContent
    private val changes = merge(combine(
        app.servicesVersion, app.conversationsVersion, app.contactsVersion,
        app.sessionStateChangeCount, container.connectionManager.snapshot,
    ) { _, _, _, _, _ -> Unit }, container.deviceAccess.changes.map { Unit })
    val connectionState = container.connectionManager.snapshot.map { it.state }
        .stateIn(scope, SharingStarted.Eagerly, app.connectionState)
    val currentRadioId = changes.map { app.currentRadioId }
        .stateIn(scope, SharingStarted.Eagerly, app.currentRadioId)
    internal val navigationDeviceContext = changes.map {
        app.connectedDevice?.let { device ->
            NavigationDeviceContext(device.radioId, device.defaultFloodScopeName)
        }
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        app.connectedDevice?.let { NavigationDeviceContext(it.radioId, it.defaultFloodScopeName) },
    )
    internal val notificationSource = app.servicesVersion.map { container.sessions.current?.notificationService }
        .stateIn(scope, SharingStarted.Eagerly, container.sessions.current?.notificationService)
    val unreadCount = merge(changes, events().map { Unit }).map {
        app.currentRadioId?.let { radio ->
            store.getTotalUnreadCounts(radio).let { Math.addExact(Math.addExact(it.contacts, it.channels), it.rooms) }
        } ?: 0L
    }.catch { failure -> report("Unread counts could not load", failure); throw failure }
        .stateIn(scope, SharingStarted.Eagerly, 0L)

    internal fun session(radio: RadioId): RadioSessionContainer {
        val current = container.sessions.current
        if (current == null || current.token.radioId != radio ||
            app.connectionState != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
        ) throw MessageServiceException(MessageServiceError.NotConnected)
        return current
    }

    private fun sendQueue(radio: RadioId): ChatSendQueueService {
        val current = container.sessions.current
        if (current == null || current.token.radioId != radio ||
            app.connectionState != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
        ) throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
        return current.chatSendQueueService
    }

    internal fun maximumChannels(): UByte = app.connectedDevice?.maxChannels ?: 0u

    internal class ChannelConfirmationSubmission(
        val radioId: RadioId?,
        val maximumChannels: UByte,
        val holder: ChannelSheetsStateHolder,
    ) {
        val loadStarted = AtomicBoolean(false)
    }

    internal fun releaseInactiveChannelConfirmations(navigation: NavigationCoordinator) {
        val state = navigation.state.value
        val active = listOfNotNull(state.pendingChannelConfirmation?.deliveryId, state.pendingHashtagConfirmation?.deliveryId)
        synchronized(channelConfirmations) {
            channelConfirmations.entries.removeAll { (key, submission) ->
                key.first === navigation && key.second !in active && !submission.holder.state.value.isSubmitting
            }
        }
    }

    internal fun channelConfirmation(
        navigation: NavigationCoordinator, deliveryId: UUID, radioId: RadioId?,
    ): ChannelConfirmationSubmission {
        val maximum = maximumChannels()
        val key = navigation to deliveryId
        return synchronized(channelConfirmations) {
            val retained = channelConfirmations[key]
            if (retained != null && (retained.radioId == radioId && retained.maximumChannels == maximum ||
                    retained.holder.state.value.let { it.isSubmitting || it.committedWrite != null })) {
                retained
            } else {
                ChannelConfirmationSubmission(radioId, maximum, ChannelSheetsStateHolder(radioId, maximum, channels))
                    .also { channelConfirmations[key] = it }
            }
        }
    }

    internal fun loadChannelConfirmation(submission: ChannelConfirmationSubmission) {
        if (submission.loadStarted.compareAndSet(false, true)) {
            scope.launch(start = CoroutineStart.UNDISPATCHED) { submission.holder.load() }
        }
    }

    internal fun confirmChannel(
        navigation: NavigationCoordinator, request: PendingChannelConfirmation, submission: ChannelConfirmationSubmission,
    ) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val state = navigation.state.value
            val current = state.pendingChannelConfirmation ?: state.pendingHashtagConfirmation
            if (current?.deliveryId != request.deliveryId) return@launch
            if (synchronized(channelConfirmations) { channelConfirmations[navigation to request.deliveryId] !== submission }) {
                return@launch
            }
            try {
                val link = request.link
                val channel = if (link != null) {
                    submission.holder.joinLink(ChannelLink(link.name, link.secret, link.regionScope, link.hasHashtagSecretMismatch))
                } else {
                    submission.holder.updateHashtag(requireNotNull(request.hashtag).id)
                    submission.holder.joinHashtag()
                }
                channel?.let { navigation.completeChannelConfirmation(request.deliveryId, it) }
            } finally {
                releaseInactiveChannelConfirmations(navigation)
            }
        }
    }

    internal fun pathDevice(radio: RadioId, screenScope: kotlinx.coroutines.CoroutineScope): StateFlow<DeviceDTO?> {
        fun current() = app.connectedDevice?.takeIf { it.radioId == radio }
        return changes.map { current() }.stateIn(screenScope, SharingStarted.Eagerly, current())
    }

    val drafts = object : TimelineDraftStore {
        override fun get(conversationId: ChatConversationID) = app.draftStore.draft(conversationId)
        override fun set(conversationId: ChatConversationID, text: String?) {
            if (text == null) app.draftStore.clearDraft(conversationId)
            else app.draftStore.setDraft(text, conversationId)
        }
    }

    internal fun restoreNotificationDraft(
        target: ComposerTarget,
        composer: ComposerStateHolder,
        source: NotificationService?,
    ) {
        if (target !is ComposerTarget.Direct) return
        val radio = target.contact.radioId
        val notifications = container.sessions.current?.takeIf { it.token.radioId == radio }?.notificationService
            ?: return
        if (source !== notifications) return
        val pending = notifications.consumeDraft(EntityKey(radio, target.contact.id)) ?: return
        composer.onDraftChanged(pending.ifEmpty { drafts.get(target.conversationId).orEmpty() })
    }

    val list: ChatListFeatureDependencies = listFor(null)

    fun listFor(radio: RadioId?): ChatListFeatureDependencies = object : ChatListFeatureDependencies {
        private fun key(id: UUID) = EntityKey(
            radio ?: throw MessageServiceException(MessageServiceError.NotConnected), id,
        )
        override val data = object : ChatListDataSource {
            override suspend fun fetchConversations(radioId: RadioId) = store.fetchConversations(radioId)
            override suspend fun fetchChannels(radioId: RadioId) = store.fetchChannelsForDisplay(radioId)
            override suspend fun fetchRemoteNodeSessions(radioId: RadioId) = store.fetchRemoteNodeSessions(radioId)
            override suspend fun fetchContacts(radioId: RadioId) = store.fetchContacts(radioId)
            override suspend fun fetchLastMessages(contactIds: List<UUID>, limit: Int) =
                store.fetchLastMessages(contactIds.map(::key).snapshot(), limit.toLong())
                    .entries.associate { it.key.id to it.value }
            override suspend fun fetchLastChannelMessages(channels: List<ChatChannelKey>, limit: Int): Map<UUID, List<MessageDTO>> =
                channels.associate { channel ->
                    val messages = try {
                        if (channel.radioId == MockDataProvider.simulatorRadioId) {
                            store.fetchLastChannelMessages(
                                listOf(ChannelQuery(channel.radioId, channel.channelIndex, channel.id)).snapshot(), limit.toLong(),
                            ).getValue(EntityKey(channel.radioId, channel.id))
                        } else {
                            channelHistory.fetchChannelTimeline(
                                channelOrigin(channel.radioId, channel.channelIndex, channel.occupantID), limit.toLong(),
                            )
                        }
                    } catch (failure: PersistenceStoreException) {
                        if (failure.cause !is ChannelHistoryRejected) throw failure
                        report("Channel preview origin could not be verified", failure)
                        emptyList()
                    }
                    channel.id to messages
                }
            override suspend fun fetchFailedSendConversationKeys(
                radioId: RadioId, channels: List<ChatChannelKey>,
            ): FailedSendConversationKeys {
                val failed = store.fetchFailedSendConversationKeys(radioId)
                if (radioId == MockDataProvider.simulatorRadioId) return failed
                val current = mutableSetOf<UUID>()
                for (channel in channels) {
                    if (channel.radioId != radioId || channel.id !in failed.channelIDs) continue
                    try {
                        if (channelHistory.countUnseenChannelTimelineFailures(
                                channelOrigin(radioId, channel.channelIndex, channel.occupantID),
                            ) > 0) current += channel.id
                    } catch (failure: PersistenceStoreException) {
                        if (failure.cause !is ChannelHistoryRejected) throw failure
                        report("Channel failure indicator origin could not be verified", failure)
                    }
                }
                return failed.copy(channelIDs = current.snapshotSet())
            }
            override suspend fun setContactMuted(contactId: UUID, isMuted: Boolean) =
                store.setContactMuted(key(contactId), isMuted)
            override suspend fun setChannelNotificationLevel(channelId: UUID, level: NotificationLevel) =
                store.setChannelNotificationLevel(key(channelId), level)
            override suspend fun setSessionNotificationLevel(sessionId: UUID, level: NotificationLevel) =
                store.setSessionNotificationLevel(key(sessionId), level)
            override suspend fun setChannelFavorite(channelId: UUID, isFavorite: Boolean) =
                store.setChannelFavorite(key(channelId), isFavorite)
            override suspend fun setSessionFavorite(sessionId: UUID, isFavorite: Boolean) =
                store.setSessionFavorite(key(sessionId), isFavorite)
            override suspend fun deleteDirectConversation(contact: ContactDTO) {
                store.deleteMessagesForContact(EntityKey(contact.radioId, contact.id))
                store.updateContactLastMessage(EntityKey(contact.radioId, contact.id), null)
                app.refreshConversations()
            }

        }
        override val reactions = object : ChatReactionClassifier {
            override fun isDirectReaction(text: String) = ReactionParser.parseDM(text) != null
            override fun isChannelReaction(text: String) = ReactionParser.parse(text) != null
        }
        override val navigation = object : ChatListNavigation {
            override val pendingChatContact = navigationFlow { state ->
                state.pendingChatContact?.takeIf {
                    state.selectedTab == AppTab.CHATS && state.chatsSelectedRoute == ChatSelection.Direct(it)
                }
            }
            override val pendingChannel = navigationFlow { state ->
                state.pendingChannel?.takeIf {
                    state.selectedTab == AppTab.CHATS && state.chatsSelectedRoute == ChatSelection.Channel(it)
                }
            }
            override val pendingRoomSession = navigationFlow { state ->
                state.pendingRoomSession?.takeIf {
                    state.selectedTab == AppTab.CHATS && state.chatsSelectedRoute == ChatSelection.Room(it)
                }
            }
            override val pendingRoomAuthentication = navigationFlow { state ->
                state.pendingRoomAuthentication?.takeIf { state.selectedTab == AppTab.CHATS }
            }
            override fun acknowledgePendingRoute(route: ChatRoute) =
                container.navigation.acknowledgePendingChatRoute(route)
            override fun captureChannelSelectionCleanup(channel: ChannelDTO) =
                container.navigation.captureChannelSelectionCleanup(channel)
            override fun clearPendingRoomAuthentication() = container.navigation.clearPendingRoomAuthentication()
        }
        override val links = object : ChatLinkEnvironment {
            override var selectedTab: com.meshcoreone.android.core.contracts.AppTab
                get() = container.navigation.state.value.selectedTab
                set(value) = container.navigation.selectTab(value)
            override val currentRadioId get() = app.currentRadioId
            override val contactLinkGeneration get() = container.navigation.currentState.generation
            override fun captureContactLinkSession(generation: Long) = container.captureContactLinkSession(generation)
            override val connectedDevicePublicKey get() = app.connectedDevice?.publicKey
            override fun captureContactLinkContext(publicKey: Bytes, publications: ContactLinkPublications) =
                container.captureContactLinkContext(publicKey, publications)
            override fun resolveContactLinkContext(context: ContactLinkContext, publicKey: Bytes) =
                when (val resolution = container.resolveContactLinkContext(context, publicKey)) {
                    ContactLinkLookupResolution.Stale -> ContactLinkTargetResolution.Stale
                    is ContactLinkLookupResolution.Current -> ContactLinkTargetResolution.Current(resolution)
                }
            override fun completeResolvedContactLookup(
                context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
                existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
            ): ContactLinkTargetCompletion {
                cancellation.ensureActive()
                if (target !is ContactLinkLookupResolution.Current) {
                    return ContactLinkTargetCompletion.Completed(ContactLinkCompletion.StaleContext)
                }
                return when (val completion = container.completeResolvedContactLookup(
                    context, target, ContactLinkRequest(link.name, link.publicKey, link.contactType), existing,
                    selectChats = false, publications = publications, cancellation = cancellation,
                )) {
                    ContactLinkResolvedCompletion.TargetChanged -> ContactLinkTargetCompletion.TargetChanged
                    is ContactLinkResolvedCompletion.Completed -> ContactLinkTargetCompletion.Completed(completion.outcome)
                }
            }
            override fun completeContactLookup(
                context: ContactLinkContext, link: ContactLinkResult, existing: ContactDTO?,
                publications: ContactLinkPublications,
            ) = container.completeContactLookup(
                context, ContactLinkRequest(link.name, link.publicKey, link.contactType), existing,
                selectChats = false, publications = publications,
            )
            override val parser = MeshCoreLinkParser
            override fun navigateToMap(latitude: Double, longitude: Double) =
                container.navigation.navigateToMap(latitude, longitude)
            override fun navigateToContactDetail(contact: ContactDTO) =
                container.navigation.navigateToContactDetail(contact)
            override fun navigateToChannel(channel: ChannelDTO) = container.navigation.navigateToChannel(channel)
            override fun stageContactLink(link: ContactLinkResult) =
                container.navigation.stageContactLink(
                    ContactLinkRequest(link.name, link.publicKey, link.contactType), app.currentRadioId,
                )
            override fun completeContactLookup(
                link: ContactLinkResult, existing: ContactDTO?, radioId: RadioId?, generation: Long,
                isCurrentSession: () -> Boolean,
            ) = container.navigation.completeContactLookup(
                ContactLinkRequest(link.name, link.publicKey, link.contactType), existing,
                radioId, generation, app.currentRadioId, isCurrentSession,
            )
            override fun stageChannelLink(link: ChannelLinkResult) =
                container.navigation.stageChannelLink(ChannelLinkRequest(
                    link.name, link.secret, link.regionScope,
                    com.meshcoreone.android.app.deeplinks.MeshCoreUriParser.hasHashtagSecretMismatch(link.name, link.secret),
                ))
            override fun stageHashtag(fullName: String) = container.navigation.stageHashtag(HashtagJoinRequest(fullName))
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = store.fetchContact(radioId, publicKey)
            override suspend fun fetchChannels(radioId: RadioId) = store.fetchChannels(radioId)
        }
        override val connectionState get() = this@AppChatsFeature.connectionState
        override val currentRadioId = MutableStateFlow(radio)
        override val reloadRequests get() = changes
        override val messageEvents: Flow<ChatListEvent> get() = events().mapNotNull(::listEvent)
        override val clock: Clock = Clock.systemUTC()
        override val diagnostics = ChatListDiagnostics { operation, failure -> report(operation, failure) }
        override fun conversationServices(): ChatConversationServices? {
            val current = container.sessions.current ?: return null
            return object : ChatConversationServices {
                override suspend fun setContactFavorite(contactId: UUID, isFavorite: Boolean) =
                    session(current.token.radioId).contactService.setContactFavorite(key(contactId), isFavorite)
                override suspend fun clearChannel(channel: ChannelDTO) {
                    if (container.sessions.current !== current || channel.radioId != current.token.radioId ||
                        app.connectionState != DeviceConnectionState.READY
                    ) throw com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure.RetiredSession()
                    current.channelService.clearChannel(channel)
                }
                override suspend fun captureChannelNotificationCleanup(channel: ChannelDTO) =
                    current.notificationService.captureDeliveredChannelCleanup(channel.index, channel.radioId)
                override suspend fun leaveRoom(session: RemoteNodeSessionDTO) =
                    this@AppChatsFeature.session(session.radioId).roomServerService.leaveRoom(
                        EntityKey(session.radioId, session.id), session.publicKey,
                    )
                override suspend fun updateBadgeCount() = current.notificationService.updateBadgeCount()
            }
        }
    }

    fun activate(selection: com.meshcoreone.android.app.navigation.ChatSelection): () -> Unit {
        val radio = when (selection) {
            is com.meshcoreone.android.app.navigation.ChatSelection.Direct -> selection.contact.radioId
            is com.meshcoreone.android.app.navigation.ChatSelection.Channel -> selection.channel.radioId
            is com.meshcoreone.android.app.navigation.ChatSelection.Room -> selection.session.radioId
        }
        val service = container.sessions.current?.takeIf { it.token.radioId == radio }?.notificationService
            ?: return {}
        when (selection) {
            is com.meshcoreone.android.app.navigation.ChatSelection.Direct -> service.setActiveConversation(contactID = selection.contact.id)
            is com.meshcoreone.android.app.navigation.ChatSelection.Channel -> service.setActiveConversation(channelIndex = selection.channel.index, channelRadioId = radio)
            is com.meshcoreone.android.app.navigation.ChatSelection.Room -> service.setActiveConversation(roomSessionID = selection.session.id)
        }
        val installed = service.activeConversation
        return { if (service.activeConversation == installed) service.setActiveConversation() }
    }

    fun actions(radio: RadioId) = object : com.meshcoreone.android.feature.chats.actions.MessageActionsDataSource {
        override suspend fun contacts(radioId: RadioId) = store.fetchContacts(radioId)
        override suspend fun discoveredNodes(radioId: RadioId) = store.fetchDiscoveredNodes(radioId)
        override suspend fun repeats(messageId: UUID) = store.fetchMessageRepeats(EntityKey(radio, messageId))
        override suspend fun reactions(messageId: UUID) = store.fetchReactions(EntityKey(radio, messageId))
        override suspend fun blockChannelSender(radioId: RadioId, senderName: String) {
            store.saveBlockedChannelSender(BlockedChannelSenderDTO(name = senderName, radioId = radioId))
            container.sessions.current?.takeIf { it.token.radioId == radioId }?.syncCoordinator?.refreshBlockedContactsCache(radioId, store)
            try {
                store.deleteChannelMessages(senderName, radioId)
            } catch (failure: PersistenceStoreException) {
                throw BlockedSenderHistoryDeletionException(failure)
            }
            app.messageEventStream.send(MessageEvent.ChannelHistoryDeleted(radioId))
            app.refreshConversations()
        }
        override suspend fun blockContacts(contactIds: Set<UUID>) {
            var firstFailure: Exception? = null
            for (id in contactIds) {
                try {
                    val contact = store.fetchContact(EntityKey(radio, id))
                        ?: throw MessageServiceException(MessageServiceError.ContactNotFound)
                    store.saveContact(contact.copy(isBlocked = true))
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (firstFailure == null) firstFailure = failure else firstFailure.addSuppressed(failure)
                }
            }
            container.sessions.current?.takeIf { it.token.radioId == radio }?.syncCoordinator?.refreshBlockedContactsCache(radio, store)
            app.bumpContactsVersion()
            firstFailure?.let { throw it }
        }
        override suspend fun setMuted(contactId: UUID, muted: Boolean) {
            store.setContactMuted(EntityKey(radio, contactId), muted)
            app.bumpContactsVersion()
        }
    }

    suspend fun delete(message: MessageDTO) {
        store.deleteMessage(EntityKey(message.radioId, message.id))
        if (!message.isChannelMessage) {
            message.contactID?.let { store.recomputeContactLastMessageDate(EntityKey(message.radioId, it)) }
        }
        app.refreshConversations()
    }

    private suspend fun retryDirect(messageId: UUID, contact: ContactDTO, isResend: Boolean) {
        val current = container.sessions.current?.takeIf { it.token.radioId == contact.radioId }
        val envelope = DirectMessageEnvelope(messageId, contact.id, isResend)
        store.replacePendingSendForRetry(messageId, PendingSendDTO.fromEnvelope(envelope, contact.radioId))
        app.messageEventStream.send(MessageEvent.MessageResent(messageId))
        app.refreshConversations()
        val queue = current?.chatSendQueueService
            ?: throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
        queue.signalDMEnqueued(envelope)
    }

    suspend fun sendAgain(message: MessageDTO, conversation: TimelineConversation) {
        when (conversation) {
            is TimelineConversation.Direct -> retryDirect(message.id, conversation.contact, isResend = true)
            is TimelineConversation.Channel -> enqueueChannel(message.radioId, message.id, isResend = true)
        }
    }

    internal suspend fun failPending(message: MessageDTO) {
        if (store.updateMessageStatusUnlessDelivered(EntityKey(message.radioId, message.id), MessageStatus.FAILED)) {
            app.refreshConversations()
            try {
                markFailedSendSeenIfVisible(message)
            } finally {
                // The failure is already committed even if marking it seen is cancelled.
                app.refreshConversations()
                app.messageEventStream.send(MessageEvent.MessageFailed(message.id))
            }
        }
    }

    private suspend fun markFailedSendSeenIfVisible(message: MessageDTO) {
        val state = navigation.state.value
        val open = if (state.selectedTab == com.meshcoreone.android.core.contracts.AppTab.CHATS) {
            state.stacks.getValue(com.meshcoreone.android.core.contracts.AppTab.CHATS).last().destination
                as? com.meshcoreone.android.app.navigation.NavigationDestination.Chat
        } else null
        try {
            when (val selection = open?.selection) {
                is com.meshcoreone.android.app.navigation.ChatSelection.Direct ->
                    if (selection.contact.radioId == message.radioId && selection.contact.id == message.contactID) {
                        store.markFailedSendsSeen(EntityKey(message.radioId, selection.contact.id))
                    }
                is com.meshcoreone.android.app.navigation.ChatSelection.Channel ->
                    if (selection.channel.radioId == message.radioId && selection.channel.index == message.channelIndex &&
                        selection.channel.occupantID == message.channelOccupantID) {
                        markChannelFailuresSeen(selection.channel)
                    }
                else -> Unit
            }
        } catch (failure: PersistenceStoreException) {
            report("Mark visible failed send seen", failure)
        }
    }

    suspend fun react(message: MessageDTO, emoji: String, conversation: TimelineConversation) {
        val current = session(message.radioId)
        when (conversation) {
            is TimelineConversation.Direct -> current.messageService.sendDirectMessage(
                current.reactionService.buildDMReactionText(emoji, message.text, message.reactionTimestamp),
                conversation.contact,
            )
            is TimelineConversation.Channel -> current.messageService.sendChannelMessage(
                current.reactionService.buildReactionText(emoji, message.senderNodeName ?: app.connectedDevice?.nodeName
                    ?: throw MessageServiceException(MessageServiceError.NotConnected), message.text, message.reactionTimestamp),
                conversation.channel.index, conversation.channel.radioId,
            )
        }
    }

    private fun <T> navigationFlow(select: (com.meshcoreone.android.app.navigation.NavigationState) -> T) =
        navigation.state.map(select).stateIn(scope, SharingStarted.WhileSubscribed(), select(navigation.state.value))

    private fun events(): Flow<MessageEvent> = flow { emitAll(app.messageEventStream.events()) }

    internal fun updateRenderEnvironment(env: EnvInputs, resources: android.content.res.Resources? = null) {
        if (resources != null) renderResources.value = resources
        renderEnvironment.value = env
        app.chatEnvInputs(null, ChatEnvSnapshot(env.themeID, env.isDark, env.isHighContrast, env.contentSizeCategory))
    }

    internal fun envInputs(conversation: ChatConversationType?, snapshot: ChatEnvSnapshot): EnvInputs {
        val name = app.connectedDevice?.takeIf {
            conversation == null || it.radioId == conversation.coordinatorId.radioId
        }?.nodeName.orEmpty()
        val base = renderEnvironment.value.copy(
            themeID = snapshot.themeId, isDark = snapshot.isDark,
            isHighContrast = snapshot.isHighContrast, contentSizeCategory = snapshot.contentSizeCategory,
            currentUserName = name, translationOffersEnabled = false,
        )
        return content?.applyRenderPreferences(base) ?: base
    }

    internal fun bindState(
        conversation: TimelineConversation,
        coordinator: ChatCoordinator,
        owner: Any,
        zoneId: ZoneId,
        initial: ChatTimelineState,
        writer: ChatTimelineWriter? = null,
        senderTables: (() -> ChatSenderTables)? = null,
        envOverride: EnvInputs? = null,
    ): CoordinatedTimelineState {
        val invalidations = merge(
            changes, renderEnvironment.map { Unit }, renderResources.map { Unit },
            content?.renderInvalidations ?: emptyFlow(),
        )
        return CoordinatedTimelineState(
            coordinator, owner, zoneId, initial, writer, scope, invalidations,
            assemble = { messages, rows ->
                val kind = when (conversation) {
                    is TimelineConversation.Direct -> ChatConversationType.Dm(conversation.contact)
                    is TimelineConversation.Channel -> ChatConversationType.Channel(conversation.channel)
                }
                val env = envOverride?.copy(translationOffersEnabled = false) ?: envInputs(kind, app.lastChatEnvSnapshot ?: ChatEnvSnapshot(
                    renderEnvironment.value.themeID, renderEnvironment.value.isDark,
                    renderEnvironment.value.isHighContrast, renderEnvironment.value.contentSizeCategory,
                ))
                val resources = renderResources.value ?: content?.resources
                val contacts = if (conversation is TimelineConversation.Channel) {
                    senderTables?.invoke()?.contacts ?: store.fetchContacts(conversation.id.radioId)
                } else emptyList()
                val nicknames = MessageBubbleConfiguration.buildNicknameLookup(contacts)
                val rowById = rows.filterIsInstance<TimelineRow.Message>().associateBy { it.message.id }
                val unreadIds = rows.filterIsInstance<TimelineRow.UnreadDivider>().mapTo(HashSet()) { it.beforeMessageId }
                val inputs = messages.map { message ->
                    val resolution = if (resources == null) NodeNameResolution(
                        message.senderNodeName.orEmpty(), NodeNameMatchKind.UNRESOLVED,
                    ) else bubbleSenderResolution(message, contacts, nicknames, resources)
                    message to messageBubbleBuildInputs(
                        message, resolution, env, resources, rowById[message.id], content?.renderContent(message, env),
                        showUnreadDivider = message.id in unreadIds,
                    )
                }
                TimelineRenderBatch(inputs, env)
            },
            report = ::report,
        )
    }

    internal suspend fun prepareTimelineMessages(
        messages: List<MessageDTO>,
        indexing: ReactionIndexing?,
    ): List<MessageDTO> {
        if (indexing == null) return messages
        val radio = when (val target = indexing.scope) {
            is ReactionIndexScope.Direct -> target.contact.radioId
            is ReactionIndexScope.Channel -> target.channel.radioId
        }
        check(messages.all { it.radioId == radio }) { "Reaction indexing crossed a conversation radio" }
        return messages.map { message ->
            val reactions = when (val target = indexing.scope) {
                is ReactionIndexScope.Direct -> indexing.service.indexDMMessage(
                    message.id, target.contact.id, message.text, message.reactionTimestamp,
                ).map { pending ->
                    check(pending.radioId == radio) { "Pending direct reaction belongs to another radio" }
                    ReactionDTO(
                        messageID = message.id, emoji = pending.parsed.emoji, senderName = pending.senderName,
                        messageHash = pending.parsed.messageHash, rawText = pending.rawText,
                        contactID = target.contact.id, radioId = pending.radioId,
                    ) to { indexing.service.requeuePendingDMReaction(pending) }
                }
                is ReactionIndexScope.Channel -> {
                    val name = if (message.isOutgoing) target.localNodeName else message.senderNodeName
                    if (name == null) emptyList() else indexing.service.indexMessage(
                        message.id, target.channel.index, name, message.text, message.timestamp,
                    ).map { pending ->
                        check(pending.radioId == radio) { "Pending channel reaction belongs to another radio" }
                        ReactionDTO(
                            messageID = message.id, emoji = pending.parsed.emoji, senderName = pending.senderNodeName,
                            messageHash = pending.parsed.messageHash, rawText = pending.rawText,
                            channelIndex = pending.channelIndex, radioId = pending.radioId,
                        ) to { indexing.service.requeuePendingReaction(pending) }
                    }
                }
            }
            var updated = message
            var completed = 0
            try {
                for ((reaction, _) in reactions) {
                    kotlinx.coroutines.currentCoroutineContext().ensureActive()
                    val key = EntityKey(message.radioId, message.id)
                    val result = if (store.reactionExists(key, reaction.senderName, reaction.emoji)) {
                        // A prior save may have committed before summary reconciliation failed or was cancelled.
                        indexing.service.reconcileSummary(key, store)
                    } else {
                        indexing.service.persistReactionAndUpdateSummary(reaction, store)
                    }
                    val summary = result?.summary ?: throw PersistenceStoreException(
                        PersistenceStoreError.SaveFailed("History reaction replay did not complete"),
                    )
                    updated = updated.copy(reactionSummary = summary)
                    completed++
                }
            } finally {
                // Indexing drains the whole batch; a failure must retain the current and all later matches.
                reactions.drop(completed).forEach { (_, requeue) -> requeue() }
            }
            updated
        }
    }

    fun timeline(conversation: TimelineConversation): ChatTimelineDependencies = object : ChatTimelineDependencies {
        private val radio = conversation.id.radioId
        override fun bindTimeline(owner: Any, scope: CoroutineScope, zoneId: ZoneId, initial: ChatTimelineState) =
            bindState(conversation,
                app.mountedChatCoordinator(conversation.id),
                owner, zoneId, initial,
            )
        override fun isHiddenOutgoingReaction(message: MessageDTO) =
            message.isHiddenOutgoingReaction(isDM = conversation is TimelineConversation.Direct)
        override val content get() = this@AppChatsFeature.content
        override suspend fun prepareMessages(messages: List<MessageDTO>): List<MessageDTO> {
            val current = container.sessions.current?.takeIf { it.token.radioId == radio } ?: return messages
            return prepareTimelineMessages(messages, ReactionIndexing(current.reactionService, when (conversation) {
                is TimelineConversation.Direct -> ReactionIndexScope.Direct(conversation.contact)
                is TimelineConversation.Channel -> ReactionIndexScope.Channel(
                    conversation.channel, app.connectedDevice?.takeIf { it.radioId == radio }?.nodeName,
                )
            }))
        }
        override fun admissionIdentity() = container.sessions.current?.takeIf { it.token.radioId == radio }?.token
        override fun isCurrentAdmission(identity: SessionToken?) =
            identity != null && container.sessions.current?.token == identity &&
                app.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
        override suspend fun syncChannelFloodScope(channel: ChannelDTO) = floodScopeMutex.withLock {
            val current = container.sessions.current?.takeIf {
                it.token.radioId == channel.radioId && it.token.radioId == radio &&
                    app.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            } ?: return@withLock
            val device = container.connectionManager.connectedDevice?.takeIf { it.radioId == radio }
                ?: return@withLock
            val selected = store.fetchChannel(radio, channel.index) ?: channel
            val desired = PushedFloodScope(
                current.token, selected.floodScope, device.defaultFloodScopeName, device.supportsUnscopedFloodSend,
            )
            if (desired == lastPushedScope) return@withLock
            lastPushedScope = null
            current.applyChannelFloodScope(ChannelFloodScopeResolver.resolve(
                selected.floodScope, device.defaultFloodScopeName, device.supportsUnscopedFloodSend,
            ))
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!isCurrentAdmission(current.token)) {
                throw CancellationException("Channel scope session superseded")
            }
            lastPushedScope = desired
        }
        override val draftStore get() = drafts
        override val data = object : TimelineDataSource {
            override suspend fun fetchMessages(conversation: TimelineConversation, limit: Int, offset: Int) =
                when (conversation) {
                    is TimelineConversation.Direct -> store.fetchMessages(
                        EntityKey(conversation.contact.radioId, conversation.contact.id), limit.toLong(), offset.toLong(),
                    )
                    is TimelineConversation.Channel -> channelMessages(conversation.channel, limit.toLong(), offset.toLong())
                }
            override suspend fun fetchMessageWindow(conversation: TimelineConversation, anchorSortDate: Instant?, floorLimit: Int) =
                when (conversation) {
                    is TimelineConversation.Direct -> store.fetchMessageWindow(
                        EntityKey(conversation.contact.radioId, conversation.contact.id), anchorSortDate, floorLimit.toLong(),
                    )
                    is TimelineConversation.Channel -> channelWindow(conversation.channel, anchorSortDate, floorLimit.toLong())
                }
            override suspend fun fetchMessage(messageId: UUID): MessageDTO? {
                val key = EntityKey(radio, messageId)
                if (conversation is TimelineConversation.Channel && radio != MockDataProvider.simulatorRadioId) {
                    val expected = channelOrigin(radio, conversation.channel.index, conversation.channel.occupantID)
                    return channelHistory.fetchChannelTimelineMessage(expected, key)
                }
                return store.fetchMessage(key)
            }
            override suspend fun unreadAnchorMessageId(conversation: TimelineConversation): UUID? =
                when (conversation) {
                    is TimelineConversation.Direct ->
                        store.newestUnreadIncomingMessage(EntityKey(radio, conversation.contact.id))?.id
                    is TimelineConversation.Channel -> null
                }
            override suspend fun clearUnread(conversation: TimelineConversation) {
                // A mounted read is not a durable OPEN receipt, so retain content taps for retry.
                when (conversation) {
                    is TimelineConversation.Direct ->
                        store.clearUnreadCount(EntityKey(radio, conversation.contact.id))
                    is TimelineConversation.Channel -> {
                        val channel = conversation.channel
                        if (radio == MockDataProvider.simulatorRadioId) store.clearChannelUnreadCount(radio, channel.index)
                        else channelHistory.clearChannelTimelineUnread(channelOrigin(radio, channel.index, channel.occupantID))
                    }
                }
                container.sessions.current?.notificationService?.updateBadgeCount()
                app.refreshConversations()
            }
            override suspend fun markFailedSendsSeen(conversation: TimelineConversation) = when (conversation) {
                is TimelineConversation.Direct -> store.markFailedSendsSeen(EntityKey(radio, conversation.contact.id))
                is TimelineConversation.Channel -> markChannelFailuresSeen(conversation.channel)
            }
        }
        override val retryService = TimelineRetryService { message, target ->
            when (target) {
                is TimelineConversation.Direct -> retryDirect(message.id, target.contact, isResend = false)
                is TimelineConversation.Channel -> enqueueChannel(radio, message.id, isResend = true)
            }
        }
        override val events: Flow<TimelineEvent> get() = this@AppChatsFeature.events().transform<MessageEvent, TimelineEvent> { event ->
            val ids = when (event) {
                is MessageEvent.DirectMessageReceived -> listOf(event.message.id)
                is MessageEvent.ChannelMessageReceived -> listOf(event.message.id)
                is MessageEvent.ChannelHistoryDeleted -> {
                    if (conversation is TimelineConversation.Channel && event.radioId == radio) {
                        emit(TimelineEvent.HistoryChanged(conversation.id))
                    }
                    emptyList()
                }
                is MessageEvent.MessageStatusResolved -> listOf(event.messageID)
                is MessageEvent.MessageResent -> listOf(event.messageID)
                is MessageEvent.MessageFailed -> listOf(event.messageID)
                is MessageEvent.MessageRetrying -> listOf(event.messageID)
                is MessageEvent.HeardRepeatRecorded -> listOf(event.messageID)
                is MessageEvent.ReactionReceived -> listOf(event.messageID)
                is MessageEvent.MessagesRegionUpdated -> event.messageIDs
                is MessageEvent.RoutingChanged -> emptyList()
                is MessageEvent.RoomMessageReceived, is MessageEvent.RoomMessageStatusUpdated,
                is MessageEvent.RoomMessageFailed -> emptyList()
            }
            for (id in ids) {
                val stored = store.fetchMessage(EntityKey(radio, id)) ?: continue
                val message = when (conversation) {
                    is TimelineConversation.Direct -> stored.takeIf { it.contactID == conversation.contact.id }
                    is TimelineConversation.Channel -> {
                        val channel = conversation.channel
                        when {
                            stored.contactID != null || stored.channelIndex != channel.index -> null
                            radio == MockDataProvider.simulatorRadioId -> stored
                            stored.channelOccupantID != channel.occupantID &&
                                !(stored.channelOccupantID == null && stored.direction == MessageDirection.INCOMING) -> null
                            else -> channelHistory.fetchChannelTimelineMessage(
                                channelOrigin(radio, channel.index, channel.occupantID), EntityKey(radio, id),
                            )
                        }
                    }
                }
                if (message != null) {
                    if (event is MessageEvent.MessageFailed && !message.failureSeen) {
                        markFailedSendSeenIfVisible(message)
                        app.refreshConversations()
                    }
                    emit(when (event) {
                        is MessageEvent.DirectMessageReceived, is MessageEvent.ChannelMessageReceived ->
                            TimelineEvent.MessageReceived(conversation.id, message)
                        else -> TimelineEvent.MessageChanged(conversation.id, message.id)
                    })
                }
            }
        }
        override val diagnostics = TimelineDiagnostics { operation, failure -> report(operation, failure) }
    }

    fun sender(radio: RadioId): ComposerSendPort = object : ComposerSendPort {
        override suspend fun createPendingDirect(text: String, contact: ContactDTO, textType: TextType, replyToId: UUID?) =
            session(radio).messageService.createPendingMessage(text, contact, textType, replyToId).also {
                app.messageEventStream.send(MessageEvent.DirectMessageReceived(it, contact))
                app.refreshConversations()
            }
        override suspend fun createPendingChannel(text: String, channel: ChannelDTO, textType: TextType) =
            session(radio).messageService.createPendingChannelMessage(text, channel, textType).also {
                app.messageEventStream.send(MessageEvent.ChannelMessageReceived(it, channel.index))
                app.refreshConversations()
            }
        override suspend fun enqueueDM(envelope: DirectMessageEnvelope) =
            sendQueue(radio).enqueueDM(envelope)
        override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) =
            sendQueue(radio).enqueueChannel(envelope)
        override suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope) =
            sendQueue(radio).signalDMEnqueued(envelope)
        override suspend fun recordLocalEnqueueFailure(messageId: UUID) =
            failPending(savedMessage(radio, messageId))
    }

    private suspend fun savedMessage(radio: RadioId, id: UUID): MessageDTO =
        store.fetchMessage(EntityKey(radio, id))
            ?: throw MessageServiceException(MessageServiceError.SendFailed("Saved message not found"))

    private suspend fun enqueueChannel(radio: RadioId, id: UUID, isResend: Boolean) {
        val queue = sendQueue(radio)
        val localName = app.connectedDevice?.takeIf { it.radioId == radio }?.nodeName
        val message = savedMessage(radio, id)
        val index = message.channelIndex
            ?: throw MessageServiceException(MessageServiceError.ChannelNotFound)
        queue.enqueueChannel(
            ChannelMessageEnvelope(id, index, isResend, message.text, message.timestamp, localName, message.channelOccupantID),
        )
    }

    private fun acquireRoomKeepAlive(graph: RadioSessionContainer, room: RemoteNodeSessionDTO): AutoCloseable? =
        synchronized(visibleRoomKeepAlives) {
            val connection = container.connectionManager.snapshot.value
            if (!room.isRoom || !room.isConnected || room.radioId != graph.token.radioId ||
                container.sessions.current !== graph || connection.token != graph.token ||
                connection.state != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            ) return@synchronized null
            val session = EntityKey(room.radioId, room.id)
            val key = graph to session
            val owner = Any()
            graph.remoteNodeService.startSessionKeepAlive(session, room.publicKey)
            visibleRoomKeepAlives[key] = owner
            AutoCloseable {
                synchronized(visibleRoomKeepAlives) {
                    // Replacement mounts own the same service slot; an old disposal cannot stop them.
                    if (visibleRoomKeepAlives[key] === owner) {
                        visibleRoomKeepAlives.remove(key)
                        graph.remoteNodeService.stopSessionKeepAlive(session)
                    }
                }
            }
        }

    internal fun captureNavigationSession(token: com.meshcoreone.android.core.contracts.domain.SessionToken?): () -> Boolean {
        val initiatingSession = token?.let { expected -> container.sessions.current?.takeIf { it.token == expected } }
        return { initiatingSession != null && container.isCurrentSession(initiatingSession) }
    }

    internal fun roomForAuthentication(
        room: RemoteNodeSessionDTO,
        token: com.meshcoreone.android.core.contracts.domain.SessionToken?,
        isCurrentSession: () -> Boolean,
    ): RoomConversationDependencies {
        val initiatingSession = token?.let { expected -> container.sessions.current?.takeIf { it.token == expected } }
        return room(room) {
            if (initiatingSession == null || initiatingSession.token.radioId != room.radioId || !isCurrentSession() ||
                session(room.radioId) !== initiatingSession
            ) {
                throw MessageServiceException(MessageServiceError.NotConnected)
            }
            initiatingSession
        }
    }

    fun room(room: RemoteNodeSessionDTO): RoomConversationDependencies = room(room) { session(room.radioId) }

    private fun room(
        room: RemoteNodeSessionDTO,
        joinSession: () -> RadioSessionContainer,
    ): RoomConversationDependencies = object : RoomConversationDependencies {
        private fun roomKey() = EntityKey(room.radioId, room.id)
        override val keepAlive: Flow<RoomSessionKeepAlive?> = changes.onStart { emit(Unit) }
            .map {
                val connection = container.connectionManager.snapshot.value
                container.sessions.current?.takeIf {
                    it.token.radioId == room.radioId && connection.token == it.token &&
                        connection.state == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
                }
            }
            .distinctUntilChanged()
            .map { graph -> graph?.let { RoomSessionKeepAlive { session -> acquireRoomKeepAlive(it, session) } } }
        override val data = object : RoomConversationDataSource {
            override suspend fun fetchMessages(session: RemoteNodeSessionDTO) =
                store.fetchRoomMessages(EntityKey(session.radioId, session.id))
            override suspend fun refreshSession(session: RemoteNodeSessionDTO) =
                store.fetchRemoteNodeSession(EntityKey(session.radioId, session.id))
            override suspend fun contactFor(session: RemoteNodeSessionDTO) =
                store.fetchContact(session.radioId, session.publicKey)
            override suspend fun markAsRead(session: RemoteNodeSessionDTO) =
                store.resetRoomUnreadCount(EntityKey(session.radioId, session.id))
            override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) =
                store.markRoomFailedSendsSeen(EntityKey(session.radioId, session.id))
        }
        override val service = object : RoomConversationService {
            override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean) =
                joinSession().roomServerService.joinRoom(
                    room.radioId, contact, password, rememberPassword, contact.outPathLength,
                )
            override suspend fun reconnect(session: RemoteNodeSessionDTO) =
                this@AppChatsFeature.session(room.radioId).roomServerService.reconnectRoom(roomKey())
            override suspend fun post(session: RemoteNodeSessionDTO, text: String) =
                this@AppChatsFeature.session(room.radioId).roomServerService.postMessage(roomKey(), text)
            override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID) =
                this@AppChatsFeature.session(room.radioId).roomServerService.retryMessage(EntityKey(room.radioId, messageId))
        }
        override val events: Flow<RoomConversationEvent> get() = merge(
            this@AppChatsFeature.events().transform<MessageEvent, RoomConversationEvent> { event ->
                val id = when (event) {
                    is MessageEvent.RoomMessageReceived -> event.message.id
                    is MessageEvent.RoomMessageStatusUpdated -> event.messageID
                    is MessageEvent.RoomMessageFailed -> event.messageID
                    else -> return@transform
                }
                val message = store.fetchRoomMessage(EntityKey(room.radioId, id)) ?: return@transform
                if (message.sessionID == room.id) {
                    emit(when (event) {
                        is MessageEvent.RoomMessageReceived -> RoomConversationEvent.MessageReceived(room.id, message)
                        is MessageEvent.RoomMessageFailed -> RoomConversationEvent.MessageFailed(room.id, message.id)
                        else -> RoomConversationEvent.MessageChanged(room.id, message.id)
                    })
                }
            },
            changes.mapNotNull {
                store.fetchRemoteNodeSession(roomKey())?.let { RoomConversationEvent.ConnectionRecovered(room.id, it) }
            }.distinctUntilChanged(),
        )
        override val diagnostics = RoomConversationDiagnostics { operation, failure -> report(operation, failure) }
        override suspend fun removeDeliveredNotifications(session: RemoteNodeSessionDTO) {
            container.sessions.current?.takeIf { it.token.radioId == room.radioId }?.notificationService
                ?.removeDeliveredNotificationsForRoom(session.id)
        }
        override suspend fun updateBadgeCount() {
            container.sessions.current?.notificationService?.updateBadgeCount()
        }
        override fun conversationsChanged() = app.refreshConversations()
    }

    val channels = object : ChannelSheetDependencies {
        override val data = object : ChannelSheetDataSource {
            override suspend fun fetchChannels(radioId: RadioId) = store.fetchChannels(radioId)
            override suspend fun fetchChannel(radioId: RadioId, index: UByte) = store.fetchChannel(radioId, index)
        }
        override val service = object : ChannelSheetService {
            override suspend fun createChannelWithSecret(
                radioId: RadioId, maxChannels: UByte, name: String, secret: Bytes, regionScope: String?,
                onCommitted: (ChannelWriteReceipt) -> Unit,
            ): ChannelDTO =
                try {
                    session(radioId).channelService.createChannelWithSecret(radioId, maxChannels, name, secret, regionScope) { key, index ->
                        onCommitted(ChannelWriteReceipt(key, index))
                    }
                } catch (_: com.meshcoreone.android.core.services.contacts.ChannelMutationError.NoAvailableSlots) {
                    throw ChannelSheetError.NoAvailableSlots
                }

            override suspend fun joinHashtagChannel(
                radioId: RadioId, maxChannels: UByte, name: String,
                onCommitted: (ChannelWriteReceipt) -> Unit,
            ): ChannelDTO {
                if (app.connectionState != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY) {
                    store.fetchChannels(radioId).firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
                }
                return try {
                    session(radioId).channelService.joinHashtagChannel(radioId, maxChannels, name) { key, index ->
                        onCommitted(ChannelWriteReceipt(key, index))
                    }
                } catch (_: com.meshcoreone.android.core.services.contacts.ChannelMutationError.NoAvailableSlots) {
                    throw ChannelSheetError.NoAvailableSlots
                }
            }

            override suspend fun setChannelWithSecret(radioId: RadioId, index: UByte, name: String, secret: Bytes) =
                session(radioId).channelService.setChannelWithSecret(radioId, index, name, secret)
            override suspend fun setChannel(radioId: RadioId, index: UByte, name: String, passphrase: String) =
                session(radioId).channelService.setChannel(radioId, index, name, passphrase)
            override suspend fun setupPublicChannel(radioId: RadioId) = session(radioId).channelService.setupPublicChannel(radioId)
            override fun exportChannelUri(channel: ChannelDTO) =
                ChannelService.exportChannelURI(channel.name, channel.secret, channel.floodScope)
        }
        override val diagnostics = ChannelSheetDiagnostics { operation, failure -> report(operation, failure) }
    }

    fun environment(
        target: ComposerTarget,
        screenScope: kotlinx.coroutines.CoroutineScope,
        location: ComposerLocationSource,
    ): ComposerEnvironment = object : ComposerEnvironment {
        private val radio = when (target) {
            is ComposerTarget.Direct -> target.contact.radioId
            is ComposerTarget.Channel -> target.channel.radioId
        }
        override val connectionState = container.connectionManager.snapshot.map {
            if (it.token?.radioId == radio) it.state else com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED
        }.stateIn(screenScope, SharingStarted.Eagerly, com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED)
        override val device = changes.map {
            app.connectedDevice?.takeIf { it.radioId == radio }?.let {
                ComposerDeviceInfo(it.nodeName, it.publicKey, it.latitude, it.longitude, it.hasLocation)
            }
        }.stateIn(screenScope, SharingStarted.Eagerly, null)
        override val mentionContacts = changes.map {
            val radio = when (target) {
                is ComposerTarget.Direct -> target.contact.radioId
                is ComposerTarget.Channel -> target.channel.radioId
            }
            store.fetchContacts(radio)
        }.catch { failure -> report("Load mention contacts", failure); throw failure }
            .stateIn(screenScope, SharingStarted.Eagerly, emptyList())
        override val recentSenderOrder = changes.map {
            when (target) {
                is ComposerTarget.Direct -> emptyMap()
                is ComposerTarget.Channel -> channelMessages(target.channel)
                    .filter { it.senderNodeName != null }
                    .groupBy { checkNotNull(it.senderNodeName) }
                    .mapValues { (_, messages) -> messages.maxOf { it.timestamp } }
            }
        }.catch { failure ->
            report("Load recent senders", failure)
            if (failure is PersistenceStoreException && failure.cause is ChannelHistoryRejected) emit(emptyMap())
            else throw failure
        }
            .stateIn(screenScope, SharingStarted.Eagerly, emptyMap())
        override val location = location
    }

    fun report(operation: String, failure: Throwable?) {
        logger.log(Level.WARNING, operation, failure)
    }
}

internal data class CoordinatedRenderItem(val item: MessageItem) : TimelineRenderItem {
    override val id get() = item.id
}

internal data class TimelineRenderBatch(
    val inputs: List<Pair<MessageDTO, MessageBuildInputs>>,
    val env: EnvInputs,
)

/** PRIME uses the mounted feature's population/window implementation, never a competing fetch graph. */
internal class CoordinatedPrimeTimeline(
    private val feature: AppChatsFeature,
    private val scope: CoroutineScope,
) : PrimeTimeline {
    override var envInputs = EnvInputs.DEFAULT
    override val messages get() = coordinator?.messages ?: SnapshotList.empty()
    override var conversation: ChatConversationType? = null
        private set
    private var coordinator: ChatCoordinator? = null
    private var writer: ChatTimelineWriter? = null
    private var viewModel: ChatViewModel? = null
    private var binding: CoordinatedTimelineState? = null
    private var senderTables: () -> ChatSenderTables = { ChatSenderTables.EMPTY }

    override fun bind(coordinator: ChatCoordinator, senderTables: () -> ChatSenderTables): Boolean {
        close()
        val bound = coordinator.bindWriter(this, ChatWriterRole.PRIME,
            renderItemRebuilder = { binding?.requestRender() },
            renderStateInvalidated = { binding?.requestRender() },
        ) ?: return false
        this.coordinator = coordinator
        this.senderTables = senderTables
        writer = bound
        return true
    }

    override suspend fun open(
        conversation: ChatConversationType,
        reactions: ReactionIndexing?,
        populateMode: PopulateMode,
    ): TimelineOpenOutcome {
        val shared = coordinator ?: return TimelineOpenOutcome.Unavailable
        val bound = writer?.takeIf { it.isCurrent } ?: return TimelineOpenOutcome.Unavailable
        this.conversation = conversation
        val target = when (conversation) {
            is ChatConversationType.Dm -> TimelineConversation.Direct(conversation.contact)
            is ChatConversationType.Channel -> TimelineConversation.Channel(conversation.channel)
        }
        val dependencies = object : ChatTimelineDependencies by feature.timeline(target) {
            override fun bindTimeline(owner: Any, scope: CoroutineScope, zoneId: ZoneId, initial: ChatTimelineState) =
                feature.bindState(target, shared, this@CoordinatedPrimeTimeline, zoneId, initial,
                    bound, senderTables, envInputs).also { binding = it }
            override suspend fun prepareMessages(messages: List<MessageDTO>) =
                feature.prepareTimelineMessages(messages, reactions)
        }
        val mounted = ChatViewModel(target, dependencies, scope).also { viewModel = it }
        when (populateMode) {
            PopulateMode.REPLACE -> mounted.prime()
        }
        binding?.awaitRender()
        if (!bound.isCurrent) return TimelineOpenOutcome.Unavailable
        val state = mounted.state.value
        (state.loadError ?: state.passiveError)?.let { return TimelineOpenOutcome.Failed(it) }
        return TimelineOpenOutcome.Loaded
    }

    override fun close() {
        viewModel?.close()
        viewModel = null
        binding = null
        coordinator?.releaseWriter(this)
        coordinator = null
        writer = null
    }
}

/** A screen's presentation projection; messages and paging are always read back from the shared coordinator. */
internal class CoordinatedTimelineState(
    internal val coordinator: ChatCoordinator,
    private val owner: Any,
    private val zoneId: ZoneId,
    initial: ChatTimelineState,
    boundWriter: ChatTimelineWriter? = null,
    private val renderingScope: CoroutineScope? = null,
    invalidations: Flow<Unit> = emptyFlow(),
    private val assemble: (suspend (List<MessageDTO>, List<TimelineRow>) -> TimelineRenderBatch)? = null,
    private val report: (String, Throwable?) -> Unit = { _, _ -> },
) : TimelineStateStore {
    private val presentation = MutableStateFlow(initial)
    private val projectionLock = Any()
    private var rowMessages: List<MessageDTO>? = null
    private var rowAnchor: UUID? = null
    private var cachedRows: List<TimelineRow> = emptyList()
    private var renderedSource: List<MessageItem>? = null
    private var cachedRendered: List<CoordinatedRenderItem> = emptyList()
    @Volatile private var closed = false
    private var ready = false
    private var assemblyJob: Job? = null
    private val writer = boundWriter ?: checkNotNull(coordinator.bindWriter(
        owner, ChatWriterRole.INTERACTIVE,
        renderItemRebuilder = { requestRender() }, renderStateInvalidated = ::requestRender,
    ))
    private val observation: Job?
    init {
        ready = true
        observation = renderingScope?.launch { invalidations.collect { requestRender() } }
        requestRender()
    }
    override val state: StateFlow<ChatTimelineState> = object : StateFlow<ChatTimelineState> {
        override val value: ChatTimelineState get() = project()
        override val replayCache: List<ChatTimelineState> get() = listOf(value)

        @OptIn(InternalCoroutinesApi::class)
        override suspend fun collect(collector: FlowCollector<ChatTimelineState>): Nothing {
            combine(presentation, coordinator.messagesFlow, coordinator.renderStateFlow) { _, _, _ -> project() }
                .distinctUntilChanged().collect(collector)
            awaitCancellation()
        }
    }
    override val isCurrent: Boolean get() = !closed && writer.isCurrent

    private fun rowsFor(messages: List<MessageDTO>, anchor: UUID?): List<TimelineRow> = synchronized(projectionLock) {
        if (rowMessages !== messages || rowAnchor != anchor) {
            cachedRows = buildTimelineRows(messages, anchor, zoneId)
            rowMessages = messages
            rowAnchor = anchor
        }
        cachedRows
    }

    private fun renderedFor(items: List<MessageItem>): List<CoordinatedRenderItem> = synchronized(projectionLock) {
        if (renderedSource !== items) {
            cachedRendered = items.map(::CoordinatedRenderItem)
            renderedSource = items
        }
        cachedRendered
    }

    private fun project(): ChatTimelineState {
        val snapshot = coordinator.snapshot()
        val render = snapshot.renderState
        val messages = snapshot.messages
        val ui = presentation.value
        val unread = (ui.initialAnchor as? InitialTimelineAnchor.Message)?.messageId
        return ui.copy(
            messages = messages,
            rows = rowsFor(messages, unread),
            renderItems = renderedFor(render.items),
            renderItemIndexByID = render.itemIndexByID,
            isLoading = ui.isLoading || render.phase == ChatRenderState.LoadPhase.LOADING,
            hasLoadedOnce = render.phase == ChatRenderState.LoadPhase.LOADED,
            hasMoreMessages = render.hasMoreMessages,
            isLoadingOlder = render.isLoadingOlder,
            totalFetchedCount = Math.toIntExact(render.totalFetchedCount),
        )
    }

    override fun update(transform: (ChatTimelineState) -> ChatTimelineState) {
        if (closed) return
        var rebuild = false
        writer.performMutation {
            val before = project()
            val next = transform(before)
            rebuild = next.messages != before.messages || next.initialAnchor != before.initialAnchor
            if (next.messages != before.messages) writer.replaceAll(next.messages)
            when {
                next.isLoading -> writer.beginLoading()
                next.hasLoadedOnce -> writer.markLoaded()
                coordinator.renderState.phase != ChatRenderState.LoadPhase.UNINITIALIZED ->
                    writer.updateRenderState { it.with(phase = ChatRenderState.LoadPhase.UNINITIALIZED) }
            }
            val render = coordinator.renderState
            if (next.hasMoreMessages != render.hasMoreMessages ||
                next.isLoadingOlder != render.isLoadingOlder ||
                next.totalFetchedCount.toLong() != render.totalFetchedCount
            ) {
                writer.updateRenderState {
                    it.with(
                        hasMoreMessages = next.hasMoreMessages,
                        isLoadingOlder = next.isLoadingOlder,
                        totalFetchedCount = next.totalFetchedCount.toLong(),
                    )
                }
            }
            presentation.value = next.copy(
                messages = emptyList(), rows = emptyList(), renderItems = emptyList(), renderItemIndexByID = emptyMap(),
            )
        }
        if (rebuild) requestRender()
    }

    fun requestRender() {
        if (!ready || !isCurrent) return
        val scope = renderingScope ?: return
        val build = assemble ?: return
        assemblyJob?.cancel()
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val snapshot = coordinator.snapshot()
                val anchor = (presentation.value.initialAnchor as? InitialTimelineAnchor.Message)?.messageId
                val batch = build(snapshot.messages, rowsFor(snapshot.messages, anchor))
                writer.performMutation {
                    if (snapshot.revision == coordinator.renderStateID) writer.rebuildItems(batch.inputs, batch.env)
                    else requestRender()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                presentation.update { it.copy(passiveError = failure) }
                report("Build mounted chat render inputs", failure)
            }
        }
        assemblyJob = job
        job.start()
    }

    suspend fun awaitRender() {
        while (isCurrent) {
            val assembly = assemblyJob
            assembly?.join()
            val build = coordinator.buildItemsTask
            build?.join()
            if (assembly === assemblyJob && build === coordinator.buildItemsTask) return
        }
    }

    override suspend fun <T> performWindowOperation(operation: suspend () -> T): T =
        writer.performWindowOperation(operation)

    override fun close() {
        if (closed) return
        closed = true
        observation?.cancel()
        assemblyJob?.cancel()
        coordinator.releaseWriter(owner)
    }
}

private fun listEvent(event: MessageEvent): ChatListEvent? = when (event) {
    is MessageEvent.ChannelHistoryDeleted -> null
    is MessageEvent.DirectMessageReceived -> ChatListEvent.DIRECT_MESSAGE_RECEIVED
    is MessageEvent.ChannelMessageReceived -> ChatListEvent.CHANNEL_MESSAGE_RECEIVED
    is MessageEvent.RoomMessageReceived -> ChatListEvent.ROOM_MESSAGE_RECEIVED
    is MessageEvent.MessageStatusResolved -> ChatListEvent.MESSAGE_STATUS_RESOLVED
    is MessageEvent.MessageResent -> ChatListEvent.MESSAGE_RESENT
    is MessageEvent.MessageFailed -> ChatListEvent.MESSAGE_FAILED
    is MessageEvent.MessageRetrying -> ChatListEvent.MESSAGE_RETRYING
    is MessageEvent.HeardRepeatRecorded -> ChatListEvent.HEARD_REPEAT_RECORDED
    is MessageEvent.ReactionReceived -> ChatListEvent.REACTION_RECEIVED
    is MessageEvent.MessagesRegionUpdated -> ChatListEvent.MESSAGES_REGION_UPDATED
    is MessageEvent.RoutingChanged -> ChatListEvent.ROUTING_CHANGED
    is MessageEvent.RoomMessageStatusUpdated -> ChatListEvent.ROOM_MESSAGE_STATUS_UPDATED
    is MessageEvent.RoomMessageFailed -> ChatListEvent.ROOM_MESSAGE_FAILED
}
