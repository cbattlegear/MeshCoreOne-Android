// PortedFrom: MC1/Views/Chats/Components/InlineImageView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/Fragments/InlineImageFragmentView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/FullScreenImageViewer.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/PreviewSkeleton.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.composer.InlineImageMetrics
import com.meshcoreone.android.feature.chats.composer.InlineImageSource
import com.meshcoreone.android.feature.chats.composer.InlineImageState
import kotlin.coroutines.cancellation.CancellationException

private val IMAGE_SHAPE = RoundedCornerShape(12.dp)
private const val MAX_ZOOM = 5f

/**
 * Inline image: a reserved-footprint skeleton while loading, the image once decoded (tap opens the
 * viewer), and a tap-to-retry state on failure. Loading goes through [source]; failures are reported
 * through [onError] and shown, never swallowed.
 */
@Composable
fun InlineImage(
    url: String,
    cachedAspect: Double?,
    source: InlineImageSource,
    onError: (String, Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    var attempt by remember(url) { mutableIntStateOf(0) }
    var fullScreen by remember(url) { mutableStateOf(false) }
    val loaded by produceState<LoadResult>(LoadResult.Loading, url, attempt) {
        value = LoadResult.Loading
        value = try {
            source.load(url)?.let { LoadResult.Done(it) } ?: LoadResult.Failed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onError(url, failure)
            LoadResult.Failed
        }
    }
    when (val result = loaded) {
        LoadResult.Loading -> Box(
            modifier.fillMaxWidth().aspectRatio(InlineImageMetrics.reservedAspect(InlineImageState.Loading(cachedAspect)).toFloat())
                .clip(IMAGE_SHAPE).background(MaterialTheme.colorScheme.surfaceVariant),
        )
        LoadResult.Failed -> {
            val label = stringResource(AppChatsStrings.chatsInlineImageFailedLabel)
            val hint = stringResource(AppChatsStrings.chatsInlineImageRetryHint)
            Box(
                modifier.fillMaxWidth().aspectRatio(InlineImageMetrics.reservedAspect(InlineImageState.Failed).toFloat())
                    .clip(IMAGE_SHAPE).background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(onClickLabel = hint, role = Role.Button) { attempt++ }
                    .semantics(mergeDescendants = true) { contentDescription = label; stateDescription = hint },
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(AppChatsStrings.chatsInlineImageTapToRetry), style = MaterialTheme.typography.labelLarge) }
        }
        is LoadResult.Done -> {
            val size = InlineImageMetrics.displaySize(result.image.width, result.image.height)
            val label = stringResource(AppChatsStrings.chatsInlineImageImageAccessibility)
            val hint = stringResource(AppChatsStrings.chatsInlineImageTapHint)
            Image(
                result.image, null,
                modifier.size(size.width.dp, size.height.dp).clip(IMAGE_SHAPE)
                    .clickable(onClickLabel = hint, role = Role.Image) { fullScreen = true }
                    .semantics { contentDescription = label; stateDescription = hint },
                contentScale = ContentScale.Fit,
            )
            if (fullScreen) FullScreenImageDialog(result.image, onDismiss = { fullScreen = false })
        }
    }
}

private sealed interface LoadResult {
    data object Loading : LoadResult
    data object Failed : LoadResult
    data class Done(val image: ImageBitmap) : LoadResult
}

/** Pinch-to-zoom viewer with a Close action; zoom is clamped to 1x..5x and pan resets at 1x. */
@Composable
fun FullScreenImageDialog(image: ImageBitmap, onDismiss: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val label = stringResource(AppChatsStrings.chatsInlineImageImageAccessibility)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Image(
                image, null,
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            offset = if (scale == 1f) Offset.Zero else offset + pan
                        }
                    }
                    .graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y)
                    .semantics { contentDescription = label },
                contentScale = ContentScale.Fit,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp).sharedTouchTarget()) {
                Text(stringResource(AppChatsStrings.chatsImageViewerClose), color = Color.White)
            }
        }
    }
}
