// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockMeshCoreSession.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Mocks/MockPersistenceStore.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.ContactAvatarUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactFavoriteUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactBlockTransition
import com.meshcoreone.android.core.contracts.domain.ContactLocalUpdate
import com.meshcoreone.android.core.contracts.domain.ContactLocalUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.RoomPersisting
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ContactFetchResult
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import java.lang.reflect.Proxy
import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.DynamicTest

private val contactsCaseTimeout: Duration = 20.seconds

/** A ported Swift case named `Suite::name(signature)`, run on a real-time `runBlocking` loop. */
internal fun contactsOriginal(
    suite: String,
    name: String,
    signature: String = "()",
    body: suspend CoroutineScope.() -> Unit,
): DynamicTest = DynamicTest.dynamicTest("$suite::$name$signature") {
    runBlocking { withTimeout(contactsCaseTimeout) { body() } }
}

/** One argument of a parameterized Swift case; the argument is appended in brackets. */
internal fun contactsOriginalArgument(
    suite: String,
    name: String,
    signature: String,
    argument: Any,
    body: suspend CoroutineScope.() -> Unit,
): DynamicTest = DynamicTest.dynamicTest("$suite::$name$signature [$argument]") {
    runBlocking { withTimeout(contactsCaseTimeout) { body() } }
}

