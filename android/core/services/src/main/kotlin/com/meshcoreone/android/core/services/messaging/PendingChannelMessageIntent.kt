// AndroidOnly: WP-208 Caller-held original channel scope and producer timestamps for pending creation.
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.ChannelMessageCreation
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Prepare once before saving, then install alongside a fresh [PendingMessageCreation] receipt.
 * Retain these exact arguments for explicit reconciliation; neither cancellation nor an absent
 * readback authorizes creating a new intent or substituting the current channel occupant.
 */
class PendingChannelMessageIntent internal constructor(
    val creation: ChannelMessageCreation,
    val timestamp: UInt,
    val createdAt: Instant,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<PendingChannelMessageIntent>
}
