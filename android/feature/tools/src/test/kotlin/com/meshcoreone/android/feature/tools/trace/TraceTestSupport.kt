// AndroidOnly: WP-314 JVM test support: source-case annotation, main-queue dispatcher, virtual time and fixture fakes.
package com.meshcoreone.android.feature.tools.trace

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TracePathPersisting
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SavedTracePathDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TracePathRunDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MessageSentInfo
import com.meshcoreone.android.core.protocol.event.TraceInfo
import com.meshcoreone.android.core.protocol.event.TraceNode
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Binds a test to an original Swift case id (`Suite::name()` in the frozen Swift sources). The method name
 * is a JVM-safe rendering; this id is exact.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
@Repeatable
internal annotation class OriginalCase(val value: String, val disposition: String = "source-behavior")

/** Single-threaded, non-reentrant queue: models the source's `@MainActor` (resumes run later, never inline). */
internal class MainQueue : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queue.addLast(block)
    }

    fun runCurrent() {
        var steps = 0
        while (queue.isNotEmpty()) {
            check(++steps < 100_000) { "Main queue did not settle" }
            queue.removeFirst().run()
        }
    }
}

/** Virtual clock: sleepers wake only when the test advances time. No real waiting. */
internal class VirtualTime(start: Instant, private val main: MainQueue) : TraceTimeSource {
    private class Sleeper(val wakeAt: Instant, val sequence: Long, val continuation: CancellableContinuation<Unit>)

    private var nowValue = start
    private var sequence = 0L
    private val sleepers = mutableListOf<Sleeper>()

    override fun now(): Instant = nowValue

    override suspend fun sleep(duration: Duration) {
        suspendCancellableCoroutine { continuation ->
            val sleeper = Sleeper(nowValue.plus(duration.toJavaDuration()), sequence++, continuation)
            sleepers += sleeper
            continuation.invokeOnCancellation { sleepers.remove(sleeper) }
        }
    }

    val pendingSleepers: Int get() = sleepers.size

    /** Moves time forward, waking due sleepers in order and draining the main queue after each. */
    fun advanceBy(duration: Duration) {
        val target = nowValue.plus(duration.toJavaDuration())
        main.runCurrent()
        while (true) {
            val next = sleepers.filter { !it.wakeAt.isAfter(target) }
                .minWithOrNull(compareBy<Sleeper>({ it.wakeAt }, { it.sequence })) ?: break
            sleepers.remove(next)
            nowValue = next.wakeAt
            next.continuation.resume(Unit)
            main.runCurrent()
        }
        nowValue = target
        main.runCurrent()
    }
}

internal class RecordingDiagnostics : TraceDiagnostics {
    val failures = mutableListOf<Pair<String, Exception>>()
    override fun failure(operation: String, error: Exception) {
        failures += operation to error
    }
}

internal class MapRecentsStorage : RecentHopsStorage {
    val values = mutableMapOf<String, List<String>>()
    override fun stringList(key: String): List<String>? = values[key]
    override fun setStringList(key: String, value: List<String>) {
        values[key] = value
    }
}

/** English copy from `MC1/Resources/Localization/en.lproj` (keys noted per member). */
internal object EnglishTraceStrings : TracePathStrings {
    override val myDevice = "My Device" // contacts.results.hop.myDevice
    override val sendFailed = "Failed to send trace packet" // contacts.trace.error.sendFailed
    override val noResponse = "No response received" // contacts.trace.error.noResponse
    override fun allFailed(count: Int) = "All $count traces failed" // contacts.trace.error.allFailed
    override fun codeInvalidFormat(codes: String) = "Invalid format: $codes" // contacts.codeInput.error.invalidFormat
    override fun codeNotFound(codes: String) = "$codes not found" // contacts.codeInput.error.notFound
    override fun codeAlreadyInPath(codes: String) = "$codes already in path" // contacts.codeInput.error.alreadyInPath
    override fun pathNamePrefix(prefix: String) = "Path $prefix" // contacts.pathName.prefix
    override fun pathNameTwoEndpoints(first: String, last: String) = "$first → $last"
    override fun pathNameMultipleEndpoints(first: String, last: String) = "$first → ... → $last"
    override val defaultMapPathName = "Path" // contacts.trace.map.defaultPathName
}

internal class FakeNodeDirectory(
    var contacts: List<ContactDTO> = emptyList(),
    var nodes: List<DiscoveredNodeDTO> = emptyList(),
    var failure: Exception? = null,
) : TraceNodeDirectory {
    override suspend fun fetchContacts(radioId: RadioId): List<ContactDTO> {
        failure?.let { throw it }
        return contacts
    }

    override suspend fun fetchDiscoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
        failure?.let { throw it }
        return nodes
    }
}

