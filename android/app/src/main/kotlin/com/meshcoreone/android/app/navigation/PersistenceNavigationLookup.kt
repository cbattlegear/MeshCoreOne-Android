// PortedFrom: MC1/State/NavigationCoordinator.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Read-only adapter; the injected process store is never created or closed by navigation.
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryRejected
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ChannelDTO
import java.util.UUID

class PersistenceNavigationLookup(private val store: PersistenceStoreProtocol) : NavigationLookup {
    override suspend fun contact(key: EntityKey) = store.fetchContact(key)
    override suspend fun channel(radioId: RadioId, index: UByte) = store.fetchChannel(radioId, index)
    override suspend fun room(key: EntityKey) = store.fetchRemoteNodeSession(key)
    override suspend fun isChannelNotificationTarget(message: EntityKey, channel: ChannelDTO, incarnation: String): Boolean {
        val occupant = channel.occupantID ?: return false
        val history = store as? ChannelHistoryPersisting ?: return false
        return try {
            history.fetchChannelTimelineMessage(ChannelHistoryScope(channel.radioId, channel.index, occupant), message)
                ?.channelReactionIncarnation == incarnation
        } catch (failure: PersistenceStoreException) {
            if (failure.cause !is ChannelHistoryRejected) throw failure
            false
        }
    }
    override suspend fun channelReactionTarget(occurrence: EntityKey, message: UUID, channel: ChannelDTO): String? {
        val target = store.fetchCommittedReaction(occurrence) ?: return null
        val row = target.target
        return target.targetIncarnation.takeIf {
            row.id == message && row.radioId == channel.radioId && row.channelIndex == channel.index &&
                row.contactID == null && row.channelOccupantID != null && row.channelOccupantID == channel.occupantID
        }
    }

    override suspend fun directReactionTarget(occurrence: EntityKey, message: UUID, contact: EntityKey, token: String): String? {
        val committed = store.fetchCommittedReaction(occurrence) ?: return null
        val row = committed.target
        return committed.targetIncarnation.takeIf {
            committed.notificationOccurrenceToken == token &&
                occurrence.radioId == contact.radioId && row.radioId == contact.radioId && row.id == message &&
                row.contactID == contact.id && row.channelIndex == null
        }
    }
}
