// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/MessagePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+PendingSends.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID

internal class PendingSendRepository(private val context: RoomRepositoryContext) : PendingSendPersisting, QueuedChannelTerminalPersisting {
    override suspend fun upsertPendingSend(dto: PendingSendDTO) = context.write("upsertPendingSend") {
        val existing = database.pendingSends().byId(dto.radioId.value, dto.id)
        val candidate = dto.toEntity()
        if (existing != null && (existing.isChannel || candidate.isChannel)) {
            if (candidate.copy(
                    channelDispatchStateRawValue = existing.channelDispatchStateRawValue,
                    channelAttemptID = existing.channelAttemptID,
                ) != existing) reject(existing, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
            if ((candidate.channelDispatchStateRawValue != null &&
                candidate.channelDispatchStateRawValue != existing.channelDispatchStateRawValue) ||
                (candidate.channelAttemptID != null && candidate.channelAttemptID != existing.channelAttemptID)
            ) reject(existing, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        } else {
            if (candidate.isChannel && (dto.channelDispatchState != null || dto.channelAttemptID != null)) {
                reject(candidate, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
            }
            database.pendingSends().upsert(candidate)
        }
        save()
    }

    override suspend fun insertPendingSendAssigningSequence(dto: PendingSendDTO): Long =
        context.write("insertPendingSendAssigningSequence") {
            val next = checkedIncrement(database.pendingSends().maximumSequence(dto.radioId.value) ?: 0)
            database.pendingSends().upsert(newSubmission(dto.copy(sequence = next)))
            save()
            next
        }

    override suspend fun replacePendingSendForRetry(messageID: UUID, dto: PendingSendDTO): Long =
        context.write("replacePendingSendForRetry") {
            if (dto.messageID != messageID) {
                reject(dto.toEntity(), QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
            }
            val previous = database.pendingSends().forMessage(dto.radioId.value, messageID)
            previous.forEach(::requireRemovable)
            if (previous.any { it.id == dto.id && it.isChannel }) {
                reject(dto.toEntity(), QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
            }
            val replacement = newSubmission(dto, replacing = previous.map { it.id }.toSet())
            database.pendingSends().deleteForMessage(dto.radioId.value, messageID)
            database.messages().setStatusUnlessDelivered(dto.radioId.value, messageID, MessageStatus.PENDING.rawValue)
            val next = checkedIncrement(database.pendingSends().maximumSequence(dto.radioId.value) ?: 0)
            database.pendingSends().upsert(replacement.copy(sequence = next))
            save()
            next
        }

    override suspend fun fetchPendingSends(radioId: RadioId): SnapshotList<PendingSendDTO> =
        context.read("fetchPendingSends") { projectPending(database.pendingSends().forRadio(radioId.value)) }

    override suspend fun fetchPendingSendsForMessage(key: EntityKey): SnapshotList<PendingSendDTO> =
        context.read("fetchPendingSendsForMessage") {
            projectPending(database.pendingSends().forMessage(key.radioId.value, key.id))
        }

    private fun projectPending(rows: List<PendingSendEntity>): SnapshotList<PendingSendDTO> =
        rows.mapNotNull { row ->
            if (PendingSendKind.fromRawValue(row.kindRawValue) == null) {
                context.reporter.report(
                    "unknownPendingSendKind",
                    PersistenceStoreException(PersistenceStoreError.InvalidData,
                        DatabaseValueException("pendingSend.kind", "Unknown raw value ${row.kindRawValue}")),
                )
                null
            } else {
                row.toDTO()
            }
        }.snapshot()

    override suspend fun deletePendingSend(key: EntityKey) = context.write("deletePendingSend") {
        database.pendingSends().byId(key.radioId.value, key.id)?.let(::requireRemovable)
        if (database.pendingSends().delete(key.radioId.value, key.id) > 0) save()
    }

    override suspend fun deletePendingSendsForMessage(key: EntityKey) =
        context.write("deletePendingSendsForMessage") {
            database.pendingSends().forMessage(key.radioId.value, key.id).forEach(::requireRemovable)
            if (database.pendingSends().deleteForMessage(key.radioId.value, key.id) > 0) save()
        }

    override suspend fun hasPendingSend(key: EntityKey): Boolean = context.read("hasPendingSend") {
        database.pendingSends().forMessage(key.radioId.value, key.id).isNotEmpty()
    }

    override suspend fun incrementPendingSendAttemptCount(key: EntityKey): Long? =
        context.write("incrementPendingSendAttemptCount") {
            val row = database.pendingSends().forMessage(key.radioId.value, key.id).firstOrNull()
                ?: return@write null
            val next = checkedIncrement(row.attemptCount ?: 0)
            database.pendingSends().setAttemptCount(key.radioId.value, row.id, next)
            save()
            next
        }

    override suspend fun purgeOrphanPendingSends(): Long = context.write("purgeOrphanPendingSends") {
        val rows = database.pendingSends().orphanedRadioRows().filterNot { it.isChannel }
        for (row in rows) database.pendingSends().delete(row.radioId, row.id)
        if (rows.isNotEmpty()) save()
        rows.size.toLong()
    }

    override suspend fun purgeLegacyAttemptCountRows(): Long = context.write("purgeLegacyAttemptCountRows") {
        val rows = database.pendingSends().legacyAttemptRows().filterNot { it.isChannel }
        for (row in rows) database.pendingSends().delete(row.radioId, row.id)
        if (rows.isNotEmpty()) save()
        rows.size.toLong()
    }

    override suspend fun beginQueuedChannelAttempt(
        key: QueuedChannelAttemptKey, expectedOccupantID: UUID, wireTimestamp: UInt,
    ): QueuedChannelAttempt = context.write("beginQueuedChannelAttempt") {
        val pending = submission(key)
        if (pending.channelDispatchStateRawValue == null) reject(pending, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
        if (pending.channelDispatchStateRawValue != QueuedChannelDispatchState.READY.rawValue || pending.channelAttemptID != null) {
            reject(pending, QueuedChannelAttemptConflict.SUBMISSION_NOT_READY)
        }
        database.pendingSends().forMessage(key.radioId.value, key.messageID)
            .filterNot { it.id == key.pendingID }.forEach(::requireRemovable)
        val index = pending.channelIndex
        val message = database.messages().byId(key.radioId.value, key.messageID)
        if (!pending.isChannel || pending.contactID != null || index == null || pending.channelOccupantID != expectedOccupantID ||
            message == null || message.directionRawValue != MessageDirection.OUTGOING.rawValue ||
            message.contactID != null ||
            message.channelIndex != index || message.channelOccupantID != expectedOccupantID || message.text != pending.messageText
        ) reject(pending, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        if (database.queuedChannelAttempts().byId(key.radioId.value, key.attemptID) != null) {
            reject(pending, QueuedChannelAttemptConflict.ATTEMPT_ID_REUSED)
        }
        val attempt = QueuedChannelAttemptEntity(
            key.radioId.value, key.attemptID, key.pendingID, key.messageID, index,
            expectedOccupantID, wireTimestamp.toLong(), pending.isResend, QueuedChannelAttemptPhase.DISPATCHING.rawValue,
        )
        if (database.pendingSends().beginChannelAttempt(
                key.radioId.value, key.pendingID, key.messageID, expectedOccupantID, key.attemptID,
            ) != 1) reject(pending, QueuedChannelAttemptConflict.SUBMISSION_NOT_READY)
        database.queuedChannelAttempts().insert(attempt)
        save()
        attempt.toDTO()
    }

    override suspend fun settleQueuedChannelAttempt(
        key: QueuedChannelAttemptKey, outcome: QueuedChannelAttemptOutcome,
    ): QueuedChannelAttempt = context.write("settleQueuedChannelAttempt") {
        val attempt = attempt(key)
        val current = attempt.toDTO()
        if (current.phase == outcome.phase) return@write current
        if (current.phase != QueuedChannelAttemptPhase.DISPATCHING || current.isCompleted) {
            reject(key, QueuedChannelAttemptConflict.CONFLICTING_SETTLEMENT)
        }
        val pending = submission(key)
        if (database.pendingSends().settleChannelAttempt(
                key.radioId.value, key.pendingID, key.messageID, attempt.channelOccupantID, key.attemptID,
                outcome.submissionState.rawValue,
                key.attemptID.takeUnless { outcome == QueuedChannelAttemptOutcome.DEFINITELY_UNSENT },
            ) != 1) reject(pending, QueuedChannelAttemptConflict.SUBMISSION_NOT_READY)
        if (database.queuedChannelAttempts().settle(
                key.radioId.value, key.pendingID, key.messageID, key.attemptID, outcome.phase.rawValue,
            ) != 1) reject(key, QueuedChannelAttemptConflict.CONFLICTING_SETTLEMENT)
        save()
        current.copy(phase = outcome.phase)
    }

    override suspend fun completeAcceptedQueuedChannelAttempt(key: QueuedChannelAttemptKey) =
        context.write("completeAcceptedQueuedChannelAttempt") {
            val attempt = attempt(key)
            if (attempt.toDTO().phase != QueuedChannelAttemptPhase.ACCEPTED) {
                reject(key, QueuedChannelAttemptConflict.NOT_ACCEPTED)
            }
            if (attempt.isCompleted) return@write
            val pending = submission(key)
            val message = database.messages().byId(key.radioId.value, key.messageID)
            if (message == null || message.channelIndex != attempt.channelIndex ||
                message.channelOccupantID != attempt.channelOccupantID ||
                message.contactID != null ||
                message.directionRawValue != MessageDirection.OUTGOING.rawValue || message.text != pending.messageText ||
                pending.isResend != attempt.isResend || pending.channelOccupantID != attempt.channelOccupantID
            ) reject(pending, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
            if (database.pendingSends().forMessage(key.radioId.value, key.messageID).any { it.id != key.pendingID }) {
                reject(pending, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
            }
            if (database.pendingSends().deleteAcceptedChannelAttempt(
                    key.radioId.value, key.pendingID, key.messageID, key.attemptID,
                ) != 1) reject(pending, QueuedChannelAttemptConflict.SUBMISSION_NOT_READY)
            database.messages().upsert(message.copy(
                statusRawValue = if (message.statusRawValue == MessageStatus.DELIVERED.rawValue) {
                    message.statusRawValue
                } else MessageStatus.SENT.rawValue,
                timestamp = attempt.wireTimestamp,
                sendCount = if (attempt.isResend) checkedIncrement(message.sendCount) else message.sendCount,
                heardRepeats = if (attempt.isResend) 0 else message.heardRepeats,
            ))
            if (attempt.isResend) database.repeats().deleteForMessage(key.radioId.value, key.messageID)
            if (database.queuedChannelAttempts().complete(
                    key.radioId.value, key.pendingID, key.messageID, key.attemptID,
                ) != 1) reject(key, QueuedChannelAttemptConflict.CONFLICTING_SETTLEMENT)
            save()
        }

    override suspend fun fetchQueuedChannelAttempt(key: QueuedChannelAttemptKey): QueuedChannelAttempt? =
        context.read("fetchQueuedChannelAttempt") {
            database.queuedChannelAttempts().byId(key.radioId.value, key.attemptID)?.toDTO()?.takeIf { it.key == key }
        }

    override suspend fun retireDefinitelyUnsentQueuedChannelAttempt(key: QueuedChannelAttemptKey): QueuedChannelRetirement =
        context.write("retireDefinitelyUnsentQueuedChannelAttempt") {
            val pending = submission(key)
            if (pending.channelDispatchStateRawValue == null) reject(pending, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
            requireRemovable(pending)
            val attempt = attempt(key).toDTO()
            val latest = database.queuedChannelAttempts().forPending(key.radioId.value, key.pendingID).lastOrNull()?.toDTO()
            if (attempt.phase != QueuedChannelAttemptPhase.DEFINITELY_UNSENT || attempt.isCompleted || latest?.key != key) {
                reject(key, QueuedChannelAttemptConflict.CONFLICTING_SETTLEMENT)
            }
            val message = database.messages().byId(key.radioId.value, key.messageID)
            if (message == null || message.directionRawValue != MessageDirection.OUTGOING.rawValue ||
                message.contactID != null || pending.contactID != null ||
                message.channelIndex != attempt.channelIndex.toLong() || pending.channelIndex != message.channelIndex ||
                message.channelOccupantID != attempt.channelOccupantID || pending.channelOccupantID != attempt.channelOccupantID ||
                message.text != pending.messageText || pending.isResend != attempt.isResend
            ) reject(pending, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
            if (database.pendingSends().forMessage(key.radioId.value, key.messageID).any { it.id != key.pendingID }) {
                reject(pending, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
            }
            val status = if (message.statusRawValue == MessageStatus.DELIVERED.rawValue) MessageStatus.DELIVERED else MessageStatus.FAILED
            val changed = message.statusRawValue != status.rawValue
            if (database.pendingSends().delete(key.radioId.value, key.pendingID) != 1) {
                reject(key, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
            }
            if (changed) {
                database.messages().upsert(message.copy(
                    statusRawValue = status.rawValue,
                    failureSeen = if (status == MessageStatus.FAILED) false else message.failureSeen,
                ))
            }
            save()
            QueuedChannelRetirement(status, changed)
        }

    override suspend fun fetchQueuedChannelAttempts(pending: EntityKey): SnapshotList<QueuedChannelAttempt> =
        context.read("fetchQueuedChannelAttempts") {
            database.queuedChannelAttempts().forPending(pending.radioId.value, pending.id).map { it.toDTO() }.snapshot()
        }

    private suspend fun RepositoryTransaction.newSubmission(dto: PendingSendDTO, replacing: Set<UUID> = emptySet()): PendingSendEntity {
        val row = dto.toEntity()
        val existing = database.pendingSends().byId(row.radioId, row.id)
        if (!row.isChannel) {
            if (existing?.isChannel == true) reject(existing, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
            return row
        }
        if (existing != null ||
            database.queuedChannelAttempts().forPending(row.radioId, row.id).isNotEmpty()
        ) reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        database.pendingSends().forMessage(row.radioId, row.messageID)
            .filterNot { it.id in replacing }.forEach(::requireRemovable)
        if (dto.channelDispatchState != null || dto.channelAttemptID != null) {
            reject(row, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
        }
        return row.copy(channelDispatchStateRawValue = QueuedChannelDispatchState.READY.rawValue.takeIf { row.channelOccupantID != null })
    }

    private suspend fun RepositoryTransaction.submission(key: QueuedChannelAttemptKey): PendingSendEntity {
        val row = database.pendingSends().byId(key.radioId.value, key.pendingID)
            ?: reject(key, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
        if (row.messageID != key.messageID || !row.isChannel) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        return row
    }

    private suspend fun RepositoryTransaction.attempt(key: QueuedChannelAttemptKey): QueuedChannelAttemptEntity {
        val row = database.queuedChannelAttempts().byId(key.radioId.value, key.attemptID)
            ?: reject(key, QueuedChannelAttemptConflict.MISSING_ATTEMPT)
        if (row.pendingID != key.pendingID || row.messageID != key.messageID) {
            reject(key, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        }
        return row
    }

    private val PendingSendEntity.isChannel: Boolean get() = kindRawValue == PendingSendKind.CHANNEL.rawValue

    private fun requireRemovable(row: PendingSendEntity) {
        if (row.isChannel && (row.channelDispatchStateRawValue != QueuedChannelDispatchState.READY.rawValue || row.channelAttemptID != null)) {
            reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
    }

    private fun reject(row: PendingSendEntity, reason: QueuedChannelAttemptConflict): Nothing =
        throw PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(EntityKey(RadioId(row.radioId), row.id), reason))

    private fun reject(key: QueuedChannelAttemptKey, reason: QueuedChannelAttemptConflict): Nothing =
        throw PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(EntityKey(key.radioId, key.pendingID), reason))
}
