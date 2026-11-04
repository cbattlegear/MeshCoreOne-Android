// PortedFrom: MC1Services/Sources/MC1Services/Services/AdvertisementService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactAdmission
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactPersisting
import com.meshcoreone.android.core.contracts.domain.LocalDirectContactResolution
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.errors.AdvertisementFault
import com.meshcoreone.android.core.contracts.domain.errors.SourceServiceFaultCarrier
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.snapshotSet
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.ParsedRxLogData
import com.meshcoreone.android.core.protocol.event.PathInfo
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.TraceInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.model.decodePathLen
import com.meshcoreone.android.core.protocol.session.AdvertisingSessionOps
import com.meshcoreone.android.core.protocol.session.SessionEventStreaming
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

// MARK: - Errors and outcomes

sealed class AdvertisementError(message: String, cause: Throwable? = null) :
    Exception(message, cause), SourceServiceFaultCarrier {
    class NotConnected : AdvertisementError("Not connected to device.")
    class SendFailed : AdvertisementError("Failed to send advertisement.")
    class InvalidResponse : AdvertisementError("Invalid response from device.")
    class SessionError(val error: MeshCoreException) : AdvertisementError(error.message.orEmpty(), error)

    /** Exhaustive projection onto the neutral payload; the session cause is carried as the same instance. */
    override val sourceServiceFault: AdvertisementFault
        get() = when (this) {
            is NotConnected -> AdvertisementFault.NotConnected
            is SendFailed -> AdvertisementFault.SendFailed
            is InvalidResponse -> AdvertisementFault.InvalidResponse
            is SessionError -> AdvertisementFault.SessionError(error)
        }
}

/**
 * Result of one advert-driven contact delta exchange.
 *
 * `BUSY` is not `FAILED`: a collision with another sync never reaches the radio, so it must not spend
 * the failure budget that drops drained keys. `NOT_READY` is permanent until a full contact fetch
 * succeeds: neither requeue nor spend the failure budget.
 */
enum class AdvertContactSyncOutcome { SYNCED, BUSY, FAILED, NOT_READY }

/** Delta-sync handler: receives `fullRefetch` and reports the exchange outcome. */
typealias AdvertDeltaSyncHandler = suspend (fullRefetch: Boolean) -> AdvertContactSyncOutcome

/**
 * Monotonic scheduling time plus the phone wall clock. Swift used `ContinuousClock` and `Date()`;
 * both are injected here so debounce, min-interval and busy backoff are deterministic in tests.
 */
interface AdvertisementClock {
    /** Monotonic instant measured from an arbitrary origin. */
    fun now(): Duration

    /** Suspends until [now] reaches [deadline]; returns immediately when it already has. Cancellable. */
    suspend fun sleepUntil(deadline: Duration)

    /** Phone wall clock used for receive-time stamps. */
    fun wallNow(): Instant

    companion object {
        val Default: AdvertisementClock = object : AdvertisementClock {
            override fun now(): Duration = System.nanoTime().nanoseconds
            override suspend fun sleepUntil(deadline: Duration) = delay(deadline - now())
            override fun wallNow(): Instant = Instant.now()
        }
    }
}

/** Log sink for the Swift `Advertisement` and `discover-trace` logger categories. */
fun interface AdvertisementLogSink {
    fun log(category: String, level: DebugLogLevel, message: String)

    companion object {
        const val CATEGORY = "Advertisement"
        const val DISCOVER_TRACE = "discover-trace"
        val None = AdvertisementLogSink { _, _, _ -> }
    }
}

// MARK: - Actor-confined state

/**
 * Swift actor state. Every access happens inside [AdvertisementService.locked]; a lock section is the
 * Kotlin equivalent of one actor segment between suspension points.
 */
internal class AdvertisementServiceState {
    var eventMonitorJob: Job? = null
    /** In-flight `flushPendingDeletedKeys`. Callers wait through `ContactDeletedCleanup`, not only `deleteContacts`. */
    var deletionFlushJob: Job? = null
    var currentRadioId: RadioId? = null
    /** When true, advert delta sync is deferred (full or manual contact sync). */
    var isSyncingContacts = false
    /** 0x80 keys heard since the last delta sync. */
    val pendingAdvertKeys = LinkedHashSet<Bytes>()
    /** Phone receive times for pending 0x80 keys, stamped onto `lastHeardTimestamp` after a delta insert. */
    val pendingAdvertReceiveTimes = LinkedHashMap<Bytes, Instant>()
    /** 0x81 path-update keys waiting for the next delta sync. Kept apart: a path key must not create a Discover row. */
    val pendingPathKeys = LinkedHashSet<Bytes>()
    /** Keys the radio deleted (0x8F) since this round drained its keys. Cleared when the next round drains. */
    val contactsDeletedDuringSync = LinkedHashSet<Bytes>()
    /** Drain-surviving 0x8F queue, flushed on foreground or `stopEventMonitoring`. */
    val pendingDeletedKeys = LinkedHashSet<Bytes>()
    var deltaSyncJob: Job? = null
    /** Monotonic identity for the scheduled round; `finishRound` clears the job only while it matches. */
    var deltaSyncGeneration = 0L
    var lastDeltaSyncEnd: Duration? = null
    var deltaSyncHandler: AdvertDeltaSyncHandler? = null
    /** One-shot: the next delta sync runs as a prune-free full fetch. */
    var escalateToFullRefetch = false
    /** Delta syncs that failed back to back without an intervening success. */
    var consecutiveDeltaSyncFailures = 0
    /** Last overwrite-oldest deletion, used to correlate the replacement advert (0x8F then new contact). */
    var lastOverwriteDeletion: OverwriteDeletion? = null
    /** Jobs created inside a lock section; started only after the lock is released. */
    val jobsToStart = ArrayList<Job>()

