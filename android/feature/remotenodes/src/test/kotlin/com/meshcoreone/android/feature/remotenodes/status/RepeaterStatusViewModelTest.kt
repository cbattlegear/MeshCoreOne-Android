// PortedFrom: MC1Tests/ViewModels/RepeaterStatusViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.protocol.event.OwnerInfoResponse
import com.meshcoreone.android.feature.remotenodes.resolver.NeighborNameResolver
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import com.meshcoreone.android.feature.remotenodes.support.session
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test

/**
 * The Swift suite drives the real `NodeSnapshotService` over an in-memory `PersistenceStore`; here the
 * service is [SnapshotServiceFake] over [WindowedSnapshotPersister], which reproduces the store's
 * 15-minute enrich-or-insert window (see StatusTestFakes.kt).
 */
class RepeaterStatusViewModelTest {
    private val clock = VirtualClock()

    private fun viewModel(withSnapshots: Boolean = true): Pair<RepeaterStatusStateHolder, SnapshotServiceFake> {
        val service = SnapshotServiceFake(WindowedSnapshotPersister(clock), clock)
        val model = RepeaterStatusStateHolder(clock, FakeFaults, CoroutineScope(Dispatchers.Unconfined))
        model.helper.configure(contactOcv = { null }, nodeSnapshots = { if (withSnapshots) service else null })
        model.helper.setSession(session(name = "Test Repeater"))
        return model to service
    }

    private suspend fun RepeaterStatusStateHolder.applyStatus() {
        val status = statusResponse()
        helper.handleStatusResponse(status, rxAirtimeSeconds = status.rxAirtime, receiveErrors = status.receiveErrors)
    }

    @Test
    @OriginalCase("RepeaterStatusViewModelTests::Enrichment lost when snapshot is throttled on refresh()", "platform-adaptation")
    fun `enrichment persists after a throttled refresh`() = runSuspend {
        val (model, _) = viewModel()
        model.applyStatus()
        assertEquals(1, model.helper.fetchHistory().size, "First visit should save a snapshot")

        model.applyStatus()
        assertEquals(1, model.helper.fetchHistory().size, "Throttled save should not create a new snapshot")

        model.handleNeighboursResponse(neighboursResponse())
        val snapshots = model.helper.fetchHistory()
        assertEquals(false, snapshots.firstOrNull()?.neighborSnapshots?.isEmpty(), "Neighbor enrichment should persist")
    }

    @Test
    @OriginalCase("RepeaterStatusViewModelTests::Status stays unloaded until a status response is applied()", "platform-adaptation")
    fun `status stays unloaded until a response is applied`() = runSuspend {
        val (model, _) = viewModel()
        assertFalse(model.helper.state.value.statusLoaded, "Status should start unloaded")
        assertFalse(model.helper.state.value.statusExpanded, "Status should start collapsed")
        model.applyStatus()
        assertTrue(model.helper.state.value.statusLoaded, "Status should load after a response is applied")
    }

    @Test
    @OriginalCase("RepeaterStatusViewModelTests::Telemetry without status persists a telemetry-only snapshot()", "platform-adaptation")
    fun `telemetry without status persists a telemetry-only snapshot`() = runSuspend {
        val (model, _) = viewModel()
        assertTrue(model.helper.fetchHistory().isEmpty(), "No snapshot should exist before any response")

        model.helper.handleTelemetryResponse(telemetryResponse())

        val persisted = model.helper.fetchHistory().firstOrNull()
        assertNotNull(persisted, "Telemetry-only snapshot should persist when no status snapshot exists")
        assertEquals(1, persisted.telemetryEntries?.size, "Snapshot should carry the telemetry entry")
    }

    @Test
    @OriginalCase("RepeaterStatusViewModelTests::Neighbors expanded without status persist a neighbor-only snapshot()", "platform-adaptation")
    fun `neighbors without status persist a neighbor-only snapshot`() = runSuspend {
        val (model, _) = viewModel()
        assertTrue(model.helper.fetchHistory().isEmpty(), "No snapshot should exist before any response")

        model.handleNeighboursResponse(neighboursResponse())

        val persisted = model.helper.fetchHistory().firstOrNull()
        assertNotNull(persisted, "Neighbors-first should persist a neighbor-bearing snapshot")
        assertEquals(1, persisted.neighborSnapshots?.size)
        assertNull(persisted.uptimeSeconds, "A neighbor-only row carries no status yet")
    }

