// AndroidOnly: WP-217 Simulator-only atomic channel seeding and persisted-occupant safety.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.PersistenceStoreError
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreException
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ChannelFloodScope
import com.meshcoreone.android.core.model.NotificationLevel
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Test

class SimulatorChannelSeedTest : RepositoryTest() {
    private fun seed(
        index: UByte = 0u,
        id: UUID = UUID.fromString("00000000-0000-0000-0000-000000000101"),
        radio: RadioId = RADIO_A,
    ) = ChannelDTO(
        id = id,
        radioId = radio,
        index = index,
        name = "Fixture",
        secret = Bytes(ByteArray(16) { (it + 1).toByte() }),
        isEnabled = true,
        lastMessageDate = AT,
        unreadCount = 2,
        unreadMentionCount = 1,
        notificationLevel = NotificationLevel.ALL,
    )

    private fun personalized(dto: ChannelDTO) = dto.copy(
        name = "User's channel",
        lastMessageDate = AT.minusSeconds(123),
        unreadCount = 17,
        unreadMentionCount = 4,
        notificationLevel = NotificationLevel.MUTED,
        isFavorite = true,
    ).withFloodScope(ChannelFloodScope.Region("North, West"))

    private fun forbidChannelWrites() {
        val sqlite = db.openHelper.writableDatabase
        for (operation in listOf("INSERT", "UPDATE", "DELETE")) {
            sqlite.execSQL(
                "CREATE TRIGGER reject_simulator_channel_$operation BEFORE $operation ON channels " +
                    "BEGIN SELECT RAISE(ABORT, 'unexpected channel mutation'); END",
            )
        }
    }

