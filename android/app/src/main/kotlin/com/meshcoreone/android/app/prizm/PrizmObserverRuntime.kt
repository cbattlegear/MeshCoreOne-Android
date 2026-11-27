// AndroidOnly: WP-101 Prizm Mobile Observer process-level controller behind the settings port (plain Kotlin, no Android types).
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.ClaimClock
import com.meshcoreone.android.core.observer.claim.ClaimService
import com.meshcoreone.android.core.observer.geo.UploadGate
import com.meshcoreone.android.core.observer.location.LocationSharing
import com.meshcoreone.android.core.observer.location.PrivacyZone
import com.meshcoreone.android.core.observer.location.PrivacyZoneSource
import com.meshcoreone.android.core.observer.prizm.AuthorizationRequest
import com.meshcoreone.android.core.observer.prizm.PrizmClient
import com.meshcoreone.android.core.observer.prizm.PrizmException
import com.meshcoreone.android.core.observer.prizm.PrizmBaseUrl
import com.meshcoreone.android.core.observer.prizm.PrizmEndpoints
import com.meshcoreone.android.core.observer.prizm.PrizmUnauthorizedException
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.core.observer.session.ObserverMode
import com.meshcoreone.android.core.observer.session.ObserverRunner
import com.meshcoreone.android.core.observer.session.ObserverStatus
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimResult
import com.meshcoreone.android.feature.settings.prizm.PrizmCoordinate
import com.meshcoreone.android.feature.settings.prizm.PrizmBaseUrlResult
import com.meshcoreone.android.feature.settings.prizm.PrizmCounters
import com.meshcoreone.android.feature.settings.prizm.PrizmDebugState
import com.meshcoreone.android.feature.settings.prizm.PrizmRemoveResult
import com.meshcoreone.android.feature.settings.prizm.PrizmDeleteResult
import com.meshcoreone.android.feature.settings.prizm.PrizmNode
import com.meshcoreone.android.feature.settings.prizm.PrizmNodeType
import com.meshcoreone.android.feature.settings.prizm.PrizmObserverPort
import com.meshcoreone.android.feature.settings.prizm.PrizmObserverState
import com.meshcoreone.android.feature.settings.prizm.PrizmSignInResult
import com.meshcoreone.android.feature.settings.prizm.PrizmZone
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The one owner of Prizm Observer state in the process. The settings screens talk to it through [PrizmObserverPort]; the
 * foreground service reads [status] and calls [setScreenOn]; the app glue reports the connected node with [onNode].
 *
 * Starting and stopping: uploads run while the switch is on, the user is signed in and a node is connected (the
 * [ObserverRunner] decides). Switching off ends publishing at once (there is no offline status any more), then stops the service.
 */
