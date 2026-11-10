// AndroidOnly: WP-306 Feature-owned dependency seams (contracts.md XFeatureDependencies pattern); the app layer adapts core:services/data/runtime to these.
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.FailedSendConversationKeys
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Identity of a channel slot for the batched last-message fetch. */
data class ChatChannelKey(val radioId: RadioId, val channelIndex: UByte, val id: UUID)

/** Persistence reads/writes the list needs (the iOS `DataStore` subset ChatViewModel used). */
interface ChatListDataSource {
    suspend fun fetchConversations(radioId: RadioId): List<ContactDTO>
    suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO>
    suspend fun fetchRemoteNodeSessions(radioId: RadioId): List<RemoteNodeSessionDTO>
    suspend fun fetchContacts(radioId: RadioId): List<ContactDTO>
    suspend fun fetchLastMessages(contactIds: List<UUID>, limit: Int): Map<UUID, List<MessageDTO>>
    suspend fun fetchLastChannelMessages(channels: List<ChatChannelKey>, limit: Int): Map<UUID, List<MessageDTO>>
    suspend fun fetchFailedSendConversationKeys(radioId: RadioId): FailedSendConversationKeys
    suspend fun setContactMuted(contactId: UUID, isMuted: Boolean)
    suspend fun setChannelNotificationLevel(channelId: UUID, level: NotificationLevel)
    suspend fun setSessionNotificationLevel(sessionId: UUID, level: NotificationLevel)
    suspend fun setChannelFavorite(channelId: UUID, isFavorite: Boolean)
    suspend fun setSessionFavorite(sessionId: UUID, isFavorite: Boolean)

    /** Local clear of a direct conversation (messages and `lastMessageDate`); no radio command. */
    suspend fun deleteDirectConversation(contact: ContactDTO)
}

/** Radio/notification services behind row actions; resolved lazily so a missing service surfaces as an error. */
interface ChatConversationServices {
    /** Pushes the favorite flag to the device and returns once it is confirmed. */
    suspend fun setContactFavorite(contactId: UUID, isFavorite: Boolean)
    suspend fun clearChannel(radioId: RadioId, index: UByte)
    suspend fun removeDeliveredNotifications(radioId: RadioId, channelIndex: UByte)

    /** Leaves the room session then removes its backing contact. */
    suspend fun leaveRoom(session: RemoteNodeSessionDTO)
    suspend fun updateBadgeCount()
}

/** Whether a stored message text is a reaction (reactions are skipped for previews unless failed). */
interface ChatReactionClassifier {
    fun isDirectReaction(text: String): Boolean
    fun isChannelReaction(text: String): Boolean
}

/** Message-event kinds that matter to the list (mirror of the `MessageEvent` cases). */
enum class ChatListEvent {
    DIRECT_MESSAGE_RECEIVED, CHANNEL_MESSAGE_RECEIVED, ROOM_MESSAGE_RECEIVED, MESSAGE_FAILED, MESSAGE_RESENT,
    ROOM_MESSAGE_FAILED, MESSAGE_STATUS_RESOLVED, ROOM_MESSAGE_STATUS_UPDATED, MESSAGE_RETRYING,
    HEARD_REPEAT_RECORDED, REACTION_RECEIVED, MESSAGES_REGION_UPDATED, ROUTING_CHANGED,
}

/** Navigation hand-off the shell owns (pending requests from notifications/links, and the selected route). */
interface ChatListNavigation {
    val pendingChatContact: StateFlow<ContactDTO?>
    val pendingChannel: StateFlow<ChannelDTO?>
    val pendingRoomSession: StateFlow<RemoteNodeSessionDTO?>
    val pendingRoomAuthentication: StateFlow<RemoteNodeSessionDTO?>
    /** Acknowledges only when the shell still owns this route as its current pending hand-off. */
    fun acknowledgePendingRoute(route: ChatRoute): Boolean
    fun clearPendingRoomAuthentication()
}

/** Diagnostics sink (no Android Log on the JVM test classpath). */
fun interface ChatListDiagnostics {
    fun report(message: String, failure: Throwable?)
}

/**
 * Everything the Chats list needs from the app. Without an implementation bound the entry keeps the
 * honest not-yet-ported shell.
 */
interface ChatListFeatureDependencies {
    val data: ChatListDataSource
    val reactions: ChatReactionClassifier
    val navigation: ChatListNavigation
    val links: ChatLinkEnvironment
    val connectionState: StateFlow<DeviceConnectionState>
    val currentRadioId: StateFlow<RadioId?>

    /** Emits when services are replaced or the conversation set changed elsewhere (`servicesVersion`/`conversationsVersion`). */
    val reloadRequests: Flow<Unit>
    val messageEvents: Flow<ChatListEvent>
    val clock: Clock
    val diagnostics: ChatListDiagnostics get() = ChatListDiagnostics { _, _ -> }

    /** Null when radio services are unavailable. */
    fun conversationServices(): ChatConversationServices?
}

enum class ContactLinkCaptureDisposition { Lookup, IgnoredSelfContact, StaleContext }

