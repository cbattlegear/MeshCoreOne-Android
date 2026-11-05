// AndroidOnly: WP-303 Narrow ports app state needs from the connection layer and from platform integrations.
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.connectivity.device.RemoveUnfavoritedResult
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.protocol.event.FrequencyRange
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.model.SnapshotList
import java.time.Instant
import java.util.UUID

/**
 * The connection-layer surface app state drives (Swift `ConnectionManager` members app state calls). The
 * container implements it over the runtime `ConnectionManager`, `PairingCoordinator` and
 * `ConnectedDeviceEditor`; tests implement it with a recording fake.
 */
interface AppConnectionPort {
    val connectionState: DeviceConnectionState
    val connectedDevice: DeviceDTO?
    val lastConnectedDeviceId: UUID?
    val lastConnectedRadioId: RadioId?
    val shouldSuppressDisconnectedPill: Boolean
    val hasSystemPairingRegistry: Boolean
    val shouldDeferOpportunisticReconnect: Boolean
    var isPairingFlowActive: Boolean

    suspend fun activate()
    suspend fun appDidEnterBackground()
    suspend fun appDidBecomeActive()
    suspend fun checkWiFiConnectionHealth()
    suspend fun checkSyncHealth()
    fun clearSurfacedAuthenticationFailure()
    suspend fun disconnect(reason: RuntimeDisconnectReason)
    suspend fun connectViaWiFi(host: String, port: UShort, forceFullSync: Boolean)
    suspend fun connect(deviceId: UUID, forceReconnect: Boolean)
    suspend fun pairNewDevice()
    suspend fun systemAccessoriesMissingDeviceRecord(): List<SystemPairedAccessory>
    suspend fun removeSystemAccessoriesMissingDeviceRecord(ids: List<UUID>)
    suspend fun removeFailedPairing(deviceId: UUID)
    suspend fun stopBleScanning()
    suspend fun removeStaleNodes(olderThanDays: Int): RemoveUnfavoritedResult

    // Connected-device edits driven by the settings event stream (Swift `connectionManager.update*`).
    fun updateDevice(info: SelfInfo, appliedRadioPresetId: String?)
    suspend fun updateDevice(info: SelfInfo, appliedRadioPresetId: String?, expectedToken: SessionToken): Boolean =
        throw UnsupportedOperationException("Session-bound radio preset updates are not supported")
    fun updateDevice(device: DeviceDTO)
    fun updateOCVSettings(device: DeviceDTO, expectedToken: SessionToken)
    fun updateAutoAddConfig(config: AutoAddConfig)
    suspend fun updateAutoAddConfig(config: AutoAddConfig, expectedToken: SessionToken): Boolean =
        throw UnsupportedOperationException("Session-bound auto-add updates are not supported")
    fun updateClientRepeat(enabled: Boolean)
    suspend fun updateClientRepeat(enabled: Boolean, expectedToken: SessionToken): Boolean =
        throw UnsupportedOperationException("Session-bound client repeat updates are not supported")
    fun updatePathHashMode(mode: UByte)
    suspend fun updatePathHashMode(mode: UByte, expectedToken: SessionToken): Boolean =
        throw UnsupportedOperationException("Session-bound path hash replacement is not supported")
    fun updateDefaultFloodScopeName(name: String?)
    suspend fun updateDefaultFloodScopeName(name: String?, expectedToken: SessionToken): Boolean =
        throw UnsupportedOperationException("Session-bound default scope updates are not supported")
    fun setAllowedRepeatFreqRanges(ranges: SnapshotList<FrequencyRange>)
    fun setAllowedRepeatFreqRanges(ranges: SnapshotList<FrequencyRange>, expectedToken: SessionToken): Boolean =
        throw UnsupportedOperationException("Session-bound repeat ranges are not supported")
}

/** Persists the user's region choice (Swift `BackupUserDefaults.persist/loadRegionSelection`). */
interface RegionSelectionStore {
    /** Null when nothing is stored. A stored value that cannot be decoded is cleared and reported as null. */
    suspend fun load(): RegionSelection?
    suspend fun persist(selection: RegionSelection?)
}

/** Stale-node cleanup policy inputs (`autoDeleteStaleNodesDays` and `lastStaleCleanupDate`). */
interface StaleCleanupPreferences {
    suspend fun thresholdDays(): Int
    suspend fun lastRun(): Instant?
    suspend fun recordRun(at: Instant)
}

/**
 * Platform integrations app state notifies. Each member has a no-op default: the Live Activity, offline-map,
 * App Intents and notification-navigation collaborators belong to other work packages, so the container binds
 * only what exists on main.
 */
interface AppStatePlatform {
    suspend fun flushDebugLog() {}
    suspend fun onUnreadCountChanged(totalUnread: Int) {}
    suspend fun onConnectionLost() {}
    suspend fun onConnectionReady(device: DeviceDTO, ocvArray: List<Long>, unreadCount: Int) {}
    suspend fun onAutoReconnectStarted() {}
    fun onEnterBackground() {}
    fun hasActiveConnectionActivity(): Boolean = false
    suspend fun onReturnToForeground() {}
    suspend fun onPacketReceived() {}
    suspend fun onBatteryChanged(battery: BatteryInfo) {}
    fun resumeOfflineMaps() {}
    fun onSessionWired(session: AppSession, device: DeviceDTO?) {}
    fun configureNotificationNavigation(session: AppSession, connectedDevice: () -> DeviceDTO?) {}
    fun onSessionChanged() {}
    suspend fun onDisconnectRequested() {}

    companion object { val NONE: AppStatePlatform = object : AppStatePlatform {} }
}