/** An Android-native WP-209 case (cancellation, concurrency, boundary coverage). */
internal fun contactsNative(name: String, body: suspend CoroutineScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-209::$name") { runBlocking { withTimeout(contactsCaseTimeout) { body() } } }

/** A persistence role whose every member fails, so a fake only answers what a test exercises. */
internal inline fun <reified T : Any> contactsRejectingRole(): T = Proxy.newProxyInstance(
    T::class.java.classLoader,
    arrayOf(T::class.java),
) { _, method, _ ->
    when (method.name) {
        "toString" -> "Rejecting ${T::class.java.simpleName}"
        "hashCode" -> System.identityHashCode(T::class.java)
        "equals" -> false
        else -> throw UnsupportedOperationException("Unexpected ${T::class.java.simpleName}.${method.name}")
    }
} as T

internal fun contactsKey(byte: Int): Bytes = Bytes(ByteArray(32) { byte.toByte() })

internal fun contactsDevice(radioId: RadioId, publicKey: Bytes = contactsKey(0x01)): DeviceDTO =
    DeviceDTO(radioId = radioId, publicKey = publicKey, nodeName = "TestDevice")

/**
 * In-memory store answering the contact and device roles with the real repository's semantics:
 * frame upserts go through [ContactDTO.updating]/[ContactDTO.fromFrame] (raw type byte preserved),
 * and deleting a contact cascades its direct messages.
 */
internal class ContactsFakeStore(
    devices: List<DeviceDTO> = emptyList(),
) : DevicePersisting by contactsRejectingRole(),
    ContactPersisting by contactsRejectingRole(),
    RoomPersisting by contactsRejectingRole() {
    private val lock = Any()
    private val deviceRows = devices.associateBy { it.radioId }.toMutableMap()
    private val contactRows = linkedMapOf<UUID, ContactDTO>()
    private val messageRows = mutableListOf<MessageDTO>()
    private val sessionRows = mutableListOf<RemoteNodeSessionDTO>()
    private val deletedContacts = mutableListOf<UUID>()
    private val deletedMessagesFor = mutableListOf<UUID>()
    private val deletedChannelSenders = mutableListOf<Pair<String, RadioId>>()

    var fetchDeviceFailure: Exception? = null
    var fetchContactByKeyFailure: Exception? = null
    var fetchSessionsFailure: Exception? = null

    val contacts: Map<UUID, ContactDTO> get() = synchronized(lock) { LinkedHashMap(contactRows) }
    val messages: List<MessageDTO> get() = synchronized(lock) { messageRows.toList() }
    val deletedContactIDs: List<UUID> get() = synchronized(lock) { deletedContacts.toList() }
    val deletedMessagesForContactIDs: List<UUID> get() = synchronized(lock) { deletedMessagesFor.toList() }
    val deletedChannelMessageSenders: List<Pair<String, RadioId>> get() = synchronized(lock) { deletedChannelSenders.toList() }

    fun seed(contact: ContactDTO) = synchronized(lock) { contactRows[contact.id] = contact }
    fun seed(message: MessageDTO) = synchronized(lock) { messageRows += message }
    fun seed(session: RemoteNodeSessionDTO) = synchronized(lock) { sessionRows += session }
    fun messagesFor(contactId: UUID): List<MessageDTO> = synchronized(lock) { messageRows.filter { it.contactID == contactId } }

    override suspend fun fetchDevice(radioId: RadioId): DeviceDTO? {
        fetchDeviceFailure?.let { throw it }
        return synchronized(lock) { deviceRows[radioId] }
    }

    override suspend fun fetchContacts(radioId: RadioId): SnapshotList<ContactDTO> =
        synchronized(lock) { contactRows.values.filter { it.radioId == radioId }.snapshot() }

    override suspend fun fetchConversations(radioId: RadioId): SnapshotList<ContactDTO> =
        synchronized(lock) { contactRows.values.filter { it.radioId == radioId && it.lastMessageDate != null }.snapshot() }

    override suspend fun fetchContact(key: EntityKey): ContactDTO? {
        fetchContactByKeyFailure?.let { throw it }
        return synchronized(lock) { contactRows[key.id]?.takeIf { it.radioId == key.radioId } }
    }

    override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? =
        synchronized(lock) { contactRows.values.firstOrNull { it.radioId == radioId && it.publicKey == publicKey } }

    override suspend fun saveContact(radioId: RadioId, frame: ContactFrame): ContactSaveResult = synchronized(lock) {
        upsert(radioId, frame)
    }

    override suspend fun saveContactPreservingNewer(
        radioId: RadioId, frame: ContactFrame, expectedFrame: ContactFrame?,
    ): ContactSaveResult? = synchronized(lock) {
        val existing = contactRows.values.firstOrNull { it.radioId == radioId && it.publicKey == frame.publicKey }
        if (existing != null && (existing.lastModified > frame.lastModified ||
                (existing.lastModified == frame.lastModified && existing.toContactFrame() != expectedFrame &&
                    existing.toContactFrame() != frame))) null
        else upsert(radioId, frame)
    }

    override suspend fun saveContact(dto: ContactDTO) = synchronized(lock) { contactRows[dto.id] = dto }

    override suspend fun updateContactAvatar(
        key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
    ): ContactAvatarUpdateResult = synchronized(lock) {
        val row = contactRows[key.id]?.takeIf { it.radioId == key.radioId }
            ?: return@synchronized ContactAvatarUpdateResult.CONTACT_NOT_FOUND
        if (row.publicKey != expectedPublicKey) return@synchronized ContactAvatarUpdateResult.IDENTITY_MISMATCH
        contactRows[key.id] = row.withAvatar(imageData)
        ContactAvatarUpdateResult.UPDATED
    }

    override suspend fun batchSaveContacts(radioId: RadioId, frames: SnapshotList<ContactFrame>): Long = synchronized(lock) {
        frames.forEach { upsert(radioId, it) }
        frames.size.toLong()
    }

    override suspend fun updateContactFavorite(
        key: EntityKey, expectedPublicKey: Bytes, isFavorite: Boolean,
    ): ContactFavoriteUpdateResult = synchronized(lock) {
        val row = contactRows[key.id]?.takeIf { it.radioId == key.radioId }
            ?: return@synchronized ContactFavoriteUpdateResult.CONTACT_NOT_FOUND
        if (row.publicKey != expectedPublicKey) return@synchronized ContactFavoriteUpdateResult.IDENTITY_MISMATCH
        contactRows[key.id] = row.copy(
            flags = ((row.flags.toInt() and 0xFE) or if (isFavorite) 1 else 0).toUByte(),
            isFavorite = isFavorite,
        )
        ContactFavoriteUpdateResult.UPDATED
    }

    override suspend fun updateContactLocalFields(
        key: EntityKey, expectedPublicKey: Bytes, update: ContactLocalUpdate,
    ): ContactLocalUpdateResult = synchronized(lock) {
        val row = contactRows[key.id]?.takeIf { it.radioId == key.radioId }
            ?: return@synchronized ContactLocalUpdateResult.ContactNotFound
        if (row.publicKey != expectedPublicKey) return@synchronized ContactLocalUpdateResult.IdentityMismatch
        var transition = ContactBlockTransition.NONE
        contactRows[key.id] = when (update) {
            is ContactLocalUpdate.Preferences -> {
                transition = when {
                    update.isBlocked == true && !row.isBlocked -> ContactBlockTransition.BLOCKED
                    update.isBlocked == false && row.isBlocked -> ContactBlockTransition.UNBLOCKED
                    else -> ContactBlockTransition.NONE
                }
                row.copy(
                    nickname = if (update.nickname == null) row.nickname else update.nickname.ifEmpty { null },
                    isBlocked = update.isBlocked ?: row.isBlocked,
                    isFavorite = update.isFavorite ?: row.isFavorite,
                    unreadCount = if (transition == ContactBlockTransition.BLOCKED) 0 else row.unreadCount,
                )
            }
            is ContactLocalUpdate.OCVSettings -> row.copy(
                ocvPreset = update.preset, customOCVArrayString = update.customArray,
            )
            is ContactLocalUpdate.TelemetryPermissions ->
                row.copy(flags = ((row.flags.toInt() and 0xF1) or if (update.granted) 0x0E else 0).toUByte())
        }
        ContactLocalUpdateResult.Updated(transition)
    }

    private fun upsert(radioId: RadioId, frame: ContactFrame): ContactSaveResult {
        val existing = contactRows.values.firstOrNull { it.radioId == radioId && it.publicKey == frame.publicKey }
        val dto = existing?.updating(frame) ?: ContactDTO.fromFrame(radioId, frame)
        contactRows[dto.id] = dto
        return ContactSaveResult(dto.id, existing == null)
    }

    override suspend fun deleteContact(key: EntityKey) = synchronized(lock) {
        messageRows.removeAll { it.contactID == key.id }
        if (contactRows[key.id]?.radioId == key.radioId) contactRows.remove(key.id)
        deletedContacts += key.id
    }

    override suspend fun deleteMessagesForContact(key: EntityKey) = synchronized(lock) {
        deletedMessagesFor += key.id
        messageRows.removeAll { it.contactID == key.id }
        Unit
    }

    override suspend fun clearUnreadCount(key: EntityKey) = synchronized(lock) {
        contactRows[key.id]?.let { contactRows[key.id] = it.copy(unreadCount = 0) }
        Unit
    }

    override suspend fun clearUnreadMentionCount(key: EntityKey) = synchronized(lock) {
        contactRows[key.id]?.let { contactRows[key.id] = it.copy(unreadMentionCount = 0) }
        Unit
    }

    override suspend fun deleteChannelMessages(senderName: String, radioId: RadioId) = synchronized(lock) {
        deletedChannelSenders += senderName to radioId
        Unit
    }

    override suspend fun fetchRemoteNodeSessions(radioId: RadioId): SnapshotList<RemoteNodeSessionDTO> {
        fetchSessionsFailure?.let { throw it }
        return synchronized(lock) { sessionRows.filter { it.radioId == radioId }.snapshot() }
    }
}

/** Contact-operation fake for `MeshCoreSessionProtocol`, ported from `MockMeshCoreSession`. */
internal class ContactsFakeSession : ContactSessionOps {
    data class FlagsInvocation(val contact: MeshContact, val flags: ContactFlags)

    private val lock = Any()
    private var contactsStub: List<MeshContact> = emptyList()
    private var reportedTotalStub: Long? = null
    private var reportsNoTotal = false
    private val fetches = mutableListOf<Instant?>()
    private val added = mutableListOf<MeshContact>()
    private val removed = mutableListOf<Bytes>()
    private val resets = mutableListOf<Bytes>()
    private val discoveries = mutableListOf<Bytes>()
    private val shares = mutableListOf<Bytes>()
    private val imports = mutableListOf<Bytes>()
    private val flagChanges = mutableListOf<FlagsInvocation>()
    private var holdRequested = false
    private val holdStarted = CompletableDeferred<Unit>()
    private var holdGate: CompletableDeferred<Unit>? = null

    var getContactsFailure: Exception? = null
    var addContactFailure: Exception? = null
    var removeContactFailure: Exception? = null
    var resetPathFailure: Exception? = null
    var sendPathDiscoveryFailure: Exception? = null
    var shareContactFailure: Exception? = null
    var exportContactFailure: Exception? = null
    var importContactFailure: Exception? = null
    var changeContactFlagsFailures: Map<Bytes, Exception> = emptyMap()
    var exportedURI: String = "meshcore://exported"
    var sentInfo: MessageSentInfo = MessageSentInfo(0u, Bytes.of(1, 2, 3, 4), 5000u)

    val getContactsInvocations: List<Instant?> get() = synchronized(lock) { fetches.toList() }
    val addContactInvocations: List<MeshContact> get() = synchronized(lock) { added.toList() }
    val removeContactPublicKeys: List<Bytes> get() = synchronized(lock) { removed.toList() }
    val resetPathPublicKeys: List<Bytes> get() = synchronized(lock) { resets.toList() }
    val sendPathDiscoveryDestinations: List<Bytes> get() = synchronized(lock) { discoveries.toList() }
    val shareContactPublicKeys: List<Bytes> get() = synchronized(lock) { shares.toList() }
    val importContactInvocations: List<Bytes> get() = synchronized(lock) { imports.toList() }
    val changeContactFlagsInvocations: List<FlagsInvocation> get() = synchronized(lock) { flagChanges.toList() }

    fun setStubbedContacts(contacts: List<MeshContact>) = synchronized(lock) { contactsStub = contacts.toList() }
    fun setStubbedReportedTotal(total: Long?) = synchronized(lock) { reportedTotalStub = total }
    fun setStubbedReportsNoTotal(value: Boolean) = synchronized(lock) { reportsNoTotal = value }

    /** The next `getContactsReportingTotal` records its invocation, then parks until released. */
    fun holdNextGetContacts() = synchronized(lock) { holdRequested = true }
    suspend fun waitForGetContactsStart() = holdStarted.await()
    fun isGetContactsHeld(): Boolean = synchronized(lock) { holdGate?.isCompleted == false }
    fun releaseGetContacts() = synchronized(lock) { holdGate?.complete(Unit) }

    override suspend fun getContacts(since: Instant?): List<MeshContact> = getContactsReportingTotal(since).contacts

    override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult {
        val gate = synchronized(lock) {
            fetches += since
            if (holdRequested) {
                holdRequested = false
                CompletableDeferred<Unit>().also { holdGate = it; holdStarted.complete(Unit) }
            } else {
                null
            }
        }
        gate?.await()
        getContactsFailure?.let { throw it }
        return synchronized(lock) {
            ContactFetchResult(contactsStub, if (reportsNoTotal) null else reportedTotalStub ?: contactsStub.size.toLong())
        }
    }

    override suspend fun getContact(publicKey: Bytes): MeshContact? = null

    override suspend fun addContact(contact: MeshContact) {
        synchronized(lock) { added += contact }
        addContactFailure?.let { throw it }
    }

    override suspend fun removeContact(publicKey: Bytes) {
        synchronized(lock) { removed += publicKey }
        removeContactFailure?.let { throw it }
    }

    override suspend fun resetPath(publicKey: Bytes) {
        synchronized(lock) { resets += publicKey }
        resetPathFailure?.let { throw it }
    }

    override suspend fun sendPathDiscovery(destination: Bytes): MessageSentInfo {
        synchronized(lock) { discoveries += destination }
        sendPathDiscoveryFailure?.let { throw it }
        return sentInfo
    }

    override suspend fun shareContact(publicKey: Bytes) {
        synchronized(lock) { shares += publicKey }
        shareContactFailure?.let { throw it }
    }

    override suspend fun exportContact(publicKey: Bytes?): String {
        exportContactFailure?.let { throw it }
        return exportedURI
    }

    override suspend fun importContact(cardData: Bytes) {
        synchronized(lock) { imports += cardData }
        importContactFailure?.let { throw it }
    }

    override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) {
        synchronized(lock) { flagChanges += FlagsInvocation(contact, flags) }
        changeContactFlagsFailures[contact.publicKey]?.let { throw it }
    }
}

