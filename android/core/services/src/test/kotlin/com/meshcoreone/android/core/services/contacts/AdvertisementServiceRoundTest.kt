// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactSaveResult
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Rollback, coalescing, advert/path handling, re-merge, deferral, teardown, escalation and min interval. */
class AdvertisementServiceRoundTest {
    @TestFactory
    fun originalCases(): List<DynamicTest> = listOf(
        // MARK: - Rollback cascade safety
        advertCase("pathUpdate cancel after save does not cascade-delete messages") {
            // A contact re-saved mid-round after 0x8F must not cascade-wipe a DM that landed before rollback.
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0xD4)
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler {
                hold.waitUntilReleased()
                var saved: ContactSaveResult? = null
                advertTry { saved = store.saveContact(radioId, advertContactFrame(key, name = "HasMessages")) }
                saved?.let { advertTry { store.saveMessage(advertDirectMessage(radioId, it.id, "keep me")) } }
                AdvertContactSyncOutcome.SYNCED
            }
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("handler waiting") { hold.isWaiting }

            // 0x8F while the commit is held: no local row yet, so only the rollback key is set. Release
            // only once the 0x8F left the deferred queue (ADR 004 state wait, not a fixed sleep).
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("delete handled") { key in service.contactsDeletedDuringSync && key !in service.pendingDeletedKeys }
            hold.release()
            eventually("round done") { service.deltaSyncTask == null }

            val contact = assertNotNull(store.fetchContact(radioId, key))
            val messages = store.fetchMessages(EntityKey(radioId, contact.id), 50, 0)
            assertEquals(1, messages.size, "messages must not be cascade-deleted on rollback")
            service.stopEventMonitoring()
        },

