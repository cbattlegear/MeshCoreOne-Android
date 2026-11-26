// PortedFrom: MC1/State/AppState.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.model.RegionSelection

/**
 * The stored form of the user's region choice: a flat JSON object of string fields, as Swift's `JSONEncoder`
 * writes `RegionSelection` (`countryCode`, `source`, and `administrativeAreaCode`/`countyKey` only when set).
 * "/" is escaped as Foundation does. Parsing is strict: anything but one flat object of string values with a known source decodes to null, which the
 * loader treats as a corrupt value and clears. Native adaptation: no JSON library is on the app classpath, and the
 * grammar needed here is a single level of string members.
 */
object RegionSelectionJson {
    fun encode(selection: RegionSelection): String {
        val members = buildList {
            add("countryCode" to selection.countryCode)
            selection.administrativeAreaCode?.let { add("administrativeAreaCode" to it) }
            selection.countyKey?.let { add("countyKey" to it) }
            add("source" to selection.source.rawValue)
        }
        return members.joinToString(",", "{", "}") { (key, value) -> "${quote(key)}:${quote(value)}" }
    }

    fun decode(text: String): RegionSelection? {
        val members = Parser(text).parseObject() ?: return null
        val country = members["countryCode"] ?: return null
        val source = RegionSelection.Source.entries.firstOrNull { it.rawValue == members["source"] } ?: return null
        val allowed = setOf("countryCode", "source", "administrativeAreaCode", "countyKey")
        if (!allowed.containsAll(members.keys)) return null
        return RegionSelection(country, source, members["administrativeAreaCode"], members["countyKey"])
    }

    private fun quote(value: String): String = buildString {
        append('"')
        for (character in value) {
            when {
                character == '"' -> append("\\\"")
                character == '\\' -> append("\\\\")
                character == '/' -> append("\\/")
                character == '\n' -> append("\\n")
                character == '\r' -> append("\\r")
                character == '\t' -> append("\\t")
                character.code < 0x20 -> append("\\u%04x".format(character.code))
                else -> append(character)
            }
        }
        append('"')
    }

    private class Parser(private val text: String) {
        private var index = 0

        fun parseObject(): Map<String, String>? {
            skipSpace()
            if (!consume('{')) return null
            val members = linkedMapOf<String, String>()
            skipSpace()
            if (consume('}')) return finish(members)
            while (true) {
                skipSpace()
                val key = parseString() ?: return null
                skipSpace()
                if (!consume(':')) return null
                skipSpace()
                val value = parseString() ?: return null
                if (members.put(key, value) != null) return null
                skipSpace()
                if (consume(',')) continue
                if (consume('}')) return finish(members)
                return null
            }
        }

        private fun finish(members: Map<String, String>): Map<String, String>? {
            skipSpace()
            return if (index == text.length) members else null
        }

        private fun parseString(): String? {
            if (!consume('"')) return null
            val builder = StringBuilder()
            while (index < text.length) {
                val character = text[index++]
                when {
                    character == '"' -> return builder.toString()
                    character == '\\' -> builder.append(parseEscape() ?: return null)
                    character.code < 0x20 -> return null
                    else -> builder.append(character)
                }
            }
            return null
        }

        private fun parseEscape(): Char? {
            if (index >= text.length) return null
            return when (val escape = text[index++]) {
                '"', '\\', '/' -> escape
                'b' -> '\b'
                'f' -> '\u000c'
                'n' -> '\n'
                'r' -> '\r'
                't' -> '\t'
                'u' -> {
                    if (index + 4 > text.length) return null
                    val digits = text.substring(index, index + 4)
                    if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
                    index += 4
                    digits.toInt(16).toChar()
                }
                else -> null
            }
        }

        private fun skipSpace() {
            while (index < text.length && (text[index] == ' ' || text[index] == '\n' || text[index] == '\r' || text[index] == '\t')) index++
        }

        private fun consume(expected: Char): Boolean {
            if (index < text.length && text[index] == expected) { index++; return true }
            return false
        }
    }
}
