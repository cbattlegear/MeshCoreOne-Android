// AndroidOnly: WP-306 Identity-checked local pin writes adapted from Avi0n/MeshCoreOne@5e980a53e077c45a1270170612fdf5dae97792ff.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.ChatPinPersisting
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO

internal class ChatPinRepository(private val context: RoomRepositoryContext) : ChatPinPersisting {
    override suspend fun setContactPinned(expected: ContactDTO, isPinned: Boolean) =
        context.write("setContactPinned") {
            val row = database.contacts().byId(expected.radioId.value, expected.id)
                ?.takeIf { it.publicKey == expected.publicKey }
                ?: throw PersistenceStoreException(PersistenceStoreError.ContactNotFound)
            database.contacts().upsert(row.copy(isPinned = isPinned))
            save()
        }

    override suspend fun setChannelPinned(expected: ChannelDTO, isPinned: Boolean) =
        context.channelWrite("setChannelPinned") {
            val row = database.channels().byId(expected.radioId.value, expected.id)
                ?: throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
            val occupant = database.channelOccupants().forIndex(expected.radioId.value, expected.index.toLong())
            if (row.index != expected.index.toLong() || row.secret != expected.secret ||
                row.isEnabled != expected.isEnabled || occupant != expected.occupantID
            ) throw PersistenceStoreException(PersistenceStoreError.ChannelNotFound)
            database.channels().upsert(row.copy(isFavorite = isPinned))
            save()
        }

    override suspend fun setRoomPinned(expected: RemoteNodeSessionDTO, isPinned: Boolean) =
        context.write("setRoomPinned") {
            val row = database.sessions().byId(expected.radioId.value, expected.id)
                ?.takeIf { it.publicKey == expected.publicKey }
                ?: throw PersistenceStoreException(PersistenceStoreError.RemoteNodeSessionNotFound)
            database.sessions().upsert(row.copy(isFavorite = isPinned))
            save()
        }
}
