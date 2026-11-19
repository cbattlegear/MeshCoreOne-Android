// PortedFrom: MC1/Views/Settings/RadioSettingsView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/PresetLocationSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/RadioPresetSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/PathHashModeSection.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/Sections/AdvancedRadioSection.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.settings.device.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.ui.AsyncActionLabel
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.radioActionEnabled
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.feature.settings.device.AdvancedRadioStateHolder
import com.meshcoreone.android.feature.settings.device.PathHashModeStateHolder
import com.meshcoreone.android.feature.settings.device.PresetLocationPolicy
import com.meshcoreone.android.feature.settings.device.PresetLocationSession
import com.meshcoreone.android.feature.settings.device.RadioCatalogPort
import com.meshcoreone.android.feature.settings.device.RadioOptions
import com.meshcoreone.android.feature.settings.device.RadioPresetStateHolder
import com.meshcoreone.android.feature.settings.device.RadioWriteGate
import com.meshcoreone.android.feature.settings.device.RegionSelectionPort
import com.meshcoreone.android.feature.settings.device.SettingsExternalDestination
import com.meshcoreone.android.feature.settings.device.SettingsNavigator
import com.meshcoreone.android.feature.settings.device.SubdivisionCatalog
import com.meshcoreone.android.feature.settings.device.LocationPermissionPort
import com.meshcoreone.android.feature.settings.device.LocationAuthorization

/** Everything the Radio page binds to. Built once per page by the host. */
class RadioSettingsHolders(
    val gate: RadioWriteGate,
    val preset: RadioPresetStateHolder,
    val pathHash: PathHashModeStateHolder,
    val advanced: AdvancedRadioStateHolder,
    val locationSession: PresetLocationSession,
    val regions: RegionSelectionPort,
    val catalog: RadioCatalogPort,
    val location: LocationPermissionPort,
    val subdivisions: SubdivisionCatalog = PresetLocationPolicy.PinnedSubdivisionCatalog,
)

/** Settings > Radio. The location row is omitted while Repeat Mode is on. */
@Composable
fun RadioSettingsScreen(holders: RadioSettingsHolders, navigator: SettingsNavigator, onDone: (() -> Unit)?) {
    HolderLifecycle({ holders.preset.start(); holders.pathHash.start(); holders.advanced.start(); holders.locationSession.resolveOnAppear() },
        { holders.preset.stop(); holders.pathHash.stop(); holders.advanced.stop(); holders.locationSession.cancel() })
    val presetState by holders.preset.state.collectAsState()
    val pathState by holders.pathHash.state.collectAsState()
    val locationState by holders.locationSession.state.collectAsState()
    SettingsScreenFrame(res(AppSettingsStrings.radioHeader), onDone) {
        if (presetState.device?.clientRepeat != true) PresetLocationSection(holders, navigator)
        RadioPresetSection(holders)
        if (pathState.device?.supportsPathHashMode == true) PathHashModeSection(holders.pathHash)
        AdvancedRadioSection(holders.advanced, holders.gate)
    }
    ErrorMessageDialog(locationState.errorMessage, holders.locationSession::dismissError)
    ConfirmDialog(
        visible = locationState.showOpenSettingsAlert, title = res(AppSettingsStrings.alertErrorTitle),
        message = res(AppSettingsStrings.radioPresetLocationDenied), confirm = res(AppSettingsStrings.notificationsOpenSettings),
        onConfirm = { holders.locationSession.dismissOpenSettingsAlert(); navigator.open(SettingsExternalDestination.AppSystemSettings) },
        onDismiss = holders.locationSession::dismissOpenSettingsAlert, destructive = false,
    )
}

