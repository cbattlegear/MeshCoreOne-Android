// AndroidOnly: WP-317 Real Settings action, protocol readback, AppState acknowledgment and Room write/retry integration.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.DiagnosticsPort
import com.meshcoreone.android.feature.settings.device.LocationAuthorization
import com.meshcoreone.android.feature.settings.device.LocationPermissionPort
import com.meshcoreone.android.feature.settings.device.NotificationPermissionPort
import com.meshcoreone.android.feature.settings.device.PathHashModeStateHolder
import com.meshcoreone.android.feature.settings.device.SettingsEnvironment
import com.meshcoreone.android.feature.settings.device.SettingsNavigator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsPathHashPersistenceTest : RoomProcessTest() {
    @Test fun roomFailureReachesTheActualSettingsActionAndReselectionRetries() = runTest {
        withSettings { h, holder, failures, _ ->
            val before = assertNotNull(h.manager.connectedDevice)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_path_hash_save BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'path hash storage failure'); END",
            )
            try {
                holder.onModeSelected(2u)
                h.eventually("Settings receives the storage error") {
                    !holder.state.value.isApplying && failures.isNotEmpty()
                }
                assertNotNull(holder.state.value.errorMessage)
                assertEquals(before.pathHashMode, store.fetchDevice(before.id)!!.pathHashMode)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_path_hash_save")
            }
            holder.onModeSelected(2u)
            h.eventually("reselection persists and acknowledges") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null &&
                    h.manager.connectedDevice?.pathHashMode == 2u.toUByte()
            }
            assertEquals(2u.toUByte(), store.fetchDevice(before.id)!!.pathHashMode)
            assertEquals(2, h.links.radios.single().sent.count { it[0] == 0x3d.toUByte() })
        }
    }

    @Test fun contactWatermarkBetweenTicketAndSaveIsMergedWithoutLosingUnrelatedFields() = runTest {
        var armed = false
        var conflicts = 0
        val interleaving = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
            override suspend fun fetchDeviceWriteSnapshot(id: java.util.UUID) = store.fetchDeviceWriteSnapshot(id)
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.pathHashMode == 2u.toUByte()) {
                    armed = false
                    store.updateDeviceLastContactSync(dto.radioId, 9876u)
                    val saved = store.saveDeviceIfUnchanged(dto, expected)
                    assertFalse(saved, "The real Room watermark write must invalidate the original ticket")
                    conflicts++
                    return saved
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        withSettings(interleaving) { h, holder, failures, _ ->
            val before = assertNotNull(store.fetchDevice(assertNotNull(h.manager.connectedDevice).id))
            armed = true
            holder.onModeSelected(2u)
            h.eventually("watermark conflict is resolved and acknowledged") {
                !holder.state.value.isApplying && holder.state.value.device?.pathHashMode == 2u.toUByte()
            }
            assertEquals(1, conflicts)
            assertTrue(failures.isEmpty())
            assertEquals(before.copy(pathHashMode = 2u, lastContactSync = 9876u), store.fetchDevice(before.id))
            assertEquals(1, h.links.radios.single().sent.count { it[0] == 0x3d.toUByte() })
        }
    }

    @Test fun settingsRemainsBusyUntilTheRealRoomWriteIsAcknowledged() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
            override suspend fun fetchDeviceWriteSnapshot(id: java.util.UUID) = store.fetchDeviceWriteSnapshot(id)
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.pathHashMode == 2u.toUByte()) {
                    entered.complete(Unit)
                    release.await()
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        try {
            withSettings(held) { h, holder, failures, _ ->
                val before = assertNotNull(h.manager.connectedDevice)
                armed = true
                holder.onModeSelected(2u)
                h.eventually("actual conditional write is suspended") { entered.isCompleted }
                assertTrue(holder.state.value.isApplying)
                assertEquals(before.pathHashMode, store.fetchDevice(before.id)!!.pathHashMode)
                release.complete(Unit)
                h.eventually("actual conditional write is acknowledged") { !holder.state.value.isApplying }
                assertTrue(failures.isEmpty())
                assertEquals(2u.toUByte(), store.fetchDevice(before.id)!!.pathHashMode)
            }
        } finally { release.complete(Unit) }
    }

    @Test fun failedPersistenceCanRestoreThePreviousModeThroughRealRadioReadbackAndRoom() = runTest {
        withSettings { h, holder, failures, radioMode ->
            val before = assertNotNull(h.manager.connectedDevice)
            assertEquals(0u.toUByte(), before.pathHashMode)
            val radio = h.links.radios.single()
            val start = radio.sent.size
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_path_hash_restore BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'path hash storage failure'); END",
            )
            try {
                holder.onModeSelected(2u)
                h.eventually("radio changed but persistence failed") {
                    !holder.state.value.isApplying && failures.isNotEmpty()
                }
                assertEquals(2u.toUByte(), radioMode())
                assertEquals(0u.toUByte(), holder.state.value.displayedMode)
                assertEquals(before, store.fetchDevice(before.id))
                assertEquals(before, h.manager.connectedDevice)
                assertNotNull(holder.state.value.errorMessage)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_path_hash_restore")
            }
            holder.onModeSelected(0u)
            h.eventually("previous mode restored on radio and acknowledged in Settings") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null && radioMode() == 0u.toUByte()
            }
            assertEquals(0u.toUByte(), radioMode())
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
            assertEquals(
                listOf(0x3d, 0x16, 0x3d, 0x16),
                radio.sent.drop(start).map { it[0].toInt() }.filter { it == 0x3d || it == 0x16 },
            )
        }
    }

    @Test fun cleanSameModeSelectionDoesNotWriteTheRadioOrDatabase() = runTest {
        withSettings { h, holder, failures, radioMode ->
            val before = assertNotNull(h.manager.connectedDevice)
            val row = assertNotNull(db.devices().byId(before.id))
            val start = h.links.radios.single().sent.size
            holder.onModeSelected(before.pathHashMode)
            h.settle()
            assertFalse(holder.state.value.isApplying)
            assertTrue(failures.isEmpty())
            assertEquals(before.pathHashMode, radioMode())
            assertEquals(row, db.devices().byId(before.id))
            assertTrue(h.links.radios.single().sent.drop(start).none { it[0] == 0x3d.toUByte() })
        }
    }

    @Test fun retiredBufferedHashCannotReachRoomAfterNewerAcknowledgedMode() = runTest {
        withSettings { h, holder, failures, radioMode ->
            val service = assertNotNull(h.sessions.current).settingsService
            val before = assertNotNull(h.manager.connectedDevice)
            val retired = service.events()
            holder.onModeSelected(2u)
            h.eventually("old verified mode queued without a collector") {
                radioMode() == 2u.toUByte() && holder.state.value.isApplying
            }
            h.appState.wireServicesIfConnected()
            h.eventually("old waiter sees subscription retirement") {
                !holder.state.value.isApplying && failures.isNotEmpty()
            }
            holder.onModeSelected(0u)
            h.eventually("new mode acknowledged") {
                !holder.state.value.isApplying && !service.pathHashModeNeedsRecovery
            }
            assertTrue(retired.events.toList().isEmpty(), "Retired queued persistence must not be delivered")
            assertEquals(0u.toUByte(), radioMode())
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
        }
    }

    @Test fun alreadyEnteredOldRoomCommitCannotLandAfterNewerAcknowledgedMode() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val commits = mutableListOf<UByte>()
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                val saved = if (armed && dto.pathHashMode == 2u.toUByte()) {
                    armed = false
                    entered.complete(Unit)
                    withContext(NonCancellable) {
                        release.await()
                        store.saveDeviceIfUnchanged(dto, expected)
                    }
                } else store.saveDeviceIfUnchanged(dto, expected)
                if (saved) commits += dto.pathHashMode
                return saved
            }
        }
        try {
            withSettings(held) { h, holder, failures, radioMode ->
                val before = assertNotNull(h.manager.connectedDevice)
                val service = assertNotNull(h.sessions.current).settingsService
                armed = true
                holder.onModeSelected(2u)
                h.eventually("old Room write entered") { entered.isCompleted }
                h.appState.wireServicesIfConnected()
                h.eventually("retired original waiter fails") {
                    !holder.state.value.isApplying && failures.isNotEmpty()
                }
                holder.onModeSelected(0u)
                h.eventually("new radio mode verified while old commit held") { radioMode() == 0u.toUByte() }
                assertTrue(holder.state.value.isApplying, "New success must wait for serialized persistence")
                assertTrue(service.pathHashModeNeedsRecovery)
                release.complete(Unit)
                h.eventually("newer mode persists and acknowledges") {
                    !holder.state.value.isApplying && !service.pathHashModeNeedsRecovery
                }
                assertEquals(listOf(2u.toUByte(), 0u.toUByte()), commits)
                assertEquals(before, store.fetchDevice(before.id))
                assertEquals(before, h.manager.connectedDevice)
                h.settle()
                assertEquals(before, store.fetchDevice(before.id))
            }
        } finally { release.complete(Unit) }
    }

    private suspend fun TestScope.withSettings(
        persistence: PersistenceStoreProtocol = store,
        action: suspend (ContainerHarness, PathHashModeStateHolder, MutableList<Throwable>, () -> UByte) -> Unit,
    ) {
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        var holder: PathHashModeStateHolder? = null
        try {
            var mode = 0
            h.links.configureRadio = { radio ->
                radio.firmwareVersion = 10u
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        0x1f -> listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                        0x3d -> { mode = command[2].toInt(); listOf(Bytes.of(0)) }
                        0x16 -> listOf(
                            Bytes.of(13, 10, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                                Bytes.utf8("T-Deck").paddedOrTruncated(40) +
                                Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0, mode),
                        )
                        else -> null
                    }
                }
            }
            h.container.foreground.activityStarted()
            h.manager.connect(h.target())
            h.eventually("foreground startup and sync finish") {
                h.sessions.current?.syncCoordinator?.state == SyncState.Synced &&
                    h.appState.canRunSettingsStartupReads
            }
            h.assertReady()
            val feature = AppSettingsFeature.create(
                h.container, MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>()),
            )
            val deps = feature.dependencies(
                notifications = object : NotificationPermissionPort {
                    override suspend fun status() = error("No notification request in path hash test")
                    override suspend fun request() = error("No notification request in path hash test")
                },
                location = object : LocationPermissionPort {
                    override val authorization = MutableStateFlow(LocationAuthorization.DENIED)
                    override val isRequestingLocation = MutableStateFlow(false)
                    override fun requestPermissionIfNeeded() = error("No location request in path hash test")
                    override suspend fun requestCurrentLocation() = error("No location request in path hash test")
                },
                navigator = SettingsNavigator { error("No navigation in path hash test") },
                diagnostics = object : DiagnosticsPort {
                    override suspend fun createExportFile() = error("No log export in path hash test")
                    override suspend fun clearDebugLogs() = error("No log deletion in path hash test")
                },
            )
            val failures = mutableListOf<Throwable>()
            val settings = PathHashModeStateHolder(
                SettingsEnvironment(backgroundScope, describe = {
                    failures += it
                    UiText.Verbatim("Path hash setting could not be saved")
                }), deps.connection, deps.settingsService,
            )
            holder = settings
            settings.start()
            h.eventually("Settings observes the ready device") {
                settings.state.value.device != null && settings.state.value.enabled
            }
            action(h, settings, failures) { mode.toUByte() }
        } finally {
            holder?.stop()
            h.container.close()
        }
    }
}
