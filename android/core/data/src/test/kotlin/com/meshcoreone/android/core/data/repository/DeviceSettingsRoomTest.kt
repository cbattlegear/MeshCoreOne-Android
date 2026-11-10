// PortedFrom: MC1Services/Tests/MC1ServicesTests/KnownRegionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/DeviceService.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/State/AppState+Wiring.swift@db14559b39d32322b06477c6ae676112f583db50
// Native assertions: actual Room/process-store consumers of this WP's real connection bundle and protocol session.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.DeviceServiceError
import com.meshcoreone.android.core.contracts.domain.errors.DeviceServiceException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.model.ResponseCode
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import com.meshcoreone.android.core.services.device.DeviceSettingsServiceFactory
import com.meshcoreone.android.core.services.device.DeviceSettingsServices
import com.meshcoreone.android.core.services.device.SettingsEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Clock
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
private annotation class DeviceSettingsSourceCase(val value: String)

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceSettingsRoomTest : RepositoryTest() {
    @Test fun delayedDeviceEditCannotReactivateAnOldRadio() = runTest(scheduler) {
        val original = device()
        val replacement = device(id = UUID.randomUUID(), radio = RADIO_B)
        store.saveDevice(original)
        store.setActiveDevice(original.id)
        val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        store.saveDevice(replacement)
        store.setActiveDevice(replacement.id)
        assertFalse(store.saveDeviceIfUnchanged(ticket.device.copy(appliedRadioPresetID = "late"), ticket))
        assertEquals(replacement.id, store.fetchActiveDevice()!!.id)
        assertFalse(store.fetchDevice(original.id)!!.isActive)
        assertNull(store.fetchDevice(original.id)!!.appliedRadioPresetID)
    }

    @Test fun sameRadioIdenticalFieldsDoNotReviveAnOlderWriteTicket() = runTest(scheduler) {
        val original = device()
        store.saveDevice(original)
        val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        store.saveDevice(original.copy(nodeName = "Successor"))
        store.saveDevice(original)
        assertFalse(store.saveDeviceIfUnchanged(original.copy(appliedRadioPresetID = "late"), ticket))
        assertEquals(original, store.fetchDevice(original.id))
    }

    @Test fun currentDeviceEditTicketCommitsOnceAndPreservesActivation() = runTest(scheduler) {
        val original = device()
        store.saveDevice(original)
        store.setActiveDevice(original.id)
        val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
        val updated = ticket.device.copy(appliedRadioPresetID = "current", pathHashMode = 2u)
        assertTrue(store.saveDeviceIfUnchanged(updated, ticket))
        assertEquals(updated, store.fetchDevice(original.id))
        assertFalse(store.saveDeviceIfUnchanged(updated.copy(nodeName = "replayed"), ticket))
        assertTrue(store.fetchDevice(original.id)!!.isActive)
    }

    @DeviceSettingsSourceCase("KnownRegionTests::addDeviceKnownRegion finds device by radioID, not by id()")
    @Test fun knownRegionAddUsesTheRadioIdentityRatherThanDeviceUUID() = runTest(scheduler) {
        val device = device(id = UUID.fromString("00000000-0000-0000-0000-000000000021"))
        assertNotEquals(device.id, device.radioId.value)
        store.saveDevice(device)
        store.addDeviceKnownRegion(device.radioId, "US915")
        assertTrue("US915" in assertNotNull(store.fetchDevice(device.id)).knownRegions)
    }

    @DeviceSettingsSourceCase("KnownRegionTests::addDeviceKnownRegion does not duplicate existing region()")
    @Test fun knownRegionAddDoesNotDuplicateAnExistingEntry() = runTest(scheduler) {
        val device = device().copy(knownRegions = SnapshotList.of("US915"))
        store.saveDevice(device)
        store.addDeviceKnownRegion(device.radioId, "US915")
        assertEquals(listOf("US915"), assertNotNull(store.fetchDevice(device.id)).knownRegions)
    }

    @DeviceSettingsSourceCase("KnownRegionTests::addDeviceKnownRegion throws when device not found()")
    @Test fun knownRegionAddFailsForAnAbsentRadioWithoutCreatingADevice() = runTest(scheduler) {
        val failure = assertFailsWith<PersistenceStoreException> { store.addDeviceKnownRegion(RADIO_A, "EU868") }
        assertEquals(PersistenceStoreError.DeviceNotFound, failure.error)
        assertTrue(store.fetchDevices().isEmpty())
    }

    @DeviceSettingsSourceCase("KnownRegionTests::removeDeviceKnownRegion clears region and channel regionScope()")
    @Test fun knownRegionRemovalResetsItsRealRoomChannelScope() = runTest(scheduler) {
        val device = device().copy(knownRegions = SnapshotList.of("US915", "EU868"))
        store.saveDevice(device)
        val channel = ChannelDTO(radioId = RADIO_A, index = 1u, name = "Test")
            .withFloodScope(ChannelFloodScope.Region("US915"))
        store.saveChannel(channel)
        store.removeDeviceKnownRegion(RADIO_A, "US915")
        val actual = assertNotNull(store.fetchDevice(device.id))
        assertFalse("US915" in actual.knownRegions)
        assertTrue("EU868" in actual.knownRegions)
        assertEquals(ChannelFloodScope.Inherit, store.fetchChannels(RADIO_A).single().floodScope)
    }

    @DeviceSettingsSourceCase("KnownRegionTests::removeDeviceKnownRegion leaves unrelated channel regionScope intact()")
    @Test fun knownRegionRemovalLeavesTheOtherChannelScopeIntact() = runTest(scheduler) {
        val device = device().copy(knownRegions = SnapshotList.of("US915", "EU868"))
        store.saveDevice(device)
        store.saveChannel(ChannelDTO(radioId = RADIO_A, index = 1u, name = "Chan1")
            .withFloodScope(ChannelFloodScope.Region("US915")))
        store.saveChannel(ChannelDTO(radioId = RADIO_A, index = 2u, name = "Chan2")
            .withFloodScope(ChannelFloodScope.Region("EU868")))
        store.removeDeviceKnownRegion(RADIO_A, "US915")
        assertEquals(
            listOf(ChannelFloodScope.Inherit, ChannelFloodScope.Region("EU868")),
            store.fetchChannels(RADIO_A).sortedBy { it.index }.map { it.floodScope },
        )
    }

    @DeviceSettingsSourceCase("KnownRegionTests::removeDeviceKnownRegion throws when device not found()")
    @Test fun knownRegionRemovalFailsForAnAbsentRadio() = runTest(scheduler) {
        val failure = assertFailsWith<PersistenceStoreException> { store.removeDeviceKnownRegion(RADIO_A, "US915") }
        assertEquals(PersistenceStoreError.DeviceNotFound, failure.error)
        assertTrue(store.fetchDevices().isEmpty())
    }

    @DeviceSettingsSourceCase("KnownRegionTests::Full saveDevice does not clobber regions added via the targeted path()")
    @Test fun fullStaleDeviceSaveCannotClobberTargetedKnownRegions() = runTest(scheduler) {
        val stale = device()
        store.saveDevice(stale)
        store.addDeviceKnownRegion(RADIO_A, "US915")
        store.addDeviceKnownRegion(RADIO_A, "EU868")
        store.saveDevice(stale)
        assertEquals(listOf("US915", "EU868"), assertNotNull(store.fetchDevice(stale.id)).knownRegions)
    }

    @Test fun realDeviceServiceCommitsBeforeItsTaggedCallbackAndPreservesAllIdentityFields() = runTest(scheduler) {
        val device = device()
        store.saveDevice(device)
        store.addDeviceKnownRegion(RADIO_A, "US915")
        val connection = connection(1)
        try {
            var callbacks = 0
            connection.services.deviceService.setDeviceUpdateCallback { event ->
                assertEquals(connection.services.context.token, event.token)
                assertEquals("custom", assertNotNull(store.fetchDevice(device.id)).ocvPreset)
                assertEquals(device.id, event.event.id)
                assertEquals(device.radioId, event.event.radioId)
                assertEquals(device.publicKey, event.event.publicKey)
                callbacks++
            }
            connection.services.deviceService.updateOCVSettings(device.id, "custom",
                "4200,4100,4000,3900,3800,3700,3600,3500,3400,3300,3200")
            assertEquals(1, callbacks)
            assertEquals(listOf("US915"), assertNotNull(store.fetchDevice(device.id)).knownRegions)
            assertEquals((4200L downTo 3200L step 100).toList(), assertNotNull(store.fetchDevice(device.id)).activeOCVArray)
        } finally { connection.close() }
    }

    @Test fun realDeviceServiceAbsentDeviceIsNotReportedAsAPersistenceSuccess() = runTest(scheduler) {
        val connection = connection(1)
        try {
            var callbacks = 0
            connection.services.deviceService.setDeviceUpdateCallback { callbacks++ }
            val failure = assertFailsWith<DeviceServiceException> {
                connection.services.deviceService.updateOCVSettings(UUID.randomUUID(), "liIon", null)
            }
            assertEquals(DeviceServiceError.DeviceNotFound, failure.error)
            assertEquals(0, callbacks)
            assertTrue(store.fetchDevices().isEmpty())
        } finally { connection.close() }
    }

    @Test fun closingAConnectionBundleKeepsThePhysicalSessionAndProcessStoreOwnedByTheirRealOwners() = runTest(scheduler) {
        val device = device()
        store.saveDevice(device)
        val connection = connection(1)
        try {
            connection.services.deviceService.updateOCVSettings(device.id, "liFePO4", null)
            connection.services.close()
            connection.services.close()
            assertTrue(connection.radio.mock.isConnected())
            assertEquals("liFePO4", assertNotNull(store.fetchDevice(device.id)).ocvPreset)
            assertEquals(device.radioId, assertNotNull(store.fetchDevice(device.id)).radioId)
        } finally { connection.close() }
    }

    @Test fun newGenerationAndStoreReopenRetainCommittedOCVAndKnownRegionState() = runTest(scheduler) {
        val device = device()
        store.saveDevice(device)
        store.addDeviceKnownRegion(RADIO_A, "US915")
        val first = connection(1)
        first.services.deviceService.updateOCVSettings(device.id, "liFePO4", null)
        first.close()
        store.close()
        store = newStore()
        val second = connection(2)
        try {
            val actual = assertNotNull(store.fetchDevice(device.id))
            assertEquals("liFePO4", actual.ocvPreset)
            assertEquals(listOf("US915"), actual.knownRegions)
            assertEquals(2L, second.services.context.token.generation.value)
            second.services.deviceService.updateOCVSettings(device.id, "liIon", null)
            assertEquals("liIon", assertNotNull(store.fetchDevice(device.id)).ocvPreset)
        } finally { second.close() }
    }

    @Test fun realSettingsReadbackFeedsTheGenerationGuardedRoomConsumerWithoutAnOptimisticSnapshot() = runTest(scheduler) {
        val device = device()
        store.saveDevice(device)
        val connection = connection(1)
        try {
            val subscription = connection.services.settingsService.events()
            val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                subscription.events.collect { event ->
                    val update = event.event
                    if (event.token == connection.signals.snapshot.value.token && update is SettingsEvent.DeviceUpdated) {
                        val current = assertNotNull(store.fetchDevice(device.id))
                        store.saveDevice(current.updating(update.info).copy(appliedRadioPresetID = update.appliedRadioPresetID))
                    }
                }
            }
            val info = connection.services.settingsService.setNodeNameVerified("Verified")
            runCurrent()
            assertEquals("Verified", info.name)
            assertEquals("Verified", assertNotNull(store.fetchDevice(device.id)).nodeName)
            subscription.close()
            collector.join()
        } finally { connection.close() }
    }

    private class Signals(sessionToken: SessionToken) : ConnectionSignals {
        override val snapshot = MutableStateFlow(ConnectionSnapshot(
            DeviceConnectionState.READY, ConnectionState.Connected, null,
            ConnectionIntent.WantsConnection(), sessionToken, null,
        ))
        override suspend fun subscribeTransitions(): ConnectionSubscription =
            throw UnsupportedOperationException("No fake transition monitor is substituted")
    }

    private class Radio : MeshTransport {
        val mock = MockTransport()
        var name = "Test"
        override suspend fun connect() = mock.connect()
        override suspend fun disconnect() = mock.disconnect()
        override suspend fun isConnected() = mock.isConnected()
        override suspend fun receivedData(): Flow<Bytes> = mock.receivedData()
        override suspend fun send(data: Bytes) {
            mock.send(data)
            when {
                data == PacketBuilder.appStart("MCore") -> mock.simulateReceive(selfPacket(name))
                data == PacketBuilder.setName("Verified") -> { name = "Verified"; mock.simulateOK() }
                else -> throw AssertionError("Unexpected real service wire command in the Room consumer")
            }
        }
        companion object {
            private fun little(value: Int) = Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
            private fun selfPacket(name: String): Bytes =
                Bytes.of(ResponseCode.SELF_INFO.rawValue.toInt(), 1, 22, 22) + Bytes(ByteArray(32) { 1 }) +
                    little(0) + little(0) + Bytes.of(0, 0, 0, 0) +
                    little(915000) + little(125000) + Bytes.of(7, 5) + Bytes.utf8(name)
        }
    }

    private class Connection(
        val radio: Radio, val session: MeshCoreSession, val services: DeviceSettingsServices,
        val signals: Signals, private val owner: CoroutineScope,
    ) {
        suspend fun close() { services.close(); session.stop(); owner.cancel() }
    }

    private suspend fun TestScope.connection(generation: Long): Connection {
        val radio = Radio()
        val sessionClock = object : SessionClock {
            override val now: Duration get() = testScheduler.currentTime.milliseconds
            override val wallClock: Clock get() = clock
            override suspend fun sleepFor(duration: Duration) = delay(duration)
        }
        val connectionOwner = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val session = MeshCoreSession(
            radio, SessionConfiguration(defaultTimeout = 1.0, clientIdentifier = "MCore"),
            sessionClock, connectionOwner.coroutineContext,
        )
        session.start()
        val token = SessionToken(ProcessEpoch(UUID.fromString("00000000-0000-0000-0000-000000000031")), Generation(generation), RADIO_A)
        val signals = Signals(token)
        val services = DeviceSettingsServiceFactory(
            store, store, store, sessionClock, StandardTestDispatcher(testScheduler),
        ).create(SessionInputs(token, session, signals, connectionOwner))
        return Connection(radio, session, services, signals, connectionOwner)
    }
}
