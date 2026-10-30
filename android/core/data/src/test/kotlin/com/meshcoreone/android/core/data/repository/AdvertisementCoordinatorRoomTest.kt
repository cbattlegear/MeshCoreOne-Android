// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ConnectionState
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.ContactFlags
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
    @OriginalCase("AdvertisementServiceTests::burst of adverts coalesces to one handler call()")
    @Test fun heldFirstRoundCoalescesFourMoreRealContactsIntoOneFollowupRound() = runTest(scheduler) {
        withAdvert {
            val seeds = (0 until 5).map { seed(0xA0 + it, "K${0xA0 + it}") }
            val hold = holdHandler()
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler {
                calls++
                if (calls == 1) hold.await()
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seeds.first().contact.publicKey))
            assertTrue(hold.waiting)
            for (seed in seeds.drop(1)) send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(1, calls)
            hold.release()
            runCurrent()
            assertEquals(2, calls)
            assertEquals(5, store.fetchDiscoveredNodes(RADIO_A).size)
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::min interval delays second delta sync after success()")
    @Test fun aSuccessfulRoundEnforcesTheExact150MillisecondMinimumInterval() = runTest(scheduler) {
        withAdvert(minInterval = 150.milliseconds) {
            val a = seed(0x71, "A")
            val b = seed(0x72, "B")
            val flags = mutableListOf<Boolean>()
            foreground = true
            service.setDeltaSyncHandler { flags += it; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(a.contact.publicKey))
            assertEquals(listOf(false), flags)
            send(MeshEvent.Advertisement(b.contact.publicKey))
            advanceTimeBy(149)
            runCurrent()
            assertEquals(listOf(false), flags)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(false, false), flags)
            assertEquals(setOf(a.contact.publicKey, b.contact.publicKey),
                store.fetchDiscoveredNodes(RADIO_A).map { it.publicKey }.toSet())
        }
    }

    @OriginalCase("AdvertisementServiceTests::armed round that backgrounds before fire does not invoke handler()")
    @Test fun aRoundThatBackgroundsBeforeIts200MillisecondDeadlineRetainsItsRealWork() = runTest(scheduler) {
        withAdvert(debounce = 200.milliseconds) {
            val seed = seed(0xB4, "Armed")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            foreground = false
            advanceTimeBy(200)
            runCurrent()
            assertEquals(0, calls)
            foreground = true
            service.handleReturnToForeground()
            advanceTimeBy(200)
            runCurrent()
            assertEquals(1, calls)
            assertNotNull(saved(seed.contact))
        }
    }

    @OriginalCase("AdvertisementServiceTests::nil handler leaves keys pending for later install()")
    @Test fun installingTheFirstHandlerDrainsAnAlreadyRecordedRealContact() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x61, "Pending")
            var calls = 0
            foreground = true
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(0, calls)
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            runCurrent()
            assertEquals(1, calls)
            assertNotNull(saved(seed.contact))
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::setDeltaSyncHandler nil then reinstall still syncs pending keys()")
    @Test fun explicitlyClearingThenReinstallingTheHandlerStillDrainsTheRecordedContact() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x8A, "Reinstall")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler(null)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(0, calls)
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            runCurrent()
            assertEquals(1, calls)
            assertNotNull(saved(seed.contact))
        }
    }

    @OriginalCase("AdvertisementServiceTests::handler failure remerges keys and retries()")
    @Test fun aFailedRoundRetainsTheUnknownKeyForRealPersistenceAndDiscoveryOnRetry() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0xF5, "Retry")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler {
                if (++calls == 1) AdvertContactSyncOutcome.FAILED else {
                    store.saveContact(RADIO_A, seed.contact.toContactFrame())
                    AdvertContactSyncOutcome.SYNCED
                }
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(2, calls)
            assertEquals("Retry", store.fetchContact(RADIO_A, seed.contact.publicKey)?.name)
            assertEquals(seed.contact.publicKey, store.fetchDiscoveredNodes(RADIO_A).single().publicKey)
            assertEquals(1, events.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().size)
        }
    }

    @OriginalCase("AdvertisementServiceTests::notReady outcome drops drained keys without reschedule or budget spend()")
    @Test fun notReadyDropsTheOldWorkWithoutDelayingTheNextAdvertByTheMinimumInterval() = runTest(scheduler) {
        withAdvert(minInterval = 150.milliseconds) {
            val seed = seed(0x57, "NotReady")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler {
                if (++calls == 1) AdvertContactSyncOutcome.NOT_READY else AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(1, calls)
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(1, calls)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(2, calls)
            assertEquals(0L, scheduler.currentTime)
            assertNotNull(saved(seed.contact))
        }
    }

    @OriginalCase("AdvertisementServiceTests::repeated failures stop the retry loop()")
    @Test fun theFailureCapStopsActualRoundsAndDropsTheirDrainedKey() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x53, "Flaky")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.FAILED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES, calls)
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES, calls)
            assertNotNull(saved(seed.contact))
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
            expectedErrors = 1
        }
    }

    @OriginalCase("AdvertisementServiceTests::busy rounds keep their keys past the failure cap()")
    @Test fun busyBackoffsBeyondTheFailureCapStillReachRealPersistenceAndDiscovery() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x54, "Late")
            val busyRounds = AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES + 2
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler {
                if (++calls <= busyRounds) AdvertContactSyncOutcome.BUSY else {
                    store.saveContact(RADIO_A, seed.contact.toContactFrame())
                    AdvertContactSyncOutcome.SYNCED
                }
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(busyRounds * 5L)
            runCurrent()
            assertEquals(busyRounds + 1, calls)
            assertEquals("Late", store.fetchContact(RADIO_A, seed.contact.publicKey)?.name)
            assertEquals(1, events.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().size)
        }
    }

    @OriginalCase("AdvertisementServiceTests::busy outcome does not stamp min-interval when backoff is non-zero()")
    @Test fun aBusyRoundUsesIts20MillisecondBackoffNotThe30SecondSuccessInterval() = runTest(scheduler) {
        withAdvert(minInterval = 30_000.milliseconds, busyBackoff = 20.milliseconds) {
            val seed = seed(0x87, "Busy")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler {
                if (++calls == 1) AdvertContactSyncOutcome.BUSY else AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(19)
            runCurrent()
            assertEquals(1, calls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, calls)
            assertNotNull(saved(seed.contact))
        }
    }

    @OriginalCase("AdvertisementServiceTests::contactDeleted removes pending key before sync runs()")
    @Test fun aDeleteBeforeDrainCannotBeRearmedWhenContactSyncFinishes() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x49, "Doomed")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            service.setSyncingContacts(true)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertTrue(AdvertisementEvent.ContactUpdated in events)
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            assertNull(saved(seed.contact))
            assertEquals(listOf(seed.contact.id), cleanups().single().contactIDs.toList())
            service.setSyncingContacts(false)
            runCurrent()
            assertEquals(0, calls)
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::reconcile skips deleted contact after mid-handler 0x8F()")
    @Test fun deletingAnUnpersistedContactDuringTheActualRoundCannotAnnounceIt() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x4A, "NeverPersisted")
            store.deleteContacts(RADIO_A, SnapshotSet(listOf(seed.contact.publicKey)))
            val handler = holdHandler()
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler {
                calls++
                handler.await()
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertTrue(handler.waiting)
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            handler.release()
            runCurrent()
            assertEquals(1, calls)
            assertNull(saved(seed.contact))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(AdvertisementEvent.ContactUpdated in events)
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::stopEventMonitoring stamps lastHeard for background 0x80()")
    @Test fun stopStampsTheBackgroundReceiveTimeIntoTheRealContactAndDiscoverRows() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xB7, "HeardOvernight")
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(seed.contact, saved(seed.contact))
            stop()
            assertEquals(seed.contact.copy(lastHeardTimestamp = 1_700_000_000u), saved(seed.contact))
            val node = store.fetchDiscoveredNodes(RADIO_A).single()
            assertEquals(seed.contact.publicKey, node.publicKey)
            assertEquals(AT, node.lastHeard)
        }
    }

    @OriginalCase("AdvertisementServiceTests::stop during isInForeground hop does not invoke handler()")
    @Test fun stopInvalidatesTheRoundParkedAtItsForegroundCheckBeforeCallingTheHandler() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xD0, "ParkedRound")
            var calls = 0
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(0, calls)
            foreground = true
            val hop = holdForeground()
            service.handleReturnToForeground()
            runCurrent()
            assertTrue(hop.waiting)
            stop()
            hop.release()
            runCurrent()
            assertEquals(0, calls)
            assertEquals(seed.contact.copy(lastHeardTimestamp = 1_700_000_000u), saved(seed.contact))
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::stop during handler still stamps drained lastHeard()")
    @Test fun aCancelledHeldHandlerStillStampsItsDrainedReceiveTimeWithoutAnnouncingAfterStop() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xD1, "DrainedHeard")
            val handler = holdHandler()
            service.setDeltaSyncHandler {
                withContext(NonCancellable) { handler.await() }
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(seed.contact, saved(seed.contact))
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertTrue(handler.waiting)
            stop()
            handler.release()
            runCurrent()
            assertEquals(seed.contact.copy(lastHeardTimestamp = 1_700_000_000u), saved(seed.contact))
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

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

    @OriginalCase("AdvertisementServiceTests::materializeContactForPendingAdvert creates contact for unique pending key()")
    @Test fun uniquePendingAdvertMaterializesAnActualContactAndRemainsEligibleForDeltaSync() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x8D, "DebounceNode")
            stubContact(seed.contact.publicKey, "DebounceNode")
            foreground = true
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertNull(saved(seed.contact))
            val contact = assertNotNull(service.materializeContactForPendingAdvert(seed.contact.publicKey.prefix(6), RADIO_A))
            assertEquals(seed.contact.copy(id = contact.id, lastHeardTimestamp = AT.epochSecond.toUInt()), contact)
            assertEquals(contact, store.fetchContactByPrefix(RADIO_A, seed.contact.publicKey.prefix(6)))
            assertEquals(listOf(seed.contact.publicKey), requestedContacts())
            runCurrent()
            assertEquals(listOf<AdvertisementEvent>(AdvertisementEvent.ContactUpdated), events)
            var calls = 0
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            runCurrent()
            assertEquals(1, calls, "Materialization must not consume the pending delta key")
            assertEquals(contact, saved(contact))
            assertEquals(contact.name, store.fetchDiscoveredNodes(RADIO_A).single().name)
        }
    }

    @OriginalCase("AdvertisementServiceTests::materializeContactForPendingAdvert returns nil without pending match()")
    @Test fun materializationWithoutAPendingMatchDoesNotQueryTheRadioOrInventRoomRows() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x8E, "NoPending")
            stubContact(seed.contact.publicKey, "NoPending")
            assertNull(service.materializeContactForPendingAdvert(seed.contact.publicKey.prefix(6), RADIO_A))
            assertTrue(requestedContacts().isEmpty())
            assertNull(saved(seed.contact))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    @OriginalCase("AdvertisementServiceTests::materializeContactForPendingAdvert returns nil on multi-match prefix()")
    @Test fun ambiguousPendingPrefixesCannotMaterializeEitherActualRoomContact() = runTest(scheduler) {
        withAdvert {
            val first = Bytes(ByteArray(32) { if (it == 0) 0xAB.toByte() else (0x8F + it).toByte() })
            val second = Bytes(ByteArray(32) { if (it == 0) 0xAB.toByte() else (0x90 + it).toByte() })
            assertNotEquals(first, second)
            send(MeshEvent.Advertisement(first))
            send(MeshEvent.Advertisement(second))
            assertNull(service.materializeContactForPendingAdvert(Bytes.of(0xAB), RADIO_A))
            assertTrue(requestedContacts().isEmpty())
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    @OriginalCase("AdvertisementServiceTests::materializeContactForPendingAdvert still works while backgrounded()")
    @Test fun backgroundMaterializationCommitsTheContactWithoutInvokingDeltaSync() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0xB5, "DebounceNode")
            stubContact(seed.contact.publicKey, "DebounceNode")
            var calls = 0
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            val contact = assertNotNull(service.materializeContactForPendingAdvert(seed.contact.publicKey.prefix(6), RADIO_A))
            assertEquals(seed.contact.copy(id = contact.id, lastHeardTimestamp = AT.epochSecond.toUInt()), contact)
            assertEquals(contact, store.fetchContactByPrefix(RADIO_A, seed.contact.publicKey.prefix(6)))
            assertEquals(listOf(seed.contact.publicKey), requestedContacts())
            assertEquals(0, calls)
            runCurrent()
            assertEquals(listOf<AdvertisementEvent>(AdvertisementEvent.ContactUpdated), events)
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(1, calls)
            assertEquals(contact, saved(contact))
        }
    }

    @OriginalCase("AdvertisementServiceTests::adopting orphaned DMs emits conversationsChanged()")
    @Test fun realOrphanAdoptionCreatesTheConversationAndItsConsumerInvalidation() = runTest(scheduler) {
        withAdvert { assertAdoption(0x8E, "AdoptedDM", "orphan needs conversation signal", 1_700_000_500u) }
    }

    @OriginalCase("AdvertisementServiceTests::adopting orphaned DMs emits orphanDirectMessagesAdopted with the contact()")
    @Test fun realOrphanAdoptionHandsTheCommittedContactToTheNotificationConsumer() = runTest(scheduler) {
        withAdvert { assertAdoption(0x9E, "AdoptedDM", "orphan awaiting notification", 1_700_000_600u) }
    }

    @OriginalCase("AdvertisementServiceTests::snapshot fetch failure still adopts orphaned DMs for drained keys()")
    @Test fun aFailedPreRoundSnapshotCannotPreventActualRoomOrphanAdoption() = runTest(scheduler) {
        withAdvert {
            rejectSnapshot()
            assertAdoption(0x8C, "LateNode", "orphan before delta", 1_700_000_400u)
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
            expectedErrors = 1
        }
    }

    @OriginalCase("AdvertisementServiceTests::snapshot fetch failure does not invent new-contact notifications()")
    @Test fun aFailedPreRoundSnapshotDoesNotAnnounceAKnownRoomContactAsNew() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x8B, "Known")
            rejectSnapshot()
            foreground = true
            service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(seed.contact.copy(lastHeardTimestamp = AT.epochSecond.toUInt()), saved(seed.contact))
            assertEquals("Known", store.fetchDiscoveredNodes(RADIO_A).single().name)
            assertTrue(events.any { it == AdvertisementEvent.ContactUpdated })
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
            expectedErrors = 1
        }
    }

    @OriginalCase("AdvertisementServiceTests::delta round with no adoption does not emit conversationsChanged()")
    @Test fun aSuccessfulActualRoomRoundWithoutOrphansDoesNotInventConversationChanges() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x8F, "NoOrphans")
            foreground = true
            service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertNotNull(saved(seed.contact))
            assertEquals("NoOrphans", store.fetchDiscoveredNodes(RADIO_A).single().name)
            assertTrue(events.any { it == AdvertisementEvent.ContactUpdated })
            assertTrue(events.none { it == AdvertisementEvent.ConversationsChanged })
            assertTrue(events.none { it is AdvertisementEvent.OrphanDirectMessagesAdopted })
            assertTrue(store.fetchConversations(RADIO_A).isEmpty())
        }
    }

    @OriginalCase("AdvertisementServiceTests::unknown key advert yields newContactDiscovered once after handler persists()")
    @Test fun anUnknownContactIsAnnouncedOnceAndItsSecondActualFrameUpdatesCoordinates() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0xC2, "NewNode")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler {
                calls++
                store.saveContact(RADIO_A, contactFrame(seed.contact.publicKey, "NewNode").copy(
                    latitude = 9.0 + calls, longitude = 19.0 + calls))
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            val first = assertNotNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
            assertEquals(10.0, first.latitude)
            assertEquals(20.0, first.longitude)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(2, calls)
            assertEquals(first.copy(latitude = 11.0, longitude = 21.0), store.fetchContact(RADIO_A, seed.contact.publicKey))
            val announced = events.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().single()
            assertEquals("NewNode", announced.name)
            assertEquals(first.id, announced.contactID)
            assertEquals(ContactType.CHAT, announced.contactType)
            assertEquals(11.0, store.fetchDiscoveredNodes(RADIO_A).single().latitude)
        }
    }

    @OriginalCase("AdvertisementServiceTests::0x8A then 0x80 for same key notifies from each path()")
    @Test fun discoverOnlyNewContactAndLaterPersistedAdvertAnnounceTheirDistinctPaths() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0xD3, "Manual")
            val mesh = stubContact(seed.contact.publicKey, "Manual")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler {
                calls++
                store.saveContact(RADIO_A, contactFrame(seed.contact.publicKey, "Manual"))
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.NewContact(mesh))
            assertNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
            assertEquals("Manual", store.fetchDiscoveredNodes(RADIO_A).single().name)
            assertEquals(1, events.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().size)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(1, calls)
            assertNotNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
            assertEquals(listOf("Manual", "Manual"),
                events.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().map { it.name })
            assertTrue(events.count { it == AdvertisementEvent.ContactUpdated } >= 2)
        }
    }

    @OriginalCase("AdvertisementServiceTests::pathUpdate only triggers handler without Discover row()")
    @Test fun anActualPathOnlyRoundNeverInventsAContactOrDiscoverRow() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0xE4, "Path only")
            val flags = mutableListOf<Boolean>()
            foreground = true
            service.setDeltaSyncHandler { flags += it; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.PathUpdate(seed.contact.publicKey))
            assertEquals(listOf(false, true), flags)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(requestedContacts().isEmpty())
            assertTrue(events.any { it == AdvertisementEvent.ContactUpdated })
        }
    }

    @OriginalCase("AdvertisementServiceTests::isSyncingContacts defers handler until cleared()")
    @Test fun aContactSyncClaimDefersTheActualPendingAdvertUntilRelease() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x16, "Deferred")
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            service.setSyncingContacts(true)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(50)
            runCurrent()
            assertEquals(0, calls)
            assertEquals(seed.contact.copy(lastHeardTimestamp = AT.epochSecond.toUInt()), saved(seed.contact))
            assertEquals(seed.contact.publicKey, store.fetchDiscoveredNodes(RADIO_A).single().publicKey)
            service.setSyncingContacts(false)
            runCurrent()
            assertEquals(1, calls)
            assertEquals("Deferred", store.fetchDiscoveredNodes(RADIO_A).single().name)
        }
    }

    @OriginalCase("AdvertisementServiceTests::unknown key missing after success escalates to fullRefetch once()")
    @Test fun anActuallyMissingContactEscalatesRetriesTheFullRefetchThenStops() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x38, "Missing")
            val flags = mutableListOf<Boolean>()
            foreground = true
            service.setDeltaSyncHandler {
                flags += it
                if (flags.size == 2) AdvertContactSyncOutcome.FAILED else AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(50)
            runCurrent()
            assertEquals(listOf(false, true, true), flags)
            assertNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::background 0x80 records key and does not invoke deltaSyncHandler()")
    @Test fun aBackgroundAdvertDoesNotTouchTheActualContactUntilItsPendingRecencyIsStopped() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xB0, "Known")
            var calls = 0
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(80)
            runCurrent()
            assertEquals(0, calls)
            assertEquals(seed.contact, saved(seed.contact))
            assertTrue(events.isEmpty())
            stop()
            assertEquals(seed.contact.copy(lastHeardTimestamp = AT.epochSecond.toUInt()), saved(seed.contact))
            assertEquals(0, calls)
        }
    }

    @OriginalCase("AdvertisementServiceTests::handleReturnToForeground drains keys recorded while backgrounded()")
    @Test fun returningToForegroundDrainsTheActualBackgroundKeyWithoutInventingItsMissingRow() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0xB2, "Background pending")
            val flags = mutableListOf<Boolean>()
            service.setDeltaSyncHandler { flags += it; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertTrue(flags.isEmpty())
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(listOf(false, true), flags)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
        }
    }

    @OriginalCase("AdvertisementServiceTests::background 0x81 records path key and does not invoke handler()")
    @Test fun aBackgroundPathUpdateWaitsForForegroundWithoutCreatingDiscover() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0xB3, "Background path")
            val flags = mutableListOf<Boolean>()
            service.setDeltaSyncHandler { flags += it; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.PathUpdate(seed.contact.publicKey))
            advanceTimeBy(80)
            runCurrent()
            assertTrue(flags.isEmpty())
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(listOf(false, true), flags)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(requestedContacts().isEmpty())
        }
    }

    @OriginalCase("AdvertisementServiceTests::setSyncingContacts false while backgrounded does not invoke handler()")
    @Test fun releasingAContactSyncClaimWhileBackgroundedCannotRunTheActualPendingAdvert() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0xB6, "Background sync")
            val flags = mutableListOf<Boolean>()
            service.setDeltaSyncHandler { flags += it; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            service.setSyncingContacts(true)
            service.setSyncingContacts(false)
            advanceTimeBy(80)
            runCurrent()
            assertTrue(flags.isEmpty())
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(events.isEmpty())
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(listOf(false, true), flags)
        }
    }

    @OriginalCase("AdvertisementServiceTests::empty schedule with no pending work does not call the handler()")
    @Test fun publicSchedulingEntryPointsWithNoWorkCannotInvokeTheHandlerOrInventRows() = runTest(scheduler) {
        withAdvert {
            var calls = 0
            foreground = true
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            service.setSyncingContacts(true)
            service.setSyncingContacts(false)
            service.handleReturnToForeground()
            advanceTimeBy(80)
            runCurrent()
            assertEquals(0, calls)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.isEmpty())
        }
    }

    @OriginalCase("AdvertisementServiceTests::capped failure round restores pathSyncPending()")
    @Test fun aFailureCapPreservesThePathWorkAfterItsAdvertKeysAreDropped() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x86, "Cap")
            val flags = mutableListOf<Boolean>()
            val cap = AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES
            foreground = true
            service.setDeltaSyncHandler {
                flags += it
                if (flags.size <= cap) AdvertContactSyncOutcome.FAILED else {
                    store.saveContact(seed.contact.copy(outPathLength = 1u, outPath = Bytes.of(1),
                        lastModified = seed.contact.lastModified + 1u, lastHeardTimestamp = AT.epochSecond.toUInt()))
                    AdvertContactSyncOutcome.SYNCED
                }
            }
            service.setSyncingContacts(true)
            send(MeshEvent.PathUpdate(seed.contact.publicKey))
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            service.setSyncingContacts(false)
            runCurrent()
            assertEquals(List(cap) { false }, flags)
            val touched = store.fetchDiscoveredNodes(RADIO_A).toList()
            assertEquals(listOf(seed.contact.publicKey), touched.map { it.publicKey })
            service.setSyncingContacts(true)
            service.setSyncingContacts(false)
            runCurrent()
            assertEquals(List(cap + 1) { false }, flags)
            assertEquals(seed.contact.copy(outPathLength = 1u, outPath = Bytes.of(1),
                lastModified = seed.contact.lastModified + 1u, lastHeardTimestamp = AT.epochSecond.toUInt()),
                saved(seed.contact))
            assertEquals(touched, store.fetchDiscoveredNodes(RADIO_A).toList(),
                "Only path work, not the dropped advert keys, survives")
            expectedErrors = 1
        }
    }

    @OriginalCase("AdvertisementServiceTests::capped failure round restores escalateToFullRefetch()")
    @Test fun theFailureCapLeavesAnObservableOwedFullRefetchWithoutAdvertKeys() = runTest(scheduler) {
        exerciseOwedFullRefetch(0x88, rewire = false)
    }

    @OriginalCase("AdvertisementServiceTests::setSyncingContacts false re-arms owed full refetch with empty pending keys()")
    @Test fun endingContactSyncRearmsTheActualOwedFullRefetchAfterTheFailureCap() = runTest(scheduler) {
        exerciseOwedFullRefetch(0x91, rewire = false)
    }

    @OriginalCase("AdvertisementServiceTests::setDeltaSyncHandler re-arms owed full refetch with empty pending keys()")
    @Test fun rewiringTheHandlerRearmsTheActualOwedFullRefetchAfterTheFailureCap() = runTest(scheduler) {
        exerciseOwedFullRefetch(0x92, rewire = true)
    }

    private suspend fun TestScope.exerciseOwedFullRefetch(byte: Int, rewire: Boolean) {
        withAdvert {
            val seed = unknown(byte, "Missing")
            val flags = mutableListOf<Boolean>()
            val cap = AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES
            foreground = true
            val handler: AdvertDeltaSyncHandler = {
                flags += it
                if (flags.size in 2..cap + 1) AdvertContactSyncOutcome.FAILED else AdvertContactSyncOutcome.SYNCED
            }
            service.setDeltaSyncHandler(handler)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(listOf(false) + List(cap) { true }, flags)
            advanceTimeBy(80)
            runCurrent()
            assertEquals(cap + 1, flags.size)
            if (rewire) {
                service.setDeltaSyncHandler(null)
                service.setDeltaSyncHandler(handler)
            } else {
                service.setSyncingContacts(true)
                service.setSyncingContacts(false)
            }
            runCurrent()
            assertEquals(listOf(false) + List(cap + 1) { true }, flags)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
            expectedErrors = 1
        }
    }

    @OriginalCase("AdvertisementServiceTests::fresh advert during the capped failing round re-arms and drains()")
    @Test fun aFreshAdvertDuringTheHeldCapRoundRunsWithoutAnotherExternalWake() = runTest(scheduler) {
        withAdvert {
            val first = seed(0x71, "First")
            val fresh = unknown(0x72, "Fresh")
            val hold = holdHandler()
            val cap = AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES
            val flags = mutableListOf<Boolean>()
            foreground = true
            service.setDeltaSyncHandler {
                flags += it
                if (flags.size == cap) hold.await()
                if (flags.size <= cap) AdvertContactSyncOutcome.FAILED else AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(first.contact.publicKey))
            assertTrue(hold.waiting)
            send(MeshEvent.Advertisement(fresh.contact.publicKey))
            assertEquals(cap, flags.size)
            hold.release()
            runCurrent()
            assertEquals(List(cap + 1) { false } + true, flags)
            assertEquals(first.contact.copy(lastHeardTimestamp = AT.epochSecond.toUInt()), saved(first.contact))
            assertNull(store.fetchContact(RADIO_A, fresh.contact.publicKey))
            advanceTimeBy(80)
            runCurrent()
            assertEquals(cap + 2, flags.size)
            expectedErrors = 1
        }
    }

    @OriginalCase("AdvertisementServiceTests::finishRound with stale generation leaves the new task registered()")
    @Test fun aReleasedCancelledRoundCannotClearItsHeldReplacementGeneration() = runTest(scheduler) {
        withAdvert(debounce = 30_000.milliseconds) {
            val seed = seed(0x89, "Gen")
            val old = holdHandler()
            val replacement = holdHandler()
            var calls = 0
            foreground = true
            val handler: AdvertDeltaSyncHandler = {
                when (++calls) {
                    1 -> withContext(NonCancellable) { old.await() }
                    2 -> withContext(NonCancellable) { replacement.await() }
                }
                AdvertContactSyncOutcome.SYNCED
            }
            service.setDeltaSyncHandler(handler)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(old.waiting)
            stop()
            restart()
            service.setDeltaSyncHandler(handler)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue(replacement.waiting)
            old.release()
            runCurrent()
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(2, calls, "A stale finish must not allow a second live round beside the held replacement")
            replacement.release()
            runCurrent()
            advanceTimeBy(29_999)
            runCurrent()
            assertEquals(2, calls)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, calls)
            assertEquals("Gen", store.fetchDiscoveredNodes(RADIO_A).single().name)
            assertEquals(seed.contact.copy(lastHeardTimestamp = AT.plusSeconds(60).epochSecond.toUInt()), saved(seed.contact))
        }
    }

    @OriginalCase("AdvertisementServiceTests::known contact advert bumps lastHeard without getContact()")
    @Test fun aKnownAdvertRefreshesTheRealContactAndDiscoverWithoutARadioLookup() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0xB1, "Known")
            store.upsertDiscoveredNode(RADIO_A, seed.contact.toContactFrame())
            foreground = true
            var calls = 0
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            advanceTimeBy(1_000)
            clock.now = AT.plusSeconds(1)
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertEquals(seed.contact.copy(lastHeardTimestamp = AT.plusSeconds(1).epochSecond.toUInt()), saved(seed.contact))
            assertEquals(AT.plusSeconds(1), store.fetchDiscoveredNodes(RADIO_A).single().lastHeard)
            assertEquals(1, calls)
            assertTrue(requestedContacts().isEmpty())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::pathUpdate for known contact escalates when incremental leaves lastModified unchanged()")
    @Test fun anUnchangedIncrementalWatermarkTriggersTheRealPruneFreePathRefresh() = runTest(scheduler) {
        exerciseKnownPathRefresh(refreshIncremental = false)
    }

    @OriginalCase("AdvertisementServiceTests::pathUpdate for known contact does not escalate when incremental refreshes lastModified()")
    @Test fun anActuallyRefreshedIncrementalPathDoesNotFetchAgain() = runTest(scheduler) {
        exerciseKnownPathRefresh(refreshIncremental = true)
    }

    private suspend fun TestScope.exerciseKnownPathRefresh(refreshIncremental: Boolean) {
        withAdvert {
            val seed = seed(if (refreshIncremental) 0xA2 else 0xA1, "Relay")
            val old = seed.contact.copy(typeRawValue = ContactType.REPEATER.rawValue,
                outPathLength = if (refreshIncremental) 1u else 2u,
                outPath = if (refreshIncremental) Bytes.of(0x11) else Bytes.of(1, 2),
                latitude = if (refreshIncremental) 1.0 else 10.0, longitude = if (refreshIncremental) 2.0 else 20.0)
            store.saveContact(old)
            val expected = old.copy(outPathLength = 2u,
                outPath = if (refreshIncremental) Bytes.of(0xAA, 0xBB) else Bytes.of(3, 4),
                lastModified = old.lastModified + if (refreshIncremental) 10u else 50u,
                latitude = if (refreshIncremental) 1.0 else 10.5, longitude = if (refreshIncremental) 2.0 else 20.5)
            val flags = mutableListOf<Boolean>()
            foreground = true
            service.setDeltaSyncHandler {
                flags += it
                if (refreshIncremental || it) store.saveContact(RADIO_A, expected.toContactFrame())
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.PathUpdate(old.publicKey))
            advanceTimeBy(80)
            runCurrent()
            assertEquals(if (refreshIncremental) listOf(false) else listOf(false, true), flags)
            assertEquals(expected, saved(old))
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(requestedContacts().isEmpty())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::pathUpdate deferred by contact sync still runs()")
    @Test fun aPathOnlyClaimDefersAndThenRunsWithoutInventingAnAdvert() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x63, "DeferredPath")
            val flags = mutableListOf<Boolean>()
            foreground = true
            service.setDeltaSyncHandler { flags += it; AdvertContactSyncOutcome.SYNCED }
            service.setSyncingContacts(true)
            send(MeshEvent.PathUpdate(seed.contact.publicKey))
            assertTrue(flags.isEmpty())
            service.setSyncingContacts(false)
            runCurrent()
            assertEquals(listOf(false, true), flags)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::re-created contact with a surviving Discover row notifies again()")
    @Test fun aSurvivingDiscoverDoesNotHideTheActualRecreatedContactFromConsumers() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x74, "Returning")
            store.upsertDiscoveredNode(RADIO_A, seed.contact.toContactFrame())
            foreground = true
            var calls = 0
            service.setDeltaSyncHandler {
                calls++
                store.saveContact(RADIO_A, seed.contact.toContactFrame())
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(40)
            runCurrent()
            val contact = assertNotNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
            assertEquals(seed.contact.copy(id = contact.id, lastHeardTimestamp = AT.epochSecond.toUInt()), contact)
            assertEquals("Returning", store.fetchDiscoveredNodes(RADIO_A).single().name)
            assertEquals(listOf("Returning"), events.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().map { it.name })
            assertEquals(1, calls)
        }
    }

    @OriginalCase("AdvertisementServiceTests::advert retries touch once then drops key when both fail()")
    @Test fun twoTypedTouchFailuresDropTheUnknownAdvertWithoutInventingAStoreResult() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x85, "Unknown")
            intercepted.touchFailure = PersistenceStoreException(PersistenceStoreError.FetchFailed("controlled advert touch"))
            foreground = true
            var calls = 0
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(80)
            runCurrent()
            assertEquals(2, intercepted.touchCalls)
            assertEquals(0, calls)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.isEmpty())
            expectedErrors = 2
        }
    }

    @OriginalCase("AdvertisementServiceTests::touch failure does not announce a known contact as new()")
    @Test fun twoActualSqlTouchRollbacksCannotAnnounceALongKnownContactAsNew() = runTest(scheduler) {
        withAdvert {
            val seed = seed(0x51, "LongKnown")
            rejectTouches()
            foreground = true
            var calls = 0
            service.setDeltaSyncHandler { calls++; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(60)
            runCurrent()
            assertEquals(2, intercepted.touchCalls)
            assertEquals(seed.contact, saved(seed.contact))
            assertEquals(0, calls)
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
            expectedErrors = 2
        }
    }

    @OriginalCase("AdvertisementServiceTests::contact lookup failure does not escalate to full refetch()")
    @Test fun aTypedLookupFailureCannotTurnAnActualIncrementalIntoAFullRefetch() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x96, "Unavailable")
            intercepted.lookupFailure = PersistenceStoreException(PersistenceStoreError.FetchFailed("controlled contact lookup"))
            val flags = mutableListOf<Boolean>()
            foreground = true
            service.setDeltaSyncHandler { flags += it; AdvertContactSyncOutcome.SYNCED }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            advanceTimeBy(80)
            runCurrent()
            assertEquals(listOf(false), flags)
            assertTrue(store.fetchContacts(RADIO_A).isEmpty())
            assertTrue(store.fetchDiscoveredNodes(RADIO_A).isEmpty())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
            expectedErrors = 3
        }
    }

    @OriginalCase("AdvertisementServiceTests::dropped background adverts retain phone recency(outcome : AdvertContactSyncOutcome)")
    @Test fun bothDroppedBackgroundOutcomesKeepRealPhoneRecencyInsteadOfTheOldRadioWatermark() = runTest(scheduler) {
        for (outcome in listOf(AdvertContactSyncOutcome.NOT_READY, AdvertContactSyncOutcome.FAILED)) withAdvert {
            val seed = seed(0x58, "Dropped")
            val receivedAt = AT.plusSeconds(86_400 - 3_600)
            wallOffsetMillis = (86_400 - 3_600) * 1_000L
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            val cutoff = receivedAt.epochSecond.toUInt() - 1u
            assertTrue(assertNotNull(saved(seed.contact)).matchesStaleNodePrune(cutoff))
            val rounds = if (outcome == AdvertContactSyncOutcome.FAILED) AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES else 1
            var calls = 0
            service.setDeltaSyncHandler { calls++; outcome }
            wallOffsetMillis = 86_400_000
            foreground = true
            service.handleReturnToForeground()
            runCurrent()
            assertEquals(rounds, calls)
            val after = assertNotNull(saved(seed.contact))
            assertFalse(after.matchesStaleNodePrune(cutoff))
            assertEquals(seed.contact.lastModified, after.lastModified)
            assertEquals(if (outcome == AdvertContactSyncOutcome.NOT_READY) receivedAt.epochSecond.toUInt()
                else AT.plusSeconds(86_400).epochSecond.toUInt(), after.lastHeardTimestamp)
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
            expectedErrors = if (outcome == AdvertContactSyncOutcome.FAILED) 1 else 0
        }
    }

    @OriginalCase("AdvertisementServiceTests::fresh lastHeard protects contact with old lastModified from prune()")
    @Test fun actualStoredPhoneRecencyProtectsAnOldRadioWatermarkButNotTheStaleControl() = runTest(scheduler) {
        withAdvert {
            val fresh = seed(0x5A, "StaleRadio").contact.copy(lastModified = 1_000_000u,
                lastHeardTimestamp = AT.epochSecond.toUInt())
            val stale = seed(0x5B, "TrulyStale").contact.copy(lastModified = 1_000_000u,
                lastHeardTimestamp = 1_000_000u)
            store.saveContact(fresh)
            store.saveContact(stale)
            val cutoff = AT.minusSeconds(30 * 86_400L).epochSecond.toUInt()
            val fetched = assertNotNull(saved(fresh))
            assertEquals(fresh, fetched)
            assertTrue(fetched.lastModified < cutoff)
            assertFalse(fetched.matchesStaleNodePrune(cutoff))
            assertTrue(assertNotNull(saved(stale)).matchesStaleNodePrune(cutoff))
        }
    }

    @OriginalCase("AdvertisementServiceTests::favorite contact with stale recency does not match stale-node prune()")
    @Test fun theActuallyStoredFavoriteRemainsProtectedEvenWithBothOldTimestamps() = runTest(scheduler) {
        withAdvert {
            val favorite = seed(0x5D, "FavoriteStale").contact.copy(lastModified = 1_000_000u,
                lastHeardTimestamp = 1_000_000u, isFavorite = true)
            store.saveContact(favorite)
            val fetched = assertNotNull(saved(favorite))
            val cutoff = AT.minusSeconds(30 * 86_400L).epochSecond.toUInt()
            assertEquals(favorite, fetched)
            assertTrue(fetched.recencyTimestamp < cutoff)
            assertFalse(fetched.matchesStaleNodePrune(cutoff))
        }
    }

    @OriginalCase("AdvertisementServiceTests::pathUpdate cancel after save does not cascade-delete messages()")
    @Test fun rollbackAfterTheRadioDeletePreservesTheActualMessageThatArrivedWithTheLateSave() = runTest(scheduler) {
        exerciseDeletedRound(0xD4, "HasMessages", history = "keep me")
    }

    @OriginalCase("AdvertisementServiceTests::rolled back key does not escalate to a full refetch()")
    @Test fun theActuallyRolledBackGhostCannotTriggerAnotherRadioRefetch() = runTest(scheduler) {
        exerciseDeletedRound(0x52, "Ghost", history = null)
    }

    @OriginalCase("AdvertisementServiceTests::reconcile skips a contact the radio deleted this round()")
    @Test fun anExistingMessageProtectedGhostStillCannotAcquireDiscoverOrNewContactEvents() = runTest(scheduler) {
        exerciseDeletedRound(0x50, "Ghost", history = "guarded rollback", originallyKnown = true)
    }

    private suspend fun TestScope.exerciseDeletedRound(byte: Int, name: String, history: String?, originallyKnown: Boolean = false) {
        withAdvert {
            val seed = if (originallyKnown) seed(byte, "Doomed") else unknown(byte, name)
            val hold = holdHandler()
            val flags = mutableListOf<Boolean>()
            var inserted: ContactDTO? = null
            var insertedMessage: MessageDTO? = null
            foreground = true
            service.setDeltaSyncHandler {
                flags += it
                hold.await()
                store.saveContact(RADIO_A, seed.contact.toContactFrame().copy(name = name))
                val contact = assertNotNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
                inserted = contact
                if (history != null) {
                    insertedMessage = message(contactID = contact.id, text = history, direction = MessageDirection.INCOMING)
                    store.saveMessage(assertNotNull(insertedMessage))
                }
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertTrue(hold.waiting)
            val beforeDeletionDiscover = store.fetchDiscoveredNodes(RADIO_A).toList()
            send(MeshEvent.ContactDeleted(seed.contact.publicKey))
            assertNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
            hold.release()
            runCurrent()
            advanceTimeBy(80)
            runCurrent()
            assertEquals(listOf(false), flags)
            if (history == null) {
                assertNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
                assertTrue(store.fetchMessages(EntityKey(RADIO_A, assertNotNull(inserted).id), 10, 0).isEmpty())
            } else {
                val contact = assertNotNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
                assertEquals(assertNotNull(inserted), contact)
                assertEquals(listOf(assertNotNull(insertedMessage)), store.fetchMessages(EntityKey(RADIO_A, contact.id), 10, 0).toList())
            }
            assertEquals(beforeDeletionDiscover, store.fetchDiscoveredNodes(RADIO_A).toList())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    @OriginalCase("AdvertisementServiceTests::contact deleted after the commit returns stays tracked for the round()")
    @Test fun anActualDeleteAfterAnUnrelatedCommitStillCommitsCleanupAndCannotAnnounceTheDeletedPeer() = runTest(scheduler) {
        withAdvert {
            val synced = unknown(0x4E, "Synced")
            val deleted = seed(0x4F, "Doomed")
            foreground = true
            var calls = 0
            service.setDeltaSyncHandler {
                calls++
                store.saveContact(RADIO_A, synced.contact.toContactFrame())
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(synced.contact.publicKey))
            assertEquals(1, calls)
            assertNotNull(store.fetchContact(RADIO_A, synced.contact.publicKey))
            send(MeshEvent.ContactDeleted(deleted.contact.publicKey))
            advanceTimeBy(80)
            runCurrent()
            assertNull(saved(deleted.contact))
            assertEquals(listOf(deleted.contact.id), cleanups().single().contactIDs.toList())
            assertEquals(listOf(synced.contact.publicKey), store.fetchDiscoveredNodes(RADIO_A).map { it.publicKey })
            assertEquals(listOf("Synced"), events.filterIsInstance<AdvertisementEvent.NewContactDiscovered>().map { it.name })
            assertEquals(1, calls)
        }
    }

    @OriginalCase("AdvertisementServiceTests::teardown mid-handler prevents reconcile writes()")
    @Test fun aLateCommittedContactCannotReconcileAfterStopWhileTheMonitorIsParked() = runTest(scheduler) {
        withAdvert {
            val seed = unknown(0x27, "Late")
            val parked = unknown(0x28, "Parked")
            val handler = holdHandler()
            foreground = true
            service.setDeltaSyncHandler {
                withContext(NonCancellable) {
                    handler.await()
                    store.saveContact(RADIO_A, seed.contact.toContactFrame())
                }
                AdvertContactSyncOutcome.SYNCED
            }
            send(MeshEvent.Advertisement(seed.contact.publicKey))
            assertTrue(handler.waiting)
            val hop = holdForeground()
            send(MeshEvent.Advertisement(parked.contact.publicKey), handled = false)
            assertTrue(hop.waiting)
            val stopping = async { stop() }
            runCurrent()
            assertFalse(stopping.isCompleted)
            handler.release()
            runCurrent()
            assertNotNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
            val heardAfterStop = store.fetchDiscoveredNodes(RADIO_A).single()
            assertEquals(seed.contact.publicKey, heardAfterStop.publicKey)
            assertEquals(AT, heardAfterStop.lastHeard)
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
            hop.release()
            stopping.await()
            assertEquals(listOf(heardAfterStop), store.fetchDiscoveredNodes(RADIO_A).toList())
            assertTrue(events.none { it is AdvertisementEvent.NewContactDiscovered })
        }
    }

    private suspend fun Rig.assertAdoption(byte: Int, name: String, text: String, timestamp: UInt) {
        val seed = unknown(byte, name)
        val orphan = MessageDTO(radioId = RADIO_A, text = text, timestamp = timestamp, createdAt = AT,
            direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
            senderKeyPrefix = seed.contact.publicKey.prefix(6))
        val foreign = orphan.copy(radioId = RADIO_B)
        store.saveMessage(orphan)
        store.saveMessage(foreign)
        assertTrue(store.fetchConversations(RADIO_A).isEmpty())
        foreground = true
        var calls = 0
        service.setDeltaSyncHandler {
            calls++
            store.saveContact(RADIO_A, contactFrame(seed.contact.publicKey, name))
            AdvertContactSyncOutcome.SYNCED
        }
        send(MeshEvent.Advertisement(seed.contact.publicKey))
        assertEquals(1, calls)
        val contact = assertNotNull(store.fetchContact(RADIO_A, seed.contact.publicKey))
        assertEquals(orphan.copy(contactID = contact.id,
            deduplicationKey = DeduplicationKey.contentBased(contact.id, null, null, timestamp, text)),
            store.fetchMessage(EntityKey(RADIO_A, orphan.id)))
        assertEquals(foreign, store.fetchMessage(EntityKey(RADIO_B, foreign.id)))
        assertEquals(1L, contact.unreadCount)
        assertEquals(orphan.date, contact.lastMessageDate)
        assertEquals(listOf(contact), store.fetchConversations(RADIO_A).toList())
        assertEquals(1, events.count { it == AdvertisementEvent.ConversationsChanged })
        assertEquals(listOf(contact.id),
            events.filterIsInstance<AdvertisementEvent.OrphanDirectMessagesAdopted>().single().contactIDs.toList())
        assertTrue(events.any { it == AdvertisementEvent.ContactUpdated })
        assertEquals(name, store.fetchDiscoveredNodes(RADIO_A).single().name)
    }

    private fun contactFrame(key: Bytes, name: String) = ContactFrame(
        key, ContactType.CHAT, 0u, 0u, Bytes.EMPTY, name, 1_700_000_000u, 0.0, 0.0, 1_700_000_100u,
    )

    private suspend fun TestScope.withAdvert(
        debounce: Duration = Duration.ZERO,
        minInterval: Duration = Duration.ZERO,
        busyBackoff: Duration = 5.milliseconds,
        block: suspend Rig.() -> Unit,
    ) {
        val rig = Rig(this, debounce, minInterval, busyBackoff)
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
        var snapshotFailure: PersistenceStoreException? = null
        var touchFailure: PersistenceStoreException? = null
        var lookupFailure: PersistenceStoreException? = null
        var touchCalls = 0
        override suspend fun touchContactHeard(radioId: RadioId, publicKey: Bytes, date: Instant): Boolean {
            touchCalls++
            touchFailure?.let { throw it }
            return actual.touchContactHeard(radioId, publicKey, date)
        }
        override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
            lookupFailure?.let { throw it }
            return actual.fetchContact(radioId, publicKey)
        }
        override suspend fun fetchContactPublicKeys(radioId: RadioId): SnapshotSet<Bytes> {
            snapshotFailure?.let { throw it }
            return actual.fetchContactPublicKeys(radioId)
        }
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
        val contacts = mutableMapOf<Bytes, MeshContact>()
        val requestedContacts = mutableListOf<Bytes>()
        override val connectionState: Flow<ConnectionState> = flowOf(ConnectionState.Connected)
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
        override suspend fun getContact(publicKey: Bytes): MeshContact? {
            requestedContacts += publicKey
            return contacts.getValue(publicKey)
        }
        private fun unexpected(): Nothing = error("Deletion coordination must not issue radio commands")
    }

    private inner class Rig(private val test: TestScope, debounce: Duration, minInterval: Duration, busyBackoff: Duration) {
        private val owner = SupervisorJob(test.coroutineContext[Job])
        private val scope = CoroutineScope(test.coroutineContext + owner)
        private val source = Source()
        val intercepted = InterceptedStore(store)
        private val seeds = mutableListOf<Seed>()
        private val holds = mutableListOf<Hold>()
        private var foregroundHop: Hold? = null
        private var stopped = false
        private var rejecting = false
        private var rejectingTouches = false
        var wallOffsetMillis = 0L
        var foreground = false
        var expectedErrors = 0
        val errors = mutableListOf<String>()
        val events = mutableListOf<AdvertisementEvent>()
        val service = AdvertisementService(
            source, source, intercepted, scope, debounce, minInterval, busyBackoff,
            object : AppStateProvider {
                override suspend fun isInForeground(): Boolean {
                    foregroundHop?.await()
                    return foreground
                }
            },
            object : AdvertisementClock {
                override fun now(): Duration = scheduler.currentTime.milliseconds
                override fun wallNow(): Instant = AT.plusMillis(scheduler.currentTime + wallOffsetMillis)
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
            val frame = contactFrame(key, name)
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
        suspend fun unknown(byte: Int, name: String): Seed = seed(byte, name).also {
            store.deleteContacts(RADIO_A, SnapshotSet(listOf(it.contact.publicKey)))
        }
        fun stubContact(key: Bytes, name: String): MeshContact {
            val contact = MeshContact(key.hexString, key, ContactType.CHAT, ContactFlags(0u),
                0u, Bytes.EMPTY, name, Instant.ofEpochSecond(1_700_000_000),
                0.0, 0.0, Instant.ofEpochSecond(1_700_000_100))
            source.contacts[key] = contact
            return contact
        }
        fun requestedContacts() = source.requestedContacts.toList()
        fun rejectSnapshot() {
            intercepted.snapshotFailure = PersistenceStoreException(PersistenceStoreError.FetchFailed("controlled pre-round snapshot"))
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
        fun holdHandler(): Hold = Hold().also { holds += it }
        fun rejectDeletes() {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_advert_delete AFTER DELETE ON contacts " +
                "BEGIN SELECT RAISE(FAIL, 'controlled advertisement deletion failure'); END")
            rejecting = true
        }
        fun rejectTouches() {
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_advert_touch AFTER UPDATE ON contacts " +
                "BEGIN SELECT RAISE(FAIL, 'controlled advertisement touch failure'); END")
            rejectingTouches = true
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
        suspend fun restart() {
            check(stopped)
            stopped = false
            service.startEventMonitoring(RADIO_A)
            test.runCurrent()
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
            if (rejectingTouches) {
                db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_advert_touch")
                rejectingTouches = false
            }
            for (seed in seeds) {
                store.deleteContacts(RADIO_A, SnapshotSet(listOf(seed.contact.publicKey)))
                store.deleteContacts(RADIO_B, SnapshotSet(listOf(seed.contact.publicKey)))
            }
        }
    }
}
