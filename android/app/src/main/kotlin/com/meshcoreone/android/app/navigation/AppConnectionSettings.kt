// AndroidOnly: WP-211 Native connection chooser and companion Wi-Fi settings compared with current upstream.
package com.meshcoreone.android.app.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
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
import com.meshcoreone.android.core.l10n.RetainedUiStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException

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
        try {
            bluetoothId = feature.bluetoothEndpoint(device)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            feature.report("Bluetooth endpoint lookup failed", failure)
        }
    }
    val dial = ConnectionLinkWiFiDial.capture(
        transport, device?.connectionMethods?.filterIsInstance<ConnectionMethod.WiFi>()?.firstOrNull(),
        wifi.address.takeIf { wifi.associated == true },
    )
    val rows = ConnectionLinkRows.make(
        transport, bluetoothId, when (wifi.support) {
            RadioWiFiSupport.UNKNOWN -> ConnectionLinkRows.WiFiSupport.UNKNOWN
            RadioWiFiSupport.SUPPORTED -> ConnectionLinkRows.WiFiSupport.SUPPORTED
            RadioWiFiSupport.UNAVAILABLE -> ConnectionLinkRows.WiFiSupport.UNAVAILABLE
        }, wifi.associated == true, dial, switching,
    )
    when {
        wifi.editor != null -> RadioWiFiEditor(feature.radioWiFi, wifi)
        page == "bluetooth" -> bluetoothPage(back)
        page == "address" -> AppWifiSettings(feature, device, back)
        page == "wifi" -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(S.connectionLinkWifiInfo), style = MaterialTheme.typography.titleLarge)
            if (transport == TransportType.WIFI) {
                TextButton(onClick = { page = "address" }) { Text(stringResource(RetainedUiStrings.wifiHeader)) }
            }
            RadioWiFiSettings(feature.radioWiFi, wifi, switching?.target != null)
            TextButton(onClick = back) { Text(stringResource(L.commonDone)) }
        }
        else -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).selectableGroup(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(S.connectionHeader), style = MaterialTheme.typography.titleLarge)
            rows.rows.forEach { row ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.weight(1f).selectable(
                            selected = rows.checked == row.link,
                            enabled = row.canSwitch && rows.target == null,
                            role = Role.RadioButton,
                            onClick = { feature.switchConnectionLink(row.link, bluetoothId) },
                        ).heightIn(min = 48.dp).padding(vertical = 12.dp),
                    ) {
                        Text(stringResource(if (row.link == TransportType.BLUETOOTH) S.bluetoothHeader else S.connectionLinkWifi))
                        row.address?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        if (rows.target == row.link) Text(stringResource(S.connectionLinkSwitching))
                        else if (transport == row.link) Text(stringResource(S.connectionLinkInUse))
                    }
                    RadioButton(selected = rows.checked == row.link, onClick = null, enabled = row.canSwitch || rows.checked == row.link)
                    TextButton(onClick = { page = if (row.link == TransportType.BLUETOOTH) "bluetooth" else "wifi" }) {
                        Text(stringResource(if (row.link == TransportType.BLUETOOTH) S.connectionLinkBluetoothInfo else S.connectionLinkWifiInfo))
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
internal fun RadioWiFiSettings(
    controller: RadioWiFiController,
    state: RadioWiFiPresentation,
    switchInProgress: Boolean = false,
) {
    Text(stringResource(S.connectionLinkRadioWiFi), style = MaterialTheme.typography.titleMedium)
    if (state.support == RadioWiFiSupport.SUPPORTED) {
        val enabledLabel = stringResource(S.radioWiFiEnabled)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(enabledLabel, Modifier.weight(1f))
            Switch(
                checked = state.enabled, onCheckedChange = controller::requestEnabled,
                enabled = !switchInProgress && controller.canOperate(),
                modifier = Modifier.semantics { contentDescription = enabledLabel },
            )
        }
        Text(stringResource(S.radioWiFiFooter))
        if (state.networkCurrent) Text("${stringResource(S.radioWiFiNetwork)}: ${state.network ?: stringResource(S.radioWiFiNetworkNotSet)}")
        state.associated?.let {
            Text("${stringResource(S.radioWiFiStatus)}: ${stringResource(if (it) S.radioWiFiStatusConnected else S.radioWiFiStatusNotConnected)}")
        }
        state.address?.let { Text("${stringResource(S.radioWiFiIpAddress)}: $it") }
        TextButton(onClick = controller::openEditor, enabled = !switchInProgress && controller.canOperate()) {
            Text(stringResource(S.radioWiFiNetworkName))
        }
        TextButton(onClick = controller::requestClear, enabled = !switchInProgress && controller.canOperate() && state.networkCurrent && state.network != null) {
            Text(stringResource(S.radioWiFiClear))
        }
        if (state.pendingReboot) Button(onClick = controller::requestReboot, enabled = !switchInProgress && controller.canReboot()) {
            Text(stringResource(S.radioWiFiRebootToApply))
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
internal fun RadioWiFiEditor(controller: RadioWiFiController, state: RadioWiFiPresentation) {
    val editor = state.editor ?: return
    BackHandler { controller.closeEditor() }
    var showPassword by rememberSaveable(editor.id.toString()) { mutableStateOf(false) }
    val networkName = stringResource(S.radioWiFiNetworkName)
    val password = stringResource(S.radioWiFiPassword)
    val validation = when {
        editor.ssid.isEmpty() -> S.radioWiFiEditorEmptyName
        editor.ssid.toByteArray(Charsets.UTF_8).size > 32 -> S.radioWiFiEditorNameLength
        editor.password.toByteArray(Charsets.UTF_8).size > 63 -> S.radioWiFiEditorPasswordLength
        !RadioWiFiCredentials.isRepresentable(editor.ssid) || !RadioWiFiCredentials.isRepresentable(editor.password) ->
            S.radioWiFiEditorUnsupportedCharacter
        else -> null
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(S.radioWiFiNetwork), style = MaterialTheme.typography.titleLarge)
        OutlinedTextField(
            editor.ssid, controller::setSsid, Modifier.fillMaxWidth().semantics { contentDescription = networkName },
            enabled = !state.busy, label = { Text(networkName) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        OutlinedTextField(
            editor.password, controller::setPassword, Modifier.fillMaxWidth().semantics { contentDescription = password },
            enabled = !state.busy, label = { Text(password) }, singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
        )
        TextButton(onClick = { showPassword = !showPassword }) {
            Text(stringResource(if (showPassword) S.radioWiFiHidePassword else S.radioWiFiShowPassword))
        }
        Text(
            stringResource(validation ?: S.radioWiFiEditorFooter),
            color = if (validation != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (editor.baseline == null && !state.busy) TextButton(onClick = controller::readEditor) { Text(stringResource(L.commonTryAgain)) }
        if (state.busy) CircularProgressIndicator()
        TextButton(onClick = controller::requestSave, enabled = controller.canSave()) { Text(stringResource(L.commonSave)) }
        TextButton(onClick = controller::closeEditor, enabled = !state.writing) { Text(stringResource(L.commonCancel)) }
    }
}

@Composable
internal fun RadioWiFiDialogs(controller: RadioWiFiController, state: RadioWiFiPresentation, transport: TransportType?) {
    state.confirmation?.let { confirmation ->
        val title = when (confirmation) {
            RadioWiFiConfirmation.SAVE -> S.radioWiFiAlertSaveTitle
            RadioWiFiConfirmation.DISABLE -> S.radioWiFiAlertTurnOffTitle
            RadioWiFiConfirmation.CLEAR -> S.radioWiFiAlertClearTitle
            RadioWiFiConfirmation.REBOOT -> S.radioWiFiAlertRebootTitle
        }
        val message = when (confirmation) {
            RadioWiFiConfirmation.SAVE, RadioWiFiConfirmation.DISABLE -> stringResource(S.radioWiFiAlertWifiTransportMessage)
            RadioWiFiConfirmation.CLEAR -> stringResource(if (transport == TransportType.WIFI) S.radioWiFiAlertClearWifiMessage else S.radioWiFiAlertClearMessage)
            RadioWiFiConfirmation.REBOOT -> when {
                !state.enabled -> stringResource(S.radioWiFiAlertRebootDisabled)
                state.networkCurrent && !state.network.isNullOrEmpty() -> S.radioWiFiAlertRebootJoin(LocalContext.current.resources, state.network)
                else -> stringResource(S.radioWiFiAlertRebootRestart)
            }
        }
        AlertDialog(onDismissRequest = controller::cancelConfirmation, title = { Text(stringResource(title)) },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = controller::confirm) { Text(stringResource(when (confirmation) {
                RadioWiFiConfirmation.SAVE -> L.commonSave
                RadioWiFiConfirmation.DISABLE -> S.radioWiFiAlertTurnOffConfirm
                RadioWiFiConfirmation.CLEAR -> S.radioWiFiClear
                RadioWiFiConfirmation.REBOOT -> S.radioWiFiRebootToApply
            })) } },
            dismissButton = { TextButton(onClick = controller::cancelConfirmation) { Text(stringResource(L.commonCancel)) } })
    }
    state.error?.let { problem ->
        AlertDialog(onDismissRequest = controller::dismissError,
            text = { Text(uiString(UiErrorMapper().message(problem))) },
            confirmButton = { TextButton(onClick = controller::dismissError) { Text(stringResource(L.commonOk)) } })
    }
}
