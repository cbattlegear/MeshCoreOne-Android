// AndroidOnly: WP-208 In-memory authority fixture; not Room, durable history or process-death evidence.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import java.util.UUID

internal class RecordingDirectAckAuthority(private val store: RecordingStore) :
    DirectAckAuthority, DirectPendingCompletionAuthority, DirectQueueAuthority, DirectMessageCreationAuthority {
    private class Lease(val token: SessionToken, val requestID: UUID) : DirectAckLease
    private class Attempt(
        val lease: Lease,
        var receipt: DirectAckReceipt,
        var revision: Long,
        val messageIncarnation: Long,
        val pendingID: UUID?,
        val pendingRevision: Long?,
    ) : DirectAckAttempt

    private val currentLeases = mutableMapOf<RadioId, Lease>()
    private val leases = mutableMapOf<UUID, Lease>()
    private val attempts = mutableMapOf<UUID, Attempt>()
    private val explicitRetries = mutableMapOf<UUID, Pair<UUID, Long>>()
    private val definitelyUnsent = mutableMapOf<UUID, Pair<UUID, UUID>>()
    private val current = mutableMapOf<EntityKey, Attempt>()
    private val revoked = mutableSetOf<Lease>()
    private class Intent(
        val lease: Lease, val receipt: DirectAckIntentReceipt,
        val revision: Long, val pendingRevision: Long?, var current: Boolean = true,
    ) : DirectAckIntent
    private val intents = mutableMapOf<UUID, Intent>()
    var afterFailure: (suspend (DirectAckReceipt) -> Unit)? = null
    private class Completion(
        val lease: Lease,
        var receipt: DirectPendingCompletionReceipt,
        val messageRevision: Long,
        val pendingRevision: Long,
        val queueScoped: Boolean,
    ) : DirectPendingCompletion
    private val completions = mutableMapOf<UUID, Completion>()
    private class QueueSelection(
        val lease: Lease, val key: EntityKey, val pendingID: UUID,
        val pendingRevision: Long, val messageRevision: Long, val messageIncarnation: Long, val messageExists: Boolean,
        val requestID: UUID = UUID.randomUUID(),
    ) : DirectQueueSelection
    private val queueOperations = mutableMapOf<Pair<QueueSelection, UUID>, Pair<DirectQueueMutation, DirectQueueMutationReceipt>>()
    private val queueBumps = mutableMapOf<Pair<QueueSelection, UUID>, DirectQueueBumpReceipt>()
    private val queuePreparations = mutableMapOf<Pair<UUID, UUID>,
        Pair<Pair<UInt?, Boolean>, DirectQueuePreparationReceipt>>()
    private class Creation(
        val lease: Lease, val key: EntityKey, val requestID: UUID, val claimID: UUID,
        val incarnation: Long, val revision: Long,
    ) : DirectMessageCreation
    private val creations = mutableMapOf<UUID, Creation>()
    private val creationFailures = mutableMapOf<Pair<UUID, UUID>, Pair<Boolean, DirectMessageCreationFailureReceipt>>()
    private val creationResolutions = mutableMapOf<Pair<UUID, UUID>, Triple<Boolean, Attempt?, Boolean>>()

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

    override suspend fun createDirectMessage(
        lease: DirectAckLease, message: MessageDTO, creationRequestId: UUID, claimRequestId: UUID,
    ): DirectMessageCreationResult {
        val key = EntityKey(message.radioId, message.id)
        if (creations.containsKey(creationRequestId)) {
            val result = recoverDirectMessageCreation(lease, key, creationRequestId, claimRequestId)
            return if (result is DirectMessageCreationResult.AlreadyCreated && store.messages[key] != message) {
                DirectMessageCreationResult.Rejected(DirectAckRejection.CONFLICT)
            } else result
        }
        val owner = (lease as? Lease)?.takeIf { currentLeases[key.radioId] === it && it !in revoked }
            ?: return DirectMessageCreationResult.Rejected(DirectAckRejection.STALE_LEASE)
        store.authorityAccess("saveMessage")
        if (currentLeases[key.radioId] !== owner || owner in revoked) {
            return DirectMessageCreationResult.Rejected(DirectAckRejection.STALE_LEASE)
        }
        if (store.messages[key] != null) return DirectMessageCreationResult.Rejected(DirectAckRejection.CONFLICT)
        if (message.direction != MessageDirection.OUTGOING || message.channelIndex != null ||
            message.contactID == null || message.status != MessageStatus.PENDING) {
            return DirectMessageCreationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        store.messages[key] = message
        val value = Creation(owner, key, creationRequestId, claimRequestId,
            store.messageIncarnation(key), store.messageRevision(key))
        creations[creationRequestId] = value
        return DirectMessageCreationResult.Created(value)
    }

    override suspend fun recoverDirectMessageCreation(
        lease: DirectAckLease, key: EntityKey, creationRequestId: UUID, claimRequestId: UUID,
    ): DirectMessageCreationResult = recoverCreation(lease, key, creationRequestId, claimRequestId, false)

    private fun recoverCreation(
        lease: DirectAckLease, key: EntityKey, creationRequestId: UUID, claimRequestId: UUID,
        allowSameIncarnationEdits: Boolean,
    ): DirectMessageCreationResult {
        val value = creations[creationRequestId] ?: return DirectMessageCreationResult.Rejected(DirectAckRejection.MISSING)
        if (value.lease !== lease || value.key != key || value.claimID != claimRequestId) {
            return DirectMessageCreationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        if (currentLeases[key.radioId] !== value.lease || value.lease in revoked) {
            return DirectMessageCreationResult.Rejected(DirectAckRejection.STALE_LEASE)
        }
        if (store.messages[key] == null) return DirectMessageCreationResult.Rejected(DirectAckRejection.MISSING)
        if (store.messageIncarnation(key) != value.incarnation ||
            (!allowSameIncarnationEdits && store.messageRevision(key) != value.revision)) {
            return DirectMessageCreationResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        return DirectMessageCreationResult.AlreadyCreated(value)
    }

    override suspend fun failDirectMessageCreation(
        creation: DirectMessageCreation, failureRequestId: UUID,
    ): DirectMessageCreationFailureResult = failCreation(creation, failureRequestId, false)

    override suspend fun failCreatedDirectRetryMessage(
        creation: DirectMessageCreation, failureRequestId: UUID,
    ): DirectMessageCreationFailureResult = failCreation(creation, failureRequestId, true)

    private suspend fun failCreation(
        creation: DirectMessageCreation, failureRequestId: UUID, allowSameIncarnationEdits: Boolean,
    ): DirectMessageCreationFailureResult {
        val value = creation as? Creation
            ?: return DirectMessageCreationFailureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        creationFailures[value.requestID to failureRequestId]?.let { (allowedEdits, receipt) ->
            return if (allowedEdits == allowSameIncarnationEdits) DirectMessageCreationFailureResult.AlreadyApplied(receipt)
            else DirectMessageCreationFailureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val recovered = recoverCreation(
            value.lease, value.key, value.requestID, value.claimID, allowSameIncarnationEdits,
        )
        if (recovered is DirectMessageCreationResult.Rejected) {
            return DirectMessageCreationFailureResult.Rejected(recovered.reason)
        }
        if (current[value.key]?.let { permission(it) == DirectAckPermission.CURRENT } == true ||
            intents.values.any { it.receipt.key == value.key && it.receipt.requestId != value.claimID &&
                intentPermission(it) == DirectAckPermission.CURRENT }
        ) return DirectMessageCreationFailureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        var row = requireNotNull(store.messages[value.key])
        var changed = row.status !in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)
        if (changed) {
            store.authorityAccess("updateMessageStatusUnlessDelivered")
            val checked = recoverCreation(
                value.lease, value.key, value.requestID, value.claimID, allowSameIncarnationEdits,
            )
            if (checked is DirectMessageCreationResult.Rejected) {
                return DirectMessageCreationFailureResult.Rejected(checked.reason)
            }
            if (current[value.key]?.let { permission(it) == DirectAckPermission.CURRENT } == true ||
                intents.values.any { it.receipt.key == value.key && it.receipt.requestId != value.claimID &&
                    intentPermission(it) == DirectAckPermission.CURRENT }
            ) return DirectMessageCreationFailureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
            row = requireNotNull(store.messages[value.key])
            changed = row.status !in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)
            if (changed) store.messages[value.key] = row.copy(status = MessageStatus.FAILED)
        }
        val result = DirectMessageCreationFailureReceipt(if (changed) MessageStatus.FAILED else row.status, changed)
        creationFailures[value.requestID to failureRequestId] = allowSameIncarnationEdits to result
        return DirectMessageCreationFailureResult.Applied(result)
    }

    override suspend fun captureCreatedDirectIntent(creation: DirectMessageCreation): DirectAckIntentResult {
        val value = (creation as? Creation)?.takeIf { creations[it.requestID] === it }
            ?: return DirectAckIntentResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val recovered = recoverDirectMessageCreation(value.lease, value.key, value.requestID, value.claimID)
        if (recovered is DirectMessageCreationResult.Rejected) return DirectAckIntentResult.Rejected(recovered.reason)
        return captureDirectAckIntent(value.lease, value.key, value.claimID, DirectAckMode.SEND, null)
    }

    override suspend fun captureCreatedDirectRetryIntent(
        creation: DirectMessageCreation, mode: DirectAckMode,
    ): DirectAckIntentResult {
        val value = (creation as? Creation)?.takeIf { creations[it.requestID] === it }
            ?: return DirectAckIntentResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val recovered = recoverCreation(value.lease, value.key, value.requestID, value.claimID, true)
        if (recovered is DirectMessageCreationResult.Rejected) return DirectAckIntentResult.Rejected(recovered.reason)
        return captureDirectAckIntent(value.lease, value.key, value.claimID, mode, null)
    }

    override suspend fun resolveCreatedDirectWithoutDispatch(
        creation: DirectMessageCreation, requestId: UUID, resolveToSent: Boolean, attempt: DirectAckAttempt?,
    ): DirectMessageCreationResolutionResult {
        val value = (creation as? Creation)?.takeIf { creations[it.requestID] === it }
            ?: return DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val expected = attempt?.let { resolve(it) }
        if (attempt != null && (expected == null || expected.lease !== value.lease)) {
            return DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        if (!resolveToSent && expected != null) {
            return DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.CONFLICT)
        }
        fun validate(): DirectMessageCreationResolutionResult.Rejected? {
            val recovered = recoverCreation(value.lease, value.key, value.requestID, value.claimID, true)
            return if (recovered is DirectMessageCreationResult.Rejected) {
                DirectMessageCreationResolutionResult.Rejected(recovered.reason)
            } else null
        }
        validate()?.let { return it }
        var row = requireNotNull(store.messages[value.key])
        if (row.direction != MessageDirection.OUTGOING || row.channelIndex != null || row.contactID == null) {
            return DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val previous = creationResolutions[value.requestID to requestId]
        if (previous != null) {
            if (previous.first != resolveToSent || previous.second !== expected) {
                return DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.CONFLICT)
            }
            return DirectMessageCreationResolutionResult.AlreadyApplied(
                DirectMessageCreationResolutionReceipt(row, previous.third),
            )
        }
        fun competing(): Boolean = if (expected == null) hasCompetingQueueAuthority(value.key)
        else expected.receipt.key != value.key || permission(expected) != DirectAckPermission.CURRENT ||
            hasCompetingIntent(expected)
        var changed = resolveToSent && row.status !in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)
        if (changed) {
            if (competing()) return DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
            store.authorityAccess("clearRetryingToSent")
            validate()?.let { return it }
            if (competing()) return DirectMessageCreationResolutionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
            row = requireNotNull(store.messages[value.key])
            changed = row.status !in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)
            if (changed) {
                row = row.copy(status = MessageStatus.SENT)
                if (expected == null) store.messages[value.key] = row else commit(expected, row)
            }
        }
        creationResolutions[value.requestID to requestId] = Triple(resolveToSent, expected, changed)
        return DirectMessageCreationResolutionResult.Applied(DirectMessageCreationResolutionReceipt(row, changed))
    }

    private fun intentPermission(value: Intent): DirectAckPermission = when {
        currentLeases[value.receipt.key.radioId] !== value.lease || value.lease in revoked -> DirectAckPermission.STALE_LEASE
        store.messages[value.receipt.key] == null -> DirectAckPermission.MISSING
        !value.current || store.messageRevision(value.receipt.key) != value.revision ||
            value.receipt.pendingSendId?.let {
                store.pendingRevision(EntityKey(value.receipt.key.radioId, it)) != value.pendingRevision
            } == true -> DirectAckPermission.INVALIDATED
        else -> DirectAckPermission.CURRENT
    }

    private fun hasCompetingIntent(attempt: Attempt): Boolean = intents.values.any {
        it.receipt.key == attempt.receipt.key && it.receipt.requestId != attempt.receipt.requestId &&
            intentPermission(it) == DirectAckPermission.CURRENT
    }

    internal fun recordExplicitRetry(key: EntityKey, pendingID: UUID) {
        val revision = store.pendingRevision(EntityKey(key.radioId, pendingID))
        attempts.values.filter {
            it.receipt.key == key && it.pendingID != null &&
                it.messageIncarnation == store.messageIncarnation(key) && it.receipt.pendingCompletions.isEmpty() &&
                it.receipt.definitelyUnsentWireRequestId == null
        }.forEach { explicitRetries[it.receipt.requestId] = pendingID to revision }
    }

    private fun hasUnresolvedQueuedAttempt(key: EntityKey, pendingID: UUID? = null): Boolean = attempts.values.any {
        val retry = explicitRetries[it.receipt.requestId]
        val authorized = pendingID != null && retry == (pendingID to store.pendingRevision(EntityKey(key.radioId, pendingID)))
        it.receipt.key == key && it.pendingID != null && store.messages[key] != null &&
            it.messageIncarnation == store.messageIncarnation(key) && it.receipt.pendingCompletions.isEmpty() &&
            it.receipt.definitelyUnsentWireRequestId == null && !authorized
    }

    private fun hasCompetingQueueAuthority(key: EntityKey, exceptIntent: UUID? = null, pendingID: UUID? = null): Boolean =
        hasUnresolvedQueuedAttempt(key, pendingID) || current[key]?.let {
            permission(it) == DirectAckPermission.CURRENT &&
                it.receipt.phase !in listOf(DirectAckPhase.FAILED, DirectAckPhase.DELIVERED)
        } == true || intents.values.any {
            it.receipt.key == key && it.receipt.requestId != exceptIntent &&
                intentPermission(it) == DirectAckPermission.CURRENT
        }

    override suspend fun captureDirectAckIntent(
        lease: DirectAckLease, key: EntityKey, requestId: UUID, mode: DirectAckMode, pendingSendId: UUID?,
    ): DirectAckIntentResult {
        val owner = (lease as? Lease)?.takeIf { it === currentLeases[key.radioId] && it !in revoked }
            ?: return DirectAckIntentResult.Rejected(DirectAckRejection.STALE_LEASE)
        intents[requestId]?.let {
            if (it.lease !== owner || it.receipt.key != key || it.receipt.mode != mode ||
                it.receipt.pendingSendId != pendingSendId) return DirectAckIntentResult.Rejected(DirectAckRejection.CONFLICT)
            return if (intentPermission(it) == DirectAckPermission.CURRENT) DirectAckIntentResult.AlreadyCaptured(it, it.receipt)
            else DirectAckIntentResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        val message = store.messages[key] ?: return DirectAckIntentResult.Rejected(DirectAckRejection.MISSING)
        if (message.channelIndex != null || message.direction != MessageDirection.OUTGOING ||
            mode == DirectAckMode.SEND && message.status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) {
            return DirectAckIntentResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val pending = pendingSendId?.let { store.pending[EntityKey(key.radioId, it)] }
        if (pendingSendId != null && pending?.messageID != key.id) {
            return DirectAckIntentResult.Rejected(DirectAckRejection.MISSING)
        }
        val receipt = DirectAckIntentReceipt(key, requestId, mode, message, pendingSendId, pending, DirectAckPermission.CURRENT)
        val intent = Intent(owner, receipt, store.messageRevision(key),
            pendingSendId?.let { store.pendingRevision(EntityKey(key.radioId, it)) })
        intents[requestId] = intent
        return DirectAckIntentResult.Captured(intent, receipt)
    }

    override suspend fun inspectDirectAckIntent(lease: DirectAckLease, requestId: UUID): DirectAckIntentObservation {
        val value = intents[requestId]?.takeIf { it.lease === lease }
            ?: return DirectAckIntentObservation.Rejected(DirectAckRejection.MISSING)
        return DirectAckIntentObservation.Found(value.receipt.copy(permission = intentPermission(value)))
    }

    override suspend fun retireDirectAckIntent(lease: DirectAckLease, requestId: UUID): DirectAckIntentRetirement {
        val owner = lease as? Lease ?: return DirectAckIntentRetirement.Rejected(DirectAckRejection.INVALID_HANDLE)
        val value = intents[requestId] ?: return DirectAckIntentRetirement.NotCurrent
        if (value.lease !== owner) return DirectAckIntentRetirement.Rejected(DirectAckRejection.CONFLICT)
        if (attempts[value.receipt.requestId] != null) return DirectAckIntentRetirement.AttemptBegun
        if (intentPermission(value) != DirectAckPermission.CURRENT) return DirectAckIntentRetirement.NotCurrent
        value.current = false
        return DirectAckIntentRetirement.Retired
    }

    override suspend fun beginDirectAckAttempt(
        intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
    ): DirectAckBeginResult {
        val captured = (intent as? Intent)?.takeIf { intents[it.receipt.requestId] === it }
            ?: return DirectAckBeginResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val owner = captured.lease
        val key = captured.receipt.key
        val requestId = captured.receipt.requestId
        val mode = captured.receipt.mode
        val pendingSendId = captured.receipt.pendingSendId
        attempts[requestId]?.let {
            val first = it.receipt.wires.first()
            if (it.lease !== owner || it.receipt.key != key || it.receipt.mode != mode || it.pendingID != pendingSendId ||
                first.requestId != firstWireRequestId || first.predictedAckCode != predictedAckCode) {
                return DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
            }
            return if (permission(it) == DirectAckPermission.CURRENT) DirectAckBeginResult.AlreadyBegun(it, receipt(it))
            else DirectAckBeginResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        when (intentPermission(captured)) {
            DirectAckPermission.STALE_LEASE -> return DirectAckBeginResult.Rejected(DirectAckRejection.STALE_LEASE)
            DirectAckPermission.MISSING -> return DirectAckBeginResult.Rejected(DirectAckRejection.MISSING)
            DirectAckPermission.INVALIDATED -> return DirectAckBeginResult.Rejected(DirectAckRejection.INVALIDATED)
            DirectAckPermission.CURRENT -> Unit
        }
        if (pendingSendId != null && hasUnresolvedQueuedAttempt(key, pendingSendId)) {
            return DirectAckBeginResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
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
        ), store.messageRevision(key), store.messageIncarnation(key), pendingSendId,
            pendingSendId?.let { store.pendingRevision(EntityKey(key.radioId, it)) })
        intents.values.filter { it.receipt.key == key }.forEach { it.current = false }
        attempts[requestId] = value
        current[key] = value
        return DirectAckBeginResult.Begun(value, receipt(value))
    }

    override suspend fun registerDirectAckDispatch(
        attempt: DirectAckAttempt, wireRequestId: UUID, predictedAckCode: UInt,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        value.receipt.wires.firstOrNull { it.requestId == wireRequestId }?.let {
            return if (it.predictedAckCode == predictedAckCode) DirectAckMutationResult.AlreadyApplied(receipt(value))
            else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(value)?.let { return it }
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
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
        store.authorityAccess("recordDirectSendAccepted")
        rejection(value)?.let { return it }
        if (value.receipt.phase == DirectAckPhase.REGISTERED) {
            value.receipt = value.receipt.copy(phase = DirectAckPhase.ACCEPTED)
        }
        value.receipt = value.receipt.copy(wires = value.receipt.wires.map {
            if (it.requestId == wireRequestId) it.copy(acceptedAckCode = ackCode) else it
        }.snapshot())
        return DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun retireDefinitelyUnsentQueuedAttempt(
        attempt: DirectAckAttempt, wireRequestId: UUID, requestId: UUID,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        definitelyUnsent[value.receipt.requestId]?.let { (priorRequest, priorWire) ->
            if (priorRequest == requestId) {
                return if (priorWire == wireRequestId) DirectAckMutationResult.AlreadyApplied(receipt(value))
                else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
            }
        }
        rejection(value)?.let { return it }
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val wires = value.receipt.wires
        if (value.pendingID == null || value.receipt.phase != DirectAckPhase.REGISTERED ||
            wires.size != 1 || wires.single().requestId != wireRequestId || wires.single().acceptedAckCode != null ||
            value.receipt.delivery != null
        ) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        store.authorityAccess("retireDefinitelyUnsentQueuedAttempt")
        rejection(value)?.let { return it }
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        definitelyUnsent[value.receipt.requestId] = requestId to wireRequestId
        value.receipt = value.receipt.copy(definitelyUnsentWireRequestId = wireRequestId)
        if (current[value.receipt.key] === value) current.remove(value.receipt.key)
        return DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun recordDirectFailure(attempt: DirectAckAttempt): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        rejection(value)?.let { return it }
        if (value.receipt.phase == DirectAckPhase.DELIVERED || value.receipt.initialStatus == MessageStatus.DELIVERED) {
            return DirectAckMutationResult.AlreadyDelivered(receipt(value))
        }
        if (value.receipt.phase == DirectAckPhase.FAILED) return DirectAckMutationResult.AlreadyApplied(receipt(value))
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        store.authorityAccess("updateMessageStatusUnlessDelivered")
        rejection(value)?.let { return it }
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
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
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (value.receipt.wires.none { it.predictedAckCode == ackCode || it.acceptedAckCode == ackCode }) {
            return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (attempts.values.any { other ->
                other !== value && other.receipt.key == value.receipt.key &&
                    other.messageIncarnation == value.messageIncarnation && other.receipt.wires.any {
                        it.requestId != other.receipt.definitelyUnsentWireRequestId &&
                            (it.predictedAckCode == ackCode || it.acceptedAckCode == ackCode)
                    }
            }
        ) return DirectAckMutationResult.Rejected(DirectAckRejection.AMBIGUOUS_ACK)
        store.authorityAccess("updateMessageAck")
        rejection(value)?.let { return it }
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
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
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val operation = when (change) {
            is DirectAckBookkeeping.SendCompleted -> "updateMessageAck"
            is DirectAckBookkeeping.RetryStarted -> "updateMessageRetryStatus"
            DirectAckBookkeeping.RetryExhausted -> "clearRetryingToSent"
            DirectAckBookkeeping.ReturnToPending -> "updateMessageStatusUnlessDelivered"
            DirectAckBookkeeping.ResendCountIncrement -> "incrementMessageSendCount"
        }
        store.authorityAccess(operation)
        rejection(value)?.let { return it }
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val row = requireNotNull(store.messages[value.receipt.key])
        if (row.status == MessageStatus.FAILED && change != DirectAckBookkeeping.ResendCountIncrement &&
            change != DirectAckBookkeeping.ReturnToPending) {
            return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (row.status == MessageStatus.DELIVERED && change != DirectAckBookkeeping.ResendCountIncrement) {
            return DirectAckMutationResult.AlreadyDelivered(receipt(value))
        }
        val updated = when (change) {
            is DirectAckBookkeeping.SendCompleted -> {
                val code = value.receipt.wires.firstOrNull {
                    it.requestId == change.wireRequestId
                }?.acceptedAckCode ?: return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                if (row.status != MessageStatus.PENDING) {
                    return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
                row.copy(status = MessageStatus.SENT, ackCode = code)
            }
            is DirectAckBookkeeping.RetryStarted ->
                if (row.status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) row else
                    row.copy(status = MessageStatus.RETRYING, retryAttempt = change.retryAttempt, maxRetryAttempts = change.maxRetryAttempts)
            DirectAckBookkeeping.RetryExhausted ->
                if (row.status in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)) row else row.copy(status = MessageStatus.SENT)
            DirectAckBookkeeping.ReturnToPending -> row.copy(status = MessageStatus.PENDING)
            DirectAckBookkeeping.ResendCountIncrement -> row.copy(sendCount = Math.incrementExact(row.sendCount))
        }
        if (updated != row) commit(value, updated)
        value.receipt = value.receipt.copy(bookkeeping =
            (value.receipt.bookkeeping + DirectAckBookkeepingReceipt(requestId, change)).snapshot())
        return DirectAckMutationResult.Applied(receipt(value))
    }

    override suspend fun completeDirectPending(
        attempt: DirectAckAttempt, pendingSendId: UUID, requestId: UUID,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        value.receipt.pendingCompletions.firstOrNull { it.requestId == requestId }?.let {
            return if (it.pendingSendId == pendingSendId) DirectAckMutationResult.AlreadyApplied(receipt(value))
            else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        if (pendingSendId != value.pendingID) return DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        rejection(value)?.let { return it }
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (value.receipt.pendingCompletions.isNotEmpty()) return DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        if (value.receipt.wires.none { it.acceptedAckCode != null } && value.receipt.delivery == null) {
            return DirectAckMutationResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        store.authorityAccess("deletePendingSendsForMessage")
        rejection(value)?.let { return it }
        if (hasCompetingIntent(value)) return DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
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
        store.authorityAccess("inspectDirectAckAttempt")
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
        val pendingKey = EntityKey(key.radioId, value.receipt.pendingSendId)
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
    ): DirectPendingCaptureResult = capturePendingCompletion(
        lease, key, pendingSendId, captureRequestId, reason, queueScoped = false,
    )

    private suspend fun capturePendingCompletion(
        lease: DirectAckLease, key: EntityKey, pendingSendId: UUID, captureRequestId: UUID,
        reason: DirectPendingCompletionReason, queueScoped: Boolean,
    ): DirectPendingCaptureResult {
        val owner = (lease as? Lease)?.takeIf { currentLeases[key.radioId] === it && it !in revoked }
            ?: return DirectPendingCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        completions[captureRequestId]?.let {
            if (it.lease !== owner || it.receipt.key != key || it.receipt.pendingSendId != pendingSendId ||
                it.receipt.reason != reason || it.queueScoped != queueScoped) {
                return DirectPendingCaptureResult.Rejected(DirectAckRejection.CONFLICT)
            }
            return if (completionPermission(it) == DirectPendingCompletionPermission.CURRENT) {
                DirectPendingCaptureResult.AlreadyCaptured(it, completionReceipt(it))
            } else DirectPendingCaptureResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        if (queueScoped && hasCompetingQueueAuthority(key, pendingID = pendingSendId)) {
            return DirectPendingCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
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
            captureRequestId, key, reason, message, pendingSendId, pending, null, DirectPendingCompletionPermission.CURRENT,
        )
        val value = Completion(owner, receipt, store.messageRevision(key), store.pendingRevision(pendingKey), queueScoped)
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
        if (value.queueScoped && hasCompetingQueueAuthority(value.receipt.key, pendingID = value.receipt.pendingSendId)) {
            return DirectPendingCompletionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        store.authorityAccess("deletePendingSendsForMessage")
        if (completionPermission(value) != DirectPendingCompletionPermission.CURRENT) {
            return DirectPendingCompletionResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        if (value.queueScoped && hasCompetingQueueAuthority(value.receipt.key, pendingID = value.receipt.pendingSendId)) {
            return DirectPendingCompletionResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        store.pending.remove(EntityKey(value.receipt.key.radioId, value.receipt.pendingSendId))
        value.receipt = value.receipt.copy(completedRequestId = completionRequestId, pending = null)
        return DirectPendingCompletionResult.Applied(completionReceipt(value))
    }

    override suspend fun inspectDirectPendingCompletion(
        lease: DirectAckLease, captureRequestId: UUID,
    ): DirectPendingCompletionObservation {
        val value = completions[captureRequestId]?.takeIf { it.lease === lease }
            ?: return DirectPendingCompletionObservation.Rejected(DirectAckRejection.MISSING)
        return DirectPendingCompletionObservation.Found(completionReceipt(value))
    }

    override suspend fun captureDirectQueueSelection(
        lease: DirectAckLease, key: EntityKey, pendingSendId: UUID,
    ): DirectQueueCaptureResult {
        val owner = (lease as? Lease)?.takeIf { currentLeases[key.radioId] === it && it !in revoked }
            ?: return DirectQueueCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        val pendingKey = EntityKey(key.radioId, pendingSendId)
        val pending = store.pending[pendingKey] ?: return DirectQueueCaptureResult.Rejected(DirectAckRejection.MISSING)
        val message = store.messages[key]
        if (pending.messageID != key.id || pending.kind != PendingSendKind.DM || pending.contactID == null ||
            message != null && (message.direction != MessageDirection.OUTGOING ||
                message.channelIndex != null || message.contactID != pending.contactID)
        ) return DirectQueueCaptureResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (hasUnresolvedQueuedAttempt(key, pendingSendId)) {
            return DirectQueueCaptureResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }

        return DirectQueueCaptureResult.Captured(
            QueueSelection(owner, key, pendingSendId, store.pendingRevision(pendingKey),
                store.messageRevision(key), store.messageIncarnation(key), message != null),
            pending,
        )
    }

    override fun compareDirectQueueSelections(
        first: DirectQueueSelection, second: DirectQueueSelection,
    ): DirectQueueIncarnationComparison {
        val left = first as? QueueSelection
        val right = second as? QueueSelection
        if (left == null || right == null || leases[left.lease.requestID] !== left.lease ||
            leases[right.lease.requestID] !== right.lease
        ) return DirectQueueIncarnationComparison.Rejected(DirectAckRejection.INVALID_HANDLE)
        return if (left.lease === right.lease && left.key == right.key && left.pendingID == right.pendingID &&
            left.pendingRevision == right.pendingRevision
        ) DirectQueueIncarnationComparison.Same else DirectQueueIncarnationComparison.Different
    }

    override suspend fun mutateDirectQueueSelection(
        selection: DirectQueueSelection, requestId: UUID, mutation: DirectQueueMutation,
    ): DirectQueueMutationResult {
        val selected = selection as? QueueSelection
            ?: return DirectQueueMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (queueBumps.containsKey(selected to requestId)) {
            return DirectQueueMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        queueOperations[selected to requestId]?.let { (previous, receipt) ->
            return if (previous == mutation) DirectQueueMutationResult.AlreadyApplied(receipt)
            else DirectQueueMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        fun current(): Boolean {
            val pendingKey = EntityKey(selected.key.radioId, selected.pendingID)
            return currentLeases[selected.key.radioId] === selected.lease && selected.lease !in revoked &&
                store.pending[pendingKey]?.messageID == selected.key.id &&
                store.pendingRevision(pendingKey) == selected.pendingRevision &&
                store.messageRevision(selected.key) == selected.messageRevision &&
                (store.messages[selected.key] != null) == selected.messageExists
        }
        if (!current()) return DirectQueueMutationResult.Rejected(DirectAckRejection.INVALIDATED)
        val pendingKey = EntityKey(selected.key.radioId, selected.pendingID)
        val pending = requireNotNull(store.pending[pendingKey])
        fun hasCompetingAuthority(): Boolean {
            val ownClaim = (mutation as? DirectQueueMutation.DefinitelyUnsentFailure)?.claimRequestId
            return current[selected.key]?.let { permission(it) == DirectAckPermission.CURRENT } == true ||
                intents.values.any {
                    it.receipt.key == selected.key && it.receipt.requestId != ownClaim &&
                        intentPermission(it) == DirectAckPermission.CURRENT
                }
        }
        when (mutation) {
            DirectQueueMutation.MissingContact ->
                if (store.contacts[EntityKey(selected.key.radioId, requireNotNull(pending.contactID))] != null) {
                    return DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
            is DirectQueueMutation.DefinitelyUnsentFailure ->
                if (attempts[mutation.claimRequestId] != null) {
                    return DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
            DirectQueueMutation.ReturnToPending -> Unit
        }
        if (hasUnresolvedQueuedAttempt(selected.key, selected.pendingID)) {
            return DirectQueueMutationResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        if (hasCompetingAuthority()) return DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val row = store.messages[selected.key]
        val target = if (mutation == DirectQueueMutation.ReturnToPending) MessageStatus.PENDING else MessageStatus.FAILED
        val changed = row != null && row.status != MessageStatus.DELIVERED &&
            !(mutation == DirectQueueMutation.ReturnToPending && row.status == MessageStatus.PENDING)
        val removed = mutation != DirectQueueMutation.ReturnToPending
        if (changed) store.authorityAccess("updateMessageStatusUnlessDelivered")
        if (removed) store.authorityAccess("deletePendingSendsForMessage")
        if (!current()) return DirectQueueMutationResult.Rejected(DirectAckRejection.INVALIDATED)
        if (mutation == DirectQueueMutation.MissingContact &&
            store.contacts[EntityKey(selected.key.radioId, requireNotNull(pending.contactID))] != null
        ) return DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (mutation is DirectQueueMutation.DefinitelyUnsentFailure && attempts[mutation.claimRequestId] != null) {
            return DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (hasUnresolvedQueuedAttempt(selected.key, selected.pendingID)) {
            return DirectQueueMutationResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        if (hasCompetingAuthority()) return DirectQueueMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (changed) store.messages[selected.key] = requireNotNull(row).copy(status = target)
        if (removed) store.pending.remove(pendingKey)
        val receipt = DirectQueueMutationReceipt(selected.pendingID, if (changed) target else row?.status, changed, removed)
        queueOperations[selected to requestId] = mutation to receipt
        return DirectQueueMutationResult.Applied(receipt)
    }

    override suspend fun bumpDirectQueueSelection(
        selection: DirectQueueSelection, requestId: UUID,
    ): DirectQueueBumpResult {
        val selected = selection as? QueueSelection
            ?: return DirectQueueBumpResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (queueOperations.containsKey(selected to requestId)) {
            return DirectQueueBumpResult.Rejected(DirectAckRejection.CONFLICT)
        }
        fun current(value: QueueSelection): Boolean {
            val pendingKey = EntityKey(value.key.radioId, value.pendingID)
            return currentLeases[value.key.radioId] === value.lease && value.lease !in revoked &&
                store.pending[pendingKey]?.messageID == value.key.id &&
                store.pendingRevision(pendingKey) == value.pendingRevision &&
                store.messageRevision(value.key) == value.messageRevision &&
                (store.messages[value.key] != null) == value.messageExists
        }
        if (hasUnresolvedQueuedAttempt(selected.key, selected.pendingID)) {
            return DirectQueueBumpResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        queueBumps[selected to requestId]?.let {
            val renewed = it.selection as QueueSelection
            return if (current(renewed)) DirectQueueBumpResult.AlreadyApplied(it)
            else DirectQueueBumpResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        if (!current(selected)) return DirectQueueBumpResult.Rejected(DirectAckRejection.INVALIDATED)
        if (hasCompetingQueueAuthority(selected.key, pendingID = selected.pendingID)) return DirectQueueBumpResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        store.authorityAccess("incrementPendingSendAttemptCount")
        if (!current(selected)) return DirectQueueBumpResult.Rejected(DirectAckRejection.INVALIDATED)
        if (hasCompetingQueueAuthority(selected.key, pendingID = selected.pendingID)) return DirectQueueBumpResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        val pendingKey = EntityKey(selected.key.radioId, selected.pendingID)
        val pending = requireNotNull(store.pending[pendingKey])
        val grants = explicitRetries.filter { (claim, grant) ->
            attempts[claim]?.receipt?.key == selected.key &&
                grant == (selected.pendingID to store.pendingRevision(pendingKey))
        }.keys.toList()
        val count = Math.incrementExact(pending.attemptCount ?: 0L)
        val updated = pending.copy(attemptCount = count)
        store.pending[pendingKey] = updated
        grants.forEach { explicitRetries[it] = selected.pendingID to store.pendingRevision(pendingKey) }
        val renewed = QueueSelection(selected.lease, selected.key, selected.pendingID,
            store.pendingRevision(pendingKey), store.messageRevision(selected.key),
            store.messageIncarnation(selected.key), selected.messageExists, selected.requestID)
        val receipt = DirectQueueBumpReceipt(renewed, updated, count)
        queueBumps[selected to requestId] = receipt
        return DirectQueueBumpResult.Applied(receipt)
    }

    private fun queueCurrent(selected: QueueSelection, revision: Long? = null): Boolean {
        val pendingKey = EntityKey(selected.key.radioId, selected.pendingID)
        val pending = store.pending[pendingKey]
        val message = store.messages[selected.key]
        return currentLeases[selected.key.radioId] === selected.lease && selected.lease !in revoked &&
            pending?.messageID == selected.key.id && pending.kind == PendingSendKind.DM &&
            store.pendingRevision(pendingKey) == selected.pendingRevision &&
            message != null && message.direction == MessageDirection.OUTGOING && message.channelIndex == null &&
            message.contactID == pending.contactID && store.messageIncarnation(selected.key) == selected.messageIncarnation &&
            (revision == null || store.messageRevision(selected.key) == revision)
    }

    override suspend fun prepareDirectQueueSelection(
        selection: DirectQueueSelection, requestId: UUID, timestamp: UInt?, resolveToSent: Boolean,
    ): DirectQueuePreparationResult {
        val selected = selection as? QueueSelection
            ?: return DirectQueuePreparationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (hasUnresolvedQueuedAttempt(selected.key, selected.pendingID)) {
            return DirectQueuePreparationResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        queuePreparations[selected.requestID to requestId]?.let { (inputs, receipt) ->
            if (inputs != (timestamp to resolveToSent)) {
                return DirectQueuePreparationResult.Rejected(DirectAckRejection.CONFLICT)
            }
            val renewed = receipt.selection as QueueSelection
            return if (queueCurrent(renewed, renewed.messageRevision)) {
                DirectQueuePreparationResult.AlreadyApplied(receipt)
            } else DirectQueuePreparationResult.Rejected(DirectAckRejection.INVALIDATED)
        }
        if (!queueCurrent(selected)) return DirectQueuePreparationResult.Rejected(DirectAckRejection.INVALIDATED)
        if (hasCompetingQueueAuthority(selected.key, pendingID = selected.pendingID)) return DirectQueuePreparationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (timestamp != null) store.authorityAccess("updateMessageTimestamp")
        if (!queueCurrent(selected)) return DirectQueuePreparationResult.Rejected(DirectAckRejection.INVALIDATED)
        if (hasCompetingQueueAuthority(selected.key, pendingID = selected.pendingID)) return DirectQueuePreparationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        var message = requireNotNull(store.messages[selected.key])
        if (timestamp != null) {
            message = message.copy(timestamp = timestamp)
            store.messages[selected.key] = message
        }
        val resolved = resolveToSent && message.status !in listOf(MessageStatus.FAILED, MessageStatus.DELIVERED)
        if (resolved) {
            message = message.copy(status = MessageStatus.SENT)
            store.messages[selected.key] = message
        }
        val renewed = QueueSelection(selected.lease, selected.key, selected.pendingID, selected.pendingRevision,
            store.messageRevision(selected.key), selected.messageIncarnation, true, selected.requestID)
        val receipt = DirectQueuePreparationReceipt(renewed, message, resolved)
        queuePreparations[selected.requestID to requestId] = (timestamp to resolveToSent) to receipt
        return DirectQueuePreparationResult.Applied(receipt)
    }

    override suspend fun captureDirectQueueIntent(
        selection: DirectQueueSelection, requestId: UUID, mode: DirectAckMode,
    ): DirectAckIntentResult {
        val selected = selection as? QueueSelection
            ?: return DirectAckIntentResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (!queueCurrent(selected)) return DirectAckIntentResult.Rejected(DirectAckRejection.INVALIDATED)
        if (hasUnresolvedQueuedAttempt(selected.key, selected.pendingID)) {
            return DirectAckIntentResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        if (hasCompetingQueueAuthority(selected.key, requestId, selected.pendingID)) {
            return DirectAckIntentResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        return captureDirectAckIntent(selected.lease, selected.key, requestId, mode, selected.pendingID)
    }

    override suspend fun captureDirectQueueCompletion(
        selection: DirectQueueSelection, captureRequestId: UUID, reason: DirectPendingCompletionReason,
    ): DirectPendingCaptureResult {
        val selected = selection as? QueueSelection
            ?: return DirectPendingCaptureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (!queueCurrent(selected)) return DirectPendingCaptureResult.Rejected(DirectAckRejection.INVALIDATED)
        if (hasUnresolvedQueuedAttempt(selected.key, selected.pendingID)) {
            return DirectPendingCaptureResult.Rejected(DirectAckRejection.UNRESOLVED_OUTCOME)
        }
        return capturePendingCompletion(selected.lease, selected.key, selected.pendingID, captureRequestId, reason, queueScoped = true)
    }
}