    private fun rawChannelKeys(): List<Triple<String, String, Long>> =
        db.openHelper.writableDatabase.query(
            "SELECT radioId, id, `index` FROM channels ORDER BY radioId, id",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(Triple(cursor.getString(0), cursor.getString(1), cursor.getLong(2)))
            }
        }

    @Test fun firstSeedInsertsTheCompleteFixtureAndDoesNotTouchAnotherRadio() = runTest {
        val fixture = seed()
        val foreign = personalized(seed(radio = RADIO_B))
        store.saveChannel(foreign)

        store.saveSimulatorChannel(fixture)

        assertEquals(fixture, store.fetchChannel(entity(id = fixture.id)))
        assertEquals(foreign, store.fetchChannel(entity(RADIO_B, foreign.id)))
        assertEquals(listOf(fixture), store.fetchChannels(RADIO_A))
        assertEquals(listOf(foreign), store.fetchChannels(RADIO_B))
    }

    @Test fun simulatorChannelPlanIsTransactionBoundAndComposesWithOtherWrites() = runTest {
        val fixture = seed()
        val repository = RoomRepositoryContext(db, clock, owner, RepositoryIssueReporter { _, failure -> issues += failure })
        val channels = ChannelRepository(repository, RoomRepository(repository))
        try {
            lateinit var original: RepositoryTransaction
            val abandoned = repository.write("prepareSimulatorChannel") {
                original = this
                val plan = assertIs<ChannelRepository.SimulatorChannelSeedPlan.New>(
                    with(channels) { this@write.prepareSimulatorChannel(fixture) },
                )
                assertNull(database.channels().byId(RADIO_A.value, fixture.id))
                val wrongJob = assertFailsWith<PersistenceStoreException> {
                    coroutineScope { with(channels) { original.applySimulatorChannel(plan) } }
                }
                assertEquals(PersistenceStoreError.InvalidData, wrongJob.error)
                plan
            }
            val outsideTransaction = assertFailsWith<PersistenceStoreException> {
                with(channels) { original.applySimulatorChannel(abandoned) }
            }
            assertEquals(PersistenceStoreError.InvalidData, outsideTransaction.error)
            val wrongTransaction = assertFailsWith<PersistenceStoreException> {
                repository.write("applyWrongSimulatorChannelPlan") {
                    with(channels) { this@write.applySimulatorChannel(abandoned) }
                }
            }
            assertEquals(PersistenceStoreError.InvalidData, wrongTransaction.error)
            val escapedReceiver = assertFailsWith<PersistenceStoreException> {
                repository.write("applyEscapedSimulatorChannelPlan") {
                    with(channels) { original.applySimulatorChannel(abandoned) }
                }
            }
            assertEquals(PersistenceStoreError.InvalidData, escapedReceiver.error)
            assertNull(db.channels().byId(RADIO_A.value, fixture.id))

            val abort = assertFailsWith<PersistenceStoreException> {
                repository.write("abortSimulatorChannelBundle") {
                    val plan = assertIs<ChannelRepository.SimulatorChannelSeedPlan.New>(
                        with(channels) { this@write.prepareSimulatorChannel(fixture) },
                    )
                    assertEquals(fixture.toEntity(), with(channels) { this@write.applySimulatorChannel(plan) })
                    throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                }
            }
            assertEquals(PersistenceStoreError.InvalidData, abort.error)
            assertNull(db.channels().byId(RADIO_A.value, fixture.id))

            repository.write("commitSimulatorChannelBundle") {
                val plan = assertIs<ChannelRepository.SimulatorChannelSeedPlan.New>(
                    with(channels) { this@write.prepareSimulatorChannel(fixture) },
                )
                assertEquals(fixture.toEntity(), with(channels) { this@write.applySimulatorChannel(plan) })
            }
            assertEquals(fixture, store.fetchChannel(entity(id = fixture.id)))
            forbidChannelWrites()
            repository.write("retainSimulatorChannelBundle") {
                val plan = assertIs<ChannelRepository.SimulatorChannelSeedPlan.Existing>(
                    with(channels) { this@write.prepareSimulatorChannel(fixture) },
                )
                assertEquals(fixture.toEntity(), with(channels) { this@write.applySimulatorChannel(plan) })
            }
            assertEquals(fixture, store.fetchChannel(entity(id = fixture.id)))
        } finally {
            repository.close()
        }
    }

    @Test fun preparedPlansRejectInterveningOccupantChangesAndRollThemBack() = runTest {
        val fixture = seed(id = UUID.fromString("cafecafe-0000-0000-0000-000000000101"))
        val existing = personalized(fixture.copy(id = UUID.fromString("beefbeef-0000-0000-0000-000000000101")))
        db.channels().insert(existing.toEntity())
        val before = db.channels().backupAll().toSet()
        val rawBefore = rawChannelKeys()
        val repository = RoomRepositoryContext(db, clock, owner, RepositoryIssueReporter { _, failure -> issues += failure })
        val channels = ChannelRepository(repository, RoomRepository(repository))
        try {
            for (change in listOf(
                "metadata", "secret", "delete", "duplicate", "fixture-id-other-slot",
                "case-fixture-id", "case-occupant-id",
            )) {
                val failure = assertFailsWith<PersistenceStoreException> {
                    repository.write("changePreparedSimulatorChannel") {
                        val plan = assertIs<ChannelRepository.SimulatorChannelSeedPlan.Existing>(
                            with(channels) { this@write.prepareSimulatorChannel(fixture) },
                        )
                        when (change) {
                            "metadata" -> database.channels().upsert(existing.copy(name = "intervening edit").toEntity())
                            "secret" -> database.channels().upsert(
                                existing.copy(secret = Bytes(ByteArray(16) { 0x55 })).toEntity(),
                            )
                            "delete" -> database.channels().delete(RADIO_A.value, existing.id)
                            "duplicate" -> database.channels().insert(existing.copy(id = UUID.randomUUID()).toEntity())
                            "fixture-id-other-slot" -> database.channels().insert(fixture.copy(index = 1u).toEntity())
                            "case-fixture-id", "case-occupant-id" -> {
                                val temporary = UUID.randomUUID()
                                val logicalId = if (change == "case-fixture-id") fixture.id else existing.id
                                database.channels().insert(existing.copy(id = temporary, index = 1u).toEntity())
                                database.openHelper.writableDatabase.execSQL(
                                    "UPDATE channels SET id = lower('${logicalId.canonicalString()}') " +
                                        "WHERE radioId = '${RADIO_A.value.canonicalString()}' " +
                                        "AND id = '${temporary.canonicalString()}'",
                                )
                            }
                        }
                        with(channels) { this@write.applySimulatorChannel(plan) }
                    }
                }
                val expected = if (change == "metadata" || change == "delete") {
                    PersistenceStoreError.SaveFailed("saveSimulatorChannel")
                } else {
                    PersistenceStoreError.InvalidData
                }
                assertEquals(expected, failure.error, change)
                assertEquals(before, db.channels().backupAll().toSet(), change)
                assertEquals(rawBefore, rawChannelKeys(), change)
            }

            val absent = seed(index = 1u, id = UUID.randomUUID())
            val failure = assertFailsWith<PersistenceStoreException> {
                repository.write("occupyPreparedSimulatorChannel") {
                    val plan = assertIs<ChannelRepository.SimulatorChannelSeedPlan.New>(
                        with(channels) { this@write.prepareSimulatorChannel(absent) },
                    )
                    database.channels().insert(absent.toEntity())
                    with(channels) { this@write.applySimulatorChannel(plan) }
                }
            }
            assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorChannel"), failure.error)
            assertEquals(before, db.channels().backupAll().toSet())
            assertNull(db.channels().byId(RADIO_A.value, absent.id))
        } finally {
            repository.close()
        }
    }

    @Test fun noncanonicalExistingIdsRefuseWithoutChangingEitherSlot() = runTest {
        val sameId = seed(id = UUID.fromString("abcdefab-1234-5678-90ab-abcdefabcdef"))
        val alternate = seed(index = 1u, id = UUID.fromString("fedcbafe-1234-5678-90ab-abcdefabcdef"))
        val restored = personalized(alternate.copy(id = UUID.fromString("abcdefab-2222-5678-90ab-abcdefabcdef")))
        val foreign = personalized(seed(radio = RADIO_B))
        db.channels().insert(personalized(sameId).toEntity())
        db.channels().insert(restored.toEntity())
        db.channels().insert(foreign.toEntity())
        val sqlite = db.openHelper.writableDatabase
        for (id in listOf(sameId.id, restored.id)) {
            sqlite.execSQL(
                "UPDATE channels SET id = lower(id) WHERE radioId = '${RADIO_A.value.canonicalString()}' " +
                    "AND id = '${id.canonicalString()}'",
            )
            assertNull(db.channels().byId(RADIO_A.value, id))
        }
        val before = db.channels().backupAll().toSet()
        forbidChannelWrites()

        for (fixture in listOf(sameId, alternate)) {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorChannel(fixture) }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
        }

        assertEquals(before, db.channels().backupAll().toSet())
        assertNull(db.channels().byId(RADIO_A.value, sameId.id))
        assertNull(db.channels().byId(RADIO_A.value, restored.id))
        assertEquals(foreign, store.fetchChannel(entity(RADIO_B, foreign.id)))
    }

    @Test fun logicalIdentityAndSlotCollisionsRefuseAcrossMixedCaseRadioAndIds() = runTest {
        val sqlite = db.openHelper.writableDatabase
        val scenarios = listOf("fixture-id", "radio-slot", "radio-id", "alternate-id")
        for ((ordinal, scenario) in scenarios.withIndex()) {
            val suffix = (ordinal + 1).toString().padStart(12, '0')
            val radio = RadioId(UUID.fromString("abcdefab-0000-0000-0000-$suffix"))
            val foreignRadio = RadioId(UUID.fromString("12345678-0000-0000-0000-$suffix"))
            val fixture = seed(id = UUID.fromString("cafecafe-0000-0000-0000-$suffix"), radio = radio)
            val alternate = UUID.fromString("beefbeef-0000-0000-0000-$suffix")
            val dummy = UUID.fromString("deaddead-0000-0000-0000-$suffix")
            val foreign = personalized(fixture.copy(radioId = foreignRadio))
            db.channels().insert(foreign.toEntity())
            when (scenario) {
                "fixture-id" -> {
                    db.channels().insert(personalized(fixture.copy(index = 1u)).toEntity())
                    sqlite.execSQL(
                        "UPDATE channels SET id = 'CaFeCaFe-0000-0000-0000-$suffix' " +
                            "WHERE radioId = '${radio.value.canonicalString()}' AND id = '${fixture.id.canonicalString()}'",
                    )
                }
                "radio-slot" -> {
                    db.channels().insert(personalized(fixture.copy(id = alternate)).toEntity())
                    sqlite.execSQL(
                        "UPDATE channels SET radioId = 'AbCdEfAb-0000-0000-0000-$suffix' " +
                            "WHERE radioId = '${radio.value.canonicalString()}' AND id = '${alternate.canonicalString()}'",
                    )
                }
                "radio-id" -> {
                    db.channels().insert(personalized(fixture.copy(index = 1u)).toEntity())
                    sqlite.execSQL(
                        "UPDATE channels SET radioId = lower(radioId) " +
                            "WHERE radioId = '${radio.value.canonicalString()}' AND id = '${fixture.id.canonicalString()}'",
                    )
                }
                "alternate-id" -> {
                    db.channels().insert(personalized(fixture.copy(id = alternate)).toEntity())
                    db.channels().insert(personalized(fixture.copy(index = 1u, id = dummy)).toEntity())
                    sqlite.execSQL(
                        "UPDATE channels SET id = lower('${alternate.canonicalString()}') " +
                            "WHERE radioId = '${radio.value.canonicalString()}' AND id = '${dummy.canonicalString()}'",
                    )
                }
            }
            val before = db.channels().backupAll().toSet()
            val rawBefore = rawChannelKeys()

            val failure = assertFailsWith<PersistenceStoreException>(scenario) { store.saveSimulatorChannel(fixture) }

            assertEquals(PersistenceStoreError.InvalidData, failure.error, scenario)
            assertEquals(before, db.channels().backupAll().toSet(), scenario)
            assertEquals(rawBefore, rawChannelKeys(), scenario)
            assertEquals(foreign, store.fetchChannel(entity(foreignRadio, foreign.id)), scenario)
        }
    }

    @Test fun logicallyEqualIdsOnAnotherRadioDoNotBlockSeeding() = runTest {
        val fixture = seed(
            id = UUID.fromString("cafecafe-0000-0000-0000-000000000001"),
            radio = RadioId(UUID.fromString("abcdefab-0000-0000-0000-000000000001")),
        )
        val foreign = personalized(fixture.copy(
            radioId = RadioId(UUID.fromString("abcdefab-0000-0000-0000-000000000002")),
        ))
        db.channels().insert(foreign.toEntity())
        db.openHelper.writableDatabase.execSQL(
            "UPDATE channels SET radioId = lower(radioId), id = lower(id) " +
                "WHERE radioId = '${foreign.radioId.value.canonicalString()}'",
        )
        val rawForeign = rawChannelKeys()

        store.saveSimulatorChannel(fixture)

        assertEquals(fixture, store.fetchChannel(entity(fixture.radioId, fixture.id)))
        assertEquals(listOf(foreign.toEntity()), db.channels().simulatorIdentityCohort(foreign.radioId.value, foreign.id))
        assertEquals(rawForeign, rawChannelKeys().filter {
            it.first.equals(foreign.radioId.value.canonicalString(), ignoreCase = true)
        })
        assertEquals(2, db.channels().backupAll().size)
    }

    @Test fun lateLogicalIdDuplicatesRollBackAllChangesAndCanBeRetried() = runTest {
        val sqlite = db.openHelper.writableDatabase
        val columns = buildList {
            sqlite.query("PRAGMA table_info(`channels`)").use { cursor ->
                val name = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) add(cursor.getString(name))
            }
        }
        assertTrue(columns.isNotEmpty())
        for ((ordinal, changedColumn) in listOf("id", "radioId", "slot").withIndex()) {
            val suffix = (ordinal + 1).toString().padStart(12, '0')
            val radio = RadioId(UUID.fromString("abcdefab-1111-0000-0000-$suffix"))
            val fixture = seed(id = UUID.fromString("cafecafe-1111-0000-0000-$suffix"), radio = radio)
            val sentinel = personalized(seed(index = 2u, id = UUID.randomUUID(), radio = radio))
            val foreign = personalized(fixture.copy(radioId = RADIO_B))
            db.channels().insert(sentinel.toEntity())
            db.channels().insert(foreign.toEntity())
            val before = db.channels().backupAll().toSet()
            val rawBefore = rawChannelKeys()
            val insertedColumns = columns.joinToString { "`$it`" }
            val insertedValues = columns.joinToString { column ->
                when {
                    changedColumn == "slot" && column == "radioId" -> "lower(NEW.`radioId`)"
                    changedColumn == "slot" && column == "id" -> "'BEEFBEEF-1111-0000-0000-$suffix'"
                    column == changedColumn -> "lower(NEW.`$column`)"
                    column == "index" && changedColumn != "slot" -> "NEW.`index` + 1"
                    else -> "NEW.`$column`"
                }
            }
            val duplicatePredicate = if (changedColumn == "slot") {
                "radioId COLLATE NOCASE = NEW.radioId AND `index` = NEW.`index`"
            } else {
                "radioId COLLATE NOCASE = NEW.radioId AND id COLLATE NOCASE = NEW.id"
            }
            sqlite.execSQL(
                "CREATE TRIGGER duplicate_simulator_channel AFTER INSERT ON channels " +
                    "WHEN NEW.radioId = '${radio.value.canonicalString()}' " +
                    "AND NEW.id = '${fixture.id.canonicalString()}' BEGIN " +
                    "INSERT INTO channels ($insertedColumns) VALUES ($insertedValues); " +
                    "UPDATE channels SET name = 'must roll back' WHERE radioId = NEW.radioId " +
                    "AND id = '${sentinel.id.canonicalString()}'; " +
                    "SELECT CASE WHEN (SELECT COUNT(*) FROM channels WHERE $duplicatePredicate) != 2 " +
                    "THEN RAISE(ABORT, 'logical duplicate not inserted') END; END",
            )
            try {
                val failure = assertFailsWith<PersistenceStoreException>(changedColumn) {
                    store.saveSimulatorChannel(fixture)
                }
                assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorChannel"), failure.error, changedColumn)
                assertIs<IllegalStateException>(failure.cause, changedColumn)
                assertEquals("Simulator channel insert did not persist the expected row", failure.cause?.message)
                assertEquals(before, db.channels().backupAll().toSet(), changedColumn)
                assertEquals(rawBefore, rawChannelKeys(), changedColumn)
                assertNull(db.channels().byId(radio.value, fixture.id))
                assertEquals(sentinel, store.fetchChannel(entity(radio, sentinel.id)))
                assertEquals(foreign, store.fetchChannel(entity(RADIO_B, foreign.id)))
            } finally {
                sqlite.execSQL("DROP TRIGGER duplicate_simulator_channel")
            }

            store.saveSimulatorChannel(fixture)
            assertEquals(fixture, store.fetchChannel(entity(radio, fixture.id)))
            assertEquals(sentinel, store.fetchChannel(entity(radio, sentinel.id)))
        }
    }

    @Test fun noncanonicalInsertedIdRollsBackTriggerChangesAndCanBeRetried() = runTest {
        val fixture = seed(id = UUID.fromString("abcdefab-1234-5678-90ab-abcdefabcdef"))
        val sentinel = personalized(seed(index = 1u, id = UUID.randomUUID()))
        store.saveChannel(sentinel)
        val before = db.channels().backupAll().toSet()
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER alter_simulator_channel_id AFTER INSERT ON channels " +
                "WHEN NEW.id = '${fixture.id.canonicalString()}' " +
                "BEGIN UPDATE channels SET id = lower(NEW.id) WHERE radioId = NEW.radioId AND id = NEW.id; " +
                "UPDATE channels SET name = 'must roll back' WHERE radioId = NEW.radioId " +
                "AND id = '${sentinel.id.canonicalString()}'; " +
                "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM channels WHERE radioId = NEW.radioId " +
                "AND id = lower(NEW.id)) THEN RAISE(ABORT, 'ID case was not changed') END; END",
        )
        try {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorChannel(fixture) }
            assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorChannel"), failure.error)
            assertEquals("Simulator channel insert did not persist the expected row", failure.cause?.message)
            assertEquals(before, db.channels().backupAll().toSet())
            assertNull(db.channels().byId(RADIO_A.value, fixture.id))
            assertTrue(db.channels().forIndex(RADIO_A.value, fixture.index.toLong()).isEmpty())
            assertEquals(sentinel, store.fetchChannel(entity(id = sentinel.id)))
        } finally {
            sqlite.execSQL("DROP TRIGGER alter_simulator_channel_id")
        }

        store.saveSimulatorChannel(fixture)
        assertEquals(fixture, store.fetchChannel(entity(id = fixture.id)))
        assertEquals(sentinel, store.fetchChannel(entity(id = sentinel.id)))
    }

    @Test fun sameIdReplayIsAWholeRowNoOpIncludingLegacyStorageFields() = runTest {
        val fixture = seed()
        val saved = personalized(fixture)
        db.channels().insert(saved.toEntity().copy(notificationLevelRawValue = -1, legacyIsMuted = true))
        val before = assertNotNull(db.channels().byId(RADIO_A.value, fixture.id))
        assertEquals(saved, store.fetchChannel(entity(id = fixture.id)))
        forbidChannelWrites()

        repeat(3) { store.saveSimulatorChannel(fixture) }

        assertEquals(before, db.channels().byId(RADIO_A.value, fixture.id))
        assertEquals(saved, store.fetchChannel(entity(id = fixture.id)))
    }

    @Test fun remintedMatchingOccupantAndItsHistoryAreNotReplacedOrRewritten() = runTest {
        val fixture = seed()
        val restored = personalized(fixture.copy(id = UUID.randomUUID()))
        val foreign = personalized(seed(radio = RADIO_B))
        val history = message(contactID = null, channelIndex = fixture.index, text = "restored history")
        db.channels().insert(restored.toEntity())
        db.channels().insert(foreign.toEntity())
        store.saveMessage(history)
        val persistedHistory = assertNotNull(store.fetchMessage(entity(id = history.id)))
        val before = db.channels().backupAll().toSet()
        forbidChannelWrites()

        repeat(3) { store.saveSimulatorChannel(fixture) }

        assertEquals(before, db.channels().backupAll().toSet())
        assertEquals(restored, store.fetchChannel(entity(id = restored.id)))
        assertNull(store.fetchChannel(entity(id = fixture.id)))
        assertEquals(foreign, store.fetchChannel(entity(RADIO_B, foreign.id)))
        assertEquals(persistedHistory, store.fetchMessage(entity(id = history.id)))
    }

    @Test fun mismatchedSecretsAndEnabledStatesRefuseSameAndRemintedIdsWithoutMutation() = runTest {
        val fixtures = (0..3).map { index ->
            seed(index.toUByte(), UUID(0, 0x200L + index))
        }
        val occupants = fixtures.mapIndexed { index, fixture ->
            personalized(fixture.copy(
                id = if (index >= 2) UUID(0, 0x300L + index) else fixture.id,
                secret = if (index % 2 == 0) Bytes(ByteArray(16) { 0x7F }) else fixture.secret,
                isEnabled = index % 2 == 0,
            ))
        }
        occupants.forEach { db.channels().insert(it.toEntity()) }
        val history = message(contactID = null, channelIndex = fixtures.first().index, text = "keep mismatch history")
        store.saveMessage(history)
        val before = db.channels().backupAll().toSet()
        val persistedHistory = assertNotNull(store.fetchMessage(entity(id = history.id)))
        forbidChannelWrites()

        for (fixture in fixtures) {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorChannel(fixture) }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
        }

        assertEquals(before, db.channels().backupAll().toSet())
        assertEquals(persistedHistory, store.fetchMessage(entity(id = history.id)))
    }

    @Test fun ambiguousOccupantsAndASeedIdInAnotherSlotRefuseWithoutMutation() = runTest {
        val ambiguous = seed(index = 1u)
        val first = personalized(ambiguous)
        val second = personalized(ambiguous.copy(id = UUID.randomUUID()))
        val movedSeed = seed(index = 2u, id = UUID.randomUUID())
        val moved = personalized(movedSeed.copy(index = 3u))
        val target = personalized(movedSeed.copy(id = UUID.randomUUID()))
        val foreign = personalized(seed(index = 1u, radio = RADIO_B))
        for (channel in listOf(first, second, moved, target, foreign)) db.channels().insert(channel.toEntity())
        val history = message(contactID = null, channelIndex = 1u, text = "keep ambiguous history")
        store.saveMessage(history)
        val before = db.channels().backupAll().toSet()
        val persistedHistory = assertNotNull(store.fetchMessage(entity(id = history.id)))
        forbidChannelWrites()

        for (fixture in listOf(ambiguous, movedSeed)) {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorChannel(fixture) }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
        }

        assertEquals(before, db.channels().backupAll().toSet())
        assertEquals(persistedHistory, store.fetchMessage(entity(id = history.id)))
        assertEquals(foreign, store.fetchChannel(entity(RADIO_B, foreign.id)))
    }

    @Test fun ordinarySameIdSaveStillUpdatesTheExistingDto() = runTest {
        val original = seed()
        val changed = personalized(original.copy(secret = Bytes(ByteArray(16) { 0x55 }), isEnabled = false))
        store.saveChannel(original)

        store.saveChannel(changed)

        assertEquals(changed, store.fetchChannel(entity(id = original.id)))
    }

    @Test fun ignoredInsertRollsBackTriggerSideEffectsAndCanBeRetried() = runTest {
        val fixture = seed()
        val sentinel = personalized(seed(index = 1u, id = UUID.randomUUID()))
        store.saveChannel(sentinel)
        val before = db.channels().backupAll().toSet()
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER ignore_simulator_channel BEFORE INSERT ON channels WHEN NEW.`index` = 0 " +
                "BEGIN UPDATE channels SET isEnabled = 0 WHERE radioId = NEW.radioId AND `index` = 1; " +
                "SELECT CASE WHEN NOT EXISTS (SELECT 1 FROM channels WHERE radioId = NEW.radioId " +
                "AND `index` = 1 AND isEnabled = 0) THEN RAISE(ABORT, 'sentinel was not updated') END; " +
                "SELECT RAISE(IGNORE); END",
        )

        try {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorChannel(fixture) }
            assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorChannel"), failure.error)
            assertEquals("Simulator channel insert did not persist the expected row", failure.cause?.message)
            assertEquals(before, db.channels().backupAll().toSet())
            assertNull(store.fetchChannel(entity(id = fixture.id)))
            assertEquals(sentinel, store.fetchChannel(entity(id = sentinel.id)))
        } finally {
            sqlite.execSQL("DROP TRIGGER ignore_simulator_channel")
        }

        store.saveSimulatorChannel(fixture)
        assertEquals(fixture, store.fetchChannel(entity(id = fixture.id)))
        assertEquals(sentinel, store.fetchChannel(entity(id = sentinel.id)))
    }

    @Test fun rolledBackFirstSeedLeavesTheSlotEmptyAndPreservesHistory() = runTest {
        val fixture = seed()
        val history = message(contactID = null, channelIndex = fixture.index, text = "keep rollback history")
        store.saveMessage(history)
        val persistedHistory = assertNotNull(store.fetchMessage(entity(id = history.id)))
        val pending = rx()
        store.saveRxLogEntry(pending)
        val sqlite = db.openHelper.writableDatabase
        sqlite.execSQL(
            "CREATE TRIGGER reject_seed_flush BEFORE INSERT ON rx_log_entries " +
                "WHEN EXISTS (SELECT 1 FROM channels WHERE radioId = NEW.radioId AND `index` = 0) " +
                "BEGIN SELECT RAISE(ABORT, 'seed rollback after insert'); END",
        )

        try {
            val failure = assertFailsWith<PersistenceStoreException> { store.saveSimulatorChannel(fixture) }
            assertEquals(PersistenceStoreError.SaveFailed("saveSimulatorChannel"), failure.error)
            assertTrue(failure.cause?.message?.contains("seed rollback after insert") == true)
            assertNull(store.fetchChannel(entity(id = fixture.id)))
            assertTrue(store.fetchChannels(RADIO_A).isEmpty())
            assertEquals(persistedHistory, store.fetchMessage(entity(id = history.id)))
            assertEquals(0L, db.rxLogs().count(RADIO_A.value))
            assertEquals(pending.id, store.fetchRxLogEntries(RADIO_A).single().id)
        } finally {
            sqlite.execSQL("DROP TRIGGER reject_seed_flush")
        }
        store.flushPendingRxLogEntries()
        assertEquals(1L, db.rxLogs().count(RADIO_A.value))
    }

    @Test fun aCommittedConcurrentOccupantWinsBeforeTheSeedCanInsert() = runBlocking<Unit> {
        val fixture = seed()
        val saved = personalized(fixture)
        val inserted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val context = RoomRepositoryContext(db, clock, owner, RepositoryIssueReporter { _, failure -> issues += failure })
        val channels = ChannelRepository(context, RoomRepository(context))
        try {
            withTimeout(10_000) {
                val writer = async(Dispatchers.Default) {
                    context.write("testUserChannelUpdate") {
                        database.channels().insert(saved.toEntity())
                        save()
                        inserted.complete(Unit)
                        release.await()
                    }
                }
                inserted.await()
                val seeding = async(start = CoroutineStart.UNDISPATCHED) { channels.saveSimulatorChannel(fixture) }
                try {
                    assertFalse(seeding.isCompleted)
                } finally {
                    release.complete(Unit)
                }
                writer.await()
                seeding.await()
            }
        } finally {
            release.complete(Unit)
            context.close()
        }

        assertEquals(saved, store.fetchChannel(entity(id = fixture.id)))
        assertEquals(listOf(saved), store.fetchChannels(RADIO_A))
    }

    @Test fun concurrentFirstSeedAndRealDtoUpdateAlwaysRetainTheUserUpdate() = runBlocking {
        withTimeout(20_000) {
            repeat(24) { index ->
                val fixture = seed(index.toUByte(), UUID(0, 0x400L + index))
                val saved = personalized(fixture)
                val start = CompletableDeferred<Unit>()
                val seeding = async(Dispatchers.IO) {
                    start.await()
                    store.saveSimulatorChannel(fixture)
                }
                val update = async(Dispatchers.IO) {
                    start.await()
                    store.saveChannel(saved)
                }
                start.complete(Unit)
                awaitAll(seeding, update)
                assertEquals(saved, store.fetchChannel(entity(id = fixture.id)))
            }
        }
        assertEquals(24, store.fetchChannels(RADIO_A).size)
        assertTrue(store.fetchChannels(RADIO_B).isEmpty())
    }
}