        // MARK: - Coalescing
        advertCase("burst of adverts coalesces to one handler call") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            // Hold the first call so later adverts accumulate instead of each draining separately.
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler { fullRefetch ->
                val isFirst = recorder.callCount == 0
                val result = recorder.handle(fullRefetch)
                if (isFirst) hold.waitUntilReleased()
                result
            }
            val keys = (0 until 5).map { advertPublicKey(0xA0 + it) }
            keys.forEach { store.saveContact(radioId, advertContactFrame(it, name = "K${it.prefix(1).hexString}")) }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(keys[0]))
            eventually("first drain held") { hold.isWaiting }
            assertEquals(1, recorder.callCount)

            keys.drop(1).forEach { session.yieldEvent(MeshEvent.Advertisement(it)) }
            // Replaces the 40 ms sleep: the remaining keys are recorded while the first drain is held.
            eventually("mid-hold keys recorded") {
                session.handledEvents >= 5 && service.pendingAdvertKeys.containsAll(keys.drop(1))
            }
            assertEquals(1, recorder.callCount, "mid-hold adverts must not start a parallel handler")
            hold.release()

            eventually("keys recorded mid-hold should schedule one second pass") { recorder.callCount == 2 }
            awaitIdle(service)
            service.stopEventMonitoring()
            assertEquals(2, recorder.callCount)
            assertTrue(session.getContactPublicKeys.isEmpty())
        },

        // MARK: - Known contact 0x80
        advertCase("known contact advert bumps lastHeard without getContact") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xB1)
            val frame = advertContactFrame(key, name = "Known")
            store.saveContact(radioId, frame)
            store.upsertDiscoveredNode(radioId, frame)

            val before = Instant.now().minusSeconds(1)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("heard") { (store.fetchContact(radioId, key)?.lastHeardTimestamp ?: 0u) > 0u }

            val contact = assertNotNull(store.fetchContact(radioId, key))
            assertTrue((contact.lastHeardTimestamp ?: 0u) >= before.epochSecond.toUInt())
            val node = assertNotNull(store.fetchDiscoveredNodes(radioId).firstOrNull { it.publicKey == key })
            assertTrue(node.lastHeard >= before)
            eventually("handler ran") { recorder.callCount >= 1 }
            assertTrue(session.getContactPublicKeys.isEmpty())
            service.stopEventMonitoring()
        },

        // MARK: - Unknown key 0x80
        advertCase("unknown key advert yields newContactDiscovered once after handler persists") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0xC2)
            recorder.enqueuePersist(advertContactFrame(key, name = "NewNode", latitude = 10.0, longitude = 20.0))
            installHandler(service, recorder)
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("discovered") { listener.counter.newContactCount >= 1 }

            // A second advert for the same key must not re-notify as new.
            recorder.enqueuePersist(advertContactFrame(key, name = "NewNode", latitude = 11.0, longitude = 21.0))
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("second handler") { recorder.callCount >= 2 }
            awaitIdle(service)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
            assertEquals(1, listener.counter.newContactCount)
            assertEquals("NewNode", assertNotNull(store.fetchContact(radioId, key)).name)
        },

        // MARK: - 0x8A parity
        advertCase("0x8A then 0x80 for same key notifies from each path") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xD3)
            val mesh = advertMeshContact(key, name = "Manual")
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.NewContact(mesh))
            eventually("0x8A notified") { listener.counter.newContactCount >= 1 }

            // 0x8A leaves a Discover row but no Contact row, so the 0x80 round's snapshot lacks the key
            // and the handler-persisted contact is announced separately.
            recorder.enqueuePersist(advertContactFrame(key, name = "Manual"))
            session.yieldEvent(MeshEvent.Advertisement(key))
            // 0x8A yielded the first ContactUpdated; the second lands after reconcile.
            eventually("reconciled") { listener.counter.contactUpdatedCount >= 2 }
            awaitIdle(service)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
            assertEquals(2, listener.counter.newContactCount)
            assertEquals(1, recorder.callCount)
        },

        // MARK: - Path update
        advertCase("pathUpdate only triggers handler without Discover row") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xE4)
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.PathUpdate(key))
            eventually("handler ran") { recorder.callCount >= 1 }
            eventually("contact updated") { listener.counter.contactUpdatedCount >= 1 }
            assertTrue(store.fetchDiscoveredNodes(radioId).isEmpty())
            assertTrue(session.getContactPublicKeys.isEmpty())

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("pathUpdate for known contact escalates when incremental leaves lastModified unchanged") {
            // A radio RTC reset can stamp path lastmod at or below the watermark; the incremental then
            // returns nothing and only the escalated full refetch delivers the path.
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0xA1)
            val oldLastMod = 1_700_000_100u
            val newPath = Bytes.of(0x03, 0x04)
            val newLastMod = oldLastMod + 50u
            store.saveContact(
                radioId,
                advertContactFrame(
                    key, name = "Relay", type = ContactType.REPEATER, latitude = 10.0, longitude = 20.0,
                    lastModified = oldLastMod, outPathLength = 2u, outPath = Bytes.of(0x01, 0x02),
                ),
            )
            val calls = AdvertCallFlagRecorder()
            service.setDeltaSyncHandler { fullRefetch ->
                calls.note(fullRefetch)
                // The incremental models an empty watermark filter; only the full refetch writes.
                if (fullRefetch) {
                    advertTry {
                        store.saveContact(
                            radioId,
                            advertContactFrame(
                                key, name = "Relay", type = ContactType.REPEATER, latitude = 10.5, longitude = 20.5,
                                lastModified = newLastMod, outPathLength = 2u, outPath = newPath,
                            ),
                        )
                    }
                }
                AdvertContactSyncOutcome.SYNCED
            }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.PathUpdate(key))
            eventually("when incremental leaves lastModified unchanged, path must escalate to full refetch", 3.seconds) {
                calls.flags.size >= 2 && true in calls.flags
            }
            assertEquals(false, calls.flags.first())
            assertTrue(session.getContactPublicKeys.isEmpty(), "must not reinstate per-key getContact")
            val contact = store.fetchContact(radioId, key)
            assertEquals(newPath, contact?.outPath)
            assertEquals(newLastMod, contact?.lastModified)
            assertEquals(10.5, contact?.latitude)
            service.stopEventMonitoring()
        },

        advertCase("pathUpdate for known contact does not escalate when incremental refreshes lastModified") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0xA2)
            val oldLastMod = 1_700_000_100u
            val newLastMod = oldLastMod + 10u
            val newPath = Bytes.of(0xAA, 0xBB)
            store.saveContact(
                radioId,
                advertContactFrame(
                    key, name = "Relay", type = ContactType.REPEATER, latitude = 1.0, longitude = 2.0,
                    lastModified = oldLastMod, outPathLength = 1u, outPath = Bytes.of(0x11),
                ),
            )
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            recorder.enqueuePersist(
                advertContactFrame(
                    key, name = "Relay", type = ContactType.REPEATER, latitude = 1.0, longitude = 2.0,
                    lastModified = newLastMod, outPathLength = 2u, outPath = newPath,
                ),
            )
            installHandler(service, recorder)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.PathUpdate(key))
            eventually("handler ran") { recorder.callCount >= 1 }
            // Replaces the 80 ms sleep: an incorrect escalation re-arms in the same section that
            // finishes the round, so an idle service proves no second round was scheduled.
            awaitIdle(service)
            assertEquals(1, recorder.callCount, "successful path delivery must not escalate")
            assertEquals(listOf(false), recorder.fullRefetchFlags)
            assertFalse(service.escalateToFullRefetch)
            val contact = store.fetchContact(radioId, key)
            assertEquals(newPath, contact?.outPath)
            assertEquals(newLastMod, contact?.lastModified)
            service.stopEventMonitoring()
        },

        // MARK: - Failure re-merge
        advertCase("handler failure remerges keys and retries") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0xF5)
            // Unknown key: the first failure leaves no row; the retry persists and reconcile notifies.
            recorder.enqueueResult(AdvertContactSyncOutcome.FAILED)
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            recorder.enqueuePersist(advertContactFrame(key, name = "Retry"))
            installHandler(service, recorder)
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("retried") { recorder.callCount >= 2 }
            eventually("re-merged key must reach reconcile after successful retry") { listener.counter.newContactCount >= 1 }
            assertNotNull(store.fetchContact(radioId, key))
            assertTrue(store.fetchDiscoveredNodes(radioId).any { it.publicKey == key })

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        // MARK: - Syncing deferral
        advertCase("isSyncingContacts defers handler until cleared") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0x16)
            store.saveContact(radioId, advertContactFrame(key, name = "Deferred"))

            service.setSyncingContacts(true)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            // Replaces the 50 ms sleep: the advert was handled and its round ran into the deferral.
            awaitHandled(1)
            awaitIdle(service)
            assertEquals(0, recorder.callCount)

            service.setSyncingContacts(false)
            val ran = advertWaitUntil { recorder.callCount >= 1 }
            service.stopEventMonitoring()
            assertTrue(ran)
        },

        // MARK: - Teardown mid-handler
        advertCase("teardown mid-handler prevents reconcile writes") {
            val store = makeStore()
            val scene = AdvertFakeAppState(isInForeground = true)
            val service = makeService(store, appStateProvider = scene)
            val key = advertPublicKey(0x27)
            val parkKey = advertPublicKey(0x28)
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler {
                hold.waitUntilReleased()
                advertTry { store.saveContact(radioId, advertContactFrame(key, name = "Late")) }
                AdvertContactSyncOutcome.SYNCED
            }
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("entered") { hold.isWaiting }
            scene.hangForegroundChecks()
            session.yieldEvent(MeshEvent.Advertisement(parkKey))
            eventually("parked") { scene.isWaitingOnForegroundCheck }

            val stopped = async { service.stopEventMonitoring() }
            eventually("handler dropped") { service.deltaSyncHandler == null }
            hold.release()
            eventually("saved") { store.fetchContact(radioId, key) != null }
            scene.releaseForegroundCheck()
            stopped.await()

            eventually("cancel-path stamp should insert Discover via touchContactHeard") {
                store.fetchDiscoveredNodes(radioId).any { it.publicKey == key }
            }
            service.finishEvents()
            listener.join()
            assertEquals(0, listener.counter.newContactCount, "reconcile must not notify after stopEventMonitoring")
        },

        // MARK: - Escalation
        advertCase("unknown key missing after success escalates to fullRefetch once") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            // Incremental success (no row) escalates; the full refetch fails and keeps the flag; the
            // final full refetch succeeds and drops the still-missing key.
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            recorder.enqueueResult(AdvertContactSyncOutcome.FAILED)
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            installHandler(service, recorder)
            val key = advertPublicKey(0x38)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            val threeCalls = advertWaitUntil { recorder.callCount >= 3 }
            // Replaces the post-stop 50 ms sleep: idle after the third call proves no fourth round.
            awaitIdle(service)
            service.stopEventMonitoring()
            assertTrue(threeCalls)

            val flags = recorder.fullRefetchFlags
            assertEquals(3, flags.size)
            assertEquals(false, flags[0], "first pass is incremental")
            assertEquals(true, flags[1], "escalated full refetch")
            assertEquals(true, flags[2], "failed full refetch must restore escalate flag")
            assertEquals(3, recorder.callCount)
        },

        // MARK: - Nil handler keeps pending
        advertCase("nil handler leaves keys pending for later install") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x61)
            store.saveContact(radioId, advertContactFrame(key, name = "Pending"))

            // No handler yet: the scheduled round finds nil and leaves the key pending.
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            awaitHandled(1)
            awaitIdle(service)
            assertEquals(0, recorder.callCount)

            installHandler(service, recorder)
            val ran = advertWaitUntil { recorder.callCount >= 1 }
            service.stopEventMonitoring()
            assertTrue(ran, "installing a handler must re-arm pending keys")
        },

        // MARK: - Min interval
        advertCase("min interval delays second delta sync after success") {
            val store = makeStore()
            val clock = AdvertManualClock()
            val service = makeService(store, minInterval = 150.milliseconds, clock = clock)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val keyA = advertPublicKey(0x71)
            val keyB = advertPublicKey(0x72)
            store.saveContact(radioId, advertContactFrame(keyA, name = "A"))
            store.saveContact(radioId, advertContactFrame(keyB, name = "B"))

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(keyA))
            eventually("first") { recorder.callCount >= 1 }

            session.yieldEvent(MeshEvent.Advertisement(keyB))
            // Replaces the 40 ms sleep: the second round is armed and parked on the min interval.
            eventually("second round parked on min interval") {
                session.handledEvents >= 2 && service.deltaSyncTask != null && clock.sleeperDeadlines.isNotEmpty()
            }
            assertEquals(1, recorder.callCount, "second pass must wait for min interval")

            clock.advance(150.milliseconds)
            val second = advertWaitUntil(2.seconds) { recorder.callCount >= 2 }
            service.stopEventMonitoring()
            assertTrue(second)
        },

        // MARK: - 0x8F drops pending key
        advertCase("contactDeleted removes pending key before sync runs") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0x49)
            // Known contact so ContactUpdated after touch proves the advert was handled before 0x8F.
            val saved = store.saveContact(radioId, advertContactFrame(key, name = "Doomed"))
            val listener = listen(service)

            // Defer delta sync so 0x8F can clear the pending map before drain.
            service.setSyncingContacts(true)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("advert handled") { listener.counter.contactUpdatedCount >= 1 }

            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("deleted") { store.fetchContact(radioId, key) == null }
            eventually("cleaned") {
                listener.counter.contactDeletedCleanupCount == 1 && listener.counter.contactDeletedCleanupIDs == listOf(saved.id)
            }

            service.setSyncingContacts(false)
            // Replaces the 50 ms sleep: the pending key was removed, so nothing re-armed.
            awaitIdle(service)
            listener.sync()
            assertEquals(0, recorder.callCount)
            assertEquals(0, listener.counter.newContactCount)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("reconcile skips deleted contact after mid-handler 0x8F") {
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0x4A)
            val listener = listen(service)
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler {
                hold.waitUntilReleased()
                AdvertContactSyncOutcome.SYNCED
            }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("waiting") { hold.isWaiting }
            // Contact never persisted; 0x8F during the handler; reconcile sees no row.
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            hold.release()

            eventually("updated") { listener.counter.contactUpdatedCount >= 1 }
            awaitIdle(service)
            listener.sync()
            assertEquals(0, listener.counter.newContactCount)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("contactDeleted during commit rolls back the resurrected contact") {
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0x4B)
            val listener = listen(service)
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler {
                hold.waitUntilReleased()
                // The batch commit re-saves a row the radio deleted while it was in flight.
                advertTry { store.saveContact(radioId, advertContactFrame(key, name = "Ghost")) }
                AdvertContactSyncOutcome.SYNCED
            }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("waiting") { hold.isWaiting }
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            // Replaces the 40 ms sleep: the 0x8F handler recorded the key before the commit returns.
            awaitHandled(2)
            hold.release()

            eventually("synced") { listener.counter.contactUpdatedCount >= 1 }
            awaitIdle(service)
            assertNull(store.fetchContact(radioId, key), "a row re-saved after the radio deleted it must be rolled back")
            assertTrue(store.fetchDiscoveredNodes(radioId).isEmpty(), "a rolled-back contact must leave no Discover row")
            listener.sync()
            assertEquals(0, listener.counter.newContactCount)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("rollback keeps contact the radio deleted then re-added mid-round") {
            // Overwrite-oldest can free a slot then auto-add the same key inside one round; the
            // re-advert clears the 0x8F tombstone so rollback keeps the re-synced row.
            val store = makeStore()
            val service = makeService(store)
            val advertKey = advertPublicKey(0x93)
            val deletedKey = advertPublicKey(0x94)
            store.saveContact(radioId, advertContactFrame(deletedKey, name = "SlotVictim"))
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler {
                hold.waitUntilReleased()
                // getContacts after the radio re-added the key re-creates the local row.
                advertTry { store.saveContact(radioId, advertContactFrame(deletedKey, name = "ReAdded")) }
                AdvertContactSyncOutcome.SYNCED
            }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(advertKey))
            eventually("waiting") { hold.isWaiting }
            session.yieldEvent(MeshEvent.ContactDeleted(deletedKey))
            eventually("deleted") {
                store.fetchContact(radioId, deletedKey) == null && deletedKey in service.contactsDeletedDuringSync
            }

            // Re-advert: the radio auto-added the contact again mid-round.
            session.yieldEvent(MeshEvent.Advertisement(deletedKey))
            eventually("re-advert must clear the mid-round delete tombstone") {
                deletedKey in service.pendingAdvertKeys && deletedKey !in service.contactsDeletedDuringSync
            }
            hold.release()
            eventually("round done") { service.deltaSyncTask == null }

            val contact = store.fetchContact(radioId, deletedKey)
            assertNotNull(contact, "a contact the radio re-added mid-round must survive rollback")
            assertEquals("ReAdded", contact.name)
            assertFalse(
                deletedKey in service.contactsDeletedDuringSync,
                "tombstone must stay clear so reconcile does not skip the re-added key",
            )
            service.stopEventMonitoring()
        },

        advertCase("contactDeleted during failed commit rolls back and does not re-queue") {
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0x4C)
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler {
                hold.waitUntilReleased()
                // An early batch committed the row before the sync failed.
                advertTry { store.saveContact(radioId, advertContactFrame(key, name = "Ghost")) }
                AdvertContactSyncOutcome.FAILED
            }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("waiting") { hold.isWaiting }
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            awaitHandled(2)
            hold.release()

            eventually("round done") { service.deltaSyncTask == null }
            assertNull(store.fetchContact(radioId, key), "a row committed by a failed sync must still be rolled back")
            assertFalse(key in service.pendingAdvertKeys, "a radio-deleted key must not re-queue for a fetch the radio cannot answer")
            service.stopEventMonitoring()
        },

        advertCase("rollback still runs when teardown cancels the sync mid-commit") {
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0x4D)
            store.saveContact(radioId, advertContactFrame(key, name = "Known"))
            val hold = AdvertHandlerHold()
            val marker = AdvertCommitMarker()
            service.setDeltaSyncHandler {
                hold.waitUntilReleased()
                advertTry { store.saveContact(radioId, advertContactFrame(key, name = "Ghost")) }
                marker.markCommitted()
                AdvertContactSyncOutcome.SYNCED
            }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("waiting") { hold.isWaiting }
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("deleted") { store.fetchContact(radioId, key) == null }

            // Teardown cancels the round and clears the handler while the commit runs.
            service.stopEventMonitoring()
            hold.release()
            eventually("committed") { marker.committed }
            eventually("a commit landing after teardown must still be rolled back") { store.fetchContact(radioId, key) == null }
        },

        advertCase("contact deleted after the commit returns stays tracked for the round") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val advertKey = advertPublicKey(0x4E)
            val deletedKey = advertPublicKey(0x4F)
            recorder.enqueuePersist(advertContactFrame(advertKey, name = "Synced"))
            installHandler(service, recorder)
            store.saveContact(radioId, advertContactFrame(deletedKey, name = "Doomed"))

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(advertKey))
            eventually("committed") { recorder.callCount >= 1 }
            session.yieldEvent(MeshEvent.ContactDeleted(deletedKey))
            val tracked = advertWaitUntil { deletedKey in service.contactsDeletedDuringSync }
            service.stopEventMonitoring()
            assertTrue(tracked, "a delete outside the commit must still be tracked for the round")
        },

        advertCase("reconcile skips a contact the radio deleted this round") {
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0x50)
            store.saveContact(radioId, advertContactFrame(key, name = "Doomed"))
            val listener = listen(service)

            // No handler installed, so no round drains the recorded delete before reconcile.
            startMonitoring(service)
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("tracked") { key in service.contactsDeletedDuringSync }

            // A batch commit re-saved the row the radio dropped.
            store.saveContact(radioId, advertContactFrame(key, name = "Ghost"))
            service.reconcile(setOf(key), setOf(key))
            assertTrue(store.fetchDiscoveredNodes(radioId).isEmpty(), "a deleted key must not gain a Discover row")
            listener.sync()
            assertEquals(0, listener.counter.newContactCount, "a deleted key must not be announced")

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("rolled back key does not escalate to a full refetch") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x52)
            val hold = AdvertHandlerHold()
            service.setDeltaSyncHandler { fullRefetch ->
                val isFirst = recorder.callCount == 0
                val result = recorder.handle(fullRefetch)
                if (isFirst) {
                    hold.waitUntilReleased()
                    advertTry { store.saveContact(radioId, advertContactFrame(key, name = "Ghost")) }
                }
                result
            }

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("waiting") { hold.isWaiting }
            session.yieldEvent(MeshEvent.ContactDeleted(key))
            eventually("tracked") { key in service.contactsDeletedDuringSync }
            hold.release()
            // An escalation would re-arm in the section that finishes the round, so idle is final.
            eventually("round done") { service.deltaSyncTask == null }
            service.stopEventMonitoring()

            assertEquals(listOf(false), recorder.fullRefetchFlags, "a rolled-back key must not refetch")
            assertNull(store.fetchContact(radioId, key))
        },
    )
}
