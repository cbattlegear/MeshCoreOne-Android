// AndroidOnly: WP-217 Present both saved simulator device snapshots before choosing one.
package com.meshcoreone.android.app.container

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.R
import com.meshcoreone.android.core.data.repository.SimulatorPreferenceChoice
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.ui.uiString
import java.text.NumberFormat
import java.util.Locale

@Composable
internal fun SimulatorPreferenceRecoveryDialog(recovery: SimulatorPreferenceRecovery) {
    val state by recovery.prompt.collectAsStateWithLifecycle()
    val prompt = state ?: return
    val promptId = prompt.id.toString()
    var selectedPromptId by rememberSaveable(prompt.id) { mutableStateOf<String?>(null) }
    var savedChoice by rememberSaveable(prompt.id) { mutableStateOf<SimulatorPreferenceChoice?>(null) }
    val choice = savedChoice.takeIf { selectedPromptId == promptId }
    fun select(value: SimulatorPreferenceChoice) {
        selectedPromptId = promptId
        savedChoice = value
    }
    val cancel: () -> Unit = { if (!prompt.isApplying) recovery.cancel(prompt.id) }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val notSet = stringResource(R.string.simulator_preference_recovery_not_set)
    val emptyText = stringResource(R.string.simulator_preference_recovery_empty_text)
    val noRegions = stringResource(R.string.simulator_preference_recovery_no_regions)
    AlertDialog(
        onDismissRequest = cancel,
        properties = DialogProperties(
            dismissOnBackPress = !prompt.isApplying,
            dismissOnClickOutside = !prompt.isApplying,
        ),
        title = {
            Text(
                stringResource(R.string.simulator_preference_recovery_title),
                Modifier.semantics { heading() },
            )
        },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("simulator-preference-recovery"),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(R.string.simulator_preference_recovery_explanation))
                Text(stringResource(R.string.simulator_preference_recovery_device_details))
                prompt.failure?.let { failure ->
                    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                        Text(
                            stringResource(R.string.simulator_preference_recovery_retry),
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(uiString(failure), style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (prompt.isApplying) {
                    Text(
                        stringResource(R.string.simulator_preference_recovery_applying),
                        Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SimulatorPreferenceSnapshot(
                        stringResource(R.string.simulator_preference_recovery_current),
                        prompt.conflict.canonical,
                        choice == SimulatorPreferenceChoice.KEEP_CANONICAL,
                        !prompt.isApplying,
                        { select(SimulatorPreferenceChoice.KEEP_CANONICAL) },
                        "simulator-preference-current", locale, notSet, emptyText, noRegions,
                    )
                    HorizontalDivider()
                    SimulatorPreferenceSnapshot(
                        stringResource(R.string.simulator_preference_recovery_recovered),
                        prompt.conflict.imported,
                        choice == SimulatorPreferenceChoice.KEEP_IMPORTED,
                        !prompt.isApplying,
                        { select(SimulatorPreferenceChoice.KEEP_IMPORTED) },
                        "simulator-preference-recovered", locale, notSet, emptyText, noRegions,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = choice != null && !prompt.isApplying,
                onClick = { choice?.let { recovery.choose(prompt.id, it) } },
                modifier = Modifier.heightIn(min = 48.dp).testTag("simulator-preference-apply"),
            ) { Text(stringResource(R.string.simulator_preference_recovery_apply)) }
        },
        dismissButton = {
            TextButton(
                enabled = !prompt.isApplying, onClick = cancel,
                modifier = Modifier.heightIn(min = 48.dp).testTag("simulator-preference-cancel"),
            ) { Text(stringResource(L.commonCancel)) }
        },
    )
}

@Composable
private fun SimulatorPreferenceSnapshot(
    label: String,
    device: DeviceDTO,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    tag: String,
    locale: Locale,
    notSet: String,
    emptyText: String,
    noRegions: String,
) {
    val number = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 6
    }
    val coordinate = NumberFormat.getNumberInstance(locale).apply {
        isGroupingUsed = false
        maximumFractionDigits = 340
    }
    val on = stringResource(L.accessibilityOn)
    val off = stringResource(L.accessibilityOff)
    fun boolean(value: Boolean) = if (value) on else off
    val fields = listOf(
        Triple("flood-scope", stringResource(R.string.simulator_preference_recovery_flood_scope),
            preferenceText(device.defaultFloodScopeName, notSet, emptyText)),
        Triple("repeat-frequency", stringResource(R.string.simulator_preference_recovery_repeat_frequency),
            device.preRepeatFrequency?.let { number.format(it.toLong() / 1000.0) } ?: notSet),
        Triple("repeat-bandwidth", stringResource(R.string.simulator_preference_recovery_repeat_bandwidth),
            device.preRepeatBandwidth?.let { number.format(it.toLong() / 1000.0) } ?: notSet),
        Triple("repeat-spreading-factor", stringResource(R.string.simulator_preference_recovery_repeat_spreading_factor),
            device.preRepeatSpreadingFactor?.toString() ?: notSet),
        Triple("repeat-coding-rate", stringResource(R.string.simulator_preference_recovery_repeat_coding_rate),
            device.preRepeatCodingRate?.toString() ?: notSet),
        Triple("last-contact-sync", stringResource(R.string.simulator_preference_recovery_last_contact_sync),
            device.lastContactSync.toString()),
        Triple("ocv-preset", stringResource(R.string.simulator_preference_recovery_ocv_preset),
            preferenceText(device.ocvPreset, notSet, emptyText)),
        Triple("radio-preset", stringResource(R.string.simulator_preference_recovery_radio_preset),
            preferenceText(device.appliedRadioPresetID, notSet, emptyText)),
        Triple("custom-ocv", stringResource(R.string.simulator_preference_recovery_custom_ocv),
            preferenceText(device.customOCVArrayString, notSet, emptyText)),
        Triple("known-regions", stringResource(R.string.simulator_preference_recovery_known_regions),
            if (device.knownRegions.isEmpty()) noRegions else
                device.knownRegions.joinToString("\n") { preferenceText(it, notSet, emptyText) }),
        Triple("node-name", stringResource(S.nodeName), preferenceText(device.nodeName, notSet, emptyText)),
        Triple("firmware", stringResource(S.deviceInfoFirmwareVersion),
            "${preferenceText(device.firmwareVersionString, notSet, emptyText)} (${device.firmwareVersion})"),
        Triple("manufacturer", stringResource(S.deviceInfoManufacturer),
            preferenceText(device.manufacturerName, notSet, emptyText)),
        Triple("build-date", stringResource(S.deviceInfoBuildDate), preferenceText(device.buildDate, notSet, emptyText)),
        Triple("max-contacts", stringResource(S.deviceInfoMaxNodes), device.maxContacts.toString()),
        Triple("max-channels", stringResource(S.deviceInfoMaxChannels), device.maxChannels.toString()),
        Triple("frequency", stringResource(S.advancedRadioFrequency), number.format(device.frequency.toLong() / 1000.0)),
        Triple("bandwidth", stringResource(S.advancedRadioBandwidth), number.format(device.bandwidth.toLong() / 1000.0)),
        Triple("spreading-factor", stringResource(S.advancedRadioSpreadingFactor), device.spreadingFactor.toString()),
        Triple("coding-rate", stringResource(S.advancedRadioCodingRate), device.codingRate.toString()),
        Triple("tx-power", stringResource(S.advancedRadioTxPower), device.txPower.toString()),
        Triple("max-tx-power", stringResource(S.deviceInfoMaxTxPower), device.maxTxPower.toString()),
        Triple("latitude", stringResource(S.locationPickerLatitude), coordinate.format(device.latitude)),
        Triple("longitude", stringResource(S.locationPickerLongitude), coordinate.format(device.longitude)),
        Triple("pin", stringResource(S.bluetoothCurrentPin), device.blePin.toString()),
        Triple("repeat", stringResource(S.radioRepeatMode), boolean(device.clientRepeat)),
        Triple("path-hash", stringResource(S.pathHashModeLabel), device.pathHashMode.toString()),
        Triple("manual-add", stringResource(S.nodesAutoAddModeManual), boolean(device.manualAddContacts)),
        Triple("auto-add-config", stringResource(S.nodesAutoAddMode), device.autoAddConfig.toString()),
        Triple("auto-add-max-hops", stringResource(S.nodesMaxHops), device.autoAddMaxHops.toString()),
        Triple("multi-acks", stringResource(S.directMessagesAcknowledgments), device.multiAcks.toString()),
        Triple("telemetry-base", stringResource(S.telemetryAllowRequests), device.telemetryModeBase.toString()),
        Triple("telemetry-location", stringResource(S.telemetryIncludeLocation), device.telemetryModeLoc.toString()),
        Triple("telemetry-environment", stringResource(S.telemetryIncludeEnvironment), device.telemetryModeEnv.toString()),
        Triple("advert-location", stringResource(S.nodeShareLocationPublicly), device.advertLocationPolicy.toString()),
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = enabled)
            Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleSmall)
        }
        fields.forEach { (key, field, value) ->
            Column(Modifier.fillMaxWidth().padding(start = 48.dp)) {
                Text(field, style = MaterialTheme.typography.labelMedium)
                Text(value, Modifier.testTag("$tag-$key"), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

internal fun preferenceText(value: String?, notSet: String, emptyText: String): String = when {
    value == null -> notSet
    value.isEmpty() -> "$emptyText (\"\")"
    else -> buildString {
        append('"')
        var offset = 0
        while (offset < value.length) {
            val codePoint = value.codePointAt(offset)
            when (codePoint) {
                '\\'.code -> append("\\\\")
                '"'.code -> append("\\\"")
                '\n'.code -> append("\\n")
                '\r'.code -> append("\\r")
                '\t'.code -> append("\\t")
                else -> {
                    val kind = Character.getType(codePoint)
                    if (Character.isISOControl(codePoint) || kind == Character.FORMAT.toInt() ||
                        kind == Character.SURROGATE.toInt() ||
                        kind == Character.LINE_SEPARATOR.toInt() || kind == Character.PARAGRAPH_SEPARATOR.toInt()
                    ) {
                        repeat(Character.charCount(codePoint)) { index ->
                            append("\\u")
                            append(value[offset + index].code.toString(16).padStart(4, '0'))
                        }
                    } else appendCodePoint(codePoint)
                }
            }
            offset += Character.charCount(codePoint)
        }
        append('"')
    }
}
