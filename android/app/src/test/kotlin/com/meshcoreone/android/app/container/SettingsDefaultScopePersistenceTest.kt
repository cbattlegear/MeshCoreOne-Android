// AndroidOnly: WP-317 Actual Settings default-scope action, radio, AppState, Room and region-removal interleavings.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.navigation.removeSettingsKnownRegion
import com.meshcoreone.android.core.contracts.domain.DeviceWriteSnapshot
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceError
import com.meshcoreone.android.core.contracts.domain.errors.MessageServiceException
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.snapshot
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.FloodScope
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.ui.UiText
import com.meshcoreone.android.feature.settings.device.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsDefaultScopePersistenceTest : RoomProcessTest() {
    @Test fun acceptedRfReadbackRatherThanTheRequestBecomesTheDraftForAFreshUnrelatedEdit() = runTest {
        var dependencies: SettingsFeatureDependencies? = null
        withSettings(exposeDependencies = { dependencies = it }) { h, _, _, radio ->
            val d = assertNotNull(dependencies)
            val failures = mutableListOf<Throwable>()
            val advanced = AdvancedRadioStateHolder(
                SettingsEnvironment(backgroundScope, describe = { failures += it; UiText.Verbatim("Radio failed") }),
                d.connection, d.settingsService, RadioWriteGate(),
            )
            advanced.start()
            try {
                h.eventually("Advanced observes the initial radio") { advanced.state.value.hasLoaded }
                val before = assertNotNull(h.manager.connectedDevice)
                val token = assertNotNull(h.sessions.current).token
                radio.radioWriteTransform = { command ->
                    if (radio.radioWrites.size == 1) PacketBuilder.setRadio(867.999, 125.0, 9u, 7u, false) else command
                }
                advanced.onFrequencyTextChanged("868.000")
                advanced.onBandwidthSelected(125_000u)
                advanced.onSpreadingFactorSelected(9)
                advanced.onCodingRateSelected(7)
                advanced.onTxPowerTextChanged("10")
                advanced.apply(token)
                h.eventually("accepted one-kHz readback is acknowledged through Room") {
                    advanced.state.value.showSuccess && !advanced.state.value.isApplying
                }
                assertTrue(failures.isEmpty())
                assertEquals(867_999u, store.fetchDevice(before.id)?.frequency)
                assertEquals(867_999u, h.manager.connectedDevice?.frequency)
                assertEquals("867.999", advanced.state.value.frequencyText)
                assertEquals(867.999, advanced.state.value.frequency)
                assertEquals(125_000u, advanced.state.value.bandwidth)
                assertEquals(9, advanced.state.value.spreadingFactor)
                assertEquals(7, advanced.state.value.codingRate)
                assertEquals(10, advanced.state.value.txPower)
                assertFalse(advanced.state.value.settingsModified)
                advanced.onSpreadingFactorSelected(8)
                advanced.apply(token)
                h.eventually("fresh SF-only Apply sends the acknowledged frequency unchanged") {
                    radio.powerWrites.size == 2 && advanced.state.value.showSuccess && !advanced.state.value.isApplying
                }
                assertEquals(
                    listOf(PacketBuilder.setRadio(868.0, 125.0, 9u, 7u, false), PacketBuilder.setRadio(867.999, 125.0, 8u, 7u, false)),
                    radio.radioWrites,
                )
                assertEquals(listOf(PacketBuilder.setTxPower(10), PacketBuilder.setTxPower(10)), radio.powerWrites)
                val saved = assertNotNull(store.fetchDevice(before.id))
                assertEquals(867_999u, saved.frequency)
                assertEquals(8.toUByte(), saved.spreadingFactor)
                assertEquals(saved, h.manager.connectedDevice)
                assertEquals("867.999", advanced.state.value.frequencyText)
                assertFalse(advanced.state.value.settingsModified)
                assertTrue(failures.isEmpty())
                assertNull(advanced.state.value.errorMessage)
            } finally { advanced.stop() }
        }
    }

    @Test fun reopeningAdvancedAfterFailedTxPersistenceReconcilesPowerBeforeAnUnrelatedRfEdit() = runTest {
        for (followup in listOf("success", "failed-untouched", "failed-explicit")) {
        val laterFailure = followup != "success"
        val intendedPower: Byte = if (followup == "failed-explicit") 15 else 10
        var rejectPower = false
        val rejected = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (rejectPower && dto.txPower == 10.toByte()) {
                    throw com.meshcoreone.android.core.contracts.domain.PersistenceStoreException(
                        com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.SaveFailed("Power persistence unavailable"),
                    )
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        var dependencies: SettingsFeatureDependencies? = null
        withSettings(rejected, exposeDependencies = { dependencies = it }) { h, _, _, radio ->
            val d = assertNotNull(dependencies)
            val failures = mutableListOf<Throwable>()
            fun advanced() = AdvancedRadioStateHolder(
                SettingsEnvironment(backgroundScope, describe = { failures += it; UiText.Verbatim("Power failed") }),
                d.connection, d.settingsService, RadioWriteGate(),
            )
            val first = advanced()
            first.start()
            try {
                h.eventually("first Advanced observes current power") { first.state.value.hasLoaded }
                val before = assertNotNull(h.manager.connectedDevice)
                rejectPower = true
                first.onTxPowerTextChanged("10")
                first.apply()
                h.eventually("actual verified TX persistence failure reaches Settings") {
                    failures.isNotEmpty() && !first.state.value.isApplying
                }
                assertTrue(h.sessions.current!!.settingsService.radioSettingsNeedsRecovery)
                assertEquals(before.txPower, store.fetchDevice(before.id)?.txPower)
                assertEquals(before.txPower, h.manager.connectedDevice?.txPower)
                assertFalse(first.state.value.showSuccess)
                first.stop()
                rejectPower = false
                val reopened = advanced()
                reopened.start()
                try {
                    h.eventually("fresh Advanced initially captures the stale stored power") { reopened.state.value.hasLoaded }
                    assertEquals(before.txPower.toInt(), reopened.state.value.txPower)
                    reopened.onSpreadingFactorSelected(9)
                    if (followup == "failed-explicit") reopened.onTxPowerTextChanged(intendedPower.toString())
                    val token = assertNotNull(h.sessions.current).token
                    val failuresBefore = failures.size
                    if (laterFailure) radio.powerFailure = 6u
                    reopened.apply()
                    if (laterFailure) {
                        h.eventually("later TX rejects while the reconciled RF and power remain committed") {
                            !reopened.state.value.isApplying && failures.size > failuresBefore
                        }
                        assertEquals(token, h.sessions.current?.token)
                        h.assertReady()
                        assertFalse(reopened.state.value.showSuccess)
                        assertNotNull(reopened.state.value.errorMessage)
                        assertEquals(10.toByte(), store.fetchDevice(before.id)?.txPower)
                        assertEquals(9.toUByte(), store.fetchDevice(before.id)?.spreadingFactor)
                        assertEquals(intendedPower.toInt(), reopened.state.value.txPower)
                        assertEquals(intendedPower.toString(), reopened.state.value.txPowerText)
                        radio.powerFailure = null
                        reopened.dismissError()
                        reopened.apply()
                    }
                    h.eventually("RF-only fresh action preserves the reconciled actual power") { reopened.state.value.showSuccess }
                    val saved = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(intendedPower, saved.txPower)
                    assertEquals(9.toUByte(), saved.spreadingFactor)
                    assertEquals(saved, h.manager.connectedDevice)
                    val expected = listOf(PacketBuilder.setTxPower(10), PacketBuilder.setTxPower(intendedPower)) +
                        if (laterFailure) listOf(PacketBuilder.setTxPower(intendedPower)) else emptyList()
                    assertEquals(expected, radio.powerWrites)
                    assertFalse(h.sessions.current!!.settingsService.radioSettingsNeedsRecovery)
                    assertNull(reopened.state.value.errorMessage)
                } finally { reopened.stop() }
            } finally { first.stop(); rejectPower = false }
        }
        }
    }

    @Test fun failedActualRfPersistenceReconcilesBeforeTheSameSessionActionRetryDerivesItsBaseline() = runTest {
        var failRf = false
        val rejected = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (failRf && dto.frequency == 868_100u) {
                    throw com.meshcoreone.android.core.contracts.domain.PersistenceStoreException(
                        com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.SaveFailed("Radio parameters unavailable"),
                    )
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        var dependencies: SettingsFeatureDependencies? = null
        withSettings(rejected, exposeDependencies = { dependencies = it }) { h, _, _, radio ->
            val d = assertNotNull(dependencies)
            val failures = mutableListOf<Throwable>()
            val advanced = AdvancedRadioStateHolder(
                SettingsEnvironment(backgroundScope, describe = { failures += it; UiText.Verbatim("Radio failed") }),
                d.connection, d.settingsService, RadioWriteGate(),
            )
            advanced.start()
            try {
                h.eventually("manual Settings ready before RF persistence failure") { advanced.state.value.hasLoaded }
                val before = assertNotNull(h.manager.connectedDevice)
                val session = assertNotNull(h.sessions.current)
                failRf = true
                advanced.onFrequencyTextChanged("868.1")
                advanced.onTxPowerTextChanged("10")
                advanced.apply(session.token)
                h.eventually("verified RF persistence failure reaches the actual action") {
                    failures.isNotEmpty() && !advanced.state.value.isApplying
                }
                assertFalse(advanced.state.value.showSuccess)
                assertNotNull(advanced.state.value.errorMessage)
                assertTrue(failures.single() is com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)
                assertEquals(before, store.fetchDevice(before.id))
                assertEquals(before, h.manager.connectedDevice)
                assertEquals(1, radio.radioWrites.size)
                assertTrue(radio.powerWrites.isEmpty())
                assertTrue(session.settingsService.radioSettingsNeedsRecovery)
                failRf = false
                advanced.dismissError()
                advanced.apply(session.token)
                h.eventually("actual readback reconciliation permits the same-session retry") {
                    advanced.state.value.showSuccess && !advanced.state.value.isApplying
                }
                val saved = assertNotNull(store.fetchDevice(before.id))
                assertEquals(868_100u, saved.frequency)
                assertEquals(10.toByte(), saved.txPower)
                assertEquals(before.bandwidth, saved.bandwidth)
                assertEquals(before.spreadingFactor, saved.spreadingFactor)
                assertEquals(before.codingRate, saved.codingRate)
                assertEquals(saved, h.manager.connectedDevice)
                assertEquals(session.token, h.sessions.current?.token)
                assertEquals(2, radio.radioWrites.size)
                assertEquals(1, radio.powerWrites.size)
                assertFalse(session.settingsService.radioSettingsNeedsRecovery)
                assertNull(advanced.state.value.errorMessage)
            } finally { advanced.stop() }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    @Test fun manualRadioWaitsForActualConsumerAndIgnoresAnOlderObservationButAcceptsALaterChange() = runTest {
        val rfEntered = CompletableDeferred<Unit>()
        val rfRelease = CompletableDeferred<Unit>()
        val powerEntered = CompletableDeferred<Unit>()
        val powerRelease = CompletableDeferred<Unit>()
        val observationEntered = CompletableDeferred<Unit>()
        val observationRelease = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.frequency == 868_100u) {
                    if (dto.txPower == 10.toByte() && !powerEntered.isCompleted) {
                        powerEntered.complete(Unit)
                        powerRelease.await()
                    } else if (dto.txPower != 10.toByte() && !rfEntered.isCompleted) {
                        rfEntered.complete(Unit)
                        rfRelease.await()
                    }
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        var dependencies: SettingsFeatureDependencies? = null
        try {
            withSettings(held, exposeDependencies = { dependencies = it }) { h, _, _, radio ->
                val d = assertNotNull(dependencies)
                var holdObservation = true
                val connection = object : SettingsConnection by d.connection {
                    override val deviceObservations = d.connection.deviceObservations.onEach {
                        val device = it.device
                        if (holdObservation && device != null && device.frequency == 868_100u && device.txPower != 10.toByte()) {
                            holdObservation = false
                            observationEntered.complete(Unit)
                            observationRelease.await()
                        }
                    }
                }
                val failures = mutableListOf<Throwable>()
                val gate = RadioWriteGate()
                val advanced = AdvancedRadioStateHolder(
                    SettingsEnvironment(backgroundScope, describe = { failures += it; UiText.Verbatim("Radio failed") }),
                    connection, d.settingsService, gate,
                )
                val states = mutableListOf<AdvancedRadioState>()
                val collecting = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    advanced.state.collect { states += it }
                }
                advanced.start()
                try {
                    h.eventually("manual Settings observes the actual ready device") { advanced.state.value.hasLoaded }
                    val before = assertNotNull(h.manager.connectedDevice)
                    val token = assertNotNull(advanced.state.value.sessionToken)
                    advanced.onFrequencyTextChanged("868.1")
                    advanced.onBandwidthSelected(125_000u)
                    advanced.onSpreadingFactorSelected(9)
                    advanced.onCodingRateSelected(7)
                    advanced.onTxPowerTextChanged("10")
                    armed = true
                    advanced.apply(token)
                    h.eventually("RF event held at the real AppState Room consumer") { rfEntered.isCompleted }
                    assertTrue(advanced.state.value.isApplying && gate.inFlight.value)
                    assertFalse(advanced.state.value.showSuccess)
                    assertTrue(radio.powerWrites.isEmpty(), "Power cannot overtake the RF persistence acknowledgement")
                    assertEquals(before, store.fetchDevice(before.id))
                    rfRelease.complete(Unit)
                    h.eventually("older RF-only observation and real power persistence both held") {
                        observationEntered.isCompleted && powerEntered.isCompleted
                    }
                    assertTrue(advanced.state.value.isApplying && gate.inFlight.value)
                    assertFalse(advanced.state.value.showSuccess)
                    assertEquals(before.txPower, store.fetchDevice(before.id)!!.txPower)
                    assertEquals(1, radio.powerWrites.size)
                    powerRelease.complete(Unit)
                    h.eventually("verified radio and power commit before showing success") {
                        advanced.state.value.showSuccess && !advanced.state.value.isApplying
                    }
                    val committed = assertNotNull(store.fetchDevice(before.id))
                    assertEquals(868_100u, committed.frequency)
                    assertEquals(125_000u, committed.bandwidth)
                    assertEquals(9u.toUByte(), committed.spreadingFactor)
                    assertEquals(7u.toUByte(), committed.codingRate)
                    assertEquals(10.toByte(), committed.txPower)
                    assertEquals(committed, h.manager.connectedDevice)
                    assertFalse(gate.inFlight.value)
                    val afterSuccess = states.size
                    val completedAt = testScheduler.currentTime
                    observationRelease.complete(Unit)
                    h.eventually("old queued observation drains without resetting the committed form", virtualBudgetMillis = 0) {
                        advanced.state.value.device == committed
                    }
                    val drained = states.drop(afterSuccess)
                    assertTrue(drained.isNotEmpty(), "The older observation must actually be consumed")
                    assertTrue(drained.all { it.showSuccess && it.frequency == 868.1 && it.txPower == 10 })
                    assertEquals("868.100", advanced.state.value.frequencyText)
                    assertEquals("10", advanced.state.value.txPowerText)
                    assertFalse(advanced.state.value.settingsModified)
                    assertEquals(token, advanced.state.value.sessionToken)

                    armed = false
                    val later = async {
                        h.sessions.current!!.settingsService.setRadioParamsVerified(867_300u, 125_000u, 9u, 7u)
                    }
                    h.eventually("a genuinely later actual radio change clears success and reloads the form", virtualBudgetMillis = 0) {
                        later.isCompleted && advanced.state.value.frequency == 867.3 && !advanced.state.value.showSuccess
                    }
                    later.await()
                    assertEquals(completedAt, testScheduler.currentTime, "The later change, not the success timer, clears the checkmark")
                    assertEquals("867.300", advanced.state.value.frequencyText)
                    assertEquals(10, advanced.state.value.txPower)
                    assertEquals(867_300u, store.fetchDevice(before.id)!!.frequency)
                    assertEquals(2, radio.radioWrites.size)
                    assertEquals(1, radio.powerWrites.size)
                    assertTrue(failures.isEmpty())
                } finally { advanced.stop(); collecting.cancel() }
            }
        } finally {
            rfRelease.complete(Unit)
            powerRelease.complete(Unit)
            observationRelease.complete(Unit)
        }
    }

    @Test fun manualRadioPersistenceFailureReachesTheActualActionAndCanBeRetried() = runTest {
        var fail = false
        val rejected = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (fail && dto.txPower == 10.toByte()) {
                    throw com.meshcoreone.android.core.contracts.domain.PersistenceStoreException(
                        com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.SaveFailed("Radio power unavailable"),
                    )
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        var dependencies: SettingsFeatureDependencies? = null
        withSettings(rejected, exposeDependencies = { dependencies = it }) { h, _, _, radio ->
            val d = assertNotNull(dependencies)
            val failures = mutableListOf<Throwable>()
            val advanced = AdvancedRadioStateHolder(
                SettingsEnvironment(backgroundScope, describe = { failures += it; UiText.Verbatim("Radio failed") }),
                d.connection, d.settingsService, RadioWriteGate(),
            )
            advanced.start()
            try {
                h.eventually("manual Settings ready for actual persistence failure") { advanced.state.value.hasLoaded }
                val before = assertNotNull(h.manager.connectedDevice)
                fail = true
                advanced.onTxPowerTextChanged("10")
                advanced.apply()
                h.eventually("actual power persistence failure reaches Settings") {
                    failures.isNotEmpty() && !advanced.state.value.isApplying
                }
                assertFalse(advanced.state.value.showSuccess)
                assertNotNull(advanced.state.value.errorMessage)
                assertTrue(failures.single() is com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)
                assertEquals(before.txPower, store.fetchDevice(before.id)!!.txPower)
                assertEquals(1, radio.powerWrites.size)
                fail = false
                advanced.dismissError()
                advanced.onTxPowerTextChanged("10")
                advanced.apply()
                h.eventually("actual Settings power retry commits and succeeds") {
                    advanced.state.value.showSuccess && !advanced.state.value.isApplying
                }
                assertEquals(10.toByte(), store.fetchDevice(before.id)!!.txPower)
                assertEquals(10.toByte(), h.manager.connectedDevice!!.txPower)
                assertEquals(2, radio.powerWrites.size)
                assertNull(advanced.state.value.errorMessage)
            } finally { advanced.stop() }
        }
    }

    @Test fun repeatWaitsForActualRoomSnapshotAndClearFailuresReachTheActualAction() = runTest {
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
                    throw com.meshcoreone.android.core.contracts.domain.PersistenceStoreException(
                        com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.SaveFailed("Repeat snapshot unavailable"),
                    )
                }
                if (armed && expected.device.preRepeatFrequency != null && dto.preRepeatFrequency == null && failClear) {
                    clearEntered.complete(Unit)
                    clearRelease.await()
                    throw com.meshcoreone.android.core.contracts.domain.PersistenceStoreException(
                        com.meshcoreone.android.core.contracts.domain.PersistenceStoreError.SaveFailed("Repeat clear unavailable"),
                    )
                }
                return store.saveDeviceIfUnchanged(dto, expected)
            }
        }
        var deps: SettingsFeatureDependencies? = null
        try {
            withSettings(held, exposeDependencies = { deps = it }) { h, _, _, radio ->
                val d = assertNotNull(deps)
                val failures = mutableListOf<Throwable>()
                val repeat = RadioPresetStateHolder(
                    SettingsEnvironment(backgroundScope, describe = { failures += it; UiText.Verbatim("Repeat failed") }),
                    d.connection, d.regions, d.radioCatalog, d.settingsService, d.deviceStore, RadioWriteGate(),
                )
                repeat.start()
                try {
                    h.eventually("Repeat observes real ready device") { repeat.state.value.device != null }
                    val before = assertNotNull(h.manager.connectedDevice)
                    armed = true
                    repeat.onRepeatToggled(true)
                    repeat.confirmEnableRepeatMode()
                    h.eventually("actual pre-Repeat Room write held") { saveEntered.isCompleted }
                    assertTrue(repeat.state.value.isApplyingRepeat)
                    assertTrue(radio.radioWrites.isEmpty(), "No radio mutation before durable snapshot")
                    assertNull(store.fetchDevice(before.id)!!.preRepeatFrequency)
                    saveRelease.complete(Unit)
                    h.eventually("snapshot failure reaches Repeat action") {
                        !repeat.state.value.isApplyingRepeat && failures.isNotEmpty()
                    }
                    assertTrue(radio.radioWrites.isEmpty())
                    assertNotNull(repeat.state.value.errorMessage)
                    failSave = false
                    repeat.onRepeatToggled(true)
                    repeat.confirmEnableRepeatMode()
                    h.eventually("actual Repeat radio and snapshot committed") {
                        !repeat.state.value.isApplyingRepeat && h.manager.connectedDevice?.clientRepeat == true
                    }
                    assertEquals(before.frequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                    assertEquals(1, radio.radioWrites.size)
                    repeat.onRepeatToggled(false)
                    h.eventually("actual pre-Repeat clear held after verified restore") { clearEntered.isCompleted }
                    assertTrue(repeat.state.value.isApplyingRepeat)
                    assertEquals(before.frequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                    clearRelease.complete(Unit)
                    h.eventually("clear failure remains visible") {
                        !repeat.state.value.isApplyingRepeat && failures.size >= 2
                    }
                    assertNotNull(repeat.state.value.errorMessage)
                    assertEquals(before.frequency, store.fetchDevice(before.id)!!.preRepeatFrequency)
                    failClear = false
                    repeat.onRepeatToggled(false)
                    h.eventually("restored snapshot clear acknowledges") {
                        !repeat.state.value.isApplyingRepeat && !repeat.state.value.isRepeatEnabled &&
                            h.manager.connectedDevice?.frequency == before.frequency &&
                            h.manager.connectedDevice?.clientRepeat == false
                    }
                    val restored = store.fetchDevice(before.id)!!
                    assertNull(restored.preRepeatFrequency)
                    assertEquals(before.frequency, restored.frequency)
                    assertFalse(restored.clientRepeat)
                    assertEquals(listOf(1, 0, 0), radio.radioWrites.map { it[11].toInt() })
                } finally { repeat.stop() }
            }
        } finally { saveRelease.complete(Unit); clearRelease.complete(Unit) }
    }

    @Test fun channelReactionAndNotificationReplyShareTheActualWireGateAndCanRetry() = runTest {
        withSettings { h, holder, _, radio ->
            val device = assertNotNull(h.manager.connectedDevice)
            val channel = ChannelDTO(radioId = device.radioId, index = 1u, name = "Inline scope control")
            store.saveChannel(channel)
            holder.select("EU868")
            h.eventually("default acknowledged before inline actions") {
                !holder.state.value.isApplying && holder.state.value.currentScope == "EU868"
            }
            val source = MessageDTO(
                java.util.UUID.randomUUID(), device.radioId, channelIndex = channel.index,
                text = "Original", timestamp = 1u, senderNodeName = "Peer",
            )
            val start = h.links.radios.single().sent.size
            radio.activeScopeFailure = 2u
            val failure = assertFailsWith<MessageServiceException> {
                h.container.chatsFeature.react(source, "\uD83D\uDC4D", TimelineConversation.Channel(channel))
            }
            assertTrue(failure.error is MessageServiceError.SendFailed)
            h.sessions.current!!.notificationActionHandler.handleChannelQuickReply(device.radioId, channel.index, "Inline reply")
            assertTrue(radio.channelTransmissions.isEmpty())
            assertEquals(2, radio.activeScopes.size)
            val failed = store.fetchMessages(device.radioId, channel.index)
            assertEquals(2, failed.size)
            assertTrue(failed.all { it.status == MessageStatus.FAILED }, "Neither inline action can retain false pending/sent success")
            radio.activeScopeFailure = null
            h.container.chatsFeature.react(source, "\uD83D\uDC4D", TimelineConversation.Channel(channel))
            h.sessions.current!!.notificationActionHandler.handleChannelQuickReply(device.radioId, channel.index, "Inline reply retry")
            assertEquals(2, radio.channelTransmissions.size)
            val saved = store.fetchMessages(device.radioId, channel.index)
            assertEquals(2, saved.count { it.status == MessageStatus.SENT })
            assertTrue(saved.filter { it.id in failed.map(MessageDTO::id) }.all { it.status == MessageStatus.FAILED })
            val scopeOpcode = PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey())[0]
            val frames = h.links.radios.single().sent.drop(start)
            assertEquals(
                listOf(scopeOpcode, scopeOpcode, scopeOpcode, 3u.toUByte(), scopeOpcode, 3u.toUByte()),
                frames.map { it[0] }.filter { it == scopeOpcode || it == 3u.toUByte() },
            )
            assertTrue(frames.none { it[0] == 0x1fu.toUByte() }, "Scope ERROR 2 must never query channel existence")
            val timeline = h.container.chatsFeature.timeline(TimelineConversation.Channel(channel))
            val applied = radio.activeScopes.size
            timeline.syncChannelFloodScope(channel)
            assertEquals(applied, radio.activeScopes.size, "Timeline reuses the actual inline transmission's successful scope")
            val other = ChannelDTO(radioId = device.radioId, index = 2u, name = "Other scope")
                .withFloodScope(ChannelFloodScope.Region("US915"))
            store.saveChannel(other)
            h.container.chatsFeature.timeline(TimelineConversation.Channel(other)).syncChannelFloodScope(other)
            timeline.syncChannelFloodScope(channel)
            timeline.syncChannelFloodScope(channel)
            assertEquals(
                listOf(
                    PacketBuilder.setFloodScope(FloodScope.Region("US915").scopeKey()),
                    PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey()),
                ),
                radio.activeScopes.drop(applied),
                "Another conversation invalidates actual scope, while repeated unchanged opens remain deduplicated",
            )
        }
    }

    @Test fun actualChannelTransmissionWaitsThroughPausedAndFailedReconciliationThenSendsAfterRetry() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        var failedSaves = 0
        val held = object : PersistenceStoreProtocol by store {
            override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                if (armed && dto.defaultFloodScopeName == "EU868") {
                    armed = false
                    entered.complete(Unit)
                    release.await()
                }
                return try {
                    store.saveDeviceIfUnchanged(dto, expected)
                } catch (failure: com.meshcoreone.android.core.contracts.domain.PersistenceStoreException) {
                    if (dto.defaultFloodScopeName == "EU868") failedSaves++
                    throw failure
                }
            }
        }
        try {
            withSettings(held) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                store.saveChannel(ChannelDTO(radioId = before.radioId, index = 1u, name = "Scope control"))
                db.openHelper.writableDatabase.execSQL(
                    "CREATE TRIGGER reject_transmission_scope BEFORE UPDATE ON devices " +
                        "BEGIN SELECT RAISE(FAIL, 'scope acknowledgment unavailable'); END",
                )
                try {
                    armed = true
                    holder.select("EU868")
                    h.eventually("actual Settings persistence is paused") { entered.isCompleted }
                    val message = enqueueActualChannel(h, before, "scope-gated channel")
                    val channel = assertNotNull(store.fetchChannel(before.radioId, 1u))
                    val source = MessageDTO(
                        java.util.UUID.randomUUID(), before.radioId, channelIndex = channel.index,
                        text = "Inline source", timestamp = 1u, senderNodeName = "Peer",
                    )
                    val reaction = async {
                        runCatching { h.container.chatsFeature.react(source, "\uD83D\uDC4D", TimelineConversation.Channel(channel)) }
                    }
                    val notification = async {
                        h.sessions.current!!.notificationActionHandler.handleChannelQuickReply(
                            before.radioId, channel.index, "Reply during reconciliation",
                        )
                    }
                    h.settle()
                    assertTrue(radio.channelTransmissions.isEmpty())
                    assertTrue(radio.activeScopes.isEmpty())
                    assertFalse(reaction.isCompleted, "Inline reaction cannot bypass the pending Room acknowledgment")
                    assertFalse(notification.isCompleted, "Notification reply cannot bypass the pending Room acknowledgment")
                    assertEquals(before, store.fetchDevice(before.id))
                    release.complete(Unit)
                    h.eventually("selection, queued and inline gates encounter real Room failures") {
                        failedSaves >= 4 && !holder.state.value.isApplying && failures.isNotEmpty() &&
                            reaction.isCompleted && notification.isCompleted
                    }
                    assertTrue(reaction.await().exceptionOrNull() is com.meshcoreone.android.core.contracts.domain.PersistenceStoreException)
                    notification.await()
                    val inlineFailures = store.fetchMessages(before.radioId, channel.index).filter { it.id != message.id }
                    assertEquals(2, inlineFailures.size)
                    assertTrue(inlineFailures.all { it.status == MessageStatus.FAILED })
                    assertNotNull(holder.state.value.errorMessage)
                    assertTrue(radio.channelTransmissions.isEmpty(), "Failed reconciliation cannot transmit")
                    assertTrue(radio.activeScopes.isEmpty(), "Failed reconciliation cannot apply a stale inherited scope")
                    assertEquals(before, store.fetchDevice(before.id))
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_transmission_scope")
                    holder.select("EU868")
                    h.eventually("actual Settings retry acknowledges the radio default") {
                        !holder.state.value.isApplying && holder.state.value.errorMessage == null &&
                            h.manager.connectedDevice?.defaultFloodScopeName == "EU868"
                    }
                    h.sessions.current!!.chatSendQueueService.transportDidOpen()
                    h.eventually("actual persisted channel message transmits after reconciliation") {
                        radio.channelTransmissions.size == 1 &&
                            kotlinx.coroutines.runBlocking {
                                store.fetchMessage(com.meshcoreone.android.core.contracts.domain.EntityKey(before.radioId, message.id))
                                    ?.status == com.meshcoreone.android.core.model.MessageStatus.SENT
                            }
                    }
                    assertEquals(
                        listOf(PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey())),
                        radio.activeScopes,
                    )
                    assertEquals("EU868", radio.mode)
                    assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
                    assertEquals(before.copy(defaultFloodScopeName = "EU868"), h.manager.connectedDevice)
                    val frames = h.links.radios.single().sent
                    assertTrue(
                        frames.indexOf(radio.channelTransmissions.single()) > frames.lastIndexOf(radio.activeScopes.single()),
                        "A successful actual channel scope application must precede the channel text command",
                    )
                } finally {
                    db.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS reject_transmission_scope")
                }
            }
        } finally { release.complete(Unit) }
    }

    @Test fun olderFirmwareKeepsInheritedChannelSendsButRejectsUnsupportedScopedSends() = runTest {
        for (version in listOf<UByte>(7u, 8u)) {
            withSettings(firmwareVersion = version) { h, _, _, radio ->
                val device = assertNotNull(h.manager.connectedDevice)
                val channel = ChannelDTO(radioId = device.radioId, index = 1u, name = "Legacy scope control")
                store.saveChannel(channel)
                val inherited = enqueueActualChannel(h, device, "inherited v$version")
                h.eventually("inherited send remains supported on firmware $version") {
                    kotlinx.coroutines.runBlocking {
                        store.fetchMessage(com.meshcoreone.android.core.contracts.domain.EntityKey(device.radioId, inherited.id))
                            ?.status == MessageStatus.SENT
                    }
                }
                assertEquals(if (version < 8u) 0 else 1, radio.activeScopes.size)
                store.saveChannel(channel.withFloodScope(com.meshcoreone.android.core.model.ChannelFloodScope.Region("EU868")))
                val scoped = enqueueActualChannel(h, device, "scoped v$version")
                val scopedKey = com.meshcoreone.android.core.contracts.domain.EntityKey(device.radioId, scoped.id)
                h.eventually("unsupported scope is explicit, supported scope transmits") {
                    kotlinx.coroutines.runBlocking {
                        store.fetchMessage(scopedKey)?.status == if (version < 8u) MessageStatus.FAILED else MessageStatus.SENT
                    }
                }
                assertFalse(store.hasPendingSend(scopedKey))
                assertEquals(if (version < 8u) 1 else 2, radio.channelTransmissions.size)
                assertEquals(if (version < 8u) 0 else 2, radio.activeScopes.size)
            }
        }
    }

    @Test fun inheritedChannelScopeUsesTheConnectedTransportRowNotTheFirstMatchingRadioIdentity() = runTest {
        var inactive: DeviceDTO? = null
        val ambiguous = object : PersistenceStoreProtocol by store {
            override suspend fun fetchDevice(radioId: com.meshcoreone.android.core.model.RadioId): DeviceDTO? =
                inactive?.takeIf { it.radioId == radioId } ?: store.fetchDevice(radioId)
        }
        withSettings(ambiguous) { h, holder, _, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            inactive = before.copy(id = java.util.UUID.randomUUID(), isActive = false, defaultFloodScopeName = "US915")
            store.saveDevice(assertNotNull(inactive))
            store.saveChannel(ChannelDTO(radioId = before.radioId, index = 1u, name = "Active transport scope"))
            holder.select("EU868")
            h.eventually("active transport scope acknowledged") {
                !holder.state.value.isApplying && h.manager.connectedDevice?.defaultFloodScopeName == "EU868"
            }
            val message = enqueueActualChannel(h, before, "active transport default")
            h.eventually("active transport inherited send finishes") {
                kotlinx.coroutines.runBlocking {
                    store.fetchMessage(com.meshcoreone.android.core.contracts.domain.EntityKey(before.radioId, message.id))
                        ?.status == MessageStatus.SENT
                }
            }
            assertEquals(
                listOf(PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey())),
                radio.activeScopes,
            )
            assertEquals("US915", store.fetchDevice(assertNotNull(inactive).id)?.defaultFloodScopeName)
            assertEquals("EU868", store.fetchDevice(before.id)?.defaultFloodScopeName)
        }
    }

    @Test fun failedScopeApplicationIsNotMistakenForAChannelErrorTwoOrAChannelTransmission() = runTest {
        withSettings { h, holder, _, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val channel = ChannelDTO(radioId = before.radioId, index = 1u, name = "Scope control")
            store.saveChannel(channel)
            radio.channel = channel
            holder.select("EU868")
            h.eventually("default acknowledged") { !holder.state.value.isApplying && holder.state.value.currentScope == "EU868" }
            radio.activeScopeFailure = 2u
            val start = h.links.radios.single().sent.size
            val failed = enqueueActualChannel(h, before, "scope preparation fails")
            h.eventually("scope preparation failure is surfaced on the actual unsent message") {
                kotlinx.coroutines.runBlocking {
                    store.fetchMessage(com.meshcoreone.android.core.contracts.domain.EntityKey(before.radioId, failed.id))
                        ?.status == com.meshcoreone.android.core.model.MessageStatus.FAILED
                }
            }
            assertTrue(radio.channelTransmissions.isEmpty())
            assertFalse(
                h.links.radios.single().sent.drop(start).any { it[0] == 0x1f.toUByte() },
                "ERROR2 from scope preparation must not enter the channel-write query/accounting classifier",
            )
            radio.activeScopeFailure = null
            for (transient in listOf(
                com.meshcoreone.android.core.protocol.config.MeshCoreException.Timeout(),
                com.meshcoreone.android.core.protocol.config.MeshCoreException.ConnectionLost(),
                com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException(
                    com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError.NotConnected,
                ),
            )) {
                val transport = h.links.radios.last()
                val preparationStart = transport.sent.size
                val transmissionsBefore = radio.channelTransmissions.size
                val scopeOpcode = PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey())[0]
                var encountered = false
                transport.beforeSend = { command ->
                    if (command[0] == scopeOpcode) {
                        encountered = true
                        throw transient
                    }
                }
                val pending = enqueueActualChannel(h, before, "Transient scope preparation ${transient.javaClass.simpleName}")
                val pendingKey = com.meshcoreone.android.core.contracts.domain.EntityKey(before.radioId, pending.id)
                try {
                    h.eventually("transient scope failure parks the real pending message") {
                        encountered && kotlinx.coroutines.runBlocking {
                            store.fetchPendingSendsForMessage(pendingKey).firstOrNull()?.attemptCount?.let { it > 0 } == true &&
                                store.fetchMessage(pendingKey)?.status == MessageStatus.PENDING
                        }
                    }
                    h.settle()
                    assertTrue(store.hasPendingSend(pendingKey), "Pre-send transient failures cannot delete the unsent envelope")
                    assertEquals(MessageStatus.PENDING, store.fetchMessage(pendingKey)?.status)
                    assertEquals(transmissionsBefore, radio.channelTransmissions.size)
                    assertFalse(transport.sent.drop(preparationStart).any { it[0] == 0x1f.toUByte() }, "A scope timeout/lost connection is not a channel ERROR2")
                } finally { transport.beforeSend = {} }
                // A failed serialized scope exchange can retire its session; recover through the real runtime.
                h.manager.disconnect()
                h.manager.connect(h.target())
                h.eventually("the same parked envelope transmits after scope preparation recovers") {
                    kotlinx.coroutines.runBlocking {
                        store.fetchMessage(pendingKey)?.status == MessageStatus.SENT && !store.hasPendingSend(pendingKey)
                    }
                }
            }
            val sentBefore = radio.channelTransmissions.size
            val sent = enqueueActualChannel(h, before, "scope preparation retried")
            h.eventually("positive actual send follows successful scope application") {
                radio.channelTransmissions.size == sentBefore + 1 && kotlinx.coroutines.runBlocking {
                    store.fetchMessage(com.meshcoreone.android.core.contracts.domain.EntityKey(before.radioId, sent.id))
                        ?.status == com.meshcoreone.android.core.model.MessageStatus.SENT
                }
            }
            val frames = h.links.radios.last().sent
            assertTrue(frames.indexOf(radio.channelTransmissions.last()) > frames.lastIndexOf(radio.activeScopes.last()))
            assertEquals("EU868", radio.mode)
        }
    }

    @Test fun acceptedChannelIsNotRetriedWhenSettingsContextLosesConnectionAfterTheWrite() = runTest {
        withSettings { h, holder, _, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            store.saveChannel(ChannelDTO(radioId = before.radioId, index = 1u, name = "Accepted scope control"))
            holder.select("EU868")
            h.eventually("default acknowledged") { !holder.state.value.isApplying && holder.state.value.currentScope == "EU868" }
            val session = h.sessions.current!!
            var connectionWithdrawn = false
            radio.onChannelWrite = {
                kotlinx.coroutines.runBlocking {
                    h.manager.setConnectionState(com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.CONNECTING)
                }
                connectionWithdrawn = true
            }
            try {
                val message = enqueueActualChannel(h, before, "accepted before scope context post-check")
                val key = com.meshcoreone.android.core.contracts.domain.EntityKey(before.radioId, message.id)
                h.eventually("radio acceptance remains sent despite Settings post-check disconnection") {
                    connectionWithdrawn && kotlinx.coroutines.runBlocking {
                        store.fetchMessage(key)?.status == MessageStatus.SENT && !store.hasPendingSend(key)
                    }
                }
                assertFalse(h.manager.connectionState.isConnected)
                assertEquals(session.token, h.manager.snapshot.value.token)
                assertEquals(1, radio.channelTransmissions.size)
                radio.onChannelWrite = null
                h.manager.setConnectionState(com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY)
                session.chatSendQueueService.transportDidOpen()
                h.settle()
                assertEquals(1, radio.channelTransmissions.size, "Post-action connection loss cannot resend an accepted envelope")
                assertEquals(MessageStatus.SENT, store.fetchMessage(key)?.status)
                assertFalse(store.hasPendingSend(key))
            } finally {
                radio.onChannelWrite = null
                h.manager.setConnectionState(com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY)
            }
        }
    }

    @Test fun successorGenerationReconcilesARadioWriteThatOutlivedTheOriginalPersistenceConsumer() = runTest {
        withSettings { h, holder, _, radio ->
            holder.select("US915")
            h.eventually("original durable baseline") {
                !holder.state.value.isApplying && holder.state.value.currentScope == "US915"
            }
            val first = h.sessions.current!!
            val retired = first.settingsService.events()
            holder.select("EU868")
            h.eventually("B is on the radio but its old consumer has not acknowledged") {
                radio.mode == "EU868" && holder.state.value.isApplying
            }
            assertEquals("US915", store.fetchDevice(h.manager.connectedDevice!!.id)!!.defaultFloodScopeName)
            h.manager.disconnect()
            h.manager.connect(h.target())
            h.eventually("S2 becomes ready without adopting the S1 completion") {
                h.sessions.current != null && h.sessions.current !== first && h.appState.canRunSettingsStartupReads
            }
            val current = assertNotNull(h.manager.connectedDevice)
            val channel = ChannelDTO(radioId = current.radioId, index = 1u, name = "Inherited after reconnect")
            store.saveChannel(channel)
            val readsBefore = radio.defaultReads
            h.container.chatsFeature.timeline(TimelineConversation.Channel(channel)).syncChannelFloodScope(channel)
            assertTrue(radio.defaultReads > readsBefore, "S2 must establish its own actual-radio baseline")
            assertEquals("EU868", store.fetchDevice(current.id)!!.defaultFloodScopeName)
            assertEquals("EU868", h.manager.connectedDevice!!.defaultFloodScopeName)
            assertEquals(PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey()), radio.activeScopes.last())
            assertEquals(listOf<String?>("US915", "EU868"), radio.writes, "No invented rollback or successor write")
            assertTrue(retired.events.toList().isEmpty())
        }
    }

    @Test fun sameSessionRewireKeepsQueuedScopeAcknowledgementUntilRoomCommits() = runTest {
        assertSameSessionRewireKeepsChannelAcknowledgement(queued = true)
    }

    @Test fun sameSessionRewireKeepsNotificationScopeAcknowledgementUntilRoomCommits() = runTest {
        assertSameSessionRewireKeepsChannelAcknowledgement(queued = false)
    }

    private suspend fun TestScope.assertSameSessionRewireKeepsChannelAcknowledgement(queued: Boolean) {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store {
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
            withSettings(held) { h, _, failures, radio ->
                val device = assertNotNull(h.manager.connectedDevice)
                val session = assertNotNull(h.sessions.current)
                val channel = ChannelDTO(radioId = device.radioId, index = 1u, name = "Rewired scope")
                store.saveChannel(channel)
                radio.channel = channel
                radio.mode = "EU868"
                armed = true
                val message = if (queued) enqueueActualChannel(h, device, "Queued rewire") else null
                val reply = if (queued) null else backgroundScope.launch {
                    session.notificationActionHandler.handleChannelQuickReply(device.radioId, 1u, "Notification rewire")
                }
                h.eventually("scope acknowledgement is waiting on the actual Room write") { entered.isCompleted }
                assertTrue(radio.channelTransmissions.isEmpty())
                assertNull(store.fetchDevice(device.id)!!.defaultFloodScopeName)
                val rewire = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    h.appState.wireServicesIfConnected()
                }
                h.settle()
                assertTrue(radio.channelTransmissions.isEmpty())
                if (reply != null) assertFalse(reply.isCompleted)
                assertEquals(session.token, h.sessions.current?.token)
                release.complete(Unit)
                h.eventually("the same acknowledgement permits exactly one send without another ready transition") {
                    radio.channelTransmissions.size == 1 && (reply == null || reply.isCompleted) &&
                        (message == null || !store.hasPendingSend(EntityKey(device.radioId, message.id)))
                }
                rewire.join()
                reply?.join()
                assertEquals("EU868", h.manager.connectedDevice!!.defaultFloodScopeName)
                assertEquals("EU868", store.fetchDevice(device.id)!!.defaultFloodScopeName)
                if (message != null) assertEquals(
                    MessageStatus.SENT, store.fetchMessage(EntityKey(device.radioId, message.id))!!.status,
                )
                assertEquals(listOf(PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey())), radio.activeScopes)
                assertTrue(radio.writes.isEmpty(), "Reading the baseline must not invent a default-scope write")
                assertTrue(failures.isEmpty())
            }
        } finally { release.complete(Unit) }
    }

    @Test fun actualInFlightScopeSubscriberReplacementReconcilesWithoutReportingTheOldActionSuccessful() = runTest {
        withSettings { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            var rewire: Job? = null
            radio.onWrite = {
                radio.onWrite = null
                // Explicit teardown replaces the subscriber; an ordinary same-session rewire keeps it.
                rewire = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    h.appState.tearDownAppStateSessionState()
                    h.appState.wireServicesIfConnected()
                }
            }
            holder.select("EU868")
            h.eventually("replacement consumer acknowledges the actual in-flight radio scope") {
                !holder.state.value.isApplying && failures.isNotEmpty() &&
                    !h.sessions.current!!.settingsService.defaultFloodScopeNeedsRecovery &&
                    h.manager.connectedDevice?.defaultFloodScopeName == "EU868"
            }
            assertNotNull(rewire).join()
            assertNotNull(
                holder.state.value.errorMessage ?: holder.retryAlertState.value.message,
                "The retired action must not claim success",
            )
            assertTrue(holder.state.value.errorMessage != null || holder.retryAlertState.value.isPresented)
            assertEquals("EU868", radio.mode)
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), h.manager.connectedDevice)
            assertEquals(listOf<String?>("EU868"), radio.writes)
            holder.select("EU868")
            h.eventually("same-session retry uses the current consumer") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null
            }
            assertEquals(listOf<String?>("EU868", "EU868"), radio.writes)
        }
    }

    @Test fun differentTargetAliasCasRacesReplanActualRadioInBothOrdersAndPreserveDisabledSurvivors() = runTest {
        var completed = 0
        for (prefix in listOf("A".repeat(30), "é".repeat(15))) {
            for (removeShort in listOf(false, true)) {
                for (scoped in listOf(false, true)) {
                    val longer = prefix + "suffix"
                    val target = if (removeShort) prefix else longer
                    val other = if (removeShort) longer else prefix
                    val entered = CompletableDeferred<Unit>()
                    val release = CompletableDeferred<Unit>()
                    var armed = false
                    var rejectedHeldTicket = 0
                    val held = object : PersistenceStoreProtocol by store {
                        override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                            val heldTicket = armed && target !in dto.knownRegions && other in dto.knownRegions
                            if (heldTicket) {
                                armed = false
                                entered.complete(Unit)
                                release.await()
                            }
                            val accepted = store.saveDeviceIfUnchanged(dto, expected)
                            if (heldTicket && !accepted) rejectedHeldTicket++
                            return accepted
                        }
                    }
                    try {
                        withSettings(held) { h, holder, failures, radio ->
                            val before = assertNotNull(h.manager.connectedDevice)
                            val session = h.sessions.current!!
                            com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, prefix)
                            com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, longer)
                            val targetChannel = store.fetchChannel(before.radioId, 1u)
                                ?: ChannelDTO(radioId = before.radioId, index = 1u, name = "Target").also {
                                    store.saveChannel(it)
                                }
                            val otherChannel = store.fetchChannel(before.radioId, 2u)
                                ?: ChannelDTO(radioId = before.radioId, index = 2u, name = "Other").also {
                                    store.saveChannel(it)
                                }
                            store.setChannelFloodScope(EntityKey(before.radioId, targetChannel.id), ChannelFloodScope.Region(target))
                            store.setChannelFloodScope(EntityKey(before.radioId, otherChannel.id), ChannelFloodScope.Region(other))
                            assertEquals(ChannelFloodScope.Region(target), store.fetchChannel(before.radioId, 1u)!!.floodScope)
                            assertEquals(ChannelFloodScope.Region(other), store.fetchChannel(before.radioId, 2u)!!.floodScope)
                            h.eventually("mixed aliases observed") { longer in holder.state.value.sortedKnownRegions }
                            if (scoped) {
                                holder.select(target)
                                h.eventually("actual selected scope acknowledged") {
                                    !holder.state.value.isApplying && holder.state.value.currentScope == prefix
                                }
                            }
                            armed = true
                            val removal = async { removeSettingsKnownRegion(h.container, session, target) }
                            h.eventually("actual Settings removal is paused before its Room CAS") { entered.isCompleted }
                            store.removeDeviceKnownRegion(before.radioId, other)
                            val contested = assertNotNull(store.fetchDevice(before.id))
                            assertTrue(target in contested.knownRegions)
                            assertFalse(other in contested.knownRegions)
                            assertEquals(if (scoped) prefix else null, contested.defaultFloodScopeName)
                            assertEquals(if (scoped) prefix else null, radio.mode)
                            assertEquals(ChannelFloodScope.Region(target), store.fetchChannel(before.radioId, 1u)!!.floodScope)
                            release.complete(Unit)
                            h.eventually("conditional editor removal readmits the actual scope transition") { removal.isCompleted }
                            removal.await()
                            assertEquals(1, rejectedHeldTicket, "The stale actual Room ticket must be rejected")
                            assertNull(radio.mode)
                            assertEquals(before, store.fetchDevice(before.id))
                            assertEquals(before, h.manager.connectedDevice)
                            assertEquals(ChannelFloodScope.Inherit, store.fetchChannel(before.radioId, 1u)!!.floodScope)
                            assertEquals(ChannelFloodScope.Inherit, store.fetchChannel(before.radioId, 2u)!!.floodScope)
                            assertEquals(if (scoped) listOf(prefix, null) else emptyList<String?>(), radio.writes)
                            assertTrue(failures.isEmpty())
                            completed++
                        }
                    } finally { release.complete(Unit) }
                }
            }
        }
        assertEquals(8, completed)
    }

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
                    h.manager.connectedDevice?.defaultFloodScopeName == "EU868" && holder.state.value.currentScope == "EU868"
            }
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
            assertEquals(listOf<String?>("EU868", "EU868"), radio.writes)
        }
    }

    @Test fun selectionWaitsForRoomAndRemovalQueuesBeforeSamplingTheNewDefault() = runTest {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var armed = false
        val held = object : PersistenceStoreProtocol by store {
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
                assertEquals(listOf<String?>("EU868"), radio.writes)
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
        val held = object : PersistenceStoreProtocol by store {
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
        val held = object : PersistenceStoreProtocol by store {
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
                h.settle()
                assertEquals(listOf<String?>("EU868"), radio.writes, "Accepted write retains the queue through its acknowledgment")
                assertEquals("EU868", radio.mode)
                assertFalse(removal.isCompleted)
                assertEquals(before, store.fetchDevice(before.id))
                release.complete(Unit)
                h.eventually("old write and clear complete before region removal acknowledges") { removal.isCompleted }
                removal.await()
                assertNull(radio.mode)
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
                "CREATE TRIGGER reject_scope_clear BEFORE UPDATE ON devices WHEN NEW.defaultFloodScopeName IS NULL " +
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
        val interleaving = object : PersistenceStoreProtocol by store {
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
            assertEquals(listOf<String?>("EU868"), radio.writes)
            assertEquals(before.copy(defaultFloodScopeName = "EU868", lastContactSync = 9876u), store.fetchDevice(before.id))
            assertEquals(store.fetchDevice(before.id), h.manager.connectedDevice)
        }
    }

    @Test fun bufferedOrdinaryReadAndCancelledClearCannotRestoreADeletedDefault() = runTest {
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
        val actionJob = SupervisorJob(backgroundScope.coroutineContext[Job])
        val actionScope = CoroutineScope(backgroundScope.coroutineContext + actionJob)
        try {
            withSettings(held, actionScope) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val session = h.sessions.current!!
                armed = true
                val edit = async { h.container.deviceEditor.updatePathHashMode(2u, session.token) }
                h.eventually("actual editor blocks ordinary read consumption") { entered.isCompleted }
                assertEquals("EU868", session.settingsService.setDefaultFloodScopeVerified("EU868"))
                assertNull(store.fetchDevice(before.id)!!.defaultFloodScopeName, "Original cached-null removal window")
                assertNull(h.manager.connectedDevice?.defaultFloodScopeName)
                holder.select(null)
                h.eventually("radio clear sent while old ordinary read waits") { radio.writes.size == 2 && radio.mode == null }
                actionJob.cancel()
                h.eventually("clear UI waiter cancelled") { !holder.state.value.isApplying }
                val removal = async { removeSettingsKnownRegion(h.container, session, "EU868") }
                h.settle()
                assertFalse(removal.isCompleted)
                release.complete(Unit)
                h.eventually("buffered read, accepted clear and removal finish in order") { removal.isCompleted }
                assertTrue(edit.await())
                removal.await()
                assertTrue(failures.isEmpty())
                assertNull(radio.mode)
                assertEquals(listOf("EU868", null), radio.writes)
                val expected = before.copy(pathHashMode = 2u, knownRegions = listOf("US915").snapshot())
                assertEquals(expected, store.fetchDevice(before.id))
                assertEquals(expected, h.manager.connectedDevice)
                h.settle()
                assertEquals(expected, store.fetchDevice(before.id), "No delayed ordinary scope restoration after removal ACK")
            }
        } finally { release.complete(Unit); actionJob.cancel() }
    }

    @Test fun mismatchedNameAndKeyReachTheActionAfterActualNamePersistenceAndCanBeRetried() = runTest {
        withSettings { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            radio.writeTransform = { if (it == "EU868") "US915" else it }
            holder.select("EU868")
            h.eventually("actual mismatched name persisted and reported") {
                !holder.state.value.isApplying && holder.state.value.errorMessage != null && holder.state.value.currentScope == "US915"
            }
            assertEquals("US915", radio.mode)
            assertEquals(before.copy(defaultFloodScopeName = "US915"), store.fetchDevice(before.id))
            radio.writeTransform = { it }
            radio.key = Bytes(ByteArray(16) { 7 })
            holder.select("EU868")
            h.eventually("same-name wrong-key readback fails instead of reporting success") {
                !holder.state.value.isApplying && holder.state.value.errorMessage != null && holder.state.value.currentScope == "EU868"
            }
            assertEquals(2, failures.size)
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
            radio.key = null
            holder.select("EU868")
            h.eventually("corrected name and key verify and persist") {
                !holder.state.value.isApplying && holder.state.value.errorMessage == null
            }
            assertEquals(listOf<String?>("EU868", "EU868", "EU868"), radio.writes)
            assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
        }
    }

    @Test fun normalizedAliasRemovalPreservesRadioScopeAndUpdatesTheActualPicker() = runTest {
        withSettings { h, holder, failures, radio ->
            val session = h.sessions.current!!
            val prefix = "A".repeat(30)
            val first = prefix + "one"
            val second = prefix + "two"
            assertTrue(h.container.deviceEditor.addKnownRegion(first, session.token))
            assertTrue(h.container.deviceEditor.addKnownRegion(second, session.token))
            h.eventually("newly committed aliases observed by picker") { second in holder.state.value.sortedKnownRegions }
            holder.select(first)
            h.eventually("normalized selected scope observed by picker") {
                !holder.state.value.isApplying && holder.state.value.currentScope == prefix
            }
            removeSettingsKnownRegion(h.container, session, second)
            h.eventually("removed alias disappears from actual picker") { second !in holder.state.value.sortedKnownRegions }
            assertTrue(first in holder.state.value.sortedKnownRegions)
            assertEquals(prefix, radio.mode)
            assertEquals(prefix, holder.state.value.currentScope)
            assertEquals(prefix, store.fetchDevice(h.manager.connectedDevice!!.id)!!.defaultFloodScopeName)
            assertEquals(listOf<String?>(prefix), radio.writes, "Removing a different raw alias must not clear the selected scope")
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun removingTheExactThirtyByteAliasPreservesItsLongSurvivorUntilTheLastRemoval() = runTest {
        withSettings { h, holder, failures, radio ->
            val before = assertNotNull(h.manager.connectedDevice)
            val session = h.sessions.current!!
            val prefix = "A".repeat(30)
            val longer = prefix + "suffix"
            com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, prefix)
            com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, longer)
            h.eventually("mixed aliases observed") { longer in holder.state.value.sortedKnownRegions }
            holder.select(prefix)
            h.eventually("exact-length alias committed") { !holder.state.value.isApplying && holder.state.value.currentScope == prefix }
            val rows = com.meshcoreone.android.app.navigation.SettingsKnownRegionRows(session, listOf(prefix, longer))
            removeSettingsKnownRegion(h.container, rows, prefix)
            assertEquals(prefix, radio.mode)
            assertEquals(prefix, store.fetchDevice(before.id)!!.defaultFloodScopeName)
            assertEquals(prefix, h.manager.connectedDevice?.defaultFloodScopeName)
            assertEquals(listOf<String?>(prefix), radio.writes)
            assertTrue(longer in store.fetchDevice(before.id)!!.knownRegions)
            removeSettingsKnownRegion(h.container, session, longer)
            assertNull(radio.mode)
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
            assertEquals(listOf(prefix, null), radio.writes)
            assertTrue(failures.isEmpty())
        }
    }

    @Test fun retiredVisibleRegionRowsCannotDeleteOrClearTheSuccessorSession() = runTest {
        withSettings { h, holder, _, radio ->
            val first = h.sessions.current!!
            holder.select("EU868")
            h.eventually("original scope saved") { !holder.state.value.isApplying && holder.state.value.currentScope == "EU868" }
            val rows = com.meshcoreone.android.app.navigation.SettingsKnownRegionRows(first, listOf("EU868", "US915"))
            h.manager.disconnect()
            h.manager.connect(h.target())
            h.eventually("successor ready for Settings") {
                h.sessions.current != null && h.sessions.current !== first && h.appState.canRunSettingsStartupReads
            }
            val before = assertNotNull(h.manager.connectedDevice)
            val writes = radio.writes.toList()
            kotlin.test.assertFailsWith<SettingsNotConnectedException> {
                removeSettingsKnownRegion(h.container, rows, "EU868")
            }
            assertEquals(writes, radio.writes)
            assertEquals(before, store.fetchDevice(before.id))
            assertEquals(before, h.manager.connectedDevice)
        }
    }

    @Test fun cancelledInFlightScopeAutomaticallyReconcilesThroughTheReplacementActualConsumerBeforeRouting() = runTest {
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        val actions = CoroutineScope(backgroundScope.coroutineContext + job)
        try {
            withSettings(actionScope = actions) { h, holder, failures, radio ->
                val before = assertNotNull(h.manager.connectedDevice)
                val service = h.sessions.current!!.settingsService
                val retired = service.events()
                holder.select("EU868")
                h.eventually("verified scope awaits its unstarted consumer") {
                    radio.mode == "EU868" && holder.state.value.isApplying &&
                        h.links.radios.single().sent.any { it[0] == 0x40.toUByte() }
                }
                assertNull(store.fetchDevice(before.id)!!.defaultFloodScopeName)
                job.cancel()
                h.eventually("original UI waiter is cancelled") { !holder.state.value.isApplying }
                val route = async {
                    val channel = ChannelDTO(radioId = before.radioId, index = 0u, name = "Scope control")
                    h.container.chatsFeature.timeline(TimelineConversation.Channel(channel)).syncChannelFloodScope(channel)
                }
                h.settle()
                assertFalse(route.isCompleted, "Dependent routing cannot observe the old cached default")
                assertTrue(radio.activeScopes.isEmpty(), "Unacknowledged scope must not reach channel routing")
                h.appState.wireServicesIfConnected()
                h.eventually("actual replacement consumer acknowledges reconciliation") {
                    route.isCompleted && !service.defaultFloodScopeNeedsRecovery
                }
                route.await()
                assertEquals(
                    listOf(PacketBuilder.setFloodScope(FloodScope.Region("EU868").scopeKey())),
                    radio.activeScopes,
                    "The actual channel routing command must use the acknowledged default, not stale cached Disabled",
                )
                assertTrue(retired.events.toList().isEmpty())
                assertEquals(before.copy(defaultFloodScopeName = "EU868"), store.fetchDevice(before.id))
                assertEquals(before.copy(defaultFloodScopeName = "EU868"), h.manager.connectedDevice)
                assertEquals(listOf<String?>("EU868"), radio.writes, "Recovery reads actual state; it does not invent a rollback write")
                assertTrue(failures.isEmpty())
            }
        } finally { job.cancel() }
    }

    @Test fun queuedConcurrentAliasMutationsKeepRadioRoomAndLiveDecisionsInTheSameOrder() = runTest {
        for (addSurvivor in listOf(false, true)) {
            val prefix = "A".repeat(30)
            val longer = prefix + "old"
            val next = prefix + "new"
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var armed = false
            val held = object : PersistenceStoreProtocol by store {
                override suspend fun saveDeviceIfUnchanged(dto: DeviceDTO, expected: DeviceWriteSnapshot): Boolean {
                    if (armed && prefix !in dto.knownRegions && longer in dto.knownRegions) {
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
                    val session = h.sessions.current!!
                    com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, prefix)
                    com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, longer)
                    h.eventually("mixed aliases ready") { longer in holder.state.value.sortedKnownRegions }
                    holder.select(prefix)
                    h.eventually("selected prefix committed") { !holder.state.value.isApplying && holder.state.value.currentScope == prefix }
                    armed = true
                    val first = async { removeSettingsKnownRegion(h.container, session, prefix) }
                    h.eventually("first actual removal held at Room CAS") { entered.isCompleted }
                    val addition = if (addSurvivor) async {
                        com.meshcoreone.android.app.navigation.addSettingsKnownRegion(h.container, session, next)
                    } else null
                    h.settle()
                    val second = async { removeSettingsKnownRegion(h.container, session, longer) }
                    h.settle()
                    assertFalse(second.isCompleted)
                    if (addition != null) assertFalse(addition.isCompleted)
                    assertEquals(prefix, radio.mode)
                    assertEquals(listOf<String?>(prefix), radio.writes)
                    assertTrue(prefix in store.fetchDevice(before.id)!!.knownRegions)
                    release.complete(Unit)
                    h.eventually("all queued alias decisions acknowledged") {
                        first.isCompleted && second.isCompleted && addition?.isCompleted != false
                    }
                    first.await(); addition?.await(); second.await()
                    if (addSurvivor) {
                        val expected = before.copy(
                            knownRegions = (before.knownRegions + next).snapshot(), defaultFloodScopeName = prefix,
                        )
                        assertEquals(prefix, radio.mode)
                        assertEquals(expected, store.fetchDevice(before.id))
                        assertEquals(expected, h.manager.connectedDevice)
                        assertEquals(listOf<String?>(prefix), radio.writes)
                        removeSettingsKnownRegion(h.container, session, next)
                    }
                    assertNull(radio.mode)
                    assertEquals(before, store.fetchDevice(before.id))
                    assertEquals(before, h.manager.connectedDevice)
                    assertEquals(listOf(prefix, null), radio.writes)
                    assertTrue(failures.isEmpty())
                }
            } finally { release.complete(Unit) }
        }
    }

    private suspend fun enqueueActualChannel(h: ContainerHarness, device: DeviceDTO, text: String): com.meshcoreone.android.core.model.MessageDTO {
        val sender = h.container.chatsFeature.sender(device.radioId)
        val message = sender.createPendingChannel(text, 1u, device.radioId, com.meshcoreone.android.core.model.TextType.PLAIN)
        sender.enqueueChannel(com.meshcoreone.android.core.model.ChannelMessageEnvelope(
            message.id, 1u, false, message.text, message.timestamp, null,
        ))
        return message
    }

    private class ScopeRadio {
        var firmwareVersion: UByte = 11u
        val radioWrites = mutableListOf<Bytes>()
        var radioWriteTransform: (Bytes) -> Bytes = { it }
        val powerWrites = mutableListOf<Bytes>()
        var powerFailure: UByte? = null
        private var radioParams: Bytes? = null
        private var txPower: Byte? = null
        private var repeatEnabled = false
        var mode: String? = null
        var key: Bytes? = null
        var writeTransform: (String?) -> String? = { it }
        val writes = mutableListOf<String?>()
        val activeScopes = mutableListOf<Bytes>()
        val channelTransmissions = mutableListOf<Bytes>()
        var channel: ChannelDTO? = null
        var onChannelWrite: (() -> Unit)? = null
        var defaultReads = 0
        var onWrite: (() -> Unit)? = null
        var activeScopeFailure: UByte? = null
        fun configure(radio: FirmwareRadio) {
            radio.firmwareVersion = firmwareVersion
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x0b -> {
                        check(command.size == 11 || command.size == 12) { "Invalid SET_RADIO frame length ${command.size}" }
                        radioWrites += command
                        radioParams = radioWriteTransform(command).slice(1, 11)
                        repeatEnabled = command.size == 12 && command[11] != 0u.toUByte()
                        listOf(Bytes.of(0))
                    }
                    0x0c -> {
                        powerWrites += command
                        powerFailure?.let { listOf(Bytes.of(1, it.toInt())) } ?: run {
                            txPower = command[1].toByte()
                            listOf(Bytes.of(0))
                        }
                    }
                    0x01 -> if (radioParams == null && txPower == null) null else {
                        val self = radio.selfPacket().toByteArray()
                        txPower?.let { self[2] = it }
                        val packet = Bytes(self)
                        listOf(radioParams?.let {
                            packet.slice(0, 48) + it + Bytes.utf8("TestNode")
                        } ?: packet)
                    }
                    0x16 -> if (radioParams != null) listOf(
                        Bytes.of(13, 11, 50, 8) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                            Bytes.utf8("T-Deck").paddedOrTruncated(40) +
                            Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(if (repeatEnabled) 1 else 0, 0),
                    ) else null
                    0x03 -> {
                        channelTransmissions += command
                        onChannelWrite?.invoke()
                        listOf(Bytes.of(0))
                    }
                    0x1f -> channel?.takeIf { it.index == command[1] }?.let {
                        listOf(Bytes.of(0x12, it.index.toInt()) + Bytes.utf8(it.name).paddedOrTruncated(32) + it.secret)
                    }
                    PacketBuilder.setFloodScope(Bytes(ByteArray(16)))[0].toInt() -> {
                        activeScopes += command
                        listOf(activeScopeFailure?.let { Bytes.of(1, it.toInt()) } ?: Bytes.of(0))
                    }
                    0x3f -> {
                        val requested = if (command.size == 1) null else
                            command.slice(1, 32).toByteArray().toString(Charsets.UTF_8).substringBefore('\u0000')
                        writes += requested
                        mode = writeTransform(requested)
                        onWrite?.invoke()
                        listOf(Bytes.of(0))
                    }
                    0x40 -> {
                        defaultReads++
                        listOf(Bytes.of(0x1c) + (mode?.let {
                        Bytes.utf8(it).paddedOrTruncated(31) + (key ?: FloodScope.Region(it).scopeKey())
                        } ?: Bytes.EMPTY))
                    }
                    else -> null
                }
            }
        }
    }

    private suspend fun TestScope.withSettings(
        persistence: PersistenceStoreProtocol = store,
        actionScope: CoroutineScope? = null,
        exposeDependencies: (SettingsFeatureDependencies) -> Unit = {},
        firmwareVersion: UByte = 11u,
        action: suspend (ContainerHarness, DefaultFloodScopeStateHolder, MutableList<Throwable>, ScopeRadio) -> Unit,
    ) {
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val radio = ScopeRadio().also { it.firmwareVersion = firmwareVersion }
        var holder: DefaultFloodScopeStateHolder? = null
        try {
            h.links.configureRadio = radio::configure
            h.connectForegroundSettings()
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
            exposeDependencies(deps)
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