/** Records each trace command; answers with [suggestedTimeoutMs] or throws [failure]. */
internal class FakeTraceSender(var suggestedTimeoutMs: UInt = 0u, var failure: Exception? = null) : TraceSender {
    data class Sent(val tag: UInt, val authCode: UInt, val flags: UByte, val path: Bytes)

    val sent = mutableListOf<Sent>()

    override suspend fun sendTrace(tag: UInt, authCode: UInt, flags: UByte, path: Bytes): MessageSentInfo {
        sent += Sent(tag, authCode, flags, path)
        failure?.let { throw it }
        return MessageSentInfo(0u, Bytes.of(0, 0, 0, 0), suggestedTimeoutMs)
    }
}

/** In-memory `TracePathPersisting` with the source store's create/append/fetch semantics. */
internal class InMemoryTracePathStore(private val clock: () -> Instant) : TracePathPersisting {
    val paths = linkedMapOf<UUID, SavedTracePathDTO>()
    var failure: Exception? = null

    override suspend fun fetchSavedTracePaths(radioId: RadioId): SnapshotList<SavedTracePathDTO> {
        failure?.let { throw it }
        return paths.values.filter { it.radioId == radioId }.snapshot()
    }

    override suspend fun fetchSavedTracePath(key: EntityKey): SavedTracePathDTO? {
        failure?.let { throw it }
        return paths[key.id]?.takeIf { it.radioId == key.radioId }
    }

    override suspend fun createSavedTracePath(
        radioId: RadioId, name: String, pathBytes: Bytes, hashSize: Long, initialRun: TracePathRunDTO?,
    ): SavedTracePathDTO {
        failure?.let { throw it }
        val path = SavedTracePathDTO(UUID.randomUUID(), radioId, name, pathBytes, hashSize, clock(), listOfNotNull(initialRun).snapshot())
        paths[path.id] = path
        return path
    }

    override suspend fun updateSavedTracePathName(key: EntityKey, name: String) {
        failure?.let { throw it }
        paths[key.id]?.let { paths[key.id] = it.copy(name = name) }
    }

    override suspend fun deleteSavedTracePath(key: EntityKey) {
        failure?.let { throw it }
        paths.remove(key.id)
    }

    override suspend fun appendTracePathRun(path: EntityKey, run: TracePathRunDTO) {
        failure?.let { throw it }
        paths[path.id]?.let { paths[path.id] = it.copy(runs = (it.runs + run).snapshot()) }
    }

    fun insert(path: SavedTracePathDTO) {
        paths[path.id] = path
    }
}

/**
 * Multicast trace-response fan-out with the WP-209 broadcaster's contract: registration happens
 * when [subscribe] is called, and [finish] ends every subscriber and later subscriptions.
 */
internal class TraceResponseBroadcaster {
    private val subscribers = mutableListOf<Channel<TraceResponse>>()
    private var finished = false

    fun subscribe(): Flow<TraceResponse> {
        val channel = Channel<TraceResponse>(Channel.UNLIMITED)
        if (finished) channel.close() else subscribers += channel
        return flow {
            try {
                for (event in channel) emit(event)
            } finally {
                subscribers.remove(channel)
                channel.cancel()
            }
        }
    }

    fun yield(event: TraceResponse) {
        subscribers.toList().forEach { it.trySend(event) }
    }

    fun finish() {
        finished = true
        subscribers.toList().forEach { it.close() }
        subscribers.clear()
    }
}

internal class FakeTraceDependencies : TracePathFeatureDependencies {
    var device: DeviceDTO? = null
    var location: Coordinate? = null
    var directory: TraceNodeDirectory? = null
    var store: TracePathPersisting? = null
    var sender: TraceSender? = null
    var responses: () -> Flow<TraceResponse>? = { null }

    override fun connectedDevice(): DeviceDTO? = device
    override fun bestAvailableLocation(): Coordinate? = location
    override fun nodeDirectory(): TraceNodeDirectory? = directory
    override fun savedPaths(): TracePathPersisting? = store
    override fun traceSender(): TraceSender? = sender
    override fun traceResponses(): Flow<TraceResponse>? = responses()
}

/** One holder on a main queue and virtual clock. Swift cases with an unconfigured view model skip [configure]. */
internal class TraceHarness(configureDependencies: Boolean = false) {
    val main = MainQueue()
    val time = VirtualTime(T0, main)
    val scope = CoroutineScope(SupervisorJob() + main)
    val diagnostics = RecordingDiagnostics()
    val recents = MapRecentsStorage()
    val deps = FakeTraceDependencies()
    private var tags = 0x1000u
    val holder = TracePathStateHolder(scope, time, EnglishTraceStrings, recents, diagnostics, tagSource = { tags++ })
    val state: TracePathState get() = holder.state.value