@Composable
private fun PresetLocationSection(holders: RadioSettingsHolders, navigator: SettingsNavigator) {
    val selection by holders.regions.selection.collectAsState()
    val authorization by holders.location.authorization.collectAsState()
    val session by holders.locationSession.state.collectAsState()
    val expanded = PresetLocationPolicy.shouldExpandOnRadio(authorization == LocationAuthorization.AUTHORIZED, selection, holders.subdivisions)
    val detail = selection?.let { UiText.Verbatim(holders.catalog.regionDisplayName(it)) } ?: res(AppSettingsStrings.radioPresetLocationNotSet)
    SettingsSection(null, listOf(res(AppSettingsStrings.radioPresetLocationFooter))) {
        if (expanded) {
            ActionRow(res(AppSettingsStrings.radioPresetLocation), true, { navigator.open(SettingsExternalDestination.RegionPicker) }, detail = detail)
            RowDivider()
            val locating = stringResource(AppSettingsStrings.radioPresetLocationUseMyLocationLocating)
            Row(Modifier.fillMaxWidth().semantics { if (session.isResolving) contentDescription = locating },
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (session.isResolving) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(locating)
                } else {
                    ActionRow(UiText.Resource(com.meshcoreone.android.core.l10n.R.string.l10n_app_onboarding_region_usemylocation), true, holders.locationSession::useMyLocation)
                }
            }
        } else {
            ActionRow(res(AppSettingsStrings.radioPresetLocation), true, { navigator.open(SettingsExternalDestination.RegionPicker) }, detail = detail)
        }
    }
}

@Composable
private fun RadioPresetSection(holders: RadioSettingsHolders) {
    val holder = holders.preset
    val state by holder.state.collectAsState()
    val inFlight by holders.gate.inFlight.collectAsState()
    val connected = state.connectionState == DeviceConnectionState.READY
    val custom = res(AppSettingsStrings.batteryCurveCustom)
    val selectedName = state.selectedPresetId?.let { id -> (state.presets + state.repeatPresets).firstOrNull { it.id == id }?.name }
    val options: List<Pair<UiText, String?>> = buildList {
        if (state.showsCustomRow) add(custom to null)
        val source = if (state.isRepeatEnabled) state.repeatPresets else state.presets
        source.forEach { add(UiText.Verbatim(it.name) to it.id) }
    }
    val footers = buildList {
        add(res(AppSettingsStrings.radioFooter))
        state.region?.let { add(UiText.Verbatim(holders.catalog.regionDisplayName(it))) }
        add(res(AppSettingsStrings.radioRegulationsFooter))
    }
    SettingsSection(res(AppSettingsStrings.radioHeader), footers) {
        DropdownRow(res(AppSettingsStrings.radioPreset), selectedName?.let { UiText.Verbatim(it) } ?: custom, options,
            enabled = state.pickerEnabled(inFlight), onSelect = holder::onPresetSelected)
        if (state.supportsRepeatToggle) {
            RowDivider()
            SwitchRow(res(AppSettingsStrings.radioRepeatMode), state.isRepeatEnabled, state.repeatToggleEnabled(inFlight) && connected,
                holder::onRepeatToggled, description = res(AppSettingsStrings.radioRepeatModeFooter))
        }
    }
    ConfirmDialog(
        state.showRepeatConfirmation, res(AppSettingsStrings.radioRepeatModeConfirmTitle), res(AppSettingsStrings.radioRepeatModeConfirmMessage),
        res(AppSettingsStrings.radioRepeatModeConfirmEnable), holder::confirmEnableRepeatMode, holder::dismissRepeatConfirmation, destructive = false,
    )
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}

@Composable
private fun PathHashModeSection(holder: PathHashModeStateHolder) {
    val state by holder.state.collectAsState()
    val options = listOf(
        res(AppSettingsStrings.pathHashModeOneByte) to 0.toUByte(),
        res(AppSettingsStrings.pathHashModeTwoBytes) to 1.toUByte(),
        res(AppSettingsStrings.pathHashModeThreeBytes) to 2.toUByte(),
    )
    SettingsSection(res(AppSettingsStrings.pathHashModeHeader), listOf(res(AppSettingsStrings.pathHashModeFooter))) {
        DropdownRow(res(AppSettingsStrings.pathHashModeLabel), options.first { it.second == state.displayedMode }.first, options,
            state.enabled, holder::onModeSelected)
    }
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}

