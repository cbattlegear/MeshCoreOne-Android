// PortedFrom: MC1/Views/Tools/CLI/CLIToolViewModel+Sessions.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.FormattedStringIds
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftNumbers
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftStrings
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext

private const val NODE_NAME_COLUMN = 20
private const val NODE_KIND_COLUMN = 10
private const val FORGET_LONG = "--forget "
private const val FORGET_SHORT = "-f "

private fun CliToolStateHolder.tools(id: Int): String = text.string(id)

private fun CliToolStateHolder.appendError(line: String) = appendOutput(line, CliOutputType.ERROR)

// MARK: - Sessions

internal fun CliToolStateHolder.handleSessionCommand(args: String) {
    val subcommand = SwiftStrings.lowercased(SwiftStrings.trimmingWhitespaces(args))
    when {
        subcommand.isEmpty() || subcommand == "list" -> showSessionList()
        subcommand == "local" -> switchToLocal()
        else -> switchToSession(SwiftStrings.trimmingWhitespaces(args))
    }
}

internal fun CliToolStateHolder.showSessionList() {
    val current = state.value
    appendOutput(tools(AppToolsStrings.toolsCliSessionListHeader), CliOutputType.RESPONSE)
    val localMarker = if (current.activeSession?.isLocal == true) "*" else " "
    appendOutput(
        "  $localMarker 1. ${current.localDeviceName} (${tools(AppToolsStrings.toolsCliSessionLocal)})",
        CliOutputType.RESPONSE,
    )
    current.remoteSessions.forEachIndexed { index, session ->
        val marker = if (current.activeSession?.id == session.id) "*" else " "
        appendOutput("  $marker ${index + 2}. @${session.name}", CliOutputType.RESPONSE)
    }
}

internal fun CliToolStateHolder.switchToLocal() {
    clearCompletionState()
    val name = state.value.localDeviceName
    setActiveSession(CliSession.local(name))
    appendOutput("${tools(AppToolsStrings.toolsCliSessionSwitched)} $name", CliOutputType.SUCCESS)
}

internal fun CliToolStateHolder.switchToSession(name: String) {
    clearCompletionState()
    val sessions = state.value.remoteSessions
    val number = SwiftNumbers.parseInt(name)
    val session = if (number != null) {
        if (number == 1L) return switchToLocal()
        sessions.getOrNull((number - 2).coerceIn(-1L, Int.MAX_VALUE.toLong()).toInt())
    } else {
        sessions.firstOrNull { localizedCompare.equal(it.name, name) }
    }
    if (session == null) {
        appendError("${tools(AppToolsStrings.toolsCliSessionNotFound)} $name")
        return
    }
    setActiveSession(session)
    appendOutput("${tools(AppToolsStrings.toolsCliSessionSwitched)} @${session.name}", CliOutputType.SUCCESS)
}

// MARK: - Login

internal suspend fun CliToolStateHolder.handleLogin(args: String) {
    if (state.value.activeSession?.isLocal != true) return appendError(tools(AppToolsStrings.toolsCliLoginFromLocalOnly))

    // `--forget`/`-f` must be the first argument.
    val trimmed = SwiftStrings.trimmingWhitespaces(args)
    val forgetPassword = trimmed.startsWith(FORGET_LONG) || trimmed.startsWith(FORGET_SHORT)
    val nodeName = when {
        trimmed.startsWith(FORGET_LONG) -> SwiftStrings.trimmingWhitespaces(trimmed.removePrefix(FORGET_LONG))
        trimmed.startsWith(FORGET_SHORT) -> SwiftStrings.trimmingWhitespaces(trimmed.removePrefix(FORGET_SHORT))
        else -> trimmed
    }
    if (nodeName.isEmpty()) return appendError(tools(AppToolsStrings.toolsCliLoginUsage))

    val store = dataStore
    val radio = radioId
    val remote = remoteNodeService
    if (store == null || radio == null || remote == null) return appendError(tools(AppToolsStrings.toolsCliNotConnected))

    val found: ContactDTO? = try {
        store.fetchContacts(radio).firstOrNull {
            localizedCompare.equal(it.name, nodeName) && (it.isRepeater || it.isRoom)
        }
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        errors.log("Failed to fetch contacts", failure)
        null
    }
    val contact = found ?: return appendError("${tools(AppToolsStrings.toolsCliNodeNotFound)} $nodeName")

    if (forgetPassword) {
        try {
            remote.deletePassword(contact)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            errors.log("Failed to delete stored password", failure) // Swift `try?`
        }
    }

    val storedPassword = remote.retrievePassword(contact)
    if (storedPassword != null) {
        completeLogin(contact, storedPassword)
    } else {
        update { it.copy(pendingLoginContact = contact) }
    }
}

