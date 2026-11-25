// AndroidOnly: WP-211 Native connection chooser and companion Wi-Fi settings compared with current upstream.
package com.meshcoreone.android.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.R
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.app.state.ConnectionLinkRows
import com.meshcoreone.android.app.state.ConnectionLinkWiFiDial
import com.meshcoreone.android.core.contracts.domain.ConnectionSnapshot
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.services.device.RadioWiFiCredentials
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S

@Composable
internal fun AppConnectionSettings(
    feature: AppSettingsFeature,
    device: DeviceDTO?,
    snapshot: ConnectionSnapshot,
    transport: TransportType?,
    onDone: () -> Unit,
    bluetoothPage: @Composable (() -> Unit) -> Unit,
) {
    val wifi by feature.radioWiFi.state.collectAsState()
    val switching by feature.linkSwitch.collectAsState()
    var page by rememberSaveable { mutableStateOf("links") }
    val back = {
        page = when (page) { "address" -> "wifi"; else -> "links" }
    }
    BackHandler(page != "links", back)
    val servicesVersion by feature.container.appState.servicesVersion.collectAsState()
    LaunchedEffect(snapshot.token, snapshot.state, servicesVersion, device?.supportsRadioCLI) { feature.bindRadioWiFi() }
    var bluetoothId by remember(device?.id, device?.connectionMethods) { mutableStateOf<java.util.UUID?>(null) }
    LaunchedEffect(device?.id, device?.connectionMethods) {
        bluetoothId = feature.bluetoothEndpoint(device)
    }
    val dial = ConnectionLinkWiFiDial.capture(
        transport, device?.connectionMethods?.filterIsInstance<ConnectionMethod.WiFi>()?.firstOrNull(), wifi.address,
    )
    val rows = ConnectionLinkRows.make(
        transport, bluetoothId, when (wifi.support) {
            RadioWiFiSupport.UNKNOWN -> ConnectionLinkRows.WiFiSupport.UNKNOWN
            RadioWiFiSupport.SUPPORTED -> ConnectionLinkRows.WiFiSupport.SUPPORTED
            RadioWiFiSupport.UNAVAILABLE -> ConnectionLinkRows.WiFiSupport.UNAVAILABLE
        }, wifi.associated == true, dial, switching,
    )
    when (page) {
        "bluetooth" -> bluetoothPage(back)
        "address" -> AppWifiSettings(feature, device, back)
        "wifi" -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.radio_wifi_connection_link_wifiinfo), style = MaterialTheme.typography.titleLarge)
            if (transport == TransportType.WIFI) {
                TextButton(onClick = { page = "address" }) { Text(stringResource(S.wifiHeader)) }
            }
            RadioWiFiSettings(feature.radioWiFi, wifi)
            TextButton(onClick = back) { Text(stringResource(L.commonDone)) }
        }
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.radio_wifi_connection_header), style = MaterialTheme.typography.titleLarge)
            rows.rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.weight(1f).selectable(
                            selected = rows.checked == row.link,
                            enabled = row.canSwitch && rows.target == null,
                            role = Role.RadioButton,
                            onClick = { feature.switchConnectionLink(row.link, bluetoothId, dial) },
                        ).heightIn(min = 48.dp).padding(vertical = 12.dp),
                    ) {
                        Text(stringResource(if (row.link == TransportType.BLUETOOTH) S.bluetoothHeader else R.string.radio_wifi_connection_link_wifi))
                        row.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (rows.target == row.link) Text(stringResource(R.string.radio_wifi_connection_link_switching))
                        else if (rows.checked == row.link) Text(stringResource(R.string.radio_wifi_connection_link_inuse))
                    }
                    RadioButton(selected = rows.checked == row.link, onClick = null, enabled = row.canSwitch || rows.checked == row.link)
                    TextButton(onClick = { page = if (row.link == TransportType.BLUETOOTH) "bluetooth" else "wifi" }) {
                        Text(stringResource(if (row.link == TransportType.BLUETOOTH) R.string.radio_wifi_connection_link_bluetoothinfo else R.string.radio_wifi_connection_link_wifiinfo))
                    }
                }
            }
            if (wifi.busy) CircularProgressIndicator()
            else if (wifi.support == RadioWiFiSupport.UNKNOWN && device?.supportsRadioCLI == true) {
                TextButton(onClick = feature.radioWiFi::refresh) { Text(stringResource(L.commonTryAgain)) }
            }
            TextButton(onClick = onDone) { Text(stringResource(L.commonDone)) }
        }
    }
    RadioWiFiDialogs(feature.radioWiFi, wifi, transport)
}

