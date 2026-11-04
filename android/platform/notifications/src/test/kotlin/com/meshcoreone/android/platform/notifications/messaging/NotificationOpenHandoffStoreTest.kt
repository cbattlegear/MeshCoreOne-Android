// AndroidOnly: WP-401 Verify indexed durable notification-open identity and idempotent acknowledgement.
package com.meshcoreone.android.platform.notifications.messaging

import androidx.test.core.app.ApplicationProvider
import android.database.sqlite.SQLiteDatabase
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.notifications.NotificationPayload
import com.meshcoreone.android.core.model.RadioId
import java.io.File
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37])
class NotificationOpenHandoffStoreTest {
    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val databaseFile = File(context.cacheDir, "notification-open-${UUID.randomUUID()}.db")
    private val store get() = NotificationOpenHandoffStore(databaseFile)
    private val contact = EntityKey(RadioId(UUID(0, 1)), UUID(0, 2))
    private val direct = NotificationPayload.DirectMessage(contact, UUID(0, 3))

    @After
    fun clearDatabaseFiles() {
        listOf(databaseFile, File("${databaseFile.path}-wal"), File("${databaseFile.path}-shm"))
            .filter(File::exists)
            .forEach { check(it.delete()) { "Could not remove notification test database file" } }
    }

    @Before
    fun initializeFreshTestStore() = runTest {
        clearDatabaseFiles()
        assertTrue(store.initializeNew() is NotificationOpenHandoffStore.InventoryResult.Snapshot)
    }

    @Test
    fun identityAndPayloadSurviveStoreRecreationAndAcknowledgementIsIdempotent() = runTest {
        val firstPost = UUID.randomUUID().toString()
        val secondPost = UUID.randomUUID().toString()
        val first = (store.stage(firstPost, direct) as NotificationOpenHandoffStore.StageResult.Staged).handoff
        val second = store.stage(
            secondPost,
            NotificationPayload.ChannelMessage(contact.radioId, 7u, UUID(0, 4)),
        ).let { (it as NotificationOpenHandoffStore.StageResult.Staged).handoff }

        assertEquals(first.identity.producerStoreId, second.identity.producerStoreId)
        assertNotEquals(first.identity.deliveryId, second.identity.deliveryId)
        assertTrue(second.identity.sequence > first.identity.sequence)
        assertEquals(
            first.identity,
            (store.stage(firstPost, direct) as NotificationOpenHandoffStore.StageResult.AlreadyPending)
                .handoff.identity,
        )

        val reopened = store
        val snapshot = (reopened.beginInventory() as NotificationOpenHandoffStore.InventoryResult.Snapshot).value
        val firstPage = reopened.readInventoryPage(snapshot, null, 1)
            as NotificationOpenHandoffStore.InventoryResult.Page
        assertFalse(firstPage.complete)
        assertEquals(listOf(first.identity), firstPage.entries.map(NotificationOpenInventoryEntry::identity))
        val secondPage = reopened.readInventoryPage(snapshot, firstPage.nextAfterSequence, 1)
            as NotificationOpenHandoffStore.InventoryResult.Page
        assertTrue(secondPage.complete)
        assertEquals(listOf(second.identity), secondPage.entries.map(NotificationOpenInventoryEntry::identity))

        assertEquals(
            NotificationOpenHandoffStore.AcknowledgeResult.Acknowledged,
            reopened.acknowledge(first.identity),
        )
        assertEquals(
            NotificationOpenHandoffStore.AcknowledgeResult.AlreadyAcknowledged,
            store.acknowledge(first.identity),
        )
        assertEquals(
            NotificationOpenHandoffStore.StageResult.AlreadyConsumed(first.identity),
            reopened.stage(firstPost, direct),
        )
        val consumedSnapshot =
            (reopened.beginInventory() as NotificationOpenHandoffStore.InventoryResult.Snapshot).value
        val all = reopened.readInventoryPage(consumedSnapshot, null, 10)
            as NotificationOpenHandoffStore.InventoryResult.Page
        assertTrue(all.entries.first().consumed)
        assertEquals(null, all.entries.first().payload)
        assertEquals(first.identity, all.entries.first().identity)
    }

    @Test
    fun duplicateConcurrentStagesConvergeOnOnePersistentDeliveryIdentity() = runTest {
        val post = UUID.randomUUID().toString()
        val otherStore = NotificationOpenHandoffStore(databaseFile)
        val first = async { store.stage(post, direct) }
        val second = async { otherStore.stage(post, direct) }
        val results = listOf(first.await(), second.await())
        assertEquals(
            setOf(
                NotificationOpenHandoffStore.StageResult.Staged::class,
                NotificationOpenHandoffStore.StageResult.AlreadyPending::class,
            ),
            results.map { it::class }.toSet(),
        )
        val identities = results.map {
            when (it) {
                is NotificationOpenHandoffStore.StageResult.Staged -> it.handoff.identity
                is NotificationOpenHandoffStore.StageResult.AlreadyPending -> it.handoff.identity
                else -> error("Unexpected stage result $it")
            }
        }
        assertEquals(1, identities.toSet().size)
    }

