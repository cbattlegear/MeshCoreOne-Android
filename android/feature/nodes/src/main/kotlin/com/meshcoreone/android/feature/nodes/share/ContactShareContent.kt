// PortedFrom: MC1/Views/Contacts/ContactShareContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Contacts/ContactURIActivityItem.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Utilities/QRCodeGenerator.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.share

import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.ContactUriCodec

/** Share payloads for a contact: the copyable key and the `meshcore://contact/add` URI. */
class ContactShareContent(private val codec: ContactUriCodec) {
    /** The URI, encoded by the WP-209 contact service (never re-implemented here). */
    fun uri(name: String, publicKey: Bytes, type: ContactType): String = codec.exportContactUri(name, publicKey, type)

    companion object {
        /** Uppercase hex with no separators, for the clipboard. */
        fun compactPublicKeyHex(publicKey: Bytes): String = publicKey.uppercaseHexString()

        /** Space-separated uppercase hex shown under the QR code and on the detail screen. */
        fun spacedPublicKeyHex(publicKey: Bytes): String = publicKey.uppercaseHexString(" ")
    }
}

/**
 * What the system share sheet receives for a contact URI (`ContactURIActivityItem`). iOS returned an
 * empty `String` placeholder so the sheet classified the item as text and offered Copy. Android uses
 * a `text/plain` send whose text is the URI string (never a `Uri` stream or a view intent), with the
 * subject carried separately. The app supplies its own URI Copy action: installed chooser targets
 * are not guaranteed to include Copy.
 */
data class ContactShareTextPayload(val text: String, val subject: String) {
    val mimeType: String get() = PLAIN_TEXT_MIME

    /** The value consumed by the in-app contact-link clipboard action. */
    val copyText: String get() = text

    companion object {
        const val PLAIN_TEXT_MIME = "text/plain"
    }
}

/**
 * The QR rendering request for a contact URI (`QRCodeGenerator.generate`): UTF-8 payload, medium error
 * correction, ten device pixels per module, opaque modules over a transparent background so the image
 * can be tinted with the current text color. Rendering needs an admitted QR encoder (ZXing) and stays
 * behind [QrCodeRenderer].
 */
data class ContactQrSpec(val payload: String, val scale: Double = DEFAULT_SCALE, val correctionLevel: String = DEFAULT_CORRECTION) {
    val payloadBytes: ByteArray get() = payload.toByteArray(Charsets.UTF_8)

    companion object {
        const val DEFAULT_SCALE = 10.0
        const val DEFAULT_CORRECTION = "M"
    }
}

/** Platform QR encoder seam; null when the payload cannot be encoded. */
fun interface QrCodeRenderer {
    fun render(spec: ContactQrSpec): QrModules?
}

/** A rendered QR matrix (true = dark module), independent of any bitmap type. */
class QrModules(val size: Int, private val dark: BooleanArray) {
    init {
        require(size > 0 && dark.size == size * size) { "QR module matrix must be size x size" }
    }

    fun isDark(row: Int, column: Int): Boolean = dark[row * size + column]
}
