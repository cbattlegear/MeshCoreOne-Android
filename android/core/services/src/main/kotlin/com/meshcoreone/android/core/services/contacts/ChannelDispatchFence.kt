// AndroidOnly: WP-209 Shared channel dispatch, occupant mutation and connection admission ordering.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutation
import com.meshcoreone.android.core.contracts.domain.ChannelDispatchAdmission
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import java.util.UUID
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

sealed class ChannelDispatchFailure(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class RetiredSession : ChannelDispatchFailure("The initiating channel session is no longer active.")
    class UnknownOccupant : ChannelDispatchFailure("The intended channel occupant cannot be proved.")
    class ReplacedOccupant : ChannelDispatchFailure("The intended channel occupant has been replaced.")
    class UnreconciledSession : ChannelDispatchFailure("This channel has not been authoritatively reconciled on the active connection.")
    class UnsettledDispatch(cause: Throwable) :
        ChannelDispatchFailure("Channel dispatch is uncertain; this radio remains quarantined.", cause)
}

/**
 * Production uses [PROCESS], also shared by the persistence gate's default instance.
 *
 * Lock order is operation lane -> service-local mutation lock (if any) -> identity lane -> Room.
 * The operation lane is deliberately process-wide: restore must exclude the entire radio
 * mutation-through-persistence interval, not just its final database write. No observer, cache,
 * receipt-publication or UI callback belongs in an operation.
 *
 * This primitive alone does not establish that every caller participates, or protect against
 * another client physically changing the radio. An ambiguous exchange quarantines its radio
 * across logical reconnects. There is intentionally no "new generation clears uncertainty" API.
 * A transport that never settles retains the operation lane, including for other radios; only
 * the transport owner can establish a bounded drain. A timeout alone cannot release exclusion.
 */
