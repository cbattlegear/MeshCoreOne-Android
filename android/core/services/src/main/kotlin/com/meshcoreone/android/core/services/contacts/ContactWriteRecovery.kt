// AndroidOnly: WP-209 Distinguishes radio acceptance from incomplete local contact accounting.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatch
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatchOutcome
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The radio accepted this write; a full contact sync is required, not a claimed rollback.
 * A null cause means atomic persistence retained a newer local frame instead of this one.
 */
class ContactWriteCompletionFailed(
    val radioId: RadioId,
    val publicKey: Bytes,
    cause: Exception? = null,
) : Exception("Contact accepted by radio; local completion requires reconciliation", cause)

enum class ContactWriteAdmissionBlockReason {
    RECONCILIATION_REQUIRED,
    SYNC_IN_PROGRESS,
    PREVIOUS_WRITE_UNRESOLVED,
}

/** The write was rejected before dispatch; no radio-side result is being claimed. */
class ContactWriteAdmissionBlocked(
    val radioId: RadioId,
    val reason: ContactWriteAdmissionBlockReason,
) : Exception("Contact write was not dispatched: $reason")

/**
 * Preferences survive graph replacement; epochs prevent an older snapshot from clearing a newer
 * uncertainty. Per-radio fences cover both the serialized write admission and the complete local
 * snapshot-application tail. The weak key is the process-owned preference role.
 */
internal object ContactWriteRecovery {
    private class RadioState {
        var epoch: Any? = null
        var epochProvenanceReadPending = false
        var epochLoadedWithoutProvenance = false
        var uncertaintyWithoutDispatch = false
        var markerRecordingPending = false
        var markerClearingPending = false
        var markerClearRetryPending = false
        var syncing = false
        val syncMutex = Mutex()
        val writes = linkedSetOf<ContactWriteDispatch>()
        val dispatchingWrites = linkedSetOf<ContactWriteDispatch>()
        val finishedWrites = linkedSetOf<ContactWriteDispatch>()
        val invalidatedWrites = linkedSetOf<ContactWriteDispatch>()
        val locallyCompletedWrites = linkedSetOf<ContactWriteDispatch>()
        val reportedUncertainties = linkedSetOf<ContactWriteDispatch>()
        val pendingUncertainties = linkedSetOf<ContactWriteDispatch>()
        val settledUncertainties = linkedSetOf<ContactWriteDispatch>()
        // A delayed caller/failure report may arrive after all strong receipt references retire.
        val provenDispatches = WeakHashMap<ContactWriteDispatch, Unit>()
    }

    private class Subscriber(
        val channel: Channel<ContactWriteRecoveryState>,
        var lastState: ContactWriteRecoveryState,
    )

    private data class Subscription(
        val initialState: ContactWriteRecoveryState,
        val subscriber: Subscriber,
    )

    private data class Publication(
        val subscriber: Subscriber,
        val state: ContactWriteRecoveryState,
    )

    private val states = WeakHashMap<ContactPreferenceFlags, MutableMap<RadioId, RadioState>>()
    private val subscribers =
        WeakHashMap<ContactPreferenceFlags, MutableMap<RadioId, MutableSet<Subscriber>>>()
    private val pendingPublications = java.util.ArrayDeque<Publication>()
    private val publicationDrainActive = AtomicBoolean()

    fun updates(preferences: ContactPreferenceFlags, radioId: RadioId): Flow<ContactWriteRecoveryState> = flow {
        val subscription = synchronized(this@ContactWriteRecovery) {
            val initial = readOnlyState(preferences, radioId, state(preferences, radioId))
            val subscriber = Subscriber(Channel(Channel.UNLIMITED), initial)
            subscribers.getOrPut(preferences) { mutableMapOf() }
                .getOrPut(radioId) { linkedSetOf() }
                .add(subscriber)
            Subscription(initial, subscriber)
        }
        try {
            emit(subscription.initialState)
            for (update in subscription.subscriber.channel) emit(update)
        } finally {
            synchronized(this@ContactWriteRecovery) {
                subscribers[preferences]?.get(radioId)?.let { radioSubscribers ->
                    radioSubscribers.remove(subscription.subscriber)
                    if (radioSubscribers.isEmpty()) subscribers[preferences]?.remove(radioId)
                    if (subscribers[preferences].isNullOrEmpty()) subscribers.remove(preferences)
                }
                pendingPublications.removeIf { it.subscriber === subscription.subscriber }
            }
            subscription.subscriber.channel.close()
        }
    }

