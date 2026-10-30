// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/RxLogServiceRegionReprocessTests.swift@db14559b39d32322b06477c6ae676112f583db50
// WP-212: Real settings/discovery edits, session-owned RX reprocessing and Room message updates.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.navigation.AppSettingsExternal
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceAccess
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.device.RadioPresetMatcher
import com.meshcoreone.android.core.contracts.domain.ConnectedDeviceEdit
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as L
import com.meshcoreone.android.core.l10n.generated.AppSettingsStrings as S
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.feature.settings.device.SettingsExternalDestination
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@Config(sdk = [31], qualifiers = "en-rUS-w500dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RxRegionConsumerTest : RoomProcessTest() {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    @OriginalCase("RxLogServiceRegionReprocessTests::regionUpdateEvents yields message IDs after channel message reprocess()", "native-equivalent")
    @OriginalCase("RxLogServiceRegionReprocessTests::empty known regions clears sticky labels()", "native-equivalent")
    fun mountedRegionManagementAddAndDeleteReprocessTheLiveRxAndMessageExactlyOnceEach() = runTest {
        var nextIdentity = 7
        val f = RxIngestionFixture(this, store, identity = { key(nextIdentity++) })
        val storage = MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>())
        val host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        try {
            f.connect()
            val stamp = 1_704_111_000u
            val row = f.ingest(f.packet(f.encrypted(stamp, "NodeAlpha: region"), region = "Germany"))
            assertNull(row.regionScope)
            f.poll(stamp, "NodeAlpha: region", 0)
            val message = store.fetchMessages(f.radio, 0u).single()
            assertNull(message.regionScope)
            val events = CopyOnWriteArrayList<List<UUID>>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.rxLogService.regionUpdateEvents().collect { events += it.toList() }
            }
            try {
                val feature = AppSettingsFeature.create(f.harness.container, storage)
                host.get().setContent {
                    MaterialTheme {
                        AppSettingsExternal(feature, SettingsExternalDestination.RegionManagement) {}
                    }
                }
                compose.waitForIdle()
                compose.onNodeWithText(host.get().getString(S.radioPresetLocation)).performTextInput("Germany")
                compose.onNodeWithText(host.get().getString(L.commonSave)).performClick()
                compose.waitUntil(5_000) {
                    f.harness.scheduler.runCurrent()
                    events.size == 1
                }
                assertEquals(listOf(listOf(message.id)), events.toList())
                store.flushPendingRxLogEntries()
                assertEquals(listOf("Germany"), assertNotNull(store.fetchDevice(f.radio)).knownRegions)
                val updated = store.fetchRxLogEntries(f.radio).single { it.id == row.id }
                assertEquals("Germany", updated.regionScope)
                assertEquals(listOf("Germany"), updated.regionScopeMatches)
                val saved = assertNotNull(store.fetchMessage(com.meshcoreone.android.core.contracts.domain.EntityKey(f.radio, message.id)))
                assertEquals("Germany", saved.regionScope)
                assertEquals(listOf("Germany"), saved.regionScopeMatches)
                assertEquals("Germany", f.harness.manager.connectedDevice?.knownRegions?.single())

                compose.waitForIdle()
                compose.onNodeWithText(host.get().getString(L.commonDelete)).performClick()
                compose.waitUntil(5_000) {
                    f.harness.scheduler.runCurrent()
                    events.size == 2
                }
                assertEquals(listOf(listOf(message.id), listOf(message.id)), events.toList())
                assertTrue(assertNotNull(store.fetchDevice(f.radio)).knownRegions.isEmpty())
                val cleared = store.fetchRxLogEntries(f.radio).single { it.id == row.id }
                assertNull(cleared.regionScope)
                assertTrue(cleared.regionScopeMatches.isEmpty())
                assertNull(assertNotNull(store.fetchMessage(com.meshcoreone.android.core.contracts.domain.EntityKey(f.radio, message.id))).regionScope)
                assertNull(feature.failure.value)

                assertNotNull(f.harness.container.deviceEditor.addKnownRegion("France")).join()
                compose.waitForIdle()
                compose.onNodeWithText("France").assertExists()
                assertNotNull(f.harness.container.deviceEditor.removeKnownRegion("France")).join()
                compose.waitForIdle()
                compose.onNodeWithText("France").assertDoesNotExist()
                assertEquals(2, events.size, "unrelated discovery edits do not rewrite the Germany message")

                assertNotNull(f.harness.container.deviceEditor.addKnownRegion("France")).join()
                compose.waitForIdle()
                compose.onNodeWithText("France").assertExists()
                val oldRadio = f.radio
                f.harness.manager.connect(f.harness.target())
                f.harness.eventually("new radio and handlers ready") {
                    f.harness.sessions.current?.let {
                        it.token.radioId != oldRadio && it.messagePollingService.hasMessageHandlersWired &&
                            f.harness.appState.canRunSettingsStartupReads
                    } == true
                }
                f.live = assertNotNull(f.harness.sessions.current)
                compose.waitForIdle()
                compose.onNodeWithText("France").assertDoesNotExist()
                assertEquals(listOf("France"), assertNotNull(store.fetchDevice(oldRadio)).knownRegions)
                assertTrue(assertNotNull(store.fetchDevice(f.radio)).knownRegions.isEmpty())
            } finally { observer.cancelAndJoin() }
        } finally {
            host.pause().stop().destroy()
            f.harness.container.close()
            storage.close()
        }
    }

    @Test
    @OriginalCase("RxLogServiceRegionReprocessTests::regionUpdateEvents yields message IDs after DM message reprocess()", "native-equivalent")
    @OriginalCase("RxLogServiceRegionReprocessTests::live resolve persists unique name and matches array together()", "native-equivalent")
    fun editorDiscoveryEditsNotifyTheCurrentDmCacheOnceAndNewPacketsUseIt() = runTest {
        val f = RxIngestionFixture(this, store)
        try {
            f.connect()
            val stamp = 1_704_222_000u
            val payload = Bytes.of(0, 0xAB, 0xCC, 0xDD, 0xEE)
            val packet = f.packet(payload, region = "Germany")
            val parsed = assertNotNull(com.meshcoreone.android.core.protocol.parser.RxLogParser.parse(5.0, -80, packet))
            val entry = RxLogEntryDTO.fromParsed(
                f.radio, parsed.copy(payloadType = PayloadType.TEXT_MESSAGE),
                decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = stamp,
            )
            store.saveRxLogEntry(entry)
            val message = MessageDTO(
                radioId = f.radio, contactID = UUID.randomUUID(), timestamp = stamp, text = "region DM",
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
                senderKeyPrefix = Bytes.of(0xAB, 0x11, 0x22, 0x33),
            )
            store.saveMessage(message)
            val events = CopyOnWriteArrayList<List<UUID>>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.rxLogService.regionUpdateEvents().collect { events += it.toList() }
            }
            try {
                assertNotNull(f.harness.container.deviceEditor.addKnownRegion("Germany")).join()
                f.harness.eventually("editor triggers DM region event") { events.size == 1 }
                assertNull(f.harness.container.deviceEditor.addKnownRegion("Germany"))
                f.harness.settle()
                assertEquals(listOf(listOf(message.id)), events.toList())
                assertEquals("Germany", assertNotNull(store.fetchMessage(
                    com.meshcoreone.android.core.contracts.domain.EntityKey(f.radio, message.id),
                )).regionScope)
                val live = f.ingest(f.packet(Bytes.of(1, 2, 3, 4), region = "Germany"))
                assertEquals("Germany", live.regionScope)
                assertEquals(listOf("Germany"), live.regionScopeMatches)
                assertNotNull(f.harness.container.deviceEditor.removeKnownRegion("Germany")).join()
                f.harness.eventually("empty editor cache clears DM") { events.size == 2 }
                assertEquals(listOf(listOf(message.id), listOf(message.id)), events.toList())
                assertTrue(assertNotNull(store.fetchDevice(f.radio)).knownRegions.isEmpty())
                val cleared = store.fetchRxLogEntries(f.radio).single { it.id == entry.id }
                assertNull(cleared.regionScope)
                assertTrue(cleared.regionScopeMatches.isEmpty())
                assertNull(f.ingest(f.packet(Bytes.of(5, 6, 7, 8), region = "Germany")).regionScope)
            } finally { observer.cancelAndJoin() }
        } finally { f.harness.container.close() }
    }

    @Test fun delayedOldPersistenceCannotRouteItsRegionsIntoAReplacementOfTheSameRadio() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun addDeviceKnownRegion(radioId: RadioId, region: String) {
                entered.complete(Unit)
                release.await()
                store.addDeviceKnownRegion(radioId, region)
            }

        }
        val f = RxIngestionFixture(this, store, held)
        try {
            f.connect()
            val old = f.live
            val id = assertNotNull(f.harness.manager.connectedDevice).id
            val edit = assertNotNull(f.harness.container.deviceEditor.addKnownRegion("Germany"))
            f.harness.eventually("old region persistence suspended") { entered.isCompleted }
            f.harness.manager.disconnect()
            f.harness.manager.connect(f.harness.target(id))
            f.harness.eventually("replacement handlers ready") {
                f.harness.sessions.current?.let { it !== old && it.messagePollingService.hasMessageHandlersWired } == true
            }
            val replacement = assertNotNull(f.harness.sessions.current)
            assertEquals(old.token.radioId, replacement.token.radioId)
            assertNotEquals(old.token, replacement.token)
            assertTrue(old.isTornDown)
            f.live = replacement
            release.complete(Unit)
            edit.join()
            assertEquals(listOf("Germany"), assertNotNull(store.fetchDevice(f.radio)).knownRegions)
            assertTrue(assertNotNull(f.harness.manager.connectedDevice).knownRegions.isEmpty())
            val packet = f.packet(Bytes.of(1, 2, 3, 4), region = "Germany")
            assertNull(f.ingest(packet).regionScope, "old edit must not retarget replacement service's cache")
        } finally {
            release.complete(Unit)
            f.harness.container.close()
        }
    }

    @Test fun reconnectBetweenAcceptedEditAndRegistryLookupRejectsTheOriginalGeneration() = runTest {
        val f = RxIngestionFixture(this, store)
        val accepted = CompletableDeferred<SessionToken>()
        val resumeCallback = CountDownLatch(1)
        try {
            f.connect()
            val old = f.live
            val id = assertNotNull(f.harness.manager.connectedDevice).id
            val access = object : ConnectedDeviceAccess by f.harness.container.deviceAccess {
                override fun replaceConnectedDevice(device: DeviceDTO): ConnectedDeviceEdit? {
                    val edit = f.harness.container.deviceAccess.replaceConnectedDevice(device)
                    accepted.complete(assertNotNull(edit).token)
                    check(resumeCallback.await(10, TimeUnit.SECONDS)) { "Accepted-edit callback was not released" }
                    return edit
                }
            }
            val editor = ConnectedDeviceEditor(
                access, store, store, { null }, RadioPresetMatcher { _, _, _, _ -> emptySet() },
                backgroundScope, { EPOCH }, onKnownRegionsChanged = f.harness.container::knownRegionsChanged,
            )
            val edit = async(Dispatchers.Default) { editor.addKnownRegion("Germany") }
            f.harness.eventually("DTO accepted before callback lookup") { accepted.isCompleted }
            assertEquals(old.token, accepted.await())
            f.harness.manager.disconnect()
            f.harness.manager.connect(f.harness.target(id))
            f.harness.eventually("same-radio replacement handlers ready") {
                f.harness.sessions.current?.let {
                    it !== old && it.messagePollingService.hasMessageHandlersWired
                } == true
            }
            f.live = assertNotNull(f.harness.sessions.current)
            assertEquals(old.token.radioId, f.radio)
            assertNotEquals(old.token, f.live.token)
            // A live packet also establishes that the replacement's initial cache load has finished.
            f.ingest(f.packet(Bytes.of(1, 2, 3, 4)))
            resumeCallback.countDown()
            assertNotNull(edit.await()).join()
            assertEquals(listOf("Germany"), assertNotNull(store.fetchDevice(f.radio)).knownRegions)
            assertTrue(assertNotNull(f.harness.manager.connectedDevice).knownRegions.isEmpty())
            assertNull(f.ingest(f.packet(Bytes.of(5, 6, 7, 8), region = "Germany")).regionScope)
        } finally {
            resumeCallback.countDown()
            f.harness.container.close()
        }
    }

    @Test fun laterSameSessionEditWinsWhenItsNotificationOvertakesAnEarlierEdit() = runTest {
        val f = RxIngestionFixture(this, store)
        val firstCallback = CompletableDeferred<ConnectedDeviceEdit>()
        val secondCallback = CompletableDeferred<ConnectedDeviceEdit>()
        val releaseFirst = CountDownLatch(1)
        var observer: Job? = null
        try {
            f.connect()
            val stamp = 1_704_444_000u
            val text = "NodeAlpha: overlapping regions"
            val retained = f.ingest(f.packet(f.encrypted(stamp, text), region = "France"))
            assertNull(retained.regionScope)
            f.poll(stamp, text)
            val message = store.fetchMessages(f.radio, 0u).single()
            val events = CopyOnWriteArrayList<List<UUID>>()
            observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                f.live.rxLogService.regionUpdateEvents().collect { events += it.toList() }
            }
            val original = assertNotNull(f.harness.manager.connectedDevice)
            val editor = ConnectedDeviceEditor(
                f.harness.container.deviceAccess, store, store, { null },
                RadioPresetMatcher { _, _, _, _ -> emptySet() }, backgroundScope, { EPOCH },
                onKnownRegionsChanged = { edit, regions ->
                    if (regions == listOf("Germany")) {
                        firstCallback.complete(edit)
                        check(releaseFirst.await(30, TimeUnit.SECONDS)) { "First callback was not released" }
                    } else if (regions == listOf("France")) {
                        secondCallback.complete(edit)
                    }
                    f.harness.container.knownRegionsChanged(edit, regions)
                },
            )
            val first = async(Dispatchers.Default) {
                editor.updateDevice(original.copy(knownRegions = SnapshotList.of("Germany")))
            }
            f.harness.eventually("first accepted edit paused before its RX callback") { firstCallback.isCompleted }
            val second = async(Dispatchers.Default) {
                editor.updateDevice(original.copy(knownRegions = SnapshotList.of("France")))
            }
            second.await()
            val earlier = firstCallback.await()
            val later = secondCallback.await()
            assertEquals(earlier.token, later.token)
            assertTrue(later.sequence > earlier.sequence)
            assertEquals(listOf("Germany"), later.previousKnownRegions)
            assertEquals(listOf("France"), assertNotNull(f.harness.manager.connectedDevice).knownRegions)
            f.harness.eventually("newer edit reprocesses the retained message") { events.size == 1 }
            assertEquals(listOf(listOf(message.id)), events.toList())
            assertEquals("France", assertNotNull(store.fetchMessage(
                com.meshcoreone.android.core.contracts.domain.EntityKey(f.radio, message.id),
            )).regionScope)
            assertEquals("France", store.fetchRxLogEntries(f.radio).single { it.id == retained.id }.regionScope)
            assertEquals("France", f.ingest(f.packet(Bytes.of(1, 2, 3, 4), region = "France")).regionScope)

            releaseFirst.countDown()
            first.await()
            f.harness.settle()
            assertEquals(listOf("France"), assertNotNull(f.harness.manager.connectedDevice).knownRegions)
            assertEquals(listOf(listOf(message.id)), events.toList())
            assertNull(f.ingest(f.packet(Bytes.of(5, 6, 7, 8), region = "Germany")).regionScope)
            assertEquals("France", f.ingest(f.packet(Bytes.of(9, 10, 11, 12), region = "France")).regionScope)
        } finally {
            releaseFirst.countDown()
            observer?.cancelAndJoin()
            f.harness.container.close()
        }
    }

    @Test
    @OriginalCase("RxLogServiceRegionReprocessTests::replaceScopeKeyCache rewrites sticky first-match to ambiguous multi-match()", "native-equivalent")
    @OriginalCase("RxLogServiceRegionReprocessTests::reprocess overwrites intermediate resolution with final cache()", "native-equivalent")
    fun realSameKeyNamesOverwriteStickyAndIntermediateRoomLabelsWithAmbiguity() = runTest {
        val f = RxIngestionFixture(this, store)
        try {
            f.connect()
            val payload = Bytes.of(0xCA, 0xFE, 0xBA, 0xBE)
            val stamp = 1_704_333_000u
            val parsed = assertNotNull(com.meshcoreone.android.core.protocol.parser.RxLogParser.parse(
                5.0, -80, f.packet(payload, region = "Germany"),
            ))
            val entry = RxLogEntryDTO.fromParsed(
                f.radio, parsed, channelIndex = 0u, channelName = "Public", decryptStatus = DecryptStatus.SUCCESS,
                senderTimestamp = stamp, regionScope = "Stale", regionScopeMatches = SnapshotList.of("Stale"),
            )
            val message = MessageDTO(
                radioId = f.radio, channelIndex = 0u, text = "region", timestamp = stamp,
                direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            )
            store.saveRxLogEntry(entry)
            store.saveMessage(message)
            f.live.rxLogService.updateKnownRegions(listOf("Germany"))
            val mid = store.fetchRxLogEntries(f.radio).single { it.id == entry.id }
            assertEquals("Germany", mid.regionScope)
            assertEquals(listOf("Germany"), mid.regionScopeMatches)
            // These real names deliberately derive the same key; no cross-module private cache seam is used.
            f.live.rxLogService.updateKnownRegions(listOf("Germany", "#Germany"))
            val final = store.fetchRxLogEntries(f.radio).single { it.id == entry.id }
            assertNull(final.regionScope)
            assertEquals(setOf("Germany", "#Germany"), final.regionScopeMatches.toSet())
            val saved = assertNotNull(store.fetchMessage(
                com.meshcoreone.android.core.contracts.domain.EntityKey(f.radio, message.id),
            ))
            assertNull(saved.regionScope)
            assertEquals(setOf("Germany", "#Germany"), saved.regionScopeMatches.toSet())
        } finally { f.harness.container.close() }
    }
}
