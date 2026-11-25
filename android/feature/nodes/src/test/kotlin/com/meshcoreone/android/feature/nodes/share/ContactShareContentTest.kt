// PortedFrom: MC1Tests/Views/Contacts/ContactShareContentTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
// PortedFrom: MC1Tests/Views/Contacts/ContactURIActivityItemTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.share

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.support.FakeUriCodec
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.OriginalCase
import com.meshcoreone.android.feature.nodes.support.scenario
import com.meshcoreone.android.feature.nodes.support.settle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import org.junit.Test

class ContactShareContentTest {
    private val uri = "meshcore://contact/add?name=Alice&public_key=AB&type=1"
    private val subject = "MeshCore One Contact"

    @Test @OriginalCase("ContactShareContentTests::compact public key hex has no whitespace()")
    fun `compact public key hex has no whitespace`() {
        val hex = ContactShareContent.compactPublicKeyHex(Bytes.of(0xAA, 0xBB, 0xCC, 0xDD))
        assertEquals("AABBCCDD", hex)
        assertFalse(hex.any { it.isWhitespace() })
    }

    @Test @OriginalCase("ContactURIActivityItemTests::placeholder is a String not a URL()", "platform-adaptation")
    fun `share payload is plain text not a URI`() {
        // iOS: an empty String placeholder makes the sheet treat the item as text. Android: text/plain EXTRA_TEXT.
        val payload = ContactShareTextPayload(uri, subject)
        assertEquals("text/plain", payload.mimeType)
        assertTrue(payload.text.startsWith("meshcore://"))
        assertEquals(subject, payload.subject)
    }

    @Test @OriginalCase("ContactURIActivityItemTests::copy activity returns the uri string()", "platform-adaptation")
    fun `copy target receives the uri string`() {
        assertEquals(uri, ContactShareTextPayload(uri, subject).copyText)
    }

    @Test
    fun `share sheet delegates the uri to the contact service codec and reuses it for the QR`() = scenario {
        val harness = Harness(clock)
        val key = Bytes.of(*IntArray(32) { 0xAB })
        val holder = ContactQrShareStateHolder("Relay", key, ContactType.REPEATER, harness.dependencies, scope)
        assertEquals(FakeUriCodec().exportContactUri("Relay", key, ContactType.REPEATER), holder.contactUri)
        assertEquals(holder.contactUri, holder.qrSpec.payload)
        assertEquals(10.0, holder.qrSpec.scale)
        assertEquals("M", holder.qrSpec.correctionLevel)
        assertEquals(holder.contactUri, holder.sharePayload(subject).text)
    }

    @Test
    fun `copy public key returns compact hex and shows feedback for two seconds`() = scenario {
        val holder = ContactQrShareStateHolder("A", Bytes.of(0x0A, 0xFF), ContactType.CHAT, Harness(clock).dependencies, scope)
        assertEquals("0AFF", holder.copyPublicKey())
        settle()
        assertTrue(holder.copyFeedback.showing.value)
        clock.advanceBy(1.seconds)
        assertTrue(holder.copyFeedback.showing.value)
        clock.advanceBy(1.seconds)
        assertFalse(holder.copyFeedback.showing.value)
        assertEquals("0A FF", holder.publicKeyText)
    }
}
