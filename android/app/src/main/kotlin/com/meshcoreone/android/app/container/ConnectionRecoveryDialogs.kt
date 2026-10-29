// PortedFrom: MC1/ContentView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.meshcoreone.android.app.state.AppState
import com.meshcoreone.android.app.state.ConnectionUiSnapshot
import com.meshcoreone.android.app.state.PairingFailureKind
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as S
import com.meshcoreone.android.core.ui.uiString
import kotlinx.coroutines.launch

@Composable
fun AppConnectionRecoveryDialogs(app: AppState) {
    val state by app.connectionUI.state.collectAsState()
    val scope = rememberCoroutineScope()
    ConnectionRecoveryDialogs(
        state,
        onDismiss = {
            app.connectionUI.clearPairingFailure()
            app.connectionUI.otherAppWarningDeviceId = null
        },
        onRetry = {
            app.connectionUI.showingConnectionFailedAlert = false
            scope.launch { app.retryFailedPairingConnect() }
        },
        onRemoveAndRetry = {
            app.removeFailedPairingAndRetry()
        },
    )
}

@Composable
internal fun ConnectionRecoveryDialogs(
    state: ConnectionUiSnapshot,
    onDismiss: () -> Unit,
    onRetry: () -> Unit,
    onRemoveAndRetry: () -> Unit,
) {
    if (state.otherAppWarningDeviceId != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(S.alertCouldNotConnectTitle)) },
            text = { Text(stringResource(S.alertCouldNotConnectOtherAppMessage)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(S.commonOk)) } },
        )
    } else if (state.showingConnectionFailedAlert) {
        val hasDevice = state.failedPairingDeviceId != null
        val canRetry = hasDevice && state.pairingFailureKind != PairingFailureKind.AUTHENTICATION &&
            state.pairingFailureKind != PairingFailureKind.PIN_REJECTED
        val removeLabel = stringResource(S.accessibilityAlertConnectionFailedRemoveAndRetry)
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(state.connectionFailedTitle?.let { uiString(it) } ?: stringResource(S.alertConnectionFailedTitle)) },
            text = { Text(state.connectionFailedMessage?.let { uiString(it) } ?: stringResource(S.alertConnectionFailedDefaultMessage)) },
            confirmButton = {
                Column {
                    if (canRetry) TextButton(onClick = onRetry) { Text(stringResource(S.commonTryAgain)) }
                    if (hasDevice) {
                        TextButton(
                            onClick = onRemoveAndRetry,
                            modifier = Modifier.semantics { contentDescription = removeLabel },
                        ) {
                            Text(stringResource(S.alertConnectionFailedRemoveAndRetry), color = MaterialTheme.colorScheme.error)
                        }
                    } else {
                        TextButton(onClick = onDismiss) { Text(stringResource(S.commonOk)) }
                    }
                }
            },
            dismissButton = {
                if (hasDevice) TextButton(onClick = onDismiss) { Text(stringResource(S.commonCancel)) }
            },
        )
    }
}
