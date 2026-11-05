// AndroidOnly: WP-309 Shared read-only historical aggregate; never binds evidence to an ordinary timeline DTO.
package com.meshcoreone.android.feature.chats.actions

import androidx.annotation.MainThread
import com.meshcoreone.android.core.contracts.domain.DirectMessageConfirmationEvidence
import com.meshcoreone.android.core.contracts.domain.DirectMessageConfirmationEvidenceInspecting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.RadioId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

sealed interface DirectMessageConfirmationReadStatus {
    data object NoMessages : DirectMessageConfirmationReadStatus
    data object Checking : DirectMessageConfirmationReadStatus
    data object CapabilityUnavailable : DirectMessageConfirmationReadStatus
    data object HistoricalRead : DirectMessageConfirmationReadStatus
    data object Interrupted : DirectMessageConfirmationReadStatus
    data object UnexpectedUnavailable : DirectMessageConfirmationReadStatus
    data class ReadUnavailable(val cause: PersistenceStoreException) : DirectMessageConfirmationReadStatus
}

data class DirectMessageConfirmationAggregateState(
    val observation: DirectAckConfirmationObservation? = null,
    val status: DirectMessageConfirmationReadStatus = DirectMessageConfirmationReadStatus.NoMessages,
    val hasHistoricalUncertainty: Boolean = false,
    val rowDiagnosticsUnavailable: Boolean = false,
)

/**
 * One serial worker over registered loaded outgoing-DM keys, shared by mounted consumers.
 * Present snapshots may establish only a SOME-messages historical warning on this radio.
 * No returned identity/status/reason is ever attached to a rendered DTO: it has no birth tag.
 */
@MainThread
class DirectMessageConfirmationAggregate(
    private val inspector: DirectMessageConfirmationEvidenceInspecting?,
    private val scope: CoroutineScope,
) {
    private val consumers = mutableMapOf<Any, Set<EntityKey>>()
    private val mutableState = MutableStateFlow(DirectMessageConfirmationAggregateState())
    val state = mutableState.asStateFlow()
    private var observation: DirectAckConfirmationObservation? = null
    private var currentKeys = emptySet<EntityKey>()
    private var request: Any? = null
    private var job: Job? = null
    private var historicalRadio: RadioId? = null

    fun observe(owner: DirectAckConfirmationObservation?) {
        if (observation == owner) return
        observation = owner
        mutableState.value = mutableState.value.copy(
            observation = owner,
            hasHistoricalUncertainty = mutableState.value.hasHistoricalUncertainty &&
                (owner == null || historicalRadio == owner.radioId),
        )
        restart()
    }

    fun register(consumer: Any, keys: Set<EntityKey>) {
        if (consumers[consumer] == keys) return
        consumers[consumer] = keys.toSet()
        val combined = consumers.values.flatten().toSet()
        if (currentKeys == combined) return
        currentKeys = combined
        restart()
    }

    fun unregister(consumer: Any) {
        if (consumers.remove(consumer) == null) return
        val combined = consumers.values.flatten().toSet()
        if (currentKeys == combined) return
        currentKeys = combined
        restart()
    }

    fun recheck() { restart() }

    fun invalidate() {
        request = null
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(status = DirectMessageConfirmationReadStatus.Interrupted)
    }

    private fun restart() {
        val ticket = Any()
        request = ticket
        job?.cancel()
        job = null
        val owner = observation
        val keys = currentKeys.filter { it.radioId == owner?.radioId }
        mutableState.value = mutableState.value.copy(
            rowDiagnosticsUnavailable = keys.isNotEmpty(),
            status = when {
                owner == null || keys.isEmpty() -> DirectMessageConfirmationReadStatus.NoMessages
                inspector == null -> DirectMessageConfirmationReadStatus.CapabilityUnavailable
                else -> DirectMessageConfirmationReadStatus.Checking
            },
        )
        val source = inspector ?: return
        if (owner == null || keys.isEmpty() || !scope.isActive) return
        job = scope.launch {
            var handled = false
            try {
                while (true) {
                    for (key in keys) {
                        coroutineContext.ensureActive()
                        if (request !== ticket) return@launch
                        val evidence = source.inspectDirectMessageConfirmationEvidence(key)
                        coroutineContext.ensureActive()
                        if (request !== ticket) return@launch
                        check(evidence.key == key) { "Message confirmation evidence returned a different key" }
                        if (evidence is DirectMessageConfirmationEvidence.Present) {
                            // Even identical DTO fields cannot establish which incarnation is rendered.
                            historicalRadio = owner.radioId
                            mutableState.value = mutableState.value.copy(hasHistoricalUncertainty = true)
                        }
                    }
                    mutableState.value = mutableState.value.copy(
                        status = DirectMessageConfirmationReadStatus.HistoricalRead,
                    )
                    delay(1_000)
                    mutableState.value = mutableState.value.copy(status = DirectMessageConfirmationReadStatus.Checking)
                }
            } catch (cancelled: CancellationException) {
                handled = true
                if (request === ticket) mutableState.value = mutableState.value.copy(
                    status = DirectMessageConfirmationReadStatus.Interrupted,
                )
                throw cancelled
            } catch (failure: PersistenceStoreException) {
                handled = true
                if (request === ticket) mutableState.value = mutableState.value.copy(
                    status = DirectMessageConfirmationReadStatus.ReadUnavailable(failure),
                )
            } finally {
                if (!handled && request === ticket) mutableState.value = mutableState.value.copy(
                    status = DirectMessageConfirmationReadStatus.UnexpectedUnavailable,
                )
            }
        }
    }
}
