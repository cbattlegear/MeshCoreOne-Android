// PortedFrom: MC1Services/Sources/MC1Services/Services/SettingsEvent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.device

import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.event.FrequencyRange
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.SelfInfo
import kotlinx.coroutines.CompletableDeferred

sealed interface SettingsEvent {
    data class DeviceUpdated(
        val info: SelfInfo,
        val appliedRadioPresetID: String?,
        val persistence: CompletableDeferred<Unit>? = null,
    ) : SettingsEvent
    data class AutoAddConfigUpdated(val config: AutoAddConfig) : SettingsEvent
    data class ClientRepeatUpdated(
        val enabled: Boolean,
        val persistence: CompletableDeferred<Unit>? = null,
    ) : SettingsEvent
    data class PathHashModeUpdated(
        val mode: UByte,
        val persistence: CompletableDeferred<Unit>? = null,
    ) : SettingsEvent
    data class AllowedRepeatFreqUpdated(val ranges: SnapshotList<FrequencyRange>) : SettingsEvent
    data class DefaultFloodScopeUpdated(
        val name: String?,
        val persistence: CompletableDeferred<Unit>? = null,
    ) : SettingsEvent
}
