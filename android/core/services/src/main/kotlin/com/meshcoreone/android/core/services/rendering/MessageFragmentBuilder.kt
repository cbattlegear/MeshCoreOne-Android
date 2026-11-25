// PortedFrom: MC1Services/Sources/MC1Services/Services/MessageFragmentBuilder.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MecpMessage
import com.meshcoreone.android.core.model.RegionScopeSemantics
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import java.time.Instant
import com.meshcoreone.android.core.services.rendering.InlineImage as InlineImageValue

/**
 * Builds the immutable [MessageItem] for a message from immutable inputs. Pure: no I/O, no suspension,
 * no shared state, so it is safe on any thread (the coordinator runs it on its build dispatcher).
 */
object MessageFragmentBuilder {
    fun makeItem(message: MessageDTO, inputs: MessageBuildInputs, envInputs: EnvInputs): MessageItem = MessageItem(
        id = message.id,
        envelope = makeEnvelope(message, inputs),
        content = makeFragments(message, inputs, envInputs),
        footer = makeFooter(message, inputs, envInputs),
        grouping = makeGrouping(inputs),
        shouldRequestPreviewFetch = shouldRequestPreviewFetch(inputs, message),
    )

    fun makeFragments(message: MessageDTO, inputs: MessageBuildInputs, envInputs: EnvInputs): SnapshotList<MessageFragment> {
        val fragments = ArrayList<MessageFragment>()
        fragments.add(MessageFragment.Text(makeText(message, inputs, envInputs)))
        val summary = message.reactionSummary
        if (!summary.isNullOrEmpty()) fragments.add(MessageFragment.ReactionSummary(summary))
        if (MecpMessage.decode(message.text) != null) return fragments.snapshot()

        val url = inputs.cachedURL
        if (inputs.previewState == PreviewLoadState.MALWARE_WARNING && url != null) {
            fragments.add(MessageFragment.MalwareWarning(url))
            mapPreview(inputs, envInputs)?.let(fragments::add)
            return fragments.snapshot()
        }
        if (envInputs.previewsEnabled) {
            fragments.add(
                if (inputs.isInlineImageURL) MessageFragment.InlineImage(makeInlineImage(url, inputs, envInputs))
                else MessageFragment.LinkPreview(makeLinkPreview(message, url, inputs)),
            )
        }
        mapPreview(inputs, envInputs)?.let(fragments::add)
        return fragments.snapshot()
    }

    /**
     * The map card for the first linkified coordinate, if any; it follows any link preview and is shown
     * on malware messages too (the location is not the link). Skipped entirely when the user disabled
     * map thumbnails, so no third-party tile request is ever made.
     */
    private fun mapPreview(inputs: MessageBuildInputs, envInputs: EnvInputs): MessageFragment? {
        if (!envInputs.showMapPreviews) return null
        val latitude = inputs.mapPreviewLatitude ?: return null
        val longitude = inputs.mapPreviewLongitude ?: return null
        return MessageFragment.MapPreview(
            MapPreviewFragmentState(latitude, longitude, envInputs.isDark, envInputs.isOffline, inputs.isMapPreviewReady),
        )
    }

    private fun makeText(message: MessageDTO, inputs: MessageBuildInputs, envInputs: EnvInputs) = MessageTextPayload(
        raw = message.text,
        formatted = inputs.formattedText,
        baseColor = inputs.baseColor,
        isOutgoing = message.isOutgoing,
        currentUserName = envInputs.currentUserName,
        translation = inputs.translation,
    )

    private fun makeInlineImage(url: WebURL?, inputs: MessageBuildInputs, envInputs: EnvInputs): InlineImageValue {
        val failedBlank = InlineImageValue.LoadState.Failed(WebURL.BLANK)
        val state = when (inputs.previewState) {
            PreviewLoadState.LOADED -> when {
                inputs.hasInlineImageRef ->
                    InlineImageValue.LoadState.Loaded(ImageReference(inputs.messageID, ImageReference.Role.INLINE), inputs.imageIsGIF)
                url != null -> InlineImageValue.LoadState.Loading(url)
                else -> failedBlank
            }
            PreviewLoadState.LOADING -> url?.let(InlineImageValue.LoadState::Loading) ?: failedBlank
            PreviewLoadState.NO_PREVIEW -> url?.let(InlineImageValue.LoadState::Failed) ?: failedBlank
            PreviewLoadState.DISABLED -> url?.let(InlineImageValue.LoadState::Disabled) ?: failedBlank
            PreviewLoadState.IDLE, PreviewLoadState.MALWARE_WARNING -> url?.let(InlineImageValue.LoadState::Idle) ?: failedBlank
        }
        return InlineImageValue(state, envInputs.autoPlayGIFs, inputs.inlineImageAspect)
    }

