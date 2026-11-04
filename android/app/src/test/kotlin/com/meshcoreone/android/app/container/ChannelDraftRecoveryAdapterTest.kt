// AndroidOnly: WP-308 Actual App recovery adapter reads retained Room rows without send or origin adoption.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.feature.chats.composer.ComposerTarget
import com.meshcoreone.android.feature.chats.composer.RecoveredDraftStateHolder
import com.meshcoreone.android.feature.chats.composer.ui.recoveredDraftLabels
import com.meshcoreone.android.feature.chats.composer.ui.RecoveredDraftList
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.ui.UiText
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31, 37], qualifiers = "en-rUS-w390dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChannelDraftRecoveryAdapterTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private val radio = RadioId(UUID(0, 1))
    private fun channel(secret: Int) = ChannelInfo(2u, "Channel", Bytes(ByteArray(16) { secret.toByte() }))
    private fun row(id: Long, origin: UUID?) = MessageDTO(
        id = UUID(0, id), radioId = radio, channelIndex = 2u,
        text = "  original \u4e2d\uD83D\uDE80 $id  ", timestamp = 100u,
        createdAt = Instant.ofEpochSecond(id), channelOccupantID = origin, status = MessageStatus.FAILED,
    )

    @Test fun replacementListAndExactCopyKeepBothKnownAndUnknownOriginalsUnchanged() = runBlocking {
        store.saveChannel(radio, channel(1))
        val originA = assertNotNull(store.fetchChannelOccupantID(radio, 2u))
        val old = row(10, originA)
        val unknown = row(11, null)
        store.saveMessage(old)
        store.saveMessage(unknown)
        store.saveChannel(radio, channel(2))
        val target = ComposerTarget.Channel(assertNotNull(store.fetchChannel(radio, 2u)))
        val current = row(12, assertNotNull(store.fetchChannelOccupantID(radio, 2u)))
        store.saveMessage(current)
        val copies = mutableListOf<Pair<MessageDTO, ComposerTarget>>()
        val adapter = ChannelDraftRecoveryAdapter(radio, store, store) { source, destination ->
            copies += source to destination
        }
        assertEquals(listOf(unknown, old), adapter.fetchPage(radio, 40, 0))
        assertTrue(copies.isEmpty())
        val changed = old.copy(text = "latest exact-key text", status = MessageStatus.SENT)
        store.saveMessage(changed)
        adapter.copyToDraft(EntityKey(radio, old.id), target)
        assertEquals(listOf(changed to target), copies)
        assertEquals(changed, store.fetchMessage(EntityKey(radio, old.id)))
        assertEquals(unknown, store.fetchMessage(EntityKey(radio, unknown.id)))
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        assertNotEquals(current.channelOccupantID, copies.single().first.channelOccupantID)
    }

    @Test fun missingOrForeignSourceNeverInvokesDestinationCopy() = runBlocking {
        val adapter = ChannelDraftRecoveryAdapter(radio, store, store) { _, _ ->
            fail("Invalid source must not reach destination copy")
        }
        val target = ComposerTarget.Channel(ChannelDTO(
            id = UUID(0, 20), radioId = radio, index = 2u, name = "Selected",
            secret = Bytes(ByteArray(16)),
        ))
        val missing = assertFailsWith<PersistenceStoreException> {
            adapter.copyToDraft(EntityKey(radio, UUID(0, 21)), target)
        }
        assertEquals(PersistenceStoreError.MessageNotFound, missing.error)
        val foreign = RadioId(UUID(0, 99))
        val rejected = assertFailsWith<PersistenceStoreException> {
            adapter.copyToDraft(EntityKey(foreign, UUID(0, 21)), target)
        }
        assertEquals(PersistenceStoreError.InvalidData, rejected.error)
        assertFailsWith<PersistenceStoreException> { adapter.fetchPage(foreign, 40, 0) }
    }

    @Test fun deletedSourceAndDestinationConflictDoNotDeleteOrEnqueueRecoveryRows() = runBlocking {
        store.saveChannel(radio, channel(1))
        val original = row(10, null)
        store.saveMessage(original)
        val target = ComposerTarget.Channel(assertNotNull(store.fetchChannel(radio, 2u)))
        val conflict = IllegalStateException("Newer destination draft")
        val adapter = ChannelDraftRecoveryAdapter(radio, store, store) { _, _ -> throw conflict }
        assertEquals(listOf(original), adapter.fetchPage(radio, 40, 0))
        assertSame(conflict, assertFailsWith<IllegalStateException> {
            adapter.copyToDraft(EntityKey(radio, original.id), target)
        })
        assertEquals(original, store.fetchMessage(EntityKey(radio, original.id)))
        assertTrue(store.fetchPendingSends(radio).isEmpty())
        store.deleteMessage(EntityKey(radio, original.id))
        val missing = assertFailsWith<PersistenceStoreException> {
            adapter.copyToDraft(EntityKey(radio, original.id), target)
        }
        assertEquals(PersistenceStoreError.MessageNotFound, missing.error)
    }

    @Test fun mountedFont200RecoveryRequiresSelectionAndCopiesIntoAppDraftWithoutSending() = runTest {
        store.saveChannel(radio, channel(1))
        val originA = assertNotNull(store.fetchChannelOccupantID(radio, 2u))
        val originals = (1L..45L).map { row(it, if (it % 2L == 0L) originA else null) }
        originals.forEach { store.saveMessage(it) }
        store.saveChannel(radio, channel(2))
        val selected = ComposerTarget.Channel(assertNotNull(store.fetchChannel(radio, 2u)))
        val rig = ContainerHarness(this, store)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        try {
            val drafts = rig.container.chatsFeature.drafts
            val adapter = ChannelDraftRecoveryAdapter(radio, store, store) { current, destination ->
                val revision = drafts.revision(destination.conversationId)
                check(drafts.restoreIfUnchanged(destination.conversationId, revision, current.text)) {
                    "Destination already has newer draft text"
                }
            }
            val holder = RecoveredDraftStateHolder(radio, adapter, { _, failure -> throw failure })
            holder.refresh()
            val destination = mutableStateOf<ComposerTarget?>(null)
            host.get().setContent {
                MeshCoreTheme {
                    CompositionLocalProvider(LocalDensity provides Density(1f, 2f)) {
                        val state by holder.state.collectAsState()
                        val scope = rememberCoroutineScope()
                        RecoveredDraftList(
                            state = state,
                            labels = recoveredDraftLabels(),
                            destinationLabel = destination.value?.let { UiText.Verbatim("Selected channel B") },
                            originLabel = { message ->
                                UiText.Resource(if (message.channelOccupantID == null) {
                                    com.meshcoreone.android.core.l10n.R.string.wp308_recovery_status_unknown_origin
                                } else {
                                    com.meshcoreone.android.core.l10n.R.string.wp308_recovery_status_replaced_occupant
                                })
                            },
                            failureText = { checkNotNull(it.message) },
                            onSelect = holder::select,
                            onRefresh = { scope.launch { holder.refresh() } },
                            onLoadMore = { scope.launch { holder.loadMore() } },
                            onChooseChat = { destination.value = selected },
                            onCopy = { scope.launch { holder.copySelectedTo(checkNotNull(destination.value)) } },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
            fun node(text: String): SemanticsNodeInteraction {
                compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(text))
                return compose.onNodeWithText(text)
            }
            node("Copy to selected chat").assertIsNotEnabled()
            node("Load more recovered drafts").performClick()
            compose.waitUntil(5_000) { holder.state.value.messages.size == 45 }
            val oldest = originals.first()
            node(oldest.text).performClick()
            node("Copy to selected chat").assertIsNotEnabled()
            assertNull(drafts.get(selected.conversationId))
            node("Choose chat").performClick()
            node("Selected channel B").assertIsDisplayed()
            assertNull(drafts.get(selected.conversationId))
            node("Copy to selected chat").performClick()
            compose.waitUntil(5_000) { drafts.get(selected.conversationId) == oldest.text }
            assertNull(holder.state.value.selected)
            assertEquals(45, holder.state.value.messages.size)
            originals.forEach { assertEquals(it, store.fetchMessage(EntityKey(radio, it.id))) }
            assertTrue(store.fetchPendingSends(radio).isEmpty())
            assertTrue(rig.links.links.isEmpty())
        } finally {
            host.pause().stop().destroy()
            rig.container.close()
        }
    }
}
