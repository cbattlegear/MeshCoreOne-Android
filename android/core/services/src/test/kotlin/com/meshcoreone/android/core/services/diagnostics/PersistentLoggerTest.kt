// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/PersistentLoggerTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Services/Sources/MC1Services/Services/PersistentLogger.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import java.time.Duration
import java.util.UUID
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Reassigns the process-global [DebugLogBuffer.shared]; the Swift suite is `.serialized`. */
class PersistentLoggerTest {
    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        logCoreOriginal("PersistentLoggerTests", "debug does not persist and info does") {
            withLogCoreScope { scope ->
                val store = LogCoreMemoryStore()
                val buffer = DebugLogBuffer(store, scope)

                var messages: List<String> = emptyList()
                try {
                    for (attempt in 0 until MAX_READ_BACK_ATTEMPTS) {
                        val category = UUID.randomUUID().toString()
                        if (!writeAndReadBack(buffer)) continue
                        DebugLogBuffer.resetPendingStateForTesting()

                        val logger = PersistentLogger("test.persist-gate", category, platformSink = LogCoreRecordingSink())
                        logger.debug("debug-line")
                        logger.info("info-line")
                        messages = pollForMessages(store, buffer, category, requiredMessage = "info-line")
                        if ("info-line" in messages) break
                    }
                } finally {
                    DebugLogBuffer.shared = null
                }

                assertFalse("debug-line" in messages)
                assertTrue("info-line" in messages)
            }
        },
    )

    @TestFactory
    fun nativeCases(): List<DynamicTest> = listOf(
        logCoreNative("every level writes to the platform sink and info and above persist with level, subsystem and category") {
            val sink = LogCoreRecordingSink()
            val recorded = mutableListOf<DebugLogEntryDTO>()
            val logger = PersistentLogger("sub", "cat", sink, LogCoreManualClock()) { recorded += it }
            logger.debug("d")
            logger.info("i")
            logger.notice("n")
            logger.warning("w")
            logger.error("e")
            logger.fault("f")

            assertEquals(
                listOf(
                    LogCoreSinkLine(DebugLogLevel.DEBUG, "sub", "cat", "d"),
                    LogCoreSinkLine(DebugLogLevel.INFO, "sub", "cat", "i"),
                    LogCoreSinkLine(DebugLogLevel.NOTICE, "sub", "cat", "n"),
                    LogCoreSinkLine(DebugLogLevel.WARNING, "sub", "cat", "w"),
                    LogCoreSinkLine(DebugLogLevel.ERROR, "sub", "cat", "e"),
                    LogCoreSinkLine(DebugLogLevel.FAULT, "sub", "cat", "f"),
                ),
                sink.lines,
            )
            assertEquals(
                listOf(
                    DebugLogLevel.INFO to "i", DebugLogLevel.NOTICE to "n", DebugLogLevel.WARNING to "w",
                    DebugLogLevel.ERROR to "e", DebugLogLevel.FAULT to "f",
                ),
                recorded.map { it.level to it.message },
            )
            assertTrue(recorded.all { it.subsystem == "sub" && it.category == "cat" })
        },
        logCoreNative("the persisted timestamp comes from the injected clock") {
            val clock = LogCoreManualClock()
            clock.advance(Duration.ofSeconds(42))
            val recorded = mutableListOf<DebugLogEntryDTO>()
            PersistentLogger("sub", "cat", LogCoreRecordingSink(), clock) { recorded += it }.info("x")
            assertEquals(LOG_CORE_EPOCH.plusSeconds(42), recorded.single().timestamp)
        },
        logCoreNative("the default recorder queues into the process-wide hub while no buffer is assigned") {
            DebugLogBuffer.shared = null
            DebugLogBuffer.resetPendingStateForTesting()
            try {
                val logger = PersistentLogger("test.hub", UUID.randomUUID().toString(), platformSink = LogCoreRecordingSink())
                logger.debug("not queued")
                logger.warning("queued")
                assertEquals(1, DebugLogBuffer.pendingCountForTesting)
            } finally {
                DebugLogBuffer.resetPendingStateForTesting()
            }
        },
        logCoreNative("java.util.logging sink maps levels and writes braces verbatim") {
            assertEquals(Level.FINE, JavaUtilLoggingSink.julLevel(DebugLogLevel.DEBUG))
            assertEquals(Level.INFO, JavaUtilLoggingSink.julLevel(DebugLogLevel.INFO))
            assertEquals(Level.INFO, JavaUtilLoggingSink.julLevel(DebugLogLevel.NOTICE))
            assertEquals(Level.WARNING, JavaUtilLoggingSink.julLevel(DebugLogLevel.WARNING))
            assertEquals(Level.SEVERE, JavaUtilLoggingSink.julLevel(DebugLogLevel.ERROR))
            assertEquals(Level.SEVERE, JavaUtilLoggingSink.julLevel(DebugLogLevel.FAULT))

            val category = "jul-${UUID.randomUUID()}"
            val julLogger = Logger.getLogger("test.wp212.$category")
            val captured = mutableListOf<LogRecord>()
            val handler = object : Handler() {
                override fun publish(record: LogRecord) { captured += record }
                override fun flush() = Unit
                override fun close() = Unit
            }
            julLogger.useParentHandlers = false
            julLogger.level = Level.ALL
            julLogger.addHandler(handler)
            try {
                JavaUtilLoggingSink.write(DebugLogLevel.ERROR, "test.wp212", category, "value {0} stays")
                JavaUtilLoggingSink.write(DebugLogLevel.DEBUG, "test.wp212", category, "fine line")
            } finally {
                julLogger.removeHandler(handler)
            }
            assertEquals(listOf(Level.SEVERE to "value {0} stays", Level.FINE to "fine line"), captured.map { it.level to it.message })
            assertEquals("value {0} stays", java.util.logging.SimpleFormatter().formatMessage(captured.first()))
        },
    )

    private fun writeAndReadBack(buffer: DebugLogBuffer, attempts: Int = MAX_READ_BACK_ATTEMPTS): Boolean {
        repeat(attempts) {
            DebugLogBuffer.shared = buffer
            if (DebugLogBuffer.shared === buffer) return true
        }
        return false
    }

    private suspend fun pollForMessages(
        store: LogCoreMemoryStore,
        buffer: DebugLogBuffer,
        category: String,
        requiredMessage: String,
    ): List<String> {
        logCorePoll {
            buffer.flush()
            store.debugLogEntries.any { it.category == category && it.message == requiredMessage }
        }
        return store.debugLogEntries.filter { it.category == category }.map { it.message }
    }

    private companion object {
        const val MAX_READ_BACK_ATTEMPTS = 5
    }
}
