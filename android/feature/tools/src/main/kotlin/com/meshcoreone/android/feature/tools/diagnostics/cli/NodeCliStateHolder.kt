// PortedFrom: MC1/Views/RemoteNodes/NodeCLIViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.FormattedStringIds
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsClock
import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsText
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftStrings
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Immutable snapshot of a single managed node's CLI. */
data class NodeCliState(
    val terminal: CliTerminalState = CliTerminalState(),
    val isWaitingForResponse: Boolean = false,
    val sessionName: String = "",
)

/** Sends one raw CLI line to the managed node with an explicit timeout (Swift `sendRawCommand` closure). */
fun interface NodeCliSender {
    suspend fun send(command: String, timeout: Duration): String
}

/**
 * Plain state holder for the node CLI (Swift `NodeCLIViewModel`): always a remote session, so
 * completion uses the remote vocabulary without the app-CLI session commands. Confined to
 * [scope]'s single-threaded dispatcher.
 */
class NodeCliStateHolder(
    private val scope: CoroutineScope,
    private val text: DiagnosticsText,
    private val clock: DiagnosticsClock = DiagnosticsClock.SYSTEM,
    private val errors: CliErrorPresentation = CliErrorPresentation(),
) : CliTerminalHost {
    val completionEngine = CliCompletionEngine()
    private val mutableState = MutableStateFlow(NodeCliState())
    val state: StateFlow<NodeCliState> = mutableState.asStateFlow()
    private var sender: NodeCliSender? = null
    private var currentCommandJob: Job? = null
    private var hasConfigured = false

    override val currentInput: String get() = state.value.terminal.currentInput
    override val ghostText: String get() = state.value.terminal.ghostText
    override val tabSelectionIndex: Int? get() = state.value.terminal.tabSelectionIndex
    override val isWaitingForResponse: Boolean get() = state.value.isWaitingForResponse

    val promptText: String get() = prompt(state.value)

    private fun prompt(state: NodeCliState): String =
        if (state.isWaitingForResponse) "" else "@${state.sessionName}${text.string(AppToolsStrings.toolsCliPromptSuffix)} "

    private fun updateTerminal(transform: (CliTerminalState) -> CliTerminalState) =
        mutableState.update { it.copy(terminal = transform(it.terminal)) }

    private fun setWaiting(waiting: Boolean) = mutableState.update { it.copy(isWaitingForResponse = waiting) }

    /** Idempotent: the connection banner is appended only on the first call. */
    fun configure(sessionName: String, sender: NodeCliSender) {
        mutableState.update { it.copy(sessionName = sessionName) }
        this.sender = sender
        if (hasConfigured) return
        hasConfigured = true
        appendOutput(text.format(FormattedStringIds.NODE_CLI_BANNER_CONNECTED, sessionName), CliOutputType.RESPONSE)
        appendOutput(text.string(AppRemoteNodesStrings.remoteNodesNodeCliBannerHint), CliOutputType.RESPONSE)
        appendOutput("", CliOutputType.RESPONSE)
    }

    override fun updateInput(input: String) = updateTerminal { it.copy(currentInput = input) }

    override fun executeCommand(command: String) {
        val trimmed = SwiftStrings.trimmingWhitespacesAndNewlines(command)
        if (state.value.isWaitingForResponse) return
        val promptPrefix = SwiftStrings.trimmingWhitespaces(promptText)

        if (trimmed.isEmpty()) {
            appendOutput(promptPrefix, CliOutputType.COMMAND)
            return
        }

        updateTerminal { it.addingToHistory(trimmed) }
        appendOutput("$promptPrefix $trimmed", CliOutputType.COMMAND)

        val parts = SwiftStrings.split(trimmed, ' ', maxSplits = 1)
        val cmd = SwiftStrings.lowercased(parts[0])
        val args = parts.getOrElse(1) { "" }
        currentCommandJob = scope.launch { handleCommand(cmd, args, trimmed) }
        updateTerminal { it.copy(currentInput = "") }
    }

    override fun cancelCurrentCommand() {
        currentCommandJob?.cancel()
        currentCommandJob = null
        if (state.value.isWaitingForResponse) {
            setWaiting(false)
            appendOutput(text.string(AppRemoteNodesStrings.remoteNodesNodeCliCancelled), CliOutputType.ERROR)
        }
    }

    private suspend fun handleCommand(cmd: String, args: String, raw: String) {
        when {
            cmd == "help" -> showHelp()
            cmd == "clear" && args.isEmpty() -> updateTerminal { it.clearingOutput() }
            else -> sendCommand(raw)
        }
    }

    private suspend fun sendCommand(command: String) {
        val send = sender ?: return
        // Reboot does not reply; success and timeout both read as success.
        val normalized = SwiftStrings.lowercased(command)
        val isReboot = normalized == "reboot" || normalized == "reboot now"
        setWaiting(true)
        try {
            val response = send.send(command, if (isReboot) CliTimeouts.FIRE_AND_FORGET_CLI else CliTimeouts.DEFAULT_CLI)
            if (isReboot) {
                appendOutput(text.string(AppRemoteNodesStrings.remoteNodesNodeCliRebootSent), CliOutputType.SUCCESS)
            } else if (currentCoroutineContext().isActive) {
                appendOutput(response, CliOutputType.RESPONSE)
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (isReboot && errors.remoteFault(failure) == CliRemoteFault.Timeout) {
                appendOutput(text.string(AppRemoteNodesStrings.remoteNodesNodeCliRebootSent), CliOutputType.SUCCESS)
            } else {
                appendOutput(errors.describe(failure), CliOutputType.ERROR)
            }
        } finally {
            setWaiting(false)
        }
    }

    private fun showHelp() {
        listOf(
            AppRemoteNodesStrings.remoteNodesNodeCliHelpHeader, AppRemoteNodesStrings.remoteNodesNodeCliHelpHelp,
            AppRemoteNodesStrings.remoteNodesNodeCliHelpClear, AppRemoteNodesStrings.remoteNodesNodeCliHelpClearStats,
            AppRemoteNodesStrings.remoteNodesNodeCliHelpReboot, AppRemoteNodesStrings.remoteNodesNodeCliHelpPassthrough,
        ).forEach { appendOutput(text.string(it), CliOutputType.RESPONSE) }
    }

    override fun historyUp() = updateTerminal { it.historyUp() }

    override fun historyDown() = updateTerminal { it.historyDown() }

    fun appendOutput(line: String, type: CliOutputType) = updateTerminal { it.appendingOutput(line, type, clock.now()) }

    /** Twin of [CliToolStateHolder.getResponseBlock]; both use [CliTerminalState.responseBlock]. */
    fun getResponseBlock(line: CliOutputLine): String = state.value.terminal.responseBlock(line)

    override fun paste(text: String, cursorPosition: Int) = updateTerminal { it.pasting(text, cursorPosition) }

    override fun updateGhostText(cursorAtEnd: Boolean) {
        val input = state.value.terminal.currentInput
        if (input.isEmpty() || !cursorAtEnd) {
            updateTerminal { it.clearingGhostText() }
            return
        }
        updateTerminal { it.withGhostText(suggestions(input).firstOrNull()) }
    }

    override fun acceptGhostText() = updateTerminal { it.acceptingGhostText() }

    override fun tabComplete(): List<String>? {
        val (next, suggestions) = state.value.terminal.tabCompleting(::suggestions)
        updateTerminal { next }
        return suggestions
    }

    override fun applySelectedSuggestion(): Boolean {
        val (next, applied) = state.value.terminal.applyingSelectedSuggestion()
        updateTerminal { next }
        return applied
    }

    fun applySuggestion(suggestion: String): Boolean {
        val (next, applied) = state.value.terminal.applyingSuggestion(suggestion)
        updateTerminal { next }
        return applied
    }

    override fun clearTabState() = updateTerminal { it.clearingTabState() }

    private fun suggestions(input: String): List<String> =
        completionEngine.completions(input, isLocal = false, includeSessionCommands = false)
}
