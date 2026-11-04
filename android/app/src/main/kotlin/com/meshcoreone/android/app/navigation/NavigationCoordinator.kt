// PortedFrom: MC1/State/NavigationCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/MainSidebarView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactDetailView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.FeatureId
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
import com.meshcoreone.android.feature.chats.list.ContactLinkCancellation
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkPublication
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import com.meshcoreone.android.feature.chats.list.ContactLinkHandoffOutcome
import com.meshcoreone.android.feature.chats.list.ContactLinkTargetCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
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

internal sealed interface MountedOnboardingProducer {
    data object NoDevice : MountedOnboardingProducer
    data class Preset(val token: SessionToken) : MountedOnboardingProducer
}

internal class MountedOnboardingCompletion internal constructor(
    private val owner: Any,
    internal val presented: NavigationStateStore.PublishedSnapshot,
    internal val entryId: Long,
    private val sessionCurrent: (MountedOnboardingProducer) -> Boolean,
) {
    private val retired = AtomicBoolean(false)
    internal fun belongsTo(owner: Any): Boolean = this.owner === owner
    internal fun isActive(owner: Any): Boolean = belongsTo(owner) && !retired.get()
    internal fun isCurrent(owner: Any, producer: MountedOnboardingProducer): Boolean =
        isActive(owner) && sessionCurrent(producer)
    internal fun retire(owner: Any) {
        if (belongsTo(owner)) retired.set(true)
    }
}

class NavigationCoordinator(initial: NavigationState = NavigationState()) {
    private val mutableState = NavigationStateStore(initial)
    val state: StateFlow<NavigationState> = mutableState.state
    internal val publishedNavigation get() = mutableState.publishedSnapshots
    internal val currentState: NavigationState get() = mutableState.value
    internal val contactHandoffUpdates get() = mutableState.publishedSnapshots
    private val notificationMutex = Mutex()
    private val drainMutex = Mutex()
    private val localCommitLock = Any()
    private val onboardingIdentity = Any()
    private var commitBoundary: (() -> Unit) -> Unit = { action -> synchronized(localCommitLock, action) }
    private var onboardingSessionCapture: (NavigationState) -> (MountedOnboardingProducer) -> Boolean = { { false } }

    internal fun installCommitBoundary(boundary: (() -> Unit) -> Unit) {
        commitBoundary = boundary
    }

    internal fun installOnboardingSessionCapture(
        capture: (NavigationState) -> (MountedOnboardingProducer) -> Boolean,
    ) {
        onboardingSessionCapture = capture
    }

    internal fun captureMountedOnboarding(
        presented: NavigationStateStore.PublishedSnapshot,
        entryId: Long,
    ): MountedOnboardingCompletion? {
        if (!mutableState.owns(presented)) return null
        val entry = presented.value.activeStack.last()
        if (entry.id != entryId || entry.destination != NavigationDestination.Auxiliary(FeatureId.ONBOARDING)) {
            return null
        }
        return MountedOnboardingCompletion(
            onboardingIdentity, presented, entryId, onboardingSessionCapture(presented.value),
        )
    }

    internal fun retireMountedOnboarding(completion: MountedOnboardingCompletion?) {
        if (completion == null || !completion.belongsTo(onboardingIdentity)) return
        commitBoundary { completion.retire(onboardingIdentity) }
    }

    internal fun retireBinding(action: () -> Unit) = commitBoundary(action)

    private fun commitIfCurrent(
        expected: NavigationStateStore.Snapshot, next: NavigationState, isCurrent: () -> Boolean,
        onCommitted: () -> Unit = {},
    ): Boolean? {
        var committed: Boolean? = null
        val publication = NavigationStateStore.Publication()
        try {
            commitBoundary {
                if (isCurrent()) {
                    committed = mutableState.compareAndSet(expected, next, publication)
                    if (committed == true) onCommitted()
                }
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
            tabBarVisible = false,
        ).withMessageScroll(scrollToMessageID)
    }

    fun navigateToChannel(channel: ChannelDTO, scrollToMessageID: UUID? = null) = mutableState.update {
        val selection = ChatSelection.Channel(channel)
        it.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
            pendingChannel = channel, chatsSelectedRoute = selection,
            tabBarVisible = false,
        ).withMessageScroll(scrollToMessageID)
    }

