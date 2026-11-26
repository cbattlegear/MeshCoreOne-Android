// AndroidOnly: WP-407 Settings adapters over the process preferences/store and current connection-owned services.
// AndroidOnly: WP-312 Maps settings observe the shared preference migration and authoritative values.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.*
import com.meshcoreone.android.core.runtime.ConnectionManager
import com.meshcoreone.android.core.services.device.RadioPreset
import com.meshcoreone.android.core.services.device.RadioPresets
import com.meshcoreone.android.core.services.device.RegionalAreas
import com.meshcoreone.android.core.services.device.RegionDiscoveryService
import com.meshcoreone.android.core.services.device.SettingsService
import com.meshcoreone.android.core.ui.percentage
import com.meshcoreone.android.feature.settings.device.*
import com.meshcoreone.android.feature.settings.device.GeneratedIdentity
import com.meshcoreone.android.feature.settings.app.nodeconfig.*
import com.meshcoreone.android.core.services.remote.NodeConfigJson
import com.meshcoreone.android.core.services.remote.NodeConfigContactSaveFailure
import com.meshcoreone.android.core.services.remote.NodeConfigService
import com.meshcoreone.android.core.services.remote.ImportStep
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.app.container.onboarding.AppOnboardingWiFiPort
import com.meshcoreone.android.app.container.onboarding.AppStateOnboardingHost
import com.meshcoreone.android.app.container.onboarding.OnboardingConnectionHost
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import com.meshcoreone.android.feature.onboarding.WiFiConnectionCoordinator
import com.meshcoreone.android.feature.onboarding.DeviceScanCoordinator
import com.meshcoreone.android.feature.onboarding.PairingBlocker
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.app.state.ConnectionLinkSwitch
import com.meshcoreone.android.app.state.ConnectionLinkWiFiDial
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.select

