// AndroidOnly: WP-309 Read-only, radio-scoped presentation of persisted direct-ACK uncertainty.
package com.meshcoreone.android.feature.chats.actions

import androidx.annotation.MainThread
import com.meshcoreone.android.core.contracts.domain.DirectAckRadioConfirmationEvidence
import com.meshcoreone.android.core.contracts.domain.DirectAckRadioConfirmationEvidenceObserving
import com.meshcoreone.android.core.contracts.domain.DirectAckRadioConfirmationObservation
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * The graph owner advances observationEpoch on observer/lifetime replacement.
 * Observation is eventual snapshot polling, never settlement authority or synchronous freshness.
 */
data class DirectAckConfirmationObservation(
    val radioId: RadioId,
    val sessionToken: SessionToken?,
    val observationEpoch: Long,
) {
    init {
        require(sessionToken == null || sessionToken.radioId == radioId)
        require(observationEpoch >= 0)
    }
}

sealed interface DirectAckConfirmationStatus {
    data object NotInspected : DirectAckConfirmationStatus
    data object Checking : DirectAckConfirmationStatus
    data object CapabilityUnavailable : DirectAckConfirmationStatus
    data class ReadUnavailable(val failure: PersistenceStoreException) : DirectAckConfirmationStatus
    data class RadioMismatch(val receivedRadioId: RadioId) : DirectAckConfirmationStatus
    data object Interrupted : DirectAckConfirmationStatus
    data object StreamEnded : DirectAckConfirmationStatus
    data object UnexpectedUnavailable : DirectAckConfirmationStatus
    data class Inspected(val evidence: DirectAckRadioConfirmationEvidence) : DirectAckConfirmationStatus
}

data class DirectAckConfirmationState(
    val observation: DirectAckConfirmationObservation?,
    val status: DirectAckConfirmationStatus,
    val lastEvidence: DirectAckRadioConfirmationEvidence? = null,
    val isActive: Boolean = false,
) {
    val lastKnownPersistedWildcard: DirectAckRadioConfirmationEvidence.PersistedWildcard?
        get() = lastEvidence as? DirectAckRadioConfirmationEvidence.PersistedWildcard
}

/**
 * One monitor per active radio graph, shared by its UI consumers and confined to its main dispatcher.
 * The owner observes on activation/replacement and invalidates on stop/close; routes only collect.
 */
@MainThread
class DirectAckConfirmationStateHolder(
    private val observer: DirectAckRadioConfirmationEvidenceObserving?,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(
        DirectAckConfirmationState(null, DirectAckConfirmationStatus.NotInspected),
    )
    val state = mutableState.asStateFlow()
    private var request: Any? = null
    private var job: Job? = null

    fun observe(observation: DirectAckConfirmationObservation) {
        if (mutableState.value.observation == observation && job?.isActive == true) return
        start(observation)
    }

    fun recheck() {
        if (!mutableState.value.isActive) return
        val observation = mutableState.value.observation ?: return
        start(observation)
    }

    private fun start(observation: DirectAckConfirmationObservation) {
        if (!scope.isActive) {
            invalidate()
            return
        }
        val ticket = Any()
        request = ticket
        job?.cancel()
        job = null
        val historical = mutableState.value.lastKnownPersistedWildcard?.takeIf {
            it.radioId == observation.radioId
        }
        mutableState.value = DirectAckConfirmationState(
            observation, DirectAckConfirmationStatus.Checking, historical, isActive = true,
        )
        val source = observer
        if (source == null) {
            mutableState.value = mutableState.value.copy(status = DirectAckConfirmationStatus.CapabilityUnavailable)
            return
        }
        job = scope.launch {
            var handled = false
            try {
                var mismatched = false
                source.observeDirectAckRadioConfirmationEvidence(observation.radioId).takeWhile { event ->
                    coroutineContext.ensureActive()
                    if (request !== ticket) return@takeWhile false
                    if (event.radioId != observation.radioId) {
                        mismatched = true
                        mutableState.value = mutableState.value.copy(
                            status = DirectAckConfirmationStatus.RadioMismatch(event.radioId),
                        )
                        false
                    } else true
                }.collect { event ->
                    when (event) {
                        is DirectAckRadioConfirmationObservation.Inspecting -> {
                            mutableState.value = mutableState.value.copy(status = DirectAckConfirmationStatus.Checking)
                        }
                        is DirectAckRadioConfirmationObservation.Snapshot -> {
                            mutableState.value = DirectAckConfirmationState(
                                observation,
                                DirectAckConfirmationStatus.Inspected(event.evidence),
                                event.evidence,
                                isActive = true,
                            )
                        }
                    }
                }
                if (request === ticket && !mismatched) {
                    mutableState.value = mutableState.value.copy(status = DirectAckConfirmationStatus.StreamEnded)
                }
                handled = true
            } catch (cancelled: CancellationException) {
                handled = true
                if (request === ticket) {
                    mutableState.value = mutableState.value.copy(
                        status = DirectAckConfirmationStatus.Interrupted, isActive = scope.isActive,
                    )
                }
                throw cancelled
            } catch (failure: PersistenceStoreException) {
                handled = true
                if (request === ticket) {
                    mutableState.value = mutableState.value.copy(status = DirectAckConfirmationStatus.ReadUnavailable(failure))
                }
            } finally {
                // Unknown causes still escape unchanged to the owner's uncaught handler, never stale clear UI.
                if (!handled && request === ticket) {
                    mutableState.value = mutableState.value.copy(
                        status = DirectAckConfirmationStatus.UnexpectedUnavailable, isActive = scope.isActive,
                    )
                }
            }
        }
    }

    fun invalidate() {
        request = null
        job?.cancel()
        job = null
        mutableState.value = mutableState.value.copy(
            status = DirectAckConfirmationStatus.Interrupted, isActive = false,
        )
    }
}
