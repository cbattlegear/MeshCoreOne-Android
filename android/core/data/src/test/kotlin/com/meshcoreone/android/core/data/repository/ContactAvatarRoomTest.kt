// AndroidOnly: WP-209 Real Room controls for identity-checked, existing-row-only avatar persistence.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.ContactAvatarUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactFavoriteUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactBlockTransition
import com.meshcoreone.android.core.contracts.domain.ContactLocalUpdate
import com.meshcoreone.android.core.contracts.domain.ContactLocalUpdateResult
import com.meshcoreone.android.core.contracts.domain.ContactPersisting
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.MeshContact
import com.meshcoreone.android.core.protocol.session.ContactFetchResult
import com.meshcoreone.android.core.protocol.session.ContactSessionOps
import com.meshcoreone.android.core.services.contacts.ContactPreferenceFlags
import com.meshcoreone.android.core.services.contacts.ContactService
import com.meshcoreone.android.core.services.contacts.ContactServiceError
import com.meshcoreone.android.core.services.contacts.ContactCleanupHandling
import com.meshcoreone.android.core.services.contacts.ContactCleanupReason
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContactAvatarRoomTest : RepositoryTest() {
    private class HeldFavoriteSession : ContactSessionOps {
        val started = CompletableDeferred<ContactFlags>()
        val acknowledged = CompletableDeferred<Unit>()
        override suspend fun changeContactFlags(contact: MeshContact, flags: ContactFlags) {
            started.complete(flags)
            acknowledged.await()
        }
        override suspend fun getContactsReportingTotal(since: Instant?): ContactFetchResult = unexpected()
        override suspend fun getContacts(since: Instant?): List<MeshContact> = unexpected()
        override suspend fun getContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun addContact(contact: MeshContact): Nothing = unexpected()
        override suspend fun removeContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun resetPath(publicKey: Bytes): Nothing = unexpected()
        override suspend fun sendPathDiscovery(destination: Bytes): Nothing = unexpected()
        override suspend fun shareContact(publicKey: Bytes): Nothing = unexpected()
        override suspend fun exportContact(publicKey: Bytes?): Nothing = unexpected()
        override suspend fun importContact(cardData: Bytes): Nothing = unexpected()
        private fun unexpected(): Nothing = error("Favorite update must only change contact flags")
    }

    private fun favoriteService(session: HeldFavoriteSession): ContactService = ContactService(
        session, store, store, null, null,
        object : ContactPreferenceFlags {
            override fun bool(key: String): Nothing = error("Favorite update must not read preferences")
            override fun set(key: String, value: Boolean): Nothing = error("Favorite update must not write preferences")
        },
        clock,
    )

    private fun localService(
        contacts: ContactPersisting = store,
        session: HeldFavoriteSession = HeldFavoriteSession().apply { acknowledged.complete(Unit) },
        cleanup: ContactCleanupHandling? = null,
    ): ContactService = ContactService(
        session, store, contacts, null, cleanup,
        object : ContactPreferenceFlags {
            override fun bool(key: String): Nothing = error("Local update must not read preferences")
            override fun set(key: String, value: Boolean): Nothing = error("Local update must not write preferences")
        },
        clock,
    )

    private enum class LocalEdit { PREFERENCES, OCV, TELEMETRY_GRANT, TELEMETRY_REVOKE }

    private suspend fun applyEdit(service: ContactService, edit: LocalEdit) {
        when (edit) {
            LocalEdit.PREFERENCES -> service.updateContactPreferences(entity(), nickname = "  Local name  ", isBlocked = true)
            LocalEdit.OCV -> service.updateContactOCVSettings(entity(), "custom", "3.2,3.8")
            LocalEdit.TELEMETRY_GRANT -> service.setTelemetryPermissions(entity(), true)
            LocalEdit.TELEMETRY_REVOKE -> service.setTelemetryPermissions(entity(), false)
        }
    }

    private fun afterEdit(row: ContactDTO, edit: LocalEdit): ContactDTO = when (edit) {
        LocalEdit.PREFERENCES -> row.copy(
            nickname = "Local name", isBlocked = true, unreadCount = if (row.isBlocked) row.unreadCount else 0,
        )
        LocalEdit.OCV -> row.copy(ocvPreset = "custom", customOCVArrayString = "3.2,3.8")
        LocalEdit.TELEMETRY_GRANT -> row.copy(flags = (row.flags.toInt() or 0x0E).toUByte())
        LocalEdit.TELEMETRY_REVOKE -> row.copy(flags = (row.flags.toInt() and 0xF1).toUByte())
    }

    @Test fun avatarCommittedAfterLocalFetchSurvivesEveryLocalFieldWrite() = runTest {
        for (edit in LocalEdit.entries) {
            for (image in listOf(Bytes.of(2, 3), null)) {
                val original = contact().copy(flags = 0x8Eu, nickname = "Old name", unreadCount = 7)
                    .withAvatar(Bytes.of(1))
                store.saveContact(original)
                store.saveContact(original.copy(radioId = RADIO_B))
                val fetched = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val held = object : ContactPersisting by store {
                    override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                        val stale = store.fetchContact(key)
                        fetched.complete(Unit)
                        release.await()
                        return stale
                    }
                    override suspend fun saveContact(dto: ContactDTO): Unit = error("Local update must not save a stale DTO")
                }
                val pending = async { applyEdit(localService(held), edit) }
                fetched.await()
                val writer = newStore()
                try {
                    writer.incrementUnreadCount(entity())
                    writer.updateContactFavorite(entity(), original.publicKey, true)
                    writer.updateContactAvatar(entity(), original.publicKey, image)
                    val before = assertNotNull(writer.fetchContact(entity()))
                    release.complete(Unit)
                    pending.await()
                    assertEquals(afterEdit(before, edit), store.fetchContact(entity()), "$edit / $image")
                    assertEquals(original.copy(radioId = RADIO_B), store.fetchContact(entity(RADIO_B)))
                } finally {
                    pending.cancelAndJoin()
                    writer.close()
                }
            }
        }
    }

    @Test fun localFieldsCommittedWhileAvatarWriteIsHeldSurviveSetAndClear() = runTest {
        for (edit in LocalEdit.entries) {
            for (image in listOf(Bytes.of(2, 3), null)) {
                val original = contact().copy(flags = 0x8Eu, unreadCount = 7).withAvatar(Bytes.of(1))
                store.saveContact(original)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val held = object : ContactPersisting by store {
                    override suspend fun updateContactAvatar(
                        key: EntityKey, expectedPublicKey: Bytes, imageData: Bytes?,
                    ): ContactAvatarUpdateResult {
                        started.complete(Unit)
                        release.await()
                        return store.updateContactAvatar(key, expectedPublicKey, imageData)
                    }
                }
                val pending = async { localService(held).updateContactAvatar(entity(), original.publicKey, image) }
                started.await()
                applyEdit(localService(), edit)
                val committed = assertNotNull(store.fetchContact(entity()))
                assertEquals(afterEdit(original, edit), committed)
                release.complete(Unit)
                pending.await()
                assertEquals(committed.withAvatar(image), store.fetchContact(entity()))
            }
        }
    }

    @Test fun telemetryAckCannotOverwriteConcurrentAvatarOrFavoriteAndPreservesWireFlags() = runTest {
        for (granted in listOf(true, false)) {
            for (image in listOf(Bytes.of(2), null)) {
                val original = contact().copy(flags = if (granted) 0x80u else 0x8Eu).withAvatar(Bytes.of(1))
                store.saveContact(original)
                val session = HeldFavoriteSession()
                val pending = async { localService(session = session).setTelemetryPermissions(entity(), granted) }
                assertEquals(ContactFlags(if (granted) 0x8Eu else 0x80u), session.started.await())
                store.updateContactFavorite(entity(), original.publicKey, true)
                store.updateContactAvatar(entity(), original.publicKey, image)
                val before = assertNotNull(store.fetchContact(entity()))
                session.acknowledged.complete(Unit)
                pending.await()
                assertEquals(afterEdit(before, if (granted) LocalEdit.TELEMETRY_GRANT else LocalEdit.TELEMETRY_REVOKE),
                    store.fetchContact(entity()))
            }
        }
    }

    @Test fun missingOrReplacedLocalIdentityNeverMutatesOrRunsCleanup() = runTest {
        for (edit in LocalEdit.entries) {
            for (replacement in listOf<ContactDTO?>(null, contact(publicKey = key(0x55)).withAvatar(Bytes.of(9)))) {
                store.saveContact(contact())
                val fetched = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                val held = object : ContactPersisting by store {
                    override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                        val stale = store.fetchContact(key)
                        fetched.complete(Unit)
                        release.await()
                        return stale
                    }
                }
                val cleanup = object : ContactCleanupHandling {
                    override suspend fun handleCleanup(contact: EntityKey, reason: ContactCleanupReason, publicKey: Bytes) =
                        error("Rejected local update must not run cleanup")
                }
                val pending = async {
                    try {
                        applyEdit(localService(held, cleanup = cleanup), edit)
                        null
                    } catch (failure: ContactServiceError.ContactNotFound) {
                        failure
                    }
                }
                fetched.await()
                store.deleteContact(entity())
                replacement?.let { store.saveContact(it) }
                val before = db.contacts().backupAll()
                release.complete(Unit)
                assertNotNull(pending.await())
                assertEquals(before, db.contacts().backupAll())
            }
        }
    }

    @Test fun telemetryDeletionReplacementAndCancellationDuringAckNeverWrite() = runTest {
        for (replacement in listOf<ContactDTO?>(null, contact(publicKey = key(0x55)).withAvatar(Bytes.of(9)))) {
            store.saveContact(contact())
            val session = HeldFavoriteSession()
            val pending = async {
                try {
                    localService(session = session).setTelemetryPermissions(entity(), true)
                    null
                } catch (failure: ContactServiceError.ContactNotFound) {
                    failure
                }
            }
            session.started.await()
            store.deleteContact(entity())
            replacement?.let { store.saveContact(it) }
            val before = db.contacts().backupAll()
            session.acknowledged.complete(Unit)
            assertNotNull(pending.await())
            assertEquals(before, db.contacts().backupAll())
        }
        store.saveContact(contact())
        val session = HeldFavoriteSession()
        val pending = async { localService(session = session).setTelemetryPermissions(entity(), true) }
        session.started.await()
        store.updateContactAvatar(entity(), key(), Bytes.of(3))
        val before = db.contacts().backupAll()
        pending.cancelAndJoin()
        assertEquals(before, db.contacts().backupAll())
    }

    @Test fun preferenceBlockTransitionUsesCurrentRowAndNullClearSemantics() = runTest {
        val original = contact().copy(nickname = "Keep", unreadCount = 5, unreadMentionCount = 2)
        store.saveContact(original)
        val seen = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val held = object : ContactPersisting by store {
            override suspend fun fetchContact(key: EntityKey): ContactDTO? {
                val stale = store.fetchContact(key)
                seen.complete(Unit)
                release.await()
                return stale
            }
        }
        val reasons = mutableListOf<ContactCleanupReason>()
        val cleanup = object : ContactCleanupHandling {
            override suspend fun handleCleanup(contact: EntityKey, reason: ContactCleanupReason, publicKey: Bytes) {
                assertEquals(entity(), contact)
                assertEquals(original.publicKey, publicKey)
                reasons += reason
            }
        }
        val pending = async { localService(held, cleanup = cleanup).updateContactPreferences(entity(), isBlocked = false) }
        seen.await()
        assertEquals(ContactLocalUpdateResult.Updated(ContactBlockTransition.BLOCKED),
            store.updateContactLocalFields(entity(), original.publicKey, ContactLocalUpdate.Preferences(null, true, null)))
        store.incrementUnreadCount(entity())
        release.complete(Unit)
        pending.await()
        assertEquals(listOf(ContactCleanupReason.UNBLOCKED), reasons)
        assertEquals(original.copy(unreadCount = 1), store.fetchContact(entity()))
        localService(cleanup = cleanup).updateContactPreferences(entity(), nickname = " \t ", isBlocked = true)
        assertEquals(listOf(ContactCleanupReason.UNBLOCKED, ContactCleanupReason.BLOCKED), reasons)
        assertEquals(original.copy(nickname = null, isBlocked = true, unreadCount = 0), store.fetchContact(entity()))
        store.incrementUnreadCount(entity())
        localService(cleanup = cleanup).updateContactPreferences(entity(), isBlocked = true, isFavorite = true)
        assertEquals(2, reasons.size)
        assertEquals(original.copy(nickname = null, isBlocked = true, unreadCount = 1, isFavorite = true),
            store.fetchContact(entity()))
    }

    @Test fun localStoreOutcomesCheckFullKeyAndRadioWithoutChangingAnyRows() = runTest {
        val updates = listOf(
            ContactLocalUpdate.Preferences("", true, false), ContactLocalUpdate.OCVSettings("custom", null),
            ContactLocalUpdate.TelemetryPermissions(true), ContactLocalUpdate.TelemetryPermissions(false),
        )
        val foreign = contact(RADIO_B).withAvatar(Bytes.of(1))
        store.saveContact(foreign)
        for (update in updates) {
            assertEquals(ContactLocalUpdateResult.ContactNotFound,
                store.updateContactLocalFields(entity(), foreign.publicKey, update))
        }
        store.saveContact(contact())
        val before = db.contacts().backupAll()
        val wrongFullKey = Bytes(ByteArray(32) { if (it == 31) 0x55.toByte() else 0x44.toByte() })
        for (update in updates) {
            assertEquals(ContactLocalUpdateResult.IdentityMismatch,
                store.updateContactLocalFields(entity(), wrongFullKey, update))
            assertEquals(before, db.contacts().backupAll())
        }
        store.updateContactLocalFields(entity(), key(), ContactLocalUpdate.OCVSettings("custom", null))
        assertEquals(foreign, store.fetchContact(entity(RADIO_B)))
    }

    @Test fun avatarSetAndClearCommittedWhileFavoriteAckIsHeldSurviveTheFavoriteCommit() = runTest {
        for (favorite in listOf(true, false)) {
            for (image in listOf(Bytes.of(2, 3), null)) {
                val original = contact().copy(flags = if (favorite) 0x80u else 0x81u, isFavorite = !favorite)
                    .withAvatar(Bytes.of(1))
                store.saveContact(original)
                store.saveContact(original.copy(radioId = RADIO_B))
                val foreign = assertNotNull(store.fetchContact(entity(RADIO_B)))
                val session = HeldFavoriteSession()
                val service = favoriteService(session)
                val pending = async { service.setContactFavorite(entity(), favorite) }
                assertEquals(ContactFlags(if (favorite) 0x81u else 0x80u), session.started.await())
                val writer = newStore()
                try {
                    writer.incrementUnreadCount(entity())
                    writer.saveContact(assertNotNull(writer.fetchContact(entity())).copy(
                        nickname = "Changed while waiting", flags = 0x8Eu,
                    ))
                    service.updateContactAvatar(entity(), original.publicKey, image)
                    val beforeAck = assertNotNull(store.fetchContact(entity()))
                    session.acknowledged.complete(Unit)
                    pending.await()
                    assertEquals(beforeAck.copy(flags = if (favorite) 0x8Fu else 0x8Eu, isFavorite = favorite),
                        store.fetchContact(entity()))
                    assertEquals(foreign, store.fetchContact(entity(RADIO_B)))
                } finally {
                    pending.cancelAndJoin()
                    writer.close()
                }
            }
        }
    }

    @Test fun deletedOrReplacedContactDuringFavoriteAckIsNotResurrectedOrMutated() = runTest {
        for (replacement in listOf<ContactDTO?>(null, contact(publicKey = key(0x55)).withAvatar(Bytes.of(9)))) {
            store.saveContact(contact())
            val session = HeldFavoriteSession()
            val service = favoriteService(session)
            val pending = async {
                try {
                    service.setContactFavorite(entity(), true)
                    null
                } catch (failure: ContactServiceError.ContactNotFound) {
                    failure
                }
            }
            session.started.await()
            store.deleteContact(entity())
            replacement?.let { store.saveContact(it) }
            val beforeAck = db.contacts().backupAll()
            session.acknowledged.complete(Unit)
            assertNotNull(pending.await())
            assertEquals(beforeAck, db.contacts().backupAll())
        }
    }

    @Test fun cancelledFavoriteAckLeavesTheConcurrentAvatarCommitUntouched() = runTest {
        store.saveContact(contact())
        val session = HeldFavoriteSession()
        val service = favoriteService(session)
        val pending = async { service.setContactFavorite(entity(), true) }
        session.started.await()
        service.updateContactAvatar(entity(), key(), Bytes.of(3))
        val committed = assertNotNull(store.fetchContact(entity()))
        pending.cancelAndJoin()
        assertEquals(committed, store.fetchContact(entity()))
    }

    @Test fun favoriteStoreOutcomeChecksFullIdentityAndNeverTouchesAnotherRadio() = runTest {
        val foreign = contact(RADIO_B).withAvatar(Bytes.of(1))
        store.saveContact(foreign)
        assertEquals(ContactFavoriteUpdateResult.CONTACT_NOT_FOUND,
            store.updateContactFavorite(entity(), foreign.publicKey, true))
        store.saveContact(contact())
        val before = db.contacts().backupAll()
        assertEquals(ContactFavoriteUpdateResult.IDENTITY_MISMATCH,
            store.updateContactFavorite(entity(), key(0x55), true))
        assertEquals(before, db.contacts().backupAll())
        assertEquals(ContactFavoriteUpdateResult.UPDATED, store.updateContactFavorite(entity(), key(), true))
        assertEquals(foreign, store.fetchContact(entity(RADIO_B)))
    }

    @Test fun avatarSetAndClearPreserveMetadataCommittedAfterTheCallerFetched() = runTest {
        val original = contact()
        store.saveContact(original)
        val stale = assertNotNull(store.fetchContact(entity()))
        // A separate repository commits after the caller's fetch, before its avatar write.
        val writer = newStore()
        try {
            val current = original.copy(
                name = "New advert", nickname = "Local name", flags = 5u, outPathLength = 1u,
                outPath = Bytes.of(0xAB), lastHeardTimestamp = 42u, isFavorite = true,
                isBlocked = true, isMuted = true, unreadCount = 7, unreadMentionCount = 3,
                lastMessageDate = AT, ocvPreset = "custom", customOCVArrayString = "3.2,3.8",
            )
            writer.saveContact(current)
            val committed = assertNotNull(writer.fetchContact(entity()))
            val image = Bytes.of(0xDE, 0xAD)
            assertEquals(ContactAvatarUpdateResult.UPDATED, store.updateContactAvatar(entity(), stale.publicKey, image))
            assertEquals(committed.withAvatar(image), store.fetchContact(entity()))
            writer.incrementUnreadCount(entity())
            val beforeClear = assertNotNull(writer.fetchContact(entity()))
            assertEquals(ContactAvatarUpdateResult.UPDATED, store.updateContactAvatar(entity(), stale.publicKey, null))
            assertEquals(beforeClear.withAvatar(null), store.fetchContact(entity()))
        } finally {
            writer.close()
        }
    }

    @Test fun deletionAfterCallerFetchNeverResurrectsTheContactForSetOrClear() = runTest {
        val original = contact().withAvatar(Bytes.of(1))
        store.saveContact(original)
        val stale = assertNotNull(store.fetchContact(entity()))
        store.deleteContact(entity())
        for (image in listOf(Bytes.of(2), null)) {
            assertEquals(ContactAvatarUpdateResult.CONTACT_NOT_FOUND,
                store.updateContactAvatar(entity(), stale.publicKey, image))
            assertNull(store.fetchContact(entity()))
            assertEquals(emptyList(), db.contacts().backupAll())
        }
    }

    @Test fun differentPublicKeyAtTheSameScopedIdIsNeverMutatedForSetOrClear() = runTest {
        store.saveContact(contact())
        val stale = assertNotNull(store.fetchContact(entity()))
        store.deleteContact(entity())
        val differentFullKey = Bytes(ByteArray(32) { if (it == 31) 0x55.toByte() else 0x44.toByte() })
        val replacement = contact(publicKey = differentFullKey, name = "Replacement").withAvatar(Bytes.of(3))
        store.saveContact(replacement)
        val before = db.contacts().backupAll()
        for (image in listOf(Bytes.of(2), null)) {
            assertEquals(ContactAvatarUpdateResult.IDENTITY_MISMATCH,
                store.updateContactAvatar(entity(), stale.publicKey, image))
            assertEquals(before, db.contacts().backupAll())
        }
    }

    @Test fun identicalIdAndPublicKeyInAnotherRadioAreNotAnAvatarTarget() = runTest {
        val foreign = contact(RADIO_B).withAvatar(Bytes.of(3))
        store.saveContact(foreign)
        val before = db.contacts().backupAll()
        for (image in listOf(Bytes.of(2), null)) {
            assertEquals(ContactAvatarUpdateResult.CONTACT_NOT_FOUND,
                store.updateContactAvatar(entity(), foreign.publicKey, image))
            assertEquals(before, db.contacts().backupAll())
        }
        store.saveContact(contact())
        for (image in listOf(Bytes.of(2), null)) {
            assertEquals(ContactAvatarUpdateResult.UPDATED, store.updateContactAvatar(entity(), foreign.publicKey, image))
            assertEquals(image, assertNotNull(store.fetchContact(entity())).avatarImageData)
            assertEquals(foreign, store.fetchContact(entity(RADIO_B)))
        }
    }
}
