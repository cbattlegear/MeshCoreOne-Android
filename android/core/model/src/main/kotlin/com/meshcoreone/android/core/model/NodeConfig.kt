// PortedFrom: MC1Services/Sources/MC1Services/Models/NodeConfig.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

data class MeshCoreNodeConfig(
    val name: String? = null,
    val publicKey: String? = null,
    val privateKey: String? = null,
    val radioSettings: RadioSettings? = null,
    val positionSettings: PositionSettings? = null,
    val otherSettings: OtherSettings? = null,
    val channels: SnapshotList<ChannelConfig>? = null,
    val contacts: SnapshotList<ContactConfig>? = null,
) {
    data class RadioSettings(
        val frequency: UInt, val bandwidth: UInt, val spreadingFactor: UByte, val codingRate: UByte, val txPower: Byte,
    )
    data class PositionSettings(val latitude: String, val longitude: String) {
        val isZero: Boolean get() = (latitude.toDoubleOrNull() ?: 0.0) == 0.0 && (longitude.toDoubleOrNull() ?: 0.0) == 0.0
    }
    data class OtherSettings(
        val manualAddContacts: UByte? = null,
        val advertLocationPolicy: UByte? = null,
        val telemetryModeBase: UByte? = null,
        val telemetryModeLocation: UByte? = null,
        val telemetryModeEnvironment: UByte? = null,
        val multiAcks: UByte? = null,
        val advertisementType: UByte? = null,
    )
    data class ChannelConfig(val name: String, val secret: String)
    data class ContactConfig(
        val type: UByte,
        val name: String,
        val publicKey: String,
        val flags: UByte,
        val latitude: String,
        val longitude: String,
        val lastAdvert: UInt,
        val lastModified: UInt,
        val customName: String? = null,
        val outPath: String? = null,
        val pathHashMode: UByte? = null,
    )
}

data class ConfigSections(
    val nodeIdentity: Boolean = false,
    val radioSettings: Boolean = false,
    val positionSettings: Boolean = false,
    val otherSettings: Boolean = false,
    val channels: Boolean = false,
    val contacts: Boolean = false,
) {
    val allSelected: Boolean get() = nodeIdentity && radioSettings && positionSettings && otherSettings && channels && contacts
    val anySectionSelected: Boolean get() = nodeIdentity || radioSettings || positionSettings || otherSettings || channels || contacts
    fun selectAll(): ConfigSections = ConfigSections(true, true, true, true, true, true)
    fun deselectAll(): ConfigSections = ConfigSections()
}

object NodeConfigWireContract {
    val snakeCaseKeys = mapOf(
        "publicKey" to "public_key", "privateKey" to "private_key",
        "radioSettings" to "radio_settings", "positionSettings" to "position_settings", "otherSettings" to "other_settings",
        "spreadingFactor" to "spreading_factor", "codingRate" to "coding_rate", "txPower" to "tx_power",
        "manualAddContacts" to "manual_add_contacts", "advertLocationPolicy" to "advert_location_policy",
        "telemetryModeBase" to "telemetry_mode_base", "telemetryModeLocation" to "telemetry_mode_location",
        "telemetryModeEnvironment" to "telemetry_mode_environment", "multiAcks" to "multi_acks",
        "advertisementType" to "advertisement_type", "customName" to "custom_name",
        "lastAdvert" to "last_advert", "lastModified" to "last_modified", "outPath" to "out_path",
        "pathHashMode" to "path_hash_mode",
    ).snapshotMap()
    val explicitNullContactKeys = SnapshotSet(listOf("custom_name", "out_path"))
}
