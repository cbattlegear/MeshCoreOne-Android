// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.AdvertisingSessionOps
import com.meshcoreone.android.core.protocol.session.SessionEventStreaming
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.DynamicTest

// MARK: - Case builders

internal fun advertCase(name: String, signature: String = "()", body: suspend AdvertHarness.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("AdvertisementServiceTests::$name$signature") { runAdvertHarness(body = body) }

/** One original parameter family executed once, iterating its Swift arguments on fresh harnesses. */
internal fun <A> advertParameterizedCase(
    name: String,
    signature: String,
    arguments: List<A>,
    body: suspend AdvertHarness.(A) -> Unit,
): DynamicTest = DynamicTest.dynamicTest("AdvertisementServiceTests::$name$signature") {
    arguments.forEach { argument -> runAdvertHarness { body(argument) } }
}

internal fun advertNativeCase(name: String, multiThreaded: Boolean = false, body: suspend AdvertHarness.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-209::$name") { runAdvertHarness(multiThreaded, body) }

/**
 * Runs one case on a fresh harness. By default the service shares the test's single event-loop thread,
 * the closest analogue of Swift actor serialization; native stress cases opt into the shared pool.
 */
internal fun runAdvertHarness(multiThreaded: Boolean = false, body: suspend AdvertHarness.() -> Unit) = runBlocking {
    val failures = java.util.Collections.synchronizedList(ArrayList<Throwable>())
    val dispatcher = if (multiThreaded) kotlinx.coroutines.Dispatchers.Default else
        requireNotNull(coroutineContext[ContinuationInterceptor]) { "runBlocking always installs an event loop" }
    val serviceScope = CoroutineScope(SupervisorJob() + dispatcher + CoroutineExceptionHandler { _, e -> failures += e })
    try {
        withTimeout(60.seconds) { AdvertHarness(this, serviceScope).body() }
    } finally {
        serviceScope.cancel()
    }
    assertTrue(failures.isEmpty(), "Uncaught service-scope failures: $failures")
}

// MARK: - Helpers (Swift file-private helpers)

/** Polls until [predicate] is true or [timeout] elapses (Swift `waitUntil`). */
internal suspend fun advertWaitUntil(
    timeout: Duration = 10.seconds,
    poll: Duration = 10.milliseconds,
    predicate: suspend () -> Boolean,
): Boolean {
    val deadline = TimeSource.Monotonic.markNow() + timeout
    while (deadline.hasNotPassedNow()) {
        if (predicate()) return true
        delay(poll)
    }
    return predicate()
}

internal fun advertPublicKey(seed: Int): Bytes =
    Bytes(ByteArray(ProtocolLimits.PUBLIC_KEY_SIZE) { ((it + seed) and 0xFF).toByte() })

internal fun advertMeshContact(publicKey: Bytes, name: String = "Node", type: ContactType = ContactType.CHAT): MeshContact =
    MeshContact(
        id = publicKey.hexString, publicKey = publicKey, type = type, flags = ContactFlags(0u), outPathLength = 0u,
        outPath = Bytes.EMPTY, advertisedName = name, lastAdvertisement = Instant.ofEpochSecond(1_700_000_000),
        latitude = 0.0, longitude = 0.0, lastModified = Instant.ofEpochSecond(1_700_000_100),
    )

internal fun advertContactFrame(
    publicKey: Bytes,
    name: String = "LocalContact",
    type: ContactType = ContactType.CHAT,
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    lastAdvertTimestamp: UInt = 1_700_000_000u,
    lastModified: UInt = 1_700_000_100u,
    outPathLength: UByte = 0u,
    outPath: Bytes = Bytes.EMPTY,
): ContactFrame = ContactFrame(publicKey, type, 0u, outPathLength, outPath, name, lastAdvertTimestamp, latitude, longitude, lastModified)

internal fun advertTestContact(
    radioId: RadioId, publicKey: Bytes, name: String, lastModified: UInt, lastHeardTimestamp: UInt?, isFavorite: Boolean = false,
): ContactDTO = ContactDTO(
    radioId = radioId, publicKey = publicKey, name = name, typeRawValue = ContactType.CHAT.rawValue,
    lastModified = lastModified, lastHeardTimestamp = lastHeardTimestamp, isFavorite = isFavorite,
)

internal fun advertDirectMessage(radioId: RadioId, contactID: UUID?, text: String, senderKeyPrefix: Bytes? = null): MessageDTO =
    MessageDTO(
        radioId = radioId, contactID = contactID, text = text, timestamp = 1_700_000_400u,
        direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, senderKeyPrefix = senderKeyPrefix,
    )

/** Rethrows cancellation; swallows anything else (Swift `try?`). */
internal suspend fun advertTry(block: suspend () -> Unit) {
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // try? semantics
    }
}

// MARK: - Harness

internal class AdvertHarness(private val testScope: CoroutineScope, val serviceScope: CoroutineScope) {
    val radioId = RadioId(UUID.randomUUID())
    val session = AdvertFakeSession()

    /** Swift `makeStore()`: the in-memory store with this radio's device row. */
    suspend fun makeStore(): AdvertFakeStore = AdvertFakeStore().also {
        it.saveDevice(DeviceDTO(radioId = radioId, publicKey = advertPublicKey(0xEE), nodeName = "TestRadio"))
    }

    /** Swift `MockPersistenceStore()`: no device row. */
    fun makeMockStore(): AdvertFakeStore = AdvertFakeStore()

    fun makeService(
        store: PersistenceStoreProtocol,
        debounce: Duration = Duration.ZERO,
        minInterval: Duration = Duration.ZERO,
        busyBackoff: Duration = Duration.ZERO,
        appStateProvider: AppStateProvider? = null,
        clock: AdvertisementClock = AdvertisementClock.Default,
    ) = AdvertisementService(session, session, store, serviceScope, debounce, minInterval, busyBackoff, appStateProvider, clock)

    suspend fun startMonitoring(service: AdvertisementService) {
        service.startEventMonitoring(radioId)
        assertTrue(advertWaitUntil { session.eventSubscriptionCount >= 1 }, "monitor subscribed")
    }

    fun installHandler(service: AdvertisementService, recorder: AdvertHandlerRecorder) =
        service.setDeltaSyncHandler { recorder.handle(it) }

    fun listen(service: AdvertisementService): AdvertListener {
        val events = service.events()
        val counter = AdvertEventCounter()
        return AdvertListener(service, radioId, counter, serviceScope.launch { events.collect(counter::note) })
    }

    /** Swift `let ok = await waitUntil { ... }; #expect(ok)`. */
    suspend fun eventually(message: String, timeout: Duration = 10.seconds, predicate: suspend () -> Boolean) =
        assertTrue(advertWaitUntil(timeout) { predicate() }, message)

    fun <T> async(block: suspend CoroutineScope.() -> T): Deferred<T> = testScope.async(block = block)

    /** Waits until [count] monitored events finished handling. */
    suspend fun awaitHandled(count: Int) =
        assertTrue(advertWaitUntil { session.handledEvents >= count }, "service handled $count event(s)")

    /** Waits until no delta-sync round is registered (Swift `deltaSyncTask == nil`). */
    suspend fun awaitIdle(service: AdvertisementService) =
        assertTrue(advertWaitUntil { service.deltaSyncTask == null }, "delta sync idle")
}

internal class AdvertListener(
    private val service: AdvertisementService,
    private val radioId: RadioId,
    val counter: AdvertEventCounter,
    private val job: Job,
) {
    /** Waits for the subscription to end after `finishEvents()` (Swift `listener.result`). */
    suspend fun join() = job.join()
    fun cancel() = job.cancel()

    /**
     * Ordering barrier replacing Swift's settle sleeps: pushes a sentinel through the service's own
     * FIFO broadcaster and waits for this listener to observe it, so every event the service yielded
     * before this call has been counted.
     */
    suspend fun sync() {
        val tag = SENTINEL_TAGS.incrementAndGet().toUInt()
        service.eventBroadcaster.yield(AdvertisementEvent.TraceSnrObserved(tag, 0.0, null, radioId))
        assertTrue(advertWaitUntil { tag in counter.sentinels }, "listener observed sentinel $tag")
    }

    private companion object {
        val SENTINEL_TAGS = java.util.concurrent.atomic.AtomicInteger()
    }
}

// MARK: - Fakes

internal class AdvertFakeSession : AdvertisingSessionOps, SessionEventStreaming {
    private val lock = Any()
    private val subscribers = LinkedHashMap<Long, Pair<EventFilter, Channel<MeshEvent>>>()
    private var nextId = 0L
    private var handled = 0
    private val requestedKeys = ArrayList<Bytes>()
    private val stubbedContacts = HashMap<Bytes, MeshContact>()

    val eventSubscriptionCount: Int get() = synchronized(lock) { subscribers.size }
    val handledEvents: Int get() = synchronized(lock) { handled }
    val getContactPublicKeys: List<Bytes> get() = synchronized(lock) { requestedKeys.toList() }

    fun setStubbedContact(contact: MeshContact, key: Bytes) = synchronized(lock) { stubbedContacts[key] = contact }

    /** Delivers to every matching subscription; `handledEvents` counts once the collector finished it. */
    fun yieldEvent(event: MeshEvent) = synchronized(lock) {
        subscribers.values.filter { it.first.matches(event) }.forEach { it.second.trySend(event) }
    }

    override val connectionState: Flow<ConnectionState> = emptyFlow()
    override fun events(): Flow<MeshEvent> = events(EventFilter { true })

    /** Registers eagerly at call time, like `MeshCoreSession.events(filter)`. */
    override fun events(filter: EventFilter): Flow<MeshEvent> {
        val channel = Channel<MeshEvent>(Channel.UNLIMITED)
        val id = synchronized(lock) { nextId++.also { subscribers[it] = filter to channel } }
        return flow {
            try {
                for (event in channel) {
                    emit(event)
                    synchronized(lock) { handled += 1 }
                }
            } finally {
                synchronized(lock) { subscribers.remove(id) }
            }
        }
    }

    override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): MeshEvent? =
        throw AssertionError("waitForEvent is not used by AdvertisementService")

    @Volatile var advertisementFailure: Exception? = null

    override suspend fun sendAdvertisement(flood: Boolean) {
        advertisementFailure?.let { throw it }
    }
    override suspend fun setName(name: String) = Unit
    override suspend fun setCoordinates(latitude: Double, longitude: Double) = Unit
    override suspend fun getContact(publicKey: Bytes): MeshContact? = synchronized(lock) {
        requestedKeys += publicKey
        stubbedContacts[publicKey]
    }
}

