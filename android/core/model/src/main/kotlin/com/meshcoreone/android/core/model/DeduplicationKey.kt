// PortedFrom: MC1Services/Sources/MC1Services/Utilities/DeduplicationKey.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.model

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.sha256
import java.util.UUID

object DeduplicationKey {
    const val CHANNEL_PREFIX = "ch-"
    const val DIRECT_MESSAGE_PREFIX = "dm-"
    const val OUTGOING_IDENTITY_PREFIX = "out-"
    const val UNKNOWN_CONTACT_PLACEHOLDER = "unknown"

    fun contentBased(
        contactID: UUID?, channelIndex: UByte?, senderNodeName: String?, timestamp: UInt, content: String,
    ): String {
        val hash = sha256(Bytes.utf8(content)).prefix(4).uppercaseHexString()
        return if (channelIndex != null) {
            "$CHANNEL_PREFIX$channelIndex-$timestamp-${senderNodeName ?: ""}-$hash"
        } else {
            "$DIRECT_MESSAGE_PREFIX${contactID?.canonicalString() ?: UNKNOWN_CONTACT_PLACEHOLDER}-$timestamp-$hash"
        }
    }
}
