// PortedFrom: MC1/Services/LinkPreviewService+Scrape.swift@db14559b39d32322b06477c6ae676112f583db50
// Only the pure, side-effect-free `<meta>`-tag scanning portion (`parseHTMLMetadata`,
// `metaTagContents`, `parseAttributes`, `unescapeHTMLEntities`) is ported here, exactly as
// the Swift original is documented to be "pure and side-effect-free so it can run against
// captured HTML fixtures in tests without a network fetch." The network-fetching portion
// (`scrapeHTMLMetadata`, `loadImageData`, `boundedData`) is ported in `LinkPreviewScraper.kt`.
package com.meshcoreone.android.core.services.content

/** Result of scanning a page's `<meta>` tags for Open Graph / Twitter Card hints. */
data class ScrapedPageMetadata(val title: String?, val imageUrl: String?)
data class PagePreviewMetadata(val title: String?, val imageUrl: String?, val iconUrl: String?)

object LinkPreviewHtmlMetadata {
    private const val METADATA_PROPERTY_ATTRIBUTE = "property"
    private const val METADATA_NAME_ATTRIBUTE = "name"
    private const val METADATA_CONTENT_ATTRIBUTE = "content"
    private const val OG_IMAGE_PROPERTY = "og:image"
    private const val TWITTER_IMAGE_PROPERTY = "twitter:image"
    private const val OG_TITLE_PROPERTY = "og:title"

    private val metaTagPattern = Regex("""<meta\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val metaAttributePattern = Regex("""([a-zA-Z][a-zA-Z0-9-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")

    /**
     * Ordered so `&amp;` decodes last: unescaping it first would let an already-escaped
     * entity like `&amp;lt;` incorrectly decode a second time into `<` instead of the
     * literal text `&lt;`.
     */
    private val htmlEntities = listOf(
        "&quot;" to "\"",
        "&#39;" to "'",
        "&apos;" to "'",
        "&lt;" to "<",
        "&gt;" to ">",
        "&amp;" to "&",
    )

    /**
     * Scans every `<meta>` tag in [html] for Open Graph / Twitter Card hero image and title
     * hints, resolving a relative image URL against [baseUrl]. `og:image` wins over
     * `twitter:image` when both are present. Returns `null` when neither hint is found, or
     * when the only image hint resolves to a non-HTTP(S) scheme.
     */
    fun parseHtmlMetadata(html: String, baseUrl: String): ScrapedPageMetadata? {
        var ogImage: String? = null
        var twitterImage: String? = null
        var ogTitle: String? = null

        for (tag in metaTagContents(html)) {
            val attributes = parseAttributes(tag)
            val content = attributes[METADATA_CONTENT_ATTRIBUTE] ?: continue
            val property = attributes[METADATA_PROPERTY_ATTRIBUTE] ?: attributes[METADATA_NAME_ATTRIBUTE]

            when {
                ogImage == null && property == OG_IMAGE_PROPERTY -> ogImage = content
                twitterImage == null && property == TWITTER_IMAGE_PROPERTY -> twitterImage = content
                ogTitle == null && property == OG_TITLE_PROPERTY -> ogTitle = content
            }
        }

        val rawImageUrl = (ogImage ?: twitterImage)?.let(::unescapeHtmlEntities)
        val resolvedImageUrl = rawImageUrl?.let { resolveAgainst(baseUrl, it) }
        val imageUrl = resolvedImageUrl?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

        val title = ogTitle?.let(::unescapeHtmlEntities)

        if (imageUrl == null && title == null) return null
        return ScrapedPageMetadata(title = title, imageUrl = imageUrl)
    }

    fun parsePageMetadata(html: String, baseUrl: String): PagePreviewMetadata? {
        val openGraph = parseHtmlMetadata(html, baseUrl)
        val title = openGraph?.title ?: Regex(
            """<title\b[^>]*>(.*?)</title>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.get(1)?.let(::unescapeHtmlEntities)?.trim()
        val icon = Regex("""<link\b[^>]*>""", RegexOption.IGNORE_CASE).findAll(html).mapNotNull { match ->
            val attributes = parseAttributes(match.value)
            val rel = attributes["rel"]?.lowercase()?.split(Regex("\\s+")).orEmpty()
            if ("icon" !in rel) null else attributes["href"]?.let(::unescapeHtmlEntities)?.let {
                resolveAgainst(baseUrl, it)
            }?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.firstOrNull()
        if (title == null && openGraph?.imageUrl == null && icon == null) return null
        return PagePreviewMetadata(title, openGraph?.imageUrl, icon)
    }

    /** Returns the raw text of every `<meta ...>` tag in [html], in document order. */
    private fun metaTagContents(html: String): List<String> = metaTagPattern.findAll(html).map { it.value }.toList()

    /** Extracts `name="value"` / `name='value'` pairs from a single tag's text. */
    private fun parseAttributes(tag: String): Map<String, String> {
        val attributes = mutableMapOf<String, String>()
        for (match in metaAttributePattern.findAll(tag)) {
            val name = match.groupValues[1].lowercase()
            val value = match.groups[2]?.value ?: match.groups[3]?.value ?: continue
            attributes[name] = value
        }
        return attributes
    }

    private fun unescapeHtmlEntities(text: String): String =
        htmlEntities.fold(text) { acc, (entity, replacement) -> acc.replace(entity, replacement) }

    /**
     * Resolves [reference] against [base], mirroring `URL(string:relativeTo:)?.absoluteURL`:
     * an absolute `reference` (one that already carries a scheme) is returned unchanged; a
     * path-absolute or relative one is resolved against [base]'s origin/path.
     */
    private fun resolveAgainst(base: String, reference: String): String? =
        runCatching {
            val baseUri = java.net.URI(base)
            val referenceUri = java.net.URI(reference)
            if (reference.startsWith("?")) {
                // URI.resolve drops the final path segment for query-only references.
                baseUri.toString().substringBefore('#').substringBefore('?') + referenceUri.toString()
            } else {
                baseUri.resolve(referenceUri).toString()
            }
        }.getOrNull()
}