class AppSettingsFeature private constructor(
    internal val container: AppContainer,
    private val storage: MeshCoreStorage,
    private val initial: PreferenceSnapshot,
) {
    var whatsNew: AppWhatsNew? = null
        private set
    private val scope get() = container.uiScope
    private val app get() = container.appState
    internal val radioWiFi = RadioWiFiController(
        scope,
        currentService = ::currentRadioWiFiService,
        isReady = { container.connectionManager.snapshot.value.state == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY },
        transport = { container.currentTransport },
    )
    private fun currentRadioWiFiService(): com.meshcoreone.android.core.services.device.RadioWiFiService? {
        val snapshot = container.connectionManager.snapshot.value
        val session = container.sessions.current
        return if (snapshot.state == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
            session != null && snapshot.token == session.token
        ) session.radioWiFiService else null
    }

    internal fun bindRadioWiFi() =
        radioWiFi.bind(currentRadioWiFiService(), app.connectedDevice?.supportsRadioCLI == true)

    private val linkLock = Any()
    internal val linkSwitch = MutableStateFlow<ConnectionLinkSwitch?>(null)
    private var retainedLinkSelection: ConnectionManager.LinkSwitchSelection? = null

    internal suspend fun bluetoothEndpoint(device: DeviceDTO?): UUID? {
        val id = device?.connectionMethods?.filterIsInstance<ConnectionMethod.Bluetooth>()?.firstOrNull()?.peripheralUUID
            ?: return null
        return id.takeIf { container.connectionPort.hasBluetoothEndpoint(it) }
    }

    internal fun switchConnectionLink(target: TransportType, bluetoothId: UUID?) {
        val started = synchronized(linkLock) {
            val device = app.connectedDevice
            val snapshot = container.connectionManager.snapshot.value
            val existing = linkSwitch.value?.let { record ->
                retainedLinkSelection?.let { selection ->
                    record.copy(deviceId = container.connectionManager.committedLinkSwitchDeviceId(selection))
                } ?: record
            }
            if (snapshot.intent == com.meshcoreone.android.core.contracts.domain.ConnectionIntent.UserDisconnected ||
                (device != null && snapshot.token?.radioId != device.radioId)
            ) return@synchronized null
            if (existing != null && snapshot.token?.radioId?.let { it != existing.radioId } == true) {
                linkSwitch.value = null
                retainedLinkSelection = null
                return@synchronized null
            }
            val live = container.currentTransport
            val wifiState = radioWiFi.state.value
            val wifi = ConnectionLinkWiFiDial.capture(
                live, device?.connectionMethods?.filterIsInstance<ConnectionMethod.WiFi>()?.firstOrNull(),
                wifiState.address.takeIf { wifiState.associated == true },
            )
            if (existing == null && target == TransportType.WIFI &&
                (wifiState.support != RadioWiFiSupport.SUPPORTED || wifiState.associated != true || wifi?.canDial != true)
            ) return@synchronized null
            val currentBluetoothId = bluetoothId?.takeIf { id ->
                device?.connectionMethods?.any { it is ConnectionMethod.Bluetooth && it.peripheralUUID == id } == true
            }
            val record = ConnectionLinkSwitch.start(
                existing, target, live, device?.radioId, device?.id,
                currentBluetoothId, wifi, UUID.randomUUID(),
            ) ?: return@synchronized null
            val selection = container.connectionManager.captureLinkSwitchSelection(record.deviceId, record.radioId)
                ?: return@synchronized null
            SelectionAction.LinkSwitch(record, selection, linkSwitch.value?.token)
        } ?: return
        if (target == TransportType.WIFI) requestSelectionPermission(started)
        else {
            if (selectionAction.value is SelectionAction.LinkSwitch) cancelSelectionPermission()
            submitLinkSwitch(started)
        }
    }

    private fun submitLinkSwitch(action: SelectionAction.LinkSwitch) {
        val (record, selection, previousToken) = action
        val target = requireNotNull(record.target)
        scope.launch {
            try {
                container.connectionManager.requireCurrentLinkSwitchSelection(selection)
            } catch (_: CancellationException) {
                return@launch
            }
            if (target == TransportType.WIFI) {
                val permissions = container.onboarding?.connectionPermissions
                permissions?.refresh()
                if (permissions?.snapshot?.value?.localNetwork != PermissionStatus.GRANTED) {
                    requestSelectionPermission(action)
                    return@launch
                }
            }
            try {
                synchronized(linkLock) {
                    container.connectionManager.requireCurrentLinkSwitchSelection(selection)
                    if (linkSwitch.value?.token != previousToken || linkSwitch.value?.target != null) return@launch
                    linkSwitch.value = record
                    retainedLinkSelection = selection
                }
            } catch (_: CancellationException) {
                return@launch
            }
            var failure: Exception? = null
            var wasCancelled = false
            try {
                container.connectionManager.requireCurrentLinkSwitchSelection(selection)
                when (target) {
                    TransportType.BLUETOOTH -> {
                        container.connectionPort.switchToBluetooth(
                            requireNotNull(record.bluetoothDeviceId), selection,
                        )
                        app.wireServicesIfConnected()
                    }
                    TransportType.WIFI -> requireNotNull(record.wifi).let { dial ->
                        app.connectionUI.hideDisconnectedPill()
                        container.connectionPort.switchToWiFi(
                            requireNotNull(dial.displayHost), dial.port, selection,
                        )
                        app.wireServicesIfConnected()
                    }
                    TransportType.SIMULATOR -> error("Simulator is not a physical connection link")
                }
            } catch (cancelled: CancellationException) {
                wasCancelled = true
                throw cancelled
            } catch (problem: Exception) {
                failure = problem
            } finally {
                synchronized(linkLock) {
                    val current = linkSwitch.value ?: return@synchronized
                    if (wasCancelled && current.token == record.token) {
                        linkSwitch.value = null
                        retainedLinkSelection = null
                        return@synchronized
                    }
                    val completion = current.complete(
                        record.token, failure != null, container.currentTransport, app.connectedDevice?.radioId,
                    )
                    when (completion) {
                        ConnectionLinkSwitch.Completion.Ignored -> Unit
                        ConnectionLinkSwitch.Completion.Cleared -> {
                            linkSwitch.value = null
                            retainedLinkSelection = null
                        }
                        is ConnectionLinkSwitch.Completion.Held -> linkSwitch.value = completion.record.copy(
                            deviceId = container.connectionManager.committedLinkSwitchDeviceId(selection),
                        )
                    }
                    if (failure != null && completion is ConnectionLinkSwitch.Completion.Held) {
                        val id = if (target == TransportType.BLUETOOTH) record.bluetoothDeviceId ?: record.deviceId else record.deviceId
                        app.connectionUI.presentSavedDeviceConnectFailure(id, requireNotNull(failure))
                    }
                }
            }
        }
    }
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val preferences = MutableStateFlow(initial)
    internal val preferenceValues: StateFlow<PreferenceSnapshot> get() = preferences
    internal fun setPreference(key: PreferenceKey.BooleanKey, value: Boolean) {
        write { storage.preferences.set(key, value) }
    }
    internal fun setMapAppearance(value: com.meshcoreone.android.core.maps.MapColorPreference) {
        write { storage.preferences.set(AppStorageKey.mapColorSchemePreference, value.rawValue) }
    }
    internal fun observeMapPreferences(legacy: android.content.SharedPreferences) =
        AppMapPreferences(storage.preferences, legacy).observe(
            com.meshcoreone.android.core.maps.MapFilterHost.MAIN_MAP,
        )
    private val deviceValues = mutableMapOf<UUID, DevicePreferenceSnapshot>()
    val failure = MutableStateFlow<Throwable?>(null)
    private val changes = combine(
        app.servicesVersion, app.sessionStateChangeCount, container.connectionManager.snapshot, container.deviceAccess.changes,
    ) { _, _, _, _ -> Unit }
    private val device = changes.flatMapLatest {
        val current = app.connectedDevice
        if (current == null) flowOf(null) else storage.devicePreferences.observe(current.id).map { value ->
            deviceValues[current.id] = value
            current
        }
    }.catch { report("Device preferences could not load", it); emit(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    init {
        scope.launch {
            container.connectionManager.snapshot.collect { snapshot ->
                synchronized(linkLock) {
                    val record = linkSwitch.value ?: return@synchronized
                    if (snapshot.intent == com.meshcoreone.android.core.contracts.domain.ConnectionIntent.UserDisconnected ||
                        snapshot.token?.radioId?.let { it != record.radioId || record.target == null } == true
                    ) {
                        linkSwitch.value = null
                        retainedLinkSelection = null
                    }
                }
            }
        }
        scope.launch {
            for (write in writes) {
                try { write() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (problem: Exception) { report("Settings persistence failed", problem) }
            }
        }
        scope.launch {
            storage.preferences.snapshots.catch { report("Settings preferences could not load", it) }
                .collect { preferences.value = it }
        }
    }

    internal fun report(operation: String, problem: Throwable?) {
        Logger.getLogger("com.mc1.Settings").log(Level.WARNING, operation, problem)
        if (problem != null) failure.value = problem
    }

    private fun write(action: suspend () -> Unit) { writes.trySend(action).getOrThrow() }

    internal suspend fun deviceSelection(): DeviceSelectionListBuilder.Result {
        val hasRegistry = container.connectionPort.hasSystemPairingRegistry
        val pending = if (hasRegistry) container.pairing.systemAccessoriesMissingDeviceRecord() else emptyList()
        return DeviceSelectionListBuilder.make(
            saved = container.pairing.fetchSavedDevices(),
            accessories = container.pairing.pairedAccessoryInfos.map { it.id to it.name },
            needsSetup = pending.map { SystemPairedAccessory(it.id, it.name) },
            hasSystemPairingRegistry = hasRegistry,
        )
    }

    internal suspend fun devicesConnectedElsewhere(list: DeviceSelectionListBuilder.Result): Set<UUID> =
        list.connectable.filter { device ->
            (device.connectionMethods.isEmpty() || device.connectionMethods.any { !it.isWiFi }) &&
                container.isDeviceConnectedToOtherApp(device.id)
        }.map { it.id }.toSet()

    internal val selectionVisible = MutableStateFlow(false)
    internal fun selectionHostEligible(): Boolean {
        val navigation = container.navigation.state.value
        val destination = navigation.activeStack.last().destination
        return navigation.selectedTab == AppTab.SETTINGS &&
            (destination == NavigationDestination.Root(AppTab.SETTINGS) || destination is NavigationDestination.Setting)
    }
    internal val selectionWifiVisible = MutableStateFlow(false)
    internal val selectionWifi = WiFiConnectionCoordinator(
        scope, AppOnboardingWiFiPort(object : OnboardingConnectionHost by AppStateOnboardingHost(container) {
            override suspend fun connectWiFi(host: String, port: UShort) =
                app.connectViaWiFi(host, port, forceFullSync = true)
        }),
    ) { selectionWifiVisible.value = false }

    internal sealed interface SelectionAction {
        data class Saved(
            val device: DeviceDTO,
            val wifiSelection: ConnectionManager.SavedWiFiSelection?,
        ) : SelectionAction
        data class LinkSwitch(
            val record: ConnectionLinkSwitch,
            val selection: ConnectionManager.LinkSwitchSelection,
            val previousToken: UUID?,
        ) : SelectionAction
        data class Setup(val accessory: SystemPairedAccessory) : SelectionAction
        data object Manual : SelectionAction
        data object Scan : SelectionAction
    }

    internal val selectionAction = MutableStateFlow<SelectionAction?>(null)
    internal val selectionPermissionDenied = MutableStateFlow(false)
    internal val selectionScanBlocker = MutableStateFlow(PairingBlocker.NONE)
    internal val selectionRequestedPermission = MutableStateFlow<OnboardingPermissionKind?>(null)
    private var selectionScanQueued = false
    private var selectionScanOwned = false
    private var selectionScanWasAuthorized = false
    private var selectionScanRevocationRetry = false
    internal val ownsSelectionScan: Boolean get() = selectionScanOwned
    internal var selectionScanJob: Job? = null
        private set
    private val selectionScanGate by lazy {
        val platform = requireNotNull(container.onboarding)
        DeviceScanCoordinator(scope, platform.pairing, platform.demo, platform.permissions, onConnected = {})
    }

    private val selectionPermissionKind: OnboardingPermissionKind?
        get() = when (selectionAction.value) {
            null -> null
            else -> if (selectionUsesBluetooth) OnboardingPermissionKind.BLUETOOTH else OnboardingPermissionKind.LOCAL_NETWORK
        }

    internal val selectionUsesBluetooth: Boolean
        get() = when (val action = selectionAction.value) {
            SelectionAction.Scan, is SelectionAction.Setup -> true
            is SelectionAction.Saved -> action.device.connectionMethods.none { it.isWiFi }
            else -> false
        }

    internal fun showSelectionWifi() {
        selectionWifi.reset()
        selectionWifiVisible.value = true
    }

    internal fun dismissSelectionWifi() {
        if (!selectionWifi.state.value.isConnecting) {
            if (selectionAction.value == SelectionAction.Manual) cancelSelectionPermission()
            selectionWifiVisible.value = false
        }
    }

    internal fun selectSavedDevice(device: DeviceDTO) {
        val wifiSelection = if (device.connectionMethods.any { it.isWiFi }) {
            container.connectionManager.captureSavedWiFiSelection(device.id, device.radioId)
        } else null
        requestSelectionPermission(SelectionAction.Saved(device, wifiSelection))
    }

    internal fun submitSelectionWifi() = requestSelectionPermission(SelectionAction.Manual)

    private fun requestSelectionPermission(action: SelectionAction) {
        if (selectionAction.value != null || selectionWifi.state.value.isConnecting) return
        selectionAction.value = action
        refreshSelectionPermission()
    }

    internal fun refreshSelectionPermission() {
        val platform = container.onboarding
        if (platform == null) {
            if (selectionAction.value == null) return
            cancelSelectionPermission()
            report("Connection permission requester is unavailable", IllegalStateException("Connection permission requester is unavailable"))
            return
        }
        val permissions = platform.permissions
        permissions.refresh()
        platform.connectionPermissions.refresh()
        val snapshot = if (selectionUsesBluetooth && selectionAction.value != SelectionAction.Scan)
            platform.connectionPermissions.snapshot.value else permissions.snapshot.value
        val scanBlocker = selectionScanGate.blockerFor(snapshot)
        selectionScanBlocker.value = scanBlocker
        val scanActive = platform.pairing.scanFallback?.isRequested?.value == true
        if (scanActive && scanBlocker == PairingBlocker.NONE) {
            selectionScanWasAuthorized = true
        } else if (scanActive && scanBlocker == PairingBlocker.BLUETOOTH_PERMISSION_DENIED && selectionScanWasAuthorized) {
            selectionScanWasAuthorized = false
            selectionScanRevocationRetry = true
        }
        if (selectionAction.value == null && scanActive && scanBlocker != PairingBlocker.NONE) {
            selectionAction.value = SelectionAction.Scan
        }
        val action = selectionAction.value ?: return
        if (action == SelectionAction.Scan) {
            when (scanBlocker) {
                PairingBlocker.NONE -> {
                    clearSelectionPermission()
                    if (platform.pairing.scanFallback?.isRequested?.value != true && selectionScanJob?.isActive != true) {
                        selectionScanOwned = platform.pairing.scanFallback != null
                        app.connectionUI.queuedSystemPairingSetup = null
                        val previous = selectionScanJob
                        selectionScanJob = scope.launch {
                            try {
                                previous?.join()
                                ensureActive()
                                if (!selectionHostEligible()) return@launch
                                platform.permissions.refresh()
                                if (selectionScanGate.blockerFor(platform.permissions.snapshot.value) != PairingBlocker.NONE) {
                                    requestSelectionPermission(SelectionAction.Scan)
                                    return@launch
                                }
                                container.connectionPort.stopBleScanning()
                                app.startDeviceScanAndAwait {
                                    selectionScanOwned = false
                                }
                            } finally {
                                if (currentCoroutineContext()[Job] === selectionScanJob) selectionScanOwned = false
                            }
                        }
                    }
                }
                PairingBlocker.BLUETOOTH_PERMISSION -> selectionPermissionDenied.value = false
                PairingBlocker.BLUETOOTH_PERMISSION_DENIED ->
                    selectionPermissionDenied.value = !selectionScanRevocationRetry
                PairingBlocker.BLUETOOTH_OFF -> selectionPermissionDenied.value = true
            }
            return
        }
        if (selectionUsesBluetooth) {
            when (scanBlocker) {
                PairingBlocker.NONE -> {
                    clearSelectionPermission()
                    when (action) {
                        is SelectionAction.Saved -> connectSavedDevice(action.device, action.wifiSelection)
                        is SelectionAction.Setup -> startDeviceSetup(action.accessory)
                        else -> error("Only saved BLE and setup use the connection gate")
                    }
                }
                PairingBlocker.BLUETOOTH_PERMISSION -> selectionPermissionDenied.value = false
                PairingBlocker.BLUETOOTH_PERMISSION_DENIED, PairingBlocker.BLUETOOTH_OFF ->
                    selectionPermissionDenied.value = true
            }
            return
        }
        when (snapshot.localNetwork) {
            PermissionStatus.GRANTED -> {
                clearSelectionPermission()
                when (action) {
                    is SelectionAction.Saved -> connectSavedDevice(action.device, action.wifiSelection)
                    is SelectionAction.LinkSwitch -> submitLinkSwitch(action)
                    SelectionAction.Manual -> selectionWifi.connect()
                    SelectionAction.Scan -> error("Scan uses the Bluetooth gate")
                    is SelectionAction.Setup -> error("Setup uses the Bluetooth gate")
                }
            }
            PermissionStatus.DENIED -> selectionPermissionDenied.value = true
            PermissionStatus.NOT_DETERMINED -> Unit
        }
    }

    internal fun beginSelectionPermissionRequest(): OnboardingPermissionKind? {
        val kind = selectionPermissionKind ?: return null
        if (selectionRequestedPermission.value != null || selectionPermissionDenied.value) return null
        selectionRequestedPermission.value = kind
        return kind
    }

    internal fun selectionPermissionResult() {
        val platform = container.onboarding
        val port = if (selectionAction.value == SelectionAction.Scan) platform?.permissions else platform?.connectionPermissions
        if (selectionRequestedPermission.value != null && port?.wasRequestCancelled == true) {
            selectionRequestedPermission.value = null
            cancelSelectionPermission()
            return
        }
        selectionRequestedPermission.value = null
        selectionScanRevocationRetry = false
        refreshSelectionPermission()
    }

    internal fun cancelSelectionPermission() {
        val scan = selectionAction.value == SelectionAction.Scan
        clearSelectionPermission()
        if (scan) cancelSelectionDiscovery()
    }

    private fun clearSelectionPermission() {
        selectionAction.value = null
        selectionPermissionDenied.value = false
    }

    internal fun connectSavedDevice(
        device: DeviceDTO,
        wifiSelection: ConnectionManager.SavedWiFiSelection? =
            container.connectionManager.captureSavedWiFiSelection(device.id, device.radioId),
    ): Job {
        return scope.launch {
            try {
                val wifi = device.connectionMethods.filterIsInstance<ConnectionMethod.WiFi>().firstOrNull()
                if (wifi != null) {
                    val selection = wifiSelection ?: throw CancellationException("Saved Wi-Fi selection is stale")
                    container.connectionManager.requireCurrentSavedWiFiSelection(selection)
                    app.connectionUI.hideDisconnectedPill()
                    container.connectionPort.connectSavedWiFi(wifi.host, wifi.port, selection, forceFullSync = true)
                    app.wireServicesIfConnected()
                } else container.connectionPort.connect(device.id, forceFullSync = true, forceReconnect = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (problem: Exception) {
                app.connectionUI.presentSavedDeviceConnectFailure(device.id, problem)
            }
        }
    }

    internal fun queueDeviceScan() {
        selectionScanQueued = true
    }

    internal fun selectionSheetDismissed() {
        app.handleDeviceSelectionSheetDismissed()
        if (!selectionScanQueued) return
        selectionScanQueued = false
        if (selectionHostEligible()) {
            requestSelectionPermission(SelectionAction.Scan)
        }
    }

    internal fun cancelSelectionScan() {
        selectionScanQueued = false
        selectionScanWasAuthorized = false
        selectionScanRevocationRetry = false
        if (selectionAction.value == SelectionAction.Scan) clearSelectionPermission()
        if (selectionScanOwned) {
            selectionScanJob?.cancel()
            container.onboarding?.pairing?.scanFallback?.cancel()
        }
        selectionScanOwned = false
    }

    internal fun cancelSelectionDiscovery() {
        cancelSelectionScan()
        container.onboarding?.pairing?.scanFallback?.cancel()
    }

    internal fun selectSelectionScanDevice(id: UUID) {
        requireNotNull(container.onboarding?.pairing?.scanFallback).select(id)
    }

    internal fun queueDeviceSetup(accessory: SystemPairedAccessory) =
        requestSelectionPermission(SelectionAction.Setup(accessory))

    private fun startDeviceSetup(accessory: SystemPairedAccessory) {
        selectionVisible.value = false
        app.connectionUI.queuedDeviceScanAfterSelectionDismiss = false
        app.connectionUI.queuedSystemPairingSetup =
            com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt(
                listOf(com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory(accessory.id, accessory.name)),
            )
        container.connectionPort.isPairingFlowActive = true
        app.handleDeviceSelectionSheetDismissed()
    }

    private fun live(): RadioSessionContainer = container.sessions.current?.takeIf {
        app.canRunSettingsStartupReads && it.token.radioId == app.currentRadioId
    } ?: throw SettingsNotConnectedException()

    private var configHolder: NodeConfigStateHolder? = null
    @Volatile private lateinit var configResources: android.content.res.Resources

    internal fun nodeConfig(resources: android.content.res.Resources): NodeConfigStateHolder {
        configResources = resources
        return configHolder ?: NodeConfigStateHolder(scope, {
            val connection = live()
            val token = connection.token
            object : NodeConfigSession {
                private var identityHandoff: kotlinx.coroutines.CompletableDeferred<Unit>? = null
                override val deviceName = app.connectedDevice?.nodeName.orEmpty()
                override val connectionIdentity: Any = token
                override val reportsWriteOutcomes = true
                override fun requireCurrent() {
                    if (container.sessions.current !== connection || connection.token != token ||
                        !container.connectionManager.snapshot.value.state.isConnected) throw SettingsNotConnectedException()
                }
                private suspend fun <T> bound(action: suspend () -> T): T = coroutineScope {
                    requireCurrent()
                    val work = async { action() }
                    val lost = async {
                        changes.first {
                            container.sessions.current !== connection || connection.token != token ||
                                !container.connectionManager.snapshot.value.state.isConnected
                        }
                    }
                    try {
                        select<T> { work.onAwait { it }; lost.onAwait { throw SettingsNotConnectedException() } }.also { requireCurrent() }
                    } finally {
                        work.cancel()
                        lost.cancel()
                    }
                }
                override suspend fun export(sections: ConfigSections) = bound { connection.nodeConfigService.exportConfig(sections) }
                override suspend fun current() = bound {
                    val info = connection.settingsService.getSelfInfo()
                    MeshCoreNodeConfig(name = info.name, radioSettings = NodeConfigService.buildRadioSettings(info),
                        positionSettings = MeshCoreNodeConfig.PositionSettings(info.latitude.toString(), info.longitude.toString()))
                }
                override suspend fun preview(config: MeshCoreNodeConfig, sections: ConfigSections) =
                    bound { connection.nodeConfigService.previewImport(config, sections).channelsOverwriteExisting }
                override suspend fun apply(config: MeshCoreNodeConfig, sections: ConfigSections, progress: (NodeConfigProgress) -> Unit) =
                    applyTracked(config, sections, {}, progress)
                override suspend fun applyTracked(
                    config: MeshCoreNodeConfig,
                    sections: ConfigSections,
                    onWriteStatus: (NodeConfigWriteStatus) -> Unit,
                    progress: (NodeConfigProgress) -> Unit,
                ) = bound {
                    check(identityHandoff == null) { "A configuration import is already in progress" }
                    val handoff = kotlinx.coroutines.CompletableDeferred<Unit>().also { identityHandoff = it }
                    connection.nodeConfigService.importConfig(config, sections, token.radioId, handoff,
                        onWriteStatus = { onWriteStatus(NodeConfigWriteStatus(it.confirmed, it.uncertain)) }) {
                        val resources = configResources
                        val description = when (val step = it.step) {
                            ImportStep.Position -> resources.getString(S.configImportStepPosition)
                            ImportStep.OtherParameters -> resources.getString(S.configImportStepOtherParameters)
                            ImportStep.PrivateKey -> resources.getString(S.configImportStepPrivateKey)
                            ImportStep.NodeName -> resources.getString(S.configImportStepNodeName)
                            ImportStep.RadioParameters -> resources.getString(S.configImportStepRadioParameters)
                            ImportStep.TxPower -> resources.getString(S.configImportStepTxPower)
                            is ImportStep.Channel -> S.configImportStepChannel(resources, step.name)
                            is ImportStep.Contact -> S.configImportStepContact(resources, step.name)
                        }
                        progress(NodeConfigProgress(it.current, it.total, description))
                    }
                }
                override suspend fun refresh() = bound { connection.settingsService.refreshDeviceInfo(); Unit }
                override fun finishImport() {
                    identityHandoff?.complete(Unit)
                    identityHandoff = null
                }
            }
        }, object : NodeConfigCodec {
            override fun decode(bytes: ByteArray) = NodeConfigJson.decode(bytes)
            override fun encode(config: MeshCoreNodeConfig) = NodeConfigJson.encodeToBytes(config)
        }, ResourceNodeConfigMessages({ configResources }, { it is NodeConfigContactSaveFailure })).also { configHolder = it }
    }

    val catalog = object : RadioCatalogPort {
        private fun RadioPreset.option() = RadioPresetOption(id, name, region.rawValue, repeatSectionHeader)
        override fun visiblePresets(region: RegionSelection?, activeId: String?) =
            RadioPresets.visiblePresets(region, activeId).map { it.option() }
        override val repeatPresets get() = RadioPresets.repeatPresets.map { it.option() }
        override fun resolvedPreset(device: DeviceDTO, region: RegionSelection?) =
            RadioPresets.resolvedPreset(device.frequency, device.bandwidth, device.spreadingFactor, device.codingRate,
                device.appliedRadioPresetID, region)?.option()
        override fun matchingPresetIds(device: DeviceDTO) =
            RadioPresets.matchingPresets(device.frequency, device.bandwidth, device.spreadingFactor, device.codingRate).map { it.id }.toSet()
        override fun nearestRepeatPreset(frequencyKHz: UInt) = RadioPresets.nearestRepeatPreset(frequencyKHz)?.option()
        override fun matchingRepeatPreset(frequencyKHz: UInt) = RadioPresets.matchingRepeatPreset(frequencyKHz)?.option()
        override fun repeatFrequencyKHz(presetId: String) = RadioPresets.repeatPresets.firstOrNull { it.id == presetId }?.frequencyKHz
        override fun regionDisplayName(region: RegionSelection) = RegionalAreas.displayName(region)
    }

    val regions = object : RegionSelectionPort {
        override val selection get() = app.regionSelectionFlow
        override fun set(selection: RegionSelection?) { app.regionSelection = selection }
    }

    fun dependencies(
        notifications: NotificationPermissionPort,
        location: LocationPermissionPort,
        navigator: SettingsNavigator,
        diagnostics: DiagnosticsPort,
    ): SettingsFeatureDependencies = SettingsFeatureDependencies(
        connection = object : SettingsConnection {
            override val connectedDevice = device
            override val connectionState = container.connectionManager.snapshot.map { it.state }
                .stateIn(scope, SharingStarted.WhileSubscribed(), app.connectionState)
            override val startupReadsAllowed = changes.map { app.canRunSettingsStartupReads }
                .stateIn(scope, SharingStarted.WhileSubscribed(), app.canRunSettingsStartupReads)
        },
        settingsService = {
            container.sessions.current?.takeIf { app.canRunSettingsStartupReads }?.let { session ->
                SettingsRadioAdapter(session.settingsService, session::setDefaultFloodScopePersisted) { expected, info ->
                    container.deviceEditor.persistRepeatRestore(expected, info, session.token)
                }
            }
        },
        radioCatalog = catalog,
        subdivisions = SubdivisionCatalog(RegionalAreas::showsSubdivisionPicker),
        regions = regions,
        regionLookup = RegionLookupPort { container.settingsRegionResolver?.resolve() },
        devicePreferences = object : DevicePreferencePort {
            private fun value(id: UUID) = checkNotNull(deviceValues[id]) { "Device preferences have not loaded" }
            override fun isAutoUpdateLocationEnabled(deviceId: UUID) = value(deviceId).autoUpdateLocation
            override fun gpsSource(deviceId: UUID) = GpsSource.valueOf(value(deviceId).gpsSource.name)
            override fun hasSetGpsSource(deviceId: UUID) = value(deviceId).hasSetGPSSource
            override fun setAutoUpdateLocationEnabled(enabled: Boolean, deviceId: UUID) {
                write {
                    storage.devicePreferences.setAutoUpdateLocationEnabled(enabled, deviceId)
                    deviceValues[deviceId] = storage.devicePreferences.observe(deviceId).first()
                }
            }
            override fun setGpsSource(source: GpsSource, deviceId: UUID) {
                val mapped = GPSSource.valueOf(source.name)
                write {
                    storage.devicePreferences.setGPSSource(mapped, deviceId)
                    deviceValues[deviceId] = storage.devicePreferences.observe(deviceId).first()
                }
            }
        },
        location = location,
        notificationPreferences = container.notificationPreferences,
        notificationPermission = notifications,
        discoveryChoices = DiscoveryChoiceSource {
            StoredDiscoveryChoices(preferences.value.contains(AppStorageKey.notifyNewContactsContact),
                preferences.value.contains(AppStorageKey.notifyNewContactsRepeater),
                preferences.value.contains(AppStorageKey.notifyNewContactsRoom))
        },
        maintenance = object : DeviceMaintenancePort {
            override suspend fun forgetDevice(deleteData: Boolean) { container.pairing.forgetDevice(deleteData) }
            override suspend fun forgetDevice(id: UUID) { container.pairing.forgetDevice(id) }
            override suspend fun unfavoritedNodeCount() = container.deviceEditor.unfavoritedNodeCount()
            override suspend fun removeUnfavoritedNodes() = container.deviceEditor.removeUnfavoritedNodes().let {
                RemoveUnfavoritedResult(it.removed.toLong(), it.total.toLong())
            }
        },
        deviceStore = object : DeviceSettingsStorePort {
            override suspend fun updateOcvSettings(deviceId: UUID, preset: String, customArray: String?) {
                live().deviceService.updateOCVSettings(deviceId, preset, customArray)
            }
            override suspend fun savePreRepeatSettings(deviceId: UUID) = container.deviceEditor.savePreRepeatSettings(deviceId)
            override fun addKnownRegion(region: String) { container.deviceEditor.addKnownRegion(region) }
            override fun removeKnownRegion(region: String) { container.deviceEditor.removeKnownRegion(region) }
        },
        diagnostics = diagnostics,
        identityKeys = object : IdentityKeyPort {
            override fun isValidExpandedKey(key: Bytes): Boolean = try {
                KeyGenerationService.validateExpandedKey(key); true
            } catch (_: KeyGenerationFailure.InvalidKey) { false }
            override suspend fun generateIdentity(hexPrefix: String?): GeneratedIdentity {
                val key = KeyGenerationService().generateIdentity(hexPrefix)
                return GeneratedIdentity(key.publicKey, key.expandedPrivateKey)
            }
        },
        nodeIdentity = {
            container.sessions.current?.takeIf { app.canRunSettingsStartupReads }?.let { current ->
                object : NodeIdentityPort { override suspend fun setNodeNameVerified(name: String) = current.settingsService.setNodeNameVerified(name) }
            }
        },
        battery = object : DeviceBatteryPort {
            override val battery = app.batteryMonitor.deviceBatteryFlow.map { info ->
                info?.let { DeviceBatterySnapshot(it.level.toDouble() / 1000,
                    it.percentage(app.batteryMonitor.activeBatteryOcvArray(app.connectedDevice)).toInt(),
                    it.usedStorageKB?.let(Math::toIntExact), it.totalStorageKB?.let(Math::toIntExact)) }
            }.stateIn(scope, SharingStarted.WhileSubscribed(), null)
            override suspend fun fetch() { app.batteryMonitor.deviceBattery = live().settingsService.getBattery() }
        },
        staleCleanup = object : StaleNodeCleanupPort {
            override val thresholdDays = preferences.map { Math.toIntExact(it[AppStorageKey.autoDeleteStaleNodesDays]) }
                .stateIn(scope, SharingStarted.WhileSubscribed(), Math.toIntExact(preferences.value[AppStorageKey.autoDeleteStaleNodesDays]))
            override val lastCleanup = preferences.map { values ->
                values[AppStorageKey.lastStaleCleanupDate].takeIf { it > 0 }?.let(DataStoreStaleCleanupPreferences::fromReferenceSeconds)
            }.stateIn(scope, SharingStarted.WhileSubscribed(), null)
            override fun setThresholdDays(days: Int) { write { storage.preferences.set(AppStorageKey.autoDeleteStaleNodesDays, days.toLong()) } }
            override fun performCleanup(force: Boolean) { app.performStaleNodeCleanup(force) }
        },
        regionDiscovery = RegionDiscoveryPort { known, supports ->
            when (val result = live().regionDiscoveryService.discover(known, supports)) {
                is RegionDiscoveryService.Outcome.SendFailed -> RegionDiscoveryOutcome.SendFailed
                RegionDiscoveryService.Outcome.NoRepeatersResponded -> RegionDiscoveryOutcome.NoRepeatersResponded
                is RegionDiscoveryService.Outcome.ErrorLoadingRepeaters -> RegionDiscoveryOutcome.ErrorLoadingRepeaters
                is RegionDiscoveryService.Outcome.Completed -> {
                    if (result.isPartial) report("Some repeater region queries failed: ${result.queryFailures.size}", null)
                    RegionDiscoveryOutcome.Completed(result.newRegions, result.allRepeatersTableFull)
                }
            }
        },
        chatPreferences = object : ChatPreferencePort {
            override val values = preferences.map { values -> CHAT_KEYS.mapValues { (_, key) -> values[key] } }
                .stateIn(scope, SharingStarted.WhileSubscribed(), CHAT_KEYS.mapValues { (_, key) -> preferences.value[key] })
            override fun set(preference: ChatPreference, value: Boolean) { write { storage.preferences.set(CHAT_KEYS.getValue(preference), value) } }
        },
        navigator = navigator,
        pathFallback = remotePathFallbackSettings,
    )

    internal val remotePathFallbackSettings = PathFallbackSettings(
        enabled = preferences.map { it[AppStorageKey.remoteFloodFallback] }.distinctUntilChanged(),
        setEnabled = { setPreference(AppStorageKey.remoteFloodFallback, it) },
    )

    companion object {
        private val CHAT_KEYS = mapOf(
            ChatPreference.REPLY_WITH_QUOTE to AppStorageKey.replyWithQuote,
            ChatPreference.SHOW_INCOMING_PATH to AppStorageKey.showIncomingPath,
            ChatPreference.SHOW_INCOMING_HOP_COUNT to AppStorageKey.showIncomingHopCount,
            ChatPreference.SHOW_INCOMING_REGION to AppStorageKey.showIncomingRegion,
            ChatPreference.SHOW_INCOMING_HEARD_COUNT to AppStorageKey.showIncomingHeardCount,
        )
        suspend fun create(container: AppContainer, storage: MeshCoreStorage, currentVersion: String): AppSettingsFeature {
            val feature = create(container, storage)
            feature.whatsNew = AppWhatsNew.create(
                storage.preferences, currentVersion, feature.initial[AppStorageKey.hasCompletedOnboarding],
            )
            return feature
        }

        suspend fun create(container: AppContainer, storage: MeshCoreStorage) =
            AppSettingsFeature(container, storage, storage.preferences.snapshot())
    }
}

private class SettingsRadioAdapter(
    private val service: SettingsService,
    private val setDefaultScope: suspend (String?) -> String?,
    private val persistRepeatRestore: suspend (DeviceDTO, SelfInfo) -> Boolean,
) : SettingsRadioPort {
    override val pathHashModeNeedsRecovery get() = service.pathHashModeNeedsRecovery
    override suspend fun getSelfInfo() = service.getSelfInfo()
    override suspend fun setRadioParamsVerified(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte,
        codingRate: UByte, clientRepeat: Boolean?, appliedRadioPresetID: String?) =
        service.setRadioParamsPersisted(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate, clientRepeat, appliedRadioPresetID)
    override suspend fun setTxPowerVerified(power: Byte) = service.setTxPowerPersisted(power)
    override suspend fun restoreRepeat(expected: DeviceDTO) =
        service.restoreRepeatPersisted(expected) { persistRepeatRestore(expected, it) }
    override suspend fun applyRadioPresetVerified(presetId: String) =
        service.applyRadioPresetPersisted(requireNotNull((RadioPresets.all + RadioPresets.repeatPresets).firstOrNull { it.id == presetId }))
    override suspend fun setOtherParamsVerified(device: DeviceDTO, autoAddContacts: Boolean?, telemetryModes: TelemetryModes?,
        advertLocationPolicy: AdvertLocationPolicy?, multiAcks: UByte?) =
        service.setOtherParamsVerified(device, autoAddContacts, telemetryModes, advertLocationPolicy, multiAcks)
    override suspend fun setAutoAddConfigVerified(config: AutoAddConfig) = service.setAutoAddConfigVerified(config)
    override suspend fun refreshAutoAddConfig() = service.refreshAutoAddConfig()
    override suspend fun setPathHashModeVerified(mode: UByte) = service.setPathHashModePersisted(mode)
    override suspend fun getDeviceGPSState() = service.getDeviceGPSState().let { DeviceGpsState(it.isSupported, it.isEnabled) }
    override suspend fun setDeviceGPSEnabledVerified(enabled: Boolean) =
        service.setDeviceGPSEnabledVerified(enabled).let { DeviceGpsState(it.isSupported, it.isEnabled) }
    override suspend fun refreshDeviceInfo() = service.refreshDeviceInfo()
    override suspend fun getDefaultFloodScope() = service.getDefaultFloodScope()
    override suspend fun setDefaultFloodScopeVerified(name: String?) = setDefaultScope(name)
    override suspend fun setBlePin(pin: UInt) = expectedReboot { service.setBlePin(pin) }
    override suspend fun reboot() = expectedReboot { service.reboot() }
    override suspend fun factoryReset() = expectedReboot { service.factoryReset() }
    override suspend fun importPrivateKey(key: Bytes) = service.importPrivateKey(key)

    private suspend fun <T> expectedReboot(operation: suspend () -> T): T = try {
        operation()
    } catch (failure: com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException) {
        val error = failure.error
        if (error is com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError.SessionError &&
            error.error is com.meshcoreone.android.core.protocol.config.MeshCoreException.Timeout
        ) throw SettingsOperationTimeoutException().also { it.initCause(failure) }
        throw failure
    }
}

internal suspend fun MeshCoreStorage.isRemoteFloodFallbackEnabled(): Boolean = try {
    preferences.get(AppStorageKey.remoteFloodFallback)
} catch (_: StorageFailure) {
    // PreferenceStore already reports the failure. Do not clear a path if the preference is unknown.
    false
}
