// AndroidOnly: WP-317 Actual Settings default-scope action, radio, AppState, Room and region-removal interleavings.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.navigation.removeSettingsKnownRegion
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
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
                    h.manager.connectedDevice?.defaultFloodScopeName == "EU868"
            }
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
            assertEquals(listOf("EU868", "EU868"), radio.writes)
        }
    }

    @Test fun selectionWaitsForRoomAndRemovalQueuesBeforeSamplingTheNewDefault() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
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
                assertEquals(listOf("EU868"), radio.writes)
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
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
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
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
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
                h.eventually("removal clears actual radio but awaits serialized old consumer") { radio.writes.size == 2 }
                assertNull(radio.mode)
                assertFalse(removal.isCompleted)
                assertEquals(before, store.fetchDevice(before.id))
                release.complete(Unit)
                h.eventually("old write and clear complete before region removal acknowledges") { removal.isCompleted }
                removal.await()
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
                "CREATE TRIGGER reject_scope_clear BEFORE UPDATE ON devices " +
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
        val interleaving = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
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
            assertEquals(listOf("EU868"), radio.writes)
            assertEquals(before.copy(defaultFloodScopeName = "EU868", lastContactSync = 9876u), store.fetchDevice(before.id))
            assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
        }
    }

    private class ScopeRadio {
        var mode: String? = null
        val writes = mutableListOf<String?>()
        fun configure(radio: FirmwareRadio) {
            radio.firmwareVersion = 11u
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x3f -> {
                        mode = if (command.size == 1) null else
                            command.slice(1, 32).toByteArray().toString(Charsets.UTF_8).substringBefore('\u0000')
                        writes += mode
                        listOf(Bytes.of(0))
                    }
                    0x40 -> listOf(Bytes.of(0x1c) + (mode?.let {
                        Bytes.utf8(it).paddedOrTruncated(31) + Bytes(ByteArray(16))
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
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
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
