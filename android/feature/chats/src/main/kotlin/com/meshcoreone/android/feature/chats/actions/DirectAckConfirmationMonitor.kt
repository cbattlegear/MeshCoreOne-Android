// AndroidOnly: WP-309 One supervised read-only confirmation monitor per active App radio graph.
package com.meshcoreone.android.feature.chats.actions

import androidx.annotation.MainThread
import com.meshcoreone.android.core.contracts.domain.DirectAckRadioConfirmationEvidenceObserving
import com.meshcoreone.android.core.contracts.domain.DirectMessageConfirmationEvidenceInspecting
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
    private val observations: Flow<DirectAckConfirmationObservation?>,
    parentScope: CoroutineScope,
    report: (String, Throwable?) -> Unit,
    messageInspector: DirectMessageConfirmationEvidenceInspecting? = null,
) {
    private val job = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + job +
        CoroutineExceptionHandler { _, failure ->
            report("Delivery confirmation observation unavailable", failure)
        })
    val holder = DirectAckConfirmationStateHolder(observer, scope)
    val messages = DirectMessageConfirmationAggregate(messageInspector, scope)
    val state get() = holder.state
    private var binding: Job? = null

    fun start() {
        if (binding?.isActive == true || !job.isActive) return
        binding = scope.launch {
            try {
                observations.collect { observation ->
                    if (observation == null) holder.invalidate() else holder.observe(observation)
                    messages.observe(observation)
                }
            } finally {
                holder.invalidate()
                messages.invalidate()
            }
        }
    }

    fun close() {
        holder.invalidate()
        messages.invalidate()
        scope.cancel()
    }
}
