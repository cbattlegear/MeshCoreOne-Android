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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SettingsPathHashPersistenceTest : RoomProcessTest() {
    @Test fun roomFailureReachesTheActualSettingsActionAndReselectionRetries() = runTest {
        withSettings { h, holder, failures ->
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
        val interleaving = object : PersistenceStoreProtocol by store, DeviceEditPersisting {
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
        withSettings(interleaving) { h, holder, failures ->
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
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting {
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
            withSettings(held) { h, holder, failures ->
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

    private suspend fun TestScope.withSettings(
        persistence: PersistenceStoreProtocol = store,
        action: suspend (ContainerHarness, PathHashModeStateHolder, MutableList<Throwable>) -> Unit,
    ) {
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        var holder: PathHashModeStateHolder? = null
        try {
            h.links.configureRadio = { radio ->
                var mode = 0
                radio.firmwareVersion = 10u
                radio.respond = { command ->
                    when (command[0].toInt()) {
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
            action(h, settings, failures)
        } finally {
            holder?.stop()
            h.container.close()
        }
    }
}
