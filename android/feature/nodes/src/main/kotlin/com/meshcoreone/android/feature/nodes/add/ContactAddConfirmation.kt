// PortedFrom: MC1/Views/Contacts/ContactAddConfirmationContent.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.feature.nodes.add

import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.deps.ScannedContact
import com.meshcoreone.android.feature.nodes.model.localizedNameRes
import com.meshcoreone.android.core.ui.hoppicker.SwiftText

/**
 * Identity review shared by a contact QR scan and a `meshcore://contact/add` chat link. The scanned
 * name is only a claim; the public key is the verifiable identity, and a saved contact wins.
 */
data class ContactAddConfirmation(
    val contactResult: ScannedContact,
    val existingContact: ContactDTO?,
    val errorMessage: NodesMessage?,
    val isAdding: Boolean,
    val canScanAgain: Boolean,
) {
    val displayedName: String get() = existingContact?.displayName ?: contactResult.name

    val displayedType: ContactType get() = existingContact?.type ?: contactResult.contactType

    val displayedTypeName: NodesMessage get() = NodesMessage.res(displayedType.localizedNameRes())

    /** The claimed name, shown only when it differs (canonically) from a saved contact's name. */
    val scannedAsName: String?
        get() = contactResult.name.takeIf { existingContact != null && SwiftText.canonical(it) != SwiftText.canonical(displayedName) }

    val scannedAsText: NodesMessage?
        get() = scannedAsName?.let { NodesMessage.res(R.string.l10n_app_contacts_contacts_add_scannedas, it) }

    val publicKeyText: String get() = contactResult.publicKey.uppercaseHexString(" ")

    val showsScanAgain: Boolean get() = canScanAgain

    /** The avatar/glyph is decorative; the name and type rows carry the identity for accessibility. */
    val hidesIdentityGlyph: Boolean get() = true

    val primaryButtonTitle: NodesMessage
        get() = NodesMessage.res(
            if (existingContact == null) R.string.l10n_app_contacts_contacts_add_add else R.string.l10n_app_contacts_contacts_add_view,
        )

    /** Button disabled while adding (also the scan-again button). */
    val isPrimaryEnabled: Boolean get() = !isAdding

    val primaryAccessibilityLabel: NodesMessage
        get() = when {
            isAdding -> NodesMessage.res(R.string.l10n_app_contacts_contacts_scan_importing)
            existingContact != null -> NodesMessage.res(R.string.l10n_app_contacts_contacts_add_viewaccessibility, existingContact.displayName)
            else -> NodesMessage.res(R.string.l10n_app_contacts_contacts_add_add)
        }
}
