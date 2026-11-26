// AndroidOnly: WP-213 Strict receive-side MECP reading for the v1.7.1 chat display.
package com.meshcoreone.android.core.model

class MecpMessage private constructor(
    val severity: Severity,
    val codes: SnapshotList<String>,
    val personCount: Long?,
    val utcClock: String?,
    val etaMinutes: Long?,
    val tail: String?,
) {
    enum class Severity(val wireDigit: Char) {
        MAYDAY('0'), URGENT('1'), SAFETY('2'), ROUTINE('3'),
    }

    companion object {
        private const val PREFIX = "MECP/"
        private val code = Regex("[A-Z][0-9]{2}")
        private val pax = Regex("[0-9]+pax")
        private val clock = Regex("@[0-9]{4}")
        private val eta = Regex("[0-9]+")

        fun decode(text: String): MecpMessage? {
            if (!text.startsWith(PREFIX) || text.getOrNull(PREFIX.length + 1) != '/') return null
            val severity = Severity.entries.firstOrNull { it.wireDigit == text[PREFIX.length] } ?: return null
            val tokens = text.substring(PREFIX.length + 2).split(' ').filter(String::isNotEmpty)
            val codes = tokens.takeWhile(code::matches).snapshot()
            if (codes.isEmpty()) return null

            val tail = tokens.drop(codes.size)
            val paxIndex = tail.indexOfFirst(pax::matches)
            val personCount = tail.getOrNull(paxIndex)?.removeSuffix("pax")?.toLongOrNull()
            val clockIndex = tail.indexOfFirst(clock::matches)
            val utcClock = tail.getOrNull(clockIndex)?.drop(1)?.takeIf {
                it.take(2).toInt() < 24 && it.takeLast(2).toInt() < 60
            }
            val etaMinutes = if ("R03" in codes) tail.firstOrNull()?.takeIf(eta::matches)?.toLongOrNull() else null
            val remainder = tail.filterIndexed { index, _ ->
                !(index == paxIndex && personCount != null ||
                    index == clockIndex && utcClock != null ||
                    index == 0 && etaMinutes != null)
            }.joinToString(" ").ifEmpty { null }
            return MecpMessage(severity, codes, personCount, utcClock, etaMinutes, remainder)
        }
    }
}
