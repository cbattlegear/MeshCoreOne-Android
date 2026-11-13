// PortedFrom: MC1/Views/Chats/ConversationRow.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ChannelConversationRow.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/ChannelAvatar.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/UnreadBadges.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/NotificationLevelIndicator.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.meshcoreone.android.core.designsystem.AvatarCategory
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.designsystem.readableGlyph
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.ui.AvatarImageCache
import com.meshcoreone.android.core.ui.AvatarImageState
import com.meshcoreone.android.core.ui.ContactAvatar
import com.meshcoreone.android.core.ui.ConversationTimestamp
import com.meshcoreone.android.core.ui.NodeAvatar
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.list.Conversation
import java.time.Clock
import java.time.Instant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val AvatarSize = 44.dp
internal val RowHorizontalPadding = 16.dp
internal val RowAvatarGap = 12.dp

/** Divider start inset: row padding + avatar (which grows with font size) + gap, so every divider lines up with the text. */
internal fun conversationDividerInset(fontScale: Float): androidx.compose.ui.unit.Dp =
    RowHorizontalPadding + AvatarSize * fontScale.coerceAtLeast(1f) + RowAvatarGap

/** Display data a row needs beyond the [Conversation] itself. */
data class ConversationRowModel(
    val conversation: Conversation,
    val title: String,
    val preview: String?,
    val hasFailedSend: Boolean,
    val isTogglingFavorite: Boolean,
)

@Composable
internal fun ConversationRowContent(
    model: ConversationRowModel,
    clock: Clock,
    referenceDate: Instant?,
    avatarCache: AvatarImageCache?,
    modifier: Modifier = Modifier,
) {
    val conversation = model.conversation
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(RowAvatarGap), verticalAlignment = Alignment.CenterVertically) {
        ConversationAvatar(conversation, model.title, avatarCache)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                NotificationLevelIndicator(conversation.notificationLevel)
                when {
                    model.isTogglingFavorite -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    conversation.isFavorite -> Icon(
                        MeshSymbol.READY.vector, stringResource(AppChatsStrings.chatsRowFavorite),
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                conversation.lastMessageDate?.let { ConversationTimestamp(it, clock, referenceDate = referenceDate) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.preview ?: stringResource(AppChatsStrings.chatsRowNoMessages), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (model.hasFailedSend) Icon(
                    MeshSymbol.WARNING.vector, stringResource(AppChatsStrings.chatsRowFailedSend),
                    Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error,
                )
                UnreadBadges(conversation.unreadCount, unreadMentionCount(conversation), conversation.notificationLevel)
            }
        }
    }
}

private fun unreadMentionCount(conversation: Conversation): Long = when (conversation) {
    is Conversation.Direct -> conversation.contact.unreadMentionCount
    is Conversation.Channel -> conversation.channel.unreadMentionCount
    is Conversation.Room -> 0
}

@Composable
private fun ConversationAvatar(conversation: Conversation, title: String, avatarCache: AvatarImageCache?) {
    when (conversation) {
        is Conversation.Direct -> {
            val data = conversation.contact.avatarImageData
            val image by produceState<AvatarImageState>(AvatarImageState.Absent, data, avatarCache) {
                value = if (data == null || avatarCache == null) AvatarImageState.Absent
                else withContext(Dispatchers.Default) { avatarCache.decode(data) }
            }
            ContactAvatar(title, AvatarSize, image = image)
        }
        is Conversation.Channel -> ChannelAvatar(conversation.channel, AvatarSize, title)
        is Conversation.Room -> NodeAvatar(RemoteNodeRole.ROOM_SERVER, AvatarSize, UiText.Verbatim(title))
    }
}

/** Globe for the public channel, hashtag for `#` channels, lock for private ones. */
@Composable
internal fun ChannelAvatar(channel: ChannelDTO, size: androidx.compose.ui.unit.Dp, label: String) {
    val frame = LocalMeshTheme.current.frame
    val fill = frame.categoryAvatarColor(AvatarCategory.CHANNEL)
    val glyph = readableGlyph(frame.avatarGlyphColor(fill, frame.theme.usesCategoryAvatarOverride), fill)
    val symbol = when {
        channel.isPublicChannel -> MeshSymbol.GLOBE
        channel.name.startsWith("#") -> MeshSymbol.HASHTAG
        else -> MeshSymbol.LOCK
    }
    val diameter = size * LocalDensity.current.fontScale.coerceAtLeast(1f)
    Box(Modifier.size(diameter).clip(CircleShape).background(fill.toComposeColor()).semantics { contentDescription = label },
        contentAlignment = Alignment.Center) {
        Icon(symbol.vector, null, Modifier.size(diameter * 0.45f), tint = glyph.toComposeColor())
    }
}

@Composable
internal fun NotificationLevelIndicator(level: NotificationLevel) {
    val (symbol, label) = when (level) {
        NotificationLevel.MUTED -> MeshSymbol.ERROR to AppChatsStrings.chatsRowMuted
        NotificationLevel.MENTIONS_ONLY -> MeshSymbol.SIGNAL to AppChatsStrings.chatsRowMentionsOnly
        NotificationLevel.ALL -> return
    }
    Icon(symbol.vector, stringResource(label), Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Mention "@" badge and unread-count capsule; the accent applies per notification level. */
@Composable
internal fun UnreadBadges(unreadCount: Long, unreadMentionCount: Long, level: NotificationLevel) {
    val mentionColor = if (level == NotificationLevel.MUTED) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
    val unreadColor = if (level == NotificationLevel.ALL) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    // The row's merged description announces the count; the visual badges stay out of the semantic tree.
    Row(Modifier.clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (unreadMentionCount > 0) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(mentionColor), contentAlignment = Alignment.Center) {
                Text("@", color = MaterialTheme.colorScheme.onPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (unreadCount > 0) {
            Box(Modifier.clip(RoundedCornerShape(50)).background(unreadColor).padding(horizontal = 6.dp, vertical = 2.dp)) {
                Text(unreadCount.toString(), color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
