// AndroidOnly: WP-303 User-initiated enrollment identity and foreground presentation lifetime.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.EnrollmentRequest
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Observation
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Operation
import com.meshcoreone.android.app.container.NotificationEnrollmentCoordinator.Result
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Exact displayed copy, not a resource ID, saved consent flag, or proof of rendering. */
internal data class EnrollmentWarningCopy(
    val title: String,
    val body: String,
    val confirm: String,
    val cancel: String,
) {
    init {
        require(listOf(title, body, confirm, cancel).all { it.isNotBlank() })
    }

    val canonical: String get() = listOf(title, body, confirm, cancel).joinToString("") { "${it.length}:$it" }
}

/**
 * One process owner, called on the UI thread, including synchronous retirement callbacks.
 * The supplied scope must outlive a settings composition/rotation. No identity or consent is
 * restored from saved UI state: a new process uses the durable coordinator's resume contract.
 */
internal class NotificationEnrollmentProducer(
    private val scope: CoroutineScope,
    private val backend: Backend,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    interface Backend {
        suspend fun observe(copy: EnrollmentWarningCopy): Observation
        fun present(request: EnrollmentRequest, copy: EnrollmentWarningCopy): Presentation
        suspend fun resume(copy: EnrollmentWarningCopy): Result
    }

    interface Presentation {
        val request: EnrollmentRequest
        val copy: EnrollmentWarningCopy
        /** Must synchronously fence both staging and consumption, without disk IO. */
        fun retire()
        suspend fun invalidate()
        suspend fun stageExplicitAction(): Staging
        suspend fun enroll(): Result
    }

    enum class Staging { STAGED, REQUIRED, REJECTED, ALREADY_CONSUMED, INDETERMINATE }

    sealed interface State {
        data object Ready : State
        data object Reading : State
        data class Warning(val presentation: Presentation) : State
        data class Working(val operation: Operation?, val presentation: Presentation? = null) : State
        data class Interrupted(val operation: Operation, val actionAttempted: Boolean) : State
        data class Outcome(val result: Result) : State
    }

    private val mutable = MutableStateFlow<State>(State.Ready)
    val state = mutable.asStateFlow()
    private var request: EnrollmentRequest? = null
    private var presentation: Presentation? = null
    private var actionAttempted = false
    private var reconciledUnknown = false
    private var busy = false
    private var host: Any? = null
    private var epoch = 0L
    val canBegin: Boolean get() = !busy && (!actionAttempted || reconciledUnknown) && presentation == null

    /** A competing activity cannot replace a currently mounted foreground owner. */
    fun attach(owner: Any): Boolean {
        if (host != null && host !== owner) return false
        host = owner
        return true
    }

    fun detach(owner: Any) {
        if (host !== owner) return
        host = null
        epoch++
        retire()
    }

    /** Only the actual new-generation button calls this; missing history never calls it. */
    fun begin(owner: Any, copy: EnrollmentWarningCopy) {
        if (host !== owner || !canBegin) return
        busy = true
        mutable.value = State.Reading
        val started = epoch
        scope.launch {
            try {
                when (val observed = backend.observe(copy)) {
                    is Observation.Failed -> mutable.value = State.Outcome(Result.Blocked(observed.reason))
                    else -> {
                        if (host !== owner || epoch != started) return@launch
                        val expected = (observed as? Observation.Current)?.record
                        // Only a later, explicit NEW action after durable recovery found no current
                        // authorization may abandon the old tuple. It is never an automatic retry.
                        if (actionAttempted && reconciledUnknown && expected != null) {
                            mutable.value = State.Outcome(Result.Superseded)
                            return@launch
                        }
                        val retained = request.takeUnless { actionAttempted && reconciledUnknown }
                        if (retained != null && retained.expectedCurrent != expected) {
                            mutable.value = State.Outcome(Result.Superseded)
                            return@launch
                        }
                        val next = retained ?: EnrollmentRequest(
                            Operation(newId(), newId(), newId()), newId(), expected,
                        ).also {
                            request = it
                            actionAttempted = false
                            reconciledUnknown = false
                        }
                        val shown = backend.present(next, copy)
                        check(shown.request == next && shown.copy == copy)
                        presentation = shown
                        mutable.value = State.Warning(shown)
                    }
                }
            } catch (cancelled: CancellationException) {
                request?.let { mutable.value = State.Interrupted(it.operation, actionAttempted) }
                throw cancelled
            } finally {
                busy = false
                if (mutable.value == State.Reading) mutable.value = State.Ready
            }
        }
    }

    /** Identity checks reject callbacks captured by a dismissed/replaced/rotated dialog. */
    fun confirm(owner: Any, shown: Presentation) {
        if (host !== owner || presentation !== shown || busy || actionAttempted) return
        actionAttempted = true
        busy = true
        mutable.value = State.Working(shown.request.operation, shown)
        scope.launch {
            try {
                val result = when (shown.stageExplicitAction()) {
                    Staging.STAGED -> {
                        if (presentation !== shown || host !== owner) {
                            Result.Indeterminate(shown.request.operation, NotificationEnrollmentCoordinator.Step.AUTHORIZE)
                        } else shown.enroll()
                    }
                    Staging.INDETERMINATE, Staging.ALREADY_CONSUMED ->
                        Result.Indeterminate(shown.request.operation, NotificationEnrollmentCoordinator.Step.AUTHORIZE)
                    Staging.REQUIRED -> Result.Blocked(NotificationEnrollmentCoordinator.Failure.ACKNOWLEDGMENT_REQUIRED)
                    Staging.REJECTED -> Result.Blocked(NotificationEnrollmentCoordinator.Failure.ACKNOWLEDGMENT_DECLINED)
                }
                mutable.value = State.Outcome(boundToAttempt(result))
            } catch (cancelled: CancellationException) {
                mutable.value = State.Interrupted(shown.request.operation, true)
                throw cancelled
            } finally {
                // Retirement precedes asynchronous invalidation even on cancellation.
                retire()
                busy = false
            }
        }
    }

    fun dismiss(owner: Any, shown: Presentation) {
        if (host === owner && presentation === shown) retire()
    }

    /** Pause/focus loss/copy replacement is not permission to reauthorize uncertain writes. */
    fun retire() {
        val old = presentation ?: return
        old.retire()
        presentation = null
        val state = mutable.value
        if (!busy) mutable.value = State.Interrupted(old.request.operation, actionAttempted)
        else if (state is State.Working) mutable.value = state.copy(presentation = null)
        scope.launch { old.invalidate() }
    }

    /** After an attempted action (or process restart), only durable same-operation recovery. */
    fun resume(owner: Any, copy: EnrollmentWarningCopy) {
        if (host !== owner || busy || presentation != null) return
        busy = true
        mutable.value = State.Working(request?.operation)
        scope.launch {
            try {
                val result = boundToAttempt(backend.resume(copy))
                reconciledUnknown = result == Result.NeedsAcknowledgment
                mutable.value = State.Outcome(result)
            } catch (cancelled: CancellationException) {
                request?.let { mutable.value = State.Interrupted(it.operation, actionAttempted) }
                throw cancelled
            } finally {
                busy = false
            }
        }
    }

    private fun boundToAttempt(result: Result): Result {
        val expected = request?.operation ?: return result
        val actual = when (result) {
            is Result.BoundSnapshot -> result.witness.operation
            is Result.Incomplete -> result.operation
            is Result.Indeterminate -> result.operation
            else -> null
        }
        return if (actual != null && actual != expected) Result.Superseded else result
    }
}