internal suspend fun CliToolStateHolder.completeLogin(contact: ContactDTO, password: String) {
    val radio = radioId
    val remote = remoteNodeService
    if (radio == null || remote == null) return appendError(tools(AppToolsStrings.toolsCliNotConnected))
    val loginJob = currentCoroutineContext().job

    try {
        val remoteSession = remote.createSession(radio, contact)
        if (!currentCoroutineContext().isActive) return

        val success = remote.login(remoteSession, password, contact.outPathLength) { seconds ->
            // The service invokes this from its own scope, not the command's dispatcher or Job.
            withContext(scope.coroutineContext.minusKey(Job)) {
                if (loginJob.isActive && ownsCommand(loginJob)) {
                    startCountdown(seconds.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt())
                }
            }
        }
        if (!currentCoroutineContext().isActive) return
        if (!success) {
            return appendError("${tools(AppToolsStrings.toolsCliLoginFailed)} ${tools(AppToolsStrings.toolsCliLoginFailedAuth)}")
        }

        try {
            remote.storePassword(password, contact.publicKey)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            errors.log("Failed to store password", failure) // Swift `try?`
        }

        val cliSession = CliSession.remote(remoteSession, contact.name, contact.outPathLength)
        update { it.copy(remoteSessions = it.remoteSessions + cliSession, activeSession = cliSession) }
        appendOutput("${tools(AppToolsStrings.toolsCliLoginSuccess)} @${contact.name}", CliOutputType.SUCCESS)
    } catch (failure: CancellationException) {
        throw failure // Already reported by cancelCurrentCommand.
    } catch (failure: Exception) {
        appendError(loginFailureLine(failure))
    } finally {
        if (ownsCommand(loginJob)) stopCountdown()
    }
}

private fun CliToolStateHolder.loginFailureLine(failure: Throwable): String {
    val loginFailed = tools(AppToolsStrings.toolsCliLoginFailed)
    return when (val fault = errors.remoteFault(failure)) {
        CliRemoteFault.PasswordNotFound -> tools(AppToolsStrings.toolsCliPasswordRequired)
        CliRemoteFault.Timeout -> tools(AppToolsStrings.toolsCliTimeout)
        is CliRemoteFault.LoginFailed -> "$loginFailed ${fault.reason}"
        CliRemoteFault.Cancelled -> tools(AppToolsStrings.toolsCliCancelled)
        null -> "$loginFailed ${errors.describe(failure)}"
    }
}

internal suspend fun CliToolStateHolder.handleLogout() {
    val session = state.value.activeSession
    if (session == null || session.isLocal) return appendError(tools(AppToolsStrings.toolsCliNotLoggedIn))

    // Logout errors are ignored by protocol design.
    val key = session.remoteKey
    val remote = remoteNodeService
    if (remote != null && key != null) {
        try {
            remote.logout(key)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            errors.log("Logout failed", failure)
        }
    }

    update { current ->
        current.copy(
            remoteSessions = current.remoteSessions.filterNot { it.id == session.id },
            activeSession = CliSession.local(current.localDeviceName),
        )
    }
    appendOutput(tools(AppToolsStrings.toolsCliLogoutSuccess), CliOutputType.SUCCESS)
}

// MARK: - Remote commands

