// AndroidOnly: WP-311 Mounted contact sharing through the production URI codec and Android clipboard/chooser.
package com.meshcoreone.android.app.container

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.deeplinks.MeshCoreDeepLink
import com.meshcoreone.android.app.deeplinks.MeshCoreUriParser
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.NodesDestination
import com.meshcoreone.android.feature.nodes.NodesEntry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ContactShareConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun selectedContactLinkCopiesAndSharesWithoutChangingThePublicKeyCopyAction() = runTest {
        val h = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = requireNotNull(h.appState.currentRadioId)
            val clipboard = host.get().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            for ((type, name, seed) in listOf(
                Triple(ContactType.CHAT, "Alice & Bob", 51),
                Triple(ContactType.REPEATER, "Relay North", 72),
                Triple(ContactType.ROOM, "Operations Room", 93),
            )) {
                val target = ContactDTO(
                    radioId = radio, publicKey = Bytes(ByteArray(32) { seed.toByte() }),
                    name = name, typeRawValue = type.rawValue, lastHeardTimestamp = null,
                )
                store.saveContact(target)
                compose.runOnUiThread {
                    host.get().setContent {
                        MaterialTheme {
                            NodesEntry(
                                FeatureRoute(FeatureId.NODES), {}, h.container.nodesFeature,
                                NodesDestination.Detail(target),
                            )
                        }
                    }
                }
                val shareLabel = host.get().getString(C.contactsDetailShareContact)
                compose.waitUntil(30_000) {
                    h.settle()
                    compose.onAllNodes(hasText(shareLabel) and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText(shareLabel) and hasClickAction()).performScrollTo().performClick()
                val sentBefore = h.links.radios.single().sent.size
                compose.onNodeWithText(host.get().getString(R.string.contact_link_copy))
                    .performScrollTo().performClick()
                compose.onNodeWithText(host.get().getString(R.string.contact_link_copied)).assertIsNotEnabled()
                val clip = requireNotNull(clipboard.primaryClip)
                assertEquals(name, clip.description.label.toString())
                assertTrue(clip.description.hasMimeType("text/plain"))
                val uri = clip.getItemAt(0).text.toString()
                assertTrue(uri.startsWith("meshcore://contact/add?"))
                val parsed = MeshCoreUriParser.parseContact(uri) as MeshCoreDeepLink.Contact
                assertEquals(name, parsed.name)
                assertEquals(target.publicKey, parsed.publicKey)
                assertEquals(type, parsed.contactType)

                compose.onNodeWithText(host.get().getString(C.contactsQrCopy)).performClick()
                assertEquals(target.publicKey.hexString.uppercase(), clipboard.primaryClip?.getItemAt(0)?.text.toString())
                compose.onNode(
                    hasText(shareLabel) and hasClickAction() and hasAnyAncestor(isDialog()),
                ).performClick()
                val chooser = Shadows.shadowOf(host.get()).nextStartedActivity
                assertEquals(Intent.ACTION_CHOOSER, chooser.action)
                assertEquals(name, chooser.getCharSequenceExtra(Intent.EXTRA_TITLE).toString())
                val send = requireNotNull(chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT))
                assertEquals(Intent.ACTION_SEND, send.action)
                assertEquals("text/plain", send.type)
                assertEquals(uri, send.getStringExtra(Intent.EXTRA_TEXT))
                assertEquals(host.get().getString(C.contactsQrShareSubject), send.getStringExtra(Intent.EXTRA_SUBJECT))
                assertEquals(sentBefore, h.links.radios.single().sent.size)
                compose.onNodeWithText(host.get().getString(C.contactsCommonDone)).performClick()
                compose.runOnUiThread { host.get().setContent {} }
                compose.waitForIdle()
            }
        } finally {
            host.pause().stop().destroy()
            h.container.close()
        }
    }
}
