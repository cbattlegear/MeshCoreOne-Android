// PortedFrom: MC1Services/Sources/MC1Services/Utilities/ChannelRXCorrelation.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.model.DeduplicationKey
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.event.PayloadType
import java.text.Normalizer

object ChannelRXCorrelation {
    fun matching(entries: List<RxLogEntryDTO>, deduplicationKey: String?): SnapshotList<RxLogEntryDTO> {
        if (deduplicationKey == null) return SnapshotList.empty()
        return entries.filter { entry ->
            if (entry.payloadType != PayloadType.GROUP_TEXT || entry.decryptStatus != DecryptStatus.SUCCESS) return@filter false
            val parsed = entry.decodedText?.let(ChannelMessageFormat::parse) ?: return@filter false
            val channel = entry.channelIndex ?: return@filter false
            val timestamp = entry.senderTimestamp ?: return@filter false
            val candidate = DeduplicationKey.contentBased(null, channel, parsed.senderName, timestamp, parsed.messageText)
            Normalizer.normalize(candidate, Normalizer.Form.NFC) == Normalizer.normalize(deduplicationKey, Normalizer.Form.NFC)
        }.sortedBy { it.receivedAt }.snapshot()
    }
}