/** Swift `MockAppStateProvider`, including the non-cancellable parked foreground check. */
internal class AdvertFakeAppState(isInForeground: Boolean = true) : AppStateProvider {
    private val lock = Any()
    private var stubbed = isInForeground
    private var hang = false
    private var parked: Continuation<Unit>? = null
    private var waiting = false

    val isWaitingOnForegroundCheck: Boolean get() = synchronized(lock) { waiting }

    override suspend fun isInForeground(): Boolean {
        if (synchronized(lock) { hang.also { if (it) waiting = true } }) {
            suspendCoroutine { continuation ->
                val resumeNow = synchronized(lock) { if (hang) { parked = continuation; false } else true }
                if (resumeNow) continuation.resume(Unit)
            }
            synchronized(lock) { waiting = false }
        }
        return synchronized(lock) { stubbed }
    }

    fun setIsInForeground(value: Boolean) = synchronized(lock) { stubbed = value }
    fun hangForegroundChecks() = synchronized(lock) { hang = true }
    fun releaseForegroundCheck() {
        val continuation = synchronized(lock) {
            hang = false
            parked.also { parked = null }
        }
        continuation?.resume(Unit)
    }
}

/** Manual monotonic clock: sleepers resume only when [advance] passes their deadline. */
internal class AdvertManualClock : AdvertisementClock {
    private val lock = Any()
    private var current = Duration.ZERO
    private val sleepers = ArrayList<Pair<Duration, CancellableContinuation<Unit>>>()

