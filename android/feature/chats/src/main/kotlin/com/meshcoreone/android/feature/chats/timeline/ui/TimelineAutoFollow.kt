// AndroidOnly: WP-302 Compose reversed-LazyColumn keeps the first visible item by key when a newer row is inserted at index 0, so
// the Swift tiled view's "stay pinned to the newest message" behavior must be an explicit scroll decision here.
package com.meshcoreone.android.feature.chats.timeline.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Mutable bookkeeping owned by one composition; never shared across threads (UI thread only). */
internal class TimelineFollowTracker {
    /** Newest row key already accounted for; `null` until the first rows arrive. */
    var trackedNewestKey by mutableStateOf<String?>(null)

    /** Whether the viewport sat at the newest row before the newest key last changed. */
    var wasAtLatest: Boolean = true
}

/** Use keys because a LazyColumn may still report indices from the previous layout. */
internal fun isViewportAtLatest(visibleKeys: List<Any>, newestKey: String?): Boolean =
    newestKey == null || newestKey in visibleKeys

/**
 * Whether a newly arrived newest row should scroll the list to the latest message: only when a newer row
 * actually appeared, and either the user was already at the latest message or the row is their own send.
 * The first load (no previous key) is handled by initial-anchor resolution, not by following.
 */
internal fun shouldFollowLatest(
    previousNewestKey: String?,
    newestKey: String?,
    wasAtLatest: Boolean,
    newestIsOutgoing: Boolean,
): Boolean = previousNewestKey != null && newestKey != null && previousNewestKey != newestKey &&
    (wasAtLatest || newestIsOutgoing)
