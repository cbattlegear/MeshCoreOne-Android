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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setText
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.R as Strings
import com.meshcoreone.android.core.model.RadioId

internal data class TraceCodeInputConnection(val generation: Int, val radioId: RadioId?)

internal class TraceCodeInputState {
    var expanded by mutableStateOf(false)
    var input by mutableStateOf("")
    var outcome by mutableStateOf<CodeInputResult?>(null)
    var nextHopAnnouncement by mutableStateOf<Int?>(null)
    var restoringDraft by mutableStateOf(false)
    var restoredHashMode: UByte? = null
    var connection by mutableStateOf<TraceCodeInputConnection?>(null)
}

private fun traceCodeInputSaver(holder: TracePathStateHolder) = Saver<TraceCodeInputState, Bundle>(
    save = { entry ->
        Bundle().apply {
            putBoolean("expanded", entry.expanded)
            putString("input", entry.input)
            val savedMode = holder.state.value.traceHashMode
                ?: entry.restoredHashMode.takeIf { entry.restoringDraft }
            savedMode?.let { putInt("traceHashMode", it.toInt()) }
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
internal fun rememberTraceCodeInputState(
    holder: TracePathStateHolder,
    connectionGeneration: Int = 0,
    radioId: RadioId? = holder.deps.connectedDevice()?.radioId,
): TraceCodeInputState {
    val saver = remember(holder) { traceCodeInputSaver(holder) }
    val entry = rememberSaveable(saver = saver) { TraceCodeInputState() }
    DisposableEffect(entry, holder) {
        val stopObserving = holder.observeHashModeActions {
            entry.restoredHashMode = null
            entry.restoringDraft = false
        }
        onDispose { stopObserving() }
    }
    LaunchedEffect(entry, holder, connectionGeneration, radioId) {
        entry.connection = TraceCodeInputConnection(connectionGeneration, radioId)
        // Restore only saved screen state, never a lazy-item remount.
        val device = holder.deps.connectedDevice()
        if (entry.restoringDraft &&
            (entry.restoredHashMode == null || device != null && device.radioId == radioId)
        ) {
            val restored = entry.restoredHashMode?.let { holder.restoreTraceHashMode(it, radioId) } ?: true
            if (restored) {
                entry.restoredHashMode = null
                entry.restoringDraft = false
            }
        }
    }
    return entry
}

@Composable
internal fun TraceCodeInput(
    state: TracePathState,
    holder: TracePathStateHolder,
    entry: TraceCodeInputState = rememberTraceCodeInputState(holder),
    canEdit: () -> Boolean = { holder.isConnectionCurrent() },
) {
    var expanded by entry::expanded
    var input by entry::input
    var outcome by entry::outcome
    var nextHopAnnouncement by entry::nextHopAnnouncement
    var requestFocus by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val focus = remember { FocusRequester() }
    val connection = entry.connection
    val device = holder.deps.connectedDevice()
    val mismatchedDevice = connection != null && device?.radioId != connection.radioId
    val waitingForDevice = mismatchedDevice ||
        (entry.restoringDraft && entry.restoredHashMode != null && (device == null || device.radioId != connection?.radioId))
    val hashSize = holder.hashSize
    val classifications = remember(input, state, holder, entry.restoringDraft, hashSize, connection) {
        if (entry.restoringDraft || mismatchedDevice) emptyList() else holder.classifyCodes(input)
    }
    val addable = classifications.filter { it.willBeAdded }.map { it.code }

    fun edit(value: String) {
        if (!canEdit()) return
        if (value.contains(',')) holder.adoptHashSize(value)
        input = value
        outcome = null
        nextHopAnnouncement = null
    }

    val expand = { if (canEdit()) { requestFocus = true; expanded = true } }
    val paste = {
        if (canEdit()) {
            outcome = null
            nextHopAnnouncement = null
            clipboard.getText()?.text?.trim {
                SwiftText.isWhitespace(it.code) || it in "\n\r\u000B\u000C\u0085\u2028\u2029"
            }?.takeIf { it.isNotEmpty() }?.let(::edit)
        }
    }
    val add = {
        if (canEdit()) {
            val result = holder.addCodes(input)
            outcome = result
            nextHopAnnouncement = if (result.added.isNotEmpty()) holder.state.value.outboundPath.size + 1 else null
            input = ""
        }
    }
    val done = {
        if (canEdit()) {
            expanded = false
            requestFocus = false
            input = ""
            outcome = null
            nextHopAnnouncement = null
        }
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!expanded) {
            OutlinedButton(
                onClick = expand,
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                    onClick { expand(); true }
                },
            ) { Text(stringResource(C.contactsPathEditAddHop)) }
        } else {
            OutlinedTextField(
                value = input,
                onValueChange = ::edit,
                label = { Text(stringResource(C.contactsPathEditAddHop)) },
                supportingText = {
                    if (!waitingForDevice) {
                        Text(stringResource(Strings.string.l10n_app_contacts_contacts_add_hexplaceholder, hashSize * 2))
                    }
                },
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics {
                    setText { edit(it.text); true }
                },
            )
            TextButton(
                onClick = paste,
                modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                    onClick { paste(); true }
                },
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
                onClick = add,
                enabled = addable.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().sizeIn(minHeight = 48.dp).semantics {
                    onClick { add(); true }
                },
            ) {
                Text(
                    if (waitingForDevice) stringResource(Strings.string.l10n_app_contacts_contacts_add_error_notconnected)
                    else if (addable.isEmpty()) stringResource(C.contactsPathEditBulkAddEmpty)
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
                onClick = done,
                modifier = Modifier.sizeIn(minHeight = 48.dp).semantics {
                    onClick { done(); true }
                },
            ) { Text(stringResource(L.commonDone)) }
            LaunchedEffect(requestFocus) {
                if (requestFocus) {
                    focus.requestFocus()
                    requestFocus = false
                }
            }
        }
    }
}
