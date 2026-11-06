// AndroidOnly: WP-201 Selection-time origin proof and original channel-message creation.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.TextType
import java.time.Instant
import java.util.UUID

/** Only the private data issuer's authenticated implementation is accepted; public markers grant nothing. */
interface OriginalChannelSelectionProof

/** Binds the committed message incarnation to its original origin; not a dispatch admission. */
interface OriginalChannelCreationReceipt

data class SelectedOriginalChannel(
    val proof: OriginalChannelSelectionProof,
    val channel: ChannelDTO,
    val sessionToken: SessionToken,
)

/** Snapshot all fields once before creation; never regenerate UUID, timestamp or nanos on uncertainty. */
data class OriginalChannelCreationIntent(
    val requestId: UUID,
    val messageId: UUID,
    val selection: OriginalChannelSelectionProof,
    val sessionToken: SessionToken,
    val text: String,
    val textType: TextType,
    val timestamp: UInt,
    val createdAt: Instant,
)

data class CreatedOriginalChannel(
    val receipt: OriginalChannelCreationReceipt,
    val message: MessageDTO,
    val sessionToken: SessionToken,
)

enum class OriginalChannelCreationRejection {
    INVALID_PROOF, INVALID_SESSION, NOT_ELIGIBLE, MESSAGE_KEY_CONFLICT, REQUEST_CONFLICT,
}

sealed interface OriginalChannelSelectionResult {
    data class Selected(val capture: SelectedOriginalChannel) : OriginalChannelSelectionResult
    data object Stale : OriginalChannelSelectionResult
    data class Rejected(
        val reason: OriginalChannelCreationRejection,
    ) : OriginalChannelSelectionResult
}

/**
 * Read-only request evidence, NEVER an enqueue/creation/dispatch grant or a replacement receipt.
 * Current means the recorded incarnation is present, not that it is sendable or unchanged.
 * Unavailable retains committed identity despite deletion/replacement/retirement.
 * NotRecorded is an observation, NOT rollback/no-effect proof or permission to create/retry.
 */
sealed interface OriginalChannelCreationObservation {
    sealed interface Recorded : OriginalChannelCreationObservation
    data class RecordedCurrent(
        val requestId: UUID,
        val key: EntityKey,
    ) : Recorded
    data class RecordedUnavailable(
        val requestId: UUID,
        val key: EntityKey,
    ) : Recorded
    data object NotRecorded : OriginalChannelCreationObservation
    data object Conflict : OriginalChannelCreationObservation
}

sealed interface OriginalChannelCreationResult {
    data class Created(val capture: CreatedOriginalChannel) : OriginalChannelCreationResult
    /** Exact duplicate request; observation is RecordedCurrent or RecordedUnavailable, never a grant. */
    data class AlreadyRecorded(
        val observation: OriginalChannelCreationObservation.Recorded,
    ) : OriginalChannelCreationResult
    data object Stale : OriginalChannelCreationResult
    data class Rejected(
        val reason: OriginalChannelCreationRejection,
    ) : OriginalChannelCreationResult
}

/**
 * Optional independently implemented role; no default issuer, schema or UI integration is supplied.
 * Requires durable channel occupant epochs, original message incarnation/origin and complete request
 * bindings, with raw-write fencing for replacement, delete/recreate and same-value generic upsert.
 * Existing UUID/radio/index DTOs and held history/intent handles are not interchangeable proofs.
 *
 * Missing/legacy proof must retain draft text with NO grant. No no-proof/UUID-only creation, existing
 * row upsert, enqueue, retry or queued-attempt operation is exposed here. Their original transaction
 * and separately admitted contracts remain prerequisites; this role never mints dispatch authority.
 */
interface OriginalChannelCreationAuthority {
    /**
     * Call ONLY at the actual NEW channel-selection/composer-binding event BEFORE text composition.
     * In one read transaction check expectedChannel's selected identity against the actual row and
     * return its canonical snapshot plus authenticated proof of exact session, radio/index/occupant
     * and durable epoch. expectedChannel is a stale-selection assertion, never authority.
     * Retain this exact capture with the draft. Never call to refresh proof for existing composed text.
     * Same-UUID delete/recreate rotates the epoch and invalidates the old proof.
     */
    suspend fun selectOriginalChannel(
        sessionToken: SessionToken,
        expectedChannel: ChannelDTO,
    ): OriginalChannelSelectionResult

    /**
     * Validate the ORIGINAL proof's issuer/store/session and exact epoch/slot, not today's recapture.
     * In ONE transaction reserve requestId with ALL intent fields including selection's authenticated
     * origin/epoch and Instant seconds+nanos, insert the message WITHOUT upsert, and record original
     * message incarnation -> origin. Return actual persisted MessageDTO and original session binding.
     * Fresh successful return alone carries a private creation receipt for that committed incarnation.
     *
     * Compare durable request binding BEFORE mutation on reuse: exact duplicate returns ONLY recorded
     * observation, never a receipt; differing complete inputs return REQUEST_CONFLICT. Reuse cannot
     * insert, bump, rebind or remint, even after generic mutation, process/session replacement or loss
     * of the original return. Stale proof, duplicate message key and rejected inputs write nothing.
     * Current session/proof failure does not turn a recorded request into an unrecorded new request.
     *
     * Exceptions/cancellation propagate unchanged; postcommit return loss is UNKNOWN, not rollback.
     * Caller retains exact intent and text; no automatic create retry/new request or late token mint.
     * Service synchronously retains Created receipt+actual DTO+session before its subsequent ownership
     * check or canceled composer return. Lost receipt cannot be reconstructed by inspecting the row.
     */
    suspend fun createOriginalChannelMessage(
        intent: OriginalChannelCreationIntent,
    ): OriginalChannelCreationResult

    /**
     * Read persisted request binding using the retained complete original intent, without writes.
     * Compare all bound fields/authenticated original proof identity without refreshing its epoch;
     * retired original sessions/proofs may be observed, never reactivated. A conflicting binding
     * returns Conflict. Only the recorded original incarnation qualifies for RecordedCurrent;
     * same-key replacement is RecordedUnavailable. Preserve recorded key when unavailable.
     * Never mint receipt/proof, retry, enqueue or upgrade authority, including after NotRecorded.
     * Exceptions/cancellation propagate; no fabricated observation on storage failure.
     */
    suspend fun inspectOriginalChannelCreation(
        intent: OriginalChannelCreationIntent,
    ): OriginalChannelCreationObservation
}
