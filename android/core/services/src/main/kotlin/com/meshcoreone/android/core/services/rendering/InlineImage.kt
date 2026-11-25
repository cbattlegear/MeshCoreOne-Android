// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/InlineImage.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

/** Inline-image fragment payload. */
class InlineImage(
    val state: LoadState,
    val autoPlayGIFs: Boolean,
    /**
     * Width-over-height ratio resolved from the inline-image dimensions store at build time. Null means
     * dimensions are unknown and the view layer falls back to its 16:9 reservation skeleton.
     */
    val cachedAspect: Double? = null,
) {
    sealed interface LoadState {
        data class Idle(val url: WebURL) : LoadState
        data class Loading(val url: WebURL) : LoadState
        data class Loaded(val image: ImageReference, val isGIF: Boolean) : LoadState
        data class Failed(val url: WebURL) : LoadState
        /** Scope-off tap-to-load placeholder; mirrors [LinkPreviewFragmentState.Mode.Disabled]. */
        data class Disabled(val url: WebURL) : LoadState
    }

    private val fields: Array<Any?> get() = arrayOf(state, autoPlayGIFs, cachedAspect)
    override fun equals(other: Any?): Boolean = other is InlineImage && swiftFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = swiftFieldsHash(fields)
    override fun toString(): String = "InlineImage(state=$state, autoPlayGIFs=$autoPlayGIFs, cachedAspect=$cachedAspect)"
}