    @Test
    @OriginalCase(
        "RepeaterStatusViewModelTests::Status applied after telemetry-first enriches the telemetry-only snapshot()",
        "platform-adaptation",
    )
    fun `status after telemetry-first enriches the telemetry-only snapshot`() = runSuspend {
        val (model, _) = viewModel()
        model.helper.handleTelemetryResponse(telemetryResponse())

        val telemetryOnly = model.helper.fetchHistory().firstOrNull()
        assertNotNull(telemetryOnly, "Telemetry-first should persist a telemetry-only snapshot")
        assertNull(telemetryOnly.uptimeSeconds, "Telemetry-only snapshot should not carry status fields yet")

        val status = statusResponse()
        model.applyStatus()

        val snapshots = model.helper.fetchHistory()
        val enriched = snapshots.firstOrNull()
        assertEquals(1, snapshots.size, "In-window status capture should not create a second snapshot")
        assertNotNull(enriched?.uptimeSeconds, "Telemetry-first snapshot should be enriched with status fields")
        assertEquals(1, enriched?.telemetryEntries?.size, "Telemetry entry should be preserved")
        assertEquals(status.uptime, enriched?.uptimeSeconds, "Status uptime should be backfilled")
        assertEquals(status.batteryMillivolts, enriched?.batteryMillivolts, "Status battery should be backfilled")
    }

    @Test
    @OriginalCase("RepeaterStatusViewModelTests::Owner info response exposes the firmware version()")
    fun `owner info response exposes the firmware version`() {
        val model = RepeaterStatusStateHolder(clock, FakeFaults, CoroutineScope(Dispatchers.Unconfined))
        model.applyOwnerInfo(OwnerInfoResponse(firmwareVersion = "v1.16.0", nodeName = "Test Repeater", ownerInfo = "Hello"))
        assertEquals("v1.16.0", model.state.value.firmwareVersion, "Firmware version should be captured from owner info")
        assertEquals("Hello", model.state.value.ownerInfo, "Owner info text should still be captured")
    }

    @Test
    @OriginalCase("RepeaterStatusViewModelTests::Empty firmware string maps to nil so the row stays hidden()")
    fun `empty firmware string maps to null`() {
        val model = RepeaterStatusStateHolder(clock, FakeFaults, CoroutineScope(Dispatchers.Unconfined))
        model.applyOwnerInfo(OwnerInfoResponse(firmwareVersion = "", nodeName = "Test Repeater", ownerInfo = ""))
        assertNull(model.state.value.firmwareVersion, "An empty firmware string must map to null")
    }

    @Test
    @OriginalCase("RepeaterStatusViewModelTests::Neighbours response captures the key display width from the device hash size()")
    fun `neighbours response captures the key display width`() = runSuspend {
        val model = RepeaterStatusStateHolder(clock, FakeFaults, CoroutineScope(Dispatchers.Unconfined))
        model.configure(repeaterAdmin = { null }, contactOcv = { null }, nodeSnapshots = { null }, deviceHashSize = { 3 })
        assertEquals(
            NeighborNameResolver.MINIMUM_KEY_DISPLAY_BYTE_COUNT, model.state.value.neighborKeyDisplayByteCount,
            "Width should start at the floor before any neighbours response",
        )
        model.handleNeighboursResponse(neighboursResponse())
        assertEquals(3, model.state.value.neighborKeyDisplayByteCount, "Width should be captured from the device hash size at fetch")
    }

    @Test
    @OriginalCase("RepeaterStatusViewModelTests::Captured key display width survives a later disconnect()")
    fun `captured key display width survives a later disconnect`() = runSuspend {
        val model = RepeaterStatusStateHolder(clock, FakeFaults, CoroutineScope(Dispatchers.Unconfined))
        var hashSize: Int? = 3
        model.configure(repeaterAdmin = { null }, contactOcv = { null }, nodeSnapshots = { null }, deviceHashSize = { hashSize })
        model.handleNeighboursResponse(neighboursResponse())
        hashSize = null
        assertEquals(3, model.state.value.neighborKeyDisplayByteCount, "A disconnect must not reflow identifiers already on screen")
    }
}
