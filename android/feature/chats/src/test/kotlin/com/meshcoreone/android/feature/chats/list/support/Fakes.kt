// AndroidOnly: WP-306 Recording fakes for the feature-owned dependency interfaces.
package com.meshcoreone.android.feature.chats.list.support

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.FailedSendConversationKeys
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.ChatChannelKey
import com.meshcoreone.android.feature.chats.list.ChatConversationServices
import com.meshcoreone.android.feature.chats.list.ChatLinkEnvironment
import com.meshcoreone.android.feature.chats.list.ChatListDataSource
import com.meshcoreone.android.feature.chats.list.ChatListEvent
import com.meshcoreone.android.feature.chats.list.ChatListFeatureDependencies
import com.meshcoreone.android.feature.chats.list.ChatListNavigation
import com.meshcoreone.android.feature.chats.list.ChatRoute
import com.meshcoreone.android.feature.chats.list.ChatReactionClassifier
import com.meshcoreone.android.feature.chats.list.ChannelLinkResult
import com.meshcoreone.android.feature.chats.list.ContactLinkResult
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkCancellation
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkContext
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import com.meshcoreone.android.feature.chats.list.ContactLinkResolvedTarget
import com.meshcoreone.android.feature.chats.list.ContactLinkTargetCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkTargetResolution
import com.meshcoreone.android.feature.chats.list.MeshCoreLinkParser
import com.meshcoreone.android.feature.chats.list.MeshCoreLinkParsing
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

internal class FakeData : ChatListDataSource {
    var contacts: List<ContactDTO> = emptyList()
    var channels: List<ChannelDTO> = emptyList()
    var rooms: List<RemoteNodeSessionDTO> = emptyList()
    var failedSend = FailedSendConversationKeys.EMPTY
    var contactMessages: Map<UUID, List<MessageDTO>> = emptyMap()
    var channelMessages: Map<UUID, List<MessageDTO>> = emptyMap()
    var failFetchConversations: Exception? = null
    var failDeleteDirect: Exception? = null
    var failFavoriteWrites: Exception? = null
    var conversationFetchGate: (suspend () -> Unit)? = null
    var roomFetchGate: (suspend () -> Unit)? = null
    var channelPreviewGate: (suspend () -> Unit)? = null
    val calls = mutableListOf<String>()

    override suspend fun fetchConversations(radioId: RadioId): List<ContactDTO> {
        calls += "fetchConversations"
        failFetchConversations?.let { throw it }
        val fetched = contacts
        conversationFetchGate?.invoke()
        return fetched
    }
    override suspend fun fetchChannels(radioId: RadioId) = channels
    override suspend fun fetchRemoteNodeSessions(radioId: RadioId): List<RemoteNodeSessionDTO> {
        val fetched = rooms
        roomFetchGate?.invoke()
        return fetched
    }
    override suspend fun fetchContacts(radioId: RadioId) = contacts
    override suspend fun fetchLastMessages(contactIds: List<UUID>, limit: Int) = contactMessages
    override suspend fun fetchLastChannelMessages(channels: List<ChatChannelKey>, limit: Int): Map<UUID, List<MessageDTO>> {
        val fetched = channelMessages
        channelPreviewGate?.invoke()
        return fetched
    }
    var failedSendGate: (suspend () -> Unit)? = null
    override suspend fun fetchFailedSendConversationKeys(radioId: RadioId, channels: List<ChatChannelKey>): FailedSendConversationKeys {
        val fetched = failedSend
        failedSendGate?.invoke()
        return fetched
    }
    override suspend fun setContactMuted(contactId: UUID, isMuted: Boolean) { calls += "muted:$contactId:$isMuted" }
    override suspend fun setChannelNotificationLevel(channelId: UUID, level: NotificationLevel) { calls += "level:$channelId:$level" }
    override suspend fun setSessionNotificationLevel(sessionId: UUID, level: NotificationLevel) { calls += "slevel:$sessionId:$level" }
    override suspend fun setChannelFavorite(channelId: UUID, isFavorite: Boolean) {
        failFavoriteWrites?.let { throw it }
        calls += "chfav:$channelId:$isFavorite"
    }
    override suspend fun setSessionFavorite(sessionId: UUID, isFavorite: Boolean) {
        failFavoriteWrites?.let { throw it }
        calls += "sfav:$sessionId:$isFavorite"
    }
    override suspend fun deleteDirectConversation(contact: ContactDTO) {
        failDeleteDirect?.let { throw it }
        calls += "deleteDirect:${contact.id}"
        // The store clears lastMessageDate, so the conversation drops out of the next fetch.
        contacts = contacts.filter { it.id != contact.id }
    }
}

