// AndroidOnly: WP-217 Simulator generations use the normal ownership lifecycle without a physical handshake.
package com.meshcoreone.android.core.runtime

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.ConnectionIntent
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.RoomPersisting
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.DevicePlatform
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeSimulatorTest {
    @TestFactory
    fun simulatorLifecycle() = listOf(
        nativeCase("simulator publishes only after hydration and seeding and tears down once") {
            val fixture = SimulatorFixture(this)
            try {
                fixture.manager.connect(ConnectionTarget.Simulator)
                assertEquals(listOf("hydrate", "seed", "available", "synced"), fixture.order)
                assertEquals(DeviceConnectionState.READY, fixture.manager.connectionState)
                assertEquals(ConnectionState.Disconnected, fixture.manager.snapshot.value.transport)
                assertTrue(fixture.manager.isSimulatorConnection)
                assertEquals(fixture.id, fixture.devices.fetchActiveDevice()?.id)
                assertEquals(fixture.id, fixture.last.read().deviceId)
                assertEquals(0, fixture.links.single().radio.connectionAttempts)
                assertTrue(fixture.links.single().radio.frames.isEmpty())
                assertEquals(0, fixture.services.single().monitoringStarts)
                assertTrue(fixture.services.single().syncForces.isEmpty())

                fixture.manager.connect(ConnectionTarget.Simulator)
                assertEquals(1, fixture.links.size)
                fixture.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                assertEquals(DeviceConnectionState.DISCONNECTED, fixture.manager.connectionState)
                assertEquals(1, fixture.services.single().teardowns)
                assertEquals(1, fixture.links.single().disconnects)
                assertEquals(1, fixture.links.single().registrationClosures)
            } finally {
                fixture.manager.close()
            }
            assertEquals(1, fixture.services.single().teardowns)
            assertEquals(1, fixture.links.single().disconnects)
        },
        nativeCase("invalid simulator identities fail before graph creation or persistence") {
            for (invalidKey in listOf(false, true)) {
                val fixture = SimulatorFixture(this)
                fixture.device = if (invalidKey) fixture.device.copy(publicKey = Bytes.of(1))
                    else fixture.device.copy(radioId = RadioId(UUID.randomUUID()))
                try {
                    val error = assertFailsWith<ConnectionError.InvalidIdentity> {
                        fixture.manager.connect(ConnectionTarget.Simulator)
                    }
                    assertEquals(
                        if (invalidKey) "Radio public key must contain exactly 32 bytes"
                        else "Simulator device and radio identities do not match",
                        error.message,
                    )
                    assertTrue(fixture.services.isEmpty())
                    assertTrue(fixture.devices.rows.isEmpty())
                    assertEquals(DeviceConnectionState.DISCONNECTED, fixture.manager.connectionState)
                    assertEquals(1, fixture.links.single().disconnects)
                    assertEquals(1, fixture.links.single().registrationClosures)
                } finally {
                    fixture.manager.close()
                }
            }
        },
        nativeCase("foreground restores the last simulator when the connection intent is unchanged") {
            val fixture = SimulatorFixture(this)
            try {
                fixture.manager.connect(ConnectionTarget.Simulator)
                fixture.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                fixture.manager.appDidBecomeActive()
                assertEquals(DeviceConnectionState.READY, fixture.manager.connectionState)
                assertTrue(fixture.manager.isSimulatorConnection)
                assertEquals(2, fixture.links.size)
            } finally {
                fixture.manager.close()
            }
        },
        nativeCase("a stale foreground simulator read cannot override a newer disconnect or connection") {
            for (switchToPhysical in listOf(false, true)) {
                val delegate = TestPreferences()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var blockNextRead = false
                val preferences = object : ProcessConnectionPreferences by delegate {
                    override suspend fun read(): RuntimePreferenceSnapshot {
                        val snapshot = delegate.read()
                        if (blockNextRead) {
                            blockNextRead = false
                            entered.complete(Unit)
                            release.await()
                        }
                        return snapshot
                    }
                }
                val fixture = SimulatorFixture(this, preferences)
                try {
                    fixture.manager.connect(ConnectionTarget.Simulator)
                    fixture.manager.disconnect(RuntimeDisconnectReason.WIFI_RECONNECT_PREP)
                    blockNextRead = true
                    val foreground = backgroundScope.async { fixture.manager.appDidBecomeActive() }
                    runCurrent()
                    assertTrue(entered.isCompleted)

                    if (switchToPhysical) {
                        fixture.manager.connect(ConnectionTarget.WiFi("127.0.0.1", 5000u))
                    } else {
                        fixture.manager.disconnect()
                    }
                    release.complete(Unit)
                    runCurrent()
                    foreground.await()

                    assertEquals(1, fixture.links.size, "the old simulator connection must not restart")
                    assertFalse(fixture.manager.isSimulatorConnection)
                    if (switchToPhysical) {
                        assertEquals(DeviceConnectionState.READY, fixture.manager.connectionState)
                        assertEquals(1, fixture.physicalLinks.size)
                        assertTrue(fixture.manager.connectionIntent.wantsConnection)
                    } else {
                        assertEquals(DeviceConnectionState.DISCONNECTED, fixture.manager.connectionState)
                        assertEquals(ConnectionIntent.UserDisconnected, fixture.manager.connectionIntent)
                        assertEquals(ConnectionIntent.UserDisconnected, fixture.last.restoredIntent())
                    }
                } finally {
                    release.complete(Unit)
                    fixture.manager.close()
                }
            }
        },
    )
}

