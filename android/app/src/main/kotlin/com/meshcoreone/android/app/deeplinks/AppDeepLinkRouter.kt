// PortedFrom: MC1/Views/Chats/Navigation/ChatLinkRouter.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.contracts.AppTab
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class AppDeepLinkRouter(private val environment: AppDeepLinkEnvironment) {
    suspend fun routeExternal(uriText: String): DeepLinkRouteOutcome = route(uriText, external = true)

    suspend fun route(uriText: String): DeepLinkRouteOutcome = route(uriText, external = false)

    private suspend fun route(uriText: String, external: Boolean): DeepLinkRouteOutcome {
        currentCoroutineContext().ensureActive()
        val uri = try {
            URI(uriText)
        } catch (_: URISyntaxException) {
            return DeepLinkRouteOutcome.Rejected
        }
        if (uri.scheme.equals(MeshCoreUriParser.SCHEME, ignoreCase = true)) {
            return when (val link = MeshCoreUriParser.parse(uriText)) {
                is MeshCoreDeepLink.Map -> {
                    commitOutcomeIfActive {
                        environment.navigateToMap(link.latitude, link.longitude)
                        DeepLinkRouteOutcome.Navigated
                    }
                }
                is MeshCoreDeepLink.Contact -> routeContact(link, external)
                is MeshCoreDeepLink.Channel -> routeChannel(link, external)
                else -> DeepLinkRouteOutcome.Rejected
            }
        }
        if (uri.scheme.equals(HashtagDeepLinkSupport.SCHEME, ignoreCase = true) &&
            uri.host.equals(HashtagDeepLinkSupport.HOST, ignoreCase = true)
        ) {
            val fullName = HashtagDeepLinkSupport.channelNameFromUri(uri)
                ?.let(HashtagDeepLinkSupport::fullChannelName)
                ?: return DeepLinkRouteOutcome.Rejected
            return routeHashtag(MeshCoreDeepLink.Hashtag(fullName), external)
        }
        return DeepLinkRouteOutcome.Rejected
    }

    private suspend fun routeContact(link: MeshCoreDeepLink.Contact, external: Boolean): DeepLinkRouteOutcome {
        if (link.publicKey == environment.connectedDevicePublicKey) return commitOutcomeIfActive {
            if (external) environment.selectedTab = AppTab.CHATS
            DeepLinkRouteOutcome.IgnoredSelfContact
        }
        val existing = environment.currentRadioId?.let { radioId ->
            lookupOrNull { environment.fetchContact(radioId, link.publicKey) }
        }
        return commitOutcomeIfActive {
            if (existing != null) {
                environment.navigateToContactDetail(existing)
                DeepLinkRouteOutcome.Navigated
            } else {
                environment.stageContactConfirmation(link, selectChats = external)
                DeepLinkRouteOutcome.ConfirmationStaged
            }
        }
    }

    private suspend fun routeChannel(link: MeshCoreDeepLink.Channel, external: Boolean): DeepLinkRouteOutcome {
        val existing = environment.currentRadioId?.let { radioId ->
            lookupOrNull { environment.fetchChannels(radioId) }?.firstOrNull { it.secret == link.secret }
        }
        return commitOutcomeIfActive {
            if (existing != null) {
                environment.navigateToChannel(existing)
                DeepLinkRouteOutcome.Navigated
            } else {
                environment.stageChannelConfirmation(link, selectChats = external)
                DeepLinkRouteOutcome.ConfirmationStaged
            }
        }
    }

    private suspend fun routeHashtag(link: MeshCoreDeepLink.Hashtag, external: Boolean): DeepLinkRouteOutcome {
        val existing = environment.currentRadioId?.let { radioId ->
            lookupOrNull { environment.fetchChannels(radioId) }
                ?.let { HashtagDeepLinkSupport.findChannelByName(link.fullName, it) }
        }
        return commitOutcomeIfActive {
            if (existing != null) {
                environment.navigateToChannel(existing)
                DeepLinkRouteOutcome.Navigated
            } else {
                environment.stageHashtagConfirmation(link, selectChats = external)
                DeepLinkRouteOutcome.ConfirmationStaged
            }
        }
    }

    private suspend fun commitOutcomeIfActive(block: () -> DeepLinkRouteOutcome): DeepLinkRouteOutcome {
        val context = currentCoroutineContext()
        val commit = { context.ensureActive(); block() }
        val guard = context[PendingExternalRoute.CommitGuard]
        return if (guard == null) commit() else guard.commitOutcome(commit)
    }

    private suspend fun <T> lookupOrNull(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
