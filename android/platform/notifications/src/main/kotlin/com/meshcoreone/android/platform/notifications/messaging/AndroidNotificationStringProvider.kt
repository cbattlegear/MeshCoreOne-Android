// PortedFrom: MC1/Services/NotificationStringProviderImpl.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.platform.notifications.messaging

import android.content.Context
import com.meshcoreone.android.core.contracts.domain.NotificationStringProvider
import com.meshcoreone.android.core.l10n.MecpReading
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.protocol.model.ContactType

class AndroidNotificationStringProvider(context: Context) : NotificationStringProvider {
    private val resources = context.applicationContext.resources

    override fun discoveryNotificationTitle(type: ContactType): String = resources.getString(
        when (type) {
            ContactType.CHAT -> AppLocalizableStrings.notificationsDiscoveryContact
            ContactType.REPEATER -> AppLocalizableStrings.notificationsDiscoveryRepeater
            ContactType.ROOM -> AppLocalizableStrings.notificationsDiscoveryRoom
            ContactType.SENSOR, ContactType.UNKNOWN -> AppLocalizableStrings.notificationsDiscoveryContact
        },
    )

    override val replyActionTitle: String
        get() = resources.getString(AppLocalizableStrings.notificationsActionReply)
    override val sendButtonTitle: String
        get() = resources.getString(AppLocalizableStrings.notificationsActionSend)
    override val messagePlaceholder: String
        get() = resources.getString(AppLocalizableStrings.notificationsActionMessagePlaceholder)
    override val markAsReadActionTitle: String
        get() = resources.getString(AppLocalizableStrings.notificationsActionMarkAsRead)
    override val lowBatteryTitle: String
        get() = resources.getString(AppLocalizableStrings.notificationsLowBatteryTitle)

    override fun lowBatteryBody(deviceName: String, percentage: Long): String =
        AppLocalizableStrings.notificationsLowBatteryBody(resources, deviceName, percentage.toInt())

    override val quickReplyFailedTitle: String
        get() = resources.getString(AppLocalizableStrings.notificationsQuickReplyFailedTitle)

    override fun quickReplyFailedBody(conversationName: String): String =
        AppLocalizableStrings.notificationsQuickReplyFailedBody(resources, conversationName)

    override val unknownContactName: String
        get() = resources.getString(AppLocalizableStrings.notificationsDiscoveryUnknownContact)

    override fun defaultChannelName(index: Long): String =
        AppChatsStrings.chatsChannelDefaultName(resources, index.toInt())

    override fun messageDisplayText(storedText: String): String = MecpReading.display(storedText, resources)

    override fun reactionNotificationBody(emoji: String, messagePreview: String): String =
        AppLocalizableStrings.notificationsReactionBody(resources, emoji, messagePreview)
}
