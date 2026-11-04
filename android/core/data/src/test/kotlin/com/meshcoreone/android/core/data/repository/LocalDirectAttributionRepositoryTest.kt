// AndroidOnly: WP-202 Real Room controls for local-only attribution, ABA, roster fencing and data retention.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDirectAttributionRepositoryTest : RepositoryTest() {
    private val prefix = Bytes.of(1, 2, 3, 4, 5, 6)
    private fun peer(suffix: Int, name: String = "Peer$suffix") =
        contact(id = UUID(0, suffix.toLong()), publicKey = Bytes(prefix.toByteArray() + ByteArray(26) { suffix.toByte() }), name = name)
    private fun incoming(id: UUID = UUID.randomUUID()) = message(
        id = id, contactID = null, text = "Original \uD83D\uDE80 text", direction = MessageDirection.INCOMING,
    ).copy(senderKeyPrefix = prefix, timestamp = 42u, senderTimestamp = 0u, timestampCorrected = true)

    @Test fun classificationCountsEveryRadioRowIndependentOfNameAndInsertOrder() = runTest(scheduler) {
        assertEquals(LocalDirectContactResolution.NotFound, store.classifyLocalDirectContact(RADIO_A, prefix))
        val first = peer(10, "Z")
        val second = peer(11, "A")
        for (order in listOf(listOf(first, second), listOf(second, first))) {
            order.forEach { store.saveContact(it) }
            assertEquals(LocalDirectContactResolution.Ambiguous, store.classifyLocalDirectContact(RADIO_A, prefix))
            order.forEach { store.deleteContact(EntityKey(RADIO_A, it.id)) }
        }
        store.saveContact(first.copy(radioId = RADIO_B))
        assertEquals(LocalDirectContactResolution.NotFound, store.classifyLocalDirectContact(RADIO_A, prefix))
        store.saveContact(first)
        assertEquals(first, assertIs<LocalDirectContactResolution.Unique>(store.classifyLocalDirectContact(RADIO_A, prefix)).contact)
    }

    @Test fun deletionAfterLookupSavesNullBindingAndExactOriginalOccurrence() = runTest(scheduler) {
        val peer = peer(10)
        store.saveContact(peer)
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        store.deleteContact(EntityKey(RADIO_A, peer.id))
        val wire = incoming().copy(contactID = peer.id)
        val result = store.saveIncomingDirectMessage(wire, observed)
        assertEquals(LocalDirectContactResolution.Stale, result.resolution)
        assertNull(result.message.contactID)
        assertEquals(wire.text, result.message.text)
        assertEquals(wire.id, result.message.id)
        assertEquals(prefix, result.message.senderKeyPrefix)
        assertEquals(wire.senderTimestamp, result.message.senderTimestamp)
        assertEquals(result.message, store.fetchMessage(EntityKey(RADIO_A, wire.id)))
    }

    @Test fun addingCollisionAfterLookupCannotPersistTheProvisionalContact() = runTest(scheduler) {
        store.saveContact(peer(10))
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        store.batchSaveContacts(RADIO_A, listOf(peer(11).toContactFrame()).snapshot())
        val result = store.saveIncomingDirectMessage(incoming().copy(contactID = peer(10).id), observed)
        assertEquals(LocalDirectContactResolution.Ambiguous, result.resolution)
        assertNull(result.message.contactID)
        assertEquals(0L, store.fetchContact(EntityKey(RADIO_A, peer(10).id))?.unreadCount)
    }

    @Test fun sameIdAndKeyDeletionRecreationInvalidatesTheProcessObservation() = runTest(scheduler) {
        val original = peer(10)
        store.saveContact(original)
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        store.deleteContact(EntityKey(RADIO_A, original.id))
        store.saveContact(original)
        val result = store.saveIncomingDirectMessage(incoming(), observed)
        assertEquals(LocalDirectContactResolution.Stale, result.resolution)
        assertNull(result.message.contactID)
    }

    @Test fun ambiguousOccurrencesNeverAutoAdoptAfterShrinkOrRepositoryRecreation() = runTest(scheduler) {
        val first = peer(10)
        val second = peer(11)
        store.saveContact(first)
        store.saveContact(second)
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        val one = store.saveIncomingDirectMessage(incoming(), observed).message
        val two = store.saveIncomingDirectMessage(incoming(), observed).message
        assertNotEquals(one.deduplicationKey, two.deduplicationKey)
        store.deleteContact(EntityKey(RADIO_A, second.id))
        store.close()
        store = newStore()
        assertTrue(store.adoptOrphanedDirectMessages(
            RADIO_A, listOf(ContactIdentity(first.id, first.publicKey)).snapshot(),
        ).isEmpty())
        for (dto in listOf(one, two)) {
            val saved = assertNotNull(store.fetchMessage(EntityKey(RADIO_A, dto.id)))
            assertNull(saved.contactID)
            assertEquals(dto.deduplicationKey, saved.deduplicationKey)
            assertEquals(dto.text, saved.text)
        }
    }

    @Test fun finalResolvedDedupUsesOriginalWireTimestampNotCorrectedTimestamp() = runTest(scheduler) {
        val peer = peer(10)
        store.saveContact(peer)
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        val one = store.saveIncomingDirectMessage(incoming(), observed)
        val duplicate = store.saveIncomingDirectMessage(incoming(), observed)
        assertTrue(one.inserted)
        assertFalse(duplicate.inserted)
        assertEquals(one.message.id, duplicate.message.id)
        assertEquals(RepositoryDeduplicationKey.contentBased(peer.id, null, null, 0u, one.message.text), one.message.deduplicationKey)
        assertEquals(peer.id, one.message.contactID)
    }

    @Test fun occurrenceRetryNeverRebindsAnInitiallyUnresolvedBody() = runTest(scheduler) {
        val dto = incoming()
        val first = store.saveIncomingDirectMessage(dto, LocalDirectContactResolution.NotFound)
        store.saveContact(peer(10))
        val retry = store.saveIncomingDirectMessage(dto, store.classifyLocalDirectContact(RADIO_A, prefix))
        assertFalse(retry.inserted)
        assertEquals(first.message, retry.message)
        assertEquals(LocalDirectContactResolution.Stale, retry.resolution)
        assertNull(retry.message.contactID)
    }

    @Test fun scopedReadsAndMetadataReenterButRosterWritesWaitUntilScopeExits() = runTest(scheduler) {
        val peer = peer(10)
        store.saveContact(peer)
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val scoped = async {
            store.withLocalDirectContactOperation(RADIO_A, prefix, observed) { admission ->
                assertIs<LocalDirectContactResolution.Unique>(admission.resolution)
                assertTrue(store.touchContactHeard(RADIO_A, peer.publicKey, AT))
                assertNotNull(store.fetchContact(EntityKey(RADIO_A, peer.id)))
                entered.complete(Unit)
                release.await()
                admission.saveIncomingDirectMessage(incoming())
            }
        }
        entered.await()
        val writer = async { store.saveContact(peer(11)) }
        runCurrent()
        assertFalse(writer.isCompleted)
        release.complete(Unit)
        assertEquals(peer.id, scoped.await().message.contactID)
        writer.await()
        assertEquals(LocalDirectContactResolution.Ambiguous, store.classifyLocalDirectContact(RADIO_A, prefix))
    }

    @Test fun cancellationReleasesFenceAndExpiredOrChildAdmissionsAreRejected() = runTest(scheduler) {
        store.saveContact(peer(10))
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        lateinit var escaped: LocalDirectContactAdmission
        val entered = CompletableDeferred<Unit>()
        val job = launch {
            store.withLocalDirectContactOperation(RADIO_A, prefix, observed) {
                escaped = it
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val writer = async { store.saveContact(peer(11)) }
        runCurrent()
        assertFalse(writer.isCompleted)
        job.cancelAndJoin()
        writer.await()
        assertFailsWith<IllegalStateException> { escaped.saveIncomingDirectMessage(incoming()) }
        store.withLocalDirectContactOperation(RADIO_A, prefix, LocalDirectContactResolution.Ambiguous) { admission ->
            val child = async {
                assertFailsWith<IllegalStateException> { admission.saveIncomingDirectMessage(incoming()) }
            }
            child.await()
        }
    }

    @Test fun deviceClearAndRestoreJoinTheSameRosterFence() = runTest(scheduler) {
        val device = device()
        store.saveDevice(device)
        store.saveContact(peer(10))
        val observed = store.classifyLocalDirectContact(RADIO_A, prefix)
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val read = launch {
            store.withLocalDirectContactOperation(RADIO_A, prefix, observed) {
                entered.complete(Unit)
                release.await()
            }
        }
        entered.await()
        val clear = async { store.deleteDeviceData(device.id) }
        val restore = async { store.withBackupRestore { _, _ -> Unit } }
        runCurrent()
        assertFalse(clear.isCompleted)
        assertFalse(restore.isCompleted)
        release.complete(Unit)
        read.join()
        clear.await()
        restore.await()
        assertEquals(LocalDirectContactResolution.NotFound, store.classifyLocalDirectContact(RADIO_A, prefix))
        assertEquals(LocalDirectContactResolution.Stale, store.saveIncomingDirectMessage(incoming(), observed).resolution)
    }

    @Test fun conditionalMaterializationDoesNotOverwriteACompetingRowAndPathUpdatesAreAtomic() = runTest(scheduler) {
        val peer = peer(10)
        val inserted = assertIs<LocalDirectContactResolution.Unique>(
            store.materializeLocalDirectContactIfAbsent(RADIO_A, prefix, peer.toContactFrame(), AT),
        )
        val actual = inserted.contact
        val second = store.materializeLocalDirectContactIfAbsent(RADIO_A, prefix, peer(11).toContactFrame(), AT)
        assertEquals(actual.id, assertIs<LocalDirectContactResolution.Unique>(second).contact.id)
        assertEquals(1, store.fetchContacts(RADIO_A).size)
        val updated = assertIs<LocalDirectContactResolution.Unique>(store.applyLocalDirectPathIfCurrent(
            RADIO_A, prefix, inserted, 1u, Bytes.of(0xAA), AT.plusSeconds(1),
        )).contact
        assertEquals(Bytes.of(0xAA), updated.outPath)
        assertEquals(actual.lastModified, updated.lastModified)
        assertEquals(AT.plusSeconds(1), store.fetchDiscoveredNodes(RADIO_A).single().lastHeard)
        store.saveContact(peer(11))
        assertEquals(LocalDirectContactResolution.Ambiguous, store.applyLocalDirectPathIfCurrent(
            RADIO_A, prefix, inserted, 0u, Bytes.EMPTY, AT.plusSeconds(2),
        ))
        assertEquals(updated, store.fetchContact(EntityKey(RADIO_A, updated.id)))
    }

    @Test fun malformedPrefixAndStorageFailureNeverBecomeAbsence() = runTest(scheduler) {
        assertFailsWith<PersistenceStoreException> { store.classifyLocalDirectContact(RADIO_A, Bytes.EMPTY) }
        store.close()
        assertFailsWith<PersistenceStoreException> { store.classifyLocalDirectContact(RADIO_A, prefix) }
    }
}
