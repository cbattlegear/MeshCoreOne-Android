// AndroidOnly: WP-303 Synchronous onboarding flag store over an asynchronous preference store.
package com.meshcoreone.android.app.container.onboarding

import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.ui.ShowOnceTips
import com.meshcoreone.android.feature.onboarding.OnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingState
import java.util.logging.Level
import java.util.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
) : OnboardingFlagStore {
    private data class Write(
        val key: String,
        val value: Boolean,
        val revision: Long,
        val persisted: CompletableDeferred<Unit>? = null,
        val requireCurrent: (() -> Unit)? = null,
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
    private val writes = Channel<Write>(Channel.UNLIMITED)

    /** Mirrors the persisted `hasCompletedOnboarding` flag; flips as soon as the flow completes. */
    val hasCompleted: StateFlow<Boolean> = completed.asStateFlow()

    init {
        scope.launch {
            try {
                for (write in writes) {
                    try {
                        validate(write)
                        persist(write.key, write.value)
                        synchronized(lock) {
                            validate(write)
                            if (write.revision >= (snapshotRevision[write.key] ?: 0L)) {
                                snapshotRevision[write.key] = write.revision
                                values = values + (write.key to write.value)
                                if (write.key == OnboardingState.KEY_HAS_COMPLETED) completed.value = write.value
                            }
                        }
                        write.persisted?.complete(Unit)
                    } catch (rejected: RejectedWrite) {
                        write.persisted?.completeExceptionally(rejected.failure)
                    } catch (cancelled: CancellationException) {
                        write.persisted?.completeExceptionally(cancelled)
                        throw cancelled
                    } catch (failure: Exception) {
                        try {
                            onFailure(failure)
                        } finally {
                            write.persisted?.completeExceptionally(failure)
                        }
                    }
                }
            } finally {
                val stopped = CancellationException("Onboarding preference writer stopped")
                writes.close(stopped)
                while (true) {
                    val queued = writes.tryReceive().getOrNull() ?: break
                    queued.persisted?.completeExceptionally(stopped)
                }
            }
        }
    }

    override fun getBoolean(key: String): Boolean = synchronized(lock) { values[key] ?: false }

    override fun setBoolean(key: String, value: Boolean) {
        synchronized(lock) {
            val revision = nextRevision + 1
            check(writes.trySend(Write(key, value, revision)).isSuccess) {
                "Onboarding preference writer is unavailable"
            }
            nextRevision = revision
            snapshotRevision[key] = revision
            values = values + (key to value)
            if (key == OnboardingState.KEY_HAS_COMPLETED) completed.value = value
        }
    }

    override suspend fun persistBoolean(key: String, value: Boolean) =
        persistBoolean(key, value) {}

    override suspend fun persistBoolean(key: String, value: Boolean, requireCurrent: () -> Unit) {
        requireCurrent()
        val persisted = CompletableDeferred<Unit>()
        synchronized(lock) {
            val revision = nextRevision + 1
            check(writes.trySend(Write(key, value, revision, persisted, requireCurrent)).isSuccess) {
                "Onboarding preference writer is unavailable"
            }
            nextRevision = revision
        }
        persisted.await()
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
                    if (name == OnboardingState.KEY_HAS_COMPLETED && value) {
                        ShowOnceTips(store).completeOnboarding()
                    } else {
                        store.set(key, value)
                    }
                },
                onFailure = { logger.log(Level.WARNING, "Failed to persist onboarding flag: ${it.message}") },
            )
    }
}