enum class ContactLinkCompletion { Navigated, ConfirmationStaged, IgnoredSelfContact, StaleContext, WrongTarget }

interface ContactLinkContext {
    val radioId: RadioId?
    val selfPublicKey: com.meshcoreone.android.core.protocol.bytes.Bytes?
    val disposition: ContactLinkCaptureDisposition
}

interface ContactLinkResolvedTarget {
    val radioId: RadioId?
    val selfPublicKey: com.meshcoreone.android.core.protocol.bytes.Bytes?
}

sealed interface ContactLinkTargetResolution {
    data object Stale : ContactLinkTargetResolution
    data class Current(val target: ContactLinkResolvedTarget) : ContactLinkTargetResolution
}

sealed interface ContactLinkTargetCompletion {
    data object TargetChanged : ContactLinkTargetCompletion
    data class Completed(val outcome: ContactLinkCompletion) : ContactLinkTargetCompletion
    data class Offered(val receipt: ContactLinkHandoffReceipt) : ContactLinkTargetCompletion
}

sealed interface ContactLinkHandoffOutcome {
    data class Accepted(val outcome: ContactLinkCompletion) : ContactLinkHandoffOutcome
    data object Superseded : ContactLinkHandoffOutcome
}

interface ContactLinkHandoffReceipt {
    suspend fun awaitOutcome(): ContactLinkHandoffOutcome
}

class ContactLinkCancellation private constructor() {
    private val cancelled = AtomicBoolean(false)
    private val commitGate = Any()

    fun ensureActive() {
        if (cancelled.get()) throw CancellationException("Contact-link lookup was cancelled")
    }

    /** Orders this token's cancellation observer with the non-publishing commit, not with Job.cancel or publication. */
    fun <T> commitIfActive(commit: () -> T): T = synchronized(commitGate) {
        ensureActive()
        commit()
    }

    companion object {
        @OptIn(InternalCoroutinesApi::class)
        fun observe(job: Job): Pair<ContactLinkCancellation, DisposableHandle> {
            val cancellation = ContactLinkCancellation()
            val handle = job.invokeOnCompletion(onCancelling = true, invokeImmediately = true) { cause ->
                if (cause != null) {
                    cancellation.cancelled.set(true)
                    // An already-claimed commit may finish before this observer exits.
                    synchronized(cancellation.commitGate) { }
                }
            }
            return cancellation to handle
        }
    }
}

interface ContactLinkPublication {
    fun release()
}

/**
 * Single-owner synchronous capture or completion, never shared across suspension/threads.
 * Allocate before entering guards and use `use` outside them: close is once-only, and a release
 * failure is suppressed onto an original failure/cancellation rather than replacing it.
 */
class ContactLinkPublications : AutoCloseable {
    private var publication: ContactLinkPublication? = null
    private var released = false

    fun defer(publication: ContactLinkPublication) {
        check(!released && this.publication == null) { "Contact-link publication already assigned or released" }
        this.publication = publication
    }

    fun release() {
        if (released) return
        released = true
        publication?.release()
    }

    override fun close() = release()
}

/** Shell navigation and lookups the link router uses (iOS `appState.navigation` + `offlineDataStore`). */
interface ChatLinkEnvironment {
    var selectedTab: AppTab
    val currentRadioId: RadioId?
    val contactLinkGeneration: Long get() = 0
    fun captureContactLinkSession(generation: Long): () -> Boolean = { true }
    val connectedDevicePublicKey: com.meshcoreone.android.core.protocol.bytes.Bytes?
    fun captureContactLinkContext(
        publicKey: com.meshcoreone.android.core.protocol.bytes.Bytes, publications: ContactLinkPublications,
    ): ContactLinkContext
    fun resolveContactLinkContext(
        context: ContactLinkContext, publicKey: com.meshcoreone.android.core.protocol.bytes.Bytes,
    ): ContactLinkTargetResolution
    fun completeResolvedContactLookup(
        context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
        existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
    ): ContactLinkTargetCompletion
    fun completeContactLookup(
        context: ContactLinkContext, link: ContactLinkResult, existing: ContactDTO?, publications: ContactLinkPublications,
    ): ContactLinkCompletion
    val parser: MeshCoreLinkParsing
    fun navigateToMap(latitude: Double, longitude: Double)
    fun navigateToContactDetail(contact: ContactDTO)
    fun navigateToChannel(channel: ChannelDTO)
    fun stageContactLink(link: ContactLinkResult)
    fun completeContactLookup(
        link: ContactLinkResult, existing: ContactDTO?, radioId: RadioId?, generation: Long,
        isCurrentSession: () -> Boolean = { true },
    ): Boolean {
        if (!isCurrentSession() || currentRadioId != radioId || contactLinkGeneration != generation) return false
        if (existing != null) navigateToContactDetail(existing) else stageContactLink(link)
        return true
    }
    fun stageChannelLink(link: ChannelLinkResult)
    fun stageHashtag(fullName: String)
    suspend fun fetchContact(radioId: RadioId, publicKey: com.meshcoreone.android.core.protocol.bytes.Bytes): ContactDTO?
    suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO>
}
