// AndroidOnly: WP-306 Real Room-to-App Chats read integration; Android-local controls, not iOS interoperability.
package com.meshcoreone.android.app.platform

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.app.container.ContainerHarness
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.backup.AppBackupCodec
import com.meshcoreone.android.core.data.backup.AppBackupService
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.datastore.MeshCoreStorage
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.list.UnresolvedMessagesStateHolder
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.Test

class UnresolvedMessagesFeatureTest : RoomProcessTest() {
    private val radio = RadioId(UUID(0, 559))
    private val prefix = Bytes.of(1, 2, 3, 4, 5, 6)
    private fun message(id: Long, selected: RadioId = radio) = MessageDTO(
        id = UUID(0, id), radioId = selected, text = "Original retained text \u4f60\u597d",
        timestamp = 42u, createdAt = Instant.ofEpochSecond(1_700_000_000),
        sortDate = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED, senderKeyPrefix = prefix,
    )

    @Test fun noContactsTwoRadiosAndSameContentOccurrencesReachTheRealFeatureWithoutMutation() = runTest {
        val first = store.saveIncomingDirectMessage(message(1), LocalDirectContactResolution.NotFound).message
        val second = store.saveIncomingDirectMessage(message(2), LocalDirectContactResolution.NotFound).message
        val foreign = message(3, RadioId(UUID(0, 560))).copy(text = "Other radio only")
        store.saveIncomingDirectMessage(foreign, LocalDirectContactResolution.NotFound)
        val h = ContainerHarness(this, store)
        try {
            val deps = h.container.chatsFeature.listFor(radio)
            assertTrue(deps.data.fetchConversations(radio).isEmpty())
            val holder = UnresolvedMessagesStateHolder(radio, deps.data, deps.diagnostics)
            holder.refresh()
            val rows = holder.state.value.messages
            assertEquals(setOf(first.id, second.id), rows.map { it.occurrenceID }.toSet())
            assertEquals(listOf(first.text, second.text), rows.map { it.text })
            assertTrue(rows.all { it.provenance == UnprovedDirectMessageProvenance.UNRESOLVED })
            val other = h.container.chatsFeature.listFor(foreign.radioId)
            assertEquals(listOf(foreign.text), other.data.fetchUnprovedDirectMessages(foreign.radioId, 50, 0).map { it.text })
            assertEquals(first, store.fetchMessage(EntityKey(radio, first.id)))
            assertEquals(second, store.fetchMessage(EntityKey(radio, second.id)))
            assertTrue(store.fetchContacts(radio).isEmpty())
            assertTrue(h.lifecycle.created.isEmpty(), "Reading cached text must not open a radio")
        } finally { h.container.close() }
    }

    @Test fun historicalAmbiguityAndLocalCandidateNeverBecomeSenderOrReplyAuthority() = runTest {
        val peer = ContactDTO(radioId = radio, publicKey = Bytes(prefix.toByteArray() + ByteArray(26) { 1 }),
            name = "Tentative local match", lastHeardTimestamp = null)
        val collision = peer.copy(id = UUID.randomUUID(), publicKey = Bytes(prefix.toByteArray() + ByteArray(26) { 2 }))
        store.saveContact(peer); store.saveContact(collision)
        val unresolved = store.saveIncomingDirectMessage(message(11), store.classifyLocalDirectContact(radio, prefix)).message
        store.deleteContact(EntityKey(radio, collision.id))
        val candidate = store.saveIncomingDirectMessage(message(12),
            store.classifyLocalDirectContact(radio, prefix)).message
        val legacy = message(13).copy(timestampCorrected = true)
        store.saveMessage(legacy)
        val h = ContainerHarness(this, store)
        try {
            val deps = h.container.chatsFeature.listFor(radio)
            val rows = deps.data.fetchUnprovedDirectMessages(radio, 50, 0)
            assertEquals(UnprovedDirectMessageProvenance.UNRESOLVED, rows.single { it.occurrenceID == unresolved.id }.provenance)
            assertEquals(UnprovedDirectMessageProvenance.LOCAL_CANDIDATE, rows.single { it.occurrenceID == candidate.id }.provenance)
            assertNull(store.fetchMessage(EntityKey(radio, unresolved.id))?.contactID)
            assertEquals(peer.id, store.fetchMessage(EntityKey(radio, candidate.id))?.contactID)
            val unknown = rows.single { it.occurrenceID == legacy.id }
            assertEquals(UnprovedDirectMessageProvenance.LEGACY_UNKNOWN, unknown.provenance)
            assertNull(unknown.wireTimestamp)
            assertNull(unknown.receivedAt)
            store.adoptOrphanedDirectMessages(radio, listOf(ContactIdentity(peer.id, peer.publicKey)).snapshot())
            assertNull(store.fetchMessage(EntityKey(radio, unresolved.id))?.contactID)
            assertEquals(UnprovedDirectMessageProvenance.UNRESOLVED,
                deps.data.fetchUnprovedDirectMessages(radio, 50, 0).single { it.occurrenceID == unresolved.id }.provenance)
            assertTrue(h.lifecycle.created.isEmpty())
        } finally { h.container.close() }
    }

