// AndroidOnly: WP-215 Hand-written fakes and a single-threaded harness for the notification policy tests.
package com.meshcoreone.android.core.services.notifications

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationId
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.domain.NotificationStringProvider
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.UnreadCounts
import com.meshcoreone.android.core.contracts.notifications.DeliveredNotification
import com.meshcoreone.android.core.contracts.notifications.DeliveredPostIdentity
import com.meshcoreone.android.core.contracts.notifications.SnapshotRemovalOutcome
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationCategoryDefinition
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.NotificationPreferences
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.TreeMap
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicTest

internal val RADIO_A = RadioId(UUID.fromString("0A0A0A0A-0000-4000-8000-00000000000A"))
internal val RADIO_B = RadioId(UUID.fromString("0B0B0B0B-0000-4000-8000-00000000000B"))

/** Original-case display name: `Suite::name()`. */
internal fun originalCase(suite: String, name: String, body: suspend CoroutineScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("$suite::$name()") { runBlocking { withTimeout(10.seconds) { body() } } }

/** Native case display name: `WP-215::description`. */
internal fun nativeCase(description: String, body: suspend CoroutineScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-215::$description") { runBlocking { withTimeout(10.seconds) { body() } } }

/** Yields until [condition] holds; the bound only guards against a hang, it never paces the test. */
internal suspend fun yieldUntil(description: String, condition: () -> Boolean) {
    repeat(10_000) {
        if (condition()) return
        yield()
    }
    fail("Condition never held: $description")
}

/** Lets every runnable coroutine on the single test thread run; a bound, not a clock. */
internal suspend fun drainRunnable() = repeat(1_000) { yield() }

internal fun allEnabledPreferences(): NotificationPreferences = NotificationPreferences(
    contactMessagesEnabled = true, channelMessagesEnabled = true, roomMessagesEnabled = true,
    newContactDiscoveredEnabled = true, discoveryContactEnabled = true, discoveryRepeaterEnabled = true,
    discoveryRoomEnabled = true, reactionNotificationsEnabled = true, soundEnabled = true, badgeEnabled = true,
    lowBatteryEnabled = true,
)

/**
 * Ordered log of side effects across all fakes. [cancelCallerAt] cancels the calling coroutine right after
 * that event is recorded (the call itself still succeeds), to model a caller cancelled mid-transaction.
 */
internal class Journal {
    private val lock = Any()
    private val entries = ArrayList<String>()
    @Volatile var cancelCallerAt: String? = null
    val events: List<String> get() = synchronized(lock) { entries.toList() }

    fun note(event: String) {
        synchronized(lock) { entries += event }
    }

    suspend fun record(event: String) {
        note(event)
        if (event == cancelCallerAt) currentCoroutineContext().cancel()
    }
}

/** Records every delivery call; posted notifications stay delivered until removed, like the platform. */
internal class FakeDelivery(private val journal: Journal = Journal()) : NotificationDeliveryPort {
    private val lock = Any()
    var status: NotificationAuthorizationStatus = NotificationAuthorizationStatus.AUTHORIZED
    var grantOnRequest = true
    var postResult: NotificationPostResult = NotificationPostResult.Posted
    val failing = HashSet<String>()
    var cancelling: String? = null
    private val postedRequests = ArrayList<NotificationRequest>()
    private val shown = LinkedHashMap<NotificationId, NotificationPayload?>()
    private val identities = LinkedHashMap<NotificationId, DeliveredPostIdentity>()
    var beforeSnapshotRemoval: (suspend () -> Unit)? = null
    var lastSnapshotOutcomes: List<SnapshotRemovalOutcome> = emptyList()
        private set
    private val badges = ArrayList<Long>()
    private val removed = ArrayList<List<NotificationId>>()
    private var categories: SnapshotList<NotificationCategoryDefinition>? = null
    var listCalls = 0
        private set

    val posted: List<NotificationRequest> get() = synchronized(lock) { postedRequests.toList() }
    val badgeSets: List<Long> get() = synchronized(lock) { badges.toList() }
    val removals: List<List<NotificationId>> get() = synchronized(lock) { removed.toList() }
    val registered: SnapshotList<NotificationCategoryDefinition>? get() = synchronized(lock) { categories }
    val shownIds: List<NotificationId> get() = synchronized(lock) { shown.keys.toList() }

    private fun maybeFail(operation: String) {
        if (operation == cancelling) throw kotlinx.coroutines.CancellationException("cancelled in $operation")
        if (operation in failing) throw IllegalStateException("delivery $operation failed")
    }

    /** A notification some other owner posted (no WP-215 payload). */
    fun showForeign(id: String) = synchronized(lock) { shown[NotificationId(id)] = null }

    fun show(id: String, payload: NotificationPayload) = synchronized(lock) {
        shown[NotificationId(id)] = payload
        identities[NotificationId(id)] = DeliveredPostIdentity(UUID.randomUUID())
    }

    fun forgetPostIdentity(id: String) = synchronized(lock) { identities.remove(NotificationId(id)) }

    override suspend fun authorizationStatus(): NotificationAuthorizationStatus {
        maybeFail("authorizationStatus")
        return status
    }

    override suspend fun requestAuthorization(): Boolean {
        maybeFail("requestAuthorization")
        return grantOnRequest
    }

    override suspend fun registerCategories(categories: SnapshotList<NotificationCategoryDefinition>) {
        maybeFail("registerCategories")
        synchronized(lock) { this.categories = categories }
    }

    override suspend fun post(request: NotificationRequest): NotificationPostResult {
        synchronized(lock) { postedRequests += request }
        journal.record("post")
        maybeFail("post")
        if (postResult == NotificationPostResult.Posted) show(request.id.value, request.payload)
        return postResult
    }

    override suspend fun setBadgeCount(count: Long) {
        synchronized(lock) { badges += count }
        journal.record("setBadgeCount")
        maybeFail("setBadgeCount")
    }

    override suspend fun deliveredNotifications(): SnapshotList<DeliveredNotification> {
        synchronized(lock) { listCalls += 1 }
        maybeFail("deliveredNotifications")
        return synchronized(lock) { shown.map { DeliveredNotification(it.key, it.value, identities[it.key]) }.snapshot() }
    }

    override suspend fun removeDeliveredSnapshot(
        selected: SnapshotList<DeliveredNotification>,
    ): SnapshotList<SnapshotRemovalOutcome> {
        beforeSnapshotRemoval?.invoke()
        journal.record("removeDelivered")
        maybeFail("removeDeliveredSnapshot")
        return synchronized(lock) {
            selected.map {
                when {
                    it.postIdentity == null || identities[it.id] == null -> SnapshotRemovalOutcome.UNKNOWN_IDENTITY
                    identities[it.id] != it.postIdentity -> SnapshotRemovalOutcome.SUPERSEDED
                    else -> {
                        shown.remove(it.id)
                        identities.remove(it.id)
                        SnapshotRemovalOutcome.REMOVED
                    }
                }
            }.also { lastSnapshotOutcomes = it }.snapshot()
        }
    }

    override suspend fun removeDelivered(ids: SnapshotList<NotificationId>) {
        synchronized(lock) { removed += ids.toList() }
        journal.record("removeDelivered")
        maybeFail("removeDelivered")
        synchronized(lock) { ids.forEach { shown.remove(it); identities.remove(it) } }
    }
}

internal class FakePreferences(initial: NotificationPreferences = allEnabledPreferences()) : NotificationPreferencesPort {
    private val values = MutableStateFlow(initial)
    override val preferences: StateFlow<NotificationPreferences> = values
    override suspend fun update(preferences: NotificationPreferences) {
        values.value = preferences
    }
    fun set(transform: (NotificationPreferences) -> NotificationPreferences) {
        values.value = transform(values.value)
    }
}

internal class FakeAppState : AppStateProvider {
    @Volatile var foreground = false
    @Volatile var fail = false
    override suspend fun isInForeground(): Boolean {
        if (fail) throw IllegalStateException("lifecycle unavailable")
        return foreground
    }
}

/** Each call reads [counts] at entry, then waits for the next queued gate, if any, before returning it. */
internal class FakeUnreadCounts : NotificationUnreadCounting {
    @Volatile var counts = UnreadCounts(0, 0, 0)
    @Volatile var fail = false
    @Volatile var cancel = false
    @Volatile var calls = 0
        private set
    val gates = java.util.concurrent.ConcurrentLinkedQueue<CompletableDeferred<Unit>>()
    override suspend fun unreadCounts(): UnreadCounts {
        calls += 1
        val snapshot = counts
        gates.poll()?.await()
        if (cancel) throw kotlinx.coroutines.CancellationException("store scope cancelled")
        if (fail) throw IllegalStateException("store closed")
        return snapshot
    }
}

/** Sleeps by yielding once; time stands still. Records every requested duration. */
internal class ImmediateClock : NotificationClock {
    private val requested = java.util.concurrent.CopyOnWriteArrayList<Duration>()
    val sleeps: List<Duration> get() = requested.toList()
    override fun now(): Instant = NOW
    override suspend fun sleep(duration: Duration) {
        requested += duration
        yield()
    }
    companion object {
        val NOW: Instant = Instant.parse("2026-10-07T12:00:00.123456789Z")
    }
}

/** Sleeps until the test advances; nothing wakes on its own. */
internal class VirtualClock : NotificationClock {
    private val lock = Any()
    private var sequence = 0L
    private val sleepers = TreeMap<Long, CancellableContinuation<Unit>>()
    private val requested = java.util.concurrent.CopyOnWriteArrayList<Duration>()
    val sleeps: List<Duration> get() = requested.toList()
    override fun now(): Instant = Instant.parse("2026-10-07T12:00:00Z")
    override suspend fun sleep(duration: Duration) = suspendCancellableCoroutine { continuation ->
        requested += duration
        val key = synchronized(lock) { sequence++.also { sleepers[it] = continuation } }
        continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(key) } }
    }
    val sleeperCount: Int get() = synchronized(lock) { sleepers.size }
    fun wakeAll() {
        val woken = synchronized(lock) { sleepers.values.toList().also { sleepers.clear() } }
        woken.forEach { it.resume(Unit) }
    }
}

