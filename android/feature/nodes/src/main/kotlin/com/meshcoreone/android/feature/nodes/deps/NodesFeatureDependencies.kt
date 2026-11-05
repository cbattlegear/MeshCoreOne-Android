// AndroidOnly: WP-311 Feature-owned seams over services/persistence/platform the nodes logic consumes (feature modules cannot see core:services).
package com.meshcoreone.android.feature.nodes.deps

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.PathInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * A registered event stream: registration happens when the subscription is created, so events
 * yielded after the call are never missed (the Swift `events()` contract). [events] has one consumer.
 */
interface EventSubscription<out T> : AutoCloseable {
    val events: Flow<T>
}

/** Subset of the persistence store the nodes screens read (`ContactPersisting`/`DiscoveredNodePersisting`). */
interface NodesDataStore {
    suspend fun fetchContacts(radioId: RadioId): List<ContactDTO>
    suspend fun fetchContact(key: EntityKey): ContactDTO?
    suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO?
    suspend fun fetchBlockedContacts(radioId: RadioId): List<ContactDTO>
    suspend fun fetchContactPublicKeys(radioId: RadioId): Set<Bytes>
    suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean
    suspend fun fetchDiscoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO>
    suspend fun deleteDiscoveredNode(key: EntityKey)
    suspend fun clearDiscoveredNodes(radioId: RadioId)
}

/** Contact sync progress (`ContactServiceEvent.syncProgress`). */
data class SyncProgress(val received: Long, val total: Long)

/**
 * The `ContactService` operations used here, with the WP-209 signatures. Failures the screens
 * branch on are thrown as [NodesContactFailure]; anything else is reported through
 * [UserFacingMessages].
 */
interface NodesContactService {
    fun syncProgressEvents(): EventSubscription<SyncProgress>
    suspend fun syncContactsForRefresh(radioId: RadioId)
    suspend fun getContact(radioId: RadioId, publicKey: Bytes): ContactDTO?
    suspend fun addOrUpdateContact(radioId: RadioId, contact: ContactFrame)
    suspend fun removeContact(radioId: RadioId, publicKey: Bytes)
    suspend fun removeLocalContact(contact: EntityKey, publicKey: Bytes)
    suspend fun clearContactMessages(contact: EntityKey)
    suspend fun resetPath(radioId: RadioId, publicKey: Bytes)
    suspend fun sendPathDiscovery(radioId: RadioId, publicKey: Bytes): MessageSentInfo
    suspend fun setPath(radioId: RadioId, publicKey: Bytes, path: Bytes, pathLength: UByte)
    suspend fun shareContact(publicKey: Bytes)
    suspend fun updateContactPreferences(contact: EntityKey, nickname: String? = null, isBlocked: Boolean? = null)
    suspend fun updateContactAvatar(contact: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?)
    suspend fun setContactFavorite(contact: EntityKey, isFavorite: Boolean)
}

/** `ContactServiceError` cases the nodes screens branch on. */
sealed class NodesContactFailure(message: String) : Exception(message) {
    class ContactNotFound : NodesContactFailure("Contact not found on device")
    class ContactTableFull : NodesContactFailure("Device node list is full")
    class ShareContactUnavailable : NodesContactFailure("Unable to share node")
}

/** `AdvertisementEvent` cases the nodes screens observe. */
sealed interface NodesAdvertEvent {
    data class PathDiscoveryResponse(val path: PathInfo) : NodesAdvertEvent
    data class TraceSnrObserved(val tag: UInt, val localSnr: Double, val remoteSnr: Double?) : NodesAdvertEvent
}

interface NodesAdvertisementService {
    fun events(): EventSubscription<NodesAdvertEvent>
    suspend fun setSyncingContacts(isSyncing: Boolean)
}

/** `BinaryProtocolService.sendTrace` for the zero-hop ping. */
interface NodesTraceService {
    suspend fun sendTrace(tag: UInt, flags: UByte, path: Bytes): MessageSentInfo
}

/** Notification cleanup after clearing a conversation (WP-215/WP-401 own the real service). */
interface NodesNotificationCleanup {
    suspend fun removeDeliveredNotifications(contactId: UUID)
    suspend fun updateBadgeCount()
}

/** Radio-owned list identity and graph-owned interaction lifetime, published together to mounted consumers. */
data class NodesConnectionSnapshot(
    val radioId: RadioId?,
    val identity: Any?,
    val device: DeviceDTO? = null,
    val state: DeviceConnectionState = DeviceConnectionState.DISCONNECTED,
)

