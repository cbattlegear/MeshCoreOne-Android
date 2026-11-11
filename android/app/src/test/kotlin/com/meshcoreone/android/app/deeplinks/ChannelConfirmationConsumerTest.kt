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
import com.meshcoreone.android.app.navigation.AppChannelsContent
import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.ChatSelection
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.app.navigation.NativeNavigationShell
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationDestination
import com.meshcoreone.android.app.navigation.channelFailureText
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityWriteScope
import com.meshcoreone.android.core.contracts.domain.ChannelHistoryPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelPersisting
import com.meshcoreone.android.core.contracts.domain.ChannelOccupantSnapshot
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.contacts.ChannelMutationError
import com.meshcoreone.android.core.services.contacts.ChannelDispatchFailure
import com.meshcoreone.android.core.services.messaging.QueuedChannelRetainedException
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

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

    private fun emptyChannelReply(index: Int): Bytes = Bytes.of(0x12, index) + Bytes(ByteArray(48))

    @Test fun allocationRecoveryUsesTypedResourcesWithoutLeakingDiagnosticCopy() {
        val unresolved = ChannelMutationError.SyncIncomplete(SnapshotList.of<UByte>(1u))
        val uncertain = ChannelMutationError.ReconciliationRequired(IllegalStateException("private radio diagnostic"))
        assertEquals(UiText.Resource(com.meshcoreone.android.R.string.platform_channel_sync_incomplete),
            channelFailureText(unresolved))
        assertEquals(UiText.Resource(com.meshcoreone.android.R.string.platform_channel_reconciliation_required),
            channelFailureText(uncertain))
        val quarantined = ChannelDispatchFailure.UnsettledDispatch(IllegalStateException("private radio diagnostic"))
        assertEquals(UiText.Resource(com.meshcoreone.android.R.string.platform_channel_reconciliation_required),
            channelFailureText(quarantined))
        assertFalse(channelFailureText(quarantined).resolve(host.get().resources).contains("private radio diagnostic"))
        assertContains(channelFailureText(quarantined).resolve(host.get().resources), "reconnecting alone cannot clear")
        assertFalse(channelFailureText(uncertain).resolve(host.get().resources).contains("private radio diagnostic"))
        assertEquals(UiText.Resource(com.meshcoreone.android.core.l10n.generated.AppChatsStrings.chatsJoinFromMessageErrorNoSlots),
            channelFailureText(ChannelMutationError.NoAvailableSlots()))
    }

    @Test fun legacyUncertainSendThenMountedJoinShowsResourceGuidanceWithoutAnotherRadioWrite() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> when (frame[0].toInt()) {
                3 -> listOf(Bytes.of(1, 2))
                0x1f -> listOf(emptyChannelReply(frame[1].toInt()))
                0x20 -> listOf(Bytes.of(0))
                else -> null
            } }
        }
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val node = assertNotNull(h.sessions.current)
            node.channelService.setChannelWithSecret(
                node.token.radioId, 0u, "Before", Bytes.fromHex("00112233445566778899AABBCCDDEEFF"),
            )
            val retained = assertFailsWith<QueuedChannelRetainedException> {
                node.messageService.sendChannelMessage("uncertain original", 0u, node.token.radioId)
            }
            val rejection = assertNotNull(generateSequence<Throwable>(retained) { it.cause }
                .filterIsInstance<MeshCoreException.DeviceError>()
                .firstOrNull())
            assertEquals(2.toUByte(), rejection.code)
            val firmware = h.links.radios.single()
            val writes = firmware.sent.count { it[0].toInt() == 0x20 }
            h.container.navigation.stageChannelLink(ChannelLinkRequest(
                "#test", Bytes.fromHex("00112233445566778899AABBCCDDEEFF"),
            ))
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                    AppPendingChannel(h.container.chatsFeature, h.container.navigation)
                } }
            }
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                !compose.onNodeWithText(host.get().getString(L.commonOk))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            val recovery = host.get().getString(com.meshcoreone.android.R.string.platform_channel_reconciliation_required)
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                compose.onAllNodesWithText(recovery).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(recovery).assertIsDisplayed()
            compose.onAllNodesWithText("Channel dispatch is uncertain; this radio remains quarantined.").assertCountEquals(0)
            assertEquals(1, firmware.sent.count { it[0].toInt() == 3 })
            assertEquals(writes, firmware.sent.count { it[0].toInt() == 0x20 })
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    private fun interceptChannelPersistence(decorate: (ChannelPersisting) -> ChannelPersisting): PersistenceStoreProtocol {
        val channels = decorate(store)
        return object : PersistenceStoreProtocol by store, ChannelHistoryPersisting by store {
            override suspend fun <T> withChannelOperation(
                block: suspend (ChannelPersisting, ChannelIdentityWriteScope) -> T,
            ): T = store.withChannelOperation { scoped, scope -> block(decorate(scoped), scope) }

            override suspend fun saveChannel(radioId: RadioId, info: ChannelInfo): java.util.UUID =
                channels.saveChannel(radioId, info)

            override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? =
                channels.fetchChannel(radioId, index)

            override suspend fun fetchChannels(radioId: RadioId): SnapshotList<ChannelDTO> =
                channels.fetchChannels(radioId)

            override suspend fun batchSaveChannels(
                radioId: RadioId, configured: SnapshotList<ChannelInfo>, unconfiguredIndices: SnapshotList<UByte>, pruneBeyond: UByte?,
            ): SnapshotList<ChannelDTO> = channels.batchSaveChannels(radioId, configured, unconfiguredIndices, pruneBeyond)

            override suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope) =
                channels.setChannelFloodScope(key, floodScope)
        }
    }

    private enum class RegionReplacement { REUSED_ROW, RECREATED_ROW, ABA }

    @Test fun joinedRegionAndMountedNavigationExcludeReusedRowReplacement() = runTest {
        joinedRegionExcludesReplacement(RegionReplacement.REUSED_ROW)
    }

    @Test fun joinedRegionAndMountedNavigationExcludeDeletedAndRecreatedSlot() = runTest {
        joinedRegionExcludesReplacement(RegionReplacement.RECREATED_ROW)
    }

    @Test fun joinedRegionAndMountedNavigationDoNotResampleAnABAIncarnation() = runTest {
        joinedRegionExcludesReplacement(RegionReplacement.ABA)
    }

    private suspend fun TestScope.joinedRegionExcludesReplacement(mode: RegionReplacement) {
        val reached = CompletableDeferred<ChannelOccupantSnapshot>()
        val release = CompletableDeferred<Unit>()
        val writtenOccupants = mutableListOf<java.util.UUID>()
        val persistence = interceptChannelPersistence { scoped -> object : ChannelPersisting by scoped {
            override suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope) {
                if (floodScope == ChannelFloodScope.Region("region-a")) {
                    val saved = assertNotNull(scoped.fetchChannelOccupantSnapshot(key.radioId, 1u))
                    assertEquals(key.id, saved.channel.id)
                    reached.complete(saved)
                    release.await()
                    assertEquals(saved.occupantID, scoped.fetchChannelOccupantID(key.radioId, 1u))
                    writtenOccupants += saved.occupantID
                }
                scoped.setChannelFloodScope(key, floodScope)
            }
        } }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> when (frame[0].toInt()) {
                0x1f -> listOf(emptyChannelReply(frame[1].toInt()))
                0x20 -> listOf(Bytes.of(0))
                else -> null
            } }
        }
        val secretA = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
        val secretB = Bytes(ByteArray(16) { 0x55 })
        val navigation = h.container.navigation
        val selections = java.util.Collections.synchronizedList(mutableListOf<ChatSelection?>())
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            navigation.state.collect { selections += it.chatsSelectedRoute }
        }
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("initial sync establishes the allocation domain") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val radio = assertNotNull(h.appState.currentRadioId)
            val service = assertNotNull(h.container.sessions.current).channelService
            AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation)).routeExternal(
                "meshcore://channel/add?name=Joined%20A&secret=${secretA.hexString}&region_scope=region-a",
            )
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(navigation, chats = h.container.chatsFeature)
                } }
            }
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                !compose.onNodeWithText(host.get().getString(L.commonOk))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            h.eventually("actual ACK and Room save reach local preference under the same operation", virtualBudgetMillis = 0) {
                reached.isCompleted
            }
            val savedA = reached.await()
            assertEquals("Joined A", savedA.channel.name)
            assertEquals(secretA, savedA.channel.secret)
            assertNotNull(navigation.state.value.pendingChannelConfirmation)
            compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
            val replacement = async(UnconfinedTestDispatcher(testScheduler)) {
                if (mode == RegionReplacement.RECREATED_ROW) {
                    store.deleteChannel(EntityKey(radio, savedA.channel.id))
                }
                service.setChannelWithSecret(radio, 1u, "Replacement B", secretB)
                if (mode == RegionReplacement.ABA) {
                    service.setChannelWithSecret(radio, 1u, "Joined A", secretA)
                }
                val final = assertNotNull(store.fetchChannel(radio, 1u))
                store.setChannelFloodScope(EntityKey(radio, final.id), ChannelFloodScope.Region("replacement-region"))
            }
            h.scheduler.runCurrent()
            assertFalse(replacement.isCompleted, "Replacement must wait through A's local preference and return snapshot")
            assertEquals(savedA, store.fetchChannelOccupantSnapshot(radio, 1u))
            release.complete(Unit)
            h.eventually("joined A completes its own confirmation and queued replacement finishes", virtualBudgetMillis = 0) {
                replacement.isCompleted && navigation.state.value.pendingChannelConfirmation == null
            }
            replacement.await()
            val final = assertNotNull(store.fetchChannelOccupantSnapshot(radio, 1u))
            assertNotEquals(savedA.occupantID, final.occupantID, "ABA and recreated slots are new occupant incarnations")
            if (mode == RegionReplacement.RECREATED_ROW) assertNotEquals(savedA.channel.id, final.channel.id)
            else assertEquals(savedA.channel.id, final.channel.id, "Reused row UUID is deliberately not an occupant witness")
            assertEquals(ChannelFloodScope.Region("replacement-region"), final.channel.floodScope)
            assertEquals(if (mode == RegionReplacement.ABA) secretA else secretB, final.channel.secret)
            assertEquals(listOf(savedA.occupantID), writtenOccupants, "A's region never writes against a replacement occupant")
            val joined = savedA.channel.withFloodScope(ChannelFloodScope.Region("region-a"))
            val observed = synchronized(selections) { selections.filterIsInstance<ChatSelection.Channel>().map { it.channel } }
            assertTrue(joined in observed,
                "The original consumer receives the immutable result captured before releasing the operation")
            assertFalse(final.channel in observed, "Navigation must never substitute the replacement DTO")
            assertTrue((navigation.state.value.chatsSelectedRoute as? ChatSelection.Channel)?.channel.let { it == null || it == joined },
                "Existing occupant-retirement handling may clear A, but cannot select B")
            compose.waitForIdle()
            compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(0)
        } finally {
            release.complete(Unit)
            observer.cancel()
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    @Test fun existingHashtagFromMountedOptionsAfterDisconnectNavigatesCachedConversationWithoutRadioWork() = runTest {
        val h = ContainerHarness(this, store)
        val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
        val occupant = Bytes.of(0x12, 1) + Bytes.utf8("#Ops").paddedOrTruncated(32) + secret
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> when (frame[0].toInt()) {
                0x1f -> listOf(if (frame[1].toInt() == 1) occupant else emptyChannelReply(frame[1].toInt()))
                0x20 -> listOf(Bytes.of(0))
                else -> null
            } }
        }
        val showing = mutableStateOf(true)
        val navigation = h.container.navigation
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val radio = assertNotNull(h.appState.currentRadioId)
            h.eventually("actual channel sync saves the offline hashtag destination") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val cached = assertNotNull(store.fetchChannel(radio, 1u))
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                    if (showing.value) AppChannelsContent(h.container.chatsFeature, navigation) { showing.value = false }
                } }
            }
            val option = host.get().getString(R.string.l10n_app_chats_chats_channeloptions_joinhashtag_title)
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                compose.onAllNodesWithText(option).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(option).performClick()
            compose.onNode(hasSetTextAction()).performTextInput("ops")
            h.manager.disconnect(com.meshcoreone.android.core.runtime.RuntimeDisconnectReason.USER_INITIATED)
            h.settle()
            assertNull(h.sessions.current)
            val before = h.links.radios.single().sent.toList()
            val links = h.links.radios.size
            compose.onNodeWithText(host.get().getString(R.string.l10n_app_chats_chats_joinprivate_joinbutton)).performClick()
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                !showing.value
            }
            assertEquals(ChatSelection.Channel(cached), navigation.state.value.chatsSelectedRoute)
            assertEquals(cached, store.fetchChannel(radio, 1u))
            assertEquals(before, h.links.radios.single().sent, "Offline cached navigation performs neither reads nor writes")
            assertEquals(links, h.links.radios.size, "No new connection is created for cached navigation")
            assertNull(h.sessions.current)
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun postSaveFetchFailureDisablesOnlyThatDeliveryAndIdenticalReplacementCanBeConsented() = runTest {
        postSaveRetryControl(inCache = false, cancel = false)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun postSaveFetchCancellationDisablesOnlyThatDeliveryAndIdenticalReplacementCanBeConsented() = runTest {
        postSaveRetryControl(inCache = false, cancel = true)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun postSaveCacheFailureDisablesOnlyThatDeliveryAndIdenticalReplacementCanBeConsented() = runTest {
        postSaveRetryControl(inCache = true, cancel = false)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun postSaveCacheCancellationDisablesOnlyThatDeliveryAndIdenticalReplacementCanBeConsented() = runTest {
        postSaveRetryControl(inCache = true, cancel = true)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun postSaveFetchFailureRetainsItsReceiptAcrossTabReturn() = runTest {
        postSaveRetryControl(inCache = false, cancel = false, presentation = ConfirmationPresentation.TAB_RETURN)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun postSaveCacheCancellationRetainsItsReceiptAcrossActivityRecreation() = runTest {
        postSaveRetryControl(inCache = true, cancel = true, presentation = ConfirmationPresentation.ACTIVITY_RECREATION)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun heldPostSaveFetchFailureSurvivesActualActivityRecreation() = runTest {
        postSaveRetryControl(inCache = false, cancel = false, presentation = ConfirmationPresentation.HELD_ACTIVITY_RECREATION)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun heldPostSaveCacheFailureSurvivesTabReturn() = runTest {
        postSaveRetryControl(inCache = true, cancel = false, presentation = ConfirmationPresentation.HELD_TAB_RETURN)
    }

    private enum class ConfirmationPresentation {
        RESIZE, TAB_RETURN, ACTIVITY_RECREATION, HELD_TAB_RETURN, HELD_ACTIVITY_RECREATION,
    }

    private suspend fun TestScope.postSaveRetryControl(
        inCache: Boolean, cancel: Boolean, presentation: ConfirmationPresentation = ConfirmationPresentation.RESIZE,
    ) {
        val savedBy = java.util.concurrent.atomic.AtomicReference<Job?>(null)
        val returnedId = java.util.concurrent.atomic.AtomicReference<java.util.UUID?>(null)
        val probe = java.util.concurrent.atomic.AtomicBoolean(false)
        val reached = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val failure = IllegalStateException("held real post-save ${if (inCache) "cache" else "channel"} read")
        suspend fun afterSaved() {
            if (probe.get() && returnedId.get() != null && currentCoroutineContext()[Job] === savedBy.get()) {
                reached.complete(Unit)
                release.await()
                throw failure
            }
        }
        val persistence = interceptChannelPersistence { scoped -> object : ChannelPersisting by scoped {
            override suspend fun saveChannel(radioId: RadioId, info: ChannelInfo): java.util.UUID {
                val id = scoped.saveChannel(radioId, info)
                if (probe.get() && info.name == "#test" && savedBy.compareAndSet(null, currentCoroutineContext()[Job])) {
                    returnedId.set(id)
                }
                return id
            }
            override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? {
                if (!inCache) afterSaved()
                return scoped.fetchChannel(radioId, index)
            }
            override suspend fun fetchChannels(radioId: RadioId): SnapshotList<ChannelDTO> {
                if (inCache) afterSaved()
                return scoped.fetchChannels(radioId)
            }
        } }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> when (frame[0].toInt()) {
                0x1f -> listOf(emptyChannelReply(frame[1].toInt()))
                0x20 -> listOf(Bytes.of(0))
                else -> null
            } }
        }
        val width = mutableStateOf(390.dp)
        val navigation = h.container.navigation
        val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
        val uri = "meshcore://channel/add?name=%23test&secret=00112233445566778899AABBCCDDEEFF&region_scope=testregion"
        val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
        val selections = java.util.Collections.synchronizedList(mutableListOf<ChatSelection?>())
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            navigation.state.collect { selections += it.chatsSelectedRoute }
        }
        fun mount() {
            host.get().setContent { Box(Modifier.width(width.value)) { MeshCoreTheme(motionScale = 0f) {
                NativeNavigationShell(navigation, chats = h.container.chatsFeature)
            } } }
        }
        fun changePresentation() {
            when (presentation) {
                ConfirmationPresentation.TAB_RETURN, ConfirmationPresentation.HELD_TAB_RETURN -> {
                    compose.runOnIdle { navigation.selectTab(AppTab.MAP) }
                    compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(0)
                    compose.runOnIdle { navigation.selectTab(AppTab.CHATS) }
                }
                ConfirmationPresentation.ACTIVITY_RECREATION, ConfirmationPresentation.HELD_ACTIVITY_RECREATION -> {
                    val previous = host.get()
                    host.windowFocusChanged(false)
                    host.recreate().visible()
                    host.get().actionBar?.hide()
                    host.windowFocusChanged(true)
                    assertNotSame(previous, host.get(), "Recreate the actual Activity, not only its content")
                    compose.runOnUiThread { mount() }
                }
                ConfirmationPresentation.RESIZE -> Unit
            }
            h.scheduler.runCurrent()
            compose.waitForIdle()
        }
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("initial channel sync completes") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val radio = assertNotNull(h.appState.currentRadioId)
            val firmware = h.links.radios.single()
            val before = firmware.sent.count { it[0].toInt() == 0x20 }
            val existing = ChannelDTO(radioId = radio, index = 0u, name = "Existing conversation")
            store.saveChannel(existing)
            navigation.navigateToChannel(existing)
            val initialStacks = navigation.state.value.stacks
            external.routeExternal(uri)
            val first = assertNotNull(navigation.state.value.pendingChannelConfirmation)
            compose.runOnUiThread { mount() }
            fun awaitEnabled() = compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                !compose.onNodeWithText(host.get().getString(L.commonOk))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            awaitEnabled()
            probe.set(true)
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            h.eventually("actual ACK and returned Room UUID precede held read", virtualBudgetMillis = 0) { reached.isCompleted }
            val saved = assertNotNull(store.fetchChannel(radio, 1u))
            assertEquals(returnedId.get(), saved.id)
            assertEquals("#test", saved.name)
            assertEquals(secret, saved.secret)
            assertEquals(ChannelFloodScope.Region("testregion"), saved.floodScope,
                "Local preference commits before the later read/cache failure while the saved occupant is still exclusive")
            assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 })
            assertEquals(first, navigation.state.value.pendingChannelConfirmation)
            compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
            val requestJob = assertNotNull(savedBy.get())
            val transitionWhileHeld = presentation == ConfirmationPresentation.HELD_TAB_RETURN ||
                presentation == ConfirmationPresentation.HELD_ACTIVITY_RECREATION
            if (transitionWhileHeld) {
                changePresentation()
                assertTrue(requestJob.isActive, "Presentation disposal must not cancel an already-consented write")
                compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
            }
            if (cancel) requestJob.cancel(CancellationException("cancel after actual returned Room UUID"))
            else release.complete(Unit)
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                requestJob.isCompleted
            }
            assertEquals(cancel, requestJob.isCancelled)
            compose.onNodeWithText(host.get().getString(com.meshcoreone.android.R.string.platform_channel_already_saved))
                .assertIsDisplayed()
            probe.set(false)
            if (!transitionWhileHeld && presentation != ConfirmationPresentation.RESIZE) {
                changePresentation()
                compose.waitUntil(10_000) {
                    h.scheduler.runCurrent()
                    compose.onAllNodesWithText(host.get().getString(com.meshcoreone.android.R.string.platform_channel_already_saved))
                        .fetchSemanticsNodes().isNotEmpty() ||
                        !compose.onNodeWithText(host.get().getString(L.commonOk))
                            .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
                }
                compose.onNodeWithText(host.get().getString(com.meshcoreone.android.R.string.platform_channel_already_saved))
                    .assertIsDisplayed()
                compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
            }
            for (value in listOf(1080, 390, 1080)) {
                compose.runOnIdle { width.value = value.dp }
                h.scheduler.runCurrent()
                compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
                compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(1)
                compose.onNodeWithText(host.get().getString(L.commonOk)).performTouchInput { click() }
                compose.onNodeWithTag(if (value < 780) "single-pane" else "list-detail").assertIsDisplayed()
                assertEquals(first, navigation.state.value.pendingChannelConfirmation)
                assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 }, "Retry control cannot dispatch again")
                assertEquals(listOf<UByte>(0u, 1u), store.fetchChannels(radio).map { it.index })
                assertEquals(saved, store.fetchChannel(radio, 1u))
                assertEquals(ChatSelection.Channel(existing), navigation.state.value.chatsSelectedRoute)
                assertEquals(initialStacks, navigation.state.value.stacks)
            }
            assertFalse(synchronized(selections) {
                selections.any { it is ChatSelection.Channel && it.channel.id == saved.id }
            }, "A saved-but-incomplete result must not fabricate a successful navigation")

            navigation.stageChannelLink(requireNotNull(first.link))
            val second = assertNotNull(navigation.state.value.pendingChannelConfirmation)
            assertNotEquals(first.deliveryId, second.deliveryId)
            awaitEnabled()
            assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 }, "New delivery is not consent")
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            h.eventually("independently consented identical delivery completes", virtualBudgetMillis = 0) {
                navigation.state.value.pendingChannelConfirmation == null
            }
            val joined = assertNotNull(store.fetchChannel(radio, 2u))
            assertNotEquals(saved.id, joined.id)
            assertEquals(secret, joined.secret)
            assertEquals("#test", joined.name)
            assertEquals(ChannelFloodScope.Region("testregion"), joined.floodScope)
            assertEquals(saved, store.fetchChannel(radio, 1u))
            assertEquals(listOf(
                PacketBuilder.setChannel(1u, "#test", secret), PacketBuilder.setChannel(2u, "#test", secret),
            ), firmware.sent.filter { it[0].toInt() == 0x20 }.drop(before))
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                navigation.state.value.let { it.pendingChannel == null && it.chatsSelectedRoute == ChatSelection.Channel(joined) }
            }
            val completed = navigation.state.value
            assertEquals(NavigationDestination.Chat(ChatSelection.Channel(joined)), completed.activeStack.last().destination)
            assertEquals(initialStacks.getValue(AppTab.CHATS).toList(), completed.activeStack.dropLast(1))
            assertEquals(initialStacks.filterKeys { it != AppTab.CHATS }, completed.stacks.filterKeys { it != AppTab.CHATS })
            compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(0)
            val entry = hasTestTag("entry:${completed.activeStack.last().id}")
            compose.onNode(entry).assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(entry), useUnmergedTree = true).assertIsDisplayed()
        } finally {
            observer.cancel()
            probe.set(false)
            release.complete(Unit)
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

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

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun distinctReplacementWithTwoConsentsBeforeFirstAckPreservesBothAllocatedChannels() = runTest {
        preAckTwoConsents(initialHashtag = false)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun identicalReplacementWithTwoConsentsBeforeFirstAckStillAllocatesDistinctSlots() = runTest {
        preAckTwoConsents(initialHashtag = false, identical = true)
    }

    @Test
    @Config(qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
    fun channelOverHashtagWithTwoConsentsBeforeFirstAckPreservesIndependentSuccessfulResults() = runTest {
        preAckTwoConsents(initialHashtag = true)
    }

    @Test
    fun readyWithUnknownRadioSlotRefusesEarlyConsentThenQueuesRetryOverlapWithoutOverwritingOccupant() = runTest {
        readyRetryAllocation(joinFirst = false)
    }

    @Test
    fun readyJoinBeforeRetryPreservesAcceptedChannelAndHistoryWhileNextConsentWaitsForBatchCommit() = runTest {
        readyRetryAllocation(joinFirst = true)
    }

    @Test
    fun sameRadioEsp32ReconnectWithSkippedChannelPhaseReadsFreshAuthorityOnExplicitConsent() = runTest {
        val h = ContainerHarness(this, store, syncClock = object : SyncClock {
            override fun now() = EPOCH.plusMillis(testScheduler.currentTime)
            override fun elapsed() = testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        })
        val occupantSecret = Bytes.fromHex("11111111111111111111111111111111")
        val occupant = Bytes.of(0x12, 1) + Bytes.utf8("Radio occupant").paddedOrTruncated(32) + occupantSecret
        h.links.configureRadio = { radio ->
            radio.modelName = "Heltec V3"
            radio.respond = { frame -> when (frame[0].toInt()) {
                0x1f -> listOf(if (frame[1].toInt() == 1) occupant else emptyChannelReply(frame[1].toInt()))
                0x20 -> listOf(Bytes.of(0))
                else -> null
            } }
        }
        try {
            h.container.foreground.activityStarted()
            val target = h.target()
            h.manager.connect(target)
            h.eventually("first foreground sync records a clean channel stamp") {
                h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                    h.manager.lastCleanChannelSync != null
            }
            h.assertReady()
            val old = assertNotNull(h.sessions.current)
            val radio = assertNotNull(h.appState.currentRadioId)
            assertEquals("Radio occupant", store.fetchChannel(radio, 1u)?.name)
            val cleanSync = assertNotNull(h.manager.lastCleanChannelSync)
            h.manager.teardownSessionForReconnect()
            val rebuilt = backgroundScope.async { h.manager.rebuildSession(target.deviceId) }
            h.eventually("fresh graph becomes ready inside the recent-sync window") {
                rebuilt.isCompleted &&
                    h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            rebuilt.await()
            h.assertReady()
            assertEquals(cleanSync, h.manager.lastCleanChannelSync, "Same-radio reconnect skips the recent channel phase")
            val fresh = assertNotNull(h.sessions.current)
            assertNotSame(old.channelService, fresh.channelService)
            assertTrue(old.isTornDown)
            assertEquals(radio, h.appState.currentRadioId)
            val firmware = h.links.radios.last()
            assertEquals(2, h.links.radios.size)
            assertTrue(firmware.sent.none { it[0].toInt() == 0x1f }, "Exercise the actual recent-sync skip on the new service")
            val navigation = h.container.navigation
            val link = ChannelLinkRequest("#after-reconnect", Bytes.fromHex("00112233445566778899AABBCCDDEEFF"), "new-region", true)
            navigation.stageChannelLink(link)
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(navigation, chats = h.container.chatsFeature)
                } }
            }
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                !compose.onNodeWithText(host.get().getString(L.commonOk))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            assertTrue(firmware.sent.none { it[0].toInt() == 0x1f || it[0].toInt() == 0x20 }, "Neither staging nor composition refreshes/writes radio")
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            h.eventually("consent establishes current-service authority and completes the join", virtualBudgetMillis = 0) {
                navigation.state.value.pendingChannelConfirmation == null
            }
            val joined = assertNotNull(store.fetchChannel(radio, 2u))
            assertEquals(link.name, joined.name)
            assertEquals(link.secret, joined.secret)
            assertEquals(ChannelFloodScope.Region("new-region"), joined.floodScope)
            assertEquals(occupantSecret, store.fetchChannel(radio, 1u)?.secret)
            assertEquals((0 until h.container.chatsFeature.maximumChannels().toInt()).toList(),
                firmware.sent.filter { it[0].toInt() == 0x1f }.map { it[1].toInt() })
            assertEquals(listOf(PacketBuilder.setChannel(2u, link.name, link.secret)),
                firmware.sent.filter { it[0].toInt() == 0x20 })
            assertSame(fresh, h.sessions.current)
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                navigation.state.value.let { it.pendingChannel == null && it.chatsSelectedRoute == ChatSelection.Channel(joined) }
            }
            compose.waitForIdle()
            compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(0)
        } finally {
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    private suspend fun TestScope.readyRetryAllocation(joinFirst: Boolean) {
        val retryRead = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var holdRetry = false
        var failObservation = !joinFirst
        var retryIndex: UByte = 1u
        val persistence = interceptChannelPersistence { scoped -> object : ChannelPersisting by scoped {
            override suspend fun batchSaveChannels(
                radioId: RadioId, configured: SnapshotList<ChannelInfo>, unconfiguredIndices: SnapshotList<UByte>, pruneBeyond: UByte?,
            ): SnapshotList<ChannelDTO> {
                if (failObservation && configured.any { it.index == 1.toUByte() }) {
                    throw IllegalStateException("Channel observation could not be persisted")
                }
                if (holdRetry && configured.any { it.index == retryIndex }) {
                    retryRead.complete(Unit)
                    release.await()
                }
                return scoped.batchSaveChannels(radioId, configured, unconfiguredIndices, pruneBeyond)
            }
        } }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val occupantSecret = Bytes.fromHex("11111111111111111111111111111111")
        val remote = mutableMapOf(1 to (Bytes.of(0x12, 1) + Bytes.utf8("Radio occupant").paddedOrTruncated(32) + occupantSecret))
        h.links.configureRadio = { radio ->
            radio.respond = { frame ->
                when (frame[0].toInt()) {
                    0x1f -> listOf(remote[frame[1].toInt()] ?: emptyChannelReply(frame[1].toInt()))
                    0x20 -> {
                        remote[frame[1].toInt()] = Bytes.of(0x12) + frame.slice(1, frame.size)
                        listOf(Bytes.of(0))
                    }
                    else -> null
                }
            }
        }
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("initial channel phase completes even when one slot is unresolved") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val radio = assertNotNull(h.appState.currentRadioId)
            val firmware = h.links.radios.single()
            val before = firmware.sent.count { it[0].toInt() == 0x20 }
            val navigation = h.container.navigation
            val existing = ChannelDTO(radioId = radio, index = 0u, name = "Existing conversation", secret = Bytes(ByteArray(16)))
            store.saveChannel(existing)
            remote[0] = Bytes.of(0x12, 0) + Bytes.utf8(existing.name).paddedOrTruncated(32) + existing.secret
            navigation.navigateToChannel(existing)
            compose.runOnUiThread {
                host.get().setContent { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(navigation, chats = h.container.chatsFeature)
                } }
            }
            val first = ChannelLinkRequest("#accepted", Bytes.fromHex("00112233445566778899AABBCCDDEEFF"), "accepted-region", true)
            navigation.stageChannelLink(first)
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                !compose.onNodeWithText(host.get().getString(L.commonOk))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            val message: com.meshcoreone.android.core.model.MessageDTO?
            val accepted: ChannelDTO?
            val expected: ChannelLinkRequest
            if (joinFirst) {
                h.eventually("first accepted join finishes before retry", virtualBudgetMillis = 0) {
                    navigation.state.value.pendingChannelConfirmation == null
                }
                accepted = assertNotNull(store.fetchChannel(radio, 2u))
                assertEquals(first.secret, accepted.secret)
                message = com.meshcoreone.android.core.model.MessageDTO(
                    radioId = radio, channelIndex = accepted.index, text = "Preserve accepted history", timestamp = 1_704_067_200u,
                    createdAt = EPOCH, sortDate = EPOCH,
                )
                store.saveMessage(message)
                retryIndex = accepted.index
                expected = first.copy(name = "#after-retry", regionScope = "next-region")
            } else {
                compose.waitUntil(10_000) {
                    h.scheduler.runCurrent()
                    compose.onAllNodesWithText(
                        host.get().getString(com.meshcoreone.android.R.string.platform_channel_sync_incomplete),
                    ).fetchSemanticsNodes().isNotEmpty()
                }
                h.assertReady()
                assertNull(store.fetchChannel(radio, 1u), "READY is not proof that the unknown radio slot is free")
                assertEquals(before, firmware.sent.count { it[0].toInt() == 0x20 }, "Failed early consent must not dispatch")
                accepted = null
                message = null
                expected = first
            }
            val service = assertNotNull(h.sessions.current).channelService
            if (!joinFirst) {
                failObservation = false
                assertTrue(service.syncChannels(radio, h.container.chatsFeature.maximumChannels(), false).errors.isEmpty())
                assertEquals(occupantSecret, store.fetchChannel(radio, 1u)?.secret,
                    "Explicit recovery establishes real authority; a generic unindexed error is not an empty-slot reply")
            }
            holdRetry = true
            val retry = backgroundScope.async { service.retryFailedChannels(radio, SnapshotList.of(retryIndex)) }
            h.eventually("actual configured retry reply is held before Room batch commit", virtualBudgetMillis = 1_000) {
                retryRead.isCompleted
            }
            if (joinFirst) navigation.stageChannelLink(expected)
            val pending = assertNotNull(navigation.state.value.pendingChannelConfirmation)
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                !compose.onNodeWithText(host.get().getString(L.commonOk))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
            h.scheduler.runCurrent()
            h.assertReady()
            assertEquals(before + if (joinFirst) 1 else 0, firmware.sent.count { it[0].toInt() == 0x20 })
            assertEquals(pending, navigation.state.value.pendingChannelConfirmation)
            if (accepted != null) assertEquals(accepted.secret, store.fetchChannel(radio, accepted.index)?.secret)
            else assertEquals(occupantSecret, store.fetchChannel(radio, 1u)?.secret)

            release.complete(Unit)
            h.eventually("retry batch commits and separately confirmed join finishes", virtualBudgetMillis = 0) {
                retry.isCompleted && navigation.state.value.pendingChannelConfirmation == null
            }
            assertEquals(1, retry.await().channelsSynced)
            val joined = assertNotNull(store.fetchChannel(radio, if (joinFirst) 3u else 2u))
            assertEquals(expected.name, joined.name)
            assertEquals(expected.secret, joined.secret)
            assertEquals(ChannelFloodScope.Region(requireNotNull(expected.regionScope)), joined.floodScope)
            assertEquals("Radio occupant", store.fetchChannel(radio, 1u)?.name)
            assertEquals(occupantSecret, store.fetchChannel(radio, 1u)?.secret)
            if (accepted != null && message != null) {
                assertEquals(accepted.id, store.fetchChannel(radio, accepted.index)?.id)
                assertEquals(accepted.secret, store.fetchChannel(radio, accepted.index)?.secret)
                assertEquals(message.text, store.fetchMessage(EntityKey(radio, message.id))?.text)
            }
            assertEquals(before + if (joinFirst) 2 else 1, firmware.sent.count { it[0].toInt() == 0x20 })
            assertEquals(PacketBuilder.setChannel(joined.index, expected.name, expected.secret),
                firmware.sent.last { it[0].toInt() == 0x20 })
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                navigation.state.value.let { it.pendingChannel == null && it.chatsSelectedRoute == ChatSelection.Channel(joined) }
            }
            compose.waitForIdle()
            val completed = navigation.state.value
            assertEquals(AppTab.CHATS, completed.selectedTab)
            assertEquals(NavigationDestination.Chat(ChatSelection.Channel(joined)), completed.activeStack.last().destination)
            assertFalse(completed.tabBarVisible)
            compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(0)
        } finally {
            release.complete(Unit)
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    private suspend fun TestScope.preAckTwoConsents(initialHashtag: Boolean, identical: Boolean = false) {
        val scopeApplied = CompletableDeferred<Unit>()
        val persistence = interceptChannelPersistence { scoped -> object : ChannelPersisting by scoped {
            override suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope) {
                scoped.setChannelFloodScope(key, floodScope)
                if (floodScope == ChannelFloodScope.Region("region-a")) scopeApplied.complete(Unit)
            }
        } }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        var holdAcknowledgements = false
        h.links.configureRadio = { radio ->
            radio.respond = { frame ->
                when (frame[0].toInt()) {
                    0x1f -> listOf(emptyChannelReply(frame[1].toInt()))
                    0x20 -> if (holdAcknowledgements) emptyList() else listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
        val width = mutableStateOf(390.dp)
        val navigation = h.container.navigation
        val selections = java.util.Collections.synchronizedList(mutableListOf<ChatSelection?>())
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            navigation.state.collect { selections += it.chatsSelectedRoute }
        }
        try {
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            h.eventually("initial channel sync completes") {
                h.appState.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced
            }
            val radio = assertNotNull(h.appState.currentRadioId)
            val firmware = h.links.radios.single()
            val before = firmware.sent.count { it[0].toInt() == 0x20 }
            val existing = ChannelDTO(radioId = radio, index = 0u, name = "Existing conversation")
            store.saveChannel(existing)
            navigation.navigateToChannel(existing)
            val initialStacks = navigation.state.value.stacks
            val firstSecret = Bytes.fromHex(if (initialHashtag) "9cd8fcf22a47333b591d96a2b848b73f" else
                "00112233445566778899AABBCCDDEEFF")
            val firstLink = ChannelLinkRequest("#test", firstSecret, "region-a", true)
            if (initialHashtag) navigation.stageHashtag(HashtagJoinRequest("#test")) else navigation.stageChannelLink(firstLink)
            val first = assertNotNull(if (initialHashtag) navigation.state.value.pendingHashtagConfirmation
                else navigation.state.value.pendingChannelConfirmation)
            compose.runOnUiThread {
                host.get().setContent { Box(Modifier.width(width.value)) { MeshCoreTheme(motionScale = 0f) {
                    NativeNavigationShell(navigation, chats = h.container.chatsFeature)
                } } }
            }
            fun awaitEnabled() = compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                !compose.onNodeWithText(host.get().getString(L.commonOk))
                    .fetchSemanticsNode().config.contains(SemanticsProperties.Disabled)
            }
            awaitEnabled()
            assertEquals(before, firmware.sent.count { it[0].toInt() == 0x20 }, "No write before A consent")
            holdAcknowledgements = true
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            h.eventually("A actual SET_CHANNEL is sent without acknowledgement", virtualBudgetMillis = 0) {
                firmware.sent.count { it[0].toInt() == 0x20 } == before + 1
            }
            assertNull(store.fetchChannel(radio, 1u), "The hold is before ACK and Room save, not after commit")
            val secondLink = if (identical) firstLink else ChannelLinkRequest(
                "#second", Bytes.fromHex("FFEEDDCCBBAA99887766554433221100"), "region-b", true,
            )
            navigation.stageChannelLink(secondLink)
            val second = assertNotNull(navigation.state.value.pendingChannelConfirmation)
            assertNotEquals(first.deliveryId, second.deliveryId)
            awaitEnabled()
            compose.onNodeWithText(secondLink.name).assertIsDisplayed()
            assertNull(store.fetchChannel(radio, 1u), "B loads while A has not saved")
            assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 }, "Staging B is not consent")
            compose.onNodeWithText(host.get().getString(L.commonOk)).performClick()
            for (value in listOf(1080, 390, 1080)) {
                compose.runOnIdle { width.value = value.dp }
                h.scheduler.runCurrent()
                compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
                compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(1)
                compose.onNodeWithTag(if (value < 780) "single-pane" else "list-detail").assertIsDisplayed()
                assertEquals(second, navigation.state.value.pendingChannelConfirmation)
                assertEquals(before + 1, firmware.sent.count { it[0].toInt() == 0x20 }, "B must await fresh allocation")
                assertNull(store.fetchChannel(radio, 1u))
            }
            assertEquals(ChatSelection.Channel(existing), navigation.state.value.chatsSelectedRoute)
            assertEquals(initialStacks, navigation.state.value.stacks)

            firmware.push(Bytes.of(0))
            h.eventually("B dispatches only after A acknowledgement and save", virtualBudgetMillis = 0) {
                firmware.sent.count { it[0].toInt() == 0x20 } == before + 2
            }
            h.eventually("A completes while B acknowledgement remains held", virtualBudgetMillis = 0) {
                if (initialHashtag) navigation.state.value.pendingHashtagConfirmation == null else scopeApplied.isCompleted
            }
            compose.waitForIdle()
            val savedFirst = assertNotNull(store.fetchChannel(radio, 1u))
            assertEquals(firstSecret, savedFirst.secret)
            assertEquals("#test", savedFirst.name)
            assertEquals(if (initialHashtag) ChannelFloodScope.Inherit else ChannelFloodScope.Region("region-a"), savedFirst.floodScope)
            assertNull(store.fetchChannel(radio, 2u), "B has not been acknowledged")
            assertEquals(second, navigation.state.value.pendingChannelConfirmation)
            val expectedBeforeB = ChatSelection.Channel(if (initialHashtag) savedFirst else existing)
            assertEquals(expectedBeforeB, navigation.state.value.chatsSelectedRoute)
            if (!initialHashtag) {
                assertEquals(initialStacks, navigation.state.value.stacks)
                assertFalse(synchronized(selections) { selections.any { it is ChatSelection.Channel && it.channel.index == 1.toUByte() } },
                    "Superseded A must never navigate, even though its write is preserved")
            }
            val writes = firmware.sent.filter { it[0].toInt() == 0x20 }.drop(before)
            assertEquals(listOf(
                PacketBuilder.setChannel(1u, "#test", firstSecret),
                PacketBuilder.setChannel(2u, secondLink.name, secondLink.secret),
            ), writes)
            compose.onNodeWithText(host.get().getString(L.commonOk)).assertIsNotEnabled()
            firmware.push(Bytes.of(0))
            h.eventually("Both accepted channels persist and B consumes only its confirmation", virtualBudgetMillis = 0) {
                navigation.state.value.pendingChannelConfirmation == null
            }
            val savedSecond = assertNotNull(store.fetchChannel(radio, 2u))
            assertNotEquals(savedFirst.id, savedSecond.id)
            assertEquals(savedFirst, store.fetchChannel(radio, 1u), "A's accepted row and scope must not be overwritten")
            assertEquals(secondLink.name, savedSecond.name)
            assertEquals(secondLink.secret, savedSecond.secret)
            assertEquals(ChannelFloodScope.Region(requireNotNull(secondLink.regionScope)), savedSecond.floodScope)
            assertEquals(listOf<UByte>(0u, 1u, 2u), store.fetchChannels(radio).map { it.index })
            assertEquals(before + 2, firmware.sent.count { it[0].toInt() == 0x20 }, "Exactly one radio write per actual consent")
            compose.waitUntil(10_000) {
                h.scheduler.runCurrent()
                navigation.state.value.let { it.pendingChannel == null && it.chatsSelectedRoute == ChatSelection.Channel(savedSecond) }
            }
            compose.waitForIdle()
            val completed = navigation.state.value
            assertEquals(AppTab.CHATS, completed.selectedTab)
            assertEquals(ChatSelection.Channel(savedSecond), completed.chatsSelectedRoute)
            assertEquals(NavigationDestination.Chat(ChatSelection.Channel(savedSecond)), completed.activeStack.last().destination)
            assertEquals(initialStacks.getValue(AppTab.CHATS).toList(), completed.activeStack.dropLast(if (initialHashtag) 2 else 1))
            assertEquals(initialStacks.filterKeys { it != AppTab.CHATS }, completed.stacks.filterKeys { it != AppTab.CHATS })
            assertFalse(completed.tabBarVisible)
            assertNull(completed.pendingHashtagConfirmation)
            compose.onAllNodesWithText(host.get().getString(L.commonOk)).assertCountEquals(0)
            val entry = hasTestTag("entry:${completed.activeStack.last().id}")
            compose.onNode(entry).assertIsDisplayed()
            compose.onNode(hasSetTextAction() and hasAnyAncestor(entry), useUnmergedTree = true).assertIsDisplayed()
        } finally {
            observer.cancel()
            compose.runOnUiThread { host.get().setContent {} }
            h.container.close()
        }
    }

    private suspend fun TestScope.heldRealJoin(replacement: String, initialHashtag: Boolean = false) {
        val saved = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scopeApplied = CompletableDeferred<Unit>()
        var hold = false
        val persistence = interceptChannelPersistence { scoped -> object : ChannelPersisting by scoped {
            override suspend fun saveChannel(radioId: RadioId, info: ChannelInfo): java.util.UUID {
                val id = scoped.saveChannel(radioId, info)
                if (hold && info.name == "#test") {
                    saved.complete(Unit)
                    release.await()
                }
                return id
            }

            override suspend fun setChannelFloodScope(key: EntityKey, floodScope: ChannelFloodScope) {
                scoped.setChannelFloodScope(key, floodScope)
                if (floodScope == ChannelFloodScope.Region("testregion")) scopeApplied.complete(Unit)
            }
        } }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        h.links.configureRadio = { radio ->
            radio.respond = { frame -> when (frame[0].toInt()) {
                0x1f -> listOf(emptyChannelReply(frame[1].toInt()))
                0x20 -> listOf(Bytes.of(0))
                else -> null
            } }
        }
        val width = mutableStateOf(390.dp)
        try {
            h.container.foreground.activityStarted()
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
            radio.respond = { frame -> when (frame[0].toInt()) {
                0x1f -> listOf(emptyChannelReply(frame[1].toInt()))
                0x20 -> listOf(Bytes.of(0))
                else -> null
            } }
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
