// AndroidOnly: WP-209 Native cancellation, event-order, boundary, real-session and cleanup-chain cases for the contact port.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.yield
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ContactServiceNativeTests {
    private val radioId = RadioId(UUID.randomUUID())

    private fun meshContact(keyByte: Int, name: String, lastModified: Long = 0) = MeshContact(
        id = contactsKey(keyByte).uppercaseHexString(), publicKey = contactsKey(keyByte), type = ContactType.CHAT,
        flags = ContactFlags(0u), outPathLength = 0u, outPath = Bytes.EMPTY, advertisedName = name,
        lastAdvertisement = Instant.EPOCH, latitude = 0.0, longitude = 0.0, lastModified = Instant.ofEpochSecond(lastModified),
    )

    private fun stored(keyByte: Int, name: String, flags: UByte = 0u, isFavorite: Boolean = false) = ContactDTO(
        radioId = radioId, publicKey = contactsKey(keyByte), name = name, flags = flags, isFavorite = isFavorite,
        lastHeardTimestamp = null,
    )

    @TestFactory
    fun syncAndEventCases(): List<DynamicTest> = listOf(
        contactsNative("syncContacts emits syncProgress 0 of n then received of n to a subscriber registered before the call") {
            val session = ContactsFakeSession().apply {
                setStubbedContacts(listOf(meshContact(0xAA, "A", 10), meshContact(0xBB, "B", 30), meshContact(0xCC, "C", 20)))
            }
            val service = contactsService(session = session, store = ContactsFakeStore(listOf(contactsDevice(radioId))))
            val subscription = service.events()

            val result = service.syncContacts(radioId, since = Instant.ofEpochSecond(5))
            service.finishEvents()

            assertEquals(
                listOf(ContactServiceEvent.SyncProgress(0, 3), ContactServiceEvent.SyncProgress(3, 3)),
                subscription.events.toList(),
            )
            assertEquals(30u, result.lastSyncTimestamp)
            assertEquals(listOf<Instant?>(Instant.ofEpochSecond(5)), session.getContactsInvocations)
        },
        contactsNative("an empty device reply yields lastSyncTimestamp 0 and a full sync with a complete empty snapshot prunes everything but the V-contact") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
            store.seed(stored(0xDD, "Gone"))
            store.seed(ContactDTO(radioId = radioId, publicKey = vKey, name = "v", lastHeardTimestamp = null))
            val cleanup = ContactsRecordingCleanup()

            val result = contactsService(store = store, cleanupCoordinator = cleanup).syncContacts(radioId)

            assertEquals(0u, result.lastSyncTimestamp)
            assertEquals(0L, result.contactsReceived)
            assertEquals(listOf("v"), store.fetchContacts(radioId).map { it.name })
            assertEquals(listOf(ContactCleanupReason.DELETED), cleanup.invocations.map { it.reason })
            assertEquals(contactsKey(0xDD), cleanup.invocations.single().publicKey)
        },
        contactsNative("full sync prune skips a truncated snapshot, a missing contactsStart total and an unknown self key") {
            for (variant in listOf("truncated", "noTotal", "noDevice", "deviceFails")) {
                val devices = if (variant == "noDevice") emptyList() else listOf(contactsDevice(radioId))
                val store = ContactsFakeStore(devices)
                if (variant == "deviceFails") store.fetchDeviceFailure = IllegalStateException("store offline")
                store.seed(stored(0xDD, "Keep"))
                val session = ContactsFakeSession().apply {
                    setStubbedContacts(listOf(meshContact(0xAA, "Alice")))
                    if (variant == "truncated") setStubbedReportedTotal(2)
                    if (variant == "noTotal") setStubbedReportsNoTotal(true)
                }
                contactsService(session = session, store = store).syncContacts(radioId)
                assertNotNull(store.fetchContact(radioId, contactsKey(0xDD)), variant)
            }
        },
        contactsNative("syncContacts maps a session failure to sessionError and passes a store failure through unchanged") {
            val timeout = MeshCoreException.Timeout()
            val session = ContactsFakeSession().apply { getContactsFailure = timeout }
            val mapped = assertFailsWith<ContactServiceError.SessionError> { contactsService(session = session).syncContacts(radioId) }
            assertSame(timeout, mapped.error)
            assertEquals("Mesh operation timed out", mapped.errorDescription)

            val storeFailure = IllegalStateException("disk full")
            val store = object : com.meshcoreone.android.core.contracts.domain.ContactPersisting by ContactsFakeStore() {
                override suspend fun batchSaveContacts(
                    radioId: RadioId,
                    frames: com.meshcoreone.android.core.model.SnapshotList<com.meshcoreone.android.core.model.ContactFrame>,
                ): Long = throw storeFailure
            }
            val service = ContactService(ContactsFakeSession(), ContactsFakeStore(), store, null, null, ContactsFakePreferences())
            assertSame(storeFailure, assertFailsWith<IllegalStateException> { service.syncContacts(radioId) })
        },
        contactsNative("finishEvents completes live collectors and later subscriptions start finished") {
            val service = contactsService()
            val live = service.events()
            val collector = async(start = CoroutineStart.UNDISPATCHED) { live.events.toList() }
            service.finishEvents()
            assertEquals(emptyList(), collector.await())
            assertEquals(emptyList(), service.events().events.toList())
        },
    )

    @TestFactory
    fun cancellationCases(): List<DynamicTest> = listOf(
        contactsNative("syncContactsForRefresh cancelled while waiting for the advert claim propagates cancellation and never fetches") {
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession()
            val service = contactsService(session = session, syncCoordinator = coordinator)
            val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
            val holder = object : com.meshcoreone.android.core.contracts.domain.ContactServiceProtocol {
                override suspend fun syncContacts(radioId: RadioId, since: Instant?) =
                    gate.await().let { com.meshcoreone.android.core.contracts.domain.ContactSyncResult(0, 0u, true) }
            }
            val advert = async { coordinator.performAdvertContactSync(radioId, holder) }
            yield()

            val refresh = async { service.syncContactsForRefresh(radioId) }
            delay(50.milliseconds)
            refresh.cancelAndJoin()

            assertTrue(refresh.isCancelled)
            assertFalse(coordinator.manualContactSyncActive)
            assertTrue(session.getContactsInvocations.isEmpty())
            gate.complete(Unit)
            assertEquals(ContactsAdvertOutcome.SYNCED, advert.await())
        },
        contactsNative("syncContactsForRefresh cancelled mid-fetch still clears the manual claim") {
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession().apply { holdNextGetContacts() }
            val service = contactsService(session = session, syncCoordinator = coordinator)

            val refresh = async { service.syncContactsForRefresh(radioId) }
            session.waitForGetContactsStart()
            assertTrue(coordinator.manualContactSyncActive)
            refresh.cancelAndJoin()

            assertTrue(refresh.isCancelled)
            assertFalse(coordinator.manualContactSyncActive)
            assertEquals(listOf("manual:true", "manual:false"), coordinator.notifications)
        },
        contactsNative("syncContactsForRefresh clears the claim after a failed sync and rethrows the mapped error") {
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession().apply { getContactsFailure = MeshCoreException.NotConnected() }
            val service = contactsService(session = session, syncCoordinator = coordinator)
            assertFailsWith<ContactServiceError.SessionError> { service.syncContactsForRefresh(radioId) }
            assertEquals(listOf("manual:true", "manual:false"), coordinator.notifications)
        },
        contactsNative("migrateAppFavoritesToDevice propagates cancellation rather than counting it as a failed contact") {
            val store = ContactsFakeStore()
            store.seed(stored(0xA1, "Fav", isFavorite = true))
            val session = ContactsFakeSession().apply {
                changeContactFlagsFailures = mapOf(contactsKey(0xA1) to CancellationException("cancelled"))
            }
            val preferences = ContactsFakePreferences()
            assertFailsWith<CancellationException> {
                contactsService(session = session, store = store, preferences = preferences).migrateAppFavoritesToDevice(radioId)
            }
            assertFalse(preferences.bool(ContactService.FAVORITES_MIGRATION_KEY))
        },
    )

    @TestFactory
    fun removalAndErrorMappingCases(): List<DynamicTest> = listOf(
        contactsNative("removeContact ignores the ZephCore V-contact: no device remove, no local delete, no nodeDeleted") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val vKey = assertNotNull(VContactIdentity.publicKey(contactsKey(0x01)))
            store.seed(ContactDTO(radioId = radioId, publicKey = vKey, name = "v", lastHeardTimestamp = null))
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store)
            val events = service.events()

            service.removeContact(radioId, vKey)
            service.removeLocalContact(EntityKey(radioId, store.contacts.keys.single()), vKey)
            service.finishEvents()

            assertTrue(session.removeContactPublicKeys.isEmpty())
            assertEquals(1, store.contacts.size)
            assertEquals(emptyList(), events.events.toList())
        },
        contactsNative("removeContact deletes, cleans up, emits nodeDeleted and notifies; device NOT_FOUND maps to contactNotFound") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val contact = stored(0x22, "Bob").also(store::seed)
            val cleanup = ContactsRecordingCleanup()
            val coordinator = ContactsFakeSyncCoordinator()
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store, syncCoordinator = coordinator, cleanupCoordinator = cleanup)
            val events = service.events()

            service.removeContact(radioId, contact.publicKey)
            assertEquals(listOf(EntityKey(radioId, contact.id)), cleanup.invocations.map { it.contact })
            assertEquals(listOf("contactsChanged"), coordinator.notifications)

            session.removeContactFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.ContactNotFound> { service.removeContact(radioId, contact.publicKey) }
            session.removeContactFailure = MeshCoreException.DeviceError(3u)
            assertFailsWith<ContactServiceError.SessionError> { service.removeContact(radioId, contact.publicKey) }
            service.finishEvents()
            assertEquals(listOf<ContactServiceEvent>(ContactServiceEvent.NodeDeleted), events.events.toList())
        },
        contactsNative("removeLocalContact propagates lookup failure without deletion, cleanup or success events; a verified retry succeeds") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val contact = stored(0x33, "Carol").also(store::seed)
            val lookupFailure = IllegalStateException("lookup failed")
            store.fetchContactByKeyFailure = lookupFailure
            val cleanup = ContactsRecordingCleanup()
            val coordinator = ContactsFakeSyncCoordinator()
            val service = contactsService(store = store, syncCoordinator = coordinator, cleanupCoordinator = cleanup)
            val events = service.events()

            assertSame(lookupFailure, assertFailsWith<IllegalStateException> {
                service.removeLocalContact(EntityKey(radioId, contact.id), contact.publicKey)
            })
            service.finishEvents()
            assertEquals(contact, store.fetchContact(radioId, contact.publicKey))
            assertTrue(store.deletedContactIDs.isEmpty())
            assertTrue(cleanup.invocations.isEmpty())
            assertTrue(coordinator.notifications.isEmpty())
            assertEquals(emptyList(), events.events.toList())

            store.fetchContactByKeyFailure = null
            val retry = contactsService(store = store, syncCoordinator = coordinator, cleanupCoordinator = cleanup)
            val retryEvents = retry.events()
            retry.removeLocalContact(EntityKey(radioId, contact.id), contact.publicKey)
            retry.finishEvents()
            assertEquals(listOf(contact.id), store.deletedContactIDs)
            assertEquals(listOf(ContactCleanupReason.DELETED), cleanup.invocations.map { it.reason })
            assertEquals(listOf<ContactServiceEvent>(ContactServiceEvent.NodeDeleted), retryEvents.events.toList())
            assertEquals(listOf("contactsChanged"), coordinator.notifications)
        },
        contactsNative("device error codes map to the Swift ContactServiceError cases per operation") {
            val store = ContactsFakeStore()
            val contact = stored(0x44, "Dan").also(store::seed)
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store)
            val frame = contact.toContactFrame()

            session.addContactFailure = MeshCoreException.DeviceError(3u)
            assertFailsWith<ContactServiceError.ContactTableFull> { service.addOrUpdateContact(radioId, frame) }
            session.addContactFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.SessionError> { service.addOrUpdateContact(radioId, frame) }
            session.shareContactFailure = MeshCoreException.DeviceError(3u)
            assertFailsWith<ContactServiceError.ShareContactUnavailable> { service.shareContact(contact.publicKey) }
            session.shareContactFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.ContactNotFound> { service.shareContact(contact.publicKey) }
            session.shareContactFailure = MeshCoreException.DeviceError(4u)
            assertFailsWith<ContactServiceError.SessionError> { service.shareContact(contact.publicKey) }
            session.resetPathFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.ContactNotFound> { service.resetPath(radioId, contact.publicKey) }
            session.sendPathDiscoveryFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.ContactNotFound> { service.sendPathDiscovery(radioId, contact.publicKey) }
            session.sendPathDiscoveryFailure = MeshCoreException.DeviceError(3u)
            assertFailsWith<ContactServiceError.SessionError> { service.sendPathDiscovery(radioId, contact.publicKey) }
            session.importContactFailure = MeshCoreException.DeviceError(2u)
            assertFailsWith<ContactServiceError.SessionError> { service.importContact(Bytes.of(1)) }
            session.exportContactFailure = MeshCoreException.Timeout()
            @Suppress("DEPRECATION")
            assertFailsWith<ContactServiceError.SessionError> { service.exportContact(null) }
            assertEquals(emptyList(), store.deletedContactIDs)
            assertEquals(contact, store.contacts[contact.id])
        },
        contactsNative("setPath stamps lastModified from the injected clock, normalises the type byte and pushes to the device") {
            val store = ContactsFakeStore()
            val contact = stored(0x55, "Eve").copy(typeRawValue = 0x7Fu, lastHeardTimestamp = 9u).also(store::seed)
            val session = ContactsFakeSession()
            val clock = Clock.fixed(Instant.ofEpochSecond(1_800_000_000, 900_000_000), ZoneOffset.UTC)
            val service = contactsService(session = session, store = store, clock = clock)

            service.setPath(radioId, contact.publicKey, Bytes.of(0x0A, 0x0B), 2u)

            val pushed = session.addContactInvocations.single()
            assertEquals(1_800_000_000L, pushed.lastModified.epochSecond)
            assertEquals(ContactType.CHAT.rawValue, pushed.typeRawValue)
            assertEquals(Bytes.of(0x0A, 0x0B), pushed.outPath)
            val saved = assertNotNull(store.fetchContact(radioId, contact.publicKey))
            assertEquals(1_800_000_000u, saved.lastModified)
            assertEquals(2u.toUByte(), saved.outPathLength)
            assertFailsWith<ContactServiceError.ContactNotFound> {
                service.setPath(radioId, contactsKey(0x99), Bytes.EMPTY, 0u)
            }
        },
    )

    @TestFactory
    fun flagAndPreferenceCases(): List<DynamicTest> = listOf(
        contactsNative("setTelemetryPermissions sets and clears bits 1-3 preserving the favourite bit") {
            val store = ContactsFakeStore()
            val contact = stored(0x66, "Fay", flags = 0x01u).also(store::seed)
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store)
            val key = EntityKey(radioId, contact.id)

            service.setTelemetryPermissions(key, granted = true)
            assertEquals(0x0Fu.toUByte(), store.contacts[contact.id]?.flags)
            assertTrue(ContactService.hasTelemetryPermissions(0x0Fu))
            service.setTelemetryPermissions(key, granted = false)
            assertEquals(0x01u.toUByte(), store.contacts[contact.id]?.flags)
            assertFalse(ContactService.hasTelemetryPermissions(0x01u))
            assertTrue(ContactService.hasTelemetryPermissions(0x04u))
            // The device record carries the pre-change flags with the new flags as the argument.
            assertEquals(
                listOf(0x01u to 0x0Fu, 0x0Fu to 0x01u).map { (old, new) -> old.toUByte() to new.toUByte() },
                session.changeContactFlagsInvocations.map { it.contact.flags.rawValue to it.flags.rawValue },
            )
        },
        contactsNative("setContactFavorite persists nothing when the device rejects the flag change") {
            val store = ContactsFakeStore()
            val contact = stored(0x77, "Gus").also(store::seed)
            val session = ContactsFakeSession().apply {
                changeContactFlagsFailures = mapOf(contact.publicKey to MeshCoreException.Timeout())
            }
            assertFailsWith<ContactServiceError.SessionError> {
                contactsService(session = session, store = store).setContactFavorite(EntityKey(radioId, contact.id), true)
            }
            assertEquals(contact, store.contacts[contact.id])
        },
        contactsNative("migrateAppFavoritesToDevice marks complete only when every favourite reaches the device") {
            val store = ContactsFakeStore()
            store.seed(stored(0xA1, "One", isFavorite = true))
            store.seed(stored(0xA2, "Two", isFavorite = true))
            store.seed(stored(0xA3, "AlreadyOnDevice", flags = 0x01u, isFavorite = true))
            store.seed(stored(0xA4, "NotFavourite"))
            val session = ContactsFakeSession().apply {
                changeContactFlagsFailures = mapOf(contactsKey(0xA2) to MeshCoreException.Timeout())
            }
            val preferences = ContactsFakePreferences()
            val service = contactsService(session = session, store = store, preferences = preferences)

            assertEquals(1L, service.migrateAppFavoritesToDevice(radioId))
            assertFalse(preferences.bool(ContactService.FAVORITES_MIGRATION_KEY))

            session.changeContactFlagsFailures = emptyMap()
            assertEquals(1L, service.migrateAppFavoritesToDevice(radioId))
            assertTrue(preferences.bool(ContactService.FAVORITES_MIGRATION_KEY))
            assertEquals(0L, service.migrateAppFavoritesToDevice(radioId))
            assertEquals(3, session.changeContactFlagsInvocations.size)
        },
        contactsNative("updateContactPreferences trims Foundation whitespacesAndNewlines, not Kotlin isWhitespace") {
            val store = ContactsFakeStore()
            val contact = stored(0x88, "Hal").also(store::seed)
            val service = contactsService(store = store)
            val key = EntityKey(radioId, contact.id)

            service.updateContactPreferences(key, nickname = " \tRico \u0085")
            assertEquals("Rico", store.contacts[contact.id]?.nickname)
            service.updateContactPreferences(key, nickname = "\u001FRico\u001F")
            assertEquals("\u001FRico\u001F", store.contacts[contact.id]?.nickname)
        },
        contactsNative("model-rebuilding updates persist a nil lastHeardTimestamp as 0; the avatar update keeps nil") {
            val store = ContactsFakeStore()
            val contact = stored(0x89, "Ida").also(store::seed)
            val service = contactsService(store = store)
            val key = EntityKey(radioId, contact.id)

            service.updateContactAvatar(key, Bytes.of(1))
            assertNull(store.contacts[contact.id]?.lastHeardTimestamp)
            service.updateContactOCVSettings(key, "liIon", null)
            assertEquals(0u, store.contacts[contact.id]?.lastHeardTimestamp)
            assertEquals("liIon", store.contacts[contact.id]?.ocvPreset)
            assertFailsWith<ContactServiceError.ContactNotFound> {
                service.updateContactOCVSettings(EntityKey(radioId, UUID.randomUUID()), "x", null)
            }
        },
    )

    @TestFactory
    fun uriAndShareCases(): List<DynamicTest> = listOf(
        contactsNative("exportContactURI percent-encodes query-significant characters like URLComponents plus MeshCoreURIQuery") {
            val key = assertNotNull(applicationBytesFromHex(SHARE_HEX))
            fun uri(name: String, type: ContactType = ContactType.CHAT) = ContactService.exportContactURI(name, key, type)
            assertEquals("meshcore://contact/add?name=Node&public_key=$SHARE_HEX&type=1", uri("Node"))
            assertEquals("meshcore://contact/add?name=a%20%26%20b&public_key=$SHARE_HEX&type=3", uri("a & b", ContactType.ROOM))
            assertTrue(uri("C++ dev").contains("name=C%2B%2B%20dev&"))
            assertTrue(uri("100% sure?").contains("name=100%25%20sure?&"))
            assertTrue(uri("a#b=c").contains("name=a%23b%3Dc&"))
            assertTrue(uri("北京").contains("name=%E5%8C%97%E4%BA%AC&"))
            assertTrue(uri("12:30/@'*,;!\$()~._-").contains("name=12:30/@'*,;!\$()~._-&"))
            assertTrue(uri("Alice&public_key=BC&type=3").startsWith("meshcore://contact/add?name=Alice%26public_key%3DBC%26type%3D3&"))
        },
        contactsNative("parseShare follows ICU first-match semantics when the first token has a non-ASCII digit type") {
            val text = "<$SHARE_HEX:٣:first> <$SHARE_HEX:2:second>"
            assertNull(ContactShareUtilities.parseShare(text))
            assertEquals(listOf("second"), ContactShareUtilities.extractShares(text).map { it.name })
            assertEquals(ContactType.ROOM, ContactShareUtilities.parseShare("<${SHARE_HEX.lowercase()}:003:x>")?.contactType)
        },
        contactsNative("share tokens use grapheme semantics for the terminator and separators") {
            val key = assertNotNull(applicationBytesFromHex(SHARE_HEX))
            // A '>' fused with a combining mark is a different Character in Swift and is kept.
            assertEquals("<$SHARE_HEX:1:a>́b>", ContactShareUtilities.formatShare(key, ContactType.CHAT, "a>́b>"))
            // A ':' fused with a combining mark does not split the type from the name.
            assertNull(ContactShareUtilities.parseShare("<$SHARE_HEX:1:́name>"))
            assertEquals("́", ContactResult("x", key, ContactType.CHAT).id.let { "́" })
            assertEquals(SHARE_HEX, ContactResult("x", key, ContactType.CHAT).id)
        },
    )

    @TestFactory
    fun cleanupCoordinatorCases(): List<DynamicTest> = listOf(
        contactsNative("cleanup coordinator: block deletes channel messages, refreshes the cache, notifies, clears notifications and badge") {
            val fixture = CleanupFixture()
            val contact = stored(0x10, "Blocked").also(fixture.store::seed)

            fixture.coordinator.handleCleanup(EntityKey(radioId, contact.id), ContactCleanupReason.BLOCKED, contact.publicKey)

            assertEquals(listOf("Blocked" to radioId), fixture.store.deletedChannelMessageSenders)
            assertEquals(listOf("refreshBlocked:${radioId.value}", "conversationsChanged"), fixture.sync.notifications)
            assertEquals(listOf("remove:${contact.id}", "badge"), fixture.calls)
        },
        contactsNative("cleanup coordinator: unblock refreshes without deleting messages or notifications") {
            val fixture = CleanupFixture()
            val contact = stored(0x11, "Unblocked").also(fixture.store::seed)

            fixture.coordinator.handleCleanup(EntityKey(radioId, contact.id), ContactCleanupReason.UNBLOCKED, contact.publicKey)

            assertTrue(fixture.store.deletedChannelMessageSenders.isEmpty())
            assertEquals(listOf("refreshBlocked:${radioId.value}", "conversationsChanged"), fixture.sync.notifications)
            assertEquals(listOf("badge"), fixture.calls)
        },
        contactsNative("cleanup coordinator: delete removes the matching remote node session for the coordinator radio") {
            val fixture = CleanupFixture()
            val key = contactsKey(0x12)
            val session = RemoteNodeSessionDTO(radioId = radioId, publicKey = key, name = "Rpt", role = RemoteNodeRole.REPEATER)
            fixture.store.seed(session)
            fixture.store.seed(RemoteNodeSessionDTO(radioId = radioId, publicKey = contactsKey(0x13), name = "Other", role = RemoteNodeRole.REPEATER))
            val contactId = UUID.randomUUID()

            fixture.coordinator.handleCleanup(EntityKey(radioId, contactId), ContactCleanupReason.DELETED, key)

            assertEquals(listOf("remove:$contactId", "badge", "session:${session.id}"), fixture.calls)
            assertEquals(listOf("conversationsChanged"), fixture.sync.notifications)
        },
        contactsNative("cleanup coordinator: failing optional steps do not stop the chain") {
            val fixture = CleanupFixture(failSessionRemoval = true)
            fixture.store.fetchContactByKeyFailure = IllegalStateException("lookup")
            fixture.coordinator.handleCleanup(EntityKey(radioId, UUID.randomUUID()), ContactCleanupReason.BLOCKED, contactsKey(1))
            assertTrue(fixture.sync.notifications.isEmpty())
            assertEquals(2, fixture.calls.size)
            assertEquals("badge", fixture.calls.last())

            fixture.store.seed(RemoteNodeSessionDTO(radioId = radioId, publicKey = contactsKey(2), name = "R", role = RemoteNodeRole.REPEATER))
            fixture.coordinator.handleCleanup(EntityKey(radioId, UUID.randomUUID()), ContactCleanupReason.DELETED, contactsKey(2))
            assertEquals(listOf("conversationsChanged"), fixture.sync.notifications)

            fixture.store.fetchSessionsFailure = IllegalStateException("sessions")
            fixture.coordinator.handleCleanup(EntityKey(radioId, UUID.randomUUID()), ContactCleanupReason.DELETED, contactsKey(2))
            assertEquals(listOf("conversationsChanged", "conversationsChanged"), fixture.sync.notifications)
        },
    )

    private inner class CleanupFixture(failSessionRemoval: Boolean = false) {
        val store = ContactsFakeStore()
        val sync = ContactsFakeSyncCoordinator()
        private val lock = Any()
        private val recorded = mutableListOf<String>()
        val calls: List<String> get() = synchronized(lock) { recorded.toList() }
        val coordinator = ContactCleanupCoordinator(
            contacts = store,
            rooms = store,
            syncCoordinator = sync,
            notificationService = object : ContactCleanupNotifications {
                override suspend fun removeDeliveredNotifications(contactId: UUID) = synchronized(lock) { recorded += "remove:$contactId" }
                override suspend fun updateBadgeCount() = synchronized(lock) { recorded += "badge" }
            },
            remoteNodeService = object : ContactCleanupRemoteSessions {
                override suspend fun removeSession(id: UUID, publicKey: Bytes) {
                    synchronized(lock) { recorded += "session:$id" }
                    if (failSessionRemoval) throw IllegalStateException("keychain")
                }
            },
            radioId = radioId,
        )
    }

    private companion object {
        const val SHARE_HEX = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"
    }
}
