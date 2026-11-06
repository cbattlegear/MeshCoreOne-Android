// AndroidOnly: WP-317 Real Settings action, protocol readback, AppState acknowledgment and Room write/retry integration.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.onboarding.AppStateRadioConfigurator
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.services.device.RadioPresets
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.DiagnosticsPort
import com.meshcoreone.android.feature.settings.device.LocationAuthorization
import com.meshcoreone.android.feature.settings.device.LocationPermissionPort
import com.meshcoreone.android.feature.settings.device.NotificationPermissionPort
import com.meshcoreone.android.feature.settings.device.PathHashModeStateHolder
import com.meshcoreone.android.feature.settings.device.SettingsEnvironment
import com.meshcoreone.android.feature.settings.device.SettingsNavigator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
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
import java.util.concurrent.atomic.AtomicInteger

class SettingsPathHashPersistenceTest : RoomProcessTest() {
    @Test fun ordinarySettingsHashWaitsForOnboardingRadioAndMetadataCommit() = runTest {
        mixedPresetThenSettings("commit")
    }

    @Test fun failedOnboardingMetadataReleasesSharedOrderingForSettingsRetry() = runTest {
        mixedPresetThenSettings("failure")
    }

    @Test fun cancelledOnboardingWaitsForAdmittedMetadataBeforeSettingsCanWrite() = runTest {
        mixedPresetThenSettings("cancel")
    }

