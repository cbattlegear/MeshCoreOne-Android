// AndroidOnly: WP-208 Read-only inbox for direct messages with a known sender-prefix collision.
package com.meshcoreone.android.feature.chats.list.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.feature.chats.list.ChatListFeatureDependencies

@Composable
internal fun AmbiguousDirectInbox(dependencies: ChatListFeatureDependencies, radioId: RadioId) {
    val messages by remember(dependencies, radioId) { dependencies.observeAmbiguousDirectMessages(radioId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    var open by rememberSaveable(radioId.toString()) { mutableStateOf(false) }
    if (messages.isEmpty()) return
    TextButton(onClick = { open = true }) { Text(stringResource(R.string.chats_ambiguous_direct_title)) }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text(stringResource(R.string.chats_ambiguous_direct_title)) },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text(stringResource(R.string.chats_ambiguous_direct_explanation)) }
                items(messages, key = { it.id.toString() }) { message ->
                    SelectionContainer { Text(message.text) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
