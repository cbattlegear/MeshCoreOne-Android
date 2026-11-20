// AndroidOnly: WP-214 Real Room crash-boundary, ordering, eviction and unknown-outcome custody controls.
package com.meshcoreone.android.app.container

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.DirectAckStorage
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.services.reactions.*
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
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
class DurableReactionQueueTest {
    @get:Rule val folder = TemporaryFolder()
    private val stamp = 1_704_067_200u
    private val receivedAt = Instant.ofEpochSecond(stamp.toLong() + 1, 123_456_789)
    private val emoji = "\uD83D\uDC4D"

    @Test fun restartRetainsOriginalContextGroupedOrderAndOtherRadioCustody() = runTest {
        val file = File(folder.root, "ordered.db")
        val radio = RadioId(UUID.randomUUID())
        val otherRadio = RadioId(UUID.randomUUID())
        val originals = disk(file) { fixture ->
            val first = fixture.target(radio)
            val second = fixture.target(radio, text = "Other")
            val service = fixture.service()
            val entries = listOf(
                pending(first, "Bob"), pending(second, "Carol"), pending(first, "Dave"),
                pending(first.copy(radioId = otherRadio), "Foreign"),
            )
            entries.forEach { service.queuePendingReaction(it) }
            val rows = fixture.store.reactionQueue.fetchReactionQueue().entries
            assertEquals(entries.map { it.occurrenceId }, rows.map { it.occurrenceID })
            assertTrue(rows.all { it.receivedAt == receivedAt && it.localNodeName == "Original Local" })
            assertTrue(rows.all { it.deliveryContext == DeliveryContext.InitialSync(Instant.EPOCH.plusNanos(7)) })
            first to entries
        }
        disk(file) { fixture ->
            val (first, entries) = originals
            val saves = mutableListOf<ReactionDTO>()
            val observed = object : PersistenceStoreProtocol by fixture.store, ReactionSummaryReceipting by fixture.store {
                override suspend fun saveReaction(dto: ReactionDTO) {
                    saves += dto
                    fixture.store.saveReaction(dto)
                }
            }
            val service = fixture.service()
            assertIs<ReactionReplayResult.Completed>(service.recoverRetainedChannelReactions(radio, observed) {})
            assertEquals(listOf("Bob", "Dave", "Carol"), saves.map { it.senderName })
            // Repeat through the public consumer: completed custody must remain absent.
            assertIs<ReactionReplayResult.Completed>(service.recoverRetainedChannelReactions(radio, fixture.store) {})
            assertEquals(listOf(entries.last().occurrenceId),
                fixture.store.reactionQueue.fetchReactionQueue().entries.map { it.occurrenceID })
            assertEquals("$emoji:2", fixture.store.fetchMessage(EntityKey(radio, first.id))?.reactionSummary)
            assertEquals(setOf("Bob", "Dave"), fixture.store.fetchReactions(EntityKey(radio, first.id))
                .map { it.senderName }.toSet())
            // Replay the foreign entry only after a target on its original radio actually exists.
            fixture.target(otherRadio, id = UUID.randomUUID())
            assertIs<ReactionReplayResult.Completed>(service.recoverRetainedChannelReactions(otherRadio, observed) {})
            assertEquals(listOf("Bob", "Dave", "Carol", "Foreign"), saves.map { it.senderName })
        }
    }

    @Test fun legacyAdmissionEvictsNativeWorkDurablyAndStaleRequeueCannotReviveIt() = runTest {
        val file = File(folder.root, "eviction.db")
        disk(file) { fixture ->
            val target = fixture.target()
            val native = pending(target, "Evicted")
            val service = fixture.service()
            service.queuePendingReaction(native)
            repeat(100) {
                service.queuePendingReaction(ParsedReaction(emoji, "Legacy $it", "hash$it"),
                    3u, "Legacy", "raw", target.radioId)
            }
            assertTrue(fixture.store.reactionQueue.fetchReactionQueue().entries.isEmpty())
            service.requeuePendingReaction(native)
            assertTrue(fixture.store.reactionQueue.fetchReactionQueue().entries.isEmpty())
            service.clearPendingReactions()
        }
        disk(file) { fixture ->
            val snapshot = fixture.store.reactionQueue.fetchReactionQueue()
            assertNotNull(snapshot.version, "An empty terminal transition must still fence stale writers")
            assertTrue(snapshot.entries.isEmpty())
        }
    }

