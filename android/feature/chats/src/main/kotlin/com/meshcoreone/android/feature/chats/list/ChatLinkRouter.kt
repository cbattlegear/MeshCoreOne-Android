// PortedFrom: MC1/Views/Chats/Navigation/ChatLinkRouter.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.AppTab
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Shared routing for chat-content URLs. Returns true for any URL whose scheme (and host, for
 * `meshcoreone`) the router claims, so the caller does not fall through to the system URL handler;
 * false for external schemes and for a `meshcore://` URL no parser matched. Async lookups run on
 * [scope] (fire-and-forget, as the Swift `Task`s did). `meshcoreone://mention/...` stays local to the
 * conversation view.
 */
class ChatLinkRouter(
    private val environment: ChatLinkEnvironment,
    private val scope: CoroutineScope,
    private val diagnostics: ChatListDiagnostics = ChatListDiagnostics { _, _ -> },
) {
    fun route(url: String): Boolean {
        val parsed = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return false
        }
        if (parsed.scheme == MeshCoreLinkParser.SCHEME) return handleMeshCoreLink(url)
        if (parsed.scheme == HashtagLinks.SCHEME && parsed.host == HashtagLinks.HOST) {
            val name = HashtagLinks.channelNameFromUrl(parsed)
            if (name != null) handleHashtagTap(name) else diagnostics.report("Hashtag URL missing name", null)
            return true
        }
        return false
    }

    /**
     * Routes a URL handed to the app from outside a chat. Switches to Chats first (confirmation sheets
     * present only from Chats) and restores the previous tab when nothing was handled.
     */
    fun routeExternalOpen(url: String): Boolean {
        val previousTab = environment.selectedTab
        environment.selectedTab = AppTab.CHATS
        val handled = route(url)
        if (!handled) environment.selectedTab = previousTab
        return handled
    }

    private fun handleMeshCoreLink(url: String): Boolean {
        val parser = environment.parser
        parser.parseMapURL(url)?.let {
            environment.navigateToMap(it.latitude, it.longitude)
            return true
        }
        parser.parseContactURL(url)?.let {
            handleContactLink(it)
            return true
        }
        parser.parseChannelURL(url)?.let {
            handleChannelLink(it)
            return true
        }
        // Scheme/host/path only: query values can include channel secrets.
        diagnostics.report("Failed to parse meshcore URL: ${redacted(url)}", null)
        return false
    }

    private fun redacted(url: String): String = url.substringBefore('?')

    private fun handleContactLink(link: ContactLinkResult) {
        if (link.publicKey == environment.connectedDevicePublicKey) return
        val radioId = environment.currentRadioId
        val generation = environment.contactLinkGeneration
        launchLookup {
            val existing = radioId?.let { runCatchingNonCancellation { environment.fetchContact(it, link.publicKey) } }
            currentCoroutineContext().ensureActive()
            if (link.publicKey == environment.connectedDevicePublicKey) return@launchLookup
            if (!environment.completeContactLookup(link, existing, radioId, generation)) {
                diagnostics.report("Contact-link lookup context is no longer current", null)
            }
        }
    }

    private fun handleChannelLink(link: ChannelLinkResult) {
        launchLookup {
            val radioId = environment.currentRadioId
            val existing = radioId?.let {
                runCatchingNonCancellation { environment.fetchChannels(it) }?.firstOrNull { channel -> channel.secret == link.secret }
            }
            if (existing != null) environment.navigateToChannel(existing) else environment.stageChannelLink(link)
        }
    }

    private fun handleHashtagTap(name: String) {
        launchLookup {
            val fullName = HashtagLinks.fullChannelName(name)
            if (fullName == null) {
                diagnostics.report("Invalid hashtag name in tap", null)
                return@launchLookup
            }
            val radioId = environment.currentRadioId
            if (radioId == null) {
                environment.stageHashtag(fullName)
                return@launchLookup
            }
            val channels = try {
                environment.fetchChannels(radioId)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                diagnostics.report("Failed to fetch channels for hashtag lookup", failure)
                environment.stageHashtag(fullName)
                return@launchLookup
            }
            val existing = HashtagLinks.findChannelByName(fullName, channels)
            if (existing != null) environment.navigateToChannel(existing) else environment.stageHashtag(fullName)
        }
    }

    private fun launchLookup(block: suspend () -> Unit) {
        scope.launch { block() }
    }

    /** `try?`: a failed lookup is treated as "not found"; cancellation still propagates. */
    private suspend fun <T> runCatchingNonCancellation(block: suspend () -> T): T? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        diagnostics.report("Link lookup failed", failure)
        null
    }
}
