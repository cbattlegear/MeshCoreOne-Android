// AndroidOnly: WP-209 Drives ContactService over a real MeshCoreSession and the protocol MockTransport wire.
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.session.MeshCoreSession
import com.meshcoreone.android.core.protocol.transport.MeshTransport
import com.meshcoreone.android.core.protocol.transport.mock.MockTransport
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ContactServiceSessionTests {
    private val radioId = RadioId(UUID.randomUUID())

    @TestFactory
    fun realSessionCases(): List<DynamicTest> = listOf(
        contactsNative("syncContacts over a real session persists the streamed contacts and prunes against contactsStart") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            store.seed(com.meshcoreone.android.core.model.ContactDTO(radioId = radioId, publicKey = contactsKey(0xDD), name = "Gone", lastHeardTimestamp = null))
            withRadio({ frame ->
                if (frame[0].toInt() == GET_CONTACTS) {
                    listOf(
                        raw(0x02, le32(2)),
                        contactPacket(contactsKey(0x11), "Alice", lastModified = 1_700_000_050),
                        contactPacket(contactsKey(0x22), "Bob", lastModified = 1_700_000_010),
                        raw(0x04, le32(1_700_000_050)),
                    )
                } else {
                    emptyList()
                }
            }) { session ->
                val result = contactsService(session = session, store = store).syncContacts(radioId)

                assertEquals(2L, result.contactsReceived)
                assertEquals(1_700_000_050u, result.lastSyncTimestamp)
                assertEquals(setOf("Alice", "Bob"), store.fetchContacts(radioId).map { it.name }.toSet())
                assertNull(store.fetchContact(radioId, contactsKey(0xDD)))
            }
        },
        contactsNative("a firmware TABLE_FULL reply to addContact surfaces contactTableFull and nothing is persisted") {
            val store = ContactsFakeStore()
            withRadio({ frame -> if (frame[0].toInt() == UPDATE_CONTACT) listOf(Bytes.of(RESPONSE_ERROR, 3)) else emptyList() }) { session ->
                val frame = com.meshcoreone.android.core.model.ContactFrame(
                    publicKey = contactsKey(0x31), type = com.meshcoreone.android.core.protocol.model.ContactType.CHAT,
                    flags = 0u, outPathLength = 0xFFu, outPath = Bytes.EMPTY, name = "Full", lastAdvertTimestamp = 0u,
                    latitude = 0.0, longitude = 0.0, lastModified = 0u,
                )
                assertFailsWith<ContactServiceError.ContactTableFull> {
                    contactsService(session = session, store = store).addOrUpdateContact(radioId, frame)
                }
                assertNull(store.fetchContact(radioId, contactsKey(0x31)))
            }
        },
        contactsNative("a firmware NOT_FOUND reply to removeContact surfaces contactNotFound and keeps the local row") {
            val store = ContactsFakeStore(listOf(contactsDevice(radioId)))
            store.seed(com.meshcoreone.android.core.model.ContactDTO(radioId = radioId, publicKey = contactsKey(0x41), name = "Kept", lastHeardTimestamp = null))
            withRadio({ frame -> if (frame[0].toInt() == REMOVE_CONTACT) listOf(Bytes.of(RESPONSE_ERROR, 2)) else emptyList() }) { session ->
                assertFailsWith<ContactServiceError.ContactNotFound> {
                    contactsService(session = session, store = store).removeContact(radioId, contactsKey(0x41))
                }
                assertNotNull(store.fetchContact(radioId, contactsKey(0x41)))
            }
        },
    )

    /** Starts a real session whose radio answers APP_START with a self-info frame and [respond] otherwise. */
    private suspend fun withRadio(respond: (Bytes) -> List<Bytes>, body: suspend (MeshCoreSession) -> Unit) {
        val transport = RespondingTransport { frame -> if (frame[0].toInt() == APP_START) listOf(selfPacket()) else respond(frame) }
        val job = Job()
        val session = MeshCoreSession(transport, coroutineContext = Dispatchers.Default + job, onDiagnostic = {})
        try {
            session.start()
            body(session)
        } finally {
            session.stop()
            job.cancel()
        }
    }

    private class RespondingTransport(private val respond: (Bytes) -> List<Bytes>) : MeshTransport {
        private val mock = MockTransport()
        override suspend fun connect() = mock.connect()
        override suspend fun disconnect() = mock.disconnect()
        override suspend fun send(data: Bytes) {
            mock.send(data)
            respond(data).forEach { mock.simulateReceive(it) }
        }
        override suspend fun receivedData(): Flow<Bytes> = mock.receivedData()
        override suspend fun isConnected(): Boolean = mock.isConnected()
    }

    private fun le32(value: Long): Bytes =
        Bytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value.toInt()).array())

    private fun raw(code: Int, payload: Bytes = Bytes.EMPTY): Bytes = Bytes.of(code) + payload

    private fun padded(bytes: Bytes, size: Int): Bytes = bytes.paddedOrTruncated(size)

    private fun contactPacket(key: Bytes, name: String, lastModified: Long): Bytes = raw(
        0x03,
        key + Bytes.of(1, 0, 0xFF) + padded(Bytes.EMPTY, 64) + padded(Bytes.utf8(name), 32) +
            le32(0) + le32(0) + le32(0) + le32(lastModified),
    )

    private fun selfPacket(): Bytes = raw(
        0x05,
        Bytes.of(1, 22, 22) + contactsKey(0x01) + le32(0) + le32(0) + Bytes.of(0, 0, 0, 0) +
            le32(915_000) + le32(125_000) + Bytes.of(7, 5) + Bytes.utf8("Test"),
    )

    private companion object {
        const val APP_START = 0x01
        const val GET_CONTACTS = 0x04
        const val UPDATE_CONTACT = 0x09
        const val REMOVE_CONTACT = 0x0F
        const val RESPONSE_ERROR = 0x01
    }
}
