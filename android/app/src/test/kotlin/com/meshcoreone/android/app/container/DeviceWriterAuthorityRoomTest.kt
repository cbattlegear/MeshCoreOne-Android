// AndroidOnly: WP-305 Deterministic production-editor/real-Room competing writer interleavings.
package com.meshcoreone.android.app.container

import androidx.room.withTransaction
import com.meshcoreone.android.app.container.onboarding.AppStateRadioConfigurator
import com.meshcoreone.android.app.navigation.addSettingsKnownRegion
import com.meshcoreone.android.app.navigation.removeSettingsKnownRegion
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceAccess
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.device.RadioPresetMatcher
import com.meshcoreone.android.core.contracts.domain.DeviceEditPersisting
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.SelfInfo
import com.meshcoreone.android.core.services.device.RadioPresets
import com.meshcoreone.android.feature.onboarding.OnboardingPresetTarget
import com.meshcoreone.android.feature.settings.device.SettingsNotConnectedException
import java.time.Instant
import java.util.Collections
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
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
        var beforeAdd: suspend () -> Unit = {}
        var beforeRemove: suspend () -> Unit = {}
        var rejected = 0
        override suspend fun insertDeviceIfAbsent(dto: DeviceDTO): Boolean = room.insertDeviceIfAbsent(dto)
        override suspend fun activateDeviceIfCurrent(id: UUID, isCurrent: () -> Boolean): Boolean =
            room.activateDeviceIfCurrent(id, isCurrent)

        override suspend fun fetchDeviceWriteSnapshot(id: UUID): DeviceWriteSnapshot? {
            val hook = beforeSnapshot
            beforeSnapshot = {}
            hook()
            return room.fetchDeviceWriteSnapshot(id)
        }

        override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
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

        override suspend fun addDeviceKnownRegion(radioId: RadioId, region: String) {
            val hook = beforeAdd
            beforeAdd = {}
            hook()
            room.addDeviceKnownRegion(radioId, region)
        }

        override suspend fun removeDeviceKnownRegion(radioId: RadioId, region: String) {
            val hook = beforeRemove
            beforeRemove = {}
            hook()
            room.removeDeviceKnownRegion(radioId, region)
        }
    }

    private fun editorFor(
        access: ConnectedDeviceAccess,
        persistence: PausedStore,
        scope: CoroutineScope,
    ) = ConnectedDeviceEditor(
        access, persistence, persistence, { null },
        RadioPresetMatcher { _, _, _, _ -> emptySet() },
        scope, { Instant.EPOCH },
    )

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
        var current = true
        var guardCalls = 0
        val transaction = async {
            db.withTransaction { entered.complete(Unit); release.await() }
        }
        try {
            entered.await()
            val submitted = CompletableDeferred<Unit>()
            val activation = async {
                submitted.complete(Unit)
                store.activateDeviceIfCurrent(original.id) { guardCalls++; current }
            }
            submitted.await()
            assertEquals(0, guardCalls, "The guard must run inside Room, not before waiting for its transaction")
            current = false
            release.complete(Unit)
            assertFalse(activation.await())
            transaction.await()
            assertEquals(1, guardCalls)
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

    @Test fun capturedOldGenerationCannotWriteAfterSameIdSuccessorInstallsBeforeAdmission() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val admissionEntered = CompletableDeferred<Unit>()
        val releaseAdmission = CompletableDeferred<Unit>()
        var capturedToken: SessionToken? = null
        var capturedDeviceId: UUID? = null
        try {
            val pairingId = UUID.randomUUID()
            h.manager.connect(h.target(pairingId))
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val oldToken = assertNotNull(h.manager.snapshot.value.token)
            val managerAccess = h.container.deviceAccess
            val delayedAdmission = object : ConnectedDeviceAccess by managerAccess {
                override suspend fun <T : Any> withDeviceWrite(
                    token: SessionToken,
                    deviceId: UUID,
                    block: suspend () -> T,
                ): T? {
                    capturedToken = token
                    capturedDeviceId = deviceId
                    admissionEntered.complete(Unit)
                    releaseAdmission.await()
                    return managerAccess.withDeviceWrite(token, deviceId, block)
                }
            }
            val editor = editorFor(delayedAdmission, paused, this)
            val write = assertNotNull(editor.updateDevice(before.info(), "stale-generation-preset"))
            admissionEntered.await()
            assertEquals(oldToken, capturedToken)
            assertEquals(before.id, capturedDeviceId)

            h.manager.disconnect()
            h.manager.connect(h.target(pairingId))
            h.settle()
            val successor = assertNotNull(h.manager.connectedDevice)
            val successorToken = assertNotNull(h.manager.snapshot.value.token)
            assertEquals(before.id, successor.id)
            assertEquals(before.radioId, successor.radioId)
            assertTrue(oldToken != successorToken)
            val persistedSuccessor = assertNotNull(store.fetchDevice(successor.id))

            releaseAdmission.complete(Unit)
            write.join()

            assertEquals(persistedSuccessor, store.fetchDevice(successor.id), "Stale admission performs no Room write")
            assertEquals(successor, h.manager.connectedDevice, "A same-ID successor is not republished from G1")
        } finally {
            releaseAdmission.complete(Unit)
            h.container.close()
        }
    }

    @Test fun retirementWaitsForAdmittedDeviceWriteThroughRoomCommitWithoutSuccessor() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val write = async {
                h.container.deviceEditor.updateDevice(before.info(), "committed-before-retirement", token)
            }
            entered.await()

            val retirement = async { h.manager.disconnect() }
            testScheduler.runCurrent()
            assertFalse(retirement.isCompleted, "Retirement cannot finish while the admitted Room write is held")

            release.complete(Unit)
            assertFalse(write.await(), "A commit after logical detach is not reported as live publication")
            retirement.await()

            assertEquals(
                "committed-before-retirement",
                store.fetchDevice(before.id)!!.appliedRadioPresetID,
                "Room commit completes before teardown releases the generation",
            )
            assertNull(h.manager.connectedDevice)
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }

    @Test fun admittedDeviceWriteHoldsSuccessorUntilRoomCommitAndCannotPublishIntoIt() = runTest {
        val paused = PausedStore(store)
        val firstId = UUID(0, 501)
        val successorId = UUID(0, 502)
        val h = ContainerHarness(
            this, store, persistenceOverride = paused,
            identity = { target ->
                key(if ((target as com.meshcoreone.android.core.contracts.domain.ConnectionTarget.Bluetooth).deviceId == firstId) 7 else 8)
            },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target(firstId))
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val write = async {
                h.container.deviceEditor.updateDevice(before.info(), "before-successor", token)
            }
            entered.await()

            val successorInstall = async { h.manager.switchDevice(h.target(successorId)) }
            testScheduler.runCurrent()
            assertFalse(successorInstall.isCompleted, "Successor installation waits for the admitted Room result")

            release.complete(Unit)
            assertFalse(write.await(), "A retired generation is not reported as live publication")
            successorInstall.await()
            h.settle()

            val successor = assertNotNull(h.manager.connectedDevice)
            assertTrue(successor.id != before.id)
            assertNull(store.fetchDevice(successor.id)!!.appliedRadioPresetID)
            assertEquals("before-successor", store.fetchDevice(before.id)!!.appliedRadioPresetID)
            assertEquals(successor, h.manager.connectedDevice)
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }

    @Test fun retirementDrainsAnAdmittedDeviceWriteThroughRoomRollback() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val trigger = "reject_retiring_device_write"
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            paused.beforeSave = { entered.complete(Unit); release.await() }
            val write = async {
                runCatching { h.container.deviceEditor.updateDevice(before.info(), "must-roll-back", token) }
            }
            entered.await()

            val retirement = async { h.manager.disconnect() }
            testScheduler.runCurrent()
            assertFalse(retirement.isCompleted, "Retirement remains drained through the pending transaction")
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER $trigger BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'retiring device write rejected'); END",
            )

            release.complete(Unit)
            assertTrue(write.await().isFailure)
            retirement.await()

            assertNull(store.fetchDevice(before.id)!!.appliedRadioPresetID)
            assertNull(h.manager.connectedDevice)
        } finally {
            release.complete(Unit)
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS $trigger")
            h.container.close()
        }
    }

    @Test fun directKnownRegionAdditionDrainsBeforeNoSuccessorTeardown() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            paused.beforeAdd = { entered.complete(Unit); release.await() }
            val edit = assertNotNull(h.container.deviceEditor.addKnownRegion("US915"))
            entered.await()

            val retirement = async { h.manager.disconnect() }
            testScheduler.runCurrent()
            assertFalse(retirement.isCompleted, "The direct region DAO write is part of the generation lease")
            release.complete(Unit)
            edit.join()
            retirement.await()

            assertEquals(listOf("US915"), store.fetchDevice(before.id)!!.knownRegions)
            assertNull(h.manager.connectedDevice)
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }

    @Test fun directKnownRegionRemovalDrainsBeforeSuccessorInstallation() = runTest {
        val paused = PausedStore(store)
        val firstId = UUID(0, 601)
        val successorId = UUID(0, 602)
        val h = ContainerHarness(
            this, store, persistenceOverride = paused,
            identity = { target ->
                key(if ((target as com.meshcoreone.android.core.contracts.domain.ConnectionTarget.Bluetooth).deviceId == firstId) 7 else 8)
            },
        )
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target(firstId))
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            store.addDeviceKnownRegion(before.radioId, "US915")
            h.container.deviceEditor.updateDevice(before.copy(knownRegions = listOf("US915").snapshot()))
            paused.beforeRemove = { entered.complete(Unit); release.await() }
            val edit = assertNotNull(h.container.deviceEditor.removeKnownRegion("US915"))
            entered.await()

            val successorInstall = async { h.manager.switchDevice(h.target(successorId)) }
            testScheduler.runCurrent()
            assertFalse(successorInstall.isCompleted, "The direct region DAO write is part of the generation lease")
            release.complete(Unit)
            edit.join()
            successorInstall.await()
            h.settle()

            assertEquals(emptyList<String>(), store.fetchDevice(before.id)!!.knownRegions)
            assertTrue(h.manager.connectedDevice!!.id != before.id)
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }

    @Test fun explicitlyGuardOffEditorKeepsLegacyUnboundPersistence() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val managerAccess = h.container.deviceAccess
            val guardOffAccess = object : ConnectedDeviceAccess by managerAccess {
                override val requiresGenerationDeviceWriteLease: Boolean get() = false
                override val connectedDeviceToken: SessionToken? get() = null
                override suspend fun <T : Any> withDeviceWrite(
                    token: SessionToken,
                    deviceId: UUID,
                    block: suspend () -> T,
                ): T? = error("The explicit legacy path must not request a generation lease")
            }
            val editor = editorFor(guardOffAccess, paused, this)
            assertNotNull(editor.updateDevice(before.info(), "legacy-guard-off")).join()
            assertEquals("legacy-guard-off", store.fetchDevice(before.id)!!.appliedRadioPresetID)
            assertEquals("legacy-guard-off", h.manager.connectedDevice!!.appliedRadioPresetID)
        } finally {
            h.container.close()
        }
    }

    @Test fun guardedCaptureFailureRefusesEveryEditorWriteWithoutLegacyFallback() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        try {
            h.manager.connect(h.target())
            h.settle()
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            val managerAccess = h.container.deviceAccess
            val unavailableCapture = object : ConnectedDeviceAccess by managerAccess {
                override val requiresGenerationDeviceWriteLease: Boolean get() = true
                override fun captureDeviceWrite(): Pair<SessionToken, DeviceDTO>? = null
                override suspend fun <T : Any> withDeviceWrite(
                    token: SessionToken,
                    deviceId: UUID,
                    block: suspend () -> T,
                ): T? = error("A missing guarded capture must be refused before lease admission")
            }
            val editor = editorFor(unavailableCapture, paused, this)

            assertNull(editor.updateDevice(before.info(), "must-not-write"))
            assertFalse(editor.updateDevice(before.info(), "must-not-write", token))
            assertNull(editor.addKnownRegion("US915"))
            assertNull(editor.removeKnownRegion("US915"))

            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
        } finally {
            h.container.close()
        }
    }

    @Test fun concurrentOnboardingPresetsSerializeRadioAndDeviceMetadata() = runTest {
        val paused = PausedStore(store)
        val h = ContainerHarness(this, store, persistenceOverride = paused)
        val radioFrequencyKHz = AtomicInteger(915_000)
        val radioWrites = Collections.synchronizedList(mutableListOf<UInt>())
        val deviceWriteEntered = CompletableDeferred<Unit>()
        val releaseDeviceWrite = CompletableDeferred<Unit>()
        try {
            h.links.configureRadio = { radio ->
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        0x0b -> {
                            val frequency = (0..3).fold(0u) { value, index ->
                                value or (command[index + 1].toUInt() shl (index * 8))
                            }
                            radioFrequencyKHz.set(frequency.toInt())
                            radioWrites += frequency
                            listOf(Bytes.of(0))
                        }
                        0x01 -> listOf(
                            Bytes.of(5, 1, 22, 22) + key(7) + le32(0) + le32(0) + Bytes.of(0, 0, 2, 0) +
                                le32(radioFrequencyKHz.get().toLong()) + le32(250_000) + Bytes.of(10, 5) +
                                Bytes.utf8("TestNode"),
                        )
                        else -> null
                    }
                }
            }
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()

            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.manager.snapshot.value.token)
            val target = OnboardingPresetTarget(token.epoch.value, token.generation.value, token.radioId.value)
            val firstPreset = RadioPresets.all.first().copy(id = "first-preset", frequencyMHz = 868.0)
            val secondPreset = RadioPresets.all.first().copy(id = "second-preset", frequencyMHz = 915.0)
            val configurator = AppStateRadioConfigurator(h.container)
            radioWrites.clear()
            paused.beforeSave = { deviceWriteEntered.complete(Unit); releaseDeviceWrite.await() }
            fun radioWritesSnapshot() = synchronized(radioWrites) { radioWrites.toList() }

            val first = async(start = CoroutineStart.UNDISPATCHED) {
                configurator.applyPreset(firstPreset, target)
            }
            deviceWriteEntered.await()
            val secondStarted = CompletableDeferred<Unit>()
            val second = async(start = CoroutineStart.UNDISPATCHED) {
                secondStarted.complete(Unit)
                configurator.applyPreset(secondPreset, target)
            }
            secondStarted.await()
            h.settle()

            assertEquals(listOf(firstPreset.frequencyKHz), radioWritesSnapshot())
            assertFalse(second.isCompleted, "The second radio write waits for the first device metadata commit")

            releaseDeviceWrite.complete(Unit)
            first.await()
            second.await()
            h.settle()

            assertEquals(listOf(firstPreset.frequencyKHz, secondPreset.frequencyKHz), radioWritesSnapshot())
            val saved = assertNotNull(store.fetchDevice(before.id))
            assertEquals(secondPreset.frequencyKHz, saved.frequency)
            assertEquals(secondPreset.id, saved.appliedRadioPresetID)
            assertEquals(saved, h.manager.connectedDevice)
        } finally {
            releaseDeviceWrite.complete(Unit)
            h.container.close()
        }
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
            store.saveDevice(before)
            store.addDeviceKnownRegion(before.radioId, "US915")
            store.addDeviceKnownRegion(before.radioId, "EU868")
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
            store.saveDevice(before)
            store.addDeviceKnownRegion(before.radioId, "US915")
            store.addDeviceKnownRegion(before.radioId, "EU868")
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

    private fun DeviceDTO.info(frequency: Double = 915.0) = SelfInfo(
        advertisementType = 0u, txPower = txPower, maxTxPower = maxTxPower, publicKey = publicKey,
        latitude = latitude, longitude = longitude, multiAcks = multiAcks,
        advertisementLocationPolicy = advertLocationPolicy, telemetryModeEnvironment = telemetryModeEnv,
        telemetryModeLocation = telemetryModeLoc, telemetryModeBase = telemetryModeBase,
        manualAddContacts = manualAddContacts, radioFrequency = frequency, radioBandwidth = 250.0,
        radioSpreadingFactor = 10u, radioCodingRate = 5u, name = nodeName,
    )
}
