// AndroidOnly: WP-202 Actual Room controls for builder-independent deferred simulator families.
package com.meshcoreone.android.core.data.repository

import android.database.sqlite.SQLiteConstraintException
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.ChannelIdentityMutex
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test

class DeferredSimulatorConversationRoomTest : RepositoryTest() {
    @Test fun deferredFacadeWaitsForTheChannelLaneAndReturnsOnlyPairedExistingAuthority() = runTest {
        val gate = ChannelIdentityMutex()
        val writer = RoomPersistenceStore(db, owner, clock, channelIdentityMutation = gate)
        val fixture = fixture(channel = true)
        val channel = assertIs<SimulatorConversationParent.Channel>(fixture.parent).channel
        val planned = plan(family(fixture))
        val release = CompletableDeferred<Unit>()
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            gate.withChannelOperation { release.await() }
        }
        val seed = async(start = CoroutineStart.UNDISPATCHED) { writer.seedSimulatorConversation(planned, 0) }
        try {
            assertFalse(seed.isCompleted)
            assertNull(db.channels().byId(channel.radioId.value, channel.id))
            release.complete(Unit)
            operation.await()
            val created = assertIs<SimulatorConversationSeedResult.Created>(seed.await())
            assertNull(assertIs<SimulatorConversationParent.Channel>(created.parent).channel.occupantID)
            val unresolved = assertNotNull(db.channelOccupants().stateForIndex(channel.radioId.value, channel.index.toLong()))
            assertTrue(unresolved.unresolved)
            assertNull(writer.fetchChannelOccupantSnapshot(channel.radioId, channel.index))
            val before = rawRows()
            val preserved = assertIs<SimulatorConversationSeedResult.PreservedExisting>(writer.seedSimulatorConversation(planned, 0))
            assertNull(assertIs<SimulatorConversationParent.Channel>(preserved.parent).channel.occupantID)
            assertEquals(before, rawRows())
            assertEquals(unresolved, db.channelOccupants().stateForIndex(channel.radioId.value, channel.index.toLong()))

            writer.saveChannel(channel.radioId, ChannelInfo(channel.index, channel.name, channel.secret))
            val current = assertNotNull(writer.fetchChannel(channel.radioId, channel.index))
            assertNotNull(current.occupantID)
            val authoritative = assertIs<SimulatorConversationSeedResult.PreservedExisting>(
                writer.seedSimulatorConversation(planned, 0),
            )
            assertEquals(current, assertIs<SimulatorConversationParent.Channel>(authoritative.parent).channel)
        } finally {
            release.complete(Unit)
            writer.close()
        }
    }

    private fun id(number: Int): UUID =
        UUID.fromString("abcdefab-1111-2222-3333-${number.toString(16).padStart(12, '0')}")

    private fun fixture(channel: Boolean = false, number: Int = 1, radio: RadioId = RADIO_A): SimulatorConversationSeed {
        val parent = if (channel) {
            SimulatorConversationParent.Channel(ChannelDTO(
                id = id(number * 100), radioId = radio, index = number.toUByte(), name = "Fixture $number",
                secret = Bytes(ByteArray(16) { number.toByte() }), unreadCount = 3,
            ))
        } else {
            SimulatorConversationParent.Direct(contact(radio, id(number * 100), key(number)).copy(unreadCount = 3))
        }
        val message = message(
            radio, id(number * 100 + 1), contactID = if (channel) null else id(number * 100),
            channelIndex = if (channel) number.toUByte() else null,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
        ).copy(
            deduplicationKey = "fixture-$number", reactionSummary = "fixture:1", heardRepeats = 3,
            linkPreviewURL = "https://example.test", linkPreviewFetched = true,
            linkPreviewImageData = Bytes.of(1, 2), linkPreviewIconData = Bytes.of(3),
        )
        return SimulatorConversationSeed(
            parent, SnapshotList.of(message),
            SnapshotList.of(ReactionDTO(
                id(number * 100 + 2), message.id, "fixture", "Peer", "00000000", "fixture", AT,
                radioId = radio, contactID = message.contactID, channelIndex = message.channelIndex,
            )),
            SnapshotList.of(MessageRepeatDTO(
                id(number * 100 + 3), message.id, AT.plusSeconds(5), Bytes.of(1), 1u,
            )),
        )
    }

    private fun family(
        seed: SimulatorConversationSeed,
        messages: Iterable<SimulatorMessageIdentity> = seed.messages.map {
            SimulatorMessageIdentity(it.id, it.deduplicationKey, it.replyToID)
        },
        reactions: Iterable<SimulatorReactionIdentity> = seed.reactions.map {
            SimulatorReactionIdentity(it.id, it.messageID, it.senderName, it.emoji)
        },
        repeats: Iterable<SimulatorRepeatIdentity> = seed.repeats.map {
            SimulatorRepeatIdentity(it.id, it.messageID, it.rxLogEntryID)
        },
        previews: Iterable<UUID> = seed.messages.filter { it.linkPreviewFetched }.map { it.id },
        summaries: Iterable<SimulatorReactionSummary> = seed.messages.mapNotNull {
            message -> message.reactionSummary?.let { SimulatorReactionSummary(message.id, it) }
        },
        repeatTargets: Iterable<UUID> = seed.repeats.map { it.messageID }.distinct(),
        build: () -> SimulatorConversationSeed = { seed },
    ) = SimulatorConversationFamily(seed.parent, messages, reactions, repeats, previews, summaries, repeatTargets, build)

    private fun plan(vararg families: SimulatorConversationFamily) = SimulatorConversationIdentityPlan.validate(families.toList())

    private suspend fun MessageDao.backupAll(): List<MessageEntity> =
        backupRadioIds().flatMap { radio ->
            backupPageWithoutPreviewBlobs(radio, -1, 0).map { assertNotNull(byId(radio, it.id)) }
        }

    private fun assertOriginalFailure(expected: Throwable, observed: Throwable) {
        assertTrue(generateSequence(observed) { it.cause }.any { it === expected })
    }

    private fun repositoryContext() =
        RoomRepositoryContext(db, clock, owner, RepositoryIssueReporter { _, failure -> issues += failure })

    private suspend fun seed(
        plan: SimulatorConversationIdentityPlan,
        afterFamily: (Int) -> Unit = {},
    ): SnapshotList<SimulatorConversationSeedResult> {
        val repository = repositoryContext()
        return try {
            val results = mutableListOf<SimulatorConversationSeedResult>()
            for (index in plan.families.indices) {
                results += repository.write("seedDeferredSimulatorConversation") {
                    seedDeferredSimulatorConversation(plan, index, ChannelRepository(repository, RoomRepository(repository)))
                }
                afterFamily(index)
            }
            results.snapshot()
        } finally {
            repository.close()
        }
    }

    private suspend fun saveParent(parent: SimulatorConversationParent) {
        when (parent) {
            is SimulatorConversationParent.Direct -> store.saveContact(parent.contact)
            is SimulatorConversationParent.Channel -> store.saveChannel(parent.channel)
        }
    }

    private fun rawRows(): Map<String, List<List<String?>>> =
        listOf("contacts", "channels", "messages", "reactions", "message_repeats", "pending_sends", "rx_log_entries")
            .associateWith { table ->
                db.openHelper.writableDatabase.query("SELECT * FROM `$table` ORDER BY radioId, id").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add((0 until cursor.columnCount).map { column ->
                            when (cursor.getType(column)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> null
                                android.database.Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList().toString()
                                else -> cursor.getString(column)
                            }
                        })
                    }
                }
            }

    private fun assertInvalid(block: () -> Unit) {
        assertEquals(PersistenceStoreError.InvalidData, assertFailsWith<PersistenceStoreException>(block = block).error)
    }

    private suspend fun assertReadbackFailure(
        plan: SimulatorConversationIdentityPlan,
        afterFamily: (Int) -> Unit = {},
    ) {
        val failure = assertFailsWith<PersistenceStoreException> { seed(plan, afterFamily) }
        assertIs<PersistenceStoreError.SaveFailed>(failure.error)
        assertEquals(
            "Simulator conversation insert did not persist the expected rows",
            assertIs<IllegalStateException>(failure.cause).message,
        )
    }

    @Test fun allFamiliesAreValidatedBeforeAnyBuilderIncludingSkippedFamilies() = runTest {
        val first = fixture()
        val second = fixture(true, 2)
        saveParent(first.parent)
        var calls = 0
        val before = rawRows()
        assertInvalid {
            plan(
                family(first, build = { calls++; first }),
                family(second, messages = listOf(SimulatorMessageIdentity(first.messages.single().id, null)),
                    build = { calls++; second }),
            )
        }
        assertEquals(0, calls)
        assertEquals(before, rawRows())
    }

    @Test fun globalParentMessageReactionRepeatAndCanonicalDedupCollisionsNeverBuild() = runTest {
        val first = fixture()
        val other = fixture(true, 2)
        var calls = 0
        val one = family(first, build = { calls++; first })
        val variants = listOf(
            family(first, build = { calls++; first }),
            family(other, messages = listOf(SimulatorMessageIdentity(first.messages.single().id, null))),
            family(other, reactions = listOf(SimulatorReactionIdentity(
                first.reactions.single().id, other.messages.single().id, "Peer", "fixture",
            ))),
            family(other, repeats = listOf(SimulatorRepeatIdentity(
                first.repeats.single().id, other.messages.single().id,
            ))),
        )
        for (variant in variants) assertInvalid { plan(one, variant) }
        val composed = first.copy(messages = first.messages.map { it.copy(deduplicationKey = "caf\u00e9") }.snapshot())
        val decomposed = other.copy(messages = other.messages.map { it.copy(deduplicationKey = "cafe\u0301") }.snapshot())
        assertInvalid { plan(family(composed), family(decomposed)) }
        assertEquals(0, calls)
        assertTrue(db.messages().backupAll().isEmpty())
    }

    @Test fun orphanReplySidecarTargetsAndRxReferencesRejectWithoutBuilders() = runTest {
        val fixture = fixture()
        val message = fixture.messages.single()
        val reaction = fixture.reactions.single()
        val repeat = fixture.repeats.single()
        val invalid = listOf(
            family(fixture, messages = listOf(SimulatorMessageIdentity(message.id, message.deduplicationKey, id(999)))),
            family(fixture, reactions = listOf(SimulatorReactionIdentity(reaction.id, id(999), "Peer", "fixture"))),
            family(fixture, repeats = listOf(SimulatorRepeatIdentity(repeat.id, id(999)))),
            family(fixture, repeats = listOf(SimulatorRepeatIdentity(repeat.id, message.id, id(999)))),
            family(fixture, previews = listOf(id(999))),
            family(fixture, summaries = listOf(SimulatorReactionSummary(id(999), "fixture:1"))),
            family(fixture, summaries = listOf(SimulatorReactionSummary(message.id, "fixture:99"))),
            family(fixture, repeatTargets = listOf(id(999))),
        )
        for (entry in invalid) assertInvalid { plan(entry) }
        assertTrue(db.messages().backupAll().isEmpty())
    }

    @Test fun independentParentIdentityKeyAndSlotCollisionsRejectBeforeConstruction() = runTest {
        val direct = fixture()
        val otherDirect = fixture(number = 2)
        val contact = assertIs<SimulatorConversationParent.Direct>(direct.parent).contact
        val otherContact = assertIs<SimulatorConversationParent.Direct>(otherDirect.parent).contact
        val channel = fixture(true, 3)
        val otherChannel = fixture(true, 4)
        val firstChannel = assertIs<SimulatorConversationParent.Channel>(channel.parent).channel
        val secondChannel = assertIs<SimulatorConversationParent.Channel>(otherChannel.parent).channel
        for (replacement in listOf(otherContact.copy(id = contact.id), otherContact.copy(publicKey = contact.publicKey))) {
            assertInvalid { plan(family(direct), family(otherDirect.copy(parent = SimulatorConversationParent.Direct(replacement)))) }
        }
        for (replacement in listOf(secondChannel.copy(id = firstChannel.id), secondChannel.copy(index = firstChannel.index))) {
            assertInvalid { plan(family(channel), family(otherChannel.copy(parent = SimulatorConversationParent.Channel(replacement)))) }
        }
        val independent = fixture(true, 3, RADIO_B)
        assertEquals(2, plan(family(channel), family(independent)).families.size)
    }

    @Test fun canonicalReactionSenderIdentityCollisionRejectsDistinctRowIds() = runTest {
        val fixture = fixture()
        val messageID = fixture.messages.single().id
        assertInvalid {
            plan(family(fixture, reactions = listOf(
                SimulatorReactionIdentity(id(901), messageID, "caf\u00e9", "fixture"),
                SimulatorReactionIdentity(id(902), messageID, "cafe\u0301", "fixture"),
            ), summaries = listOf(SimulatorReactionSummary(messageID, "fixture:2"))))
        }
        assertTrue(db.reactions().backupAll().isEmpty())
    }

    @Test fun callerCollectionsAreSnapshottedBeforeTheyCanBeChanged() = runTest {
        val fixture = fixture()
        val messages = mutableListOf(SimulatorMessageIdentity(
            fixture.messages.single().id, fixture.messages.single().deduplicationKey,
        ))
        val entries = mutableListOf(family(fixture, messages = messages))
        val planned = SimulatorConversationIdentityPlan.validate(entries)
        entries.clear()
        messages.clear()
        assertEquals(1, planned.families.size)
        assertEquals(1, planned.families.single().messages.size)
        assertIs<SimulatorConversationSeedResult.Created>(seed(planned).single())
    }

    @Test fun existingDirectAndChannelParentsNeverInvokeBuildersOrRewriteChildren() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) 2 else 1)
            saveParent(fixture.parent)
            db.messages().insert(fixture.messages.single().toEntity().copy(text = "user-edited"))
            val before = rawRows()
            val result = seed(plan(family(fixture, build = { error("Existing parent must not build") })))
            assertIs<SimulatorConversationSeedResult.PreservedExisting>(result.single())
            assertEquals(before, rawRows())
        }
    }

    @Test fun existingAlternateIdentityReturnsActualParentWithoutBuildingFixedIdChildren() = runTest {
        val fixture = fixture()
        val requested = assertIs<SimulatorConversationParent.Direct>(fixture.parent).contact
        val actual = requested.copy(id = id(999), nickname = "user")
        store.saveContact(actual)
        val result = assertIs<SimulatorConversationSeedResult.PreservedExisting>(
            seed(plan(family(fixture, build = { error("Remapped existing parent must not build") }))).single(),
        )
        assertEquals(actual, assertIs<SimulatorConversationParent.Direct>(result.parent).contact)
        assertTrue(db.messages().backupAll().isEmpty())
    }

    @Test fun existingContactOnlyAdvancesApprovedFreshnessAndSavesWithoutBuilding() = runTest {
        val fixture = fixture()
        val original = assertIs<SimulatorConversationParent.Direct>(fixture.parent).contact
            .copy(lastModified = 10u, nickname = "user", unreadCount = 0)
        store.saveContact(original)
        val requested = fixture.copy(parent = SimulatorConversationParent.Direct(original.copy(
            lastModified = 20u, nickname = "fixture", unreadCount = 99,
        )))
        val repository = repositoryContext()
        try {
            val result = repository.write("deferredFreshness") {
                val value = seedDeferredSimulatorConversation(
                    plan(family(requested, build = { error("Existing contact must not build") })), 0,
                    ChannelRepository(repository, RoomRepository(repository)),
                )
                assertTrue(saves)
                value
            }
            val expected = original.copy(lastModified = 20u)
            val preserved = assertIs<SimulatorConversationSeedResult.PreservedExisting>(result)
            assertEquals(expected, assertIs<SimulatorConversationParent.Direct>(preserved.parent).contact)
            assertEquals(expected, db.contacts().backupAll().single().toDTO())
            assertTrue(db.messages().backupAll().isEmpty())
        } finally {
            repository.close()
        }
    }

    private class JobWitness : ThreadContextElement<Job?>, AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<JobWitness>
        val observed = ThreadLocal<Job?>()
        override fun updateThreadContext(context: CoroutineContext): Job? =
            observed.get().also { observed.set(context[Job]) }
        override fun restoreThreadContext(context: CoroutineContext, oldState: Job?) { observed.set(oldState) }
    }

    @Test fun absentBuilderRunsOnceInsideTheActualTransactionAndExactPrepareApplyJob() = runTest {
        val fixture = fixture()
        val repository = repositoryContext()
        val witness = JobWitness()
        var calls = 0
        var transactionJob: Job? = null
        val planned = plan(family(fixture, build = {
            assertTrue(db.inTransaction())
            assertSame(assertNotNull(transactionJob), witness.observed.get())
            calls++
            fixture
        }))
        try {
            withContext(witness) {
                repository.write("deferredJobControl") {
                    transactionJob = currentCoroutineContext()[Job]
                    seedDeferredSimulatorConversation(planned, 0, ChannelRepository(repository, RoomRepository(repository)))
                }
            }
            assertEquals(1, calls)
            assertEquals(fixture.messages.single(), db.messages().backupAll().single().toDTO())
            assertEquals(fixture.reactions.single(), db.reactions().backupAll().single().toDTO())
            assertEquals(fixture.repeats.single(), db.repeats().backupAll().single().toDTO())
        } finally {
            repository.close()
        }
    }

    @Test fun eachBuiltIdentityParentAndSidecarMismatchFailsWithNoPartialWrites() = runTest {
        val fixture = fixture()
        val mutations = listOf(
            fixture.copy(parent = this@DeferredSimulatorConversationRoomTest.fixture(true, 2).parent),
            fixture.copy(messages = fixture.messages.map { it.copy(id = id(999)) }.snapshot()),
            fixture.copy(messages = fixture.messages.map { it.copy(deduplicationKey = "wrong") }.snapshot()),
            fixture.copy(messages = fixture.messages.map { it.copy(replyToID = id(999)) }.snapshot()),
            fixture.copy(messages = fixture.messages.map { it.copy(contactID = id(999)) }.snapshot()),
            fixture.copy(messages = fixture.messages.map { it.copy(linkPreviewFetched = false) }.snapshot()),
            fixture.copy(messages = fixture.messages.map { it.copy(reactionSummary = "wrong") }.snapshot()),
            fixture.copy(reactions = fixture.reactions.map { it.copy(senderName = "wrong") }.snapshot()),
            fixture.copy(reactions = fixture.reactions.map { it.copy(radioId = RADIO_B) }.snapshot()),
            fixture.copy(repeats = fixture.repeats.map { it.copy(id = id(999)) }.snapshot()),
        )
        val before = rawRows()
        for (mutated in mutations) {
            val error = assertFailsWith<PersistenceStoreException> { seed(plan(family(fixture, build = { mutated }))) }
            assertEquals(PersistenceStoreError.InvalidData, error.error)
            assertEquals(before, rawRows())
        }
    }

    @Test fun laterBuilderFailureRetainsItsCauseWithoutRollingBackEarlierCommittedFamilies() = runTest {
        val first = fixture()
        val second = fixture(true, 2)
        val failure = IllegalStateException("control: builder failed")
        var committed = rawRows()
        assertOriginalFailure(failure, assertFailsWith<IllegalStateException> {
            seed(plan(family(first), family(second, build = { throw failure }))) {
                if (it == 0) committed = rawRows()
            }
        })
        assertEquals(committed, rawRows())
        assertEquals(first.messages.single(), db.messages().backupAll().single().toDTO())
        assertTrue(db.channels().backupAll().isEmpty())
    }

    @Test fun deferredEntryOutsideAnActualRoomTransactionFailsBeforeBuilding() = runTest {
        val fixture = fixture()
        val repository = repositoryContext()
        var calls = 0
        try {
            val error = assertFailsWith<PersistenceStoreException> {
                RepositoryTransaction(db, clock, emptyMap()).seedDeferredSimulatorConversation(
                    plan(family(fixture, build = { calls++; fixture })), 0,
                    ChannelRepository(repository, RoomRepository(repository)),
                )
            }
            assertEquals(PersistenceStoreError.InvalidData, error.error)
            assertEquals(0, calls)
            assertTrue(db.contacts().backupAll().isEmpty())
        } finally {
            repository.close()
        }
    }

    @Test fun builderChangingThePreparedParentIsRefusedByTheRealParentProducerAndRolledBack() = runTest {
        for (channel in listOf(false, true)) {
            val fixture = fixture(channel, if (channel) 2 else 1)
            val template = fixture(channel, 9)
            saveParent(template.parent)
            val before = rawRows()
            val table = if (channel) "channels" else "contacts"
            val replacements = when (val parent = fixture.parent) {
                is SimulatorConversationParent.Direct -> mapOf(
                    "id" to "'${parent.contact.id.canonicalString()}'",
                    "publicKey" to "X'${parent.contact.publicKey.uppercaseHexString()}'",
                )
                is SimulatorConversationParent.Channel -> mapOf(
                    "id" to "'${parent.channel.id.canonicalString()}'",
                    "index" to parent.channel.index.toString(),
                    "secret" to "X'${parent.channel.secret.uppercaseHexString()}'",
                )
            }
            val sql = db.openHelper.writableDatabase
            val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            val insert = "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) " +
                "SELECT ${columns.joinToString { replacements[it] ?: "`$it`" }} FROM `$table` LIMIT 1"
            val error = assertFailsWith<PersistenceStoreException> {
                seed(plan(family(fixture, build = {
                    sql.execSQL(insert)
                    fixture
                })))
            }
            assertIs<PersistenceStoreError.SaveFailed>(error.error)
            assertEquals(before, rawRows())
        }
    }

    @Test fun callerCancellationBeforeCommitRollsBackCurrentFamilyAndRetryBuildsOnce() = runBlocking<Unit> {
        val fixture = fixture()
        val repository = repositoryContext()
        val entered = CompletableDeferred<Unit>()
        var calls = 0
        val planned = plan(family(fixture, build = { calls++; fixture }))
        val before = rawRows()
        try {
            coroutineScope {
                val work = async(Dispatchers.Default) {
                    repository.write("deferredCancellation") {
                        seedDeferredSimulatorConversation(planned, 0, ChannelRepository(repository, RoomRepository(repository)))
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                withTimeout(10_000) { entered.await() }
                work.cancelAndJoin()
                assertTrue(work.isCancelled)
            }
            assertEquals(before, rawRows())
            assertIs<SimulatorConversationSeedResult.Created>(seed(planned).single())
            assertEquals(2, calls)
        } finally {
            repository.close()
        }
    }

    @Test fun laterFamilyCancellationRetainsEarlierCommitAndOnlyRollsBackItsOwnWrites() = runBlocking<Unit> {
        val first = fixture()
        val later = fixture(true, 2)
        val planned = plan(family(first), family(later))
        val repository = repositoryContext()
        val entered = CompletableDeferred<Unit>()
        try {
            repository.write("firstDeferredFamily") {
                seedDeferredSimulatorConversation(planned, 0, ChannelRepository(repository, RoomRepository(repository)))
            }
            val committed = rawRows()
            coroutineScope {
                val work = async(Dispatchers.Default) {
                    repository.write("cancelLaterDeferredFamily") {
                        seedDeferredSimulatorConversation(planned, 1, ChannelRepository(repository, RoomRepository(repository)))
                        entered.complete(Unit)
                        awaitCancellation()
                    }
                }
                withTimeout(10_000) { entered.await() }
                work.cancelAndJoin()
                assertTrue(work.isCancelled)
            }
            assertEquals(committed, rawRows())
            assertEquals(first.messages.single(), db.messages().backupAll().single().toDTO())
            assertTrue(db.channels().backupAll().isEmpty())
        } finally {
            repository.close()
        }
    }

    @Test fun invalidFamilyIndexOrAbsentEarlierParentFailsWithoutBuildingOrGuessingProgress() = runTest {
        val first = fixture()
        val later = fixture(true, 2)
        var calls = 0
        val planned = plan(family(first, build = { calls++; first }), family(later, build = { calls++; later }))
        val repository = repositoryContext()
        val before = rawRows()
        try {
            for (index in listOf(-1, 2, 1)) {
                val error = assertFailsWith<PersistenceStoreException> {
                    repository.write("invalidDeferredPosition") {
                        seedDeferredSimulatorConversation(planned, index, ChannelRepository(repository, RoomRepository(repository)))
                    }
                }
                assertEquals(PersistenceStoreError.InvalidData, error.error)
                assertEquals(before, rawRows())
            }
            assertEquals(0, calls)
        } finally {
            repository.close()
        }
    }

    @Test fun concurrentSeparateContextsCreateOnceAndOnlyTheWinnerBuilds() = runBlocking<Unit> {
        val fixture = fixture(true)
        val calls = AtomicInteger()
        val planned = plan(family(fixture, build = { calls.incrementAndGet(); fixture }))
        val start = CompletableDeferred<Unit>()
        val results = coroutineScope {
            val first = async(Dispatchers.Default) { start.await(); seed(planned).single() }
            val second = async(Dispatchers.Default) { start.await(); seed(planned).single() }
            start.complete(Unit)
            withTimeout(10_000) { listOf(first.await(), second.await()) }
        }
        assertEquals(1, calls.get())
        assertEquals(1, results.filterIsInstance<SimulatorConversationSeedResult.Created>().size)
        assertEquals(1, results.filterIsInstance<SimulatorConversationSeedResult.PreservedExisting>().size)
        assertEquals(fixture.messages.single(), db.messages().backupAll().single().toDTO())
    }

    @Test fun databaseChildCollisionRefusesInsteadOfAdoptingUnrelatedRows() = runTest {
        val fixture = fixture()
        db.messages().insert(fixture.messages.single().copy(contactID = id(999)).toEntity())
        val before = rawRows()
        val error = assertFailsWith<PersistenceStoreException> { seed(plan(family(fixture))) }
        assertEquals(PersistenceStoreError.InvalidData, error.error)
        assertEquals(before, rawRows())
        assertTrue(db.contacts().backupAll().isEmpty())
    }

    @Test fun ignoredAndAlteredInsertsMustNotProduceCreatedResults() = runTest {
        val fixture = fixture()
        val before = rawRows()
        for (ignore in listOf(true, false)) {
            val timing = if (ignore) "BEFORE" else "AFTER"
            val body = if (ignore) "SELECT RAISE(IGNORE);" else
                "UPDATE messages SET text = 'altered' WHERE radioId = NEW.radioId AND id = NEW.id;"
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER alter_deferred $timing INSERT ON messages BEGIN $body END")
            if (ignore) {
                val failure = assertFailsWith<PersistenceStoreException> { seed(plan(family(fixture))) }
                assertIs<PersistenceStoreError.SaveFailed>(failure.error)
                assertIs<SQLiteConstraintException>(failure.cause)
            } else {
                assertReadbackFailure(plan(family(fixture)))
            }
            assertEquals(before, rawRows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER alter_deferred")
        }
        assertIs<SimulatorConversationSeedResult.Created>(seed(plan(family(fixture))).single())
    }

    @Test fun laterFamilyChildMutationOfEarlierCreatedOrPreservedParentFailsFinalReadback() = runTest {
        for (preserve in listOf(false, true)) for (channel in listOf(false, true)) {
            val first = fixture(channel, if (preserve) 3 else 1)
            val later = fixture(!channel, if (preserve) 4 else 2)
            if (preserve) saveParent(first.parent)
            var committed = rawRows()
            val table = if (channel) "channels" else "contacts"
            val parentID = when (val parent = first.parent) {
                is SimulatorConversationParent.Direct -> parent.contact.id
                is SimulatorConversationParent.Channel -> parent.channel.id
            }
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER contaminate_earlier AFTER INSERT ON messages " +
                    "WHEN NEW.id = '${later.messages.single().id.canonicalString()}' BEGIN " +
                    "UPDATE $table SET unreadCount = 99 WHERE id = '${parentID.canonicalString()}'; END",
            )
            val planned = plan(family(first, build = {
                assertFalse(preserve, "Existing parent unexpectedly built")
                first
            }), family(later))
            assertReadbackFailure(planned) { if (it == 0) committed = rawRows() }
            assertEquals(committed, rawRows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER contaminate_earlier")
            assertEquals(2, seed(planned).size)
            for (name in listOf("message_repeats", "reactions", "messages", "contacts", "channels")) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM `$name`")
            }
        }
    }

    @Test fun currentFamilySidecarDmlCannotAlterItsMessageBeforeFinalReadback() = runTest {
        val first = fixture()
        val before = rawRows()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER contaminate_message AFTER INSERT ON message_repeats BEGIN " +
                "UPDATE messages SET text = 'late corruption' WHERE id = '${first.messages.single().id.canonicalString()}'; END",
        )
        assertReadbackFailure(plan(family(first)))
        assertEquals(before, rawRows())
    }

    @Test fun laterChildLogicalParentTwinsFailAfterEarlierCreatedAndPreservedParentsWereRead() = runTest {
        val radio = RadioId(id(8000))
        for (channel in listOf(false, true)) for (preserve in listOf(false, true)) {
            for (identity in listOf(false, true)) {
                val first = fixture(channel, radio = radio)
                val later = fixture(!channel, 2, radio)
                if (preserve) saveParent(first.parent)
                var committed = rawRows()
                val table = if (channel) "channels" else "contacts"
                val parentID = when (val parent = first.parent) {
                    is SimulatorConversationParent.Direct -> parent.contact.id
                    is SimulatorConversationParent.Channel -> parent.channel.id
                }
                val sql = db.openHelper.writableDatabase
                val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor ->
                    buildList {
                        while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                    }
                }
                val replacements = mutableMapOf(
                    "id" to if (identity) "lower(id)" else "'${id(999).canonicalString()}'",
                    "radioId" to "lower(radioId)",
                )
                if (identity) {
                    if (channel) replacements["index"] = "9"
                    else replacements["publicKey"] = "X'${"66".repeat(32)}'"
                }
                val values = columns.joinToString { replacements[it] ?: "`$it`" }
                sql.execSQL(
                    "CREATE TRIGGER late_deferred_twin AFTER INSERT ON messages " +
                        "WHEN NEW.id = '${later.messages.single().id.canonicalString()}' BEGIN " +
                        "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) " +
                        "SELECT $values FROM `$table` WHERE id = '${parentID.canonicalString()}'; END",
                )
                assertReadbackFailure(plan(family(first), family(later))) { if (it == 0) committed = rawRows() }
                assertEquals(committed, rawRows())
                sql.execSQL("DROP TRIGGER late_deferred_twin")
                for (name in listOf("message_repeats", "reactions", "messages", "contacts", "channels")) {
                    sql.execSQL("DELETE FROM `$name`")
                }
            }
        }
    }

    @Test fun bufferedRxDmlIsIncludedBeforeTheFinalParentAndChildReadbacks() = runTest {
        val fixture = fixture()
        val repository = repositoryContext()
        repository.saveRx(rx().toEntity())
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER deferred_rx_mutation AFTER INSERT ON rx_log_entries BEGIN " +
                "UPDATE contacts SET unreadCount = 99; END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> {
                repository.write("deferredRxControl") {
                    seedDeferredSimulatorConversation(
                        plan(family(fixture)), 0, ChannelRepository(repository, RoomRepository(repository)),
                    )
                }
            }
            assertEquals(
                "Simulator conversation insert did not persist the expected rows",
                assertIs<IllegalStateException>(failure.cause).message,
            )
            assertTrue(db.contacts().backupAll().isEmpty())
            assertEquals(0L, db.rxLogs().count(RADIO_A.value))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER deferred_rx_mutation")
            repository.flushRx()
            assertEquals(1L, db.rxLogs().count(RADIO_A.value))
        } finally {
            repository.close()
        }
    }

    @Test fun bufferedRxAliasFailureRollsBackConversationAndRetainsPendingForRetry() = runTest {
        val fixture = fixture()
        val pending = rx(id = id(900))
        val repository = repositoryContext()
        repository.saveRx(pending.toEntity())
        val sql = db.openHelper.writableDatabase
        sql.execSQL(
            "CREATE TRIGGER deferred_rx_alias AFTER INSERT ON rx_log_entries BEGIN " +
                "UPDATE rx_log_entries SET id = lower(id) WHERE id = NEW.id; END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> {
                repository.write("seedSimulatorConversation") {
                    seedDeferredSimulatorConversation(
                        plan(family(fixture)), 0, ChannelRepository(repository, RoomRepository(repository)),
                    )
                }
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertTrue(db.contacts().backupAll().isEmpty())
            assertTrue(db.messages().backupAll().isEmpty())
            assertEquals(0L, db.rxLogs().count(RADIO_A.value))

            sql.execSQL("DROP TRIGGER deferred_rx_alias")
            repository.flushRx()
            assertEquals(listOf(pending.id), db.rxLogs().newest(RADIO_A.value, -1).map { it.id })
            val retried = repository.write("seedSimulatorConversation") {
                seedDeferredSimulatorConversation(
                    plan(family(fixture)), 0, ChannelRepository(repository, RoomRepository(repository)),
                )
            }
            assertIs<SimulatorConversationSeedResult.Created>(retried)
            assertEquals(fixture.messages.map { it.id }, db.messages().backupAll().map { it.id })
        } finally {
            sql.execSQL("DROP TRIGGER IF EXISTS deferred_rx_alias")
            repository.close()
        }
    }

    @Test fun failureInANewCallDoesNotPretendToRollbackAnEarlierCommittedFamily() = runTest {
        val first = fixture()
        val later = fixture(true, 2)
        assertIs<SimulatorConversationSeedResult.Created>(seed(plan(family(first))).single())
        val before = rawRows()
        val failure = IllegalStateException("control: later call")
        assertOriginalFailure(failure, assertFailsWith<IllegalStateException> {
            seed(plan(family(first, build = { error("Existing must not build") }),
                family(later, build = { throw failure })))
        })
        assertEquals(before, rawRows())
        assertEquals(first.messages.single(), db.messages().backupAll().single().toDTO())
    }

    @Test fun earlierChildCannotMutateAnAlreadyExistingLaterParentBeforeItsTurn() = runTest {
        for (channel in listOf(false, true)) {
            val first = fixture(!channel)
            val future = fixture(channel, 2)
            saveParent(future.parent)
            val before = rawRows()
            val table = if (channel) "channels" else "contacts"
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER alter_future_parent AFTER INSERT ON messages BEGIN " +
                    "UPDATE $table SET unreadCount = 99 WHERE id = '${id(200).canonicalString()}'; END",
            )
            assertReadbackFailure(plan(family(first), family(future, build = { error("Future must not build") })))
            assertEquals(before, rawRows())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER alter_future_parent")
            for (name in listOf("message_repeats", "reactions", "messages", "contacts", "channels")) {
                db.openHelper.writableDatabase.execSQL("DELETE FROM `$name`")
            }
        }
    }

    @Test fun earlierChildCannotIntroduceLogicalIdentityOrKeyTwinsOfAnExistingLaterParent() = runTest {
        val radio = RadioId(id(8000))
        for (channel in listOf(false, true)) for (identity in listOf(false, true)) {
            val first = fixture(!channel, radio = radio)
            val future = fixture(channel, 2, radio)
            saveParent(future.parent)
            val before = rawRows()
            val table = if (channel) "channels" else "contacts"
            val sql = db.openHelper.writableDatabase
            val columns = sql.query("PRAGMA table_info(`$table`)").use { cursor ->
                buildList {
                    while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                }
            }
            val replacements = mutableMapOf(
                "id" to if (identity) "lower(id)" else "'${id(999).canonicalString()}'",
                "radioId" to "lower(radioId)",
            )
            if (identity) {
                if (channel) replacements["index"] = "9"
                else replacements["publicKey"] = "X'${"66".repeat(32)}'"
            }
            sql.execSQL(
                "CREATE TRIGGER clone_future_parent AFTER INSERT ON messages BEGIN " +
                    "INSERT INTO `$table` (${columns.joinToString { "`$it`" }}) " +
                    "SELECT ${columns.joinToString { replacements[it] ?: "`$it`" }} " +
                    "FROM `$table` WHERE id = '${id(200).canonicalString()}'; END",
            )
            assertReadbackFailure(plan(family(first), family(future)))
            assertEquals(before, rawRows())
            sql.execSQL("DROP TRIGGER clone_future_parent")
            for (name in listOf("message_repeats", "reactions", "messages", "contacts", "channels")) {
                sql.execSQL("DELETE FROM `$name`")
            }
        }
    }

    @Test fun invalidFutureParentIsNotAdmittedEarlyAndDoesNotRollbackAnEarlierValidCommit() = runTest {
        val first = fixture()
        val future = fixture(true, 2)
        val requested = assertIs<SimulatorConversationParent.Channel>(future.parent).channel
        store.saveChannel(requested.copy(secret = Bytes(ByteArray(16) { 0x55 })))
        var committed = rawRows()
        val failure = assertFailsWith<PersistenceStoreException> {
            seed(plan(family(first), family(future, build = { error("Incompatible future must not build") }))) {
                if (it == 0) committed = rawRows()
            }
        }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(committed, rawRows())
        assertEquals(first.messages.single(), db.messages().backupAll().single().toDTO())
    }

    @Test fun cancellationDuringSynchronousBuilderTakesPriorityOverItsSubsequentException() = runTest {
        val fixture = fixture()
        val repository = repositoryContext()
        val builderFailure = IllegalStateException("control: builder throws after cancelling exact TX job")
        val before = rawRows()
        try {
            var job: Job? = null
            val cancelled = assertFailsWith<CancellationException> {
                repository.write("cancelInsideDeferredBuilder") {
                    job = currentCoroutineContext()[Job]
                    seedDeferredSimulatorConversation(plan(family(fixture, build = {
                        assertTrue(db.inTransaction())
                        assertNotNull(job).cancel()
                        throw builderFailure
                    })), 0, ChannelRepository(repository, RoomRepository(repository)))
                }
            }
            assertTrue(generateSequence<Throwable>(cancelled) { it.cause }.any { error ->
                error.suppressed.any { it === builderFailure }
            })
            assertEquals(before, rawRows())
        } finally {
            repository.close()
        }
    }

    @Test fun canonicalEquivalentStoredDeduplicationKeyRejectsBeforeParentDml() = runTest {
        val fixture = fixture().let { seed -> seed.copy(
            messages = seed.messages.map { it.copy(deduplicationKey = "caf\u00e9") }.snapshot(),
        ) }
        db.messages().insert(message(contactID = id(999)).copy(deduplicationKey = "cafe\u0301").toEntity())
        val before = rawRows()
        val failure = assertFailsWith<PersistenceStoreException> { seed(plan(family(fixture))) }
        assertEquals(PersistenceStoreError.InvalidData, failure.error)
        assertEquals(before, rawRows())
        assertTrue(db.contacts().backupAll().isEmpty())
    }

    @Test fun canonicalEquivalentLateDeduplicationCollisionFailsActualFinalReadback() = runTest {
        val fixture = fixture().let { seed -> seed.copy(
            messages = seed.messages.map { it.copy(deduplicationKey = "caf\u00e9") }.snapshot(),
        ) }
        val foreign = message(contactID = id(999)).toEntity()
        db.messages().insert(foreign)
        val before = rawRows()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER late_canonical_key AFTER INSERT ON messages " +
                "WHEN NEW.id = '${fixture.messages.single().id.canonicalString()}' BEGIN " +
                "UPDATE messages SET deduplicationKey = 'cafe\u0301' WHERE id = '${foreign.id.canonicalString()}'; END",
        )
        assertReadbackFailure(plan(family(fixture)))
        assertEquals(before, rawRows())
    }

    @Test fun futureContactCoordinatesAreComparedByRealBitsNotRoundedText() = runTest {
        val original = 1.0
        val changed = java.lang.Math.nextUp(original)
        val sql = db.openHelper.writableDatabase
        sql.query("SELECT ?, ?", arrayOf(original, changed)).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertNotEquals(cursor.getDouble(0).toRawBits(), cursor.getDouble(1).toRawBits())
            assertEquals(cursor.getString(0), cursor.getString(1), "Control must defeat the old text snapshot")
        }
        for ((index, coordinate) in listOf("latitude", "longitude").withIndex()) {
            val first = fixture(true, index * 3 + 1)
            val future = fixture(false, index * 3 + 2)
            val parent = assertIs<SimulatorConversationParent.Direct>(future.parent).contact
            store.saveContact(parent.copy(latitude = original, longitude = original))
            val before = rawRows()
            sql.execSQL(
                "CREATE TRIGGER alter_future_real AFTER INSERT ON messages BEGIN " +
                    "UPDATE contacts SET $coordinate = $changed WHERE id = '${parent.id.canonicalString()}'; END",
            )
            assertReadbackFailure(plan(family(first), family(future, build = { error("Future must not build") })))
            assertEquals(before, rawRows())
            sql.query("SELECT latitude, longitude FROM contacts WHERE id = ?", arrayOf(parent.id.canonicalString())).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(original.toRawBits(), cursor.getDouble(0).toRawBits())
                assertEquals(original.toRawBits(), cursor.getDouble(1).toRawBits())
            }
            assertTrue(db.messages().backupAll().isEmpty())
            sql.execSQL("DROP TRIGGER alter_future_real")
        }
    }

    @Test fun futureParentAndDeduplicationQueriesUseBinaryRadioRangesAndRetainCaseAliases() = runTest {
        val radio = RadioId(id(8000))
        val foreign = RadioId(id(8001))
        val sql = db.openHelper.writableDatabase
        fun assertRadioRange(query: String, arguments: Array<Any>) {
            val details = sql.query("EXPLAIN QUERY PLAN $query", arguments).use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("detail"))) }
            }
            assertTrue(details.any { it.startsWith("SEARCH ") && it.contains("radioId>") && it.contains("radioId<") },
                "Expected binary radio range search: $details")
            assertFalse(details.any { it.startsWith("SCAN contacts") || it.startsWith("SCAN channels") || it.startsWith("SCAN messages") },
                "Unexpected full-table scan: $details")
        }
        for (channel in listOf(false, true)) {
            val target = fixture(channel, if (channel) 2 else 1, radio)
            val other = fixture(channel, if (channel) 2 else 1, foreign)
            saveParent(target.parent)
            saveParent(other.parent)
            val table = if (channel) "channels" else "contacts"
            sql.execSQL("UPDATE $table SET radioId = lower(radioId) WHERE radioId = ?", arrayOf(radio.value.canonicalString()))
            val parent = target.parent
            val arguments: Array<Any> = when (parent) {
                is SimulatorConversationParent.Direct -> arrayOf(
                    radio.value.toString(), radio.value.toString(), radio.value.toString(),
                    parent.contact.id.toString(), parent.contact.publicKey.toByteArray(),
                )
                is SimulatorConversationParent.Channel -> arrayOf(
                    radio.value.toString(), radio.value.toString(), radio.value.toString(),
                    parent.channel.id.toString(), parent.channel.index.toLong(),
                )
            }
            val query = if (channel) SIMULATOR_FUTURE_CHANNEL_QUERY else SIMULATOR_FUTURE_CONTACT_QUERY
            assertRadioRange(query, arguments)
            sql.query(query, arguments).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(radio.value.toString().lowercase(), cursor.getString(cursor.getColumnIndexOrThrow("radioId")))
                assertFalse(cursor.moveToNext(), "Other-radio parent must not be included")
            }
        }
        db.messages().insert(message(radio).copy(deduplicationKey = "target").toEntity())
        db.messages().insert(message(foreign).copy(deduplicationKey = "foreign").toEntity())
        sql.execSQL("UPDATE messages SET radioId = lower(radioId) WHERE radioId = ?", arrayOf(radio.value.canonicalString()))
        // Mirrors the DAO annotation; the source-integrity check binds this SQL to that annotation.
        val query = "SELECT deduplicationKey FROM messages WHERE radioId >= upper(?) AND radioId <= lower(?) AND radioId = ? COLLATE NOCASE AND deduplicationKey IS NOT NULL"
        assertRadioRange(query, arrayOf(radio.value.toString(), radio.value.toString(), radio.value.toString()))
        assertEquals(listOf("target"), db.messages().simulatorDeduplicationKeys(radio.value))
    }

    @Test fun cancellationFenceDoesNotReplaceFatalBuilderErrors() = runTest {
        val fixture = fixture()
        val repository = repositoryContext()
        val fatal = AssertionError("control: fatal builder error after exact TX cancellation")
        val before = rawRows()
        try {
            var job: Job? = null
            val observed = assertFailsWith<AssertionError> {
                repository.write("fatalDeferredBuilder") {
                    job = currentCoroutineContext()[Job]
                    seedDeferredSimulatorConversation(plan(family(fixture, build = {
                        assertNotNull(job).cancel()
                        throw fatal
                    })), 0, ChannelRepository(repository, RoomRepository(repository)))
                }
            }
            assertOriginalFailure(fatal, observed)
            assertEquals(before, rawRows())
        } finally {
            repository.close()
        }
    }
}