    val sleeperDeadlines: List<Duration> get() = synchronized(lock) { sleepers.map { it.first } }

    override fun now(): Duration = synchronized(lock) { current }
    override fun wallNow(): Instant = Instant.now()

    override suspend fun sleepUntil(deadline: Duration) {
        if (deadline <= now()) return
        suspendCancellableCoroutine { continuation ->
            val entry = deadline to continuation
            continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(entry) } }
            val resumeNow = synchronized(lock) { if (deadline <= current) true else { sleepers += entry; false } }
            if (resumeNow) continuation.resume(Unit)
        }
    }

    fun advance(by: Duration) {
        val ready = synchronized(lock) {
            current += by
            sleepers.filter { it.first <= current }.also { sleepers.removeAll(it.toSet()) }
        }
        ready.forEach { it.second.resume(Unit) }
    }
}

// MARK: - Recorders (Swift test actors)

internal class AdvertHandlerRecorder(private val store: PersistenceStoreProtocol, private val radioId: RadioId) {
    private val lock = Any()
    private val calls = ArrayList<Boolean>()
    private val results = ArrayDeque<AdvertContactSyncOutcome>()
    private val persistFrames = ArrayDeque<ContactFrame>()

    val callCount: Int get() = synchronized(lock) { calls.size }
    val fullRefetchFlags: List<Boolean> get() = synchronized(lock) { calls.toList() }

    fun enqueueResult(outcome: AdvertContactSyncOutcome) = synchronized(lock) { results.addLast(outcome) }
    fun enqueuePersist(frame: ContactFrame) = synchronized(lock) { persistFrames.addLast(frame) }

