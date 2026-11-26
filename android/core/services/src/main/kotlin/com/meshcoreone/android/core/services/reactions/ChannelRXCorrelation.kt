// PortedFrom: MC1Services/Sources/MC1Services/Utilities/ChannelRXCorrelation.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.services.rendering.SwiftText

/**
 * WP-208 stand-in: a package-local copy of `ChannelRXCorrelation.matching` (owned by WP-208; same signature as
 * WP-208's Kotlin `messaging.ChannelRXCorrelation`). Delete this file and swap the import once WP-208 lands.
 *
 * Pure matcher over decrypted RX log entries: keeps decrypted group-text rows whose `"sender: body"` rebuilds
 * [deduplicationKey], oldest first (stable for equal receive times). Keys compare by canonical equivalence,
 * as Swift `String ==` does.
 */
internal object ChannelRXCorrelation {
    fun matching(entries: List<RxLogEntryDTO>, deduplicationKey: String?): SnapshotList<RxLogEntryDTO> {
        if (deduplicationKey == null) return SnapshotList.empty()
        val target = SwiftText.canonical(deduplicationKey)
        return entries.filter { entry -> candidateKey(entry)?.let(SwiftText::canonical) == target }
            .sortedBy { it.receivedAt }
            .snapshot()
    }

    private fun candidateKey(entry: RxLogEntryDTO): String? {
        if (entry.payloadType != PayloadType.GROUP_TEXT) return null
        if (entry.decryptStatus != DecryptStatus.SUCCESS) return null
        val decodedText = entry.decodedText ?: return null
        val channelIndex = entry.channelIndex ?: return null
        val senderTimestamp = entry.senderTimestamp ?: return null
        val parsed = ChannelMessageFormat.parse(decodedText) ?: return null
        return DeduplicationKey.contentBased(null, channelIndex, parsed.senderName, senderTimestamp, parsed.messageText)
    }
}
