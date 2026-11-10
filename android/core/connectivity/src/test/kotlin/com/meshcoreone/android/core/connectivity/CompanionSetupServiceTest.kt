// AndroidOnly: WP-206 Companion association session behavior (chooser outcomes, orphan cleanup, failures, Settings removal, endpoint identity).
package com.meshcoreone.android.core.connectivity

import com.meshcoreone.android.core.connectivity.pairing.AssociationRecordStore
import com.meshcoreone.android.core.connectivity.pairing.ChooserRequests
import com.meshcoreone.android.core.connectivity.pairing.CompanionAssociation
import com.meshcoreone.android.core.connectivity.pairing.CompanionFailureMapping
import com.meshcoreone.android.core.connectivity.pairing.CompanionFailureOutcome
import com.meshcoreone.android.core.connectivity.pairing.CompanionDiscoveryCriteria
import com.meshcoreone.android.core.connectivity.pairing.InMemoryAssociationRecordStore
import com.meshcoreone.android.core.connectivity.pairing.CompanionAssociationEvents
import com.meshcoreone.android.core.connectivity.pairing.CompanionChooserHost
import com.meshcoreone.android.core.connectivity.pairing.CompanionDeviceGateway
import com.meshcoreone.android.core.connectivity.pairing.CompanionPairingService
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupDelegate
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupError
import com.meshcoreone.android.core.connectivity.pairing.CompanionSetupService
import com.meshcoreone.android.core.connectivity.pairing.DeviceEndpointIdentity
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingFactory
import com.meshcoreone.android.core.connectivity.pairing.EndpointStability
import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.support.Scenario
import com.meshcoreone.android.core.connectivity.support.association
import com.meshcoreone.android.core.connectivity.support.scenario
import com.meshcoreone.android.core.connectivity.support.settle
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import org.junit.Test

class CompanionSetupServiceTest {
    private class FakeGateway : CompanionDeviceGateway {
        override var isSupported = true
        var associations = mutableListOf<CompanionAssociation>()
        var events: CompanionAssociationEvents? = null
        val disassociated = mutableListOf<UUID>()
        val observing = linkedSetOf<UUID>()
        var associateCalls = 0
        var afterDisassociate: suspend (CompanionAssociation) -> Unit = {}
        override fun observePresence(association: CompanionAssociation, observe: Boolean) {
            if (observe) observing += association.deviceId else observing -= association.deviceId
        }
        override fun associations(): List<CompanionAssociation> = associations.toList()
        override fun associate(events: CompanionAssociationEvents) { associateCalls++; this.events = events }
        override suspend fun disassociate(association: CompanionAssociation) {
            disassociated += association.deviceId
            associations.removeAll { it.deviceId == association.deviceId }
            afterDisassociate(association)
        }
    }

    private class Recorder : CompanionSetupDelegate {
        val removed = mutableListOf<UUID>()
        val failed = mutableListOf<UUID>()
        override fun companionSetupDidRemoveAccessory(deviceId: UUID) { removed += deviceId }
        override fun companionSetupDidFailPairing(deviceId: UUID) { failed += deviceId }
    }

    private fun Scenario.service(
        gateway: FakeGateway,
        host: CompanionChooserHost? = CompanionChooserHost { _, _ -> },
        records: AssociationRecordStore = InMemoryAssociationRecordStore(),
    ) = CompanionSetupService(gateway, scope, clock, chooserHost = host, records = records)

    @Test fun `cancelled clear finishes issued removal but never starts the next accessory`() = scenario {
        val first = association(UUID.randomUUID(), "first")
        val second = association(UUID.randomUUID(), "second")
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val release = kotlinx.coroutines.CompletableDeferred<Unit>()
        val gateway = FakeGateway().apply {
            associations += listOf(first, second)
            afterDisassociate = { accessory ->
                if (accessory == first) {
                    entered.complete(Unit)
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { release.await() }
                }
            }
        }
        val service = service(gateway)
        val pairing = CompanionPairingService(service)
        pairing.activate()
        val clearing = scope.async { pairing.clearStaleRegistrations() }
        try {
            entered.await()
            assertEquals(listOf(first.deviceId), gateway.disassociated)
            clearing.cancel()
            release.complete(Unit)
            assertFailsWith<CancellationException> { clearing.await() }
            settle()
            assertEquals(listOf(first.deviceId), gateway.disassociated)
            assertEquals(listOf(second), service.pairedAccessories)
            pairing.clearStaleRegistrations()
            assertEquals(listOf(first.deviceId, second.deviceId), gateway.disassociated)
            assertTrue(service.pairedAccessories.isEmpty())
        } finally { release.complete(Unit); clearing.cancel() }
    }

