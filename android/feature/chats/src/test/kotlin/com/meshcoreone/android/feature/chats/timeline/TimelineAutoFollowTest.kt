// AndroidOnly: WP-302 auto-follow decision for the reversed Compose timeline.
package com.meshcoreone.android.feature.chats.timeline

import com.meshcoreone.android.feature.chats.timeline.ui.isViewportAtLatest
import com.meshcoreone.android.feature.chats.timeline.ui.shouldFollowLatest
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class TimelineAutoFollowTest {
    @Test
    fun `incoming message follows when user was at latest`() =
        assertTrue(shouldFollowLatest("a", "b", wasAtLatest = true, newestIsOutgoing = false))

    @Test
    fun `incoming message does not move a user scrolled away`() =
        assertFalse(shouldFollowLatest("a", "b", wasAtLatest = false, newestIsOutgoing = false))

    @Test
    fun `own send always follows`() =
        assertTrue(shouldFollowLatest("a", "b", wasAtLatest = false, newestIsOutgoing = true))

    @Test
    fun `unchanged newest key never follows (older page prepended or status change)`() {
        assertFalse(shouldFollowLatest("a", "a", wasAtLatest = true, newestIsOutgoing = true))
    }

    @Test
    fun `first load is left to initial anchor resolution`() {
        assertFalse(shouldFollowLatest(null, "a", wasAtLatest = true, newestIsOutgoing = true))
        assertFalse(shouldFollowLatest("a", null, wasAtLatest = true, newestIsOutgoing = true))
    }

    @Test
    fun `viewport is at latest only when index zero is visible or list empty`() {
        assertTrue(isViewportAtLatest(listOf(0, 1, 2), rowCount = 5))
        assertFalse(isViewportAtLatest(listOf(1, 2, 3), rowCount = 5))
        assertTrue(isViewportAtLatest(emptyList(), rowCount = 0))
    }
}
