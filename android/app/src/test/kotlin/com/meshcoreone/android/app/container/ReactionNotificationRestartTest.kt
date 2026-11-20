// AndroidOnly: WP-215 File-backed restart custody through the real reaction notification consumer.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.CommittedReaction
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.NotificationPostResult
import com.meshcoreone.android.core.contracts.domain.NotificationPreferencesPort
import com.meshcoreone.android.core.contracts.domain.PendingReactionNotification
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.contracts.domain.ReactionNotificationPersisting
import com.meshcoreone.android.core.contracts.notifications.NotificationAuthorizationStatus
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.contracts.notifications.NotificationRequest
import com.meshcoreone.android.core.data.repository.RepositoryIssueReporter
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.ReactionDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.reactions.ReactionParser
import com.meshcoreone.android.core.services.reactions.ReactionPersistResult
import com.meshcoreone.android.core.services.sync.SyncClock
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
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
            fresh.eventually("both exact committed notifications recovered after process restart") {
                fresh.delivery.posted.count { it.id.value.startsWith("reaction-") } == 2
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

    @Test fun unpublishedCommitRecoversOnlyOnItsOriginalRadioWithoutRedrivingReactionWrites() = runTest {
        val file = File(folder.root, "unpublished.db")
        val seed = seed(this, file)
        val other = DiskStore(file)
        val wrongRadio = harness(this, other, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED), identity = key(8))
        try {
            connect(wrongRadio, id = UUID.fromString("12345678-1234-1234-1234-123456789def"))
            assertEquals(1, other.store.reactionNotifications.pendingReactionNotifications(seed.radioId).size)
            assertTrue(wrongRadio.delivery.posted.none { it.id.value.startsWith("reaction-") })
        } finally {
            try { wrongRadio.container.close() } finally { other.close() }
        }
        val disk = DiskStore(file)
        val writes = AtomicInteger()
        val observing = object : PersistenceStoreProtocol by disk.store {
            override suspend fun saveReaction(dto: ReactionDTO) {
                writes.incrementAndGet()
                disk.store.saveReaction(dto)
            }
        }
        val fresh = harness(this, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED), observing)
        try {
            connect(fresh)
            awaitDrained(this, fresh, disk, seed)
            assertEquals(listOf("Peer"), fresh.delivery.posted.filter { it.id.value.startsWith("reaction-") }.map { it.title })
            assertEquals(0, writes.get())
            assertEquals(seed, disk.store.fetchReactions(EntityKey(seed.radioId, seed.messageID)).single())
        } finally {
            try { fresh.container.close() } finally { disk.close() }
        }
        val third = DiskStore(file)
        val completed = harness(this, third, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED))
        try {
            assertTrue(third.store.reactionNotifications.pendingReactionNotifications(seed.radioId).isEmpty())
            connect(completed)
            // A late producer signal cannot re-admit the acknowledged storage generation.
            val graph = assertNotNull(completed.sessions.current)
            graph.syncCoordinator.publishReactionPersisted(
                ReactionPersistResult(seed.messageID, "", occurrenceId = seed.id),
            )
            completed.settle()
            assertTrue(third.store.reactionNotifications.pendingReactionNotifications(seed.radioId).isEmpty())
            assertTrue(completed.delivery.posted.none { it.id.value.startsWith("reaction-") })
            assertNotNull(third.store.fetchCommittedReaction(EntityKey(seed.radioId, seed.id)))
        } finally {
            try { completed.container.close() } finally { third.close() }
        }
    }

    @Test fun identicalTargetReinsertAfterRestartCannotInheritPendingNotification() = runTest {
        staleTarget(this, delete = true)
    }

    @Test fun sameIdTargetReplacementAfterRestartCannotInheritPendingNotification() = runTest {
        staleTarget(this, delete = false)
    }

    private suspend fun staleTarget(test: TestScope, delete: Boolean) {
        val file = File(folder.root, "replacement.db")
        val seed = seed(test, file)
        val disk = DiskStore(file)
        val fresh = harness(test, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED))
        try {
            val key = EntityKey(seed.radioId, seed.messageID)
            val original = assertNotNull(disk.store.fetchMessage(key))
            if (delete) disk.store.deleteMessage(key)
            disk.store.saveMessage(if (delete) original else original.copy(text = "Replacement"))
            assertNull(disk.store.fetchCommittedReaction(EntityKey(seed.radioId, seed.id)))
            connect(fresh)
            awaitDrained(test, fresh, disk, seed)
            assertTrue(fresh.delivery.posted.none { it.id.value.startsWith("reaction-") })
        } finally {
            try { fresh.container.close() } finally { disk.close() }
        }
    }

    @Test fun restartRetainsCurrentMuteAndSelfReactionSuppression() = runTest {
        for (self in listOf(false, true)) {
            val file = File(folder.root, "policy-$self.db")
            val seed = seed(this, file, if (self) "TestNode" else "Peer")
            val disk = DiskStore(file)
            val fresh = harness(this, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED))
            try {
                if (!self) {
                    val channel = assertNotNull(disk.store.fetchChannel(seed.radioId, 3u))
                    disk.store.setChannelMuted(EntityKey(seed.radioId, channel.id), true)
                }
                connect(fresh)
                awaitDrained(this, fresh, disk, seed)
                assertTrue(fresh.delivery.posted.none { it.id.value.startsWith("reaction-") })
            } finally {
                try { fresh.container.close() } finally { disk.close() }
            }
        }
    }

    @Test fun acknowledgementStorageFailureDoesNotRepeatAnAcknowledgedBackendRequest() = runTest {
        val file = File(folder.root, "ack.db")
        val seed = seed(this, file)
        val disk = DiskStore(file)
        disk.database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_notification_ack BEFORE UPDATE OF notificationToken ON reactions " +
                "BEGIN SELECT RAISE(ABORT, 'notification ack fixture'); END",
        )
        val fresh = harness(this, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED))
        try {
            connect(fresh)
            fresh.eventually("backend acknowledged the request") {
                fresh.delivery.posted.count { it.id.value.startsWith("reaction-") } == 1
            }
            fresh.eventually("actual Room acknowledgement failure surfaced") {
                disk.failures.any { it == "acknowledgeReactionNotification" }
            }
            assertEquals(1, disk.store.reactionNotifications.pendingReactionNotifications(seed.radioId).size)
            disk.database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_notification_ack")
            awaitDrained(this, fresh, disk, seed)
            assertEquals(1, fresh.delivery.posted.count { it.id.value.startsWith("reaction-") })
            assertEquals(seed, disk.store.fetchReactions(EntityKey(seed.radioId, seed.messageID)).single())
        } finally {
            try { fresh.container.close() } finally { disk.close() }
        }
    }

    @Test fun transientBackendFailureRetriesTheSameCommittedRequestWhileConnected() = runTest {
        val file = File(folder.root, "retry.db")
        val seed = seed(this, file)
        val disk = DiskStore(file)
        val ready = CompletableDeferred<Unit>()
        val underlying = disk.store.reactionNotifications
        val persistence = object : PersistenceStoreProtocol by disk.store {
            override val reactionNotifications = object : ReactionNotificationPersisting by underlying {
                override suspend fun pendingReactionNotifications(radioId: RadioId) =
                    ready.await().let { underlying.pendingReactionNotifications(radioId) }
            }
        }
        val attempts = CopyOnWriteArrayList<String>()
        val backend = object : RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED) {
            override suspend fun post(request: NotificationRequest): NotificationPostResult {
                if (request.id.value.startsWith("reaction-")) {
                    attempts += request.id.value
                    if (attempts.size == 1) error("transient backend fixture")
                }
                return super.post(request)
            }
        }
        val fresh = harness(this, disk, backend, persistence)
        try {
            connect(fresh)
            awaitSynced(fresh)
            ready.complete(Unit)
            awaitDrained(this, fresh, disk, seed)
            assertNotNull(disk.store.fetchCommittedReaction(EntityKey(seed.radioId, seed.id)))
            assertEquals(2, attempts.size)
            assertEquals(1, attempts.toSet().size)
            assertEquals(1, backend.posted.count { it.id.value.startsWith("reaction-") })
        } finally {
            ready.complete(Unit)
            try { fresh.container.close() } finally { disk.close() }
        }
    }

    @Test fun disabledReactionPreferenceSuppressesAndDurablyAcknowledgesRecovery() = runTest {
        val file = File(folder.root, "preferences.db")
        val seed = seed(this, file)
        val disk = DiskStore(file)
        val preferences = FixedNotificationPreferences()
        preferences.update(preferences.preferences.value.copy(reactionNotificationsEnabled = false))
        val fresh = harness(this, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED), preferences = preferences)
        try {
            connect(fresh)
            awaitDrained(this, fresh, disk, seed)
            assertTrue(fresh.delivery.posted.none { it.id.value.startsWith("reaction-") })
        } finally {
            try { fresh.container.close() } finally { disk.close() }
        }
    }

    @Test fun directMessageRecoveryAlsoUsesTheExactCommittedOccurrence() = runTest {
        val file = File(folder.root, "direct.db")
        val seed = seed(this, file, direct = true)
        val disk = DiskStore(file)
        val fresh = harness(this, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED))
        try {
            connect(fresh)
            awaitDrained(this, fresh, disk, seed)
            val notification = fresh.delivery.posted.single { it.id.value.startsWith("reaction-") }
            assertEquals("Peer", notification.title)
            val payload = notification.payload as NotificationPayload.Reaction
            assertEquals(EntityKey(seed.radioId, assertNotNull(seed.contactID)), payload.contact)
            assertNull(payload.channelIndex)
        } finally {
            try { fresh.container.close() } finally { disk.close() }
        }
    }

    @Test fun actualRoomReadFailureIsRetainedAndRetriedByTheActiveConsumer() = runTest {
        val file = File(folder.root, "read.db")
        val seed = seed(this, file)
        val disk = DiskStore(file)
        val once = AtomicBoolean(true)
        val ready = CompletableDeferred<Unit>()
        val underlying = disk.store.reactionNotifications
        val persistence = object : PersistenceStoreProtocol by disk.store {
            override val reactionNotifications = object : ReactionNotificationPersisting by underlying {
                override suspend fun pendingReactionNotifications(radioId: RadioId) =
                    if (once.compareAndSet(true, false)) {
                        ready.await()
                        val sql = disk.database.openHelper.writableDatabase
                        sql.execSQL("ALTER TABLE reactions RENAME TO unavailable_reactions")
                        try { underlying.pendingReactionNotifications(radioId) }
                        finally { sql.execSQL("ALTER TABLE unavailable_reactions RENAME TO reactions") }
                    } else underlying.pendingReactionNotifications(radioId)
            }
        }
        val fresh = harness(this, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED), persistence)
        try {
            connect(fresh)
            awaitSynced(fresh)
            ready.complete(Unit)
            fresh.eventually("actual Room pending-read failure surfaced") {
                disk.failures.any { it == "pendingReactionNotifications" }
            }
            awaitDrained(this, fresh, disk, seed)
            assertNotNull(disk.store.fetchCommittedReaction(EntityKey(seed.radioId, seed.id)))
            assertEquals(1, fresh.delivery.posted.count { it.id.value.startsWith("reaction-") })
        } finally {
            ready.complete(Unit)
            try { fresh.container.close() } finally { disk.close() }
        }
    }

    @Test fun finalProofCheckRejectsAnIdenticalReinsertedReactionGenerationBeforePosting() = runTest {
        val file = File(folder.root, "generation.db")
        val seed = seed(this, file)
        val disk = DiskStore(file)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val once = AtomicBoolean(true)
        val underlying = disk.store.reactionNotifications
        val persistence = object : PersistenceStoreProtocol by disk.store {
            override val reactionNotifications = object : ReactionNotificationPersisting by underlying {
                override suspend fun fetchPendingReactionNotification(
                    work: PendingReactionNotification,
                ): CommittedReaction? {
                    val committed = underlying.fetchPendingReactionNotification(work)
                    if (once.compareAndSet(true, false)) {
                        assertNotNull(committed)
                        entered.complete(Unit)
                        release.await()
                    }
                    return committed
                }
            }
        }
        val fresh = harness(this, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED), persistence)
        try {
            connect(fresh)
            fresh.eventually("consumer read original committed generation") { entered.isCompleted }
            val old = underlying.pendingReactionNotifications(seed.radioId).single()
            disk.store.deleteReactionsForMessage(EntityKey(seed.radioId, seed.messageID))
            disk.store.saveReaction(seed.copy(retainNotification = true))
            val replacement = underlying.pendingReactionNotifications(seed.radioId).single()
            assertNotEquals(old.token, replacement.token)
            release.complete(Unit)
            awaitDrained(this, fresh, disk, seed)
            assertEquals(1, fresh.delivery.posted.count { it.id.value.startsWith("reaction-") })
        } finally {
            release.complete(Unit)
            try { fresh.container.close() } finally { disk.close() }
        }
    }

    private suspend fun seed(
        test: TestScope, file: File, sender: String = "Peer", direct: Boolean = false,
    ): ReactionDTO {
        val disk = DiskStore(file)
        val original = harness(test, disk, RecordingDelivery(NotificationAuthorizationStatus.AUTHORIZED))
        try {
            connect(original, configure = false)
            val graph = assertNotNull(original.sessions.current)
            disk.store.saveChannel(graph.token.radioId, ChannelInfo(3u, "General", channelSecret))
            val contact = if (direct) ContactDTO(
                radioId = graph.token.radioId, publicKey = key(6), name = "Remote", lastHeardTimestamp = null,
            ).also { disk.store.saveContact(it) } else null
            val message = MessageDTO(
                radioId = graph.token.radioId, contactID = contact?.id,
                channelIndex = if (direct) null else 3u, text = "Hello", timestamp = stamp,
                direction = MessageDirection.OUTGOING, status = MessageStatus.SENT, createdAt = receivedAt,
            )
            disk.store.saveMessage(message)
            val reaction = ReactionDTO(
                messageID = message.id, emoji = "\uD83D\uDC4D", senderName = sender, messageHash = "12345678",
                rawText = "original", receivedAt = receivedAt, contactID = contact?.id,
                channelIndex = message.channelIndex, radioId = message.radioId,
            )
            assertNotNull(graph.reactionService.persistReactionAndUpdateSummary(reaction, disk.store))
            assertEquals(1, disk.store.reactionNotifications.pendingReactionNotifications(reaction.radioId).size)
            return reaction
        } finally {
            try { original.container.close() } finally { disk.close() }
        }
    }

    private suspend fun awaitDrained(test: TestScope, h: ContainerHarness, disk: DiskStore, seed: ReactionDTO) {
        val drained = test.backgroundScope.async {
            while (disk.store.reactionNotifications.pendingReactionNotifications(seed.radioId).isNotEmpty()) delay(1)
        }
        h.eventually("durable terminal consumer acknowledgement", virtualBudgetMillis = 60_000, virtualStepMillis = 100) {
            drained.isCompleted
        }
        drained.await()
    }

    private fun awaitSynced(h: ContainerHarness) {
        h.eventually("completed initial synchronization", virtualBudgetMillis = 60_000, virtualStepMillis = 100) {
            h.sessions.current?.syncCoordinator?.state == com.meshcoreone.android.core.services.sync.SyncState.Synced &&
                h.sessions.current?.notificationService?.isSuppressingNotifications == false
        }
    }

    private fun harness(
        test: TestScope, disk: DiskStore, delivery: RecordingDelivery,
        persistence: PersistenceStoreProtocol = disk.store, identity: Bytes = key(7),
        preferences: NotificationPreferencesPort = FixedNotificationPreferences(),
    ) = ContainerHarness(
        test, disk.store, identity = { identity }, deliveryOverride = delivery, persistenceOverride = persistence,
        notificationPreferences = preferences,
        syncClock = object : SyncClock {
            override fun now() = receivedAt
            override fun elapsed() = test.testScheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) = delay(duration)
        },
    ).also { harness ->
        harness.links.configureRadio = { radio ->
            radio.respond = { command ->
                if (command[0] == 0x1f.toUByte()) {
                    if (command[1] == 3.toUByte()) {
                        listOf(Bytes.of(0x12, 3) + Bytes.utf8("General") + Bytes(ByteArray(25)) + channelSecret)
                    } else listOf(Bytes.of(0x12, command[1].toInt()) + Bytes(ByteArray(48)))
                } else null
            }
        }
    }

    private suspend fun connect(h: ContainerHarness, configure: Boolean = true, id: UUID = target) {
        if (configure) h.appState
        h.manager.connect(h.target(id))
        h.eventually("configured reaction notification consumer") {
            h.manager.connectionState == DeviceConnectionState.READY &&
                (!configure || h.sessions.current?.notificationService?.isAuthorized == true) &&
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
        val failures = CopyOnWriteArrayList<String>()
        val store = RoomPersistenceStore(
            database, CoroutineScope(owner + Dispatchers.Default),
            reporter = RepositoryIssueReporter { operation, _ -> failures += operation },
        )

        suspend fun close() = withContext(NonCancellable) {
            try { store.close() } finally {
                try { owner.cancelAndJoin() } finally { database.close() }
            }
        }
    }
}
