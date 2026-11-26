// PortedFrom: MC1Tests/Views/Contacts/ScanContactQRFeedbackTests.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.add

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.deps.ScannedContact
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.OriginalCase
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

/** Typed resource assertions stand in for the resolved `L10n` strings the Swift suite compares. */
class ScanContactQRFeedbackTest {
    private val samplePublicKey = Fixtures.repeated(0xAA)
    private val add = NodesMessage.res(R.string.l10n_app_contacts_contacts_add_add)
    private val importing = NodesMessage.res(R.string.l10n_app_contacts_contacts_scan_importing)
    private val alreadyAdded = NodesMessage.res(R.string.l10n_app_contacts_contacts_add_alreadyadded)

    private fun content(
        isAdding: Boolean = false,
        qrName: String = "Example Repeater",
        existingContact: ContactDTO? = null,
        canScanAgain: Boolean = false,
    ) = ContactAddConfirmation(ScannedContact(qrName, samplePublicKey, ContactType.REPEATER), existingContact, null, isAdding, canScanAgain)

    private fun sampleContact(name: String) = Fixtures.contact(publicKey = samplePublicKey, name = name, type = ContactType.REPEATER)

    @Test @OriginalCase("ScanContactQRFeedbackTests::idle add button uses add and hides the type glyph()")
    fun `idle add button uses add and hides the type glyph`() {
        val content = content(isAdding = false)
        assertEquals(add, content.primaryAccessibilityLabel)
        assertNotEquals(importing, content.primaryAccessibilityLabel)
        assertNotEquals(alreadyAdded, content.primaryAccessibilityLabel)
        assertTrue(content.hidesIdentityGlyph)
        assertTrue(samplePublicKey.uppercaseHexString(" ") in content.publicKeyText)
        assertFalse(content.showsScanAgain)
    }

    @Test @OriginalCase("ScanContactQRFeedbackTests::importing add button uses importing not add()")
    fun `importing add button uses importing not add`() {
        val content = content(isAdding = true)
        assertEquals(importing, content.primaryAccessibilityLabel)
        assertNotEquals(add, content.primaryAccessibilityLabel)
    }

    @Test @OriginalCase("ScanContactQRFeedbackTests::scan again appears when provided()")
    fun `scan again appears when provided`() {
        assertTrue(content(canScanAgain = true).showsScanAgain)
    }

    @Test @OriginalCase("ScanContactQRFeedbackTests::existing contact uses view not add()")
    fun `existing contact uses view not add`() {
        val existing = sampleContact("Example Repeater")
        val content = content(existingContact = existing)
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_viewaccessibility, existing.displayName), content.primaryAccessibilityLabel)
        assertNotEquals(alreadyAdded, content.primaryAccessibilityLabel)
        assertNotEquals(add, content.primaryAccessibilityLabel)
        assertNull(content.scannedAsText)
    }

    @Test @OriginalCase("ScanContactQRFeedbackTests::name mismatch shows scanned as caption()")
    fun `name mismatch shows scanned as caption`() {
        val existing = sampleContact("Saved Repeater")
        val content = content(qrName = "Claimed Name", existingContact = existing)
        assertEquals(existing.displayName, content.displayedName)
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_scannedas, "Claimed Name"), content.scannedAsText)
    }
}