@Composable
private fun RadioWiFiSettings(controller: RadioWiFiController, state: RadioWiFiPresentation) {
    Text(stringResource(R.string.radio_wifi_connection_link_radiowifi), style = MaterialTheme.typography.titleMedium)
    if (state.support == RadioWiFiSupport.SUPPORTED) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.radio_wifi_radiowifi_enabled), Modifier.weight(1f))
            Switch(checked = state.enabled, onCheckedChange = controller::requestEnabled, enabled = controller.canOperate())
        }
        Text(stringResource(R.string.radio_wifi_radiowifi_footer))
        if (state.networkCurrent) Text("${stringResource(R.string.radio_wifi_radiowifi_network)}: ${state.network ?: stringResource(R.string.radio_wifi_radiowifi_network_notset)}")
        state.associated?.let {
            Text("${stringResource(R.string.radio_wifi_radiowifi_status)}: ${stringResource(if (it) R.string.radio_wifi_radiowifi_status_connected else R.string.radio_wifi_radiowifi_status_notconnected)}")
        }
        state.address?.let { Text("${stringResource(R.string.radio_wifi_radiowifi_ipaddress)}: $it") }
        TextButton(onClick = controller::openEditor, enabled = controller.canOperate()) {
            Text(stringResource(R.string.radio_wifi_radiowifi_networkname))
        }
        TextButton(onClick = controller::requestClear, enabled = controller.canOperate() && state.networkCurrent && state.network != null) {
            Text(stringResource(R.string.radio_wifi_radiowifi_clear))
        }
        if (state.pendingReboot) Button(onClick = controller::requestReboot, enabled = controller.canReboot()) {
            Text(stringResource(R.string.radio_wifi_radiowifi_reboottoapply))
        }
    } else {
        when {
            state.busy -> CircularProgressIndicator()
            state.support == RadioWiFiSupport.UNAVAILABLE -> Text(stringResource(L.errorDeviceUnsupportedCommand))
            else -> Text(stringResource(L.commonErrorFailedToLoad))
        }
    }
    if (state.support != RadioWiFiSupport.UNAVAILABLE && !state.busy) {
        TextButton(onClick = controller::refresh) { Text(stringResource(L.commonTryAgain)) }
    }
}

