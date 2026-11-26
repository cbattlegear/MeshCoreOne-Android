// PortedFrom: MC1/Intents/CompositeIDHelpers.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Intents/MessageTargetKind.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Intents/MessageTargetEntity.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.platform.shortcuts

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/** Raw values are persisted inside shortcut ids; never rename them. */
enum class TargetKind(val rawValue: String) {
    CONTACT("contact"),
    CHANNEL("channel");

    companion object {
        fun fromRawValue(rawValue: String): TargetKind? = entries.firstOrNull { it.rawValue == rawValue }
    }
}

/** A radio-scoped, kind-tagged, stable send target shown in pickers and dynamic shortcuts. */
data class ShortcutTarget(val id: String, val kind: TargetKind, val displayName: String, val subtitle: String)

data class ParsedTargetId(val radioId: RadioId, val kind: TargetKind, val keyHex: String)

object TargetIdentity {
    private const val CHANNEL_DIGEST_BYTES = 16
    private const val CHANNEL_DIGEST_DOMAIN = "MC1.ChannelEntity.id.v1"
    private const val SEPARATOR = '/'
    private const val CONTACT_SUBTITLE_PREFIX_BYTES = 6

    /** "<radioId>/<kind>/<keyOrDigestHex>": the radio scope keeps a saved shortcut bound to its radio. */
    fun format(radioId: RadioId, kind: TargetKind, keyHex: String): String =
        "${radioId.canonicalString}$SEPARATOR${kind.rawValue}$SEPARATOR$keyHex"

    /** Fails safe to null for anything malformed so a corrupt saved id never resolves anywhere. */
    fun parse(id: String): ParsedTargetId? {
        val first = id.indexOf(SEPARATOR)
        if (first < 0) return null
        val second = id.indexOf(SEPARATOR, first + 1)
        if (second < 0) return null
        val radio = runCatching { UUID.fromString(id.substring(0, first)) }.getOrNull() ?: return null
        val kind = TargetKind.fromRawValue(id.substring(first + 1, second)) ?: return null
        val key = id.substring(second + 1)
        if (key.isEmpty()) return null
        return ParsedTargetId(RadioId(radio), kind, key)
    }

    /** Non-reversible, domain-separated, radio-scoped digest; the raw secret never enters an id. */
    fun channelDigestHex(radioId: RadioId, secret: ByteArray): String {
        val uuid = radioId.value
        val radioBytes = ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(CHANNEL_DIGEST_DOMAIN.toByteArray(Charsets.UTF_8))
        digest.update(radioBytes)
        digest.update(secret)
        return digest.digest().copyOf(CHANNEL_DIGEST_BYTES).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }
    }

    fun contactId(contact: ContactDTO): String = format(contact.radioId, TargetKind.CONTACT, contact.publicKey.hexString)

    fun channelId(channel: ChannelDTO): String =
        format(channel.radioId, TargetKind.CHANNEL, channelDigestHex(channel.radioId, channel.secret.toByteArray()))

    fun target(contact: ContactDTO, subtitle: String = contact.publicKey.prefix(CONTACT_SUBTITLE_PREFIX_BYTES).hexString) =
        ShortcutTarget(contactId(contact), TargetKind.CONTACT, contact.displayName, subtitle)

    fun target(channel: ChannelDTO, channelSubtitle: String) =
        ShortcutTarget(channelId(channel), TargetKind.CHANNEL, channel.name, channelSubtitle)
}
