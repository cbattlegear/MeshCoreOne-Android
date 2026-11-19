// AndroidOnly: WP-214 File-backed restart reproduction for classified native reaction custody.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.ParsedReaction
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.reactions.ReactionReplayResult
import com.meshcoreone.android.core.services.sync.SyncClock
import java.io.File
import java.time.Instant
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class IncomingReactionRestartCustodyTest {
    @get:Rule val folder = TemporaryFolder()
    private val stamp = 1_704_067_200u
    private val receivedAt = Instant.ofEpochSecond(stamp.toLong() + 1)
    private val emoji = "\uD83D\uDC4D"
    private val raw = "@[Alice]$emoji\nb45pc4ek"
    private enum class Fault { TARGET, EXISTENCE, SUMMARY }

    @Test fun targetReadFailureRecoversAfterFileBackedRestartWithoutHistory() = runTest {
        restart(this, Fault.TARGET)
    }

    @Test fun existenceReadFailureRecoversAfterFileBackedRestartWithoutHistory() = runTest {
        restart(this, Fault.EXISTENCE)
    }

    @Test fun unknownSummaryRemainsRetainedAfterFileBackedRestart() = runTest {
        restart(this, Fault.SUMMARY)
    }

    private suspend fun restart(test: TestScope, fault: Fault) {
        val file = File(folder.root, "reactions.db")
        val first = DiskStore(file)
        var armed = false
        var failure: PersistenceStoreException? = null
        suspend fun <T> failRoom(table: String, operation: suspend () -> T): T {
            armed = false
            val sql = first.database.openHelper.writableDatabase
            sql.execSQL("ALTER TABLE $table RENAME TO ${table}_held")
            return try {
                operation()
            } catch (error: PersistenceStoreException) {
                failure = error
                throw error
            } finally {
                sql.execSQL("ALTER TABLE ${table}_held RENAME TO $table")
            }
        }
        val faulty = object : PersistenceStoreProtocol by first.store {
            override suspend fun findChannelMessageForReaction(
                radioId: RadioId, channelIndex: UByte, parsedReaction: ParsedReaction, localNodeName: String?,
                timestampWindow: ClosedRange<UInt>, limit: Long,
            ): MessageDTO? {
                suspend fun read() = first.store.findChannelMessageForReaction(
                    radioId, channelIndex, parsedReaction, localNodeName, timestampWindow, limit,
                )
                return if (armed && fault == Fault.TARGET) failRoom("direct_ack_incarnations") { read() } else read()
            }

            override suspend fun reactionExists(message: EntityKey, senderName: String, emoji: String): Boolean =
                if (armed && fault == Fault.EXISTENCE) {
                    failRoom("reactions") { first.store.reactionExists(message, senderName, emoji) }
                } else first.store.reactionExists(message, senderName, emoji)

            override suspend fun updateMessageReactionSummary(
                message: EntityKey, summary: String?, expectedIncarnation: String?,
            ) {
                if (armed && fault == Fault.SUMMARY) {
                    armed = false
                    val sql = first.database.openHelper.writableDatabase
                    sql.execSQL(
                        "CREATE TRIGGER reaction_restart_summary_fault BEFORE UPDATE OF reactionSummary ON messages " +
                            "BEGIN SELECT RAISE(ABORT, 'summary restart fixture'); END",
                    )
                    try {
                        first.store.updateMessageReactionSummary(message, summary, expectedIncarnation)
                    } catch (error: PersistenceStoreException) {
                        failure = error
                        throw error
                    } finally {
                        sql.execSQL("DROP TRIGGER reaction_restart_summary_fault")
                    }
                } else first.store.updateMessageReactionSummary(message, summary, expectedIncarnation)
            }
        }
        val harness = ContainerHarness(test, first.store, persistenceOverride = faulty, syncClock = clock(test))
        val target: MessageDTO
        val incarnation: String
        try {
            harness.manager.connect(harness.target())
            harness.eventually("first process ready") { harness.manager.connectionState == DeviceConnectionState.READY }
            val graph = assertNotNull(harness.sessions.current)
            val radio = graph.token.radioId
            first.store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            target = MessageDTO(
                radioId = radio, channelIndex = 3u, text = "Hello", timestamp = stamp,
                direction = MessageDirection.INCOMING, senderNodeName = "Alice",
            )
            first.store.saveMessage(target)
            val key = EntityKey(radio, target.id)
            incarnation = assertNotNull(first.store.fetchChannelReactionMatch(key)?.channelReactionIncarnation)
            armed = true
            poll(harness, "Bob: $raw")
            assertNotNull(failure, "the failure must come from the production Room store")
            if (fault == Fault.SUMMARY) {
                assertEquals(listOf("Bob"), first.store.fetchReactions(key).map { it.senderName })
                assertIs<ReactionReplayResult.Retained>(graph.reactionService.replayIndexedChannelMessage(
                    target.id, radio, 3u, "Alice", target.text, stamp, faulty, incarnation,
                ))
            } else {
                assertTrue(first.store.fetchReactions(key).isEmpty())
                val pending = graph.reactionService.indexMessage(
                    target.id, 3u, "Alice", target.text, stamp, radio, includeCustody = true,
                    targetIncarnation = incarnation,
                )
                assertEquals(listOf("Bob"), pending.map { it.senderNodeName })
                assertEquals(listOf(receivedAt), pending.map { it.receivedAt })
                pending.forEach(graph.reactionService::requeuePendingReaction)
            }
            assertEquals(null, first.store.fetchMessage(key)?.reactionSummary)
        } finally {
            try { harness.container.close() } finally { first.close() }
        }
        val second = DiskStore(file)
        val fresh = ContainerHarness(test, second.store, syncClock = clock(test))
        try {
            val key = EntityKey(target.radioId, target.id)
            assertEquals(target.text, second.store.fetchMessage(key)?.text)
            assertEquals(incarnation, second.store.fetchChannelReactionMatch(key)?.channelReactionIncarnation)
            fresh.manager.connect(fresh.target())
            fresh.eventually("fresh process ready") { fresh.manager.connectionState == DeviceConnectionState.READY }
            val graph = assertNotNull(fresh.sessions.current)
            assertEquals(target.radioId, graph.token.radioId)
            fresh.eventually("fresh process recovery finished") { !graph.isReactionRecoveryActive }
            if (fault == Fault.SUMMARY) {
                assertEquals(listOf("Bob"), second.store.fetchReactions(key).map { it.senderName })
                assertEquals(null, second.store.fetchMessage(key)?.reactionSummary)
                assertIs<ReactionReplayResult.Retained>(graph.reactionService.replayIndexedChannelMessage(
                    target.id, target.radioId, 3u, "Alice", target.text, stamp, second.store, incarnation,
                ), "restart must preserve the unknown operation, not report successful empty custody")
            } else {
                val saved = second.store.fetchReactions(key)
                assertEquals(listOf("Bob"), saved.map { it.senderName }, "fresh recovery must consume the original work")
                assertEquals(raw, saved.single().rawText)
                assertEquals(receivedAt, saved.single().receivedAt)
                assertEquals("$emoji:1", second.store.fetchMessage(key)?.reactionSummary)
            }
        } finally {
            try { fresh.container.close() } finally { second.close() }
        }
    }

    private fun clock(test: TestScope) = object : SyncClock {
        override fun now() = receivedAt
        override fun elapsed() = test.testScheduler.currentTime.milliseconds
        override suspend fun sleep(duration: Duration) = delay(duration)
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

    private class DiskStore(file: File) {
        private val owner = SupervisorJob()
        private val immediate = Executor { it.run() }
        val database = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), MeshCoreDatabase::class.java, file.absolutePath,
        ).addCallback(DirectAckStorage.callback).allowMainThreadQueries()
            .setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val store = RoomPersistenceStore(database, CoroutineScope(owner + Dispatchers.Default))

        suspend fun close() = withContext(NonCancellable) {
            try { store.close() } finally {
                try { owner.cancelAndJoin() } finally { database.close() }
            }
        }
    }
}
