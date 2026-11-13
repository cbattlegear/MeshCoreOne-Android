// AndroidOnly: WP-302 Compose reversed-LazyColumn keeps the first visible item by key when a newer row is inserted at index 0, so
// the Swift tiled view's "stay pinned to the newest message" behavior must be an explicit scroll decision here.
package com.meshcoreone.android.feature.chats.timeline.ui

/** Mutable bookkeeping owned by one composition; never shared across threads (UI thread only). */
internal class TimelineFollowTracker {
    /** Newest row key already accounted for; `null` until the first rows arrive. */
    var trackedNewestKey: String? = null

    /** Whether the viewport sat at the newest row before the newest key last changed. */
    var wasAtLatest: Boolean = true
}

/** The newest row is visible when reversed-list index 0 is on screen. */
internal fun isViewportAtLatest(visibleIndices: List<Int>, rowCount: Int): Boolean =
    rowCount == 0 || visibleIndices.any { it == 0 }

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