internal class ContactsFakePreferences : ContactPreferenceFlags {
    private val values = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    override fun bool(key: String): Boolean = values[key] ?: false
    override fun set(key: String, value: Boolean) { values[key] = value }
}

/** Records every cleanup invocation (Swift `CleanupTracker` + `RecordingCleanupCoordinator`). */
internal class ContactsRecordingCleanup : ContactCleanupHandling {
    data class Invocation(val contact: EntityKey, val reason: ContactCleanupReason, val publicKey: Bytes)

    private val lock = Any()
    private val recorded = mutableListOf<Invocation>()
    val invocations: List<Invocation> get() = synchronized(lock) { recorded.toList() }

    override suspend fun handleCleanup(contact: EntityKey, reason: ContactCleanupReason, publicKey: Bytes) {
        synchronized(lock) { recorded += Invocation(contact, reason, publicKey) }
    }
}

internal enum class ContactsAdvertOutcome { SYNCED, BUSY, FAILED }

/**
 * Stand-in for the unported `SyncCoordinator`, modelling exactly its advert-claim protocol from
 * `SyncCoordinator+Sync.swift`: an advert delta holds `isSyncInProgress && advertContactSyncActive`;
 * `claimManualContactSync` waits (bounded) for that claim to clear and sets the manual flag in the
 * same critical section; an advert round returns busy while either flag is set. Watermark
 * readiness and phase bookkeeping are out of scope. It also records UI notifications in order.
 */
