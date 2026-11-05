// AndroidOnly: WP-308 No-route presentation of exact retained text; observed coordinates are not authority.
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString
import java.util.UUID

/** Display coordinates only; never a radio, destination, authenticated issuance or outgoing message. */
data class UnboundRecoveryKey(val storeId: UUID, val recordId: UUID, val sequence: Long)
data class UnboundRecoveryText(val key: UnboundRecoveryKey, val exactText: String)

/** A whole inventory is supplied by its owner; this content never reads, retains or mutates storage. */
@Composable
fun UnboundRecoveryList(
    records: List<UnboundRecoveryText>,
    selected: UnboundRecoveryKey?,
    labels: RecoveredDraftLabels,
    unboundWarning: UiText,
    destinationLabel: UiText?,
    working: Boolean,
    failure: UiText?,
    onSelect: (UnboundRecoveryKey) -> Unit,
    onRefresh: () -> Unit,
    onChooseChat: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedPresent = selected != null && records.any { it.key == selected }
    LazyColumn(modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item("heading") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiString(labels.title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                Text(uiString(labels.explanation))
                Text(uiString(unboundWarning))
            }
        }
        failure?.let { message ->
            item("failure") {
                Text(uiString(message), Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error)
            }
        }
        if (records.isEmpty() && !working && failure == null) {
            item("empty") { Text(uiString(labels.empty), Modifier.padding(horizontal = 16.dp)) }
        }
        items(records, key = { "${it.key.storeId}:${it.key.recordId}:${it.key.sequence}" }) { record ->
            Column {
                Row(
                    Modifier.fillMaxWidth().sharedTouchTarget().selectable(
                        selected = selected == record.key, enabled = !working,
                        role = Role.RadioButton, onClick = { onSelect(record.key) },
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = selected == record.key, onClick = null, enabled = !working)
                    Text(record.exactText, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                }
                HorizontalDivider()
            }
        }
        if (working) {
            item("progress") { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }
        item("actions") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onRefresh, enabled = !working, modifier = Modifier.sharedTouchTarget()) {
                    Text(uiString(labels.refresh))
                }
                destinationLabel?.let { Text(uiString(it)) }
                TextButton(onChooseChat, enabled = selectedPresent && !working,
                    modifier = Modifier.sharedTouchTarget()) {
                    Text(uiString(labels.chooseChat))
                }
                Button(onCopy, enabled = selectedPresent && destinationLabel != null && !working,
                    modifier = Modifier.sharedTouchTarget()) {
                    Text(uiString(labels.copyToSelectedChat))
                }
            }
        }
    }
}
