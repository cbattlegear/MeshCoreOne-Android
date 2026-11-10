// PortedFrom: MC1Services/Tests/MC1ServicesTests/DraftStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.rendering

import com.meshcoreone.android.core.model.ChatConversationID
import com.meshcoreone.android.core.model.RadioId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** In-memory `UserDefaults` suite: a fresh instance per test, shared between store instances for "restart". */
private class InMemoryDraftDefaults : DraftDefaults {
    private val lock = Any()
    private var values: Map<String, Map<String, String>> = emptyMap()
    var writes: Int = 0
        private set

    override fun stringDictionary(key: String): Map<String, String>? = synchronized(lock) { values[key] }

    override fun setStringDictionary(value: Map<String, String>, key: String) {
        synchronized(lock) {
            values = values + (key to value.toMap())
            writes += 1
        }
    }
}

class DraftStoreTest {
    private val radioId = RadioId(UUID.randomUUID())
    private val contactID = UUID.randomUUID()

    private fun dm(radio: RadioId, contact: UUID) = ChatConversationID.dm(radio, contact)

    private fun channel(radio: RadioId, index: UByte) = ChatConversationID.channel(radio, index)

    @TestFactory
    fun draftStoreTests(): List<DynamicTest> = listOf(
        case("empty slot vacate invalidates a selected recovery destination without a persistence write") {
            val defaults = InMemoryDraftDefaults()
            val store = DraftStore(defaults)
            val id = channel(radioId, 2u)
            val revision = store.revision(id)
            store.clearChannelDrafts(radioId, setOf(2u))
            store.clearChannelDrafts(radioId, setOf(2u))
            assertFalse(store.restoreIfUnchanged(id, revision, "old selected text"))
            assertNull(store.draft(id))
            assertEquals(0, defaults.writes)
            assertTrue(store.restoreIfUnchanged(id, store.revision(id), "new explicit selection"))
            assertEquals("new explicit selection", store.draft(id))
        },
        case("direct typing and clearing invalidates a recovery token without affecting another chat") {
            val store = DraftStore(InMemoryDraftDefaults())
            val id = dm(radioId, contactID)
            val other = channel(radioId, 2u)
            val revision = store.revision(id)
            val otherRevision = store.revision(other)
            store.setDraft("new typing", id)
            store.clearDraft(id)
            assertFalse(store.restoreIfUnchanged(id, revision, "stale recovery"))
            assertTrue(store.restoreIfUnchanged(other, otherRevision, "unrelated draft"))
            assertNull(store.draft(id))
            assertFalse(store.restoreIfUnchanged(other, store.revision(other), "overwrite"))
            assertEquals("unrelated draft", store.draft(other))
        },
        case("setDraft / draft / clearDraft round-trip") {
            val store = DraftStore(InMemoryDraftDefaults())
            val id = dm(radioId, contactID)
            assertNull(store.draft(id))
            store.setDraft("hello", id)
            assertEquals("hello", store.draft(id))
            store.clearDraft(id)
            assertNull(store.draft(id))
        },
        case("draft survives a new DraftStore instance on the same suite (restart)") {
            val defaults = InMemoryDraftDefaults()
            val id = dm(radioId, contactID)
            DraftStore(defaults).setDraft("persisted", id)
            assertEquals("persisted", DraftStore(defaults).draft(id))
        },
        case("whitespace / newline-only input removes the entry") {
            val store = DraftStore(InMemoryDraftDefaults())
            val id = dm(radioId, contactID)
            store.setDraft("real", id)
            assertEquals("real", store.draft(id))
            store.setDraft("   \n\t ", id)
            assertNull(store.draft(id))
        },
        case("non-empty draft with a trailing newline is stored verbatim") {
            val store = DraftStore(InMemoryDraftDefaults())
            val id = dm(radioId, contactID)
            store.setDraft("hi\n", id)
            assertEquals("hi\n", store.draft(id))
        },
        case("draftToApply returns the saved draft when the field is empty") {
            val store = DraftStore(InMemoryDraftDefaults())
            val id = dm(radioId, contactID)
            store.setDraft("saved", id)
            assertEquals("saved", store.draftToApply("", id))
        },
        case("draftToApply returns nil when the field already has text") {
            val store = DraftStore(InMemoryDraftDefaults())
            val id = dm(radioId, contactID)
            store.setDraft("saved", id)
            assertNull(store.draftToApply("typing", id))
        },
        case("draftToApply returns nil when no draft exists") {
            assertNull(DraftStore(InMemoryDraftDefaults()).draftToApply("", dm(radioId, contactID)))
        },
        case("dm and channel drafts at matching identifiers do not collide") {
            val store = DraftStore(InMemoryDraftDefaults())
            val dmID = dm(radioId, contactID)
            val channelID = channel(radioId, 3u)
            store.setDraft("dm-text", dmID)
            store.setDraft("channel-text", channelID)
            assertEquals("dm-text", store.draft(dmID))
            assertEquals("channel-text", store.draft(channelID))
        },
        case("same channel index on different radios is isolated") {
            val store = DraftStore(InMemoryDraftDefaults())
            val radioA = RadioId(UUID.randomUUID())
            val radioB = RadioId(UUID.randomUUID())
            store.setDraft("a", channel(radioA, 1u))
            store.setDraft("b", channel(radioB, 1u))
            assertEquals("a", store.draft(channel(radioA, 1u)))
            assertEquals("b", store.draft(channel(radioB, 1u)))
        },
        case("clearDraft persists the deletion across a fresh instance (restart)") {
            val defaults = InMemoryDraftDefaults()
            val id = dm(radioId, contactID)
            val first = DraftStore(defaults)
            first.setDraft("persisted", id)
            first.clearDraft(id)
            assertNull(DraftStore(defaults).draft(id))
        },
        case("whitespace-clear persists the deletion across a fresh instance (restart)") {
            val defaults = InMemoryDraftDefaults()
            val id = dm(radioId, contactID)
            val first = DraftStore(defaults)
            first.setDraft("persisted", id)
            first.setDraft("   \n ", id)
            assertNull(DraftStore(defaults).draft(id))
        },
        case("clearDraft on a never-set id is a no-op") {
            val defaults = InMemoryDraftDefaults()
            val id = channel(radioId, 4u)
            val store = DraftStore(defaults)
            store.clearDraft(id)
            assertNull(store.draft(id))
            assertNull(DraftStore(defaults).draft(id))
            assertEquals(0, defaults.writes, "a no-op clear must not persist")
        },
        case("clearChannelDrafts(radioID:indices:) clears the given slots and persists") {
            val defaults = InMemoryDraftDefaults()
            val store = DraftStore(defaults)
            store.setDraft("one", channel(radioId, 1u))
            store.setDraft("two", channel(radioId, 2u))
            store.setDraft("keep", channel(radioId, 3u))
            store.clearChannelDrafts(radioId, setOf<UByte>(1u, 2u))
            assertNull(store.draft(channel(radioId, 1u)))
            assertNull(store.draft(channel(radioId, 2u)))
            assertEquals("keep", store.draft(channel(radioId, 3u)))
            val reloaded = DraftStore(defaults)
            assertNull(reloaded.draft(channel(radioId, 1u)))
            assertEquals("keep", reloaded.draft(channel(radioId, 3u)))
        },
        case("clearChannelDrafts(slotsByRadio:) clears slots across radios, leaving dm drafts intact") {
            val store = DraftStore(InMemoryDraftDefaults())
            val radioA = RadioId(UUID.randomUUID())
            val radioB = RadioId(UUID.randomUUID())
            store.setDraft("a1", channel(radioA, 1u))
            store.setDraft("b1", channel(radioB, 1u))
            store.setDraft("dm", dm(radioA, contactID))
            store.clearChannelDrafts(mapOf(radioA to setOf<UByte>(1u), radioB to setOf<UByte>(1u)))
            assertNull(store.draft(channel(radioA, 1u)))
            assertNull(store.draft(channel(radioB, 1u)))
            assertEquals("dm", store.draft(dm(radioA, contactID)))
        },
        case("draftStorageKey encodes dm and channel keys in the pinned format") {
            val radio = RadioId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
            val contact = UUID.fromString("22222222-2222-2222-2222-222222222222")
            assertEquals("11111111-1111-1111-1111-111111111111|dm|22222222-2222-2222-2222-222222222222", dm(radio, contact).draftStorageKey)
            assertEquals("11111111-1111-1111-1111-111111111111|ch|5", channel(radio, 5u).draftStorageKey)
        },
    )

