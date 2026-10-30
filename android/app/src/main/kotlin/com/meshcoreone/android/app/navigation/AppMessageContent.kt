// AndroidOnly: WP-407 Real bounded preview/image rendering, including native animated GIF playback.
package com.meshcoreone.android.app.navigation

import android.widget.ImageView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.meshcoreone.android.app.container.AppChatContent
import com.meshcoreone.android.app.container.ChatImage
import com.meshcoreone.android.app.content.BitmapDecodedImageHandle
import com.meshcoreone.android.app.content.GifDecodedImageHandle
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.services.content.UrlSafetyChecker
import com.meshcoreone.android.core.datastore.AppStorageKey
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.meshcoreone.android.feature.chats.composer.*
import com.meshcoreone.android.feature.chats.composer.ui.LinkPreviewRoute
import com.meshcoreone.android.core.services.rendering.MessageFragment
import com.meshcoreone.android.core.services.rendering.MessageItem
import com.meshcoreone.android.core.services.rendering.PreviewLoadState
import com.meshcoreone.android.core.services.simulator.DemoOfflineContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun AppMessageContent(
    content: AppChatContent,
    message: MessageDTO,
    report: (String, Throwable?) -> Unit,
    onMap: (String) -> Unit,
    rendered: MessageItem? = null,
) {
    val resolutions by content.resolutions.collectAsState()
    val media = remember(content, message.radioId, message.id, message.text, resolutions) { content.media(message) }
    val scope = rememberCoroutineScope()
    val uri = LocalUriHandler.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences by content.rendering.collectAsState()
    val autoResolve = preferences[if (message.isChannelMessage) AppStorageKey.linkPreviewsAutoResolveChannels else AppStorageKey.linkPreviewsAutoResolveDM]
    (if (preferences[AppStorageKey.linkPreviewsEnabled]) media else emptyList()).forEach { entry ->
        val url = entry.url
        val bundledPreview = DemoOfflineContent.preview(message, url) != null
        val bundledImage = DemoOfflineContent.isInlineImage(message, url)
        if (DemoOfflineContent.isSimulatorMessage(message) && !bundledPreview && !bundledImage) return@forEach
        key(message.radioId, message.id, url, autoResolve) {
            var image by remember { mutableStateOf<ChatImage?>(null) }
            var failure by remember { mutableStateOf<Throwable?>(null) }
            var manualImage by remember { mutableStateOf(false) }
            var retry by remember { mutableIntStateOf(0) }
            var fullscreen by remember { mutableStateOf(false) }
            val inline = entry.isImage
            val holder = remember(content, message.radioId, message.id, message.text, url, message.isChannelMessage,
                preferences[AppStorageKey.linkPreviewsEnabled],
                preferences[AppStorageKey.linkPreviewsAutoResolveDM], preferences[AppStorageKey.linkPreviewsAutoResolveChannels]) {
                LinkPreviewStateHolder(url, message.isChannelMessage, content.previews(message, url),
                    UrlSafetyPort { bundledPreview || UrlSafetyChecker.isSafe(it) },
                    MalwareDomainPort { if (bundledPreview) false else content.malware.isBlocked(it) },
                    scope, ComposerDiagnostics(report))
            }
            DisposableEffect(holder) { onDispose { holder.close() } }
            if (!inline || image == ChatImage.HtmlPage) {
                val preview by holder.state.collectAsState()
                SideEffect {
                    if (url == media.firstOrNull()?.url) content.recordPreviewState(message.id, preview)
                }
                LinkPreviewRoute(holder, content.images, uri::openUri, onImageError = { _, problem ->
                    report("Preview image decode failed", problem)
                })
            } else if (!autoResolve && !manualImage) {
                SideEffect {
                    if (url == media.firstOrNull()?.url) content.recordPreviewState(message.id, PreviewLoadState.DISABLED)
                }
                TextButton(onClick = { manualImage = true }) { Text(stringResource(AppChatsStrings.chatsPreviewTapToLoad)) }
            } else if (image == null) {
                val sharedAspect = if (url == media.firstOrNull()?.url) {
                    rendered?.content?.filterIsInstance<MessageFragment.InlineImage>()?.firstOrNull()?.image?.cachedAspect
                } else null
                (sharedAspect ?: entry.aspect)?.takeIf { it.isFinite() && it > 0 }?.let { aspect ->
                    Spacer(Modifier.widthIn(max = InlineImageMetrics.MAX_WIDTH_DP.dp).fillMaxWidth().aspectRatio(aspect.toFloat()))
                }
            }
            LaunchedEffect(content, message.radioId, message.id, message.text, url, autoResolve, manualImage, retry) {
                if (!inline || (!autoResolve && !manualImage)) return@LaunchedEffect
                image = null
                failure = null
                if (url == media.firstOrNull()?.url) content.recordPreviewState(message.id, PreviewLoadState.LOADING)
                try {
                    image = if (bundledImage) {
                        content.bundledInlineImage(message, url)
                    } else {
                        val host = LinkOpenPolicy.host(url) ?: error("Image link has no host")
                        check(!content.malware.isBlocked(host)) { "Image host is blocked" }
                        check(UrlSafetyChecker.isSafe(url)) { "Unsafe image URL" }
                        if (retry > 0) content.retryInline(url)
                        content.inline(url)
                    }
                    if (url == media.firstOrNull()?.url) content.recordPreviewState(message.id, PreviewLoadState.LOADED)
                }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) {
                    report("Inline image failed", problem)
                    failure = problem
                    if (url == media.firstOrNull()?.url) content.recordPreviewState(message.id, PreviewLoadState.NO_PREVIEW)
                }
            }
            when (val loaded = image) {
                is ChatImage.Decoded -> {
                    val handle = loaded.handle
                    val size = InlineImageMetrics.displaySize(handle.width, handle.height)
                    val modifier = Modifier.widthIn(max = size.width.dp).height(size.height.dp).clickable { fullscreen = true }
                    when (handle) {
                        is BitmapDecodedImageHandle -> Image(handle.bitmap.asImageBitmap(), url, modifier)
                        is GifDecodedImageHandle -> {
                            val drawable = remember(handle) { handle.newDrawable() }
                            val autoPlay = preferences[AppStorageKey.autoPlayGIFs]
                            LifecycleResumeEffect(drawable, autoPlay) {
                                if (autoPlay && android.animation.ValueAnimator.areAnimatorsEnabled()) drawable.start()
                                onPauseOrDispose { drawable.stop() }
                            }
                            AndroidView(
                            factory = { context -> ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER } },
                            modifier = modifier,
                            update = { view ->
                                if (view.tag !== handle) {
                                    (view.drawable as? android.graphics.drawable.Animatable)?.stop()
                                    view.tag = handle
                                    view.setImageDrawable(drawable)
                                    view.contentDescription = url
                                }
                            },
                            onRelease = { (it.drawable as? android.graphics.drawable.Animatable)?.stop() },
                        )
                        }
                        else -> error("Unregistered native image decoder")
                    }
                }
                ChatImage.HtmlPage, null -> Unit
            }
            if (fullscreen) {
                val loaded = image as? ChatImage.Decoded
                val bitmap = (loaded?.handle as? BitmapDecodedImageHandle)?.bitmap
                androidx.compose.ui.window.Dialog(onDismissRequest = { fullscreen = false }) {
                    Surface {
                        Column {
                            if (bitmap != null) Image(bitmap.asImageBitmap(), url, Modifier.fillMaxWidth())
                            else (loaded?.handle as? GifDecodedImageHandle)?.let { handle ->
                                val drawable = remember(handle) { handle.newDrawable() }
                                LifecycleResumeEffect(drawable, preferences[AppStorageKey.autoPlayGIFs]) {
                                    if (preferences[AppStorageKey.autoPlayGIFs] && android.animation.ValueAnimator.areAnimatorsEnabled()) drawable.start()
                                    onPauseOrDispose { drawable.stop() }
                                }
                                AndroidView(factory = { context -> ImageView(context).apply {
                                    scaleType = ImageView.ScaleType.FIT_CENTER
                                    contentDescription = url
                                    setImageDrawable(drawable)
                                } }, modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp),
                                    onRelease = { drawable.stop() })
                            }
                            TextButton(onClick = { fullscreen = false }) {
                                Text(stringResource(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonDone))
                            }
                            TextButton(onClick = {
                                scope.launch {
                                    try { com.meshcoreone.android.app.platform.shareImage(context, content.shareData(url, message)) }
                                    catch (cancelled: CancellationException) { throw cancelled }
                                    catch (problem: Exception) { report("Image sharing failed", problem); failure = problem }
                                }
                            }) { Text(stringResource(AppChatsStrings.chatsImageViewerShare)) }
                        }
                    }
                }
            }
            if (failure != null) {
                Text(com.meshcoreone.android.core.ui.uiString(com.meshcoreone.android.core.ui.UiErrorMapper().message(requireNotNull(failure))))
                TextButton(onClick = { retry++ }) { Text(stringResource(AppChatsStrings.chatsInlineImageTapToRetry)) }
            }
        }
    }
    val coordinate = remember(message.text, preferences[AppStorageKey.showMapPreviewThumbnails], rendered) {
        val shared = rendered?.content?.filterIsInstance<MessageFragment.MapPreview>()?.firstOrNull()?.state
        if (shared != null && preferences[AppStorageKey.showMapPreviewThumbnails]) {
            com.meshcoreone.android.core.model.Coordinate(shared.latitude, shared.longitude)
        } else chatMapPreviewCoordinate(message.text, preferences[AppStorageKey.showMapPreviewThumbnails])
    }
    coordinate?.let { AppChatMapPreview(content.mapPreviews, it, onMap,
        forceOffline = DemoOfflineContent.isSimulatorMessage(message)) }
}
