// PortedFrom: MC1Services/Sources/MC1Services/Utilities/ContactResult.swift@5e980a53e077c45a1270170612fdf5dae97792ff
package com.meshcoreone.android.core.services.contacts

import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType

/** Parsed contact identity recovered from a deep-link URL or a contact share token. */
data class ContactResult(
    val name: String,
    val publicKey: Bytes,
    val contactType: ContactType,
) {
    /** Identifiable id: the public key as uppercase hex. */
    val id: String get() = publicKey.uppercaseHexString()
}
