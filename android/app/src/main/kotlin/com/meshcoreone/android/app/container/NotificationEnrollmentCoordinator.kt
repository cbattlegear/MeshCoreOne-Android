// AndroidOnly: WP-303 Nonactivated, loss-aware notification generation enrollment.
package com.meshcoreone.android.app.container

import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates an explicitly acknowledged NEW generation, never a claim of first installation.
 * No production adapters or ingress are installed here. Port implementations must authenticate
 * acknowledgment and durably retain the witness; an in-memory implementation is not sufficient.
 */
internal class NotificationEnrollmentCoordinator(
    private val witness: Witness,
    private val journal: Journal,
) {
    data class Operation(val operationId: UUID, val storeId: UUID)

    enum class Phase { PREPARING, BOUND }

    data class CreationReceipt(
        val operation: Operation,
        val revision: Long,
        val phase: Phase,
        val creationNonce: UUID,
        val witnessId: UUID?,
    )

    enum class WitnessPhase { AUTHORIZED, ACCEPTED, BOUND }

    data class WitnessRecord(
        val operation: Operation,
        val witnessId: UUID,
        val revision: Long,
        val phase: WitnessPhase,
        val receipt: CreationReceipt?,
    )

    /**
     * An opaque reference to the real foreground acknowledgment, not a Boolean proof.
     * The witness adapter must validate it against the warning and exact prior observation,
     * consume it idempotently for this operation, and preserve previous generations.
     */
    data class EnrollmentRequest(
        val operation: Operation,
        val acknowledgmentId: UUID,
        val expectedCurrent: WitnessRecord?,
    )

    enum class Failure {
        ACKNOWLEDGMENT_REQUIRED, ACKNOWLEDGMENT_DECLINED, NOT_RETAINED,
        STORAGE_UNAVAILABLE, CORRUPT_STORAGE, IDENTITY_MISMATCH, INVALID_RECEIPT,
    }

    sealed interface Observation {
        data object UnknownOrLost : Observation
        data class Current(val record: WitnessRecord) : Observation
        data class Failed(val reason: Failure) : Observation
    }

    sealed interface WitnessWrite {
        /** True only on the uninterrupted return from a newly retained foreground authorization. */
        data class Stored(val record: WitnessRecord, val newlyAuthorized: Boolean = false) : WitnessWrite
        data class NotRetained(val reason: Failure) : WitnessWrite
        data object Stale : WitnessWrite
        data class Indeterminate(val operation: Operation) : WitnessWrite
    }

    sealed interface JournalResult {
        data class Observed(val receipt: CreationReceipt) : JournalResult
        data class NotRetained(val reason: Failure) : JournalResult
        data class Indeterminate(val operation: Operation) : JournalResult
    }

    interface Witness {
        /** Read-only. Absence, including joint key/data loss, is never freshness proof. */
        suspend fun observe(): Observation

        /**
         * Atomically validates actual acknowledgment and expectedCurrent, then retains AUTHORIZED.
         * Same-operation retry must not mint another identity or replace a different current record.
         * Historical bytes survive; locked, corrupt and mismatched state is not reset.
         * Only a NEW authorization returns newlyAuthorized=true; replay/lost-return recovery cannot.
         */
        suspend fun authorize(request: EnrollmentRequest): WitnessWrite

        /** CAS of the complete current record; persist the exact causal receipt before journal bind. */
        suspend fun accept(expected: WitnessRecord, preparing: CreationReceipt): WitnessWrite

        /** CAS of the complete current record; persist the exact bound receipt and witness identity. */
        suspend fun complete(expected: WitnessRecord, bound: CreationReceipt): WitnessWrite
    }

    interface Journal {
        /**
         * Only for an already durably authorized operation at its distinct generation path.
         * Exact-operation reconciliation is allowed; empty/partial/foreign files are not recreated.
         */
        suspend fun prepare(operation: Operation): JournalResult

        /** CAS of the exact preparing receipt, retaining the witness identity in the same journal. */
        suspend fun bind(preparing: CreationReceipt, witnessId: UUID): JournalResult

        /** Read-only; verifies both operation/store and the already known creation nonce. */
        suspend fun reconcile(operation: Operation, expectedNonce: UUID?): JournalResult
    }

    enum class Step { AUTHORIZE, PREPARE, ACCEPT_WITNESS, BIND_JOURNAL, COMPLETE_WITNESS, INSPECT }

    sealed interface Result {
        data object NeedsAcknowledgment : Result
        data class Incomplete(val operation: Operation) : Result
        data class Blocked(val reason: Failure) : Result
        data object Superseded : Result
        data class Indeterminate(val operation: Operation, val step: Step) : Result

        /**
         * An enrollment observation only, NOT an issuance/dispatch capability. Future ingress still
         * needs exact current-generation fencing and the journal's transactional bound checks.
         * It conveys no radio/target authority, historical freshness, or antirollback guarantee.
         */
        data class BoundSnapshot(val witness: WitnessRecord, val receipt: CreationReceipt) : Result
    }

    private val mutex = Mutex()

    suspend fun enroll(request: EnrollmentRequest): Result = mutex.withLock {
        when (val write = witness.authorize(request)) {
            is WitnessWrite.Stored -> {
                if (write.record.operation != request.operation || !valid(write.record) ||
                    write.newlyAuthorized && (
                        write.record.phase != WitnessPhase.AUTHORIZED ||
                            request.expectedCurrent?.operation == request.operation
                        )
                ) {
                    Result.Blocked(Failure.INVALID_RECEIPT)
                } else {
                    advance(write.record, mayPrepare = write.newlyAuthorized)
                }
            }
            is WitnessWrite.NotRetained -> Result.Blocked(write.reason)
            WitnessWrite.Stale -> Result.Superseded
            is WitnessWrite.Indeterminate -> uncertain(write.operation, request.operation, Step.AUTHORIZE)
        }
    }

    /** Resume only the positively retained current authorization, without another creation identity. */
    suspend fun resume(): Result = mutex.withLock {
        when (val observation = witness.observe()) {
            Observation.UnknownOrLost -> Result.NeedsAcknowledgment
            is Observation.Failed -> Result.Blocked(observation.reason)
            is Observation.Current -> advance(observation.record)
        }
    }

    /** Startup inspection performs no creation, migration, witness writes, send, or replay. */
    suspend fun inspect(): Result = mutex.withLock {
        when (val observation = witness.observe()) {
            Observation.UnknownOrLost -> Result.NeedsAcknowledgment
            is Observation.Failed -> Result.Blocked(observation.reason)
            is Observation.Current -> {
                val record = observation.record
                when {
                    !valid(record) -> Result.Blocked(Failure.INVALID_RECEIPT)
                    record.phase != WitnessPhase.BOUND -> Result.Incomplete(record.operation)
                    else -> inspectBound(record)
                }
            }
        }
    }

    private suspend fun advance(initial: WitnessRecord, mayPrepare: Boolean = false): Result {
        if (!valid(initial)) return Result.Blocked(Failure.INVALID_RECEIPT)
        var current = initial
        if (current.phase == WitnessPhase.AUTHORIZED) {
            currentCheck(current)?.let { return it }
            // After a lost return, missing storage may be LOST committed storage, not uncreated.
            val attempt = if (mayPrepare) journal.prepare(current.operation)
            else journal.reconcile(current.operation, expectedNonce = null)
            val preparing = when (val result = attempt) {
                is JournalResult.Observed -> result.receipt
                is JournalResult.NotRetained -> return Result.Blocked(result.reason)
                is JournalResult.Indeterminate ->
                    return uncertain(result.operation, current.operation, Step.PREPARE)
            }
            if (!isPreparing(preparing) || preparing.operation != current.operation) {
                return Result.Blocked(Failure.INVALID_RECEIPT)
            }
            currentCheck(current)?.let { return it }
            val previous = current
            when (val write = witness.accept(previous, preparing)) {
                is WitnessWrite.Stored -> {
                    if (write.newlyAuthorized ||
                        !transition(previous, write.record, WitnessPhase.ACCEPTED, preparing)
                    ) {
                        return Result.Blocked(Failure.INVALID_RECEIPT)
                    }
                    current = write.record
                }
                is WitnessWrite.NotRetained -> return Result.Blocked(write.reason)
                WitnessWrite.Stale -> return Result.Superseded
                is WitnessWrite.Indeterminate ->
                    return uncertain(write.operation, current.operation, Step.ACCEPT_WITNESS)
            }
        }
        if (current.phase == WitnessPhase.ACCEPTED) {
            currentCheck(current)?.let { return it }
            val preparing = requireNotNull(current.receipt)
            val bound = when (val result = journal.bind(preparing, current.witnessId)) {
                is JournalResult.Observed -> result.receipt
                is JournalResult.NotRetained -> return Result.Blocked(result.reason)
                is JournalResult.Indeterminate ->
                    return uncertain(result.operation, current.operation, Step.BIND_JOURNAL)
            }
            if (!isBound(bound) || bound.operation != current.operation ||
                bound.creationNonce != preparing.creationNonce || bound.witnessId != current.witnessId
            ) return Result.Blocked(Failure.INVALID_RECEIPT)
            currentCheck(current)?.let { return it }
            val previous = current
            when (val write = witness.complete(previous, bound)) {
                is WitnessWrite.Stored -> {
                    if (write.newlyAuthorized || !transition(previous, write.record, WitnessPhase.BOUND, bound)) {
                        return Result.Blocked(Failure.INVALID_RECEIPT)
                    }
                    current = write.record
                }
                is WitnessWrite.NotRetained -> return Result.Blocked(write.reason)
                WitnessWrite.Stale -> return Result.Superseded
                is WitnessWrite.Indeterminate ->
                    return uncertain(write.operation, current.operation, Step.COMPLETE_WITNESS)
            }
        }
        return inspectBound(current)
    }

    private suspend fun inspectBound(record: WitnessRecord): Result {
        currentCheck(record)?.let { return it }
        val expected = requireNotNull(record.receipt)
        return when (val result = journal.reconcile(record.operation, expected.creationNonce)) {
            is JournalResult.NotRetained -> Result.Blocked(result.reason)
            is JournalResult.Indeterminate -> uncertain(result.operation, record.operation, Step.INSPECT)
            is JournalResult.Observed -> {
                if (result.receipt != expected || !isBound(result.receipt)) {
                    Result.Blocked(Failure.IDENTITY_MISMATCH)
                } else {
                    currentCheck(record) ?: Result.BoundSnapshot(record, result.receipt)
                }
            }
        }
    }

    private suspend fun currentCheck(expected: WitnessRecord): Result? = when (val read = witness.observe()) {
        Observation.UnknownOrLost -> Result.NeedsAcknowledgment
        is Observation.Failed -> Result.Blocked(read.reason)
        is Observation.Current -> if (read.record == expected) null else Result.Superseded
    }

    private fun transition(
        previous: WitnessRecord,
        next: WitnessRecord,
        phase: WitnessPhase,
        receipt: CreationReceipt,
    ): Boolean =
        previous.revision < Long.MAX_VALUE && next.revision == previous.revision + 1 &&
            next.operation == previous.operation && next.witnessId == previous.witnessId &&
            next.phase == phase && next.receipt == receipt && valid(next)

    private fun valid(record: WitnessRecord): Boolean = record.revision > 0 && when (record.phase) {
        WitnessPhase.AUTHORIZED -> record.receipt == null
        WitnessPhase.ACCEPTED -> record.receipt?.let { isPreparing(it) && it.operation == record.operation } == true
        WitnessPhase.BOUND -> record.receipt?.let {
            isBound(it) && it.operation == record.operation && it.witnessId == record.witnessId
        } == true
    }

    private fun isPreparing(receipt: CreationReceipt): Boolean =
        receipt.phase == Phase.PREPARING && receipt.revision == 1L && receipt.witnessId == null

    private fun isBound(receipt: CreationReceipt): Boolean =
        receipt.phase == Phase.BOUND && receipt.revision == 2L && receipt.witnessId != null

    private fun uncertain(actual: Operation, expected: Operation, step: Step): Result =
        if (actual == expected) Result.Indeterminate(expected, step) else Result.Blocked(Failure.INVALID_RECEIPT)
}
