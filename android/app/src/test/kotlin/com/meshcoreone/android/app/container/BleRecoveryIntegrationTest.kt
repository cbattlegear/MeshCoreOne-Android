// AndroidOnly: WP-303 Real Android BLE adapter, runtime manager and App graph over Room with a controlled framework peer.
package com.meshcoreone.android.app.container

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.meshcoreone.android.core.ble.AndroidGattFacade
import com.meshcoreone.android.core.ble.BlePhase
import com.meshcoreone.android.core.ble.BluetoothAvailability
import com.meshcoreone.android.core.ble.NusUuid
import com.meshcoreone.android.core.connectivity.ConnectivityPlatform
import com.meshcoreone.android.core.connectivity.SystemLinkAdopter
import com.meshcoreone.android.core.connectivity.ble.BleLinkInspector
import com.meshcoreone.android.core.connectivity.platform.AndroidSystemLinkProbe
import com.meshcoreone.android.core.connectivity.pairing.BluetoothEndpoint
import com.meshcoreone.android.core.connectivity.pairing.DeviceEndpointIdentity
import com.meshcoreone.android.core.connectivity.pairing.InMemoryKnownEndpointStore
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
import kotlin.Suppress
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
@Config(sdk = [31], shadows = [BleRecoveryDeviceShadow::class, BleRecoveryGattShadow::class])
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
            f.markSystemConnected()
            assertTrue(f.systemProbe.isDeviceConnectedToSystem(f.target.deviceId))
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
            DeviceEndpointIdentity.deviceId(address),
        )
        val peers = mutableListOf<Peer>()
        val preferences = MemoryConnectionPreferences()
        var adoptions = 0
        private val clock = SchedulerClock { test.testScheduler.currentTime }
        val factory = AndroidRuntimeLinkFactory(
            { handle -> AndroidGattFacade(application, handle) },
            linkDispatcher = UnconfinedTestDispatcher(test.testScheduler),
        )
        val systemProbe = AndroidSystemLinkProbe(application)
        private val pairing = HarnessPairingService().also { it.registered[target.deviceId] = "Controlled BLE radio" }
        private val endpoints = InMemoryKnownEndpointStore()
        private val connectivity = ConnectivityPlatform(
            pairing, BleLinkInspector(systemProbe, factory::currentBleLink) { BluetoothAvailability.Ready },
            endpoints, SystemLinkAdopter { adoptions++; false },
        )
        private val diagnostics = mutableListOf<com.meshcoreone.android.core.runtime.RuntimeDiagnostic>()
        val container = AppContainer(AppContainerDependencies(
            store = store,
            passwords = MemoryVault(),
            connectionPreferences = preferences,
            connectivity = connectivity,
            linkProbe = systemProbe,
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
            val deviceShadow = Shadow.extract<BleRecoveryDeviceShadow>(device)
            deviceShadow.onOpen = { opened ->
                val peer = Peer(opened, test)
                peers += peer
                requireNotNull(peer.shadow.gattCallback).onConnectionStateChange(
                    opened, BluetoothGatt.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED,
                )
            }
        }

        fun markSystemConnected() {
            val manager = application.getSystemService(BluetoothManager::class.java)
            val device = requireNotNull(manager.adapter).getRemoteDevice(address)
            Shadows.shadowOf(manager).addDevice(BluetoothProfile.GATT, BluetoothProfile.STATE_CONNECTED, device)
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
            endpoints.remember(BluetoothEndpoint(target.deviceId, address, null))
            assertEquals(address, endpoints.endpoint(target.deviceId)?.address)
            runOperation { manager.connect(target) }
            until("connection ready") { manager.connectionState == DeviceConnectionState.READY }
            assertEquals(BlePhase.Connected, factory.currentBleLink()?.diagnostics?.phase)
            assertEquals(target.deviceId, LastConnectionStore(preferences, clock).read().deviceId)
            val peer = peers.last()
            assertEquals(listOf("discover", "mtu", "notify", "descriptor"), peer.shadow.setup)
            assertEquals(1, peer.shadow.services.size)
            assertTrue(peer.shadow.notificationsEnabled)
            assertFalse(peer.shadow.closed)
            assertEquals(0, peer.shadow.disconnects)
        }

        suspend fun close() {
            try { runOperation { container.close() } }
            finally {
                peers.forEach {
                    it.job.cancel()
                    assertEquals(1, it.shadow.disconnects)
                    assertEquals(1, it.shadow.closes)
                    assertTrue(it.shadow.closed)
                }
            }
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

@Implements(BluetoothDevice::class, minSdk = 31, maxSdk = 32)
class BleRecoveryDeviceShadow {
    @RealObject private lateinit var actual: BluetoothDevice
    private lateinit var address: String
    internal var onOpen: (BluetoothGatt) -> Unit = { error("Native GATT peer is not installed") }

    @Implementation
    fun __constructor__(address: String) {
        this.address = address
    }

    @Implementation
    fun getAddress(): String = address

    @Implementation
    fun getBondState(): Int = BluetoothDevice.BOND_BONDED

    @Implementation
    fun connectGatt(
        context: Context,
        autoConnect: Boolean,
        callback: BluetoothGattCallback,
        transport: Int,
        phy: Int,
        handler: Handler,
    ): BluetoothGatt {
        assertEquals(RuntimeEnvironment.getApplication(), context.applicationContext)
        assertFalse(autoConnect)
        assertEquals(BluetoothDevice.TRANSPORT_LE, transport)
        assertEquals(BluetoothDevice.PHY_LE_1M_MASK, phy)
        assertEquals(Looper.getMainLooper(), handler.looper)
        val gatt = ShadowBluetoothGatt.newInstance(actual)
        val shadow = Shadow.extract<BleRecoveryGattShadow>(gatt)
        shadow.gattCallback = callback
        onOpen(gatt)
        return gatt
    }
}

@Implements(BluetoothGatt::class, minSdk = 31, maxSdk = 32)
class BleRecoveryGattShadow {
    @RealObject private lateinit var actual: BluetoothGatt
    internal lateinit var gattCallback: BluetoothGattCallback
    internal var onCommand: (ByteArray) -> Unit = { error("Firmware peer is not installed") }
    internal var onClosed: () -> Unit = {}
    internal var closes = 0
    internal var disconnects = 0
    internal val writes = mutableListOf<ByteArray>()
    internal val setup = mutableListOf<String>()
    private val discoverable = mutableListOf<BluetoothGattService>()
    internal val services = mutableListOf<BluetoothGattService>()
    internal var notificationsEnabled = false
    internal var closed = false

    internal fun addDiscoverableService(service: BluetoothGattService) {
        discoverable += service
    }

    @Implementation
    fun discoverServices(): Boolean {
        check(!closed)
        setup += "discover"
        services.clear()
        services += discoverable
        check(services.isNotEmpty())
        gattCallback.onServicesDiscovered(actual, BluetoothGatt.GATT_SUCCESS)
        return true
    }

    @Implementation
    fun getServices(): List<BluetoothGattService> = services.toList()

    @Implementation
    fun setCharacteristicNotification(characteristic: BluetoothGattCharacteristic, enable: Boolean): Boolean {
        check(!closed)
        assertEquals(NusUuid.RX, characteristic.uuid)
        assertTrue(services.any { characteristic in it.characteristics })
        assertTrue(enable)
        setup += "notify"
        notificationsEnabled = true
        return true
    }

    @Implementation
    fun requestMtu(mtu: Int): Boolean {
        check(!closed)
        assertTrue(mtu >= 247)
        setup += "mtu"
        requireNotNull(gattCallback).onMtuChanged(actual, 247, BluetoothGatt.GATT_SUCCESS)
        return true
    }

    @Suppress("DEPRECATION")
    @Implementation
    fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean {
        check(!closed)
        assertTrue(notificationsEnabled)
        assertEquals(NusUuid.CCCD, descriptor.uuid)
        assertTrue(requireNotNull(descriptor.value).contentEquals(byteArrayOf(1, 0)))
        setup += "descriptor"
        requireNotNull(gattCallback).onDescriptorWrite(actual, descriptor, BluetoothGatt.GATT_SUCCESS)
        return true
    }

    @Suppress("DEPRECATION")
    @Implementation
    fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
        check(!closed)
        assertEquals(NusUuid.TX, characteristic.uuid)
        assertTrue(services.any { characteristic in it.characteristics })
        val bytes = requireNotNull(characteristic.value).copyOf()
        writes += bytes
        requireNotNull(gattCallback).onCharacteristicWrite(actual, characteristic, BluetoothGatt.GATT_SUCCESS)
        onCommand(bytes)
        return true
    }

    @Implementation
    fun disconnect() {
        disconnects++
    }

    @Implementation
    fun close() {
        closes++
        closed = true
        onClosed()
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
