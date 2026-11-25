// PortedFrom: MC1Services/Tests/MC1ServicesTests/IdentityReconciliationIntegrationTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.ConnectionMethod
import com.meshcoreone.android.core.model.SnapshotList
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IdentityReconciliationPersistenceTest : RepositoryTest() {
    @Test fun reconciliationPublishesItsCommittedResultBeforeCancellationReturns() = runTest {
        val source = device().copy(connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())))
        store.saveDevice(source)
        var committedKey: com.meshcoreone.android.core.protocol.bytes.Bytes? = null
        val work = async {
            val caller = currentCoroutineContext()[Job]
            store.reconcileImportedIdentity(
                source.id, source.radioId, source.publicKey, key(0x55), { true },
            ) {
                committedKey = it.publicKey
                caller?.cancel(CancellationException("after commit"))
            }
        }
        assertFailsWith<CancellationException> { work.await() }
        assertEquals(key(0x55), committedKey)
        assertEquals(committedKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
    }

    @Test fun recoveryChecksTheSourceBeforeInvokingTheRadioWrite() = runTest {
        val source = device().copy(connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())))
        store.saveDevice(source)
        store.deleteDevice(source.id)
        var writes = 0
        val result = store.retryImportedIdentity(source.id, source.radioId, source.publicKey, { true }, writeIdentity = {
            writes++
            key(0x55)
        })
        assertNull(result)
        assertEquals(0, writes)
    }

    @Test fun recoveryAtomicallyAdoptsTheMatchingGhostWithoutMovingEitherPartition() = runTest {
        val ghost = device(RADIO_A).copy(publicKey = key(0x11))
        val source = device(RADIO_B).copy(
            publicKey = key(0x22), connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())),
        )
        store.saveDevice(ghost)
        store.saveDevice(source)
        store.saveContact(RADIO_A, frame(0x33, "Restored"))
        store.saveContact(RADIO_B, frame(0x44, "Temporary"))
        var writes = 0
        val result = store.retryImportedIdentity(source.id, source.radioId, source.publicKey, { true }, writeIdentity = {
            writes++
            ghost.publicKey
        })
        assertEquals(1, writes)
        assertEquals(RADIO_A, assertNotNull(result).radioId)
        assertEquals(ghost.publicKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
        assertNull(store.fetchDevice(ghost.id))
        assertEquals(listOf("Restored"), store.fetchContacts(RADIO_A).map { it.name })
        assertEquals(listOf("Temporary"), store.fetchContacts(RADIO_B).map { it.name })
    }

    @Test fun revokedRecoveryDoesNotCommitTheObservedIdentity() = runTest {
        val source = device().copy(connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())))
        store.saveDevice(source)
        var current = true
        val result = store.retryImportedIdentity(source.id, source.radioId, source.publicKey, { current }, writeIdentity = {
            current = false
            key(0x55)
        })
        assertNull(result)
        assertEquals(source.publicKey, assertNotNull(store.fetchDevice(source.id)).publicKey)
    }

    @Test fun recoveryDoesNotWriteToAReplacedSource() = runTest {
        val source = device().copy(connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())))
        store.saveDevice(source)
        store.saveDevice(source.copy(publicKey = key(0x66)))
        var writes = 0
        val result = store.retryImportedIdentity(source.id, source.radioId, source.publicKey, { true }, writeIdentity = {
            writes++
            key(0x55)
        })
        assertNull(result)
        assertEquals(0, writes)
        assertEquals(key(0x66), assertNotNull(store.fetchDevice(source.id)).publicKey)
    }

    @Test fun failedRecoveryWriteDoesNotChangeTheSourcePartition() = runTest {
        val source = device().copy(connectionMethods = SnapshotList.of(ConnectionMethod.Bluetooth(UUID.randomUUID())))
        store.saveDevice(source)
        assertFailsWith<IllegalStateException> {
            store.retryImportedIdentity(source.id, source.radioId, source.publicKey, { true }, writeIdentity = {
                throw IllegalStateException("write failed")
            })
        }
        val unchanged = assertNotNull(store.fetchDevice(source.id))
        assertEquals(source.radioId, unchanged.radioId)
        assertEquals(source.publicKey, unchanged.publicKey)
    }

    @Test @OriginalCase("IdentityReconciliationIntegrationTests::Remove + erase/flash + re-pair + import config re-links orphaned children()")
    fun publicKeyReconciliationReattachesTheOriginalRadioPartition() = runTest {
        val original = device().copy(publicKey = key(0x11), isActive = true)
        store.saveDevice(original)
        store.saveContact(RADIO_A, frame(0x22, "Bob"))
        store.demoteDeviceToGhost(original.id)
        val ghost = assertNotNull(store.fetchDevice(original.publicKey))
        val paired = device(RADIO_B).copy(publicKey = key(0x33), isActive = true)
        store.saveDevice(paired)
        assertTrue(store.fetchContacts(RADIO_B).isEmpty())
        assertEquals(1, store.fetchContacts(RADIO_A).size)
        assertEquals(RADIO_A, store.reconcileGhostIdentity(paired.id, original.publicKey))
        val updated = assertNotNull(store.fetchDevice(paired.id))
        assertEquals(RADIO_A, updated.radioId)
        assertEquals(original.publicKey, updated.publicKey)
        assertNull(store.fetchDevice(original.id))
        assertNull(store.fetchDevice(ghost.id))
        assertEquals(listOf("Bob"), store.fetchContacts(RADIO_A).map { it.name })
    }

    @Test @OriginalCase("IdentityReconciliationIntegrationTests::No reconciliation when radio's publicKey didn't change after re-pair()")
    fun currentDeviceWithoutAGhostKeepsItsRadioID() = runTest {
        val current = device().copy(isActive = true)
        store.saveDevice(current)
        assertNull(store.reconcileGhostIdentity(current.id, current.publicKey))
        val unchanged = assertNotNull(store.fetchDevice(current.id))
        assertEquals(current.radioId, unchanged.radioId)
        assertEquals(current.publicKey, unchanged.publicKey)
    }

    @Test @OriginalCase("IdentityReconciliationIntegrationTests::Reconciliation does not migrate post-pair child rows to the reconciled radioID()")
    fun reconciliationDoesNotInventAMigrationForTemporaryChildRows() = runTest {
        val original = device().copy(publicKey = key(0x11), isActive = true)
        store.saveDevice(original)
        store.demoteDeviceToGhost(original.id)
        val temporary = RadioId(UUID.randomUUID())
        val paired = device(temporary).copy(publicKey = key(0x22), isActive = true)
        store.saveDevice(paired)
        store.saveContact(temporary, frame(0x33, "PostPair"))
        assertEquals(RADIO_A, store.reconcileGhostIdentity(paired.id, original.publicKey))
        assertEquals(listOf("PostPair"), store.fetchContacts(temporary).map { it.name })
        assertTrue(store.fetchContacts(RADIO_A).isEmpty())
        assertEquals(RADIO_A, assertNotNull(store.fetchDevice(paired.id)).radioId)
        assertEquals(original.publicKey, assertNotNull(store.fetchDevice(paired.id)).publicKey)
    }
}
