// AndroidOnly: WP-209 Factory-issued messaging capability for the actual shared channel dispatch boundary.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ChannelHistoryScope
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * The connection factory supplies the same lease used by channel mutations and a plain
 * authoritative store read. Consumers cannot open a lease or reconcile a slot through this API.
 */
class ChannelDispatchBinding internal constructor(
    private val fence: ChannelDispatchFence,
    private val lease: ChannelDispatchFence.Lease,
    private val currentOccupantID: suspend (RadioId, UByte) -> UUID?,
) {
    suspend fun <T> dispatch(
        token: SessionToken,
        connectionIdentity: Any,
        origin: ChannelHistoryScope,
        operation: suspend () -> T,
    ): T {
        currentCoroutineContext().ensureActive()
        if (lease.token != token || lease.connection !== connectionIdentity || origin.radioId != token.radioId) {
            throw ChannelDispatchFailure.RetiredSession()
        }
        return fence.dispatch(
            lease, origin.channelIndex, origin.expectedOccupantID, currentOccupantID, operation,
        )
    }
}
