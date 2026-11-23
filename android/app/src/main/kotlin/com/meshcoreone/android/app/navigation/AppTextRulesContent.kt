// AndroidOnly: WP-309 Issue #729 native, offline settings over the process-owned text-rule store.
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.domain.TextRulePersisting
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.*
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect

internal data class TextRuleSettingsSnapshot(
    val preference: TextRulesPreference?,
    val conversations: List<TextRuleConversation>,
    val archive: List<RecentlyBlockedText>,
)

@Composable
internal fun AppTextRulesContent(
    store: TextRulePersisting, onDone: () -> Unit, report: (String, Throwable) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember(store) { mutableStateOf<TextRuleSettingsSnapshot?>(null) }
    var failed by remember(store) { mutableStateOf(false) }
    var busy by remember(store) { mutableStateOf(false) }
    val loadFailed by store.textRuleLoadFailed.collectAsState()
    suspend fun reload() {
        // Keep the archive review available even when only preference decoding has failed.
        val conversations = store.textRuleConversations()
        val archive = store.recentlyBlockedTexts()
        snapshot = TextRuleSettingsSnapshot(null, conversations, archive)
        snapshot = TextRuleSettingsSnapshot(store.textRulesPreference(), conversations, archive)
    }
    fun run(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { action(); reload(); failed = false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed = true; report("Text-rule settings failed", failure) }
            finally { busy = false }
        }
    }
    LaunchedEffect(store) {
        try {
            store.textRuleChanges.collect {
                try { reload(); failed = false }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { failed = true; report("Could not load text-rule settings", failure) }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { failed = true; report("Could not observe recently blocked messages", failure) }
    }
    TextRuleSettingsContent(snapshot, busy, failed, loadFailed, onDone,
        onRetry = { run {} },
        onUpdate = { update -> run { store.updateTextRules(update) } },
        onOverride = { key, value -> run { store.setTextRuleOverride(key, value) } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TextRuleSettingsContent(
    snapshot: TextRuleSettingsSnapshot?, busy: Boolean, failed: Boolean, loadFailed: Boolean,
    onDone: () -> Unit, onRetry: () -> Unit,
    onUpdate: ((TextRulesPreference) -> TextRulesPreference) -> Unit,
    onOverride: (TextRuleKey, TextRuleOverride) -> Unit,
) {
    var review by rememberSaveable { mutableStateOf(false) }
    var phrase by rememberSaveable { mutableStateOf("") }
    var fieldName by rememberSaveable { mutableStateOf(TextRuleField.MESSAGE.name) }
    var matchName by rememberSaveable { mutableStateOf(TextRuleMatch.CONTAINS.name) }
    var pendingRuleID by rememberSaveable { mutableStateOf<String?>(null) }
    val field = TextRuleField.valueOf(fieldName)
    val match = TextRuleMatch.valueOf(matchName)
    val preference = snapshot?.preference
    val enabled = preference != null && !busy
    LaunchedEffect(preference?.rules) {
        preference?.rules?.firstOrNull { it.id.toString() == pendingRuleID }?.let {
            if (phrase == it.phrase) phrase = ""
            pendingRuleID = null
        }
    }
    LazyColumn(
        Modifier.fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth().testTag("text-rule-settings"),
        contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TextButton(onClick = onDone) { Text(stringResource(R.string.text_rules_done)) }
            Text(stringResource(R.string.text_rules_title), style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!review, { review = false }, label = { Text(stringResource(R.string.text_rules_rules)) })
                FilterChip(review, { review = true }, label = { Text(stringResource(R.string.text_rules_recent)) })
            }
        }
        if (failed || loadFailed) item {
            Text(stringResource(if (loadFailed) R.string.text_rules_load_failed else R.string.text_rules_failed),
                color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.text_rules_retry)) }
        }
        if (snapshot == null && !failed) item { CircularProgressIndicator() }
        if (review) {
            item { Text(stringResource(R.string.text_rules_recent_description)) }
            if (snapshot?.archive?.isEmpty() == true) item { Text(stringResource(R.string.text_rules_recent_empty)) }
            items(snapshot?.archive.orEmpty(), key = { it.id.toString() }) { entry ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(listOfNotNull(entry.conversationName, entry.radioName).joinToString(" / "),
                            style = MaterialTheme.typography.titleSmall)
                        entry.sender?.let { Text(highlightedText(it, entry.hit.senderHighlights)) }
                        Text(highlightedText(entry.body, entry.hit.bodyHighlights))
                        Text(stringResource(R.string.text_rules_matched, entry.hit.rule.phrase))
                        Text(DateFormat.getDateTimeInstance().format(Date.from(entry.arrivedAt)),
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            item { Text(stringResource(R.string.text_rules_description)) }
            if (preference?.rules?.isEmpty() == true) item { Text(stringResource(R.string.text_rules_empty)) }
            items(preference?.rules.orEmpty(), key = { it.id.toString() }) { rule ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(rule.phrase, style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(fieldLabel(rule.field)) + " / " + stringResource(matchLabel(rule.match)))
                        TextButton(enabled = enabled, onClick = {
                            onUpdate { it.copy(rules = it.rules.filterNot { saved -> saved.id == rule.id }) }
                        }) { Text(stringResource(R.string.text_rules_delete, rule.phrase)) }
                    }
                }
            }
            item {
                OutlinedTextField(phrase, { phrase = it }, Modifier.fillMaxWidth(), enabled = enabled,
                    label = { Text(stringResource(R.string.text_rules_phrase)) })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextRuleField.entries.forEach { choice ->
                        FilterChip(field == choice, { fieldName = choice.name }, enabled = enabled,
                            label = { Text(stringResource(fieldLabel(choice))) })
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextRuleMatch.entries.forEach { choice ->
                        FilterChip(match == choice, { matchName = choice.name }, enabled = enabled,
                            label = { Text(stringResource(matchLabel(choice))) })
                    }
                }
                Button(enabled = enabled && phrase.isNotBlank(), onClick = {
                    val rule = TextRule(phrase = phrase, field = field, match = match)
                    pendingRuleID = rule.id.toString()
                    onUpdate { it.copy(rules = it.rules + rule) }
                }) { Text(stringResource(R.string.text_rules_add)) }
            }
            item {
                Text(stringResource(R.string.text_rules_types), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() })
            }
            TextRuleKind.entries.forEach { kind ->
                val selection = preference?.selections?.get(kind)
                item(key = kind.name) {
                    Text(stringResource(kindLabel(kind)), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() })
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextRuleSelectionMode.entries.forEach { mode ->
                            val scopedLabel = stringResource(R.string.text_rules_scoped_choice,
                                stringResource(kindLabel(kind)), stringResource(selectionLabel(mode)))
                            FilterChip(selection?.mode == mode, enabled = enabled, onClick = {
                                onUpdate { current -> current.copy(selections = current.selections + (kind to
                                    TextRuleSelection(mode, if (mode == TextRuleSelectionMode.FIXED) selection?.keys.orEmpty() else emptySet()))) }
                            }, label = { Text(stringResource(selectionLabel(mode))) },
                                modifier = Modifier.semantics { contentDescription = scopedLabel })
                        }
                    }
                }
                val chats = snapshot?.conversations.orEmpty().filter { it.kind == kind }
                if (chats.isEmpty()) item { Text(stringResource(R.string.text_rules_no_chats)) }
                items(chats, key = { "chat:${it.key.radioId.value}:${it.key.conversationID}" }) { chat ->
                    val chatLabel = stringResource(R.string.text_rules_conversation, chat.name, chat.radioName)
                    Text(chatLabel, style = MaterialTheme.typography.titleSmall)
                    if (selection?.mode == TextRuleSelectionMode.FIXED) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(stringResource(R.string.text_rules_fixed), Modifier.weight(1f))
                            Checkbox(chat.key in selection.keys, enabled = enabled,
                                modifier = Modifier.semantics { contentDescription = chatLabel }, onCheckedChange = { selected ->
                                onUpdate { current ->
                                    val saved = current.selections.getValue(kind)
                                    current.copy(selections = current.selections + (kind to saved.copy(
                                        keys = if (selected) saved.keys + chat.key else saved.keys - chat.key)))
                                }
                            })
                        }
                    }
                    Text(stringResource(R.string.text_rules_override), style = MaterialTheme.typography.labelMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextRuleOverride.entries.forEach { value ->
                            val scopedLabel = stringResource(R.string.text_rules_scoped_choice,
                                chatLabel, stringResource(overrideLabel(value)))
                            FilterChip(chat.override == value, { onOverride(chat.key, value) }, enabled = enabled,
                                label = { Text(stringResource(overrideLabel(value))) },
                                modifier = Modifier.semantics { contentDescription = scopedLabel })
                        }
                    }
                }
            }
        }
        if (busy) item { CircularProgressIndicator() }
    }
}

