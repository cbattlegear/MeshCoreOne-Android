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
import com.meshcoreone.android.core.contracts.domain.RepeatSnapshotReconciliationRequired
import com.meshcoreone.android.core.contracts.domain.RepeatSnapshotClearRequired
import com.meshcoreone.android.core.contracts.domain.RepeatSnapshotRestoreAuthority
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.connectivity.device.ConnectedDeviceEditor
import com.meshcoreone.android.core.connectivity.device.DeviceEditPersistenceConflict
import com.meshcoreone.android.core.connectivity.device.RadioPresetMatcher
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.session.RadioWritePermit
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.*
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsRepeatPersistenceTest : RoomProcessTest() {
    @Test fun advancedApplyPreservesRestoredRfUnlessExplicitlyEditedAfterRepeatOffProjectionFailure() = runTest {
        for ((baselineBandwidth, editAfterFailure) in listOf(250_000u to false, 250_000u to true, 7_799u to false)) {
        var rejectRestoreProjections = false
        var rejectedRepeatOff = false
        var rejectedRestoredRf = false
        val failing = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (rejectRestoreProjections && expected.device.clientRepeat &&
                    (!dto.clientRepeat || dto.frequency != expected.device.frequency)
                ) {
                    if (!dto.clientRepeat) rejectedRepeatOff = true
                    if (dto.frequency != expected.device.frequency) rejectedRestoredRf = true
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Repeat restore storage unavailable"))
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        var dependencies: SettingsFeatureDependencies? = null
        withRepeat(failing, exposeDependencies = { dependencies = it }) { h, holder, failures, radio ->
            val session = assertNotNull(h.sessions.current)
            if (baselineBandwidth != 250_000u) {
                val current = assertNotNull(h.manager.connectedDevice)
                session.settingsService.setRadioParamsPersisted(
                    current.frequency, baselineBandwidth, current.spreadingFactor, current.codingRate,
                    false, expectedDevice = current,
                )
                h.eventually("the actual non-option baseline is durable and observed") {
                    holder.state.value.device?.bandwidth == baselineBandwidth
                }
            }
            val before = assertNotNull(h.manager.connectedDevice)
            assertEquals(baselineBandwidth, before.bandwidth)
            holder.onRepeatToggled(true)
            holder.confirmEnableRepeatMode()
            h.eventually("Repeat is enabled before the verified-off projection failure") {
                !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true
            }
            val deps = assertNotNull(dependencies)
            val advancedFailures = mutableListOf<Throwable>()
            val advanced = AdvancedRadioStateHolder(
                SettingsEnvironment(backgroundScope, describe = { advancedFailures += it; UiText.Verbatim("Radio failed") }),
                deps.connection, deps.settingsService, RadioWriteGate(),
            )
            advanced.start()
            try {
                h.eventually("Advanced observes the enabled Repeat device") {
                    advanced.state.value.hasLoaded && advanced.state.value.device?.clientRepeat == true
                }
                advanced.onBandwidthSelected(125_000u)
                advanced.onSpreadingFactorSelected(9)
                advanced.onCodingRateSelected(7)
                advanced.apply(session.token)
                h.eventually("deliberate Repeat-on RF edits reach radio and Room") {
                    !advanced.state.value.isApplying && advanced.state.value.showSuccess &&
                        holder.state.value.device?.bandwidth == 125_000u
                }
                assertTrue(advancedFailures.isEmpty())
                val repeating = assertNotNull(store.fetchDevice(before.id))
                assertTrue(repeating.frequency != before.frequency)
                assertEquals(125_000u, repeating.bandwidth)
                assertEquals(9u.toUByte(), repeating.spreadingFactor)
                assertEquals(7u.toUByte(), repeating.codingRate)
                rejectRestoreProjections = true
                holder.onRepeatToggled(false)
                h.eventually("verified off failed its first actual Room projection") {
                    !holder.state.value.isApplyingRepeat && failures.isNotEmpty()
                }
                h.eventually("both restore publications encounter the actual storage outage") {
                    rejectedRepeatOff && rejectedRestoredRf
                }
                assertFalse(holder.state.value.isRepeatEnabled)
                assertNotNull(holder.state.value.errorMessage)
                assertEquals(repeating, store.fetchDevice(before.id))
                assertEquals(repeating, h.manager.connectedDevice)
                rejectRestoreProjections = false
                assertEquals(before.frequency, radio.frequency)
                assertFalse(session.settingsService.queryDevice().clientRepeat)
                assertTrue(session.settingsService.radioSettingsNeedsRecovery)
                h.eventually("Advanced captures the still-pinned old Repeat frequency") {
                    advanced.state.value.hasLoaded && advanced.state.value.device == repeating
                }
                assertFalse(advanced.state.value.frequencyEditable)
                assertEquals(repeating.frequency.toDouble() / 1000.0, advanced.state.value.frequency)
                if (editAfterFailure) {
                    advanced.onBandwidthSelected(62_500u)
                    advanced.onSpreadingFactorSelected(8)
                    advanced.onCodingRateSelected(6)
                }
                advanced.onTxPowerTextChanged("10")
                advanced.apply(session.token)
                h.eventually("TX-only Apply completes actual recovery and both durable writes") {
                    !advanced.state.value.isApplying && advanced.state.value.showSuccess
                }
                assertEquals(before.frequency, radio.frequency, "TX-only Apply must not replay the disabled Repeat frequency")
                assertFalse(session.settingsService.queryDevice().clientRepeat)
                assertTrue(advancedFailures.isEmpty())
                assertNull(advanced.state.value.errorMessage)
                assertFalse(session.settingsService.radioSettingsNeedsRecovery)
                val saved = assertNotNull(store.fetchDevice(before.id))
                assertEquals(before.frequency, saved.frequency)
                val expectedBandwidth = if (editAfterFailure) 62_500u else before.bandwidth
                val expectedSf = if (editAfterFailure) 8u.toUByte() else before.spreadingFactor
                val expectedCr = if (editAfterFailure) 6u.toUByte() else before.codingRate
                assertEquals(expectedBandwidth, saved.bandwidth)
                assertEquals(expectedSf, saved.spreadingFactor)
                assertEquals(expectedCr, saved.codingRate)
                assertEquals(10.toByte(), saved.txPower)
                assertFalse(saved.clientRepeat)
                assertEquals(repeating.preRepeatFrequency, saved.preRepeatFrequency)
                assertEquals(repeating.preRepeatBandwidth, saved.preRepeatBandwidth)
                assertEquals(repeating.preRepeatSpreadingFactor, saved.preRepeatSpreadingFactor)
                assertEquals(repeating.preRepeatCodingRate, saved.preRepeatCodingRate)
                assertEquals(saved, h.manager.connectedDevice)
                val actual = session.settingsService.getSelfInfo()
                assertEquals(before.frequency.toDouble() / 1000.0, actual.radioFrequency)
                assertEquals(expectedBandwidth.toDouble() / 1000.0, actual.radioBandwidth)
                assertEquals(expectedSf, actual.radioSpreadingFactor)
                assertEquals(expectedCr, actual.radioCodingRate)
                h.eventually("Advanced fields reflect the restored frequency and committed power") {
                    advanced.state.value.device == saved && !advanced.state.value.settingsModified
                }
                assertEquals(session.token, advanced.state.value.sessionToken)
                val initialFrames = if (baselineBandwidth == 250_000u) emptyList() else listOf(0)
                assertEquals(initialFrames + listOf(1, 1, 0, 0), radio.radioWrites.map { it[11].toInt() })
                assertEquals(
                    PacketBuilder.setRadio(
                        before.frequency.toDouble() / 1000.0, before.bandwidth.toDouble() / 1000.0,
                        before.spreadingFactor, before.codingRate, false,
                    ),
                    radio.radioWrites[initialFrames.size + 2],
                    "The actual disable restored every original RF field before its publication failed",
                )
                assertEquals(
                    PacketBuilder.setRadio(
                        before.frequency.toDouble() / 1000.0, expectedBandwidth.toDouble() / 1000.0,
                        expectedSf, expectedCr, false,
                    ),
                    radio.radioWrites.last(),
                )
                assertEquals(2, radio.powerWrites.size)
                assertEquals(10u.toUByte(), radio.powerWrites.last()[1])
            } finally { advanced.stop() }
        }
        }
    }

    @Test fun normalPresetRejectsRepeatModeChangedByFailedEnableReconciliation() = runTest {
        var rejectRepeatOn = false
        val failing = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (rejectRepeatOn && !expected.device.clientRepeat && dto.clientRepeat) {
                    rejectRepeatOn = false
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Repeat enable projection unavailable"))
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        withRepeat(failing) { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val service = assertNotNull(h.sessions.current).settingsService
            rejectRepeatOn = true
            holder.onRepeatToggled(true)
            holder.confirmEnableRepeatMode()
            h.eventually("failed enable releases the action with its old false projection") {
                !holder.state.value.isApplyingRepeat && failures.isNotEmpty() &&
                    holder.state.value.device?.clientRepeat == false
            }
            assertFalse(rejectRepeatOn, "The real Repeat-on Room projection was rejected")
            assertFalse(holder.state.value.isRepeatEnabled)
            assertTrue(service.radioSettingsNeedsRecovery)
            assertTrue(service.queryDevice().clientRepeat, "Firmware enabled Repeat before persistence failed")
            val failedEnable = assertNotNull(store.fetchDevice(before.id))
            assertFalse(failedEnable.clientRepeat)
            assertEquals(before.frequency, failedEnable.preRepeatFrequency)
            assertEquals(before.bandwidth, failedEnable.preRepeatBandwidth)
            assertEquals(before.spreadingFactor, failedEnable.preRepeatSpreadingFactor)
            assertEquals(before.codingRate, failedEnable.preRepeatCodingRate)
            assertEquals(failedEnable, h.manager.connectedDevice)
            val previousFailures = failures.size

            holder.onPresetSelected("eu-narrow")
            h.eventually("normal selection finishes reconciliation before deciding whether to write") {
                !holder.state.value.isApplying && !service.radioSettingsNeedsRecovery
            }
            assertEquals(1, radio.radioWrites.size, "A stale normal selection cannot send a Repeat-off SET")
            val mismatch = assertIs<SettingsServiceException>(failures.drop(previousFailures).single())
            assertIs<SettingsServiceError.VerificationFailed>(mismatch.error)
            assertNotNull(holder.state.value.errorMessage)
            assertTrue(service.queryDevice().clientRepeat)
            h.eventually("recovered Repeat-on is rendered") {
                holder.state.value.device?.clientRepeat == true && holder.state.value.isRepeatEnabled
            }
            val recovered = assertNotNull(store.fetchDevice(before.id))
            assertTrue(recovered.clientRepeat)
            assertEquals(radio.frequency, recovered.frequency)
            assertEquals(failedEnable.preRepeatFrequency, recovered.preRepeatFrequency)
            assertEquals(failedEnable.preRepeatBandwidth, recovered.preRepeatBandwidth)
            assertEquals(failedEnable.preRepeatSpreadingFactor, recovered.preRepeatSpreadingFactor)
            assertEquals(failedEnable.preRepeatCodingRate, recovered.preRepeatCodingRate)
            assertEquals(recovered, h.manager.connectedDevice)

            holder.onRepeatToggled(false)
            h.eventually("an explicit disable restores and clears the retained baseline") {
                !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == false &&
                    holder.state.value.device?.preRepeatFrequency == null
            }
            val restored = assertNotNull(store.fetchDevice(before.id))
            assertEquals(before.frequency, restored.frequency)
            assertEquals(before.bandwidth, restored.bandwidth)
            assertEquals(before.spreadingFactor, restored.spreadingFactor)
            assertEquals(before.codingRate, restored.codingRate)
            assertNull(restored.preRepeatFrequency)
            assertNull(restored.preRepeatBandwidth)
            assertNull(restored.preRepeatSpreadingFactor)
            assertNull(restored.preRepeatCodingRate)
            holder.onPresetSelected("eu-narrow")
            h.eventually("a freshly selected normal preset succeeds after explicit disable") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null &&
                    h.manager.connectedDevice?.appliedRadioPresetID == "eu-narrow"
            }
            val saved = assertNotNull(store.fetchDevice(before.id))
            assertFalse(saved.clientRepeat)
            assertFalse(service.queryDevice().clientRepeat)
            assertEquals(869_618u, saved.frequency)
            assertEquals(saved, h.manager.connectedDevice)
            assertEquals(listOf(12, 12, 11), radio.radioWrites.map { it.size })
            assertEquals(listOf(1, 0), radio.radioWrites.take(2).map { it[11].toInt() })
        }
    }

    @Test fun normalPresetRoomFailureReachesTheActionAndReselectionRetries() = runTest {
        withRepeat(store) { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val service = assertNotNull(h.sessions.current).settingsService
            assertTrue(holder.state.value.presets.any { it.id == "eu-narrow" })
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_normal_preset BEFORE UPDATE ON devices " +
                    "BEGIN SELECT RAISE(FAIL, 'preset storage failure'); END",
            )
            try {
                holder.onPresetSelected("eu-narrow")
                h.eventually("normal preset persistence error reaches the actual action") {
                    !holder.state.value.isApplying && failures.isNotEmpty()
                }
                assertNotNull(holder.state.value.errorMessage)
                assertEquals(869_618u, radio.frequency)
                assertEquals(before, store.fetchDevice(before.id))
                assertEquals(before, h.manager.connectedDevice)
                assertTrue(service.radioSettingsNeedsRecovery)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_normal_preset")
            }
            holder.onPresetSelected("eu-narrow")
            h.eventually("same normal preset reconciles and persists") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null &&
                    !service.radioSettingsNeedsRecovery
            }
            val saved = assertNotNull(store.fetchDevice(before.id))
            assertEquals(869_618u, saved.frequency)
            assertEquals("eu-narrow", saved.appliedRadioPresetID)
            assertEquals(saved, h.manager.connectedDevice)
            assertEquals(before.lastContactSync, saved.lastContactSync)
            assertEquals(before.pathHashMode, saved.pathHashMode)
            assertFalse(service.pathHashModeNeedsRecovery)
            assertEquals(2, radio.radioWrites.size)
        }
    }

    @Test fun failedPresetHashIsReconciledBeforeApplyingAPresetWithoutAHash() = runTest {
        withRepeat(store) { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val service = assertNotNull(h.sessions.current).settingsService
            assertTrue(holder.state.value.presets.any { it.id == "ca" })
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_preset_hash BEFORE UPDATE ON devices WHEN NEW.pathHashMode != OLD.pathHashMode " +
                    "BEGIN SELECT RAISE(FAIL, 'preset hash storage failure'); END",
            )
            try {
                holder.onPresetSelected("ca")
                h.eventually("optional preset hash failure reaches the action") {
                    !holder.state.value.isApplying && failures.isNotEmpty()
                }
                assertNotNull(holder.state.value.errorMessage)
                assertEquals(2u.toUByte(), radio.pathHashMode)
                assertEquals(before.pathHashMode, store.fetchDevice(before.id)!!.pathHashMode)
                assertTrue(service.pathHashModeNeedsRecovery)
                assertTrue(service.radioSettingsNeedsRecovery)
            } finally {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_preset_hash")
            }
            holder.onPresetSelected("eu-narrow")
            h.eventually("hash recovery and subsequent no-hash preset settle both revisions") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null &&
                    !service.pathHashModeNeedsRecovery && !service.radioSettingsNeedsRecovery
            }
            val saved = assertNotNull(store.fetchDevice(before.id))
            assertEquals(2u.toUByte(), saved.pathHashMode)
            assertEquals(869_618u, saved.frequency)
            assertEquals("eu-narrow", saved.appliedRadioPresetID)
            assertEquals(saved, h.manager.connectedDevice)
            assertEquals(1, h.links.radios.single().sent.count { it[0] == 0x3d.toUByte() })
        }
    }

    @Test fun normalPresetWaitsForOptionalHashRoomAcknowledgementAndSettlesBothRevisions() = runTest {
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
        try {
            withRepeat(held) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val service = assertNotNull(h.sessions.current).settingsService
                armed = true
                holder.onPresetSelected("ca")
                h.eventually("normal preset hash reached actual held Room writer") { entered.isCompleted }
                assertTrue(holder.state.value.isApplying)
                assertEquals(2u.toUByte(), radio.pathHashMode)
                assertEquals(before.pathHashMode, store.fetchDevice(before.id)!!.pathHashMode)
                assertTrue(service.radioSettingsNeedsRecovery)
                assertTrue(service.pathHashModeNeedsRecovery)
                release.complete(Unit)
                h.eventually("normal preset waits for both durable projections") {
                    !holder.state.value.isApplying && !service.radioSettingsNeedsRecovery &&
                        !service.pathHashModeNeedsRecovery
                }
                assertTrue(failures.isEmpty())
                val saved = assertNotNull(store.fetchDevice(before.id))
                assertEquals("ca", saved.appliedRadioPresetID)
                assertEquals(2u.toUByte(), saved.pathHashMode)
                assertEquals(saved, h.manager.connectedDevice)
            }
        } finally { release.complete(Unit) }
    }

    @Test fun retiredNormalPresetPublicationsCannotClaimSuccessAndFreshRetrySettlesBothRevisions() = runTest {
        withRepeat(store) { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val service = assertNotNull(h.sessions.current).settingsService
            val retired = service.events()
            holder.onPresetSelected("ca")
            h.eventually("normal preset verified but its publications have no consumer") {
                radio.pathHashMode == 2u.toUByte() && holder.state.value.isApplying
            }
            assertEquals(before, store.fetchDevice(before.id))
            h.appState.wireServicesIfConnected()
            h.eventually("normal preset subscription retirement reaches its actual action") {
                !holder.state.value.isApplying && failures.isNotEmpty()
            }
            assertTrue(holder.retryAlertState.value.isPresented)
            assertNotNull(holder.retryAlertState.value.message)
            assertEquals(1, holder.retryAlertState.value.retryCount)
            assertTrue(service.radioSettingsNeedsRecovery)
            assertTrue(service.pathHashModeNeedsRecovery)
            assertTrue(retired.events.toList().isEmpty())
            holder.retry.retry()
            h.eventually("fresh normal preset retry reconciles both retired publications") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null &&
                    !service.radioSettingsNeedsRecovery && !service.pathHashModeNeedsRecovery
            }
            val saved = assertNotNull(store.fetchDevice(before.id))
            assertEquals("ca", saved.appliedRadioPresetID)
            assertEquals(2u.toUByte(), saved.pathHashMode)
            assertEquals(saved, h.manager.connectedDevice)
            assertFalse(holder.retryAlertState.value.isPresented)
            assertEquals(0, holder.retryAlertState.value.retryCount)
        }
    }

    @Test fun normalPresetMergesARealContactWatermarkConflictWithoutLosingUnrelatedFields() = runTest {
        var armed = false
        var conflicts = 0
        val interleaving = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.appliedRadioPresetID == "eu-narrow") {
                    armed = false
                    store.updateDeviceLastContactSync(dto.radioId, 9876u)
                    val saved = store.saveDeviceIfUnchanged(dto, expected)
                    assertFalse(saved, "The real Room watermark invalidates the preset write ticket")
                    conflicts++
                    return saved
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        withRepeat(interleaving) { h, holder, failures, _ ->
            val before = assertNotNull(h.manager.connectedDevice)
            armed = true
            holder.onPresetSelected("eu-narrow")
            h.eventually("normal preset resolves the actual Room conflict and acknowledges") {
                !holder.state.value.isApplying &&
                    h.manager.connectedDevice?.appliedRadioPresetID == "eu-narrow"
            }
            assertEquals(1, conflicts)
            assertTrue(failures.isEmpty())
            val saved = assertNotNull(store.fetchDevice(before.id))
            assertEquals(9876u, saved.lastContactSync)
            assertEquals(before.pathHashMode, saved.pathHashMode)
            assertEquals(before.nodeName, saved.nodeName)
            assertEquals(before.publicKey, saved.publicKey)
            assertEquals(saved, h.manager.connectedDevice)
        }
    }

    @Test fun queuedRealEditorCannotPreventDisconnectFromClosingAnActualSuspendedRestoreSend() = runTest {
        println("WP305_REPEAT_DISCONNECT|queuedRealEditorCannotPreventDisconnectFromClosingAnActualSuspendedRestoreSend|START")
        val editorEntered = CompletableDeferred<Unit>()
        val runtimeEntered = CompletableDeferred<Unit>()
        var armed = false
        val observed = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (!armed) return store.saveDeviceIfUnchanged(dto, expected)
                editorEntered.complete(Unit)
                return store.saveDeviceIfUnchanged(dto, expected.copy(withAuthority = { write ->
                    expected.withAuthority {
                        runtimeEntered.complete(Unit)
                        write()
                    }
                }))
            }
        }
        withRepeat(observed, sessionConfiguration = SessionConfiguration(defaultTimeout = 60.0)) { h, _, _, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.sessions.current).token
            val sending = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            radio.beforeRadioSend = { sending.complete(Unit); release.await() }
            val restore = async {
                runCatching {
                    assertNotNull(h.sessions.current).settingsService.setRadioParamsVerified(
                        before.frequency, before.bandwidth, before.spreadingFactor, before.codingRate, false,
                        restoreAuthority = h.container.deviceEditor.preRepeatSettingsRestoreAuthority(token, before),
                    )
                }
            }
            var edit: kotlinx.coroutines.Deferred<Result<Boolean>>? = null
            var disconnect: kotlinx.coroutines.Deferred<*>? = null
            try {
                h.eventually("actual restore send suspended") { sending.isCompleted }
                armed = true
                val editor = async(start = CoroutineStart.UNDISPATCHED) {
                    runCatching { h.container.deviceEditor.updatePathHashMode(2u, token) }
                }
                edit = editor
                h.eventually("real editor reached actual Room CAS") { editorEntered.isCompleted }
                h.eventually("real editor entered the actual runtime authority before disconnect") { runtimeEntered.isCompleted }
                assertFalse(editor.isCompleted)
                assertEquals(before, store.fetchDevice(before.id))
                val physical = h.links.radios.last()
                val closing = async(start = CoroutineStart.UNDISPATCHED) { h.manager.disconnect() }
                disconnect = closing
                h.eventually(
                    "disconnect physically closes the suspended send without a protocol timeout (runtimeHeld=${runtimeEntered.isCompleted})",
                    virtualBudgetMillis = 0,
                ) { physical.closes > 0 && !physical.connected }
                assertTrue(radio.radioWrites.isEmpty(), "Disconnect cancelled the send before its frame")
                assertFalse(release.isCompleted, "Only actual teardown, not the test latch or a timeout, made progress")
                assertEquals(before, store.fetchDevice(before.id))
                h.eventually("retired editor and disconnect settle") { editor.isCompleted && closing.isCompleted }
                val outcome = editor.await()
                assertTrue(outcome.getOrNull() == false || outcome.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                assertEquals(before, store.fetchDevice(before.id))
                h.manager.connect(h.target(before.id))
                h.eventually("fresh current session finishes startup after contested disconnect") {
                    h.sessions.current?.syncCoordinator?.state == SyncState.Synced && h.appState.canRunSettingsStartupReads
                }
                val nextToken = assertNotNull(h.sessions.current).token
                assertFalse(nextToken == token)
                val fresh = assertNotNull(h.manager.connectedDevice)
                assertFalse(h.container.deviceEditor.updatePathHashMode(2u, token), "The old token remains unable to write")
                assertEquals(fresh, store.fetchDevice(fresh.id))
                assertTrue(h.container.deviceEditor.updatePathHashMode(1u, nextToken))
                assertEquals(fresh.copy(pathHashMode = 1u), store.fetchDevice(fresh.id))
                assertEquals(fresh.copy(pathHashMode = 1u), h.manager.connectedDevice)
            } finally {
                release.complete(Unit)
                restore.cancel()
                edit?.cancel()
                restore.join()
                edit?.join()
                disconnect?.join()
            }
        }
        println("WP305_REPEAT_DISCONNECT|queuedRealEditorCannotPreventDisconnectFromClosingAnActualSuspendedRestoreSend|END|SUCCESS")
    }

    @Test fun identityWritersCannotChangeAnAdmittedRestoreBeforeItsActualSet() = runTest {
        for ((index, operation) in listOf("delete", "delete-all", "demote", "reconcile", "insert").withIndex()) {
            withRepeat(store) { h, _, _, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val token = assertNotNull(h.sessions.current).token
                val ghost = before.copy(id = UUID.randomUUID(), radioId = com.meshcoreone.android.core.model.RadioId(UUID.randomUUID()),
                    publicKey = key(91 + index), isActive = false, connectionMethods = com.meshcoreone.android.core.model.SnapshotList.empty())
                store.saveDevice(ghost)
                val authority = h.container.deviceEditor.preRepeatSettingsRestoreAuthority(token, before)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                radio.beforeRadioSend = { entered.complete(Unit); release.await() }
                val restore = async {
                    runCatching {
                        assertNotNull(h.sessions.current).settingsService.setRadioParamsVerified(
                            before.frequency, before.bandwidth, before.spreadingFactor, before.codingRate, false,
                            expectedDevice = before, restoreAuthority = authority,
                        )
                    }
                }
                var writer: kotlinx.coroutines.Deferred<Any?>? = null
                try {
                    h.eventually("actual restore send entered before identity mutation") { entered.isCompleted }
                    val contender = async<Any?>(start = CoroutineStart.UNDISPATCHED) {
                        when (operation) {
                            "delete" -> store.deleteDevice(before.id)
                            "delete-all" -> store.deleteDeviceAndData(before.id)
                            "demote" -> store.demoteDeviceToGhost(before.id)
                            "reconcile" -> store.reconcileGhostIdentity(before.id, ghost.publicKey)
                            else -> assertFalse(store.insertDeviceIfAbsent(before))
                        }
                    }
                    writer = contender
                    assertEquals(before, store.fetchDevice(before.id), "$operation must not invalidate an admitted identity")
                    assertFalse(contender.isCompleted, "$operation must wait outside the DB mutex")
                    assertTrue(radio.radioWrites.isEmpty())
                    release.complete(Unit)
                    restore.await().exceptionOrNull()?.let { failure ->
                        assertTrue(failure is kotlinx.coroutines.CancellationException ||
                            failure is com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException ||
                            failure is DeviceEditPersistenceConflict, "Unexpected post-send failure: $failure")
                    }
                    contender.await()
                    assertEquals(1, radio.radioWrites.size)
                    when (operation) {
                        "delete", "delete-all", "demote" -> assertNull(store.fetchDevice(before.id))
                        "reconcile" -> {
                            assertEquals(before.copy(radioId = ghost.radioId, publicKey = ghost.publicKey), store.fetchDevice(before.id))
                            assertNull(store.fetchDevice(ghost.id))
                        }
                        else -> assertEquals(before, store.fetchDevice(before.id))
                    }
                } finally {
                    release.complete(Unit)
                    restore.cancel()
                    restore.join()
                    writer?.join()
                }
            }
        }
    }

    @Test fun capturedRestoreRejectsDeleteReinsertAbaWithoutSending() = runTest {
        withRepeat(store) { h, _, _, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.sessions.current).token
            val authority = h.container.deviceEditor.preRepeatSettingsRestoreAuthority(token, before)
            store.deleteDevice(before.id)
            assertTrue(store.insertDeviceIfAbsent(before))
            store.saveDevice(before)
            assertEquals(before, store.fetchDevice(before.id), "The durable row really returned to the same full DTO")
            val failure = runCatching {
                assertNotNull(h.sessions.current).settingsService.setRadioParamsVerified(
                    before.frequency, before.bandwidth, before.spreadingFactor, before.codingRate, false,
                    expectedDevice = before, restoreAuthority = authority,
                )
            }.exceptionOrNull()
            assertIs<DeviceEditPersistenceConflict>(failure)
            assertTrue(radio.radioWrites.isEmpty())
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
        }
    }

    @Test fun actualProtocolRestoreReleasesAdmissionAfterSendWhileOkIsWithheld() = runTest {
        withRepeat(store) { h, holder, failures, radio ->
            holder.onRepeatToggled(true)
            holder.confirmEnableRepeatMode()
            h.eventually("Repeat active before delayed OK") { !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true }
            radio.withholdNextAck = true
            holder.onRepeatToggled(false)
            h.eventually("actual restore SET sent without OK") { radio.radioWrites.size == 2 && radio.hasPendingAck }
            val before = assertNotNull(store.fetchDevice(assertNotNull(h.manager.connectedDevice).id))
            val changed = before.copy(lastContactSync = before.lastContactSync + 1u)
            val writer = async(start = CoroutineStart.UNDISPATCHED) { store.saveDevice(changed) }
            try {
                h.eventually("admitted same-device write completes before firmware OK") { writer.isCompleted }
                writer.await()
                assertTrue(holder.state.value.isApplyingRepeat, "The real MeshCoreSession is still awaiting the withheld OK")
                assertTrue(failures.isEmpty())
                assertEquals(changed, store.fetchDevice(before.id))
                radio.releaseAck()
                h.eventually("restore and clear complete after OK") {
                    !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == false &&
                        h.manager.connectedDevice?.preRepeatFrequency == null
                }
                val expected = changed.clearingPreRepeatSettings().copy(
                    frequency = assertNotNull(changed.preRepeatFrequency), bandwidth = assertNotNull(changed.preRepeatBandwidth),
                    spreadingFactor = assertNotNull(changed.preRepeatSpreadingFactor), codingRate = assertNotNull(changed.preRepeatCodingRate),
                    clientRepeat = false,
                )
                assertEquals(expected, store.fetchDevice(before.id))
                assertEquals(expected, h.manager.connectedDevice)
            } finally { radio.releaseAck(); writer.join() }
        }
    }

    @Test fun cancellationCannotReleaseRestoreAdmissionWhileTheActualSendChildCanStillWrite() = runTest {
        withRepeat(store) { h, _, _, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val token = assertNotNull(h.sessions.current).token
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            radio.beforeRadioSend = {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    entered.complete(Unit)
                    release.await()
                }
            }
            val restore = async {
                assertNotNull(h.sessions.current).settingsService.setRadioParamsVerified(
                    before.frequency, before.bandwidth, before.spreadingFactor, before.codingRate, false,
                    restoreAuthority = h.container.deviceEditor.preRepeatSettingsRestoreAuthority(token, before),
                )
            }
            try {
                h.eventually("actual transport send child entered") { entered.isCompleted }
                restore.cancel()
                testScheduler.runCurrent()
                val changed = before.copy(lastContactSync = before.lastContactSync + 1u)
                val writer = async(start = CoroutineStart.UNDISPATCHED) { store.saveDevice(changed) }
                try {
                    assertEquals(before, store.fetchDevice(before.id))
                    assertFalse(writer.isCompleted, "Caller cancellation does not settle a transport child that can still write")
                    assertTrue(radio.radioWrites.isEmpty())
                    release.complete(Unit)
                    h.eventually("writer proceeds after the actual send child settles") { writer.isCompleted }
                    writer.await()
                    assertEquals(changed, store.fetchDevice(before.id))
                } finally { release.complete(Unit); writer.join() }
            } finally { release.complete(Unit); restore.cancel(); restore.join() }
        }
    }

    @Test fun persistedPartialSnapshotsAreClearedOnlyByAnActualRepeatOffReconnect() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (field in listOf("frequency", "bandwidth", "sf", "cr")) for (active in listOf(false, true)) {
            val name = "repeat-reopen-${UUID.randomUUID()}.db"
            fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
                .allowMainThreadQueries().setQueryExecutor(Executor { it.run() }).setTransactionExecutor(Executor { it.run() }).build()
            val firstDb = open()
            val first = RoomPersistenceStore(firstDb, backgroundScope)
            lateinit var persisted: DeviceDTO
            try {
                withRepeat(first, room = first) { h, _, _, _ ->
                    val token = assertNotNull(h.sessions.current).token
                    val attempt = PreRepeatSettingsAttempt(token)
                    assertTrue(h.container.deviceEditor.savePreRepeatSettings(token, attempt))
                    val created = assertNotNull(h.manager.connectedDevice)
                    persisted = when (field) {
                        "frequency" -> created.clearingPreRepeatSettings().copy(preRepeatFrequency = 868_000u)
                        "bandwidth" -> created.clearingPreRepeatSettings().copy(preRepeatBandwidth = 125_000u)
                        "sf" -> created.clearingPreRepeatSettings().copy(preRepeatSpreadingFactor = 9u)
                        else -> created.clearingPreRepeatSettings().copy(preRepeatCodingRate = 6u)
                    }
                    first.saveDevice(persisted)
                    assertIs<DeviceEditPersistenceConflict>(
                        runCatching { h.container.deviceEditor.abortPreRepeatSettings(token, attempt) }.exceptionOrNull(),
                    )
                    assertIs<RepeatSnapshotReconciliationRequired>(
                        runCatching { first.repeatSnapshotAuthority.requireUsable(persisted) }.exceptionOrNull(),
                    )
                }
                persisted = assertNotNull(first.fetchDevice(persisted.id))
            } finally { first.close(); firstDb.close() }
            val reopenedDb = open()
            val reopened = RoomPersistenceStore(reopenedDb, backgroundScope)
            try {
                assertTrue(first.repeatSnapshotAuthority !== reopened.repeatSnapshotAuthority)
                assertEquals(persisted, reopened.fetchDevice(persisted.id), "Reopening alone must preserve imported/partial rows")
                withRepeat(reopened, room = reopened, connectExisting = persisted.id, initiallyRepeating = active) { h, _, _, radio ->
                    val current = assertNotNull(h.manager.connectedDevice)
                    val actual = assertNotNull(reopened.fetchDevice(persisted.id))
                    assertEquals(current, actual)
                    assertEquals(active, actual.clientRepeat)
                    val expected = if (active) persisted else persisted.clearingPreRepeatSettings()
                    assertEquals(expected.preRepeatFrequency, actual.preRepeatFrequency, "$field / active=$active")
                    assertEquals(expected.preRepeatBandwidth, actual.preRepeatBandwidth, "$field / active=$active")
                    assertEquals(expected.preRepeatSpreadingFactor, actual.preRepeatSpreadingFactor, "$field / active=$active")
                    assertEquals(expected.preRepeatCodingRate, actual.preRepeatCodingRate, "$field / active=$active")
                    assertTrue(radio.radioWrites.isEmpty(), "Reconnect cleanup is persistence only")
                }
            } finally { reopened.close(); reopenedDb.close(); assertTrue(context.deleteDatabase(name)) }
        }
    }

    @Test fun restoreDispatchRejectsSiblingUnsentCreationAfterItsEarlyValidation() = runTest {
        val validated = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            withRepeat(store, afterRestoreValidation = { validated.complete(Unit); release.await() }) { h, holder, failures, radio ->
                val token = assertNotNull(h.sessions.current).token
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("Repeat active before queued restoration") {
                    !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true &&
                        h.manager.connectedDevice?.frequency == radio.frequency
                }
                holder.onRepeatToggled(false)
                h.eventually("restore passed early validation before reading its device") { validated.isCompleted }
                val service = assertNotNull(h.sessions.current).settingsService
                service.setRadioParamsVerified(868_000u, 125_000u, 9u, 6u, false)
                h.eventually("independent manual off is published") { h.manager.connectedDevice?.frequency == 868_000u }
                assertTrue(h.container.deviceEditor.clearPreRepeatSettings(token))
                val sibling = ConnectedDeviceEditor(
                    h.container.deviceAccess, store, store, { null },
                    RadioPresetMatcher { _, _, _, _ -> emptySet() }, backgroundScope, { java.time.Instant.EPOCH },
                )
                val attempt = PreRepeatSettingsAttempt(token)
                assertTrue(sibling.savePreRepeatSettings(token, attempt))
                val created = assertNotNull(h.manager.connectedDevice)
                store.updateDeviceLastContactSync(created.radioId, created.lastContactSync + 1u)
                assertNotNull(runCatching { sibling.abortPreRepeatSettings(token, attempt) }.exceptionOrNull())
                service.setRadioParamsVerified(869_000u, 62_500u, 8u, 7u, false)
                h.eventually("later manual baseline is published before old restore resumes") { h.manager.connectedDevice?.frequency == 869_000u }
                val before = assertNotNull(store.fetchDevice(created.id))
                val transmissions = radio.radioWrites.size
                release.complete(Unit)
                h.eventually("dispatch admission rejects the newly fenced snapshot") {
                    !holder.state.value.isApplyingRepeat && failures.isNotEmpty()
                }
                assertIs<RepeatSnapshotReconciliationRequired>(failures.last())
                assertNotNull(holder.state.value.errorMessage)
                assertEquals(transmissions, radio.radioWrites.size, "Early validation cannot authorize a later SET")
                assertEquals(before, store.fetchDevice(created.id))
                assertEquals(before, h.manager.connectedDevice)
                assertEquals(token, h.sessions.current?.token)
            }
        } finally { release.complete(Unit) }
    }

    @Test fun restoreDispatchRejectsChangedCompletePartialOrClearedSnapshotsAfterCapturingItsArguments() = runTest {
        for (replacement in listOf("full", "frequency", "bandwidth", "sf", "cr", "clear")) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            try {
                withRepeat(store, beforeRestoreWrite = { entered.complete(Unit); release.await() }) { h, holder, failures, radio ->
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("Repeat active before restore argument race") {
                        !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true &&
                            h.manager.connectedDevice?.frequency == radio.frequency
                    }
                    holder.onRepeatToggled(false)
                    h.eventually("restore arguments captured before admission") { entered.isCompleted }
                    val current = assertNotNull(h.manager.connectedDevice)
                    val cleared = current.clearingPreRepeatSettings()
                    val changed = when (replacement) {
                        "full" -> cleared.copy(preRepeatFrequency = 868_000u, preRepeatBandwidth = 125_000u,
                            preRepeatSpreadingFactor = 9u, preRepeatCodingRate = 6u)
                        "frequency" -> cleared.copy(preRepeatFrequency = 868_000u)
                        "bandwidth" -> cleared.copy(preRepeatBandwidth = 125_000u)
                        "sf" -> cleared.copy(preRepeatSpreadingFactor = 9u)
                        "cr" -> cleared.copy(preRepeatCodingRate = 6u)
                        else -> cleared
                    }
                    store.saveDevice(changed)
                    h.container.deviceEditor.updateDevice(changed)
                    val transmissions = radio.radioWrites.size
                    release.complete(Unit)
                    h.eventually("stale restoration arguments are rejected") { !holder.state.value.isApplyingRepeat && failures.isNotEmpty() }
                    assertIs<DeviceEditPersistenceConflict>(failures.last())
                    assertEquals(transmissions, radio.radioWrites.size, "A $replacement snapshot change must prevent the old SET")
                    assertEquals(changed, store.fetchDevice(changed.id))
                    assertEquals(changed, h.manager.connectedDevice)
                }
            } finally { release.complete(Unit) }
        }
    }

    @Test fun separateStoresAndEditorsOnTheSameRoomDatabaseShareTheUnsentSnapshotFenceAndCommittedClear() = runTest {
        withRepeat(store) { h, _, _, _ ->
            val token = assertNotNull(h.sessions.current).token
            val original = assertNotNull(h.manager.connectedDevice)
            val alternate = RoomPersistenceStore(db, backgroundScope)
            try {
                assertTrue(store.repeatSnapshotAuthority === alternate.repeatSnapshotAuthority)
                val wrapped = object : PersistenceStoreProtocol by alternate {}
                val sibling = ConnectedDeviceEditor(
                    h.container.deviceAccess, wrapped, wrapped, { null },
                    RadioPresetMatcher { _, _, _, _ -> emptySet() }, backgroundScope, { java.time.Instant.EPOCH },
                )
                assertTrue(h.container.deviceEditor.savePreRepeatSettings(token, PreRepeatSettingsAttempt(token)))
                assertIs<RepeatSnapshotReconciliationRequired>(
                    runCatching { sibling.savePreRepeatSettings(token, PreRepeatSettingsAttempt(token)) }.exceptionOrNull(),
                )
                assertIs<RepeatSnapshotReconciliationRequired>(
                    runCatching { sibling.validatePreRepeatSettings(token) }.exceptionOrNull(),
                )
                assertEquals(original.savingPreRepeatSettings(), store.fetchDevice(original.id))
                assertTrue(sibling.clearPreRepeatSettings(token))
                assertTrue(h.container.deviceEditor.validatePreRepeatSettings(token))
                assertEquals(original, store.fetchDevice(original.id))
            } finally { alternate.close() }
        }
    }

    @Test fun conflictedUnsentSnapshotCannotRestoreOldRfAfterAnInterveningWriteAndFreshEnable() = runTest {
        for (aba in listOf(false, true)) {
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
                withRepeat(held) { h, holder, failures, radio ->
                    val before = assertNotNull(h.manager.connectedDevice)
                    val token = assertNotNull(h.sessions.current).token
                    armed = true
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("snapshot committed before intervening revision") { committed.isCompleted }
                    val created = assertNotNull(store.fetchDeviceWriteSnapshot(before.id))
                    store.updateDeviceLastContactSync(before.radioId, created.device.lastContactSync + 1u)
                    if (aba) store.updateDeviceLastContactSync(before.radioId, created.device.lastContactSync)
                    val intervened = assertNotNull(store.fetchDeviceWriteSnapshot(before.id))
                    assertTrue(intervened.revision > created.revision)
                    if (aba) assertEquals(created.device, intervened.device, "Equal DTOs do not restore creation authority")
                    committed.await().cancel()
                    h.eventually("strict unsent cleanup conflict is reported") {
                        !holder.state.value.isApplyingRepeat && failures.isNotEmpty()
                    }
                    assertTrue(radio.radioWrites.isEmpty(), "The abandoned attempt never sent SET")
                    assertEquals(intervened.device, store.fetchDevice(before.id), "Conflicting cleanup must not rebase or clear")
                    assertNotNull(holder.state.value.errorMessage)
                    val service = assertNotNull(h.sessions.current).settingsService
                    service.setRadioParamsVerified(868_000u, 125_000u, 9u, 6u, false)
                    h.eventually("later manual RF is live and observed on the original token") {
                        h.manager.connectedDevice?.frequency == 868_000u && holder.state.value.device?.frequency == 868_000u
                    }
                    val manual = assertNotNull(h.manager.connectedDevice)
                    assertEquals(token, h.sessions.current?.token)
                    val oldFailures = failures.size
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("fresh enable is reconciled or explicitly fenced") {
                        !holder.state.value.isApplyingRepeat && (failures.size > oldFailures ||
                            (h.manager.connectedDevice?.clientRepeat == true && holder.state.value.device?.clientRepeat == true &&
                                h.manager.connectedDevice?.frequency == radio.frequency && holder.state.value.device?.frequency == radio.frequency))
                    }
                    if (h.manager.connectedDevice?.clientRepeat == true) {
                        holder.onRepeatToggled(false)
                        h.eventually("fresh Repeat off finishes") {
                            !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == false &&
                                h.manager.connectedDevice?.frequency == radio.frequency
                        }
                        val restored = assertNotNull(store.fetchDevice(before.id))
                        assertEquals(manual.frequency, restored.frequency, "An abandoned snapshot must never restore the pre-cancellation RF")
                        assertEquals(manual.bandwidth, restored.bandwidth)
                        assertEquals(manual.spreadingFactor, restored.spreadingFactor)
                        assertEquals(manual.codingRate, restored.codingRate)
                    } else {
                        assertNotNull(holder.state.value.errorMessage)
                        assertEquals(1, radio.radioWrites.size, "A fenced fresh enable cannot send SET")
                        assertEquals(manual, store.fetchDevice(before.id))
                        val wrapped = object : PersistenceStoreProtocol by store {}
                        val sibling = ConnectedDeviceEditor(
                            h.container.deviceAccess, wrapped, wrapped, { null },
                            RadioPresetMatcher { _, _, _, _ -> emptySet() }, backgroundScope, { java.time.Instant.EPOCH },
                        )
                        assertIs<RepeatSnapshotReconciliationRequired>(
                            runCatching { sibling.savePreRepeatSettings(token, PreRepeatSettingsAttempt(token)) }.exceptionOrNull(),
                            "A separate editor and delegating wrapper share the process-store fence",
                        )
                        assertIs<RepeatSnapshotReconciliationRequired>(
                            runCatching { sibling.validatePreRepeatSettings(token) }.exceptionOrNull(),
                        )
                        holder.onRepeatToggled(false)
                        h.eventually("fenced restoration releases its busy state") { !holder.state.value.isApplyingRepeat }
                        assertEquals(1, radio.radioWrites.size, "A fenced restore cannot use the abandoned baseline either")
                        assertEquals(manual, store.fetchDevice(before.id))
                        assertTrue(h.container.deviceEditor.clearPreRepeatSettings(token), "Explicit current-token clear resolves the ambiguity")
                        assertTrue(sibling.validatePreRepeatSettings(token))
                        h.eventually("explicit resolution is observed") { holder.state.value.device?.preRepeatFrequency == null }
                        holder.onRepeatToggled(true)
                        holder.confirmEnableRepeatMode()
                        h.eventually("resolved fresh Repeat is live and observed") {
                            !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true &&
                                holder.state.value.device?.clientRepeat == true && h.manager.connectedDevice?.frequency == radio.frequency &&
                                holder.state.value.device?.frequency == radio.frequency
                        }
                        holder.onRepeatToggled(false)
                        h.eventually("resolved Repeat restores the later manual baseline") {
                            !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == false &&
                                h.manager.connectedDevice?.preRepeatFrequency == null && h.manager.connectedDevice?.frequency == manual.frequency
                        }
                        val restored = assertNotNull(store.fetchDevice(before.id))
                        assertEquals(manual.frequency, restored.frequency)
                        assertEquals(manual.bandwidth, restored.bandwidth)
                        assertEquals(manual.spreadingFactor, restored.spreadingFactor)
                        assertEquals(manual.codingRate, restored.codingRate)
                    }
                }
            } finally { hold.complete(Unit) }
        }
    }

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

    @Test fun explicitSnapshotDiscardRejectsConcurrentRevisionAndAbaButAllowsFreshRecovery() = runTest {
        println("WP305_REPEAT_CLEAR|explicit-discard|START")
        for (aba in listOf(false, true)) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var armed = false
            val held = object : PersistenceStoreProtocol by store {
                override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                    if (armed && expected.device.preRepeatFrequency != null && dto.preRepeatFrequency == null) {
                        armed = false
                        entered.complete(Unit)
                        release.await()
                    }
                    return store.saveDeviceIfUnchanged(dto, expected)
                }
            }
            try {
                withRepeat(held) { h, _, _, radio ->
                    val token = assertNotNull(h.sessions.current).token
                    assertTrue(h.container.deviceEditor.savePreRepeatSettings(token))
                    val before = assertNotNull(store.fetchDevice(assertNotNull(h.manager.connectedDevice).id))
                    armed = true
                    val discard = async { runCatching { h.container.deviceEditor.discardPreRepeatSettings(token) } }
                    try {
                        h.eventually("explicit discard paused after its exact authoritative receipt") { entered.isCompleted }
                        val changed = before.copy(preRepeatBandwidth = before.bandwidth + 1u)
                        store.saveDevice(changed)
                        if (aba) store.saveDevice(before)
                        val latest = assertNotNull(store.fetchDevice(before.id))
                        h.container.deviceEditor.updateDevice(latest)
                        release.complete(Unit)
                        assertIs<DeviceEditPersistenceConflict>(discard.await().exceptionOrNull())
                        assertEquals(latest, store.fetchDevice(before.id))
                        assertEquals(latest, h.manager.connectedDevice)
                        assertTrue(radio.radioWrites.isEmpty(), "Discard never sends RF")
                        assertTrue(h.container.deviceEditor.discardPreRepeatSettings(token))
                        assertEquals(latest.clearingPreRepeatSettings(), store.fetchDevice(before.id))
                        assertTrue(h.container.deviceEditor.savePreRepeatSettings(token))
                        h.manager.disconnect()
                        h.manager.connect(h.target(before.id))
                        h.eventually("fresh current session after explicit recovery") {
                            h.sessions.current?.token != token && h.appState.canRunSettingsStartupReads
                        }
                        val next = assertNotNull(h.sessions.current).token
                        val successor = store.fetchDevice(before.id)
                        assertFalse(h.container.deviceEditor.discardPreRepeatSettings(token))
                        assertEquals(successor, store.fetchDevice(before.id))
                        assertTrue(h.container.deviceEditor.savePreRepeatSettings(next))
                        assertTrue(h.container.deviceEditor.discardPreRepeatSettings(next))
                    } finally { release.complete(Unit); discard.cancel(); discard.join() }
                }
            } finally { release.complete(Unit) }
            println("WP305_REPEAT_CLEAR|explicit-discard|aba=$aba|END|SUCCESS")
        }
    }

    @Test fun failedClearRetirementSurvivesASameRadioReconnectBeforeTheNextCycle() = runTest {
        println("WP305_REPEAT_CLEAR|reconnected-retirement|START")
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var failClear = true
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (failClear && expected.device.preRepeatFrequency != null && dto.preRepeatFrequency == null) {
                    failClear = false
                    entered.complete(Unit)
                    release.await()
                    throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Retirement failed before reconnect"))
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        try {
            withRepeat(held) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val oldToken = assertNotNull(h.sessions.current).token
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("Repeat active before failed retirement and reconnect") {
                    !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true
                }
                holder.onRepeatToggled(false)
                h.eventually("clear held after old generation durably restores Repeat off") { entered.isCompleted }
                val consumed = assertNotNull(store.fetchDevice(before.id))
                assertFalse(consumed.clientRepeat)
                assertEquals(before.frequency, consumed.preRepeatFrequency)
                release.complete(Unit)
                h.eventually("clear failure is visible before reconnect") {
                    !holder.state.value.isApplyingRepeat && failures.isNotEmpty()
                }
                assertEquals(consumed, store.fetchDevice(before.id))
                h.manager.disconnect()
                radio.repeatOnNextConnection = true
                h.manager.connect(h.target(before.id))
                h.eventually("same radio reconnects with a fresh observed generation") {
                    h.sessions.current?.token != oldToken &&
                        h.sessions.current?.syncCoordinator?.state == SyncState.Synced &&
                        h.appState.canRunSettingsStartupReads &&
                        holder.state.value.sessionToken == h.sessions.current?.token
                }
                val nextToken = assertNotNull(h.sessions.current).token
                assertEquals(oldToken.radioId, nextToken.radioId)
                assertTrue(oldToken != nextToken)
                val successor = assertNotNull(store.fetchDevice(before.id))
                assertTrue(successor.clientRepeat, "Active reconnect really preserves the consumed snapshot")
                assertEquals(before.frequency, successor.preRepeatFrequency)
                assertFalse(h.container.deviceEditor.clearPreRepeatSettings(oldToken))
                assertEquals(successor, store.fetchDevice(before.id))
                val manualFrequency = if (before.frequency == 868_000u) 915_000u else 868_000u
                assertNotNull(h.sessions.current).settingsService.setRadioParamsPersisted(
                    manualFrequency, before.bandwidth, before.spreadingFactor, before.codingRate, false,
                )
                h.eventually("successor observes manual RF with Repeat off") {
                    holder.state.value.device?.frequency == manualFrequency && !holder.state.value.isRepeatEnabled
                }
                assertEquals(before.frequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("new generation enables after reconciling old retirement") {
                    !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true
                }
                assertEquals(manualFrequency, store.fetchDevice(before.id)!!.preRepeatFrequency,
                    "A fresh token must not make a consumed baseline reusable")
                holder.onRepeatToggled(false)
                h.eventually("new generation restores its own manual baseline") {
                    !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == false
                }
                val restored = assertNotNull(store.fetchDevice(before.id))
                assertEquals(manualFrequency, restored.frequency)
                assertEquals(before.bandwidth, restored.bandwidth)
                assertEquals(before.spreadingFactor, restored.spreadingFactor)
                assertEquals(before.codingRate, restored.codingRate)
                assertNull(restored.preRepeatFrequency)
                assertNull(restored.preRepeatBandwidth)
                assertNull(restored.preRepeatSpreadingFactor)
                assertNull(restored.preRepeatCodingRate)
                assertEquals(restored, h.manager.connectedDevice)
                assertEquals(listOf(1, 0, 0, 1, 0), radio.radioWrites.map { it[11].toInt() })
            }
        } finally { release.complete(Unit) }
        println("WP305_REPEAT_CLEAR|reconnected-retirement|END|SUCCESS")
    }

    @Test fun committedSiblingClearDuringRestoreReadbackCannotResurrectAnObsoleteRetirement() = runTest {
        println("WP305_REPEAT_CLEAR|late-registration|START")
        for (replacement in listOf("clear", "nonempty", "aba")) {
            withRepeat(store) { h, holder, _, radio ->
                val token = assertNotNull(h.sessions.current).token
                val before = assertNotNull(h.manager.connectedDevice)
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("Repeat active before held restoration readback") {
                    !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true
                }
                assertTrue(h.container.deviceEditor.validatePreRepeatSettings(token))
                val repeating = assertNotNull(h.manager.connectedDevice)
                val authority = h.container.deviceEditor.preRepeatSettingsRestoreAuthority(token, repeating)
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var armed = true
                radio.beforeRadioReadback = {
                    if (armed && radio.radioWrites.size >= 2) {
                        armed = false
                        entered.complete(Unit)
                        release.await()
                    }
                }
                val restore = async {
                    assertNotNull(h.sessions.current).settingsService.setRadioParamsPersisted(
                        before.frequency, before.bandwidth, before.spreadingFactor, before.codingRate, false,
                        restoreAuthority = authority,
                    )
                }
                val alternate = RoomPersistenceStore(db, backgroundScope)
                try {
                    val sibling = ConnectedDeviceEditor(
                        h.container.deviceAccess, alternate, alternate, { null },
                        RadioPresetMatcher { _, _, _, _ -> emptySet() }, backgroundScope, { java.time.Instant.EPOCH },
                    )
                    h.eventually("actual restore send settled but readback is held") { entered.isCompleted }
                    assertEquals(2, radio.radioWrites.size)
                    assertFalse(store.repeatSnapshotAuthority.hasClearRequest(repeating))
                    assertTrue(sibling.clearPreRepeatSettings(token), "The actual sibling clear commits while readback is held")
                    val cleared = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(repeating.clearingPreRepeatSettings(), cleared)
                    assertEquals(cleared, h.manager.connectedDevice)
                    if (replacement != "clear") {
                        val replaced = if (replacement == "aba") repeating else repeating.copy(
                            preRepeatFrequency = if (before.frequency == 868_000u) 915_000u else 868_000u,
                        )
                        store.saveDevice(replaced)
                        h.container.deviceEditor.updateDevice(replaced)
                    }
                    release.complete(Unit)
                    restore.await()
                    val settled = assertNotNull(store.fetchDevice(before.id))
                    assertFalse(settled.clientRepeat)
                    assertEquals(before.frequency, settled.frequency)
                    val writes = radio.radioWrites.toList()
                    if (replacement == "clear") {
                        assertTrue(sibling.validatePreRepeatSettings(token), "A committed all-null snapshot is already retired")
                        assertTrue(sibling.savePreRepeatSettings(token), "The late callback cannot fence fresh preparation")
                        assertTrue(sibling.clearPreRepeatSettings(token))
                    } else {
                        assertIs<RepeatSnapshotClearRequired>(
                            runCatching { sibling.validatePreRepeatSettings(token) }.exceptionOrNull(),
                        )
                        assertIs<RepeatSnapshotClearRequired>(
                            runCatching { sibling.savePreRepeatSettings(token) }.exceptionOrNull(),
                        )
                        assertEquals(settled, store.fetchDevice(before.id), "A nonempty or ABA successor snapshot is never discarded")
                        assertTrue(sibling.discardPreRepeatSettings(token))
                        assertEquals(settled.clearingPreRepeatSettings(), store.fetchDevice(before.id))
                        assertTrue(sibling.validatePreRepeatSettings(token))
                    }
                    assertEquals(writes, radio.radioWrites, "Retirement reconciliation never invents radio traffic")
                    val manualFrequency = if (before.frequency == 868_000u) 915_000u else 868_000u
                    assertNotNull(h.sessions.current).settingsService.setRadioParamsPersisted(
                        manualFrequency, before.bandwidth, before.spreadingFactor, before.codingRate, false,
                    )
                    h.eventually("fresh manual RF is observed after late retirement") {
                        holder.state.value.device?.frequency == manualFrequency
                    }
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("fresh cycle enables after late retirement") {
                        !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true
                    }
                    assertEquals(manualFrequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                    holder.onRepeatToggled(false)
                    h.eventually("fresh cycle restores after late retirement") {
                        !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == false
                    }
                    val after = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(manualFrequency, after.frequency)
                    assertEquals(before.bandwidth, after.bandwidth)
                    assertEquals(before.spreadingFactor, after.spreadingFactor)
                    assertEquals(before.codingRate, after.codingRate)
                    assertNull(after.preRepeatFrequency)
                    assertNull(after.preRepeatBandwidth)
                    assertNull(after.preRepeatSpreadingFactor)
                    assertNull(after.preRepeatCodingRate)
                    assertEquals(after, h.manager.connectedDevice)
                    assertEquals(listOf(1, 0, 0, 1, 0), radio.radioWrites.map { it[11].toInt() })
                } finally {
                    release.complete(Unit)
                    restore.cancel()
                    restore.join()
                    alternate.close()
                }
            }
            println("WP305_REPEAT_CLEAR|late-registration|$replacement|END|SUCCESS")
        }
    }

    @Test fun confirmedRestoreRetirementIsSharedAcrossEditorsAndRejectsSnapshotAba() = runTest {
        println("WP305_REPEAT_CLEAR|shared-retirement|START")
        for (change in listOf("revision", "snapshot-aba", "identity", "identity-aba")) {
            withRepeat(store) { h, _, _, radio ->
                val token = assertNotNull(h.sessions.current).token
                val original = assertNotNull(h.manager.connectedDevice)
                val editor = h.container.deviceEditor
                assertTrue(editor.savePreRepeatSettings(token))
                val saved = assertNotNull(h.manager.connectedDevice)
                assertNotNull(h.sessions.current).settingsService.setRadioParamsPersisted(
                    saved.frequency, saved.bandwidth, saved.spreadingFactor, saved.codingRate, false,
                    restoreAuthority = editor.preRepeatSettingsRestoreAuthority(token, saved),
                )
                val restored = assertNotNull(store.fetchDevice(saved.id))
                val alternate = RoomPersistenceStore(db, backgroundScope)
                try {
                    val wrapped = object : PersistenceStoreProtocol by alternate {}
                    val sibling = ConnectedDeviceEditor(
                        h.container.deviceAccess, wrapped, wrapped, { null },
                        RadioPresetMatcher { _, _, _, _ -> emptySet() }, backgroundScope, { java.time.Instant.EPOCH },
                    )
                    assertTrue(store.repeatSnapshotAuthority === alternate.repeatSnapshotAuthority)
                    if (change != "revision") {
                        val changed = if (change == "snapshot-aba") {
                            restored.copy(preRepeatBandwidth = restored.bandwidth + 1u)
                        } else restored.copy(publicKey = key(92))
                        store.saveDevice(changed)
                        if (change != "identity") store.saveDevice(restored)
                        val current = assertNotNull(store.fetchDevice(saved.id))
                        editor.updateDevice(current)
                        val writes = radio.radioWrites.toList()
                        assertIs<RepeatSnapshotClearRequired>(
                            runCatching { sibling.savePreRepeatSettings(token) }.exceptionOrNull(),
                        )
                        assertIs<RepeatSnapshotClearRequired>(
                            runCatching { sibling.validatePreRepeatSettings(token) }.exceptionOrNull(),
                        )
                        assertEquals(current, store.fetchDevice(saved.id))
                        assertEquals(writes, radio.radioWrites)
                        val cleared = current.clearingPreRepeatSettings()
                        assertTrue(sibling.discardPreRepeatSettings(token))
                        assertEquals(cleared, store.fetchDevice(saved.id))
                        if (change == "identity") {
                            val originalIdentity = cleared.copy(publicKey = original.publicKey)
                            store.saveDevice(originalIdentity)
                            editor.updateDevice(originalIdentity)
                        }
                        assertTrue(sibling.savePreRepeatSettings(token))
                    } else {
                        val changed = restored.copy(pathHashMode = 2u)
                        store.saveDevice(changed)
                        editor.updateDevice(changed)
                        assertTrue(sibling.savePreRepeatSettings(token), "Unrelated full-row revision is not snapshot ABA")
                    }
                    val fresh = assertNotNull(store.fetchDevice(saved.id))
                    assertEquals(original.frequency, fresh.preRepeatFrequency)
                    assertEquals(original.bandwidth, fresh.preRepeatBandwidth)
                    assertEquals(original.spreadingFactor, fresh.preRepeatSpreadingFactor)
                    assertEquals(original.codingRate, fresh.preRepeatCodingRate)
                    assertEquals(fresh, h.manager.connectedDevice)
                    assertTrue(sibling.clearPreRepeatSettings(token))
                    assertNull(store.fetchDevice(saved.id)!!.preRepeatFrequency)
                } finally { alternate.close() }
            }
            println("WP305_REPEAT_CLEAR|shared-retirement|$change|END|SUCCESS")
        }
    }

    @Test fun delayedRepeatOffObservationAfterFailedClearCannotReuseThePreviousBaseline() = runTest {
        println("WP305_REPEAT_CLEAR|delayed-off|START")
        for (failurePoint in listOf("save", "cancel-clear", "cancel-before-clear")) {
            val clearEntered = CompletableDeferred<Unit>()
            val clearRelease = CompletableDeferred<Unit>()
            var failClear = true
            val held = object : PersistenceStoreProtocol by store {
                override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                    if (failClear && expected.device.preRepeatFrequency != null && dto.preRepeatFrequency == null) {
                        clearEntered.complete(Unit)
                        clearRelease.await()
                        if (failurePoint != "save") {
                            currentCoroutineContext()[Job]!!.cancel()
                            throw kotlinx.coroutines.CancellationException("Cancelled snapshot retirement")
                        }
                        throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Snapshot retirement unavailable"))
                    }
                    return store.saveDeviceIfUnchanged(dto, expected)
                }
            }
            val observedDevice = MutableStateFlow<DeviceDTO?>(null)
            val observation = MutableStateFlow(SettingsDeviceObservation(null))
            var forwarding: Job? = null
            try {
                withRepeat(held, beforeSnapshotClear = {
                    if (failClear && failurePoint == "cancel-before-clear") {
                        clearEntered.complete(Unit)
                        clearRelease.await()
                        currentCoroutineContext()[Job]!!.cancel()
                        throw kotlinx.coroutines.CancellationException("Cancelled before the editor clear API")
                    }
                }, connectionOverride = { actual ->
                    forwarding = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        actual.deviceObservations.collect {
                            observedDevice.value = it.device
                            observation.value = it
                        }
                    }
                    object : SettingsConnection by actual {
                        override val connectedDevice = observedDevice
                        override val deviceObservations = observation
                    }
                }) { h, holder, _, radio ->
                    forwarding?.cancel()
                    forwarding?.join()
                    val token = assertNotNull(h.sessions.current).token
                    fun deliver() {
                        val device = assertNotNull(h.manager.connectedDevice)
                        observedDevice.value = device
                        observation.value = SettingsDeviceObservation(device, token)
                    }
                    deliver()
                    h.eventually("initial token observation delivered") { holder.state.value.sessionToken == token }
                    val before = assertNotNull(h.manager.connectedDevice)
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("first Repeat enable acknowledges") {
                        !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true
                    }
                    deliver()
                    h.eventually("holder observes Repeat on before restore") { holder.state.value.device?.clientRepeat == true }
                    holder.onRepeatToggled(false)
                    h.eventually("actual clear waits after durable Repeat off") { clearEntered.isCompleted }
                    val restored = assertNotNull(store.fetchDevice(before.id))
                    assertFalse(restored.clientRepeat)
                    assertEquals(before.frequency, restored.frequency)
                    assertEquals(before.frequency, restored.preRepeatFrequency)
                    assertTrue(holder.state.value.device?.clientRepeat == true, "Only the holder observation is delayed")
                    clearRelease.complete(Unit)
                    h.eventually("failed or cancelled clear action settles") { !holder.state.value.isApplyingRepeat }
                    assertEquals(restored, store.fetchDevice(before.id))
                    deliver()
                    h.eventually("late durable Repeat-off observation flips the holder off") {
                        holder.state.value.device?.clientRepeat == false && !holder.state.value.isRepeatEnabled
                    }
                    val manualFrequency = if (before.frequency == 868_000u) 915_000u else 868_000u
                    assertNotNull(h.sessions.current).settingsService.setRadioParamsPersisted(
                        manualFrequency, before.bandwidth, before.spreadingFactor, before.codingRate,
                        clientRepeat = false,
                    )
                    val manual = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(manualFrequency, manual.frequency)
                    assertEquals(before.frequency, manual.preRepeatFrequency, "The failed clear really retained the obsolete snapshot")
                    deliver()
                    h.eventually("holder observes subsequent manual RF") { holder.state.value.device?.frequency == manualFrequency }
                    val writesBeforeRetry = radio.radioWrites.toList()
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("a new cycle refuses while snapshot retirement still fails") {
                        !holder.state.value.isApplyingRepeat
                    }
                    assertFalse(h.manager.connectedDevice!!.clientRepeat)
                    assertEquals(writesBeforeRetry, radio.radioWrites, "No new SET may reuse the retired baseline")
                    assertEquals(manual, store.fetchDevice(before.id))
                    failClear = false
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("fresh Repeat enable acknowledges after retirement recovery") {
                        !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true
                    }
                    deliver()
                    h.eventually("holder observes fresh Repeat on") { holder.state.value.device?.clientRepeat == true }
                    holder.onRepeatToggled(false)
                    h.eventually("fresh Repeat off acknowledges") {
                        !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == false
                    }
                    val after = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(manualFrequency, after.frequency, "A new cycle must restore the later manual RF, never the obsolete baseline")
                    assertEquals(before.bandwidth, after.bandwidth)
                    assertEquals(before.spreadingFactor, after.spreadingFactor)
                    assertEquals(before.codingRate, after.codingRate)
                    assertNull(after.preRepeatFrequency)
                    assertNull(after.preRepeatBandwidth)
                    assertNull(after.preRepeatSpreadingFactor)
                    assertNull(after.preRepeatCodingRate)
                    assertEquals(after, h.manager.connectedDevice)
                    assertEquals(listOf(1, 0, 0, 1, 0), radio.radioWrites.map { it[11].toInt() })
                }
            } finally { clearRelease.complete(Unit); forwarding?.cancel(); forwarding?.join() }
            println("WP305_REPEAT_CLEAR|delayed-off|$failurePoint|END|SUCCESS")
        }
    }

    @Test fun delayedEnableObservationCannotMasqueradeAsOffProofForAFailedDisable() = runTest {
        val observedDevice = MutableStateFlow<DeviceDTO?>(null)
        val observation = MutableStateFlow(SettingsDeviceObservation(null))
        var forwarding: Job? = null
        var failRestore = true
        try {
            withRepeat(store, beforeRestoreWrite = {
                if (failRestore) throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Restore admission unavailable"))
            }, connectionOverride = { actual ->
                forwarding = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    actual.deviceObservations.collect {
                        observedDevice.value = it.device
                        observation.value = it
                    }
                }
                object : SettingsConnection by actual {
                    override val connectedDevice = observedDevice
                    override val deviceObservations = observation
                }
            }) { h, holder, failures, radio ->
                forwarding?.cancel()
                forwarding?.join()
                val token = assertNotNull(h.sessions.current).token
                val before = assertNotNull(h.manager.connectedDevice)
                assertFalse(assertNotNull(holder.state.value.device).clientRepeat)
                holder.onRepeatToggled(true)
                holder.confirmEnableRepeatMode()
                h.eventually("actual enable acknowledges while its observation is withheld") {
                    !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true
                }
                assertTrue(holder.state.value.isRepeatEnabled)
                assertFalse(assertNotNull(holder.state.value.device).clientRepeat)
                val repeating = assertNotNull(store.fetchDevice(before.id))
                assertNotNull(repeating.preRepeatFrequency)

                holder.onRepeatToggled(false)
                h.eventually("disable fails before it can verify or send off") {
                    !holder.state.value.isApplyingRepeat && failures.isNotEmpty()
                }
                assertEquals(token, holder.state.value.sessionToken)
                assertTrue(holder.state.value.isRepeatEnabled, "An old false observation is not this action's off proof")
                assertNotNull(holder.state.value.errorMessage)
                assertEquals(repeating, store.fetchDevice(before.id))
                assertEquals(repeating, h.manager.connectedDevice)
                assertEquals(listOf(1), radio.radioWrites.map { it[11].toInt() }, "Failed admission cannot send Repeat-off")

                failRestore = false
                holder.onRepeatToggled(false)
                h.eventually("fresh disable verifies off and clears its snapshot") {
                    !holder.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == false
                }
                val after = assertNotNull(store.fetchDevice(before.id))
                observedDevice.value = after
                observation.value = SettingsDeviceObservation(after, token)
                h.eventually("holder observes the actual restored RF") { holder.state.value.device == after }
                assertFalse(holder.state.value.isRepeatEnabled)
                assertNull(holder.state.value.errorMessage)
                assertEquals(before.frequency, after.frequency)
                assertEquals(before.bandwidth, after.bandwidth)
                assertEquals(before.spreadingFactor, after.spreadingFactor)
                assertEquals(before.codingRate, after.codingRate)
                assertNull(after.preRepeatFrequency)
                assertNull(after.preRepeatBandwidth)
                assertNull(after.preRepeatSpreadingFactor)
                assertNull(after.preRepeatCodingRate)
                assertEquals(after, h.manager.connectedDevice)
                assertEquals(listOf(1, 0), radio.radioWrites.map { it[11].toInt() })
            }
        } finally { forwarding?.cancel(); forwarding?.join() }
    }

    @Test fun verifiedRepeatOffStaysOffWhenLaterPersistenceFailsAndANewCycleRecovers() = runTest {
        for (failurePoint in listOf("repeat-projection", "radio-projection", "clear")) {
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var failPersistence = true
            val held = object : PersistenceStoreProtocol by store {
                override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                    val clearsSnapshot = expected.device.preRepeatFrequency != null && dto.preRepeatFrequency == null
                    val projectsRepeatOff = expected.device.clientRepeat && !dto.clientRepeat && dto.preRepeatFrequency != null
                    val projectsRestoredRf = !expected.device.clientRepeat && !dto.clientRepeat &&
                        expected.device.frequency != dto.frequency && dto.preRepeatFrequency != null
                    val failsHere = when (failurePoint) {
                        "repeat-projection" -> projectsRepeatOff
                        "radio-projection" -> projectsRestoredRf
                        else -> clearsSnapshot
                    }
                    if (failPersistence && failsHere) {
                        entered.complete(Unit)
                        release.await()
                        throw PersistenceStoreException(PersistenceStoreError.SaveFailed("Repeat $failurePoint unavailable"))
                    }
                    return store.saveDeviceIfUnchanged(dto, expected)
                }
            }
            try {
                withRepeat(held) { h, holder, failures, radio ->
                    val before = assertNotNull(h.manager.connectedDevice)
                    val token = assertNotNull(h.sessions.current).token
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("initial Repeat enable is observed and acknowledged") {
                        !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true
                    }
                    holder.onRepeatToggled(false)
                    h.eventually("verified off reaches the held $failurePoint boundary") {
                        entered.isCompleted && holder.state.value.device?.clientRepeat == (failurePoint == "repeat-projection")
                    }
                    assertTrue(holder.state.value.isApplyingRepeat)
                    assertFalse(holder.state.value.isRepeatEnabled)
                    val durableOff = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(failurePoint == "repeat-projection", durableOff.clientRepeat)
                    assertNotNull(durableOff.preRepeatFrequency)
                    if (failurePoint != "clear") {
                        assertEquals(before.frequency, radio.frequency, "Actual restore was verified before either publication")
                        assertTrue(durableOff.frequency != before.frequency, "The later RF projection has not committed")
                    }

                    release.complete(Unit)
                    h.eventually("$failurePoint failure reaches the actual action") {
                        !holder.state.value.isApplyingRepeat && failures.isNotEmpty()
                    }
                    assertEquals(token, holder.state.value.sessionToken)
                    assertFalse(holder.state.value.isRepeatEnabled, "Later persistence cannot undo verified Repeat-off")
                    assertEquals(failurePoint == "repeat-projection", assertNotNull(holder.state.value.device).clientRepeat)
                    assertNotNull(holder.state.value.errorMessage)
                    assertNotNull(store.fetchDevice(before.id)?.preRepeatFrequency, "Failure cannot silently discard the baseline")

                    failPersistence = false
                    holder.onRepeatToggled(true)
                    holder.confirmEnableRepeatMode()
                    h.eventually("a new cycle reconciles retirement and clears the visible error") {
                        !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == true
                    }
                    assertTrue(holder.state.value.isRepeatEnabled)
                    assertNull(holder.state.value.errorMessage)
                    val fresh = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(before.frequency, fresh.preRepeatFrequency)
                    assertEquals(before.bandwidth, fresh.preRepeatBandwidth)
                    assertEquals(before.spreadingFactor, fresh.preRepeatSpreadingFactor)
                    assertEquals(before.codingRate, fresh.preRepeatCodingRate)
                    holder.onRepeatToggled(false)
                    h.eventually("the fresh cycle restores RF and clears its snapshot") {
                        !holder.state.value.isApplyingRepeat && holder.state.value.device?.clientRepeat == false
                    }
                    val after = assertNotNull(store.fetchDevice(before.id))
                    assertFalse(holder.state.value.isRepeatEnabled)
                    assertNull(holder.state.value.errorMessage)
                    assertEquals(before.frequency, after.frequency)
                    assertEquals(before.bandwidth, after.bandwidth)
                    assertEquals(before.spreadingFactor, after.spreadingFactor)
                    assertEquals(before.codingRate, after.codingRate)
                    assertNull(after.preRepeatFrequency)
                    assertNull(after.preRepeatBandwidth)
                    assertNull(after.preRepeatSpreadingFactor)
                    assertNull(after.preRepeatCodingRate)
                    assertEquals(after, h.manager.connectedDevice)
                    assertEquals(listOf(1, 0, 1, 0), radio.radioWrites.map { it[11].toInt() })
                }
            } finally { release.complete(Unit) }
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
                    if (clear) {
                        assertIs<RepeatSnapshotClearRequired>(
                            runCatching { h.container.deviceEditor.savePreRepeatSettings(successorToken) }.exceptionOrNull(),
                            "An active successor cannot reuse the old consumed baseline merely by changing tokens",
                        )
                        assertEquals(durable, store.fetchDevice(successor.id))
                        assertFalse(h.container.deviceEditor.discardPreRepeatSettings(oldToken))
                        assertTrue(h.container.deviceEditor.discardPreRepeatSettings(successorToken))
                        assertEquals(assertNotNull(durable).clearingPreRepeatSettings(), store.fetchDevice(successor.id))
                    }
                    assertTrue(h.container.deviceEditor.savePreRepeatSettings(successorToken))
                    assertNotNull(store.fetchDevice(successor.id)?.preRepeatFrequency, "A fresh successor action remains supported")
                }
            } finally { release.complete(Unit) }
        }
    }

    private class RepeatRadio {
        val radioWrites = mutableListOf<Bytes>()
        val powerWrites = mutableListOf<Bytes>()
        private var txPower: Byte? = null
        var pathHashMode: UByte = 0u
            private set
        private var params: Bytes? = null
        private var repeat = false
        var repeatOnNextConnection = false
        var beforeRadioSend: suspend () -> Unit = {}
        var beforeRadioReadback: suspend () -> Unit = {}
        var withholdNextAck = false
        private var pendingAck: FirmwareRadio? = null
        val hasPendingAck get() = pendingAck != null
        fun releaseAck() {
            pendingAck?.push(Bytes.of(0))
            pendingAck = null
        }
        val frequency: UInt?
            get() = radioWrites.lastOrNull()?.let { frame ->
                (frame[1].toUInt() or (frame[2].toUInt() shl 8) or
                    (frame[3].toUInt() shl 16) or (frame[4].toUInt() shl 24))
            }

        fun configure(radio: FirmwareRadio) {
            params = null
            txPower = null
            pathHashMode = 0u
            repeat = repeatOnNextConnection
            repeatOnNextConnection = false
            radio.firmwareVersion = 11u
            radio.beforeSend = { command ->
                if (command[0] == 0x0b.toUByte()) beforeRadioSend()
                if (command[0] == 0x01.toUByte()) beforeRadioReadback()
            }
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x0b -> {
                        require(command.size == 11 || command.size == 12)
                        radioWrites += command
                        params = command.slice(1, 11)
                        repeat = command.size == 12 && command[11] != 0u.toUByte()
                        if (withholdNextAck) {
                            withholdNextAck = false
                            pendingAck = radio
                            emptyList()
                        } else listOf(Bytes.of(0))
                    }
                    0x0c -> {
                        powerWrites += command
                        txPower = command[1].toByte()
                        listOf(Bytes.of(0))
                    }
                    0x01 -> if (params == null && txPower == null) null else {
                        val self = radio.selfPacket().toByteArray()
                        txPower?.let { self[2] = it }
                        val packet = Bytes(self)
                        listOf(params?.let {
                            packet.slice(0, 48) + it + Bytes.utf8("TestNode")
                        } ?: packet)
                    }
                    0x16 -> if (params != null || repeat) listOf(
                        Bytes.of(13, 11, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                            Bytes.utf8("T-Deck").paddedOrTruncated(40) +
                            Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(if (repeat) 1 else 0, pathHashMode.toInt()),
                    ) else null
                    0x3d -> { pathHashMode = command[2]; listOf(Bytes.of(0)) }
                    0x3c -> listOf(Bytes.of(0x1a))
                    0x1f -> listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                    else -> null
                }
            }
        }
    }

    private suspend fun TestScope.withRepeat(
        persistence: PersistenceStoreProtocol,
        room: RoomPersistenceStore = store,
        afterRestoreValidation: (suspend () -> Unit)? = null,
        beforeRestoreWrite: (suspend () -> Unit)? = null,
        beforeSnapshotClear: (suspend () -> Unit)? = null,
        connectExisting: UUID? = null,
        initiallyRepeating: Boolean = false,
        sessionConfiguration: SessionConfiguration = SessionConfiguration.DEFAULT,
        connectionOverride: (SettingsConnection) -> SettingsConnection = { it },
        exposeDependencies: (SettingsFeatureDependencies) -> Unit = {},
        action: suspend (ContainerHarness, RadioPresetStateHolder, MutableList<Throwable>, RepeatRadio) -> Unit,
    ) {
        val h = ContainerHarness(this, room, persistenceOverride = persistence, sessionConfiguration = sessionConfiguration)
        val radio = RepeatRadio()
        var holder: RadioPresetStateHolder? = null
        try {
            h.links.configureRadio = radio::configure
            radio.repeatOnNextConnection = initiallyRepeating
            if (connectExisting == null) h.connectForegroundSettings() else {
                h.container.foreground.activityStarted()
                h.manager.connect(h.target(connectExisting))
            }
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
            exposeDependencies(deps)
            val failures = mutableListOf<Throwable>()
            val deviceStore = object : DeviceSettingsStorePort by deps.deviceStore {
                override suspend fun clearPreRepeatSettings(expectedToken: SessionToken?) {
                    beforeSnapshotClear?.invoke()
                    deps.deviceStore.clearPreRepeatSettings(expectedToken)
                }
                override suspend fun validatePreRepeatSettings(expectedToken: SessionToken?) {
                    deps.deviceStore.validatePreRepeatSettings(expectedToken)
                    afterRestoreValidation?.invoke()
                }
                override fun preRepeatSettingsRestoreAuthority(expectedToken: SessionToken?, expectedDevice: DeviceDTO): RepeatSnapshotRestoreAuthority {
                    val actual = deps.deviceStore.preRepeatSettingsRestoreAuthority(expectedToken, expectedDevice)
                    return object : RepeatSnapshotRestoreAuthority {
                        override suspend fun withWrite(write: suspend (RadioWritePermit) -> Unit) {
                            beforeRestoreWrite?.invoke()
                            actual.withWrite(write)
                        }
                        override fun onRestoreVerified() = actual.onRestoreVerified()
                    }
                }
            }
            val repeat = RadioPresetStateHolder(
                SettingsEnvironment(backgroundScope, describe = { failures += it; UiText.Verbatim("Repeat failed") }),
                connectionOverride(deps.connection), deps.regions, deps.radioCatalog, deps.settingsService, deviceStore, RadioWriteGate(),
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
