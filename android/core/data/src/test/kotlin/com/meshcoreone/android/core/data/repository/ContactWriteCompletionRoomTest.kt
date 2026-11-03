// AndroidOnly: WP-209 Atomic accepted-contact upserts preserve newer frames and unrelated local metadata.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ContactWriteCompletionRoomTest : RepositoryTest() {
    @Test
    fun acceptedFrameUpsertIsRadioScopedAndPreservesLocalMetadata() = runTest {
        val incoming = frame(name = "Accepted", flags = 0u).copy(lastModified = 2u)
        val original = ContactDTO.fromFrame(RADIO_A, frame(flags = 1u)).copy(
            nickname = "My nickname", unreadCount = 9, isMuted = true, isFavorite = true,
            avatarImageData = Bytes.of(1, 2, 3),
        )
        val other = original.copy(radioId = RADIO_B, name = "Other radio")
        store.saveContact(original)
        store.saveContact(other)
        val result = store.saveContactPreservingNewer(RADIO_A, incoming, original.toContactFrame())
        assertEquals(original.id, result.id)
        assertFalse(result.isNew)
        assertEquals(original.updating(incoming), store.fetchContact(RADIO_A, incoming.publicKey))
        assertEquals(other, store.fetchContact(RADIO_B, incoming.publicKey))
    }

    @Test
    fun newerAndEqualTimestampChangedFramesCannotBeOverwrittenByAnOldCompletion() = runTest {
        for (modified in listOf(1u, 2u)) {
            val initial = frame(byte = modified.toInt(), name = "Initial").copy(lastModified = 1u)
            val first = store.saveContact(RADIO_A, initial)
            val newer = assertNotNull(store.fetchContact(entity(id = first.id))).copy(
                name = "Newer", lastModified = modified, nickname = "Retained", unreadCount = 7,
            )
            store.saveContact(newer)
            val result = store.saveContactPreservingNewer(RADIO_A, initial.copy(name = "Late"), initial)
            assertEquals(first.id, result.id)
            assertEquals(newer, store.fetchContact(RADIO_A, initial.publicKey))
        }
    }

    @Test
    fun intentionalEqualTimestampChangeAndInsertionStillPersist() = runTest {
        val initial = frame(name = "Initial")
        assertTrue(store.saveContactPreservingNewer(RADIO_A, initial).isNew)
        val updated = initial.copy(name = "Intentional")
        assertFalse(store.saveContactPreservingNewer(RADIO_A, updated, initial).isNew)
        assertEquals("Intentional", store.fetchContact(RADIO_A, initial.publicKey)?.name)
        assertTrue(issues.isEmpty())
    }
}
