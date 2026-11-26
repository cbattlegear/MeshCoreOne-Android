// PortedFrom: MC1/Views/Tools/CLI/CLIOutputLine.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1/Views/Tools/CLI/CLISession.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.tools.diagnostics.cli

import com.meshcoreone.android.core.contracts.domain.EntityKey
import java.time.Instant
import java.util.UUID

/** Terminal line kinds; the UI layer maps each to the source colors (secondary/green/orange/primary). */
enum class CliOutputType {
    /** User-entered command (echoed). */
    COMMAND,

    /** Success/acknowledgment. */
    SUCCESS,

    /** Error message. */
    ERROR,

    /** Node response data. */
    RESPONSE,
}

/** One immutable terminal line; [id] is unique within its terminal (Swift used a fresh UUID). */
data class CliOutputLine(val id: Long, val text: String, val type: CliOutputType, val timestamp: Instant)

/**
 * A CLI session. Local sessions get a fresh id each time they are created (as in Swift); remote
 * sessions carry the remote-node session key used by the service ports.
 */
data class CliSession(
    val id: UUID,
    val name: String,
    val isLocal: Boolean,
    val pathLength: UByte,
    val remoteKey: EntityKey? = null,
) {
    companion object {
        fun local(deviceName: String): CliSession = CliSession(UUID.randomUUID(), deviceName, isLocal = true, pathLength = 0u)

        fun remote(key: EntityKey, name: String, pathLength: UByte): CliSession =
            CliSession(key.id, name, isLocal = false, pathLength = pathLength, remoteKey = key)
    }
}
