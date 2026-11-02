// AndroidOnly: WP-311 Native coverage of contact detail confirmations, radio actions, ping and path-discovery push routing.
package com.meshcoreone.android.feature.nodes.detail

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PathInfo
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesAdvertEvent
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.path.PathDiscoveryResult
import com.meshcoreone.android.feature.nodes.support.FakeContactService
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Fixtures.contact
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.scenario
import com.meshcoreone.android.feature.nodes.support.settle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import org.junit.Test

class ContactDetailNativeTest {
    @Test
    fun `blocking waits for explicit confirmation while unblocking runs at once`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId), name = "Alice")
        harness.store.contacts += alice
        val detail = ContactDetailStateHolder(alice, harness.dependencies, scope)
        detail.requestToggleBlock()
        assertEquals(DetailConfirmation.Block("Alice"), detail.state.value.pendingConfirmation)
        assertTrue(harness.contactService.calls.isEmpty())
        detail.cancelConfirmation()
        assertTrue(harness.contactService.calls.isEmpty())
        detail.requestToggleBlock()
        assertFalse(detail.confirm())
        assertEquals(listOf("prefs:nickname=null,isBlocked=true"), harness.contactService.calls)
        assertTrue(detail.state.value.contact.isBlocked)
        detail.requestToggleBlock()
        assertNull(detail.state.value.pendingConfirmation)
        assertEquals("prefs:nickname=null,isBlocked=false", harness.contactService.calls.last())
    }

    @Test
    fun `delete is confirmed then removes the node and dismisses`() = scenario {
        val harness = Harness(clock).connect()
        val relay = contact(name = "Relay", type = ContactType.REPEATER)
        val detail = ContactDetailStateHolder(relay, harness.dependencies, scope)
        assertFalse(detail.confirm())
        detail.requestDelete()
        val pending = assertIs<DetailConfirmation.Delete>(detail.state.value.pendingConfirmation)
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_alert_delete_title, NodesMessage.res(R.string.l10n_app_contacts_contacts_nodekind_repeater)), pending.title)
        assertTrue(detail.confirm())
        assertEquals(listOf("remove"), harness.contactService.calls)
    }

    @Test
    fun `delete without a radio reports services unavailable instead of dismissing`() = scenario {
        val detail = ContactDetailStateHolder(contact(name = "Alice"), Harness(clock).dependencies, scope)
        detail.requestDelete()
        assertFalse(detail.confirm())
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_error_servicesunavailable), detail.state.value.errorMessage)
    }

    @Test
    fun `clear messages clears notifications and calls back before dismissing`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(name = "Alice")
        var cleared = false
        val detail = ContactDetailStateHolder(alice, harness.dependencies, scope, onClearMessages = { cleared = true })
        detail.requestClearMessages()
        assertTrue(detail.confirm())
        assertEquals(listOf("clearMessages"), harness.contactService.calls)
        assertEquals(listOf("remove:${alice.id}", "badge"), harness.notifications.calls)
        assertTrue(cleared)
    }

    @Test
    fun `failed clear keeps the screen and resets the spinner`() = scenario {
        val harness = Harness(clock).connect()
        harness.contactService.clearMessagesBehavior = { error("db") }
        val detail = ContactDetailStateHolder(contact(name = "Alice"), harness.dependencies, scope)
        detail.requestClearMessages()
        assertFalse(detail.confirm())
        assertFalse(detail.state.value.isClearingMessages)
        assertEquals(NodesMessage.Text("failed: db"), detail.state.value.errorMessage)
    }

    @Test
    fun `share via advert shows success briefly and maps unavailable`() = scenario {
        val harness = Harness(clock).connect()
        val detail = ContactDetailStateHolder(contact(name = "Alice"), harness.dependencies, scope)
        val job = scope.launch { detail.shareViaAdvert() }
        settle()
        assertTrue(detail.state.value.showShareSuccess)
        clock.advanceBy(1500.milliseconds)
        job.join()
        assertFalse(detail.state.value.showShareSuccess)
        harness.contactService.shareBehavior = { throw NodesContactFailure.ShareContactUnavailable() }
        detail.shareViaAdvert()
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_sharecontactunavailable), detail.state.value.errorMessage)
        assertFalse(detail.state.value.isSharing)
    }

    @Test
    fun `ping matches the trace tag and stamps last heard`() = scenario {
        val harness = Harness(clock).connect(device = Fixtures.device(pathHashMode = 1u))
        val relay = contact(name = "Relay", type = ContactType.REPEATER, publicKey = Fixtures.key(0x10, 0x20, 0x30))
        harness.trace.behavior = { tag, _, _ ->
            harness.adverts.broadcaster.emit(NodesAdvertEvent.TraceSnrObserved(tag + 1u, 1.0, 1.0))
            harness.adverts.broadcaster.emit(NodesAdvertEvent.TraceSnrObserved(tag, 6.5, -2.25))
            FakeContactService.sentInfo(1000u)
        }
        val detail = ContactDetailStateHolder(relay, harness.dependencies, scope, pingHelper = PingHelper(harness.dependencies) { 77u })
        detail.pingRepeater()
        val result = assertIs<PingResult.Success>(detail.state.value.pingResult)
        assertEquals(-2.25, result.snrThere)
        assertEquals(6.5, result.snrBack)
        assertEquals(Triple(77u, 1u.toUByte(), Bytes.of(0x10, 0x20)), harness.trace.sent.single())
        assertEquals(relay.publicKey, harness.store.touched.single().first)
        assertFalse(detail.state.value.isPinging)
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_pingsuccessannouncement, 0L), harness.announcer.messages.last())
    }

    @Test
    fun `ping without a response times out to no response`() = scenario {
        val harness = Harness(clock).connect()
        harness.timeouts.zeroHop = 5.0
        val detail = ContactDetailStateHolder(contact(name = "Relay", type = ContactType.REPEATER), harness.dependencies, scope)
        val job = scope.launch { detail.pingRepeater() }
        settle()
        assertTrue(detail.state.value.isPinging)
        clock.advanceBy(5.seconds)
        job.join()
        assertEquals(PingResult.Error(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_pingnoresponse)), detail.state.value.pingResult)
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_pingfailureannouncement), harness.announcer.messages.last())
        assertTrue(harness.store.touched.isEmpty())
    }

    @Test
    fun `only this contact's discovery push resolves the screen`() = scenario {
        val harness = Harness(clock).connect()
        val relay = contact(radioId = checkNotNull(harness.session.radioId), name = "Relay", publicKey = Fixtures.key(0xAB, 0xCD))
        val detail = ContactDetailStateHolder(relay, harness.dependencies, scope)
        val screen = scope.launch { detail.run() }
        settle()
        detail.path.discoverPath(relay)
        settle()
        assertTrue(detail.path.state.value.isDiscovering)
        harness.adverts.broadcaster.emit(NodesAdvertEvent.PathDiscoveryResponse(PathInfo(Bytes.of(0x99), 0x01u, Bytes.of(0x11), 0u, Bytes.EMPTY)))
        settle()
        assertTrue(detail.path.state.value.isDiscovering)
        harness.adverts.broadcaster.emit(NodesAdvertEvent.PathDiscoveryResponse(PathInfo(Bytes.of(0xAB), 0x02u, Bytes.of(0x11, 0x22), 0u, Bytes.EMPTY)))
        settle()
        assertEquals(PathDiscoveryResult.Success(2), detail.path.state.value.discoveryResult)
        screen.cancel()
        detail.onDisappear()
    }

    @Test
    fun `nickname save passes the edited text and ends editing`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId), name = "Alice")
        harness.store.contacts += alice
        val detail = ContactDetailStateHolder(alice, harness.dependencies, scope)
        detail.beginEditingNickname()
        detail.setNickname("Ally")
        detail.saveNickname()
        assertEquals("prefs:nickname=Ally,isBlocked=null", harness.contactService.calls.single())
        assertEquals("Ally", detail.state.value.contact.nickname)
        assertFalse(detail.state.value.isEditingNickname)
    }

    @Test
    fun `avatar save rejects an undecodable image and stores bytes otherwise`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId), name = "Alice")
        harness.store.contacts += alice
        val detail = ContactDetailStateHolder(alice, harness.dependencies, scope)
        detail.saveAvatar(null)
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_avatar_invalidimage), detail.state.value.errorMessage)
        detail.saveAvatar(Bytes.of(1, 2, 3))
        assertEquals(Bytes.of(1, 2, 3), detail.state.value.contact.avatarImageData)
        detail.removeAvatar()
        assertNull(detail.state.value.contact.avatarImageData)
        assertEquals(listOf("avatar:3", "avatar:null"), harness.contactService.calls)
        assertFalse(detail.state.value.isSavingAvatar)
        assertEquals(512 to 256, AvatarProcessing.targetSize(2048.0, 1024.0))
        assertEquals(300 to 200, AvatarProcessing.targetSize(300.0, 200.0))
        assertNull(AvatarProcessing.targetSize(0.0, 0.0))
    }

    @Test
    fun `avatar unavailable service and changed radio never report a no-op as saved`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId))
        val detail = ContactDetailStateHolder(alice, harness.dependencies, scope)
        harness.session.contacts = null
        assertFalse(detail.saveAvatar(Bytes.of(1)))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_detail_error_servicesunavailable), detail.state.value.errorMessage)
        harness.session.contacts = harness.contactService
        harness.session.radioId = Fixtures.radio()
        assertFalse(detail.removeAvatar())
        assertTrue(harness.contactService.avatars.isEmpty())
        assertFalse(detail.state.value.isSavingAvatar)
    }

    @Test
    fun `avatar encoding and save failures preserve the old image and reset saving`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId)).copy(avatarImageData = Bytes.of(9))
        harness.store.contacts += alice
        val detail = ContactDetailStateHolder(alice, harness.dependencies, scope)
        assertFalse(detail.saveAvatar { null })
        assertEquals(alice, detail.state.value.contact)
        assertTrue(harness.contactService.avatars.isEmpty())
        harness.contactService.avatarBehavior = { _, _ -> error("disk full") }
        assertFalse(detail.saveAvatar(Bytes.of(1)))
        assertEquals(NodesMessage.Text("failed: disk full"), detail.state.value.errorMessage)
        assertEquals(alice, detail.state.value.contact)
        assertFalse(detail.state.value.isSavingAvatar)
    }

    @Test
    fun `avatar cancellation during encoding or persistence resets saving and propagates`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId))
        val detail = ContactDetailStateHolder(alice, harness.dependencies, scope)
        val encodeGate = CompletableDeferred<Bytes?>()
        val encoding = scope.launch { detail.saveAvatar { encodeGate.await() } }
        settle()
        assertTrue(detail.state.value.isSavingAvatar)
        assertFalse(detail.removeAvatar(), "A second operation cannot overlap encoding")
        encoding.cancel()
        encoding.join()
        assertTrue(encoding.isCancelled)
        assertFalse(detail.state.value.isSavingAvatar)
        assertTrue(harness.contactService.avatars.isEmpty())
        harness.contactService.avatarBehavior = { _, _ -> CompletableDeferred<Unit>().await() }
        val saving = scope.launch { detail.saveAvatar(Bytes.of(1)) }
        settle()
        assertTrue(detail.state.value.isSavingAvatar)
        saving.cancel()
        saving.join()
        assertTrue(saving.isCancelled)
        assertFalse(detail.state.value.isSavingAvatar)
        assertNull(detail.state.value.errorMessage)
    }

    @Test
    fun `radio changing during encoding rejects the result before persistence`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId))
        val detail = ContactDetailStateHolder(alice, harness.dependencies, scope)
        assertFalse(detail.saveAvatar {
            harness.session.radioId = Fixtures.radio()
            Bytes.of(1)
        })
        assertTrue(harness.contactService.avatars.isEmpty())
        assertEquals(alice, detail.state.value.contact)
        assertFalse(detail.state.value.isSavingAvatar)
    }

    @Test
    fun `presentation describes routes, actions and the V-contact`() = scenario {
        val harness = Harness(clock).connect()
        val self = Fixtures.repeated(0x42)
        harness.session.device = Fixtures.device(checkNotNull(harness.session.radioId), publicKey = self)
        val vContact = contact(name = "V", publicKey = checkNotNull(VContactIdentity.publicKey(self)))
        assertTrue(ContactDetailStateHolder(vContact, harness.dependencies, scope).isVContact)
        val routed = contact(name = "R", outPathLength = 0x42u, outPath = Bytes.of(0xA1, 0xB2, 0xC3, 0xD4))
        val display = ContactDetailPresentation.pathDisplayWithNames(routed) { hop -> if (hop == Bytes.of(0xA1, 0xB2)) "Hill" else null }
        assertEquals(NodesMessage.Text("Hill \u2192 C3D4"), display)
        assertEquals(display, ContactDetailPresentation.routeDisplayText(routed, display))
        assertEquals("A1B2,C3D4", ContactDetailPresentation.routeIdPrefixes(routed))
        val flood = contact(name = "F", outPathLength = 0xFFu)
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_flood), ContactDetailPresentation.routeDisplayText(flood, display))
        assertTrue(ContactDetailPresentation.resetPathDisabled(flood, isSettingPath = false))
        assertFalse(ContactDetailPresentation.radioEnabled(DeviceConnectionState.SYNCING))
        assertEquals(
            listOf(DetailAction.TELEMETRY, DetailAction.SAVED_HISTORY, DetailAction.SHARE_QR, DetailAction.SHARE_VIA_ADVERT, DetailAction.FAVORITE),
            ContactDetailPresentation.actions(contact(name = "C"), showFromDirectChat = true),
        )
        assertEquals(DetailAction.JOIN_ROOM, ContactDetailPresentation.actions(contact(name = "Room", type = ContactType.ROOM), false).first())
    }

    @Test
    fun `sheet router queues follow-up sheets until dismissal`() {
        val router = DetailSheetRouter()
        val relay = contact(name = "Relay", type = ContactType.REPEATER)
        router.showTelemetry(relay)
        assertEquals(DetailSheet.NodeAuth, router.activeSheet)
        val session = com.meshcoreone.android.core.model.RemoteNodeSessionDTO(
            radioId = relay.radioId, publicKey = relay.publicKey, name = "Relay",
            role = com.meshcoreone.android.core.model.RemoteNodeRole.REPEATER,
        )
        router.onTelemetryAuthenticated(relay, session)
        assertNull(router.activeSheet)
        router.onSheetDismissed()
        assertEquals(DetailSheet.RepeaterStatus(session), router.activeSheet)
        router.showTelemetry(contact(name = "Chat"))
        assertIs<DetailSheet.NodeTelemetry>(router.activeSheet)
    }
}
