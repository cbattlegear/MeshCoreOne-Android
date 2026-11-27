// AndroidOnly: WP-101 Prizm Mobile Observer runtime tests with fakes (no Android types, no network).
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.NodeSigner
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.observer.geo.PolygonGeofence
import com.meshcoreone.android.core.observer.geo.GeoPolygon
import com.meshcoreone.android.core.observer.geo.LatLon
import com.meshcoreone.android.core.observer.health.DeviceStatsReader
import com.meshcoreone.android.core.observer.prizm.PrizmException
import com.meshcoreone.android.core.observer.prizm.PrizmUnauthorizedException
import com.meshcoreone.android.core.observer.prizm.PrizmSession
import com.meshcoreone.android.core.observer.prizm.StandInPrizmClient
import com.meshcoreone.android.core.observer.session.LocationUpdates
import com.meshcoreone.android.core.observer.session.ObserverNodeLink
import com.meshcoreone.android.core.observer.session.ObserverRunner
import com.meshcoreone.android.core.observer.session.ObserverSession
import com.meshcoreone.android.core.observer.upload.MqttMessage
import com.meshcoreone.android.core.observer.upload.MqttPublisher
import com.meshcoreone.android.core.observer.upload.PublishResult
import com.meshcoreone.android.core.observer.upload.UploadClock
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimResult
import com.meshcoreone.android.feature.settings.prizm.PrizmCoordinate
import com.meshcoreone.android.feature.settings.prizm.PrizmNodeType
import com.meshcoreone.android.feature.settings.prizm.PrizmSignInResult
import com.meshcoreone.android.feature.settings.prizm.PrizmZone
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking

private fun runUnit(block: suspend CoroutineScope.() -> Unit) { runBlocking { block() } }

private class MemoryStore : PrizmObserverStore {
    var baseUrl: String? = null; var standInFlag = false
    override fun clearNodeType(nodeKeyHex: String) { types.remove(nodeKeyHex) }
    override fun serverBaseUrl() = baseUrl
    override fun setServerBaseUrl(url: String?) { baseUrl = url }
    override fun useStandIn() = standInFlag
    override fun setUseStandIn(enabled: Boolean) { standInFlag = enabled }
    var upload = false; var share = false; var zoneList = emptyList<PrizmZone>()
    val types = mutableMapOf<String, NodeType>(); var saved: PrizmSession? = null; var pendingAuth: PendingAuthorization? = null
    override fun uploadEnabled() = upload
    override fun setUploadEnabled(enabled: Boolean) { upload = enabled }
    override fun shareLocation() = share
    override fun setShareLocation(enabled: Boolean) { share = enabled }
    override fun zones() = zoneList
    override fun saveZones(zones: List<PrizmZone>) { zoneList = zones }
    override fun nodeType(nodeKeyHex: String) = types[nodeKeyHex]
    override fun setNodeType(nodeKeyHex: String, type: NodeType) { types[nodeKeyHex] = type }
    override fun session() = saved
    override fun saveSession(session: PrizmSession?) { saved = session }
    override fun pending() = pendingAuth
    override fun savePending(pending: PendingAuthorization?) { pendingAuth = pending }
    override fun clearAccount() { saved = null; pendingAuth = null }
}

private class FakeService(var accept: Boolean = true) : PrizmServiceControl {
    var starts = 0; var stops = 0
    override fun start(): Boolean { starts++; return accept }
    override fun stop() { stops++ }
}

private class NodeFake : ObserverNodeLink {
    override val nodeKey = Bytes(ByteArray(32) { (it + 7).toByte() })
    override val deviceInfo = NodeDeviceInfo(nodeKey, "Heltec 1", 20, 22, 910.5, 62.5, 7, 5, 9, "1.2", "b", "Heltec V3", false, 0)
    val feed = MutableSharedFlow<MeshEvent>(extraBufferCapacity = 8)
    override fun events(): Flow<MeshEvent> = feed
    override suspend fun fetchChannels(): List<ChannelInfo> = emptyList()
    override val signer: NodeSigner = NodeSigner { Bytes(ByteArray(64) { 1 }) }
    override fun statsReader(): DeviceStatsReader? = null
    override fun isConnected() = true
}

private class SilentLocation : LocationUpdates {
    var stopped = false
    override fun request(intervalMs: Long) = Unit
    override fun stop() { stopped = true }
}

private class RecordingPublisher : MqttPublisher {
    val messages = mutableListOf<MqttMessage>()
    override suspend fun publish(message: MqttMessage): PublishResult { messages += message; return PublishResult.Acked }
}

