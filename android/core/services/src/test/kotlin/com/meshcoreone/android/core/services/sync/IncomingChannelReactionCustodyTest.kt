// AndroidOnly: WP-214 Concurrent receive ownership across actual journal preimage reconciliation.
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.services.reactions.PendingReaction
import com.meshcoreone.android.core.services.reactions.PendingReactionCustody
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class IncomingChannelReactionCustodyTest {
    @Test fun anOldFinallyCannotReleaseAWorkerAdmittedByJournalReconciliation() = runTest {
        var failWrite = true
        var snapshot = ReactionQueueSnapshot(null, SnapshotList.empty())
        val deletionListeners = mutableListOf<(ReactionQueueRadioDeletion) -> Unit>()
        val journal = object : ReactionQueuePersisting {
            override fun addRadioDeletionListener(listener: (ReactionQueueRadioDeletion) -> Unit) {
                deletionListeners += listener
            }
            override suspend fun fetchReactionQueue() = snapshot
            override suspend fun replaceReactionQueue(expectedVersion: UUID?, next: ReactionQueueSnapshot) {
                assertEquals(snapshot.version, expectedVersion)
                check(!failWrite) { "controlled admission failure before commit" }
                snapshot = next
            }
            override suspend fun resolveReactionTarget(
                expectedVersion: UUID?, version: UUID, occurrence: EntityKey, timestampWindow: ClosedRange<UInt>,
                limit: Long, cachedTarget: () -> ReactionTargetBinding?,
            ): ResolvedReactionQueue = error("This control does not select or mutate a target")
        }
        val native = PendingReactionCustody(journal)
        val receives = IncomingChannelReactionCustody(journal)
        val radio = RadioId(UUID.randomUUID())
        val time = Instant.ofEpochSecond(1_704_067_200)
        val parsed = ParsedReaction(emoji = "\uD83D\uDC4D", targetSender = "Alice", messageHash = "hash")
        val incoming = SyncIncomingChannelReaction(parsed, 3u, "Bob", "original", radio, time,
            "TestNode", DeliveryContext.Live)
        receives.retain(ReceivedChannelReaction(
            ChannelMessage(3u, 0u, 0u, time, "Bob: original", null), null, incoming,
        ))
        val pending = PendingReaction(parsed, 3u, "Bob", "original", radio, time,
            localNodeName = "TestNode", deliveryContext = DeliveryContext.Live,
            occurrenceId = incoming.occurrenceID, verifyMutationOutcomes = true)
        val beforeOldFinally = CompletableDeferred<Unit>()
        val releaseOldWorker = CompletableDeferred<Unit>()
        val first = backgroundScope.launch {
            val receive = assertNotNull(receives.claim(radio))
            try {
                native.queue(pending, UUID.randomUUID(), receives.transfer(receive))
                error("controlled admission must fail")
            } catch (failure: IllegalStateException) {
                assertEquals("controlled admission failure before commit", failure.message)
                beforeOldFinally.complete(Unit)
                releaseOldWorker.await()
            } finally { receives.release(receive) }
        }
        beforeOldFinally.await()
        assertTrue(native.takeRetainedNativeChannel(radio).isEmpty(),
            "the exact unchanged preimage must resolve the handoff as uncommitted")
        val newWorkerOwnsReceive = CompletableDeferred<Unit>()
        val continueNewWorker = CompletableDeferred<Unit>()
        val second = backgroundScope.async {
            val receive = assertNotNull(receives.claim(radio))
            newWorkerOwnsReceive.complete(Unit)
            continueNewWorker.await()
            assertNull(receives.claim(radio), "an old release must not expose the active receive to a third consumer")
            native.queue(pending, UUID.randomUUID(), receives.transfer(receive))
            receives.release(receive)
        }
        newWorkerOwnsReceive.await()
        releaseOldWorker.complete(Unit)
        first.join()
        failWrite = false
        continueNewWorker.complete(Unit)
        second.await()
        assertNull(receives.claim(radio))
        assertEquals(incoming.occurrenceID, native.takeRetainedNativeChannel(radio).single().occurrenceId)
    }
}