    @Test
    fun pendingCapacityDoesNotEvictOrConsumeRetainedIdentities() = runTest {
        val consumedPost = UUID.randomUUID().toString()
        val consumed = (store.stage(consumedPost, direct) as NotificationOpenHandoffStore.StageResult.Staged).handoff
        assertEquals(NotificationOpenHandoffStore.AcknowledgeResult.Acknowledged, store.acknowledge(consumed.identity))
        repeat(32) {
            assertTrue(store.stage(UUID.randomUUID().toString(), direct) is
                NotificationOpenHandoffStore.StageResult.Staged)
        }
        val rejected = store.stage(UUID.randomUUID().toString(), direct)
        assertEquals(
            NotificationOpenHandoffStore.StageResult.Rejected(
                NotificationOpenHandoffStore.Failure.PENDING_CAPACITY,
            ),
            rejected,
        )
        assertEquals(
            NotificationOpenHandoffStore.StageResult.AlreadyConsumed(consumed.identity),
            store.stage(consumedPost, direct),
        )
    }

    @Test
    fun conflictingPayloadAndWrongIdentityDoNotAcknowledgeAnotherDelivery() = runTest {
        val post = UUID.randomUUID().toString()
        val staged = (store.stage(post, direct) as NotificationOpenHandoffStore.StageResult.Staged).handoff
        assertEquals(
            NotificationOpenHandoffStore.StageResult.Rejected(
                NotificationOpenHandoffStore.Failure.POST_IDENTITY_CONFLICT,
            ),
            store.stage(post, NotificationPayload.LowBattery(50L)),
        )
        val wrongSequence = staged.identity.copy(sequence = staged.identity.sequence + 1)
        assertEquals(
            NotificationOpenHandoffStore.AcknowledgeResult.IdentityMismatch,
            store.acknowledge(wrongSequence),
        )
        assertEquals(
            NotificationOpenHandoffStore.AcknowledgeResult.NotFound,
            store.acknowledge(staged.identity.copy(postId = UUID.randomUUID().toString())),
        )
        val snapshot = (store.beginInventory() as NotificationOpenHandoffStore.InventoryResult.Snapshot).value
        val page = store.readInventoryPage(snapshot, null, 10) as NotificationOpenHandoffStore.InventoryResult.Page
        assertEquals(staged.identity, page.entries.single().identity)
        assertFalse(page.entries.single().consumed)
    }

    @Test
    fun writesInvalidateAnEarlierPagedInventory() = runTest {
        store.stage(UUID.randomUUID().toString(), direct)
        val snapshot = (store.beginInventory() as NotificationOpenHandoffStore.InventoryResult.Snapshot).value
        store.stage(UUID.randomUUID().toString(), direct)
        assertEquals(
            NotificationOpenHandoffStore.InventoryResult.SnapshotInvalidated,
            store.readInventoryPage(snapshot, null, 1),
        )
    }

    @Test
    fun acknowledgementInvalidatesSnapshotAndRetainsConflictingPostEvidence() = runTest {
        val post = UUID.randomUUID().toString()
        val staged = (store.stage(post, direct) as NotificationOpenHandoffStore.StageResult.Staged).handoff
        val snapshot = (store.beginInventory() as NotificationOpenHandoffStore.InventoryResult.Snapshot).value
        assertEquals(NotificationOpenHandoffStore.AcknowledgeResult.Acknowledged, store.acknowledge(staged.identity))
        assertEquals(
            NotificationOpenHandoffStore.InventoryResult.SnapshotInvalidated,
            store.readInventoryPage(snapshot, null, 10),
        )
        assertEquals(
            NotificationOpenHandoffStore.StageResult.Rejected(NotificationOpenHandoffStore.Failure.POST_IDENTITY_CONFLICT),
            store.stage(post, NotificationPayload.LowBattery(50)),
        )
        assertEquals(
            NotificationOpenHandoffStore.AcknowledgeResult.IdentityMismatch,
            store.acknowledge(staged.identity.copy(producerStoreId = UUID.randomUUID())),
        )
    }