class PrizmObserverRuntimeTest {
    private val scope = CoroutineScope(Dispatchers.Unconfined + Job())
    private val store = MemoryStore()
    private val service = FakeService()
    private val publisher = RecordingPublisher()
    private val opened = mutableListOf<String>()
    private val signature = Bytes(ByteArray(64) { 1 })
    private val signer = NodeSigner { signature }
    private val standInClient = StandInPrizmClient()
    /** Behaves like the server (via the stand-in) but can be told to answer 401 or fail. */
    private var failWith: PrizmException? = null
    private val client = object : com.meshcoreone.android.core.observer.prizm.PrizmClient by standInClient {
        override suspend fun claimedNodes(session: PrizmSession) = failWith?.let { throw it } ?: standInClient.claimedNodes(session)
        override suspend fun removeNode(session: PrizmSession, nodeKey: Bytes) { failWith?.let { throw it }; standInClient.removeNode(session, nodeKey) }
        override suspend fun deleteMyData(session: PrizmSession) = failWith?.let { throw it } ?: com.meshcoreone.android.core.observer.prizm.DataDeleteResult(7, 1)
        override suspend fun claimStart(session: PrizmSession, nodeKey: Bytes) = failWith?.let { throw it } ?: standInClient.claimStart(session, nodeKey)
    }
    private val fence = PolygonGeofence(mapOf("nd" to listOf(GeoPolygon(listOf(LatLon(46.0, -98.0), LatLon(46.0, -97.0), LatLon(47.0, -97.0), LatLon(47.0, -98.0))))), 2_000.0)
    private lateinit var runtime: PrizmObserverRuntime

    private fun build(standIn: Boolean = true, debug: Boolean = true): PrizmObserverRuntime {
        store.standInFlag = standIn
        val runner = ObserverRunner(scope) { link ->
            ObserverSession(link, SilentLocation(), publisher, runtime.sharing, runtime.zoneSource, fence, UploadClock { Instant.now() }, { 1_000L })
        }
        runtime = PrizmObserverRuntime(
            store, client, standInClient, debug, PrizmAuthLauncher { opened += it; true },
            service, PrizmLocationProvider { PrizmCoordinate(46.9, -97.1) }, runner, scope,
        )
        return runtime
    }

    private fun connect(): PrizmConnectedNode = PrizmConnectedNode(NodeFake(), signer).also { runtime.onNode(it) }

    @Test fun standInSignInStoresASessionAndSignOutClearsItAndStopsTheService() = runUnit {
        build()
        assertEquals(PrizmSignInResult.SignedIn, runtime.signIn())
        assertNotNull(store.saved); assertEquals("Stand-in Observer", runtime.state.value.signedInAs)
        runtime.setUploadEnabled(true)
        runtime.signOut()
        assertNull(store.saved); assertFalse(store.upload); assertFalse(runtime.state.value.signedIn)
        assertEquals(1, service.stops)
    }

    @Test fun uploadCannotBeSwitchedOnWhileSignedOutOrWhenTheServiceIsRefused() = runUnit {
        build()
        runtime.setUploadEnabled(true)
        assertFalse(store.upload); assertEquals(0, service.starts)
        runtime.signIn(); service.accept = false
        runtime.setUploadEnabled(true)
        assertFalse(store.upload); assertFalse(runtime.state.value.uploadEnabled)
    }

    @Test fun switchingUploadOnStartsTheServiceAndOffStopsPublishingAtOnce() = runUnit {
        build(); runtime.signIn(); connect()
        runtime.setUploadEnabled(true)
        assertTrue(store.upload); assertEquals(1, service.starts); assertTrue(runtime.state.value.running)
        runtime.setUploadEnabled(false)
        assertFalse(runtime.state.value.uploadEnabled); assertFalse(runtime.state.value.running)
        assertEquals(1, service.stops)
        assertTrue(publisher.messages.none { it.topic.endsWith("/packets") })
    }

    @Test fun withSharingOffTheScreenSaysUploadsArePausedEvenBeforeANodeConnects() = runUnit {
        build(); runtime.signIn()
        runtime.setUploadEnabled(true)
        assertEquals("Upload paused: location is off", runtime.state.value.gateText)
        runtime.setShareLocation(true)
        assertNull(runtime.state.value.gateText)
        runtime.setShareLocation(false)
        connect()
        assertEquals("Upload paused: location is off", runtime.state.value.gateText)
        runtime.setUploadEnabled(false)
        assertNull(runtime.state.value.gateText)
    }

