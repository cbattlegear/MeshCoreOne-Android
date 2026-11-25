// AndroidOnly: WP-306 Transactional counter-only list actions adapted from Avi0n/MeshCoreOne@122d6af88e92384bb2780cf05b7afbe19e2e1d37.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.ConversationReadStatePersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ChannelDTO

internal class ConversationReadStateRepository(
    private val context: RoomRepositoryContext,
) : ConversationReadStatePersisting {
    override suspend fun setContactReadState(key: EntityKey, isRead: Boolean) =
        context.write("setContactReadState") {
            val row = database.contacts().byId(key.radioId.value, key.id)
                ?: throw PersistenceStoreException(PersistenceStoreError.ContactNotFound)
            database.contacts().upsert(row.copy(
                unreadCount = if (isRead) 0 else maxOf(row.unreadCount, 1),
                unreadMentionCount = if (isRead) 0 else row.unreadMentionCount,
            ))
            save()
        }

    override suspend fun setChannelReadState(expected: ChannelDTO, isRead: Boolean) =
        context.channelWrite("setChannelReadState") {
            val row = database.channels().byId(expected.radioId.value, expected.id)
                ?: throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
            val occupant = database.channelOccupants().forIndex(expected.radioId.value, expected.index.toLong())
            if (row.index != expected.index.toLong() || row.secret != expected.secret ||
                row.isEnabled != expected.isEnabled ||
                occupant != expected.occupantID
            ) throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
            database.channels().upsert(row.copy(
                unreadCount = if (isRead) 0 else maxOf(row.unreadCount, 1),
                unreadMentionCount = if (isRead) 0 else row.unreadMentionCount,
            ))
            save()
        }

    override suspend fun setRoomReadState(key: EntityKey, isRead: Boolean) =
        context.write("setRoomReadState") {
            val row = database.sessions().byId(key.radioId.value, key.id)
                ?: throw PersistenceStoreException(PersistenceStoreError.RemoteNodeSessionNotFound)
            database.sessions().upsert(row.copy(unreadCount = if (isRead) 0 else maxOf(row.unreadCount, 1)))
            save()
        }
}
