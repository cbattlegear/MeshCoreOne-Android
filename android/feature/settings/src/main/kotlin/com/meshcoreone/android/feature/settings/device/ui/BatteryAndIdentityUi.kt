// PortedFrom: MC1/Views/Settings/Sections/BatteryCurveSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/Components/BatteryCurveChart.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/Sections/RegenerateIdentitySheet.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.settings.device.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.model.OCVPreset
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.settings.device.BatteryCurveStateHolder
import com.meshcoreone.android.feature.settings.device.BatteryCurveValidationError
import com.meshcoreone.android.feature.settings.device.RegenerateIdentityStateHolder
import kotlinx.coroutines.launch

@Composable
internal fun BatteryCurveSection(holder: BatteryCurveStateHolder) {
    val state by holder.state.collectAsState()
    val resources = LocalContext.current.resources
    val presetOptions = state.presets.map { UiText.Verbatim(it.displayName) to it } +
        if (state.showsCustomRow) listOf(res(AppSettingsStrings.batteryCurveCustom) to OCVPreset.CUSTOM) else emptyList()
    val current = if (state.selectedPreset == OCVPreset.CUSTOM) res(AppSettingsStrings.batteryCurveCustom) else UiText.Verbatim(state.selectedPreset.displayName)
    SettingsSection(res(AppSettingsStrings.batteryCurveHeader), listOf(res(AppSettingsStrings.batteryCurveFooter))) {
        DropdownRow(res(AppSettingsStrings.batteryCurvePreset), current, presetOptions, !state.isDisabled, holder::onPresetSelected)
        BatteryCurveChart(state.voltages)
        ActionRow(res(AppSettingsStrings.batteryCurveEditValues), !state.isDisabled, { holder.setEditingValues(!state.isEditingValues) })
        if (state.isEditingValues) {
            state.voltages.forEachIndexed { index, value ->
                VoltageField(index, value, state.fieldHasError(index), !state.isDisabled, holder)
            }
        }
        state.validationError?.let { error ->
            val text = when (error) {
                is BatteryCurveValidationError.OutOfRange -> AppSettingsStrings.batteryCurveValidationOutOfRange(resources, error.percent)
                BatteryCurveValidationError.NotDescending -> stringResource(AppSettingsStrings.batteryCurveValidationNotDescending)
            }
            Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
        }
    }
}

/** One millivolt field. Commits when focus leaves the field or on Done, never per keystroke. */
@Composable
private fun VoltageField(index: Int, value: Long, hasError: Boolean, enabled: Boolean, holder: BatteryCurveStateHolder) {
    val resources = LocalContext.current.resources
    val percent = (10 - index) * 10
    var text by remember(value) { mutableStateOf(value.toString()) }
    val label = AppSettingsStrings.batteryCurveAccessibilityVoltageLabel(resources, percent)
    val reading = AppSettingsStrings.batteryCurveAccessibilityVoltageValue(resources, value.toInt())
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$percent%", Modifier.width(48.dp), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            value = text, onValueChange = { text = it; holder.onVoltageTextChanged(index, it) }, enabled = enabled, singleLine = true, isError = hasError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { holder.onFieldSubmitted(index) }),
            modifier = Modifier.weight(1f).sharedTouchTarget().onFocusChanged { holder.onFieldFocusChanged(index, it.isFocused) }
                .semantics { contentDescription = "$label, $reading" },
        )
        Text(stringResource(AppSettingsStrings.batteryCurveMV), style = MaterialTheme.typography.bodySmall)
    }
}

/** The voltage-to-percentage curve, with a spoken summary of its endpoints for TalkBack. */
@Composable
private fun BatteryCurveChart(voltages: List<Long>) {
    val line = MaterialTheme.colorScheme.primary
    val resources = LocalContext.current.resources
    val summary = if (voltages.size > 1) {
        AppSettingsStrings.batteryCurveAccessibilityVoltageValue(resources, voltages.first().toInt()) + " → " +
            AppSettingsStrings.batteryCurveAccessibilityVoltageValue(resources, voltages.last().toInt())
    } else ""
    Canvas(Modifier.fillMaxWidth().height(96.dp).padding(vertical = 8.dp).semantics { contentDescription = summary }) {
        if (voltages.size < 2) return@Canvas
        val high = voltages.max().toFloat()
        val low = voltages.min().toFloat()
        val span = (high - low).coerceAtLeast(1f)
        val points = voltages.mapIndexed { i, v ->
            Offset(size.width * (voltages.lastIndex - i) / voltages.lastIndex, size.height * (1f - (v - low) / span))
        }
        points.zipWithNext().forEach { (a, b) -> drawLine(line, a, b, strokeWidth = 3.dp.toPx()) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RegenerateIdentitySheet(holder: RegenerateIdentityStateHolder, onDismiss: () -> Unit) {
    val state by holder.state.collectAsState()
    val scope = rememberCoroutineScope()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(AppSettingsStrings.regenerateIdentitySheetTitle), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(AppSettingsStrings.regenerateIdentitySheetExplanation), style = MaterialTheme.typography.bodyMedium)
            ValidatedField(
                res(AppSettingsStrings.regenerateIdentityPrefixLabel), state.hexPrefix, holder::sanitizePrefix, !state.isBusy, KeyboardType.Ascii,
                error = state.prefixError, supporting = res(AppSettingsStrings.regenerateIdentityPrefixFooter),
                placeholder = res(AppSettingsStrings.regenerateIdentityPrefixPlaceholder),
            )
            TextButton(holder::generateKey, Modifier.fillMaxWidth().sharedTouchTarget(), enabled = !state.isBusy) {
                Text(stringResource(if (state.isGenerating) AppSettingsStrings.regenerateIdentityGenerating else AppSettingsStrings.regenerateIdentityGenerate))
            }
            state.generatedKey?.let { key ->
                Text(key.publicKeyHex, Modifier.semantics { contentDescription = key.accessibilityLabel }, fontFamily = FontFamily.Monospace)
                TextButton(holder::requestReplace, Modifier.fillMaxWidth().sharedTouchTarget(), enabled = !state.isBusy) {
                    Text(stringResource(if (state.isImporting) AppSettingsStrings.regenerateIdentityImporting else AppSettingsStrings.regenerateIdentityReplace),
                        color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    ConfirmDialog(state.showingReplaceAlert, res(AppSettingsStrings.regenerateIdentityAlertReplaceTitle), res(AppSettingsStrings.regenerateIdentityAlertReplaceMessage),
        res(AppSettingsStrings.regenerateIdentityAlertReplaceConfirm),
        { holder.dismissReplaceAlert(); scope.launch { if (holder.replaceIdentity()) onDismiss() } }, holder::dismissReplaceAlert)
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
}
