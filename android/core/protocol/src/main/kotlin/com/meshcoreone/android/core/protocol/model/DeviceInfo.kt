// PortedFrom: MeshCore/Sources/MeshCore/Models/DeviceInfo.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.protocol.model

import com.meshcoreone.android.core.protocol.bytes.Bytes

data class SelfInfo(
    val advertisementType: UByte,
    val txPower: Byte,
    val maxTxPower: Byte,
    val publicKey: Bytes,
    val latitude: Double,
    val longitude: Double,
    val multiAcks: UByte,
    val advertisementLocationPolicy: UByte,
    val telemetryModeEnvironment: UByte,
    val telemetryModeLocation: UByte,
    val telemetryModeBase: UByte,
    val manualAddContacts: Boolean,
    val radioFrequency: Double,
    val radioBandwidth: Double,
    val radioSpreadingFactor: UByte,
    val radioCodingRate: UByte,
    val name: String,
)

data class DeviceCapabilities(
    val firmwareVersion: UByte,
    val maxContacts: Long,
    val maxChannels: Long,
    val blePin: UInt,
    val firmwareBuild: String,
    val model: String,
    val version: String,
    val clientRepeat: Boolean = false,
    val pathHashMode: UByte = 0u,
) {
    val hashSize: Int get() = pathHashMode.toInt() + 1
    val supportsPathHashMode: Boolean get() = firmwareVersion >= 10u
}

data class BatteryInfo(
    val level: Long,
    val usedStorageKB: Long? = null,
    val totalStorageKB: Long? = null,
)
