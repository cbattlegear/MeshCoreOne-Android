// AndroidOnly: WP-303 Native tests: the connectedDevice service follows the connection snapshot and foreground; presence drives reconnects.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.connectivity.presence.CompanionPresenceDispatcher
import com.meshcoreone.android.core.connectivity.presence.PresenceEvent
import com.meshcoreone.android.core.connectivity.service.ConnectedDeviceHostingController
import com.meshcoreone.android.core.connectivity.service.ConnectedDeviceServiceHost
import com.meshcoreone.android.core.connectivity.service.ForegroundServiceStarter
import com.meshcoreone.android.core.connectivity.service.StartOutcome
import com.meshcoreone.android.core.contracts.domain.Capability
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ConnectionSnapshot
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.event.ConnectionState
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HostBindingTest {
    private class Starter : ForegroundServiceStarter {
        var starts = 0
        var stops = 0
        override fun start(): StartOutcome { starts += 1; return StartOutcome.Started }
        override fun stop() { stops += 1 }
    }

    private class Environment : HostEnvironment {
        override var connectPermissionGranted = true
        override var userUnlocked = true
        override var bluetoothEnabled = true
        override var reconnectInProgress = false
        override var lastConnectedDeviceId: UUID? = null
        override var lanOnly = false
        override var isSimulator = false
    }

    private class Effects : PresenceEffects {
        val calls = mutableListOf<String>()
        override suspend fun reconnect(deviceId: UUID) { calls += "reconnect:$deviceId" }
        override fun refreshAssociations() { calls += "refresh" }
        override suspend fun forgetAssociation(deviceId: UUID) { calls += "forget:$deviceId" }
        override fun permissionRevoked(capability: Capability) { calls += "revoked:$capability" }
        override fun deferredUntilUnlock(deviceId: UUID) { calls += "deferred:$deviceId" }
    }

    private val token = SessionToken(ProcessEpoch(UUID.randomUUID()), Generation(1), RadioId(UUID.randomUUID()))
    private fun snapshot(state: DeviceConnectionState, intent: ConnectionIntent = ConnectionIntent.WantsConnection()) = ConnectionSnapshot(
        state, if (state == DeviceConnectionState.DISCONNECTED) ConnectionState.Disconnected else ConnectionState.Connected,
        null, intent, token.takeIf { state.isOperational }, null,
    )

    /** Drains events queued while no sink was attached, so one test's late event never replays into the next. */
    private fun drainPresence() {
        val drain = com.meshcoreone.android.core.connectivity.presence.PresenceEventSink { }
        CompanionPresenceDispatcher.attach(drain)
        CompanionPresenceDispatcher.detach(drain)
    }

    @Before fun clean() = drainPresence()

    @After fun detach() {
        drainPresence()
        ConnectedDeviceServiceHost.onStarted = null
    }

    private class Rig(scope: TestScope, val connection: MutableStateFlow<ConnectionSnapshot>) {
        val foreground = MutableStateFlow(true)
        val starter = Starter()
        val environment = Environment()
        val effects = Effects()
        val binding = ConnectedDeviceHostBinding(
            connection, foreground, { connection.value }, ConnectedDeviceHostingController(starter), environment, effects,
            scope.backgroundScope,
        )
    }

    @Test
    fun theServiceIsHeldWhileAConnectionIsWantedAndStoppedAfterItStarted() = runTest {
        val rig = Rig(this, MutableStateFlow(snapshot(DeviceConnectionState.DISCONNECTED, ConnectionIntent.None)))
        rig.binding.start()
        runCurrent()
        assertEquals(0, rig.starter.starts, "an idle intent holds no service")
        rig.connection.value = snapshot(DeviceConnectionState.READY)
        runCurrent()
        assertEquals(1, rig.starter.starts)
        rig.connection.value = snapshot(DeviceConnectionState.SYNCING)
        runCurrent()
        assertEquals(1, rig.starter.starts, "state changes while held do not start a second service")
        ConnectedDeviceServiceHost.onStarted?.invoke()
        rig.connection.value = snapshot(DeviceConnectionState.DISCONNECTED, ConnectionIntent.UserDisconnected)
        runCurrent()
        assertEquals(1, rig.starter.stops)
        rig.binding.close()
    }

    @Test
    fun aBackgroundStartIsDeferredUntilTheAppIsVisible() = runTest {
        val rig = Rig(this, MutableStateFlow(snapshot(DeviceConnectionState.READY)))
        rig.foreground.value = false
        rig.binding.start()
        runCurrent()
        assertEquals(0, rig.starter.starts, "the system forbids a background start")
        rig.foreground.value = true
        runCurrent()
        assertEquals(1, rig.starter.starts)
        rig.binding.close()
    }

    @Test
    fun withoutTheConnectPermissionTheServiceIsNotStarted() = runTest {
        val rig = Rig(this, MutableStateFlow(snapshot(DeviceConnectionState.READY)))
        rig.environment.connectPermissionGranted = false
        rig.binding.start()
        runCurrent()
        assertEquals(0, rig.starter.starts)
        rig.binding.close()
    }

    @Test
    fun simulatorNeverStartsRadioHostingOrActsOnPresence() = runTest {
        val rig = Rig(this, MutableStateFlow(snapshot(DeviceConnectionState.CONNECTING)))
        val simulatorId = UUID.randomUUID()
        rig.environment.isSimulator = true
        rig.environment.lastConnectedDeviceId = simulatorId
        rig.environment.connectPermissionGranted = false
        rig.binding.start()
        runCurrent()
        assertEquals(0, rig.starter.starts)
        rig.connection.value = snapshot(DeviceConnectionState.DISCONNECTED)
        CompanionPresenceDispatcher.dispatch(PresenceEvent.Appeared(simulatorId))
        runCurrent()
        assertTrue(rig.effects.calls.isEmpty(), "a simulator is not a companion Bluetooth device")
        rig.binding.close()
    }

    @Test
    fun presenceOfTheLastRadioReconnectsAndEveryOtherEventIsRoutedOrIgnored() = runTest {
        val rig = Rig(this, MutableStateFlow(snapshot(DeviceConnectionState.DISCONNECTED)))
        val last = UUID.randomUUID(); val other = UUID.randomUUID()
        rig.environment.lastConnectedDeviceId = last
        rig.binding.start()
        runCurrent()
        CompanionPresenceDispatcher.dispatch(PresenceEvent.Appeared(other))
        CompanionPresenceDispatcher.dispatch(PresenceEvent.Disappeared(last))
        runCurrent()
        assertTrue(rig.effects.calls.isEmpty(), "another radio and a disappearance are ignored: ${rig.effects.calls}")
        CompanionPresenceDispatcher.dispatch(PresenceEvent.Appeared(last))
        CompanionPresenceDispatcher.dispatch(PresenceEvent.AssociationsChanged)
        CompanionPresenceDispatcher.dispatch(PresenceEvent.AssociationRemoved(other))
        runCurrent()
        assertEquals(listOf("reconnect:$last", "refresh", "forget:$other"), rig.effects.calls)
        rig.binding.close()
    }

    @Test
    fun presenceDoesNotOverrideAUserDisconnectAnUnlockOrAPermissionRevocation() = runTest {
        val connection = MutableStateFlow(snapshot(DeviceConnectionState.DISCONNECTED, ConnectionIntent.UserDisconnected))
        val rig = Rig(this, connection)
        val last = UUID.randomUUID()
        rig.environment.lastConnectedDeviceId = last
        rig.binding.start()
        CompanionPresenceDispatcher.dispatch(PresenceEvent.Appeared(last))
        runCurrent()
        assertTrue(rig.effects.calls.isEmpty(), "the user disconnected on purpose")
        connection.value = snapshot(DeviceConnectionState.DISCONNECTED)
        rig.environment.userUnlocked = false
        CompanionPresenceDispatcher.dispatch(PresenceEvent.Appeared(last))
        runCurrent()
        assertEquals(listOf("deferred:$last"), rig.effects.calls)
        rig.environment.userUnlocked = true
        rig.environment.connectPermissionGranted = false
        CompanionPresenceDispatcher.dispatch(PresenceEvent.Appeared(last))
        runCurrent()
        assertEquals(listOf("deferred:$last", "revoked:${Capability.BLUETOOTH_CONNECT}"), rig.effects.calls)
        rig.binding.close()
    }

    @Test
    fun closingDetachesTheServiceHooksAndThePresenceSink() = runTest {
        val rig = Rig(this, MutableStateFlow(snapshot(DeviceConnectionState.DISCONNECTED)))
        val last = UUID.randomUUID()
        rig.environment.lastConnectedDeviceId = last
        rig.binding.start()
        assertTrue(ConnectedDeviceServiceHost.onStarted != null)
        rig.binding.close()
        assertNull(ConnectedDeviceServiceHost.onStarted)
        assertNull(ConnectedDeviceServiceHost.onStopped)
        CompanionPresenceDispatcher.dispatch(PresenceEvent.Appeared(last))
        runCurrent()
        assertTrue(rig.effects.calls.isEmpty(), "a closed binding is never driven")
    }
}
