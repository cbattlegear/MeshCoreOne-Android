// PortedFrom: MC1Services/Sources/MC1Services/Protocols/Persistence/MessagePersisting.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistenceStore+PendingSends.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import java.time.Instant

internal class PendingSendRepository(private val context: RoomRepositoryContext) :
    PendingSendPersisting, QueuedChannelTerminalPersisting, QueuedChannelReadinessPersisting, ChannelSubmissionPersisting {
    override suspend fun upsertPendingSend(dto: PendingSendDTO) = context.write("upsertPendingSend") {
        val existing = database.pendingSends().byId(dto.radioId.value, dto.id)
        if (database.channelSubmissions().byId(dto.id) != null && existing == null) {
            rejectSubmission(EntityKey(dto.radioId, dto.id), ChannelSubmissionConflict.IDENTITY_CONFLICT)
        }
        val candidate = dto.toEntity()
        if (existing != null && existing.messageID != candidate.messageID) {
            reject(candidate, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        }
        if (existing == null &&
            database.queuedChannelAttempts().forPending(candidate.radioId, candidate.id).isNotEmpty()
        ) reject(candidate, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        if (existing != null && PendingSendKind.fromRawValue(existing.kindRawValue) == null) {
            reject(existing, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
        rejectConflictingSibling(candidate)
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
            if (previous.any { PendingSendKind.fromRawValue(it.kindRawValue) == null }) {
                reject(previous.first { PendingSendKind.fromRawValue(it.kindRawValue) == null },
                    QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
            }
            if (previous.any { it.isChannel != (dto.kind == PendingSendKind.CHANNEL) }) {
                reject(previous.first { it.isChannel != (dto.kind == PendingSendKind.CHANNEL) },
                    QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
            }
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

    override suspend fun admitChannelSubmission(
        submission: ChannelSubmission, enqueuedAt: Instant,
    ): ChannelSubmissionEvidence = context.write("admitChannelSubmission") {
        val identity = EntityKey(submission.origin.radioId, submission.pendingID)
        val retained = database.channelSubmissions().byId(submission.pendingID)
        if (retained != null) {
            requireSubmission(retained, submission)
            return@write submissionEvidence(retained)
        }
        val envelope = submission.envelope
        if (database.channelSubmissions().hasUnreceiptedIdentity(identity.id)) {
            rejectSubmission(identity, ChannelSubmissionConflict.UNKNOWN_LEGACY_SUBMISSION)
        }
        val message = database.messages().byId(identity.radioId.value, envelope.messageID)
        if (message == null || message.directionRawValue != MessageDirection.OUTGOING.rawValue ||
            message.contactID != null || message.channelIndex != envelope.channelIndex.toLong() ||
            message.channelOccupantID != submission.origin.expectedOccupantID || message.text != envelope.messageText ||
            message.timestamp != envelope.messageTimestamp.toLong()
        ) rejectSubmission(identity, ChannelSubmissionConflict.MESSAGE_MISMATCH)
        val next = checkedIncrement(database.pendingSends().maximumSequence(identity.radioId.value) ?: 0)
        val pending = newSubmission(PendingSendDTO.fromEnvelope(
            envelope, identity.radioId, id = identity.id, enqueuedAt = enqueuedAt,
        ).copy(sequence = next))
        val receipt = ChannelSubmissionEntity(identity.id, identity.radioId.value, envelope.messageID,
            envelope.channelIndex.toLong(), submission.origin.expectedOccupantID, envelope.messageText,
            envelope.messageTimestamp.toLong(), envelope.localNodeName, envelope.isResend,
            enqueuedAt.epochSecond, enqueuedAt.nano, next, ChannelSubmissionPhase.ADMITTED.rawValue)
        database.channelSubmissions().insert(receipt)
        database.pendingSends().upsert(pending)
        save()
        submissionEvidence(receipt)
    }

    override suspend fun reconcileChannelSubmission(submission: ChannelSubmission): ChannelSubmissionReconciliation =
        context.read("reconcileChannelSubmission") {
            val retained = database.channelSubmissions().byId(submission.pendingID)
                ?: return@read ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.ABSENT)
            if (retained.phaseRawValue == 3L) {
                return@read ChannelSubmissionReconciliation.Unknown(ChannelSubmissionUnknown.REDACTED)
            }
            requireSubmission(retained, submission)
            ChannelSubmissionReconciliation.Known(submissionEvidence(retained))
        }

    private fun requireSubmission(row: ChannelSubmissionEntity, submission: ChannelSubmission) {
        val identity = EntityKey(submission.origin.radioId, submission.pendingID)
        if (row.phaseRawValue == 3L) rejectSubmission(identity, ChannelSubmissionConflict.REDACTED_SUBMISSION)
        if (row.radioId != identity.radioId.value || row.messageID != submission.envelope.messageID ||
            row.channelIndex != submission.origin.channelIndex.toLong() ||
            row.channelOccupantID != submission.origin.expectedOccupantID ||
            row.messageText != submission.envelope.messageText ||
            row.messageTimestamp != submission.envelope.messageTimestamp.toLong() ||
            row.localNodeName != submission.envelope.localNodeName || row.isResend != submission.envelope.isResend
        ) rejectSubmission(identity, ChannelSubmissionConflict.IDENTITY_CONFLICT)
    }

    private suspend fun RepositoryTransaction.submissionEvidence(row: ChannelSubmissionEntity): ChannelSubmissionEvidence {
        val phase = ChannelSubmissionPhase.entries.firstOrNull { it.rawValue == row.phaseRawValue }
            ?: throw DatabaseValueException("channelSubmission.phase", "Unknown or redacted submission phase")
        val origin = ChannelHistoryScope(RadioId(row.radioId), row.validatedIndex(), row.channelOccupantID)
        val text = row.messageText ?: throw DatabaseValueException("channelSubmission.text", "Missing retained intent")
        val submission = ChannelSubmission(row.pendingID, origin, ChannelMessageEnvelope(row.messageID, origin.channelIndex,
            row.isResend, text, row.validatedTimestamp(), row.localNodeName, row.channelOccupantID))
        val pending = database.pendingSends().byId(row.radioId, row.pendingID)?.toDTO()
        if (pending != null && (pending.radioId != origin.radioId || pending.channelMessageEnvelope() != submission.envelope)) {
            rejectSubmission(EntityKey(origin.radioId, row.pendingID), ChannelSubmissionConflict.IDENTITY_CONFLICT)
        }
        return ChannelSubmissionEvidence(submission, row.enqueuedAt(),
            row.sequence, phase, pending,
            database.queuedChannelAttempts().forPending(row.radioId, row.pendingID).map { it.toDTO() }.snapshot())
    }

    private fun rejectSubmission(identity: EntityKey, reason: ChannelSubmissionConflict): Nothing =
        throw PersistenceStoreException(PersistenceStoreError.InvalidData, ChannelSubmissionRejected(identity, reason))

    private suspend fun RepositoryTransaction.finishSubmission(key: QueuedChannelAttemptKey, phase: ChannelSubmissionPhase) {
        if (database.channelSubmissions().byId(key.pendingID) == null) return
        if (database.channelSubmissions().finish(key.pendingID, key.radioId.value, key.messageID, phase.rawValue) != 1) {
            rejectSubmission(EntityKey(key.radioId, key.pendingID), ChannelSubmissionConflict.IDENTITY_CONFLICT)
        }
    }

    override suspend fun incrementPendingSendAttemptCount(key: EntityKey): Long? =
        context.write("incrementPendingSendAttemptCount") {
            val row = database.pendingSends().forMessage(key.radioId.value, key.id).firstOrNull()
                ?: return@write null
            if (row.isChannel) {
                requireReadyQueuedChannelSubmission(row, key.id)
            } else if (row.isDirect) {
                rejectConflictingSibling(row)
            } else {
                reject(row, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
            }
            val next = checkedIncrement(row.attemptCount ?: 0)
            if (database.pendingSends().setAttemptCount(row.radioId, row.id, next) != 1) {
                reject(row, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
            }
            save()
            next
        }

    override suspend fun incrementReadyQueuedChannelAttemptCount(pending: EntityKey, messageID: UUID): Long =
        context.write("incrementReadyQueuedChannelAttemptCount") {
            val row = database.pendingSends().byId(pending.radioId.value, pending.id)
                ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData,
                    QueuedChannelAttemptRejected(pending, QueuedChannelAttemptConflict.MISSING_SUBMISSION))
            if (!row.isChannel || row.messageID != messageID) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
            requireReadyQueuedChannelSubmission(row, messageID)
            val next = checkedIncrement(row.attemptCount ?: 0)
            if (database.pendingSends().setAttemptCount(row.radioId, row.id, next) != 1) {
                reject(row, QueuedChannelAttemptConflict.MISSING_SUBMISSION)
            }
            save()
            next
        }

    private suspend fun RepositoryTransaction.requireReadyQueuedChannelSubmission(
        row: PendingSendEntity, messageID: UUID,
    ) {
        if (!row.isChannel || row.messageID != messageID) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        if (row.channelDispatchStateRawValue == null) reject(row, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
        requireRemovable(row)
        val message = database.messages().byId(row.radioId, messageID)
        if (row.channelIndex == null || row.contactID != null || row.channelOccupantID == null ||
            message == null || message.contactID != null || message.directionRawValue != MessageDirection.OUTGOING.rawValue ||
            message.channelIndex != row.channelIndex || message.channelOccupantID != row.channelOccupantID ||
            message.text != row.messageText
        ) reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        rejectConflictingSibling(row)
    }

    override suspend fun purgeOrphanPendingSends(): Long = context.write("purgeOrphanPendingSends") {
        val removable = mutableListOf<PendingSendEntity>()
        for (row in database.pendingSends().orphanedRadioRows()) {
            if (!row.isDirect) {
                if (!row.isChannel) context.reporter.report(
                    "purgeOrphanPendingSends", rejection(row, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION),
                )
                continue
            }
            if (findConflictingSibling(row) != null) {
                context.reporter.report(
                    "purgeOrphanPendingSends", rejection(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION),
                )
            } else {
                removable += row
            }
        }
        var deleted = 0L
        for (row in removable) deleted += database.pendingSends().delete(row.radioId, row.id).toLong()
        if (deleted > 0) save()
        deleted
    }

    override suspend fun purgeLegacyAttemptCountRows(): Long = context.write("purgeLegacyAttemptCountRows") {
        val removable = mutableListOf<PendingSendEntity>()
        for (row in database.pendingSends().legacyAttemptRows()) {
            if (!row.isDirect) {
                if (!row.isChannel) context.reporter.report(
                    "purgeLegacyAttemptCountRows", rejection(row, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION),
                )
                continue
            }
            if (findConflictingSibling(row) != null) {
                context.reporter.report(
                    "purgeLegacyAttemptCountRows", rejection(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION),
                )
            } else {
                removable += row
            }
        }
        var deleted = 0L
        for (row in removable) deleted += database.pendingSends().delete(row.radioId, row.id).toLong()
        if (deleted > 0) save()
        deleted
    }

    override suspend fun beginQueuedChannelAttempt(
        key: QueuedChannelAttemptKey, expectedOccupantID: UUID, wireTimestamp: UInt,
    ): QueuedChannelAttempt = context.write("beginQueuedChannelAttempt") {
        val pending = submission(key)
        if (pending.channelDispatchStateRawValue == null) reject(pending, QueuedChannelAttemptConflict.UNKNOWN_SUBMISSION)
        if (pending.channelDispatchStateRawValue != QueuedChannelDispatchState.READY.rawValue || pending.channelAttemptID != null) {
            reject(pending, QueuedChannelAttemptConflict.SUBMISSION_NOT_READY)
        }
        rejectConflictingSibling(pending)
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
            finishSubmission(key, ChannelSubmissionPhase.ACCEPTED_COMPLETED)
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
            finishSubmission(key, ChannelSubmissionPhase.RETIRED)
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
        if (database.channelSubmissions().byId(row.id) != null) {
            rejectSubmission(EntityKey(dto.radioId, dto.id), ChannelSubmissionConflict.IDENTITY_CONFLICT)
        }
        if (existing != null && PendingSendKind.fromRawValue(existing.kindRawValue) == null) {
            reject(existing, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
        if (existing != null && existing.messageID != row.messageID) {
            reject(row, QueuedChannelAttemptConflict.MISMATCHED_SUBMISSION)
        }
        if (!row.isChannel) {
            if (existing?.isChannel == true) reject(existing, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
            rejectConflictingSibling(row, replacing + row.id)
            return row
        }
        if (existing != null ||
            database.queuedChannelAttempts().forPending(row.radioId, row.id).isNotEmpty()
        ) reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        rejectConflictingSibling(row, replacing + row.id)
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
    private val PendingSendEntity.isDirect: Boolean get() = kindRawValue == PendingSendKind.DM.rawValue

    private suspend fun RepositoryTransaction.findConflictingSibling(
        row: PendingSendEntity, excludedIDs: Set<UUID> = setOf(row.id),
    ): PendingSendEntity? =
        database.pendingSends().forMessage(row.radioId, row.messageID).firstOrNull {
            it.id !in excludedIDs && (row.isChannel || !it.isDirect)
        }

    private suspend fun RepositoryTransaction.rejectConflictingSibling(
        row: PendingSendEntity, excludedIDs: Set<UUID> = setOf(row.id),
    ) {
        findConflictingSibling(row, excludedIDs)?.let {
            reject(it, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
    }

    private fun rejection(row: PendingSendEntity, reason: QueuedChannelAttemptConflict) =
        PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(EntityKey(RadioId(row.radioId), row.id), reason))

    private fun requireRemovable(row: PendingSendEntity) {
        if (row.isChannel && (row.channelDispatchStateRawValue != QueuedChannelDispatchState.READY.rawValue || row.channelAttemptID != null)) {
            reject(row, QueuedChannelAttemptConflict.RETAINED_SUBMISSION)
        }
    }

    private fun reject(row: PendingSendEntity, reason: QueuedChannelAttemptConflict): Nothing =
        throw rejection(row, reason)

    private fun reject(key: QueuedChannelAttemptKey, reason: QueuedChannelAttemptConflict): Nothing =
        throw PersistenceStoreException(PersistenceStoreError.InvalidData,
            QueuedChannelAttemptRejected(EntityKey(key.radioId, key.pendingID), reason))
}
