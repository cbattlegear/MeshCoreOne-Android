// PortedFrom: MC1Services/Tests/MC1ServicesTests/BLEReconnectionCoordinatorTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

private class Delegate : BLEReconnectionDelegate {
    override var connectionIntent: ConnectionIntent = ConnectionIntent.WantsConnection()
    var state = DeviceConnectionState.READY
    override val connectionState get() = state
    val order = mutableListOf<String>()
    val rebuilds = mutableListOf<UUID>()
    var teardowns = 0
    var disconnects = 0
    var notifications = 0
    var autoNotifications = 0
    var failures = 0
    var cleared = false
    var auto = false
    var throwsRemaining = 0
    var rebuildHook: suspend () -> Unit = {}
    var failureHook: suspend () -> Unit = {}
    var queryHook: suspend () -> Unit = {}
    var notifyHook: suspend () -> Unit = {}
    override fun setConnectionState(state: DeviceConnectionState) { this.state = state }
    override fun clearConnectedDevice() { cleared = true }
    override suspend fun teardownSessionForReconnect() { teardowns++; order += "teardown" }
    override suspend fun rebuildSession(deviceId: UUID) {
        rebuilds += deviceId
        rebuildHook()
        if (throwsRemaining > 0) { throwsRemaining--; throw IllegalStateException("rebuild failure") }
    }
    override suspend fun disconnectTransport() { disconnects++ }
    override suspend fun notifyAutoReconnectStarted() { autoNotifications++; order += "notifyAutoReconnectStarted"; notifyHook() }
    override suspend fun notifyConnectionLost() { notifications++ }
    override suspend fun handleReconnectionFailure() { failures++; failureHook() }
    override suspend fun isTransportAutoReconnecting(): Boolean { queryHook(); return auto }
}

