// AndroidOnly: WP-216 Durable outgoing reaction admission and completion.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.PendingSendDTO
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes

data class OutgoingReactionAdmission(
    val target: MessageDTO,
    val outgoing: MessageDTO,
    val pending: PendingSendDTO,
    val reaction: ReactionDTO,
    val contactPublicKey: Bytes?,
    val channel: ChannelDTO?,
)

sealed interface OutgoingReactionAdmissionResult {
    data class Pending(val pending: PendingSendDTO) : OutgoingReactionAdmissionResult
    data object Completed : OutgoingReactionAdmissionResult
}

interface OutgoingReactionPersisting {
    /** Atomically binds the displayed target, outgoing message and queue row. */
    suspend fun admitOutgoingReaction(admission: OutgoingReactionAdmission): OutgoingReactionAdmissionResult

    suspend fun isOutgoingReaction(message: EntityKey): Boolean

    /** Reject a queued reaction whose bound target is no longer current. */
    suspend fun requireCurrentOutgoingReactionTarget(message: EntityKey)

    /** Returns false for an unresolved wire outcome, without treating it as a failed local commit. */
    suspend fun isOutgoingReactionAcceptanceKnown(message: EntityKey): Boolean

    /** Returns the original reaction inputs; null means this is an ordinary queued message. */
    suspend fun fetchOutgoingReaction(message: EntityKey): ReactionDTO?

    suspend fun completeOutgoingReaction(message: EntityKey)

    /** Retires a changed target only if its exact queued send is provably undispatched. */
    suspend fun retireInvalidOutgoingReaction(message: EntityKey, pendingId: java.util.UUID): Boolean
}
