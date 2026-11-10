// AndroidOnly: WP-405 App-level routing, identity, malformed input, cancellation, and command-safety cases.
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.app.navigation.ContactLinkLookupResolution
import com.meshcoreone.android.app.navigation.ContactLinkResolvedCompletion
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.list.ContactLinkCaptureDisposition
import com.meshcoreone.android.feature.chats.list.ContactLinkCompletion
import com.meshcoreone.android.feature.chats.list.ContactLinkContext
import com.meshcoreone.android.feature.chats.list.ContactLinkCancellation
import com.meshcoreone.android.feature.chats.list.ContactLinkPublication
import com.meshcoreone.android.feature.chats.list.ContactLinkPublications
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

class AppDeepLinkRouterTest {
    @Test
    fun malformedAndAdministrativeUrisAreRejectedWithoutStateChanges() = runTest {
        val environment = FakeEnvironment().apply { selectedTab = AppTab.NODES }
        val router = AppDeepLinkRouter(environment)
        listOf(
            "https://example.com", "meshcore://admin/reboot", "meshcore://channel/delete?index=0",
            "meshcore://contact/add?name=bad", "meshcoreone://status", "meshcoreone://mention/Alice",
        ).forEach { uri ->
            assertIs<DeepLinkRouteOutcome.Rejected>(router.routeExternal(uri), uri)
            assertEquals(AppTab.NODES, environment.selectedTab)
        }
        assertEquals(0, environment.mutations)
    }

    @Test
    fun contactIdentityIsIgnoredExistingOrStagedAsConfirmation() = runTest {
        val key = Bytes(ByteArray(32) { 0xAB.toByte() })
        val uri = "meshcore://contact/add?name=Alice&public_key=${key.hexString}&type=1"
        val self = FakeEnvironment(connectedDevicePublicKey = key)
        assertIs<DeepLinkRouteOutcome.IgnoredSelfContact>(AppDeepLinkRouter(self).routeExternal(uri))
        assertNull(self.pendingContact)

        val existing = contact(key)
        val known = FakeEnvironment().apply { contacts = listOf(existing) }
        assertIs<DeepLinkRouteOutcome.Navigated>(AppDeepLinkRouter(known).routeExternal(uri))
        assertEquals(existing, known.contactDetail)

        val fresh = FakeEnvironment()
        assertIs<DeepLinkRouteOutcome.ConfirmationStaged>(AppDeepLinkRouter(fresh).routeExternal(uri))
        assertEquals("Alice", fresh.pendingContact?.name)
        assertEquals(0, fresh.adminCommandCount)
    }

    @Test
    fun channelAndHashtagExistingMatchesNavigateOtherwiseRequireConfirmation() = runTest {
        val secret = Bytes(ByteArray(16) { 0xCD.toByte() })
        val existing = channel("#General", secret)
        val channelUri = "meshcore://channel/add?name=Ops&secret=${secret.hexString}&region_scope=testregion"
        val known = FakeEnvironment().apply { channels = listOf(existing) }
        assertIs<DeepLinkRouteOutcome.Navigated>(AppDeepLinkRouter(known).routeExternal(channelUri))
        assertEquals(existing, known.navigatedChannel)
        assertNull(known.pendingChannel)

        val fresh = FakeEnvironment()
        assertIs<DeepLinkRouteOutcome.ConfirmationStaged>(AppDeepLinkRouter(fresh).routeExternal(channelUri))
        assertEquals("testregion", fresh.pendingChannel?.regionScope)
        assertIs<DeepLinkRouteOutcome.ConfirmationStaged>(
            AppDeepLinkRouter(fresh).routeExternal("meshcoreone://hashtag/general"),
        )
        assertEquals("#general", fresh.pendingHashtag?.fullName)
        assertEquals(0, fresh.adminCommandCount)
    }