    @Test fun `association completes the picker and lists the new association without opening GATT`() = scenario {
        val gateway = FakeGateway()
        val launched = mutableListOf<Any>()
        val service = service(gateway, CompanionChooserHost { chooser, _ -> launched += chooser })
        service.activateSession()
        val picker = scope.async { service.showPicker() }
        settle()
        gateway.events!!.onChooserPending("intent-sender", 1)
        val created = association(UUID.randomUUID(), "MeshCore-1")
        gateway.associations += created
        gateway.events!!.onAssociationCreated(created)
        assertEquals(created.deviceId, picker.await())
        assertEquals(listOf<Any>("intent-sender"), launched)
        assertEquals(listOf(created), service.pairedAccessories)
        assertEquals("selected", service.lastPickerOutcome)
        // The gateway contract has no GATT/bond operation: association is not a connection.
        assertEquals(1, gateway.associateCalls)
    }

    @Test fun `dismissed chooser maps to DevicePairingError_Cancelled through the pairing service`() = scenario {
        val gateway = FakeGateway()
        val service = service(gateway)
        val pairing = CompanionPairingService(service)
        pairing.activate()
        val discovery = scope.async { runCatching { pairing.discoverDevice() } }
        settle()
        gateway.events!!.onDismissed()
        assertIs<DevicePairingError.Cancelled>(discovery.await().exceptionOrNull())
    }

    @Test fun `no foreground chooser host maps to PickerUnavailable`() = scenario {
        val gateway = FakeGateway()
        val pairing = CompanionPairingService(service(gateway, host = null))
        pairing.activate()
        assertFailsWith<DevicePairingError.PickerUnavailable> { pairing.discoverDevice() }
        assertEquals(0, gateway.associateCalls)
    }

    @Test fun `unsupported companion setup refuses activation and the factory selects the scan fallback`() = scenario {
        val gateway = FakeGateway().apply { isSupported = false }
        assertFailsWith<CompanionSetupError.SessionNotActive> { service(gateway).activateSession() }
        assertEquals(DevicePairingFactory.Kind.ScanFallback, DevicePairingFactory.select(companionDeviceSetupSupported = false))
        assertIs<BluetoothScanPairingService>(DevicePairingFactory.make(false, { error("unused") }))
        assertEquals(DevicePairingFactory.Kind.Companion, DevicePairingFactory.select(companionDeviceSetupSupported = true))
    }

    @Test fun `second picker while one is active is rejected as already in progress`() = scenario {
        val gateway = FakeGateway()
        val pairing = CompanionPairingService(service(gateway))
        pairing.activate()
        val first = scope.async { runCatching { pairing.discoverDevice() } }
        settle()
        assertFailsWith<DevicePairingError.AlreadyInProgress> { pairing.discoverDevice() }
        gateway.events!!.onDismissed()
        first.await()
    }

    @Test fun `association completed after the awaiting coroutine was cancelled is disassociated as an orphan`() = scenario {
        val gateway = FakeGateway()
        val service = service(gateway)
        service.activateSession()
        val picker = scope.async { service.showPicker() }
        settle()
        val events = gateway.events!!
        picker.cancel()
        assertIs<CancellationException>(runCatching { picker.await() }.exceptionOrNull())
        val orphan = association(UUID.randomUUID(), "late")
        gateway.associations += orphan
        events.onAssociationCreated(orphan)
        settle()
        assertEquals(listOf(orphan.deviceId), gateway.disassociated)
        assertEquals("orphanedAfterCancellation", service.lastPickerOutcome)
        assertTrue(service.pairedAccessories.none { it.deviceId == orphan.deviceId })
    }

    @Test fun `pairing failure for a listed association notifies the delegate and removes it`() = scenario {
        val gateway = FakeGateway()
        val failed = association(UUID.randomUUID(), "wrong-pin")
        gateway.associations += failed
        val service = service(gateway)
        val recorder = Recorder()
        service.delegate = recorder
        service.activateSession()
        val picker = scope.async { runCatching { service.showPicker() } }
        settle()
        gateway.events!!.onFailure(CompanionSetupError.PairingFailed("PIN"), failed)
        assertIs<CompanionSetupError.PairingFailed>(picker.await().exceptionOrNull())
        settle()
        assertEquals(listOf(failed.deviceId), recorder.failed)
        assertEquals(listOf(failed.deviceId), gateway.disassociated)
    }

