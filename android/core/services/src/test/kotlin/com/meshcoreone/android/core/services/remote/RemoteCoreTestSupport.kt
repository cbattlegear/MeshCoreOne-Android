// AndroidOnly: WP-210 Deterministic virtual clock, single-threaded harness and original-case labels for RemoteNodeService tests without kotlinx-coroutines-test.
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.session.SessionClock
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicTest
import kotlin.test.fail

/**
 * Virtual [SessionClock]: `now` only moves when a test calls [advance], and sleepers wake in deadline
 * order. The wall clock is [epoch] plus virtual time, so wire timestamps are exact.
 */
class RemoteCoreTestClock(private val epoch: Instant = Instant.ofEpochSecond(1_786_722_487)) : SessionClock {
    private class Sleeper(val deadline: Duration, val continuation: CancellableContinuation<Unit>)

    private val lock = Any()
    private var elapsed = Duration.ZERO
    private val sleepers = mutableListOf<Sleeper>()

    override val now: Duration get() = synchronized(lock) { elapsed }

    override val wallClock: Clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = epoch.plusNanos(now.inWholeNanoseconds)
    }

    val sleeperCount: Int get() = synchronized(lock) { sleepers.size }

    override suspend fun sleepFor(duration: Duration) {
        currentCoroutineContext().ensureActive()
        if (duration <= Duration.ZERO) {
            yield()
            return
        }
        suspendCancellableCoroutine { continuation ->
            val sleeper = synchronized(lock) { Sleeper(elapsed + duration, continuation).also { sleepers += it } }
            continuation.invokeOnCancellation { synchronized(lock) { sleepers.remove(sleeper) } }
        }
    }

    /** Moves virtual time forward and wakes every sleeper whose deadline has passed. */
    fun advance(by: Duration) {
        val due = synchronized(lock) {
            elapsed += by
            sleepers.filter { it.deadline <= elapsed }.sortedBy { it.deadline }.also { sleepers.removeAll(it) }
        }
        due.forEach { it.continuation.resume(Unit) }
    }
}

/** Lets every coroutine queued on the single test thread run until the queue drains. */
suspend fun remoteCoreSettle(rounds: Int = 200) = repeat(rounds) { yield() }

/** Yields until [condition] holds; fails with [description] instead of hanging. No wall-clock waits. */
suspend fun remoteCoreAwait(description: String, condition: () -> Boolean) {
    repeat(20_000) {
        if (condition()) return
        yield()
    }
    if (!condition()) fail(description)
}

/** Advances [clock] in [step]s, settling between steps, until [condition] holds. */
suspend fun remoteCoreAdvanceUntil(
    clock: RemoteCoreTestClock, description: String, step: Duration = 100.milliseconds, maxSteps: Int = 2_000,
    condition: () -> Boolean,
) {
    repeat(maxSteps) {
        remoteCoreSettle()
        if (condition()) return
        clock.advance(step)
    }
    remoteCoreSettle()
    if (!condition()) fail(description)
}

/** Original Swift case: display name `Suite::name()` (or the given parameter signature). */
fun remoteCoreOriginal(
    suite: String, name: String, signature: String = "()", body: suspend CoroutineScope.() -> Unit,
): DynamicTest = DynamicTest.dynamicTest("$suite::$name$signature") { remoteCoreRun(body) }

/** Android-native WP-210 case (cancellation, concurrency, adapters). */
fun remoteCoreNative(name: String, body: suspend CoroutineScope.() -> Unit): DynamicTest =
    DynamicTest.dynamicTest("WP-210::$name") { remoteCoreRun(body) }

/** Single-threaded event loop; the real-time timeout is only a hang guard, never synchronization. */
private fun remoteCoreRun(body: suspend CoroutineScope.() -> Unit) = runBlocking {
    withTimeout(60.seconds) { body() }
}

