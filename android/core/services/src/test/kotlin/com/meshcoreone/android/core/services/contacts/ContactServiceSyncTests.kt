// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ContactServiceSyncTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol
import com.meshcoreone.android.core.contracts.domain.ContactSyncResult
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Service-level coverage for `ContactService.syncContacts` batch persistence and the manual-refresh
 * claim. The Swift suite drives the real in-memory store and `SyncCoordinator`; here the store fake
 * has the real repository's upsert semantics and [ContactsFakeSyncCoordinator] models the advert
 * claim protocol, because `SyncCoordinator` is not ported on this base.
 */
class ContactServiceSyncTests {
    private val suite = "ContactServiceSyncTests"

    private fun meshContact(keyByte: Int, name: String, lastModified: Instant = Instant.EPOCH): MeshContact {
        val key = contactsKey(keyByte)
        return MeshContact(
            id = key.uppercaseHexString(), publicKey = key, type = ContactType.CHAT, flags = ContactFlags(0u),
            outPathLength = 0u, outPath = Bytes.EMPTY, advertisedName = name, lastAdvertisement = Instant.EPOCH,
            latitude = 0.0, longitude = 0.0, lastModified = lastModified,
        )
    }

    private fun contactFrame(key: Bytes, name: String) = ContactFrame(
        publicKey = key, type = ContactType.CHAT, flags = 0u, outPathLength = 0u, outPath = Bytes.EMPTY, name = name,
        lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0, lastModified = 0u,
    )

