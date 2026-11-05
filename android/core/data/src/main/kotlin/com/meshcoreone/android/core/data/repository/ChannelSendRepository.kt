// AndroidOnly: WP-202 Atomic channel failure grants bind actual persisted row incarnations.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID

internal data class ChannelSendLease(val issuer: UUID, val token: SessionToken, val request: UUID)

internal class ChannelSendRepository(
    private val context: RoomRepositoryContext,
    private val directAcks: DirectAckRepository,
) : ChannelFailureAuthority {
    private data class Attempt(
        val lease: ChannelSendLease,
        val capture: UUID,
        val version: Long,
    ) : ChannelFailureAttempt

    override suspend fun activateChannelFailureSession(
        token: SessionToken,
        requestId: UUID,
    ): DirectAckActivationResult = directAcks.activateDirectAckSession(token, requestId)

    override suspend fun revokeChannelFailureSession(lease: DirectAckLease): DirectAckRevocationResult =
        directAcks.revokeDirectAckSession(lease)

    private suspend fun <T> transaction(
        operation: String,
        block: suspend RepositoryTransaction.() -> T,
    ): T = context.write(operation) {
        try {
            DirectAckStorage.verify(database.openHelper.writableDatabase)
        } catch (cause: IllegalStateException) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData, cause)
        }
        block()
    }

    override suspend fun createChannelFailureAttempt(
        lease: DirectAckLease,
        creationRequestId: UUID,
        message: MessageDTO,
    ): ChannelFailureCaptureResult = transaction("createChannelFailureAttempt") {
        val issued = issued(lease)
            ?: return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (message.radioId != issued.token.radioId) {
            return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val entity = message.toEntity()
        val digest = channelCreationDigest(message)
        val previous = database.channelSends().attempt(entity.radioId, creationRequestId)
        if (previous != null) {
            if (previous.leaseId != issued.request || previous.messageId != entity.id ||
                previous.origin != "CREATE" || previous.creationDigest != digest
            ) return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.CONFLICT)
            return@transaction captureReplay(previous, issued)
        }
        if (!active(issued)) return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        if (!eligible(entity) || entity.statusRawValue != MessageStatus.PENDING.rawValue) {
            return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (database.messages().byId(entity.radioId, entity.id) != null) {
            return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        database.channelSends().insertMessage(entity)
        val identity = identity(entity.radioId, entity.id, "message")
        val capture = ChannelSendAttemptEntity(
            entity.radioId, creationRequestId, issued.request, entity.id, "CREATE", digest, null,
            identity.incarnation, identity.revision, null, null, null, 0, false, false, true,
        )
        database.channelSends().insert(capture)
        save()
        ChannelFailureCaptureResult.Captured(Attempt(issued, creationRequestId, 0), receipt(capture, issued))
    }

    override suspend fun prepareChannelFailureAttempt(
        lease: DirectAckLease,
        prepareRequestId: UUID,
        key: EntityKey,
        pendingSendId: UUID?,
        resendTimestamp: UInt?,
    ): ChannelFailureCaptureResult = transaction("prepareChannelFailureAttempt") {
        val issued = issued(lease)
            ?: return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (key.radioId != issued.token.radioId) {
            return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val dao = database.channelSends()
        val previous = dao.attempt(key.radioId.value, prepareRequestId)
        if (previous != null) {
            if (previous.leaseId != issued.request || previous.messageId != key.id ||
                previous.origin != "PREPARE" || previous.pendingId != pendingSendId ||
                previous.resendTimestamp != resendTimestamp?.toLong()
            ) return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.CONFLICT)
            return@transaction captureReplay(previous, issued)
        }
        if (!active(issued)) return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        val row = database.messages().byId(key.radioId.value, key.id)
            ?: return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.MISSING)
        if (!eligible(row) || row.statusRawValue == MessageStatus.DELIVERED.rawValue) {
            return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        var messageIdentity = identity(row.radioId, row.id, "message")
        val pending = pendingSendId?.let {
            dao.pending(row.radioId, it)
                ?: return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.MISSING)
        }
        if (pending != null && !eligible(pending, row)) {
            return@transaction ChannelFailureCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val pendingIdentity = pending?.let { identity(it.radioId, it.id, "pending") }
        if (resendTimestamp != null) {
            ownWrite(dao.timestamp(row.radioId, row.id, messageIdentity.incarnation,
                messageIdentity.revision, resendTimestamp.toLong()))
            messageIdentity = advanced(messageIdentity)
        }
        val capture = ChannelSendAttemptEntity(
            row.radioId, prepareRequestId, issued.request, row.id, "PREPARE", null, resendTimestamp?.toLong(),
            messageIdentity.incarnation, messageIdentity.revision, pendingSendId,
            pendingIdentity?.incarnation, pendingIdentity?.revision, 0, false, false, true,
        )
        dao.insert(capture)
        save()
        ChannelFailureCaptureResult.Captured(Attempt(issued, prepareRequestId, 0), receipt(capture, issued))
    }

    override suspend fun remapChannelFailureAttempt(
        attempt: ChannelFailureAttempt,
        requestId: UUID,
    ): ChannelFailureRemapResult = transaction("remapChannelFailureAttempt") {
        val handle = issued(attempt)
            ?: return@transaction ChannelFailureRemapResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val dao = database.channelSends()
        val captured = dao.attempt(handle.lease.token.radioId.value, handle.capture)
            ?: return@transaction ChannelFailureRemapResult.Rejected(DirectAckRejection.MISSING)
        if (captured.leaseId != handle.lease.request) {
            return@transaction ChannelFailureRemapResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val previous = dao.operation(captured.radioId, captured.captureRequestId, requestId)
        if (previous != null) {
            if (previous.kind != ChannelFailureOperation.REMAPPED_TO_PENDING.name ||
                previous.inputVersion != handle.version
            ) return@transaction ChannelFailureRemapResult.Rejected(DirectAckRejection.CONFLICT)
            rejection(permission(captured, handle.lease))?.let {
                return@transaction ChannelFailureRemapResult.Rejected(it)
            }
            if (previous.outputVersion != captured.version) {
                return@transaction ChannelFailureRemapResult.Rejected(DirectAckRejection.INVALIDATED)
            }
            return@transaction ChannelFailureRemapResult.AlreadyRemapped(
                handle.copy(version = previous.outputVersion), receipt(captured, handle.lease),
            )
        }
        rejection(permission(captured, handle.lease))?.let {
            return@transaction ChannelFailureRemapResult.Rejected(it)
        }
        if (handle.version != captured.version) {
            return@transaction ChannelFailureRemapResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        val row = currentMessage(captured)
        if (row.statusRawValue == MessageStatus.DELIVERED.rawValue) {
            return@transaction ChannelFailureRemapResult.AlreadyDelivered(receipt(captured, handle.lease))
        }
        ownWrite(dao.status(captured.radioId, captured.messageId, captured.messageIncarnation,
            captured.messageRevision, MessageStatus.PENDING.rawValue))
        val identity = advanced(captured)
        val next = captured.copy(messageRevision = identity.revision, version = checkedIncrement(captured.version),
            failed = false)
        ownWrite(dao.update(next))
        dao.insert(ChannelSendOperationEntity(captured.radioId, captured.captureRequestId, requestId,
            ChannelFailureOperation.REMAPPED_TO_PENDING.name, handle.version, next.version))
        save()
        ChannelFailureRemapResult.Remapped(handle.copy(version = next.version), receipt(next, handle.lease))
    }

    override suspend fun recordChannelFailure(
        attempt: ChannelFailureAttempt,
        requestId: UUID,
        cleanup: ChannelFailureCleanup,
    ): ChannelFailureResult = transaction("recordChannelFailure") {
        val handle = issued(attempt)
            ?: return@transaction ChannelFailureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val dao = database.channelSends()
        val captured = dao.attempt(handle.lease.token.radioId.value, handle.capture)
            ?: return@transaction ChannelFailureResult.Rejected(DirectAckRejection.MISSING)
        if (captured.leaseId != handle.lease.request) {
            return@transaction ChannelFailureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val kind = when (cleanup) {
            ChannelFailureCleanup.KEEP_PENDING -> ChannelFailureOperation.FAILED
            ChannelFailureCleanup.DELETE_CAPTURED_PENDING -> ChannelFailureOperation.FAILED_AND_PENDING_DELETED
        }
        val previous = dao.operation(captured.radioId, captured.captureRequestId, requestId)
        if (previous != null) {
            if (previous.kind != kind.name || previous.inputVersion != handle.version) {
                return@transaction ChannelFailureResult.Rejected(DirectAckRejection.CONFLICT)
            }
            return@transaction ChannelFailureResult.AlreadyApplied(receipt(captured, handle.lease))
        }
        rejection(permission(captured, handle.lease))?.let {
            return@transaction ChannelFailureResult.Rejected(it)
        }
        if (handle.version != captured.version) {
            return@transaction ChannelFailureResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        val row = currentMessage(captured)
        if (row.statusRawValue == MessageStatus.DELIVERED.rawValue) {
            return@transaction ChannelFailureResult.AlreadyDelivered(receipt(captured, handle.lease))
        }
        if (cleanup == ChannelFailureCleanup.DELETE_CAPTURED_PENDING && captured.pendingId == null) {
            return@transaction ChannelFailureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        ownWrite(dao.status(captured.radioId, captured.messageId, captured.messageIncarnation,
            captured.messageRevision, MessageStatus.FAILED.rawValue))
        var identity = advanced(captured)
        var next = captured.copy(messageRevision = identity.revision, failed = true)
        if (cleanup == ChannelFailureCleanup.DELETE_CAPTURED_PENDING) {
            ownWrite(dao.deletePending(captured.radioId, captured.messageId,
                requireNotNull(captured.pendingId), requireNotNull(captured.pendingIncarnation),
                requireNotNull(captured.pendingRevision)))
            identity = advanced(identity)
            next = next.copy(messageRevision = identity.revision, pendingDeleted = true, current = false)
        }
        ownWrite(dao.update(next))
        dao.insert(ChannelSendOperationEntity(captured.radioId, captured.captureRequestId, requestId,
            kind.name, handle.version, next.version))
        save()
        ChannelFailureResult.Applied(receipt(next, handle.lease))
    }

    override suspend fun inspectChannelFailureAttempt(
        lease: DirectAckLease,
        captureRequestId: UUID,
    ): ChannelFailureObservation = transaction("inspectChannelFailureAttempt") {
        val issued = issued(lease)
            ?: return@transaction ChannelFailureObservation.Rejected(DirectAckRejection.INVALID_HANDLE)
        val captured = database.channelSends().attempt(issued.token.radioId.value, captureRequestId)
            ?: return@transaction ChannelFailureObservation.Rejected(DirectAckRejection.MISSING)
        if (captured.leaseId != issued.request) {
            return@transaction ChannelFailureObservation.Rejected(DirectAckRejection.CONFLICT)
        }
        ChannelFailureObservation.Found(receipt(captured, issued))
    }

    private fun issued(lease: DirectAckLease): ChannelSendLease? =
        directAcks.channelSendLease(lease)?.takeIf { it.issuer == context.directAckIssuer }

    private fun issued(attempt: ChannelFailureAttempt): Attempt? =
        (attempt as? Attempt)?.takeIf { it.lease.issuer == context.directAckIssuer }

    private suspend fun RepositoryTransaction.active(lease: ChannelSendLease): Boolean {
        val row = database.directAcks().lease(lease.token.radioId.value, lease.request) ?: return false
        return row.active && row.issuer == lease.issuer && row.epoch == lease.token.epoch.value &&
            row.generation == lease.token.generation.value
    }

    private suspend fun RepositoryTransaction.captureReplay(
        captured: ChannelSendAttemptEntity,
        lease: ChannelSendLease,
    ): ChannelFailureCaptureResult {
        rejection(permission(captured, lease))?.let { return ChannelFailureCaptureResult.Rejected(it) }
        if (captured.version != 0L) return ChannelFailureCaptureResult.Rejected(DirectAckRejection.INVALIDATED)
        return ChannelFailureCaptureResult.AlreadyCaptured(
            Attempt(lease, captured.captureRequestId, 0), receipt(captured, lease),
        )
    }

    private suspend fun RepositoryTransaction.permission(
        captured: ChannelSendAttemptEntity,
        lease: ChannelSendLease,
    ): DirectAckPermission {
        validate(captured)
        if (!active(lease)) return DirectAckPermission.STALE_LEASE
        if (!captured.current || captured.pendingDeleted) return DirectAckPermission.INVALIDATED
        val row = database.messages().byId(captured.radioId, captured.messageId)
            ?: return DirectAckPermission.MISSING
        val identity = database.directAcks().incarnation(captured.radioId, captured.messageId, "message")
            ?: return DirectAckPermission.MISSING
        validate(identity)
        if (identity.incarnation != captured.messageIncarnation || identity.revision != captured.messageRevision) {
            return DirectAckPermission.INVALIDATED
        }
        if (!eligible(row)) return DirectAckPermission.INVALIDATED
        val pendingId = captured.pendingId
        if (pendingId != null) {
            val pending = database.channelSends().pending(captured.radioId, pendingId)
                ?: return DirectAckPermission.MISSING
            val pendingIdentity = database.directAcks().incarnation(captured.radioId, pendingId, "pending")
                ?: return DirectAckPermission.MISSING
            validate(pendingIdentity)
            if (pendingIdentity.incarnation != captured.pendingIncarnation ||
                pendingIdentity.revision != captured.pendingRevision || !eligible(pending, row)
            ) return DirectAckPermission.INVALIDATED
        }
        return DirectAckPermission.CURRENT
    }

    private suspend fun RepositoryTransaction.receipt(
        captured: ChannelSendAttemptEntity,
        lease: ChannelSendLease,
    ): ChannelFailureReceipt {
        val permission = permission(captured, lease)
        val current = permission == DirectAckPermission.CURRENT
        val pendingId = captured.pendingId
        val operations = database.channelSends().operations(captured.radioId, captured.captureRequestId).map {
            val kind = ChannelFailureOperation.entries.firstOrNull { kind -> kind.name == it.kind }
                ?: invalidData("Unknown channel send operation")
            if (it.inputVersion < 0 || it.outputVersion < 0 || it.outputVersion > captured.version ||
                (kind == ChannelFailureOperation.REMAPPED_TO_PENDING &&
                    (it.inputVersion == Long.MAX_VALUE || it.outputVersion != it.inputVersion + 1)) ||
                (kind != ChannelFailureOperation.REMAPPED_TO_PENDING && it.outputVersion != it.inputVersion)
            ) invalidData("Invalid channel send operation version")
            ChannelFailureOperationReceipt(it.requestId, kind)
        }.snapshot()
        return ChannelFailureReceipt(
            captured.captureRequestId, EntityKey(RadioId(captured.radioId), captured.messageId),
            if (current) currentMessage(captured).toDTO() else null, pendingId,
            if (current && pendingId != null) {
                database.channelSends().pending(captured.radioId, pendingId)?.toDTO()
                    ?: invalidData("Current channel attempt lost its pending row")
            } else null,
            permission, operations,
        )
    }

    private suspend fun RepositoryTransaction.currentMessage(capture: ChannelSendAttemptEntity): MessageEntity =
        database.messages().byId(capture.radioId, capture.messageId)
            ?: invalidData("Current channel attempt lost its message")

    private suspend fun RepositoryTransaction.identity(radio: UUID, id: UUID, kind: String): DirectAckIncarnationEntity =
        (database.directAcks().incarnation(radio, id, kind)
            ?: invalidData("Missing channel $kind incarnation")).also(::validate)

    private suspend fun RepositoryTransaction.advanced(capture: ChannelSendAttemptEntity): DirectAckIncarnationEntity =
        advanced(DirectAckIncarnationEntity(capture.radioId, capture.messageId, "message",
            capture.messageIncarnation, capture.messageRevision))

    private suspend fun RepositoryTransaction.advanced(previous: DirectAckIncarnationEntity): DirectAckIncarnationEntity {
        val next = identity(previous.radioId, previous.id, previous.kind)
        if (next.incarnation != previous.incarnation || next.revision != checkedIncrement(previous.revision)) {
            invalidData("Own channel operation did not advance exactly its original message revision")
        }
        return next
    }

    private fun eligible(row: MessageEntity): Boolean {
        if (MessageStatus.fromRawValue(row.statusRawValue) == null) invalidData("Unknown channel message status")
        return row.directionRawValue == MessageDirection.OUTGOING.rawValue && row.contactID == null &&
            row.channelIndex?.let { it in 0..255 } == true
    }

    private fun eligible(pending: PendingSendEntity, message: MessageEntity): Boolean =
        pending.radioId == message.radioId && pending.messageID == message.id &&
            pending.kindRawValue == PendingSendKind.CHANNEL.rawValue &&
            pending.contactID == null && pending.channelIndex == message.channelIndex

    private fun validate(identity: DirectAckIncarnationEntity) {
        if (identity.revision < 0 || !identity.incarnation.matches(Regex("[0-9a-f]{64}"))) {
            invalidData("Malformed channel persisted incarnation")
        }
    }

    private fun validate(capture: ChannelSendAttemptEntity) {
        val hasPending = capture.pendingId != null
        if (capture.version < 0 || capture.messageRevision < 0 ||
            !capture.messageIncarnation.matches(Regex("[0-9a-f]{64}")) ||
            (capture.origin != "CREATE" && capture.origin != "PREPARE") ||
            (capture.origin == "CREATE" && (capture.creationDigest?.matches(Regex("[0-9a-f]{64}")) != true ||
                hasPending || capture.resendTimestamp != null)) ||
            (capture.origin == "PREPARE" && capture.creationDigest != null) ||
            capture.resendTimestamp?.let { it !in 0..UInt.MAX_VALUE.toLong() } == true ||
            hasPending != (capture.pendingIncarnation != null) ||
            hasPending != (capture.pendingRevision != null) ||
            capture.pendingIncarnation?.matches(Regex("[0-9a-f]{64}")) == false ||
            capture.pendingRevision?.let { it < 0 } == true ||
            (capture.pendingDeleted && (!hasPending || capture.current))
        ) invalidData("Malformed channel capture binding")
    }

    private fun ownWrite(count: Int) {
        if (count != 1) invalidData("Own channel transaction did not mutate exactly one row")
    }

    private fun rejection(permission: DirectAckPermission): DirectAckRejection? = when (permission) {
        DirectAckPermission.CURRENT -> null
        DirectAckPermission.INVALIDATED -> DirectAckRejection.INVALIDATED
        DirectAckPermission.STALE_LEASE -> DirectAckRejection.STALE_LEASE
        DirectAckPermission.MISSING -> DirectAckRejection.MISSING
    }

    private fun invalidData(message: String): Nothing =
        throw PersistenceStoreException(PersistenceStoreError.InvalidData, IllegalStateException(message))
}
