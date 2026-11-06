// AndroidOnly: WP-303 Builds OnboardingFeatureDependencies from the AppContainer and supplies the activity seams.
// AndroidOnly: WP-211 Supply app-localized resources to the mounted regional catalog.
// AndroidOnly: WP-305 Retain first-run and isolated Settings rerun flow sessions across Activity recreation.
package com.meshcoreone.android.app.container.onboarding

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.state.ConnectionUiSnapshot
import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.permissions.AndroidPermissionState
import com.meshcoreone.android.core.connectivity.permissions.PermissionSnapshot
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.Generation
import com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt
import com.meshcoreone.android.core.contracts.domain.ProcessEpoch
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.app.content.LocationPermissionResultReporting
import com.meshcoreone.android.core.services.content.RegionResolver
import com.meshcoreone.android.core.services.device.RadioPreset
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.feature.onboarding.OnboardingDemoPort
import com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies
import com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator
import com.meshcoreone.android.feature.onboarding.InMemoryOnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingPairingPort
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionPort
import com.meshcoreone.android.feature.onboarding.OnboardingPresetPort
import com.meshcoreone.android.feature.onboarding.OnboardingPresetTarget
import com.meshcoreone.android.feature.onboarding.OnboardingRegionPort
import com.meshcoreone.android.feature.onboarding.OnboardingWiFiPort
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Process-lifetime platform pieces the container cannot derive itself; built once by the container factory. */
class OnboardingPlatform(
    val flags: WriteBehindOnboardingFlags,
    val permissionFacts: OnboardingPermissionFacts,
    val requests: RequestedPermissions = RequestedPermissions(),
    val regionResolver: RegionResolver? = null,
    val locationReporter: LocationPermissionResultReporting? = null,
    val scans: BleScanCoordinator? = null,
    val openAppSettings: () -> Unit = {},
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val regionResources: (() -> Resources)? = null,
)

