// AndroidOnly: WP-208 In-memory authority fixture; not Room, durable history or process-death evidence.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import java.util.UUID

internal class RecordingDirectAckAuthority(private val store: RecordingStore) :
    DirectAckAuthority, DirectPendingCompletionAuthority {
    private class Lease(val token: SessionToken, val requestID: UUID) : DirectAckLease
    private class Attempt(
        val lease: Lease,
        var receipt: DirectAckReceipt,
        var revision: Long,
        val pendingID: UUID?,
        val pendingRevision: Long?,
    ) : DirectAckAttempt

    private val currentLeases = mutableMapOf<RadioId, Lease>()
    private val leases = mutableMapOf<UUID, Lease>()
    private val attempts = mutableMapOf<UUID, Attempt>()
    private val current = mutableMapOf<EntityKey, Attempt>()
    private val revoked = mutableSetOf<Lease>()
    var afterFailure: (suspend (DirectAckReceipt) -> Unit)? = null
    private class Completion(
        val lease: Lease,
        var receipt: DirectPendingCompletionReceipt,
        val messageRevision: Long,
        val pendingRevision: Long,
    ) : DirectPendingCompletion
    private val completions = mutableMapOf<UUID, Completion>()

    private fun permission(attempt: Attempt): DirectAckPermission = when {
        currentLeases[attempt.lease.token.radioId] !== attempt.lease ||
            attempt.lease in revoked -> DirectAckPermission.STALE_LEASE
        store.messages[attempt.receipt.key] == null -> DirectAckPermission.MISSING
        current[attempt.receipt.key] !== attempt ||
            store.messageRevision(attempt.receipt.key) != attempt.revision -> DirectAckPermission.INVALIDATED
        else -> DirectAckPermission.CURRENT
    }

    private fun receipt(attempt: Attempt) = attempt.receipt.copy(permission = permission(attempt))

    private fun rejection(attempt: Attempt): DirectAckMutationResult.Rejected? = when (permission(attempt)) {
        DirectAckPermission.CURRENT -> null
        DirectAckPermission.STALE_LEASE -> DirectAckMutationResult.Rejected(DirectAckRejection.STALE_LEASE)
        DirectAckPermission.MISSING -> DirectAckMutationResult.Rejected(DirectAckRejection.MISSING)
        DirectAckPermission.INVALIDATED -> DirectAckMutationResult.Rejected(DirectAckRejection.INVALIDATED)
    }

    private fun resolve(handle: DirectAckAttempt): Attempt? =
        (handle as? Attempt)?.takeIf { attempts[it.receipt.requestId] === it }

    private fun commit(attempt: Attempt, message: MessageDTO, phase: DirectAckPhase = attempt.receipt.phase) {
        store.messages[attempt.receipt.key] = message
        store.invalidateMessage(attempt.receipt.key)
        attempt.revision = store.messageRevision(attempt.receipt.key)
        attempt.receipt = attempt.receipt.copy(message = message, phase = phase)
    }

    override suspend fun activateDirectAckSession(token: SessionToken, requestId: UUID): DirectAckActivationResult {
        leases[requestId]?.let {
            return if (it === currentLeases[token.radioId] && it.token == token && it !in revoked) {
                DirectAckActivationResult.AlreadyActivated(it)
            } else DirectAckActivationResult.Rejected(DirectAckRejection.STALE_LEASE)
        }
        val prior = currentLeases[token.radioId]
        if (prior != null && (prior.token.epoch != token.epoch ||
                prior.token.generation.value >= token.generation.value)) {
            return DirectAckActivationResult.Rejected(DirectAckRejection.STALE_LEASE)
        }
        val value = Lease(token, requestId)
        currentLeases[token.radioId] = value
        leases[requestId] = value
        return DirectAckActivationResult.Activated(value)
    }

    override suspend fun beginDirectAckAttempt(
        lease: DirectAckLease, key: EntityKey, requestId: UUID, firstWireRequestId: UUID,
        predictedAckCode: UInt, mode: DirectAckMode, pendingSendId: UUID?,
    ): DirectAckBeginResult {
        val owner = (lease as? Lease)?.takeIf { it === currentLeases[key.radioId] && it !in revoked }
            ?: return DirectAckBeginResult.Rejected(DirectAckRejection.STALE_LEASE)
        attempts[requestId]?.let {
            val first = it.receipt.wires.first()
            if (it.lease !== owner || it.receipt.key != key || it.receipt.mode != mode || it.pendingID != pendingSendId ||
                first.requestId != firstWireRequestId || first.predictedAckCode != predictedAckCode) {
                return DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
            }
            return if (permission(it) == DirectAckPermission.CURRENT) DirectAckBeginResult.AlreadyBegun(it, receipt(it))
            else DirectAckBeginResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        val message = store.messages[key] ?: return DirectAckBeginResult.Rejected(DirectAckRejection.MISSING)
        if (message.channelIndex != null || message.direction != MessageDirection.OUTGOING ||
            mode == DirectAckMode.SEND && message.status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) {
            return DirectAckBeginResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (pendingSendId != null && store.pending[EntityKey(key.radioId, pendingSendId)]?.messageID != key.id) {
            return DirectAckBeginResult.Rejected(DirectAckRejection.MISSING)
        }
        val value = Attempt(owner, DirectAckReceipt(
            key, requestId, mode, message.status, DirectAckPhase.REGISTERED,
            listOf(DirectAckWireReceipt(firstWireRequestId, predictedAckCode, null)).snapshot(),
            emptyList<DirectAckBookkeepingReceipt>().snapshot(), emptyList<DirectAckPendingReceipt>().snapshot(),
            message, null, DirectAckPermission.CURRENT,
        ), store.messageRevision(key), pendingSendId,
            pendingSendId?.let { store.pendingRevision(EntityKey(key.radioId, it)) })
        attempts[requestId] = value
        current[key] = value
        return DirectAckBeginResult.Begun(value, receipt(value))
    }

    override suspend fun registerDirectAckDispatch(
        attempt: DirectAckAttempt, wireRequestId: UUID, predictedAckCode: UInt,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        rejection(value)?.let { return it }
        value.receipt.wires.firstOrNull { it.requestId == wireRequestId }?.let {
            return if (it.predictedAckCode == predictedAckCode) DirectAckMutationResult.AlreadyApplied(receipt(value))
            else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        value.receipt = value.receipt.copy(wires =
            (value.receipt.wires + DirectAckWireReceipt(wireRequestId, predictedAckCode, null)).snapshot())
        return DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun recordDirectSendAccepted(
        attempt: DirectAckAttempt, wireRequestId: UUID, ackCode: UInt,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val wire = value.receipt.wires.firstOrNull { it.requestId == wireRequestId }
            ?: return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (wire.acceptedAckCode != null) return if (wire.acceptedAckCode == ackCode) {
            DirectAckMutationResult.AlreadyApplied(receipt(value))
        } else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        rejection(value)?.let { return it }
        store.authorityAccess("updateMessageAck")
        rejection(value)?.let { return it }
        val message = requireNotNull(store.messages[value.receipt.key])
        if (message.status !in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) {
            commit(value, message.copy(status = MessageStatus.SENT, ackCode = ackCode), DirectAckPhase.ACCEPTED)
        }
        value.receipt = value.receipt.copy(wires = value.receipt.wires.map {
            if (it.requestId == wireRequestId) it.copy(acceptedAckCode = ackCode) else it
        }.snapshot())
        return DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun recordDirectFailure(attempt: DirectAckAttempt): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        rejection(value)?.let { return it }
        if (value.receipt.phase == DirectAckPhase.DELIVERED || value.receipt.initialStatus == MessageStatus.DELIVERED) {
            return DirectAckMutationResult.AlreadyDelivered(receipt(value))
        }
        if (value.receipt.phase == DirectAckPhase.FAILED) return DirectAckMutationResult.AlreadyApplied(receipt(value))
        store.authorityAccess("updateMessageStatusUnlessDelivered")
        rejection(value)?.let { return it }
        commit(value, requireNotNull(store.messages[value.receipt.key]).copy(status = MessageStatus.FAILED), DirectAckPhase.FAILED)
        afterFailure?.invoke(receipt(value))
        return DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun settleDirectAck(
        attempt: DirectAckAttempt, ackCode: UInt, roundTripTime: UInt?,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        value.receipt.delivery?.let {
            return if (it.ackCode == ackCode && it.roundTripTime == roundTripTime) {
                DirectAckMutationResult.AlreadyApplied(receipt(value))
            } else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(value)?.let { return it }
        if (value.receipt.wires.none { it.predictedAckCode == ackCode || it.acceptedAckCode == ackCode }) {
            return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        store.authorityAccess("updateMessageAck")
        rejection(value)?.let { return it }
        val message = requireNotNull(store.messages[value.receipt.key]).copy(
            status = MessageStatus.DELIVERED, ackCode = ackCode, roundTripTime = roundTripTime,
        )
        commit(value, message, DirectAckPhase.DELIVERED)
        value.receipt = value.receipt.copy(delivery = DirectAckDelivery(ackCode, roundTripTime, message))
        return DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun applyDirectAckBookkeeping(
        attempt: DirectAckAttempt, requestId: UUID, change: DirectAckBookkeeping,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        value.receipt.bookkeeping.firstOrNull { it.requestId == requestId }?.let {
            return if (it.change == change) DirectAckMutationResult.AlreadyApplied(receipt(value))
            else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(value)?.let { return it }
        val operation = when (change) {
            is DirectAckBookkeeping.RetryStarted -> "updateMessageRetryStatus"
            DirectAckBookkeeping.RetryExhausted -> "clearRetryingToSent"
            DirectAckBookkeeping.ResendCountIncrement -> "incrementMessageSendCount"
        }
        store.authorityAccess(operation)
        rejection(value)?.let { return it }
        val row = requireNotNull(store.messages[value.receipt.key])
        if (row.status == MessageStatus.FAILED && change != DirectAckBookkeeping.ResendCountIncrement) {
            return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val updated = when (change) {
            is DirectAckBookkeeping.RetryStarted ->
                if (row.status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) row else
                    row.copy(status = MessageStatus.RETRYING, retryAttempt = change.retryAttempt, maxRetryAttempts = change.maxRetryAttempts)
            DirectAckBookkeeping.RetryExhausted ->
                if (row.status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) row else row.copy(status = MessageStatus.SENT)
            DirectAckBookkeeping.ResendCountIncrement -> row.copy(sendCount = Math.incrementExact(row.sendCount))
        }
        if (updated != row) commit(value, updated)
        value.receipt = value.receipt.copy(bookkeeping =
            (value.receipt.bookkeeping + DirectAckBookkeepingReceipt(requestId, change)).snapshot())
        return if (row.status == MessageStatus.DELIVERED && change != DirectAckBookkeeping.ResendCountIncrement) {
            DirectAckMutationResult.AlreadyDelivered(receipt(value))
        } else DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun completeDirectPending(
        attempt: DirectAckAttempt, pendingSendId: UUID, requestId: UUID,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        value.receipt.pendingCompletions.firstOrNull { it.requestId == requestId }?.let {
            return if (it.pendingSendId == pendingSendId) DirectAckMutationResult.AlreadyApplied(receipt(value))
            else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(value)?.let { return it }
        store.authorityAccess("deletePendingSendsForMessage")
        rejection(value)?.let { return it }
        val key = EntityKey(value.receipt.key.radioId, pendingSendId)
        if (pendingSendId != value.pendingID || store.pendingRevision(key) != value.pendingRevision ||
            store.pending[key]?.messageID != value.receipt.key.id) {
            return DirectAckMutationResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        store.pending.remove(key)
        store.invalidatePending(key, invalidateMessage = false)
        value.revision = store.messageRevision(value.receipt.key)
        value.receipt = value.receipt.copy(pendingCompletions =
            (value.receipt.pendingCompletions + DirectAckPendingReceipt(requestId, pendingSendId)).snapshot())
        return DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun inspectDirectAckAttempt(lease: DirectAckLease, requestId: UUID): DirectAckObservation {
        val value = attempts[requestId]?.takeIf { it.lease === lease }
            ?: return DirectAckObservation.Rejected(DirectAckRejection.MISSING)
        return DirectAckObservation.Found(receipt(value))
    }

    override suspend fun revokeDirectAckSession(lease: DirectAckLease): DirectAckRevocationResult {
        val owner = (lease as? Lease)?.takeIf { leases[it.requestID] === it }
            ?: return DirectAckRevocationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        return if (revoked.add(owner)) DirectAckRevocationResult.Revoked else DirectAckRevocationResult.AlreadyRevoked
    }

    private fun completionPermission(value: Completion): DirectPendingCompletionPermission {
        val key = value.receipt.key
        val pendingKey = EntityKey(key.radioId, value.receipt.pending.id)
        return when {
            currentLeases[key.radioId] !== value.lease || value.lease in revoked ->
                DirectPendingCompletionPermission.STALE_LEASE
            store.messages[key] == null || store.pending[pendingKey] == null -> DirectPendingCompletionPermission.MISSING
            store.messageRevision(key) != value.messageRevision ||
                store.pendingRevision(pendingKey) != value.pendingRevision -> DirectPendingCompletionPermission.INVALIDATED
            else -> DirectPendingCompletionPermission.CURRENT
        }
    }

    private fun completionReceipt(value: Completion) = value.receipt.copy(permission = completionPermission(value))

    override suspend fun captureDirectPendingCompletion(
        lease: DirectAckLease, key: EntityKey, pendingSendId: UUID, captureRequestId: UUID,
        reason: DirectPendingCompletionReason,
    ): DirectPendingCaptureResult {
        val owner = (lease as? Lease)?.takeIf { currentLeases[key.radioId] === it && it !in revoked }
            ?: return DirectPendingCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        completions[captureRequestId]?.let {
            if (it.lease !== owner || it.receipt.key != key || it.receipt.pending.id != pendingSendId ||
                it.receipt.reason != reason) return DirectPendingCaptureResult.Rejected(DirectAckRejection.CONFLICT)
            return if (completionPermission(it) == DirectPendingCompletionPermission.CURRENT) {
                DirectPendingCaptureResult.AlreadyCaptured(it, completionReceipt(it))
            } else DirectPendingCaptureResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        val message = store.messages[key] ?: return DirectPendingCaptureResult.Rejected(DirectAckRejection.MISSING)
        val pendingKey = EntityKey(key.radioId, pendingSendId)
        val pending = store.pending[pendingKey] ?: return DirectPendingCaptureResult.Rejected(DirectAckRejection.MISSING)
        val eligible = message.direction == MessageDirection.OUTGOING && message.channelIndex == null &&
            pending.messageID == key.id && pending.kind == PendingSendKind.DM && when (reason) {
                DirectPendingCompletionReason.AlreadyDeliveredSend -> message.status == MessageStatus.DELIVERED && !pending.isResend
                is DirectPendingCompletionReason.ZeroAttempts -> reason.configuredMaximumAttempts == 0L
            }
        if (!eligible) return DirectPendingCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val receipt = DirectPendingCompletionReceipt(
            captureRequestId, key, reason, message, pending, null, DirectPendingCompletionPermission.CURRENT,
        )
        val value = Completion(owner, receipt, store.messageRevision(key), store.pendingRevision(pendingKey))
        completions[captureRequestId] = value
        return DirectPendingCaptureResult.Captured(value, receipt)
    }

    override suspend fun completeDirectPendingWithoutDispatch(
        completion: DirectPendingCompletion, completionRequestId: UUID,
    ): DirectPendingCompletionResult {
        val value = (completion as? Completion)?.takeIf { completions[it.receipt.captureRequestId] === it }
            ?: return DirectPendingCompletionResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        value.receipt.completedRequestId?.let {
            return if (it == completionRequestId) DirectPendingCompletionResult.AlreadyApplied(completionReceipt(value))
            else DirectPendingCompletionResult.Rejected(DirectAckRejection.CONFLICT)
        }
        if (completionPermission(value) != DirectPendingCompletionPermission.CURRENT) {
            return DirectPendingCompletionResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        store.authorityAccess("deletePendingSendsForMessage")
        if (completionPermission(value) != DirectPendingCompletionPermission.CURRENT) {
            return DirectPendingCompletionResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        store.pending.remove(EntityKey(value.receipt.key.radioId, value.receipt.pending.id))
        value.receipt = value.receipt.copy(completedRequestId = completionRequestId)
        return DirectPendingCompletionResult.Applied(completionReceipt(value))
    }

    override suspend fun inspectDirectPendingCompletion(
        lease: DirectAckLease, captureRequestId: UUID,
    ): DirectPendingCompletionObservation {
        val value = completions[captureRequestId]?.takeIf { it.lease === lease }
            ?: return DirectPendingCompletionObservation.Rejected(DirectAckRejection.MISSING)
        return DirectPendingCompletionObservation.Found(completionReceipt(value))
    }
}
