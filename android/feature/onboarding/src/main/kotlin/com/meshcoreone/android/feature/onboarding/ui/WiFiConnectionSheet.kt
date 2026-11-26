// PortedFrom: MC1/Views/Onboarding/WiFiConnectionSheet.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.WiFiAddressState
import com.meshcoreone.android.core.ui.WiFiAddressFields
import com.meshcoreone.android.core.ui.WiFiField
import com.meshcoreone.android.core.ui.WiFiSheetToolbar
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.onboarding.WiFiSheetState

/** Hostname/IP + port entry. The sheet cannot be dismissed by the toolbar while connecting. */
@Composable
fun WiFiConnectionSheetContent(
    state: WiFiSheetState,
    onAddressChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onConnect: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    initialFocus: WiFiField? = WiFiField.IP_ADDRESS,
    onPermissionSettings: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(initialFocus) }
    val fields = WiFiAddressState(state.ipAddress, state.port, focused, state.isConnecting)
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = OnboardingMetrics.cardSpacing),
        verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.cardSpacing),
    ) {
        WiFiSheetToolbar(fields, onFocusChange = { focused = it }, onCancel = onCancel)
        Text(stringResource(O.wifiConnectionTitle), Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (onPermissionSettings != null) {
            Text(uiString(com.meshcoreone.android.core.ui.UiErrorMapper().connectionIssue(
                com.meshcoreone.android.core.contracts.domain.ConnectionIssue.PermissionDenied(
                    com.meshcoreone.android.core.contracts.domain.Capability.LOCAL_NETWORK,
                ),
            )))
            OutlinedButton(onPermissionSettings, Modifier.onboardingTouchTarget()) {
                Text(stringResource(O.permissionsOpenSettings))
            }
        }
        WiFiAddressFields(
            fields, onAddressChange, onPortChange, onFocusChange = { focused = it }, onPortSubmit = onConnect,
            sectionHeader = UiText.Resource(O.wifiConnectionConnectionDetailsHeader),
            sectionFooter = UiText.Resource(O.wifiConnectionConnectionDetailsFooter),
        )
        state.error?.let {
            Text(uiString(it), Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
        }
        OnboardingPrimaryButton(
            stringResource(O.wifiConnectionConnect), onConnect, Modifier.padding(bottom = OnboardingMetrics.largeSpacing),
            enabled = state.canConnect, loading = state.isConnecting, loadingText = stringResource(O.wifiConnectionConnecting),
        )
    }
}