    private suspend fun TestScope.mixedPresetThenSettings(outcome: String) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val terminal = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (!armed || dto.appliedRadioPresetID != "mixed-preset") {
                    return store.saveDeviceIfUnchanged(dto, expected)
                }
                armed = false
                entered.complete(Unit)
                return withContext(NonCancellable) {
                    release.await()
                    try {
                        if (outcome == "failure") throw IllegalStateException("preset metadata rejected")
                        store.saveDeviceIfUnchanged(dto, expected)
                    } finally { terminal.complete(Unit) }
                }
            }
        }
        try {
            withSettings(held, beforeClose = { release.complete(Unit) }) { h, holder, failures, radioMode ->
                val before = assertNotNull(h.manager.connectedDevice)
                val configurator = AppStateRadioConfigurator(h.container)
                val preset = RadioPresets.all.first().copy(
                    id = "mixed-preset", frequencyMHz = 868.0, bandwidthKHz = 250.0,
                    spreadingFactor = 10u, codingRate = 5u, pathHashSize = 3,
                )
                armed = true
                val first = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { configurator.applyPreset(preset, configurator.currentTarget()) }
                }
                h.eventually("onboarding metadata entered the actual Room writer") { entered.isCompleted }
                if (outcome == "cancel") first.cancel()
                holder.onModeSelected(0u)
                h.settle()
                assertEquals(2u.toUByte(), radioMode(), "Settings cannot overtake the held preset metadata")
                assertEquals(
                    listOf(2),
                    h.links.radios.single().sent.filter { it[0] == 0x3d.toUByte() }.map { it[2].toInt() },
                )
                assertTrue(holder.state.value.isApplying)
                assertFalse(terminal.isCompleted)
                release.complete(Unit)
                if (outcome == "cancel") {
                    first.join()
                    assertTrue(first.isCancelled)
                } else {
                    val result = first.await()
                    if (outcome == "failure") {
                        assertTrue(generateSequence(assertNotNull(result.exceptionOrNull())) { it.cause }
                            .any { it.message == "preset metadata rejected" })
                    } else result.getOrThrow()
                }
                h.eventually("Settings commits and acknowledges after preset terminal outcome") {
                    terminal.isCompleted && !holder.state.value.isApplying &&
                        !assertNotNull(h.sessions.current).settingsService.pathHashModeNeedsRecovery
                }
                assertTrue(failures.isEmpty())
                assertEquals(0u.toUByte(), radioMode())
                assertEquals(0u.toUByte(), store.fetchDevice(before.id)!!.pathHashMode)
                assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
                assertEquals(
                    listOf(2, 0),
                    h.links.radios.single().sent.filter { it[0] == 0x3d.toUByte() }.map { it[2].toInt() },
                )
                h.settle()
                assertEquals(0u.toUByte(), store.fetchDevice(before.id)!!.pathHashMode)
            }
        } finally { release.complete(Unit) }
    }

    @Test fun onboardingAfterOrdinarySettingsKeepsEventAcknowledgementOutsideRadioLock() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.pathHashMode == 1u.toUByte()) {
                    armed = false
                    entered.complete(Unit)
                    release.await()
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        try {
            withSettings(held, beforeClose = { release.complete(Unit) }) { h, holder, failures, radioMode ->
                val before = assertNotNull(h.manager.connectedDevice)
                armed = true
                holder.onModeSelected(1u)
                h.eventually("ordinary Settings is held in its actual event consumer") { entered.isCompleted }
                val configurator = AppStateRadioConfigurator(h.container)
                val preset = RadioPresets.all.first().copy(
                    id = "later-preset", frequencyMHz = 868.0, bandwidthKHz = 250.0,
                    spreadingFactor = 10u, codingRate = 5u, pathHashSize = 3,
                )
                val later = async { configurator.applyPreset(preset, configurator.currentTarget()) }
                h.eventually("later preset radio write does not wait on the earlier Settings ACK") {
                    radioMode() == 2u.toUByte()
                }
                assertTrue(holder.state.value.isApplying)
                assertFalse(later.isCompleted)
                release.complete(Unit)
                later.await()
                h.eventually("earlier Settings waiter settles and later preset metadata wins") {
                    !holder.state.value.isApplying &&
                        h.manager.connectedDevice?.pathHashMode == 2u.toUByte()
                }
                h.settle()
                assertTrue(failures.isEmpty())
                val saved = assertNotNull(store.fetchDevice(before.id))
                assertEquals(2u.toUByte(), saved.pathHashMode)
                assertEquals(preset.id, saved.appliedRadioPresetID)
                assertEquals(preset.frequencyKHz, saved.frequency)
                assertEquals(saved, h.manager.connectedDevice)
                assertEquals(2u.toUByte(), radioMode())
                assertEquals(
                    listOf(1, 2),
                    h.links.radios.single().sent.filter { it[0] == 0x3d.toUByte() }.map { it[2].toInt() },
                )
            }
        } finally { release.complete(Unit) }
    }

    @Test fun replacementDrainsAdmittedPresetAndRefusesQueuedOldSettingsWrite() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store, DeviceEditPersisting by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.appliedRadioPresetID == "retired-preset") {
                    armed = false
                    entered.complete(Unit)
                    return withContext(NonCancellable) {
                        release.await()
                        store.saveDeviceIfUnchanged(dto, expected)
                    }
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        try {
            withSettings(held, beforeClose = { release.complete(Unit) }) { h, holder, failures, _ ->
                val oldToken = assertNotNull(h.manager.snapshot.value.token)
                val oldRadio = h.links.radios.single()
                val configurator = AppStateRadioConfigurator(h.container)
                val preset = RadioPresets.all.first().copy(
                    id = "retired-preset", frequencyMHz = 868.0, bandwidthKHz = 250.0,
                    spreadingFactor = 10u, codingRate = 5u, pathHashSize = 3,
                )
                armed = true
                val first = async { runCatching { configurator.applyPreset(preset, configurator.currentTarget()) } }
                h.eventually("preset writer is held before replacement") { entered.isCompleted }
                holder.onModeSelected(0u)
                val replacement = async {
                    h.manager.disconnect()
                    h.manager.connect(h.target())
                }
                h.eventually("replacement logically retires the old generation") {
                    h.manager.snapshot.value.token != oldToken
                }
                assertFalse(replacement.isCompleted)
                assertEquals(1, h.links.radios.size, "No successor may install ahead of the admitted writer")
                release.complete(Unit)
                assertNotNull(first.await().exceptionOrNull())
                replacement.await()
                h.assertReady()
                h.eventually("old Settings waiter observes retirement") {
                    !holder.state.value.isApplying && failures.isNotEmpty()
                }
                val successor = assertNotNull(h.manager.connectedDevice)
                h.settle()
                assertEquals(successor, h.manager.connectedDevice)
                assertEquals(successor, store.fetchDevice(successor.id))
                assertEquals(
                    listOf(2), oldRadio.sent.filter { it[0] == 0x3d.toUByte() }.map { it[2].toInt() },
                    "The old queued Settings write must not acquire the successor or write after retirement",
                )
            }
        } finally { release.complete(Unit) }
    }

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
        beforeClose: () -> Unit = {},
        action: suspend (ContainerHarness, PathHashModeStateHolder, MutableList<Throwable>, () -> UByte) -> Unit,
    ) {
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        var holder: PathHashModeStateHolder? = null
        try {
            val mode = AtomicInteger(0)
            val frequencyKHz = AtomicInteger(915_000)
            h.links.configureRadio = { radio ->
                radio.firmwareVersion = 10u
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        0x1f -> listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                        0x0b -> {
                            val frequency = (0..3).fold(0u) { value, index ->
                                value or (command[index + 1].toUInt() shl (index * 8))
                            }
                            frequencyKHz.set(frequency.toInt())
                            listOf(Bytes.of(0))
                        }
                        0x01 -> listOf(
                            Bytes.of(5, 1, 22, 22) + key(7) + le32(0) + le32(0) + Bytes.of(0, 0, 2, 0) +
                                le32(frequencyKHz.get().toLong()) + le32(250_000) + Bytes.of(10, 5) +
                                Bytes.utf8("TestNode"),
                        )
                        0x3d -> { mode.set(command[2].toInt()); listOf(Bytes.of(0)) }
                        0x16 -> listOf(
                            Bytes.of(13, 10, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                                Bytes.utf8("T-Deck").paddedOrTruncated(40) +
                                Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0, mode.get()),
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
            action(h, settings, failures) { mode.get().toUByte() }
        } finally {
            beforeClose()
            holder?.stop()
            h.container.close()
        }
    }
}
