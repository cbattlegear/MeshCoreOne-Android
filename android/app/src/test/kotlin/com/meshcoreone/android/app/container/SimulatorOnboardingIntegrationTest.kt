// AndroidOnly: WP-217 Mounted onboarding and simulator seed integration over the production Room and DataStore adapters.
package com.meshcoreone.android.app.container

import android.content.Context
import android.graphics.Bitmap
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.onboarding.AppOnboarding
import com.meshcoreone.android.app.container.onboarding.AppOnboardingFactory
import com.meshcoreone.android.app.container.onboarding.FakePermissionFacts
import com.meshcoreone.android.app.container.onboarding.OnboardingPlatform
import com.meshcoreone.android.app.container.onboarding.WriteBehindOnboardingFlags
import com.meshcoreone.android.app.content.DataStoreLinkPreviewPreferencesSource
import com.meshcoreone.android.app.navigation.AppMessageContent
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.datastore.AppStorageKey
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppOnboardingStrings as O
import com.meshcoreone.android.core.maps.MapSnapshotRenderer
import com.meshcoreone.android.core.maps.MapSnapshotRequest
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.core.services.simulator.DemoOfflineContent
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.core.services.simulator.SimulatorSeedStore
import com.meshcoreone.android.core.services.simulator.locationHistoryNodePublicKey
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOutcome
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "w400dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SimulatorOnboardingIntegrationTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.windowFocusChanged(true)
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() {
        host.windowFocusChanged(false)
        host.pause().stop().destroy()
    }

    @Test fun mountedOnboardingConnectsAndTheRealStoreRetainsEverySeedFamily() = runTest {
        val fixture = fixture(this)
        var firstClosed = false
        try {
            host.get().setContent { MeshCoreTheme(motionScale = 0f) { OnboardingFlow(fixture.onboarding, {}) } }
            button(O.welcomeGetStarted).performClick()
            button(O.permissionsContinue).performClick()
            val title = compose.onNode(hasText(text(O.deviceScanTitle)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            repeat(3) { title.performClick() }
            compose.waitUntil(30_000) {
                fixture.harness.settle()
                compose.onAllNodesWithText(text(O.deviceScanDemoModeAlertTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(fixture.storage.preferences.get(AppStorageKey.isDemoModeUnlocked))
            assertTrue(fixture.storage.preferences.get(AppStorageKey.isDemoModeEnabled))
            button(L.commonOk).performClick()
            button(O.deviceScanContinueDemo).performScrollTo().performClick()
            compose.waitUntil(30_000) {
                fixture.harness.settle()
                compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isNotEmpty()
            }

            val harness = fixture.harness
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            assertTrue(harness.manager.isSimulatorConnection)
            assertEquals(MockDataProvider.simulatorDeviceID, harness.manager.connectedDevice?.id)
            assertEquals(MockDataProvider.simulatorDeviceID, store.fetchActiveDevice()?.id)
            assertNotNull(harness.appState.services)
            assertTrue(harness.links.radios.isEmpty(), "the simulator must not create a physical radio")
            assertEquals(OnboardingPresetOutcome.NoRadioToConfigure, fixture.onboarding.presets.apply("ignored"))
            assertSeedFamilies()

            val radio = MockDataProvider.simulatorRadioId
            val preview = message("10000000-0000-0000-0000-00000000000A")
            val image = message("10000000-0000-0000-0000-00000000000B")
            val reports = mutableListOf<String>()
            host.get().setContent {
                MeshCoreTheme(motionScale = 0f) {
                    Column {
                        AppMessageContent(fixture.content, preview.withoutPreviewBlobs(), { detail, _ -> reports += detail }, {})
                        AppMessageContent(fixture.content, image.withoutPreviewBlobs(), { detail, _ -> reports += detail }, {})
                    }
                }
            }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText("Skyline Ridge Trail Guide").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription(MockDataProvider.inlineImageURL).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText("Skyline Ridge Trail Guide")).assertIsDisplayed()
            assertTrue(reports.isEmpty(), reports.toString())
            fixture.content.prefetch(preview.text, false)
            fixture.content.prefetch(image.text, false)
            assertTrue(fixture.httpRequests.isEmpty(), fixture.httpRequests.toString())
            assertFalse(DemoOfflineContent.isInlineImage(image.copy(id = UUID.randomUUID()), MockDataProvider.inlineImageURL))
            assertNull(DemoOfflineContent.preview(preview.copy(radioId = com.meshcoreone.android.core.model.RadioId(UUID.randomUUID())),
                "https://meshcoreone.com/trails/skyline"))

            fixture.content.trimMemory()
            assertIs<ChatImage.Decoded>(fixture.content.bundledInlineImage(image, MockDataProvider.inlineImageURL))
            assertEquals(MockDataProvider.demoImageData, Bytes(fixture.content.shareData(MockDataProvider.inlineImageURL, image)))
            assertFailsWith<IllegalArgumentException> { fixture.content.shareData(MockDataProvider.inlineImageURL) }
            assertFailsWith<IllegalArgumentException> {
                fixture.content.shareData(MockDataProvider.inlineImageURL, image.copy(id = UUID.randomUUID()))
            }
            assertNotNull(fixture.content.previews(preview, "https://meshcoreone.com/trails/skyline")
                .cachedPreview("https://meshcoreone.com/trails/skyline"))
            assertTrue(fixture.httpRequests.isEmpty(), fixture.httpRequests.toString())

            val aliceKey = EntityKey(radio, MockDataProvider.aliceChenID)
            val avatar = Bytes(ByteArray(16) { 0xCD.toByte() })
            store.saveContact(assertNotNull(store.fetchContact(aliceKey)).withAvatar(avatar))
            val history = store.fetchNodeStatusSnapshots(MockDataProvider.locationHistoryNodePublicKey, null).map { it.id }.toSet()
            host.get().setContent {}
            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            assertEquals(avatar, store.fetchContact(aliceKey)?.avatarImageData)
            assertEquals(history, store.fetchNodeStatusSnapshots(MockDataProvider.locationHistoryNodePublicKey, null).map { it.id }.toSet())
            assertSeedFamilies()

            harness.container.close()
            firstClosed = true
            val restored = ContainerHarness(this, store, chatContent = fixture.content, preferencesOverride = harness.preferences)
            try {
                restored.manager.activate()
                restored.settle()
                assertEquals(DeviceConnectionState.READY, restored.manager.connectionState)
                assertEquals(MockDataProvider.simulatorDeviceID, restored.manager.connectedDevice?.id)
                assertTrue(restored.manager.isSimulatorConnection)
                assertTrue(restored.links.radios.isEmpty())
                assertEquals(avatar, store.fetchContact(aliceKey)?.avatarImageData)
                assertEquals(history, store.fetchNodeStatusSnapshots(MockDataProvider.locationHistoryNodePublicKey, null).map { it.id }.toSet())
            } finally {
                restored.container.close()
            }
        } finally {
            fixture.close(closeHarness = !firstClosed)
        }
    }

    @Test fun aSeedFailureIsVisibleAndRetryUsesTheSameRealStore() = runTest {
        var fail = true
        val fixture = fixture(this) { delegate ->
            object : SimulatorSeedStore by delegate {
                override suspend fun saveChannel(dto: ChannelDTO) {
                    if (fail) throw IOException("seed write failed")
                    delegate.saveChannel(dto)
                }
            }
        }
        try {
            assertIs<OnboardingConnectOutcome.Failed>(fixture.onboarding.demo.connectDemo())
            assertEquals(DeviceConnectionState.DISCONNECTED, fixture.harness.manager.connectionState)
            assertEquals(1, fixture.harness.lifecycle.created.size)
            assertEquals(1, fixture.harness.lifecycle.tornDown.size)
            fail = false
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            assertEquals(DeviceConnectionState.READY, fixture.harness.manager.connectionState)
            assertSeedFamilies()
            assertTrue(fixture.harness.links.radios.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun cancellingAnIncompleteSeedTearsDownAndDoesNotPreventRetry() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var block = true
        val fixture = fixture(this) { delegate ->
            object : SimulatorSeedStore by delegate {
                override suspend fun saveChannel(dto: ChannelDTO) {
                    if (block) {
                        entered.complete(Unit)
                        release.await()
                    }
                    delegate.saveChannel(dto)
                }
            }
        }
        try {
            val first = async { fixture.onboarding.demo.connectDemo() }
            entered.await()
            first.cancelAndJoin()
            assertTrue(first.isCancelled)
            assertEquals(DeviceConnectionState.DISCONNECTED, fixture.harness.manager.connectionState)
            assertEquals(1, fixture.harness.lifecycle.created.size)
            assertEquals(1, fixture.harness.lifecycle.tornDown.size)
            block = false
            release.complete(Unit)
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            assertSeedFamilies()
            assertTrue(fixture.harness.links.radios.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun aChangedPreviewNeverFallsBackToNetwork() = runTest {
        val fixture = fixture(this)
        try {
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            val preview = message("10000000-0000-0000-0000-00000000000A")
            val url = "https://meshcoreone.com/trails/skyline"
            store.updateMessageLinkPreview(EntityKey(preview.radioId, preview.id), url, "altered", Bytes.EMPTY, null, true)
            assertFailsWith<IllegalStateException> { fixture.content.previews(preview, url).cachedPreview(url) }
            assertTrue(fixture.httpRequests.isEmpty(), fixture.httpRequests.toString())
        } finally {
            fixture.close()
        }
    }

    @Test fun simulatorCoordinateThumbnailIsOfflineEvenWhenNetworkIsAvailable() = runTest {
        val fixture = fixture(this)
        try {
            val coordinate = mutableStateOf(MessageDTO(
                radioId = MockDataProvider.simulatorRadioId,
                text = "37.7749, -122.4194",
                timestamp = 1u,
                direction = MessageDirection.INCOMING,
            ))
            compose.runOnUiThread {
                host.get().setContent {
                    MeshCoreTheme(motionScale = 0f) {
                        AppMessageContent(fixture.content, coordinate.value,
                            { detail, cause -> throw AssertionError(detail, cause) }, {})
                    }
                }
            }
            compose.waitUntil(10_000) { fixture.mapRequests.isNotEmpty() }
            assertTrue(fixture.mapRequests.single().isOffline)
            compose.runOnUiThread {
                coordinate.value = coordinate.value.copy(
                    id = UUID.randomUUID(),
                    radioId = RadioId(UUID.randomUUID()),
                    text = "51.5074, -0.1278",
                )
            }
            compose.waitUntil(10_000) { fixture.mapRequests.size == 2 }
            assertFalse(fixture.mapRequests.last().isOffline)
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertSeedFamilies() {
        val radio = MockDataProvider.simulatorRadioId
        val contacts = store.fetchContacts(radio)
        assertEquals(11, contacts.size)
        val repeaterPrefixes = contacts.filter { it.type == ContactType.REPEATER && it.hasLocation }
            .map { it.publicKey.prefix(1) }.toSet()
        assertTrue(setOf(Bytes.of(0x91), Bytes.of(0x92), Bytes.of(0x93)).all { it in repeaterPrefixes })
        val channels = store.fetchChannels(radio)
        assertEquals(4, channels.size)
        assertEquals(NotificationLevel.MUTED, channels.single { it.index == 2.toUByte() }.notificationLevel)
        assertTrue(channels.single { it.index == 1.toUByte() }.isFavorite)

        val preview = message("10000000-0000-0000-0000-00000000000A")
        assertEquals("Skyline Ridge Trail Guide", preview.linkPreviewTitle)
        assertTrue(preview.linkPreviewFetched)
        assertEquals(MockDataProvider.demoImageData, preview.linkPreviewImageData)
        val dmReaction = message("10000000-0000-0000-0000-000000000002")
        assertEquals("👍:2,❤️:1", dmReaction.reactionSummary)
        assertEquals(3, store.fetchReactions(EntityKey(radio, dmReaction.id)).size)
        val channelReaction = message("C1000000-0000-0000-0000-000000000002")
        assertEquals("🎉:2", channelReaction.reactionSummary)
        assertEquals(2, store.fetchReactions(EntityKey(radio, channelReaction.id)).size)
        assertEquals(3, store.fetchMessageRepeats(key("60000000-0000-0000-0000-000000000002")).size)

        for (id in listOf("10000000-0000-0000-0000-000000000010", "C0000000-0000-0000-0000-000000000006")) {
            val multi = message(id)
            assertFalse(multi.isOutgoing)
            assertEquals(3L, multi.heardRepeats)
            assertEquals(2L, multi.hopCount)
            assertEquals(Bytes.of(0x91, 0x92), multi.pathNodes)
            val repeats = store.fetchMessageRepeats(key(id))
            assertEquals(setOf(1L, 2L, 3L), repeats.map { it.hopCount }.toSet())
            assertTrue(repeats.flatMap { it.pathNodes }.all { Bytes.of(it.toInt()) in repeaterPrefixes })
        }
        val unique = message("60000000-0000-0000-0000-000000000004")
        assertEquals(RouteType.TC_FLOOD, unique.routeType)
        assertEquals("US915", unique.regionScope)
        assertEquals(listOf("US915"), unique.regionScopeMatches)
        for (id in listOf("60000000-0000-0000-0000-000000000005", "C0000000-0000-0000-0000-000000000005")) {
            val ambiguous = message(id)
            assertEquals(RouteType.TC_FLOOD, ambiguous.routeType)
            assertNull(ambiguous.regionScope)
            assertEquals(listOf("de-by", "de-hh"), ambiguous.regionScopeMatches)
        }
        val entries = store.fetchRxLogEntries(radio)
        assertEquals(2, entries.size)
        val uniqueRx = entries.single { it.id == uuid("A0000000-0000-0000-0000-000000000001") }
        assertEquals("US915", uniqueRx.regionScope)
        assertEquals(listOf("US915"), uniqueRx.regionScopeMatches)
        assertFalse(assertNotNull(uniqueRx.transportCode).isEmpty)
        val ambiguousRx = entries.single { it.id == uuid("A0000000-0000-0000-0000-000000000002") }
        assertNull(ambiguousRx.regionScope)
        assertEquals(listOf("de-by", "de-hh"), ambiguousRx.regionScopeMatches)
        assertNotNull(db.rxLogs().byId(radio.value, uniqueRx.id), "seed success must follow the actual RX flush")
        assertNotNull(db.rxLogs().byId(radio.value, ambiguousRx.id), "seed success must follow the actual RX flush")

        val snapshots = store.fetchNodeStatusSnapshots(MockDataProvider.locationHistoryNodePublicKey, null)
        assertEquals(20, snapshots.size)
        assertEquals(37.7149, snapshots.last().latitude)
        assertEquals(-122.4794, snapshots.last().longitude)
        assertTrue(snapshots.any { it.altitude == null })
    }

    private suspend fun message(id: String): MessageDTO = assertNotNull(store.fetchMessage(key(id)))
    private fun key(id: String) = EntityKey(MockDataProvider.simulatorRadioId, uuid(id))
    private fun uuid(value: String): UUID = UUID.fromString(value)
    private fun text(id: Int) = host.get().getString(id)
    private fun button(id: Int) = compose.onNode(hasText(text(id)) and hasClickAction())

    private suspend fun fixture(
        test: TestScope,
        wrapSeed: (SimulatorSeedStore) -> SimulatorSeedStore = { it },
    ): Fixture {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val storage = MeshCoreStorage.get(context)
        storage.preferences.set(AppStorageKey.isDemoModeUnlocked, false)
        storage.preferences.set(AppStorageKey.isDemoModeEnabled, false)
        storage.preferences.set(AppStorageKey.linkPreviewsEnabled, true)
        storage.preferences.set(AppStorageKey.linkPreviewsAutoResolveDM, true)
        storage.preferences.set(AppStorageKey.showMapPreviewThumbnails, true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = java.util.Collections.synchronizedList(mutableListOf<String>())
        val mapRequests = java.util.concurrent.CopyOnWriteArrayList<MapSnapshotRequest>()
        val mapPreviews = AppChatMapPreviews(
            context,
            scope,
            renderer = object : MapSnapshotRenderer<Bitmap> {
                override suspend fun render(request: MapSnapshotRequest): Bitmap {
                    mapRequests += request
                    return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
                }
            },
            online = { true },
        )
        val http = object : BoundedHttpFetching {
            override suspend fun fetch(url: String, timeoutMs: Long, rangeHeader: String?): HttpFetchAttempt {
                requests += url
                throw AssertionError("Simulator initiated an HTTP request: $url")
            }
        }
        val content = AppChatContent(
            context, store, http, DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope),
            storage.preferences, storage.preferences.snapshot(), scope, mapPreviews,
        )
        val harness = ContainerHarness(test, store, chatContent = content, simulatorSeedStore = wrapSeed(RoomSimulatorSeedStore(store)))
        val flags = WriteBehindOnboardingFlags(emptyMap(), scope, persist = { _, _ -> })
        val onboarding = AppOnboardingFactory.create(harness.container, OnboardingPlatform(flags, FakePermissionFacts()),
            scope, DemoModeManager(DataStoreDemoModeDefaults.load(storage.preferences)))
        return Fixture(storage, scope, content, harness, onboarding, requests, mapRequests)
    }

    private class Fixture(
        val storage: MeshCoreStorage,
        val scope: CoroutineScope,
        val content: AppChatContent,
        val harness: ContainerHarness,
        val onboarding: AppOnboarding,
        val httpRequests: List<String>,
        val mapRequests: List<MapSnapshotRequest>,
    ) {
        suspend fun close(closeHarness: Boolean = true) {
            if (closeHarness) harness.container.close()
            content.close()
            scope.cancel()
            storage.close()
        }
    }
}