class PrizmObserverRuntime(
    private val store: PrizmObserverStore,
    /** The real client (HTTP); [standInClient] is used only while the debug "offline stand-ins" switch is on. */
    private val realClient: PrizmClient,
    private val standInClient: PrizmClient,
    /** True in debuggable builds: the debug server options are offered and stored values apply. Release builds ignore them. */
    private val debugOptions: Boolean,
    private val auth: PrizmAuthLauncher,
    private val service: PrizmServiceControl,
    private val location: PrizmLocationProvider,
    private val runner: ObserverRunner,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.systemUTC(),
    private val log: (String) -> Unit = {},
) : PrizmObserverPort {
    private val lock = Any()
    private val evaluation = Mutex()
    private var session: PrizmSession? = store.session()?.takeIf { !it.isExpired(clock.instant()) }
    private var zones: List<PrizmZone> = store.zones()
    private var uploadEnabled = store.uploadEnabled()
    private var shareLocation = store.shareLocation()
    private var node: PrizmConnectedNode? = null
    private var claimedKeys: Set<String> = emptySet()
    private var dismissedFor: PrizmConnectedNode? = null
    private var authError: String? = null
    private var pending: AuthorizationRequest? = null
    private val stateFlow = MutableStateFlow(buildState())

    private val standIn: Boolean get() = debugOptions && store.useStandIn()
    private val client: PrizmClient get() = if (standIn) standInClient else realClient

    /** The server base URL in effect: the production default, or the stored beta override in a debug build. */
    val baseUrl: String get() = (if (debugOptions) store.serverBaseUrl() else null) ?: PrizmEndpoints.DEFAULT_BASE_URL
    override val state: StateFlow<PrizmObserverState> = stateFlow.asStateFlow()

    /** Read per record by the redactor, so a switch or zone edit applies to the very next packet. */
    val sharing = LocationSharing { synchronized(lock) { shareLocation } }
    val zoneSource = PrivacyZoneSource { synchronized(lock) { zones.map { PrivacyZone(it.latitude, it.longitude, it.radiusMeters) } } }
    val status: StateFlow<ObserverStatus> get() = runner.status

    init { scope.launch { runner.status.collect { publish() } } }

    /** The app reports the connected node (null when disconnected). Same instance means no change. */
    fun onNode(connected: PrizmConnectedNode?) {
        synchronized(lock) {
            if (node === connected) return
            node = connected
            claimedKeys = connected?.let { c -> if (store.nodeType(c.keyHex) != null) setOf(c.keyHex) else emptySet() }.orEmpty()
        }
        publish(); reevaluate()
        if (connected != null) scope.launch { refreshClaimed(connected) }
    }

    /** Asks Prizm which nodes this user has claimed (a claim made elsewhere, or removed elsewhere, shows up here). Failures keep what is known. */
    private suspend fun refreshClaimed(connected: PrizmConnectedNode) {
        val user = synchronized(lock) { session } ?: return
        val claimed = try {
            client.claimedNodes(user)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: PrizmUnauthorizedException) {
            expireSession(e.message); return
        } catch (e: PrizmException) {
            log("could not read claimed nodes: ${e.message}"); return
        }
        synchronized(lock) {
            if (node !== connected) return
            claimedKeys = if (connected.keyHex in claimed) claimedKeys + connected.keyHex else claimedKeys - connected.keyHex
        }
        publish()
    }

    /** App came to the foreground: bring the service back if uploads are on (a background start is not allowed). */
    fun onForeground() { if (synchronized(lock) { uploadEnabled && session != null }) { service.start(); reevaluate() } }

    fun setScreenOn(on: Boolean) = runner.setScreenOn(on)

    fun onFix(fix: com.meshcoreone.android.core.observer.location.LocationFix) = runner.onFix(fix)

    // --- PrizmObserverPort ---

    override suspend fun signIn(): PrizmSignInResult {
        if (standIn) {
            return try {
                accept(client.exchangeCode("stand-in-code", "stand-in-verifier"))
                PrizmSignInResult.SignedIn
            } catch (cancelled: CancellationException) { throw cancelled } catch (e: PrizmException) {
                PrizmSignInResult.Failed(e.message ?: "sign-in failed")
            }
        }
        val request = AuthorizationRequest.create(baseUrl)
        store.savePending(PendingAuthorization(request.url, request.pkce.codeVerifier, request.state))
        synchronized(lock) { pending = request }
        return if (auth.open(request.url)) PrizmSignInResult.BrowserOpened else PrizmSignInResult.Failed("No browser available")
    }

    /** The App Link came back. Returns true when the intent was ours and has been consumed (successfully or not). */
    fun handleCallback(uri: String): Boolean {
        if (!PrizmCallback.isCallback(uri, PrizmBaseUrl.hostOf(baseUrl) ?: return false)) return false
        scope.launch { completeSignIn(uri) }
        return true
    }

    private suspend fun completeSignIn(uri: String) {
        try {
            val request = synchronized(lock) { pending } ?: store.pending()?.let { AuthorizationRequest.restore(it.url, it.codeVerifier, it.state) }
                ?: throw PrizmException("No sign-in is waiting for this response")
            val code = request.codeFromCallback(PrizmCallback.parameters(uri))
            accept(client.exchangeCode(code, request.pkce.codeVerifier))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: PrizmException) {
            fail(e.message ?: "sign-in failed")
        } finally {
            store.savePending(null); synchronized(lock) { pending = null }
        }
    }

    private fun accept(fresh: PrizmSession) {
        store.saveSession(fresh)
        synchronized(lock) { session = fresh; authError = null }
        publish()
    }

    private fun fail(message: String) { synchronized(lock) { authError = message }; publish() }

    override fun clearAuthError() { synchronized(lock) { authError = null }; publish() }

    override suspend fun signOut() {
        val old = synchronized(lock) { session }
        synchronized(lock) { uploadEnabled = false; session = null }
        store.setUploadEnabled(false)
        reevaluateNow()
        service.stop()
        try { old?.let { client.revoke(it) } } catch (cancelled: CancellationException) { throw cancelled } catch (e: PrizmException) {
            log("revoke failed, signed out locally: ${e.message}")
        }
        store.clearAccount()
        publish()
    }

    /** The server said 401 (revoked or expired): there is no refresh, so sign out locally and ask for a new sign-in. */
    private suspend fun expireSession(message: String?) {
        synchronized(lock) { uploadEnabled = false; session = null; authError = message ?: "Your Prizm sign-in has expired. Sign in again." }
        store.setUploadEnabled(false); store.clearAccount()
        reevaluateNow(); service.stop(); publish()
    }

    override fun setUploadEnabled(enabled: Boolean) {
        if (enabled && synchronized(lock) { session } == null) return
        if (enabled && !service.start()) { log("foreground service refused to start"); return }
        synchronized(lock) { uploadEnabled = enabled }
        store.setUploadEnabled(enabled)
        publish()
        scope.launch { reevaluateNow(); if (!enabled) service.stop() }
    }

    override fun setShareLocation(enabled: Boolean) {
        synchronized(lock) { shareLocation = enabled }
        store.setShareLocation(enabled); publish()
    }

    override fun addZone(latitude: Double, longitude: Double, radiusMeters: Double) {
        val zone = PrizmZone(UUID.randomUUID().toString(), latitude, longitude, radiusMeters)
        val next = synchronized(lock) { (zones + zone).also { zones = it } }
        store.saveZones(next); publish()
    }

    override fun removeZone(id: String) {
        val next = synchronized(lock) { zones.filter { it.id != id }.also { zones = it } }
        store.saveZones(next); publish()
    }

    override suspend fun currentLocation(): PrizmCoordinate? = location.currentLocation()

    override suspend fun claim(type: PrizmNodeType, confirmTakeover: Boolean): PrizmClaimResult {
        val connected = synchronized(lock) { node } ?: return PrizmClaimResult.Rejected("No node is connected")
        val user = currentSession() ?: return PrizmClaimResult.Rejected("Not signed in")
        val service = ClaimService(client, connected.signer, ClaimClock { clock.instant() })
        val result = try {
            PrizmClaimMapping.result(
                service.claim(user, connected.link.nodeKey, PrizmClaimMapping.nodeType(type), connected.link.deviceInfo, confirmTakeover),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: PrizmUnauthorizedException) {
            expireSession(e.message)
            PrizmClaimResult.Rejected(e.message ?: "Not signed in")
        } catch (e: PrizmException) {
            PrizmClaimResult.NetworkFailed(e.message ?: "network error")
        }
        if (PrizmClaimMapping.isClaimed(result)) {
            store.setNodeType(connected.keyHex, PrizmClaimMapping.nodeType(type))
            synchronized(lock) { if (node === connected) claimedKeys = claimedKeys + connected.keyHex }
            publish()
        }
        return result
    }

    override fun dismissClaimPrompt() { synchronized(lock) { dismissedFor = node }; publish() }

    override suspend fun deleteMyData(): PrizmDeleteResult {
        val user = currentSession() ?: return PrizmDeleteResult.Failed("Not signed in")
        return try {
            client.deleteMyData(user)
            PrizmDeleteResult.Deleted
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: PrizmUnauthorizedException) {
            expireSession(e.message)
            PrizmDeleteResult.Failed(e.message ?: "Not signed in")
        } catch (e: PrizmException) {
            PrizmDeleteResult.Failed(e.message ?: "network error")
        }
    }

    override suspend fun removeNodeClaim(): PrizmRemoveResult {
        val connected = synchronized(lock) { node } ?: return PrizmRemoveResult.Failed("No node is connected")
        val user = currentSession() ?: return PrizmRemoveResult.Failed("Not signed in")
        return try {
            client.removeNode(user, connected.link.nodeKey)
            store.clearNodeType(connected.keyHex)
            synchronized(lock) { claimedKeys = claimedKeys - connected.keyHex }
            publish()
            PrizmRemoveResult.Removed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: PrizmUnauthorizedException) {
            expireSession(e.message)
            PrizmRemoveResult.Failed(e.message ?: "Not signed in")
        } catch (e: PrizmException) {
            PrizmRemoveResult.Failed(e.message ?: "network error")
        }
    }

    override fun setServerBaseUrl(text: String): PrizmBaseUrlResult {
        if (!debugOptions) return PrizmBaseUrlResult.Invalid
        val result: PrizmBaseUrlResult = if (text.isBlank()) PrizmBaseUrlResult.Reset else PrizmBaseUrl.parse(text)?.let { PrizmBaseUrlResult.Applied(it) } ?: return PrizmBaseUrlResult.Invalid
        val next = (result as? PrizmBaseUrlResult.Applied)?.url?.takeIf { it != PrizmEndpoints.DEFAULT_BASE_URL }
        if (next != store.serverBaseUrl()) { store.setServerBaseUrl(next); signOutLocally() }
        return result
    }

    override fun setUseStandIn(enabled: Boolean) {
        if (!debugOptions || enabled == store.useStandIn()) return
        store.setUseStandIn(enabled); signOutLocally()
    }

    /** Tokens belong to one server (or to the stand-in): a changed server or client drops the session without calling anyone. */
    private fun signOutLocally() {
        scope.launch {
            synchronized(lock) { uploadEnabled = false; session = null; authError = null; claimedKeys = emptySet() }
            store.setUploadEnabled(false); store.clearAccount()
            reevaluateNow(); service.stop(); publish()
        }
        publish()
    }

    // --- internals ---

    /** The session if it is still good; an expired one (180 days, no refresh) signs the user out and asks for a new sign-in. */
    private suspend fun currentSession(): PrizmSession? {
        val current = synchronized(lock) { session } ?: return null
        if (!current.isExpired(clock.instant())) return current
        expireSession(null)
        return null
    }

    private fun reevaluate() { scope.launch { reevaluateNow() } }

    private suspend fun reevaluateNow() = evaluation.withLock {
        val (enabled, link) = synchronized(lock) { (uploadEnabled && session != null) to node?.link }
        runner.update(enabled, link)
    }

    private fun publish() { stateFlow.value = buildState() }

    private fun buildState(): PrizmObserverState = synchronized(lock) {
        val current = runner.status.value
        val connected = node
        PrizmObserverState(
            signedInAs = session?.user?.displayName,
            uploadEnabled = uploadEnabled,
            shareLocation = shareLocation,
            zones = zones,
            node = connected?.let { PrizmNode(it.name, it.model) },
            claimPending = session != null && connected != null && connected.keyHex !in claimedKeys && dismissedFor !== connected,
            counters = PrizmCounters(
                current.counters.heard, current.counters.uploaded, current.counters.metadataOnly, current.counters.queued, current.counters.withheld,
            ),
            // Before a session runs (no node yet) the switch alone already means "paused": say so on the screen.
            gateText = current.gateText ?: if (uploadEnabled && !shareLocation && current.mode != ObserverMode.UPLOADING) UploadGate.NOTIFICATION_LOCATION_OFF else null,
            running = current.mode == ObserverMode.UPLOADING,
            standIn = standIn,
            nodeClaimed = connected != null && connected.keyHex in claimedKeys,
            debug = if (debugOptions) PrizmDebugState(baseUrl, store.serverBaseUrl() != null, store.useStandIn()) else null,
            authError = authError,
        )
    }
}
