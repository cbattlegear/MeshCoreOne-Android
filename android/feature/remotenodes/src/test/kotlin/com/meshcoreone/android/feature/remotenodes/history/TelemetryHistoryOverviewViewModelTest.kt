// PortedFrom: MC1Tests/ViewModels/TelemetryHistoryOverviewViewModelTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.TEST_RADIO
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.bytes
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TelemetryHistoryOverviewViewModelTest {
    private val testPublicKey = bytes(32, 0xAB)
    private val clock = VirtualClock()
    private val store = InMemoryHistoryStore(clock)

    private fun viewModel() = TelemetryHistoryOverviewStateHolder(clock, UTC, EN_US, MeasurementSystem.METRIC, englishTitle)

    private suspend fun TelemetryHistoryOverviewStateHolder.load() = loadData(store, testPublicKey, TEST_RADIO)

    private fun contact(
        publicKey: Bytes = testPublicKey,
        name: String = "Test Repeater",
        lastAdvertTimestamp: UInt = 0u,
        ocvPreset: String? = null,
    ) = ContactDTO(
        radioId = TEST_RADIO, publicKey = publicKey, name = name, typeRawValue = ContactType.REPEATER.rawValue,
        outPathLength = 0u, lastAdvertTimestamp = lastAdvertTimestamp, lastHeardTimestamp = null, ocvPreset = ocvPreset,
    )

    private fun saveBatteryOnly(millivolts: Int, timestamp: java.time.Instant = clock.now) =
        store.saveNodeStatusSnapshot(testPublicKey, timestamp = timestamp, batteryMillivolts = millivolts.toUShort())

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::loadData fetches snapshots from persistence store()")
    fun `loadData fetches snapshots from persistence store`() = runSuspend {
        store.saveNodeStatusSnapshot(
            testPublicKey, batteryMillivolts = 3800u, lastSNR = 8.0, lastRSSI = -90, noiseFloor = -120,
            packetsSent = 500u, packetsReceived = 1000u,
        )
        store.saveNodeStatusSnapshot(
            testPublicKey, batteryMillivolts = 3750u, lastSNR = 7.5, lastRSSI = -92, noiseFloor = -118,
            packetsSent = 600u, packetsReceived = 1100u,
        )
        val viewModel = viewModel()
        viewModel.load()
        assertEquals(2, viewModel.state.value.snapshots.size)
    }

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::loadData with no snapshots leaves empty array()")
    fun `loadData with no snapshots leaves empty array`() = runSuspend {
        val viewModel = viewModel()
        viewModel.load()
        assertTrue(viewModel.state.value.snapshots.isEmpty())
    }

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::loadData resolves OCV from contact preset()")
    fun `loadData resolves OCV from contact preset`() = runSuspend {
        store.saveContact(contact(ocvPreset = OCVPreset.LI_FE_PO4.rawValue))
        val viewModel = viewModel()
        viewModel.load()
        assertEquals(OCVPreset.LI_FE_PO4.ocvArray.toList(), viewModel.state.value.ocvArray.toList())
    }

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::loadData defaults to liIon when no contact found()")
    fun `loadData defaults to liIon when no contact found`() = runSuspend {
        val viewModel = viewModel()
        viewModel.load()
        assertEquals(OCVPreset.LI_ION.ocvArray.toList(), viewModel.state.value.ocvArray.toList())
    }

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::filteredSnapshots returns all when timeRange is .all()")
    fun `filteredSnapshots returns all when timeRange is all`() = runSuspend {
        saveBatteryOnly(3800)
        val viewModel = viewModel()
        viewModel.load()
        viewModel.setTimeRange(HistoryTimeRange.ALL)
        assertEquals(1, viewModel.filteredSnapshots.size)
    }

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::filteredSnapshots excludes old snapshots for .week range()")
    fun `filteredSnapshots excludes old snapshots for week range`() = runSuspend {
        val thirtyDaysAgo = clock.now.atZone(UTC).minusDays(30).toInstant()
        saveBatteryOnly(3600, timestamp = thirtyDaysAgo)
        saveBatteryOnly(3800)
        val viewModel = viewModel()
        viewModel.load()
        viewModel.setTimeRange(HistoryTimeRange.WEEK)
        assertEquals(1, viewModel.filteredSnapshots.size)
        assertEquals(3800u.toUShort(), viewModel.filteredSnapshots.first().batteryMillivolts)
    }

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::hasSnapshots reflects snapshot count()")
    fun `hasSnapshots reflects snapshot count`() = runSuspend {
        val viewModel = viewModel()
        assertFalse(viewModel.hasSnapshots)
        saveBatteryOnly(3800)
        viewModel.load()
        assertTrue(viewModel.hasSnapshots)
    }

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::hasTelemetryData returns true when telemetry entries exist()")
    fun `hasTelemetryData returns true when telemetry entries exist`() = runSuspend {
        val id = saveBatteryOnly(3800)
        val viewModel = viewModel()
        viewModel.load()
        assertFalse(viewModel.hasTelemetryData, "Should be false with no telemetry entries")

        store.updateSnapshotTelemetry(id, listOf(TelemetrySnapshotEntry(0, "Voltage", 3.8)))
        viewModel.load()
        assertTrue(viewModel.hasTelemetryData, "Should be true after adding telemetry entries")
    }

    @Test @OriginalCase("TelemetryHistoryOverviewViewModelTests::hasNeighborData returns true when neighbor snapshots exist()")
    fun `hasNeighborData returns true when neighbor snapshots exist`() = runSuspend {
        val id = saveBatteryOnly(3800)
        val viewModel = viewModel()
        viewModel.load()
        assertFalse(viewModel.hasNeighborData, "Should be false with no neighbor snapshots")

        store.updateSnapshotNeighbors(id, listOf(NeighborSnapshotEntry(Bytes.of(0x01, 0x02), 6.5, 30)))
        viewModel.load()
        assertTrue(viewModel.hasNeighborData, "Should be true after adding neighbor snapshots")
    }

    @Test
    @OriginalCase("TelemetryHistoryOverviewViewModelTests::resolveNeighborName disambiguates short contact prefixes by resolver policy()")
    fun `resolveNeighborName disambiguates short contact prefixes by resolver policy`() = runSuspend {
        val older = contact(
            publicKey = Bytes.of(0xAB, 0xCD, 0x01) + bytes(29, 0), name = "A Older Repeater", lastAdvertTimestamp = 100u,
        )
        val newer = contact(
            publicKey = Bytes.of(0xAB, 0xCD, 0x02) + bytes(29, 0), name = "Z Newer Repeater", lastAdvertTimestamp = 200u,
        )
        store.saveContact(older)
        store.saveContact(newer)
        val viewModel = viewModel()
        viewModel.load()
        assertEquals("Z Newer Repeater", viewModel.resolveNeighborName(Bytes.of(0xAB, 0xCD)))
    }

    @Test
    @OriginalCase("TelemetryHistoryOverviewViewModelTests::Radio section surfaces for a snapshot carrying only packet-type counters()")
    fun `Radio section surfaces for a snapshot carrying only packet-type counters`() {
        val viewModel = viewModel()
        val onlyCounters = NodeStatusSnapshotDTO(
            nodePublicKey = testPublicKey, sentDirect = 100u, sentFlood = 200u, receivedDirect = 300u,
            receivedFlood = 400u, directDuplicates = 11u, floodDuplicates = 22u,
        )
        assertTrue(viewModel.hasRadioData(listOf(onlyCounters)))
        assertFalse(viewModel.hasRadioData(listOf(NodeStatusSnapshotDTO(nodePublicKey = testPublicKey))))
    }

    @Test
    @OriginalCase("TelemetryHistoryOverviewViewModelTests::channelGroups groups by channel and sorts by chartSortPriority then alphabetically()", "platform-adaptation")
    fun `channelGroups groups by channel and sorts by chartSortPriority then alphabetically`() = runSuspend {
        // Swift compares resolved L10n titles; the port carries resource ids, so this asserts the ids and
        // the English text they resolve to.
        val id = saveBatteryOnly(3800)
        store.updateSnapshotTelemetry(
            id,
            listOf(
                TelemetrySnapshotEntry(0, "Voltage", 3.8),
                TelemetrySnapshotEntry(0, "Temperature", 22.5),
                TelemetrySnapshotEntry(2, "Humidity", 55.0),
                TelemetrySnapshotEntry(2, "Voltage", 4.1),
            ),
        )
        val viewModel = viewModel()
        viewModel.load()
        val groups = viewModel.channelGroups

        assertEquals(2, groups.size, "Should have 2 channel groups")
        assertEquals(0L, groups[0].channel, "First group should be channel 0")
        assertEquals(2L, groups[1].channel, "Second group should be channel 2")
        assertEquals(2, groups[0].charts.size, "Channel 0 should have 2 charts")
        assertTitle(AppRemoteNodesStrings.remoteNodesStatusSensorVoltage, "Voltage", groups[0].charts[0].title)
        assertTitle(AppRemoteNodesStrings.remoteNodesStatusSensorTemperature, "Temperature", groups[0].charts[1].title)
        assertEquals(2, groups[1].charts.size, "Channel 2 should have 2 charts")
        assertTitle(AppRemoteNodesStrings.remoteNodesStatusSensorVoltage, "Voltage", groups[1].charts[0].title)
        assertTitle(AppRemoteNodesStrings.remoteNodesStatusSensorHumidity, "Humidity", groups[1].charts[1].title)
    }

    private fun assertTitle(expectedId: Int, english: String, actual: RemoteNodesText) {
        assertEquals(RemoteNodesText.Resource(expectedId), actual)
        assertEquals(english, actual.titleText(englishTitle))
    }
}
