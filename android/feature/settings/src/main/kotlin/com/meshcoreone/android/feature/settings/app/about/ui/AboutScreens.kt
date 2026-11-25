// PortedFrom: MC1/Views/Settings/Sections/AboutSection.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Settings/FeedbackView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Support/SupportDevelopmentView.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation: IAP-free support screen; the licenses title has no localization-pipeline key, so the app passes it in.
package com.meshcoreone.android.feature.settings.app.about.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.feature.settings.app.about.AboutDestination
import com.meshcoreone.android.feature.settings.app.about.AboutLinks
import com.meshcoreone.android.feature.settings.app.about.AboutRow
import com.meshcoreone.android.feature.settings.app.about.LicenseCatalog
import com.meshcoreone.android.feature.settings.app.about.LicenseEntry
import com.meshcoreone.android.feature.settings.app.about.LicenseText
import com.meshcoreone.android.feature.settings.app.about.LicenseTextSource
import com.meshcoreone.android.feature.settings.app.about.SupportScreenModel
import com.meshcoreone.android.feature.settings.app.about.resolve

private const val ALL_UNLOCKED_EMOJI = "🎉"

@Composable
private fun LinkRow(row: AboutRow, onNavigate: (AboutDestination) -> Unit, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val click = when (row) {
        is AboutRow.Destination -> ({ onNavigate(row.destination) })
        is AboutRow.ExternalLink -> ({ uriHandler.openUri(row.url) })
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = click),
        verticalAlignment = Alignment.CenterVertically,
    ) { Text(stringResource(row.labelResource)) }
}

/** About group of the Settings list; [licensesTitle] labels the row that opens the licenses screen. */
@Composable
fun AboutSection(licensesTitle: String, onNavigate: (AboutDestination) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Text(stringResource(S.aboutHeader), style = MaterialTheme.typography.titleSmall)
        AboutLinks.rows.forEach { LinkRow(it, onNavigate) }
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onNavigate(AboutDestination.LICENSES) },
            verticalAlignment = Alignment.CenterVertically,
        ) { Text(licensesTitle) }
    }
}

@Composable
fun FeedbackScreen(diagnostics: @Composable () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(S.feedbackHeaderBody), style = MaterialTheme.typography.bodyMedium)
        AboutLinks.feedbackLinks.forEach { LinkRow(it, onNavigate = {}) }
        HorizontalDivider()
        diagnostics()
    }
}

/** Support Development without purchases: all-unlocked themes card, the sponsors link and the developer email. */
@Composable
fun SupportScreen(model: SupportScreenModel, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(S.supportThemesTitle), style = MaterialTheme.typography.titleSmall)
        if (model.allThemesUnlocked) {
            Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(ALL_UNLOCKED_EMOJI, style = MaterialTheme.typography.displayMedium)
                Text(stringResource(S.supportThemesAllUnlocked), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
            }
            Text(stringResource(S.supportThemesPurchasedFooter), style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        model.links.forEach { LinkRow(it, onNavigate = {}) }
    }
}

@Composable
fun LicensesScreen(source: LicenseTextSource, modifier: Modifier = Modifier, entries: List<LicenseEntry> = LicenseCatalog.entries) {
    var expanded by remember { mutableStateOf<LicenseText?>(null) }
    var requested by remember { mutableStateOf<LicenseEntry?>(null) }
    LaunchedEffect(requested) { requested?.let { expanded = source.resolve(it) } }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(entries, key = { it.id }) { entry ->
            Column(Modifier.fillMaxWidth().clickable { requested = if (requested == entry) null else entry; if (requested == null) expanded = null }) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall)
                Text(entry.kind.spdx, style = MaterialTheme.typography.bodySmall)
                (expanded as? LicenseText.Loaded)?.takeIf { it.entry == entry }?.let {
                    Text(it.text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
    }
}