    val pathSyncPending: Boolean get() = pendingPathKeys.isNotEmpty()
    val hasPendingDeltaSyncWork: Boolean
        get() = pendingAdvertKeys.isNotEmpty() || pathSyncPending || escalateToFullRefetch

    fun isRadioDeleted(key: Bytes): Boolean = key in contactsDeletedDuringSync || key in pendingDeletedKeys

    class OverwriteDeletion(val name: String, val pubKeyHex: String, val time: Instant)
}

// MARK: - Advertisement Service

/**
 * Manages self advertisements and processes incoming adverts via MeshCore events.
 *
 * [scope] owns the event monitor, delta-sync rounds and deferred-delete flushes (Swift unstructured
 * tasks); cancelling it is the teardown Swift's `deinit` performed. [advertising] and [eventSource]
 * are normally the same session object (Swift: `any AdvertisingSessionOps & SessionEventStreaming`).
 */
class AdvertisementService(
    internal val advertising: AdvertisingSessionOps,
    internal val eventSource: SessionEventStreaming,
    internal val dataStore: PersistenceStoreProtocol,
    internal val scope: CoroutineScope,
    internal val advertSyncDebounce: Duration = 5.seconds,
    internal val advertSyncMinInterval: Duration = 30.seconds,
    /** Backoff before re-arming after `BUSY`; shorter than the min interval so collisions poll cheaply. */
    internal val advertSyncBusyBackoff: Duration = 5.seconds,
    internal val appStateProvider: AppStateProvider? = null,
    internal val clock: AdvertisementClock = AdvertisementClock.Default,
    private val logSink: AdvertisementLogSink = AdvertisementLogSink.None,
) {
    private val lock = Any()
    private val pendingAdvertGate = Mutex()
    private val localDirectContacts: LocalDirectContactPersisting
        get() = checkNotNull(dataStore as? LocalDirectContactPersisting) {
            "Advertisement contact attribution requires LocalDirectContactPersisting"
        }
    private val state = AdvertisementServiceState()
    /** Keys revived while a deferred-delete flush is off-actor in `deleteContacts`; read per key by the store. */
    private val revivedLock = Any()
    private val revivedDuringDeleteFlush = HashSet<Bytes>()
    internal val eventBroadcaster = AdvertisementEventBroadcaster()

    init {
        require(!advertSyncDebounce.isNegative() && !advertSyncMinInterval.isNegative()) { "Negative advert interval" }
        require(!advertSyncBusyBackoff.isNegative()) { "Negative busy backoff" }
    }

    /** Runs one actor segment; jobs scheduled inside it start after the lock is released. */
    internal fun <T> locked(block: AdvertisementServiceState.() -> T): T {
        val starts: List<Job>
        val result = synchronized(lock) {
            val value = state.block()
            starts = state.jobsToStart.toList()
            state.jobsToStart.clear()
            value
        }
        starts.forEach(Job::start)
        return result
    }

    internal suspend fun <T> withPendingAdvertGate(block: suspend () -> T): T =
        pendingAdvertGate.withLock { block() }

    internal suspend fun <T> pendingLocked(block: AdvertisementServiceState.() -> T): T =
        withPendingAdvertGate { locked(block) }

    internal fun logger(level: DebugLogLevel, message: String) = logSink.log(AdvertisementLogSink.CATEGORY, level, message)
    internal fun discoverTrace(level: DebugLogLevel, message: String) =
        logSink.log(AdvertisementLogSink.DISCOVER_TRACE, level, message)

    // MARK: Test-visible state (Swift `@testable` internal properties)

    internal val currentRadioId: RadioId? get() = locked { currentRadioId }
    internal val isSyncingContacts: Boolean get() = locked { isSyncingContacts }
    internal val pendingAdvertKeys: Set<Bytes> get() = locked { pendingAdvertKeys.toSet() }
    internal val pendingPathKeys: Set<Bytes> get() = locked { pendingPathKeys.toSet() }
    internal val pathSyncPending: Boolean get() = locked { pathSyncPending }
    internal val contactsDeletedDuringSync: Set<Bytes> get() = locked { contactsDeletedDuringSync.toSet() }
    internal val pendingDeletedKeys: Set<Bytes> get() = locked { pendingDeletedKeys.toSet() }
    internal val deltaSyncTask: Job? get() = locked { deltaSyncJob }
    internal val deltaSyncGeneration: Long get() = locked { deltaSyncGeneration }
    internal val lastDeltaSyncEnd: Duration? get() = locked { lastDeltaSyncEnd }
    internal val deltaSyncHandler: AdvertDeltaSyncHandler? get() = locked { deltaSyncHandler }
    internal val escalateToFullRefetch: Boolean get() = locked { escalateToFullRefetch }
    internal val consecutiveDeltaSyncFailures: Int get() = locked { consecutiveDeltaSyncFailures }
    internal val hasPendingDeltaSyncWork: Boolean get() = locked { hasPendingDeltaSyncWork }

    internal suspend fun isInForeground(): Boolean = appStateProvider?.isInForeground() ?: true

    internal fun isRadioDeleted(key: Bytes): Boolean = locked { isRadioDeleted(key) }

    // MARK: - Events

    /**
     * Returns a fresh stream of advertisement and discovery events. Registration is synchronous, so
     * events yielded after this call are never dropped. Consumers re-subscribe per connection.
     */
    fun events(): Flow<AdvertisementEvent> = eventBroadcaster.subscribe()

    /** Ends every [events] subscriber's collection so consumers release the service. */
    fun finishEvents() = eventBroadcaster.finish()

    // MARK: - Event Monitoring

    /** Starts monitoring MeshCore events for advertisement-related notifications. */
    fun startEventMonitoring(radioId: RadioId) {
        val monitor = scope.launch(start = CoroutineStart.LAZY) { monitorEvents(radioId) }
        locked {
            // Swift cancels the old monitor before creating the new one; cancel inside the segment so the
            // new monitor (started when `locked` exits) never overlaps a still-subscribed predecessor.
            eventMonitorJob?.cancel()
            currentRadioId = radioId
            eventMonitorJob = monitor
            jobsToStart += monitor
        }
    }

    private suspend fun monitorEvents(radioId: RadioId) {
        try {
            eventSource.events(MONITORED_EVENTS).collect { event ->
                // Swift breaks the for-await loop once cancelled; never start another event after cancel.
                currentCoroutineContext().ensureActive()
                // Swift actor handlers are not interrupted by monitor cancellation; stop awaits them.
                withContext(NonCancellable) { handleEvent(event, radioId) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Advertisement event stream ended: ${failure.message}")
        }
    }

    /** Stops event monitoring and clears advert delta-sync state. */
    suspend fun stopEventMonitoring() = withContext(NonCancellable) {
        // Drop the handler and bump the generation before any suspension so the in-flight round
        // cannot reconcile or re-arm.
        val (monitor, round) = locked {
            val m = eventMonitorJob
            eventMonitorJob = null
            deltaSyncHandler = null
            val r = deltaSyncJob
            deltaSyncGeneration += 1
            m to r
        }
        monitor?.cancel()
        round?.cancel()
        // Await the flush before clearing `currentRadioId` so a queued 0x8F cannot outlive stop.
        monitor?.join()
        flushPendingDeletedKeys()
        val (receiveTimes, radioId) = locked { pendingAdvertReceiveTimes.toMap() to currentRadioId }
        stampAdvertReceiveTimes(receiveTimes, radioId)
        pendingLocked {
            currentRadioId = null
            deltaSyncJob = null
            pendingAdvertKeys.clear()
            pendingPathKeys.clear()
            pendingAdvertReceiveTimes.clear()
            escalateToFullRefetch = false
            consecutiveDeltaSyncFailures = 0
            isSyncingContacts = false
            // Keep `contactsDeletedDuringSync` so a commit still in flight can roll back radio-deleted rows.
        }
    }

    /** Flushes queued 0x8F deletes, then re-arms when delta-sync work is pending. */
    suspend fun handleReturnToForeground() {
        flushPendingDeletedKeys()
        locked { if (hasPendingDeltaSyncWork) scheduleDeltaSyncLocked(this) }
    }

    internal suspend fun flushPendingDeletedKeys() {
        val (job, created) = locked {
            deletionFlushJob?.let { return@locked it to false }
            if (pendingDeletedKeys.isEmpty()) return@locked null
            val radioId = currentRadioId ?: return@locked null
            val flush = scope.launch(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext().job
                withContext(NonCancellable) { runDeletionFlush(radioId, owner) }
            }
            deletionFlushJob = flush
            jobsToStart += flush
            flush to true
        } ?: return
        job.join()
        // A flush cancelled before it ever ran never reaches its own cleanup.
        if (created) clearAbandonedDeletionFlush(job)
    }

    private fun clearAbandonedDeletionFlush(job: Job) {
        locked { releaseDeletionFlushLocked(job) }
    }

    /**
     * Swift's flush `defer` clears the revived set and the task in the same actor segment as the final
     * empty check or the failure requeue; doing both here under the lock keeps that atomicity, and the
     * owner check stops a finished flush from clearing a successor's revived keys.
     */
    private fun AdvertisementServiceState.releaseDeletionFlushLocked(owner: Job) {
        if (deletionFlushJob !== owner) return
        deletionFlushJob = null
        synchronized(revivedLock) { revivedDuringDeleteFlush.clear() }
    }

    private suspend fun runDeletionFlush(radioId: RadioId, owner: Job) {
        try {
            while (true) {
                val keys = locked {
                    if (pendingDeletedKeys.isEmpty()) {
                        releaseDeletionFlushLocked(owner)
                        null
                    } else {
                        pendingDeletedKeys.toSet().also { pendingDeletedKeys.clear() }
                    }
                } ?: return
                val deletedIds = try {
                    dataStore.deleteContacts(radioId, keys.snapshotSet()) {
                        synchronized(revivedLock) { revivedDuringDeleteFlush.toList() }.snapshotSet()
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    locked {
                        pendingDeletedKeys += keys
                        pendingDeletedKeys -= pendingAdvertKeys
                        releaseDeletionFlushLocked(owner)
                    }
                    logger(DebugLogLevel.ERROR, "Overwrite oldest: deferred delete flush failed: ${failure.message}")
                    return
                }
                if (deletedIds.isEmpty()) continue
                logger(DebugLogLevel.NOTICE, "Overwrite oldest: applying ${deletedIds.size} deferred deletion(s)")
                eventBroadcaster.yield(AdvertisementEvent.ContactDeletedCleanup(deletedIds))
                eventBroadcaster.yield(AdvertisementEvent.NodeStorageFullChanged(false))
                eventBroadcaster.yield(AdvertisementEvent.ContactUpdated)
            }
        } finally {
            // Normal exits already released under the lock; this covers an unexpected throw.
            locked { releaseDeletionFlushLocked(owner) }
        }
    }

    internal fun markRevived(key: Bytes) = synchronized(revivedLock) { revivedDuringDeleteFlush += key }

    private fun unmarkRevived(key: Bytes) = synchronized(revivedLock) { revivedDuringDeleteFlush -= key }

    /**
     * Records a 0x80 key for the next delta sync. A re-advert for a radio-deleted key means the radio
     * re-added it after 0x8F, so both tombstones clear. [receivedAt] is the phone clock at the 0x80.
     */
    internal suspend fun recordPendingAdvertKey(key: Bytes, receivedAt: Instant = clock.wallNow()) =
        pendingLocked { recordPendingAdvertKeyLocked(key, receivedAt) }

    internal fun AdvertisementServiceState.recordPendingAdvertKeyLocked(key: Bytes, receivedAt: Instant) {
        pendingAdvertKeys += key
        contactsDeletedDuringSync -= key
        pendingDeletedKeys -= key
        markRevived(key)
        pendingAdvertReceiveTimes[key] = receivedAt
    }

    /** Captures and removes phone receive times for keys about to drain. */
    internal fun AdvertisementServiceState.takeAdvertReceiveTimesLocked(keys: Set<Bytes>): Map<Bytes, Instant> =
        keys.mapNotNull { key -> pendingAdvertReceiveTimes.remove(key)?.let { key to it } }.toMap()

    /**
     * Stamps `lastHeardTimestamp` for drained 0x80 keys that now have a Contact row, using each key's
     * recorded phone receive time, so a stale radio RTC cannot prune a contact just heard on air.
     */
    internal suspend fun stampAdvertReceiveTimes(receiveTimes: Map<Bytes, Instant>, radioId: RadioId?) {
        if (radioId == null || receiveTimes.isEmpty()) return
        for ((publicKey, receivedAt) in receiveTimes) {
            if (isRadioDeleted(publicKey)) continue
            try {
                dataStore.touchContactHeard(radioId, publicKey, receivedAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                logger(DebugLogLevel.ERROR, "Post-delta lastHeard stamp failed for ${publicKey.uppercaseHexString()}: ${failure.message}")
            }
        }
    }

    /**
     * Creates a local contact from a pending 0x80 key so a DM that arrives inside the debounce window
     * can notify and list normally. Exactly one pending key must match [prefix]; the key stays pending
     * so the debounced round still reconciles Discover and may announce the contact. Storage failures
     * and cancellation propagate. NotFound means genuine absence, not ambiguity or a failed lookup.
     */
    suspend fun materializeContactForPendingAdvert(prefix: Bytes, radioId: RadioId): LocalDirectContactResolution {
        require(prefix.size == 6) { "Pending advert materialization requires a six-byte receive prefix" }
        val initial = withPendingAdvertGate {
            val local = localDirectContacts.classifyLocalDirectContact(radioId, prefix)
            unionPendingResolution(prefix, local)
        }
        if (initial != LocalDirectContactResolution.NotFound) return initial
        val pending = pendingLocked {
            if (currentRadioId != radioId) return@pendingLocked null
            val keys = pendingAdvertKeys.filter { it.advertStartsWith(prefix) }
            if (keys.size != 1) return@pendingLocked null
            Triple(keys.single(), pendingAdvertReceiveTimes[keys.single()], eventMonitorJob)
        } ?: return withPendingAdvertGate {
            unionPendingResolution(prefix, LocalDirectContactResolution.NotFound)
        }
        val meshContact = advertising.getContact(pending.first)
        return withPendingAdvertGate {
            val keys = locked { pendingAdvertKeys.filter { it.advertStartsWith(prefix) } }
            if (keys.size > 1) return@withPendingAdvertGate LocalDirectContactResolution.Ambiguous
            val stillCurrent = locked {
                currentRadioId == radioId && eventMonitorJob === pending.third &&
                    !isRadioDeleted(pending.first)
            }
            if (!stillCurrent || keys.singleOrNull() != pending.first) {
                return@withPendingAdvertGate LocalDirectContactResolution.Stale
            }
            val local = localDirectContacts.classifyLocalDirectContact(radioId, prefix)
            val resolution = unionPendingResolution(prefix, local)
            if (resolution != LocalDirectContactResolution.NotFound) return@withPendingAdvertGate resolution
            if (meshContact == null) {
                logger(DebugLogLevel.INFO, "materialize pending advert: radio contact unavailable")
                return@withPendingAdvertGate LocalDirectContactResolution.Stale
            }
            if (meshContact.publicKey != pending.first) {
                logger(DebugLogLevel.WARNING, "materialize pending advert: radio returned a different full key")
                return@withPendingAdvertGate LocalDirectContactResolution.Stale
            }
            val receivedAt = locked { pendingAdvertReceiveTimes[pending.first] } ?: pending.second
                ?: clock.wallNow()
            val materialized = unionPendingResolution(
                prefix, localDirectContacts.materializeLocalDirectContactIfAbsent(
                    radioId, prefix, meshContact.advertContactFrame(), receivedAt,
                ),
            )
            if (materialized is LocalDirectContactResolution.Unique) {
                eventBroadcaster.yield(AdvertisementEvent.ContactUpdated)
            }
            materialized
        }
    }

    /**
     * Keeps pending-prefix ambiguity stable through the producer's roster admission and consumer
     * effects. Lock order is pending, local roster, then Room. The callback must not mutate adverts,
     * roster or channels, reenter this gate, or perform hardware/platform notification work.
     * A unique local result is not proof of the RF sender; unresolved observations are never upgraded.
     */
    suspend fun <T> withPendingAdvertContactOperation(
        radioId: RadioId,
        prefix: Bytes,
        observed: LocalDirectContactResolution,
        block: suspend (LocalDirectContactAdmission) -> T,
    ): T = withPendingAdvertGate {
        require(prefix.size == 6) { "Pending advert admission requires a six-byte receive prefix" }
        val current = localDirectContacts.classifyLocalDirectContact(radioId, prefix)
        val combined = when {
            observed == LocalDirectContactResolution.Ambiguous || observed == LocalDirectContactResolution.Stale -> observed
            unionPendingResolution(prefix, current) == LocalDirectContactResolution.Ambiguous ->
                LocalDirectContactResolution.Ambiguous
            else -> unionPendingResolution(prefix, observed)
        }
        localDirectContacts.withLocalDirectContactOperation(radioId, prefix, combined, block)
    }

    private fun unionPendingResolution(prefix: Bytes, local: LocalDirectContactResolution): LocalDirectContactResolution =
        locked {
            val keys = pendingAdvertKeys.filter { it.advertStartsWith(prefix) }
            if (keys.size > 1 || local is LocalDirectContactResolution.Unique &&
                keys.any { it != local.contact.publicKey }
            ) LocalDirectContactResolution.Ambiguous else local
        }

    private suspend fun bestEffort(message: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "$message: ${failure.message}")
        }
    }

    /** Installs the delta-sync handler; a non-nil handler re-arms when work is already pending. */
    fun setDeltaSyncHandler(handler: AdvertDeltaSyncHandler?) = locked {
        deltaSyncHandler = handler
        if (handler != null && hasPendingDeltaSyncWork) scheduleDeltaSyncLocked(this)
    }

    /** Toggles deferred contact sync; clearing it with pending work re-arms (foreground only). */
    suspend fun setSyncingContacts(isSyncing: Boolean) {
        val check = locked {
            isSyncingContacts = isSyncing
            !isSyncing && hasPendingDeltaSyncWork
        }
        if (check && isInForeground()) locked { scheduleDeltaSyncLocked(this) }
    }

    private suspend fun handleEvent(event: MeshEvent, radioId: RadioId) {
        when (event) {
            is MeshEvent.Advertisement -> handleAdvertEvent(event.publicKey, radioId)
            is MeshEvent.NewContact -> handleNewAdvertEvent(event.contact, radioId)
            is MeshEvent.PathUpdate -> handlePathUpdatedEvent(event.publicKey)
            is MeshEvent.PathResponse -> handlePathDiscoveryResponse(event.path, radioId)
            is MeshEvent.TraceData -> handleTraceData(event.trace, radioId)
            is MeshEvent.RxLogData -> if (event.data.payloadType == PayloadType.TRACE) handleTraceRxLog(event.data, radioId)
            is MeshEvent.ContactDeleted -> handleContactDeletedEvent(event.publicKey, radioId)
            is MeshEvent.ContactsFull -> handleContactsFullEvent()
            else -> Unit
        }
    }

    private fun handleTraceRxLog(logData: ParsedRxLogData, radioId: RadioId) {
        val snr = logData.snr ?: return
        if (logData.packetPayload.size < 4) return
        val tag = logData.packetPayload.readUInt32LE(0)
        val remoteSnr = logData.pathNodes.lastOrNull()?.let { it.toByte().toDouble() / 4.0 }
        eventBroadcaster.yield(AdvertisementEvent.TraceSnrObserved(tag, snr, remoteSnr, radioId))
    }

    // MARK: - Send Advertisement / node name / location

    /** Sends a self advertisement: flood reaches all nodes, zero-hop reaches direct neighbors only. */
    suspend fun sendSelfAdvertisement(flood: Boolean) =
        wrapSessionError { advertising.sendAdvertisement(flood) }

    /** Sets the node's advertised name (max 31 characters). */
    suspend fun setAdvertName(name: String) = wrapSessionError { advertising.setName(name) }

    /** Sets the node's advertised GPS coordinates. */
    suspend fun setAdvertLocation(latitude: Double, longitude: Double) =
        wrapSessionError { advertising.setCoordinates(latitude, longitude) }

    private suspend fun wrapSessionError(block: suspend () -> Unit) {
        try {
            block()
        } catch (error: MeshCoreException) {
            throw AdvertisementError.SessionError(error)
        }
    }

    // MARK: - Private Event Handlers

    /** 0x80 change notification: the radio already updated a contact row. */
    private suspend fun handleAdvertEvent(publicKey: Bytes, radioId: RadioId) {
        val pubKeyHex = publicKey.uppercaseHexString()
        logger(DebugLogLevel.DEBUG, "Advert event for $pubKeyHex")
        discoverTrace(DebugLogLevel.DEBUG, "B1 0x80 ADVERT received key=$pubKeyHex")
        // One phone clock for touch and pending receive time so the post-delta stamp matches the air-hear.
        val receivedAt = clock.wallNow()
        // Visible to an in-flight `deleteContacts`. Do not drop `pendingDeletedKeys` here: a failed
        // touch must not drop a queued 0x8F.
        markRevived(publicKey)
        if (!isInForeground()) {
            recordPendingAdvertKey(publicKey, receivedAt)
            return
        }
        // Retry the touch once: a transient store error must not permanently drop the advert, and
        // recording without a successful touch could announce a long-known contact as new.
        val known: Boolean? = try {
            dataStore.touchContactHeard(radioId, publicKey, receivedAt)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Error handling advert event (retrying once): ${failure.message}")
            try {
                dataStore.touchContactHeard(radioId, publicKey, receivedAt)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (retryFailure: Exception) {
                logger(DebugLogLevel.ERROR, "Advert touch retry failed: ${retryFailure.message}")
                null
            }
        }
        if (known == true) eventBroadcaster.yield(AdvertisementEvent.ContactUpdated)
        pendingLocked {
            if (known == false) {
                discoverTrace(DebugLogLevel.DEBUG, "B2 0x80 no local contact key=$pubKeyHex syncing=$isSyncingContacts")
                logger(DebugLogLevel.DEBUG, "ADVERT received for unknown contact - scheduling delta sync")
            }
            if (known != null) recordPendingAdvertKeyLocked(publicKey, receivedAt)
            // Schedule so other pending keys or a path update still run; the empty-round guard no-ops
            // when this advert alone failed both touch attempts.
            scheduleDeltaSyncLocked(this)
        }
    }

    /** New advertisement (manual add mode): a new contact was discovered. */
    private suspend fun handleNewAdvertEvent(contact: MeshContact, radioId: RadioId) {
        val frame = contact.advertContactFrame()
        val pubKeyHex = frame.publicKey.uppercaseHexString()
        discoverTrace(DebugLogLevel.DEBUG, "B1 0x8A NEW_ADVERT received key=$pubKeyHex")
        try {
            val result = dataStore.upsertDiscoveredNode(radioId, frame)
            discoverTrace(DebugLogLevel.DEBUG, "B2 0x8A upsert key=$pubKeyHex isNew=${result.isNew}")
            eventBroadcaster.yield(AdvertisementEvent.ContactUpdated)
            // Notify only first-time discoveries, not repeat adverts for the same contact.
            if (result.isNew) {
                val node = result.node
                eventBroadcaster.yield(AdvertisementEvent.NewContactDiscovered(node.name, node.id, node.nodeType))
                locked { logOverwriteReplacementIfRecentLocked(node.name, node.nodeType) }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Error handling new advert event: ${failure.message}")
            discoverTrace(DebugLogLevel.ERROR, "B2 0x8A upsert FAILED key=$pubKeyHex: ${failure.message}")
        }
    }

    /**
     * Path changed on the radio: record the key and schedule a delta sync. Path keys never create
     * Discover rows; an undelivered incremental escalates once to a prune-free full refetch.
     */
    private suspend fun handlePathUpdatedEvent(publicKey: Bytes) {
        logger(DebugLogLevel.DEBUG, "Path updated event for ${publicKey.uppercaseHexString()}")
        locked { pendingPathKeys += publicKey }
        if (isInForeground()) locked { scheduleDeltaSyncLocked(this) }
    }

    /** Path discovery response: update out-path and stamp phone-clock lastHeard; keep radio lastModified. */
    private suspend fun handlePathDiscoveryResponse(result: PathInfo, radioId: RadioId) {
        // Chunk using each direction's declared hash size so firmware mode skew cannot smear hop boundaries.
        val outHops = result.outPath.advertHops(decodePathLen(result.outPathLength)?.hashSize ?: 1)
        val inHops = result.inPath.advertHops(decodePathLen(result.inPathLength)?.hashSize ?: 1)
        val outDisplay = if (outHops.isEmpty()) "direct" else outHops.joinToString(" → ")
        val inDisplay = if (inHops.isEmpty()) "direct" else inHops.joinToString(" → ")
        logger(
            DebugLogLevel.INFO,
            "Path discovery for ${result.publicKeyPrefix.prefix(3).uppercaseHexString()}... - Out: ${outHops.size} hops " +
                "($outDisplay), In: ${inHops.size} hops ($inDisplay)",
        )
        try {
            val observed = localDirectContacts.classifyLocalDirectContact(radioId, result.publicKeyPrefix)
            val applied = if (observed is LocalDirectContactResolution.Unique) {
                localDirectContacts.applyLocalDirectPathIfCurrent(
                    radioId, result.publicKeyPrefix, observed, result.outPathLength, result.outPath, clock.wallNow(),
                )
            } else observed
            when (applied) {
                is LocalDirectContactResolution.Unique -> Unit
                LocalDirectContactResolution.NotFound ->
                    logger(DebugLogLevel.INFO, "Path response has no matching local contact")
                LocalDirectContactResolution.Ambiguous ->
                    logger(DebugLogLevel.WARNING, "Path response has an ambiguous local contact prefix; no path or recency changed")
                LocalDirectContactResolution.Stale ->
                    logger(DebugLogLevel.WARNING, "Path response local contact changed before mutation; no path or recency changed")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger(DebugLogLevel.ERROR, "Error handling path discovery response: ${failure.message}")
        }
        eventBroadcaster.yield(AdvertisementEvent.PathDiscoveryResponse(result))
    }

    private fun handleTraceData(traceInfo: TraceInfo, radioId: RadioId) {
        logger(DebugLogLevel.INFO, "Received trace data: tag=${traceInfo.tag}, hops=${traceInfo.path.size}")
        eventBroadcaster.yield(AdvertisementEvent.TraceResponse(traceInfo, radioId))
    }

    /** Contact deleted (0x8F): the device auto-deleted a contact via overwrite oldest. */
    private suspend fun handleContactDeletedEvent(publicKey: Bytes, radioId: RadioId) {
        val fullPubKeyHex = publicKey.uppercaseHexString()
        val pubKeyPrefix = publicKey.prefix(6).uppercaseHexString()
        // ZephCore `set v.contact off` also pushes 0x8F for the V-key; that frees no slot. Keep the row.
        val selfPublicKey = try {
            dataStore.fetchDevice(radioId)?.publicKey
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (selfPublicKey != null && VContactIdentity.isVContact(publicKey, selfPublicKey)) {
            logger(
                DebugLogLevel.INFO,
                "Contact deleted push for ZephCore V-contact ($pubKeyPrefix...); preserving local row, not clearing storage-full",
            )
            return
        }
        pendingLocked {
            // Drop pending reconcile/path keys so a deleted contact is not re-notified or escalated.
            pendingAdvertKeys -= publicKey
            pendingPathKeys -= publicKey
            pendingAdvertReceiveTimes.remove(publicKey)
            // A running round can re-save this row; a key recorded outside a round clears at the next drain.
            contactsDeletedDuringSync += publicKey
            // Enqueue before the foreground hop so a concurrent flush cannot miss this key.
            unmarkRevived(publicKey)
            pendingDeletedKeys += publicKey
        }
        if (!isInForeground()) return
        if (!locked { pendingDeletedKeys.remove(publicKey) }) return
        logger(DebugLogLevel.DEBUG, "Overwrite oldest: device deleted contact with key $pubKeyPrefix...")
        try {
            val contact = dataStore.fetchContact(radioId, publicKey)
            if (contact == null) {
                logger(
                    DebugLogLevel.WARNING,
                    "Overwrite oldest: contact not found in local database for key $pubKeyPrefix... (may have been deleted already)",
                )
                return
            }
            val contactName = contact.name.ifEmpty { "(unnamed)" }
            val typeDesc = ContactType.fromRawValue(contact.typeRawValue)?.toString() ?: "unknown(${contact.typeRawValue})"
            logger(
                DebugLogLevel.NOTICE,
                "Overwrite oldest: deleting contact '$contactName' [key=$fullPubKeyHex, type=$typeDesc, " +
                    "favorite=${contact.isFavorite}, pathLen=${contact.outPathLength}, " +
                    "lastModified=${Instant.ofEpochSecond(contact.lastModified.toLong())}, " +
                    "lastAdvert=${Instant.ofEpochSecond(contact.lastAdvertTimestamp.toLong())}]",
            )
            locked {
                lastOverwriteDeletion = AdvertisementServiceState.OverwriteDeletion(contactName, pubKeyPrefix, clock.wallNow())
            }
            dataStore.deleteContact(EntityKey(radioId, contact.id))
            logger(DebugLogLevel.DEBUG, "Overwrite oldest: deleted contact '$contactName' and its messages from local database")
            eventBroadcaster.yield(AdvertisementEvent.ContactDeletedCleanup(SnapshotList.of(contact.id)))
            eventBroadcaster.yield(AdvertisementEvent.NodeStorageFullChanged(false))
            logger(DebugLogLevel.DEBUG, "Overwrite oldest: cleanup complete for '$contactName', storage full flag cleared")
            eventBroadcaster.yield(AdvertisementEvent.ContactUpdated)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            locked { pendingDeletedKeys += publicKey }
            logger(DebugLogLevel.ERROR, "Overwrite oldest: failed to delete contact $pubKeyPrefix...: ${failure.message}")
        }
    }

    /** Correlates an overwrite-oldest deletion with a replacement contact seen soon after. */
    internal fun AdvertisementServiceState.logOverwriteReplacementIfRecentLocked(newName: String, newType: ContactType) {
        val deletion = lastOverwriteDeletion ?: return
        if (java.time.Duration.between(deletion.time, clock.wallNow()).seconds >= OVERWRITE_CORRELATION_SECONDS) return
        logger(
            DebugLogLevel.NOTICE,
            "Overwrite oldest: '${deletion.name}' (${deletion.pubKeyHex}...) replaced by '$newName' (type=$newType)",
        )
        lastOverwriteDeletion = null
    }

    /** Contacts full (0x90): device node storage is full. */
    private fun handleContactsFullEvent() {
        logger(
            DebugLogLevel.WARNING,
            "Device node storage is full - if overwrite oldest is enabled, the next new node will trigger " +
                "auto-deletion of the oldest non-favorite contact",
        )
        eventBroadcaster.yield(AdvertisementEvent.NodeStorageFullChanged(true))
    }

    companion object {
        /** Failed delta syncs tolerated before a round drops its drained keys. */
        const val MAX_CONSECUTIVE_DELTA_SYNC_FAILURES = 5
        private const val OVERWRITE_CORRELATION_SECONDS = 60L

        private val MONITORED_EVENTS = EventFilter { event ->
            when (event) {
                is MeshEvent.Advertisement, is MeshEvent.NewContact, is MeshEvent.PathUpdate,
                is MeshEvent.PathResponse, is MeshEvent.TraceData, is MeshEvent.ContactDeleted,
                MeshEvent.ContactsFull -> true
                is MeshEvent.RxLogData -> event.data.payloadType == PayloadType.TRACE
                else -> false
            }
        }
    }
}

internal fun Bytes.advertStartsWith(prefix: Bytes): Boolean = size >= prefix.size && prefix(prefix.size) == prefix

private fun Bytes.advertHops(hashSize: Int): List<String> {
    val step = hashSize.coerceAtLeast(1)
    return (0 until size step step).map { start -> slice(start, minOf(start + step, size)).uppercaseHexString() }
}

/** `MeshContact.toContactFrame()` (Swift: ContactService.swift extension, owned by the contact-service port). */
internal fun MeshContact.advertContactFrame(): ContactFrame = ContactFrame(
    publicKey = publicKey,
    type = type,
    flags = flags.rawValue,
    outPathLength = outPathLength,
    outPath = outPath,
    name = advertisedName,
    lastAdvertTimestamp = lastAdvertisement.epochSecond.toUInt(),
    latitude = latitude,
    longitude = longitude,
    lastModified = lastModified.epochSecond.toUInt(),
    typeRawValue = typeRawValue,
)