    @Test fun `associations removed in Settings are reported once on refresh`() = scenario {
        val gateway = FakeGateway()
        val kept = association(UUID.randomUUID(), "kept")
        val gone = association(UUID.randomUUID(), "gone")
        gateway.associations += listOf(kept, gone)
        val service = service(gateway)
        val recorder = Recorder()
        service.delegate = recorder
        service.activateSession()
        gateway.associations.remove(gone)
        service.refreshAssociations()
        service.refreshAssociations()
        assertEquals(listOf(gone.deviceId), recorder.removed)
        assertEquals(listOf(kept), service.pairedAccessories)
    }

    @Test fun `invalidating the session fails a pending picker and clears associations`() = scenario {
        val gateway = FakeGateway()
        gateway.associations += association(UUID.randomUUID(), "a")
        val service = service(gateway)
        service.activateSession()
        val picker = scope.async { runCatching { service.showPicker() } }
        settle()
        service.invalidateSession()
        assertIs<CompanionSetupError.SessionInvalidated>(picker.await().exceptionOrNull())
        assertFalse(service.isSessionActive)
        assertTrue(service.pairedAccessories.isEmpty())
    }

    @Test fun `system rename is unsupported and removal of an unknown id is a no-op`() = scenario {
        val gateway = FakeGateway()
        val pairing = CompanionPairingService(service(gateway))
        pairing.activate()
        assertFalse(pairing.supportsSystemRename)
        assertTrue(pairing.hasSystemPairingRegistry)
        pairing.removeDevice(UUID.randomUUID())
        assertTrue(gateway.disassociated.isEmpty())
    }

    @Test fun `endpoint ids are stable per canonical address and distinct across addresses`() {
        val lower = DeviceEndpointIdentity.deviceId("c4:de:e2:11:22:33")
        assertEquals(lower, DeviceEndpointIdentity.deviceId("C4:DE:E2:11:22:33"))
        assertNotEquals(lower, DeviceEndpointIdentity.deviceId("C4:DE:E2:11:22:34"))
        assertFailsWith<IllegalArgumentException> { DeviceEndpointIdentity.deviceId("not-an-address") }
        // The association id flows into the runtime pairing handle.
        assertEquals(7, association(lower, "x", 7).endpoint.handle.associationId)
    }

    @Test fun `rotating private addresses are ephemeral until bonded while static addresses are stable`() {
        assertEquals(EndpointStability.Stable, DeviceEndpointIdentity.stability("C4:00:00:00:00:01", bonded = false))
        assertEquals(EndpointStability.Ephemeral, DeviceEndpointIdentity.stability("4A:00:00:00:00:01", bonded = false))
        assertEquals(EndpointStability.Ephemeral, DeviceEndpointIdentity.stability("0A:00:00:00:00:01", bonded = false))
        assertEquals(EndpointStability.Stable, DeviceEndpointIdentity.stability("4A:00:00:00:00:01", bonded = true))
        assertNull(association(UUID.randomUUID(), "n", -1).endpoint.associationId)
    }

    @Test fun `presence is observed for every association and stopped before removal, never for orphans`() = scenario {
        val gateway = FakeGateway()
        val existing = association(UUID.randomUUID(), "existing")
        gateway.associations += existing
        val service = service(gateway)
        service.activateSession()
        assertEquals(setOf(existing.deviceId), gateway.observing)
        val picker = scope.async { service.showPicker() }
        settle()
        val created = association(UUID.randomUUID(), "new")
        gateway.associations += created
        gateway.events!!.onAssociationCreated(created)
        picker.await()
        assertEquals(setOf(existing.deviceId, created.deviceId), gateway.observing)
        service.removeAccessory(existing)
        assertEquals(setOf(created.deviceId), gateway.observing)
        val cancelled = scope.async { service.showPicker() }
        settle()
        val events = gateway.events!!
        cancelled.cancel()
        runCatching { cancelled.await() }
        val orphan = association(UUID.randomUUID(), "orphan")
        events.onAssociationCreated(orphan)
        settle()
        assertTrue(orphan.deviceId !in gateway.observing)
    }

    @Test fun `advertised names are recorded and survive a new session where CDM lists no name`() = scenario {
        val records = InMemoryAssociationRecordStore()
        val gateway = FakeGateway()
        val service = service(gateway, records = records)
        service.activateSession()
        val picker = scope.async { service.showPicker() }
        settle()
        val created = association(UUID.randomUUID(), "MeshCore-A1")
        gateway.associations += created.copy(displayName = CompanionDiscoveryCriteria.DEFAULT_ACCESSORY_NAME)
        gateway.events!!.onAssociationCreated(created)
        picker.await()
        assertEquals("MeshCore-A1", service.accessory(created.deviceId)?.displayName)
        val restarted = service(gateway, records = records)
        restarted.activateSession()
        assertEquals("MeshCore-A1", restarted.accessory(created.deviceId)?.displayName)
    }

