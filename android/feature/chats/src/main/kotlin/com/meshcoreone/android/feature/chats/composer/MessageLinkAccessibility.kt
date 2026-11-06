// PortedFrom: MC1/Views/Chats/Linkify/MessageLinkAccessibility.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.feature.chats.list.MeshCoreLinkParser
import com.meshcoreone.android.feature.chats.list.ChatMailtoPolicy
import com.meshcoreone.android.feature.chats.list.MeshCoreLinkParsing

/** Localized, target-bearing names of the TalkBack custom actions (Compose layer supplies the resource-backed one). */
interface LinkActionNames {
    fun openLink(): String
    fun openWebLink(host: String): String
    fun openMapLink(): String
    fun addContact(name: String): String
    fun openChannel(name: String): String
    fun openMention(name: String): String
    fun openHashtag(name: String): String
}

/**
 * TalkBack "open" actions for a message bubble's links. A bubble merges its children into one
 * accessibility node, so body links lose per-link focus; these actions restore activation for every
 * link kind. Derived from the same [FormattedMessage] the body renders, so they cannot diverge from the
 * visible links. Preview URL first, then body links in document order, de-duplicated, capped.
 */
object MessageLinkAccessibility {
    /** Upper bound per message so a link-packed message cannot flood the action list. */
    const val MAX_ACTIONS = 8

    data class Action(val name: String, val url: String)

    fun actions(
        previewUrl: String?,
        formatted: FormattedMessage?,
        names: LinkActionNames,
        parser: MeshCoreLinkParsing = MeshCoreLinkParser,
    ): List<Action> {
        val seen = LinkedHashSet<String>()
        val actions = ArrayList<Action>()
        fun append(url: String) {
            if (actions.size >= MAX_ACTIONS || !seen.add(url)) return
            actions += Action(name(url, names, parser), url)
        }
        previewUrl?.let(::append)
        formatted?.links?.forEach(::append)
        return actions
    }

    private val urlShape = Regex("""^([A-Za-z][A-Za-z0-9+.-]*):(?://([^/?#]*))?([^?#]*)""")

    private fun name(url: String, names: LinkActionNames, parser: MeshCoreLinkParsing): String {
        val parts = urlShape.find(url) ?: return names.openLink()
        val scheme = parts.groupValues[1].lowercase(java.util.Locale.ROOT)
        val host = hostOf(parts.groupValues[2])
        return when (scheme) {
            "http", "https" -> if (host.isEmpty()) names.openLink() else names.openWebLink(host)
            "mailto" -> ChatMailtoPolicy.recipient(url)?.let(names::openWebLink) ?: names.openLink()
            MeshCoreLinkParser.SCHEME -> when (host) {
                "map" -> names.openMapLink()
                "contact" -> parser.parseContactURL(url)?.name?.let(names::addContact) ?: names.openLink()
                "channel" -> parser.parseChannelURL(url)?.name?.let(names::openChannel) ?: names.openLink()
                else -> names.openLink()
            }
            MentionDeeplink.SCHEME -> when (host) {
                MentionDeeplink.HOST -> MentionDeeplink.name(url)?.let(names::openMention) ?: names.openLink()
                "hashtag" -> hashtagName(parts.groupValues[3])?.let(names::openHashtag) ?: names.openLink()
                else -> names.openLink()
            }
            else -> names.openLink()
        }
    }

    /** Authority minus userinfo and port. */
    private fun hostOf(authority: String): String {
        val afterUser = authority.substringAfterLast('@')
        return if (afterUser.startsWith("[")) afterUser.substringBefore(']') + "]" else afterUser.substringBefore(':')
    }

    /** Percent-decoded path after the leading slash of `meshcoreone://hashtag/<channel>`. */
    private fun hashtagName(path: String): String? {
        val encoded = path.removePrefix("/")
        if (encoded.isEmpty()) return null
        return MentionDeeplink.percentDecode(encoded)?.takeIf { it.isNotEmpty() }
    }
}
