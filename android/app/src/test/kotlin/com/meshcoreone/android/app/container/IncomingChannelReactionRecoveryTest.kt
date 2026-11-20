// AndroidOnly: WP-214 Classified native channel reaction failures through polling, Room and history replay.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.ReactionIndexScope
import com.meshcoreone.android.app.state.ReactionIndexing
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.reactions.ReactionPersistResult
import com.meshcoreone.android.core.services.reactions.ReactionService
import com.meshcoreone.android.core.services.sync.SyncDataEvent
import com.meshcoreone.android.core.services.sync.SyncClock
import com.meshcoreone.android.core.services.sync.SyncLogSink
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IncomingChannelReactionRecoveryTest : RoomProcessTest() {
    private val stamp = 1_704_067_200u
    private val receivedAt = Instant.ofEpochSecond(stamp.toLong() + 1)
    private val emoji = "\uD83D\uDC4D"
    private val raw = "@[Alice]$emoji\nb45pc4ek"
    private enum class Fault { MISS, LOOKUP, EXISTS, SAVE_BEFORE, SAVE_AFTER, SUMMARY_BEFORE, SUMMARY_AFTER }

    @Test fun genuineMissKeepsItsOriginalClassifiedContext() = runTest { recover(this, Fault.MISS) }
    @Test fun genuineMissSurvivesItsSessionInDurableCustody() = runTest { recover(this, Fault.MISS, retireMiss = true) }
    @Test fun failedLookupSurvivesReconnect() = runTest { recover(this, Fault.LOOKUP) }
    @Test fun cancelledLookupSurvivesReconnect() = runTest { recover(this, Fault.LOOKUP, cancel = true) }
    @Test fun failedExistenceReadSurvivesReconnect() = runTest { recover(this, Fault.EXISTS) }
    @Test fun uncertainSaveWithLateCommitSurvivesReconnect() = runTest { recover(this, Fault.SAVE_BEFORE) }
    @Test fun liveRetryConsumesOldPendingWorkWithoutDuplicatePublication() = runTest {
        recover(this, Fault.SAVE_BEFORE, liveRetry = true)
    }
    @Test fun consecutiveFailedSavesCreateOnlyOneNotificationObligation() = runTest {
        recover(this, Fault.SAVE_BEFORE, consecutiveFailures = true)
    }
    @Test fun resolvedOutgoingTargetStillReplaysAfterLocalRename() = runTest {
        recover(this, Fault.SAVE_BEFORE, renamed = true)
    }
    @Test fun failedSaveAfterCommitReconcilesWithoutAnotherSave() = runTest { recover(this, Fault.SAVE_AFTER) }
    @Test fun cachedTargetFailedSaveAlsoSurvivesReconnect() = runTest { recover(this, Fault.SAVE_AFTER, cached = true) }
    @Test fun cancelledSaveAfterCommitReconcilesWithoutAnotherSave() = runTest { recover(this, Fault.SAVE_AFTER, cancel = true) }
    @Test fun uncertainSummaryWithLateCommitRemainsPending() = runTest { recover(this, Fault.SUMMARY_BEFORE) }
    @Test fun cancelledSummaryWithLateCommitRemainsPending() = runTest { recover(this, Fault.SUMMARY_BEFORE, cancel = true) }
    @Test fun failedSummaryAfterCommitRemainsPendingWithoutAReceipt() = runTest { recover(this, Fault.SUMMARY_AFTER) }

    @Test fun queuedNotificationsUseTheirOwnCommittedReaction() = runTest {
        verifyQueuedNotifications(this, TargetChange.HEARD_REPEAT)
    }

    @Test fun queuedNotificationsDoNotRetargetAnOrdinaryMessageUpsert() = runTest {
        verifyQueuedNotifications(this, TargetChange.TEXT_UPSERT)
    }

    @Test fun queuedNotificationsDoNotRetargetAnEqualValueUpsert() = runTest {
        verifyQueuedNotifications(this, TargetChange.EQUAL_UPSERT)
    }

    @Test fun queuedNotificationsDoNotRetargetAnIdenticalReinsert() = runTest {
        verifyQueuedNotifications(this, TargetChange.REINSERT)
    }

    @Test fun queuedNotificationsDoNotFollowAReplacedChannelSlot() = runTest {
        verifyQueuedNotifications(this, TargetChange.SLOT_REPLACEMENT)
    }

    @Test fun databaseMatchedReactionCannotCommitAgainstAnIdenticalReplacement() = runTest {
        verifyMatchedReplacement(this, MatchBoundary.BEFORE_SAVE)
    }

    @Test fun retryCannotRebindPreviouslyMatchedReactionToIdenticalReplacement() = runTest {
        verifyMatchedReplacement(this, MatchBoundary.FAILED_SAVE)
    }

    @Test fun committedReactionCannotRebindAfterSummaryFailureAndIdenticalReinsert() = runTest {
        verifyMatchedReplacement(this, MatchBoundary.FAILED_SUMMARY)
    }

    private enum class MatchBoundary { BEFORE_SAVE, FAILED_SAVE, FAILED_SUMMARY }

    private suspend fun verifyMatchedReplacement(test: TestScope, boundary: MatchBoundary) {
        val scheduler = test.testScheduler
        val matched = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveReaction(dto: ReactionDTO) {
                assertNotNull(dto.expectedTargetIncarnation)
                if (boundary != MatchBoundary.FAILED_SUMMARY && matched.complete(Unit)) {
                    resume.await()
                    if (boundary == MatchBoundary.FAILED_SAVE) {
                        throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                    }
                }
                store.saveReaction(dto)
            }

            override suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?, expectedIncarnation: String?) {
                if (boundary == MatchBoundary.FAILED_SUMMARY && matched.complete(Unit)) {
                    resume.await()
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
                store.updateMessageReactionSummary(message, summary, expectedIncarnation)
            }
        }
        val delivery = RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED)
        val harness = ContainerHarness(
            test, store, persistenceOverride = persistence, deliveryOverride = delivery,
            syncClock = object : SyncClock {
                override fun now() = receivedAt
                override fun elapsed() = scheduler.currentTime.milliseconds
                override suspend fun sleep(duration: Duration) = delay(duration)
            },
        )
        try {
            harness.manager.connect(harness.target())
            harness.eventually("session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            live.notificationService.checkAuthorizationStatus()
            val radio = live.token.radioId
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val message = MessageDTO(
                radioId = radio, channelIndex = 3u, text = "Hello", timestamp = stamp,
                direction = MessageDirection.OUTGOING,
            )
            store.saveMessage(message)
            val ingest = test.launch { poll(harness, "Bob: @[TestNode]$emoji\nb45pc4ek") }
            harness.eventually("reaction reached the held persistence boundary") { matched.isCompleted }
            val key = EntityKey(radio, message.id)
            if (boundary == MatchBoundary.FAILED_SUMMARY) {
                val committed = store.fetchReactions(key).single()
                assertNotNull(store.fetchCommittedReaction(EntityKey(radio, committed.id)))
                store.deleteMessage(key)
                assertTrue(store.fetchReactions(key).isEmpty())
            }
            store.saveMessage(message)
            resume.complete(Unit)
            ingest.join()
            harness.settle()
            if (boundary != MatchBoundary.BEFORE_SAVE) {
                val replacement = assertNotNull(store.fetchChannelReactionMatch(key))
                live.reactionService.persistIndexedChannelMessage(
                    message.id, radio, 3u, "TestNode", message.text, stamp, persistence,
                    assertNotNull(replacement.channelReactionIncarnation),
                )
            }
            assertTrue(store.fetchReactions(key).isEmpty())
            assertEquals(null, store.fetchMessage(key)?.reactionSummary)
            assertTrue(delivery.posted.isEmpty())
        } finally {
            resume.complete(Unit)
            harness.container.close()
        }
    }

    @Test fun cancelledPostIsRetriedOnceThroughTheSameRadioReplacement() = runTest {
        val entered = CompletableDeferred<Unit>()
        var attempts = 0
        val delivery = object : RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED) {
            override suspend fun post(request: NotificationRequest): NotificationPostResult {
                if (++attempts == 1) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                return super.post(request)
            }
        }
        val scheduler = testScheduler
        val harness = ContainerHarness(
            this, store, deliveryOverride = delivery,
            syncClock = object : SyncClock {
                override fun now() = receivedAt
                override fun elapsed() = scheduler.currentTime.milliseconds
                override suspend fun sleep(duration: Duration) = delay(duration)
            },
        )
        try {
            val connectionTarget = harness.target()
            harness.manager.connect(connectionTarget)
            harness.eventually("original session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            live.notificationService.checkAuthorizationStatus()
            val radio = live.token.radioId
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val message = MessageDTO(
                radioId = radio, channelIndex = 3u, text = "Hello", timestamp = stamp,
                direction = MessageDirection.OUTGOING,
            )
            store.saveMessage(message)
            live.reactionService.indexMessage(message.id, 3u, "TestNode", message.text, stamp)
            poll(harness, "Bob: @[TestNode]$emoji\nb45pc4ek")
            harness.eventually("post suspended after reaction commit") { entered.isCompleted }
            val saved = store.fetchReactions(EntityKey(radio, message.id)).single()

            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            assertTrue(live.isTornDown)
            harness.manager.connect(connectionTarget)
            harness.eventually("replacement posts original occurrence") { delivery.posted.size == 1 }
            val replacement = assertNotNull(harness.sessions.current)
            assertEquals(radio, replacement.token.radioId)
            assertEquals("Bob", delivery.posted.single().title)
            replacement.syncCoordinator.publishReactionPersisted(
                ReactionPersistResult(message.id, "$emoji:1", occurrenceId = saved.id),
            )
            harness.settle()
            assertEquals(2, attempts)
            assertEquals(1, delivery.posted.size)
        } finally {
            harness.container.close()
        }
    }

    private enum class TargetChange { HEARD_REPEAT, TEXT_UPSERT, EQUAL_UPSERT, REINSERT, SLOT_REPLACEMENT }

    private suspend fun verifyQueuedNotifications(test: TestScope, change: TargetChange) {
        var now = receivedAt
        val firstPost = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val delivery = object : RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED) {
            override suspend fun post(request: NotificationRequest): NotificationPostResult {
                if (request.title == "Bob") {
                    firstPost.complete(Unit)
                    release.await()
                }
                return super.post(request)
            }
        }
        val harness = ContainerHarness(
            test, store, deliveryOverride = delivery,
            syncClock = object : SyncClock {
                override fun now() = now
                override fun elapsed() = test.testScheduler.currentTime.milliseconds
                override suspend fun sleep(duration: Duration) = delay(duration)
            },
        )
        try {
            harness.manager.connect(harness.target())
            harness.eventually("notification session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            live.notificationService.checkAuthorizationStatus()
            val radio = live.token.radioId
            val channel = seedReconciledChannel(ChannelDTO(
                radioId = radio, index = 3u, name = "General", secret = Bytes(ByteArray(16)),
            ))
            val message = MessageDTO(
                radioId = radio, channelIndex = 3u, text = "Hello", timestamp = stamp,
                direction = MessageDirection.OUTGOING, channelOccupantID = assertNotNull(channel.occupantID),
            )
            store.saveMessage(message)
            live.reactionService.indexMessage(message.id, 3u, "TestNode", message.text, stamp)
            suspend fun ingest(sender: String) {
                poll(harness, "$sender: @[TestNode]$emoji\nb45pc4ek")
                now = now.plusSeconds(1)
            }
            ingest("Bob")
            harness.eventually("first reaction holds the notification consumer") { firstPost.isCompleted }
            ingest("Carol")
            ingest("Dave")
            assertEquals(
                setOf("Bob", "Carol", "Dave"),
                store.fetchReactions(EntityKey(radio, message.id)).map { it.senderName }.toSet(),
            )
            val key = EntityKey(radio, message.id)
            when (change) {
                TargetChange.HEARD_REPEAT -> store.incrementMessageHeardRepeats(key)
                TargetChange.TEXT_UPSERT -> store.saveMessage(message.copy(text = "Replacement"))
                TargetChange.EQUAL_UPSERT -> store.saveMessage(message)
                TargetChange.REINSERT -> {
                    store.deleteMessage(key)
                    store.saveMessage(message)
                    assertEquals(message, store.fetchMessage(key))
                }
                TargetChange.SLOT_REPLACEMENT -> {
                    store.saveChannel(radio, ChannelInfo(3u, "Different", Bytes(ByteArray(16) { 1 })))
                    val successor = assertNotNull(store.fetchChannel(radio, 3u))
                    assertTrue(successor.occupantID != channel.occupantID)
                }
            }
            release.complete(Unit)
            harness.settle()
            assertEquals(
                if (change == TargetChange.HEARD_REPEAT) listOf("Bob", "Carol", "Dave") else listOf("Bob"),
                delivery.posted.map { it.title },
            )
            assertTrue(delivery.posted.all { "Hello" in it.body })
        } finally {
            release.complete(Unit)
            harness.container.close()
        }
    }

    private suspend fun recover(
        test: TestScope, fault: Fault, cancel: Boolean = false, cached: Boolean = false, retireMiss: Boolean = false,
        liveRetry: Boolean = false, consecutiveFailures: Boolean = false, renamed: Boolean = false,
    ) {
        val targetSender = if (renamed) "TestNode" else "Alice"
        val wire = if (renamed) "@[$targetSender]$emoji\nb45pc4ek" else raw
        var receiveTime = receivedAt
        var target: MessageDTO? = null
        var armed = false
        var saves = 0
        var lookups = 0
        var uncertainSave: ReactionDTO? = null
        var uncertainSummary: Triple<EntityKey, String?, String?>? = null
        val entered = CompletableDeferred<Unit>()
        val logs = mutableListOf<String>()
        val events = mutableListOf<SyncDataEvent.ReactionReceived>()
        suspend fun fail() {
            if (cancel) {
                println("CANCEL_DIAGNOSTIC fail entered fault=$fault")
                entered.complete(Unit)
                try { awaitCancellation() } finally { println("CANCEL_DIAGNOSTIC fail cancelled") }
            }
            throw IllegalStateException("injected $fault")
        }
        val persistence = object : PersistenceStoreProtocol by store {
            override val reactionQueue = store.reactionQueue.aroundSelection { work, _, resolve ->
                if (armed && !cached) {
                    lookups++
                    assertEquals(target?.radioId, work.radioId)
                    assertEquals(3u.toUByte(), work.channelIndex)
                    assertEquals("TestNode", work.localNodeName)
                    if (fault == Fault.LOOKUP) fail()
                }
                resolve()
            }
            override suspend fun findChannelMessageForReaction(
                radio: RadioId, channelIndex: UByte, parsed: ParsedReaction, localNodeName: String?,
                window: ClosedRange<UInt>, limit: Long,
            ): MessageDTO? {
                if (armed) {
                    lookups++
                    assertEquals(target?.radioId, radio)
                    assertEquals(3u.toUByte(), channelIndex)
                    assertEquals("TestNode", localNodeName)
                    if (fault == Fault.LOOKUP) fail()
                }
                return store.findChannelMessageForReaction(radio, channelIndex, parsed, localNodeName, window, limit)
            }

            override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean {
                if (armed && fault == Fault.EXISTS && message.id == target?.id) fail()
                return store.reactionExists(message, senderName, emoji)
            }

            override suspend fun saveReaction(dto: ReactionDTO) {
                if (dto.messageID == target?.id) {
                    saves++
                    if (armed && fault == Fault.SAVE_BEFORE) {
                        uncertainSave = dto
                        fail()
                    }
                    store.saveReaction(dto)
                    if (armed && fault == Fault.SAVE_AFTER) fail()
                } else store.saveReaction(dto)
            }

            override suspend fun updateMessageReactionSummary(message: EntityKey, summary: String?, expectedIncarnation: String?) {
                if (armed && message.id == target?.id && fault == Fault.SUMMARY_BEFORE) {
                    uncertainSummary = Triple(message, summary, expectedIncarnation)
                    fail()
                }
                store.updateMessageReactionSummary(message, summary, expectedIncarnation)
                if (armed && message.id == target?.id && fault == Fault.SUMMARY_AFTER) fail()
            }
        }
        val harness = ContainerHarness(
            test, store, persistenceOverride = persistence,
            syncClock = object : SyncClock {
                override fun now() = receiveTime
                override fun elapsed() = test.testScheduler.currentTime.milliseconds
                override suspend fun sleep(duration: Duration) = delay(duration)
            },
            syncLogSink = SyncLogSink { _, _, message -> logs += message },
            onSessionCreated = { graph ->
                test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    graph.syncCoordinator.dataEvents().collect {
                        if (it is SyncDataEvent.ReactionReceived) events += it
                    }
                }
            },
        )
        val connectionTarget = harness.target()
        try {
            harness.manager.connect(connectionTarget)
            harness.eventually("incoming recovery session ready") {
                harness.manager.connectionState == DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            store.saveChannel(live.token.radioId, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val channel = assertNotNull(store.fetchChannel(live.token.radioId, 3u))
            target = MessageDTO(
                radioId = live.token.radioId, channelIndex = 3u, text = "Hello",
                timestamp = stamp + 10u, senderTimestamp = stamp, timestampCorrected = true,
                direction = if (renamed) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                senderNodeName = if (renamed) null else targetSender,
            )
            val originalTarget = assertNotNull(target)
            if (fault != Fault.MISS) store.saveMessage(originalTarget)
            if (cached) {
                val match = assertNotNull(store.fetchChannelReactionMatch(EntityKey(originalTarget.radioId, originalTarget.id)))
                live.reactionService.indexMessage(
                    originalTarget.id, 3u, targetSender, "Hello", stamp, originalTarget.radioId,
                    targetIncarnation = assertNotNull(match.channelReactionIncarnation),
                )
            }
            armed = true
            val beforePoll = Instant.now()
            if (cancel) {
                val polling = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { poll(harness, "Bob: $wire") }
                println("CANCEL_DIAGNOSTIC waiting entry")
                entered.await()
                println("CANCEL_DIAGNOSTIC cancelling polling")
                polling.cancelAndJoin()
                println("CANCEL_DIAGNOSTIC polling joined")
                assertTrue(polling.isCancelled)
            } else poll(harness, "Bob: $wire")
            if (consecutiveFailures) {
                receiveTime = receivedAt.plusSeconds(1)
                poll(harness, "Bob: $wire")
            }
            val afterPoll = Instant.now()
            test.runCurrent()
            assertEquals(if (cached) 0 else if (consecutiveFailures) 2 else 1, lookups)
            assertTrue(events.isEmpty())
            assertFalse(logs.any { it.startsWith("Saved reaction ") }, "failure/miss must not log success")

            armed = false
            uncertainSave?.let { store.saveReaction(it) }
            uncertainSummary?.let { (message, summary, incarnation) ->
                store.updateMessageReactionSummary(message, summary, incarnation)
            }
            // Classified native misses now have durable custody, as do failed reads/mutations.
            if (fault == Fault.MISS) store.saveMessage(originalTarget)
            if (fault != Fault.MISS || retireMiss) {
                harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                assertTrue(live.isTornDown)
            }
            suspend fun takePending(service: ReactionService = live.reactionService) =
                service.indexMessage(
                    originalTarget.id, 3u, targetSender, "Hello", stamp, originalTarget.radioId, includeCustody = true,
                    targetIncarnation = store.fetchChannelReactionMatch(
                        EntityKey(originalTarget.radioId, originalTarget.id),
                    )?.channelReactionIncarnation,
                )
            if (!retireMiss && !liveRetry && fault != Fault.LOOKUP) {
                // Inspect after actual retirement for failures: requeueing before it would mask wrong ownership.
                val allRetained = takePending()
                assertEquals(if (consecutiveFailures) 2 else 1, allRetained.size)
                val retained = allRetained.first()
                assertEquals(wire, retained.rawText)
                assertEquals("Bob", retained.senderNodeName)
                assertEquals(receivedAt, retained.receivedAt)
                assertEquals("TestNode", retained.localNodeName)
                val context = assertIs<DeliveryContext.InitialSync>(retained.deliveryContext)
                assertTrue(context.anchor in beforePoll..afterPoll, "the original drain context must survive replacement")
                assertEquals(live.token.radioId, retained.radioId)
                assertEquals(3u.toUByte(), retained.channelIndex)
                allRetained.forEach {
                    if (fault == Fault.MISS) live.reactionService.queuePendingReaction(it)
                    else live.reactionService.requeuePendingReaction(it)
                }
            }
            if (fault != Fault.MISS || retireMiss) {
                harness.manager.connect(connectionTarget)
                harness.eventually("incoming recovery replacement ready") {
                    harness.manager.connectionState == DeviceConnectionState.READY && harness.sessions.current !== live
                }
            }
            val replacement = assertNotNull(harness.sessions.current)
            harness.eventually("incoming recovery pass finished") { !replacement.isReactionRecoveryActive }
            if (fault == Fault.SUMMARY_BEFORE || fault == Fault.SUMMARY_AFTER) {
                val match = assertNotNull(store.fetchChannelReactionMatch(EntityKey(originalTarget.radioId, originalTarget.id)))
                assertEquals(null, replacement.reactionService.persistIndexedChannelMessage(
                    originalTarget.id, originalTarget.radioId, 3u, targetSender, originalTarget.text, stamp,
                    persistence, assertNotNull(match.channelReactionIncarnation),
                ))
                assertEquals("$emoji:1", store.fetchMessage(EntityKey(originalTarget.radioId, originalTarget.id))?.reactionSummary)
                assertEquals(1, saves)
                assertTrue(events.isEmpty())
                val stillPending = takePending(replacement.reactionService)
                assertEquals(1, stillPending.size)
                stillPending.forEach { replacement.reactionService.requeuePendingReaction(it) }
                return
            }
            run {
                if (liveRetry) {
                    receiveTime = receivedAt.plusSeconds(60)
                    poll(harness, "Bob: $wire")
                }
                val indexing = ReactionIndexing(
                    replacement.reactionService, ReactionIndexScope.Channel(channel, if (renamed) "RenamedNode" else "TestNode"),
                    onPersisted = { harness.appState.publishReactionPersisted(replacement.reactionService, it) },
                )
                harness.container.chatsFeature.prepareTimelineMessages(listOf(originalTarget), indexing)
                harness.container.chatsFeature.prepareTimelineMessages(listOf(originalTarget), indexing)
                test.runCurrent()
                val saved = store.fetchReactions(EntityKey(originalTarget.radioId, originalTarget.id)).single()
                assertEquals(wire, saved.rawText)
                assertEquals(receivedAt, saved.receivedAt)
                assertEquals("Bob", saved.senderName)
                assertEquals(emoji, saved.emoji)
                assertEquals("$emoji:1", store.fetchMessage(EntityKey(originalTarget.radioId, originalTarget.id))?.reactionSummary)
                assertEquals(listOf(SyncDataEvent.ReactionReceived(originalTarget.id, "$emoji:1", saved.id)), events)
                assertEquals(1, saves)
                assertTrue(takePending(replacement.reactionService).isEmpty())
            }
        } finally { harness.container.close() }
    }

    private suspend fun poll(harness: ContainerHarness, text: String) {
        val live = assertNotNull(harness.sessions.current)
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
        try { live.messagePollingService.pollAllMessages() } finally { firmware.respond = previous }
    }
}
