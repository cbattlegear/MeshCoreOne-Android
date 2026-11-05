// AndroidOnly: WP-311 Native coverage of manual add validation and the QR scan claim/lookup/import flow.
package com.meshcoreone.android.feature.nodes.add

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.FakeContactService
import com.meshcoreone.android.feature.nodes.support.scenario
import com.meshcoreone.android.feature.nodes.support.settle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Test

class AddAndScanNativeTest {
    private val hex64 = "ab".repeat(32)

    @Test
    fun `key field keeps lowercase hex and validates the 64-character length`() = scenario {
        val form = AddContactStateHolder(Harness(clock).dependencies)
        form.setPublicKeyHex("AB cd-EF")
        assertEquals("abcdef", form.state.value.publicKeyHex)
        assertEquals(PublicKeyStatus.Count(6, 64), form.state.value.publicKeyStatus)
        form.setPublicKeyHex(hex64.uppercase())
        assertEquals(PublicKeyStatus.Valid, form.state.value.publicKeyStatus)
        assertFalse(form.state.value.canAdd)
        form.setContactName("Alice")
        assertTrue(form.state.value.canAdd)
    }

    @Test
    fun `names are capped to the usable firmware byte length on whole characters`() = scenario {
        val form = AddContactStateHolder(Harness(clock).dependencies)
        form.setContactName("\u00E9".repeat(20))
        assertEquals("\u00E9".repeat(15), form.state.value.contactName)
    }

