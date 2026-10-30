// AndroidOnly: WP-407 Compose chat content adapters reuse the bounded, SSRF-guarded process content services.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.compose.ui.graphics.asImageBitmap
import com.meshcoreone.android.app.content.*
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.LinkPreviewDataDTO
import com.meshcoreone.android.core.services.content.*
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.datastore.PreferenceSnapshot
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.feature.chats.composer.InlineImageSource
import com.meshcoreone.android.feature.chats.composer.LinkPreviewPort
import com.meshcoreone.android.feature.chats.composer.LinkPreviewOutcome
import com.meshcoreone.android.feature.chats.composer.LinkPreviewData
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.meshcoreone.android.feature.chats.timeline.TimelineContent
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.rendering.PreviewLoadState
import com.meshcoreone.android.core.services.rendering.WebURL
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.maps.MapSnapshotRequest
import com.meshcoreone.android.feature.chats.composer.LinkPreviewCardState

internal sealed interface ChatImage {
    class Decoded(val handle: DecodedImageHandle, val encodedData: EncodedImageBytes) : ChatImage
    data object HtmlPage : ChatImage
}

class AppChatContent internal constructor(
    context: Context,
    private val store: PersistenceStoreProtocol,
    http: BoundedHttpFetching,
    preferences: LinkPreviewPreferencesSource,
    private val preferenceStore: PreferenceStore,
    initialPreferences: PreferenceSnapshot,
    scope: CoroutineScope,
    internal val mapPreviews: AppChatMapPreviews = AppChatMapPreviews(context, scope,
        enabled = { preferenceStore.get(AppStorageKey.showMapPreviewThumbnails) }),
    imageSafety: suspend (String) -> Boolean = { UrlSafetyChecker.isSafe(it) },
) : AutoCloseable, TimelineContent {
    internal val resources = context.resources
    val rendering = preferenceStore.snapshots.catch { failure ->
        java.util.logging.Logger.getLogger("com.mc1.ChatContent").log(java.util.logging.Level.WARNING, "Chat rendering preferences unavailable", failure)
        throw failure
    }.stateIn(scope, SharingStarted.Eagerly, initialPreferences)
    private val previewCache = LinkPreviewCache(NativeLinkMetadataFetching(http), LinkPreviewPreferences(preferences), scope)
    private val imageCache = InlineImageCache(http, isUrlSafe = imageSafety)
    private val dimensions = inlineImageDimensionsStore(context).also(imageCache::attachDimensionsStore)
    private val mutableResolutions = MutableStateFlow(0L)
    internal val resolutions: StateFlow<Long> = mutableResolutions.asStateFlow()
    private val previewStates = android.util.LruCache<java.util.UUID, PreviewLoadState>(128)
    private val dimensionSubscription = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        dimensions.resolutionUpdates.collect { invalidateMedia() }
    }
    private val decoder = InlineImageDecoder()
    private val thumbnails = BitmapImageDecoder()
    private val emojiCatalog = AppEmojiCatalog(context.assets)
    internal val emojiPreferences = AppEmojiPreferences(preferenceStore, scope)
    init {
        scope.launch {
            rendering.map { it[AppStorageKey.showMapPreviewThumbnails] }.distinctUntilChanged().collect {
                if (it) mapPreviews.snapshots.clearFailures()
            }
        }
    }
    internal fun emojiPicker(screenScope: CoroutineScope) =
        com.meshcoreone.android.feature.chats.composer.EmojiPickerStateHolder(
            com.meshcoreone.android.feature.chats.composer.EmojiCatalogSource {
                emojiPreferences.refresh()
                emojiCatalog.load()
            }, emojiPreferences, screenScope,
        )
    val malware = AndroidMalwareDomainFilter(context, com.meshcoreone.android.core.datastore.MeshCoreStorage.get(context).preferences, http, scope = scope)

    private val prefetcher by lazy {
        InlineImagePrefetcher(
            InlineImageDimensionProbing { url ->
                val host = URI(url).host
                if (host != null && !malware.isBlocked(host)) imageCache.probeImageDimensions(url) else null
            },
            object : LinkPreviewCaching by previewCache {
                override suspend fun preview(url: String, dataStore: com.meshcoreone.android.core.contracts.domain.LinkPreviewPersisting,
                    isChannelMessage: Boolean): LinkPreviewResult {
                    if (!automatic(isChannelMessage)) {
                        val cached = previewCache.cachedPreview(url) ?: dataStore.fetchLinkPreview(url)
                        return cached?.let(LinkPreviewResult::Loaded) ?: LinkPreviewResult.Disabled
                    }
                    val host = URI(url).host
                    if (host == null || malware.isBlocked(host)) return LinkPreviewResult.NoPreviewAvailable
                    return previewCache.preview(url, dataStore, isChannelMessage)
                }
            },
            dimensions,
            store,
        )
    }
    override val enabled get() = rendering.value[AppStorageKey.linkPreviewsEnabled]
    override fun containsUrls(text: String) = LinkUrlExtraction.extractAllUrls(text).isNotEmpty()
    override suspend fun prefetch(text: String, isChannelMessage: Boolean) {
        if (!enabled) return
        prefetcher.prefetch(text, isChannelMessage, automatic(isChannelMessage))
        invalidateMedia()
    }
    private fun automatic(isChannelMessage: Boolean) = rendering.value[
        if (isChannelMessage) AppStorageKey.linkPreviewsAutoResolveChannels else AppStorageKey.linkPreviewsAutoResolveDM
    ]
    internal val primerPreferences = com.meshcoreone.android.app.state.LinkPreviewPreferences(::automatic)
    internal val primerWarmer = object : com.meshcoreone.android.app.state.InlinePreviewPrefetching {
        override fun containsUrls(text: String) = this@AppChatContent.containsUrls(text)
        override suspend fun prefetch(text: String, isChannelMessage: Boolean, allowImageProbes: Boolean) {
            if (!enabled) return
            prefetcher.prefetch(text, isChannelMessage, allowImageProbes && automatic(isChannelMessage))
            invalidateMedia()
        }
    }
    private fun invalidateMedia() {
        mutableResolutions.update(Math::incrementExact)
    }
    internal val renderInvalidations: Flow<Unit> = merge(
        rendering.map { Unit }, resolutions.map { Unit }, mapPreviews.network.map { Unit },
        mapPreviews.snapshots.resolutions.map { Unit },
    )
    internal fun applyRenderPreferences(base: EnvInputs) = base.copy(
        autoPlayGIFs = rendering.value[AppStorageKey.autoPlayGIFs],
        showIncomingPath = rendering.value[AppStorageKey.showIncomingPath],
        showIncomingHopCount = rendering.value[AppStorageKey.showIncomingHopCount],
        showIncomingRegion = rendering.value[AppStorageKey.showIncomingRegion],
        showIncomingHeardCount = rendering.value[AppStorageKey.showIncomingHeardCount],
        showIncomingSendTime = rendering.value[AppStorageKey.showIncomingSendTime],
        previewsEnabled = enabled,
        showMapPreviews = rendering.value[AppStorageKey.showMapPreviewThumbnails],
        isOffline = mapPreviews.network.value != ChatMapNetwork.Online,
        translationOffersEnabled = false,
    )
    internal data class RenderContent(
        val url: WebURL?,
        val state: PreviewLoadState,
        val preview: LinkPreviewDataDTO?,
        val inline: Boolean,
        val hasInlineRef: Boolean,
        val gif: Boolean,
        val aspect: Double?,
        val coordinate: Coordinate?,
        val mapReady: Boolean,
    )
    internal suspend fun renderContent(message: MessageDTO, env: EnvInputs): RenderContent {
        val media = media(message.text).firstOrNull()
        val url = media?.url
        val decoded = url?.let { imageCache.decoded(ImageUrlClassifier.directImageUrl(URI(it)).toString()) }
        val preview = if (enabled) url?.let { previewCache.cachedPreview(it, store) } else null
        val recorded = previewStates.get(message.id)
        val state = when {
            !enabled -> PreviewLoadState.DISABLED
            recorded == PreviewLoadState.MALWARE_WARNING -> recorded
            recorded == PreviewLoadState.NO_PREVIEW -> recorded
            !automatic(message.isChannelMessage) && decoded == null && preview == null &&
                recorded != PreviewLoadState.LOADED && recorded != PreviewLoadState.LOADING ->
                PreviewLoadState.DISABLED
            recorded == PreviewLoadState.LOADING -> recorded
            decoded != null || preview != null -> PreviewLoadState.LOADED
            url == null -> PreviewLoadState.IDLE
            else -> recorded ?: PreviewLoadState.IDLE
        }
        val coordinate = com.meshcoreone.android.app.navigation.chatMapPreviewCoordinate(message.text, env.showMapPreviews)
        val mapReady = coordinate?.let {
            mapPreviews.snapshots.isResolved(MapSnapshotRequest(it.latitude, it.longitude, env.isDark, env.isOffline))
        } ?: false
        return RenderContent(url?.let(WebURL::parse), state, preview, media?.isImage == true && preview == null,
            decoded != null, decoded?.isGif == true, media?.aspect, coordinate, mapReady)
    }
    internal fun recordPreviewState(id: java.util.UUID, state: LinkPreviewCardState) = recordPreviewState(id, when (state) {
        LinkPreviewCardState.Hidden -> PreviewLoadState.NO_PREVIEW
        is LinkPreviewCardState.Loading -> PreviewLoadState.LOADING
        is LinkPreviewCardState.TapToLoad -> PreviewLoadState.DISABLED
        is LinkPreviewCardState.Malware -> PreviewLoadState.MALWARE_WARNING
        is LinkPreviewCardState.Loaded -> PreviewLoadState.LOADED
    })
    internal fun recordPreviewState(id: java.util.UUID, state: PreviewLoadState) {
        if (previewStates.put(id, state) != state) invalidateMedia()
    }
    internal data class Media(val url: String, val isImage: Boolean, val aspect: Double?)
    internal fun media(text: String): List<Media> = LinkUrlExtraction.extractAllUrls(text).distinct().mapNotNull { url ->
        val uri = try { URI(url) } catch (_: java.net.URISyntaxException) {
            java.util.logging.Logger.getLogger("com.mc1.ChatContent").fine("Malformed extracted media URI; attachment skipped")
            return@mapNotNull null
        }
        val resolved = ImageUrlClassifier.directImageUrl(uri).toString()
        Media(url, ImageUrlClassifier.isImageUrl(uri) && !imageCache.servesHtmlPage(resolved),
            dimensions.aspect(resolved) ?: dimensions.aspect(url))
    }
    private val previews = object : LinkPreviewPort {
        override suspend fun cachedPreview(url: String) = previewCache.cachedPreview(url, store)?.forUi()
        override suspend fun preview(url: String, isChannelMessage: Boolean) = previewCache.preview(url, store, isChannelMessage).forUi()
        override suspend fun manualFetch(url: String) = previewCache.manualFetch(url, store).forUi()
    }
    internal fun previews(isChannelMessage: Boolean): LinkPreviewPort = PolicyPreviewPort(
        previews,
        enabled = { rendering.value[AppStorageKey.linkPreviewsEnabled] },
        autoResolve = { rendering.value[if (isChannelMessage) AppStorageKey.linkPreviewsAutoResolveChannels else AppStorageKey.linkPreviewsAutoResolveDM] },
    )
    val images = InlineImageSource { reference ->
        imageCache.decoded(reference)?.handle?.let { handle ->
            if (handle is BitmapDecodedImageHandle) return@InlineImageSource handle.bitmap.asImageBitmap()
        }
        val data = when {
            reference.startsWith(HERO) -> previewCache.cachedPreview(reference.removePrefix(HERO), store)?.imageData?.toByteArray()
            reference.startsWith(ICON) -> previewCache.cachedPreview(reference.removePrefix(ICON), store)?.iconData?.toByteArray()
            else -> throw IllegalArgumentException("Unknown preview image reference")
        } ?: return@InlineImageSource null
        when (val result = thumbnails.decode(data, 900)) {
            is ImageDecodeOutcome.Decoded -> {
                val handle = result.handle
                check(handle is BitmapDecodedImageHandle) { "Unexpected thumbnail decoder handle" }
                imageCache.storeDecoded(CachedDecodedImage(handle, false, null), reference)
                handle.bitmap.asImageBitmap()
            }
            is ImageDecodeOutcome.Failed -> throw IllegalStateException(result.reason)
            is ImageDecodeOutcome.Unsupported -> throw IllegalStateException(result.reason)
            ImageDecodeOutcome.Cancelled -> throw CancellationException("Image decode cancelled")
        }
    }
    fun isImage(url: String) = ImageUrlClassifier.isImageUrl(URI(url)) &&
        !imageCache.servesHtmlPage(ImageUrlClassifier.directImageUrl(URI(url)).toString())
    fun aspect(url: String) = dimensions.aspect(ImageUrlClassifier.directImageUrl(URI(url)).toString()) ?: dimensions.aspect(url)
    internal fun shareData(image: ChatImage.Decoded): ByteArray = image.encodedData.copy()
    internal fun cachedInline(url: String): ChatImage.Decoded? {
        val resolved = ImageUrlClassifier.directImageUrl(URI(url)).toString()
        val cached = imageCache.decoded(resolved) ?: return null
        return cached.encodedData?.let { ChatImage.Decoded(cached.handle, it) }
    }
    suspend fun recordReaction(emoji: String) {
        preferenceStore.update {
            this[AppStorageKey.recentReactionEmojis] =
                com.meshcoreone.android.feature.chats.composer.RecentEmojis.record(
                    snapshot[AppStorageKey.recentReactionEmojis].ifEmpty {
                        com.meshcoreone.android.feature.chats.composer.RecentEmojis.DEFAULTS
                    }, emoji,
                ).snapshot()
        }
    }
    internal suspend fun inline(url: String): ChatImage {
        check(rendering.value[AppStorageKey.linkPreviewsEnabled]) { "Link content is disabled" }
        val resolved = ImageUrlClassifier.directImageUrl(URI(url)).toString()
        imageCache.decoded(resolved)?.let { cached ->
            return ChatImage.Decoded(cached.handle, requireNotNull(cached.encodedData) {
                "The decoded image no longer has its original encoded bytes"
            })
        }
        val fetch = withTimeoutOrNull(30_000) {
            var result = imageCache.fetchImageData(resolved)
            while (result == InlineImageResult.Loading) {
                delay(100)
                result = imageCache.fetchImageData(resolved)
            }
            result
        } ?: throw IllegalStateException("Inline image fetch timed out")
        return when (fetch) {
            is InlineImageResult.Loaded -> when (val decoded = decoder.decode(fetch.data, 900)) {
                is ImageDecodeOutcome.Decoded -> {
                    val cached = CachedDecodedImage(decoded.handle, decoded.handle is GifDecodedImageHandle, fetch.data)
                    imageCache.storeDecoded(cached, resolved)
                    invalidateMedia()
                    ChatImage.Decoded(decoded.handle, requireNotNull(cached.encodedData))
                }
                is ImageDecodeOutcome.Failed -> throw IllegalStateException(decoded.reason)
                is ImageDecodeOutcome.Unsupported -> throw IllegalStateException(decoded.reason)
                ImageDecodeOutcome.Cancelled -> throw CancellationException("Image decode cancelled")
            }
            InlineImageResult.NotImage -> {
                invalidateMedia()
                ChatImage.HtmlPage
            }
            InlineImageResult.Failed -> throw IllegalStateException("Image fetch failed")
            InlineImageResult.Loading -> error("Image fetch is still in flight")
        }
    }
    internal suspend fun retryInline(url: String) {
        imageCache.clearFailure(ImageUrlClassifier.directImageUrl(URI(url)).toString())
    }
    private fun LinkPreviewDataDTO.forUi() = LinkPreviewData(url, title,
        imageData?.let { HERO + url }, iconData?.let { ICON + url },
        imageWidth?.let(Math::toIntExact), imageHeight?.let(Math::toIntExact))
    private fun LinkPreviewResult.forUi(): LinkPreviewOutcome = when (this) {
        is LinkPreviewResult.Loaded -> LinkPreviewOutcome.Loaded(data.forUi())
        LinkPreviewResult.Loading -> LinkPreviewOutcome.Loading
        LinkPreviewResult.NoPreviewAvailable -> LinkPreviewOutcome.NoPreviewAvailable
        LinkPreviewResult.Disabled -> LinkPreviewOutcome.Disabled
        LinkPreviewResult.Failed -> LinkPreviewOutcome.Failed
    }
    fun trimMemory() {
        previewStates.evictAll()
        imageCache.clearDecodedMirror()
        imageCache.clearRawMemory()
        mapPreviews.snapshots.clear()
    }
    override fun close() {
        dimensions.close()
        dimensionSubscription.cancel()
        mapPreviews.close()
        previewCache.close()
        malware.close()
        trimMemory()
    }
    companion object {
        private const val HERO = "mc1-preview-hero:"
        private const val ICON = "mc1-preview-icon:"
    }

    internal class PolicyPreviewPort(
        private val delegate: LinkPreviewPort,
        private val enabled: () -> Boolean,
        private val autoResolve: () -> Boolean,
    ) : LinkPreviewPort {
        override suspend fun cachedPreview(url: String) =
            if (enabled()) delegate.cachedPreview(url) else null
        override suspend fun preview(url: String, isChannelMessage: Boolean): LinkPreviewOutcome = when {
            !enabled() -> LinkPreviewOutcome.NoPreviewAvailable
            !autoResolve() -> LinkPreviewOutcome.Disabled
            else -> delegate.preview(url, isChannelMessage)
        }
        override suspend fun manualFetch(url: String) =
            if (enabled()) delegate.manualFetch(url) else LinkPreviewOutcome.NoPreviewAvailable
    }
}
