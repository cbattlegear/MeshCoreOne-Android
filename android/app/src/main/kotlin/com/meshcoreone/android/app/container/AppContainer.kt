// PortedFrom: MC1/MC1App.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/State/AppState.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.navigation.CapturedContactLinkContext
import com.meshcoreone.android.app.navigation.ContactLinkLookupResolution
import com.meshcoreone.android.app.navigation.ContactLinkRequest
import com.meshcoreone.android.app.navigation.ContactLinkResolvedCompletion
import com.meshcoreone.android.app.navigation.ChatContactMount
import com.meshcoreone.android.app.navigation.MountedOnboardingProducer
import com.meshcoreone.android.app.navigation.NavigationCleanupOwner
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.app.navigation.NavigationFailure
import com.meshcoreone.android.app.navigation.NavigationOutcome
import com.meshcoreone.android.app.navigation.NavigationState
import com.meshcoreone.android.app.navigation.NotificationNavigationBinding
import com.meshcoreone.android.app.navigation.NotificationNavigationReceipt
import com.meshcoreone.android.app.navigation.PersistenceNavigationLookup
import com.meshcoreone.android.app.state.AccessibilityAnnouncer
import com.meshcoreone.android.app.state.AppState
import com.meshcoreone.android.app.state.AppStateDependencies
import com.meshcoreone.android.app.state.AppStatePlatform
import com.meshcoreone.android.app.state.BatteryMonitor
import com.meshcoreone.android.app.state.ChatPrimerFactory
import com.meshcoreone.android.app.state.ConnectionUiState
import com.meshcoreone.android.app.state.ProcessForegroundState
import com.meshcoreone.android.app.state.RegionSelectionStore
import com.meshcoreone.android.app.state.ResyncFailureRegistry
import com.meshcoreone.android.app.state.StaleCleanupPreferences
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.ConnectivityPlatform
import com.meshcoreone.android.core.connectivity.SystemConnectivityClock
import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.ble.SystemLinkProbe
import com.meshcoreone.android.core.connectivity.bond.BondInspector
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.device.ContactRemovalPort
import com.meshcoreone.android.core.connectivity.device.RadioPresetMatcher
import com.meshcoreone.android.core.connectivity.pairing.KnownEndpointStore
import com.meshcoreone.android.core.connectivity.pairing.PairingCoordinator
import com.meshcoreone.android.core.connectivity.service.ConnectedDeviceHostingController
import com.meshcoreone.android.core.connectivity.service.ForegroundServiceStarter
import com.meshcoreone.android.core.contracts.domain.Capability
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.ConnectedDeviceEdit
import com.meshcoreone.android.core.contracts.domain.ConnectionSnapshot
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagingIssueReporter
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.domain.NotificationStringProvider
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.notifications.NotificationDeliveryPort
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.maps.OfflineMapController
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SystemSessionClock
import com.meshcoreone.android.core.runtime.ConnectionManager
import com.meshcoreone.android.core.runtime.ConnectionObserver
import com.meshcoreone.android.core.runtime.LastConnectionStore
import com.meshcoreone.android.core.runtime.ProcessConnectionPreferences
import com.meshcoreone.android.core.runtime.ProcessRuntimeMaintenance
import com.meshcoreone.android.core.runtime.RuntimeClock
import com.meshcoreone.android.core.runtime.RuntimeIssueReporter
import com.meshcoreone.android.core.runtime.RuntimeLinkFactory
import com.meshcoreone.android.core.runtime.SystemRuntimeClock
import com.meshcoreone.android.core.services.contacts.ContactPreferenceFlags
import com.meshcoreone.android.core.services.device.RadioPresets
import com.meshcoreone.android.core.services.diagnostics.DebugLogBuffer
import com.meshcoreone.android.core.services.messaging.ChatSendQueueConfig
import com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress
import com.meshcoreone.android.core.services.messaging.ChannelQueryAccountingEvent
import com.meshcoreone.android.core.services.messaging.LoggingMessagingIssueReporter
import com.meshcoreone.android.core.services.messaging.MessageServiceConfig
import com.meshcoreone.android.core.services.notifications.NotificationResponseDispatch
import com.meshcoreone.android.core.services.notifications.didReceive
import com.meshcoreone.android.core.services.notifications.didReceiveWithResult
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import com.meshcoreone.android.core.services.rendering.DraftStore
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncLogSink
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.core.services.simulator.SimulatorSeedStore
import com.meshcoreone.android.feature.nodes.deps.Announcer
import com.meshcoreone.android.feature.nodes.deps.StringListPreferences
import com.meshcoreone.android.feature.nodes.deps.UserFacingMessages
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkCancellation
import com.meshcoreone.android.feature.chats.list.ContactLinkContext
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import com.meshcoreone.android.feature.chats.list.ContactLinkTargetCompletion
import java.time.Instant
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every process-lifetime collaborator [AppContainer] assembles. Production values come from
 * [AndroidAppContainerFactory]; tests supply recording doubles through the same constructor, so the container logic
 * that is exercised is the production wiring, not a parallel copy.
 */