    fun observe(preferences: ContactPreferenceFlags, radioId: RadioId): ContactWriteRecoveryState = mutate {
        val observed = readOnlyState(preferences, radioId, state(preferences, radioId))
        publish(preferences, observed)
        observed
    }

    fun outcomeChanged(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) = mutate {
        val state = state(preferences, radioId)
        if (dispatch in state.writes || dispatch in state.pendingUncertainties ||
            dispatch in state.reportedUncertainties
        ) publishTrackedState(preferences, radioId, state)
    }

    fun snapshot(preferences: ContactPreferenceFlags, radioId: RadioId): Any? = mutate {
        val state = state(preferences, radioId)
        loadEpoch(preferences, radioId, state)
    }

    suspend fun <T> withSyncFence(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        operation: suspend () -> T,
    ): T {
        val state = synchronized(this) { state(preferences, radioId) }
        return state.syncMutex.withLock {
            try {
                synchronized(this) {
                    loadEpoch(preferences, radioId, state)
                    if (state.epoch != null || hasUnresolvedWrite(state)) throw ContactServiceError.SyncInterrupted()
                    state.syncing = true
                    state.writes.filterNot { it in state.dispatchingWrites }.forEach {
                        state.invalidatedWrites.add(it)
                    }
                }
            } finally {
                flushPublications()
            }
            try {
                operation()
            } finally {
                synchronized(this) { state.syncing = false }
            }
        }
    }

    suspend fun withContactMutationFence(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        shouldMutate: suspend () -> Boolean,
        operation: suspend () -> Unit,
    ) {
        val state = synchronized(this) { state(preferences, radioId) }
        state.syncMutex.withLock {
            // Protected-contact no-ops must not invalidate queued writes or claim the sync fence.
            if (!shouldMutate()) return@withLock
            try {
                synchronized(this) {
                    loadEpoch(preferences, radioId, state)
                    if (state.epoch != null || state.dispatchingWrites.isNotEmpty() || hasUnresolvedWrite(state)) {
                        throw ContactServiceError.SyncInterrupted()
                    }
                    state.syncing = true
                    state.writes.filterNot { it in state.dispatchingWrites }.forEach {
                        state.invalidatedWrites.add(it)
                    }
                }
            } finally {
                flushPublications()
            }
            try {
                operation()
            } finally {
                synchronized(this) { state.syncing = false }
            }
        }
    }

    fun registerWrite(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) = mutate {
        val state = state(preferences, radioId)
        blockReason(preferences, radioId, state)?.let { throw ContactWriteAdmissionBlocked(radioId, it) }
        state.writes += dispatch
    }

    fun beforeDispatch(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) = mutate {
        val state = state(preferences, radioId)
        loadEpoch(preferences, radioId, state)
        if (dispatch in state.invalidatedWrites) {
            throw ContactWriteAdmissionBlocked(radioId, ContactWriteAdmissionBlockReason.SYNC_IN_PROGRESS)
        }
        blockReason(preferences, radioId, state, dispatch)?.let { throw ContactWriteAdmissionBlocked(radioId, it) }
        state.dispatchingWrites += dispatch
    }

    fun completeWrite(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) = mutate {
        val state = state(preferences, radioId)
        state.locallyCompletedWrites += dispatch
        publishTrackedState(preferences, radioId, state)
    }

