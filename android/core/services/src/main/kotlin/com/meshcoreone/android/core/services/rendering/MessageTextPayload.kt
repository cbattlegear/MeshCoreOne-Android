// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/MessageTextPayload.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

/**
 * Placeholder port for the Swift `AttributedString` body that the app layer (WP-307/WP-308) bakes from
 * mentions, links and hashtags before the build. The builder only copies it through, so WP-213 needs
 * nothing but value semantics: implementations must be immutable with structural equals/hashCode.
 */
interface FormattedMessageText

/** Text fragment payload. */
data class MessageTextPayload(
    val raw: String,
    val formatted: FormattedMessageText?,
    val baseColor: BaseColorSlot,
    val isOutgoing: Boolean,
    val currentUserName: String,
    /** Null means no Translation offer row; [raw]/[formatted] always describe the stored original. */
    val translation: MessageTranslationChrome? = null,
)
