// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/MessageFooter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant

/**
 * Hop / path / region footer slots derived by [MessageFragmentBuilder]. [status] stays the raw enum and
 * [sendTimeToShow] a raw instant so localization and 12h/24h formatting happen at render time.
 */
data class MessageFooter(
    val showHop: Boolean,
    val hopCount: Long,
    val formattedPath: String?,
    /** Compact chip text; null hides the region chip. Multi-match is the slash-joined label. */
    val regionToShow: String?,
    /** Candidate region names for popover and accessibility. More than one entry is ambiguous. */
    val regionMatchNames: SnapshotList<String> = SnapshotList.empty(),
    /** Clock-corrected send time shown inside the bubble; null hides it. */
    val sendTimeToShow: Instant?,
    /** Whether a corrected timestamp replaced an invalid sender clock; drives the warning badge. */
    val sendTimeWasCorrected: Boolean,
    val showStatusRow: Boolean,
    val status: MessageStatus,
    /** Channel broadcast vs DM, so a DM's transient SENT reads as "Sending..." at render time. */
    val isChannelMessage: Boolean,
    val heardRepeats: Long,
    /** Incoming extra-arrival ear chip; outgoing repeats use [heardRepeats] with [showStatusRow]. */
    val showHeardCount: Boolean = false,
    val retryAttempt: Long,
    val maxRetryAttempts: Long,
    val sendCount: Long,
) {
    /** True when [regionMatchNames] has more than one entry. Derived, not stored. */
    val regionIsAmbiguous: Boolean get() = regionMatchNames.size > 1

    /** Returns a new footer with [status] overridden. */
    fun with(status: MessageStatus): MessageFooter = copy(status = status)
}
