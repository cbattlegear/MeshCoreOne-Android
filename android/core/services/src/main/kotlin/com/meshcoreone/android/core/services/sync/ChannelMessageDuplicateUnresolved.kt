// AndroidOnly: WP-214 Non-redrivable handoff for an unresolved channel duplicate.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ChannelReactionOccurrence
import com.meshcoreone.android.core.contracts.domain.ChannelReceiveEnvelope
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import kotlin.coroutines.cancellation.CancellationException

sealed interface ChannelMessageDuplicateUnresolvedReason {
    /** The candidate is only a read snapshot; it grants no target or duplicate authority. */
    data class ContentKeyHit(val observedCandidate: MessageDTO) : ChannelMessageDuplicateUnresolvedReason

    data class LookupFailed(val cause: Exception) : ChannelMessageDuplicateUnresolvedReason
}

data class ChannelMessageDuplicateUnresolved(
    val envelope: ChannelReceiveEnvelope,
    val occurrence: ChannelReactionOccurrence,
    val messageDTO: MessageDTO,
    val reason: ChannelMessageDuplicateUnresolvedReason,
) {
    init {
        require(occurrence.envelope === envelope)
        require(messageDTO.radioId == envelope.session.radioId)
        require(messageDTO.channelIndex == envelope.message.channelIndex)
        require(messageDTO.contactID == null && messageDTO.direction == MessageDirection.INCOMING)
    }
}

class ChannelMessageDuplicateUnresolvedException(
    val work: ChannelMessageDuplicateUnresolved,
) : Exception(
    "Channel message duplicate identity is unresolved; no save was attempted and redrive is not permitted",
    (work.reason as? ChannelMessageDuplicateUnresolvedReason.LookupFailed)?.cause,
) {
    init {
        require((work.reason as? ChannelMessageDuplicateUnresolvedReason.LookupFailed)?.cause !is CancellationException)
    }
}

class ChannelMessageDuplicateUnresolvedCancellation(
    val work: ChannelMessageDuplicateUnresolved,
    original: CancellationException,
) : CancellationException(
    "Channel message duplicate lookup was cancelled; no save was attempted and redrive is not permitted",
) {
    init {
        require((work.reason as? ChannelMessageDuplicateUnresolvedReason.LookupFailed)?.cause === original)
        initCause(original)
    }
}
