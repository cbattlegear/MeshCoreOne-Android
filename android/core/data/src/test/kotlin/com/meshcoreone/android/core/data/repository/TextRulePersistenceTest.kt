// AndroidOnly: WP-309 Issue #729 native Room assertions for newer upstream TextRulesPreference/Ownership contracts.
package com.meshcoreone.android.core.data.repository

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.database.MeshCoreDatabase
import com.meshcoreone.android.core.database.TextRulePreferenceEntity
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TextRulePersistenceTest : RepositoryTest() {
    private suspend fun directRules(field: TextRuleField = TextRuleField.MESSAGE, phrase: String = "filter") {
        store.saveDevice(device())
        store.saveContact(contact())
        store.updateTextRules {
            it.copy(rules = listOf(TextRule(phrase = phrase, field = field)),
                selections = it.selections + (TextRuleKind.DIRECT_MESSAGE to TextRuleSelection(TextRuleSelectionMode.ALL)))
        }
    }
    private fun incoming(text: String = "please FILTER this", stamp: UInt = 1u) =
        message(text = text, timestamp = stamp, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED)
            .copy(senderKeyPrefix = key().prefix(6))
    private suspend fun admit(message: MessageDTO, contact: ContactDTO? = contact(), reaction: Boolean = false) =
        store.admitTextMessage(message, contact, null, message.text, 0u, reaction)

    @Test fun archiveIsCommittedBeforeDiscardReplayRemainsSuppressedAfterRuleRemovalAndHistoryIsUntouched() = runTest {
        directRules()
        val existing = message(text = "filter is old history")
        store.saveMessage(existing)
        val row = incoming()
        assertEquals(TextRuleAdmission.DISCARDED, admit(row))
        assertNull(store.fetchMessage(entity(id = row.id)))
        val archived = store.recentlyBlockedTexts().single()
        assertEquals(row.text, archived.body)
        assertEquals(row.text, archived.identity.wireText)
        assertEquals(CONTACT_A, archived.contactID)
        assertEquals(listOf(TextRuleHighlight(7, 6)), archived.hit.bodyHighlights)
        store.updateTextRules { it.copy(rules = emptyList()) }
        assertEquals(TextRuleAdmission.DISCARDED, admit(row.copy(id = UUID.randomUUID())))
        assertEquals(1, store.recentlyBlockedTexts().size)
        assertEquals(existing, store.fetchMessage(entity(id = existing.id)))
        assertEquals(0, store.getUnreadCount(entity()))
    }

    @Test fun failedArchiveAndPreferenceWritesRollBackAndRetryDoesNotReportFakeSuccess() = runTest {
        directRules()
        val committed = store.textRulesPreference()
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_text_archive AFTER INSERT ON recently_blocked_texts BEGIN SELECT RAISE(FAIL, 'test archive failure'); END")
        val row = incoming()
        assertFailsWith<PersistenceStoreException> { admit(row) }
        assertTrue(store.recentlyBlockedTexts().isEmpty())
        assertNull(store.fetchMessage(entity(id = row.id)))
        assertTrue(issues.isNotEmpty())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_text_archive")
        assertEquals(TextRuleAdmission.DISCARDED, admit(row))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_text_preference BEFORE INSERT ON text_rule_preferences BEGIN SELECT RAISE(FAIL, 'test preference failure'); END")
        assertFailsWith<PersistenceStoreException> { store.updateTextRules { it.copy(rules = emptyList()) } }
        assertEquals(committed, store.textRulesPreference())
        db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_text_preference")
        assertFailsWith<IllegalArgumentException> { store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = " \n"))) } }
        assertEquals(committed, store.textRulesPreference())
    }

    @Test fun malformedPreferencesAreRetainedAndReportedWhileReceivingUnfilteredUntilRecovery() = runTest {
        directRules()
        val committed = db.textRules().preference()!!
        db.textRules().preference(TextRulePreferenceEntity(payload = "invalid settings"))
        assertFailsWith<PersistenceStoreException> { store.textRulesPreference() }
        assertTrue(store.textRuleLoadFailed.value)
        assertFailsWith<PersistenceStoreException> { store.updateTextRules { TextRulesPreference() } }
        val row = incoming()
        assertEquals(TextRuleAdmission.SAVED, admit(row))
        assertNotNull(store.fetchMessage(entity(id = row.id)))
        assertEquals("invalid settings", db.textRules().preference()?.payload)
        db.textRules().preference(committed)
        assertEquals(TextRuleJson.preference(committed.payload), store.textRulesPreference())
        assertFalse(store.textRuleLoadFailed.value)
    }

    @Test fun ambiguousSendersAreNotGuessedAndExistingContactBlocksDoNotBecomeTextBlocks() = runTest {
        directRules(TextRuleField.SENDER, "TestContact")
        val first = incoming()
        assertEquals(TextRuleAdmission.DISCARDED, admit(first))
        val collision = contact(id = UUID.randomUUID(),
            publicKey = key().prefix(6) + Bytes(ByteArray(26) { 0x55 }))
        store.saveContact(collision)
        val second = incoming(stamp = 2u)
        assertEquals(TextRuleAdmission.SAVED, admit(second))
        val blocked = contact().copy(isBlocked = true)
        store.saveContact(blocked)
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "filter"))) }
        assertEquals(TextRuleAdmission.SAVED, admit(incoming(stamp = 3u), blocked))
        assertEquals(1, store.recentlyBlockedTexts().size)
    }

    @Test fun reactionsMatchOnlySenderAndAreNotArchived() = runTest {
        directRules()
        assertEquals(TextRuleAdmission.REACTION, admit(incoming(), reaction = true))
        assertTrue(store.recentlyBlockedTexts().isEmpty())
        store.updateTextRules { it.copy(rules = listOf(TextRule(phrase = "TestContact", field = TextRuleField.SENDER))) }
        assertEquals(TextRuleAdmission.DISCARDED, admit(incoming(), reaction = true))
        assertTrue(store.recentlyBlockedTexts().isEmpty())
    }

    @Test fun archiveIsBoundedByArrivalAndContactDeletionRetiresSelectionAndOwnedArchive() = runTest {
        directRules()
        val before = store.textRuleConversations().single()
        store.setTextRuleOverride(before.key, TextRuleOverride.ON)
        repeat(105) { index ->
            assertEquals(TextRuleAdmission.DISCARDED, admit(incoming(stamp = index.toUInt()).copy(
                createdAt = AT.plusSeconds(index.toLong()))))
        }
        assertEquals(100, store.recentlyBlockedTexts().size)
        assertEquals(104u, store.recentlyBlockedTexts().first().identity.senderTimestamp)
        assertEquals(5u, store.recentlyBlockedTexts().last().identity.senderTimestamp)
        store.deleteContactIfUnreferenced(entity())
        assertNotNull(store.fetchContact(entity()), "archive retains its bound contact")
        store.deleteContact(entity())
        assertTrue(store.recentlyBlockedTexts().isEmpty())
        store.saveContact(contact())
        val restored = store.textRuleConversations().single()
        assertNotEquals(before.key, restored.key)
        assertEquals(TextRuleOverride.INHERIT, restored.override)
        assertFailsWith<PersistenceStoreException> { store.setTextRuleOverride(before.key, TextRuleOverride.ON) }
    }

    @Test fun preferenceAndConversationIdentitySurviveFileReopen() = runTest {
        store.close(); db.close()
        val name = "text-rules-restart.db"
        db = MeshCoreDatabase.open(context, name)
        store = newStore()
        directRules()
        val conversation = store.textRuleConversations().single()
        store.setTextRuleOverride(conversation.key, TextRuleOverride.OFF)
        val preference = store.textRulesPreference()
        val row = incoming()
        assertEquals(TextRuleAdmission.SAVED, admit(row))
        store.close(); db.close()
        db = MeshCoreDatabase.open(context, name)
        store = newStore()
        assertEquals(preference, store.textRulesPreference())
        assertEquals(conversation.key, store.textRuleConversations().single().key)
        assertEquals(TextRuleOverride.OFF, store.textRuleConversations().single().override)
        assertEquals(row.text, store.fetchMessage(entity(id = row.id))?.text)
    }
}