    @Test
    fun `fullwidth hex counts toward the length but fails conversion`() = scenario {
        val harness = Harness(clock).connect()
        val form = AddContactStateHolder(harness.dependencies)
        form.setContactName("Alice")
        form.setPublicKeyHex("\uFF21" + hex64.drop(1))
        assertTrue(form.state.value.isValidPublicKey)
        assertFalse(form.add())
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_invalidformat), form.state.value.errorMessage)
        assertTrue(harness.contactService.calls.isEmpty())
    }

    @Test
    fun `add sends a never-advertised flood frame and reports a full table`() = scenario {
        val harness = Harness(clock).connect()
        val form = AddContactStateHolder(harness.dependencies)
        form.setContactName("Relay")
        form.setPublicKeyHex(hex64)
        form.selectType(ContactType.REPEATER)
        assertTrue(form.add())
        val stored = harness.store.contacts.single()
        assertEquals(ContactType.REPEATER, stored.type)
        assertTrue(stored.isFloodRouted)
        assertEquals(0u, stored.lastAdvertTimestamp)
        assertEquals(clock.wallNow.epochSecond.toUInt(), stored.lastModified)
        harness.contactService.addBehavior = { _, _ -> throw NodesContactFailure.ContactTableFull() }
        assertFalse(form.add())
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_nodelistfull, 350), form.state.value.errorMessage)
        assertFalse(form.state.value.isSubmitting)
    }

    @Test
    fun `add without a radio reports not connected`() = scenario {
        val form = AddContactStateHolder(Harness(clock).dependencies)
        assertFalse(form.add())
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_add_error_notconnected), form.state.value.errorMessage)
    }

    @Test
    fun `manual add returns the stored identity before list fetch catches up`() = scenario {
        val harness = Harness(clock).connect()
        val stored = Fixtures.contact(
            radioId = checkNotNull(harness.session.radioId),
            publicKey = Fixtures.repeated(0xAB),
            name = "Stored",
        )
        harness.contactService.addBehavior = { _, _ -> }
        harness.contactService.getContactBehavior = { radio, key ->
            assertEquals(stored.radioId, radio)
            assertEquals(stored.publicKey, key)
            stored
        }
        val form = AddContactStateHolder(harness.dependencies)
        form.setContactName("Entered")
        form.setPublicKeyHex(hex64)

        assertEquals(stored, form.addContact())
        assertEquals(listOf("add:Entered", "getContact"), harness.contactService.calls)
        assertTrue(harness.store.contacts.isEmpty(), "This double models a stale list, not durable persistence")
    }

    @Test
    fun `manual add missing stored identity reports failure instead of fabricating a row`() = scenario {
        val harness = Harness(clock).connect()
        harness.contactService.addBehavior = { _, _ -> }
        val form = AddContactStateHolder(harness.dependencies)
        form.setContactName("Missing")
        form.setPublicKeyHex(hex64)

        assertNull(form.addContact())
        assertNotNull(form.state.value.errorMessage)
        assertFalse(form.state.value.isSubmitting)
        assertTrue(harness.store.contacts.isEmpty())
    }

    @Test
    fun `manual add stored-identity lookup cancellation propagates`() = scenario {
        val harness = Harness(clock).connect()
        val cancelled = CancellationException("lookup cancelled")
        harness.contactService.getContactBehavior = { _, _ -> throw cancelled }
        val form = AddContactStateHolder(harness.dependencies)
        form.setContactName("Cancelled")
        form.setPublicKeyHex(hex64)

        var observed: CancellationException? = null
        try {
            form.addContact()
        } catch (error: CancellationException) {
            observed = error
        }
        assertTrue(observed === cancelled)
        assertFalse(form.state.value.isSubmitting)
        assertNull(form.state.value.errorMessage)
    }

    @Test
    fun `manual add rejects a same-radio session replaced during stored lookup`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val stored = Fixtures.contact(radioId = radio, publicKey = Fixtures.repeated(0xAB))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.contactService.getContactBehavior = { _, _ ->
            entered.complete(Unit)
            release.await()
            stored
        }
        val form = AddContactStateHolder(harness.dependencies)
        form.setContactName("Entered")
        form.setPublicKeyHex(hex64)
        var completed = false
        val adding = scope.launch { form.addContact(); completed = true }
        entered.await()
        harness.session.contacts = FakeContactService(harness.store)
        release.complete(Unit)
        adding.join()
        assertTrue(adding.isCancelled)
        assertFalse(completed)
        assertFalse(form.state.value.isSubmitting)
        assertNull(form.state.value.errorMessage)
        assertEquals(listOf("add:Entered", "getContact"), harness.contactService.calls)
    }

    @Test
    fun `manual add rejects a stored row from a different radio or public key`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val form = AddContactStateHolder(harness.dependencies)
        form.setContactName("Entered")
        form.setPublicKeyHex(hex64)
        listOf(
            Fixtures.contact(radioId = Fixtures.radio(), publicKey = Fixtures.repeated(0xAB)),
            Fixtures.contact(radioId = radio, publicKey = Fixtures.repeated(0xBC)),
        ).forEach { wrong ->
            harness.contactService.getContactBehavior = { _, _ -> wrong }
            assertNull(form.addContact())
            assertNotNull(form.state.value.errorMessage)
        }
    }

    @Test
    fun `scan import never reads a replacement radios store after a held add`() = scenario {
        val harness = Harness(clock).connect()
        val oldRadio = checkNotNull(harness.session.radioId)
        val scan = ScanContactStateHolder(harness.dependencies, scope)
        scan.handleScanResult(harness.codec.exportContactUri("Relay", Fixtures.repeated(0xEE), ContactType.REPEATER))
        settle()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.contactService.addBehavior = { radio, _ ->
            assertEquals(oldRadio, radio)
            entered.complete(Unit)
            release.await()
        }
        var completed = false
        val importing = scope.launch { scan.confirm(); completed = true }
        entered.await()
        val replacement = Harness(clock).connect()
        var replacementReads = 0
        replacement.store.fetchContactBehavior = { _, _ -> replacementReads++; null }
        harness.session.device = replacement.session.device
        harness.session.radioId = replacement.session.radioId
        harness.session.contacts = replacement.contactService
        harness.session.servicesStore = replacement.store
        release.complete(Unit)
        importing.join()
        assertTrue(importing.isCancelled)
        assertFalse(completed)
        assertEquals(0, replacementReads)
        assertEquals(0, scan.state.value.successFeedback)
        assertFalse(scan.state.value.isImporting)
    }

    @Test
    fun `scan reset cancels a held stored lookup without completing the next review`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val scanned = Fixtures.repeated(0xEE)
        val stored = Fixtures.contact(radioId = radio, publicKey = scanned)
        val scan = ScanContactStateHolder(harness.dependencies, scope)
        scan.handleScanResult(harness.codec.exportContactUri("Relay", scanned, ContactType.REPEATER))
        settle()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        harness.contactService.addBehavior = { _, _ -> }
        harness.store.fetchContactBehavior = { _, _ ->
            entered.complete(Unit)
            release.await()
            stored
        }
        val importing = scope.launch { scan.confirm() }
        entered.await()
        scan.resetToScanner()
        release.complete(Unit)
        importing.join()
        assertTrue(importing.isCancelled)
        assertNull(scan.state.value.scannedContact)
        assertEquals(0, scan.state.value.successFeedback)
        assertNull(scan.state.value.errorMessage)
    }

    @Test
    fun `pasting a contact link fills the form and a bad link shows the row error`() = scenario {
        val harness = Harness(clock)
        val form = AddContactStateHolder(harness.dependencies)
        form.pasteContactUrl("https://example.com")
        assertTrue(form.state.value.showPasteError)
        form.pasteContactUrl(harness.codec.exportContactUri("Room", Fixtures.repeated(0xCD), ContactType.ROOM))
        val state = form.state.value
        assertFalse(state.showPasteError)
        assertEquals("Room", state.contactName)
        assertEquals("cd".repeat(32), state.publicKeyHex)
        assertEquals(ContactType.ROOM, state.selectedType)
    }

    @Test
    fun `an invalid scan reports the format error and keeps scanning`() = scenario {
        val scan = ScanContactStateHolder(Harness(clock).connect().dependencies, scope)
        scan.handleScanResult("not a contact")
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_scan_error_invalidformat), scan.state.value.errorMessage)
        assertEquals(1, scan.state.value.errorFeedback)
        assertNull(scan.state.value.scannedContact)
    }

    @Test
    fun `a saved contact opens as View and later scans are ignored`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val saved = Fixtures.contact(radioId = radio, publicKey = Fixtures.repeated(0xAA), name = "Saved")
        harness.store.contacts += saved
        val scan = ScanContactStateHolder(harness.dependencies, scope)
        scan.handleScanResult(harness.codec.exportContactUri("Claimed", saved.publicKey, ContactType.CHAT))
        scan.handleScanResult(harness.codec.exportContactUri("Other", Fixtures.repeated(0xBB), ContactType.CHAT))
        settle()
        val state = scan.state.value
        assertEquals("Claimed", state.scannedContact?.name)
        assertEquals(saved, state.existingContact)
        assertEquals(NodesMessage.Text("Saved"), state.title)
        assertEquals(1, state.selectionFeedback)
        assertEquals(ScanConfirmOutcome.Completed(saved), scan.confirm())
        assertTrue(harness.contactService.calls.isEmpty())
    }

    @Test
    fun `importing a new scanned contact adds it after confirmation and opens the stored row`() = scenario {
        val harness = Harness(clock).connect()
        val scan = ScanContactStateHolder(harness.dependencies, scope)
        scan.handleScanResult(harness.codec.exportContactUri("Relay", Fixtures.repeated(0xEE), ContactType.REPEATER))
        settle()
        assertTrue(harness.contactService.calls.isEmpty())
        val outcome = assertIs<ScanConfirmOutcome.Completed>(scan.confirm())
        assertEquals("Relay", outcome.contact.name)
        assertEquals(listOf("add:Relay"), harness.contactService.calls)
        assertEquals(1, scan.state.value.successFeedback)
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_scan_accessibility_added, "Relay"), harness.announcer.messages.last())
    }

    @Test
    fun `import failure is announced and scan again resets the review`() = scenario {
        val harness = Harness(clock).connect()
        harness.contactService.addBehavior = { _, _ -> error("radio") }
        val scan = ScanContactStateHolder(harness.dependencies, scope)
        scan.handleScanResult(harness.codec.exportContactUri("Relay", Fixtures.repeated(0xEE), ContactType.REPEATER))
        settle()
        assertEquals(ScanConfirmOutcome.Stay, scan.confirm())
        val message = NodesMessage.res(R.string.l10n_app_contacts_contacts_scan_error_importfailed, "failed: radio")
        assertEquals(message, scan.state.value.errorMessage)
        assertEquals(message, harness.announcer.messages.last())
        assertFalse(scan.state.value.isImporting)
        scan.resetToScanner()
        assertNull(scan.state.value.scannedContact)
        assertNull(scan.state.value.errorMessage)
    }
}