    private fun makeLinkPreview(message: MessageDTO, url: WebURL?, inputs: MessageBuildInputs): LinkPreviewFragmentState {
        val imageRef = if (inputs.hasPreviewImageRef) ImageReference(inputs.messageID, ImageReference.Role.LINK_PREVIEW_IMAGE) else null
        val iconRef = if (inputs.hasPreviewIconRef) ImageReference(inputs.messageID, ImageReference.Role.LINK_PREVIEW_ICON) else null
        val mode = when (inputs.previewState) {
            PreviewLoadState.LOADED ->
                inputs.loadedPreview?.let { LinkPreviewFragmentState.Mode.Loaded(it, imageRef, iconRef) }
                    ?: LinkPreviewFragmentState.Mode.NoPreview
            PreviewLoadState.LOADING -> url?.let(LinkPreviewFragmentState.Mode::Loading) ?: LinkPreviewFragmentState.Mode.Idle
            PreviewLoadState.NO_PREVIEW -> LinkPreviewFragmentState.Mode.NoPreview
            PreviewLoadState.DISABLED -> url?.let(LinkPreviewFragmentState.Mode::Disabled) ?: LinkPreviewFragmentState.Mode.Idle
            PreviewLoadState.IDLE -> idleLinkPreviewMode(message, url, imageRef, iconRef)
            PreviewLoadState.MALWARE_WARNING -> LinkPreviewFragmentState.Mode.Idle
        }
        return LinkPreviewFragmentState(mode, inputs.previewHeroAspect)
    }

    private fun idleLinkPreviewMode(
        message: MessageDTO,
        url: WebURL?,
        imageRef: ImageReference?,
        iconRef: ImageReference?,
    ): LinkPreviewFragmentState.Mode {
        val legacyURL = message.linkPreviewURL?.let(WebURL::parse)
        return when {
            legacyURL != null -> LinkPreviewFragmentState.Mode.Legacy(legacyURL, message.linkPreviewTitle, imageRef, iconRef)
            url != null -> LinkPreviewFragmentState.Mode.Loading(url)
            else -> LinkPreviewFragmentState.Mode.Idle
        }
    }

    private fun makeEnvelope(message: MessageDTO, inputs: MessageBuildInputs): MessageItemEnvelope = MessageItemEnvelope(
        messageID = message.id,
        isOutgoing = message.isOutgoing,
        senderName = inputs.senderResolution.displayName,
        senderResolution = inputs.senderResolution,
        status = message.status,
        // Send time, not drain time: a days-old drained message must not be relabeled at delivery time.
        date = message.senderDate,
        hasFailed = message.hasFailed,
        containsSelfMention = message.containsSelfMention,
        mentionSeen = message.mentionSeen,
        incomingAvatar = inputs.incomingAvatar,
    )

    private fun makeFooter(message: MessageDTO, inputs: MessageBuildInputs, envInputs: EnvInputs): MessageFooter {
        val showHop = envInputs.showIncomingHopCount && message.isFloodRouted && !message.isOutgoing
        val showHeardCount = envInputs.showIncomingHeardCount && !message.isOutgoing && message.heardRepeats > 0
        // Region chip: flood routes only, when the setting is on; the slash join is baked here.
        val showRegion = envInputs.showIncomingRegion && message.isFloodRouted
        val resolved = if (showRegion) RegionScopeSemantics.coalesce(message.regionScope, message.regionScopeMatches) else null
        // Send time shows inside every bubble, unconditionally: the clock-corrected senderDate.
        val sendTimeToShow: Instant = message.senderDate
        return MessageFooter(
            showHop = showHop,
            hopCount = message.hopCount,
            formattedPath = inputs.formattedPath,
            regionToShow = resolved?.let(RegionScopeSemantics::chipLabel),
            regionMatchNames = resolved?.let(RegionScopeSemantics::matchNames) ?: SnapshotList.empty(),
            sendTimeToShow = sendTimeToShow,
            sendTimeWasCorrected = message.timestampCorrected,
            showStatusRow = message.isOutgoing,
            status = message.status,
            isChannelMessage = message.isChannelMessage,
            heardRepeats = message.heardRepeats,
            showHeardCount = showHeardCount,
            retryAttempt = message.retryAttempt,
            maxRetryAttempts = message.maxRetryAttempts,
            sendCount = message.sendCount,
        )
    }

    private fun makeGrouping(inputs: MessageBuildInputs) = GroupingFlags(
        showTimestamp = inputs.showTimestamp,
        showDirectionGap = inputs.showDirectionGap,
        showSenderName = inputs.showSenderName,
        showNewMessagesDivider = inputs.showNewMessagesDivider,
        showDayDivider = inputs.showDayDivider,
    )

    /** Pre-computed `.onAppear` predicate: idle, a detected URL, and no persisted legacy preview. */
    private fun shouldRequestPreviewFetch(inputs: MessageBuildInputs, message: MessageDTO): Boolean =
        inputs.previewState == PreviewLoadState.IDLE && inputs.cachedURL != null && message.linkPreviewURL == null &&
            MecpMessage.decode(message.text) == null
}
