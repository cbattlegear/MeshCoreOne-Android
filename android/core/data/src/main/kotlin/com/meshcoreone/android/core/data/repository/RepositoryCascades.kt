// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Contacts.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Channels.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+Devices.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+PendingSends.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.LocalDirectMessageProvenance
import com.meshcoreone.android.core.contracts.domain.ChannelSubmissionConflict
import com.meshcoreone.android.core.contracts.domain.ChannelSubmissionRejected
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.QueuedChannelAttemptRejected
import com.meshcoreone.android.core.database.MessageEntity
import com.meshcoreone.android.core.database.PendingSendEntity
import com.meshcoreone.android.core.database.toDTO
import com.meshcoreone.android.core.model.PendingSendKind
import com.meshcoreone.android.core.model.QueuedChannelAttemptConflict
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus

internal suspend fun RepositoryTransaction.directPendingIdentityFailure(
    row: PendingSendEntity,
): PersistenceStoreException? {
    if (row.kindRawValue != PendingSendKind.DM.rawValue) return null
    val identity = EntityKey(RadioId(row.radioId), row.id)
    if (database.queuedChannelAttempts().forPending(row.radioId, row.id).isNotEmpty()) {
        return PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(identity, QueuedChannelAttemptConflict.RETAINED_SUBMISSION))
    }
    if (database.channelSubmissions().byId(row.id) != null) {
        return PersistenceStoreException(PersistenceStoreError.InvalidData,
            ChannelSubmissionRejected(identity, ChannelSubmissionConflict.IDENTITY_CONFLICT))
    }
    return null
}

internal suspend fun RepositoryTransaction.requireDirectPendingIdentityAvailable(row: PendingSendEntity) {
    directPendingIdentityFailure(row)?.let { throw it }
}

internal suspend fun RepositoryTransaction.deleteMessageDependents(
    radioId: RadioId,
    messages: List<MessageEntity>,
    reactionsByMessage: Boolean,
    preserveConflictingPending: Boolean = true,
) {
    for (message in messages) {
        if (preserveConflictingPending) {
            database.pendingSends().forMessage(radioId.value, message.id)
                .filter { it.kindRawValue == PendingSendKind.DM.rawValue }
                .forEach { requireDirectPendingIdentityAvailable(it) }
        }
        database.channelSubmissions().redactMessage(radioId.value, message.id)
        database.pendingSends().deleteForMessage(radioId.value, message.id)
        database.repeats().deleteForMessage(radioId.value, message.id)
        if (reactionsByMessage) database.reactions().deleteForMessage(radioId.value, message.id)
    }
}

internal suspend fun RepositoryTransaction.deleteContactMessages(
    key: EntityKey, preserveLocalOccurrences: Boolean = false,
) {
    val (preserved, messages) = database.messages().forContactRemoval(key.radioId.value, key.id).partition { row ->
        preserveLocalOccurrences && row.directionRawValue == MessageDirection.INCOMING.rawValue &&
            row.channelIndex == null && row.channelOccupantID == null &&
            LocalDirectMessageProvenance.of(row.toDTO()) != null
    }
    for (row in preserved) {
        database.messages().upsert(row.copy(
            contactID = null,
            deduplicationKey = LocalDirectMessageProvenance.UNRESOLVED.occurrenceKey(row.id),
        ))
    }
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
            database.pendingSends().forMessage(radioId.value, message.id)
                .filter { it.kindRawValue == PendingSendKind.DM.rawValue }
                .forEach { requireDirectPendingIdentityAvailable(it) }
            database.messages().setStatusUnlessDelivered(radioId.value, message.id, MessageStatus.FAILED.rawValue)
        } else {
            deleteMessageDependents(radioId, listOf(message), reactionsByMessage = true)
            database.messages().delete(radioId.value, message.id)
        }
    }
}
