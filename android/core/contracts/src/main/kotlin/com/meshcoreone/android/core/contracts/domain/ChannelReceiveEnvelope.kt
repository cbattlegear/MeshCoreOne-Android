// AndroidOnly: WP-214 Nonactivated channel receive-origin carrier; host occurrence identity is not firmware proof.
package com.meshcoreone.android.core.contracts.domain

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import java.util.UUID

sealed interface ChannelReceiveOrigin {
    data object Unproved : ChannelReceiveOrigin

    /**
     * Reserved for a separately admitted authoritative receive producer. There is deliberately no
     * constructor or factory available to callers: a current slot, key or UUID cannot establish origin.
     */
    class Proved private constructor(
        val session: SessionToken,
        val message: ChannelMessage,
        val occupantID: UUID,
    ) : ChannelReceiveOrigin
}

/**
 * [occurrenceID] identifies an in-process delivery, not a radio packet or channel occupant.
 * The same occurrence survives buffering/retry; distinct equal wire messages remain distinct.
 */
data class ChannelReceiveEnvelope(
    val occurrenceID: UUID,
    val session: SessionToken,
    val message: ChannelMessage,
    val context: DeliveryContext,
    val origin: ChannelReceiveOrigin,
) {
    init {
        if (origin is ChannelReceiveOrigin.Proved) {
            require(origin.session == session && origin.message === message) {
                "Receive proof belongs to a different session or parsed message occurrence"
            }
        }
    }
}

/** Separate opt-in contract; existing MessagePollingServiceProtocol consumers are unchanged. */
interface ChannelReceiveEnvelopePolling {
    /**
     * Replaces the channel consumer. Install before receiving to opt into occurrence-aware echo pairing;
     * changing consumers cannot recover events already coalesced by legacy matching.
     * [currentChannel] is lookup metadata, never receive-origin proof.
     */
    suspend fun setChannelReceiveEnvelopeHandler(
        handler: suspend (envelope: ChannelReceiveEnvelope, currentChannel: ChannelDTO?) -> Unit,
    )
}
