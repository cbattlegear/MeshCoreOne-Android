// PortedFrom: MC1/Views/Settings/BackupRestoreView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/ImportPreviewSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/ImportSuccessContent.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Settings/ExportSuccessContent.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: sheets are full-width dialogs; the system file picker (SAF) is behind BackupDocumentPort. Compose is compile/lint-checked only.
package com.meshcoreone.android.feature.settings.app.backup.ui

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.feature.settings.app.backup.AppBackupStateHolder
import com.meshcoreone.android.feature.settings.app.backup.BackupFeatureDependencies
import com.meshcoreone.android.feature.settings.app.backup.CountRow
import com.meshcoreone.android.feature.settings.app.backup.ExportSuccessSummary
import com.meshcoreone.android.feature.settings.app.backup.ImportHeroIcon
import com.meshcoreone.android.feature.settings.app.backup.ImportResultPresentation
import com.meshcoreone.android.feature.settings.app.backup.ImportResultPresenter
import com.meshcoreone.android.feature.settings.app.backup.ImportState
import com.meshcoreone.android.feature.settings.app.backup.ParsedBackup
import com.meshcoreone.android.feature.settings.app.backup.manifestRows
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun BackupRestoreScreen(
    dependencies: BackupFeatureDependencies,
    modifier: Modifier = Modifier,
    presentingCommittedOutcome: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val holder = remember(dependencies) { AppBackupStateHolder(dependencies, scope) }
    val state by holder.state.collectAsState()
    val connected by dependencies.connection.isRadioConnected.collectAsState()
    var confirmExport by remember { mutableStateOf(false) }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text(stringResource(S.settingsBackupFileBackupHeader), style = MaterialTheme.typography.titleSmall) }
        item {
            ActionRow(
                title = if (state.isExporting) stringResource(S.settingsBackupExportProgress) else stringResource(S.settingsBackupExportTitle),
                subtitle = stringResource(S.settingsBackupExportSubtitle),
                busy = state.isExporting,
                enabled = !state.isBusy,
                onClick = { confirmExport = true },
            )
        }
        item {
            ActionRow(
                title = if (state.isParsing) stringResource(S.settingsBackupImportParsing) else stringResource(S.settingsBackupImportTitle),
                subtitle = stringResource(S.settingsBackupImportSubtitle),
                busy = state.isParsing,
                enabled = !state.isBusy && !connected && !presentingCommittedOutcome,
                onClick = { holder.selectFileToImport() },
            )
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (connected) Text(stringResource(S.settingsBackupImportDisabledWhenConnected), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(S.settingsBackupFileBackupFooter), style = MaterialTheme.typography.bodySmall)
                Text(stringResource(S.settingsBackupFileBackupRadioConfig), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (confirmExport) {
        AlertDialog(
            onDismissRequest = { confirmExport = false },
            title = { Text(stringResource(S.settingsBackupExportAlertTitle)) },
            text = { Text(stringResource(S.settingsBackupExportAlertMessage)) },
            confirmButton = {
                TextButton(onClick = { confirmExport = false; holder.performExport() }) { Text(stringResource(S.settingsBackupExportAlertExport)) }
            },
            dismissButton = { TextButton(onClick = { confirmExport = false }) { Text(stringResource(S.settingsBackupExportAlertCancel)) } },
        )
    }
    if (state.isImportSheetActive && !presentingCommittedOutcome) {
        val strings = rememberBackupStrings()
        ImportSheet(
            import = state.import,
            isCancelling = state.isCancellingImport,
            presentation = (state.import as? ImportState.Success)?.let { ImportResultPresenter.present(it.result, strings) },
            previewRows = (state.import as? ImportState.Preview)?.let { manifestRows(it.backup.manifest, strings) },
            onImport = { holder.performImport() },
            onCancelImport = { holder.cancelImport() },
            onDismiss = { holder.dismissImportSheet() },
        )
    }
    state.exportSummary?.let { summary ->
        val strings = rememberBackupStrings()
        FullDialog(onDismiss = { holder.dismissExportSuccess() }) {
            ExportSuccess(summary, manifestRows(summary.manifest, strings)) { holder.dismissExportSuccess() }
        }
    }
    state.errorMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { holder.dismissError() },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { holder.dismissError() }) { Text(stringResource(S.settingsBackupImportErrorDismiss)) } },
        )
    }
}

