// AndroidOnly: WP-311 In-memory test doubles for the nodes feature seams (store, services, codec, preferences).
package com.meshcoreone.android.feature.nodes.support

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.Announcer
import com.meshcoreone.android.feature.nodes.deps.ContactUriCodec
import com.meshcoreone.android.feature.nodes.deps.EventSubscription
import com.meshcoreone.android.feature.nodes.deps.FirmwareTimeouts
import com.meshcoreone.android.feature.nodes.deps.NodesAdvertEvent
import com.meshcoreone.android.feature.nodes.deps.NodesAdvertisementService
import com.meshcoreone.android.feature.nodes.deps.NodesContactService
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesConnectionSnapshot
import com.meshcoreone.android.feature.nodes.deps.NodesDataStore
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.deps.NodesNotificationCleanup
import com.meshcoreone.android.feature.nodes.deps.NodesSession
import com.meshcoreone.android.feature.nodes.deps.NodesTraceService
import com.meshcoreone.android.feature.nodes.deps.ScannedContact
import com.meshcoreone.android.feature.nodes.deps.StringListPreferences
import com.meshcoreone.android.feature.nodes.deps.SyncProgress
import com.meshcoreone.android.feature.nodes.deps.UserFacingMessages
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.time.Duration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/** Multicast broadcaster: subscribers register when [subscribe] is called. */
internal class TestBroadcaster<T> {
    private val channels = mutableListOf<Channel<T>>()
    val subscriberCount: Int get() = channels.size

    fun subscribe(): EventSubscription<T> {
        val channel = Channel<T>(Channel.UNLIMITED)
        channels += channel
        return object : EventSubscription<T> {
            override val events: Flow<T> = channel.receiveAsFlow()
            override fun close() {
                channels -= channel
                channel.close()
            }
        }
    }

    fun emit(event: T) = channels.toList().forEach { it.trySend(event) }
}

/** In-memory persistence store with injectable failures. */
internal class FakeDataStore : NodesDataStore {
    val contacts = mutableListOf<ContactDTO>()
    val discovered = mutableListOf<DiscoveredNodeDTO>()
    var fetchContactsError: Exception? = null
    var fetchDiscoveredError: Exception? = null
    var fetchContactsGate: (suspend () -> Unit)? = null
    var fetchContactBehavior: (suspend (RadioId, Bytes) -> ContactDTO?)? = null
    val touched = mutableListOf<Pair<Bytes, Instant>>()
    val deletedDiscovered = mutableListOf<EntityKey>()

    override suspend fun fetchContacts(radioId: RadioId): List<ContactDTO> {
        val snapshot = contacts.filter { it.radioId == radioId }
        fetchContactsGate?.invoke()
        fetchContactsError?.let { throw it }
        return snapshot
    }

    override suspend fun fetchContact(key: EntityKey): ContactDTO? = contacts.firstOrNull { it.id == key.id }
    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
        fetchContactBehavior?.let { return it(radioId, publicKey) }
        return contacts.firstOrNull { it.radioId == radioId && it.publicKey == publicKey }
    }

    override suspend fun fetchBlockedContacts(radioId: RadioId): List<ContactDTO> = contacts.filter { it.radioId == radioId && it.isBlocked }
    override suspend fun fetchContactPublicKeys(radioId: RadioId): Set<Bytes> = contacts.filter { it.radioId == radioId }.map { it.publicKey }.toSet()

    override suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean {
        touched += publicKey to date
        return true
    }

    override suspend fun fetchDiscoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
        fetchDiscoveredError?.let { throw it }
        return discovered.filter { it.radioId == radioId }
    }

    override suspend fun deleteDiscoveredNode(key: EntityKey) {
        deletedDiscovered += key
        discovered.removeAll { it.id == key.id }
    }

    override suspend fun clearDiscoveredNodes(radioId: RadioId) {
        discovered.removeAll { it.radioId == radioId }
    }
}

