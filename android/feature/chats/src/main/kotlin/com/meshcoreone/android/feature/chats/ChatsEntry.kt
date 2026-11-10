// AndroidOnly: WP-002 Registered unavailable messaging entry; no fake contacts/messages/send result.
package com.meshcoreone.android.feature.chats

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.ui.AvatarImageCache
import com.meshcoreone.android.core.ui.FeatureShellCopy
import com.meshcoreone.android.core.ui.ScaffoldFeatureContent
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.list.ChatListFeatureDependencies
import com.meshcoreone.android.feature.chats.list.ChatRoute
import com.meshcoreone.android.feature.chats.list.ui.ChatsAdaptiveLayout
import com.meshcoreone.android.feature.chats.list.ui.ChatsListRoot

/**
 * Without [dependencies] (the app adapters are not bound yet) this keeps the honest "not yet ported"
 * shell: no fabricated conversations. With them, the real list shows; [detailPane] is the optional
 * timeline slot for >= 600dp (WP-307/308), and [onOpenChat] receives every navigation.
 */
@Composable
fun ChatsEntry(
    route: FeatureRoute,
    onNavigate: (FeatureRoute) -> Unit,
    dependencies: ChatListFeatureDependencies? = null,
    selectedRoute: ChatRoute? = null,
    onOpenChat: (ChatRoute) -> Unit = {},
    onClearSelection: () -> Unit = {},
    onNewChannel: () -> Unit = {},
    onRoomAuthenticationRequested: (RemoteNodeSessionDTO) -> Unit = {},
    failureText: @Composable (Throwable) -> UiText = { UiText.Verbatim(it.message ?: it.javaClass.simpleName) },
    detailPane: (@Composable (ChatRoute?) -> Unit)? = null,
    avatarCache: AvatarImageCache? = null,
    consumePendingRoomAuthentication: Boolean = true,
) {
    if (dependencies != null) {
        ChatsAdaptiveLayout(selectedRoute, detail = detailPane, list = { modifier ->
            ChatsListRoot(
                dependencies, selectedRoute, onOpenChat, onClearSelection, onNewChannel,
                onRoomAuthenticationRequested, failureText, modifier, avatarCache,
                consumePendingRoomAuthentication = consumePendingRoomAuthentication,
            )
        })
        return
    }
    ScaffoldFeatureContent(
        FeatureId.CHATS, route,
        FeatureShellCopy(
            stringResource(R.string.tab_chats),
            stringResource(R.string.scaffold_chats_description),
            stringResource(R.string.scaffold_send_message),
        ),
        onNavigate,
    )
}
