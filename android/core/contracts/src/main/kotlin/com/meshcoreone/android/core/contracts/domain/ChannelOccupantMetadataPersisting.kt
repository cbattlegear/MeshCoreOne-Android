// AndroidOnly: WP-202 Conditional channel-message metadata persistence against a supplied occupant scope.
package com.meshcoreone.android.core.contracts.domain

import java.time.Instant

interface ChannelOccupantMetadataPersisting {
    /**
     * Applies message metadata only if this exact occupant is still the resolved Room slot occupant.
     * Null clears the last-message date; the supplied scope is not RF-origin proof.
     * Storage failures remain typed exceptions.
     */
    suspend fun applyChannelMessageMetadataIfCurrentOccupant(
        scope: ChannelHistoryScope,
        lastMessageDate: Instant?,
        incrementUnreadCount: Boolean,
    ): ChannelOccupantMetadataMutationOutcome
}
