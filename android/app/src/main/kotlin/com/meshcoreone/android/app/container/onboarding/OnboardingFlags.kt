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
    private val lock = Any()
    private var values: Map<String, Boolean> = initial
    private val completed = MutableStateFlow(initial[OnboardingState.KEY_HAS_COMPLETED] ?: false)
    private val writes = Channel<Pair<String, Boolean>>(Channel.UNLIMITED)

    /** Mirrors the persisted `hasCompletedOnboarding` flag; flips as soon as the flow completes. */
    val hasCompleted: StateFlow<Boolean> = completed.asStateFlow()

    init {
        scope.launch {
            for ((key, value) in writes) {
                try {
                    persist(key, value)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    onFailure(failure)
                }
            }
        }
    }

    override fun getBoolean(key: String): Boolean = synchronized(lock) { values[key] ?: false }

    override fun setBoolean(key: String, value: Boolean) {
        synchronized(lock) {
            values = values + (key to value)
            if (key == OnboardingState.KEY_HAS_COMPLETED) completed.value = value
            writes.trySend(key to value)
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
                    require(name in KEYS) { "Unknown onboarding flag: $name" }
                    ShowOnceTips(store).persistOnboardingFlag(value)
                },
                onFailure = { logger.log(Level.WARNING, "Failed to persist onboarding flag: ${it.message}") },
            )
    }
}
