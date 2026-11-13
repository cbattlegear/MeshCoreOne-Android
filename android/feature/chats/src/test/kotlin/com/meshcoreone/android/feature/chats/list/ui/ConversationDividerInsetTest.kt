// AndroidOnly: WP-306 Row dividers share one inset that follows the avatar's font scaling.
package com.meshcoreone.android.feature.chats.list.ui

import androidx.compose.ui.unit.dp
import kotlin.test.assertEquals
import org.junit.Test

class ConversationDividerInsetTest {
    @Test
    fun `default scale insets divider to the text start`() {
        assertEquals(72.dp, conversationDividerInset(1f))
    }

    @Test
    fun `larger font scale moves the inset with the avatar`() {
        assertEquals(16.dp + 44.dp * 1.5f + 12.dp, conversationDividerInset(1.5f))
    }

    @Test
    fun `scales below one never shrink the inset`() {
        assertEquals(72.dp, conversationDividerInset(0.85f))
    }
}
