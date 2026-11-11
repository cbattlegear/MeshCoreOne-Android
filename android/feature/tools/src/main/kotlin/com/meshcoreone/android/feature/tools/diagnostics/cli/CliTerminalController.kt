// PortedFrom: MC1/Views/Tools/CLI/CLITerminalView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/CLI/CLIToolView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/NodeCLIView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/CLI/HiddenTextView.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Tools/CLI/CLIInputAccessoryView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.DiagnosticsClock
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What either CLI holder exposes to the shared terminal (the Swift `CLITerminalView` callbacks). */
interface CliTerminalHost {
    val currentInput: String
    val ghostText: String
    val tabSelectionIndex: Int?
    val isWaitingForResponse: Boolean
    fun updateInput(input: String)
    fun executeCommand(command: String)
    fun applySelectedSuggestion(): Boolean
    fun historyUp()
    fun historyDown()
    fun acceptGhostText()
    fun tabComplete(): List<String>?
    fun cancelCurrentCommand()
    fun clearTabState()
    fun updateGhostText(cursorAtEnd: Boolean)
    fun paste(text: String, cursorPosition: Int)
}

/** Hardware keys the terminal handles itself (Swift `onKeyPress` / `UIKeyCommand`). */
enum class CliTerminalKey { UP, DOWN, TAB, ESCAPE, K }

/** View-local terminal state: cursor (UTF-16 offset), keyboard focus and the delayed cancel button. */
data class CliTerminalUiState(
    val cursorPosition: Int = 0,
    val isKeyboardFocused: Boolean = false,
    val showCancel: Boolean = false,
)

/**
 * Keyboard, cursor and accessory-bar behavior of the shared terminal, kept out of Compose so it is
 * unit-testable. Cursor offsets are UTF-16 (Compose `TextFieldValue.selection`) rather than Swift
 * `Character` counts, and never split a surrogate pair.
 */
