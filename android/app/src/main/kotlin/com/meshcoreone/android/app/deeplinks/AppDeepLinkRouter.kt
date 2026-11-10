// PortedFrom: MC1/Views/Chats/Navigation/ChatLinkRouter.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.app.navigation.ContactLinkLookupResolution
import com.meshcoreone.android.app.navigation.ContactLinkResolvedCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkCancellation
import com.meshcoreone.android.feature.chats.list.ContactLinkContext
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import java.net.URI
import java.net.URISyntaxException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
        val job = checkNotNull(currentCoroutineContext()[Job])
        val (cancellation, observation) = ContactLinkCancellation.observe(job)
        return try {
            routeContact(link, external, cancellation)
        } finally {
            observation.dispose()
        }
    }

    private suspend fun routeContact(
        link: MeshCoreDeepLink.Contact, external: Boolean, cancellation: ContactLinkCancellation,
    ): DeepLinkRouteOutcome {
        val coroutineContext = currentCoroutineContext()
        val guard = coroutineContext[PendingExternalRoute.CommitGuard]
        val original = guard?.contactLinkContext()
        val captured = original ?: ContactLinkPublications().use { publications ->
            val capture = {
                coroutineContext.ensureActive()
                environment.captureContactLinkContext(link.publicKey, publications).also {
                    guard?.retainContactLinkContext(it)
                }
            }
            if (guard == null) capture() else guard.commit(capture)
        }
        if (captured.disposition == ContactLinkCaptureDisposition.StaleContext) {
            return commitOutcomeIfActive { DeepLinkRouteOutcome.ContactLookupRejected }
        }
        var targetChanges = 0
        while (true) {
            val outcome = routeContactAttempt(captured, link, external, original != null, cancellation)
            if (outcome is DeepLinkRouteOutcome.ContactLookupFailed &&
                outcome.cause is ContactLookupTargetChanged && targetChanges++ == 0
            ) continue
            return outcome
        }
    }

    private suspend fun routeContactAttempt(
        captured: ContactLinkContext, link: MeshCoreDeepLink.Contact, external: Boolean,
        retryingDelivery: Boolean, cancellation: ContactLinkCancellation,
    ): DeepLinkRouteOutcome {
        val coroutineContext = currentCoroutineContext()
        val guard = coroutineContext[PendingExternalRoute.CommitGuard]
        val resolve = {
            coroutineContext.ensureActive()
            environment.resolveContactLinkContext(captured, link.publicKey)
        }
        val target = when (val resolution = if (guard == null) resolve() else guard.commit(resolve)) {
            ContactLinkLookupResolution.Stale ->
                return if (retryingDelivery) DeepLinkRouteOutcome.ContactLookupFailed(ContactLookupContextStale())
                else commitOutcomeIfActive { DeepLinkRouteOutcome.ContactLookupRejected }
            is ContactLinkLookupResolution.Current -> resolution
        }
        val existing = if (captured.disposition == ContactLinkCaptureDisposition.Lookup &&
            link.publicKey != target.selfPublicKey
        ) {
            target.radioId?.let { radioId ->
                try {
                    environment.fetchContact(radioId, link.publicKey)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    coroutineContext.ensureActive()
                    val failed = DeepLinkRouteOutcome.ContactLookupFailed(failure)
                    val outcome = if (guard == null) failed else guard.commit { failed }
                    java.util.logging.Logger.getLogger("com.mc1.DeepLinks").warning(
                        "Contact lookup failed; delivery requires retry",
                    )
                    return outcome
                }
            }
        } else null
        return ContactLinkPublications().use { publications ->
            commitOutcomeIfActive {
                val completion = environment.completeResolvedContactLookup(
                    captured, target, link, existing, external, publications, cancellation,
                )
                val outcome = when (completion) {
                    ContactLinkResolvedCompletion.TargetChanged ->
                        return@commitOutcomeIfActive DeepLinkRouteOutcome.ContactLookupFailed(ContactLookupTargetChanged())
                    is ContactLinkResolvedCompletion.Completed -> completion.outcome
                }
                when (outcome) {
                    ContactLinkCompletion.Navigated -> DeepLinkRouteOutcome.Navigated
                    ContactLinkCompletion.ConfirmationStaged -> DeepLinkRouteOutcome.ConfirmationStaged
                    ContactLinkCompletion.IgnoredSelfContact -> DeepLinkRouteOutcome.IgnoredSelfContact
                    ContactLinkCompletion.WrongTarget -> DeepLinkRouteOutcome.ContactLookupRejected
                    ContactLinkCompletion.StaleContext ->
                        if (retryingDelivery) DeepLinkRouteOutcome.ContactLookupFailed(ContactLookupContextStale())
                        else DeepLinkRouteOutcome.ContactLookupRejected
                }
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
