// AndroidOnly: WP-309 Real Room diagnostic consumer controls; requires the separately composed storage producer.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.actions.DirectAckConfirmationObservation
import com.meshcoreone.android.feature.chats.actions.DirectMessageConfirmationAggregate
import com.meshcoreone.android.feature.chats.actions.DirectMessageConfirmationReadStatus
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

/**
 * No interface delegation, synthetic identity, fake wire, raw SQL or reconstructed claim.
 * A second store handle is a consumer-reconstruction control, NOT a database/process restart.
 */
class DirectMessageConfirmationPersistenceIntegrationTest : RoomProcessTest() {
    private val radio = RadioId(UUID(309, 120))
    private val messageId = UUID(309, 121)

    private fun inspector(store: PersistenceStoreProtocol): DirectMessageConfirmationEvidenceInspecting =
        requireNotNull(store as? DirectMessageConfirmationEvidenceInspecting) {
            "The exact candidate must compose the canonical durable message evidence producer"
        }

    private suspend fun seedFailure(): EntityKey {
        val contact = ContactDTO(
            radioId = radio, publicKey = Bytes(ByteArray(32) { 12 }), name = "Fixture",
            lastHeardTimestamp = null,
        )
        store.saveContact(contact)
        store.saveMessage(MessageDTO(
            id = messageId, radioId = radio, contactID = contact.id, text = "Retained failure",
            timestamp = 1_700_000_000u, createdAt = Instant.ofEpochSecond(1_700_000_000),
            status = MessageStatus.FAILED,
        ))
        return EntityKey(radio, messageId)
    }

    @Test fun actualDurableFailureEvidenceSurvivesConsumerAndStoreHandleReconstructionWithoutCallingItUnsent() = runTest {
        val key = seedFailure()
        val first = inspector(store).inspectDirectMessageConfirmationEvidence(key)
            as DirectMessageConfirmationEvidence.Present
        assertEquals(MessageStatus.FAILED, first.status)
        assertTrue(first.reasons.contains(DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN))
        assertTrue(first.reasons.contains(DirectMessageConfirmationUncertaintyReason.PREAUTHORITY_HISTORY_UNKNOWN))
        val secondOwner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val second = RoomPersistenceStore(db, secondOwner)
        val aggregate = DirectMessageConfirmationAggregate(inspector(second), backgroundScope)
        try {
            val after = inspector(second).inspectDirectMessageConfirmationEvidence(key)
                as DirectMessageConfirmationEvidence.Present
            assertEquals(first.identity, after.identity)
            assertEquals(first.status, after.status)
            assertEquals(first.reasons, after.reasons)
            aggregate.register(this@DirectMessageConfirmationPersistenceIntegrationTest, setOf(key))
            aggregate.observe(DirectAckConfirmationObservation(radio, null, 1))
            val observed = withTimeout(10_000) {
                aggregate.state.first { it.status == DirectMessageConfirmationReadStatus.HistoricalRead }
            }
            assertTrue(observed.hasHistoricalUncertainty)
            assertTrue(observed.rowDiagnosticsUnavailable)
            assertEquals(MessageStatus.FAILED, second.fetchMessage(key)?.status)
        } finally {
            aggregate.invalidate()
            try { second.close() } finally { secondOwner.cancel() }
        }
    }

    @Test fun actualIdenticalRowReplacementChangesPersistedIdentityDespiteEveryDtoFieldBeingEqual() = runTest {
        val key = seedFailure()
        val source = inspector(store)
        val before = source.inspectDirectMessageConfirmationEvidence(key)
            as DirectMessageConfirmationEvidence.Present
        val original = requireNotNull(store.fetchMessage(key))
        store.deleteMessage(key)
        assertEquals(DirectMessageConfirmationEvidence.Missing(key),
            source.inspectDirectMessageConfirmationEvidence(key))
        store.saveMessage(original.copy())
        val replacement = source.inspectDirectMessageConfirmationEvidence(key)
            as DirectMessageConfirmationEvidence.Present
        assertEquals(original, store.fetchMessage(key))
        assertNotEquals(before.identity.incarnationId, replacement.identity.incarnationId)
        assertTrue(replacement.reasons.contains(
            DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN,
        ))
        assertTrue(replacement.reasons.contains(
            DirectMessageConfirmationUncertaintyReason.PREAUTHORITY_HISTORY_UNKNOWN,
        ))
        assertEquals(MessageStatus.FAILED, replacement.status)
    }

    @Test fun actualRadioObserverRetainsGlobalUnknownAfterAllMessageRowsAreDeletedAndConsumerIsRecreated() = runTest {
        val key = seedFailure()
        store.deleteMessage(key)
        val secondOwner = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val second = RoomPersistenceStore(db, secondOwner)
        try {
            val observer = requireNotNull((second as PersistenceStoreProtocol)
                as? DirectAckRadioConfirmationEvidenceObserving) {
                "The exact candidate must compose the canonical radio evidence observer"
            }
            for (requestedRadio in listOf(radio, RadioId(UUID(309, 122)))) {
                val snapshot = withTimeout(10_000) {
                    observer.observeDirectAckRadioConfirmationEvidence(requestedRadio)
                        .first { it is DirectAckRadioConfirmationObservation.Snapshot }
                } as DirectAckRadioConfirmationObservation.Snapshot
                val evidence = snapshot.evidence as DirectAckRadioConfirmationEvidence.PersistedWildcard
                assertEquals(requestedRadio, evidence.radioId)
                assertTrue(evidence.reasons.contains(
                    DirectAckRadioConfirmationWildcardReason.PREAUTHORITY_HISTORY_UNKNOWN,
                ))
            }
            assertNull(second.fetchMessage(key))
        } finally {
            try { second.close() } finally { secondOwner.cancel() }
        }
    }
}
