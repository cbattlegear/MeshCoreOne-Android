// AndroidOnly: WP-107 One-attempt messaging cancellation provenance using the existing request dispatch fence.
package com.meshcoreone.android.core.protocol.session

/**
 * Fresh accounting for one direct or channel message attempt. Read
 * [cancellationDisposition] only after that call propagates cancellation.
 * DEFINITELY_UNSENT proves this attempt cannot dispatch later; UNKNOWN and
 * MAY_HAVE_WRITTEN require conservative handling. No value proves an ACK,
 * message commit, delivery, or permission to replay a possibly sent message.
 */
class MessageWriteDispatch {
    internal val accounting = ChannelWriteDispatch()

    val cancellationDisposition: ChannelWriteCancellationDisposition
        get() = accounting.cancellationDisposition

    internal fun claim() = accounting.claim()
}
