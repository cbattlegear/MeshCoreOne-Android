// PortedFrom: MC1/Views/PathEditing/AddHopPickerView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.trace

import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.R as Strings

internal class TraceCodeInputState {
    var expanded by mutableStateOf(false)
    var input by mutableStateOf("")
    var outcome by mutableStateOf<CodeInputResult?>(null)
    var nextHopAnnouncement by mutableStateOf<Int?>(null)
    var restoringDraft by mutableStateOf(false)
    var restoredHashMode: UByte? = null
}

private fun traceCodeInputSaver(holder: TracePathStateHolder) = Saver<TraceCodeInputState, Bundle>(
    save = { entry ->
        Bundle().apply {
            putBoolean("expanded", entry.expanded)
            putString("input", entry.input)
            holder.state.value.traceHashMode?.let { putInt("traceHashMode", it.toInt()) }
            entry.nextHopAnnouncement?.let { putInt("nextHop", it) }
            entry.outcome?.let { result ->
                putStringArrayList("added", ArrayList(result.added))
                putStringArrayList("notFound", ArrayList(result.notFound))
                putStringArrayList("alreadyInPath", ArrayList(result.alreadyInPath))
                putStringArrayList("invalidFormat", ArrayList(result.invalidFormat))
            }
        }
    },
    restore = { saved ->
        TraceCodeInputState().apply {
            expanded = saved.getBoolean("expanded")
            input = requireNotNull(saved.getString("input"))
            restoredHashMode = if (saved.containsKey("traceHashMode")) {
                saved.getInt("traceHashMode").also { require(it in 0..255) }.toUByte()
            } else null
            nextHopAnnouncement = if (saved.containsKey("nextHop")) saved.getInt("nextHop") else null
            if (saved.containsKey("added")) outcome = CodeInputResult(
                added = requireNotNull(saved.getStringArrayList("added")),
                notFound = requireNotNull(saved.getStringArrayList("notFound")),
                alreadyInPath = requireNotNull(saved.getStringArrayList("alreadyInPath")),
                invalidFormat = requireNotNull(saved.getStringArrayList("invalidFormat")),
            )
            restoringDraft = true
        }
    },
)

@Composable
internal fun rememberTraceCodeInputState(holder: TracePathStateHolder): TraceCodeInputState {
    val saver = remember(holder) { traceCodeInputSaver(holder) }
    val entry = rememberSaveable(saver = saver) { TraceCodeInputState() }
    LaunchedEffect(entry, holder) {
        // Restore only saved screen state, never a lazy-item remount.
        if (entry.restoringDraft) {
            entry.restoredHashMode?.let(holder::adoptTraceHashMode)
            entry.restoringDraft = false
        }
    }
    return entry
}

@Composable
internal fun TraceCodeInput(
    state: TracePathState,
    holder: TracePathStateHolder,
    entry: TraceCodeInputState = rememberTraceCodeInputState(holder),
) {
    var expanded by entry::expanded
    var input by entry::input
    var outcome by entry::outcome
    var nextHopAnnouncement by entry::nextHopAnnouncement
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    val classifications = remember(input, state, holder, entry.restoringDraft) {
        if (entry.restoringDraft) emptyList() else holder.classifyCodes(input)
    }
    val addable = classifications.filter { it.willBeAdded }.map { it.code }

    fun edit(value: String) {
        if (value.contains(',')) holder.adoptHashSize(value)
        input = value
        outcome = null
        nextHopAnnouncement = null
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!expanded) {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            ) { Text(stringResource(C.contactsPathEditAddHop)) }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = ::edit,
                label = { Text(stringResource(C.contactsPathEditAddHop)) },
                supportingText = {
                    Text(stringResource(Strings.string.l10n_app_contacts_contacts_add_hexplaceholder, holder.hashSize * 2))
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
            TextButton(
                onClick = {
                    outcome = null
                    nextHopAnnouncement = null
                    clipboard.getText()?.text?.trim {
                        SwiftText.isWhitespace(it.code) || it in "\n\r\u000B\u000C\u0085\u2028\u2029"
                    }?.takeIf { it.isNotEmpty() }?.let(::edit)
                },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text(stringResource(C.contactsPathEditPaste)) }
            classifications.forEach { entry ->
                val message = when (entry.status) {
                    is HopCodeStatus.WillAdd -> stringResource(
                        Strings.string.l10n_app_contacts_contacts_pathedit_bulkadd_willadd, entry.code,
                    )
                    HopCodeStatus.AlreadyInPath -> stringResource(
                        Strings.string.l10n_app_contacts_contacts_codeinput_error_alreadyinpath, entry.code,
                    )
                    HopCodeStatus.NotFound -> stringResource(
                        Strings.string.l10n_app_contacts_contacts_codeinput_error_notfound, entry.code,
                    )
                    HopCodeStatus.InvalidFormat -> stringResource(
                        Strings.string.l10n_app_contacts_contacts_codeinput_error_invalidformat, entry.code,
                    )
                    HopCodeStatus.PathFull -> stringResource(
                        Strings.string.l10n_app_contacts_contacts_pathedit_bulkadd_pathfull, entry.code,
                    )
                }
                Text(
                    message,
                    color = if (entry.willBeAdded) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = {
                    val result = holder.addCodes(input)
                    outcome = result
                    nextHopAnnouncement = if (result.added.isNotEmpty()) holder.state.value.outboundPath.size + 1 else null
                    input = ""
                },
                enabled = addable.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp),
            ) {
                Text(
                    if (addable.isEmpty()) stringResource(C.contactsPathEditBulkAddEmpty)
                    else stringResource(
                        Strings.string.l10n_app_contacts_contacts_pathedit_bulkadd_action, addable.joinToString(", "),
                    ),
                )
            }
            outcome?.takeIf { it.added.isNotEmpty() || it.hasErrors }?.let { result ->
                Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
                    nextHopAnnouncement?.let { nextHop ->
                        Text(stringResource(Strings.string.l10n_app_contacts_contacts_pathedit_positionappend, nextHop))
                    }
                    if (result.invalidFormat.isNotEmpty()) Text(
                        stringResource(
                            Strings.string.l10n_app_contacts_contacts_codeinput_error_invalidformat,
                            result.invalidFormat.joinToString(", "),
                        ), color = MaterialTheme.colorScheme.error,
                    )
                    if (result.notFound.isNotEmpty()) Text(
                        stringResource(
                            Strings.string.l10n_app_contacts_contacts_codeinput_error_notfound,
                            result.notFound.joinToString(", "),
                        ), color = MaterialTheme.colorScheme.error,
                    )
                    if (result.alreadyInPath.isNotEmpty()) Text(
                        stringResource(
                            Strings.string.l10n_app_contacts_contacts_codeinput_error_alreadyinpath,
                            result.alreadyInPath.joinToString(", "),
                        ), color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            TextButton(
                onClick = { expanded = false; input = ""; outcome = null; nextHopAnnouncement = null },
                modifier = Modifier.sizeIn(minHeight = 48.dp),
            ) { Text(stringResource(L.commonDone)) }
            LaunchedEffect(Unit) { focus.requestFocus() }
        }
    }
}