    @Test fun takenWorkSurvivesLegacyCapacityAndRestartWithItsOriginalBinding() = runTest {
        heldWorkRestart(replace = false)
    }

    @Test fun boundWorkCannotAdoptAnIdenticalReinsertedTargetAfterRestart() = runTest {
        heldWorkRestart(replace = true)
    }

    private suspend fun heldWorkRestart(replace: Boolean) {
        val file = File(folder.root, "held.db")
        val original = disk(file) { fixture ->
            val target = fixture.target()
            val work = pending(target, "Bob")
            val service = fixture.service()
            service.queuePendingReaction(work)
            val incarnation = fixture.store.fetchChannelReactionMatch(EntityKey(target.radioId, target.id))!!.channelReactionIncarnation
            val taken = service.indexMessage(target.id, 3u, "Alice", target.text, stamp, target.radioId,
                includeCustody = true, targetIncarnation = incarnation).single()
            repeat(100) {
                service.queuePendingReaction(ParsedReaction(emoji, "Legacy $it", "hash$it"), 3u, "Legacy", "raw", target.radioId)
            }
            val row = fixture.store.reactionQueue.fetchReactionQueue().entries.single()
            assertFalse(row.queued)
            assertEquals(incarnation, row.targetIncarnation)
            assertEquals(taken.occurrenceId, row.occurrenceID)
            target to work
        }
        disk(file) { fixture ->
            val (target, work) = original
            val key = EntityKey(target.radioId, target.id)
            if (replace) {
                fixture.store.deleteMessage(key)
                fixture.store.saveMessage(target)
            }
            assertIs<ReactionReplayResult.Completed>(
                fixture.service().recoverRetainedChannelReactions(target.radioId, fixture.store) {},
            )
            val saved = fixture.store.fetchReactions(key)
            if (replace) assertTrue(saved.isEmpty()) else {
                assertEquals(work.occurrenceId, saved.single().id)
                assertEquals(receivedAt, saved.single().receivedAt)
            }
            assertTrue(fixture.store.reactionQueue.fetchReactionQueue().entries.isEmpty())
        }
    }

    @Test fun committedUnknownSaveIsProvedAfterRestartWithoutAnotherSave() = runTest { saveBoundary(commit = true) }
    @Test fun absentUnknownSaveIsNotTreatedAsPermissionToRetryAfterRestart() = runTest { saveBoundary(commit = false) }

    private suspend fun saveBoundary(commit: Boolean) {
        val file = File(folder.root, "save.db")
        val original = disk(file) { fixture ->
            val target = fixture.target()
            val service = fixture.service()
            val work = pending(target, "Bob")
            service.queuePendingReaction(work)
            val faulty = object : ReactionPersisting by fixture.store, ReactionSummaryReceipting by fixture.store {
                override suspend fun saveReaction(dto: ReactionDTO) {
                    if (commit) fixture.store.saveReaction(dto)
                    throw CancellationException("Controlled loss at save boundary")
                }
            }
            assertFailsWith<CancellationException> { fixture.replay(service, target, faulty) }
            val row = fixture.store.reactionQueue.fetchReactionQueue().entries.single()
            assertEquals(ReactionMutationPhase.SAVE, row.mutation)
            assertTrue(row.notificationPending)
            target to work
        }
        disk(file) { fixture ->
            val (target, work) = original
            var saves = 0
            val guarded = object : PersistenceStoreProtocol by fixture.store, ReactionSummaryReceipting by fixture.store {
                override suspend fun saveReaction(dto: ReactionDTO) {
                    saves++
                    fixture.store.saveReaction(dto)
                }
            }
            val result = fixture.service().recoverRetainedChannelReactions(target.radioId, guarded) {}
            assertEquals(0, saves)
            if (commit) {
                assertIs<ReactionReplayResult.Completed>(result)
                assertEquals(work.occurrenceId, fixture.store.fetchReactions(EntityKey(target.radioId, target.id)).single().id)
                assertTrue(fixture.store.reactionQueue.fetchReactionQueue().entries.isEmpty())
            } else {
                assertIs<ReactionReplayResult.Retained>(result)
                assertEquals(work.occurrenceId, fixture.store.reactionQueue.fetchReactionQueue().entries.single().occurrenceID)
            }
        }
    }

