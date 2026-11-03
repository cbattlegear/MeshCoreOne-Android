// AndroidOnly: WP-303 Real Android BLE adapter, runtime manager and App graph over Room with a controlled framework peer.
package com.meshcoreone.android.app.container

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.os.Looper
import com.meshcoreone.android.core.ble.AndroidGattFacade
import com.meshcoreone.android.core.ble.BlePhase
import com.meshcoreone.android.core.ble.BluetoothAvailability
import com.meshcoreone.android.core.ble.NusUuid
import com.meshcoreone.android.core.connectivity.ConnectivityPlatform
import com.meshcoreone.android.core.connectivity.SystemLinkAdopter
import com.meshcoreone.android.core.connectivity.ble.BleLinkInspector
import com.meshcoreone.android.core.connectivity.ble.SystemLinkProbe
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.contracts.domain.BluetoothAddress
import com.meshcoreone.android.core.contracts.domain.BluetoothPairingHandle
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.PersistenceKeys
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.runtime.LastConnectionStore
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.runtime.RuntimeIssueReporter
import com.meshcoreone.android.core.runtime.RuntimePreferenceValue
import com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress
import com.meshcoreone.android.core.services.rendering.DraftStore
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothAdapter
import org.robolectric.shadows.ShadowBluetoothGatt