/** Fails the test for any store member a case did not expect; an Error escapes the handler's containment. */
internal inline fun <reified T : Any> unexpectedCalls(): T = Proxy.newProxyInstance(
    T::class.java.classLoader, arrayOf(T::class.java),
) { _, method, _ -> throw AssertionError("Unexpected store call: ${method.name}") } as T

internal class FakeStore(private val journal: Journal = Journal()) : PersistenceStoreProtocol by unexpectedCalls() {
    private val lock = Any()
    val contacts = HashMap<EntityKey, ContactDTO>()
    val channels = HashMap<Pair<RadioId, UByte>, ChannelDTO>()
    val messages = HashMap<EntityKey, MessageDTO>()
    val reactions = HashMap<EntityKey, List<ReactionDTO>>()
    val failing = HashSet<String>()
    var cancelling: String? = null
    private val recorded = ArrayList<Pair<String, Any>>()
    val calls: List<Pair<String, Any>> get() = synchronized(lock) { recorded.toList() }
    val writes: List<Pair<String, Any>> get() = calls.filter { !it.first.startsWith("fetch") }

    private suspend fun record(operation: String, argument: Any) {
        synchronized(lock) { recorded += operation to argument }
        if (!operation.startsWith("fetch")) journal.record(operation)
        if (operation == cancelling) throw kotlinx.coroutines.CancellationException("cancelled in $operation")
        if (operation in failing) throw IllegalStateException("store $operation failed")
    }

