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
import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.ChannelQuery
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryRejected
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
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
import com.meshcoreone.android.core.services.messaging.PendingMessageCreation
import com.meshcoreone.android.core.services.notifications.NotificationService
import com.meshcoreone.android.core.services.remote.startSessionKeepAlive
import com.meshcoreone.android.core.services.remote.stopSessionKeepAlive
import java.time.Clock
import java.time.Instant
import java.util.UUID
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
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId

class AppChatsFeature internal constructor(private val container: AppContainer) {
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
    private val store get() = container.processStore
    private val scope get() = container.uiScope
    private val app get() = container.appState
    private val navigation get() = container.navigation
    private val logger = Logger.getLogger("com.mc1.AppChatsFeature")
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

    internal fun pathDevice(radio: RadioId, screenScope: kotlinx.coroutines.CoroutineScope): StateFlow<DeviceDTO?> {
        fun current() = app.connectedDevice?.takeIf { it.radioId == radio }
        return changes.map { current() }.stateIn(screenScope, SharingStarted.Eagerly, current())
    }

    private val draftLock = Any()
    private data class UnresolvedCreation(
        val target: ComposerTarget, val revision: Long, val slotGeneration: Int,
        val receipt: PendingMessageCreation,
    )
    private val unresolvedCreations = java.util.concurrent.ConcurrentHashMap<UUID, UnresolvedCreation>()
    private data class DeferredDraft(
        val target: ComposerTarget, val text: String, val revision: Long, val slotGeneration: Int,
    )
    private val deferredDrafts = java.util.concurrent.ConcurrentHashMap<UUID, DeferredDraft>()
    private data class DeferredCommitted(
        val target: ComposerTarget, val slotGeneration: Int, val messageId: UUID,
    )
    private val deferredCommitted = java.util.concurrent.ConcurrentHashMap<UUID, DeferredCommitted>()
    private val committedRecoveryMutex = Mutex()
    val drafts = object : ComposerDraftStore {
        override val changes = MutableStateFlow(0L)
        override fun get(conversationId: ChatConversationID) = synchronized(draftLock) {
            app.draftStore.draft(conversationId)
        }
        override fun set(conversationId: ChatConversationID, text: String?) = synchronized(draftLock) {
            if (text == null) app.draftStore.clearDraft(conversationId)
            else app.draftStore.setDraft(text, conversationId)
            changes.value++
            Unit
        }
        override fun revision(conversationId: ChatConversationID) = synchronized(draftLock) {
            app.draftStore.revision(conversationId)
        }
        override fun restoreIfUnchanged(conversationId: ChatConversationID, revision: Long, text: String) =
            synchronized(draftLock) {
                app.draftStore.restoreIfUnchanged(conversationId, revision, text).also { restored ->
                    if (restored) changes.value++
                }
            }
    }

