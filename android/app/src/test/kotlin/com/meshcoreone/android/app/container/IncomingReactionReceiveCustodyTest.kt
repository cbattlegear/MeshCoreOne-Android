// AndroidOnly: WP-214 Real graph/Room custody before native RX/dedup reads and proven classified handoff.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.*
import org.junit.Test

class IncomingReactionReceiveCustodyTest : RoomProcessTest() {
    private val stamp = 1_704_067_200u
    private val receivedAt = Instant.ofEpochSecond(stamp.toLong() + 1)
    private val emoji = "\uD83D\uDC4D"
    private val raw = "@[Alice]$emoji\nb45pc4ek"
    private enum class Lookup { RX, DUPLICATE }
    private enum class Admission { BEFORE_COMMIT, AFTER_COMMIT }

    @Test fun retirementDuringRxReadRetainsTheOriginalNativeReceive() = runTest { lookupFailure(Lookup.RX, true) }
    @Test fun retirementDuringDuplicateReadRetainsTheOriginalNativeReceive() = runTest { lookupFailure(Lookup.DUPLICATE, true) }
    @Test fun failedRxReadRemainsOwnedUntilAnActiveRetrySucceeds() = runTest { lookupFailure(Lookup.RX, false) }
    @Test fun failedDuplicateReadRemainsOwnedUntilAnActiveRetrySucceeds() = runTest { lookupFailure(Lookup.DUPLICATE, false) }

    @Test fun liveReactionBehindBlockedConsumerSurvivesMonitorRetirement() = runTest {
        val faults = Faults()
        var now = receivedAt
        val h = harness(faults) { now }
        try {
            val target = ready(h)
            val old = assertNotNull(h.sessions.current)
            faults.lookup = Lookup.RX
            faults.cancelRead = true
            fun packet(sender: String) =
                Bytes.of(0x11, 20, 0, 0, 3, 0, 0) + le32(stamp.toLong()) + Bytes.utf8("$sender: $raw")

            h.links.radios.last().push(packet("Bob"))
            h.eventually("first reaction entered RX lookup") { faults.entered.isCompleted }
            h.links.radios.last().push(packet("Carol"))
            h.eventually("both live reactions reached poller intake") {
                old.messagePollingService.undeliveredCount == 2
            }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertTrue(old.isTornDown)
            now = receivedAt.plusSeconds(3_600)
            faults.lookup = null
            h.manager.connect(h.target())
            h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
            val recovered = backgroundScope.async {
                while (store.fetchReactions(EntityKey(target.radioId, target.id)).size != 2) delay(1)
            }
            h.eventually("both retained reactions recovered", virtualBudgetMillis = 10_000) { recovered.isCompleted }
            recovered.await()
            assertEquals(setOf("Bob", "Carol"),
                store.fetchReactions(EntityKey(target.radioId, target.id)).map { it.senderName }.toSet())
            assertEquals(2, faults.admissions.size)
            faults.observed.forEach {
                assertEquals(receivedAt, it.receivedAt)
                assertEquals(target.radioId, it.radioId)
                assertIs<DeliveryContext.Live>(it.deliveryContext)
            }
        } finally { h.container.close() }
    }