@Composable
private fun AdvancedRadioSection(holder: AdvancedRadioStateHolder, gate: RadioWriteGate) {
    val state by holder.state.collectAsState()
    val inFlight by gate.inFlight.collectAsState()
    val context = LocalContext.current
    val enabled = radioActionEnabled(state.connectionState) && !state.isApplying
    val footers = buildList {
        add(res(AppSettingsStrings.advancedRadioFooter))
        if (!state.frequencyEditable) add(res(AppSettingsStrings.advancedRadioFrequencyRepeatModeFooter))
    }
    SettingsSection(res(AppSettingsStrings.advancedRadioHeader), footers) {
        if (!state.hasLoaded) {
            CircularProgressIndicator(Modifier.size(24.dp).sharedTouchTarget(), strokeWidth = 2.dp)
            return@SettingsSection
        }
        val invalid = state.frequencyText.isNotEmpty() && state.frequency == null
        ValidatedField(res(AppSettingsStrings.advancedRadioFrequency), state.frequencyText, holder::onFrequencyTextChanged,
            enabled && state.frequencyEditable, KeyboardType.Decimal,
            error = if (invalid) res(AppSettingsStrings.advancedRadioInvalidInput) else null,
            placeholder = res(AppSettingsStrings.advancedRadioFrequencyPlaceholder))
        val bandwidthOptions = RadioOptions.bandwidthsHz.map { hz -> UiText.Verbatim(RadioOptions.formatBandwidth(hz)) to hz }
        DropdownRow(res(AppSettingsStrings.advancedRadioBandwidth), state.bandwidth?.let { UiText.Verbatim(RadioOptions.formatBandwidth(it)) } ?: UiText.Verbatim(""),
            bandwidthOptions, enabled, holder::onBandwidthSelected, accessibilityHint = res(AppSettingsStrings.advancedRadioAccessibilityBandwidthHint))
        val sfOptions = RadioOptions.spreadingFactors.map { UiText.Verbatim(it.toString()) to it }
        DropdownRow(res(AppSettingsStrings.advancedRadioSpreadingFactor), UiText.Verbatim(state.spreadingFactor?.toString().orEmpty()),
            sfOptions, enabled, holder::onSpreadingFactorSelected, accessibilityHint = res(AppSettingsStrings.advancedRadioAccessibilitySpreadingFactorHint))
        val crOptions = RadioOptions.codingRates.map { UiText.Verbatim(it.toString()) to it }
        DropdownRow(res(AppSettingsStrings.advancedRadioCodingRate), UiText.Verbatim(state.codingRate?.toString().orEmpty()),
            crOptions, enabled, holder::onCodingRateSelected, accessibilityHint = res(AppSettingsStrings.advancedRadioAccessibilityCodingRateHint))
        val powerInvalid = state.txPowerText.isNotEmpty() && state.txPower == null
        ValidatedField(res(AppSettingsStrings.advancedRadioTxPower), state.txPowerText, holder::onTxPowerTextChanged, enabled,
            KeyboardType.Number, error = if (powerInvalid) res(AppSettingsStrings.advancedRadioInvalidInput) else null,
            placeholder = res(AppSettingsStrings.advancedRadioTxPowerPlaceholder))
        androidx.compose.material3.TextButton(holder::apply, Modifier.fillMaxWidth().sharedTouchTarget(), enabled = state.canApply(inFlight)) {
            AsyncActionLabel(state.isApplying, state.showSuccess, res(AppSettingsStrings.advancedRadioApply), res(AppSettingsStrings.advancedRadioApply)) {
                Text(uiString(res(AppSettingsStrings.advancedRadioApply)))
            }
        }
    }
    ErrorMessageDialog(state.errorMessage, holder::dismissError)
    RetryAlertDialog(holder.retry)
}
