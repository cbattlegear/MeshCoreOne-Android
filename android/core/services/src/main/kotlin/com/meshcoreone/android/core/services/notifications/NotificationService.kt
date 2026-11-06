// PortedFrom: MC1Services/Sources/MC1Services/Services/NotificationService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Protocols/NotificationStringProvider.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.domain.NotificationStringProvider
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationActionReceipt
import com.meshcoreone.android.core.contracts.notifications.NotificationCategory
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.NotificationPreferences
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The conversation the user is viewing; at most one slot is populated (Swift's four `active*` vars). */
data class ActiveConversation(
    val contactID: UUID? = null,
    val channelIndex: UByte? = null,
    val channelRadioId: RadioId? = null,
    val roomSessionID: UUID? = null,
)

/**
 * Notification business policy for one radio session: what to notify, suppression, active-conversation
 * tracking, badge policy, drafts, and routing of notification responses to the action callbacks.
 * Platform delivery goes through [NotificationDeliveryPort] (WP-401).
 *
 * Swift `@MainActor` state becomes atomics plus a lock that is never held across a suspension. The
 * Swift 5 s `NotificationPreferences()` cache becomes a read of [preferencesPort], which is already an
 * in-memory snapshot. Every delivery, clock and unread-count failure is contained, so notification
 * problems never block messaging. [radioId] qualifies the Swift bare contact/session UUIDs.
 *
 * Exposes every member of WP-209's `ContactCleanupNotifications` and the posting members of WP-214's
 * `SyncNotificationServicing` with identical signatures.
 */
class NotificationService(
    val radioId: RadioId,
    private val delivery: NotificationDeliveryPort,
    private val preferencesPort: NotificationPreferencesPort,
    private val appState: AppStateProvider,
    private val unreadCounting: NotificationUnreadCounting,
    private val scope: CoroutineScope,
    private val clock: NotificationClock = SystemNotificationClock,
) {
    internal val logger: Logger = Logger.getLogger("com.mc1.Notifications")
    private val lock = Any()

    // MARK: - Authorization and strings

    @Volatile
    var authorizationStatus: NotificationAuthorizationStatus = NotificationAuthorizationStatus.NOT_DETERMINED
        private set

    /** Whether notification permissions are authorized. */
    val isAuthorized: Boolean get() = authorizationStatus == NotificationAuthorizationStatus.AUTHORIZED

    @Volatile
    private var stringProvider: NotificationStringProvider? = null

    /** Sets the string provider for localized notification content. */
    fun setStringProvider(provider: NotificationStringProvider) {
        stringProvider = provider
    }

    /** Read access for [NotificationActionHandler], which builds localized display strings. */
    internal val strings: NotificationStringProvider? get() = stringProvider

    // MARK: - Action callbacks (installed by the app layer; WP-303 clears them on teardown)

    @Volatile var onQuickReply: (suspend (contact: EntityKey, text: String) -> Unit)? = null
    @Volatile var onNotificationTapped: (suspend (contact: EntityKey) -> Unit)? = null
    @Volatile var onChannelNotificationTapped: (suspend (radioId: RadioId, channelIndex: UByte) -> Unit)? = null
    @Volatile var onRoomNotificationTapped: (suspend (session: EntityKey) -> Unit)? = null
    @Volatile var onNewContactNotificationTapped: (suspend (contact: EntityKey) -> Unit)? = null

    @Volatile
    var onReactionNotificationTapped: (
        suspend (contact: EntityKey?, channelIndex: UByte?, radioId: RadioId?, messageID: UUID) -> Unit
    )? = null

    @Volatile var onMarkAsRead: (suspend (contact: EntityKey, messageID: UUID) -> Unit)? = null
    @Volatile var onChannelMarkAsRead: (suspend (radioId: RadioId, channelIndex: UByte, messageID: UUID) -> Unit)? = null
    @Volatile var onRoomMarkAsRead: (suspend (session: EntityKey, messageID: UUID) -> Unit)? = null
    @Volatile var onChannelQuickReply: (suspend (radioId: RadioId, channelIndex: UByte, text: String) -> Unit)? = null

    // MARK: - Suppression, badge, active conversation

    /** Badge count last computed by [updateBadgeCount]. */
    @Volatile
    var badgeCount: Long = 0
        private set

    /** Whether message notifications are temporarily suppressed (during the sync window). */
    @Volatile var isSuppressingNotifications: Boolean = false

    private val active = AtomicReference(ActiveConversation())

    val activeConversation: ActiveConversation get() = active.get()

    var activeContactID: UUID?
        get() = active.get().contactID
        set(value) { active.updateAndGet { it.copy(contactID = value) } }

    var activeChannelIndex: UByte?
        get() = active.get().channelIndex
        set(value) { active.updateAndGet { it.copy(channelIndex = value) } }

    var activeChannelRadioId: RadioId?
        get() = active.get().channelRadioId
        set(value) { active.updateAndGet { it.copy(channelRadioId = value) } }

    var activeRoomSessionID: UUID?
        get() = active.get().roomSessionID
        set(value) { active.updateAndGet { it.copy(roomSessionID = value) } }

    /** Atomically sets the active conversation, clearing every slot the caller does not pass. */
    fun setActiveConversation(
        contactID: UUID? = null,
        channelIndex: UByte? = null,
        channelRadioId: RadioId? = null,
        roomSessionID: UUID? = null,
    ) {
        active.set(ActiveConversation(contactID, channelIndex, channelRadioId, roomSessionID))
    }

    /** Serializes badge computations so an older read can never overwrite a newer one. */
    private val badgeUpdateMutex = Mutex()

    /** Guarded by [lock]: the debounced badge task. */
    private var pendingBadgeUpdate: Job? = null

    /** Guarded by [lock]: in-memory quick-reply drafts, lost with the session as in Swift. */
    private val pendingDrafts = HashMap<EntityKey, String>()
    private val pendingDraftOwners = HashMap<EntityKey, NotificationActionReceipt>()

    // MARK: - Setup and authorization

    /** Registers notification categories, then checks the current authorization status. */
    suspend fun setup() {
        contained("registerCategories", Unit) { delivery.registerCategories(notificationCategoryDefinitions(stringProvider)) }
        checkAuthorizationStatus()
    }

    /** Requests notification authorization; a failure counts as denied. */
    suspend fun requestAuthorization(): Boolean {
        val granted = contained("requestAuthorization", false) { delivery.requestAuthorization() }
        authorizationStatus = if (granted) NotificationAuthorizationStatus.AUTHORIZED else NotificationAuthorizationStatus.DENIED
        return granted
    }

    /** Refreshes [authorizationStatus] from the platform; a failure leaves it unchanged. */
    suspend fun checkAuthorizationStatus() {
        val status = contained("authorizationStatus", null) { delivery.authorizationStatus() } ?: return
        authorizationStatus = status
    }

    // MARK: - Posting

    suspend fun postDirectMessageNotification(
        from: String,
        contactID: UUID,
        messageText: String,
        messageID: UUID,
        isMuted: Boolean = false,
    ) {
        if (isMuted || !isAuthorized) return
        val prefs = preferences()
        if (!prefs.contactMessagesEnabled) return
        if (isSuppressingNotifications) return
        deliver(
            NotificationRequest(
                id = NotificationId(messageID.canonicalString()), category = NotificationCategory.DIRECT_MESSAGE,
                title = from, body = messageText, soundEnabled = prefs.soundEnabled, badge = nextBadge(prefs),
                threadIdentifier = contactID.canonicalString(),
                payload = NotificationPayload.DirectMessage(EntityKey(radioId, contactID), messageID),
            ),
        )
        if (prefs.badgeEnabled) updateBadgeCount()
    }

    suspend fun postChannelMessageNotification(
        channelName: String,
        channelIndex: UByte,
        radioId: RadioId,
        senderName: String?,
        messageText: String,
        messageID: UUID,
        notificationLevel: NotificationLevel,
        hasSelfMention: Boolean,
    ) {
        if (notificationLevel == NotificationLevel.MUTED) return
        if (notificationLevel == NotificationLevel.MENTIONS_ONLY && !hasSelfMention) return
        if (!isAuthorized) return
        val prefs = preferences()
        if (!prefs.channelMessagesEnabled || isSuppressingNotifications) return
        deliver(
            NotificationRequest(
                id = NotificationId(messageID.canonicalString()), category = NotificationCategory.CHANNEL_MESSAGE,
                title = channelName, body = senderBody(senderName, messageText), soundEnabled = prefs.soundEnabled,
                badge = nextBadge(prefs), threadIdentifier = "channel-${radioId.canonicalString}-$channelIndex",
                payload = NotificationPayload.ChannelMessage(radioId, channelIndex, messageID),
            ),
        )
        if (prefs.badgeEnabled) updateBadgeCount()
    }

    suspend fun postRoomMessageNotification(
        roomName: String,
        sessionID: UUID,
        senderName: String?,
        messageText: String,
        messageID: UUID,
        notificationLevel: NotificationLevel,
    ) {
        if (notificationLevel == NotificationLevel.MUTED) return
        if (!isAuthorized) return
        val prefs = preferences()
        if (!prefs.roomMessagesEnabled || isSuppressingNotifications) return
        deliver(
            NotificationRequest(
                id = NotificationId(messageID.canonicalString()), category = NotificationCategory.ROOM_MESSAGE,
                title = roomName, body = senderBody(senderName, messageText), soundEnabled = prefs.soundEnabled,
                badge = nextBadge(prefs), threadIdentifier = "room-${sessionID.canonicalString()}",
                payload = NotificationPayload.RoomMessage(roomName, EntityKey(radioId, sessionID), messageID),
            ),
        )
        if (prefs.badgeEnabled) updateBadgeCount()
    }

    /** Posts that a new contact of [contactType] was discovered; not subject to sync suppression, as in Swift. */
    suspend fun postNewContactNotification(contactName: String, contactID: UUID, contactType: ContactType) {
        if (!isAuthorized) return
        val prefs = preferences()
        if (!prefs.newContactDiscoveredEnabled) return
        val typeEnabled = when (contactType) {
            ContactType.CHAT -> prefs.discoveryContactEnabled
            ContactType.REPEATER -> prefs.discoveryRepeaterEnabled
            ContactType.ROOM -> prefs.discoveryRoomEnabled
        }
        if (!typeEnabled) return
        val strings = stringProvider
        deliver(
            NotificationRequest(
                id = NotificationId("new-contact-${contactID.canonicalString()}"), category = null,
                title = strings?.discoveryNotificationTitle(contactType) ?: defaultDiscoveryTitle(contactType),
                body = contactName.ifEmpty { strings?.unknownContactName ?: "Unknown Contact" },
                soundEnabled = prefs.soundEnabled, badge = null, threadIdentifier = "discovery",
                payload = NotificationPayload.NewContact(EntityKey(radioId, contactID)),
            ),
        )
    }

    /** Default English titles when no string provider is set. */
    internal fun defaultDiscoveryTitle(type: ContactType): String = when (type) {
        ContactType.CHAT -> "New Contact Discovered"
        ContactType.REPEATER -> "New Repeater Discovered"
        ContactType.ROOM -> "New Room Discovered"
    }

    /** Posts that someone reacted to the user's message; [radioId] accompanies [channelIndex] for channel reactions. */
    suspend fun postReactionNotification(
        reactorName: String,
        body: String,
        messageID: UUID,
        contactID: UUID?,
        channelIndex: UByte?,
        radioId: RadioId?,
    ) {
        if (!isAuthorized) return
        val prefs = preferences()
        if (!prefs.reactionNotificationsEnabled || isSuppressingNotifications) return
        val contact = contactID?.let { EntityKey(this.radioId, it) }
        val channelRadio = if (channelIndex != null) radioId else null
        val thread = when {
            channelIndex != null && channelRadio != null -> "reaction-channel-${channelRadio.canonicalString}-$channelIndex"
            contactID != null -> "reaction-contact-${contactID.canonicalString()}"
            else -> null
        }
        deliver(
            NotificationRequest(
                id = NotificationId("reaction-${messageID.canonicalString()}-$reactorName-${timeSuffix()}"),
                category = NotificationCategory.REACTION, title = reactorName, body = body,
                soundEnabled = prefs.soundEnabled, badge = null, threadIdentifier = thread,
                payload = NotificationPayload.Reaction(
                    messageID, contact, channelIndex.takeIf { channelRadio != null }, channelRadio,
                ),
            ),
        )
    }

    /** Posts a low battery warning, identified by device name so repeats replace each other. */
    suspend fun postLowBatteryNotification(deviceName: String, batteryPercentage: Long) {
        if (!isAuthorized) return
        val prefs = preferences()
        if (!prefs.lowBatteryEnabled) return
        val strings = stringProvider
        deliver(
            NotificationRequest(
                id = NotificationId("low-battery-$deviceName"), category = NotificationCategory.LOW_BATTERY,
                title = strings?.lowBatteryTitle ?: "Low Battery",
                body = strings?.lowBatteryBody(deviceName, batteryPercentage) ?: "$deviceName battery is at $batteryPercentage%",
                soundEnabled = prefs.soundEnabled, badge = null, threadIdentifier = null,
                payload = NotificationPayload.LowBattery(batteryPercentage),
            ),
        )
    }

    /** Posts that a direct-message quick reply failed to send; always with sound, as in Swift. */
    suspend fun postQuickReplyFailedNotification(contactName: String, contactID: UUID) =
        postQuickReplyFailedNotification(contactName, EntityKey(radioId, contactID))

    /** [postQuickReplyFailedNotification] for a contact that may belong to another radio; the payload keeps its radio. */
    suspend fun postQuickReplyFailedNotification(contactName: String, contact: EntityKey) {
        if (!isAuthorized) return
        val strings = stringProvider
        deliver(
            NotificationRequest(
                id = NotificationId("quick-reply-failed-${contact.id.canonicalString()}-${timeSuffix()}"),
                category = NotificationCategory.DIRECT_MESSAGE, title = strings?.quickReplyFailedTitle ?: "Message Not Sent",
                body = strings?.quickReplyFailedBody(contactName) ?: "Your reply to $contactName couldn't be sent.",
                soundEnabled = true, badge = null, threadIdentifier = null,
                payload = NotificationPayload.QuickReplyFailed(contact),
            ),
        )
    }

    /** Posts that a channel quick reply failed to send. */
    suspend fun postChannelQuickReplyFailedNotification(channelName: String, radioId: RadioId, channelIndex: UByte) {
        if (!isAuthorized) return
        val strings = stringProvider
        deliver(
            NotificationRequest(
                id = NotificationId("channel-reply-failed-${radioId.canonicalString}-$channelIndex-${timeSuffix()}"),
                category = null, title = strings?.quickReplyFailedTitle ?: "Message Not Sent",
                body = strings?.quickReplyFailedBody(channelName) ?: "Your reply to $channelName couldn't be sent.",
                soundEnabled = true, badge = null, threadIdentifier = null,
                payload = NotificationPayload.ChannelQuickReplyFailed(radioId, channelIndex),
            ),
        )
    }

    // MARK: - Drafts

    /**
     * Saves a draft for [contact] when a quick reply fails; in-memory only, lost with this session.
     * Keyed by radio plus id (Swift: contact UUID) so a same-UUID contact of another radio never sees it.
     */
    fun saveDraft(contact: EntityKey, text: String) {
        synchronized(lock) {
            pendingDrafts[contact] = text
            pendingDraftOwners.remove(contact)
        }
    }

    internal fun saveActionDraft(contact: EntityKey, text: String, owner: NotificationActionReceipt) {
        synchronized(lock) {
            pendingDrafts[contact] = text
            pendingDraftOwners[contact] = owner
        }
    }

    internal fun clearActionDraft(contact: EntityKey, owner: NotificationActionReceipt) {
        synchronized(lock) {
            if (pendingDraftOwners[contact] === owner) {
                pendingDrafts.remove(contact)
                pendingDraftOwners.remove(contact)
            }
        }
    }

    /** Retrieves and removes the draft for [contact], or null when none exists. */
    fun consumeDraft(contact: EntityKey): String? = synchronized(lock) {
        pendingDraftOwners.remove(contact)
        pendingDrafts.remove(contact)
    }

    // MARK: - Badge

    /**
     * Recomputes the badge after a 100 ms debounce. A later call cancels a pending one that has not
     * started computing; this call then returns without the update, as Swift's awaited task does.
     * A computation that already started finishes (Swift's update is not cooperative) and computations
     * run one at a time in start order, so the newest read always lands last.
     */
    suspend fun updateBadgeCount() {
        val task = scope.launch(start = CoroutineStart.LAZY) {
            // Swift `try? await Task.sleep`: a failed sleep is ignored, only cancellation stops the update.
            contained("badgeDebounce", Unit) { clock.sleep(BADGE_DEBOUNCE) }
            ensureActive()
            withContext(NonCancellable) { badgeUpdateMutex.withLock { contained("badgeUpdate", Unit) { performBadgeUpdate() } } }
        }
        val previous = synchronized(lock) { pendingBadgeUpdate.also { pendingBadgeUpdate = task } }
        previous?.cancel()
        task.start()
        task.join()
    }

    private suspend fun performBadgeUpdate() {
        val prefs = preferences()
        if (!prefs.badgeEnabled) {
            badgeCount = 0
            contained("setBadgeCount", Unit) { delivery.setBadgeCount(0) }
            return
        }
        val counts = contained("unreadCounts", ZERO_COUNTS) { unreadCounting.unreadCounts() }
        var total = 0L
        if (prefs.contactMessagesEnabled) total += counts.contacts
        if (prefs.channelMessagesEnabled) total += counts.channels
        if (prefs.roomMessagesEnabled) total += counts.rooms
        badgeCount = total
        contained("setBadgeCount", Unit) { delivery.setBadgeCount(total) }
    }

    // MARK: - Removing delivered notifications

    /** Removes the delivered notification for [messageID]. */
    suspend fun removeDeliveredNotification(messageID: UUID) {
        removeIds(listOf(NotificationId(messageID.canonicalString())))
    }

    /** Removes every delivered notification for [contactId]. */
    suspend fun removeDeliveredNotifications(contactId: UUID) = removeDeliveredNotifications(setOf(contactId))

    /** Removes delivered notifications for [contactIds], listing delivered notifications once. */
    suspend fun removeDeliveredNotifications(contactIds: Set<UUID>) {
        if (contactIds.isEmpty()) return
        removeDeliveredMatching { it.contact?.id in contactIds }
    }

    /** Removes every delivered notification for the channel at [channelIndex] on [radioId]. */
    suspend fun removeDeliveredNotifications(channelIndex: UByte, radioId: RadioId) =
        removeDeliveredMatching { it.channelIndex == channelIndex && it.channelRadioId == radioId }

    /** Removes every delivered notification for the room session [sessionID]. */
    suspend fun removeDeliveredNotificationsForRoom(sessionID: UUID) =
        removeDeliveredMatching { it.session?.id == sessionID }

    private suspend fun removeDeliveredMatching(predicate: (PayloadKeys) -> Boolean) {
        val delivered = contained("deliveredNotifications", null) { delivery.deliveredNotifications() } ?: return
        val ids = delivered.filter { notification -> notification.payload?.let { predicate(PayloadKeys.of(it)) } == true }
            .map { it.id }
        if (ids.isNotEmpty()) removeIds(ids)
    }

    private suspend fun removeIds(ids: List<NotificationId>) {
        contained("removeDelivered", Unit) { delivery.removeDelivered(ids.snapshot()) }
    }

    // MARK: - Helpers

    private fun preferences(): NotificationPreferences = preferencesPort.preferences.value

    private fun nextBadge(prefs: NotificationPreferences): Long? = if (prefs.badgeEnabled) badgeCount + 1 else null

    private fun senderBody(senderName: String?, messageText: String): String =
        if (senderName != null) "$senderName: $messageText" else messageText

    /** Swift `Date().timeIntervalSince1970`, used only to make identifiers unique. */
    private fun timeSuffix(): String {
        val now = clock.now()
        return "${now.epochSecond}.${now.nano.toString().padStart(9, '0')}"
    }

    /**
     * Hands [request] to the platform. Android has no `willPresent`, so the foreground decision is
     * made here: while the app is in the foreground, a notification for the conversation the user is
     * viewing is not shown (Swift returns no presentation options for it).
     */
    private suspend fun deliver(request: NotificationRequest) {
        val foreground = contained("isInForeground", false) { appState.isInForeground() }
        if (foreground && !shouldPresentWhileForeground(request.payload)) return
        when (val result = contained<NotificationPostResult?>("post", null) { delivery.post(request) }) {
            NotificationPostResult.Posted, null -> Unit
            NotificationPostResult.PermissionDenied ->
                logger.warning("Failed to post notification: permission denied")
            is NotificationPostResult.Unsupported ->
                logger.warning("Failed to post notification: unsupported ${result.capability}")
        }
    }

    /** Runs [block], logging and returning [fallback] on any failure except cancellation. */
    internal inline fun <T> contained(operation: String, fallback: T, block: () -> T): T =
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Notification $operation failed", failure)
            fallback
        }

    private companion object {
        val BADGE_DEBOUNCE = 100.milliseconds
        val ZERO_COUNTS = UnreadCounts(0, 0, 0)
    }
}
