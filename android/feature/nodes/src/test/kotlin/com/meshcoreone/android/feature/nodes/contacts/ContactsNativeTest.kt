// AndroidOnly: WP-311 Native coverage of nodes-list sync, delete, favorite/block/nickname, load races and row actions.
package com.meshcoreone.android.feature.nodes.contacts

import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.l10n.R
import com.meshcoreone.android.core.model.VContactIdentity
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.nodes.deps.NodesContactFailure
import com.meshcoreone.android.feature.nodes.deps.NodesMessage
import com.meshcoreone.android.feature.nodes.deps.SyncProgress
import com.meshcoreone.android.feature.nodes.model.NodeSegment
import com.meshcoreone.android.feature.nodes.model.NodeSortOrder
import com.meshcoreone.android.feature.nodes.support.Fixtures
import com.meshcoreone.android.feature.nodes.support.Fixtures.contact
import com.meshcoreone.android.feature.nodes.support.Harness
import com.meshcoreone.android.feature.nodes.support.FakeAdvertisementService
import com.meshcoreone.android.feature.nodes.support.scenario
import com.meshcoreone.android.feature.nodes.support.settle
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import org.junit.Test

class ContactsNativeTest {
    @Test
    fun `sync flags the advert pipeline, streams progress and reloads`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val release = CompletableDeferred<Unit>()
        harness.contactService.sync = {
            harness.contactService.progress.emit(SyncProgress(3, 10))
            release.await()
            harness.store.contacts += contact(radioId = radio, name = "Synced")
        }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val job = scope.launch { holder.syncContacts(radio) }
        settle()
        assertTrue(holder.state.value.isSyncing)
        assertEquals(SyncProgress(3, 10), holder.state.value.syncProgress)
        holder.syncContacts(radio) // re-trigger while syncing is a no-op
        assertEquals(1, harness.contactService.calls.count { it == "sync" })
        release.complete(Unit)
        job.join()
        val state = holder.state.value
        assertFalse(state.isSyncing)
        assertNull(state.syncProgress)
        assertEquals(listOf("Synced"), state.contacts.map { it.name })
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
        assertEquals(0, harness.contactService.progress.subscriberCount)
    }

    @Test
    fun `cancelled sync is not reported and still resets the advert flag`() = scenario {
        val harness = Harness(clock).connect()
        harness.contactService.sync = { CompletableDeferred<Unit>().await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val job = scope.launch { holder.syncContacts(checkNotNull(harness.session.radioId)) }
        settle()
        job.cancel()
        job.join()
        assertNull(holder.state.value.errorMessage)
        assertFalse(holder.state.value.isSyncing)
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
    }

    @Test
    fun `held sync resets only its original sessions advert pipeline`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val release = CompletableDeferred<Unit>()
        harness.contactService.sync = { release.await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val syncing = scope.launch { holder.syncContacts(radio) }
        settle()
        val replacement = FakeAdvertisementService()
        harness.session.adverts = replacement
        release.complete(Unit)
        syncing.join()
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
        assertTrue(replacement.syncingFlags.isEmpty())
        assertEquals(0, harness.contactService.progress.subscriberCount)
        assertFalse(holder.state.value.isSyncing)
    }

    @Test
    fun `held same-radio sync success cannot reload through the replacement graph`() = verifySyncReplacement("success")

    @Test
    fun `held same-radio sync progress cannot overwrite replacement progress`() = verifySyncReplacement("progress")

    @Test
    fun `held same-radio sync ordinary failure cannot fail or stop the replacement sync`() = verifySyncReplacement("failure")

    private fun verifySyncReplacement(outcome: String) = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val admitted = contact(radioId = radio, name = "Admitted")
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.upsert(admitted)
        val oldEntered = CompletableDeferred<Unit>()
        val oldRelease = CompletableDeferred<Unit>()
        harness.contactService.sync = {
            harness.contactService.progress.emit(SyncProgress(1, 10))
            oldEntered.complete(Unit)
            oldRelease.await()
            when (outcome) {
                "failure" -> error("old graph failed")
                "progress" -> harness.contactService.progress.emit(SyncProgress(99, 100))
            }
        }
        val oldSync = scope.launch { holder.syncContacts(radio) }
        oldEntered.await()
        settle()
        assertEquals(SyncProgress(1, 10), holder.state.value.syncProgress)
        val replacement = Harness(clock).connect(radio)
        val newEntered = CompletableDeferred<Unit>()
        val newRelease = CompletableDeferred<Unit>()
        var newReads = 0
        replacement.store.fetchContactsGate = { newReads++ }
        replacement.contactService.sync = {
            replacement.contactService.progress.emit(SyncProgress(2, 20))
            newEntered.complete(Unit)
            newRelease.await()
        }
        harness.session.contacts = replacement.contactService
        harness.session.adverts = replacement.adverts
        harness.session.device = replacement.session.device
        harness.session.servicesStore = replacement.store
        harness.session.offlineStore = replacement.store
        holder.onConnectionChanged()
        assertEquals(listOf(admitted), holder.state.value.contacts)
        assertFalse(holder.state.value.isSyncing)
        assertNull(holder.state.value.syncProgress)
        assertEquals(0, harness.contactService.progress.subscriberCount)

        val newSync = scope.launch { holder.syncContacts(radio) }
        newEntered.await()
        settle()
        assertEquals(SyncProgress(2, 20), holder.state.value.syncProgress)
        harness.contactService.progress.emit(SyncProgress(88, 100))
        oldRelease.complete(Unit)
        oldSync.join()
        settle()
        assertTrue(holder.state.value.isSyncing, "Only B may finish B's active sync")
        assertNull(holder.state.value.errorMessage)
        assertEquals(SyncProgress(2, 20), holder.state.value.syncProgress)
        assertEquals(listOf(admitted), holder.state.value.contacts)
        assertEquals(0, newReads, "Old success must not fetch through B")
        assertEquals(listOf("sync"), replacement.contactService.calls)
        newRelease.complete(Unit)
        newSync.join()
        assertFalse(holder.state.value.isSyncing)
        assertNull(holder.state.value.syncProgress)
        assertNull(holder.state.value.errorMessage)
        assertEquals(listOf(admitted), holder.state.value.contacts, "Radio-owned admission survives a graph replacement and stale fetch")
        assertEquals(1, newReads)
        assertEquals(listOf(true, false), harness.adverts.syncingFlags)
        assertEquals(listOf(true, false), replacement.adverts.syncingFlags)
        assertEquals(0, replacement.contactService.progress.subscriberCount)
    }

    @Test
    fun `sync failure surfaces the user-facing message`() = scenario {
        val harness = Harness(clock).connect()
        harness.contactService.sync = { error("radio gone") }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.syncContacts(checkNotNull(harness.session.radioId))
        assertEquals(NodesMessage.Text("failed: radio gone"), holder.state.value.errorMessage)
        assertFalse(holder.state.value.isSyncing)
    }

    @Test
    fun `confirmed delete keeps a spinner until the ack then masks the row`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val alice = contact(radioId = radio, name = "Alice")
        harness.store.contacts += alice
        val ack = CompletableDeferred<Unit>()
        harness.contactService.removeContact = { _, _ -> ack.await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        val job = scope.launch { holder.deleteContact(alice) }
        settle()
        assertTrue(alice.id in holder.state.value.deletingIds)
        assertEquals(listOf(alice.id), holder.state.value.contacts.map { it.id })
        holder.deleteContact(alice) // pending guard
        assertEquals(1, harness.contactService.calls.count { it == "remove" })
        ack.complete(Unit)
        job.join()
        val state = holder.state.value
        assertTrue(state.contacts.isEmpty())
        assertTrue(alice.id in state.pendingRemovalIds)
        assertTrue(state.deletingIds.isEmpty())
        // A reload that no longer sees the row self-heals the mask.
        harness.store.contacts.clear()
        holder.loadContacts(radio)
        assertTrue(holder.state.value.pendingRemovalIds.isEmpty())
    }

    @Test
    fun `contact not found on the radio clears the orphaned local row`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId), name = "Alice")
        harness.contactService.removeContact = { _, _ -> throw NodesContactFailure.ContactNotFound() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.seed { it.copy(contacts = listOf(alice)) }
        holder.deleteContact(alice)
        assertEquals(listOf("remove", "removeLocal"), harness.contactService.calls)
        assertTrue(holder.state.value.contacts.isEmpty())
        assertNull(holder.state.value.errorMessage)
    }

    @Test
    fun `a silent radio times out after seven seconds and keeps the row`() = scenario {
        val harness = Harness(clock).connect()
        val alice = contact(radioId = checkNotNull(harness.session.radioId), name = "Alice")
        harness.contactService.removeContact = { _, _ -> CompletableDeferred<Unit>().await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.seed { it.copy(contacts = listOf(alice)) }
        val job = scope.launch { holder.deleteContact(alice) }
        settle()
        clock.advanceBy(6.seconds)
        assertTrue(alice.id in holder.state.value.deletingIds)
        clock.advanceBy(1.seconds)
        job.join()
        val state = holder.state.value
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_viewmodel_removetimedout), state.errorMessage)
        assertEquals(listOf(alice.id), state.contacts.map { it.id })
        assertTrue(state.deletingIds.isEmpty() && state.pendingRemovalIds.isEmpty())
    }

    @Test
    fun `toggle favorite flips on the radio and reloads`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val alice = contact(radioId = radio, name = "Alice")
        harness.store.contacts += alice
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        holder.toggleFavorite(alice)
        assertEquals(listOf("favorite:true"), harness.contactService.calls)
        assertTrue(holder.state.value.contacts.single().isFavorite)
        assertNull(holder.state.value.togglingFavoriteId)
    }

    @Test
    fun `empty nickname is passed as unchanged and block toggles`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val alice = contact(radioId = radio, name = "Alice", nickname = "Al")
        harness.store.contacts += alice
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        holder.updateNickname(alice, "")
        holder.toggleBlocked(alice)
        assertEquals(listOf("prefs:nickname=null,isBlocked=null", "prefs:nickname=null,isBlocked=true"), harness.contactService.calls)
        assertTrue(holder.state.value.contacts.single().isBlocked)
    }

    @Test
    fun `a slower load cannot overwrite a newer one`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val gate = CompletableDeferred<Unit>()
        harness.store.contacts += contact(radioId = radio, name = "Old")
        harness.store.fetchContactsGate = { gate.await() }
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val slow = scope.launch { holder.loadContacts(radio) }
        settle()
        harness.store.fetchContactsGate = null
        harness.store.contacts.clear()
        harness.store.contacts += contact(radioId = radio, name = "New")
        holder.loadContacts(radio)
        gate.complete(Unit)
        slow.join()
        assertEquals(listOf("New"), holder.state.value.contacts.map { it.name })
        assertTrue(holder.state.value.hasLoadedOnce)
    }

    @Test
    fun `a failed fetch still finishes loading and the hop fallback is best effort`() = scenario {
        val harness = Harness(clock).connect()
        harness.store.fetchContactsError = IllegalStateException("disk")
        harness.store.fetchDiscoveredError = IllegalStateException("volatile")
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(checkNotNull(harness.session.radioId))
        val state = holder.state.value
        assertEquals(NodesMessage.Text("failed: disk"), state.errorMessage)
        assertTrue(state.hasLoadedOnce)
        assertFalse(state.isLoading)
        assertTrue(state.inboundHopByKey.isEmpty())
    }

    @Test
    fun `inbound hop counts come from discovered nodes`() = scenario {
        val harness = Harness(clock).connect()
        val radio = checkNotNull(harness.session.radioId)
        val key = Fixtures.randomKey()
        harness.store.discovered += Fixtures.node(radioId = radio, publicKey = key, inboundHopCount = 4)
        harness.store.discovered += Fixtures.node(radioId = radio, publicKey = Fixtures.randomKey())
        val holder = ContactsStateHolder(harness.dependencies, scope)
        holder.loadContacts(radio)
        assertEquals(mapOf(key to 4L), holder.state.value.inboundHopByKey)
    }

    @Test
    fun `search matches a public key hex prefix case-insensitively`() = scenario {
        val holder = ContactsStateHolder(Harness(clock).dependencies, scope)
        val row = contact(name = "Zed", publicKey = Fixtures.key(0x0A, 0xBC))
        holder.seed { it.copy(contacts = listOf(row, contact(name = "Other", publicKey = Fixtures.key(0x11)))) }
        assertEquals(listOf("Zed"), holder.filteredContacts("0abc", NodeSegment.FAVORITES, NodeSortOrder.NAME, null).map { it.name })
    }

    @Test
    fun `list actions fall back to last heard without a location and gate refresh on readiness`() = scenario {
        val harness = Harness(clock).connect()
        val holder = ContactsStateHolder(harness.dependencies, scope)
        val actions = ContactListActions(holder, harness.dependencies)
        holder.seed {
            it.copy(contacts = listOf(contact(name = "A", lastModified = 1u), contact(name = "B", lastModified = 2u)))
        }
        assertEquals(listOf("B", "A"), actions.filteredContacts("", NodeSegment.CONTACTS, NodeSortOrder.DISTANCE, null).map { it.name })
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_list_searchpromptwithcount, 2), actions.searchPrompt)
        harness.session.state = DeviceConnectionState.CONNECTING
        assertEquals(ContactListActions.RefreshOutcome.ShowOfflineAlert, actions.refreshNodes())
        harness.session.state = DeviceConnectionState.DISCONNECTED
        actions.announceOfflineStateIfNeeded()
        assertEquals(listOf(NodesMessage.res(R.string.l10n_app_contacts_contacts_list_offlineannouncement)), harness.announcer.messages)
        assertEquals(NodeSegment.FAVORITES, ContactListActions.segmentAfterLoad(NodeSegment.CONTACTS, loaded = true, hasFavorites = true))
        assertEquals(NodeSegment.ROOMS, ContactListActions.segmentAfterLoad(NodeSegment.ROOMS, loaded = true, hasFavorites = true))
    }

    @Test
    fun `row actions follow edge, type, connection and pending state`() = scenario {
        val chat = contact(name = "Chat")
        val repeater = contact(name = "Rep", type = ContactType.REPEATER, isFavorite = true)
        val ready = DeviceConnectionState.READY
        val idle = ContactsState()
        val all = ContactRowActions.actions(chat, RowActionEdge.ALL, ready, null, idle).map { it.kind }
        assertEquals(listOf(RowActionKind.SEND_MESSAGE, RowActionKind.DELETE, RowActionKind.BLOCK, RowActionKind.FAVORITE), all)
        assertEquals(listOf(RowActionKind.UNFAVORITE), ContactRowActions.actions(repeater, RowActionEdge.LEADING, ready, null, idle).map { it.kind })
        assertEquals(listOf(RowActionKind.DELETE), ContactRowActions.actions(repeater, RowActionEdge.TRAILING, ready, null, idle).map { it.kind })
        val pending = ContactRowActions.actions(chat, RowActionEdge.TRAILING, ready, null, ContactsState(deletingIds = setOf(chat.id)))
        assertFalse(pending.first { it.kind == RowActionKind.DELETE }.enabled)
        assertTrue(ContactRowActions.actions(chat, RowActionEdge.ALL, DeviceConnectionState.CONNECTED, null, idle).none { it.enabled })
        val self = Fixtures.repeated(0x42)
        val vContact = contact(name = "V", publicKey = checkNotNull(VContactIdentity.publicKey(self)))
        assertTrue(ContactRowActions.actions(vContact, RowActionEdge.TRAILING, ready, self, idle).none { it.kind == RowActionKind.DELETE })
        assertFalse(ContactRowActions.allowsFullSwipe(RowActionEdge.TRAILING))
        assertTrue(ContactRowActions.allowsFullSwipe(RowActionEdge.LEADING))
    }

    @Test
    fun `row labels show direct, hop counts and flood`() {
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_direct), ContactRowPresentation.routeLabel(contact(outPathLength = 0u), null))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_hops, 2L), ContactRowPresentation.routeLabel(contact(outPathLength = 2u), null))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_hops, 3L), ContactRowPresentation.routeLabel(contact(outPathLength = 0xFFu), 3))
        assertEquals(NodesMessage.res(R.string.l10n_app_contacts_contacts_route_flood), ContactRowPresentation.routeLabel(contact(outPathLength = 0xFFu), null))
        assertEquals("0AB1", ContactRowPresentation.idPrefixHex(contact(publicKey = Fixtures.key(0x0A, 0xB1)), 2))
    }
}
