// AndroidOnly: WP-303 Native tests: permission revocation, durable stores and the disconnect reconciliation edge.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.DeviceEndpointIdentity
import com.meshcoreone.android.core.connectivity.permissions.ConnectivityPermission
import com.meshcoreone.android.core.connectivity.permissions.PermissionSnapshot
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.ui.StatusPillState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class PermissionRevocationGuardTest {
    private class Teardown(override var liveTransport: LiveTransport) : TransportTeardown {
        var closed = 0
        override suspend fun disconnectTransport() { closed += 1 }
    }

    private fun snapshot(vararg granted: ConnectivityPermission) = PermissionSnapshot(sdkInt = 36, granted = granted.toSet())
    private val all = arrayOf(ConnectivityPermission.BLUETOOTH_CONNECT, ConnectivityPermission.BLUETOOTH_SCAN,
        ConnectivityPermission.POST_NOTIFICATIONS, ConnectivityPermission.ACCESS_LOCAL_NETWORK)

    private fun guard(teardown: Teardown, vararg reads: PermissionSnapshot): PermissionRevocationGuard {
        val queue = ArrayDeque(reads.toList())
        return PermissionRevocationGuard({ queue.removeFirst() }, teardown)
    }

    @Test fun theFirstSnapshotIsOnlyABaseline() = runTest {
        val teardown = Teardown(LiveTransport.BLUETOOTH)
        val guard = guard(teardown, snapshot())
        assertTrue(guard.check().isEmpty())
        assertEquals(0, teardown.closed)
    }

    @Test fun aRevokedBluetoothGrantClosesALiveBluetoothTransport() = runTest {
        val teardown = Teardown(LiveTransport.BLUETOOTH)
        val guard = guard(teardown, snapshot(*all), snapshot(ConnectivityPermission.BLUETOOTH_SCAN, ConnectivityPermission.ACCESS_LOCAL_NETWORK))
        guard.check()
        assertEquals(setOf(ConnectivityPermission.BLUETOOTH_CONNECT, ConnectivityPermission.POST_NOTIFICATIONS), guard.check())
        assertEquals(1, teardown.closed)
    }

    @Test fun aRevocationThatDoesNotConcernTheLiveTransportLeavesItAlone() = runTest {
        val lan = Teardown(LiveTransport.LAN)
        val lanGuard = guard(lan, snapshot(*all), snapshot(ConnectivityPermission.BLUETOOTH_SCAN, ConnectivityPermission.ACCESS_LOCAL_NETWORK, ConnectivityPermission.POST_NOTIFICATIONS))
        lanGuard.check()
        assertEquals(setOf(ConnectivityPermission.BLUETOOTH_CONNECT), lanGuard.check())
        assertEquals(0, lan.closed, "losing Bluetooth does not close the LAN transport")

        val ble = Teardown(LiveTransport.BLUETOOTH)
        val bleGuard = guard(ble, snapshot(*all), snapshot(ConnectivityPermission.BLUETOOTH_CONNECT, ConnectivityPermission.BLUETOOTH_SCAN))
        bleGuard.check()
        assertEquals(setOf(ConnectivityPermission.POST_NOTIFICATIONS, ConnectivityPermission.ACCESS_LOCAL_NETWORK), bleGuard.check())
        assertEquals(0, ble.closed, "losing notifications or LAN access does not close Bluetooth")

        val idle = Teardown(LiveTransport.NONE)
        val idleGuard = guard(idle, snapshot(*all), snapshot())
        idleGuard.check(); idleGuard.check()
        assertEquals(0, idle.closed, "nothing live, nothing to close")
    }

    @Test fun aRevokedLocalNetworkGrantClosesALiveLanTransport() = runTest {
        val teardown = Teardown(LiveTransport.LAN)
        val guard = guard(teardown, snapshot(*all), snapshot(ConnectivityPermission.BLUETOOTH_CONNECT))
        guard.check(); guard.check()
        assertEquals(1, teardown.closed)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class DurableStoresTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    @Test fun associationRecordsSurviveANewStoreInstance() {
        val id = UUID.randomUUID(); val other = UUID.randomUUID()
        SharedPreferencesAssociationRecords(context).apply { remember(id, "Radio A"); remember(other, "Radio B") }
        val reopened = SharedPreferencesAssociationRecords(context)
        assertEquals(mapOf(id to "Radio A", other to "Radio B"), reopened.records())
        reopened.forget(id)
        assertEquals(mapOf(other to "Radio B"), SharedPreferencesAssociationRecords(context).records())
        reopened.forget(other)
    }

    @Test fun knownEndpointsRoundTripAndForget() = runBlocking<Unit> {
        val id = UUID.randomUUID()
        val store = SharedPreferencesKnownEndpoints(context)
        assertNull(store.endpoint(id))
        val withAssociation = BluetoothEndpoint(id, "C0:00:00:00:00:01", 7)
        store.remember(withAssociation)
        assertEquals(withAssociation, SharedPreferencesKnownEndpoints(context).endpoint(id))
        val without = BluetoothEndpoint(id, "C0:00:00:00:00:02", null)
        store.remember(without)
        assertEquals(without, SharedPreferencesKnownEndpoints(context).endpoint(id))
        store.forget(id)
        assertNull(SharedPreferencesKnownEndpoints(context).endpoint(id))
    }

    @Test fun rotatingScanEndpointIsResolvableInProcessButNotPersisted() = runBlocking<Unit> {
        val id = UUID.randomUUID()
        val endpoint = BluetoothEndpoint(id, "4A:00:00:00:00:01", null)
        val store = SharedPreferencesKnownEndpoints(context)
        store.remember(endpoint, DeviceEndpointIdentity.stability(endpoint.address, bonded = false))
        assertEquals(endpoint, store.endpoint(id))
        assertNull(SharedPreferencesKnownEndpoints(context).endpoint(id))
    }

    @Test fun bondedPrivateEndpointIsPersistedAsStable() = runBlocking<Unit> {
        val id = UUID.randomUUID()
        val endpoint = BluetoothEndpoint(id, "4A:00:00:00:00:02", null)
        val store = SharedPreferencesKnownEndpoints(context)
        store.remember(endpoint, DeviceEndpointIdentity.stability(endpoint.address, bonded = true))
        assertEquals(endpoint, SharedPreferencesKnownEndpoints(context).endpoint(id))
    }

    @Test fun draftsAndContactFlagsPersistAcrossInstances() {
        SharedPreferencesDraftDefaults(context).setStringDictionary(mapOf("a|ch|1" to "hello"), "chat.drafts.v1")
        assertEquals(mapOf("a|ch|1" to "hello"), SharedPreferencesDraftDefaults(context).stringDictionary("chat.drafts.v1"))
        assertNull(SharedPreferencesDraftDefaults(context).stringDictionary("absent"))
        assertFalse(SharedPreferencesContactFlags(context).bool("migrated"))
        SharedPreferencesContactFlags(context).set("migrated", true)
        assertTrue(SharedPreferencesContactFlags(context).bool("migrated"))
    }
}

class DisconnectReconciliationTest : RoomProcessTest() {
    @Test
    fun aGraphLostWithoutAConnectionLostCallbackStillResetsTheSessionState() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            assertNotNull(h.appState.syncCoordinator)
            // The runtime reports no loss callback when a failed switch detaches a generation: simulate the graph
            // disappearing without one by withdrawing it from the registry, then run the reconciliation edge.
            h.sessions.withdraw(assertNotNull(h.sessions.current))
            assertNull(h.appState.services)
            assertNotNull(h.appState.syncCoordinator, "app state has not been told")
            h.appState.reconcileSessionLoss()
            assertNull(h.appState.syncCoordinator)
            assertEquals(0, h.appState.activeSessionJobCount)
            assertEquals(0, h.appState.messageEventDispatcher.activeJobCount)
            assertFalse(h.appState.batteryMonitor.isRefreshLoopActive)
            assertTrue(h.appState.statusPillState != StatusPillState.Ready)
            // Already torn down: a second reconcile is a no-op rather than a second "connection lost" reset.
            h.appState.connectionUI.showReadyToastBriefly()
            h.appState.reconcileSessionLoss()
            assertTrue(h.appState.connectionUI.showReadyToast, "no second reset")
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
        } finally { h.container.close() }
    }
}
