// AndroidOnly: WP-216 File-backed recovery of accepted outgoing reaction bookkeeping.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.MessagingDiagnostic
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.timeline.TimelineConversation
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
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
class OutgoingReactionRecoveryTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun acceptedDirectReactionSurvivesColdStoreReopenWithoutResending() = runTest {
        val file = File(folder.root, "outgoing.db")
        val firstStore = DiskStore(file)
        val first = harness(this, firstStore)
        var target: MessageDTO? = null
        var pendingId: UUID? = null
        try {
            first.manager.connect(first.target())
            first.eventually("first radio ready") { first.manager.connectionState == DeviceConnectionState.READY }
            val radio = assertNotNull(first.sessions.current).token.radioId
            val contact = ContactDTO(radioId = radio, publicKey = key(3), name = "Peer", lastHeardTimestamp = null)
            firstStore.store.saveContact(contact)
            val message = MessageDTO(radioId = radio, contactID = contact.id, text = "Hello", timestamp = 42u,
                direction = MessageDirection.INCOMING)
            firstStore.store.saveMessage(message)
            val conversation = TimelineConversation.Direct(contact)
            val feature = first.container.chatsFeature
            val displayed = assertNotNull(feature.timeline(conversation).data.fetchMessage(message.id))
            feature.react(displayed, "\uD83D\uDC4D", feature.captureMessageActionConversation(conversation))
            first.eventually("direct acceptance and local summary are durable") {
                runBlocking {
                    first.links.radios.last().sent.count { it[0].toInt() == 0x02 } == 1 &&
                        firstStore.store.fetchMessage(EntityKey(radio, message.id))?.reactionSummary == "\uD83D\uDC4D:1"
                }
            }
            assertEquals(1, firstStore.store.fetchPendingSends(radio).size)
            pendingId = firstStore.store.fetchPendingSends(radio).single().id
            target = message
        } finally {
            try { first.container.close() } finally { firstStore.close() }
        }

        val saved = assertNotNull(target)
        val secondStore = DiskStore(file)
        val second = harness(this, secondStore)
        try {
            second.manager.connect(second.target())
            second.eventually("replacement radio ready") { second.manager.connectionState == DeviceConnectionState.READY }
            second.eventually("accepted direct attempt is retained for ACK") {
                synchronized(second.messagingDiagnostics) {
                    second.messagingDiagnostics.filterIsInstance<MessagingDiagnostic.Failure>().any {
                        it.operation == "directUncertain.$pendingId"
                    }
                }
            }
            assertEquals(0, second.links.radios.last().sent.count { it[0].toInt() == 0x02 })
            assertEquals("\uD83D\uDC4D:1", assertNotNull(
                secondStore.store.fetchMessage(EntityKey(saved.radioId, saved.id)),
            ).reactionSummary)
            assertEquals(1, secondStore.store.fetchReactions(EntityKey(saved.radioId, saved.id)).size)
            assertTrue(secondStore.store.fetchPendingSends(saved.radioId).isNotEmpty())
        } finally {
            try { second.container.close() } finally { secondStore.close() }
        }
    }

    @Test fun acceptedChannelReactionRecoversFailedBookkeepingAfterColdStoreReopen() = runTest {
        val file = File(folder.root, "channel-outgoing.db")
        val firstStore = DiskStore(file)
        val first = harness(this, firstStore)
        var target: MessageDTO? = null
        try {
            first.manager.connect(first.target())
            first.eventually("first channel radio ready") { first.manager.connectionState == DeviceConnectionState.READY }
            val live = assertNotNull(first.sessions.current)
            live.channelService.syncChannels(live.token.radioId, 1u, usePipelinedRead = false)
            val channel = firstStore.store.fetchChannels(live.token.radioId).single()
            val message = MessageDTO(
                radioId = live.token.radioId, channelIndex = channel.index, channelOccupantID = channel.occupantID,
                text = "Hello", timestamp = 42u, direction = MessageDirection.INCOMING, senderNodeName = "Alice",
            )
            firstStore.store.saveMessage(message)
            val conversation = TimelineConversation.Channel(channel)
            val feature = first.container.chatsFeature
            val displayed = assertNotNull(feature.timeline(conversation).data.fetchMessage(message.id))
            firstStore.database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER outgoing_cold_summary_fault BEFORE UPDATE OF reactionSummary ON messages " +
                    "BEGIN SELECT RAISE(ABORT, 'outgoing cold summary fault'); END",
            )
            feature.react(displayed, "\uD83D\uDC4D", feature.captureMessageActionConversation(conversation))
            first.eventually("channel acceptance survives failed bookkeeping") {
                runBlocking {
                    first.links.radios.last().sent.count { it[0].toInt() == 0x03 } == 1 &&
                        firstStore.store.fetchReactions(EntityKey(message.radioId, message.id)).size == 1 &&
                        first.messagingDiagnostics.isNotEmpty()
                }
            }
            assertEquals(null, firstStore.store.fetchMessage(EntityKey(message.radioId, message.id))?.reactionSummary)
            assertEquals(1, firstStore.store.fetchPendingSends(message.radioId).size)
            target = message
        } finally {
            try { first.container.close() } finally {
                try {
                    firstStore.database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS outgoing_cold_summary_fault")
                } finally { firstStore.close() }
            }
        }

        val saved = assertNotNull(target)
        val secondStore = DiskStore(file)
        val second = harness(this, secondStore)
        try {
            second.manager.connect(second.target())
            second.eventually("replacement channel radio ready") { second.manager.connectionState == DeviceConnectionState.READY }
            second.eventually("accepted channel bookkeeping recovers from disk") {
                runBlocking {
                    secondStore.store.fetchMessage(EntityKey(saved.radioId, saved.id))?.reactionSummary == "\uD83D\uDC4D:1" &&
                        secondStore.store.fetchPendingSends(saved.radioId).isEmpty()
                }
            }
            assertEquals(0, second.links.radios.last().sent.count { it[0].toInt() == 0x03 })
            assertEquals(1, secondStore.store.fetchReactions(EntityKey(saved.radioId, saved.id)).size)
            val channel = secondStore.store.fetchChannels(saved.radioId).single()
            val conversation = TimelineConversation.Channel(channel)
            val feature = second.container.chatsFeature
            val displayed = assertNotNull(feature.timeline(conversation).data.fetchMessage(saved.id))
            feature.react(displayed, "\uD83D\uDC4D", feature.captureMessageActionConversation(conversation))
            second.settle()
            assertEquals(0, second.links.radios.last().sent.count { it[0].toInt() == 0x03 })
        } finally {
            try { second.container.close() } finally { secondStore.close() }
        }
    }

    private fun harness(test: TestScope, disk: DiskStore): ContainerHarness = ContainerHarness(test, disk.store).also { h ->
        h.links.configureRadio = { radio ->
            radio.knownContacts += contactFrame(key(3), "Peer")
            radio.respond = { command ->
                when (command[0].toInt()) {
                    0x1f -> if (command[1] == 0.toUByte()) {
                        listOf(Bytes.of(0x12, 0) + Bytes.utf8("Test").paddedOrTruncated(32) +
                            Bytes(ByteArray(16) { (it + 1).toByte() }))
                    } else null
                    0x02 -> listOf(sentReply(command, key(7)))
                    0x03 -> listOf(Bytes.of(0))
                    else -> null
                }
            }
        }
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
