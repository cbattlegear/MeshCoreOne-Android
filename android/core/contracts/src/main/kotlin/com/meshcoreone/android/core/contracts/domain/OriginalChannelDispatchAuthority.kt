// AndroidOnly: WP-201 Original message/pending admission validation within a participating-writer guard.
package com.meshcoreone.android.core.contracts.domain

/**
 * Authenticated producer-issued ORIGINAL preparation, not a key/DTO/receipt or a current successor.
 * The private issuing implementation binds the original process/store/session, message incarnation
 * and full mutation revision, exact optional pending incarnation/revision/relationship and original
 * prepared wire tuple. Public implementations or reconstructed fields confer no authority.
 *
 * This declaration supplies no issuer, factory or original-prepare implementation. A separately
 * admitted producer must mint it in the actual original preparation transaction. A later fetch,
 * same-value row, captured UUID, new current handle or inspection of today's capture cannot do so.
 */
interface OriginalChannelDispatchAdmission

enum class OriginalChannelDispatchRejection {
    INVALID_ADMISSION, NOT_ELIGIBLE, CONFLICT,
}

sealed interface OriginalChannelDispatchResult<out T> {
    /**
     * The callback returned normally inside the validation/mutation interval. value is precisely
     * its result, not a storage acceptance receipt, recipient delivery or exactly-once certificate.
     * No validation grant escapes the interval, and this result cannot authorize another callback.
     */
    data class Executed<T>(val value: T) : OriginalChannelDispatchResult<T>

    /** Original row is missing or identity/revision/session changed; callback was NOT invoked. */
    data object Stale : OriginalChannelDispatchResult<Nothing>
    /** Foreign/invalid handle or ineligible/conflicting original relationship; no callback. */
    data class Rejected(
        val reason: OriginalChannelDispatchRejection,
    ) : OriginalChannelDispatchResult<Nothing>
}

/**
 * Separate optional ORIGINAL-admission validation role, independent of reaction/ACK/completion APIs.
 * No default implementation, key-based capture, refreshed DTO or fake issuer is supplied. Missing
 * original preparation/identity/participating-writer support means this capability is unavailable.
 *
 * Caller holds the separately admitted occupant fence and live connection/identity ownership before
 * entry. Implementation acquires the PROCESS participating-writer dispatch lane, then validates the
 * original admission through the same store's read transaction/context mutex. Release that read
 * transaction AND per-store mutex AFTER validation and BEFORE invoking block, while retaining the
 * shared mutation lane throughout. No Room transaction or per-store mutex spans transport.
 * Do not reacquire the dispatch lane from that read or acquire occupant/identity locks inside it.
 * The order is:
 * occupant fence -> connection/identity -> shared mutation guard -> context mutex -> Room read.
 *
 * Under the SAME guard interval validate issuer/store/process and initiating session, exact ORIGINAL
 * message incarnation AND expected full-mutation revision, original outgoing channel/payload/wire
 * timestamp and any original recorded occupant binding. Validate the exact optional pending ID,
 * incarnation/revision, CHANNEL relationship, resend mode, payload and attempt-count state captured
 * by original preparation. Expected revisions are from that completed preparation, not a later
 * revalidation capture. Absence of a captured pending is not permission to substitute a newly
 * inserted pending or silently change public/queued mode. Original prepare must define its actual
 * no-pending eligibility and competing-pending policy; the validator must enforce that same binding.
 *
 * Delete/recreate, restore, same-value generic DML, another attempt, retired session or current
 * successor must not refresh this original admission. The reaction-only identity revision which
 * preserves benign read/status/path updates is NOT sufficient for full dispatch mutation checks.
 * The actual independent identity schema and original-prepare producer remain prerequisites.
 *
 * Only after valid original checks call block exactly once PER INVOCATION while retaining the guard.
 * block performs the actual already-prepared wire invocation and, on genuine successful transport
 * return, its synchronous RAM-only acceptance note BEFORE returning/releasing. Do not perform
 * repository writes, completion/indexing, notifications, event publication, another admission or
 * lock-order inversion inside block. Participating-writer reentry fails explicitly, not deadlocks.
 * A wire invocation must use the SAME original immutable payload/session captured by preparation;
 * successful validation cannot be used to send a replacement tuple or a different session.
 *
 * Preserve coroutine Job, cancellation and callback result/exception; do not launch a new Job,
 * swallow failures or extend a caller's noncancellable boundary. Cancellation before lane acquisition
 * never invokes block. Failure/cancellation after wire entry may mean MAY_HAVE_WRITTEN: no Stale,
 * Rejected or fabricated unsent result may replace that exception. A thrown callback/acceptance-note
 * return is not proof of no send. No automatic callback retry or physical redispatch is performed.
 * This validation role has no consumption/replay journal; it does not make repeated calls with an
 * otherwise current admission one physical send. Caller-owned attempt/acceptance/fence logic must
 * independently prevent duplicate dispatch. Executed cannot be used to retry an earlier callback.
 *
 * Release the mutation lane only after the actual callback/note interval finishes; completion and
 * publication occur afterward under their own authorities. Current outer occupant/runtime gates
 * remain independently required. A process participating-writer guard does not exclude arbitrary
 * raw SQL/DAO handles or another process; all required writers/restore/close/flush paths must really
 * participate, and remaining bypasses must be blocked before dispatch activation.
 */
interface OriginalChannelDispatchAuthority {
    suspend fun <T> withValidatedOriginalChannelDispatch(
        admission: OriginalChannelDispatchAdmission,
        block: suspend () -> T,
    ): OriginalChannelDispatchResult<T>
}
