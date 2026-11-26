// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistentLogger.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel

/** Delivery point for persisted entries; production delivers to the process-wide [DebugLogBuffer] hub. */
fun interface DebugLogRecorder {
    fun record(entry: DebugLogEntryDTO)
}

/**
 * Drop-in replacement for a platform logger that uses the shared [DebugLogBuffer].
 *
 * Every level is written to the non-persistent [platformSink] (Swift writes to OSLog). `.info`
 * and above are additionally persisted through [recorder] (Swift `DebugLogBuffer.record`);
 * `debug` is log-only.
 *
 * Android has no `os.Logger`: the default sink is [JavaUtilLoggingSink] because `core:services`
 * is a pure JVM module that cannot reference Android's `Log`; Android forwards
 * `java.util.logging` to logcat. The sink, clock and recorder are injectable for tests.
 *
 * Thread-safe and stateless like the Swift `Sendable` struct: it may be called from any thread.
 */
class PersistentLogger(
    val subsystem: String,
    val category: String,
    private val platformSink: DebugLogPlatformSink = JavaUtilLoggingSink,
    private val clock: DebugLogClock = SystemDebugLogClock,
    private val recorder: DebugLogRecorder = DebugLogRecorder { DebugLogBuffer.record(it) },
) {
    fun debug(message: String) {
        platformSink.write(DebugLogLevel.DEBUG, subsystem, category, message)
    }

    fun info(message: String) = logAndPersist(DebugLogLevel.INFO, message)

    fun notice(message: String) = logAndPersist(DebugLogLevel.NOTICE, message)

    fun warning(message: String) = logAndPersist(DebugLogLevel.WARNING, message)

    fun error(message: String) = logAndPersist(DebugLogLevel.ERROR, message)

    fun fault(message: String) = logAndPersist(DebugLogLevel.FAULT, message)

    private fun logAndPersist(level: DebugLogLevel, message: String) {
        platformSink.write(level, subsystem, category, message)
        recorder.record(
            DebugLogEntryDTO.create(
                level = level,
                subsystem = subsystem,
                category = category,
                message = message,
                timestamp = clock.now(),
            ),
        )
    }
}