    init {
        scope.launch {
            merge(changes, app.channelSlotGenerations.map { Unit }).collect {
                for ((id, recovery) in unresolvedCreations) {
                    try {
                        val snapshot = recovery.receipt.snapshot
                        if (snapshot.state == PendingMessageCreation.State.WRITING) continue
                        val allocated = checkNotNull(snapshot.message)
                        val target = recovery.target
                        if (snapshot.state == PendingMessageCreation.State.COMMITTED) {
                            deferredCommitted[id] = DeferredCommitted(target, recovery.slotGeneration, id)
                            unresolvedCreations.remove(id, recovery)
                            continue
                        }
                        if (target is ComposerTarget.Channel) {
                            val current = store.fetchChannel(target.channel.radioId, target.channel.index)
                            if ((app.channelSlotGenerations.value[target.conversationId] ?: 0) !=
                                recovery.slotGeneration || current == null || current.id != target.channel.id ||
                                current.secret != target.channel.secret || !current.isEnabled
                            ) {
                                report("Uncertain creation belongs to a replaced channel slot",
                                    MessageServiceException(MessageServiceError.ChannelNotFound))
                                continue
                            }
                        }
                        val saved = store.fetchMessage(EntityKey(allocated.radioId, allocated.id))
                        if (target is ComposerTarget.Channel &&
                            (app.channelSlotGenerations.value[target.conversationId] ?: 0) != recovery.slotGeneration
                        ) {
                            report("Uncertain creation changed channel slot during reconciliation",
                                MessageServiceException(MessageServiceError.ChannelNotFound))
                            continue
                        }
                        if (saved == null) {
                            drafts.restoreIfUnchanged(target.conversationId, recovery.revision, allocated.text)
                        } else {
                            if (saved.id != allocated.id || saved.radioId != allocated.radioId ||
                                saved.contactID != allocated.contactID || saved.channelIndex != allocated.channelIndex ||
                                saved.text != allocated.text || saved.timestamp != allocated.timestamp ||
                                saved.createdAt != allocated.createdAt || saved.direction != allocated.direction ||
                                saved.textType != allocated.textType || saved.replyToID != allocated.replyToID
                            ) {
                                throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                            }
                            deferredCommitted[id] = DeferredCommitted(target, recovery.slotGeneration, id)
                        }
                        unresolvedCreations.remove(id, recovery)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: PersistenceStoreException) {
                        report("Uncertain composer creation could not reconcile its exact saved identity", failure)
                    }
                }
                for ((id, recovery) in deferredDrafts) {
                    val conversation = recovery.target.conversationId
                    if (drafts.revision(conversation) != recovery.revision ||
                        (app.channelSlotGenerations.value[conversation] ?: 0) != recovery.slotGeneration
                    ) {
                        deferredDrafts.remove(id, recovery)
                        continue
                    }
                    try {
                        val target = recovery.target
                        if (target is ComposerTarget.Channel) {
                            val current = store.fetchChannel(target.channel.radioId, target.channel.index)
                            if (current == null || current.id != target.channel.id ||
                                current.secret != target.channel.secret || !current.isEnabled
                            ) {
                                deferredDrafts.remove(id, recovery)
                                continue
                            }
                        }
                        if ((app.channelSlotGenerations.value[conversation] ?: 0) == recovery.slotGeneration) {
                            drafts.restoreIfUnchanged(conversation, recovery.revision, recovery.text)
                            deferredDrafts.remove(id, recovery)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: PersistenceStoreException) {
                        report("Deferred composer draft recovery could not read its channel", failure)
                    }
                }
                for ((id, recovery) in deferredCommitted) {
                    try {
                        val target = recovery.target
                        if (target is ComposerTarget.Channel) {
                            if ((app.channelSlotGenerations.value[target.conversationId] ?: 0) !=
                                recovery.slotGeneration
                            ) continue
                            val current = store.fetchChannel(target.channel.radioId, target.channel.index)
                            if (current == null || current.id != target.channel.id ||
                                current.secret != target.channel.secret || !current.isEnabled
                            ) continue
                        }
                        recoverCommittedCreation(recovery)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: PersistenceStoreException) {
                        report("Committed composer recovery could not update its saved row", failure)
                    } catch (failure: MessageServiceException) {
                        report("Committed composer recovery could not find its saved row or contact", failure)
                    }
                }
            }
        }
    }

    private suspend fun recoverCommittedCreation(recovery: DeferredCommitted) = committedRecoveryMutex.withLock {
        if (deferredCommitted[recovery.messageId] != recovery) return@withLock
        requireOriginalChannel(recovery.target, recovery.slotGeneration)
        val radio = recovery.target.conversationId.radioId
        val committed = savedMessage(radio, recovery.messageId)
        committed.contactID?.let { store.recomputeContactLastMessageDate(EntityKey(radio, it)) }
        sender(radio).recordLocalEnqueueFailure(recovery.messageId)
        val saved = savedMessage(radio, recovery.messageId)
        requireOriginalChannel(recovery.target, recovery.slotGeneration)
        val channelIndex = saved.channelIndex
        if (channelIndex != null) {
            app.messageEventStream.send(MessageEvent.ChannelMessageReceived(saved, channelIndex))
        } else {
            val contact = saved.contactID?.let { store.fetchContact(EntityKey(radio, it)) }
                ?: throw MessageServiceException(MessageServiceError.ContactNotFound)
            app.messageEventStream.send(MessageEvent.DirectMessageReceived(saved, contact))
        }
        app.refreshConversations()
        deferredCommitted.remove(recovery.messageId, recovery)
        Unit
    }

    private suspend fun requireOriginalChannel(target: ComposerTarget, generation: Int) {
        if (target !is ComposerTarget.Channel) return
        val current = store.fetchChannel(target.channel.radioId, target.channel.index)
        if ((app.channelSlotGenerations.value[target.conversationId] ?: 0) != generation ||
            current == null || current.id != target.channel.id || current.secret != target.channel.secret ||
            !current.isEnabled
        ) throw MessageServiceException(MessageServiceError.ChannelNotFound)
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
            override suspend fun fetchChannels(radioId: RadioId) = store.fetchChannels(radioId)
            override suspend fun fetchRemoteNodeSessions(radioId: RadioId) = store.fetchRemoteNodeSessions(radioId)
            override suspend fun fetchContacts(radioId: RadioId) = store.fetchContacts(radioId)
            override suspend fun fetchLastMessages(contactIds: List<UUID>, limit: Int) =
                store.fetchLastMessages(contactIds.map(::key).snapshot(), limit.toLong())
                    .entries.associate { it.key.id to it.value }
            override suspend fun fetchLastChannelMessages(channels: List<ChatChannelKey>, limit: Int) =
                store.fetchLastChannelMessages(
                    channels.map { ChannelQuery(it.radioId, it.channelIndex, it.id) }.snapshot(), limit.toLong(),
                ).entries.associate { it.key.id to it.value }
            override suspend fun fetchFailedSendConversationKeys(radioId: RadioId) =
                store.fetchFailedSendConversationKeys(radioId)
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
                override suspend fun clearChannel(radioId: RadioId, index: UByte) =
                    session(radioId).channelService.clearChannel(radioId, index)
                override suspend fun removeDeliveredNotifications(radioId: RadioId, channelIndex: UByte) =
                    current.notificationService.removeDeliveredNotifications(channelIndex, radioId)
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
                    if (selection.channel.radioId == message.radioId && selection.channel.index == message.channelIndex) {
                        store.markFailedSendsSeen(message.radioId, selection.channel.index)
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
                    is TimelineConversation.Channel -> store.fetchMessages(
                        conversation.channel.radioId, conversation.channel.index, limit.toLong(), offset.toLong(),
                    )
                }
            override suspend fun fetchMessageWindow(conversation: TimelineConversation, anchorSortDate: Instant?, floorLimit: Int) =
                when (conversation) {
                    is TimelineConversation.Direct -> store.fetchMessageWindow(
                        EntityKey(conversation.contact.radioId, conversation.contact.id), anchorSortDate, floorLimit.toLong(),
                    )
                    is TimelineConversation.Channel -> store.fetchMessageWindow(
                        conversation.channel.radioId, conversation.channel.index, anchorSortDate, floorLimit.toLong(),
                    )
                }
            override suspend fun fetchMessage(messageId: UUID) = store.fetchMessage(EntityKey(radio, messageId))
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
                    is TimelineConversation.Channel ->
                        store.clearChannelUnreadCount(radio, conversation.channel.index)
                }
                container.sessions.current?.notificationService?.updateBadgeCount()
                app.refreshConversations()
            }
            override suspend fun markFailedSendsSeen(conversation: TimelineConversation) = when (conversation) {
                is TimelineConversation.Direct -> store.markFailedSendsSeen(EntityKey(radio, conversation.contact.id))
                is TimelineConversation.Channel -> store.markFailedSendsSeen(radio, conversation.channel.index)
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
                val message = store.fetchMessage(EntityKey(radio, id)) ?: continue
                val belongs = when (conversation) {
                    is TimelineConversation.Direct -> message.contactID == conversation.contact.id
                    is TimelineConversation.Channel -> message.channelIndex == conversation.channel.index
                }
                if (belongs) {
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

    internal fun composer(
        target: ComposerTarget,
        environment: ComposerEnvironment,
        screenScope: CoroutineScope,
        decorateSender: (ComposerSendPort) -> ComposerSendPort = { it },
    ): ComposerStateHolder {
        val radio = target.conversationId.radioId
        return ComposerStateHolder(
            target, decorateSender(sender(radio)), drafts, environment, screenScope,
            ComposerTimer { kotlinx.coroutines.delay(it) }, ComposerDiagnostics(::report),
            sendScope = scope,
            captureSendPort = {
                val initiating = container.sessions.current
                val receipt = PendingMessageCreation()
                val operationId = UUID.randomUUID()
                val slotGeneration = app.channelSlotGenerations.value[target.conversationId] ?: 0
                fun isSameSlot() = target !is ComposerTarget.Channel ||
                    (app.channelSlotGenerations.value[target.conversationId] ?: 0) == slotGeneration
                suspend fun checkChannel() {
                    if (target is ComposerTarget.Channel) {
                        val current = store.fetchChannel(radio, target.channel.index)
                        if (!isSameSlot() || current == null || current.id != target.channel.id ||
                            current.secret != target.channel.secret || !current.isEnabled
                        ) throw MessageServiceException(MessageServiceError.ChannelNotFound)
                    }
                }
                val captured = {
                    if (initiating == null || initiating.token.radioId != radio || session(radio) !== initiating) {
                        throw MessageServiceException(MessageServiceError.NotConnected)
                    }
                    initiating
                }
                val pinned = sender(radio, captured, beforeChannelPublication = { checkChannel() }) {
                    if (initiating == null || initiating.token.radioId != radio ||
                        container.sessions.current !== initiating ||
                        app.connectionState != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
                    ) throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
                    initiating.chatSendQueueService
                }
                decorateSender(object : ComposerSendPort by pinned {
                    override fun retainDraftForRecovery(text: String, revision: Long?) {
                        deferredDrafts[operationId] = DeferredDraft(
                            target, text, checkNotNull(revision), slotGeneration,
                        )
                    }
                    override fun retainUncertainCreation(revision: Long?) {
                        val message = checkNotNull(receipt.snapshot.message)
                        unresolvedCreations[message.id] = UnresolvedCreation(
                            target, checkNotNull(revision), slotGeneration, receipt,
                        )
                    }
                    override suspend fun recordCommittedCreationFailure(messageId: UUID) {
                        val recovery = DeferredCommitted(target, slotGeneration, messageId)
                        deferredCommitted[messageId] = recovery
                        recoverCommittedCreation(recovery)
                    }
                    override suspend fun recordLocalEnqueueFailure(messageId: UUID) {
                        recordCommittedCreationFailure(messageId)
                    }
                    override suspend fun canRestoreDraft(): Boolean {
                        if (!isSameSlot()) return false
                        if (target !is ComposerTarget.Channel) return true
                        val current = store.fetchChannel(radio, target.channel.index)
                        return current != null && current.id == target.channel.id &&
                            current.secret == target.channel.secret && current.isEnabled && isSameSlot()
                    }
                    override fun creationOutcome(): ComposerCreationOutcome {
                        val snapshot = receipt.snapshot
                        return when (snapshot.state) {
                            PendingMessageCreation.State.NOT_STARTED,
                            PendingMessageCreation.State.NOT_COMMITTED -> ComposerCreationOutcome.NotCommitted
                            PendingMessageCreation.State.COMMITTED ->
                                ComposerCreationOutcome.Committed(checkNotNull(snapshot.message))
                            PendingMessageCreation.State.WRITING,
                            PendingMessageCreation.State.UNKNOWN -> {
                                ComposerCreationOutcome.Uncertain(snapshot.message)
                            }
                        }
                    }
                    override suspend fun createPendingDirect(
                        text: String, contact: ContactDTO, textType: TextType, replyToId: UUID?,
                    ) = withContext(receipt) {
                        pinned.createPendingDirect(text, contact, textType, replyToId)
                    }
                    override suspend fun createPendingChannel(
                        text: String, channelIndex: UByte, radioId: RadioId, textType: TextType,
                    ) = withContext(receipt) {
                        checkChannel()
                        pinned.createPendingChannel(text, channelIndex, radioId, textType).also {
                            checkChannel()
                        }
                    }
                    override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) {
                        checkChannel()
                        pinned.enqueueChannel(envelope.copy(
                            localNodeName = app.connectedDevice?.takeIf { it.radioId == radio }?.nodeName,
                        ))
                    }
                })
            },
        )
    }

    fun sender(radio: RadioId): ComposerSendPort =
        sender(radio, { session(radio) }, acceptedQueue = { sendQueue(radio) })

    private fun sender(
        radio: RadioId,
        acceptedSession: () -> RadioSessionContainer,
        beforeChannelPublication: suspend () -> Unit = {},
        acceptedQueue: () -> ChatSendQueueService,
    ): ComposerSendPort = object : ComposerSendPort {
        override suspend fun createPendingDirect(text: String, contact: ContactDTO, textType: TextType, replyToId: UUID?) =
            acceptedSession().messageService.createPendingMessage(text, contact, textType, replyToId).also {
                app.messageEventStream.send(MessageEvent.DirectMessageReceived(it, contact))
                app.refreshConversations()
            }
        override suspend fun createPendingChannel(text: String, channelIndex: UByte, radioId: RadioId, textType: TextType) =
            acceptedSession().messageService.createPendingChannelMessage(text, channelIndex, radioId, textType).also {
                beforeChannelPublication()
                app.messageEventStream.send(MessageEvent.ChannelMessageReceived(it, channelIndex))
                app.refreshConversations()
            }
        override suspend fun enqueueDM(envelope: DirectMessageEnvelope) =
            acceptedQueue().enqueueDM(envelope)
        override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) =
            acceptedQueue().enqueueChannel(envelope)
        override suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope) =
            sendQueue(radio).signalDMEnqueued(envelope)
        override suspend fun recordLocalEnqueueFailure(messageId: UUID) {
            if (store.fetchPendingSendsForMessage(EntityKey(radio, messageId)).isEmpty()) {
                val message = savedMessage(radio, messageId)
                if (message.status == MessageStatus.PENDING) failPending(message)
            }
        }
    }

    private suspend fun savedMessage(radio: RadioId, id: UUID): MessageDTO =
        store.fetchMessage(EntityKey(radio, id))
            ?: throw MessageServiceException(MessageServiceError.SendFailed("Saved message not found"))

    private suspend fun enqueueChannel(radio: RadioId, id: UUID, isResend: Boolean) {
        val localName = app.connectedDevice?.takeIf { it.radioId == radio }?.nodeName
        val message = savedMessage(radio, id)
        deferredCommitted[id]?.let { requireOriginalChannel(it.target, it.slotGeneration) }
        unresolvedCreations[id]?.let { requireOriginalChannel(it.target, it.slotGeneration) }
        val index = message.channelIndex
            ?: throw MessageServiceException(MessageServiceError.ChannelNotFound)
        val history = store as? ChannelHistoryPersisting
            ?: throw PersistenceStoreException(
                PersistenceStoreError.FetchFailed("Channel origin validation is unavailable"),
            )
        val origin = try {
            history.validateChannelMessageOrigin(EntityKey(radio, id))
        } catch (failure: PersistenceStoreException) {
            if (failure.cause !is ChannelHistoryRejected) throw failure
            throw MessageServiceException(MessageServiceError.ChannelNotFound, failure)
        }
        val queue = sendQueue(radio)
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
            override suspend fun setFloodScope(channel: ChannelDTO, scope: ChannelFloodScope): ChannelDTO {
                store.setChannelFloodScope(EntityKey(channel.radioId, channel.id), scope)
                return store.fetchChannel(channel.radioId, channel.index)
                    ?: throw MessageServiceException(MessageServiceError.ChannelNotFound)
            }
        }
        override val service = object : ChannelSheetService {
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
                is ComposerTarget.Channel -> store.fetchMessages(target.channel.radioId, target.channel.index)
                    .filter { it.senderNodeName != null }
                    .groupBy { checkNotNull(it.senderNodeName) }
                    .mapValues { (_, messages) -> messages.maxOf { it.timestamp } }
            }
        }.catch { failure -> report("Load recent senders", failure); throw failure }
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
