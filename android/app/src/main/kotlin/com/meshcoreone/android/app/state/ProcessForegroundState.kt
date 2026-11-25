// PortedFrom: MC1/Services/AppStateProviderImpl.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.app.state

import com.meshcoreone.android.core.contracts.domain.AppStateProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the app is in the foreground (Swift `applicationState != .background`). Android reports it as "at least
 * one activity is started": the application forwards `onActivityStarted`/`onActivityStopped` here, so the answer
 * is a lock-free count and is safe to read from any thread. Configuration changes stop and restart the same
 * activity, so the count dips to zero only when the whole task is truly backgrounded.
 */
class ProcessForegroundState : AppStateProvider {
    private val lock = Any()
    private var startedActivities = 0
    private val foreground = MutableStateFlow(false)

    /** Observable foreground flag (hosting and reconciliation re-evaluate when it changes). */
    val foregroundFlow: StateFlow<Boolean> = foreground.asStateFlow()

    val isForeground: Boolean get() = foreground.value

    override suspend fun isInForeground(): Boolean = isForeground

    /** Returns true when this start moved the process from background to foreground. */
    fun activityStarted(): Boolean = synchronized(lock) {
        startedActivities += 1
        (startedActivities == 1).also { if (it) foreground.value = true }
    }

    /** Returns true when this stop moved the process from foreground to background. */
    fun activityStopped(): Boolean = synchronized(lock) {
        if (startedActivities == 0) return@synchronized false
        startedActivities -= 1
        (startedActivities == 0).also { if (it) foreground.value = false }
    }
}
