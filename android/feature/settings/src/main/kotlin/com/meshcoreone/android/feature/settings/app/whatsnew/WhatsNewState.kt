// PortedFrom: MC1/State/WhatsNewState.swift@db14559b39d32322b06477c6ae676112f583db50
// Native adaptation: the UserDefaults baseline sits behind the suspending WhatsNewBaselineStore (the app binds AppStorageKey.lastShownWhatsNewVersion); storage failures propagate.
package com.meshcoreone.android.feature.settings.app.whatsnew

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Device-local "last shown What's New version" baseline. */
interface WhatsNewBaselineStore {
    suspend fun read(): String?

    suspend fun write(version: String)
}

/**
 * Owns the baseline and decides once per launch whether to present the sheet. [currentVersion] is the
 * running marketing version (`versionName`).
 */
class WhatsNewState(private val baseline: WhatsNewBaselineStore, private val currentVersion: String) {
    private val pending = MutableStateFlow<WhatsNewRelease?>(null)
    private val transition = Mutex()
    private var evaluated = false
    private var markedShown = false

    /** The release to present, set by [evaluate] and cleared by [markShown]. */
    val pendingRelease: StateFlow<WhatsNewRelease?> = pending

    /**
     * Runs once per process launch. A show defers the baseline to [markShown]; suppress finalizes it now.
     * Screenshot mode and an unparseable version neither present nor write. A storage failure propagates and
     * leaves evaluation retryable, rather than treating an incomplete read/write as a launch decision.
     */
    suspend fun evaluate(isOnboarded: Boolean, isScreenshotMode: Boolean, catalog: List<WhatsNewRelease> = WhatsNewCatalog.releases) {
        transition.withLock {
            if (evaluated) return
            val current = WhatsNewVersion.parse(currentVersion)
            if (!isScreenshotMode && current != null) {
                val release = resolve(current, baseline.read(), isOnboarded, catalog)
                if (release != null) pending.value = release else baseline.write(currentVersion)
            }
            evaluated = true
        }
    }

    /**
     * Persists the baseline for the running version and clears the pending release (Continue, Support and Back).
     * A failed/cancelled write leaves the release pending for retry; no dismissal claims persistence.
     */
    suspend fun markShown() {
        transition.withLock {
            if (markedShown) return
            baseline.write(currentVersion)
            markedShown = true
            pending.value = null
        }
    }

    companion object {
        /** The launch-time show/suppress decision as a pure function. */
        fun resolve(
            current: WhatsNewVersion,
            baselineString: String?,
            isOnboarded: Boolean,
            catalog: List<WhatsNewRelease>,
        ): WhatsNewRelease? {
            val release = catalog.firstOrNull { it.version == current }?.takeIf { it.items.isNotEmpty() } ?: return null
            // No baseline: an upgrader sees the notes, a brand-new install (still mid-onboarding) does not.
            if (baselineString == null) return if (isOnboarded) release else null
            val baseline = WhatsNewVersion.parse(baselineString) ?: return null
            return if (current > baseline) release else null
        }
    }
}