internal suspend fun CliToolStateHolder.sendRemoteCommand(command: String) {
    val session = state.value.activeSession
    val key = session?.remoteKey
    val service = repeaterAdminService
    if (session == null || session.isLocal || key == null || service == null) {
        return appendError(tools(AppToolsStrings.toolsCliNotLoggedIn))
    }

    // Reboot never replies, so a timeout is reported as success.
    if (SwiftStrings.lowercased(command).startsWith("reboot")) {
        try {
            service.sendRawCommand(key, command, CliTimeouts.FIRE_AND_FORGET_CLI)
            appendOutput(tools(AppToolsStrings.toolsCliRebootSent), CliOutputType.SUCCESS)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            if (errors.remoteFault(failure) == CliRemoteFault.Timeout) {
                appendOutput(tools(AppToolsStrings.toolsCliRebootSent), CliOutputType.SUCCESS)
            } else {
                appendError(errors.describe(failure))
            }
        }
        return
    }

    try {
        val response = service.sendRawCommand(key, command, CliTimeouts.DEFAULT_CLI)
        if (!currentCoroutineContext().isActive) return
        appendOutput(response, CliOutputType.RESPONSE)
    } catch (failure: CancellationException) {
        throw failure // Already reported by cancelCurrentCommand.
    } catch (failure: Exception) {
        val timedOut = errors.remoteFault(failure) == CliRemoteFault.Timeout
        appendError(if (timedOut) tools(AppToolsStrings.toolsCliCommandTimeout) else errors.describe(failure))
    }
}

// MARK: - Local listing commands

internal suspend fun CliToolStateHolder.handleNodesCommand() {
    val store = dataStore
    val radio = radioId
    if (store == null || radio == null) return appendError(tools(AppToolsStrings.toolsCliNotConnected))

    val contacts = try {
        store.fetchContacts(radio)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        errors.log("Failed to fetch contacts", failure)
        return appendOutput(tools(AppToolsStrings.toolsCliNoNodes), CliOutputType.RESPONSE)
    }

    val nodes = contacts
        .filter { it.isRepeater || it.isRoom }
        .sortedWith(compareBy<ContactDTO> { if (it.isRepeater) 0 else 1 }.thenBy(localizedCompare.comparator) { it.name })
    if (nodes.isEmpty()) return appendOutput(tools(AppToolsStrings.toolsCliNoNodes), CliOutputType.RESPONSE)

    appendOutput(text.format(FormattedStringIds.CLI_NODES_HEADER, nodes.size.toLong()), CliOutputType.RESPONSE)
    nodes.forEach { node ->
        val typeLabel = tools(
            if (node.isRepeater) AppContactsStrings.contactsNodeKindRepeater else AppContactsStrings.contactsNodeKindRoom,
        )
        val padded = padded(node.name, NODE_NAME_COLUMN)
        val label = padded(typeLabel, NODE_KIND_COLUMN)
        appendOutput("  $padded $label${formatRoute(node)}", CliOutputType.RESPONSE)
    }
}

internal suspend fun CliToolStateHolder.handleChannelsCommand() {
    val store = dataStore
    val radio = radioId
    if (store == null || radio == null) return appendError(tools(AppToolsStrings.toolsCliNotConnected))

    val channels: List<ChannelDTO> = try {
        store.fetchChannels(radio)
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        errors.log("Failed to fetch channels", failure)
        return appendOutput(tools(AppToolsStrings.toolsCliNoChannels), CliOutputType.RESPONSE)
    }
    if (channels.isEmpty()) return appendOutput(tools(AppToolsStrings.toolsCliNoChannels), CliOutputType.RESPONSE)

    val sorted = channels.sortedBy { it.index }
    appendOutput(text.format(FormattedStringIds.CLI_CHANNELS_HEADER, sorted.size.toLong()), CliOutputType.RESPONSE)
    sorted.forEach { channel ->
        val name = channel.name.ifEmpty { "(${tools(AppToolsStrings.toolsCliChannelEmpty)})" }
        appendOutput("  [${channel.index}] $name", CliOutputType.RESPONSE)
    }
}

private fun CliToolStateHolder.formatRoute(contact: ContactDTO): String = when {
    contact.isFloodRouted -> tools(AppContactsStrings.contactsRouteFlood)
    contact.pathHopCount == 0L -> tools(AppContactsStrings.contactsRouteDirect)
    else -> text.format(FormattedStringIds.CONTACTS_ROUTE_HOPS, contact.pathHopCount.toInt())
}

/** `NSString.padding(toLength:withPad:startingAt:)`: pads or truncates to [length] UTF-16 units. */
internal fun padded(value: String, length: Int): String =
    if (value.length >= length) value.substring(0, length) else value.padEnd(length, ' ')
