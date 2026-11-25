// PortedFrom: MC1/Models/LinkPreviewPreferences.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Pure policy logic only - the real `UserDefaults`-equivalent storage is Android DataStore
// (core:datastore's PreferenceStore), reached through a narrow adapter outside this pure-JVM
// module (see docs/android/deviations/WP-218.md). Unlike the Swift source's synchronous
// `UserDefaults` get/set, the real Android producer (core:datastore.PreferenceStore) is
// asynchronous: reads are `Flow`-backed snapshots, writes are suspend functions (see
// AppearancePreferenceStore/NotificationPreferenceStore in core:datastore for the established
// shape). `LinkPreviewPreferencesSource` mirrors that producer contract - a `StateFlow` of
// the current snapshot for reads, suspend `update` for writes - rather than a synchronous
// `var` shape that would misrepresent how the real DataStore-backed adapter actually behaves.
// `LinkPreviewPreferencesSource` is defined locally (not in core:contracts) because WP-218's
// write_paths do not admit contracts/ edits without a separate amendment.
package com.meshcoreone.android.core.services.content

import kotlinx.coroutines.flow.StateFlow

/** Immutable snapshot of the three booleans [LinkPreviewPreferences] needs. */
data class LinkPreviewPreferencesSnapshot(
    val previewsEnabled: Boolean = false,
    val autoResolveDM: Boolean = true,
    val autoResolveChannels: Boolean = true,
)

/**
 * Narrow producer-role port [LinkPreviewPreferences] depends on. [preferences] is a `StateFlow`
 * so the always-available current value ([StateFlow.value]) can back synchronous policy
 * decisions (e.g. a per-message `shouldAutoResolve` check) while [update] goes through the real
 * asynchronous DataStore write path. A future native adapter (outside this module) implements
 * this against the real DataStore-backed preference store; this module only depends on the
 * shape of the port, not its implementation.
 */
interface LinkPreviewPreferencesSource {
    val preferences: StateFlow<LinkPreviewPreferencesSnapshot>
    suspend fun update(snapshot: LinkPreviewPreferencesSnapshot)
}

/**
 * User preferences for link preview behavior. Mirrors the Swift `LinkPreviewPreferences`
 * struct's policy exactly; only the storage mechanism differs (asynchronous DataStore vs.
 * synchronous `UserDefaults`, both behind [LinkPreviewPreferencesSource]).
 */
class LinkPreviewPreferences(private val source: LinkPreviewPreferencesSource) {
    private val current: LinkPreviewPreferencesSnapshot
        get() = source.preferences.value

    val previewsEnabled: Boolean get() = current.previewsEnabled
    val autoResolveDM: Boolean get() = current.autoResolveDM
    val autoResolveChannels: Boolean get() = current.autoResolveChannels

    suspend fun setPreviewsEnabled(value: Boolean) = source.update(current.copy(previewsEnabled = value))
    suspend fun setAutoResolveDM(value: Boolean) = source.update(current.copy(autoResolveDM = value))
    suspend fun setAutoResolveChannels(value: Boolean) = source.update(current.copy(autoResolveChannels = value))

    /** Whether previews should be shown at all. */
    val shouldShowPreview: Boolean
        get() = previewsEnabled

    /** Whether to auto-resolve based on message type. */
    fun shouldAutoResolve(isChannelMessage: Boolean): Boolean {
        if (!previewsEnabled) return false
        return if (isChannelMessage) autoResolveChannels else autoResolveDM
    }
}