@OptIn(ExperimentalCoroutinesApi::class)
private class SimulatorFixture(test: TestScope, preferences: ProcessConnectionPreferences = TestPreferences()) {
    val id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    val devices = TestDevices()
    val clock = TestClock(test.testScheduler)
    val last = LastConnectionStore(preferences, clock)
    val platform = TestPlatform()
    val links = mutableListOf<SimulatorLink>()
    val physicalLinks = mutableListOf<TestLink>()
    val services = mutableListOf<TestServices>()
    val order = mutableListOf<String>()
    var device = DeviceDTO(id = id, radioId = RadioId(id), publicKey = publicKey, nodeName = "Simulator")
    val manager = ConnectionManager(
        devices,
        object : RoomPersisting by rejectingRole(RoomPersisting::class.java) {
            override suspend fun resetAllRemoteNodeSessionConnections() = Unit
        },
        rejectingRole(ContactPersisting::class.java),
        object : ProcessRuntimeMaintenance {
            override suspend fun warmUp() = Unit
            override suspend fun initializeDevicePreferences(device: DeviceDTO) = Unit
        },
        last,
        platform,
        RuntimeLinkFactory { target ->
            when (target) {
                ConnectionTarget.Simulator -> SimulatorLink(
                    connectedDevice = { device },
                    seed = { order += "seed"; devices.saveDevice(device) },
                ).also { links += it }
                is ConnectionTarget.WiFi -> TestLink(
                    TestRadio().also { it.key = Bytes(ByteArray(32) { 0x41 }) }, TransportType.WIFI,
                ).also { physicalLinks += it }
                is ConnectionTarget.Bluetooth -> error("Unexpected Bluetooth link")
            }
        },
        RuntimeServiceFactory { inputs, ownership ->
            TestServices(inputs).also { service ->
                services += service
                service.beforeHydrate = { order += "hydrate" }
                ownership.register(service)
            }
        },
        ConnectionObserver(
            onServicesAvailable = { order += "available" },
            onConnectionLost = {},
            onAutoReconnectStarted = {},
            onDeviceSynced = { order += "synced" },
            onAuthenticationFailure = {},
            onLastDeviceCleared = {},
        ),
        RuntimeIssueReporter {},
        clock,
        context = test.backgroundScope.coroutineContext,
        configuration = SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
        simulatorDeviceId = id,
    )
}

private class SimulatorLink(
    private val connectedDevice: () -> DeviceDTO,
    private val seed: suspend () -> Unit,
) : RuntimeSimulatorLink {
    val radio = TestRadio()
    var disconnects = 0
    var registrationClosures = 0
    override val type = TransportType.SIMULATOR
    override val transport: MeshTransport = object : MeshTransport by radio {
        override suspend fun disconnect() {
            disconnects++
            radio.disconnect()
        }
    }

    override suspend fun connectSimulator(): DeviceDTO = connectedDevice()
    override suspend fun seedSimulator() = seed()
    override fun register(callbacks: LinkCallbacks): AutoCloseable = AutoCloseable { registrationClosures++ }
    override suspend fun configure(capabilities: DeviceCapabilities, platform: DevicePlatform) =
        error("The simulator must not configure a physical link")
    override suspend fun setSessionLive(token: SessionToken?) {
        check(token == null)
    }
    override suspend fun recordBondVerification(deviceId: UUID, at: Instant) =
        error("The simulator must not change a Bluetooth bond")
    override suspend fun clearBondVerification(deviceId: UUID) =
        error("The simulator must not change a Bluetooth bond")
    override suspend fun mayRefreshBond(deviceId: UUID): Boolean = false
}
