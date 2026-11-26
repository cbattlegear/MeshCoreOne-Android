// PortedFrom: MeshCore/Sources/MeshCore/LPP/LPPDecoder.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.lpp

import java.time.Instant

sealed interface LPPValue {
    data class Digital(val value: Boolean) : LPPValue
    data class Integer(val value: Long) : LPPValue

    // Swift Double equality treats signed zeros equally, unlike JVM data-class equality.
    data class Float(val value: Double) : LPPValue {
        override fun equals(other: Any?): Boolean = other is Float && value == other.value
        override fun hashCode(): Int = value.swiftHashCode()
    }

    data class Vector3(val x: Double, val y: Double, val z: Double) : LPPValue {
        override fun equals(other: Any?): Boolean =
            other is Vector3 && x == other.x && y == other.y && z == other.z

        override fun hashCode(): Int = (31 * x.swiftHashCode() + y.swiftHashCode()) * 31 + z.swiftHashCode()
    }

    data class Gps(val latitude: Double, val longitude: Double, val altitude: Double) : LPPValue {
        override fun equals(other: Any?): Boolean =
            other is Gps && latitude == other.latitude && longitude == other.longitude && altitude == other.altitude

        override fun hashCode(): Int =
            (31 * latitude.swiftHashCode() + longitude.swiftHashCode()) * 31 + altitude.swiftHashCode()
    }

    data class Rgb(val red: UByte, val green: UByte, val blue: UByte) : LPPValue
    data class Timestamp(val value: Instant) : LPPValue
}

data class LPPDataPoint(val channel: UByte, val type: LPPSensorType, val value: LPPValue)

private fun Double.swiftHashCode(): Int = if (this == 0.0) 0.0.hashCode() else hashCode()