    fun abandonDefinitelyUnsent(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) = mutate {
        if (dispatch.outcome == ContactWriteDispatchOutcome.DEFINITELY_UNSENT) {
            val state = state(preferences, radioId)
            if (dispatch !in state.settledUncertainties) {
                if (dispatch !in state.pendingUncertainties && dispatch !in state.reportedUncertainties) {
                    abandon(state, dispatch)
                }
                publishTrackedState(preferences, radioId, state)
            }
        }
    }

    fun retryExistingRecovery(preferences: ContactPreferenceFlags, radioId: RadioId): Boolean = mutate {
        val state = state(preferences, radioId)
        loadEpoch(preferences, radioId, state) != null
    }

    fun finishWrite(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        dispatch: ContactWriteDispatch,
        requireReconciliation: (Exception) -> Unit,
    ) {
        var recoveryFailure: Exception? = null
        synchronized(this) {
            val state = state(preferences, radioId)
            state.dispatchingWrites.remove(dispatch)
            state.finishedWrites += dispatch
            publishTrackedState(preferences, radioId, state)
            val uncertainty = when (dispatch.outcome) {
                ContactWriteDispatchOutcome.DEFINITELY_UNSENT,
                ContactWriteDispatchOutcome.REJECTED -> {
                    state.provenDispatches[dispatch] = Unit
                    val settled = try {
                        settleProvenDispatch(preferences, radioId, state, dispatch)
                    } catch (failure: Exception) {
                        recoveryFailure = failure
                        true
                    }
                    if (!settled) {
                        abandon(state, dispatch)
                    }
                    null
                }
                ContactWriteDispatchOutcome.ACCEPTED -> {
                    val locallyCompleted = state.locallyCompletedWrites.remove(dispatch)
                    if (locallyCompleted) {
                        state.provenDispatches[dispatch] = Unit
                        if (dispatch in state.reportedUncertainties || dispatch in state.pendingUncertainties) {
                            try {
                                settleProvenDispatch(preferences, radioId, state, dispatch)
                            } catch (failure: Exception) {
                                recoveryFailure = failure
                            }
                        } else {
                            abandon(state, dispatch)
                        }
                        null
                    } else {
                        state.writes += dispatch
                        dispatch.terminalFailure ?: java.util.concurrent.CancellationException(
                            "Contact write was canceled or incompletely accounted",
                        )
                    }
                }
                ContactWriteDispatchOutcome.UNKNOWN,
                ContactWriteDispatchOutcome.MAY_HAVE_WRITTEN -> {
                    state.writes += dispatch
                    dispatch.terminalFailure ?: IllegalStateException("Contact write outcome is unknown")
                }
            }
            recoveryFailure = recoveryFailure ?: uncertainty
        }
        flushPublications()
        recoveryFailure?.let(requireReconciliation)
    }

    @Synchronized
    fun isWriteTracked(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch): Boolean =
        dispatch in state(preferences, radioId).writes

    fun abandonWrite(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) = mutate {
        val state = state(preferences, radioId)
        if (dispatch !in state.settledUncertainties) {
            if (dispatch !in state.pendingUncertainties && dispatch !in state.reportedUncertainties) {
                abandon(state, dispatch)
            }
            publishTrackedState(preferences, radioId, state)
        }
    }

    fun require(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch? = null) = mutate {
        val state = state(preferences, radioId)
        val alreadyKnown = dispatch != null && (
            dispatch in state.pendingUncertainties || dispatch in state.reportedUncertainties ||
                dispatch in state.provenDispatches
            )
        if (alreadyKnown) {
            if (state.epoch != null) loadEpoch(preferences, radioId, state)
            return@mutate
        }
        // Register before reading preferences or retrying an older receipt: either can fail.
        if (state.epoch == null) state.epochProvenanceReadPending = true
        state.epoch = Any()
        state.markerRecordingPending = true
        if (dispatch != null) {
            state.pendingUncertainties.add(dispatch)
        } else {
            state.uncertaintyWithoutDispatch = true
        }
        publishTrackedState(preferences, radioId, state)
        loadEpoch(preferences, radioId, state)
    }

