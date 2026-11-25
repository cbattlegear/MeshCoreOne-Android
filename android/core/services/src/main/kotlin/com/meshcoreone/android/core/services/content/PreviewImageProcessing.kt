// PortedFrom: MC1/Services/LinkPreviewService.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Services/LinkPreviewService+Scrape.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.content

fun interface PreviewImageProcessing {
    suspend fun boundedPreviewImage(data: ByteArray): ByteArray?
}
