// PortedFrom: MC1Services/Sources/MC1Services/Services/RemoteCLICommandRewriter.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Feature-local mirror of the WP-210 core:services copy (PR #50 blob 93b31f87); features may not depend on core:services. See docs/android/deviations/WP-313.md.
package com.meshcoreone.android.feature.remotenodes.cli

import java.time.Instant

/**
 * Rewrites remote `clock sync` to `time <host-epoch>`. Companion firmware restamps CLI packets to its
 * own RTC, so the packet timestamp is not the phone clock.
 */
internal object RemoteCLICommandRewriter {
    const val CLOCK_SYNC_COMMAND = "clock sync"
    const val TIME_COMMAND_PREFIX = "time "

    fun rewrite(command: String, now: Instant = Instant.now()): String {
        val normalized = command.splitOnWhitespace().joinToString(" ").lowercase()
        if (normalized != CLOCK_SYNC_COMMAND) return command
        return TIME_COMMAND_PREFIX + epochSeconds32(now).toString()
    }

    /** Saturates pre-1970 and post-2106 instants instead of trapping like Swift `UInt32(_:)`. */
    internal fun epochSeconds32(instant: Instant): UInt {
        val seconds = instant.epochSecond
        if (seconds < 0 || (seconds == 0L && instant.nano == 0)) return 0u
        if (seconds >= UInt.MAX_VALUE.toLong()) return UInt.MAX_VALUE
        return seconds.toUInt()
    }

    /** Swift `split(whereSeparator: \.isWhitespace)`: whitespace runs separate words, empty pieces dropped. */
    private fun String.splitOnWhitespace(): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        for (character in this) {
            if (character.isWhitespace()) {
                if (current.isNotEmpty()) { words += current.toString(); current.clear() }
            } else {
                current.append(character)
            }
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }
}
