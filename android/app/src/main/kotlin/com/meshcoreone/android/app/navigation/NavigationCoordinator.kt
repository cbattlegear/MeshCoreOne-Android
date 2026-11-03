// PortedFrom: MC1/State/NavigationCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/MainSidebarView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.snapshotMap
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.ChatRoute
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkPublication
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

// Native adaptation: Inject process repositories; this sink never constructs or owns a radio session.
interface NavigationLookup {
    suspend fun contact(key: EntityKey): ContactDTO?
    suspend fun channel(radioId: RadioId, index: UByte): ChannelDTO?
    suspend fun room(key: EntityKey): RemoteNodeSessionDTO?
}

internal data class NavigationCleanupOwner(
    val sessionToken: SessionToken?,
    val radioId: RadioId?,
    val generation: Long,
)

class NavigationCoordinator(initial: NavigationState = NavigationState()) {
    private val mutableState = NavigationStateStore(initial)
    val state: StateFlow<NavigationState> = mutableState.state
    internal val currentState: NavigationState get() = mutableState.value
    private val notificationMutex = Mutex()
    private val drainMutex = Mutex()
    private val localCommitLock = Any()
    private var commitBoundary: (() -> Unit) -> Unit = { action -> synchronized(localCommitLock, action) }

    internal fun installCommitBoundary(boundary: (() -> Unit) -> Unit) {
        commitBoundary = boundary
    }

    internal fun retireBinding(action: () -> Unit) = commitBoundary(action)

    private fun commitIfCurrent(
        expected: NavigationStateStore.Snapshot, next: NavigationState, isCurrent: () -> Boolean,
    ): Boolean? {
        var committed: Boolean? = null
        val publication = NavigationStateStore.Publication()
        try {
            commitBoundary {
                if (isCurrent()) committed = mutableState.compareAndSet(expected, next, publication)
            }
        } finally {
            if (committed == true) mutableState.release(publication)
        }
        return committed
    }

    // AndroidOnly: WP-302 Called only by a newly created host, not on resize or connection changes.
    fun restoreHostState(restored: NavigationState) {
        val current = mutableState.snapshot()
        check(current.revision == 0L && current.value == NavigationState() &&
            mutableState.compareAndSet(current, restored, forceRevision = true)
        ) {
            "A live navigation host cannot be overwritten by restoration"
        }
    }

    // AndroidOnly: WP-407 Attach restored public screen state without overwriting live process radio/readiness.
    internal fun attachHostState(restored: NavigationState) = mutableState.update {
        if (restored == NavigationState() || it.stacks.values.any { stack -> stack.size > 1 } ||
            it.selectedTab != AppTab.CHATS || it.pendingMapFocus != null ||
            it.pendingContactLink != null || it.pendingChannelLink != null || it.pendingHashtag != null ||
            it.pendingNotifications.isNotEmpty() || it.pendingChatContact != null || it.pendingChannel != null ||
            it.pendingRoomSession != null || it.pendingRoomAuthentication != null || it.pendingContactDetail != null
        ) return@update it
        it.copy(
            selectedTab = restored.selectedTab,
            stacks = restored.stacks,
            nextEntryId = maxOf(it.nextEntryId, restored.nextEntryId),
            selectedSetting = restored.selectedSetting,
            selectedTool = restored.selectedTool,
            widthDp = restored.widthDp,
            tabBarVisible = restored.tabBarVisible,
            failure = restored.failure ?: it.failure,
        )
    }

    fun selectTab(tab: AppTab) = mutableState.update { it.select(tab) }
    fun measureWindow(widthDp: Float) {
        require(widthDp.isFinite() && widthDp >= 0f) { "Window width must be finite and nonnegative" }
        mutableState.update { it.copy(widthDp = widthDp) }
    }

    fun navigate(route: FeatureRoute) {
        val tab = route.feature.tab
        if (tab != null) selectTab(tab)
        else mutableState.update {
            it.push(it.selectedTab, NavigationDestination.Auxiliary(route.feature))
        }
    }

