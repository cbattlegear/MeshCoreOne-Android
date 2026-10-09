// PortedFrom: MC1/ContentView.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Edge-to-edge Activity and retained screen navigation; no process/radio graph assembly.
package com.meshcoreone.android.app

import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.container.createToolsDiagnosticsDependencies
import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.DeepLinkRouteOutcome
import com.meshcoreone.android.app.deeplinks.PendingExternalRoute
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationFailure
import com.meshcoreone.android.app.navigation.NavigationSavedState
import com.meshcoreone.android.app.navigation.NavigationState
import com.meshcoreone.android.app.container.onboarding.OnboardingGate
import com.meshcoreone.android.core.connectivity.pairing.CompanionChooserHost
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NavigationHostViewModel : ViewModel() {
    val navigation = NavigationCoordinator()
    var initialized = false
    private var deepLinksBound = false
    private var initialViewIntentSubmitted = false
    private var deliverySequence = 0L
    private var router: AppDeepLinkRouter? = null
    val pendingExternalRoute = PendingExternalRoute { uri ->
        router?.routeExternal(uri) ?: DeepLinkRouteOutcome.Rejected
    }

    fun bindDeepLinks(container: AppContainer) {
        if (deepLinksBound) return
        router = AppDeepLinkRouter(ContainerDeepLinkEnvironment(container, navigation))
        deepLinksBound = true
        viewModelScope.launch { pendingExternalRoute.markReady() }
    }

    fun submitInitialViewIntent(intent: Intent) {
        if (initialViewIntentSubmitted || intent.action != Intent.ACTION_VIEW) return
        initialViewIntentSubmitted = true
        submitViewIntent("initial", intent)
    }

    fun submitNewViewIntent(intent: Intent) {
        if (intent.action != Intent.ACTION_VIEW) return
        deliverySequence = Math.incrementExact(deliverySequence)
        submitViewIntent("new-$deliverySequence", intent)
    }

    private fun submitViewIntent(id: String, intent: Intent) {
        val uri = intent.dataString ?: return
        viewModelScope.launch { pendingExternalRoute.submit(PendingExternalRoute.Delivery(id, uri)) }
    }
}

open class MainActivity : ComponentActivity() {
    private data class ContainerResolution(val container: AppContainer?)

    private lateinit var host: NavigationHostViewModel
    private val uiScope = MainScope()
    private val chooserRequests = HashMap<Int, Long>()
    private var nextChooserCode = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        host = ViewModelProvider(this)[NavigationHostViewModel::class.java]
        if (!host.initialized) {
            if (savedInstanceState?.containsKey(NavigationSavedState.KEY) == true) {
                val saved = savedInstanceState.getStringArrayList(NavigationSavedState.KEY)
                host.navigation.restoreHostState(
                    if (saved != null) NavigationSavedState.restore(saved)
                    else NavigationState(failure = NavigationFailure.InvalidSavedState),
                )
            }
            host.initialized = true
        }
        host.submitInitialViewIntent(intent)
        uiScope.launch {
            val application = application as? MeshCoreApplication ?: return@launch
            try {
                host.bindDeepLinks(application.container.await())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The process container reports its construction failure; the staged URI remains inert.
            }
        }
        setContent {
            MeshCoreTheme {
                // Nothing is drawn until the container answers, so a first run never flashes the main shell.
                val resolved by produceContainer()
                resolved?.let { answer ->
                    OnboardingGate(host.navigation, answer.container?.onboarding) { bound ->
                        val tools = remember(answer.container) {
                            answer.container?.createToolsDiagnosticsDependencies()
                        }
                        NativeNavigationShell(
                            host.navigation,
                            onboarding = bound?.forRerun(),
                            map = answer.container?.mapFeature,
                            tools = tools,
                            nodes = answer.container?.nodesFeature,
                            lineOfSight = answer.container?.lineOfSight,
                        )
                    }
                }
            }
        }
    }

    /**
     * The process container's onboarding bindings once known (null answer = none: no process container, or it failed
     * to build); null state while the container is still building.
     */
    @Composable
    private fun produceContainer(): State<ContainerResolution?> = produceState<ContainerResolution?>(null) {
        val app = application as? MeshCoreApplication
        value = ContainerResolution(if (app == null) null else try {
            app.container.await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            null
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        host.submitNewViewIntent(intent)
    }

    /** While resumed, this activity is the host that can launch the system companion-device chooser. */
    override fun onResume() {
        super.onResume()
        val application = application as? MeshCoreApplication ?: return
        uiScope.launch {
            val setup = try {
                application.container.await().companionSetup
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                null
            }
            setup?.setChooserHost(CompanionChooserHost { chooser, requestId -> launchChooser(chooser, requestId) })
        }
    }

    override fun onPause() {
        (application as? MeshCoreApplication)?.let { app ->
            if (app.container.isCompleted && !app.container.isCancelled) {
                uiScope.launch { app.container.await().companionSetup?.setChooserHost(null) }
            }
        }
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun launchChooser(chooser: Any, requestId: Long) {
        val sender = chooser as? IntentSender ?: return
        val code = nextChooserCode.also { nextChooserCode = if (it >= MAX_CHOOSER_CODE) 1 else it + 1 }
        chooserRequests[code] = requestId
        startIntentSenderForResult(sender, code, null, 0, 0, 0)
    }

    /** API 31-32 deliver the chooser selection here; API 33+ report it through the association callbacks instead. */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val requestId = chooserRequests.remove(requestCode) ?: return
        val app = application as? MeshCoreApplication ?: return
        uiScope.launch { app.container.await().onChooserResult(requestId, resultCode, data) }
    }

    private companion object {
        const val MAX_CHOOSER_CODE = 0x7FFF
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(NavigationSavedState.KEY, NavigationSavedState.encode(host.navigation.state.value))
        super.onSaveInstanceState(outState)
    }
}
