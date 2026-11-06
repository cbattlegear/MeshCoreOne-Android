// AndroidOnly: WP-214 Non-redrivable custody for rejected or inconsistent channel save receipts.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.ChannelReactionIngressResult
import com.meshcoreone.android.core.contracts.domain.ChannelReactionOccurrence
import com.meshcoreone.android.core.contracts.domain.ChannelReceiveEnvelope
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection

sealed interface ChannelMessageUnusableReceipt {
    val result: ChannelReactionIngressResult
    val cause: Exception?

    data class Rejected(
        override val result: ChannelReactionIngressResult.Rejected,
        override val cause: Exception? = null,
    ) : ChannelMessageUnusableReceipt

    data class Inconsistent(
        override val result: ChannelReactionIngressResult,
        override val cause: Exception? = null,
    ) : ChannelMessageUnusableReceipt {
        init { require(result !is ChannelReactionIngressResult.Rejected) }
    }
}

data class ChannelMessageIngressUnusableReceipt(
    val envelope: ChannelReceiveEnvelope,
    val occurrence: ChannelReactionOccurrence,
    val messageDTO: MessageDTO,
    val receipt: ChannelMessageUnusableReceipt,
) {
    init {
        require(occurrence.envelope === envelope)
        require(messageDTO.radioId == envelope.session.radioId)
        require(messageDTO.channelIndex == envelope.message.channelIndex)
        require(messageDTO.contactID == null && messageDTO.direction == MessageDirection.INCOMING)
    }
}

class ChannelMessageIngressUnusableReceiptException(
    val work: ChannelMessageIngressUnusableReceipt,
) : Exception("Channel message save returned an unusable receipt; redrive is not permitted", work.receipt.cause)
