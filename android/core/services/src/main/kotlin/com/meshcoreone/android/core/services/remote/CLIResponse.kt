// PortedFrom: MC1Services/Sources/MC1Services/Services/CLIResponse.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.remote

import java.util.regex.Pattern

/** Parsed CLI response from a repeater or room server. Swift `Int` payloads map to [Long]. */
sealed interface CLIResponse {
    data object Ok : CLIResponse
    data class Error(val message: String) : CLIResponse

    /** Specific case for "Error: unknown command". */
    data class UnknownCommand(val message: String) : CLIResponse
    data class Version(val text: String) : CLIResponse
    data class DeviceTime(val text: String) : CLIResponse
    data class Name(val text: String) : CLIResponse
    data class Radio(val frequency: Double, val bandwidth: Double, val spreadingFactor: Long, val codingRate: Long) :
        CLIResponse
    data class TxPower(val dBm: Long) : CLIResponse
    data class RepeatMode(val enabled: Boolean) : CLIResponse
    data class AdvertInterval(val minutes: Long) : CLIResponse

    /** Value is in hours, not minutes. */
    data class FloodAdvertInterval(val hours: Long) : CLIResponse
    data class FloodMax(val hops: Long) : CLIResponse
    data class Latitude(val value: Double) : CLIResponse
    data class Longitude(val value: Double) : CLIResponse
    data class OwnerInfo(val text: String) : CLIResponse
    data class Raw(val text: String) : CLIResponse

    /** Swift's `(prefix: String, body: String)` tuple from [splitEchoedPrefix]. */
    data class EchoedPrefix(val prefix: String, val body: String)

    /**
     * Canonical query strings. [parse]'s query hints and the structured-query set both build on these
     * so a new query can't join one and drift from the other.
     */
    object Query {
        const val VERSION = "ver"
        const val NAME = "get name"
        const val OWNER_INFO = "get owner.info"
        const val CLOCK = "clock"
        const val RADIO = "get radio"
        const val TX_POWER = "get tx"
        const val REPEAT_MODE = "get repeat"
        const val ADVERT_INTERVAL = "get advert.interval"
        const val FLOOD_ADVERT_INTERVAL = "get flood.advert.interval"
        const val FLOOD_MAX = "get flood.max"
        const val LATITUDE = "get lat"
        const val LONGITUDE = "get lon"
    }

