// PortedFrom: MC1/Views/Onboarding/PermissionsView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Onboarding/PermissionCard.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.onboarding.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.meshcoreone.android.core.ui.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshSymbol
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionSnapshot
import com.meshcoreone.android.feature.onboarding.PermissionCardStatus
import com.meshcoreone.android.feature.onboarding.PermissionsCoordinator
import com.meshcoreone.android.feature.onboarding.permissionCardStatus

@Composable
fun PermissionsScreen(
    snapshot: OnboardingPermissionSnapshot,
    onRequest: (OnboardingPermissionKind) -> Unit,
    onOpenSettings: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OnboardingColumn(modifier) {
        OnboardingHeader(stringResource(O.permissionsTitle), stringResource(O.permissionsSubtitle))
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = OnboardingMetrics.largeSpacing),
            verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.cardSpacing),
        ) {
            PermissionsCoordinator.CARDS.forEach { kind ->
                val status = permissionCardStatus(snapshot.status(kind))
                PermissionCard(
                    symbol = if (kind == OnboardingPermissionKind.LOCATION) MeshSymbol.GLOBE else MeshSymbol.SIGNAL,
                    title = stringResource(if (kind == OnboardingPermissionKind.LOCATION) O.permissionsLocationTitle else O.permissionsNotificationsTitle),
                    description = stringResource(if (kind == OnboardingPermissionKind.LOCATION) O.permissionsLocationDescription else O.permissionsNotificationsDescription),
                    status = status, isOptional = true,
                    onRequest = { onRequest(kind) }, onOpenSettings = onOpenSettings,
                )
            }
        }
        OnboardingPrimaryButton(stringResource(O.permissionsContinue), onContinue, Modifier.padding(bottom = OnboardingMetrics.cardSpacing))
    }
}

@Composable
fun PermissionCard(
    symbol: MeshSymbol,
    title: String,
    description: String,
    status: PermissionCardStatus,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    isOptional: Boolean = false,
) {
    val tint = when (status) {
        PermissionCardStatus.GRANTED -> MaterialTheme.colorScheme.tertiary
        PermissionCardStatus.DENIED -> MaterialTheme.colorScheme.error
        PermissionCardStatus.REQUESTABLE -> MaterialTheme.colorScheme.primary
    }
    Card(modifier.fillMaxWidth()) {
        Row(Modifier.padding(OnboardingMetrics.cardSpacing), horizontalArrangement = Arrangement.spacedBy(OnboardingMetrics.cardSpacing), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(48.dp).background(tint.copy(alpha = 0.1f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(symbol.vector, null, tint = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(OnboardingMetrics.compactSpacing)) {
                Row(horizontalArrangement = Arrangement.spacedBy(OnboardingMetrics.titleStackSpacing), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.titleMedium)
                    if (isOptional) Text(stringResource(O.permissionsOptional), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when (status) {
                PermissionCardStatus.GRANTED -> Icon(MeshSymbol.READY.vector, null, tint = tint)
                PermissionCardStatus.DENIED -> OutlinedButton(onOpenSettings, Modifier.onboardingTouchTarget()) { Text(stringResource(O.permissionsOpenSettings)) }
                PermissionCardStatus.REQUESTABLE -> Button(onRequest, Modifier.onboardingTouchTarget()) { Text(stringResource(O.permissionsRequest)) }
            }
        }
    }
}
