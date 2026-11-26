// PortedFrom: MC1Services/Tests/MC1ServicesTests/ConnectionManagerPromotionTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.support.OriginalCase
import com.meshcoreone.android.core.connectivity.support.RuntimeHarness
import com.meshcoreone.android.core.connectivity.support.runtimeScenario
import com.meshcoreone.android.core.connectivity.support.settle
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionSnapshot
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.RuntimeSyncResult
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import org.junit.Test

/**
 * READY promotion (WP-207 `promoteToReady`, private) observed through real connects. The Swift
 * `expectedServices` identity guard is the runtime's generation currency check: a generation that
 * stops being current during sync is never promoted.
 */
class ConnectionManagerPromotionTest {
    private suspend fun RuntimeHarness.recordSnapshots(): MutableList<ConnectionSnapshot> {
        val recorded = mutableListOf<ConnectionSnapshot>()
        val subscription = manager.subscribeTransitions()
        scenario.scope.launch { subscription.transitions.collect { recorded += it } }
        settle()
        return recorded
    }

    private suspend fun RuntimeHarness.connectWith(sync: RuntimeSyncResult = RuntimeSyncResult.Usable, beforeSync: suspend () -> Unit = {}): UUID {
        val id = UUID.randomUUID()
        register(id)
        configureServices = { it.syncResult = sync; it.beforeSync = beforeSync }
        runCatching { manager.connect(target(id)) }
        settle()
        return id
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady suppressed when services replaced during sync()", "native-equivalent")
    fun `promoteToReady suppressed when services replaced during sync`() = runtimeScenario {
        val snapshots = recordSnapshots()
        val replacement = UUID.randomUUID()
        register(replacement)
        var first = true
        configureServices = { services ->
            if (first) {
                first = false
                services.beforeSync = { scenario.scope.launch { manager.connect(target(replacement)) }; settle() }
            }
        }
        val original = UUID.randomUUID()
        register(original)
        runCatching { manager.connect(target(original)) }
        settle()
        val originalToken = services.first().token
        assertTrue(snapshots.none { it.state == DeviceConnectionState.READY && it.token == originalToken })
        assertEquals(1, syncedCount, "Only the replacement generation is synced")
        assertEquals(replacement, manager.connectedDevice?.id)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady suppressed when services nil (disconnected)()", "native-equivalent")
    fun `promoteToReady suppressed when services nil (disconnected)`() = runtimeScenario {
        val snapshots = recordSnapshots()
        connectWith { manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP) }
        assertTrue(snapshots.none { it.state == DeviceConnectionState.READY })
        assertEquals(DeviceConnectionState.DISCONNECTED, manager.connectionState)
        assertEquals(0, syncedCount)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady sets .syncing when sync failed()")
    fun `promoteToReady sets syncing when sync failed`() = runtimeScenario {
        connectWith(RuntimeSyncResult.Failed(IllegalStateException("channel sync")))
        assertEquals(DeviceConnectionState.SYNCING, manager.connectionState)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady skips onDeviceSynced when sync failed()")
    fun `promoteToReady skips onDeviceSynced when sync failed`() = runtimeScenario {
        connectWith(RuntimeSyncResult.Failed(IllegalStateException("channel sync")))
        assertEquals(DeviceConnectionState.SYNCING, manager.connectionState, "Still promoted to SYNCING for the resync loop")
        assertEquals(0, syncedCount)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady sets .ready when sync succeeded()")
    fun `promoteToReady sets ready when sync succeeded`() = runtimeScenario {
        connectWith()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady calls onDeviceSynced when sync succeeded()")
    fun `promoteToReady calls onDeviceSynced when sync succeeded`() = runtimeScenario {
        connectWith()
        assertEquals(1, syncedCount)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady does NOT call onDeviceSynced when sync failed()")
    fun `promoteToReady does NOT call onDeviceSynced when sync failed`() = runtimeScenario {
        connectWith(RuntimeSyncResult.Failed(IllegalStateException("sync")))
        assertEquals(0, syncedCount)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady sets .ready and fires onDeviceSynced on successful sync()")
    fun `promoteToReady sets ready and fires onDeviceSynced on successful sync`() = runtimeScenario {
        connectWith()
        assertEquals(DeviceConnectionState.READY, manager.connectionState)
        assertEquals(TransportType.BLUETOOTH, links.last().type)
        assertEquals(1, syncedCount)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady suppressed when user disconnected during sync()", "native-equivalent")
    fun `promoteToReady suppressed when user disconnected during sync`() = runtimeScenario {
        val snapshots = recordSnapshots()
        connectWith { manager.disconnect(RuntimeDisconnectReason.USER_INITIATED) }
        assertTrue(snapshots.none { it.state == DeviceConnectionState.READY })
        assertEquals(ConnectionIntent.UserDisconnected, manager.connectionIntent)
        assertEquals(0, syncedCount)
        assertNull(manager.connectedDevice)
    }

    @Test @OriginalCase("ConnectionManagerPromotionTests::promoteToReady suppressed when additionalGuard returns false()", "native-equivalent")
    fun `promoteToReady suppressed when the generation guard fails`() = runtimeScenario {
        val snapshots = recordSnapshots()
        // The runtime's guard is generation currency: retiring the generation mid-sync fails it.
        connectWith { manager.teardownSessionForReconnect() }
        assertTrue(snapshots.none { it.state == DeviceConnectionState.READY })
        assertEquals(0, syncedCount)
        assertFalse(manager.connectionState.isOperational)
    }
}