@Composable
private fun ActionRow(title: String, subtitle: String, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) CircularProgressIndicator(Modifier.padding(end = 4.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FullDialog(onDismiss: () -> Unit, dismissible: Boolean = true, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(dismissOnBackPress = dismissible, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) { Surface(Modifier.fillMaxSize()) { content() } }
}

@Composable
private fun ImportSheet(
    import: ImportState,
    isCancelling: Boolean,
    presentation: ImportResultPresentation?,
    previewRows: List<CountRow>?,
    onImport: () -> Unit,
    onCancelImport: () -> Unit,
    onDismiss: () -> Unit,
) {
    val importing = import is ImportState.Importing
    FullDialog(onDismiss = { if (!importing) onDismiss() }, dismissible = !importing) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(S.settingsBackupImportPreviewTitle), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = { if (importing) onCancelImport() else onDismiss() }, enabled = !isCancelling) {
                    Text(stringResource(S.settingsBackupImportPreviewCancel))
                }
            }
            when (import) {
                is ImportState.Preview -> PreviewContent(import.backup, previewRows.orEmpty(), onImport)
                ImportState.Importing -> Progress(
                    if (isCancelling) stringResource(S.settingsBackupImportCancelling) else stringResource(S.settingsBackupImportProgress),
                )
                is ImportState.Success -> presentation?.let { SuccessContent(it, onDismiss) }
                is ImportState.Failed -> Message(
                    stringResource(S.settingsBackupImportErrorTitle), import.message, stringResource(S.settingsBackupImportErrorDismiss), onDismiss,
                )
                ImportState.Cancelled -> Message(
                    stringResource(S.settingsBackupImportCancelledTitle), stringResource(S.settingsBackupImportCancelledMessage),
                    stringResource(S.settingsBackupImportSuccessDone), onDismiss,
                )
                ImportState.Idle, ImportState.Parsing -> Unit
            }
        }
    }
}

@Composable
private fun PreviewContent(backup: ParsedBackup, rows: List<CountRow>, onImport: () -> Unit) {
    val exported = remember(backup.exportDate) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(backup.exportDate)
    }
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item { Text(stringResource(S.settingsBackupImportPreviewDetails), style = MaterialTheme.typography.titleSmall) }
        item { LabeledRow(stringResource(S.settingsBackupImportPreviewExported), exported) }
        item { LabeledRow(stringResource(S.settingsBackupImportPreviewAppVersion), "${backup.appVersion} (${backup.appBuild})") }
        item { HorizontalDivider() }
        item { Text(stringResource(S.settingsBackupImportPreviewContents), style = MaterialTheme.typography.titleSmall) }
        items(rows, key = { it.kind }) { LabeledRow(it.label, it.count.toString()) }
        item { Text(stringResource(S.settingsBackupImportPreviewInfo), style = MaterialTheme.typography.bodySmall) }
        item { Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text(stringResource(S.settingsBackupImportPreviewButton)) } }
    }
}

@Composable
private fun SuccessContent(p: ImportResultPresentation, onDone: () -> Unit) {
    LazyColumn(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(p.heroTitle, style = MaterialTheme.typography.titleMedium)
                Text(p.heroSubtitle, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (p.addedRows.isNotEmpty()) {
            item { Text(stringResource(S.settingsBackupImportSuccessAddedSection), style = MaterialTheme.typography.titleSmall) }
            items(p.addedRows, key = { "a${it.kind}" }) { LabeledRow(it.label, it.count.toString()) }
        }
        if (p.skippedRows.isNotEmpty()) {
            item { Text(stringResource(S.settingsBackupImportSuccessAlreadyHereSection), style = MaterialTheme.typography.titleSmall) }
            item { Text(p.alreadyHereSummary.orEmpty()) }
            items(p.skippedRows, key = { "s${it.kind}" }) { LabeledRow(it.label, it.count.toString()) }
            item { Text(stringResource(S.settingsBackupImportSuccessAlreadyHereFooter), style = MaterialTheme.typography.bodySmall) }
            p.alreadyHereRefreshed?.let { item { Text(it, style = MaterialTheme.typography.bodySmall) } }
        }
        if (p.droppedRows.isNotEmpty()) {
            item { Text(stringResource(S.settingsBackupImportSuccessDroppedSection), style = MaterialTheme.typography.titleSmall) }
            item { Text(p.droppedSummary.orEmpty()) }
            items(p.droppedRows, key = { "d${it.kind}" }) { LabeledRow(it.label, it.count.toString()) }
            item { Text(p.droppedFooter.orEmpty(), style = MaterialTheme.typography.bodySmall) }
        }
        item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(S.settingsBackupImportSuccessDone)) } }
    }
}

@Composable
private fun ExportSuccess(summary: ExportSuccessSummary, rows: List<CountRow>, onDone: () -> Unit) {
    val context = LocalContext.current
    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(S.settingsBackupExportSuccessTitle), style = MaterialTheme.typography.titleMedium)
                Text(summary.filename, style = MaterialTheme.typography.bodyMedium)
                Text(Formatter.formatFileSize(context, summary.byteCount.toLong()), style = MaterialTheme.typography.bodySmall)
            }
        }
        item { Text(stringResource(S.settingsBackupExportSuccessIncludedSection), style = MaterialTheme.typography.titleSmall) }
        items(rows, key = { it.kind }) { LabeledRow(it.label, it.count.toString()) }
        item { Spacer(Modifier.padding(4.dp)) }
        item { Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(S.settingsBackupExportSuccessDone)) } }
    }
}

@Composable
private fun Progress(text: String) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Text(text, Modifier.padding(top = 16.dp).semantics { liveRegion = LiveRegionMode.Polite })
    }
}

@Composable
private fun Message(title: String, body: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Text(body, textAlign = TextAlign.Center)
        Button(onClick = onAction, modifier = Modifier.fillMaxWidth()) { Text(action) }
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