    fun reconciled(preferences: ContactPreferenceFlags, radioId: RadioId, epoch: Any): Boolean = mutate {
        val state = state(preferences, radioId)
        if (state.epoch !== epoch || hasUnresolvedWrite(state)) return@mutate false
        recordPendingMarker(preferences, radioId, state)
        clearMarker(preferences, radioId, state)
    }

    private fun blockReason(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        state: RadioState,
        dispatch: ContactWriteDispatch? = null,
    ): ContactWriteAdmissionBlockReason? {
        loadEpoch(preferences, radioId, state)
        return when {
            state.syncing -> ContactWriteAdmissionBlockReason.SYNC_IN_PROGRESS
            state.epoch != null -> ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED
            dispatch != null && state.writes.any {
                it !== dispatch && (it in state.dispatchingWrites || it in state.finishedWrites) &&
                    it.outcome != ContactWriteDispatchOutcome.DEFINITELY_UNSENT &&
                    it.outcome != ContactWriteDispatchOutcome.REJECTED
            } -> ContactWriteAdmissionBlockReason.PREVIOUS_WRITE_UNRESOLVED
            else -> null
        }
    }

    private fun loadEpoch(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        state: RadioState,
    ): Any? {
        if (state.epochProvenanceReadPending) {
            if (preferences.bool(key(radioId))) state.epochLoadedWithoutProvenance = true
            state.epochProvenanceReadPending = false
            publishTrackedState(preferences, radioId, state)
        } else if (state.epoch == null && preferences.bool(key(radioId))) {
            state.epoch = Any()
            state.epochLoadedWithoutProvenance = true
            publishTrackedState(preferences, radioId, state)
        }
        if (clearSettledEpoch(preferences, radioId, state)) return null
        recordPendingMarker(preferences, radioId, state)
        return state.epoch
    }

    private fun settleProvenDispatch(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        state: RadioState,
        dispatch: ContactWriteDispatch,
    ): Boolean {
        if (dispatch !in state.reportedUncertainties && dispatch !in state.pendingUncertainties) return false
        state.settledUncertainties += dispatch
        state.writes.remove(dispatch)
        state.dispatchingWrites.remove(dispatch)
        state.finishedWrites.remove(dispatch)
        state.invalidatedWrites.remove(dispatch)
        if (!clearSettledEpoch(preferences, radioId, state)) {
            publishTrackedState(preferences, radioId, state)
        }
        return true
    }

    private fun clearSettledEpoch(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        state: RadioState,
    ): Boolean {
        if (state.epoch == null || state.epochProvenanceReadPending || state.epochLoadedWithoutProvenance ||
            state.uncertaintyWithoutDispatch || state.syncing
        ) return false
        val uncertainties = state.pendingUncertainties + state.reportedUncertainties
        if (uncertainties.isEmpty() || uncertainties.any { it !in state.settledUncertainties } ||
            hasUnresolvedWrite(state)
        ) return false
        recordPendingMarker(preferences, radioId, state)
        return clearMarker(preferences, radioId, state)
    }

    private fun clearMarker(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        state: RadioState,
    ): Boolean {
        state.markerClearingPending = true
        state.markerClearRetryPending = false
        try {
            preferences.set(key(radioId), false)
        } catch (failure: Exception) {
            state.markerClearRetryPending = true
            publishTrackedState(preferences, radioId, state)
            throw failure
        }
        clearRecoveryState(state)
        publishClear(preferences, radioId)
        return true
    }

    private fun clearRecoveryState(state: RadioState) {
        val owners = state.pendingUncertainties + state.reportedUncertainties
        owners.forEach {
            state.provenDispatches[it] = Unit
            abandon(state, it)
        }
        state.markerRecordingPending = false
        state.markerClearingPending = false
        state.markerClearRetryPending = false
        state.epoch = null
        state.epochProvenanceReadPending = false
        state.epochLoadedWithoutProvenance = false
        state.uncertaintyWithoutDispatch = false
        state.reportedUncertainties.clear()
        state.pendingUncertainties.clear()
        state.settledUncertainties.clear()
    }

