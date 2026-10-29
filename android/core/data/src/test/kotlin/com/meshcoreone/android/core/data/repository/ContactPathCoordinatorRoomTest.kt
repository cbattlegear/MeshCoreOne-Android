// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ContactServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ContactServiceSyncTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import com.meshcoreone.android.core.protocol.session.ContactFetchResult
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.services.contacts.ContactPreferenceFlags
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.contacts.ContactSyncCoordinating
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.core.services.contacts.ContactServiceEvent
import com.meshcoreone.android.core.services.sync.*
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ContactPathCoordinatorRoomTest : RepositoryTest() {
    private fun mesh(byte: Int, name: String, modified: Instant = Instant.EPOCH): MeshContact {
        val key = key(byte)
        return MeshContact(key.uppercaseHexString(), key, ContactType.CHAT, ContactFlags(0u),
            0u, Bytes.EMPTY, name, Instant.EPOCH, 0.0, 0.0, modified)
    }

    private class SyncSession(private val contacts: List<MeshContact>) : ContactSessionOps {
        val calls = mutableListOf<Instant?>()
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult {
            calls += since
            gate?.await()
            return ContactFetchResult(contacts, contacts.size.toLong())
        }
        override suspend fun getContacts(since: Instant?): List<MeshContact> =
            getContactsReportingTotal(since).contacts
        override suspend fun getContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun addContact(contact: MeshContact): Nothing = unexpected()
        override suspend fun removeContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun resetPath(publicKey: Bytes): Nothing = unexpected()
        override suspend fun sendPathDiscovery(destination: Bytes): Nothing = unexpected()
        override suspend fun shareContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun exportContact(publicKey: Bytes?): Nothing = unexpected()
        override suspend fun importContact(cardData: Bytes): Nothing = unexpected()
        override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags): Nothing = unexpected()
        private fun unexpected(): Nothing = error("Contact sync must not invoke another session operation")
    }

    private fun syncService(session: ContactSessionOps, coordinator: SyncCoordinator? = null): ContactService {
        val bridge = coordinator?.let { actual ->
            object : ContactSyncCoordinating {
                override suspend fun claimManualContactSync() = actual.claimManualContactSync()
                override suspend fun setManualContactSyncActive(active: Boolean) = actual.setManualContactSyncActive(active)
                override suspend fun notifyContactsChanged() = actual.notifyContactsChanged()
                override suspend fun notifyConversationsChanged() = actual.notifyConversationsChanged()
                override suspend fun refreshBlockedContactsCache(radioId: RadioId, dataStore: ContactPersisting) =
                    actual.refreshBlockedContactsCache(radioId, dataStore)
            }
        }
        val preferences = object : ContactPreferenceFlags {
            override fun bool(key: String): Nothing = error("Contact sync must not read preference flags")
            override fun set(key: String, value: Boolean): Nothing = error("Contact sync must not write preference flags")
        }
        return ContactService(session, store, store, bridge, null, preferences, clock)
    }

    @OriginalCase("ContactServiceSyncTests::Full sync persists all device contacts and prunes locals not on device()")
    @Test fun fullSyncCommitsBothDeviceContactsAndPrunesTheActualStaleLocalBeforeCompletion() = runTest(scheduler) {
        exerciseBatchSync(incremental = false, vContact = false)
    }

    @OriginalCase("ContactServiceSyncTests::Full sync prunes true orphans but keeps the ZephCore V-contact()")
    @Test fun fullSyncPrunesTheActualOrphanButKeepsTheDerivedVContactAndForeignPartition() = runTest(scheduler) {
        exerciseBatchSync(incremental = false, vContact = true)
    }

    @OriginalCase("ContactServiceSyncTests::Incremental sync upserts without pruning unseen locals()")
    @Test fun incrementalBatchKeepsTheActualUnseenLocalAndPublishesItsCommittedProgress() = runTest(scheduler) {
        exerciseBatchSync(incremental = true, vContact = false)
    }

    private suspend fun TestScope.exerciseBatchSync(incremental: Boolean, vContact: Boolean) {
        store.saveDevice(device().copy(publicKey = key(1)))
        val stale = contact(publicKey = key(0xDD),
            name = if (incremental) "Existing" else if (vContact) "Orphan" else "Stale")
        store.saveContact(stale)
        store.saveContact(stale.copy(radioId = RADIO_B))
        val virtual = if (vContact) contact(id = java.util.UUID.randomUUID(),
            publicKey = assertNotNull(VContactIdentity.publicKey(key(1))), name = "vTestDevice") else null
        virtual?.let { store.saveContact(it) }
        val rows = if (!incremental && !vContact) listOf(mesh(0xAA, "Alice", AT), mesh(0xBB, "Bob"))
            else listOf(mesh(0xAA, "Alice"))
        val session = SyncSession(rows)
        val service = syncService(session)
        val subscription = service.events()
        val observed = mutableListOf<ContactServiceEvent>()
        val listener = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { subscription.events.toList(observed) }
        try {
            val since = if (incremental) Instant.ofEpochSecond(100) else null
            val result = service.syncContacts(RADIO_A, since)
            service.finishEvents()
            listener.join()
            assertEquals(rows.size.toLong(), result.contactsReceived)
            assertEquals(incremental, result.isIncremental)
            assertEquals(if (rows.size == 2) AT.epochSecond.toUInt() else 0u, result.lastSyncTimestamp)
            val names = rows.map { it.advertisedName }.toSet() +
                if (incremental) setOf("Existing") else if (vContact) setOf("vTestDevice") else emptySet()
            assertEquals(names, store.fetchContacts(RADIO_A).map { it.name }.toSet())
            assertEquals(if (incremental) stale else null, store.fetchContact(RADIO_A, stale.publicKey))
            virtual?.let { assertEquals(it, store.fetchContact(RADIO_A, it.publicKey)) }
            assertEquals(stale.copy(radioId = RADIO_B), store.fetchContact(RADIO_B, stale.publicKey))
            assertEquals(listOf(since), session.calls)
            assertEquals(listOf<ContactServiceEvent>(ContactServiceEvent.SyncProgress(0, rows.size.toLong()),
                ContactServiceEvent.SyncProgress(rows.size.toLong(), rows.size.toLong())), observed)
        } finally {
            service.finishEvents()
            subscription.close()
            listener.cancelAndJoin()
        }
    }

    @OriginalCase("ContactServiceSyncTests::Manual refresh waits for an in-flight advert delta sync()")
    @Test fun actualManualRefreshWaitsForTheRealAdvertClaimAndThenCommitsItsRoomSnapshot() = runTest(scheduler) {
        exerciseClaims(race = false, timeout = false)
    }

    @OriginalCase("ContactServiceSyncTests::Manual refresh claim is atomic so a racing advert delta returns busy()")
    @Test fun theRealManualClaimRejectsARacingAdvertWhileItsActualRadioReadIsHeld() = runTest(scheduler) {
        exerciseClaims(race = true, timeout = false)
    }

    @OriginalCase("ContactServiceSyncTests::Manual refresh surfaces syncInterrupted when the advert claim wait times out()")
    @Test fun theActualFortyMillisecondClaimTimeoutSurfacesSyncInterruptedWithoutAManualRead() = runTest(scheduler) {
        exerciseClaims(race = false, timeout = true)
    }

    private suspend fun TestScope.exerciseClaims(race: Boolean, timeout: Boolean) {
        store.saveDevice(device().copy(publicKey = key(1), lastContactSync = 1_704_067_200u))
        val syncClock = object : SyncClock {
            override fun now(): Instant = Instant.ofEpochSecond(1_704_067_200).plusMillis(scheduler.currentTime)
            override fun elapsed(): Duration = scheduler.currentTime.milliseconds
            override suspend fun sleep(duration: Duration) { delay(duration) }
        }
        val coordinator = SyncCoordinator(owner, syncClock)
        if (timeout) coordinator.setAdvertContactSyncWaitTimeoutOverride(40.milliseconds)
        val advertGate = CompletableDeferred<Unit>()
        val manualGate = CompletableDeferred<Unit>()
        val advertSession = SyncSession(listOf(mesh(0xAA, "Alice"))).also { it.gate = advertGate }
        val manualSession = SyncSession(listOf(mesh(0xAA, "Alice"))).also { if (race) it.gate = manualGate }
        val advertService = syncService(advertSession)
        val manualService = syncService(manualSession, coordinator)
        val advert = async { coordinator.performAdvertContactSync(false, RADIO_A, store, advertService) }
        try {
            runCurrent()
            assertEquals(listOf<Instant?>(Instant.ofEpochSecond(1_704_067_199)), advertSession.calls)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            if (timeout) {
                val refresh = async {
                    assertFailsWith<ContactServiceError.SyncInterrupted> { manualService.syncContactsForRefresh(RADIO_A) }
                }
                runCurrent()
                advanceTimeBy(39)
                runCurrent()
                assertFalse(refresh.isCompleted)
                advanceTimeBy(1)
                runCurrent()
                refresh.await()
                assertTrue(manualSession.calls.isEmpty())
                assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            } else {
                val refresh = async { manualService.syncContactsForRefresh(RADIO_A) }
                runCurrent()
                advanceTimeBy(200)
                runCurrent()
                assertFalse(refresh.isCompleted)
                assertTrue(manualSession.calls.isEmpty())
                advertGate.complete(Unit)
                runCurrent()
                assertEquals(SyncAdvertContactSyncOutcome.SYNCED, advert.await())
                assertEquals(listOf<Instant?>(null), manualSession.calls)
                if (race) {
                    assertFalse(refresh.isCompleted)
                    val callsBefore = advertSession.calls.toList()
                    assertEquals(SyncAdvertContactSyncOutcome.BUSY,
                        coordinator.performAdvertContactSync(false, RADIO_A, store, advertService))
                    assertEquals(callsBefore, advertSession.calls)
                    manualGate.complete(Unit)
                    runCurrent()
                }
                assertEquals(1L, refresh.await().contactsReceived)
            }
            advertGate.complete(Unit)
            runCurrent()
            assertEquals(SyncAdvertContactSyncOutcome.SYNCED, advert.await())
            assertEquals(listOf("Alice"), store.fetchContacts(RADIO_A).map { it.name })
            assertEquals(1L, coordinator.contactsVersion)
        } finally {
            advertGate.complete(Unit)
            manualGate.complete(Unit)
            advertService.finishEvents()
            manualService.finishEvents()
        }
    }

    @OriginalCase("ContactServiceTests::resetPath flood-routes the contact while preserving an unmodeled type byte()")
    @Test fun productionResetPathCommitsFloodRoutingWithoutNormalizingAnUnknownTypeOrOtherRadio() = runTest {
        val contact = contact().copy(typeRawValue = 0x7Fu, outPathLength = 2u, outPath = Bytes.of(0xAB, 0xCD))
        store.saveContact(contact)
        store.saveContact(contact.copy(radioId = RADIO_B))
        val resetKeys = mutableListOf<Bytes>()
        val session = object : ContactSessionOps {
            override suspend fun resetPath(publicKey: Bytes) { resetKeys += publicKey }
            override suspend fun getContacts(since: Instant?): Nothing = unexpected()
            override suspend fun getContactsReportingTotal(since: Instant?): Nothing = unexpected()
            override suspend fun getContact(publicKey: Bytes): Nothing = unexpected()
            override suspend fun addContact(contact: MeshContact): Nothing = unexpected()
            override suspend fun removeContact(publicKey: Bytes): Nothing = unexpected()
            override suspend fun sendPathDiscovery(destination: Bytes): Nothing = unexpected()
            override suspend fun shareContact(publicKey: Bytes): Nothing = unexpected()
            override suspend fun exportContact(publicKey: Bytes?): Nothing = unexpected()
            override suspend fun importContact(cardData: Bytes): Nothing = unexpected()
            override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags): Nothing = unexpected()
            private fun unexpected(): Nothing = error("Reset-path must not invoke another session operation")
        }
        val preferences = object : ContactPreferenceFlags {
            override fun bool(key: String): Nothing = error("Reset-path must not read preference flags")
            override fun set(key: String, value: Boolean): Nothing = error("Reset-path must not write preference flags")
        }
        val service = ContactService(session, store, store, null, null, preferences, clock)
        try {
            service.resetPath(RADIO_A, contact.publicKey)
            assertEquals(listOf(contact.publicKey), resetKeys)
            val expected = contact.copy(outPathLength = 255u, outPath = Bytes.EMPTY, lastModified = AT.epochSecond.toUInt())
            val actual = assertNotNull(store.fetchContact(RADIO_A, contact.publicKey))
            assertTrue(actual.isFloodRouted)
            assertEquals(0x7Fu.toUByte(), actual.typeRawValue)
            assertEquals(expected, actual)
            assertEquals(contact.copy(radioId = RADIO_B), store.fetchContact(RADIO_B, contact.publicKey))
        } finally {
            service.finishEvents()
        }
    }
}
