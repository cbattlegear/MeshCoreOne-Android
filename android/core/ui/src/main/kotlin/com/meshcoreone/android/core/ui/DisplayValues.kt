// PortedFrom: MC1/Extensions/BatteryInfo+Display.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/ChannelDTO+DisplayName.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/Collection+Chunked.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/ContactType+Display.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/DecryptStatus+Display.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/DiscoveredNodeDTO+ContactFrame.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/NotificationLevel+Display.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/RoomPermissionLevel+Display.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Extensions/UInt8+Hex.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.ui

import android.util.Log
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DiscoveredNodeDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RoomPermissionLevel
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Clock
import java.util.Locale
import kotlin.math.round

enum class BatteryLevelRole { NORMAL, WARNING, CRITICAL }
enum class ContactPinRole { CONTACT_CHAT, CONTACT_REPEATER, CONTACT_ROOM }

val BatteryInfo.isBatteryPresent: Boolean get() = level > 0
val BatteryInfo.voltage: Double get() = level / 1000.0
val BatteryInfo.percentage: Int get() = (((voltage - 3.0) / 1.2) * 100).coerceIn(0.0, 100.0).toInt()

fun BatteryInfo.percentage(ocvArray: List<Long>): Int {
    if (ocvArray.size != 11) return percentage
    if (level >= ocvArray[0]) return 100
    if (level <= ocvArray[10]) return 0
    for (index in 0..<10) {
        val upper = ocvArray[index]
        val lower = ocvArray[index + 1]
        if (level >= lower) {
            val numerator = Math.subtractExact(level, lower).toDouble()
            val denominator = Math.subtractExact(upper, lower).toDouble()
            require(denominator != 0.0) { "The selected OCV segment has no voltage span" }
            return (9 - index) * 10 + kotlin.math.floor(numerator / denominator * 10 + 0.5).toInt()
        }
    }
    return 0
}

fun batterySourceIconName(percentage: Int): String = when (percentage) {
    in 88..100 -> "battery.100"
    in 63..<88 -> "battery.75"
    in 38..<63 -> "battery.50"
    in 13..<38 -> "battery.25"
    else -> "battery.0"
}

fun batteryLevelRole(percentage: Int): BatteryLevelRole = when (percentage) {
    in 20..100 -> BatteryLevelRole.NORMAL
    in 10..<20 -> BatteryLevelRole.WARNING
    else -> BatteryLevelRole.CRITICAL
}

val BatteryInfo.sourceIconName: String get() = batterySourceIconName(percentage)
val BatteryInfo.levelRole: BatteryLevelRole get() = batteryLevelRole(percentage)

fun ChannelDTO.displayName(): UiText = if (name.isEmpty()) {
    generatedText("Chats.Channel.defaultName") { AppChatsStrings.chatsChannelDefaultName(it, index.toInt()) }
} else UiText.Verbatim(name)

fun <T> Collection<T>.sourceChunked(size: Long): SnapshotList<SnapshotList<T>> {
    if (size <= 0) {
        Log.w("MeshCoreOne.UI", "Invalid nonpositive display chunk size")
        return SnapshotList.empty()
    }
    val values = toList()
    val result = mutableListOf<SnapshotList<T>>()
    var start = 0
    while (start < values.size) {
        val length = minOf(size, (values.size - start).toLong()).toInt()
        result += values.subList(start, start + length).snapshot()
        start += length
    }
    return result.snapshot()
}

val ContactType.labelResource: Int get() = when (this) {
    ContactType.CHAT -> AppContactsStrings.contactsNodeKindContact
    ContactType.REPEATER -> AppContactsStrings.contactsNodeKindRepeater
    ContactType.ROOM -> AppContactsStrings.contactsNodeKindRoom
    ContactType.SENSOR -> com.meshcoreone.android.core.l10n.R.string.l10n_app_remotenodes_remotenodes_status_sensor_genericsensor
    ContactType.UNKNOWN -> com.meshcoreone.android.core.l10n.R.string.l10n_app_remotenodes_remotenodes_status_unknown
}

