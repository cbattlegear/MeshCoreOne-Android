// PortedFrom: MC1/Views/Onboarding/RegionStepView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Onboarding/RegionPickerView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Onboarding/RegionPickerRows.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import com.meshcoreone.android.core.ui.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.onboarding.AdministrativeAreaKind
import com.meshcoreone.android.feature.onboarding.OnboardingRegionCatalog
import com.meshcoreone.android.feature.onboarding.RegionStepMode
import com.meshcoreone.android.feature.onboarding.RegionStepState

data class RegionStepActions(
    val onChooseAnother: () -> Unit,
    val onUseMyLocation: () -> Unit,
    val onCommitDetected: () -> Unit,
    val onCommitManual: () -> Unit,
    val onManualSelection: (RegionSelection?) -> Unit,
    val onDismissError: () -> Unit,
)

@Composable
fun RegionStepScreen(
    state: RegionStepState,
    locationGranted: Boolean,
    catalog: OnboardingRegionCatalog,
    actions: RegionStepActions,
    modifier: Modifier = Modifier,
) {
    OnboardingColumn(modifier) {
        when (state.mode(locationGranted)) {
            RegionStepMode.RESOLVING -> Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.cardSpacing, Alignment.CenterVertically)) {
                CircularProgressIndicator()
                Text(stringResource(O.regionResolving), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RegionStepMode.DETECTED -> DetectedRegion(state.resolved, catalog, actions)
            RegionStepMode.MANUAL -> ManualRegion(state, locationGranted, catalog, actions)
        }
    }
    state.error?.let {
        AlertDialog(
            onDismissRequest = actions.onDismissError,
            title = { Text(stringResource(S.alertErrorTitle)) },
            text = { Text(uiString(it)) },
            confirmButton = { TextButton(actions.onDismissError) { Text(stringResource(L.commonOk)) } },
        )
    }
}

@Composable
private fun ColumnHeader() = OnboardingHeader(stringResource(O.regionTitle), stringResource(O.regionSubtitle))

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.DetectedRegion(region: RegionSelection?, catalog: OnboardingRegionCatalog, actions: RegionStepActions) {
    ColumnHeader()
    Spacer(Modifier.weight(1f))
    if (region != null) {
        Card(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}) {
            Column(Modifier.fillMaxWidth().padding(OnboardingMetrics.contentPadding), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.titleStackSpacing)) {
                Text(stringResource(O.regionDetectedTag), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(catalog.displayName(region), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Text(stringResource(O.regionDetectedSource), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    OutlinedButton(actions.onChooseAnother, Modifier.padding(top = OnboardingMetrics.cardSpacing).onboardingTouchTarget()) { Text(stringResource(O.regionChooseAnother)) }
    Spacer(Modifier.weight(1f))
    OnboardingPrimaryButton(stringResource(O.regionUseThisRegion), actions.onCommitDetected, Modifier.padding(bottom = OnboardingMetrics.cardSpacing))
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ManualRegion(
    state: RegionStepState, locationGranted: Boolean, catalog: OnboardingRegionCatalog, actions: RegionStepActions,
) {
    ColumnHeader()
    RegionPicker(state.manualSelection, catalog, actions.onManualSelection, Modifier.weight(1f).padding(vertical = OnboardingMetrics.cardSpacing))
    if (locationGranted) TextButton(actions.onUseMyLocation, Modifier.onboardingTouchTarget()) { Text(stringResource(O.regionUseMyLocation)) }
    OnboardingPrimaryButton(stringResource(O.regionContinue), actions.onCommitManual, Modifier.padding(bottom = OnboardingMetrics.cardSpacing), enabled = state.manualSelection != null)
}

private enum class PickerSheet { COUNTRY, SUBDIVISION }

/** Country + state/province rows; the second row is hidden for countries without a subdivision catalog. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegionPicker(selection: RegionSelection?, catalog: OnboardingRegionCatalog, onSelect: (RegionSelection?) -> Unit, modifier: Modifier = Modifier) {
    var sheet by remember { mutableStateOf<PickerSheet?>(null) }
    val country = selection?.countryCode
    val notSet = stringResource(S.radioPresetLocationNotSet)
    Column(modifier.verticalScroll(rememberScrollState())) {
        PickerRow(stringResource(O.regionCountry), country?.let(catalog::countryDisplayName) ?: notSet) { sheet = PickerSheet.COUNTRY }
        if (catalog.showsSubdivisionPicker(country)) {
            HorizontalDivider()
            PickerRow(areaTitle(catalog.administrativeAreaKind(country)), selection?.administrativeAreaCode?.let { catalog.subdivisionDisplayName(it) ?: it } ?: notSet) {
                sheet = PickerSheet.SUBDIVISION
            }
        }
    }
    sheet?.let { which ->
        ModalBottomSheet({ sheet = null }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            when (which) {
                PickerSheet.COUNTRY -> SearchableList(
                    stringResource(O.regionCountry), catalog.countries().map { it.code to it.displayName }, country,
                    onCancel = { sheet = null },
                ) { code ->
                    RegionSelection.afterChoosingCountry(code, selection)?.let(onSelect)
                    sheet = null
                }
                PickerSheet.SUBDIVISION -> SearchableList(
                    areaTitle(catalog.administrativeAreaKind(country)), catalog.subdivisions(country).map { it.code to it.displayName },
                    selection?.administrativeAreaCode, onCancel = { sheet = null },
                ) { code ->
                    RegionSelection.afterChoosingSubdivision(code, selection)?.let(onSelect)
                    sheet = null
                }
            }
        }
    }
}

@Composable
private fun areaTitle(kind: AdministrativeAreaKind): String =
    stringResource(if (kind == AdministrativeAreaKind.PROVINCE) O.regionProvince else O.regionState)

@Composable
private fun PickerRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = OnboardingMetrics.minHitTarget).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) {}.padding(vertical = OnboardingMetrics.mediumSpacing),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text(value, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchableList(title: String, rows: List<Pair<String, String>>, selectedId: String?, onCancel: () -> Unit, onPick: (String) -> Unit) {
    var search by remember { mutableStateOf("") }
    val filtered = remember(rows, search) { filterRows(rows, search) }
    Column(Modifier.fillMaxWidth().padding(horizontal = OnboardingMetrics.cardSpacing)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            TextButton(onCancel, Modifier.onboardingTouchTarget()) { Text(stringResource(L.commonCancel)) }
        }
        OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(AppChatsStrings.chatsSearchPlaceholder)) })
        LazyColumn(Modifier.fillMaxWidth()) {
            items(filtered, key = { it.first }) { (id, name) ->
                Row(
                    Modifier.fillMaxWidth().heightIn(min = OnboardingMetrics.minHitTarget).clickable(role = Role.Button) { onPick(id) },
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = MaterialTheme.typography.bodyLarge)
                    if (id == selectedId) Icon(MeshSymbol.CHECK.vector, null, tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/** Case-insensitive substring search over the display names (the original used localizedStandardContains). */
internal fun filterRows(rows: List<Pair<String, String>>, query: String): List<Pair<String, String>> =
    if (query.isBlank()) rows else rows.filter { it.second.contains(query.trim(), ignoreCase = true) }