class AppContainerDependencies(
    val store: PersistenceStoreProtocol,
    val passwords: NodePasswordVault,
    val connectionPreferences: ProcessConnectionPreferences,
    val connectivity: ConnectivityPlatform,
    val linkProbe: SystemLinkProbe,
    val scans: BleScanCoordinator,
    val linkFactory: RuntimeLinkFactory,
    val notificationDelivery: NotificationDeliveryPort,
    val notificationPreferences: NotificationPreferencesPort,
    val contactPreferences: ContactPreferenceFlags,
    val draftStore: DraftStore,
    val chatSendQueueProgress: ChatSendQueueProgress,
    /** The main-thread stand-in app state, UI timers and the chat coordinators run on. */
    val mainScope: CoroutineScope,
    val notificationStrings: NotificationStringProvider? = null,
    val foreground: ProcessForegroundState = ProcessForegroundState(),
    val runtimeClock: RuntimeClock = SystemRuntimeClock(),
    val runtimeContext: CoroutineContext = Dispatchers.Default,
    val sessionConfiguration: SessionConfiguration = SessionConfiguration.DEFAULT,
    val messageServiceConfig: MessageServiceConfig = MessageServiceConfig(),
    val chatSendQueueConfig: ChatSendQueueConfig = ChatSendQueueConfig(),
    val knownEndpoints: KnownEndpointStore? = null,
    val bonds: BondInspector? = null,
    val ensureBonded: (suspend (java.util.UUID) -> Unit)? = null,
    val accessibility: AccessibilityAnnouncer = AccessibilityAnnouncer.NONE,
    val platform: AppStatePlatform = AppStatePlatform.NONE,
    val regionStore: RegionSelectionStore? = null,
    val stalePreferences: StaleCleanupPreferences? = null,
    val primerFactory: ChatPrimerFactory? = null,
    val syncClock: SyncClock = SyncClock.SYSTEM,
    val sessionClock: SessionClock = SystemSessionClock(),
    val logSink: SyncLogSink = SyncLogSink.NONE,
    val messagingReporter: MessagingIssueReporter = LoggingMessagingIssueReporter,
    val runtimeReporter: RuntimeIssueReporter = RuntimeIssueReporter {},
    val connectivityDiagnostics: ConnectivityDiagnostics = ConnectivityDiagnostics.NONE,
    val sessionLifecycle: SessionLifecycleListener = SessionLifecycleListener.NONE,
    /** Starts/stops the single connectedDevice foreground service; null leaves hosting unbound (JVM composition). */
    val hostingStarter: ForegroundServiceStarter? = null,
    val hostEnvironment: HostEnvironment? = null,
    /** Re-reads the system association list (presence `AssociationsChanged`). */
    val refreshAssociations: () -> Unit = {},
    /** Reads the platform permission facts on each foreground edge; null disables revocation handling. */
    val permissionSnapshot: (() -> com.meshcoreone.android.core.connectivity.permissions.PermissionSnapshot)? = null,
    /** The companion setup the resumed activity attaches its chooser host to; null when no companion registry exists. */
    val companionSetup: com.meshcoreone.android.core.connectivity.pairing.CompanionSetupService? = null,
    /** Forwards an API 31-32 chooser activity result (request id, result code, data) to the companion gateway. */
    val chooserResult: ((Long, Int, android.content.Intent?) -> Unit)? = null,
    /** Platform pieces for the onboarding flow; null leaves onboarding unbound (the shell shows no fake flow). */
    val onboardingPlatform: com.meshcoreone.android.app.container.onboarding.OnboardingPlatform? = null,
    /** Production pre-advert policy; an unbound composition must not silently skip an enabled refresh. */
    val advertLocation: AppAdvertLocation? = null,
    val nodesLocations: Flow<Coordinate?> = flowOf(null),
    val offlineMaps: OfflineMapController? = null,
    val phoneLocation: () -> com.meshcoreone.android.core.model.Coordinate? = { null },
    val mapPreferences: com.meshcoreone.android.core.maps.MapPreferenceStore? = null,
    val nodesMessages: UserFacingMessages = UserFacingMessages { it.message ?: "Operation failed" },
    val nodesAnnouncer: Announcer = Announcer {},
    val nodesPreferences: StringListPreferences = object : StringListPreferences {
        override fun stringList(key: String): List<String>? = null
        override fun setStringList(key: String, value: List<String>) = Unit
    },
    /** Real process/store/RF/elevation adapters for line-of-sight; network terrain remains optional to mesh. */
    val lineOfSight: com.meshcoreone.android.feature.tools.los.LineOfSightFeatureDependencies? = null,
    val themeService: com.meshcoreone.android.core.designsystem.ThemeService? = null,
    val backupEngine: com.meshcoreone.android.feature.settings.app.backup.BackupEngine? = null,
    internal val chatContent: AppChatContent? = null,
    val newBootstrapDebugLog: (CoroutineScope) -> DebugLogBuffer? = { null },
    val runtimeRecoveryDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Dispatcher for the process-owned, session-checked notification READY retry. */
    val notificationReadyDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** The actual process-store adapter used by simulator generations; physical-only test compositions may omit it. */
    val simulatorSeedStore: SimulatorSeedStore? = null,
    /** Passive pre-barrier persistence observer; must not block, throw or perform lifecycle work. */
    val onChannelQueryAccounting: ((ChannelQueryAccountingEvent) -> Unit)? = null,
    /** Releases process-owned resources (database, storage) once the runtime has closed. */
    val onClose: suspend () -> Unit = {},
    /** Retry platform responses only after the current generation's navigation callbacks are installed. */
    val onNotificationNavigationReady: () -> Unit = {},
)

/**
 * The process-scoped container (Swift `MC1App`'s long-lived objects): one runtime connection manager, one app
 * state, one process store and the registry through which the per-connection [RadioSessionContainer] is reached.
 * It owns no radio-specific service; those are built by [RadioSessionContainerFactory] each time the runtime
 * connects, and the registry proves each one was torn down.
 */
class AppContainer(private val dependencies: AppContainerDependencies) {
    private val processJob = SupervisorJob()
    private val processScope = CoroutineScope(dependencies.runtimeContext + processJob)
    private val resyncFailureHub = ResyncFailureHub()
    private val observerBridge = ObserverBridge(dependencies.foreground, dependencies.mainScope.coroutineContext)

    val sessions = SessionRegistry()
    val navigation = NavigationCoordinator().also { coordinator ->
        coordinator.installCommitBoundary { action -> withNavigationCommitBoundary(action) }
        coordinator.installOnboardingSessionCapture(::captureOnboardingNavigationSession)
    }
    private val contactHandoffSettlement = processScope.launch(Dispatchers.Default) {
        navigation.contactHandoffUpdates.collect { navigation.settlePublishedChatContactReceipts() }
    }
    internal val currentTransport: com.meshcoreone.android.core.model.TransportType?
        get() = if (connectionManager.isSimulatorConnection) com.meshcoreone.android.core.model.TransportType.SIMULATOR
        else connectionManager.snapshot.value.token?.let {
            (dependencies.linkFactory as? AndroidRuntimeLinkFactory)?.transportFor(it)
        }
    internal val processStore get() = dependencies.store
    internal val simulatorPreferenceRecovery =
        (dependencies.store as? RoomPersistenceStore)?.let(::SimulatorPreferenceRecovery)
    internal val mapPreferences get() = dependencies.mapPreferences
    internal val bestAvailableLocation: com.meshcoreone.android.core.model.Coordinate?
        get() = dependencies.phoneLocation() ?: appState.connectedDevice?.takeIf { it.hasLocation }?.let {
            com.meshcoreone.android.core.model.Coordinate(it.latitude, it.longitude)
        }
    internal val uiScope = CoroutineScope(dependencies.mainScope.coroutineContext + SupervisorJob(processJob))
    val foreground: ProcessForegroundState = dependencies.foreground
    val bootstrapDebugLog: DebugLogBuffer? = dependencies.newBootstrapDebugLog(processScope)
        // A live process publishes its bootstrap buffer as the process-global one; the inert placeholder never does.
        ?.also { DebugLogBuffer.shared = it }

