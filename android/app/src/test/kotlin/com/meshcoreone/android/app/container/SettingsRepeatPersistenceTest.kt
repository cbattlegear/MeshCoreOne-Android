// AndroidOnly: WP-305 Actual Settings Repeat action, production adapter, radio, runtime and Room boundaries.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.*
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
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

class SettingsRepeatPersistenceTest : RoomProcessTest() {
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
        val snapshotEntered = CompletableDeferred<Unit>()
        var armed = false
        val observed = object : PersistenceStoreProtocol by store {
            override suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot? {
                if (armed) snapshotEntered.complete(Unit)
                return store.fetchDeviceWriteSnapshot(id)
            }
        }
        withRepeat(observed) { h, holder, _, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val transactionEntered = CompletableDeferred<Unit>()
            val transactionRelease = CompletableDeferred<Unit>()
            val transaction = async(Dispatchers.IO) {
                db.withTransaction { transactionEntered.complete(Unit); transactionRelease.await() }
            }
            try {
                h.eventually("actual Room transaction entered") { transactionEntered.isCompleted }
                armed = true
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("actual editor snapshot read queued behind Room") { snapshotEntered.isCompleted }
                assertTrue(holder.state.value.isApplyingRepeat)
                assertTrue(radio.radioWrites.isEmpty())
                assertEquals(before, h.manager.connectedDevice)
                transactionRelease.complete(Unit)
                transaction.await()
                h.eventually("Room snapshot commits before radio mutation") {
                    !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true &&
                        h.manager.connectedDevice?.frequency == radio.frequency
                }
                assertEquals(before.frequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                assertEquals(1, radio.radioWrites.size)
            } finally { transactionRelease.complete(Unit); transaction.await() }
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
                    h.manager.connect(h.target(original.id))
                    h.eventually("same-radio successor ready") {
                        h.sessions.current?.token != oldToken && h.appState.canRunSettingsStartupReads
                    }
                    val successorToken = assertNotNull(h.sessions.current).token
                    assertEquals(oldToken.radioId, successorToken.radioId)
                    assertTrue(oldToken != successorToken)
                    val successor = assertNotNull(h.manager.connectedDevice)
                    val durable = store.fetchDevice(successor.id)
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
        val frequency: UInt?
            get() = radioWrites.lastOrNull()?.let { frame ->
                (frame[1].toUInt() or (frame[2].toUInt() shl 8) or
                    (frame[3].toUInt() shl 16) or (frame[4].toUInt() shl 24))
            }

        fun configure(radio: FirmwareRadio) {
            params = null
            repeat = false
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
                    0x16 -> if (params != null) listOf(
                        Bytes.of(13, 11, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                            Bytes.utf8("T-Deck").paddedOrTruncated(40) +
                            Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(if (repeat) 1 else 0, 0),
                    ) else null
                    else -> null
                }
            }
        }
    }

    private suspend fun TestScope.withRepeat(
        persistence: PersistenceStoreProtocol,
        action: suspend (ContainerHarness, RadioPresetStateHolder, MutableList<Throwable>, RepeatRadio) -> Unit,
    ) {
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
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
