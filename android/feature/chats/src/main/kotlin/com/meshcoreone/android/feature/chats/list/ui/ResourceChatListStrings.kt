// AndroidOnly: WP-306 Resource-backed implementation of the feature text seam.
package com.meshcoreone.android.feature.chats.list.ui

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.feature.chats.list.ChatListStrings

internal class ResourceChatListStrings(private val resources: Resources) : ChatListStrings {
    override fun channelDefaultName(index: Int) = AppChatsStrings.chatsChannelDefaultName(resources, index)
    override fun floodRouting() = resources.getString(AppChatsStrings.chatsConnectionStatusFloodRouting)
    override fun directHops(hops: Long) = AppChatsStrings.chatsConnectionStatusDirect(resources, hops.toInt())
    override fun headerRegion(region: String) = AppChatsStrings.chatsChannelHeaderRegion(resources, region)
    override fun scopedDefault(region: String) = AppChatsStrings.chatsChannelInfoRegionScopedDefault(resources, region)
}

/** Recreated when the locale/configuration changes so labels never go stale. */
@Composable
fun rememberChatListStrings(): ChatListStrings {
    val configuration = LocalConfiguration.current
    val resources = LocalContext.current.resources
    return remember(configuration, resources) { ResourceChatListStrings(resources) }
}
