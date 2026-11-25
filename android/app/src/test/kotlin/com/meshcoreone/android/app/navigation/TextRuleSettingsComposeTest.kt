// AndroidOnly: WP-309 Issue #729 mounted settings persistence/recovery, accessible choices and saved drafts.
package com.meshcoreone.android.app.navigation

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.app.container.AppSettingsFeature
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TextRulePersisting
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.database.TextRulePreferenceEntity
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TextRuleSettingsComposeTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun failedSaveKeepsTheDraftAndLastCommittedRulesThenRetryUsesTheRealStore() = runTest {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        val reports = mutableListOf<Throwable>()
        try {
            host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                AppTextRulesContent(store, {}, { _, failure -> reports += failure })
            } }
            val resources = host.get().resources
            val add = resources.getString(R.string.text_rules_add)
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(resources.getString(R.string.text_rules_empty)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasSetTextAction()).performTextInput("retain my draft")
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_settings_rule BEFORE INSERT ON text_rule_preferences BEGIN SELECT RAISE(FAIL, 'test settings failure'); END")
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(add))
            compose.onNodeWithText(add).assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5_000) { reports.isNotEmpty() }
            assertTrue(store.textRulesPreference().rules.isEmpty())
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(resources.getString(R.string.text_rules_failed)))
            compose.onNodeWithText(resources.getString(R.string.text_rules_failed)).assertIsDisplayed()
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_settings_rule")
            compose.onNodeWithText(resources.getString(R.string.text_rules_retry)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasSetTextAction())
            compose.onNode(hasSetTextAction()).assertTextContains("retain my draft")
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(add))
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasText(add) and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(add).performClick()
            compose.waitUntil(5_000) {
                runBlocking { store.textRulesPreference().rules.any { it.phrase == "retain my draft" } }
            }
            val delete = resources.getString(R.string.text_rules_delete, "retain my draft")
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(delete))
            compose.onNodeWithText(delete).assertIsDisplayed()
            assertEquals(listOf("retain my draft"), store.textRulesPreference().rules.map { it.phrase })
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_settings_rule")
            host.pause().stop().destroy()
        }
    }

    @Test fun archiveRemainsReviewableWhenPreferenceDecodingFails() = runTest {
        val radio = RadioId(UUID(0, 1))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "Radio"))
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter")),
            selections = it.selections + (TextRuleKind.DIRECT_MESSAGE to TextRuleSelection(TextRuleSelectionMode.ALL))) }
        val message = MessageDTO(radioId = radio, text = "  FILTER original text  ", timestamp = 100u,
            direction = MessageDirection.INCOMING, senderKeyPrefix = Bytes(ByteArray(6) { 2 }))
        store.admitTextMessage(message, null, null, message.text, 0u, false, message.createdAt, false)
        db.textRules().preference(TextRulePreferenceEntity(payload = "unreadable"))
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        try {
            host.get().setContent { MeshCoreTheme(motionScale = 0f) { AppTextRulesContent(store, {}, { _, _ -> }) } }
            val resources = host.get().resources
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(resources.getString(R.string.text_rules_load_failed)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(resources.getString(R.string.text_rules_recent)).performClick()
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(message.text))
            compose.onNodeWithText(message.text).assertIsDisplayed()
            compose.onNodeWithText(resources.getString(R.string.text_rules_matched, "filter")).assertIsDisplayed()
        } finally { host.pause().stop().destroy() }
    }

    @Test fun retryRestartsAFailedArchiveObserverAndShowsLaterBlockedMessages() = runTest {
        val radio = RadioId(UUID(0, 1))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "Radio"))
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter")),
            selections = it.selections + (TextRuleKind.DIRECT_MESSAGE to TextRuleSelection(TextRuleSelectionMode.ALL))) }
        val subscriptions = AtomicInteger()
        val interrupted = object : TextRulePersisting by store {
            override val textRuleChanges = flow {
                if (subscriptions.getAndIncrement() == 0) throw IllegalStateException("observer failed")
                emitAll(store.textRuleChanges)
            }
        }
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        try {
            host.get().setContent { MeshCoreTheme(motionScale = 0f) { AppTextRulesContent(interrupted, {}, { _, _ -> }) } }
            val resources = host.get().resources
            val retry = resources.getString(R.string.text_rules_retry)
            compose.waitUntil(5_000) { compose.onAllNodesWithText(retry).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(retry).performClick()
            compose.waitUntil(5_000) { subscriptions.get() >= 2 }
            val message = MessageDTO(radioId = radio, text = "filter after retry", timestamp = 101u,
                direction = MessageDirection.INCOMING, senderKeyPrefix = Bytes(ByteArray(6) { 2 }))
            store.admitTextMessage(message, null, null, message.text, 0u, false, message.createdAt, false)
            compose.onNodeWithText(resources.getString(R.string.text_rules_recent)).performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(message.text).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(message.text).assertIsDisplayed()
        } finally { host.pause().stop().destroy() }
    }

    @Test fun mountedSettingsTrackLiveInventoryAndExternalRuleChangesWithoutArchiveEvents() = runTest {
        val radio = RadioId(UUID(0, 1))
        val device = DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "Radio")
        val peer = ContactDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 2 }), name = "Peer",
            typeRawValue = ContactType.CHAT.rawValue, lastHeardTimestamp = 0u)
        store.saveDevice(device)
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        val reports = mutableListOf<Throwable>()
        val loadingInventory = AtomicReference<List<TextRuleConversation>>(emptyList())
        val loadedInventory = AtomicReference<List<TextRuleConversation>>(emptyList())
        val observing = object : TextRulePersisting by store {
            override suspend fun textRuleConversations() =
                store.textRuleConversations().also { loadingInventory.set(it) }
            override suspend fun textRulesPreference() =
                store.textRulesPreference().also { loadedInventory.set(loadingInventory.get()) }
        }
        try {
            host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                AppTextRulesContent(observing, {}, { _, failure -> reports += failure })
            } }
            val resources = host.get().resources
            fun chat(name: String, radioName: String = "Radio") =
                resources.getString(R.string.text_rules_conversation, name, radioName)
            fun waitForChat(name: String, radioName: String = "Radio") {
                compose.waitUntil(5_000) {
                    loadedInventory.get().any { it.name == name && it.radioName == radioName }
                }
                compose.waitForIdle()
                val label = chat(name, radioName)
                compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(label))
                compose.onNodeWithText(label).assertIsDisplayed()
            }
            fun waitForRemoval(name: String, radioName: String) {
                compose.waitUntil(5_000) {
                    loadedInventory.get().none { it.name == name && it.radioName == radioName }
                }
                compose.waitForIdle()
                compose.onAllNodesWithText(chat(name, radioName)).assertCountEquals(0)
            }
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(resources.getString(R.string.text_rules_empty)).fetchSemanticsNodes().isNotEmpty()
            }
            val roomHeading = resources.getString(R.string.text_rules_rooms)
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(roomHeading))
            store.saveContact(peer)
            waitForChat("Peer")

            val conversation = store.textRuleConversations().single()
            store.setTextRuleOverride(conversation.key, TextRuleOverride.ON)
            val on = resources.getString(R.string.text_rules_scoped_choice, chat("Peer"),
                resources.getString(R.string.text_rules_on))
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasContentDescription(on))
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription(on) and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }
            store.updateTextRules { it.copy(selections = it.selections + (
                TextRuleKind.DIRECT_MESSAGE to TextRuleSelection(TextRuleSelectionMode.ALL))) }
            val all = resources.getString(R.string.text_rules_scoped_choice,
                resources.getString(R.string.text_rules_dm), resources.getString(R.string.text_rules_all))
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasContentDescription(all))
            compose.waitUntil(5_000) {
                compose.onAllNodes(hasContentDescription(all) and isSelected()).fetchSemanticsNodes().isNotEmpty()
            }

            store.saveContact(peer.copy(name = "Renamed peer"))
            waitForChat("Renamed peer")
            store.saveDevice(device.copy(nodeName = "Renamed radio"))
            waitForChat("Renamed peer", "Renamed radio")
            store.deleteContact(EntityKey(radio, peer.id))
            waitForRemoval("Renamed peer", "Renamed radio")

            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(resources.getString(R.string.text_rules_recent)))
            val room = RemoteNodeSessionDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 3 }),
                name = "Room", role = RemoteNodeRole.ROOM_SERVER)
            store.saveRemoteNodeSessionDTO(room)
            waitForChat("Room", "Renamed radio")
            store.saveRemoteNodeSessionDTO(room.copy(name = "Renamed room"))
            waitForChat("Renamed room", "Renamed radio")
            store.deleteRemoteNodeSession(EntityKey(radio, room.id))
            waitForRemoval("Renamed room", "Renamed radio")

            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(resources.getString(R.string.text_rules_dm)))
            val channel = ChannelInfo(2u, "Private", Bytes(ByteArray(16) { 4 }))
            store.saveChannel(radio, channel)
            waitForChat("Private", "Renamed radio")
            val current = assertNotNull(store.fetchChannel(radio, 2u))
            store.saveChannel(current.copy(name = "Renamed channel"))
            waitForChat("Renamed channel", "Renamed radio")
            store.invalidateChannelOccupant(radio, 2u)
            waitForRemoval("Renamed channel", "Renamed radio")

            assertTrue(store.recentlyBlockedTexts().isEmpty())
            assertTrue(reports.isEmpty(), reports.toString())
        } finally { host.pause().stop().destroy() }
    }

    @Test fun actualSettingsEntryRestoresTheEditorDraftAndArchiveDestination() = runTest {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        val h = ContainerHarness(this, store)
        val storage = MeshCoreStorage.get(host.get())
        val restoration = StateRestorationTester(object : ComposeContentTestRule, ComposeTestRule by compose {
            override fun setContent(composable: @Composable () -> Unit) { host.get().setContent(content = composable) }
        })
        try {
            val settings = AppSettingsFeature.create(h.container, storage)
            compose.runOnUiThread {
                h.container.navigation.selectTab(AppTab.SETTINGS)
                h.container.navigation.navigateToSetting(SettingsDetail.CHATS)
            }
            restoration.setContent {
                val navigation by h.container.navigation.state.collectAsState()
                MeshCoreTheme(motionScale = 0f) {
                    AppSettingsContent(settings, navigation.activeStack.last().destination, h.container.navigation)
                }
            }
            val resources = host.get().resources
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(resources.getString(R.string.text_rules_title)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(resources.getString(R.string.text_rules_title)).performScrollTo().performClick()
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText(resources.getString(R.string.text_rules_empty)).fetchSemanticsNodes().isNotEmpty()
            }
            val form = hasSetTextAction()
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(form)
            compose.onNode(form).performTextInput("persist the actual settings draft")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(form)
            compose.onNode(form).assertTextContains("persist the actual settings draft")
            val recent = resources.getString(R.string.text_rules_recent)
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasText(recent))
            compose.onNodeWithText(recent).performClick()
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText(resources.getString(R.string.text_rules_recent_description)).assertIsDisplayed()
            assertTrue(h.lifecycle.created.isEmpty(), "offline settings must not create a radio session")
        } finally {
            host.pause().stop().destroy()
            h.container.close()
            storage.close()
        }
    }

    @Test fun doubleFontDraftAndAccessibleChatChoicesSurviveSavedStateRestoration() {
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        val restoration = StateRestorationTester(object : ComposeContentTestRule, ComposeTestRule by compose {
            override fun setContent(composable: @Composable () -> Unit) { host.get().setContent(content = composable) }
        })
        val key = TextRuleKey(RadioId(UUID(0, 1)), UUID(0, 2))
        var preference by mutableStateOf(TextRulesPreference())
        var override by mutableStateOf(TextRuleOverride.INHERIT)
        try {
            restoration.setContent {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    MeshCoreTheme(motionScale = 0f) {
                        TextRuleSettingsContent(TextRuleSettingsSnapshot(preference,
                            listOf(TextRuleConversation(key, TextRuleKind.PUBLIC_CHANNEL, "Peer", "Radio", override)),
                            emptyList()), false, false, false, {}, {},
                            { preference = it(preference) }, { actual, value -> assertEquals(key, actual); override = value })
                    }
                }
            }
            val resources = host.get().resources
            val form = hasSetTextAction()
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(form)
            compose.onNode(form).performTextInput("保存 café*")
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(form)
            compose.onNode(form).assertTextContains("保存 café*")
            val fixed = resources.getString(R.string.text_rules_scoped_choice,
                resources.getString(R.string.text_rules_public), resources.getString(R.string.text_rules_fixed))
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasContentDescription(fixed))
            compose.onNodeWithContentDescription(fixed).performClick().assertIsSelected()
            assertEquals(TextRuleSelectionMode.FIXED, preference.selections.getValue(TextRuleKind.PUBLIC_CHANNEL).mode)
            val chatLabel = resources.getString(R.string.text_rules_conversation, "Peer", "Radio")
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasContentDescription(chatLabel))
            compose.onNodeWithContentDescription(chatLabel).performClick().assertIsOn()
            assertEquals(setOf(key), preference.selections.getValue(TextRuleKind.PUBLIC_CHANNEL).keys)
            val off = resources.getString(R.string.text_rules_scoped_choice, chatLabel,
                resources.getString(R.string.text_rules_off))
            compose.onNodeWithTag("text-rule-settings").performScrollToNode(hasContentDescription(off))
            compose.onNodeWithContentDescription(off).performClick().assertIsSelected()
            assertEquals(TextRuleOverride.OFF, override)
        } finally { host.pause().stop().destroy() }
    }
}
