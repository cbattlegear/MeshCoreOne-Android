// AndroidOnly: WP-214 Real Room read failures and uncertain incoming reaction saves.
package com.meshcoreone.android.app.container

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

    private suspend fun recoverRoomRead(test: TestScope, targetRead: Boolean) {
        var armed = false
        var failure: PersistenceStoreException? = null
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
            test, store, persistenceOverride = persistence, syncClock = clock(test),
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
            harness.manager.disconnect(RuntimeDisconnectReason.USER_INITIATED)
            harness.manager.connect(connection)
            harness.eventually("replacement ready") {
                harness.manager.connectionState == DeviceConnectionState.READY && harness.sessions.current !== live
            }
            val replacement = assertNotNull(harness.sessions.current)
            val stored = assertNotNull(store.fetchChannelReactionMatch(EntityKey(radio, target.id)))
            val results = assertNotNull(replacement.reactionService.persistIndexedChannelMessage(
                target.id, radio, 3u, "Alice", target.text, stamp, persistence,
                assertNotNull(stored.channelReactionIncarnation),
            ))
            assertEquals(1, results.size)
            val saved = store.fetchReactions(EntityKey(radio, target.id)).single()
            assertEquals("Bob", saved.senderName)
            assertEquals(raw, saved.rawText)
            assertEquals(receivedAt, saved.receivedAt)
            assertEquals("$emoji:1", store.fetchMessage(EntityKey(radio, target.id))?.reactionSummary)
        } finally {
            harness.container.close()
        }
    }

    @Test fun uncertainSaveIsNotRetriedAndIndependentReactionStillCompletes() = runTest {
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
            assertEquals(listOf("Carol"), store.fetchReactions(key).map { it.senderName })
            store.saveReaction(savedAttempt)
            val stored = assertNotNull(store.fetchChannelReactionMatch(key))
            val completed = assertNotNull(live.reactionService.persistIndexedChannelMessage(
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

    private fun target(radio: RadioId) = MessageDTO(
        radioId = radio, channelIndex = 3u, text = "Hello", timestamp = stamp,
        direction = MessageDirection.INCOMING, senderNodeName = "Alice",
    )

    private fun clock(test: TestScope) = object : SyncClock {
        override fun now() = receivedAt
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
