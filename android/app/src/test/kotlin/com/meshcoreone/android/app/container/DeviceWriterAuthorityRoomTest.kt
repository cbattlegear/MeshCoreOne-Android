// AndroidOnly: WP-305 Deterministic production-editor/real-Room competing writer interleavings.
package com.meshcoreone.android.app.container

import androidx.room.withTransaction
import com.meshcoreone.android.app.navigation.addSettingsKnownRegion
import com.meshcoreone.android.app.navigation.removeSettingsKnownRegion
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.SelfInfo
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
            val accepted = room.saveDeviceIfUnchanged(dto, expected)
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
            release.complete(Unit)
            update.join()
            assertTrue(update.isCancelled)
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
            assertEquals(before, h.manager.connectedDevice)
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

    private fun DeviceDTO.info(frequency: Double = 915.0) = SelfInfo(
        advertisementType = 0u, txPower = txPower, maxTxPower = maxTxPower, publicKey = publicKey,
        latitude = latitude, longitude = longitude, multiAcks = multiAcks,
        advertisementLocationPolicy = advertLocationPolicy, telemetryModeEnvironment = telemetryModeEnv,
        telemetryModeLocation = telemetryModeLoc, telemetryModeBase = telemetryModeBase,
        manualAddContacts = manualAddContacts, radioFrequency = frequency, radioBandwidth = 250.0,
        radioSpreadingFactor = 10u, radioCodingRate = 5u, name = nodeName,
    )
}
