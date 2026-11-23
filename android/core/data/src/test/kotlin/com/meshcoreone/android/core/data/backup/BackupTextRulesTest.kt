// AndroidOnly: WP-309 Issue #729 local-only preferences, selection incarnation and blocked archive backup boundaries.
package com.meshcoreone.android.core.data.backup

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.TextRuleAdmission
import com.meshcoreone.android.core.model.*
import kotlin.test.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BackupTextRulesTest : BackupRoomTest() {
    @Test fun backupDoesNotCarryRulesOverridesOrArchiveAndRestoredContactsGetNewSelectionIdentity() = runTest {
        store.saveDevice(device())
        val contact = contact()
        store.saveContact(contact)
        store.updateTextRules {
            it.copy(rules = listOf(TextRule(phrase = "blocked")),
                selections = it.selections + (TextRuleKind.DIRECT_MESSAGE to TextRuleSelection(TextRuleSelectionMode.ALL)))
        }
        val selected = store.textRuleConversations().single()
        store.setTextRuleOverride(selected.key, TextRuleOverride.ON)
        val incoming = message(text = "blocked", direction = MessageDirection.INCOMING)
            .copy(senderKeyPrefix = contact.publicKey.prefix(6))
        assertEquals(TextRuleAdmission.DISCARDED,
            store.admitTextMessage(incoming, contact, null, incoming.text, 0u, false))
        val exported = service.export(store)
        val envelope = AppBackupCodec().parseBackup(exported.data)
        assertTrue(envelope.messages.isEmpty(), "blocked body must not enter exported history")
        val bytes = envelope.jsonObject().toString()
        assertFalse(bytes.contains(selected.key.conversationID.toString(), ignoreCase = true))
        assertFalse(bytes.contains("blocked"), "neither phrase nor archive may enter envelope v1")
        val preference = store.textRulesPreference()
        service.importBackup(envelope, store)
        assertEquals(TextRuleOverride.ON, store.textRuleConversations().single().override)
        store.deleteContact(EntityKey(RADIO, contact.id))
        assertTrue(store.recentlyBlockedTexts().isEmpty())
        service.importBackup(envelope, store)
        val restored = store.textRuleConversations().single()
        assertNotEquals(selected.key, restored.key)
        assertEquals(TextRuleOverride.INHERIT, restored.override)
        assertEquals(preference, store.textRulesPreference())
        withIndependentStore { _, other ->
            service.importBackup(envelope, other)
            assertEquals(TextRulesPreference(), other.textRulesPreference())
            assertTrue(other.recentlyBlockedTexts().isEmpty())
            assertEquals(TextRuleOverride.INHERIT, other.textRuleConversations().single().override)
        }
    }
}
