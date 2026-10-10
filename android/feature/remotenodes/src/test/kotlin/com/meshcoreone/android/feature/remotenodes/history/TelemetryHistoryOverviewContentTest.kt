// AndroidOnly: WP-313 Native tests for the overview's section decisions, neighbour charts, sensor sections and loadData error paths.
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.NeighborSnapshotEntry
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.support.TEST_RADIO
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.bytes
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import com.meshcoreone.android.feature.remotenodes.telemetry.ChartAccent
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class TelemetryHistoryOverviewContentTest {
    private val key = bytes(32, 0xAB)
    private val clock = VirtualClock()
    private val store = InMemoryHistoryStore(clock)
    private val holder = TelemetryHistoryOverviewStateHolder(clock, UTC, EN_US, MeasurementSystem.US, englishTitle)

    private suspend fun load() = holder.loadData(store, key, TEST_RADIO)

    private fun content(showNeighbors: Boolean = true) =
        TelemetryHistoryOverviewContent.build(holder, showNeighbors, MeasurementSystem.US)

    private fun contact(ocvPreset: String?) =
        ContactDTO(radioId = TEST_RADIO, publicKey = key, name = "Repeater", lastHeardTimestamp = null, ocvPreset = ocvPreset)

    @Test
    fun `no snapshots shows the empty state`() = runSuspend {
        load()
        val empty = assertIs<TelemetryHistoryOverviewContent.Empty>(content())
        assertEquals(RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryOverviewTitle), empty.title)
        assertEquals(RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryNoSnapshotsMessage), empty.message)
    }

    @Test
    fun `radio-only snapshots show not-captured notices for sensors and neighbours`() = runSuspend {
        store.saveNodeStatusSnapshot(key, batteryMillivolts = 3800u)
        load()
        val loaded = assertIs<TelemetryHistoryOverviewContent.Loaded>(content())
        assertEquals(1, loaded.radio?.size)
        val sensors = assertIs<OverviewSection.NotCaptured>(loaded.sensors)
        assertEquals(
            RemoteNodesText.resource(
                R.string.l10n_app_remotenodes_remotenodes_history_sectionnotcaptured,
                RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistorySensorsSection),
            ),
            sensors.notice.text,
        )
        val neighbors = assertIs<OverviewSection.NotCaptured>(loaded.neighbors)
        assertEquals(RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryNeighborsSection), neighbors.notice.section)
        assertNull(assertIs<TelemetryHistoryOverviewContent.Loaded>(content(showNeighbors = false)).neighbors)
        assertEquals(OverviewExpansion(radio = true, sensors = false, neighbors = false), OverviewExpansion.initial(true))
        assertEquals(OverviewExpansion(radio = true, sensors = true, neighbors = false), OverviewExpansion.initial(false))
    }

    @Test
    fun `snapshots outside the range keep the sections but hide radio data`() = runSuspend {
        store.saveNodeStatusSnapshot(key, timestamp = clock.now.minusSeconds(60 * 86_400L), batteryMillivolts = 3800u)
        load()
        val loaded = assertIs<TelemetryHistoryOverviewContent.Loaded>(content())
        assertTrue(loaded.filteredSnapshots.isEmpty())
        assertNull(loaded.radio)
        assertIs<OverviewSection.NotCaptured>(loaded.sensors)
    }

    @Test
    fun `sensor sections are headed per channel only with several channels and use converted units`() = runSuspend {
        val id = store.saveNodeStatusSnapshot(key)
        store.updateSnapshotTelemetry(
            id, listOf(TelemetrySnapshotEntry(1, "Temperature", 25.0), TelemetrySnapshotEntry(1, "Voltage", 3.9)),
        )
        load()
        val single = assertIs<OverviewSection.Content<List<SensorChartSection>>>(
            assertIs<TelemetryHistoryOverviewContent.Loaded>(content()).sensors,
        ).value
        assertEquals(1, single.size)
        assertNull(single[0].header)
        val (voltage, temperature) = single[0].charts
        assertEquals(ChartAccent.ORANGE, voltage.series.single().color)
        assertEquals(MetricChartModel.voltageChartDomain(OCVPreset.LI_ION.ocvArray.toList(), voltage.series.single().dataPoints), voltage.yAxisDomain)
        assertEquals("°F", temperature.unit)
        assertEquals(ChartAccent.RED, temperature.series.single().color)
        assertEquals(77.0, temperature.series.single().dataPoints.single().value, 1e-6)
        assertNull(temperature.yAxisDomain)

        store.updateSnapshotTelemetry(
            id, listOf(TelemetrySnapshotEntry(3, "Mystery", 1.0), TelemetrySnapshotEntry(1, "Voltage", 3.9)),
        )
        load()
        val sections = TelemetrySensorCharts.sections(holder.channelGroups, OCVPreset.LI_ION.ocvArray.toList(), MeasurementSystem.US)
        assertEquals(
            listOf(1, 3).map { RemoteNodesText.resource(R.string.l10n_app_remotenodes_remotenodes_status_channel, it) },
            sections.map { it.header },
        )
        val mystery = sections[1].charts.single()
        assertEquals(RemoteNodesText.Verbatim("Mystery"), mystery.title)
        assertEquals("", mystery.unit)
        assertEquals(ChartAccent.CYAN, mystery.series.single().color)
    }

    @Test
    fun `charts on a channel sort by priority then localized title`() {
        val snapshot = NodeStatusSnapshotDTO(
            nodePublicKey = key,
            telemetryEntries = listOf(
                TelemetrySnapshotEntry(0, "Temperature", 20.0), TelemetrySnapshotEntry(0, "Pressure", 1000.0),
                TelemetrySnapshotEntry(0, "Temperature", 40.0), TelemetrySnapshotEntry(0, "Humidity", 50.0),
                TelemetrySnapshotEntry(0, "Voltage", 4.0), TelemetrySnapshotEntry(0, "aux", 1.0),
            ).snapshot(),
        )
        val titles = ChannelGroups.groups(listOf(snapshot), MeasurementSystem.METRIC, EN_US, englishTitle)
            .single().charts.map { it.title.titleText(englishTitle) }
        assertEquals(listOf("Voltage", "aux", "Humidity", "MCU temperature", "Pressure", "Temperature"), titles)
    }

    @Test
    fun `neighbour charts resolve names, fall back to hex and sort by name`() = runSuspend {
        val known = Bytes.of(0xAB, 0xCD)
        val first = store.saveNodeStatusSnapshot(key, timestamp = clock.now.minusSeconds(120))
        val second = store.saveNodeStatusSnapshot(key)
        store.updateSnapshotNeighbors(
            first, listOf(NeighborSnapshotEntry(Bytes.of(0x01, 0x0F), 6.5, 1), NeighborSnapshotEntry(known, 2.0, 1)),
        )
        store.updateSnapshotNeighbors(second, listOf(NeighborSnapshotEntry(known, 3.0, 1), NeighborSnapshotEntry(Bytes.of(0x00, 0x10), 1.0, 1)))
        store.saveContact(
            ContactDTO(radioId = TEST_RADIO, publicKey = Bytes.of(0xAB, 0xCD, 0x01) + bytes(29, 0), name = "Zed", lastHeardTimestamp = null),
        )
        load()
        val neighbors = assertIs<OverviewSection.Content<List<NeighborChart>>>(
            assertIs<TelemetryHistoryOverviewContent.Loaded>(content()).neighbors,
        ).value
        assertEquals(listOf("0010", "010F", "Zed"), neighbors.map { it.name })
        assertEquals(listOf(2.0, 3.0), neighbors[2].dataPoints.map { it.value })
        assertEquals(RemoteNodesText.Verbatim("Zed"), neighbors[2].chart.title)
        assertEquals(ChartAccent.BLUE, neighbors[2].chart.series.single().color)
        assertEquals("dB", neighbors[2].chart.unit)
    }

    @Test
    fun `swift string order compares unicode scalars of the NFC form`() {
        assertTrue(swiftStringCompare("Zed", "abc") < 0)
        assertEquals(0, swiftStringCompare("é", "é"))
        assertTrue(swiftStringCompare("�", "😀") < 0)
        assertTrue(swiftStringCompare("ab", "abc") < 0)
    }

    @Test
    fun `load failures retain prior data and expose the failure`() = runSuspend {
        store.saveNodeStatusSnapshot(key, batteryMillivolts = 3800u)
        store.saveContact(contact(OCVPreset.LI_FE_PO4.rawValue))
        load()
        assertEquals(1, holder.state.value.snapshots.size)
        assertEquals(1, holder.state.value.contacts.size)
        assertEquals(OCVPreset.LI_FE_PO4.ocvArray.toList(), holder.state.value.ocvArray)

        store.snapshotError = IOException("disk")
        store.contactError = IOException("disk")
        store.contactsError = IOException("disk")
        store.discoveredError = IOException("disk")
        load()
        assertEquals(1, holder.state.value.snapshots.size)
        assertEquals(OCVPreset.LI_FE_PO4.ocvArray.toList(), holder.state.value.ocvArray, "contact failure keeps the curve")
        assertEquals(1, holder.state.value.contacts.size)
        assertTrue(holder.state.value.discoveredNodes.isEmpty())
        assertTrue(holder.state.value.error is RemoteNodesText.Failure)
        assertFalse(holder.state.value.isLoading)
        store.snapshotError = null
        store.contactError = null
        store.contactsError = null
        store.discoveredError = null
        load()
        assertEquals(null, holder.state.value.error)
    }

    @Test
    fun `cancellation is never swallowed`() = runSuspend {
        store.snapshotError = CancellationException("stop")
        assertFailsWith<CancellationException> { load() }
        assertEquals(0, store.contactReads)
        assertFalse(holder.state.value.isLoading)
    }

    @Test
    fun `time range labels and picker title use the Swift strings`() {
        assertEquals(HistoryTimeRange.MONTH, HistoryTimeRange.DEFAULT)
        assertEquals(
            listOf(
                AppRemoteNodesStrings.remoteNodesHistoryWeek, AppRemoteNodesStrings.remoteNodesHistoryMonth,
                AppRemoteNodesStrings.remoteNodesHistoryThreeMonths, AppRemoteNodesStrings.remoteNodesHistoryAll,
            ).map { RemoteNodesText.Resource(it) },
            HistoryTimeRange.entries.map { it.label },
        )
        assertEquals(RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesHistoryTimeRange), HistoryTimeRange.pickerTitle)
    }
}
