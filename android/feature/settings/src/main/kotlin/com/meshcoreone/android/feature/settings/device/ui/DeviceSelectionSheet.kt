// PortedFrom: MC1/Views/Settings/DeviceSelectionSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/SystemPairingSetupSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.settings.device.DeviceSelectionListBuilder
import com.meshcoreone.android.feature.settings.device.SystemPairedAccessory
import java.util.UUID

/**
 * Bottom sheet listing saved radios the user can reach plus accessories that still need setup. A radio another phone
 * holds stays visible but says so; only BLE-only rows are blocked by that BLE warning.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectionSheet(
    list: DeviceSelectionListBuilder.Result, connectedElsewhere: Set<UUID>,
    onSelect: (DeviceDTO) -> Unit, onSetUp: (SystemPairedAccessory) -> Unit, onScan: () -> Unit, onConnectViaWifi: () -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "title") {
                Text(stringResource(AppSettingsStrings.deviceSelectionTitle), style = MaterialTheme.typography.titleLarge)
            }
            if (list.connectable.isEmpty() && list.needsSetup.isEmpty()) {
                item(key = "empty-title") {
                    Text(stringResource(AppSettingsStrings.deviceSelectionNoPairedDevices), style = MaterialTheme.typography.titleMedium)
                }
                item(key = "empty-description") {
                    Text(stringResource(AppSettingsStrings.deviceSelectionNoPairedDescription))
                }
            }
            items(list.connectable, key = { "radio-${it.id}" }) { device ->
                val elsewhere = device.id in connectedElsewhere
                val detail = if (elsewhere) res(AppSettingsStrings.deviceSelectionConnectedElsewhere) else null
                val canConnect = !elsewhere || device.connectionMethods.any { it.isWiFi }
                ActionRow(UiText.Verbatim(device.nodeName), canConnect, { onSelect(device) }, detail = detail)
            }
            if (list.needsSetup.isNotEmpty()) {
                item(key = "needs-setup-title") {
                    Text(stringResource(AppSettingsStrings.deviceSelectionPreviouslyPaired), style = MaterialTheme.typography.titleSmall)
                }
                items(list.needsSetup, key = { "setup-${it.id}" }) { accessory ->
                    val row = DeviceSelectionListBuilder.needsSetupPresentation(accessory.name)
                    val label = uiString(row.accessibilityLabel)
                    val hint = uiString(row.accessibilityHint)
                    androidx.compose.foundation.layout.Box(Modifier.semantics { contentDescription = "$label. $hint" }) {
                        ActionRow(UiText.Verbatim(accessory.name), true, { onSetUp(accessory) }, detail = row.trailingTitle)
                    }
                }
            }
            item(key = "scan-bluetooth") {
                ActionRow(res(AppSettingsStrings.deviceSelectionScanBluetooth), true, onScan)
            }
            item(key = "connect-via-wifi") {
                ActionRow(res(AppSettingsStrings.deviceSelectionConnectViaWifi), true, onConnectViaWifi)
            }
        }
    }
}
