// AndroidOnly: WP-209 Distinguishes radio acceptance from incomplete local contact accounting.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatch
import com.meshcoreone.android.core.protocol.session.ContactWriteDispatchOutcome
import java.util.WeakHashMap
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
        var epochLoadedWithoutProvenance = false
        var markerRecordingPending = false
        var syncing = false
        val syncMutex = Mutex()
        val writes = linkedSetOf<ContactWriteDispatch>()
        val dispatchingWrites = linkedSetOf<ContactWriteDispatch>()
        val finishedWrites = linkedSetOf<ContactWriteDispatch>()
        val invalidatedWrites = linkedSetOf<ContactWriteDispatch>()
        val locallyCompletedWrites = linkedSetOf<ContactWriteDispatch>()
        val reportedUncertainties = linkedSetOf<ContactWriteDispatch>()
        val pendingUncertainties = linkedSetOf<ContactWriteDispatch>()
    }

    private val states = WeakHashMap<ContactPreferenceFlags, MutableMap<RadioId, RadioState>>()

    @Synchronized
    fun snapshot(preferences: ContactPreferenceFlags, radioId: RadioId): Any? {
        val state = state(preferences, radioId)
        return loadEpoch(preferences, radioId, state)
    }

    suspend fun <T> withSyncFence(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        operation: suspend () -> T,
    ): T {
        val state = synchronized(this) { state(preferences, radioId) }
        return state.syncMutex.withLock {
            synchronized(this) {
                loadEpoch(preferences, radioId, state)
                if (state.epoch != null || hasUnresolvedWrite(state)) throw ContactServiceError.SyncInterrupted()
                state.syncing = true
                state.writes.filterNot { it in state.dispatchingWrites }.forEach {
                    state.invalidatedWrites.add(it)
                }
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
            try {
                operation()
            } finally {
                synchronized(this) { state.syncing = false }
            }
        }
    }

    @Synchronized
    fun registerWrite(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) {
        val state = state(preferences, radioId)
        blockReason(preferences, radioId, state)?.let { throw ContactWriteAdmissionBlocked(radioId, it) }
        state.writes += dispatch
    }

    @Synchronized
    fun beforeDispatch(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) {
        val state = state(preferences, radioId)
        loadEpoch(preferences, radioId, state)
        if (dispatch in state.invalidatedWrites) {
            throw ContactWriteAdmissionBlocked(radioId, ContactWriteAdmissionBlockReason.SYNC_IN_PROGRESS)
        }
        blockReason(preferences, radioId, state, dispatch)?.let { throw ContactWriteAdmissionBlocked(radioId, it) }
        state.dispatchingWrites += dispatch
    }

    @Synchronized
    fun completeWrite(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) {
        state(preferences, radioId).locallyCompletedWrites += dispatch
    }

    @Synchronized
    fun abandonDefinitelyUnsent(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) {
        if (dispatch.outcome == ContactWriteDispatchOutcome.DEFINITELY_UNSENT) {
            abandon(state(preferences, radioId), dispatch)
        }
    }

    @Synchronized
    fun retryExistingRecovery(preferences: ContactPreferenceFlags, radioId: RadioId): Boolean {
        val state = state(preferences, radioId)
        if (state.epoch == null) return false
        recordPendingMarker(preferences, radioId, state)
        return true
    }

    fun finishWrite(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        dispatch: ContactWriteDispatch,
        requireReconciliation: (Exception) -> Unit,
    ) {
        synchronized(this) {
            val state = state(preferences, radioId)
            state.dispatchingWrites.remove(dispatch)
            state.finishedWrites += dispatch
            val uncertainty = when (dispatch.outcome) {
                ContactWriteDispatchOutcome.DEFINITELY_UNSENT,
                ContactWriteDispatchOutcome.REJECTED -> {
                    abandon(state, dispatch)
                    null
                }
                ContactWriteDispatchOutcome.ACCEPTED -> {
                    val locallyCompleted = state.locallyCompletedWrites.remove(dispatch)
                    if (locallyCompleted &&
                        dispatch.cancellationDisposition !=
                        com.meshcoreone.android.core.protocol.session.ChannelWriteCancellationDisposition.MAY_HAVE_WRITTEN
                    ) {
                        abandon(state, dispatch)
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
            uncertainty?.let(requireReconciliation)
        }
    }

    @Synchronized
    fun isWriteTracked(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch): Boolean =
        dispatch in state(preferences, radioId).writes

    @Synchronized
    fun abandonWrite(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch) {
        abandon(state(preferences, radioId), dispatch)
    }

    @Synchronized
    fun require(preferences: ContactPreferenceFlags, radioId: RadioId, dispatch: ContactWriteDispatch? = null) {
        val state = state(preferences, radioId)
        if (dispatch != null && dispatch in state.reportedUncertainties) {
            recordPendingMarker(preferences, radioId, state)
            return
        }
        state.epoch = Any()
        state.markerRecordingPending = true
        if (dispatch != null) state.pendingUncertainties.add(dispatch)
        recordPendingMarker(preferences, radioId, state)
    }

    @Synchronized
    fun reconciled(preferences: ContactPreferenceFlags, radioId: RadioId, epoch: Any): Boolean {
        val state = state(preferences, radioId)
        if (state.epoch !== epoch || hasUnresolvedWrite(state)) return false
        recordPendingMarker(preferences, radioId, state)
        preferences.set(key(radioId), false)
        state.markerRecordingPending = false
        state.epoch = null
        state.epochLoadedWithoutProvenance = false
        state.writes.clear()
        state.dispatchingWrites.clear()
        state.finishedWrites.clear()
        state.locallyCompletedWrites.clear()
        state.reportedUncertainties.clear()
        state.pendingUncertainties.clear()
        return true
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
        recordPendingMarker(preferences, radioId, state)
        if (state.epoch == null && preferences.bool(key(radioId))) {
            state.epoch = Any()
            state.epochLoadedWithoutProvenance = true
        }
        return state.epoch
    }

    private fun recordPendingMarker(preferences: ContactPreferenceFlags, radioId: RadioId, state: RadioState) {
        if (!state.markerRecordingPending) return
        // A failed commit may already be visible to bool(); only a successful set records durability.
        preferences.set(key(radioId), true)
        state.markerRecordingPending = false
        state.reportedUncertainties.addAll(state.pendingUncertainties)
        state.pendingUncertainties.clear()
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
    }

    private fun state(preferences: ContactPreferenceFlags, radioId: RadioId): RadioState =
        states.getOrPut(preferences) { mutableMapOf() }.getOrPut(radioId) { RadioState() }

    private fun key(radioId: RadioId): String = "contactWriteReconciliation.${radioId.value}"
}
