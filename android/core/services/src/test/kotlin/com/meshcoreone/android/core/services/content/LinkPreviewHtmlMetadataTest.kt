// PortedFrom: MC1Tests/Services/LinkPreviewServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// (parseHTMLMetadata cases only; see `LinkPreviewScraperTest.kt` for the network-leg cases.)
package com.meshcoreone.android.core.services.content

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkPreviewHtmlMetadataTest {
    private val pageUrl = "https://example.com/page"

    @Test
    fun `parses a pasteboard-style head for its og image`() {
        val html = """
            <head>
            <meta property="og:title" content="Pasteboard">
            <meta property="og:image" content="https://gcdnb.pbrd.co/images/lZtwvIBHoO7L.jpg">
            </head>
        """.trimIndent()
        val result = LinkPreviewHtmlMetadata.parseHtmlMetadata(html, "https://pasteboard.co/lZtwvIBHoO7L.jpg")
        assertEquals("https://gcdnb.pbrd.co/images/lZtwvIBHoO7L.jpg", result?.imageUrl)
    }

    @Test
    fun `prefers og image over twitter image when both are present`() {
        val html = """
            <head>
            <meta property="og:title" content="Imgur gallery">
            <meta property="og:image" content="https://i.imgur.com/rmwz8FJ.jpeg?fb">
            <meta name="twitter:image" content="https://i.imgur.com/different.jpeg">
            </head>
        """.trimIndent()
        val result = LinkPreviewHtmlMetadata.parseHtmlMetadata(html, "https://imgur.com/gallery/abc123")
        assertEquals("https://i.imgur.com/rmwz8FJ.jpeg?fb", result?.imageUrl)
    }

    @Test
    fun `handles content attribute before property attribute`() {
        val html = """<meta content="https://example.com/hero.jpg" property="og:image">"""
        val result = LinkPreviewHtmlMetadata.parseHtmlMetadata(html, pageUrl)
        assertEquals("https://example.com/hero.jpg", result?.imageUrl)
    }

    @Test
    fun `falls back to twitter image when no og image is present`() {
        val html = """<meta name="twitter:image" content="https://example.com/twitter-hero.jpg">"""
        val result = LinkPreviewHtmlMetadata.parseHtmlMetadata(html, pageUrl)
        assertEquals("https://example.com/twitter-hero.jpg", result?.imageUrl)
    }

    @Test
    fun `resolves a relative og image url against the page url`() {
        val html = """<meta property="og:image" content="/images/hero.jpg">"""
        val result = LinkPreviewHtmlMetadata.parseHtmlMetadata(html, pageUrl)
        assertEquals("https://example.com/images/hero.jpg", result?.imageUrl)
    }

    @Test
    fun `query-only image and icon references retain the page path`() {
        val html = """
            <meta property="og:image" content="?image=hero&amp;size=2">
            <link rel="icon" href="?image=icon">
        """.trimIndent()
        val result = LinkPreviewHtmlMetadata.parsePageMetadata(
            html,
            "https://example.com/gallery/a%20b?old=1#section",
        )
        assertEquals("https://example.com/gallery/a%20b?image=hero&size=2", result?.imageUrl)
        assertEquals("https://example.com/gallery/a%20b?image=icon", result?.iconUrl)
    }

    @Test
    fun `unescapes html entities in a multi param og image url`() {
        val html = """<meta property="og:image" content="https://example.com/img.jpg?a=1&amp;b=2">"""
        val result = LinkPreviewHtmlMetadata.parseHtmlMetadata(html, pageUrl)
        assertEquals("https://example.com/img.jpg?a=1&b=2", result?.imageUrl)
    }

    @Test
    fun `returns null when the page has no open graph or twitter card tags`() {
        val html = """<head><meta charset="utf-8"><title>No OG tags</title></head>"""
        assertNull(LinkPreviewHtmlMetadata.parseHtmlMetadata(html, pageUrl))
    }

    @Test
    fun `extracts the og title alongside the og image`() {
        val html = """
            <head>
            <meta property="og:title" content="Ben &amp; Jerry">
            <meta property="og:image" content="https://example.com/hero.jpg">
            </head>
        """.trimIndent()
        val result = LinkPreviewHtmlMetadata.parseHtmlMetadata(html, pageUrl)
        assertEquals("Ben & Jerry", result?.title)
        assertEquals("https://example.com/hero.jpg", result?.imageUrl)
    }

    @Test
    fun `returns a title-only result when the page has og title but no image`() {
        val html = """<meta property="og:title" content="Title only page">"""
        val result = LinkPreviewHtmlMetadata.parseHtmlMetadata(html, pageUrl)
        assertEquals("Title only page", result?.title)
        assertNull(result?.imageUrl)
    }

    @Test
    fun `drops a non-http og image url`() {
        val html = """<meta property="og:image" content="file:///etc/passwd">"""
        assertNull(LinkPreviewHtmlMetadata.parseHtmlMetadata(html, pageUrl))
    }
}
