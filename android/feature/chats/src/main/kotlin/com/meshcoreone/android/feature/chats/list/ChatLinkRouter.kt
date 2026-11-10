// PortedFrom: MC1/Views/Chats/Navigation/ChatLinkRouter.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.contracts.AppTab
import java.net.URI
import java.net.URISyntaxException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface ContactLinkLookupIssue {
    data class Failed(val cause: Exception) : ContactLinkLookupIssue
    data object ContextRetired : ContactLinkLookupIssue
}

data class ContactLinkLookupFailure(
    val deliveryId: UUID,
    val attempt: Long,
    val issue: ContactLinkLookupIssue,
)

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
    private class ContactDelivery(val link: ContactLinkResult, val context: ContactLinkContext) {
        val id: UUID = UUID.randomUUID()
    }

    private sealed interface ContactLookupState {
        data object Idle : ContactLookupState
        data class Running(val delivery: ContactDelivery, val attempt: Long) : ContactLookupState
        data class Failed(val delivery: ContactDelivery, val failure: ContactLinkLookupFailure) : ContactLookupState
    }

    private data class ContactLookupSnapshot(val revision: Long, val state: ContactLookupState)

    private val contactLookupLock = Any()
    private var contactLookup = ContactLookupSnapshot(0, ContactLookupState.Idle)
    private val publishedContactLookup = MutableStateFlow(contactLookup)
    val contactLookupFailure: Flow<ContactLinkLookupFailure?> = publishedContactLookup
        .map { (it.state as? ContactLookupState.Failed)?.failure }
        .distinctUntilChanged()

    fun retryContactLookup(failure: ContactLinkLookupFailure) {
        val retry = synchronized(contactLookupLock) {
            val current = contactLookup.state as? ContactLookupState.Failed ?: return
            if (current.failure != failure || failure.issue !is ContactLinkLookupIssue.Failed) return
            ContactLookupState.Running(current.delivery, Math.incrementExact(failure.attempt)).also(::setContactLookup)
        }
        publishContactLookup()
        launchLookup { resolveContactLink(retry) }
    }

    fun dismissContactLookupFailure(failure: ContactLinkLookupFailure) {
        synchronized(contactLookupLock) {
            val current = contactLookup.state as? ContactLookupState.Failed ?: return
            if (current.failure != failure) return
            setContactLookup(ContactLookupState.Idle)
        }
        publishContactLookup()
    }

    private fun setContactLookup(state: ContactLookupState) {
        contactLookup = ContactLookupSnapshot(Math.incrementExact(contactLookup.revision), state)
    }

    private fun publishContactLookup() {
        val latest = synchronized(contactLookupLock) { contactLookup }
        publishedContactLookup.update { previous ->
            if (latest.revision > previous.revision) latest else previous
        }
    }

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
        var attempt: ContactLookupState.Running? = null
        var capturedState: ContactLookupSnapshot? = null
        val context = try {
            ContactLinkPublications().use { publications ->
                synchronized(contactLookupLock) {
                    environment.captureContactLinkContext(link.publicKey, publications).also { captured ->
                        val delivery = ContactDelivery(link, captured)
                        attempt = if (captured.disposition == ContactLinkCaptureDisposition.Lookup)
                            ContactLookupState.Running(delivery, 0) else null
                        val state = when (captured.disposition) {
                            ContactLinkCaptureDisposition.Lookup -> checkNotNull(attempt)
                            ContactLinkCaptureDisposition.IgnoredSelfContact -> ContactLookupState.Idle
                            ContactLinkCaptureDisposition.StaleContext -> ContactLookupState.Failed(
                                delivery, ContactLinkLookupFailure(delivery.id, 0, ContactLinkLookupIssue.ContextRetired),
                            )
                        }
                        setContactLookup(state)
                        capturedState = contactLookup
                    }
                }
            }
        } catch (failure: Throwable) {
            val changed = synchronized(contactLookupLock) {
                if (contactLookup !== capturedState) false else {
                    setContactLookup(ContactLookupState.Idle)
                    true
                }
            }
            if (changed) publishContactLookup()
            throw failure
        }
        publishContactLookup()
        when (context.disposition) {
            ContactLinkCaptureDisposition.IgnoredSelfContact -> return
            ContactLinkCaptureDisposition.StaleContext -> {
                diagnostics.report("Contact-link initiating context is not current", null)
                return
            }
            ContactLinkCaptureDisposition.Lookup -> Unit
        }
        val active = checkNotNull(attempt)
        launchLookup { resolveContactLink(active) }
    }

    private suspend fun resolveContactLink(attempt: ContactLookupState.Running) {
        val link = attempt.delivery.link
        val context = attempt.delivery.context
        val job = checkNotNull(currentCoroutineContext()[Job])
        val (cancellation, observation) = ContactLinkCancellation.observe(job)
        try {
            var targetChanges = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val target = when (val resolution = environment.resolveContactLinkContext(context, link.publicKey)) {
                    ContactLinkTargetResolution.Stale -> {
                        diagnostics.report("Contact-link lookup context is no longer current", null)
                        publishContactFailure(attempt, ContactLinkLookupIssue.ContextRetired, cancellation)
                        return
                    }
                    is ContactLinkTargetResolution.Current -> resolution.target
                }
                val existing = if (target.selfPublicKey == link.publicKey) null else target.radioId?.let { radio ->
                    try {
                        environment.fetchContact(radio, link.publicKey)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        diagnostics.report("Contact-link lookup failed", failure)
                        currentCoroutineContext().ensureActive()
                        val issue = when (environment.resolveContactLinkContext(context, link.publicKey)) {
                            ContactLinkTargetResolution.Stale -> ContactLinkLookupIssue.ContextRetired
                            is ContactLinkTargetResolution.Current -> ContactLinkLookupIssue.Failed(failure)
                        }
                        publishContactFailure(attempt, issue, cancellation)
                        return
                    }
                }
                currentCoroutineContext().ensureActive()
                when (val completion = ContactLinkPublications().use { publications ->
                    environment.completeResolvedContactLookup(context, target, link, existing, publications, cancellation)
                }) {
                    ContactLinkTargetCompletion.TargetChanged -> {
                        if (targetChanges++ == 0) continue
                        diagnostics.report("Contact-link target changed repeatedly", null)
                        return
                    }
                    is ContactLinkTargetCompletion.Completed -> reportContactCompletion(completion.outcome)
                    is ContactLinkTargetCompletion.Offered -> when (val handoff = completion.receipt.awaitOutcome()) {
                        is ContactLinkHandoffOutcome.Accepted -> reportContactCompletion(handoff.outcome)
                        ContactLinkHandoffOutcome.Superseded ->
                            diagnostics.report("Contact-link delivery was superseded before navigation accepted it", null)
                    }
                }
                return
            }
        } finally {
            observation.dispose()
            val changed = synchronized(contactLookupLock) {
                if (contactLookup.state !== attempt) false else {
                    setContactLookup(ContactLookupState.Idle)
                    true
                }
            }
            if (changed) publishContactLookup()
        }
    }

    private fun publishContactFailure(
        attempt: ContactLookupState.Running, issue: ContactLinkLookupIssue, cancellation: ContactLinkCancellation,
    ) {
        val failure = ContactLinkLookupFailure(attempt.delivery.id, attempt.attempt, issue)
        val changed = synchronized(contactLookupLock) {
            cancellation.commitIfActive {
                if (contactLookup.state !== attempt) false else {
                    setContactLookup(ContactLookupState.Failed(attempt.delivery, failure))
                    true
                }
            }
        }
        if (changed) publishContactLookup()
    }

    private fun reportContactCompletion(outcome: ContactLinkCompletion) {
        when (outcome) {
            ContactLinkCompletion.StaleContext ->
                diagnostics.report("Contact-link lookup context is no longer current", null)
            ContactLinkCompletion.WrongTarget ->
                diagnostics.report("Contact-link lookup returned the wrong target", null)
            ContactLinkCompletion.Navigated, ContactLinkCompletion.ConfirmationStaged,
            ContactLinkCompletion.IgnoredSelfContact -> Unit
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
