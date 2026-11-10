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
import androidx.compose.ui.test.onLast
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
import com.meshcoreone.android.core.connectivity.ConnectivityDiagnostics
import com.meshcoreone.android.core.connectivity.SystemConnectivityClock
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingDelegate
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingService
import com.meshcoreone.android.core.connectivity.pairing.PairingCoordinator
import com.meshcoreone.android.core.contracts.domain.DevicePersisting
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
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.NotificationLevel
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.TransportType
import com.meshcoreone.android.core.services.simulator.channels as restoredSimulatorChannels
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshTransportError
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.BatteryInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.content.BoundedHttpFetching
import com.meshcoreone.android.core.services.content.HttpFetchAttempt
import com.meshcoreone.android.core.services.rendering.EnvInputs
import com.meshcoreone.android.core.services.simulator.channels
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.core.services.simulator.DemoOfflineContent
import com.meshcoreone.android.core.services.simulator.MockDataProvider
import com.meshcoreone.android.core.services.simulator.SimulatorSeedStore
import com.meshcoreone.android.core.services.simulator.locationHistoryNodePublicKey
import com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome
import com.meshcoreone.android.feature.onboarding.OnboardingPresetOutcome
import com.meshcoreone.android.feature.onboarding.ui.OnboardingFlow
import com.meshcoreone.android.core.contracts.domain.ContactAvatarUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactFavoriteUpdateResult
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.notifications.NotificationAction
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationResponse
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.services.notifications.didReceive
import com.meshcoreone.android.core.services.simulator.SimulatorDeferredFixturePlan
import com.meshcoreone.android.core.services.simulator.SimulatorFixtureParent
import com.meshcoreone.android.core.services.simulator.SimulatorFixtureSeedResult
import com.meshcoreone.android.core.services.simulator.channelMessages
import com.meshcoreone.android.core.services.simulator.contacts
import com.meshcoreone.android.core.services.simulator.messages
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.feature.nodes.contacts.ContactsStateHolder
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import java.time.Instant
import java.util.Locale
import java.io.IOException
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
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

    @Test fun freshStoreBackupImportPreservesChannelRowsAcrossSimulatorReconnects() = runTest {
        val fixture = fixture(this)
        try {
            val harness = fixture.harness
            val radio = MockDataProvider.simulatorRadioId
            val now = java.time.Instant.ofEpochSecond(1_700_000_000)
            val seedChannels = MockDataProvider.channels(now)
            val restoredMessage = MessageDTO(
                radioId = radio,
                channelIndex = MockDataProvider.publicChannelIndex,
                text = "Restored simulator history",
                timestamp = 1_700_000_000u,
                createdAt = now,
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
            )
            assertTrue(store.fetchDevices().isEmpty())
            assertTrue(store.fetchChannels(radio).isEmpty())
            val backup = com.meshcoreone.android.core.data.backup.AppBackupEnvelope(
                exportDate = now,
                appVersion = "simulator-regression",
                appBuild = "1",
                devices = SnapshotList(listOf(MockDataProvider.simulatorDevice(now))),
                channels = seedChannels,
                messages = SnapshotList(listOf(restoredMessage)),
            ).withActualManifest()
            com.meshcoreone.android.core.data.backup.AppBackupService(
                fixture.storage.backupPreferences, "simulator-regression", "1",
            ).importBackup(backup, store)

            val restored = store.fetchChannels(radio)
            val restoredBySlot = restored.associateBy { it.index }
            assertEquals(4, seedChannels.size)
            assertEquals(seedChannels.size, restored.size)
            assertEquals(seedChannels.map { it.index }.toSet(), restoredBySlot.keys)
            for (seed in seedChannels) {
                val channel = restoredBySlot.getValue(seed.index)
                assertNotEquals(seed.id, channel.id)
                assertEquals(seed.secret, channel.secret)
                assertEquals(seed.isEnabled, channel.isEnabled)
                assertNull(store.fetchChannel(EntityKey(radio, seed.id)))
            }
            assertEquals(restoredMessage.text, assertNotNull(store.fetchMessage(EntityKey(radio, restoredMessage.id))).text)

            val foreign = seedChannels.first().copy(radioId = RadioId(UUID.randomUUID()), name = "Unrelated radio")
            store.saveChannel(foreign)
            repeat(3) {
                assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
                harness.settle()
                assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
                val channels = store.fetchChannels(radio)
                assertEquals(4, channels.size, "each seed must retain exactly one row per simulator slot")
                assertEquals(restoredBySlot.mapValues { it.value.id }, channels.associate { it.index to it.id })
                for (seed in seedChannels) {
                    val channel = channels.single { it.index == seed.index }
                    assertEquals(seed.secret, channel.secret)
                    assertEquals(seed.isEnabled, channel.isEnabled)
                    assertNull(store.fetchChannel(EntityKey(radio, seed.id)))
                }
                assertEquals(restoredMessage.text, assertNotNull(store.fetchMessage(EntityKey(radio, restoredMessage.id))).text)
                assertEquals(foreign, store.fetchChannel(EntityKey(foreign.radioId, foreign.id)))
                assertSeedFamilies(missingChannelFixtures = seedChannels.map { it.index }.toSet())
                assertTrue(harness.links.radios.isEmpty())
                assertTrue(fixture.httpRequests.isEmpty())
                harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            }
            assertEquals(3, harness.lifecycle.created.size)
            assertEquals(3, harness.lifecycle.tornDown.size)
        } finally {
            fixture.close()
        }
    }

    @Test fun mountedOnboardingConnectsAndTheRealStoreRetainsEverySeedFamily() = runTest {
        val fixture = fixture(this)
        val subscription = fixture.harness.manager.subscribeTransitions()
        val transitions = java.util.Collections.synchronizedList(mutableListOf(subscription.initial))
        val recorder = backgroundScope.launch { subscription.transitions.collect { transitions += it } }
        var firstClosed = false
        try {
            fixture.harness.appState.batteryMonitor.deviceBattery = BatteryInfo(level = 3700)
            host.get().setContent { MeshCoreTheme(motionScale = 0f) { OnboardingFlow(fixture.onboarding, {}) } }
            button(O.welcomeGetStarted).performClick()
            compose.onAllNodes(hasText(text(O.permissionsContinue)) and hasClickAction()).onLast().performClick()
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
            fixture.harness.eventually("mounted simulator connection", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                fixture.harness.manager.connectionState == DeviceConnectionState.READY
            }
            compose.waitUntil(30_000) {
                fixture.harness.settle()
                compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isNotEmpty()
            }

            val harness = fixture.harness
            assertEquals(
                DeviceConnectionState.READY, harness.manager.connectionState,
                "transitions=$transitions; diagnostics=${harness.diagnostics}; " +
                    "created=${harness.lifecycle.created.size}; tornDown=${harness.lifecycle.tornDown.size}; " +
                    "preferences=${harness.preferences.values}",
            )
            assertTrue(harness.manager.isSimulatorConnection)
            assertEquals(MockDataProvider.simulatorDeviceID, harness.manager.connectedDevice?.id)
            assertEquals(MockDataProvider.simulatorDeviceID, store.fetchActiveDevice()?.id)
            assertEquals(TransportType.SIMULATOR, assertNotNull(harness.appState.services).transportType)
            assertTrue(harness.links.radios.isEmpty(), "the simulator must not create a physical radio")
            assertNull(harness.appState.batteryMonitor.deviceBattery, "a prior radio's battery must not carry over")
            assertFalse(harness.appState.batteryMonitor.isBootstrapActive)
            assertFalse(harness.appState.batteryMonitor.isRefreshLoopActive)
            repeat(2) {
                assertEquals(MeshTransportError.NotConnected, assertFailsWith<MeshTransportError> {
                    assertNotNull(harness.sessions.current).settingsService.getBattery()
                })
            }
            harness.appState.handleReturnToForeground()
            harness.settle()
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            assertFalse(harness.appState.batteryMonitor.isBootstrapActive)
            assertFalse(harness.appState.batteryMonitor.isRefreshLoopActive)
            assertEquals(1, harness.lifecycle.created.size)
            assertTrue(harness.lifecycle.tornDown.isEmpty())
            assertEquals(OnboardingPresetOutcome.NoRadioToConfigure, fixture.onboarding.presets.apply("ignored"))
            assertSeedFamilies()

            val radio = MockDataProvider.simulatorRadioId
            val rawAlice = MockDataProvider.contacts(Instant.ofEpochSecond(1_700_000_000))
                .single { it.id == MockDataProvider.aliceChenID }
            assertNull(rawAlice.lastHeardTimestamp)
            val storedAlice = assertNotNull(store.fetchContact(EntityKey(radio, rawAlice.id)))
            assertEquals(rawAlice.publicKey, storedAlice.publicKey)
            assertEquals(0u, storedAlice.lastHeardTimestamp)
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
            recorder.cancel()
            subscription.close()
            fixture.close(closeHarness = !firstClosed)
        }
    }

    @Test fun realSimulatorReconnectPreservesPersonalizedSeededChannelState() = runTest {
        val fixture = fixture(this)
        try {
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            val harness = fixture.harness
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            val radio = MockDataProvider.simulatorRadioId
            val original = assertNotNull(store.fetchChannel(radio, MockDataProvider.publicChannelIndex))
            assertEquals(MockDataProvider.publicChannelID, original.id)
            assertFalse(original.isFavorite)
            assertEquals(NotificationLevel.ALL, original.notificationLevel)
            val channelKey = EntityKey(radio, original.id)
            store.setChannelFavorite(channelKey, true)
            store.setChannelNotificationLevel(channelKey, NotificationLevel.MUTED)
            store.setChannelFloodScope(
                channelKey,
                com.meshcoreone.android.core.model.ChannelFloodScope.Region("US915"),
            )
            val customized = assertNotNull(store.fetchChannel(channelKey))
            assertTrue(customized.isFavorite)
            assertEquals(NotificationLevel.MUTED, customized.notificationLevel)
            assertEquals(com.meshcoreone.android.core.model.ChannelFloodScope.Region("US915"), customized.floodScope)
            val history = MessageDTO(
                radioId = radio,
                channelIndex = original.index,
                text = "History on a personalized simulator channel",
                timestamp = 1_700_000_000u,
                createdAt = java.time.Instant.ofEpochSecond(1_700_000_000),
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
            )
            store.saveMessage(history)
            val persistedHistory = assertNotNull(store.fetchMessage(EntityKey(radio, history.id)))
            val channelIds = store.fetchChannels(radio).map { it.id }.toSet()

            repeat(3) {
                harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
                assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
                assertTrue(harness.manager.isSimulatorConnection)
                assertEquals(customized, store.fetchChannel(channelKey))
                assertEquals(channelIds, store.fetchChannels(radio).map { it.id }.toSet())
                assertEquals(persistedHistory, store.fetchMessage(EntityKey(radio, history.id)))
            }
            assertSeedFamilies()
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun realSimulatorReconnectPreservesPersonalizedSeededContactState() = runTest {
        val fixture = fixture(this)
        try {
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            val harness = fixture.harness
            val radio = MockDataProvider.simulatorRadioId
            val key = EntityKey(radio, MockDataProvider.aliceChenID)
            val original = assertNotNull(store.fetchContact(key))
            val avatar = Bytes(ByteArray(16) { (it + 1).toByte() })
            assertEquals(ContactAvatarUpdateResult.UPDATED, store.updateContactAvatar(key, original.publicKey, avatar))
            assertEquals(ContactFavoriteUpdateResult.UPDATED, store.updateContactFavorite(key, original.publicKey, true))
            store.setContactMuted(key, true)
            val customized = assertNotNull(store.fetchContact(key))
            assertEquals(avatar, customized.avatarImageData)
            assertTrue(customized.isFavorite)
            assertTrue(customized.isMuted)
            val history = MessageDTO(
                radioId = radio,
                contactID = key.id,
                text = "History on a personalized simulator contact",
                timestamp = 1_700_000_000u,
                createdAt = java.time.Instant.ofEpochSecond(1_700_000_000),
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
            )
            store.saveMessage(history)
            val persistedHistory = assertNotNull(store.fetchMessage(EntityKey(radio, history.id)))
            val contactIds = store.fetchContacts(radio).map { it.id }.toSet()
            var lastModified = customized.lastModified

            repeat(3) {
                harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
                assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
                assertTrue(harness.manager.isSimulatorConnection)
                val retained = assertNotNull(store.fetchContact(key))
                assertTrue(retained.lastModified >= lastModified)
                assertEquals(customized.copy(lastModified = retained.lastModified), retained)
                lastModified = retained.lastModified
                assertEquals(contactIds, store.fetchContacts(radio).map { it.id }.toSet())
                assertEquals(persistedHistory, store.fetchMessage(EntityKey(radio, history.id)))
            }
            assertSeedFamilies()
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun ambiguousSeededContactFailsVisiblyAndRetriesWithoutMovingHistory() = runTest {
        val fixture = fixture(this)
        try {
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            val harness = fixture.harness
            val radio = MockDataProvider.simulatorRadioId
            val key = EntityKey(radio, MockDataProvider.aliceChenID)
            store.setContactMuted(key, true)
            val original = assertNotNull(db.contacts().byId(radio.value, key.id))
            val duplicate = original.copy(id = UUID.randomUUID())
            db.contacts().insert(duplicate)
            val history = MessageDTO(
                radioId = radio,
                contactID = key.id,
                text = "Keep this simulator conversation",
                timestamp = 1_700_000_001u,
                createdAt = java.time.Instant.ofEpochSecond(1_700_000_001),
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
            )
            store.saveMessage(history)
            val persistedHistory = assertNotNull(store.fetchMessage(EntityKey(radio, history.id)))
            val before = db.contacts().forRadio(radio.value).toSet()

            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            val failure = assertIs<OnboardingConnectOutcome.Failed>(fixture.onboarding.demo.connectDemo())
            val invalidData = com.meshcoreone.android.core.ui.UiErrorMapper().persistenceError(
                com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.InvalidData,
            )
                .resolve(host.get().resources)
            assertEquals(invalidData, failure.message.resolve(host.get().resources))
            assertEquals(DeviceConnectionState.DISCONNECTED, harness.manager.connectionState)
            assertEquals(before, db.contacts().forRadio(radio.value).toSet())
            assertEquals(persistedHistory, store.fetchMessage(EntityKey(radio, history.id)))
            harness.eventually("ambiguous simulator contact failure", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                synchronized(harness.diagnostics) {
                    harness.diagnostics.any {
                        it is com.meshcoreone.android.core.runtime.RuntimeDiagnostic.Failure &&
                            (it.cause as? com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)?.error ==
                            com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.InvalidData
                    }
                }
            }

            // Fixture-only repair; the production seed must not delete either identity.
            assertEquals(1, db.contacts().delete(radio.value, duplicate.id))
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            val retained = assertNotNull(db.contacts().byId(radio.value, key.id))
            assertTrue(retained.lastModified >= original.lastModified)
            assertEquals(original.copy(lastModified = retained.lastModified), retained)
            assertEquals(persistedHistory, store.fetchMessage(EntityKey(radio, history.id)))
            assertSeedFamilies()
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun freshContactStoreRestoresASeededIdentityAndPreservesItAcrossReconnects() = runTest {
        val fixture = fixture(this)
        try {
            val now = java.time.Instant.ofEpochSecond(1_700_000_000)
            val radio = MockDataProvider.simulatorRadioId
            val contact = MockDataProvider.contacts(now).first { it.id == MockDataProvider.aliceChenID }
                .copy(isMuted = true, isFavorite = true, nickname = "Restored Alice")
                .withAvatar(Bytes(ByteArray(16) { (it + 1).toByte() }))
            val history = importSimulatorContactFixture(fixture, contact, now)
            val key = EntityKey(radio, contact.id)
            val imported = assertNotNull(store.fetchContact(key))
            assertTrue(imported.isMuted)
            assertTrue(imported.isFavorite)
            assertEquals("Restored Alice", imported.nickname)
            assertEquals(contact.avatarImageData, imported.avatarImageData)
            var lastModified = imported.lastModified

            repeat(3) {
                assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
                assertEquals(DeviceConnectionState.READY, fixture.harness.manager.connectionState)
                val retained = assertNotNull(store.fetchContact(key))
                assertTrue(retained.lastModified >= lastModified)
                assertEquals(imported.copy(lastModified = retained.lastModified), retained)
                lastModified = retained.lastModified
                assertEquals(history, store.fetchMessage(EntityKey(radio, history.id)))
                assertEquals(1, store.fetchContacts(radio).count { it.publicKey == contact.publicKey })
                fixture.harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            }
            assertSeedFamilies(missingAliceFixtures = true)
            assertTrue(fixture.harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun restoredAlternateContactIdPreservesTheExistingPartialConversation() = runTest {
        val fixture = fixture(this)
        try {
            val now = java.time.Instant.ofEpochSecond(1_700_000_000)
            val radio = MockDataProvider.simulatorRadioId
            val fixtureContact = MockDataProvider.contacts(now).first { it.id == MockDataProvider.aliceChenID }
            val restored = fixtureContact.copy(id = UUID.randomUUID(), isMuted = true, nickname = "Restored alias")
            val history = importSimulatorContactFixture(fixture, restored, now)
            val key = EntityKey(radio, restored.id)
            val before = assertNotNull(store.fetchContact(key))
            assertNull(store.fetchContact(EntityKey(radio, fixtureContact.id)))

            repeat(3) {
                assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
                assertEquals(DeviceConnectionState.READY, fixture.harness.manager.connectionState)
                val retained = assertNotNull(store.fetchContact(key))
                assertTrue(retained.lastModified >= before.lastModified)
                assertEquals(before.copy(lastModified = retained.lastModified), retained)
                assertNull(store.fetchContact(EntityKey(radio, fixtureContact.id)))
                assertEquals(listOf(restored.id), store.fetchContacts(radio).filter {
                    it.publicKey == fixtureContact.publicKey
                }.map { it.id })
                assertEquals(history, store.fetchMessage(EntityKey(radio, history.id)))
                assertEquals(restored.id, history.contactID)
                fixture.harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            }
            assertSeedFamilies(missingAliceFixtures = true)
            assertTrue(fixture.harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun mountedSimulatorRefreshesContactRecencyBeforeAutomaticCleanupWithoutLosingUserState() = runTest {
        val cleanup = MemoryStaleCleanup(days = 14)
        val fixture = fixture(this, stalePreferences = cleanup)
        try {
            val radio = MockDataProvider.simulatorRadioId
            val oldTime = java.time.Instant.now().minusSeconds(30L * 86_400L)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            val oldSeed = com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode(
                clock = java.time.Clock.fixed(oldTime, java.time.ZoneOffset.UTC),
            )
            oldSeed.seedDataStore(RoomSimulatorSeedStore(store))
            val aliceKey = EntityKey(radio, MockDataProvider.aliceChenID)
            val hannahKey = EntityKey(radio, MockDataProvider.hannahLeeID)
            val ghostKey = EntityKey(radio, MockDataProvider.ghostNodeID)
            val aliceBefore = assertNotNull(store.fetchContact(aliceKey))
            val hannah = assertNotNull(store.fetchContact(hannahKey))
            assertTrue(hannah.isFavorite)
            assertEquals(ContactFavoriteUpdateResult.UPDATED, store.updateContactFavorite(hannahKey, hannah.publicKey, false))
            store.setContactMuted(hannahKey, true)
            val hannahBefore = assertNotNull(store.fetchContact(hannahKey))
            val ghostBefore = assertNotNull(store.fetchContact(ghostKey))
            val sentinel = ContactDTO(
                radioId = radio,
                publicKey = Bytes(ByteArray(32) { 0x7E.toByte() }),
                name = "Favorite older contact",
                typeRawValue = ContactType.CHAT.rawValue,
                lastModified = oldTime.plusSeconds(86_400L).epochSecond.toUInt(),
                lastHeardTimestamp = null,
                isFavorite = true,
            )
            store.saveContact(sentinel)
            val storedSentinel = assertNotNull(store.fetchContact(EntityKey(radio, sentinel.id)))
            assertTrue(storedSentinel.recencyTimestamp > aliceBefore.recencyTimestamp)
            assertFalse(hannahBefore.isFavorite)
            assertTrue(hannahBefore.isMuted)
            val connectionStarted = java.time.Instant.now().epochSecond.toUInt()

            mountAndConnectSimulator(fixture)
            val harness = fixture.harness
            harness.eventually("automatic stale cleanup", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                cleanup.last != null
            }
            val aliceAfter = assertNotNull(store.fetchContact(aliceKey))
            val hannahAfter = assertNotNull(store.fetchContact(hannahKey))
            val ghostAfter = assertNotNull(store.fetchContact(ghostKey))
            assertTrue(aliceAfter.lastModified >= connectionStarted)
            assertEquals(aliceBefore.copy(lastModified = aliceAfter.lastModified), aliceAfter)
            assertEquals(hannahBefore.copy(lastModified = hannahAfter.lastModified), hannahAfter)
            assertEquals(ghostBefore.copy(lastModified = ghostAfter.lastModified), ghostAfter)
            assertEquals(aliceAfter.lastModified, hannahAfter.lastModified)
            assertEquals(aliceAfter.lastModified - 86_400u, ghostAfter.lastModified)
            assertEquals(storedSentinel, store.fetchContact(EntityKey(radio, sentinel.id)))

            val contacts = ContactsStateHolder(harness.container.nodesFeature, fixture.scope)
            contacts.loadContacts(radio)
            val sorted = contacts.filteredContacts("", NodeSegment.CONTACTS, NodeSortOrder.LAST_HEARD, null)
            val alicePosition = sorted.indexOfFirst { it.id == aliceKey.id }
            val sentinelPosition = sorted.indexOfFirst { it.id == sentinel.id }
            assertTrue(alicePosition >= 0 && sentinelPosition > alicePosition)
            val removal = harness.container.deviceEditor.removeStaleNodes(14)
            assertEquals(0, removal.total)
            assertEquals(0, removal.removed)

            oldSeed.seedDataStore(RoomSimulatorSeedStore(store))
            assertEquals(aliceAfter, store.fetchContact(aliceKey))
            assertEquals(hannahAfter, store.fetchContact(hannahKey))
            assertEquals(ghostAfter, store.fetchContact(ghostKey))
        } finally {
            fixture.close()
        }
    }

    @Test fun mountedSimulatorReconnectPreservesReadMentionsAndMessageSidecars() = runTest {
        val fixture = fixture(this)
        try {
            val radio = MockDataProvider.simulatorRadioId
            val oldTime = java.time.Instant.now().minusSeconds(30L * 86_400L)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS)
            com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode(
                clock = java.time.Clock.fixed(oldTime, java.time.ZoneOffset.UTC),
            ).seedDataStore(RoomSimulatorSeedStore(store))
            val alice = EntityKey(radio, MockDataProvider.aliceChenID)
            val reacted = key("10000000-0000-0000-0000-000000000002")
            val preview = key("10000000-0000-0000-0000-00000000000A")
            val repeated = key("60000000-0000-0000-0000-000000000002")
            val mention = key("C1000000-0000-0000-0000-000000000003")
            val oldReacted = assertNotNull(store.fetchMessage(reacted))
            val oldRepeats = store.fetchMessageRepeats(repeated).toSet()
            assertTrue(oldRepeats.isNotEmpty())

            mountAndConnectSimulator(fixture)
            val harness = fixture.harness
            assertEquals(oldReacted.createdAt, assertNotNull(store.fetchMessage(reacted)).createdAt)
            assertEquals(oldRepeats, store.fetchMessageRepeats(repeated).toSet())
            val session = assertNotNull(harness.sessions.current)
            assertNotNull(session.notificationService.onMarkAsRead)
            assertNotNull(session.notificationService.onChannelMarkAsRead)
            val unread = store.fetchMessages(alice, limit = 500).filter {
                it.direction == MessageDirection.INCOMING && !it.isRead
            }
            assertTrue(unread.isNotEmpty())
            for (row in unread) {
                session.notificationService.didReceive(
                    NotificationResponse(NotificationPayload.DirectMessage(alice, row.id), NotificationAction.MARK_READ, null),
                )
            }
            assertNull(store.newestUnreadIncomingMessage(alice))
            val aliceAfterRead = assertNotNull(store.fetchContact(alice))
            assertEquals(0L, aliceAfterRead.unreadCount)

            val meshIndex = MockDataProvider.meshHQChannelIndex
            val mesh = assertNotNull(store.fetchChannel(radio, meshIndex))
            val meshMessage = store.fetchMessages(radio, meshIndex, limit = 500).first { !it.isRead }
            val meshKey = EntityKey(radio, meshMessage.id)
            session.notificationService.didReceive(
                NotificationResponse(NotificationPayload.ChannelMessage(radio, meshIndex, meshMessage.id), NotificationAction.MARK_READ, null),
            )
            assertTrue(assertNotNull(store.fetchMessage(meshKey)).isRead)
            assertEquals(0L, assertNotNull(store.fetchChannel(radio, meshIndex)).unreadCount)

            val bayIndex = MockDataProvider.bayAreaChannelIndex
            val bay = assertNotNull(store.fetchChannel(radio, bayIndex))
            assertTrue(mention.id in store.fetchUnseenChannelMentionIDs(radio, bayIndex))
            store.markMentionSeen(mention)
            store.clearChannelUnreadMentionCount(EntityKey(radio, bay.id))
            session.notificationService.didReceive(
                NotificationResponse(NotificationPayload.ChannelMessage(radio, bayIndex, mention.id), NotificationAction.MARK_READ, null),
            )
            assertTrue(assertNotNull(store.fetchMessage(mention)).mentionSeen)
            assertFalse(mention.id in store.fetchUnseenChannelMentionIDs(radio, bayIndex))
            assertEquals(0L, assertNotNull(store.fetchChannel(radio, bayIndex)).unreadMentionCount)

            val extraReaction = ReactionDTO(
                messageID = reacted.id,
                emoji = "👍",
                senderName = "Local reaction",
                messageHash = reacted.id.toString().uppercase(java.util.Locale.ROOT).take(8),
                rawText = "👍",
                receivedAt = oldTime.plusSeconds(5),
                contactID = alice.id,
                radioId = radio,
            )
            val reactionResult = assertNotNull(session.reactionService.persistReactionAndUpdateSummary(extraReaction, store))
            val reactionsBefore = store.fetchReactions(reacted).toSet()
            assertEquals(3, reactionsBefore.count { it.emoji == "👍" })
            assertEquals(reactionResult.summary, assertNotNull(store.fetchMessage(reacted)).reactionSummary)
            store.updateMessageLinkPreview(
                preview, "https://example.invalid/preserved", "Preserved preview",
                Bytes.of(1, 2, 3), Bytes.of(4, 5), fetched = true,
            )
            val watched = (unread.map { EntityKey(radio, it.id) } + listOf(meshKey, mention, reacted, preview, repeated))
                .distinct()
                .associateWith { assertNotNull(store.fetchMessage(it)) }
            val meshAfterRead = assertNotNull(store.fetchChannel(radio, meshIndex))
            val bayAfterRead = assertNotNull(store.fetchChannel(radio, bayIndex))
            val repeatsBefore = store.fetchMessageRepeats(repeated).toSet()
            assertEquals(oldRepeats, repeatsBefore)
            assertEquals(mesh.id, meshAfterRead.id)
            assertEquals(bay.id, bayAfterRead.id)

            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            reconnectMountedSimulator(fixture)
            assertNull(store.newestUnreadIncomingMessage(alice))
            assertEquals(aliceAfterRead.lastMessageDate, assertNotNull(store.fetchContact(alice)).lastMessageDate)
            assertEquals(0L, assertNotNull(store.fetchContact(alice)).unreadCount)
            assertEquals(meshAfterRead, store.fetchChannel(radio, meshIndex))
            assertEquals(bayAfterRead, store.fetchChannel(radio, bayIndex))
            assertFalse(mention.id in store.fetchUnseenChannelMentionIDs(radio, bayIndex))
            for ((messageKey, before) in watched) assertEquals(before, store.fetchMessage(messageKey))
            assertEquals(reactionsBefore, store.fetchReactions(reacted).toSet())
            assertEquals(repeatsBefore, store.fetchMessageRepeats(repeated).toSet())
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun mountedSimulatorReconnectPreservesDeletedFixtureMessagesAndSidecars() = runTest {
        val fixture = fixture(this)
        try {
            mountAndConnectSimulator(fixture)
            val harness = fixture.harness
            val radio = MockDataProvider.simulatorRadioId
            val alice = EntityKey(radio, MockDataProvider.aliceChenID)
            val bayIndex = MockDataProvider.bayAreaChannelIndex
            val deletedDm = key("10000000-0000-0000-0000-00000000000A")
            val deletedChannel = key("C1000000-0000-0000-0000-000000000003")
            val reacted = key("10000000-0000-0000-0000-000000000002")
            val repeated = key("60000000-0000-0000-0000-000000000002")
            assertNotNull(store.fetchMessage(deletedDm))
            assertNotNull(store.fetchMessage(deletedChannel))
            assertTrue(store.fetchReactions(reacted).isNotEmpty())
            assertTrue(store.fetchMessageRepeats(repeated).isNotEmpty())

            store.deleteMessage(deletedDm)
            store.deleteMessage(deletedChannel)
            store.deleteReactionsForMessage(reacted)
            store.updateMessageReactionSummary(reacted, null)
            store.deleteMessageRepeats(repeated)
            val aliceBefore = assertNotNull(store.fetchContact(alice))
            val bayBefore = assertNotNull(store.fetchChannel(radio, bayIndex))
            val directBefore = store.fetchMessages(alice, limit = 500).toSet()
            val channelBefore = store.fetchMessages(radio, bayIndex, limit = 500).toSet()
            val reactedBefore = assertNotNull(store.fetchMessage(reacted))
            assertNull(reactedBefore.reactionSummary)
            assertTrue(store.fetchReactions(reacted).isEmpty())
            assertTrue(store.fetchMessageRepeats(repeated).isEmpty())

            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            reconnectMountedSimulator(fixture)
            val aliceAfter = assertNotNull(store.fetchContact(alice))
            assertTrue(aliceAfter.lastModified >= aliceBefore.lastModified)
            assertEquals(aliceBefore.copy(lastModified = aliceAfter.lastModified), aliceAfter)
            assertEquals(bayBefore, store.fetchChannel(radio, bayIndex))
            assertEquals(directBefore, store.fetchMessages(alice, limit = 500).toSet())
            assertEquals(channelBefore, store.fetchMessages(radio, bayIndex, limit = 500).toSet())
            assertNull(store.fetchMessage(deletedDm))
            assertNull(store.fetchMessage(deletedChannel))
            assertTrue(store.fetchReactions(deletedChannel).isEmpty())
            assertEquals(reactedBefore, store.fetchMessage(reacted))
            assertTrue(store.fetchReactions(reacted).isEmpty())
            assertTrue(store.fetchMessageRepeats(repeated).isEmpty())
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun mountedSimulatorRejectsForeignFixtureCollisionsAndOrphanSidecarsBeforeRetry() = runTest {
        val fixture = fixture(this)
        try {
            val harness = fixture.harness
            val radio = MockDataProvider.simulatorRadioId
            val alice = EntityKey(radio, MockDataProvider.aliceChenID)
            val collided = key("10000000-0000-0000-0000-00000000000A")
            val reacted = key("10000000-0000-0000-0000-000000000002")
            val foreign = MessageDTO(
                id = collided.id,
                radioId = radio,
                contactID = UUID.randomUUID(),
                text = "Unrelated restored message",
                timestamp = 1_700_000_000u,
                createdAt = Instant.ofEpochSecond(1_700_000_000),
                direction = MessageDirection.INCOMING,
                status = MessageStatus.DELIVERED,
            )
            store.saveMessage(foreign)
            val savedForeign = assertNotNull(store.fetchMessage(collided))
            host.get().setContent { MeshCoreTheme(motionScale = 0f) { OnboardingFlow(fixture.onboarding, {}) } }
            button(O.welcomeGetStarted).performClick()
            compose.onAllNodes(hasText(text(O.permissionsContinue)) and hasClickAction()).onLast().performClick()
            val title = compose.onNode(hasText(text(O.deviceScanTitle)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            repeat(3) { title.performClick() }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(text(O.deviceScanDemoModeAlertTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            button(L.commonOk).performClick()
            val invalidData = UiErrorMapper().persistenceError(PersistenceStoreError.InvalidData)
                .resolve(host.get().resources)
            fun invalidDataFailureCount(): Int = synchronized(harness.diagnostics) {
                harness.diagnostics.count {
                    it is RuntimeDiagnostic.Failure && it.operation == "connect.attempt.1" &&
                        (it.cause as? PersistenceStoreException)?.error == PersistenceStoreError.InvalidData
                }
            }
            var observedFailures = invalidDataFailureCount()
            fun assertVisibleFailure() {
                harness.eventually("simulator fixture collision rejection", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                    invalidDataFailureCount() > observedFailures
                }
                observedFailures = invalidDataFailureCount()
                compose.waitUntil(30_000) {
                    harness.settle()
                    compose.onAllNodesWithText(invalidData).fetchSemanticsNodes().isNotEmpty()
                }
                compose.onNode(hasText(invalidData)).assertIsDisplayed()
                assertEquals(DeviceConnectionState.DISCONNECTED, harness.manager.connectionState)
                assertTrue(compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isEmpty())
            }

            button(O.deviceScanContinueDemo).performScrollTo().performClick()
            assertVisibleFailure()
            assertNull(store.fetchContact(alice))
            assertEquals(savedForeign, store.fetchMessage(collided))
            button(L.commonOk).performClick()
            store.deleteMessage(collided)
            val orphan = ReactionDTO(
                messageID = reacted.id,
                emoji = "👍",
                senderName = "Restored orphan",
                messageHash = reacted.id.toString().uppercase(Locale.ROOT).take(8),
                rawText = "👍",
                receivedAt = Instant.ofEpochSecond(1_700_000_000),
                contactID = UUID.randomUUID(),
                radioId = radio,
            )
            store.saveReaction(orphan)
            button(O.deviceScanContinueDemo).performScrollTo().performClick()
            assertVisibleFailure()
            assertNull(store.fetchContact(alice))
            assertNull(store.fetchMessage(reacted))
            assertEquals(listOf(orphan), store.fetchReactions(reacted))
            button(L.commonOk).performClick()
            store.deleteReactionsForMessage(reacted)
            button(O.deviceScanContinueDemo).performScrollTo().performClick()
            harness.eventually("simulator retry after orphan repair", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            assertSeedFamilies()
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    private suspend fun importSimulatorContactFixture(
        fixture: Fixture, contact: ContactDTO, now: java.time.Instant,
    ): MessageDTO {
        val radio = MockDataProvider.simulatorRadioId
        assertEquals(radio, contact.radioId)
        assertTrue(store.fetchContacts(radio).isEmpty())
        val device = MockDataProvider.simulatorDevice(now).copy(isActive = false)
        store.saveDevice(device)
        val history = MessageDTO(
            radioId = radio,
            contactID = contact.id,
            text = "Restored simulator contact history",
            timestamp = 1_700_000_000u,
            createdAt = now,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
        )
        val backup = com.meshcoreone.android.core.data.backup.AppBackupEnvelope(
            exportDate = now,
            appVersion = "simulator-contact-regression",
            appBuild = "1",
            devices = SnapshotList.of(device),
            contacts = SnapshotList.of(contact),
            messages = SnapshotList.of(history),
        ).withActualManifest()
        com.meshcoreone.android.core.data.backup.AppBackupService(
            fixture.storage.backupPreferences, "simulator-contact-regression", "1",
        )
            .importBackup(backup, store)
        return assertNotNull(store.fetchMessage(EntityKey(radio, history.id)))
    }

    private fun mountAndConnectSimulator(fixture: Fixture) {
        host.get().setContent { MeshCoreTheme(motionScale = 0f) { OnboardingFlow(fixture.onboarding, onCompleted = {}) } }
        compose.waitForIdle()
        button(O.welcomeGetStarted).performClick()
        compose.onAllNodes(hasText(text(O.permissionsContinue)) and hasClickAction()).onLast().performClick()
        val title = compose.onNode(hasText(text(O.deviceScanTitle)) and
            SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        repeat(3) { title.performClick() }
        compose.waitUntil(30_000) {
            fixture.harness.settle()
            compose.onAllNodesWithText(text(O.deviceScanDemoModeAlertTitle)).fetchSemanticsNodes().isNotEmpty()
        }
        button(L.commonOk).performClick()
        button(O.deviceScanContinueDemo).performScrollTo().performClick()
        fixture.harness.eventually("mounted simulator ready", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
            fixture.harness.manager.connectionState == DeviceConnectionState.READY
        }
        compose.waitUntil(30_000) {
            fixture.harness.settle()
            compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(fixture.harness.manager.isSimulatorConnection)
    }

    private fun reconnectMountedSimulator(fixture: Fixture) {
        compose.runOnUiThread { host.get().onBackPressedDispatcher.onBackPressed() }
        compose.waitUntil(30_000) {
            fixture.harness.settle()
            compose.onAllNodesWithText(text(O.deviceScanContinueDemo)).fetchSemanticsNodes().isNotEmpty()
        }
        button(O.deviceScanContinueDemo).performScrollTo().performClick()
        fixture.harness.eventually("mounted simulator reconnected", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
            fixture.harness.manager.connectionState == DeviceConnectionState.READY
        }
        compose.waitUntil(30_000) {
            fixture.harness.settle()
            compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(fixture.harness.manager.isSimulatorConnection)
    }

    @Test fun roomSeedCancellationWinsBeforeFixturePlan() = runTest {
        val boundary = Instant.ofEpochSecond(100_000)
        val cancellation = kotlinx.coroutines.CancellationException("before simulator fixture preflight")
        val adapter = RoomSimulatorSeedStore(store)
        val cancelling = object : SimulatorSeedStore by adapter {
            override suspend fun seedConversations(
                buildPlan: () -> SimulatorDeferredFixturePlan,
                replayReference: suspend () -> Unit,
            ): SimulatorFixtureSeedResult {
                kotlinx.coroutines.currentCoroutineContext().cancel(cancellation)
                return adapter.seedConversations(buildPlan, replayReference)
            }
        }
        assertFailsWith<IllegalArgumentException> {
            MockDataProvider.messages(MockDataProvider.bobMartinezID, boundary)
        }
        var observed: Throwable? = null
        val attempt = launch {
            try {
                com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode(
                    clock = java.time.Clock.fixed(boundary, java.time.ZoneOffset.UTC),
                ).seedDataStore(cancelling)
            } catch (failure: kotlinx.coroutines.CancellationException) {
                observed = failure
            } catch (failure: IllegalArgumentException) {
                observed = failure
            }
        }
        attempt.join()
        assertTrue(attempt.isCancelled)
        val propagated = assertIs<kotlinx.coroutines.CancellationException>(observed)
        assertTrue(propagated === cancellation || propagated.cause === cancellation)
        assertNotNull(store.fetchDevice(MockDataProvider.simulatorDeviceID))
        assertTrue(store.fetchContacts(MockDataProvider.simulatorRadioId).isEmpty())
    }

    @Test fun roomSeedCancellationDuringFixtureFailureKeepsBothCauses() = runTest {
        val adapter = RoomSimulatorSeedStore(store)
        val fixtureFailures = listOf(
            IllegalArgumentException("fixture timestamp is invalid"),
            IllegalStateException("fixture identity is invalid"),
            ArithmeticException("fixture timestamp overflow"),
            java.time.DateTimeException("fixture instant overflow"),
        )
        for (fixtureFailure in fixtureFailures) {
            val cancellation = kotlinx.coroutines.CancellationException("during simulator fixture preflight")
            val activeFailure = assertFailsWith<RuntimeException> {
                adapter.seedConversations({ throw fixtureFailure }, {})
            }
            assertTrue(activeFailure === fixtureFailure)

            var observed: Throwable? = null
            val attempt = launch {
                val caller = kotlinx.coroutines.currentCoroutineContext()
                try {
                    adapter.seedConversations({
                        caller.cancel(cancellation)
                        throw fixtureFailure
                    }, {})
                } catch (failure: kotlinx.coroutines.CancellationException) {
                    observed = failure
                } catch (failure: RuntimeException) {
                    observed = failure
                }
            }
            attempt.join()
            assertTrue(attempt.isCancelled)
            val propagated = assertIs<kotlinx.coroutines.CancellationException>(observed)
            assertTrue(propagated === cancellation || propagated.cause === cancellation)
            assertTrue(propagated.suppressed.any { it === fixtureFailure })
            assertTrue(store.fetchContacts(MockDataProvider.simulatorRadioId).isEmpty())
        }
    }

    @Test fun roomDeferredFixturePreservesEarlierParentStoreFailure() = runTest {
        val boundary = Instant.ofEpochSecond(100_000)
        val valid = Instant.ofEpochSecond(1_700_000_000)
        val radio = MockDataProvider.simulatorRadioId
        val alice = EntityKey(radio, MockDataProvider.aliceChenID)
        val adapter = RoomSimulatorSeedStore(store)
        fun seedAt(time: Instant) = com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode(
            clock = java.time.Clock.fixed(time, java.time.ZoneOffset.UTC),
        )

        assertTrue(MockDataProvider.messages(alice.id, boundary).isNotEmpty())
        assertFailsWith<IllegalArgumentException> {
            MockDataProvider.messages(MockDataProvider.bobMartinezID, boundary)
        }
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_simulator_parent BEFORE INSERT ON contacts " +
                "BEGIN SELECT RAISE(ABORT, 'earlier simulator parent failure'); END",
        )
        try {
            val parentFailureAtBoundary = assertFailsWith<PersistenceStoreException> {
                seedAt(boundary).seedDataStore(adapter)
            }
            assertEquals(PersistenceStoreError.SaveFailed("seedSimulatorConversation"), parentFailureAtBoundary.error)
            assertTrue(parentFailureAtBoundary.cause?.message?.contains("earlier simulator parent failure") == true)
            assertNotNull(store.fetchDevice(MockDataProvider.simulatorDeviceID))
            assertTrue(store.fetchContacts(radio).isEmpty())
            assertNull(store.fetchContact(alice))

            val parentFailure = assertFailsWith<PersistenceStoreException> {
                seedAt(valid).seedDataStore(adapter)
            }
            assertEquals(PersistenceStoreError.SaveFailed("seedSimulatorConversation"), parentFailure.error)
            assertTrue(parentFailure.cause?.message?.contains("earlier simulator parent failure") == true)
            assertNull(store.fetchContact(alice))
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_simulator_parent")
        }
        seedAt(valid).seedDataStore(adapter)
        assertNotNull(store.fetchContact(alice))
        assertEquals(
            MockDataProvider.messages(alice.id, valid).map { it.id }.toSet(),
            store.fetchMessages(alice, limit = 500).map { it.id }.toSet(),
        )
    }

    @Test fun roomDeferredFixtureVisitsExistingPartialConversationBeforeLaterFailure() = runTest {
        val boundary = Instant.ofEpochSecond(100_000)
        val valid = Instant.ofEpochSecond(1_700_000_000)
        val radio = MockDataProvider.simulatorRadioId
        val aliceFixture = MockDataProvider.contacts(boundary).single { it.id == MockDataProvider.aliceChenID }
        val alice = EntityKey(radio, aliceFixture.id)
        store.saveContact(aliceFixture.copy(name = "Personalized Alice", lastModified = 1u))
        val message = MessageDTO(
            id = UUID.fromString("F0000000-0000-0000-0000-000000000001"),
            radioId = radio,
            contactID = alice.id,
            text = "Existing partial conversation",
            timestamp = 100_000u,
            createdAt = boundary,
            direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED,
        )
        store.saveMessage(message)
        val beforeContact = assertNotNull(store.fetchContact(alice))
        val beforeMessages = store.fetchMessages(alice, limit = 500)
        assertEquals(1u, beforeContact.lastModified)
        assertEquals(listOf(message.id), beforeMessages.map { it.id })
        val adapter = RoomSimulatorSeedStore(store)
        fun seedAt(time: Instant) = com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode(
            clock = java.time.Clock.fixed(time, java.time.ZoneOffset.UTC),
        )

        val laterFixtureFailure = assertFailsWith<IllegalArgumentException> {
            MockDataProvider.messages(MockDataProvider.bobMartinezID, boundary)
        }
        val preflightFailure = assertFailsWith<IllegalArgumentException> {
            seedAt(boundary).seedDataStore(adapter)
        }
        assertEquals(laterFixtureFailure.message, preflightFailure.message)
        assertEquals(beforeContact.copy(lastModified = boundary.epochSecond.toUInt()), store.fetchContact(alice))
        assertEquals(beforeMessages, store.fetchMessages(alice, limit = 500))

        seedAt(valid).seedDataStore(adapter)
        val afterContact = assertNotNull(store.fetchContact(alice))
        assertEquals(beforeContact.copy(lastModified = valid.epochSecond.toUInt()), afterContact)
        assertEquals(beforeMessages, store.fetchMessages(alice, limit = 500))
    }

    @Test fun existingConversationsSkipFixtureBuildersWithInvalidTimestamps() = runTest {
        val valid = Instant.ofEpochSecond(1_700_000_000)
        val boundary = Instant.ofEpochSecond(100_000)
        val radio = MockDataProvider.simulatorRadioId
        val bob = EntityKey(radio, MockDataProvider.bobMartinezID)
        val adapter = RoomSimulatorSeedStore(store)
        fun seedAt(time: Instant) = com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode(
            clock = java.time.Clock.fixed(time, java.time.ZoneOffset.UTC),
        )

        seedAt(valid).seedDataStore(adapter)
        val beforeContact = assertNotNull(store.fetchContact(bob))
        val beforeMessages = store.fetchMessages(bob, limit = 500)
        assertTrue(beforeMessages.isNotEmpty())
        assertFailsWith<IllegalArgumentException> { MockDataProvider.messages(bob.id, boundary) }

        seedAt(boundary).seedDataStore(adapter)
        assertEquals(beforeContact, store.fetchContact(bob))
        assertEquals(beforeMessages, store.fetchMessages(bob, limit = 500))
    }

    @Test fun roomFixtureChildFailureRollsBackAnAbsentParentAndRetries() = runTest {
        val now = Instant.ofEpochSecond(1_700_000_000)
        val radio = MockDataProvider.simulatorRadioId
        val alice = EntityKey(radio, MockDataProvider.aliceChenID)
        val adapter = RoomSimulatorSeedStore(store)
        val mode = com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode(
            clock = java.time.Clock.fixed(now, java.time.ZoneOffset.UTC),
        )
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_simulator_child BEFORE INSERT ON messages " +
                "WHEN EXISTS (SELECT 1 FROM contacts WHERE radioId = NEW.radioId AND id = NEW.contactID) " +
                "BEGIN SELECT RAISE(ABORT, 'simulator child failure'); END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> { mode.seedDataStore(adapter) }
            assertEquals(PersistenceStoreError.SaveFailed("seedSimulatorConversation"), failure.error)
            assertTrue(failure.cause?.message?.contains("simulator child failure") == true)
            assertNull(store.fetchContact(alice))
            assertTrue(store.fetchMessages(alice, limit = 500).isEmpty())
        } finally {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_simulator_child")
        }
        mode.seedDataStore(adapter)
        assertNotNull(store.fetchContact(alice))
        assertEquals(
            MockDataProvider.messages(alice.id, now).map { it.id }.toSet(),
            store.fetchMessages(alice, limit = 500).map { it.id }.toSet(),
        )
    }

    @Test fun aSeedFailureIsVisibleAndRetryUsesTheSameRealStore() = runTest {
        var fail = true
        val fixture = fixture(this) { delegate ->
            object : SimulatorSeedStore by delegate {
                override suspend fun seedConversations(
                    buildPlan: () -> SimulatorDeferredFixturePlan,
                    replayReference: suspend () -> Unit,
                ): SimulatorFixtureSeedResult {
                    if (fail) {
                        delegate.seedConversations(
                            {
                                object : SimulatorDeferredFixturePlan {
                                    override val families =
                                        buildPlan().families.takeWhile { it.parent is SimulatorFixtureParent.Direct }.snapshot()
                                }
                            },
                            replayReference,
                        )
                        throw IOException("seed write failed")
                    }
                    return delegate.seedConversations(buildPlan, replayReference)
                }
            }
        }
        try {
            assertIs<OnboardingConnectOutcome.Failed>(fixture.onboarding.demo.connectDemo())
            assertEquals(DeviceConnectionState.DISCONNECTED, fixture.harness.manager.connectionState)
            assertEquals(1, fixture.harness.lifecycle.created.size)
            assertEquals(1, fixture.harness.lifecycle.tornDown.size)
            val radio = MockDataProvider.simulatorRadioId
            val alice = EntityKey(radio, MockDataProvider.aliceChenID)
            val committedMessages = store.fetchMessages(alice, limit = 500).toSet()
            assertNotNull(store.fetchContact(alice))
            assertTrue(committedMessages.isNotEmpty())
            assertTrue(store.fetchChannels(radio).isEmpty())
            fail = false
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            assertEquals(DeviceConnectionState.READY, fixture.harness.manager.connectionState)
            assertEquals(committedMessages, store.fetchMessages(alice, limit = 500).toSet())
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
                override suspend fun seedConversations(
                    buildPlan: () -> SimulatorDeferredFixturePlan,
                    replayReference: suspend () -> Unit,
                ): SimulatorFixtureSeedResult {
                    if (block) {
                        delegate.seedConversations(
                            {
                                object : SimulatorDeferredFixturePlan {
                                    override val families =
                                        buildPlan().families.takeWhile { it.parent is SimulatorFixtureParent.Direct }.snapshot()
                                }
                            },
                            replayReference,
                        )
                        entered.complete(Unit)
                        release.await()
                    }
                    return delegate.seedConversations(buildPlan, replayReference)
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
            val radio = MockDataProvider.simulatorRadioId
            val alice = EntityKey(radio, MockDataProvider.aliceChenID)
            val committedMessages = store.fetchMessages(alice, limit = 500).toSet()
            assertNotNull(store.fetchContact(alice))
            assertTrue(committedMessages.isNotEmpty())
            assertTrue(store.fetchChannels(radio).isEmpty())
            block = false
            release.complete(Unit)
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            assertEquals(committedMessages, store.fetchMessages(alice, limit = 500).toSet())
            assertSeedFamilies()
            assertTrue(fixture.harness.links.radios.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun savedDeviceReconnectUsesSimulatorButPhysicalIdsStillUseBluetooth() = runTest {
        val fixture = fixture(this)
        try {
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            val harness = fixture.harness
            val port = harness.container.connectionPort
            port.refreshLastConnection()
            val saved = assertNotNull(port.lastConnectedDeviceId)
            assertEquals(MockDataProvider.simulatorDeviceID, saved)

            for (forceReconnect in listOf(false, true)) {
                port.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                port.connect(saved, forceReconnect)
                assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
                assertTrue(harness.manager.isSimulatorConnection)
                assertEquals(saved, harness.manager.connectedDevice?.id)
                assertTrue(harness.links.radios.isEmpty())
            }

            val physical = harness.target()
            port.connect(physical.deviceId, forceReconnect = false)
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            assertFalse(harness.manager.isSimulatorConnection)
            assertEquals(physical.deviceId, harness.manager.connectedDevice?.id)
            assertEquals(TransportType.BLUETOOTH, harness.links.links.single().type)
        } finally {
            fixture.close()
        }
    }

    @Test fun removingAPhysicalAssociationDemotesItWithoutDisconnectingTheSimulator() = runTest {
        val fixture = fixture(this)
        try {
            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            val harness = fixture.harness
            val physicalId = UUID.randomUUID()
            val physical = DeviceDTO(
                id = physicalId,
                radioId = RadioId(UUID.randomUUID()),
                publicKey = Bytes(ByteArray(32) { 0x41 }),
                nodeName = "Physical radio",
                connectionMethods = SnapshotList(listOf(ConnectionMethod.Bluetooth(physicalId))),
            )
            store.saveDevice(physical)
            val completed = CompletableDeferred<Throwable?>()
            val devices = object : DevicePersisting by store {
                override suspend fun demoteDeviceToGhost(id: UUID) {
                    store.demoteDeviceToGhost(id)
                    completed.complete(null)
                }
            }
            val pairing = object : DevicePairingService by harness.pairing {
                override var delegate: DevicePairingDelegate? = null
                override fun isDeviceConnectable(id: UUID) = id != physicalId
            }
            PairingCoordinator(
                RuntimePairingPort(harness.manager, RuntimePlatformAdapter(newConnectivity(harness.pairing))),
                pairing, devices, NoSystemLinks, {}, SystemConnectivityClock(), backgroundScope,
                ConnectivityDiagnostics { _, failure -> if (failure != null) completed.complete(failure) },
            )
            assertNotNull(pairing.delegate).devicePairingDidRemoveDevice(pairing, physicalId)
            harness.eventually("physical association removal") { completed.isCompleted }
            assertNull(completed.await(), "association removal must reach ghost demotion")
            assertNull(store.fetchDevice(physicalId))
            val ghost = assertNotNull(store.fetchDevice(physical.radioId))
            assertNotEquals(physicalId, ghost.id)
            assertFalse(ghost.isActive)
            assertTrue(ghost.connectionMethods.isEmpty())
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            assertTrue(harness.manager.isSimulatorConnection)
            assertEquals(MockDataProvider.simulatorDeviceID, store.fetchActiveDevice()?.id)
            harness.container.connectionPort.refreshLastConnection()
            assertEquals(MockDataProvider.simulatorDeviceID, harness.container.connectionPort.lastConnectedDeviceId)
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

    @Test fun bundledImageOverridesAnOrdinaryMessagesHtmlReroute() = runTest {
        val fixture = fixture(this, ordinaryImageHtml = true)
        try {
            val url = MockDataProvider.inlineImageURL
            val ordinary = MessageDTO(radioId = RadioId(UUID.randomUUID()), text = url, timestamp = 1u)
            assertTrue(fixture.content.media(ordinary).single().isImage)
            assertIs<ChatImage.HtmlPage>(fixture.content.inline(url))
            assertFalse(fixture.content.media(ordinary).single().isImage)
            assertEquals(listOf(url), fixture.httpRequests)

            assertEquals(OnboardingConnectOutcome.Connected, fixture.onboarding.demo.connectDemo())
            val bundled = message("10000000-0000-0000-0000-00000000000B")
            val rendered = fixture.content.renderContent(bundled, EnvInputs.DEFAULT)
            assertTrue(rendered.inline)
            assertTrue(rendered.hasInlineRef)
            assertTrue(fixture.content.media(bundled).single().isImage)
            assertFalse(fixture.content.media(ordinary).single().isImage)

            val reports = mutableListOf<String>()
            host.get().setContent {
                MeshCoreTheme(motionScale = 0f) {
                    AppMessageContent(fixture.content, bundled, { detail, _ -> reports += detail }, {})
                }
            }
            compose.waitUntil(30_000) {
                fixture.harness.settle()
                compose.onAllNodesWithContentDescription(url).fetchSemanticsNodes().isNotEmpty()
            }
            assertTrue(reports.isEmpty(), reports.toString())
            assertEquals(listOf(url), fixture.httpRequests, "the simulator must not request the ordinary URL")
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

    @Test fun mountedAmbiguousSimulatorSeedShowsFailureAndRetriesAfterRepair() = runTest {
        val fixture = fixture(this)
        try {
            val harness = fixture.harness
            val radio = MockDataProvider.simulatorRadioId
            val index = MockDataProvider.publicChannelIndex
            com.meshcoreone.android.core.services.simulator.SimulatorConnectionMode()
                .seedDataStore(RoomSimulatorSeedStore(store))
            val original = db.channels().forIndex(radio.value, index.toLong()).single()
            assertEquals(MockDataProvider.publicChannelID, original.id)
            val savedMessage = MessageDTO(
                radioId = radio,
                channelIndex = index,
                text = "Existing user message",
                timestamp = 1_700_000_000u,
                createdAt = java.time.Instant.ofEpochSecond(1_700_000_000),
                direction = MessageDirection.INCOMING,
                status = com.meshcoreone.android.core.model.MessageStatus.DELIVERED,
            )
            store.saveMessage(savedMessage)
            val duplicate = original.copy(id = UUID.randomUUID(), name = "Ambiguous legacy slot")
            val otherRadio = RadioId(UUID.randomUUID())
            val foreign = original.copy(radioId = otherRadio.value, name = "Unrelated radio")
            db.channels().upsert(duplicate)
            db.channels().upsert(foreign)
            val before = store.fetchChannels(radio).associateBy { it.id }
            val messages = store.fetchMessages(radio, index, limit = 500)
            assertEquals(2, before.values.count { it.index == index })
            assertTrue(messages.any { it.id == savedMessage.id && it.text == savedMessage.text })

            host.get().setContent { MeshCoreTheme(motionScale = 0f) { OnboardingFlow(fixture.onboarding, {}) } }
            button(O.welcomeGetStarted).performClick()
            compose.onAllNodes(hasText(text(O.permissionsContinue)) and hasClickAction()).onLast().performClick()
            val title = compose.onNode(hasText(text(O.deviceScanTitle)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            repeat(3) { title.performClick() }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(text(O.deviceScanDemoModeAlertTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            button(L.commonOk).performClick()
            button(O.deviceScanContinueDemo).performScrollTo().performClick()
            val invalidData = com.meshcoreone.android.core.ui.UiErrorMapper().persistenceError(
                com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.InvalidData,
            ).resolve(host.get().resources)
            harness.eventually("ambiguous simulator seed failure", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                synchronized(harness.diagnostics) {
                    harness.diagnostics.any {
                        it is com.meshcoreone.android.core.runtime.RuntimeDiagnostic.Failure &&
                            it.operation == "connect.attempt.1" &&
                            (it.cause as? com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)?.error ==
                            com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.InvalidData
                    }
                }
            }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(invalidData).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(invalidData)).assertIsDisplayed()
            assertTrue(synchronized(harness.diagnostics) {
                harness.diagnostics.any {
                    it is com.meshcoreone.android.core.runtime.RuntimeDiagnostic.Failure &&
                        it.operation == "connect.attempt.1" &&
                        (it.cause as? com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)?.error ==
                        com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.InvalidData
                }
            })
            assertEquals(DeviceConnectionState.DISCONNECTED, harness.manager.connectionState)
            assertTrue(compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isEmpty())
            assertEquals(before, store.fetchChannels(radio).associateBy { it.id })
            assertEquals(messages, store.fetchMessages(radio, index, limit = 500))
            assertEquals(foreign, db.channels().byId(otherRadio.value, foreign.id))

            button(L.commonOk).performClick()
            // Fixture-only repair; the production path must not delete either ambiguous row.
            assertEquals(1, db.channels().delete(radio.value, duplicate.id))
            button(O.deviceScanContinueDemo).performScrollTo().performClick()
            harness.eventually("repaired ambiguous simulator seed", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            assertTrue(harness.manager.isSimulatorConnection)
            assertEquals(original.id, store.fetchChannels(radio).single { it.index == index }.id)
            assertEquals(messages.associate { it.id to it.text },
                store.fetchMessages(radio, index, limit = 500).associate { it.id to it.text })
            assertEquals(savedMessage.text, assertNotNull(store.fetchMessage(EntityKey(radio, savedMessage.id))).text)
            assertEquals(foreign, db.channels().byId(otherRadio.value, foreign.id))
            assertSeedFamilies()
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    @Test fun restoredChannelWithDifferentSecretFailsAndRetriesWithoutDataLoss() = runTest {
        assertRestoredSimulatorChannelConflict(this, secretMismatch = true)
    }

    @Test fun restoredDisabledChannelFailsAndRetriesWithoutDataLoss() = runTest {
        assertRestoredSimulatorChannelConflict(this, secretMismatch = false)
    }

    private suspend fun assertRestoredSimulatorChannelConflict(test: TestScope, secretMismatch: Boolean) {
        val fixture = fixture(test)
        try {
            val harness = fixture.harness
            val radio = MockDataProvider.simulatorRadioId
            val now = java.time.Instant.ofEpochSecond(1_700_000_000)
            val seed = MockDataProvider.restoredSimulatorChannels(now)
                .single { it.index == MockDataProvider.publicChannelIndex }
            val differentSecret = Bytes(ByteArray(16) { (it + 0x31).toByte() })
            assertNotEquals(seed.secret, differentSecret)
            val importedChannel = seed.copy(
                name = "Restored channel preferences",
                secret = if (secretMismatch) differentSecret else seed.secret,
                isEnabled = if (secretMismatch) seed.isEnabled else !seed.isEnabled,
                lastMessageDate = now.plusSeconds(60),
                unreadCount = 17,
                unreadMentionCount = 3,
                notificationLevel = NotificationLevel.MENTIONS_ONLY,
                isFavorite = true,
            ).withFloodScope(com.meshcoreone.android.core.model.ChannelFloodScope.Region("US915"))
            val history = MessageDTO(
                radioId = radio,
                channelIndex = seed.index,
                text = "Restored user history",
                timestamp = 1_700_000_000u,
                createdAt = now,
                direction = MessageDirection.INCOMING,
                status = com.meshcoreone.android.core.model.MessageStatus.DELIVERED,
            )
            assertTrue(store.fetchDevices().isEmpty())
            assertTrue(store.fetchChannels(radio).isEmpty())
            val backup = com.meshcoreone.android.core.data.backup.AppBackupEnvelope(
                exportDate = now,
                appVersion = "simulator-conflict-regression",
                appBuild = "1",
                devices = SnapshotList.of(MockDataProvider.simulatorDevice(now)),
                channels = SnapshotList.of(importedChannel),
                messages = SnapshotList.of(history),
            ).withActualManifest()
            com.meshcoreone.android.core.data.backup.AppBackupService(
                fixture.storage.backupPreferences, "simulator-conflict-regression", "1",
            ).importBackup(backup, store)
            val restored = store.fetchChannels(radio).single()
            assertNotEquals(seed.id, restored.id)
            assertEquals(importedChannel.copy(id = restored.id), restored)
            assertNull(store.fetchChannel(EntityKey(radio, seed.id)))
            if (secretMismatch) {
                assertNotEquals(seed.secret, restored.secret)
                assertEquals(seed.isEnabled, restored.isEnabled)
            } else {
                assertEquals(seed.secret, restored.secret)
                assertNotEquals(seed.isEnabled, restored.isEnabled)
            }
            val originalHistory = assertNotNull(store.fetchMessage(EntityKey(radio, history.id)))
            val foreign = seed.copy(radioId = RadioId(UUID.randomUUID()), name = "Other radio")
            store.saveChannel(foreign)

            host.get().setContent { MeshCoreTheme(motionScale = 0f) { OnboardingFlow(fixture.onboarding, {}) } }
            button(O.welcomeGetStarted).performClick()
            compose.onAllNodes(hasText(text(O.permissionsContinue)) and hasClickAction()).onLast().performClick()
            val title = compose.onNode(hasText(text(O.deviceScanTitle)) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            repeat(3) { title.performClick() }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(text(O.deviceScanDemoModeAlertTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            button(L.commonOk).performClick()
            button(O.deviceScanContinueDemo).performScrollTo().performClick()
            val invalidData = com.meshcoreone.android.core.ui.UiErrorMapper().persistenceError(
                com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.InvalidData,
            ).resolve(host.get().resources)
            harness.eventually("restored simulator conflict failure", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                synchronized(harness.diagnostics) {
                    harness.diagnostics.any {
                        it is com.meshcoreone.android.core.runtime.RuntimeDiagnostic.Failure &&
                            it.operation == "connect.attempt.1" &&
                            (it.cause as? com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)?.error ==
                            com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.InvalidData
                    }
                }
            }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(invalidData).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNode(hasText(invalidData)).assertIsDisplayed()
            assertTrue(synchronized(harness.diagnostics) {
                harness.diagnostics.any {
                    it is com.meshcoreone.android.core.runtime.RuntimeDiagnostic.Failure &&
                        it.operation == "connect.attempt.1" &&
                        (it.cause as? com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)?.error ==
                        com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.InvalidData
                }
            })
            assertEquals(DeviceConnectionState.DISCONNECTED, harness.manager.connectionState)
            assertTrue(compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isEmpty())
            assertEquals(listOf(restored), store.fetchChannels(radio).toList())
            assertNull(store.fetchChannel(EntityKey(radio, seed.id)))
            assertEquals(originalHistory, store.fetchMessage(EntityKey(radio, history.id)))
            assertEquals(foreign, store.fetchChannel(EntityKey(foreign.radioId, foreign.id)))
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())

            button(L.commonOk).performClick()
            // Fixture-only repair; the failed seed must not rewrite the restored channel.
            val row = assertNotNull(db.channels().byId(radio.value, restored.id))
            db.channels().upsert(row.copy(secret = seed.secret, isEnabled = seed.isEnabled))
            val repaired = restored.copy(secret = seed.secret, isEnabled = seed.isEnabled)
            assertEquals(repaired, store.fetchChannel(EntityKey(radio, restored.id)))
            button(O.deviceScanContinueDemo).performScrollTo().performClick()
            harness.eventually("repaired restored simulator conflict", virtualBudgetMillis = 2_000, virtualStepMillis = 5) {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            compose.waitUntil(30_000) {
                harness.settle()
                compose.onAllNodesWithText(text(O.regionTitle)).fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(DeviceConnectionState.READY, harness.manager.connectionState)
            assertTrue(harness.manager.isSimulatorConnection)
            assertEquals(repaired, store.fetchChannel(EntityKey(radio, restored.id)))
            assertNull(store.fetchChannel(EntityKey(radio, seed.id)))
            assertEquals(originalHistory, store.fetchMessage(EntityKey(radio, history.id)))
            assertEquals(foreign, store.fetchChannel(EntityKey(foreign.radioId, foreign.id)))
            assertSeedFamilies(missingChannelFixtures = setOf(seed.index))
            assertTrue(harness.links.radios.isEmpty())
            assertTrue(fixture.httpRequests.isEmpty())
        } finally {
            fixture.close()
        }
    }

    private suspend fun assertSeedFamilies(
        expectedChannelCount: Int = 4,
        missingAliceFixtures: Boolean = false,
        missingChannelFixtures: Set<UByte> = emptySet(),
    ) {
        val radio = MockDataProvider.simulatorRadioId
        val contacts = store.fetchContacts(radio)
        assertEquals(11, contacts.size)
        val repeaterPrefixes = contacts.filter { it.type == ContactType.REPEATER && it.hasLocation }
            .map { it.publicKey.prefix(1) }.toSet()
        assertTrue(setOf(Bytes.of(0x91), Bytes.of(0x92), Bytes.of(0x93)).all { it in repeaterPrefixes })
        val channels = store.fetchChannels(radio)
        assertEquals(expectedChannelCount, channels.size)
        assertEquals(NotificationLevel.MUTED, channels.single { it.index == 2.toUByte() }.notificationLevel)
        assertTrue(channels.single { it.index == 1.toUByte() }.isFavorite)

        val fixtureTime = Instant.ofEpochSecond(1_700_000_000)
        if (missingAliceFixtures) {
            for (fixture in MockDataProvider.messages(MockDataProvider.aliceChenID, fixtureTime)) {
                val fixtureKey = EntityKey(radio, fixture.id)
                assertNull(store.fetchMessage(fixtureKey))
                assertTrue(store.fetchReactions(fixtureKey).isEmpty())
                assertTrue(store.fetchMessageRepeats(fixtureKey).isEmpty())
            }
        } else {
            val preview = message("10000000-0000-0000-0000-00000000000A")
            assertEquals("Skyline Ridge Trail Guide", preview.linkPreviewTitle)
            assertTrue(preview.linkPreviewFetched)
            assertEquals(MockDataProvider.demoImageData, preview.linkPreviewImageData)
            val dmReaction = message("10000000-0000-0000-0000-000000000002")
            assertEquals("👍:2,❤️:1", dmReaction.reactionSummary)
            assertEquals(3, store.fetchReactions(EntityKey(radio, dmReaction.id)).size)
        }
        for (index in missingChannelFixtures) {
            for (fixture in MockDataProvider.channelMessages(index, fixtureTime)) {
                val fixtureKey = EntityKey(radio, fixture.id)
                assertNull(store.fetchMessage(fixtureKey))
                assertTrue(store.fetchReactions(fixtureKey).isEmpty())
                assertTrue(store.fetchMessageRepeats(fixtureKey).isEmpty())
            }
        }
        if (MockDataProvider.bayAreaChannelIndex !in missingChannelFixtures) {
            val channelReaction = message("C1000000-0000-0000-0000-000000000002")
            assertEquals("🎉:2", channelReaction.reactionSummary)
            assertEquals(2, store.fetchReactions(EntityKey(radio, channelReaction.id)).size)
        }
        assertEquals(3, store.fetchMessageRepeats(key("60000000-0000-0000-0000-000000000002")).size)

        val multipathIDs = buildList {
            if (!missingAliceFixtures) add("10000000-0000-0000-0000-000000000010")
            if (MockDataProvider.publicChannelIndex !in missingChannelFixtures) {
                add("C0000000-0000-0000-0000-000000000006")
            }
        }
        for (id in multipathIDs) {
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
        val ambiguousIDs = buildList {
            add("60000000-0000-0000-0000-000000000005")
            if (MockDataProvider.publicChannelIndex !in missingChannelFixtures) {
                add("C0000000-0000-0000-0000-000000000005")
            }
        }
        for (id in ambiguousIDs) {
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
        ordinaryImageHtml: Boolean = false,
        stalePreferences: com.meshcoreone.android.app.state.StaleCleanupPreferences? = null,
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
                if (ordinaryImageHtml && requests.size == 1 && url == MockDataProvider.inlineImageURL) {
                    return HttpFetchAttempt.Started(200, "text/html", 0, {}) { }
                }
                throw AssertionError("Simulator initiated an HTTP request: $url")
            }
        }
        val content = AppChatContent(
            context, store, http, DataStoreLinkPreviewPreferencesSource.create(storage.preferences, scope),
            storage.preferences, storage.preferences.snapshot(), scope, mapPreviews, imageSafety = { true },
        )
        val harness = ContainerHarness(
            test, store, chatContent = content, stalePreferences = stalePreferences,
            simulatorSeedStore = wrapSeed(RoomSimulatorSeedStore(store)),
        )
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
