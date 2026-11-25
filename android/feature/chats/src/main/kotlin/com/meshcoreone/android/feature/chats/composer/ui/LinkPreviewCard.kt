// PortedFrom: MC1/Views/Chats/Components/LinkPreviewCard.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/LinkPreviewLoadingCard.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/TapToLoadPreview.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/MalwareWarningCard.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Chats/Components/Fragments/LinkPreviewFragmentView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.composer.InlineImageSource
import com.meshcoreone.android.feature.chats.composer.LinkOpenPolicy
import com.meshcoreone.android.feature.chats.composer.LinkPreviewCardState
import com.meshcoreone.android.feature.chats.composer.LinkPreviewData
import com.meshcoreone.android.feature.chats.composer.LinkPreviewMetrics
import com.meshcoreone.android.feature.chats.composer.LinkPreviewStateHolder

private val CARD_SHAPE = RoundedCornerShape(LinkPreviewMetrics.CORNER_RADIUS_DP.dp)

/** Starts the holder once and renders its state. [onOpen] receives only http(s) URLs. */
@Composable
fun LinkPreviewRoute(
    holder: LinkPreviewStateHolder,
    images: InlineImageSource,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
    onImageError: (String, Throwable) -> Unit = { _, _ -> },
) {
    val state by holder.state.collectAsStateWithLifecycle()
    LaunchedEffect(holder) { holder.start() }
    LinkPreviewCard(state, images, onOpen, holder::manualLoad, modifier, onImageError)
}

@Composable
fun LinkPreviewCard(
    state: LinkPreviewCardState,
    images: InlineImageSource,
    onOpen: (String) -> Unit,
    onTapToLoad: () -> Unit,
    modifier: Modifier = Modifier,
    onImageError: (String, Throwable) -> Unit = { _, _ -> },
) {
    when (state) {
        LinkPreviewCardState.Hidden -> Unit
        is LinkPreviewCardState.Loading -> StatusCard(state.url, modifier, loading = true, onClick = null)
        is LinkPreviewCardState.TapToLoad -> StatusCard(state.url, modifier, loading = false, onClick = onTapToLoad)
        is LinkPreviewCardState.Malware -> MalwareCard(state.url, modifier)
        is LinkPreviewCardState.Loaded -> LoadedCard(state.data, images, onOpen, modifier, onImageError)
    }
}

@Composable
private fun StatusCard(url: String, modifier: Modifier, loading: Boolean, onClick: (() -> Unit)?) {
    val resources = LocalResources.current
    val host = LinkOpenPolicy.host(url) ?: "link"
    val label = if (loading) AppChatsStrings.chatsPreviewLoadingAccessibility(resources, host) else AppChatsStrings.chatsPreviewTapAccessibility(resources, host)
    val hint = stringResource(if (loading) AppChatsStrings.chatsPreviewLoadingHint else AppChatsStrings.chatsPreviewTapHint)
    Row(
        modifier
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (onClick != null) it.clickable(onClickLabel = hint, role = Role.Button, onClick = onClick) else it }
            .sharedTouchTarget()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                stateDescription = hint
            },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
        else Icon(MeshSymbol.GLOBE.vector, null, Modifier.size(16.dp))
        Text(
            stringResource(if (loading) AppChatsStrings.chatsPreviewLoading else AppChatsStrings.chatsPreviewTapToLoad),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun MalwareCard(url: String, modifier: Modifier) {
    val resources = LocalResources.current
    val domain = LinkOpenPolicy.host(url) ?: url
    val label = AppChatsStrings.chatsMalwareWarningAccessibility(resources, domain)
    Row(
        modifier
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(10.dp)
            .semantics(mergeDescendants = true) { contentDescription = label },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MeshSymbol.WARNING.vector, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.error)
        Column {
            Text(
                stringResource(AppChatsStrings.chatsMalwareWarningTitle), fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium,
            )
            Text(domain, color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LoadedCard(
    data: LinkPreviewData,
    images: InlineImageSource,
    onOpen: (String) -> Unit,
    modifier: Modifier,
    onImageError: (String, Throwable) -> Unit,
) {
    val resources = LocalResources.current
    val domain = LinkOpenPolicy.host(data.url) ?: data.url
    val title = data.title?.takeIf { it.isNotEmpty() }
    val label = AppChatsStrings.chatsLinkPreviewAccessibilityLabel(resources, title ?: domain, domain)
    val hint = stringResource(AppChatsStrings.chatsLinkPreviewAccessibilityHint)
    val hero by produceHero(data.imageRef, images, onImageError)
    val openable = LinkOpenPolicy.isOpenable(data.url)
    Column(
        modifier
            .clip(CARD_SHAPE)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .let { if (openable) it.clickable(onClickLabel = hint, role = Role.Button) { onOpen(data.url) } else it }
            .semantics(mergeDescendants = true) { contentDescription = label },
    ) {
        hero?.let { image ->
            Image(
                image, null,
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(LinkPreviewMetrics.heroAspect(data.imageWidth, data.imageHeight).toFloat())
                    .heightIn(min = LinkPreviewMetrics.MIN_HERO_HEIGHT_DP.dp, max = LinkPreviewMetrics.MAX_HERO_HEIGHT_DP.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Row(Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(MeshSymbol.GLOBE.vector, null, Modifier.size(16.dp))
            Column {
                title?.let { Text(it, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium) }
                Text(domain, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** Loads the hero through the app-bound source; a failure is reported and the card stays tappable without the image. */
@Composable
private fun produceHero(ref: String?, images: InlineImageSource, onError: (String, Throwable) -> Unit) =
    produceState<ImageBitmap?>(null, ref, images) {
        value = if (ref == null) null else try {
            images.load(ref)
        } catch (cancelled: kotlin.coroutines.cancellation.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            onError(ref, failure)
            null
        }
    }
