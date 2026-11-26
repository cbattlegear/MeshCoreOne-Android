// PortedFrom: MC1/Views/Chats/Components/ChatShareMenu.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.Locale

/** Predicates and insert text of the composer's "+" share menu (location, contact, my info). */
object ComposerShare {
    /** Location timeout for a fresh fix (iOS `locationFixTimeout`). */
    const val LOCATION_FIX_TIMEOUT_MILLIS = 5_000L

    fun canShareLocation(phoneCoordinate: Coordinate?, nodeCoordinate: Coordinate?, locationAuthorized: Boolean): Boolean =
        phoneCoordinate != null || nodeCoordinate != null || locationAuthorized

    fun canShareMyInfo(publicKey: Bytes, nodeName: String): Boolean =
        publicKey.size == ProtocolLimits.PUBLIC_KEY_SIZE && !ComposerText.isBlank(nodeName)

    /** `"%.6f, %.6f"`, locale-independent so the text round-trips through the coordinate linkifier. */
    fun locationText(coordinate: Coordinate): String =
        String.format(Locale.ROOT, "%.6f, %.6f", coordinate.latitude, coordinate.longitude)

    fun myInfoToken(publicKey: Bytes, nodeName: String): String =
        ContactShare.formatShare(publicKey, ContactType.CHAT, nodeName)

    /** Appends [shared] to [draft], inserting one space unless the draft is empty or already ends in whitespace. */
    fun insertShared(draft: String, shared: String): String {
        val last = ComposerText.graphemes(draft).lastOrNull()
        val separator = if (last != null && !ComposerText.isWhitespaceCharacter(last)) " " else ""
        return draft + separator + shared
    }
}
