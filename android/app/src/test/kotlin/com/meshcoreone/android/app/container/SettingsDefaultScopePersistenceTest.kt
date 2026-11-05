// AndroidOnly: WP-317 Actual Settings default-scope action, radio, AppState, Room and region-removal interleavings.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.navigation.removeSettingsKnownRegion
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsDefaultScopePersistenceTest : RoomProcessTest() {
    @Test fun roomFailureReachesTheActualSettingsActionAndSameScopeReselectionRetries() = runTest {
        withSettings { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_scope_save BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'scope storage failure'); END",
            )
            try {
                holder.select("EU868")
                h.eventually("scope storage error reaches actual Settings action") {
                    !holder.state.value.isApplying && failures.isNotEmpty()
                }
                assertNotNull(holder.state.value.errorMessage)
                assertEquals("EU868", radio.mode)
                assertEquals(before, store.fetchDevice(before.id))
                assertEquals(before, h.manager.connectedDevice)
            } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_scope_save") }
            holder.select("EU868")
            h.eventually("scope retry commits and acknowledges") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null &&
                    h.manager.connectedDevice?.defaultFloodScopeName == "EU868" && holder.state.value.currentScope == "EU868"
            }
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
            assertEquals(listOf<String?>("EU868", "EU868"), radio.writes)
        }
    }

    @Test fun selectionWaitsForRoomAndRemovalQueuesBeforeSamplingTheNewDefault() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.defaultFloodScopeName == "EU868") {
                    armed = false
                    entered.complete(Unit)
                    release.await()
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        try {
            withSettings(held) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                store.saveChannel(ChannelDTO(radioId = before.radioId, index = 1u, name = "Scoped")
                    .withFloodScope(ChannelFloodScope.Region("EU868")))
                armed = true
                holder.select("EU868")
                h.eventually("actual scope Room write held") { entered.isCompleted }
                assertTrue(holder.state.value.isApplying)
                assertNull(h.manager.connectedDevice!!.defaultFloodScopeName)
                assertEquals("EU868", radio.mode)
                val removal = async(start = CoroutineStart.UNDISPATCHED) {
                    removeSettingsKnownRegion(h.container, h.sessions.current!!, "EU868")
                }
                h.settle()
                assertFalse(removal.isCompleted)
                assertEquals(listOf<String?>("EU868"), radio.writes)
                assertEquals(before, store.fetchDevice(before.id))
                release.complete(Unit)
                h.eventually("selection and queued region removal acknowledge") {
                    !holder.state.value.isApplying && removal.isCompleted
                }
                removal.await()
                assertTrue(failures.isEmpty())
                assertEquals(listOf("EU868", null), radio.writes)
                assertNull(radio.mode)
                val expected = before.copy(knownRegions = listOf("US915").snapshot())
                assertEquals(expected, store.fetchDevice(before.id))
                assertEquals(expected, h.manager.connectedDevice)
                assertEquals(ChannelFloodScope.Inherit, store.fetchChannel(before.radioId, 1u)!!.floodScope)
            }
        } finally { release.complete(Unit) }
    }

    @Test fun failedSelectionStillClearsActualRadioBeforeRemovingTheRegion() = runTest {
        withSettings { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_scope_before_remove BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'scope storage failure'); END",
            )
            try {
                holder.select("EU868")
                h.eventually("failed selection leaves radio uncertainty") {
                    !holder.state.value.isApplying && failures.isNotEmpty()
                }
                assertNull(h.manager.connectedDevice!!.defaultFloodScopeName)
                assertEquals("EU868", radio.mode)
            } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_scope_before_remove") }
            removeSettingsKnownRegion(h.container, h.sessions.current!!, "EU868")
            assertEquals(listOf("EU868", null), radio.writes)
            assertNull(radio.mode)
            val expected = before.copy(knownRegions = listOf("US915").snapshot())
            assertEquals(expected, store.fetchDevice(before.id))
            assertEquals(expected, h.manager.connectedDevice)
        }
    }

    @Test fun selectionQueuedBehindRemovalRejectsTheNowDeletedRegionBeforeSending() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && "EU868" !in dto.knownRegions) {
                    armed = false
                    entered.complete(Unit)
                    release.await()
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        try {
            withSettings(held) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                armed = true
                val removal = async { removeSettingsKnownRegion(h.container, h.sessions.current!!, "EU868") }
                h.eventually("region removal already holds its Room write") { entered.isCompleted }
                holder.select("EU868")
                h.settle()
                assertTrue(holder.state.value.isApplying)
                assertTrue(radio.writes.isEmpty())
                release.complete(Unit)
                h.eventually("queued stale selection reaches its actual action as failure") {
                    removal.isCompleted && !holder.state.value.isApplying && failures.isNotEmpty()
                }
                removal.await()
                assertNotNull(holder.state.value.errorMessage)
                assertTrue(radio.writes.isEmpty(), "Deleted-region selection is rejected before any radio write")
                assertNull(radio.mode)
                val expected = before.copy(knownRegions = listOf("US915").snapshot())
                assertEquals(expected, store.fetchDevice(before.id))
                assertEquals(expected, h.manager.connectedDevice)
            }
        } finally { release.complete(Unit) }
    }

    @Test fun cancellingTheSettingsWaiterCannotLetAnEnteredOldWriteResurrectARemovedDefault() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val commits = mutableListOf<String?>()
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.defaultFloodScopeName == "EU868") {
                    armed = false
                    entered.complete(Unit)
                    release.await()
                }
                val accepted = store.saveDeviceIfUnchanged(dto, expected)
                if (accepted && dto.defaultFloodScopeName != expected.device.defaultFloodScopeName) {
                    commits += dto.defaultFloodScopeName
                }
                return accepted
            }
        }
        val actionJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val actionScope = CoroutineScope(backgroundScope.coroutineContext + actionJob)
        try {
            withSettings(held, actionScope) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                armed = true
                holder.select("EU868")
                h.eventually("scope event already inside real Room consumer") { entered.isCompleted }
                actionJob.cancel()
                h.eventually("Settings waiter cancelled after sending radio write") { !holder.state.value.isApplying }
                assertEquals("EU868", radio.mode)
                val removal = async { removeSettingsKnownRegion(h.container, h.sessions.current!!, "EU868") }
                h.settle()
                assertEquals(listOf<String?>("EU868"), radio.writes, "Accepted write retains the queue through its acknowledgment")
                assertEquals("EU868", radio.mode)
                assertFalse(removal.isCompleted)
                assertEquals(before, store.fetchDevice(before.id))
                release.complete(Unit)
                h.eventually("old write and clear complete before region removal acknowledges") { removal.isCompleted }
                removal.await()
                assertNull(radio.mode)
                assertEquals(listOf("EU868", null), commits)
                assertTrue(failures.isEmpty())
                val expected = before.copy(knownRegions = listOf("US915").snapshot())
                assertEquals(expected, store.fetchDevice(before.id))
                assertEquals(expected, h.manager.connectedDevice)
                h.settle()
                assertEquals(expected, store.fetchDevice(before.id))
            }
        } finally { release.complete(Unit); actionJob.cancel() }
    }

    @Test fun failedClearPersistenceDoesNotDeleteTheRegionAndCanBeRetried() = runTest {
        withSettings { h, holder, _, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            holder.select("EU868")
            h.eventually("initial selected default acknowledged") {
                !holder.state.value.isApplying && h.manager.connectedDevice?.defaultFloodScopeName == "EU868"
            }
            val selected = before.copy(defaultFloodScopeName = "EU868")
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_scope_clear BEFORE UPDATE ON devices WHEN NEW.defaultFloodScopeName IS NULL " +
                    "BEGIN SELECT RAISE(FAIL, 'scope clear storage failure'); END",
            )
            try {
                assertTrue(runCatching { removeSettingsKnownRegion(h.container, h.sessions.current!!, "EU868") }.isFailure)
                assertEquals(selected, store.fetchDevice(before.id))
                assertEquals(selected, h.manager.connectedDevice)
                assertNull(radio.mode, "A sent clear is not undone by the failed Room write")
            } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_scope_clear") }
            removeSettingsKnownRegion(h.container, h.sessions.current!!, "EU868")
            assertEquals(listOf("EU868", null, null), radio.writes)
            assertEquals(before.copy(knownRegions = listOf("US915").snapshot()), store.fetchDevice(before.id))
        }
    }

    @Test fun contactWatermarkConflictPreservesUnrelatedFieldsBeforeScopeSuccess() = runTest {
        var armed = false
        var rejected = 0
        val interleaving = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.defaultFloodScopeName == "EU868") {
                    armed = false
                    store.updateDeviceLastContactSync(dto.radioId, 9876u)
                    val accepted = store.saveDeviceIfUnchanged(dto, expected)
                    assertFalse(accepted, "Actual contact watermark must reject the old Room revision")
                    rejected++
                    return accepted
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        withSettings(interleaving) { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            armed = true
            holder.select("EU868")
            h.eventually("scope conflict resolves and acknowledges") {
                !holder.state.value.isApplying && h.manager.connectedDevice?.defaultFloodScopeName == "EU868"
            }
            assertEquals(1, rejected)
            assertTrue(failures.isEmpty())
            assertEquals(listOf<String?>("EU868"), radio.writes)
            assertEquals(before.copy(defaultFloodScopeName = "EU868", lastContactSync = 9876u), store.fetchDevice(before.id))
            assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
        }
    }

    @Test fun bufferedOrdinaryReadAndCancelledClearCannotRestoreADeletedDefault() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.pathHashMode == 2u.toUByte()) {
                    armed = false
                    entered.complete(Unit)
                    release.await()
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        val actionJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val actionScope = CoroutineScope(backgroundScope.coroutineContext + actionJob)
        try {
            withSettings(held, actionScope) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val session = h.sessions.current!!
                armed = true
                val edit = async { h.container.deviceEditor.updatePathHashMode(2u, session.token) }
                h.eventually("actual editor blocks ordinary read consumption") { entered.isCompleted }
                assertEquals("EU868", session.settingsService.setDefaultFloodScopeVerified("EU868"))
                assertNull(store.fetchDevice(before.id)!!.defaultFloodScopeName, "Original cached-null removal window")
                assertNull(h.manager.connectedDevice?.defaultFloodScopeName)
                holder.select(null)
                h.eventually("radio clear sent while old ordinary read waits") { radio.writes.size == 2 && radio.mode == null }
                actionJob.cancel()
                h.eventually("clear UI waiter cancelled") { !holder.state.value.isApplying }
                val removal = async { removeSettingsKnownRegion(h.container, session, "EU868") }
                h.settle()
                assertFalse(removal.isCompleted)
                release.complete(Unit)
                h.eventually("buffered read, accepted clear and removal finish in order") { removal.isCompleted }
                assertTrue(edit.await())
                removal.await()
                assertTrue(failures.isEmpty())
                assertNull(radio.mode)
                assertEquals(listOf("EU868", null), radio.writes)
                val expected = before.copy(pathHashMode = 2u, knownRegions = listOf("US915").snapshot())
                assertEquals(expected, store.fetchDevice(before.id))
                assertEquals(expected, h.manager.connectedDevice)
                h.settle()
                assertEquals(expected, store.fetchDevice(before.id), "No delayed ordinary scope restoration after removal ACK")
            }
        } finally { release.complete(Unit); actionJob.cancel() }
    }

    @Test fun mismatchedNameAndKeyReachTheActionAfterActualNamePersistenceAndCanBeRetried() = runTest {
        withSettings { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            radio.writeTransform = { if (it == "EU868") "US915" else it }
            holder.select("EU868")
            h.eventually("actual mismatched name persisted and reported") {
                !holder.state.value.isApplying && holder.state.value.errorMessage != null && holder.state.value.currentScope == "US915"
            }
            assertEquals("US915", radio.mode)
            assertEquals(before.copy(defaultFloodScopeName = "US915"), store.fetchDevice(before.id))
            radio.writeTransform = { it }
            radio.key = Bytes(ByteArray(16) { 7 })
            holder.select("EU868")
            h.eventually("same-name wrong-key readback fails instead of reporting success") {
                !holder.state.value.isApplying && holder.state.value.errorMessage != null && holder.state.value.currentScope == "EU868"
            }
            assertEquals(2, failures.size)
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
            radio.key = null
            holder.select("EU868")
            h.eventually("corrected name and key verify and persist") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null
            }
            assertEquals(listOf<String?>("EU868", "EU868", "EU868"), radio.writes)
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
        }
    }

    @Test fun normalizedAliasRemovalPreservesRadioScopeAndUpdatesTheActualPicker() = runTest {
        withSettings { h, holder, failures, radio ->
            val session = h.sessions.current!!
            val prefix = "A".repeat(30)
            val first = prefix + "one"
            val second = prefix + "two"
            assertTrue(h.container.deviceEditor.addKnownRegion(first, session.token))
            assertTrue(h.container.deviceEditor.addKnownRegion(second, session.token))
            h.eventually("newly committed aliases observed by picker") { second in holder.state.value.sortedKnownRegions }
            holder.select(first)
            h.eventually("normalized selected scope observed by picker") {
                !holder.state.value.isApplying && holder.state.value.currentScope == prefix
            }
            removeSettingsKnownRegion(h.container, session, second)
            h.eventually("removed alias disappears from actual picker") { second !in holder.state.value.sortedKnownRegions }
            assertTrue(first in holder.state.value.sortedKnownRegions)
            assertEquals(prefix, radio.mode)
            assertEquals(prefix, holder.state.value.currentScope)
            assertEquals(prefix, store.fetchDevice(h.manager.connectedDevice!!.id)!!.defaultFloodScopeName)
            assertEquals(listOf<String?>(prefix), radio.writes, "Removing a different raw alias must not clear the selected scope")
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun removingTheExactThirtyByteAliasPreservesItsLongSurvivorUntilTheLastRemoval() = runTest {
        withSettings { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val session = h.sessions.current!!
            val prefix = "A".repeat(30)
            val longer = prefix + "suffix"
            com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, prefix)
            com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, longer)
            h.eventually("mixed aliases observed") { longer in holder.state.value.sortedKnownRegions }
            holder.select(prefix)
            h.eventually("exact-length alias committed") { !holder.state.value.isApplying && holder.state.value.currentScope == prefix }
            val rows = com.meshcoreone.android.app.navigation.SettingsKnownRegionRows(session, listOf(prefix, longer))
            removeSettingsKnownRegion(h.container, rows, prefix)
            assertEquals(prefix, radio.mode)
            assertEquals(prefix, store.fetchDevice(before.id)!!.defaultFloodScopeName)
            assertEquals(prefix, h.manager.connectedDevice?.defaultFloodScopeName)
            assertEquals(listOf<String?>(prefix), radio.writes)
            assertTrue(longer in store.fetchDevice(before.id)!!.knownRegions)
            removeSettingsKnownRegion(h.container, session, longer)
            assertNull(radio.mode)
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
            assertEquals(listOf(prefix, null), radio.writes)
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun retiredVisibleRegionRowsCannotDeleteOrClearTheSuccessorSession() = runTest {
        withSettings { h, holder, _, radio ->
            val first = h.sessions.current!!
            holder.select("EU868")
            h.eventually("original scope saved") { !holder.state.value.isApplying && holder.state.value.currentScope == "EU868" }
            val rows = com.meshcoreone.android.app.navigation.SettingsKnownRegionRows(first, listOf("EU868", "US915"))
            h.manager.disconnect()
            h.manager.connect(h.target())
            h.eventually("successor ready for Settings") {
                h.sessions.current != null && h.sessions.current !== first && h.appState.canRunSettingsStartupReads
            }
            val before = assertNotNull(h.manager.connectedDevice)
            val writes = radio.writes.toList()
            kotlin.test.assertFailsWith<SettingsNotConnectedException> {
                removeSettingsKnownRegion(h.container, rows, "EU868")
            }
            assertEquals(writes, radio.writes)
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
        }
    }

    @Test fun cancelledInFlightScopeAutomaticallyReconcilesThroughTheReplacementActualConsumerBeforeRouting() = runTest {
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        val actions = CoroutineScope(backgroundScope.coroutineContext + job)
        try {
            withSettings(actionScope = actions) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val service = h.sessions.current!!.settingsService
                val retired = service.events()
                holder.select("EU868")
                h.eventually("verified scope awaits its unstarted consumer") {
                    radio.mode == "EU868" && holder.state.value.isApplying &&
                        h.links.radios.single().sent.any { it[0] == 0x40.toUByte() }
                }
                assertNull(store.fetchDevice(before.id)!!.defaultFloodScopeName)
                job.cancel()
                h.eventually("original UI waiter is cancelled") { !holder.state.value.isApplying }
                val route = async {
                    val channel = ChannelDTO(radioId = before.radioId, index = 0u, name = "Scope control")
                    h.container.chatsFeature.timeline(TimelineConversation.Channel(channel)).syncChannelFloodScope(channel)
                }
                h.settle()
                assertFalse(route.isCompleted, "Dependent routing cannot observe the old cached default")
                assertTrue(radio.activeScopes.isEmpty(), "Unacknowledged scope must not reach channel routing")
                h.appState.wireServicesIfConnected()
                h.eventually("actual replacement consumer acknowledges reconciliation") {
                    route.isCompleted && !service.defaultFloodScopeNeedsRecovery
                }
                route.await()
                assertEquals(
                    listOf(PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey())),
                    radio.activeScopes,
                    "The actual channel routing command must use the acknowledged default, not stale cached Disabled",
                )
                assertTrue(retired.events.toList().isEmpty())
                assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
                assertEquals(before.copy(defaultFloodScopeName = "EU868"), h.manager.connectedDevice)
                assertEquals(listOf<String?>("EU868"), radio.writes, "Recovery reads actual state; it does not invent a rollback write")
                assertTrue(failures.isEmpty())
            }
        } finally { job.cancel() }
    }

    @Test fun queuedConcurrentAliasMutationsKeepRadioRoomAndLiveDecisionsInTheSameOrder() = runTest {
        for (addSurvivor in listOf(false, true)) {
            val prefix = "A".repeat(30)
            val longer = prefix + "old"
            val next = prefix + "new"
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var armed = false
            val held = object : PersistenceStoreProtocol by store {
                override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                    if (armed && prefix !in dto.knownRegions && longer in dto.knownRegions) {
                        armed = false
                        entered.complete(Unit)
                        release.await()
                    }
                    return store.saveDeviceIfUnchanged(dto, expected)
                }
            }
            try {
                withSettings(held) { h, holder, failures, radio ->
                    val before = assertNotNull(h.manager.connectedDevice)
                    val session = h.sessions.current!!
                    com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, prefix)
                    com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, longer)
                    h.eventually("mixed aliases ready") { longer in holder.state.value.sortedKnownRegions }
                    holder.select(prefix)
                    h.eventually("selected prefix committed") { !holder.state.value.isApplying && holder.state.value.currentScope == prefix }
                    armed = true
                    val first = async { removeSettingsKnownRegion(h.container, session, prefix) }
                    h.eventually("first actual removal held at Room CAS") { entered.isCompleted }
                    val addition = if (addSurvivor) async {
                        com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, next)
                    } else null
                    h.settle()
                    val second = async { removeSettingsKnownRegion(h.container, session, longer) }
                    h.settle()
                    assertFalse(second.isCompleted)
                    if (addition != null) assertFalse(addition.isCompleted)
                    assertEquals(prefix, radio.mode)
                    assertEquals(listOf<String?>(prefix), radio.writes)
                    assertTrue(prefix in store.fetchDevice(before.id)!!.knownRegions)
                    release.complete(Unit)
                    h.eventually("all queued alias decisions acknowledged") {
                        first.isCompleted && second.isCompleted && addition?.isCompleted != false
                    }
                    first.await(); addition?.await(); second.await()
                    if (addSurvivor) {
                        val expected = before.copy(
                            knownRegions = (before.knownRegions + next).snapshot(), defaultFloodScopeName = prefix,
                        )
                        assertEquals(prefix, radio.mode)
                        assertEquals(expected, store.fetchDevice(before.id))
                        assertEquals(expected, h.manager.connectedDevice)
                        assertEquals(listOf<String?>(prefix), radio.writes)
                        removeSettingsKnownRegion(h.container, session, next)
                    }
                    assertNull(radio.mode)
                    assertEquals(before, store.fetchDevice(before.id))
                    assertEquals(before, h.manager.connectedDevice)
                    assertEquals(listOf(prefix, null), radio.writes)
                    assertTrue(failures.isEmpty())
                }
            } finally { release.complete(Unit) }
        }
    }

    private class ScopeRadio {
        var mode: String? = null
        var key: Bytes? = null
        var writeTransform: (String?) -> String? = { it }
        val writes = mutableListOf<String?>()
        val activeScopes = mutableListOf<Bytes>()
        fun configure(radio: FirmwareRadio) {
            radio.firmwareVersion = 11u
            radio.respond = { command ->
                when (command[0].toInt()) {
                    PacketBuilder.setFloodScope(Bytes(ByteArray(16)))[0].toInt() -> {
                        activeScopes += command
                        listOf(Bytes.of(0))
                    }
                    0x3f -> {
                        val requested = if (command.size == 1) null else
                            command.slice(1, 32).toByteArray().toString(Charsets.UTF_8).substringBefore('\u0000')
                        writes += requested
                        mode = writeTransform(requested)
                        listOf(Bytes.of(0))
                    }
                    0x40 -> listOf(Bytes.of(0x1c) + (mode?.let {
                        Bytes.utf8(it).paddedOrTruncated(31) + (key ?: FloodScope.Region(it).scopeKey())
                    } ?: Bytes.EMPTY))
                    else -> null
                }
            }
        }
    }

    private suspend fun TestScope.withSettings(
        persistence: PersistenceStoreProtocol = store,
        actionScope: CoroutineScope? = null,
        action: suspend (ContainerHarness, DefaultFloodScopeStateHolder, MutableList<Throwable>, ScopeRadio) -> Unit,
    ) {
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val radio = ScopeRadio()
        var holder: DefaultFloodScopeStateHolder? = null
        try {
            h.links.configureRadio = radio::configure
            h.connectForegroundSettings()
            h.eventually("foreground startup and sync finish") {
                h.sessions.current?.syncCoordinator?.state == SyncState.Synced && h.appState.canRunSettingsStartupReads
            }
            h.assertReady()
            val session = h.sessions.current!!
            assertTrue(h.container.deviceEditor.addKnownRegion("EU868", session.token))
            assertTrue(h.container.deviceEditor.addKnownRegion("US915", session.token))
            val feature = AppSettingsFeature.create(
                h.container, MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>()),
            )
            val deps = feature.dependencies(
                notifications = object : NotificationPermissionPort {
                    override suspend fun status() = error("No notification request in scope test")
                    override suspend fun request() = error("No notification request in scope test")
                },
                location = object : LocationPermissionPort {
                    override val authorization = MutableStateFlow(LocationAuthorization.DENIED)
                    override val isRequestingLocation = MutableStateFlow(false)
                    override fun requestPermissionIfNeeded() = error("No location request in scope test")
                    override suspend fun requestCurrentLocation() = error("No location request in scope test")
                },
                navigator = SettingsNavigator { error("No navigation in scope test") },
                diagnostics = object : DiagnosticsPort {
                    override suspend fun createExportFile() = error("No log export in scope test")
                    override suspend fun clearDebugLogs() = error("No log deletion in scope test")
                },
            )
            val failures = mutableListOf<Throwable>()
            val settings = DefaultFloodScopeStateHolder(
                SettingsEnvironment(actionScope ?: backgroundScope, describe = {
                    failures += it
                    UiText.Verbatim("Default flood scope could not be saved")
                }), deps.connection, deps.settingsService, deps.regionDiscovery, deps.deviceStore,
            )
            holder = settings
            settings.start()
            h.eventually("Settings observes ready device") {
                settings.state.value.device != null && settings.state.value.enabled
            }
            action(h, settings, failures, radio)
        } finally {
            holder?.stop()
            h.container.close()
        }
    }
}
