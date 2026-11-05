// AndroidOnly: WP-202 Atomic persisted direct-ACK authority, distinct from ACK ingress.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID

internal class DirectAckRepository(private val context: RoomRepositoryContext) :
    DirectAckAuthority, DirectPendingCompletionAuthority {
    private data class Lease(val issuer: UUID, val token: SessionToken, val request: UUID) : DirectAckLease
    private data class Attempt(val lease: Lease, val request: UUID) : DirectAckAttempt
    private data class PendingCompletion(val lease: Lease, val request: UUID) : DirectPendingCompletion

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

    override suspend fun beginDirectAckAttempt(
        lease: DirectAckLease,
        key: EntityKey,
        requestId: UUID,
        firstWireRequestId: UUID,
        predictedAckCode: UInt,
        mode: DirectAckMode,
        pendingSendId: UUID?,
    ): DirectAckBeginResult = transaction("beginDirectAckAttempt") {
        val issued = issuedLease(lease) ?: return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (key.radioId != issued.token.radioId) return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
        if (!active(issued)) return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.STALE_LEASE)
        val dao = database.directAcks()
        val existing = dao.claim(key.radioId.value, requestId)
        if (existing != null) {
            val first = dao.wires(key.radioId.value, requestId).firstOrNull { it.requestId == firstWireRequestId }
            if (existing.leaseId != issued.request || existing.firstWireId != firstWireRequestId ||
                existing.snapshot.id != key.id || existing.mode != mode.name ||
                existing.pendingId != pendingSendId || first?.predictedCode != predictedAckCode.toLong()
            ) return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
            rejection(permission(existing, issued))?.let { return@transaction DirectAckBeginResult.Rejected(it) }
            return@transaction DirectAckBeginResult.AlreadyBegun(Attempt(issued, requestId), receipt(existing, issued))
        }
        val row = database.messages().byId(key.radioId.value, key.id)
            ?: return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.MISSING)
        if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null ||
            (mode == DirectAckMode.SEND && row.statusRawValue in setOf(3L, 4L))
        ) return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (MessageStatus.fromRawValue(row.statusRawValue) == null) invalidData("Unknown direct message status")
        val incarnation = dao.incarnation(key.radioId.value, key.id, "message")
            ?: invalidData("Missing persisted message incarnation")
        val pending = pendingSendId?.let { id ->
            database.pendingSends().forMessage(key.radioId.value, key.id).firstOrNull { it.id == id }
                ?: return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.MISSING)
        }
        if (pending != null && pending.kindRawValue != PendingSendKind.DM.rawValue) {
            return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val pendingIncarnation = pending?.let {
            dao.incarnation(it.radioId, it.id, "pending")?.incarnation ?: invalidData("Missing pending incarnation")
        }
        dao.invalidateMessage(key.radioId.value, key.id)
        val claim = DirectAckClaimEntity(
            requestId, issued.request, firstWireRequestId, incarnation.incarnation, incarnation.revision, DirectAckPhase.REGISTERED.name, mode.name,
            row.statusRawValue, true, pendingSendId, pendingIncarnation, row,
        )
        dao.claim(claim)
        dao.wire(DirectAckWireEntity(key.radioId.value, requestId, firstWireRequestId, predictedAckCode.toLong(), null))
        save()
        DirectAckBeginResult.Begun(Attempt(issued, requestId), receipt(claim, issued))
    }

    override suspend fun registerDirectAckDispatch(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckMutationResult = mutate("registerDirectAckDispatch", attempt) { issued, claim ->
        val dao = database.directAcks()
        val existing = dao.wires(claim.snapshot.radioId, claim.requestId).firstOrNull { it.requestId == wireRequestId }
        if (existing != null) return@mutate if (existing.predictedCode == predictedAckCode.toLong()) {
            DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        } else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        if (claim.phase == DirectAckPhase.DELIVERED.name) {
            return@mutate DirectAckMutationResult.AlreadyDelivered(receipt(claim, issued.lease))
        }
        if (claim.phase == DirectAckPhase.FAILED.name) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        dao.wire(DirectAckWireEntity(claim.snapshot.radioId, claim.requestId, wireRequestId, predictedAckCode.toLong(), null))
        save()
        DirectAckMutationResult.Applied(receipt(claim, issued.lease))
    }

    override suspend fun recordDirectSendAccepted(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        ackCode: UInt,
    ): DirectAckMutationResult = mutate("recordDirectSendAccepted", attempt) { issued, claim ->
        val dao = database.directAcks()
        val wire = dao.wires(claim.snapshot.radioId, claim.requestId).firstOrNull { it.requestId == wireRequestId }
            ?: return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.MISSING)
        if (wire.acceptedCode != null) return@mutate if (wire.acceptedCode == ackCode.toLong()) {
            DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        } else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        dao.wire(wire.copy(acceptedCode = ackCode.toLong()))
        val writesMessage = claim.phase in setOf(DirectAckPhase.REGISTERED.name, DirectAckPhase.ACCEPTED.name) &&
            claim.snapshot.statusRawValue !in setOf(3L, 4L)
        if (writesMessage) database.messages().setAck(claim.snapshot.radioId, claim.snapshot.id, ackCode.toLong(), 2, null)
        val phase = if (claim.phase == DirectAckPhase.REGISTERED.name) DirectAckPhase.ACCEPTED.name else claim.phase
        val updated = renew(claim, phase, if (writesMessage) 1 else 0)
        save()
        DirectAckMutationResult.Applied(receipt(updated, issued.lease))
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
            ownWrite(database.messages().setStatusUnlessDelivered(claim.snapshot.radioId, claim.snapshot.id, 4))
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
        val previous = dao.delivery(claim.snapshot.radioId, claim.requestId)
        if (previous != null) return@mutate if (previous.ackCode == ackCode.toLong() &&
            previous.roundTripTime == roundTripTime?.toLong()
        ) DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        if (dao.wires(claim.snapshot.radioId, claim.requestId).none {
                it.predictedCode == ackCode.toLong() || it.acceptedCode == ackCode.toLong()
            }
        ) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        ownWrite(dao.deliver(claim.snapshot.radioId, claim.snapshot.id, ackCode.toLong(), roundTripTime?.toLong()))
        val updated = renew(claim, DirectAckPhase.DELIVERED.name, 1)
        dao.delivery(DirectAckDeliveryEntity(claim.requestId, ackCode.toLong(), roundTripTime?.toLong(), updated.snapshot))
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
        val row = claim.snapshot
        if (change != DirectAckBookkeeping.ResendCountIncrement && row.statusRawValue == 3L) {
            return@mutate DirectAckMutationResult.AlreadyDelivered(receipt(claim, issued.lease))
        }
        when (change) {
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
            claim.snapshot.radioId, claim.requestId, requestId, "PENDING", null, null, pendingSendId,
        )
        replay(claim, issued, operation)?.let { return@mutate it }
        if (claim.pendingId != pendingSendId) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        if (database.directAcks().operations(claim.snapshot.radioId, claim.requestId).any { it.kind == "PENDING" }) {
            return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        ownWrite(database.pendingSends().delete(claim.snapshot.radioId, pendingSendId))
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
        if (key.radioId != issued.token.radioId) return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        val dao = database.directAcks()
        val reasonName = when (reason) {
            DirectPendingCompletionReason.AlreadyDeliveredSend -> "DELIVERED"
            is DirectPendingCompletionReason.ZeroAttempts -> "ZERO"
        }
        val maximum = (reason as? DirectPendingCompletionReason.ZeroAttempts)?.configuredMaximumAttempts
        val existing = dao.pendingCompletion(key.radioId.value, captureRequestId)
        if (existing != null) {
            if (existing.leaseId != issued.request || existing.message.id != key.id ||
                existing.pending.id != pendingSendId || existing.reason != reasonName || existing.maximumAttempts != maximum
            ) return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.CONFLICT)
            rejection(pendingPermission(existing, issued))?.let {
                return@transaction DirectPendingCaptureResult.Rejected(it)
            }
            return@transaction DirectPendingCaptureResult.AlreadyCaptured(
                PendingCompletion(issued, captureRequestId), pendingReceipt(existing, issued),
            )
        }
        if (!active(issued)) return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        val row = database.messages().byId(key.radioId.value, key.id)
            ?: return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.MISSING)
        val pending = database.pendingSends().forMessage(key.radioId.value, key.id).firstOrNull { it.id == pendingSendId }
            ?: return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.MISSING)
        if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null ||
            pending.kindRawValue != PendingSendKind.DM.rawValue ||
            (reasonName == "DELIVERED" && (row.statusRawValue != 3L || pending.isResend)) ||
            (reasonName == "ZERO" && maximum != 0L)
        ) return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val messageIdentity = dao.incarnation(key.radioId.value, key.id, "message")
            ?: invalidData("Missing no-dispatch message incarnation")
        val pendingIdentity = dao.incarnation(key.radioId.value, pendingSendId, "pending")
            ?: invalidData("Missing no-dispatch pending incarnation")
        val capture = DirectPendingCompletionEntity(
            captureRequestId, issued.request, messageIdentity.incarnation, messageIdentity.revision,
            pendingIdentity.incarnation, reasonName, maximum, true, null, row, pending,
        )
        dao.pendingCompletion(capture)
        save()
        DirectPendingCaptureResult.Captured(PendingCompletion(issued, captureRequestId), pendingReceipt(capture, issued))
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
        ownWrite(database.pendingSends().delete(capture.pending.radioId, capture.pending.id))
        val identity = dao.incarnation(capture.message.radioId, capture.message.id, "message")
            ?: invalidData("No-dispatch completion lost its message")
        if (identity.incarnation != capture.messageIncarnation ||
            identity.revision != checkedIncrement(capture.messageRevision)
        ) invalidData("Independent mutation inside no-dispatch completion")
        val committed = capture.copy(completedRequestId = completionRequestId, current = false)
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
        val message = dao.incarnation(capture.message.radioId, capture.message.id, "message")
            ?: return DirectAckPermission.MISSING
        if (!capture.current || message.incarnation != capture.messageIncarnation ||
            message.revision != capture.messageRevision ||
            dao.incarnation(capture.pending.radioId, capture.pending.id, "pending")?.incarnation != capture.pendingIncarnation
        ) return DirectAckPermission.INVALIDATED
        return DirectAckPermission.CURRENT
    }

    private suspend fun RepositoryTransaction.pendingReceipt(
        capture: DirectPendingCompletionEntity,
        lease: Lease,
    ) = DirectPendingCompletionReceipt(
        capture.captureRequestId, EntityKey(RadioId(capture.message.radioId), capture.message.id),
        when (capture.reason) {
            "DELIVERED" -> DirectPendingCompletionReason.AlreadyDeliveredSend
            "ZERO" -> DirectPendingCompletionReason.ZeroAttempts(
                capture.maximumAttempts ?: invalidData("Missing no-dispatch maximum attempts"),
            )
            else -> invalidData("Unknown no-dispatch completion reason")
        },
        capture.message.toDTO(), capture.pending.toDTO(), capture.completedRequestId,
        when (pendingPermission(capture, lease)) {
            DirectAckPermission.CURRENT -> DirectPendingCompletionPermission.CURRENT
            DirectAckPermission.INVALIDATED -> DirectPendingCompletionPermission.INVALIDATED
            DirectAckPermission.STALE_LEASE -> DirectPendingCompletionPermission.STALE_LEASE
            DirectAckPermission.MISSING -> DirectPendingCompletionPermission.MISSING
        },
    )

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

    private suspend fun RepositoryTransaction.permission(claim: DirectAckClaimEntity, lease: Lease): DirectAckPermission {
        if (!active(lease)) return DirectAckPermission.STALE_LEASE
        val dao = database.directAcks()
        val identity = dao.incarnation(claim.snapshot.radioId, claim.snapshot.id, "message")
            ?: return DirectAckPermission.MISSING
        if (!claim.current || identity.incarnation != claim.incarnation || identity.revision != claim.revision) {
            return DirectAckPermission.INVALIDATED
        }
        val pendingId = claim.pendingId
        if (pendingId != null && dao.operations(claim.snapshot.radioId, claim.requestId).none { it.kind == "PENDING" }) {
            if (dao.incarnation(claim.snapshot.radioId, pendingId, "pending")?.incarnation != claim.pendingIncarnation) {
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
        val identity = database.directAcks().incarnation(claim.snapshot.radioId, claim.snapshot.id, "message")
            ?: invalidData("Own direct ACK transaction lost its incarnation")
        val expected = checkedAdd(claim.revision, revisionAdvances.toLong())
        if (identity.incarnation != claim.incarnation || identity.revision != expected) {
            invalidData("Independent message mutation inside direct ACK transaction")
        }
        val row = database.messages().byId(claim.snapshot.radioId, claim.snapshot.id)
            ?: invalidData("Own direct ACK transaction lost its message")
        return claim.copy(phase = phase, revision = expected, current = true, snapshot = row).also { database.directAcks().claim(it) }
    }

    private fun operation(claim: DirectAckClaimEntity, request: UUID, change: DirectAckBookkeeping): DirectAckOperationEntity =
        DirectAckOperationEntity(
            claim.snapshot.radioId, claim.requestId, request,
            when (change) {
                is DirectAckBookkeeping.RetryStarted -> "RETRY"
                DirectAckBookkeeping.RetryExhausted -> "EXHAUSTED"
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
        val previous = database.directAcks().operations(claim.snapshot.radioId, claim.requestId)
            .firstOrNull { it.requestId == operation.requestId } ?: return null
        return if (previous == operation) DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
    }

    private suspend fun RepositoryTransaction.receipt(claim: DirectAckClaimEntity, lease: Lease): DirectAckReceipt {
        val dao = database.directAcks()
        val operations = dao.operations(claim.snapshot.radioId, claim.requestId)
        val bookkeeping = operations.filter { it.kind != "PENDING" }.map {
            val change = when (it.kind) {
                "RETRY" -> DirectAckBookkeeping.RetryStarted(
                    it.argument ?: invalidData("Missing retry argument"), it.maximum ?: invalidData("Missing retry maximum"),
                )
                "EXHAUSTED" -> DirectAckBookkeeping.RetryExhausted
                "COUNT" -> DirectAckBookkeeping.ResendCountIncrement
                else -> invalidData("Unknown direct ACK operation")
            }
            DirectAckBookkeepingReceipt(it.requestId, change)
        }.snapshot()
        val delivery = dao.delivery(claim.snapshot.radioId, claim.requestId)?.let {
            DirectAckDelivery(it.ackCode.asUInt(), it.roundTripTime?.asUInt(), it.snapshot.toDTO())
        }
        return DirectAckReceipt(
            EntityKey(RadioId(claim.snapshot.radioId), claim.snapshot.id), claim.requestId,
            DirectAckMode.entries.firstOrNull { it.name == claim.mode } ?: invalidData("Unknown direct ACK mode"),
            MessageStatus.fromRawValue(claim.initialStatus)
                ?: invalidData("Unknown initial direct ACK status"),
            DirectAckPhase.entries.firstOrNull { it.name == claim.phase } ?: invalidData("Unknown direct ACK phase"),
            dao.wires(claim.snapshot.radioId, claim.requestId).map {
                DirectAckWireReceipt(it.requestId, it.predictedCode.asUInt(), it.acceptedCode?.asUInt())
            }.snapshot(),
            bookkeeping,
            operations.filter { it.kind == "PENDING" }.map {
                DirectAckPendingReceipt(it.requestId, it.pendingId ?: invalidData("Missing pending completion identity"))
            }.snapshot(),
            claim.snapshot.toDTO(), delivery, permission(claim, lease),
        )
    }

    private fun Long.asUInt(): UInt {
        if (this !in 0..UInt.MAX_VALUE.toLong()) invalidData("Direct ACK value outside UInt32")
        return toUInt()
    }
}
