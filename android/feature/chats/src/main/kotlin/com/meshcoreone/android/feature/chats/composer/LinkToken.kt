// PortedFrom: MC1/Views/Chats/Linkify/MessageLinkToken.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Linkify/MessageLinkStyler.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.Coordinate

/**
 * Detector kinds in overlap-resolution priority, highest first (ordinal is the priority): a contact
 * chip or mention shadows any link inside it, a URL shadows a hashtag or coordinate it contains, and
 * a hashtag inside a meshcore link wins so the link is left unlinked.
 */
enum class LinkKind { CONTACT_SHARE, MENTION, URL, HASHTAG, MESHCORE_LINK, COORDINATE }

/** Semantic color slot; Android resolves it against the live theme (iOS froze a `Color` on the token). */
enum class LinkColor { BASE, MENTION_IDENTITY, HASHTAG, OUTGOING_TEXT }

/**
 * One styled span of the normalized message body. [start]/[end] are UTF-16 offsets, end exclusive.
 * [url] is null for a span that is styled but not tappable (a mention whose name cannot be encoded).
 * [colorKey] is the mention name an identity color derives from; [selfMention] adds the highlight.
 */
data class LinkToken(
    val start: Int,
    val end: Int,
    val kind: LinkKind,
    val url: String?,
    val color: LinkColor = LinkColor.BASE,
    val colorKey: String? = null,
    val selfMention: Boolean = false,
    val underline: Boolean = true,
    val bold: Boolean = false,
) {
    /** Swift `Range.overlaps`: empty ranges overlap nothing. */
    fun overlaps(other: LinkToken): Boolean = start < other.end && other.start < end && start < end && other.start < other.end
}

/** The styled message: normalized text, sorted non-overlapping tokens, and the first surviving coordinate. */
data class FormattedMessage(val text: String, val tokens: List<LinkToken>, val mapCoordinate: Coordinate?) {
    /** The link on the span covering `text[start, end)` entirely, or null. */
    fun linkAt(start: Int, end: Int): String? = tokens.firstOrNull { it.start <= start && end <= it.end }?.url

    /** The link on the first occurrence of [substring], or null (also when the substring is absent). */
    fun linkFor(substring: String): String? {
        val start = text.indexOf(substring)
        return if (start < 0) null else linkAt(start, start + substring.length)
    }

    /** Linked runs' URLs in document order (what VoiceOver custom actions are derived from). */
    val links: List<String> get() = tokens.mapNotNull { it.url }
}
