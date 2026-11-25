// PortedFrom: MC1Services/Sources/MC1Services/Models/NotificationPreferences.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Immutable policy snapshot; WP-204 persists it and WP-317 supplies observable settings UI.
package com.meshcoreone.android.core.model

data class NotificationPreferences(
    val contactMessagesEnabled: Boolean,
    val channelMessagesEnabled: Boolean,
    val roomMessagesEnabled: Boolean,
    val newContactDiscoveredEnabled: Boolean,
    val discoveryContactEnabled: Boolean,
    val discoveryRepeaterEnabled: Boolean,
    val discoveryRoomEnabled: Boolean,
    val reactionNotificationsEnabled: Boolean,
    val soundEnabled: Boolean,
    val badgeEnabled: Boolean,
    val lowBatteryEnabled: Boolean,
) {
    fun enablingDiscovery(
        enabled: Boolean, hasStoredContactChoice: Boolean, hasStoredRepeaterChoice: Boolean, hasStoredRoomChoice: Boolean,
    ): NotificationPreferences = if (
        enabled && !newContactDiscoveredEnabled && !hasStoredContactChoice && !hasStoredRepeaterChoice && !hasStoredRoomChoice
    ) copy(
        newContactDiscoveredEnabled = true, discoveryContactEnabled = true,
        discoveryRepeaterEnabled = true, discoveryRoomEnabled = true,
    ) else copy(newContactDiscoveredEnabled = enabled)
}
