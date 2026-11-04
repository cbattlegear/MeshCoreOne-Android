// AndroidOnly: WP-405 Issue #504 submission-time supersession through the real URI router and navigation consumer.
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.ContactLinkRequest
import com.meshcoreone.android.app.navigation.ContactLinkLookup
import com.meshcoreone.android.app.navigation.ContactLinkLookupResolution
import com.meshcoreone.android.app.navigation.ContactLinkResolvedCompletion
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.app.navigation.MapFocusRequest
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.app.navigation.NavigationLayout
import com.meshcoreone.android.app.navigation.NavigationState
import com.meshcoreone.android.app.navigation.NavigationFailure
import com.meshcoreone.android.app.navigation.NavigationTarget
import com.meshcoreone.android.app.navigation.SettingsDetail
import com.meshcoreone.android.app.navigation.ToolSelection
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkContext
import com.meshcoreone.android.feature.chats.list.ContactLinkCancellation
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalRouteReplacementTest {
    @Test
    fun supersededRevisionDuringRetryReturnsTrueStaleWithoutCompletionPublication() = runTest {
        val environment = Environment()
        var fail = true
        var supersedingState: NavigationState? = null
        environment.contactLookup = {
            if (fail) throw IllegalStateException("store unavailable")
            ContactLinkPublications().use { publications ->
                environment.navigation.captureContactLinkLookup(
                    key, radio, null, null, null, true, publications,
                )
            }
            supersedingState = environment.navigation.state.value
            contact()
        }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("stale-retry", contactUri)
        pending.submit(delivery)
        val observed = assertNotNull(pending.lookupFailure.value)
        fail = false
        val stale = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(
            pending.retry(observed.deliveryId, observed.attempt),
        )
        assertIs<ContactLookupContextStale>(stale.failure.cause)
        assertEquals(assertNotNull(supersedingState), environment.navigation.state.value)
        assertNull(environment.navigation.state.value.failure)
        assertNull(environment.navigation.state.value.selectedContact)
        assertNull(environment.navigation.state.value.pendingContactLink)
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        assertNotNull(pending.lookupFailure.value)
    }

    @Test
    fun wrongTargetRetryAccountsForTheActualPublishedCoordinatorFailure() = runTest {
        val environment = Environment()
        var fail = true
        var reads = 0
        environment.contactLookup = {
            reads++
            if (fail) throw IllegalStateException("store unavailable")
            contact().copy(publicKey = Bytes(ByteArray(32) { 0x55 }))
        }
        environment.navigation.navigateToMap(37.7749, -122.4194)
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("wrong-target-retry", contactUri)
        assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.submit(delivery))
        val observed = assertNotNull(pending.lookupFailure.value)
        val before = environment.navigation.state.value
        fail = false
        val rejected = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
            pending.retry(observed.deliveryId, observed.attempt),
        )
        assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, rejected.outcome)
        assertEquals(
            before.copy(
                failure = NavigationFailure.WrongTarget(NavigationTarget.CONTACT),
                contactLookupRevision = Math.incrementExact(before.contactLookupRevision),
            ),
            environment.navigation.state.value,
        )
        assertNull(environment.navigation.state.value.selectedContact)
        assertNull(environment.navigation.state.value.pendingContactLink)
        assertNull(pending.snapshot())
        assertNull(pending.lookupFailure.value)
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(
            pending.retry(observed.deliveryId, observed.attempt),
        )
        assertEquals(2, reads)
    }

    @Test
    fun cancellationAfterWrongTargetCasBeforePublicationReleaseCannotMakeTheRejectedDeliveryReplayable() = runTest {
        for (dispatcher in listOf(StandardTestDispatcher(testScheduler), UnconfinedTestDispatcher(testScheduler))) {
            val environment = Environment()
            var fail = true
            var reads = 0
            environment.contactLookup = {
                reads++
                if (fail) throw IllegalStateException("store unavailable")
                contact().copy(publicKey = Bytes(ByteArray(32) { 0x55 }))
            }
            environment.navigation.navigateToMap(37.7749, -122.4194)
            val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
            pending.markReady()
            val delivery = PendingExternalRoute.Delivery("cancelled-rejected-retry", contactUri)
            pending.submit(delivery)
            val observed = assertNotNull(pending.lookupFailure.value)
            val before = environment.navigation.state.value
            fail = false
            val caller = async(dispatcher, start = CoroutineStart.LAZY) {
                pending.retry(observed.deliveryId, observed.attempt)
            }
            environment.afterContactCommit = {
                caller.cancel(CancellationException("cancel after invalid-target CAS before publication release"))
            }
            caller.start()
            caller.join()
            assertTrue(caller.isCancelled)
            val rejected = before.copy(
                failure = NavigationFailure.WrongTarget(NavigationTarget.CONTACT),
                contactLookupRevision = Math.incrementExact(before.contactLookupRevision),
            )
            assertEquals(rejected, environment.navigation.state.value)
            assertNull(environment.navigation.state.value.selectedContact)
            assertNull(environment.navigation.state.value.pendingContactLink)
            assertNull(pending.snapshot())
            assertNull(pending.lookupFailure.value)
            assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
            assertIs<PendingExternalRoute.SubmitOutcome.Staged>(pending.retry())
            assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
            assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(
                pending.retry(observed.deliveryId, observed.attempt),
            )
            assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(
                pending.cancel(observed.deliveryId, observed.attempt),
            )
            assertEquals(2, reads)
            assertEquals(rejected, environment.navigation.state.value)
        }
    }

    @Test
    fun cancellationFromPublishedWrongTargetCollectorCannotMakeTheRejectedDeliveryReplayable() = runTest {
        for (dispatcher in listOf(StandardTestDispatcher(testScheduler), UnconfinedTestDispatcher(testScheduler))) {
            val environment = Environment()
            var fail = true
            var reads = 0
            environment.contactLookup = {
                reads++
                if (fail) throw IllegalStateException("store unavailable")
                contact().copy(publicKey = Bytes(ByteArray(32) { 0x55 }))
            }
            val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
            pending.markReady()
            val delivery = PendingExternalRoute.Delivery("published-rejected-retry", contactUri)
            pending.submit(delivery)
            val observed = assertNotNull(pending.lookupFailure.value)
            val before = environment.navigation.currentState
            val expected = before.copy(
                failure = NavigationFailure.WrongTarget(NavigationTarget.CONTACT),
                contactLookupRevision = Math.incrementExact(before.contactLookupRevision),
            )
            fail = false
            val caller = async(dispatcher, start = CoroutineStart.LAZY) {
                pending.retry(observed.deliveryId, observed.attempt)
            }
            val published = CompletableDeferred<NavigationState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                environment.navigation.state.collect { state ->
                    if (state.failure == expected.failure) {
                        assertEquals(expected, state)
                        assertEquals(state, environment.navigation.state.value)
                        published.complete(state)
                        caller.cancel(CancellationException("cancel after observing invalid-target publication"))
                    }
                }
            }
            try {
                caller.start()
                assertEquals(expected, published.await())
                caller.join()
                assertTrue(caller.isCancelled)
                assertEquals(expected, environment.navigation.currentState)
                assertNull(environment.navigation.state.value.selectedContact)
                assertNull(environment.navigation.state.value.pendingContactLink)
                assertNull(pending.snapshot())
                assertNull(pending.lookupFailure.value)
                assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
                assertIs<PendingExternalRoute.SubmitOutcome.Staged>(pending.retry())
                assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
                assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(
                    pending.cancel(observed.deliveryId, observed.attempt),
                )
                assertEquals(2, reads)
            } finally { collector.cancelAndJoin() }
        }
    }

    @Test
    fun recoveredLookupRetriesUsingTheActualCoordinatorContextPredicate() = runTest {
        val environment = Environment()
        var fail = true
        environment.contactLookup = {
            if (fail) throw IllegalStateException("store unavailable")
            contact()
        }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("retry-A", contactUri)
        assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.submit(delivery))
        val observed = assertNotNull(pending.lookupFailure.value)
        fail = false
        val routed = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
            pending.retry(observed.deliveryId, observed.attempt),
        )
        assertEquals(DeepLinkRouteOutcome.Navigated, routed.outcome)
        assertEquals(contact(), environment.navigation.state.value.selectedContact)
        assertNull(pending.lookupFailure.value)
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
    }

    @Test
    fun replacementNavigatesWithoutWaitingForTheObsoleteLookup() = runTest {
        for (known in listOf(false, true)) {
            val environment = Environment()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            environment.contactLookup = {
                started.complete(Unit)
                withContext(NonCancellable) { release.await() }
                if (known) contact() else null
            }
            val router = AppDeepLinkRouter(environment)
            val pending = PendingExternalRoute(router::routeExternal)
            pending.markReady()
            val a = PendingExternalRoute.Delivery("A", contactUri)
            val b = PendingExternalRoute.Delivery("B", mapUri)
            val first = async { pending.submit(a) }
            started.await()
            val second = async { pending.submit(b) }
            testScheduler.runCurrent()
            assertIs<PendingExternalRoute.SubmitOutcome.Routed>(second.await())
            assertFalse(first.isCompleted, "A deliberately ignores lookup cancellation")
            assertEquals(MapFocusRequest(51.5074, -0.1278), environment.navigation.state.value.pendingMapFocus)
            release.complete(Unit)
            assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
            assertNoContactResult(environment)
            assertEquals(AppTab.MAP, environment.navigation.state.value.selectedTab)
            assertNull(pending.snapshot())
            assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(a))
            assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(b))
        }
    }

    @Test
    fun distinctUriSubmissionInvalidatesAUntilBEntersTheRouter() = runTest {
        for (known in listOf(false, true)) assertBeforeReplacementRouter(known, identicalUri = false)
    }

    @Test
    fun identicalUriWithDistinctDeliveryIdentityAlsoInvalidatesAAtSubmission() = runTest {
        for (known in listOf(false, true)) assertBeforeReplacementRouter(known, identicalUri = true)
    }

    private suspend fun TestScope.assertBeforeReplacementRouter(known: Boolean, identicalUri: Boolean) {
        val environment = Environment()
        val started = CompletableDeferred<Unit>()
        val releaseA = CompletableDeferred<Unit>()
        val allowBRouter = CompletableDeferred<Unit>()
        var lookups = 0
        environment.contactLookup = {
            if (++lookups == 1) {
                started.complete(Unit)
                withContext(NonCancellable) { releaseA.await() }
            }
            if (known) contact() else null
        }
        val router = AppDeepLinkRouter(environment)
        var dispatches = 0
        var bRouterStarted = false
        val pending = PendingExternalRoute {
            if (++dispatches == 2) {
                allowBRouter.await()
                bRouterStarted = true
            }
            router.routeExternal(it)
        }
        pending.markReady()
        val a = PendingExternalRoute.Delivery("A", contactUri)
        val b = PendingExternalRoute.Delivery("B", if (identicalUri) contactUri else channelUri)
        val first = async { pending.submit(a) }
        started.await()
        val second = async { pending.submit(b) }
        testScheduler.runCurrent()
        assertEquals(b, pending.snapshot())
        assertFalse(bRouterStarted)
        releaseA.complete(Unit)
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
        assertFalse(bRouterStarted, "A must be invalidated before B's router is entered")
        assertNoContactResult(environment)
        assertNull(environment.navigation.state.value.pendingChannelLink)
        assertEquals(b, pending.snapshot(), "A cleanup must not clear B")
        allowBRouter.complete(Unit)
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(second.await())
        assertTrue(bRouterStarted)
        val state = environment.navigation.state.value
        if (identicalUri) {
            assertEquals(2, lookups)
            if (known) assertEquals(contact(), state.pendingContactDetail)
            else assertPendingContact(state)
        } else {
            assertEquals(
                ChannelLinkRequest("#test", secret, "testregion", hasHashtagSecretMismatch = true),
                state.pendingChannelLink,
            )
        }
        assertNull(pending.snapshot())
        val after = environment.navigation.state.value
        pending.markReady()
        pending.retry()
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(b))
        assertEquals(after, environment.navigation.state.value)
    }

    @Test
    fun repeatedInFlightDeliveryIdentityDoesNotCancelItsLookup() = runTest {
        val environment = Environment()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var lookups = 0
        environment.contactLookup = {
            lookups++
            started.complete(Unit)
            release.await()
            null
        }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("same", contactUri)
        val first = async { pending.submit(delivery) }
        started.await()
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
        assertEquals(delivery, pending.snapshot())
        release.complete(Unit)
        assertIs<PendingExternalRoute.SubmitOutcome.Routed>(first.await())
        assertEquals(1, lookups)
        assertPendingContact(environment.navigation.state.value)
    }

    @Test
    fun synchronousContactWritesHoldTheSameGuardThatSubmissionInvalidates() = runTest {
        for (known in listOf(false, true)) {
            val environment = Environment()
            var guard: PendingExternalRoute.CommitGuard? = null
            var commits = 0
            environment.contactLookup = {
                guard = assertNotNull(currentCoroutineContext()[PendingExternalRoute.CommitGuard])
                if (known) contact() else null
            }
            environment.beforeContactCommit = {
                assertTrue(Thread.holdsLock(assertNotNull(guard)), "Validity check and navigation write must be atomic")
                commits++
            }
            val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
            pending.markReady()
            assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
                pending.submit(PendingExternalRoute.Delivery("A", contactUri)),
            )
            assertEquals(1, commits)
            val state = environment.navigation.state.value
            if (known) assertEquals(contact(), state.pendingContactDetail)
            else assertPendingContact(state)
        }
    }

    @Test
    fun cancellationAfterContactCommitPropagatesWithoutMakingTheDeliveryReplayable() = runTest {
        for (dispatcher in listOf(StandardTestDispatcher(testScheduler), UnconfinedTestDispatcher(testScheduler))) {
            for (known in listOf(false, true)) {
                val environment = Environment()
                environment.contactLookup = { if (known) contact() else null }
                var commits = 0
                environment.beforeContactCommit = { commits++ }
                val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
                pending.markReady()
                val delivery = PendingExternalRoute.Delivery("committed", contactUri)
                val caller = async(dispatcher, start = CoroutineStart.LAZY) { pending.submit(delivery) }
                environment.afterContactCommit = {
                    caller.cancel(CancellationException("cancel after navigation write"))
                }
                caller.start()
                caller.join()
                assertTrue(caller.isCancelled)
                val committed = environment.navigation.state.value
                if (known) assertEquals(contact(), committed.pendingContactDetail)
                else assertPendingContact(committed)
                assertNull(pending.snapshot(), "A committed target must not be left available for retry")
                assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
                assertIs<PendingExternalRoute.SubmitOutcome.Staged>(pending.retry())
                assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
                assertEquals(1, commits)
                assertEquals(committed, environment.navigation.state.value)
            }
        }
    }

    @Test
    fun obsoleteChannelAndHashtagLookupsCannotReplaceTheNewMapNavigation() = runTest {
        for (uri in listOf(channelUri, "meshcoreone://hashtag/test")) {
            for (known in listOf(false, true)) {
                val environment = Environment()
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                environment.channelLookup = {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    if (known) listOf(ChannelDTO(radioId = radio, index = 1u, name = "#test", secret = secret))
                    else emptyList()
                }
                val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
                pending.markReady()
                val first = async { pending.submit(PendingExternalRoute.Delivery("A", uri)) }
                started.await()
                assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
                    pending.submit(PendingExternalRoute.Delivery("B", mapUri)),
                )
                release.complete(Unit)
                assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
                val state = environment.navigation.state.value
                assertEquals(AppTab.MAP, state.selectedTab)
                assertEquals(MapFocusRequest(51.5074, -0.1278), state.pendingMapFocus)
                assertNull(state.pendingChannelLink)
                assertNull(state.pendingHashtag)
                assertNull(state.pendingChannel)
                assertNull(pending.snapshot())
            }
        }
    }

    @Test
    fun rejectedReplacementPreservesOriginalSelectionAndEveryBackStack() = runTest {
        for (known in listOf(false, true)) {
            for (initialTab in listOf(AppTab.SETTINGS, AppTab.TOOLS)) {
                val environment = environmentWithBackStacks(initialTab)
                val original = environment.navigation.state.value
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                environment.contactLookup = {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    if (known) contact() else null
                }
                val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
                pending.markReady()
                val first = async { pending.submit(PendingExternalRoute.Delivery("A", contactUri)) }
                started.await()
                val captured = original.copy(contactLookupRevision = original.contactLookupRevision + 1)
                assertEquals(captured, environment.navigation.state.value, "Lookup must not select a speculative host")
                val replacement = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
                    pending.submit(PendingExternalRoute.Delivery("B", malformedContactUri)),
                )
                assertIs<DeepLinkRouteOutcome.Rejected>(replacement.outcome)
                assertEquals(captured, environment.navigation.state.value)
                release.complete(Unit)
                assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
                assertEquals(captured, environment.navigation.state.value, "Obsolete completion must not roll back navigation")
                assertNull(pending.snapshot())
            }
        }
    }

    @Test
    fun rejectedReplacementCannotRollBackAUserSelectionMadeDuringLookup() = runTest {
        for (known in listOf(false, true)) {
            for (userTab in AppTab.entries) {
                val environment = environmentWithBackStacks(AppTab.SETTINGS)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                environment.contactLookup = {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                    if (known) contact() else null
                }
                val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
                pending.markReady()
                val first = async { pending.submit(PendingExternalRoute.Delivery("A", contactUri)) }
                started.await()
                environment.navigation.selectTab(AppTab.MAP)
                environment.navigation.selectTab(userTab)
                val userSelection = environment.navigation.state.value
                val replacement = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
                    pending.submit(PendingExternalRoute.Delivery("B", malformedContactUri)),
                )
                assertIs<DeepLinkRouteOutcome.Rejected>(replacement.outcome)
                assertEquals(userSelection, environment.navigation.state.value)
                release.complete(Unit)
                assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
                assertEquals(userSelection, environment.navigation.state.value)
                assertNull(pending.snapshot())
            }
        }
    }

    @Test
    fun rejectedDeliveryCannotRollBackAnAlreadyCommittedContactResult() = runTest {
        for (known in listOf(false, true)) {
            val environment = environmentWithBackStacks(AppTab.SETTINGS)
            environment.contactLookup = { if (known) contact() else null }
            val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
            pending.markReady()
            val delivery = PendingExternalRoute.Delivery("A", contactUri)
            assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.submit(delivery))
            val committed = environment.navigation.state.value
            if (known) {
                assertEquals(AppTab.NODES, committed.selectedTab)
                assertEquals(contact(), committed.pendingContactDetail)
            } else {
                assertEquals(AppTab.CHATS, committed.selectedTab)
                assertPendingContact(committed)
            }
            val rejected = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
                pending.submit(PendingExternalRoute.Delivery("B", malformedContactUri)),
            )
            assertIs<DeepLinkRouteOutcome.Rejected>(rejected.outcome)
            assertEquals(committed, environment.navigation.state.value)
            assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
            assertEquals(committed, environment.navigation.state.value)
        }
    }

    @Test
    fun validReplacementCommitsItsOwnSelectionWithoutDestroyingUnrelatedStacks() = runTest {
        for (uri in listOf(mapUri, channelUri, "meshcoreone://hashtag/test", contactUri)) {
            val environment = environmentWithBackStacks(AppTab.SETTINGS)
            val originalStacks = environment.navigation.state.value.stacks
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var lookups = 0
            environment.contactLookup = {
                if (++lookups == 1) {
                    started.complete(Unit)
                    withContext(NonCancellable) { release.await() }
                }
                null
            }
            val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
            pending.markReady()
            val first = async { pending.submit(PendingExternalRoute.Delivery("A", contactUri)) }
            started.await()
            environment.navigation.selectTab(AppTab.NODES)
            assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
                pending.submit(PendingExternalRoute.Delivery("B", uri)),
            )
            val committed = environment.navigation.state.value
            assertEquals(originalStacks, committed.stacks)
            when (uri) {
                mapUri -> {
                    assertEquals(AppTab.MAP, committed.selectedTab)
                    assertEquals(MapFocusRequest(51.5074, -0.1278), committed.pendingMapFocus)
                }
                channelUri -> {
                    assertEquals(AppTab.CHATS, committed.selectedTab)
                    assertEquals(ChannelLinkRequest("#test", secret, "testregion", true), committed.pendingChannelLink)
                }
                contactUri -> {
                    assertEquals(AppTab.CHATS, committed.selectedTab)
                    assertPendingContact(committed)
                    assertEquals(2, lookups, "Identical URI text with a new ID must route independently")
                }
                else -> {
                    assertEquals(AppTab.CHATS, committed.selectedTab)
                    assertEquals(HashtagJoinRequest("#test"), committed.pendingHashtag)
                }
            }
            release.complete(Unit)
            assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
            assertEquals(committed, environment.navigation.state.value)
            assertNull(pending.snapshot())
        }
    }

    @Test
    fun rejectedUriPreservesToolDetailAndBackAtTheRailBoundary() = runTest {
        val rail = NavigationLayout.RAIL_MIN_WIDTH_DP.toFloat()
        for (width in listOf(rail - 1f, rail, rail + 1f)) {
            val environment = environmentWithBackStacks(AppTab.TOOLS)
            val reference = environmentWithBackStacks(AppTab.TOOLS)
            environment.navigation.measureWindow(width)
            reference.navigation.measureWindow(width)
            val original = environment.navigation.state.value
            assertEquals(2, original.activeStack.size)
            assertEquals(ToolSelection.NOISE_FLOOR, original.selectedTool)
            assertIs<DeepLinkRouteOutcome.Rejected>(
                AppDeepLinkRouter(environment).routeExternal(malformedContactUri),
            )
            assertEquals(original, environment.navigation.state.value)
            assertTrue(environment.navigation.back())
            assertTrue(reference.navigation.back())
            assertEquals(reference.navigation.state.value, environment.navigation.state.value)
            assertEquals(AppTab.TOOLS, environment.navigation.state.value.selectedTab)
            assertEquals(1, environment.navigation.state.value.activeStack.size)
            assertNull(environment.navigation.state.value.selectedTool)
        }
    }

    @Test
    fun committedExternalSelectionRetainsTheApprovedRailToolCleanup() = runTest {
        val rail = NavigationLayout.RAIL_MIN_WIDTH_DP.toFloat()
        for (width in listOf(rail - 1f, rail, rail + 1f)) {
            for (uri in listOf(mapUri, contactUri, channelUri, "meshcoreone://hashtag/test")) {
                val environment = environmentWithBackStacks(AppTab.TOOLS)
                environment.navigation.measureWindow(width)
                val original = environment.navigation.state.value
                val outcome = AppDeepLinkRouter(environment).routeExternal(uri)
                if (uri == mapUri) assertIs<DeepLinkRouteOutcome.Navigated>(outcome)
                else assertIs<DeepLinkRouteOutcome.ConfirmationStaged>(outcome)
                val committed = environment.navigation.state.value
                assertEquals(if (uri == mapUri) AppTab.MAP else AppTab.CHATS, committed.selectedTab)
                assertEquals(
                    if (width < rail) original.stacks.getValue(AppTab.TOOLS)
                    else original.stacks.getValue(AppTab.TOOLS).take(1),
                    committed.stacks.getValue(AppTab.TOOLS),
                )
                assertEquals(if (width < rail) ToolSelection.NOISE_FLOOR else null, committed.selectedTool)
                for (tab in AppTab.entries.filterNot { it == AppTab.TOOLS }) {
                    assertEquals(original.stacks.getValue(tab), committed.stacks.getValue(tab))
                }
            }
        }
    }

    @Test
    fun reentrantSubmissionObservesSelectionAndConfirmationAsOneCommit() = runTest {
        for (uri in listOf(contactUri, channelUri, "meshcoreone://hashtag/test")) {
            val environment = environmentWithBackStacks(AppTab.SETTINGS)
            val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
            pending.markReady()
            val submissions = this
            var replacement: Deferred<PendingExternalRoute.SubmitOutcome>? = null
            val observed = mutableListOf<NavigationState>()
            val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                environment.navigation.state.collect { state ->
                    observed += state
                    if (state.selectedTab == AppTab.CHATS && replacement == null) {
                        when (uri) {
                            contactUri -> assertPendingContact(state)
                            channelUri -> assertEquals(
                                ChannelLinkRequest("#test", secret, "testregion", true), state.pendingChannelLink,
                            )
                            else -> assertEquals(HashtagJoinRequest("#test"), state.pendingHashtag)
                        }
                        replacement = submissions.async(start = CoroutineStart.UNDISPATCHED) {
                            pending.submit(PendingExternalRoute.Delivery("B", mapUri))
                        }
                    }
                }
            }
            val first = async { pending.submit(PendingExternalRoute.Delivery("A", uri)) }
            testScheduler.runCurrent()
            assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(first.await())
            assertIs<PendingExternalRoute.SubmitOutcome.Routed>(assertNotNull(replacement).await())
            val committed = environment.navigation.state.value
            assertEquals(AppTab.MAP, committed.selectedTab)
            assertEquals(MapFocusRequest(51.5074, -0.1278), committed.pendingMapFocus)
            assertEquals(
                if (uri == contactUri) listOf(AppTab.SETTINGS, AppTab.SETTINGS, AppTab.CHATS, AppTab.MAP)
                else listOf(AppTab.SETTINGS, AppTab.CHATS, AppTab.MAP),
                observed.map { it.selectedTab },
            )
            assertEquals(committed, observed.last())
            assertNull(pending.snapshot())
            assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(
                pending.submit(PendingExternalRoute.Delivery("A", uri)),
            )
            collector.cancelAndJoin()
        }
    }

    @Test
    fun inChatRoutingAndClearingDoNotSelectAnExternalConfirmationHost() = runTest {
        for (uri in listOf(contactUri, channelUri, "meshcoreone://hashtag/test")) {
            val environment = environmentWithBackStacks(AppTab.SETTINGS)
            val original = environment.navigation.state.value
            assertIs<DeepLinkRouteOutcome.ConfirmationStaged>(AppDeepLinkRouter(environment).route(uri))
            val staged = environment.navigation.state.value
            assertEquals(original.selectedTab, staged.selectedTab)
            assertEquals(original.stacks, staged.stacks)
            when (uri) {
                contactUri -> {
                    assertNotNull(staged.pendingContactLink)
                    environment.navigation.clearPendingContactLink()
                }
                channelUri -> {
                    assertNotNull(staged.pendingChannelLink)
                    environment.navigation.clearPendingChannelLink()
                }
                else -> {
                    assertNotNull(staged.pendingHashtag)
                    environment.navigation.clearPendingHashtag()
                }
            }
            assertEquals(
                if (uri == contactUri) original.copy(
                    nextEntryId = original.nextEntryId + 1,
                    contactLookupRevision = original.contactLookupRevision + 2,
                ) else original,
                environment.navigation.state.value,
            )
        }
    }

    private fun environmentWithBackStacks(initialTab: AppTab): Environment = Environment().apply {
        navigation.navigateToContactDetail(contact())
        navigation.navigateToSetting(SettingsDetail.APPEARANCE)
        navigation.measureWindow(900f)
        if (initialTab == AppTab.TOOLS) navigation.navigateToTool(ToolSelection.NOISE_FLOOR)
    }

    private fun assertNoContactResult(environment: Environment) {
        val state = environment.navigation.state.value
        assertNull(state.pendingContactLink)
        assertNull(state.pendingContactDetail)
        assertNull(state.selectedContact)
    }

    private fun assertPendingContact(state: NavigationState) {
        assertEquals(
            ContactLinkRequest(
                "Alice", key, ContactType.CHAT, targetRadioId = radio,
                generation = state.generation, deliveryId = state.nextEntryId - 1,
            ),
            state.pendingContactLink,
        )
    }

    private class CapturedLookup(val lookup: ContactLinkLookup) : ContactLinkContext {
        override val radioId: RadioId? get() = lookup.radioId
        override val selfPublicKey: Bytes? get() = lookup.selfPublicKey
        override val disposition: ContactLinkCaptureDisposition get() = lookup.disposition
    }

    private class Environment : AppDeepLinkEnvironment {
        val navigation = NavigationCoordinator()
        var contactLookup: suspend () -> ContactDTO? = { null }
        var channelLookup: suspend () -> List<ChannelDTO> = { emptyList() }
        var beforeContactCommit: () -> Unit = {}
        var afterContactCommit: () -> Unit = {}
        override var selectedTab: AppTab
            get() = navigation.state.value.selectedTab
            set(value) = navigation.selectTab(value)
        override val currentRadioId: RadioId = radio
        override val connectedDevicePublicKey: Bytes? = null
        override fun captureContactLinkContext(
            publicKey: Bytes, publications: ContactLinkPublications,
        ): ContactLinkContext = CapturedLookup(navigation.captureContactLinkLookup(
            publicKey, radio, null, null, null, true, publications,
        ))
        override fun resolveContactLinkContext(
           context: ContactLinkContext, publicKey: Bytes,
        ): ContactLinkLookupResolution {
           val lookup = (context as CapturedLookup).lookup
           if (lookup.publicKey != publicKey) return ContactLinkLookupResolution.Stale
           return navigation.resolveCapturedContactLookup(
               lookup, navigation.state.value.boundSessionToken, lookup.allowsInitialBinding, null,
           )
        }
        override fun completeResolvedContactLookup(
            context: ContactLinkContext, target: ContactLinkLookupResolution.Current,
            link: MeshCoreDeepLink.Contact, existing: ContactDTO?,
            selectChats: Boolean, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
        ): ContactLinkResolvedCompletion {
            val lookup = (context as CapturedLookup).lookup
            beforeContactCommit()
            val result = navigation.completeResolvedCapturedContactLookup(
                lookup, target, ContactLinkRequest(link.name, link.publicKey, link.contactType), existing,
                navigation.state.value.boundSessionToken, lookup.allowsInitialBinding, null, selectChats, publications, cancellation,
            )
            afterContactCommit()
            return result
        }
        override fun navigateToMap(latitude: Double, longitude: Double) = navigation.navigateToMap(latitude, longitude)
        override fun navigateToContactDetail(contact: ContactDTO) {
            beforeContactCommit()
            navigation.navigateToContactDetail(contact)
            afterContactCommit()
        }
        override fun navigateToChannel(channel: ChannelDTO) = navigation.navigateToChannel(channel)
        override fun stageContactConfirmation(link: MeshCoreDeepLink.Contact, selectChats: Boolean) {
            beforeContactCommit()
            navigation.stageContactLink(ContactLinkRequest(link.name, link.publicKey, link.contactType), selectChats)
            afterContactCommit()
        }
        override fun stageChannelConfirmation(link: MeshCoreDeepLink.Channel, selectChats: Boolean) =
            navigation.stageChannelLink(
                ChannelLinkRequest(link.name, link.secret, link.regionScope, link.hasHashtagSecretMismatch),
                selectChats,
            )
        override fun stageHashtagConfirmation(link: MeshCoreDeepLink.Hashtag, selectChats: Boolean) =
            navigation.stageHashtag(HashtagJoinRequest(link.fullName), selectChats)
        override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? = contactLookup()
        override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> = channelLookup()
    }

    private companion object {
        val radio = RadioId(UUID(0, 1))
        val key = Bytes(ByteArray(32) { 0xAB.toByte() })
        val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
        val contactUri = "meshcore://contact/add?name=Alice&public_key=${key.hexString}&type=1"
        val channelUri = "meshcore://channel/add?name=%23test&secret=${secret.hexString}&region_scope=testregion"
        const val mapUri = "meshcore://map?lat=51.5074&lon=-0.1278"
        const val malformedContactUri = "meshcore://contact/add?name=broken"
        fun contact() = ContactDTO(
            id = UUID(0, 2), radioId = radio, publicKey = key, name = "Alice",
            typeRawValue = ContactType.CHAT.rawValue, lastHeardTimestamp = null,
        )
    }
}
