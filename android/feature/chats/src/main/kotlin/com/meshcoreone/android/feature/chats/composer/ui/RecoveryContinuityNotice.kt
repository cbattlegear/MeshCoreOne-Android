// AndroidOnly: WP-308 Unactivated loss-aware recovery acknowledgment; no storage or generation side effects.
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString

data class RecoveryContinuityLabels(
    val title: UiText,
    val continuityUnavailable: UiText,
    val lostTextWarning: UiText,
    val copyOnlyExplanation: UiText,
    val acknowledgment: UiText,
    val createNewGeneration: UiText,
    val inspectRecoveredText: UiText,
)

/** The route must reset acknowledgment when the underlying continuity evidence changes. */
@Composable
fun RecoveryContinuityNotice(
    labels: RecoveryContinuityLabels,
    acknowledged: Boolean,
    working: Boolean,
    creationAvailable: Boolean,
    failure: UiText?,
    onAcknowledgedChange: (Boolean) -> Unit,
    onCreateNewGeneration: () -> Unit,
    onInspectRecoveredText: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("title") {
            Text(uiString(labels.title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
        }
        item("continuity") { Text(uiString(labels.continuityUnavailable)) }
        item("loss") { Text(uiString(labels.lostTextWarning)) }
        item("copy-only") { Text(uiString(labels.copyOnlyExplanation)) }
        failure?.let { message ->
            item("failure") {
                Text(uiString(message), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        item("inspect") {
            TextButton(onClick = onInspectRecoveredText, enabled = !working,
                modifier = Modifier.sharedTouchTarget()) {
                Text(uiString(labels.inspectRecoveredText))
            }
        }
        item("acknowledgment") {
            Row(
                Modifier.fillMaxWidth().sharedTouchTarget().toggleable(
                    value = acknowledged, enabled = !working,
                    role = Role.Checkbox, onValueChange = onAcknowledgedChange,
                ).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = acknowledged, onCheckedChange = null, enabled = !working)
                Text(uiString(labels.acknowledgment), Modifier.weight(1f))
            }
        }
        if (working) {
            item("working") { CircularProgressIndicator() }
        }
        item("create") {
            Button(
                onClick = onCreateNewGeneration,
                enabled = acknowledged && creationAvailable && !working,
                modifier = Modifier.sharedTouchTarget(),
            ) { Text(uiString(labels.createNewGeneration)) }
        }
    }
}