@Composable
private fun highlightedText(text: String, highlights: List<TextRuleHighlight>): AnnotatedString =
    AnnotatedString.Builder(text).apply {
        val style = SpanStyle(background = MaterialTheme.colorScheme.tertiaryContainer,
            color = MaterialTheme.colorScheme.onTertiaryContainer)
        highlights.forEach {
            if (it.location >= 0 && it.length > 0 && it.location <= text.length - it.length) {
                addStyle(style, it.location, it.location + it.length)
            }
        }
    }.toAnnotatedString()

private fun fieldLabel(value: TextRuleField) = when (value) {
    TextRuleField.MESSAGE -> R.string.text_rules_message
    TextRuleField.SENDER -> R.string.text_rules_sender
    TextRuleField.MESSAGE_OR_SENDER -> R.string.text_rules_either
}
private fun matchLabel(value: TextRuleMatch) = when (value) {
    TextRuleMatch.CONTAINS -> R.string.text_rules_contains
    TextRuleMatch.WHOLE_FIELD -> R.string.text_rules_whole
}
private fun selectionLabel(value: TextRuleSelectionMode) = when (value) {
    TextRuleSelectionMode.OFF -> R.string.text_rules_off
    TextRuleSelectionMode.ALL -> R.string.text_rules_all
    TextRuleSelectionMode.FIXED -> R.string.text_rules_fixed
}
private fun overrideLabel(value: TextRuleOverride) = when (value) {
    TextRuleOverride.INHERIT -> R.string.text_rules_inherit
    TextRuleOverride.ON -> R.string.text_rules_on
    TextRuleOverride.OFF -> R.string.text_rules_off
}
private fun kindLabel(value: TextRuleKind) = when (value) {
    TextRuleKind.PUBLIC_CHANNEL -> R.string.text_rules_public
    TextRuleKind.HASHTAG_CHANNEL -> R.string.text_rules_hashtag
    TextRuleKind.PRIVATE_CHANNEL -> R.string.text_rules_private
    TextRuleKind.DIRECT_MESSAGE -> R.string.text_rules_dm
    TextRuleKind.ROOM -> R.string.text_rules_rooms
}