@Composable
internal fun RadioWiFiDialogs(controller: RadioWiFiController, state: RadioWiFiPresentation, transport: TransportType?) {
    state.editor?.let { editor ->
        var showPassword by rememberSaveable(editor.id.toString()) { mutableStateOf(false) }
        val validation = when {
            !RadioWiFiCredentials.isRepresentable(editor.ssid) || !RadioWiFiCredentials.isRepresentable(editor.password) ->
                R.string.radio_wifi_radiowifi_editor_unsupportedcharacter
            editor.ssid.isEmpty() -> R.string.radio_wifi_radiowifi_editor_emptyname
            editor.ssid.toByteArray(Charsets.UTF_8).size > 32 -> R.string.radio_wifi_radiowifi_editor_namelength
            editor.password.toByteArray(Charsets.UTF_8).size > 63 -> R.string.radio_wifi_radiowifi_editor_passwordlength
            else -> null
        }
        AlertDialog(
            onDismissRequest = controller::closeEditor,
            title = { Text(stringResource(R.string.radio_wifi_radiowifi_network)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(editor.ssid, controller::setSsid, label = { Text(stringResource(R.string.radio_wifi_radiowifi_networkname)) }, enabled = !state.writing, singleLine = true)
                    OutlinedTextField(editor.password, controller::setPassword, label = { Text(stringResource(R.string.radio_wifi_radiowifi_password)) }, enabled = !state.writing, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation())
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(stringResource(if (showPassword) R.string.radio_wifi_radiowifi_hidepassword else R.string.radio_wifi_radiowifi_showpassword))
                    }
                    Text(stringResource(R.string.radio_wifi_radiowifi_editor_footer))
                    if ((editor.ssidEdited || editor.passwordEdited) && validation != null) Text(stringResource(validation), color = MaterialTheme.colorScheme.error)
                    if (editor.baseline == null && !state.busy) TextButton(onClick = controller::readEditor) { Text(stringResource(L.commonTryAgain)) }
                    if (state.busy) CircularProgressIndicator()
                }
            },
            confirmButton = { TextButton(onClick = controller::requestSave, enabled = controller.canSave()) { Text(stringResource(L.commonSave)) } },
            dismissButton = { TextButton(onClick = controller::closeEditor, enabled = !state.writing) { Text(stringResource(L.commonCancel)) } },
        )
    }
    state.confirmation?.let { confirmation ->
        val title = when (confirmation) {
            RadioWiFiConfirmation.SAVE -> R.string.radio_wifi_radiowifi_alert_save_title
            RadioWiFiConfirmation.DISABLE -> R.string.radio_wifi_radiowifi_alert_turnoff_title
            RadioWiFiConfirmation.CLEAR -> R.string.radio_wifi_radiowifi_alert_clear_title
            RadioWiFiConfirmation.REBOOT -> R.string.radio_wifi_radiowifi_alert_reboot_title
        }
        val message = when (confirmation) {
            RadioWiFiConfirmation.SAVE, RadioWiFiConfirmation.DISABLE -> stringResource(R.string.radio_wifi_radiowifi_alert_wifitransport_message)
            RadioWiFiConfirmation.CLEAR -> stringResource(if (transport == TransportType.WIFI) R.string.radio_wifi_radiowifi_alert_clear_wifimessage else R.string.radio_wifi_radiowifi_alert_clear_message)
            RadioWiFiConfirmation.REBOOT -> when {
                !state.enabled -> stringResource(R.string.radio_wifi_radiowifi_alert_reboot_disabled)
                state.networkCurrent && !state.network.isNullOrEmpty() -> stringResource(R.string.radio_wifi_radiowifi_alert_reboot_join, state.network)
                else -> stringResource(R.string.radio_wifi_radiowifi_alert_reboot_restart)
            }
        }
        AlertDialog(onDismissRequest = controller::cancelConfirmation, title = { Text(stringResource(title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::confirm) { Text(stringResource(when (confirmation) {
                RadioWiFiConfirmation.SAVE -> L.commonSave
                RadioWiFiConfirmation.DISABLE -> R.string.radio_wifi_radiowifi_alert_turnoff_confirm
                RadioWiFiConfirmation.CLEAR -> R.string.radio_wifi_radiowifi_clear
                RadioWiFiConfirmation.REBOOT -> R.string.radio_wifi_radiowifi_reboottoapply
            })) } },
            dismissButton = { TextButton(onClick = controller::cancelConfirmation) { Text(stringResource(L.commonCancel)) } })
    }
    state.error?.let { problem ->
        AlertDialog(onDismissRequest = controller::dismissError,
            text = { Text(uiString(UiErrorMapper().message(problem))) },
            confirmButton = { TextButton(onClick = controller::dismissError) { Text(stringResource(L.commonOk)) } })
    }
}
