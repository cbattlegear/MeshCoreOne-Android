// AndroidOnly: WP-303 Synchronous onboarding flag store over an asynchronous preference store.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.contracts.domain.OnboardingCompletionAttempt
import com.meshcoreone.android.core.ui.ShowOnceTips
import com.meshcoreone.android.feature.onboarding.OnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingCompletionOutcomeUnknown
import com.meshcoreone.android.feature.onboarding.OnboardingState
import java.io.IOException
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OnboardingCompletionOutcomeUnknownException(
    val writeFailure: Exception,
    val readbackFailure: Exception,
) : IOException(writeFailure.message, writeFailure), OnboardingCompletionOutcomeUnknown {
    init { addSuppressed(readbackFailure) }
}

class OnboardingCompletionCancellationOutcomeUnknownException(
    val writeFailure: CancellationException,
    val readbackFailure: Exception,
) : CancellationException(writeFailure.message), OnboardingCompletionOutcomeUnknown {
    init {
        initCause(writeFailure)
        if (writeFailure !== readbackFailure) addSuppressed(readbackFailure)
    }
}

/**
 * [OnboardingFlagStore] reads are served from a snapshot loaded before the UI exists; ordinary writes update the
 * snapshot when queued, while acknowledged writes update it after persistence. One consumer drains writes in order
 * on [scope]. Lock-confined; nothing suspends under the lock. [hasCompleted] is the first-run gate the app root observes.
 */
