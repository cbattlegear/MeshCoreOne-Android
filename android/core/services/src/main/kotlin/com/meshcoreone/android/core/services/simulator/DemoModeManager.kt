// PortedFrom: MC1/Utilities/DemoModeManager.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.simulator

import java.util.logging.Logger
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Observable demo-mode flags, replacing the Swift `@Observable` properties. */
data class DemoModeState(val isUnlocked: Boolean, val isEnabled: Boolean)

/**
 * Demo-mode unlock/enable flags persisted in [defaults] under the `AppStorageKey` raw values
 * `isDemoModeUnlocked` and `isDemoModeEnabled`.
 *
 * Swift observes the properties via `@Observable` on the main actor. Mutations are serialized and publish
 * only after the defaults store confirms the write, including assignments whose value is unchanged.
 */
class DemoModeManager(private val defaults: DemoModeDefaults) {
    private val writes = Mutex()
    private val mutableState = MutableStateFlow(readState())

    /** Current flags, for observers (the Swift `@Observable` surface). */
    val state: StateFlow<DemoModeState> = mutableState.asStateFlow()

    val isUnlocked: Boolean get() = mutableState.value.isUnlocked

    val isEnabled: Boolean get() = mutableState.value.isEnabled

    suspend fun setUnlocked(value: Boolean) = writes.withLock {
        defaults.set(value, IS_DEMO_MODE_UNLOCKED_KEY)
        currentCoroutineContext().ensureActive()
        mutableState.value = readState()
    }

    suspend fun setEnabled(value: Boolean) = writes.withLock {
        defaults.set(value, IS_DEMO_MODE_ENABLED_KEY)
        currentCoroutineContext().ensureActive()
        mutableState.value = readState()
    }

    /**
     * Unlocks and enables demo mode. Both values are written through before a single state publish, so observers
     * never see the unlocked-but-disabled midpoint (SwiftUI coalesces Swift's two assignments the same way).
     */
    suspend fun unlock() = writes.withLock {
        defaults.setAll(mapOf(IS_DEMO_MODE_UNLOCKED_KEY to true, IS_DEMO_MODE_ENABLED_KEY to true))
        currentCoroutineContext().ensureActive()
        mutableState.value = DemoModeState(isUnlocked = true, isEnabled = true)
        logger.info("Demo mode unlocked and enabled")
    }

    private fun readState() = DemoModeState(
        isUnlocked = defaults.bool(IS_DEMO_MODE_UNLOCKED_KEY),
        isEnabled = defaults.bool(IS_DEMO_MODE_ENABLED_KEY),
    )

    companion object {
        /** `AppStorageKey.isDemoModeUnlocked.rawValue`. */
        const val IS_DEMO_MODE_UNLOCKED_KEY = "isDemoModeUnlocked"

        /** `AppStorageKey.isDemoModeEnabled.rawValue`. */
        const val IS_DEMO_MODE_ENABLED_KEY = "isDemoModeEnabled"

        private val logger: Logger = Logger.getLogger("com.mc1.DemoMode")
        private val sharedLock = Any()
        private var standardDefaults: DemoModeDefaults? = null
        private var sharedInstance: DemoModeManager? = null

        /**
         * Binds the process-wide store behind [shared] (Swift's `UserDefaults.standard`). Must run before the
         * first [shared] access; installing the same instance again is a no-op, a different one is rejected.
         */
        fun installStandardDefaults(defaults: DemoModeDefaults) {
            synchronized(sharedLock) {
                val current = standardDefaults
                check(current == null || current === defaults) { "Demo-mode standard defaults are already installed" }
                standardDefaults = defaults
            }
        }

        /** The process-wide manager. Fails fast if [installStandardDefaults] has not run yet. */
        val shared: DemoModeManager
            get() = synchronized(sharedLock) {
                sharedInstance ?: run {
                    val defaults = checkNotNull(standardDefaults) {
                        "DemoModeManager.shared accessed before installStandardDefaults"
                    }
                    DemoModeManager(defaults).also { sharedInstance = it }
                }
            }
    }
}
