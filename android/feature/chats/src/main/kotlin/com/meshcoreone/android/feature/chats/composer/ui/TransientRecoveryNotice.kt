// AndroidOnly: WP-308 Visible volatile-only reply recovery; not a durable record or receiver entry point.
package com.meshcoreone.android.feature.chats.composer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.core.ui.uiString
import java.util.UUID

enum class TransientRetention { NOT_RETAINED, INDETERMINATE }

/** attemptId correlates caller-held evidence only; it is not a storage, submission or issuance receipt. */
data class TransientRecoveryText(
    val attemptId: UUID,
    val exactText: String,
    val retention: TransientRetention,
)

data class TransientRecoveryLabels(
    val title: UiText,
    val notRetained: UiText,
    val indeterminate: UiText,
    val memoryWarning: UiText,
    val possibleSendWarning: UiText,
    val chooseChat: UiText,
    val copyToSelectedChat: UiText,
    val acknowledge: UiText,
)

/**
 * No saveable state or automatic callback. Acknowledgment only acknowledges this notice: it is not
 * retention, consumption, deletion, enrollment or permission to replay. The owner retains evidence.
 */
@Composable
fun TransientRecoveryNotice(
    text: TransientRecoveryText,
    labels: TransientRecoveryLabels,
    destinationLabel: UiText?,
    acknowledged: Boolean,
    working: Boolean,
    failure: UiText?,
    onChooseChat: () -> Unit,
    onCopy: (TransientRecoveryText) -> Unit,
    onAcknowledge: (TransientRecoveryText) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier.padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item("title") {
            Text(uiString(labels.title), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics { heading() })
        }
        item("retention") {
            Text(uiString(when (text.retention) {
                TransientRetention.NOT_RETAINED -> labels.notRetained
                TransientRetention.INDETERMINATE -> labels.indeterminate
            }), color = MaterialTheme.colorScheme.error,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        item("memory-warning") { Text(uiString(labels.memoryWarning)) }
        item("send-warning") { Text(uiString(labels.possibleSendWarning)) }
        item("text") { Text(text.exactText, style = MaterialTheme.typography.bodyLarge) }
        failure?.let { message ->
            item("failure") {
                Text(uiString(message), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            }
        }
        destinationLabel?.let { label ->
            item("destination") { Text(uiString(label)) }
        }
        item("choose") {
            TextButton(onClick = onChooseChat, enabled = !working,
                modifier = Modifier.sharedTouchTarget()) {
                Text(uiString(labels.chooseChat))
            }
        }
        if (working) {
            item("working") { CircularProgressIndicator() }
        }
        item("copy") {
            Button(onClick = { onCopy(text) }, enabled = destinationLabel != null && !working,
                modifier = Modifier.sharedTouchTarget()) {
                Text(uiString(labels.copyToSelectedChat))
            }
        }
        item("acknowledge") {
            TextButton(onClick = { onAcknowledge(text) }, enabled = !acknowledged && !working,
                modifier = Modifier.sharedTouchTarget()) {
                Text(uiString(labels.acknowledge))
            }
        }
    }
}