    @Test
    fun allPayloadFamiliesRoundTripWithoutMessageText() = runTest {
        val payloads = listOf(
            direct,
            NotificationPayload.ChannelMessage(contact.radioId, 255u, UUID(0, 4)),
            NotificationPayload.RoomMessage("room-\u00e9", contact, UUID(0, 5)),
            NotificationPayload.NewContact(contact),
            NotificationPayload.Reaction(UUID(0, 6), contact, null, null),
            NotificationPayload.Reaction(UUID(0, 7), null, 0u, contact.radioId),
            NotificationPayload.Reaction(UUID(0, 8), null, null, null),
            NotificationPayload.LowBattery(Long.MAX_VALUE),
            NotificationPayload.QuickReplyFailed(contact),
            NotificationPayload.ChannelQuickReplyFailed(contact.radioId, 255u),
        )
        payloads.forEach { payload ->
            val post = UUID.randomUUID().toString()
            val staged = (store.stage(post, payload) as NotificationOpenHandoffStore.StageResult.Staged).handoff
            assertEquals(payload, staged.payload)
            assertEquals(
                payload,
                (store.stage(post, payload) as NotificationOpenHandoffStore.StageResult.AlreadyPending).handoff.payload,
            )
        }
        val snapshot = (store.beginInventory() as NotificationOpenHandoffStore.InventoryResult.Snapshot).value
        val page = store.readInventoryPage(snapshot, null, 32) as NotificationOpenHandoffStore.InventoryResult.Page
        assertTrue(page.complete)
        assertEquals(payloads, page.entries.map { it.payload })
    }

    @Test
    fun missingRetainedRowMakesInventoryCorruptRatherThanComplete() = runTest {
        val first = (store.stage(UUID.randomUUID().toString(), direct)
            as NotificationOpenHandoffStore.StageResult.Staged).handoff
        assertTrue(store.stage(UUID.randomUUID().toString(), direct) is NotificationOpenHandoffStore.StageResult.Staged)
        SQLiteDatabase.openDatabase(databaseFile.path, null, SQLiteDatabase.OPEN_READWRITE).use {
            it.execSQL("DELETE FROM notification_open_records WHERE sequence = ?", arrayOf(first.identity.sequence))
        }
        val snapshot = (store.beginInventory() as NotificationOpenHandoffStore.InventoryResult.Snapshot).value
        assertEquals(
            NotificationOpenHandoffStore.InventoryResult.Failed(NotificationOpenHandoffStore.Failure.CORRUPT_STORAGE),
            store.readInventoryPage(snapshot, null, 32),
        )
    }

    @Test
    fun invalidInputNeverCreatesAnAcceptedRow() = runTest {
        assertEquals(
            NotificationOpenHandoffStore.StageResult.Rejected(NotificationOpenHandoffStore.Failure.INVALID_POST_ID),
            store.stage("not-a-post", direct),
        )
        assertEquals(
            NotificationOpenHandoffStore.StageResult.Rejected(NotificationOpenHandoffStore.Failure.INVALID_PAYLOAD),
            store.stage(UUID.randomUUID().toString(), NotificationPayload.LowBattery(Long.MIN_VALUE)),
        )
        val snapshot = (store.beginInventory() as NotificationOpenHandoffStore.InventoryResult.Snapshot).value
        val page = store.readInventoryPage(snapshot, null, 32) as NotificationOpenHandoffStore.InventoryResult.Page
        assertTrue(page.complete)
        assertTrue(page.entries.isEmpty())
    }

    @Test
    fun corruptDatabaseIsReportedWithoutRecreatingOrErasingIt() = runTest {
        val bytes = "not-a-sqlite-database".toByteArray()
        databaseFile.writeBytes(bytes)
        val result = store.stage(UUID.randomUUID().toString(), direct)
        assertEquals(
            NotificationOpenHandoffStore.StageResult.Rejected(
                NotificationOpenHandoffStore.Failure.CORRUPT_STORAGE,
            ),
            result,
        )
        assertEquals(bytes.toList(), databaseFile.readBytes().toList())
    }

    @Test
    fun unavailableStorageIsTypedAndDoesNotReportAcceptance() = runTest {
        clearDatabaseFiles()
        check(databaseFile.mkdir())
        val result = store.stage(UUID.randomUUID().toString(), direct)
        assertEquals(
            NotificationOpenHandoffStore.StageResult.Rejected(
                NotificationOpenHandoffStore.Failure.STORAGE_UNAVAILABLE,
            ),
            result,
        )
    }

    @Test
    fun missingInitializedStoreIsNotRecreatedByStageReadOrAck() = runTest {
        val staged = (store.stage(UUID.randomUUID().toString(), direct)
            as NotificationOpenHandoffStore.StageResult.Staged).handoff
        clearDatabaseFiles()
        assertEquals(
            NotificationOpenHandoffStore.StageResult.Rejected(NotificationOpenHandoffStore.Failure.MISSING_STORAGE),
            store.stage(staged.identity.postId, direct),
        )
        assertEquals(
            NotificationOpenHandoffStore.InventoryResult.Failed(NotificationOpenHandoffStore.Failure.MISSING_STORAGE),
            store.beginInventory(),
        )
        assertEquals(
            NotificationOpenHandoffStore.AcknowledgeResult.Failed(NotificationOpenHandoffStore.Failure.MISSING_STORAGE),
            store.acknowledge(staged.identity),
        )
        assertFalse(databaseFile.exists())
    }
}