    suspend fun handle(fullRefetch: Boolean): AdvertContactSyncOutcome {
        val (outcome, frame) = synchronized(lock) {
            calls += fullRefetch
            val outcome = results.removeFirstOrNull() ?: AdvertContactSyncOutcome.SYNCED
            // Only persist on success so a failed call cannot mask a missing re-merge of pending keys.
            outcome to if (outcome == AdvertContactSyncOutcome.SYNCED) persistFrames.removeFirstOrNull() else null
        }
        if (frame != null) advertTry { store.saveContact(radioId, frame) }
        return outcome
    }
}

internal class AdvertEventCounter {
    private val lock = Any()
    private var newContacts = 0
    private var contactUpdates = 0
    private var conversationChanges = 0
    private val adopted = ArrayList<UUID>()
    private var cleanups = 0
    private val cleanupIds = ArrayList<UUID>()
    private var storageFullChanges = 0
    private val sentinelTags = HashSet<UInt>()
    private val traces = ArrayList<AdvertisementEvent.TraceSnrObserved>()

    val newContactCount: Int get() = synchronized(lock) { newContacts }
    val contactUpdatedCount: Int get() = synchronized(lock) { contactUpdates }
    val conversationsChangedCount: Int get() = synchronized(lock) { conversationChanges }
    val adoptedContactIDs: List<UUID> get() = synchronized(lock) { adopted.toList() }
    val contactDeletedCleanupCount: Int get() = synchronized(lock) { cleanups }
    val contactDeletedCleanupIDs: List<UUID> get() = synchronized(lock) { cleanupIds.toList() }
    val nodeStorageFullChangedCount: Int get() = synchronized(lock) { storageFullChanges }
    val sentinels: Set<UInt> get() = synchronized(lock) { sentinelTags.toSet() }
    val traceObservations: List<AdvertisementEvent.TraceSnrObserved> get() = synchronized(lock) { traces.toList() }

    fun note(event: AdvertisementEvent) {
        synchronized(lock) { record(event) }
    }

    private fun record(event: AdvertisementEvent) {
        when (event) {
            is AdvertisementEvent.NewContactDiscovered -> newContacts += 1
            AdvertisementEvent.ContactUpdated -> contactUpdates += 1
            AdvertisementEvent.ConversationsChanged -> conversationChanges += 1
            is AdvertisementEvent.OrphanDirectMessagesAdopted -> adopted += event.contactIDs
            is AdvertisementEvent.ContactDeletedCleanup -> {
                cleanups += 1
                cleanupIds += event.contactIDs
            }
            is AdvertisementEvent.NodeStorageFullChanged -> storageFullChanges += 1
            is AdvertisementEvent.TraceSnrObserved -> {
                sentinelTags += event.tag
                traces += event
            }
            else -> Unit
        }
    }
}

/** Records `fullRefetch` flags from a custom delta-sync handler. */
internal class AdvertCallFlagRecorder {
    private val lock = Any()
    private val recorded = ArrayList<Boolean>()
    val flags: List<Boolean> get() = synchronized(lock) { recorded.toList() }
    fun note(fullRefetch: Boolean) = synchronized(lock) { recorded += fullRefetch }
}

/** Fails every round up to [cap]; the final failing round records [freshKey] mid-flight. */
internal class AdvertCapRoundInjector(private val service: AdvertisementService, private val freshKey: Bytes, private val cap: Int) {
    private val lock = Any()
    private var count = 0
    val calls: Int get() = synchronized(lock) { count }

    suspend fun handle(): AdvertContactSyncOutcome {
        val call = synchronized(lock) { ++count }
        if (call == cap) service.recordPendingAdvertKey(freshKey)
        return if (call > cap) AdvertContactSyncOutcome.SYNCED else AdvertContactSyncOutcome.FAILED
    }
}

internal class AdvertCommitMarker {
    @Volatile var committed = false
        private set
    fun markCommitted() { committed = true }
}

/**
 * Swift `HandlerHold`: a sticky one-shot gate built on a non-cancellable continuation, like Swift's
 * CheckedContinuation, so a cancelled round's handler still completes after release.
 */
internal class AdvertHandlerHold {
    private val lock = Any()
    private var continuation: Continuation<Unit>? = null
    private var waiting = false
    private var released = false

    val isWaiting: Boolean get() = synchronized(lock) { waiting }

    suspend fun waitUntilReleased() {
        if (synchronized(lock) { released.also { if (!it) waiting = true } }) return
        suspendCoroutine { cont ->
            val resumeNow = synchronized(lock) { if (released) true else { continuation = cont; false } }
            if (resumeNow) cont.resume(Unit)
        }
        synchronized(lock) { waiting = false }
    }

    fun release() {
        val cont = synchronized(lock) {
            released = true
            continuation.also { continuation = null }
        }
        cont?.resume(Unit)
    }
}