    private val lastConnection = LastConnectionStore(dependencies.connectionPreferences, dependencies.runtimeClock)
    private val platformAdapter = RuntimePlatformAdapter(dependencies.connectivity)
    private val runtimeState = ManagerRuntimeSyncState({ connectionManager }, processScope)

    private val environment = SessionEnvironment(
        store = dependencies.store,
        processScope = processScope,
        notificationDelivery = dependencies.notificationDelivery,
        notificationPreferences = dependencies.notificationPreferences,
        notificationStrings = dependencies.notificationStrings,
        appState = dependencies.foreground,
        passwords = dependencies.passwords,
        contactPreferences = dependencies.contactPreferences,
        chatSendQueueProgress = dependencies.chatSendQueueProgress,
        runtimeState = runtimeState,
        resyncFailure = resyncFailureHub,
        syncClock = dependencies.syncClock,
        sessionClock = dependencies.sessionClock,
        logSink = dependencies.logSink,
        messagingReporter = dependencies.messagingReporter,
        onChannelQueryAccounting = dependencies.onChannelQueryAccounting,
        messageServiceConfig = dependencies.messageServiceConfig,
        chatSendQueueConfig = dependencies.chatSendQueueConfig,
        onSessionLifecycle = object : SessionLifecycleListener {
            override fun created(container: RadioSessionContainer) = dependencies.sessionLifecycle.created(container)
            override fun tornDown(container: RadioSessionContainer) {
                // The factory has withdrawn this graph; a state edge can arrive before that withdrawal.
                uiScope.launch {
                    statePlatform.retire(container)
                }
                dependencies.sessionLifecycle.tornDown(container)
            }
        },
        bootstrapDebugLog = bootstrapDebugLog,
    )

    val connectionManager: ConnectionManager = ConnectionManager(
        devices = dependencies.store,
        rooms = dependencies.store,
        contacts = dependencies.store,
        maintenance = object : ProcessRuntimeMaintenance {
            override suspend fun warmUp() = dependencies.store.warmUp()
            // The source applies no per-device defaults at connect; per-device preferences are read lazily.
            override suspend fun initializeDevicePreferences(device: com.meshcoreone.android.core.model.DeviceDTO) = Unit
        },
        lastConnection = lastConnection,
        platform = platformAdapter,
        linkFactory = RuntimeLinkFactory { target ->
            if (target == ConnectionTarget.Simulator) {
                val seed = checkNotNull(dependencies.simulatorSeedStore) { "Simulator seed store is not installed" }
                val protectedSeed = object : SimulatorSeedStore by seed {
                    override suspend fun saveDevice(dto: com.meshcoreone.android.core.model.DeviceDTO) {
                        val recovery = simulatorPreferenceRecovery
                        if (recovery == null) seed.saveDevice(dto)
                        else recovery.seed(dto) { seed.saveDevice(dto) }
                    }
                }
                SimulatorRuntimeLink(protectedSeed, finishSeed = {
                    dependencies.store.flushPendingRxLogEntries()
                    checkNotNull(dependencies.chatContent) { "Simulator chat content is not installed" }.seedDemoInlineImage()
                })
            } else dependencies.linkFactory.create(target)
        },
        serviceFactory = RadioSessionContainerFactory(environment, sessions),
        observer = observerBridge.observer,
        reporter = dependencies.runtimeReporter,
        clock = dependencies.runtimeClock,
        context = dependencies.runtimeContext,
        configuration = dependencies.sessionConfiguration,
        recoveryDispatcher = dependencies.runtimeRecoveryDispatcher,
        simulatorDeviceId = MockDataProvider.simulatorDeviceID,
        publicationRegistry = sessions.publicationIdentity,
    )

    val pairing: PairingCoordinator = PairingCoordinator(
        RuntimePairingPort(connectionManager, platformAdapter), dependencies.connectivity.pairing, dependencies.store,
        dependencies.linkProbe, dependencies.scans::stopBleScanning, SystemConnectivityClock(), processScope,
        dependencies.connectivityDiagnostics, dependencies.ensureBonded, dependencies.knownEndpoints, dependencies.bonds,
    )

    internal val deviceAccess = ManagerDeviceAccess(connectionManager)
    internal val deviceEditor = ConnectedDeviceEditor(
        deviceAccess, dependencies.store, dependencies.store,
        { sessions.current?.let { ContactRemoval(it.token.radioId, it) } },
        RadioPresetMatcher { frequency, bandwidth, spreadingFactor, codingRate ->
            RadioPresets.matchingPresets(frequency, bandwidth, spreadingFactor, codingRate).map { it.id }.toSet()
        },
        processScope, { Instant.now() }, dependencies.connectivityDiagnostics,
        onKnownRegionsChanged = ::knownRegionsChanged,
    )

    internal fun knownRegionsChanged(edit: ConnectedDeviceEdit, regions: List<String>) {
        sessions.current?.takeIf { it.token == edit.token }?.knownRegionsChanged(edit.sequence, regions)
    }

    val connectionPort = ConnectionManagerAppPort(
        connectionManager, platformAdapter, pairing, lastConnection, deviceEditor, dependencies.scans::stopBleScanning,
    )

    private val statePlatform = ContainerAppStatePlatform(
        dependencies.platform, navigation, dependencies.store, dependencies.onNotificationNavigationReady,
        isCurrentSession = ::isCurrentSession,
    )
    private val disconnectWatcher: Job by lazy { watchDisconnects() }
    private val notificationReadyWatcher: Job by lazy { watchNotificationReadiness() }

    private fun <T> withNavigationCommitBoundary(action: () -> T): T =
        connectionManager.withNavigationCommitBoundary {
            sessions.withNavigationCommitBoundary { current ->
                if (current == null) action() else current.withNavigationCommitBoundary(action)
            }
        }

