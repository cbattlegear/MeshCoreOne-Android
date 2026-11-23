// PortedFrom: MC1/Views/Settings/Sections/MapPreviewSettingsSection.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-312 Accessible map appearance selection over the existing process preference.
package com.meshcoreone.android.app.navigation

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.container.AppSettingsFeature
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.maps.MapPreferences
import com.meshcoreone.android.core.maps.MapColorPreference
import kotlinx.coroutines.CancellationException
import com.meshcoreone.android.core.l10n.generated.AppMapStrings as M
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L

@Composable
internal fun AppMapSettings(feature: AppSettingsFeature, onDone: () -> Unit) {
    val context = LocalContext.current
    val displayPreferences = remember(context, feature) {
        feature.observeMapPreferences(context.getSharedPreferences("shared-map", Context.MODE_PRIVATE))
    }
    var display by remember(displayPreferences) { mutableStateOf<MapPreferences?>(null) }
    LaunchedEffect(displayPreferences) {
        try {
            displayPreferences.collect { display = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            display = null
            feature.report("Map display settings could not load", failure)
        }
    }
    val appPreferences by feature.preferenceValues.collectAsState()
    val thumbnailLabel = stringResource(S.mapPreviewsToggle)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(S.mapsDisplayHeader), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            Text(stringResource(S.mapsAppearance), Modifier.semantics { heading() },
                style = MaterialTheme.typography.titleMedium)
            for (appearance in MapColorPreference.entries) {
                val label = when (appearance) {
                    MapColorPreference.SYSTEM -> S.appearanceSchemeSystem
                    MapColorPreference.LIGHT -> S.appearanceSchemeLight
                    MapColorPreference.DARK -> S.appearanceSchemeDark
                }
                val selected = display?.appearance == appearance
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected, enabled = display != null, role = Role.RadioButton,
                            onClick = { feature.setMapAppearance(appearance) })
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    RadioButton(selected = selected, onClick = null, enabled = display != null)
                    Text(stringResource(label), Modifier.weight(1f))
                }
            }
            Text(stringResource(S.mapsAppearanceFooter), style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(M.mapControlsClusterNodes), Modifier.weight(1f))
            Switch(display?.clustering == true,
                { feature.setPreference(AppStorageKey.mapClusteringEnabled, it) },
                enabled = display != null)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(M.mapControlsShowLabels), Modifier.weight(1f))
            Switch(display?.labels == true,
                { feature.setPreference(AppStorageKey.mapShowLabels, it) },
                enabled = display != null)
        }
        Text(stringResource(S.mapPreviewsHeader), style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(thumbnailLabel, Modifier.weight(1f))
            Switch(appPreferences[AppStorageKey.showMapPreviewThumbnails],
                { feature.setPreference(AppStorageKey.showMapPreviewThumbnails, it) },
                Modifier.semantics { contentDescription = thumbnailLabel })
        }
        Text(stringResource(S.mapPreviewsFooter))
        TextButton(onClick = onDone) { Text(stringResource(L.commonDone)) }
    }
}
