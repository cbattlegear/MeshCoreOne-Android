// PortedFrom: MeshCore/Tests/MeshCoreTests/Fixtures/PythonReferenceBytes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Pinned cayennelpp bytes copied verbatim; no candidate encoder generates these expectations.
package com.meshcoreone.android.core.protocol.lpp

import com.meshcoreone.android.core.protocol.bytes.Bytes

internal object PythonLPPReferenceBytes {
    val temperature25_5 = Bytes.of(0x01, 0x67, 0x00, 0xff)
    val humidity65 = Bytes.of(0x02, 0x68, 0x82)
    val analog3_3 = Bytes.of(0x03, 0x02, 0x01, 0x4a)
    val gpsSF = Bytes.of(0x04, 0x88, 0x05, 0xc3, 0x95, 0xed, 0x51, 0xfe, 0x00, 0x03, 0xe8)
    val barometer1013 = Bytes.of(0x05, 0x73, 0x27, 0x94)
    val accelerometer1g = Bytes.of(0x06, 0x71, 0x00, 0x00, 0x00, 0x00, 0x03, 0xe8)

    val bySourceName = mapOf(
        "lpp_temperature_25_5" to temperature25_5,
        "lpp_humidity_65" to humidity65,
        "lpp_analog_3_3" to analog3_3,
        "lpp_gps_sf" to gpsSF,
        "lpp_barometer_1013" to barometer1013,
        "lpp_accelerometer_1g" to accelerometer1g,
    )
}
