// PortedFrom: MC1/Views/Settings/SettingsListContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/SettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/NoDeviceSection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.settings.device.RadioCatalogPort
import com.meshcoreone.android.feature.settings.device.RadioDetailText
import com.meshcoreone.android.feature.settings.device.RegionSelectionPort
import com.meshcoreone.android.feature.settings.device.SettingsDetail
import com.meshcoreone.android.feature.settings.device.SettingsListPresentation

/** The settings list: "My device" rows while a radio is connected, otherwise a connect prompt, then the app pages. */
@Composable
fun SettingsListScreen(
    device: DeviceDTO?, transport: TransportType?, regions: RegionSelectionPort, catalog: RadioCatalogPort,
    onSelect: (SettingsDetail) -> Unit, onConnect: () -> Unit, versionText: UiText? = null,
) {
    val region by regions.selection.collectAsState()
    SettingsScreenFrame(res(AppSettingsStrings.title), onDone = null) {
        if (device != null) {
            val radioDetail = when (val text = SettingsListPresentation.radioDetailText(device, region, catalog)) {
                is RadioDetailText.Preset -> UiText.Verbatim(text.name)
                RadioDetailText.Custom -> res(AppSettingsStrings.batteryCurveCustom)
            }
            SettingsSection(res(AppSettingsStrings.myDeviceHeader)) {
                ActionRow(res(AppSettingsStrings.deviceInfoTitle), true, { onSelect(SettingsDetail.DEVICE_INFO) }, detail = UiText.Verbatim(device.nodeName))
                ActionRow(res(AppSettingsStrings.radioHeader), true, { onSelect(SettingsDetail.RADIO) }, detail = radioDetail)
                ActionRow(res(AppSettingsStrings.locationHeader), true, { onSelect(SettingsDetail.LOCATION) }, detail = SettingsListPresentation.locationDetailText(device))
                if (showPhysicalConnectionSettings(transport)) {
                    ActionRow(SettingsListPresentation.connectionTitle(transport), true, { onSelect(SettingsDetail.CONNECTION) })
                }
                ActionRow(res(AppSettingsStrings.advancedSettingsTitle), true, { onSelect(SettingsDetail.ADVANCED) })
            }
        } else {
            SettingsSection(res(AppSettingsStrings.deviceHeader), listOf(res(AppSettingsStrings.deviceNoDeviceConnected))) {
                ActionRow(res(AppSettingsStrings.deviceConnect), true, onConnect)
            }
        }

        SettingsSection(res(AppSettingsStrings.appSettingsHeader)) {
            ActionRow(res(AppSettingsStrings.notificationsHeader), true, { onSelect(SettingsDetail.NOTIFICATIONS) })
            ActionRow(res(AppSettingsStrings.chatSettingsTitle), true, { onSelect(SettingsDetail.CHATS) })
            ActionRow(res(AppSettingsStrings.appearanceTitle), true, { onSelect(SettingsDetail.APPEARANCE) })
            ActionRow(res(AppSettingsStrings.languageTitle), true, { onSelect(SettingsDetail.LANGUAGE) })
            ActionRow(res(AppSettingsStrings.mapsTitle), true, { onSelect(SettingsDetail.MAPS) })
            ActionRow(res(AppSettingsStrings.settingsBackupTitle), true, { onSelect(SettingsDetail.BACKUP) })
            ActionRow(res(AppSettingsStrings.supportTitle), true, { onSelect(SettingsDetail.SUPPORT) })
            ActionRow(res(AppSettingsStrings.feedbackTitle), true, { onSelect(SettingsDetail.FEEDBACK) })
        }
        if (versionText != null) {
            Column(Modifier.fillMaxWidth().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(uiString(versionText), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
            }
        }
    }
}

internal fun showPhysicalConnectionSettings(transport: TransportType?): Boolean = transport != TransportType.SIMULATOR
