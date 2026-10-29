// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.AdvertisingSessionOps
import com.meshcoreone.android.core.protocol.session.SessionEventStreaming
import com.meshcoreone.android.core.runtime.EventBroadcaster
import com.meshcoreone.android.core.services.contacts.*
import java.time.Instant
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AdvertisementCoordinatorRoomTest : RepositoryTest() {
    @OriginalCase("AdvertisementServiceTests::background 0x8F queues key and does not deleteContact()")
    @Test fun backgroundDeletePreservesTheRealRowUntilStopDrainsIt() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x8C, "Doomed")
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            assertEquals(seed.contact, saved(seed.contact))
            assertTrue(events.isEmpty())
            stop()
            assertNull(saved(seed.contact))
            assertCleanup(seed.contact.id)
        }
    }

    @OriginalCase("AdvertisementServiceTests::handleReturnToForeground flushes queued 0x8F deletes once()")
    @Test fun foregroundDeletesBothQueuedContactsInOneCommittedCleanup() = runTest(scheduler) {
        withAdvert {
            val a = seed(0xA1, "A")
            val b = seed(0xA2, "B")
            send(MeshEvent.ContactDeleted(a.contact.publicKey))
            send(MeshEvent.ContactDeleted(b.contact.publicKey))
            assertEquals(a.contact, saved(a.contact))
            assertEquals(b.contact, saved(b.contact))
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertNull(saved(a.contact))
            assertNull(saved(b.contact))
            assertEquals(1, intercepted.deleteCalls)
            assertCleanup(a.contact.id, b.contact.id)
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(1, intercepted.deleteCalls)
            assertCleanup(a.contact.id, b.contact.id)
        }
    }

    @OriginalCase("AdvertisementServiceTests::re-advert after background 0x8F clears pendingDeletedKeys()")
    @Test fun aLaterBackgroundAdvertRevivesTheQueuedContact() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xA3, "Back")
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            stop()
            assertNotNull(saved(seed.contact))
            assertEquals(0, intercepted.deleteCalls)
            assertTrue(cleanups().isEmpty())
        }
    }

    @OriginalCase("AdvertisementServiceTests::stopEventMonitoring flushes 0x8F parked on isInForeground hop()")
    @Test fun stopWaitsForTheParkedForegroundHopThenFlushesTheActualDelete() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xC1, "Parked")
            val hop = holdForeground()
            send(MeshEvent.ContactDeleted(seed.contact.publicKey), handled = false)
            assertTrue(hop.waiting)
            val stopping = async { stop() }
            runCurrent()
            assertFalse(stopping.isCompleted)
            assertEquals(seed.contact, saved(seed.contact))
            hop.release()
            stopping.await()
            assertNull(saved(seed.contact))
            assertCleanup(seed.contact.id)
        }
    }

    @OriginalCase("AdvertisementServiceTests::0x80 during 0x8F flush hop does not delete the revived contact()")
    @Test fun anAdvertParkedInsideTheDeleteHopPreservesContactAndHistory() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xA4, "Revived", history = "keep")
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            foreground = true
            val foregroundHop = holdForeground()
            val deletion = holdDelete()
            val flushing = async { service.handleReturnToForeground() }
            runCurrent()
            assertTrue(deletion.waiting)
            send(MeshEvent.Advertisement(seed.contact.publicKey), handled = false)
            assertTrue(foregroundHop.waiting)
            deletion.release()
            flushing.await()
            assertEquals(seed.contact, saved(seed.contact))
            assertHistory(seed, present = true)
            assertTrue(cleanups().isEmpty())
            foregroundHop.release()
        }
    }

    @OriginalCase("AdvertisementServiceTests::flush does not clear skip set before deleteContacts()")
    @Test fun revivalBeforeStartingTheDeleteHopSurvivesItsRealSkippingCallback() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xA5, "SkipFirst", history = "keep")
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            foreground = true
            val foregroundHop = holdForeground()
            send(MeshEvent.Advertisement(seed.contact.publicKey), handled = false)
            assertTrue(foregroundHop.waiting)
            val deletion = holdDelete()
            val flushing = async { service.handleReturnToForeground() }
            runCurrent()
            assertTrue(deletion.waiting)
            deletion.release()
            flushing.await()
            assertEquals(seed.contact, saved(seed.contact))
            assertHistory(seed, present = true)
            assertTrue(cleanups().isEmpty())
            foregroundHop.release()
        }
    }

    @OriginalCase("AdvertisementServiceTests::background 0x80 then 0x8F flush still deletes the contact()")
    @Test fun theLaterDeleteWinsOverAnEarlierAdvertAndCascadesRealHistory() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xA6, "LastEvent", history = "gone")
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertNull(saved(seed.contact))
            assertHistory(seed, present = false)
            assertCleanup(seed.contact.id)
        }
    }

    @OriginalCase("AdvertisementServiceTests::failed 0x8F flush re-queues keys()")
    @Test fun aFailedRealDeleteRollsBackAndIsRetriedOnTheNextForegroundFlush() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xE1, "Retry", history = "retain through rollback")
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            rejectDeletes()
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(seed.contact, saved(seed.contact))
            assertHistory(seed, present = true)
            assertTrue(cleanups().isEmpty())
            assertEquals(1, errors.size)
            allowDeletes()
            service.handleReturnToForeground()
            runCurrent()
            assertNull(saved(seed.contact))
            assertHistory(seed, present = false)
            assertCleanup(seed.contact.id)
            assertEquals(2, intercepted.deleteCalls)
            expectedErrors = 1
        }
    }

    @OriginalCase("AdvertisementServiceTests::stop joins foreground deletion flush before finishing events(storeFails : Bool)")
    @Test fun stopJoinsBothSuccessfulAndFailedRealFlushesWithoutRetryingTheJoinedBatch() = runTest(scheduler) {
        for (fails in listOf(false, true)) withAdvert {
            val seed = seed(0xE4, "LocalContact")
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            if (fails) rejectDeletes()
            val deletion = holdDelete()
            foreground = true
            val flushing = async { service.handleReturnToForeground() }
            runCurrent()
            assertTrue(deletion.waiting)
            val stopping = async { stop(); service.finishEvents() }
            runCurrent()
            assertFalse(stopping.isCompleted)
            deletion.release()
            flushing.await()
            stopping.await()
            assertEquals(1, intercepted.deleteCalls)
            if (fails) {
                assertEquals(seed.contact, saved(seed.contact))
                assertTrue(cleanups().isEmpty())
                expectedErrors = 1
            } else {
                assertNull(saved(seed.contact))
                assertCleanup(seed.contact.id)
            }
        }
    }

    @OriginalCase("AdvertisementServiceTests::stop drains deletions queued during a foreground flush(nextRevived : Bool)")
    @Test fun stopDrainsTheNextBatchButPreservesItsRevivedContactAndHistory() = runTest(scheduler) {
        for (revived in listOf(false, true)) withAdvert {
            val first = seed(0xE5, "LocalContact")
            val next = seed(0xE6, "LocalContact", history = "keep if revived")
            service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.ContactDeleted(first.contact.publicKey))
            val deletion = holdDelete()
            val flushing = async { service.handleReturnToForeground() }
            runCurrent()
            assertTrue(deletion.waiting)
            send(MeshEvent.ContactDeleted(next.contact.publicKey))
            val foregroundHop = if (revived) holdForeground() else null
            if (revived) send(MeshEvent.Advertisement(next.contact.publicKey), handled = false)
            val stopping = async { stop(); service.finishEvents() }
            runCurrent()
            assertFalse(stopping.isCompleted)
            deletion.release()
            runCurrent()
            foregroundHop?.release()
            flushing.await()
            stopping.await()
            assertNull(saved(first.contact))
            assertEquals(2, intercepted.deleteCalls)
            val deleted = cleanups().flatMap { it.contactIDs }.toSet()
            assertEquals(if (revived) setOf(first.contact.id) else setOf(first.contact.id, next.contact.id), deleted)
            if (revived) assertNotNull(saved(next.contact)) else assertNull(saved(next.contact))
            assertHistory(next, present = revived)
        }
    }

    @OriginalCase("AdvertisementServiceTests::failed immediate 0x8F re-queues key for flush()")
    @Test fun anImmediateRealDeleteFailureRetainsTheRowUntilAFlushCanCommit() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xE3, "ImmediateRetry")
            foreground = true
            rejectDeletes()
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            assertEquals(seed.contact, saved(seed.contact))
            assertTrue(cleanups().isEmpty())
            assertEquals(1, errors.size)
            allowDeletes()
            service.handleReturnToForeground()
            runCurrent()
            assertNull(saved(seed.contact))
            assertCleanup(seed.contact.id)
            expectedErrors = 1
        }
    }

    @OriginalCase("AdvertisementServiceTests::failed 0x8F flush does not re-queue a key revived during the hop()")
    @Test fun aRevivalDuringTheFailedDeleteHopIsNotDeletedByALaterRetry() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xE2, "Subtract")
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            val deletion = holdDelete()
            foreground = true
            val flushing = async { service.handleReturnToForeground() }
            runCurrent()
            assertTrue(deletion.waiting)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            rejectBufferedFlush()
            deletion.release()
            flushing.await()
            allowDeletes()
            store.flushPendingRxLogEntries()
            assertEquals(1L, store.countRxLogEntries(RADIO_A))
            val attempts = intercepted.deleteCalls
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(attempts, intercepted.deleteCalls)
            assertNotNull(saved(seed.contact))
            assertTrue(cleanups().isEmpty())
            expectedErrors = 1
        }
    }

    private suspend fun TestScope.withAdvert(block: suspend Rig.() -> Unit) {
        val rig = Rig(this)
        try {
            rig.start()
            rig.block()
            rig.assertForeignRows()
        } finally {
            rig.close()
        }
        assertEquals(rig.expectedErrors, rig.errors.size, rig.errors.joinToString("\n"))
    }

    private data class Seed(val contact: ContactDTO, val foreign: ContactDTO, val history: MessageDTO?)

    private class Hold {
        private val released = CompletableDeferred<Unit>()
        var waiting = false
            private set
        suspend fun await() {
            waiting = true
            released.await()
            waiting = false
        }
        fun release() { released.complete(Unit) }
    }

    private class InterceptedStore(private val actual: PersistenceStoreProtocol) : PersistenceStoreProtocol by actual {
        var deletion: Hold? = null
        var deleteCalls = 0
        override suspend fun deleteContacts(
            radioId: RadioId, publicKeys: SnapshotSet<Bytes>, skippingPublicKeys: () -> SnapshotSet<Bytes>,
        ): SnapshotList<UUID> {
            deleteCalls++
            val held = deletion
            deletion = null
            held?.await()
            return actual.deleteContacts(radioId, publicKeys, skippingPublicKeys)
        }
    }

    private class Source : AdvertisingSessionOps, SessionEventStreaming {
        private val broadcaster = EventBroadcaster<MeshEvent>()
        val handled = mutableListOf<MeshEvent>()
        override val connectionState: Flow<ConnectionState> = flowOf(ConnectionState.CONNECTED)
        override fun events(): Flow<MeshEvent> = events(EventFilter { true })
        override fun events(filter: EventFilter): Flow<MeshEvent> {
            val subscription = broadcaster.subscribe()
            return flow {
                subscription.events.filter(filter::matches).collect {
                    emit(it)
                    handled += it
                }
            }
        }
        fun send(event: MeshEvent) { broadcaster.yield(event) }
        override suspend fun waitForEvent(filter: EventFilter, timeout: Double?): Nothing = unexpected()
        override suspend fun sendAdvertisement(flood: Boolean): Nothing = unexpected()
        override suspend fun setName(name: String): Nothing = unexpected()
        override suspend fun setCoordinates(latitude: Double, longitude: Double): Nothing = unexpected()
        override suspend fun getContact(publicKey: Bytes): MeshContact? = unexpected()
        private fun unexpected(): Nothing = error("Deletion coordination must not issue radio commands")
    }

    private inner class Rig(private val test: TestScope) {
        private val owner = SupervisorJob(test.coroutineContext[Job])
        private val scope = CoroutineScope(test.coroutineContext + owner)
        private val source = Source()
        val intercepted = InterceptedStore(store)
        private val seeds = mutableListOf<Seed>()
        private val holds = mutableListOf<Hold>()
        private var foregroundHop: Hold? = null
        private var stopped = false
        private var rejecting = false
        var foreground = false
        var expectedErrors = 0
        val errors = mutableListOf<String>()
        val events = mutableListOf<AdvertisementEvent>()
        val service = AdvertisementService(
            source, source, intercepted, scope, Duration.ZERO, Duration.ZERO, 5.milliseconds,
            object : AppStateProvider {
                override suspend fun isInForeground(): Boolean {
                    foregroundHop?.await()
                    return foreground
                }
            },
            object : AdvertisementClock {
                override fun now(): Duration = scheduler.currentTime.milliseconds
                override fun wallNow(): Instant = AT.plusMillis(scheduler.currentTime)
                override suspend fun sleepUntil(deadline: Duration) { delay(deadline - now()) }
            },
            AdvertisementLogSink { _, level, message -> if (level == DebugLogLevel.ERROR) errors += message },
        )
        private var listener: Job? = null
        suspend fun start() {
            val stream = service.events()
            listener = test.backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { stream.toList(events) }
            service.startEventMonitoring(RADIO_A)
            test.runCurrent()
        }
        suspend fun seed(byte: Int, name: String, history: String? = null): Seed {
            val key = Bytes(ByteArray(32) { (byte + it).toByte() })
            val frame = ContactFrame(key, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, name,
                1_700_000_000u, 0.0, 0.0, 1_700_000_100u)
            val result = store.saveContact(RADIO_A, frame)
            val contact = assertNotNull(store.fetchContact(EntityKey(RADIO_A, result.id)))
            val foreign = contact.copy(radioId = RADIO_B)
            store.saveContact(foreign)
            val message = history?.let { message(contactID = contact.id, text = it, direction = MessageDirection.INCOMING) }
            if (message != null) {
                store.saveMessage(message)
                store.saveMessage(message.copy(radioId = RADIO_B))
            }
            return Seed(contact, foreign, message).also { seeds += it }
        }
        fun send(event: MeshEvent, handled: Boolean = true) {
            val before = source.handled.size
            source.send(event)
            test.runCurrent()
            if (handled) assertEquals(before + 1, source.handled.size, "The production event handler must finish")
        }
        suspend fun saved(contact: ContactDTO) = store.fetchContact(EntityKey(contact.radioId, contact.id))
        fun cleanups() = events.filterIsInstance<AdvertisementEvent.ContactDeletedCleanup>()
        fun assertCleanup(vararg ids: UUID) {
            val cleanup = cleanups().single()
            assertEquals(ids.toSet(), cleanup.contactIDs.toSet())
            assertEquals(listOf<AdvertisementEvent>(cleanup, AdvertisementEvent.NodeStorageFullChanged(false),
                AdvertisementEvent.ContactUpdated), events)
        }
        suspend fun assertHistory(seed: Seed, present: Boolean) {
            val message = assertNotNull(seed.history)
            assertEquals(if (present) message else null, store.fetchMessage(EntityKey(RADIO_A, message.id)))
            assertEquals(if (present) listOf(message) else emptyList(),
                store.fetchMessages(EntityKey(RADIO_A, seed.contact.id), 10, 0).toList())
        }
        fun holdForeground(): Hold = Hold().also { foregroundHop = it; holds += it }
        fun holdDelete(): Hold = Hold().also { intercepted.deletion = it; holds += it }
        fun rejectDeletes() {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_advert_delete AFTER DELETE ON contacts " +
                "BEGIN SELECT RAISE(FAIL, 'controlled advertisement deletion failure'); END")
            rejecting = true
        }
        suspend fun rejectBufferedFlush() {
            store.saveRxLogEntry(rx())
            // The real pre-delete flush fails even though the revived contact would be skipped.
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_advert_delete AFTER INSERT ON rx_log_entries " +
                "BEGIN SELECT RAISE(FAIL, 'controlled advertisement flush failure'); END")
            rejecting = true
        }
        fun allowDeletes() {
            if (rejecting) {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_advert_delete")
                rejecting = false
            }
        }
        suspend fun stop() {
            if (!stopped) {
                service.stopEventMonitoring()
                stopped = true
                test.runCurrent()
            }
        }
        suspend fun assertForeignRows() {
            for (seed in seeds) {
                assertEquals(seed.foreign, saved(seed.foreign))
                seed.history?.let { assertEquals(it.copy(radioId = RADIO_B),
                    store.fetchMessage(EntityKey(RADIO_B, it.id))) }
            }
        }
        suspend fun close() {
            holds.forEach(Hold::release)
            stop()
            service.finishEvents()
            listener?.join()
            owner.cancelAndJoin()
            allowDeletes()
            for (seed in seeds) {
                store.deleteContacts(RADIO_A, SnapshotSet(listOf(seed.contact.publicKey)))
                store.deleteContacts(RADIO_B, SnapshotSet(listOf(seed.contact.publicKey)))
            }
        }
    }
}
