// AndroidOnly: WP-313 Production adapters over the real process store and synthetic per-connection service graph.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.remote.BinaryProtocolError
import com.meshcoreone.android.core.services.remote.RemoteNodeError
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AppRemoteNodesIntegrationTest : RoomProcessTest() {
    @Test fun catalogOcvAndSnapshotsUseTheActualRadioPartitionedProcessStore() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val radio = assertNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(radioId = radio, publicKey = key(42), name = "Synthetic remote", typeRawValue = 2u)
            val session = RemoteNodeSessionDTO(
                radioId = radio, publicKey = contact.publicKey, name = contact.name, role = RemoteNodeRole.REPEATER,
                isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN,
            )
            store.saveContact(contact)
            store.saveRemoteNodeSessionDTO(session)
            val feature = h.container.remoteNodesFeature
            val catalog = feature.catalog()
            assertEquals(contact.id, catalog.contacts.single { it.publicKey == contact.publicKey }.id)
            assertEquals(session, catalog.sessions.single { it.publicKey == contact.publicKey })
            val bound = assertNotNull(feature.services())
            assertEquals(radio, bound.connectedRadioId())
            val ocv = assertNotNull(bound.contactOcv())
            ocv.updateContactOCVSettings(contact.id, OCVPreset.LI_FE_PO4.rawValue, null)
            assertEquals(OCVPreset.LI_FE_PO4.rawValue, ocv.getContact(radio, contact.publicKey)?.ocvPreset)
            val snapshots = assertNotNull(bound.nodeSnapshots())
            val telemetry = listOf(TelemetrySnapshotEntry(1, "Temperature", 22.5)).snapshot()
            assertNotNull(snapshots.recordSnapshot(contact.publicKey, null, telemetry, null, null))
            assertEquals(telemetry, snapshots.fetchSnapshots(contact.publicKey).single().telemetryEntries)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED); h.settle()
            assertNull(bound.repeaterAdmin())
            assertNull(bound.roomAdmin())
            assertNull(bound.login())
            assertEquals(telemetry, assertNotNull(bound.historyStore()).fetchNodeStatusSnapshots(contact.publicKey, null).single().telemetryEntries)
            assertEquals(session.id, feature.catalog().sessions.single { it.publicKey == contact.publicKey }.id)
        } finally { h.container.close() }
    }

    @Test fun capturedPortsRejectOperationsAfterDisconnectAndCannotReachTheReplacementRadio() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            val old = assertNotNull(h.container.remoteNodesFeature.services())
            val radio = assertNotNull(old.connectedRadioId())
            val repeater = assertNotNull(old.repeaterAdmin())
            val room = assertNotNull(old.roomAdmin())
            val key = EntityKey(radio, java.util.UUID.randomUUID())
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED); h.settle()
            assertFailsWith<RemoteNodeError.NotConnected> { repeater.sendRawCommand(key, "reboot", 2.seconds) }
            assertFailsWith<RemoteNodeError.NotConnected> { room.requestStatus(key, null) }
            h.manager.connect(h.target()); h.settle(); h.assertReady()
            assertNull(old.repeaterAdmin())
            assertNull(old.roomAdmin())
            assertNull(old.connectedRadioId())
            assertNotNull(h.container.remoteNodesFeature.services()?.repeaterAdmin())
            assertFailsWith<RemoteNodeError.NotConnected> { repeater.fetchAllNeighbors(key, null) }
        } finally { h.container.close() }
    }

    @Test fun faultClassifierUsesExactTypedFirmwareAndSessionCases() {
        assertTrue(AppRemoteNodeFaults.isTimeout(RemoteNodeError.Timeout()))
        assertFalse(AppRemoteNodeFaults.isTimeout(BinaryProtocolError.Timeout()))
        assertTrue(AppRemoteNodeFaults.isRemoteNoResponseYet(RemoteNodeError.SessionError(MeshCoreException.DeviceError(10u))))
        assertTrue(AppRemoteNodeFaults.isRemoteNoResponseYet(BinaryProtocolError.SessionError(MeshCoreException.DeviceError(10u))))
        assertFalse(AppRemoteNodeFaults.isRemoteNoResponseYet(RemoteNodeError.SessionError(MeshCoreException.DeviceError(9u))))
        assertTrue(AppRemoteNodeFaults.isBinarySessionTimeout(BinaryProtocolError.SessionError(MeshCoreException.Timeout())))
        assertFalse(AppRemoteNodeFaults.isBinarySessionTimeout(RemoteNodeError.Timeout()))
    }
}
