// AndroidOnly: WP-208 In-memory authority fixture; not Room, durable history or process-death evidence.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class RecordingDirectAckAuthority(private val store: RecordingStore) :
    DirectAckAuthority, DirectPendingCompletionAuthority, DirectAckRadioExclusionAuthority {
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
    private class Intent(
        val lease: Lease, val receipt: DirectAckIntentReceipt,
        val revision: Long, val pendingRevision: Long?,
    ) : DirectAckIntent
    private val intents = mutableMapOf<UUID, Intent>()
    var afterFailure: (suspend (DirectAckReceipt) -> Unit)? = null
    var afterEmissionMark: (suspend (DirectAckEmissionReceipt) -> Unit)? = null
    private class Completion(
        val lease: Lease,
        var receipt: DirectPendingCompletionReceipt,
        val messageRevision: Long,
        val pendingRevision: Long,
    ) : DirectPendingCompletion
    private val completions = mutableMapOf<UUID, Completion>()
    private class Emission(
        val attempt: Attempt,
        var receipt: DirectAckEmissionReceipt,
        val resolutions: MutableMap<UUID, DirectAckEmissionResolution> = mutableMapOf(),
    ) : DirectAckEmissionHandle
    private val emissions = mutableMapOf<UUID, Emission>()
    private val trackedWires = mutableSetOf<Pair<UUID, UUID>>()
    private val saturatedRadios = mutableSetOf<RadioId>()
    private val radioMutations = Mutex()

    suspend fun seedAcceptedFixture(attempt: DirectAckAttempt) {
        val value = requireNotNull(resolve(attempt))
        value.receipt.wires.forEach { wire ->
            val marker = markDirectAckWireMayHaveWritten(attempt, wire.requestId, UUID.randomUUID())
            check(marker is DirectAckEmissionMarkResult.Marked)
            check(resolveDirectAckWireEmission(marker.handle, UUID.randomUUID(),
                DirectAckEmissionResolution.Accepted(wire.predictedAckCode)) is DirectAckEmissionMutationResult.Applied)
        }
    }

    suspend fun seedLegacyFixture(key: EntityKey, predictedCode: UInt, acceptedCode: UInt?): DirectAckAttempt {
        val lease = requireNotNull(currentLeases[key.radioId])
        val intent = captureDirectAckIntent(lease, key, UUID.randomUUID(), DirectAckMode.SEND, null)
        check(intent is DirectAckIntentResult.Captured)
        val wireID = UUID.randomUUID()
        val begun = beginDirectAckAttempt(intent.intent, wireID, predictedCode)
        check(begun is DirectAckBeginResult.Begun)
        if (acceptedCode != null) {
            check(recordDirectSendAccepted(begun.attempt, wireID, acceptedCode) is DirectAckMutationResult.Applied)
        }
        return begun.attempt
    }

    override suspend fun markDirectAckWireMayHaveWritten(
        attempt: DirectAckAttempt, wireRequestId: UUID, emissionRequestId: UUID,
    ): DirectAckEmissionMarkResult = radioMutations.withLock {
        val value = resolve(attempt)
            ?: return@withLock DirectAckEmissionMarkResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        emissions[emissionRequestId]?.let {
            return@withLock if (it.attempt === value && it.receipt.evidence.wireRequestId == wireRequestId) {
                DirectAckEmissionMarkResult.AlreadyMarked(it, it.receipt)
            } else DirectAckEmissionMarkResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(value)?.let { return@withLock DirectAckEmissionMarkResult.Rejected(it.reason) }
        if ((value.receipt.requestId to wireRequestId) !in trackedWires) {
            return@withLock DirectAckEmissionMarkResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        val wire = value.receipt.wires.firstOrNull { it.requestId == wireRequestId }
            ?: return@withLock DirectAckEmissionMarkResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        store.authorityAccess("markDirectAckWireMayHaveWritten")
        rejection(value)?.let { return@withLock DirectAckEmissionMarkResult.Rejected(it.reason) }
        val radio = value.receipt.key.radioId
        if (radio in saturatedRadios) {
            return@withLock DirectAckEmissionMarkResult.CoveredBySaturation(DirectAckRadioSaturationReceipt(radio))
        }
        val receipt = DirectAckEmissionReceipt(emissionRequestId, null, DirectAckRadioWireEvidence(
            radio, value.receipt.requestId, wireRequestId, emissionRequestId,
            wire.predictedAckCode, DirectAckRadioWireOutcome.MayHaveWritten,
        ))
        val emission = Emission(value, receipt)
        emissions[emissionRequestId] = emission
        afterEmissionMark?.invoke(receipt)
        DirectAckEmissionMarkResult.Marked(emission, receipt)
    }

    override suspend fun inspectDirectAckWireEmission(
        attempt: DirectAckAttempt, wireRequestId: UUID, emissionRequestId: UUID,
    ): DirectAckEmissionObservation = radioMutations.withLock {
        val value = resolve(attempt)
            ?: return@withLock DirectAckEmissionObservation.Rejected(DirectAckRejection.INVALID_HANDLE)
        emissions[emissionRequestId]?.let {
            return@withLock if (it.attempt === value && it.receipt.evidence.wireRequestId == wireRequestId) {
                DirectAckEmissionObservation.Found(it.receipt)
            } else DirectAckEmissionObservation.Rejected(DirectAckRejection.CONFLICT)
        }
        if (value.receipt.key.radioId in saturatedRadios) {
            DirectAckEmissionObservation.CoveredBySaturation(DirectAckRadioSaturationReceipt(value.receipt.key.radioId))
        } else DirectAckEmissionObservation.NotFound
    }

    override suspend fun resolveDirectAckWireEmission(
        handle: DirectAckEmissionHandle, resolutionRequestId: UUID, resolution: DirectAckEmissionResolution,
    ): DirectAckEmissionMutationResult = radioMutations.withLock {
        val emission = (handle as? Emission)?.takeIf { emissions[it.receipt.emissionRequestId] === it }
            ?: return@withLock DirectAckEmissionMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val replay = emission.resolutions[resolutionRequestId]
        if (replay == resolution) return@withLock DirectAckEmissionMutationResult.AlreadyApplied(emission.receipt)
        store.authorityAccess("resolveDirectAckWireEmission")
        val prior = emission.receipt.evidence.outcome
        val next = when (resolution) {
            DirectAckEmissionResolution.DefinitelyUnsent -> DirectAckRadioWireOutcome.DefinitelyUnsent
            is DirectAckEmissionResolution.Accepted -> DirectAckRadioWireOutcome.Accepted(resolution.ackCode)
        }
        val outcome = if (replay == null && (prior == DirectAckRadioWireOutcome.MayHaveWritten || prior == next)) next
        else DirectAckRadioWireOutcome.Conflict(
            (prior as? DirectAckRadioWireOutcome.Accepted)?.ackCode
                ?: (prior as? DirectAckRadioWireOutcome.Conflict)?.firstAcceptedAckCode
                ?: (next as? DirectAckRadioWireOutcome.Accepted)?.ackCode,
            prior == DirectAckRadioWireOutcome.DefinitelyUnsent || next == DirectAckRadioWireOutcome.DefinitelyUnsent ||
                (prior as? DirectAckRadioWireOutcome.Conflict)?.definitelyUnsentObserved == true,
        )
        emission.receipt = emission.receipt.copy(
            resolutionRequestId = resolutionRequestId, evidence = emission.receipt.evidence.copy(outcome = outcome))
        emission.resolutions.putIfAbsent(resolutionRequestId, resolution)
        if (resolution is DirectAckEmissionResolution.Accepted) {
            val value = emission.attempt
            value.receipt = value.receipt.copy(
                phase = if (permission(value) == DirectAckPermission.CURRENT &&
                    value.receipt.phase == DirectAckPhase.REGISTERED) DirectAckPhase.ACCEPTED else value.receipt.phase,
                wires = value.receipt.wires.map {
                    if (it.requestId == emission.receipt.evidence.wireRequestId) {
                        it.copy(acceptedAckCode = resolution.ackCode)
                    } else it
                }.snapshot(),
            )
        }
        if (outcome is DirectAckRadioWireOutcome.Conflict) DirectAckEmissionMutationResult.Conflict(emission.receipt)
        else DirectAckEmissionMutationResult.Applied(emission.receipt)
    }

    private fun radioEvidence(radio: RadioId, diagnostic: Boolean = true): DirectAckRadioEvidenceSnapshot {
        val marked = emissions.values.map { it.receipt.evidence }.filter { it.radioId == radio }
        val legacy = attempts.values.filter { it.receipt.key.radioId == radio }.flatMap { attempt ->
            attempt.receipt.wires.filter { wire ->
                (attempt.receipt.requestId to wire.requestId) !in trackedWires
            }.map { wire ->
                DirectAckRadioWireEvidence(radio, attempt.receipt.requestId, wire.requestId, null,
                    wire.predictedAckCode, wire.acceptedAckCode?.let { DirectAckRadioWireOutcome.LegacyAcceptedUnknown(it) }
                        ?: DirectAckRadioWireOutcome.LegacyUnknown)
            }
        }
        val all = marked + legacy
        return DirectAckRadioEvidenceSnapshot(radio,
            (if (diagnostic) all.take(DirectAckRadioEvidenceLimits.MAX_DIAGNOSTIC_WIRES_PER_RADIO) else all).snapshot(), when {
                radio in saturatedRadios -> DirectAckRadioEvidenceCoverage.SATURATED
                diagnostic && all.size > DirectAckRadioEvidenceLimits.MAX_DIAGNOSTIC_WIRES_PER_RADIO ->
                    DirectAckRadioEvidenceCoverage.TRUNCATED
                else -> DirectAckRadioEvidenceCoverage.COMPLETE
            })
    }

    override suspend fun inspectDirectAckRadioEvidence(radioId: RadioId): DirectAckRadioEvidenceObservation =
        radioMutations.withLock { DirectAckRadioEvidenceObservation.Found(radioEvidence(radioId)) }

    override suspend fun settleDirectAckExcludingRadioHistory(
        attempt: DirectAckAttempt, ackCode: UInt, roundTripTime: UInt?,
    ): DirectAckRadioSettlementResult = radioMutations.withLock {
        val value = resolve(attempt)
            ?: return@withLock DirectAckRadioSettlementResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        value.receipt.delivery?.let {
            return@withLock if (it.ackCode == ackCode && it.roundTripTime == roundTripTime) {
                DirectAckRadioSettlementResult.Settled(DirectAckRadioSettlementReceipt.AlreadyApplied(receipt(value)))
            } else DirectAckRadioSettlementResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(value)?.let { return@withLock DirectAckRadioSettlementResult.Rejected(it.reason) }
        store.authorityAccess("updateMessageAck")
        rejection(value)?.let { return@withLock DirectAckRadioSettlementResult.Rejected(it.reason) }
        val reasons = DirectAckRadioEvidenceAssessment.nonsettlingReasons(
            value.receipt.requestId, ackCode, radioEvidence(value.receipt.key.radioId, diagnostic = false),
        )
        if (reasons.isNotEmpty()) return@withLock DirectAckRadioSettlementResult.Nonsettling(reasons)
        val message = requireNotNull(store.messages[value.receipt.key]).copy(
            status = MessageStatus.DELIVERED, ackCode = ackCode, roundTripTime = roundTripTime,
        )
        commit(value, message, DirectAckPhase.DELIVERED)
        value.receipt = value.receipt.copy(delivery = DirectAckDelivery(ackCode, roundTripTime, message))
        DirectAckRadioSettlementResult.Settled(DirectAckRadioSettlementReceipt.Applied(receipt(value)))
    }

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

    private fun intentPermission(value: Intent): DirectAckPermission = when {
        currentLeases[value.receipt.key.radioId] !== value.lease || value.lease in revoked -> DirectAckPermission.STALE_LEASE
        store.messages[value.receipt.key] == null -> DirectAckPermission.MISSING
        store.messageRevision(value.receipt.key) != value.revision ||
            value.receipt.pendingSendId?.let {
                store.pendingRevision(EntityKey(value.receipt.key.radioId, it)) != value.pendingRevision
            } == true -> DirectAckPermission.INVALIDATED
        else -> DirectAckPermission.CURRENT
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

    override suspend fun beginDirectAckAttempt(
        intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
    ): DirectAckBeginResult = radioMutations.withLock {
        beginDirectAckAttemptWithTracking(intent, firstWireRequestId, predictedAckCode, false)
    }

    override suspend fun beginDirectAckAttemptRequiringEmissionTracking(
        intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt,
    ): DirectAckBeginResult = radioMutations.withLock {
        beginDirectAckAttemptWithTracking(intent, firstWireRequestId, predictedAckCode, true)
    }

    private fun beginDirectAckAttemptWithTracking(
        intent: DirectAckIntent, firstWireRequestId: UUID, predictedAckCode: UInt, tracked: Boolean,
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
                first.requestId != firstWireRequestId || first.predictedAckCode != predictedAckCode ||
                ((requestId to firstWireRequestId) in trackedWires) != tracked) {
                return DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
            }
            if (intentPermission(captured) != DirectAckPermission.CURRENT) {
                return DirectAckBeginResult.Rejected(DirectAckRejection.INVALIDATED)
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
        if (tracked) trackedWires += requestId to firstWireRequestId
        return DirectAckBeginResult.Begun(value, receipt(value))
    }

    override suspend fun registerDirectAckDispatch(
        attempt: DirectAckAttempt, wireRequestId: UUID, predictedAckCode: UInt,
    ): DirectAckMutationResult = radioMutations.withLock {
        registerDirectAckDispatchWithTracking(attempt, wireRequestId, predictedAckCode, false)
    }

    override suspend fun registerDirectAckDispatchRequiringEmissionTracking(
        attempt: DirectAckAttempt, wireRequestId: UUID, predictedAckCode: UInt,
    ): DirectAckMutationResult = radioMutations.withLock {
        registerDirectAckDispatchWithTracking(attempt, wireRequestId, predictedAckCode, true)
    }

    private fun registerDirectAckDispatchWithTracking(
        attempt: DirectAckAttempt, wireRequestId: UUID, predictedAckCode: UInt, tracked: Boolean,
    ): DirectAckMutationResult {
        val value = resolve(attempt) ?: return DirectAckMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        rejection(value)?.let { return it }
        if (((value.receipt.requestId to value.receipt.wires.first().requestId) in trackedWires) != tracked) {
            return DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        value.receipt.wires.firstOrNull { it.requestId == wireRequestId }?.let {
            return if (it.predictedAckCode == predictedAckCode &&
                ((value.receipt.requestId to wireRequestId) in trackedWires) == tracked) DirectAckMutationResult.AlreadyApplied(receipt(value))
            else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        value.receipt = value.receipt.copy(wires =
            (value.receipt.wires + DirectAckWireReceipt(wireRequestId, predictedAckCode, null)).snapshot())
        if (tracked) trackedWires += value.receipt.requestId to wireRequestId
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
        if (value.receipt.wires.none { (it.acceptedAckCode ?: it.predictedAckCode) == ackCode }) {
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
            is DirectAckBookkeeping.SendCompleted -> "updateMessageAck"
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
    ): DirectPendingCaptureResult {
        val owner = (lease as? Lease)?.takeIf { currentLeases[key.radioId] === it && it !in revoked }
            ?: return DirectPendingCaptureResult.Rejected(DirectAckRejection.STALE_LEASE)
        completions[captureRequestId]?.let {
            if (it.lease !== owner || it.receipt.key != key || it.receipt.pendingSendId != pendingSendId ||
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
            captureRequestId, key, reason, message, pendingSendId, pending, null, DirectPendingCompletionPermission.CURRENT,
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
}
