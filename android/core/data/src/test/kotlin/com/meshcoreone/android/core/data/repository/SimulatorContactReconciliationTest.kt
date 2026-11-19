// AndroidOnly: WP-202 #605 real Room controls for the simulator contact safety adaptation.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.DatabaseValueException
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.canonicalString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.Test

class SimulatorContactReconciliationTest : RepositoryTest() {
    private lateinit var seedContext: RoomRepositoryContext
    private lateinit var seeds: ContactRepository
    private val seed = contact()
    private val personalized = seed.copy(
        name = "User name",
        flags = 0x31u,
        outPathLength = 2u,
        outPath = Bytes.of(3, 4),
        lastAdvertTimestamp = 42u,
        latitude = 12.5,
        longitude = -43.25,
        lastModified = 70u,
        lastHeardTimestamp = 90u,
        nickname = "",
        isBlocked = true,
        isMuted = true,
        isFavorite = true,
        lastMessageDate = AT.minusNanos(123),
        unreadCount = 17,
        unreadMentionCount = 3,
        ocvPreset = "custom",
        customOCVArrayString = "",
        avatarImageData = Bytes.of(1, 2, 3),
    )

    @Before fun openSeedRepository() {
        seedContext = RoomRepositoryContext(db, clock, owner, RepositoryIssueReporter { _, error -> issues += error })
        seeds = ContactRepository(seedContext, DiscoveredNodeRepository(seedContext))
    }

    @After fun closeSeedRepository() {
        runBlocking { seedContext.close() }
    }

    @Test fun firstSeedInsertsTheExactRawFixtureAndRetainsFixedMessageReferences() = runTest(scheduler) {
        seeds.saveSimulatorContact(seed)
        val history = message(text = "Fixed-ID simulator conversation")
        store.saveMessage(history)

        assertEquals(seed.toEntity(), db.contacts().byId(RADIO_A.value, seed.id))
        repeat(3) { seeds.saveSimulatorContact(seed.copy(name = "Later fixture", lastModified = 999u)) }
        assertEquals(seed.copy(lastModified = 999u).toEntity(), db.contacts().byId(RADIO_A.value, seed.id))
        assertEquals(listOf(history), store.fetchMessages(entity(), 10, 0).toList())
        assertEquals(1, db.contacts().forPublicKey(RADIO_A.value, seed.publicKey).size)
    }

    @Test fun aRawFixtureWithNoLastHeardUsesTheExistingStorageMapping() = runTest(scheduler) {
        val raw = seed.copy(lastHeardTimestamp = null)

        seeds.saveSimulatorContact(raw)

        assertEquals(raw.toEntity(), db.contacts().byId(RADIO_A.value, seed.id))
        assertEquals(0u, store.fetchContact(entity())?.lastHeardTimestamp)
    }

    @Test fun repeatedSeedPreservesTheEntirePersonalizedRowAndHistoryWithoutWrites() = runTest(scheduler) {
        store.saveContact(personalized)
        val history = message(text = "Retained history")
        store.saveMessage(history)
        val before = assertNotNull(db.contacts().byId(RADIO_A.value, seed.id))
        rejectContactWrites()

        repeat(3) {
            seeds.saveSimulatorContact(seed.copy(lastHeardTimestamp = 999u))
            assertEquals(before, db.contacts().byId(RADIO_A.value, seed.id))
            assertEquals(personalized, store.fetchContact(entity()))
            assertEquals(history, store.fetchMessage(entity(id = history.id)))
        }
    }

    @Test fun seedPreservesExplicitClearsInsteadOfReapplyingFixtureDefaults() = runTest(scheduler) {
        val cleared = seed.copy(nickname = null, ocvPreset = null, customOCVArrayString = null, avatarImageData = null)
        store.saveContact(cleared)

        seeds.saveSimulatorContact(personalized)

        assertEquals(cleared.copy(lastModified = personalized.lastModified).toEntity(), db.contacts().byId(RADIO_A.value, seed.id))
    }

    @Test fun realMuteAndFavoriteEditsSurviveLaterSeed() = runTest(scheduler) {
        seeds.saveSimulatorContact(seed)
        store.setContactMuted(entity(), true)
        store.updateContactFavorite(entity(), seed.publicKey, true)
        val edited = assertNotNull(store.fetchContact(entity()))
        assertTrue(edited.isMuted)
        assertTrue(edited.isFavorite)
        rejectContactWrites()

        seeds.saveSimulatorContact(seed)

        assertEquals(edited, store.fetchContact(entity()))
    }

    @Test fun laterSeedRefreshesActualRecencyAndPruneInputsWithoutRevivingAFavorite() = runTest(scheduler) {
        val first = seed.copy(
            lastModified = AT.epochSecond.toUInt(),
            lastAdvertTimestamp = AT.epochSecond.toUInt(),
            lastHeardTimestamp = null,
            isFavorite = true,
        )
        seeds.saveSimulatorContact(first)
        store.updateContactFavorite(entity(), first.publicKey, false)
        val original = assertNotNull(store.fetchContact(entity()))
        assertFalse(original.isFavorite)
        assertEquals(AT, original.recencyDate)
        assertFalse(original.matchesStaleNodePrune(AT.minusSeconds(14 * 86_400L).epochSecond.toUInt()))
        clock.now = AT.plusSeconds(30 * 86_400L)
        val laterFixture = first.copy(
            lastModified = clock.now.epochSecond.toUInt(),
            lastAdvertTimestamp = clock.now.epochSecond.toUInt(),
        )
        seeds.saveSimulatorContact(laterFixture)

        val retained = assertNotNull(store.fetchContact(entity()))
        val cutoff = clock.now.minusSeconds(14 * 86_400L).epochSecond.toUInt()
        assertEquals(original.copy(lastModified = laterFixture.lastModified), retained)
        assertEquals(clock.now, retained.recencyDate)
        assertEquals(laterFixture.lastModified, retained.recencyTimestamp)
        assertTrue(original.matchesStaleNodePrune(cutoff))
        assertFalse(retained.matchesStaleNodePrune(cutoff))
        assertFalse(retained.isFavorite)
        rejectContactWrites()
        seeds.saveSimulatorContact(laterFixture)
    }

