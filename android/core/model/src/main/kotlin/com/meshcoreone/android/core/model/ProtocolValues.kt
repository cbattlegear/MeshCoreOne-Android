// PortedFrom: MC1Services/Sources/MC1Services/Models/ProtocolTypes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/AutoAddMode.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/NotificationLevel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/DebugLogLevel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Models/DecryptStatus.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/AdvertLocationPolicy.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/TelemetryModes.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.ContactType

enum class TextType(val rawValue: UByte) {
    PLAIN(0u), CLI_DATA(1u), SIGNED_PLAIN(2u);
    companion object {
        fun fromRawValue(value: UByte): TextType? = entries.firstOrNull { it.rawValue == value }
    }
}

enum class RemoteNodeRole(val rawValue: UByte) {
    REPEATER(2u), ROOM_SERVER(3u), SENSOR(4u);
    companion object {
        fun fromRawValue(value: UByte): RemoteNodeRole? = entries.firstOrNull { it.rawValue == value }
        fun fromContactType(type: ContactType): RemoteNodeRole? = when (type) {
            ContactType.CHAT, ContactType.UNKNOWN -> null
            ContactType.REPEATER -> REPEATER
            ContactType.ROOM -> ROOM_SERVER
            ContactType.SENSOR -> SENSOR
        }
    }
}

enum class RoomPermissionLevel(val rawValue: UByte, val displayName: String) {
    GUEST(0u, "Guest"), READ_WRITE(1u, "Member"), ADMIN(2u, "Admin");
    val canPost: Boolean get() = rawValue >= READ_WRITE.rawValue
    val isAdmin: Boolean get() = this == ADMIN
    companion object {
        fun fromRawValue(value: UByte): RoomPermissionLevel? = entries.firstOrNull { it.rawValue == value }
    }
}

enum class AutoAddMode(val rawValue: String) {
    MANUAL("manual"), SELECTED_TYPES("selectedTypes"), ALL("all");
    companion object {
        fun mode(manualAddContacts: Boolean, autoAddConfig: UByte): AutoAddMode {
            val mask = AutoAddConfig.CONTACTS_BIT.toInt() or AutoAddConfig.REPEATERS_BIT.toInt() or
                AutoAddConfig.ROOM_SERVERS_BIT.toInt() or AutoAddConfig.SENSORS_BIT.toInt()
            return when {
                !manualAddContacts -> ALL
                autoAddConfig.toInt() and mask == 0 -> MANUAL
                else -> SELECTED_TYPES
            }
        }
    }
}

enum class NotificationLevel(val rawValue: Long, val displayName: String, val accessibilityDescription: String) {
    MUTED(0, "Muted", "Muted, no notifications"),
    MENTIONS_ONLY(1, "Mentions", "Mentions only"),
    ALL(2, "All", "All notifications");
    companion object {
        val channelLevels = entries.toList().snapshot()
        val roomLevels = SnapshotList.of(MUTED, ALL)
        fun fromRawValue(value: Long): NotificationLevel? = entries.firstOrNull { it.rawValue == value }
    }
}

enum class DebugLogLevel(val rawValue: Long, val label: String) {
    DEBUG(0, "DEBUG"), INFO(1, "INFO"), NOTICE(2, "NOTICE"), WARNING(3, "WARNING"),
    ERROR(4, "ERROR"), FAULT(5, "FAULT");
    companion object {
        fun fromRawValue(value: Long): DebugLogLevel? = entries.firstOrNull { it.rawValue == value }
    }
}

enum class DecryptStatus(val rawValue: Long, val displayName: String) {
    NOT_APPLICABLE(0, "N/A"), NO_MATCHING_KEY(1, "No Key"), HMAC_FAILED(2, "HMAC Failed"),
    DECRYPT_FAILED(3, "Decrypt Failed"), SUCCESS(4, "Decrypted"), PENDING(5, "Has Key"),
    DM_NO_MATCHING_KEY(6, "No DM Key");
    companion object {
        fun fromRawValue(value: Long): DecryptStatus? = entries.firstOrNull { it.rawValue == value }
    }
}

enum class AdvertLocationPolicy(val rawValue: UByte) {
    NONE(0u), SHARE(1u), PREFS(2u);
    val isEnabled: Boolean get() = this != NONE
    companion object {
        fun fromRawValue(value: UByte): AdvertLocationPolicy? = entries.firstOrNull { it.rawValue == value }
    }
}

data class TelemetryModes private constructor(val base: UByte, val location: UByte, val environment: UByte) {
    val packed: UByte get() = (environment.toInt() shl 4 or (location.toInt() shl 2) or base.toInt()).toUByte()
    companion object {
        fun of(base: UByte = 0u, location: UByte = 0u, environment: UByte = 0u): TelemetryModes =
            TelemetryModes((base.toInt() and 3).toUByte(), (location.toInt() and 3).toUByte(), (environment.toInt() and 3).toUByte())
        fun fromPacked(packed: UByte): TelemetryModes =
            of(packed, (packed.toInt() ushr 2).toUByte(), (packed.toInt() ushr 4).toUByte())
    }
}

class InvalidRawEnumException(val type: String, val rawValue: Long) :
    IllegalArgumentException("Unknown $type raw value: $rawValue")
