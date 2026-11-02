// AndroidOnly: WP-107 Producer-owned cancellation provenance for a single channel write.
package com.meshcoreone.android.core.protocol.session

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

enum class ChannelWriteCancellationDisposition {
    UNKNOWN,
    DEFINITELY_UNSENT,
    MAY_HAVE_WRITTEN,
}

/**
 * One-use accounting for the four-argument [ChannelSessionOps.setChannel] operation.
 * Read [cancellationDisposition] only after that call propagates cancellation. Only
 * DEFINITELY_UNSENT proves this attempt cannot dispatch later. No value proves an ACK,
 * rollback, persistence commit, or that a channel slot is free.
 */
class ChannelWriteDispatch {
    private val claimed = AtomicBoolean(false)
    private val disposition = AtomicReference(ChannelWriteCancellationDisposition.UNKNOWN)

    val cancellationDisposition: ChannelWriteCancellationDisposition get() = disposition.get()

    internal fun claim() {
        require(claimed.compareAndSet(false, true)) { "ChannelWriteDispatch requires a fresh handle per call" }
    }

    internal fun cancelled(value: ChannelWriteCancellationDisposition) {
        disposition.compareAndSet(ChannelWriteCancellationDisposition.UNKNOWN, value)
    }
}
