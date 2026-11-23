// AndroidOnly: WP-210 Keep automatic path recovery bound to both the caller and the radio generation.
package com.meshcoreone.android.core.services.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class RemotePathRecoveryOwner(private val isCurrent: () -> Boolean) {
    suspend fun requireCurrent() {
        currentCoroutineContext().ensureActive()
        if (!isCurrent()) throw CancellationException("Remote path recovery owner was retired")
    }
}

internal suspend fun <T> CoroutineScope.runRemotePathRecovery(
    isCurrent: () -> Boolean,
    recovery: suspend RemotePathRecoveryOwner.() -> T,
): T {
    val caller = currentCoroutineContext()
    caller.ensureActive()
    val owner = RemotePathRecoveryOwner {
        // The requesting coroutine may cancel independently of the connection.
        caller.ensureActive()
        isCurrent()
    }
    val task = async { owner.recovery() }
    return try {
        task.await()
    } finally {
        task.cancel()
    }
}