/** Contact service double: every call is recorded; behaviors are swappable lambdas. */
internal class FakeContactService(private val store: FakeDataStore? = null) : NodesContactService {
    val calls = mutableListOf<String>()
    val progress = TestBroadcaster<SyncProgress>()
    var removeContact: suspend (RadioId, Bytes) -> Unit = { _, _ -> }
    var removeLocal: suspend (EntityKey) -> Unit = { key -> store?.contacts?.removeAll { it.id == key.id } }
    var sync: suspend (RadioId) -> Unit = { }
    var setPathBehavior: suspend (Bytes, UByte) -> Unit = { _, _ -> }
    var resetPathBehavior: suspend () -> Unit = { }
    var sendPathDiscoveryBehavior: suspend () -> MessageSentInfo = { sentInfo(0u) }
    var addBehavior: suspend (RadioId, ContactFrame) -> Unit = { radioId, frame ->
        store?.contacts?.add(ContactDTO.fromFrame(radioId, frame).copy(lastHeardTimestamp = null))
    }
    var preferencesBehavior: suspend (EntityKey, String?, Boolean?) -> Unit = { key, nickname, isBlocked ->
        store?.let { data ->
            val index = data.contacts.indexOfFirst { it.id == key.id }
            if (index >= 0) {
                val existing = data.contacts[index]
                data.contacts[index] = existing.copy(nickname = nickname ?: existing.nickname, isBlocked = isBlocked ?: existing.isBlocked)
            }
        }
    }
    var favoriteBehavior: suspend (EntityKey, Boolean) -> Unit = { key, isFavorite ->
        store?.let { data ->
            val index = data.contacts.indexOfFirst { it.id == key.id }
            if (index >= 0) data.contacts[index] = data.contacts[index].copy(isFavorite = isFavorite)
        }
    }
    var shareBehavior: suspend () -> Unit = { }
    var clearMessagesBehavior: suspend () -> Unit = { }
    val avatars = mutableListOf<Pair<EntityKey, Bytes?>>()
    val avatarIdentities = mutableListOf<Pair<EntityKey, Bytes>>()
    var avatarBehavior: suspend (EntityKey, Bytes?) -> Unit = { key, bytes ->
        store?.let { data ->
            val index = data.contacts.indexOfFirst { it.id == key.id && it.radioId == key.radioId }
            if (index >= 0) data.contacts[index] = data.contacts[index].copy(avatarImageData = bytes)
        }
    }
    var getContactBehavior: suspend (RadioId, Bytes) -> ContactDTO? = { radioId, key -> store?.fetchContact(radioId, key) }
    val setPaths = mutableListOf<Pair<Bytes, UByte>>()