    private fun recordPendingMarker(preferences: ContactPreferenceFlags, radioId: RadioId, state: RadioState) {
        if (!state.markerRecordingPending) return
        // A failed commit may already be visible to bool(); only a successful set records durability.
        try {
            preferences.set(key(radioId), true)
        } catch (failure: Exception) {
            publishTrackedState(preferences, radioId, state)
            throw failure
        }
        state.markerRecordingPending = false
        state.reportedUncertainties.addAll(state.pendingUncertainties)
        state.pendingUncertainties.clear()
        publishTrackedState(preferences, radioId, state)
    }

    private fun hasUnresolvedWrite(state: RadioState): Boolean =
        state.epochLoadedWithoutProvenance || state.writes.any { dispatch ->
            when (dispatch.outcome) {
                ContactWriteDispatchOutcome.UNKNOWN ->
                    dispatch in state.dispatchingWrites || dispatch in state.finishedWrites
                ContactWriteDispatchOutcome.MAY_HAVE_WRITTEN -> true
                ContactWriteDispatchOutcome.ACCEPTED -> dispatch !in state.finishedWrites
                ContactWriteDispatchOutcome.DEFINITELY_UNSENT,
                ContactWriteDispatchOutcome.REJECTED -> false
            }
        }

    private fun abandon(state: RadioState, dispatch: ContactWriteDispatch) {
        state.writes.remove(dispatch)
        state.dispatchingWrites.remove(dispatch)
        state.finishedWrites.remove(dispatch)
        state.invalidatedWrites.remove(dispatch)
        state.locallyCompletedWrites.remove(dispatch)
        state.reportedUncertainties.remove(dispatch)
        state.pendingUncertainties.remove(dispatch)
        state.settledUncertainties.remove(dispatch)
    }

    private fun readOnlyState(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        state: RadioState,
    ): ContactWriteRecoveryState {
        if (state.epoch != null) return trackedState(radioId, state)
        return if (preferences.bool(key(radioId))) {
            ContactWriteRecoveryState.Required(
                radioId = radioId,
                reason = ContactWriteRecoveryReason.STORED_MARKER_WITHOUT_PROVENANCE,
                markerRecording = ContactWriteMarkerRecording.LOADED_FLAG_UNVERIFIED,
                localCompletion = ContactWriteLocalCompletion.UNAVAILABLE,
            )
        } else {
            ContactWriteRecoveryState.Clear(radioId)
        }
    }

    private fun trackedState(radioId: RadioId, state: RadioState): ContactWriteRecoveryState.Required {
        val owners = state.pendingUncertainties + state.reportedUncertainties
        val unresolved = owners.filterNot { it in state.settledUncertainties }
        val reason = when {
            state.epochLoadedWithoutProvenance ->
                ContactWriteRecoveryReason.STORED_MARKER_WITHOUT_PROVENANCE
            unresolved.any {
                it.outcome == ContactWriteDispatchOutcome.UNKNOWN ||
                    it.outcome == ContactWriteDispatchOutcome.MAY_HAVE_WRITTEN
            } -> ContactWriteRecoveryReason.RADIO_OUTCOME_UNCERTAIN
            state.uncertaintyWithoutDispatch || owners.isEmpty() ->
                ContactWriteRecoveryReason.OPERATION_OUTCOME_UNAVAILABLE
            unresolved.any { it.outcome == ContactWriteDispatchOutcome.ACCEPTED } ->
                ContactWriteRecoveryReason.RADIO_ACCEPTED_RECONCILIATION_REQUIRED
            unresolved.any { it.outcome == ContactWriteDispatchOutcome.REJECTED } ->
                ContactWriteRecoveryReason.RADIO_REJECTION_SETTLEMENT_PENDING
            unresolved.any { it.outcome == ContactWriteDispatchOutcome.DEFINITELY_UNSENT } ->
                ContactWriteRecoveryReason.RADIO_UNSENT_SETTLEMENT_PENDING
            state.markerClearingPending -> ContactWriteRecoveryReason.MARKER_CLEAR_PENDING
            else -> ContactWriteRecoveryReason.OPERATION_OUTCOME_UNAVAILABLE
        }
        val markerRecording = when {
            state.markerRecordingPending -> ContactWriteMarkerRecording.RETRY_PENDING
            state.markerClearRetryPending -> ContactWriteMarkerRecording.CLEAR_RETRY_PENDING
            state.epochLoadedWithoutProvenance -> ContactWriteMarkerRecording.LOADED_FLAG_UNVERIFIED
            else -> ContactWriteMarkerRecording.SET_SUCCEEDED
        }
        return ContactWriteRecoveryState.Required(
            radioId = radioId,
            reason = reason,
            markerRecording = markerRecording,
            localCompletion = localCompletion(state),
        )
    }

