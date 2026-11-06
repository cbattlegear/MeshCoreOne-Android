// AndroidOnly: WP-407 Settings adapters over the process preferences/store and current connection-owned services.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.datastore.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.*
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
    private val scope get() = container.uiScope
    private val app get() = container.appState
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private val preferences = MutableStateFlow(initial)
    internal val preferenceValues: StateFlow<PreferenceSnapshot> get() = preferences
    internal fun setPreference(key: PreferenceKey.BooleanKey, value: Boolean) {
        write { storage.preferences.set(key, value) }
    }
    private val deviceValues = mutableMapOf<UUID, DevicePreferenceSnapshot>()
    val failure = MutableStateFlow<Throwable?>(null)
    private val changes = combine(app.servicesVersion, app.sessionStateChangeCount, container.connectionManager.snapshot) { _, _, _ -> Unit }
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
        data class Saved(val device: DeviceDTO) : SelectionAction
        data class Setup(val accessory: SystemPairedAccessory) : SelectionAction
        data object Manual : SelectionAction
        data object Scan : SelectionAction
    }

    internal val selectionAction = MutableStateFlow<SelectionAction?>(null)
    internal val selectionPermissionDenied = MutableStateFlow(false)
    internal val selectionScanBlocker = MutableStateFlow(PairingBlocker.NONE)
    internal val selectionRequestedPermission = MutableStateFlow<OnboardingPermissionKind?>(null)
    private var selectionRequestedForScan = false
    private var selectionScanQueued = false
    private var selectionScanOwned = false
    internal val ownsSelectionScan: Boolean get() = selectionScanOwned
    internal var selectionScanJob: Job? = null
        private set
    private val selectionScanGate by lazy {
        val platform = requireNotNull(container.onboarding)
        DeviceScanCoordinator(scope, platform.pairing, platform.demo, platform.permissions, {})
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
        requestSelectionPermission(SelectionAction.Saved(device))
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
        if (selectionAction.value == null &&
            platform.pairing.scanFallback?.isRequested?.value == true && scanBlocker != PairingBlocker.NONE) {
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
                PairingBlocker.BLUETOOTH_PERMISSION_DENIED, PairingBlocker.BLUETOOTH_OFF ->
                    selectionPermissionDenied.value = true
            }
            return
        }
        if (selectionUsesBluetooth) {
            when (scanBlocker) {
                PairingBlocker.NONE -> {
                    clearSelectionPermission()
                    when (action) {
                        is SelectionAction.Saved -> connectSavedDevice(action.device)
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
                    is SelectionAction.Saved -> connectSavedDevice(action.device)
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
        selectionRequestedForScan = selectionAction.value == SelectionAction.Scan
        selectionRequestedPermission.value = kind
        return kind
    }

    internal fun selectionPermissionResult() {
        val kind = selectionRequestedPermission.value
        selectionRequestedPermission.value = null
        kind?.let {
            val platform = requireNotNull(container.onboarding)
            val permissions = if (selectionRequestedForScan) platform.permissions else platform.connectionPermissions
            permissions.onRequestFinished(it)
        }
        selectionRequestedForScan = false
        refreshSelectionPermission()
    }

    internal fun cancelSelectionPermission() {
        val scan = selectionAction.value == SelectionAction.Scan
        clearSelectionPermission()
        if (scan) {
            cancelSelectionScan()
            container.onboarding?.pairing?.scanFallback?.cancel()
        }
    }

    private fun clearSelectionPermission() {
        selectionAction.value = null
        selectionPermissionDenied.value = false
    }

    internal fun connectSavedDevice(device: DeviceDTO): Job = scope.launch {
        try {
            val wifi = device.connectionMethods.filterIsInstance<ConnectionMethod.WiFi>().firstOrNull()
            if (wifi != null) app.connectViaWiFi(wifi.host, wifi.port, forceFullSync = true)
            else container.connectionPort.connect(device.id, forceFullSync = true, forceReconnect = true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (problem: Exception) {
            app.connectionUI.presentSavedDeviceConnectFailure(device.id, problem)
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
        if (selectionAction.value == SelectionAction.Scan) clearSelectionPermission()
        if (selectionScanOwned) {
            selectionScanJob?.cancel()
            container.onboarding?.pairing?.scanFallback?.cancel()
        }
        selectionScanOwned = false
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

    internal fun nodeConfig(resources: android.content.res.Resources): NodeConfigStateHolder =
        configHolder ?: NodeConfigStateHolder(scope, {
            val connection = live()
            val token = connection.token
            object : NodeConfigSession {
                private var identityHandoff: kotlinx.coroutines.CompletableDeferred<Unit>? = null
                override val deviceName = app.connectedDevice?.nodeName.orEmpty()
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
                override suspend fun apply(config: MeshCoreNodeConfig, sections: ConfigSections, progress: (NodeConfigProgress) -> Unit) = bound {
                    check(identityHandoff == null) { "A configuration import is already in progress" }
                    val handoff = kotlinx.coroutines.CompletableDeferred<Unit>().also { identityHandoff = it }
                    connection.nodeConfigService.importConfig(config, sections, token.radioId, handoff) {
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
        }, ResourceNodeConfigMessages(resources, { it is NodeConfigContactSaveFailure })).also { configHolder = it }

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
        settingsService = { container.sessions.current?.takeIf { app.canRunSettingsStartupReads }?.let { SettingsRadioAdapter(it.settingsService) } },
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
            override fun savePreRepeatSettings() { container.deviceEditor.savePreRepeatSettings() }
            override fun clearPreRepeatSettings() { container.deviceEditor.clearPreRepeatSettings() }
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
    )

    companion object {
        private val CHAT_KEYS = mapOf(
            ChatPreference.REPLY_WITH_QUOTE to AppStorageKey.replyWithQuote,
            ChatPreference.SHOW_INCOMING_PATH to AppStorageKey.showIncomingPath,
            ChatPreference.SHOW_INCOMING_HOP_COUNT to AppStorageKey.showIncomingHopCount,
            ChatPreference.SHOW_INCOMING_REGION to AppStorageKey.showIncomingRegion,
            ChatPreference.SHOW_INCOMING_HEARD_COUNT to AppStorageKey.showIncomingHeardCount,
        )
        suspend fun create(container: AppContainer, storage: MeshCoreStorage) =
            AppSettingsFeature(container, storage, storage.preferences.snapshot())
    }
}

private class SettingsRadioAdapter(private val service: SettingsService) : SettingsRadioPort {
    override suspend fun getSelfInfo() = service.getSelfInfo()
    override suspend fun setRadioParamsVerified(frequencyKHz: UInt, bandwidthKHz: UInt, spreadingFactor: UByte,
        codingRate: UByte, clientRepeat: Boolean?, appliedRadioPresetID: String?) =
        service.setRadioParamsVerified(frequencyKHz, bandwidthKHz, spreadingFactor, codingRate, clientRepeat, appliedRadioPresetID)
    override suspend fun setTxPowerVerified(power: Byte) = service.setTxPowerVerified(power)
    override suspend fun applyRadioPresetVerified(presetId: String) =
        service.applyRadioPresetVerified(requireNotNull((RadioPresets.all + RadioPresets.repeatPresets).firstOrNull { it.id == presetId }))
    override suspend fun setOtherParamsVerified(device: DeviceDTO, autoAddContacts: Boolean?, telemetryModes: TelemetryModes?,
        advertLocationPolicy: AdvertLocationPolicy?, multiAcks: UByte?) =
        service.setOtherParamsVerified(device, autoAddContacts, telemetryModes, advertLocationPolicy, multiAcks)
    override suspend fun setAutoAddConfigVerified(config: AutoAddConfig) = service.setAutoAddConfigVerified(config)
    override suspend fun refreshAutoAddConfig() = service.refreshAutoAddConfig()
    override suspend fun setPathHashModeVerified(mode: UByte) = service.setPathHashModeVerified(mode)
    override suspend fun getDeviceGPSState() = service.getDeviceGPSState().let { DeviceGpsState(it.isSupported, it.isEnabled) }
    override suspend fun setDeviceGPSEnabledVerified(enabled: Boolean) =
        service.setDeviceGPSEnabledVerified(enabled).let { DeviceGpsState(it.isSupported, it.isEnabled) }
    override suspend fun refreshDeviceInfo() = service.refreshDeviceInfo()
    override suspend fun getDefaultFloodScope() = service.getDefaultFloodScope()
    override suspend fun setDefaultFloodScopeVerified(name: String?) = service.setDefaultFloodScopeVerified(name)
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
