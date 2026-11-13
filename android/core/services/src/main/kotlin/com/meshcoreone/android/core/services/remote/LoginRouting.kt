// AndroidOnly: WP-210 Direct-path-then-flood login routing shared by every login surface (Swift inlines it in NodeAuthenticationSheet only).
package com.meshcoreone.android.core.services.remote

import com.meshcoreone.android.core.protocol.command.PacketBuilder
import kotlinx.coroutines.CancellationException

object LoginRouting {
    /**
     * Runs [attempt] with [pathLength]; when the stored route produces no reply (a [RemoteNodeError.Timeout]), reports
     * [onFallback], resets the radio's path with [resetPath] and runs [attempt] once more with the flood sentinel so the
     * network can find a route. A login already sent flooded, a wrong password and every other failure propagate
     * unchanged, and a failing [resetPath] is reported as itself rather than masked by the timeout.
     */
    suspend fun <T> withFloodFallback(
        pathLength: UByte,
        resetPath: suspend () -> Unit,
        onFallback: suspend () -> Unit = {},
        attempt: suspend (pathLength: UByte) -> T,
    ): T {
        try {
            return attempt(pathLength)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: RemoteNodeError.Timeout) {
            if (pathLength == PacketBuilder.FLOOD_PATH_SENTINEL) throw error
        }
        onFallback()
        resetPath()
        return attempt(PacketBuilder.FLOOD_PATH_SENTINEL)
    }
}
