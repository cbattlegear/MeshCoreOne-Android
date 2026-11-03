// AndroidOnly: WP-305 stateful host wiring the stateless step screens to the coordinators.
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.ui.SharedUiScaffold
import com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies
import com.meshcoreone.android.feature.onboarding.OnboardingFlowCoordinator
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionSnapshot
import com.meshcoreone.android.feature.onboarding.OnboardingStep
import com.meshcoreone.android.feature.onboarding.PairSheet
import com.meshcoreone.android.feature.onboarding.PermissionStatus
import com.meshcoreone.android.core.ui.WiFiField
import kotlinx.coroutines.flow.emptyFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingFlow(dependencies: OnboardingFeatureDependencies, onCompleted: () -> Unit, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val flow = remember(dependencies) { OnboardingFlowCoordinator(scope, dependencies).also { it.restoreStartingPath() } }
    val requester = dependencies.rememberPermissionRequester { flow.onPermissionResult() }
    val path by flow.onboarding.onboardingPathFlow.collectAsState()
    val completed by flow.onboarding.hasCompletedOnboardingFlow.collectAsState()
    val permissionSnapshot by dependencies.permissions.snapshot.collectAsState()
    val completionError by flow.hasCompletionError.collectAsState()
    val completingWithoutDevice by flow.isCompletingWithoutDevice.collectAsState()
    LaunchedEffect(completed) { if (completed) onCompleted() }
    dependencies.InterceptBack(enabled = path.isNotEmpty()) { flow.back() }

    SharedUiScaffold(modifier.fillMaxSize()) {
        when (path.lastOrNull() ?: OnboardingStep.WELCOME) {
            OnboardingStep.WELCOME -> WelcomeScreen(flow::getStarted)
            OnboardingStep.PERMISSIONS -> PermissionsScreen(permissionSnapshot, requester, dependencies::openAppSettings, flow::continueFromPermissions)
            OnboardingStep.PAIR -> PairStep(flow, dependencies, permissionSnapshot, requester, completingWithoutDevice)
            OnboardingStep.REGION -> RegionStep(flow, dependencies, permissionSnapshot)
            OnboardingStep.PRESET -> PresetStep(flow, dependencies)
        }
    }
    if (completionError) {
        AlertDialog(
            onDismissRequest = flow::dismissCompletionError,
            title = { Text(stringResource(S.alertRetryUnableToSave)) },
            confirmButton = {
                TextButton(flow::retryCompletion) { Text(stringResource(L.commonTryAgain)) }
            },
            dismissButton = {
                TextButton(flow::dismissCompletionError) { Text(stringResource(L.commonCancel)) }
            },
        )
    }
    val fallback = dependencies.pairing.scanFallback
    val scanRequested by (fallback?.isRequested ?: remember { kotlinx.coroutines.flow.MutableStateFlow(false) }).collectAsState()
    if (fallback != null && scanRequested) {
        ModalBottomSheet(fallback::cancel, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            val found by remember(fallback) { fallback.discoveries() }.collectAsState(emptyList())
            DeviceScannerSheetContent(found, permissionSnapshot, fallback::select, fallback::cancel)
        }
    }
}

@Composable
private fun PairStep(
    flow: OnboardingFlowCoordinator, deps: OnboardingFeatureDependencies,
    permissions: OnboardingPermissionSnapshot, requester: (OnboardingPermissionKind) -> Unit,
    completingWithoutDevice: Boolean,
) {
    val state by flow.deviceScan.state.collectAsState()
    val connection by deps.pairing.connection.collectAsState()
    val demoEnabled by deps.demo.isEnabled.collectAsState()
    val pairedCount by deps.pairing.pairedAccessoryCount.collectAsState()
    val clearing by flow.isClearingPairings.collectAsState()
    val wifiState by flow.wifi.state.collectAsState()
    val action = flow.deviceScan.primaryAction(demoEnabled, state.otherAppDeviceId)
    LaunchedEffect(state.sheet == PairSheet.WIFI) {
        if (state.sheet == PairSheet.WIFI) {
            flow.wifi.reset()
            if (permissions.localNetwork == PermissionStatus.NOT_DETERMINED) requester(OnboardingPermissionKind.LOCAL_NETWORK)
        }
    }
    val scan = flow.deviceScan
    DeviceScanScreen(
        state, connection, action,
        DeviceScanActions(
            onTitleTap = scan::onTitleTap,
            onPrimary = {
                when (it) {
                    com.meshcoreone.android.feature.onboarding.PairPrimaryAction.CONNECT_DEMO -> scan.connectDemo()
                    com.meshcoreone.android.feature.onboarding.PairPrimaryAction.RETRY_CONNECTION -> state.otherAppDeviceId?.let(scan::retryConnection)
                    com.meshcoreone.android.feature.onboarding.PairPrimaryAction.ADD_DEVICE -> flow.addDevice(requester)
                }
            },
            onContinue = scan::continueConnected, onOpenSheet = scan::showSheet, onDismissSheet = flow::dismissPairingSheet,
            onDismissFailure = scan::dismissFailure, onDismissDemoAlert = scan::dismissDemoAlert,
            onOpenAppSettings = deps::openAppSettings, canDismissSheet = flow::canDismissPairingSheet,
        ),
    ) { sheet ->
        when (sheet) {
            PairSheet.NO_DEVICE -> NoDeviceSheetContent(
                flow::confirmNoDevice, flow::dismissPairingSheet, isCompleting = completingWithoutDevice,
            )
            PairSheet.TROUBLESHOOTING -> TroubleshootingSheetContent(
                pairedCount, clearing, flow::clearStalePairings, deps::openAppSettings, flow::dismissPairingSheet,
            )
            PairSheet.WIFI -> WiFiConnectionSheetContent(
                wifiState, flow.wifi::setAddress, flow.wifi::setPort, flow.wifi::connect, flow::dismissPairingSheet,
                initialFocus = WiFiField.IP_ADDRESS,
            )
            PairSheet.NONE -> Unit
        }
    }
}

@Composable
private fun RegionStep(flow: OnboardingFlowCoordinator, deps: OnboardingFeatureDependencies, permissions: OnboardingPermissionSnapshot) {
    val state by flow.region.state.collectAsState()
    val granted = permissions.location == PermissionStatus.GRANTED
    LaunchedEffect(granted) { flow.region.resolve() }
    RegionStepScreen(
        state, granted, deps.region.catalog,
        RegionStepActions(
            flow.region::chooseAnother, flow.region::useMyLocation, flow.region::commitDetected, flow.region::commitManual,
            flow.region::setManualSelection, flow.region::dismissError,
        ),
    )
}

@Composable
private fun PresetStep(flow: OnboardingFlowCoordinator, deps: OnboardingFeatureDependencies) {
    val state by flow.preset.state.collectAsState()
    val canApply by deps.presets.canApply.collectAsState()
    val selection by deps.region.selection.collectAsState()
    PresetStepScreen(
        state, selection?.let(deps.region.catalog::displayName), flow.preset.visiblePresets(), canApply,
        PresetStepActions(flow.preset::select, { flow.preset.apply() }, flow.preset::dismissError, flow.preset::retry, flow.preset::dismissRetryAlert),
    )
}
