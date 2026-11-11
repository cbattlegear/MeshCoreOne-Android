// AndroidOnly: WP-306 Mounted navigation metadata updates through the process graph and real Room store.
package com.meshcoreone.android.app.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversationChromeConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun retainedRoutesObserveMetadataWithoutChangingTheirNavigationIdentity() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val contact = ContactDTO(
                radioId = radio, publicKey = Bytes(ByteArray(32) { 5 }), name = "Alice",
                nickname = "Ally", outPathLength = 2u, lastHeardTimestamp = null,
            )
            store.saveContact(contact)
            h.container.navigation.navigateToChat(contact)
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature) { _, _ ->
                            Text("Conversation body")
                        }
                    }
                }
            }
            fun awaitTitle(title: String) {
                compose.waitUntil(30_000) {
                    h.settle()
                    compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithTag("navigation-heading").assertTextEquals(title)
            }
            awaitTitle("Ally")
            val directRoute = h.container.navigation.state.value.activeStack.last()
            store.saveContact(contact.copy(nickname = "Updated Ally", outPathLength = 255u))
            h.appState.bumpContactsVersion()
            awaitTitle("Updated Ally")
            compose.onNodeWithTag("navigation-subtitle").assertTextEquals(
                host.get().getString(AppChatsStrings.chatsConnectionStatusFloodRouting),
            )
            assertEquals(directRoute, h.container.navigation.state.value.activeStack.last())

            val channel = seedReconciledChannel(ChannelDTO(radioId = radio, index = 3u, name = "Operations"))
            h.container.navigation.navigateToChannel(channel)
            awaitTitle("Operations")
            val channelRoute = h.container.navigation.state.value.activeStack.last()
            store.saveChannel(channel.copy(name = "Updated Operations").withFloodScope(ChannelFloodScope.Region("Germany")))
            h.appState.refreshConversations()
            awaitTitle("Updated Operations")
            compose.onNodeWithTag("navigation-subtitle").assertTextEquals(
                AppChatsStrings.chatsChannelHeaderRegion(host.get().resources, "Germany"),
            )
            assertEquals(channelRoute, h.container.navigation.state.value.activeStack.last())

            seedReconciledChannel(channel.copy(name = "Replacement", secret = Bytes(ByteArray(16) { 9 })))
            h.appState.refreshConversations()
            awaitTitle(host.get().getString(com.meshcoreone.android.core.l10n.R.string.tab_chats))
            compose.onNodeWithTag("navigation-subtitle").assertDoesNotExist()
            compose.onNodeWithText("Replacement").assertDoesNotExist()
            assertEquals(channelRoute, h.container.navigation.state.value.activeStack.last())
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            h.container.close()
        }
    }
}