    @Test fun aUserEditCommittedWhileSeedWaitsIsNotReplacedByItsStaleFixture() = runBlocking<Unit> {
        seeds.saveSimulatorContact(seed)
        val edited = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        try {
            withTimeout(10_000) {
                val writer = launch(Dispatchers.Default) {
                    seedContext.write("testConcurrentContactEdit") {
                        database.contacts().upsert(personalized.toEntity())
                        save()
                        edited.complete(Unit)
                        release.await()
                    }
                }
                edited.await()
                val reseed = async(start = CoroutineStart.UNDISPATCHED) {
                    seeds.saveSimulatorContact(seed.copy(lastModified = 999u))
                }
                try {
                    assertFalse(reseed.isCompleted)
                } finally {
                    release.complete(Unit)
                }
                writer.join()
                reseed.await()
            }
        } finally {
            release.complete(Unit)
        }

        assertEquals(personalized.copy(lastModified = 999u), store.fetchContact(entity()))
    }

    @Test fun advancingFreshnessPreservesEveryOtherFieldAndBothRadiosHistory() = runTest(scheduler) {
        val foreign = personalized.copy(radioId = RADIO_B)
        store.saveContact(personalized)
        store.saveContact(foreign)
        val history = message(text = "Preserved message state")
        val foreignHistory = message(radio = RADIO_B, text = "Other radio history")
        store.saveMessage(history)
        store.saveMessage(foreignHistory)
        val sqlite = db.openHelper.writableDatabase
        val columns = sqlite.query("PRAGMA table_info(contacts)").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue("lastModified" in columns && columns.size > 1)
        val protectedColumns = columns.filterNot { it == "lastModified" }.joinToString(",") { "\"$it\"" }
        sqlite.execSQL(
            "CREATE TRIGGER reject_contact_field_replacement BEFORE UPDATE OF $protectedColumns ON contacts " +
                "BEGIN SELECT RAISE(ABORT, 'only lastModified may be targeted'); END",
        )
        for (operation in listOf("INSERT", "DELETE")) {
            sqlite.execSQL(
                "CREATE TRIGGER reject_contact_freshness_${operation.lowercase()} BEFORE $operation ON contacts " +
                    "BEGIN SELECT RAISE(ABORT, 'existing contact must not be replaced'); END",
            )
        }

        seeds.saveSimulatorContact(seed.copy(lastModified = UInt.MAX_VALUE, avatarImageData = Bytes.of(9)))

        assertEquals(personalized.copy(lastModified = UInt.MAX_VALUE), store.fetchContact(entity()))
        assertEquals(foreign, store.fetchContact(entity(RADIO_B)))
        assertEquals(history, store.fetchMessage(entity(id = history.id)))
        assertEquals(foreignHistory, store.fetchMessage(entity(RADIO_B, foreignHistory.id)))
    }

    @Test fun newerExistingFreshnessSurvivesEqualFixturesAndClockRewindWithoutWrites() = runTest(scheduler) {
        val existing = personalized.copy(lastModified = AT.plusSeconds(30 * 86_400L).epochSecond.toUInt())
        store.saveContact(existing)
        rejectContactWrites()
        val cases = listOf(
            AT.plusSeconds(60 * 86_400L) to AT.epochSecond.toUInt(),
            AT to existing.lastModified,
            AT.minusSeconds(30 * 86_400L) to AT.minusSeconds(30 * 86_400L).epochSecond.toUInt(),
        )
        for ((wallClock, fixtureTimestamp) in cases) {
            clock.now = wallClock
            seeds.saveSimulatorContact(seed.copy(lastModified = fixtureTimestamp))
            assertEquals(existing, store.fetchContact(entity()))
        }
    }

    @Test fun anIntentionallyOldFixtureAdvancesOnlyToItsOwnTimestampNotTheWallClock() = runTest(scheduler) {
        val existing = seed.copy(lastModified = AT.minusSeconds(90 * 86_400L).epochSecond.toUInt())
        store.saveContact(existing)
        clock.now = AT.plusSeconds(30 * 86_400L)
        val fixture = seed.copy(lastModified = AT.minusSeconds(60 * 86_400L).epochSecond.toUInt())

        seeds.saveSimulatorContact(fixture)

        val retained = assertNotNull(store.fetchContact(entity()))
        assertEquals(existing.copy(lastModified = fixture.lastModified), retained)
        assertEquals(AT.minusSeconds(60 * 86_400L), retained.recencyDate)
        assertTrue(retained.matchesStaleNodePrune(clock.now.minusSeconds(14 * 86_400L).epochSecond.toUInt()))
    }

