// PortedFrom: MC1Services/Sources/MC1Services/Models/Rendering/MessageBuildInputs.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import java.util.UUID

/**
 * Per-message immutable snapshot the pure builder consumes. The view model assembles one per message
 * from its own state; image fields are handles ([ImageReference] flags), never bitmaps.
 */
class MessageBuildInputs(
    val messageID: UUID,
    val previewState: PreviewLoadState,
    val loadedPreview: LinkPreviewDataDTO?,
    val cachedURL: WebURL?,
    /** Precomputed decision whether [cachedURL] routes to the inline-image fragment. */
    val isInlineImageURL: Boolean,
    val hasInlineImageRef: Boolean,
    val hasPreviewImageRef: Boolean,
    val hasPreviewIconRef: Boolean,
    val imageIsGIF: Boolean,
    /** Cached width-over-height ratio for an inline-image [cachedURL]; null when unknown. */
    val inlineImageAspect: Double? = null,
    /** Remembered hero-image ratio for the link preview of [cachedURL]; null when never seen. */
    val previewHeroAspect: Double? = null,
    /** First linkified coordinate in the text, or null; drives the map-preview fragment. */
    val mapPreviewLatitude: Double? = null,
    val mapPreviewLongitude: Double? = null,
    /** True once the snapshot for this coordinate's request has resolved (cached or failed). */
    val isMapPreviewReady: Boolean = false,
    val formattedText: FormattedMessageText?,
    val baseColor: BaseColorSlot,
    val formattedPath: String?,
    val senderResolution: NodeNameResolution,
    val showTimestamp: Boolean,
    val showDirectionGap: Boolean,
    val showSenderName: Boolean,
    val showNewMessagesDivider: Boolean,
    /** True for the first message of a new calendar day; drives the day separator. */
    val showDayDivider: Boolean = false,
    /** Present only on channel incoming cluster-end rows. */
    val incomingAvatar: IncomingAvatarIdentity? = null,
    /** Already-decided Translation chrome; the builder copies it and never runs detection. */
    val translation: MessageTranslationChrome? = null,
) {
    private val fields: Array<Any?>
        get() = arrayOf(
            messageID, previewState, loadedPreview, cachedURL, isInlineImageURL, hasInlineImageRef, hasPreviewImageRef,
            hasPreviewIconRef, imageIsGIF, inlineImageAspect, previewHeroAspect, mapPreviewLatitude, mapPreviewLongitude,
            isMapPreviewReady, formattedText, baseColor, formattedPath, senderResolution, showTimestamp, showDirectionGap,
            showSenderName, showNewMessagesDivider, showDayDivider, incomingAvatar, translation,
        )

    override fun equals(other: Any?): Boolean = other is MessageBuildInputs && swiftFieldsEqual(fields, other.fields)
    override fun hashCode(): Int = swiftFieldsHash(fields)
    override fun toString(): String = "MessageBuildInputs(messageID=$messageID, previewState=$previewState, cachedURL=$cachedURL)"
}
