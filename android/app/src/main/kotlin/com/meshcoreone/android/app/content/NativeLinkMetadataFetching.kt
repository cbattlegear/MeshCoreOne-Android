// PortedFrom: MC1/Services/LinkPreviewService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Services/LinkPreviewService+Scrape.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.content

import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.LinkMetadataFetching
import com.meshcoreone.android.core.services.content.LinkPreviewMetadata
import com.meshcoreone.android.core.services.content.LinkPreviewScraper
import com.meshcoreone.android.core.services.content.PreviewImageProcessing

class NativeLinkMetadataFetching(
    http: BoundedHttpFetching,
    images: PreviewImageProcessing = BitmapPreviewImageProcessor(),
) : LinkMetadataFetching {
    private val scraper = LinkPreviewScraper(http, images)

    override suspend fun fetchMetadata(url: String): LinkPreviewMetadata? {
        val page = scraper.fetchPageMetadata(url) ?: return null
        val hero = page.imageUrl?.let { scraper.loadImageData(it) }
        val icon = page.iconUrl?.let { scraper.loadImageData(it) }
        if (page.title == null && hero == null && icon == null) return null
        return LinkPreviewMetadata(page.title, hero, icon)
    }
}
