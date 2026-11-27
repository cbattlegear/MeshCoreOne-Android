// AndroidOnly: WP-101 Prizm Mobile Observer runtime tests with fakes (no Android types, no network).
package com.meshcoreone.android.app.prizm

import com.meshcoreone.android.core.observer.claim.NodeDeviceInfo
import com.meshcoreone.android.core.observer.claim.NodeSigner
import com.meshcoreone.android.core.observer.claim.NodeType
import com.meshcoreone.android.core.observer.geo.PolygonGeofence
import com.meshcoreone.android.core.observer.geo.GeoPolygon
import com.meshcoreone.android.core.observer.geo.LatLon
import com.meshcoreone.android.core.observer.health.DeviceStatsReader
import com.meshcoreone.android.core.observer.prizm.PrizmOAuthConfig
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
    private val client = StandInPrizmClient()
    private val fence = PolygonGeofence(mapOf("nd" to listOf(GeoPolygon(listOf(LatLon(46.0, -98.0), LatLon(46.0, -97.0), LatLon(47.0, -97.0), LatLon(47.0, -98.0))))), 2_000.0)
    private lateinit var runtime: PrizmObserverRuntime

    private fun build(standIn: Boolean = true): PrizmObserverRuntime {
        val runner = ObserverRunner(scope) { link ->
            ObserverSession(link, SilentLocation(), publisher, runtime.sharing, runtime.zoneSource, fence, UploadClock { Instant.now() }, { 1_000L }, "s")
        }
        runtime = PrizmObserverRuntime(
            store, client, standIn, PrizmOAuthConfig("client", PrizmCallback.REDIRECT_URI), PrizmAuthLauncher { opened += it; true },
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

    @Test fun switchingUploadOnStartsTheServiceAndOffStopsPublishingAtOnceWithOfflineStatus() = runUnit {
        build(); runtime.signIn(); connect()
        runtime.setUploadEnabled(true)
        assertTrue(store.upload); assertEquals(1, service.starts); assertTrue(runtime.state.value.running)
        runtime.setUploadEnabled(false)
        assertFalse(runtime.state.value.uploadEnabled); assertFalse(runtime.state.value.running)
        assertEquals(1, service.stops)
        assertTrue(publisher.messages.none { it.topic.endsWith("/packets") })
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
        assertTrue(runtime.claim(PrizmNodeType.MOBILE, false) is PrizmClaimResult.Rejected)
        connect()
        assertTrue(runtime.claim(PrizmNodeType.MOBILE, false) is PrizmClaimResult.Rejected, "signed out")
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

    @Test fun deleteMyDataSaysUnavailableUntilPrizmHasAnEndpoint() = runUnit {
        build()
        assertEquals(com.meshcoreone.android.feature.settings.prizm.PrizmDeleteResult.Unavailable, runtime.deleteMyData())
    }
}
