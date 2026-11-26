// PortedFrom: MC1Services/Sources/MC1Services/Services/CommandAuditLogger.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native coverage: the Swift source has no dedicated test; every format string is pinned here.
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.diagnostics.CommandAuditLogger.Target
import java.util.UUID
import kotlin.test.assertEquals
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class CommandAuditLoggerTest {
    private class AuditFixture {
        val sink = LogCoreRecordingSink()
        val recorded = mutableListOf<DebugLogEntryDTO>()
        val audit = CommandAuditLogger(
            PersistentLogger(CommandAuditLogger.SUBSYSTEM, CommandAuditLogger.CATEGORY, sink, LogCoreManualClock()) { recorded += it },
        )

        /** Asserts exactly one line was written and persisted with [level] and [message]. */
        fun expect(level: DebugLogLevel, message: String) {
            assertEquals(listOf(LogCoreSinkLine(level, "com.mc1", "CommandAudit", message)), sink.lines)
            val entry = recorded.single()
            assertEquals(level, entry.level)
            assertEquals("com.mc1", entry.subsystem)
            assertEquals("CommandAudit", entry.category)
            assertEquals(message, entry.message)
        }
    }

    private fun format(name: String, level: DebugLogLevel, expected: String, call: CommandAuditLogger.() -> Unit): DynamicTest =
        logCoreNative("CommandAuditLogger $name") {
            val fixture = AuditFixture()
            fixture.audit.call()
            fixture.expect(level, expected)
        }

    @TestFactory
    fun formatCases(): List<DynamicTest> = listOf(
        format("login request repeater", DebugLogLevel.INFO, "[CMD] -> REPEATER LOGIN to=abcdef01020a pathLen=255") {
            logLoginRequest(Target.REPEATER, KEY, 255u)
        },
        format("login request room", DebugLogLevel.INFO, "[CMD] -> ROOM LOGIN to=abcdef01020a pathLen=0") {
            logLoginRequest(Target.ROOM, KEY, 0u)
        },
        format("login success admin", DebugLogLevel.INFO, "[CMD] <- REPEATER LOGIN_OK from=abcdef01020a admin=true") {
            logLoginSuccess(Target.REPEATER, KEY, isAdmin = true)
        },
        format("login success guest", DebugLogLevel.INFO, "[CMD] <- ROOM LOGIN_OK from=abcdef01020a admin=false") {
            logLoginSuccess(Target.ROOM, KEY, isAdmin = false)
        },
        format("login failed is a warning", DebugLogLevel.WARNING, "[CMD] <- ROOM LOGIN_FAIL from=abcdef01020a reason=timeout") {
            logLoginFailed(Target.ROOM, KEY, reason = "timeout")
        },
        format("logout", DebugLogLevel.INFO, "[CMD] -> REPEATER LOGOUT to=abcdef01020a") {
            logLogout(Target.REPEATER, KEY)
        },
        format("status request", DebugLogLevel.INFO, "[CMD] -> ROOM STATUS_REQ to=abcdef01020a") {
            logStatusRequest(Target.ROOM, KEY)
        },
        format("status response with values", DebugLogLevel.INFO,
            "[CMD] <- REPEATER STATUS from=abcdef01020a battery=65535mV uptime=4294967295s") {
            logStatusResponse(Target.REPEATER, KEY, batteryMv = UShort.MAX_VALUE, uptimeSec = UInt.MAX_VALUE)
        },
        format("status response without values", DebugLogLevel.INFO, "[CMD] <- ROOM STATUS from=abcdef01020a battery=n/a uptime=n/a") {
            logStatusResponse(Target.ROOM, KEY, batteryMv = null, uptimeSec = null)
        },
        format("status response with zero values", DebugLogLevel.INFO, "[CMD] <- ROOM STATUS from=abcdef01020a battery=0mV uptime=0s") {
            logStatusResponse(Target.ROOM, KEY, batteryMv = 0u, uptimeSec = 0u)
        },
        format("telemetry request", DebugLogLevel.INFO, "[CMD] -> REPEATER TELEM_REQ to=abcdef01020a") {
            logTelemetryRequest(Target.REPEATER, KEY)
        },
        format("telemetry response", DebugLogLevel.INFO, "[CMD] <- ROOM TELEM from=abcdef01020a points=3") {
            logTelemetryResponse(Target.ROOM, KEY, pointCount = 3)
        },
        format("CLI command redacts passwords", DebugLogLevel.INFO,
            "[CMD] -> REPEATER CLI to=abcdef01020a cmd=\"set password [REDACTED]\"") {
            logCLICommand(KEY, "set password hunter2")
        },
        format("CLI command passes plain commands", DebugLogLevel.INFO, "[CMD] -> REPEATER CLI to=abcdef01020a cmd=\"get name\"") {
            logCLICommand(KEY, "get name")
        },
        format("CLI command truncates past 40 characters", DebugLogLevel.INFO,
            "[CMD] -> REPEATER CLI to=abcdef01020a cmd=\"${"c".repeat(40)}...\"") {
            logCLICommand(KEY, "c".repeat(41))
        },
        format("CLI response short", DebugLogLevel.INFO, "[CMD] <- REPEATER CLI_RESP from=abcdef01020a resp=\"OK\"") {
            logCLIResponse(KEY, "OK")
        },
        format("CLI response exactly 100 characters is kept", DebugLogLevel.INFO,
            "[CMD] <- REPEATER CLI_RESP from=abcdef01020a resp=\"${"r".repeat(100)}\"") {
            logCLIResponse(KEY, "r".repeat(100))
        },
        format("CLI response over 100 characters is truncated", DebugLogLevel.INFO,
            "[CMD] <- REPEATER CLI_RESP from=abcdef01020a resp=\"${"r".repeat(100)}...\"") {
            logCLIResponse(KEY, "r".repeat(101))
        },
        format("CLI response truncation counts grapheme clusters", DebugLogLevel.INFO,
            "[CMD] <- REPEATER CLI_RESP from=abcdef01020a resp=\"${FLAG.repeat(100)}...\"") {
            logCLIResponse(KEY, FLAG.repeat(101))
        },
        format("CLI response passwords are not redacted (Swift logs full content)", DebugLogLevel.INFO,
            "[CMD] <- REPEATER CLI_RESP from=abcdef01020a resp=\"password secret\"") {
            logCLIResponse(KEY, "password secret")
        },
        format("neighbors request", DebugLogLevel.INFO, "[CMD] -> REPEATER NEIGHBORS_REQ to=abcdef01020a count=255 offset=65535") {
            logNeighborsRequest(KEY, count = 255u, offset = 65535u)
        },
        format("neighbors response", DebugLogLevel.INFO, "[CMD] <- REPEATER NEIGHBORS from=abcdef01020a total=12 returned=0") {
            logNeighborsResponse(KEY, totalCount = 12, returnedCount = 0)
        },
        format("room message posted", DebugLogLevel.INFO, "[CMD] -> ROOM MSG to=abcdef01020a len=140") {
            logRoomMessagePosted(KEY, messageLength = 140)
        },
        format("room message received prints the full author prefix", DebugLogLevel.INFO,
            "[CMD] <- ROOM MSG from=abcdef01020a author=00ff10a0b0c0d0e0 len=-1") {
            logRoomMessageReceived(KEY, Bytes.of(0x00, 0xFF, 0x10, 0xA0, 0xB0, 0xC0, 0xD0, 0xE0), messageLength = -1)
        },
        format("keep-alive repeater", DebugLogLevel.INFO, "[CMD] -> REPEATER KEEPALIVE to=abcdef01020a") {
            logKeepAlive(Target.REPEATER, KEY)
        },
        format("keep-alive room", DebugLogLevel.INFO, "[CMD] -> ROOM KEEPALIVE to=abcdef01020a") {
            logKeepAlive(Target.ROOM, KEY)
        },
        format("short public key prints every byte it has", DebugLogLevel.INFO, "[CMD] -> ROOM KEEPALIVE to=0a0b") {
            logKeepAlive(Target.ROOM, Bytes.of(0x0A, 0x0B))
        },
    )

    @TestFactory
    fun shapeCases(): List<DynamicTest> = listOf(
        logCoreNative("CommandAuditLogger direction and target raw values match Swift") {
            assertEquals("->", CommandAuditLogger.Direction.OUT.rawValue)
            assertEquals("<-", CommandAuditLogger.Direction.IN.rawValue)
            assertEquals("REPEATER", Target.REPEATER.rawValue)
            assertEquals("ROOM", Target.ROOM.rawValue)
        },
        logCoreNative("CommandAuditLogger default logger persists through the hub as com.mc1/CommandAudit") {
            DebugLogBuffer.shared = null
            DebugLogBuffer.resetPendingStateForTesting()
            try {
                withLogCoreScope { scope ->
                    val store = LogCoreMemoryStore()
                    val buffer = DebugLogBuffer(store, scope, LogCoreManualClock(), LogCoreRecordingSink())
                    val marker = UUID.randomUUID().toString()
                    CommandAuditLogger().logLoginFailed(Target.REPEATER, KEY, reason = marker)
                    assertEquals(1, DebugLogBuffer.pendingCountForTesting)
                    DebugLogBuffer.shared = buffer
                    buffer.flush()
                    val entry = store.debugLogEntries.single { it.message.endsWith(marker) }
                    assertEquals("com.mc1", entry.subsystem)
                    assertEquals("CommandAudit", entry.category)
                    assertEquals(DebugLogLevel.WARNING, entry.level)
                }
            } finally {
                DebugLogBuffer.shared = null
                DebugLogBuffer.resetPendingStateForTesting()
            }
        },
    )

    private companion object {
        val KEY = Bytes.of(0xAB, 0xCD, 0xEF, 0x01, 0x02, 0x0A, 0xFF, 0xEE, 0xDD)
        const val FLAG = "🇺🇸"
    }
}