@OptIn(ExperimentalCoroutinesApi::class)
@Config(sdk = [31], shadows = [BleRecoveryGattShadow::class])
class BleRecoveryIntegrationTest : RoomProcessTest() {
    @Test fun nativeLossAndHealthReconnectReplaceGraphWithoutAcceptingOldGattIdentityOrFrames() = runTest {
        val f = Fixture(this)
        try {
            f.connect()
            val first = assertNotNull(f.container.sessions.current)
            val firstPeer = f.peers.single()
            val stored = assertNotNull(store.fetchDevice(f.target.deviceId))
            assertEquals(key(7), stored.publicKey)
            assertEquals(stored.radioId, first.token.radioId)
            assertEquals(TransportType.BLUETOOTH, f.container.currentTransport)
            assertEquals(f.target.deviceId, f.container.connectionPort.lastConnectedDeviceId)
            assertEquals(1, firstPeer.shadow.writes.count { it.first().toInt() == 1 })

            firstPeer.shadow.lose()
            f.until("native loss tears down the graph") {
                first.isTornDown && first.liveJobCount == 0 && f.container.sessions.current == null &&
                    f.container.currentTransport == null && firstPeer.shadow.closes == 1
            }
            assertEquals(0, first.liveJobCount)
            assertEquals(DeviceConnectionState.DISCONNECTED, f.manager.connectionState)
            assertTrue(f.manager.connectionIntent.wantsConnection)
            assertNull(f.container.currentTransport)
            assertEquals(1, f.peers.size, "no implicit BLE rearm")
            assertTrue(f.preferences.values[PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC]
                .let { it as RuntimePreferenceValue.Text }.value.contains("source=handleConnectionLoss"))

            f.runOperation { f.manager.checkBLEConnectionHealth() }
            f.until("fresh graph reaches ready") { f.manager.connectionState == DeviceConnectionState.READY }
            val fresh = assertNotNull(f.container.sessions.current)
            assertNotSame(first, fresh)
            assertTrue(fresh.token.generation.value > first.token.generation.value)
            assertEquals(first.token.radioId, fresh.token.radioId, "the verified radio retains its Room partition")
            assertEquals(stored.id, store.fetchDevice(f.target.deviceId)?.id)
            assertEquals(2, f.peers.size)
            assertEquals(1, firstPeer.shadow.closes)
            assertEquals(1, f.peers.last().shadow.writes.count { it.first().toInt() == 1 }, "fresh appStart handshake")
            assertEquals(1, f.container.sessions.outstanding)
            assertEquals(TransportType.BLUETOOTH, f.factory.transportFor(fresh.token))
            assertNull(f.factory.transportFor(first.token), "old session cannot identify the live transport")

            val before = assertNotNull(store.fetchDevice(f.target.deviceId))
            val selfInfoKeys = mutableListOf<Bytes>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                fresh.toolEvents().collect { event ->
                    if (event is MeshEvent.SelfInfo) selfInfoKeys += event.info.publicKey
                }
            }
            try {
                val currentPeer = f.peers.last()
                val identityFrame = FirmwareRadio(key(99), "Stale identity").selfPacket().toByteArray()
                currentPeer.shadow.notify(currentPeer.rx, identityFrame)
                f.until("current GATT self-info reaches the fresh session observer") { selfInfoKeys == listOf(key(99)) }
                assertEquals(before, store.fetchDevice(f.target.deviceId),
                    "Room alone cannot detect self-info consumed by an already-ready session")

                firstPeer.shadow.lateConnected()
                firstPeer.shadow.notify(firstPeer.rx, identityFrame)
                firstPeer.shadow.lose(BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION)
                f.pump()
                assertEquals(listOf(key(99)), selfInfoKeys, "retired GATT must not emit self-info into the fresh session")
                currentPeer.shadow.notify(currentPeer.rx, FirmwareRadio(key(7)).selfPacket().toByteArray())
                f.until("current GATT observer remains live after stale callbacks") { selfInfoKeys.size >= 2 }
                assertEquals(listOf(key(99), key(7)), selfInfoKeys, "only current-GATT self-info reaches the fresh session")
                assertEquals(before, store.fetchDevice(f.target.deviceId), "old native callbacks cannot overwrite the current radio")
                assertEquals(fresh, f.container.sessions.current)
                assertEquals(DeviceConnectionState.READY, f.manager.connectionState)
                assertEquals(2, f.peers.size)
                assertEquals(1, f.container.sessions.outstanding)
            } finally { observer.cancel() }
        } finally { f.close() }
    }

    @Test fun userStopClosesNativeGattAndHealthCannotRearmOrRecreateItsGraph() = runTest {
        val f = Fixture(this)
        try {
            f.connect()
            val graph = assertNotNull(f.container.sessions.current)
            val peer = f.peers.single()
            f.runOperation { f.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED) }
            f.until("all graph jobs stop") { graph.isTornDown && graph.liveJobCount == 0 }
            assertFalse(f.manager.connectionIntent.wantsConnection)
            assertNull(f.container.sessions.current)
            assertNull(f.container.currentTransport)
            assertEquals(1, peer.shadow.closes)
            peer.shadow.lateConnected()
            peer.shadow.lose()
            f.runOperation { f.manager.checkBLEConnectionHealth() }
            assertEquals(1, f.peers.size)
            assertEquals(0, f.container.sessions.outstanding)
            assertEquals(DeviceConnectionState.DISCONNECTED, f.manager.connectionState)
            assertNotNull(store.fetchDevice(f.target.deviceId), "user stop preserves process persistence")
        } finally { f.close() }
    }

    @Test fun ownedSystemLinkHealthDoesNotAdoptForeignGattButExplicitConnectUsesFreshNativeAttempt() = runTest {
        val f = Fixture(this)
        try {
            f.connect()
            val original = assertNotNull(f.container.sessions.current)
            f.peers.single().shadow.lose()
            f.until("loss before system-link health check") {
                original.isTornDown && f.container.sessions.current == null && f.container.currentTransport == null &&
                    f.factory.currentBleLink()?.diagnostics?.phase == BlePhase.Idle
            }
            f.systemConnected = true
            f.runOperation { f.manager.checkBLEConnectionHealth() }
            assertEquals(DeviceConnectionState.DISCONNECTED, f.manager.connectionState)
            assertEquals(1, f.peers.size, "health must not open another app's system-connected GATT")
            assertNull(f.container.sessions.current)
            val diagnostic = f.preferences.values[PersistenceKeys.LAST_DISCONNECT_DIAGNOSTIC] as RuntimePreferenceValue.Text
            assertTrue(diagnostic.value.contains("source=checkBLEConnectionHealth.otherAppConnected"))

            f.connect()
            assertEquals(2, f.peers.size, "manual owned-target fallthrough opens one new native GATT")
            assertNotSame(original, f.container.sessions.current)
            assertEquals(DeviceConnectionState.READY, f.manager.connectionState)
            assertEquals(1, f.container.sessions.outstanding)
            assertEquals(2, f.adoptions, "both owned idle routes decline adoption; only explicit connect opens a GATT")
        } finally { f.close() }
    }

    private inner class Fixture(private val test: TestScope) {
        private val application: Application = RuntimeEnvironment.getApplication()
        private val address = "02:00:00:00:38:30"
        val target = ConnectionTarget.Bluetooth(
            BluetoothPairingHandle(BluetoothAddress(address), null),
            UUID.fromString("38300000-0000-0000-0000-000000000001"),
        )
        val peers = mutableListOf<Peer>()
        val preferences = MemoryConnectionPreferences()
        var systemConnected = false
        var adoptions = 0
        private val clock = SchedulerClock { test.testScheduler.currentTime }
        val factory = AndroidRuntimeLinkFactory(
            { handle -> AndroidGattFacade(application, handle) },
            linkDispatcher = UnconfinedTestDispatcher(test.testScheduler),
        )
        private val probe = object : SystemLinkProbe {
            override suspend fun isDeviceConnectedToSystem(deviceId: UUID) = systemConnected && deviceId == target.deviceId
            override suspend fun systemConnectedDeviceIds() = if (systemConnected) setOf(target.deviceId) else emptySet()
        }
        private val pairing = HarnessPairingService().also { it.registered[target.deviceId] = "Controlled BLE radio" }
        private val endpoints = object : KnownEndpointStore {
            override suspend fun endpoint(deviceId: UUID): BluetoothEndpoint? =
                if (deviceId == target.deviceId) BluetoothEndpoint(deviceId, address, null) else null
            override suspend fun remember(endpoint: BluetoothEndpoint) {
                assertEquals(target.deviceId, endpoint.deviceId)
                assertEquals(address, endpoint.address)
            }
            override suspend fun forget(deviceId: UUID) = Unit
        }
        private val connectivity = ConnectivityPlatform(
            pairing, BleLinkInspector(probe, factory::currentBleLink) { BluetoothAvailability.Ready },
            endpoints, SystemLinkAdopter { adoptions++; false },
        )
        private val diagnostics = mutableListOf<com.meshcoreone.android.core.runtime.RuntimeDiagnostic>()
        val container = AppContainer(AppContainerDependencies(
            store = store,
            passwords = MemoryVault(),
            connectionPreferences = preferences,
            connectivity = connectivity,
            linkProbe = probe,
            scans = newScans(),
            linkFactory = factory,
            notificationDelivery = RecordingDelivery(),
            notificationPreferences = FixedNotificationPreferences(),
            contactPreferences = MemoryFlags(),
            draftStore = DraftStore(MemoryDraftDefaults()),
            chatSendQueueProgress = ChatSendQueueProgress(),
            mainScope = test.backgroundScope,
            runtimeContext = test.backgroundScope.coroutineContext,
            runtimeClock = clock,
            runtimeReporter = RuntimeIssueReporter { diagnostics += it },
        ))
        val manager get() = container.connectionManager

        init {
            Shadows.shadowOf(application).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT)
            ShadowBluetoothAdapter.setIsBluetoothSupported(true)
            val adapter = requireNotNull(BluetoothAdapter.getDefaultAdapter())
            Shadows.shadowOf(adapter).setState(BluetoothAdapter.STATE_ON)
            val device = adapter.getRemoteDevice(address)
            Shadows.shadowOf(device).setBondState(BluetoothDevice.BOND_BONDED)
            Shadows.shadowOf(device).setGattConnectionInterceptor { opened ->
                val peer = Peer(opened, test)
                peers += peer
                requireNotNull(peer.shadow.gattCallback).onConnectionStateChange(
                    opened, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
                )
            }
        }

        fun pump() {
            repeat(5) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                test.testScheduler.runCurrent()
                test.testScheduler.advanceTimeBy(1)
                Thread.sleep(2)
            }
        }

        fun until(description: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + 15_000_000_000L
            while (System.nanoTime() < deadline) {
                pump()
                if (condition()) return
            }
            throw AssertionError("Timed out: $description; state=${manager.connectionState}, " +
                "phase=${factory.currentBleLink()?.diagnostics?.phase}, diagnostics=$diagnostics")
        }

        suspend fun runOperation(operation: suspend () -> Unit) {
            val work: Deferred<Unit> = test.backgroundScope.async { operation() }
            until("operation completes") { work.isCompleted }
            work.await()
            pump()
        }

        suspend fun connect() {
            runOperation { manager.connect(target) }
            until("connection ready") { manager.connectionState == DeviceConnectionState.READY }
            assertEquals(BlePhase.Connected, factory.currentBleLink()?.diagnostics?.phase)
            assertEquals(target.deviceId, LastConnectionStore(preferences, clock).read().deviceId)
        }

        suspend fun close() {
            try { runOperation { container.close() } }
            finally { peers.forEach { it.job.cancel() } }
        }
    }

    private class Peer(gatt: BluetoothGatt, test: TestScope) {
        val shadow: BleRecoveryGattShadow = Shadow.extract(gatt)
        val rx = BluetoothGattCharacteristic(
            NusUuid.RX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ,
        )
        private val tx = BluetoothGattCharacteristic(
            NusUuid.TX, BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE,
        )
        val job = Job(test.backgroundScope.coroutineContext[Job])

        init {
            val service = BluetoothGattService(NusUuid.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            rx.addDescriptor(BluetoothGattDescriptor(
                NusUuid.CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
            ))
            service.addCharacteristic(tx)
            service.addCharacteristic(rx)
            shadow.addDiscoverableService(service)
            shadow.allowCharacteristicNotification(rx)
            val commands = Channel<Bytes>(Channel.UNLIMITED)
            val firmware = FirmwareRadio(key(7))
            test.backgroundScope.launch(job, start = CoroutineStart.UNDISPATCHED) {
                firmware.connect()
                launch(start = CoroutineStart.UNDISPATCHED) {
                    firmware.receivedData().collect { shadow.notify(rx, it.toByteArray()) }
                }
                for (command in commands) firmware.send(command)
            }
            shadow.onCommand = { check(commands.trySend(Bytes(it)).isSuccess) }
            shadow.onClosed = { commands.close(); job.cancel() }
        }
    }
}