    @Test fun ignoredFreshnessUpdateRollsBackTriggerSideEffectsAndCanRetry() = runTest(scheduler) {
        val sentinel = personalized.copy(id = UUID.randomUUID(), publicKey = key(0x55))
        store.saveContact(personalized)
        store.saveContact(sentinel)
        val before = db.contacts().backupAll().toSet()
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER ignore_contact_freshness BEFORE UPDATE ON contacts " +
                "WHEN NEW.id = '${seed.id.canonicalString()}' " +
                "BEGIN UPDATE contacts SET nickname = 'must roll back' " +
                "WHERE radioId = NEW.radioId AND id = '${sentinel.id.canonicalString()}'; " +
                "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM contacts WHERE radioId = NEW.radioId " +
                "AND id = '${sentinel.id.canonicalString()}' AND nickname = 'must roll back') " +
                "THEN RAISE(ABORT, 'sentinel was not updated') END; SELECT RAISE(IGNORE); END",
        )
        try {
            val error = assertFailsWith<PersistenceStoreException> {
                seeds.saveSimulatorContact(seed.copy(lastModified = 999u))
            }
            assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorContact"), error.error)
            assertNull(error.cause)
            assertEquals(before, db.contacts().backupAll().toSet())
        } finally {
            sqlite.execSQL("DROP TRIGGER ignore_contact_freshness")
        }

        seeds.saveSimulatorContact(seed.copy(lastModified = 999u))

