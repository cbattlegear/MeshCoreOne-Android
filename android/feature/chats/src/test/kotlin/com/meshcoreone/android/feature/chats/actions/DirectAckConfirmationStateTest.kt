// AndroidOnly: WP-309 Discriminating snapshot presentation controls, not journal or device evidence.
package com.meshcoreone.android.feature.chats.actions

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.SnapshotSet
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class DirectAckConfirmationStateTest {
    private val radio = RadioId(UUID(309, 1))
    private val otherRadio = RadioId(UUID(309, 2))
    private val token = SessionToken(ProcessEpoch(UUID(309, 3)), Generation(1), radio)
    private val observation = DirectAckConfirmationObservation(radio, token, 1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After fun close() { scope.cancel() }

    private fun source(read: suspend (RadioId) -> DirectAckRadioConfirmationEvidence) =
        object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) =
                flow {
                    emit(DirectAckRadioConfirmationObservation.Inspecting(radioId))
                    emit(DirectAckRadioConfirmationObservation.Snapshot(read(radioId)))
                    awaitCancellation()
                }
        }

    private fun wildcard(id: RadioId = radio) = DirectAckRadioConfirmationEvidence.PersistedWildcard(
        id, SnapshotSet(DirectAckRadioConfirmationWildcardReason.entries),
    )

    @Test fun startsUninspectedNotHealthyAndMissingCapabilityIsExplicit() {
        val holder = DirectAckConfirmationStateHolder(null, scope)
        assertNull(holder.state.value.observation)
        assertSame(DirectAckConfirmationStatus.NotInspected, holder.state.value.status)
        holder.observe(observation)
        assertEquals(observation, holder.state.value.observation)
        assertSame(DirectAckConfirmationStatus.CapabilityUnavailable, holder.state.value.status)
    }

    @Test fun waitingForTheCompleteSnapshotDoesNotInventAbsence() {
        val gate = CompletableDeferred<DirectAckRadioConfirmationEvidence>()
        val holder = DirectAckConfirmationStateHolder(source { assertEquals(radio, it); gate.await() }, scope)
        holder.observe(observation)
        assertSame(DirectAckConfirmationStatus.Checking, holder.state.value.status)
        val evidence = wildcard()
        gate.complete(evidence)
        assertSame(evidence, (holder.state.value.status as DirectAckConfirmationStatus.Inspected).evidence)
        assertEquals(DirectAckRadioConfirmationWildcardReason.entries.toSet(), evidence.reasons)
    }

    @Test fun noWildcardRemainsTheActualSnapshotNotAnEligibilityBoolean() {
        val evidence = DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radio)
        val holder = DirectAckConfirmationStateHolder(source { evidence }, scope)
        holder.observe(observation)
        assertSame(evidence, (holder.state.value.status as DirectAckConfirmationStatus.Inspected).evidence)
        assertEquals(observation, holder.state.value.observation)
    }

    @Test fun typedReadFailureRetainsTheOriginalExceptionAndCanBeRechecked() {
        val cause = IllegalStateException("fixture cause")
        val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("fixture"), cause)
        var fail = true
        val holder = DirectAckConfirmationStateHolder(source {
            if (fail) throw failure
            wildcard()
        }, scope)
        holder.observe(observation)
        val observed = holder.state.value.status as DirectAckConfirmationStatus.ReadUnavailable
        assertSame(failure, observed.failure)
        assertSame(cause, observed.failure.cause)
        fail = false
        holder.observe(observation)
        assertEquals(wildcard(), (holder.state.value.status as DirectAckConfirmationStatus.Inspected).evidence)
    }

    @Test fun wrongRadioSnapshotIsUnavailableNotRetagged() {
        val holder = DirectAckConfirmationStateHolder(source { wildcard(otherRadio) }, scope)
        holder.observe(observation)
        assertEquals(DirectAckConfirmationStatus.RadioMismatch(otherRadio), holder.state.value.status)
        assertEquals(observation, holder.state.value.observation)
    }

    @Test fun recreationRequeriesTheSameRetainedEvidenceRatherThanDefaultingHealthy() {
        var reads = 0
        val evidence = wildcard()
        val observer = source { reads++; evidence }
        val first = DirectAckConfirmationStateHolder(observer, scope)
        first.observe(observation)
        first.invalidate()
        val recreated = DirectAckConfirmationStateHolder(observer, scope)
        assertSame(DirectAckConfirmationStatus.NotInspected, recreated.state.value.status)
        recreated.observe(observation)
        assertEquals(2, reads)
        assertSame(evidence, (recreated.state.value.status as DirectAckConfirmationStatus.Inspected).evidence)
    }

    @Test fun replacedRadioDiscardsEvenANoncancellableOldRead() {
        assertReplacementFenced(DirectAckConfirmationObservation(otherRadio, null, 2))
    }

    @Test fun replacedSessionOnTheSameRadioDiscardsTheOldRead() {
        assertReplacementFenced(observation.copy(sessionToken = token.copy(generation = Generation(2))))
    }

    @Test fun replacedProcessOnTheSameRadioDiscardsTheOldRead() {
        assertReplacementFenced(observation.copy(sessionToken = token.copy(epoch = ProcessEpoch(UUID(309, 4)))))
    }

    @Test fun producerEpochAndSameIdentityRechecksBothDiscardOldReads() {
        assertReplacementFenced(observation.copy(observationEpoch = 2))
        assertReplacementFenced(observation)
    }

    private fun assertReplacementFenced(replacement: DirectAckConfirmationObservation) {
        val old = CompletableDeferred<DirectAckRadioConfirmationEvidence>()
        var calls = 0
        val holder = DirectAckConfirmationStateHolder(source {
            if (calls++ == 0) withContext(NonCancellable) { old.await() }
            else DirectAckRadioConfirmationEvidence.NoPersistedWildcard(it)
        }, scope)
        holder.observe(observation)
        if (replacement == observation) holder.recheck() else holder.observe(replacement)
        val expected = holder.state.value
        assertEquals(replacement, expected.observation)
        assertEquals(
            DirectAckRadioConfirmationEvidence.NoPersistedWildcard(replacement.radioId),
            (expected.status as DirectAckConfirmationStatus.Inspected).evidence,
        )
        old.complete(wildcard())
        assertSame(expected, holder.state.value)
        assertEquals(2, calls)
        holder.invalidate()
    }

    @Test fun invalidationCancelsAndRejectsLateCompletionsWithoutRemovingStoredEvidence() {
        val gate = CompletableDeferred<DirectAckRadioConfirmationEvidence>()
        val holder = DirectAckConfirmationStateHolder(source {
            withContext(NonCancellable) { gate.await() }
        }, scope)
        holder.observe(observation)
        holder.invalidate()
        val invalidated = holder.state.value
        assertEquals(observation, invalidated.observation)
        assertSame(DirectAckConfirmationStatus.Interrupted, invalidated.status)
        gate.complete(wildcard())
        assertSame(invalidated, holder.state.value)
    }

    @Test fun currentReadCancellationStaysInterruptedAndPropagates() {
        val cancelled = CancellationException("fixture cancellation")
        val gate = CompletableDeferred<Unit>()
        var propagated: CancellationException? = null
        val holder = DirectAckConfirmationStateHolder(source {
            gate.await()
            throw cancelled
        }, scope)
        holder.observe(observation)
        val subscription = scope.coroutineContext[Job]!!.children.single()
        subscription.invokeOnCompletion { propagated = it as? CancellationException }
        gate.complete(Unit)
        assertSame(DirectAckConfirmationStatus.Interrupted, holder.state.value.status)
        assertSame(cancelled, propagated)
        assertTrue(scope.isActive)
    }

    @Test fun graphConsumersShareOneSubscriptionAndInspectingDiscardsOldAbsence() {
        val events = Channel<DirectAckRadioConfirmationObservation>(Channel.UNLIMITED)
        var subscriptions = 0
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId): Flow<DirectAckRadioConfirmationObservation> =
                flow {
                    subscriptions++
                    for (event in events) emit(event)
                }
        }
        val holder = DirectAckConfirmationStateHolder(observer, scope)
        holder.observe(observation)
        holder.observe(observation)
        events.trySend(DirectAckRadioConfirmationObservation.Snapshot(
            DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radio),
        )).getOrThrow()
        assertTrue(holder.state.value.status is DirectAckConfirmationStatus.Inspected)
        events.trySend(DirectAckRadioConfirmationObservation.Inspecting(radio)).getOrThrow()
        assertSame(DirectAckConfirmationStatus.Checking, holder.state.value.status)
        assertNull(holder.state.value.lastKnownPersistedWildcard)
        assertEquals(1, subscriptions)
        events.close()
        assertSame(DirectAckConfirmationStatus.StreamEnded, holder.state.value.status)
    }

    @Test fun knownWildcardSurvivesRefreshTypedErrorAndCloseWithoutAHealthyFallback() {
        val gate = CompletableDeferred<Unit>()
        val refreshing = CompletableDeferred<Unit>()
        val failure = PersistenceStoreException(PersistenceStoreError.FetchFailed("closed fixture"))
        val evidence = wildcard()
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(evidence))
                gate.await()
                emit(DirectAckRadioConfirmationObservation.Inspecting(radioId))
                refreshing.complete(Unit)
                awaitCancellation()
            }
        }
        val holder = DirectAckConfirmationStateHolder(observer, scope)
        holder.observe(observation)
        gate.complete(Unit)
        assertTrue(refreshing.isCompleted)
        assertSame(DirectAckConfirmationStatus.Checking, holder.state.value.status)
        assertSame(evidence, holder.state.value.lastKnownPersistedWildcard)
        holder.invalidate()
        assertSame(DirectAckConfirmationStatus.Interrupted, holder.state.value.status)
        assertSame(evidence, holder.state.value.lastKnownPersistedWildcard)

        val failing = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(evidence))
                throw failure
            }
        }
        val failed = DirectAckConfirmationStateHolder(failing, scope)
        failed.observe(observation)
        assertSame(failure, (failed.state.value.status as DirectAckConfirmationStatus.ReadUnavailable).failure)
        assertSame(evidence, failed.state.value.lastKnownPersistedWildcard)
    }

    @Test fun wrongRadioInspectingEventAlsoEndsTheSubscriptionAsUnavailable() {
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Inspecting(otherRadio))
                throw AssertionError("Wrong-radio stream was not stopped")
            }
        }
        val holder = DirectAckConfirmationStateHolder(observer, scope)
        holder.observe(observation)
        assertEquals(DirectAckConfirmationStatus.RadioMismatch(otherRadio), holder.state.value.status)
    }

    @Test fun closedGraphCannotBeRestartedByAStaleScreenRecheckOrACancelledOwner() {
        var reads = 0
        val holder = DirectAckConfirmationStateHolder(source { reads++; wildcard(it) }, scope)
        holder.observe(observation)
        holder.invalidate()
        holder.recheck()
        assertEquals(1, reads)
        assertFalse(holder.state.value.isActive)
        assertSame(DirectAckConfirmationStatus.Interrupted, holder.state.value.status)
        assertEquals(wildcard(), holder.state.value.lastKnownPersistedWildcard)
        scope.cancel()
        holder.observe(observation)
        assertEquals(1, reads)
        assertFalse(holder.state.value.isActive)
        assertSame(DirectAckConfirmationStatus.Interrupted, holder.state.value.status)
    }

    @Test fun unexpectedFailureAfterAbsenceBecomesUnavailableAndEscapesWithTheOriginalCause() {
        val gate = CompletableDeferred<Unit>()
        val unexpected = IllegalStateException("unexpected fixture observer")
        val reported = mutableListOf<Throwable>()
        val reportingScope = CoroutineScope(scope.coroutineContext + CoroutineExceptionHandler { _, cause ->
            reported += cause
        })
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(
                    DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId),
                ))
                gate.await()
                throw unexpected
            }
        }
        val holder = DirectAckConfirmationStateHolder(observer, reportingScope)
        holder.observe(observation)
        assertTrue(holder.state.value.status is DirectAckConfirmationStatus.Inspected)
        gate.complete(Unit)
        assertSame(DirectAckConfirmationStatus.UnexpectedUnavailable, holder.state.value.status)
        assertNull(holder.state.value.lastKnownPersistedWildcard)
        assertEquals(1, reported.size)
        assertSame(unexpected, reported.single())
    }

    @Test fun unexpectedFailureRetainsKnownWildcardButAReplacedOwnersFailureCannotPublish() {
        val oldFailure = CompletableDeferred<Unit>()
        val unexpected = IllegalArgumentException("old observer fixture")
        val reported = mutableListOf<Throwable>()
        val reportingScope = CoroutineScope(scope.coroutineContext + CoroutineExceptionHandler { _, cause ->
            reported += cause
        })
        var collections = 0
        val observer = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                if (collections++ == 0) {
                    emit(DirectAckRadioConfirmationObservation.Snapshot(wildcard(radioId)))
                    withContext(NonCancellable) {
                        oldFailure.await()
                        throw unexpected
                    }
                } else {
                    emit(DirectAckRadioConfirmationObservation.Snapshot(
                        DirectAckRadioConfirmationEvidence.NoPersistedWildcard(radioId),
                    ))
                    awaitCancellation()
                }
            }
        }
        val holder = DirectAckConfirmationStateHolder(observer, reportingScope)
        holder.observe(observation)
        assertEquals(wildcard(), holder.state.value.lastKnownPersistedWildcard)
        val replacement = DirectAckConfirmationObservation(otherRadio, null, 2)
        holder.observe(replacement)
        val current = holder.state.value
        oldFailure.complete(Unit)
        assertSame(current, holder.state.value)
        assertNull(current.lastKnownPersistedWildcard)
        assertEquals(replacement, current.observation)
        assertEquals(1, reported.size)
        assertSame(unexpected, reported.single())

        val failNow = object : DirectAckRadioConfirmationEvidenceObserving {
            override fun observeDirectAckRadioConfirmationEvidence(radioId: RadioId) = flow {
                emit(DirectAckRadioConfirmationObservation.Snapshot(wildcard(radioId)))
                throw unexpected
            }
        }
        val failed = DirectAckConfirmationStateHolder(failNow, reportingScope)
        failed.observe(observation)
        assertSame(DirectAckConfirmationStatus.UnexpectedUnavailable, failed.state.value.status)
        assertEquals(wildcard(), failed.state.value.lastKnownPersistedWildcard)
        assertEquals(2, reported.size)
        assertSame(unexpected, reported.last())
    }

    @Test fun observationCannotPairOneRadioWithAnotherRadiosSessionOrANegativeEpoch() {
        assertThrows(IllegalArgumentException::class.java) {
            observation.copy(radioId = otherRadio)
        }
        assertThrows(IllegalArgumentException::class.java) {
            observation.copy(observationEpoch = -1)
        }
    }
}