    override fun syncProgressEvents(): EventSubscription<SyncProgress> = progress.subscribe()
    override suspend fun syncContactsForRefresh(radioId: RadioId) { calls += "sync"; sync(radioId) }
    override suspend fun getContact(radioId: RadioId, publicKey: Bytes): ContactDTO? { calls += "getContact"; return getContactBehavior(radioId, publicKey) }
    override suspend fun addOrUpdateContact(radioId: RadioId, contact: ContactFrame) { calls += "add:${contact.name}"; addBehavior(radioId, contact) }
    override suspend fun removeContact(radioId: RadioId, publicKey: Bytes) { calls += "remove"; removeContact.invoke(radioId, publicKey) }
    override suspend fun removeLocalContact(contact: EntityKey, publicKey: Bytes) { calls += "removeLocal"; removeLocal(contact) }
    override suspend fun clearContactMessages(contact: EntityKey) { calls += "clearMessages"; clearMessagesBehavior() }
    override suspend fun resetPath(radioId: RadioId, publicKey: Bytes) { calls += "resetPath"; resetPathBehavior() }
    override suspend fun sendPathDiscovery(radioId: RadioId, publicKey: Bytes): MessageSentInfo { calls += "discover"; return sendPathDiscoveryBehavior() }
    override suspend fun setPath(radioId: RadioId, publicKey: Bytes, path: Bytes, pathLength: UByte) {
        calls += "setPath"
        setPaths += path to pathLength
        setPathBehavior(path, pathLength)
    }
    override suspend fun shareContact(publicKey: Bytes) { calls += "share"; shareBehavior() }
    override suspend fun updateContactPreferences(contact: EntityKey, nickname: String?, isBlocked: Boolean?) {
        calls += "prefs:nickname=$nickname,isBlocked=$isBlocked"
        preferencesBehavior(contact, nickname, isBlocked)
    }
    override suspend fun updateContactAvatar(contact: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?) {
        calls += "avatar:${imageData?.size}"
        val stored = store?.contacts?.firstOrNull { it.id == contact.id && it.radioId == contact.radioId }
        if (stored == null || stored.publicKey != expectedPublicKey) throw NodesContactFailure.ContactNotFound()
        avatars += contact to imageData
        avatarIdentities += contact to expectedPublicKey
        avatarBehavior(contact, imageData)
    }
    override suspend fun setContactFavorite(contact: EntityKey, isFavorite: Boolean) { calls += "favorite:$isFavorite"; favoriteBehavior(contact, isFavorite) }

    companion object {
        fun sentInfo(suggestedTimeoutMs: UInt) = MessageSentInfo(0u, Bytes.of(0, 0, 0, 0), suggestedTimeoutMs)
    }
}

internal class FakeAdvertisementService : NodesAdvertisementService {
    val broadcaster = TestBroadcaster<NodesAdvertEvent>()
    val syncingFlags = mutableListOf<Boolean>()
    var syncingBehavior: suspend (Boolean) -> Unit = {}
    override fun events(): EventSubscription<NodesAdvertEvent> = broadcaster.subscribe()
    override suspend fun setSyncingContacts(isSyncing: Boolean) {
        syncingFlags += isSyncing
        syncingBehavior(isSyncing)
    }
}

internal class FakeTraceService(var behavior: suspend (UInt, UByte, Bytes) -> MessageSentInfo = { _, _, _ -> FakeContactService.sentInfo(0u) }) :
    NodesTraceService {
    val sent = mutableListOf<Triple<UInt, UByte, Bytes>>()
    override suspend fun sendTrace(tag: UInt, flags: UByte, path: Bytes): MessageSentInfo {
        sent += Triple(tag, flags, path)
        return behavior(tag, flags, path)
    }
}

internal class FakeNotificationCleanup : NodesNotificationCleanup {
    val calls = mutableListOf<String>()
    override suspend fun removeDeliveredNotifications(contactId: UUID) { calls += "remove:$contactId" }
    override suspend fun updateBadgeCount() { calls += "badge" }
}

/** Mutable app-graph facts; null services model a disconnected radio. */
internal class FakeSession : NodesSession {
    private val connection = MutableStateFlow(NodesConnectionSnapshot(null, null))
    override fun connectionSnapshots() = connection
    var state: DeviceConnectionState = DeviceConnectionState.READY
        set(value) {
            field = value
            publishConnection()
        }
    var device: DeviceDTO? = null
        set(value) {
            field = value
            publishConnection()
        }
    var radioId: RadioId? = null
        set(value) {
            field = value
            publishConnection()
        }
    var offlineStore: NodesDataStore? = null
    var servicesStore: NodesDataStore? = null
    var contacts: NodesContactService? = null
        set(value) {
            field = value
            publishConnection()
        }
    var adverts: NodesAdvertisementService? = null
    var trace: NodesTraceService? = null
    var notifications: NodesNotificationCleanup? = null
    var onContactServiceRead: () -> Unit = {}
    var onAdvertisementServiceRead: () -> Unit = {}
    var onConnectionStateRead: () -> Unit = {}
    var onRadioIdRead: () -> Unit = {}
    var onDeviceRead: () -> Unit = {}
    var onServicesStoreRead: () -> Unit = {}