internal class FakeServices : ChatConversationServices {
    val calls = mutableListOf<String>()
    var failClearChannel: Exception? = null
    var failLeaveRoom: Exception? = null
    var clearChannelGate: (suspend () -> Unit)? = null
    override suspend fun setContactFavorite(contactId: UUID, isFavorite: Boolean) { calls += "fav:$contactId:$isFavorite" }
    override suspend fun clearChannel(radioId: RadioId, index: UByte) {
        clearChannelGate?.invoke()
        failClearChannel?.let { throw it }
        calls += "clear:$index"
    }
    override suspend fun removeDeliveredNotifications(radioId: RadioId, channelIndex: UByte) { calls += "removeNotifications:$channelIndex" }
    override suspend fun leaveRoom(session: RemoteNodeSessionDTO) {
        failLeaveRoom?.let { throw it }
        calls += "leave:${session.id}"
    }
    override suspend fun updateBadgeCount() { calls += "badge" }
}

internal class FakeNavigation : ChatListNavigation {
    override val pendingChatContact = MutableStateFlow<ContactDTO?>(null)
    override val pendingChannel = MutableStateFlow<ChannelDTO?>(null)
    override val pendingRoomSession = MutableStateFlow<RemoteNodeSessionDTO?>(null)
    override val pendingRoomAuthentication = MutableStateFlow<RemoteNodeSessionDTO?>(null)
    val acknowledgedRoutes = mutableListOf<ChatRoute>()
    override fun acknowledgePendingRoute(route: ChatRoute): Boolean {
        val matched = when (route) {
            is ChatRoute.Direct -> pendingChatContact.value == route.contact
            is ChatRoute.Channel -> pendingChannel.value == route.channel
            is ChatRoute.Room -> pendingRoomSession.value == route.session
        }
        if (!matched) return false
        when (route) {
            is ChatRoute.Direct -> pendingChatContact.value = null
            is ChatRoute.Channel -> pendingChannel.value = null
            is ChatRoute.Room -> pendingRoomSession.value = null
        }
        acknowledgedRoutes += route
        return true
    }
    override fun clearPendingRoomAuthentication() { pendingRoomAuthentication.value = null }
}

