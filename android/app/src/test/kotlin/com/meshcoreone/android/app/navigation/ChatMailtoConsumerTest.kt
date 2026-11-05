// AndroidOnly: WP-306 Mounted App conversation mailto tap through the real dispatcher and Android action adapter.
package com.meshcoreone.android.app.container

import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.navigation.ChatExternalLinkOpener
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.feature.chats.composer.MessageLinkDispatcher
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import kotlin.test.*

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h1000dp-mdpi")
@OptIn(ExperimentalCoroutinesApi::class)
class ChatMailtoConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val url = "mailto:test@example.com?subject=Hello%20Mesh"

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun mountedChatMailtoTapStartsOnlyTheNativeSendToAction() = mountedMailto(false, "MAILTO:test@example.com?subject=Hello%20Mesh")

    @Test fun mountedChatMailtoWithoutHandlerShowsFailureInsteadOfCrashing() = mountedMailto(true)

    private fun mountedMailto(unavailable: Boolean, tappedUrl: String = url) = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio -> radio.knownContacts += contactFrame(key(20), "Mail Peer") }
        val attempts = mutableListOf<Intent>()
        val context = object : ContextWrapper(host.get()) {
            override fun startActivity(intent: Intent) {
                attempts += intent
                if (unavailable) throw ActivityNotFoundException("No mail application")
            }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("contacts finish synchronizing") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val radio = assertNotNull(h.appState.currentRadioId)
            val peer = store.fetchContacts(radio).single { it.name == "Mail Peer" }
            store.saveMessage(MessageDTO(
                radioId = radio, contactID = peer.id, text = tappedUrl, timestamp = 100u,
                createdAt = EPOCH, direction = MessageDirection.INCOMING,
            ))
            compose.runOnUiThread {
                h.container.navigation.navigateToChat(peer)
                host.get().setContent {
                    CompositionLocalProvider(LocalContext provides context) {
                        MeshCoreTheme(motionScale = 0f) {
                            NativeNavigationShell(h.container.navigation, chats = h.container.chatsFeature)
                        }
                    }
                }
            }
            compose.waitUntil(10_000) {
                h.settle()
                compose.onAllNodesWithText(tappedUrl, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            val action = compose.onNodeWithText(tappedUrl, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.CustomActions].single()
            compose.runOnIdle { assertTrue(action.action()) }
            compose.waitForIdle()
            val intent = attempts.single()
            assertEquals(Intent.ACTION_SENDTO, intent.action)
            assertEquals(url, intent.dataString)
            assertNull(intent.component)
            assertNull(intent.selector)
            if (unavailable) {
                compose.onNode(isDialog()).assertExists()
                compose.onNodeWithText(host.get().getString(com.meshcoreone.android.app.R.string.platform_chat_link_unavailable))
                    .assertIsDisplayed()
                compose.onNodeWithText("No mail application").assertDoesNotExist()
            } else compose.onNode(isDialog()).assertDoesNotExist()
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    @Test fun unsafeSchemesNeverReachAnyExternalHandlerAndWebStillUsesItsOwnAction() = runTest {
        val h = ContainerHarness(this, store)
        val native = mutableListOf<Intent>()
        val web = mutableListOf<String>()
        val failures = mutableListOf<Exception>()
        val context = object : ContextWrapper(host.get()) {
            override fun startActivity(intent: Intent) { native += intent }
        }
        try {
            val opener = ChatExternalLinkOpener(context, web::add, failures::add)
            val router = ChatLinkRouter(h.container.chatsFeature.list.links, backgroundScope)
            val dispatcher = MessageLinkDispatcher(
                { error("Not a mention") }, { error("Not a mention") }, { error("Not a mention") },
                { error("Not a mention") }, router::route, opener::open,
            )
            for (bad in listOf(
                "javascript:alert(1)", "intent://x#Intent;end", "file:///private", "content://private",
                "custom://x", "tel:123", "mailto:", "mailto://test@example.com", "mailto:bad%ZZ",
            )) assertFalse(dispatcher.dispatch(bad), bad)
            assertTrue(native.isEmpty())
            assertTrue(web.isEmpty())
            assertTrue(failures.isEmpty())
            assertTrue(dispatcher.dispatch("https://example.com"))
            assertEquals(listOf("https://example.com"), web)
            assertTrue(native.isEmpty())
        } finally { h.container.close() }
    }
}
