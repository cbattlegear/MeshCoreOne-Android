// AndroidOnly: WP-402 Notification actions are requests to the existing connection owner; this layer never owns a connection.
package com.meshcoreone.android.platform.notifications.status

import com.meshcoreone.android.core.contracts.domain.ConnectionController
import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.DisconnectReason
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.cancellation.CancellationException

enum class ConnectionCommand { CONNECT, DISCONNECT }

enum class CommandDisposition {
    /** Forwarded to the connection owner. */
    EXECUTED,

    /** No owner yet (process cold-started by the action): kept until [ConnectionStatusCommandRouter.attach]. */
    QUEUED_COLD_START,

    /** The owner is already in the requested state, or the same command is already running. */
    IGNORED_REDUNDANT,

    /** Connect was requested but no last radio is known. */
    NO_TARGET,

    /** The owner rejected the request; the status notification reflects the owner's own snapshot. */
    FAILED,

    /** A queued command waited past its time-to-live. */
    EXPIRED,
}

/** Resolves the radio a Connect action reconnects to (the persisted last radio); null when none. */
fun interface ConnectionTargetProvider {
    suspend fun lastConnectionTarget(): ConnectionTarget?
}

/**
 * Receiver-side adapter. It holds a reference to the process's single [ConnectionController] (the
 * runtime connection owner) and forwards user intent to it; it constructs no transport, session or
 * second controller. Cold start keeps only the latest pending command (opposite commands cancel
 * each other), for [ttlMillis]. The owner's snapshot decides redundancy, so a tap in the wrong state is a no-op.
 */
class ConnectionStatusCommandRouter(
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    private class Owner(val controller: ConnectionController, val targets: ConnectionTargetProvider)

    private val lock = Any()
    private var owner: Owner? = null
    private var pending: Pair<ConnectionCommand, Long>? = null
    private val running = AtomicBoolean(false)

    val pendingCommand: ConnectionCommand? get() = synchronized(lock) { pending?.first }

    suspend fun dispatch(command: ConnectionCommand): CommandDisposition {
        val current = synchronized(lock) {
            owner ?: run {
                pending = command to clock()
                return CommandDisposition.QUEUED_COLD_START
            }
        }
        return execute(current, command)
    }

    /** Installs the owner and runs the latest queued command, if it has not expired. */
    suspend fun attach(controller: ConnectionController, targets: ConnectionTargetProvider): CommandDisposition? {
        val installed = Owner(controller, targets)
        val queued = synchronized(lock) {
            owner = installed
            pending.also { pending = null }
        } ?: return null
        if (clock() - queued.second > ttlMillis) return CommandDisposition.EXPIRED
        return execute(installed, queued.first)
    }

    fun detach(controller: ConnectionController) {
        synchronized(lock) { if (owner?.controller === controller) owner = null }
    }

    private suspend fun execute(current: Owner, command: ConnectionCommand): CommandDisposition {
        if (!running.compareAndSet(false, true)) return CommandDisposition.IGNORED_REDUNDANT
        try {
            val state = current.controller.snapshot.value.state
            return when (command) {
                ConnectionCommand.CONNECT -> connect(current, state)
                ConnectionCommand.DISCONNECT -> disconnect(current, state)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Connection action failed", failure)
            return CommandDisposition.FAILED
        } finally {
            running.set(false)
        }
    }

    private suspend fun connect(current: Owner, state: DeviceConnectionState): CommandDisposition {
        if (state != DeviceConnectionState.DISCONNECTED) return CommandDisposition.IGNORED_REDUNDANT
        val target = current.targets.lastConnectionTarget() ?: return CommandDisposition.NO_TARGET
        current.controller.connect(target)
        return CommandDisposition.EXECUTED
    }

    private suspend fun disconnect(current: Owner, state: DeviceConnectionState): CommandDisposition {
        // A DISCONNECTED snapshot with a pending reconnect still needs the user's intent recorded, so only a
        // plain disconnected-and-idle owner is redundant.
        if (state == DeviceConnectionState.DISCONNECTED && !current.controller.snapshot.value.intent.wantsConnection) {
            return CommandDisposition.IGNORED_REDUNDANT
        }
        current.controller.disconnect(DisconnectReason.USER_REQUEST)
        return CommandDisposition.EXECUTED
    }

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 120_000L
        private val logger: Logger = Logger.getLogger("com.mc1.ConnectionStatusCommandRouter")
    }
}

/** Process-wide holder so the system-instantiated receiver can reach the router; the app calls `router.attach`. */
object ConnectionStatusCommands {
    val router: ConnectionStatusCommandRouter = ConnectionStatusCommandRouter()
}