/** [OnboardingPermissionFacts] over the real platform readers. */
class AndroidOnboardingPermissionFacts(
    private val context: Context,
    private val state: AndroidPermissionState = AndroidPermissionState(context),
) : OnboardingPermissionFacts {
    override fun snapshot(): PermissionSnapshot = state.snapshot()
    override fun locationGranted(): Boolean =
        context.checkSelfPermission(OnboardingPermissionMapping.COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** Opens this app's system settings page (the target of every "Open Settings" action). */
fun appSettingsOpener(context: Context): () -> Unit = {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** [OnboardingConnectionHost] over the process app state, pairing coordinator and runtime manager. */
class AppStateOnboardingHost(private val container: AppContainer) : OnboardingConnectionHost {
    private val appState get() = container.appState
    override val currentState: DeviceConnectionState get() = container.connectionManager.connectionState
    override fun connectionStates(): Flow<DeviceConnectionState> =
        container.connectionManager.snapshot.map { it.state }.distinctUntilChanged()
    override val ui: StateFlow<ConnectionUiSnapshot> get() = appState.connectionUI.state
    override val isPairingActive: Boolean get() = container.connectionPort.isPairingFlowActive
    override val registeredAccessoryCount: Int get() = container.pairing.pairing.registeredDeviceCount
    override val hasLastConnectedDevice: Boolean get() = container.connectionPort.lastConnectedDeviceId != null
    override val isDemoDevice: Boolean get() = container.connectionManager.isSimulatorConnection

    override suspend fun startPairing(): com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome {
        val completed = appState.startDeviceScanAndAwait()
        return OnboardingConnectMapping.afterPairing(currentState, ui.value, completed)
    }

    override suspend fun retryConnect(deviceId: UUID) {
        try {
            container.connectionPort.connect(deviceId, forceReconnect = true)
            appState.wireServicesIfConnected()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            appState.connectionUI.presentSavedDeviceConnectFailure(deviceId, failure)
            throw failure
        }
    }

    override suspend fun connectWiFi(host: String, port: UShort) = appState.connectViaWiFi(host, port)
    override suspend fun clearStaleRegistrations() = container.pairing.pairing.clearStaleRegistrations()

    override fun consumeFailure() {
        appState.connectionUI.clearPairingFailure()
        appState.connectionUI.otherAppWarningDeviceId = null
    }
}

/** [OnboardingRadioConfigurator] over the live session's settings service and the connected-device editor. */
class AppStateRadioConfigurator(private val container: AppContainer) : OnboardingRadioConfigurator {
    // SettingsService serializes radio writes separately from the token-bound Room metadata edit.
    private val presetOperation = Mutex()

    override fun sealCompletion(
        target: OnboardingPresetTarget?,
        attempt: OnboardingCompletionAttempt,
    ): Boolean {
        if (target == null) return false
        return container.connectionManager.sealOnboardingCompletion(
            SessionToken(
                ProcessEpoch(target.epoch), Generation(target.generation), RadioId(target.radioId),
            ),
            attempt,
        )
    }
    override fun connectionStates(): Flow<DeviceConnectionState> =
        container.connectionManager.snapshot.map { it.state }.distinctUntilChanged()
    override val isDemoRadio: Boolean get() = container.connectionManager.isSimulatorConnection
    override fun currentTarget(): OnboardingPresetTarget? =
        container.connectionManager.snapshot.value.takeIf { it.state.isConnected }?.token?.let { token ->
            OnboardingPresetTarget(token.epoch.value, token.generation.value, token.radioId.value)
        }

    override suspend fun applyPreset(preset: RadioPreset) = applyPreset(preset, currentTarget())

    override suspend fun applyPreset(preset: RadioPreset, expectedTarget: OnboardingPresetTarget?) {
        presetOperation.withLock {
            val session = container.appState.services
                ?: throw SettingsServiceException(SettingsServiceError.NotConnected)
            val token = session.token
            if (expectedTarget != OnboardingPresetTarget(
                    token.epoch.value, token.generation.value, token.radioId.value,
                )
            ) {
                throw SettingsServiceException(SettingsServiceError.NotConnected)
            }
            val pathHashMode =
                preset.pathHashMode.takeIf { container.connectionManager.connectedDevice?.supportsPathHashMode == true }
            val info = session.settingsService.applyRadioPresetVerified(preset)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            if (!container.connectionPort.updateDevice(info, preset.id, token)) {
                throw SettingsServiceException(SettingsServiceError.NotConnected)
            }
            pathHashMode?.let { mode ->
                if (!container.connectionPort.updatePathHashMode(mode, token)) {
                    throw SettingsServiceException(SettingsServiceError.NotConnected)
                }
            }
        }
    }
}

/**
 * The app-bound [OnboardingFeatureDependencies]. Permission requests go through an activity-result launcher
 * (`RequestMultiplePermissions`) and system back through `BackHandler`: the seams the feature module cannot compile.
 */
class AppOnboarding(
    override val flags: OnboardingFlagStore,
    override val permissions: AndroidOnboardingPermissionPort,
    override val pairing: OnboardingPairingPort,
    override val wifi: OnboardingWiFiPort,
    override val demo: OnboardingDemoPort,
    override val region: OnboardingRegionPort,
    override val presets: OnboardingPresetPort,
    private val settingsOpener: () -> Unit,
    private val locationReporter: LocationPermissionResultReporting?,
    private val sdkInt: Int,
    /** Process-owned scope so completion and preset work outlive an Activity composition. */
    private val flowScope: CoroutineScope,
    /** The first-run gate: true once the persisted completion flag is set. */
    val hasCompleted: StateFlow<Boolean>,
    val pairingState: com.meshcoreone.android.app.state.AppState? = null,
) : OnboardingFeatureDependencies {
    val wasCompletedAtLoad: Boolean = hasCompleted.value
    private var firstRunFlowCoordinator: OnboardingFlowCoordinator? = null
    private var retiringFirstRunFlow: OnboardingFlowCoordinator? = null
    private val firstRunRevision = MutableStateFlow(0L)
    override val flowCoordinatorRevision: StateFlow<Long> = firstRunRevision.asStateFlow()
    private val rerunDependencies by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { Rerun(this) }

    override fun openAppSettings() = settingsOpener()

    @Synchronized
    override fun retainedFlowCoordinator(): OnboardingFlowCoordinator {
        retiringFirstRunFlow?.let {
            if (it.onboarding.hasUnsettledCompletion) return it
            releaseRetiringCoordinator(it)
        }
        return firstRunFlowCoordinator ?: OnboardingFlowCoordinator(flowScope, this).also {
            it.restoreStartingPath()
            firstRunFlowCoordinator = it
        }
    }

    @Synchronized
    override fun ownsFlowCoordinator(coordinator: OnboardingFlowCoordinator): Boolean =
        firstRunFlowCoordinator === coordinator

    @Synchronized
    override fun onFlowCompletionHandled(coordinator: OnboardingFlowCoordinator) {
        if (firstRunFlowCoordinator === coordinator) {
            coordinator.onboarding.retireCompletion()
            firstRunFlowCoordinator = null
            if (coordinator.onboarding.hasUnsettledCompletion) {
                retiringFirstRunFlow = coordinator
                flowScope.launch {
                    coordinator.onboarding.unsettledCompletionFlow.first { !it }
                    synchronized(this@AppOnboarding) {
                        releaseRetiringCoordinator(coordinator)
                    }
                }
            } else if (!flags.getBoolean(com.meshcoreone.android.feature.onboarding.OnboardingState.KEY_HAS_COMPLETED)) {
                firstRunRevision.value = Math.incrementExact(firstRunRevision.value)
            }
        }
    }

    private fun releaseRetiringCoordinator(coordinator: OnboardingFlowCoordinator) {
        if (retiringFirstRunFlow === coordinator) {
            retiringFirstRunFlow = null
            if (!flags.getBoolean(com.meshcoreone.android.feature.onboarding.OnboardingState.KEY_HAS_COMPLETED)) {
                firstRunRevision.value = Math.incrementExact(firstRunRevision.value)
            }
        }
    }

    /** Records a finished runtime request: the location grant first (the producer reads it), then the snapshot. */
    fun onPermissionResult(kind: OnboardingPermissionKind, grants: Map<String, Boolean>) {
        if (kind == OnboardingPermissionKind.LOCATION) locationReporter?.reportPermissionResult(grants.values.any { it })
        permissions.onRequestFinished(kind)
    }

    @Composable
    override fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit {
        var pending by rememberSaveable { mutableStateOf<OnboardingPermissionKind?>(null) }
        val latest by rememberUpdatedState(onResult)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            pending?.let { onPermissionResult(it, grants) }
            pending = null
            latest()
        }
        return remember(launcher) {
            { kind ->
                val names = permissions.permissionsFor(kind, sdkInt)
                if (names.isEmpty()) {
                    onPermissionResult(kind, emptyMap())
                    latest()
                } else {
                    pending = kind
                    launcher.launch(names.toTypedArray())
                }
            }
        }
    }

    @Composable
    override fun InterceptBack(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

    /**
     * Legacy single-entry rerun owner with in-memory completion flags. Multi-entry hosts must retain one
     * [createRerunOwner] result per navigation entry.
     */
    fun forRerun(): OnboardingFeatureDependencies = rerunDependencies

    /**
     * Creates an isolated Settings-rerun owner. The navigation host must retain the returned instance by
     * navigation-entry identity across Activity recreation and retire only that entry's owner on removal.
     */
    fun createRerunOwner(): OnboardingFeatureDependencies = Rerun(this)

    private class Rerun(private val base: AppOnboarding) : OnboardingFeatureDependencies by base {
        private val flowRevision = MutableStateFlow(0L)
        override val flowCoordinatorRevision: StateFlow<Long> = flowRevision.asStateFlow()
        private var sessionFlags: OnboardingFlagStore = InMemoryOnboardingFlagStore()
        private var flowCoordinator: OnboardingFlowCoordinator? = null
        private val retiringFlowCoordinators = mutableSetOf<OnboardingFlowCoordinator>()

        override val flags: OnboardingFlagStore get() = sessionFlags

        @Synchronized
        override fun retainedFlowCoordinator(): OnboardingFlowCoordinator {
            flowCoordinator?.let { return it }
            return OnboardingFlowCoordinator(base.flowScope, this)
                .also {
                    it.restoreStartingPath()
                    flowCoordinator = it
                }
        }

        @Synchronized
        override fun ownsFlowCoordinator(coordinator: OnboardingFlowCoordinator): Boolean =
            flowCoordinator === coordinator

        @Synchronized
        override fun onFlowCompletionHandled(coordinator: OnboardingFlowCoordinator) {
            retireFlowCoordinator(coordinator)
        }

        @Synchronized
        override fun onFlowCoordinatorEntryRemoved(coordinator: OnboardingFlowCoordinator) {
            retireFlowCoordinator(coordinator)
        }

        private fun retireFlowCoordinator(coordinator: OnboardingFlowCoordinator) {
            if (flowCoordinator !== coordinator) return
            coordinator.onboarding.retireCompletion()
            flowCoordinator = null
            sessionFlags = InMemoryOnboardingFlagStore()
            flowRevision.value = Math.incrementExact(flowRevision.value)
            if (coordinator.onboarding.hasUnsettledCompletion && retiringFlowCoordinators.add(coordinator)) {
                base.flowScope.launch {
                    coordinator.onboarding.unsettledCompletionFlow.first { !it }
                    synchronized(this@Rerun) { retiringFlowCoordinators.remove(coordinator) }
                }
            }
        }

        @Composable
        override fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit =
            base.rememberPermissionRequester(onResult)

        @Composable
        override fun InterceptBack(enabled: Boolean, onBack: () -> Unit) = base.InterceptBack(enabled, onBack)
    }
}

object AppOnboardingFactory {
    /** Binds every onboarding port to [container]; [scope] owns the derived state flows (process lifetime). */
    fun create(
        container: AppContainer, platform: OnboardingPlatform, scope: CoroutineScope,
        demoManager: DemoModeManager = DemoModeManager.shared,
    ): AppOnboarding {
        val host = AppStateOnboardingHost(container)
        val service = container.pairing.pairing as? BluetoothScanPairingService
        val scanFallback = service?.let { svc -> platform.scans?.let { ScanFallbackPort(svc, it) } }
        val permissions = AndroidOnboardingPermissionPort(
            platform.permissionFacts, platform.requests, scanFallback = service != null,
        )
        return AppOnboarding(
            flags = platform.flags,
            permissions = permissions,
            pairing = AppOnboardingPairingPort(host, scope, scanFallback),
            wifi = AppOnboardingWiFiPort(host),
            demo = AppOnboardingDemoPort(demoManager, scope) {
                container.connectionManager.connect(ConnectionTarget.Simulator)
            },
            region = AppOnboardingRegionPort(
                ServicesRegionCatalog(resources = platform.regionResources), container.appState.regionSelectionFlow,
                { container.appState.regionSelection = it }, platform.regionResolver?.let { resolver -> suspend { resolver.resolve() } },
            ),
            presets = AppOnboardingPresetPort(AppStateRadioConfigurator(container), scope),
            settingsOpener = platform.openAppSettings,
            locationReporter = platform.locationReporter,
            sdkInt = platform.sdkInt,
            flowScope = scope,
            hasCompleted = platform.flags.hasCompleted,
            pairingState = container.appState,
        )
    }
}