internal class ContactsFakeSyncCoordinator(
    private val waitTimeout: Duration = 200.seconds,
) : ContactSyncCoordinating {
    private val lock = Any()
    private var isSyncInProgress = false
    private var advertContactSyncActive = false
    private var manualActive = false
    private val waiters = mutableListOf<CompletableDeferred<Unit>>()
    private val recorded = mutableListOf<String>()

    val manualContactSyncActive: Boolean get() = synchronized(lock) { manualActive }
    val notifications: List<String> get() = synchronized(lock) { recorded.toList() }

    override suspend fun claimManualContactSync() {
        val deadline = System.nanoTime() + waitTimeout.inWholeNanoseconds
        while (true) {
            val waiter = synchronized(lock) {
                if (!(isSyncInProgress && advertContactSyncActive)) {
                    manualActive = true
                    recorded += "manual:true"
                    return
                }
                CompletableDeferred<Unit>().also { waiters += it }
            }
            val remaining = deadline - System.nanoTime()
            val released = if (remaining <= 0) null else withTimeoutOrNull(remaining.nanoseconds) { waiter.await() }
            if (released == null) {
                synchronized(lock) { waiters.remove(waiter) }
                throw IllegalStateException("Timed out waiting for advert contact sync to release claim")
            }
        }
    }

    override suspend fun setManualContactSyncActive(active: Boolean) {
        // A cancellation point, as a Mutex-confined Kotlin coordinator would have.
        kotlinx.coroutines.yield()
        synchronized(lock) {
            manualActive = active
            recorded += "manual:$active"
        }
    }

    override suspend fun notifyContactsChanged() = synchronized(lock) { recorded += "contactsChanged" }

    override suspend fun notifyConversationsChanged() = synchronized(lock) { recorded += "conversationsChanged" }

    override suspend fun refreshBlockedContactsCache(radioId: RadioId, dataStore: ContactPersisting) =
        synchronized(lock) { recorded += "refreshBlocked:${radioId.value}" }

    suspend fun performAdvertContactSync(radioId: RadioId, contactService: ContactServiceProtocol): ContactsAdvertOutcome {
        synchronized(lock) {
            if (manualActive || isSyncInProgress) return ContactsAdvertOutcome.BUSY
            isSyncInProgress = true
            advertContactSyncActive = true
        }
        return try {
            contactService.syncContacts(radioId, Instant.ofEpochSecond(1_704_067_200))
            ContactsAdvertOutcome.SYNCED
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ContactsAdvertOutcome.FAILED
        } finally {
            val released = synchronized(lock) {
                isSyncInProgress = false
                advertContactSyncActive = false
                waiters.toList().also { waiters.clear() }
            }
            released.forEach { it.complete(Unit) }
        }
    }
}

internal fun contactsService(
    session: ContactSessionOps = ContactsFakeSession(),
    store: ContactsFakeStore = ContactsFakeStore(),
    syncCoordinator: ContactSyncCoordinating? = null,
    cleanupCoordinator: ContactCleanupHandling? = null,
    preferences: ContactPreferenceFlags = ContactsFakePreferences(),
    clock: java.time.Clock = java.time.Clock.fixed(Instant.ofEpochSecond(1_700_000_500), java.time.ZoneOffset.UTC),
    logger: ContactServiceLogger = ContactServiceLogger.NONE,
): ContactService = ContactService(session, store, store, syncCoordinator, cleanupCoordinator, preferences, clock, logger)
