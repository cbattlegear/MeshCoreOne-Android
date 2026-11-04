// PortedFrom: MC1Services/Tests/MC1ServicesTests/AdvertisementServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.TextType
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.event.PathInfo
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

private const val PRUNE_DAYS = 30L
private const val SECONDS_PER_DAY = 86_400L

private fun pruneCutoff(): UInt = Instant.now().minusSeconds(PRUNE_DAYS * SECONDS_PER_DAY).epochSecond.toUInt()

/** Swift's full `MessageDTO(...)` orphan DM literal. */
private fun orphanDirectMessage(id: UUID, radioId: RadioId, text: String, timestamp: UInt, prefix: Bytes) = MessageDTO(
    id = id, radioId = radioId, contactID = null, channelIndex = null, text = text, timestamp = timestamp,
    createdAt = Instant.now(), direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
    textType = TextType.PLAIN, ackCode = null, pathLength = 0u, snr = null, pathNodes = null,
    senderKeyPrefix = prefix, senderNodeName = null, isRead = false, replyToID = null, roundTripTime = null,
    heardRepeats = 0, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
)

/** Snapshot failures, orphan DM adoption, materialize, store errors, prune safety and path responses. */
class AdvertisementServiceStoreTest {
    @TestFactory
    fun originalCases(): List<DynamicTest> = listOf(
        advertCase("snapshot fetch failure does not invent new-contact notifications") {
            val store = makeMockStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x8B)
            // Known local contact: a failed snapshot must not treat it as inserted.
            store.saveContact(radioId, advertContactFrame(key, name = "Known"))
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            installHandler(service, recorder)
            val listener = listen(service)

            startMonitoring(service)
            // Fail only the snapshot read used by the round (not touch / reconcile).
            store.fetchContactPublicKeysError = AdvertStoreUnavailable()
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("ran") { recorder.callCount >= 1 }
            awaitIdle(service)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
            assertEquals(
                0, listener.counter.newContactCount,
                "snapshot failure must suppress new-contact notifications (prefer miss over false notify)",
            )
        },

        advertCase("snapshot fetch failure still adopts orphaned DMs for drained keys") {
            // A failed pre-round snapshot must not empty the adoption keys.
            val store = makeMockStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x8C)
            val messageID = UUID.randomUUID()
            store.saveMessage(orphanDirectMessage(messageID, radioId, "orphan before delta", 1_700_000_400u, key.prefix(6)))
            recorder.enqueuePersist(advertContactFrame(key, name = "LateNode"))
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            installHandler(service, recorder)
            startMonitoring(service)
            store.fetchContactPublicKeysError = AdvertStoreUnavailable()
            session.yieldEvent(MeshEvent.Advertisement(key))

