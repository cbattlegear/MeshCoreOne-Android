// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.json

/**
 * Minimal ordered JSON object writer (no JSON library is a dependency of this module). Field order is the call
 * order, so serialized output is stable and can be asserted exactly. Non-finite doubles are never written.
 */
class JsonObjectBuilder {
    private val out = StringBuilder("{")
    private var first = true

    private fun key(name: String) {
        if (!first) out.append(',')
        first = false
        out.append(quote(name)).append(':')
    }

    fun string(name: String, value: String): JsonObjectBuilder { key(name); out.append(quote(value)); return this }
    fun long(name: String, value: Long): JsonObjectBuilder { key(name); out.append(value); return this }
    fun int(name: String, value: Int): JsonObjectBuilder = long(name, value.toLong())
    fun bool(name: String, value: Boolean): JsonObjectBuilder { key(name); out.append(value); return this }
    fun double(name: String, value: Double): JsonObjectBuilder {
        require(value.isFinite()) { "JSON cannot carry a non-finite number" }
        key(name); out.append(value); return this
    }

    /** A number rendered with exactly [decimals] fraction digits (Locale.ROOT, so never a decimal comma). */
    fun fixed(name: String, value: Double, decimals: Int): JsonObjectBuilder {
        require(value.isFinite()) { "JSON cannot carry a non-finite number" }
        key(name); out.append(String.format(java.util.Locale.ROOT, "%.${decimals}f", value)); return this
    }

    /** Adds [value] only when it is non-null and finite; this is how optional fields are omitted. */
    fun optString(name: String, value: String?): JsonObjectBuilder = if (value == null) this else string(name, value)
    fun optLong(name: String, value: Long?): JsonObjectBuilder = if (value == null) this else long(name, value)
    fun optDouble(name: String, value: Double?): JsonObjectBuilder =
        if (value == null || !value.isFinite()) this else double(name, value)

    /** Adds an already-rendered JSON value (object or array). */
    fun raw(name: String, renderedJson: String): JsonObjectBuilder { key(name); out.append(renderedJson); return this }

    fun build(): String = "$out}"

    companion object {
        fun quote(text: String): String {
            val sb = StringBuilder("\"")
            for (c in text) {
                when {
                    c == '"' -> sb.append("\\\"")
                    c == '\\' -> sb.append("\\\\")
                    c == '\n' -> sb.append("\\n")
                    c == '\r' -> sb.append("\\r")
                    c == '\t' -> sb.append("\\t")
                    c.code < 0x20 -> sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
                    else -> sb.append(c)
                }
            }
            return sb.append('"').toString()
        }

        fun array(renderedElements: List<String>): String = renderedElements.joinToString(",", "[", "]")
    }
}

inline fun jsonObject(block: JsonObjectBuilder.() -> Unit): String = JsonObjectBuilder().apply(block).build()