    @Test fun anUnclaimedNodePromptsOnceAndDismissHidesItForThatConnection() = runUnit {
        build(); runtime.signIn()
        val node = connect()
        assertTrue(runtime.state.value.claimPending); assertEquals("Heltec 1", runtime.state.value.node?.name)
        runtime.dismissClaimPrompt(); assertFalse(runtime.state.value.claimPending)
        runtime.onNode(null); runtime.onNode(PrizmConnectedNode(NodeFake(), signer))
        assertTrue(runtime.state.value.claimPending, "a new connection prompts again")
        assertNotNull(node)
    }

    @Test fun aSuccessfulClaimIsRememberedAndEndsThePrompt() = runUnit {
        build(); runtime.signIn()
        val node = connect()
        val result = runtime.claim(PrizmNodeType.VEHICLE, confirmTakeover = false)
        assertEquals(PrizmClaimResult.Linked, result)
        assertEquals(NodeType.VEHICLE, store.types[node.keyHex]); assertFalse(runtime.state.value.claimPending)
        runtime.onNode(null); runtime.onNode(PrizmConnectedNode(NodeFake(), signer))
        assertFalse(runtime.state.value.claimPending, "already claimed from this phone")
    }

    @Test fun claimWithoutANodeOrWithoutSignInIsRefusedWithoutTouchingPrizm() = runUnit {
        build()
        assertTrue(runtime.claim(PrizmNodeType.PORTABLE, false) is PrizmClaimResult.Rejected)
        connect()
        assertTrue(runtime.claim(PrizmNodeType.PORTABLE, false) is PrizmClaimResult.Rejected, "signed out")
    }

    @Test fun zonesAndSharingPersistAndFeedTheRedactorSources() {
        build()
        runtime.addZone(46.9, -97.1, 300.0); runtime.setShareLocation(true)
        assertEquals(1, store.zoneList.size); assertTrue(store.share)
        assertTrue(runtime.sharing.isEnabled())
        val zone = runtime.zoneSource.zones().single()
        assertEquals(300.0, zone.radiusMeters)
        runtime.removeZone(store.zoneList.single().id)
        assertTrue(runtime.zoneSource.zones().isEmpty() && store.zoneList.isEmpty())
    }

    @Test fun theBrowserCallbackChecksStateAndCompletesSignIn() = runUnit {
        build(standIn = false)
        assertEquals(PrizmSignInResult.BrowserOpened, runtime.signIn())
        val url = opened.single()
        val state = Regex("state=([^&]+)").find(url)!!.groupValues[1]
        assertNotNull(store.pendingAuth, "pending authorization survives a process kill")
        assertFalse(runtime.handleCallback("https://evil.example/app/callback?code=x&state=$state"))
        assertTrue(runtime.handleCallback("${PrizmCallback.REDIRECT_URI}?code=abc&state=forged"))
        assertNull(store.saved, "a forged state signs nobody in")
        assertNotNull(runtime.state.value.authError)
        runtime.signIn()
        val good = Regex("state=([^&]+)").findAll(opened.last()).first().groupValues[1]
        assertTrue(runtime.handleCallback("${PrizmCallback.REDIRECT_URI}?code=abc&state=$good"))
        assertNotNull(store.saved); assertNull(store.pendingAuth); assertNull(runtime.state.value.authError)
    }

    @Test fun cameBackToTheForegroundWithUploadOnRestartsTheService() = runUnit {
        build(); runtime.signIn(); runtime.setUploadEnabled(true)
        val before = service.starts
        runtime.onForeground()
        assertEquals(before + 1, service.starts)
        runtime.setUploadEnabled(false); val after = service.starts
        runtime.onForeground(); assertEquals(after, service.starts)
    }

    private fun signedInWithRealClient(): PrizmConnectedNode {
        build(); runBlocking { runtime.signIn() }
        store.standInFlag = false // from here on the runtime talks to the scripted "server" client
        return connect()
    }

    @Test fun deleteMyDataAsksPrizmAndReportsQueuedOrAUserMessage() = runUnit {
        signedInWithRealClient()
        assertEquals(com.meshcoreone.android.feature.settings.prizm.PrizmDeleteResult.Deleted, runtime.deleteMyData())
        failWith = PrizmException("Prizm could not be reached. Check your connection and try again.")
        assertEquals(
            com.meshcoreone.android.feature.settings.prizm.PrizmDeleteResult.Failed("Prizm could not be reached. Check your connection and try again."),
            runtime.deleteMyData(),
        )
    }

