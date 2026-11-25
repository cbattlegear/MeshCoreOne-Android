// PortedFrom: MC1/Views/Tools/CLI/CLILocalCommandParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftNumbers
import com.meshcoreone.android.feature.tools.diagnostics.text.SwiftStrings

/** Outcomes of parsing a local-session line: fall through, a validated command, or a usage/error. */
sealed interface CliLocalParseResult {
    data object NotLocal : CliLocalParseResult
    data class Command(val command: CliLocalCommand) : CliLocalParseResult
    data class Invalid(val error: CliLocalParseError) : CliLocalParseResult
}

/** A recognized-but-rejected local command; rendering (and L10n) is the executor's job. */
sealed interface CliLocalParseError {
    data class BadArguments(val usage: CliLocalUsage) : CliLocalParseError
    data object ValueOutOfRange : CliLocalParseError
    data object InvalidCustomVarToken : CliLocalParseError
}

/** Which usage line to render for a [CliLocalParseError.BadArguments] result. */
enum class CliLocalUsage { GET, SET, SET_RADIO }

/** Parses a local-session input line into a typed command or error. Pure and dependency-free. */
object CliLocalCommandParser {
    private val frequencyRangeMHz = 150.0..2500.0
    private val bandwidthRangeKHz = 7.0..500.0
    private val spreadingFactorRange = 5u..12u
    private val codingRateRange = 5u..8u
    private val latitudeRange = -90.0..90.0
    private val longitudeRange = -180.0..180.0
    private val multiAcksRange = 0u..1u
    private val pathHashModeRange = 0u..2u

    private val customVarReservedInKey = setOf(':', ',')
    private val customVarReservedInValue = setOf(',')
    private const val CUSTOM_VAR_DUMP_KEY = "custom"
    private const val CUSTOM_VAR_ESCAPE_PREFIX = "_"

    /** Firmware's per-pair enumeration budget for `CMD_GET_CUSTOM_VARS` (UTF-8 bytes). */
    private const val MAX_CUSTOM_VAR_PAIR_BYTES = 139

    fun parse(line: String): CliLocalParseResult {
        val trimmed = SwiftStrings.trimmingWhitespaces(line)
        if (trimmed.isEmpty()) return CliLocalParseResult.NotLocal

        val parts = SwiftStrings.split(trimmed, ' ', maxSplits = 1)
        val command = SwiftStrings.lowercased(parts[0])
        val rest = parts.getOrElse(1) { "" }

        return when (command) {
            "clock" -> {
                val sub = SwiftStrings.lowercased(SwiftStrings.trimmingWhitespaces(rest))
                command(if (sub == "sync") CliLocalCommand.ClockSync else CliLocalCommand.Clock)
            }
            "ver" -> command(CliLocalCommand.Ver)
            "board" -> command(CliLocalCommand.Board)
            "advert", "advert.zerohop" -> command(CliLocalCommand.Advert(flood = false))
            "floodadv" -> command(CliLocalCommand.Advert(flood = true))
            "reboot" -> command(CliLocalCommand.Reboot)
            "get" -> parseGet(rest)
            "set" -> parseSet(rest)
            else -> CliLocalParseResult.NotLocal
        }
    }

    private fun command(command: CliLocalCommand) = CliLocalParseResult.Command(command)

    private fun invalid(error: CliLocalParseError) = CliLocalParseResult.Invalid(error)

    private fun badArguments(usage: CliLocalUsage) = invalid(CliLocalParseError.BadArguments(usage))

    private fun isValidCustomVarKey(key: String): Boolean =
        key.isNotEmpty() && key.none { it in customVarReservedInKey }

    private fun isValidCustomVarValue(value: String): Boolean = value.none { it in customVarReservedInValue }

    private fun parseGet(rest: String): CliLocalParseResult {
        val rawToken = firstToken(rest)
        if (rawToken.isEmpty()) return badArguments(CliLocalUsage.GET)

        val lowered = SwiftStrings.lowercased(rawToken)
        if (lowered == CUSTOM_VAR_DUMP_KEY) return command(CliLocalCommand.GetCustomVars)
        CliLocalKey.fromRawValue(lowered)?.let { return command(CliLocalCommand.GetKey(it)) }

        val key = strippedCustomVarKey(rawToken)
        if (key.isEmpty()) return badArguments(CliLocalUsage.GET)
        if (!isValidCustomVarKey(key)) return invalid(CliLocalParseError.InvalidCustomVarToken)
        return command(CliLocalCommand.GetCustomVar(key))
    }

