// PortedFrom: MC1/Views/Chats/Navigation/ChatLinkRouter.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.contracts.AppTab
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AppDeepLinkRouter(private val environment: AppDeepLinkEnvironment) {
    suspend fun routeExternal(uriText: String): DeepLinkRouteOutcome {
        val previousTab = commitIfActive {
            environment.selectedTab.also { environment.selectedTab = AppTab.CHATS }
        }
        val outcome = route(uriText)
        commitIfActive {
            if (outcome == DeepLinkRouteOutcome.Rejected) environment.selectedTab = previousTab
        }
        return outcome
    }

    suspend fun route(uriText: String): DeepLinkRouteOutcome {
        currentCoroutineContext().ensureActive()
        val uri = try {
            URI(uriText)
        } catch (_: URISyntaxException) {
            return DeepLinkRouteOutcome.Rejected
        }
        if (uri.scheme.equals(MeshCoreUriParser.SCHEME, ignoreCase = true)) {
            return when (val link = MeshCoreUriParser.parse(uriText)) {
                is MeshCoreDeepLink.Map -> {
                    commitIfActive { environment.navigateToMap(link.latitude, link.longitude) }
                    DeepLinkRouteOutcome.Navigated
                }
                is MeshCoreDeepLink.Contact -> routeContact(link)
                is MeshCoreDeepLink.Channel -> routeChannel(link)
                else -> DeepLinkRouteOutcome.Rejected
            }
        }
        if (uri.scheme.equals(HashtagDeepLinkSupport.SCHEME, ignoreCase = true) &&
            uri.host.equals(HashtagDeepLinkSupport.HOST, ignoreCase = true)
        ) {
            val fullName = HashtagDeepLinkSupport.channelNameFromUri(uri)
                ?.let(HashtagDeepLinkSupport::fullChannelName)
                ?: return DeepLinkRouteOutcome.Rejected
            return routeHashtag(MeshCoreDeepLink.Hashtag(fullName))
        }
        return DeepLinkRouteOutcome.Rejected
    }

    private suspend fun routeContact(link: MeshCoreDeepLink.Contact): DeepLinkRouteOutcome {
        if (link.publicKey == environment.connectedDevicePublicKey) return DeepLinkRouteOutcome.IgnoredSelfContact
        val existing = environment.currentRadioId?.let { radioId ->
            lookupOrNull { environment.fetchContact(radioId, link.publicKey) }
        }
        return commitIfActive {
            if (existing != null) {
                environment.navigateToContactDetail(existing)
                DeepLinkRouteOutcome.Navigated
            } else {
                environment.stageContactConfirmation(link)
                DeepLinkRouteOutcome.ConfirmationStaged
            }
        }
    }

    private suspend fun routeChannel(link: MeshCoreDeepLink.Channel): DeepLinkRouteOutcome {
        val existing = environment.currentRadioId?.let { radioId ->
            lookupOrNull { environment.fetchChannels(radioId) }?.firstOrNull { it.secret == link.secret }
        }
        return commitIfActive {
            if (existing != null) {
                environment.navigateToChannel(existing)
                DeepLinkRouteOutcome.Navigated
            } else {
                environment.stageChannelConfirmation(link)
                DeepLinkRouteOutcome.ConfirmationStaged
            }
        }
    }

    private suspend fun routeHashtag(link: MeshCoreDeepLink.Hashtag): DeepLinkRouteOutcome {
        val existing = environment.currentRadioId?.let { radioId ->
            lookupOrNull { environment.fetchChannels(radioId) }
                ?.let { HashtagDeepLinkSupport.findChannelByName(link.fullName, it) }
        }
        return commitIfActive {
            if (existing != null) {
                environment.navigateToChannel(existing)
                DeepLinkRouteOutcome.Navigated
            } else {
                environment.stageHashtagConfirmation(link)
                DeepLinkRouteOutcome.ConfirmationStaged
            }
        }
    }

    private suspend fun <T> commitIfActive(block: () -> T): T {
        val context = currentCoroutineContext()
        val commit = { context.ensureActive(); block() }
        val guard = context[PendingExternalRoute.CommitGuard]
        return if (guard == null) commit() else guard.commit(commit)
    }

    private suspend fun <T> lookupOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
