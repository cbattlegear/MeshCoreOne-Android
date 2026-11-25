// PortedFrom: MC1Tests/ViewModels/NodeLocationCaptureTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.TelemetryResponse
import com.meshcoreone.android.core.protocol.lpp.LPPDecoder
import com.meshcoreone.android.core.protocol.lpp.LPPSensorType
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import com.meshcoreone.android.feature.remotenodes.support.OriginalCase
import com.meshcoreone.android.feature.remotenodes.support.VirtualClock
import com.meshcoreone.android.feature.remotenodes.support.runSuspend
import com.meshcoreone.android.feature.remotenodes.telemetry.swiftRounded
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/**
 * Swift wires the real `NodeSnapshotService` over the suite's own appending `StoringSnapshotPersister`;
 * here [SnapshotServiceFake] (same delegation) runs over [AppendingSnapshotPersister] (same append-only
 * rows), so the cases keep their source behavior.
 */
class NodeLocationCaptureTest {
    private val clock = VirtualClock()
    private val publicKey = Bytes(ByteArray(32) { 0x42 })

    /** One GPS point on channel 1, checked with the real decoder before use. */
    private fun gpsOnlyResponse(lat: Double, lon: Double, alt: Double = 0.0): TelemetryResponse {
        fun int24BE(scaled: Int): List<Byte> =
            listOf((scaled shr 16) and 0xFF, (scaled shr 8) and 0xFF, scaled and 0xFF).map { it.toByte() }
        val bytes = mutableListOf(0x01.toByte(), LPPSensorType.GPS.rawValue.toByte())
        bytes += int24BE(swiftRounded(lat * 10000).toInt())
        bytes += int24BE(swiftRounded(lon * 10000).toInt())
        bytes += int24BE(swiftRounded(alt * 100).toInt())
        val raw = Bytes(bytes.toByteArray())
        assertTrue(LPPDecoder.decode(raw).dataPoints.any { it.value is LPPValue.Gps })
        return TelemetryResponse(publicKey.prefix(6), null, raw)
    }

    private fun setUp(): Pair<NodeStatusStateHolder, SnapshotServiceFake> {
        val service = SnapshotServiceFake(AppendingSnapshotPersister(clock), clock)
        val model = NodeStatusStateHolder(clock, FakeFaults)
        model.configureForDirectTelemetry(publicKey)
        model.configure(contactOcv = { null }, nodeSnapshots = { service })
        return model to service
    }

    @Test
    @OriginalCase("NodeLocationCaptureTests::A GPS-only response persists the fix and exposes it live()")
    fun `a GPS-only response persists the fix and exposes it live`() = runSuspend {
        val (model, service) = setUp()
        model.handleTelemetryResponse(gpsOnlyResponse(37.7749, -122.4194))

        assertEquals(37.7749, model.state.value.currentLocationFix?.latitude)
        val snapshots = service.fetchSnapshots(publicKey)
        assertEquals(1, snapshots.size, "A GPS-only response still captures a snapshot")
        assertEquals(37.7749, snapshots.firstOrNull()?.latitude)
    }

    @Test
    @OriginalCase("NodeLocationCaptureTests::A (0,0) response captures no fix()")
    fun `a null-island response captures no fix`() = runSuspend {
        val (model, service) = setUp()
        model.handleTelemetryResponse(gpsOnlyResponse(0.0, 0.0))

        assertNull(model.state.value.currentLocationFix)
        assertTrue(service.fetchSnapshots(publicKey).isEmpty(), "A no-fix, no-telemetry response writes nothing")
    }

    @Test
    @OriginalCase("NodeLocationCaptureTests::A plausible altitude is captured and persisted alongside the fix()")
    fun `a plausible altitude is captured and persisted`() = runSuspend {
        val (model, service) = setUp()
        model.handleTelemetryResponse(gpsOnlyResponse(37.7749, -122.4194, alt = 42.0))

        assertEquals(42.0, model.state.value.currentLocationFix?.altitude)
        assertEquals(42.0, service.fetchSnapshots(publicKey).firstOrNull()?.altitude)
    }

    @Test
    @OriginalCase("NodeLocationCaptureTests::A sea-level altitude is retained, not dropped like null-island()")
    fun `a sea-level altitude is retained`() = runSuspend {
        val (model, _) = setUp()
        model.handleTelemetryResponse(gpsOnlyResponse(37.7749, -122.4194, alt = 0.0))

        assertEquals(0.0, model.state.value.currentLocationFix?.altitude)
    }

    @Test
    @OriginalCase("NodeLocationCaptureTests::An implausible altitude is dropped but the fix survives()")
    fun `an implausible altitude is dropped but the fix survives`() = runSuspend {
        val (model, _) = setUp()
        model.handleTelemetryResponse(gpsOnlyResponse(37.7749, -122.4194, alt = 50000.0))

        assertEquals(37.7749, model.state.value.currentLocationFix?.latitude)
        assertNull(model.state.value.currentLocationFix?.altitude, "Out-of-range altitude is dropped, not the fix")
    }
}