            eventually("ran") { recorder.callCount >= 1 }
            val linked = advertWaitUntil { store.message(messageID)?.contactID != null }
            service.stopEventMonitoring()
            assertTrue(linked, "snapshot failure must not permanently orphan DMs already received")
            val contact = assertNotNull(store.fetchContact(radioId, key))
            assertEquals(contact.id, store.message(messageID)?.contactID)
            assertEquals(1L, contact.unreadCount)
        },

        advertCase("adopting orphaned DMs emits conversationsChanged") {
            // Adoption creates a conversation row; the chat list reloads on conversations, not contacts.
            val store = makeMockStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x8E)
            val messageID = UUID.randomUUID()
            store.saveMessage(orphanDirectMessage(messageID, radioId, "orphan needs conversation signal", 1_700_000_500u, key.prefix(6)))
            recorder.enqueuePersist(advertContactFrame(key, name = "AdoptedDM"))
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            installHandler(service, recorder)
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("ran") { recorder.callCount >= 1 }
            eventually("linked") { store.message(messageID)?.contactID != null }
            eventually("adoption must emit conversationsChanged so the chat list reloads") {
                listener.counter.conversationsChangedCount >= 1
            }

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
            assertTrue(listener.counter.conversationsChangedCount >= 1)
            assertTrue(listener.counter.contactUpdatedCount >= 1, "contactUpdated must still fire for Discover")
        },

        advertCase("adopting orphaned DMs emits orphanDirectMessagesAdopted with the contact") {
            // The adopted DM never notified at receipt; the notification owner needs the contact.
            val store = makeMockStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x9E)
            val messageID = UUID.randomUUID()
            store.saveMessage(orphanDirectMessage(messageID, radioId, "orphan awaiting notification", 1_700_000_600u, key.prefix(6)))
            recorder.enqueuePersist(advertContactFrame(key, name = "AdoptedDM"))
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            installHandler(service, recorder)
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("linked") { store.message(messageID)?.contactID != null }
            val contact = assertNotNull(store.fetchContact(radioId, key))
            eventually("adoption must announce the contact so the banner and badge fire") {
                contact.id in listener.counter.adoptedContactIDs
            }

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
        },

        advertCase("delta round with no adoption does not emit conversationsChanged") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x8F)
            store.saveContact(radioId, advertContactFrame(key, name = "NoOrphans"))
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            installHandler(service, recorder)
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("ran") { recorder.callCount >= 1 }
            eventually("updated") { listener.counter.contactUpdatedCount >= 1 }
            // Replaces the 80 ms yield: the round finished and every emitted event was counted.
            awaitIdle(service)
            listener.sync()

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
            assertEquals(0, listener.counter.conversationsChangedCount, "rounds that adopt nothing must not emit conversationsChanged")
        },

        advertCase("materializeContactForPendingAdvert creates contact for unique pending key") {
            // A DM in the debounce window needs a Contact row immediately; materialize from the 0x80 key.
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0x8D)
            session.setStubbedContact(advertMeshContact(key, name = "DebounceNode"), key)

            // No delta handler: the advert records the pending key and never inserts a row.
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("pending") { key in service.pendingAdvertKeys }
            assertNull(store.fetchContact(radioId, key))

            val prefix = key.prefix(6)
            val contact = assertNotNull(service.materializeContactForPendingAdvert(prefix, radioId))
            assertEquals(key, contact.publicKey)
            assertEquals("DebounceNode", contact.name)
            assertTrue(key in service.pendingAdvertKeys, "key must stay pending for delta reconcile")
            assertTrue(key in session.getContactPublicKeys)
            // Prefix lookup is what message polling uses for the next DM hop.
            assertEquals(contact.id, store.fetchContactByPrefix(radioId, prefix)?.id)
            service.stopEventMonitoring()
        },

        advertCase("materializeContactForPendingAdvert returns nil without pending match") {
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0x8E)
            session.setStubbedContact(advertMeshContact(key, name = "NoPending"), key)

            startMonitoring(service)
            assertNull(service.materializeContactForPendingAdvert(key.prefix(6), radioId))
            assertTrue(session.getContactPublicKeys.isEmpty())
            service.stopEventMonitoring()
        },

        advertCase("materializeContactForPendingAdvert returns nil on multi-match prefix") {
            val store = makeStore()
            val service = makeService(store)
            // Two 32-byte keys that share the 1-byte prefix used for the query.
            val keyA = Bytes(advertPublicKey(0x8F).toByteArray().also { it[0] = 0xAB.toByte() })
            val keyB = Bytes(advertPublicKey(0x90).toByteArray().also { it[0] = 0xAB.toByte() })
            assertNotEquals(keyA, keyB)

            service.recordPendingAdvertKey(keyA)
            service.recordPendingAdvertKey(keyB)
            assertNull(service.materializeContactForPendingAdvert(Bytes.of(0xAB), radioId))
            assertTrue(session.getContactPublicKeys.isEmpty())
        },

        advertCase("empty schedule with no pending work does not call the handler") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)

            startMonitoring(service)
            // Schedule without pending keys or paths: the empty-round guard must no-op.
            service.scheduleDeltaSync()
            awaitIdle(service)
            assertEquals(0, recorder.callCount)
            service.stopEventMonitoring()
        },

        advertCase("busy outcome does not stamp min-interval when backoff is non-zero") {
            val store = makeStore()
            val clock = AdvertManualClock()
            // Busy must not stamp lastDeltaSyncEnd; a 30 s min interval would block the re-arm.
            val service = makeService(store, minInterval = 30.seconds, busyBackoff = 20.milliseconds, clock = clock)
            val recorder = AdvertHandlerRecorder(store, radioId)
            recorder.enqueueResult(AdvertContactSyncOutcome.BUSY)
            recorder.enqueueResult(AdvertContactSyncOutcome.SYNCED)
            installHandler(service, recorder)
            val key = advertPublicKey(0x87)
            store.saveContact(radioId, advertContactFrame(key, name = "Busy"))
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))

            eventually("busy retry parked on its backoff") { recorder.callCount == 1 && clock.sleeperDeadlines.isNotEmpty() }
            clock.advance(20.milliseconds)
            val second = advertWaitUntil(2.seconds) { recorder.callCount >= 2 }
            service.stopEventMonitoring()
            assertTrue(second, "busy must re-arm on busy backoff, not the 30s min interval")
        },

        advertCase("touch failure does not announce a known contact as new") {
            // A failed touch cannot tell known from unknown; prefer losing the round over a false notify.
            val store = makeMockStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0x51)
            store.saveContact(radioId, advertContactFrame(key, name = "LongKnown"))
            store.touchContactHeardError = AdvertStoreUnavailable()
            val listener = listen(service)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("retried") { store.touchContactHeardCallCount >= 2 }
            awaitHandled(1)
            awaitIdle(service)

            service.stopEventMonitoring()
            service.finishEvents()
            listener.join()
            assertEquals(0, recorder.callCount, "no key recorded means empty-round guard skips")
            assertEquals(0, listener.counter.newContactCount, "a store error must not record a known contact as unknown")
        },

        advertCase("contact lookup failure does not escalate to full refetch") {
            val store = makeMockStore()
            store.fetchContactError = AdvertStoreUnavailable()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)

            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(advertPublicKey(0x96)))
            eventually("ran") { recorder.callCount >= 1 }
            awaitIdle(service)
            service.stopEventMonitoring()

            assertEquals(1, recorder.callCount, "a local read failure must not trigger a radio refetch")
            assertEquals(listOf(false), recorder.fullRefetchFlags)
        },

        // MARK: - Prune safety
        advertCase("fresh lastHeard protects contact with old lastModified from prune") {
            val store = makeStore()
            val oldStamp = 1_000_000u
            val freshStamp = Instant.now().epochSecond.toUInt()
            val key = advertPublicKey(0x5A)
            store.saveContact(advertTestContact(radioId, key, "StaleRadio", oldStamp, freshStamp))

            // Production prune path: matchesStaleNodePrune on fetched DTOs.
            val fetched = assertNotNull(store.fetchContact(radioId, key))
            val cutoff = pruneCutoff()
            assertTrue(fetched.lastModified < cutoff)
            assertFalse(fetched.matchesStaleNodePrune(cutoff))

            // Control: old lastHeard and lastModified both stale, so it would prune.
            val staleKey = advertPublicKey(0x5B)
            store.saveContact(advertTestContact(radioId, staleKey, "TrulyStale", oldStamp, oldStamp))
            assertTrue(assertNotNull(store.fetchContact(radioId, staleKey)).matchesStaleNodePrune(cutoff))
        },

        advertCase("unknown advert contact stamped lastHeard survives stale-node prune") {
            // An unknown 0x80 cannot touch lastHeard until the delta insert, which starts at 0.
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            val key = advertPublicKey(0x5C)
            val oldStamp = 1_000_000u
            recorder.enqueuePersist(advertContactFrame(key, name = "JustHeard", lastAdvertTimestamp = oldStamp, lastModified = oldStamp))
            installHandler(service, recorder)

            val beforeAdvert = Instant.now().minusSeconds(1)
            startMonitoring(service)
            session.yieldEvent(MeshEvent.Advertisement(key))
            eventually("post-delta stamp must set lastHeardTimestamp after insert") {
                (store.fetchContact(radioId, key)?.lastHeardTimestamp ?: 0u) > 0u
            }

            val contact = assertNotNull(store.fetchContact(radioId, key))
            val cutoff = pruneCutoff()
            assertEquals(oldStamp, contact.lastModified)
            assertTrue(contact.lastModified < cutoff)
            assertTrue((contact.lastHeardTimestamp ?: 0u) >= beforeAdvert.epochSecond.toUInt())
            assertTrue(contact.recencyTimestamp >= beforeAdvert.epochSecond.toUInt())
            assertFalse(contact.matchesStaleNodePrune(cutoff))
            // The radio-sourced timestamps alone would still fall before the cutoff.
            assertTrue(oldStamp < cutoff)
            service.stopEventMonitoring()
        },

        advertCase("favorite contact with stale recency does not match stale-node prune") {
            val oldStamp = 1_000_000u
            val cutoff = pruneCutoff()
            val favorite = advertTestContact(radioId, advertPublicKey(0x5D), "FavoriteStale", oldStamp, oldStamp, isFavorite = true)
            assertTrue(favorite.recencyTimestamp < cutoff)
            assertFalse(favorite.matchesStaleNodePrune(cutoff))
        },

        // MARK: - Path discovery response lastHeard
        advertCase("pathResponse stamps lastHeard and preserves radio lastModified") {
            val store = makeStore()
            val service = makeService(store)
            val key = advertPublicKey(0xA5)
            val radioLastMod = 1_700_000_100u
            store.saveContact(radioId, advertContactFrame(key, name = "PathPeer", lastModified = radioLastMod))
            startMonitoring(service)

            session.yieldEvent(MeshEvent.PathResponse(PathInfo(key.prefix(6), 0u, Bytes.EMPTY, 0u, Bytes.EMPTY)))
            eventually("stamped") { (store.fetchContact(radioId, key)?.lastHeardTimestamp ?: 0u) > 0u }
            val updated = assertNotNull(store.fetchContact(radioId, key))
            assertEquals(radioLastMod, updated.lastModified)
            assertTrue((updated.lastHeardTimestamp ?: 0u) > 0u)
            service.stopEventMonitoring()
        },

        advertCase("pathUpdate does not stamp lastHeard") {
            val store = makeStore()
            val service = makeService(store)
            val recorder = AdvertHandlerRecorder(store, radioId)
            installHandler(service, recorder)
            val key = advertPublicKey(0xA6)
            store.saveContact(radioId, advertContactFrame(key, name = "PathUpdatePeer", lastModified = 1_700_000_100u))

            startMonitoring(service)
            session.yieldEvent(MeshEvent.PathUpdate(key))
            eventually("ran") { recorder.callCount >= 1 }
            val updated = assertNotNull(store.fetchContact(radioId, key))
            assertEquals(0u, updated.lastHeardTimestamp ?: 0u)
            service.stopEventMonitoring()
        },
    )
}
