// PortedFrom: MC1/ContentView.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: Edge-to-edge Activity and retained screen navigation; no process/radio graph assembly.
// Native adaptation: WP-215 explicit copy/discard of rejected notification actions; never automatic replay.
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
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.container.AppConnectionRecoveryDialogs
import com.meshcoreone.android.app.container.SimulatorPreferenceRecoveryDialog
import com.meshcoreone.android.app.container.AppConnectionStatus
import com.meshcoreone.android.app.container.createToolsFeatureDependencies
import com.meshcoreone.android.app.container.createToolsDiagnosticsDependencies
import com.meshcoreone.android.app.deeplinks.DeepLinkRouteOutcome
import com.meshcoreone.android.app.deeplinks.PendingExternalRoute
import com.meshcoreone.android.app.navigation.GuardedExternalLinkRouter
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.AppShortcutContent
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationFailure
import com.meshcoreone.android.app.navigation.NavigationSavedState
import com.meshcoreone.android.app.navigation.NavigationState
import com.meshcoreone.android.app.container.onboarding.OnboardingGate
import com.meshcoreone.android.app.container.onboarding.onboardingShellVisible
import com.meshcoreone.android.core.connectivity.pairing.CompanionChooserHost
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeServiceHost
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.feature.tools.trace.AndroidRecentHopsStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.meshcoreone.android.platform.notifications.messaging.NotificationActionRecovery
import com.meshcoreone.android.platform.notifications.messaging.NotificationRecoverySnapshot
import com.meshcoreone.android.platform.notifications.R as NotificationStrings

class NavigationHostViewModel : ViewModel() {
    var navigation = NavigationCoordinator()
        private set
    var initialized = false
    private var deepLinksBound = false
    private var initialViewIntentSubmitted = false
    private var deliverySequence = 0L
    private var router: GuardedExternalLinkRouter? = null
    val pendingExternalRoute = PendingExternalRoute { uri ->
        router?.routeExternal(uri) ?: DeepLinkRouteOutcome.Rejected
    }

