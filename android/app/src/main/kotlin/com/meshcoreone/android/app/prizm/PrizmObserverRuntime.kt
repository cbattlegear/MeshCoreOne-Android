// AndroidOnly: WP-101 Prizm Mobile Observer process-level controller behind the settings port (plain Kotlin, no Android types).
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.ClaimClock
import com.meshcoreone.android.core.observer.claim.ClaimService
import com.meshcoreone.android.core.observer.location.LocationSharing
import com.meshcoreone.android.core.observer.location.PrivacyZone
import com.meshcoreone.android.core.observer.location.PrivacyZoneSource
import com.meshcoreone.android.core.observer.prizm.AuthorizationRequest
import com.meshcoreone.android.core.observer.prizm.PrizmClient
import com.meshcoreone.android.core.observer.prizm.PrizmException
import com.meshcoreone.android.core.observer.prizm.PrizmOAuthConfig
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.core.observer.session.ObserverMode
import com.meshcoreone.android.core.observer.session.ObserverRunner
import com.meshcoreone.android.core.observer.session.ObserverStatus
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimResult
import com.meshcoreone.android.feature.settings.prizm.PrizmCoordinate
import com.meshcoreone.android.feature.settings.prizm.PrizmCounters
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
 * [ObserverRunner] decides). Switching off ends publishing at once and sends the offline status, then stops the service.
 */
class PrizmObserverRuntime(
    private val store: PrizmObserverStore,
    private val client: PrizmClient,
    private val standIn: Boolean,
    private val oauth: PrizmOAuthConfig,
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
    private var session: PrizmSession? = store.session()
    private var zones: List<PrizmZone> = store.zones()
    private var uploadEnabled = store.uploadEnabled()
    private var shareLocation = store.shareLocation()
    private var node: PrizmConnectedNode? = null
    private var claimedKeys: Set<String> = emptySet()
    private var dismissedFor: PrizmConnectedNode? = null
    private var authError: String? = null
    private var pending: AuthorizationRequest? = null
    private val stateFlow = MutableStateFlow(buildState())
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
    }

    /** App came to the foreground: bring the service back if uploads are on (a background start is not allowed). */
    fun onForeground() { if (synchronized(lock) { uploadEnabled && session != null }) { service.start(); reevaluate() } }

    fun setScreenOn(on: Boolean) = runner.setScreenOn(on)

    fun onFix(fix: com.meshcoreone.android.core.observer.location.LocationFix) = runner.onFix(fix)

    // --- PrizmObserverPort ---

    override suspend fun signIn(): PrizmSignInResult {
        if (standIn) {
            return try {
                accept(client.exchangeCode("stand-in-code", "stand-in-verifier", PrizmCallback.REDIRECT_URI))
                PrizmSignInResult.SignedIn
            } catch (cancelled: CancellationException) { throw cancelled } catch (e: PrizmException) {
                PrizmSignInResult.Failed(e.message ?: "sign-in failed")
            }
        }
        val request = AuthorizationRequest.create(oauth)
        store.savePending(PendingAuthorization(request.url, request.pkce.codeVerifier, request.state))
        synchronized(lock) { pending = request }
        return if (auth.open(request.url)) PrizmSignInResult.BrowserOpened else PrizmSignInResult.Failed("No browser available")
    }

    /** The App Link came back. Returns true when the intent was ours and has been consumed (successfully or not). */
    fun handleCallback(uri: String): Boolean {
        if (!PrizmCallback.isCallback(uri)) return false
        scope.launch { completeSignIn(uri) }
        return true
    }

    private suspend fun completeSignIn(uri: String) {
        try {
            val request = synchronized(lock) { pending } ?: store.pending()?.let { AuthorizationRequest.restore(it.url, it.codeVerifier, it.state) }
                ?: throw PrizmException("No sign-in is waiting for this response")
            val code = request.codeFromCallback(PrizmCallback.parameters(uri))
            accept(client.exchangeCode(code, request.pkce.codeVerifier, PrizmCallback.REDIRECT_URI))
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
        try { old?.let { client.revoke(it) } } catch (cancelled: CancellationException) { throw cancelled } catch (e: Exception) {
            log("revoke failed, signed out locally: ${e.message}")
        }
        store.clearAccount()
        publish()
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
        val user = freshSession() ?: return PrizmClaimResult.Rejected("Not signed in")
        val service = ClaimService(client, connected.signer, ClaimClock { clock.instant() })
        val result = try {
            PrizmClaimMapping.result(
                service.claim(user, connected.link.nodeKey, PrizmClaimMapping.nodeType(type), connected.link.deviceInfo, confirmTakeover),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
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

    /** No Prizm deletion endpoint exists yet; this says so instead of pretending. */
    override suspend fun deleteMyData(): PrizmDeleteResult = PrizmDeleteResult.Unavailable

    // --- internals ---

    private suspend fun freshSession(): PrizmSession? {
        val current = synchronized(lock) { session } ?: return null
        if (!current.needsRefresh(clock.instant())) return current
        return try {
            client.refresh(current).also { accept(it) }
        } catch (cancelled: CancellationException) { throw cancelled } catch (e: PrizmException) { log("refresh failed: ${e.message}"); current }
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
            counters = PrizmCounters(current.counters.heard, current.counters.uploaded, current.counters.metadataOnly, current.counters.queued),
            gateText = current.gateText,
            running = current.mode == ObserverMode.UPLOADING,
            standIn = standIn,
            authError = authError,
        )
    }
}
