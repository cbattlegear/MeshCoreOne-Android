// AndroidOnly: WP-202 Copy-only recovery listing of unknown and replaced channel occupants through actual Room.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.toEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelDraftRecoveryPersistenceTest : RepositoryTest() {
    private fun info(byte: Int = 1) = ChannelInfo(5u, "channel", Bytes(ByteArray(16) { byte.toByte() }))

    private fun draft(
        number: Long, occupant: UUID? = null, status: MessageStatus = MessageStatus.FAILED,
    ) = message(
        id = UUID(0, number), contactID = null, channelIndex = 5u,
        text = "preserved draft $number", createdAt = AT.plusNanos(number), status = status,
    ).copy(channelOccupantID = occupant)

    @Test fun originAndDestinationPredicatesPrecedePagingAndKeepDeterministicCreationOrder() = runTest {
        store.saveChannel(RADIO_A, info())
        val old = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        store.saveChannel(RADIO_A, info(2))
        val current = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        val candidates = listOf(
            draft(1, old, MessageStatus.PENDING),
            draft(2, null, MessageStatus.SENDING),
            draft(3, old, MessageStatus.RETRYING),
            draft(4),
            draft(5, old).copy(createdAt = AT.plusNanos(4)),
        )
        val excluded = listOf(
            draft(100, current),
            draft(101, old, MessageStatus.SENT),
            draft(102, null, MessageStatus.DELIVERED),
            draft(103).copy(direction = MessageDirection.INCOMING),
            draft(104).copy(contactID = CONTACT_A, channelIndex = null),
            draft(105).copy(contactID = CONTACT_A),
            draft(106).copy(channelIndex = null),
            draft(107).copy(radioId = RADIO_B),
        )
        for (row in candidates + excluded) store.saveMessage(row)
        val expected = listOf(candidates[3], candidates[4], candidates[2], candidates[1], candidates[0])
        val recovery: ChannelDraftRecoveryPersisting = store
        assertEquals(expected, recovery.fetchRecoverableChannelDrafts(RADIO_A, 50).toList())
        for ((offset, row) in expected.withIndex()) {
            assertEquals(listOf(row), recovery.fetchRecoverableChannelDrafts(RADIO_A, 1, offset.toLong()).toList())
        }
        assertTrue(recovery.fetchRecoverableChannelDrafts(RADIO_A, 1, 5).isEmpty())
        assertTrue(recovery.fetchRecoverableChannelDrafts(RADIO_A, 0).isEmpty())
        assertEquals(listOf(excluded.last()), recovery.fetchRecoverableChannelDrafts(RADIO_B).toList())
        for (row in candidates + excluded) {
            assertEquals(row, store.fetchMessage(EntityKey(row.radioId, row.id)))
        }
    }

    @Test fun pendingEvidenceIsRadioScopedAndListingDoesNotRetireOrRewriteIt() = runTest {
        val retained = draft(1, status = MessageStatus.SENT)
        val foreignPendingOnly = draft(2, status = MessageStatus.SENT)
        val deliveredWithPending = draft(3, status = MessageStatus.DELIVERED)
        val directPendingOnly = draft(4, status = MessageStatus.SENT)
        for (row in listOf(retained, foreignPendingOnly, deliveredWithPending, directPendingOnly)) store.saveMessage(row)
        val queued = listOf(
            pending(messageID = retained.id),
            pending(messageID = deliveredWithPending.id),
            pending(radio = RADIO_B, messageID = foreignPendingOnly.id),
            pending(messageID = directPendingOnly.id),
        ).map {
            it.copy(
                kind = if (it.messageID == directPendingOnly.id) PendingSendKind.DM else PendingSendKind.CHANNEL,
                contactID = if (it.messageID == directPendingOnly.id) CONTACT_A else null,
                channelIndex = if (it.messageID == directPendingOnly.id) null else 5u,
            )
        }
        // Raw persisted legacy evidence cannot be manufactured by the guarded enqueue API.
        for (row in queued) db.pendingSends().insert(row.toEntity())
        val before = store.fetchPendingSends(RADIO_A)
        assertEquals(listOf(retained), store.fetchRecoverableChannelDrafts(RADIO_A).toList())
        assertEquals(listOf(retained), store.fetchRecoverableChannelDrafts(RADIO_A, 1, 0).toList())
        assertTrue(store.fetchRecoverableChannelDrafts(RADIO_A, 1, 1).isEmpty())
        assertEquals(before, store.fetchPendingSends(RADIO_A))
        assertEquals(retained, store.fetchMessage(EntityKey(RADIO_A, retained.id)))
        assertEquals(deliveredWithPending, store.fetchMessage(EntityKey(RADIO_A, deliveredWithPending.id)))
    }

    @Test fun unresolvedDisabledMissingAndDuplicateAuthorityNeverHidesRetainedDrafts() = runTest {
        store.saveChannel(RADIO_A, info())
        val occupant = assertNotNull(store.fetchChannelOccupantID(RADIO_A, 5u))
        val row = draft(1, occupant)
        store.saveMessage(row)
        val channel = assertNotNull(store.fetchChannel(RADIO_A, 5u)).toEntity()
        val state = assertNotNull(db.channelOccupants().stateForIndex(RADIO_A.value, 5))
        assertTrue(store.fetchRecoverableChannelDrafts(RADIO_A).isEmpty())

        db.channelOccupants().upsert(state.copy(unresolved = true))
        assertEquals(listOf(row), store.fetchRecoverableChannelDrafts(RADIO_A).toList())
        db.channelOccupants().upsert(state)
        assertTrue(store.fetchRecoverableChannelDrafts(RADIO_A).isEmpty())
        db.channels().upsert(channel.copy(isEnabled = false))
        assertEquals(listOf(row), store.fetchRecoverableChannelDrafts(RADIO_A).toList())
        db.channels().upsert(channel)
        val duplicate = channel.copy(id = UUID.randomUUID(), isEnabled = false)
        db.channels().insert(duplicate)
        assertEquals(listOf(row), store.fetchRecoverableChannelDrafts(RADIO_A).toList())
        db.channels().delete(RADIO_A.value, duplicate.id)
        assertTrue(store.fetchRecoverableChannelDrafts(RADIO_A).isEmpty())
        db.channels().delete(RADIO_A.value, channel.id)
        assertEquals(listOf(row), store.fetchRecoverableChannelDrafts(RADIO_A).toList())
        db.channels().insert(channel)
        db.channelOccupants().invalidate(RADIO_A.value, 5)
        assertEquals(listOf(row), store.fetchRecoverableChannelDrafts(RADIO_A).toList())
        assertEquals(row, store.fetchMessage(EntityKey(RADIO_A, row.id)))
    }

    @Test fun replacementAndReopenPreserveDraftsAndExplicitCopyCreatesOnlyNewSelectedIntent() = runTest {
        val name = "channel-recovery-${UUID.randomUUID()}.db"
        var disk = MeshCoreDatabase.open(context, name)
        var persisted = RoomPersistenceStore(disk, owner, clock)
        try {
            persisted.saveChannel(RADIO_A, info())
            val old = assertNotNull(persisted.fetchChannelOccupantID(RADIO_A, 5u))
            val original = draft(1, old, MessageStatus.PENDING)
            val legacy = draft(2, status = MessageStatus.PENDING)
            persisted.saveMessage(original)
            persisted.saveMessage(legacy)
            persisted.saveChannel(RADIO_A, info(2))
            val retained = listOf(legacy, original).map { it.copy(status = MessageStatus.FAILED) }
            val selected = ChannelHistoryScope.from(assertNotNull(persisted.fetchChannelOccupantSnapshot(RADIO_A, 5u)))
            persisted.close()
            disk.close()
            disk = MeshCoreDatabase.open(context, name)
            persisted = RoomPersistenceStore(disk, owner, clock)

            assertEquals(retained, persisted.fetchRecoverableChannelDrafts(RADIO_A).toList())
            assertTrue(persisted.fetchPendingSends(RADIO_A).isEmpty())
            val blocked = assertFailsWith<PersistenceStoreException> { persisted.fetchChannelHistory(selected) }
            assertEquals(ChannelHistoryConflict.UNBOUND_HISTORY, assertIs<ChannelHistoryRejected>(blocked.cause).reason)
            val copy = persisted.createOrReconcileChannelMessage(
                ChannelMessageCreation(UUID.randomUUID(), selected, legacy.text, legacy.textType),
                1_700_000_001u, AT.plusSeconds(1),
            )
            assertNotEquals(legacy.id, copy.id)
            assertEquals(selected.expectedOccupantID, copy.channelOccupantID)
            assertEquals(legacy.text, copy.text)
            assertEquals(retained, persisted.fetchRecoverableChannelDrafts(RADIO_A).toList())
            for (row in retained) assertEquals(row, persisted.fetchMessage(EntityKey(RADIO_A, row.id)))
            persisted.deleteMessage(EntityKey(RADIO_A, original.id))
            assertEquals(listOf(retained.first()), persisted.fetchRecoverableChannelDrafts(RADIO_A).toList())
        } finally {
            persisted.close()
            disk.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun invalidBoundsAndMalformedStoredDraftsFailExplicitlyInsteadOfPartialSuccess() = runTest {
        for ((limit, offset) in listOf(-1L to 0L, 1L to -1L)) {
            val failure = assertFailsWith<PersistenceStoreException> {
                store.fetchRecoverableChannelDrafts(RADIO_A, limit, offset)
            }
            assertEquals(PersistenceStoreError.InvalidData, failure.error)
            assertSame(failure, issues.last())
        }
        val valid = draft(1)
        val malformed = draft(2).toEntity().copy(textTypeRawValue = Long.MAX_VALUE)
        store.saveMessage(valid)
        db.messages().insert(malformed)
        val failure = assertFailsWith<PersistenceStoreException> { store.fetchRecoverableChannelDrafts(RADIO_A) }
        assertIs<PersistenceStoreError.FetchFailed>(failure.error)
        assertSame(failure, issues.last())
        assertEquals(malformed, db.messages().byId(RADIO_A.value, malformed.id))
        assertEquals(valid, store.fetchMessage(EntityKey(RADIO_A, valid.id)))
    }
}
