// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerResyncLoopTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.RadioId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** A retry harness where every real resync fails at the contact phase (Swift: transport not connected). */
internal class ResyncHarness(
    val controller: SyncRetryController,
    val host: FakeSyncRetryHost,
    val services: SyncRetryServices,
    val radioId: RadioId,
)

internal suspend fun SyncTestScope.makeResyncHarness(): ResyncHarness {
    val radioId = newRadio()
    val fakes = SyncTestServices(SyncInMemoryStore.createTestDataStore(radioId)).apply {
        contactService.stubbedSyncContactsResult = Result.failure(SyncCoordinatorError.NotConnected())
    }
    val services = SyncRetryServices(coordinator(), fakes.dependencies(), fakes.remoteNode)
    val (controller, host) = retryController()
    host.connectionState = DeviceConnectionState.SYNCING
    host.currentServices = services
    host.connectionIntent = ConnectionIntent.WantsConnection()
    return ResyncHarness(controller, host, services, radioId)
}

/** Original ConnectionManagerResyncLoopTests. */
class ConnectionManagerResyncLoopTests {
    private val suite = "ConnectionManagerResyncLoopTests"
    private fun case(name: String, body: suspend SyncTestScope.() -> Unit) = syncCase(suite, name, body)

    @TestFactory
    fun cases(): List<DynamicTest> = listOf(
        case("Cancelling resync loop closes the activity bracket with succeeded=false") {
            val h = makeResyncHarness()
            val started = CallTracker()
            val values = ValueTracker<Boolean>()
            h.services.syncCoordinator.setSyncActivityCallbacks({ started.markCalled() }, { values.record(it) }, {})
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("beginResyncActivity should fire") { started.wasCalled }
            h.controller.cancelResyncLoop()
            waitUntil("endResyncActivity should fire after cancellation") { values.values.isNotEmpty() }
            assertEquals(false, values.values.last(), "Cancelled resync should report succeeded=false")
        },
        case("Resync loop exits and closes bracket when connectionIntent changes") {
            val h = makeResyncHarness()
            val started = CallTracker()
            val values = ValueTracker<Boolean>()
            h.services.syncCoordinator.setSyncActivityCallbacks({ started.markCalled() }, { values.record(it) }, {})
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("beginResyncActivity should fire") { started.wasCalled }
            h.host.connectionIntent = ConnectionIntent.UserDisconnected
            waitUntil("endResyncActivity should fire after guard exit", 5.seconds) { values.values.isNotEmpty() }
            assertEquals(false, values.values.last(), "Guard exit should report succeeded=false")
        },
        case("Max resync attempts triggers disconnect and onResyncFailed") {
            val h = makeResyncHarness()
            val values = ValueTracker<Boolean>()
            h.services.syncCoordinator.setSyncActivityCallbacks({}, { values.record(it) }, {})
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("onResyncFailed should fire after max attempts", 15.seconds) { h.host.resyncFailed.wasCalled }
            assertEquals(DeviceConnectionState.DISCONNECTED, h.host.connectionState)
            assertEquals(false, values.values.last(), "Max-attempts bracket should close with succeeded=false")
            assertFalse(values.values.contains(true), "No resync iteration should report success")
        },
        case("Each resync attempt opens and closes an inner bracket") {
            val h = makeResyncHarness()
            val starts = CallTracker()
            val ends = CallTracker()
            h.services.syncCoordinator.setSyncActivityCallbacks({ starts.markCalled() }, { ends.markCalled() }, {})
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("resync loop should exhaust", 15.seconds) { h.host.resyncFailed.wasCalled }
            settle()
            assertEquals(4, starts.callCount, "Expected 1 outer + 3 inner activity starts")
            assertEquals(4, ends.callCount, "Expected 3 inner + 1 outer activity ends")
        },
        case("Resync success promotes .syncing → .ready") {
            val h = makeResyncHarness()
            h.services.syncCoordinator.setPerformResyncOverride { _, _ -> true }
            val values = ValueTracker<Boolean>()
            h.services.syncCoordinator.setSyncActivityCallbacks({}, { values.record(it) }, {})
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("endResyncActivity should fire with success", 10.seconds) { values.values.contains(true) }
            settle()
            assertEquals(DeviceConnectionState.READY, h.host.connectionState)
        },
        case("Resync success calls onDeviceSynced") {
            val h = makeResyncHarness()
            h.services.syncCoordinator.setPerformResyncOverride { _, _ -> true }
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("onDeviceSynced should fire after resync success", 10.seconds) { h.host.deviceSynced.wasCalled }
            assertTrue(h.host.deviceSynced.wasCalled)
        },
        case("Disconnect during .syncing transitions to .disconnected and closes bracket") {
            val h = makeResyncHarness()
            val started = CallTracker()
            val values = ValueTracker<Boolean>()
            h.services.syncCoordinator.setSyncActivityCallbacks({ started.markCalled() }, { values.record(it) }, {})
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("beginResyncActivity should fire") { started.wasCalled }
            h.host.disconnect("userInitiated")
            waitUntil("endResyncActivity should fire after disconnect") { values.values.isNotEmpty() }
            assertEquals(DeviceConnectionState.DISCONNECTED, h.host.connectionState)
            assertEquals(false, values.values.last())
        },
        case("Superseded resync loop cannot disconnect a healthy successor connection") {
            val h = makeResyncHarness()
            val staleCalls = CallTracker()
            h.services.syncCoordinator.setPerformResyncOverride { _, _ ->
                staleCalls.markCalled()
                false
            }
            h.controller.startResyncLoop(h.radioId, h.services)
            // A later reconnect installs a healthy successor without cancelling the cycle-N loop.
            val successor = SyncTestServices(SyncInMemoryStore.createTestDataStore(h.radioId))
            val successorServices = SyncRetryServices(coordinator(), successor.dependencies(), successor.remoteNode)
            h.host.connectionState = DeviceConnectionState.READY
            h.host.currentServices = successorServices
            waitUntil("orphaned resync loop should stop", 12.seconds) { h.controller.resyncTask == null }
            assertEquals(0, staleCalls.callCount, "Superseded loop must not resync against the replaced container")
            assertFalse(h.host.resyncFailed.wasCalled, "Superseded loop must not trigger resync-failure teardown")
            assertEquals(DeviceConnectionState.READY, h.host.connectionState, "Healthy successor connection must remain operational")
            assertSame(successorServices, h.host.currentServices, "Healthy successor container must stay installed")
        },
        case("Cancelling the resync loop stops further resync attempts") {
            val h = makeResyncHarness()
            val calls = CallTracker()
            h.services.syncCoordinator.setPerformResyncOverride { _, _ ->
                calls.markCalled()
                false
            }
            h.controller.startResyncLoop(h.radioId, h.services)
            waitUntil("first resync attempt should run", 6.seconds) { calls.wasCalled }
            h.controller.cancelResyncLoop()
            val countAtCancel = calls.callCount
            assertNull(h.controller.resyncTask, "Cancellation must clear the stored resync task")
            sleep(3.seconds)
            assertEquals(countAtCancel, calls.callCount, "Cancelled loop must not perform further resync attempts")
        },
    )
}