    override suspend fun fetchContact(key: EntityKey): ContactDTO? = record("fetchContact", key).let { contacts[key] }
    override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? =
        record("fetchChannel", radioId to index).let { channels[radioId to index] }
    override suspend fun fetchMessage(key: EntityKey): MessageDTO? = record("fetchMessage", key).let { messages[key] }
    override suspend fun fetchReactions(message: EntityKey, limit: Long): SnapshotList<ReactionDTO> =
        record("fetchReactions", message to limit).let { (reactions[message] ?: emptyList()).take(limit.toInt()).snapshot() }
    override suspend fun clearUnreadCount(key: EntityKey) = record("clearUnreadCount", key)
    override suspend fun clearChannelUnreadCount(radioId: RadioId, index: UByte) = record("clearChannelUnreadCount", radioId to index)
    override suspend fun markMessageAsRead(key: EntityKey) = record("markMessageAsRead", key)
}

internal class FakeSender(private val journal: Journal = Journal()) : NotificationMessageSending {
    private val lock = Any()
    var fail = false
    var cancel = false
    private val sent = ArrayList<Pair<String, Any>>()
    val sends: List<Pair<String, Any>> get() = synchronized(lock) { sent.toList() }
    override suspend fun sendDirectMessage(text: String, contact: ContactDTO): MessageDTO {
        synchronized(lock) { sent += text to contact.id }
        journal.record("send")
        if (cancel) throw kotlinx.coroutines.CancellationException("send cancelled")
        if (fail) throw IllegalStateException("not connected")
        return MessageDTO(radioId = contact.radioId, contactID = contact.id, text = text, timestamp = 1u)
    }
    override suspend fun sendChannelMessage(text: String, channelIndex: UByte, radioId: RadioId) {
        synchronized(lock) { sent += text to (radioId to channelIndex) }
        journal.record("send")
        if (cancel) throw kotlinx.coroutines.CancellationException("send cancelled")
        if (fail) throw IllegalStateException("not connected")
    }
}