    @Test fun completionRollbackRecoversFromSummaryProofWithoutRepeatingTheMutation() = runTest {
        completionBoundary(commit = false)
    }
    @Test fun lostCompletionResponseDoesNotResurrectAlreadyRemovedWork() = runTest {
        completionBoundary(commit = true)
    }

    private suspend fun completionBoundary(commit: Boolean) {
        val file = File(folder.root, "completion.db")
        var updates = 0
        val original = disk(file) { fixture ->
            var armed = false
            val journal = object : ReactionQueuePersisting by fixture.store.reactionQueue {
                override suspend fun replaceReactionQueue(expectedVersion: UUID?, next: ReactionQueueSnapshot) {
                    if (!armed || next.entries.isNotEmpty()) {
                        fixture.store.reactionQueue.replaceReactionQueue(expectedVersion, next)
                        return
                    }
                    armed = false
                    if (commit) {
                        fixture.store.reactionQueue.replaceReactionQueue(expectedVersion, next)
                        throw CancellationException("Controlled completion response loss")
                    }
                    val sql = fixture.database.openHelper.writableDatabase
                    sql.execSQL("CREATE TRIGGER reject_reaction_completion BEFORE DELETE ON classified_channel_reactions " +
                        "BEGIN SELECT RAISE(ABORT, 'completion storage fixture'); END")
                    try { fixture.store.reactionQueue.replaceReactionQueue(expectedVersion, next) }
                    finally { sql.execSQL("DROP TRIGGER reject_reaction_completion") }
                }
            }
            val service = fixture.service(journal)
            val target = fixture.target()
            service.queuePendingReaction(pending(target, "Bob"))
            val observed = object : ReactionPersisting by fixture.store, ReactionSummaryReceipting by fixture.store {
                override suspend fun updateReactionSummary(mutation: ReactionSummaryMutation) {
                    updates++
                    fixture.store.updateReactionSummary(mutation)
                }
            }
            if (commit) assertFailsWith<CancellationException> {
                fixture.replay(service, target, observed) { armed = true }
            } else assertFailsWith<PersistenceStoreException> {
                fixture.replay(service, target, observed) { armed = true }
            }
            if (commit) assertTrue(fixture.store.reactionQueue.fetchReactionQueue().entries.isEmpty())
            else assertEquals(ReactionMutationPhase.SUMMARY, fixture.store.reactionQueue.fetchReactionQueue().entries.single().mutation)
            target
        }
        disk(file) { fixture ->
            val guarded = object : PersistenceStoreProtocol by fixture.store, ReactionSummaryReceipting by fixture.store {
                override suspend fun updateReactionSummary(mutation: ReactionSummaryMutation) {
                    updates++
                    fixture.store.updateReactionSummary(mutation)
                }
            }
            assertIs<ReactionReplayResult.Completed>(fixture.service().recoverRetainedChannelReactions(original.radioId, guarded) {})
            assertEquals(1, updates)
            assertTrue(fixture.store.reactionQueue.fetchReactionQueue().entries.isEmpty())
            assertEquals("$emoji:1", fixture.store.fetchMessage(EntityKey(original.radioId, original.id))?.reactionSummary)
        }
    }

