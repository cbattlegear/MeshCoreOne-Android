// AndroidOnly: WP-209 Approved fresh-session channel recovery through the real App, runtime, session and Room.
// AndroidOnly: WP-207 Physical teardown, cancellation and stale-generation isolation companions.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.ConnectionTarget
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.runtime.RuntimeDiagnostic
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.contacts.ChannelServiceError
import com.meshcoreone.android.core.services.sync.SyncClock
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelRecoveryIntegrationTest : RoomProcessTest() {
    @Test fun partialDrainCommitsBeforeTeardownAndFreshHandshakeActuallyRecoversMissingSlot() = runTest {
        withRecovery { f ->
            f.awaitPartialCommit()
            val oldGraph = assertNotNull(f.h.sessions.current)
            val oldCallbacks = assertNotNull(f.h.links.links.single().callbacks)
            val oldRadio = f.h.links.radios.single()
            assertEquals(0, oldRadio.closes, "controller must await actual channel persistence")
            assertTrue(oldRadio.connected)
            assertEquals(1, f.h.links.radios.size)
            assertEquals(f.radioId, assertNotNull(f.h.manager.lastAttemptedChannelSync).first)
            assertEquals(listOf(0, 1, 2, 3), f.channelRequests(oldRadio))
            assertFailsWith<ChannelServiceError.NotConnected> { oldGraph.channelService.fetchChannel(2u) }
            oldRadio.push(f.channelPacket(2, "stale"))
            f.h.scheduler.runCurrent()
            assertEquals("keep2", store.fetchChannel(f.radioId, 2u)?.name)

            f.commitGate.complete(Unit)
            f.awaitRecovered()
            val fresh = assertNotNull(f.h.sessions.current)
            val newRadio = f.h.links.radios.last()
            assertNotSame(oldGraph, fresh)
            assertTrue(oldGraph.isTornDown)
            assertTrue(fresh.token.generation.value > oldGraph.token.generation.value)
            assertEquals(1, oldRadio.closes)
            assertFalse(oldRadio.connected)
            assertEquals(0, oldRadio.readers)
            assertEquals(1, newRadio.connects)
            assertEquals(1, newRadio.readers)
            assertEquals(1, newRadio.sent.count { it[0].toInt() == 1 }, "fresh appStart handshake")
            assertEquals(listOf(0, 1, 2, 3), f.channelRequests(newRadio), "recent attempted sync must not skip recovery")
            assertEquals(listOf(0, 1, 2, 3), f.channelRequests(oldRadio), "no old-connection reconciliation")
            assertEquals(listOf("new0", "keep2", "new3"), f.partialRows.map { it.name })
            assertEquals(f.oldMissing, f.partialRows.single { it.index == 2.toUByte() })
            assertEquals(listOf("new0", "recovered", "new3"), store.fetchChannels(f.radioId).map { it.name })
            assertEquals(f.oldMissing.id, store.fetchChannel(f.radioId, 2u)?.id)
            assertEquals(1, f.h.sessions.outstanding)
            assertNotNull(f.h.manager.lastCleanChannelSync)

            oldCallbacks.onDisconnected(IllegalStateException("late old callback"))
            oldCallbacks.onReconnected()
            oldRadio.push(f.channelPacket(2, "stale-after-reconnect"))
            f.h.settle()
            f.h.assertReady()
            assertEquals(2, f.h.links.radios.size)
            assertEquals("recovered", store.fetchChannel(f.radioId, 2u)?.name)
            assertTrue(f.h.diagnostics.any { it is RuntimeDiagnostic.StaleCallback })
        }
    }

    @Test fun userDisconnectCancelsHeldPartialCommitAndNeverReconnects() = runTest {
        withRecovery { f ->
            f.awaitPartialCommit()
            val old = f.h.links.radios.single()
            val graph = assertNotNull(f.h.sessions.current)
            f.h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            f.h.settle()
            assertEquals(DeviceConnectionState.DISCONNECTED, f.h.manager.connectionState)
            assertFalse(f.h.manager.connectionIntent.wantsConnection)
            assertTrue(graph.isTornDown)
            assertEquals(0, graph.liveJobCount)
            assertEquals(1, old.closes)
            assertEquals(0, old.readers)
            assertEquals(1, f.h.links.radios.size)
            assertEquals(f.oldMissing, store.fetchChannel(f.radioId, 2u))
            f.commitGate.complete(Unit)
            f.h.settle()
            assertEquals(1, f.h.links.radios.size)
            assertEquals(0, f.h.sessions.outstanding)
        }
    }

    @Test fun callerCancellationBeforeCommitDoesNotPublishPartialRowsOrReportRecoverySuccess() = runTest {
        withRecovery { f ->
            f.awaitPartialCommit()
            f.connecting.cancelAndJoin()
            f.commitGate.complete(Unit)
            f.h.settle()
            assertEquals(f.oldMissing, store.fetchChannel(f.radioId, 2u))
            assertEquals("keep0", store.fetchChannel(f.radioId, 0u)?.name)
            assertEquals(DeviceConnectionState.DISCONNECTED, f.h.manager.connectionState)
            assertEquals(0, f.h.sessions.outstanding)
            assertTrue(f.h.links.radios.all { !it.connected && it.readers == 0 })
            assertEquals(1, f.h.links.radios.size)
        }
    }

    @Test fun failedFreshConnectionRetainsPartialRowsAndCannotBecomeReady() = runTest {
        withRecovery(failReconnect = true) { f ->
            f.awaitPartialCommit()
            f.commitGate.complete(Unit)
            f.h.eventually("fresh connection failure is observable", virtualBudgetMillis = 2_000) {
                f.h.links.radios.size >= 2 && f.h.diagnostics.any {
                    it is RuntimeDiagnostic.Failure && it.operation.startsWith("wifi.reconnect")
                }
            }
            assertFalse(f.h.manager.connectionState == DeviceConnectionState.READY)
            assertEquals(f.oldMissing, store.fetchChannel(f.radioId, 2u))
            assertEquals("new0", store.fetchChannel(f.radioId, 0u)?.name)
            assertEquals(1, f.h.links.radios.first().closes)
            assertTrue(f.h.links.radios.drop(1).all { it.sent.isEmpty() })
            f.h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            f.h.settle()
            assertEquals(0, f.h.sessions.outstanding)
        }
    }

    @Test fun serialStallRetiresFurtherReadsAndRecoversOnlyOnFreshTransport() = runTest {
        withRecovery(serial = true) { f ->
            f.awaitPartialCommit()
            val old = f.h.links.radios.single()
            assertEquals(listOf(0, 1, 2), f.channelRequests(old), "neither timeout retry nor later slot may reach the retired session")
            assertEquals(0, old.closes)
            assertEquals(0, old.pipelinedReadChecks, "exercise actual serial service/controller path")
            f.commitGate.complete(Unit)
            f.awaitRecovered()
            assertEquals(listOf("new0", "keep2", "keep3"), f.partialRows.map { it.name })
            assertEquals(f.oldMissing, f.partialRows.single { it.index == 2.toUByte() })
            assertEquals(1, old.closes)
            assertEquals(0, old.readers)
            val fresh = f.h.links.radios.last()
            assertEquals(1, fresh.sent.count { it[0].toInt() == 1 })
            assertEquals(listOf(0, 1, 2, 3), f.channelRequests(fresh))
            assertEquals(0, fresh.pipelinedReadChecks)
            assertEquals(listOf("new0", "recovered", "new3"), store.fetchChannels(f.radioId).map { it.name })
            assertEquals(1, f.h.sessions.outstanding)
        }
    }

    private suspend fun TestScope.withRecovery(
        failReconnect: Boolean = false,
        serial: Boolean = false,
        body: suspend TestScope.(Fixture) -> Unit,
    ) {
        val f = Fixture(this, failReconnect, serial)
        try {
            f.seed()
            f.connecting = backgroundScope.async { f.h.manager.connect(f.target) }
            body(f)
        } finally {
            f.commitGate.complete(Unit)
            f.h.container.close()
        }
    }

    private inner class Fixture(test: TestScope, failReconnect: Boolean, private val serial: Boolean) {
        val radioId = RadioId(UUID.fromString("C3800000-0000-0000-0000-000000000001"))
        val deviceId = UUID.fromString("C3800000-0000-0000-0000-000000000002")
        val target = ConnectionTarget.WiFi("localhost", 5000u)
        val commitGate = CompletableDeferred<Unit>()
        val commitEntered = CompletableDeferred<Unit>()
        val partialRows = mutableListOf<ChannelDTO>()
        lateinit var oldMissing: ChannelDTO
        lateinit var connecting: kotlinx.coroutines.Deferred<Unit>
        private var commits = 0
        private val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun batchSaveChannels(
                radioId: RadioId, configured: SnapshotList<ChannelInfo>,
                unconfiguredIndices: SnapshotList<UByte>, pruneBeyond: UByte?,
            ): SnapshotList<ChannelDTO> {
                if (radioId == this@Fixture.radioId && commits++ == 0) {
                    assertEquals(if (serial) listOf<UByte>(0u) else listOf<UByte>(0u, 3u), configured.map { it.index })
                    assertEquals(listOf<UByte>(1u), unconfiguredIndices.toList())
                    commitEntered.complete(Unit)
                    commitGate.await()
                    return store.batchSaveChannels(radioId, configured, unconfiguredIndices, pruneBeyond).also {
                        partialRows += it
                    }
                }
                return store.batchSaveChannels(radioId, configured, unconfiguredIndices, pruneBeyond)
            }
        }
        val h = ContainerHarness(
            test, store,
            persistenceOverride = persistence,
            recoveryDispatcher = StandardTestDispatcher(test.testScheduler),
            syncClock = object : SyncClock {
                override fun now() = EPOCH.plusMillis(test.testScheduler.currentTime)
                override fun elapsed() = test.testScheduler.currentTime.milliseconds
                override suspend fun sleep(duration: Duration) = delay(duration)
            },
            sessionConfiguration = SessionConfiguration(channelPipelineWindow = 4),
        ).also { harness ->
            harness.container.foreground.activityStarted()
            harness.links.configureRadio = { radio ->
                val first = harness.links.radios.isEmpty()
                radio.modelName = if (serial) "T1000-E" else "Heltec V3"
                radio.pipelinedReadsSupported = true
                if (!first && failReconnect) radio.connectFailure = IllegalStateException("fresh connection unavailable")
                radio.respond = { command ->
                    when (command[0].toInt()) {
                        0x16 -> listOf(
                            Bytes.of(13, 9, 50, 4) + le32(0) + Bytes.utf8("01 Jan 2025").paddedOrTruncated(12) +
                                Bytes.utf8(radio.modelName).paddedOrTruncated(40) +
                                Bytes.utf8("v1.13.0").paddedOrTruncated(20) + Bytes.of(0),
                        )
                        0x1f -> {
                            val index = command[1].toInt()
                            when {
                                first && index == 2 -> emptyList()
                                index == 1 -> listOf(Bytes.of(0x12, 1) + Bytes(ByteArray(48)))
                                else -> listOf(channelPacket(index, if (index == 2) "recovered" else "new$index"))
                            }
                        }
                        else -> null
                    }
                }
            }
        }

        suspend fun seed() {
            store.saveDevice(DeviceDTO(
                id = deviceId, radioId = radioId, publicKey = key(7), nodeName = "Recovery",
                connectionMethods = SnapshotList.of(ConnectionMethod.WiFi("localhost", 5000u)),
            ))
            for (index in 0..3) store.saveChannel(radioId, ChannelInfo(index.toUByte(), "keep$index", Bytes(ByteArray(16) { 0x44 })))
            oldMissing = assertNotNull(store.fetchChannel(radioId, 2u))
        }

        fun channelPacket(index: Int, name: String): Bytes =
            Bytes.of(0x12, index) + Bytes.utf8(name).paddedOrTruncated(32) + Bytes(ByteArray(16) { 0x55 })

        fun channelRequests(radio: FirmwareRadio) =
            radio.sent.filter { it[0].toInt() == 0x1f }.map { it[1].toInt() }

        fun awaitPartialCommit() = h.eventually(
            "actual channel read reaches partial Room commit", virtualBudgetMillis = if (serial) 6_000 else 2_000, virtualStepMillis = 10,
        ) { commitEntered.isCompleted }

        fun awaitRecovered() = h.eventually(
            "runtime closes old transport, reconnects and completes fresh sync", virtualBudgetMillis = 10_000, virtualStepMillis = 10,
        ) { h.links.radios.size == 2 && h.manager.connectionState == DeviceConnectionState.READY }
    }
}
