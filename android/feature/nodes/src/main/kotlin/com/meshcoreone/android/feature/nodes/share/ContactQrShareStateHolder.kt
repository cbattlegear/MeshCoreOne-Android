// PortedFrom: MC1/Views/Contacts/ContactQRShareSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.nodes.share

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesClock
import com.meshcoreone.android.feature.nodes.deps.NodesFeatureDependencies
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** "Copied" feedback that reverts after [duration]; while shown the copy action is disabled. */
class CopyFeedback(
    private val clock: NodesClock,
    private val scope: CoroutineScope,
    private val duration: Duration = DEFAULT_DURATION,
) {
    private val mutableShowing = MutableStateFlow(false)
    val showing: StateFlow<Boolean> = mutableShowing.asStateFlow()
    private var resetJob: Job? = null

    fun trigger() {
        mutableShowing.value = true
        resetJob?.cancel()
        resetJob = scope.launch {
            clock.sleep(duration)
            mutableShowing.value = false
        }
    }

    companion object {
        val DEFAULT_DURATION: Duration = 2.seconds
    }
}

/**
 * Logic of the share-contact sheet: the URI, its QR request, the share-sheet payload and the
 * copy-public-key action. The clipboard write and QR bitmap stay in the UI layer.
 */
class ContactQrShareStateHolder(
    val contactName: String,
    val publicKey: Bytes,
    val contactType: ContactType,
    dependencies: NodesFeatureDependencies,
    scope: CoroutineScope,
) {
    val contactUri: String = ContactShareContent(dependencies.uriCodec).uri(contactName, publicKey, contactType)
    val qrSpec: ContactQrSpec = ContactQrSpec(contactUri)
    val publicKeyText: String = ContactShareContent.spacedPublicKeyHex(publicKey)
    val copyFeedback = CopyFeedback(dependencies.clock, scope)
    val linkCopyFeedback = CopyFeedback(dependencies.clock, scope)

    fun sharePayload(subject: String): ContactShareTextPayload = ContactShareTextPayload(contactUri, subject)

    /** Returns the text to place on the clipboard and starts the copied feedback. */
    fun copyPublicKey(): String {
        copyFeedback.trigger()
        return ContactShareContent.compactPublicKeyHex(publicKey)
    }
}