    @Test fun androidCodecRestoreKeepsDistinctRetainedTextAccessibleThroughTheFeature() = runTest {
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = Bytes(ByteArray(32) { 9 }), nodeName = "Test radio"))
        val first = store.saveIncomingDirectMessage(
            message(31).copy(senderTimestamp = 0u, timestampCorrected = true), LocalDirectContactResolution.NotFound,
        ).message
        val second = store.saveIncomingDirectMessage(
            message(32).copy(senderTimestamp = 0u, timestampCorrected = true), LocalDirectContactResolution.NotFound,
        ).message
        val context = ApplicationProvider.getApplicationContext<Context>()
        val backup = AppBackupService(MeshCoreStorage.get(context).backupPreferences, "test", "1")
        val parsed = AppBackupCodec().parseBackup(backup.export(store).data)
        val immediate = java.util.concurrent.Executor { it.run() }
        val destination = Room.inMemoryDatabaseBuilder(context, MeshCoreDatabase::class.java)
            .allowMainThreadQueries().setQueryExecutor(immediate).setTransactionExecutor(immediate).build()
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val restored = RoomPersistenceStore(destination, owner)
        try {
            backup.importBackup(parsed, restored)
            val h = ContainerHarness(this, restored)
            try {
                val deps = h.container.chatsFeature.listFor(radio)
                val holder = UnresolvedMessagesStateHolder(radio, deps.data, deps.diagnostics)
                holder.refresh()
                assertEquals(setOf(first.id, second.id), holder.state.value.messages.map { it.occurrenceID }.toSet())
                assertTrue(holder.state.value.messages.all {
                    it.text == first.text && it.wireTimestamp == 0u &&
                        it.provenance == UnprovedDirectMessageProvenance.UNRESOLVED
                })
                assertTrue(restored.fetchContacts(radio).isEmpty())
            } finally { h.container.close() }
        } finally { restored.close(); destination.close(); owner.cancel() }
    }

    @Test fun fileReopenPreservesDistinctOccurrencesAndTheFeatureReadsThemAfterRestart() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "unresolved-${UUID.randomUUID()}.db"
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun open() = Room.databaseBuilder(context, MeshCoreDatabase::class.java, name)
            .allowMainThreadQueries().setQueryExecutor { it.run() }.setTransactionExecutor { it.run() }.build()
        var database = open()
        var persistent = RoomPersistenceStore(database, owner)
        try {
            persistent.saveIncomingDirectMessage(message(21), LocalDirectContactResolution.NotFound)
            persistent.saveIncomingDirectMessage(message(22), LocalDirectContactResolution.NotFound)
            persistent.close(); database.close()
            database = open()
            persistent = RoomPersistenceStore(database, owner)
            val h = ContainerHarness(this, persistent)
            try {
                val deps = h.container.chatsFeature.listFor(radio)
                val holder = UnresolvedMessagesStateHolder(radio, deps.data, deps.diagnostics)
                holder.refresh()
                assertEquals(setOf(UUID(0, 21), UUID(0, 22)), holder.state.value.messages.map { it.occurrenceID }.toSet())
                assertTrue(holder.state.value.messages.all { it.text == message(21).text })
            } finally { h.container.close() }
        } finally {
            persistent.close(); database.close(); owner.cancel()
            context.deleteDatabase(name)
        }
    }
}