    internal fun isCurrentSession(session: com.meshcoreone.android.app.state.AppSession): Boolean =
        withNavigationCommitBoundary {
            val current = sessions.current
            current != null && current === session && !current.isTornDown &&
                connectionManager.isNavigationSessionCurrent(session.token)
        }

    private data class OnboardingNavigationCapture(
        val graph: RadioSessionContainer?,
        val lifecycle: ConnectionManager.NavigationLifecycleStamp,
        val aligned: Boolean,
        val offline: ConnectionManager.OfflineNavigationLifecycle?,
    )

    private fun captureOnboardingNavigationSession(
        source: NavigationState,
    ): (MountedOnboardingProducer) -> Boolean {
        val (initiating, lifecycle, aligned, offline) = withNavigationCommitBoundary {
            val graph = sessions.current
            val stamp = connectionManager.captureNavigationLifecycle()
            val offline = connectionManager.captureOfflineNavigationLifecycle(stamp)
            val current = if (graph != null) {
                !graph.isTornDown &&
                    (graph.token == source.boundSessionToken ||
                        source.boundSessionToken == null) &&
                    connectionManager.isInitialNavigationSession(stamp, graph.token, sessions.publicationIdentity, graph)
            } else {
                source.boundSessionToken == null && (
                    connectionManager.isNavigationLifecycleCurrent(stamp) ||
                        offline != null
                    )
            }
            OnboardingNavigationCapture(graph, stamp, current, offline)
        }
        return { producer ->
            withNavigationCommitBoundary {
                val graph = sessions.current
                val token = navigation.currentState.boundSessionToken
                when {
                    !aligned -> false
                    producer is MountedOnboardingProducer.Preset && graph?.token != producer.token -> false
                    producer is MountedOnboardingProducer.NoDeviceAtSession && graph?.token != producer.token -> false
                    producer == MountedOnboardingProducer.NoDevice && graph !== initiating -> false
                    graph == null -> initiating == null && token == null &&
                        (connectionManager.isNavigationLifecycleCurrent(lifecycle) ||
                            connectionManager.isOfflineNavigationLifecycleCurrent(lifecycle))
                    graph.isTornDown || token != null && token != graph.token -> false
                    initiating != null -> graph === initiating &&
                        connectionManager.isInitialNavigationSession(
                            lifecycle, graph.token, sessions.publicationIdentity, graph,
                        )
                    else -> source.boundSessionToken == null && (
                        connectionManager.isInitialNavigationSession(
                            lifecycle, graph.token, sessions.publicationIdentity, graph,
                        ) || offline != null && connectionManager.isFirstNavigationSessionAfterOffline(
                            offline, graph.token, sessions.publicationIdentity, graph,
                        )
                        )
                }
            }
        }
    }

    internal fun captureContactNavigationSession(generation: Long): () -> Boolean {
        val (initiating, stamp) = withNavigationCommitBoundary {
            sessions.current to connectionManager.captureNavigationLifecycle()
        }
        return {
            withNavigationCommitBoundary {
                val current = sessions.current
                if (initiating != null) {
                    current === initiating && !initiating.isTornDown &&
                        connectionManager.isInitialNavigationSession(
                            stamp, initiating.token, sessions.publicationIdentity, initiating,
                        ) &&
                        (connectionManager.snapshot.value.token == initiating.token ||
                            connectionManager.snapshot.value.token == null &&
                            navigation.currentState.let { it.radioId == null && it.generation == generation })
                } else if (current == null) {
                    connectionManager.isNavigationLifecycleCurrent(stamp)
                } else {
                    !current.isTornDown &&
                        connectionManager.isInitialNavigationSession(
                            stamp, current.token, sessions.publicationIdentity, current,
                        ) &&
                        navigation.currentState.let {
                            it.radioId == null && it.generation == generation ||
                                it.initialContactLinkGeneration == generation &&
                                it.generation == Math.incrementExact(generation)
                        }
                }
            }
        }
    }

    internal data class ExternalChannelLinkSession(val radioId: RadioId?, val isCurrent: () -> Boolean)

    private data class ExternalChannelLinkCapture(
        val graph: RadioSessionContainer?,
        val lifecycle: ConnectionManager.NavigationLifecycleStamp,
        val radioId: RadioId?,
        val generation: Long,
        val entryEpoch: Long,
        val quiescentOffline: Boolean,
    )

    internal fun captureExternalChannelLinkSession(): ExternalChannelLinkSession {
        val captured = withNavigationCommitBoundary {
            val lifecycle = connectionManager.captureNavigationLifecycle()
            ExternalChannelLinkCapture(
                sessions.current, lifecycle, appState.currentRadioId,
                navigation.currentState.generation, navigation.currentEntryEpoch,
                connectionManager.isOfflineNavigationLifecycleCurrent(lifecycle),
            )
        }
        return ExternalChannelLinkSession(captured.radioId) {
            withNavigationCommitBoundary {
                val graph = sessions.current
                if (navigation.currentState.generation != captured.generation ||
                    navigation.currentEntryEpoch != captured.entryEpoch ||
                    appState.currentRadioId != captured.radioId
                ) false
                else if (captured.graph == null) {
                    graph == null && (
                        if (captured.quiescentOffline) {
                            connectionManager.isOfflineNavigationLifecycleCurrent(captured.lifecycle)
                        } else connectionManager.isNavigationLifecycleCurrent(captured.lifecycle)
                        )
                } else {
                    graph === captured.graph && !captured.graph.isTornDown &&
                        connectionManager.isInitialNavigationSession(
                            captured.lifecycle, captured.graph.token, sessions.publicationIdentity, captured.graph,
                        )
                }
            }
        }
    }

    internal fun captureContactLinkSession(generation: Long): () -> Boolean {
        val lookupRevision = navigation.beginContactLinkLookup(generation)
        val isCurrentSession = captureContactNavigationSession(generation)
        return { navigation.isContactLinkLookupCurrent(lookupRevision) && isCurrentSession() }
    }

