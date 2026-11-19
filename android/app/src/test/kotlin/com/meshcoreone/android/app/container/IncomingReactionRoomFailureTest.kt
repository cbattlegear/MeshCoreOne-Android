// AndroidOnly: WP-214 Real Room read failures and uncertain incoming reaction saves.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.ReactionIndexScope
import com.meshcoreone.android.app.state.ReactionIndexing
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.runtime.RuntimeDisconnectReason
import com.meshcoreone.android.core.services.sync.SyncClock
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IncomingReactionRoomFailureTest : RoomProcessTest() {
    private val stamp = 1_704_067_200u
    private val receivedAt = Instant.ofEpochSecond(stamp.toLong() + 1)
    private val emoji = "\uD83D\uDC4D"
    private val raw = "@[Alice]$emoji\nb45pc4ek"

    @Test fun roomTargetReadFailureRetainsTheReaction() = runTest { recoverRoomRead(this, true) }
    @Test fun roomExistenceReadFailureRetainsTheReaction() = runTest { recoverRoomRead(this, false) }
    @Test fun roomTargetReadFailureRecoversWithoutHistoryReplay() = runTest { recoverRoomRead(this, true, true) }
    @Test fun roomExistenceReadFailureRecoversWithoutHistoryReplay() = runTest { recoverRoomRead(this, false, true) }
    @Test fun cancelledReconnectLookupRemainsRecoverable() = runTest { recoverRoomRead(this, true, true, true) }

    private suspend fun recoverRoomRead(
        test: TestScope, targetRead: Boolean, automatic: Boolean = false, cancelReconnect: Boolean = false,
    ) {
        var armed = false
        var failure: PersistenceStoreException? = null
        var now = receivedAt
        val recoveryEntered = CompletableDeferred<Unit>()
        suspend fun <T> failRealRead(table: String, read: suspend () -> T): T {
            val sql = db.openHelper.writableDatabase
            sql.execSQL("ALTER TABLE $table RENAME TO ${table}_held")
            return try {
                read()
            } catch (error: PersistenceStoreException) {
                failure = error
                throw error
            } finally {
                sql.execSQL("ALTER TABLE ${table}_held RENAME TO $table")
            }
        }
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun findChannelMessageForReaction(
                radioId: RadioId, channelIndex: UByte, parsedReaction: ParsedReaction, localNodeName: String?,
                timestampWindow: ClosedRange<UInt>, limit: Long,
            ): MessageDTO? {
                if (armed && targetRead) {
                    armed = false
                    return failRealRead("direct_ack_incarnations") {
                        store.findChannelMessageForReaction(
                            radioId, channelIndex, parsedReaction, localNodeName, timestampWindow, limit,
                        )
                    }
                }
                if (cancelReconnect && failure != null && recoveryEntered.complete(Unit)) awaitCancellation()
                if (failure != null) {
                    assertEquals("TestNode", localNodeName)
                    assertTrue(stamp in timestampWindow, "recovery must use the original receive window")
                }
                return store.findChannelMessageForReaction(
                    radioId, channelIndex, parsedReaction, localNodeName, timestampWindow, limit,
                )
            }

            override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean {
                if (armed && !targetRead) {
                    armed = false
                    return failRealRead("reactions") { store.reactionExists(message, senderName, emoji) }
                }
                return store.reactionExists(message, senderName, emoji)
            }
        }
        val logs = mutableListOf<String>()
        val harness = ContainerHarness(
            test, store, persistenceOverride = persistence, syncClock = clock(test) { now },
            syncLogSink = com.meshcoreone.android.core.services.sync.SyncLogSink { _, _, message -> logs += message },
        )
        try {
            val connection = harness.target()
            harness.manager.connect(connection)
            harness.eventually("session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            val radio = live.token.radioId
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val target = target(radio)
            store.saveMessage(target)
            armed = true
            poll(harness, "Bob: $raw")
            assertNotNull(failure, "the fault must come from the production Room store")
            assertTrue(store.fetchReactions(EntityKey(radio, target.id)).isEmpty())
            assertTrue(logs.none { it.startsWith("Saved reaction ") })
            now = receivedAt.plusSeconds(30 * 86_400L)
            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            harness.manager.connect(connection)
            if (cancelReconnect) {
                harness.eventually("recovery lookup entered") { recoveryEntered.isCompleted }
                harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
                assertTrue(store.fetchReactions(EntityKey(radio, target.id)).isEmpty())
                harness.manager.connect(connection)
            }
            harness.eventually("replacement ready") {
                harness.manager.connectionState == DeviceConnectionState.READY && harness.sessions.current !== live
            }
            val replacement = assertNotNull(harness.sessions.current)
            if (automatic) {
                assertEquals(
                    listOf("Bob"), store.fetchReactions(EntityKey(radio, target.id)).map { it.senderName },
                    "a usable replacement graph must consume retained work without opening history",
                )
            }
            val stored = assertNotNull(store.fetchChannelReactionMatch(EntityKey(radio, target.id)))
            val results = assertNotNull(replacement.reactionService.persistIndexedChannelMessage(
                target.id, radio, 3u, "Alice", target.text, stamp, persistence,
                assertNotNull(stored.channelReactionIncarnation),
            ))
            assertTrue(results.isEmpty(), "reconnect must already have consumed the retained work")
            val saved = store.fetchReactions(EntityKey(radio, target.id)).single()
            assertEquals("Bob", saved.senderName)
            assertEquals(raw, saved.rawText)
            assertEquals(receivedAt, saved.receivedAt)
            assertEquals("$emoji:1", store.fetchMessage(EntityKey(radio, target.id))?.reactionSummary)
        } finally {
            harness.container.close()
        }
    }

    @Test fun uncertainSaveIsNotRetriedAndLaterReactionsRemainOrdered() = runTest {
        var bobAttempts = 0
        var unknown: ReactionDTO? = null
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveReaction(dto: ReactionDTO) {
                if (dto.senderName == "Bob") {
                    bobAttempts++
                    if (bobAttempts == 1) {
                        unknown = dto
                        throw IllegalStateException("save outcome is unknown")
                    }
                }
                store.saveReaction(dto)
            }
        }
        val harness = ContainerHarness(this, store, persistenceOverride = persistence, syncClock = clock(this))
        try {
            harness.manager.connect(harness.target())
            harness.eventually("session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            val radio = live.token.radioId
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val target = target(radio)
            store.saveMessage(target)
            poll(harness, "Bob: $raw")
            val savedAttempt = assertNotNull(unknown)
            poll(harness, "Carol: $raw")
            poll(harness, "Bob: $raw")
            val key = EntityKey(radio, target.id)
            assertEquals(1, bobAttempts, "an uncertain mutation must not be sent again")
            assertTrue(store.fetchReactions(key).isEmpty(), "later reactions must remain pending in arrival order")
            val channel = assertNotNull(store.fetchChannel(radio, 3u))
            val stored = assertNotNull(store.fetchChannelReactionMatch(key))
            val indexing = ReactionIndexing(live.reactionService, ReactionIndexScope.Channel(channel, "TestNode")) {
                error("unproven mutations must not be acknowledged")
            }
            repeat(2) {
                assertEquals(listOf(stored), harness.container.chatsFeature.prepareTimelineMessages(listOf(stored), indexing))
            }
            assertEquals(1, bobAttempts)
            assertTrue(store.fetchReactions(key).isEmpty())
            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            harness.manager.connect(harness.target())
            harness.eventually("uncertain mutation replacement ready") {
                harness.manager.connectionState == DeviceConnectionState.READY && harness.sessions.current !== live
            }
            val replacement = assertNotNull(harness.sessions.current)
            assertEquals(1, bobAttempts, "reconnect must not repeat an uncertain save")
            assertTrue(store.fetchReactions(key).isEmpty(), "later work remains ordered behind the uncertain save")
            store.saveReaction(savedAttempt)
            val completed = assertNotNull(replacement.reactionService.persistIndexedChannelMessage(
                target.id, radio, 3u, "Alice", target.text, stamp, persistence,
                assertNotNull(stored.channelReactionIncarnation),
            ))
            assertEquals(1, bobAttempts)
            assertEquals(setOf("Bob", "Carol"), store.fetchReactions(key).map { it.senderName }.toSet())
            assertTrue(completed.any { it.occurrenceId == savedAttempt.id && it.shouldNotify })
            assertEquals("$emoji:2", store.fetchMessage(key)?.reactionSummary)
        } finally {
            harness.container.close()
        }
    }

    @Test fun uncertainSummaryIsNotRetriedOrAcknowledgedFromAnEqualValue() = runTest {
        var attempts = 0
        var attempted: Triple<EntityKey, String?, String?>? = null
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun updateMessageReactionSummary(
                message: EntityKey, summary: String?, expectedIncarnation: String?,
            ) {
                attempts++
                if (attempts == 1) {
                    attempted = Triple(message, summary, expectedIncarnation)
                    throw IllegalStateException("summary outcome is unknown")
                }
                store.updateMessageReactionSummary(message, summary, expectedIncarnation)
            }
        }
        val harness = ContainerHarness(this, store, persistenceOverride = persistence, syncClock = clock(this))
        try {
            harness.manager.connect(harness.target())
            harness.eventually("session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            val radio = live.token.radioId
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val target = target(radio)
            store.saveMessage(target)
            poll(harness, "Bob: $raw")
            val key = EntityKey(radio, target.id)
            val saved = store.fetchReactions(key).single()
            val match = assertNotNull(store.fetchChannelReactionMatch(key))
            suspend fun replay() = live.reactionService.persistIndexedChannelMessage(
                target.id, radio, 3u, "Alice", target.text, stamp, persistence,
                assertNotNull(match.channelReactionIncarnation),
            )
            assertEquals(null, replay())
            assertEquals(1, attempts)
            assertEquals(null, store.fetchMessage(key)?.reactionSummary)
            poll(harness, "Carol: $raw")
            val channel = assertNotNull(store.fetchChannel(radio, 3u))
            val indexing = ReactionIndexing(live.reactionService, ReactionIndexScope.Channel(channel, "TestNode")) {
                error("an uncertain summary must not be acknowledged")
            }
            repeat(2) {
                assertEquals(listOf(match), harness.container.chatsFeature.prepareTimelineMessages(listOf(match), indexing))
            }
            val (message, summary, incarnation) = assertNotNull(attempted)
            store.updateMessageReactionSummary(message, summary, incarnation)
            assertEquals(null, replay(), "an equal summary does not identify the committing update")
            assertEquals(saved.id, store.fetchReactions(key).single().id)
            assertEquals(1, attempts)
            assertEquals("$emoji:1", store.fetchMessage(key)?.reactionSummary)
            val refreshed = assertNotNull(store.fetchChannelReactionMatch(key))
            repeat(2) {
                assertEquals(
                    listOf(refreshed),
                    harness.container.chatsFeature.prepareTimelineMessages(listOf(refreshed), indexing),
                )
            }
            assertEquals(1, attempts)
            assertEquals(listOf("Bob"), store.fetchReactions(key).map { it.senderName })
            assertEquals(null, replay(), "displaying stored messages must not acknowledge retained work")
        } finally {
            harness.container.close()
        }
    }

    @Test fun uncertainOldTargetDoesNotBlockAReplacementReaction() = runTest {
        var bobAttempts = 0
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveReaction(dto: ReactionDTO) {
                if (dto.senderName == "Bob") {
                    bobAttempts++
                    throw IllegalStateException("save outcome is unknown")
                }
                store.saveReaction(dto)
            }
        }
        val harness = ContainerHarness(this, store, persistenceOverride = persistence, syncClock = clock(this))
        try {
            harness.manager.connect(harness.target())
            harness.eventually("session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            val radio = live.token.radioId
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val target = target(radio)
            val key = EntityKey(radio, target.id)
            store.saveMessage(target)
            val original = assertNotNull(store.fetchChannelReactionMatch(key)?.channelReactionIncarnation)
            poll(harness, "Bob: $raw")
            store.saveMessage(target)
            val replacement = assertNotNull(store.fetchChannelReactionMatch(key)?.channelReactionIncarnation)
            assertTrue(original != replacement)
            poll(harness, "Carol: $raw")
            assertEquals(1, bobAttempts)
            assertEquals(listOf("Carol"), store.fetchReactions(key).map { it.senderName })
            assertEquals("$emoji:1", store.fetchMessage(key)?.reactionSummary)
            val channel = assertNotNull(store.fetchChannel(radio, 3u))
            val display = assertNotNull(store.fetchChannelReactionMatch(key))
            val prepared = harness.container.chatsFeature.prepareTimelineMessages(
                listOf(display), ReactionIndexing(live.reactionService, ReactionIndexScope.Channel(channel, "TestNode")) {},
            )
            assertEquals("$emoji:1", prepared.single().reactionSummary)
            val retained = live.reactionService.indexMessage(
                target.id, 3u, "Alice", target.text, stamp, radio, includeCustody = true,
                targetIncarnation = replacement,
            )
            assertEquals(listOf("Bob"), retained.map { it.senderNodeName })
            retained.forEach(live.reactionService::requeuePendingReaction)
        } finally {
            harness.container.close()
        }
    }

    @Test fun uncertainDuplicateSummaryDoesNotTreatAnEqualValueAsProof() = runTest {
        var attempts = 0
        var attempted: Triple<EntityKey, String?, String?>? = null
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun updateMessageReactionSummary(
                message: EntityKey, summary: String?, expectedIncarnation: String?,
            ) {
                attempts++
                attempted = Triple(message, summary, expectedIncarnation)
                throw IllegalStateException("summary outcome is unknown")
            }
        }
        val harness = ContainerHarness(this, store, persistenceOverride = persistence, syncClock = clock(this))
        try {
            harness.manager.connect(harness.target())
            harness.eventually("session ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(harness.sessions.current)
            val radio = live.token.radioId
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            val target = target(radio)
            val key = EntityKey(radio, target.id)
            store.saveMessage(target)
            val incarnation = assertNotNull(store.fetchChannelReactionMatch(key)?.channelReactionIncarnation)
            val existing = ReactionDTO(
                messageID = target.id, emoji = emoji, senderName = "Bob", messageHash = "b45pc4ek",
                rawText = raw, channelIndex = 3u, radioId = radio, expectedTargetIncarnation = incarnation,
            )
            store.saveReaction(existing)
            poll(harness, "Bob: $raw")
            val (message, summary, expected) = assertNotNull(attempted)
            store.updateMessageReactionSummary(message, summary, expected)
            val result = live.reactionService.persistIndexedChannelMessage(
                target.id, radio, 3u, "Alice", target.text, stamp, persistence, incarnation,
            )
            assertEquals(null, result)
            assertEquals(1, attempts)
            assertEquals(existing.id, store.fetchReactions(key).single().id)
        } finally {
            harness.container.close()
        }
    }

    private fun target(radio: RadioId) = MessageDTO(
        radioId = radio, channelIndex = 3u, text = "Hello", timestamp = stamp,
        direction = MessageDirection.INCOMING, senderNodeName = "Alice",
    )

    private fun clock(test: TestScope, current: () -> Instant = { receivedAt }) = object : SyncClock {
        override fun now() = current()
        override fun elapsed() = test.testScheduler.currentTime.milliseconds
        override suspend fun sleep(duration: Duration) = delay(duration)
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