/**
 * Live connection facts from the app graph (`AppState`). Every provider is re-read at its point of
 * use, so a disconnect between actions is observed, never a stale snapshot; `null` services mean
 * disconnected.
 */
interface NodesSession {
    /** Capture before suspending RF work; production verifies the exact graph identity. */
    fun captureSessionValidity(): () -> Boolean {
        val device = connectedDevice()
        return { device != null && connectedDevice()?.id == device.id && connectionState() == DeviceConnectionState.READY }
    }
    /** Capture the owner of a pending contact link, including the offline cached-contact case. */
    fun captureContactNavigationValidity(generation: Long): () -> Boolean {
        val radio = currentRadioId()
        val device = connectedDevice()
        val live = captureSessionValidity()
        return {
            if (device == null) connectedDevice() == null && currentRadioId() == radio else live()
        }
    }

    /** Static sessions emit once; mutable live adapters must emit on radio or graph replacement. */
    fun connectionSnapshots(): Flow<NodesConnectionSnapshot> =
        flowOf(NodesConnectionSnapshot(currentRadioId(), connectionIdentity(), connectedDevice(), connectionState()))
    /** Stable identity of the live service graph; changes even when reconnecting the same radio. */
    fun connectionIdentity(): Any? = contactService()
    fun connectionState(): DeviceConnectionState
    fun connectedDevice(): DeviceDTO?
    fun currentRadioId(): RadioId?
    /** `AppState.offlineDataStore`: readable while disconnected (lists, discovery). */
    fun offlineDataStore(): NodesDataStore?
    /** `services.dataStore`: present only with a live service graph. */
    fun servicesDataStore(): NodesDataStore?
    fun contactService(): NodesContactService?
    fun advertisementService(): NodesAdvertisementService?
    fun traceService(): NodesTraceService?
    fun notificationCleanup(): NodesNotificationCleanup?
}

internal suspend fun NodesSession.ensureContactConnection(identity: Any?, radioId: RadioId?) {
    currentCoroutineContext().ensureActive()
    if (connectionIdentity() !== identity || currentRadioId() != radioId) {
        throw CancellationException("Contact operation's radio session changed")
    }
}

/** The scanned/pasted contact payload (`MeshCoreURLParser.ContactResult`, WP-405). */
data class ScannedContact(val name: String, val publicKey: Bytes, val contactType: ContactType)

/**
 * `meshcore://contact/add` encode (`ContactService.exportContactURI`, WP-209) and parse
 * (`MeshCoreURLParser.parseContactURL`, WP-405). The feature never re-implements either.
 */
interface ContactUriCodec {
    fun exportContactUri(name: String, publicKey: Bytes, type: ContactType): String
    fun parseContactUri(text: String): ScannedContact?
}

/** `FirmwareSuggestedTimeout` (WP-316) values used by path discovery and ping. */
interface FirmwareTimeouts {
    fun pathDiscoverySeconds(suggestedTimeoutMs: UInt): Double
    fun pathDiscoveryRetransmitInterval(suggestedTimeoutMs: UInt): Duration?
    fun zeroHopSeconds(suggestedTimeoutMs: UInt): Double
}

/** `Error.userFacingMessage` (WP-304). */
fun interface UserFacingMessages {
    fun message(error: Throwable): String
}

/** VoiceOver/TalkBack announcement (`AccessibilityNotification.Announcement`). */
fun interface Announcer {
    fun announce(message: NodesMessage)
}

/** `UserDefaults` string arrays used by the recent-hops LRU. */
interface StringListPreferences {
    fun stringList(key: String): List<String>?
    fun setStringList(key: String, value: List<String>)
}

/** Virtual-time friendly clock: wall time for timestamps, monotonic time and sleeps for waits. */
interface NodesClock {
    val wallNow: Instant
    val elapsed: Duration
    suspend fun sleep(duration: Duration)
}

/** Everything the nodes state holders need, resolved by the app graph. */
class NodesFeatureDependencies(
    val session: NodesSession,
    val uriCodec: ContactUriCodec,
    val timeouts: FirmwareTimeouts,
    val messages: UserFacingMessages,
    val announcer: Announcer,
    val preferences: StringListPreferences,
    val clock: NodesClock,
    val locale: () -> Locale = Locale::getDefault,
)