    @TestFactory
    fun nativeDraftCases(): List<DynamicTest> = listOf(
        native("emptiness uses Foundation whitespacesAndNewlines: U+0085 and U+200B clear, U+001C is kept verbatim") {
            val store = DraftStore(InMemoryDraftDefaults())
            val id = dm(radioId, contactID)
            store.setDraft("x", id)
            store.setDraft("\u0085​ ", id)
            assertNull(store.draft(id))
            store.setDraft("\u001C", id)
            assertEquals("\u001C", store.draft(id))
        },
        native("uppercase UUID segments and storage key constant are pinned") {
            assertEquals("chat.drafts.v1", DraftStore.STORAGE_KEY)
            val radio = RadioId(UUID.fromString("abcdefab-cdef-abcd-efab-cdefabcdefab"))
            assertEquals("ABCDEFAB-CDEF-ABCD-EFAB-CDEFABCDEFAB|ch|255", channel(radio, 255u).draftStorageKey)
        },
        native("batch channel clear persists once and not at all when nothing matched") {
            val defaults = InMemoryDraftDefaults()
            val store = DraftStore(defaults)
            store.setDraft("one", channel(radioId, 1u))
            store.setDraft("two", channel(radioId, 2u))
            val writesBefore = defaults.writes
            store.clearChannelDrafts(radioId, setOf<UByte>(1u, 2u, 9u))
            assertEquals(writesBefore + 1, defaults.writes)
            store.clearChannelDrafts(radioId, setOf<UByte>(7u))
            assertEquals(writesBefore + 1, defaults.writes)
        },
    )

    private fun case(name: String, body: () -> Unit) = DynamicTest.dynamicTest("DraftStoreTests::$name()", body)

    private fun native(name: String, body: () -> Unit) = DynamicTest.dynamicTest("WP-213::$name", body)
}
