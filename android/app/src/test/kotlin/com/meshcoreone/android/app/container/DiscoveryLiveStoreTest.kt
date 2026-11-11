// AndroidOnly: WP-311 Mounted discovery invalidation through the production App adapter and real Room store.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import com.meshcoreone.android.core.contracts.FeatureId
import com.meshcoreone.android.core.contracts.FeatureRoute
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.l10n.generated.AppContactsStrings as C
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.NodesDestination
import com.meshcoreone.android.feature.nodes.NodesEntry
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DiscoveryLiveStoreTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun persistedDiscoveryAndContactChangesReachTheSameMountedProductionConsumer() = runTest {
        val h = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = assertNotNull(h.appState.currentRadioId)
            fun frame(value: Int, name: String) = ContactFrame(
                key(value), ContactType.REPEATER, 0u, 255u, Bytes.EMPTY,
                name, 20u, 38.0, -123.0, 20u,
            )
            val first = store.upsertDiscoveredNode(radio, frame(71, "Initial discovery")).node
            host.get().setContent {
                MaterialTheme {
                    NodesEntry(
                        FeatureRoute(FeatureId.NODES), {}, h.container.nodesFeature,
                        destination = NodesDestination.Discovery,
                    )
                }
            }
            fun waitForText(text: String, present: Boolean = true) {
                compose.waitUntil(5_000) {
                    h.settle(1)
                    compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() == present
                }
            }
            waitForText(first.name)
            store.deleteDiscoveredNode(EntityKey(radio, first.id))
            val latest = store.upsertDiscoveredNode(radio, frame(72, "Late discovery")).node
            h.appState.bumpContactsVersion()
            waitForText(latest.name)
            waitForText(first.name, present = false)
            waitForText(host.get().getString(C.contactsDiscoveryAdd))
            store.saveContact(ContactDTO(
                radioId = radio, publicKey = latest.publicKey, name = latest.name,
                typeRawValue = ContactType.REPEATER.rawValue, lastHeardTimestamp = null,
            ))
            h.appState.bumpContactsVersion()
            waitForText(host.get().getString(C.contactsDiscoveryAdded))
            waitForText(host.get().getString(C.contactsDiscoveryAdd), present = false)
            assertEquals(listOf(latest.id), store.fetchDiscoveredNodes(radio).map { it.id })
            assertEquals(latest.publicKey, assertNotNull(store.fetchContact(radio, latest.publicKey)).publicKey)
        } finally {
            host.pause().stop().destroy()
            h.container.close()
        }
    }
}
