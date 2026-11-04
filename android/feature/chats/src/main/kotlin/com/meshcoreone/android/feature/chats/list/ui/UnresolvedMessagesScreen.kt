// AndroidOnly: WP-306 Established Chats read surface for unproved direct messages, not a conversation.
package com.meshcoreone.android.feature.chats.list.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.UnprovedDirectMessage
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.ui.sharedTouchTarget
import com.meshcoreone.android.feature.chats.list.UnresolvedMessagesState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnresolvedMessagesScreen(
    state: UnresolvedMessagesState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val formatter = remember(locale) {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneId.systemDefault())
    }
    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.unresolved_messages_title), Modifier.semantics { heading() }) },
                navigationIcon = {
                    TextButton(onBack, Modifier.sharedTouchTarget()) { Text(stringResource(R.string.unresolved_messages_back)) }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().testTag("unresolved-messages"),
        ) {
            item(key = "context") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.radioId?.let {
                        Text(stringResource(R.string.unresolved_messages_radio, it.value.toString()),
                            style = MaterialTheme.typography.labelLarge)
                    }
                    Text(stringResource(R.string.unresolved_messages_notice), style = MaterialTheme.typography.bodyMedium)
                    if (state.radioId != null) {
                        TextButton(onRefresh, Modifier.sharedTouchTarget(), enabled = !state.isLoading) {
                            Text(stringResource(R.string.unresolved_messages_refresh))
                        }
                    }
                }
            }
            items(state.messages, key = { "${it.radioId.value}:${it.occurrenceID}" }) { message ->
                UnresolvedMessageRow(message, formatter)
                HorizontalDivider()
            }
            item(key = "status") {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        state.radioId == null -> Text(stringResource(R.string.unresolved_messages_no_radio))
                        state.isLoading -> {
                            CircularProgressIndicator()
                            Text(stringResource(R.string.unresolved_messages_loading))
                        }
                        state.loadFailed -> {
                            Text(stringResource(R.string.unresolved_messages_error), color = MaterialTheme.colorScheme.error)
                            TextButton(onRefresh, Modifier.sharedTouchTarget()) {
                                Text(stringResource(AppLocalizableStrings.commonTryAgain))
                            }
                        }
                        state.messages.isEmpty() -> Text(stringResource(R.string.unresolved_messages_empty))
                        state.hasMore -> TextButton(onLoadMore, Modifier.sharedTouchTarget()) {
                            Text(stringResource(R.string.unresolved_messages_more))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UnresolvedMessageRow(message: UnprovedDirectMessage, formatter: DateTimeFormatter) {
    Column(
        Modifier.fillMaxWidth().testTag("unresolved-occurrence:${message.occurrenceID}").padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val provenance = when (message.provenance) {
            com.meshcoreone.android.core.contracts.domain.UnprovedDirectMessageProvenance.UNRESOLVED ->
                R.string.unresolved_messages_sender
            com.meshcoreone.android.core.contracts.domain.UnprovedDirectMessageProvenance.LOCAL_CANDIDATE ->
                R.string.unresolved_messages_candidate
            com.meshcoreone.android.core.contracts.domain.UnprovedDirectMessageProvenance.LEGACY_UNKNOWN ->
                R.string.unresolved_messages_legacy
        }
        Text(stringResource(provenance), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyLarge) }
        Text(message.wireTimestamp?.let {
            stringResource(R.string.unresolved_messages_wire_time, formatter.format(Instant.ofEpochSecond(it.toLong())))
        } ?: stringResource(R.string.unresolved_messages_wire_time_unknown),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(message.receivedAt?.let {
            stringResource(R.string.unresolved_messages_received_time, formatter.format(it))
        } ?: stringResource(R.string.unresolved_messages_received_time_unknown),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
