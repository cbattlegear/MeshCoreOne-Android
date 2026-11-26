// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.deeplinks.MeshCoreUriParser
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test

class ContactUriOriginalPredicateTest {
    @Test
    fun originalOutOfRangeContactTypeKeepsTheNameAndFallsBackToChat() {
        val key = "A1432C142E1615EAB6414856F58C90CD61E7C5901650142E5EFE4D2F1332654D"
        val parsed = assertNotNull(
            MeshCoreUriParser.parseContact("meshcore://contact/add?name=Node&public_key=$key&type=300"),
        )
        assertEquals("Node", parsed.name)
        assertEquals(ContactType.CHAT, parsed.contactType)
    }
}