    init {
        if (configureDependencies) holder.configure(deps)
    }

    /** Runs a suspend call to completion on the main queue; fails if it is still suspended. */
    fun <T> complete(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        scope.launch { outcome = runCatching { block() } }
        main.runCurrent()
        return checkNotNull(outcome) { "Call is still suspended" }.getOrThrow()
    }

    /** Starts a suspend call that is expected to wait (batch runs). */
    fun start(block: suspend () -> Unit): Job = scope.launch { block() }.also { main.runCurrent() }

    fun respond(tag: UInt, path: List<TraceNode>, radioId: RadioId? = null) {
        holder.handleTraceResponse(traceInfo(tag, path), radioId)
        main.runCurrent()
    }
}

internal val T0: Instant = Instant.ofEpochSecond(1_700_000_000)
internal val RADIO: RadioId = RadioId(UUID.fromString("00000000-0000-0000-0000-0000000000a1"))

internal fun key(vararg prefix: Int): Bytes = Bytes(ByteArray(32) { index -> if (index < prefix.size) prefix[index].toByte() else 0 })

internal fun contact(
    vararg prefix: Int,
    name: String,
    type: ContactType = ContactType.REPEATER,
    lastAdvertTimestamp: UInt = 0u,
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    isFavorite: Boolean = false,
    lastHeardTimestamp: UInt? = 0u,
): ContactDTO = ContactDTO(
    radioId = RadioId(UUID.randomUUID()), publicKey = key(*prefix), name = name, typeRawValue = type.rawValue,
    outPathLength = 0u, lastAdvertTimestamp = lastAdvertTimestamp, latitude = latitude, longitude = longitude,
    lastHeardTimestamp = lastHeardTimestamp, isFavorite = isFavorite,
)

internal fun discovered(
    publicKey: Bytes,
    name: String,
    lastAdvertTimestamp: UInt = 0u,
    lastHeard: Instant = T0,
    latitude: Double = 0.0,
    longitude: Double = 0.0,
    type: ContactType = ContactType.REPEATER,
): DiscoveredNodeDTO = DiscoveredNodeDTO(
    UUID.randomUUID(), RadioId(UUID.randomUUID()), publicKey, name, type.rawValue, lastHeard,
    lastAdvertTimestamp, latitude, longitude, 0u, Bytes.EMPTY, null, null,
)

internal fun savedPath(
    bytes: Bytes,
    hashSize: Long = 1,
    runs: List<TracePathRunDTO> = emptyList(),
    radioId: RadioId = RadioId(UUID.randomUUID()),
): SavedTracePathDTO = SavedTracePathDTO(UUID.randomUUID(), radioId, "Test Path", bytes, hashSize, T0, runs.snapshot())

internal fun run(date: Instant, roundTripMs: Long = 100, success: Boolean = true): TracePathRunDTO =
    TracePathRunDTO(
        UUID.randomUUID(), date, success, if (success) roundTripMs else 0,
        if (success) SnapshotList.of(5.0, 3.0, -2.0) else SnapshotList.empty(),
    )

internal fun device(
    firmwareVersion: UByte = 8u,
    firmwareVersionString: String = "v1.11.0",
    pathHashMode: UByte = 0u,
    radioId: RadioId = RADIO,
    nodeName: String = "TestDevice",
): DeviceDTO = DeviceDTO(
    radioId = radioId, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = nodeName,
    firmwareVersion = firmwareVersion, firmwareVersionString = firmwareVersionString,
    manufacturerName = "TestMfg", buildDate = "01 Jan 2025", pathHashMode = pathHashMode, lastConnected = T0,
)

internal fun node(hash: Int?, snr: Double): TraceNode = TraceNode.fromHash(hash?.toUByte(), snr)

internal fun traceInfo(tag: UInt, path: List<TraceNode>): TraceInfo =
    TraceInfo(tag, 0u, 0u, (path.size - 1).coerceAtLeast(0).toUByte(), path)

internal fun hop(snr: Double, start: Boolean = false, end: Boolean = false, lat: Double? = null, lon: Double? = null, name: String? = null, hash: Bytes? = null): TraceHop =
    TraceHop(if (start || end) null else hash ?: Bytes.of(0xAA), name, snr, start, end, lat, lon)

internal fun result(hops: List<TraceHop>, durationMs: Long = 100, success: Boolean = true, path: Bytes = Bytes.of(0xAA), hashSize: Int = 1): TraceResult =
    TraceResult(hops.snapshot(), durationMs, success, if (success) null else "Failed", path, hashSize)
