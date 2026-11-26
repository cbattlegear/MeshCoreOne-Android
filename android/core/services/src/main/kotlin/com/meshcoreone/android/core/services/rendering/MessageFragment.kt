// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/MessageFragment.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.services.rendering.InlineImage as InlineImageValue

/** Closed set of rows inside a bubble. Adding a case forces every consumer to update. */
sealed interface MessageFragment {
    data class Text(val payload: MessageTextPayload) : MessageFragment
    data class InlineImage(val image: InlineImageValue) : MessageFragment
    data class LinkPreview(val state: LinkPreviewFragmentState) : MessageFragment
    data class MapPreview(val state: MapPreviewFragmentState) : MessageFragment
    data class MalwareWarning(val url: WebURL) : MessageFragment
    /** Raw summary string (`"👍:3,❤️:2"`); emitted only for a non-empty summary, parsed at render time. */
    data class ReactionSummary(val summary: String) : MessageFragment
}
