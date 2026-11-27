// AndroidOnly: WP-101 Prizm Mobile Observer (see docs/android/deviations/PRIZM-OBSERVER.md).
package com.meshcoreone.android.core.observer.location

/**
 * The last [retentionMs] (default 2 minutes, measured back from the newest fix) of fixes, ordered by time. Thread safe:
 * the location callback adds while the packet path reads. Readers get immutable snapshots.
 */
class FixBuffer(private val retentionMs: Long = DEFAULT_RETENTION_MS) {
    private val lock = Any()
    private var fixes: List<LocationFix> = emptyList()

    /** Adds [fix]; returns false (and keeps nothing) for an invalid one. A second fix at the same instant replaces the first. */
    fun add(fix: LocationFix): Boolean {
        if (!fix.isValid) return false
        synchronized(lock) {
            val merged = (fixes.filter { it.fixElapsedMs != fix.fixElapsedMs } + fix).sortedBy { it.fixElapsedMs }
            val cutoff = merged.last().fixElapsedMs - retentionMs
            fixes = merged.filter { it.fixElapsedMs >= cutoff }
        }
        return true
    }

    fun snapshot(): List<LocationFix> = synchronized(lock) { fixes }

    /** Latest fix at or before [elapsedMs]. */
    fun atOrBefore(elapsedMs: Long): LocationFix? = snapshot().lastOrNull { it.fixElapsedMs <= elapsedMs }

    /** Earliest fix at or after [elapsedMs]. */
    fun atOrAfter(elapsedMs: Long): LocationFix? = snapshot().firstOrNull { it.fixElapsedMs >= elapsedMs }

    companion object { const val DEFAULT_RETENTION_MS = 120_000L }
}
