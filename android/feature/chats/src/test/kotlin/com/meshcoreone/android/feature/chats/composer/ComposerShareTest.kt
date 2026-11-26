// PortedFrom: MC1Tests/Views/Chats/Components/ChatShareMenuTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.chats.composer

import com.meshcoreone.android.core.model.Coordinate
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ComposerShareTest {
    private val validKey = Bytes(ByteArray(32) { 0xAB.toByte() })
    private val sample = Coordinate(37.0, -122.0)

    @Test
    @OriginalCase("ChatShareMenuTests::Location is shareable when authorized, even with no current fix or node coordinate()")
    fun `Location is shareable when authorized, even with no current fix or node coordinate`() {
        assertTrue(ComposerShare.canShareLocation(null, null, true))
    }

    @Test
    @OriginalCase("ChatShareMenuTests::Location is shareable from a node coordinate without authorization()")
    fun `Location is shareable from a node coordinate without authorization`() {
        assertTrue(ComposerShare.canShareLocation(null, sample, false))
    }

    @Test
    @OriginalCase("ChatShareMenuTests::Location is not shareable with no coordinate and no authorization()")
    fun `Location is not shareable with no coordinate and no authorization`() {
        assertFalse(ComposerShare.canShareLocation(null, null, false))
    }

    @Test
    @OriginalCase("ChatShareMenuTests::My info is shareable with a full-length key and a non-empty name()")
    fun `My info is shareable with a full-length key and a non-empty name`() {
        assertTrue(ComposerShare.canShareMyInfo(validKey, "Base 1"))
    }

    @Test
    @OriginalCase("ChatShareMenuTests::My info is not shareable with a wrong-length public key()")
    fun `My info is not shareable with a wrong-length public key`() {
        assertFalse(ComposerShare.canShareMyInfo(Bytes(ByteArray(8) { 0xAB.toByte() }), "Base 1"))
    }

    @Test
    @OriginalCase("ChatShareMenuTests::My info is not shareable with a blank name()")
    fun `My info is not shareable with a blank name`() {
        assertFalse(ComposerShare.canShareMyInfo(validKey, "   "))
    }

    @Test
    fun `location text is dot-decimal regardless of locale and insertShared separates only when needed`() {
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals("37.334900, -122.009020", ComposerShare.locationText(Coordinate(37.3349, -122.00902)))
        } finally {
            java.util.Locale.setDefault(previous)
        }
        assertEquals("x", ComposerShare.insertShared("", "x"))
        assertEquals("a x", ComposerShare.insertShared("a", "x"))
        assertEquals("a\nx", ComposerShare.insertShared("a\n", "x"))
    }

    @Test
    fun `contact tokens round trip and reject bad fields`() {
        val token = ContactShare.formatShare(validKey, ContactType.CHAT, "Base>1: x")
        assertEquals("<${"AB".repeat(32)}:1:Base1: x>", token)
        val parsed = ContactShare.parseShare(token)!!
        assertEquals("Base1: x", parsed.name)
        assertEquals(ContactType.CHAT, parsed.type)
        assertNull(ContactShare.parseShare("<${"AB".repeat(32)}:300:x>"))
        assertNull(ContactShare.parseShare("<${"AB".repeat(31)}:1:x>"))
        assertEquals(
            "meshcore://contact/add?name=A%20B%2BC&public_key=${"AB".repeat(32)}&type=1",
            ContactShare.exportContactUri("A B+C", validKey, ContactType.CHAT),
        )
    }
}
