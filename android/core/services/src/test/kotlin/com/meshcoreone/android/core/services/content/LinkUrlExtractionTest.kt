// PortedFrom: MC1Tests/Services/LinkPreviewServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// (`extractFirstURL`/`extractGiphyGIFURL` cases only; see `LinkUrlExtraction.kt`.)
package com.meshcoreone.android.core.services.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkUrlExtractionTest {
    @Test
    fun `extracts https url from text`() {
        val text = "Check out https://example.com/article for more info"
        assertEquals("https://example.com/article", LinkUrlExtraction.extractFirstUrl(text))
    }

    @Test
    fun `extracts http url from text`() {
        val text = "Visit http://example.com"
        assertEquals("http://example.com", LinkUrlExtraction.extractFirstUrl(text))
    }

    @Test
    fun `extracts a bare web domain with inferred https scheme`() {
        assertEquals("https://WCMesh.com", LinkUrlExtraction.extractFirstUrl("Visit WCMesh.com today"))
    }

    @Test
    fun `extracts a www web domain as an absolute https url`() {
        assertEquals("https://www.example.com", LinkUrlExtraction.extractFirstUrl("Visit www.example.com"))
    }

    @Test
    fun `returns null for text without urls`() {
        assertNull(LinkUrlExtraction.extractFirstUrl("Just some plain text without links"))
    }

    @Test
    fun `extracts first url when multiple urls present`() {
        val text = "First https://first.com then https://second.com"
        assertEquals("https://first.com", LinkUrlExtraction.extractFirstUrl(text))
    }

    @Test
    fun `ignores non-http schemes like tel and mailto`() {
        val text = "Call me at tel:+1234567890, mailto:test@example.com or ftp://example.com"
        assertNull(LinkUrlExtraction.extractFirstUrl(text))
    }

    @Test
    fun `extracts url with path and query string`() {
        val text = "Read https://example.com/blog/2024/article-title?ref=social"
        assertEquals("https://example.com/blog/2024/article-title?ref=social", LinkUrlExtraction.extractFirstUrl(text))
    }

    @Test
    fun `extracts url at beginning of text`() {
        assertEquals("https://example.com", LinkUrlExtraction.extractFirstUrl("https://example.com is a great site"))
    }

    @Test
    fun `extracts url at end of text`() {
        assertEquals("https://example.com", LinkUrlExtraction.extractFirstUrl("Check this out: https://example.com"))
    }

    @Test
    fun `returns null for empty text`() {
        assertNull(LinkUrlExtraction.extractFirstUrl(""))
    }

    @Test
    fun `handles url with fragment`() {
        assertEquals("https://example.com/page#section", LinkUrlExtraction.extractFirstUrl("See https://example.com/page#section"))
    }

    @Test
    fun `ignores url-like text within mention brackets`() {
        val text = "Hey @[Ferret PocketMesh WCMesh.com], check this out!"
        assertNull(LinkUrlExtraction.extractFirstUrl(text))
    }

    @Test
    fun `suppresses an explicit http url matched only inside a mention`() {
        assertNull(LinkUrlExtraction.extractFirstUrl("Check @[https://docs.example.com/page] for details"))
    }

    @Test
    fun `ignores domain-like text within mention brackets`() {
        assertNull(LinkUrlExtraction.extractFirstUrl("@[Server node.example.com] says hello"))
    }

    @Test
    fun `extracts real url when mention also contains url-like text`() {
        val text = "@[Server node.example.com] says check https://docs.example.com"
        assertEquals("https://docs.example.com", LinkUrlExtraction.extractFirstUrl(text))
    }

    @Test
    fun `extracts url when no mentions present`() {
        val text = "Just a normal message with https://example.com link"
        assertEquals("https://example.com", LinkUrlExtraction.extractFirstUrl(text))
    }

    @Test
    fun `trims trailing punctuation and only unmatched closing delimiters`() {
        assertEquals(
            listOf("https://example.com", "https://www.example.org/path(a)"),
            LinkUrlExtraction.extractAllUrls("Visit example.com, then (www.example.org/path(a))."),
        )
        assertEquals("https://example.com/a(b)", LinkUrlExtraction.extractFirstUrl("Link:https://example.com/a(b)."))
    }

    @Test
    fun `matches a complete punycode top-level domain`() {
        assertEquals("https://site.xn--p1ai/path", LinkUrlExtraction.extractFirstUrl("Visit site.xn--p1ai/path"))
    }

    @Test
    fun `does not extract dotted email addresses as domains`() {
        assertNull(
            LinkUrlExtraction.extractFirstUrl(
                "Email first.last@example.com or first.last+tag@example.net or name.with.dots@sub.example.org",
            ),
        )
    }

    @Test
    fun `does not extract URL-like email local parts as web links`() {
        assertNull(LinkUrlExtraction.extractFirstUrl("Contact project.com/sales@example.com"))
        assertEquals(
            listOf("https://example.com/contact?email=foo@example.org"),
            LinkUrlExtraction.extractAllUrls("example.com/contact?email=foo@example.org"),
        )
    }

    @Test
    fun `does not extract domains embedded in non-hierarchical or unsupported URI values`() {
        assertNull(LinkUrlExtraction.extractFirstUrl("mailto:alice@example.org?subject=tracker.com"))
        assertEquals(
            emptyList(),
            LinkUrlExtraction.extractAllUrls("ftp://example.com/?next=https://other.com/path"),
        )
        assertNull(LinkUrlExtraction.extractFirstUrl("view-source:https://example.com"))
    }

    @Test
    fun `extracts links after prose labels without treating labels as URI schemes`() {
        assertEquals("https://example.com", LinkUrlExtraction.extractFirstUrl("Link:(https://example.com)"))
        assertEquals("https://example.com", LinkUrlExtraction.extractFirstUrl("Visit:example.com"))
    }

    @Test
    fun `trims surrounding quotes and angle brackets from bare URL suffixes`() {
        assertEquals("https://example.com/path", LinkUrlExtraction.extractFirstUrl("Visit \"example.com/path\""))
        assertEquals("https://example.com/path", LinkUrlExtraction.extractFirstUrl("Visit <example.com/path>"))
    }

    @Test
    fun `preserves mixed-case hostnames for shared case-insensitive malware checking`() {
        assertEquals("https://Blocked.Example/path", LinkUrlExtraction.extractFirstUrl("Blocked.Example/path"))
        assertEquals("https://BLOCKED.EXAMPLE/path", LinkUrlExtraction.extractFirstUrl("HTTPS://BLOCKED.EXAMPLE/path"))
    }

    @Test
    fun `normalizes uppercase explicit schemes without changing the rest of the url`() {
        assertEquals("https://Example.COM/Page", LinkUrlExtraction.extractFirstUrl("Link:HTTPS://Example.COM/Page"))
        assertEquals("https://example.com", LinkUrlExtraction.extractFirstUrl("See-https://example.com"))
    }

    @Test
    fun `does not extract bare domains embedded in supported or unsupported uri urls`() {
        assertEquals(
            listOf("https://example.com/?next=tracker.com"),
            LinkUrlExtraction.extractAllUrls("https://example.com/?next=tracker.com"),
        )
        assertEquals(
            emptyList(),
            LinkUrlExtraction.extractAllUrls("ftp://example.com/?next=tracker.com"),
        )
    }

    @Test
    fun `keeps outer bare links when email or nested uri appears in their query`() {
        assertEquals(
            listOf("https://example.com/contact?email=foo@example.org"),
            LinkUrlExtraction.extractAllUrls("example.com/contact?email=foo@example.org"),
        )
        assertEquals(
            listOf("https://example.com/?next=https://other.com/path"),
            LinkUrlExtraction.extractAllUrls("example.com/?next=https://other.com/path"),
        )
    }

    @Test
    fun `returns null when only url-like text in mention`() {
        assertNull(LinkUrlExtraction.extractFirstUrl("Message from @[192.168.1.100]"))
    }

    @Test
    fun `extracts giphy url from g prefix message`() {
        assertEquals(
            "https://media.giphy.com/media/JgWZYoIgjzsIQO8joZ/giphy.gif",
            LinkUrlExtraction.extractFirstUrl("g:JgWZYoIgjzsIQO8joZ"),
        )
    }

    @Test
    fun `extracts giphy url from g with whitespace`() {
        assertEquals(
            "https://media.giphy.com/media/ABC123xyz/giphy.gif",
            LinkUrlExtraction.extractFirstUrl("  g:ABC123xyz  "),
        )
    }

    @Test
    fun `handles g with hyphens and underscores in id`() {
        assertEquals(
            "https://media.giphy.com/media/my-gif_ID-123/giphy.gif",
            LinkUrlExtraction.extractFirstUrl("g:my-gif_ID-123"),
        )
    }

    @Test
    fun `returns null for g with no id`() {
        assertNull(LinkUrlExtraction.extractFirstUrl("g:"))
    }

    @Test
    fun `does not match g embedded in longer text`() {
        // Should not match because wholeMatch requires the entire string.
        assertNull(LinkUrlExtraction.extractFirstUrl("Check out g:ABC123 please"))
    }

    @Test
    fun `does not match g with invalid characters in id`() {
        assertNull(LinkUrlExtraction.extractFirstUrl("g:ABC 123"))
    }

    @Test
    fun `extractGiphyGifUrl returns null for plain text`() {
        assertNull(LinkUrlExtraction.extractGiphyGifUrl("hello world"))
    }

    @Test
    fun `extractGiphyGifUrl returns null for regular url`() {
        assertNull(LinkUrlExtraction.extractGiphyGifUrl("https://example.com"))
    }
}
