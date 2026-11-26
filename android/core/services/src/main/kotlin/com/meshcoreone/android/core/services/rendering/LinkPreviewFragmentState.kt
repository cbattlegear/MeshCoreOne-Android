// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/LinkPreviewFragmentState.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.LinkPreviewDataDTO

/** Link-preview card payload. */
class LinkPreviewFragmentState(
    val mode: Mode,
    /**
     * Remembered hero-image width-over-height ratio for this URL from the persisted dimensions store,
     * so the loading shimmer reserves the final card footprint. Null when the size was never seen.
     */
    val heroAspectHint: Double? = null,
) {
    sealed interface Mode {
        data object Idle : Mode
        data class Loading(val url: WebURL) : Mode
        data class Loaded(val preview: LinkPreviewDataDTO, val image: ImageReference?, val icon: ImageReference?) : Mode
        data object NoPreview : Mode
        data class Disabled(val url: WebURL) : Mode
        data class Legacy(val url: WebURL, val title: String?, val image: ImageReference?, val icon: ImageReference?) : Mode
    }

    /**
     * The single openable URL the preview resolves to: the destination of a loaded or legacy card, null
     * for modes that show no openable card.
     */
    val primaryURL: WebURL?
        get() = when (val current = mode) {
            is Mode.Loaded -> WebURL.parse(current.preview.url)
            is Mode.Legacy -> current.url
            Mode.Idle, is Mode.Loading, Mode.NoPreview, is Mode.Disabled -> null
        }

    private val fields: Array<Any?> get() = arrayOf(mode, heroAspectHint)
    override fun equals(other: Any?): Boolean = other is LinkPreviewFragmentState && swiftFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = swiftFieldsHash(fields)
    override fun toString(): String = "LinkPreviewFragmentState(mode=$mode, heroAspectHint=$heroAspectHint)"
}
