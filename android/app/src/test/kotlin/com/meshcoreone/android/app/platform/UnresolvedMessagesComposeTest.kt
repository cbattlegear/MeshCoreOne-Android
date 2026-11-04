// AndroidOnly: WP-306 Mounted Chats/Room UI controls; no hardware, RF proof or iOS compatibility claim.
package com.meshcoreone.android.app.platform

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.list.*
import com.meshcoreone.android.feature.chats.list.ui.ChatsListRoot
import com.meshcoreone.android.feature.chats.list.ui.UnresolvedMessagesScreen
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w400dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UnresolvedMessagesComposeTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private val radio = RadioId(UUID(0, 559))
    private fun text(id: Int) = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().getString(id)
    private fun mount(deps: ChatListFeatureDependencies): ActivityController<ComponentActivity> {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        host.get().setContent {
            MeshCoreTheme(motionScale = 0f) {
                ChatsListRoot(deps, null, { error("Unresolved rows cannot open a chat") }, {}, {}, {},
                    { UiText.Verbatim("Controlled failure") })
            }
        }
        return host
    }

    @Test fun emptyContactListStillHasReachableReadOnlyEntryAndDistinctOccurrences() = runTest {
        val original = MessageDTO(
            radioId = radio, text = "Same-content retained occurrence", timestamp = 42u,
            createdAt = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.INCOMING,
            senderKeyPrefix = Bytes.of(1, 2, 3, 4, 5, 6),
        )
        val first = store.saveIncomingDirectMessage(original, LocalDirectContactResolution.NotFound).message
        val second = store.saveIncomingDirectMessage(original.copy(id = UUID.randomUUID()), LocalDirectContactResolution.NotFound).message
        val h = ContainerHarness(this, store)
        val host = mount(h.container.chatsFeature.listFor(radio))
        try {
            compose.onNodeWithText(text(R.string.unresolved_messages_title)).assertIsDisplayed().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(original.text).fetchSemanticsNodes().size == 2
            }
            compose.onNodeWithTag("unresolved-occurrence:${first.id}").assertExists()
            compose.onNodeWithTag("unresolved-occurrence:${second.id}").assertExists()
            compose.onNodeWithText(text(R.string.unresolved_messages_notice)).assertIsDisplayed()
            compose.onNodeWithText(text(AppChatsStrings.chatsComposeNewMessage)).assertDoesNotExist()
            compose.onNodeWithText(text(R.string.unresolved_messages_back)).performClick()
            compose.onNodeWithTag("unresolved-messages").assertDoesNotExist()
            assertTrue(store.fetchContacts(radio).isEmpty())
            assertFalse(requireNotNull(store.fetchMessage(EntityKey(radio, first.id))).isRead)
        } finally { host.pause().stop().destroy(); h.container.close() }
    }

    @Test fun loadFailureHasRetryAndEmptySuccessIsDifferentFromLoading() = runTest {
        val h = ContainerHarness(this, store)
        val bound = h.container.chatsFeature.listFor(radio)
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val data = object : ChatListDataSource by bound.data {
            override suspend fun fetchUnprovedDirectMessages(radioId: RadioId, limit: Long, offset: Long): List<UnprovedDirectMessage> {
                calls++
                if (calls == 1) { gate.await(); throw IllegalStateException("Controlled read failure") }
                return bound.data.fetchUnprovedDirectMessages(radioId, limit, offset)
            }
        }
        val deps = object : ChatListFeatureDependencies by bound { override val data = data }
        val host = mount(deps)
        try {
            compose.onNodeWithText(text(R.string.unresolved_messages_title)).performClick()
            compose.onNodeWithText(text(R.string.unresolved_messages_loading)).assertExists()
            compose.onNodeWithText(text(R.string.unresolved_messages_empty)).assertDoesNotExist()
            compose.runOnIdle { gate.complete(Unit) }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.unresolved_messages_error)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(text(com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings.commonTryAgain)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(text(R.string.unresolved_messages_empty)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(2, calls)
        } finally { host.pause().stop().destroy(); h.container.close() }
    }

    @Test fun switchingRadioClosesOldReadViewAndNeverShowsPreviousRadioText() = runTest {
        val h = ContainerHarness(this, store)
        val other = RadioId(UUID(0, 560))
        val selected = MutableStateFlow<RadioId?>(radio)
        for ((target, value) in listOf(radio to "Radio A only", other to "Radio B only")) {
            store.saveMessage(MessageDTO(radioId = target, text = value, timestamp = 42u,
                direction = MessageDirection.INCOMING))
        }
        val bound = h.container.chatsFeature.listFor(radio)
        val deps = object : ChatListFeatureDependencies by bound { override val currentRadioId = selected }
        val host = mount(deps)
        try {
            compose.onNodeWithText(text(R.string.unresolved_messages_title)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Radio A only").fetchSemanticsNodes().isNotEmpty() }
            compose.runOnIdle { selected.value = other }
            compose.onNodeWithText("Radio A only").assertDoesNotExist()
            compose.onNodeWithTag("unresolved-messages").assertDoesNotExist()
            compose.onNodeWithText(text(R.string.unresolved_messages_title)).performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Radio B only").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Radio A only").assertDoesNotExist()
        } finally { host.pause().stop().destroy(); h.container.close() }
    }

    @Test fun historicalUnknownTimesAreNotInventedAndCandidateLabelDoesNotNameAContact() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide(); host.windowFocusChanged(true)
        var state by mutableStateOf(UnresolvedMessagesState(
            radio, listOf(UnprovedDirectMessage(UUID(0, 1), radio, "Historical original", null, null,
                UnprovedDirectMessageProvenance.LEGACY_UNKNOWN)), isLoading = false,
        ))
        host.get().setContent {
            MeshCoreTheme { UnresolvedMessagesScreen(state, {}, {}, {}) }
        }
        try {
            compose.onNodeWithText(text(R.string.unresolved_messages_legacy)).assertExists()
            compose.onNodeWithText(text(R.string.unresolved_messages_wire_time_unknown)).assertExists()
            compose.onNodeWithText(text(R.string.unresolved_messages_received_time_unknown)).assertExists()
            compose.runOnIdle { state = state.copy(messages = state.messages.map {
                it.copy(provenance = UnprovedDirectMessageProvenance.LOCAL_CANDIDATE)
            }) }
            compose.onNodeWithText(text(R.string.unresolved_messages_candidate)).assertExists()
            compose.onNodeWithText("Historical original").assertExists()
        } finally { host.pause().stop().destroy() }
    }
}