    @Test fun stopClaimingCallsPrizmForgetsTheNodeTypeAndEndsTheClaimedState() = runUnit {
        val node = signedInWithRealClient()
        assertEquals(PrizmClaimResult.Linked, runtime.claim(PrizmNodeType.VEHICLE, false))
        assertEquals(NodeType.VEHICLE, store.types[node.keyHex])
        assertTrue(runtime.state.value.nodeClaimed)
        assertEquals(com.meshcoreone.android.feature.settings.prizm.PrizmRemoveResult.Removed, runtime.removeNodeClaim())
        assertFalse(runtime.state.value.nodeClaimed); assertNull(store.types[node.keyHex])
        failWith = PrizmException("Prizm does not have this node claimed by your account.")
        assertTrue(runtime.removeNodeClaim() is com.meshcoreone.android.feature.settings.prizm.PrizmRemoveResult.Failed)
    }

    @Test fun a401MeansNoRefreshJustSignedOutWithAMessageAndUploadsOff() = runUnit {
        signedInWithRealClient()
        runtime.setUploadEnabled(true)
        failWith = PrizmUnauthorizedException()
        assertTrue(runtime.deleteMyData() is com.meshcoreone.android.feature.settings.prizm.PrizmDeleteResult.Failed)
        assertFalse(runtime.state.value.signedIn); assertFalse(store.upload); assertNull(store.saved)
        assertEquals("Your Prizm sign-in has expired. Sign in again.", runtime.state.value.authError)
    }

    @Test fun anExpiredTokenAtStartupIsSignedOutBecauseThereIsNoRefresh() = runUnit {
        val expired = PrizmSession("pza_old", java.time.Instant.parse("2020-01-01T00:00:00Z"), com.meshcoreone.android.core.observer.prizm.PrizmUser("x"))
        store.saved = expired
        build()
        assertFalse(runtime.state.value.signedIn, "an expired token at startup is signed out, there is no refresh")
    }

    @Test fun theNodesClaimedStateFollowsPrizmWhenItConnects() = runUnit {
        build(); runtime.signIn()
        val node = connect()
        assertFalse(runtime.state.value.nodeClaimed)
        runtime.claim(PrizmNodeType.VEHICLE, false)
        runtime.onNode(null); runtime.onNode(PrizmConnectedNode(NodeFake(), signer))
        assertTrue(runtime.state.value.nodeClaimed, "the stand-in server remembers the claim")
        assertNotNull(node)
    }

    @Test fun debugServerUrlIsValidatedStoredAndSignsOutBecauseTokensBelongToOneServer() = runUnit {
        build(); runtime.signIn()
        assertEquals("https://mqtt.nodakmesh.org", runtime.baseUrl)
        assertEquals(com.meshcoreone.android.feature.settings.prizm.PrizmBaseUrlResult.Invalid, runtime.setServerBaseUrl("ftp://x"))
        assertTrue(runtime.state.value.signedIn)
        assertEquals(com.meshcoreone.android.feature.settings.prizm.PrizmBaseUrlResult.Applied("https://beta.example.org"), runtime.setServerBaseUrl(" https://beta.example.org/ "))
        assertEquals("https://beta.example.org", store.baseUrl); assertEquals("https://beta.example.org", runtime.baseUrl)
        assertTrue(runtime.state.value.debug!!.overridden)
        assertFalse(runtime.state.value.signedIn)
        assertEquals(com.meshcoreone.android.feature.settings.prizm.PrizmBaseUrlResult.Reset, runtime.setServerBaseUrl(""))
        assertNull(store.baseUrl)
    }

    @Test fun standInSwitchSignsOutAndReleaseBuildsIgnoreDebugOptions() = runUnit {
        build(); runtime.signIn()
        runtime.setUseStandIn(false)
        assertFalse(runtime.state.value.signedIn); assertFalse(store.standInFlag)
        build(standIn = true, debug = false)
        store.baseUrl = "https://beta.example.org"
        assertEquals("https://mqtt.nodakmesh.org", runtime.baseUrl, "release builds ignore a stored override")
        assertNull(runtime.state.value.debug)
        assertEquals(com.meshcoreone.android.feature.settings.prizm.PrizmBaseUrlResult.Invalid, runtime.setServerBaseUrl("https://x.org"))
        assertFalse(runtime.state.value.standIn, "a stored stand-in flag is ignored in release builds")
    }
}