    /** Swift `PersistenceStore.createTestDataStore(radioID:)`: a store holding the test device (self key 0x01...). */
    private fun testDataStore(radioId: RadioId) = ContactsFakeStore(listOf(contactsDevice(radioId)))

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        contactsOriginal(suite, "Full sync persists all device contacts and prunes locals not on device") {
            val radioId = RadioId(UUID.randomUUID())
            val store = testDataStore(radioId)
            // A stale local contact that the device no longer reports.
            store.saveContact(radioId, contactFrame(contactsKey(0xDD), "Stale"))

            val session = ContactsFakeSession()
            val lastModified = Instant.ofEpochSecond(1_700_000_000)
            session.setStubbedContacts(listOf(meshContact(0xAA, "Alice", lastModified), meshContact(0xBB, "Bob")))

            val service = contactsService(session = session, store = store)
            val result = service.syncContacts(radioId, since = null)

            assertEquals(2L, result.contactsReceived)
            assertEquals(false, result.isIncremental)
            assertEquals(lastModified.epochSecond.toUInt(), result.lastSyncTimestamp)

            val stored = store.fetchContacts(radioId)
            assertEquals(setOf("Alice", "Bob"), stored.map { it.name }.toSet())
            // The stale contact was pruned on full sync.
            assertNull(store.fetchContact(radioId, contactsKey(0xDD)))
        },
        contactsOriginal(suite, "Full sync prunes true orphans but keeps the ZephCore V-contact") {
            val radioId = RadioId(UUID.randomUUID())
            val store = testDataStore(radioId)

            // The V-contact derived from the test device's self key is omitted from GET_CONTACTS
            // while clock-deferred or disabled, so a full sync must not treat it as a table orphan.
            val selfKey = contactsKey(0x01)
            val vKey = assertNotNull(VContactIdentity.publicKey(selfKey))
            store.saveContact(radioId, contactFrame(vKey, "vTestDevice"))
            // A genuine orphan the device no longer reports.
            store.saveContact(radioId, contactFrame(contactsKey(0xDD), "Orphan"))

            val session = ContactsFakeSession()
            session.setStubbedContacts(listOf(meshContact(0xAA, "Alice")))

            contactsService(session = session, store = store).syncContacts(radioId, since = null)

            // The orphan is pruned; the V-contact survives.
            assertNull(store.fetchContact(radioId, contactsKey(0xDD)))
            assertNotNull(store.fetchContact(radioId, vKey))
            assertEquals(setOf("Alice", "vTestDevice"), store.fetchContacts(radioId).map { it.name }.toSet())
        },
        contactsOriginal(suite, "Incremental sync upserts without pruning unseen locals") {
            val radioId = RadioId(UUID.randomUUID())
            val store = testDataStore(radioId)
            // A local contact not present in this incremental batch must survive.
            store.saveContact(radioId, contactFrame(contactsKey(0xDD), "Existing"))

            val session = ContactsFakeSession()
            session.setStubbedContacts(listOf(meshContact(0xAA, "Alice")))

            val result = contactsService(session = session, store = store)
                .syncContacts(radioId, since = Instant.ofEpochSecond(100))

            assertEquals(1L, result.contactsReceived)
            assertEquals(true, result.isIncremental)
            assertEquals(setOf("Alice", "Existing"), store.fetchContacts(radioId).map { it.name }.toSet())
        },
        contactsOriginal(suite, "Manual refresh waits for an in-flight advert delta sync") {
            val radioId = RadioId(UUID.randomUUID())
            val store = testDataStore(radioId)
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession()
            session.setStubbedContacts(listOf(meshContact(0xAA, "Alice")))
            val service = contactsService(session = session, store = store, syncCoordinator = coordinator)

            val gated = ContactsGatedSyncContactService()
            val advertTask = async { coordinator.performAdvertContactSync(radioId, gated) }
            gated.waitForSyncStart()

            val refreshTask = async { service.syncContactsForRefresh(radioId) }

            delay(200.milliseconds)
            assertTrue(
                session.getContactsInvocations.isEmpty(),
                "A manual refresh must not fetch while an advert delta sync holds the claim",
            )

            gated.release()
            assertEquals(ContactsAdvertOutcome.SYNCED, advertTask.await())
            refreshTask.await()
            assertEquals(1, session.getContactsInvocations.size)
        },
        contactsOriginal(suite, "Manual refresh claim is atomic so a racing advert delta returns busy") {
            // claimManualContactSync waits and sets the flag in one critical section so a racing
            // advert sync either waits behind the claim or sees manual = true. The refresh body is
            // gated at getContacts so the test can observe the claim held before racing a second advert.
            val radioId = RadioId(UUID.randomUUID())
            val store = testDataStore(radioId)
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession()
            session.setStubbedContacts(listOf(meshContact(0xAA, "Alice")))
            session.holdNextGetContacts()
            val service = contactsService(session = session, store = store, syncCoordinator = coordinator)

            val gated = ContactsGatedSyncContactService()
            val firstAdvert = async { coordinator.performAdvertContactSync(radioId, gated) }
            gated.waitForSyncStart()

            val refreshTask = async { service.syncContactsForRefresh(radioId) }
            // Refresh is parked in the claim wait; release the first advert so the claim lands.
            gated.release()
            assertEquals(ContactsAdvertOutcome.SYNCED, firstAdvert.await())

            // Claim + set manual finished, refresh body parked in getContacts.
            session.waitForGetContactsStart()
            assertTrue(session.isGetContactsHeld())

            // Claim is held for the whole refresh body. A new advert round must return busy rather
            // than entering syncContacts and racing progress events.
            val secondAdvert = coordinator.performAdvertContactSync(radioId, ContactsMockContactService())
            assertEquals(ContactsAdvertOutcome.BUSY, secondAdvert, "Manual claim must block advert delta for the whole refresh")

            session.releaseGetContacts()
            refreshTask.await()
            assertEquals(1, session.getContactsInvocations.size)
        },
        contactsOriginal(suite, "Manual refresh surfaces syncInterrupted when the advert claim wait times out") {
            // ContactService maps a timed-out claim to syncInterrupted so the refresh spinner stops
            // with an error rather than hanging or racing the advert stream.
            val radioId = RadioId(UUID.randomUUID())
            val store = testDataStore(radioId)
            val coordinator = ContactsFakeSyncCoordinator(waitTimeout = 40.milliseconds)
            val session = ContactsFakeSession()
            session.setStubbedContacts(listOf(meshContact(0xAA, "Alice")))
            val service = contactsService(session = session, store = store, syncCoordinator = coordinator)

            val gated = ContactsGatedSyncContactService()
            val advertTask = async { coordinator.performAdvertContactSync(radioId, gated) }
            gated.waitForSyncStart()

            try {
                assertFailsWith<ContactServiceError.SyncInterrupted> { service.syncContactsForRefresh(radioId) }
                assertTrue(session.getContactsInvocations.isEmpty(), "Timed-out refresh must not start a contact fetch")
            } finally {
                gated.release()
                advertTask.await()
            }
        },
    )
}

/**
 * Contact service stub that parks inside `syncContacts` until released, so a test can hold an
 * advert-driven delta sync open.
 */
private class ContactsGatedSyncContactService : ContactServiceProtocol {
    private val started = CompletableDeferred<Unit>()
    private val gate = CompletableDeferred<Unit>()

    suspend fun waitForSyncStart() = started.await()

    fun release() {
        gate.complete(Unit)
    }

    override suspend fun syncContacts(radioId: RadioId, since: Instant?): ContactSyncResult {
        started.complete(Unit)
        gate.await()
        return ContactSyncResult(contactsReceived = 0, lastSyncTimestamp = 0u, isIncremental = true)
    }
}