    fun bindDeepLinks(container: AppContainer) {
        if (deepLinksBound) return
        container.navigation.attachHostState(navigation.state.value)
        navigation = container.navigation
        router = GuardedExternalLinkRouter(container, navigation)
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

    internal fun retryContactLink(deliveryId: String, attempt: Long) {
        viewModelScope.launch { pendingExternalRoute.retry(deliveryId, attempt) }
    }

    internal fun cancelContactLink(deliveryId: String, attempt: Long) {
        viewModelScope.launch { pendingExternalRoute.cancel(deliveryId, attempt) }
    }

    private fun submitViewIntent(id: String, intent: Intent) {
        val uri = intent.dataString ?: return
        viewModelScope.launch { pendingExternalRoute.submit(PendingExternalRoute.Delivery(id, uri)) }
    }
}

@Composable
internal fun ExternalContactLinkFailure(
    failure: PendingExternalRoute.LookupFailure?,
    onRetry: (String, Long) -> Unit,
    onCancel: (String, Long) -> Unit,
) {
    val current = failure ?: return
    key(current.deliveryId, current.attempt) {
        val cancel = { onCancel(current.deliveryId, current.attempt) }
        AlertDialog(
            modifier = Modifier.testTag("external-contact-link-failure"),
            onDismissRequest = cancel,
            title = {
                Text(stringResource(com.meshcoreone.android.core.l10n.R.string.l10n_app_contacts_contacts_nodekind_contact))
            },
            text = { Text(stringResource(AppLocalizableStrings.commonErrorFailedToLoad)) },
            confirmButton = {
                TextButton(
                    onClick = { onRetry(current.deliveryId, current.attempt) },
                    modifier = Modifier.testTag("external-contact-link-retry"),
                ) { Text(stringResource(AppLocalizableStrings.commonTryAgain)) }
            },
            dismissButton = {
                TextButton(
                    onClick = cancel,
                    modifier = Modifier.testTag("external-contact-link-cancel"),
                ) { Text(stringResource(AppLocalizableStrings.commonCancel)) }
            },
        )
    }
}

@Composable
internal fun AppRootRecoveryDialogs(
    navigation: NavigationCoordinator,
    pendingExternalRoute: PendingExternalRoute,
    showExternalLinkFailure: Boolean,
    shellVisible: Boolean,
    nodesAvailable: Boolean,
    connectionRecoveryVisible: Boolean,
    onRetryContactLink: (String, Long) -> Unit,
    onCancelContactLink: (String, Long) -> Unit,
    onDispositionStarted: () -> Unit = {},
) {
    val failure by pendingExternalRoute.lookupFailure.collectAsStateWithLifecycle()
    val navigationState by navigation.state.collectAsStateWithLifecycle()
    if (showExternalLinkFailure) {
        ExternalContactLinkFailure(failure, onRetryContactLink, onCancelContactLink)
    }
    val contactConfirmationVisible =
        shellVisible && nodesAvailable && navigationState.pendingContactLink != null
    NotificationActionRecoveryDialog(
        blockedByOtherDialog = connectionRecoveryVisible || contactConfirmationVisible ||
            (showExternalLinkFailure && failure != null),
        onDispositionStarted = onDispositionStarted,
    )
}

open class MainActivity : ComponentActivity() {
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.meshcoreone.android.app.platform.AppLanguages.attach(base))
    }
    private data class ContainerResolution(val container: AppContainer?, val failure: Throwable? = null)

    private lateinit var host: NavigationHostViewModel
    private val uiScope = MainScope()
    private val chooserRequests = HashMap<Int, Long>()
    private var nextChooserCode = 1
    private var chooserJob: kotlinx.coroutines.Job? = null
    private var chooserSetup: com.meshcoreone.android.core.connectivity.pairing.CompanionSetupService? = null

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
        setContent {
            val resolved by produceContainer()
            val screen: @Composable () -> Unit = {
                val answer = resolved
                val connectionRecovery = answer?.container?.appState?.connectionUI?.state?.collectAsState()?.value
                val simulatorRecovery = answer?.container?.simulatorPreferenceRecovery
                val simulatorPrompt = simulatorRecovery?.prompt?.collectAsStateWithLifecycle()?.value
                val shellVisible = onboardingShellVisible(host.navigation, answer?.container?.onboarding)
                if (answer == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else if (answer.failure != null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(AppLocalizableStrings.commonErrorFailedToLoad))
                    }
                } else {
                    answer.container?.let { AppShortcutContent(it) }
                    OnboardingGate(host.navigation, answer.container?.onboarding) { bound ->
                        val tools = remember(answer.container) {
                            answer.container?.let { container ->
                                container.createToolsFeatureDependencies(
                                    AndroidRecentHopsStorage(getSharedPreferences("tools", MODE_PRIVATE)),
                                    container.createToolsDiagnosticsDependencies(),
                                )
                            }
                        }
                        val unread by (answer.container?.chatsFeature?.unreadCount
                            ?: remember { kotlinx.coroutines.flow.MutableStateFlow(0L) }).collectAsState()
                        NativeNavigationShell(
                            host.navigation,
                            unreadCount = unread,
                            onboarding = bound,
                            map = answer.container?.mapFeature,
                            tools = tools,
                            nodes = answer.container?.nodesFeature,
                            remoteNodes = answer.container?.remoteNodesFeature,
                            lineOfSight = answer.container?.lineOfSight,
                            chats = answer.container?.chatsFeature,
                            settings = answer.container?.settingsFeature,
                            connectionStatus = {
                                answer.container?.let { container ->
                                    AppConnectionStatus(container) {
                                        host.navigation.navigate(
                                            com.meshcoreone.android.core.contracts.FeatureRoute(
                                                com.meshcoreone.android.core.contracts.FeatureId.ONBOARDING,
                                            ),
                                        )
                                    }
                                }
                            },
                        )
                    }
                    if (simulatorPrompt != null && simulatorRecovery != null) {
                        SimulatorPreferenceRecoveryDialog(simulatorRecovery)
                    } else {
                        answer.container?.let { AppConnectionRecoveryDialogs(it.appState) }
                    }
                }
                AppRootRecoveryDialogs(
                    navigation = host.navigation,
                    pendingExternalRoute = host.pendingExternalRoute,
                    showExternalLinkFailure = answer != null && answer.failure == null && simulatorPrompt == null,
                    shellVisible = answer != null && answer.failure == null && shellVisible,
                    nodesAvailable = answer?.container?.nodesFeature != null,
                    connectionRecoveryVisible = simulatorPrompt != null || connectionRecovery?.showingConnectionFailedAlert == true ||
                        connectionRecovery?.otherAppWarningDeviceId != null,
                    onRetryContactLink = host::retryContactLink,
                    onCancelContactLink = host::cancelContactLink,
                )
            }
            val themeService = resolved?.container?.themeService
            if (themeService != null) ThemeServiceHost(themeService) { screen() }
            else MeshCoreTheme(content = screen)
        }
    }

    /**
     * The process container's onboarding bindings once known (null answer = none: no process container, or it failed
     * to build); null state while the container is still building.
     */
    @Composable
    private fun produceContainer(): State<ContainerResolution?> = produceState<ContainerResolution?>(null) {
        val app = application as? MeshCoreApplication
        value = if (app == null) ContainerResolution(null) else try {
            val current = app.container.await()
            host.bindDeepLinks(current)
            ContainerResolution(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            ContainerResolution(null, failure)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        host.submitNewViewIntent(intent)
    }

    /** While resumed, this activity is the host that can launch the system companion-device chooser. */
    override fun onResume() {
        super.onResume()
        NotificationActionRecovery.refresh()
        val application = application as? MeshCoreApplication ?: return
        chooserJob?.cancel()
        chooserJob = uiScope.launch {
            val setup = try {
                application.container.await().companionSetup
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                java.util.logging.Logger.getLogger("com.mc1.Launcher").log(
                    java.util.logging.Level.WARNING, "Companion chooser unavailable", failure,
                )
                return@launch
            }
            activeChooserHost.set(this@MainActivity)
            chooserSetup = setup
            setup?.setChooserHost(CompanionChooserHost { chooser, requestId -> launchChooser(chooser, requestId) })
        }
    }

    override fun onPause() {
        chooserJob?.cancel()
        chooserJob = null
        if (activeChooserHost.compareAndSet(this, null)) chooserSetup?.setChooserHost(null)
        chooserSetup = null
        super.onPause()
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun launchChooser(chooser: Any, requestId: Long) {
        val sender = requireNotNull(chooser as? IntentSender) { "Companion chooser must be an IntentSender" }
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
        val activeChooserHost = java.util.concurrent.atomic.AtomicReference<MainActivity?>(null)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList(NavigationSavedState.KEY, NavigationSavedState.encode(host.navigation.state.value))
        super.onSaveInstanceState(outState)
    }
}

@Composable
internal fun NotificationActionRecoveryDialog(
    blockedByOtherDialog: Boolean = false,
    onDispositionStarted: () -> Unit = {},
) {
    val context = LocalContext.current
    val sequence by NotificationActionRecovery.changes.collectAsState()
    var dismissedSequence by remember { mutableStateOf<Long?>(null) }
    var dismissedUnavailable by remember { mutableStateOf<Pair<Long, Boolean>?>(null) }
    val reader = remember { Mutex() }
    val snapshot by produceState<NotificationRecoverySnapshot?>(null, sequence) {
        value = reader.withLock {
            withContext(Dispatchers.IO) { NotificationActionRecovery.load(context.applicationContext) }
        }
    }
    val recovery = snapshot?.takeIf { it.version == sequence } ?: NotificationActionRecovery.statusSnapshot()
    val item = recovery.items.firstOrNull()
    // Operation state belongs to this host, not to an item temporarily absent during a read.
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val scope = rememberCoroutineScope()
    val empty = item == null && recovery.storageFailure == null &&
        !recovery.notRetained && !recovery.manualOpenRequired && error == null && !busy
    val hidden = blockedByOtherDialog || empty || dismissedSequence == sequence ||
        (!recovery.recordsAvailable &&
        dismissedUnavailable == (recovery.version to recovery.readCompleted)
        )
    val displayedAction by rememberUpdatedState(
        Triple(item?.entry?.id, recovery.version, !hidden),
    )
    if (hidden) return
    val canAct = {
        !busy && displayedAction == Triple(item?.entry?.id, recovery.version, true) &&
            NotificationActionRecovery.changes.value == recovery.version
    }
    val close = {
        if (!busy) {
            error = null
            if (!recovery.recordsAvailable) {
                // Closing an incomplete read must not acknowledge unseen records or status.
                dismissedUnavailable = recovery.version to recovery.readCompleted
            } else {
                NotificationActionRecovery.acknowledgeFailure(recovery.version)?.let { acknowledgedVersion ->
                    dismissedSequence = acknowledgedVersion
                }
            }
        }
    }
    AlertDialog(
        properties = DialogProperties(securePolicy = SecureFlagPolicy.SecureOn),
        onDismissRequest = close,
        title = { Text(stringResource(NotificationStrings.string.notification_action_recovery_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).testTag(
                    if (recovery.readCompleted) "notification-recovery-read-complete"
                    else "notification-recovery-read-pending",
                ),
            ) {
                if (recovery.notRetained) Text(stringResource(NotificationStrings.string.notification_action_recovery_not_retained))
                if (!recovery.recordsAvailable || recovery.storageFailure != null) {
                    Text(stringResource(NotificationStrings.string.notification_action_recovery_storage_unavailable))
                }
                if (recovery.manualOpenRequired) {
                    Text(stringResource(NotificationStrings.string.notification_action_recovery_launch_failed))
                }
                item?.let {
                    Text(
                        stringResource(
                            if (it.entry.response.action == NotificationAction.REPLY) {
                                NotificationStrings.string.notification_action_recovery_rejected_reply
                            } else {
                                NotificationStrings.string.notification_action_recovery_rejected_mark_read
                            },
                        ),
                    )
                    Text(
                        stringResource(
                            if (it.persistent) NotificationStrings.string.notification_action_recovery_saved
                            else NotificationStrings.string.notification_action_recovery_memory_only,
                        ),
                    )
                    Text(
                        stringResource(
                            NotificationStrings.string.notification_action_recovery_original_target,
                            it.entry.response.payload.toString(),
                        ),
                    )
                    it.entry.response.userText?.let { text -> Text(text) }
                }
                error?.let { Text(stringResource(it)) }
            }
        },
        confirmButton = {
            Column {
                val text = item?.entry?.response?.userText
                if (item != null && text != null) {
                    TextButton(
                        enabled = !busy,
                        onClick = action@ {
                            if (!canAct()) return@action
                            busy = true
                            error = null
                            onDispositionStarted()
                            scope.launch {
                                try {
                                    if (!copyRejectedNotificationText(context, text)) {
                                        error = NotificationStrings.string.notification_action_recovery_copy_failed
                                    } else {
                                        val failure = withContext(Dispatchers.IO) {
                                            NotificationActionRecovery.dispose(context.applicationContext, item)
                                        }
                                        if (failure != null) {
                                            error = NotificationStrings.string.notification_action_recovery_discard_failed
                                        }
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(stringResource(AppChatsStrings.chatsMessageActionCopy)) }
                }
                if (item != null) {
                    TextButton(
                        enabled = !busy,
                        onClick = action@ {
                            if (!canAct()) return@action
                            busy = true
                            error = null
                            onDispositionStarted()
                            scope.launch {
                                try {
                                    val failure = withContext(Dispatchers.IO) {
                                        NotificationActionRecovery.dispose(context.applicationContext, item)
                                    }
                                    if (failure != null) {
                                        error = NotificationStrings.string.notification_action_recovery_discard_failed
                                    }
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(stringResource(AppChatsStrings.chatsMessageActionDelete)) }
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = close) {
                Text(stringResource(AppLocalizableStrings.commonCancel))
            }
        },
    )
}

internal fun copyRejectedNotificationText(context: android.content.Context, text: String): Boolean {
    return try {
        val clipboard = context.getSystemService(android.content.ClipboardManager::class.java) ?: return false
        val clip = android.content.ClipData.newPlainText("", text).apply {
            description.extras = android.os.PersistableBundle().apply {
                putBoolean("android.content.extra.IS_SENSITIVE", true)
            }
        }
        clipboard.setPrimaryClip(clip)
        val copied = clipboard.primaryClip
        copied != null && copied.itemCount == 1 && copied.getItemAt(0).text?.toString() == text
    } catch (_: SecurityException) {
        false
    } catch (_: IllegalStateException) {
        false
    } catch (failure: RuntimeException) {
        if (failure.cause is android.os.RemoteException) false else throw failure
    }
}
