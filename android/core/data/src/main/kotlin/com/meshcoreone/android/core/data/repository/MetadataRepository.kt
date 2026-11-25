// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Metadata.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.model.ContactType

internal class MetadataRepository(private val context: RoomRepositoryContext) : MetadataPersisting {
    override suspend fun getTotalUnreadCounts(radioId: RadioId): UnreadCounts =
        context.read("getTotalUnreadCounts") {
            val contacts = database.contacts().forRadio(radioId.value)
                .filter { it.unreadCount > 0 && !it.isMuted && !it.isBlocked && it.typeRawValue != ContactType.REPEATER.rawValue.toLong() }
                .fold(0L) { total, row -> checkedAdd(total, row.unreadCount) }
            val channels = database.channels().forRadio(radioId.value)
                .filter { it.notificationLevelRawValue != NotificationLevel.MUTED.rawValue && (it.unreadCount > 0 || it.unreadMentionCount > 0) }
                .fold(0L) { total, row ->
                    checkedAdd(total, if (row.toDTO().notificationLevel == NotificationLevel.MENTIONS_ONLY) row.unreadMentionCount else row.unreadCount)
                }
            val rooms = database.sessions().forRadio(radioId.value)
                .filter { it.notificationLevelRawValue != NotificationLevel.MUTED.rawValue &&
                    it.unreadCount > 0 && it.roleRawValue == RemoteNodeRole.ROOM_SERVER.rawValue.toLong() }
                .fold(0L) { total, row -> checkedAdd(total, row.unreadCount) }
            UnreadCounts(contacts, channels, rooms)
        }

    override suspend fun getUnreadCount(contact: EntityKey): Long = context.read("getUnreadCount") {
        database.contacts().byId(contact.radioId.value, contact.id)?.unreadCount ?: 0
    }

    override suspend fun getChannelUnreadCount(channel: EntityKey): Long = context.read("getChannelUnreadCount") {
        database.channels().byId(channel.radioId.value, channel.id)?.unreadCount ?: 0
    }
}
