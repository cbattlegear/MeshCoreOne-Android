// PortedFrom: MC1/Views/WhatsNew/WhatsNewSheet.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: Continue, Support Development and system-back share one dismiss path that persists the baseline (via onDismiss).
package com.meshcoreone.android.feature.settings.app.whatsnew.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings
import com.meshcoreone.android.core.l10n.generated.AppWhatsNewStrings
import com.meshcoreone.android.feature.settings.app.whatsnew.WhatsNewItem
import com.meshcoreone.android.feature.settings.app.whatsnew.WhatsNewRelease

/** [onSupport] navigates to the support screen; both buttons and back call [onDismiss] to persist the baseline. */
@Composable
fun WhatsNewSheet(release: WhatsNewRelease, onSupport: () -> Unit, onDismiss: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(
                        stringResource(AppWhatsNewStrings.whatsNewTitle), style = MaterialTheme.typography.headlineLarge,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 24.dp).semantics { heading() },
                    )
                    release.items.forEach { WhatsNewRow(it) }
                    TextButton(onClick = { uriHandler.openUri(release.releaseNotesUrl) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text(stringResource(AppWhatsNewStrings.whatsNewFullReleaseNotes))
                    }
                }
                OutlinedButton(onClick = { onSupport(); onDismiss() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(AppSettingsStrings.supportTitle))
                }
                Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(AppWhatsNewStrings.whatsNewContinueButton)) }
            }
        }
    }
}

@Composable
private fun WhatsNewRow(item: WhatsNewItem) {
    Row(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Icon(item.symbol.vector, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
        Column {
            Text(stringResource(item.titleResource), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(item.descriptionResource), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