    private fun localCompletion(state: RadioState): ContactWriteLocalCompletion {
        val owners = state.pendingUncertainties + state.reportedUncertainties
        val accepted = (state.writes + owners).filter {
            it.outcome == ContactWriteDispatchOutcome.ACCEPTED
        }.toSet()
        if (accepted.isEmpty()) return ContactWriteLocalCompletion.UNAVAILABLE
        val completed = accepted.filter {
            it in state.locallyCompletedWrites || it in state.settledUncertainties
        }
        val otherUnresolved = state.epochLoadedWithoutProvenance || state.uncertaintyWithoutDispatch ||
            owners.any {
                it !in state.settledUncertainties && it.outcome != ContactWriteDispatchOutcome.ACCEPTED
            }
        return when {
            otherUnresolved || (completed.isNotEmpty() && completed.size < accepted.size) ->
                ContactWriteLocalCompletion.PARTIAL
            completed.size == accepted.size -> ContactWriteLocalCompletion.COMPLETE
            else -> ContactWriteLocalCompletion.PENDING
        }
    }

    private fun publishTrackedState(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        state: RadioState,
    ) {
        if (state.epoch != null) publish(preferences, trackedState(radioId, state))
    }

    private fun publishClear(preferences: ContactPreferenceFlags, radioId: RadioId) {
        publish(preferences, ContactWriteRecoveryState.Clear(radioId))
    }

    private fun publish(preferences: ContactPreferenceFlags, value: ContactWriteRecoveryState) {
        subscribers[preferences]?.get(value.radioId)?.forEach { subscriber ->
            if (subscriber.lastState != value) {
                subscriber.lastState = value
                pendingPublications.addLast(Publication(subscriber, value))
            }
        }
    }

    private fun flushPublications() {
        while (publicationDrainActive.compareAndSet(false, true)) {
            try {
                while (true) {
                    val publication = synchronized(this) {
                        if (pendingPublications.isEmpty()) null else pendingPublications.removeFirst()
                    } ?: break
                    val result = publication.subscriber.channel.trySend(publication.state)
                    if (result.isClosed) {
                        synchronized(this) {
                            subscribers.values.forEach { byRadio ->
                                byRadio.values.forEach { it.remove(publication.subscriber) }
                            }
                        }
                    } else {
                        check(result.isSuccess) { "An unbounded recovery-state subscriber lost an update" }
                    }
                }
            } finally {
                publicationDrainActive.set(false)
            }
            if (synchronized(this) { pendingPublications.isEmpty() }) return
        }
    }

    private inline fun <T> mutate(block: () -> T): T =
        try {
            synchronized(this, block)
        } finally {
            flushPublications()
        }

    private fun state(preferences: ContactPreferenceFlags, radioId: RadioId): RadioState =
        states.getOrPut(preferences) { mutableMapOf() }.getOrPut(radioId) { RadioState() }

    private fun key(radioId: RadioId): String = "contactWriteReconciliation.${radioId.value}"
}