internal class FakeRooms(private val journal: Journal = Journal()) : NotificationRoomReading {
    var fail = false
    val marked = ArrayList<EntityKey>()
    override suspend fun markAsRead(session: EntityKey) {
        synchronized(marked) { marked += session }
        journal.record("roomMarkAsRead")
        if (fail) throw IllegalStateException("session missing")
    }
}

internal class FakeSync(private val journal: Journal = Journal()) : NotificationConversationsNotifying {
    @Volatile var fail = false
    @Volatile var notifications = 0
        private set
    override fun notifyConversationsChanged() {
        notifications += 1
        journal.note("notifyConversationsChanged")
        if (fail) throw IllegalStateException("observer failed")
    }
}

/** Mock provider mirroring the Swift test's `MockStringProvider`. */
internal class MockStringProvider : NotificationStringProvider {
    override fun discoveryNotificationTitle(type: ContactType): String = "Mock Title"
    override val replyActionTitle: String = "Mock Reply"
    override val sendButtonTitle: String = "Mock Send"
    override val messagePlaceholder: String = "Mock Placeholder"
    override val markAsReadActionTitle: String = "Mock Mark as Read"
    override val lowBatteryTitle: String = "Mock Low Battery"
    override fun lowBatteryBody(deviceName: String, percentage: Long): String = "Mock Battery"
    override val quickReplyFailedTitle: String = "Mock Not Sent"
    override fun quickReplyFailedBody(conversationName: String): String = "Mock Failed"
    override val unknownContactName: String = "Mock Unknown"
    override fun defaultChannelName(index: Long): String = "Localized Channel $index"
    override fun reactionNotificationBody(emoji: String, messagePreview: String): String = "Mock reacted $emoji to $messagePreview"
}

/** One radio session's notification graph built from fakes (the Swift tests' `ServiceContainer.forTesting`). */
internal class NotificationHarness(
    scope: CoroutineScope,
    radioId: RadioId = RADIO_A,
    clock: NotificationClock = ImmediateClock(),
) {
    val journal = Journal()
    val delivery = FakeDelivery(journal)
    val preferences = FakePreferences()
    val appState = FakeAppState()
    val counts = FakeUnreadCounts()
    val service = NotificationService(radioId, delivery, preferences, appState, counts, scope, clock)
    val store = FakeStore(journal)
    val sender = FakeSender(journal)
    val rooms = FakeRooms(journal)
    val sync = FakeSync(journal)
    val handler = NotificationActionHandler(store, sender, service, rooms, sync)

    /** Reads the fake's AUTHORIZED status into the service, as `setup()` does on connect. */
    suspend fun authorize(): NotificationHarness = also { service.checkAuthorizationStatus() }

    fun contact(radioId: RadioId = RADIO_A, name: String = "Alice", muted: Boolean = false, nickname: String? = null): ContactDTO {
        val dto = ContactDTO(
            id = UUID.randomUUID(), radioId = radioId, publicKey = Bytes(ByteArray(32) { 7 }), name = name,
            lastHeardTimestamp = null, nickname = nickname, isMuted = muted,
        )
        store.contacts[EntityKey(radioId, dto.id)] = dto
        return dto
    }
}

internal val ContactDTO.key: EntityKey get() = EntityKey(radioId, id)
