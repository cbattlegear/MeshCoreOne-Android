// PortedFrom: MC1/Views/Chats/Components/Fragments/LinkPreviewFragmentView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/RichPreviewMetrics.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/TapToLoadPreview.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/Components/MalwareWarningCard.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.composer

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Resolved preview metadata. Image/icon are opaque references the app's image source understands. */
data class LinkPreviewData(
    val url: String,
    val title: String? = null,
    val imageRef: String? = null,
    val iconRef: String? = null,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

/** Mirror of core:services `LinkPreviewResult`. */
sealed interface LinkPreviewOutcome {
    data class Loaded(val data: LinkPreviewData) : LinkPreviewOutcome
    data object Loading : LinkPreviewOutcome
    data object NoPreviewAvailable : LinkPreviewOutcome
    data object Disabled : LinkPreviewOutcome
    data object Failed : LinkPreviewOutcome
}

/**
 * Feature-owned mirror of core:services `LinkPreviewCaching` (WP-218): the app binds it to the cache.
 * [preview] honors the user's auto-resolve preference ([LinkPreviewOutcome.Disabled] means tap to load);
 * [manualFetch] bypasses it.
 */
interface LinkPreviewPort {
    suspend fun cachedPreview(url: String): LinkPreviewData?
    suspend fun preview(url: String, isChannelMessage: Boolean): LinkPreviewOutcome
    suspend fun manualFetch(url: String): LinkPreviewOutcome
}

/** Mirror of `UrlSafetyChecker.isSafe` (SSRF guard: private/reserved/loopback hosts are unsafe). */
fun interface UrlSafetyPort { suspend fun isSafe(url: String): Boolean }

/** Mirror of `MalwareDomainFilter.isBlocked`. */
fun interface MalwareDomainPort { suspend fun isBlocked(host: String): Boolean }

sealed interface LinkPreviewCardState {
    data object Hidden : LinkPreviewCardState
    data class Loading(val url: String, val heroAspectHint: Double? = null) : LinkPreviewCardState
    data class TapToLoad(val url: String) : LinkPreviewCardState
    data class Malware(val url: String) : LinkPreviewCardState
    data class Loaded(val data: LinkPreviewData) : LinkPreviewCardState
}

/** Hero sizing of rich preview cards (iOS `RichPreviewMetrics`). */
object LinkPreviewMetrics {
    const val FALLBACK_ASPECT = 16.0 / 9.0
    const val MIN_HERO_HEIGHT_DP = 100
    const val MAX_HERO_HEIGHT_DP = 250
    const val CORNER_RADIUS_DP = 12

    fun heroAspect(imageWidth: Int?, imageHeight: Int?): Double =
        if (imageWidth != null && imageHeight != null && imageWidth > 0 && imageHeight > 0) {
            imageWidth.toDouble() / imageHeight
        } else {
            FALLBACK_ASPECT
        }
}

/** Only web links open from a card or body tap: no custom schemes, intents, `javascript:` or file URLs. */
object LinkOpenPolicy {
    private val webLink = Regex("""^https?://[^\s/?#]+""", RegexOption.IGNORE_CASE)
    fun isOpenable(url: String): Boolean = webLink.containsMatchIn(url)
    fun host(url: String): String? =
        Regex("""^https?://(?:[^/?#@]*@)?(\[[^\]]*\]|[^/?#:]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)
}

/**
 * One link-preview card's lifecycle. Cached content remains available offline after the malware
 * blocklist check; the SSRF guard runs before any new automatic or manual network fetch. A tap on
 * the disabled card fetches once; repeated taps while loading are ignored, and cancellation is
 * never converted into a failure.
 */
class LinkPreviewStateHolder(
    private val url: String,
    private val isChannelMessage: Boolean,
    private val port: LinkPreviewPort,
    private val safety: UrlSafetyPort,
    private val malware: MalwareDomainPort,
    private val scope: CoroutineScope,
    private val diagnostics: ComposerDiagnostics = ComposerDiagnostics { _, _ -> },
) {
    private val mutableState = MutableStateFlow<LinkPreviewCardState>(LinkPreviewCardState.Hidden)
    val state: StateFlow<LinkPreviewCardState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun start(): Job = launchOnce { resolve(manual = false) }

    /** Tap-to-load. Returns null when a load is already running or there is nothing to tap. */
    fun manualLoad(): Job? {
        val current = mutableState.value
        if (current !is LinkPreviewCardState.TapToLoad) return null
        return launchOnce { resolve(manual = true) }
    }

    private fun launchOnce(block: suspend () -> Unit): Job {
        job?.cancel()
        return scope.launch { block() }.also { job = it }
    }

    private suspend fun resolve(manual: Boolean) {
        try {
            val host = LinkOpenPolicy.host(url)
            if (host == null || !LinkOpenPolicy.isOpenable(url)) return hide()
            if (malware.isBlocked(host)) {
                mutableState.value = LinkPreviewCardState.Malware(url)
                return
            }
            val cached = if (manual) null else port.cachedPreview(url)
            if (cached != null) {
                mutableState.value = LinkPreviewCardState.Loaded(cached)
                return
            }
            if (!safety.isSafe(url)) return hide()
            mutableState.value = LinkPreviewCardState.Loading(url)
            val outcome = if (manual) port.manualFetch(url) else port.preview(url, isChannelMessage)
            mutableState.value = when (outcome) {
                is LinkPreviewOutcome.Loaded -> LinkPreviewCardState.Loaded(outcome.data)
                LinkPreviewOutcome.Loading -> LinkPreviewCardState.Loading(url)
                LinkPreviewOutcome.Disabled -> LinkPreviewCardState.TapToLoad(url)
                LinkPreviewOutcome.NoPreviewAvailable, LinkPreviewOutcome.Failed -> LinkPreviewCardState.Hidden
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            diagnostics.report("linkPreview", failure)
            hide()
        }
    }

    private fun hide() { mutableState.value = LinkPreviewCardState.Hidden }

    fun close() { job?.cancel() }
}
