// PortedFrom: MC1/Views/Settings/Sections/NotificationSettingsSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/LocationSettingsSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/BluetoothSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/ConnectionSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/DeviceInfoView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.settings.device.BluetoothPinStateHolder
import com.meshcoreone.android.feature.settings.device.BluetoothPinType
import com.meshcoreone.android.feature.settings.device.DeviceInfoStateHolder
import com.meshcoreone.android.feature.settings.device.GpsSource
import com.meshcoreone.android.feature.settings.device.LocationSettingsStateHolder
import com.meshcoreone.android.feature.settings.device.NotificationSettingsStateHolder
import com.meshcoreone.android.feature.settings.device.SettingsExternalDestination
import com.meshcoreone.android.feature.settings.device.SettingsNavigator
import com.meshcoreone.android.feature.settings.device.SettingsSubpage
import com.meshcoreone.android.feature.settings.device.StorageUsageBand
import kotlinx.coroutines.launch

/** Settings > Notifications. Call [NotificationSettingsStateHolder.onResume] from the host's lifecycle for the foreground refresh. */
@Composable
fun NotificationSettingsScreen(
    holder: NotificationSettingsStateHolder,
    navigator: SettingsNavigator,
    onDone: (() -> Unit)?,
    notificationEnrollment: @Composable () -> Unit = {},
) {
    HolderLifecycle(holder::start, holder::stop)
    val state by holder.state.collectAsState()
    val prefs = state.preferences
    SettingsScreenFrame(res(AppSettingsStrings.notificationsHeader), onDone) {
        SettingsSection(null) {
            when {
                state.showsToggles && prefs != null -> {
                    SwitchRow(res(AppSettingsStrings.notificationsContactMessages), prefs.contactMessagesEnabled, true, holder::onContactMessages)
                    SwitchRow(res(AppSettingsStrings.notificationsChannelMessages), prefs.channelMessagesEnabled, true, holder::onChannelMessages)
                    SwitchRow(res(AppSettingsStrings.notificationsRoomMessages), prefs.roomMessagesEnabled, true, holder::onRoomMessages)
                    SwitchRow(res(AppSettingsStrings.notificationsReactions), prefs.reactionNotificationsEnabled, true, holder::onReactions)
                    SwitchRow(res(AppSettingsStrings.notificationsLowBattery), prefs.lowBatteryEnabled, true, holder::onLowBattery)
                    SwitchRow(res(AppSettingsStrings.notificationsNewContactDiscovered), prefs.newContactDiscoveredEnabled, true, holder::onNewContactDiscovered)
                    if (state.showsDiscoveryChildren) {
                        SwitchRow(res(AppSettingsStrings.notificationsDiscoveryContact), prefs.discoveryContactEnabled, true, holder::onDiscoveryContact, indent = true)
                        SwitchRow(res(AppSettingsStrings.notificationsDiscoveryRepeater), prefs.discoveryRepeaterEnabled, true, holder::onDiscoveryRepeater, indent = true)
                        SwitchRow(res(AppSettingsStrings.notificationsDiscoveryRoom), prefs.discoveryRoomEnabled, true, holder::onDiscoveryRoom, indent = true)
                    }
                }
                state.authorization == com.meshcoreone.android.feature.settings.device.NotificationAuthorization.DENIED -> {
                    Text(stringResource(AppSettingsStrings.notificationsDisabled), Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ActionRow(res(AppSettingsStrings.notificationsOpenSettings), true, { navigator.open(SettingsExternalDestination.AppSystemSettings) })
                }
                else -> ActionRow(res(AppSettingsStrings.notificationsEnable), true, holder::requestAuthorization)
            }
        }
        notificationEnrollment()
    }
}

/** Settings > My Device > Location. */
@Composable
fun LocationSettingsScreen(holder: LocationSettingsStateHolder, navigator: SettingsNavigator, onDone: (() -> Unit)?) {
    HolderLifecycle(holder::start, holder::stop)
    val state by holder.state.collectAsState()
    val gpsOptions = listOf(res(AppSettingsStrings.locationGpsSourcePhone) to GpsSource.PHONE, res(AppSettingsStrings.locationGpsSourceDevice) to GpsSource.DEVICE)
    SettingsScreenFrame(res(AppSettingsStrings.locationHeader), onDone) {
        SettingsSection(res(AppSettingsStrings.locationHeader), listOf(res(AppSettingsStrings.locationFooter))) {
            ActionRow(res(AppSettingsStrings.nodeSetLocation), state.setLocationEnabled, { navigator.open(SettingsExternalDestination.LocationPicker) },
                detail = res(if (state.isLocationSet) AppSettingsStrings.nodeLocationSet else AppSettingsStrings.nodeLocationNotSet))
            SwitchRow(res(AppSettingsStrings.nodeShareLocationPublicly), state.shareLocation, state.controlsEnabled, holder::onShareToggled)
            SwitchRow(res(AppSettingsStrings.locationAutoUpdate), state.autoUpdateLocation, state.controlsEnabled, holder::onAutoUpdateToggled)
            if (state.autoUpdateLocation) {
                if (state.deviceHasGps) {
                    DropdownRow(res(AppSettingsStrings.locationGpsSource), gpsOptions.first { it.second == state.gpsSource }.first, gpsOptions,
                        state.controlsEnabled, holder::onGpsSourceSelected)
                } else {
                    Row(Modifier.fillMaxWidth().sharedTouchTarget(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(stringResource(AppSettingsStrings.locationGpsSource))
                        Text(stringResource(AppSettingsStrings.locationGpsSourcePhone), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (state.deviceHasGps) {
            SettingsSection(res(AppSettingsStrings.locationDeviceGpsHeader), listOf(res(AppSettingsStrings.locationDeviceGpsFooter))) {
                SwitchRow(res(AppSettingsStrings.locationDeviceGpsToggle), state.deviceGpsEnabled, state.controlsEnabled, holder::onDeviceGpsToggled)
            }
        }
    }
    ConfirmDialog(
        state.showLocationDeniedAlert, res(com.meshcoreone.android.core.l10n.R.string.l10n_app_onboarding_permissions_locationalert_title),
        res(com.meshcoreone.android.core.l10n.R.string.l10n_app_onboarding_permissions_locationalert_message),
        res(com.meshcoreone.android.core.l10n.R.string.l10n_app_onboarding_permissions_locationalert_opensettings),
        { holder.dismissLocationDeniedAlert(); navigator.open(SettingsExternalDestination.AppSystemSettings) }, holder::dismissLocationDeniedAlert, destructive = false,
    )
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}

/** Settings > My Device > Connection (Bluetooth PIN). The Wi-Fi variant of this page is deferred. */
@Composable
fun BluetoothSettingsScreen(holder: BluetoothPinStateHolder, onDone: (() -> Unit)?) {
    HolderLifecycle(holder::start, holder::stop)
    val state by holder.state.collectAsState()
    val typeName = { type: BluetoothPinType ->
        res(if (type == BluetoothPinType.DEFAULT) AppSettingsStrings.bluetoothPinTypeDefault else AppSettingsStrings.bluetoothPinTypeCustom)
    }
    var pinText by remember { mutableStateOf("") }
    SettingsScreenFrame(res(AppSettingsStrings.bluetoothHeader), onDone) {
        SettingsSection(res(AppSettingsStrings.bluetoothHeader),
            if (state.pinType == BluetoothPinType.DEFAULT) listOf(res(AppSettingsStrings.bluetoothDefaultPinFooter)) else emptyList()) {
            DropdownRow(res(AppSettingsStrings.bluetoothPinType), typeName(state.pinType), BluetoothPinType.entries.map { typeName(it) to it }, state.enabled, holder::onPinTypeSelected)
            if (state.showsCurrentPin) {
                val pin = state.device?.blePin?.toString().orEmpty()
                ActionRow(res(AppSettingsStrings.bluetoothCurrentPin), true, holder::togglePinVisible,
                    detail = UiText.Verbatim(if (state.isPinVisible) pin else "••••••"))
                ActionRow(res(AppSettingsStrings.bluetoothChangePin), state.enabled, { pinText = ""; holder.requestChangePin() })
            }
        }
    }
    val entryTitle = if (state.showingChangePinEntry) AppSettingsStrings.bluetoothAlertChangePinTitle else AppSettingsStrings.bluetoothAlertSetPinTitle
    val entryMessage = if (state.showingChangePinEntry) AppSettingsStrings.bluetoothAlertChangePinMessage else AppSettingsStrings.bluetoothAlertSetPinMessage
    if (state.showingPinEntry || state.showingChangePinEntry) {
        AlertDialog(
            onDismissRequest = holder::cancelPinDialog,
            title = { Text(stringResource(entryTitle)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(entryMessage))
                    OutlinedTextField(pinText, { pinText = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                        placeholder = { Text(stringResource(AppSettingsStrings.bluetoothPinPlaceholder)) },
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.fillMaxWidth().sharedTouchTarget())
                }
            },
            confirmButton = { TextButton({ holder.submitCustomPin(pinText); pinText = "" }, Modifier.sharedTouchTarget()) { Text(stringResource(AppSettingsStrings.bluetoothSetPin)) } },
            dismissButton = { TextButton(holder::cancelPinDialog, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonCancel)) } },
        )
    }
    ConfirmDialog(state.showingRemoveConfirmation, res(AppSettingsStrings.bluetoothAlertChangePinTypeTitle), res(AppSettingsStrings.bluetoothAlertChangePinTypeMessage),
        res(AppSettingsStrings.bluetoothAlertChange), holder::confirmRemoveCustomPin, holder::cancelPinDialog)
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
}

/** Settings > My Device > Device info. */
@Composable
fun DeviceInfoScreen(holder: DeviceInfoStateHolder, navigator: SettingsNavigator, onOpenSubpage: (SettingsSubpage) -> Unit, onDone: (() -> Unit)?) {
    HolderLifecycle(holder::start, holder::stop)
    val state by holder.state.collectAsState()
    val resources = LocalContext.current.resources
    val device = state.device
    SettingsScreenFrame(res(AppSettingsStrings.deviceInfoTitle), onDone) {
        if (device == null) {
            SettingsSection(null) {
                Text(stringResource(AppSettingsStrings.deviceInfoNoDeviceTitle), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(stringResource(AppSettingsStrings.deviceInfoNoDeviceDescription), Modifier.padding(bottom = 8.dp))
            }
            return@SettingsScreenFrame
        }
        SettingsSection(res(AppSettingsStrings.deviceHeader)) {
            Text(device.nodeName, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleLarge)
            Text(state.manufacturer ?: stringResource(AppSettingsStrings.deviceInfoDefaultManufacturer), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodyMedium)
        }
        SettingsSection(res(AppSettingsStrings.nodeHeader), listOf(res(AppSettingsStrings.nodeFooter))) {
            ActionRow(res(AppSettingsStrings.nodeName), state.nameEditEnabled, holder::beginEditName, detail = UiText.Verbatim(device.nodeName))
            ActionRow(res(AppSettingsStrings.deviceInfoPublicKey), true, { onOpenSubpage(SettingsSubpage.PublicKey(device.publicKey)) })
            ActionRow(res(AppSettingsStrings.deviceInfoShareContact), true, { navigator.open(SettingsExternalDestination.ShareContactQr) })
        }
        SettingsSection(res(AppSettingsStrings.deviceInfoConnectionHeader)) {
            Row(Modifier.fillMaxWidth().sharedTouchTarget(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(stringResource(AppSettingsStrings.deviceInfoConnectionStatus))
                Text(stringResource(AppSettingsStrings.deviceConnected), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsSection(res(AppSettingsStrings.deviceInfoPowerStorageHeader)) {
            val battery = state.battery
            if (battery == null) {
                Row(Modifier.fillMaxWidth().sharedTouchTarget(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(AppSettingsStrings.deviceInfoBatteryAndStorage))
                    CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
                }
            } else {
                InfoRow(res(AppSettingsStrings.deviceInfoBattery), UiText.Verbatim("${battery.percentage}% (${state.voltageText})"))
                InfoRow(res(AppSettingsStrings.deviceInfoStorageUsed), UiText.Verbatim(storageText(state.storageUsedBytes, state.storageTotalBytes)))
                StorageBar(state.storageUsageRatio, state.storageBand, storageText(state.storageUsedBytes, state.storageTotalBytes))
            }
        }
        SettingsSection(res(AppSettingsStrings.deviceInfoFirmwareHeader)) {
            val unknown = res(AppSettingsStrings.deviceInfoUnknown)
            InfoRow(res(AppSettingsStrings.deviceInfoFirmwareVersion),
                state.firmwareVersionText?.let { UiText.Verbatim(it) }
                    ?: UiText.Verbatim(AppSettingsStrings.deviceInfoFirmwareVersionFormat(resources, state.firmwareVersionNumber.toString())))
            InfoRow(res(AppSettingsStrings.deviceInfoBuildDate), state.buildDate?.let { UiText.Verbatim(it) } ?: unknown)
            InfoRow(res(AppSettingsStrings.deviceInfoManufacturer), state.manufacturer?.let { UiText.Verbatim(it) } ?: unknown)
        }
        SettingsSection(res(AppSettingsStrings.deviceInfoCapabilitiesHeader)) {
            InfoRow(res(AppSettingsStrings.deviceInfoMaxNodes), UiText.Verbatim(device.maxContacts.toString()))
            InfoRow(res(AppSettingsStrings.deviceInfoMaxChannels), UiText.Verbatim(device.maxChannels.toString()))
            InfoRow(res(AppSettingsStrings.deviceInfoMaxTxPower), UiText.Verbatim(AppSettingsStrings.deviceInfoTxPowerFormat(resources, device.maxTxPower.toString())))
        }
    }
    if (state.isEditingName) {
        AlertDialog(
            onDismissRequest = holder::cancelEditName,
            title = { Text(stringResource(AppSettingsStrings.nodeAlertEditNameTitle)) },
            text = { OutlinedTextField(state.nodeName, holder::onNodeNameChanged, singleLine = true, label = { Text(stringResource(AppSettingsStrings.nodeName)) },
                modifier = Modifier.fillMaxWidth().sharedTouchTarget()) },
            confirmButton = { TextButton(holder::saveNodeName, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonSave)) } },
            dismissButton = { TextButton(holder::cancelEditName, Modifier.sharedTouchTarget()) { Text(stringResource(AppLocalizableStrings.commonCancel)) } },
        )
    }
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}

@Composable
private fun InfoRow(label: UiText, value: UiText) {
    val text = "${uiString(label)}, ${uiString(value)}"
    Row(Modifier.fillMaxWidth().sharedTouchTarget().semantics(mergeDescendants = true) { contentDescription = text },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(uiString(label), style = MaterialTheme.typography.bodyLarge)
        Text(uiString(value), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "used / total" in binary units; `ByteCountFormatStyle(.memory)` is approximated by android's short file size. */
@Composable
private fun storageText(used: Long, total: Long): String {
    val context = LocalContext.current
    return android.text.format.Formatter.formatShortFileSize(context, used) + " / " + android.text.format.Formatter.formatShortFileSize(context, total)
}

@Composable
private fun StorageBar(ratio: Double, band: StorageUsageBand, description: String) {
    val color = when (band) {
        StorageUsageBand.NORMAL -> Color(0xFF2E7D32)
        StorageUsageBand.WARNING -> Color(0xFFEF6C00)
        StorageUsageBand.CRITICAL -> Color(0xFFC62828)
    }
    val track = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.fillMaxWidth().height(8.dp).padding(vertical = 0.dp).semantics { contentDescription = description }) {
        drawRoundRect(track, size = size)
        drawRoundRect(color, size = Size((size.width * ratio.toFloat().coerceIn(0f, 1f)), size.height))
    }
}
