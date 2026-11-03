// PortedFrom: MC1Services/Sources/MC1Services/Services/NotificationActionHandler.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationReplyOutcomeUncertain
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * Executes the multi-service transactions behind notification actions: quick reply, mark-as-read and
 * reaction notifications, for the radio session of [notificationService]. The app layer installs thin
 * forwarders on [NotificationService] that delegate here and injects connection readiness and the local
 * node name through [configure].
 *
 * Every action key carries its radio. Mark-read writes go to the action's own radio rows, never to the
 * session's. A quick reply for another radio is not transmitted by this session's radio: it takes the
 * not-ready path (draft and failure notification), so a reply that arrives after a radio switch can
 * never be sent from, or stored against, the wrong radio.
 *
 * Swift `try?` reads become contained failures; follow-up steps after a successful send are contained
 * one by one so a Kotlin collaborator failure cannot turn a sent reply into a "not sent" report.
 */
class NotificationActionHandler(
    private val dataStore: PersistenceStoreProtocol,
    private val messageService: NotificationMessageSending,
    private val notificationService: NotificationService,
    private val roomServerService: NotificationRoomReading,
    private val syncCoordinator: NotificationConversationsNotifying,
) {
    private class Configuration(val isConnectionReady: () -> Boolean, val localNodeName: () -> String?)

    /** Null until [configure] runs; replaced atomically so both closures always come from one call. */
    @Volatile
    private var configuration: Configuration? = null

    private val radioId: RadioId get() = notificationService.radioId

    /** Injects the app-layer inputs. Idempotent; re-run per connection when notification handling is configured. */
    fun configure(isConnectionReady: () -> Boolean, localNodeName: () -> String?) {
        configuration = Configuration(isConnectionReady, localNodeName)
    }

    /** Whether [configure] has been called; distinguishes the pre-wiring window from an unknown node name. */
    internal val isConfigured: Boolean get() = configuration != null

    /** Swift's default `{ false }` before [configure]; a throwing closure counts as not ready. */
    private fun connectionReady(): Boolean {
        val closure = configuration?.isConnectionReady ?: return false
        return contained("isConnectionReady", false) { closure() }
    }

    // MARK: - Quick Reply

    suspend fun handleQuickReply(contact: EntityKey, text: String) {
        val stored = contained("fetchContact", null) { dataStore.fetchContact(contact) } ?: return

        if (connectionReady() && contact.radioId == radioId && stored.radioId == radioId) {
            sendReply(
                send = { messageService.sendDirectMessage(text, stored) },
                notSent = { replyNotSent(contact, stored.displayName, text) },
                accepted = {
                    // Clear unread state - user replied so they've seen the chat.
                    contained("clearUnreadCount", Unit) { dataStore.clearUnreadCount(contact) }
                    notificationService.removeDeliveredNotifications(contact.id)
                    notificationService.updateBadgeCount()
                    contained("notifyConversationsChanged", Unit) { syncCoordinator.notifyConversationsChanged() }
                },
            )
            return
        }

        replyNotSent(contact, stored.displayName, text)
    }

    private suspend fun replyNotSent(contact: EntityKey, displayName: String, text: String) {
        notificationService.saveDraft(contact, text)
        notificationService.postQuickReplyFailedNotification(displayName, contact)
    }

    suspend fun handleChannelQuickReply(radioId: RadioId, channelIndex: UByte, text: String) {
        // Fetch channel for display name in failure notification.
        val channel = contained("fetchChannel", null) { dataStore.fetchChannel(radioId, channelIndex) }
        val channelName = channelDisplayName(channel?.name, channelIndex)

        if (!connectionReady() || radioId != this.radioId) {
            notificationService.postChannelQuickReplyFailedNotification(channelName, radioId, channelIndex)
            return
        }

        sendReply(
            send = { messageService.sendChannelMessage(text, channelIndex, radioId) },
            notSent = {
                notificationService.postChannelQuickReplyFailedNotification(channelName, radioId, channelIndex)
            },
            accepted = {
                // Clear unread state - user replied so they've seen the channel.
                contained("clearChannelUnreadCount", Unit) { dataStore.clearChannelUnreadCount(radioId, channelIndex) }
                notificationService.removeDeliveredNotifications(channelIndex, radioId)
                notificationService.updateBadgeCount()
                contained("notifyConversationsChanged", Unit) { syncCoordinator.notifyConversationsChanged() }
            },
        )
    }

    private suspend fun sendReply(
        send: suspend () -> Unit,
        notSent: suspend () -> Unit,
        accepted: suspend () -> Unit,
    ) {
        val receipt = currentCoroutineContext()[NotificationActionReceipt] ?: NotificationActionReceipt()
        try {
            withContext(receipt) {
                send()
                receipt.accepted()
            }
        } catch (cancelled: CancellationException) {
            receipt.uncertain()
            withContext(NonCancellable) {
                when {
                    receipt.isAccepted -> accepted()
                    receipt.preventsReplay -> logger.log(Level.WARNING, "Reply acceptance unknown; not restoring an unsent draft", cancelled)
                    else -> notSent()
                }
            }
            throw cancelled
        } catch (failure: Exception) {
            receipt.uncertain()
            when {
                receipt.isAccepted -> logger.log(Level.WARNING, "Reply accepted before follow-up failed", failure)
                receipt.preventsReplay -> throw NotificationReplyOutcomeUncertain(failure)
                else -> {
                    logger.log(Level.INFO, "Quick reply failed before acceptance", failure)
                    notSent()
                    return
                }
            }
        }
        withContext(NonCancellable) { accepted() }
    }

    /** The stored name, then the localized fallback, then a last-resort English literal. */
    internal fun channelDisplayName(name: String?, index: UByte): String =
        name ?: notificationService.strings?.defaultChannelName(index.toLong()) ?: "Channel $index"

    // MARK: - Mark as Read

    suspend fun handleMarkAsRead(contact: EntityKey, messageID: UUID) = markRead("handleMarkAsRead", messageID) {
        dataStore.markMessageAsRead(EntityKey(contact.radioId, messageID))
        dataStore.clearUnreadCount(contact)
    }

    suspend fun handleChannelMarkAsRead(radioId: RadioId, channelIndex: UByte, messageID: UUID) =
        markRead("handleChannelMarkAsRead", messageID) {
            dataStore.markMessageAsRead(EntityKey(radioId, messageID))
            dataStore.clearChannelUnreadCount(radioId, channelIndex)
        }

    suspend fun handleRoomMarkAsRead(session: EntityKey, messageID: UUID) = markRead("handleRoomMarkAsRead", messageID) {
        roomServerService.markAsRead(session)
    }

    /**
     * Once started, the bounded local writes finish without caller cancellation interrupting their
     * return path. Record commitment only after every write returns, before cancellable caller handoff.
     * A write failure (including partial writes) retains the existing idempotent retry contract.
     */
    private suspend fun markRead(operation: String, messageID: UUID, writes: suspend () -> Unit) {
        currentCoroutineContext().ensureActive()
        val receipt = currentCoroutineContext()[NotificationActionReceipt] ?: NotificationActionReceipt()
        contained(operation, Unit) {
            withContext(NonCancellable) {
                writes()
                receipt.committed()
                notificationService.removeDeliveredNotification(messageID)
                notificationService.updateBadgeCount()
                syncCoordinator.notifyConversationsChanged()
            }
        }
    }

    // MARK: - Reactions

    /** Posts a notification when someone reacts to one of this session's outgoing messages. */
    suspend fun handleReactionNotification(messageID: UUID) {
        // Suppress entirely before configure(): posting then risks notifying the user about their own
        // reaction, while missing a stranger's reaction for a moment is harmless.
        val localNodeName = configuration?.localNodeName ?: run {
            logger.fine("Reaction notification suppressed: handler not yet configured")
            return
        }

        val key = EntityKey(radioId, messageID)
        val message = contained("fetchMessage", null) { dataStore.fetchMessage(key) } ?: return
        if (message.direction != MessageDirection.OUTGOING) return

        val latestReaction = contained("fetchReactions", null) { dataStore.fetchReactions(key, limit = 1) }
            ?.firstOrNull() ?: return

        // Self-reaction check; a failing name closure suppresses rather than risk a self notification.
        val nodeName = try {
            localNodeName()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Reaction notification suppressed: localNodeName failed", failure)
            return
        }
        if (nodeName != null && NotificationSwiftText.equal(latestReaction.senderName, nodeName)) return

        val contactID = message.contactID
        val channelIndex = message.channelIndex
        val isMuted = when {
            contactID != null ->
                contained("fetchContact", null) { dataStore.fetchContact(EntityKey(message.radioId, contactID)) }?.isMuted ?: false
            channelIndex != null ->
                contained("fetchChannel", null) { dataStore.fetchChannel(message.radioId, channelIndex) }?.isMuted ?: false
            else -> false
        }
        if (isMuted) return

        val preview = reactionPreview(message.text)
        val body = notificationService.strings?.reactionNotificationBody(latestReaction.emoji, preview)
            ?: "Reacted ${latestReaction.emoji} to your message: \"$preview\""

        notificationService.postReactionNotification(
            reactorName = latestReaction.senderName,
            body = body,
            messageID = messageID,
            contactID = contactID,
            channelIndex = channelIndex,
            radioId = if (channelIndex != null) message.radioId else null,
        )
    }

    private inline fun <T> contained(operation: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.FINE, "Notification action $operation failed", failure)
            fallback
        }

    companion object {
        private val logger: Logger = Logger.getLogger("com.mc1.NotificationActionHandler")

        const val REACTION_PREVIEW_MAX_LENGTH = 50
        const val REACTION_PREVIEW_KEEP_LENGTH = 47
        const val REACTION_PREVIEW_ELLIPSIS = "..."

        /** Truncates a reacted-to message for the notification body, counting Swift `Character`s. */
        fun reactionPreview(text: String): String =
            if (NotificationSwiftText.characterCount(text) > REACTION_PREVIEW_MAX_LENGTH) {
                NotificationSwiftText.prefix(text, REACTION_PREVIEW_KEEP_LENGTH) + REACTION_PREVIEW_ELLIPSIS
            } else {
                text
            }
    }
}