    private fun NavigationState.withMessageScroll(messageId: UUID?): NavigationState {
        if (messageId == null) return copy(pendingMessageScroll = null)
        val entry = stacks.getValue(AppTab.CHATS).last()
        check(entry.destination is NavigationDestination.Chat) { "A message scroll requires a chat destination" }
        return copy(
            pendingMessageScroll = PendingMessageScroll(nextEntryId, entry.id, messageId),
            nextEntryId = Math.incrementExact(nextEntryId),
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
        mount: ChatContactMount? = null,
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
                mount != null && !snapshot.ownsChatContactMount(mount) -> ContactLinkCaptureDisposition.StaleContext
                !boundGraph && !initialGraph && !offline -> ContactLinkCaptureDisposition.StaleContext
                publicKey == selfPublicKey -> ContactLinkCaptureDisposition.IgnoredSelfContact
                else -> ContactLinkCaptureDisposition.Lookup
            }
            val revision = if (disposition != ContactLinkCaptureDisposition.StaleContext)
                Math.incrementExact(current.contactLookupRevision) else null
            val lookup = ContactLinkLookup(
                this, mount, current.generation, current.boundSessionToken, current.radioId,
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

    internal fun registerChatContactMount(mount: ChatContactMount): ContactLinkPublication {
        val publication = NavigationStateStore.Publication()
        while (true) {
            val snapshot = mutableState.snapshot()
            val mounts = snapshot.handoffs.mounts
            if (mount.registrationSealed.get() || mount.id in mounts) break
            if (mutableState.compareAndSet(
                    snapshot, snapshot.value, publication,
                    handoffs = snapshot.handoffs.copy(mounts = mounts + (mount.id to true)),
                )) break
        }
        return handoffPublication(publication)
    }

    /** Lock-free, not wait-free: no suspension, callbacks or publication on the disposing thread. */
    internal fun sealChatContactMount(mount: ChatContactMount): ContactLinkPublication {
        mount.registrationSealed.set(true)
        val publication = NavigationStateStore.Publication()
        while (true) {
            val snapshot = mutableState.snapshot()
            val handoffs = snapshot.handoffs
            val next = handoffs.copy(
                mounts = handoffs.mounts + (mount.id to false),
                offers = handoffs.offers.mapValues { (_, offer) ->
                    if (offer.mount === mount && offer.terminal == null) {
                        offer.copy(terminal = ContactLinkHandoffOutcome.Superseded)
                    } else offer
                },
            )
            if (mutableState.compareAndSet(snapshot, snapshot.value, publication, handoffs = next)) break
        }
        return handoffPublication(publication)
    }

    private fun handoffPublication(publication: NavigationStateStore.Publication) = object : ContactLinkPublication {
        override fun release() = mutableState.release(publication)
    }

    internal fun pendingChatContactOffers(): List<ChatContactOffer> =
        mutableState.snapshot().handoffs.offers.values.filter { it.terminal == null }

    private fun NavigationStateStore.Snapshot.ownsChatContactMount(mount: ChatContactMount): Boolean =
        handoffs.mounts[mount.id] == true && mount.entryId != null &&
            value.selectedTab == AppTab.CHATS && value.activeStack.last().let {
                it.id == mount.entryId && it.destination is NavigationDestination.Chat
            }

    internal fun offerChatContact(
        mount: ChatContactMount, context: ContactLinkContext, target: ContactLinkLookupResolution.Current,
        link: ContactLinkRequest, contact: ContactDTO?, publications: ContactLinkPublications,
        cancellation: ContactLinkCancellation,
        isCurrent: () -> Boolean,
    ): ContactLinkTargetCompletion {
        val offer = ChatContactOffer(UUID.randomUUID(), mount, context, target, link, contact, ChatContactReceipt())
        val publication = NavigationStateStore.Publication()
        publications.defer(handoffPublication(publication))
        while (true) {
            val snapshot = mutableState.snapshot()
            if (!snapshot.ownsChatContactMount(mount) ||
                (context as? CapturedContactLinkContext)?.lookup?.mount !== mount || !isCurrent()
            ) {
                return ContactLinkTargetCompletion.Completed(ContactLinkCompletion.StaleContext)
            }
            val committed = cancellation.commitIfActive {
                mutableState.compareAndSet(
                    snapshot, snapshot.value, publication,
                    handoffs = snapshot.handoffs.copy(offers = snapshot.handoffs.offers + (offer.id to offer)),
                )
            }
            if (committed) return ContactLinkTargetCompletion.Offered(offer.receipt)
        }
    }

    internal fun acceptChatContact(
        offered: ChatContactOffer, publications: ContactLinkPublications,
        currentToken: SessionToken?, allowInitialBinding: Boolean, selfPublicKey: Bytes?,
        isCurrent: () -> Boolean,
    ) {
        val publication = NavigationStateStore.Publication()
        publications.defer(handoffPublication(publication))
        while (true) {
            val snapshot = mutableState.snapshot()
            val offer = snapshot.handoffs.offers[offered.id] ?: return
            if (offer !== offered || offer.terminal != null) return
            val lookup = (offer.context as? CapturedContactLinkContext)?.lookup
            val matched = lookup?.let {
                currentContactLookup(it, snapshot, currentToken, allowInitialBinding, selfPublicKey)
            }
            val eligible = snapshot.ownsChatContactMount(offer.mount) && lookup?.mount === offer.mount && isCurrent() &&
                matched != null && matched.radioId == offer.target.radioId &&
                matched.selfPublicKey == offer.target.selfPublicKey && offer.target.origin === lookup
            val outcome = if (!eligible) null else when {
                offer.link.publicKey != lookup?.publicKey || offer.contact != null &&
                    (offer.contact.radioId != matched?.radioId || offer.contact.publicKey != lookup?.publicKey) ->
                    ContactLinkCompletion.WrongTarget
                lookup?.publicKey == matched?.selfPublicKey -> ContactLinkCompletion.IgnoredSelfContact
                offer.contact == null -> ContactLinkCompletion.ConfirmationStaged
                else -> ContactLinkCompletion.Navigated
            }
            val next = if (outcome == null) snapshot.value else {
                contactLookupDestination(snapshot.value, offer.link, offer.contact, matched?.radioId, outcome, false)
                    .copy(contactLookupRevision = Math.incrementExact(snapshot.value.contactLookupRevision))
            }
            val terminal = if (outcome == null) ContactLinkHandoffOutcome.Superseded
                else ContactLinkHandoffOutcome.Accepted(outcome)
            if (mutableState.compareAndSet(
                    snapshot, next, publication,
                    handoffs = snapshot.handoffs.copy(offers = snapshot.handoffs.offers +
                        (offer.id to offer.copy(terminal = terminal))),
                )) return
        }
    }

    internal fun settlePublishedChatContactReceipts() {
        // A release may leave an older gate pending. Only exact published proof authorizes settlement.
        val terminals = mutableState.publishedHandoffs().offers.values.filter { it.terminal != null }
        terminals.forEach { it.receipt.settle(checkNotNull(it.terminal)) }
        while (true) {
            val snapshot = mutableState.snapshot()
            val handoffs = snapshot.handoffs
            val settled = terminals.map { it.id }.toSet()
            val offers = handoffs.offers - settled
            val mounts = handoffs.mounts.filter { (id, open) -> open || offers.values.any { it.mount.id == id } }
            if (mutableState.compareAndSet(
                    snapshot, snapshot.value, handoffs = ChatContactHandoffs(mounts, offers),
                )) return
        }
    }

    private fun contactLookupDestination(
        current: NavigationState, link: ContactLinkRequest, contact: ContactDTO?, radio: RadioId?,
        outcome: ContactLinkCompletion, selectChats: Boolean,
    ): NavigationState = when (outcome) {
        ContactLinkCompletion.WrongTarget -> current.copy(failure = NavigationFailure.WrongTarget(NavigationTarget.CONTACT))
        ContactLinkCompletion.IgnoredSelfContact -> if (selectChats) current.select(AppTab.CHATS) else current
        ContactLinkCompletion.ConfirmationStaged ->
            (if (selectChats) current.select(AppTab.CHATS) else current).stageContactLink(link, radio)
        ContactLinkCompletion.Navigated -> current.push(
            AppTab.NODES, NavigationDestination.ContactDetail(checkNotNull(contact)),
        ).copy(pendingContactLink = null, pendingContactDetail = contact, selectedContact = contact, nodesShowingDiscovery = false)
        ContactLinkCompletion.StaleContext -> current
    }

    private fun currentContactLookup(
        lookup: ContactLinkLookup, snapshot: NavigationStateStore.Snapshot, currentToken: SessionToken?,
        allowInitialBinding: Boolean,
        identifiedSelfPublicKey: Bytes?,
    ): ContactLinkLookupResolution.Current? {
        if (lookup.owner !== this || lookup.disposition == ContactLinkCaptureDisposition.StaleContext) return null
        if (lookup.mount != null && !snapshot.ownsChatContactMount(lookup.mount)) return null
        val current = snapshot.value
        val initial = allowInitialBinding && lookup.allowsInitialBinding && currentToken != null &&
            current.initialContactLinkGeneration == lookup.generation &&
            current.generation == Math.incrementExact(lookup.generation) &&
            current.boundSessionToken == currentToken
        val same = current.generation == lookup.generation && current.boundSessionToken == lookup.boundToken &&
            current.radioId == lookup.navigationRadioId
        if (!same && !initial || lookup.revision == null || current.contactLookupRevision != lookup.revision) return null
        val radio = lookup.radioId ?: currentToken?.radioId.takeIf {
            allowInitialBinding && lookup.allowsInitialBinding
        }
        if (current.radioId != null && current.radioId != radio) return null
        val selfPublicKey = if (lookup.allowsInitialBinding) {
            identifiedSelfPublicKey ?: lookup.selfPublicKey
        } else lookup.selfPublicKey
        return ContactLinkLookupResolution.Current(radio, selfPublicKey, lookup)
    }

    internal fun resolveCapturedContactLookup(
        lookup: ContactLinkLookup, currentToken: SessionToken?, allowInitialBinding: Boolean,
        identifiedSelfPublicKey: Bytes?,
    ): ContactLinkLookupResolution = currentContactLookup(
        lookup, mutableState.snapshot(), currentToken, allowInitialBinding, identifiedSelfPublicKey,
    ) ?: ContactLinkLookupResolution.Stale

    internal fun isCapturedContactLookupCurrent(
        lookup: ContactLinkLookup, currentToken: SessionToken?, allowInitialBinding: Boolean,
    ): Boolean = resolveCapturedContactLookup(lookup, currentToken, allowInitialBinding, null) is
        ContactLinkLookupResolution.Current

    internal fun completeCapturedContactLookup(
        lookup: ContactLinkLookup, link: ContactLinkRequest, contact: ContactDTO?,
        currentToken: SessionToken?, allowInitialBinding: Boolean, identifiedSelfPublicKey: Bytes?,
        selectChats: Boolean, publications: ContactLinkPublications,
    ): ContactLinkCompletion {
        val target = ContactLinkLookupResolution.Current(lookup.radioId, lookup.selfPublicKey, lookup)
        return when (val completion = completeResolvedCapturedContactLookup(
            lookup, target, link, contact, currentToken, allowInitialBinding, identifiedSelfPublicKey,
            selectChats, publications,
        )) {
            is ContactLinkResolvedCompletion.Completed -> completion.outcome
            ContactLinkResolvedCompletion.TargetChanged -> ContactLinkCompletion.StaleContext
        }
    }

    internal fun completeResolvedCapturedContactLookup(
        lookup: ContactLinkLookup, target: ContactLinkLookupResolution.Current,
        link: ContactLinkRequest, contact: ContactDTO?,
        currentToken: SessionToken?, allowInitialBinding: Boolean, identifiedSelfPublicKey: Bytes?,
        selectChats: Boolean, publications: ContactLinkPublications,
        cancellation: ContactLinkCancellation? = null,
    ): ContactLinkResolvedCompletion {
        cancellation?.ensureActive()
        if (target.origin !== lookup) return ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.StaleContext)
        var publication: NavigationStateStore.Publication? = null
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            val matched = currentContactLookup(lookup, snapshot, currentToken, allowInitialBinding, identifiedSelfPublicKey)
                ?: return ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.StaleContext)
            if (target.radioId != matched.radioId || target.selfPublicKey != matched.selfPublicKey) {
                return ContactLinkResolvedCompletion.TargetChanged
            }
            val capturedSelf = lookup.disposition == ContactLinkCaptureDisposition.IgnoredSelfContact
            val selfContact = capturedSelf ||
                lookup.allowsInitialBinding && lookup.publicKey == matched.selfPublicKey
            val radio = matched.radioId
            val wrongTarget = link.publicKey != lookup.publicKey ||
                contact != null && (contact.radioId != radio || contact.publicKey != lookup.publicKey)
            val outcome = when {
                wrongTarget -> ContactLinkCompletion.WrongTarget
                selfContact -> ContactLinkCompletion.IgnoredSelfContact
                contact == null -> ContactLinkCompletion.ConfirmationStaged
                else -> ContactLinkCompletion.Navigated
            }
            val destination = contactLookupDestination(current, link, contact, radio, outcome, selectChats)
            val next = destination.copy(contactLookupRevision = Math.incrementExact(current.contactLookupRevision))
            val deferred = publication ?: contactLinkPublication(publications).also { publication = it }
            val committed = cancellation?.commitIfActive {
                mutableState.compareAndSet(snapshot, next, deferred)
            } ?: mutableState.compareAndSet(snapshot, next, deferred)
            if (committed) {
                return ContactLinkResolvedCompletion.Completed(outcome)
            }
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
    fun clearPendingScrollToMessage() = mutableState.update { it.copy(pendingMessageScroll = null) }

    internal fun isPendingMessageScroll(entryId: Long, request: PendingMessageScroll): Boolean =
        mutableState.value.pendingMessageScrollFor(entryId) == request

    internal fun consumePendingMessageScroll(entryId: Long, request: PendingMessageScroll): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            if (current.pendingMessageScrollFor(entryId) != request) return false
            if (mutableState.compareAndSet(snapshot, current.copy(pendingMessageScroll = null))) return true
        }
    }

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

    internal fun setNavigationReadyWhen(ready: Boolean, isCurrent: () -> Boolean): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            when (commitIfCurrent(snapshot, snapshot.value.copy(navigationReady = ready), isCurrent)) {
                null -> return false
                true -> return true
                false -> Unit
            }
        }
    }

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

    internal fun clearPerRadioSelectionWhen(isCurrent: () -> Boolean): Boolean {
        while (true) {
            val snapshot = mutableState.snapshot()
            val next = clearRadioSelection(snapshot.value).copy(generation = Math.incrementExact(snapshot.value.generation))
            when (commitIfCurrent(snapshot, next, isCurrent)) {
                null -> return false
                true -> return true
                false -> Unit
            }
        }
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
                            navigationReady = false,
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
    ): Boolean = bindNotificationSessionOwned(radioId, localPublicKey, token, isCurrentSession) != null

    internal fun bindNotificationSessionOwned(
        radioId: RadioId,
        localPublicKey: Bytes?,
        token: SessionToken?,
        isCurrentSession: () -> Boolean,
    ): NavigationCleanupOwner? {
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
                null -> return null
                true -> return NavigationCleanupOwner(token, radioId, withRoom.generation)
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
            pendingMessageScroll = retained.pendingMessageScroll?.takeIf {
                chats != null && it.entryId == retained.stacks.getValue(AppTab.CHATS).last().id
            },
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
            pendingDiscoveryNavigation = false, pendingMessageScroll = null, pendingMapFocus = null,
        )

    fun manuallyDisconnect() = mutableState.update {
        clearDeviceSelection(it).withoutRoomAuthentication().copy(
            generation = Math.incrementExact(it.generation), boundSessionToken = null,
        )
    }

    fun back(): Boolean = back(publishedNavigation.value)

    internal fun completeMountedOnboarding(
        completion: MountedOnboardingCompletion?, producer: MountedOnboardingProducer,
    ): Boolean {
        if (completion == null || !completion.belongsTo(onboardingIdentity)) return false
        val presented = completion.presented
        val source = presented.value
        val entry = source.activeStack.last()
        if (entry.id != completion.entryId || entry.destination != NavigationDestination.Auxiliary(FeatureId.ONBOARDING)) {
            return false
        }
        fun sameSession(state: NavigationState): Boolean {
            val unchanged = state.generation == source.generation && state.radioId == source.radioId &&
                state.boundSessionToken == source.boundSessionToken
            val firstBinding = source.radioId == null && source.boundSessionToken == null &&
                source.generation < Long.MAX_VALUE && state.generation == source.generation + 1 &&
                state.radioId != null && state.boundSessionToken?.radioId == state.radioId
            return unchanged || firstBinding
        }

        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            if (snapshot.revision < presented.revision || snapshot.activeEntryEpoch != presented.activeEntryEpoch ||
                !sameSession(current) ||
                current.selectedTab != source.selectedTab || current.activeStack.last() != entry
            ) return false
            val next = current.withRetainedStack(current.selectedTab, current.activeStack.dropLast(1))
                .select(AppTab.CHATS)
            when (commitIfCurrent(
                snapshot, next,
                isCurrent = { sameSession(mutableState.value) && completion.isCurrent(onboardingIdentity, producer) },
                onCommitted = { completion.retire(onboardingIdentity) },
            )) {
                true -> return true
                null -> return false
                false -> Unit
            }
        }
    }

    internal fun reportMountedOnboardingFailure(completion: MountedOnboardingCompletion?): Boolean {
        if (completion == null || !completion.belongsTo(onboardingIdentity)) return false
        val source = completion.presented
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            if (snapshot.activeEntryEpoch != source.activeEntryEpoch ||
                current.selectedTab != source.value.selectedTab ||
                current.activeStack.last() != source.value.activeStack.last()
            ) return false
            if (current.failure != null) return false
            val failure = NavigationFailure.StaleOnboarding(Math.incrementExact(snapshot.revision))
            when (commitIfCurrent(
                snapshot, current.copy(failure = failure),
                isCurrent = { completion.isActive(onboardingIdentity) },
                onCommitted = { completion.retire(onboardingIdentity) },
            )) {
                true -> return true
                null -> return false
                false -> Unit
            }
        }
    }

    internal fun back(presented: NavigationStateStore.PublishedSnapshot): Boolean {
        val source = presented.value
        if (!source.canGoBack) return false
        val tab = source.selectedTab
        val presentedStack = source.activeStack
        while (true) {
            val snapshot = mutableState.snapshot()
            val current = snapshot.value
            if (snapshot.revision < presented.revision || current.generation != source.generation ||
                current.radioId != source.radioId || current.boundSessionToken != source.boundSessionToken
            ) return false
            val currentStack = current.stacks.getValue(tab)
            if (currentStack.take(presentedStack.size).map { it.id } != presentedStack.map { it.id }) return false
            // Leave the presented entry rather than an unseen descendant.
            val next = if (presentedStack.size == 1) current.select(AppTab.CHATS)
                else current.withRetainedStack(tab, currentStack.take(presentedStack.size - 1))
            if (mutableState.compareAndSet(snapshot, next)) return true
        }
    }

    private fun NavigationState.withRetainedStack(tab: AppTab, entries: List<NavigationEntry>): NavigationState {
        val stack = entries.snapshot()
        val destination = stack.last().destination
        return copy(
            selectedTab = tab,
            stacks = (stacks + (tab to stack)).snapshotMap(),
            tabBarVisible = stack.size == 1,
            chatsSelectedRoute = if (tab == AppTab.CHATS)
                (destination as? NavigationDestination.Chat)?.selection else chatsSelectedRoute,
            selectedContact = if (tab == AppTab.NODES)
                (destination as? NavigationDestination.ContactDetail)?.contact else selectedContact,
            nodesShowingDiscovery = if (tab == AppTab.NODES)
                destination == NavigationDestination.Discovery else nodesShowingDiscovery,
            selectedTool = if (tab == AppTab.TOOLS)
                (destination as? NavigationDestination.Tool)?.selection else selectedTool,
            selectedSetting = if (tab == AppTab.SETTINGS)
                (destination as? NavigationDestination.Setting)?.selection else selectedSetting,
            pendingChatContact = if (tab == AppTab.CHATS) null else pendingChatContact,
            pendingChannel = if (tab == AppTab.CHATS) null else pendingChannel,
            pendingRoomSession = if (tab == AppTab.CHATS) null else pendingRoomSession,
            pendingMessageScroll = if (tab == AppTab.CHATS) null else pendingMessageScroll,
            pendingContactDetail = if (tab == AppTab.NODES) null else pendingContactDetail,
            pendingDiscoveryNavigation = if (tab == AppTab.NODES) false else pendingDiscoveryNavigation,
        )
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
                            tabBarVisible = false,
                        ).withMessageScroll(resolved.messageId)
                    }
                    is Resolved.Channel -> {
                        val selection = ChatSelection.Channel(resolved.channel)
                        delivery.push(AppTab.CHATS, NavigationDestination.Chat(selection)).copy(
                            pendingChannel = resolved.channel, chatsSelectedRoute = selection,
                            tabBarVisible = false, failure = resolved.recoveryFailure,
                        ).withMessageScroll(resolved.messageId)
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