    @Test fun acceptedLiveReactionResumesAfterMonitorRestartWithoutAnotherEvent() = runTest {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            val polling = assertNotNull(h.sessions.current).messagePollingService
            faults.lookup = Lookup.RX
            faults.cancelRead = true
            h.links.radios.last().push(
                Bytes.of(0x11, 20, 0, 0, 3, 0, 0) + le32(stamp.toLong()) + Bytes.utf8("Bob: $raw"),
            )
            h.eventually("reaction entered RX lookup") { faults.entered.isCompleted }
            polling.stopMessageEventMonitoring()
            assertEquals(1, polling.undeliveredCount)
            faults.lookup = null
            polling.startMessageEventMonitoring()
            recovered(h, target)
            assertEquals(1, faults.admissions.size)
            h.eventually("resumed poller completed its accepted delivery") { polling.undeliveredCount == 0 }
        } finally { h.container.close() }
    }

    private suspend fun TestScope.lookupFailure(boundary: Lookup, cancel: Boolean) {
        val faults = Faults()
        var now = receivedAt
        val h = harness(faults) { now }
        try {
            val target = ready(h)
            val old = assertNotNull(h.sessions.current)
            faults.lookup = boundary
            faults.cancelRead = cancel
            val polling = backgroundScope.async { poll(h, "Bob: $raw") }
            h.eventually("actual pre-classification Room read") { faults.entered.isCompleted }
            if (!cancel) {
                h.eventually("failed read returned to its process owner") { polling.isCompleted }
                polling.await()
            }
            assertTrue(store.fetchReactions(EntityKey(target.radioId, target.id)).isEmpty(),
                "an unproved prerequisite read must not release custody into mutation")
            assertTrue(store.reactionQueue.fetchReactionQueue().entries.isEmpty())
            if (cancel) {
                h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                assertTrue(old.isTornDown)
                polling.cancelAndJoin()
                now = receivedAt.plusSeconds(3_600)
                faults.lookup = null
                h.manager.connect(h.target())
                h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
                assertNotSame(old, h.sessions.current)
            } else {
                faults.lookup = null
                advanceTimeBy(30_000)
                runCurrent()
                assertSame(old, h.sessions.current)
            }
            recovered(h, target)
            assertEquals(1, faults.saves)
            assertEquals(1, faults.admissions.size)
            assertTrue(faults.observed.isNotEmpty())
            faults.observed.forEach {
                assertEquals(receivedAt, it.receivedAt)
                assertEquals("TestNode", it.localNodeName)
                assertEquals(target.radioId, it.radioId)
                assertEquals(raw, it.rawText)
                assertEquals("Bob", it.senderName)
            }
        } finally { h.container.close() }
    }

    @Test fun aProvenUncommittedAdmissionReturnsTheSameReceiveForRetry() = runTest {
        admissionFailure(Admission.BEFORE_COMMIT)
    }

    @Test fun anUnknownAdmissionWaitsForExactJournalProofWithoutReplayingTheReceive() = runTest {
        admissionFailure(Admission.AFTER_COMMIT)
    }

    private suspend fun TestScope.admissionFailure(boundary: Admission) {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            faults.admission = boundary
            poll(h, "Bob: $raw")
            assertEquals(1, faults.admissionWrites)
            val lookups = faults.rxReads
            if (boundary == Admission.AFTER_COMMIT) {
                repeat(2) {
                    advanceTimeBy(30_000)
                    runCurrent()
                    h.eventually("blocked journal proof check") { !assertNotNull(h.sessions.current).isReactionRecoveryActive }
                }
                assertEquals(1, faults.admissionWrites)
                assertEquals(lookups, faults.rxReads, "an uncertain handoff cannot replay prerequisite work")
                assertEquals(0, faults.saves)
                faults.blockQueueProof = false
            }
            advanceTimeBy(30_000)
            runCurrent()
            recovered(h, target)
            assertEquals(1, faults.admissions.size, "an unchanged preimage must not manufacture a new occurrence")
            assertEquals(1, faults.saves)
            assertEquals(if (boundary == Admission.BEFORE_COMMIT) 2 else 1, faults.admissionWrites)
        } finally { h.container.close() }
    }

    @Test fun retirementAfterClassifiedHandoffUsesExactSaveProofWithoutResending() = runTest {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            faults.loseSaveResponse = true
            poll(h, "Bob: $raw")
            assertEquals(1, faults.saves)
            assertTrue(faults.events.isEmpty())
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            h.manager.connect(h.target())
            h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
            recovered(h, target)
            assertEquals(1, faults.saves)
            assertEquals(1, faults.admissions.size)
            assertEquals(1, faults.events.size)
        } finally { h.container.close() }
    }

    @Test fun aNonReactionSaveIsNeverAdoptedOrReplayedByReactionReceiveCustody() = runTest {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            faults.cancelPlainSave = true
            val polling = backgroundScope.async { poll(h, "Bob: ordinary body") }
            h.eventually("ordinary Room save committed before cancellation") { faults.entered.isCompleted }
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            polling.cancelAndJoin()
            h.manager.connect(h.target())
            h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, faults.plainSaves)
            assertEquals(listOf("Hello", "ordinary body"),
                store.fetchMessages(target.radioId, 3u).map { it.text }.sorted())
            assertEquals(0, faults.saves)
            assertTrue(faults.admissions.isEmpty())
        } finally { h.container.close() }
    }

    @Test fun separateEqualDeliveredReceivesKeepDistinctHandoffsWithoutDuplicateDelivery() = runTest {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            faults.lookup = Lookup.RX
            repeat(2) { poll(h, "Bob: $raw") }
            assertEquals(0, faults.saves)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            faults.lookup = null
            h.manager.connect(h.target())
            h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
            recovered(h, target)
            assertEquals(2, faults.admissions.size)
            assertEquals(1, faults.saves)
            assertEquals(1, faults.events.size)
        } finally { h.container.close() }
    }

    @Test fun trueBodyDuplicateIsConsumedWithoutAdmittingAReaction() = runTest {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            store.saveMessage(target.copy(id = UUID.randomUUID(), senderNodeName = "Bob", text = raw,
                deduplicationKey = syncMessageCodecs().deduplicationKey.contentBased(null, 3u, "Bob", stamp, raw)))
            poll(h, "Bob: $raw")
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, faults.saves)
            assertTrue(faults.admissions.isEmpty())
            assertTrue(store.fetchReactions(EntityKey(target.radioId, target.id)).isEmpty())
        } finally { h.container.close() }
    }

    @Test fun receiveCapacityFailureIsExplicitAndDoesNotEvictPreviouslyAcceptedWork() = runTest {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            faults.lookup = Lookup.RX
            repeat(100) { poll(h, "Bob: $raw") }
            val failure = assertFails { poll(h, "Bob: $raw") }
            assertTrue(generateSequence(failure) { it.cause }.any {
                it.message?.contains("receive capacity exhausted") == true
            }, "the actual consumer must report receive capacity exhaustion: $failure")
            assertEquals(0, faults.saves)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            faults.lookup = null
            h.manager.connect(h.target())
            h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
            recovered(h, target)
            assertEquals(100, faults.admissions.size)
            assertEquals(1, faults.saves)
        } finally { h.container.close() }
    }

    @Test fun nativeCustodyPrecedesUnusedChannelMetadataAndSurvivesRetirement() = runTest {
        val faults = Faults()
        var now = receivedAt
        val h = harness(faults) { now }
        try {
            val target = ready(h)
            faults.failChannelLookup = true
            faults.lookup = Lookup.RX
            faults.cancelRead = true
            val polling = backgroundScope.async { poll(h, "Bob: $raw") }
            h.eventually("native receive or failed pre-handler lookup") {
                faults.entered.isCompleted || polling.isCompleted
            }
            assertTrue(faults.entered.isCompleted, "unused channel metadata must not precede native custody")
            assertEquals(0, faults.failedChannelReads)
            assertEquals(0, faults.saves)
            h.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            polling.cancelAndJoin()
            now = receivedAt.plusSeconds(3_600)
            faults.failChannelLookup = false
            faults.lookup = null
            h.manager.connect(h.target())
            h.eventually("replacement ready") { h.manager.connectionState == DeviceConnectionState.READY }
            recovered(h, target)
            assertEquals(1, faults.admissions.size)
            assertEquals(1, faults.saves)
            faults.observed.forEach {
                assertEquals(receivedAt, it.receivedAt)
                assertEquals(target.radioId, it.radioId)
                assertEquals("TestNode", it.localNodeName)
                assertEquals(raw, it.rawText)
                assertIs<DeliveryContext.InitialSync>(it.deliveryContext)
            }
        } finally { h.container.close() }
    }

    @Test fun aCapacityRejectedReceiveCanBeExplicitlyRetriedAfterTheAcceptedWorkDrains() = runTest {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            val graph = assertNotNull(h.sessions.current)
            faults.lookup = Lookup.RX
            repeat(100) { poll(h, "Bob: $raw") }
            assertFails { poll(h, "Bob: $raw") }
            assertEquals(1, graph.messagePollingService.undeliveredCount)
            faults.lookup = null
            advanceTimeBy(30_000)
            runCurrent()
            recovered(h, target)
            assertEquals(100, faults.admissions.size)
            assertEquals(1, graph.messagePollingService.undeliveredCount)
            poll(h, null)
            recovered(h, target)
            assertSame(graph, h.sessions.current)
            assertEquals(101, faults.admissions.size)
            assertEquals(0, graph.messagePollingService.undeliveredCount)
            assertEquals(1, faults.saves)
            assertEquals(1, faults.events.size)
        } finally { h.container.close() }
    }

    @Test fun aNonReactionStillRequiresMetadataAndRetriesOnlyThroughTheOriginalPoller() = runTest {
        val faults = Faults()
        val h = harness(faults)
        try {
            val target = ready(h)
            val graph = assertNotNull(h.sessions.current)
            faults.failChannelLookup = true
            assertFails { poll(h, "Bob: ordinary body") }
            assertTrue(faults.failedChannelReads > 0)
            assertEquals(0, faults.plainSaves)
            assertEquals(1, graph.messagePollingService.undeliveredCount)
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, faults.plainSaves, "native recovery must not adopt a plain delivery")
            faults.failChannelLookup = false
            poll(h, null)
            assertEquals(1, faults.plainSaves)
            assertEquals(0, graph.messagePollingService.undeliveredCount)
            assertEquals(listOf("Hello", "ordinary body"),
                store.fetchMessages(target.radioId, 3u).map { it.text }.sorted())
            assertTrue(faults.admissions.isEmpty())
        } finally { h.container.close() }
    }

    private inner class Faults : PersistenceStoreProtocol by store, ReactionSummaryReceipting by store {
        var lookup: Lookup? = null
        var failChannelLookup = false
        var failedChannelReads = 0
        var cancelRead = false
        val entered = CompletableDeferred<Unit>()
        var rxReads = 0
        var saves = 0
        var loseSaveResponse = false
        var plainSaves = 0
        var cancelPlainSave = false
        var admission: Admission? = null
        var admissionWrites = 0
        var blockQueueProof = false
        val admissions = linkedSetOf<UUID>()
        val observed = mutableListOf<ClassifiedReactionWork>()
        val events = mutableListOf<SyncDataEvent.ReactionReceived>()

        override suspend fun fetchChannel(radioId: RadioId, index: UByte): ChannelDTO? {
            if (!failChannelLookup) return store.fetchChannel(radioId, index)
            failedChannelReads++
            val sql = db.openHelper.writableDatabase
            sql.execSQL("ALTER TABLE channels RENAME TO channels_receive_fault")
            try { return store.fetchChannel(radioId, index) } finally {
                sql.execSQL("ALTER TABLE channels_receive_fault RENAME TO channels")
            }
        }

        private suspend fun <T> read(boundary: Lookup, table: String, action: suspend () -> T): T {
            if (lookup != boundary) return action()
            if (cancelRead) {
                action()
                entered.complete(Unit)
                awaitCancellation()
            }
            val sql = db.openHelper.writableDatabase
            sql.execSQL("ALTER TABLE $table RENAME TO ${table}_receive_fault")
            try { return action() } finally {
                sql.execSQL("ALTER TABLE ${table}_receive_fault RENAME TO $table")
                entered.complete(Unit)
            }
        }

        override suspend fun fetchRxLogEntries(radioId: RadioId, channelIndex: UByte, senderTimestamp: UInt): SnapshotList<RxLogEntryDTO> {
            rxReads++
            return read(Lookup.RX, "rx_log_entries") { store.fetchRxLogEntries(radioId, channelIndex, senderTimestamp) }
        }

        override suspend fun fetchMessage(deduplicationKey: String, radioId: RadioId): MessageDTO? =
            read(Lookup.DUPLICATE, "messages") { store.fetchMessage(deduplicationKey, radioId) }

        override suspend fun saveReaction(dto: ReactionDTO) {
            saves++
            store.saveReaction(dto)
            if (loseSaveResponse) {
                loseSaveResponse = false
                throw IllegalStateException("committed reaction save response was lost")
            }
        }

        override suspend fun saveReceivedChannelMessage(dto: MessageDTO): MessageDTO {
            plainSaves++
            val saved = store.saveReceivedChannelMessage(dto)
            if (cancelPlainSave) { entered.complete(Unit); awaitCancellation() }
            return saved
        }

        override val reactionQueue = object : ReactionQueuePersisting by store.reactionQueue {
            override suspend fun fetchReactionQueue(): ReactionQueueSnapshot {
                check(!blockQueueProof) { "controlled journal proof read failure" }
                return store.reactionQueue.fetchReactionQueue()
            }

            override suspend fun replaceReactionQueue(expectedVersion: UUID?, next: ReactionQueueSnapshot) {
                val received = next.entries.filter { it.rawText == raw }
                admissions += received.map { it.occurrenceID }
                observed += received
                if (received.any { it.queued && it.targetID == null && it.mutation == null }) {
                    admissionWrites++
                    val boundary = admission
                    if (boundary != null) {
                        admission = null
                        if (boundary == Admission.AFTER_COMMIT) {
                            store.reactionQueue.replaceReactionQueue(expectedVersion, next)
                            blockQueueProof = true
                        }
                        throw IllegalStateException("controlled native admission response failure")
                    }
                }
                store.reactionQueue.replaceReactionQueue(expectedVersion, next)
            }
        }
    }

    private fun TestScope.harness(faults: Faults, now: () -> Instant = { receivedAt }) = ContainerHarness(
        this, store, persistenceOverride = faults, syncClock = object : SyncClock {
            override fun now() = now()
            override fun elapsed() = testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        },
        onSessionCreated = { graph ->
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                graph.syncCoordinator.dataEvents().collect {
                    if (it is SyncDataEvent.ReactionReceived) faults.events += it
                }
            }
        },
    )

    private suspend fun ready(h: ContainerHarness): MessageDTO {
        h.manager.connect(h.target())
        h.eventually("session ready") { h.manager.connectionState == DeviceConnectionState.READY }
        val radio = assertNotNull(h.sessions.current).token.radioId
        store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
        return MessageDTO(radioId = radio, channelIndex = 3u, text = "Hello", timestamp = stamp,
            direction = MessageDirection.INCOMING, senderNodeName = "Alice").also { store.saveMessage(it) }
    }

    private suspend fun TestScope.recovered(h: ContainerHarness, target: MessageDTO) {
        val complete = backgroundScope.async {
            while (store.fetchMessage(EntityKey(target.radioId, target.id))?.reactionSummary != "$emoji:1" ||
                store.reactionQueue.fetchReactionQueue().entries.isNotEmpty()
            ) delay(1)
        }
        h.eventually("retained receive consumed through Room", virtualBudgetMillis = 10_000) { complete.isCompleted }
        complete.await()
        h.eventually("receiving pass finished") { !assertNotNull(h.sessions.current).isReactionRecoveryActive }
        assertEquals(listOf("Bob"), store.fetchReactions(EntityKey(target.radioId, target.id)).map { it.senderName })
    }

    private suspend fun poll(h: ContainerHarness, text: String?) {
        val radio = h.links.radios.last()
        val previous = radio.respond
        var pending = true
        radio.respond = { command ->
            if (command[0] == 0x0a.toUByte()) {
                if (pending && text != null) {
                    pending = false
                    listOf(Bytes.of(0x11, 20, 0, 0, 3, 0, 0) + le32(stamp.toLong()) + Bytes.utf8(text))
                } else listOf(Bytes.of(0x0a))
            } else previous(command)
        }
        try { assertNotNull(h.sessions.current).messagePollingService.pollAllMessages() }
        finally { radio.respond = previous }
    }
}