    @Test
    fun mapRoutingEndsOnMapWhileConfirmationRoutesEndOnChats() = runTest {
        val environment = FakeEnvironment().apply { selectedTab = AppTab.NODES }
        val router = AppDeepLinkRouter(environment)
        assertIs<DeepLinkRouteOutcome.Navigated>(router.routeExternal("meshcore://map?lat=1&lon=2"))
        assertEquals(AppTab.MAP, environment.selectedTab)
        environment.selectedTab = AppTab.NODES
        assertIs<DeepLinkRouteOutcome.ConfirmationStaged>(
            router.routeExternal("meshcoreone://hashtag/general"),
        )
        assertEquals(AppTab.CHATS, environment.selectedTab)
    }

    @Test
    fun lookupCancellationPropagatesAndNeverStagesAConfirmation() = runTest {
        val cancellation = CancellationException("stop")
        val environment = FakeEnvironment().apply { contactFailure = cancellation }
        val uri = "meshcore://contact/add?name=Alice&public_key=${"AB".repeat(32)}&type=1"
        var cancelled = false
        try {
            AppDeepLinkRouter(environment).routeExternal(uri)
        } catch (failure: CancellationException) {
            assertSame(cancellation, failure)
            cancelled = true
        }
        assertEquals(true, cancelled)
        assertNull(environment.pendingContact)
    }

