// PortedFrom: MC1/State/AppState.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.connectivity.device.RemoveUnfavoritedResult
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.event.FrequencyRange
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.runtime.ConnectionError
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.rendering.ChatCoordinatorRegistry
import com.meshcoreone.android.core.services.rendering.DraftDefaults
import com.meshcoreone.android.core.services.rendering.DraftStore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The shared inert stand-in a UI reads when no process container is configured (Swift `AppState.placeholder`,
 * the SwiftUI environment default; the Compose analogue is a `CompositionLocal` default owned by the UI work
 * packages). It is built once, never connected, owns no session, starts no job and never touches process-global
 * state such as the shared debug-log buffer.
 */
object AppStatePlaceholder {
    val instance: AppState by lazy {
        AppState(
            AppStateDependencies(
                connection = InertConnectionPort,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                session = { null },
                processStore = { null },
                draftStore = DraftStore(InertDraftDefaults),
                connectionUi = ConnectionUiState(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)),
                batteryMonitor = BatteryMonitor(CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)),
                resyncFailure = ResyncFailureRegistry { },
                registryFactory = { store: com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol ->
                    ChatCoordinatorRegistry(store, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined))
                },
            ),
        )
    }
}

private object InertDraftDefaults : DraftDefaults {
    override fun stringDictionary(key: String): Map<String, String>? = null
    override fun setStringDictionary(value: Map<String, String>, key: String) = Unit
}

/** A connection layer that is never connected and refuses every action. */
private object InertConnectionPort : AppConnectionPort {
    override val connectionState: DeviceConnectionState = DeviceConnectionState.DISCONNECTED
    override val connectedDevice: DeviceDTO? = null
    override val lastConnectedDeviceId: UUID? = null
    override val lastConnectedRadioId: RadioId? = null
    override val shouldSuppressDisconnectedPill: Boolean = false
    override val hasSystemPairingRegistry: Boolean = false
    override val shouldDeferOpportunisticReconnect: Boolean = false
    override var isPairingFlowActive: Boolean
        get() = false
        set(value) = Unit

    override suspend fun activate() = Unit
    override suspend fun appDidEnterBackground() = Unit
    override suspend fun appDidBecomeActive() = Unit
    override suspend fun checkWiFiConnectionHealth() = Unit
    override suspend fun checkSyncHealth() = Unit
    override fun clearSurfacedAuthenticationFailure() = Unit
    override suspend fun disconnect(reason: RuntimeDisconnectReason) = Unit
    override suspend fun connectViaWiFi(host: String, port: UShort, forceFullSync: Boolean): Unit = throw ConnectionError.NotConnected()
    override suspend fun connect(deviceId: UUID, forceReconnect: Boolean): Unit = throw ConnectionError.NotConnected()
    override suspend fun pairNewDevice(): Unit = throw ConnectionError.NotConnected()
    override suspend fun systemAccessoriesMissingDeviceRecord(): List<SystemPairedAccessory> = emptyList()
    override suspend fun removeSystemAccessoriesMissingDeviceRecord(ids: List<UUID>) = Unit
    override suspend fun removeFailedPairing(deviceId: UUID) = Unit
    override suspend fun stopBleScanning() = Unit
    override suspend fun removeStaleNodes(olderThanDays: Int): RemoveUnfavoritedResult = RemoveUnfavoritedResult(0, 0)
    override fun updateDevice(info: SelfInfo, appliedRadioPresetId: String?) = Unit
    override fun updateDevice(device: DeviceDTO) = Unit
    override fun updateAutoAddConfig(config: AutoAddConfig) = Unit
    override fun updateClientRepeat(enabled: Boolean) = Unit
    override fun updatePathHashMode(mode: UByte) = Unit
    override fun updateDefaultFloodScopeName(name: String?) = Unit
    override fun setAllowedRepeatFreqRanges(ranges: SnapshotList<FrequencyRange>) = Unit
}
