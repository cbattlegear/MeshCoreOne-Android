// AndroidOnly: WP-101 Prizm Mobile Observer tests (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.health

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.CoreStats
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.PacketStats
import com.meshcoreone.android.core.protocol.event.RadioStats
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.parser.PacketParser
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

private val TS = Instant.parse("2026-11-27T12:05:00Z")

class HealthRecordSerializerTest {
    private val full = HealthSnapshot(
        batteryMillivolts = 3_987, uptimeSeconds = 86_400, noiseFloorDbm = -112, txAirSeconds = 42, rxAirSeconds = 1_234,
        rxPackets = 5_000, txPackets = 300, rxErrors = 7,
    )

    @Test fun matchesTheSpecKeysInOrder() {
        assertEquals(
            "{\"ts\":\"2026-11-27T12:05:00Z\",\"batt_mv\":3987,\"uptime_s\":86400,\"noise_floor\":-112,\"tx_air_s\":42," +
                "\"rx_air_s\":1234,\"rx_pkts\":5000,\"tx_pkts\":300,\"rx_errors\":7,\"ble_connected\":true," +
                "\"lat\":46.877200,\"lon\":-96.789800}",
            HealthRecordSerializer.toJson(HealthRecord(TS, full, bleConnected = true, latitude = 46.8772, longitude = -96.7898)),
        )
    }

    @Test fun absentFieldsAndLocationAreOmitted() {
        assertEquals(
            "{\"ts\":\"2026-11-27T12:05:00Z\",\"batt_mv\":3700,\"ble_connected\":true}",
            HealthRecordSerializer.toJson(HealthRecord(TS, HealthSnapshot(batteryMillivolts = 3_700), true)),
        )
        assertEquals(
            "{\"ts\":\"2026-11-27T12:05:00Z\",\"ble_connected\":false}",
            HealthRecordSerializer.toJson(HealthRecord(TS, HealthSnapshot(), false)),
        )
    }

    @Test fun batteryIsMillivoltsNeverAPercentage() {
        val json = HealthRecordSerializer.toJson(HealthRecord(TS, full, true))
        assertTrue(json.contains("\"batt_mv\":3987"))
        for (bad in listOf("pct", "percent", "\"battery\"", "level")) assertFalse(json.contains(bad), bad)
    }

    @Test fun latAndLonAreBothPresentOrBothAbsent() {
        assertFailsWith<IllegalArgumentException> { HealthRecord(TS, full, true, latitude = 1.0, longitude = null) }
        assertFailsWith<IllegalArgumentException> { HealthRecord(TS, full, true, latitude = null, longitude = 1.0) }
    }
}

/** Frames are laid out exactly like examples/companion_radio/MyMesh.cpp builds them (little endian, memcpy). */
class FirmwareLayoutTest {
    private fun le(value: Long, bytes: Int) = ByteArray(bytes) { ((value shr (8 * it)) and 0xff).toByte() }

    @Test fun commandsAreTheFirmwareOpcodes() {
        assertEquals("3800", PacketBuilder.getStatsCore().hexString) // CMD_GET_STATS 56, STATS_TYPE_CORE 0
        assertEquals("3801", PacketBuilder.getStatsRadio().hexString)
        assertEquals("3802", PacketBuilder.getStatsPackets().hexString)
        assertEquals("14", PacketBuilder.getBattery().hexString) // CMD_GET_BATT_AND_STORAGE 20
    }

    @Test fun coreStatsFrame() {
        // RESP_CODE_STATS 24, STATS_TYPE_CORE 0, battery_mv u16, uptime_secs u32, _err_flags u16, queue_len u8
        val frame = Bytes(byteArrayOf(24, 0) + le(3_987, 2) + le(86_400, 4) + le(0x0102, 2) + byteArrayOf(5))
        val stats = (PacketParser.parse(frame) as MeshEvent.StatsCore).stats
        assertEquals(CoreStats(3_987u, 86_400u, 0x0102u, 5u), stats)
    }

    @Test fun radioStatsFrame() {
        // STATS_TYPE_RADIO 1, noise_floor i16, last_rssi i8, last_snr i8 (x4), tx_air_secs u32, rx_air_secs u32
        val frame = Bytes(byteArrayOf(24, 1) + le(-112L and 0xffffL, 2) + byteArrayOf(-95, 22) + le(42, 4) + le(1_234, 4))
        val stats = (PacketParser.parse(frame) as MeshEvent.StatsRadio).stats
        assertEquals(RadioStats((-112).toShort(), (-95).toByte(), 5.5, 42u, 1_234u), stats)
    }

    @Test fun packetStatsFrame() {
        // STATS_TYPE_PACKETS 2, recv, sent, n_sent_flood, n_sent_direct, n_recv_flood, n_recv_direct, n_recv_errors (all u32)
        val frame = Bytes(byteArrayOf(24, 2) + listOf(5_000L, 300, 100, 200, 4_000, 1_000, 7).flatMap { le(it, 4).toList() })
        val stats = (PacketParser.parse(frame) as MeshEvent.StatsPackets).stats
        assertEquals(PacketStats(5_000u, 300u, 100u, 200u, 4_000u, 1_000u, 7u), stats)
    }