    @Test
    fun contactLookupFailureIsUncommittedAndCanRetryWithoutImportConfirmation() = runTest {
        val failure = IllegalStateException("store unavailable")
        val environment = FakeEnvironment().apply { contactFailure = failure }
        val router = AppDeepLinkRouter(environment)
        val guard = PendingExternalRoute.CommitGuard()
        val outcome = withContext(guard) { router.routeExternal(contactUri()) }
        assertSame(failure, assertIs<DeepLinkRouteOutcome.ContactLookupFailed>(outcome).cause)
        assertFalse(guard.wasCommitted())
        assertNull(environment.completedContext)
        assertNull(environment.pendingContact)
        assertEquals(0, environment.mutations)

        val pending = PendingExternalRoute(router::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("retry-contact", contactUri())
        val failed = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.submit(delivery))
        assertSame(failure, failed.failure.cause)
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        environment.contactFailure = null
        environment.contacts = listOf(contact(Bytes.fromHex("AB".repeat(32))))
        val recovered = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.retry())
        assertEquals(DeepLinkRouteOutcome.Navigated, recovered.outcome)
        assertNull(environment.pendingContact)
        assertNull(pending.snapshot())
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
        assertEquals(2, environment.captureCount, "Direct call and held delivery each capture once; retry reuses its capture")
    }

    @Test
    fun retryContextRejectionRetainsDeliveryWithoutReplacementLookupOrCommit() = runTest {
        val environment = FakeEnvironment().apply { contactFailure = IllegalStateException("store unavailable") }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("A", contactUri())
        pending.submit(delivery)
        val first = assertNotNull(pending.lookupFailure.value)
        val originalRadio = environment.currentRadioId
        environment.currentRadioId = RadioId(UUID(0, 2))
        environment.contactFailure = null
        val rejected = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(
            pending.retry(first.deliveryId, first.attempt),
        )
        assertIs<ContactLookupContextStale>(rejected.failure.cause)
        assertEquals(listOf(originalRadio), environment.lookupRadios)
        assertEquals(1, environment.captureCount)
        assertEquals(0, environment.mutations)
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        val stale = assertNotNull(pending.lookupFailure.value)
        assertTrue(stale.attempt > first.attempt)
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(pending.cancel(first.deliveryId, first.attempt))
        assertEquals(stale, pending.lookupFailure.value)
    }

    @Test
    fun retryCompletionRejectionRemainsUncommittedAfterOriginalLookup() = runTest {
        val environment = FakeEnvironment().apply { contactFailure = IllegalStateException("store unavailable") }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("A", contactUri())
        pending.submit(delivery)
        val observed = assertNotNull(pending.lookupFailure.value)
        environment.contactFailure = null
        environment.forcedCompletion = ContactLinkCompletion.StaleContext
        val result = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(
            pending.retry(observed.deliveryId, observed.attempt),
        )
        assertIs<ContactLookupContextStale>(result.failure.cause)
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        assertEquals(0, environment.mutations)
        assertEquals(1, environment.captureCount)
    }

    @Test
    fun repeatedTargetChangeIsBoundedAndRetainsDeliveryAndOriginalContextForExplicitRetry() = runTest {
        val environment = FakeEnvironment().apply { targetChanged = true }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("target-changed", contactUri())
        val first = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.submit(delivery))
        assertIs<ContactLookupTargetChanged>(first.failure.cause)
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
        assertEquals(0, environment.mutations)
        assertEquals(2, environment.resolutionCount)
        val observed = assertNotNull(pending.lookupFailure.value)
        environment.targetChanged = false
        environment.contacts = listOf(contact(Bytes.fromHex("AB".repeat(32))))
        val recovered = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(
            pending.retry(observed.deliveryId, observed.attempt),
        )
        assertEquals(DeepLinkRouteOutcome.Navigated, recovered.outcome)
        assertEquals(1, environment.captureCount)
        assertEquals(3, environment.resolutionCount)
        assertNull(pending.snapshot())
        assertNull(pending.lookupFailure.value)
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
        assertIs<PendingExternalRoute.SubmitOutcome.Superseded>(
            pending.cancel(observed.deliveryId, observed.attempt),
        )
    }

    @Test
    fun oneTargetChangeReresolvesAndRefetchesWithoutRecapturingOrExposingFalseAbsence() = runTest {
        val saved = contact(Bytes.fromHex("AB".repeat(32)))
        val environment = FakeEnvironment().apply {
            contacts = listOf(saved)
            targetChangesRemaining = 1
        }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("target-changes-once", contactUri())
        val result = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.submit(delivery))
        assertEquals(DeepLinkRouteOutcome.Navigated, result.outcome)
        assertEquals(1, environment.captureCount)
        assertEquals(2, environment.resolutionCount)
        assertEquals(listOf(saved.radioId, saved.radioId), environment.lookupRadios)
        assertEquals(saved, environment.contactDetail)
        assertNull(environment.pendingContact)
        assertNull(pending.lookupFailure.value)
        assertNull(pending.snapshot())
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
    }

    @Test
    fun precommitCancellationAlsoRetainsTheOriginalContactContext() = runTest {
        val environment = FakeEnvironment().apply { contactFailure = CancellationException("lookup canceled") }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("A", contactUri())
        assertFailsWith<CancellationException> { pending.submit(delivery) }
        assertNull(pending.lookupFailure.value)
        val originalRadio = environment.currentRadioId
        environment.currentRadioId = RadioId(UUID(0, 2))
        environment.contactFailure = null
        val stale = assertIs<PendingExternalRoute.SubmitOutcome.RetryableFailure>(pending.retry())
        assertIs<ContactLookupContextStale>(stale.failure.cause)
        assertEquals(1, environment.captureCount)
        assertEquals(listOf(originalRadio), environment.lookupRadios)
        assertEquals(delivery, pending.snapshot())
        assertTrue(pending.consumedDeliveryIds().isEmpty())
    }

    @Test
    fun contactResolutionRejectsChangedRadioWithoutReadingTheReplacement() = runTest {
        val key = Bytes(ByteArray(32) { 0xAB.toByte() })
        val environment = FakeEnvironment()
        val initiatingRadio = environment.currentRadioId
        environment.afterCapture = {
            environment.currentRadioId = RadioId(UUID(0, 2))
            environment.connectedDevicePublicKey = key
        }
        val result = AppDeepLinkRouter(environment).routeExternal(contactUri(key))
        assertIs<DeepLinkRouteOutcome.ContactLookupRejected>(result)
        assertTrue(environment.lookupRadios.isEmpty())
        assertNull(environment.completedContext)
        assertNull(environment.pendingContact)
        assertNotEquals(initiatingRadio, environment.currentRadioId)
    }

    @Test
    fun staleCaptureRejectsBeforeLookupAndTerminallyAccountsForItsDelivery() = runTest {
        val environment = FakeEnvironment().apply { staleCapture = true }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("stale", contactUri())
        val result = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.submit(delivery))
        assertIs<DeepLinkRouteOutcome.ContactLookupRejected>(result.outcome)
        assertEquals(0, environment.lookupRadios.size)
        assertNull(environment.completedContext)
        assertEquals(0, environment.mutations)
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
    }

    @Test
    fun typedContactCompletionNeverMapsStaleOrWrongTargetToNavigation() = runTest {
        val expected = mapOf(
            ContactLinkCompletion.Navigated to DeepLinkRouteOutcome.Navigated,
            ContactLinkCompletion.ConfirmationStaged to DeepLinkRouteOutcome.ConfirmationStaged,
            ContactLinkCompletion.IgnoredSelfContact to DeepLinkRouteOutcome.IgnoredSelfContact,
            ContactLinkCompletion.StaleContext to DeepLinkRouteOutcome.ContactLookupRejected,
            ContactLinkCompletion.WrongTarget to DeepLinkRouteOutcome.ContactLookupRejected,
        )
        for ((completion, outcome) in expected) {
            val environment = FakeEnvironment().apply { forcedCompletion = completion }
            assertEquals(outcome, AppDeepLinkRouter(environment).routeExternal(contactUri()))
            assertEquals(0, environment.mutations)
        }
    }

    @Test
    fun captureAndCompletionReleaseSeparateBatchesOutsideTheDeliveryGuard() = runTest {
        for (external in listOf(false, true)) {
            val guard = PendingExternalRoute.CommitGuard()
            val environment = FakeEnvironment()
            val releases = mutableListOf<String>()
            environment.onPublication = { phase ->
                assertFalse(Thread.holdsLock(guard), phase)
                releases += phase
            }
            environment.afterCapture = { assertTrue(Thread.holdsLock(guard)) }
            environment.beforeCompletion = { assertTrue(Thread.holdsLock(guard)) }
            environment.beforeLookup = { assertEquals(listOf("capture"), releases) }
            withContext(guard) {
                val router = AppDeepLinkRouter(environment)
                assertIs<DeepLinkRouteOutcome.ConfirmationStaged>(
                    if (external) router.routeExternal(contactUri()) else router.route(contactUri()),
                )
            }
            assertEquals(listOf("capture", "completion"), releases)
            assertNotSame(environment.captureBatch, environment.completionBatch)
            assertTrue(guard.wasCommitted())
            assertEquals(if (external) AppTab.CHATS else AppTab.NODES, environment.selectedTab)
        }
    }

    @Test
    fun selfContactStillUsesGuardedCompletionAndTerminalDeliveryAccounting() = runTest {
        val key = Bytes(ByteArray(32) { 0xAB.toByte() })
        val environment = FakeEnvironment(connectedDevicePublicKey = key)
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("self", contactUri(key))
        val result = assertIs<PendingExternalRoute.SubmitOutcome.Routed>(pending.submit(delivery))
        assertIs<DeepLinkRouteOutcome.IgnoredSelfContact>(result.outcome)
        assertEquals(0, environment.lookupRadios.size)
        assertEquals(ContactLinkCaptureDisposition.IgnoredSelfContact, environment.completedContext?.disposition)
        assertEquals(AppTab.CHATS, environment.selectedTab)
        assertNull(environment.pendingContact)
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
    }

    @Test
    fun publicationFailureCannotMaskAnOriginalCaptureOrCompletionCancellation() = runTest {
        for (phase in listOf("capture", "completion")) {
            val cancellation = CancellationException("$phase cancelled")
            val cleanup = IllegalStateException("$phase release")
            val guard = PendingExternalRoute.CommitGuard()
            val releases = mutableListOf<String>()
            val environment = FakeEnvironment().apply {
                if (phase == "capture") afterCapture = { throw cancellation }
                else beforeCompletion = { throw cancellation }
                onPublication = {
                    assertFalse(Thread.holdsLock(guard))
                    releases += it
                    if (it == phase) throw cleanup
                }
            }
            val thrown = assertFailsWith<CancellationException> {
                withContext(guard) { AppDeepLinkRouter(environment).routeExternal(contactUri()) }
            }
            assertCoroutineFailureIdentity(cancellation, thrown)
            assertEquals(listOf(cleanup), cancellation.suppressed.toList())
            assertEquals(if (phase == "capture") listOf("capture") else listOf("capture", "completion"), releases)
            assertNull(environment.pendingContact)
            assertFalse(guard.wasCommitted())
        }
    }

    @Test
    fun completionReleaseFailureLeavesTheExactCommittedDeliveryConsumed() = runTest {
        val cleanup = IllegalStateException("completion release")
        val environment = FakeEnvironment().apply {
            onPublication = { if (it == "completion") throw cleanup }
        }
        val pending = PendingExternalRoute(AppDeepLinkRouter(environment)::routeExternal)
        pending.markReady()
        val delivery = PendingExternalRoute.Delivery("committed-before-release", contactUri())
        assertCoroutineFailureIdentity(cleanup, assertFailsWith<IllegalStateException> { pending.submit(delivery) })
        assertEquals("Alice", environment.pendingContact?.name)
        assertNull(pending.snapshot())
        assertEquals(setOf(delivery.id), pending.consumedDeliveryIds())
        assertIs<PendingExternalRoute.SubmitOutcome.Duplicate>(pending.submit(delivery))
    }

    private fun assertCoroutineFailureIdentity(expected: Throwable, actual: Throwable) {
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        assertSame(expected, if (actual === expected) actual else actual.cause,
            "only the original failure or its direct coroutine recovery copy is accepted")
    }

    private fun contactUri(key: Bytes = Bytes(ByteArray(32) { 0xAB.toByte() })) =
        "meshcore://contact/add?name=Alice&public_key=${key.hexString}&type=1"

    private data class CapturedContext(
        val publicKey: Bytes,
        override val radioId: RadioId?,
        override val selfPublicKey: Bytes?,
        override val disposition: ContactLinkCaptureDisposition,
    ) : ContactLinkContext

    private class FakeEnvironment(
        override var connectedDevicePublicKey: Bytes? = null,
    ) : AppDeepLinkEnvironment {
        override var selectedTab: AppTab = AppTab.NODES
        override var currentRadioId: RadioId? = RadioId(UUID(0, 1))
        var contacts = emptyList<ContactDTO>()
        var channels = emptyList<ChannelDTO>()
        var contactFailure: Exception? = null
        var map: Pair<Double, Double>? = null
        var contactDetail: ContactDTO? = null
        var navigatedChannel: ChannelDTO? = null
        var pendingContact: MeshCoreDeepLink.Contact? = null
        var pendingChannel: MeshCoreDeepLink.Channel? = null
        var pendingHashtag: MeshCoreDeepLink.Hashtag? = null
        var mutations = 0
        var adminCommandCount = 0
        var staleCapture = false
        var contextCurrent = true
        var captureCount = 0
        var forcedCompletion: ContactLinkCompletion? = null
        var targetChanged = false
        var targetChangesRemaining = 0
        var resolutionCount = 0
        var afterCapture: () -> Unit = {}
        var beforeCompletion: () -> Unit = {}
        var beforeLookup: () -> Unit = {}
        var onPublication: (String) -> Unit = {}
        val lookupRadios = mutableListOf<RadioId>()
        var completedContext: ContactLinkContext? = null
        var captureBatch: ContactLinkPublications? = null
        var completionBatch: ContactLinkPublications? = null

        override fun captureContactLinkContext(
            publicKey: Bytes, publications: ContactLinkPublications,
        ): ContactLinkContext {
            captureCount++
            captureBatch = publications
            publications.defer(object : ContactLinkPublication {
                override fun release() = onPublication("capture")
            })
            val captured = CapturedContext(
                publicKey, currentRadioId, connectedDevicePublicKey,
                when {
                    staleCapture -> ContactLinkCaptureDisposition.StaleContext
                    publicKey == connectedDevicePublicKey -> ContactLinkCaptureDisposition.IgnoredSelfContact
                    else -> ContactLinkCaptureDisposition.Lookup
                },
            )
            afterCapture()
            return captured
        }

        override fun resolveContactLinkContext(
            context: ContactLinkContext, publicKey: Bytes,
        ): ContactLinkLookupResolution {
            resolutionCount++
            return if (contextCurrent && context.radioId == currentRadioId && (context as CapturedContext).publicKey == publicKey)
                ContactLinkLookupResolution.Current(context.radioId, context.selfPublicKey, origin = context)
            else ContactLinkLookupResolution.Stale
        }

        override fun completeResolvedContactLookup(
            context: ContactLinkContext, target: ContactLinkLookupResolution.Current,
            link: MeshCoreDeepLink.Contact, existing: ContactDTO?,
            selectChats: Boolean, publications: ContactLinkPublications, cancellation: ContactLinkCancellation,
        ): ContactLinkResolvedCompletion {
            assertSame(context, target.origin)
            assertEquals(context.radioId, target.radioId)
            assertEquals(context.selfPublicKey, target.selfPublicKey)
            if (targetChanged || targetChangesRemaining > 0) {
                if (targetChangesRemaining > 0) targetChangesRemaining--
                return ContactLinkResolvedCompletion.TargetChanged
            }
            return cancellation.commitIfActive {
                ContactLinkResolvedCompletion.Completed(
                    completeLookup(context, link, existing, selectChats, publications),
                )
            }
        }

        private fun completeLookup(
            context: ContactLinkContext, link: MeshCoreDeepLink.Contact, existing: ContactDTO?,
            selectChats: Boolean, publications: ContactLinkPublications,
        ): ContactLinkCompletion {
            completedContext = context
            completionBatch = publications
            publications.defer(object : ContactLinkPublication {
                override fun release() = onPublication("completion")
            })
            beforeCompletion()
            forcedCompletion?.let { return it }
            if (context.disposition == ContactLinkCaptureDisposition.IgnoredSelfContact) {
                if (selectChats) selectedTab = AppTab.CHATS
                return ContactLinkCompletion.IgnoredSelfContact
            }
            return if (existing != null) {
                navigateToContactDetail(existing)
                ContactLinkCompletion.Navigated
            } else {
                stageContactConfirmation(link, selectChats)
                ContactLinkCompletion.ConfirmationStaged
            }
        }

        override fun navigateToMap(latitude: Double, longitude: Double) {
            mutations++; map = latitude to longitude; selectedTab = AppTab.MAP
        }
        override fun navigateToContactDetail(contact: ContactDTO) { mutations++; contactDetail = contact; selectedTab = AppTab.NODES }
        override fun navigateToChannel(channel: ChannelDTO) { mutations++; navigatedChannel = channel; selectedTab = AppTab.CHATS }
        override fun stageContactConfirmation(link: MeshCoreDeepLink.Contact, selectChats: Boolean) {
            if (selectChats) selectedTab = AppTab.CHATS
            mutations++
            pendingContact = link
        }
        override fun stageChannelConfirmation(link: MeshCoreDeepLink.Channel, selectChats: Boolean) {
            if (selectChats) selectedTab = AppTab.CHATS
            mutations++
            pendingChannel = link
        }
        override fun stageHashtagConfirmation(link: MeshCoreDeepLink.Hashtag, selectChats: Boolean) {
            if (selectChats) selectedTab = AppTab.CHATS
            mutations++
            pendingHashtag = link
        }
        override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes): ContactDTO? {
            lookupRadios += radioId
            beforeLookup()
            contactFailure?.let { throw it }
            return contacts.firstOrNull { it.publicKey == publicKey }
        }
        override suspend fun fetchChannels(radioId: RadioId): List<ChannelDTO> = channels
    }

    private fun contact(key: Bytes) = ContactDTO(
        radioId = RadioId(UUID(0, 1)),
        publicKey = key,
        name = "Alice",
        typeRawValue = ContactType.CHAT.rawValue,
        lastHeardTimestamp = null,
    )

    private fun channel(name: String, secret: Bytes) = ChannelDTO(
        radioId = RadioId(UUID(0, 1)), index = 1u, name = name, secret = secret,
    )
}
