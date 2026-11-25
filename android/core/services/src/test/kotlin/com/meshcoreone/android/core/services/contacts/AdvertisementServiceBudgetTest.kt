// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.protocol.event.MeshEvent
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** notReady, failure cap, busy collisions, path-only sync, owed full refetch re-arming and generations. */
class AdvertisementServiceBudgetTest {
    private val cap = AdvertisementService.MAX_CONSECUTIVE_DELTA_SYNC_FAILURES

    @TestFactory
    fun originalCases(): List<DynamicTest> = listOf(
        // MARK: - notReady outcome
        advertCase("notReady outcome drops drained keys without reschedule or budget spend") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            recorder.enqueueResult(AdvertContactSyncOutcome.NOT_READY)
            // A second result would only run if notReady incorrectly re-armed.
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            installHandler(service, recorder)
            val key = advertPublicKey(0x57)
            store.saveContact(radioId, advertContactFrame(key, name = "NotReady"))

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("ran") { recorder.callCount >= 1 }
            awaitIdle(service)
            service.stopEventMonitoring()

            assertTrue(service.pendingAdvertKeys.isEmpty(), "notReady must drop drained advert keys")
            assertEquals(1, recorder.callCount, "notReady must not schedule another round")
            assertEquals(0, service.consecutiveDeltaSyncFailures)
            assertNull(service.lastDeltaSyncEnd, "notReady must not stamp lastDeltaSyncEnd")
        },

        // MARK: - Failure cap
        advertParameterizedCase(
            "dropped background adverts retain phone recency", "(outcome : AdvertContactSyncOutcome)",
            listOf(AdvertContactSyncOutcome.NOT_READY, AdvertContactSyncOutcome.FAILED),
        ) { outcome ->
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val rounds = if (outcome == AdvertContactSyncOutcome.FAILED) cap else 1
            repeat(rounds) { recorder.enqueueResult(outcome) }
            installHandler(service, recorder)
            val key = advertPublicKey(0x58)
            store.saveContact(radioId, advertContactFrame(key))
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("queued") { key in service.pendingAdvertKeys }

            val receivedAt = Instant.now().minusSeconds(3600)
            service.recordPendingAdvertKey(key, receivedAt)
            val cutoff = receivedAt.epochSecond.toUInt() - 1u
            val before = assertNotNull(store.fetchContact(radioId, key))
            assertTrue(before.matchesStaleNodePrune(cutoff))

            scene.setIsInForeground(true)
            service.handleReturnToForeground()
            eventually("finished") { recorder.callCount >= rounds && service.deltaSyncTask == null }
            eventually("stamped") { (store.fetchContact(radioId, key)?.lastHeardTimestamp ?: 0u) > cutoff }
            val after = assertNotNull(store.fetchContact(radioId, key))
            assertFalse(after.matchesStaleNodePrune(cutoff))
            if (outcome == AdvertContactSyncOutcome.NOT_READY) {
                assertEquals(receivedAt.epochSecond.toUInt(), after.lastHeardTimestamp)
            }
            assertTrue(service.pendingAdvertKeys.isEmpty())
            assertEquals(rounds, recorder.callCount)
            service.stopEventMonitoring()
        },

        advertCase("repeated failures stop the retry loop") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            repeat(cap + 3) { recorder.enqueueResult(AdvertContactSyncOutcome.FAILED) }
            installHandler(service, recorder)
            val key = advertPublicKey(0x53)
            store.saveContact(radioId, advertContactFrame(key, name = "Flaky"))

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("capped") { recorder.callCount >= cap }
            // Replaces the 120 ms sleep: once idle, no further round is registered.
            awaitIdle(service)
            service.stopEventMonitoring()

