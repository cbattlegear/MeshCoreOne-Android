// PortedFrom: MC1/Views/Tools/CLI/CLIToolViewModel+LocalCommands.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.l10n.generated.AppToolsStrings
import com.meshcoreone.android.core.protocol.model.ErrorCode
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * Executes a parsed local-session radio command against the connected radio, appending
 * firmware-parity output. Every `set` routes through a `*Verified` setter.
 */
internal suspend fun CliToolStateHolder.executeLocal(command: CliLocalCommand) {
    when (command) {
        CliLocalCommand.Clock -> runClock(sync = false)
        CliLocalCommand.ClockSync -> runClock(sync = true)
        CliLocalCommand.Ver -> withSettings {
            val capabilities = it.queryDevice()
            output("${capabilities.version} (Build: ${capabilities.firmwareBuild})")
        }
        CliLocalCommand.Board -> withSettings { output(it.queryDevice().model) }
        is CliLocalCommand.Advert -> runAdvert(command.flood)
        CliLocalCommand.Reboot -> withSettings {
            it.reboot()
            output(LocalCommandOutput.OK_REBOOTING)
        }
        is CliLocalCommand.GetKey -> runGet(command.key)
        is CliLocalCommand.SetName -> runSetName(command.name)
        is CliLocalCommand.SetLatitude -> withSettings {
            val current = it.getSelfInfo()
            it.setManualLocationVerified(command.latitude, current.longitude)
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetLongitude -> withSettings {
            val current = it.getSelfInfo()
            it.setManualLocationVerified(current.latitude, command.longitude)
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetTxPower -> withSettings {
            it.setTxPowerVerified(command.power)
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetRadio -> withSettings {
            it.setRadioParamsVerified(
                LocalCommandOutput.freqKHz(command.frequencyMHz), LocalCommandOutput.bandwidthHz(command.bandwidthKHz),
                command.spreadingFactor, command.codingRate,
            )
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetFrequency -> withSettings {
            val current = it.getSelfInfo()
            it.setRadioParamsVerified(
                LocalCommandOutput.freqKHz(command.frequencyMHz), LocalCommandOutput.bandwidthHz(current.radioBandwidth),
                current.radioSpreadingFactor, current.radioCodingRate,
            )
            output(LocalCommandOutput.OK)
        }
        is CliLocalCommand.SetMultiAcks -> runSetMultiAcks(command.value)
        is CliLocalCommand.SetPathHashMode -> withSettings {
            it.setPathHashModeVerified(command.mode)
            output(LocalCommandOutput.OK)
        }
        CliLocalCommand.GetCustomVars -> runGetCustomVars(key = null)
        is CliLocalCommand.GetCustomVar -> runGetCustomVars(command.key)
        is CliLocalCommand.SetCustomVar -> runSetCustomVar(command.key, command.value)
    }
}

/** Renders a parser error as a usage or error output line. */
internal fun CliToolStateHolder.renderParseError(error: CliLocalParseError) {
    val id = when (error) {
        is CliLocalParseError.BadArguments -> when (error.usage) {
            CliLocalUsage.GET -> AppToolsStrings.toolsCliUsageGet
            CliLocalUsage.SET -> AppToolsStrings.toolsCliUsageSet
            CliLocalUsage.SET_RADIO -> AppToolsStrings.toolsCliUsageSetRadio
        }
        CliLocalParseError.ValueOutOfRange -> AppToolsStrings.toolsCliInvalidValue
        CliLocalParseError.InvalidCustomVarToken -> AppToolsStrings.toolsCliInvalidCustomVarToken
    }
    appendOutput(text.string(id), CliOutputType.ERROR)
}

private suspend fun CliToolStateHolder.runClock(sync: Boolean) = withSettings { settings ->
    if (sync) {
        val now = clock.now()
        settings.setTime(now)
        output(LocalCommandOutput.clockSet(now))
    } else {
        output(LocalCommandOutput.clock(settings.getTime()))
    }
}

private suspend fun CliToolStateHolder.runGet(key: CliLocalKey) = withSettings { settings ->
    val value = when (key) {
        CliLocalKey.NAME -> settings.getSelfInfo().name
        CliLocalKey.LAT -> LocalCommandOutput.coordinate(settings.getSelfInfo().latitude)
        CliLocalKey.LON -> LocalCommandOutput.coordinate(settings.getSelfInfo().longitude)
        CliLocalKey.TX -> settings.getSelfInfo().txPower.toString()
        CliLocalKey.RADIO -> LocalCommandOutput.radio(settings.getSelfInfo())
        CliLocalKey.FREQ -> LocalCommandOutput.decimal(settings.getSelfInfo().radioFrequency)
        CliLocalKey.PUBLIC_KEY -> LocalCommandOutput.hex(settings.getSelfInfo().publicKey)
        CliLocalKey.MULTI_ACKS -> settings.getSelfInfo().multiAcks.toString()
        CliLocalKey.PATH_HASH_MODE -> settings.queryDevice().pathHashMode.toString()
        CliLocalKey.BAT -> "${settings.getBattery().level} mV"
    }
    output(LocalCommandOutput.value(value))
}

private suspend fun CliToolStateHolder.runSetName(name: String) = withSettings { settings ->
    val selfInfo = settings.setNodeNameVerified(name)
    update { current ->
        val session = current.activeSession
        current.copy(
            localDeviceName = selfInfo.name,
            activeSession = if (session?.isLocal == true) CliSession.local(selfInfo.name) else session,
        )
    }
    output(LocalCommandOutput.OK)
}

private suspend fun CliToolStateHolder.runSetMultiAcks(value: UByte) {
    val settings = settingsService ?: return notConnected()
    val device = connectedDevice ?: return notConnected()
    guarded {
        settings.setOtherParamsVerified(device, value)
        output(LocalCommandOutput.OK)
    }
}

/** Fetches all custom vars in one round trip; a key is looked up client-side so it never hits the wire. */
private suspend fun CliToolStateHolder.runGetCustomVars(key: String?) = withSettings { settings ->
    val vars = settings.getCustomVars()
    completionEngine.updateCustomVarKeys(vars.keys.toList())
    if (key == null) {
        output(LocalCommandOutput.customVarList(vars))
    } else {
        output(vars[key]?.let(LocalCommandOutput::value) ?: LocalCommandOutput.unknownVar(key))
    }
}

private suspend fun CliToolStateHolder.runSetCustomVar(key: String, value: String) = withSettings { settings ->
    try {
        settings.setCustomVar(key, value)
        output(LocalCommandOutput.customVarSet(key, value))
    } catch (failure: SettingsServiceException) {
        val sessionError = failure.error as? SettingsServiceError.SessionError
        if (sessionError?.error?.deviceErrorCode != ErrorCode.ILLEGAL_ARGUMENT) throw failure
        output(LocalCommandOutput.UNKNOWN_CUSTOM_VAR)
    }
}

private suspend fun CliToolStateHolder.runAdvert(flood: Boolean) = guarded {
    sendSelfAdvert(flood)
    output(if (flood) LocalCommandOutput.FLOOD_ADVERT else LocalCommandOutput.ZERO_HOP_ADVERT)
}

// MARK: - Output helpers

private suspend fun CliToolStateHolder.withSettings(block: suspend (CliSettingsPort) -> Unit) {
    val settings = settingsService ?: return notConnected()
    guarded { block(settings) }
}

/** Runs [block], rendering a failure as an error line; cancellation propagates. */
private suspend fun CliToolStateHolder.guarded(block: suspend () -> Unit) {
    try {
        block()
    } catch (failure: CancellationException) {
        throw failure
    } catch (failure: Exception) {
        renderError(failure)
    }
}

private fun CliToolStateHolder.notConnected() =
    appendOutput(text.string(AppToolsStrings.toolsCliNotConnected), CliOutputType.ERROR)

private suspend fun CliToolStateHolder.output(line: String) {
    if (!currentCoroutineContext().isActive) return
    appendOutput(line, CliOutputType.RESPONSE)
}

private suspend fun CliToolStateHolder.renderError(error: Throwable) {
    if (!currentCoroutineContext().isActive) return
    appendOutput(errors.describe(error), CliOutputType.ERROR)
}
