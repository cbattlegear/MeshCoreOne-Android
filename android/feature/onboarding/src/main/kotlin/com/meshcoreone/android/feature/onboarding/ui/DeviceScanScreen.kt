// PortedFrom: MC1/Views/Onboarding/DeviceScanView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.onboarding.DeviceScanState
import com.meshcoreone.android.feature.onboarding.OnboardingConnectionSnapshot
import com.meshcoreone.android.feature.onboarding.PairPrimaryAction
import com.meshcoreone.android.feature.onboarding.PairSheet
import com.meshcoreone.android.feature.onboarding.PairingBlocker

data class DeviceScanActions(
    val onTitleTap: () -> Unit,
    val onPrimary: (PairPrimaryAction) -> Unit,
    val onContinue: () -> Unit,
    val onOpenSheet: (PairSheet) -> Unit,
    val onDismissSheet: () -> Unit,
    val onDismissFailure: () -> Unit,
    val onDismissDemoAlert: () -> Unit,
    val onOpenAppSettings: () -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceScanScreen(
    state: DeviceScanState,
    connection: OnboardingConnectionSnapshot,
    primaryAction: PairPrimaryAction,
    actions: DeviceScanActions,
    modifier: Modifier = Modifier,
    sheetContent: @Composable (PairSheet) -> Unit,
) {
    val busy = connection.isBusy || state.localBusy
    val hasConnected = connection.isReady
    OnboardingColumn(modifier.verticalScroll(rememberScrollState())) {
        Column(Modifier.padding(top = OnboardingMetrics.headerTopPadding), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.mediumSpacing)) {
            PulsingAntenna()
            Text(
                stringResource(O.deviceScanTitle),
                Modifier.semantics { heading() }.clickable(onClickLabel = null, role = Role.Button) { actions.onTitleTap() },
                style = MaterialTheme.typography.headlineLarge, textAlign = TextAlign.Center,
            )
            if (!hasConnected) {
                Text(stringResource(O.deviceScanSubtitle), style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
        }
        Spacer(Modifier.weight(1f))
        if (hasConnected && !state.didInitiatePairing) {
            Text(stringResource(O.deviceScanAlreadyPaired), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        }
        BluetoothBlockerNotice(state.blocker, actions.onOpenAppSettings)
        Spacer(Modifier.weight(1f))
        Column(Modifier.fillMaxWidth().padding(bottom = OnboardingMetrics.cardSpacing), verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.mediumSpacing),
            horizontalAlignment = Alignment.CenterHorizontally) {
            if (hasConnected) {
                OnboardingPrimaryButton(stringResource(O.deviceScanContinue), actions.onContinue)
            } else {
                OnboardingPrimaryButton(
                    primaryLabel(primaryAction), { actions.onPrimary(primaryAction) },
                    enabled = !busy, loading = busy, loadingText = stringResource(O.deviceScanConnecting),
                )
                TextButton({ actions.onOpenSheet(PairSheet.WIFI) }, Modifier.onboardingTouchTarget()) { Text(stringResource(O.deviceScanConnectViaWifi)) }
                TextButton({ actions.onOpenSheet(PairSheet.TROUBLESHOOTING) }, Modifier.onboardingTouchTarget()) { Text(stringResource(O.deviceScanDeviceNotAppearing)) }
                TextButton({ actions.onOpenSheet(PairSheet.NO_DEVICE) }, Modifier.onboardingTouchTarget()) {
                    Text(stringResource(O.deviceScanNoDeviceYet), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
    if (state.sheet != PairSheet.NONE) {
        ModalBottomSheet(actions.onDismissSheet, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            sheetContent(state.sheet)
        }
    }
    state.failure?.let { message ->
        AlertDialog(
            onDismissRequest = actions.onDismissFailure,
            confirmButton = { TextButton(actions.onDismissFailure) { Text(stringResource(L.commonOk)) } },
            text = { Text(uiString(message)) },
        )
    }
    if (state.showDemoAlert) {
        AlertDialog(
            onDismissRequest = actions.onDismissDemoAlert,
            title = { Text(stringResource(O.deviceScanDemoModeAlertTitle)) },
            text = { Text(stringResource(O.deviceScanDemoModeAlertMessage)) },
            confirmButton = { TextButton(actions.onDismissDemoAlert) { Text(stringResource(L.commonOk)) } },
        )
    }
}

@Composable
private fun primaryLabel(action: PairPrimaryAction): String = stringResource(
    when (action) {
        PairPrimaryAction.CONNECT_DEMO -> O.deviceScanContinueDemo
        PairPrimaryAction.RETRY_CONNECTION -> O.deviceScanRetryConnection
        PairPrimaryAction.ADD_DEVICE -> O.deviceScanAddDevice
    },
)

/** Inline remedy for the two blockers the user must fix outside the app (denied permission, adapter off). */
@Composable
private fun BluetoothBlockerNotice(blocker: PairingBlocker, onOpenSettings: () -> Unit) {
    val (title, message) = when (blocker) {
        PairingBlocker.BLUETOOTH_PERMISSION_DENIED ->
            O.deviceScannerBluetoothUnauthorizedTitle to O.deviceScannerBluetoothUnauthorizedMessage
        PairingBlocker.BLUETOOTH_OFF -> O.deviceScannerBluetoothOffTitle to O.deviceScannerBluetoothOffMessage
        else -> return
    }
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.titleStackSpacing)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(stringResource(message), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onOpenSettings, Modifier.onboardingTouchTarget()) { Text(stringResource(O.permissionsOpenSettings)) }
    }
}
