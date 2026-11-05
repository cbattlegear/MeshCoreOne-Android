// AndroidOnly: WP-309 One supervised read-only confirmation monitor per active App radio graph.
package com.meshcoreone.android.feature.chats.actions

import androidx.annotation.MainThread
import com.meshcoreone.android.core.contracts.domain.DirectAckRadioConfirmationEvidenceObserving
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

@MainThread
class DirectAckConfirmationMonitor(
    observer: DirectAckRadioConfirmationEvidenceObserving?,
    observations: Flow<DirectAckConfirmationObservation?>,
    parentScope: CoroutineScope,
    report: (String, Throwable?) -> Unit,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job +
        CoroutineExceptionHandler { _, failure ->
            report("Delivery confirmation observation unavailable", failure)
        })
    val holder = DirectAckConfirmationStateHolder(observer, scope)
    val state get() = holder.state

    init {
        scope.launch {
            try {
                observations.collect { observation ->
                    if (observation == null) holder.invalidate() else holder.observe(observation)
                }
            } finally {
                holder.invalidate()
            }
        }
    }

    fun close() {
        holder.invalidate()
        scope.cancel()
    }
}
