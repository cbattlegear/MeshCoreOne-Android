// AndroidOnly: WP-202 Atomic persisted direct-ACK authority, distinct from ACK ingress.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class DirectAckRepository(private val context: RoomRepositoryContext) :
    DirectAckAuthority, DirectPendingCompletionAuthority, DirectAckRadioExclusionAuthority,
    DirectAckRadioConfirmationEvidenceInspecting, DirectAckRadioConfirmationEvidenceObserving,
    DirectAckRadioFailureAuthority, DirectMessageConfirmationEvidenceInspecting {
    private data class Lease(val issuer: UUID, val token: SessionToken, val request: UUID) : DirectAckLease
    private data class Attempt(val lease: Lease, val request: UUID) : DirectAckAttempt
    private data class Intent(val lease: Lease, val request: UUID) : DirectAckIntent
    private data class PendingCompletion(val lease: Lease, val request: UUID) : DirectPendingCompletion
    private data class Emission(val attempt: Attempt, val wire: UUID, val request: UUID) : DirectAckEmissionHandle

    internal fun channelSendLease(lease: DirectAckLease): ChannelSendLease? =
        issuedLease(lease)?.let { ChannelSendLease(it.issuer, it.token, it.request) }

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

    private suspend fun RepositoryTransaction.preauthorityHistoryUnknown(): Boolean {
        try {
            DirectAckPrehistoryStorage.verify(database.openHelper.writableDatabase)
        } catch (cause: IllegalStateException) {
            throw PersistenceStoreException(PersistenceStoreError.InvalidData, cause)
        }
        val metadata = database.directAckPrehistory().rows().singleOrNull()
            ?: invalidData("Missing singleton physical prehistory")
        if (metadata.singleton != 1L || metadata.state != "UNKNOWN") {
            invalidData("Malformed physical prehistory")
        }
        return metadata.state == "UNKNOWN"
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

    override suspend fun captureDirectAckIntent(
        lease: DirectAckLease,
        key: EntityKey,
        requestId: UUID,
        mode: DirectAckMode,
        pendingSendId: UUID?,
    ): DirectAckIntentResult = transaction("captureDirectAckIntent") {
        val issued = issuedLease(lease) ?: return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        if (key.radioId != issued.token.radioId) return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.CONFLICT)
        val dao = database.directAcks()
        val existing = dao.intent(key.radioId.value, requestId)
        if (existing != null) {
            if (existing.leaseId != issued.request || existing.messageId != key.id ||
                existing.mode != mode.name || existing.pendingId != pendingSendId
            ) return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.CONFLICT)
            rejection(intentPermission(existing, issued))?.let { return@transaction DirectAckIntentResult.Rejected(it) }
            return@transaction DirectAckIntentResult.AlreadyCaptured(Intent(issued, requestId), intentReceipt(existing, issued))
        }
        if (!active(issued)) return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.STALE_LEASE)
        val row = database.messages().byId(key.radioId.value, key.id)
            ?: return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.MISSING)
        if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null ||
            (mode == DirectAckMode.SEND && row.statusRawValue in setOf(3L, 4L))
        ) return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (MessageStatus.fromRawValue(row.statusRawValue) == null) invalidData("Unknown direct message status")
        val incarnation = dao.incarnation(key.radioId.value, key.id, "message")
            ?: invalidData("Missing persisted message incarnation")
        val pending = pendingSendId?.let { id ->
            database.pendingSends().forMessage(key.radioId.value, key.id).firstOrNull { it.id == id }
                ?: return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.MISSING)
        }
        if (pending != null && pending.kindRawValue != PendingSendKind.DM.rawValue) {
            return@transaction DirectAckIntentResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
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
        DirectAckIntentResult.Captured(Intent(issued, requestId), intentReceipt(captured, issued))
    }

    override suspend fun beginDirectAckAttempt(
        intent: DirectAckIntent,
        firstWireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckBeginResult = beginAttempt(intent, firstWireRequestId, predictedAckCode, false)

    override suspend fun beginDirectAckAttemptRequiringEmissionTracking(
        intent: DirectAckIntent,
        firstWireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckBeginResult = beginAttempt(intent, firstWireRequestId, predictedAckCode, true)

    private suspend fun beginAttempt(
        intent: DirectAckIntent,
        firstWireRequestId: UUID,
        predictedAckCode: UInt,
        tracked: Boolean,
    ): DirectAckBeginResult = transaction(if (tracked) "beginDirectAckAttemptRequiringEmissionTracking" else "beginDirectAckAttempt") {
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
            val first = dao.wire(captured.radioId, captured.requestId, firstWireRequestId)
                ?: return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
            if (existing.firstWireId != firstWireRequestId || first.predictedCode != predictedAckCode.toLong()) {
                return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
            }
            if (trackingRequired(first) != tracked) {
                return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
            }
            rejection(permission(existing, issued.lease))?.let { return@transaction DirectAckBeginResult.Rejected(it) }
            return@transaction DirectAckBeginResult.AlreadyBegun(Attempt(issued.lease, issued.request), receipt(existing, issued.lease))
        }
        rejection(intentPermission(captured, issued.lease))?.let { return@transaction DirectAckBeginResult.Rejected(it) }
        if (tracked && dao.operation(captured.radioId, captured.requestId, firstWireRequestId) != null) {
            return@transaction DirectAckBeginResult.Rejected(DirectAckRejection.CONFLICT)
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
        val wire = DirectAckWireEntity(captured.radioId, captured.requestId, firstWireRequestId, predictedAckCode.toLong(), null)
        dao.wire(wire)
        if (tracked) {
            dao.operation(trackingOperation(wire))
            appendWireFact(wire, "ACCEPTANCE_HISTORY_REQUIRED:", wire.predictedCode)
        }
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
    ): DirectAckMutationResult = registerDispatch(attempt, wireRequestId, predictedAckCode, false)

    override suspend fun registerDirectAckDispatchRequiringEmissionTracking(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        predictedAckCode: UInt,
    ): DirectAckMutationResult = registerDispatch(attempt, wireRequestId, predictedAckCode, true)

    private suspend fun registerDispatch(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        predictedAckCode: UInt,
        tracked: Boolean,
    ): DirectAckMutationResult = mutate(
        if (tracked) "registerDirectAckDispatchRequiringEmissionTracking" else "registerDirectAckDispatch", attempt,
    ) { issued, claim ->
        val dao = database.directAcks()
        val first = dao.wire(claim.radioId, claim.requestId, claim.firstWireId)
            ?: invalidData("Direct ACK claim lost its first wire")
        if (trackingRequired(first) != tracked) {
            return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val existing = dao.wire(claim.radioId, claim.requestId, wireRequestId)
        if (existing != null) return@mutate if (existing.predictedCode == predictedAckCode.toLong()) {
            if (trackingRequired(existing) == tracked) DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
            else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        } else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        deny(claim, issued)?.let { return@mutate it }
        if (claim.phase == DirectAckPhase.DELIVERED.name) {
            return@mutate DirectAckMutationResult.AlreadyDelivered(receipt(claim, issued.lease))
        }
        if (claim.phase == DirectAckPhase.FAILED.name) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        if (tracked && dao.operation(claim.radioId, claim.requestId, wireRequestId) != null) {
            return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val wire = DirectAckWireEntity(claim.radioId, claim.requestId, wireRequestId, predictedAckCode.toLong(), null)
        dao.wire(wire)
        if (tracked) {
            dao.operation(trackingOperation(wire))
            appendWireFact(wire, "ACCEPTANCE_HISTORY_REQUIRED:", wire.predictedCode)
        }
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
        if (trackingRequired(wire)) appendWireFact(wire, "TRACKED_WIRE_ACCEPTANCE:", ackCode.toLong())
        bindAcceptedWire(wire, ackCode)
        val phase = if (claim.phase == DirectAckPhase.REGISTERED.name) DirectAckPhase.ACCEPTED.name else claim.phase
        val updated = renew(claim, phase)
        save()
        DirectAckMutationResult.Applied(receipt(updated, issued.lease))
    }

    private suspend fun RepositoryTransaction.bindAcceptedWire(wire: DirectAckWireEntity, code: UInt) {
        database.directAcks().wire(wire.copy(acceptedCode = code.toLong()))
    }

    private suspend fun RepositoryTransaction.appendWireFact(wire: DirectAckWireEntity, prefix: String, value: Long) {
        val dao = database.directAcks()
        var request = UUID.randomUUID()
        while (dao.operation(wire.radioId, wire.claimId, request) != null) request = UUID.randomUUID()
        dao.operation(DirectAckOperationEntity(
            wire.radioId, wire.claimId, request, prefix + wire.requestId, value, 1, null,
        ))
    }

    private fun trackingOperation(wire: DirectAckWireEntity) = DirectAckOperationEntity(
        wire.radioId, wire.claimId, wire.requestId, "EMISSION_TRACKING_REQUIRED", wire.predictedCode, 1, null,
    )

    private suspend fun RepositoryTransaction.trackingRequired(wire: DirectAckWireEntity): Boolean {
        val operation = database.directAcks().operation(wire.radioId, wire.claimId, wire.requestId) ?: return false
        if (operation.kind != "EMISSION_TRACKING_REQUIRED") return false
        if (operation != trackingOperation(wire)) invalidData("Malformed direct ACK wire tracking provenance")
        return true
    }

    override suspend fun markDirectAckWireMayHaveWritten(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        emissionRequestId: UUID,
    ): DirectAckEmissionMarkResult = transaction("markDirectAckWireMayHaveWritten") {
        val issued = issuedAttempt(attempt)
            ?: return@transaction DirectAckEmissionMarkResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val radio = issued.lease.token.radioId.value
        val claims = database.directAcks()
        val claim = claims.claim(radio, issued.request)
            ?: return@transaction DirectAckEmissionMarkResult.Rejected(DirectAckRejection.MISSING)
        if (claim.leaseId != issued.lease.request) {
            return@transaction DirectAckEmissionMarkResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(permission(claim, issued.lease))?.let {
            return@transaction DirectAckEmissionMarkResult.Rejected(it)
        }
        val dao = database.directAckExclusions()
        val wire = claims.wire(radio, issued.request, wireRequestId)
            ?: return@transaction DirectAckEmissionMarkResult.Rejected(DirectAckRejection.MISSING)
        if (!trackingRequired(wire)) {
            return@transaction DirectAckEmissionMarkResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val previous = dao.emissionRequests(radio, emissionRequestId)
        if (previous.isNotEmpty()) {
            val row = previous.singleOrNull()
            if (row == null || row.claimId != issued.request || row.wireId != wireRequestId ||
                row.issuer != context.directAckIssuer
            ) return@transaction DirectAckEmissionMarkResult.Rejected(DirectAckRejection.CONFLICT)
            return@transaction DirectAckEmissionMarkResult.AlreadyMarked(
                Emission(issued, wireRequestId, emissionRequestId), emissionReceipt(row),
            )
        }
        if (claim.phase == DirectAckPhase.FAILED.name || claim.phase == DirectAckPhase.DELIVERED.name) {
            return@transaction DirectAckEmissionMarkResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
        }
        if (dao.saturated(radio)) {
            return@transaction DirectAckEmissionMarkResult.CoveredBySaturation(
                DirectAckRadioSaturationReceipt(issued.lease.token.radioId),
            )
        }
        val row = DirectAckEmissionEntity(
            radio, issued.request, wireRequestId, context.directAckIssuer, emissionRequestId,
            wire.predictedCode, "MAY_HAVE_WRITTEN", null, null,
        )
        dao.emission(row)
        save()
        DirectAckEmissionMarkResult.Marked(Emission(issued, wireRequestId, emissionRequestId), emissionReceipt(row))
    }

    override suspend fun inspectDirectAckWireEmission(
        attempt: DirectAckAttempt,
        wireRequestId: UUID,
        emissionRequestId: UUID,
    ): DirectAckEmissionObservation = transaction("inspectDirectAckWireEmission") {
        val issued = issuedAttempt(attempt)
            ?: return@transaction DirectAckEmissionObservation.Rejected(DirectAckRejection.INVALID_HANDLE)
        val radio = issued.lease.token.radioId.value
        val claim = database.directAcks().claim(radio, issued.request)
            ?: return@transaction DirectAckEmissionObservation.Rejected(DirectAckRejection.MISSING)
        if (claim.leaseId != issued.lease.request) {
            return@transaction DirectAckEmissionObservation.Rejected(DirectAckRejection.CONFLICT)
        }
        val dao = database.directAckExclusions()
        val requests = dao.emissionRequests(radio, emissionRequestId)
        if (requests.isNotEmpty()) {
            val row = requests.singleOrNull()
            if (row == null || row.claimId != issued.request || row.wireId != wireRequestId ||
                row.issuer != context.directAckIssuer
            ) return@transaction DirectAckEmissionObservation.Rejected(DirectAckRejection.CONFLICT)
            return@transaction DirectAckEmissionObservation.Found(emissionReceipt(row))
        }
        if (dao.saturated(radio)) DirectAckEmissionObservation.CoveredBySaturation(
            DirectAckRadioSaturationReceipt(issued.lease.token.radioId),
        ) else DirectAckEmissionObservation.NotFound
    }

    override suspend fun resolveDirectAckWireEmission(
        handle: DirectAckEmissionHandle,
        resolutionRequestId: UUID,
        resolution: DirectAckEmissionResolution,
    ): DirectAckEmissionMutationResult = transaction("resolveDirectAckWireEmission") {
        val issued = handle as? Emission
        if (issued == null) {
            return@transaction DirectAckEmissionMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val attempt = issued.attempt
        val radio = attempt.lease.token.radioId.value
        val claims = database.directAcks()
        val lease = claims.lease(radio, attempt.lease.request)
            ?: return@transaction DirectAckEmissionMutationResult.Rejected(DirectAckRejection.MISSING)
        if (lease.issuer != attempt.lease.issuer || lease.epoch != attempt.lease.token.epoch.value ||
            lease.generation != attempt.lease.token.generation.value
        ) return@transaction DirectAckEmissionMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val claim = claims.claim(radio, attempt.request)
            ?: return@transaction DirectAckEmissionMutationResult.Rejected(DirectAckRejection.MISSING)
        if (claim.leaseId != attempt.lease.request) {
            return@transaction DirectAckEmissionMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val dao = database.directAckExclusions()
        val row = dao.emission(radio, attempt.request, issued.wire, issued.request)
            ?: return@transaction DirectAckEmissionMutationResult.Rejected(DirectAckRejection.MISSING)
        if (row.issuer != attempt.lease.issuer) {
            return@transaction DirectAckEmissionMutationResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        }
        val outcome = emissionEvidence(row).outcome
        val desired = when (resolution) {
            DirectAckEmissionResolution.DefinitelyUnsent -> DirectAckRadioWireOutcome.DefinitelyUnsent
            is DirectAckEmissionResolution.Accepted -> DirectAckRadioWireOutcome.Accepted(resolution.ackCode)
        }
        val previous = claims.operation(radio, attempt.request, resolutionRequestId)
        val inputKind = if (resolution is DirectAckEmissionResolution.Accepted) "ACCEPTED" else "UNSENT"
        val prefix = "EMISSION_RESOLUTION:${issued.wire}:${issued.request}:$inputKind:"
        val inputCode = (resolution as? DirectAckEmissionResolution.Accepted)?.ackCode?.toLong()
        if (previous != null) {
            if (!previous.kind.startsWith("EMISSION_RESOLUTION:")) {
                return@transaction DirectAckEmissionMutationResult.Rejected(DirectAckRejection.CONFLICT)
            }
            if (previous.kind.startsWith(prefix) && previous.argument == inputCode && previous.pendingId == null) {
                val originalState = previous.kind.removePrefix(prefix)
                return@transaction when (originalState) {
                    "ACCEPTED", "DEFINITELY_UNSENT" ->
                        DirectAckEmissionMutationResult.AlreadyApplied(emissionReceipt(row))
                    "CONFLICT", "CONFLICT_UNSENT" -> DirectAckEmissionMutationResult.Conflict(emissionReceipt(row))
                    else -> invalidData("Unknown journaled emission resolution result")
                }
            }
        }
        val contradictory = previous != null ||
            (outcome != DirectAckRadioWireOutcome.MayHaveWritten && outcome != desired)
        val unsent = outcome == DirectAckRadioWireOutcome.DefinitelyUnsent ||
            (outcome as? DirectAckRadioWireOutcome.Conflict)?.definitelyUnsentObserved == true ||
            resolution == DirectAckEmissionResolution.DefinitelyUnsent
        val accepted = row.actualCode ?: (resolution as? DirectAckEmissionResolution.Accepted)?.ackCode?.toLong()
        val updated = row.copy(
            state = when {
                contradictory && unsent -> "CONFLICT_UNSENT"
                contradictory -> "CONFLICT"
                resolution == DirectAckEmissionResolution.DefinitelyUnsent -> "DEFINITELY_UNSENT"
                else -> "ACCEPTED"
            },
            actualCode = accepted,
            resolutionRequestId = resolutionRequestId,
        )
        val wire = claims.wire(radio, attempt.request, issued.wire)
            ?: invalidData("Persisted emission lost its registered wire")
        if (!trackingRequired(wire)) invalidData("Persisted emission lacks tracked-wire provenance")
        if (resolution is DirectAckEmissionResolution.Accepted) {
            bindAcceptedWire(wire, resolution.ackCode)
            if (!contradictory && issuedLease(attempt.lease) != null &&
                permission(claim, attempt.lease) == DirectAckPermission.CURRENT &&
                claim.phase == DirectAckPhase.REGISTERED.name
            ) renew(claim, DirectAckPhase.ACCEPTED.name)
        }
        dao.emission(updated)
        if (previous == null) claims.operation(DirectAckOperationEntity(
            radio, attempt.request, resolutionRequestId, prefix + updated.state, inputCode, updated.actualCode, null,
        ))
        save()
        if (contradictory) DirectAckEmissionMutationResult.Conflict(emissionReceipt(updated))
        else DirectAckEmissionMutationResult.Applied(emissionReceipt(updated))
    }

    override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId): Flow<DirectAckRadioConfirmationObservation> =
        flow {
            while (true) {
                emit(DirectAckRadioConfirmationObservation.Inspecting(radioId))
                emit(DirectAckRadioConfirmationObservation.Snapshot(inspectDirectAckRadioConfirmationEvidence(radioId)))
                delay(1_000)
            }
        }

    override suspend fun inspectDirectMessageConfirmationEvidence(key: EntityKey): DirectMessageConfirmationEvidence =
        context.read("inspectDirectMessageConfirmationEvidence") {
            val row = database.messages().byId(key.radioId.value, key.id)
                ?: return@read DirectMessageConfirmationEvidence.Missing(key)
            if (row.directionRawValue != MessageDirection.OUTGOING.rawValue || row.channelIndex != null) {
                return@read DirectMessageConfirmationEvidence.NotEligible(key)
            }
            val status = MessageStatus.fromRawValue(row.statusRawValue) ?: invalidData("Unknown direct message status")
            val identity = database.directAcks().incarnation(key.radioId.value, key.id, "message")
                ?: invalidData("Missing persisted diagnostic message identity")
            if (identity.incarnation.length != 64 || identity.incarnation.any {
                    it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F'
                } || identity.revision < 0
            ) invalidData("Malformed persisted diagnostic message identity")
            val history = database.directAckExclusions()
            if (history.confirmationEvidence(key.radioId.value).malformedPhysicalEvidence ||
                history.malformedAcceptanceHistory(key.radioId.value) ||
                history.malformedRegisteredHistory(key.radioId.value)
            ) invalidData("Malformed persisted message confirmation evidence")
            val evidence = history.messageConfirmationEvidence(key.radioId.value, key.id, identity.incarnation)
            val reasons = linkedSetOf(DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN)
            if (evidence.targetUnresolved) reasons += DirectMessageConfirmationUncertaintyReason.TARGET_UNRESOLVED_PHYSICAL_EMISSION
            if (evidence.targetConflict) reasons += DirectMessageConfirmationUncertaintyReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE
            if (evidence.targetLegacy) reasons += DirectMessageConfirmationUncertaintyReason.TARGET_UNTRACKED_LEGACY_ANCESTRY
            if (evidence.otherUnresolved) reasons += DirectMessageConfirmationUncertaintyReason.UNRESOLVED_OTHER_CLAIM_EMISSION
            if (evidence.otherConflict) reasons += DirectMessageConfirmationUncertaintyReason.CONFLICTING_OTHER_CLAIM_EVIDENCE
            if (evidence.otherLegacy) reasons += DirectMessageConfirmationUncertaintyReason.UNTRACKED_OTHER_CLAIM_ANCESTRY
            if (evidence.otherKnownCode) reasons += DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE
            if (evidence.saturated) reasons += DirectMessageConfirmationUncertaintyReason.RADIO_HISTORY_SATURATED
            if (preauthorityHistoryUnknown()) reasons += DirectMessageConfirmationUncertaintyReason.PREAUTHORITY_HISTORY_UNKNOWN
            DirectMessageConfirmationEvidence.Present(
                key, DirectMessageConfirmationIdentity(identity.incarnation, identity.revision), status, reasons.snapshotSet(),
            )
        }

    override suspend fun inspectDirectAckRadioConfirmationEvidence(radioId: RadioId): DirectAckRadioConfirmationEvidence =
        context.read("inspectDirectAckRadioConfirmationEvidence") {
            val dao = database.directAckExclusions()
            val row = dao.confirmationEvidence(radioId.value)
            if (row.malformedPhysicalEvidence || dao.malformedAcceptanceHistory(radioId.value) ||
                dao.malformedRegisteredHistory(radioId.value)
            ) {
                invalidData("Malformed persisted radio emission evidence")
            }
            val reasons = linkedSetOf<DirectAckRadioConfirmationWildcardReason>()
            if (row.unresolvedPhysicalEmission) reasons += DirectAckRadioConfirmationWildcardReason.UNRESOLVED_PHYSICAL_EMISSION
            if (row.conflictingPhysicalEvidence) reasons += DirectAckRadioConfirmationWildcardReason.CONFLICTING_PHYSICAL_EVIDENCE
            if (row.untrackedLegacyAncestry) reasons += DirectAckRadioConfirmationWildcardReason.UNTRACKED_LEGACY_ANCESTRY
            if (row.stickySaturation) reasons += DirectAckRadioConfirmationWildcardReason.STICKY_SATURATION
            if (preauthorityHistoryUnknown()) reasons += DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN
            if (reasons.isEmpty()) DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId)
            else DirectAckRadioConfirmationEvidence.PersistedWildcard(radioId, reasons.snapshotSet())
        }

    override suspend fun inspectDirectAckRadioEvidence(radioId: RadioId): DirectAckRadioEvidenceObservation =
        transaction("inspectDirectAckRadioEvidence") {
            val dao = database.directAckExclusions()
            if (dao.confirmationEvidence(radioId.value).malformedPhysicalEvidence ||
                dao.malformedRegisteredHistory(radioId.value) ||
                dao.inconsistentAcceptedHistory(radioId.value) ||
                dao.incompleteAcceptanceCoverageHistory(radioId.value) ||
                dao.malformedAcceptanceHistory(radioId.value)
            ) {
                invalidData("Tracked wire acceptance ancestry is incomplete or inconsistent")
            }
            val maximum = DirectAckRadioEvidenceLimits.MAX_DIAGNOSTIC_WIRES_PER_RADIO
            val markers = dao.emissions(radioId.value, maximum)
            val legacy = dao.legacyWires(radioId.value, maximum - markers.size)
            val coverage = when {
                dao.saturated(radioId.value) -> DirectAckRadioEvidenceCoverage.SATURATED
                checkedAdd(dao.count(radioId.value), dao.legacyCount(radioId.value)) > maximum ->
                    DirectAckRadioEvidenceCoverage.TRUNCATED
                else -> DirectAckRadioEvidenceCoverage.COMPLETE
            }
            val evidence = markers.map(::emissionEvidence) + legacy.map {
                DirectAckRadioWireEvidence(
                    radioId, it.claimId, it.requestId, null, it.predictedCode.asUInt(),
                    it.acceptedCode?.let { code -> DirectAckRadioWireOutcome.LegacyAcceptedUnknown(code.asUInt()) }
                        ?: DirectAckRadioWireOutcome.LegacyUnknown,
                )
            }
            DirectAckRadioEvidenceObservation.Found(DirectAckRadioEvidenceSnapshot(
                radioId, evidence.snapshot(), coverage, preauthorityHistoryUnknown(),
            ))
        }

    override suspend fun settleDirectAckExcludingRadioHistory(
        attempt: DirectAckAttempt,
        ackCode: UInt,
        roundTripTime: UInt?,
    ): DirectAckRadioSettlementResult = transaction("settleDirectAckExcludingRadioHistory") {
        val issued = issuedAttempt(attempt)
            ?: return@transaction DirectAckRadioSettlementResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val radio = issued.lease.token.radioId.value
        val dao = database.directAcks()
        val claim = dao.claim(radio, issued.request)
            ?: return@transaction DirectAckRadioSettlementResult.Rejected(DirectAckRejection.MISSING)
        if (claim.leaseId != issued.lease.request) {
            return@transaction DirectAckRadioSettlementResult.Rejected(DirectAckRejection.CONFLICT)
        }
        val proofs = dao.radioDeliveries(radio, claim.requestId)
        if (proofs.size > 1) invalidData("Multiple radio exclusion delivery proofs")
        val previousOperation = proofs.singleOrNull()
        val delivered = dao.delivery(radio, claim.requestId)
        if (delivered != null) {
            if (previousOperation == null || previousOperation.argument != ackCode.toLong() ||
                previousOperation.maximum != roundTripTime?.toLong() || previousOperation.pendingId != null ||
                delivered.ackCode != ackCode.toLong() ||
                delivered.roundTripTime != roundTripTime?.toLong()
            ) return@transaction DirectAckRadioSettlementResult.Rejected(DirectAckRejection.CONFLICT)
            return@transaction DirectAckRadioSettlementResult.Settled(
                DirectAckRadioSettlementReceipt.AlreadyApplied(receipt(claim, issued.lease)),
            )
        }
        if (previousOperation != null) {
            return@transaction DirectAckRadioSettlementResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(permission(claim, issued.lease))?.let {
            return@transaction DirectAckRadioSettlementResult.Rejected(it)
        }
        val history = database.directAckExclusions()
        if (history.confirmationEvidence(radio).malformedPhysicalEvidence || history.malformedAcceptanceHistory(radio) ||
            history.malformedRegisteredHistory(radio)
        ) {
            invalidData("Malformed persisted radio emission evidence")
        }
        val reasons = linkedSetOf<DirectAckRadioNonsettlingReason>()
        if (preauthorityHistoryUnknown()) reasons += DirectAckRadioNonsettlingReason.PREAUTHORITY_HISTORY_UNKNOWN
        if (!history.acceptedCurrentWire(radio, claim.requestId, ackCode.toLong()) ||
            history.conflictedClaim(radio, claim.requestId)
        ) {
            reasons += if (history.unresolvedCurrentClaim(radio, claim.requestId)) {
                DirectAckRadioNonsettlingReason.TARGET_ACCEPTANCE_UNRESOLVED
            } else DirectAckRadioNonsettlingReason.TARGET_CODE_UNREGISTERED
        }
        if (history.competingCode(radio, claim.requestId, ackCode.toLong())) {
            reasons += DirectAckRadioNonsettlingReason.KNOWN_OTHER_CLAIM_CODE
        }
        if (history.unresolvedCompetitor(radio, claim.requestId)) {
            reasons += DirectAckRadioNonsettlingReason.UNRESOLVED_OTHER_CLAIM_EMISSION
        }
        if (history.saturated(radio)) reasons += DirectAckRadioNonsettlingReason.RADIO_HISTORY_SATURATED
        if (reasons.isNotEmpty()) return@transaction DirectAckRadioSettlementResult.Nonsettling(reasons.snapshotSet())
        var proofRequest = UUID.randomUUID()
        while (dao.operation(radio, claim.requestId, proofRequest) != null) proofRequest = UUID.randomUUID()
        val operation = DirectAckOperationEntity(
            radio, claim.requestId, proofRequest, "RADIO_DELIVERY", ackCode.toLong(), roundTripTime?.toLong(), null,
        )
        ownWrite(dao.deliver(radio, claim.messageId, ackCode.toLong(), roundTripTime?.toLong()))
        val updated = renew(claim, DirectAckPhase.DELIVERED.name, 1)
        dao.delivery(DirectAckDeliveryEntity(
            claim.requestId, ackCode.toLong(), roundTripTime?.toLong(), radio, claim.messageId, updated.snapshot,
        ))
        dao.operation(operation)
        save()
        DirectAckRadioSettlementResult.Settled(DirectAckRadioSettlementReceipt.Applied(receipt(updated, issued.lease)))
    }

    private fun issuedAttempt(attempt: DirectAckAttempt): Attempt? =
        (attempt as? Attempt)?.takeIf { issuedLease(it.lease) != null }

    private fun emissionReceipt(row: DirectAckEmissionEntity) =
        DirectAckEmissionReceipt(row.markRequestId, row.resolutionRequestId, emissionEvidence(row))

    private fun emissionEvidence(row: DirectAckEmissionEntity): DirectAckRadioWireEvidence {
        val actual = row.actualCode?.asUInt()
        val outcome = when (row.state) {
            "MAY_HAVE_WRITTEN" -> {
                if (actual != null || row.resolutionRequestId != null) invalidData("Malformed unresolved emission")
                DirectAckRadioWireOutcome.MayHaveWritten
            }
            "DEFINITELY_UNSENT" -> {
                if (actual != null || row.resolutionRequestId == null) invalidData("Malformed unsent emission")
                DirectAckRadioWireOutcome.DefinitelyUnsent
            }
            "ACCEPTED" -> {
                if (row.resolutionRequestId == null) invalidData("Accepted emission has no resolution")
                DirectAckRadioWireOutcome.Accepted(actual ?: invalidData("Accepted emission has no actual code"))
            }
            "CONFLICT", "CONFLICT_UNSENT" -> DirectAckRadioWireOutcome.Conflict(actual, row.state == "CONFLICT_UNSENT")
            else -> invalidData("Unknown persisted emission state")
        }
        return DirectAckRadioWireEvidence(
            RadioId(row.radioId), row.claimId, row.wireId, row.markRequestId, row.predictedCode.asUInt(), outcome,
        )
    }

    override suspend fun recordDirectFailureExcludingRadioHistory(
        attempt: DirectAckAttempt,
    ): DirectAckRadioFailureResult = transaction("recordDirectFailureExcludingRadioHistory") {
        val issued = issuedAttempt(attempt)
            ?: return@transaction DirectAckRadioFailureResult.Rejected(DirectAckRejection.INVALID_HANDLE)
        val radio = issued.lease.token.radioId.value
        val dao = database.directAcks()
        val claim = dao.claim(radio, issued.request)
            ?: return@transaction DirectAckRadioFailureResult.Rejected(DirectAckRejection.MISSING)
        if (claim.leaseId != issued.lease.request) {
            return@transaction DirectAckRadioFailureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        if (dao.delivery(radio, claim.requestId) != null ||
            claim.phase == DirectAckPhase.DELIVERED.name || claim.initialStatus == MessageStatus.DELIVERED.rawValue
        ) return@transaction DirectAckRadioFailureResult.AlreadyDelivered(receipt(claim, issued.lease))
        val proofs = dao.radioFailures(radio, claim.requestId)
        if (proofs.size > 1) invalidData("Multiple guarded radio failure proofs")
        val proof = proofs.singleOrNull()
        if (proof != null) {
            if (proof.argument != null || proof.maximum != null || proof.pendingId != null ||
                claim.phase != DirectAckPhase.FAILED.name
            ) invalidData("Malformed guarded radio failure proof")
            return@transaction DirectAckRadioFailureResult.AlreadyApplied(receipt(claim, issued.lease))
        }
        if (claim.phase == DirectAckPhase.FAILED.name) {
            return@transaction DirectAckRadioFailureResult.Rejected(DirectAckRejection.CONFLICT)
        }
        rejection(permission(claim, issued.lease))?.let {
            return@transaction DirectAckRadioFailureResult.Rejected(it)
        }
        if (database.directAckExclusions().malformedRegisteredHistory(radio)) {
            invalidData("Malformed persisted registered radio evidence")
        }
        val history = database.directAckExclusions().failureEvidence(radio, claim.requestId)
        val reasons = linkedSetOf<DirectAckRadioNonfailingReason>()
        if (history.targetUnresolved) reasons += DirectAckRadioNonfailingReason.TARGET_UNRESOLVED_PHYSICAL_EMISSION
        if (history.targetConflict) reasons += DirectAckRadioNonfailingReason.TARGET_CONFLICTING_PHYSICAL_EVIDENCE
        if (history.targetLegacy) reasons += DirectAckRadioNonfailingReason.TARGET_UNTRACKED_LEGACY_ANCESTRY
        if (history.saturated) reasons += DirectAckRadioNonfailingReason.RADIO_HISTORY_SATURATED
        if (preauthorityHistoryUnknown()) reasons += DirectAckRadioNonfailingReason.PREAUTHORITY_HISTORY_UNKNOWN
        if (history.targetAccepted || history.sameIncarnationOtherUncertainty ||
            history.sameIncarnationOtherAccepted || reasons.isNotEmpty()
        ) {
            if (history.otherUnresolved) reasons += DirectAckRadioNonfailingReason.UNRESOLVED_OTHER_CLAIM_EMISSION
            if (history.otherConflict) reasons += DirectAckRadioNonfailingReason.CONFLICTING_OTHER_CLAIM_EVIDENCE
            if (history.otherLegacy) reasons += DirectAckRadioNonfailingReason.UNTRACKED_OTHER_CLAIM_ANCESTRY
            if (history.otherKnownCode) reasons += DirectAckRadioNonfailingReason.KNOWN_OTHER_CLAIM_CODE
        }
        if (reasons.isNotEmpty()) return@transaction DirectAckRadioFailureResult.Nonfailing(reasons.snapshotSet())
        var request = UUID.randomUUID()
        while (dao.operation(radio, claim.requestId, request) != null) request = UUID.randomUUID()
        ownWrite(database.messages().setStatusUnlessDelivered(radio, claim.messageId, MessageStatus.FAILED.rawValue))
        val updated = renew(claim, DirectAckPhase.FAILED.name, 1)
        dao.operation(DirectAckOperationEntity(radio, claim.requestId, request, "RADIO_FAILURE", null, null, null))
        save()
        DirectAckRadioFailureResult.Applied(receipt(updated, issued.lease))
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
        if (dao.wires(claim.radioId, claim.requestId).none {
                (it.acceptedCode ?: it.predictedCode) == ackCode.toLong()
            }
        ) return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
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
        val row = claim.snapshot ?: invalidData("Current claim has no message")
        val dao = database.directAcks()
        if (change != DirectAckBookkeeping.ResendCountIncrement && row.statusRawValue == 3L) {
            return@mutate DirectAckMutationResult.AlreadyDelivered(receipt(claim, issued.lease))
        }
        when (change) {
            is DirectAckBookkeeping.SendCompleted -> {
                if (claim.mode != DirectAckMode.SEND.name ||
                    row.statusRawValue != MessageStatus.PENDING.rawValue
                ) {
                    return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                }
                val wire = dao.wires(claim.radioId, claim.requestId)
                    .firstOrNull { it.requestId == change.wireRequestId }
                    ?: return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.MISSING)
                val acceptedCode = wire.acceptedCode
                    ?: return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.NOT_ELIGIBLE)
                ownWrite(database.messages().setAck(
                    claim.radioId, claim.messageId, acceptedCode, MessageStatus.SENT.rawValue, null,
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
        if (database.directAcks().hasPendingCompletion(claim.radioId, claim.requestId)) {
            return@mutate DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
        }
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
        if (key.radioId != issued.token.radioId) return@transaction DirectPendingCaptureResult.Rejected(DirectAckRejection.CONFLICT)
        val dao = database.directAcks()
        val reasonName = when (reason) {
            DirectPendingCompletionReason.AlreadyDeliveredSend -> "DELIVERED"
            is DirectPendingCompletionReason.ZeroAttempts -> "ZERO"
        }
        val maximum = (reason as? DirectPendingCompletionReason.ZeroAttempts)?.configuredMaximumAttempts
        val existing = dao.pendingCompletion(key.radioId.value, captureRequestId)
        if (existing != null) {
            if (existing.leaseId != issued.request || existing.messageId != key.id ||
                existing.pendingId != pendingSendId || existing.reason != reasonName || existing.maximumAttempts != maximum
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
            pendingIdentity.incarnation, reasonName, maximum, true, null, key.radioId.value, key.id, pendingSendId, row, pending,
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
        ownWrite(database.pendingSends().delete(capture.radioId, capture.pendingId))
        val identity = dao.incarnation(capture.radioId, capture.messageId, "message")
            ?: invalidData("No-dispatch completion lost its message")
        if (identity.incarnation != capture.messageIncarnation ||
            identity.revision != checkedIncrement(capture.messageRevision)
        ) invalidData("Independent mutation inside no-dispatch completion")
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
            "DELIVERED" -> DirectPendingCompletionReason.AlreadyDeliveredSend
            "ZERO" -> DirectPendingCompletionReason.ZeroAttempts(
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
        val identity = dao.incarnation(claim.radioId, claim.messageId, "message")
            ?: return DirectAckPermission.MISSING
        if (!claim.current || claim.snapshot == null || identity.incarnation != claim.incarnation || identity.revision != claim.revision) {
            return DirectAckPermission.INVALIDATED
        }
        val pendingId = claim.pendingId
        if (pendingId != null && !dao.hasPendingCompletion(claim.radioId, claim.requestId)) {
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
        val previous = database.directAcks().operation(claim.radioId, claim.requestId, operation.requestId) ?: return null
        return if (previous == operation) DirectAckMutationResult.AlreadyApplied(receipt(claim, issued.lease))
        else DirectAckMutationResult.Rejected(DirectAckRejection.CONFLICT)
    }

    private suspend fun RepositoryTransaction.receipt(claim: DirectAckClaimEntity, lease: Lease): DirectAckReceipt {
        val dao = database.directAcks()
        val operations = dao.receiptOperations(claim.radioId, claim.requestId)
        val bookkeeping = operations.filter { it.kind != "PENDING" }.map {
            val change = when {
                it.kind.startsWith("SEND_COMPLETED:") -> {
                    val wireId = it.kind.removePrefix("SEND_COMPLETED:")
                    DirectAckBookkeeping.SendCompleted(UUID.fromString(wireId))
                }
                it.kind == "RETRY" -> DirectAckBookkeeping.RetryStarted(
                    it.argument ?: invalidData("Missing retry argument"), it.maximum ?: invalidData("Missing retry maximum"),
                )
                it.kind == "EXHAUSTED" -> DirectAckBookkeeping.RetryExhausted
                it.kind == "COUNT" -> DirectAckBookkeeping.ResendCountIncrement
                else -> invalidData("Unknown direct ACK operation")
            }
            DirectAckBookkeepingReceipt(it.requestId, change)
        }.snapshot()
        val delivery = dao.delivery(claim.radioId, claim.requestId)?.let {
            DirectAckDelivery(it.ackCode.asUInt(), it.roundTripTime?.asUInt(), it.snapshot?.toDTO())
        }
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
        )
    }

    private fun Long.asUInt(): UInt {
        if (this !in 0..UInt.MAX_VALUE.toLong()) invalidData("Direct ACK value outside UInt32")
        return toUInt()
    }
}
