// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-405 Actual App dialog consent, radio write and Room preference regression.
package com.meshcoreone.android.app.container

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.navigation.AppPendingChannel
import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
class ChannelConfirmationConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyHost() { host.pause().stop().destroy() }

    @Test fun externalPrivateHashtagWarningPrecedesConsentAndPersistsExactSecretAndScope() = runTest {
        confirmPrivateHashtag(fromChat = false, name = "#avion-testing2", region = "testregion")
    }

    @Test fun chatPrivateHashtagWarningPrecedesConsentAndPersistsExactSecretAndScope() = runTest {
        confirmPrivateHashtag(fromChat = true, name = "#avion-testing2", region = "testregion")
    }

    @Test fun externalPrivateTestHashtagWithoutRegionRemainsJoinableAfterWarning() = runTest {
        confirmPrivateHashtag(fromChat = false, name = "#test", region = null)
    }

    @Test fun chatPrivateTestHashtagWithoutRegionRemainsJoinableAfterWarning() = runTest {
        confirmPrivateHashtag(fromChat = true, name = "#test", region = null)
    }

    @Test fun disconnectedConfirmationShowsMetadataButCannotWriteAndCancellationClearsIt() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val navigation = h.container.navigation
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            external.routeExternal(
                "meshcore://channel/add?name=%23test&secret=00112233445566778899AABBCCDDEEFF&region_scope=testregion",
            )
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                    AppPendingChannel(h.container.chatsFeature, navigation)
                } }
            }
            compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_hashtagsecretmismatch))
                .assertIsDisplayed()
            compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_regionscope, "testregion"))
                .assertIsDisplayed()
            compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
            assertTrue(h.links.radios.isEmpty())
            compose.onNodeWithText(host.get().getString(L.commonCancel)).performClick()
            assertNull(navigation.state.value.pendingChannelLink)
            assertTrue(h.links.radios.isEmpty())
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    @Test fun everyStagingHasItsOwnIdentityAndStaleDismissOrCompletionCannotAcknowledgeReplacement() {
        val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
        for (hashtag in listOf(false, true)) {
            for (identical in listOf(false, true)) {
                val navigation = NavigationCoordinator()
                val link = ChannelLinkRequest("#test", secret, "testregion", true)
                fun stage(replacement: Boolean) {
                    if (hashtag) navigation.stageHashtag(HashtagJoinRequest(if (replacement && !identical) "#other" else "#test"))
                    else navigation.stageChannelLink(if (replacement && !identical) link.copy(name = "#other") else link)
                }
                stage(false)
                val first = assertNotNull(if (hashtag) navigation.state.value.pendingHashtagConfirmation
                    else navigation.state.value.pendingChannelConfirmation)
                stage(true)
                val replacement = assertNotNull(if (hashtag) navigation.state.value.pendingHashtagConfirmation
                    else navigation.state.value.pendingChannelConfirmation)
                assertNotEquals(first.deliveryId, replacement.deliveryId)
                val staged = navigation.state.value
                assertFalse(navigation.completeChannelConfirmation(first.deliveryId))
                assertEquals(staged, navigation.state.value)
                val oldResult = ChannelDTO(radioId = RadioId(java.util.UUID(0, 1)), index = 1u, name = "#test", secret = secret)
                assertFalse(navigation.completeChannelConfirmation(first.deliveryId, oldResult))
                assertEquals(staged, navigation.state.value, "An old successful write must not navigate over B")
                assertTrue(navigation.completeChannelConfirmation(replacement.deliveryId))
                assertNull(navigation.state.value.pendingChannelLink)
                assertNull(navigation.state.value.pendingHashtag)
            }
        }
    }

    @Test fun independentSlotsSurviveEitherStagingOrderAndAtomicSuccessfulCompletion() {
        val link = ChannelLinkRequest("#test", Bytes.fromHex("00112233445566778899AABBCCDDEEFF"), "testregion", true)
        val hashtag = HashtagJoinRequest("#independent")
        for (hashtagFirst in listOf(false, true)) {
            for (completeHashtag in listOf(false, true)) {
                for (success in listOf(false, true)) {
                    val navigation = NavigationCoordinator()
                    if (hashtagFirst) navigation.stageHashtag(hashtag)
                    navigation.stageChannelLink(link)
                    if (!hashtagFirst) navigation.stageHashtag(hashtag)
                    val initial = navigation.state.value
                    val displayed = assertNotNull(if (completeHashtag) initial.pendingHashtagConfirmation
                        else initial.pendingChannelConfirmation)
                    val channel = ChannelDTO(radioId = RadioId(java.util.UUID(0, 2)), index = 1u, name = "#joined")
                    assertTrue(navigation.completeChannelConfirmation(displayed.deliveryId, channel.takeIf { success }))
                    val completed = navigation.state.value
                    if (completeHashtag) {
                        assertEquals(initial.pendingChannelConfirmation, completed.pendingChannelConfirmation)
                        assertNull(completed.pendingHashtag)
                    } else {
                        assertEquals(initial.pendingHashtagConfirmation, completed.pendingHashtagConfirmation)
                        assertNull(completed.pendingChannelLink)
                    }
                    assertEquals(channel.takeIf { success }, completed.pendingChannel)
                    assertEquals(if (success) 2 else 1, completed.activeStack.size)
                }
            }
        }
    }

    @Test fun completionPublishesAcknowledgementAndNavigationBeforeReentrantReplacement() = runTest {
        val navigation = NavigationCoordinator()
        val link = ChannelLinkRequest("#test", Bytes.fromHex("00112233445566778899AABBCCDDEEFF"), "testregion", true)
        navigation.stageChannelLink(link)
        val old = assertNotNull(navigation.state.value.pendingChannelConfirmation)
        val result = ChannelDTO(radioId = RadioId(java.util.UUID(0, 2)), index = 1u, name = "#test", secret = link.secret)
        var replaced = false
        val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            navigation.state.collect { state ->
                if (state.pendingChannelConfirmation == null) {
                    assertEquals(result, state.pendingChannel, "Acknowledgement and navigation must be one publication")
                    if (!replaced) {
                        replaced = true
                        navigation.stageChannelLink(link)
                    }
                }
            }
        }
        assertTrue(navigation.completeChannelConfirmation(old.deliveryId, result))
        assertTrue(replaced)
        val replacement = assertNotNull(navigation.state.value.pendingChannelConfirmation)
        assertNotEquals(old.deliveryId, replacement.deliveryId)
        assertEquals(link, replacement.link)
        assertFalse(navigation.completeChannelConfirmation(old.deliveryId))
        assertEquals(replacement, navigation.state.value.pendingChannelConfirmation)
        collector.cancel()
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun realAdaptiveShellPresentsOneDialogAndCancellationPreservesTheOtherSlotAcrossResize() = runTest {
        val h = ContainerHarness(this, store)
        val width = mutableStateOf(390.dp)
        val navigation = h.container.navigation
        val link = ChannelLinkRequest("#test", Bytes.fromHex("00112233445566778899AABBCCDDEEFF"), "testregion", true)
        try {
            navigation.navigateToChannel(ChannelDTO(radioId = RadioId(java.util.UUID(0, 3)), index = 1u, name = "Existing conversation"))
            compose.runOnUiThread {
                host.get().setContent { Box(Modifier.width(width.value)) { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(navigation, chats = h.container.chatsFeature)
                } } }
            }
            for (hashtagFirst in listOf(false, true)) {
                if (hashtagFirst) navigation.stageHashtag(HashtagJoinRequest("#unshown"))
                navigation.stageChannelLink(link)
                if (!hashtagFirst) navigation.stageHashtag(HashtagJoinRequest("#unshown"))
                val unshown = assertNotNull(navigation.state.value.pendingHashtagConfirmation)
                for (value in listOf(390, 1080, 390, 1080)) {
                    compose.runOnIdle { width.value = value.dp }
                    compose.onNodeWithTag(if (value < 780) "single-pane" else "list-detail").assertIsDisplayed()
                    compose.onAllNodesWithText(host.get().getString(L.commonCancel)).assertCountEquals(1)
                    compose.onNodeWithText("#test").assertIsDisplayed()
                    compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_regionscope, "testregion"))
                        .assertIsDisplayed()
                    compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_hashtagsecretmismatch))
                        .assertIsDisplayed()
                    compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
                }
                compose.onNodeWithText(host.get().getString(L.commonCancel)).performClick()
                assertNull(navigation.state.value.pendingChannelLink)
                assertEquals(unshown, navigation.state.value.pendingHashtagConfirmation)
                compose.onNodeWithText("#unshown").assertIsDisplayed()
                compose.onAllNodesWithText(host.get().getString(L.commonCancel)).assertCountEquals(1)
                compose.onNodeWithText(host.get().getString(L.commonCancel)).performClick()
                assertNull(navigation.state.value.pendingHashtag)
                assertTrue(h.links.radios.isEmpty(), "Disconnected consent flow must never construct/write a radio")
            }
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun independentArrivalDuringHeldRealJoinKeepsSubmittingStateAndExactlyOneWrite() = runTest {
        heldRealJoin(replacement = "hashtag")
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun identicalReplacementBeforeRealJoinReturnsSurvivesWithoutObsoleteNavigation() = runTest {
        heldRealJoin(replacement = "identical")
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun distinctReplacementBeforeRealJoinReturnsSurvivesWithoutObsoleteNavigation() = runTest {
        heldRealJoin(replacement = "different")
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun channelPreemptionOfHeldHashtagJoinRetainsItsSubmittingStateAndIndependentRequest() = runTest {
        heldRealJoin(replacement = "channel", initialHashtag = true)
    }

    private suspend fun TestScope.heldRealJoin(replacement: String, initialHashtag: Boolean = false) {
        val saved = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scopeApplied = CompletableDeferred<Unit>()
        var hold = false
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveChannel(radioId: RadioId, info: ChannelInfo): java.util.UUID {
                val id = store.saveChannel(radioId, info)
                if (hold && info.name == "#test") {
                    saved.complete(Unit)
                    release.await()
                }
                return id
            }

            override suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope) {
                store.setChannelFloodScope(key, floodScope)
                if (floodScope == ChannelFloodScope.Region("testregion")) scopeApplied.complete(Unit)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x20) listOf(Bytes.of(0)) else null }
        }
        val width = mutableStateOf(390.dp)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("initial channel sync completes") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val navigation = h.container.navigation
            val radioId = assertNotNull(h.appState.currentRadioId)
            val firmware = h.links.radios.single()
            val before = firmware.sent.count { it[0].toInt() == 0x20 }
            val secret = if (initialHashtag) com.meshcoreone.android.core.services.contacts.ChannelService.hashSecret("#test")
                else Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
            val link = ChannelLinkRequest("#test", secret, "testregion", true)
            val existing = ChannelDTO(radioId = radioId, index = 0u, name = "Existing conversation")
            store.saveChannel(existing)
            navigation.navigateToChannel(existing)
            val priorStack = navigation.state.value.stacks
            if (initialHashtag) navigation.stageHashtag(HashtagJoinRequest("#test")) else navigation.stageChannelLink(link)
            val old = assertNotNull(if (initialHashtag) navigation.state.value.pendingHashtagConfirmation
                else navigation.state.value.pendingChannelConfirmation)
            compose.runOnUiThread {
                host.get().setContent { Box(Modifier.width(width.value)) { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(navigation, chats = h.container.chatsFeature)
                } } }
            }
            compose.waitUntil(10_000) {
                h.settle()
                !compose.onNodeWithText(host.get().getString(L.commonOk))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            assertEquals(ChatSelection.Channel(existing), navigation.state.value.chatsSelectedRoute)
            assertEquals(priorStack, navigation.state.value.stacks)
            assertEquals(before, firmware.sent.count { it[0].toInt() == 0x20 }, "No write before consent")
            hold = true
            compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(1)
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            compose.waitUntil(10_000) { h.settle(); saved.isCompleted }
            assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 })
            if (replacement == "hashtag") navigation.stageHashtag(HashtagJoinRequest("#unshown"))
            else navigation.stageChannelLink(if (replacement == "identical") link else link.copy(
                name = "#replacement", regionScope = "otherregion",
            ))
            var pending = navigation.state.value
            if (replacement != "hashtag") assertNotEquals(old.deliveryId,
                assertNotNull(pending.pendingChannelConfirmation).deliveryId)
            for (value in listOf(1080, 390, 1080)) {
                compose.runOnIdle { width.value = value.dp }
                compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(1)
                compose.waitUntil(10_000) { h.settle(); navigation.state.value.pendingChannel == null }
                assertEquals(ChatSelection.Channel(existing), navigation.state.value.chatsSelectedRoute)
                assertEquals(priorStack, navigation.state.value.stacks)
                if (replacement == "hashtag") {
                    compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
                    compose.onNodeWithText("#test").assertIsDisplayed()
                }
                assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 })
            }
            if (initialHashtag) {
                compose.onNodeWithText(host.get().getString(L.commonCancel)).performClick()
                compose.onNodeWithText("#test").assertIsDisplayed()
                compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
                assertEquals(old, navigation.state.value.pendingHashtagConfirmation)
                navigation.stageChannelLink(link.copy(name = "#replacement", regionScope = "otherregion"))
                pending = navigation.state.value
            }
            release.complete(Unit)
            compose.waitUntil(10_000) {
                h.settle()
                if (initialHashtag) navigation.state.value.pendingHashtag == null
                else if (replacement == "hashtag") navigation.state.value.pendingChannelLink == null
                else scopeApplied.isCompleted
            }
            compose.waitForIdle()
            val committed = assertNotNull(store.fetchChannel(radioId, 1u))
            assertEquals(secret, committed.secret)
            assertEquals(if (initialHashtag) ChannelFloodScope.Inherit else ChannelFloodScope.Region("testregion"), committed.floodScope)
            assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 }, "One consent means one actual write")
            assertEquals(PacketBuilder.setChannel(committed.index, "#test", secret), firmware.sent.last { it[0].toInt() == 0x20 })
            val navigates = initialHashtag || replacement == "hashtag"
            val expected = ChatSelection.Channel(if (navigates) committed else existing)
            compose.waitUntil(10_000) {
                h.settle()
                navigation.state.value.let { it.pendingChannel == null && it.chatsSelectedRoute == expected }
            }
            compose.waitForIdle()
            val completed = navigation.state.value
            assertEquals(AppTab.CHATS, completed.selectedTab)
            assertEquals(expected, completed.chatsSelectedRoute)
            assertEquals(NavigationDestination.Chat(expected), completed.activeStack.last().destination)
            assertFalse(completed.tabBarVisible)
            assertNull(completed.pendingChannel, "The mounted root consumes navigation, not the lasting selection")
            if (navigates) {
                assertEquals(priorStack.getValue(AppTab.CHATS).toList(), completed.activeStack.dropLast(1))
                assertEquals(priorStack.filterKeys { it != AppTab.CHATS }, completed.stacks.filterKeys { it != AppTab.CHATS })
            } else {
                assertEquals(priorStack, completed.stacks)
            }
            val entry = hasTestTag("entry:${completed.activeStack.last().id}")
            compose.onNode(entry).assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(entry), useUnmergedTree = true).assertIsDisplayed()
            if (initialHashtag) {
                assertEquals(pending.pendingChannelConfirmation, navigation.state.value.pendingChannelConfirmation)
                compose.onNodeWithText("#replacement").assertIsDisplayed()
            } else if (replacement == "hashtag") {
                assertEquals(pending.pendingHashtagConfirmation, navigation.state.value.pendingHashtagConfirmation)
                compose.onNodeWithText("#unshown").assertIsDisplayed()
            } else {
                assertEquals(pending.pendingChannelConfirmation, navigation.state.value.pendingChannelConfirmation)
                compose.onNodeWithText(if (replacement == "identical") "#test" else "#replacement").assertIsDisplayed()
                assertFalse(navigation.completeChannelConfirmation(old.deliveryId, committed))
            }
        } finally {
            release.complete(Unit)
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    private suspend fun TestScope.confirmPrivateHashtag(fromChat: Boolean, name: String, region: String?) {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> if (frame[0].toInt() == 0x20) listOf(Bytes.of(0)) else null }
        }
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("initial channel sync finishes before a confirmed write") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val feature = h.container.chatsFeature
            val navigation = h.container.navigation
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            val chat = ChatLinkRouter(feature.list.links, backgroundScope)
            val radioId = assertNotNull(h.appState.currentRadioId)
            val firmware = h.links.radios.single()
            val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) { AppPendingChannel(feature, navigation) } }
            }
            run {
                val before = firmware.sent.count { it[0].toInt() == 0x20 }
                val uri = "meshcore://channel/add?name=%23${name.drop(1)}&secret=${secret.hexString}" +
                    (region?.let { "&region_scope=$it" } ?: "")
                if (fromChat) assertTrue(chat.route(uri)) else external.routeExternal(uri)
                testScheduler.runCurrent()
                compose.waitUntil(10_000) {
                    h.settle()
                    compose.onAllNodesWithText(name).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_hashtagsecretmismatch))
                    .assertIsDisplayed()
                region?.let {
                    compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinfrommessage_regionscope, it))
                        .assertIsDisplayed()
                }
                assertEquals(before, firmware.sent.count { it[0].toInt() == 0x20 }, "No write before consent")
                assertTrue(store.fetchChannels(radioId).none { it.name == name })
                compose.waitUntil(10_000) {
                    h.settle()
                    !compose.onNodeWithText(host.get().getString(L.commonOk))
                        .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
                }
                compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
                compose.waitUntil(10_000) {
                    h.settle()
                    navigation.state.value.pendingChannelLink == null
                }
                val saved = store.fetchChannels(radioId).single { it.name == name }
                assertEquals(secret, saved.secret)
                assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 })
                assertEquals(PacketBuilder.setChannel(saved.index, name, secret), firmware.sent.last { it[0].toInt() == 0x20 })
                assertEquals(region?.let(ChannelFloodScope::Region) ?: ChannelFloodScope.Inherit, saved.floodScope)
                assertEquals(saved.id, navigation.state.value.pendingChannel?.id)
            }
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }
}