    @Test fun batteryAndStorageFrameCarriesMillivoltsFirst() {
        // RESP_CODE_BATT_AND_STORAGE 12: battery_millivolts u16, used u32, total u32 (KB)
        val frame = Bytes(byteArrayOf(12) + le(3_650, 2) + le(100, 4) + le(1_000, 4))
        assertEquals(BatteryInfo(3_650, 100, 1_000), (PacketParser.parse(frame) as MeshEvent.Battery).info)
    }

    @Test fun snapshotFromStatsMapsEachFieldFromTheRightPayloadField() {
        val snapshot = HealthSnapshot.fromStats(
            CoreStats(3_987u, 86_400u, 0u, 0u), RadioStats((-112).toShort(), (-95).toByte(), 5.5, 42u, 1_234u),
            PacketStats(5_000u, 300u, 100u, 200u, 4_000u, 1_000u, 7u),
        )
        assertEquals(
            HealthSnapshot(3_987, 86_400, -112, 42, 1_234, 5_000, 300, 7), snapshot,
        )
    }
}

private class FakeStatsReader : DeviceStatsReader {
    var core: () -> CoreStats = { CoreStats(3_987u, 100u, 0u, 0u) }
    var radio: () -> RadioStats = { RadioStats((-110).toShort(), -90, 4.0, 10u, 20u) }
    var packets: () -> PacketStats = { PacketStats(50u, 30u, 1u, 2u, 3u, 4u, 5u) }
    var battery: () -> BatteryInfo = { BatteryInfo(3_700) }
    val calls = mutableListOf<String>()
    override suspend fun getStatsCore(): CoreStats { calls += "core"; return core() }
    override suspend fun getStatsRadio(): RadioStats { calls += "radio"; return radio() }
    override suspend fun getStatsPackets(): PacketStats { calls += "packets"; return packets() }
    override suspend fun getBattery(): BatteryInfo { calls += "battery"; return battery() }
}

class ProtocolHealthSourceTest {
    private val reader = FakeStatsReader()
    private val source = ProtocolHealthSource(reader)
    private fun read() = runBlocking { source.read() }

    @Test fun firmwareV8ReadsAllThreeStatsAndNeverTheBatteryFallback() {
        val reading = read()
        assertEquals(HealthSnapshot(3_987, 100, -110, 10, 20, 50, 30, 5), reading.snapshot)
        assertTrue(reading.failures.isEmpty())
        assertEquals(listOf("core", "radio", "packets"), reader.calls)
    }

    @Test fun olderFirmwareRejectingGetStatsFallsBackToBatteryMillivoltsOnly() {
        reader.core = { throw MeshCoreException.DeviceError(2u) }
        val reading = read()
        assertEquals(HealthSnapshot(batteryMillivolts = 3_700), reading.snapshot)
        assertEquals(listOf("core", "battery"), reader.calls)
    }

    @Test fun onceStatsAreKnownUnsupportedLaterReadsGoStraightToBattery() {
        reader.core = { throw MeshCoreException.DeviceError(2u) }
        read(); reader.calls.clear(); read()
        assertEquals(listOf("battery"), reader.calls)
    }

    @Test fun aTimeoutIsNotTakenAsUnsupportedSoStatsAreTriedAgain() {
        reader.core = { throw MeshCoreException.Timeout() }
        val first = read()
        assertEquals(HealthSnapshot(batteryMillivolts = 3_700), first.snapshot)
        assertTrue(first.failures.isNotEmpty())
        reader.core = { CoreStats(3_900u, 5u, 0u, 0u) }; reader.calls.clear()
        assertEquals(3_900, read().snapshot.batteryMillivolts); assertEquals("core", reader.calls.first())
    }

    @Test fun oneFailingStatsTypeKeepsTheOthersAndReportsTheFailure() {
        reader.radio = { throw MeshCoreException.Timeout() }
        val reading = read()
        assertEquals(3_987, reading.snapshot.batteryMillivolts); assertNull(reading.snapshot.noiseFloorDbm)
        assertEquals(50L, reading.snapshot.rxPackets)
        assertEquals(1, reading.failures.size); assertTrue(reading.failures.single().startsWith("radio"))
    }

    @Test fun everythingFailingGivesAnEmptySnapshotWithFailuresNotAnException() {
        reader.core = { throw MeshCoreException.Timeout() }; reader.battery = { throw MeshCoreException.NotConnected() }
        val reading = read()
        assertEquals(HealthSnapshot(), reading.snapshot); assertEquals(2, reading.failures.size)
    }

    @Test fun zeroMillivoltsMeansNoBatteryReadingAndIsOmitted() {
        reader.core = { throw MeshCoreException.DeviceError(2u) }; reader.battery = { BatteryInfo(0) }
        assertNull(read().snapshot.batteryMillivolts)
    }

    @Test fun cancellationIsNeverSwallowed() {
        reader.core = { throw CancellationException("stop") }
        assertFailsWith<CancellationException> { read() }
    }

    @Test fun theBatteryFallbackIsMillivoltsOfTheBatteryInfoLevel() {
        reader.core = { throw MeshCoreException.DeviceError(2u) }; reader.battery = { BatteryInfo(4_150, 1, 2) }
        assertEquals(4_150, read().snapshot.batteryMillivolts)
        assertIs<HealthSnapshot>(read().snapshot)
    }
}