    @Test fun `a name learned after creation updates the association`() = scenario {
        val gateway = FakeGateway()
        val service = service(gateway)
        service.activateSession()
        val picker = scope.async { service.showPicker() }
        settle()
        val created = association(UUID.randomUUID(), CompanionDiscoveryCriteria.DEFAULT_ACCESSORY_NAME)
        gateway.associations += created
        gateway.events!!.onAssociationCreated(created)
        picker.await()
        gateway.events!!.onAssociationNamed(created.deviceId, "MeshCore-B2")
        assertEquals("MeshCore-B2", service.accessory(created.deviceId)?.displayName)
    }

    @Test fun `a Settings removal made while the process was dead is reported on the next activation`() = scenario {
        val records = InMemoryAssociationRecordStore()
        val kept = association(UUID.randomUUID(), "kept")
        val gone = association(UUID.randomUUID(), "gone")
        records.remember(kept.deviceId, kept.displayName)
        records.remember(gone.deviceId, gone.displayName)
        val gateway = FakeGateway().apply { associations += kept }
        val service = service(gateway, records = records)
        val recorder = Recorder()
        service.delegate = recorder
        service.activateSession()
        service.refreshAssociations()
        assertEquals(listOf(gone.deviceId), recorder.removed)
        assertEquals(setOf(kept.deviceId), records.records().keys)
    }

    @Test fun `the chooser host is read at launch time and an abandoned request is never launched`() = scenario {
        val gateway = FakeGateway()
        val first = mutableListOf<Any>()
        val second = mutableListOf<Any>()
        val service = service(gateway, CompanionChooserHost { chooser, _ -> first += chooser })
        service.activateSession()
        val picker = scope.async { runCatching { service.showPicker() } }
        settle()
        service.setChooserHost(CompanionChooserHost { chooser, _ -> second += chooser })
        gateway.events!!.onChooserPending("sender", 1)
        assertTrue(first.isEmpty())
        assertEquals(listOf<Any>("sender"), second)
        gateway.events!!.onDismissed()
        picker.await()

        val detached = scope.async { runCatching { service.showPicker() } }
        settle()
        service.setChooserHost(null)
        gateway.events!!.onChooserPending("sender-2", 2)
        assertIs<CompanionSetupError.PickerRestricted>(detached.await().exceptionOrNull())

        service.setChooserHost(CompanionChooserHost { chooser, _ -> second += chooser })
        val abandoned = scope.async { service.showPicker() }
        settle()
        val events = gateway.events!!
        abandoned.cancel()
        runCatching { abandoned.await() }
        events.onChooserPending("late", 3)
        assertEquals(listOf<Any>("sender"), second, "An abandoned request does not present the chooser")
    }

    @Test fun `chooser results are correlated by request id so a late result never resolves a newer request`() {
        val requests = ChooserRequests<String>(capacity = 2)
        val old = requests.register("old")
        val new = requests.register("new")
        assertEquals("old", requests.take(old))
        assertNull(requests.take(old), "Finished requests are not reused")
        assertEquals("new", requests.take(new))
        val a = requests.register("a"); requests.register("b"); requests.register("c")
        assertNull(requests.take(a), "Bounded: the oldest abandoned request is evicted")
        requests.finish("b")
        assertEquals(1, requests.size)
    }

    @Test fun `CDM failure codes and pre-API-36 reason strings map to dismissal or typed errors`() {
        assertEquals(CompanionFailureOutcome.Dismissed, CompanionFailureMapping.fromCode(0))
        assertEquals(CompanionFailureOutcome.Dismissed, CompanionFailureMapping.fromCode(1))
        assertIs<CompanionSetupError.DiscoveryTimeout>((CompanionFailureMapping.fromCode(2) as CompanionFailureOutcome.Failed).error)
        assertIs<CompanionSetupError.ConnectionFailed>((CompanionFailureMapping.fromCode(3) as CompanionFailureOutcome.Failed).error)
        assertEquals(CompanionFailureOutcome.Dismissed, CompanionFailureMapping.fromReason("user_rejected"))
        assertEquals(CompanionFailureOutcome.Dismissed, CompanionFailureMapping.fromReason("canceled"))
        assertIs<CompanionSetupError.DiscoveryTimeout>((CompanionFailureMapping.fromReason("discovery_timeout") as CompanionFailureOutcome.Failed).error)
        assertIs<CompanionSetupError.ConnectionFailed>((CompanionFailureMapping.fromReason("internal_error") as CompanionFailureOutcome.Failed).error)
        assertIs<CompanionSetupError.ConnectionFailed>((CompanionFailureMapping.fromReason(null) as CompanionFailureOutcome.Failed).error)
    }
}
