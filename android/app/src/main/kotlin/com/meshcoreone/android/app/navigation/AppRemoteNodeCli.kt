// PortedFrom: MC1/Views/RemoteNodes/NodeCLIView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
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
internal fun AppRemoteNodeCli(
    session: RemoteNodeSessionDTO,
    send: RemoteCliSend,
    enabled: Boolean,
    stateHolder: NodeCliStateHolder? = null,
) {
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current
    val clipboard = LocalClipboardManager.current
    val copy = stringResource(AppToolsStrings.toolsRxLogCopy)
    val diagnostics by rememberUpdatedState(remember(resources) { ResourcesDiagnosticsText(resources) })
    val noService by rememberUpdatedState(stringResource(L.remoteNodesSettingsNoService))
    val currentSend by rememberUpdatedState(send)
    val canSend by rememberUpdatedState(enabled && session.isAdmin)
    val holder = remember(session.id, stateHolder) {
        stateHolder ?: NodeCliStateHolder(scope, object : DiagnosticsText {
            override val locale get() = diagnostics.locale
            override fun string(id: Int) = diagnostics.string(id)
            override fun format(id: Int, vararg args: Any) = diagnostics.format(id, *args)
            override fun joinList(items: List<String>) = diagnostics.joinList(items)
        }, errors = appCliErrors)
    }
    val controller = remember(holder) { CliTerminalController(holder, scope) }
    val inputFocus = remember { FocusRequester() }
    val state by holder.state.collectAsStateWithLifecycle()
    val terminal by controller.state.collectAsStateWithLifecycle()
    var pending by remember { mutableStateOf<String?>(null) }
    var choosingCopy by remember { mutableStateOf(false) }
    val outputScroll = rememberLazyListState()
    val historyUp = stringResource(AppToolsStrings.toolsCliHistoryUp)
    val historyDown = stringResource(AppToolsStrings.toolsCliHistoryDown)
    val tabComplete = stringResource(AppToolsStrings.toolsCliTabComplete)
    val success = stringResource(L.remoteNodesSettingsSuccess)
    val submit: () -> Unit = {
        if (canSend && !holder.isWaitingForResponse) {
            controller.onSubmit { command ->
                if (command.isNotBlank()) pending = command
            }
        }
    }
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
        SelectionContainer(Modifier.weight(1f).testTag("remote-cli-output")) {
            LazyColumn(Modifier.fillMaxSize(), state = outputScroll) {
                items(state.terminal.outputLines, key = { it.id }) { line ->
                        Text(
                            line.text,
                            modifier = Modifier.semantics {
                                customActions = listOf(CustomAccessibilityAction(copy) {
                                    clipboard.setText(AnnotatedString(holder.getResponseBlock(line)))
                                    true
                                })
                                when (line.type) {
                                    CliOutputType.SUCCESS -> stateDescription = success
                                    CliOutputType.ERROR -> error(line.text)
                                    CliOutputType.COMMAND, CliOutputType.RESPONSE -> Unit
                                }
                            },
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (line.type == CliOutputType.SUCCESS) FontWeight.Bold else FontWeight.Normal,
                            fontStyle = if (line.type == CliOutputType.ERROR) FontStyle.Italic else FontStyle.Normal,
                            color = when (line.type) {
                                CliOutputType.COMMAND -> MaterialTheme.colorScheme.onSurfaceVariant
                                CliOutputType.SUCCESS -> MaterialTheme.colorScheme.primary
                                CliOutputType.ERROR -> MaterialTheme.colorScheme.error
                                CliOutputType.RESPONSE -> MaterialTheme.colorScheme.onSurface
                            },
                        )
                }
            }
        }
        state.terminal.tabSuggestions?.let { suggestions ->
            var viewportSize by remember { mutableStateOf(IntSize.Zero) }
            Box(Modifier.fillMaxWidth().onGloballyPositioned { viewportSize = it.size }) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState())
                        .testTag("remote-cli-suggestions"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEachIndexed { index, suggestion ->
                        // The source vocabulary repeats labels; keyboard selection identifies the slot.
                        key(index, suggestion) {
                            val selected = index == state.terminal.tabSelectionIndex
                            val bringIntoView = remember { BringIntoViewRequester() }
                            var chipSize by remember { mutableStateOf(IntSize.Zero) }
                            LaunchedEffect(selected, viewportSize, chipSize) {
                                if (selected && viewportSize != IntSize.Zero && chipSize != IntSize.Zero) {
                                    bringIntoView.bringIntoView()
                                }
                            }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    if (canSend && !holder.isWaitingForResponse && holder.applySuggestion(suggestion)) {
                                        controller.onSelectionChanged(holder.currentInput.length)
                                        inputFocus.requestFocus()
                                    }
                                },
                                label = { Text(suggestion) },
                                enabled = canSend && !state.isWaitingForResponse,
                                modifier = Modifier.heightIn(min = 48.dp).bringIntoViewRequester(bringIntoView)
                                    .onGloballyPositioned { chipSize = it.size }
                                    .testTag("remote-cli-suggestion:$suggestion"),
                            )
                        }
                    }
                }
            }
        }
        OutlinedTextField(
            TextFieldValue(state.terminal.currentInput, TextRange(terminal.cursorPosition)),
            { controller.onTextChanged(it.text.replace("\n", ""), it.selection.end) },
            Modifier.fillMaxWidth().focusRequester(inputFocus).testTag("remote-cli-input").onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !canSend) false
                else when (event.key) {
                    Key.DirectionUp -> { controller.onHistoryUp(); true }
                    Key.DirectionDown -> { controller.onHistoryDown(); true }
                    Key.Tab -> { controller.onTabComplete(); true }
                    Key.Escape -> {
                        if (holder.state.value.terminal.tabSuggestions != null) {
                            holder.clearTabState()
                            true
                        } else false
                    }
                    Key.Enter, Key.NumPadEnter -> { submit(); true }
                    else -> false
                }
            }, enabled = canSend && !state.isWaitingForResponse,
            label = { Text(holder.promptText) }, singleLine = true,
            supportingText = { Text(state.terminal.ghostText) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submit() }),
        )
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = { choosingCopy = true },
                modifier = Modifier.heightIn(min = 48.dp).testTag("remote-cli-copy"),
                enabled = state.terminal.outputLines.isNotEmpty(),
            ) { Text(copy) }
            TextButton(controller::onHistoryUp, Modifier.semantics { contentDescription = historyUp }) { Text("↑") }
            TextButton(controller::onHistoryDown, Modifier.semantics { contentDescription = historyDown }) { Text("↓") }
            TextButton({ controller.onTabComplete() }, Modifier.semantics { contentDescription = tabComplete }) { Text("Tab") }
            Button(
                submit,
                Modifier.testTag("remote-cli-submit"),
                enabled = canSend && !state.isWaitingForResponse &&
                    (state.terminal.currentInput.isNotBlank() || state.terminal.tabSelectionIndex != null),
            ) {
                Text(stringResource(L.remoteNodesSettingsOk))
            }
            if (terminal.showCancel) TextButton(controller::onCancel) { Text(stringResource(L.remoteNodesCancel)) }
        }
    }
    if (choosingCopy) AlertDialog(
        onDismissRequest = { choosingCopy = false },
        title = { Text(copy) },
        text = {
            LazyColumn(state = rememberLazyListState(
                initialFirstVisibleItemIndex = outputScroll.firstVisibleItemIndex,
            )) {
                items(state.terminal.outputLines, key = { it.id }) { line ->
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(holder.getResponseBlock(line)))
                            choosingCopy = false
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Text(
                            line.text.ifBlank { stringResource(AppToolsStrings.toolsCliChannelEmpty) },
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton({ choosingCopy = false }) { Text(stringResource(L.remoteNodesCancel)) }
        },
    )
    pending?.let { command ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text(stringResource(L.remoteNodesAuthManagement)) },
            text = { Text(command) },
            dismissButton = { TextButton({ pending = null }) { Text(stringResource(L.remoteNodesCancel)) } },
            confirmButton = { TextButton({
                pending = null
                if (canSend) controller.onConfirmedCommand(command)
            }, Modifier.heightIn(min = 48.dp).testTag("remote-cli-confirm"), enabled = canSend) { Text(stringResource(L.remoteNodesSettingsOk)) } },
        )
    }
}
