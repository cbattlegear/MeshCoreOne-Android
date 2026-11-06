// AndroidOnly: WP-214 Non-redrivable custody after a known committed channel save.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ChannelReactionIngressResult
import com.meshcoreone.android.core.contracts.domain.ChannelReactionOccurrence
import com.meshcoreone.android.core.contracts.domain.ChannelReceiveEnvelope
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.services.reactions.ChannelReactionUnretainedWork
import kotlin.coroutines.cancellation.CancellationException

sealed interface CommittedChannelMessageReceipt {
    data class Authority(val result: ChannelReactionIngressResult) : CommittedChannelMessageReceipt {
        init { require(result !is ChannelReactionIngressResult.Rejected) }
    }

    data object GenericSaveCompleted : CommittedChannelMessageReceipt
}

/** The last stage known to have completed; a later throwing stage may also have applied. */
enum class ChannelMessageDeliveryStage {
    SAVE_COMMITTED,
    HARVEST_COMPLETED,
    REACTION_PREPARATION_COMPLETED,
    CHANNEL_DELIVERY_COMPLETED,
    CONVERSATIONS_CHANGED,
}

/** A typed replay overflow can be returned without an exception; preserve a cause when one exists. */
data class ChannelMessageReactionOverflow(
    val work: ChannelReactionUnretainedWork.Native,
    val originalCause: Exception? = null,
)

data class ChannelMessageDeliveryIncomplete(
    val envelope: ChannelReceiveEnvelope,
    val occurrence: ChannelReactionOccurrence,
    val messageDTO: MessageDTO,
    val receipt: CommittedChannelMessageReceipt,
    val lastCompletedStage: ChannelMessageDeliveryStage,
    val cause: Exception,
    val refreshedDTO: MessageDTO? = null,
    val reactionOverflow: ChannelMessageReactionOverflow? = null,
) {
    init {
        require(occurrence.envelope === envelope)
        require(messageDTO.radioId == envelope.session.radioId)
        require(messageDTO.channelIndex == envelope.message.channelIndex)
        require(messageDTO.contactID == null && messageDTO.direction == MessageDirection.INCOMING)
        if (refreshedDTO != null) {
            require(refreshedDTO.id == messageDTO.id && refreshedDTO.radioId == messageDTO.radioId)
            require(refreshedDTO.channelIndex == messageDTO.channelIndex && refreshedDTO.contactID == null)
            require(refreshedDTO.direction == MessageDirection.INCOMING)
        }
        when (receipt) {
            is CommittedChannelMessageReceipt.Authority -> when (val result = receipt.result) {
                is ChannelReactionIngressResult.Inserted -> {
                    require(result.captured.message.id == messageDTO.id)
                    require(result.captured.message.radioId == messageDTO.radioId)
                    require(result.captured.message.channelIndex == messageDTO.channelIndex)
                }
                is ChannelReactionIngressResult.InsertedUnproven -> {
                    require(result.message.id == messageDTO.id)
                    require(result.message.radioId == messageDTO.radioId)
                    require(result.message.channelIndex == messageDTO.channelIndex)
                }
                is ChannelReactionIngressResult.Existing -> require(result.key == EntityKey(messageDTO.radioId, messageDTO.id))
                is ChannelReactionIngressResult.Rejected -> error("A rejected save is not a committed receipt")
            }
            CommittedChannelMessageReceipt.GenericSaveCompleted -> Unit
        }
    }
}

class ChannelMessageDeliveryIncompleteException(
    val work: ChannelMessageDeliveryIncomplete,
) : Exception("Committed channel message delivery is incomplete; redrive is not permitted", work.cause)

class ChannelMessageDeliveryIncompleteCancellation(
    val work: ChannelMessageDeliveryIncomplete,
    original: CancellationException,
) : CancellationException("Committed channel message delivery was cancelled; redrive is not permitted") {
    init {
        require(work.cause === original)
        initCause(original)
    }
}