            assertEquals(cap, recorder.callCount, "retries must stop at the failure cap")
            assertTrue(service.pendingAdvertKeys.isEmpty(), "the capped round drops its drained keys")
        },

        advertCase("busy rounds keep their keys past the failure cap") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            repeat(cap + 2) { recorder.enqueueResult(AdvertContactSyncOutcome.BUSY) }
            // The key has no local row, so only the round that carries it can announce it.
            val key = advertPublicKey(0x54)
            recorder.enqueuePersist(advertContactFrame(key, name = "Late"))
            installHandler(service, recorder)
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("the drained key must survive collisions and reach the round that syncs it") {
                listener.counter.newContactCount >= 1
            }
            assertTrue(
                recorder.callCount > cap,
                "a claim collision never reached the radio, so it must not spend the failure budget",
            )
            service.stopEventMonitoring()
            listener.cancel()
        },

        // MARK: - Path-only sync
        advertCase("pathUpdate deferred by contact sync still runs") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)

            service.setSyncingContacts(true)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.PathUpdate(advertPublicKey(0x63)))
            awaitHandled(1)
            awaitIdle(service)
            assertEquals(0, recorder.callCount)

            service.setSyncingContacts(false)
            val ran = advertWaitUntil { recorder.callCount >= 1 }
            service.stopEventMonitoring()
            assertTrue(ran, "a path update that fires while syncing must re-arm the delta sync")
        },

        // MARK: - Contact-row newness gates the new-contact notification
        advertCase("re-created contact with a surviving Discover row notifies again") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x74)
            val frame = advertContactFrame(key, name = "Returning")
            // Deleting a contact leaves its Discover row; the re-inserted Contact is still new.
            store.upsertDiscoveredNode(radioId, frame)
            recorder.enqueuePersist(frame)
            installHandler(service, recorder)
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("a re-created Contact row must notify even when its Discover row survived") {
                listener.counter.newContactCount >= 1
            }
            awaitIdle(service)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
            assertNotNull(store.fetchContact(radioId, key), "the handler must have persisted the contact")
            assertEquals(1, listener.counter.newContactCount, "one insert must not notify twice")
        },

        // MARK: - Store errors
        advertCase("advert retries touch once then drops key when both fail") {
            // A failed touch cannot tell known from unknown, so nothing is recorded after one retry.
            val store = makeMockStore()
            store.touchContactHeardError = AdvertStoreUnavailable()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(advertPublicKey(0x85)))
            eventually("touch must be retried once before giving up") { store.touchContactHeardCallCount >= 2 }
            awaitHandled(1)
            awaitIdle(service)
            service.stopEventMonitoring()
            assertEquals(0, recorder.callCount, "empty-round guard must skip when no key was recorded")
            assertEquals(2, store.touchContactHeardCallCount)
        },

        advertCase("capped failure round restores pathSyncPending") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            repeat(cap) { recorder.enqueueResult(AdvertContactSyncOutcome.FAILED) }
            installHandler(service, recorder)
            val key = advertPublicKey(0x86)
            store.saveContact(radioId, advertContactFrame(key, name = "Cap"))

            startMonitoring(service)
            service.setSyncingContacts(true)
            session.yieldEvent(MeshEvent.PathUpdate(key))
            session.yieldEvent(MeshEvent.Advertisement(key))
            service.setSyncingContacts(false)

            eventually("capped") { recorder.callCount >= cap }
            awaitIdle(service)
            // The cap drops drained keys but restores the path update; the next event re-arms.
            assertTrue(service.pathSyncPending)
            assertTrue(service.pendingAdvertKeys.isEmpty())
            service.stopEventMonitoring()
        },

        advertCase("capped failure round restores escalateToFullRefetch") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            // First success with no local row escalates; failing to the cap restores the drained flag.
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            repeat(cap) { recorder.enqueueResult(AdvertContactSyncOutcome.FAILED) }
            installHandler(service, recorder)
            val key = advertPublicKey(0x88)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("capped", 3.seconds) { recorder.callCount >= 1 + cap }
            awaitIdle(service)
            assertTrue(
                service.escalateToFullRefetch,
                "cap must restore escalateToFullRefetch when the drained round was a full refetch",
            )
            assertTrue(service.pendingAdvertKeys.isEmpty())
            service.stopEventMonitoring()
        },

        advertCase("fresh advert during the capped failing round re-arms and drains") {
            // An 0x80 during the final failing round spent its one no-op schedule against the still-set
            // task; the cap must detect that fresh key and re-arm.
            val store = makeStore()
            val service = makeService(store)
            val firstKey = advertPublicKey(0x71)
            val freshKey = advertPublicKey(0x72)
            store.saveContact(radioId, advertContactFrame(firstKey, name = "First"))
            val injector = AdvertCapRoundInjector(service, freshKey, cap)
            service.setDeltaSyncHandler { injector.handle() }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(firstKey))
            eventually("a fresh advert during the capped round must re-arm the sync", 3.seconds) { injector.calls > cap }
            eventually("the re-armed round drains the fresh advert key") { service.pendingAdvertKeys.isEmpty() }
            service.stopEventMonitoring()
        },

        advertCase("setSyncingContacts false re-arms owed full refetch with empty pending keys") {
            // An owed escalateToFullRefetch alone is work for both the re-arm and the empty-round guard.
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            repeat(cap) { recorder.enqueueResult(AdvertContactSyncOutcome.FAILED) }
            installHandler(service, recorder)
            val key = advertPublicKey(0x91)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))

            eventually("capped", 3.seconds) { recorder.callCount >= 1 + cap }
            eventually("escalated") {
                service.escalateToFullRefetch && service.pendingAdvertKeys.isEmpty() && service.deltaSyncTask == null
            }
            assertFalse(service.pathSyncPending)

            val callsBeforeRearm = recorder.callCount
            service.setSyncingContacts(true)
            service.setSyncingContacts(false)
            val rearmed = advertWaitUntil(2.seconds) { recorder.callCount > callsBeforeRearm }
            service.stopEventMonitoring()
            assertTrue(rearmed, "owed full refetch must re-arm when contact sync ends")
            assertEquals(true, recorder.fullRefetchFlags.last(), "re-armed round must run as prune-free full refetch")
        },

        advertCase("setDeltaSyncHandler re-arms owed full refetch with empty pending keys") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            repeat(cap) { recorder.enqueueResult(AdvertContactSyncOutcome.FAILED) }
            installHandler(service, recorder)
            val key = advertPublicKey(0x92)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))

            eventually("capped", 3.seconds) { recorder.callCount >= 1 + cap }
            eventually("escalated") {
                service.escalateToFullRefetch && service.pendingAdvertKeys.isEmpty() && service.deltaSyncTask == null
            }

            val callsBeforeRearm = recorder.callCount
            service.setDeltaSyncHandler(null)
            installHandler(service, recorder)
            val rearmed = advertWaitUntil(2.seconds) { recorder.callCount > callsBeforeRearm }
            service.stopEventMonitoring()
            assertTrue(rearmed, "reinstalling the handler must re-arm an owed full refetch")
            assertEquals(true, recorder.fullRefetchFlags.last())
        },

        advertCase("finishRound with stale generation leaves the new task registered") {
            val store = makeStore()
            // The 30 s debounce never fires: the manual clock is not advanced.
            val service = makeService(store, debounce = 30.seconds, clock = AdvertManualClock())
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0x89)
            store.saveContact(radioId, advertContactFrame(key, name = "Gen"))
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))

            eventually("scheduled") { service.deltaSyncTask != null }
            val liveGeneration = service.deltaSyncGeneration
            // A stale finishRound (cancelled prior round) must not wipe the live task.
            service.finishRound(liveGeneration - 1)
            assertNotNull(service.deltaSyncTask)
            // Matching generation clears as designed.
            service.finishRound(liveGeneration)
            assertNull(service.deltaSyncTask)
            service.stopEventMonitoring()
        },

        advertCase("setDeltaSyncHandler nil then reinstall still syncs pending keys") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x8A)
            store.saveContact(radioId, advertContactFrame(key, name = "Reinstall"))
            startMonitoring(service)
            service.setDeltaSyncHandler(null)
            session.yieldEvent(MeshEvent.Advertisement(key))
            // ADR 004: wait for the 0x80 to be recorded instead of sleeping.
            eventually("recorded") { key in service.pendingAdvertKeys }
            assertEquals(0, recorder.callCount)

            installHandler(service, recorder)
            eventually("reinstalling a handler must re-arm for already-pending keys") { recorder.callCount >= 1 }
            service.stopEventMonitoring()
        },
    )
}
