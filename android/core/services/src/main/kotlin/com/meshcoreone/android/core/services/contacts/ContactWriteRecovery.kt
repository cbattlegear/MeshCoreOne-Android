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
        var syncing = false
        val syncMutex = Mutex()
        val writes = linkedSetOf<ContactWriteDispatch>()
        val locallyCompletedWrites = linkedSetOf<ContactWriteDispatch>()
        val reportedUncertainties = linkedSetOf<ContactWriteDispatch>()
    }

    private val states = WeakHashMap<ContactPreferenceFlags, MutableMap<RadioId, RadioState>>()

    @Synchronized
    fun snapshot(preferences: ContactPreferenceFlags, radioId: RadioId): Any? {
        val state = state(preferences, radioId)
        return state.epoch ?: if (preferences.bool(key(radioId))) Any().also { state.epoch = it } else null
    }

    suspend fun <T> withSyncFence(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        operation: suspend () -> T,
    ): T {
        val state = synchronized(this) { state(preferences, radioId) }
        return state.syncMutex.withLock {
            synchronized(this) { state.syncing = true }
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
        blockReason(preferences, radioId, state, dispatch)?.let { throw ContactWriteAdmissionBlocked(radioId, it) }
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

    fun finishWrite(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        dispatch: ContactWriteDispatch,
        requireReconciliation: (Exception) -> Unit,
    ) {
        val state = synchronized(this) { state(preferences, radioId) }
        val uncertainty = synchronized(this) {
            when (dispatch.outcome) {
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
        }
        uncertainty?.let(requireReconciliation)
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
        if (dispatch != null && !state.reportedUncertainties.add(dispatch)) return
        state.epoch = Any()
        preferences.set(key(radioId), true)
    }

    @Synchronized
    fun reconciled(preferences: ContactPreferenceFlags, radioId: RadioId, epoch: Any) {
        val state = state(preferences, radioId)
        if (state.epoch === epoch) {
            preferences.set(key(radioId), false)
            state.epoch = null
            state.writes.clear()
            state.locallyCompletedWrites.clear()
            state.reportedUncertainties.clear()
        }
    }

    private fun blockReason(
        preferences: ContactPreferenceFlags,
        radioId: RadioId,
        state: RadioState,
        dispatch: ContactWriteDispatch? = null,
    ): ContactWriteAdmissionBlockReason? = when {
        state.syncing -> ContactWriteAdmissionBlockReason.SYNC_IN_PROGRESS
        state.epoch != null || preferences.bool(key(radioId)) ->
            ContactWriteAdmissionBlockReason.RECONCILIATION_REQUIRED
        dispatch != null && state.writes.any {
            it !== dispatch && it.outcome != ContactWriteDispatchOutcome.DEFINITELY_UNSENT &&
                it.outcome != ContactWriteDispatchOutcome.REJECTED
        } -> ContactWriteAdmissionBlockReason.PREVIOUS_WRITE_UNRESOLVED
        else -> null
    }

    private fun abandon(state: RadioState, dispatch: ContactWriteDispatch) {
        state.writes.remove(dispatch)
        state.locallyCompletedWrites.remove(dispatch)
        state.reportedUncertainties.remove(dispatch)
    }

    private fun state(preferences: ContactPreferenceFlags, radioId: RadioId): RadioState =
        states.getOrPut(preferences) { mutableMapOf() }.getOrPut(radioId) { RadioState() }

    private fun key(radioId: RadioId): String = "contactWriteReconciliation.${radioId.value}"
}
