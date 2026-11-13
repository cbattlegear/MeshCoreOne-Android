// PortedFrom: MC1/Views/Settings/Sections/DiagnosticsSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/FeedbackView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: log export is a system "save as" (SAF) instead of the iOS share sheet.
package com.meshcoreone.android.feature.settings.app.diagnostics.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import com.meshcoreone.android.core.ui.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.feature.settings.app.diagnostics.LogExportDependencies
import com.meshcoreone.android.feature.settings.app.diagnostics.LogExportStateHolder

/** Diagnostics group: export debug logs and clear them (with a confirmation). */
@Composable
fun DiagnosticsSection(dependencies: LogExportDependencies, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies) { LogExportStateHolder(dependencies, scope) }
    val state by holder.state.collectAsState()
    var confirmClear by remember { mutableStateOf(false) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(S.diagnosticsHeader), style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = { holder.exportLogs() }, enabled = !state.isExporting, modifier = Modifier.fillMaxWidth()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(S.diagnosticsExportLogs))
                if (state.isExporting) CircularProgressIndicator(Modifier.padding(start = 4.dp), strokeWidth = 2.dp)
            }
        }
        OutlinedButton(onClick = { confirmClear = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(S.diagnosticsClearLogs)) }
        Text(stringResource(S.diagnosticsFooter), style = MaterialTheme.typography.bodySmall)
        state.savedFileName?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(S.diagnosticsAlertClearTitle)) },
            text = { Text(stringResource(S.diagnosticsAlertClearMessage)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; holder.clearLogs() }) { Text(stringResource(S.diagnosticsAlertClearConfirm)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(AppLocalizableStrings.commonCancel)) } },
        )
    }
    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = holder::dismissError,
            text = { Text(message) },
            confirmButton = { TextButton(onClick = holder::dismissError) { Text(stringResource(S.settingsBackupImportErrorDismiss)) } },
        )
    }
}
