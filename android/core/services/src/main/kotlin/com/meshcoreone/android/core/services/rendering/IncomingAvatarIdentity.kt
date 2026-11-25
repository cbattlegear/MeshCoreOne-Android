// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/IncomingAvatarIdentity.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.security.MessageDigest
import java.util.UUID

/** Avatar chrome for channel incoming cluster-end rows. Never carries image bytes. */
data class IncomingAvatarIdentity(
    val name: String,
    /** Unique-name channel match: a local contact id, never a DM conversation key. */
    val matchedContactID: UUID?,
    val imageRevision: ULong?,
) {
    companion object {
        private const val REVISION_BYTES = 8

        fun initials(name: String): IncomingAvatarIdentity = IncomingAvatarIdentity(name, null, null)

        /**
         * Unique-name photo only when [senderNodeName] is non-empty after trimming; the prefix-resolved
         * [displayName] is initials chrome, never a table key. Lookup keys are Swift-lowercased and
         * matched by canonical equivalence, as a Swift `[String: _]` lookup is.
         */
        fun resolve(
            senderNodeName: String?,
            displayName: String,
            table: Map<String, IncomingAvatarIdentity>,
        ): IncomingAvatarIdentity {
            val raw = senderNodeName?.let(SwiftText::trimmingWhitespacesAndNewlines)
            if (!raw.isNullOrEmpty()) {
                SwiftText.canonicalLookup(table, SwiftText.lowercased(raw))?.let { return it }
            }
            return initials(displayName)
        }

        /**
         * Change token for avatar bytes; null for missing or empty data. Swift mixes the bytes into a
         * per-process seeded `Hasher`; this uses the first 8 bytes of SHA-256, which is equally a
         * content token and must likewise never be persisted.
         */
        fun revision(data: Bytes?): ULong? {
            if (data == null || data.size == 0) return null
            val digest = MessageDigest.getInstance("SHA-256").digest(data.toByteArray())
            var value = 0UL
            for (index in 0 until REVISION_BYTES) value = (value shl 8) or (digest[index].toULong() and 0xFFUL)
            return value
        }
    }
}