    internal fun captureContactLinkContext(
        publicKey: Bytes, publications: ContactLinkPublications,
        mount: ChatContactMount? = null,
    ): ContactLinkContext = withNavigationCommitBoundary {
        val graph = sessions.current
        val lifecycle = connectionManager.captureNavigationLifecycle()
        val device = connectionManager.connectedDevice
        val radio = graph?.token?.radioId ?: device?.radioId ?: connectionPort.lastConnectedRadioId
        val selfKey = graph?.navigationPublicKey ?: device?.publicKey
        CapturedContactLinkContext(
            navigation.captureContactLinkLookup(
                publicKey, radio, selfKey, graph?.token, connectionManager.snapshot.value.token,
                if (graph == null) connectionManager.isNavigationLifecycleCurrent(lifecycle) ||
                    connectionManager.isOfflineNavigationLifecycleCurrent(lifecycle)
                else !graph.isTornDown &&
                    connectionManager.isInitialNavigationSession(
                        lifecycle, graph.token, sessions.publicationIdentity, graph,
                    ),
                publications, mount,
            ),
            graph, lifecycle,
        )
    }

    internal fun completeContactLookup(
        context: ContactLinkContext, link: ContactLinkRequest, existing: ContactDTO?,
        selectChats: Boolean, publications: ContactLinkPublications,
    ): ContactLinkCompletion {
        val captured = context as? CapturedContactLinkContext ?: return ContactLinkCompletion.StaleContext
        return withNavigationCommitBoundary {
            val current = sessions.current
            val lookup = captured.lookup
            if (!isCapturedContactGraphCurrent(captured, current)) ContactLinkCompletion.StaleContext
            else navigation.completeCapturedContactLookup(
                lookup, link, existing, current?.token, lookup.allowsInitialBinding,
                current?.navigationPublicKey.takeIf { lookup.allowsInitialBinding }, selectChats, publications,
            )
        }
    }

