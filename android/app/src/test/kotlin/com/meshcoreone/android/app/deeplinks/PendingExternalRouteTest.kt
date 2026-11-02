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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class PendingExternalRouteTest {
    @Test
    @SourceCases("PendingExternalURLTests::A URL submitted before ready is held, not routed, until markReady()")
    fun coldStartHoldsUntilReady() = runTest {
        val routed = mutableListOf<String>()
        val pending = PendingExternalRoute { routed += it; DeepLinkRouteOutcome.Navigated }
        assertIs<PendingExternalRoute.SubmitOutcome.Staged>(pending.submit(PendingExternalRoute.Delivery("1", "meshcore://map?lat=1&lon=2")))
        assertNotNull(pending.snapshot())
        assertEquals(emptyList(), routed)
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.markReady())
        assertEquals(listOf("meshcore://map?lat=1&lon=2"), routed)
        assertNull(pending.snapshot())
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
        gate.complete(Unit)
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry())
        assertNull(pending.snapshot())
        assertEquals(2, calls)
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
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry())
        assertEquals(2, calls)
        assertNull(pending.snapshot())
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
}
