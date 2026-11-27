// AndroidOnly: WP-002 Accessible explicit incomplete content; no service construction or successful action fallback.
package com.meshcoreone.android.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.SemanticsPropertyReceiver
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.FeatureShellState
import com.meshcoreone.android.core.l10n.R

data class FeatureShellCopy(val title: String, val description: String, val unavailableAction: String)
data class ScaffoldLink(val title: String, val route: FeatureRoute)

val ScaffoldAvailabilityKey = SemanticsPropertyKey<String>("ScaffoldAvailability")
var SemanticsPropertyReceiver.scaffoldAvailability by ScaffoldAvailabilityKey

@Composable
fun ScaffoldFeatureContent(
    expected: FeatureId,
    route: FeatureRoute,
    copy: FeatureShellCopy,
    onNavigate: (FeatureRoute) -> Unit,
    links: List<ScaffoldLink> = emptyList(),
) {
    require(route.feature == expected) { "Entry ${expected.stableId} received ${route.feature.stableId}" }
    val state = FeatureShellState(route)
    Column(
        modifier = Modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .testTag("feature:${route.feature.stableId}")
            .semantics { scaffoldAvailability = state.availability.name }
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(copy.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.semantics { heading() })
        Text(
            stringResource(R.string.scaffold_not_yet_ported),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(copy.description, style = MaterialTheme.typography.bodyLarge)
        Button(
            onClick = {},
            enabled = state.connectionActionsEnabled,
            modifier = Modifier.testTag("unavailable-action"),
        ) {
            Text(copy.unavailableAction)
        }
        Text(
            stringResource(R.string.scaffold_explanation),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        links.forEach { link ->
            TextButton(onClick = { onNavigate(link.route) }) {
                Text(link.title)
            }
        }
    }
}
