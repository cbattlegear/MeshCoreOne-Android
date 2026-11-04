// AndroidOnly: WP-209 Real Room controls for identity-checked, existing-row-only avatar persistence.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.ContactAvatarUpdateResult
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContactAvatarRoomTest : RepositoryTest() {
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
        val replacement = contact(publicKey = key(0x55), name = "Replacement").withAvatar(Bytes.of(3))
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
