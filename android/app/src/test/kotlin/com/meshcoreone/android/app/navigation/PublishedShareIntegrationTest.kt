// AndroidOnly: WP-404 Exercise a published direct-share intent through the mounted app and Room queue.
// ReferenceInput: MC1/Intents/SendMessageIntent.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.navigation

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.container.*
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.platform.shortcuts.AndroidShortcutText
import com.meshcoreone.android.platform.shortcuts.ShareTargetActivity
import com.meshcoreone.android.platform.shortcuts.ShortcutContract
import com.meshcoreone.android.platform.shortcuts.TargetIdentity
import java.util.Collections
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PublishedShareIntegrationTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun publishedSharesRequireConfirmationAndKeepRecipientHistoriesAndDraftsSeparate() = runTest {
        val keys = listOf(key(7), Bytes(ByteArray(32) { if (it % 2 == 0) 7 else 1 }))
        val names = listOf("Complete seven", "Seven one")
        val bodies = listOf("First shared message", "Second shared message")
        val observedAtRadio = Collections.synchronizedList(mutableListOf<MessageStatus>())
        val h = ContainerHarness(this, store, identity = { key(9) })
        h.links.configureRadio = { firmware ->
            keys.zip(names).forEach { (key, name) -> firmware.knownContacts += contactFrame(key, name) }
            firmware.respond = { frame ->
                if (frame[0].toInt() == 2) {
                    observedAtRadio += runBlocking {
                        val radio = requireNotNull(h.appState.currentRadioId)
                        val pending = store.fetchPendingSends(radio).single()
                        requireNotNull(store.fetchMessage(EntityKey(radio, pending.messageID))).status
                    }
                    sentAndDelivered(frame, key(9))
                } else null
            }
        }
        val manager = requireNotNull(host.get().getSystemService(ShortcutManager::class.java))
        try {
            h.manager.connect(h.target())
            h.eventually("share session READY") { h.manager.connectionState == DeviceConnectionState.READY }
            val radio = requireNotNull(h.appState.currentRadioId)
            val contacts = keys.map { requireNotNull(store.fetchContact(radio, it)) }
            val conversations = contacts.map { ChatConversationID.dm(radio, it.id) }
            conversations.forEachIndexed { index, id -> h.draftStore.setDraft("Existing draft $index", id) }
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) { AppShortcutContent(h.container) } }
            }
            val ids = contacts.map { ShortcutContract.SEND_SHORTCUT_PREFIX + TargetIdentity.contactId(it) }
            compose.waitUntil(30_000) {
                h.settle()
                manager.dynamicShortcuts.map { it.id }.containsAll(ids)
            }
            val text = AndroidShortcutText(host.get())
            for (index in contacts.indices) {
                val published = manager.dynamicShortcuts.single { it.id == ids[index] }
                assertTrue(published.categories?.contains(ShortcutContract.SHARE_CATEGORY) == true)
                val intent = Intent(host.get(), ShareTargetActivity::class.java).apply {
                    action = Intent.ACTION_SEND
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SHORTCUT_ID, published.id)
                    putExtra(Intent.EXTRA_TEXT, bodies[index])
                }
                compose.runOnUiThread { Robolectric.buildActivity(ShareTargetActivity::class.java, intent).create() }
                compose.waitUntil(30_000) {
                    h.settle()
                    compose.onAllNodesWithText(text.sendConfirm(names[index])).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals(index, h.links.radios.single().sent.count { it[0].toInt() == 2 })
                assertTrue(store.fetchMessages(EntityKey(radio, contacts[index].id), 100, 0).isEmpty())
                compose.onNodeWithText(host.get().getString(AppLocalizableStrings.commonOk)).performClick()
                h.eventually("shared message delivered") {
                    runBlocking {
                        store.fetchMessages(EntityKey(radio, contacts[index].id), 100, 0)
                            .singleOrNull()?.status == MessageStatus.DELIVERED
                    }
                }
                assertEquals(listOf(bodies[index]), store.fetchMessages(EntityKey(radio, contacts[index].id), 100, 0).map { it.text })
            }
            assertEquals(listOf(MessageStatus.PENDING, MessageStatus.PENDING), observedAtRadio.toList())
            val sends = h.links.radios.single().sent.filter { it[0].toInt() == 2 }
            assertEquals(keys.map { it.prefix(6) }, sends.map { Bytes(it.toByteArray().copyOfRange(7, 13)) })
            contacts.forEachIndexed { index, contact ->
                assertEquals(listOf(bodies[index]), store.fetchMessages(EntityKey(radio, contact.id), 100, 0).map { it.text })
            }
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            conversations.forEachIndexed { index, id -> assertEquals("Existing draft $index", h.draftStore.draft(id)) }
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            compose.waitForIdle()
            manager.removeAllDynamicShortcuts()
            h.container.close()
        }
    }
}
