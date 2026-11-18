// AndroidOnly: WP-201 Nonactivated caller-stable channel creation and queue submission inputs.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ChannelMessageEnvelope
import com.meshcoreone.android.core.model.TextType
import java.util.UUID

/** Captured before saving; timestamps and creation metadata remain producer-owned. */
data class ChannelMessageCreation(
    val messageID: UUID,
    val origin: ChannelHistoryScope,
    val text: String,
    val textType: TextType = TextType.PLAIN,
)

/** Exact enqueue input, not a commit receipt or permission to create, replay or dispatch. */
data class ChannelSubmission(
    val pendingID: UUID,
    val origin: ChannelHistoryScope,
    val envelope: ChannelMessageEnvelope,
) {
    init {
        require(envelope.channelIndex == origin.channelIndex) { "Channel submission index must match its captured origin" }
        require(envelope.channelOccupantID == origin.expectedOccupantID) {
            "Channel submission occupant must match its captured origin"
        }
    }
}
