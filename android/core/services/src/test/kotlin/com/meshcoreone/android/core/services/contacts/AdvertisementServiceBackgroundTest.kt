// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.protocol.event.MeshEvent
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Lets every coroutine that is ready on the single test thread run (no wall-clock wait). Used only to
 * give `stopEventMonitoring` the chance to finish before asserting it is still blocked on a held flush.
 */
private suspend fun drainReadyCoroutines() = repeat(READY_COROUTINE_TURNS) { yield() }

private const val READY_COROUTINE_TURNS = 32

/** Background deferral, deferred 0x8F flushes and teardown joins. */
class AdvertisementServiceBackgroundTest {
    @TestFactory
    fun originalCases(): List<DynamicTest> = listOf(
        advertCase("background 0x80 records key and does not invoke deltaSyncHandler") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xB0)
            store.saveContact(radioId, advertContactFrame(key, name = "Known"))
            val heardBefore = assertNotNull(store.fetchContact(radioId, key)).lastHeardTimestamp
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending") { key in service.pendingAdvertKeys }
            // Replaces the 80 ms sleep: the advert was fully handled and no round was armed.
            awaitHandled(1)
            listener.sync()
            assertNull(service.deltaSyncTask)
            assertEquals(0, recorder.callCount)
            assertEquals(0, listener.counter.contactUpdatedCount)
            val heardAfter = assertNotNull(store.fetchContact(radioId, key)).lastHeardTimestamp
            assertEquals(heardBefore, heardAfter, "background 0x80 must not touchContactHeard")

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("handleReturnToForeground drains keys recorded while backgrounded") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xB2)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending") { key in service.pendingAdvertKeys }
            assertEquals(0, recorder.callCount)

            scene.setIsInForeground(true)
            service.handleReturnToForeground()
            val ran = advertWaitUntil { recorder.callCount >= 1 }
            service.stopEventMonitoring()
            assertTrue(ran)
        },

        advertCase("background 0x81 records path key and does not invoke handler") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xB3)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.PathUpdate(key))
            eventually("pending") { key in service.pendingPathKeys }
            awaitHandled(1)
            assertNull(service.deltaSyncTask)
            service.stopEventMonitoring()
            assertEquals(0, recorder.callCount)
        },

        advertCase("armed round that backgrounds before fire does not invoke handler") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = true)
            val clock = AdvertManualClock()
            val service = makeService(store, debounce = 200.milliseconds, appStateProvider = scene, clock = clock)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xB4)
            store.saveContact(radioId, advertContactFrame(key, name = "Armed"))
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("armed") {
                key in service.pendingAdvertKeys && service.deltaSyncTask != null && clock.sleeperDeadlines.isNotEmpty()
            }
            scene.setIsInForeground(false)
            clock.advance(200.milliseconds)
            eventually("settled") { service.deltaSyncTask == null }
            assertEquals(0, recorder.callCount)
            assertTrue(key in service.pendingAdvertKeys)
            service.stopEventMonitoring()
        },

        advertCase("materializeContactForPendingAdvert still works while backgrounded") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xB5)
            session.setStubbedContact(advertMeshContact(key, name = "DebounceNode"), key)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending") { key in service.pendingAdvertKeys }
            val contact = assertNotNull(service.materializeContactForPendingAdvert(key.prefix(6), radioId))
            assertEquals(key, contact.publicKey)
            assertTrue(key in service.pendingAdvertKeys)
            assertEquals(0, recorder.callCount, "materialize must not issue GET_CONTACTS")
            service.stopEventMonitoring()
        },

        advertCase("setSyncingContacts false while backgrounded does not invoke handler") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xB6)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            // Deterministic order: the key is pending before the toggle, so the background guard is what stops it.
            eventually("pending") { key in service.pendingAdvertKeys }
            service.setSyncingContacts(true)
            service.setSyncingContacts(false)
            assertNull(service.deltaSyncTask)
            assertEquals(0, recorder.callCount)
            assertTrue(key in service.pendingAdvertKeys)
            service.stopEventMonitoring()
        },

        advertCase("background 0x8F queues key and does not deleteContact") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0x8C)
            val saved = store.saveContact(radioId, advertContactFrame(key, name = "Doomed"))
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending") { key in service.pendingAdvertKeys }
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("queued") { key in service.pendingDeletedKeys }
            assertFalse(key in service.pendingAdvertKeys)
            assertTrue(key in service.contactsDeletedDuringSync)
            assertEquals(saved.id, store.fetchContact(radioId, key)?.id)
            // Replaces the 50 ms sleep: both events handled and every emitted event counted.
            awaitHandled(2)
            listener.sync()
            assertEquals(0, listener.counter.contactDeletedCleanupCount)
            assertEquals(0, listener.counter.contactUpdatedCount)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("handleReturnToForeground flushes queued 0x8F deletes once") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val keyA = advertPublicKey(0xA1)
            val keyB = advertPublicKey(0xA2)
            val savedA = store.saveContact(radioId, advertContactFrame(keyA, name = "A"))
            val savedB = store.saveContact(radioId, advertContactFrame(keyB, name = "B"))
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(keyA))
            session.yieldEvent(MeshEvent.ContactDeleted(keyB))
            eventually("queued") { keyA in service.pendingDeletedKeys && keyB in service.pendingDeletedKeys }

            scene.setIsInForeground(true)
            service.handleReturnToForeground()
            eventually("gone") { store.fetchContact(radioId, keyA) == null && store.fetchContact(radioId, keyB) == null }
            assertTrue(service.pendingDeletedKeys.isEmpty())
            listener.sync()
            assertEquals(1, listener.counter.contactDeletedCleanupCount)
            assertEquals(setOf(savedA.id, savedB.id), listener.counter.contactDeletedCleanupIDs.toSet())
            assertEquals(1, listener.counter.contactUpdatedCount)
            assertEquals(1, listener.counter.nodeStorageFullChangedCount)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("re-advert after background 0x8F clears pendingDeletedKeys") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xA3)
            store.saveContact(radioId, advertContactFrame(key, name = "Back"))
            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("queued") { key in service.pendingDeletedKeys }

            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("cleared") { key !in service.pendingDeletedKeys }
            assertTrue(key in service.pendingAdvertKeys)
            assertNotNull(store.fetchContact(radioId, key))

            scene.setIsInForeground(true)
            service.handleReturnToForeground()
            // Replaces the 80 ms sleep: the re-armed (handler-less) round has finished.
            awaitIdle(service)
            assertNotNull(store.fetchContact(radioId, key))
            service.stopEventMonitoring()
        },

        advertCase("stopEventMonitoring flushes 0x8F parked on isInForeground hop") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xC1)
            store.saveContact(radioId, advertContactFrame(key, name = "Parked"))
            scene.hangForegroundChecks()
            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("parked") { scene.isWaitingOnForegroundCheck }

            val stopped = async { service.stopEventMonitoring() }
            scene.releaseForegroundCheck()
            stopped.await()
            assertNull(store.fetchContact(radioId, key))
            assertTrue(service.pendingDeletedKeys.isEmpty())
        },

        advertCase("stopEventMonitoring stamps lastHeard for background 0x80") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xB7)
            store.saveContact(radioId, advertContactFrame(key, name = "HeardOvernight"))
            val heardBefore = assertNotNull(store.fetchContact(radioId, key)).lastHeardTimestamp

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending") { key in service.pendingAdvertKeys }
            val heardWhileQueued = assertNotNull(store.fetchContact(radioId, key)).lastHeardTimestamp
            assertEquals(heardBefore, heardWhileQueued, "background 0x80 must not touchContactHeard")

            service.stopEventMonitoring()
            val heardAfter = assertNotNull(store.fetchContact(radioId, key)).lastHeardTimestamp
            assertTrue(
                (heardAfter ?: 0u) > (heardBefore ?: 0u),
                "teardown must stamp lastHeard so stale-RTC prune cannot CMD_REMOVE a heard contact",
            )
        },

        advertCase("stop during isInForeground hop does not invoke handler") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xD0)
            store.saveContact(radioId, advertContactFrame(key, name = "ParkedRound"))
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending") { key in service.pendingAdvertKeys }
            assertEquals(0, recorder.callCount)

            scene.setIsInForeground(true)
            scene.hangForegroundChecks()
            service.handleReturnToForeground()
            eventually("parked") { scene.isWaitingOnForegroundCheck }
            assertEquals(0, recorder.callCount)

            val stopped = async { service.stopEventMonitoring() }
            eventually("handler dropped") { service.deltaSyncHandler == null }
            scene.releaseForegroundCheck()
            stopped.await()
            assertEquals(0, recorder.callCount)
            assertTrue(service.pendingAdvertKeys.isEmpty())
        },

        advertCase("stop during handler still stamps drained lastHeard") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xD1)
            store.saveContact(radioId, advertContactFrame(key, name = "DrainedHeard"))
            val heardBefore = assertNotNull(store.fetchContact(radioId, key)).lastHeardTimestamp
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler {
                hold.waitUntilReleased()
                AdvertContactSyncOutcome.SYNCED
            }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending") { key in service.pendingAdvertKeys }
            val heardWhileQueued = assertNotNull(store.fetchContact(radioId, key)).lastHeardTimestamp
            assertEquals(heardBefore, heardWhileQueued, "background 0x80 must not touchContactHeard")

            scene.setIsInForeground(true)
            service.handleReturnToForeground()
            eventually("waiting") { hold.isWaiting }
            val stopped = async { service.stopEventMonitoring() }
            eventually("handler dropped") { service.deltaSyncHandler == null }
            hold.release()
            stopped.await()
            eventually("cancel-after-drain must stamp lastHeard; teardown copy of pending is empty") {
                (store.fetchContact(radioId, key)?.lastHeardTimestamp ?: 0u) > (heardBefore ?: 0u)
            }
        },

        advertCase("0x80 during 0x8F flush hop does not delete the revived contact") {
            val store = makeMockStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xA4)
            val saved = store.saveContact(radioId, advertContactFrame(key, name = "Revived"))
            store.saveMessage(advertDirectMessage(radioId, saved.id, "keep"))

            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("queued") { key in service.pendingDeletedKeys }

            scene.setIsInForeground(true)
            scene.hangForegroundChecks()
            store.holdNextDeleteContacts()
            val flushed = async { service.handleReturnToForeground() }
            eventually("held") { store.isDeleteContactsHeld }

            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("parked") { scene.isWaitingOnForegroundCheck }
            assertFalse(key in service.pendingAdvertKeys)

            store.releaseDeleteContacts()
            flushed.await()
            assertEquals(saved.id, store.fetchContact(radioId, key)?.id)
            assertTrue(store.deletedContactIDs.isEmpty())
            assertEquals(1, store.fetchMessages(EntityKey(radioId, saved.id), 10, 0).size)

            scene.releaseForegroundCheck()
            service.stopEventMonitoring()
        },

        advertCase("flush does not clear skip set before deleteContacts") {
            val store = makeMockStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xA5)
            val saved = store.saveContact(radioId, advertContactFrame(key, name = "SkipFirst"))
            store.saveMessage(advertDirectMessage(radioId, saved.id, "keep"))

            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("queued") { key in service.pendingDeletedKeys }

            scene.setIsInForeground(true)
            scene.hangForegroundChecks()
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("parked") { scene.isWaitingOnForegroundCheck }
            assertTrue(key in service.pendingDeletedKeys)

            store.holdNextDeleteContacts()
            val flushed = async { service.handleReturnToForeground() }
            eventually("held") { store.isDeleteContactsHeld }
            store.releaseDeleteContacts()
            flushed.await()
            assertEquals(saved.id, store.fetchContact(radioId, key)?.id)
            assertTrue(store.deletedContactIDs.isEmpty())

            scene.releaseForegroundCheck()
            service.stopEventMonitoring()
        },

        advertCase("background 0x80 then 0x8F flush still deletes the contact") {
            val store = makeMockStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xA6)
            val saved = store.saveContact(radioId, advertContactFrame(key, name = "LastEvent"))
            store.saveMessage(advertDirectMessage(radioId, saved.id, "gone"))
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending advert") { key in service.pendingAdvertKeys }
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("queued") { key in service.pendingDeletedKeys }

            scene.setIsInForeground(true)
            service.handleReturnToForeground()
            eventually("gone") { store.fetchContact(radioId, key) == null }
            assertTrue(saved.id in store.deletedContactIDs)
            assertTrue(store.fetchMessages(EntityKey(radioId, saved.id), 10, 0).isEmpty())
            eventually("cleaned") { listener.counter.contactDeletedCleanupCount == 1 }

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("failed 0x8F flush re-queues keys") {
            val store = makeMockStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xE1)
            val saved = store.saveContact(radioId, advertContactFrame(key, name = "Retry"))
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("queued") { key in service.pendingDeletedKeys }

            store.deleteContactError = AdvertStoreUnavailable()
            scene.setIsInForeground(true)
            service.handleReturnToForeground()
            assertTrue(key in service.pendingDeletedKeys)
            assertEquals(saved.id, store.fetchContact(radioId, key)?.id)
            listener.sync()
            assertEquals(0, listener.counter.contactDeletedCleanupCount)

            store.deleteContactError = null
            service.handleReturnToForeground()
            eventually("gone") { store.fetchContact(radioId, key) == null }
            assertTrue(service.pendingDeletedKeys.isEmpty())
            eventually("cleaned") { listener.counter.contactDeletedCleanupCount == 1 }

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertParameterizedCase("stop joins foreground deletion flush before finishing events", "(storeFails : Bool)", listOf(false, true)) { storeFails ->
            val store = makeMockStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xE4)
            val saved = store.saveContact(radioId, advertContactFrame(key))
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("queued") { key in service.pendingDeletedKeys }
            if (storeFails) store.deleteContactError = AdvertStoreUnavailable()
            store.holdNextDeleteContacts()
            scene.setIsInForeground(true)
            val flushed = async { service.handleReturnToForeground() }
            eventually("held") { store.isDeleteContactsHeld }

            val stopped = AdvertCommitMarker()
            val stopping = async {
                service.stopEventMonitoring()
                service.finishEvents()
                stopped.markCommitted()
            }
            // Replaces the 100 ms negative wait: once stop has torn down the monitor and every ready
            // coroutine has run, a stop that did not join the held flush would already be done.
            eventually("stop started") { service.deltaSyncHandler == null && session.eventSubscriptionCount == 0 }
            drainReadyCoroutines()
            assertFalse(stopped.committed, "stop must await the flush including cleanup emission and error handling")
            assertNotNull(service.currentRadioId)

            store.releaseDeleteContacts()
            flushed.await()
            stopping.await()
            listener.join()

            assertEquals(1, store.deleteContactsCallCount, "joining a failed flush must not retry it during stop")
            assertNull(service.currentRadioId)
            if (storeFails) {
                assertTrue(key in service.pendingDeletedKeys)
                assertEquals(0, listener.counter.contactDeletedCleanupCount)
                assertEquals(saved.id, store.fetchContact(radioId, key)?.id)
            } else {
                assertEquals(listOf(saved.id), listener.counter.contactDeletedCleanupIDs)
                assertNull(store.fetchContact(radioId, key))
            }
        },

        advertParameterizedCase("stop drains deletions queued during a foreground flush", "(nextRevived : Bool)", listOf(false, true)) { nextRevived ->
            val store = makeMockStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val firstKey = advertPublicKey(0xE5)
            val nextKey = advertPublicKey(0xE6)
            val first = store.saveContact(radioId, advertContactFrame(firstKey))
            val next = store.saveContact(radioId, advertContactFrame(nextKey))
            store.saveMessage(advertDirectMessage(radioId, next.id, "keep if revived"))
            service.setDeltaSyncHandler { AdvertContactSyncOutcome.SYNCED }
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(firstKey))
            eventually("first queued") { firstKey in service.pendingDeletedKeys }
            store.holdNextDeleteContacts()
            val flushed = async { service.handleReturnToForeground() }
            eventually("held") { store.isDeleteContactsHeld }

            session.yieldEvent(MeshEvent.ContactDeleted(nextKey))
            eventually("next queued") { nextKey in service.pendingDeletedKeys }
            if (nextRevived) {
                scene.hangForegroundChecks()
                session.yieldEvent(MeshEvent.Advertisement(nextKey))
                eventually("parked") { scene.isWaitingOnForegroundCheck }
            }
            val stopping = async {
                service.stopEventMonitoring()
                service.finishEvents()
            }
            eventually("stopping started") { service.deltaSyncHandler == null }
            store.releaseDeleteContacts()
            flushed.await()
            if (nextRevived) scene.releaseForegroundCheck()
            stopping.await()
            listener.join()

            val expected = if (nextRevived) setOf(first.id) else setOf(first.id, next.id)
            assertEquals(expected, listener.counter.contactDeletedCleanupIDs.toSet())
            if (nextRevived) {
                assertEquals(next.id, store.fetchContact(radioId, nextKey)?.id)
                assertEquals(1, store.fetchMessages(EntityKey(radioId, next.id), 10, 0).size)
            }
            assertTrue(service.pendingDeletedKeys.isEmpty())
            assertEquals(2, store.deleteContactsCallCount)
        },

        advertCase("failed immediate 0x8F re-queues key for flush") {
            val store = makeMockStore()
            val service = makeService(store)
            val key = advertPublicKey(0xE3)
            val saved = store.saveContact(radioId, advertContactFrame(key, name = "ImmediateRetry"))
            val listener = listen(service)

            startMonitoring(service)
            store.deleteContactError = AdvertStoreUnavailable()
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("requeued") { saved.id in store.deletedContactIDs && key in service.pendingDeletedKeys }
            assertEquals(saved.id, store.fetchContact(radioId, key)?.id)
            listener.sync()
            assertEquals(0, listener.counter.contactDeletedCleanupCount)

            store.deleteContactError = null
            service.handleReturnToForeground()
            eventually("gone") { store.fetchContact(radioId, key) == null }
            assertTrue(service.pendingDeletedKeys.isEmpty())
            eventually("cleaned") { listener.counter.contactDeletedCleanupCount == 1 }

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("failed 0x8F flush does not re-queue a key revived during the hop") {
            val store = makeMockStore()
            val scene = AdvertFakeAppState(isInForeground = false)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0xE2)
            store.saveContact(radioId, advertContactFrame(key, name = "Subtract"))

            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("queued") { key in service.pendingDeletedKeys }

            store.deleteContactError = AdvertStoreUnavailable()
            store.holdNextDeleteContacts()
            scene.setIsInForeground(true)
            val flushed = async { service.handleReturnToForeground() }
            eventually("held") { store.isDeleteContactsHeld }

            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("revived") { key in service.pendingAdvertKeys }
            store.releaseDeleteContacts()
            flushed.await()

            assertFalse(key in service.pendingDeletedKeys)
            assertNotNull(store.fetchContact(radioId, key))
            service.stopEventMonitoring()
        },
    )
}
