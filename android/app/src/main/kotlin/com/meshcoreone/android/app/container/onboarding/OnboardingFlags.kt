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
 * [OnboardingFlagStore] reads are served from a snapshot loaded before the UI exists; each write updates the snapshot
 * at once and persists, in the order made, on [scope] (one consumer drains the queue). Lock-confined; nothing
 * suspends under the lock. [hasCompleted] is the first-run gate the app root observes.
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
        val persisted: CompletableDeferred<Unit>? = null,
    )

    private val lock = Any()
    private var values: Map<String, Boolean> = initial
    private val completed = MutableStateFlow(initial[OnboardingState.KEY_HAS_COMPLETED] ?: false)
    private val writes = Channel<Write>(Channel.UNLIMITED)

    /** Mirrors the persisted `hasCompletedOnboarding` flag; flips as soon as the flow completes. */
    val hasCompleted: StateFlow<Boolean> = completed.asStateFlow()

    init {
        scope.launch {
            try {
                for (write in writes) {
                    try {
                        persist(write.key, write.value)
                        if (write.persisted != null) {
                            synchronized(lock) {
                                values = values + (write.key to write.value)
                                if (write.key == OnboardingState.KEY_HAS_COMPLETED) completed.value = write.value
                            }
                        }
                        write.persisted?.complete(Unit)
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
            check(writes.trySend(Write(key, value)).isSuccess) {
                "Onboarding preference writer is unavailable"
            }
            values = values + (key to value)
            if (key == OnboardingState.KEY_HAS_COMPLETED) completed.value = value
        }
    }

    override suspend fun persistBoolean(key: String, value: Boolean) {
        val persisted = CompletableDeferred<Unit>()
        writes.send(Write(key, value, persisted))
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
