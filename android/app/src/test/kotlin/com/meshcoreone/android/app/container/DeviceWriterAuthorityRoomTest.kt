// AndroidOnly: WP-305 Deterministic production-editor/real-Room competing writer interleavings.
package com.meshcoreone.android.app.container

import androidx.room.withTransaction
import com.meshcoreone.android.app.navigation.addSettingsKnownRegion
import com.meshcoreone.android.app.navigation.removeSettingsKnownRegion
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.KnownRegionRemovalOutcome
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.feature.settings.device.SettingsNotConnectedException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeviceWriterAuthorityRoomTest : RoomProcessTest() {
    private class PausedStore(private val room: RoomPersistenceStore) :
        PersistenceStoreProtocol by room, DeviceEditPersisting {
        var beforeSnapshot: suspend () -> Unit = {}
        var beforeSave: suspend () -> Unit = {}
        var afterSave: suspend () -> Unit = {}
        var beforeRemove: suspend () -> Unit = {}
        var afterActivation: suspend () -> Unit = {}
        var onWriteAuthority: () -> Unit = {}
        var rejected = 0
        override suspend fun insertDeviceIfAbsent(dto: DeviceDTO): Boolean = room.insertDeviceIfAbsent(dto)
        override suspend fun activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean {
            val accepted = room.activateDeviceIfCurrent(id, isCurrent)
            val hook = afterActivation
            afterActivation = {}
            hook()
            return accepted
        }

        override suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot? {
            val hook = beforeSnapshot
            beforeSnapshot = {}
            hook()
            return room.fetchDeviceWriteSnapshot(id)
        }

        override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
            if (expected.device.knownRegions.any { it !in dto.knownRegions }) {
                val removal = beforeRemove
                beforeRemove = {}
                removal()
            }
            val hook = beforeSave
            beforeSave = {}
            hook()
            val accepted = room.saveDeviceIfUnchanged(dto, expected.copy(withAuthority = { write ->
                expected.withAuthority {
                    onWriteAuthority()
                    write()
                }
            }))
            if (!accepted) rejected++
            val committed = afterSave
            afterSave = {}
            committed()
            return accepted
        }

        override suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String) {
            val hook = beforeRemove
            beforeRemove = {}
            hook()
            room.removeDeviceKnownRegion(radioId, region)
        }
    }

    @Test fun initializationAndRepeatedSimulatorSeedCannotReplaceSettingsOrActivateAnOlderRadio() = runTest {
        val successor = DeviceDTO(radioId = RadioId(UUID(0, 301)), publicKey = key(3), nodeName = "Successor")
        store.saveDevice(successor)
        store.setActiveDevice(successor.id)
        val staleSeed = DeviceDTO(
            radioId = RadioId(UUID(0, 302)), publicKey = key(4), nodeName = "Simulator", isActive = true,
        )
        val seed = RoomSimulatorSeedStore(store)
        seed.saveDevice(staleSeed)
        val inserted = assertNotNull(store.fetchDevice(staleSeed.id))
        assertFalse(inserted.isActive, "Only explicit activation may choose the active device")
        val ticket = store.fetchDeviceWriteSnapshot(inserted.id)!!
        val edited = inserted.copy(appliedRadioPresetID = "new-preset", pathHashMode = 2u, ocvPreset = "liFePO4")
        assertTrue(store.saveDeviceIfUnchanged(edited, ticket))
        seed.saveDevice(staleSeed)
        assertEquals(edited, store.fetchDevice(inserted.id), "A delayed/repeated seed never replaces an existing row")
        assertEquals(successor.id, store.fetchActiveDevice()!!.id)
        assertFalse(store.insertDeviceIfAbsent(staleSeed.copy(nodeName = "Late initialization")))
        assertEquals(edited, store.fetchDevice(inserted.id))
        val activeTicket = store.fetchDeviceWriteSnapshot(inserted.id)!!
        assertFalse(store.saveDeviceIfUnchanged(edited.copy(isActive = true), activeTicket))
        assertEquals(successor.id, store.fetchActiveDevice()!!.id)
    }

    @Test fun activationRechecksRetiredOwnerAfterWaitingForTheActualRoomTransaction() = runTest {
        val original = DeviceDTO(radioId = RadioId(UUID(0, 401)), publicKey = key(3), nodeName = "Original")
        val successor = DeviceDTO(radioId = RadioId(UUID(0, 402)), publicKey = key(4), nodeName = "Successor")
        store.saveDevice(original)
        store.saveDevice(successor)
        store.setActiveDevice(successor.id)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val current = AtomicBoolean(true)
        val guardCalls = AtomicInteger()
        val transaction = async(Dispatchers.IO) {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            val submitted = CompletableDeferred<Unit>()
            val activation = async(Dispatchers.IO) {
                submitted.complete(Unit)
                store.activateDeviceIfCurrent(original.id) { guardCalls.incrementAndGet(); current.get() }
            }
            submitted.await()
            assertEquals(0, guardCalls.get(), "The guard must run inside Room, not before waiting for its transaction")
            current.set(false)
            release.complete(Unit)
            assertFalse(activation.await())
            transaction.await()
            assertEquals(1, guardCalls.get())
            assertEquals(successor.id, store.fetchActiveDevice()!!.id)
            assertFalse(store.fetchDevice(original.id)!!.isActive)
        } finally { release.complete(Unit) }
    }

    @Test fun ocvCommittedWhileFreshTicketIsSuspendedIsNotDerivedFromOldLiveDto() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            paused.beforeSnapshot = {
                assertEquals(before, h.manager.connectedDevice)
                h.sessions.current!!.deviceService.updateOCVSettings(before.id, "liFePO4", "new-curve")
                assertEquals("liFePO4", h.manager.connectedDevice!!.ocvPreset)
            }

            assertTrue(h.container.deviceEditor.updateDevice(before.info(), "new-preset", token))
            val expected = before.updating(before.info()).copy(
                ocvPreset = "liFePO4", customOCVArrayString = "new-curve", appliedRadioPresetID = "new-preset",
            )
            assertEquals(expected, store.fetchDevice(before.id))
            assertEquals(expected, h.manager.connectedDevice)
        } finally { h.container.close() }
    }

    @Test fun ocvAfterTicketRejectsOldSaveWithoutReplacingNewerLiveState() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            paused.beforeSave = {
                h.sessions.current!!.deviceService.updateOCVSettings(before.id, "liFePO4", "new-curve")
                assertEquals(before.copy(ocvPreset = "liFePO4", customOCVArrayString = "new-curve"), h.manager.connectedDevice)
                assertNull(h.manager.connectedDevice!!.appliedRadioPresetID, "No optimistic preset publication")
            }
            assertTrue(h.container.deviceEditor.updateDevice(before.info(), "new-preset", token))
            assertEquals(1, paused.rejected, "The real Room CAS, not a fake conflict, rejected the old ticket")
            val actual = assertNotNull(store.fetchDevice(before.id))
            assertEquals("liFePO4", actual.ocvPreset)
            assertEquals("new-curve", actual.customOCVArrayString)
            assertEquals("new-preset", actual.appliedRadioPresetID)
            assertEquals(actual, h.manager.connectedDevice)
        } finally { h.container.close() }
    }

    @Test fun newerOcvPublishedAfterPresetCommitCannotBeOverwrittenByItsDelayedLivePublication() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            paused.afterSave = {
                h.sessions.current!!.deviceService.updateOCVSettings(before.id, "liFePO4", "latest")
            }
            assertTrue(h.container.deviceEditor.updateDevice(before.info(), "new-preset", token))
            assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
            assertEquals("liFePO4", h.manager.connectedDevice!!.ocvPreset)
        } finally { h.container.close() }
    }

    @Test fun ordinaryDelayedSaveAndPresetShareTheEntireSuspendingWriteOrder() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            val entered = CompletableDeferred<Unit>()
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val ordinary = assertNotNull(h.container.deviceEditor.updateDevice(before.info(868.0), null))
            entered.await()
            val presetEntered = CompletableDeferred<Unit>()
            val preset = async {
                presetEntered.complete(Unit)
                h.container.deviceEditor.updateDevice(before.info(915.0), "new-preset", token)
            }
            presetEntered.await()
            assertFalse(preset.isCompleted)
            assertFalse(ordinary.isCompleted)
            assertEquals(before, h.manager.connectedDevice, "A pending ordinary write has not published")
            release.complete(Unit)
            ordinary.join()
            assertTrue(preset.await())
            val actual = assertNotNull(store.fetchDevice(before.id))
            assertEquals(915000u, actual.frequency)
            assertEquals("new-preset", actual.appliedRadioPresetID)
            assertEquals(actual, h.manager.connectedDevice)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun ocvWriterPausedAfterItsReadCannotOverwriteACompletedPreset() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val entered = CompletableDeferred<Unit>()
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val ocv = async { h.sessions.current!!.deviceService.updateOCVSettings(before.id, "liFePO4", "latest") }
            entered.await()
            assertTrue(h.container.deviceEditor.updateDevice(before.info(), "new-preset", h.manager.snapshot.value.token!!))
            release.complete(Unit)
            ocv.await()
            val expected = before.updating(before.info()).copy(
                ocvPreset = "liFePO4", customOCVArrayString = "latest", appliedRadioPresetID = "new-preset",
            )
            assertEquals(1, paused.rejected)
            assertEquals(expected, store.fetchDevice(before.id))
            assertEquals(expected, h.manager.connectedDevice)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun rejectedOldTicketThenStorageFailureLeavesNewerOcvLiveAndPersisted() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            paused.beforeSave = {
                h.sessions.current!!.deviceService.updateOCVSettings(before.id, "liFePO4", "latest")
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_authority_retry BEFORE UPDATE ON devices " +
                        "BEGIN SELECT RAISE(FAIL, 'authority retry failure'); END",
                )
            }
            try {
                val result = runCatching {
                    h.container.deviceEditor.updateDevice(before.info(), "new-preset", h.manager.snapshot.value.token!!)
                }
                assertTrue(result.isFailure)
                assertEquals(1, paused.rejected)
                val expected = before.copy(ocvPreset = "liFePO4", customOCVArrayString = "latest")
                assertEquals(expected, store.fetchDevice(before.id))
                assertEquals(expected, h.manager.connectedDevice)
            } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_authority_retry") }
        } finally { h.container.close() }
    }

    @Test fun delayedOrdinarySaveCannotReactivateOrReplaceAnActualSuccessor() = runTest {
        val paused = PausedStore(store)
        val firstId = UUID(0, 100)
        val h = ContainerHarness(this, store, persistenceOverride = paused,
            identity = { target -> key(if ((target as com.meshcoreone.android.core.contracts.domain.ConnectionTarget.Bluetooth).deviceId == firstId) 7 else 8) })
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target(firstId))
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val entered = CompletableDeferred<Unit>()
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val ordinary = assertNotNull(h.container.deviceEditor.updateClientRepeat(true))
            entered.await()
            h.manager.switchDevice(h.target(UUID(0, 101)))
            h.settle()
            val successor = assertNotNull(h.manager.connectedDevice)
            assertTrue(successor.id != before.id)
            release.complete(Unit)
            ordinary.join()
            h.settle()
            assertEquals(successor, h.manager.connectedDevice)
            assertEquals(successor.id, store.fetchActiveDevice()!!.id)
            assertFalse(store.fetchDevice(before.id)!!.isActive)
            assertFalse(store.fetchDevice(before.id)!!.clientRepeat)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun sameTokenWatermarkConflictRebasesRoomAndLiveBeforeAcknowledgment() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            paused.beforeSave = { store.updateDeviceLastContactSync(before.radioId, 9876u) }
            assertTrue(h.container.deviceEditor.updatePathHashMode(2u, h.manager.snapshot.value.token!!))
            assertEquals(1, paused.rejected)
            val expected = before.copy(pathHashMode = 2u, lastContactSync = 9876u)
            assertEquals(expected, store.fetchDevice(before.id))
            assertEquals(expected, h.manager.connectedDevice)
        } finally { h.container.close() }
    }

    @Test fun regionConsumerRemovesDefaultAndChannelScopeWithoutAnyFullRowSave() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.links.configureRadio = { radio ->
                radio.firmwareVersion = 13u
                radio.respond = { command ->
                when (command[0].toInt()) {
                    0x3f -> listOf(Bytes.of(0))
                    0x40 -> listOf(Bytes.of(0x1c))
                    else -> null
                }
                }
            }
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice).copy(
                knownRegions = listOf("US915", "EU868").snapshot(), defaultFloodScopeName = "US915",
            )
            store.addDeviceKnownRegion(before.radioId, "US915")
            store.addDeviceKnownRegion(before.radioId, "EU868")
            store.saveDevice(before)
            assertEquals(before, store.fetchDevice(before.id), "Seed actual persisted regions, not an ignored full-row field")
            h.container.deviceEditor.updateDevice(before)
            store.saveChannel(ChannelDTO(radioId = before.radioId, index = 1u, name = "Region")
                .withFloodScope(ChannelFloodScope.Region("US915")))
            removeSettingsKnownRegion(h.container, h.sessions.current!!, "US915")
            h.settle()
            val expected = before.copy(knownRegions = listOf("EU868").snapshot(), defaultFloodScopeName = null)
            assertEquals(expected, store.fetchDevice(before.id))
            assertEquals(expected, h.manager.connectedDevice)
            assertEquals(ChannelFloodScope.Inherit, store.fetchChannel(before.radioId, 1u)!!.floodScope)
            assertTrue(h.links.links.last().radio.sent.any { it[0].toInt() == 0x3f }, "Real radio scope write occurred")
            assertTrue(h.links.links.last().radio.sent.any { it[0].toInt() == 0x40 }, "Real radio scope readback occurred")
        } finally { h.container.close() }
    }

    @Test fun regionConsumerPreservesConcurrentOcvHashAndAnUnrelatedDefault() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice).copy(
                knownRegions = listOf("US915", "EU868").snapshot(), defaultFloodScopeName = "US915",
            )
            store.addDeviceKnownRegion(before.radioId, "US915")
            store.addDeviceKnownRegion(before.radioId, "EU868")
            store.saveDevice(before)
            assertEquals(before, store.fetchDevice(before.id))
            h.container.deviceEditor.updateDevice(before)
            paused.beforeRemove = {
                h.sessions.current!!.deviceService.updateOCVSettings(before.id, "liFePO4", "latest")
                val ticket = store.fetchDeviceWriteSnapshot(before.id)!!
                assertTrue(store.saveDeviceIfUnchanged(ticket.device.copy(pathHashMode = 2u), ticket))
                h.container.deviceAccess.transformConnectedDevice(h.manager.snapshot.value.token) { it.copy(pathHashMode = 2u) }
            }
            removeSettingsKnownRegion(h.container, h.sessions.current!!, "EU868")
            val expected = before.copy(
                knownRegions = listOf("US915").snapshot(), ocvPreset = "liFePO4",
                customOCVArrayString = "latest", pathHashMode = 2u,
            )
            assertEquals(expected, store.fetchDevice(before.id))
            assertEquals(expected, h.manager.connectedDevice)
            addSettingsKnownRegion(h.container, h.sessions.current!!, "EU868")
            assertEquals(expected.copy(knownRegions = listOf("US915", "EU868").snapshot()), store.fetchDevice(before.id))
        } finally { h.container.close() }
    }

    @Test fun regionConsumerHeldAcrossSuccessorCannotPublishItsOldDto() = runTest {
        val paused = PausedStore(store)
        val firstId = UUID(0, 100)
        val h = ContainerHarness(this, store, persistenceOverride = paused,
            identity = { target -> key(if ((target as com.meshcoreone.android.core.contracts.domain.ConnectionTarget.Bluetooth).deviceId == firstId) 7 else 8) })
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target(firstId))
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val session = h.sessions.current!!
            addSettingsKnownRegion(h.container, session, "EU868")
            val originalSaved = assertNotNull(store.fetchDevice(before.id))
            val channel = ChannelDTO(radioId = before.radioId, index = 1u, name = "Region")
                .withFloodScope(ChannelFloodScope.Region("EU868"))
            store.saveChannel(channel)
            val entered = CompletableDeferred<Unit>()
            paused.beforeRemove = { entered.complete(Unit); release.await() }
            val removal = async { runCatching { removeSettingsKnownRegion(h.container, session, "EU868") } }
            entered.await()
            h.manager.switchDevice(h.target(UUID(0, 101)))
            h.settle()
            val successor = assertNotNull(h.manager.connectedDevice)
            release.complete(Unit)
            assertTrue(removal.await().exceptionOrNull() is SettingsNotConnectedException)
            assertEquals(successor, h.manager.connectedDevice)
            assertEquals(successor.id, store.fetchActiveDevice()!!.id)
            assertFalse(store.fetchDevice(before.id)!!.isActive)
            assertEquals(originalSaved.copy(isActive = false), store.fetchDevice(before.id),
                "Retired removal must not mutate the old row after a successor loaded")
            assertEquals(ChannelFloodScope.Region("EU868"), store.fetchChannel(before.radioId, 1u)!!.floodScope)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun regionConsumerStorageFailureDoesNotPublishOrLoseChannelScope() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            addSettingsKnownRegion(h.container, h.sessions.current!!, "EU868")
            val saved = assertNotNull(store.fetchDevice(before.id))
            val channel = ChannelDTO(radioId = before.radioId, index = 1u, name = "Region")
                .withFloodScope(ChannelFloodScope.Region("EU868"))
            store.saveChannel(channel)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_region_save BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'region storage failure'); END",
            )
            try {
                assertTrue(runCatching { removeSettingsKnownRegion(h.container, h.sessions.current!!, "EU868") }.isFailure)
                assertEquals(saved, store.fetchDevice(before.id))
                assertEquals(saved, h.manager.connectedDevice)
                assertEquals(ChannelFloodScope.Region("EU868"), store.fetchChannel(before.radioId, 1u)!!.floodScope)
            } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_region_save") }
        } finally { h.container.close() }
    }

    @Test fun runtimeRetirementWaitsForActivationCommitAuthorityRatherThanRacingItsLastGuard() = runTest {
        val paused = PausedStore(store)
        val firstId = UUID(0, 501)
        val h = ContainerHarness(this, store, persistenceOverride = paused,
            identity = { target -> key(if ((target as com.meshcoreone.android.core.contracts.domain.ConnectionTarget.Bluetooth).deviceId == firstId) 7 else 8) })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            paused.afterActivation = { entered.complete(Unit); release.await() }
            val connecting = async { runCatching { h.manager.connect(h.target(firstId)) } }
            entered.await()
            val committed = assertNotNull(store.fetchActiveDevice())
            val beforeRetirement = h.manager.snapshot.value
            val submitted = CompletableDeferred<Unit>()
            val retiring = async {
                submitted.complete(Unit)
                h.manager.disconnect()
            }
            submitted.await()
            yield()
            assertFalse(retiring.isCompleted, "Retirement cannot cross the actual activation lease")
            assertEquals(beforeRetirement, h.manager.snapshot.value, "No owner invalidation before the lease ends")
            assertEquals(committed, store.fetchActiveDevice(), "The real Room commit completed before retirement")
            release.complete(Unit)
            retiring.await()
            connecting.await()
            h.manager.connect(h.target(UUID(0, 502)))
            h.settle()
            val successor = assertNotNull(h.manager.connectedDevice)
            assertEquals(successor, store.fetchActiveDevice())
            assertFalse(store.fetchDevice(committed.id)!!.isActive)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun cancellationBeforeConditionalRoomCommitLeavesLiveAndStoredFieldsUnchanged() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val update = async { h.container.deviceEditor.updatePathHashMode(2u, h.sessions.current!!.token) }
            entered.await()
            update.cancel()
            update.join()
            assertTrue(update.isCancelled)
            release.complete(Unit)
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun cancellationAfterConditionalRoomCommitStillReconcilesLiveForTheOriginalToken() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            paused.afterSave = { entered.complete(Unit); release.await() }
            val update = async { h.container.deviceEditor.updatePathHashMode(2u, h.sessions.current!!.token) }
            entered.await()
            assertEquals(before.copy(pathHashMode = 2u), store.fetchDevice(before.id))
            assertEquals(before.copy(pathHashMode = 2u), h.manager.connectedDevice,
                "The committed callback runs before returning from storage")
            update.cancel()
            release.complete(Unit)
            update.join()
            assertTrue(update.isCancelled, "Cancellation still propagates after bounded committed publication")
            assertEquals(before.copy(pathHashMode = 2u), h.manager.connectedDevice)
            assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun ordinarySettingsEditsRejectTheOriginalTokenAfterASameRadioReconnect() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val original = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            h.manager.disconnect()
            h.manager.connect(h.target(original.id))
            h.settle()
            val successor = assertNotNull(h.manager.connectedDevice)
            assertEquals(token.radioId, h.sessions.current!!.token.radioId)
            assertTrue(token != h.sessions.current!!.token)
            assertFalse(h.container.deviceEditor.updateDevice(original.info(868.0), null, token))
            assertFalse(h.container.deviceEditor.updateAutoAddConfig(
                com.meshcoreone.android.core.protocol.model.AutoAddConfig(1u), token,
            ))
            assertFalse(h.container.deviceEditor.updateClientRepeat(true, token))
            assertFalse(h.container.deviceEditor.updateDefaultFloodScopeName("EU868", token))
            assertEquals(successor, h.manager.connectedDevice)
            assertEquals(successor, store.fetchDevice(successor.id))
        } finally { h.container.close() }
    }

    @Test fun cancellingAnActualRoomCommitWaitReleasesRuntimeAuthorityBeforeTheBlockingTransactionEnds() = runTest {
        val transactionRequested = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val pauseNext = AtomicBoolean(false)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val executor = java.util.concurrent.Executor { command ->
            if (pauseNext.compareAndSet(true, false)) {
                transactionRequested.complete(Unit)
                worker.execute { release.await(); command.run() }
            } else command.run()
        }
        val database = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
            com.meshcoreone.android.core.database.MeshCoreDatabase::class.java,
        ).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor(executor).build()
        val repository = RoomPersistenceStore(database, backgroundScope)
        val paused = PausedStore(repository)
        val h = ContainerHarness(this, repository, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            paused.beforeSave = { pauseNext.set(true) }
            val update = async { h.container.deviceEditor.updatePathHashMode(2u, token) }
            transactionRequested.await()
            update.cancel()
            update.join()
            assertTrue(update.isCancelled)
            assertEquals(1L, release.count, "Cancellation must not wait for the actual queued Room command")
            assertTrue(h.container.deviceAccess.withDeviceWriteAuthority(token) { true },
                "The cancelled write released its runtime lease before Room was unblocked")
            release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(before, repository.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
        } finally {
            release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            h.container.close()
            repository.close()
            database.close()
        }
    }

    @Test fun reconnectStateAndLiveClearCannotCrossAnActualRoomCommitLease() = runTest {
        val transactionRequested = CompletableDeferred<Unit>()
        val release = java.util.concurrent.CountDownLatch(1)
        val pauseNext = AtomicBoolean(false)
        val worker = java.util.concurrent.Executors.newSingleThreadExecutor()
        val executor = java.util.concurrent.Executor { command ->
            if (pauseNext.compareAndSet(true, false)) {
                transactionRequested.complete(Unit)
                worker.execute { release.await(); command.run() }
            } else command.run()
        }
        val database = androidx.room.Room.inMemoryDatabaseBuilder(
            androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>(),
            com.meshcoreone.android.core.database.MeshCoreDatabase::class.java,
        ).allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor(executor).build()
        val repository = RoomPersistenceStore(database, backgroundScope)
        val paused = PausedStore(repository)
        val h = ContainerHarness(this, repository, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            paused.beforeSave = { pauseNext.set(true) }
            val update = async { h.container.deviceEditor.updatePathHashMode(2u, token) }
            transactionRequested.await()
            val stateSubmitted = CompletableDeferred<Unit>()
            val stateChange = async {
                stateSubmitted.complete(Unit)
                h.manager.setConnectionState(com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.CONNECTING)
            }
            val clearSubmitted = CompletableDeferred<Unit>()
            val clear = async {
                clearSubmitted.complete(Unit)
                h.manager.clearConnectedDevice()
            }
            stateSubmitted.await()
            clearSubmitted.await()
            yield()
            assertFalse(stateChange.isCompleted)
            assertFalse(clear.isCompleted)
            assertTrue(h.manager.connectionState.isConnected)
            assertEquals(before, h.manager.connectedDevice)
            release.countDown()
            assertTrue(update.await())
            stateChange.await()
            clear.await()
            assertEquals(before.copy(pathHashMode = 2u), repository.fetchDevice(before.id))
            assertNull(h.manager.connectedDevice, "The later clear wins only after committed publication")
        } finally {
            release.countDown()
            worker.shutdown()
            assertTrue(worker.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            h.container.close()
            repository.close()
            database.close()
        }
    }

    @Test fun queuedTimeoutAndPairingRetirementRejectTheSuccessorRuntimeUnderTheActualAuthorityGate() = runTest {
        val firstId = UUID(0, 601)
        val h = ContainerHarness(this, store,
            identity = { target -> key(if ((target as com.meshcoreone.android.core.contracts.domain.ConnectionTarget.Bluetooth).deviceId == firstId) 7 else 8) })
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target(firstId))
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val current = h.manager.captureConnectionAuthority()
            val lease = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                h.container.deviceAccess.withDeviceWriteAuthority(h.sessions.current!!.token) {
                    entered.complete(Unit)
                    release.await()
                    true
                }
            }
            entered.await()
            val successor = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                h.manager.switchDevice(h.target(UUID(0, 602)))
            }
            val timeout = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                h.manager.transitionConnection(
                    com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.DISCONNECTED, true, current,
                )
            }
            val pairing = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                h.manager.markDisconnectedIfNotOperational()
            }
            assertFalse(successor.isCompleted)
            assertFalse(timeout.isCompleted)
            assertFalse(pairing.isCompleted)
            assertEquals(before, h.manager.connectedDevice)
            release.complete(Unit)
            assertTrue(lease.await())
            assertFalse(timeout.await(), "Original runtime authority is rechecked after the queued switch invalidates it")
            assertFalse(pairing.await(), "A queued pairing cleanup cannot publish into the successor revision")
            successor.await()
            h.settle()
            val live = assertNotNull(h.manager.connectedDevice)
            assertNotEquals(before.id, live.id)
            assertEquals(live, store.fetchActiveDevice())
            assertTrue(h.manager.connectionState.isOperational)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun removingShortOrLongUtf8AliasPreservesSelectedScopeUntilItsLastNormalizedSurvivorIsRemoved() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            // Ten three-byte code points fill the actual thirty-byte radio scope field.
            val short = "\u754c".repeat(10)
            val long = short + "-suffix"
            val unrelated = "US915"
            val token = h.sessions.current!!.token
            suspend fun seed() {
                for (name in listOf(short, long, unrelated)) store.addDeviceKnownRegion(before.radioId, name)
                val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(before.id))
                val seeded = ticket.device.copy(
                    knownRegions = listOf(short, long, unrelated).snapshot(), defaultFloodScopeName = short,
                )
                assertTrue(store.saveDeviceIfUnchanged(seeded, ticket))
                h.container.deviceEditor.updateDevice(seeded)
            }
            for ((removed, survivor) in listOf(short to long, long to short)) {
                seed()
                assertTrue(h.container.deviceEditor.persistKnownRegionRemoval(removed, token))
                val retained = assertNotNull(store.fetchDevice(before.id))
                assertEquals(listOf(survivor, unrelated), retained.knownRegions)
                assertEquals(short, retained.defaultFloodScopeName)
                assertEquals(retained, h.manager.connectedDevice)
                val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(before.id))
                val invalid = runCatching {
                    store.saveDeviceIfUnchanged(
                        retained.copy(knownRegions = listOf(unrelated).snapshot()), ticket,
                    )
                }.exceptionOrNull()
                assertTrue(invalid is com.meshcoreone.android.core.contracts.domain.PersistenceStoreException,
                    "Removing the last normalized alias still requires an explicit default clear")
                assertEquals(retained, store.fetchDevice(before.id))
                assertTrue(h.container.deviceEditor.persistKnownRegionRemoval(survivor, token))
                val cleared = assertNotNull(store.fetchDevice(before.id))
                assertEquals(listOf(unrelated), cleared.knownRegions)
                assertNull(cleared.defaultFloodScopeName)
                assertEquals(cleared, h.manager.connectedDevice)
            }
        } finally { h.container.close() }
    }

    @Test fun normalizedScopeSurvivorDecisionIsRecomputedAfterConcurrentKnownRegionChanges() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val short = "p".repeat(30)
            val long = short + "-suffix"
            val token = h.sessions.current!!.token
            suspend fun seed(regions: List<String>) {
                val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(before.id))
                val seeded = ticket.device.copy(knownRegions = regions.snapshot(), defaultFloodScopeName = short)
                assertTrue(store.saveDeviceIfUnchanged(seeded, ticket))
                h.container.deviceEditor.updateDevice(seeded)
            }
            seed(listOf(short))
            paused.beforeSave = { store.addDeviceKnownRegion(before.radioId, long) }
            assertTrue(h.container.deviceEditor.persistKnownRegionRemoval(short, token))
            val added = assertNotNull(store.fetchDevice(before.id))
            assertEquals(listOf(long), added.knownRegions)
            assertEquals(short, added.defaultFloodScopeName)
            assertEquals(added, h.manager.connectedDevice)
            seed(listOf(short, long))
            paused.beforeSave = { store.removeDeviceKnownRegion(before.radioId, long) }
            assertTrue(h.container.deviceEditor.persistKnownRegionRemoval(short, token))
            val removed = assertNotNull(store.fetchDevice(before.id))
            assertTrue(removed.knownRegions.isEmpty())
            assertNull(removed.defaultFloodScopeName)
            assertEquals(removed, h.manager.connectedDevice)
            assertEquals(2, paused.rejected, "Both intervening writes must force a fresh authoritative transform")
        } finally { h.container.close() }
    }

    @Test fun legacyRegionRemovalRecomputesBothAliasDecisionsAfterRealRoomConflicts() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            val short = "\u754c".repeat(10)
            val long = short + "-suffix"
            for (hasAlias in listOf(false, true)) {
                val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(before.id))
                val seeded = ticket.device.copy(
                    knownRegions = (if (hasAlias) listOf(short, long) else listOf(short)).snapshot(),
                    defaultFloodScopeName = short,
                )
                assertTrue(store.saveDeviceIfUnchanged(seeded, ticket))
                h.container.deviceEditor.updateDevice(seeded)
                paused.beforeSave = {
                    if (hasAlias) store.removeDeviceKnownRegion(before.radioId, long)
                    else store.addDeviceKnownRegion(before.radioId, long)
                }
                assertNotNull(h.container.deviceEditor.removeKnownRegion(short)).join()
                val saved = assertNotNull(store.fetchDevice(before.id))
                assertEquals(if (hasAlias) emptyList() else listOf(long), saved.knownRegions.toList())
                assertEquals(if (hasAlias) null else short, saved.defaultFloodScopeName)
                assertEquals(saved, h.manager.connectedDevice)
                assertEquals(token, h.manager.snapshot.value.token)
                assertTrue(h.manager.connectionState.isOperational)
            }
            assertEquals(2, paused.rejected, "The legacy Job API must also rederive both survivor decisions")
        } finally { h.container.close() }
    }

    @Test fun targetedSameAliasRemovalRebasesEditorRoomAndLiveInBothOrders() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            val short = "a".repeat(25) + "\u00e9\u754c"
            assertEquals(30, short.toByteArray(Charsets.UTF_8).size)
            val long = short + "-suffix"
            for ((removed, survivor) in listOf(short to long, long to short)) {
                val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(before.id))
                val seeded = ticket.device.copy(
                    knownRegions = listOf(short, long).snapshot(), defaultFloodScopeName = short,
                )
                assertTrue(store.saveDeviceIfUnchanged(seeded, ticket))
                h.container.deviceEditor.updateDevice(seeded)
                paused.beforeSave = {
                    store.removeDeviceKnownRegion(before.radioId, removed)
                    val targeted = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(listOf(survivor), targeted.knownRegions.toList())
                    assertEquals(short, targeted.defaultFloodScopeName)
                    assertEquals(seeded, h.manager.connectedDevice, "The direct writer has not published editor live state")
                }
                assertTrue(h.container.deviceEditor.persistKnownRegionRemoval(removed, token))
                val retained = assertNotNull(store.fetchDevice(before.id))
                assertEquals(listOf(survivor), retained.knownRegions.toList())
                assertEquals(short, retained.defaultFloodScopeName)
                assertEquals(retained, h.manager.connectedDevice)
                assertTrue(h.container.deviceEditor.persistKnownRegionRemoval(survivor, token))
                val cleared = assertNotNull(store.fetchDevice(before.id))
                assertTrue(cleared.knownRegions.isEmpty())
                assertNull(cleared.defaultFloodScopeName)
                assertEquals(cleared, h.manager.connectedDevice)
                assertEquals(token, h.manager.snapshot.value.token)
                assertTrue(h.manager.connectionState.isOperational)
            }
            assertEquals(2, paused.rejected, "Each same-target removal must invalidate the production editor's ticket")
        } finally { h.container.close() }
    }

    @Test fun differentAliasWriterRequiresRadioReadmissionBeforeEditorCanClearTheLastScope() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        var radioScope: String? = null
        try {
            h.links.configureRadio = { radio ->
                radio.firmwareVersion = 13u
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        0x3f -> {
                            radioScope = if (command.size == 1) null else
                                command.slice(1, 32).toByteArray().toString(Charsets.UTF_8).substringBefore('\u0000')
                            listOf(Bytes.of(0))
                        }
                        0x40 -> listOf(Bytes.of(0x1c) + (radioScope?.let {
                            Bytes.utf8(it).paddedOrTruncated(31) + FloodScope.Region(it).scopeKey()
                        } ?: Bytes.EMPTY))
                        else -> null
                    }
                }
            }
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val session = h.sessions.current!!
            val short = "a".repeat(25) + "\u00e9\u754c"
            val long = short + "-suffix"
            for (disabled in listOf(false, true)) {
                for ((removed, other) in listOf(short to long, long to short)) {
                    val acknowledged = if (disabled) null else short
                    assertEquals(acknowledged, session.settingsService.setDefaultFloodScopeVerified(acknowledged))
                    val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(before.id))
                    val seeded = ticket.device.copy(
                        knownRegions = listOf(short, long).snapshot(), defaultFloodScopeName = acknowledged,
                    )
                    assertTrue(store.saveDeviceIfUnchanged(seeded, ticket))
                    h.container.deviceEditor.updateDevice(seeded)
                    for ((index, name) in listOf(removed, other).withIndex()) {
                        val channelIndex = (index + 1).toUByte()
                        val channel = store.fetchChannel(before.radioId, channelIndex)
                            ?: ChannelDTO(radioId = before.radioId, index = channelIndex, name = name).also {
                                store.saveChannel(it)
                            }
                        store.setChannelFloodScope(EntityKey(before.radioId, channel.id), ChannelFloodScope.Region(name))
                        assertEquals(ChannelFloodScope.Region(name), store.fetchChannel(before.radioId, channelIndex)!!.floodScope,
                            "Each parameter case must reset its actual indexed channel before the competing removal")
                    }
                    paused.beforeSave = { store.removeDeviceKnownRegion(before.radioId, other) }
                    val outcome = h.container.deviceEditor.persistKnownRegionRemoval(removed, session.token, acknowledged)
                    if (disabled) {
                        assertEquals(KnownRegionRemovalOutcome.Removed, outcome)
                    } else {
                        assertEquals(KnownRegionRemovalOutcome.NeedsScopeReconciliation, outcome)
                        val contested = assertNotNull(store.fetchDevice(before.id))
                        assertEquals(listOf(removed), contested.knownRegions.toList())
                        assertEquals(short, contested.defaultFloodScopeName)
                        assertEquals(seeded, h.manager.connectedDevice, "A rejected projection must not publish")
                        assertEquals(ChannelFloodScope.Region(removed), store.fetchChannel(before.radioId, 1u)!!.floodScope)
                        assertEquals(ChannelFloodScope.Inherit, store.fetchChannel(before.radioId, 2u)!!.floodScope)
                        assertEquals(short, session.settingsService.getDefaultFloodScope(), "Actual fake-radio readback stays scoped")
                        assertTrue(h.container.deviceEditor.updateDefaultFloodScopeName(short, session.token))
                        assertEquals(contested, h.manager.connectedDevice, "Acknowledgment refreshes authoritative regions")
                        assertNull(session.settingsService.setDefaultFloodScopeVerified(null))
                        assertTrue(h.container.deviceEditor.updateDefaultFloodScopeName(null, session.token))
                        assertEquals(KnownRegionRemovalOutcome.Removed,
                            h.container.deviceEditor.persistKnownRegionRemoval(removed, session.token, null))
                    }
                    val saved = assertNotNull(store.fetchDevice(before.id))
                    assertTrue(saved.knownRegions.isEmpty())
                    assertNull(saved.defaultFloodScopeName)
                    assertEquals(saved, h.manager.connectedDevice)
                    assertNull(session.settingsService.getDefaultFloodScope())
                    assertNull(radioScope)
                    assertEquals(ChannelFloodScope.Inherit, store.fetchChannel(before.radioId, 1u)!!.floodScope)
                    assertEquals(ChannelFloodScope.Inherit, store.fetchChannel(before.radioId, 2u)!!.floodScope)
                    assertEquals(session.token, h.manager.snapshot.value.token)
                    assertTrue(h.manager.connectionState.isOperational)
                }
            }
            assertEquals(4, paused.rejected, "Each different-target write must reject the first actual CAS")
        } finally { h.container.close() }
    }

    @Test fun twoCommittedOcvWritesReturningToTheOriginalValueCannotBeRepublishedAsTheIntermediateValue() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val session = h.sessions.current!!
            session.deviceService.updateOCVSettings(before.id, "A", "curve-A")
            val original = assertNotNull(h.manager.connectedDevice)
            val originalView = assertNotNull(h.container.deviceAccess.captureDeviceWriteView(session.token))
            var firstWriteCommitted = false
            var secondWriteCommitted = false
            paused.beforeSnapshot = {
                session.deviceService.updateOCVSettings(before.id, "B", "curve-B")
                assertEquals("B", store.fetchDevice(before.id)!!.ocvPreset)
                assertEquals("B", h.manager.connectedDevice!!.ocvPreset)
                firstWriteCommitted = true
                paused.afterSave = {
                    assertEquals("B", store.fetchDevice(before.id)!!.ocvPreset)
                    session.deviceService.updateOCVSettings(before.id, "A", "curve-A")
                    secondWriteCommitted = true
                    assertEquals("A", store.fetchDevice(before.id)!!.ocvPreset)
                    assertEquals("A", h.manager.connectedDevice!!.ocvPreset)
                }
            }
            assertTrue(h.container.deviceEditor.updateDevice(original.info(), "aba-preset", session.token))
            assertTrue(firstWriteCommitted)
            assertTrue(secondWriteCommitted)
            val expected = original.updating(original.info()).copy(appliedRadioPresetID = "aba-preset")
            assertEquals(expected, store.fetchDevice(before.id))
            assertEquals(expected, h.manager.connectedDevice)
            assertTrue(h.container.deviceAccess.ocvPublicationRevision > originalView.ocvRevision,
                "Equal final OCV values do not erase the intervening publications")
            val revision = h.container.deviceAccess.ocvPublicationRevision
            session.deviceService.updateOCVSettings(before.id, "A", "curve-A")
            assertTrue(h.container.deviceAccess.ocvPublicationRevision > revision,
                "Even an equal-valued accepted OCV publication advances its revision")
            assertEquals(expected, store.fetchDevice(before.id))
            assertEquals(expected, h.manager.connectedDevice)
            assertEquals(session.token, h.manager.snapshot.value.token)
        } finally { h.container.close() }
    }

    @Test fun awaitedPreRepeatSnapshotAndClearFinishOnlyAfterTheirActualRoomCommits() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            val entered = CompletableDeferred<Unit>()
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val snapshot = async { h.container.deviceEditor.savePreRepeatSettings(token) }
            entered.await()
            assertFalse(snapshot.isCompleted)
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
            release.complete(Unit)
            assertTrue(snapshot.await())
            val saved = before.savingPreRepeatSettings()
            assertEquals(saved, store.fetchDevice(before.id))
            assertEquals(saved, h.manager.connectedDevice)
            assertTrue(h.container.deviceEditor.clearPreRepeatSettings(token))
            assertEquals(saved.clearingPreRepeatSettings(), store.fetchDevice(before.id))
            assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun awaitedPreRepeatSnapshotPropagatesRoomFailureWithoutPublishingASnapshot() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_pre_repeat BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'pre-repeat persistence failure'); END",
            )
            try {
                val failure = runCatching { h.container.deviceEditor.savePreRepeatSettings(h.sessions.current!!.token) }
                    .exceptionOrNull()
                assertTrue(failure is com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)
                assertEquals(before, store.fetchDevice(before.id))
                assertEquals(before, h.manager.connectedDevice)
            } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_pre_repeat") }
        } finally { h.container.close() }
    }

    @Test fun awaitedPreRepeatSnapshotCannotRetargetASuccessorWhileItsSaveIsPaused() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            val entered = CompletableDeferred<Unit>()
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val snapshot = async { h.container.deviceEditor.savePreRepeatSettings(token) }
            entered.await()
            h.manager.disconnect()
            h.manager.connect(h.target(before.id))
            h.settle()
            val successor = assertNotNull(h.manager.connectedDevice)
            assertNotEquals(token, h.manager.snapshot.value.token)
            release.complete(Unit)
            assertFalse(snapshot.await())
            assertEquals(successor, store.fetchDevice(before.id))
            assertEquals(successor, h.manager.connectedDevice)
            assertFalse(successor.hasPreRepeatSettings)
        } finally { release.complete(Unit); h.container.close() }
    }

    @Test fun staleSameSessionObservationCannotReplaceTheOriginalPreRepeatSnapshot() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            val observedBeforeSave = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            assertNull(observedBeforeSave.preRepeatFrequency)
            assertTrue(h.container.deviceEditor.savePreRepeatSettings(token))
            val firstSnapshot = observedBeforeSave.savingPreRepeatSettings()
            assertEquals(firstSnapshot, store.fetchDevice(observedBeforeSave.id))
            assertTrue(h.container.deviceEditor.updateDevice(observedBeforeSave.info(910.525), "repeat", token))
            assertTrue(h.container.deviceEditor.updateClientRepeat(true, token))
            val repeat = assertNotNull(store.fetchDevice(observedBeforeSave.id))
            assertNotEquals(repeat.frequency, repeat.preRepeatFrequency)
            assertEquals(firstSnapshot.preRepeatFrequency, repeat.preRepeatFrequency)
            assertEquals(token, h.manager.snapshot.value.token)
            assertNotEquals(repeat, repeat.savingPreRepeatSettings(), "The old unconditional transform loses restoration RF")

            // The same-session UI observation has not received either committed publication.
            assertNull(observedBeforeSave.preRepeatFrequency)
            assertFalse(observedBeforeSave.clientRepeat)
            assertTrue(h.container.deviceEditor.savePreRepeatSettings(token))
            assertEquals(repeat, store.fetchDevice(observedBeforeSave.id))
            assertEquals(repeat, h.manager.connectedDevice)
            assertNotNull(h.container.deviceEditor.savePreRepeatSettings()).join()
            assertEquals(repeat, store.fetchDevice(observedBeforeSave.id))
            assertEquals(repeat, h.manager.connectedDevice)
            assertEquals(token, h.manager.snapshot.value.token)
        } finally { h.container.close() }
    }

    @Test fun competingSnapshotCommitPreservesCompleteAndEveryPartialRestorationOnCasRetry() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val original = assertNotNull(h.manager.connectedDevice)
            val token = h.sessions.current!!.token
            val complete = original.savingPreRepeatSettings()
            val snapshots = listOf(
                complete,
                original.copy(preRepeatFrequency = complete.preRepeatFrequency),
                original.copy(preRepeatBandwidth = complete.preRepeatBandwidth),
                original.copy(preRepeatSpreadingFactor = complete.preRepeatSpreadingFactor),
                original.copy(preRepeatCodingRate = complete.preRepeatCodingRate),
            )
            for ((index, snapshot) in snapshots.withIndex()) {
                assertTrue(h.container.deviceEditor.clearPreRepeatSettings(token))
                val absent = assertNotNull(store.fetchDevice(original.id))
                assertNull(absent.preRepeatFrequency)
                assertNull(absent.preRepeatBandwidth)
                assertNull(absent.preRepeatSpreadingFactor)
                assertNull(absent.preRepeatCodingRate)
                val winner = snapshot.copy(
                    frequency = 910_525u, bandwidth = 125_000u, spreadingFactor = 9u,
                    codingRate = 6u, clientRepeat = true, appliedRadioPresetID = "repeat-$index",
                )
                assertNotEquals(winner, winner.savingPreRepeatSettings(), "The old transform must fail family $index")
                var competingCommit = false
                val rejectedBefore = paused.rejected
                paused.beforeSave = {
                    val ticket = assertNotNull(store.fetchDeviceWriteSnapshot(original.id))
                    assertEquals(absent, ticket.device)
                    assertTrue(store.saveDeviceIfUnchanged(winner, ticket))
                    h.container.deviceEditor.updateDevice(winner)
                    assertEquals(winner, store.fetchDevice(original.id))
                    competingCommit = true
                }
                assertTrue(h.container.deviceEditor.savePreRepeatSettings(token))
                assertTrue(competingCommit)
                assertEquals(rejectedBefore + 1, paused.rejected, "The original absent-snapshot ticket must lose")
                assertEquals(winner, store.fetchDevice(original.id), "Snapshot family $index must survive the retry")
                assertEquals(winner, h.manager.connectedDevice)
                assertEquals(index == 0, winner.hasPreRepeatSettings)
                assertEquals(token, h.manager.snapshot.value.token)
            }
        } finally { h.container.close() }
    }

    private fun DeviceDTO.info(frequency: Double = 915.0) = SelfInfo(
        advertisementType = 0u, txPower = txPower, maxTxPower = maxTxPower, publicKey = publicKey,
        latitude = latitude, longitude = longitude, multiAcks = multiAcks,
        advertisementLocationPolicy = advertLocationPolicy, telemetryModeEnvironment = telemetryModeEnv,
        telemetryModeLocation = telemetryModeLoc, telemetryModeBase = telemetryModeBase,
        manualAddContacts = manualAddContacts, radioFrequency = frequency, radioBandwidth = 250.0,
        radioSpreadingFactor = 10u, radioCodingRate = 5u, name = nodeName,
    )
}
