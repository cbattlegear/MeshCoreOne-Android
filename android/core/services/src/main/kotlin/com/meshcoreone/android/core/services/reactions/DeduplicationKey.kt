// PortedFrom: MC1Services/Sources/MC1Services/Utilities/DeduplicationKey.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.reactions

import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.sha256
import java.util.UUID

/**
 * WP-208 stand-in: a package-local copy of `DeduplicationKey.contentBased` (owned by WP-208, whose Kotlin
 * port lives at `core.model.DeduplicationKey` on the WP-208 branch with this same signature). Delete this
 * file and import the model type once WP-208 lands.
 *
 * Channel keys are `ch-{index}-{timestamp}-{sender}-{HASH}`, DM keys `dm-{CONTACT-UUID|unknown}-{timestamp}-{HASH}`,
 * where HASH is the first 4 bytes of SHA-256(UTF-8 content) as uppercase hex.
 */
internal object DeduplicationKey {
    const val CHANNEL_PREFIX = "ch-"
    const val DIRECT_MESSAGE_PREFIX = "dm-"
    const val OUTGOING_IDENTITY_PREFIX = "out-"
    const val UNKNOWN_CONTACT_PLACEHOLDER = "unknown"
    private const val CONTENT_HASH_BYTES = 4

    fun contentBased(
        contactID: UUID?,
        channelIndex: UByte?,
        senderNodeName: String?,
        timestamp: UInt,
        content: String,
    ): String {
        val hash = sha256(Bytes.utf8(content)).prefix(CONTENT_HASH_BYTES).uppercaseHexString()
        return if (channelIndex != null) {
            "$CHANNEL_PREFIX$channelIndex-$timestamp-${senderNodeName ?: ""}-$hash"
        } else {
            "$DIRECT_MESSAGE_PREFIX${contactID?.canonicalString() ?: UNKNOWN_CONTACT_PLACEHOLDER}-$timestamp-$hash"
        }
    }
}
