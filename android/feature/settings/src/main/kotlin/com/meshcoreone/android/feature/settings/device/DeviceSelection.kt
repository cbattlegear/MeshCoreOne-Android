// PortedFrom: MC1/Views/Settings/DeviceSelectionListBuilder.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/DeviceSelectionSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/State/SystemPairedAccessory.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.UiText
import java.util.UUID

/** A system-paired accessory with no saved `Device` row (Swift `SystemPairedAccessory`; on Android a companion association). */
data class SystemPairedAccessory(val id: UUID, val name: String)

/**
 * Filters saved device rows to those the user can reach from this phone. Backup-restore "shadow" rows lose
 * their Bluetooth methods and carry a fresh id the system registry does not know, so they stay hidden until paired.
 */
object DeviceSelectionFilter {
    fun isConnectable(
        device: DeviceDTO,
        pairedAccessoryIds: Set<UUID>,
        hasSystemPairingRegistry: Boolean = true,
    ): Boolean {
        if (device.connectionMethods.any { it.isWiFi }) return true
        // No registry to validate against: the stored Bluetooth method is the only reachability signal.
        if (!hasSystemPairingRegistry) return device.connectionMethods.any { it.isBluetooth }
        return device.id in pairedAccessoryIds
    }
}

/** Connectable saved radios plus the caller-supplied needs-setup list. Does not re-diff registry ids minus saved. */
object DeviceSelectionListBuilder {
    data class Result(val connectable: List<DeviceDTO>, val needsSetup: List<SystemPairedAccessory>)

    /** VoiceOver/TalkBack and trailing-control copy of a needs-setup "Previously Paired" row. */
    data class NeedsSetupRowPresentation(val trailingTitle: UiText, val accessibilityLabel: UiText, val accessibilityHint: UiText)

    fun make(
        saved: List<DeviceDTO>,
        accessories: List<Pair<UUID, String>>,
        needsSetup: List<SystemPairedAccessory>,
        hasSystemPairingRegistry: Boolean,
    ): Result {
        val pairedIds = accessories.map { it.first }.toSet()
        return Result(
            connectable = saved.filter { DeviceSelectionFilter.isConnectable(it, pairedIds, hasSystemPairingRegistry) },
            needsSetup = if (hasSystemPairingRegistry) needsSetup else emptyList(),
        )
    }

    fun needsSetupPresentation(
        name: String,
        previouslyPairedLabel: String,
        formatAccessibilityLabel: (String, String) -> String,
    ): NeedsSetupRowPresentation = NeedsSetupRowPresentation(
        trailingTitle = UiText.Resource(AppSettingsStrings.deviceSelectionSetup),
        accessibilityLabel = UiText.Verbatim(formatAccessibilityLabel(name, previouslyPairedLabel)),
        accessibilityHint = UiText.Resource(AppSettingsStrings.deviceSelectionAccessibilitySetupHint),
    )
}