@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun TestScope.withCoordinator(
    timeout: Duration = 10.seconds, maximum: Duration = 60.seconds,
    assertions: suspend (BLEReconnectionCoordinator, Delegate) -> Unit,
) {
    val delegate = Delegate()
    val coordinator = BLEReconnectionCoordinator(delegate, backgroundScope, TestClock(testScheduler), RuntimeIssueReporter {}, timeout, maximum)
    try { assertions(coordinator, delegate) } finally { coordinator.close() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SourceReconnectionCoordinatorTest {
    @TestFactory
    fun sourceCases() = listOf(
        original("BLEReconnectionCoordinatorTests", "entering auto-reconnect sets state to .connecting when user wants connection") {
            withCoordinator { c, d -> c.handleEnteringAutoReconnect(UUID.randomUUID()); assertEquals(DeviceConnectionState.CONNECTING, d.connectionState) }
        },
        original("BLEReconnectionCoordinatorTests", "entering auto-reconnect tears down session") {
            withCoordinator { c, d -> c.handleEnteringAutoReconnect(UUID.randomUUID()); assertEquals(1, d.teardowns) }
        },
        original("BLEReconnectionCoordinatorTests", "entering auto-reconnect notifies the loss before tearing down the session") {
            withCoordinator { c, d ->
                c.handleEnteringAutoReconnect(UUID.randomUUID()); assertEquals(1, d.autoNotifications)
                assertEquals(listOf("notifyAutoReconnectStarted", "teardown"), d.order)
            }
        },
        original("BLEReconnectionCoordinatorTests", "entering auto-reconnect does not notify the loss when the user disconnected") {
            withCoordinator { c, d ->
                d.connectionIntent = ConnectionIntent.UserDisconnected; d.state = DeviceConnectionState.DISCONNECTED
                c.handleEnteringAutoReconnect(UUID.randomUUID()); assertEquals(0, d.autoNotifications)
            }
        },
        original("BLEReconnectionCoordinatorTests", "entering auto-reconnect is ignored when intent is .userDisconnected") {
            withCoordinator { c, d ->
                d.connectionIntent = ConnectionIntent.UserDisconnected; d.state = DeviceConnectionState.DISCONNECTED
                c.handleEnteringAutoReconnect(UUID.randomUUID())
                assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState); assertEquals(0, d.teardowns); assertEquals(1, d.disconnects)
            }
        },
        original("BLEReconnectionCoordinatorTests", "entering auto-reconnect is ignored when intent is .none") {
            withCoordinator { c, d ->
                d.connectionIntent = ConnectionIntent.None; d.state = DeviceConnectionState.DISCONNECTED
                c.handleEnteringAutoReconnect(UUID.randomUUID())
                assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState); assertEquals(1, d.disconnects)
            }
        },
        original("BLEReconnectionCoordinatorTests", "reconnection complete keeps state .connecting when claim matches") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); c.handleEnteringAutoReconnect(id); c.handleReconnectionComplete(id)
                assertEquals(DeviceConnectionState.CONNECTING, d.connectionState)
            }
        },
        original("BLEReconnectionCoordinatorTests", "reconnection complete calls rebuildSession when claim matches") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); c.handleEnteringAutoReconnect(id); c.handleReconnectionComplete(id)
                assertEquals(listOf(id), d.rebuilds)
            }
        },
        original("BLEReconnectionCoordinatorTests", "reconnection complete is ignored when no entry was claimed") {
            withCoordinator { c, d ->
                d.state = DeviceConnectionState.DISCONNECTED; c.handleReconnectionComplete(UUID.randomUUID())
                assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState); assertTrue(d.rebuilds.isEmpty())
            }
        },
        original("BLEReconnectionCoordinatorTests", "reconnection complete is ignored when intent is .userDisconnected") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); c.handleEnteringAutoReconnect(id); d.connectionIntent = ConnectionIntent.UserDisconnected
                c.handleReconnectionComplete(id); assertTrue(d.rebuilds.isEmpty()); assertEquals(1, d.disconnects); assertNull(c.reconnectingDeviceId)
            }
        },
        original("BLEReconnectionCoordinatorTests", "reconnection complete is ignored when already .ready") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); c.handleEnteringAutoReconnect(id); d.state = DeviceConnectionState.READY
                c.handleReconnectionComplete(id); assertEquals(DeviceConnectionState.READY, d.connectionState); assertTrue(d.rebuilds.isEmpty())
            }
        },
        original("BLEReconnectionCoordinatorTests", "reconnection complete is ignored when .syncing (session alive, resync running)") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); c.handleEnteringAutoReconnect(id); d.state = DeviceConnectionState.SYNCING
                c.handleReconnectionComplete(id); assertEquals(DeviceConnectionState.SYNCING, d.connectionState); assertTrue(d.rebuilds.isEmpty())
            }
        },
        original("BLEReconnectionCoordinatorTests", "reconnection complete handles rebuild failure") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); d.throwsRemaining = 2; c.handleEnteringAutoReconnect(id); c.handleReconnectionComplete(id)
                assertEquals(1, d.failures); assertEquals(listOf(id, id), d.rebuilds)
            }
        },
        original("BLEReconnectionCoordinatorTests", "cycle claim is held across first-fail retry gap until failure returns") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); var claimAtFailure: UUID? = null
                d.throwsRemaining = 2; d.failureHook = { claimAtFailure = c.reconnectingDeviceId }
                c.handleEnteringAutoReconnect(id)
                val work = backgroundScope.async { c.handleReconnectionComplete(id) }
                runCurrent(); assertEquals(1, d.rebuilds.size); assertEquals(id, c.reconnectingDeviceId)
                advanceTimeBy(2000); runCurrent(); work.await()
                assertEquals(id, claimAtFailure); assertEquals(1, d.failures); assertNull(c.reconnectingDeviceId)
            }
        },
        original("BLEReconnectionCoordinatorTests", "overlapping completions for the same device start only one rebuild") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); val release = CompletableDeferred<Unit>(); d.rebuildHook = { release.await() }
                c.handleEnteringAutoReconnect(id)
                val first = backgroundScope.async { c.handleReconnectionComplete(id) }; runCurrent()
                c.handleReconnectionComplete(id); assertEquals(1, d.rebuilds.size)
                release.complete(Unit); runCurrent(); first.await(); assertNull(c.reconnectingDeviceId)
            }
        },
        original("BLEReconnectionCoordinatorTests", "already-connected completion during rebuild does not drop cycle claim") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); d.throwsRemaining = 2; d.rebuildHook = { d.state = DeviceConnectionState.CONNECTED }
                var claimAtFailure: UUID? = null; d.failureHook = { claimAtFailure = c.reconnectingDeviceId }
                c.handleEnteringAutoReconnect(id)
                val first = backgroundScope.async { c.handleReconnectionComplete(id) }; runCurrent()
                c.handleReconnectionComplete(id); assertEquals(id, c.reconnectingDeviceId); assertEquals(1, d.rebuilds.size)
                advanceTimeBy(2000); runCurrent(); first.await(); assertEquals(id, claimAtFailure); assertEquals(1, d.failures)
            }
        },
        original("BLEReconnectionCoordinatorTests", "successful rebuild does not clear a newer cycle claim after supersession") {
            withCoordinator { c, d ->
                val a = UUID.randomUUID(); val b = UUID.randomUUID()
                c.handleEnteringAutoReconnect(a); d.rebuildHook = { c.handleEnteringAutoReconnect(b) }
                c.handleReconnectionComplete(a); assertEquals(b, c.reconnectingDeviceId)
            }
        },
        original("BLEReconnectionCoordinatorTests", "stale device completion does not cancel active timeout") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                c.handleEnteringAutoReconnect(UUID.randomUUID()); c.handleReconnectionComplete(UUID.randomUUID())
                runCurrent(); advanceTimeBy(1000); runCurrent()
                assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState); assertTrue(d.rebuilds.isEmpty())
            }
        },
        original("BLEReconnectionCoordinatorTests", "UI timeout transitions to disconnected after duration") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                c.handleEnteringAutoReconnect(UUID.randomUUID()); runCurrent(); advanceTimeBy(1000); runCurrent()
                assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState); assertTrue(d.cleared); assertEquals(1, d.notifications)
            }
        },
        original("BLEReconnectionCoordinatorTests", "UI timeout is cancelled when reconnection completes") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                val id = UUID.randomUUID(); c.handleEnteringAutoReconnect(id); c.handleReconnectionComplete(id)
                advanceTimeBy(2000); runCurrent(); assertEquals(DeviceConnectionState.CONNECTING, d.connectionState); assertEquals(0, d.notifications)
            }
        },
        original("BLEReconnectionCoordinatorTests", "stale rebuild retry is aborted when new reconnect cycle starts during delay") {
            withCoordinator { c, d ->
                val id = UUID.randomUUID(); d.throwsRemaining = 1; c.handleEnteringAutoReconnect(id)
                val first = backgroundScope.async { c.handleReconnectionComplete(id) }; runCurrent()
                assertEquals(1, d.rebuilds.size); c.handleEnteringAutoReconnect(id); c.handleReconnectionComplete(id)
                advanceTimeBy(2000); runCurrent(); first.await()
                assertEquals(2, d.rebuilds.size); assertEquals(0, d.failures)
            }
        },
        original("BLEReconnectionCoordinatorTests", "UI timeout disconnects when max connecting window exceeded") {
            withCoordinator(timeout = 1.seconds, maximum = 3.seconds) { c, d ->
                d.auto = true; c.handleEnteringAutoReconnect(UUID.randomUUID()); runCurrent(); advanceTimeBy(3000); runCurrent()
                assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState); assertEquals(1, d.notifications)
            }
        },
        original("BLEReconnectionCoordinatorTests", "same-device completion is accepted after UI timeout while transport auto-reconnects") {
            withCoordinator(timeout = 1.seconds, maximum = 3.seconds) { c, d ->
                val id = UUID.randomUUID(); d.auto = true; c.handleEnteringAutoReconnect(id)
                runCurrent(); advanceTimeBy(3000); runCurrent(); assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState)
                c.handleReconnectionComplete(id); assertEquals(listOf(id), d.rebuilds); assertEquals(DeviceConnectionState.CONNECTING, d.connectionState)
            }
        },
        original("BLEReconnectionCoordinatorTests", "UI timeout clears cycle when transport stops auto-reconnecting") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                val id = UUID.randomUUID(); c.handleEnteringAutoReconnect(id); runCurrent(); advanceTimeBy(1000); runCurrent()
                c.handleReconnectionComplete(id); assertTrue(d.rebuilds.isEmpty()); assertNull(c.reconnectingDeviceId)
            }
        },
        original("BLEReconnectionCoordinatorTests", "different-device completion is rejected after UI timeout") {
            withCoordinator(timeout = 1.seconds, maximum = 3.seconds) { c, d ->
                d.auto = true; c.handleEnteringAutoReconnect(UUID.randomUUID()); runCurrent(); advanceTimeBy(3000); runCurrent()
                c.handleReconnectionComplete(UUID.randomUUID())
                assertTrue(d.rebuilds.isEmpty()); assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState)
            }
        },
        original("BLEReconnectionCoordinatorTests", "user-disconnected completion after UI timeout remains rejected") {
            withCoordinator(timeout = 1.seconds, maximum = 3.seconds) { c, d ->
                val id = UUID.randomUUID(); d.auto = true; c.handleEnteringAutoReconnect(id); runCurrent(); advanceTimeBy(3000); runCurrent()
                d.connectionIntent = ConnectionIntent.UserDisconnected; c.handleReconnectionComplete(id)
                assertTrue(d.rebuilds.isEmpty()); assertEquals(1, d.disconnects)
            }
        },
        original("BLEReconnectionCoordinatorTests", "UI timeout re-arms if BLE is still auto-reconnecting") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                d.auto = true; c.handleEnteringAutoReconnect(UUID.randomUUID()); runCurrent(); advanceTimeBy(2000); runCurrent()
                assertEquals(DeviceConnectionState.CONNECTING, d.connectionState); assertEquals(0, d.notifications)
            }
        },
        original("BLEReconnectionCoordinatorTests", "UI timeout eventually disconnects when max window exceeded") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                d.auto = true; c.handleEnteringAutoReconnect(UUID.randomUUID()); runCurrent()
                advanceTimeBy(59_000); runCurrent(); assertEquals(DeviceConnectionState.CONNECTING, d.connectionState)
                advanceTimeBy(1000); runCurrent(); assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState)
            }
        },
        original("BLEReconnectionCoordinatorTests", "UI timeout fires normally when BLE is not auto-reconnecting") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                c.handleEnteringAutoReconnect(UUID.randomUUID()); runCurrent(); advanceTimeBy(1000); runCurrent()
                assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState); assertEquals(1, d.notifications)
            }
        },
        original("BLEReconnectionCoordinatorTests", "UI timeout aborts when reconnection completes during its transport query") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                val id = UUID.randomUUID(); c.handleEnteringAutoReconnect(id)
                d.queryHook = { d.queryHook = {}; c.handleReconnectionComplete(id) }
                runCurrent(); advanceTimeBy(1000); runCurrent()
                assertEquals(listOf(id), d.rebuilds); assertEquals(DeviceConnectionState.CONNECTING, d.connectionState)
                assertEquals(0, d.notifications); assertFalse(d.cleared)
            }
        },
        original("BLEReconnectionCoordinatorTests", "cancelTimeout prevents timeout from firing") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                c.handleEnteringAutoReconnect(UUID.randomUUID()); c.cancelTimeout()
                advanceTimeBy(2000); runCurrent(); assertEquals(DeviceConnectionState.CONNECTING, d.connectionState)
            }
        },
    )

    @TestFactory
    fun nativeReviewCases() = listOf(
        nativeCase("refreshing a claimed reconnect timeout does not replace its pending teardown receipt") {
            withCoordinator { c, d ->
                val gate = CompletableDeferred<Unit>()
                d.notifyHook = { gate.await() }
                val id = UUID.randomUUID()
                val entry = backgroundScope.async { c.handleEnteringAutoReconnect(id) }; runCurrent()
                val generation = c.reconnectGeneration
                c.restartTimeout(id)
                val completion = backgroundScope.async { c.handleReconnectionComplete(id) }; runCurrent()
                assertEquals(0, d.teardowns); assertTrue(d.rebuilds.isEmpty()); assertFalse(completion.isCompleted)
                assertEquals(generation + 1, c.reconnectGeneration)
                gate.complete(Unit); runCurrent(); entry.await(); completion.await()
                assertEquals(1, d.teardowns); assertEquals(listOf(id), d.rebuilds)
                assertNull(c.reconnectingDeviceId)
            }
        },
        nativeCase("completion invoked by a timeout query remains cancellation-active and releases the successful cycle") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                val id = UUID.randomUUID()
                d.rebuildHook = { currentCoroutineContext().ensureActive(); yield(); currentCoroutineContext().ensureActive() }
                c.handleEnteringAutoReconnect(id)
                d.queryHook = { d.queryHook = {}; c.handleReconnectionComplete(id) }
                runCurrent(); advanceTimeBy(1000); runCurrent()
                assertEquals(listOf(id), d.rebuilds); assertEquals(0, d.failures); assertNull(c.reconnectingDeviceId)
                assertEquals(0, d.notifications)
            }
        },
        nativeCase("a suspended older timeout query cannot clear the restarted same-cycle UI window") {
            withCoordinator(timeout = 1.seconds) { c, d ->
                val id = UUID.randomUUID(); val gate = CompletableDeferred<Unit>()
                d.queryHook = { gate.await() }
                c.handleEnteringAutoReconnect(id); runCurrent(); advanceTimeBy(1000); runCurrent()
                c.restartTimeout(id)
                gate.complete(Unit); runCurrent()
                assertEquals(DeviceConnectionState.CONNECTING, d.connectionState); assertEquals(0, d.notifications)
                advanceTimeBy(999); runCurrent(); assertEquals(DeviceConnectionState.CONNECTING, d.connectionState)
                advanceTimeBy(1); runCurrent(); assertEquals(DeviceConnectionState.DISCONNECTED, d.connectionState)
                assertEquals(1, d.notifications)
            }
        },
    )
}
