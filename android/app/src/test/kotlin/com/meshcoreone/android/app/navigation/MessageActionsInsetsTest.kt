// AndroidOnly: WP-309 Message sheet uses the safe viewport and keeps its scrollable actions reachable.
package com.meshcoreone.android.app.navigation

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import com.meshcoreone.android.MainActivity
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.actions.MessageAction
import com.meshcoreone.android.feature.chats.actions.MessageActionsContent
import com.meshcoreone.android.feature.chats.actions.MessageActionsState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "w360dp-h640dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageActionsInsetsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun expandedSheetKeepsPathControlsAndLastActionAboveTheSafeBottomAtLargeFont() {
        val message = MessageDTO(
            radioId = RadioId(UUID(0, 309)), text = "A populated message with path details. ".repeat(5),
            timestamp = 1u, direction = MessageDirection.INCOMING,
            pathNodes = Bytes.of(0xA3), pathLength = 1u, heardRepeats = 1,
        )
        val invoked = mutableListOf<MessageAction>()
        var bottomInset = 0
        compose.runOnUiThread {
            compose.activity.setContent {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    MeshCoreTheme(motionScale = 0f) {
                        AppMessageActionsSheet({}) {
                            bottomInset = WindowInsets.safeDrawing.getBottom(LocalDensity.current)
                            TextButton({}) { Text("More emojis") }
                            MessageActionsContent(
                                message, MessageActionsState(), null, null, listOf("👍", "👎", "❤️", "😂", "😮", "😢"),
                                { invoked += it }, { _, _ -> }, {}, { _, _ -> }, { _, _ -> },
                                modifier = Modifier.weight(1f, fill = false),
                            )
                        }
                    }
                }
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val more = compose.onNodeWithText("More emojis").assertIsDisplayed()
        assertTrue("Sheet must open expanded: ${more.fetchSemanticsNode().boundsInRoot}",
            more.fetchSemanticsNode().boundsInRoot.top < 200f)
        val details = compose.onNode(
            hasText(compose.activity.getString(AppChatsStrings.chatsMessageActionDetails)) and hasClickAction(),
        )
        details.performScrollTo().assertIsDisplayed().performClick()
        val map = compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsPathMap))
        map.performScrollTo().assertIsDisplayed()
        val delete = compose.onNodeWithText(compose.activity.getString(AppChatsStrings.chatsMessageActionDelete))
        delete.performScrollTo().assertIsDisplayed()
        val screenHeight = compose.activity.resources.displayMetrics.heightPixels
        assertTrue("Final action stays outside the navigation safe area",
            delete.fetchSemanticsNode().boundsInRoot.bottom <= screenHeight - bottomInset)
        delete.performClick()
        compose.runOnIdle { assertEquals(listOf(MessageAction.Delete), invoked) }
    }
}
