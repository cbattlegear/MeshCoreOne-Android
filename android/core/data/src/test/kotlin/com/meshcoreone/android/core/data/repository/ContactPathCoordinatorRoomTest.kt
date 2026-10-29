// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ContactServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import com.meshcoreone.android.core.services.contacts.ContactPreferenceFlags
import com.meshcoreone.android.core.services.contacts.ContactService
import java.time.Instant
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContactPathCoordinatorRoomTest : RepositoryTest() {
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
