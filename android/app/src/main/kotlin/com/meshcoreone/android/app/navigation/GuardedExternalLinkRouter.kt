// AndroidOnly: WP-302 Bind external channel navigation to the initiating radio and presented entry.
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.deeplinks.AppDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.DeepLinkRouteOutcome
import com.meshcoreone.android.app.deeplinks.MeshCoreDeepLink
import com.meshcoreone.android.core.model.ChannelDTO
import java.util.logging.Logger

internal class GuardedExternalLinkRouter(
    private val container: AppContainer,
    private val navigation: NavigationCoordinator,
    private val environment: AppDeepLinkEnvironment = ContainerDeepLinkEnvironment(container, navigation),
) {
    suspend fun routeExternal(uri: String): DeepLinkRouteOutcome {
        val session = container.captureExternalChannelLinkSession()
        val guarded = object : AppDeepLinkEnvironment by environment {
            override fun navigateToChannel(channel: ChannelDTO) {
                if (channel.radioId != session.radioId || !navigation.navigateToChannelWhen(channel, session.isCurrent)) {
                    throw RetiredExternalChannelLink()
                }
            }

            override fun stageChannelConfirmation(link: MeshCoreDeepLink.Channel, selectChats: Boolean) {
                val request = ChannelLinkRequest(link.name, link.secret, link.regionScope, link.hasHashtagSecretMismatch)
                if (!navigation.stageChannelLinkWhen(request, selectChats, session.isCurrent)) {
                    throw RetiredExternalChannelLink()
                }
            }

            override fun stageHashtagConfirmation(link: MeshCoreDeepLink.Hashtag, selectChats: Boolean) {
                if (!navigation.stageHashtagWhen(HashtagJoinRequest(link.fullName), selectChats, session.isCurrent)) {
                    throw RetiredExternalChannelLink()
                }
            }
        }
        return try {
            AppDeepLinkRouter(guarded).routeExternal(uri)
        } catch (_: RetiredExternalChannelLink) {
            Logger.getLogger("com.mc1.DeepLinks").warning(
                "External channel link no longer matches its initiating radio or navigation entry",
            )
            DeepLinkRouteOutcome.Rejected
        }
    }

    private class RetiredExternalChannelLink : Exception()
}
