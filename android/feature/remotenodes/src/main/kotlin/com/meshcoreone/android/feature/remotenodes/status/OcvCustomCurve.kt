// PortedFrom: MC1/Views/RemoteNodes/NodeStatusViewModel.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.remotenodes.status

/**
 * The stored custom OCV curve string as `NodeStatusViewModel` reads and writes it. Parsing matches
 * Swift `split(separator: ",").compactMap { Int($0.trimmingCharacters(in: .whitespaces)) }` (verified
 * with swiftc, oracles/status_foundation.swift.txt): empty pieces are skipped; only tab and Unicode
 * space separators (Zs) are trimmed, so a piece carrying a newline, VT, FF, NEL or line separator is
 * dropped; `Int` accepts one optional sign and ASCII digits only and rejects 64-bit overflow.
 */
object OcvCustomCurve {
    /** Number of points a usable curve has (100 % down to 0 % in tens). */
    const val POINT_COUNT = 11

    private const val SEPARATOR = ','
    private const val DECIMAL_RADIX = 10

    fun parse(customString: String): List<Long> =
        customString.split(SEPARATOR).filter { it.isNotEmpty() }.mapNotNull { swiftInt(trimSwiftWhitespaces(it)) }

    /** Swift `values.map(String.init).joined(separator: ",")`. */
    fun format(values: List<Long>): String = values.joinToString(SEPARATOR.toString())

    private fun isSwiftWhitespace(character: Char): Boolean =
        character == '\t' || Character.getType(character) == Character.SPACE_SEPARATOR.toInt()

    private fun trimSwiftWhitespaces(text: String): String = text.trim(::isSwiftWhitespace)

    private fun swiftInt(text: String): Long? {
        if (text.isEmpty()) return null
        val negative = text[0] == '-'
        val digits = if (text[0] == '-' || text[0] == '+') text.substring(1) else text
        if (digits.isEmpty() || digits.any { it !in '0'..'9' }) return null
        var magnitude = 0L
        for (digit in digits) {
            val value = (digit - '0').toLong()
            // Accumulate negatively so Long.MIN_VALUE stays representable, as Swift `Int` allows.
            if (magnitude < (Long.MIN_VALUE + value) / DECIMAL_RADIX) return null
            magnitude = magnitude * DECIMAL_RADIX - value
        }
        return when {
            negative -> magnitude
            magnitude == Long.MIN_VALUE -> null
            else -> -magnitude
        }
    }
}
