// AndroidOnly: WP-302 Coherent contact-link initiation and deferred pure navigation commits.
package com.meshcoreone.android.app.navigation

import com.meshcoreone.android.app.container.RadioSessionContainer
import com.meshcoreone.android.core.contracts.domain.SessionToken
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.runtime.ConnectionManager
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkContext

internal data class ContactLinkLookup(
    val owner: NavigationCoordinator,
    val generation: Long,
    val boundToken: SessionToken?,
    val navigationRadioId: RadioId?,
    val radioId: RadioId?,
    val publicKey: Bytes,
    val selfPublicKey: Bytes?,
    val revision: Long?,
    val disposition: ContactLinkCaptureDisposition,
) {
    val allowsInitialBinding: Boolean get() = boundToken == null && navigationRadioId == null
}

internal class CapturedContactLinkContext(
    val lookup: ContactLinkLookup,
    val graph: RadioSessionContainer?,
    val lifecycle: ConnectionManager.NavigationLifecycleStamp,
) : ContactLinkContext {
    override val radioId: RadioId? get() = lookup.radioId
    override val selfPublicKey: Bytes? get() = lookup.selfPublicKey
    override val disposition: ContactLinkCaptureDisposition get() = lookup.disposition
}