@Implements(BluetoothGatt::class, minSdk = 31, maxSdk = 32)
class BleRecoveryGattShadow : ShadowBluetoothGatt() {
    @RealObject private lateinit var actual: BluetoothGatt
    internal var onCommand: (ByteArray) -> Unit = { error("Firmware peer is not installed") }
    internal var onClosed: () -> Unit = {}
    internal var closes = 0
    internal var disconnects = 0
    internal val writes = mutableListOf<ByteArray>()

    @Implementation
    override fun requestMtu(mtu: Int): Boolean {
        requireNotNull(gattCallback).onMtuChanged(actual, 247, BluetoothGatt.GATT_SUCCESS)
        return true
    }

    @Suppress("DEPRECATION")
    @Implementation
    override fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean {
        requireNotNull(gattCallback).onDescriptorWrite(actual, descriptor, BluetoothGatt.GATT_SUCCESS)
        return true
    }

    @Suppress("DEPRECATION")
    @Implementation
    override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        val bytes = requireNotNull(characteristic.value).copyOf()
        writes += bytes
        requireNotNull(gattCallback).onCharacteristicWrite(actual, characteristic, BluetoothGatt.GATT_SUCCESS)
        onCommand(bytes)
        return true
    }

    @Implementation
    override fun disconnect() {
        disconnects++
        super.disconnect()
    }

    @Implementation
    override fun close() {
        closes++
        onClosed()
        super.close()
    }

    internal fun lose(status: Int = BluetoothGatt.GATT_SUCCESS) {
        requireNotNull(gattCallback).onConnectionStateChange(actual, status, BluetoothProfile.STATE_DISCONNECTED)
    }

    @Suppress("DEPRECATION")
    internal fun notify(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        characteristic.value = bytes.copyOf()
        requireNotNull(gattCallback).onCharacteristicChanged(actual, characteristic)
    }

    internal fun lateConnected() {
        requireNotNull(gattCallback).onConnectionStateChange(actual, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
    }
}