    private fun parseSet(rest: String): CliLocalParseResult {
        val trimmed = SwiftStrings.trimmingWhitespaces(rest)
        if (trimmed.isEmpty()) return badArguments(CliLocalUsage.SET)

        val parts = SwiftStrings.split(trimmed, ' ', maxSplits = 1)
        val keyToken = parts[0]
        val value = parts.getOrNull(1)?.let(SwiftStrings::trimmingWhitespaces) ?: ""

        val key = CliLocalKey.fromRawValue(SwiftStrings.lowercased(keyToken))
            ?: return parseCustomVarSet(keyToken, value)

        return when (key) {
            CliLocalKey.NAME ->
                if (value.isEmpty()) badArguments(CliLocalUsage.SET) else command(CliLocalCommand.SetName(value))
            CliLocalKey.LAT -> parseDouble(value, latitudeRange)
                ?.let { command(CliLocalCommand.SetLatitude(it)) } ?: invalidNumber(value)
            CliLocalKey.LON -> parseDouble(value, longitudeRange)
                ?.let { command(CliLocalCommand.SetLongitude(it)) } ?: invalidNumber(value)
            CliLocalKey.TX -> parseTxPower(value)
            CliLocalKey.RADIO -> parseSetRadio(value)
            CliLocalKey.FREQ -> parseDouble(value, frequencyRangeMHz)
                ?.let { command(CliLocalCommand.SetFrequency(it)) } ?: invalidNumber(value)
            CliLocalKey.MULTI_ACKS -> parseUInt8(value, multiAcksRange)
                ?.let { command(CliLocalCommand.SetMultiAcks(it)) } ?: invalidNumber(value)
            CliLocalKey.PATH_HASH_MODE -> parseUInt8(value, pathHashModeRange)
                ?.let { command(CliLocalCommand.SetPathHashMode(it)) } ?: invalidNumber(value)
            CliLocalKey.PUBLIC_KEY, CliLocalKey.BAT -> badArguments(CliLocalUsage.SET)
        }
    }

    private fun parseTxPower(value: String): CliLocalParseResult {
        val raw = SwiftNumbers.parseInt(value) ?: return badArguments(CliLocalUsage.SET)
        if (raw < Byte.MIN_VALUE || raw > Byte.MAX_VALUE) return invalid(CliLocalParseError.ValueOutOfRange)
        return command(CliLocalCommand.SetTxPower(raw.toByte()))
    }

    /** Routes a `set` whose key is not typed to a companion custom var, stripping one leading `_`. */
    private fun parseCustomVarSet(keyToken: String, value: String): CliLocalParseResult {
        val key = strippedCustomVarKey(keyToken)
        if (key.isEmpty() || value.isEmpty()) return badArguments(CliLocalUsage.SET)
        val pairBytes = "$key:$value".toByteArray(Charsets.UTF_8).size
        if (!isValidCustomVarKey(key) || !isValidCustomVarValue(value) || pairBytes > MAX_CUSTOM_VAR_PAIR_BYTES) {
            return invalid(CliLocalParseError.InvalidCustomVarToken)
        }
        return command(CliLocalCommand.SetCustomVar(key, value))
    }

    private fun parseSetRadio(value: String): CliLocalParseResult {
        val fields = SwiftStrings.split(value, ',').map(SwiftStrings::trimmingWhitespaces)
        if (fields.size != 4) return badArguments(CliLocalUsage.SET_RADIO)
        val frequency = SwiftNumbers.parseDouble(fields[0])
        val bandwidth = SwiftNumbers.parseDouble(fields[1])
        val spreadingFactor = SwiftNumbers.parseUInt8(fields[2])
        val codingRate = SwiftNumbers.parseUInt8(fields[3])
        if (frequency == null || bandwidth == null || spreadingFactor == null || codingRate == null) {
            return badArguments(CliLocalUsage.SET_RADIO)
        }
        if (frequency !in frequencyRangeMHz || bandwidth !in bandwidthRangeKHz ||
            spreadingFactor.toUInt() !in spreadingFactorRange || codingRate.toUInt() !in codingRateRange
        ) {
            return invalid(CliLocalParseError.ValueOutOfRange)
        }
        return command(CliLocalCommand.SetRadio(frequency, bandwidth, spreadingFactor, codingRate))
    }

    private fun firstToken(value: String): String = SwiftStrings.split(value, ' ').firstOrNull() ?: ""

    private fun strippedCustomVarKey(token: String): String = token.removePrefix(CUSTOM_VAR_ESCAPE_PREFIX)

    private fun parseDouble(value: String, range: ClosedFloatingPointRange<Double>): Double? =
        SwiftNumbers.parseDouble(value)?.takeIf { it in range }

    private fun parseUInt8(value: String, range: UIntRange): UByte? =
        SwiftNumbers.parseUInt8(value)?.takeIf { it.toUInt() in range }

    /** Distinguishes non-numeric input (bad arguments) from an out-of-range number. */
    private fun invalidNumber(value: String): CliLocalParseResult =
        if (SwiftNumbers.parseDouble(value) == null) badArguments(CliLocalUsage.SET)
        else invalid(CliLocalParseError.ValueOutOfRange)
}
