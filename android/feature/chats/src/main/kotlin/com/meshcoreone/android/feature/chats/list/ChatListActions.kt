// PortedFrom: MC1/Views/Chats/ChatListActions.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ChatConversationActions.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ConversationActionError.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.ui.RadioCommandTimeout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

/** Failures surfaced by conversation delete actions (copy comes from `chats.error.*`). */
sealed class ConversationActionError(message: String) : Exception(message) {
    data object NotConnected : ConversationActionError("not connected to delete")
    data object ServicesUnavailable : ConversationActionError("services unavailable")
}

/**
 * A radio command that outlived [RadioCommandTimeout.delete]. Mirror of `MC1Services.TimeoutError`
 * (core:runtime is not feature-visible); the app's error presenter should map it to the shared timeout copy.
 */
class ConversationActionTimeout(val operationName: String) : Exception("$operationName timed out")

/** Layout-independent service sequences shared by the stack and split layouts. */
object ChatConversationActions {
    /** Clears a channel on the radio, then removes its delivered notifications and refreshes the badge. */
    suspend fun deleteChannel(channel: ChannelDTO, services: ChatConversationServices?) {
        val resolved = services ?: throw ConversationActionError.ServicesUnavailable
        resolved.clearChannel(channel.radioId, channel.index)
        resolved.removeDeliveredNotifications(channel.radioId, channel.index)
        resolved.updateBadgeCount()
    }

    /** Leaves a room session and removes its backing contact, then refreshes the badge. */
    suspend fun leaveRoom(session: RemoteNodeSessionDTO, services: ChatConversationServices?) {
        services?.leaveRoom(session)
        services?.updateBadgeCount()
    }
}

/** Bounds [block] by the delete timeout, turning only our own timeout into [ConversationActionTimeout]. */
internal suspend fun <T> withDeleteTimeout(operationName: String, block: suspend () -> T): T = try {
    withTimeout(RadioCommandTimeout.delete.toMillis()) { block() }
} catch (timeout: TimeoutCancellationException) {
    throw ConversationActionTimeout(operationName)
}

/**
 * Delete, pending-navigation and offline-announce sequences shared by both list layouts; only
 * [navigate] and [clearNavigationIfActive] differ between the stack and split paths.
 */
class ChatListActions(
    private val holder: ChatListStateHolder,
    private val dependencies: ChatListFeatureDependencies,
    private val scope: CoroutineScope,
    private val navigate: (ChatRoute) -> Unit,
    private val clearNavigationIfActive: (ChatRoute) -> Unit,
) {
    fun handleDeleteConversation(conversation: Conversation) {
        when (conversation) {
            is Conversation.Direct -> deleteDirectConversation(conversation.contact)
            is Conversation.Channel -> deleteChannelConversation(conversation.channel)
            is Conversation.Room -> holder.showRoomToDelete(conversation.session)
        }
    }

    /** Local write only: the row is hidden optimistically and restored if the write throws. */
    fun deleteDirectConversation(contact: ContactDTO) {
        if (holder.isDeletePending(contact.id)) return
        if (dependencies.connectionState.value != DeviceConnectionState.READY) {
            holder.showError(ChatListMessage.Failure(ConversationActionError.NotConnected))
            return
        }
        clearNavigationIfActive(ChatRoute.Direct(contact))
        holder.removeConversation(Conversation.Direct(contact))
        scope.launch {
            try {
                if (dependencies.connectionState.value != DeviceConnectionState.READY) {
                    throw ConversationActionError.NotConnected
                }
                dependencies.data.deleteDirectConversation(contact)
                // Confirm immediately: an inbound message re-setting lastMessageDate mid-delete would keep the row masked.
                holder.confirmDirectRemoval(contact)
                holder.requestConversationReload()
            } catch (cancellation: CancellationException) {
                holder.restoreConversation(Conversation.Direct(contact))
                throw cancellation
            } catch (failure: Exception) {
                holder.restoreConversation(Conversation.Direct(contact))
                holder.showError(ChatListMessage.Failure(failure))
            }
        }
    }

    /** Radio command: the row stays with a spinner until the ack; a failure or timeout leaves it with a retry alert. */
    fun deleteChannelConversation(channel: ChannelDTO) {
        if (holder.isDeletePending(channel.id)) return
        holder.markDeleting(channel.id)
        scope.launch {
            try {
                withDeleteTimeout("clearChannel") { ChatConversationActions.deleteChannel(channel, dependencies.conversationServices()) }
                clearNavigationIfActive(ChatRoute.Channel(channel))
                holder.removeConversation(Conversation.Channel(channel))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                holder.showChannelDeleteFailure(ChannelDeleteFailure(channel, ChatListMessage.Failure(failure)))
            } finally {
                holder.clearDeleting(channel.id)
            }
            holder.requestConversationReload()
        }
    }

    /** Room leave sends logout + remove-contact; a failure leaves the row and the trailing reload reconciles. */
    suspend fun deleteRoom(session: RemoteNodeSessionDTO) {
        if (holder.isDeletePending(session.id)) return
        holder.markDeleting(session.id)
        try {
            withDeleteTimeout("leaveRoom") { ChatConversationActions.leaveRoom(session, dependencies.conversationServices()) }
            clearNavigationIfActive(ChatRoute.Room(session))
            holder.removeConversation(Conversation.Room(session))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            dependencies.diagnostics.report("Failed to delete room", failure)
            holder.showError(ChatListMessage.Failure(failure))
        } finally {
            holder.clearDeleting(session.id)
        }
        holder.requestConversationReload()
    }

    fun handlePendingNavigation() {
        val contact = dependencies.navigation.pendingChatContact.value ?: return
        navigate(ChatRoute.Direct(contact))
        dependencies.navigation.clearPendingChatContact()
    }

    fun handlePendingChannelNavigation() {
        val channel = dependencies.navigation.pendingChannel.value ?: return
        navigate(ChatRoute.Channel(channel))
        dependencies.navigation.clearPendingChannel()
    }

    fun handlePendingRoomNavigation() {
        val session = dependencies.navigation.pendingRoomSession.value ?: return
        navigate(ChatRoute.Room(session))
        dependencies.navigation.clearPendingRoomSession()
    }

    /** Presents the auth sheet for a disconnected room a notification tap wants to open. */
    fun consumePendingRoomAuthentication() {
        val session = dependencies.navigation.pendingRoomAuthentication.value ?: return
        holder.showRoomAuthentication(session)
        dependencies.navigation.clearPendingRoomAuthentication()
    }

    /** True when the offline announcement should be posted (disconnected with a known radio). */
    fun shouldAnnounceOfflineState(): Boolean =
        dependencies.connectionState.value == DeviceConnectionState.DISCONNECTED && dependencies.currentRadioId.value != null

    /** Routes a tap: a disconnected room needs authentication first, anything else navigates. */
    fun open(route: ChatRoute) {
        val session = (route as? ChatRoute.Room)?.session
        if (session != null && !session.isConnected) holder.showRoomAuthentication(session) else navigate(route)
    }
}