    @Test fun lostAdmissionResponseIsReconciledBeforeLegacyEviction() = runTest {
        val file = File(folder.root, "admission.db")
        disk(file) { fixture ->
            var fail = true
            val journal = object : ReactionQueuePersisting by fixture.store.reactionQueue {
                override suspend fun replaceReactionQueue(expectedVersion: UUID?, next: ReactionQueueSnapshot) {
                    fixture.store.reactionQueue.replaceReactionQueue(expectedVersion, next)
                    if (fail) {
                        fail = false
                        throw CancellationException("Controlled admission response loss")
                    }
                }
            }
            val target = fixture.target()
            val service = fixture.service(journal)
            val work = pending(target, "Bob")
            assertFailsWith<CancellationException> { service.queuePendingReaction(work) }
            assertEquals(work.occurrenceId, fixture.store.reactionQueue.fetchReactionQueue().entries.single().occurrenceID)
            repeat(100) {
                service.queuePendingReaction(ParsedReaction(emoji, "Legacy $it", "hash$it"), 3u, "Legacy", "raw", target.radioId)
            }
            service.requeuePendingReaction(work)
            assertTrue(fixture.store.reactionQueue.fetchReactionQueue().entries.isEmpty())
        }
        disk(file) { assertTrue(it.store.reactionQueue.fetchReactionQueue().entries.isEmpty()) }
    }

    @Test fun failedAdmissionRollsBackItsEvictionAndPreservesEarlierValidWork() = runTest {
        val file = File(folder.root, "rollback.db")
        val target = disk(file) { fixture ->
            val value = fixture.target()
            val service = fixture.service()
            service.queuePendingReaction(pending(value, "Preserved"))
            repeat(99) {
                service.queuePendingReaction(ParsedReaction(emoji, "Legacy $it", "hash$it"), 3u, "Legacy", "raw", value.radioId)
            }
            val before = fixture.store.reactionQueue.fetchReactionQueue()
            val sql = fixture.database.openHelper.writableDatabase
            sql.execSQL("CREATE TRIGGER reject_reaction_admission BEFORE INSERT ON classified_channel_reactions " +
                "BEGIN SELECT RAISE(ABORT, 'admission storage fixture'); END")
            try {
                assertFailsWith<PersistenceStoreException> { service.queuePendingReaction(pending(value, "Not admitted")) }
            } finally { sql.execSQL("DROP TRIGGER reject_reaction_admission") }
            assertEquals(before, fixture.store.reactionQueue.fetchReactionQueue())
            service.clearPendingReactions()
            assertEquals(listOf("Preserved"), fixture.store.reactionQueue.fetchReactionQueue().entries.map { it.senderName })
            value
        }
        disk(file) { fixture ->
            assertIs<ReactionReplayResult.Completed>(fixture.service().recoverRetainedChannelReactions(target.radioId, fixture.store) {})
            assertEquals(listOf("Preserved"), fixture.store.fetchReactions(EntityKey(target.radioId, target.id)).map { it.senderName })
        }
    }

    @Test fun staleQueueImagesAndExcessDurableCapacityAreExplicitlyRejected() = runTest {
        disk(File(folder.root, "guards.db")) { fixture ->
            val service = fixture.service()
            val target = fixture.target()
            service.queuePendingReaction(pending(target, "First"))
            val old = fixture.store.reactionQueue.fetchReactionQueue()
            service.queuePendingReaction(pending(target, "Second"))
            val current = fixture.store.reactionQueue.fetchReactionQueue()
            assertFailsWith<ReactionQueueConflictException> {
                fixture.store.reactionQueue.replaceReactionQueue(old.version, old.copy(version = UUID.randomUUID()))
            }
            val oversized = (0..ReactionQueuePersisting.MAX_RETAINED).map {
                old.entries.single().copy(occurrenceID = UUID.randomUUID(), entryOrder = it.toLong())
            }.snapshot()
            val capacity = assertFailsWith<ReactionQueueCapacityException> {
                fixture.store.reactionQueue.replaceReactionQueue(current.version, ReactionQueueSnapshot(UUID.randomUUID(), oversized))
            }
            assertEquals(ReactionQueuePersisting.MAX_RETAINED, capacity.limit)
            assertEquals(current, fixture.store.reactionQueue.fetchReactionQueue())
        }
    }

