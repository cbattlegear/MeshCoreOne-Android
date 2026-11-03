// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+PendingSends.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.database.MessageEntity
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus

internal suspend fun RepositoryTransaction.deleteMessageDependents(
    radioId: RadioId,
    messages: List<MessageEntity>,
    reactionsByMessage: Boolean,
) {
    for (message in messages) {
        database.pendingSends().deleteForMessage(radioId.value, message.id)
        database.repeats().deleteForMessage(radioId.value, message.id)
        if (reactionsByMessage) database.reactions().deleteForMessage(radioId.value, message.id)
    }
}

internal suspend fun RepositoryTransaction.deleteContactMessages(key: EntityKey) {
    val messages = database.messages().newestForContact(key.radioId.value, key.id, -1)
    deleteMessageDependents(key.radioId, messages, reactionsByMessage = false)
    database.reactions().deleteForContact(key.radioId.value, key.id)
    database.messages().deleteContactMessages(key.radioId.value, key.id)
}

internal suspend fun RepositoryTransaction.deleteChannelMessages(
    radioId: RadioId, index: UByte, preserveUnresolved: Boolean = false,
) {
    val messages = database.messages().newestForChannel(radioId.value, index.toLong(), -1)
    if (!preserveUnresolved) {
        deleteMessageDependents(radioId, messages, reactionsByMessage = true)
        database.messages().deleteChannelMessages(radioId.value, index.toLong())
        return
    }
    for (message in messages) {
        val unresolved = message.directionRawValue == MessageDirection.OUTGOING.rawValue &&
            (message.statusRawValue in setOf(
                MessageStatus.PENDING.rawValue, MessageStatus.SENDING.rawValue,
                MessageStatus.RETRYING.rawValue, MessageStatus.FAILED.rawValue,
            ) || database.pendingSends().forMessage(radioId.value, message.id).isNotEmpty())
        if (unresolved) {
            database.messages().setStatusUnlessDelivered(radioId.value, message.id, MessageStatus.FAILED.rawValue)
        } else {
            deleteMessageDependents(radioId, listOf(message), reactionsByMessage = true)
            database.messages().delete(radioId.value, message.id)
        }
    }
}
