// AndroidOnly: WP-309 Historical message diagnostics never become row incarnation or delivery authority.
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DirectMessageConfirmationAggregateTest {
    private val radio = RadioId(UUID(309, 70))
    private val otherRadio = RadioId(UUID(309, 71))
    private val key = EntityKey(radio, UUID(309, 72))
    private val owner = Any()
    private fun observation(radioId: RadioId = radio, epoch: Long = 1) =
        DirectAckConfirmationObservation(radioId, null, epoch)
    private fun present(key: EntityKey = this.key) = DirectMessageConfirmationEvidence.Present(
        key, DirectMessageConfirmationIdentity("a".repeat(64), 0), MessageStatus.FAILED,
        SnapshotSet(listOf(
            DirectMessageConfirmationUncertaintyReason.TARGET_PROVENANCE_UNPROVEN,
            DirectMessageConfirmationUncertaintyReason.KNOWN_OTHER_CLAIM_CODE,
        )),
    )
    private fun inspector(read: suspend (EntityKey) -> DirectMessageConfirmationEvidence) =
        object : DirectMessageConfirmationEvidenceInspecting {
            override suspend fun inspectDirectMessageConfirmationEvidence(key: EntityKey) = read(key)
        }

    @Test fun failedWordingIsConservativeAcrossEveryDirectionChannelAndStatusWithoutMutatingTheRow() {
        for (contact in listOf<UUID?>(null, UUID(309, 73)))
        for (direction in MessageDirection.entries) for (channel in listOf<UByte?>(null, 3u)) {
            for (status in MessageStatus.entries) {
                val message = MessageDTO(
                    id = key.id, radioId = radio, contactID = contact, channelIndex = channel,
                    text = "fixture", timestamp = 1_700_000_000u, createdAt = Instant.EPOCH,
                    direction = direction, status = status,
                )
                val before = message.copy()
                assertEquals(
                    direction == MessageDirection.OUTGOING && (channel == null || contact != null) &&
                        status == MessageStatus.FAILED,
                    requiresUnconfirmedDirectFailureLabel(message),
                )
                assertEquals(before, message)
            }
        }
    }

    @Test fun missingCapabilityIsUnavailableAndNotAnEmptyOrHealthyEvidenceRead() = runTest {
        val aggregate = DirectMessageConfirmationAggregate(null, backgroundScope)
        aggregate.register(owner, setOf(key))
        aggregate.observe(observation())
        assertEquals(DirectMessageConfirmationReadStatus.CapabilityUnavailable, aggregate.state.value.status)
        assertTrue(aggregate.state.value.rowDiagnosticsUnavailable)
        assertFalse(aggregate.state.value.hasHistoricalUncertainty)
    }

    @Test fun knownCodeCollisionIsOnlyHistoricalAggregateWhileRowBindingAlwaysRemainsUnavailable() = runTest {
        val keys = mutableListOf<EntityKey>()
        val aggregate = DirectMessageConfirmationAggregate(inspector { requested ->
            keys += requested
            present(requested)
        }, backgroundScope)
        aggregate.register(owner, setOf(key, EntityKey(otherRadio, key.id)))
        aggregate.observe(observation())
        runCurrent()
        assertEquals(listOf(key), keys)
        assertTrue(aggregate.state.value.hasHistoricalUncertainty)
        assertTrue(aggregate.state.value.rowDiagnosticsUnavailable)
        assertEquals(DirectMessageConfirmationReadStatus.HistoricalRead, aggregate.state.value.status)
        advanceTimeBy(999)
        runCurrent()
        assertEquals(1, keys.size)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(key, key), keys)
        aggregate.invalidate()
    }

    @Test fun missingAndNotEligibleDoNotEstablishBirthProofOrClearKnownHistory() = runTest {
        var result: DirectMessageConfirmationEvidence = present()
        val aggregate = DirectMessageConfirmationAggregate(inspector { result }, backgroundScope)
        aggregate.register(owner, setOf(key))
        aggregate.observe(observation())
        runCurrent()
        for (next in listOf(
            DirectMessageConfirmationEvidence.Missing(key), DirectMessageConfirmationEvidence.NotEligible(key),
        )) {
            result = next
            aggregate.recheck()
            runCurrent()
            assertTrue(aggregate.state.value.hasHistoricalUncertainty)
            assertTrue(aggregate.state.value.rowDiagnosticsUnavailable)
        }
        aggregate.invalidate()
    }

    @Test fun typedFailureRetainsOriginalCauseAndSameRadioHistoricalUncertaintyAcrossDisconnect() = runTest {
        val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("fixture"))
        var fail = false
        val aggregate = DirectMessageConfirmationAggregate(inspector {
            if (fail) throw failure else present()
        }, backgroundScope)
        aggregate.register(owner, setOf(key))
        aggregate.observe(observation())
        runCurrent()
        fail = true
        aggregate.observe(null)
        aggregate.observe(observation(epoch = 2))
        runCurrent()
        assertSame(failure, (aggregate.state.value.status as DirectMessageConfirmationReadStatus.ReadUnavailable).cause)
        assertTrue(aggregate.state.value.hasHistoricalUncertainty)
        assertTrue(aggregate.state.value.rowDiagnosticsUnavailable)
        aggregate.invalidate()
    }

    @Test fun lateNoncancellableOtherRadioReadCannotDecorateCurrentGraphOrSupplyItsAggregate() = runTest {
        val oldRead = CompletableDeferred<Unit>()
        val aggregate = DirectMessageConfirmationAggregate(inspector { requested ->
            if (requested.radioId == radio) withContext(NonCancellable) {
                oldRead.await()
                present(requested)
            } else DirectMessageConfirmationEvidence.Missing(requested)
        }, backgroundScope)
        aggregate.register(owner, setOf(key, EntityKey(otherRadio, key.id)))
        aggregate.observe(observation())
        runCurrent()
        val replacement = observation(otherRadio, 2)
        aggregate.observe(replacement)
        runCurrent()
        oldRead.complete(Unit)
        runCurrent()
        assertEquals(replacement, aggregate.state.value.observation)
        assertFalse(aggregate.state.value.hasHistoricalUncertainty)
        assertTrue(aggregate.state.value.rowDiagnosticsUnavailable)
        aggregate.invalidate()
    }

    @Test fun unexpectedFailureIsUnavailableEscapesUnchangedAndDoesNotCancelSiblingWork() = runTest {
        val failure = IllegalStateException("fixture")
        val reported = mutableListOf<Throwable>()
        val job = SupervisorJob(backgroundScope.coroutineContext[Job])
        val scope = CoroutineScope(backgroundScope.coroutineContext + job +
            CoroutineExceptionHandler { _, cause -> reported += cause })
        val aggregate = DirectMessageConfirmationAggregate(inspector { throw failure }, scope)
        aggregate.register(owner, setOf(key))
        aggregate.observe(observation())
        runCurrent()
        assertEquals(DirectMessageConfirmationReadStatus.UnexpectedUnavailable, aggregate.state.value.status)
        assertSame(failure, reported.single())
        var sibling = false
        scope.launch { sibling = true }
        runCurrent()
        assertTrue(sibling)
        scope.cancel()
    }

    @Test fun repeatedReplacementAndRecheckCannotOverlapANoncancellableOldRead() = runTest {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        var active = 0
        var maximum = 0
        val aggregate = DirectMessageConfirmationAggregate(inspector { requested ->
            calls++
            active++
            maximum = maxOf(maximum, active)
            try {
                if (calls == 1) withContext(NonCancellable) { gate.await() }
                DirectMessageConfirmationEvidence.Missing(requested)
            } finally { active-- }
        }, backgroundScope)
        aggregate.register(owner, setOf(key))
        aggregate.observe(observation())
        runCurrent()
        repeat(3) { epoch ->
            aggregate.observe(observation(epoch = epoch.toLong() + 2))
            runCurrent()
            aggregate.recheck()
            runCurrent()
        }
        assertEquals(1, calls)
        assertEquals(1, active)
        assertEquals(DirectMessageConfirmationReadStatus.Checking, aggregate.state.value.status)
        gate.complete(Unit)
        runCurrent()
        assertEquals(2, calls)
        assertEquals(1, maximum)
        assertEquals(0, active)
        assertFalse(aggregate.state.value.hasHistoricalUncertainty)
        assertEquals(DirectMessageConfirmationReadStatus.HistoricalRead, aggregate.state.value.status)
        aggregate.invalidate()
    }

    @Test fun severalMountedConsumersShareOneSerialKeyReadAndReleaseOnlyTheirOwnRegistration() = runTest {
        val gate = CompletableDeferred<Unit>()
        var count = 0
        var active = 0
        var maximum = 0
        val aggregate = DirectMessageConfirmationAggregate(inspector {
            count++
            active++
            maximum = maxOf(maximum, active)
            try { gate.await(); present() } finally { active-- }
        }, backgroundScope)
        val secondOwner = Any()
        aggregate.register(owner, setOf(key))
        aggregate.register(secondOwner, setOf(key))
        aggregate.observe(observation())
        runCurrent()
        assertEquals(1, count)
        aggregate.unregister(owner)
        runCurrent()
        assertEquals(1, count)
        assertEquals(1, active)
        gate.complete(Unit)
        runCurrent()
        assertEquals(1, maximum)
        aggregate.unregister(secondOwner)
        runCurrent()
        assertEquals(0, active)
        assertEquals(DirectMessageConfirmationReadStatus.NoMessages, aggregate.state.value.status)
    }
}
