// AndroidOnly: WP-201 Initiation-captured channel attribution shared without activating history queries.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.RadioId
import java.util.UUID

/** Attribution context only: construction does not establish authority, persistence or a wire permit. */
data class ChannelHistoryScope(val radioId: RadioId, val channelIndex: UByte, val expectedOccupantID: UUID) {
    companion object {
        fun from(snapshot: ChannelOccupantSnapshot): ChannelHistoryScope =
            ChannelHistoryScope(snapshot.channel.radioId, snapshot.channel.index, snapshot.occupantID)
    }
}
