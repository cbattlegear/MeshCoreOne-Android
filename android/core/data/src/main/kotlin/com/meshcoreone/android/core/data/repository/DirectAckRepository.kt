// AndroidOnly: WP-202 Atomic persisted direct-ACK authority, distinct from ACK ingress.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID

internal class DirectAckRepository(private val context: RoomRepositoryContext) :
    DirectAckAuthority, DirectPendingCompletionAuthority, DirectQueueAuthority, DirectMessageCreationAuthority {
    private data class Lease(val issuer: UUID, val token: SessionToken, val request: UUID) : DirectAckLease
    private data class Attempt(val lease: Lease, val request: UUID) : DirectAckAttempt
    private data class Intent(val lease: Lease, val request: UUID) : DirectAckIntent
    private data class PendingCompletion(val lease: Lease, val request: UUID) : DirectPendingCompletion
    private data class Creation(
        val lease: Lease,
        val key: EntityKey,
        val request: UUID,
        val claimRequest: UUID,
        val incarnation: String,
        val revision: Long,
    ) : DirectMessageCreation
    private data class QueueSelection(
        val lease: Lease,
        val request: UUID,
        val key: EntityKey,
        val pendingId: UUID,
        val pendingIncarnation: String,
        val messageIncarnation: String?,
        val messageRevision: Long?,
    ) : DirectQueueSelection

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

    override suspend fun activateDirectAckSession(token: SessionToken, requestId: UUID): DirectAckActivationResult =
        transaction("activateDirectAckSession") {
            val dao = database.directAcks()
            val history = dao.leases(token.radioId.value)
            val previousRequest = history.firstOrNull { it.requestId == requestId }
            if (previousRequest != null) {
                if (previousRequest.issuer != context.directAckIssuer || previousRequest.epoch != token.epoch.value ||
                    previousRequest.generation != token.generation.value
                ) return@transaction DirectAckActivationResult.Rejected(DirectAckRejection.CONFLICT)
                if (!previousRequest.active) return@transaction DirectAckActivationResult.Rejected(DirectAckRejection.STALE_LEASE)
                return@transaction DirectAckActivationResult.AlreadyActivated(Lease(context.directAckIssuer, token, requestId))
            }
            val latest = history.maxByOrNull { it.sequence }
            if (history.count { it.active } > 1) invalidData("Multiple active direct ACK leases")
            val issuerEpochs = history.filter { it.issuer == context.directAckIssuer }.map { it.epoch }.toSet()
            val sameEpoch = history.filter { it.epoch == token.epoch.value }
            if ((issuerEpochs.isNotEmpty() && latest?.issuer != context.directAckIssuer) ||
                issuerEpochs.any { it != token.epoch.value } ||
                (sameEpoch.isNotEmpty() && latest?.epoch != token.epoch.value) ||
                sameEpoch.any { it.generation >= token.generation.value }
            ) return@transaction DirectAckActivationResult.Rejected(DirectAckRejection.STALE_LEASE)
            dao.revokeRadio(token.radioId.value)
            dao.lease(DirectAckLeaseEntity(
                token.radioId.value, requestId, context.directAckIssuer, token.epoch.value, token.generation.value,
                checkedIncrement(latest?.sequence ?: 0), true,
            ))
            save()
            DirectAckActivationResult.Activated(Lease(context.directAckIssuer, token, requestId))
        }

    override suspend fun createDirectMessage(
        lease: DirectAckLease,
        message: MessageDTO,
        creationRequestId: UUID,
        claimRequestId: UUID,
    ): DirectMessageCreationResult = transaction("createDirectMessage") {
        val issued = issuedLease(lease)
            ?: return@transaction DirectMessageCreationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val key = EntityKey(message.radioId, message.id)
        if (key.radioId != issued.token.radioId) return@transaction DirectMessageCreationResult.Rejected(DirectAckRejection.CONFLICT)
        val existing = database.directAcks().operations(key.radioId.value, creationRequestId)
            .firstOrNull { it.requestId == creationRequestId }
        if (existing != null) {
            val recovered = recoverCreation(issued, key, creationRequestId, claimRequestId)
            if (recovered is DirectMessageCreationResult.AlreadyCreated &&
                database.messages().byId(key.radioId.value, key.id)?.toDTO() != message
            ) return@transaction DirectMessageCreationResult.Rejected(DirectAckRejection.CONFLICT)
            return@transaction recovered
        }
        if (!active(issued)) return@transaction DirectMessageCreationResult.Rejected(DirectAckRejection.STALE_LEASE)
        if (message.direction != MessageDirection.OUTGOING || message.channelIndex != null ||
            message.contactID == null || message.status != MessageStatus.PENDING
        ) return@transaction DirectMessageCreationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (database.messages().byId(key.radioId.value, key.id) != null) {
            return@transaction DirectMessageCreationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        database.messages().upsert(message.toEntity())
        val identity = database.directAcks().incarnation(key.radioId.value, key.id, "message")
            ?: invalidData("Inline direct creation lost its incarnation")
        val kind = "INLINE_CREATE:${issued.request}:$claimRequestId:${identity.incarnation}"
        database.directAcks().operation(DirectAckOperationEntity(
            key.radioId.value, creationRequestId, creationRequestId, kind, identity.revision, null, key.id,
        ))
        save()
        DirectMessageCreationResult.Created(Creation(
            issued, key, creationRequestId, claimRequestId, identity.incarnation, identity.revision,
        ))
    }

    override suspend fun recoverDirectMessageCreation(
        lease: DirectAckLease,
        key: EntityKey,
        creationRequestId: UUID,
        claimRequestId: UUID,
    ): DirectMessageCreationResult = transaction("recoverDirectMessageCreation") {
        val issued = issuedLease(lease)
            ?: return@transaction DirectMessageCreationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        recoverCreation(issued, key, creationRequestId, claimRequestId)
    }

    private suspend fun RepositoryTransaction.recoverCreation(
        issued: Lease, key: EntityKey, creationRequestId: UUID, claimRequestId: UUID,
        allowSameIncarnationEdits: Boolean = false,
    ): DirectMessageCreationResult {
        if (key.radioId != issued.token.radioId) return DirectMessageCreationResult.Rejected(DirectAckRejection.CONFLICT)
        val dao = database.directAcks()
        val operation = dao.operations(key.radioId.value, creationRequestId)
            .firstOrNull { it.requestId == creationRequestId }
            ?: return DirectMessageCreationResult.Rejected(DirectAckRejection.MISSING)
        val prefix = "INLINE_CREATE:${issued.request}:$claimRequestId:"
        if (!operation.kind.startsWith(prefix) || operation.pendingId != key.id) {
            return DirectMessageCreationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        if (!active(issued)) return DirectMessageCreationResult.Rejected(DirectAckRejection.STALE_LEASE)
        val incarnation = operation.kind.removePrefix(prefix)
        val revision = operation.argument ?: invalidData("Missing inline direct creation revision")
        val identity = dao.incarnation(key.radioId.value, key.id, "message")
            ?: return DirectMessageCreationResult.Rejected(DirectAckRejection.MISSING)
        if (identity.incarnation != incarnation || (!allowSameIncarnationEdits && identity.revision != revision)) {
            return DirectMessageCreationResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        return DirectMessageCreationResult.AlreadyCreated(Creation(
            issued, key, creationRequestId, claimRequestId, incarnation, revision,
        ))
    }

    override suspend fun captureCreatedDirectIntent(creation: DirectMessageCreation): DirectAckIntentResult =
        transaction("captureCreatedDirectIntent") {
            val issued = creation as? Creation
            if (issued == null || issuedLease(issued.lease) == null) {
                return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.INVALID_HANDLE)
            }
            when (val recovered = recoverCreation(issued.lease, issued.key, issued.request, issued.claimRequest)) {
                is DirectMessageCreationResult.Rejected -> return@transaction DirectAckIntentResult.Rejected(recovered.reason)
                is DirectMessageCreationResult.AlreadyCreated -> if (recovered.creation != issued) {
                    return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.CONFLICT)
                }
                is DirectMessageCreationResult.Created -> invalidData("Unexpected inline creation recovery")
            }
            captureIntent(issued.lease, issued.key, issued.claimRequest, DirectAckMode.SEND, null)
        }

    override suspend fun captureCreatedDirectRetryIntent(
        creation: DirectMessageCreation,
        mode: DirectAckMode,
    ): DirectAckIntentResult = transaction("captureCreatedDirectRetryIntent") {
        val issued = creation as? Creation
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        when (val recovered = recoverCreation(
            issued.lease, issued.key, issued.request, issued.claimRequest, allowSameIncarnationEdits = true,
        )) {
            is DirectMessageCreationResult.Rejected -> return@transaction DirectAckIntentResult.Rejected(recovered.reason)
            is DirectMessageCreationResult.AlreadyCreated -> if (recovered.creation != issued) {
                return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.CONFLICT)
            }
            is DirectMessageCreationResult.Created -> invalidData("Unexpected inline creation recovery")
        }
        captureIntent(issued.lease, issued.key, issued.claimRequest, mode, null)
    }

    override suspend fun failDirectMessageCreation(
        creation: DirectMessageCreation,
        failureRequestId: UUID,
    ): DirectMessageCreationFailureResult = failCreation(creation, failureRequestId, allowSameIncarnationEdits = false)

    override suspend fun failCreatedDirectRetryMessage(
        creation: DirectMessageCreation,
        failureRequestId: UUID,
    ): DirectMessageCreationFailureResult = failCreation(creation, failureRequestId, allowSameIncarnationEdits = true)

    private suspend fun failCreation(
        creation: DirectMessageCreation,
        failureRequestId: UUID,
        allowSameIncarnationEdits: Boolean,
    ): DirectMessageCreationFailureResult = transaction("failDirectMessageCreation") {
        val issued = creation as? Creation
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectMessageCreationFailureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val radio = issued.key.radioId.value
        val dao = database.directAcks()
        val kind = if (allowSameIncarnationEdits) "INLINE_RETRY_CREATE_FAILED" else "INLINE_CREATE_FAILED"
        val previous = dao.operations(radio, issued.request).firstOrNull { it.requestId == failureRequestId }
        if (previous != null) {
            if (previous.kind != kind || previous.pendingId != issued.key.id) {
                return@transaction DirectMessageCreationFailureResult.Rejected(DirectAckRejection.CONFLICT)
            }
            val status = previous.argument?.let { MessageStatus.fromRawValue(it) }
                ?: invalidData("Missing inline direct failure status")
            val changed = when (previous.maximum) {
                0L -> false
                1L -> true
                else -> invalidData("Invalid inline direct failure result")
            }
            return@transaction DirectMessageCreationFailureResult.AlreadyApplied(
                DirectMessageCreationFailureReceipt(status, changed),
            )
        }
        val recovered = recoverCreation(
            issued.lease, issued.key, issued.request, issued.claimRequest, allowSameIncarnationEdits,
        )
        if (recovered is DirectMessageCreationResult.Rejected) {
            return@transaction DirectMessageCreationFailureResult.Rejected(recovered.reason)
        }
        if (recovered !is DirectMessageCreationResult.AlreadyCreated || recovered.creation != issued) {
            return@transaction DirectMessageCreationFailureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        if (dao.currentClaims(radio, issued.key.id) != 0 ||
            dao.otherCurrentIntents(radio, issued.key.id, issued.claimRequest) != 0
        ) return@transaction DirectMessageCreationFailureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val row = database.messages().byId(radio, issued.key.id)
            ?: return@transaction DirectMessageCreationFailureResult.Rejected(DirectAckRejection.MISSING)
        val status = MessageStatus.fromRawValue(row.statusRawValue) ?: invalidData("Unknown inline direct status")
        val changed = status != MessageStatus.FAILED && status != MessageStatus.DELIVERED
        if (changed) {
            val before = dao.incarnation(radio, issued.key.id, "message")
                ?: invalidData("Inline direct failure lost its incarnation")
            ownWrite(database.messages().setStatusUnlessDelivered(radio, issued.key.id, MessageStatus.FAILED.rawValue))
            val updatedIdentity = dao.incarnation(radio, issued.key.id, "message")
                ?: invalidData("Inline direct failure lost its message")
            if (updatedIdentity.incarnation != issued.incarnation ||
                updatedIdentity.revision != checkedIncrement(before.revision)
            ) invalidData("Independent mutation inside inline direct failure")
        }
        val result = DirectMessageCreationFailureReceipt(if (changed) MessageStatus.FAILED else status, changed)
        dao.operation(DirectAckOperationEntity(
            radio, issued.request, failureRequestId, kind, result.status.rawValue,
            if (changed) 1 else 0, issued.key.id,
        ))
        save()
        DirectMessageCreationFailureResult.Applied(result)
    }

    override suspend fun resolveCreatedDirectWithoutDispatch(
        creation: DirectMessageCreation,
        requestId: UUID,
        resolveToSent: Boolean,
        attempt: DirectAckAttempt?,
    ): DirectMessageCreationResolutionResult = transaction("resolveCreatedDirectWithoutDispatch") {
        val issued = creation as? Creation
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val expected = attempt as? Attempt
        if (attempt != null && (expected == null || expected.lease != issued.lease)) {
            return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        if (!resolveToSent && expected != null) {
            return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.CONFLICT)
        }
        when (val recovered = recoverCreation(
            issued.lease, issued.key, issued.request, issued.claimRequest, allowSameIncarnationEdits = true,
        )) {
            is DirectMessageCreationResult.Rejected ->
                return@transaction DirectMessageCreationResolutionResult.Rejected(recovered.reason)
            is DirectMessageCreationResult.AlreadyCreated -> if (recovered.creation != issued) {
                return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.CONFLICT)
            }
            is DirectMessageCreationResult.Created -> invalidData("Unexpected inline creation recovery")
        }
        val radio = issued.key.radioId.value
        val dao = database.directAcks()
        val kind = "INLINE_ZERO:${if (resolveToSent) 1 else 0}:${expected?.request ?: "NONE"}"
        val previous = dao.operations(radio, issued.request).firstOrNull { it.requestId == requestId }
        var row = database.messages().byId(radio, issued.key.id)
            ?: return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.MISSING)
        if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null || row.contactID == null) {
            return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (previous != null) {
            if (previous.kind != kind || previous.pendingId != issued.key.id) {
                return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.CONFLICT)
            }
            val changed = when (previous.maximum) {
                0L -> false
                1L -> true
                else -> invalidData("Invalid inline zero-attempt resolution")
            }
            return@transaction DirectMessageCreationResolutionResult.AlreadyApplied(
                DirectMessageCreationResolutionReceipt(row.toDTO(), changed),
            )
        }
        val status = MessageStatus.fromRawValue(row.statusRawValue) ?: invalidData("Unknown inline direct status")
        val changed = resolveToSent && status != MessageStatus.FAILED && status != MessageStatus.DELIVERED
        if (changed) {
            val before = dao.incarnation(radio, issued.key.id, "message")
                ?: invalidData("Inline zero-attempt resolution lost its incarnation")
            val claim = if (expected != null) {
                val found = dao.claim(radio, expected.request)
                    ?: return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.MISSING)
                if (found.leaseId != issued.lease.request || found.messageId != issued.key.id ||
                    found.incarnation != issued.incarnation
                ) return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.CONFLICT)
                rejection(permission(found, expected.lease))?.let {
                    return@transaction DirectMessageCreationResolutionResult.Rejected(it)
                }
                if (hasCompetingIntent(found)) {
                    return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
                found
            } else {
                if (hasCompetingQueueAuthority(radio, issued.key.id)) {
                    return@transaction DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
                null
            }
            ownWrite(database.messages().clearRetryingToSent(radio, issued.key.id))
            if (claim != null) renew(claim, revisionAdvances = 1)
            else {
                val updated = dao.incarnation(radio, issued.key.id, "message")
                    ?: invalidData("Inline zero-attempt resolution lost its message")
                if (updated.incarnation != issued.incarnation || updated.revision != checkedIncrement(before.revision)) {
                    invalidData("Independent mutation inside inline zero-attempt resolution")
                }
            }
            row = database.messages().byId(radio, issued.key.id)
                ?: invalidData("Inline zero-attempt resolution lost its row")
        }
        dao.operation(DirectAckOperationEntity(
            radio, issued.request, requestId, kind, null, if (changed) 1 else 0, issued.key.id,
        ))
        save()
        DirectMessageCreationResolutionResult.Applied(DirectMessageCreationResolutionReceipt(row.toDTO(), changed))
    }

    override suspend fun captureDirectAckIntent(
        lease: DirectAckLease,
        key: EntityKey,
        requestId: UUID,
        mode: DirectAckMode,
        pendingSendId: UUID?,
    ): DirectAckIntentResult = transaction("captureDirectAckIntent") {
        val issued = issuedLease(lease) ?: return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        captureIntent(issued, key, requestId, mode, pendingSendId)
    }

    private suspend fun RepositoryTransaction.captureIntent(
        issued: Lease,
        key: EntityKey,
        requestId: UUID,
        mode: DirectAckMode,
        pendingSendId: UUID?,
        selection: QueueSelection? = null,
    ): DirectAckIntentResult {
        if (key.radioId != issued.token.radioId) return DirectAckIntentResult.Rejected(DirectAckRejection.CONFLICT)
        val dao = database.directAcks()
        val existing = dao.intent(key.radioId.value, requestId)
        if (existing != null) {
            if (existing.leaseId != issued.request || existing.messageId != key.id ||
                existing.mode != mode.name || existing.pendingId != pendingSendId ||
                selection != null && (existing.incarnation != selection.messageIncarnation ||
                    existing.pendingIncarnation != selection.pendingIncarnation)
            ) return DirectAckIntentResult.Rejected(DirectAckRejection.CONFLICT)
            rejection(intentPermission(existing, issued))?.let { return DirectAckIntentResult.Rejected(it) }
            return DirectAckIntentResult.AlreadyCaptured(Intent(issued, requestId), intentReceipt(existing, issued))
        }
        if (!active(issued)) return DirectAckIntentResult.Rejected(DirectAckRejection.STALE_LEASE)
        val row = database.messages().byId(key.radioId.value, key.id)
            ?: return DirectAckIntentResult.Rejected(DirectAckRejection.MISSING)
        if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null ||
            (mode == DirectAckMode.SEND && row.statusRawValue in setOf(3L, 4L))
        ) return DirectAckIntentResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (MessageStatus.fromRawValue(row.statusRawValue) == null) invalidData("Unknown direct message status")
        val incarnation = dao.incarnation(key.radioId.value, key.id, "message")
            ?: invalidData("Missing persisted message incarnation")
        val pending = pendingSendId?.let { id ->
            database.pendingSends().forMessage(key.radioId.value, key.id).firstOrNull { it.id == id }
                ?: return DirectAckIntentResult.Rejected(DirectAckRejection.MISSING)
        }
        if (pending != null && pending.kindRawValue != PendingSendKind.DM.rawValue) {
            return DirectAckIntentResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val pendingIdentity = pending?.let {
            dao.incarnation(it.radioId, it.id, "pending") ?: invalidData("Missing pending incarnation")
        }
        val captured = DirectAckIntentEntity(
            key.radioId.value, requestId, issued.request, key.id, pendingSendId, mode.name,
            incarnation.incarnation, incarnation.revision, pendingIdentity?.incarnation, pendingIdentity?.revision, true, row, pending,
        )
        dao.intent(captured)
        save()
        return DirectAckIntentResult.Captured(Intent(issued, requestId), intentReceipt(captured, issued))
    }

    override suspend fun beginDirectAckAttempt(
        intent: DirectAckIntent,
        firstWireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckBeginResult = transaction("beginDirectAckAttempt") {
        val issued = intent as? Intent
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val dao = database.directAcks()
        val captured = dao.intent(issued.lease.token.radioId.value, issued.request)
            ?: return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.MISSING)
        if (captured.leaseId != issued.lease.request) return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
        val existing = dao.claim(captured.radioId, captured.requestId)
        if (existing != null) {
            val first = dao.wires(captured.radioId, captured.requestId).firstOrNull { it.requestId == firstWireRequestId }
            if (existing.firstWireId != firstWireRequestId || first?.predictedCode != predictedAckCode.toLong()) {
                return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
            }
            rejection(permission(existing, issued.lease))?.let { return@transaction DirectAckBeginResult.Rejected(it) }
            return@transaction DirectAckBeginResult.AlreadyBegun(Attempt(issued.lease, issued.request), receipt(existing, issued.lease))
        }
        rejection(intentPermission(captured, issued.lease))?.let { return@transaction DirectAckBeginResult.Rejected(it) }
        if (captured.pendingId != null &&
            dao.unresolvedQueuedClaims(captured.radioId, captured.messageId, captured.pendingId) != 0
        ) {
            return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        val row = captured.message ?: invalidData("Current direct ACK intent has no message")
        dao.invalidateMessage(captured.radioId, captured.messageId)
        dao.invalidateIntents(captured.radioId, captured.messageId)
        val claim = DirectAckClaimEntity(
            captured.requestId, issued.lease.request, firstWireRequestId, captured.incarnation, captured.revision,
            DirectAckPhase.REGISTERED.name, captured.mode, row.statusRawValue, true, captured.pendingId,
            captured.pendingIncarnation, captured.radioId, captured.messageId, row,
        )
        dao.claim(claim)
        dao.wire(DirectAckWireEntity(captured.radioId, captured.requestId, firstWireRequestId, predictedAckCode.toLong(), null))
        save()
        DirectAckBeginResult.Begun(Attempt(issued.lease, issued.request), receipt(claim, issued.lease))
    }

    override suspend fun inspectDirectAckIntent(lease: DirectAckLease, requestId: UUID): DirectAckIntentObservation =
        transaction("inspectDirectAckIntent") {
            val issued = issuedLease(lease)
                ?: return@transaction DirectAckIntentObservation.Rejected(DirectAckRejection.INVALID_HANDLE)
            val capture = database.directAcks().intent(issued.token.radioId.value, requestId)
                ?: return@transaction DirectAckIntentObservation.Rejected(DirectAckRejection.MISSING)
            if (capture.leaseId != issued.request) return@transaction DirectAckIntentObservation.Rejected(DirectAckRejection.CONFLICT)
            DirectAckIntentObservation.Found(intentReceipt(capture, issued))
        }

    override suspend fun retireDirectAckIntent(lease: DirectAckLease, requestId: UUID): DirectAckIntentRetirement =
        transaction("retireDirectAckIntent") {
            val issued = issuedLease(lease)
            if (issued == null) {
                return@transaction DirectAckIntentRetirement.Rejected(DirectAckRejection.INVALID_HANDLE)
            }
            val dao = database.directAcks()
            val capture = dao.intent(issued.token.radioId.value, requestId)
                ?: return@transaction DirectAckIntentRetirement.NotCurrent
            if (capture.leaseId != issued.request) {
                return@transaction DirectAckIntentRetirement.Rejected(DirectAckRejection.CONFLICT)
            }
            if (dao.claim(capture.radioId, capture.requestId) != null) {
                return@transaction DirectAckIntentRetirement.AttemptBegun
            }
            if (intentPermission(capture, issued) != DirectAckPermission.CURRENT) {
                return@transaction DirectAckIntentRetirement.NotCurrent
            }
            dao.intent(capture.copy(current = false, message = null, pending = null))
            save()
            DirectAckIntentRetirement.Retired
        }

    private suspend fun RepositoryTransaction.intentPermission(capture: DirectAckIntentEntity, lease: Lease): DirectAckPermission {
        if (!active(lease)) return DirectAckPermission.STALE_LEASE
        val dao = database.directAcks()
        val identity = dao.incarnation(capture.radioId, capture.messageId, "message") ?: return DirectAckPermission.MISSING
        val pendingId = capture.pendingId
        val pendingIdentity = pendingId?.let { dao.incarnation(capture.radioId, it, "pending") }
        if (!capture.current || capture.message == null || identity.incarnation != capture.incarnation ||
            identity.revision != capture.revision ||
            (pendingId != null && (capture.pending == null ||
                pendingIdentity == null || pendingIdentity.incarnation != capture.pendingIncarnation ||
                pendingIdentity.revision != capture.pendingRevision))
        ) return DirectAckPermission.INVALIDATED
        return DirectAckPermission.CURRENT
    }

    private suspend fun RepositoryTransaction.intentReceipt(capture: DirectAckIntentEntity, lease: Lease) = DirectAckIntentReceipt(
        EntityKey(RadioId(capture.radioId), capture.messageId), capture.requestId,
        DirectAckMode.entries.firstOrNull { it.name == capture.mode } ?: invalidData("Unknown intent mode"),
        capture.message?.toDTO(), capture.pendingId, capture.pending?.toDTO(), intentPermission(capture, lease),
    )

    override suspend fun registerDirectAckDispatch(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckMutationResult = mutate("registerDirectAckDispatch", attempt) { issued, claim ->
        val dao = database.directAcks()
        if (dao.operations(claim.radioId, claim.requestId).any { it.kind.startsWith("DEFINITELY_UNSENT:") }) {
            return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val existing = dao.wires(claim.radioId, claim.requestId).firstOrNull { it.requestId == wireRequestId }
        if (existing != null) return@mutate if (existing.predictedCode == predictedAckCode.toLong()) {
            DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        } else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        if (hasCompetingIntent(claim)) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (claim.phase == DirectAckPhase.DELIVERED.name) {
            return@mutate DirectAckMutationResult.AlreadyDelivered(receipt(claim, issued.lease))
        }
        if (claim.phase == DirectAckPhase.FAILED.name) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        dao.wire(DirectAckWireEntity(claim.radioId, claim.requestId, wireRequestId, predictedAckCode.toLong(), null))
        save()
        DirectAckMutationResult.Applied(receipt(claim, issued.lease))
    }

    override suspend fun recordDirectSendAccepted(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        ackCode: UInt,
    ): DirectAckMutationResult = mutate("recordDirectSendAccepted", attempt) { issued, claim ->
        val dao = database.directAcks()
        val wire = dao.wires(claim.radioId, claim.requestId).firstOrNull { it.requestId == wireRequestId }
            ?: return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.MISSING)
        if (wire.acceptedCode != null) return@mutate if (wire.acceptedCode == ackCode.toLong()) {
            DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        } else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        if (dao.operations(claim.radioId, claim.requestId).any { it.kind == "DEFINITELY_UNSENT:$wireRequestId" }) {
            return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        dao.wire(wire.copy(acceptedCode = ackCode.toLong()))
        val phase = if (claim.phase == DirectAckPhase.REGISTERED.name) DirectAckPhase.ACCEPTED.name else claim.phase
        val updated = renew(claim, phase)
        save()
        DirectAckMutationResult.Applied(receipt(updated, issued.lease))
    }

    override suspend fun retireDefinitelyUnsentQueuedAttempt(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        requestId: UUID,
    ): DirectAckMutationResult = mutate("retireDefinitelyUnsentQueuedAttempt", attempt) { issued, claim ->
        val dao = database.directAcks()
        val operation = DirectAckOperationEntity(
            claim.radioId, claim.requestId, requestId, "DEFINITELY_UNSENT:$wireRequestId", null, null, claim.pendingId,
        )
        replay(claim, issued, operation)?.let { return@mutate it }
        deny(claim, issued)?.let { return@mutate it }
        if (hasCompetingIntent(claim)) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val wires = dao.wires(claim.radioId, claim.requestId)
        if (claim.pendingId == null || claim.phase != DirectAckPhase.REGISTERED.name ||
            wires.size != 1 || wires.single().requestId != wireRequestId || wires.single().acceptedCode != null ||
            dao.delivery(claim.radioId, claim.requestId) != null
        ) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        dao.operation(operation)
        val retired = claim.copy(current = false)
        dao.claim(retired)
        save()
        DirectAckMutationResult.Applied(receipt(retired, issued.lease))
    }

    override suspend fun recordDefinitelyUnsentInlineWire(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        requestId: UUID,
    ): DirectAckMutationResult = mutate("recordDefinitelyUnsentInlineWire", attempt) { issued, claim ->
        val dao = database.directAcks()
        val operation = DirectAckOperationEntity(
            claim.radioId, claim.requestId, requestId, "DEFINITELY_UNSENT:$wireRequestId", null, null, null,
        )
        replay(claim, issued, operation)?.let { return@mutate it }
        deny(claim, issued)?.let { return@mutate it }
        if (hasCompetingIntent(claim)) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val wires = dao.wires(claim.radioId, claim.requestId)
        if (claim.pendingId != null || claim.phase != DirectAckPhase.REGISTERED.name ||
            wires.size != 1 || wires.single().requestId != wireRequestId || wires.single().acceptedCode != null ||
            dao.delivery(claim.radioId, claim.requestId) != null ||
            dao.operations(claim.radioId, claim.requestId).any { it.kind.startsWith("DEFINITELY_UNSENT:") }
        ) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        dao.operation(operation)
        save()
        DirectAckMutationResult.Applied(receipt(claim, issued.lease))
    }

    override suspend fun recordDirectFailure(attempt: DirectAckAttempt): DirectAckMutationResult =
        mutate("recordDirectFailure", attempt) { issued, claim ->
            if (claim.phase == DirectAckPhase.FAILED.name) {
                return@mutate DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
            }
            if (claim.phase == DirectAckPhase.DELIVERED.name || claim.initialStatus == 3L) {
                return@mutate DirectAckMutationResult.AlreadyDelivered(receipt(claim, issued.lease))
            }
            deny(claim, issued)?.let { return@mutate it }
            if (hasCompetingIntent(claim)) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
            ownWrite(database.messages().setStatusUnlessDelivered(claim.radioId, claim.messageId, 4))
            val updated = renew(claim, DirectAckPhase.FAILED.name, 1)
            save()
            DirectAckMutationResult.Applied(receipt(updated, issued.lease))
        }

    override suspend fun settleDirectAck(
        attempt: DirectAckAttempt,
        ackCode: UInt,
        roundTripTime: UInt?,
    ): DirectAckMutationResult = mutate("settleDirectAck", attempt) { issued, claim ->
        val dao = database.directAcks()
        val previous = dao.delivery(claim.radioId, claim.requestId)
        if (previous != null) return@mutate if (previous.ackCode == ackCode.toLong() &&
            previous.roundTripTime == roundTripTime?.toLong()
        ) DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        if (hasCompetingIntent(claim)) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val retiredWires = dao.operations(claim.radioId, claim.requestId)
            .filter { it.kind.startsWith("DEFINITELY_UNSENT:") }
            .map { it.kind.removePrefix("DEFINITELY_UNSENT:") }
            .toSet()
        if (dao.wires(claim.radioId, claim.requestId).none {
                it.requestId.toString() !in retiredWires &&
                    (it.predictedCode == ackCode.toLong() || it.acceptedCode == ackCode.toLong())
            }
        ) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (dao.collidingPriorWires(
                claim.radioId, claim.messageId, claim.incarnation, claim.requestId, ackCode.toLong(),
            ) != 0
        ) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.AMBIGUOUS_ACK)
        ownWrite(dao.deliver(claim.radioId, claim.messageId, ackCode.toLong(), roundTripTime?.toLong()))
        val updated = renew(claim, DirectAckPhase.DELIVERED.name, 1)
        dao.delivery(DirectAckDeliveryEntity(
            claim.requestId, ackCode.toLong(), roundTripTime?.toLong(), claim.radioId, claim.messageId, updated.snapshot,
        ))
        save()
        DirectAckMutationResult.Applied(receipt(updated, issued.lease))
    }

    override suspend fun applyDirectAckBookkeeping(
        attempt: DirectAckAttempt,
        requestId: UUID,
        change: DirectAckBookkeeping,
    ): DirectAckMutationResult = mutate("applyDirectAckBookkeeping", attempt) { issued, claim ->
        val operation = operation(claim, requestId, change)
        replay(claim, issued, operation)?.let { return@mutate it }
        deny(claim, issued)?.let { return@mutate it }
        if (hasCompetingIntent(claim)) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val row = claim.snapshot ?: invalidData("Current claim has no message")
        if (change != DirectAckBookkeeping.ResendCountIncrement && row.statusRawValue == 3L) {
            return@mutate DirectAckMutationResult.AlreadyDelivered(receipt(claim, issued.lease))
        }
        when (change) {
            is DirectAckBookkeeping.SendCompleted -> {
                if (claim.mode != DirectAckMode.SEND.name ||
                    row.statusRawValue != MessageStatus.PENDING.rawValue
                ) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                val wire = database.directAcks().wires(claim.radioId, claim.requestId)
                    .firstOrNull { it.requestId == change.wireRequestId }
                    ?: return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.MISSING)
                val acceptedCode = wire.acceptedCode
                    ?: return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                ownWrite(database.messages().setAck(
                    row.radioId, row.id, acceptedCode, MessageStatus.SENT.rawValue, null,
                ))
            }
            is DirectAckBookkeeping.RetryStarted -> {
                if (change.retryAttempt < 0 || change.maxRetryAttempts < 0) {
                    return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
                if (row.statusRawValue == 4L) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                ownWrite(database.messages().setRetryStatus(row.radioId, row.id, 5, change.retryAttempt, change.maxRetryAttempts))
            }
            DirectAckBookkeeping.RetryExhausted -> {
                if (row.statusRawValue == 4L) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                ownWrite(database.messages().clearRetryingToSent(row.radioId, row.id))
            }
            DirectAckBookkeeping.ReturnToPending ->
                ownWrite(database.messages().setStatusUnlessDelivered(row.radioId, row.id, MessageStatus.PENDING.rawValue))
            DirectAckBookkeeping.ResendCountIncrement -> {
                if (claim.mode != DirectAckMode.RESEND.name) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                database.messages().upsert(row.copy(sendCount = checkedIncrement(row.sendCount)))
            }
        }
        val updated = renew(claim, revisionAdvances = 1)
        database.directAcks().operation(operation)
        save()
        DirectAckMutationResult.Applied(receipt(updated, issued.lease))
    }

    override suspend fun completeDirectPending(
        attempt: DirectAckAttempt,
        pendingSendId: UUID,
        requestId: UUID,
    ): DirectAckMutationResult = mutate("completeDirectPending", attempt) { issued, claim ->
        val operation = DirectAckOperationEntity(
            claim.radioId, claim.requestId, requestId, "PENDING", null, null, pendingSendId,
        )
        replay(claim, issued, operation)?.let { return@mutate it }
        if (claim.pendingId != pendingSendId) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        if (hasCompetingIntent(claim)) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (database.directAcks().operations(claim.radioId, claim.requestId).any { it.kind == "PENDING" }) {
            return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        if (database.directAcks().wires(claim.radioId, claim.requestId).none { it.acceptedCode != null } &&
            database.directAcks().delivery(claim.radioId, claim.requestId) == null
        ) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        ownWrite(database.pendingSends().delete(claim.radioId, pendingSendId))
        val updated = renew(claim, revisionAdvances = 1)
        database.directAcks().operation(operation)
        save()
        DirectAckMutationResult.Applied(receipt(updated, issued.lease))
    }

    override suspend fun inspectDirectAckAttempt(lease: DirectAckLease, requestId: UUID): DirectAckObservation =
        transaction("inspectDirectAckAttempt") {
            val issued = issuedLease(lease) ?: return@transaction DirectAckObservation.Rejected(DirectAckRejection.INVALID_HANDLE)
            val claim = database.directAcks().claim(issued.token.radioId.value, requestId)
                ?: return@transaction DirectAckObservation.Rejected(DirectAckRejection.MISSING)
            if (claim.leaseId != issued.request) return@transaction DirectAckObservation.Rejected(DirectAckRejection.CONFLICT)
            DirectAckObservation.Found(receipt(claim, issued))
        }

    override suspend fun captureDirectPendingCompletion(
        lease: DirectAckLease,
        key: EntityKey,
        pendingSendId: UUID,
        captureRequestId: UUID,
        reason: DirectPendingCompletionReason,
    ): DirectPendingCaptureResult = transaction("captureDirectPendingCompletion") {
        val issued = issuedLease(lease)
            ?: return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        capturePendingCompletion(issued, key, pendingSendId, captureRequestId, reason)
    }

    private suspend fun RepositoryTransaction.capturePendingCompletion(
        issued: Lease,
        key: EntityKey,
        pendingSendId: UUID,
        captureRequestId: UUID,
        reason: DirectPendingCompletionReason,
        selection: QueueSelection? = null,
    ): DirectPendingCaptureResult {
        if (key.radioId != issued.token.radioId) return DirectPendingCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        val dao = database.directAcks()
        val reasonName = when (reason) {
            DirectPendingCompletionReason.AlreadyDeliveredSend -> "DELIVERED"
            DirectPendingCompletionReason.DeliveredQueuedRecovery -> "RECOVERED_DELIVERED"
            is DirectPendingCompletionReason.ZeroAttempts -> "ZERO"
        }
        if (selection != null && reason == DirectPendingCompletionReason.DeliveredQueuedRecovery) {
            return DirectPendingCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val storedReason = if (selection == null) reasonName else "QUEUE_$reasonName"
        val maximum = (reason as? DirectPendingCompletionReason.ZeroAttempts)?.configuredMaximumAttempts
        val existing = dao.pendingCompletion(key.radioId.value, captureRequestId)
        if (existing != null) {
            if (existing.leaseId != issued.request || existing.messageId != key.id ||
                existing.pendingId != pendingSendId || existing.reason != storedReason || existing.maximumAttempts != maximum ||
                selection != null && (existing.messageIncarnation != selection.messageIncarnation ||
                    existing.pendingIncarnation != selection.pendingIncarnation)
            ) return DirectPendingCaptureResult.Rejected(DirectAckRejection.CONFLICT)
            rejection(pendingPermission(existing, issued))?.let {
                return DirectPendingCaptureResult.Rejected(it)
            }
            return DirectPendingCaptureResult.AlreadyCaptured(
                PendingCompletion(issued, captureRequestId), pendingReceipt(existing, issued),
            )
        }
        if (!active(issued)) return DirectPendingCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        if (selection != null && hasCompetingQueueAuthority(key.radioId.value, key.id, pendingId = pendingSendId)) {
            return DirectPendingCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val row = database.messages().byId(key.radioId.value, key.id)
            ?: return DirectPendingCaptureResult.Rejected(DirectAckRejection.MISSING)
        val pending = database.pendingSends().forMessage(key.radioId.value, key.id).firstOrNull { it.id == pendingSendId }
            ?: return DirectPendingCaptureResult.Rejected(DirectAckRejection.MISSING)
        if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null ||
            pending.kindRawValue != PendingSendKind.DM.rawValue ||
            (reasonName in setOf("DELIVERED", "RECOVERED_DELIVERED") &&
                (row.statusRawValue != MessageStatus.DELIVERED.rawValue || pending.isResend)) ||
            (reasonName == "ZERO" && maximum != 0L)
        ) return DirectPendingCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (reason == DirectPendingCompletionReason.DeliveredQueuedRecovery &&
            recoverableDeliveredQueueClaim(key.radioId.value, key.id, pendingSendId) == null
        ) return DirectPendingCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val messageIdentity = dao.incarnation(key.radioId.value, key.id, "message")
            ?: invalidData("Missing no-dispatch message incarnation")
        val pendingIdentity = dao.incarnation(key.radioId.value, pendingSendId, "pending")
            ?: invalidData("Missing no-dispatch pending incarnation")
        val capture = DirectPendingCompletionEntity(
            captureRequestId, issued.request, messageIdentity.incarnation, messageIdentity.revision,
            pendingIdentity.incarnation, storedReason, maximum, true, null, key.radioId.value, key.id, pendingSendId, row, pending,
        )
        dao.pendingCompletion(capture)
        save()
        return DirectPendingCaptureResult.Captured(PendingCompletion(issued, captureRequestId), pendingReceipt(capture, issued))
    }

    override suspend fun completeDirectPendingWithoutDispatch(
        completion: DirectPendingCompletion,
        completionRequestId: UUID,
    ): DirectPendingCompletionResult = transaction("completeDirectPendingWithoutDispatch") {
        val issued = completion as? PendingCompletion
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectPendingCompletionResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val dao = database.directAcks()
        val capture = dao.pendingCompletion(issued.lease.token.radioId.value, issued.request)
            ?: return@transaction DirectPendingCompletionResult.Rejected(DirectAckRejection.MISSING)
        if (capture.leaseId != issued.lease.request) {
            return@transaction DirectPendingCompletionResult.Rejected(DirectAckRejection.CONFLICT)
        }
        capture.completedRequestId?.let {
            return@transaction if (it == completionRequestId) {
                DirectPendingCompletionResult.AlreadyApplied(pendingReceipt(capture, issued.lease))
            } else DirectPendingCompletionResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(pendingPermission(capture, issued.lease))?.let {
            return@transaction DirectPendingCompletionResult.Rejected(it)
        }
        if (capture.reason.startsWith("QUEUE_") &&
            hasCompetingQueueAuthority(capture.radioId, capture.messageId, pendingId = capture.pendingId)
        ) {
            return@transaction DirectPendingCompletionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val recoveredClaim = if (capture.reason == "RECOVERED_DELIVERED") {
            recoverableDeliveredQueueClaim(capture.radioId, capture.messageId, capture.pendingId)
                ?: return@transaction DirectPendingCompletionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        } else null
        if (recoveredClaim != null && dao.operations(capture.radioId, recoveredClaim.requestId)
                .any { it.requestId == completionRequestId }
        ) return@transaction DirectPendingCompletionResult.Rejected(DirectAckRejection.CONFLICT)
        ownWrite(database.pendingSends().delete(capture.radioId, capture.pendingId))
        val identity = dao.incarnation(capture.radioId, capture.messageId, "message")
            ?: invalidData("No-dispatch completion lost its message")
        if (identity.incarnation != capture.messageIncarnation ||
            identity.revision != checkedIncrement(capture.messageRevision)
        ) invalidData("Independent mutation inside no-dispatch completion")
        if (recoveredClaim != null) {
            dao.operation(DirectAckOperationEntity(
                capture.radioId, recoveredClaim.requestId, completionRequestId,
                "PENDING", null, null, capture.pendingId,
            ))
        }
        val committed = capture.copy(completedRequestId = completionRequestId, current = false, pending = null)
        dao.pendingCompletion(committed)
        save()
        DirectPendingCompletionResult.Applied(pendingReceipt(committed, issued.lease))
    }

    override suspend fun inspectDirectPendingCompletion(
        lease: DirectAckLease,
        captureRequestId: UUID,
    ): DirectPendingCompletionObservation = transaction("inspectDirectPendingCompletion") {
        val issued = issuedLease(lease)
            ?: return@transaction DirectPendingCompletionObservation.Rejected(DirectAckRejection.INVALID_HANDLE)
        val capture = database.directAcks().pendingCompletion(issued.token.radioId.value, captureRequestId)
            ?: return@transaction DirectPendingCompletionObservation.Rejected(DirectAckRejection.MISSING)
        if (capture.leaseId != issued.request) {
            return@transaction DirectPendingCompletionObservation.Rejected(DirectAckRejection.CONFLICT)
        }
        DirectPendingCompletionObservation.Found(pendingReceipt(capture, issued))
    }

    private suspend fun RepositoryTransaction.pendingPermission(
        capture: DirectPendingCompletionEntity,
        lease: Lease,
    ): DirectAckPermission {
        if (!active(lease)) return DirectAckPermission.STALE_LEASE
        val dao = database.directAcks()
        val message = dao.incarnation(capture.radioId, capture.messageId, "message")
            ?: return DirectAckPermission.MISSING
        if (!capture.current || capture.message == null || capture.pending == null || message.incarnation != capture.messageIncarnation ||
            message.revision != capture.messageRevision ||
            dao.incarnation(capture.radioId, capture.pendingId, "pending")?.incarnation != capture.pendingIncarnation
        ) return DirectAckPermission.INVALIDATED
        return DirectAckPermission.CURRENT
    }

    private suspend fun RepositoryTransaction.pendingReceipt(
        capture: DirectPendingCompletionEntity,
        lease: Lease,
    ) = DirectPendingCompletionReceipt(
        capture.captureRequestId, EntityKey(RadioId(capture.radioId), capture.messageId),
        when (capture.reason) {
            "DELIVERED", "QUEUE_DELIVERED" -> DirectPendingCompletionReason.AlreadyDeliveredSend
            "RECOVERED_DELIVERED" -> DirectPendingCompletionReason.DeliveredQueuedRecovery
            "ZERO", "QUEUE_ZERO" -> DirectPendingCompletionReason.ZeroAttempts(
                capture.maximumAttempts ?: invalidData("Missing no-dispatch maximum attempts"),
            )
            else -> invalidData("Unknown no-dispatch completion reason")
        },
        capture.message?.toDTO(), capture.pendingId, capture.pending?.toDTO(), capture.completedRequestId,
        when (pendingPermission(capture, lease)) {
            DirectAckPermission.CURRENT -> DirectPendingCompletionPermission.CURRENT
            DirectAckPermission.INVALIDATED -> DirectPendingCompletionPermission.INVALIDATED
            DirectAckPermission.STALE_LEASE -> DirectPendingCompletionPermission.STALE_LEASE
            DirectAckPermission.MISSING -> DirectPendingCompletionPermission.MISSING
        },
    )

    override suspend fun captureDirectQueueSelection(
        lease: DirectAckLease,
        key: EntityKey,
        pendingSendId: UUID,
    ): DirectQueueCaptureResult = transaction("captureDirectQueueSelection") {
        val issued = issuedLease(lease)
            ?: return@transaction DirectQueueCaptureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (key.radioId != issued.token.radioId) return@transaction DirectQueueCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        if (!active(issued)) return@transaction DirectQueueCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        val pending = database.pendingSends().byId(key.radioId.value, pendingSendId)
            ?: return@transaction DirectQueueCaptureResult.Rejected(DirectAckRejection.MISSING)
        if (pending.messageID != key.id || pending.kindRawValue != PendingSendKind.DM.rawValue ||
            pending.contactID == null || pending.channelIndex != null
        ) return@transaction DirectQueueCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        requireDirectPendingIdentityAvailable(pending)
        if (database.pendingSends().forMessage(key.radioId.value, key.id).any {
                it.id != pending.id && it.kindRawValue != PendingSendKind.DM.rawValue
            }
        ) return@transaction DirectQueueCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        val row = database.messages().byId(key.radioId.value, key.id)
        if (row != null && (row.directionRawValue != MessageDirection.OUTGOING.rawValue ||
                row.channelIndex != null || row.contactID != pending.contactID)
        ) return@transaction DirectQueueCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val dao = database.directAcks()
        val pendingIdentity = dao.incarnation(key.radioId.value, pending.id, "pending")
            ?: invalidData("Missing queued direct pending incarnation")
        val messageIdentity = row?.let {
            dao.incarnation(key.radioId.value, key.id, "message") ?: invalidData("Missing queued direct message incarnation")
        }
        if (dao.unresolvedQueuedClaims(key.radioId.value, key.id, pending.id) != 0) {
            return@transaction DirectQueueCaptureResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        DirectQueueCaptureResult.Captured(
            QueueSelection(issued, UUID.randomUUID(), key, pending.id, pendingIdentity.incarnation,
                messageIdentity?.incarnation, messageIdentity?.revision),
            pending.toDTO(),
        )
    }

    override fun compareDirectQueueSelections(
        first: DirectQueueSelection, second: DirectQueueSelection,
    ): DirectQueueIncarnationComparison {
        val left = first as? QueueSelection
        val right = second as? QueueSelection
        if (left == null || right == null || issuedLease(left.lease) == null || issuedLease(right.lease) == null) {
            return DirectQueueIncarnationComparison.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        return if (left.lease == right.lease && left.key == right.key && left.pendingId == right.pendingId &&
            left.pendingIncarnation == right.pendingIncarnation
        ) DirectQueueIncarnationComparison.Same else DirectQueueIncarnationComparison.Different
    }

    override suspend fun mutateDirectQueueSelection(
        selection: DirectQueueSelection,
        requestId: UUID,
        mutation: DirectQueueMutation,
    ): DirectQueueMutationResult = transaction("mutateDirectQueueSelection") {
        val issued = selection as? QueueSelection
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val radio = issued.key.radioId.value
        val dao = database.directAcks()
        val kind = when (mutation) {
            DirectQueueMutation.MissingContact -> "QUEUE_MISSING_CONTACT"
            is DirectQueueMutation.DefinitelyUnsentFailure -> "QUEUE_UNSENT:${mutation.claimRequestId}"
            DirectQueueMutation.ReturnToPending -> "QUEUE_PENDING"
        }
        val previous = dao.operations(radio, issued.request).firstOrNull { it.requestId == requestId }
        if (previous != null) {
            if (previous.kind != kind || previous.pendingId != issued.pendingId) {
                return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.CONFLICT)
            }
            val status = previous.argument?.let {
                MessageStatus.fromRawValue(it) ?: invalidData("Unknown queued direct result status")
            }
            return@transaction DirectQueueMutationResult.AlreadyApplied(DirectQueueMutationReceipt(
                issued.pendingId, status, previous.maximum == 1L, mutation != DirectQueueMutation.ReturnToPending,
            ))
        }
        if (!active(issued.lease)) return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.STALE_LEASE)
        val pending = database.pendingSends().byId(radio, issued.pendingId)
            ?: return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.MISSING)
        val pendingIdentity = dao.incarnation(radio, issued.pendingId, "pending")
        val row = database.messages().byId(radio, issued.key.id)
        val identity = dao.incarnation(radio, issued.key.id, "message")
        if (pending.messageID != issued.key.id || pending.kindRawValue != PendingSendKind.DM.rawValue ||
            pendingIdentity?.incarnation != issued.pendingIncarnation ||
            (issued.messageIncarnation == null && (row != null || identity != null)) ||
            (issued.messageIncarnation != null && (row == null || identity == null || identity.incarnation != issued.messageIncarnation ||
                identity.revision != issued.messageRevision))
        ) return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.INVALIDATED)
        requireDirectPendingIdentityAvailable(pending)
        if (database.pendingSends().forMessage(radio, issued.key.id).any {
                it.id != pending.id && it.kindRawValue != PendingSendKind.DM.rawValue
            }
        ) return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.CONFLICT)
        when (mutation) {
            DirectQueueMutation.MissingContact -> {
                val contact = pending.contactID ?: return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                if (database.contacts().byId(radio, contact) != null) {
                    return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
            }
            is DirectQueueMutation.DefinitelyUnsentFailure -> {
                if (dao.claim(radio, mutation.claimRequestId) != null) {
                    return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
            }
            DirectQueueMutation.ReturnToPending -> Unit
        }
        val competingIntents = when (mutation) {
            is DirectQueueMutation.DefinitelyUnsentFailure ->
                dao.otherCurrentIntents(radio, issued.key.id, mutation.claimRequestId)
            else -> dao.currentIntents(radio, issued.key.id)
        }
        if (dao.unresolvedQueuedClaims(radio, issued.key.id, issued.pendingId) != 0) {
            return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        if (dao.currentClaims(radio, issued.key.id) != 0 || competingIntents != 0) {
            return@transaction DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val target = when (mutation) {
            DirectQueueMutation.ReturnToPending -> MessageStatus.PENDING
            else -> MessageStatus.FAILED
        }
        val priorStatus = row?.let {
            MessageStatus.fromRawValue(it.statusRawValue) ?: invalidData("Unknown queued direct message status")
        }
        val changed = priorStatus != null && priorStatus != MessageStatus.DELIVERED &&
            !(mutation == DirectQueueMutation.ReturnToPending && priorStatus == MessageStatus.PENDING)
        if (changed) ownWrite(database.messages().setStatusUnlessDelivered(radio, issued.key.id, target.rawValue))
        val removed = mutation != DirectQueueMutation.ReturnToPending
        if (removed) ownWrite(database.pendingSends().delete(radio, issued.pendingId))
        val afterMessage = dao.incarnation(radio, issued.key.id, "message")
        val afterPending = dao.incarnation(radio, issued.pendingId, "pending")
        val advances = (if (changed) 1L else 0L) + (if (removed) 1L else 0L)
        val originalRevision = issued.messageRevision
        if ((issued.messageIncarnation == null && afterMessage != null) ||
            (issued.messageIncarnation != null && (afterMessage == null ||
                afterMessage.incarnation != issued.messageIncarnation ||
                originalRevision == null || afterMessage.revision != checkedAdd(originalRevision, advances))) ||
            (if (removed) afterPending != null else afterPending?.incarnation != issued.pendingIncarnation)
        ) invalidData("Independent mutation inside queued direct cleanup")
        val status = if (changed) target else priorStatus
        dao.operation(DirectAckOperationEntity(
            radio, issued.request, requestId, kind, status?.rawValue, if (changed) 1 else 0, issued.pendingId,
        ))
        save()
        DirectQueueMutationResult.Applied(DirectQueueMutationReceipt(issued.pendingId, status, changed, removed))
    }

    override suspend fun bumpDirectQueueSelection(
        selection: DirectQueueSelection,
        requestId: UUID,
    ): DirectQueueBumpResult = transaction("incrementPendingSendAttemptCount") {
        val issued = selection as? QueueSelection
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val radio = issued.key.radioId.value
        val dao = database.directAcks()
        val previous = dao.operations(radio, issued.request).firstOrNull { it.requestId == requestId }
        if (previous != null && (!previous.kind.startsWith("QUEUE_BUMP:") || previous.pendingId != issued.pendingId)) {
            return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.CONFLICT)
        }
        if (!active(issued.lease)) return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.STALE_LEASE)
        val pending = database.pendingSends().byId(radio, issued.pendingId)
            ?: return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.MISSING)
        val pendingIdentity = dao.incarnation(radio, issued.pendingId, "pending")
        val row = database.messages().byId(radio, issued.key.id)
        val identity = dao.incarnation(radio, issued.key.id, "message")
        val expectedPending = previous?.kind?.removePrefix("QUEUE_BUMP:") ?: issued.pendingIncarnation
        val expectedRevision = if (previous != null) previous.argument else issued.messageRevision
        if (pending.messageID != issued.key.id || pending.kindRawValue != PendingSendKind.DM.rawValue ||
            pendingIdentity?.incarnation != expectedPending ||
            (issued.messageIncarnation == null && (row != null || identity != null)) ||
            (issued.messageIncarnation != null && (row == null || identity == null ||
                identity.incarnation != issued.messageIncarnation || identity.revision != expectedRevision))
        ) return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.INVALIDATED)
        requireDirectPendingIdentityAvailable(pending)
        if (database.pendingSends().forMessage(radio, issued.key.id).any {
                it.id != pending.id && it.kindRawValue != PendingSendKind.DM.rawValue
            }
        ) return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.CONFLICT)
        if (dao.unresolvedQueuedClaims(radio, issued.key.id, issued.pendingId) != 0) {
            return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        if (previous != null) {
            val count = previous.maximum ?: invalidData("Missing queued direct attempt count")
            if (pending.attemptCount != count) return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.INVALIDATED)
            return@transaction DirectQueueBumpResult.AlreadyApplied(DirectQueueBumpReceipt(
                issued.copy(pendingIncarnation = expectedPending, messageRevision = expectedRevision), pending.toDTO(), count,
            ))
        }
        if (hasCompetingQueueAuthority(radio, issued.key.id, pendingId = issued.pendingId)) {
            return@transaction DirectQueueBumpResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val explicitRetryClaims = dao.explicitlyRetriedClaims(radio, issued.key.id, issued.pendingId, expectedPending)
        val count = checkedIncrement(pending.attemptCount ?: 0)
        ownWrite(database.pendingSends().setAttemptCount(radio, issued.pendingId, count))
        val updatedPending = database.pendingSends().byId(radio, issued.pendingId)
            ?: invalidData("Queued direct bump lost its pending row")
        val updatedIdentity = dao.incarnation(radio, issued.pendingId, "pending")
            ?: invalidData("Queued direct bump lost its pending incarnation")
        val updatedMessageIdentity = dao.incarnation(radio, issued.key.id, "message")
        val expectedMessageRevision = issued.messageRevision?.let(::checkedIncrement)
        if (updatedPending.attemptCount != count || updatedIdentity.incarnation == issued.pendingIncarnation ||
            (issued.messageIncarnation == null && updatedMessageIdentity != null) ||
            (issued.messageIncarnation != null && (updatedMessageIdentity == null ||
                updatedMessageIdentity.incarnation != issued.messageIncarnation ||
                updatedMessageIdentity.revision != expectedMessageRevision))
        ) invalidData("Independent mutation inside queued direct bump")
        for (claim in explicitRetryClaims) {
            dao.operation(DirectAckOperationEntity(
                radio, claim, UUID.randomUUID(), "EXPLICIT_RETRY:${updatedIdentity.incarnation}",
                null, null, issued.pendingId,
            ))
        }
        dao.operation(DirectAckOperationEntity(
            radio, issued.request, requestId, "QUEUE_BUMP:${updatedIdentity.incarnation}",
            expectedMessageRevision, count, issued.pendingId,
        ))
        save()
        DirectQueueBumpResult.Applied(DirectQueueBumpReceipt(
            issued.copy(pendingIncarnation = updatedIdentity.incarnation, messageRevision = expectedMessageRevision),
            updatedPending.toDTO(), count,
        ))
    }

    private suspend fun RepositoryTransaction.queueSelectionRejection(
        selection: QueueSelection,
        expectedRevision: Long? = null,
    ): DirectAckRejection? {
        if (!active(selection.lease)) return DirectAckRejection.STALE_LEASE
        val radio = selection.key.radioId.value
        val pending = database.pendingSends().byId(radio, selection.pendingId) ?: return DirectAckRejection.MISSING
        val row = database.messages().byId(radio, selection.key.id) ?: return DirectAckRejection.MISSING
        val dao = database.directAcks()
        val pendingIdentity = dao.incarnation(radio, selection.pendingId, "pending")
        val identity = dao.incarnation(radio, selection.key.id, "message")
        if (pending.messageID != selection.key.id || pending.kindRawValue != PendingSendKind.DM.rawValue ||
            pendingIdentity?.incarnation != selection.pendingIncarnation ||
            identity == null || identity.incarnation != selection.messageIncarnation ||
            expectedRevision != null && identity.revision != expectedRevision
        ) return DirectAckRejection.INVALIDATED
        if (dao.unresolvedQueuedClaims(radio, selection.key.id, selection.pendingId) != 0) {
            return DirectAckRejection.UNRESOLVED_OUTCOME
        }
        if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null ||
            pending.contactID == null || row.contactID != pending.contactID
        ) return DirectAckRejection.NOT_ELIGIBLE
        requireDirectPendingIdentityAvailable(pending)
        if (database.pendingSends().forMessage(radio, selection.key.id).any {
                it.id != pending.id && it.kindRawValue != PendingSendKind.DM.rawValue
            }
        ) return DirectAckRejection.CONFLICT
        return null
    }

    override suspend fun prepareDirectQueueSelection(
        selection: DirectQueueSelection,
        requestId: UUID,
        timestamp: UInt?,
        resolveToSent: Boolean,
    ): DirectQueuePreparationResult = transaction("prepareDirectQueueSelection") {
        val issued = selection as? QueueSelection
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectQueuePreparationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val radio = issued.key.radioId.value
        val dao = database.directAcks()
        val kind = "QUEUE_PREPARE:${timestamp?.toLong() ?: "preserved"}:$resolveToSent"
        val previous = dao.operations(radio, issued.request).firstOrNull { it.requestId == requestId }
        if (previous != null) {
            if (previous.kind != kind || previous.pendingId != issued.pendingId) {
                return@transaction DirectQueuePreparationResult.Rejected(DirectAckRejection.CONFLICT)
            }
            val revision = previous.argument ?: invalidData("Missing queued direct preparation revision")
            queueSelectionRejection(issued, revision)?.let {
                return@transaction DirectQueuePreparationResult.Rejected(it)
            }
            val row = database.messages().byId(radio, issued.key.id)
                ?: invalidData("Queued direct preparation lost its message")
            val resolved = when (previous.maximum) {
                0L -> false
                1L -> true
                else -> invalidData("Invalid queued direct preparation result")
            }
            return@transaction DirectQueuePreparationResult.AlreadyApplied(DirectQueuePreparationReceipt(
                issued.copy(messageRevision = revision), row.toDTO(), resolved,
            ))
        }
        queueSelectionRejection(issued)?.let { return@transaction DirectQueuePreparationResult.Rejected(it) }
        if (hasCompetingQueueAuthority(radio, issued.key.id, pendingId = issued.pendingId)) {
            return@transaction DirectQueuePreparationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val row = database.messages().byId(radio, issued.key.id)
            ?: invalidData("Queued direct preparation lost its message")
        val before = dao.incarnation(radio, issued.key.id, "message")
            ?: invalidData("Queued direct preparation lost its incarnation")
        var advances = 0L
        if (timestamp != null) {
            database.messages().upsert(row.copy(timestamp = timestamp.toLong()))
            advances++
        }
        val resolved = resolveToSent && database.messages().clearRetryingToSent(radio, issued.key.id) == 1
        if (resolved) advances++
        val after = dao.incarnation(radio, issued.key.id, "message")
            ?: invalidData("Queued direct preparation lost its incarnation")
        val revision = checkedAdd(before.revision, advances)
        if (after.incarnation != issued.messageIncarnation || after.revision != revision) {
            invalidData("Independent mutation inside queued direct preparation")
        }
        val prepared = database.messages().byId(radio, issued.key.id)
            ?: invalidData("Queued direct preparation lost its message")
        dao.operation(DirectAckOperationEntity(
            radio, issued.request, requestId, kind, revision, if (resolved) 1 else 0, issued.pendingId,
        ))
        save()
        DirectQueuePreparationResult.Applied(DirectQueuePreparationReceipt(
            issued.copy(messageRevision = revision), prepared.toDTO(), resolved,
        ))
    }

    override suspend fun captureDirectQueueIntent(
        selection: DirectQueueSelection,
        requestId: UUID,
        mode: DirectAckMode,
    ): DirectAckIntentResult = transaction("captureDirectQueueIntent") {
        val issued = selection as? QueueSelection
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        queueSelectionRejection(issued)?.let { return@transaction DirectAckIntentResult.Rejected(it) }
        if (hasCompetingQueueAuthority(issued.key.radioId.value, issued.key.id, requestId, issued.pendingId)) {
            return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        captureIntent(issued.lease, issued.key, requestId, mode, issued.pendingId, issued)
    }

    override suspend fun captureDirectQueueCompletion(
        selection: DirectQueueSelection,
        captureRequestId: UUID,
        reason: DirectPendingCompletionReason,
    ): DirectPendingCaptureResult = transaction("captureDirectQueueCompletion") {
        val issued = selection as? QueueSelection
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        queueSelectionRejection(issued)?.let { return@transaction DirectPendingCaptureResult.Rejected(it) }
        capturePendingCompletion(issued.lease, issued.key, issued.pendingId, captureRequestId, reason, issued)
    }

    override suspend fun revokeDirectAckSession(lease: DirectAckLease): DirectAckRevocationResult =
        transaction("revokeDirectAckSession") {
            val issued = issuedLease(lease) ?: return@transaction DirectAckRevocationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
            val stored = database.directAcks().leases(issued.token.radioId.value).firstOrNull { it.requestId == issued.request }
                ?: return@transaction DirectAckRevocationResult.Rejected(DirectAckRejection.MISSING)
            if (!stored.active) return@transaction DirectAckRevocationResult.AlreadyRevoked
            database.directAcks().lease(stored.copy(active = false))
            save()
            DirectAckRevocationResult.Revoked
        }

    private fun issuedLease(lease: DirectAckLease): Lease? =
        (lease as? Lease)?.takeIf { it.issuer == context.directAckIssuer }

    private fun ownWrite(affected: Int) {
        if (affected != 1) invalidData("Own direct ACK transaction did not mutate exactly one row")
    }

    private suspend fun RepositoryTransaction.active(lease: Lease): Boolean =
        database.directAcks().leases(lease.token.radioId.value).any {
            it.requestId == lease.request && it.issuer == lease.issuer && it.epoch == lease.token.epoch.value &&
                it.generation == lease.token.generation.value && it.active
        }

    private suspend fun mutate(
        operation: String,
        attempt: DirectAckAttempt,
        block: suspend RepositoryTransaction.(Attempt, DirectAckClaimEntity) -> DirectAckMutationResult,
    ): DirectAckMutationResult = transaction(operation) {
        val issued = attempt as? Attempt
        if (issued == null || issuedLease(issued.lease) == null) {
            return@transaction DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val claim = database.directAcks().claim(issued.lease.token.radioId.value, issued.request)
            ?: return@transaction DirectAckMutationResult.Rejected(DirectAckRejection.MISSING)
        if (claim.leaseId != issued.lease.request) return@transaction DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        block(issued, claim)
    }

    private suspend fun RepositoryTransaction.hasCompetingIntent(claim: DirectAckClaimEntity): Boolean =
        database.directAcks().otherCurrentIntents(claim.radioId, claim.messageId, claim.requestId) != 0

    private suspend fun RepositoryTransaction.hasCompetingQueueAuthority(
        radio: UUID, message: UUID, exceptIntent: UUID? = null, pendingId: UUID? = null,
    ): Boolean = database.directAcks().let { dao ->
        dao.unresolvedQueuedClaims(radio, message, pendingId) != 0 || dao.currentUnresolvedClaims(radio, message) != 0 ||
            if (exceptIntent == null) dao.currentIntents(radio, message) != 0
            else dao.otherCurrentIntents(radio, message, exceptIntent) != 0
    }

    private suspend fun RepositoryTransaction.recoverableDeliveredQueueClaim(
        radio: UUID, message: UUID, pendingId: UUID,
    ): DirectAckClaimEntity? {
        val dao = database.directAcks()
        val row = database.messages().byId(radio, message) ?: return null
        val pending = database.pendingSends().byId(radio, pendingId) ?: return null
        if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null ||
            row.statusRawValue != MessageStatus.DELIVERED.rawValue || row.contactID == null ||
            pending.messageID != message || pending.kindRawValue != PendingSendKind.DM.rawValue ||
            pending.contactID != row.contactID || pending.channelIndex != null || pending.isResend
        ) return null
        requireDirectPendingIdentityAvailable(pending)
        val messageIdentity = dao.incarnation(radio, message, "message") ?: return null
        val pendingIdentity = dao.incarnation(radio, pendingId, "pending") ?: return null
        if (dao.unresolvedQueuedClaims(radio, message, pendingId) != 1 ||
            dao.currentUnresolvedClaims(radio, message) != 0 || dao.currentIntents(radio, message) != 0
        ) return null
        val delivered = dao.unresolvedQueuedClaimsForRetry(radio, message).mapNotNull { dao.claim(radio, it) }
            .singleOrNull {
                it.pendingId == pendingId && it.pendingIncarnation == pendingIdentity.incarnation &&
                    it.incarnation == messageIdentity.incarnation && it.revision == messageIdentity.revision &&
                    it.mode == DirectAckMode.SEND.name && it.phase == DirectAckPhase.DELIVERED.name
            } ?: return null
        val delivery = dao.delivery(radio, delivered.requestId) ?: return null
        return delivered.takeIf {
            delivery.messageId == message && delivery.ackCode == row.ackCode &&
                delivery.roundTripTime == row.roundTripTime
        }
    }

    private suspend fun RepositoryTransaction.permission(claim: DirectAckClaimEntity, lease: Lease): DirectAckPermission {
        if (!active(lease)) return DirectAckPermission.STALE_LEASE
        val dao = database.directAcks()
        val identity = dao.incarnation(claim.radioId, claim.messageId, "message")
            ?: return DirectAckPermission.MISSING
        if (!claim.current || claim.snapshot == null || identity.incarnation != claim.incarnation || identity.revision != claim.revision) {
            return DirectAckPermission.INVALIDATED
        }
        val pendingId = claim.pendingId
        if (pendingId != null && dao.operations(claim.radioId, claim.requestId).none { it.kind == "PENDING" }) {
            if (dao.incarnation(claim.radioId, pendingId, "pending")?.incarnation != claim.pendingIncarnation) {
                return DirectAckPermission.INVALIDATED
            }
        }
        return DirectAckPermission.CURRENT
    }

    private fun rejection(permission: DirectAckPermission): DirectAckRejection? = when (permission) {
        DirectAckPermission.CURRENT -> null
        DirectAckPermission.INVALIDATED -> DirectAckRejection.INVALIDATED
        DirectAckPermission.STALE_LEASE -> DirectAckRejection.STALE_LEASE
        DirectAckPermission.MISSING -> DirectAckRejection.MISSING
    }

    private suspend fun RepositoryTransaction.deny(claim: DirectAckClaimEntity, issued: Attempt): DirectAckMutationResult? =
        rejection(permission(claim, issued.lease))?.let { DirectAckMutationResult.Rejected(it) }

    private suspend fun RepositoryTransaction.renew(
        claim: DirectAckClaimEntity,
        phase: String = claim.phase,
        revisionAdvances: Int = 0,
    ): DirectAckClaimEntity {
        val identity = database.directAcks().incarnation(claim.radioId, claim.messageId, "message")
            ?: invalidData("Own direct ACK transaction lost its incarnation")
        val expected = checkedAdd(claim.revision, revisionAdvances.toLong())
        if (identity.incarnation != claim.incarnation || identity.revision != expected) {
            invalidData("Independent message mutation inside direct ACK transaction")
        }
        val row = database.messages().byId(claim.radioId, claim.messageId)
            ?: invalidData("Own direct ACK transaction lost its message")
        return claim.copy(phase = phase, revision = expected, current = true, snapshot = row).also { database.directAcks().claim(it) }
    }

    private fun operation(claim: DirectAckClaimEntity, request: UUID, change: DirectAckBookkeeping): DirectAckOperationEntity =
        DirectAckOperationEntity(
            claim.radioId, claim.requestId, request,
            when (change) {
                is DirectAckBookkeeping.SendCompleted -> "SEND_COMPLETED:${change.wireRequestId}"
                is DirectAckBookkeeping.RetryStarted -> "RETRY"
                DirectAckBookkeeping.RetryExhausted -> "EXHAUSTED"
                DirectAckBookkeeping.ReturnToPending -> "RETURN_TO_PENDING"
                DirectAckBookkeeping.ResendCountIncrement -> "COUNT"
            },
            (change as? DirectAckBookkeeping.RetryStarted)?.retryAttempt,
            (change as? DirectAckBookkeeping.RetryStarted)?.maxRetryAttempts,
            null,
        )

    private suspend fun RepositoryTransaction.replay(
        claim: DirectAckClaimEntity,
        issued: Attempt,
        operation: DirectAckOperationEntity,
    ): DirectAckMutationResult? {
        val previous = database.directAcks().operations(claim.radioId, claim.requestId)
            .firstOrNull { it.requestId == operation.requestId } ?: return null
        return if (previous == operation) DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
    }

    private suspend fun RepositoryTransaction.receipt(claim: DirectAckClaimEntity, lease: Lease): DirectAckReceipt {
        val dao = database.directAcks()
        val operations = dao.operations(claim.radioId, claim.requestId)
        val bookkeeping = operations.filter {
            it.kind != "PENDING" && !it.kind.startsWith("EXPLICIT_RETRY:") &&
                !it.kind.startsWith("DEFINITELY_UNSENT:")
        }.map {
            val change = when {
                it.kind.startsWith("SEND_COMPLETED:") ->
                    DirectAckBookkeeping.SendCompleted(UUID.fromString(it.kind.removePrefix("SEND_COMPLETED:")))
                it.kind == "RETRY" -> DirectAckBookkeeping.RetryStarted(
                    it.argument ?: invalidData("Missing retry argument"), it.maximum ?: invalidData("Missing retry maximum"),
                )
                it.kind == "EXHAUSTED" -> DirectAckBookkeeping.RetryExhausted
                it.kind == "RETURN_TO_PENDING" -> DirectAckBookkeeping.ReturnToPending
                it.kind == "COUNT" -> DirectAckBookkeeping.ResendCountIncrement
                else -> invalidData("Unknown direct ACK operation")
            }
            DirectAckBookkeepingReceipt(it.requestId, change)
        }.snapshot()
        val delivery = dao.delivery(claim.radioId, claim.requestId)?.let {
            DirectAckDelivery(it.ackCode.asUInt(), it.roundTripTime?.asUInt(), it.snapshot?.toDTO())
        }
        val definitelyUnsent = operations.filter { it.kind.startsWith("DEFINITELY_UNSENT:") }
        if (definitelyUnsent.size > 1) invalidData("Multiple definitely-unsent direct wire receipts")
        return DirectAckReceipt(
            EntityKey(RadioId(claim.radioId), claim.messageId), claim.requestId,
            DirectAckMode.entries.firstOrNull { it.name == claim.mode } ?: invalidData("Unknown direct ACK mode"),
            MessageStatus.fromRawValue(claim.initialStatus)
                ?: invalidData("Unknown initial direct ACK status"),
            DirectAckPhase.entries.firstOrNull { it.name == claim.phase } ?: invalidData("Unknown direct ACK phase"),
            dao.wires(claim.radioId, claim.requestId).map {
                DirectAckWireReceipt(it.requestId, it.predictedCode.asUInt(), it.acceptedCode?.asUInt())
            }.snapshot(),
            bookkeeping,
            operations.filter { it.kind == "PENDING" }.map {
                DirectAckPendingReceipt(it.requestId, it.pendingId ?: invalidData("Missing pending completion identity"))
            }.snapshot(),
            claim.snapshot?.toDTO(), delivery, permission(claim, lease),
            definitelyUnsent.singleOrNull()?.kind?.removePrefix("DEFINITELY_UNSENT:")?.let(UUID::fromString),
        )
    }

    private fun Long.asUInt(): UInt {
        if (this !in 0..UInt.MAX_VALUE.toLong()) invalidData("Direct ACK value outside UInt32")
        return toUInt()
    }
}