    @Test fun interruptedSelectionRemainsVisibleButNeverRebindsToAnAvailableTarget() = runTest {
        val file = File(folder.root, "binding.db")
        val target = disk(file) { fixture ->
            val value = fixture.target()
            fixture.service().queuePendingReaction(pending(value, "Bob").copy(
                uncertainMutation = UncertainReactionMutation.Binding,
            ))
            value
        }
        disk(file) { fixture ->
            var lookups = 0
            val guarded = object : PersistenceStoreProtocol by fixture.store {
                override suspend fun findChannelMessageForReaction(
                    radioId: RadioId, channelIndex: UByte, parsedReaction: ParsedReaction, localNodeName: String?,
                    timestampWindow: ClosedRange<UInt>, limit: Long,
                ): MessageDTO? {
                    lookups++
                    return fixture.store.findChannelMessageForReaction(radioId, channelIndex, parsedReaction,
                        localNodeName, timestampWindow, limit)
                }
            }
            val service = fixture.service()
            assertIs<ReactionReplayResult.Retained>(service.recoverRetainedChannelReactions(target.radioId, guarded) {})
            assertIs<ReactionReplayResult.Retained>(fixture.replay(service, target, fixture.store))
            assertEquals(0, lookups)
            assertTrue(fixture.store.fetchReactions(EntityKey(target.radioId, target.id)).isEmpty())
            val row = fixture.store.reactionQueue.fetchReactionQueue().entries.single()
            assertNull(row.targetID)
            assertNull(row.targetIncarnation)
            assertEquals(ReactionMutationPhase.BINDING, row.mutation)
        }
    }

    private fun pending(target: MessageDTO, sender: String): PendingReaction {
        val raw = ReactionService().buildReactionText(emoji, "Alice", target.text, stamp)
        return PendingReaction(
            assertNotNull(ReactionParser.parse(raw)), 3u, sender, raw, target.radioId, receivedAt,
            localNodeName = "Original Local", deliveryContext = DeliveryContext.InitialSync(Instant.EPOCH.plusNanos(7)),
            verifyMutationOutcomes = true,
        )
    }

    private class Fixture(val database: MeshCoreDatabase, val store: RoomPersistenceStore) {
        fun service(journal: ReactionQueuePersisting = store.reactionQueue) =
            ReactionService(pendingReactionCustody = PendingReactionCustody(journal))
        suspend fun target(
            radio: RadioId = RadioId(UUID.randomUUID()), text: String = "Hello", id: UUID = UUID.randomUUID(),
        ): MessageDTO {
            store.saveChannel(radio, ChannelInfo(3u, "General", Bytes(ByteArray(16))))
            return MessageDTO(id = id, radioId = radio, channelIndex = 3u, text = text, timestamp = 1_704_067_200u,
                direction = MessageDirection.INCOMING, senderNodeName = "Alice").also { store.saveMessage(it) }
        }
        suspend fun replay(
            service: ReactionService, target: MessageDTO, persistence: ReactionPersisting,
            onPersisted: suspend (ReactionPersistResult) -> Unit = {},
        ): ReactionReplayResult = service.replayIndexedChannelMessage(
            target.id, target.radioId, 3u, "Alice", target.text, target.timestamp, persistence,
            store.fetchChannelReactionMatch(EntityKey(target.radioId, target.id))!!.channelReactionIncarnation, onPersisted,
        )
    }

    private suspend fun <T> disk(file: File, body: suspend (Fixture) -> T): T {
        val owner = SupervisorJob()
        val immediate = Executor { it.run() }
        val database = Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            MeshCoreDatabase::class.java, file.absolutePath).addCallback(DirectAckStorage.callback)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val store = RoomPersistenceStore(database, CoroutineScope(owner + Dispatchers.Default))
        return try { body(Fixture(database, store)) } finally {
            withContext(NonCancellable) {
                try { store.close() } finally { owner.cancelAndJoin(); database.close() }
            }
        }
    }
}
