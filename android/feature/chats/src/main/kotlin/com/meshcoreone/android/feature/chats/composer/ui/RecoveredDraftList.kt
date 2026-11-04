// AndroidOnly: WP-308 Separate copy-only recovered outgoing drafts; no adoption or send actions.
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
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.chats.composer.RecoveredDraftState

data class RecoveredDraftLabels(
    val title: UiText,
    val explanation: UiText,
    val empty: UiText,
    val refresh: UiText,
    val loadMore: UiText,
    val chooseChat: UiText,
    val copyToSelectedChat: UiText,
)

/** Insets and destination selection belong to the enclosing native route. Every control scrolls. */
@Composable
fun RecoveredDraftList(
    state: RecoveredDraftState,
    labels: RecoveredDraftLabels,
    destinationLabel: UiText?,
    statusLabel: (MessageDTO) -> UiText,
    failureText: @Composable (Throwable) -> String,
    onSelect: (EntityKey) -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onChooseChat: () -> Unit,
    onCopy: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = state.loading || state.copying
    LazyColumn(
        modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item("heading") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(uiString(labels.title), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
                Text(uiString(labels.explanation), style = MaterialTheme.typography.bodyMedium)
            }
        }
        state.failure?.let { failure ->
            item("failure") {
                Text(failureText(failure), Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (state.messages.isEmpty() && !state.loading && state.failure == null) {
            item("empty") { Text(uiString(labels.empty), Modifier.padding(horizontal = 16.dp)) }
        }
        items(state.messages, key = { "${it.radioId.value}:${it.id}" }) { message ->
            val key = EntityKey(message.radioId, message.id)
            Column {
                Row(
                    Modifier.fillMaxWidth().sharedTouchTarget()
                        .selectable(
                            selected = state.selected == key, enabled = !busy, role = Role.RadioButton,
                            onClick = { onSelect(key) },
                        ).padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    RadioButton(selected = state.selected == key, onClick = null, enabled = !busy)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(message.text, style = MaterialTheme.typography.bodyLarge)
                        Text(uiString(statusLabel(message)), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider()
            }
        }
        if (busy) {
            item("progress") { CircularProgressIndicator(Modifier.padding(16.dp)) }
        }
        item("paging") {
            Column(Modifier.padding(horizontal = 16.dp)) {
                if (state.hasMore && state.messages.isNotEmpty()) {
                    TextButton(onClick = onLoadMore, enabled = !busy, modifier = Modifier.sharedTouchTarget()) {
                        Text(uiString(labels.loadMore))
                    }
                }
                TextButton(onClick = onRefresh, enabled = !busy, modifier = Modifier.sharedTouchTarget()) {
                    Text(uiString(labels.refresh))
                }
            }
        }
        item("explicit-copy") {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                destinationLabel?.let { Text(uiString(it), style = MaterialTheme.typography.titleSmall) }
                TextButton(
                    onClick = onChooseChat, enabled = !busy && state.selected != null,
                    modifier = Modifier.sharedTouchTarget(),
                ) { Text(uiString(labels.chooseChat)) }
                Button(
                    onClick = onCopy, enabled = !busy && state.selected != null && destinationLabel != null,
                    modifier = Modifier.sharedTouchTarget(),
                ) { Text(uiString(labels.copyToSelectedChat)) }
            }
        }
    }
}