    private fun publishConnection() {
        connection.value = NodesConnectionSnapshot(radioId, contacts, device, state)
    }

    override fun connectionState() = state.also { onConnectionStateRead() }
    override fun connectionIdentity() = contacts
    override fun connectedDevice() = device.also { onDeviceRead() }
    override fun currentRadioId() = radioId.also { onRadioIdRead() }
    override fun offlineDataStore() = offlineStore
    override fun servicesDataStore() = servicesStore.also { onServicesStoreRead() }
    override fun contactService() = contacts.also { onContactServiceRead() }
    override fun advertisementService() = adverts.also { onAdvertisementServiceRead() }
    override fun traceService() = trace
    override fun notificationCleanup() = notifications
}

/** Test-only codec: `test-contact:<name>|<hex>|<type raw>`; the real encode/parse are WP-209/WP-405. */
internal class FakeUriCodec : ContactUriCodec {
    override fun exportContactUri(name: String, publicKey: Bytes, type: ContactType): String =
        "test-contact:$name|${publicKey.hexString}|${type.rawValue}"

    override fun parseContactUri(text: String): ScannedContact? {
        val body = text.removePrefix("test-contact:").takeIf { it != text } ?: return null
        val parts = body.split("|")
        if (parts.size != 3) return null
        val key = Bytes.parseHex(parts[1]) ?: return null
        val type = parts[2].toUByteOrNull()?.let(ContactType::fromRawValue) ?: return null
        return ScannedContact(parts[0], key, type)
    }
}

internal class FixedTimeouts(
    var discoverySeconds: Double = 20.0,
    var retransmit: Duration? = null,
    var zeroHop: Double = 5.0,
) : FirmwareTimeouts {
    override fun pathDiscoverySeconds(suggestedTimeoutMs: UInt) = discoverySeconds
    override fun pathDiscoveryRetransmitInterval(suggestedTimeoutMs: UInt) = retransmit
    override fun zeroHopSeconds(suggestedTimeoutMs: UInt) = zeroHop
}

/** `UserDefaults(suiteName:)` stand-in; share one instance to model persistence across view models. */
internal class MemoryPreferences : StringListPreferences {
    val values = mutableMapOf<String, List<String>>()
    override fun stringList(key: String): List<String>? = values[key]
    override fun setStringList(key: String, value: List<String>) { values[key] = value }
}

internal class RecordingAnnouncer : Announcer {
    val messages = mutableListOf<NodesMessage>()
    override fun announce(message: NodesMessage) { messages += message }
}

internal class Harness(
    val clock: ManualClock = ManualClock(),
    val preferences: MemoryPreferences = MemoryPreferences(),
    val locale: Locale = Locale.US,
) {
    val session = FakeSession()
    val store = FakeDataStore()
    val contactService = FakeContactService(store)
    val adverts = FakeAdvertisementService()
    val trace = FakeTraceService()
    val notifications = FakeNotificationCleanup()
    val announcer = RecordingAnnouncer()
    val timeouts = FixedTimeouts()
    val codec = FakeUriCodec()
    val dependencies = NodesFeatureDependencies(
        session = session, uriCodec = codec, timeouts = timeouts,
        messages = UserFacingMessages { error -> "failed: ${error.message}" },
        announcer = announcer, preferences = preferences, clock = clock, locale = { locale },
    )

    /** Wires a connected radio: every provider returns its double. */
    fun connect(radio: RadioId = Fixtures.radio(), device: DeviceDTO = Fixtures.device(radio)): Harness = apply {
        session.radioId = radio
        session.device = device
        session.offlineStore = store
        session.servicesStore = store
        session.contacts = contactService
        session.adverts = adverts
        session.trace = trace
        session.notifications = notifications
    }
}
