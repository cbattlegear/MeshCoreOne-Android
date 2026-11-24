// PortedFrom: MC1/Views/Settings/SettingsDetail.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/SettingsSubpage.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/SettingsListContent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device

import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.ui.UiText

/** The Settings detail pages reached from the list. */
enum class SettingsDetail(val requiresDevice: Boolean) {
    DEVICE_INFO(true), RADIO(true), LOCATION(true), CONNECTION(true), ADVANCED(true),
    NOTIFICATIONS(false), CHATS(false), APPEARANCE(false), MAPS(false), LANGUAGE(false), BACKUP(false), SUPPORT(false), FEEDBACK(false),
}

/** Second-level pushes from inside a detail page; each push rebuilds its destination so no stale state is reused. */
sealed interface SettingsSubpage {
    data class PublicKey(val publicKey: Bytes) : SettingsSubpage
    data object ConfigExport : SettingsSubpage
    data object ConfigImport : SettingsSubpage
    data object BlockedChannelSenders : SettingsSubpage
    data object BlockedContacts : SettingsSubpage
    data object TrustedContacts : SettingsSubpage
    data object TranslateIntoLanguage : SettingsSubpage
    data object PresetLocation : SettingsSubpage
}

/**
 * Links that leave this feature module; the app layer (WP-303) resolves them, because feature modules cannot
 * depend on each other and the offline-map screens belong to the shared maps work (WP-312).
 */
sealed interface SettingsExternalDestination {
    data object TextRules : SettingsExternalDestination
    /** Settings > Maps > Offline Maps: the shared offline-map settings of `core:maps`. */
    data object OfflineMapSettings : SettingsExternalDestination

    /** The Maps hub (basemap appearance plus the offline-maps entry). */
    data object MapsSettings : SettingsExternalDestination
    data object DeviceSelection : SettingsExternalDestination
    data object LocationPicker : SettingsExternalDestination
    data object RegionManagement : SettingsExternalDestination

    /** The shared country/state picker the preset-location row opens (onboarding and settings share it). */
    data object RegionPicker : SettingsExternalDestination
    data object ShareContactQr : SettingsExternalDestination
    data object AppSystemSettings : SettingsExternalDestination

    /** The import-private-key sheet (settings backup work, WP-318). */
    data object ImportPrivateKey : SettingsExternalDestination

    /** The trusted-contacts picker behind "manage trusted" (deferred screen, see the WP-317 deviations). */
    data object TrustedContacts : SettingsExternalDestination
    data object RemoteCLIContacts : SettingsExternalDestination

    /** The blocked-contact and blocked-channel-sender lists (deferred screens, see the WP-317 deviations). */
    data object BlockedContacts : SettingsExternalDestination
    data object BlockedChannelSenders : SettingsExternalDestination
}

fun interface SettingsNavigator {
    fun open(destination: SettingsExternalDestination)
}

/** Rows and derived text of the "My device" and app-settings sections of the list. */
object SettingsListPresentation {
    /** The My Device rows only exist while a radio is connected. */
    fun detailsFor(hasDevice: Boolean): List<SettingsDetail> =
        SettingsDetail.entries.filter { hasDevice || !it.requiresDevice }

    /** Clears a device-only selection when the radio goes away, so the detail pane never strands a gone device page. */
    fun selectionAfterDeviceChange(selected: SettingsDetail?, hasDevice: Boolean): SettingsDetail? =
        if (selected != null && selected.requiresDevice && !hasDevice) null else selected

    /** Radio row detail: the matching preset's name, or "Custom". Repeat Mode matches repeat presets by frequency. */
    fun radioDetailText(device: DeviceDTO, region: RegionSelection?, catalog: RadioCatalogPort): RadioDetailText {
        val preset = if (device.clientRepeat) catalog.matchingRepeatPreset(device.frequency) else catalog.resolvedPreset(device, region)
        return preset?.let { RadioDetailText.Preset(it.name) } ?: RadioDetailText.Custom
    }

    fun locationDetailText(device: DeviceDTO): UiText = UiText.Resource(
        if (device.sharesLocationPublicly) AppSettingsStrings.locationSharingPublicly else AppSettingsStrings.locationNotSharing,
    )

    /** The Connection row says Wi-Fi or Bluetooth after the active transport. */
    fun connectionTitle(transport: TransportType?): UiText = UiText.Resource(
        if (transport == TransportType.WIFI) AppSettingsStrings.wifiHeader else AppSettingsStrings.bluetoothHeader,
    )
}

sealed interface RadioDetailText {
    data class Preset(val name: String) : RadioDetailText
    data object Custom : RadioDetailText
}
