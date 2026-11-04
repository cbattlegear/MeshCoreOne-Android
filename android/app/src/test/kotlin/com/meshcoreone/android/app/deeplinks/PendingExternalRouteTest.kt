// PortedFrom: MC1Tests/State/PendingExternalURLTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class PendingExternalRouteTest {
    @Test
    fun computedFailureSurvivesSubmitterCancellationBeforeAwaitConsumption() = runTest {
        val failure = IllegalStateException("store unavailable")
        lateinit var submission: Job
        var fail = true
        var calls = 0
        val pending = PendingExternalRoute {
            calls++
            if (fail) {
                val computed = DeepLinkRouteOutcome.ContactLookupFailed(failure)
                submission.cancel()
                computed
            } else DeepLinkRouteOutcome.Navigated
        }
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("A", "contact-A")
        submission = launch(start = CoroutineStart.LAZY) { pending.submit(delivery) }
        submission.start()
        submission.join()
        assertTrue(submission.isCancelled)
        val observed = assertNotNull(pending.lookupFailure.value)
        assertEquals("A", observed.deliveryId)
        assertEquals(failure, observed.cause)
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        fail = false
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry(observed.deliveryId, observed.attempt))
        assertNull(pending.lookupFailure.value)
        assertNull(pending.snapshot())
        assertEquals(setOf("A"), pending.consumedDeliveryIds())
        assertEquals(2, calls)
    }

    @Test
    fun lookupFailureCanRetryOnceWithItsExactObservedAttempt() = runTest {
        var fail = true
        var calls = 0
        val error = IllegalStateException("store unavailable")
        val pending = PendingExternalRoute {
            calls++
            if (fail) DeepLinkRouteOutcome.ContactLookupFailed(error) else DeepLinkRouteOutcome.Navigated
        }
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("A", "contact-A")
        assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.submit(delivery))
        val observed = assertNotNull(pending.lookupFailure.value)
        assertEquals(delivery.id, observed.deliveryId)
        assertEquals(error, observed.cause)
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        fail = false
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry(observed.deliveryId, observed.attempt))
        assertNull(pending.lookupFailure.value)
        assertNull(pending.snapshot())
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(pending.retry(observed.deliveryId, observed.attempt))
        assertEquals(2, calls)
        assertEquals(setOf("A"), pending.consumedDeliveryIds())
    }

    @Test
    fun staleFailureActionsCannotRetryOrCancelAReplacementDelivery() = runTest {
        val calls = mutableListOf<String>()
        val pending = PendingExternalRoute {
            calls += it
            DeepLinkRouteOutcome.ContactLookupFailed(IllegalStateException("store unavailable"))
        }
        pending.markReady()
        pending.submit(PendingExternalRoute.Delivery("A", "contact-A"))
        val a = assertNotNull(pending.lookupFailure.value)
        val bDelivery = PendingExternalRoute.Delivery("B", "contact-B")
        pending.submit(bDelivery)
        val b = assertNotNull(pending.lookupFailure.value)
        assertEquals("B", b.deliveryId)
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(pending.retry(a.deliveryId, a.attempt))
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(pending.cancel(a.deliveryId, a.attempt))
        assertEquals(b, pending.lookupFailure.value)
        assertEquals(bDelivery, pending.snapshot())
        assertEquals(listOf("contact-A", "contact-B"), calls)
        assertEquals(setOf("A"), pending.consumedDeliveryIds())
        assertIs<PendingExternalRoute.SubmitOutcome.Cancelled>(pending.cancel(b.deliveryId, b.attempt))
        assertNull(pending.lookupFailure.value)
        assertNull(pending.snapshot())
        assertEquals(setOf("A", "B"), pending.consumedDeliveryIds())
    }

    @Test
    fun oldAttemptActionsCannotClaimANewerFailureOfTheSameDelivery() = runTest {
        var calls = 0
        val pending = PendingExternalRoute {
            calls++
            DeepLinkRouteOutcome.ContactLookupFailed(IllegalStateException("store unavailable"))
        }
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("A", "contact-A")
        pending.submit(delivery)
        val first = assertNotNull(pending.lookupFailure.value)
        assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(
            pending.retry(first.deliveryId, first.attempt),
        )
        val second = assertNotNull(pending.lookupFailure.value)
        assertTrue(second.attempt > first.attempt)
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(pending.cancel(first.deliveryId, first.attempt))
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(pending.retry(first.deliveryId, first.attempt))
        assertEquals(second, pending.lookupFailure.value)
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        assertEquals(2, calls)
    }

    @Test
    fun lateFailureOfSupersededAttemptCannotReplaceCurrentFailure() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = PendingExternalRoute { uri ->
            if (uri == "contact-A") {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
            }
            DeepLinkRouteOutcome.ContactLookupFailed(IllegalStateException("store unavailable"))
        }
        pending.markReady()
        val a = async { pending.submit(PendingExternalRoute.Delivery("A", "contact-A")) }
        started.await()
        val bDelivery = PendingExternalRoute.Delivery("B", "contact-B")
        try {
            assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.submit(bDelivery))
            val b = assertNotNull(pending.lookupFailure.value)
            release.complete(Unit)
            assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(a.await())
            assertEquals(b, pending.lookupFailure.value)
            assertEquals(bDelivery, pending.snapshot())
            assertEquals(setOf("A"), pending.consumedDeliveryIds())
        } finally { release.complete(Unit) }
    }

    @Test
    @SourceCases("PendingExternalURLTests::A URL submitted before ready is held, not routed, until markReady()")
    fun coldStartHoldsUntilReady() = runTest {
        val routed = mutableListOf<String>()
        val pending = PendingExternalRoute { routed += it; DeepLinkRouteOutcome.Navigated }
        assertIs<PendingExternalRoute.SubmitOutcome.Staged>(pending.submit(PendingExternalRoute.Delivery("1", "meshcore://map?lat=1&lon=2")))
        assertNotNull(pending.snapshot())
        assertEquals(emptyList(), routed)
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.markReady())
        assertEquals(listOf("meshcore://map?lat=1&lon=2"), routed)
        assertNull(pending.snapshot())
        assertEquals(setOf("1"), pending.consumedDeliveryIds())
    }

    @Test
    @SourceCases("PendingExternalURLTests::markReady routes a held URL exactly once()")
    fun repeatedReadinessAndDuplicateDeliveryRouteOnce() = runTest {
        var count = 0
        val pending = PendingExternalRoute { count++; DeepLinkRouteOutcome.Navigated }
        val delivery = PendingExternalRoute.Delivery("launch", "meshcore://map?lat=1&lon=2")
        pending.submit(delivery)
        pending.markReady()
        pending.markReady()
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
        assertEquals(1, count)
    }

    @Test
    @SourceCases("PendingExternalURLTests::A URL submitted after ready routes immediately()")
    fun warmDeliveryRoutesImmediately() = runTest {
        val routed = mutableListOf<String>()
        val pending = PendingExternalRoute { routed += it; DeepLinkRouteOutcome.Navigated }
        pending.markReady()
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
            pending.submit(PendingExternalRoute.Delivery("warm", "meshcoreone://hashtag/general")),
        )
        assertEquals(1, routed.size)
    }

    @Test
    fun cancellationRetainsThePendingDeliveryForOneRetry() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val pending = PendingExternalRoute {
            calls++
            started.complete(Unit)
            gate.await()
            DeepLinkRouteOutcome.Navigated
        }
        pending.markReady()
        val job = launch { pending.submit(PendingExternalRoute.Delivery("cancel", "meshcore://contact/add")) }
        started.await()
        job.cancelAndJoin()
        assertNotNull(pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty(), "Precommit cancellation must remain retryable")
        gate.complete(Unit)
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry())
        assertNull(pending.snapshot())
        assertEquals(2, calls)
        assertEquals(setOf("cancel"), pending.consumedDeliveryIds())
    }

    @Test
    fun latestColdDeliveryReplacesAnOlderPendingRoute() = runTest {
        val routed = mutableListOf<String>()
        val pending = PendingExternalRoute { routed += it; DeepLinkRouteOutcome.Navigated }
        pending.submit(PendingExternalRoute.Delivery("1", "meshcore://map?lat=1&lon=2"))
        pending.submit(PendingExternalRoute.Delivery("2", "meshcoreone://hashtag/general"))
        pending.markReady()
        assertEquals(listOf("meshcoreone://hashtag/general"), routed)
    }

    @Test
    fun failedRouteRetainsItsDeliveryAndReleasesTheSlotForRetry() = runTest {
        var calls = 0
        val failure = IllegalStateException("lookup failed")
        val pending = PendingExternalRoute {
            if (++calls == 1) throw failure
            DeepLinkRouteOutcome.Navigated
        }
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("failure", "meshcore://map?lat=1&lon=2")
        val caught = assertFailsWith<IllegalStateException> { pending.submit(delivery) }
        assertEquals(failure.message, caught.message)
        assertTrue(generateSequence<Throwable>(caught) { it.cause }.any { it === failure })
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry())
        assertEquals(2, calls)
        assertNull(pending.snapshot())
        assertEquals(setOf("failure"), pending.consumedDeliveryIds())
    }

    @Test
    fun terminalSnapshotDistinguishesIdenticalReplacementIdsWithoutClaimingRouteSuccess() = runTest {
        val routed = mutableListOf<String>()
        val pending = PendingExternalRoute { routed += it; DeepLinkRouteOutcome.Rejected }
        val uri = "meshcore://contact/add"
        pending.submit(PendingExternalRoute.Delivery("old", uri))
        val before = pending.consumedDeliveryIds()
        pending.submit(PendingExternalRoute.Delivery("replacement", uri))
        val superseded = pending.consumedDeliveryIds()
        assertEquals(setOf("old"), superseded)
        assertEquals("replacement", pending.snapshot()?.id)
        assertTrue(routed.isEmpty(), "A superseded ID can be terminal without ever routing")
        val result = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.markReady())
        assertEquals(DeepLinkRouteOutcome.Rejected, result.outcome)
        assertEquals(setOf("old", "replacement"), pending.consumedDeliveryIds())
        assertTrue(before.isEmpty(), "Earlier snapshots must not observe later mutations")
        assertEquals(setOf("old"), superseded)
        assertEquals(listOf(uri), routed)
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(
            pending.submit(PendingExternalRoute.Delivery("old", uri)),
        )
    }

    @Test
    fun terminalSnapshotRetainsCommittedIdWhenCallerCancellationPreventsRoutedOutcome() = runTest {
        val committed = CompletableDeferred<Unit>()
        val pending = PendingExternalRoute {
            val guard = assertNotNull(currentCoroutineContext()[PendingExternalRoute.CommitGuard])
            guard.commitOutcome { DeepLinkRouteOutcome.ConfirmationStaged }
            committed.complete(Unit)
            awaitCancellation()
        }
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("committed", "meshcore://contact/add")
        val caller = launch { pending.submit(delivery) }
        committed.await()
        assertTrue(pending.consumedDeliveryIds().isEmpty(), "The drain has not completed cleanup yet")
        caller.cancelAndJoin()
        assertTrue(caller.isCancelled)
        assertNull(pending.snapshot())
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
    }

    @Test
    fun terminalSnapshotKeepsActualInflightAndReplacementIdsSeparate() = runTest {
        val started = CompletableDeferred<Unit>()
        var calls = 0
        val pending = PendingExternalRoute {
            if (++calls == 1) {
                started.complete(Unit)
                awaitCancellation()
            }
            DeepLinkRouteOutcome.ConfirmationStaged
        }
        pending.markReady()
        val uri = "meshcore://contact/add"
        val first = async { pending.submit(PendingExternalRoute.Delivery("first", uri)) }
        started.await()
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
            pending.submit(PendingExternalRoute.Delivery("second", uri)),
        )
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
        assertEquals(setOf("first", "second"), pending.consumedDeliveryIds())
        assertEquals(2, calls)
    }

    @Test
    fun terminalSnapshotIsBoundedAndDetachedFromTheMutableHistory() = runTest {
        val pending = PendingExternalRoute { DeepLinkRouteOutcome.Navigated }
        repeat(65) { pending.submit(PendingExternalRoute.Delivery("id-$it", "same-uri")) }
        val retained = pending.consumedDeliveryIds()
        assertEquals((0 until 64).map { "id-$it" }.toSet(), retained)
        pending.submit(PendingExternalRoute.Delivery("id-65", "same-uri"))
        assertEquals((1..64).map { "id-$it" }.toSet(), pending.consumedDeliveryIds())
        assertEquals((0 until 64).map { "id-$it" }.toSet(), retained)
        assertEquals("id-65", pending.snapshot()?.id, "Current pending ID is not yet terminal")
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(
            pending.submit(PendingExternalRoute.Delivery("id-64", "same-uri")),
        )
    }

    @Test
    fun latestReplacementDrainsEvenWhenAnIntermediateRouterNeverStarted() = runTest {
        val started = CompletableDeferred<Unit>()
        val routed = mutableListOf<String>()
        val pending = PendingExternalRoute {
            routed += it
            if (it == "A") {
                started.complete(Unit)
                awaitCancellation()
            }
            DeepLinkRouteOutcome.Navigated
        }
        pending.markReady()
        val first = async { pending.submit(PendingExternalRoute.Delivery("A", "A")) }
        started.await()
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            pending.submit(PendingExternalRoute.Delivery("B", "B"))
        }
        val third = async(start = CoroutineStart.UNDISPATCHED) {
            pending.submit(PendingExternalRoute.Delivery("C", "C"))
        }
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(second.await())
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(third.await())
        assertEquals(listOf("A", "C"), routed)
        assertNull(pending.snapshot())
    }

    @Test
    fun queuedSubmissionCannotBorrowAnotherCallersRoutingChild() = runTest {
        val submissions = this
        val started = CompletableDeferred<Unit>()
        val routed = mutableListOf<String>()
        var replacement: Deferred<PendingExternalRoute.SubmitOutcome>? = null
        lateinit var pending: PendingExternalRoute
        pending = PendingExternalRoute { uri ->
            if (uri == "initial") {
                suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation {
                        // Queue B while A still holds the staging mutex during initial cancellation.
                        replacement = submissions.async(CoroutineName("B"), start = CoroutineStart.UNDISPATCHED) {
                            pending.submit(PendingExternalRoute.Delivery("B", "B"))
                        }
                    }
                    started.complete(Unit)
                }
            } else {
                assertEquals(uri, currentCoroutineContext()[CoroutineName]?.name)
                routed += uri
                DeepLinkRouteOutcome.Navigated
            }
        }
        pending.markReady()
        val initial = async { pending.submit(PendingExternalRoute.Delivery("initial", "initial")) }
        started.await()
        val first = async(CoroutineName("A"), start = CoroutineStart.UNDISPATCHED) {
            pending.submit(PendingExternalRoute.Delivery("A", "A"))
        }
        val second = assertNotNull(replacement)
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(initial.await())
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(second.await())
        assertEquals(listOf("B"), routed)
        assertNull(pending.snapshot())
    }
}
