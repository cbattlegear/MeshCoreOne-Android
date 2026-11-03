// PortedFrom: MC1/Views/Onboarding/NoDeviceSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Onboarding/TroubleshootingSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Onboarding/DeviceScannerSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.ui.RSSITuning
import com.meshcoreone.android.core.ui.SignalBars
import com.meshcoreone.android.feature.onboarding.OnboardingScanDiscovery
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionSnapshot
import java.util.UUID

/** Confirms leaving onboarding for the empty main app; never unlocks demo mode or visits region/preset. */
@Composable
fun NoDeviceSheetContent(
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    isCompleting: Boolean = false,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = OnboardingMetrics.cardSpacing).padding(bottom = OnboardingMetrics.largeSpacing),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.largeSpacing),
    ) {
        Text(stringResource(O.noDeviceSheetTitle), Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(O.noDeviceSheetBody), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        OnboardingPrimaryButton(
            stringResource(O.noDeviceSheetConfirm), onConfirm,
            enabled = !isCompleting, loading = isCompleting, loadingText = stringResource(O.noDeviceSheetConfirm),
        )
        OutlinedButton(
            onCancel, Modifier.fillMaxWidth().heightIn(min = OnboardingMetrics.minHitTarget), enabled = !isCompleting,
        ) { Text(stringResource(O.noDeviceSheetCancel)) }
    }
}

@Composable
fun TroubleshootingSheetContent(
    pairedAccessoryCount: Int,
    isClearing: Boolean,
    onClearStalePairings: () -> Unit,
    onOpenSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = OnboardingMetrics.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.cardSpacing)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(O.troubleshootingTitle), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            TextButton(onDone, Modifier.onboardingTouchTarget()) { Text(stringResource(L.commonDone)) }
        }
        SectionHeader(O.troubleshootingBasicChecksHeader)
        listOf(
            MeshSymbol.LOCK to O.troubleshootingBasicChecksPowerOn,
            MeshSymbol.SIGNAL to O.troubleshootingBasicChecksMoveCloser,
            MeshSymbol.NODES to O.troubleshootingBasicChecksNotConnectedElsewhere,
            MeshSymbol.SYNC to O.troubleshootingBasicChecksRestart,
        ).forEach { (symbol, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(OnboardingMetrics.mediumSpacing), verticalAlignment = Alignment.CenterVertically) {
                Icon(symbol.vector, null, tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
            }
        }
        HorizontalDivider()
        SectionHeader(O.troubleshootingFactoryResetHeader)
        Text(stringResource(O.troubleshootingFactoryResetExplanation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(O.troubleshootingFactoryResetConfirmationNote), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClearStalePairings, Modifier.onboardingTouchTarget(), enabled = !isClearing && pairedAccessoryCount > 0) {
            if (isClearing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(stringResource(O.troubleshootingFactoryResetClearPairing))
        }
        Text(
            if (pairedAccessoryCount == 0) stringResource(O.troubleshootingFactoryResetNoPairings)
            else O.troubleshootingFactoryResetPairingsFound(androidx.compose.ui.platform.LocalContext.current.resources, pairedAccessoryCount),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider()
        SectionHeader(O.troubleshootingSystemSettingsHeader)
        Text(stringResource(O.troubleshootingSystemSettingsManageAccessories), style = MaterialTheme.typography.bodyMedium)
        Text(stringResource(O.troubleshootingSystemSettingsPath), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onOpenSettings, Modifier.onboardingTouchTarget()) { Text(stringResource(O.troubleshootingSystemSettingsOpenSettings)) }
        HorizontalDivider()
        SectionHeader(O.troubleshootingStillNotAppearingHeader)
        Text(stringResource(O.troubleshootingStillNotAppearingBody), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = OnboardingMetrics.largeSpacing))
    }
}

@Composable
private fun SectionHeader(resource: Int) {
    Text(stringResource(resource), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

/** In-app BLE picker, shown only when the system companion chooser is unavailable. */
@Composable
fun DeviceScannerSheetContent(
    discoveries: List<OnboardingScanDiscovery>,
    permissions: OnboardingPermissionSnapshot,
    onSelect: (UUID) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val unknown = stringResource(O.deviceScannerUnknownDevice)
    val sorted = discoveries.sortedWith(compareBy<OnboardingScanDiscovery>({ (it.name ?: "").lowercase() }, { it.id.toString() }))
    Column(modifier.fillMaxWidth().padding(horizontal = OnboardingMetrics.cardSpacing), verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.mediumSpacing)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(O.deviceScannerTitle), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
            TextButton(onCancel, Modifier.onboardingTouchTarget()) { Text(stringResource(L.commonCancel)) }
        }
        when {
            permissions.bluetooth != PermissionStatus.GRANTED -> Remedy(O.deviceScannerBluetoothUnauthorizedTitle, O.deviceScannerBluetoothUnauthorizedMessage)
            !permissions.bluetoothAdapterEnabled -> Remedy(O.deviceScannerBluetoothOffTitle, O.deviceScannerBluetoothOffMessage)
            sorted.isEmpty() -> Column(Modifier.fillMaxWidth().padding(OnboardingMetrics.largeSpacing), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.mediumSpacing)) {
                Text(stringResource(O.deviceScannerScanning), style = MaterialTheme.typography.titleMedium)
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            else -> LazyColumn(Modifier.fillMaxWidth()) {
                items(sorted, key = { it.id }) { device ->
                    ScannerRow(device.name ?: unknown, device.tier) { onSelect(device.id) }
                }
            }
        }
    }
}

@Composable
private fun Remedy(title: Int, message: Int) {
    Column(Modifier.fillMaxWidth().padding(OnboardingMetrics.largeSpacing).semantics(mergeDescendants = true) {},
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.titleStackSpacing)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScannerRow(name: String, tier: RSSITuning.SignalTier, onClick: () -> Unit) {
    val signal = stringResource(
        when (tier) {
            RSSITuning.SignalTier.WEAK -> L.accessibilitySignalStrengthWeak
            RSSITuning.SignalTier.MEDIUM -> L.accessibilitySignalStrengthMedium
            RSSITuning.SignalTier.STRONG -> L.accessibilitySignalStrengthStrong
        },
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = OnboardingMetrics.minHitTarget)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name, $signal" }
            .padding(vertical = OnboardingMetrics.compactSpacing),
        horizontalArrangement = Arrangement.spacedBy(OnboardingMetrics.mediumSpacing), verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MeshSymbol.RADIO.vector, null, tint = MaterialTheme.colorScheme.primary)
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        SignalBars(tier)
        Text(signal, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
