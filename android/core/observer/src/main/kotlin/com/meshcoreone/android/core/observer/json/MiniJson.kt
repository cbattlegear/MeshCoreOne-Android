// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.json

/**
 * Small strict JSON reader (this module has no JSON library). Produces Map<String, Any?> (insertion ordered), List<Any?>,
 * String, Double, Boolean or null. Numbers are always Double. Throws IllegalArgumentException with the offset on bad input.
 * Used for the bundled geofence file and by tests to compare wire JSON structurally.
 */
object MiniJson {
    fun parse(text: String): Any? {
        val reader = Reader(text)
        val value = reader.value()
        reader.skipWhitespace()
        require(reader.atEnd()) { "trailing characters at ${reader.pos}" }
        return value
    }

    private class Reader(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWhitespace() { while (pos < s.length && s[pos] in " \t\r\n") pos++ }

        fun value(): Any? {
            skipWhitespace()
            require(pos < s.length) { "unexpected end" }
            return when (val c = s[pos]) {
                '{' -> obj()
                '[' -> array()
                '"' -> string()
                't' -> literal("true", true)
                'f' -> literal("false", false)
                'n' -> literal("null", null)
                else -> if (c == '-' || c in '0'..'9') number() else throw IllegalArgumentException("unexpected '$c' at $pos")
            }
        }

        private fun literal(word: String, result: Any?): Any? {
            require(s.startsWith(word, pos)) { "bad literal at $pos" }
            pos += word.length
            return result
        }

        private fun number(): Double {
            val start = pos
            while (pos < s.length && s[pos] in "+-0123456789.eE") pos++
            return s.substring(start, pos).toDoubleOrNull() ?: throw IllegalArgumentException("bad number at $start")
        }

        private fun string(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                val c = s[pos++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        require(pos < s.length) { "unterminated escape" }
                        when (val e = s[pos++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'u' -> {
                                require(pos + 4 <= s.length) { "bad unicode escape at $pos" }
                                sb.append(s.substring(pos, pos + 4).toInt(16).toChar()); pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape '\\$e' at ${pos - 1}")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun array(): List<Any?> {
            pos++
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') { pos++; return out }
            while (true) {
                out += value()
                skipWhitespace()
                when (next()) { ',' -> continue; ']' -> return out; else -> throw IllegalArgumentException("expected , or ] at ${pos - 1}") }
            }
        }

        private fun obj(): Map<String, Any?> {
            pos++
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') { pos++; return out }
            while (true) {
                skipWhitespace()
                require(peek() == '"') { "expected key at $pos" }
                val key = string()
                skipWhitespace()
                require(next() == ':') { "expected : at ${pos - 1}" }
                out[key] = value()
                skipWhitespace()
                when (next()) { ',' -> continue; '}' -> return out; else -> throw IllegalArgumentException("expected , or } at ${pos - 1}") }
            }
        }

        private fun peek(): Char? = if (pos < s.length) s[pos] else null
        private fun next(): Char? = if (pos < s.length) s[pos++] else null
    }
}
