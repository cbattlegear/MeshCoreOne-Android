// PortedFrom: MC1Tests/State/PendingExternalURLTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Tests/Utilities/MeshCoreURLParserTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.container

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModelStore
import com.meshcoreone.android.app.NavigationHostViewModel
import com.meshcoreone.android.app.deeplinks.AppDeepLinkRouter
import com.meshcoreone.android.app.deeplinks.AppDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.ContainerDeepLinkEnvironment
import com.meshcoreone.android.app.deeplinks.DeepLinkRouteOutcome
import com.meshcoreone.android.app.deeplinks.PendingExternalRoute
import com.meshcoreone.android.app.deeplinks.MeshCoreDeepLink
import com.meshcoreone.android.app.navigation.ContactLinkLookupResolution
import com.meshcoreone.android.app.navigation.ContactLinkResolvedCompletion
import com.meshcoreone.android.app.navigation.MapFocusRequest
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.contracts.domain.PersistenceStoreProtocol
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.ChatLinkRouter
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkCancellation
import com.meshcoreone.android.feature.chats.list.ContactLinkContext
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class LinkConsumerIntegrationTest : RoomProcessTest() {
    private val sanFrancisco = "meshcore://map?lat=37.7749&lon=-122.4194"
    private val focus = MapFocusRequest(37.7749, -122.4194)

    @Test fun canceledUnboundContactCaptureResolvesOnlyItsPermittedFirstGraph() = runTest {
        for ((replaceFirstGraph, selfLink) in listOf(false to false, true to false, false to true, true to true)) {
            val h = ContainerHarness(this, store)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved peer") }
            val reads = mutableListOf<RadioId>()
            var captures = 0
            lateinit var submission: Job
            val delegate = ContainerDeepLinkEnvironment(h.container, h.container.navigation)
            val environment = object : AppDeepLinkEnvironment by delegate {
                override fun captureContactLinkContext(
                    publicKey: Bytes,
                    publications: com.meshcoreone.android.feature.chats.list.ContactLinkPublications,
                ): com.meshcoreone.android.feature.chats.list.ContactLinkContext {
                    val captured = delegate.captureContactLinkContext(publicKey, publications)
                    if (++captures == 1) {
                        assertNull(captured.radioId)
                        submission.cancel()
                    }
                    return captured
                }
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    reads += radioId
                    return delegate.fetchContact(radioId, publicKey)
                }
            }
            try {
                val started = h.container.start()
                h.eventually("unbound capture fixture starts") { started.isCompleted }
                assertNull(h.sessions.current)
                assertNull(h.container.appState.offlineDataStore)
                val router = AppDeepLinkRouter(environment)
                val pending = PendingExternalRoute(router::routeExternal)
                pending.markReady()
                val uri = h.container.nodesFeature.uriCodec.exportContactUri(
                    "Alias", if (selfLink) key(7) else key(20), ContactType.REPEATER,
                )
                val delivery = PendingExternalRoute.Delivery("unbound-A", uri)
                submission = launch(start = CoroutineStart.LAZY) { pending.submit(delivery) }
                submission.start()
                submission.join()
                assertTrue(submission.isCancelled)
                assertEquals(delivery, pending.snapshot())
                assertTrue(pending.consumedDeliveryIds().isEmpty())
                assertTrue(reads.isEmpty())
                val connecting = async { h.manager.connect(h.target()) }
                val navigation = h.container.navigation
                h.eventually("first graph binds") {
                    connecting.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                        navigation.state.value.navigationReady
                }
                connecting.await()
                val first = assertNotNull(h.sessions.current)
                val saved = assertNotNull(store.fetchContact(first.token.radioId, key(20)))
                if (replaceFirstGraph) {
                    val replacing = async { h.manager.rebuildSession(assertNotNull(h.appState.connectedDevice).id) }
                    h.eventually("replacement of first graph binds") {
                        val next = h.sessions.current
                        replacing.isCompleted && next != null && next !== first &&
                            navigation.state.value.boundSessionToken == next.token &&
                            navigation.state.value.navigationReady
                    }
                    replacing.await()
                }
                val before = navigation.currentState
                val result = pending.retry()
                assertEquals(1, captures, "Retry must reuse the original null-radio capture")
                if (replaceFirstGraph) {
                    val stale = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(result)
                    assertIs<com.meshcoreone.android.app.deeplinks.ContactLookupContextStale>(stale.failure.cause)
                    assertTrue(reads.isEmpty(), "A replacement is not the permitted first binding")
                    assertEquals(before, navigation.currentState)
                    assertEquals(delivery, pending.snapshot())
                    assertTrue(pending.consumedDeliveryIds().isEmpty())
                    assertNotNull(pending.lookupFailure.value)
                } else {
                    val routed = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(result)
                    if (selfLink) {
                        assertEquals(DeepLinkRouteOutcome.IgnoredSelfContact, routed.outcome)
                        assertTrue(reads.isEmpty(), "First-bound self identity must skip the store")
                        assertNull(navigation.currentState.selectedContact)
                    } else {
                        assertEquals(DeepLinkRouteOutcome.Navigated, routed.outcome)
                        assertEquals(listOf(first.token.radioId), reads)
                        assertEquals(saved, navigation.currentState.selectedContact)
                    }
                    assertNull(navigation.currentState.pendingContactLink)
                    assertNull(pending.snapshot())
                    assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
                }
            } finally { h.container.close() }
        }
    }

    @Test fun unboundResolvedCompletionCannotTreatFirstGraphSavedContactAsAbsent() = runTest {
        val h = ContainerHarness(this, store)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved peer") }
        val (cancellation, observation) = ContactLinkCancellation.observe(checkNotNull(currentCoroutineContext()[Job]))
        try {
            val started = h.container.start()
            h.eventually("null-resolution fixture starts") { started.isCompleted }
            val navigation = h.container.navigation
            val environment = ContainerDeepLinkEnvironment(h.container, navigation)
            val link = MeshCoreDeepLink.Contact("Alias", key(20), ContactType.REPEATER)
            val context = ContactLinkPublications().use {
                environment.captureContactLinkContext(link.publicKey, it)
            }
            val unbound = assertIs<ContactLinkLookupResolution.Current>(
                environment.resolveContactLinkContext(context, link.publicKey),
            )
            assertNull(unbound.radioId)
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("first graph binds after null resolution") {
                connecting.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                    navigation.state.value.navigationReady
            }
            connecting.await()
            val radio = assertNotNull(h.sessions.current).token.radioId
            val saved = assertNotNull(store.fetchContact(radio, link.publicKey))
            val before = navigation.currentState
            val changed = ContactLinkPublications().use {
                environment.completeResolvedContactLookup(context, unbound, link, null, true, it, cancellation)
            }
            assertIs<ContactLinkResolvedCompletion.TargetChanged>(changed)
            assertEquals(before, navigation.currentState)
            assertNull(navigation.currentState.pendingContactLink)
            val bound = assertIs<ContactLinkLookupResolution.Current>(
                environment.resolveContactLinkContext(context, link.publicKey),
            )
            assertEquals(radio, bound.radioId)
            val actual = environment.fetchContact(assertNotNull(bound.radioId), link.publicKey)
            assertEquals(saved, actual)
            val completed = ContactLinkPublications().use {
                environment.completeResolvedContactLookup(context, bound, link, actual, true, it, cancellation)
            }
            assertEquals(
                ContactLinkCompletion.Navigated,
                assertIs<ContactLinkResolvedCompletion.Completed>(completed).outcome,
            )
            assertEquals(saved, navigation.currentState.selectedContact)
            assertNull(navigation.currentState.pendingContactLink)
        } finally {
            observation.dispose()
            h.container.close()
        }
    }

    @Test fun canceledExternalCompletionWaitingForActualLifecycleMonitorHasNoEffectAndRetainsOrigin() = runTest {
        for (known in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved peer") }
            val read = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val managerHeld = CountDownLatch(1)
            val releaseManager = CountDownLatch(1)
            val enteringCompletion = CountDownLatch(1)
            val completionThread = AtomicReference<Thread>()
            var heldAttempt = true
            var captures = 0
            val delegate = ContainerDeepLinkEnvironment(h.container, h.container.navigation)
            val environment = object : AppDeepLinkEnvironment by delegate {
                override fun captureContactLinkContext(
                    publicKey: Bytes, publications: ContactLinkPublications,
                ): ContactLinkContext = delegate.captureContactLinkContext(publicKey, publications).also { captures++ }

                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    val found = delegate.fetchContact(radioId, publicKey)
                    if (heldAttempt) {
                        assertEquals(known, found != null)
                        read.complete(Unit)
                        releaseRead.await()
                    }
                    return found
                }

                override fun completeResolvedContactLookup(
                    context: ContactLinkContext, target: ContactLinkLookupResolution.Current,
                    link: MeshCoreDeepLink.Contact, existing: ContactDTO?, selectChats: Boolean,
                    publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
                ): ContactLinkResolvedCompletion {
                    if (heldAttempt) {
                        completionThread.set(Thread.currentThread())
                        enteringCompletion.countDown()
                    }
                    return delegate.completeResolvedContactLookup(
                        context, target, link, existing, selectChats, publications, cancellation,
                    )
                }
            }
            try {
                val started = h.container.start()
                h.eventually("monitor cancellation fixture starts") { started.isCompleted }
                val connecting = async { h.manager.connect(h.target()) }
                val navigation = h.container.navigation
                h.eventually("monitor cancellation origin binds") {
                    connecting.isCompleted && navigation.state.value.navigationReady
                }
                connecting.await()
                val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
                pending.markReady()
                val delivery = PendingExternalRoute.Delivery(
                    "monitor-cancel",
                    h.container.nodesFeature.uriCodec.exportContactUri(
                        "Alias", if (known) key(20) else key(30), ContactType.REPEATER,
                    ),
                )
                val caller = async(Dispatchers.IO) { pending.submit(delivery) }
                read.await()
                val before = navigation.currentState
                val holding = async(Dispatchers.IO) {
                    h.manager.withNavigationCommitBoundary {
                        managerHeld.countDown()
                        check(releaseManager.await(10, TimeUnit.SECONDS)) { "Lifecycle monitor was not released" }
                    }
                }
                assertTrue(managerHeld.await(10, TimeUnit.SECONDS))
                releaseRead.complete(Unit)
                assertTrue(enteringCompletion.await(10, TimeUnit.SECONDS))
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (completionThread.get().state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
                assertEquals(Thread.State.BLOCKED, completionThread.get().state, "Completion must wait on the real manager monitor")
                caller.cancel()
                releaseManager.countDown()
                holding.await()
                caller.join()
                assertTrue(caller.isCancelled)
                assertEquals(before, navigation.currentState)
                assertEquals(before, navigation.state.value)
                assertEquals(delivery, pending.snapshot())
                assertTrue(pending.consumedDeliveryIds().isEmpty())
                assertNull(pending.lookupFailure.value)
                heldAttempt = false
                val retried = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry())
                assertEquals(
                    if (known) DeepLinkRouteOutcome.Navigated else DeepLinkRouteOutcome.ConfirmationStaged,
                    retried.outcome,
                )
                assertEquals(1, captures, "Precommit cancellation must not recapture the initiating graph")
                assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
                assertNull(pending.snapshot())
            } finally {
                releaseManager.countDown()
                releaseRead.complete(Unit)
                h.container.close()
            }
        }
    }

    @Test fun externalFirstBindingReresolvesOriginalCaptureAndReadsSavedPeerBeforeConsuming() = runTest {
        for (selfLink in listOf(false, true)) {
            val h = ContainerHarness(this, store)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved peer") }
            val resolvedUnbound = CountDownLatch(1)
            val releaseUnbound = CountDownLatch(1)
            val delegate = ContainerDeepLinkEnvironment(h.container, h.container.navigation)
            val reads = mutableListOf<RadioId>()
            var captures = 0
            var resolutions = 0
            val environment = object : AppDeepLinkEnvironment by delegate {
                override fun captureContactLinkContext(
                    publicKey: Bytes, publications: ContactLinkPublications,
                ): ContactLinkContext = delegate.captureContactLinkContext(publicKey, publications).also { captures++ }

                override fun resolveContactLinkContext(
                    context: ContactLinkContext, publicKey: Bytes,
                ): ContactLinkLookupResolution {
                    val target = delegate.resolveContactLinkContext(context, publicKey)
                    if (++resolutions == 1) {
                        assertNull(assertIs<ContactLinkLookupResolution.Current>(target).radioId)
                        resolvedUnbound.countDown()
                        check(releaseUnbound.await(10, TimeUnit.SECONDS)) { "Unbound resolution was not released" }
                    }
                    return target
                }

                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    reads += radioId
                    return delegate.fetchContact(radioId, publicKey)
                }
            }
            try {
                val started = h.container.start()
                h.eventually("first-bound automatic retry fixture starts") { started.isCompleted }
                val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
                pending.markReady()
                val delivery = PendingExternalRoute.Delivery(
                    "first-binding",
                    h.container.nodesFeature.uriCodec.exportContactUri(
                        "Alias", if (selfLink) key(7) else key(20), ContactType.REPEATER,
                    ),
                )
                val caller = async(Dispatchers.IO) { pending.submit(delivery) }
                assertTrue(resolvedUnbound.await(10, TimeUnit.SECONDS))
                val connecting = async { h.manager.connect(h.target()) }
                val navigation = h.container.navigation
                h.eventually("first graph binds before the original resolution completes") {
                    connecting.isCompleted && navigation.state.value.navigationReady
                }
                connecting.await()
                val radio = assertNotNull(h.sessions.current).token.radioId
                val saved = assertNotNull(store.fetchContact(radio, key(20)))
                assertNull(navigation.currentState.pendingContactLink)
                releaseUnbound.countDown()
                val routed = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(caller.await())
                assertEquals(1, captures)
                assertEquals(2, resolutions, "TargetChanged gets only a re-resolution, not a new origin")
                if (selfLink) {
                    assertEquals(DeepLinkRouteOutcome.IgnoredSelfContact, routed.outcome)
                    assertTrue(reads.isEmpty())
                    assertNull(navigation.currentState.selectedContact)
                } else {
                    assertEquals(DeepLinkRouteOutcome.Navigated, routed.outcome)
                    assertEquals(listOf(radio), reads)
                    assertEquals(saved, navigation.currentState.selectedContact)
                }
                assertNull(navigation.currentState.pendingContactLink)
                assertNull(pending.snapshot())
                assertNull(pending.lookupFailure.value)
                assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
            } finally {
                releaseUnbound.countDown()
                h.container.close()
            }
        }
    }

    @Test fun externalContactAdapterReadsCapturedRadioWithoutLastConnectionPreference() = runTest {
        val radio = RadioId(java.util.UUID.fromString("447175B7-557D-4F48-B671-4B2CEE2DC932"))
        val foreignRadio = RadioId(java.util.UUID.fromString("547175B7-557D-4F48-B671-4B2CEE2DC932"))
        store.saveDevice(DeviceDTO(radioId = radio, publicKey = key(7), nodeName = "Restored"))
        store.saveDevice(DeviceDTO(radioId = foreignRadio, publicKey = key(8), nodeName = "Other"))
        val saved = ContactDTO(radioId = radio, publicKey = key(20), name = "Restored peer", lastHeardTimestamp = null)
        val foreign = ContactDTO(radioId = foreignRadio, publicKey = key(21), name = "Other peer", lastHeardTimestamp = null)
        store.saveContact(saved)
        store.saveContact(foreign)
        val h = ContainerHarness(this, store)
        try {
            assertNull(h.container.appState.offlineDataStore)
            val adapter = ContainerDeepLinkEnvironment(h.container, h.container.navigation)
            assertEquals(saved, adapter.fetchContact(radio, saved.publicKey))
            assertNull(adapter.fetchContact(radio, foreign.publicKey))
            assertEquals(foreign, adapter.fetchContact(foreignRadio, foreign.publicKey))
            assertNull(adapter.fetchContact(radio, key(22)))
            assertNull(h.sessions.current)
            assertTrue(h.links.radios.isEmpty())
        } finally { h.container.close() }
    }

    @Test fun externalContactAdapterDoesNotTurnProcessStoreFailureIntoAbsence() = runTest {
        val radio = RadioId(java.util.UUID.fromString("447175B7-557D-4F48-B671-4B2CEE2DC932"))
        val failure = IllegalStateException("contact lookup failed")
        val failingStore = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? = throw failure
        }
        val h = ContainerHarness(this, store, persistenceOverride = failingStore)
        try {
            assertNull(h.container.appState.offlineDataStore)
            val adapter = ContainerDeepLinkEnvironment(h.container, h.container.navigation)
            assertSame(failure, assertFailsWith<IllegalStateException> { adapter.fetchContact(radio, key(20)) })
            assertNull(h.sessions.current)
            assertTrue(h.links.radios.isEmpty())
        } finally { h.container.close() }
    }

    @Test fun externalContactStoreFailureRetainsDeliveryUntilRecoveredRoomLookup() = runTest {
        val failure = IllegalStateException("contact lookup unavailable")
        var failLookup = false
        val observedRadios = mutableListOf<RadioId>()
        val recoveringStore = object : PersistenceStoreProtocol by store {
            override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                observedRadios += radioId
                if (failLookup) throw failure
                return store.fetchContact(radioId, publicKey)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = recoveringStore)
        h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved peer") }
        try {
            val started = h.container.start()
            h.eventually("lookup recovery fixture starts") { started.isCompleted }
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("lookup recovery graph is ready") {
                connecting.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                    h.container.navigation.state.value.navigationReady
            }
            connecting.await()
            val radio = assertNotNull(h.sessions.current).token.radioId
            val saved = assertNotNull(store.fetchContact(radio, key(20)))
            val navigation = h.container.navigation
            navigation.navigateToMap(focus.latitude, focus.longitude)
            val router = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
            val pending = PendingExternalRoute(router::routeExternal)
            pending.markReady()
            val delivery = PendingExternalRoute.Delivery(
                "recover-contact",
                h.container.nodesFeature.uriCodec.exportContactUri("Alias", key(20), ContactType.REPEATER),
            )
            observedRadios.clear()
            failLookup = true
            val failed = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.submit(delivery))
            assertSame(failure, failed.failure.cause)
            assertEquals(delivery, pending.snapshot())
            assertTrue(pending.consumedDeliveryIds().isEmpty())
            assertNull(navigation.currentState.pendingContactLink)
            assertEquals(AppTab.MAP, navigation.currentState.selectedTab)
            assertEquals(focus, navigation.currentState.pendingMapFocus)

            failLookup = false
            val recovered = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry())
            assertEquals(DeepLinkRouteOutcome.Navigated, recovered.outcome)
            assertEquals(saved, navigation.currentState.selectedContact)
            assertEquals(listOf(radio, radio), observedRadios)
            assertNull(pending.snapshot())
            assertTrue(delivery.id in pending.consumedDeliveryIds())
            assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
            val absent = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
                pending.submit(PendingExternalRoute.Delivery(
                    "absent-contact",
                    h.container.nodesFeature.uriCodec.exportContactUri("Absent", key(30), ContactType.REPEATER),
                )),
            )
            assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, absent.outcome)
            assertEquals(key(30), assertNotNull(navigation.currentState.pendingContactLink).publicKey)
        } finally { h.container.close() }
    }

    @Test fun failedExternalContactCannotRetryAgainstDifferentOrIdenticalReplacementGraph() = runTest {
        for ((sameRadio, cancelFirstLookup) in listOf(false to false, true to false, false to true, true to true)) {
            var identity = key(7)
            var failLookup = false
            var cancelLookup = false
            val lookupStarted = CompletableDeferred<Unit>()
            val reads = mutableListOf<RadioId>()
            val recoveringStore = object : PersistenceStoreProtocol by store {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    if (publicKey == key(20)) {
                        reads += radioId
                        if (cancelLookup) {
                            lookupStarted.complete(Unit)
                            awaitCancellation()
                        }
                        if (failLookup) throw IllegalStateException("store unavailable")
                    }
                    return store.fetchContact(radioId, publicKey)
                }
            }
            val h = ContainerHarness(this, store, identity = { identity }, persistenceOverride = recoveringStore)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved peer") }
            try {
                val started = h.container.start()
                h.eventually("retry origin fixture starts") { started.isCompleted }
                val connecting = async { h.manager.connect(h.target()) }
                val navigation = h.container.navigation
                h.eventually("origin graph binds") {
                    connecting.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                        navigation.state.value.navigationReady
                }
                connecting.await()
                val first = assertNotNull(h.sessions.current)
                val router = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, navigation))
                val pending = PendingExternalRoute(router::routeExternal)
                pending.markReady()
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Alias", key(20), ContactType.REPEATER)
                val delivery = PendingExternalRoute.Delivery("origin-A", uri)
                val failed = if (cancelFirstLookup) {
                    cancelLookup = true
                    val submission = launch { pending.submit(delivery) }
                    lookupStarted.await()
                    submission.cancelAndJoin()
                    cancelLookup = false
                    assertNull(pending.lookupFailure.value)
                    null
                } else {
                    failLookup = true
                    assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.submit(delivery))
                    assertNotNull(pending.lookupFailure.value)
                }
                failLookup = false
                if (!sameRadio) identity = key(8)
                val rebuilding = async {
                    h.manager.rebuildSession(assertNotNull(h.appState.connectedDevice).id)
                }
                h.eventually("replacement graph binds") {
                    val next = h.sessions.current
                    rebuilding.isCompleted && next != null && next !== first &&
                        h.manager.connectionState == DeviceConnectionState.READY &&
                        navigation.state.value.boundSessionToken == next.token &&
                        navigation.state.value.navigationReady
                }
                rebuilding.await()
                val replacement = assertNotNull(h.sessions.current)
                assertNotEquals(first.token, replacement.token)
                if (sameRadio) assertEquals(first.token.radioId, replacement.token.radioId)
                else assertNotEquals(first.token.radioId, replacement.token.radioId)
                navigation.navigateToMap(focus.latitude, focus.longitude)
                val before = navigation.currentState
                reads.clear()
                val stale = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(
                    if (failed != null) pending.retry(failed.deliveryId, failed.attempt) else pending.retry(),
                )
                assertIs<com.meshcoreone.android.app.deeplinks.ContactLookupContextStale>(stale.failure.cause)
                assertTrue(reads.isEmpty(), "Retry must reject before any replacement-radio lookup")
                assertEquals(before, navigation.currentState)
                assertEquals(delivery, pending.snapshot())
                assertTrue(pending.consumedDeliveryIds().isEmpty())
                val currentFailure = assertNotNull(pending.lookupFailure.value)
                if (failed != null) {
                    assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(
                        pending.retry(failed.deliveryId, failed.attempt),
                    )
                    assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(
                        pending.cancel(failed.deliveryId, failed.attempt),
                    )
                }
                assertEquals(currentFailure, pending.lookupFailure.value)
                val fresh = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
                    pending.submit(PendingExternalRoute.Delivery("fresh-B", uri)),
                )
                assertEquals(DeepLinkRouteOutcome.Navigated, fresh.outcome)
                assertEquals(listOf(replacement.token.radioId), reads)
                assertEquals(replacement.token.radioId, assertNotNull(navigation.currentState.selectedContact).radioId)
                assertNull(pending.lookupFailure.value)
            } finally { h.container.close() }
        }
    }

    @Test fun retryCompletionRejectsGraphOrRevisionReplacementDuringActualRoomRead() = runTest {
        for (replaceGraph in listOf(false, true)) {
            var failLookup = false
            var holdLookup = false
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val recoveringStore = object : PersistenceStoreProtocol by store {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    if (failLookup) throw IllegalStateException("store unavailable")
                    return store.fetchContact(radioId, publicKey)
                }
            }
            val h = ContainerHarness(this, store, persistenceOverride = recoveringStore)
            h.links.configureRadio = { it.knownContacts += contactFrame(key(20), "Saved peer") }
            val delegate = ContainerDeepLinkEnvironment(h.container, h.container.navigation)
            val queriedRadios = mutableListOf<RadioId>()
            val environment = object : AppDeepLinkEnvironment by delegate {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    val found = delegate.fetchContact(radioId, publicKey)
                    queriedRadios += radioId
                    if (holdLookup) {
                        assertNotNull(found, "The suspended retry must have read its real Room row")
                        readStarted.complete(Unit)
                        releaseRead.await()
                    }
                    return found
                }
            }
            try {
                val started = h.container.start()
                h.eventually("suspended retry fixture starts") { started.isCompleted }
                val connecting = async { h.manager.connect(h.target()) }
                val navigation = h.container.navigation
                h.eventually("suspended retry origin binds") {
                    connecting.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                        navigation.state.value.navigationReady
                }
                connecting.await()
                val first = assertNotNull(h.sessions.current)
                val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
                pending.markReady()
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Alias", key(20), ContactType.REPEATER)
                val delivery = PendingExternalRoute.Delivery("held-retry", uri)
                failLookup = true
                pending.submit(delivery)
                val observed = assertNotNull(pending.lookupFailure.value)
                failLookup = false
                holdLookup = true
                val retrying = async { pending.retry(observed.deliveryId, observed.attempt) }
                readStarted.await()
                if (replaceGraph) {
                    val replacing = async { h.manager.rebuildSession(assertNotNull(h.appState.connectedDevice).id) }
                    h.eventually("replacement graph binds during retry read") {
                        val next = h.sessions.current
                        replacing.isCompleted && next != null && next !== first &&
                            navigation.state.value.boundSessionToken == next.token &&
                            navigation.state.value.navigationReady
                    }
                    replacing.await()
                } else {
                    com.meshcoreone.android.feature.chats.list.ContactLinkPublications().use { publications ->
                        h.container.captureContactLinkContext(key(30), publications)
                    }
                }
                val beforeCompletion = navigation.currentState
                releaseRead.complete(Unit)
                val stale = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(retrying.await())
                assertIs<com.meshcoreone.android.app.deeplinks.ContactLookupContextStale>(stale.failure.cause)
                assertEquals(listOf(first.token.radioId), queriedRadios)
                assertEquals(beforeCompletion, navigation.currentState)
                assertEquals(delivery, pending.snapshot())
                assertTrue(pending.consumedDeliveryIds().isEmpty())
                assertNotNull(pending.lookupFailure.value)
            } finally {
                releaseRead.complete(Unit)
                h.container.close()
            }
        }
    }

    @Test fun initialViewIntentIsHeldUntilActualContainerBinding() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = ContainerHarness(this, store)
        val host = NavigationHostViewModel()
        val retained = ViewModelStore().apply { put("navigation", host) }
        try {
            host.submitInitialViewIntent(Intent(Intent.ACTION_VIEW, Uri.parse(sanFrancisco)))
            testScheduler.runCurrent()
            assertNull(host.navigation.state.value.pendingMapFocus)
            assertEquals(sanFrancisco, host.pendingExternalRoute.snapshot()?.uri)
            host.bindDeepLinks(h.container)
            testScheduler.runCurrent()
            assertSame(h.container.navigation, host.navigation)
            assertEquals(focus, h.container.navigation.state.value.pendingMapFocus)
            assertEquals(AppTab.MAP, h.container.navigation.state.value.selectedTab)
            assertNull(host.pendingExternalRoute.snapshot())
        } finally {
            retained.clear()
            h.container.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun repeatedBindingAndReadinessCannotReplayTheConsumedHeldIntent() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = ContainerHarness(this, store)
        val host = NavigationHostViewModel()
        val retained = ViewModelStore().apply { put("navigation", host) }
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanFrancisco))
            host.submitInitialViewIntent(intent)
            testScheduler.runCurrent()
            host.bindDeepLinks(h.container)
            testScheduler.runCurrent()
            assertEquals(focus, h.container.navigation.state.value.pendingMapFocus)
            h.container.navigation.clearPendingMapFocus()
            h.container.navigation.selectTab(AppTab.NODES)
            host.bindDeepLinks(h.container)
            host.pendingExternalRoute.markReady()
            host.submitInitialViewIntent(intent)
            testScheduler.runCurrent()
            assertNull(h.container.navigation.state.value.pendingMapFocus)
            assertEquals(AppTab.NODES, h.container.navigation.state.value.selectedTab)
            assertNull(host.pendingExternalRoute.snapshot())
        } finally {
            retained.clear()
            h.container.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun newViewIntentAfterActualBindingRoutesImmediatelyToTheOriginalCoordinate() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val h = ContainerHarness(this, store)
        val host = NavigationHostViewModel()
        val retained = ViewModelStore().apply { put("navigation", host) }
        try {
            host.bindDeepLinks(h.container)
            testScheduler.runCurrent()
            host.submitNewViewIntent(Intent(Intent.ACTION_VIEW, Uri.parse(sanFrancisco)))
            testScheduler.runCurrent()
            assertEquals(focus, h.container.navigation.state.value.pendingMapFocus)
            assertEquals(AppTab.MAP, h.container.navigation.state.value.selectedTab)
            assertNull(host.pendingExternalRoute.snapshot())
        } finally {
            retained.clear()
            h.container.close()
            Dispatchers.resetMain()
        }
    }

    @Test fun actualExternalAndChatProjectionKeepPrivateHashtagWarningAndOptionalScope() = runTest {
        val h = ContainerHarness(this, store)
        try {
            val feature = h.container.chatsFeature
            val external = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, h.container.navigation))
            val chat = ChatLinkRouter(feature.list.links, backgroundScope)
            val secret = Bytes.fromHex("00112233445566778899AABBCCDDEEFF")
            for ((name, region) in listOf("#test" to null, "#avion-testing2" to "testregion")) {
                val url = "meshcore://channel/add?name=%23${name.drop(1)}&secret=${secret.hexString}" +
                    (region?.let { "&region_scope=$it" } ?: "")
                assertEquals(DeepLinkRouteOutcome.ConfirmationStaged, external.routeExternal(url))
                val request = assertNotNull(h.container.navigation.state.value.pendingChannelLink)
                assertEquals(name, request.name)
                assertEquals(secret, request.secret)
                assertEquals(region, request.regionScope)
                assertTrue(request.hasHashtagSecretMismatch)
                h.container.navigation.clearPendingChannelLink()
                assertTrue(chat.route(url))
                testScheduler.runCurrent()
                assertEquals(request, h.container.navigation.state.value.pendingChannelLink)
                h.container.navigation.clearPendingChannelLink()
            }
            assertTrue(h.links.radios.isEmpty(), "Parsing and staging must not connect a radio")
        } finally { h.container.close() }
    }

    @Test fun externalDeliveryRejectsBothRetiredLookupAndUnboundReplacementGraphWithoutStoreAccess() = runTest {
        val uiScheduler = TestCoroutineScheduler()
        val mainScope = CoroutineScope(backgroundScope.coroutineContext + uiScheduler + StandardTestDispatcher(uiScheduler))
        val h = ContainerHarness(this, store, mainScope = mainScope)
        val lookupRelease = CompletableDeferred<Unit>()
        val replacementHandshake = CompletableDeferred<Unit>()
        val reads = AtomicInteger()
        var replacing = false
        h.links.configureRadio = {
            if (replacing) it.connectGate = replacementHandshake
            it.knownContacts += contactFrame(key(20), "Saved peer")
        }
        try {
            val started = h.container.start()
            h.eventually("external fixture starts") { uiScheduler.runCurrent(); started.isCompleted }
            val connecting = async { h.manager.connect(h.target()) }
            val n = h.container.navigation
            h.eventually("initial graph and navigation are ready") {
                uiScheduler.runCurrent()
                connecting.isCompleted && h.manager.connectionState == DeviceConnectionState.READY &&
                    n.state.value.navigationReady
            }
            connecting.await()
            val first = assertNotNull(h.sessions.current)
            val radio = first.token.radioId
            val saved = assertNotNull(store.fetchContact(radio, key(20)))
            val current = AppDeepLinkRouter(ContainerDeepLinkEnvironment(h.container, n))
            for (seed in listOf(20, 30)) {
                val uri = h.container.nodesFeature.uriCodec.exportContactUri("Untrusted alias", key(seed), ContactType.REPEATER)
                assertEquals(
                    if (seed == 20) DeepLinkRouteOutcome.Navigated else DeepLinkRouteOutcome.ConfirmationStaged,
                    current.routeExternal(uri),
                )
                if (seed == 20) {
                    assertEquals(saved, n.currentState.selectedContact)
                    assertEquals(AppTab.NODES, n.currentState.selectedTab)
                } else {
                    val staged = assertNotNull(n.currentState.pendingContactLink)
                    assertEquals(key(seed), staged.publicKey)
                    assertEquals(ContactType.REPEATER, staged.type)
                    assertEquals(radio, staged.targetRadioId)
                    assertEquals(AppTab.CHATS, n.currentState.selectedTab)
                    n.clearPendingContactLink()
                }
            }
            n.navigateToMap(focus.latitude, focus.longitude)
            val external = AppDeepLinkRouter(object : AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, n) {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    reads.incrementAndGet()
                    val found = store.fetchContact(radioId, publicKey)
                    lookupRelease.await()
                    return found
                }
            })
            val pending = listOf(20, 30).map { PendingExternalRoute(external::routeExternal).also { it.markReady() } }
            val deliveries = listOf(20, 30).map {
                PendingExternalRoute.Delivery(
                    "retired-$it",
                    h.container.nodesFeature.uriCodec.exportContactUri("Untrusted alias", key(it), ContactType.REPEATER),
                )
            }
            val routes = pending.zip(deliveries).map { (owner, delivery) -> async { owner.submit(delivery) } }
            h.eventually("real external found and absent lookups are held") { reads.get() == 2 }
            val boundA = n.currentState.boundSessionToken
            assertEquals(first.token, boundA)
            replacing = true
            val rebuilding = async { h.manager.rebuildSession(assertNotNull(h.appState.connectedDevice).id) }
            h.eventually("retiring callbacks drain before the held replacement handshake") {
                uiScheduler.runCurrent()
                h.links.radios.size == 2 && h.sessions.current == null
            }
            val retiredNavigation = n.currentState
            replacementHandshake.complete(Unit)
            h.eventually("same-radio runtime B precedes its queued navigation binding") {
                val next = h.sessions.current
                next != null && next !== first && h.manager.snapshot.value.token == next.token
            }
            val second = assertNotNull(h.sessions.current)
            assertEquals(radio, second.token.radioId)
            assertNotEquals(first.token, second.token)
            assertNotEquals(second.token, n.currentState.boundSessionToken)
            assertEquals(retiredNavigation.boundSessionToken, n.currentState.boundSessionToken)
            assertEquals(retiredNavigation.generation, n.currentState.generation)
            val before = n.currentState

            lookupRelease.complete(Unit)
            h.eventually("both retired external deliveries finish") { routes.all { it.isCompleted } }
            routes.forEach {
                val routed = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(it.await())
                assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, routed.outcome)
            }
            val mixedDelivery = PendingExternalRoute.Delivery("mixed-B", deliveries.last().uri)
            val mixed = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.last().submit(mixedDelivery))
            assertEquals(DeepLinkRouteOutcome.ContactLookupRejected, mixed.outcome)
            assertEquals(2, reads.get(), "A provisional successor must reject before any external store read")
            assertEquals(before, n.currentState)
            assertEquals(focus, n.currentState.pendingMapFocus)
            assertEquals(AppTab.MAP, n.currentState.selectedTab)
            assertNull(n.currentState.pendingContactLink)
            pending.zip(deliveries).forEach { (owner, delivery) ->
                assertTrue(delivery.id in owner.consumedDeliveryIds())
                assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(owner.submit(delivery))
            }
            assertTrue(mixedDelivery.id in pending.last().consumedDeliveryIds())
            h.eventually("replacement navigation binding catches up") {
                uiScheduler.runCurrent()
                rebuilding.isCompleted && n.state.value.boundSessionToken == second.token &&
                    n.state.value.navigationReady
            }
            rebuilding.await()
        } finally {
            lookupRelease.complete(Unit)
            replacementHandshake.complete(Unit)
            val closing = async { h.container.close() }
            h.eventually("external fixture cleanup drains its controlled UI queue") {
                uiScheduler.runCurrent()
                closing.isCompleted
            }
            closing.await()
        }
    }

    @Test fun externalSelfDeliveryUsesImmutableFirstGraphIdentityBeforeDevicePublication() = runTest {
        val saving = CompletableDeferred<Unit>()
        val publication = CompletableDeferred<Unit>()
        val persistence = object : PersistenceStoreProtocol by store {
            override suspend fun saveDevice(device: DeviceDTO) {
                saving.complete(Unit)
                publication.await()
                store.saveDevice(device)
            }
        }
        val h = ContainerHarness(this, store, persistenceOverride = persistence)
        val reads = AtomicInteger()
        try {
            val n = h.container.navigation
            n.navigateToMap(focus.latitude, focus.longitude)
            val connecting = async { h.manager.connect(h.target()) }
            h.eventually("first immutable graph exists ahead of device publication") {
                saving.isCompleted && h.sessions.current != null && h.manager.snapshot.value.token == null
            }
            assertNull(h.appState.connectedDevice)
            assertNull(n.state.value.radioId)
            val external = AppDeepLinkRouter(object : AppDeepLinkEnvironment by ContainerDeepLinkEnvironment(h.container, n) {
                override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
                    reads.incrementAndGet()
                    return store.fetchContact(radioId, publicKey)
                }
            })
            val pending = PendingExternalRoute(external::routeExternal)
            pending.markReady()
            val delivery = PendingExternalRoute.Delivery(
                "provisional-self",
                h.container.nodesFeature.uriCodec.exportContactUri("Untrusted self alias", key(7), ContactType.REPEATER),
            )
            val routed = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.submit(delivery))
            assertEquals(DeepLinkRouteOutcome.IgnoredSelfContact, routed.outcome)
            assertEquals(0, reads.get())
            assertEquals(AppTab.CHATS, n.state.value.selectedTab)
            assertEquals(focus, n.state.value.pendingMapFocus)
            assertNull(n.state.value.pendingContactLink)
            assertNull(n.state.value.pendingContactDetail)
            assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
            assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
            publication.complete(Unit)
            h.eventually("first graph finishes device publication") { connecting.isCompleted }
            connecting.await()
            assertTrue(h.links.radios.all { firmware -> firmware.sent.none { it[0].toInt() == 0x09 } })
        } finally {
            publication.complete(Unit)
            h.container.close()
        }
    }
}