    internal fun completeResolvedContactLookup(
        context: ContactLinkContext, target: ContactLinkLookupResolution.Current,
        link: ContactLinkRequest, existing: ContactDTO?, selectChats: Boolean, publications: ContactLinkPublications,
        cancellation: ContactLinkCancellation? = null,
    ): ContactLinkResolvedCompletion {
        val captured = context as? CapturedContactLinkContext
            ?: return ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.StaleContext)
        return withNavigationCommitBoundary {
            cancellation?.ensureActive()
            val current = sessions.current
            val lookup = captured.lookup
            if (!isCapturedContactGraphCurrent(captured, current)) {
                ContactLinkResolvedCompletion.Completed(ContactLinkCompletion.StaleContext)
            } else navigation.completeResolvedCapturedContactLookup(
                lookup, target, link, existing, current?.token, lookup.allowsInitialBinding,
                current?.navigationPublicKey.takeIf { lookup.allowsInitialBinding }, selectChats, publications, cancellation,
            )
        }
    }

    internal fun resolveContactLinkContext(context: ContactLinkContext, publicKey: Bytes): ContactLinkLookupResolution {
        val captured = context as? CapturedContactLinkContext ?: return ContactLinkLookupResolution.Stale
        return withNavigationCommitBoundary {
            val current = sessions.current
            val lookup = captured.lookup
            if (lookup.publicKey != publicKey || !isCapturedContactGraphCurrent(captured, current)) {
                ContactLinkLookupResolution.Stale
            } else navigation.resolveCapturedContactLookup(
                lookup, current?.token, lookup.allowsInitialBinding,
                current?.navigationPublicKey.takeIf { lookup.allowsInitialBinding },
            )
        }
    }

    internal fun registerChatContactMount(mount: ChatContactMount) {
        val publication = navigation.registerChatContactMount(mount)
        releaseChatContactPublication(publication)
    }

    internal fun sealChatContactMount(mount: ChatContactMount) {
        val publication = navigation.sealChatContactMount(mount)
        releaseChatContactPublication(publication)
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun releaseChatContactPublication(publication: com.meshcoreone.android.feature.chats.list.ContactLinkPublication) {
        // This non-suspending cleanup must start even if process cancellation races disposal.
        processScope.launch(Dispatchers.Default, start = CoroutineStart.ATOMIC) { publication.release() }
    }

    internal fun offerChatContact(
        mount: ChatContactMount, context: ContactLinkContext, target: ContactLinkLookupResolution.Current,
        link: ContactLinkRequest, contact: ContactDTO?, publications: ContactLinkPublications,
        cancellation: ContactLinkCancellation,
    ): ContactLinkTargetCompletion {
        val offered = ContactLinkPublications()
        publications.defer(object : com.meshcoreone.android.feature.chats.list.ContactLinkPublication {
            override fun release() = releaseChatContactPublication(object :
                com.meshcoreone.android.feature.chats.list.ContactLinkPublication {
                override fun release() = offered.release()
            })
        })
        return withNavigationCommitBoundary {
            cancellation.ensureActive()
            val resolved = resolveContactLinkContext(context, link.publicKey)
            if (resolved is ContactLinkLookupResolution.Current &&
                (resolved.radioId != target.radioId || resolved.selfPublicKey != target.selfPublicKey)
            ) return@withNavigationCommitBoundary ContactLinkTargetCompletion.TargetChanged
            navigation.offerChatContact(mount, context, target, link, contact, offered, cancellation) {
                val current = resolveContactLinkContext(context, link.publicKey)
                current is ContactLinkLookupResolution.Current &&
                    current.radioId == target.radioId && current.selfPublicKey == target.selfPublicKey
            }
        }
    }

    /** The shell, not the originating conversation or lookup task, owns acceptance. */
    internal fun acceptChatContactOffers() {
        navigation.pendingChatContactOffers().forEach { offer ->
            val accepted = ContactLinkPublications()
            try {
                withNavigationCommitBoundary {
                    val captured = offer.context as? CapturedContactLinkContext
                    val current = sessions.current
                    navigation.acceptChatContact(
                        offer, accepted, current?.token, captured?.lookup?.allowsInitialBinding == true,
                        current?.navigationPublicKey,
                    ) { captured != null && isCapturedContactGraphCurrent(captured, sessions.current) }
                }
            } finally {
                releaseChatContactPublication(object : com.meshcoreone.android.feature.chats.list.ContactLinkPublication {
                    override fun release() = accepted.release()
                })
            }
        }
    }

    internal fun isContactLinkContextCurrent(context: ContactLinkContext, publicKey: Bytes): Boolean {
        return resolveContactLinkContext(context, publicKey) is ContactLinkLookupResolution.Current
    }

    private fun isCapturedContactGraphCurrent(
        captured: CapturedContactLinkContext, current: RadioSessionContainer?,
    ): Boolean {
        val lookup = captured.lookup
        return when {
            lookup.owner !== navigation -> false
            current != null && lookup.radioId != null && lookup.radioId != current.token.radioId -> false
            current == null -> captured.graph == null &&
                (connectionManager.isNavigationLifecycleCurrent(captured.lifecycle) ||
                    connectionManager.isOfflineNavigationLifecycleCurrent(captured.lifecycle))
            current.isTornDown -> false
            captured.graph != null -> current === captured.graph &&
                (lookup.boundToken == current.token || lookup.allowsInitialBinding) &&
                connectionManager.isInitialNavigationSession(
                    captured.lifecycle, current.token, sessions.publicationIdentity, current,
                )
            else -> lookup.allowsInitialBinding &&
                connectionManager.isInitialNavigationSession(
                    captured.lifecycle, current.token, sessions.publicationIdentity, current,
                )
        }
    }

    val appState: AppState = AppState(
        AppStateDependencies(
            connection = connectionPort,
            scope = dependencies.mainScope,
            session = { sessions.current },
            isSessionCurrent = ::isCurrentSession,
            processStore = { dependencies.store },
            draftStore = dependencies.draftStore,
            connectionUi = ConnectionUiState(dependencies.mainScope, dependencies.runtimeClock, dependencies.accessibility),
            batteryMonitor = BatteryMonitor(dependencies.mainScope, dependencies.runtimeClock),
            resyncFailure = ResyncFailureRegistry { handler -> resyncFailureHub.handler = handler },
            registryFactory = { store ->
                ChatCoordinatorRegistry(store, dependencies.mainScope)
            },
            primerFactory = dependencies.primerFactory ?: ChatPrimerFactory { primerDependencies ->
                com.meshcoreone.android.app.state.ChatTimelinePrimer(
                    primerDependencies, CoordinatedPrimeTimeline(chatsFeature, uiScope),
                    dependencies.chatContent?.primerWarmer,
                    dependencies.chatContent?.primerPreferences
                        ?: com.meshcoreone.android.app.state.LinkPreviewPreferences { false },
                )
            },
            chatEnvInputsFactory = { kind, snapshot -> chatsFeature.envInputs(kind, snapshot) },
            navigation = navigation,
            platform = statePlatform,
            regionStore = dependencies.regionStore,
            stalePreferences = dependencies.stalePreferences,
            clock = dependencies.runtimeClock,
            closeChatRoute = { closeSelectedChatRoute() },
            themeService = dependencies.themeService,
            isAppForeground = { foreground.isForeground },
        ),
    )

    /** Process-scoped map data binding; the feature owns rendering and never reaches into the service graph. */
    val mapFeature by lazy(::createMapFeatureDependencies)
    val nodesFeature by lazy(::createNodesFeatureDependencies)
    val remoteNodesFeature by lazy(::createRemoteNodesFeatureDependencies)
    val chatsFeature by lazy { AppChatsFeature(this) }
    val themeService get() = dependencies.themeService
    val backupEngine get() = dependencies.backupEngine
    internal val chatContent get() = dependencies.chatContent
    internal val notificationPreferences get() = dependencies.notificationPreferences
    internal val settingsRegionResolver get() = dependencies.onboardingPlatform?.regionResolver
    internal val advertLocation get() = dependencies.advertLocation
    internal val nodesLocations get() = dependencies.nodesLocations
    var settingsFeature: AppSettingsFeature? = null
        internal set
    internal val nodesMessages get() = dependencies.nodesMessages
    internal val nodesAnnouncer get() = dependencies.nodesAnnouncer
    internal val nodesPreferences get() = dependencies.nodesPreferences
    val lineOfSight = dependencies.lineOfSight
    internal val offlineMaps: OfflineMapController
        get() = checkNotNull(dependencies.offlineMaps) { "Production map dependencies were not installed" }

    /**
     * Foreground-service hosting and companion-presence routing, present only when the platform supplies a service
     * starter and environment. Holds the service while a live connection is wanted and the app is visible.
     */
    val hostBinding: ConnectedDeviceHostBinding? = dependencies.hostingStarter?.let { starter ->
        dependencies.hostEnvironment?.let { environment ->
            ConnectedDeviceHostBinding(
                connectionManager.snapshot, foreground.foregroundFlow, { connectionManager.snapshot.value },
                ConnectedDeviceHostingController(starter), environment, PresenceRouting(), processScope,
                onFailure = { failure -> dependencies.connectivityDiagnostics.report("hosting", failure) },
            )
        }
    }

    /** Onboarding ports bound to this container's services; null without an [AppContainerDependencies.onboardingPlatform]. */
    val onboarding: com.meshcoreone.android.app.container.onboarding.AppOnboarding? by lazy {
        dependencies.onboardingPlatform?.let {
            com.meshcoreone.android.app.container.onboarding.AppOnboardingFactory.create(this, it, dependencies.mainScope)
        }
    }

    /** The companion setup service (for `setChooserHost` and the chooser result), when a registry exists. */
    val companionSetup = dependencies.companionSetup

    internal suspend fun handleNotificationResponse(response: NotificationResponse): Boolean {
        val current = sessions.current ?: return false
        if (appState.connectionState != com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY ||
            !statePlatform.isReadyFor(current)
        ) return false
        if (response.action != null) {
            if (current.notificationService.didReceiveWithResult(response) == NotificationResponseDispatch.ACTION_CALLBACK_MISSING) {
                java.util.logging.Logger.getLogger("com.mc1.NotificationActions")
                    .fine("Notification action forwarder is unavailable; retaining for retry")
                return false
            }
            return true
        }
        if (response.payload is NotificationPayload.LowBattery) {
            current.notificationService.didReceive(response)
            return true
        }
        val receipt = NotificationNavigationReceipt()
        val dispatch = withContext(receipt) { current.notificationService.didReceiveWithResult(response) }
        return when (val outcome = receipt.outcome) {
            null -> {
                when {
                    receipt.callbackStarted -> false
                    dispatch == NotificationResponseDispatch.TAP_CALLBACK_INVOKED -> true
                    else -> isCurrentSession(current)
                }
            }
            is NavigationOutcome.Failed -> outcome.failure != NavigationFailure.StaleGeneration
            NavigationOutcome.NotReady, NavigationOutcome.NoPendingRoute -> false
            else -> true
        }
    }

    /** Forwards a chooser activity result to the platform gateway; a no-op without a companion registry. */
    fun onChooserResult(requestId: Long, resultCode: Int, data: android.content.Intent?) {
        dependencies.chooserResult?.invoke(requestId, resultCode, data)
    }

    private val revocationGuard: PermissionRevocationGuard? = dependencies.permissionSnapshot?.let { read ->
        PermissionRevocationGuard(
            read,
            object : TransportTeardown {
                override val liveTransport: LiveTransport
                    get() {
                        if (connectionManager.isSimulatorConnection) return LiveTransport.NONE
                        val methods = connectionManager.connectedDevice?.connectionMethods ?: return LiveTransport.NONE
                        if (!connectionManager.connectionState.isConnected) return LiveTransport.NONE
                        return if (methods.isNotEmpty() && methods.all { it is com.meshcoreone.android.core.model.ConnectionMethod.WiFi }) {
                            LiveTransport.LAN
                        } else LiveTransport.BLUETOOTH
                    }

                override suspend fun disconnectTransport() = connectionManager.disconnectTransport()
            },
        ) { revoked -> dependencies.connectivityDiagnostics.report("permissions.revoked.$revoked", null) }
    }

    init {
        dependencies.chatContent?.bindSimulatorConnection { connectionManager.isSimulatorConnection }
        observerBridge.bind(appState, connectionPort) { token ->
            val current = sessions.current
            if (appState.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                current != null && current.token == token && statePlatform.isReadyFor(current)
            ) dependencies.onNotificationNavigationReady()
        }
    }

    /** Starts the process: binds hosting, loads persisted state and activates the runtime (restores the last connection). */
    fun start(): Job {
        hostBinding?.start()
        disconnectWatcher.start()
        notificationReadyWatcher.start()
        watchPermissions()
        return dependencies.mainScope.launch { appState.initialize() }
    }

    /** A rebuild's transient disconnected edge retires its binding, not its retained UI selection. */
    private fun watchDisconnects(): Job =
        uiScope.launch {
            connectionManager.snapshot.map { it.state }.distinctUntilChanged()
                .filter { it == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED }
                .collect {
                    if (connectionManager.activeReconnectDeviceId == null) appState.reconcileSessionLoss()
                }
        }

    private fun watchNotificationReadiness(): Job =
        uiScope.launch {
            val subscription = connectionManager.subscribeTransitions()
            try {
                var previousReady: SessionToken? = null
                fun observe(snapshot: ConnectionSnapshot) {
                    val ready = snapshot.token.takeIf { snapshot.state == DeviceConnectionState.READY }
                    if (ready != null && ready != previousReady) scheduleNotificationReadyRetry(ready)
                    previousReady = ready
                }
                observe(subscription.initial)
                subscription.transitions.collect { observe(it) }
            } finally {
                subscription.close()
            }
        }

    private fun scheduleNotificationReadyRetry(token: SessionToken) {
        // A transition may resume its collector while the manager publishes under its lock.
        processScope.launch(Dispatchers.Default) {
            withContext(dependencies.notificationReadyDispatcher) {
                val ready = withNavigationCommitBoundary {
                    val snapshot = connectionManager.snapshot.value
                    val current = sessions.current
                    snapshot.state == DeviceConnectionState.READY && snapshot.token == token &&
                        current != null && current.token == token && statePlatform.isReadyFor(current)
                }
                if (ready) {
                    try {
                        dependencies.onNotificationNavigationReady()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        dependencies.connectivityDiagnostics.report("notificationNavigation.ready", failure)
                    }
                }
            }
        }
    }

    /** Checks permission revocation on each foreground edge (the first read becomes the baseline). */
    private fun watchPermissions() {
        val guard = revocationGuard ?: return
        dependencies.mainScope.launch {
            foreground.foregroundFlow.filter { it }.collect {
                try {
                    guard.check()
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    dependencies.connectivityDiagnostics.report("permissions.check", failure)
                }
            }
        }
    }

    private inner class PresenceRouting : PresenceEffects {
        override suspend fun reconnect(deviceId: java.util.UUID) = connectionPort.connect(deviceId, forceReconnect = false)
        override fun refreshAssociations() = dependencies.refreshAssociations()
        override suspend fun forgetAssociation(deviceId: java.util.UUID) =
            pairing.devicePairingDidRemoveDevice(dependencies.connectivity.pairing, deviceId)
        override fun permissionRevoked(capability: Capability) =
            dependencies.connectivityDiagnostics.report("presence.permissionRevoked.$capability", null)
        override fun deferredUntilUnlock(deviceId: java.util.UUID) =
            dependencies.connectivityDiagnostics.report("presence.deferredUntilUnlock", null)
    }

    /**
     * `NavigationCoordinator` has no seam that clears only the chats route, so this pops the top chat of the Chats
     * stack when it is the selected one (see WP-303.md coordinator note).
     */
    private fun closeSelectedChatRoute() {
        val state = navigation.state.value
        val top = state.stacks[AppTab.CHATS]?.lastOrNull()?.destination
        if (state.selectedTab == AppTab.CHATS && top is NavigationDestination.Chat &&
            top.selection == state.chatsSelectedRoute && state.chatsSelectedRoute is ChatSelection.Channel
        ) navigation.back()
    }

    /** Process shutdown: closes the runtime (tearing down any live graph), then every process resource. */
    suspend fun close() {
        withContext(NonCancellable) {
            simulatorPreferenceRecovery?.close()
            dependencies.onboardingPlatform?.flags?.shutdown()
            hostBinding?.close()
            appState.shutdown()
            statePlatform.close()
            connectionManager.close()
            processJob.cancelAndJoin()
            navigation.settlePublishedChatContactReceipts()
            dependencies.onClose()
        }
    }

    private class ContactRemoval(private val radioId: RadioId, private val session: com.meshcoreone.android.app.state.AppSession) :
        ContactRemovalPort {
        override suspend fun removeContact(radioId: RadioId, publicKey: Bytes) = session.contactService.removeContact(radioId, publicKey)
        override suspend fun removeLocalContact(contactId: java.util.UUID, publicKey: Bytes) =
            session.contactService.removeLocalContact(EntityKey(radioId, contactId), publicKey)
    }
}

