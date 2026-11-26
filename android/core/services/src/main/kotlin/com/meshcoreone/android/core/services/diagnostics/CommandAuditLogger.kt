// PortedFrom: MC1Services/Sources/MC1Services/Services/CommandAuditLogger.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.diagnostics

import com.meshcoreone.android.core.protocol.bytes.Bytes

/**
 * Dedicated logger for command audit trails.
 * Logs all repeater and room commands with consistent formatting.
 *
 * Swift models this as an actor, but it holds no mutable state: every call formats one line and
 * hands it to the thread-safe [PersistentLogger]. The Kotlin port is therefore a plain
 * thread-safe class with synchronous methods; per-caller ordering is preserved because each
 * call records before returning. Swift `Int` parameters map to [Long].
 */
internal class CommandAuditLogger(
    private val logger: PersistentLogger = PersistentLogger(subsystem = SUBSYSTEM, category = CATEGORY),
) {
    /** Direction of the command. */
    enum class Direction(val rawValue: String) {
        OUT("->"),
        IN("<-"),
    }

    /** Target type (repeater or room). */
    enum class Target(val rawValue: String) {
        REPEATER("REPEATER"),
        ROOM("ROOM"),
    }

    // Login/Logout

    /** Log a login request being sent. */
    fun logLoginRequest(target: Target, publicKey: Bytes, pathLength: UByte) {
        logger.info("$PREFIX ${OUT} ${target.rawValue} LOGIN to=${key(publicKey)} pathLen=$pathLength")
    }

    /** Log a successful login response. */
    fun logLoginSuccess(target: Target, publicKey: Bytes, isAdmin: Boolean) {
        logger.info("$PREFIX ${IN} ${target.rawValue} LOGIN_OK from=${key(publicKey)} admin=$isAdmin")
    }

    /** Log a failed login response. */
    fun logLoginFailed(target: Target, publicKey: Bytes, reason: String) {
        logger.warning("$PREFIX ${IN} ${target.rawValue} LOGIN_FAIL from=${key(publicKey)} reason=$reason")
    }

    /** Log a logout request being sent. */
    fun logLogout(target: Target, publicKey: Bytes) {
        logger.info("$PREFIX ${OUT} ${target.rawValue} LOGOUT to=${key(publicKey)}")
    }

    // Status/Telemetry

    /** Log a status request being sent. */
    fun logStatusRequest(target: Target, publicKey: Bytes) {
        logger.info("$PREFIX ${OUT} ${target.rawValue} STATUS_REQ to=${key(publicKey)}")
    }

    /** Log a status response received. */
    fun logStatusResponse(target: Target, publicKey: Bytes, batteryMv: UShort?, uptimeSec: UInt?) {
        val battery = batteryMv?.let { "${it}mV" } ?: NOT_AVAILABLE
        val uptime = uptimeSec?.let { "${it}s" } ?: NOT_AVAILABLE
        logger.info(
            "$PREFIX ${IN} ${target.rawValue} STATUS from=${key(publicKey)} battery=$battery uptime=$uptime",
        )
    }

    /** Log a telemetry request being sent. */
    fun logTelemetryRequest(target: Target, publicKey: Bytes) {
        logger.info("$PREFIX ${OUT} ${target.rawValue} TELEM_REQ to=${key(publicKey)}")
    }

    /** Log a telemetry response received. */
    fun logTelemetryResponse(target: Target, publicKey: Bytes, pointCount: Long) {
        logger.info("$PREFIX ${IN} ${target.rawValue} TELEM from=${key(publicKey)} points=$pointCount")
    }

    // CLI Commands (Repeater)

    /** Log a CLI command being sent (with password redaction). */
    fun logCLICommand(publicKey: Bytes, command: String) {
        val redactedCmd = LogRedaction.cliCommand(command)
        logger.info("$PREFIX ${OUT} REPEATER CLI to=${key(publicKey)} cmd=\"$redactedCmd\"")
    }

    /** Log a CLI response received (full content logged, truncated to 100 characters for readability). */
    fun logCLIResponse(publicKey: Bytes, response: String) {
        val truncated = LogRedaction.truncated(response, CLI_RESPONSE_MAX_CHARACTERS)
        logger.info("$PREFIX ${IN} REPEATER CLI_RESP from=${key(publicKey)} resp=\"$truncated\"")
    }

    // Neighbors (Repeater)

    /** Log a neighbors request being sent. */
    fun logNeighborsRequest(publicKey: Bytes, count: UByte, offset: UShort) {
        logger.info("$PREFIX ${OUT} REPEATER NEIGHBORS_REQ to=${key(publicKey)} count=$count offset=$offset")
    }

    /** Log a neighbors response received. */
    fun logNeighborsResponse(publicKey: Bytes, totalCount: Long, returnedCount: Long) {
        logger.info(
            "$PREFIX ${IN} REPEATER NEIGHBORS from=${key(publicKey)} total=$totalCount returned=$returnedCount",
        )
    }

    // Room Messages (metadata only)

    /** Log a room message being posted (no content, only length). */
    fun logRoomMessagePosted(publicKey: Bytes, messageLength: Long) {
        logger.info("$PREFIX ${OUT} ROOM MSG to=${key(publicKey)} len=$messageLength")
    }

    /** Log a room message received (no content, only metadata). */
    fun logRoomMessageReceived(roomPublicKey: Bytes, authorPrefix: Bytes, messageLength: Long) {
        val authorHex = LogRedaction.hex(authorPrefix)
        logger.info("$PREFIX ${IN} ROOM MSG from=${key(roomPublicKey)} author=$authorHex len=$messageLength")
    }

    // Keep-alive

    /** Log a keep-alive request being sent. */
    fun logKeepAlive(target: Target, publicKey: Bytes) {
        logger.info("$PREFIX ${OUT} ${target.rawValue} KEEPALIVE to=${key(publicKey)}")
    }

    private fun key(publicKey: Bytes): String = LogRedaction.publicKeyHex(publicKey)

    companion object {
        const val SUBSYSTEM = "com.mc1"
        const val CATEGORY = "CommandAudit"
        private const val PREFIX = "[CMD]"
        private const val NOT_AVAILABLE = "n/a"
        private const val CLI_RESPONSE_MAX_CHARACTERS = 100
        private val OUT = Direction.OUT.rawValue
        private val IN = Direction.IN.rawValue
    }
}
