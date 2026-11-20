// AndroidOnly: WP-215 File-backed restart custody through the real reaction notification consumer.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.sync.SyncClock
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], manifest = Config.NONE)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ReactionNotificationRestartTest {
    @get:Rule val folder = TemporaryFolder()
    private val stamp = 1_700_000_000u
    private val receivedAt = Instant.ofEpochSecond(stamp.toLong())
    private val target = UUID.fromString("12345678-1234-1234-1234-123456789abc")
    private val channelSecret = Bytes(ByteArray(16) { 0x33 })

    @Test fun committedOccurrencesSurviveConsumerCancellationAndRealDatabaseReopen() = runTest {
        val file = File(folder.root, "notifications.db")
        val first = DiskStore(file)
        val entered = CompletableDeferred<Unit>()
        val blocked = object : RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED) {
            override suspend fun post(request: NotificationRequest): NotificationPostResult {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val original = harness(this, first, blocked)
        lateinit var message: MessageDTO
        lateinit var reactions: List<ReactionDTO>
        try {
            connect(original)
            val graph = assertNotNull(original.sessions.current)
            first.store.saveChannel(graph.token.radioId, ChannelInfo(3u, "General", channelSecret))
            message = MessageDTO(
                radioId = graph.token.radioId, channelIndex = 3u, text = "Hello", timestamp = stamp,
                direction = MessageDirection.OUTGOING, status = MessageStatus.SENT, createdAt = receivedAt,
            )
            first.store.saveMessage(message)
            poll(original, "Bob: @[TestNode]\uD83D\uDC4D\n${ReactionParser.generateMessageHash("Hello", stamp)}")
            original.eventually("actual notification consumer entered platform request") { entered.isCompleted }
            poll(original, "Carol: @[TestNode]\u2764\uFE0F\n${ReactionParser.generateMessageHash("Hello", stamp)}")
            reactions = first.store.fetchReactions(EntityKey(message.radioId, message.id)).toList()
            assertEquals(setOf("Bob", "Carol"), reactions.map { it.senderName }.toSet())
        } finally {
            try { original.container.close() } finally { first.close() }
        }

        val second = DiskStore(file)
        val fresh = harness(this, second, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED))
        try {
            connect(fresh)
            try {
                fresh.eventually("both exact committed notifications recovered after process restart") {
                    fresh.delivery.posted.count { it.id.value.startsWith("reaction-") } == 2
                }
            } finally {
                reactions.forEach {
                    assertNotNull(second.store.fetchCommittedReaction(EntityKey(it.radioId, it.id)))
                }
            }
            val notifications = fresh.delivery.posted.filter { it.id.value.startsWith("reaction-") }
            assertEquals(listOf("Bob", "Carol"), notifications.map { it.title })
            assertEquals(
                reactions.map { "reaction-${it.id.toString().uppercase()}" }.toSet(),
                notifications.map { it.id.value }.toSet(),
            )
            assertEquals(reactions.toSet(), second.store.fetchReactions(EntityKey(message.radioId, message.id)).toSet())
            assertTrue(notifications.all { it.body.contains("Hello") })
        } finally {
            try { fresh.container.close() } finally { second.close() }
        }
    }

    private fun harness(test: TestScope, disk: DiskStore, delivery: RecordingDelivery) =
        ContainerHarness(test, disk.store, deliveryOverride = delivery, syncClock = object : SyncClock {
            override fun now() = receivedAt
            override fun elapsed() = test.testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        }).also { harness ->
            harness.links.configureRadio = { radio ->
                radio.respond = { command ->
                    if (command[0] == 0x1f.toUByte() && command[1] == 3.toUByte()) {
                        listOf(Bytes.of(0x12, 3) + Bytes.utf8("General") + Bytes(ByteArray(25)) + channelSecret)
                    } else null
                }
            }
        }

    private suspend fun connect(h: ContainerHarness) {
        h.appState
        h.manager.connect(h.target(target))
        h.eventually("configured reaction notification consumer") {
            h.manager.connectionState == com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY &&
                h.sessions.current?.notificationService?.isAuthorized == true &&
                h.sessions.current?.notificationService?.isSuppressingNotifications == false
        }
    }

    private suspend fun poll(h: ContainerHarness, text: String) {
        val graph = assertNotNull(h.sessions.current)
        val radio = h.links.radios.last()
        val previous = radio.respond
        var pending = true
        radio.respond = { command ->
            if (command[0] == 0x0a.toUByte()) {
                if (pending) {
                    pending = false
                    listOf(Bytes.of(0x11, 20, 0, 0, 3, 0, 0) + le32(stamp.toLong()) + Bytes.utf8(text))
                } else listOf(Bytes.of(0x0a))
            } else previous(command)
        }
        try { graph.messagePollingService.pollAllMessages() } finally { radio.respond = previous }
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