    companion object {
        /**
         * Separator of the optional CLI wire prefix. Repeater and room firmware reflect a leading "XX|"
         * from the command back at the start of the reply, giving the tagless CLI channel a correlation token.
         */
        const val ECHO_PREFIX_SEPARATOR: Char = '|'

        /** Length of the wire prefix including the separator, in Swift `Character`s. */
        private const val ECHO_PREFIX_LENGTH = 3
        private val ECHO_PREFIX = Regex("[0-9A-F]{2}\\|")

        // Swift Regex `\d`/`\s` are Unicode-aware. Spelled as explicit Unicode classes, not
        // UNICODE_CHARACTER_CLASS, so they mean the same on the JVM and on Android's ICU-backed regex.
        private val TX_MAX = Pattern.compile("max=(-?${RemoteSwiftText.UNICODE_DIGIT}+)")
        private val TX_LEADING =
            Pattern.compile("^(-?${RemoteSwiftText.UNICODE_DIGIT}+)(?:dBm|${RemoteSwiftText.UNICODE_WHITESPACE}|$)")

        /**
         * Queries whose replies have a machine-checkable shape. Free-form gets and set/action commands
         * are absent because their success replies are arbitrary text: firmware answers `password` with
         * "password now:", not "OK", and letsmesh builds answer `ver` with text no shape check covers.
         */
        private val structuredQueries: Set<String> = setOf(
            Query.RADIO, Query.TX_POWER, Query.REPEAT_MODE, Query.ADVERT_INTERVAL,
            Query.FLOOD_ADVERT_INTERVAL, Query.FLOOD_MAX, Query.LATITUDE, Query.LONGITUDE,
            Query.CLOCK,
        )

        /**
         * Parse CLI response text into a structured type. Response correlation is the caller's job,
         * based on pending query tracking.
         */
        fun parse(text: String, forQuery: String? = null): CLIResponse {
            val trimmed = stripPrompt(RemoteSwiftText.trimWhitespacesAndNewlines(text))
            classifyUniversal(trimmed, forQuery)?.let { return it }
            return classifyForQuery(trimmed, forQuery) ?: Raw(trimmed)
        }

        /** Firmware prepends "> " to all CLI command responses; a bare ">" is empty content. */
        private fun stripPrompt(trimmed: String): String = when {
            trimmed.startsWith("> ") -> trimmed.substring(2)
            trimmed == ">" -> ""
            else -> trimmed
        }

        /** Shapes recognized regardless of the pending query, plus the free-form query hints. */
        private fun classifyUniversal(trimmed: String, query: String?): CLIResponse? {
            // Success responses: "OK" or "OK - clock set: ..." etc.
            if (trimmed == "OK" || trimmed.startsWith("OK - ")) return Ok
            if (trimmed.lowercase().startsWith("error") || trimmed.startsWith("ERR:")) {
                // "unknown command" is singled out for defensive handling.
                return if (trimmed.lowercase().contains("unknown command")) UnknownCommand(trimmed) else Error(trimmed)
            }
            // Firmware version: "MeshCore v1.10.0 (2025-04-18)" or "v1.11.0 (2025-04-18)".
            if (trimmed.startsWith("MeshCore v") || (trimmed.startsWith("v") && trimmed.contains("("))) {
                return Version(trimmed)
            }
            return when (query) {
                Query.VERSION -> Version(trimmed)
                Query.NAME -> Name(trimmed)
                Query.OWNER_INFO -> OwnerInfo(trimmed)
                else -> null
            }
        }

        /** Typed shapes gated on their structured query. */
        private fun classifyForQuery(trimmed: String, query: String?): CLIResponse? = when (query) {
            // Gated on the query because ":" + "/" also appears in names and owner info.
            Query.CLOCK -> if (trimmed.contains("UTC") || (trimmed.contains(":") && trimmed.contains("/"))) {
                DeviceTime(trimmed)
            } else null
            Query.RADIO -> parseRadio(trimmed)
            Query.TX_POWER -> parseTXPowerDBm(trimmed)?.let(::TxPower)
            Query.REPEAT_MODE -> when (trimmed.lowercase()) {
                "on" -> RepeatMode(true)
                "off" -> RepeatMode(false)
                else -> null
            }
            Query.ADVERT_INTERVAL -> RemoteSwiftText.int(trimmed)?.let(::AdvertInterval)
            Query.FLOOD_ADVERT_INTERVAL -> RemoteSwiftText.int(trimmed)?.let(::FloodAdvertInterval)
            Query.FLOOD_MAX -> RemoteSwiftText.int(trimmed)?.let(::FloodMax)
            Query.LATITUDE -> RemoteSwiftText.double(trimmed)?.let(::Latitude)
            Query.LONGITUDE -> RemoteSwiftText.double(trimmed)?.let(::Longitude)
            else -> null
        }

        /** Radio params: "915.000,250.0,10,5" (freq,bw,sf,cr). Swift `split` drops empty pieces. */
        private fun parseRadio(trimmed: String): Radio? {
            val parts = trimmed.split(',').filter { it.isNotEmpty() }.map(RemoteSwiftText::trimWhitespaces)
            if (parts.size < 4) return null
            val frequency = RemoteSwiftText.double(parts[0]) ?: return null
            val bandwidth = RemoteSwiftText.double(parts[1]) ?: return null
            val spreadingFactor = RemoteSwiftText.int(parts[2]) ?: return null
            val codingRate = RemoteSwiftText.int(parts[3]) ?: return null
            return Radio(frequency, bandwidth, spreadingFactor, codingRate)
        }

        /**
         * Reads the configured TX power in dBm from a `get tx` reply. Stock firmware sends a bare integer
         * ("> 22"); ZephCore appends Adaptive Power Control state ("> 22dBm (apc=off)"), and while APC is
         * active the leading number is the reduced live power whereas `max=` is what `set tx` writes back,
         * so `max=` wins when present. A digit run followed by "." or "," is never a power.
         */
        private fun parseTXPowerDBm(text: String): Long? {
            val ceiling = TX_MAX.matcher(text)
            if (ceiling.find()) return RemoteSwiftText.int(ceiling.group(1))
            val leading = TX_LEADING.matcher(text)
            if (leading.find()) return RemoteSwiftText.int(leading.group(1))
            return null
        }

        /** Whether the query's reply has a machine-checkable shape. */
        fun isStructuredQuery(query: String): Boolean = query in structuredQueries

        /**
         * Whether a reply is plausible for the given pending query. Replies to structured gets must parse
         * to their typed case (or an error); everything else matches any text.
         */
        fun isPlausibleResponse(response: String, forQuery: String): Boolean {
            if (forQuery !in structuredQueries) return true
            return parse(response, forQuery) !is Raw
        }

        /**
         * Splits an echoed wire prefix off a reply; null when the reply carries none. Only two uppercase
         * hex digits plus the separator qualify, so ordinary reply text can't be mistaken for a prefix.
         * Counts are Swift `Character`s (grapheme clusters), as in the original.
         */
        fun splitEchoedPrefix(text: String): EchoedPrefix? {
            val leading = RemoteSwiftText.leadingCharacters(text, ECHO_PREFIX_LENGTH + 1)
            if (leading.size <= ECHO_PREFIX_LENGTH) return null
            val prefix = leading.take(ECHO_PREFIX_LENGTH).joinToString("")
            if (!ECHO_PREFIX.matches(prefix)) return null
            return EchoedPrefix(prefix, text.substring(prefix.length))
        }
    }
}
