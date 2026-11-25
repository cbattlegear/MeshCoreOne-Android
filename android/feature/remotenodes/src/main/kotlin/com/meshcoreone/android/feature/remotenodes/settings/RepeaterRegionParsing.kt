// PortedFrom: MC1/Views/RemoteNodes/Repeaters/RepeaterSettingsViewModel+Regions.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.settings

import com.meshcoreone.android.feature.remotenodes.cli.RemoteSwiftText
import java.util.regex.Pattern

/** Parsers for the `region` / `region default` CLI replies. */
object RepeaterRegionParsing {
    /** `RegionMap.exportTo(reply, 160)` yields at most 159 UTF-8 bytes plus a trailing NUL. */
    const val FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES = 159

    /** CLI argument when the default scope is unset (`region default <null>`). */
    internal const val FIRMWARE_NULL_TOKEN = "<null>"

    /** Substring of a `region default` reply; set replies use [DEFAULT_SCOPE_SET_REPLY_MARKER]. */
    internal const val DEFAULT_SCOPE_REPLY_MARKER = "default scope is"
    internal const val DEFAULT_SCOPE_SET_REPLY_MARKER = "default scope is now"

    private val GRAPHEME: Pattern = Pattern.compile("\\X")

    /** Unset (`<null>` or `*`) versus a named region. */
    sealed interface ParsedDefaultScope {
        data object Cleared : ParsedDefaultScope
        data class Named(val name: String) : ParsedDefaultScope
    }

    /**
     * Parses the indented region dump. Firmware ends each region with LF; a truncated (saturated) dump
     * or one whose last scalar is not LF yields an empty list, as does any malformed row. Swift splits
     * on `Character`s, where CRLF is one character, so lines and indents are measured in grapheme
     * clusters here too.
     */
    fun parseRegionTree(response: String): List<RepeaterRegionEntry> {
        if (response.toByteArray(Charsets.UTF_8).size >= FIRMWARE_REGION_DUMP_MAX_PAYLOAD_BYTES) return emptyList()
        if (!response.endsWith("\n")) return emptyList()

        val entries = mutableListOf<RepeaterRegionEntry>()
        val stack = mutableListOf<String>()
        for (line in splitLines(characters(response)) { it == "\n" || it == "\r\n" }) {
            val depth = line.takeWhile { it == " " }.size
            var text = line.drop(depth)
            if (text.isEmpty()) return emptyList()

            val floodAllowed = text.size >= 2 && text[text.size - 2] == " " && text[text.size - 1] == "F"
            if (floodAllowed) text = text.dropLast(2)
            val isHome = text.lastOrNull() == "^"
            if (isHome) text = text.dropLast(1)

            if (text.isEmpty() || text.contains(" ")) return emptyList()
            if (depth > stack.size) return emptyList()
            while (stack.size > depth) stack.removeAt(stack.size - 1)
            val name = text.joinToString("")
            stack.add(name)
            entries += RepeaterRegionEntry(
                name = name,
                parentName = if (depth == 0) null else stack[depth - 1],
                depth = depth,
                floodAllowed = floodAllowed,
                isHome = isHome,
            )
        }
        return entries
    }

    /** Null is an unparsed reply, not an unset scope. */
    fun parseDefaultScopeReply(response: String): ParsedDefaultScope? {
        // Swift splits on the Character "\n" only; a CRLF character stays inside the line.
        val last = splitLines(characters(response)) { it == "\n" }.lastOrNull() ?: return null
        var line = RemoteSwiftText.trimWhitespaces(last.joinToString(""))
        if (line.startsWith(">")) {
            line = RemoteSwiftText.trimWhitespaces(characters(line).drop(1).joinToString(""))
        }
        if (!line.contains(DEFAULT_SCOPE_REPLY_MARKER, ignoreCase = true)) return null
        val lastToken = splitLines(characters(line)) { it == " " }.lastOrNull()?.joinToString("") ?: return null
        // Firmware treats a default of `*` as unset, same as `<null>`.
        if (lastToken == FIRMWARE_NULL_TOKEN || lastToken == RepeaterRegionEntry.UNSCOPED_NAME) return ParsedDefaultScope.Cleared
        return ParsedDefaultScope.Named(lastToken)
    }

    /** Swift `Character`s (extended grapheme clusters). */
    private fun characters(text: String): List<String> {
        val matcher = GRAPHEME.matcher(text)
        val result = mutableListOf<String>()
        while (matcher.find()) result += matcher.group()
        return result
    }

    /** `split(omittingEmptySubsequences: true, whereSeparator:)` over characters. */
    private fun splitLines(characters: List<String>, isSeparator: (String) -> Boolean): List<List<String>> {
        val lines = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        for (character in characters) {
            if (isSeparator(character)) {
                if (current.isNotEmpty()) lines += current
                current = mutableListOf()
            } else {
                current += character
            }
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }
}
