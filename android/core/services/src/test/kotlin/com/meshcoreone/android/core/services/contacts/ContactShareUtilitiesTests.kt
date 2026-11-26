// PortedFrom: MC1Services/Tests/MC1ServicesTests/ContactShareUtilitiesTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

class ContactShareUtilitiesTests {
    private val suite = "ContactShareUtilitiesTests"

    private fun publicKey(): Bytes = assertNotNull(applicationBytesFromHex(VALID_HEX))

    private fun case(name: String, body: suspend kotlinx.coroutines.CoroutineScope.() -> Unit): DynamicTest =
        contactsOriginal(suite, name, body = body)

    @TestFactory
    fun roundTripCases(): List<DynamicTest> = listOf(ContactType.CHAT, ContactType.REPEATER, ContactType.ROOM, ContactType.SENSOR).map { type ->
        contactsOriginalArgument(
            suite, "formatShare then parseShare round-trips for every contact type", "(type : ContactType)", type,
        ) {
            val publicKey = publicKey()
            val name = "AVN2"

            val token = ContactShareUtilities.formatShare(publicKey, type, name)
            val result = assertNotNull(ContactShareUtilities.parseShare(token))

            assertEquals(publicKey, result.publicKey)
            assertEquals(type, result.contactType)
            assertEquals(name, result.name)
        }
    }

    @TestFactory
    fun typeBoundCases(): List<DynamicTest> = listOf("256", "300", "99999999999999999999", "0", "5").map { typeDigits ->
        contactsOriginalArgument(
            suite, "parseShare rejects out-of-range and overflowing type values without trapping",
            "(typeDigits : String)", typeDigits,
        ) {
            val token = "<$VALID_HEX:$typeDigits:x>"
            assertNull(ContactShareUtilities.parseShare(token))
        }
    }

    @TestFactory
    fun sourceCases(): List<DynamicTest> = listOf(
        case("formatShare emits uppercase hex") {
            val token = ContactShareUtilities.formatShare(publicKey(), ContactType.CHAT, "Node")
            assertTrue(token.contains(VALID_HEX))
            assertEquals("<$VALID_HEX:1:Node>", token)
        },
        case("parseShare rejects a 63-char (short) public key") {
            val shortHex = VALID_HEX.dropLast(1)
            assertNull(ContactShareUtilities.parseShare("<$shortHex:1:Node>"))
        },
        case("parseShare rejects non-hex characters in the public key") {
            val nonHex = "G1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"
            assertNull(ContactShareUtilities.parseShare("<$nonHex:1:Node>"))
        },
        case("parseShare rejects a token missing the name field") {
            assertNull(ContactShareUtilities.parseShare("<$VALID_HEX:1>"))
        },
        case("parseShare rejects a token missing type and name") {
            assertNull(ContactShareUtilities.parseShare("<$VALID_HEX>"))
        },
        case("parseShare rejects an empty name") {
            assertNull(ContactShareUtilities.parseShare("<$VALID_HEX:1:>"))
        },
        case("parseShare round-trips a name containing '>' to its stripped form") {
            val token = ContactShareUtilities.formatShare(publicKey(), ContactType.CHAT, "AV>N2")
            assertFalse(token.drop(1).dropLast(1).contains(">"))

            val result = assertNotNull(ContactShareUtilities.parseShare(token))
            assertEquals("AVN2", result.name)
        },
        case("parseShare preserves a colon inside the name") {
            val name = "Base:Camp:1"
            val token = ContactShareUtilities.formatShare(publicKey(), ContactType.CHAT, name)
            assertEquals(name, assertNotNull(ContactShareUtilities.parseShare(token)).name)
        },
        case("parseShare preserves a newline inside the name") {
            val name = "Line1\nLine2"
            val token = ContactShareUtilities.formatShare(publicKey(), ContactType.CHAT, name)
            assertEquals(name, assertNotNull(ContactShareUtilities.parseShare(token)).name)
        },
        case("parseShare preserves an RTL-override character in the name") {
            val name = "Node‮flip"
            val token = ContactShareUtilities.formatShare(publicKey(), ContactType.CHAT, name)
            assertEquals(name, assertNotNull(ContactShareUtilities.parseShare(token)).name)
        },
        case("parseShare round-trips a 1000-character name") {
            val name = "n".repeat(1000)
            val token = ContactShareUtilities.formatShare(publicKey(), ContactType.CHAT, name)
            assertEquals(name, assertNotNull(ContactShareUtilities.parseShare(token)).name)
        },
        case("extractShares finds multiple tokens amid plain text") {
            val first = ContactShareUtilities.formatShare(publicKey(), ContactType.CHAT, "Alice")
            val second = ContactShareUtilities.formatShare(publicKey(), ContactType.REPEATER, "Bob")
            val text = "Add these: $first and also $second thanks"

            val results = ContactShareUtilities.extractShares(text)
            assertEquals(2, results.size)
            assertEquals("Alice", results[0].name)
            assertEquals(ContactType.CHAT, results[0].contactType)
            assertEquals("Bob", results[1].name)
            assertEquals(ContactType.REPEATER, results[1].contactType)
        },
        case("extractShares returns empty when there are no tokens") {
            assertTrue(ContactShareUtilities.extractShares("Just some plain text, no tokens here.").isEmpty())
        },
    )

    private companion object {
        /** A real 32-byte public key rendered as uppercase hex (64 chars). */
        const val VALID_HEX = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"
    }
}
