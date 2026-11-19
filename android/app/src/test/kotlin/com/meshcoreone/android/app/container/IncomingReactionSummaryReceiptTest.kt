// AndroidOnly: WP-214 Recover uncertain summaries only from atomic Room operation receipts.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.CommittedReactionSummary
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryMutation
import com.meshcoreone.android.core.contracts.domain.ReactionSummaryReceipting
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.reactions.ReactionReplayResult
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import java.time.Instant
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IncomingReactionSummaryReceiptTest : RoomProcessTest() {
    private val stamp = 1_704_067_200u
    private val receivedAt = Instant.ofEpochSecond(stamp.toLong() + 1)
    private val emoji = "\uD83D\uDC4D"
    private val raw = "@[Alice]$emoji\nb45pc4ek"
    private enum class Boundary { AFTER_COMMIT, CANCEL_AFTER_COMMIT, BEFORE_COMMIT, DUPLICATE, REPLACED }

    @Test fun failedSummaryResponseAndFailedProofReadRecoverInOrderWithoutAnotherUpdate() = runTest {
        recover(this, Boundary.AFTER_COMMIT)
    }

    @Test fun cancellationAfterCommitRecoversWithoutAnotherUpdate() = runTest {
        recover(this, Boundary.CANCEL_AFTER_COMMIT)
    }

    @Test fun rolledBackSummaryAndEqualLegacyUpdateDoNotProveTheUnknownOperation() = runTest {
        recover(this, Boundary.BEFORE_COMMIT)
    }

    @Test fun duplicateSummaryReceiptDoesNotCreateANotificationObligation() = runTest {
        recover(this, Boundary.DUPLICATE)
    }

    @Test fun summaryReceiptDoesNotAcknowledgeAReplacementTarget() = runTest {
        recover(this, Boundary.REPLACED)
    }

    private suspend fun recover(test: TestScope, boundary: Boundary) {
        val attempted = mutableListOf<ReactionSummaryMutation>()
        val entered = CompletableDeferred<Unit>()
        var denyProof = false
        val persistence = object : PersistenceStoreProtocol by store, ReactionSummaryReceipting by store {
            override suspend fun updateReactionSummary(mutation: ReactionSummaryMutation) {
                attempted += mutation
                if (attempted.size != 1) {
                    store.updateReactionSummary(mutation)
                    return
                }
                if (boundary == Boundary.BEFORE_COMMIT) {
                    val sql = db.openHelper.writableDatabase
                    sql.execSQL(
                        "CREATE TRIGGER reject_native_summary_receipt BEFORE INSERT ON reaction_summary_receipts " +
                            "BEGIN SELECT RAISE(ABORT, 'native receipt storage failed'); END",
                    )
                    try { store.updateReactionSummary(mutation) } finally {
                        sql.execSQL("DROP TRIGGER reject_native_summary_receipt")
                    }
                } else {
                    store.updateReactionSummary(mutation)
                    if (boundary == Boundary.CANCEL_AFTER_COMMIT) {
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                    throw IllegalStateException("summary response lost after commit")
                }
            }

            override suspend fun fetchCommittedReactionSummary(operation: EntityKey): CommittedReactionSummary? {
                if (denyProof) throw PersistenceStoreException(PersistenceStoreError.FetchFailed("receipt read unavailable"))
                return store.fetchCommittedReactionSummary(operation)
            }
        }
        val events = mutableListOf<SyncDataEvent.ReactionReceived>()
        val harness = ContainerHarness(
            test, store, persistenceOverride = persistence, syncClock = object : SyncClock {
                override fun now() = receivedAt
                override fun elapsed() = test.testScheduler.currentTime.milliseconds
                override suspend fun sleep(duration: Duration) = delay(duration)
            },
            onSessionCreated = { graph ->
                test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    graph.syncCoordinator.dataEvents().collect {
                        if (it is SyncDataEvent.ReactionReceived) events += it
                    }
                }
            },
        )
        try {
            val connection = harness.target()
            harness.manager.connect(connection)
            harness.eventually("session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val first = assertNotNull(harness.sessions.current)
            val radio = first.token.radioId
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val target = MessageDTO(
                radioId = radio, channelIndex = 3u, text = "Hello", timestamp = stamp,
                direction = MessageDirection.INCOMING, senderNodeName = "Alice",
            )
            store.saveMessage(target)
            val key = EntityKey(radio, target.id)
            val incarnation = assertNotNull(store.fetchChannelReactionMatch(key)?.channelReactionIncarnation)
            val existing = if (boundary == Boundary.DUPLICATE) ReactionDTO(
                radioId = radio, messageID = target.id, emoji = emoji, senderName = "Bob", rawText = raw,
                messageHash = "b45pc4ek", channelIndex = 3u, expectedTargetIncarnation = incarnation,
            ).also { store.saveReaction(it) } else null
            if (boundary == Boundary.CANCEL_AFTER_COMMIT) {
                val request = test.backgroundScope.async { poll(harness, "Bob: $raw") }
                harness.eventually("summary committed before cancellation") { entered.isCompleted }
                request.cancelAndJoin()
            } else poll(harness, "Bob: $raw")
            test.runCurrent()
            assertEquals(1, attempted.size)
            assertTrue(events.isEmpty(), "an unknown response is not a completed publication")
            val operation = attempted.single()
            assertEquals(incarnation, operation.targetIncarnation)
            if (boundary == Boundary.BEFORE_COMMIT) {
                assertNull(store.fetchCommittedReactionSummary(operation.operation))
                assertNull(store.fetchMessage(key)?.reactionSummary)
                store.updateMessageReactionSummary(key, "$emoji:1", incarnation)
            } else assertNotNull(store.fetchCommittedReactionSummary(operation.operation))
            if (boundary == Boundary.AFTER_COMMIT) {
                denyProof = true
                poll(harness, "Carol: $raw")
                assertEquals(1, attempted.size, "proof read failure must not repeat a summary update")
                assertEquals(listOf("Bob"), store.fetchReactions(key).map { it.senderName })
                assertTrue(events.isEmpty())
                denyProof = false
            }
            if (boundary == Boundary.REPLACED) store.saveMessage(target)
            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            harness.manager.connect(connection)
            harness.eventually("replacement ready") {
                harness.manager.connectionState == DeviceConnectionState.READY && harness.sessions.current !== first
            }
            val replacement = assertNotNull(harness.sessions.current)
            harness.eventually("receipt recovery finished") { !replacement.isReactionRecoveryActive }
            test.runCurrent()
            val unresolved = boundary == Boundary.BEFORE_COMMIT || boundary == Boundary.REPLACED
            assertEquals(if (boundary == Boundary.AFTER_COMMIT) 2 else 1, attempted.size)
            assertEquals(
                if (boundary == Boundary.AFTER_COMMIT) setOf("Bob", "Carol") else setOf("Bob"),
                store.fetchReactions(key).map { it.senderName }.toSet(),
            )
            val current = assertNotNull(store.fetchChannelReactionMatch(key))
            val replay = replacement.reactionService.replayIndexedChannelMessage(
                target.id, radio, 3u, "Alice", target.text, stamp, persistence,
                assertNotNull(current.channelReactionIncarnation),
            )
            if (unresolved) {
                assertTrue(events.isEmpty())
                if (boundary == Boundary.BEFORE_COMMIT) assertIs<ReactionReplayResult.Retained>(replay)
                else {
                    assertIs<ReactionReplayResult.Completed>(replay)
                    val retained = replacement.reactionService.indexMessage(
                        target.id, 3u, "Alice", target.text, stamp, radio, includeCustody = true,
                        targetIncarnation = current.channelReactionIncarnation,
                    )
                    assertEquals(listOf(incarnation), retained.map { it.expectedTargetIncarnation })
                    retained.forEach(replacement.reactionService::requeuePendingReaction)
                }
            } else {
                assertIs<ReactionReplayResult.Completed>(replay)
                val expectedEvents = when (boundary) {
                    Boundary.AFTER_COMMIT -> listOf(operation.operation.id, attempted.last().operation.id)
                    Boundary.DUPLICATE -> emptyList()
                    else -> listOf(operation.operation.id)
                }
                assertEquals(expectedEvents, events.map { it.occurrenceId })
                assertEquals(if (boundary == Boundary.AFTER_COMMIT) "$emoji:2" else "$emoji:1", current.reactionSummary)
            }
            existing?.let { assertEquals(it.id, store.fetchReactions(key).single().id) }
            val saved = store.fetchReactions(key).first { it.senderName == "Bob" }
            assertEquals(raw, saved.rawText)
            if (existing == null) assertEquals(receivedAt, saved.receivedAt)
        } finally { harness.container.close() }
    }

    private suspend fun poll(harness: ContainerHarness, text: String) {
        val graph = assertNotNull(harness.sessions.current)
        val firmware = harness.links.radios.last()
        val previous = firmware.respond
        var pending = true
        firmware.respond = { command ->
            if (command[0] == 0x0a.toUByte()) {
                if (pending) {
                    pending = false
                    listOf(Bytes.of(0x11, 20, 0, 0, 3, 0, 0) + le32(stamp.toLong()) + Bytes.utf8(text))
                } else listOf(Bytes.of(0x0a))
            } else previous(command)
        }
        try { graph.messagePollingService.pollAllMessages() } finally { firmware.respond = previous }
    }
}
