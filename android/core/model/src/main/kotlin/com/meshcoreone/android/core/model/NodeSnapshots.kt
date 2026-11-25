// PortedFrom: MC1Services/Sources/MC1Services/Models/NodeLocationFix.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Extensions/CLLocationCoordinate2D+ValidFix.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/NodeStatusSnapshot.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Extensions/StatusResponse+Compatibility.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.lpp.LPPDataPoint
import com.meshcoreone.android.core.protocol.lpp.LPPValue
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class Coordinate(val latitude: Double, val longitude: Double) {
    override fun equals(other: Any?): Boolean = other is Coordinate && latitude == other.latitude && longitude == other.longitude
    override fun hashCode(): Int = sourceFieldsHash(arrayOf(latitude, longitude))
    val isValid: Boolean get() = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0
    val isValidFix: Boolean get() = isValid && !(latitude == 0.0 && longitude == 0.0)
}

data class NodeLocationFix(val latitude: Double, val longitude: Double, val altitude: Double? = null) {
    private val fields get() = arrayOf(latitude, longitude, altitude)
    override fun equals(other: Any?): Boolean = other is NodeLocationFix && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)
    companion object {
        fun primaryFix(points: Iterable<LPPDataPoint>): NodeLocationFix? {
            for (point in points) {
                val gps = point.value as? LPPValue.Gps ?: continue
                if (!Coordinate(gps.latitude, gps.longitude).isValidFix) return null
                return NodeLocationFix(
                    gps.latitude, gps.longitude, gps.altitude.takeIf { it in -500.0..10000.0 },
                )
            }
            return null
        }
    }
}

object NodeSnapshotPolicy { val minimumInterval: Duration = Duration.ofMinutes(15) }

data class NodeStatusMetrics(
    val batteryMillivolts: UShort? = null,
    val lastSNR: Double? = null,
    val lastRSSI: Short? = null,
    val noiseFloor: Short? = null,
    val uptimeSeconds: UInt? = null,
    val rxAirtimeSeconds: UInt? = null,
    val packetsSent: UInt? = null,
    val packetsReceived: UInt? = null,
    val receiveErrors: UInt? = null,
    val sentDirect: UInt? = null,
    val sentFlood: UInt? = null,
    val receivedDirect: UInt? = null,
    val receivedFlood: UInt? = null,
    val directDuplicates: UInt? = null,
    val floodDuplicates: UInt? = null,
    val postedCount: UShort? = null,
    val postPushCount: UShort? = null,
) {
    private val fields get() = arrayOf(
        batteryMillivolts, lastSNR, lastRSSI, noiseFloor, uptimeSeconds, rxAirtimeSeconds, packetsSent,
        packetsReceived, receiveErrors, sentDirect, sentFlood, receivedDirect, receivedFlood,
        directDuplicates, floodDuplicates, postedCount, postPushCount,
    )
    override fun equals(other: Any?): Boolean = other is NodeStatusMetrics && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)

    companion object {
        fun fromStatus(
            status: StatusResponse, rxAirtimeSeconds: UInt? = null, receiveErrors: UInt? = null,
            postedCount: UShort? = null, postPushCount: UShort? = null,
        ): NodeStatusMetrics = NodeStatusMetrics(
            batteryMillivolts = status.battery.coerceIn(0L, 65535L).toUShort(),
            lastSNR = status.lastSNR, lastRSSI = status.lastRSSI.coerceIn(-32768L, 32767L).toShort(),
            noiseFloor = status.noiseFloor.coerceIn(-32768L, 32767L).toShort(),
            uptimeSeconds = status.uptime, rxAirtimeSeconds = rxAirtimeSeconds,
            packetsSent = status.packetsSent, packetsReceived = status.packetsReceived, receiveErrors = receiveErrors,
            sentDirect = status.sentDirect, sentFlood = status.sentFlood, receivedDirect = status.receivedDirect,
            receivedFlood = status.receivedFlood, directDuplicates = status.directDuplicates.coerceIn(0L, 0xFFFF_FFFFL).toUInt(),
            floodDuplicates = status.floodDuplicates.coerceIn(0L, 0xFFFF_FFFFL).toUInt(),
            postedCount = postedCount, postPushCount = postPushCount,
        )
    }
}

data class NeighborSnapshotEntry(val publicKeyPrefix: Bytes, val snr: Double, val secondsAgo: Long) {
    private val fields get() = arrayOf<Any?>(publicKeyPrefix, snr, secondsAgo)
    override fun equals(other: Any?): Boolean = other is NeighborSnapshotEntry && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)
}
data class TelemetrySnapshotEntry(val channel: Long, val type: String, val value: Double) {
    private val fields get() = arrayOf<Any?>(channel, type, value)
    override fun equals(other: Any?): Boolean = other is TelemetrySnapshotEntry && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)
}

data class NodeStatusSnapshotDTO(
    val id: UUID = UUID.randomUUID(),
    val timestamp: Instant = Instant.now(),
    val nodePublicKey: Bytes,
    val batteryMillivolts: UShort? = null,
    val lastSNR: Double? = null,
    val lastRSSI: Short? = null,
    val noiseFloor: Short? = null,
    val uptimeSeconds: UInt? = null,
    val rxAirtimeSeconds: UInt? = null,
    val packetsSent: UInt? = null,
    val packetsReceived: UInt? = null,
    val receiveErrors: UInt? = null,
    val sentDirect: UInt? = null,
    val sentFlood: UInt? = null,
    val receivedDirect: UInt? = null,
    val receivedFlood: UInt? = null,
    val directDuplicates: UInt? = null,
    val floodDuplicates: UInt? = null,
    val postedCount: UShort? = null,
    val postPushCount: UShort? = null,
    val neighborSnapshots: SnapshotList<NeighborSnapshotEntry>? = null,
    val telemetryEntries: SnapshotList<TelemetrySnapshotEntry>? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val selfRadioID: RadioId? = null,
) {
    private val fields get() = arrayOf(
        id, timestamp, nodePublicKey, batteryMillivolts, lastSNR, lastRSSI, noiseFloor, uptimeSeconds, rxAirtimeSeconds,
        packetsSent, packetsReceived, receiveErrors, sentDirect, sentFlood, receivedDirect, receivedFlood,
        directDuplicates, floodDuplicates, postedCount, postPushCount, neighborSnapshots, telemetryEntries, latitude, longitude, altitude, selfRadioID,
    )
    override fun equals(other: Any?): Boolean = other is NodeStatusSnapshotDTO && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)

    val validCoordinate: Coordinate?
        get() = if (latitude != null && longitude != null) {
            Coordinate(latitude, longitude).takeIf { it.isValidFix }
        } else null

    fun applying(metrics: NodeStatusMetrics): NodeStatusSnapshotDTO = copy(
        batteryMillivolts = metrics.batteryMillivolts, lastSNR = metrics.lastSNR,
        lastRSSI = metrics.lastRSSI, noiseFloor = metrics.noiseFloor, uptimeSeconds = metrics.uptimeSeconds,
        rxAirtimeSeconds = metrics.rxAirtimeSeconds, packetsSent = metrics.packetsSent,
        packetsReceived = metrics.packetsReceived, receiveErrors = metrics.receiveErrors,
        sentDirect = metrics.sentDirect, sentFlood = metrics.sentFlood, receivedDirect = metrics.receivedDirect,
        receivedFlood = metrics.receivedFlood, directDuplicates = metrics.directDuplicates,
        floodDuplicates = metrics.floodDuplicates, postedCount = metrics.postedCount, postPushCount = metrics.postPushCount,
    )
}
