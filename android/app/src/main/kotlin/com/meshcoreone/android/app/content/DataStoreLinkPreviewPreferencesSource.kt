// PortedFrom: MC1/Models/LinkPreviewPreferences.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// Native adaptation (many-to-many port): core:services/content.LinkPreviewPreferencesSource is
// the pure-JVM port role; this file is the real process-owned DataStore producer against the
// real AppStorageKey.linkPreviews* keys already defined in core:datastore (shared with the rest
// of the app - no second DataStore/PreferenceStore here). This header names the actual
// production Swift source this file's producer behavior derives from; it does NOT claim this
// file carries any of WP-218's 154 original test-assertion credit -- that credit is claimed by
// core:services' own LinkPreviewPreferences port and its tests (pure-JVM, run against a fake
// LinkPreviewPreferencesSource), which this adapter supplements rather than duplicates.
// Mirrors the established cross-module producer pattern used by core:designsystem's
// ThemeService: a suspend `create()` factory that performs one real initial DataStore read,
// then an UNDISPATCHED observation coroutine in the caller's process-owned scope keeps the
// StateFlow current. This is not a synchronous var/fake - `update` always round-trips through
// the real suspend `PreferenceStore.update`, and `preferences` only ever reflects values that
// have actually been observed from DataStore (including the first read, before any write).
package com.meshcoreone.android.app.content

import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.PreferenceStore
import com.meshcoreone.android.core.services.content.LinkPreviewPreferencesSnapshot
import com.meshcoreone.android.core.services.content.LinkPreviewPreferencesSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Real [LinkPreviewPreferencesSource] backed by the shared process-owned [PreferenceStore]
 * (see `MeshCoreStorage.get(context).preferences`). Construct via [create]; a future App-303
 * graph assembly step is responsible for obtaining the store and process scope and wiring this
 * into the content services that consume [LinkPreviewPreferencesSource].
 */
class DataStoreLinkPreviewPreferencesSource private constructor(
    private val store: PreferenceStore,
    processScope: CoroutineScope,
    initial: LinkPreviewPreferencesSnapshot,
) : LinkPreviewPreferencesSource {
    private val lifetime = SupervisorJob(
        requireNotNull(processScope.coroutineContext[Job]) { "A process-owned parent Job is required" },
    )
    private val scope = CoroutineScope(processScope.coroutineContext + lifetime)
    private val mutableState = MutableStateFlow(initial)
    override val preferences: StateFlow<LinkPreviewPreferencesSnapshot> = mutableState.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            store.snapshots.collect { snapshot ->
                mutableState.value = LinkPreviewPreferencesSnapshot(
                    previewsEnabled = snapshot[AppStorageKey.linkPreviewsEnabled],
                    autoResolveDM = snapshot[AppStorageKey.linkPreviewsAutoResolveDM],
                    autoResolveChannels = snapshot[AppStorageKey.linkPreviewsAutoResolveChannels],
                )
            }
        }
    }

    override suspend fun update(snapshot: LinkPreviewPreferencesSnapshot) {
        store.update {
            this[AppStorageKey.linkPreviewsEnabled] = snapshot.previewsEnabled
            this[AppStorageKey.linkPreviewsAutoResolveDM] = snapshot.autoResolveDM
            this[AppStorageKey.linkPreviewsAutoResolveChannels] = snapshot.autoResolveChannels
        }
    }

    companion object {
        /** Performs one real DataStore read before returning, so [preferences] is never a fake default. */
        suspend fun create(store: PreferenceStore, processScope: CoroutineScope): DataStoreLinkPreviewPreferencesSource {
            processScope.coroutineContext.ensureActive()
            val snapshot = store.snapshot()
            val initial = LinkPreviewPreferencesSnapshot(
                previewsEnabled = snapshot[AppStorageKey.linkPreviewsEnabled],
                autoResolveDM = snapshot[AppStorageKey.linkPreviewsAutoResolveDM],
                autoResolveChannels = snapshot[AppStorageKey.linkPreviewsAutoResolveChannels],
            )
            return DataStoreLinkPreviewPreferencesSource(store, processScope, initial)
        }
    }
}
