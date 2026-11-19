// PortedFrom: MC1Services/Sources/MC1Services/Models/Device.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Connection/ConnectionManager.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.DeviceCapabilities
import com.meshcoreone.android.core.protocol.model.SelfInfo
import java.time.Instant
import java.util.UUID

object DeviceDefaults {
    val frequency: UInt = 915_000u
    val bandwidth: UInt = 250_000u
    val spreadingFactor: UByte = 10u
    val codingRate: UByte = 5u
    const val TX_POWER: Byte = 20
    const val MAX_TX_POWER: Byte = 20
    const val LATITUDE = 0.0
    const val LONGITUDE = 0.0
    val blePin: UInt = 0u
    const val CLIENT_REPEAT = false
    val pathHashMode: UByte = 0u
    const val MANUAL_ADD_CONTACTS = false
    val autoAddConfig: UByte = 0u
    val autoAddMaxHops: UByte = 0u
    val multiAcks: UByte = 2u
    val telemetryModeBase: UByte = 2u
    val telemetryModeLoc: UByte = 0u
    val telemetryModeEnv: UByte = 0u
    val advertLocationPolicy: UByte = 0u
}

data class DeviceDTO(
    val id: UUID = UUID.randomUUID(),
    val radioId: RadioId,
    val publicKey: Bytes,
    val nodeName: String,
    val firmwareVersion: UByte = 0u,
    val firmwareVersionString: String = "",
    val manufacturerName: String = "",
    val buildDate: String = "",
    val maxContacts: UShort = 100u,
    val maxChannels: UByte = 8u,
    val frequency: UInt = DeviceDefaults.frequency,
    val bandwidth: UInt = DeviceDefaults.bandwidth,
    val spreadingFactor: UByte = DeviceDefaults.spreadingFactor,
    val codingRate: UByte = DeviceDefaults.codingRate,
    val txPower: Byte = DeviceDefaults.TX_POWER,
    val maxTxPower: Byte = DeviceDefaults.MAX_TX_POWER,
    val latitude: Double = DeviceDefaults.LATITUDE,
    val longitude: Double = DeviceDefaults.LONGITUDE,
    val blePin: UInt = DeviceDefaults.blePin,
    val clientRepeat: Boolean = DeviceDefaults.CLIENT_REPEAT,
    val pathHashMode: UByte = DeviceDefaults.pathHashMode,
    val defaultFloodScopeName: String? = null,
    val preRepeatFrequency: UInt? = null,
    val preRepeatBandwidth: UInt? = null,
    val preRepeatSpreadingFactor: UByte? = null,
    val preRepeatCodingRate: UByte? = null,
    val manualAddContacts: Boolean = DeviceDefaults.MANUAL_ADD_CONTACTS,
    val autoAddConfig: UByte = DeviceDefaults.autoAddConfig,
    val autoAddMaxHops: UByte = DeviceDefaults.autoAddMaxHops,
    val multiAcks: UByte = DeviceDefaults.multiAcks,
    val telemetryModeBase: UByte = DeviceDefaults.telemetryModeBase,
    val telemetryModeLoc: UByte = DeviceDefaults.telemetryModeLoc,
    val telemetryModeEnv: UByte = DeviceDefaults.telemetryModeEnv,
    val advertLocationPolicy: UByte = DeviceDefaults.advertLocationPolicy,
    val lastConnected: Instant = Instant.now(),
    val lastContactSync: UInt = 0u,
    val isActive: Boolean = false,
    val ocvPreset: String? = null,
    val appliedRadioPresetID: String? = null,
    val customOCVArrayString: String? = null,
    val connectionMethods: SnapshotList<ConnectionMethod> = SnapshotList.empty(),
    val knownRegions: SnapshotList<String> = SnapshotList.empty(),
) {
    private val fields get() = arrayOf(
        id, radioId, publicKey, nodeName, firmwareVersion, firmwareVersionString, manufacturerName, buildDate,
        maxContacts, maxChannels, frequency, bandwidth, spreadingFactor, codingRate, txPower, maxTxPower,
        latitude, longitude, blePin, clientRepeat, pathHashMode, defaultFloodScopeName, preRepeatFrequency,
        preRepeatBandwidth, preRepeatSpreadingFactor, preRepeatCodingRate, manualAddContacts, autoAddConfig,
        autoAddMaxHops, multiAcks, telemetryModeBase, telemetryModeLoc, telemetryModeEnv, advertLocationPolicy,
        lastConnected, lastContactSync, isActive, ocvPreset, appliedRadioPresetID, customOCVArrayString,
        connectionMethods, knownRegions,
    )
    override fun equals(other: Any?): Boolean = other is DeviceDTO && sourceFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = sourceFieldsHash(fields)

    val hashSize: Long get() = pathHashMode.toLong() + 1
    val traceHashSize: Long get() = if (pathHashMode.toInt() >= 64) 0 else 1L shl pathHashMode.toInt()
    val hasLocation: Boolean get() = Coordinate(latitude, longitude).isValidFix
    val publicKeyPrefix: Bytes get() = publicKey.prefix(6)
    val autoAddMode: AutoAddMode get() = AutoAddMode.mode(manualAddContacts, autoAddConfig)
    val autoAddContacts: Boolean get() = autoAddConfig.has(AutoAddConfig.CONTACTS_BIT)
    val autoAddRepeaters: Boolean get() = autoAddConfig.has(AutoAddConfig.REPEATERS_BIT)
    val autoAddRoomServers: Boolean get() = autoAddConfig.has(AutoAddConfig.ROOM_SERVERS_BIT)
    val overwriteOldest: Boolean get() = autoAddConfig.has(AutoAddConfig.OVERWRITE_OLDEST_BIT)
    val supportsAutoAddConfig: Boolean get() = firmwareVersionString.isAtLeast(1, 12)
    val supportsAutoAddMaxHops: Boolean get() = firmwareVersionString.isAtLeast(1, 14)
    val supportsTraceHashSizeOverride: Boolean get() = firmwareVersion >= 9u || firmwareVersionString.isAtLeast(1, 11)
    val supportsClientRepeat: Boolean get() = firmwareVersion >= 9u
    val supportsPathHashMode: Boolean get() = firmwareVersion >= 10u
    val supportsDefaultFloodScope: Boolean get() = firmwareVersion >= 11u
    val supportsUnscopedFloodSend: Boolean get() = firmwareVersion >= 12u
    val supportsAdHocRepeaterRequest: Boolean get() = firmwareVersion >= 13u || firmwareVersionString.isAtLeast(1, 16)
    val advertLocationPolicyMode: AdvertLocationPolicy
        get() = AdvertLocationPolicy.fromRawValue(advertLocationPolicy) ?: AdvertLocationPolicy.NONE
    val telemetryModes: TelemetryModes get() = TelemetryModes.of(telemetryModeBase, telemetryModeLoc, telemetryModeEnv)
    val sharesLocationPublicly: Boolean get() = advertLocationPolicy > 0u
    val hasPreRepeatSettings: Boolean get() = preRepeatFrequency != null && preRepeatBandwidth != null &&
        preRepeatSpreadingFactor != null && preRepeatCodingRate != null
    val activeOCVArray: SnapshotList<Long> get() = activeOCVArray(ocvPreset, customOCVArrayString)

    fun updating(info: SelfInfo): DeviceDTO = copy(
        publicKey = info.publicKey, nodeName = info.name,
        frequency = checkedUInt(info.radioFrequency * 1000), bandwidth = checkedUInt(info.radioBandwidth * 1000),
        spreadingFactor = info.radioSpreadingFactor, codingRate = info.radioCodingRate, txPower = info.txPower,
        latitude = info.latitude, longitude = info.longitude, manualAddContacts = info.manualAddContacts,
        multiAcks = info.multiAcks, telemetryModeBase = info.telemetryModeBase,
        telemetryModeLoc = info.telemetryModeLocation, telemetryModeEnv = info.telemetryModeEnvironment,
        advertLocationPolicy = info.advertisementLocationPolicy,
    )

    fun savingPreRepeatSettings(): DeviceDTO = copy(
        preRepeatFrequency = frequency, preRepeatBandwidth = bandwidth,
        preRepeatSpreadingFactor = spreadingFactor, preRepeatCodingRate = codingRate,
    )

    fun clearingPreRepeatSettings(): DeviceDTO = copy(
        preRepeatFrequency = null, preRepeatBandwidth = null, preRepeatSpreadingFactor = null, preRepeatCodingRate = null,
    )

    fun cleanedForImport(): DeviceDTO = copy(
        isActive = false, connectionMethods = connectionMethods.filterNot { it.isBluetooth }.snapshot(),
    )

    fun redactedForBackup(newSurrogateId: UUID = UUID.randomUUID()): DeviceDTO = copy(
        id = newSurrogateId, frequency = DeviceDefaults.frequency, bandwidth = DeviceDefaults.bandwidth,
        spreadingFactor = DeviceDefaults.spreadingFactor, codingRate = DeviceDefaults.codingRate,
        appliedRadioPresetID = null, txPower = DeviceDefaults.TX_POWER, maxTxPower = DeviceDefaults.MAX_TX_POWER,
        latitude = DeviceDefaults.LATITUDE, longitude = DeviceDefaults.LONGITUDE, blePin = DeviceDefaults.blePin,
        clientRepeat = DeviceDefaults.CLIENT_REPEAT, pathHashMode = DeviceDefaults.pathHashMode,
        preRepeatFrequency = null, preRepeatBandwidth = null, preRepeatSpreadingFactor = null, preRepeatCodingRate = null,
        manualAddContacts = DeviceDefaults.MANUAL_ADD_CONTACTS, autoAddConfig = DeviceDefaults.autoAddConfig,
        autoAddMaxHops = DeviceDefaults.autoAddMaxHops, multiAcks = DeviceDefaults.multiAcks,
        telemetryModeBase = DeviceDefaults.telemetryModeBase, telemetryModeLoc = DeviceDefaults.telemetryModeLoc,
        telemetryModeEnv = DeviceDefaults.telemetryModeEnv, advertLocationPolicy = DeviceDefaults.advertLocationPolicy,
        connectionMethods = connectionMethods.filterNot { it.isBluetooth }.snapshot(),
    )

    companion object {
        fun fromConnection(
            deviceId: UUID, radioId: RadioId, info: SelfInfo, capabilities: DeviceCapabilities,
            autoAdd: AutoAddConfig, existingDevice: DeviceDTO? = null,
            connectionMethods: Iterable<ConnectionMethod> = emptyList(), now: Instant = Instant.now(),
        ): DeviceDTO {
            require(capabilities.maxContacts in 0..65535) { "Contact capacity does not fit UInt16" }
            require(capabilities.maxChannels >= 0) { "Channel capacity must not be negative" }
            val merged = existingDevice?.connectionMethods?.toMutableList() ?: mutableListOf()
            for (method in connectionMethods) {
                merged.removeAll { it.isWiFi == method.isWiFi }
                merged += method
            }
            val result = DeviceDTO(
                id = deviceId, radioId = radioId, publicKey = info.publicKey, nodeName = info.name,
                firmwareVersion = capabilities.firmwareVersion, firmwareVersionString = capabilities.version,
                manufacturerName = capabilities.model, buildDate = capabilities.firmwareBuild,
                maxContacts = capabilities.maxContacts.toUShort(), maxChannels = minOf(capabilities.maxChannels, 255).toUByte(),
                frequency = checkedUInt(info.radioFrequency * 1000), bandwidth = checkedUInt(info.radioBandwidth * 1000),
                spreadingFactor = info.radioSpreadingFactor, codingRate = info.radioCodingRate,
                txPower = info.txPower, maxTxPower = info.maxTxPower, latitude = info.latitude, longitude = info.longitude,
                blePin = capabilities.blePin, clientRepeat = capabilities.clientRepeat, pathHashMode = capabilities.pathHashMode,
                defaultFloodScopeName = existingDevice?.defaultFloodScopeName,
                preRepeatFrequency = existingDevice?.preRepeatFrequency, preRepeatBandwidth = existingDevice?.preRepeatBandwidth,
                preRepeatSpreadingFactor = existingDevice?.preRepeatSpreadingFactor, preRepeatCodingRate = existingDevice?.preRepeatCodingRate,
                manualAddContacts = info.manualAddContacts, autoAddConfig = autoAdd.bitmask, autoAddMaxHops = autoAdd.maxHops,
                multiAcks = info.multiAcks, telemetryModeBase = info.telemetryModeBase, telemetryModeLoc = info.telemetryModeLocation,
                telemetryModeEnv = info.telemetryModeEnvironment, advertLocationPolicy = info.advertisementLocationPolicy,
                lastConnected = now, lastContactSync = existingDevice?.lastContactSync ?: 0u, isActive = true,
                ocvPreset = existingDevice?.ocvPreset ?: OCVPreset.presetForManufacturer(capabilities.model)?.rawValue,
                appliedRadioPresetID = existingDevice?.appliedRadioPresetID, customOCVArrayString = existingDevice?.customOCVArrayString,
                connectionMethods = merged.snapshot(), knownRegions = existingDevice?.knownRegions ?: SnapshotList.empty(),
            )
            return if (!capabilities.clientRepeat && existingDevice?.preRepeatFrequency != null) {
                result.clearingPreRepeatSettings()
            } else result
        }
    }
}

private fun UByte.has(bit: UByte): Boolean = toInt() and bit.toInt() != 0

internal fun checkedUInt(value: Double): UInt {
    require(value.isFinite() && value >= 0 && value < 4_294_967_296.0) { "Value does not fit UInt32: $value" }
    return value.toLong().toUInt()
}

fun String.isAtLeast(major: Long, minor: Long): Boolean {
    for (token in split(Regex("[^\\p{N}.]+"))) {
        val parts = token.split('.').filter { it.isNotEmpty() }
        if (parts.size < 2) continue
        val actualMajor = parts[0].asciiLongOrNull() ?: continue
        val actualMinor = parts[1].asciiLongOrNull() ?: continue
        return actualMajor > major || (actualMajor == major && actualMinor >= minor)
    }
    return false
}

private fun String.asciiLongOrNull(): Long? =
    if (all { it in '0'..'9' }) toLongOrNull() else null