/** Mirrors the shell: navigating to the map selects the Map tab. */
internal class FakeLinks(
    override val parser: MeshCoreLinkParsing = MeshCoreLinkParser,
    override var currentRadioId: RadioId? = Fixtures.radio,
    override var connectedDevicePublicKey: Bytes? = null,
) : ChatLinkEnvironment {
    private data class CapturedContact(
        val owner: FakeLinks,
        override val radioId: RadioId?,
        override val selfPublicKey: Bytes?,
        override val disposition: ContactLinkCaptureDisposition,
        val generation: Long,
        val revision: Long,
        val publicKey: Bytes,
    ) : ContactLinkContext

    private class ResolvedContact(
        val owner: FakeLinks,
        val capture: CapturedContact,
        override val radioId: RadioId?,
        override val selfPublicKey: Bytes?,
    ) : ContactLinkResolvedTarget

    override var contactLinkGeneration = 0L
    private var contactRevision = 0L
    var afterContactCapture: () -> Unit = {}
    var afterContactResolve: (ContactLinkResolvedTarget) -> Unit = {}
    var contactFetch: (suspend (RadioId, Bytes) -> ContactDTO?)? = null
    val contactFetchRadios = mutableListOf<RadioId>()
    val contactCompletions = mutableListOf<ContactLinkCompletion>()
    override var selectedTab: AppTab = AppTab.CHATS
    var mapFocus: Pair<Double, Double>? = null
    var contactDetail: ContactDTO? = null
    var navigatedChannel: ChannelDTO? = null
    var pendingContact: ContactLinkResult? = null
    var pendingChannel: ChannelLinkResult? = null
    var pendingHashtag: String? = null
    var storedContacts: List<ContactDTO> = emptyList()
    var storedChannels: List<ChannelDTO> = emptyList()
    var failChannelFetch: Exception? = null

    override fun captureContactLinkContext(publicKey: Bytes, publications: ContactLinkPublications): ContactLinkContext {
        val disposition = if (publicKey == connectedDevicePublicKey)
            ContactLinkCaptureDisposition.IgnoredSelfContact else ContactLinkCaptureDisposition.Lookup
        contactRevision++
        return CapturedContact(
            this, currentRadioId, connectedDevicePublicKey, disposition, contactLinkGeneration, contactRevision, publicKey,
        ).also { afterContactCapture() }
    }

    private fun resolveCurrent(context: ContactLinkContext, publicKey: Bytes): ContactLinkTargetResolution {
        val captured = context as? CapturedContact ?: return ContactLinkTargetResolution.Stale
        val same = captured.generation == contactLinkGeneration && captured.radioId == currentRadioId
        val initialBinding = captured.radioId == null && currentRadioId != null &&
            captured.generation != Long.MAX_VALUE && contactLinkGeneration == captured.generation + 1
        if (captured.owner !== this || captured.publicKey != publicKey || captured.revision != contactRevision ||
            !same && !initialBinding
        ) return ContactLinkTargetResolution.Stale
        val target = ResolvedContact(
            this, captured, if (initialBinding) currentRadioId else captured.radioId,
            if (initialBinding) connectedDevicePublicKey ?: captured.selfPublicKey else captured.selfPublicKey,
        )
        return ContactLinkTargetResolution.Current(target)
    }

    override fun resolveContactLinkContext(context: ContactLinkContext, publicKey: Bytes): ContactLinkTargetResolution =
        resolveCurrent(context, publicKey).also {
            if (it is ContactLinkTargetResolution.Current) afterContactResolve(it.target)
        }

    override fun completeResolvedContactLookup(
        context: ContactLinkContext, target: ContactLinkResolvedTarget, link: ContactLinkResult,
        existing: ContactDTO?, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
    ): ContactLinkTargetCompletion {
        cancellation.ensureActive()
        val captured = context as? CapturedContact
        val resolved = target as? ResolvedContact
        val current = captured?.let { resolveCurrent(it, it.publicKey) } as? ContactLinkTargetResolution.Current
        if (captured == null || resolved == null || resolved.owner !== this || resolved.capture !== captured ||
            current == null
        ) {
            contactCompletions += ContactLinkCompletion.StaleContext
            return ContactLinkTargetCompletion.Completed(ContactLinkCompletion.StaleContext)
        }
        if (resolved.radioId != current.target.radioId || resolved.selfPublicKey != current.target.selfPublicKey) {
            return ContactLinkTargetCompletion.TargetChanged
        }
        return cancellation.commitIfActive {
            ContactLinkTargetCompletion.Completed(completeCurrent(captured, resolved, link, existing))
        }
    }

    override fun completeContactLookup(
        context: ContactLinkContext, link: ContactLinkResult, existing: ContactDTO?, publications: ContactLinkPublications,
    ): ContactLinkCompletion {
        val captured = context as? CapturedContact ?: return ContactLinkCompletion.StaleContext.also {
            contactCompletions += it
        }
        val resolved = (resolveCurrent(captured, captured.publicKey) as? ContactLinkTargetResolution.Current)
            ?.target as? ResolvedContact ?: return ContactLinkCompletion.StaleContext.also {
            contactCompletions += it
        }
        return completeCurrent(captured, resolved, link, existing)
    }

    private fun completeCurrent(
        captured: CapturedContact, target: ResolvedContact, link: ContactLinkResult, existing: ContactDTO?,
    ): ContactLinkCompletion {
        val result = when {
            link.publicKey != captured.publicKey ||
                existing != null && (existing.radioId != target.radioId || existing.publicKey != captured.publicKey) ->
                ContactLinkCompletion.WrongTarget
            captured.disposition == ContactLinkCaptureDisposition.IgnoredSelfContact ||
                captured.publicKey == target.selfPublicKey -> ContactLinkCompletion.IgnoredSelfContact
            existing == null -> {
                stageContactLink(link)
                ContactLinkCompletion.ConfirmationStaged
            }
            else -> {
                navigateToContactDetail(existing)
                ContactLinkCompletion.Navigated
            }
        }
        if (result != ContactLinkCompletion.StaleContext) contactRevision++
        contactCompletions += result
        return result
    }

    override fun navigateToMap(latitude: Double, longitude: Double) {
        mapFocus = latitude to longitude
        selectedTab = AppTab.MAP
    }
    override fun navigateToContactDetail(contact: ContactDTO) { contactDetail = contact }
    override fun navigateToChannel(channel: ChannelDTO) { navigatedChannel = channel }
    override fun stageContactLink(link: ContactLinkResult) { pendingContact = link }
    override fun stageChannelLink(link: ChannelLinkResult) { pendingChannel = link }
    override fun stageHashtag(fullName: String) { pendingHashtag = fullName }
    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
        contactFetchRadios += radioId
        val fetch = contactFetch
        return if (fetch != null) fetch(radioId, publicKey)
        else storedContacts.firstOrNull { it.radioId == radioId && it.publicKey == publicKey }
    }
    override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> {
        failChannelFetch?.let { throw it }
        return storedChannels
    }
}

internal class FakeDependencies(
    override val data: FakeData = FakeData(),
    val services: FakeServices? = FakeServices(),
    override val links: FakeLinks = FakeLinks(),
    override val navigation: FakeNavigation = FakeNavigation(),
) : ChatListFeatureDependencies {
    override val reactions = object : ChatReactionClassifier {
        override fun isDirectReaction(text: String) = text.startsWith("react:")
        override fun isChannelReaction(text: String) = text.startsWith("react:")
    }
    override val connectionState: MutableStateFlow<DeviceConnectionState> = MutableStateFlow(DeviceConnectionState.READY)
    override val currentRadioId: MutableStateFlow<RadioId?> = MutableStateFlow(Fixtures.radio)
    override val reloadRequests: Flow<Unit> = emptyFlow()
    override val messageEvents: Flow<ChatListEvent> = emptyFlow()
    override val clock: Clock = Clock.fixed(Fixtures.epoch, ZoneOffset.UTC)
    val reports = mutableListOf<String>()
    override val diagnostics = com.meshcoreone.android.feature.chats.list.ChatListDiagnostics { message, _ -> reports += message }
    override fun conversationServices(): ChatConversationServices? = services
}