val ContactType.symbol: MeshSymbol get() = when (this) {
    ContactType.CHAT -> MeshSymbol.MESSAGES
    ContactType.REPEATER -> MeshSymbol.RADIO
    ContactType.ROOM -> MeshSymbol.ROOM
    ContactType.SENSOR, ContactType.UNKNOWN -> MeshSymbol.RADIO
}

val ContactType.pinRole: ContactPinRole get() = when (this) {
    ContactType.CHAT -> ContactPinRole.CONTACT_CHAT
    ContactType.REPEATER -> ContactPinRole.CONTACT_REPEATER
    ContactType.ROOM -> ContactPinRole.CONTACT_ROOM
    ContactType.SENSOR, ContactType.UNKNOWN -> ContactPinRole.CONTACT_REPEATER
}

val DecryptStatus.labelResource: Int get() = when (this) {
    DecryptStatus.NOT_APPLICABLE -> AppToolsStrings.toolsRxLogDecryptStatusNotApplicable
    DecryptStatus.NO_MATCHING_KEY -> AppToolsStrings.toolsRxLogDecryptStatusNoKey
    DecryptStatus.HMAC_FAILED -> AppToolsStrings.toolsRxLogDecryptStatusHmacFailed
    DecryptStatus.DECRYPT_FAILED -> AppToolsStrings.toolsRxLogDecryptStatusDecryptFailed
    DecryptStatus.SUCCESS -> AppToolsStrings.toolsRxLogDecryptStatusDecrypted
    DecryptStatus.PENDING -> AppToolsStrings.toolsRxLogDecryptStatusHasKey
    DecryptStatus.DM_NO_MATCHING_KEY -> AppToolsStrings.toolsRxLogDecryptStatusNoDmKey
}

fun DiscoveredNodeDTO.makeContactFrame(lastModified: UInt): ContactFrame = ContactFrame(
    publicKey, nodeType, 0u, outPathLength, outPath, name, lastAdvertTimestamp,
    latitude, longitude, lastModified, typeRawValue,
)

fun DiscoveredNodeDTO.makeContactFrame(clock: Clock): ContactFrame {
    val seconds = clock.instant().epochSecond
    require(seconds in 0..UInt.MAX_VALUE.toLong()) { "Contact modification time is outside the wire UInt32 range" }
    return makeContactFrame(seconds.toUInt())
}

val NotificationLevel.labelResource: Int get() = when (this) {
    NotificationLevel.MUTED -> AppChatsStrings.chatsNotificationLevelMuted
    NotificationLevel.MENTIONS_ONLY -> AppChatsStrings.chatsNotificationLevelMentions
    NotificationLevel.ALL -> AppChatsStrings.chatsNotificationLevelAll
}

val NotificationLevel.accessibilityResource: Int get() = when (this) {
    NotificationLevel.MUTED -> AppChatsStrings.chatsNotificationLevelAccessibilityMuted
    NotificationLevel.MENTIONS_ONLY -> AppChatsStrings.chatsNotificationLevelAccessibilityMentionsOnly
    NotificationLevel.ALL -> AppChatsStrings.chatsNotificationLevelAccessibilityAll
}

val RoomPermissionLevel.labelResource: Int get() = when (this) {
    RoomPermissionLevel.GUEST -> AppRemoteNodesStrings.remoteNodesPermissionGuest
    RoomPermissionLevel.READ_WRITE -> AppRemoteNodesStrings.remoteNodesPermissionMember
    RoomPermissionLevel.ADMIN -> AppRemoteNodesStrings.remoteNodesPermissionAdmin
}

val UByte.hexString: String get() = toString(16).uppercase(Locale.ROOT).padStart(2, '0')

val RSSITuning.SignalTier.accessibilityResource: Int get() = when (this) {
    RSSITuning.SignalTier.WEAK -> AppLocalizableStrings.accessibilitySignalStrengthWeak
    RSSITuning.SignalTier.MEDIUM -> AppLocalizableStrings.accessibilitySignalStrengthMedium
    RSSITuning.SignalTier.STRONG -> AppLocalizableStrings.accessibilitySignalStrengthStrong
}
