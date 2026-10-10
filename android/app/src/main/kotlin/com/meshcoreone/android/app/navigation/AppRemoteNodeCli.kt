// PortedFrom: MC1/Views/RemoteNodes/NodeCLIView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as L
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.feature.remotenodes.settings.RemoteCliSend
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.feature.tools.diagnostics.ResourcesDiagnosticsText
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsText
import com.meshcoreone.android.feature.tools.diagnostics.cli.*
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.app.container.appCliErrors

@Composable
internal fun AppRemoteNodeCli(session: RemoteNodeSessionDTO, send: RemoteCliSend, enabled: Boolean) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val diagnostics by rememberUpdatedState(remember(resources) { ResourcesDiagnosticsText(resources) })
    val noService by rememberUpdatedState(stringResource(L.remoteNodesSettingsNoService))
    val currentSend by rememberUpdatedState(send)
    val canSend by rememberUpdatedState(enabled && session.isAdmin)
    val holder = remember(session.id) {
        NodeCliStateHolder(scope, object : DiagnosticsText {
            override val locale get() = diagnostics.locale
            override fun string(id: Int) = diagnostics.string(id)
            override fun format(id: Int, vararg args: Any) = diagnostics.format(id, *args)
            override fun joinList(items: List<String>) = diagnostics.joinList(items)
        }, errors = appCliErrors)
    }
    val controller = remember(holder) { CliTerminalController(holder, scope) }
    val state by holder.state.collectAsStateWithLifecycle()
    val terminal by controller.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<String?>(null) }
    val historyUp = stringResource(AppToolsStrings.toolsCliHistoryUp)
    val historyDown = stringResource(AppToolsStrings.toolsCliHistoryDown)
    val tabComplete = stringResource(AppToolsStrings.toolsCliTabComplete)
    LaunchedEffect(holder) {
        holder.configure(session.name) { command, timeout ->
            check(canSend) { noService }
            currentSend(EntityKey(session.radioId, session.id), command, timeout)
        }
        controller.onAppear()
    }
    LaunchedEffect(state.isWaitingForResponse) { controller.onWaitingChanged(state.isWaitingForResponse) }
    LaunchedEffect(enabled) { if (!enabled) holder.cancelCurrentCommand() }
    DisposableEffect(holder) { onDispose { holder.cancelCurrentCommand(); controller.onDisappear() } }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.terminal.outputLines, key = { it.id }) { line ->
                    Text(line.text, fontFamily = FontFamily.Monospace)
                }
            }
        }
        OutlinedTextField(
            TextFieldValue(state.terminal.currentInput, TextRange(terminal.cursorPosition)),
            { controller.onTextChanged(it.text.replace("\n", ""), it.selection.end) },
            Modifier.fillMaxWidth().testTag("remote-cli-input").onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !canSend) false
                else when (event.key) {
                    Key.DirectionUp -> { controller.onHistoryUp(); true }
                    Key.DirectionDown -> { controller.onHistoryDown(); true }
                    Key.Tab -> { controller.onTabComplete(); true }
                    Key.Enter, Key.NumPadEnter -> {
                        if (state.terminal.currentInput.isNotBlank()) pending = state.terminal.currentInput
                        true
                    }
                    else -> false
                }
            }, enabled = canSend && !state.isWaitingForResponse,
            label = { Text(holder.promptText) }, singleLine = true,
            supportingText = { Text(state.terminal.ghostText) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (canSend) pending = state.terminal.currentInput }),
        )
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(controller::onHistoryUp, Modifier.semantics { contentDescription = historyUp }) { Text("↑") }
            TextButton(controller::onHistoryDown, Modifier.semantics { contentDescription = historyDown }) { Text("↓") }
            TextButton({ controller.onTabComplete() }, Modifier.semantics { contentDescription = tabComplete }) { Text("Tab") }
            Button({ pending = state.terminal.currentInput }, enabled = canSend && !state.isWaitingForResponse && state.terminal.currentInput.isNotBlank()) {
                Text(stringResource(L.remoteNodesSettingsOk))
            }
            if (terminal.showCancel) TextButton(controller::onCancel) { Text(stringResource(L.remoteNodesCancel)) }
        }
    }
    pending?.let { command ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(L.remoteNodesAuthManagement)) },
            text = { Text(command) },
            dismissButton = { TextButton({ pending = null }) { Text(stringResource(L.remoteNodesCancel)) } },
            confirmButton = { TextButton({
                pending = null
                if (canSend) holder.executeCommand(command)
            }, Modifier.heightIn(min = 48.dp).testTag("remote-cli-confirm"), enabled = canSend) { Text(stringResource(L.remoteNodesSettingsOk)) } },
        )
    }
}