/** Holds the resync-failure hook ConnectionUiState installs, and fires it from the session's retry loop. */
internal class ResyncFailureHub : ResyncFailureSignal {
    @Volatile var handler: (() -> Unit)? = null
    override fun resyncFailed() { handler?.invoke() }
}

/** Adds the process-global debug-log flush to the platform hooks app state calls. */
private class ContainerAppStatePlatform(
    private val delegate: AppStatePlatform,
    private val navigation: NavigationCoordinator,
    store: PersistenceStoreProtocol,
    private val onNavigationReady: () -> Unit,
    private val isCurrentSession: (com.meshcoreone.android.app.state.AppSession) -> Boolean,
) : AppStatePlatform by delegate, AutoCloseable {
    private val lookup = PersistenceNavigationLookup(store)
    private data class InstalledBinding(
        val session: com.meshcoreone.android.app.state.AppSession,
        val owner: NavigationCleanupOwner,
        val binding: NotificationNavigationBinding,
    )

    @Volatile private var installed: InstalledBinding? = null
    @Volatile private var closed = false

    fun isReadyFor(current: com.meshcoreone.android.app.state.AppSession): Boolean {
        val binding = installed ?: return false
        val state = navigation.currentState
        return isCurrentSession(current) && binding.session === current && binding.owner.generation == state.generation &&
            state.boundSessionToken == current.token && state.navigationReady
    }

    override fun configureNotificationNavigation(
        session: com.meshcoreone.android.app.state.AppSession,
        connectedDevice: () -> com.meshcoreone.android.core.model.DeviceDTO?,
    ) {
        if (!isCurrentSession(session)) return
        delegate.configureNotificationNavigation(session, connectedDevice)
        var adopted: InstalledBinding? = null
        var existingOwner: NavigationCleanupOwner? = null
        navigation.retireBinding {
            if (!closed && isCurrentSession(session)) {
                val owner = navigation.captureCleanupOwner()
                if (owner.sessionToken == session.token && owner.radioId == session.token.radioId) {
                    existingOwner = owner
                    installed?.takeIf { it.session === session && it.owner == owner }?.let { adopted = it }
                }
            }
        }
        if (adopted == null) {
            val owner = existingOwner ?: navigation.bindNotificationSessionOwned(
                session.token.radioId, connectedDevice()?.publicKey, session.token,
            ) { !closed && isCurrentSession(session) } ?: return
            var previous: InstalledBinding? = null
            navigation.retireBinding {
                if (!closed && isCurrentSession(session) && navigation.captureCleanupOwner() == owner) {
                    val next = InstalledBinding(
                        session, owner,
                        NotificationNavigationBinding(
                            session.notificationService, navigation, lookup, connectedDevice,
                            onOutcome = { outcome ->
                                if (outcome is com.meshcoreone.android.app.navigation.NavigationOutcome.Failed) {
                                    java.util.logging.Logger.getLogger("com.mc1.Navigation").warning(
                                        "Notification navigation failed: ${outcome.failure.javaClass.simpleName}",
                                    )
                                }
                            },
                            isCurrentSession = { isCurrentSession(session) },
                        ),
                    )
                    previous = installed
                    installed = next
                    adopted = next
                }
            }
            previous?.binding?.close()
        }
        val current = adopted ?: return
        if (!navigation.setNavigationReadyWhen(true) {
                !closed && installed === current && isCurrentSession(session) &&
                    navigation.captureCleanupOwner() == current.owner
            }) return
        onNavigationReady()
    }

    override suspend fun onConnectionLost() {
        val owner = installed?.owner
        if (owner != null) retireOwned { it.owner == owner }
        delegate.onConnectionLost()
    }

    override suspend fun onConnectionLost(sessionToken: SessionToken?, navigationGeneration: Long) {
        retireOwned { it.owner.sessionToken == sessionToken && it.owner.generation == navigationGeneration }
        delegate.onConnectionLost(sessionToken, navigationGeneration)
    }

    fun retire(retired: com.meshcoreone.android.app.state.AppSession) {
        retireOwned { it.session === retired }
    }

    private fun retireOwned(matches: (InstalledBinding) -> Boolean) {
        var retiring: InstalledBinding? = null
        navigation.retireBinding {
            val current = installed
            if (current != null && matches(current)) {
                retiring = current
                installed = null
            }
        }
        retiring?.let { current ->
            navigation.setNavigationReadyWhen(false) { navigation.captureCleanupOwner() == current.owner }
            current.binding.close()
        }
    }

    override fun close() {
        var retiring: InstalledBinding? = null
        navigation.retireBinding {
            closed = true
            retiring = installed
            installed = null
        }
        navigation.setNavigationReady(false)
        retiring?.binding?.close()
    }

    override suspend fun flushDebugLog() {
        DebugLogBuffer.shared?.flush()
        delegate.flushDebugLog()
    }
}