class WriteBehindOnboardingFlags(
    initial: Map<String, Boolean>,
    scope: CoroutineScope,
    private val persist: suspend (String, Boolean) -> Unit,
    private val onFailure: (Throwable) -> Unit = {},
    private val stageCompletion: suspend (String) -> Unit = {},
    private val persistCompletion: suspend (String) -> Unit = { persist(OnboardingState.KEY_HAS_COMPLETED, true) },
    private val reconcileCompletion: suspend (String) -> Boolean = { false },
) : OnboardingFlagStore {
    private data class Write(
        val key: String,
        val value: Boolean,
        val revision: Long,
        val persisted: CancellableContinuation<Unit>? = null,
        val requireCurrent: (() -> Unit)? = null,
        val attempt: OnboardingCompletionAttempt? = null,
        val seal: ((OnboardingCompletionAttempt) -> Boolean)? = null,
        val onFinished: ((Result<Unit>) -> Unit)? = null,
        val onReconciled: ((Result<Boolean>) -> Unit)? = null,
    )

    private class RejectedWrite(val failure: Exception) : Exception(failure)

    private fun validate(write: Write) {
        try {
            write.requireCurrent?.invoke()
        } catch (failure: Exception) {
            throw RejectedWrite(failure)
        }
    }

    private val lock = Any()
    private var values: Map<String, Boolean> = initial
    private var nextRevision = 0L
    // A completed write must not overwrite a value already published by a newer operation.
    private val snapshotRevision = mutableMapOf<String, Long>()
    private val completed = MutableStateFlow(initial[OnboardingState.KEY_HAS_COMPLETED] ?: false)
    private val writes = ArrayDeque<Write>()
    private val wakeups = Channel<Unit>(Channel.CONFLATED)
    private var stopped: CancellationException? = null
    private val pendingCompletions = mutableMapOf<Long, OnboardingCompletionAttempt>()
    private var drained = CompletableDeferred(Unit)

    private fun enqueue(write: Write) {
        stopped?.let { throw it }
        if (drained.isCompleted) drained = CompletableDeferred()
        if (write.key == OnboardingState.KEY_HAS_COMPLETED && write.onReconciled == null) {
            pendingCompletions.values.forEach {
                it.reject(IllegalStateException("Onboarding completion was superseded by a newer write"))
            }
        }
        if (write.onReconciled == null) write.attempt?.let { pendingCompletions[write.revision] = it }
        writes.addLast(write)
    }

    /** Mirrors the persisted `hasCompletedOnboarding` flag; flips as soon as the flow completes. */
    val hasCompleted: StateFlow<Boolean> = completed.asStateFlow()

    init {
        scope.launch {
            try {
                for (signal in wakeups) while (true) {
                    val write = synchronized(lock) { writes.removeFirstOrNull() } ?: break
                    try {
                        validate(write)
                        val attempt = write.attempt
                        val reconciled = if (write.onReconciled != null) {
                            reconcileCompletion(checkNotNull(attempt).id.toString())
                        } else null
                        if (write.onReconciled == null && attempt == null) {
                            persist(write.key, write.value)
                        } else if (write.onReconciled == null && attempt != null) {
                            stageCompletion(attempt.id.toString())
                            validate(write)
                            val sealed = try {
                                write.seal?.invoke(attempt) == true
                            } catch (failure: Exception) {
                                throw RejectedWrite(failure)
                            }
                            if (!sealed) {
                                throw RejectedWrite(attempt.rejection()
                                    ?: IllegalStateException("Onboarding completion retired before finalization"))
                            }
                            try {
                                persistCompletion(attempt.id.toString())
                            } catch (failure: Exception) {
                                val committed = try {
                                    reconcileCompletion(attempt.id.toString())
                                } catch (readFailure: Exception) {
                                    if (failure is CancellationException) {
                                        throw OnboardingCompletionCancellationOutcomeUnknownException(failure, readFailure)
                                    }
                                    throw OnboardingCompletionOutcomeUnknownException(failure, readFailure)
                                }
                                if (!committed) throw failure
                                onFailure(failure)
                            }
                        }
                        synchronized(lock) {
                            if (reconciled != false && write.revision >= (snapshotRevision[write.key] ?: 0L)) {
                                snapshotRevision[write.key] = write.revision
                                values = values + (write.key to write.value)
                                if (write.key == OnboardingState.KEY_HAS_COMPLETED) completed.value = write.value
                            }
                        }
                        write.onFinished?.invoke(Result.success(Unit))
                        write.onReconciled?.invoke(Result.success(checkNotNull(reconciled)))
                        write.persisted?.resume(Unit)
                    } catch (rejected: RejectedWrite) {
                        write.onFinished?.invoke(Result.failure(rejected.failure))
                        write.onReconciled?.invoke(Result.failure(rejected.failure))
                        write.persisted?.resumeWithException(rejected.failure)
                    } catch (cancelled: CancellationException) {
                        write.onFinished?.invoke(Result.failure(cancelled))
                        write.onReconciled?.invoke(Result.failure(cancelled))
                        write.persisted?.resumeWithException(cancelled)
                        throw cancelled
                    } catch (failure: Exception) {
                        write.onFinished?.invoke(Result.failure(failure))
                        write.onReconciled?.invoke(Result.failure(failure))
                        try {
                            onFailure(failure)
                        } finally {
                            write.persisted?.resumeWithException(failure)
                        }
                    } finally {
                        val finished = synchronized(lock) {
                            pendingCompletions.remove(write.revision)
                            if (writes.isEmpty()) drained.also { drained = CompletableDeferred(Unit) } else null
                        }
                        finished?.complete(Unit)
                    }
                }
            } finally {
                val failure = CancellationException("Onboarding preference writer stopped")
                val abandoned = synchronized(lock) {
                    stopped = failure
                    writes.toList().also { writes.clear(); pendingCompletions.clear() }
                }
                wakeups.close(failure)
                abandoned.forEach {
                    it.onFinished?.invoke(Result.failure(failure))
                    it.onReconciled?.invoke(Result.failure(failure))
                    it.persisted?.resumeWithException(failure)
                }
            }
        }
    }

    override fun getBoolean(key: String): Boolean = synchronized(lock) { values[key] ?: false }

    override suspend fun reconcileCompletion(
        attempt: OnboardingCompletionAttempt, requireCurrent: () -> Unit,
        onFinished: (Result<Boolean>) -> Unit,
    ) {
        var submitted = false
        try {
            requireCurrent()
            suspendCancellableCoroutine<Unit> { continuation ->
                synchronized(lock) {
                    val revision = nextRevision + 1
                    enqueue(
                        Write(
                            OnboardingState.KEY_HAS_COMPLETED, true, revision, continuation,
                            requireCurrent, attempt, onReconciled = onFinished,
                        ),
                    )
                    nextRevision = revision
                    submitted = true
                }
                wakeups.trySend(Unit).getOrThrow()
            }
        } catch (failure: Exception) {
            if (!submitted) onFinished(Result.failure(failure))
            throw failure
        }
    }

    /** Process shutdown rejects pending decisions before cancelling the process Job; sealed writes drain first. */
    suspend fun shutdown() {
        val completion = synchronized(lock) {
            val failure = CancellationException("Onboarding process is shutting down")
            stopped = failure
            pendingCompletions.values.forEach { it.reject(failure) }
            drained
        }
        completion.await()
    }

    override fun setBoolean(key: String, value: Boolean) {
        synchronized(lock) {
            val revision = nextRevision + 1
            enqueue(Write(key, value, revision))
            nextRevision = revision
            snapshotRevision[key] = revision
            values = values + (key to value)
            if (key == OnboardingState.KEY_HAS_COMPLETED) completed.value = value
        }
        wakeups.trySend(Unit).getOrThrow()
    }

    override suspend fun persistBoolean(key: String, value: Boolean) =
        persistBoolean(key, value) {}

    override suspend fun persistBoolean(key: String, value: Boolean, requireCurrent: () -> Unit) =
        persistBoolean(key, value, requireCurrent) { it.trySeal() }

    override suspend fun persistBoolean(
        key: String, value: Boolean, requireCurrent: () -> Unit,
        seal: (OnboardingCompletionAttempt) -> Boolean,
    ) = persistBoolean(key, value, requireCurrent, seal, OnboardingCompletionAttempt())

    override suspend fun persistBoolean(
        key: String, value: Boolean, requireCurrent: () -> Unit,
        seal: (OnboardingCompletionAttempt) -> Boolean, attempt: OnboardingCompletionAttempt,
    ) = persistBoolean(key, value, requireCurrent, seal, attempt) {}

    override suspend fun persistBoolean(
        key: String, value: Boolean, requireCurrent: () -> Unit,
        seal: (OnboardingCompletionAttempt) -> Boolean, attempt: OnboardingCompletionAttempt,
        onFinished: (Result<Unit>) -> Unit,
    ) {
        requireCurrent()
        suspendCancellableCoroutine { continuation ->
            val completion = if (key == OnboardingState.KEY_HAS_COMPLETED && value) attempt else null
            continuation.invokeOnCancellation { cause ->
                completion?.reject(CancellationException("Onboarding caller cancelled before sealing").apply { initCause(cause) })
            }
            synchronized(lock) {
                val revision = nextRevision + 1
                nextRevision = revision
                enqueue(Write(key, value, revision, continuation, requireCurrent, completion, seal, onFinished))
            }
            wakeups.trySend(Unit).getOrThrow()
        }
    }

    companion object {
        private val logger: Logger = Logger.getLogger("com.mc1.OnboardingFlags")
        private val KEYS = mapOf(OnboardingState.KEY_HAS_COMPLETED to AppStorageKey.hasCompletedOnboarding)

        /** Loads the persisted flags, then binds writes to [store]. */
        suspend fun load(store: PreferenceStore, scope: CoroutineScope): WriteBehindOnboardingFlags =
            WriteBehindOnboardingFlags(
                KEYS.mapValues { (_, key) -> store.get(key) }, scope,
                persist = { name, value ->
                    val key = KEYS[name] ?: throw IllegalArgumentException("Unknown onboarding flag: $name")
                    when {
                        name == OnboardingState.KEY_HAS_COMPLETED && value ->
                            ShowOnceTips(store).completeOnboarding()
                        name == OnboardingState.KEY_HAS_COMPLETED ->
                            ShowOnceTips(store).persistOnboardingFlag(value)
                        else -> store.set(key, value)
                    }
                },
                onFailure = { logger.log(Level.WARNING, "Failed to persist onboarding flag: ${it.message}") },
                stageCompletion = { ShowOnceTips(store).stageOnboardingCompletion(it) },
                persistCompletion = { ShowOnceTips(store).completeOnboarding(it) },
                reconcileCompletion = { ShowOnceTips(store).isOnboardingCompletionFinalized(it) },
            )
    }
}
