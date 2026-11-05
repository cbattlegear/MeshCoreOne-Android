// AndroidOnly: WP-405 App-level routing, identity, malformed input, cancellation, and command-safety cases.
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest

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
        val environment = FakeEnvironment().apply { contactFailure = CancellationException("stop") }
        val uri = "meshcore://contact/add?name=Alice&public_key=${"AB".repeat(32)}&type=1"
        var cancelled = false
        try {
            AppDeepLinkRouter(environment).routeExternal(uri)
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertEquals(true, cancelled)
        assertNull(environment.pendingContact)
    }

    private class FakeEnvironment(
        override val connectedDevicePublicKey: Bytes? = null,
    ) : AppDeepLinkEnvironment {
        override var selectedTab: AppTab = AppTab.NODES
        override val currentRadioId: RadioId? = RadioId(UUID(0, 1))
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