    fun navigateToChat(contact: ContactDTO, scrollToMessageID: UUID? = null) = mutableState.update {
        val selection = ChatSelection.Direct(contact)
        it.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
            pendingChatContact = contact, chatsSelectedRoute = selection,
            pendingScrollToMessageID = scrollToMessageID, tabBarVisible = false,
        )
    }

    fun navigateToChannel(channel: ChannelDTO, scrollToMessageID: UUID? = null) = mutableState.update {
        val selection = ChatSelection.Channel(channel)
        it.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
            pendingChannel = channel, chatsSelectedRoute = selection,
            pendingScrollToMessageID = scrollToMessageID, tabBarVisible = false,
        )
    }

    fun navigateToRoom(session: RemoteNodeSessionDTO) = mutableState.update {
        val selection = ChatSelection.Room(session)
        it.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
            pendingRoomSession = session, chatsSelectedRoute = selection, tabBarVisible = false,
        )
    }

    internal fun acknowledgePendingChatRoute(route: ChatRoute): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val selection = when (route) {
                is ChatRoute.Direct -> ChatSelection.Direct(route.contact)
                is ChatRoute.Channel -> ChatSelection.Channel(route.channel)
                is ChatRoute.Room -> ChatSelection.Room(route.session)
            }
            val ownsHandoff = current.selectedTab == AppTab.CHATS &&
                current.chatsSelectedRoute == selection &&
                current.stacks.getValue(AppTab.CHATS).last().destination == NavigationDestination.Chat(selection) &&
                when (route) {
                    is ChatRoute.Direct -> current.pendingChatContact == route.contact
                    is ChatRoute.Channel -> current.pendingChannel == route.channel
                    is ChatRoute.Room -> current.pendingRoomSession == route.session
                }
            if (!ownsHandoff) return false
            val next = when (route) {
                is ChatRoute.Direct -> current.copy(pendingChatContact = null)
                is ChatRoute.Channel -> current.copy(pendingChannel = null)
                is ChatRoute.Room -> current.copy(pendingRoomSession = null)
            }
            if (mutableState.compareAndSet(snapshot, next)) return true
        }
    }

    fun requestRoomAuthentication(session: RemoteNodeSessionDTO) = mutableState.update { current ->
        when {
            !session.isRoom -> current.copy(failure = NavigationFailure.WrongTarget(NavigationTarget.ROOM))
            current.radioId != null && current.radioId != session.radioId ->
                current.copy(failure = NavigationFailure.WrongRadio(current.radioId, session.radioId))
            else -> current.select(AppTab.CHATS).copy(
                pendingChatContact = null, pendingChannel = null, pendingRoomSession = null, failure = null,
            ).stageRoomAuthentication(session)
        }
    }

    private fun NavigationState.stageRoomAuthentication(session: RemoteNodeSessionDTO) = copy(
        pendingRoomAuthentication = session,
        roomAuthenticationDeliveryId = nextEntryId,
        roomAuthenticationGeneration = generation,
        roomAuthenticationSessionToken = boundSessionToken,
        nextEntryId = Math.incrementExact(nextEntryId),
    )

    private fun NavigationState.withoutRoomAuthentication() = copy(
        pendingRoomAuthentication = null,
        roomAuthenticationDeliveryId = null,
        roomAuthenticationGeneration = null,
        roomAuthenticationSessionToken = null,
    )

    internal fun dismissRoomAuthentication(deliveryId: Long): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            if (current.roomAuthenticationDeliveryId != deliveryId) return false
            if (mutableState.compareAndSet(snapshot, current.withoutRoomAuthentication())) return true
        }
    }

    internal fun completeRoomAuthentication(
        deliveryId: Long,
        generation: Long,
        session: RemoteNodeSessionDTO,
        isCurrentSession: () -> Boolean,
    ): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val pending = current.pendingRoomAuthentication
            if (!isCurrentSession() || current.roomAuthenticationDeliveryId != deliveryId ||
                current.roomAuthenticationGeneration != generation || current.generation != generation ||
                pending == null || !session.isConnected || !session.isRoom ||
                session.radioId != pending.radioId || session.publicKey != pending.publicKey ||
                current.radioId != null && current.radioId != session.radioId
            ) return false
            val selection = ChatSelection.Room(session)
            val next = current.withoutRoomAuthentication()
                .push(AppTab.CHATS, NavigationDestination.Chat(selection))
                .copy(pendingRoomSession = session, chatsSelectedRoute = selection, tabBarVisible = false)
            when (commitIfCurrent(snapshot, next, isCurrentSession)) {
                true -> return true
                null -> return false
                false -> Unit
            }
        }
    }

    fun navigateToDiscovery() = mutableState.update {
        it.push(AppTab.NODES, NavigationDestination.Discovery).copy(
            pendingDiscoveryNavigation = true, nodesShowingDiscovery = true, tabBarVisible = true,
        )
    }

    fun navigateToContacts() = selectTab(AppTab.NODES)
    fun navigateToContactDetail(contact: ContactDTO) = mutableState.update {
        it.push(AppTab.NODES, NavigationDestination.ContactDetail(contact)).copy(
            pendingContactDetail = contact, selectedContact = contact, nodesShowingDiscovery = false,
        )
    }

    fun navigateToRemoteNode(contact: ContactDTO, action: com.meshcoreone.android.feature.nodes.RemoteNodeAction) =
        mutableState.update { it.push(AppTab.NODES, NavigationDestination.RemoteNode(contact, action)) }

    fun navigateToMap(latitude: Double, longitude: Double) = mutableState.update {
        it.select(AppTab.MAP).copy(pendingMapFocus = MapFocusRequest(latitude, longitude))
    }

    fun navigateToSetting(detail: SettingsDetail) = mutableState.update {
        it.push(AppTab.SETTINGS, NavigationDestination.Setting(detail)).copy(selectedSetting = detail)
    }

    fun navigateToTool(tool: ToolSelection) = mutableState.update {
        it.push(AppTab.TOOLS, NavigationDestination.Tool(tool)).copy(selectedTool = tool)
    }

    fun stageContactLink(
        link: ContactLinkRequest?, targetRadioId: RadioId? = mutableState.value.radioId, selectChats: Boolean = false,
    ) = mutableState.update {
        if (link == null) it.copy(pendingContactLink = null) else {
            (if (selectChats) it.select(AppTab.CHATS) else it)
                .copy(contactLookupRevision = Math.incrementExact(it.contactLookupRevision))
                .stageContactLink(link, targetRadioId)
        }
    }

    private fun NavigationState.stageContactLink(link: ContactLinkRequest, radioId: RadioId?) = copy(
        pendingContactLink = link.copy(targetRadioId = radioId, generation = generation, deliveryId = nextEntryId),
        nextEntryId = Math.incrementExact(nextEntryId),
    )

    internal fun beginContactLinkLookup(expectedGeneration: Long): Long? {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val initialAssignment = current.initialContactLinkGeneration == expectedGeneration &&
                current.generation == Math.incrementExact(expectedGeneration)
            if (current.generation != expectedGeneration && !initialAssignment) return null
            val revision = Math.incrementExact(current.contactLookupRevision)
            if (mutableState.compareAndSet(snapshot, current.copy(contactLookupRevision = revision))) return revision
        }
    }

    internal fun isContactLinkLookupCurrent(revision: Long?): Boolean =
        revision != null && mutableState.value.contactLookupRevision == revision

    internal fun captureContactLinkLookup(
        publicKey: Bytes, radioId: RadioId?, selfPublicKey: Bytes?, graphToken: SessionToken?,
        runtimeToken: SessionToken?, graphCurrent: Boolean, publications: ContactLinkPublications,
    ): ContactLinkLookup {
        var publication: NavigationStateStore.Publication? = null
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val boundGraph = graphToken != null && graphCurrent && runtimeToken == graphToken &&
                current.boundSessionToken == graphToken && current.radioId == graphToken.radioId
            val initialGraph = graphToken != null && graphCurrent && (runtimeToken == null || runtimeToken == graphToken) &&
                current.boundSessionToken == null && current.radioId == null
            val offline = graphToken == null && graphCurrent && runtimeToken == null && current.boundSessionToken == null &&
                (current.radioId == null || current.radioId == radioId)
            val disposition = when {
                !boundGraph && !initialGraph && !offline -> ContactLinkCaptureDisposition.StaleContext
                publicKey == selfPublicKey -> ContactLinkCaptureDisposition.IgnoredSelfContact
                else -> ContactLinkCaptureDisposition.Lookup
            }
            val revision = if (disposition == ContactLinkCaptureDisposition.Lookup)
                Math.incrementExact(current.contactLookupRevision) else null
            val lookup = ContactLinkLookup(
                this, current.generation, current.boundSessionToken, current.radioId,
                radioId, publicKey, selfPublicKey, revision, disposition,
            )
            if (revision == null) return lookup
            val deferred = publication ?: contactLinkPublication(publications).also { publication = it }
            if (mutableState.compareAndSet(snapshot, current.copy(contactLookupRevision = revision), deferred)) return lookup
        }
    }

    private fun contactLinkPublication(publications: ContactLinkPublications): NavigationStateStore.Publication {
        val publication = NavigationStateStore.Publication()
        publications.defer(object : ContactLinkPublication {
            override fun release() = mutableState.release(publication)
        })
        return publication
    }

    internal fun completeCapturedContactLookup(
        lookup: ContactLinkLookup, link: ContactLinkRequest, contact: ContactDTO?,
        currentToken: SessionToken?, allowInitialBinding: Boolean, identifiedSelfPublicKey: Bytes?,
        selectChats: Boolean, publications: ContactLinkPublications,
    ): ContactLinkCompletion {
        if (lookup.owner !== this || lookup.disposition == ContactLinkCaptureDisposition.StaleContext)
            return ContactLinkCompletion.StaleContext
        var publication: NavigationStateStore.Publication? = null
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val initial = allowInitialBinding && lookup.allowsInitialBinding && currentToken != null &&
                current.initialContactLinkGeneration == lookup.generation &&
                current.generation == Math.incrementExact(lookup.generation) &&
                current.boundSessionToken == currentToken
            val same = current.generation == lookup.generation && current.boundSessionToken == lookup.boundToken &&
                current.radioId == lookup.navigationRadioId
            if (!same && !initial) return ContactLinkCompletion.StaleContext
            val capturedSelf = lookup.disposition == ContactLinkCaptureDisposition.IgnoredSelfContact
            if (!capturedSelf && (lookup.revision == null || current.contactLookupRevision != lookup.revision))
                return ContactLinkCompletion.StaleContext
            val selfContact = capturedSelf ||
                lookup.allowsInitialBinding && lookup.publicKey == identifiedSelfPublicKey
            val radio = if (initial && lookup.radioId == null) current.radioId else lookup.radioId
            if (current.radioId != null && current.radioId != radio) return ContactLinkCompletion.StaleContext
            val wrongTarget = link.publicKey != lookup.publicKey ||
                contact != null && (contact.radioId != radio || contact.publicKey != lookup.publicKey)
            val outcome = when {
                wrongTarget -> ContactLinkCompletion.WrongTarget
                selfContact -> ContactLinkCompletion.IgnoredSelfContact
                contact == null -> ContactLinkCompletion.ConfirmationStaged
                else -> ContactLinkCompletion.Navigated
            }
            val destination = when {
                wrongTarget -> current.copy(failure = NavigationFailure.WrongTarget(NavigationTarget.CONTACT))
                selfContact -> if (selectChats) current.select(AppTab.CHATS) else current
                contact == null -> (if (selectChats) current.select(AppTab.CHATS) else current).stageContactLink(link, radio)
                else -> current.push(AppTab.NODES, NavigationDestination.ContactDetail(contact)).copy(
                    pendingContactLink = null, pendingContactDetail = contact, selectedContact = contact,
                    nodesShowingDiscovery = false,
                )
            }
            val next = if (outcome == ContactLinkCompletion.IgnoredSelfContact) destination
                else destination.copy(contactLookupRevision = Math.incrementExact(current.contactLookupRevision))
            val deferred = publication ?: contactLinkPublication(publications).also { publication = it }
            if (mutableState.compareAndSet(snapshot, next, deferred)) return outcome
        }
    }

    // Native adaptation: Commit lookup results against their initiating context, never a post-lookup radio.
    fun completeContactLookup(
        link: ContactLinkRequest, contact: ContactDTO?, targetRadioId: RadioId?,
        expectedGeneration: Long, currentRadioId: RadioId?,
        isCurrentSession: () -> Boolean = { true },
    ): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val initialAssignment = current.initialContactLinkGeneration == expectedGeneration &&
                current.generation == Math.incrementExact(expectedGeneration)
            val radio = if (initialAssignment && targetRadioId == null) current.radioId else targetRadioId
            if (!isCurrentSession() || current.generation != expectedGeneration && !initialAssignment ||
                currentRadioId != radio || current.radioId != null && current.radioId != radio
            ) return false
            val failure = when {
                contact != null && (contact.radioId != radio || contact.publicKey != link.publicKey) ->
                    NavigationFailure.WrongTarget(NavigationTarget.CONTACT)
                else -> null
            }
            val next = when {
                failure != null -> current.copy(failure = failure)
                contact == null -> current.stageContactLink(link, radio)
                else -> current.push(AppTab.NODES, NavigationDestination.ContactDetail(contact)).copy(
                    pendingContactLink = null, pendingContactDetail = contact, selectedContact = contact,
                    nodesShowingDiscovery = false,
                )
            }.copy(contactLookupRevision = Math.incrementExact(current.contactLookupRevision))
            when (commitIfCurrent(snapshot, next, isCurrentSession)) {
                true -> return failure == null
                null -> return false
                false -> Unit
            }
        }
    }

    // AndroidOnly: WP-302 A late dismiss/import must not acknowledge a replacement request or radio.
    fun completeContactLink(
        link: ContactLinkRequest, contact: ContactDTO? = null, isCurrentSession: () -> Boolean = { true },
    ): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            if (!isCurrentSession() || link.deliveryId == null || current.pendingContactLink?.deliveryId != link.deliveryId ||
                current.generation != link.generation ||
                contact != null && (contact.publicKey != link.publicKey ||
                    link.targetRadioId != null && contact.radioId != link.targetRadioId)
            ) return false
            val next = if (contact == null) current.copy(pendingContactLink = null) else {
                current.push(AppTab.NODES, NavigationDestination.ContactDetail(contact)).copy(
                    pendingContactLink = null, pendingContactDetail = contact, selectedContact = contact,
                    nodesShowingDiscovery = false,
                )
            }
            when (commitIfCurrent(snapshot, next, isCurrentSession)) {
                true -> return true
                null -> return false
                false -> Unit
            }
        }
    }
    fun stageChannelLink(link: ChannelLinkRequest?, selectChats: Boolean = false) = mutableState.update {
        (if (selectChats && link != null) it.select(AppTab.CHATS) else it).copy(pendingChannelLink = link)
    }
    fun stageHashtag(link: HashtagJoinRequest?, selectChats: Boolean = false) = mutableState.update {
        (if (selectChats && link != null) it.select(AppTab.CHATS) else it).copy(pendingHashtag = link)
    }
    fun setPendingDeviceMenuTipDonation(pending: Boolean) =
        mutableState.update { it.copy(pendingDeviceMenuTipDonation = pending) }

    fun clearPendingNavigation() = mutableState.update { it.copy(pendingChatContact = null) }
    fun clearPendingRoomNavigation() = mutableState.update { it.copy(pendingRoomSession = null) }
    fun clearPendingRoomAuthentication() = mutableState.update { it.withoutRoomAuthentication() }
    fun clearPendingChannelNavigation() = mutableState.update { it.copy(pendingChannel = null) }
    fun clearPendingDiscoveryNavigation() = mutableState.update { it.copy(pendingDiscoveryNavigation = false) }
    fun clearPendingScrollToMessage() = mutableState.update { it.copy(pendingScrollToMessageID = null) }
    fun clearPendingContactDetailNavigation() = mutableState.update { it.copy(pendingContactDetail = null) }
    fun clearPendingMapFocus() = mutableState.update { it.copy(pendingMapFocus = null) }
    fun clearPendingContactLink() = stageContactLink(null)
    fun clearPendingChannelLink() = stageChannelLink(null)
    fun clearPendingHashtag() = stageHashtag(null)
    fun clearFailure(expected: NavigationFailure? = mutableState.value.failure) = mutableState.update {
        if (it.failure == expected) it.copy(failure = null) else it
    }

    // AndroidOnly: WP-302 WP303 signals real graph readiness; composition never claims readiness or performs lookup.
    fun setNavigationReady(ready: Boolean) = mutableState.update { it.copy(navigationReady = ready) }

    fun enqueueNotification(payload: NotificationPayload, manualAddContacts: Boolean = false): Long {
        val request = NotificationNavigationRequest.fromPayload(payload, manualAddContacts)
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val generation = if (!current.navigationReady && current.radioId == null) null else current.generation
            val pending = PendingNotificationRoute(current.nextEntryId, generation, request)
            if (mutableState.compareAndSet(snapshot, current.copy(
                nextEntryId = Math.incrementExact(current.nextEntryId),
                pendingNotifications = (current.pendingNotifications + pending).snapshot(),
            ))) return pending.id
        }
    }

    suspend fun consumePendingNotification(lookup: NavigationLookup): NavigationOutcome = drainMutex.withLock {
        val current = mutableState.value
        if (!current.navigationReady) return@withLock NavigationOutcome.NotReady
        val pending = current.pendingNotifications.firstOrNull() ?: return@withLock NavigationOutcome.NoPendingRoute
        val outcome = handleNotificationRequest(pending.request, lookup, pending.generation ?: current.generation)
        // Cancellation before a committed transition propagates and leaves the request available for a real retry.
        mutableState.update {
            it.copy(pendingNotifications = it.pendingNotifications.filterNot { queued -> queued.id == pending.id }.snapshot())
        }
        outcome
    }

    fun clearPerDeviceSelection() = mutableState.update(::clearDeviceSelection)

    fun clearPerRadioSelection() = mutableState.update {
        clearRadioSelection(it).copy(generation = Math.incrementExact(it.generation))
    }

    internal fun captureCleanupOwner(): NavigationCleanupOwner = mutableState.value.let {
        NavigationCleanupOwner(it.boundSessionToken, it.radioId, it.generation)
    }

    internal fun clearPendingLinksIfCurrent(owner: NavigationCleanupOwner): Boolean {
        while (true) {
            val publication = NavigationStateStore.Publication()
            var result: Boolean? = null
            try {
                commitBoundary {
                    val snapshot = mutableState.snapshot()
                    val current = snapshot.value
                    if (current.boundSessionToken != owner.sessionToken || current.radioId != owner.radioId ||
                        current.generation != owner.generation
                    ) {
                        result = false
                    } else {
                        val next = clearRadioSelection(current).copy(
                            generation = Math.incrementExact(current.generation),
                            pendingContactLink = null, pendingChannelLink = null, pendingHashtag = null,
                        )
                        if (mutableState.compareAndSet(snapshot, next, publication)) result = true
                    }
                }
            } finally {
                if (result == true) mutableState.release(publication)
            }
            if (result != null) return result == true
        }
    }

    fun clearPendingLinks() = mutableState.update {
        clearRadioSelection(it).copy(
            generation = Math.incrementExact(it.generation),
            pendingContactLink = null, pendingChannelLink = null, pendingHashtag = null,
        )
    }

    // AndroidOnly: WP-302 The process owner supplies generation changes; manual disconnect retains cached chats.
    fun replaceRadio(radioId: RadioId?) = mutableState.update { it.replaceRadioState(radioId) }

    // Native adaptation: A new session always retires old callbacks, even for the same radio.
    fun bindNotificationSession(
        radioId: RadioId,
        localPublicKey: Bytes?,
        token: SessionToken? = null,
        isCurrentSession: () -> Boolean = { true },
    ): Boolean {
        require(token == null || token.radioId == radioId) { "Session token must match the navigation radio" }
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val eligible = current.pendingContactLink?.takeIf {
                (current.radioId == null || current.radioId == radioId) &&
                    it.generation == current.generation && (it.targetRadioId == null || it.targetRadioId == radioId) &&
                    it.publicKey != localPublicKey
            }
            val eligibleRoom = current.pendingRoomAuthentication?.takeIf {
                (current.radioId == null || current.radioId == radioId) && it.radioId == radioId && it.isRoom
            }
            val next = when {
                current.radioId == radioId -> current.withoutRoomAuthentication().copy(
                    generation = Math.incrementExact(current.generation), navigationReady = false, pendingContactLink = null,
                    initialContactLinkGeneration = null, boundSessionToken = token,
                )
                current.radioId == null -> current.bindInitialRadioState(radioId).copy(boundSessionToken = token)
                else -> current.replaceRadioState(radioId).copy(navigationReady = false, boundSessionToken = token)
            }
            val withContact = if (eligible == null) next else next.stageContactLink(eligible, radioId)
            val withRoom = if (eligibleRoom == null) withContact else withContact.stageRoomAuthentication(eligibleRoom)
            when (commitIfCurrent(snapshot, withRoom, isCurrentSession)) {
                null -> return false
                true -> return true
                false -> Unit
            }
        }
    }

    private fun NavigationState.bindInitialRadioState(radioId: RadioId): NavigationState {
        val retained = removeDestinations { destination ->
            val ownerRadio = when (destination) {
                is NavigationDestination.Chat -> when (val selection = destination.selection) {
                    is ChatSelection.Direct -> selection.contact.radioId
                    is ChatSelection.Channel -> selection.channel.radioId
                    is ChatSelection.Room -> selection.session.radioId
                }
                is NavigationDestination.ContactDetail -> destination.contact.radioId
                is NavigationDestination.RemoteNode -> destination.contact.radioId
                else -> null
            }
            ownerRadio != null && ownerRadio != radioId
        }
        val chats = retained.stacks.getValue(AppTab.CHATS).last().destination as? NavigationDestination.Chat
        val nodes = retained.stacks.getValue(AppTab.NODES).last().destination
        return retained.copy(
            radioId = radioId, generation = Math.incrementExact(generation),
            initialContactLinkGeneration = generation, navigationReady = false, pendingContactLink = null,
            chatsSelectedRoute = chats?.selection,
            selectedContact = (nodes as? NavigationDestination.ContactDetail)?.contact,
            nodesShowingDiscovery = nodes == NavigationDestination.Discovery,
            pendingChatContact = pendingChatContact?.takeIf { it.radioId == radioId },
            pendingChannel = pendingChannel?.takeIf { it.radioId == radioId },
            pendingRoomSession = pendingRoomSession?.takeIf { it.radioId == radioId },
            pendingRoomAuthentication = null, roomAuthenticationDeliveryId = null, roomAuthenticationGeneration = null,
            roomAuthenticationSessionToken = null,
            pendingContactDetail = pendingContactDetail?.takeIf { it.radioId == radioId },
            pendingScrollToMessageID = pendingScrollToMessageID.takeIf { chats != null },
        )
    }

    private fun NavigationState.replaceRadioState(radioId: RadioId?) =
        clearRadioSelection(this).copy(
            radioId = radioId, generation = Math.incrementExact(generation), boundSessionToken = null,
            initialContactLinkGeneration = null,
            pendingContactLink = null, pendingChannelLink = null, pendingHashtag = null,
            pendingChatContact = null, pendingChannel = null, pendingRoomSession = null,
            pendingRoomAuthentication = null, roomAuthenticationDeliveryId = null, roomAuthenticationGeneration = null,
            roomAuthenticationSessionToken = null,
            pendingContactDetail = null,
            pendingDiscoveryNavigation = false, pendingScrollToMessageID = null, pendingMapFocus = null,
        )

    fun manuallyDisconnect() = mutableState.update {
        clearDeviceSelection(it).withoutRoomAuthentication().copy(
            generation = Math.incrementExact(it.generation), boundSessionToken = null,
        )
    }

    fun back(): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            if (!current.canGoBack) return false
            val next = if (current.activeStack.size == 1) current.select(AppTab.CHATS)
                else {
                    val stack = current.activeStack.dropLast(1).snapshot()
                    val destination = stack.last().destination
                    current.copy(
                        stacks = (current.stacks + (current.selectedTab to stack)).snapshotMap(),
                        tabBarVisible = stack.size == 1,
                        chatsSelectedRoute = if (current.selectedTab == AppTab.CHATS)
                            (destination as? NavigationDestination.Chat)?.selection else current.chatsSelectedRoute,
                        selectedContact = if (current.selectedTab == AppTab.NODES)
                            (destination as? NavigationDestination.ContactDetail)?.contact else current.selectedContact,
                        nodesShowingDiscovery = if (current.selectedTab == AppTab.NODES)
                            destination == NavigationDestination.Discovery else current.nodesShowingDiscovery,
                        selectedTool = if (current.selectedTab == AppTab.TOOLS)
                            (destination as? NavigationDestination.Tool)?.selection else current.selectedTool,
                        selectedSetting = if (current.selectedTab == AppTab.SETTINGS)
                            (destination as? NavigationDestination.Setting)?.selection else current.selectedSetting,
                        pendingChatContact = if (current.selectedTab == AppTab.CHATS) null else current.pendingChatContact,
                        pendingChannel = if (current.selectedTab == AppTab.CHATS) null else current.pendingChannel,
                        pendingRoomSession = if (current.selectedTab == AppTab.CHATS) null else current.pendingRoomSession,
                        pendingScrollToMessageID = if (current.selectedTab == AppTab.CHATS) null else current.pendingScrollToMessageID,
                        pendingContactDetail = if (current.selectedTab == AppTab.NODES) null else current.pendingContactDetail,
                        pendingDiscoveryNavigation = if (current.selectedTab == AppTab.NODES) false else current.pendingDiscoveryNavigation,
                    )
                }
            if (mutableState.compareAndSet(snapshot, next)) return true
        }
    }

    suspend fun handleNotification(
        payload: NotificationPayload,
        lookup: NavigationLookup,
        manualAddContacts: Boolean = false,
        expectedGeneration: Long = mutableState.value.generation,
    ): NavigationOutcome = handleNotificationRequest(
        NotificationNavigationRequest.fromPayload(payload, manualAddContacts), lookup, expectedGeneration,
    )

    suspend fun handleNotificationRequest(
        request: NotificationNavigationRequest,
        lookup: NavigationLookup,
        expectedGeneration: Long = mutableState.value.generation,
        isCurrentSession: () -> Boolean = { true },
    ): NavigationOutcome {
        return notificationMutex.withLock {
            val current = mutableState.value
            if (current.generation != expectedGeneration || !isCurrentSession()) {
                return@withLock NavigationOutcome.Failed(NavigationFailure.StaleGeneration)
            }
            if (current.radioId != null && request.radioId != null && current.radioId != request.radioId) {
                return@withLock commitNotification(
                    Resolved.Failure(NavigationFailure.WrongRadio(current.radioId, requireNotNull(request.radioId))),
                    expectedGeneration,
                    isCurrentSession,
                )
            }
            try {
                val resolved = resolve(request, lookup)
                currentCoroutineContext().ensureActive()
                commitNotification(resolved, expectedGeneration, isCurrentSession)
            } catch (error: PersistenceStoreException) {
                (error.cause as? CancellationException)?.let { throw it }
                currentCoroutineContext().ensureActive()
                val failure = NavigationFailure.Repository(error.error)
                commitNotification(
                    if (request is NotificationNavigationRequest.NewContact) Resolved.Fallback(failure)
                    else Resolved.Failure(failure),
                    expectedGeneration,
                    isCurrentSession,
                )
            }
        }
    }

    private fun commitNotification(
        resolved: Resolved, generation: Long, isCurrentSession: () -> Boolean = { true },
    ): NavigationOutcome {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            if (current.generation != generation || !isCurrentSession()) {
                return NavigationOutcome.Failed(NavigationFailure.StaleGeneration)
            }
            val delivery = if (resolved is Resolved.Failure) current else current.withoutRoomAuthentication().copy(
                pendingChatContact = null, pendingChannel = null, pendingRoomSession = null,
            )
            val next = when (resolved) {
                    is Resolved.Chat -> {
                        val selection = ChatSelection.Direct(resolved.contact)
                        delivery.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
                            pendingChatContact = resolved.contact, chatsSelectedRoute = selection,
                            pendingScrollToMessageID = resolved.messageId, tabBarVisible = false,
                        )
                    }
                    is Resolved.Channel -> {
                        val selection = ChatSelection.Channel(resolved.channel)
                        delivery.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
                            pendingChannel = resolved.channel, chatsSelectedRoute = selection,
                            pendingScrollToMessageID = resolved.messageId, tabBarVisible = false,
                            failure = resolved.recoveryFailure,
                        )
                    }
                    is Resolved.Room -> {
                        if (resolved.session.isConnected) {
                            val selection = ChatSelection.Room(resolved.session)
                            delivery.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
                                pendingRoomSession = resolved.session, chatsSelectedRoute = selection, tabBarVisible = false,
                            )
                        } else {
                            delivery.select(AppTab.CHATS).copy(failure = null).stageRoomAuthentication(resolved.session)
                        }
                    }
                    is Resolved.Contact -> delivery.push(
                        AppTab.NODES, NavigationDestination.ContactDetail(resolved.contact),
                    ).copy(pendingContactDetail = resolved.contact, selectedContact = resolved.contact, nodesShowingDiscovery = false)
                    Resolved.Discovery -> delivery.push(AppTab.NODES, NavigationDestination.Discovery).copy(
                        pendingDiscoveryNavigation = true, nodesShowingDiscovery = true, tabBarVisible = true,
                    )
                    is Resolved.Fallback -> delivery.select(AppTab.NODES).copy(failure = resolved.failure)
                    is Resolved.Failure -> current.copy(failure = resolved.failure)
            }
            when (commitIfCurrent(snapshot, next, isCurrentSession)) {
                null -> return NavigationOutcome.Failed(NavigationFailure.StaleGeneration)
                false -> continue
                true -> {
                    return when (resolved) {
                        is Resolved.Fallback -> NavigationOutcome.Fallback(resolved.failure)
                        is Resolved.Failure -> NavigationOutcome.Failed(resolved.failure)
                        is Resolved.Channel -> resolved.recoveryFailure?.let { NavigationOutcome.Fallback(it) }
                            ?: NavigationOutcome.Navigated
                        else -> NavigationOutcome.Navigated
                    }
                }
            }
        }
    }

    private suspend fun resolve(
        request: NotificationNavigationRequest, lookup: NavigationLookup,
    ): Resolved = when (request) {
        is NotificationNavigationRequest.Direct -> resolveContact(lookup, request.contact)
        is NotificationNavigationRequest.NewContact -> if (request.manualAddContacts) Resolved.Discovery
        else {
            val resolved = resolveContact(lookup, request.contact)
            if (resolved is Resolved.Chat) Resolved.Contact(resolved.contact)
            else if (resolved is Resolved.Failure && resolved.failure is NavigationFailure.TargetNotFound)
                Resolved.Fallback(resolved.failure)
            else resolved
        }
        is NotificationNavigationRequest.Channel -> resolveChannel(lookup, request.radioId, request.index)
        is NotificationNavigationRequest.Reaction -> {
            var contactFailure: NavigationFailure.Repository? = null
            val contact = try {
                request.contact?.let { lookup.contact(it) }
            } catch (error: PersistenceStoreException) {
                (error.cause as? CancellationException)?.let { throw it }
                currentCoroutineContext().ensureActive()
                contactFailure = NavigationFailure.Repository(error.error)
                null
            }
            if (contact != null) {
                val key = requireNotNull(request.contact)
                val expected = key.radioId
                if (contact.radioId != expected) Resolved.Failure(NavigationFailure.WrongRadio(expected, contact.radioId))
                else if (contact.id != key.id) Resolved.Failure(NavigationFailure.WrongTarget(NavigationTarget.CONTACT))
                else Resolved.Chat(contact, request.messageId)
            } else if (request.channelRadioId != null && request.channelIndex != null) {
                val currentRadio = mutableState.value.radioId
                if (currentRadio != null && currentRadio != request.channelRadioId)
                    Resolved.Failure(NavigationFailure.WrongRadio(currentRadio, request.channelRadioId))
                else when (val channel = resolveChannel(lookup, request.channelRadioId, request.channelIndex, request.messageId)) {
                    is Resolved.Channel -> channel.copy(recoveryFailure = contactFailure)
                    is Resolved.Failure -> if (channel.failure is NavigationFailure.TargetNotFound)
                        Resolved.Failure(contactFailure ?: channel.failure) else channel
                    else -> channel
                }
            } else Resolved.Failure(contactFailure ?: NavigationFailure.TargetNotFound(NavigationTarget.REACTION))
        }
        is NotificationNavigationRequest.Room -> lookup.room(request.session)?.let {
            if (it.radioId != request.session.radioId)
                Resolved.Failure(NavigationFailure.WrongRadio(request.session.radioId, it.radioId))
            else if (it.id != request.session.id) Resolved.Failure(NavigationFailure.WrongTarget(NavigationTarget.ROOM))
            else Resolved.Room(it)
        } ?: Resolved.Failure(NavigationFailure.TargetNotFound(NavigationTarget.ROOM))
        NotificationNavigationRequest.Unsupported -> Resolved.Failure(NavigationFailure.UnsupportedNotification)
    }

    private suspend fun resolveContact(lookup: NavigationLookup, key: EntityKey): Resolved =
        lookup.contact(key)?.let {
            if (it.radioId != key.radioId) Resolved.Failure(NavigationFailure.WrongRadio(key.radioId, it.radioId))
            else if (it.id != key.id) Resolved.Failure(NavigationFailure.WrongTarget(NavigationTarget.CONTACT))
            else Resolved.Chat(it)
        } ?: Resolved.Failure(NavigationFailure.TargetNotFound(NavigationTarget.CONTACT))

    private suspend fun resolveChannel(
        lookup: NavigationLookup, radioId: RadioId, index: UByte, messageId: UUID? = null,
    ): Resolved = lookup.channel(radioId, index)?.let {
        if (it.radioId != radioId) Resolved.Failure(NavigationFailure.WrongRadio(radioId, it.radioId))
        else if (it.index != index) Resolved.Failure(NavigationFailure.WrongTarget(NavigationTarget.CHANNEL))
        else Resolved.Channel(it, messageId)
    } ?: Resolved.Failure(NavigationFailure.TargetNotFound(NavigationTarget.CHANNEL))

    private sealed interface Resolved {
        data class Chat(val contact: ContactDTO, val messageId: UUID? = null) : Resolved
        data class Channel(
            val channel: ChannelDTO, val messageId: UUID? = null,
            val recoveryFailure: NavigationFailure.Repository? = null,
        ) : Resolved
        data class Room(val session: RemoteNodeSessionDTO) : Resolved
        data class Contact(val contact: ContactDTO) : Resolved
        data object Discovery : Resolved
        data class Fallback(val failure: NavigationFailure) : Resolved
        data class Failure(val failure: NavigationFailure) : Resolved
    }

    private companion object {
        fun clearDeviceSelection(state: NavigationState): NavigationState {
            val retained = state.removeDestinations {
                (it is NavigationDestination.Tool && it.selection.requiresRadio) ||
                    (it is NavigationDestination.Setting && it.selection.requiresDevice)
            }
            return retained.copy(
                selectedTool = if (state.selectedTool?.requiresRadio == true) {
                    retained.stacks.getValue(AppTab.TOOLS)
                        .mapNotNull { (it.destination as? NavigationDestination.Tool)?.selection }.lastOrNull()
                } else state.selectedTool,
                selectedSetting = if (state.selectedSetting?.requiresDevice == true) {
                    retained.stacks.getValue(AppTab.SETTINGS)
                        .mapNotNull { (it.destination as? NavigationDestination.Setting)?.selection }.lastOrNull()
                } else state.selectedSetting,
            )
        }

        fun clearRadioSelection(state: NavigationState): NavigationState =
            clearDeviceSelection(state).removeDestinations {
                it is NavigationDestination.Chat || it is NavigationDestination.ContactDetail ||
                    it is NavigationDestination.RemoteNode ||
                    it == NavigationDestination.Discovery
            }.copy(
                selectedContact = null, nodesShowingDiscovery = false, chatsSelectedRoute = null,
                pendingRoomAuthentication = null, roomAuthenticationDeliveryId = null, roomAuthenticationGeneration = null,
                roomAuthenticationSessionToken = null, boundSessionToken = null,
            )
    }
}
