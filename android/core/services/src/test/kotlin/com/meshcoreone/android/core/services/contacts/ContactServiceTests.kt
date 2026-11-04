// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ContactServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.contracts.domain.ContactSyncResult
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.ContactFrame
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.parser.Parsers
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ContactServiceTests {
    private val suite = "ContactServiceTests"

    // Sync result test values
    private val testContactsReceived = 5L
    private val testSyncTimestamp: UInt = 1_234_567_890u
    private val maxContactsReceived = Long.MAX_VALUE
    private val maxSyncTimestamp = UInt.MAX_VALUE

    // Contact test values
    private val testPublicKey = Bytes(ByteArray(32) { (it + 1).toByte() })
    private val testTimestamp: UInt = 1_700_000_000u
    private val testModifiedTimestamp: UInt = 1_700_000_100u
    private val testFlags: UByte = 0x01u
    private val invalidContactType = 0xFF
    private val testOutPath = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)

    private fun contact(
        id: UUID,
        radioId: RadioId,
        unreadCount: Long = 0,
        unreadMentionCount: Long = 0,
        nickname: String? = null,
        isBlocked: Boolean = false,
        isMuted: Boolean = false,
        isFavorite: Boolean = false,
        lastMessageDate: Instant? = null,
        ocvPreset: String? = null,
        customOCVArrayString: String? = null,
    ) = ContactDTO(
        id = id, radioId = radioId, publicKey = testPublicKey, name = "TestContact",
        typeRawValue = ContactType.CHAT.rawValue, flags = 0u, outPathLength = 0u, outPath = Bytes.EMPTY,
        lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0, lastModified = 0u, lastHeardTimestamp = null,
        nickname = nickname, isBlocked = isBlocked, isMuted = isMuted, isFavorite = isFavorite,
        lastMessageDate = lastMessageDate, unreadCount = unreadCount, unreadMentionCount = unreadMentionCount,
        ocvPreset = ocvPreset, customOCVArrayString = customOCVArrayString,
    )

    private fun meshContact(
        type: ContactType,
        name: String,
        publicKey: Bytes = Bytes(ByteArray(ProtocolLimits.PUBLIC_KEY_SIZE)),
        outPathLength: UByte = 0u,
    ) = MeshContact(
        id = publicKey.uppercaseHexString(), publicKey = publicKey, type = type, flags = ContactFlags(0u),
        outPathLength = outPathLength, outPath = Bytes.EMPTY, advertisedName = name,
        lastAdvertisement = Instant.now(), latitude = 0.0, longitude = 0.0, lastModified = Instant.now(),
    )

    private fun frame(type: ContactType, name: String, publicKey: Bytes = Bytes(ByteArray(ProtocolLimits.PUBLIC_KEY_SIZE))) =
        ContactFrame(
            publicKey = publicKey, type = type, flags = 0u, outPathLength = 0u, outPath = Bytes.EMPTY, name = name,
            lastAdvertTimestamp = 0u, latitude = 0.0, longitude = 0.0, lastModified = 0u,
        )

    private fun case(name: String, body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): DynamicTest =
        contactsOriginal(suite, name, body = body)

    @TestFactory
    fun valueAndConversionCases(): List<DynamicTest> = listOf(
        case("ContactSyncResult initializes correctly") {
            val result = ContactSyncResult(testContactsReceived, testSyncTimestamp, isIncremental = true)
            assertEquals(testContactsReceived, result.contactsReceived)
            assertEquals(testSyncTimestamp, result.lastSyncTimestamp)
            assertEquals(true, result.isIncremental)
        },
        case("ContactSyncResult handles zero contacts") {
            val result = ContactSyncResult(0, 0u, isIncremental = false)
            assertEquals(0L, result.contactsReceived)
            assertEquals(0u, result.lastSyncTimestamp)
            assertEquals(false, result.isIncremental)
        },
        case("ContactSyncResult handles maximum values") {
            val result = ContactSyncResult(maxContactsReceived, maxSyncTimestamp, isIncremental = true)
            assertEquals(maxContactsReceived, result.contactsReceived)
            assertEquals(maxSyncTimestamp, result.lastSyncTimestamp)
            assertEquals(true, result.isIncremental)
        },
        case("ContactServiceError cases are distinct") {
            // Verify basic error cases
            val basicErrors: List<ContactServiceError> = listOf(
                ContactServiceError.NotConnected(), ContactServiceError.SendFailed(),
                ContactServiceError.InvalidResponse(), ContactServiceError.SyncInterrupted(),
                ContactServiceError.ContactNotFound(), ContactServiceError.ContactTableFull(),
            )
            // Verify all basic cases are distinct (no duplicates)
            val descriptions = basicErrors.map { it.toString() }
            assertEquals(descriptions.size, descriptions.toSet().size)
        },
        case("MeshContact converts to ContactFrame correctly") {
            val lastAdvertDate = Instant.ofEpochSecond(testTimestamp.toLong())
            val lastModifiedDate = Instant.ofEpochSecond(testModifiedTimestamp.toLong())
            val meshContact = MeshContact(
                id = testPublicKey.uppercaseHexString(), publicKey = testPublicKey, type = ContactType.CHAT,
                flags = ContactFlags(testFlags), outPathLength = 2u, outPath = testOutPath, advertisedName = "TestNode",
                lastAdvertisement = lastAdvertDate, latitude = 37.7749, longitude = -122.4194, lastModified = lastModifiedDate,
            )

            val contactFrame = meshContact.toContactFrame()

            assertEquals(testPublicKey, contactFrame.publicKey)
            assertEquals(ContactType.CHAT, contactFrame.type)
            assertEquals(testFlags, contactFrame.flags)
            assertEquals(2u.toUByte(), contactFrame.outPathLength)
            assertEquals(testOutPath, contactFrame.outPath)
            assertEquals("TestNode", contactFrame.name)
            assertEquals(lastAdvertDate.epochSecond.toUInt(), contactFrame.lastAdvertTimestamp)
            assertEquals(37.7749, contactFrame.latitude)
            assertEquals(-122.4194, contactFrame.longitude)
            assertEquals(lastModifiedDate.epochSecond.toUInt(), contactFrame.lastModified)
        },
        case("MeshContact handles all ContactType conversions") {
            assertEquals(ContactType.CHAT, meshContact(ContactType.CHAT, "Chat").toContactFrame().type)
            assertEquals(ContactType.REPEATER, meshContact(ContactType.REPEATER, "Repeater").toContactFrame().type)
            assertEquals(ContactType.ROOM, meshContact(ContactType.ROOM, "Room").toContactFrame().type)
        },
        case("Parser handles invalid ContactType by defaulting to .chat") {
            // Build 147-byte contact data with invalid type byte at offset 32
            val data = ByteArray(147)
            data[32] = invalidContactType.toByte()
            // Parser should default unknown types to .chat
            assertEquals(ContactType.CHAT, Parsers.parseContactData(Bytes(data))?.type)
        },
        case("MeshContact handles flood routing path") {
            val frame = meshContact(ContactType.CHAT, "Flood", outPathLength = 0xFFu).toContactFrame()
            assertEquals(0xFFu.toUByte(), frame.outPathLength)
            assertTrue(frame.outPath.isEmpty)
        },
        case("ContactFrame converts to MeshContact correctly") {
            val contactFrame = ContactFrame(
                publicKey = testPublicKey, type = ContactType.CHAT, flags = testFlags, outPathLength = 2u,
                outPath = testOutPath, name = "TestNode", lastAdvertTimestamp = testTimestamp,
                latitude = 37.7749, longitude = -122.4194, lastModified = testModifiedTimestamp,
            )

            val meshContact = contactFrame.toMeshContact()

            assertEquals(testPublicKey.uppercaseHexString(), meshContact.id)
            assertEquals(testPublicKey, meshContact.publicKey)
            assertEquals(ContactType.CHAT, meshContact.type)
            assertEquals(ContactFlags(testFlags), meshContact.flags)
            assertEquals(2u.toUByte(), meshContact.outPathLength)
            assertEquals(testOutPath, meshContact.outPath)
            assertEquals("TestNode", meshContact.advertisedName)
            assertEquals(Instant.ofEpochSecond(testTimestamp.toLong()), meshContact.lastAdvertisement)
            assertEquals(37.7749, meshContact.latitude)
            assertEquals(-122.4194, meshContact.longitude)
            assertEquals(Instant.ofEpochSecond(testModifiedTimestamp.toLong()), meshContact.lastModified)
        },
        case("ContactFrame ID generation from public key") {
            val publicKey = Bytes.of(
                0xAB, 0xCD, 0xEF, 0x12, 0x34, 0x56, 0x78, 0x90, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77, 0x88,
                0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF, 0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08,
            )
            val meshContact = frame(ContactType.CHAT, "Test", publicKey).toMeshContact()
            // ID should be hex string of public key (uppercase)
            assertEquals(publicKey.uppercaseHexString(), meshContact.id)
            assertEquals("ABCDEF1234567890112233445566778899AABBCCDDEEFF000102030405060708", meshContact.id)
        },
        case("ContactFrame handles all ContactType conversions") {
            assertEquals(ContactType.CHAT, frame(ContactType.CHAT, "Chat").toMeshContact().type)
            assertEquals(ContactType.REPEATER, frame(ContactType.REPEATER, "Repeater").toMeshContact().type)
            assertEquals(ContactType.ROOM, frame(ContactType.ROOM, "Room").toMeshContact().type)
        },
        case("Round-trip conversion MeshContact -> ContactFrame -> MeshContact") {
            val original = MeshContact(
                id = testPublicKey.uppercaseHexString(), publicKey = testPublicKey, type = ContactType.REPEATER,
                flags = ContactFlags(0x05u), outPathLength = 3u, outPath = Bytes.of(0xAA, 0xBB, 0xCC),
                advertisedName = "OriginalNode", lastAdvertisement = Instant.ofEpochSecond(testTimestamp.toLong()),
                latitude = 40.7128, longitude = -74.0060, lastModified = Instant.ofEpochSecond(1_700_000_200),
            )
            val roundTripped = original.toContactFrame().toMeshContact()
            assertEquals(original.id, roundTripped.id)
            assertEquals(original.publicKey, roundTripped.publicKey)
            assertEquals(original.type, roundTripped.type)
            assertEquals(original.flags, roundTripped.flags)
            assertEquals(original.outPathLength, roundTripped.outPathLength)
            assertEquals(original.outPath, roundTripped.outPath)
            assertEquals(original.advertisedName, roundTripped.advertisedName)
            assertEquals(original.lastAdvertisement, roundTripped.lastAdvertisement)
            assertEquals(original.latitude, roundTripped.latitude)
            assertEquals(original.longitude, roundTripped.longitude)
            assertEquals(original.lastModified, roundTripped.lastModified)
        },
        case("Round-trip conversion ContactFrame -> MeshContact -> ContactFrame") {
            val original = ContactFrame(
                publicKey = testPublicKey, type = ContactType.ROOM, flags = 0x03u, outPathLength = 1u,
                outPath = Bytes.of(0xFF), name = "OriginalRoom", lastAdvertTimestamp = testTimestamp,
                latitude = 51.5074, longitude = -0.1278, lastModified = 1_700_000_300u,
            )
            assertEquals(original, original.toMeshContact().toContactFrame())
        },
    )

    @TestFactory
    fun serviceCases(): List<DynamicTest> = listOf(
        case("removeContact deletes messages and triggers cleanup") {
            val radioId = RadioId(UUID.randomUUID())
            val contactId = UUID.randomUUID()
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            store.seed(contact(contactId, radioId, unreadCount = 3))
            val tracker = ContactsRecordingCleanup()
            val service = contactsService(store = store, cleanupCoordinator = tracker)
            // Seed a message so the delete cascade is observable
            store.seed(MessageDTO(radioId = radioId, contactID = contactId, text = "cascade", timestamp = 0u))

            service.removeContact(radioId, testPublicKey)

            // Verify the contact was deleted and its messages died with it
            assertEquals(listOf(contactId), store.deletedContactIDs)
            assertTrue(store.messages.isEmpty())
            // Verify cleanup handler was called with reason=.deleted
            assertEquals(1, tracker.invocations.size)
            assertEquals(contactId, tracker.invocations[0].contact.id)
            assertEquals(ContactCleanupReason.DELETED, tracker.invocations[0].reason)
        },
        case("clearContactMessages deletes messages and zeroes both unread counters while preserving lastMessageDate") {
            val radioId = RadioId(UUID.randomUUID())
            val contactId = UUID.randomUUID()
            val lastMessageDate = Instant.ofEpochSecond(testTimestamp.toLong())
            val store = ContactsFakeStore()
            store.seed(contact(contactId, radioId, unreadCount = 4, unreadMentionCount = 2, lastMessageDate = lastMessageDate))
            // Seed real message rows so deletion is observable, not just forwarded.
            for (offset in 0 until 3) {
                store.seed(
                    MessageDTO(
                        radioId = radioId, contactID = contactId, text = "msg $offset", timestamp = testTimestamp + offset.toUInt(),
                        createdAt = lastMessageDate, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED,
                        senderNodeName = "TestContact",
                    ),
                )
            }
            val service = contactsService(store = store)
            val key = EntityKey(radioId, contactId)

            service.clearContactMessages(key)

            // Messages are actually gone, not merely reported deleted.
            assertEquals(listOf(contactId), store.deletedMessagesForContactIDs)
            assertTrue(store.messagesFor(contactId).isEmpty())
            // The conversation stays listed: lastMessageDate is preserved, both unread counters zeroed.
            val updated = store.fetchContact(key)
            assertEquals(lastMessageDate, updated?.lastMessageDate)
            assertEquals(0L, updated?.unreadCount)
            assertEquals(0L, updated?.unreadMentionCount)
        },
        case("updateContactPreferences clears unread when blocking") {
            val (store, key) = seeded(unreadCount = 5)
            val tracker = ContactsRecordingCleanup()
            val service = contactsService(store = store, cleanupCoordinator = tracker)

            service.updateContactPreferences(key, isBlocked = true)

            val updated = store.contacts[key.id]
            assertEquals(0L, updated?.unreadCount)
            assertEquals(true, updated?.isBlocked)
            assertEquals(1, tracker.invocations.size)
            assertEquals(key.id, tracker.invocations[0].contact.id)
            assertEquals(ContactCleanupReason.BLOCKED, tracker.invocations[0].reason)
        },
        case("updateContactAvatar sets and clears avatarImageData") {
            val (store, key) = seeded()
            val service = contactsService(store = store)
            val imageData = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF)

            service.updateContactAvatar(key, imageData)
            assertEquals(imageData, store.contacts[key.id]?.avatarImageData)

            service.updateContactAvatar(key, null)
            assertNull(store.contacts[key.id]?.avatarImageData)
        },
        case("avatarImageData survives updateContactPreferences") {
            val (store, key) = seeded()
            val service = contactsService(store = store)
            val imageData = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF)
            service.updateContactAvatar(key, imageData)

            service.updateContactPreferences(key, nickname = "Field Ops")

            val updated = store.contacts[key.id]
            assertEquals("Field Ops", updated?.nickname)
            assertEquals(imageData, updated?.avatarImageData)
        },
        case("avatarImageData survives setContactFavorite") {
            val (store, key) = seeded()
            val service = contactsService(store = store)
            val imageData = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF)
            service.updateContactAvatar(key, imageData)

            service.setContactFavorite(key, isFavorite = true)

            val updated = store.contacts[key.id]
            assertEquals(true, updated?.isFavorite)
            assertEquals(imageData, updated?.avatarImageData)
        },
        case("updateContactPreferences does not trigger cleanup when not blocking") {
            val (store, key) = seeded(unreadCount = 5)
            val tracker = ContactsRecordingCleanup()
            val service = contactsService(store = store, cleanupCoordinator = tracker)

            service.updateContactPreferences(key, nickname = "NewNickname")

            val updated = store.contacts[key.id]
            assertEquals(5L, updated?.unreadCount)
            assertEquals("NewNickname", updated?.nickname)
            assertTrue(tracker.invocations.isEmpty())
        },
        case("updateContactPreferences preserves fields when blocking") {
            val (store, key) = seeded(
                unreadCount = 5, nickname = "MyNickname", isFavorite = true, lastMessageDate = Instant.now(),
                ocvPreset = "medium", customOCVArrayString = "custom",
            )
            val service = contactsService(store = store)

            service.updateContactPreferences(key, isBlocked = true)

            val updated = store.contacts[key.id]
            assertEquals("MyNickname", updated?.nickname)
            assertEquals(true, updated?.isFavorite)
            assertEquals("medium", updated?.ocvPreset)
            assertEquals("custom", updated?.customOCVArrayString)
            assertEquals(0L, updated?.unreadCount)
            assertEquals(true, updated?.isBlocked)
        },
        case("unblocking contact triggers cleanup with unblocked reason") {
            val (store, key) = seeded(isBlocked = true)
            val tracker = ContactsRecordingCleanup()
            val service = contactsService(store = store, cleanupCoordinator = tracker)

            service.updateContactPreferences(key, isBlocked = false)

            assertEquals(false, store.contacts[key.id]?.isBlocked)
            assertEquals(1, tracker.invocations.size)
            assertEquals(key.id, tracker.invocations[0].contact.id)
            assertEquals(ContactCleanupReason.UNBLOCKED, tracker.invocations[0].reason)
        },
        case("resetPath flood-routes the contact while preserving an unmodeled type byte") {
            val radioId = RadioId(UUID.randomUUID())
            // The fake store round-trips the raw type byte like the real store does.
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            val unmodeledType: UByte = 0x7Fu
            store.seed(
                ContactDTO(
                    radioId = radioId, publicKey = testPublicKey, name = "Test", typeRawValue = unmodeledType,
                    outPathLength = 2u, outPath = testOutPath, lastHeardTimestamp = null,
                ),
            )
            val session = ContactsFakeSession()
            val service = contactsService(session = session, store = store)

            service.resetPath(radioId, testPublicKey)

            assertEquals(listOf(testPublicKey), session.resetPathPublicKeys)
            val reset = store.fetchContact(radioId, testPublicKey)
            assertEquals(true, reset?.isFloodRouted)
            assertEquals(unmodeledType, reset?.typeRawValue)
        },
        case("updateContactPreferences clears nickname when passed empty string") {
            val (store, key) = seeded(nickname = "OldNickname")
            contactsService(store = store).updateContactPreferences(key, nickname = "")
            assertNull(store.contacts[key.id]?.nickname)
        },
        case("updateContactPreferences clears nickname when passed whitespace only") {
            val (store, key) = seeded(nickname = "OldNickname")
            contactsService(store = store).updateContactPreferences(key, nickname = "   ")
            assertNull(store.contacts[key.id]?.nickname)
        },
        case("updateContactPreferences trims surrounding whitespace from nickname") {
            val (store, key) = seeded()
            contactsService(store = store).updateContactPreferences(key, nickname = "  Rico  ")
            assertEquals("Rico", store.contacts[key.id]?.nickname)
        },
        case("updateContactPreferences keeps nickname when nickname arg is nil") {
            val (store, key) = seeded(nickname = "KeepMe", isMuted = true, unreadMentionCount = 5)

            // Toggle favorite without touching nickname; nickname must survive.
            contactsService(store = store).updateContactPreferences(key, isFavorite = true)

            val updated = store.contacts[key.id]
            assertEquals("KeepMe", updated?.nickname)
            assertEquals(true, updated?.isFavorite)
            // A preferences edit must not silently reset unrelated persisted fields.
            assertEquals(true, updated?.isMuted)
            assertEquals(5L, updated?.unreadMentionCount)
        },
    )

    private fun seeded(
        unreadCount: Long = 0,
        unreadMentionCount: Long = 0,
        nickname: String? = null,
        isBlocked: Boolean = false,
        isMuted: Boolean = false,
        isFavorite: Boolean = false,
        lastMessageDate: Instant? = null,
        ocvPreset: String? = null,
        customOCVArrayString: String? = null,
    ): Pair<ContactsFakeStore, EntityKey> {
        val radioId = RadioId(UUID.randomUUID())
        val contactId = UUID.randomUUID()
        val store = ContactsFakeStore()
        store.seed(
            contact(
                contactId, radioId, unreadCount, unreadMentionCount, nickname, isBlocked, isMuted, isFavorite,
                lastMessageDate, ocvPreset, customOCVArrayString,
            ),
        )
        return store to EntityKey(radioId, contactId)
    }
}
