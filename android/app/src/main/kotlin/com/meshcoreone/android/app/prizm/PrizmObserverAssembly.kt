// AndroidOnly: WP-101 Prizm Mobile Observer wiring: builds the runtime, its stand-ins and the node tracker for the process.
package com.meshcoreone.android.app.prizm

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.core.observer.geo.NdMnGeofence
import com.meshcoreone.android.core.observer.mqtt.BrokerDirectory
import com.meshcoreone.android.core.observer.mqtt.WebSocketMqttPublisher
import com.meshcoreone.android.core.observer.prizm.HttpPrizmClient
import com.meshcoreone.android.core.observer.prizm.StandInPrizmClient
import com.meshcoreone.android.core.observer.session.ObserverRunner
import com.meshcoreone.android.core.observer.session.ObserverSession
import com.meshcoreone.android.core.observer.upload.InMemoryUploadQueueRepository
import com.meshcoreone.android.core.observer.upload.MqttPublisher
import com.meshcoreone.android.core.observer.upload.UploadClock
import com.meshcoreone.android.core.observer.upload.UploadQueueRepository
import com.meshcoreone.android.feature.settings.prizm.PrizmClaimStateHolder
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Process-level pieces the UI needs besides the port: the one claim-flow holder shared by Settings and the global prompt. */
class PrizmObserverHandle(val runtime: PrizmObserverRuntime, val claim: PrizmClaimStateHolder)

object PrizmObserverAssembly {
    private const val TAG = "PrizmObserver"

    /**
     * Real by default: [HttpPrizmClient] and [WebSocketMqttPublisher] over OkHttp (see docs/android/deviations/PRIZM-OBSERVER.md;
     * none of it has run against a real server yet). The offline [StandInPrizmClient] and [StandInMqttPublisher] are selectable only
     * in debuggable builds. There is deliberately no automatic fallback to the stand-in uploader when the server is unreachable: it
     * acknowledges everything, so records would be marked sent and dropped from the queue. The real publisher keeps them queued and
     * retries with backoff instead.
     */
    fun create(app: Application, container: AppContainer, scope: CoroutineScope): PrizmObserverHandle {
        val log: (String) -> Unit = { message -> Log.i(TAG, message) }
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val store = SharedPreferencesPrizmObserverStore(app, KeystoreSecretBox())
        val geofence by lazy { NdMnGeofence.load() }
        // One queue for the process: rows survive a node reconnect (not a process death, see the Room TODO).
        val queue: UploadQueueRepository = InMemoryUploadQueueRepository()
        lateinit var runtime: PrizmObserverRuntime
        val http = OkHttpPrizmTransport()
        val baseUrl = { runtime.baseUrl }
        val directory = BrokerDirectory(baseUrl, http)
        val mqtt = OkHttpMqttTransport()
        val location = FrameworkLocationUpdates(app, { fix -> runtime.onFix(fix) }, log)
        val runner = ObserverRunner(scope) { link ->
            val publisher: MqttPublisher =
                if (debuggable && store.useStandIn()) StandInMqttPublisher(log)
                else WebSocketMqttPublisher(mqtt, directory, clientId(link.nodeKey.hexString), log = log)
            ObserverSession(
                link, location, publisher, runtime.sharing, runtime.zoneSource, geofence,
                UploadClock { Instant.now() }, SystemClock::elapsedRealtime, queue = queue,
                brokerAudience = directory::audience, log = log,
            )
        }
        runtime = PrizmObserverRuntime(
            store = store, realClient = HttpPrizmClient(baseUrl, http), standInClient = StandInPrizmClient(), debugOptions = debuggable,
            auth = PrizmAuthLauncher { url -> launchBrowser(app, url) },
            service = AndroidPrizmServiceControl(app, log),
            location = FrameworkLocationProvider(app),
            runner = runner, scope = scope, log = log,
        )
        trackNode(container, runtime, scope)
        return PrizmObserverHandle(runtime, PrizmClaimStateHolder(runtime, scope))
    }

    /** MQTT client ids must be unique per connection: the node key prefix plus a random suffix. */
    internal fun clientId(nodeKeyHex: String): String = "mc1-${nodeKeyHex.take(12)}-${UUID.randomUUID().toString().take(8)}"

    /** Reports the connected node (or null) to the runtime whenever the connection or its session changes. */
    private fun trackNode(container: AppContainer, runtime: PrizmObserverRuntime, scope: CoroutineScope) {
        var cachedGraph: Any? = null
        var cachedNode: PrizmConnectedNode? = null
        scope.launch {
            combine(container.connectionManager.snapshot, container.sessions.currentToken) { snapshot, _ -> snapshot }.collect { snapshot ->
                val graph = container.sessions.current
                val node = if (!snapshot.state.isConnected || graph == null || graph.isTornDown) null else {
                    if (graph === cachedGraph && cachedNode != null) cachedNode
                    else SessionObserverNodeLink.create(graph.publicationSession) {
                        container.connectionManager.snapshot.value.state.isConnected
                    }?.connectedNode()?.also { cachedGraph = graph; cachedNode = it }
                }
                runtime.onNode(node)
            }
        }
    }

    private fun launchBrowser(app: Application, url: String): Boolean = try {
        app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }
}