class CliTerminalController(
    private val host: CliTerminalHost,
    private val scope: CoroutineScope,
    private val clock: DiagnosticsClock = DiagnosticsClock.SYSTEM,
    private val sessionsAction: (() -> Unit)? = null,
) {
    companion object {
        /** The cancel button enables this long after a command starts waiting. */
        val CANCEL_REVEAL_DELAY = 150.milliseconds
    }

    private val mutableState = MutableStateFlow(CliTerminalUiState())
    val state: StateFlow<CliTerminalUiState> = mutableState.asStateFlow()
    private var cancelRevealJob: Job? = null

    val showsSessionsButton: Boolean get() = sessionsAction != null
    val cursorAtEnd: Boolean get() = state.value.cursorPosition >= host.currentInput.length
    val textBeforeCursor: String get() = host.currentInput.substring(0, clampedCursor())
    val textAfterCursor: String get() = host.currentInput.substring(clampedCursor())

    private fun clampedCursor(): Int = state.value.cursorPosition.coerceIn(0, host.currentInput.length)

    private fun setCursor(position: Int) = mutableState.update { it.copy(cursorPosition = position) }

    private fun moveCursorToEnd() = setCursor(host.currentInput.length)

    /** Focuses the keyboard and restores the cursor to the end of any surviving input. */
    fun onAppear() {
        mutableState.update { it.copy(isKeyboardFocused = true) }
        moveCursorToEnd()
    }

    /** Clears the focus request so the accessory bar cannot persist across navigation. */
    fun onDisappear() = mutableState.update { it.copy(isKeyboardFocused = false) }

    fun onTapTerminal() = mutableState.update { it.copy(isKeyboardFocused = true) }

    fun onFocusChanged(focused: Boolean) = mutableState.update { it.copy(isKeyboardFocused = focused) }

    /**
     * Text field edits. A newline (typed or pasted with the command) submits the text before it,
     * while any tab selection is still valid; otherwise the input and cursor follow the field.
     */
    fun onTextChanged(text: String, selection: Int) = reacting {
        val newline = text.indexOf('\n')
        if (newline >= 0) {
            setInput(text.substring(0, newline))
            submit()
            moveCursorToEnd()
        } else {
            setInput(text)
            setCursor(selection.coerceIn(0, text.length))
        }
    }

    fun onSelectionChanged(selection: Int) = reacting { setCursor(selection.coerceIn(0, host.currentInput.length)) }

    private fun setInput(input: String) {
        if (input != host.currentInput) host.updateInput(input)
    }

    fun onSubmit() = reacting { submit() }

    /** Keeps completion acceptance ahead of a native confirmation or other command boundary. */
    fun onSubmit(onCommand: (String) -> Unit) = reacting { submit(onCommand) }

    private fun submit(onCommand: (String) -> Unit = host::executeCommand) {
        if (host.applySelectedSuggestion()) moveCursorToEnd() else onCommand(host.currentInput)
    }

    fun onHistoryUp() = reacting {
        host.historyUp()
        moveCursorToEnd()
    }

    fun onHistoryDown() = reacting {
        host.historyDown()
        moveCursorToEnd()
    }

    fun onRightArrowAtEnd() = reacting {
        if (host.ghostText.isNotEmpty()) {
            host.acceptGhostText()
            moveCursorToEnd()
        }
    }

    fun onTabComplete() = reacting {
        host.tabComplete()
        moveCursorToEnd()
    }

    fun onMoveLeft() = reacting {
        val cursor = clampedCursor()
        val input = host.currentInput
        if (cursor > 0) {
            val pair = cursor >= 2 && input[cursor - 1].isLowSurrogate() && input[cursor - 2].isHighSurrogate()
            setCursor(cursor - if (pair) 2 else 1)
        }
    }

    fun onMoveRight() = reacting {
        val input = host.currentInput
        val cursor = state.value.cursorPosition
        if (host.ghostText.isNotEmpty() && cursor >= input.length) {
            host.acceptGhostText()
            moveCursorToEnd()
        } else if (cursor < input.length) {
            val pair = cursor + 1 < input.length && input[cursor].isHighSurrogate() && input[cursor + 1].isLowSurrogate()
            setCursor(cursor + if (pair) 2 else 1)
        }
    }

    /** Pastes [clipboard] (read by the UI layer; null when empty) at the cursor and moves past it. */
    fun onPaste(clipboard: String?) = reacting {
        if (clipboard != null) {
            val cursor = state.value.cursorPosition
            host.paste(clipboard, cursor)
            setCursor(minOf(cursor + clipboard.length, host.currentInput.length))
        }
    }

    fun onSessions() = reacting { sessionsAction?.invoke() }

    fun onCancel() = reacting { host.cancelCurrentCommand() }

    fun onDismiss() = mutableState.update { it.copy(isKeyboardFocused = false) }

    fun onClear() = reacting { host.executeCommand("clear") }

    /** Hardware keys; returns whether the key was handled. */
    fun onKey(key: CliTerminalKey, commandModifier: Boolean = false): Boolean = when (key) {
        CliTerminalKey.UP -> true.also { onHistoryUp() }
        CliTerminalKey.DOWN -> true.also { onHistoryDown() }
        CliTerminalKey.TAB -> true.also { onTabComplete() }
        CliTerminalKey.ESCAPE -> true.also { onEscape() }
        CliTerminalKey.K -> commandModifier.also { if (it) onClear() }
    }

    private fun onEscape() = reacting {
        when {
            host.tabSelectionIndex != null -> host.clearTabState()
            host.isWaitingForResponse -> host.cancelCurrentCommand()
            else -> onDismiss()
        }
    }

    /**
     * SwiftUI `onChange` parity: an input change refreshes ghost text and clears tab state; a
     * cursor-only change refreshes ghost text.
     */
    private inline fun reacting(action: () -> Unit) {
        val input = host.currentInput
        val cursor = state.value.cursorPosition
        action()
        if (host.currentInput != input) {
            host.updateGhostText(cursorAtEnd)
            host.clearTabState()
        } else if (state.value.cursorPosition != cursor) {
            host.updateGhostText(cursorAtEnd)
        }
    }

    /** Call when the holder's waiting flag changes: cancel enables after [CANCEL_REVEAL_DELAY]. */
    fun onWaitingChanged(isWaiting: Boolean) {
        cancelRevealJob?.cancel()
        if (!isWaiting) {
            cancelRevealJob = null
            mutableState.update { it.copy(showCancel = false) }
            return
        }
        cancelRevealJob = scope.launch {
            clock.sleep(CANCEL_REVEAL_DELAY)
            mutableState.update { it.copy(showCancel = true) }
        }
    }
}
