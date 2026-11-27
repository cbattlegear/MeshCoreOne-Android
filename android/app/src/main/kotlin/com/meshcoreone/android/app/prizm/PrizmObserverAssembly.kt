// AndroidOnly: WP-101 Prizm Mobile Observer wiring: builds the runtime, its stand-ins and the node tracker for the process.
package com.meshcoreone.android.app.prizm

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.core.observer.geo.PlaceholderGeofence
import com.meshcoreone.android.core.observer.prizm.PrizmOAuthConfig
import com.meshcoreone.android.core.observer.prizm.StandInPrizmClient
import com.meshcoreone.android.core.observer.session.ObserverRunner
import com.meshcoreone.android.core.observer.session.ObserverSession
import com.meshcoreone.android.core.observer.upload.InMemoryUploadQueueRepository
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
     * STAND-INS in this build: [StandInPrizmClient] (no network, fake account) and [StandInMqttPublisher] (acks locally). The
     * geofence is the placeholder ND/MN outline. See docs/android/deviations/PRIZM-OBSERVER.md.
     */
    fun create(app: Application, container: AppContainer, scope: CoroutineScope): PrizmObserverHandle {
        val log: (String) -> Unit = { message -> Log.i(TAG, message) }
        val store = SharedPreferencesPrizmObserverStore(app, KeystoreSecretBox())
        val geofence by lazy { PlaceholderGeofence.load() }
        val queues = HashMap<String, UploadQueueRepository>()
        lateinit var runtime: PrizmObserverRuntime
        val location = FrameworkLocationUpdates(app, { fix -> runtime.onFix(fix) }, log)
        val publisher = StandInMqttPublisher(log)
        val runner = ObserverRunner(scope) { link ->
            ObserverSession(
                link, location, publisher, runtime.sharing, runtime.zoneSource, geofence,
                UploadClock { Instant.now() }, SystemClock::elapsedRealtime, UUID.randomUUID().toString(),
                queueFactory = { region -> synchronized(queues) { queues.getOrPut(region) { InMemoryUploadQueueRepository() } } },
                backlogRegions = { synchronized(queues) { queues.keys.toList() } },
                log = log,
            )
        }
        runtime = PrizmObserverRuntime(
            store = store, client = StandInPrizmClient(), standIn = true,
            oauth = PrizmOAuthConfig(CLIENT_ID, PrizmCallback.REDIRECT_URI),
            auth = PrizmAuthLauncher { url -> launchBrowser(app, url) },
            service = AndroidPrizmServiceControl(app, log),
            location = FrameworkLocationProvider(app),
            runner = runner, scope = scope, log = log,
        )
        trackNode(container, runtime, scope)
        return PrizmObserverHandle(runtime, PrizmClaimStateHolder(runtime, scope))
    }

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

    /**
     * OPEN QUESTION: the OAuth client id Prizm registers for this app. Used only by the real sign-in path, which the stand-in
     * client bypasses.
     */
    private const val CLIENT_ID = "meshcore-one-android"
}
