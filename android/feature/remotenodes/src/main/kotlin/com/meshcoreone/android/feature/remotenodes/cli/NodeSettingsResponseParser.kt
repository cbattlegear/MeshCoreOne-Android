// PortedFrom: MC1Services/Sources/MC1Services/Services/NodeSettingsResponseParser.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror of the WP-210 core:services copy (PR #50 blob 21b37ded); features may not depend on core:services. See docs/android/deviations/WP-313.md.
package com.meshcoreone.android.feature.remotenodes.cli

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle
import java.util.regex.Pattern

/**
 * Pure parsing of repeater and room CLI responses for the node settings screens: clock parsing,
 * owner-info wire mapping, and success/error classification. Builds on [CLIResponse] and holds no state.
 */
internal object NodeSettingsResponseParser {
    /** Swift's `(query: String, value: CLIResponse)` tuple from [recoveredResponse]. */
    data class RecoveredResponse(val query: String, val value: CLIResponse)

    // MARK: - Late Reply Recovery

    /**
     * Attribution by elimination for an out-of-band CLI reply. One command is in flight per node, so a
     * reply no pending command claimed can only answer a command that timed out unanswered. Returns the
     * parsed value when the reply parses to a query-specific shape for exactly one of the given queries;
     * several matches are ambiguous, so nothing is returned.
     */
    fun recoveredResponse(response: String, unansweredQueries: Set<String>): RecoveredResponse? {
        val matches = unansweredQueries.mapNotNull { query ->
            if (!CLIResponse.isStructuredQuery(query)) return@mapNotNull null
            when (val value = CLIResponse.parse(response, query)) {
                // Query-independent shapes match every query equally; not attributable.
                is CLIResponse.Raw, CLIResponse.Ok, is CLIResponse.Error, is CLIResponse.UnknownCommand,
                is CLIResponse.Version -> null
                else -> RecoveredResponse(query, value)
            }
        }
        return matches.singleOrNull()
    }

    // MARK: - Device Clock

    // Swift Regex `\d` is Unicode-aware; spelled as an explicit Unicode class (not UNICODE_CHARACTER_CLASS)
    // so it means the same on the JVM and on Android's ICU-backed regex.
    private val clockResponseRegex: Pattern = RemoteSwiftText.UNICODE_DIGIT.let { d ->
        Pattern.compile("($d{1,2}:$d{2}) - ($d{1,2}/$d{1,2}/$d{4}) UTC")
    }

    /** Swift `DateFormatter` "HH:mm d/M/yyyy" in UTC; non-lenient, so impossible dates are rejected. */
    private val clockResponseDateFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("H:mm d/M/uuuu").withResolverStyle(ResolverStyle.STRICT)

    /** The UTC clock shape in firmware text, including inside an `OK - clock set:` reply. */
    fun clockResponseText(text: String): String? {
        val match = clockResponseRegex.matcher(text)
        return if (match.find()) match.group(0) else null
    }

    /** Parses a firmware clock response like "06:40 - 18/4/2025 UTC"; null without the UTC clock shape. */
    fun utcDate(clockResponse: String): Instant? {
        val match = clockResponseRegex.matcher(clockResponse)
        if (!match.find()) return null
        return try {
            LocalDateTime.parse("${match.group(1)} ${match.group(2)}", clockResponseDateFormat).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    /** Seconds (Swift `TimeInterval`) the node's clock is ahead of [now]; null without a clock shape. */
    fun clockDrift(clockResponse: String, relativeTo: Instant): Double? {
        val nodeDate = utcDate(clockResponse) ?: return null
        val drift = java.time.Duration.between(relativeTo, nodeDate)
        return drift.seconds + drift.nano / 1_000_000_000.0
    }

    // MARK: - Clock Sync

    /** Outcome of a `clock sync` command response. */
    sealed interface ClockSyncOutcome {
        data object Synced : ClockSyncOutcome

        /** Firmware refused the sync because its clock is ahead of the phone's. */
        data object ClockAhead : ClockSyncOutcome

        /** Firmware reported an error; [message] has "ERR: " stripped and may be empty. */
        data class Failed(val message: String) : ClockSyncOutcome
        data object Unexpected : ClockSyncOutcome
    }

    private const val CLOCK_AHEAD_ERROR_FRAGMENT = "clock cannot go backwards"
    private const val CLI_ERROR_PREFIX = "ERR: "

    /** Classifies a `clock sync` response into a typed outcome. */
    fun classifyClockSyncResponse(response: String): ClockSyncOutcome = when (val parsed = CLIResponse.parse(response)) {
        CLIResponse.Ok -> ClockSyncOutcome.Synced
        is CLIResponse.Error -> if (parsed.message.contains(CLOCK_AHEAD_ERROR_FRAGMENT)) {
            ClockSyncOutcome.ClockAhead
        } else {
            ClockSyncOutcome.Failed(parsed.message.replace(CLI_ERROR_PREFIX, ""))
        }
        else -> ClockSyncOutcome.Unexpected
    }

    // MARK: - Password

    /** Firmware echoes "password now: {pw}" on success instead of "OK". */
    private const val PASSWORD_CHANGED_PREFIX = "password now:"

    /** Whether a `password` command response indicates the change was accepted. */
    fun isPasswordChangeSuccessful(response: String): Boolean = when (val parsed = CLIResponse.parse(response)) {
        CLIResponse.Ok -> true
        is CLIResponse.Raw -> parsed.text.startsWith(PASSWORD_CHANGED_PREFIX)
        else -> false
    }

    // MARK: - Owner Info

    /** Firmware stores owner info as a single line with "|" separating rows. */
    private const val OWNER_INFO_WIRE_SEPARATOR = "|"
    private const val OWNER_INFO_DISPLAY_SEPARATOR = "\n"

    /** Maps the wire form ("|"-separated) to the multi-line display form. */
    fun displayOwnerInfo(wire: String): String = wire.replace(OWNER_INFO_WIRE_SEPARATOR, OWNER_INFO_DISPLAY_SEPARATOR)

    /** Maps the multi-line display form back to the "|"-separated wire form. */
    fun wireOwnerInfo(display: String): String = display.replace(OWNER_INFO_DISPLAY_SEPARATOR, OWNER_INFO_WIRE_SEPARATOR)
}
