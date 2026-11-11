// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+ConversationList.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ViewModel/ChatViewModel+ConversationCache.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Conversation-list state holder (the list half of the iOS `ChatViewModel`). All state is one immutable
 * [ChatListState] published through [state]; every mutation copies. [scope] is injected so tests drive
 * it deterministically and the UI binds it to the composition.
 */
class ChatListStateHolder(
    private val dependencies: ChatListFeatureDependencies,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(ChatListState())
    val state: StateFlow<ChatListState> = mutableState

    // Fetch buffers (the raw rows the snapshot is derived from).
    private var reloadJob: Job? = null
    private var failedSendRefreshGeneration = 0

    private val data get() = dependencies.data
    private val diagnostics get() = dependencies.diagnostics

    /** Begins observing reload requests, connection drops, message events and the radio id. */
    fun start() {
        scope.launch { dependencies.reloadRequests.collect { requestConversationReload() } }
        scope.launch {
            var previous = dependencies.connectionState.value
            dependencies.connectionState.collect { now ->
                if (now == DeviceConnectionState.DISCONNECTED && previous != now) requestConversationReload()
                previous = now
            }
        }
        scope.launch {
            dependencies.messageEvents.collect { if (shouldRefreshFailedSendIndicators(it)) refreshFailedSendIndicators() }
        }
    }

    // region Search / filter

    fun setSearchText(text: String) = mutableState.update { it.copy(searchText = text) }
    fun setFilter(filter: ChatFilter) = mutableState.update { it.copy(selectedFilter = filter) }

    // endregion

    // region Banner / alerts

    var errorBannerMessage: ChatListMessage?
        get() = mutableState.value.errorBanner
        set(value) = mutableState.update { it.copy(errorBanner = value) }

    fun showError(message: ChatListMessage?) = mutableState.update { it.copy(errorAlert = message) }
    fun showChannelDeleteFailure(failure: ChannelDeleteFailure?) = mutableState.update { it.copy(channelDeleteFailure = failure) }
    fun showRoomToDelete(session: RemoteNodeSessionDTO?) = mutableState.update { it.copy(roomToDelete = session) }
    fun showRoomAuthentication(session: RemoteNodeSessionDTO?) = mutableState.update { it.copy(roomToAuthenticate = session) }

    // endregion

    // region Notification level and favorites

    suspend fun setNotificationLevel(conversation: Conversation, level: NotificationLevel) {
        if (dependencies.connectionState.value != DeviceConnectionState.READY) return
        val original = conversation.notificationLevel
        updateNotificationLevel(conversation, level)
        try {
            when (conversation) {
                is Conversation.Direct -> data.setContactMuted(conversation.contact.id, level == NotificationLevel.MUTED)
                is Conversation.Channel -> data.setChannelNotificationLevel(conversation.channel.id, level)
                is Conversation.Room -> data.setSessionNotificationLevel(conversation.session.id, level)
            }
            dependencies.conversationServices()?.updateBadgeCount()
        } catch (cancellation: CancellationException) {
            updateNotificationLevel(conversation, original)
            throw cancellation
        } catch (failure: Exception) {
            updateNotificationLevel(conversation, original)
            diagnostics.report("Failed to set notification level", failure)
        }
    }

    /** Live row for [id]; swipe chrome keeps the value from first reveal, so toggles must not use that copy. */
    fun liveConversation(id: UUID): Conversation? {
        val current = mutableState.value
        current.contacts.firstOrNull { it.id == id }?.let { return Conversation.Direct(it) }
        current.channels.firstOrNull { it.id == id }?.let { return Conversation.Channel(it) }
        current.roomSessions.firstOrNull { it.id == id }?.let { return Conversation.Room(it) }
        return null
    }

    suspend fun toggleMute(conversation: Conversation) {
        val current = liveConversation(conversation.id) ?: return
        setNotificationLevel(current, if (current.isMuted) NotificationLevel.ALL else NotificationLevel.MUTED)
    }

    suspend fun setFavorite(conversation: Conversation, isFavorite: Boolean) {
        if (dependencies.connectionState.value != DeviceConnectionState.READY) return
        val current = liveConversation(conversation.id) ?: return
        if (current.isFavorite == isFavorite) return
        toggleFavorite(current)
    }

    /**
     * Contacts push the flag to the device and update only after it confirms; channels and rooms are
     * app-only and update optimistically with rollback.
     */
    suspend fun toggleFavorite(conversation: Conversation) {
        if (dependencies.connectionState.value != DeviceConnectionState.READY) return
        val current = liveConversation(conversation.id) ?: return
        val original = current.isFavorite
        val target = !original
        when (current) {
            is Conversation.Direct -> toggleContactFavorite(current, target)
            is Conversation.Channel -> toggleAppOnlyFavorite(current, target, original) { data.setChannelFavorite(current.channel.id, target) }
            is Conversation.Room -> toggleAppOnlyFavorite(current, target, original) { data.setSessionFavorite(current.session.id, target) }
        }
    }

    private suspend fun toggleContactFavorite(current: Conversation.Direct, target: Boolean) {
        mutableState.update { it.copy(togglingFavoriteId = current.contact.id) }
        try {
            val services = dependencies.conversationServices()
            if (services != null) {
                services.setContactFavorite(current.contact.id, target)
                applyFavorite(current, target)
            } else {
                diagnostics.report("Failed to toggle contact favorite: services unavailable", null)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            diagnostics.report("Failed to toggle contact favorite", failure)
        } finally {
            mutableState.update { it.copy(togglingFavoriteId = null) }
        }
    }

    private suspend fun toggleAppOnlyFavorite(current: Conversation, target: Boolean, original: Boolean, persist: suspend () -> Unit) {
        applyFavorite(current, target)
        try {
            persist()
        } catch (cancellation: CancellationException) {
            applyFavorite(current, original)
            throw cancellation
        } catch (failure: Exception) {
            applyFavorite(current, original)
            diagnostics.report("Failed to toggle favorite", failure)
        }
    }

    private fun updateNotificationLevel(conversation: Conversation, level: NotificationLevel) = updateConversation(
        conversation,
        direct = { it.withMuted(level == NotificationLevel.MUTED) },
        channel = { it.withNotificationLevel(level) },
        room = { it.withNotificationLevel(level) },
    )

    private fun applyFavorite(conversation: Conversation, isFavorite: Boolean) = updateConversation(
        conversation,
        direct = { it.withFavorite(isFavorite) },
        channel = { it.withFavorite(isFavorite) },
        room = { it.withFavorite(isFavorite) },
    )

    /** Replaces the element backing [conversation] in its buffer and republishes the snapshot in one update. */
    private fun updateConversation(
        conversation: Conversation,
        direct: (ContactDTO) -> ContactDTO,
        channel: (ChannelDTO) -> ChannelDTO,
        room: (RemoteNodeSessionDTO) -> RemoteNodeSessionDTO,
    ) = mutableState.update { current ->
        val next = when (conversation) {
            is Conversation.Direct -> current.copy(contacts = current.contacts.map { if (it.id == conversation.contact.id) direct(it) else it })
            is Conversation.Channel -> current.copy(channels = current.channels.map { if (it.id == conversation.channel.id) channel(it) else it })
            is Conversation.Room -> current.copy(roomSessions = current.roomSessions.map { if (it.id == conversation.session.id) room(it) else it })
        }
        recompute(next)
    }

    // endregion

    // region Buffers and snapshot

    /** Replaces the fetch buffers; call [recomputeSnapshot] to publish (mirrors setting the Swift buffers). */
    internal fun setBuffers(
        contacts: List<ContactDTO>? = null,
        channels: List<ChannelDTO>? = null,
        roomSessions: List<RemoteNodeSessionDTO>? = null,
    ) = mutableState.update {
        it.copy(contacts = contacts ?: it.contacts, channels = channels ?: it.channels, roomSessions = roomSessions ?: it.roomSessions)
    }

    internal fun setHasLoadedOnce(value: Boolean) = mutableState.update { it.copy(hasLoadedOnce = value) }
    internal fun setLastMessages(messages: Map<UUID, MessageDTO>) = mutableState.update { it.copy(lastMessages = messages) }
    internal fun setSnapshotForTesting(snapshot: ConversationSnapshot) = mutableState.update { it.copy(snapshot = snapshot) }

    /** Rebuilds the favorite/other split from the buffers, skipping an identical republish. */
    fun recomputeSnapshot() = mutableState.update(::recompute)

    private fun recompute(current: ChatListState): ChatListState {
        val contactRows = current.contacts
            .filter { it.type != ContactType.REPEATER && !it.isBlocked && it.id !in current.pendingRemovalIds }
            .map { Conversation.Direct(it) }
        val channelRows = current.channels
            .filter { (it.name.isNotEmpty() || it.hasSecret) && it.id !in current.pendingRemovalIds }
            .map { Conversation.Channel(it) }
        val roomRows = current.roomSessions.filter { it.id !in current.pendingRemovalIds }.map { Conversation.Room(it) }
        val all = contactRows + channelRows + roomRows
        val snapshot = ConversationSnapshot(
            favorites = sortedByLastMessage(all.filter { it.isFavorite }),
            others = sortedByLastMessage(all.filter { !it.isFavorite }),
        )
        if (snapshot == current.snapshot) return current
        val channels = current.channels.associateBy { it.id }
        val stale = (current.snapshot.favorites + current.snapshot.others).filterIsInstance<Conversation.Channel>()
            .filter { old -> channels[old.id]?.let { sameChannelHistory(old.channel, it) } != true }.map { it.id }.toSet()
        return current.copy(snapshot = snapshot, snapshotGeneration = current.snapshotGeneration + 1,
            lastMessages = current.lastMessages.filterKeys { it !in stale }, failedSendIds = current.failedSendIds - stale)
    }

    private fun sameChannelHistory(left: ChannelDTO, right: ChannelDTO): Boolean =
        left.id == right.id && left.radioId == right.radioId && left.index == right.index &&
            left.occupantID == right.occupantID && left.secret == right.secret && left.isEnabled == right.isEnabled

    /** Most recent first; rows without a message date sort last; ties keep insertion order (stable). */
    fun sortedByLastMessage(items: List<Conversation>): List<Conversation> =
        items.sortedByDescending { it.lastMessageDate ?: NO_MESSAGE_SENTINEL }

    /** Drops pending ids the fresh fetch confirms are gone, so a confirmed deletion self-heals. */
    private fun reconciled(current: ChatListState): ChatListState {
        if (current.pendingRemovalIds.isEmpty()) return current
        val present = current.contacts.map { it.id }.toSet() + current.channels.map { it.id } + current.roomSessions.map { it.id }
        return current.copy(pendingRemovalIds = current.pendingRemovalIds.intersect(present))
    }

    // endregion

    // region Delete bookkeeping

    fun isDeletePending(id: UUID): Boolean = mutableState.value.isDeletePending(id)

    fun markDeleting(id: UUID) = mutableState.update { it.copy(deletingIds = it.deletingIds + id) }
    fun clearDeleting(id: UUID) = mutableState.update { it.copy(deletingIds = it.deletingIds - id) }

    /** Hides a conversation, recording the id so a racing reload cannot resurrect it. */
    fun removeConversation(conversation: Conversation) = mutableState.update { current ->
        val masked = current.copy(pendingRemovalIds = current.pendingRemovalIds + conversation.id)
        recompute(
            when (conversation) {
                is Conversation.Direct -> masked.copy(contacts = masked.contacts.filter { it.id != conversation.contact.id })
                is Conversation.Channel -> masked.copy(channels = masked.channels.filter { it.id != conversation.channel.id })
                is Conversation.Room -> masked.copy(roomSessions = masked.roomSessions.filter { it.id != conversation.session.id })
            },
        )
    }

    /** Re-admits a row after its delete failed, reusing the caller-held DTO. */
    fun restoreConversation(conversation: Conversation) = mutableState.update { current ->
        val unmasked = current.copy(pendingRemovalIds = current.pendingRemovalIds - conversation.id)
        recompute(
            when (conversation) {
                is Conversation.Direct -> if (unmasked.contacts.none { it.id == conversation.contact.id }) {
                    unmasked.copy(contacts = unmasked.contacts + conversation.contact)
                } else unmasked
                is Conversation.Channel -> if (unmasked.channels.none { it.id == conversation.channel.id }) {
                    unmasked.copy(channels = unmasked.channels + conversation.channel)
                } else unmasked
                is Conversation.Room -> if (unmasked.roomSessions.none { it.id == conversation.session.id }) {
                    unmasked.copy(roomSessions = unmasked.roomSessions + conversation.session)
                } else unmasked
            },
        )
    }

    /** Confirms a direct conversation's local clear: drops the mask and purges the buffer together. */
    fun confirmDirectRemoval(contact: ContactDTO) = mutableState.update {
        recompute(it.copy(pendingRemovalIds = it.pendingRemovalIds - contact.id, contacts = it.contacts.filter { row -> row.id != contact.id }))
    }

    /** Clears all conversation data (device forgotten/removed) so the list shows no stale entries. */
    fun clearConversations() {
        failedSendRefreshGeneration++
        mutableState.update {
            recompute(
                it.copy(
                    contacts = emptyList(), channels = emptyList(), roomSessions = emptyList(),
                    pendingRemovalIds = emptySet(), deletingIds = emptySet(), lastMessages = emptyMap(), failedSendIds = emptySet(),
                ),
            )
        }
    }

    // endregion

    // region Loading

    suspend fun loadConversations(radioId: com.meshcoreone.android.core.model.RadioId) {
        mutableState.update { it.copy(isLoading = true, errorBanner = null) }
        try {
            val fetched = data.fetchConversations(radioId)
            mutableState.update { recompute(it.copy(contacts = fetched)) }
        } catch (cancellation: CancellationException) {
            mutableState.update { it.copy(isLoading = false) }
            throw cancellation
        } catch (failure: Exception) {
            mutableState.update { it.copy(errorBanner = ChatListMessage.LoadConversationsFailed) }
            diagnostics.report("loadConversations failed", failure)
        }
        mutableState.update { it.copy(hasLoadedOnce = true, isLoading = false) }
    }

    /**
     * Single entry point for every list reload: cancel-and-replace, so the latest request wins and a
     * superseded one returns at a cancellation gate before committing. Returns the new job.
     */
    fun requestConversationReload(): Job? {
        reloadJob?.cancel()
        val radioId = dependencies.currentRadioId.value
        if (radioId == null) {
            reloadJob = null
            clearConversations()
            return null
        }
        val job = scope.launch { performConversationReload(radioId) }
        reloadJob = job
        return job
    }

    private suspend fun performConversationReload(radioId: com.meshcoreone.android.core.model.RadioId) {
        mutableState.update { it.copy(isLoading = true) }
        try {
            var banner: ChatListMessage? = null
            val fetchedContacts = try {
                data.fetchConversations(radioId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                banner = ChatListMessage.LoadConversationsFailed
                diagnostics.report("performConversationReload fetchConversations failed", failure)
                null
            }
            if (!currentJobActive()) return
            // Channel/room failures stay silent (only fetchConversations sets the banner).
            val fetchedChannels = silentFetch { data.fetchChannels(radioId) }
            val fetchedRooms = silentFetch { data.fetchRemoteNodeSessions(radioId) }?.filter { it.isRoom }
            if (!currentJobActive()) return
            // No suspension between the last cancellation check and this commit.
            mutableState.update { current ->
                recompute(
                    reconciled(
                        current.copy(
                            contacts = fetchedContacts ?: current.contacts,
                            channels = fetchedChannels ?: current.channels,
                            roomSessions = fetchedRooms ?: current.roomSessions,
                            errorBanner = banner,
                            hasLoadedOnce = true,
                        ),
                    ),
                )
            }
            loadLastMessagePreviews()
            refreshFailedSendIndicators()
        } finally {
            mutableState.update { it.copy(isLoading = false) }
        }
    }

    private suspend fun currentJobActive(): Boolean = kotlin.coroutines.coroutineContext[Job]?.isActive ?: true

    private suspend fun <T> silentFetch(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        diagnostics.report("Conversation reload fetch failed", failure)
        null
    }

    // endregion

    // region Previews and failed-send indicators

    /** Batch-loads last-message previews, skipping outgoing reactions unless failed (DMs evict only when empty). */
    suspend fun loadLastMessagePreviews() {
        val snapshot = mutableState.value
        val reactions = dependencies.reactions
        var previews = snapshot.lastMessages
        if (snapshot.contacts.isNotEmpty()) {
            try {
                val fetched = data.fetchLastMessages(snapshot.contacts.map { it.id }, DIRECT_PREVIEW_LIMIT)
                for (contact in snapshot.contacts) {
                    val messages = fetched[contact.id]
                    val last = messages?.lastOrNull {
                        !(it.direction == MessageDirection.OUTGOING && reactions.isDirectReaction(it.text)) || it.status == MessageStatus.FAILED
                    }
                    previews = when {
                        last != null -> previews + (contact.id to last)
                        messages.isNullOrEmpty() -> previews - contact.id
                        else -> previews
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                diagnostics.report("Failed to load contact message previews", failure)
            }
        }
        if (snapshot.channels.isNotEmpty()) {
            try {
                val keys = snapshot.channels.map { ChatChannelKey(it.radioId, it.index, it.id, it.occupantID) }
                val fetched = data.fetchLastChannelMessages(keys, CHANNEL_PREVIEW_LIMIT)
                for (channel in snapshot.channels) {
                    val messages = fetched[channel.id] ?: continue
                    val last = messages.lastOrNull {
                        !(it.direction == MessageDirection.OUTGOING && reactions.isChannelReaction(it.text) && it.status != MessageStatus.FAILED)
                    }
                    previews = if (last != null) previews + (channel.id to last) else previews - channel.id
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                diagnostics.report("Failed to load channel message previews", failure)
            }
        }
        mutableState.update { current ->
            val channels = current.channels.associateBy { it.id }
            val stale = snapshot.channels.filter { old -> channels[old.id]?.let { sameChannelHistory(old, it) } != true }
                .map { it.id }.toSet()
            current.copy(lastMessages = previews.filterKeys { it !in stale } +
                current.lastMessages.filterKeys { it in stale })
        }
    }

    /** Re-runs the failed-send query without reloading rows; a superseded refresh never applies. */
    suspend fun refreshFailedSendIndicators() {
        val generation = ++failedSendRefreshGeneration
        val radioId = dependencies.currentRadioId.value
        if (radioId == null) {
            mutableState.update { it.copy(failedSendIds = emptySet()) }
            return
        }
        val channels = mutableState.value.channels
        try {
            val keys = data.fetchFailedSendConversationKeys(radioId,
                channels.map { ChatChannelKey(it.radioId, it.index, it.id, it.occupantID) })
            if (generation != failedSendRefreshGeneration || !currentJobActive()) return
            mutableState.update { current ->
                val latest = current.channels.associateBy { it.id }
                val stale = channels.filter { old -> latest[old.id]?.let { sameChannelHistory(old, it) } != true }
                    .map { it.id }.toSet()
                current.copy(failedSendIds = (keys.contactIDs + (keys.channelIDs - stale) + keys.roomSessionIDs).toSet())
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            if (generation == failedSendRefreshGeneration) diagnostics.report("Failed to load failed-send indicators", failure)
        }
    }

    /** Status-resolved ACKs only re-query when a badge is already showing. */
    fun shouldRefreshFailedSendIndicators(event: ChatListEvent): Boolean = when (event) {
        ChatListEvent.MESSAGE_FAILED, ChatListEvent.MESSAGE_RESENT, ChatListEvent.ROOM_MESSAGE_FAILED -> true
        ChatListEvent.MESSAGE_STATUS_RESOLVED, ChatListEvent.ROOM_MESSAGE_STATUS_UPDATED -> mutableState.value.failedSendIds.isNotEmpty()
        else -> false
    }

    // endregion

    private companion object {
        val NO_MESSAGE_SENTINEL: Instant = Instant.EPOCH.minusSeconds(62_135_769_600L)
        const val DIRECT_PREVIEW_LIMIT = 10
        const val CHANNEL_PREVIEW_LIMIT = 20
    }
}
