// AndroidOnly: WP-405 Issue #504 submission-time supersession through the real URI router and navigation consumer.
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.app.navigation.ChannelLinkRequest
import com.meshcoreone.android.app.navigation.ContactLinkRequest
import com.meshcoreone.android.app.navigation.HashtagJoinRequest
import com.meshcoreone.android.app.navigation.MapFocusRequest
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class ExternalRouteReplacementTest {
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
            else assertEquals(ContactLinkRequest("Alice", key, ContactType.CHAT), state.pendingContactLink)
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
        assertEquals(ContactLinkRequest("Alice", key, ContactType.CHAT), environment.navigation.state.value.pendingContactLink)
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
            else assertEquals(ContactLinkRequest("Alice", key, ContactType.CHAT), state.pendingContactLink)
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

    private fun assertNoContactResult(environment: Environment) {
        val state = environment.navigation.state.value
        assertNull(state.pendingContactLink)
        assertNull(state.pendingContactDetail)
        assertNull(state.selectedContact)
    }

    private class Environment : AppDeepLinkEnvironment {
        val navigation = NavigationCoordinator()
        var contactLookup: suspend () -> ContactDTO? = { null }
        var channelLookup: suspend () -> List<ChannelDTO> = { emptyList() }
        var beforeContactCommit: () -> Unit = {}
        override var selectedTab: AppTab
            get() = navigation.state.value.selectedTab
            set(value) = navigation.selectTab(value)
        override val currentRadioId: RadioId = radio
        override val connectedDevicePublicKey: Bytes? = null
        override fun navigateToMap(latitude: Double, longitude: Double) = navigation.navigateToMap(latitude, longitude)
        override fun navigateToContactDetail(contact: ContactDTO) {
            beforeContactCommit()
            navigation.navigateToContactDetail(contact)
        }
        override fun navigateToChannel(channel: ChannelDTO) = navigation.navigateToChannel(channel)
        override fun stageContactConfirmation(link: MeshCoreDeepLink.Contact) {
            beforeContactCommit()
            navigation.stageContactLink(ContactLinkRequest(link.name, link.publicKey, link.contactType))
        }
        override fun stageChannelConfirmation(link: MeshCoreDeepLink.Channel) =
            navigation.stageChannelLink(
                ChannelLinkRequest(link.name, link.secret, link.regionScope, link.hasHashtagSecretMismatch),
            )
        override fun stageHashtagConfirmation(link: MeshCoreDeepLink.Hashtag) =
            navigation.stageHashtag(HashtagJoinRequest(link.fullName))
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
        fun contact() = ContactDTO(
            id = UUID(0, 2), radioId = radio, publicKey = key, name = "Alice",
            typeRawValue = ContactType.CHAT.rawValue, lastHeardTimestamp = null,
        )
    }
}
