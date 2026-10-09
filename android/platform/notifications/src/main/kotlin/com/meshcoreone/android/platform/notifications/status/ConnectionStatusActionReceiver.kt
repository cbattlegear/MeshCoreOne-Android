// AndroidOnly: WP-402 Receives the notification's Connect/Disconnect taps and forwards them to the connection owner.
package com.meshcoreone.android.platform.notifications.status

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Register not exported. It performs no radio or database work itself. */
open class ConnectionStatusActionReceiver : BroadcastReceiver() {
    protected open val router: ConnectionStatusCommandRouter get() = ConnectionStatusCommands.router

    override fun onReceive(context: Context, intent: Intent) {
        val command = parse(intent) ?: return
        val pending = goAsync()
        EXECUTOR.execute {
            val finish = Continuation<CommandDisposition>(EmptyCoroutineContext) { result ->
                result.exceptionOrNull()?.let { logger.log(Level.WARNING, "Connection status action failed", it) }
                pending.finish()
            }
            suspend { router.dispatch(command) }.startCoroutine(finish)
        }
    }

    companion object {
        const val ACTION_CONNECT = "com.meshcoreone.android.notifications.CONNECTION_CONNECT"
        const val ACTION_DISCONNECT = "com.meshcoreone.android.notifications.CONNECTION_DISCONNECT"
        private val logger: Logger = Logger.getLogger("com.mc1.ConnectionStatusActionReceiver")
        private val EXECUTOR = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "mc1-connection-actions").apply { isDaemon = true }
        }

        /** Null for any other action; nothing is dispatched for those. */
        fun parse(intent: Intent): ConnectionCommand? = when (intent.action) {
            ACTION_CONNECT -> ConnectionCommand.CONNECT
            ACTION_DISCONNECT -> ConnectionCommand.DISCONNECT
            else -> null
        }
    }
}