        assertEquals(personalized.copy(lastModified = 999u), store.fetchContact(entity()))
        assertEquals(sentinel, store.fetchContact(entity(id = sentinel.id)))
    }

    @Test fun freshnessUpdateReadbackRollsBackChangedFieldsAndIdentity() = runTest(scheduler) {
        store.saveContact(personalized)
        val before = db.contacts().backupAll().toSet()
        val sqlite = db.openHelper.writableDatabase
        val mutations = listOf(
            "nickname = 'must roll back'",
            "lastModified = 1000",
            "publicKey = zeroblob(32)",
            "id = '${UUID.randomUUID().canonicalString()}'",
        )
        for (mutation in mutations) {
            sqlite.execSQL(
                "CREATE TRIGGER mutate_contact_freshness AFTER UPDATE ON contacts " +
                    "WHEN NEW.id = '${seed.id.canonicalString()}' AND OLD.lastModified < 999 AND NEW.lastModified = 999 " +
                    "BEGIN UPDATE contacts SET $mutation WHERE radioId = NEW.radioId AND id = NEW.id; " +
                    "SELECT CASE WHEN changes() != 1 THEN RAISE(ABORT, 'target was not mutated') END; END",
            )
            try {
                val error = assertFailsWith<PersistenceStoreException> {
                    seeds.saveSimulatorContact(seed.copy(lastModified = 999u))
                }
                assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorContact"), error.error)
                assertNull(error.cause)
                assertEquals(before, db.contacts().backupAll().toSet())
            } finally {
                sqlite.execSQL("DROP TRIGGER mutate_contact_freshness")
            }
        }
    }

    @Test fun freshnessUpdateTriggersCannotCommitASecondPublicKeyOccupant() = runTest(scheduler) {
        val sentinel = personalized.copy(id = UUID.randomUUID(), publicKey = key(0x55))
        store.saveContact(personalized)
        store.saveContact(sentinel)
        val before = db.contacts().backupAll().toSet()
        val sqlite = db.openHelper.writableDatabase
        for (timing in listOf("BEFORE", "AFTER")) {
            sqlite.execSQL(
                "CREATE TRIGGER duplicate_contact_freshness $timing UPDATE ON contacts " +
                    "WHEN NEW.id = '${seed.id.canonicalString()}' AND OLD.lastModified < 999 AND NEW.lastModified = 999 " +
                    "BEGIN UPDATE contacts SET publicKey = NEW.publicKey " +
                    "WHERE radioId = NEW.radioId AND id = '${sentinel.id.canonicalString()}'; " +
                    "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM contacts WHERE radioId = NEW.radioId " +
                    "AND id = '${sentinel.id.canonicalString()}' AND publicKey = NEW.publicKey) " +
                    "THEN RAISE(ABORT, 'sentinel was not rebound') END; END",
            )
            try {
                val error = assertFailsWith<PersistenceStoreException> {
                    seeds.saveSimulatorContact(seed.copy(lastModified = 999u))
                }
                assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorContact"), error.error)
                assertNull(error.cause)
                assertEquals(before, db.contacts().backupAll().toSet())
            } finally {
                sqlite.execSQL("DROP TRIGGER duplicate_contact_freshness")
            }
        }
    }

    @Test fun sameIdWithDifferentKeyCannotOverwriteEitherIdentityOrConversation() = runTest(scheduler) {
        val replacement = personalized.copy(publicKey = key(0x55))
        val alternate = personalized.copy(id = UUID.randomUUID())
        store.saveContact(replacement)
        store.saveContact(alternate)
        val history = message(text = "Replacement identity history")
        store.saveMessage(history)
        val before = db.contacts().backupAll().toSet()
        rejectContactWrites()

        for (timestamp in listOf(0u, 999u)) {
            assertInvalidData { seeds.saveSimulatorContact(seed.copy(lastModified = timestamp)) }
        }

        assertEquals(before, db.contacts().backupAll().toSet())
        assertEquals(history, store.fetchMessage(entity(id = history.id)))
    }

    @Test fun duplicatePublicKeyRowsAreRefusedEvenWhenOneHasTheFixedId() = runTest(scheduler) {
        store.saveContact(personalized)
        store.saveContact(personalized.copy(id = UUID.randomUUID()))
        val before = db.contacts().backupAll().toSet()
        rejectContactWrites()

        for (timestamp in listOf(0u, 999u)) {
            assertInvalidData { seeds.saveSimulatorContact(seed.copy(lastModified = timestamp)) }
        }

        assertEquals(before, db.contacts().backupAll().toSet())
    }

    @Test fun duplicateAlternateRowsAreNotArbitrarilySelectedOrDuplicated() = runTest(scheduler) {
        repeat(2) { store.saveContact(personalized.copy(id = UUID.randomUUID())) }
        val before = db.contacts().backupAll().toSet()
        rejectContactWrites()

        for (timestamp in listOf(0u, 999u)) {
            assertInvalidData { seeds.saveSimulatorContact(seed.copy(lastModified = timestamp)) }
        }

        assertEquals(before, db.contacts().backupAll().toSet())
        assertNull(store.fetchContact(entity()))
    }

    @Test fun uniqueRestoredAlternateIdRetainsItsActualParentAndHistoryWithoutCreatingTheFixtureId() = runTest(scheduler) {
        val restored = personalized.copy(id = UUID.randomUUID())
        val history = message(contactID = restored.id, text = "Restored conversation")
        store.saveContact(restored)
        store.saveMessage(history)
        for (timestamp in listOf(0u, 999u)) {
            seedContext.write("testAlternateContactSeed") {
                val plan = prepareSimulatorContact(seed.copy(lastModified = timestamp))
                assertEquals(restored.id, plan.existing?.id)
                assertNull(plan.expectedInsert)
                val result = assertIs<SimulatorContactSeedResult.Existing>(applySimulatorContact(plan))
                assertEquals(restored.copy(lastModified = maxOf(restored.lastModified, timestamp)).toEntity(), result.parent)
                assertEquals(timestamp > restored.lastModified, result.mutated)
                assertFalse(saves)
                if (result.mutated) save()
            }
        }
        rejectContactWrites()
        seeds.saveSimulatorContact(seed.copy(lastModified = 999u))

        assertNull(store.fetchContact(entity()))
        assertEquals(restored.copy(lastModified = 999u), store.fetchContact(entity(id = restored.id)))
        assertEquals(listOf(history), store.fetchMessages(entity(id = restored.id), 10, 0).toList())
        assertEquals(emptyList(), store.fetchMessages(entity(), 10, 0).toList())
        assertEquals(1, db.contacts().forPublicKey(RADIO_A.value, seed.publicKey).size)
    }

    @Test fun sameIdsAndKeysOnAnotherRadioDoNotBlockOrChangeThisSeed() = runTest(scheduler) {
        val foreign = personalized.copy(radioId = RADIO_B)
        store.saveContact(foreign)

        seeds.saveSimulatorContact(seed)

        assertEquals(seed, store.fetchContact(entity()))
        assertEquals(foreign, store.fetchContact(entity(RADIO_B)))
    }

    @Test fun malformedPublicKeysFailBeforeAnyContactWrite() = runTest(scheduler) {
        rejectContactWrites()
        for (size in listOf(0, 31, 33)) {
            assertInvalidData { seeds.saveSimulatorContact(seed.copy(publicKey = Bytes(ByteArray(size)))) }
        }
        assertEquals(emptyList(), db.contacts().backupAll())
    }

    @Test fun stalePrefetchedAvatarCannotReplaceAnExistingNewerAvatar() = runTest(scheduler) {
        store.saveContact(personalized)
        val stale = seed.copy(avatarImageData = Bytes.of(9))
        rejectContactWrites()

        seeds.saveSimulatorContact(stale)

        assertEquals(personalized, store.fetchContact(entity()))
    }

    @Test fun deletionAfterAvatarPrefetchCannotResurrectTheDeletedAvatar() = runTest(scheduler) {
        store.saveContact(personalized)
        val prefetched = seed.copy(avatarImageData = store.fetchContact(entity())?.avatarImageData)
        db.contacts().delete(RADIO_A.value, seed.id)
        rejectContactWrites()

        assertInvalidData { seeds.saveSimulatorContact(prefetched) }

        assertNull(store.fetchContact(entity()))
    }

    @Test fun aRawFixtureCanSeedAfterDeletionWithoutReusingPrefetchedUserData() = runTest(scheduler) {
        store.saveContact(personalized)
        db.contacts().delete(RADIO_A.value, seed.id)

        seeds.saveSimulatorContact(seed)

        assertEquals(seed, store.fetchContact(entity()))
        assertNull(store.fetchContact(entity())?.avatarImageData)
    }

    @Test fun failedInsertRollsBackInsteadOfReportingASeededContact() = runTest(scheduler) {
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_contact_seed AFTER INSERT ON contacts " +
                "BEGIN SELECT RAISE(ABORT, 'contact seed failure'); END",
        )

        assertFailsWith<PersistenceStoreException> { seeds.saveSimulatorContact(seed) }

        assertNull(store.fetchContact(entity()))
    }

    @Test fun ignoredInsertRollsBackTriggerSideEffectsAndReportsSaveFailure() = runTest(scheduler) {
        val other = personalized.copy(id = UUID.randomUUID(), publicKey = key(0x55))
        store.saveContact(other)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER ignore_contact_seed BEFORE INSERT ON contacts WHEN NEW.id = '${seed.id.canonicalString()}' " +
                "BEGIN UPDATE contacts SET nickname = 'must roll back' WHERE id = '${other.id.canonicalString()}'; " +
                "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM contacts WHERE radioId = NEW.radioId " +
                "AND id = '${other.id.canonicalString()}' AND nickname = 'must roll back') " +
                "THEN RAISE(ABORT, 'sentinel was not updated') END; " +
                "SELECT RAISE(IGNORE); END",
        )

        val error = assertFailsWith<PersistenceStoreException> { seeds.saveSimulatorContact(seed) }

        assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorContact"), error.error)
        assertNull(error.cause)
        assertNull(store.fetchContact(entity()))
        assertEquals(other, store.fetchContact(entity(id = other.id)))
    }

    @Test fun cancellationBeforeEntryDoesNotInsertAContact() = runTest(scheduler) {
        val cancelled = Job().apply { cancel() }

        assertFailsWith<CancellationException> {
            withContext(cancelled) { seeds.saveSimulatorContact(seed) }
        }

        assertEquals(emptyList(), db.contacts().backupAll())
    }

    @Test fun insertTriggersCannotCommitASecondPublicKeyOccupant() = runTest(scheduler) {
        val sentinel = personalized.copy(id = UUID.randomUUID(), publicKey = key(0x55))
        store.saveContact(sentinel)
        val before = db.contacts().backupAll().toSet()
        val sqlite = db.openHelper.writableDatabase
        for (timing in listOf("BEFORE", "AFTER")) {
            sqlite.execSQL(
                "CREATE TRIGGER duplicate_contact_seed $timing INSERT ON contacts " +
                    "WHEN NEW.id = '${seed.id.canonicalString()}' " +
                    "BEGIN UPDATE contacts SET publicKey = NEW.publicKey " +
                    "WHERE radioId = NEW.radioId AND id = '${sentinel.id.canonicalString()}'; " +
                    "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM contacts WHERE radioId = NEW.radioId " +
                    "AND id = '${sentinel.id.canonicalString()}' AND publicKey = NEW.publicKey) " +
                    "THEN RAISE(ABORT, 'sentinel was not rebound') END; END",
            )
            try {
                val error = assertFailsWith<PersistenceStoreException> { seeds.saveSimulatorContact(seed) }

                assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorContact"), error.error)
                assertNull(error.cause)
                assertEquals(before, db.contacts().backupAll().toSet())
                assertNull(store.fetchContact(entity()))
                assertEquals(sentinel, store.fetchContact(entity(id = sentinel.id)))
                assertEquals(emptyList(), db.contacts().forPublicKey(RADIO_A.value, seed.publicKey))
            } finally {
                sqlite.execSQL("DROP TRIGGER duplicate_contact_seed")
            }
        }
    }

    @Test fun ordinarySameIdSaveStillAppliesMutableMetadata() = runTest(scheduler) {
        store.saveContact(personalized)

        store.saveContact(seed)

        assertEquals(seed.copy(lastHeardTimestamp = personalized.lastHeardTimestamp), store.fetchContact(entity()))
    }

    @Test fun preparationIsReadOnlyForBothAbsentAndCompatibleExistingParents() = runTest(scheduler) {
        store.saveContact(personalized)
        val absent = seed.copy(id = UUID.randomUUID(), publicKey = key(0x55))
        val before = db.contacts().backupAll().toSet()
        rejectContactWrites()

        seedContext.write("testContactPreparation") {
            val existing = prepareSimulatorContact(seed.copy(lastModified = 999u))
            assertEquals(personalized.toEntity(), existing.existing)
            assertNull(existing.expectedInsert)
            val insertion = prepareSimulatorContact(absent)
            assertNull(insertion.existing)
            assertEquals(absent.toEntity(), insertion.expectedInsert)
            assertFalse(saves)
        }

        assertEquals(before, db.contacts().backupAll().toSet())
    }

    @Test fun applicationReturnsActualTypedParentsAndLeavesSaveToTheOuterTransaction() = runTest(scheduler) {
        seedContext.write("testCreatedContact") {
            val result = assertIs<SimulatorContactSeedResult.Created>(applySimulatorContact(prepareSimulatorContact(seed)))
            assertEquals(seed.toEntity(), result.parent)
            assertEquals(result.parent, database.contacts().byId(RADIO_A.value, seed.id))
            assertTrue(result.mutated)
            assertFalse(saves)
            save()
        }
        seedContext.write("testRefreshedContact") {
            val result = assertIs<SimulatorContactSeedResult.Existing>(
                applySimulatorContact(prepareSimulatorContact(seed.copy(lastModified = 999u))),
            )
            assertEquals(seed.copy(lastModified = 999u).toEntity(), result.parent)
            assertEquals(result.parent, database.contacts().byId(RADIO_A.value, seed.id))
            assertTrue(result.mutated)
            assertFalse(saves)
            save()
        }
        rejectContactWrites()
        seedContext.write("testUnchangedContact") {
            val result = assertIs<SimulatorContactSeedResult.Existing>(applySimulatorContact(prepareSimulatorContact(seed)))
            assertEquals(seed.copy(lastModified = 999u).toEntity(), result.parent)
            assertFalse(result.mutated)
            assertFalse(saves)
        }
    }

    @Test fun preparedContactMutationRollsBackWhenItsOuterTransactionFails() = runTest(scheduler) {
        for (existing in listOf(false, true)) {
            if (existing) store.saveContact(personalized)
            val before = db.contacts().backupAll().toSet()

            assertInvalidData {
                seedContext.write("testOuterContactRollback") {
                    val result = applySimulatorContact(prepareSimulatorContact(seed.copy(lastModified = 999u)))
                    assertTrue(result.mutated)
                    assertEquals(result.parent, database.contacts().byId(RADIO_A.value, seed.id))
                    assertFalse(saves)
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
            }

            assertEquals(before, db.contacts().backupAll().toSet())
        }
    }

    @Test fun preparedPlanCannotMoveAcrossTransactionsOrJobs() = runTest(scheduler) {
        lateinit var original: RepositoryTransaction
        val plan = seedContext.write("testPreparePlanScope") {
            original = this
            val prepared = prepareSimulatorContact(seed)
            assertInvalidData { coroutineScope { original.applySimulatorContact(prepared) } }
            prepared
        }

        assertInvalidData {
            seedContext.write("testDifferentTransaction") { applySimulatorContact(plan) }
        }
        assertInvalidData {
            seedContext.write("testOldTransactionInNewJob") { original.applySimulatorContact(plan) }
        }

        assertEquals(emptyList(), db.contacts().backupAll())
    }

    @Test fun preparedPlanRejectsInterveningParentOrKeyCohortChangesAndRollsThemBack() = runTest(scheduler) {
        store.saveContact(personalized)
        val before = db.contacts().backupAll().toSet()
        for (duplicate in listOf(false, true)) {
            val error = assertFailsWith<PersistenceStoreException> {
                seedContext.write("testChangedContactPreparation") {
                    val plan = prepareSimulatorContact(seed.copy(lastModified = 999u))
                    if (duplicate) database.contacts().insert(personalized.copy(id = UUID.randomUUID()).toEntity())
                    else database.contacts().upsert(personalized.copy(nickname = "intervening edit").toEntity())
                    applySimulatorContact(plan)
                }
            }

            assertEquals(
                if (duplicate) PersistenceStoreError.InvalidData else PersistenceStoreError.SaveFailed("saveSimulatorContact"),
                error.error,
            )
            assertEquals(before, db.contacts().backupAll().toSet())
        }
    }

    @Test fun outOfRangeStoredFreshnessIsReportedWithoutNormalizingOrWritingEitherRow() = runTest(scheduler) {
        val rows = listOf(-1L, UInt.MAX_VALUE.toLong() + 1L).flatMapIndexed { index, timestamp ->
            listOf(false, true).mapIndexed { variant, alternate ->
                val fixture = seed.copy(id = UUID.randomUUID(), publicKey = key(0x55 + index * 2 + variant))
                val actualId = if (alternate) UUID.randomUUID() else fixture.id
                val row = personalized.copy(id = actualId, publicKey = fixture.publicKey).toEntity().copy(lastModified = timestamp)
                db.contacts().insert(row)
                fixture to row
            }
        }
        val before = db.contacts().backupAll().toSet()
        rejectContactWrites()
        for ((fixture, row) in rows) {
            val error = assertFailsWith<PersistenceStoreException> {
                seeds.saveSimulatorContact(fixture.copy(lastModified = UInt.MAX_VALUE))
            }

            assertEquals(PersistenceStoreError.SaveFailed("Invalid stored contact.lastModified"), error.error)
            assertEquals("contact.lastModified", assertIs<DatabaseValueException>(error.cause).field)
            assertTrue(issues.last() === error)
            assertEquals(row, db.contacts().byId(RADIO_A.value, row.id))
            assertEquals(before, db.contacts().backupAll().toSet())
        }
    }

    @Test fun postconditionsRequireCanonicalIdLookupAfterInsertAndRefresh() = runTest(scheduler) {
        val fixture = seed.copy(id = UUID.fromString("abcdefab-1234-5678-90ab-abcdefabcdef"))
        val sqlite = db.openHelper.writableDatabase
        for (existing in listOf(false, true)) {
            if (existing) store.saveContact(fixture.copy(lastModified = 70u))
            val before = db.contacts().backupAll().toSet()
            val event = if (existing) "UPDATE" else "INSERT"
            sqlite.execSQL(
                "CREATE TRIGGER change_contact_id_case AFTER $event ON contacts " +
                    "WHEN NEW.id = '${fixture.id.canonicalString()}' " +
                    "BEGIN UPDATE contacts SET id = lower(NEW.id) WHERE radioId = NEW.radioId AND id = NEW.id; " +
                    "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM contacts WHERE radioId = NEW.radioId " +
                    "AND id = lower(NEW.id)) THEN RAISE(ABORT, 'ID case was not changed') END; END",
            )
            try {
                val error = assertFailsWith<PersistenceStoreException> {
                    seeds.saveSimulatorContact(fixture.copy(lastModified = 999u))
                }
                assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorContact"), error.error)
                assertNull(error.cause)
                assertEquals(before, db.contacts().backupAll().toSet())
                assertEquals(before.singleOrNull(), db.contacts().byId(RADIO_A.value, fixture.id))
            } finally {
                sqlite.execSQL("DROP TRIGGER change_contact_id_case")
            }
        }
    }

    @Test fun anExistingKeyRowWithANoncanonicalIdIsRefusedBeforeAnyDml() = runTest(scheduler) {
        val fixture = seed.copy(id = UUID.fromString("abcdefab-1234-5678-90ab-abcdefabcdef"))
        store.saveContact(fixture)
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL("UPDATE contacts SET id = lower(id)")
        val before = db.contacts().backupAll().toSet()
        assertNull(db.contacts().byId(RADIO_A.value, fixture.id))
        rejectContactWrites()

        assertInvalidData { seeds.saveSimulatorContact(fixture.copy(lastModified = 999u)) }

        assertEquals(before, db.contacts().backupAll().toSet())
        assertNull(db.contacts().byId(RADIO_A.value, fixture.id))
        sqlite.query("SELECT id FROM contacts").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(fixture.id.toString(), cursor.getString(0))
            assertFalse(cursor.moveToNext())
        }
    }

    @Test fun rawCaseFixtureIdWithAnotherKeyRefusesBeforeInsertOrAlternateIdRefresh() = runTest(scheduler) {
        val fixture = alphabeticFixture()
        for (alternate in listOf(false, true)) for (mixed in listOf(false, true)) {
            for (columns in listOf(listOf("id"), listOf("radioId"), listOf("id", "radioId"))) {
                if (alternate) db.contacts().insert(personalized.copy(
                    id = UUID.fromString("fedcbafe-2222-5678-90ab-abcdefabcdef"), radioId = fixture.radioId,
                ).toEntity())
                insertRawContactAlias(fixture.copy(id = UUID.randomUUID(), publicKey = key(0x66)), fixture.id, columns, mixed)
                assertNull(db.contacts().byId(fixture.radioId.value, fixture.id))
                val before = rawContacts()
                rejectContactWrites()
                try {
                    assertInvalidData { seeds.saveSimulatorContact(fixture.copy(lastModified = 999u)) }
                    assertEquals(before, rawContacts())
                } finally {
                    allowContactWrites()
                }
                db.openHelper.writableDatabase.execSQL("DELETE FROM contacts")
            }
        }
    }

    @Test fun rawCaseActualIdTwinsRefuseBeforeBothNoOpAndFreshnessMutation() = runTest(scheduler) {
        val fixture = alphabeticFixture()
        for (alternate in listOf(false, true)) for (timestamp in listOf(70u, 999u)) {
            for (columns in listOf(listOf("id"), listOf("radioId"), listOf("id", "radioId"))) {
                val actual = personalized.copy(
                    id = if (alternate) UUID.fromString("fedcbafe-2222-5678-90ab-abcdefabcdef") else fixture.id,
                    radioId = fixture.radioId,
                )
                db.contacts().insert(actual.toEntity())
                insertRawContactAlias(actual.copy(id = UUID.randomUUID(), publicKey = key(0x66)), actual.id, columns, true)
                assertEquals(actual.toEntity(), db.contacts().byId(fixture.radioId.value, actual.id))
                val before = rawContacts()
                rejectContactWrites()
                try {
                    assertInvalidData { seeds.saveSimulatorContact(fixture.copy(lastModified = timestamp)) }
                    assertEquals(before, rawContacts())
                } finally {
                    allowContactWrites()
                }
                db.openHelper.writableDatabase.execSQL("DELETE FROM contacts")
            }
        }
    }

    @Test fun rawCaseRadioKeyCohortCannotHideAnAlternateOrDuplicateContact() = runTest(scheduler) {
        val fixture = alphabeticFixture()
        for (existing in listOf(false, true)) for (mixed in listOf(false, true)) {
            if (existing) db.contacts().insert(personalized.copy(id = fixture.id, radioId = fixture.radioId).toEntity())
            insertRawContactAlias(
                fixture.copy(id = UUID.randomUUID()),
                UUID.fromString("fedcbafe-2222-5678-90ab-abcdefabcdef"), listOf("radioId"), mixed,
            )
            val before = rawContacts()
            rejectContactWrites()
            try {
                assertInvalidData { seeds.saveSimulatorContact(fixture.copy(lastModified = 999u)) }
                assertEquals(before, rawContacts())
            } finally {
                allowContactWrites()
            }
            db.openHelper.writableDatabase.execSQL("DELETE FROM contacts")
        }
    }

    @Test fun rawCaseIdentityAndKeyOnAnotherRadioDoNotBlockOrChangeThatPartition() = runTest(scheduler) {
        val fixture = alphabeticFixture()
        val foreignRadio = RadioId(UUID.fromString("fedcbafe-6666-2222-3333-abcdefabcdef"))
        for (mixed in listOf(false, true)) {
            insertRawContactAlias(
                fixture.copy(id = UUID.randomUUID(), radioId = foreignRadio), fixture.id,
                listOf("id", "radioId"), mixed,
            )
            val before = rawContacts(foreignRadio)

            seeds.saveSimulatorContact(fixture)

            assertEquals(fixture.toEntity(), db.contacts().byId(fixture.radioId.value, fixture.id))
            assertEquals(1L, db.contacts().countSimulatorIdentity(fixture.radioId.value, fixture.id))
            assertEquals(listOf(fixture.toEntity()), db.contacts().simulatorPublicKeyCohort(fixture.radioId.value, fixture.publicKey))
            assertEquals(before, rawContacts(foreignRadio))
            db.openHelper.writableDatabase.execSQL("DELETE FROM contacts")
        }
    }

    @Test fun preparedPlanRejectsInterveningRawFixtureAliasAndRollsItBackBeforeRetry() = runTest(scheduler) {
        val fixture = alphabeticFixture().copy(lastModified = 999u)
        for (existing in listOf(false, true)) {
            val actual = personalized.copy(
                id = UUID.fromString("fedcbafe-2222-5678-90ab-abcdefabcdef"), radioId = fixture.radioId,
            )
            if (existing) db.contacts().insert(actual.toEntity())
            val before = rawContacts()

            assertInvalidData {
                seedContext.write("testInterveningRawContactAlias") {
                    val plan = prepareSimulatorContact(fixture)
                    insertRawContactAlias(
                        fixture.copy(id = UUID.randomUUID(), publicKey = key(0x66)),
                        fixture.id, listOf("id", "radioId"), true,
                    )
                    applySimulatorContact(plan)
                }
            }

            assertEquals(before, rawContacts())
            assertNull(db.contacts().byId(fixture.radioId.value, fixture.id))
            seeds.saveSimulatorContact(fixture)
            val expected = if (existing) actual.copy(lastModified = 999u) else fixture
            assertEquals(expected.toEntity(), db.contacts().byId(fixture.radioId.value, expected.id))
            db.openHelper.writableDatabase.execSQL("DELETE FROM contacts")
        }
    }

    @Test fun lateLogicalIdentityAndKeyAliasesRollBackInsertOrRefreshAndCanBeRetried() = runTest(scheduler) {
        val fixture = alphabeticFixture().copy(lastModified = 999u)
        val sqlite = db.openHelper.writableDatabase
        for (mode in listOf("new", "fixed", "alternate")) for (kind in listOf("actual-id", "fixture-id", "key-radio")) {
            val actual = personalized.copy(
                id = if (mode == "alternate") UUID.fromString("fedcbafe-2222-5678-90ab-abcdefabcdef") else fixture.id,
                radioId = fixture.radioId,
            )
            if (mode != "new") db.contacts().insert(actual.toEntity())
            val sentinel = fixture.copy(
                id = UUID.fromString("bcdefabc-3333-5678-90ab-abcdefabcdef"), publicKey = key(0x66), nickname = "sentinel",
            )
            db.contacts().insert(sentinel.toEntity())
            val before = rawContacts()
            val event = if (mode == "new") "INSERT" else "UPDATE"
            val replacement = when (kind) {
                "actual-id" -> "id = lower(NEW.id)"
                "fixture-id" -> "id = lower('${fixture.id.canonicalString()}'), radioId = lower(NEW.radioId)"
                else -> "publicKey = NEW.publicKey, radioId = lower(NEW.radioId)"
            }
            sqlite.execSQL(
                "CREATE TRIGGER late_contact_alias AFTER $event ON contacts " +
                    "WHEN NEW.id = '${actual.id.canonicalString()}' AND NEW.lastModified = 999 " +
                    "BEGIN UPDATE contacts SET $replacement, nickname = 'must roll back' " +
                    "WHERE radioId = NEW.radioId AND id = '${sentinel.id.canonicalString()}'; " +
                    "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM contacts WHERE nickname = 'must roll back') " +
                    "THEN RAISE(ABORT, 'alias was not created') END; END",
            )
            try {
                val failure = assertFailsWith<PersistenceStoreException> { seeds.saveSimulatorContact(fixture) }
                assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorContact"), failure.error)
                assertNull(failure.cause)
                assertEquals(before, rawContacts())
            } finally {
                sqlite.execSQL("DROP TRIGGER late_contact_alias")
            }

            seeds.saveSimulatorContact(fixture)
            val expected = if (mode == "new") fixture else actual.copy(lastModified = 999u)
            assertEquals(expected.toEntity(), db.contacts().byId(fixture.radioId.value, expected.id))
            assertEquals(sentinel.toEntity(), db.contacts().byId(fixture.radioId.value, sentinel.id))
            assertEquals(1L, db.contacts().countSimulatorIdentity(fixture.radioId.value, expected.id))
            db.openHelper.writableDatabase.execSQL("DELETE FROM contacts")
        }
    }

    private fun alphabeticFixture() = seed.copy(
        id = UUID.fromString("abcdefab-1234-5678-90ab-abcdefabcdef"),
        radioId = RadioId(UUID.fromString("abcdefab-6666-2222-3333-abcdefabcdef")),
    )

    private suspend fun insertRawContactAlias(dto: ContactDTO, id: UUID, columns: List<String>, mixed: Boolean) {
        db.contacts().insert(dto.toEntity())
        fun stored(value: UUID, column: String): String {
            val canonical = "'${value.canonicalString()}'"
            return when {
                column !in columns -> canonical
                mixed -> "upper(substr($canonical, 1, 8)) || lower(substr($canonical, 9))"
                else -> "lower($canonical)"
            }
        }
        db.openHelper.writableDatabase.execSQL(
            "UPDATE contacts SET id = ${stored(id, "id")}, radioId = ${stored(dto.radioId.value, "radioId")} " +
                "WHERE radioId = '${dto.radioId.value.canonicalString()}' AND id = '${dto.id.canonicalString()}'",
        )
    }

    private fun rawContacts(radio: RadioId? = null): List<List<String?>> {
        val partition = radio?.let { " WHERE radioId = '${it.value.canonicalString()}' COLLATE NOCASE" }.orEmpty()
        return db.openHelper.writableDatabase.query("SELECT * FROM contacts$partition ORDER BY radioId, id").use { cursor ->
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

    private fun allowContactWrites() {
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_contact_${operation.lowercase()}")
        }
    }

    private suspend fun assertInvalidData(action: suspend () -> Unit) {
        val error = assertFailsWith<PersistenceStoreException> { action() }
        assertEquals(PersistenceStoreError.InvalidData, error.error)
    }

    private fun rejectContactWrites() {
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER reject_contact_${operation.lowercase()} BEFORE $operation ON contacts " +
                    "BEGIN SELECT RAISE(ABORT, 'unexpected contact write'); END",
            )
        }
    }
}
