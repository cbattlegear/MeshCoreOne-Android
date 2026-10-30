// AndroidOnly: WP-217 boolean preference port standing in for UserDefaults; WP-303 binds core:datastore.
package com.meshcoreone.android.core.services.simulator

import kotlinx.coroutines.ensureActive

/**
 * The manager reads an already-loaded snapshot synchronously. A mutation completes only after all of its
 * values have been committed together; failures and cancellation must reach its caller.
 */
interface DemoModeDefaults {
    /** `UserDefaults.bool(forKey:)`: the stored value, or `false` when the key is absent. */
    fun bool(forKey: String): Boolean

    suspend fun setAll(values: Map<String, Boolean>)

    suspend fun set(value: Boolean, forKey: String) = setAll(mapOf(forKey to value))
}

/**
 * Process-local [DemoModeDefaults], the equivalent of a fresh `UserDefaults(suiteName:)` suite. Used by tests
 * and previews; it does not survive the process.
 */
class InMemoryDemoModeDefaults(initial: Map<String, Boolean> = emptyMap()) : DemoModeDefaults {
    private val lock = Any()
    private var values: Map<String, Boolean> = initial.toMap()

    override fun bool(forKey: String): Boolean = synchronized(lock) { values[forKey] ?: false }

    override suspend fun setAll(values: Map<String, Boolean>) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        synchronized(lock) { this.values = this.values + values }
    }

    /** Whether [key] has ever been written, distinguishing an explicit `false` from absence. */
    fun contains(key: String): Boolean = synchronized(lock) { key in values }
}
