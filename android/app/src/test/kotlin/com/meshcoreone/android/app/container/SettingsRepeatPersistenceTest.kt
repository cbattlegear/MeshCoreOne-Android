// AndroidOnly: WP-305 Actual Settings Repeat action, production adapter, radio, runtime and Room boundaries.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PreRepeatSettingsAttempt
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.*
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepeatPersistenceTest : RoomProcessTest() {
    @Test fun committedSnapshotCancelledBeforeSetIsAbortedAndFreshEnableRestoresTheLaterManualRf() = runTest {
        val committed = CompletableDeferred<Job>()
        val hold = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                val saved = store.saveDeviceIfUnchanged(dto, expected)
                if (saved && armed && expected.device.preRepeatFrequency == null && dto.preRepeatFrequency != null) {
                    armed = false
                    committed.complete(currentCoroutineContext()[Job]!!)
                    hold.await()
                }
                return saved
            }
        }
        try {
            withRepeat(held) { h, holder, _, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val token = assertNotNull(h.sessions.current).token
                armed = true
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("actual snapshot committed before cancelled return") { committed.isCompleted }
                assertEquals(before.frequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                assertEquals(before.frequency, h.manager.connectedDevice!!.preRepeatFrequency)
                assertTrue(radio.radioWrites.isEmpty())
                committed.await().cancel()
                h.eventually("cancelled preparation cleanup acknowledged") { !holder.state.value.isApplyingRepeat }
                assertEquals(before, store.fetchDevice(before.id))
                assertEquals(before, h.manager.connectedDevice)
                assertFalse(holder.state.value.isRepeatEnabled)
                assertTrue(radio.radioWrites.isEmpty(), "Cancellation was before the first SET")
                val service = assertNotNull(h.sessions.current).settingsService
                service.setRadioParamsVerified(868_000u, 125_000u, 9u, 6u, false)
                h.eventually("later manual RF published on the original token") {
                    h.manager.connectedDevice?.frequency == 868_000u && holder.state.value.device?.frequency == 868_000u
                }
                val manual = assertNotNull(h.manager.connectedDevice)
                assertEquals(token, h.sessions.current?.token)
                assertNull(manual.preRepeatFrequency)
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("fresh Repeat snapshot follows the later manual RF") {
                    !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true &&
                        holder.state.value.device?.clientRepeat == true &&
                        h.manager.connectedDevice?.frequency == radio.frequency &&
                        holder.state.value.device?.frequency == radio.frequency
                }
                val prepared = assertNotNull(store.fetchDevice(before.id))
                assertEquals(manual.frequency, prepared.preRepeatFrequency)
                assertEquals(manual.bandwidth, prepared.preRepeatBandwidth)
                assertEquals(manual.spreadingFactor, prepared.preRepeatSpreadingFactor)
                assertEquals(manual.codingRate, prepared.preRepeatCodingRate)
                holder.onRepeatToggled(false)
                h.eventually("fresh Repeat off restores the later manual RF and clears") {
                    !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == false &&
                        h.manager.connectedDevice?.frequency == manual.frequency &&
                        h.manager.connectedDevice?.preRepeatFrequency == null
                }
                val restored = assertNotNull(store.fetchDevice(before.id))
                assertEquals(manual.frequency, restored.frequency)
                assertEquals(manual.bandwidth, restored.bandwidth)
                assertEquals(manual.spreadingFactor, restored.spreadingFactor)
                assertEquals(manual.codingRate, restored.codingRate)
                assertNull(restored.preRepeatFrequency)
                assertNull(restored.preRepeatBandwidth)
                assertNull(restored.preRepeatSpreadingFactor)
                assertNull(restored.preRepeatCodingRate)
                assertEquals(listOf(0, 1, 0), radio.radioWrites.map { it[11].toInt() })
            }
        } finally { hold.complete(Unit) }
    }

    @Test fun abortPreservesEveryExistingSnapshotAndRefusesEqualDtoAbaOrRetiredCreationReceipts() = runTest {
        withRepeat(store) { h, _, _, _ ->
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.sessions.current).token
            val editor = h.container.deviceEditor
            val full = before.savingPreRepeatSettings()
            val existing = listOf(
                full, before.copy(preRepeatFrequency = full.preRepeatFrequency),
                before.copy(preRepeatBandwidth = full.preRepeatBandwidth),
                before.copy(preRepeatSpreadingFactor = full.preRepeatSpreadingFactor),
                before.copy(preRepeatCodingRate = full.preRepeatCodingRate),
            )
            for (snapshot in existing) {
                store.saveDevice(snapshot)
                editor.updateDevice(snapshot)
                val attempt = PreRepeatSettingsAttempt(token)
                assertTrue(editor.savePreRepeatSettings(token, attempt))
                assertTrue(editor.abortPreRepeatSettings(token, attempt))
                assertEquals(snapshot, store.fetchDevice(before.id), "Never abort a preexisting full or partial snapshot")
                assertEquals(snapshot, h.manager.connectedDevice)
            }
            assertTrue(editor.clearPreRepeatSettings(token))
            val abaAttempt = PreRepeatSettingsAttempt(token)
            assertTrue(editor.savePreRepeatSettings(token, abaAttempt))
            val prepared = assertNotNull(store.fetchDevice(before.id))
            // Same complete DTO after two targeted commits must still invalidate the creation receipt.
            store.updateDeviceLastContactSync(before.radioId, prepared.lastContactSync + 1u)
            store.updateDeviceLastContactSync(before.radioId, prepared.lastContactSync)
            assertEquals(prepared, store.fetchDevice(before.id))
            val refused = runCatching { editor.abortPreRepeatSettings(token, abaAttempt) }.exceptionOrNull()
            assertNotNull(refused, "Abort must compare the actual creation revision, not just snapshot values")
            assertEquals(prepared, store.fetchDevice(before.id))
            assertEquals(prepared, h.manager.connectedDevice)
            assertTrue(editor.clearPreRepeatSettings(token))
            val retiredAttempt = PreRepeatSettingsAttempt(token)
            assertTrue(editor.savePreRepeatSettings(token, retiredAttempt))
            h.manager.disconnect()
            h.manager.connect(h.target(before.id))
            h.eventually("successor ready for receipt refusal") {
                h.sessions.current?.token != token && h.appState.canRunSettingsStartupReads
            }
            val successor = assertNotNull(h.manager.connectedDevice)
            val durable = store.fetchDevice(before.id)
            assertNotNull(runCatching { editor.abortPreRepeatSettings(token, retiredAttempt) }.exceptionOrNull())
            assertEquals(successor, h.manager.connectedDevice)
            assertEquals(durable, store.fetchDevice(before.id))
        }
    }

    @Test fun repeatWaitsForSnapshotAndClearFailuresReachTheActualActionBeforeRetry() = runTest {
        val saveEntered = CompletableDeferred<Unit>()
        val saveRelease = CompletableDeferred<Unit>()
        val clearEntered = CompletableDeferred<Unit>()
        val clearRelease = CompletableDeferred<Unit>()
        var armed = false
        var failSave = true
        var failClear = true
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && expected.device.preRepeatFrequency == null && dto.preRepeatFrequency != null && failSave) {
                    saveEntered.complete(Unit)
                    saveRelease.await()
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Repeat snapshot unavailable"))
                }
                if (armed && expected.device.preRepeatFrequency != null && dto.preRepeatFrequency == null && failClear) {
                    clearEntered.complete(Unit)
                    clearRelease.await()
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Repeat clear unavailable"))
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        try {
            withRepeat(held) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                armed = true
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("pre-Repeat save reached production persistence boundary") { saveEntered.isCompleted }
                assertTrue(holder.state.value.isApplyingRepeat)
                assertTrue(radio.radioWrites.isEmpty(), "No radio mutation before durable snapshot")
                assertNull(store.fetchDevice(before.id)!!.preRepeatFrequency)
                saveRelease.complete(Unit)
                h.eventually("snapshot failure reaches Repeat action") {
                    !holder.state.value.isApplyingRepeat && failures.isNotEmpty()
                }
                assertTrue(radio.radioWrites.isEmpty())
                assertNotNull(holder.state.value.errorMessage)
                failSave = false
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("actual Repeat radio and snapshot committed") {
                    !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true &&
                        h.manager.connectedDevice?.frequency == radio.frequency
                }
                val repeating = store.fetchDevice(before.id)!!
                assertEquals(before.frequency, repeating.preRepeatFrequency)
                assertEquals(before.bandwidth, repeating.preRepeatBandwidth)
                assertEquals(before.spreadingFactor, repeating.preRepeatSpreadingFactor)
                assertEquals(before.codingRate, repeating.preRepeatCodingRate)
                assertEquals(1, radio.radioWrites.size)
                holder.onRepeatToggled(false)
                h.eventually("pre-Repeat clear held after verified restore") { clearEntered.isCompleted }
                assertTrue(holder.state.value.isApplyingRepeat)
                assertEquals(before.frequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                clearRelease.complete(Unit)
                h.eventually("clear failure remains visible") {
                    !holder.state.value.isApplyingRepeat && failures.size >= 2
                }
                assertNotNull(holder.state.value.errorMessage)
                assertEquals(before.frequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                failClear = false
                holder.onRepeatToggled(false)
                h.eventually("restored snapshot clear acknowledges") {
                    !holder.state.value.isApplyingRepeat && !holder.state.value.isRepeatEnabled &&
                        h.manager.connectedDevice?.frequency == before.frequency &&
                        h.manager.connectedDevice?.clientRepeat == false
                }
                val restored = store.fetchDevice(before.id)!!
                assertNull(restored.preRepeatFrequency)
                assertNull(restored.preRepeatBandwidth)
                assertNull(restored.preRepeatSpreadingFactor)
                assertNull(restored.preRepeatCodingRate)
                assertEquals(before.frequency, restored.frequency)
                assertEquals(before.bandwidth, restored.bandwidth)
                assertEquals(before.spreadingFactor, restored.spreadingFactor)
                assertEquals(before.codingRate, restored.codingRate)
                assertFalse(restored.clientRepeat)
                assertEquals(listOf(1, 0, 0), radio.radioWrites.map { it[11].toInt() })
            }
        } finally { saveRelease.complete(Unit); clearRelease.complete(Unit) }
    }

    @Test fun actualHeldRoomTransactionPreventsRepeatRadioMutationUntilTheSnapshotCommits() = runTest {
        val worker = Executors.newSingleThreadExecutor()
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), MeshCoreDatabase::class.java,
        ).allowMainThreadQueries().setQueryExecutor(Executor { it.run() }).setTransactionExecutor(worker).build()
        val repository = RoomPersistenceStore(database, backgroundScope)
        val snapshotEntered = CompletableDeferred<Unit>()
        var armed = false
        val observed = object : PersistenceStoreProtocol by repository {
            override suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot? {
                if (armed) snapshotEntered.complete(Unit)
                return repository.fetchDeviceWriteSnapshot(id)
            }
        }
        try {
            withRepeat(observed, room = repository) { h, holder, _, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val transactionEntered = CompletableDeferred<Unit>()
                val transactionRelease = CompletableDeferred<Unit>()
                val transaction = async(Dispatchers.IO) {
                    database.withTransaction {
                        assertTrue(database.inTransaction(), "The barrier holds an actual open Room transaction")
                        transactionEntered.complete(Unit)
                        transactionRelease.await()
                    }
                }
                try {
                    h.eventually("actual Room transaction entered") { transactionEntered.isCompleted }
                    armed = true
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("editor reaches snapshot boundary while actual Room transaction is held") { snapshotEntered.isCompleted }
                    assertTrue(holder.state.value.isApplyingRepeat)
                    assertTrue(radio.radioWrites.isEmpty())
                    assertEquals(before, h.manager.connectedDevice)
                    transactionRelease.complete(Unit)
                    transaction.await()
                    h.eventually("Room snapshot commits before radio mutation") {
                        !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true &&
                            h.manager.connectedDevice?.frequency == radio.frequency
                    }
                    assertEquals(before.frequency, repository.fetchDevice(before.id)!!.preRepeatFrequency)
                    assertEquals(1, radio.radioWrites.size)
                } finally { transactionRelease.complete(Unit); transaction.await() }
            }
        } finally {
            try { repository.close() } finally {
                try { database.close() } finally {
                    worker.shutdown()
                    assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS), "The owned Room transaction worker must finish")
                }
            }
        }
    }

    @Test fun retiredSnapshotOrClearCannotMutateASameRadioSuccessorOrItsDurableSnapshot() = runTest {
        for (clear in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var armed = false
            val held = object : PersistenceStoreProtocol by store {
                override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                    val saving = expected.device.preRepeatFrequency == null && dto.preRepeatFrequency != null
                    val clearing = expected.device.preRepeatFrequency != null && dto.preRepeatFrequency == null
                    if (armed && if (clear) clearing else saving) {
                        armed = false
                        entered.complete(Unit)
                        release.await()
                    }
                    return store.saveDeviceIfUnchanged(dto, expected)
                }
            }
            try {
                withRepeat(held) { h, holder, failures, radio ->
                    val original = assertNotNull(h.manager.connectedDevice)
                    val oldToken = assertNotNull(h.sessions.current).token
                    if (clear) {
                        holder.onRepeatToggled(true)
                        holder.confirmEnableRepeatMode()
                        h.eventually("Repeat active before old clear") {
                            !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true &&
                                h.manager.connectedDevice?.frequency == radio.frequency
                        }
                        h.eventually("holder observes Repeat snapshot") { holder.state.value.device?.preRepeatFrequency != null }
                    }
                    armed = true
                    if (clear) holder.onRepeatToggled(false) else {
                        holder.onRepeatToggled(true)
                        holder.confirmEnableRepeatMode()
                    }
                    h.eventually("old save or clear suspended before conditional write") { entered.isCompleted }
                    val transmitted = radio.radioWrites.size
                    h.manager.disconnect()
                    // This successor reports Repeat active; an off radio legitimately clears old restoration data at initialization.
                    radio.repeatOnNextConnection = clear
                    h.manager.connect(h.target(original.id))
                    h.eventually("same-radio successor ready") {
                        h.sessions.current?.token != oldToken && h.appState.canRunSettingsStartupReads
                    }
                    val successorToken = assertNotNull(h.sessions.current).token
                    assertEquals(oldToken.radioId, successorToken.radioId)
                    assertTrue(oldToken != successorToken)
                    val successor = assertNotNull(h.manager.connectedDevice)
                    val durable = store.fetchDevice(successor.id)
                    if (clear) {
                        assertTrue(successor.clientRepeat)
                        assertNotNull(durable?.preRepeatFrequency, "The successor owns a restoration snapshot before old clear resumes")
                    }
                    release.complete(Unit)
                    h.eventually("retired action reports failure and releases busy state") {
                        !holder.state.value.isApplyingRepeat && failures.isNotEmpty()
                    }
                    assertNotNull(holder.state.value.errorMessage)
                    assertEquals(transmitted, radio.radioWrites.size, "Retired action must not send another command")
                    assertEquals(successor, h.manager.connectedDevice)
                    assertEquals(durable, store.fetchDevice(successor.id))
                    if (clear) assertNotNull(durable?.preRepeatFrequency, "Old clear cannot erase the successor restoration snapshot")
                    else assertNull(durable?.preRepeatFrequency, "Old save cannot create a successor snapshot")
                    assertTrue(h.container.deviceEditor.savePreRepeatSettings(successorToken))
                    assertNotNull(store.fetchDevice(successor.id)?.preRepeatFrequency, "A fresh successor action remains supported")
                }
            } finally { release.complete(Unit) }
        }
    }

    private class RepeatRadio {
        val radioWrites = mutableListOf<Bytes>()
        private var params: Bytes? = null
        private var repeat = false
        var repeatOnNextConnection = false
        val frequency: UInt?
            get() = radioWrites.lastOrNull()?.let { frame ->
                (frame[1].toUInt() or (frame[2].toUInt() shl 8) or
                    (frame[3].toUInt() shl 16) or (frame[4].toUInt() shl 24))
            }

        fun configure(radio: FirmwareRadio) {
            params = null
            repeat = repeatOnNextConnection
            repeatOnNextConnection = false
            radio.firmwareVersion = 11u
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x0b -> {
                        radioWrites += command
                        params = command.slice(1, 11)
                        repeat = command[11] != 0u.toUByte()
                        listOf(Bytes.of(0))
                    }
                    0x01 -> params?.let { listOf(radio.selfPacket().slice(0, 48) + it + Bytes.utf8("TestNode")) }
                    0x16 -> if (params != null || repeat) listOf(
                        Bytes.of(13, 11, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                            Bytes.utf8("T-Deck").paddedOrTruncated(40) +
                            Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(if (repeat) 1 else 0, 0),
                    ) else null
                    0x3c -> listOf(Bytes.of(0x1a))
                    else -> null
                }
            }
        }
    }

    private suspend fun TestScope.withRepeat(
        persistence: PersistenceStoreProtocol,
        room: RoomPersistenceStore = store,
        action: suspend (ContainerHarness, RadioPresetStateHolder, MutableList<Throwable>, RepeatRadio) -> Unit,
    ) {
        val h = ContainerHarness(this, room, persistenceOverride = persistence)
        val radio = RepeatRadio()
        var holder: RadioPresetStateHolder? = null
        try {
            h.links.configureRadio = radio::configure
            h.connectForegroundSettings()
            h.eventually("foreground startup and sync finish") {
                h.sessions.current?.syncCoordinator?.state == SyncState.Synced && h.appState.canRunSettingsStartupReads
            }
            h.assertReady()
            val feature = AppSettingsFeature.create(
                h.container, MeshCoreStorage.get(ApplicationProvider.getApplicationContext<Context>()),
            )
            val deps = feature.dependencies(
                notifications = object : NotificationPermissionPort {
                    override suspend fun status() = error("No notification request in Repeat test")
                    override suspend fun request() = error("No notification request in Repeat test")
                },
                location = object : LocationPermissionPort {
                    override val authorization = MutableStateFlow(LocationAuthorization.DENIED)
                    override val isRequestingLocation = MutableStateFlow(false)
                    override fun requestPermissionIfNeeded() = error("No location request in Repeat test")
                    override suspend fun requestCurrentLocation() = error("No location request in Repeat test")
                },
                navigator = SettingsNavigator { error("No navigation in Repeat test") },
                diagnostics = object : DiagnosticsPort {
                    override suspend fun createExportFile() = error("No log export in Repeat test")
                    override suspend fun clearDebugLogs() = error("No log deletion in Repeat test")
                },
            )
            val failures = mutableListOf<Throwable>()
            val repeat = RadioPresetStateHolder(
                SettingsEnvironment(backgroundScope, describe = { failures += it; UiText.Verbatim("Repeat failed") }),
                deps.connection, deps.regions, deps.radioCatalog, deps.settingsService, deps.deviceStore, RadioWriteGate(),
            )
            holder = repeat
            repeat.start()
            h.eventually("Settings observes ready device") {
                repeat.state.value.device != null && repeat.state.value.connectionState.isConnected
            }
            action(h, repeat, failures, radio)
        } finally { holder?.stop(); h.container.close() }
    }
}
