// PortedFrom: MC1Tests/Views/RemoteNodes/ChannelGroupTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.history

import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.model.NodeStatusSnapshotDTO
import com.meshcoreone.android.core.model.TelemetrySnapshotEntry
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.feature.remotenodes.common.RemoteNodesText
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.bytes
import com.meshcoreone.android.feature.remotenodes.telemetry.MeasurementSystem
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class ChannelGroupTest {
    private val temperature = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusSensorTemperature)
    private val mcuTemperature = RemoteNodesText.Resource(AppRemoteNodesStrings.remoteNodesStatusSensorMcuTemperature)

    private fun snapshot(id: Int, entries: List<TelemetrySnapshotEntry>) = NodeStatusSnapshotDTO(
        id = UUID.fromString("00000000-0000-0000-0000-" + id.toString().padStart(12, '0')),
        nodePublicKey = bytes(32, 1),
        telemetryEntries = entries.snapshot(),
    )

    private fun groups(snapshots: List<NodeStatusSnapshotDTO>) =
        ChannelGroups.groups(snapshots, MeasurementSystem.METRIC, EN_US, englishTitle)

    @Test @OriginalCase("ChannelGroupTests::single temperature on channel 1 stays one Temperature chart()")
    fun `single temperature on channel 1 stays one Temperature chart`() {
        val groups = groups(listOf(snapshot(1, listOf(TelemetrySnapshotEntry(1, "Temperature", 21.0)))))
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].charts.size)
        assertEquals(temperature, groups[0].charts[0].title)
    }

    @Test @OriginalCase("ChannelGroupTests::two temperatures in one snapshot split into Temperature and MCU temperature()")
    fun `two temperatures in one snapshot split into Temperature and MCU temperature`() {
        val groups = groups(
            listOf(
                snapshot(
                    1,
                    listOf(TelemetrySnapshotEntry(1, "Temperature", 21.0), TelemetrySnapshotEntry(1, "Temperature", 38.0)),
                ),
            ),
        )
        val titles = groups[0].charts.map { it.title }
        assertTrue(temperature in titles)
        assertTrue(mcuTemperature in titles)
    }

    @Test @OriginalCase("ChannelGroupTests::first temperature in a later snapshot is not MCU()")
    fun `first temperature in a later snapshot is not MCU`() {
        val groups = groups(
            listOf(
                snapshot(1, listOf(TelemetrySnapshotEntry(1, "Temperature", 21.0))),
                snapshot(2, listOf(TelemetrySnapshotEntry(1, "Temperature", 22.0))),
            ),
        )
        assertEquals(1, groups[0].charts.size)
        assertEquals(2, groups[0].charts[0].dataPoints.size)
        assertEquals(temperature, groups[0].charts[0].title)
    }
}
