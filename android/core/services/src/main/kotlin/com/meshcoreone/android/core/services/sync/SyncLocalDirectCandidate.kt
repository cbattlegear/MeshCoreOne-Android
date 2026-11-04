// AndroidOnly: WP-214 Opt-in test composition for local-only direct receive attribution.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.LocalDirectContactResolution
import com.meshcoreone.android.core.contracts.domain.LocalDirectMessagePolling
import com.meshcoreone.android.core.contracts.domain.SessionToken

/**
 * Installs a nonactivated test consumer. It saves occurrences through the producer's admission
 * but does not publish contact-attributed side effects or assert RF sender identity.
 */
suspend fun SyncCoordinator.wireLocalDirectCandidateForTest(
    dependencies: SyncDependencies,
    session: SessionToken,
) {
    val polling = checkNotNull(dependencies.messagePollingService as? LocalDirectMessagePolling) {
        "Local direct candidate composition requires occurrence-aware polling"
    }
    val adverts = checkNotNull(dependencies.advertisementService as? SyncLocalDirectAdvertisementServicing) {
        "Local direct candidate composition requires pending-advert admission"
    }
    val selfNodeName = dependencies.dataStore.fetchDevice(session.radioId)?.nodeName ?: ""
    polling.setLocalDirectMessageHandler { envelope ->
        require(envelope.session == session) { "Local direct delivery belongs to another session" }
        val prefix = envelope.message.senderPublicKeyPrefix
        val observed = if (envelope.resolution == LocalDirectContactResolution.NotFound) {
            adverts.materializeLocalDirectContactForPendingAdvert(prefix, session.radioId)
        } else envelope.resolution
        adverts.withPendingAdvertContactOperation(session.radioId, prefix, observed) { admission ->
            val contact = (admission.resolution as? LocalDirectContactResolution.Unique)?.contact
            handleIncomingMessage(
                IncomingMessageKind.Direct(envelope.message, contact),
                envelope.context,
                dependencies,
                session.radioId,
                selfNodeName,
                LocalDirectCandidateReceipt(envelope.occurrenceID, admission),
            )
        }
    }
}
