// PortedFrom: MC1Tests/Views/Chats/SenderContactMatcherTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.feature.chats.list.support.Fixtures.contact
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Test

class SenderContactMatcherTest {
    @Test @OriginalCase("SenderContactMatcherTests::exact name match returns the contact()")
    fun `exact name match returns the contact`() {
        val alice = contact("Alice")
        assertEquals(listOf(alice.id), SenderContactMatcher.filter(listOf(alice), "Alice").map { it.id })
    }

    @Test @OriginalCase("SenderContactMatcherTests::case-insensitive match returns the contact()")
    fun `case-insensitive match returns the contact`() {
        val alice = contact("Alice")
        assertEquals(listOf(alice.id), SenderContactMatcher.filter(listOf(alice), "ALICE").map { it.id })
    }

    @Test @OriginalCase("SenderContactMatcherTests::non-matching name returns empty()")
    fun `non-matching name returns empty`() {
        assertTrue(SenderContactMatcher.filter(listOf(contact("Alice")), "Bob").isEmpty())
    }

    @Test @OriginalCase("SenderContactMatcherTests::multiple contacts sharing a name all match()")
    fun `multiple contacts sharing a name all match`() {
        val first = contact("Alice")
        val second = contact("alice")
        val bob = contact("Bob")
        assertEquals(setOf(first.id, second.id), SenderContactMatcher.filter(listOf(first, second, bob), "Alice").map { it.id }.toSet())
    }

    @Test @OriginalCase("SenderContactMatcherTests::leading or trailing whitespace does not match (no trimming)()")
    fun `leading or trailing whitespace does not match`() {
        assertTrue(SenderContactMatcher.filter(listOf(contact("Alice")), " Alice ").isEmpty())
    }

    @Test @OriginalCase("SenderContactMatcherTests::excludeBlocked drops blocked contacts()")
    fun `excludeBlocked drops blocked contacts`() {
        val open = contact("Alice", isBlocked = false)
        val blocked = contact("Alice", isBlocked = true)
        assertEquals(listOf(open.id), SenderContactMatcher.filter(listOf(open, blocked), "Alice", excludeBlocked = true).map { it.id })
    }

    @Test @OriginalCase("SenderContactMatcherTests::excludeBlocked=false keeps blocked contacts()")
    fun `excludeBlocked false keeps blocked contacts`() {
        val open = contact("Alice", isBlocked = false)
        val blocked = contact("Alice", isBlocked = true)
        assertEquals(setOf(open.id, blocked.id), SenderContactMatcher.filter(listOf(open, blocked), "Alice").map { it.id }.toSet())
    }

    @Test @OriginalCase("SenderContactMatcherTests::empty contact list returns empty()")
    fun `empty contact list returns empty`() {
        assertTrue(SenderContactMatcher.filter(emptyList(), "Alice").isEmpty())
    }
}