/** Service wired to fakes on the test's own single-threaded dispatcher. */
class RemoteCoreHarness(dispatcher: CoroutineContext) {
    val radioId = RadioId(UUID.randomUUID())
    val clock = RemoteCoreTestClock()
    val session = RemoteCoreFakeSession(clock)
    val store = RemoteCoreFakeStore()
    val passwords = RemoteCoreFakePasswordStore()
    val scope = CoroutineScope(SupervisorJob() + dispatcher)
    var floodFallbackEnabled: suspend () -> Boolean = { true }
    var recoveryOwnerCurrent = true
    val service = RemoteNodeService(
        session, store, passwords, scope, clock,
        isFloodFallbackEnabled = { floodFallbackEnabled() },
        isRecoveryOwnerCurrent = { recoveryOwnerCurrent },
    )

    /** Saves a session row and returns its key. */
    suspend fun addSession(dto: RemoteNodeSessionDTO): EntityKey {
        store.saveRemoteNodeSessionDTO(dto)
        return EntityKey(dto.radioId, dto.id)
    }

    /** Starts event monitoring and waits until the monitor subscribed. */
    suspend fun startMonitoring() {
        service.startEventMonitoring()
        remoteCoreAwait("event monitor never subscribed") { session.eventSubscriptionCount == 1 }
    }

    /** Pushes a CLI-typed contact message from [publicKey]'s node. */
    fun yieldReply(text: String, publicKey: Bytes) = session.yieldEvent(
        MeshEvent.ContactMessageReceived(
            ContactMessage(publicKey.prefix(6), 0u, CLI_RESPONSE_TEXT_TYPE, clock.wallClock.instant(), null, text, null),
        ),
    )

    fun close() {
        service.close()
        scope.cancel()
    }
}

/** Runs [block] with a fresh [RemoteCoreHarness] that is always closed. */
suspend fun <T> withRemoteCoreHarness(block: suspend RemoteCoreHarness.() -> T): T {
    val dispatcher = checkNotNull(currentCoroutineContext()[ContinuationInterceptor]) { "Test needs a dispatcher" }
    val harness = RemoteCoreHarness(dispatcher)
    try {
        return harness.block()
    } finally {
        harness.close()
    }
}

/** Swift `RemoteNodeSessionDTO.testSession` defaults. */
fun remoteCoreSession(
    radioId: RadioId,
    publicKey: Bytes = remoteCoreKey(0xCC),
    role: RemoteNodeRole = RemoteNodeRole.ROOM_SERVER,
    permissionLevel: RoomPermissionLevel = RoomPermissionLevel.GUEST,
    isConnected: Boolean = false,
    lastSyncTimestamp: UInt = 0u,
): RemoteNodeSessionDTO = RemoteNodeSessionDTO(
    radioId = radioId, publicKey = publicKey, name = "TestNode", role = role,
    isConnected = isConnected, permissionLevel = permissionLevel, lastSyncTimestamp = lastSyncTimestamp,
)

/** Swift `ContactDTO.testContact` defaults (flood-routed chat contact). */
fun remoteCoreContact(
    radioId: RadioId,
    publicKey: Bytes,
    typeRawValue: UByte = ContactType.CHAT.rawValue,
    outPathLength: UByte = 0xFFu,
    outPath: Bytes = Bytes.EMPTY,
): ContactDTO = ContactDTO(
    radioId = radioId, publicKey = publicKey, name = "TestContact", typeRawValue = typeRawValue,
    outPathLength = outPathLength, outPath = outPath, lastHeardTimestamp = null,
)

fun remoteCoreKey(byte: Int): Bytes = Bytes(ByteArray(32) { byte.toByte() })

/** The service's background job tree, for assertions that nothing is left running. */
val RemoteCoreHarness.activeServiceJobs: List<Job>
    get() = scope.coroutineContext[Job]?.children?.flatMap { it.children.toList() }?.filter { it.isActive }?.toList().orEmpty()