class ChannelDispatchFence(
    private val identity: ChannelIdentityMutation = ChannelIdentityMutex.PROCESS,
) {
    class Lease internal constructor(
        internal val owner: ChannelDispatchFence,
        val token: SessionToken,
        internal val connection: Any,
        internal val admission: ChannelDispatchAdmission,
    ) {
        internal var retired = false
        internal val reconciledSlots = mutableSetOf<UByte>()
    }

    private val stateLock = Any()
    private var epoch: ProcessEpoch? = null
    private val sessions = mutableMapOf<RadioId, Lease>()
    private val uncertainRadios = mutableMapOf<RadioId, Throwable>()

    /**
     * Only the connection factory opens leases. Multiple services of the SAME connection share
     * one lease; a successor requires retirement or owner revocation, and an older token cannot reopen.
     * The connection identity must be the actual session object, never an address or slot.
     */
    fun openSession(
        token: SessionToken, connectionIdentity: Any, admission: ChannelDispatchAdmission,
    ): Lease {
        val permit = admission.tryAcquire(token, connectionIdentity) ?: throw ChannelDispatchFailure.RetiredSession()
        try {
            return synchronized(stateLock) {
                if (epoch != null && epoch != token.epoch) throw ChannelDispatchFailure.RetiredSession()
                uncertainRadios[token.radioId]?.let { throw ChannelDispatchFailure.UnsettledDispatch(it) }
                val previous = sessions[token.radioId]
                if (previous != null) {
                    if (!previous.retired && !previous.admission.isRevoked &&
                        previous.token == token && previous.connection === connectionIdentity && previous.admission === admission
                    ) {
                        return@synchronized previous
                    }
                    if ((!previous.retired && !previous.admission.isRevoked) ||
                        token.generation.value <= previous.token.generation.value
                    ) {
                        throw ChannelDispatchFailure.RetiredSession()
                    }
                }
                epoch = token.epoch
                Lease(this, token, connectionIdentity, admission).also { sessions[token.radioId] = it }
            }
        } finally {
            permit.complete()
        }
    }

    /** Invalidate queued admission immediately, without waiting behind a held transport call. */
    fun retire(lease: Lease) = synchronized(stateLock) {
        require(lease.owner === this) { "A channel lease belongs to its issuing fence" }
        lease.admission.revoke()
        lease.retired = true
    }

    /**
     * The channel producer owns its ACK/save and definite-unsent classification. It must call
     * [quarantine] INSIDE this block for any write it cannot prove settled, before releasing it.
     * Publish collected completions only after this method returns or throws.
     */
    suspend fun <T> withMutation(lease: Lease, operation: suspend () -> T): T =
        identity.withChannelOperation {
            requireCurrent(lease)
            val permit = acquire(lease)
            try {
                operation()
            } finally {
                permit.complete()
            }
        }

    /**
     * The persistence producer already owns the outer lane and supplies its scoped facade.
     * Validate the gate-minted proof instead of recursively acquiring the non-reentrant lane.
     * Do not change Jobs inside [operation]; post-ACK cancellation remains the channel
     * producer's existing uncertainty/receipt contract, not guaranteed persistence.
     */
    suspend fun <T> withScopedMutation(
        lease: Lease,
        scope: ChannelIdentityWriteScope,
        operation: suspend () -> T,
    ): T {
        identity.requireChannelOperationScope(scope)
        requireCurrent(lease)
        val permit = acquire(lease)
        try {
            return operation()
        } finally {
            try {
                identity.requireChannelOperationScope(scope)
            } finally {
                permit.complete()
            }
        }
    }

    fun quarantine(lease: Lease, failure: Throwable) = synchronized(stateLock) {
        require(lease.owner === this) { "A channel lease belongs to its issuing fence" }
        uncertainRadios.putIfAbsent(lease.token.radioId, failure)
        Unit
    }

    /**
     * The channel producer records only slots actually read/ACKed and successfully persisted
     * inside this operation. Cached rows, READY and another connection's reads are not proof.
     * A retired operation may finish persistence, but cannot admit its old lease for dispatch.
     */
    suspend fun recordReconciled(
        lease: Lease, scope: ChannelIdentityWriteScope, indices: Collection<UByte>,
    ) {
        identity.requireChannelOperationScope(scope)
        synchronized(stateLock) {
            requireCurrent(lease)
            lease.reconciledSlots.addAll(indices)
        }
    }

    internal fun requireActive(lease: Lease) = requireCurrent(lease)

    fun bind(
        lease: Lease,
        currentOccupantID: suspend (RadioId, UByte) -> UUID?,
    ): ChannelDispatchBinding {
        requireCurrent(lease)
        return ChannelDispatchBinding(this, lease, currentOccupantID)
    }

    suspend fun forgetReconciled(
        lease: Lease, scope: ChannelIdentityWriteScope, indices: Collection<UByte>,
    ) {
        identity.requireChannelOperationScope(scope)
        synchronized(stateLock) {
            requireCurrent(lease)
            lease.reconciledSlots.removeAll(indices.toSet())
        }
    }

    /**
     * [currentOccupantID] is a plain authoritative read, not a mint/reconciliation operation.
     * [operation] must use this lease's connection and [index], await the actual protocol call,
     * and may update an operation-local outcome
     * receipt before returning, but must not publish callbacks or reacquire either lane.
     *
     * After admission, caller cancellation is deferred until that call settles. Cancellation
     * can still be thrown on return from NonCancellable AFTER command acceptance: the producer
     * must use its inside-operation receipt, not interpret that cancellation as an unsent row.
     * Session-owned cancellation or any ambiguous failure poisons the radio before unlocking,
     * because the protocol serializer may still own a late transport write.
     */
    suspend fun <T> dispatch(
        lease: Lease,
        index: UByte,
        expectedOccupantID: UUID?,
        currentOccupantID: suspend (RadioId, UByte) -> UUID?,
        operation: suspend () -> T,
    ): T = identity.withChannelOperation {
        requireCurrent(lease)
        if (expectedOccupantID == null) throw ChannelDispatchFailure.UnknownOccupant()
        synchronized(stateLock) {
            if (index !in lease.reconciledSlots) throw ChannelDispatchFailure.UnreconciledSession()
        }
        identity.withChannelIdentityMutation {
            val current = currentOccupantID(lease.token.radioId, index) ?: throw ChannelDispatchFailure.UnknownOccupant()
            if (current != expectedOccupantID) throw ChannelDispatchFailure.ReplacedOccupant()
            currentCoroutineContext().ensureActive()
            requireCurrent(lease)
            val permit = acquire(lease)
            try {
                withContext(NonCancellable) {
                    try {
                        operation()
                    } catch (rejected: MeshCoreException.DeviceError) {
                        throw rejected
                    } catch (failure: Exception) {
                        quarantine(lease, failure)
                        throw failure
                    }
                }
            } finally {
                permit.complete()
            }
        }
    }

    private fun requireCurrent(lease: Lease) = synchronized(stateLock) {
        if (lease.owner !== this || lease.retired || lease.admission.isRevoked || sessions[lease.token.radioId] !== lease) {
            throw ChannelDispatchFailure.RetiredSession()
        }
        uncertainRadios[lease.token.radioId]?.let { throw ChannelDispatchFailure.UnsettledDispatch(it) }
    }

    private fun acquire(lease: Lease): ChannelDispatchAdmission.Permit =
        lease.admission.tryAcquire(lease.token, lease.connection) ?: throw ChannelDispatchFailure.RetiredSession()

    companion object {
        val PROCESS = ChannelDispatchFence(ChannelIdentityMutex.PROCESS)
    }
}