/**
 * Routes the runtime's observer callbacks to app state on the main stand-in. The bridge exists before app state
 * (the runtime needs its observer at construction), so [bind] installs the targets afterwards; no callback can
 * fire before the first connect, which only starts after the container is built.
 */
internal class ObserverBridge(private val foreground: ProcessForegroundState, private val mainContext: CoroutineContext) {
    @Volatile private var appState: AppState? = null
    @Volatile private var port: ConnectionManagerAppPort? = null
    @Volatile private var onDeviceSynced: (SessionToken) -> Unit = {}
    private val scope = CoroutineScope(mainContext + SupervisorJob())

    fun bind(appState: AppState, port: ConnectionManagerAppPort, onDeviceSynced: (SessionToken) -> Unit = {}) {
        this.appState = appState
        this.port = port
        this.onDeviceSynced = onDeviceSynced
    }

    private suspend fun onMain(block: suspend (AppState, ConnectionManagerAppPort) -> Unit) {
        val state = appState ?: return
        val connection = port ?: return
        withContext(mainContext) { block(state, connection) }
    }

    val observer = ConnectionObserver(
        onServicesAvailable = { onMain { state, connection -> connection.refreshLastConnection(); state.onSessionStateChanged() } },
        onConnectionLost = { onMain { state, connection -> connection.refreshLastConnection(); state.onSessionStateChanged() } },
        onAutoReconnectStarted = { onMain { state, _ -> state.onAutoReconnectStarted() } },
        onDeviceSynced = { token -> onMain { state, _ -> state.onDeviceSynced(); onDeviceSynced(token) } },
        onAuthenticationFailure = { id -> scope.launch { appState?.handleAuthenticationFailure(id, foreground.isForeground) } },
        onLastDeviceCleared = { scope.launch { port?.refreshLastConnection(); appState?.onLastConnectedDeviceCleared() } },
    )
}
